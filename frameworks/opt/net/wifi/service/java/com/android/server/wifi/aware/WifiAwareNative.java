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

package com.android.server.wifi.aware;

import android.net.wifi.aware.ConfigRequest;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.WifiAwareCharacteristics;
import android.net.wifi.aware.WifiAwareDiscoverySessionCallback;
import android.os.Bundle;
import android.util.Log;

import com.android.server.wifi.WifiNative;

import libcore.util.HexEncoding;

import java.util.Arrays;

/**
 * Native calls to access the Wi-Fi Aware HAL.
 *
 * Relies on WifiNative to perform the actual HAL registration.
 */
public class WifiAwareNative {
    private static final String TAG = "WifiAwareNative";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    private static final int WIFI_SUCCESS = 0;

    private static WifiAwareNative sWifiAwareNativeSingleton;

    private boolean mNativeHandlersIsInitialized = false;

    private static native int registerAwareNatives();

    /**
     * Returns the singleton WifiAwareNative used to manage the actual Aware HAL
     * interface.
     *
     * @return Singleton object.
     */
    public static WifiAwareNative getInstance() {
        // dummy reference - used to make sure that WifiNative is loaded before
        // us since it is the one to load the shared library and starts its
        // initialization.
        WifiNative dummy = WifiNative.getWlanNativeInterface();
        if (dummy == null) {
            Log.w(TAG, "can't get access to WifiNative");
            return null;
        }

        if (sWifiAwareNativeSingleton == null) {
            sWifiAwareNativeSingleton = new WifiAwareNative();
            registerAwareNatives();
        }

        return sWifiAwareNativeSingleton;
    }

    /**
     * A container class for Aware (vendor) implementation capabilities (or
     * limitations). Filled-in by the firmware.
     */
    public static class Capabilities {
        public int maxConcurrentAwareClusters;
        public int maxPublishes;
        public int maxSubscribes;
        public int maxServiceNameLen;
        public int maxMatchFilterLen;
        public int maxTotalMatchFilterLen;
        public int maxServiceSpecificInfoLen;
        public int maxVsaDataLen;
        public int maxMeshDataLen;
        public int maxNdiInterfaces;
        public int maxNdpSessions;
        public int maxAppInfoLen;
        public int maxQueuedTransmitMessages;

        /**
         * Converts the internal capabilities to a parcelable & potentially app-facing
         * characteristics bundle. Only some of the information is exposed.
         */
        public WifiAwareCharacteristics toPublicCharacteristics() {
            Bundle bundle = new Bundle();
            bundle.putInt(WifiAwareCharacteristics.KEY_MAX_SERVICE_NAME_LENGTH, maxServiceNameLen);
            bundle.putInt(WifiAwareCharacteristics.KEY_MAX_SERVICE_SPECIFIC_INFO_LENGTH,
                    maxServiceSpecificInfoLen);
            bundle.putInt(WifiAwareCharacteristics.KEY_MAX_MATCH_FILTER_LENGTH, maxMatchFilterLen);
            return new WifiAwareCharacteristics(bundle);
        }

        @Override
        public String toString() {
            return "Capabilities [maxConcurrentAwareClusters=" + maxConcurrentAwareClusters
                    + ", maxPublishes=" + maxPublishes + ", maxSubscribes=" + maxSubscribes
                    + ", maxServiceNameLen=" + maxServiceNameLen + ", maxMatchFilterLen="
                    + maxMatchFilterLen + ", maxTotalMatchFilterLen=" + maxTotalMatchFilterLen
                    + ", maxServiceSpecificInfoLen=" + maxServiceSpecificInfoLen
                    + ", maxVsaDataLen=" + maxVsaDataLen + ", maxMeshDataLen=" + maxMeshDataLen
                    + ", maxNdiInterfaces=" + maxNdiInterfaces + ", maxNdpSessions="
                    + maxNdpSessions + ", maxAppInfoLen=" + maxAppInfoLen
                    + ", maxQueuedTransmitMessages=" + maxQueuedTransmitMessages + "]";
        }
    }

    /* package */ static native int initAwareHandlersNative(Class<WifiNative> cls, int iface);

    private boolean isAwareInit() {
        synchronized (WifiNative.sLock) {
            if (!WifiNative.getWlanNativeInterface().isHalStarted()) {
                /*
                 * We should never start the HAL - that's done at a higher level
                 * by the Wi-Fi state machine.
                 */
                mNativeHandlersIsInitialized = false;
                return false;
            } else if (!mNativeHandlersIsInitialized) {
                int ret = initAwareHandlersNative(WifiNative.class, WifiNative.sWlan0Index);
                if (DBG) Log.d(TAG, "initAwareHandlersNative: res=" + ret);
                mNativeHandlersIsInitialized = ret == WIFI_SUCCESS;

                return mNativeHandlersIsInitialized;
            } else {
                return true;
            }
        }
    }

    /**
     * Tell the Aware JNI to re-initialize the Aware callback pointers next time it starts up.
     */
    public void deInitAware() {
        if (VDBG) {
            Log.v(TAG, "deInitAware: mNativeHandlersIsInitialized=" + mNativeHandlersIsInitialized);
        }
        mNativeHandlersIsInitialized = false;
    }

    private WifiAwareNative() {
        // do nothing
    }

    private static native int getCapabilitiesNative(short transactionId, Class<WifiNative> cls,
            int iface);

    /**
     * Query the Aware firmware's capabilities.
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     */
    public boolean getCapabilities(short transactionId) {
        if (VDBG) Log.d(TAG, "getCapabilities");
        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = getCapabilitiesNative(transactionId, WifiNative.class,
                        WifiNative.sWlan0Index);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "getCapabilities: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "getCapabilities: cannot initialize Aware");
            return false;
        }
    }

    private static native int enableAndConfigureNative(short transactionId, Class<WifiNative> cls,
            int iface, ConfigRequest configRequest);

    private static native int updateConfigurationNative(short transactionId, Class<WifiNative> cls,
            int iface, ConfigRequest configRequest);

    /**
     * Enable and configure Aware.
     *
     * @param transactionId Transaction ID for the transaction - used in the
     *            async callback to match with the original request.
     * @param configRequest Requested Aware configuration.
     * @param initialConfiguration Specifies whether initial configuration
     *            (true) or an update (false) to the configuration.
     */
    public boolean enableAndConfigure(short transactionId, ConfigRequest configRequest,
            boolean initialConfiguration) {
        if (VDBG) Log.d(TAG, "enableAndConfigure: configRequest=" + configRequest);
        if (isAwareInit()) {
            int ret;
            if (initialConfiguration) {
                synchronized (WifiNative.sLock) {
                    ret = enableAndConfigureNative(transactionId, WifiNative.class,
                            WifiNative.sWlan0Index, configRequest);
                }
                if (ret != WIFI_SUCCESS) {
                    Log.w(TAG, "enableAndConfigureNative: HAL API returned non-success -- " + ret);
                }
            } else {
                synchronized (WifiNative.sLock) {
                    ret = updateConfigurationNative(transactionId, WifiNative.class,
                            WifiNative.sWlan0Index, configRequest);
                }
                if (ret != WIFI_SUCCESS) {
                    Log.w(TAG, "updateConfigurationNative: HAL API returned non-success -- " + ret);
                }
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "enableAndConfigure: AwareInit fails");
            return false;
        }
    }

    private static native int disableNative(short transactionId, Class<WifiNative> cls, int iface);

    /**
     * Disable Aware.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     */
    public boolean disable(short transactionId) {
        if (VDBG) Log.d(TAG, "disableAware");
        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = disableNative(transactionId, WifiNative.class, WifiNative.sWlan0Index);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "disableNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "disable: cannot initialize Aware");
            return false;
        }
    }

    private static native int publishNative(short transactionId, int publishId,
            Class<WifiNative> cls, int iface, PublishConfig publishConfig);

    /**
     * Start or modify a service publish session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param publishId ID of the requested session - 0 to request a new publish
     *            session.
     * @param publishConfig Configuration of the discovery session.
     */
    public boolean publish(short transactionId, int publishId, PublishConfig publishConfig) {
        if (VDBG) {
            Log.d(TAG, "publish: transactionId=" + transactionId + ", config=" + publishConfig);
        }

        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = publishNative(transactionId, publishId, WifiNative.class,
                        WifiNative.sWlan0Index, publishConfig);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "publishNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "publish: cannot initialize Aware");
            return false;
        }
    }

    private static native int subscribeNative(short transactionId, int subscribeId,
            Class<WifiNative> cls, int iface, SubscribeConfig subscribeConfig);

    /**
     * Start or modify a service subscription session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param subscribeId ID of the requested session - 0 to request a new
     *            subscribe session.
     * @param subscribeConfig Configuration of the discovery session.
     */
    public boolean subscribe(short transactionId, int subscribeId,
            SubscribeConfig subscribeConfig) {
        if (VDBG) {
            Log.d(TAG, "subscribe: transactionId=" + transactionId + ", config=" + subscribeConfig);
        }

        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = subscribeNative(transactionId, subscribeId, WifiNative.class,
                        WifiNative.sWlan0Index, subscribeConfig);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "subscribeNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "subscribe: cannot initialize Aware");
            return false;
        }
    }

    private static native int sendMessageNative(short transactionId, Class<WifiNative> cls,
            int iface, int pubSubId, int requestorInstanceId, byte[] dest, byte[] message);

    /**
     * Send a message through an existing discovery session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param pubSubId ID of the existing publish/subscribe session.
     * @param requestorInstanceId ID of the peer to communicate with - obtained
     *            through a previous discovery (match) operation with that peer.
     * @param dest MAC address of the peer to communicate with - obtained
     *            together with requestorInstanceId.
     * @param message Message.
     * @param messageId Arbitary integer from host (not sent to HAL - useful for
     *                  testing/debugging at this level)
     */
    public boolean sendMessage(short transactionId, int pubSubId, int requestorInstanceId,
            byte[] dest, byte[] message, int messageId) {
        if (VDBG) {
            Log.d(TAG,
                    "sendMessage: transactionId=" + transactionId + ", pubSubId=" + pubSubId
                            + ", requestorInstanceId=" + requestorInstanceId + ", dest="
                            + String.valueOf(HexEncoding.encode(dest)) + ", messageId="
                            + messageId);
        }

        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = sendMessageNative(transactionId, WifiNative.class, WifiNative.sWlan0Index,
                        pubSubId, requestorInstanceId, dest, message);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "sendMessageNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "sendMessage: cannot initialize Aware");
            return false;
        }
    }

    private static native int stopPublishNative(short transactionId, Class<WifiNative> cls,
            int iface, int pubSubId);

    /**
     * Terminate a publish discovery session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param pubSubId ID of the publish/subscribe session - obtained when
     *            creating a session.
     */
    public boolean stopPublish(short transactionId, int pubSubId) {
        if (VDBG) {
            Log.d(TAG, "stopPublish: transactionId=" + transactionId + ", pubSubId=" + pubSubId);
        }

        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = stopPublishNative(transactionId, WifiNative.class, WifiNative.sWlan0Index,
                        pubSubId);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "stopPublishNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "stopPublish: cannot initialize Aware");
            return false;
        }
    }

    private static native int stopSubscribeNative(short transactionId, Class<WifiNative> cls,
            int iface, int pubSubId);

    /**
     * Terminate a subscribe discovery session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param pubSubId ID of the publish/subscribe session - obtained when
     *            creating a session.
     */
    public boolean stopSubscribe(short transactionId, int pubSubId) {
        if (VDBG) {
            Log.d(TAG, "stopSubscribe: transactionId=" + transactionId + ", pubSubId=" + pubSubId);
        }

        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = stopSubscribeNative(transactionId, WifiNative.class, WifiNative.sWlan0Index,
                        pubSubId);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "stopSubscribeNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "stopSubscribe: cannot initialize Aware");
            return false;
        }
    }

    private static native int createAwareNetworkInterfaceNative(short transactionId,
                                                              Class<WifiNative> cls, int iface,
                                                              String interfaceName);

    /**
     * Create a Aware network interface. This only creates the Linux interface - it doesn't actually
     * create the data connection.
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     * @param interfaceName The name of the interface, e.g. "aware0".
     */
    public boolean createAwareNetworkInterface(short transactionId, String interfaceName) {
        if (VDBG) {
            Log.v(TAG, "createAwareNetworkInterface: transactionId=" + transactionId + ", "
                    + "interfaceName=" + interfaceName);
        }

        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = createAwareNetworkInterfaceNative(transactionId, WifiNative.class, WifiNative
                        .sWlan0Index, interfaceName);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "createAwareNetworkInterfaceNative: HAL API returned non-success -- "
                        + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "createAwareNetworkInterface: cannot initialize Aware");
            return false;
        }
    }

    private static native int deleteAwareNetworkInterfaceNative(short transactionId,
                                                              Class<WifiNative> cls, int iface,
                                                              String interfaceName);

    /**
     * Deletes a Aware network interface. The data connection can (should?) be torn down previously.
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     * @param interfaceName The name of the interface, e.g. "aware0".
     */
    public boolean deleteAwareNetworkInterface(short transactionId, String interfaceName) {
        if (VDBG) {
            Log.v(TAG, "deleteAwareNetworkInterface: transactionId=" + transactionId + ", "
                    + "interfaceName=" + interfaceName);
        }

        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = deleteAwareNetworkInterfaceNative(transactionId, WifiNative.class, WifiNative
                        .sWlan0Index, interfaceName);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "deleteAwareNetworkInterfaceNative: HAL API returned non-success -- "
                        + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "deleteAwareNetworkInterface: cannot initialize Aware");
            return false;
        }
    }

    private static native int initiateDataPathNative(short transactionId, Class<WifiNative> cls,
            int iface, int peerId, int channelRequestType, int channel, byte[] peer,
            String interfaceName, byte[] message);

    public static final int CHANNEL_REQUEST_TYPE_NONE = 0;
    public static final int CHANNEL_REQUEST_TYPE_REQUESTED = 1;
    public static final int CHANNEL_REQUEST_TYPE_REQUIRED = 2;

    /**
     * Initiates setting up a data-path between device and peer.
     *
     * @param transactionId      Transaction ID for the transaction - used in the async callback to
     *                           match with the original request.
     * @param peerId             ID of the peer ID to associate the data path with. A value of 0
     *                           indicates that not associated with an existing session.
     * @param channelRequestType Indicates whether the specified channel is available, if available
     *                           requested or forced (resulting in failure if cannot be
     *                           accommodated).
     * @param channel            The channel on which to set up the data-path.
     * @param peer               The MAC address of the peer to create a connection with.
     * @param interfaceName      The interface on which to create the data connection.
     * @param message An arbitrary byte array to forward to the peer as part of the data path
     *                request.
     */
    public boolean initiateDataPath(short transactionId, int peerId, int channelRequestType,
            int channel, byte[] peer, String interfaceName, byte[] message) {
        if (VDBG) {
            Log.v(TAG, "initiateDataPath: transactionId=" + transactionId + ", peerId=" + peerId
                    + ", channelRequestType=" + channelRequestType + ", channel=" + channel
                    + ", peer=" + String.valueOf(HexEncoding.encode(peer)) + ", interfaceName="
                    + interfaceName);
        }

        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = initiateDataPathNative(transactionId, WifiNative.class, WifiNative
                        .sWlan0Index, peerId, channelRequestType, channel, peer, interfaceName,
                        message);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "initiateDataPathNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "initiateDataPath: cannot initialize Aware");
            return false;
        }
    }

    private static native int respondToDataPathRequestNative(short transactionId,
            Class<WifiNative> cls, int iface, boolean accept, int ndpId, String interfaceName,
            byte[] message);

    /**
     * Responds to a data request from a peer.
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     * @param accept Accept (true) or reject (false) the original call.
     * @param ndpId The NDP (Aware data path) ID. Obtained from the request callback.
     * @param interfaceName The interface on which the data path will be setup. Obtained from the
     *                      request callback.
     * @param message An arbitrary byte array to forward to the peer in the respond message.
     */
    public boolean respondToDataPathRequest(short transactionId, boolean accept, int ndpId,
            String interfaceName, byte[] message) {
        if (VDBG) {
            Log.v(TAG, "respondToDataPathRequest: transactionId=" + transactionId + ", accept="
                    + accept + ", int ndpId=" + ndpId + ", interfaceName=" + interfaceName);
        }

        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = respondToDataPathRequestNative(transactionId, WifiNative.class, WifiNative
                        .sWlan0Index, accept, ndpId, interfaceName, message);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG,
                        "respondToDataPathRequestNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "respondToDataPathRequest: cannot initialize Aware");
            return false;
        }
    }

    private static native int endDataPathNative(short transactionId, Class<WifiNative> cls,
            int iface, int ndpId);

    /**
     * Terminate an existing data-path (does not delete the interface).
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     * @param ndpId The NDP (Aware data path) ID to be terminated.
     */
    public boolean endDataPath(short transactionId, int ndpId) {
        if (VDBG) {
            Log.v(TAG, "endDataPath: transactionId=" + transactionId + ", ndpId=" + ndpId);
        }

        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = endDataPathNative(transactionId, WifiNative.class, WifiNative.sWlan0Index,
                        ndpId);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "endDataPathNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "endDataPath: cannot initialize Aware");
            return false;
        }
    }

    // EVENTS

    // AwareResponseType for API responses: will add values as needed
    public static final int AWARE_RESPONSE_ENABLED = 0;
    public static final int AWARE_RESPONSE_PUBLISH = 2;
    public static final int AWARE_RESPONSE_PUBLISH_CANCEL = 3;
    public static final int AWARE_RESPONSE_TRANSMIT_FOLLOWUP = 4;
    public static final int AWARE_RESPONSE_SUBSCRIBE = 5;
    public static final int AWARE_RESPONSE_SUBSCRIBE_CANCEL = 6;
    public static final int AWARE_RESPONSE_CONFIG = 8;
    public static final int AWARE_RESPONSE_GET_CAPABILITIES = 12;
    public static final int AWARE_RESPONSE_DP_INTERFACE_CREATE = 13;
    public static final int AWARE_RESPONSE_DP_INTERFACE_DELETE = 14;
    public static final int AWARE_RESPONSE_DP_INITIATOR_RESPONSE = 15;
    public static final int AWARE_RESPONSE_DP_RESPONDER_RESPONSE = 16;
    public static final int AWARE_RESPONSE_DP_END = 17;

    // TODO: place-holder until resolve error codes/feedback to user b/29443148
    public static final int AWARE_STATUS_ERROR = -1;

    // direct copy from wifi_nan.h: need to keep in sync
    /* Aware HAL Status Codes */
    public static final int AWARE_STATUS_SUCCESS = 0;
    public static final int AWARE_STATUS_INTERNAL_FAILURE = 1;
    public static final int AWARE_STATUS_PROTOCOL_FAILURE = 2;
    public static final int AWARE_STATUS_INVALID_PUBLISH_SUBSCRIBE_ID = 3;
    public static final int AWARE_STATUS_NO_RESOURCE_AVAILABLE = 4;
    public static final int AWARE_STATUS_INVALID_PARAM = 5;
    public static final int AWARE_STATUS_INVALID_REQUESTOR_INSTANCE_ID = 6;
    public static final int AWARE_STATUS_INVALID_SERVICE_INSTANCE_ID = 7;
    public static final int AWARE_STATUS_INVALID_NDP_ID = 8;
    public static final int AWARE_STATUS_NAN_NOT_ALLOWED = 9;
    public static final int AWARE_STATUS_NO_OTA_ACK = 10;
    public static final int AWARE_STATUS_ALREADY_ENABLED = 11;
    public static final int AWARE_STATUS_FOLLOWUP_QUEUE_FULL = 12;
    public static final int AWARE_STATUS_UNSUPPORTED_CONCURRENCY_NAN_DISABLED = 13;

    // callback from native
    private static void onAwareNotifyResponse(short transactionId, int responseType, int status,
            String nanError) {
        if (VDBG) {
            Log.v(TAG,
                    "onAwareNotifyResponse: transactionId=" + transactionId + ", responseType="
                    + responseType + ", status=" + status + ", nanError=" + nanError);
        }
        WifiAwareStateManager stateMgr = WifiAwareStateManager.getInstance();

        switch (responseType) {
            case AWARE_RESPONSE_ENABLED:
                /* fall through */
            case AWARE_RESPONSE_CONFIG:
                if (status == AWARE_STATUS_SUCCESS) {
                    stateMgr.onConfigSuccessResponse(transactionId);
                } else {
                    stateMgr.onConfigFailedResponse(transactionId, status);
                }
                break;
            case AWARE_RESPONSE_PUBLISH_CANCEL:
                if (status != AWARE_STATUS_SUCCESS) {
                    Log.e(TAG,
                            "onAwareNotifyResponse: AWARE_RESPONSE_PUBLISH_CANCEL error - status="
                                    + status + ", nanError=" + nanError);
                }
                break;
            case AWARE_RESPONSE_TRANSMIT_FOLLOWUP:
                if (status == AWARE_STATUS_SUCCESS) {
                    stateMgr.onMessageSendQueuedSuccessResponse(transactionId);
                } else {
                    stateMgr.onMessageSendQueuedFailResponse(transactionId, status);
                }
                break;
            case AWARE_RESPONSE_SUBSCRIBE_CANCEL:
                if (status != AWARE_STATUS_SUCCESS) {
                    Log.e(TAG,
                            "onAwareNotifyResponse: AWARE_RESPONSE_PUBLISH_CANCEL error - status="
                                    + status + ", nanError=" + nanError);
                }
                break;
            case AWARE_RESPONSE_DP_INTERFACE_CREATE:
                if (status != AWARE_STATUS_SUCCESS) {
                    Log.e(TAG,
                            "onAwareNotifyResponse: AWARE_RESPONSE_DP_INTERFACE_CREATE error - "
                                    + "status="
                                    + status + ", nanError=" + nanError);
                }
                stateMgr.onCreateDataPathInterfaceResponse(transactionId,
                        status == AWARE_STATUS_SUCCESS, status);
                break;
            case AWARE_RESPONSE_DP_INTERFACE_DELETE:
                if (status != AWARE_STATUS_SUCCESS) {
                    Log.e(TAG,
                            "onAwareNotifyResponse: AWARE_RESPONSE_DP_INTERFACE_DELETE error - "
                                    + "status="
                                    + status + ", nanError=" + nanError);
                }
                stateMgr.onDeleteDataPathInterfaceResponse(transactionId,
                        status == AWARE_STATUS_SUCCESS, status);
                break;
            case AWARE_RESPONSE_DP_RESPONDER_RESPONSE:
                if (status != AWARE_STATUS_SUCCESS) {
                    Log.e(TAG,
                            "onAwareNotifyResponse: AWARE_RESPONSE_DP_RESPONDER_RESPONSE error - "
                                    + "status=" + status + ", nanError=" + nanError);
                }
                stateMgr.onRespondToDataPathSetupRequestResponse(transactionId,
                        status == AWARE_STATUS_SUCCESS, status);
                break;
            case AWARE_RESPONSE_DP_END:
                if (status != AWARE_STATUS_SUCCESS) {
                    Log.e(TAG, "onAwareNotifyResponse: AWARE_RESPONSE_DP_END error - status="
                            + status + ", nanError=" + nanError);
                }
                stateMgr.onEndDataPathResponse(transactionId, status == AWARE_STATUS_SUCCESS,
                        status);
                break;
            default:
                Log.e(TAG, "onAwareNotifyResponse: unclassified responseType=" + responseType);
                break;
        }
    }

    private static void onAwareNotifyResponsePublishSubscribe(short transactionId, int responseType,
            int status, String nanError, int pubSubId) {
        if (VDBG) {
            Log.v(TAG,
                    "onAwareNotifyResponsePublishSubscribe: transactionId=" + transactionId
                            + ", responseType=" + responseType + ", status=" + status
                            + ", nanError=" + nanError + ", pubSubId=" + pubSubId);
        }

        switch (responseType) {
            case AWARE_RESPONSE_PUBLISH:
                if (status == AWARE_STATUS_SUCCESS) {
                    WifiAwareStateManager.getInstance().onSessionConfigSuccessResponse(
                            transactionId, true, pubSubId);
                } else {
                    WifiAwareStateManager.getInstance().onSessionConfigFailResponse(transactionId,
                            true, status);
                }
                break;
            case AWARE_RESPONSE_SUBSCRIBE:
                if (status == AWARE_STATUS_SUCCESS) {
                    WifiAwareStateManager.getInstance().onSessionConfigSuccessResponse(
                            transactionId, false, pubSubId);
                } else {
                    WifiAwareStateManager.getInstance().onSessionConfigFailResponse(transactionId,
                            false, status);
                }
                break;
            default:
                Log.wtf(TAG, "onAwareNotifyResponsePublishSubscribe: unclassified responseType="
                        + responseType);
                break;
        }
    }

    private static void onAwareNotifyResponseCapabilities(short transactionId, int status,
            String nanError, Capabilities capabilities) {
        if (VDBG) {
            Log.v(TAG, "onAwareNotifyResponseCapabilities: transactionId=" + transactionId
                    + ", status=" + status + ", nanError=" + nanError + ", capabilities="
                    + capabilities);
        }

        if (status == AWARE_STATUS_SUCCESS) {
            WifiAwareStateManager.getInstance().onCapabilitiesUpdateResponse(transactionId,
                    capabilities);
        } else {
            Log.e(TAG, "onAwareNotifyResponseCapabilities: error status=" + status
                    + ", nanError=" + nanError);
        }
    }

    private static void onAwareNotifyResponseDataPathInitiate(short transactionId, int status,
            String nanError, int ndpId) {
        if (VDBG) {
            Log.v(TAG,
                    "onAwareNotifyResponseDataPathInitiate: transactionId=" + transactionId
                            + ", status=" + status + ", nanError=" + nanError + ", ndpId=" + ndpId);
        }
        if (status == AWARE_STATUS_SUCCESS) {
            WifiAwareStateManager.getInstance().onInitiateDataPathResponseSuccess(transactionId,
                    ndpId);
        } else {
            WifiAwareStateManager.getInstance().onInitiateDataPathResponseFail(transactionId,
                    status);
        }
    }

    public static final int AWARE_EVENT_ID_DISC_MAC_ADDR = 0;
    public static final int AWARE_EVENT_ID_STARTED_CLUSTER = 1;
    public static final int AWARE_EVENT_ID_JOINED_CLUSTER = 2;

    // callback from native
    private static void onDiscoveryEngineEvent(int eventType, byte[] mac) {
        if (VDBG) {
            Log.v(TAG, "onDiscoveryEngineEvent: eventType=" + eventType + ", mac="
                    + String.valueOf(HexEncoding.encode(mac)));
        }

        if (eventType == AWARE_EVENT_ID_DISC_MAC_ADDR) {
            WifiAwareStateManager.getInstance().onInterfaceAddressChangeNotification(mac);
        } else if (eventType == AWARE_EVENT_ID_STARTED_CLUSTER) {
            WifiAwareStateManager.getInstance().onClusterChangeNotification(
                    WifiAwareClientState.CLUSTER_CHANGE_EVENT_STARTED, mac);
        } else if (eventType == AWARE_EVENT_ID_JOINED_CLUSTER) {
            WifiAwareStateManager.getInstance().onClusterChangeNotification(
                    WifiAwareClientState.CLUSTER_CHANGE_EVENT_JOINED, mac);
        } else {
            Log.w(TAG, "onDiscoveryEngineEvent: invalid eventType=" + eventType);
        }
    }

    // callback from native
    private static void onMatchEvent(int pubSubId, int requestorInstanceId, byte[] mac,
            byte[] serviceSpecificInfo, byte[] matchFilter) {
        if (VDBG) {
            Log.v(TAG, "onMatchEvent: pubSubId=" + pubSubId + ", requestorInstanceId="
                    + requestorInstanceId + ", mac=" + String.valueOf(HexEncoding.encode(mac))
                    + ", serviceSpecificInfo=" + Arrays.toString(serviceSpecificInfo)
                    + ", matchFilter=" + Arrays.toString(matchFilter));
        }

        WifiAwareStateManager.getInstance().onMatchNotification(pubSubId, requestorInstanceId, mac,
                serviceSpecificInfo, matchFilter);
    }

    // callback from native
    private static void onPublishTerminated(int publishId, int status) {
        if (VDBG) Log.v(TAG, "onPublishTerminated: publishId=" + publishId + ", status=" + status);

        WifiAwareStateManager.getInstance().onSessionTerminatedNotification(publishId,
                status == AWARE_STATUS_SUCCESS
                        ? WifiAwareDiscoverySessionCallback.TERMINATE_REASON_DONE
                        : WifiAwareDiscoverySessionCallback.TERMINATE_REASON_FAIL, true);
    }

    // callback from native
    private static void onSubscribeTerminated(int subscribeId, int status) {
        if (VDBG) {
            Log.v(TAG, "onSubscribeTerminated: subscribeId=" + subscribeId + ", status=" + status);
        }

        WifiAwareStateManager.getInstance().onSessionTerminatedNotification(subscribeId,
                status == AWARE_STATUS_SUCCESS
                        ? WifiAwareDiscoverySessionCallback.TERMINATE_REASON_DONE
                        : WifiAwareDiscoverySessionCallback.TERMINATE_REASON_FAIL, false);
    }

    // callback from native
    private static void onFollowupEvent(int pubSubId, int requestorInstanceId, byte[] mac,
            byte[] message) {
        if (VDBG) {
            Log.v(TAG, "onFollowupEvent: pubSubId=" + pubSubId + ", requestorInstanceId="
                    + requestorInstanceId + ", mac=" + String.valueOf(HexEncoding.encode(mac)));
        }

        WifiAwareStateManager.getInstance().onMessageReceivedNotification(pubSubId,
                requestorInstanceId, mac, message);
    }

    // callback from native
    private static void onDisabledEvent(int status) {
        if (VDBG) Log.v(TAG, "onDisabledEvent: status=" + status);

        WifiAwareStateManager.getInstance().onAwareDownNotification(status);
    }

    // callback from native
    private static void onTransmitFollowupEvent(short transactionId, int reason) {
        if (VDBG) {
            Log.v(TAG, "onTransmitFollowupEvent: transactionId=" + transactionId + ", reason="
                    + reason);
        }

        if (reason == AWARE_STATUS_SUCCESS) {
            WifiAwareStateManager.getInstance().onMessageSendSuccessNotification(transactionId);
        } else {
            WifiAwareStateManager.getInstance().onMessageSendFailNotification(transactionId,
                    reason);
        }
    }

    private static void onDataPathRequest(int pubSubId, byte[] mac, int ndpId, byte[] message) {
        if (VDBG) {
            Log.v(TAG, "onDataPathRequest: pubSubId=" + pubSubId + ", mac=" + String.valueOf(
                    HexEncoding.encode(mac)) + ", ndpId=" + ndpId);
        }

        WifiAwareStateManager.getInstance()
                .onDataPathRequestNotification(pubSubId, mac, ndpId, message);
    }

    private static void onDataPathConfirm(int ndpId, byte[] mac, boolean accept, int reason,
            byte[] message) {
        if (VDBG) {
            Log.v(TAG, "onDataPathConfirm: ndpId=" + ndpId + ", mac=" + String.valueOf(HexEncoding
                    .encode(mac)) + ", accept=" + accept + ", reason=" + reason);
        }

        WifiAwareStateManager.getInstance()
                .onDataPathConfirmNotification(ndpId, mac, accept, reason, message);
    }

    private static void onDataPathEnd(int ndpId) {
        if (VDBG) {
            Log.v(TAG, "onDataPathEndNotification: ndpId=" + ndpId);
        }

        WifiAwareStateManager.getInstance().onDataPathEndNotification(ndpId);
    }
}
