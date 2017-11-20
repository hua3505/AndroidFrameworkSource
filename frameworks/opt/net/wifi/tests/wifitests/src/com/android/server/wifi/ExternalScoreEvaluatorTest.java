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

import android.app.test.MockAnswerUtil.AnswerWithArguments;
import android.content.Context;
import android.content.res.Resources;
import android.net.INetworkScoreCache;
import android.net.NetworkScoreManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.os.SystemClock;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.R;
import com.android.server.wifi.WifiNetworkSelectorTestUtil.ScanDetailsAndWifiConfigs;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * Unit tests for {@link com.android.server.wifi.ExternalScoreEvaluator}.
 */
@SmallTest
public class ExternalScoreEvaluatorTest {

    /** Sets up test. */
    @Before
    public void setUp() throws Exception {
        mResource = getResource();
        mScoreManager = getScoreManager();
        mContext = getContext();
        mWifiConfigManager = getWifiConfigManager();
        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime());

        mThresholdQualifiedRssi2G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz);
        mThresholdQualifiedRssi5G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz);

        mExternalScoreEvaluator = new ExternalScoreEvaluator(mContext, mWifiConfigManager,
                mClock, null);
    }

    /** Cleans up test. */
    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    private ExternalScoreEvaluator mExternalScoreEvaluator;
    private WifiConfigManager mWifiConfigManager;
    private Context mContext;
    private Resources mResource;
    private NetworkScoreManager mScoreManager;
    private WifiNetworkScoreCache mScoreCache;
    private Clock mClock = mock(Clock.class);
    private int mThresholdQualifiedRssi2G;
    private int mThresholdQualifiedRssi5G;
    private static final String TAG = "External Score Evaluator Unit Test";

    NetworkScoreManager getScoreManager() {
        NetworkScoreManager scoreManager = mock(NetworkScoreManager.class);

        doAnswer(new AnswerWithArguments() {
                public void answer(int networkType, INetworkScoreCache scoreCache) {
                    mScoreCache = (WifiNetworkScoreCache) scoreCache;
                }}).when(scoreManager).registerNetworkScoreCache(anyInt(), anyObject());

        return scoreManager;
    }

    Context getContext() {
        Context context = mock(Context.class);

        when(context.getResources()).thenReturn(mResource);
        when(context.getSystemService(Context.NETWORK_SCORE_SERVICE)).thenReturn(mScoreManager);

        return context;
    }

    Resources getResource() {
        Resources resource = mock(Resources.class);

        when(resource.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz))
                .thenReturn(-70);
        when(resource.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz))
                .thenReturn(-73);

        return resource;
    }

    WifiConfigManager getWifiConfigManager() {
        WifiConfigManager wifiConfigManager = mock(WifiConfigManager.class);
        when(wifiConfigManager.getLastSelectedNetwork())
                .thenReturn(WifiConfiguration.INVALID_NETWORK_ID);
        return wifiConfigManager;
    }


    /**
     * When no saved networks available, choose the available ephemeral networks
     * if untrusted networks are allowed.
     */
    @Test
    public void chooseEphemeralNetworkBecauseOfNoSavedNetwork() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[ESS]"};
        int[] levels = {mThresholdQualifiedRssi2G + 8, mThresholdQualifiedRssi2G + 10};
        Integer[] scores = {null, 120};
        boolean[] meteredHints = {false, true};

        List<ScanDetail> scanDetails = WifiNetworkSelectorTestUtil.buildScanDetails(
                    ssids, bssids, freqs, caps, levels, mClock);
        WifiNetworkSelectorTestUtil.configureScoreCache(mScoreCache,
                    scanDetails, scores, meteredHints);

        // No saved networks.
        when(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(any(ScanDetail.class)))
                .thenReturn(null);

        ScanResult scanResult = scanDetails.get(1).getScanResult();
        WifiConfiguration ephemeralNetworkConfig = WifiNetworkSelectorTestUtil
                .setupEphemeralNetwork(mWifiConfigManager, 1, scanResult, meteredHints[1]);

        // Untrusted networks allowed.
        WifiConfiguration candidate = mExternalScoreEvaluator.evaluateNetworks(scanDetails,
                null, null, false, true, null);

        WifiConfigurationTestUtil.assertConfigurationEqual(ephemeralNetworkConfig, candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                scanResult, candidate);
        assertEquals(meteredHints[1], candidate.meteredHint);
    }

    /**
     * When no saved networks available, choose the highest scored ephemeral networks
     * if untrusted networks are allowed.
     */
    @Test
    public void chooseHigherScoredEphemeralNetwork() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[ESS]", "[ESS]"};
        int[] levels = {mThresholdQualifiedRssi2G + 8, mThresholdQualifiedRssi2G + 8};
        Integer[] scores = {100, 120};
        boolean[] meteredHints = {true, true};
        ScanResult[] scanResults = new ScanResult[2];
        WifiConfiguration[] ephemeralNetworkConfigs = new WifiConfiguration[2];

        List<ScanDetail> scanDetails = WifiNetworkSelectorTestUtil.buildScanDetails(
                    ssids, bssids, freqs, caps, levels, mClock);
        WifiNetworkSelectorTestUtil.configureScoreCache(mScoreCache,
                    scanDetails, scores, meteredHints);

        // No saved networks.
        when(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(any(ScanDetail.class)))
                .thenReturn(null);

        for (int i = 0; i < 2; i++) {
            scanResults[i] = scanDetails.get(i).getScanResult();
            ephemeralNetworkConfigs[i] = WifiNetworkSelectorTestUtil
                .setupEphemeralNetwork(mWifiConfigManager, i, scanResults[i], meteredHints[i]);
        }

        WifiConfiguration candidate = mExternalScoreEvaluator.evaluateNetworks(scanDetails,
                null, null, false, true, null);

        WifiConfigurationTestUtil.assertConfigurationEqual(ephemeralNetworkConfigs[1], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                scanResults[1], candidate);
        assertEquals(meteredHints[1], candidate.meteredHint);
    }

    /**
     * Don't choose available ephemeral networks if no saved networks and untrusted networks
     * are not allowed.
     */
    @Test
    public void noEphemeralNetworkWhenUntrustedNetworksNotAllowed() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[ESS]"};
        int[] levels = {mThresholdQualifiedRssi2G + 8, mThresholdQualifiedRssi2G + 10};
        Integer[] scores = {null, 120};
        boolean[] meteredHints = {false, true};

        List<ScanDetail> scanDetails = WifiNetworkSelectorTestUtil.buildScanDetails(
                    ssids, bssids, freqs, caps, levels, mClock);
        WifiNetworkSelectorTestUtil.configureScoreCache(mScoreCache,
                    scanDetails, scores, meteredHints);

        // No saved networks.
        when(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(any(ScanDetail.class)))
                .thenReturn(null);

        ScanResult scanResult = scanDetails.get(1).getScanResult();
        WifiConfiguration ephemeralNetworkConfig = WifiNetworkSelectorTestUtil
                .setupEphemeralNetwork(mWifiConfigManager, 1, scanResult, meteredHints[1]);

        // Untursted networks not allowed.
        WifiConfiguration candidate = mExternalScoreEvaluator.evaluateNetworks(scanDetails,
                null, null, false, false, null);

        assertEquals("Expect null configuration", null, candidate);
    }


    /**
     * Choose externally scored saved network.
     */
    @Test
    public void chooseSavedNetworkWithExternalScore() {
        String[] ssids = {"\"test1\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3"};
        int[] freqs = {5200};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]"};
        int[] securities = {SECURITY_PSK};
        int[] levels = {mThresholdQualifiedRssi5G + 8};
        Integer[] scores = {120};
        boolean[] meteredHints = {false};


        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();
        savedConfigs[0].useExternalScores = true;

        WifiNetworkSelectorTestUtil.configureScoreCache(mScoreCache,
                    scanDetails, scores, meteredHints);

        WifiConfiguration candidate = mExternalScoreEvaluator.evaluateNetworks(scanDetails,
                null, null, false, true, null);

        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[0], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                scanDetails.get(0).getScanResult(), candidate);
    }

    /**
     * Choose externally scored saved network with higher score.
     */
    @Test
    public void chooseSavedNetworkWithHigherExternalScore() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};
        int[] levels = {mThresholdQualifiedRssi2G + 8, mThresholdQualifiedRssi2G + 8};
        Integer[] scores = {100, 120};
        boolean[] meteredHints = {false, false};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();
        savedConfigs[0].useExternalScores = savedConfigs[1].useExternalScores = true;

        WifiNetworkSelectorTestUtil.configureScoreCache(mScoreCache,
                    scanDetails, scores, meteredHints);

        WifiConfiguration candidate = mExternalScoreEvaluator.evaluateNetworks(scanDetails,
                null, null, false, true, null);

        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[1], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                scanDetails.get(1).getScanResult(), candidate);
    }

    /**
     * Prefer externally scored saved network over untrusted network when they have
     * the same score.
     */
    @Test
    public void chooseExternallyScoredSavedNetworkOverUntrustedNetworksWithSameScore() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[ESS]"};
        int[] securities = {SECURITY_PSK, SECURITY_NONE};
        int[] levels = {mThresholdQualifiedRssi2G + 8, mThresholdQualifiedRssi2G + 8};
        Integer[] scores = {120, 120};
        boolean[] meteredHints = {false, true};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();
        savedConfigs[0].useExternalScores = true;

        WifiNetworkSelectorTestUtil.configureScoreCache(mScoreCache,
                    scanDetails, scores, meteredHints);

        WifiConfiguration candidate = mExternalScoreEvaluator.evaluateNetworks(scanDetails,
                null, null, false, true, null);

        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[0], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                scanDetails.get(0).getScanResult(), candidate);
    }

    /**
     * Choose untrusted network when it has higher score than the externally scored
     * saved network.
     */
    @Test
    public void chooseUntrustedNetworkWithHigherScoreThanExternallyScoredSavedNetwork() {
        // Saved network.
        String[] savedSsids = {"\"test1\""};
        String[] savedBssids = {"6c:f3:7f:ae:8c:f3"};
        int[] savedFreqs = {2470};
        String[] savedCaps = {"[WPA2-EAP-CCMP][ESS]"};
        int[] savedSecurities = {SECURITY_PSK};
        int[] savedLevels = {mThresholdQualifiedRssi2G + 8};
        // Ephemeral network.
        String[] ephemeralSsids = {"\"test2\""};
        String[] ephemeralBssids = {"6c:f3:7f:ae:8c:f4"};
        int[] ephemeralFreqs = {2437};
        String[] ephemeralCaps = {"[ESS]"};
        int[] ephemeralLevels = {mThresholdQualifiedRssi2G + 8};
        // Ephemeral network has higher score than the saved network.
        Integer[] scores = {100, 120};
        boolean[] meteredHints = {false, true};

        // Set up the saved network.
        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(savedSsids,
                    savedBssids, savedFreqs, savedCaps, savedLevels, savedSecurities,
                    mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();
        savedConfigs[0].useExternalScores = true;

        // Set up the ephemeral network.
        scanDetails.addAll(WifiNetworkSelectorTestUtil.buildScanDetails(
                    ephemeralSsids, ephemeralBssids, ephemeralFreqs,
                    ephemeralCaps, ephemeralLevels, mClock));
        ScanResult ephemeralScanResult = scanDetails.get(1).getScanResult();
        WifiConfiguration ephemeralNetworkConfig = WifiNetworkSelectorTestUtil
                   .setupEphemeralNetwork(mWifiConfigManager, 1, ephemeralScanResult,
                                        meteredHints[1]);

        // Set up score cache for both the saved network and the ephemeral network.
        WifiNetworkSelectorTestUtil.configureScoreCache(mScoreCache,
                    scanDetails, scores, meteredHints);

        WifiConfiguration candidate = mExternalScoreEvaluator.evaluateNetworks(scanDetails,
                null, null, false, true, null);

        WifiConfigurationTestUtil.assertConfigurationEqual(ephemeralNetworkConfig, candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                ephemeralScanResult, candidate);
    }

    /**
     * Prefer externally scored saved network over untrusted network when they have
     * the same score.
     */
    @Test
    public void nullScoredNetworks() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[ESS]"};
        int[] securities = {SECURITY_PSK, SECURITY_NONE};
        int[] levels = {mThresholdQualifiedRssi2G + 8, mThresholdQualifiedRssi2G + 8};
        Integer[] scores = {null, null};
        boolean[] meteredHints = {false, true};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();
        savedConfigs[0].useExternalScores = true;

        WifiNetworkSelectorTestUtil.configureScoreCache(mScoreCache,
                    scanDetails, scores, meteredHints);

        WifiConfiguration candidate = mExternalScoreEvaluator.evaluateNetworks(scanDetails,
                null, null, false, true, null);

        assertEquals("Expect null configuration", null, candidate);
    }

    /**
     * Between two ephemeral networks with the same RSSI, choose
     * the currently connected one.
     */
    @Test
    public void chooseActiveEphemeralNetwork() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[ESS]", "[ESS]"};
        int[] levels = {mThresholdQualifiedRssi2G + 28, mThresholdQualifiedRssi2G + 28};
        boolean[] meteredHints = {true, true};
        ScanResult[] scanResults = new ScanResult[2];
        WifiConfiguration[] ephemeralNetworkConfigs = new WifiConfiguration[2];

        List<ScanDetail> scanDetails = WifiNetworkSelectorTestUtil.buildScanDetails(
                    ssids, bssids, freqs, caps, levels, mClock);
        WifiNetworkSelectorTestUtil.configureScoreCache(mScoreCache,
                    scanDetails, null, meteredHints);

        // No saved networks.
        when(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(any(ScanDetail.class)))
                .thenReturn(null);

        for (int i = 0; i < 2; i++) {
            scanResults[i] = scanDetails.get(i).getScanResult();
            ephemeralNetworkConfigs[i] = WifiNetworkSelectorTestUtil
                .setupEphemeralNetwork(mWifiConfigManager, i, scanResults[i], meteredHints[i]);
        }

        WifiConfiguration candidate = mExternalScoreEvaluator.evaluateNetworks(scanDetails,
                null, bssids[1], true, true, null);

        WifiConfigurationTestUtil.assertConfigurationEqual(ephemeralNetworkConfigs[1], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                scanResults[1], candidate);
        assertEquals(meteredHints[1], candidate.meteredHint);
    }

    /**
     *  Between two externally scored saved networks with the same RSSI, choose
     *  the currently connected one.
     */
    @Test
    public void chooseActiveSavedNetwork() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};
        int[] levels = {mThresholdQualifiedRssi2G + 28, mThresholdQualifiedRssi2G + 28};
        boolean[] meteredHints = {false, false};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();
        savedConfigs[0].useExternalScores = savedConfigs[1].useExternalScores = true;

        WifiNetworkSelectorTestUtil.configureScoreCache(mScoreCache,
                    scanDetails, null, meteredHints);

        WifiConfiguration candidate = mExternalScoreEvaluator.evaluateNetworks(scanDetails,
                savedConfigs[1], bssids[1], true, true, null);

        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[1], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                scanDetails.get(1).getScanResult(), candidate);
    }
}
