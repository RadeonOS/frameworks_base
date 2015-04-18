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

package android.os.storage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.mtp.MtpStorage;
import android.net.Uri;
import android.os.Environment;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.DebugUtils;
import android.util.SparseArray;

import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

import java.io.CharArrayWriter;
import java.io.File;
import java.util.Comparator;
import java.util.Objects;

/**
 * Information about a storage volume that may be mounted. A volume may be a
 * partition on a physical {@link DiskInfo}, an emulated volume above some other
 * storage medium, or a standalone container like an ASEC or OBB.
 *
 * @hide
 */
public class VolumeInfo implements Parcelable {
    public static final String EXTRA_VOLUME_ID = "android.os.storage.extra.VOLUME_ID";

    /** Stub volume representing internal private storage */
    public static final String ID_PRIVATE_INTERNAL = "private";
    /** Real volume representing internal emulated storage */
    public static final String ID_EMULATED_INTERNAL = "emulated";

    public static final int TYPE_PUBLIC = 0;
    public static final int TYPE_PRIVATE = 1;
    public static final int TYPE_EMULATED = 2;
    public static final int TYPE_ASEC = 3;
    public static final int TYPE_OBB = 4;

    public static final int STATE_UNMOUNTED = 0;
    public static final int STATE_MOUNTING = 1;
    public static final int STATE_MOUNTED = 2;
    public static final int STATE_FORMATTING = 3;
    public static final int STATE_UNMOUNTING = 4;
    public static final int STATE_UNMOUNTABLE = 5;
    public static final int STATE_REMOVED = 6;

    public static final int FLAG_PRIMARY = 1 << 0;
    public static final int FLAG_VISIBLE = 1 << 1;

    public static final int USER_FLAG_INITED = 1 << 0;
    public static final int USER_FLAG_SNOOZED = 1 << 1;

    private static SparseArray<String> sStateToEnvironment = new SparseArray<>();
    private static ArrayMap<String, String> sEnvironmentToBroadcast = new ArrayMap<>();

    private static final Comparator<VolumeInfo>
            sDescriptionComparator = new Comparator<VolumeInfo>() {
        @Override
        public int compare(VolumeInfo lhs, VolumeInfo rhs) {
            if (VolumeInfo.ID_PRIVATE_INTERNAL.equals(lhs.getId())) {
                return -1;
            } else if (lhs.getDescription() == null) {
                return 1;
            } else if (rhs.getDescription() == null) {
                return -1;
            } else {
                return lhs.getDescription().compareTo(rhs.getDescription());
            }
        }
    };

    static {
        sStateToEnvironment.put(VolumeInfo.STATE_UNMOUNTED, Environment.MEDIA_UNMOUNTED);
        sStateToEnvironment.put(VolumeInfo.STATE_MOUNTING, Environment.MEDIA_CHECKING);
        sStateToEnvironment.put(VolumeInfo.STATE_MOUNTED, Environment.MEDIA_MOUNTED);
        sStateToEnvironment.put(VolumeInfo.STATE_FORMATTING, Environment.MEDIA_UNMOUNTED);
        sStateToEnvironment.put(VolumeInfo.STATE_UNMOUNTING, Environment.MEDIA_EJECTING);
        sStateToEnvironment.put(VolumeInfo.STATE_UNMOUNTABLE, Environment.MEDIA_UNMOUNTABLE);
        sStateToEnvironment.put(VolumeInfo.STATE_REMOVED, Environment.MEDIA_REMOVED);

        sEnvironmentToBroadcast.put(Environment.MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_UNMOUNTED);
        sEnvironmentToBroadcast.put(Environment.MEDIA_CHECKING, Intent.ACTION_MEDIA_CHECKING);
        sEnvironmentToBroadcast.put(Environment.MEDIA_MOUNTED, Intent.ACTION_MEDIA_MOUNTED);
        sEnvironmentToBroadcast.put(Environment.MEDIA_EJECTING, Intent.ACTION_MEDIA_EJECT);
        sEnvironmentToBroadcast.put(Environment.MEDIA_UNMOUNTABLE, Intent.ACTION_MEDIA_UNMOUNTABLE);
        sEnvironmentToBroadcast.put(Environment.MEDIA_REMOVED, Intent.ACTION_MEDIA_REMOVED);
    }

    /** vold state */
    public final String id;
    public final int type;
    public int flags = 0;
    public int userId = -1;
    public int state = STATE_UNMOUNTED;
    public String fsType;
    public String fsUuid;
    public String fsLabel;
    public String path;

    /** Framework state */
    public final int mtpIndex;
    public String diskId;
    public String nickname;
    public int userFlags = 0;

    public VolumeInfo(String id, int type, int mtpIndex) {
        this.id = Preconditions.checkNotNull(id);
        this.type = type;
        this.mtpIndex = mtpIndex;
    }

    public VolumeInfo(Parcel parcel) {
        id = parcel.readString();
        type = parcel.readInt();
        flags = parcel.readInt();
        userId = parcel.readInt();
        state = parcel.readInt();
        fsType = parcel.readString();
        fsUuid = parcel.readString();
        fsLabel = parcel.readString();
        path = parcel.readString();
        mtpIndex = parcel.readInt();
        diskId = parcel.readString();
        nickname = parcel.readString();
        userFlags = parcel.readInt();
    }

    public static @NonNull String getEnvironmentForState(int state) {
        final String envState = sStateToEnvironment.get(state);
        if (envState != null) {
            return envState;
        } else {
            return Environment.MEDIA_UNKNOWN;
        }
    }

    public static @Nullable String getBroadcastForEnvironment(String envState) {
        return sEnvironmentToBroadcast.get(envState);
    }

    public static @Nullable String getBroadcastForState(int state) {
        return getBroadcastForEnvironment(getEnvironmentForState(state));
    }

    public static @NonNull Comparator<VolumeInfo> getDescriptionComparator() {
        return sDescriptionComparator;
    }

    public @NonNull String getId() {
        return id;
    }

    public @Nullable String getDiskId() {
        return diskId;
    }

    public int getType() {
        return type;
    }

    public int getState() {
        return state;
    }

    public @Nullable String getFsUuid() {
        return fsUuid;
    }

    public @Nullable String getNickname() {
        return nickname;
    }

    public @Nullable String getDescription() {
        if (ID_PRIVATE_INTERNAL.equals(id)) {
            return Resources.getSystem().getString(com.android.internal.R.string.storage_internal);
        } else if (!TextUtils.isEmpty(nickname)) {
            return nickname;
        } else if (!TextUtils.isEmpty(fsLabel)) {
            return fsLabel;
        } else {
            return null;
        }
    }

    public boolean isPrimary() {
        return (flags & FLAG_PRIMARY) != 0;
    }

    public boolean isVisible() {
        return (flags & FLAG_VISIBLE) != 0;
    }

    public boolean isInited() {
        return (userFlags & USER_FLAG_INITED) != 0;
    }

    public boolean isSnoozed() {
        return (userFlags & USER_FLAG_SNOOZED) != 0;
    }

    public boolean isVisibleToUser(int userId) {
        if (type == TYPE_PUBLIC && userId == this.userId) {
            return isVisible();
        } else if (type == TYPE_EMULATED) {
            return isVisible();
        } else {
            return false;
        }
    }

    public File getPath() {
        return new File(path);
    }

    public File getPathForUser(int userId) {
        if (path == null) {
            return null;
        } else if (type == TYPE_PUBLIC && userId == this.userId) {
            return new File(path);
        } else if (type == TYPE_EMULATED) {
            return new File(path, Integer.toString(userId));
        } else {
            return null;
        }
    }

    public StorageVolume buildStorageVolume(Context context, int userId) {
        final boolean removable;
        final boolean emulated;
        final boolean allowMassStorage = false;
        final int mtpStorageId = MtpStorage.getStorageIdForIndex(mtpIndex);
        final String envState = getEnvironmentForState(state);

        File userPath = getPathForUser(userId);
        if (userPath == null) {
            userPath = new File("/dev/null");
        }

        String description = getDescription();
        if (description == null) {
            description = context.getString(android.R.string.unknownName);
        }

        long mtpReserveSize = 0;
        long maxFileSize = 0;

        if (type == TYPE_EMULATED) {
            emulated = true;
            mtpReserveSize = StorageManager.from(context).getStorageLowBytes(userPath);

            if (ID_EMULATED_INTERNAL.equals(id)) {
                removable = false;
            } else {
                removable = true;
            }

        } else if (type == TYPE_PUBLIC) {
            emulated = false;
            removable = true;

            if ("vfat".equals(fsType)) {
                maxFileSize = 4294967295L;
            }

        } else {
            throw new IllegalStateException("Unexpected volume type " + type);
        }

        return new StorageVolume(id, mtpStorageId, userPath, description, isPrimary(), removable,
                emulated, mtpReserveSize, allowMassStorage, maxFileSize, new UserHandle(userId),
                fsUuid, envState);
    }

    // TODO: avoid this layering violation
    private static final String DOCUMENT_AUTHORITY = "com.android.externalstorage.documents";
    private static final String DOCUMENT_ROOT_PRIMARY_EMULATED = "primary";

    /**
     * Build an intent to browse the contents of this volume. Only valid for
     * {@link #TYPE_EMULATED} or {@link #TYPE_PUBLIC}.
     */
    public Intent buildBrowseIntent() {
        final Uri uri;
        if (type == VolumeInfo.TYPE_PUBLIC) {
            uri = DocumentsContract.buildRootUri(DOCUMENT_AUTHORITY, fsUuid);
        } else if (VolumeInfo.ID_EMULATED_INTERNAL.equals(id)) {
            uri = DocumentsContract.buildRootUri(DOCUMENT_AUTHORITY,
                    DOCUMENT_ROOT_PRIMARY_EMULATED);
        } else if (type == VolumeInfo.TYPE_EMULATED) {
            // TODO: build intent once supported
            uri = null;
        } else {
            throw new IllegalArgumentException();
        }

        final Intent intent = new Intent(DocumentsContract.ACTION_BROWSE_DOCUMENT_ROOT);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setData(uri);
        return intent;
    }

    @Override
    public String toString() {
        final CharArrayWriter writer = new CharArrayWriter();
        dump(new IndentingPrintWriter(writer, "    ", 80));
        return writer.toString();
    }

    public void dump(IndentingPrintWriter pw) {
        pw.println("VolumeInfo:");
        pw.increaseIndent();
        pw.printPair("id", id);
        pw.printPair("type", DebugUtils.valueToString(getClass(), "TYPE_", type));
        pw.printPair("flags", DebugUtils.flagsToString(getClass(), "FLAG_", flags));
        pw.printPair("userId", userId);
        pw.printPair("state", DebugUtils.valueToString(getClass(), "STATE_", state));
        pw.println();
        pw.printPair("fsType", fsType);
        pw.printPair("fsUuid", fsUuid);
        pw.printPair("fsLabel", fsLabel);
        pw.println();
        pw.printPair("path", path);
        pw.printPair("mtpIndex", mtpIndex);
        pw.printPair("diskId", diskId);
        pw.printPair("nickname", nickname);
        pw.printPair("userFlags", DebugUtils.flagsToString(getClass(), "USER_FLAG_", userFlags));
        pw.decreaseIndent();
        pw.println();
    }

    @Override
    public VolumeInfo clone() {
        final Parcel temp = Parcel.obtain();
        try {
            writeToParcel(temp, 0);
            temp.setDataPosition(0);
            return CREATOR.createFromParcel(temp);
        } finally {
            temp.recycle();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof VolumeInfo) {
            return Objects.equals(id, ((VolumeInfo) o).id);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    public static final Creator<VolumeInfo> CREATOR = new Creator<VolumeInfo>() {
        @Override
        public VolumeInfo createFromParcel(Parcel in) {
            return new VolumeInfo(in);
        }

        @Override
        public VolumeInfo[] newArray(int size) {
            return new VolumeInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(id);
        parcel.writeInt(type);
        parcel.writeInt(this.flags);
        parcel.writeInt(userId);
        parcel.writeInt(state);
        parcel.writeString(fsType);
        parcel.writeString(fsUuid);
        parcel.writeString(fsLabel);
        parcel.writeString(path);
        parcel.writeInt(mtpIndex);
        parcel.writeString(diskId);
        parcel.writeString(nickname);
        parcel.writeInt(userFlags);
    }
}