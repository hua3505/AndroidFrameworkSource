/*
 * Copyright 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "wifinan"

#include <ctype.h>
#include <stdlib.h>
#include <sys/socket.h>

#include <hardware_legacy/wifi_hal.h>
#include <log/log.h>
#include <nativehelper/JniConstants.h>
#include <nativehelper/ScopedBytes.h>
#include <nativehelper/ScopedUtfChars.h>
#include <nativehelper/jni.h>
#include <utils/String16.h>
#include <utils/misc.h>
#include <wifi_system/wifi.h>

#include "jni_helper.h"

namespace android {

static jclass mCls;                             /* saved WifiAwareNative object */
static JavaVM *mVM = NULL;                      /* saved JVM pointer */

wifi_handle getWifiHandle(JNIHelper &helper, jclass cls);
wifi_interface_handle getIfaceHandle(JNIHelper &helper, jclass cls, jint index);

extern wifi_hal_fn hal_fn;

// Start NAN functions

static void OnNanNotifyResponse(transaction_id id, NanResponseMsg* msg) {
  ALOGD(
      "OnNanNotifyResponse: transaction_id=%d, status=%d, nan_error=%s, response_type=%d",
      id, msg->status, msg->nan_error, msg->response_type);

  JNIHelper helper(mVM);
  JNIObject<jstring> nan_error = helper.newStringUTF(msg->nan_error);
  switch (msg->response_type) {
    case NAN_RESPONSE_PUBLISH:
      helper.reportEvent(mCls, "onAwareNotifyResponsePublishSubscribe",
                         "(SIILjava/lang/String;I)V", (short) id, (int) msg->response_type,
                         (int) msg->status, nan_error.get(),
                         msg->body.publish_response.publish_id);
      break;
    case NAN_RESPONSE_SUBSCRIBE:
      helper.reportEvent(mCls, "onAwareNotifyResponsePublishSubscribe",
                         "(SIILjava/lang/String;I)V", (short) id, (int) msg->response_type,
                         (int) msg->status, nan_error.get(),
                         msg->body.subscribe_response.subscribe_id);
      break;
    case NAN_GET_CAPABILITIES: {
      JNIObject<jobject> data = helper.createObject(
          "com/android/server/wifi/aware/WifiAwareNative$Capabilities");
      if (data == NULL) {
        ALOGE(
            "Error in allocating WifiAwareNative.Capabilities OnNanNotifyResponse");
        return;
      }

      helper.setIntField(
          data, "maxConcurrentAwareClusters",
          (int) msg->body.nan_capabilities.max_concurrent_nan_clusters);
      helper.setIntField(data, "maxPublishes",
                         (int) msg->body.nan_capabilities.max_publishes);
      helper.setIntField(data, "maxSubscribes",
                         (int) msg->body.nan_capabilities.max_subscribes);
      helper.setIntField(data, "maxServiceNameLen",
                         (int) msg->body.nan_capabilities.max_service_name_len);
      helper.setIntField(data, "maxMatchFilterLen",
                         (int) msg->body.nan_capabilities.max_match_filter_len);
      helper.setIntField(
          data, "maxTotalMatchFilterLen",
          (int) msg->body.nan_capabilities.max_total_match_filter_len);
      helper.setIntField(
          data, "maxServiceSpecificInfoLen",
          (int) msg->body.nan_capabilities.max_service_specific_info_len);
      helper.setIntField(data, "maxVsaDataLen",
                         (int) msg->body.nan_capabilities.max_vsa_data_len);
      helper.setIntField(data, "maxMeshDataLen",
                         (int) msg->body.nan_capabilities.max_mesh_data_len);
      helper.setIntField(data, "maxNdiInterfaces",
                         (int) msg->body.nan_capabilities.max_ndi_interfaces);
      helper.setIntField(data, "maxNdpSessions",
                         (int) msg->body.nan_capabilities.max_ndp_sessions);
      helper.setIntField(data, "maxAppInfoLen",
                         (int) msg->body.nan_capabilities.max_app_info_len);
      helper.setIntField(data, "maxQueuedTransmitMessages",
                         (int) msg->body.nan_capabilities.max_queued_transmit_followup_msgs);

      helper.reportEvent(
          mCls, "onAwareNotifyResponseCapabilities",
          "(SILjava/lang/String;Lcom/android/server/wifi/aware/WifiAwareNative$Capabilities;)V",
          (short) id, (int) msg->status, nan_error.get(), data.get());
      break;
    }
    case NAN_DP_INITIATOR_RESPONSE:
      helper.reportEvent(mCls, "onAwareNotifyResponseDataPathInitiate", "(SILjava/lang/String;I)V",
                         (short) id, (int) msg->status, nan_error.get(),
                         msg->body.data_request_response.ndp_instance_id);
      break;
    default:
      helper.reportEvent(mCls, "onAwareNotifyResponse", "(SIILjava/lang/String;)V", (short) id,
                         (int) msg->response_type, (int) msg->status,
                         nan_error.get());
      break;
  }
}

static void OnNanEventPublishTerminated(NanPublishTerminatedInd* event) {
    ALOGD("OnNanEventPublishTerminated");

    JNIHelper helper(mVM);
    helper.reportEvent(mCls, "onPublishTerminated", "(II)V",
                       event->publish_id, event->reason);
}

static void OnNanEventMatch(NanMatchInd* event) {
    ALOGD("OnNanEventMatch");

    JNIHelper helper(mVM);

    JNIObject<jbyteArray> macBytes = helper.newByteArray(6);
    helper.setByteArrayRegion(macBytes, 0, 6, (jbyte *) event->addr);

    JNIObject<jbyteArray> ssiBytes = helper.newByteArray(event->service_specific_info_len);
    helper.setByteArrayRegion(ssiBytes, 0, event->service_specific_info_len,
                              (jbyte *) event->service_specific_info);

    JNIObject<jbyteArray> mfBytes = helper.newByteArray(event->sdf_match_filter_len);
    helper.setByteArrayRegion(mfBytes, 0, event->sdf_match_filter_len,
                              (jbyte *) event->sdf_match_filter);

    helper.reportEvent(mCls, "onMatchEvent", "(II[B[B[B)V",
                       (int) event->publish_subscribe_id,
                       (int) event->requestor_instance_id,
                       macBytes.get(),
                       ssiBytes.get(),
                       mfBytes.get());
}

static void OnNanEventMatchExpired(NanMatchExpiredInd* event) {
    ALOGD("OnNanEventMatchExpired");
}

static void OnNanEventSubscribeTerminated(NanSubscribeTerminatedInd* event) {
    ALOGD("OnNanEventSubscribeTerminated");

    JNIHelper helper(mVM);
    helper.reportEvent(mCls, "onSubscribeTerminated", "(II)V",
                       event->subscribe_id, event->reason);
}

static void OnNanEventFollowup(NanFollowupInd* event) {
    ALOGD("OnNanEventFollowup");

    JNIHelper helper(mVM);

    JNIObject<jbyteArray> macBytes = helper.newByteArray(6);
    helper.setByteArrayRegion(macBytes, 0, 6, (jbyte *) event->addr);

    JNIObject<jbyteArray> msgBytes = helper.newByteArray(event->service_specific_info_len);
    helper.setByteArrayRegion(msgBytes, 0, event->service_specific_info_len, (jbyte *) event->service_specific_info);

    helper.reportEvent(mCls, "onFollowupEvent", "(II[B[B)V",
                       (int) event->publish_subscribe_id,
                       (int) event->requestor_instance_id,
                       macBytes.get(),
                       msgBytes.get());
}

static void OnNanEventDiscEngEvent(NanDiscEngEventInd* event) {
    ALOGD("OnNanEventDiscEngEvent called: event_type=%d", event->event_type);

    JNIHelper helper(mVM);

    JNIObject<jbyteArray> macBytes = helper.newByteArray(6);
    if (event->event_type == NAN_EVENT_ID_DISC_MAC_ADDR) {
        helper.setByteArrayRegion(macBytes, 0, 6, (jbyte *) event->data.mac_addr.addr);
    } else {
        helper.setByteArrayRegion(macBytes, 0, 6, (jbyte *) event->data.cluster.addr);
    }

    helper.reportEvent(mCls, "onDiscoveryEngineEvent", "(I[B)V",
                       (int) event->event_type, macBytes.get());
}

static void OnNanEventDisabled(NanDisabledInd* event) {
    ALOGD("OnNanEventDisabled called: reason=%d", event->reason);

    JNIHelper helper(mVM);

    helper.reportEvent(mCls, "onDisabledEvent", "(I)V", (int) event->reason);
}

static void OnNanEventTca(NanTCAInd* event) {
    ALOGD("OnNanEventTca");
}

static void OnNanEventBeaconSdfPayload(NanBeaconSdfPayloadInd* event) {
    ALOGD("OnNanEventSdfPayload");
}

static void OnNanEventDataRequest(NanDataPathRequestInd* event) {
  ALOGD("OnNanEventDataRequest");
  JNIHelper helper(mVM);

  JNIObject<jbyteArray> peerBytes = helper.newByteArray(6);
  helper.setByteArrayRegion(peerBytes, 0, 6,
                            (jbyte *)event->peer_disc_mac_addr);

  JNIObject<jbyteArray> msgBytes =
      helper.newByteArray(event->app_info.ndp_app_info_len);
  helper.setByteArrayRegion(msgBytes, 0, event->app_info.ndp_app_info_len,
                            (jbyte *)event->app_info.ndp_app_info);

  helper.reportEvent(mCls, "onDataPathRequest", "(I[BI[B)V",
                     event->service_instance_id, peerBytes.get(),
                     event->ndp_instance_id, msgBytes.get());
}

static void OnNanEventDataConfirm(NanDataPathConfirmInd* event) {
  ALOGD("OnNanEventDataConfirm");
  JNIHelper helper(mVM);

  JNIObject<jbyteArray> peerBytes = helper.newByteArray(6);
  helper.setByteArrayRegion(peerBytes, 0, 6, (jbyte *)event->peer_ndi_mac_addr);

  JNIObject<jbyteArray> msgBytes =
      helper.newByteArray(event->app_info.ndp_app_info_len);
  helper.setByteArrayRegion(msgBytes, 0, event->app_info.ndp_app_info_len,
                            (jbyte *)event->app_info.ndp_app_info);

  helper.reportEvent(
      mCls, "onDataPathConfirm", "(I[BZI[B)V", event->ndp_instance_id,
      peerBytes.get(), event->rsp_code == NAN_DP_REQUEST_ACCEPT,
      event->reason_code, msgBytes.get());
}

static void OnNanEventDataEnd(NanDataPathEndInd* event) {
  ALOGD("OnNanEventDataEnd");
  JNIHelper helper(mVM);

  for (int i = 0; i < event->num_ndp_instances; ++i) {
    helper.reportEvent(mCls, "onDataPathEnd", "(I)V",
                       event->ndp_instance_id[i]);
  }
}

static void OnNanEventTransmitFollowup(NanTransmitFollowupInd* event) {
  ALOGD("OnNanEventTransmitFollowup: transaction_id=%d, reason=%d", event->id,
        event->reason);

  JNIHelper helper(mVM);

  helper.reportEvent(mCls, "onTransmitFollowupEvent", "(SI)V",
                     (short) event->id, (int) event->reason);
}

static jint android_net_wifi_nan_register_handler(JNIEnv *env, jclass cls,
                                                  jclass wifi_native_cls,
                                                  jint iface) {
    JNIHelper helper(env);
    wifi_interface_handle handle = getIfaceHandle(helper, wifi_native_cls, iface);

    ALOGD("android_net_wifi_nan_register_handler handle=%p", handle);

    NanCallbackHandler handlers;
    memset(&handlers, 0, sizeof(NanCallbackHandler));
    handlers.NotifyResponse = OnNanNotifyResponse;
    handlers.EventPublishTerminated = OnNanEventPublishTerminated;
    handlers.EventMatch = OnNanEventMatch;
    handlers.EventMatchExpired = OnNanEventMatchExpired;
    handlers.EventSubscribeTerminated = OnNanEventSubscribeTerminated;
    handlers.EventFollowup = OnNanEventFollowup;
    handlers.EventDiscEngEvent = OnNanEventDiscEngEvent;
    handlers.EventDisabled = OnNanEventDisabled;
    handlers.EventTca = OnNanEventTca;
    handlers.EventBeaconSdfPayload = OnNanEventBeaconSdfPayload;
    handlers.EventDataRequest = OnNanEventDataRequest;
    handlers.EventDataConfirm = OnNanEventDataConfirm;
    handlers.EventDataEnd = OnNanEventDataEnd;
    handlers.EventTransmitFollowup = OnNanEventTransmitFollowup;

    if (mVM == NULL) {
        env->GetJavaVM(&mVM);
        mCls = (jclass) env->NewGlobalRef(cls);
    }

    return hal_fn.wifi_nan_register_handler(handle, handlers);
}

static jint android_net_wifi_nan_enable_request(JNIEnv *env, jclass cls,
                                                jshort transaction_id,
                                                jclass wifi_native_cls,
                                                jint iface,
                                                jobject config_request) {
    JNIHelper helper(env);
    wifi_interface_handle handle = getIfaceHandle(helper, wifi_native_cls, iface);

    ALOGD("android_net_wifi_nan_enable_request handle=%p, id=%d",
          handle, transaction_id);

    NanEnableRequest msg;
    memset(&msg, 0, sizeof(NanEnableRequest));

    /* configurable settings */
    msg.config_support_5g = 1;
    msg.support_5g_val = helper.getBoolField(config_request, "mSupport5gBand");
    msg.master_pref = helper.getIntField(config_request, "mMasterPreference");
    msg.cluster_low = helper.getIntField(config_request, "mClusterLow");
    msg.cluster_high = helper.getIntField(config_request, "mClusterHigh");

    return hal_fn.wifi_nan_enable_request(transaction_id, handle, &msg);
}

static jint android_net_wifi_nan_config_request(JNIEnv *env, jclass cls,
                                                jshort transaction_id,
                                                jclass wifi_native_cls,
                                                jint iface,
                                                jobject config_request) {
    JNIHelper helper(env);
    wifi_interface_handle handle = getIfaceHandle(helper, wifi_native_cls, iface);

    ALOGD("android_net_wifi_nan_config_request handle=%p, id=%d",
          handle, transaction_id);

    NanConfigRequest msg;
    memset(&msg, 0, sizeof(NanConfigRequest));

    /* configurable settings */
    msg.config_master_pref = 1;
    msg.master_pref = helper.getIntField(config_request, "mMasterPreference");

    return hal_fn.wifi_nan_config_request(transaction_id, handle, &msg);
}

static jint android_net_wifi_nan_get_capabilities(JNIEnv *env, jclass cls,
                                                  jshort transaction_id,
                                                  jclass wifi_native_cls,
                                                  jint iface) {
  JNIHelper helper(env);
  wifi_interface_handle handle = getIfaceHandle(helper, wifi_native_cls, iface);

  ALOGD("android_net_wifi_nan_get_capabilities handle=%p, id=%d", handle,
        transaction_id);

  return hal_fn.wifi_nan_get_capabilities(transaction_id, handle);
}

static jint android_net_wifi_nan_disable_request(JNIEnv *env, jclass cls,
                                                 jshort transaction_id,
                                                 jclass wifi_native_cls,
                                                 jint iface) {
    JNIHelper helper(env);
    wifi_interface_handle handle = getIfaceHandle(helper, wifi_native_cls, iface);

    ALOGD("android_net_wifi_nan_disable_request handle=%p, id=%d",
          handle, transaction_id);

    return hal_fn.wifi_nan_disable_request(transaction_id, handle);
}

static jint android_net_wifi_nan_publish(JNIEnv *env, jclass cls,
                                         jshort transaction_id,
                                         jint publish_id,
                                         jclass wifi_native_cls,
                                         jint iface,
                                         jobject publish_config) {
    JNIHelper helper(env);
    wifi_interface_handle handle = getIfaceHandle(helper, wifi_native_cls, iface);

    ALOGD("android_net_wifi_nan_publish handle=%p, id=%d", handle, transaction_id);

    NanPublishRequest msg;
    memset(&msg, 0, sizeof(NanPublishRequest));

    /* hard-coded settings - TBD: move to configurable */
    msg.period = 500;
    msg.publish_match_indicator = NAN_MATCH_ALG_MATCH_ONCE;
    msg.rssi_threshold_flag = 0;
    msg.connmap = 0;

    /* configurable settings */
    msg.publish_id = publish_id;
    msg.publish_type = (NanPublishType)helper.getIntField(publish_config, "mPublishType");

    size_t array_length;
    helper.getByteArrayField(publish_config, "mServiceName", msg.service_name,
                             &array_length, NAN_MAX_SERVICE_NAME_LEN);
    msg.service_name_len = array_length;
    if (array_length > NAN_MAX_SERVICE_NAME_LEN) {
        ALOGE("Length of service name field larger than max allowed");
        return 0;
    }

    helper.getByteArrayField(publish_config, "mServiceSpecificInfo",
                             msg.service_specific_info, &array_length,
                             NAN_MAX_SERVICE_SPECIFIC_INFO_LEN);
    msg.service_specific_info_len = array_length;
    if (array_length > NAN_MAX_SERVICE_SPECIFIC_INFO_LEN) {
        ALOGE("Length of service specific info field larger than max allowed");
        return 0;
    }

    if (msg.publish_type == NAN_PUBLISH_TYPE_UNSOLICITED) {
        helper.getByteArrayField(publish_config, "mMatchFilter",
                                 msg.tx_match_filter, &array_length,
                                 NAN_MAX_MATCH_FILTER_LEN);
        msg.tx_match_filter_len = array_length;
        if (array_length > NAN_MAX_MATCH_FILTER_LEN) {
            ALOGE("Length of match filter info field larger than max allowed");
            return 0;
        }
    } else {
        helper.getByteArrayField(publish_config, "mMatchFilter",
                                 msg.rx_match_filter, &array_length,
                                 NAN_MAX_MATCH_FILTER_LEN);
        msg.rx_match_filter_len = array_length;
        if (array_length > NAN_MAX_MATCH_FILTER_LEN) {
            ALOGE("Length of match filter info field larger than max allowed");
            return 0;
        }
    }

    msg.publish_count = helper.getIntField(publish_config, "mPublishCount");
    msg.ttl = helper.getIntField(publish_config, "mTtlSec");

    msg.tx_type = NAN_TX_TYPE_BROADCAST;
    if (msg.publish_type != NAN_PUBLISH_TYPE_UNSOLICITED)
      msg.tx_type = NAN_TX_TYPE_UNICAST;

    msg.recv_indication_cfg = 0;
    if (!helper.getBoolField(publish_config, "mEnableTerminateNotification")) {
      msg.recv_indication_cfg |= 0x1;
    }

    return hal_fn.wifi_nan_publish_request(transaction_id, handle, &msg);
}

static jint android_net_wifi_nan_subscribe(JNIEnv *env, jclass cls,
                                           jshort transaction_id,
                                           jint subscribe_id,
                                           jclass wifi_native_cls,
                                           jint iface,
                                           jobject subscribe_config) {
    JNIHelper helper(env);
    wifi_interface_handle handle = getIfaceHandle(helper, wifi_native_cls, iface);

    ALOGD("android_net_wifi_nan_subscribe handle=%p, id=%d", handle, transaction_id);

    NanSubscribeRequest msg;
    memset(&msg, 0, sizeof(NanSubscribeRequest));

    /* hard-coded settings - TBD: move to configurable */
    msg.period = 500;
    msg.serviceResponseFilter = NAN_SRF_ATTR_PARTIAL_MAC_ADDR;
    msg.serviceResponseInclude = NAN_SRF_INCLUDE_RESPOND;
    msg.useServiceResponseFilter = NAN_DO_NOT_USE_SRF;
    msg.ssiRequiredForMatchIndication = NAN_SSI_NOT_REQUIRED_IN_MATCH_IND;
    msg.rssi_threshold_flag = 0;
    msg.connmap = 0;
    msg.num_intf_addr_present = 0;

    /* configurable settings */
    msg.subscribe_id = subscribe_id;
    msg.subscribe_type = (NanSubscribeType)helper.getIntField(subscribe_config, "mSubscribeType");

    size_t array_length;
    helper.getByteArrayField(subscribe_config, "mServiceName", msg.service_name,
                             &array_length, NAN_MAX_SERVICE_NAME_LEN);
    msg.service_name_len = array_length;
    if (array_length > NAN_MAX_SERVICE_NAME_LEN) {
        ALOGE("Length of service name field larger than max allowed");
        return 0;
    }

    helper.getByteArrayField(subscribe_config, "mServiceSpecificInfo",
                             msg.service_specific_info, &array_length,
                             NAN_MAX_SERVICE_SPECIFIC_INFO_LEN);
    msg.service_specific_info_len = array_length;
    if (array_length > NAN_MAX_SERVICE_SPECIFIC_INFO_LEN) {
        ALOGE("Length of service specific info field larger than max allowed");
        return 0;
    }

    if (msg.subscribe_type == NAN_SUBSCRIBE_TYPE_ACTIVE) {
        helper.getByteArrayField(subscribe_config, "mMatchFilter",
                                 msg.tx_match_filter, &array_length,
                                 NAN_MAX_MATCH_FILTER_LEN);
        msg.tx_match_filter_len = array_length;
        if (array_length > NAN_MAX_MATCH_FILTER_LEN) {
            ALOGE("Length of match filter field larger than max allowed");
            return 0;
        }
    } else {
        helper.getByteArrayField(subscribe_config, "mMatchFilter",
                                 msg.rx_match_filter, &array_length,
                                 NAN_MAX_MATCH_FILTER_LEN);
        msg.rx_match_filter_len = array_length;
        if (array_length > NAN_MAX_MATCH_FILTER_LEN) {
            ALOGE("Length of match filter field larger than max allowed");
            return 0;
        }
    }

    msg.subscribe_count = helper.getIntField(subscribe_config, "mSubscribeCount");
    msg.ttl = helper.getIntField(subscribe_config, "mTtlSec");
    msg.subscribe_match_indicator = (NanMatchAlg) helper.getIntField(
      subscribe_config, "mMatchStyle");

    msg.recv_indication_cfg = 0;
    if (!helper.getBoolField(subscribe_config, "mEnableTerminateNotification")) {
      msg.recv_indication_cfg |= 0x1;
    }

    return hal_fn.wifi_nan_subscribe_request(transaction_id, handle, &msg);
}

static jint android_net_wifi_nan_send_message(JNIEnv *env, jclass cls,
                                              jshort transaction_id,
                                              jclass wifi_native_cls,
                                              jint iface,
                                              jint pub_sub_id,
                                              jint req_instance_id,
                                              jbyteArray dest,
                                              jbyteArray message) {
    JNIHelper helper(env);
    wifi_interface_handle handle = getIfaceHandle(helper, wifi_native_cls, iface);

    ALOGD("android_net_wifi_nan_send_message handle=%p, id=%d", handle, transaction_id);

    NanTransmitFollowupRequest msg;
    memset(&msg, 0, sizeof(NanTransmitFollowupRequest));

    /* hard-coded settings - TBD: move to configurable */
    msg.publish_subscribe_id = pub_sub_id;
    msg.requestor_instance_id = req_instance_id;
    msg.priority = NAN_TX_PRIORITY_NORMAL;
    msg.dw_or_faw = NAN_TRANSMIT_IN_DW;

    /* configurable settings */
    if (message == NULL) {
        msg.service_specific_info_len = 0;
    } else {
        msg.service_specific_info_len = helper.getArrayLength(message);

        ScopedBytesRO messageBytes(env, message);
        memcpy(msg.service_specific_info, (byte*) messageBytes.get(), msg.service_specific_info_len);
    }

    ScopedBytesRO destBytes(env, dest);
    memcpy(msg.addr, (byte*) destBytes.get(), 6);

    return hal_fn.wifi_nan_transmit_followup_request(transaction_id, handle, &msg);
}

static jint android_net_wifi_nan_stop_publish(JNIEnv *env, jclass cls,
                                              jshort transaction_id,
                                              jclass wifi_native_cls,
                                              jint iface,
                                              jint pub_sub_id) {
    JNIHelper helper(env);
    wifi_interface_handle handle = getIfaceHandle(helper, wifi_native_cls, iface);

    ALOGD("android_net_wifi_nan_stop_publish handle=%p, id=%d", handle, transaction_id);

    NanPublishCancelRequest msg;
    memset(&msg, 0, sizeof(NanPublishCancelRequest));

    msg.publish_id = pub_sub_id;

    return hal_fn.wifi_nan_publish_cancel_request(transaction_id, handle, &msg);
}

static jint android_net_wifi_nan_stop_subscribe(JNIEnv *env, jclass cls,
                                              jshort transaction_id,
                                              jclass wifi_native_cls,
                                              jint iface,
                                              jint pub_sub_id) {
    JNIHelper helper(env);
    wifi_interface_handle handle = getIfaceHandle(helper, wifi_native_cls, iface);

    ALOGD("android_net_wifi_nan_stop_subscribe handle=%p, id=%d", handle, transaction_id);

    NanSubscribeCancelRequest msg;
    memset(&msg, 0, sizeof(NanSubscribeCancelRequest));

    msg.subscribe_id = pub_sub_id;

    return hal_fn.wifi_nan_subscribe_cancel_request(transaction_id, handle, &msg);
}

static jint android_net_wifi_nan_create_nan_network_interface(
    JNIEnv *env, jclass cls, jshort transaction_id, jclass wifi_native_cls,
    jint iface, jstring interface_name) {
  JNIHelper helper(env);
  wifi_interface_handle handle = getIfaceHandle(helper, wifi_native_cls, iface);

  ALOGD("android_net_wifi_nan_create_nan_network_interface handle=%p, id=%d",
        handle, transaction_id);

  ScopedUtfChars chars(env, interface_name);
  if (chars.c_str() == NULL) {
    return 0;
  }

  return hal_fn.wifi_nan_data_interface_create(transaction_id, handle,
                                               (char*) chars.c_str());
}

static jint android_net_wifi_nan_delete_nan_network_interface(
    JNIEnv *env, jclass cls, jshort transaction_id, jclass wifi_native_cls,
    jint iface, jstring interface_name) {
  JNIHelper helper(env);
  wifi_interface_handle handle = getIfaceHandle(helper, wifi_native_cls, iface);

  ALOGD("android_net_wifi_nan_delete_nan_network_interface handle=%p, id=%d",
        handle, transaction_id);

  ScopedUtfChars chars(env, interface_name);
  if (chars.c_str() == NULL) {
    return 0;
  }

  return hal_fn.wifi_nan_data_interface_delete(transaction_id, handle,
                                               (char*) chars.c_str());
}

static jint android_net_wifi_nan_initiate_nan_data_path(
    JNIEnv *env, jclass cls, jshort transaction_id, jclass wifi_native_cls,
    jint iface, jint pub_sub_id, jint channel_request_type, jint channel,
    jbyteArray peer, jstring interface_name, jbyteArray message) {
  JNIHelper helper(env);
  wifi_interface_handle handle = getIfaceHandle(helper, wifi_native_cls, iface);

  ALOGD("android_net_wifi_nan_initiate_nan_data_path handle=%p, id=%d", handle,
        transaction_id);

  NanDataPathInitiatorRequest msg;
  memset(&msg, 0, sizeof(NanDataPathInitiatorRequest));

  msg.service_instance_id = pub_sub_id;
  msg.channel_request_type = (NanDataPathChannelCfg) channel_request_type;
  msg.channel = channel;

  ScopedBytesRO peerBytes(env, peer);
  memcpy(msg.peer_disc_mac_addr, (byte *)peerBytes.get(), 6);

  ScopedUtfChars chars(env, interface_name);
  if (chars.c_str() == NULL) {
    return 0;
  }
  strcpy(msg.ndp_iface, chars.c_str());

  // TODO: b/26564544: add security configuration
  msg.ndp_cfg.security_cfg = NAN_DP_CONFIG_NO_SECURITY;

  // TODO: b/29065317: add QoS configuration
  msg.ndp_cfg.qos_cfg = NAN_DP_CONFIG_NO_QOS;

  msg.app_info.ndp_app_info_len = helper.getArrayLength(message);

  ScopedBytesRO messageBytes(env, message);
  memcpy(msg.app_info.ndp_app_info, (byte *)messageBytes.get(), helper.getArrayLength(message));

  return hal_fn.wifi_nan_data_request_initiator(transaction_id, handle, &msg);
}

static jint android_net_wifi_nan_respond_nan_data_path_request(
    JNIEnv *env, jclass cls, jshort transaction_id, jclass wifi_native_cls,
    jint iface, jboolean accept, jint ndp_id, jstring interface_name,
    jbyteArray message) {
  JNIHelper helper(env);
  wifi_interface_handle handle = getIfaceHandle(helper, wifi_native_cls, iface);

  ALOGD("android_net_wifi_nan_respond_nan_data_path_request handle=%p, id=%d",
        handle, transaction_id);

  NanDataPathIndicationResponse msg;
  memset(&msg, 0, sizeof(NanDataPathIndicationResponse));

  msg.ndp_instance_id = ndp_id;

  ScopedUtfChars chars(env, interface_name);
  if (chars.c_str() == NULL) {
    return 0;
  }
  strcpy(msg.ndp_iface, chars.c_str());

  // TODO: b/26564544: add security configuration
  msg.ndp_cfg.security_cfg = NAN_DP_CONFIG_NO_SECURITY;

  // TODO: b/29065317: add QoS configuration
  msg.ndp_cfg.qos_cfg = NAN_DP_CONFIG_NO_QOS;

  msg.app_info.ndp_app_info_len = helper.getArrayLength(message);

  ScopedBytesRO messageBytes(env, message);
  memcpy(msg.app_info.ndp_app_info, (byte *)messageBytes.get(), helper.getArrayLength(message));

  msg.rsp_code = accept ? NAN_DP_REQUEST_ACCEPT : NAN_DP_REQUEST_REJECT;

  return hal_fn.wifi_nan_data_indication_response(transaction_id, handle, &msg);
}

static jint android_net_wifi_nan_end_nan_data_path(JNIEnv *env, jclass cls,
                                                   jshort transaction_id,
                                                   jclass wifi_native_cls,
                                                   jint iface, jint ndp_id) {
  JNIHelper helper(env);
  wifi_interface_handle handle = getIfaceHandle(helper, wifi_native_cls, iface);

  ALOGD("android_net_wifi_nan_end_nan_data_path handle=%p, id=%d", handle,
        transaction_id);

  NanDataPathEndRequest* msg = (NanDataPathEndRequest*)malloc(sizeof(NanDataPathEndRequest) + sizeof(NanDataPathId));

  msg->num_ndp_instances = 1;
  msg->ndp_instance_id[0] = ndp_id;

  jint status = hal_fn.wifi_nan_data_end(transaction_id, handle, msg);

  free(msg);

  return status;
}

// ----------------------------------------------------------------------------

/*
 * JNI registration.
 */

static JNINativeMethod gWifiNanMethods[] = {
    /* name, signature, funcPtr */
    {"initAwareHandlersNative", "(Ljava/lang/Class;I)I", (void*)android_net_wifi_nan_register_handler },
    {"getCapabilitiesNative", "(SLjava/lang/Class;I)I", (void*)android_net_wifi_nan_get_capabilities },
    {"enableAndConfigureNative", "(SLjava/lang/Class;ILandroid/net/wifi/aware/ConfigRequest;)I", (void*)android_net_wifi_nan_enable_request },
    {"updateConfigurationNative", "(SLjava/lang/Class;ILandroid/net/wifi/aware/ConfigRequest;)I", (void*)android_net_wifi_nan_config_request },
    {"disableNative", "(SLjava/lang/Class;I)I", (void*)android_net_wifi_nan_disable_request },
    {"publishNative", "(SILjava/lang/Class;ILandroid/net/wifi/aware/PublishConfig;)I", (void*)android_net_wifi_nan_publish },
    {"subscribeNative", "(SILjava/lang/Class;ILandroid/net/wifi/aware/SubscribeConfig;)I", (void*)android_net_wifi_nan_subscribe },
    {"sendMessageNative", "(SLjava/lang/Class;III[B[B)I", (void*)android_net_wifi_nan_send_message },
    {"stopPublishNative", "(SLjava/lang/Class;II)I", (void*)android_net_wifi_nan_stop_publish },
    {"stopSubscribeNative", "(SLjava/lang/Class;II)I", (void*)android_net_wifi_nan_stop_subscribe },
    {"createAwareNetworkInterfaceNative", "(SLjava/lang/Class;ILjava/lang/String;)I", (void*)android_net_wifi_nan_create_nan_network_interface },
    {"deleteAwareNetworkInterfaceNative", "(SLjava/lang/Class;ILjava/lang/String;)I", (void*)android_net_wifi_nan_delete_nan_network_interface },
    {"initiateDataPathNative", "(SLjava/lang/Class;IIII[BLjava/lang/String;[B)I", (void*)android_net_wifi_nan_initiate_nan_data_path },
    {"respondToDataPathRequestNative", "(SLjava/lang/Class;IZILjava/lang/String;[B)I", (void*)android_net_wifi_nan_respond_nan_data_path_request },
    {"endDataPathNative", "(SLjava/lang/Class;II)I", (void*)android_net_wifi_nan_end_nan_data_path },
};

/* User to register native functions */
extern "C"
jint Java_com_android_server_wifi_aware_WifiAwareNative_registerAwareNatives(JNIEnv* env, jclass clazz) {
    return jniRegisterNativeMethods(env,
            "com/android/server/wifi/aware/WifiAwareNative", gWifiNanMethods, NELEM(gWifiNanMethods));
}

}; // namespace android
