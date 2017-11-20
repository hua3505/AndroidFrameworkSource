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

import static com.android.server.wifi.WifiConfigurationTestUtil.generateWifiConfig;
import static com.android.server.wifi.WifiStateMachine.WIFI_WORK_SOURCE;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.test.MockAnswerUtil.AnswerWithArguments;
import android.app.test.TestAlarmManager;
import android.content.Context;
import android.content.res.Resources;
import android.net.wifi.ScanResult;
import android.net.wifi.ScanResult.InformationElement;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiScanner.PnoScanListener;
import android.net.wifi.WifiScanner.PnoSettings;
import android.net.wifi.WifiScanner.ScanData;
import android.net.wifi.WifiScanner.ScanListener;
import android.net.wifi.WifiScanner.ScanSettings;
import android.net.wifi.WifiSsid;
import android.os.SystemClock;
import android.os.WorkSource;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Unit tests for {@link com.android.server.wifi.WifiConnectivityManager}.
 */
@SmallTest
public class WifiConnectivityManagerTest {

    /**
     * Called before each test
     */
    @Before
    public void setUp() throws Exception {
        mWifiInjector = mockWifiInjector();
        mResource = mockResource();
        mAlarmManager = new TestAlarmManager();
        mContext = mockContext();
        mWifiStateMachine = mockWifiStateMachine();
        mWifiConfigManager = mockWifiConfigManager();
        mWifiInfo = getWifiInfo();
        mScanData = mockScanData();
        mWifiScanner = mockWifiScanner();
        mWifiNS = mockWifiNetworkSelector();
        mWifiConnectivityManager = createConnectivityManager();
        mWifiConnectivityManager.setWifiEnabled(true);
        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime());
    }

    /**
     * Called after each test
     */
    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    private Resources mResource;
    private Context mContext;
    private TestAlarmManager mAlarmManager;
    private TestLooper mLooper = new TestLooper();
    private WifiConnectivityManager mWifiConnectivityManager;
    private WifiNetworkSelector mWifiNS;
    private WifiStateMachine mWifiStateMachine;
    private WifiScanner mWifiScanner;
    private ScanData mScanData;
    private WifiConfigManager mWifiConfigManager;
    private WifiInfo mWifiInfo;
    private Clock mClock = mock(Clock.class);
    private WifiLastResortWatchdog mWifiLastResortWatchdog;
    private WifiMetrics mWifiMetrics;
    private WifiInjector mWifiInjector;
    private MockResources mResources;

    private static final int CANDIDATE_NETWORK_ID = 0;
    private static final String CANDIDATE_SSID = "\"AnSsid\"";
    private static final String CANDIDATE_BSSID = "6c:f3:7f:ae:8c:f3";
    private static final String TAG = "WifiConnectivityManager Unit Test";
    private static final long CURRENT_SYSTEM_TIME_MS = 1000;

    Resources mockResource() {
        Resources resource = mock(Resources.class);

        when(resource.getInteger(R.integer.config_wifi_framework_SECURITY_AWARD)).thenReturn(80);
        when(resource.getInteger(R.integer.config_wifi_framework_SAME_BSSID_AWARD)).thenReturn(24);
        when(resource.getBoolean(
                R.bool.config_wifi_framework_enable_associated_network_selection)).thenReturn(true);
        when(resource.getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_24GHz))
                .thenReturn(-60);
        when(resource.getInteger(
                R.integer.config_wifi_framework_current_network_boost))
                .thenReturn(16);
        return resource;
    }

    Context mockContext() {
        Context context = mock(Context.class);

        when(context.getResources()).thenReturn(mResource);
        when(context.getSystemService(Context.ALARM_SERVICE)).thenReturn(
                mAlarmManager.getAlarmManager());

        return context;
    }

    ScanData mockScanData() {
        ScanData scanData = mock(ScanData.class);

        when(scanData.isAllChannelsScanned()).thenReturn(true);

        return scanData;
    }

    WifiScanner mockWifiScanner() {
        WifiScanner scanner = mock(WifiScanner.class);
        ArgumentCaptor<ScanListener> allSingleScanListenerCaptor =
                ArgumentCaptor.forClass(ScanListener.class);

        doNothing().when(scanner).registerScanListener(allSingleScanListenerCaptor.capture());

        ScanData[] scanDatas = new ScanData[1];
        scanDatas[0] = mScanData;

        // do a synchronous answer for the ScanListener callbacks
        doAnswer(new AnswerWithArguments() {
                public void answer(ScanSettings settings, ScanListener listener,
                        WorkSource workSource) throws Exception {
                    listener.onResults(scanDatas);
                }}).when(scanner).startBackgroundScan(anyObject(), anyObject(), anyObject());

        doAnswer(new AnswerWithArguments() {
                public void answer(ScanSettings settings, ScanListener listener,
                        WorkSource workSource) throws Exception {
                    listener.onResults(scanDatas);
                    allSingleScanListenerCaptor.getValue().onResults(scanDatas);
                }}).when(scanner).startScan(anyObject(), anyObject(), anyObject());

        // This unfortunately needs to be a somewhat valid scan result, otherwise
        // |ScanDetailUtil.toScanDetail| raises exceptions.
        final ScanResult[] scanResults = new ScanResult[1];
        scanResults[0] = new ScanResult(WifiSsid.createFromAsciiEncoded(CANDIDATE_SSID),
                CANDIDATE_SSID, CANDIDATE_BSSID, 1245, 0, "some caps",
                -78, 2450, 1025, 22, 33, 20, 0, 0, true);
        scanResults[0].informationElements = new InformationElement[1];
        scanResults[0].informationElements[0] = new InformationElement();
        scanResults[0].informationElements[0].id = InformationElement.EID_SSID;
        scanResults[0].informationElements[0].bytes =
                CANDIDATE_SSID.getBytes(StandardCharsets.UTF_8);

        doAnswer(new AnswerWithArguments() {
            public void answer(ScanSettings settings, PnoSettings pnoSettings,
                    PnoScanListener listener) throws Exception {
                listener.onPnoNetworkFound(scanResults);
            }}).when(scanner).startDisconnectedPnoScan(anyObject(), anyObject(), anyObject());

        doAnswer(new AnswerWithArguments() {
            public void answer(ScanSettings settings, PnoSettings pnoSettings,
                    PnoScanListener listener) throws Exception {
                listener.onPnoNetworkFound(scanResults);
            }}).when(scanner).startConnectedPnoScan(anyObject(), anyObject(), anyObject());

        return scanner;
    }

    WifiStateMachine mockWifiStateMachine() {
        WifiStateMachine stateMachine = mock(WifiStateMachine.class);

        when(stateMachine.isLinkDebouncing()).thenReturn(false);
        when(stateMachine.isConnected()).thenReturn(false);
        when(stateMachine.isDisconnected()).thenReturn(true);
        when(stateMachine.isSupplicantTransientState()).thenReturn(false);

        return stateMachine;
    }

    WifiNetworkSelector mockWifiNetworkSelector() {
        WifiNetworkSelector ns = mock(WifiNetworkSelector.class);

        WifiConfiguration candidate = generateWifiConfig(
                0, CANDIDATE_NETWORK_ID, CANDIDATE_SSID, false, true, null, null);
        candidate.BSSID = CANDIDATE_BSSID;
        ScanResult candidateScanResult = new ScanResult();
        candidateScanResult.SSID = CANDIDATE_SSID;
        candidateScanResult.BSSID = CANDIDATE_BSSID;
        candidate.getNetworkSelectionStatus().setCandidate(candidateScanResult);

        when(ns.selectNetwork(anyObject(), anyObject(), anyBoolean(), anyBoolean(),
              anyBoolean())).thenReturn(candidate);
        return ns;
    }

    WifiInfo getWifiInfo() {
        WifiInfo wifiInfo = new WifiInfo();

        wifiInfo.setNetworkId(WifiConfiguration.INVALID_NETWORK_ID);
        wifiInfo.setBSSID(null);
        wifiInfo.setSupplicantState(SupplicantState.DISCONNECTED);

        return wifiInfo;
    }

    WifiConfigManager mockWifiConfigManager() {
        WifiConfigManager wifiConfigManager = mock(WifiConfigManager.class);

        when(wifiConfigManager.getConfiguredNetwork(anyInt())).thenReturn(null);

        // Pass dummy pno network list, otherwise Pno scan requests will not be triggered.
        PnoSettings.PnoNetwork pnoNetwork = new PnoSettings.PnoNetwork(CANDIDATE_SSID);
        ArrayList<PnoSettings.PnoNetwork> pnoNetworkList = new ArrayList<>();
        pnoNetworkList.add(pnoNetwork);
        when(wifiConfigManager.retrievePnoNetworkList()).thenReturn(pnoNetworkList);
        when(wifiConfigManager.retrievePnoNetworkList()).thenReturn(pnoNetworkList);

        return wifiConfigManager;
    }

    WifiInjector mockWifiInjector() {
        WifiInjector wifiInjector = mock(WifiInjector.class);
        mWifiLastResortWatchdog = mock(WifiLastResortWatchdog.class);
        mWifiMetrics = mock(WifiMetrics.class);
        when(wifiInjector.getWifiLastResortWatchdog()).thenReturn(mWifiLastResortWatchdog);
        when(wifiInjector.getWifiMetrics()).thenReturn(mWifiMetrics);
        when(wifiInjector.getClock()).thenReturn(mClock);
        return wifiInjector;
    }

    WifiConnectivityManager createConnectivityManager() {
        return new WifiConnectivityManager(mContext, mWifiStateMachine, mWifiScanner,
                mWifiConfigManager, mWifiInfo, mWifiNS, mWifiInjector, mLooper.getLooper(), true);
    }

    /**
     *  Wifi enters disconnected state while screen is on.
     *
     * Expected behavior: WifiConnectivityManager calls
     * WifiStateMachine.startConnectToNetwork() with the
     * expected candidate network ID and BSSID.
     */
    @Test
    public void enterWifiDisconnectedStateWhenScreenOn() {
        // Set screen to on
        mWifiConnectivityManager.handleScreenStateChanged(true);

        // Set WiFi to disconnected state
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_DISCONNECTED);

        verify(mWifiStateMachine).startConnectToNetwork(
                CANDIDATE_NETWORK_ID, CANDIDATE_BSSID);
    }

    /**
     *  Wifi enters connected state while screen is on.
     *
     * Expected behavior: WifiConnectivityManager calls
     * WifiStateMachine.startConnectToNetwork() with the
     * expected candidate network ID and BSSID.
     */
    @Test
    public void enterWifiConnectedStateWhenScreenOn() {
        // Set screen to on
        mWifiConnectivityManager.handleScreenStateChanged(true);

        // Set WiFi to connected state
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_CONNECTED);

        verify(mWifiStateMachine).startConnectToNetwork(
                CANDIDATE_NETWORK_ID, CANDIDATE_BSSID);
    }

    /**
     *  Screen turned on while WiFi in disconnected state.
     *
     * Expected behavior: WifiConnectivityManager calls
     * WifiStateMachine.startConnectToNetwork() with the
     * expected candidate network ID and BSSID.
     */
    @Test
    public void turnScreenOnWhenWifiInDisconnectedState() {
        // Set WiFi to disconnected state
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_DISCONNECTED);

        // Set screen to on
        mWifiConnectivityManager.handleScreenStateChanged(true);

        verify(mWifiStateMachine, atLeastOnce()).startConnectToNetwork(
                CANDIDATE_NETWORK_ID, CANDIDATE_BSSID);
    }

    /**
     *  Screen turned on while WiFi in connected state.
     *
     * Expected behavior: WifiConnectivityManager calls
     * WifiStateMachine.startConnectToNetwork() with the
     * expected candidate network ID and BSSID.
     */
    @Test
    public void turnScreenOnWhenWifiInConnectedState() {
        // Set WiFi to connected state
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_CONNECTED);

        // Set screen to on
        mWifiConnectivityManager.handleScreenStateChanged(true);

        verify(mWifiStateMachine, atLeastOnce()).startConnectToNetwork(
                CANDIDATE_NETWORK_ID, CANDIDATE_BSSID);
    }

    /**
     *  Screen turned on while WiFi in connected state but
     *  auto roaming is disabled.
     *
     * Expected behavior: WifiConnectivityManager doesn't invoke
     * WifiStateMachine.startConnectToNetwork() because roaming
     * is turned off.
     */
    @Test
    public void turnScreenOnWhenWifiInConnectedStateRoamingDisabled() {
        // Turn off auto roaming
        when(mResource.getBoolean(
                R.bool.config_wifi_framework_enable_associated_network_selection))
                .thenReturn(false);
        mWifiConnectivityManager = createConnectivityManager();

        // Set WiFi to connected state
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_CONNECTED);

        // Set screen to on
        mWifiConnectivityManager.handleScreenStateChanged(true);

        verify(mWifiStateMachine, times(0)).startConnectToNetwork(
                CANDIDATE_NETWORK_ID, CANDIDATE_BSSID);
    }

    /**
     * Multiple back to back connection attempts within the rate interval should be rate limited.
     *
     * Expected behavior: WifiConnectivityManager calls WifiStateMachine.startConnectToNetwork()
     * with the expected candidate network ID and BSSID for only the expected number of times within
     * the given interval.
     */
    @Test
    public void connectionAttemptRateLimitedWhenScreenOff() {
        int maxAttemptRate = WifiConnectivityManager.MAX_CONNECTION_ATTEMPTS_RATE;
        int timeInterval = WifiConnectivityManager.MAX_CONNECTION_ATTEMPTS_TIME_INTERVAL_MS;
        int numAttempts = 0;
        int connectionAttemptIntervals = timeInterval / maxAttemptRate;

        mWifiConnectivityManager.handleScreenStateChanged(false);

        // First attempt the max rate number of connections within the rate interval.
        long currentTimeStamp = 0;
        for (int attempt = 0; attempt < maxAttemptRate; attempt++) {
            currentTimeStamp += connectionAttemptIntervals;
            when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTimeStamp);
            // Set WiFi to disconnected state to trigger PNO scan
            mWifiConnectivityManager.handleConnectionStateChanged(
                    WifiConnectivityManager.WIFI_STATE_DISCONNECTED);
            numAttempts++;
        }
        // Now trigger another connection attempt before the rate interval, this should be
        // skipped because we've crossed rate limit.
        when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTimeStamp);
        // Set WiFi to disconnected state to trigger PNO scan
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_DISCONNECTED);

        // Verify that we attempt to connect upto the rate.
        verify(mWifiStateMachine, times(numAttempts)).startConnectToNetwork(
                CANDIDATE_NETWORK_ID, CANDIDATE_BSSID);
    }

    /**
     * Multiple back to back connection attempts outside the rate interval should not be rate
     * limited.
     *
     * Expected behavior: WifiConnectivityManager calls WifiStateMachine.startConnectToNetwork()
     * with the expected candidate network ID and BSSID for only the expected number of times within
     * the given interval.
     */
    @Test
    public void connectionAttemptNotRateLimitedWhenScreenOff() {
        int maxAttemptRate = WifiConnectivityManager.MAX_CONNECTION_ATTEMPTS_RATE;
        int timeInterval = WifiConnectivityManager.MAX_CONNECTION_ATTEMPTS_TIME_INTERVAL_MS;
        int numAttempts = 0;
        int connectionAttemptIntervals = timeInterval / maxAttemptRate;

        mWifiConnectivityManager.handleScreenStateChanged(false);

        // First attempt the max rate number of connections within the rate interval.
        long currentTimeStamp = 0;
        for (int attempt = 0; attempt < maxAttemptRate; attempt++) {
            currentTimeStamp += connectionAttemptIntervals;
            when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTimeStamp);
            // Set WiFi to disconnected state to trigger PNO scan
            mWifiConnectivityManager.handleConnectionStateChanged(
                    WifiConnectivityManager.WIFI_STATE_DISCONNECTED);
            numAttempts++;
        }
        // Now trigger another connection attempt after the rate interval, this should not be
        // skipped because we should've evicted the older attempt.
        when(mClock.getElapsedSinceBootMillis()).thenReturn(
                currentTimeStamp + connectionAttemptIntervals * 2);
        // Set WiFi to disconnected state to trigger PNO scan
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_DISCONNECTED);
        numAttempts++;

        // Verify that all the connection attempts went through
        verify(mWifiStateMachine, times(numAttempts)).startConnectToNetwork(
                CANDIDATE_NETWORK_ID, CANDIDATE_BSSID);
    }

    /**
     * Multiple back to back connection attempts after a user selection should not be rate limited.
     *
     * Expected behavior: WifiConnectivityManager calls WifiStateMachine.startConnectToNetwork()
     * with the expected candidate network ID and BSSID for only the expected number of times within
     * the given interval.
     */
    @Test
    public void connectionAttemptNotRateLimitedWhenScreenOffAfterUserSelection() {
        int maxAttemptRate = WifiConnectivityManager.MAX_CONNECTION_ATTEMPTS_RATE;
        int timeInterval = WifiConnectivityManager.MAX_CONNECTION_ATTEMPTS_TIME_INTERVAL_MS;
        int numAttempts = 0;
        int connectionAttemptIntervals = timeInterval / maxAttemptRate;

        mWifiConnectivityManager.handleScreenStateChanged(false);

        // First attempt the max rate number of connections within the rate interval.
        long currentTimeStamp = 0;
        for (int attempt = 0; attempt < maxAttemptRate; attempt++) {
            currentTimeStamp += connectionAttemptIntervals;
            when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTimeStamp);
            // Set WiFi to disconnected state to trigger PNO scan
            mWifiConnectivityManager.handleConnectionStateChanged(
                    WifiConnectivityManager.WIFI_STATE_DISCONNECTED);
            numAttempts++;
        }

        mWifiConnectivityManager.setUserConnectChoice(CANDIDATE_NETWORK_ID);

        for (int attempt = 0; attempt < maxAttemptRate; attempt++) {
            currentTimeStamp += connectionAttemptIntervals;
            when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTimeStamp);
            // Set WiFi to disconnected state to trigger PNO scan
            mWifiConnectivityManager.handleConnectionStateChanged(
                    WifiConnectivityManager.WIFI_STATE_DISCONNECTED);
            numAttempts++;
        }

        // Verify that all the connection attempts went through
        verify(mWifiStateMachine, times(numAttempts)).startConnectToNetwork(
                CANDIDATE_NETWORK_ID, CANDIDATE_BSSID);
    }

    /**
     *  PNO retry for low RSSI networks.
     *
     * Expected behavior: WifiConnectivityManager doubles the low RSSI
     * network retry delay value after QNS skips the PNO scan results
     * because of their low RSSI values.
     */
    @Test
    @Ignore("b/32977707")
    public void pnoRetryForLowRssiNetwork() {
        when(mWifiNS.selectNetwork(anyObject(), anyObject(), anyBoolean(), anyBoolean(),
              anyBoolean())).thenReturn(null);

        // Set screen to off
        mWifiConnectivityManager.handleScreenStateChanged(false);

        // Get the current retry delay value
        int lowRssiNetworkRetryDelayStartValue = mWifiConnectivityManager
                .getLowRssiNetworkRetryDelay();

        // Set WiFi to disconnected state to trigger PNO scan
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_DISCONNECTED);

        // Get the retry delay value after QNS didn't select a
        // network candicate from the PNO scan results.
        int lowRssiNetworkRetryDelayAfterPnoValue = mWifiConnectivityManager
                .getLowRssiNetworkRetryDelay();

        assertEquals(lowRssiNetworkRetryDelayStartValue * 2,
                lowRssiNetworkRetryDelayAfterPnoValue);
    }

    /**
     * Ensure that the watchdog bite increments the "Pno bad" metric.
     *
     * Expected behavior: WifiConnectivityManager detects that the PNO scan failed to find
     * a candidate while watchdog single scan did.
     */
    @Test
    @Ignore("b/32977707")
    public void watchdogBitePnoBadIncrementsMetrics() {
        // Set screen to off
        mWifiConnectivityManager.handleScreenStateChanged(false);

        // Set WiFi to disconnected state to trigger PNO scan
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_DISCONNECTED);

        // Now fire the watchdog alarm and verify the metrics were incremented.
        mAlarmManager.dispatch(WifiConnectivityManager.WATCHDOG_TIMER_TAG);
        mLooper.dispatchAll();

        verify(mWifiMetrics).incrementNumConnectivityWatchdogPnoBad();
        verify(mWifiMetrics, never()).incrementNumConnectivityWatchdogPnoGood();
    }

    /**
     * Ensure that the watchdog bite increments the "Pno good" metric.
     *
     * Expected behavior: WifiConnectivityManager detects that the PNO scan failed to find
     * a candidate which was the same with watchdog single scan.
     */
    @Test
    @Ignore("b/32977707")
    public void watchdogBitePnoGoodIncrementsMetrics() {
        // Qns returns no candidate after watchdog single scan.
        when(mWifiNS.selectNetwork(anyObject(), anyObject(), anyBoolean(), anyBoolean(),
              anyBoolean())).thenReturn(null);

        // Set screen to off
        mWifiConnectivityManager.handleScreenStateChanged(false);

        // Set WiFi to disconnected state to trigger PNO scan
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_DISCONNECTED);

        // Now fire the watchdog alarm and verify the metrics were incremented.
        mAlarmManager.dispatch(WifiConnectivityManager.WATCHDOG_TIMER_TAG);
        mLooper.dispatchAll();

        verify(mWifiMetrics).incrementNumConnectivityWatchdogPnoGood();
        verify(mWifiMetrics, never()).incrementNumConnectivityWatchdogPnoBad();
    }

    /**
     *  Verify that scan interval for screen on and wifi disconnected scenario
     *  is in the exponential backoff fashion.
     *
     * Expected behavior: WifiConnectivityManager doubles periodic
     * scan interval.
     */
    @Test
    public void checkPeriodicScanIntervalWhenDisconnected() {
        long currentTimeStamp = CURRENT_SYSTEM_TIME_MS;
        when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTimeStamp);

        // Set screen to ON
        mWifiConnectivityManager.handleScreenStateChanged(true);

        // Wait for MAX_PERIODIC_SCAN_INTERVAL_MS so that any impact triggered
        // by screen state change can settle
        currentTimeStamp += WifiConnectivityManager.MAX_PERIODIC_SCAN_INTERVAL_MS;
        when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTimeStamp);

        // Set WiFi to disconnected state to trigger periodic scan
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_DISCONNECTED);

        // Get the first periodic scan interval
        long firstIntervalMs = mAlarmManager
                    .getTriggerTimeMillis(WifiConnectivityManager.PERIODIC_SCAN_TIMER_TAG)
                    - currentTimeStamp;
        assertEquals(firstIntervalMs, WifiConnectivityManager.PERIODIC_SCAN_INTERVAL_MS);

        currentTimeStamp += firstIntervalMs;
        when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTimeStamp);

        // Now fire the first periodic scan alarm timer
        mAlarmManager.dispatch(WifiConnectivityManager.PERIODIC_SCAN_TIMER_TAG);
        mLooper.dispatchAll();

        // Get the second periodic scan interval
        long secondIntervalMs = mAlarmManager
                    .getTriggerTimeMillis(WifiConnectivityManager.PERIODIC_SCAN_TIMER_TAG)
                    - currentTimeStamp;

        // Verify the intervals are exponential back off
        assertEquals(firstIntervalMs * 2, secondIntervalMs);

        currentTimeStamp += secondIntervalMs;
        when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTimeStamp);

        // Make sure we eventually stay at the maximum scan interval.
        long intervalMs = 0;
        for (int i = 0; i < 5; i++) {
            mAlarmManager.dispatch(WifiConnectivityManager.PERIODIC_SCAN_TIMER_TAG);
            mLooper.dispatchAll();
            intervalMs = mAlarmManager
                    .getTriggerTimeMillis(WifiConnectivityManager.PERIODIC_SCAN_TIMER_TAG)
                    - currentTimeStamp;
            currentTimeStamp += intervalMs;
            when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTimeStamp);
        }

        assertEquals(intervalMs, WifiConnectivityManager.MAX_PERIODIC_SCAN_INTERVAL_MS);
    }

    /**
     *  Verify that scan interval for screen on and wifi connected scenario
     *  is in the exponential backoff fashion.
     *
     * Expected behavior: WifiConnectivityManager doubles periodic
     * scan interval.
     */
    @Test
    public void checkPeriodicScanIntervalWhenConnected() {
        long currentTimeStamp = CURRENT_SYSTEM_TIME_MS;
        when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTimeStamp);

        // Set screen to ON
        mWifiConnectivityManager.handleScreenStateChanged(true);

        // Wait for MAX_PERIODIC_SCAN_INTERVAL_MS so that any impact triggered
        // by screen state change can settle
        currentTimeStamp += WifiConnectivityManager.MAX_PERIODIC_SCAN_INTERVAL_MS;
        when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTimeStamp);

        // Set WiFi to connected state to trigger periodic scan
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_CONNECTED);

        // Get the first periodic scan interval
        long firstIntervalMs = mAlarmManager
                    .getTriggerTimeMillis(WifiConnectivityManager.PERIODIC_SCAN_TIMER_TAG)
                    - currentTimeStamp;
        assertEquals(firstIntervalMs, WifiConnectivityManager.PERIODIC_SCAN_INTERVAL_MS);

        currentTimeStamp += firstIntervalMs;
        when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTimeStamp);

        // Now fire the first periodic scan alarm timer
        mAlarmManager.dispatch(WifiConnectivityManager.PERIODIC_SCAN_TIMER_TAG);
        mLooper.dispatchAll();

        // Get the second periodic scan interval
        long secondIntervalMs = mAlarmManager
                    .getTriggerTimeMillis(WifiConnectivityManager.PERIODIC_SCAN_TIMER_TAG)
                    - currentTimeStamp;

        // Verify the intervals are exponential back off
        assertEquals(firstIntervalMs * 2, secondIntervalMs);

        currentTimeStamp += secondIntervalMs;
        when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTimeStamp);

        // Make sure we eventually stay at the maximum scan interval.
        long intervalMs = 0;
        for (int i = 0; i < 5; i++) {
            mAlarmManager.dispatch(WifiConnectivityManager.PERIODIC_SCAN_TIMER_TAG);
            mLooper.dispatchAll();
            intervalMs = mAlarmManager
                    .getTriggerTimeMillis(WifiConnectivityManager.PERIODIC_SCAN_TIMER_TAG)
                    - currentTimeStamp;
            currentTimeStamp += intervalMs;
            when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTimeStamp);
        }

        assertEquals(intervalMs, WifiConnectivityManager.MAX_PERIODIC_SCAN_INTERVAL_MS);
    }

    /**
     *  When screen on trigger two connection state change events back to back to
     *  verify that the minium scan interval is enforced.
     *
     * Expected behavior: WifiConnectivityManager start the second periodic single
     * scan PERIODIC_SCAN_INTERVAL_MS after the first one.
     */
    @Test
    public void checkMinimumPeriodicScanIntervalWhenScreenOn() {
        long currentTimeStamp = CURRENT_SYSTEM_TIME_MS;
        when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTimeStamp);

        // Set screen to ON
        mWifiConnectivityManager.handleScreenStateChanged(true);

        // Wait for MAX_PERIODIC_SCAN_INTERVAL_MS so that any impact triggered
        // by screen state change can settle
        currentTimeStamp += WifiConnectivityManager.MAX_PERIODIC_SCAN_INTERVAL_MS;
        long firstScanTimeStamp = currentTimeStamp;
        when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTimeStamp);

        // Set WiFi to connected state to trigger the periodic scan
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_CONNECTED);

        // Set the second scan attempt time stamp.
        currentTimeStamp += 2000;
        when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTimeStamp);

        // Set WiFi to disconnected state to trigger another periodic scan
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_DISCONNECTED);

        // Get the second periodic scan actual time stamp
        long secondScanTimeStamp = mAlarmManager
                    .getTriggerTimeMillis(WifiConnectivityManager.PERIODIC_SCAN_TIMER_TAG);

        // Verify that the second scan is scheduled PERIODIC_SCAN_INTERVAL_MS after the
        // very first scan.
        assertEquals(secondScanTimeStamp, firstScanTimeStamp
                       + WifiConnectivityManager.PERIODIC_SCAN_INTERVAL_MS);

    }

    /**
     *  When screen on trigger a connection state change event and a forced connectivity
     *  scan event back to back to verify that the minimum scan interval is not applied
     *  in this scenario.
     *
     * Expected behavior: WifiConnectivityManager starts the second periodic single
     * scan immediately.
     */
    @Test
    public void checkMinimumPeriodicScanIntervalNotEnforced() {
        long currentTimeStamp = CURRENT_SYSTEM_TIME_MS;
        when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTimeStamp);

        // Set screen to ON
        mWifiConnectivityManager.handleScreenStateChanged(true);

        // Wait for MAX_PERIODIC_SCAN_INTERVAL_MS so that any impact triggered
        // by screen state change can settle
        currentTimeStamp += WifiConnectivityManager.MAX_PERIODIC_SCAN_INTERVAL_MS;
        long firstScanTimeStamp = currentTimeStamp;
        when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTimeStamp);

        // Set WiFi to connected state to trigger the periodic scan
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_CONNECTED);

        // Set the second scan attempt time stamp
        currentTimeStamp += 2000;
        when(mClock.getElapsedSinceBootMillis()).thenReturn(currentTimeStamp);

        // Allow untrusted networks so WifiConnectivityManager starts a periodic scan
        // immediately.
        mWifiConnectivityManager.setUntrustedConnectionAllowed(true);

        // Get the second periodic scan actual time stamp. Note, this scan is not
        // started from the AlarmManager.
        long secondScanTimeStamp = mWifiConnectivityManager.getLastPeriodicSingleScanTimeStamp();

        // Verify that the second scan is fired immediately
        assertEquals(secondScanTimeStamp, currentTimeStamp);
    }

    /**
     * Verify that we perform full band scan when the currently connected network's tx/rx success
     * rate is low.
     *
     * Expected behavior: WifiConnectivityManager does full band scan.
     */
    @Test
    public void checkSingleScanSettingsWhenConnectedWithLowDataRate() {
        mWifiInfo.txSuccessRate = 0;
        mWifiInfo.rxSuccessRate = 0;

        final HashSet<Integer> channelList = new HashSet<>();
        channelList.add(1);
        channelList.add(2);
        channelList.add(3);

        when(mWifiStateMachine.getCurrentWifiConfiguration())
                .thenReturn(new WifiConfiguration());
        when(mWifiConfigManager.fetchChannelSetForNetworkForPartialScan(anyInt(), anyInt(),
              anyInt())).thenReturn(channelList);

        doAnswer(new AnswerWithArguments() {
            public void answer(ScanSettings settings, ScanListener listener,
                    WorkSource workSource) throws Exception {
                assertEquals(settings.band, WifiScanner.WIFI_BAND_BOTH_WITH_DFS);
                assertNull(settings.channels);
            }}).when(mWifiScanner).startScan(anyObject(), anyObject(), anyObject());

        // Set screen to ON
        mWifiConnectivityManager.handleScreenStateChanged(true);

        // Set WiFi to connected state to trigger periodic scan
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_CONNECTED);

        verify(mWifiScanner).startScan(anyObject(), anyObject(), anyObject());
    }

    /**
     * Verify that we perform partial scan when the currently connected network's tx/rx success
     * rate is high and when the currently connected network is present in scan
     * cache in WifiConfigManager.
     *
     * Expected behavior: WifiConnectivityManager does full band scan.
     */
    @Test
    public void checkSingleScanSettingsWhenConnectedWithHighDataRate() {
        mWifiInfo.txSuccessRate = WifiConnectivityManager.MAX_TX_PACKET_FOR_FULL_SCANS * 2;
        mWifiInfo.rxSuccessRate = WifiConnectivityManager.MAX_RX_PACKET_FOR_FULL_SCANS * 2;

        final HashSet<Integer> channelList = new HashSet<>();
        channelList.add(1);
        channelList.add(2);
        channelList.add(3);

        when(mWifiStateMachine.getCurrentWifiConfiguration())
                .thenReturn(new WifiConfiguration());
        when(mWifiConfigManager.fetchChannelSetForNetworkForPartialScan(anyInt(), anyInt(),
                anyInt())).thenReturn(channelList);

        doAnswer(new AnswerWithArguments() {
            public void answer(ScanSettings settings, ScanListener listener,
                    WorkSource workSource) throws Exception {
                assertEquals(settings.band, WifiScanner.WIFI_BAND_UNSPECIFIED);
                assertEquals(settings.channels.length, channelList.size());
                for (int chanIdx = 0; chanIdx < settings.channels.length; chanIdx++) {
                    assertTrue(channelList.contains(settings.channels[chanIdx].frequency));
                }
            }}).when(mWifiScanner).startScan(anyObject(), anyObject(), anyObject());

        // Set screen to ON
        mWifiConnectivityManager.handleScreenStateChanged(true);

        // Set WiFi to connected state to trigger periodic scan
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_CONNECTED);

        verify(mWifiScanner).startScan(anyObject(), anyObject(), anyObject());
    }

    /**
     * Verify that we fall back to full band scan when the currently connected network's tx/rx
     * success rate is high and the currently connected network is not present in scan cache in
     * WifiConfigManager. This is simulated by returning an empty hashset in |makeChannelList|.
     *
     * Expected behavior: WifiConnectivityManager does full band scan.
     */
    @Test
    public void checkSingleScanSettingsWhenConnectedWithHighDataRateNotInCache() {
        mWifiInfo.txSuccessRate = WifiConnectivityManager.MAX_TX_PACKET_FOR_FULL_SCANS * 2;
        mWifiInfo.rxSuccessRate = WifiConnectivityManager.MAX_RX_PACKET_FOR_FULL_SCANS * 2;

        final HashSet<Integer> channelList = new HashSet<>();

        when(mWifiStateMachine.getCurrentWifiConfiguration())
                .thenReturn(new WifiConfiguration());
        when(mWifiConfigManager.fetchChannelSetForNetworkForPartialScan(anyInt(), anyInt(),
                anyInt())).thenReturn(channelList);

        doAnswer(new AnswerWithArguments() {
            public void answer(ScanSettings settings, ScanListener listener,
                    WorkSource workSource) throws Exception {
                assertEquals(settings.band, WifiScanner.WIFI_BAND_BOTH_WITH_DFS);
                assertNull(settings.channels);
            }}).when(mWifiScanner).startScan(anyObject(), anyObject(), anyObject());

        // Set screen to ON
        mWifiConnectivityManager.handleScreenStateChanged(true);

        // Set WiFi to connected state to trigger periodic scan
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_CONNECTED);

        verify(mWifiScanner).startScan(anyObject(), anyObject(), anyObject());
    }

    /**
     *  Verify that we retry connectivity scan up to MAX_SCAN_RESTART_ALLOWED times
     *  when Wifi somehow gets into a bad state and fails to scan.
     *
     * Expected behavior: WifiConnectivityManager schedules connectivity scan
     * MAX_SCAN_RESTART_ALLOWED times.
     */
    @Test
    public void checkMaximumScanRetry() {
        // Set screen to ON
        mWifiConnectivityManager.handleScreenStateChanged(true);

        doAnswer(new AnswerWithArguments() {
            public void answer(ScanSettings settings, ScanListener listener,
                    WorkSource workSource) throws Exception {
                listener.onFailure(-1, "ScanFailure");
            }}).when(mWifiScanner).startScan(anyObject(), anyObject(), anyObject());

        // Set WiFi to disconnected state to trigger the single scan based periodic scan
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_DISCONNECTED);

        // Fire the alarm timer 2x timers
        for (int i = 0; i < (WifiConnectivityManager.MAX_SCAN_RESTART_ALLOWED * 2); i++) {
            mAlarmManager.dispatch(WifiConnectivityManager.RESTART_SINGLE_SCAN_TIMER_TAG);
            mLooper.dispatchAll();
        }

        // Verify that the connectivity scan has been retried for MAX_SCAN_RESTART_ALLOWED
        // times. Note, WifiScanner.startScan() is invoked MAX_SCAN_RESTART_ALLOWED + 1 times.
        // The very first scan is the initial one, and the other MAX_SCAN_RESTART_ALLOWED
        // are the retrial ones.
        verify(mWifiScanner, times(WifiConnectivityManager.MAX_SCAN_RESTART_ALLOWED + 1)).startScan(
                anyObject(), anyObject(), anyObject());
    }

    /**
     * Listen to scan results not requested by WifiConnectivityManager and
     * act on them.
     *
     * Expected behavior: WifiConnectivityManager calls
     * WifiStateMachine.startConnectToNetwork() with the
     * expected candidate network ID and BSSID.
     */
    @Test
    public void listenToAllSingleScanResults() {
        ScanSettings settings = new ScanSettings();
        ScanListener scanListener = mock(ScanListener.class);

        // Request a single scan outside of WifiConnectivityManager.
        mWifiScanner.startScan(settings, scanListener, WIFI_WORK_SOURCE);

        // Verify that WCM receives the scan results and initiates a connection
        // to the network.
        verify(mWifiStateMachine).startConnectToNetwork(
                CANDIDATE_NETWORK_ID, CANDIDATE_BSSID);
    }

    /**
     *  Verify that a forced connectivity scan waits for full band scan
     *  results.
     *
     * Expected behavior: WifiConnectivityManager doesn't invoke
     * WifiStateMachine.startConnectToNetwork() when full band scan
     * results are not available.
     */
    @Test
    public void waitForFullBandScanResults() {
        // Set WiFi to connected state.
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_CONNECTED);

        // Set up as partial scan results.
        when(mScanData.isAllChannelsScanned()).thenReturn(false);

        // Force a connectivity scan which enables WifiConnectivityManager
        // to wait for full band scan results.
        mWifiConnectivityManager.forceConnectivityScan();

        // No roaming because no full band scan results.
        verify(mWifiStateMachine, times(0)).startConnectToNetwork(
                CANDIDATE_NETWORK_ID, CANDIDATE_BSSID);

        // Set up as full band scan results.
        when(mScanData.isAllChannelsScanned()).thenReturn(true);

        // Force a connectivity scan which enables WifiConnectivityManager
        // to wait for full band scan results.
        mWifiConnectivityManager.forceConnectivityScan();

        // Roaming attempt because full band scan results are available.
        verify(mWifiStateMachine).startConnectToNetwork(
                CANDIDATE_NETWORK_ID, CANDIDATE_BSSID);
    }
}
