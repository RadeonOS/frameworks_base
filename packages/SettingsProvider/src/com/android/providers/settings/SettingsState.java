/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.settings;

import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import libcore.io.IoUtils;
import libcore.util.Objects;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * This class contains the state for one type of settings. It is responsible
 * for saving the state asynchronously to an XML file after a mutation and
 * loading the from an XML file on construction.
 * <p>
 * This class uses the same lock as the settings provider to ensure that
 * multiple changes made by the settings provider, e,g, upgrade, bulk insert,
 * etc, are atomically persisted since the asynchronous persistence is using
 * the same lock to grab the current state to write to disk.
 * </p>
 */
final class SettingsState {
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_PERSISTENCE = false;

    private static final String LOG_TAG = "SettingsState";

    private static final long WRITE_SETTINGS_DELAY_MILLIS = 200;
    private static final long MAX_WRITE_SETTINGS_DELAY_MILLIS = 2000;

    public static final int MAX_BYTES_PER_APP_PACKAGE_UNLIMITED = -1;
    public static final int MAX_BYTES_PER_APP_PACKAGE_LIMITED = 20000;

    public static final String SYSTEM_PACKAGE_NAME = "android";

    public static final int VERSION_UNDEFINED = -1;

    private static final String TAG_SETTINGS = "settings";
    private static final String TAG_SETTING = "setting";
    private static final String ATTR_PACKAGE = "package";

    private static final String ATTR_VERSION = "version";
    private static final String ATTR_ID = "id";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_VALUE = "value";

    private static final String NULL_VALUE = "null";

    private final Object mLock;

    private final Handler mHandler = new MyHandler();

    @GuardedBy("mLock")
    private final ArrayMap<String, Setting> mSettings = new ArrayMap<>();

    @GuardedBy("mLock")
    private final ArrayMap<String, Integer> mPackageToMemoryUsage;

    @GuardedBy("mLock")
    private final int mMaxBytesPerAppPackage;

    @GuardedBy("mLock")
    private final File mStatePersistFile;

    public final int mKey;

    @GuardedBy("mLock")
    private int mVersion = VERSION_UNDEFINED;

    @GuardedBy("mLock")
    private long mLastNotWrittenMutationTimeMillis;

    @GuardedBy("mLock")
    private boolean mDirty;

    @GuardedBy("mLock")
    private boolean mWriteScheduled;

    @GuardedBy("mLock")
    private long mNextId;

    public SettingsState(Object lock, File file, int key, int maxBytesPerAppPackage) {
        // It is important that we use the same lock as the settings provider
        // to ensure multiple mutations on this state are atomicaly persisted
        // as the async persistence should be blocked while we make changes.
        mLock = lock;
        mStatePersistFile = file;
        mKey = key;
        if (maxBytesPerAppPackage == MAX_BYTES_PER_APP_PACKAGE_LIMITED) {
            mMaxBytesPerAppPackage = maxBytesPerAppPackage;
            mPackageToMemoryUsage = new ArrayMap<>();
        } else {
            mMaxBytesPerAppPackage = maxBytesPerAppPackage;
            mPackageToMemoryUsage = null;
        }
        synchronized (mLock) {
            readStateSyncLocked();
        }
    }

    // The settings provider must hold its lock when calling here.
    public int getVersionLocked() {
        return mVersion;
    }

    // The settings provider must hold its lock when calling here.
    public void setVersionLocked(int version) {
        if (version == mVersion) {
            return;
        }
        mVersion = version;

        scheduleWriteIfNeededLocked();
    }

    // The settings provider must hold its lock when calling here.
    public void onPackageRemovedLocked(String packageName) {
        boolean removedSomething = false;

        final int settingCount = mSettings.size();
        for (int i = settingCount - 1; i >= 0; i--) {
            String name = mSettings.keyAt(i);
            // Settings defined by use are never dropped.
            if (Settings.System.PUBLIC_SETTINGS.contains(name)
                    || Settings.System.PRIVATE_SETTINGS.contains(name)) {
                continue;
            }
            Setting setting = mSettings.valueAt(i);
            if (packageName.equals(setting.packageName)) {
                mSettings.removeAt(i);
                removedSomething = true;
            }
        }

        if (removedSomething) {
            scheduleWriteIfNeededLocked();
        }
    }

    // The settings provider must hold its lock when calling here.
    public List<String> getSettingNamesLocked() {
        ArrayList<String> names = new ArrayList<>();
        final int settingsCount = mSettings.size();
        for (int i = 0; i < settingsCount; i++) {
            String name = mSettings.keyAt(i);
            names.add(name);
        }
        return names;
    }

    // The settings provider must hold its lock when calling here.
    public Setting getSettingLocked(String name) {
        if (TextUtils.isEmpty(name)) {
            return null;
        }
        return mSettings.get(name);
    }

    // The settings provider must hold its lock when calling here.
    public boolean updateSettingLocked(String name, String value, String packageName) {
        if (!hasSettingLocked(name)) {
            return false;
        }

        return insertSettingLocked(name, value, packageName);
    }

    // The settings provider must hold its lock when calling here.
    public boolean insertSettingLocked(String name, String value, String packageName) {
        if (TextUtils.isEmpty(name)) {
            return false;
        }

        Setting oldState = mSettings.get(name);
        String oldValue = (oldState != null) ? oldState.value : null;

        if (oldState != null) {
            if (!oldState.update(value, packageName)) {
                return false;
            }
        } else {
            Setting state = new Setting(name, value, packageName);
            mSettings.put(name, state);
        }

        updateMemoryUsagePerPackageLocked(packageName, oldValue, value);

        scheduleWriteIfNeededLocked();

        return true;
    }

    // The settings provider must hold its lock when calling here.
    public void persistSyncLocked() {
        mHandler.removeMessages(MyHandler.MSG_PERSIST_SETTINGS);
        doWriteState();
    }

    // The settings provider must hold its lock when calling here.
    public boolean deleteSettingLocked(String name) {
        if (TextUtils.isEmpty(name) || !hasSettingLocked(name)) {
            return false;
        }

        Setting oldState = mSettings.remove(name);

        updateMemoryUsagePerPackageLocked(oldState.packageName, oldState.value, null);

        scheduleWriteIfNeededLocked();

        return true;
    }

    // The settings provider must hold its lock when calling here.
    public void destroyLocked(Runnable callback) {
        mHandler.removeMessages(MyHandler.MSG_PERSIST_SETTINGS);
        if (callback != null) {
            if (mDirty) {
                // Do it without a delay.
                mHandler.obtainMessage(MyHandler.MSG_PERSIST_SETTINGS,
                        callback).sendToTarget();
                return;
            }
            callback.run();
        }
    }

    private void updateMemoryUsagePerPackageLocked(String packageName, String oldValue,
            String newValue) {
        if (mMaxBytesPerAppPackage == MAX_BYTES_PER_APP_PACKAGE_UNLIMITED) {
            return;
        }

        if (SYSTEM_PACKAGE_NAME.equals(packageName)) {
            return;
        }

        final int oldValueSize = (oldValue != null) ? oldValue.length() : 0;
        final int newValueSize = (newValue != null) ? newValue.length() : 0;
        final int deltaSize = newValueSize - oldValueSize;

        Integer currentSize = mPackageToMemoryUsage.get(packageName);
        final int newSize = Math.max((currentSize != null)
                ? currentSize + deltaSize : deltaSize, 0);

        if (newSize > mMaxBytesPerAppPackage) {
            throw new IllegalStateException("You are adding too many system settings. "
                    + "You should stop using system settings for app specific data"
                    + " package: " + packageName);
        }

        if (DEBUG) {
            Slog.i(LOG_TAG, "Settings for package: " + packageName
                    + " size: " + newSize + " bytes.");
        }

        mPackageToMemoryUsage.put(packageName, newSize);
    }

    private boolean hasSettingLocked(String name) {
        return mSettings.indexOfKey(name) >= 0;
    }

    private void scheduleWriteIfNeededLocked() {
        // If dirty then we have a write already scheduled.
        if (!mDirty) {
            mDirty = true;
            writeStateAsyncLocked();
        }
    }

    private void writeStateAsyncLocked() {
        final long currentTimeMillis = SystemClock.uptimeMillis();

        if (mWriteScheduled) {
            mHandler.removeMessages(MyHandler.MSG_PERSIST_SETTINGS);

            // If enough time passed, write without holding off anymore.
            final long timeSinceLastNotWrittenMutationMillis = currentTimeMillis
                    - mLastNotWrittenMutationTimeMillis;
            if (timeSinceLastNotWrittenMutationMillis >= MAX_WRITE_SETTINGS_DELAY_MILLIS) {
                mHandler.obtainMessage(MyHandler.MSG_PERSIST_SETTINGS).sendToTarget();
                return;
            }

            // Hold off a bit more as settings are frequently changing.
            final long maxDelayMillis = Math.max(mLastNotWrittenMutationTimeMillis
                    + MAX_WRITE_SETTINGS_DELAY_MILLIS - currentTimeMillis, 0);
            final long writeDelayMillis = Math.min(WRITE_SETTINGS_DELAY_MILLIS, maxDelayMillis);

            Message message = mHandler.obtainMessage(MyHandler.MSG_PERSIST_SETTINGS);
            mHandler.sendMessageDelayed(message, writeDelayMillis);
        } else {
            mLastNotWrittenMutationTimeMillis = currentTimeMillis;
            Message message = mHandler.obtainMessage(MyHandler.MSG_PERSIST_SETTINGS);
            mHandler.sendMessageDelayed(message, WRITE_SETTINGS_DELAY_MILLIS);
            mWriteScheduled = true;
        }
    }

    private void doWriteState() {
        if (DEBUG_PERSISTENCE) {
            Slog.i(LOG_TAG, "[PERSIST START]");
        }

        AtomicFile destination = new AtomicFile(mStatePersistFile);

        final int version;
        final ArrayMap<String, Setting> settings;

        synchronized (mLock) {
            version = mVersion;
            settings = new ArrayMap<>(mSettings);
            mDirty = false;
            mWriteScheduled = false;
        }

        FileOutputStream out = null;
        try {
            out = destination.startWrite();

            XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(out, "utf-8");
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_SETTINGS);
            serializer.attribute(null, ATTR_VERSION, String.valueOf(version));

            final int settingCount = settings.size();
            for (int i = 0; i < settingCount; i++) {
                Setting setting = settings.valueAt(i);

                serializer.startTag(null, TAG_SETTING);
                serializer.attribute(null, ATTR_ID, setting.getId());
                serializer.attribute(null, ATTR_NAME, setting.getName());
                serializer.attribute(null, ATTR_VALUE, packValue(setting.getValue()));
                serializer.attribute(null, ATTR_PACKAGE, packValue(setting.getPackageName()));
                serializer.endTag(null, TAG_SETTING);

                if (DEBUG_PERSISTENCE) {
                    Slog.i(LOG_TAG, "[PERSISTED]" + setting.getName() + "=" + setting.getValue());
                }
            }

            serializer.endTag(null, TAG_SETTINGS);
            serializer.endDocument();
            destination.finishWrite(out);

            if (DEBUG_PERSISTENCE) {
                Slog.i(LOG_TAG, "[PERSIST END]");
            }

        } catch (IOException e) {
            Slog.wtf(LOG_TAG, "Failed to write settings, restoring backup", e);
            destination.failWrite(out);
        } finally {
            IoUtils.closeQuietly(out);
        }
    }

    private void readStateSyncLocked() {
        FileInputStream in;
        if (!mStatePersistFile.exists()) {
            return;
        }
        try {
            in = new FileInputStream(mStatePersistFile);
        } catch (FileNotFoundException fnfe) {
            Slog.i(LOG_TAG, "No settings state");
            return;
        }
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, null);
            parseStateLocked(parser);
        } catch (XmlPullParserException | IOException ise) {
            throw new IllegalStateException("Failed parsing settings file: "
                    + mStatePersistFile , ise);
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    private void parseStateLocked(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        parser.next();
        skipEmptyTextTags(parser);
        expect(parser, XmlPullParser.START_TAG, TAG_SETTINGS);

        mVersion = Integer.parseInt(parser.getAttributeValue(null, ATTR_VERSION));

        parser.next();

        while (parseSettingLocked(parser)) {
            parser.next();
        }

        skipEmptyTextTags(parser);
        expect(parser, XmlPullParser.END_TAG, TAG_SETTINGS);
    }

    private boolean parseSettingLocked(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        skipEmptyTextTags(parser);
        if (!accept(parser, XmlPullParser.START_TAG, TAG_SETTING)) {
            return false;
        }

        String id = parser.getAttributeValue(null, ATTR_ID);
        String name = parser.getAttributeValue(null, ATTR_NAME);
        String value = parser.getAttributeValue(null, ATTR_VALUE);
        String packageName = parser.getAttributeValue(null, ATTR_PACKAGE);
        mSettings.put(name, new Setting(name, unpackValue(value),
                unpackValue(packageName), id));

        if (DEBUG_PERSISTENCE) {
            Slog.i(LOG_TAG, "[RESTORED] " + name + "=" + value);
        }

        parser.next();

        skipEmptyTextTags(parser);
        expect(parser, XmlPullParser.END_TAG, TAG_SETTING);

        return true;
    }

    private void expect(XmlPullParser parser, int type, String tag)
            throws IOException, XmlPullParserException {
        if (!accept(parser, type, tag)) {
            throw new XmlPullParserException("Expected event: " + type
                    + " and tag: " + tag + " but got event: " + parser.getEventType()
                    + " and tag:" + parser.getName());
        }
    }

    private void skipEmptyTextTags(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        while (accept(parser, XmlPullParser.TEXT, null)
                && parser.isWhitespace()) {
            parser.next();
        }
    }

    private boolean accept(XmlPullParser parser, int type, String tag)
            throws IOException, XmlPullParserException {
        if (parser.getEventType() != type) {
            return false;
        }
        if (tag != null) {
            if (!tag.equals(parser.getName())) {
                return false;
            }
        } else if (parser.getName() != null) {
            return false;
        }
        return true;
    }

    private final class MyHandler extends Handler {
        public static final int MSG_PERSIST_SETTINGS = 1;

        public MyHandler() {
            super(BackgroundThread.getHandler().getLooper());
        }

        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_PERSIST_SETTINGS: {
                    Runnable callback = (Runnable) message.obj;
                    doWriteState();
                    if (callback != null) {
                        callback.run();
                    }
                }
                break;
            }
        }
    }

    private static String packValue(String value) {
        if (value == null) {
            return NULL_VALUE;
        }
        return value;
    }

    private static String unpackValue(String value) {
        if (NULL_VALUE.equals(value)) {
            return null;
        }
        return value;
    }

    public final class Setting {
        private String name;
        private String value;
        private String packageName;
        private String id;

        public Setting(String name, String value, String packageName) {
            init(name, value, packageName, String.valueOf(mNextId++));
        }

        public Setting(String name, String value, String packageName, String id) {
            mNextId = Math.max(mNextId, Long.valueOf(id) + 1);
            init(name, value, packageName, id);
        }

        private void init(String name, String value, String packageName, String id) {
            this.name = name;
            this.value = value;
            this.packageName = packageName;
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public String getValue() {
            return value;
        }

        public String getPackageName() {
            return packageName;
        }

        public String getId() {
            return id;
        }

        public boolean update(String value, String packageName) {
            if (Objects.equal(value, this.value)) {
                return false;
            }
            this.value = value;
            this.packageName = packageName;
            this.id = String.valueOf(mNextId++);
            return true;
        }
    }
}