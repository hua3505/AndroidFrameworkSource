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

import static com.android.server.wifi.WifiConfigurationTestUtil.SECURITY_NONE;
import static com.android.server.wifi.WifiConfigurationTestUtil.SECURITY_PSK;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.res.Resources;
import android.net.NetworkScoreManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.os.SystemClock;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Pair;

import com.android.internal.R;
import com.android.server.wifi.WifiNetworkSelectorTestUtil.ScanDetailsAndWifiConfigs;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * Unit tests for {@link com.android.server.wifi.WifiNetworkSelector}.
 */
@SmallTest
public class WifiNetworkSelectorTest {

    /** Sets up test. */
    @Before
    public void setUp() throws Exception {
        mResource = getResource();
        mContext = getContext();
        mWifiConfigManager = getWifiConfigManager();
        mWifiInfo = getWifiInfo();

        mWifiNetworkSelector = new WifiNetworkSelector(mContext, mWifiConfigManager, mClock);
        mWifiNetworkSelector.registerNetworkEvaluator(mDummyEvaluator, 1);
        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime());

        mThresholdMinimumRssi2G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_24GHz);
        mThresholdMinimumRssi5G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_5GHz);
        mThresholdQualifiedRssi2G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz);
        mThresholdQualifiedRssi5G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz);
    }

    /** Cleans up test. */
    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    /**
     * All this dummy network evaluator does is to pick the very first network
     * in the scan results.
     */
    public class DummyNetworkEvaluator implements WifiNetworkSelector.NetworkEvaluator {
        private static final String NAME = "DummyNetworkEvaluator";
        private WifiConfigManager mConfigManager;

        /**
         * Get the evaluator name.
         */
        public String getName() {
            return NAME;
        }

        /**
         * Update thee evaluator.
         */
        public void update(List<ScanDetail> scanDetails) {
        }

        /**
         * Always return the first network in the scan results for connection.
         */
        public WifiConfiguration evaluateNetworks(List<ScanDetail> scanDetails,
                    WifiConfiguration currentNetwork, String currentBssid, boolean connected,
                    boolean untrustedNetworkAllowed,
                    List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks) {
            ScanDetail scanDetail = scanDetails.get(0);
            mWifiConfigManager.setNetworkCandidateScanResult(0, scanDetail.getScanResult(), 100);

            return mWifiConfigManager.getSavedNetworkForScanDetailAndCache(scanDetail);
        }
    }

    private WifiNetworkSelector mWifiNetworkSelector = null;
    private DummyNetworkEvaluator mDummyEvaluator = new DummyNetworkEvaluator();
    private WifiConfigManager mWifiConfigManager = null;
    private Context mContext;
    private Resources mResource;
    private WifiInfo mWifiInfo;
    private Clock mClock = mock(Clock.class);
    private int mThresholdMinimumRssi2G;
    private int mThresholdMinimumRssi5G;
    private int mThresholdQualifiedRssi2G;
    private int mThresholdQualifiedRssi5G;

    Context getContext() {
        Context context = mock(Context.class);
        Resources resource = mock(Resources.class);

        when(context.getResources()).thenReturn(mResource);
        return context;
    }

    Resources getResource() {
        Resources resource = mock(Resources.class);

        when(resource.getBoolean(
                R.bool.config_wifi_framework_enable_associated_network_selection)).thenReturn(true);
        when(resource.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz))
                .thenReturn(-70);
        when(resource.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz))
                .thenReturn(-73);
        when(resource.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_5GHz))
                .thenReturn(-82);
        when(resource.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_24GHz))
                .thenReturn(-85);
        return resource;
    }

    NetworkScoreManager getNetworkScoreManager() {
        NetworkScoreManager networkScoreManager = mock(NetworkScoreManager.class);

        return networkScoreManager;
    }

    WifiInfo getWifiInfo() {
        WifiInfo wifiInfo = mock(WifiInfo.class);

        // simulate a disconnected state
        when(wifiInfo.is24GHz()).thenReturn(true);
        when(wifiInfo.is5GHz()).thenReturn(false);
        when(wifiInfo.getRssi()).thenReturn(-70);
        when(wifiInfo.getNetworkId()).thenReturn(WifiConfiguration.INVALID_NETWORK_ID);
        when(wifiInfo.getBSSID()).thenReturn(null);
        return wifiInfo;
    }

    WifiConfigManager getWifiConfigManager() {
        WifiConfigManager wifiConfigManager = mock(WifiConfigManager.class);
        when(wifiConfigManager.getLastSelectedNetwork())
                .thenReturn(WifiConfiguration.INVALID_NETWORK_ID);
        return wifiConfigManager;
    }


    /**
     * No network selection if scan result is empty.
     *
     * WifiStateMachine is in disconnected state.
     * scanDetails is empty.
     *
     * Expected behavior: no network recommended by Network Selector
     */
    @Test
    public void emptyScanResults() {
        String[] ssids = new String[0];
        String[] bssids = new String[0];
        int[] freqs = new int[0];
        String[] caps = new String[0];
        int[] levels = new int[0];
        int[] securities = new int[0];

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                mWifiInfo, false, true, false);
        assertEquals("Expect null configuration", null, candidate);
    }


    /**
     * No network selection if the RSSI values in scan result are too low.
     *
     * WifiStateMachine is in disconnected state.
     * scanDetails contains a 2.4GHz and a 5GHz network, but both with RSSI lower than
     * the threshold
     *
     * Expected behavior: no network recommended by Network Selector
     */
    @Test
    public void verifyMinimumRssiThreshold() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G - 1, mThresholdMinimumRssi5G - 1};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                mWifiInfo, false, true, false);
        assertEquals("Expect null configuration", null, candidate);
    }

    /**
     * No network selection if WiFi is connected and it is too short from last
     * network selection.
     *
     * WifiStateMachine is in connected state.
     * scanDetails contains two valid networks.
     * Perform a network seletion right after the first one.
     *
     * Expected behavior: no network recommended by Network Selector
     */
    @Test
    public void verifyMinimumTimeGapWhenConnected() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + 1, mThresholdMinimumRssi5G + 1};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        // Make a network selection.
        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                mWifiInfo, false, true, false);

        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime()
                + WifiNetworkSelector.MINIMUM_NETWORK_SELECTION_INTERVAL_MS - 2000);

        // Do another network selection with WSM in CONNECTED state.
        candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                mWifiInfo, true, false, false);

        assertEquals("Expect null configuration", null, candidate);
    }

    /**
     * Perform network selection if WiFi is disconnected even if it is too short from last
     * network selection.
     *
     * WifiStateMachine is in disconnected state.
     * scanDetails contains two valid networks.
     * Perform a network seletion right after the first one.
     *
     * Expected behavior: the first network is recommended by Network Selector
     */
    @Test
    public void verifyNoMinimumTimeGapWhenDisconnected() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + 1, mThresholdMinimumRssi5G + 1};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        // Make a network selection.
        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                mWifiInfo, false, true, false);

        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime()
                + WifiNetworkSelector.MINIMUM_NETWORK_SELECTION_INTERVAL_MS - 2000);

        // Do another network selection with WSM in DISCONNECTED state.
        candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                mWifiInfo, false, true, false);

        ScanResult chosenScanResult = scanDetails.get(0).getScanResult();
        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[0], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                chosenScanResult, candidate);
    }

    /**
     * No network selection if the currently connected on is already sufficient.
     *
     * WifiStateMachine is connected to a qualified (5G, secure, good RSSI) network.
     * scanDetails contains a valid network.
     * Perform a network seletion after the first one.
     *
     * Expected behavior: no network recommended by Network Selector
     */
    @Test
    public void noNetworkSelectionWhenCurrentOneIsSufficient() {
        String[] ssids = {"\"test1\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3"};
        int[] freqs = {5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdQualifiedRssi5G + 8};
        int[] securities = {SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();

        // connect to test1
        mWifiNetworkSelector.selectNetwork(scanDetails, mWifiInfo, false, true, false);
        when(mWifiInfo.getNetworkId()).thenReturn(0);
        when(mWifiInfo.getBSSID()).thenReturn(bssids[0]);
        when(mWifiInfo.is24GHz()).thenReturn(false);

        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime()
                + WifiNetworkSelector.MINIMUM_NETWORK_SELECTION_INTERVAL_MS + 2000);

        levels[0] = mThresholdQualifiedRssi5G + 20;
        scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        scanDetails = scanDetailsAndConfigs.getScanDetails();

        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                mWifiInfo, true, false, false);
        assertEquals("Expect null configuration", null, candidate);
    }


    /**
     * New network selection is performed if the currently connected network
     * band is 2G.
     *
     * WifiStateMachine is connected to a 2G network.
     * scanDetails contains a valid networks.
     * Perform a network seletion after the first one.
     *
     * Expected behavior: the first network is recommended by Network Selector
     */
    @Test
    public void band2GNetworkIsNotSufficient() {
        String[] ssids = {"\"test1\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3"};
        int[] freqs = {2470};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdQualifiedRssi2G + 8};
        int[] securities = {SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();

        // connect to test1
        mWifiNetworkSelector.selectNetwork(scanDetails, mWifiInfo, false, true, false);
        when(mWifiInfo.getNetworkId()).thenReturn(0);
        when(mWifiInfo.getBSSID()).thenReturn(bssids[0]);
        when(mWifiInfo.is24GHz()).thenReturn(true);

        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime()
                + WifiNetworkSelector.MINIMUM_NETWORK_SELECTION_INTERVAL_MS + 2000);

        // Do another network selection.
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                mWifiInfo, true, false, false);

        ScanResult chosenScanResult = scanDetails.get(0).getScanResult();
        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[0], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                chosenScanResult, candidate);
    }


    /**
     * New network selection is performed if the currently connected network
     * is a open one.
     *
     * WifiStateMachine is connected to a open network.
     * scanDetails contains a valid networks.
     * Perform a network seletion after the first one.
     *
     * Expected behavior: the first network is recommended by Network Selector
     */
    @Test
    public void openNetworkIsNotSufficient() {
        String[] ssids = {"\"test1\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3"};
        int[] freqs = {5180};
        String[] caps = {"[ESS]"};
        int[] levels = {mThresholdQualifiedRssi5G + 8};
        int[] securities = {SECURITY_NONE};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();

        // connect to test1
        mWifiNetworkSelector.selectNetwork(scanDetails, mWifiInfo, false, true, false);
        when(mWifiInfo.getNetworkId()).thenReturn(0);
        when(mWifiInfo.getBSSID()).thenReturn(bssids[0]);
        when(mWifiInfo.is24GHz()).thenReturn(false);
        when(mWifiInfo.is5GHz()).thenReturn(true);

        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime()
                + WifiNetworkSelector.MINIMUM_NETWORK_SELECTION_INTERVAL_MS + 2000);

        // Do another network selection.
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                mWifiInfo, true, false, false);

        ScanResult chosenScanResult = scanDetails.get(0).getScanResult();
        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[0], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                chosenScanResult, candidate);
    }

    /**
     * New network selection is performed if the currently connected network
     * has low RSSI value.
     *
     * WifiStateMachine is connected to a low RSSI 5GHz network.
     * scanDetails contains a valid networks.
     * Perform a network seletion after the first one.
     *
     * Expected behavior: the first network is recommended by Network Selector
     */
    @Test
    public void lowRssi5GNetworkIsNotSufficient() {
        String[] ssids = {"\"test1\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3"};
        int[] freqs = {5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdQualifiedRssi5G - 2};
        int[] securities = {SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();

        // connect to test1
        mWifiNetworkSelector.selectNetwork(scanDetails, mWifiInfo, false, true, false);
        when(mWifiInfo.getNetworkId()).thenReturn(0);
        when(mWifiInfo.getBSSID()).thenReturn(bssids[0]);
        when(mWifiInfo.is24GHz()).thenReturn(false);
        when(mWifiInfo.is5GHz()).thenReturn(true);
        when(mWifiInfo.getRssi()).thenReturn(levels[0]);

        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime()
                + WifiNetworkSelector.MINIMUM_NETWORK_SELECTION_INTERVAL_MS + 2000);

        // Do another network selection.
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                mWifiInfo, true, false, false);

        ScanResult chosenScanResult = scanDetails.get(0).getScanResult();
        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[0], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                chosenScanResult, candidate);
    }

    /**
     * Blacklisted BSSID is filtered out for network selection.
     *
     * WifiStateMachine is disconnected.
     * scanDetails contains a network which is blacklisted.
     *
     * Expected behavior: no network recommended by Network Selector
     */
    @Test
    public void filterOutBlacklistedBssid() {
        String[] ssids = {"\"test1\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3"};
        int[] freqs = {5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdQualifiedRssi5G + 8};
        int[] securities = {SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();

        // Disable this network for BSSID_BLACKLIST_THRESHOLD times so it gets
        // blacklisted by WNS.
        for (int i = 0; i < WifiNetworkSelector.BSSID_BLACKLIST_THRESHOLD; i++) {
            mWifiNetworkSelector.enableBssidForNetworkSelection(bssids[0], false);
        }

        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                mWifiInfo, false, true, false);
        assertEquals("Expect null configuration", null, candidate);
    }

    /**
     * Wifi network selector doesn't recommend any network if the currently connected one
     * doesn't show up in the scan results.
     *
     * WifiStateMachine is under connected state and 2.4GHz test1 is connected.
     * The second scan results contains only test2 which now has a stronger RSSI than test1.
     * Test1 is not in the second scan results.
     *
     * Expected behavior: no network recommended by Network Selector
     */
    @Test
    public void noSelectionWhenCurrentNetworkNotInScanResults() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 2457};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + 20, mThresholdMinimumRssi2G + 1};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        // Make a network selection to connect to test1.
        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                mWifiInfo, false, true, false);

        when(mWifiInfo.getNetworkId()).thenReturn(0);
        when(mWifiInfo.getBSSID()).thenReturn(bssids[0]);
        when(mWifiInfo.is24GHz()).thenReturn(true);
        when(mWifiInfo.is5GHz()).thenReturn(false);
        when(mWifiInfo.getRssi()).thenReturn(levels[0]);
        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime()
                + WifiNetworkSelector.MINIMUM_NETWORK_SELECTION_INTERVAL_MS + 2000);

        // Prepare the second scan results which have no test1.
        String[] ssidsNew = {"\"test2\""};
        String[] bssidsNew = {"6c:f3:7f:ae:8c:f4"};
        int[] freqsNew = {2457};
        String[] capsNew = {"[WPA2-EAP-CCMP][ESS]"};
        int[] levelsNew = {mThresholdMinimumRssi2G + 40};
        scanDetails = WifiNetworkSelectorTestUtil.buildScanDetails(ssidsNew, bssidsNew,
                freqsNew, capsNew, levelsNew, mClock);
        candidate = mWifiNetworkSelector.selectNetwork(scanDetails, mWifiInfo, true, false, false);

        // The second network selection is skipped since current connected network is
        // missing from the scan results.
        assertEquals("Expect null configuration", null, candidate);
    }
}
