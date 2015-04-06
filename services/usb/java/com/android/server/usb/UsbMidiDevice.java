/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * See the License for the specific language governing permissions an
 * limitations under the License.
 */

package com.android.server.usb;

import android.content.Context;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiDeviceServer;
import android.media.midi.MidiManager;
import android.media.midi.MidiReceiver;
import android.media.midi.MidiSender;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import com.android.internal.midi.MidiEventScheduler;
import com.android.internal.midi.MidiEventScheduler.MidiEvent;

import libcore.io.IoUtils;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public final class UsbMidiDevice implements Closeable {
    private static final String TAG = "UsbMidiDevice";

    private MidiDeviceServer mServer;

    private final MidiEventScheduler mEventScheduler;

    private static final int BUFFER_SIZE = 512;

    private final FileDescriptor[] mFileDescriptors;

    // for polling multiple FileDescriptors for MIDI events
    private final StructPollfd[] mPollFDs;
    // streams for reading from ALSA driver
    private final FileInputStream[] mInputStreams;
    // streams for writing to ALSA driver
    private final FileOutputStream[] mOutputStreams;

    public static UsbMidiDevice create(Context context, Bundle properties, int card, int device) {
        // FIXME - support devices with different number of input and output ports
        int subDevices = nativeGetSubdeviceCount(card, device);
        if (subDevices <= 0) {
            Log.e(TAG, "nativeGetSubdeviceCount failed");
            return null;
        }

        // FIXME - support devices with different number of input and output ports
        FileDescriptor[] fileDescriptors = nativeOpen(card, device, subDevices);
        if (fileDescriptors == null) {
            Log.e(TAG, "nativeOpen failed");
            return null;
        }

        UsbMidiDevice midiDevice = new UsbMidiDevice(fileDescriptors);
        if (!midiDevice.register(context, properties)) {
            IoUtils.closeQuietly(midiDevice);
            Log.e(TAG, "createDeviceServer failed");
            return null;
        }
        return midiDevice;
    }

    private UsbMidiDevice(FileDescriptor[] fileDescriptors) {
        mFileDescriptors = fileDescriptors;
        int inputCount = fileDescriptors.length;
        int outputCount = fileDescriptors.length;

        mPollFDs = new StructPollfd[inputCount];
        mInputStreams = new FileInputStream[inputCount];
        for (int i = 0; i < inputCount; i++) {
            FileDescriptor fd = fileDescriptors[i];
            StructPollfd pollfd = new StructPollfd();
            pollfd.fd = fd;
            pollfd.events = (short)OsConstants.POLLIN;
            mPollFDs[i] = pollfd;
            mInputStreams[i] = new FileInputStream(fd);
        }

        mOutputStreams = new FileOutputStream[outputCount];
        for (int i = 0; i < outputCount; i++) {
            mOutputStreams[i] = new FileOutputStream(fileDescriptors[i]);
        }
        mEventScheduler = new MidiEventScheduler(inputCount);
    }

    private boolean register(Context context, Bundle properties) {
        MidiManager midiManager = (MidiManager)context.getSystemService(Context.MIDI_SERVICE);
        if (midiManager == null) {
            Log.e(TAG, "No MidiManager in UsbMidiDevice.create()");
            return false;
        }

        int inputCount = mInputStreams.length;
        int outputCount = mOutputStreams.length;
        MidiReceiver[] inputPortReceivers = new MidiReceiver[inputCount];
        for (int port = 0; port < inputCount; port++) {
            inputPortReceivers[port] = mEventScheduler.getReceiver(port);
        }

        mServer = midiManager.createDeviceServer(inputPortReceivers, outputCount,
                null, null, properties, MidiDeviceInfo.TYPE_USB, null);
        if (mServer == null) {
            return false;
        }
        final MidiReceiver[] outputReceivers = mServer.getOutputPortReceivers();

        // Create input thread
        new Thread("UsbMidiDevice input thread") {
            @Override
            public void run() {
                byte[] buffer = new byte[BUFFER_SIZE];
                try {
                    boolean done = false;
                    while (!done) {
                        // look for a readable FileDescriptor
                        for (int index = 0; index < mPollFDs.length; index++) {
                            StructPollfd pfd = mPollFDs[index];
                            if ((pfd.revents & OsConstants.POLLIN) != 0) {
                                // clear readable flag
                                pfd.revents = 0;

                                int count = mInputStreams[index].read(buffer);
                                outputReceivers[index].send(buffer, 0, count);
                            } else if ((pfd.revents & (OsConstants.POLLERR
                                                        | OsConstants.POLLHUP)) != 0) {
                                done = true;
                            }
                        }

                        // wait until we have a readable port
                        Os.poll(mPollFDs, -1 /* infinite timeout */);
                     }
                } catch (IOException e) {
                    Log.d(TAG, "reader thread exiting");
                } catch (ErrnoException e) {
                    Log.d(TAG, "reader thread exiting");
                }
                Log.d(TAG, "input thread exit");
            }
        }.start();

        // Create output thread
        new Thread("UsbMidiDevice output thread") {
            @Override
            public void run() {
                while (true) {
                    MidiEvent event;
                    try {
                        event = (MidiEvent)mEventScheduler.waitNextEvent();
                    } catch (InterruptedException e) {
                        // try again
                        continue;
                    }
                    if (event == null) {
                        break;
                    }
                    try {
                        mOutputStreams[event.portNumber].write(event.data, 0, event.count);
                    } catch (IOException e) {
                        Log.e(TAG, "write failed for port " + event.portNumber);
                    }
                    mEventScheduler.addEventToPool(event);
                }
                Log.d(TAG, "output thread exit");
            }
        }.start();

        return true;
    }

    @Override
    public void close() throws IOException {
        mEventScheduler.close();

        if (mServer != null) {
            mServer.close();
        }

        for (int i = 0; i < mInputStreams.length; i++) {
            mInputStreams[i].close();
        }
        for (int i = 0; i < mOutputStreams.length; i++) {
            mOutputStreams[i].close();
        }
        nativeClose(mFileDescriptors);
    }

    private static native int nativeGetSubdeviceCount(int card, int device);
    private static native FileDescriptor[] nativeOpen(int card, int device, int subdeviceCount);
    private static native void nativeClose(FileDescriptor[] fileDescriptors);
}