/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi;

import android.app.AlarmManager;
import android.content.Context;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.AtomicFile;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * This class provides the API's to save/load/modify network configurations from a persistent
 * store. Uses keystore for certificate/key management operations.
 * NOTE: This class should only be used from WifiConfigManager and is not thread-safe!
 */
public class WifiConfigStore {
    /**
     * Alarm tag to use for starting alarms for buffering file writes.
     */
    @VisibleForTesting
    public static final String BUFFERED_WRITE_ALARM_TAG = "WriteBufferAlarm";
    /**
     * Log tag.
     */
    private static final String TAG = "WifiConfigStore";
    /**
     * Config store file name for both shared & user specific stores.
     */
    private static final String STORE_FILE_NAME = "WifiConfigStore.xml";
    /**
     * Directory to store the config store files in.
     */
    private static final String STORE_DIRECTORY_NAME = "wifi";
    /**
     * Time interval for buffering file writes for non-forced writes
     */
    private static final int BUFFERED_WRITE_ALARM_INTERVAL_MS = 10 * 1000;
    /**
     * Handler instance to post alarm timeouts to
     */
    private final Handler mEventHandler;
    /**
     * Alarm manager instance to start buffer timeout alarms.
     */
    private final AlarmManager mAlarmManager;
    /**
     * Clock instance to retrieve timestamps for alarms.
     */
    private final Clock mClock;
    /**
     * Shared config store file instance.
     */
    private StoreFile mSharedStore;
    /**
     * User specific store file instance.
     */
    private StoreFile mUserStore;
    /**
     * Verbose logging flag.
     */
    private boolean mVerboseLoggingEnabled = false;
    /**
     * Flag to indicate if there is a buffered write pending.
     */
    private boolean mBufferedWritePending = false;
    /**
     * Alarm listener for flushing out any buffered writes.
     */
    private final AlarmManager.OnAlarmListener mBufferedWriteListener =
            new AlarmManager.OnAlarmListener() {
                public void onAlarm() {
                    try {
                        writeBufferedData();
                    } catch (IOException e) {
                        Log.wtf(TAG, "Buffered write failed");
                    }

                }
            };

    /**
     * Create a new instance of WifiConfigStore.
     * Note: The store file instances have been made inputs to this class to ease unit-testing.
     *
     * @param context     context to use for retrieving the alarm manager.
     * @param looper      looper instance to post alarm timeouts to.
     * @param clock       clock instance to retrieve timestamps for alarms.
     * @param sharedStore StoreFile instance pointing to the shared store file. This should
     *                    be retrieved using {@link #createSharedFile()} method.
     * @param userStore   StoreFile instance pointing to the user specific store file. This should
     *                    be retrieved using {@link #createUserFile(int)} method.
     */
    public WifiConfigStore(Context context, Looper looper, Clock clock,
            StoreFile sharedStore, StoreFile userStore) {

        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mEventHandler = new Handler(looper);
        mClock = clock;

        // Initialize the store files.
        mSharedStore = sharedStore;
        mUserStore = userStore;
    }

    /**
     * Helper method to create a store file instance for either the shared store or user store.
     * Note: The method creates the store directory if not already present. This may be needed for
     * user store files.
     *
     * @param storeBaseDir Base directory under which the store file is to be stored. The store file
     *                     will be at <storeBaseDir>/wifi/WifiConfigStore.xml.
     * @return new instance of the store file.
     */
    private static StoreFile createFile(File storeBaseDir) {
        File storeDir = new File(storeBaseDir, STORE_DIRECTORY_NAME);
        if (!storeDir.exists()) {
            if (!storeDir.mkdir()) {
                Log.w(TAG, "Could not create store directory " + storeDir);
            }
        }
        return new StoreFile(new File(storeDir, STORE_FILE_NAME));
    }

    /**
     * Create a new instance of the shared store file.
     *
     * @return new instance of the store file or null if the directory cannot be created.
     */
    public static StoreFile createSharedFile() {
        return createFile(Environment.getDataMiscDirectory());
    }

    /**
     * Create a new instance of the user specific store file.
     * The user store file is inside the user's encrypted data directory.
     *
     * @param userId userId corresponding to the currently logged-in user.
     * @return new instance of the store file or null if the directory cannot be created.
     */
    public static StoreFile createUserFile(int userId) {
        return createFile(Environment.getDataMiscCeDirectory(userId));
    }

    /**
     * Enable verbose logging.
     */
    public void enableVerboseLogging(boolean verbose) {
        mVerboseLoggingEnabled = verbose;
    }

    /**
     * API to check if any of the store files are present on the device. This can be used
     * to detect if the device needs to perform data migration from legacy stores.
     *
     * @return true if any of the store file is present, false otherwise.
     */
    public boolean areStoresPresent() {
        return (mSharedStore.exists() || mUserStore.exists());
    }

    /**
     * API to write the provided store data to config stores.
     * The method writes the user specific configurations to user specific config store and the
     * shared configurations to shared config store.
     *
     * @param forceSync boolean to force write the config stores now. if false, the writes are
     *                  buffered and written after the configured interval.
     * @param storeData The entire data to be stored across all the config store files.
     */
    public void write(boolean forceSync, WifiConfigStoreData storeData)
            throws XmlPullParserException, IOException {
        // Serialize the provided data and send it to the respective stores. The actual write will
        // be performed later depending on the |forceSync| flag .
        byte[] sharedDataBytes = storeData.createSharedRawData();
        byte[] userDataBytes = storeData.createUserRawData();

        mSharedStore.storeRawDataToWrite(sharedDataBytes);
        mUserStore.storeRawDataToWrite(userDataBytes);

        // Every write provides a new snapshot to be persisted, so |forceSync| flag overrides any
        // pending buffer writes.
        if (forceSync) {
            writeBufferedData();
        } else {
            startBufferedWriteAlarm();
        }
    }

    /**
     * Helper method to start a buffered write alarm if one doesn't already exist.
     */
    private void startBufferedWriteAlarm() {
        if (!mBufferedWritePending) {
            mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    mClock.getElapsedSinceBootMillis() + BUFFERED_WRITE_ALARM_INTERVAL_MS,
                    BUFFERED_WRITE_ALARM_TAG, mBufferedWriteListener, mEventHandler);
            mBufferedWritePending = true;
        }
    }

    /**
     * Helper method to stop a buffered write alarm if one exists.
     */
    private void stopBufferedWriteAlarm() {
        if (mBufferedWritePending) {
            mAlarmManager.cancel(mBufferedWriteListener);
            mBufferedWritePending = false;
        }
    }

    /**
     * Helper method to actually perform the writes to the file. This flushes out any write data
     * being buffered in the respective stores and cancels any pending buffer write alarms.
     */
    private void writeBufferedData() throws IOException {
        stopBufferedWriteAlarm();

        long writeStartTime = mClock.getElapsedSinceBootMillis();
        mSharedStore.writeBufferedRawData();
        mUserStore.writeBufferedRawData();
        long writeTime = mClock.getElapsedSinceBootMillis() - writeStartTime;

        Log.d(TAG, "Writing to stores completed in " + writeTime + " ms.");
    }

    /**
     * API to read the store data from the config stores.
     * The method reads the user specific configurations from user specific config store and the
     * shared configurations from the shared config store.
     *
     * @return storeData The entire data retrieved across all the config store files.
     */
    public WifiConfigStoreData read() throws XmlPullParserException, IOException {
        long readStartTime = mClock.getElapsedSinceBootMillis();
        byte[] sharedDataBytes = mSharedStore.readRawData();
        byte[] userDataBytes = mUserStore.readRawData();
        long readTime = mClock.getElapsedSinceBootMillis() - readStartTime;

        Log.d(TAG, "Reading from stores completed in " + readTime + " ms.");

        return WifiConfigStoreData.parseRawData(sharedDataBytes, userDataBytes);
    }

    /**
     * Handle a user switch. This changes the user specific store.
     *
     * @param userStore StoreFile instance pointing to the user specific store file. This should
     *                  be retrieved using {@link #createUserFile(int)} method.
     */
    public void switchUserStore(StoreFile userStore) {
        // Stop any pending buffered writes, if any.
        stopBufferedWriteAlarm();
        mUserStore = userStore;
    }

    /**
     * Class to encapsulate all file writes. This is a wrapper over {@link AtomicFile} to write/read
     * raw data from the persistent file. This class provides helper methods to read/write the
     * entire file into a byte array.
     * This helps to separate out the processing/parsing from the actual file writing.
     */
    public static class StoreFile {
        /**
         * File permissions to lock down the file.
         */
        private static final int FILE_MODE = 0600;
        /**
         * The store file to be written to.
         */
        private final AtomicFile mAtomicFile;
        /**
         * This is an intermediate buffer to store the data to be written.
         */
        private byte[] mWriteData;
        /**
         * Store the file name for setting the file permissions/logging purposes.
         */
        private String mFileName;

        public StoreFile(File file) {
            mAtomicFile = new AtomicFile(file);
            mFileName = mAtomicFile.getBaseFile().getAbsolutePath();
        }

        /**
         * Returns whether the store file already exists on disk or not.
         *
         * @return true if it exists, false otherwise.
         */
        public boolean exists() {
            return mAtomicFile.exists();
        }

        /**
         * Read the entire raw data from the store file and return in a byte array.
         *
         * @return raw data read from the file or null if the file is not found.
         * @throws IOException if an error occurs. The input stream is always closed by the method
         * even when an exception is encountered.
         */
        public byte[] readRawData() throws IOException {
            try {
                return mAtomicFile.readFully();
            } catch (FileNotFoundException e) {
                return null;
            }
        }

        /**
         * Store the provided byte array to be written when {@link #writeBufferedRawData()} method
         * is invoked.
         * This intermediate step is needed to help in buffering file writes.
         *
         * @param data raw data to be written to the file.
         */
        public void storeRawDataToWrite(byte[] data) {
            mWriteData = data;
        }

        /**
         * Write the stored raw data to the store file.
         * After the write to file, the mWriteData member is reset.
         * @throws IOException if an error occurs. The output stream is always closed by the method
         * even when an exception is encountered.
         */
        public void writeBufferedRawData() throws IOException {
            if (mWriteData == null) {
                Log.w(TAG, "No data stored for writing to file: " + mFileName);
                return;
            }
            // Write the data to the atomic file.
            FileOutputStream out = null;
            try {
                out = mAtomicFile.startWrite();
                FileUtils.setPermissions(mFileName, FILE_MODE, -1, -1);
                out.write(mWriteData);
                mAtomicFile.finishWrite(out);
            } catch (IOException e) {
                if (out != null) {
                    mAtomicFile.failWrite(out);
                }
                throw e;
            }
            // Reset the pending write data after write.
            mWriteData = null;
        }
    }
}
