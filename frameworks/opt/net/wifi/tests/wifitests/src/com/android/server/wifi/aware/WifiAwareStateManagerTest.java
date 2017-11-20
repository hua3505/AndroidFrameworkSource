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

import static org.hamcrest.core.IsEqual.equalTo;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyShort;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.test.MockAnswerUtil;
import android.app.test.TestAlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.wifi.RttManager;
import android.net.wifi.aware.ConfigRequest;
import android.net.wifi.aware.IWifiAwareDiscoverySessionCallback;
import android.net.wifi.aware.IWifiAwareEventCallback;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.WifiAwareDiscoverySessionCallback;
import android.net.wifi.aware.WifiAwareManager;
import android.os.Message;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

import libcore.util.HexEncoding;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;

/**
 * Unit test harness for WifiAwareStateManager.
 */
@SmallTest
public class WifiAwareStateManagerTest {
    private TestLooper mMockLooper;
    private Random mRandomNg = new Random(15687);
    private WifiAwareStateManager mDut;
    @Mock private WifiAwareNative mMockNative;
    @Mock private Context mMockContext;
    @Mock private AppOpsManager mMockAppOpsManager;
    @Mock private WifiAwareRttStateManager mMockAwareRttStateManager;
    TestAlarmManager mAlarmManager;
    @Mock private WifiAwareDataPathStateManager mMockAwareDataPathStatemanager;

    @Rule
    public ErrorCollector collector = new ErrorCollector();

    private static final byte[] ALL_ZERO_MAC = new byte[] {0, 0, 0, 0, 0, 0};

    /**
     * Pre-test configuration. Initialize and install mocks.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mAlarmManager = new TestAlarmManager();
        when(mMockContext.getSystemService(Context.ALARM_SERVICE))
                .thenReturn(mAlarmManager.getAlarmManager());

        when(mMockContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(
                mock(ConnectivityManager.class));
        when(mMockContext.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(mMockAppOpsManager);
        when(mMockContext.checkPermission(eq(android.Manifest.permission.ACCESS_FINE_LOCATION),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED);
        when(mMockContext.checkPermission(eq(Manifest.permission.ACCESS_COARSE_LOCATION),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED);
        when(mMockAppOpsManager.noteOp(eq(AppOpsManager.OP_FINE_LOCATION), anyInt(),
                anyString())).thenReturn(AppOpsManager.MODE_ERRORED);
        when(mMockAppOpsManager.noteOp(eq(AppOpsManager.OP_COARSE_LOCATION), anyInt(),
                anyString())).thenReturn(AppOpsManager.MODE_ERRORED);

        mMockLooper = new TestLooper();

        mDut = installNewAwareStateManager();
        mDut.start(mMockContext, mMockLooper.getLooper());
        installMocksInStateManager(mDut, mMockAwareRttStateManager, mMockAwareDataPathStatemanager);

        when(mMockNative.enableAndConfigure(anyShort(), any(ConfigRequest.class), anyBoolean()))
                .thenReturn(true);
        when(mMockNative.disable(anyShort())).thenReturn(true);
        when(mMockNative.publish(anyShort(), anyInt(), any(PublishConfig.class))).thenReturn(true);
        when(mMockNative.subscribe(anyShort(), anyInt(), any(SubscribeConfig.class)))
                .thenReturn(true);
        when(mMockNative.sendMessage(anyShort(), anyInt(), anyInt(), any(byte[].class),
                any(byte[].class), anyInt())).thenReturn(true);
        when(mMockNative.stopPublish(anyShort(), anyInt())).thenReturn(true);
        when(mMockNative.stopSubscribe(anyShort(), anyInt())).thenReturn(true);
        when(mMockNative.getCapabilities(anyShort())).thenReturn(true);

        installMockWifiAwareNative(mMockNative);
    }

    /**
     * Validate that Aware data-path interfaces are brought up and down correctly.
     */
    @Test
    public void testAwareDataPathInterfaceUpDown() throws Exception {
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mMockContext, mMockNative, mMockAwareDataPathStatemanager);

        // (1) enable usage
        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        validateCorrectAwareStatusChangeBroadcast(inOrder, true);
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();
        inOrder.verify(mMockAwareDataPathStatemanager).createAllInterfaces();
        collector.checkThat("usage enabled", mDut.isUsageEnabled(), equalTo(true));

        // (2) disable usage
        mDut.disableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockAwareDataPathStatemanager).onAwareDownCleanupDataPaths();
        inOrder.verify(mMockNative).disable((short) 0);
        inOrder.verify(mMockNative).deInitAware();
        validateCorrectAwareStatusChangeBroadcast(inOrder, false);
        inOrder.verify(mMockAwareDataPathStatemanager).deleteAllInterfaces();
        collector.checkThat("usage disabled", mDut.isUsageEnabled(), equalTo(false));

        verifyNoMoreInteractions(mMockNative, mMockAwareDataPathStatemanager);
    }

    /**
     * Validate that APIs aren't functional when usage is disabled.
     */
    @Test
    public void testDisableUsageDisablesApis() throws Exception {
        final int clientId = 12314;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();

        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        InOrder inOrder = inOrder(mMockContext, mMockNative, mockCallback);

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);

        // (1) check initial state
        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        validateCorrectAwareStatusChangeBroadcast(inOrder, true);
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();
        collector.checkThat("usage enabled", mDut.isUsageEnabled(), equalTo(true));

        // (2) disable usage and validate state
        mDut.disableUsage();
        mMockLooper.dispatchAll();
        collector.checkThat("usage disabled", mDut.isUsageEnabled(), equalTo(false));
        inOrder.verify(mMockNative).disable((short) 0);
        inOrder.verify(mMockNative).deInitAware();
        validateCorrectAwareStatusChangeBroadcast(inOrder, false);

        // (3) try connecting and validate that get nothing (app should be aware of non-availability
        // through state change broadcast and/or query API)
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();

        verifyNoMoreInteractions(mMockNative, mockCallback);
    }

    /**
     * Validate that when API usage is disabled while in the middle of a connection that internal
     * state is cleaned-up, and that all subsequent operations are NOP. Then enable usage again and
     * validate that operates correctly.
     */
    @Test
    public void testDisableUsageFlow() throws Exception {
        final int clientId = 12341;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();

        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mMockContext, mMockNative, mockCallback);

        // (1) check initial state
        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        validateCorrectAwareStatusChangeBroadcast(inOrder, true);
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        collector.checkThat("usage enabled", mDut.isUsageEnabled(), equalTo(true));

        // (2) connect (successfully)
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (3) disable usage & verify callbacks
        mDut.disableUsage();
        mMockLooper.dispatchAll();
        collector.checkThat("usage disabled", mDut.isUsageEnabled(), equalTo(false));
        inOrder.verify(mMockNative).disable((short) 0);
        inOrder.verify(mMockNative).deInitAware();
        validateCorrectAwareStatusChangeBroadcast(inOrder, false);
        validateInternalClientInfoCleanedUp(clientId);

        // (4) try connecting again and validate that just get an onAwareDown
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();

        // (5) disable usage again and validate that not much happens
        mDut.disableUsage();
        mMockLooper.dispatchAll();
        collector.checkThat("usage disabled", mDut.isUsageEnabled(), equalTo(false));

        // (6) enable usage
        mDut.enableUsage();
        mMockLooper.dispatchAll();
        collector.checkThat("usage enabled", mDut.isUsageEnabled(), equalTo(true));
        inOrder.verify(mMockNative).deInitAware();
        validateCorrectAwareStatusChangeBroadcast(inOrder, true);

        // (7) connect (should be successful)
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        verifyNoMoreInteractions(mMockNative, mockCallback);
    }

    /**
     * Validates that all events are delivered with correct arguments. Validates
     * that IdentityChanged not delivered if configuration disables delivery.
     */
    @Test
    public void testAwareEventsDelivery() throws Exception {
        final int clientId1 = 1005;
        final int clientId2 = 1007;
        final int clusterLow = 5;
        final int clusterHigh = 100;
        final int masterPref = 111;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final int reason = WifiAwareNative.AWARE_STATUS_ERROR;
        final byte[] someMac = HexEncoding.decode("000102030405".toCharArray(), false);
        final byte[] someMac2 = HexEncoding.decode("060708090A0B".toCharArray(), false);

        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterLow(clusterLow)
                .setClusterHigh(clusterHigh).setMasterPreference(masterPref)
                .build();

        IWifiAwareEventCallback mockCallback1 = mock(IWifiAwareEventCallback.class);
        IWifiAwareEventCallback mockCallback2 = mock(IWifiAwareEventCallback.class);
        ArgumentCaptor<Short> transactionIdCapture = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mockCallback1, mockCallback2, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionIdCapture.capture());
        mDut.onCapabilitiesUpdateResponse(transactionIdCapture.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect 1st and 2nd clients
        mDut.connect(clientId1, uid, pid, callingPackage, mockCallback1, configRequest, false);
        mDut.connect(clientId2, uid, pid, callingPackage, mockCallback2, configRequest, true);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionIdCapture.capture(),
                eq(configRequest), eq(true));
        short transactionId = transactionIdCapture.getValue();
        mDut.onConfigSuccessResponse(transactionId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback1).onConnectSuccess(clientId1);
        inOrder.verify(mockCallback2).onConnectSuccess(clientId2);

        // (2) deliver Aware events - without LOCATIONING permission
        mDut.onClusterChangeNotification(WifiAwareClientState.CLUSTER_CHANGE_EVENT_STARTED,
                someMac);
        mDut.onInterfaceAddressChangeNotification(someMac);
        mMockLooper.dispatchAll();

        inOrder.verify(mockCallback2).onIdentityChanged(ALL_ZERO_MAC);

        // (3) deliver new identity - still without LOCATIONING permission (should get an event)
        mDut.onInterfaceAddressChangeNotification(someMac2);
        mMockLooper.dispatchAll();

        inOrder.verify(mockCallback2).onIdentityChanged(ALL_ZERO_MAC);

        // (4) deliver same identity - still without LOCATIONING permission (should
        // not get an event)
        mDut.onInterfaceAddressChangeNotification(someMac2);
        mMockLooper.dispatchAll();

        // (5) deliver new identity - with LOCATIONING permission
        when(mMockContext.checkPermission(eq(Manifest.permission.ACCESS_COARSE_LOCATION),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mMockAppOpsManager.noteOp(eq(AppOpsManager.OP_COARSE_LOCATION), anyInt(),
                anyString())).thenReturn(AppOpsManager.MODE_ALLOWED);
        mDut.onInterfaceAddressChangeNotification(someMac);
        mMockLooper.dispatchAll();

        inOrder.verify(mockCallback2).onIdentityChanged(someMac);

        // (6) Aware down (no feedback)
        mDut.onAwareDownNotification(reason);
        mMockLooper.dispatchAll();

        validateInternalClientInfoCleanedUp(clientId1);
        validateInternalClientInfoCleanedUp(clientId2);

        verifyNoMoreInteractions(mockCallback1, mockCallback2, mMockNative);
    }

    /**
     * Validate that when the HAL doesn't respond we get a TIMEOUT (which
     * results in a failure response) at which point we can process additional
     * commands. Steps: (1) connect, (2) publish - timeout, (3) publish +
     * success.
     */
    @Test
    public void testHalNoResponseTimeout() throws Exception {
        final int clientId = 12341;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final PublishConfig publishConfig = new PublishConfig.Builder().build();

        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockSessionCallback);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect (successfully)
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (2) publish + timeout
        mDut.publish(clientId, publishConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(anyShort(), eq(0), eq(publishConfig));
        assertTrue(mAlarmManager.dispatch(WifiAwareStateManager.HAL_COMMAND_TIMEOUT_TAG));
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionConfigFail(WifiAwareNative.AWARE_STATUS_ERROR);
        validateInternalNoSessions(clientId);

        // (3) publish + success
        mDut.publish(clientId, publishConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), true, 9999);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(anyInt());

        verifyNoMoreInteractions(mMockNative, mockCallback, mockSessionCallback);
    }

    /**
     * Validates publish flow: (1) initial publish (2) fail. Expected: get a
     * failure callback.
     */
    @Test
    public void testPublishFail() throws Exception {
        final int clientId = 1005;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final int reasonFail = WifiAwareNative.AWARE_STATUS_ERROR;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        PublishConfig publishConfig = new PublishConfig.Builder().build();

        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (0) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                eq(configRequest), eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (1) initial publish
        mDut.publish(clientId, publishConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));

        // (2) publish failure
        mDut.onSessionConfigFailResponse(transactionId.getValue(), true, reasonFail);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionConfigFail(reasonFail);
        validateInternalNoSessions(clientId);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    /**
     * Validates the publish flow: (1) initial publish (2) success (3)
     * termination (e.g. DONE) (4) update session attempt (5) terminateSession
     * (6) update session attempt. Expected: session ID callback + session
     * cleaned-up.
     */
    @Test
    public void testPublishSuccessTerminated() throws Exception {
        final int clientId = 2005;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final int reasonTerminate = WifiAwareDiscoverySessionCallback.TERMINATE_REASON_DONE;
        final int publishId = 15;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        PublishConfig publishConfig = new PublishConfig.Builder().build();

        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (0) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                eq(configRequest), eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (1) initial publish
        mDut.publish(clientId, publishConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));

        // (2) publish success
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), true, publishId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());

        // (3) publish termination (from firmware - not app!)
        mDut.onSessionTerminatedNotification(publishId, reasonTerminate, true);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionTerminated(reasonTerminate);

        // (4) app update session (race condition: app didn't get termination
        // yet)
        mDut.updatePublish(clientId, sessionId.getValue(), publishConfig);
        mMockLooper.dispatchAll();

        // (5) app terminates session
        mDut.terminateSession(clientId, sessionId.getValue());
        mMockLooper.dispatchAll();

        // (6) app updates session (app already knows that terminated - will get
        // a local FAIL).
        mDut.updatePublish(clientId, sessionId.getValue(), publishConfig);
        mMockLooper.dispatchAll();

        validateInternalSessionInfoCleanedUp(clientId, sessionId.getValue());

        verifyNoMoreInteractions(mockSessionCallback, mMockNative);
    }

    /**
     * Validate the publish flow: (1) initial publish + (2) success + (3) update
     * + (4) update fails + (5) update + (6). Expected: session is still alive
     * after update failure so second update succeeds (no callbacks).
     */
    @Test
    public void testPublishUpdateFail() throws Exception {
        final int clientId = 2005;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final int publishId = 15;
        final int reasonFail = WifiAwareNative.AWARE_STATUS_ERROR;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        PublishConfig publishConfig = new PublishConfig.Builder().build();

        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (0) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (1) initial publish
        mDut.publish(clientId, publishConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));

        // (2) publish success
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), true, publishId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());

        // (3) update publish
        mDut.updatePublish(clientId, sessionId.getValue(), publishConfig);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(publishId),
                eq(publishConfig));

        // (4) update fails
        mDut.onSessionConfigFailResponse(transactionId.getValue(), true, reasonFail);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionConfigFail(reasonFail);

        // (5) another update publish
        mDut.updatePublish(clientId, sessionId.getValue(), publishConfig);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(publishId),
                eq(publishConfig));

        // (6) update succeeds
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), true, publishId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionConfigSuccess();

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    /**
     * Validate race condition: publish pending but session terminated (due to
     * disconnect - can't terminate such a session directly from app). Need to
     * make sure that once publish succeeds (failure isn't a problem) the
     * session is immediately terminated since no-one is listening for it.
     */
    @Test
    public void testDisconnectWhilePublishPending() throws Exception {
        final int clientId = 2005;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final int publishId = 15;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        PublishConfig publishConfig = new PublishConfig.Builder().build();

        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (0) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (1) initial publish
        mDut.publish(clientId, publishConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));

        // (2) disconnect (but doesn't get executed until get response for
        // publish command)
        mDut.disconnect(clientId);
        mMockLooper.dispatchAll();

        // (3) publish success
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), true, publishId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(anyInt());
        inOrder.verify(mMockNative).stopPublish(transactionId.capture(), eq(publishId));
        inOrder.verify(mMockNative).disable((short) 0);

        validateInternalClientInfoCleanedUp(clientId);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    /**
     * Validates subscribe flow: (1) initial subscribe (2) fail. Expected: get a
     * failure callback.
     */
    @Test
    public void testSubscribeFail() throws Exception {
        final int clientId = 1005;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final int reasonFail = WifiAwareNative.AWARE_STATUS_ERROR;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (0) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (1) initial subscribe
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));

        // (2) subscribe failure
        mDut.onSessionConfigFailResponse(transactionId.getValue(), false, reasonFail);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionConfigFail(reasonFail);
        validateInternalNoSessions(clientId);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    /**
     * Validates the subscribe flow: (1) initial subscribe (2) success (3)
     * termination (e.g. DONE) (4) update session attempt (5) terminateSession
     * (6) update session attempt. Expected: session ID callback + session
     * cleaned-up
     */
    @Test
    public void testSubscribeSuccessTerminated() throws Exception {
        final int clientId = 2005;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final int reasonTerminate = WifiAwareDiscoverySessionCallback.TERMINATE_REASON_DONE;
        final int subscribeId = 15;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (0) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (1) initial subscribe
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));

        // (2) subscribe success
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());

        // (3) subscribe termination (from firmware - not app!)
        mDut.onSessionTerminatedNotification(subscribeId, reasonTerminate, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionTerminated(reasonTerminate);

        // (4) app update session (race condition: app didn't get termination
        // yet)
        mDut.updateSubscribe(clientId, sessionId.getValue(), subscribeConfig);
        mMockLooper.dispatchAll();

        // (5) app terminates session
        mDut.terminateSession(clientId, sessionId.getValue());
        mMockLooper.dispatchAll();

        // (6) app updates session
        mDut.updateSubscribe(clientId, sessionId.getValue(), subscribeConfig);
        mMockLooper.dispatchAll();

        validateInternalSessionInfoCleanedUp(clientId, sessionId.getValue());

        verifyNoMoreInteractions(mockSessionCallback, mMockNative);
    }

    /**
     * Validate the subscribe flow: (1) initial subscribe + (2) success + (3)
     * update + (4) update fails + (5) update + (6). Expected: session is still
     * alive after update failure so second update succeeds (no callbacks).
     */
    @Test
    public void testSubscribeUpdateFail() throws Exception {
        final int clientId = 2005;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final int subscribeId = 15;
        final int reasonFail = WifiAwareNative.AWARE_STATUS_ERROR;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (0) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (1) initial subscribe
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));

        // (2) subscribe success
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());

        // (3) update subscribe
        mDut.updateSubscribe(clientId, sessionId.getValue(), subscribeConfig);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(subscribeId),
                eq(subscribeConfig));

        // (4) update fails
        mDut.onSessionConfigFailResponse(transactionId.getValue(), false, reasonFail);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionConfigFail(reasonFail);

        // (5) another update subscribe
        mDut.updateSubscribe(clientId, sessionId.getValue(), subscribeConfig);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(subscribeId),
                eq(subscribeConfig));

        // (6) update succeeds
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionConfigSuccess();

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    /**
     * Validate race condition: subscribe pending but session terminated (due to
     * disconnect - can't terminate such a session directly from app). Need to
     * make sure that once subscribe succeeds (failure isn't a problem) the
     * session is immediately terminated since no-one is listening for it.
     */
    @Test
    public void testDisconnectWhileSubscribePending() throws Exception {
        final int clientId = 2005;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final int subscribeId = 15;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (0) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (1) initial subscribe
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));

        // (2) disconnect (but doesn't get executed until get response for
        // subscribe command)
        mDut.disconnect(clientId);
        mMockLooper.dispatchAll();

        // (3) subscribe success
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(anyInt());
        inOrder.verify(mMockNative).stopSubscribe((short) 0, subscribeId);
        inOrder.verify(mMockNative).disable((short) 0);

        validateInternalClientInfoCleanedUp(clientId);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    /**
     * Validate (1) subscribe (success), (2) match (i.e. discovery), (3) message reception,
     * (4) message transmission failed (after ok queuing), (5) message transmission success.
     */
    @Test
    public void testMatchAndMessages() throws Exception {
        final int clientId = 1005;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final String serviceName = "some-service-name";
        final String ssi = "some much longer and more arbitrary data";
        final int subscribeCount = 7;
        final int reasonFail = WifiAwareNative.AWARE_STATUS_ERROR;
        final int subscribeId = 15;
        final int requestorId = 22;
        final byte[] peerMac = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String peerSsi = "some peer ssi data";
        final String peerMatchFilter = "filter binary array represented as string";
        final String peerMsg = "some message from peer";
        final int messageId = 6948;
        final int messageId2 = 6949;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(ssi.getBytes())
                .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
                .setSubscribeCount(subscribeCount).build();

        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (0) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                eq(configRequest), eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (1) subscribe
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());

        // (2) match
        mDut.onMatchNotification(subscribeId, requestorId, peerMac, peerSsi.getBytes(),
                peerMatchFilter.getBytes());
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMatch(requestorId, peerSsi.getBytes(),
                peerMatchFilter.getBytes());

        // (3) message Rx
        mDut.onMessageReceivedNotification(subscribeId, requestorId, peerMac, peerMsg.getBytes());
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageReceived(requestorId, peerMsg.getBytes());

        // (4) message Tx successful queuing
        mDut.sendMessage(clientId, sessionId.getValue(), requestorId, ssi.getBytes(), messageId, 0);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                eq(requestorId), eq(peerMac), eq(ssi.getBytes()), eq(messageId));
        short tid1 = transactionId.getValue();
        mDut.onMessageSendQueuedSuccessResponse(tid1);
        mMockLooper.dispatchAll();

        // (5) message Tx successful queuing
        mDut.sendMessage(clientId, sessionId.getValue(), requestorId, ssi.getBytes(), messageId2,
                0);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                eq(requestorId), eq(peerMac), eq(ssi.getBytes()), eq(messageId2));
        short tid2 = transactionId.getValue();
        mDut.onMessageSendQueuedSuccessResponse(tid2);
        mMockLooper.dispatchAll();

        // (4) and (5) final Tx results (on-air results)
        mDut.onMessageSendFailNotification(tid1, reasonFail);
        mDut.onMessageSendSuccessNotification(tid2);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageSendFail(messageId, reasonFail);
        inOrder.verify(mockSessionCallback).onMessageSendSuccess(messageId2);
        validateInternalSendMessageQueuesCleanedUp(messageId);
        validateInternalSendMessageQueuesCleanedUp(messageId2);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    /**
     * Summary: in a single publish session interact with multiple peers
     * (different MAC addresses).
     */
    @Test
    public void testMultipleMessageSources() throws Exception {
        final int clientId = 300;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final int clusterLow = 7;
        final int clusterHigh = 7;
        final int masterPref = 0;
        final String serviceName = "some-service-name";
        final int publishId = 88;
        final int peerId1 = 568;
        final int peerId2 = 873;
        final byte[] peerMac1 = HexEncoding.decode("000102030405".toCharArray(), false);
        final byte[] peerMac2 = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String msgFromPeer1 = "hey from 000102...";
        final String msgFromPeer2 = "hey from 0607...";
        final String msgToPeer1 = "hey there 000102...";
        final String msgToPeer2 = "hey there 0506...";
        final int msgToPeerId1 = 546;
        final int msgToPeerId2 = 9654;
        final int reason = WifiAwareNative.AWARE_STATUS_ERROR;

        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterLow(clusterLow)
                .setClusterHigh(clusterHigh).setMasterPreference(masterPref).build();

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(serviceName)
                .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED).build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockSessionCallback);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (2) publish
        mDut.publish(clientId, publishConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), true, publishId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());

        // (3) message received from peers 1 & 2
        mDut.onMessageReceivedNotification(publishId, peerId1, peerMac1, msgFromPeer1.getBytes());
        mDut.onMessageReceivedNotification(publishId, peerId2, peerMac2, msgFromPeer2.getBytes());
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageReceived(peerId1, msgFromPeer1.getBytes());
        inOrder.verify(mockSessionCallback).onMessageReceived(peerId2, msgFromPeer2.getBytes());

        // (4) sending messages back to same peers: one Tx fails, other succeeds
        mDut.sendMessage(clientId, sessionId.getValue(), peerId2, msgToPeer2.getBytes(),
                msgToPeerId2, 0);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(publishId), eq(peerId2),
                eq(peerMac2), eq(msgToPeer2.getBytes()), eq(msgToPeerId2));
        short transactionIdVal = transactionId.getValue();
        mDut.onMessageSendQueuedSuccessResponse(transactionIdVal);
        mDut.onMessageSendSuccessNotification(transactionIdVal);

        mDut.sendMessage(clientId, sessionId.getValue(), peerId1, msgToPeer1.getBytes(),
                msgToPeerId1, 0);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageSendSuccess(msgToPeerId2);
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(publishId), eq(peerId1),
                eq(peerMac1), eq(msgToPeer1.getBytes()), eq(msgToPeerId1));
        transactionIdVal = transactionId.getValue();
        mDut.onMessageSendQueuedSuccessResponse(transactionIdVal);
        mDut.onMessageSendFailNotification(transactionIdVal, reason);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageSendFail(msgToPeerId1, reason);
        validateInternalSendMessageQueuesCleanedUp(msgToPeerId1);
        validateInternalSendMessageQueuesCleanedUp(msgToPeerId2);

        verifyNoMoreInteractions(mMockNative, mockCallback, mockSessionCallback);
    }

    /**
     * Summary: interact with a peer which changed its identity (MAC address)
     * but which keeps its requestor instance ID. Should be transparent.
     */
    @Test
    public void testMessageWhilePeerChangesIdentity() throws Exception {
        final int clientId = 300;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final int clusterLow = 7;
        final int clusterHigh = 7;
        final int masterPref = 0;
        final String serviceName = "some-service-name";
        final int publishId = 88;
        final int peerId = 568;
        final byte[] peerMacOrig = HexEncoding.decode("000102030405".toCharArray(), false);
        final byte[] peerMacLater = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String msgFromPeer1 = "hey from 000102...";
        final String msgFromPeer2 = "hey from 0607...";
        final String msgToPeer1 = "hey there 000102...";
        final String msgToPeer2 = "hey there 0506...";
        final int msgToPeerId1 = 546;
        final int msgToPeerId2 = 9654;
        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterLow(clusterLow)
                .setClusterHigh(clusterHigh).setMasterPreference(masterPref).build();

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(serviceName)
                .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED).build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockSessionCallback);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (2) publish
        mDut.publish(clientId, publishConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), true, publishId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());

        // (3) message received & responded to
        mDut.onMessageReceivedNotification(publishId, peerId, peerMacOrig, msgFromPeer1.getBytes());
        mDut.sendMessage(clientId, sessionId.getValue(), peerId, msgToPeer1.getBytes(),
                msgToPeerId1, 0);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageReceived(peerId, msgFromPeer1.getBytes());
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(publishId), eq(peerId),
                eq(peerMacOrig), eq(msgToPeer1.getBytes()), eq(msgToPeerId1));
        mDut.onMessageSendQueuedSuccessResponse(transactionId.getValue());
        mDut.onMessageSendSuccessNotification(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageSendSuccess(msgToPeerId1);
        validateInternalSendMessageQueuesCleanedUp(msgToPeerId1);

        // (4) message received with same peer ID but different MAC
        mDut.onMessageReceivedNotification(publishId, peerId, peerMacLater,
                msgFromPeer2.getBytes());
        mDut.sendMessage(clientId, sessionId.getValue(), peerId, msgToPeer2.getBytes(),
                msgToPeerId2, 0);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageReceived(peerId, msgFromPeer2.getBytes());
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(publishId), eq(peerId),
                eq(peerMacLater), eq(msgToPeer2.getBytes()), eq(msgToPeerId2));
        mDut.onMessageSendQueuedSuccessResponse(transactionId.getValue());
        mDut.onMessageSendSuccessNotification(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageSendSuccess(msgToPeerId2);
        validateInternalSendMessageQueuesCleanedUp(msgToPeerId2);

        verifyNoMoreInteractions(mMockNative, mockCallback, mockSessionCallback);
    }

    /**
     * Validate that get failure (with correct code) when trying to send a
     * message to an invalid peer ID.
     */
    @Test
    public void testSendMessageToInvalidPeerId() throws Exception {
        final int clientId = 1005;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final String ssi = "some much longer and more arbitrary data";
        final int subscribeId = 15;
        final int requestorId = 22;
        final byte[] peerMac = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String peerSsi = "some peer ssi data";
        final String peerMatchFilter = "filter binary array represented as string";
        final int messageId = 6948;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (2) subscribe & match
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mDut.onMatchNotification(subscribeId, requestorId, peerMac, peerSsi.getBytes(),
                peerMatchFilter.getBytes());
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());
        inOrder.verify(mockSessionCallback).onMatch(requestorId, peerSsi.getBytes(),
                peerMatchFilter.getBytes());

        // (3) send message to invalid peer ID
        mDut.sendMessage(clientId, sessionId.getValue(), requestorId + 5, ssi.getBytes(),
                messageId, 0);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageSendFail(messageId,
                WifiAwareNative.AWARE_STATUS_ERROR);
        validateInternalSendMessageQueuesCleanedUp(messageId);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    /**
     * Validate that on send message timeout correct callback is dispatched and that a later
     * firmware notification is ignored.
     */
    @Test
    public void testSendMessageTimeout() throws Exception {
        final int clientId = 1005;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final String ssi = "some much longer and more arbitrary data";
        final int subscribeId = 15;
        final int requestorId = 22;
        final byte[] peerMac = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String peerSsi = "some peer ssi data";
        final String peerMatchFilter = "filter binary array represented as string";
        final int messageId = 6948;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (2) subscribe & match
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mDut.onMatchNotification(subscribeId, requestorId, peerMac, peerSsi.getBytes(),
                peerMatchFilter.getBytes());
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());
        inOrder.verify(mockSessionCallback).onMatch(requestorId, peerSsi.getBytes(),
                peerMatchFilter.getBytes());

        // (3) send 2 messages and enqueue successfully
        mDut.sendMessage(clientId, sessionId.getValue(), requestorId, ssi.getBytes(),
                messageId, 0);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                eq(requestorId), eq(peerMac), eq(ssi.getBytes()), eq(messageId));
        short transactionId1 = transactionId.getValue();
        mDut.onMessageSendQueuedSuccessResponse(transactionId1);
        mMockLooper.dispatchAll();

        mDut.sendMessage(clientId, sessionId.getValue(), requestorId, ssi.getBytes(),
                messageId + 1, 0);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                eq(requestorId), eq(peerMac), eq(ssi.getBytes()), eq(messageId + 1));
        short transactionId2 = transactionId.getValue();
        mDut.onMessageSendQueuedSuccessResponse(transactionId2);
        mMockLooper.dispatchAll();

        // (4) message send timeout
        assertTrue(mAlarmManager.dispatch(WifiAwareStateManager.HAL_SEND_MESSAGE_TIMEOUT_TAG));
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageSendFail(messageId,
                WifiAwareNative.AWARE_STATUS_ERROR);
        validateInternalSendMessageQueuesCleanedUp(messageId);

        // (5) firmware response (unlikely - but good to check)
        mDut.onMessageSendSuccessNotification(transactionId1);
        mDut.onMessageSendSuccessNotification(transactionId2);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageSendSuccess(messageId + 1);

        validateInternalSendMessageQueuesCleanedUp(messageId + 1);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    /**
     * Validate that when sending a message with a retry count the message is retried the specified
     * number of times. Scenario ending with success.
     */
    @Test
    public void testSendMessageRetransmitSuccess() throws Exception {
        final int clientId = 1005;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final String ssi = "some much longer and more arbitrary data";
        final int subscribeId = 15;
        final int requestorId = 22;
        final byte[] peerMac = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String peerSsi = "some peer ssi data";
        final String peerMatchFilter = "filter binary array represented as string";
        final int messageId = 6948;
        final int retryCount = 3;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (2) subscribe & match
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mDut.onMatchNotification(subscribeId, requestorId, peerMac, peerSsi.getBytes(),
                peerMatchFilter.getBytes());
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());
        inOrder.verify(mockSessionCallback).onMatch(requestorId, peerSsi.getBytes(),
                peerMatchFilter.getBytes());

        // (3) send message and enqueue successfully
        mDut.sendMessage(clientId, sessionId.getValue(), requestorId, ssi.getBytes(),
                messageId, retryCount);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                eq(requestorId), eq(peerMac), eq(ssi.getBytes()), eq(messageId));
        mDut.onMessageSendQueuedSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();

        // (4) loop and fail until reach retryCount
        for (int i = 0; i < retryCount; ++i) {
            mDut.onMessageSendFailNotification(transactionId.getValue(),
                    WifiAwareNative.AWARE_STATUS_NO_OTA_ACK);
            mMockLooper.dispatchAll();
            inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                    eq(requestorId), eq(peerMac), eq(ssi.getBytes()), eq(messageId));
            mDut.onMessageSendQueuedSuccessResponse(transactionId.getValue());
            mMockLooper.dispatchAll();
        }

        // (5) succeed on last retry
        mDut.onMessageSendSuccessNotification(transactionId.getValue());
        mMockLooper.dispatchAll();

        inOrder.verify(mockSessionCallback).onMessageSendSuccess(messageId);
        validateInternalSendMessageQueuesCleanedUp(messageId);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    /**
     * Validate that when sending a message with a retry count the message is retried the specified
     * number of times. Scenario ending with failure.
     */
    @Test
    public void testSendMessageRetransmitFail() throws Exception {
        final int clientId = 1005;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final String ssi = "some much longer and more arbitrary data";
        final int subscribeId = 15;
        final int requestorId = 22;
        final byte[] peerMac = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String peerSsi = "some peer ssi data";
        final String peerMatchFilter = "filter binary array represented as string";
        final int messageId = 6948;
        final int retryCount = 3;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (2) subscribe & match
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mDut.onMatchNotification(subscribeId, requestorId, peerMac, peerSsi.getBytes(),
                peerMatchFilter.getBytes());
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());
        inOrder.verify(mockSessionCallback).onMatch(requestorId, peerSsi.getBytes(),
                peerMatchFilter.getBytes());

        // (3) send message and enqueue successfully
        mDut.sendMessage(clientId, sessionId.getValue(), requestorId, ssi.getBytes(), messageId,
                retryCount);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                eq(requestorId), eq(peerMac), eq(ssi.getBytes()), eq(messageId));
        mDut.onMessageSendQueuedSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();

        // (4) loop and fail until reach retryCount+1
        for (int i = 0; i < retryCount + 1; ++i) {
            mDut.onMessageSendFailNotification(transactionId.getValue(),
                    WifiAwareNative.AWARE_STATUS_NO_OTA_ACK);
            mMockLooper.dispatchAll();

            if (i != retryCount) {
                inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                        eq(requestorId), eq(peerMac), eq(ssi.getBytes()), eq(messageId));
                mDut.onMessageSendQueuedSuccessResponse(transactionId.getValue());
                mMockLooper.dispatchAll();
            }
        }

        inOrder.verify(mockSessionCallback).onMessageSendFail(messageId,
                WifiAwareNative.AWARE_STATUS_NO_OTA_ACK);
        validateInternalSendMessageQueuesCleanedUp(messageId);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    /**
     * Validate that can send empty message successfully: null, byte[0], ""
     */
    @Test
    public void testSendEmptyMessages() throws Exception {
        final int clientId = 1005;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final String serviceName = "some-service-name";
        final String ssi = "some much longer and more arbitrary data";
        final int subscribeCount = 7;
        final int subscribeId = 15;
        final int requestorId = 22;
        final byte[] peerMac = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String peerSsi = "some peer ssi data";
        final String peerMatchFilter = "filter binary array represented as string";
        final int messageId = 6948;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(ssi.getBytes())
                .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
                .setSubscribeCount(subscribeCount).build();

        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<byte[]> byteArrayCaptor = ArgumentCaptor.forClass(byte[].class);
        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (0) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                eq(configRequest), eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (1) subscribe
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());

        // (2) match
        mDut.onMatchNotification(subscribeId, requestorId, peerMac, peerSsi.getBytes(),
                peerMatchFilter.getBytes());
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMatch(requestorId, peerSsi.getBytes(),
                peerMatchFilter.getBytes());

        // (3) message null Tx successful queuing
        mDut.sendMessage(clientId, sessionId.getValue(), requestorId, null, messageId, 0);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                eq(requestorId), eq(peerMac), isNull(byte[].class), eq(messageId));
        short tid = transactionId.getValue();
        mDut.onMessageSendQueuedSuccessResponse(tid);
        mMockLooper.dispatchAll();

        // (4) final Tx results (on-air results)
        mDut.onMessageSendSuccessNotification(tid);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageSendSuccess(messageId);
        validateInternalSendMessageQueuesCleanedUp(messageId);

        // (5) message byte[0] Tx successful queuing
        mDut.sendMessage(clientId, sessionId.getValue(), requestorId, new byte[0], messageId, 0);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                eq(requestorId), eq(peerMac), eq(new byte[0]), eq(messageId));
        tid = transactionId.getValue();
        mDut.onMessageSendQueuedSuccessResponse(tid);
        mMockLooper.dispatchAll();

        // (6) final Tx results (on-air results)
        mDut.onMessageSendSuccessNotification(tid);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageSendSuccess(messageId);
        validateInternalSendMessageQueuesCleanedUp(messageId);

        // (7) message "" Tx successful queuing
        mDut.sendMessage(clientId, sessionId.getValue(), requestorId, "".getBytes(), messageId, 0);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                eq(requestorId), eq(peerMac), byteArrayCaptor.capture(), eq(messageId));
        collector.checkThat("Empty message contents", "",
                equalTo(new String(byteArrayCaptor.getValue())));
        tid = transactionId.getValue();
        mDut.onMessageSendQueuedSuccessResponse(tid);
        mMockLooper.dispatchAll();

        // (8) final Tx results (on-air results)
        mDut.onMessageSendSuccessNotification(tid);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onMessageSendSuccess(messageId);
        validateInternalSendMessageQueuesCleanedUp(messageId);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    @Test
    public void testSendMessageQueueAllQueueFail() throws Exception {
        WifiAwareNative.Capabilities cap = getCapabilities();
        testSendMessageQueue(SendMessageAnswer.OP_QUEUE_FAIL, cap,
                cap.maxQueuedTransmitMessages + 5);
    }

    @Test
    public void testSendMessageQueueAllTxSuccess() throws Exception {
        WifiAwareNative.Capabilities cap = getCapabilities();
        testSendMessageQueue(SendMessageAnswer.OP_QUEUE_OK_SEND_OK, cap,
                cap.maxQueuedTransmitMessages + 5);
    }

    @Test
    public void testSendMessageQueueAllTxFailRetxOk() throws Exception {
        WifiAwareNative.Capabilities cap = getCapabilities();
        testSendMessageQueue(SendMessageAnswer.OP_QUEUE_OK_SEND_RETX_OK, cap,
                cap.maxQueuedTransmitMessages + 5);
    }

    @Test
    public void testSendMessageQueueAllTxFail() throws Exception {
        WifiAwareNative.Capabilities cap = getCapabilities();
        testSendMessageQueue(SendMessageAnswer.OP_QUEUE_OK_SEND_RETX_FAIL, cap,
                cap.maxQueuedTransmitMessages + 5);
    }

    @Test
    public void testSendMessageQueueRandomize() throws Exception {
        WifiAwareNative.Capabilities cap = getCapabilities();
        testSendMessageQueue(SendMessageAnswer.OP_QUEUE_RANDOMIZE, cap,
                cap.maxQueuedTransmitMessages * 10);
    }

    /**
     * Validate that when sending more messages than can be queued by the firmware (based on
     * capability information) they are queued. Support all possible test success/failure codes.
     * @param behavior: SendMessageAnswer.OP_*.
     */
    private void testSendMessageQueue(int behavior, WifiAwareNative.Capabilities cap,
            int numMessages) throws Exception {
        final int clientId = 1005;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final String ssi = "some much longer and more arbitrary data";
        final int subscribeId = 15;
        final int requestorId = 22;
        final byte[] peerMac = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String peerSsi = "some peer ssi data";
        final String peerMatchFilter = "filter binary array represented as string";
        final int messageId = 6948;
        final int retryCount = 3;
        final int reason = WifiAwareNative.AWARE_STATUS_ERROR;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> msgId = ArgumentCaptor.forClass(Integer.class);

        // (0) initial conditions
        mDut.enableUsage();
        mMockLooper.dispatchAll();
        verify(mMockNative).deInitAware();
        verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), cap);
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        verify(mockCallback).onConnectSuccess(clientId);

        // (2) subscribe & match
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mDut.onMatchNotification(subscribeId, requestorId, peerMac, peerSsi.getBytes(),
                peerMatchFilter.getBytes());
        mMockLooper.dispatchAll();
        verify(mockSessionCallback).onSessionStarted(sessionId.capture());
        verify(mockSessionCallback).onMatch(requestorId, peerSsi.getBytes(),
                peerMatchFilter.getBytes());

        // (3) send large number of messages
        SendMessageAnswer answerObj = new SendMessageAnswer(behavior);
        when(mMockNative.sendMessage(anyShort(), anyInt(), anyInt(), any(byte[].class),
                any(byte[].class), anyInt())).thenAnswer(answerObj);
        for (int i = 0; i < numMessages; ++i) {
            mDut.sendMessage(clientId, sessionId.getValue(), requestorId, ssi.getBytes(),
                    messageId + i, retryCount);
        }
        mMockLooper.dispatchAll();

        int numSends = answerObj.ops[SendMessageAnswer.OP_QUEUE_FAIL]
                + answerObj.ops[SendMessageAnswer.OP_QUEUE_OK_SEND_OK]
                + answerObj.ops[SendMessageAnswer.OP_QUEUE_OK_SEND_RETX_OK] * 2
                + answerObj.ops[SendMessageAnswer.OP_QUEUE_OK_SEND_RETX_FAIL] * (retryCount + 1);
        int numOnSendSuccess = answerObj.ops[SendMessageAnswer.OP_QUEUE_OK_SEND_OK]
                + answerObj.ops[SendMessageAnswer.OP_QUEUE_OK_SEND_RETX_OK];
        int numOnSendFail = answerObj.ops[SendMessageAnswer.OP_QUEUE_OK_SEND_RETX_FAIL];

        Log.v("WifiAwareStateMgrTest",
                "testSendMessageQueue: ops=" + Arrays.toString(answerObj.ops) + ", numSends="
                        + numSends + ", numOnSendSuccess=" + numOnSendSuccess + ", numOnSendFail="
                        + numOnSendFail);

        verify(mMockNative, times(numSends)).sendMessage(anyShort(), eq(subscribeId),
                eq(requestorId), eq(peerMac), eq(ssi.getBytes()), anyInt());
        verify(mockSessionCallback, times(numOnSendSuccess)).onMessageSendSuccess(anyInt());
        verify(mockSessionCallback, times(numOnSendFail)).onMessageSendFail(anyInt(), anyInt());

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative);
    }

    private class SendMessageAnswer extends MockAnswerUtil.AnswerWithArguments {
        public static final int OP_QUEUE_FAIL = 0;
        public static final int OP_QUEUE_OK_SEND_OK = 1;
        public static final int OP_QUEUE_OK_SEND_RETX_OK = 2;
        public static final int OP_QUEUE_OK_SEND_RETX_FAIL = 3;

        /* psuedo operation: randomly pick from the above 4 operations */
        public static final int OP_QUEUE_RANDOMIZE = -1;

        /* the number of operations which can be executed. Doesn't cound RANDOMIZE since it is
         * resolved to one of the 4 types */
        private static final int NUM_OPS = 4;

        public int[] ops = new int[NUM_OPS];

        private int mBehavior = 0;
        private SparseIntArray mPacketBehavior = new SparseIntArray();

        SendMessageAnswer(int behavior) {
            mBehavior = behavior;
        }

        public boolean answer(short transactionId, int pubSubId, int requestorInstanceId,
                byte[] dest, byte[] message, int messageId) throws Exception {
            Log.v("WifiAwareStateMgrTest",
                    "SendMessageAnswer.answer: mBehavior=" + mBehavior + ", transactionId="
                            + transactionId + ", messageId=" + messageId
                            + ", mPacketBehavior[messageId]" + mPacketBehavior.get(messageId, -1));

            int behavior = mBehavior;
            if (behavior == OP_QUEUE_RANDOMIZE) {
                behavior = mRandomNg.nextInt(NUM_OPS);
            }

            boolean packetRetx = mPacketBehavior.get(messageId, -1) != -1;
            if (packetRetx) {
                behavior = mPacketBehavior.get(messageId);
            } else {
                mPacketBehavior.put(messageId, behavior);
            }

            if (behavior == OP_QUEUE_FAIL) {
                ops[OP_QUEUE_FAIL]++;
                mDut.onMessageSendQueuedFailResponse(transactionId,
                        WifiAwareNative.AWARE_STATUS_ERROR);
            } else if (behavior == OP_QUEUE_OK_SEND_OK) {
                ops[OP_QUEUE_OK_SEND_OK]++;
                mDut.onMessageSendQueuedSuccessResponse(transactionId);
                mDut.onMessageSendSuccessNotification(transactionId);
            } else if (behavior == OP_QUEUE_OK_SEND_RETX_OK) {
                mDut.onMessageSendQueuedSuccessResponse(transactionId);
                if (!packetRetx) {
                    mDut.onMessageSendFailNotification(transactionId,
                            WifiAwareNative.AWARE_STATUS_NO_OTA_ACK);
                } else {
                    ops[OP_QUEUE_OK_SEND_RETX_OK]++;
                    mDut.onMessageSendSuccessNotification(transactionId);
                }
            } else if (behavior == OP_QUEUE_OK_SEND_RETX_FAIL) {
                mDut.onMessageSendQueuedSuccessResponse(transactionId);
                if (!packetRetx) {
                    ops[OP_QUEUE_OK_SEND_RETX_FAIL]++;
                }
                mDut.onMessageSendFailNotification(transactionId,
                        WifiAwareNative.AWARE_STATUS_NO_OTA_ACK);
            }
            return true;
        }
    }

    /**
     * Validate that start ranging function fills-in correct MAC addresses for peer IDs and
     * passed along to RTT module.
     */
    @Test
    public void testStartRanging() throws Exception {
        final int clientId = 1005;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final int subscribeId = 15;
        final int requestorId = 22;
        final byte[] peerMac = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String peerSsi = "some peer ssi data";
        final String peerMatchFilter = "filter binary array represented as string";
        final int rangingId = 18423;
        final RttManager.RttParams[] params = new RttManager.RttParams[2];
        params[0] = new RttManager.RttParams();
        params[0].bssid = Integer.toString(requestorId);
        params[1] = new RttManager.RttParams();
        params[1].bssid = Integer.toString(requestorId + 5);

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<WifiAwareClientState> clientCaptor =
                ArgumentCaptor.forClass(WifiAwareClientState.class);
        ArgumentCaptor<RttManager.RttParams[]> rttParamsCaptor =
                ArgumentCaptor.forClass(RttManager.RttParams[].class);

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mMockNative,
                mMockAwareRttStateManager);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (2) subscribe & match
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mDut.onMatchNotification(subscribeId, requestorId, peerMac, peerSsi.getBytes(),
                peerMatchFilter.getBytes());
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());
        inOrder.verify(mockSessionCallback).onMatch(requestorId, peerSsi.getBytes(),
                peerMatchFilter.getBytes());

        // (3) start ranging: pass along a valid peer ID and an invalid one
        mDut.startRanging(clientId, sessionId.getValue(), params, rangingId);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockAwareRttStateManager).startRanging(eq(rangingId),
                clientCaptor.capture(), rttParamsCaptor.capture());
        collector.checkThat("RttParams[0].bssid", "06:07:08:09:0A:0B",
                equalTo(rttParamsCaptor.getValue()[0].bssid));
        collector.checkThat("RttParams[1].bssid", "", equalTo(rttParamsCaptor.getValue()[1].bssid));

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mMockNative,
                mMockAwareRttStateManager);
    }

    /**
     * Test sequence of configuration: (1) config1, (2) config2 - incompatible,
     * (3) config3 - compatible with config1 (requiring upgrade), (4) disconnect
     * config3 (should get a downgrade), (5) disconnect config1 (should get a
     * disable).
     */
    @Test
    public void testConfigs() throws Exception {
        final int clientId1 = 9999;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final int clusterLow1 = 5;
        final int clusterHigh1 = 100;
        final int masterPref1 = 111;
        final int clientId2 = 1001;
        final boolean support5g2 = true;
        final int clusterLow2 = 7;
        final int clusterHigh2 = 155;
        final int masterPref2 = 0;

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<ConfigRequest> crCapture = ArgumentCaptor.forClass(ConfigRequest.class);

        ConfigRequest configRequest1 = new ConfigRequest.Builder().setClusterLow(clusterLow1)
                .setClusterHigh(clusterHigh1).setMasterPreference(masterPref1).build();

        ConfigRequest configRequest2 = new ConfigRequest.Builder().setSupport5gBand(support5g2)
                .setClusterLow(clusterLow2).setClusterHigh(clusterHigh2)
                .setMasterPreference(masterPref2).build();

        IWifiAwareEventCallback mockCallback1 = mock(IWifiAwareEventCallback.class);
        IWifiAwareEventCallback mockCallback2 = mock(IWifiAwareEventCallback.class);
        IWifiAwareEventCallback mockCallback3 = mock(IWifiAwareEventCallback.class);

        InOrder inOrder = inOrder(mMockNative, mockCallback1, mockCallback2, mockCallback3);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) config1 (valid)
        mDut.connect(clientId1, uid, pid, callingPackage, mockCallback1, configRequest1, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                crCapture.capture(), eq(true));
        collector.checkThat("merge: stage 1", crCapture.getValue(), equalTo(configRequest1));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback1).onConnectSuccess(clientId1);

        // (2) config2 (incompatible with config1)
        mDut.connect(clientId2, uid, pid, callingPackage, mockCallback2, configRequest2, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback2)
                .onConnectFail(WifiAwareNative.AWARE_STATUS_ERROR);
        validateInternalClientInfoCleanedUp(clientId2);

        // (5) disconnect config1: disable
        mDut.disconnect(clientId1);
        mMockLooper.dispatchAll();
        validateInternalClientInfoCleanedUp(clientId1);
        inOrder.verify(mMockNative).disable((short) 0);

        verifyNoMoreInteractions(mMockNative, mockCallback1, mockCallback2, mockCallback3);
    }

    /**
     * Summary: disconnect a client while there are pending transactions.
     */
    @Test
    public void testDisconnectWithPendingTransactions() throws Exception {
        final int clientId = 125;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final int clusterLow = 5;
        final int clusterHigh = 100;
        final int masterPref = 111;
        final String serviceName = "some-service-name";
        final String ssi = "some much longer and more arbitrary data";
        final int publishCount = 7;
        final int reason = WifiAwareDiscoverySessionCallback.TERMINATE_REASON_DONE;
        final int publishId = 22;

        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterLow(clusterLow)
                .setClusterHigh(clusterHigh).setMasterPreference(masterPref).build();

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(
                serviceName).setServiceSpecificInfo(ssi.getBytes()).setPublishType(
                PublishConfig.PUBLISH_TYPE_UNSOLICITED).setPublishCount(publishCount).build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockSessionCallback);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (2) publish (no response yet)
        mDut.publish(clientId, publishConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));

        // (3) disconnect (but doesn't get executed until get a RESPONSE to the
        // previous publish)
        mDut.disconnect(clientId);
        mMockLooper.dispatchAll();

        // (4) get successful response to the publish
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), true, publishId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(anyInt());
        inOrder.verify(mMockNative).stopPublish((short) 0, publishId);
        inOrder.verify(mMockNative).disable((short) 0);

        validateInternalClientInfoCleanedUp(clientId);

        // (5) trying to publish on the same client: NOP
        mDut.publish(clientId, publishConfig, mockSessionCallback);
        mMockLooper.dispatchAll();

        // (6) got some callback on original publishId - should be ignored
        mDut.onSessionTerminatedNotification(publishId, reason, true);
        mMockLooper.dispatchAll();

        verifyNoMoreInteractions(mMockNative, mockCallback, mockSessionCallback);
    }

    /**
     * Validate that an unknown transaction (i.e. a callback from HAL with an
     * unknown type) is simply ignored - but also cleans up its state.
     */
    @Test
    public void testUnknownTransactionType() throws Exception {
        final int clientId = 129;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final int clusterLow = 15;
        final int clusterHigh = 192;
        final int masterPref = 234;
        final String serviceName = "some-service-name";
        final String ssi = "some much longer and more arbitrary data";
        final int publishCount = 15;

        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterLow(clusterLow)
                .setClusterHigh(clusterHigh).setMasterPreference(masterPref).build();

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(
                serviceName).setServiceSpecificInfo(ssi.getBytes()).setPublishType(
                PublishConfig.PUBLISH_TYPE_UNSOLICITED).setPublishCount(publishCount).build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockPublishSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockPublishSessionCallback);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (2) publish - no response
        mDut.publish(clientId, publishConfig, mockPublishSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));

        verifyNoMoreInteractions(mMockNative, mockCallback, mockPublishSessionCallback);
    }

    /**
     * Validate that a NoOp transaction (i.e. a callback from HAL which doesn't
     * require any action except clearing up state) actually cleans up its state
     * (and does nothing else).
     */
    @Test
    public void testNoOpTransaction() throws Exception {
        final int clientId = 1294;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";

        ConfigRequest configRequest = new ConfigRequest.Builder().build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockSessionCallback);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect (no response)
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));

        verifyNoMoreInteractions(mMockNative, mockCallback, mockSessionCallback);
    }

    /**
     * Validate that getting callbacks from HAL with unknown (expired)
     * transaction ID or invalid publish/subscribe ID session doesn't have any
     * impact.
     */
    @Test
    public void testInvalidCallbackIdParameters() throws Exception {
        final int pubSubId = 1235;
        final int clientId = 132;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";

        ConfigRequest configRequest = new ConfigRequest.Builder().build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect and succeed
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        short transactionIdConfig = transactionId.getValue();
        mDut.onConfigSuccessResponse(transactionIdConfig);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (2) use the same transaction ID to send a bunch of other responses
        mDut.onConfigSuccessResponse(transactionIdConfig);
        mDut.onConfigFailedResponse(transactionIdConfig, -1);
        mDut.onSessionConfigFailResponse(transactionIdConfig, true, -1);
        mDut.onMessageSendQueuedSuccessResponse(transactionIdConfig);
        mDut.onMessageSendQueuedFailResponse(transactionIdConfig, -1);
        mDut.onSessionConfigFailResponse(transactionIdConfig, false, -1);
        mDut.onMatchNotification(-1, -1, new byte[0], new byte[0], new byte[0]);
        mDut.onSessionTerminatedNotification(-1, -1, true);
        mDut.onSessionTerminatedNotification(-1, -1, false);
        mDut.onMessageReceivedNotification(-1, -1, new byte[0], new byte[0]);
        mDut.onSessionConfigSuccessResponse(transactionIdConfig, true, pubSubId);
        mDut.onSessionConfigSuccessResponse(transactionIdConfig, false, pubSubId);
        mMockLooper.dispatchAll();

        verifyNoMoreInteractions(mMockNative, mockCallback);
    }

    /**
     * Validate that trying to update-subscribe on a publish session fails.
     */
    @Test
    public void testSubscribeOnPublishSessionType() throws Exception {
        final int clientId = 188;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final int publishId = 25;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        PublishConfig publishConfig = new PublishConfig.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockSessionCallback);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest),
                eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (2) publish
        mDut.publish(clientId, publishConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), true, publishId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());

        // (3) update-subscribe -> failure
        mDut.updateSubscribe(clientId, sessionId.getValue(), subscribeConfig);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback)
                .onSessionConfigFail(WifiAwareNative.AWARE_STATUS_ERROR);

        verifyNoMoreInteractions(mMockNative, mockCallback, mockSessionCallback);
    }

    /**
     * Validate that trying to (re)subscribe on a publish session or (re)publish
     * on a subscribe session fails.
     */
    @Test
    public void testPublishOnSubscribeSessionType() throws Exception {
        final int clientId = 188;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        final int subscribeId = 25;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        PublishConfig publishConfig = new PublishConfig.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockSessionCallback);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                eq(configRequest), eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        // (2) subscribe
        mDut.subscribe(clientId, subscribeConfig, mockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), false, subscribeId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());

        // (3) update-publish -> error
        mDut.updatePublish(clientId, sessionId.getValue(), publishConfig);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback)
                .onSessionConfigFail(WifiAwareNative.AWARE_STATUS_ERROR);

        verifyNoMoreInteractions(mMockNative, mockCallback, mockSessionCallback);
    }

    /**
     * Validate that the session ID increments monotonically
     */
    @Test
    public void testSessionIdIncrement() throws Exception {
        final int clientId = 188;
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.google.somePackage";
        int loopCount = 100;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();
        PublishConfig publishConfig = new PublishConfig.Builder().build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        IWifiAwareEventCallback mockCallback = mock(IWifiAwareEventCallback.class);
        IWifiAwareDiscoverySessionCallback mockSessionCallback = mock(
                IWifiAwareDiscoverySessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockSessionCallback);

        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitAware();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), getCapabilities());
        mMockLooper.dispatchAll();

        // (1) connect
        mDut.connect(clientId, uid, pid, callingPackage, mockCallback, configRequest, false);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                eq(configRequest), eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess(clientId);

        int prevId = 0;
        for (int i = 0; i < loopCount; ++i) {
            // (2) publish
            mDut.publish(clientId, publishConfig, mockSessionCallback);
            mMockLooper.dispatchAll();
            inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));

            // (3) publish-success
            mDut.onSessionConfigSuccessResponse(transactionId.getValue(), true, i + 1);
            mMockLooper.dispatchAll();
            inOrder.verify(mockSessionCallback).onSessionStarted(sessionId.capture());

            if (i != 0) {
                assertTrue("Session ID incrementing", sessionId.getValue() > prevId);
            }
            prevId = sessionId.getValue();
        }
    }

    /*
     * Tests of internal state of WifiAwareStateManager: very limited (not usually
     * a good idea). However, these test that the internal state is cleaned-up
     * appropriately. Alternatively would cause issues with memory leaks or
     * information leak between sessions.
     */

    /**
     * Utility routine used to validate that the internal state is cleaned-up
     * after a client is disconnected. To be used in every test which terminates
     * a client.
     *
     * @param clientId The ID of the client which should be deleted.
     */
    private void validateInternalClientInfoCleanedUp(int clientId) throws Exception {
        WifiAwareClientState client = getInternalClientState(mDut, clientId);
        collector.checkThat("Client record not cleared up for clientId=" + clientId, client,
                nullValue());
    }

    /**
     * Utility routine used to validate that the internal state is cleaned-up
     * (deleted) after a session is terminated through API (not callback!). To
     * be used in every test which terminates a session.
     *
     * @param clientId The ID of the client containing the session.
     * @param sessionId The ID of the terminated session.
     */
    private void validateInternalSessionInfoCleanedUp(int clientId, int sessionId)
            throws Exception {
        WifiAwareClientState client = getInternalClientState(mDut, clientId);
        collector.checkThat("Client record exists clientId=" + clientId, client, notNullValue());
        WifiAwareDiscoverySessionState session = getInternalSessionState(client, sessionId);
        collector.checkThat("Client record not cleaned-up for sessionId=" + sessionId, session,
                nullValue());
    }

    /**
     * Utility routine used to validate that the internal state is cleaned-up
     * (deleted) correctly. Checks that a specific client has no sessions
     * attached to it.
     *
     * @param clientId The ID of the client which we want to check.
     */
    private void validateInternalNoSessions(int clientId) throws Exception {
        WifiAwareClientState client = getInternalClientState(mDut, clientId);
        collector.checkThat("Client record exists clientId=" + clientId, client, notNullValue());

        Field field = WifiAwareClientState.class.getDeclaredField("mSessions");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        SparseArray<WifiAwareDiscoverySessionState> sessions =
                (SparseArray<WifiAwareDiscoverySessionState>) field.get(client);

        collector.checkThat("No sessions exist for clientId=" + clientId, sessions.size(),
                equalTo(0));
    }

    /**
     * Validates that the broadcast sent on Aware status change is correct.
     *
     * @param expectedEnabled The expected change status - i.e. are we expected
     *            to announce that Aware is enabled (true) or disabled (false).
     */
    private void validateCorrectAwareStatusChangeBroadcast(InOrder inOrder,
            boolean expectedEnabled) {
        ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);

        inOrder.verify(mMockContext).sendBroadcastAsUser(intent.capture(), eq(UserHandle.ALL));

        collector.checkThat("intent action", intent.getValue().getAction(),
                equalTo(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED));
    }

    /*
     * Utilities
     */

    private static WifiAwareStateManager installNewAwareStateManager()
            throws Exception {
        Constructor<WifiAwareStateManager> ctr =
                WifiAwareStateManager.class.getDeclaredConstructor();
        ctr.setAccessible(true);
        WifiAwareStateManager awareStateManager = ctr.newInstance();

        Field field = WifiAwareStateManager.class.getDeclaredField("sAwareStateManagerSingleton");
        field.setAccessible(true);
        field.set(null, awareStateManager);

        return WifiAwareStateManager.getInstance();
    }

    private static void installMocksInStateManager(WifiAwareStateManager awareStateManager,
            WifiAwareRttStateManager mockRtt, WifiAwareDataPathStateManager mockDpMgr)
            throws Exception {
        Field field = WifiAwareStateManager.class.getDeclaredField("mRtt");
        field.setAccessible(true);
        field.set(awareStateManager, mockRtt);

        field = WifiAwareStateManager.class.getDeclaredField("mDataPathMgr");
        field.setAccessible(true);
        field.set(awareStateManager, mockDpMgr);
    }

    private static void installMockWifiAwareNative(WifiAwareNative obj) throws Exception {
        Field field = WifiAwareNative.class.getDeclaredField("sWifiAwareNativeSingleton");
        field.setAccessible(true);
        field.set(null, obj);
    }

    private static WifiAwareClientState getInternalClientState(WifiAwareStateManager dut,
            int clientId) throws Exception {
        Field field = WifiAwareStateManager.class.getDeclaredField("mClients");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        SparseArray<WifiAwareClientState> clients = (SparseArray<WifiAwareClientState>) field.get(
                dut);

        return clients.get(clientId);
    }

    private static WifiAwareDiscoverySessionState getInternalSessionState(
            WifiAwareClientState client, int sessionId) throws Exception {
        Field field = WifiAwareClientState.class.getDeclaredField("mSessions");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        SparseArray<WifiAwareDiscoverySessionState> sessions =
                (SparseArray<WifiAwareDiscoverySessionState>) field.get(client);

        return sessions.get(sessionId);
    }

    private void validateInternalSendMessageQueuesCleanedUp(int messageId) throws Exception {
        Field field = WifiAwareStateManager.class.getDeclaredField("mSm");
        field.setAccessible(true);
        WifiAwareStateManager.WifiAwareStateMachine sm =
                (WifiAwareStateManager.WifiAwareStateMachine) field.get(mDut);

        field = WifiAwareStateManager.WifiAwareStateMachine.class.getDeclaredField(
                "mHostQueuedSendMessages");
        field.setAccessible(true);
        SparseArray<Message> hostQueuedSendMessages = (SparseArray<Message>) field.get(sm);

        field = WifiAwareStateManager.WifiAwareStateMachine.class.getDeclaredField(
                "mFwQueuedSendMessages");
        field.setAccessible(true);
        Map<Short, Message> fwQueuedSendMessages = (Map<Short, Message>) field.get(sm);

        for (int i = 0; i < hostQueuedSendMessages.size(); ++i) {
            Message msg = hostQueuedSendMessages.valueAt(i);
            if (msg.getData().getInt("message_id") == messageId) {
                collector.checkThat(
                        "Message not cleared-up from host queue. Message ID=" + messageId, msg,
                        nullValue());
            }
        }

        for (Message msg: fwQueuedSendMessages.values()) {
            if (msg.getData().getInt("message_id") == messageId) {
                collector.checkThat(
                        "Message not cleared-up from firmware queue. Message ID=" + messageId, msg,
                        nullValue());
            }
        }
    }

    private static WifiAwareNative.Capabilities getCapabilities() {
        WifiAwareNative.Capabilities cap = new WifiAwareNative.Capabilities();
        cap.maxConcurrentAwareClusters = 1;
        cap.maxPublishes = 2;
        cap.maxSubscribes = 2;
        cap.maxServiceNameLen = 255;
        cap.maxMatchFilterLen = 255;
        cap.maxTotalMatchFilterLen = 255;
        cap.maxServiceSpecificInfoLen = 255;
        cap.maxVsaDataLen = 255;
        cap.maxMeshDataLen = 255;
        cap.maxNdiInterfaces = 1;
        cap.maxNdpSessions = 1;
        cap.maxAppInfoLen = 255;
        cap.maxQueuedTransmitMessages = 6;
        return cap;
    }
}

