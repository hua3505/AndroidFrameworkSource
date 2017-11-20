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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.net.NetworkAgent;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;

import com.android.internal.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;

/**
 * Unit tests for {@link com.android.server.wifi.WifiScoreReport}.
 */
public class WifiScoreReportTest {

    private static final int CELLULAR_THRESHOLD_SCORE = 50;

    WifiConfiguration mWifiConfiguration;
    WifiScoreReport mWifiScoreReport;
    ScanDetailCache mScanDetailCache;
    @Mock Context mContext;
    @Mock NetworkAgent mNetworkAgent;
    @Mock Resources mResources;
    @Mock WifiConfigManager mWifiConfigManager;
    @Mock WifiInfo mWifiInfo;
    @Mock WifiMetrics mWifiMetrics;

    /**
     * Sets up resource values for testing
     *
     * See frameworks/base/core/res/res/values/config.xml
     */
    private void setUpResources(Resources resources) {
        when(resources.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_5GHz))
            .thenReturn(-82);
        when(resources.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz))
            .thenReturn(-70);
        when(resources.getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_5GHz))
            .thenReturn(-57);
        when(resources.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_24GHz))
            .thenReturn(-85);
        when(resources.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz))
            .thenReturn(-73);
        when(resources.getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_24GHz))
            .thenReturn(-60);
        when(resources.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_link_speed_24))
            .thenReturn(6); // Mbps
        when(resources.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_link_speed_5))
            .thenReturn(12);
        when(resources.getInteger(
                R.integer.config_wifi_framework_wifi_score_good_link_speed_24))
            .thenReturn(24);
        when(resources.getInteger(
                R.integer.config_wifi_framework_wifi_score_good_link_speed_5))
            .thenReturn(36);
        when(resources.getBoolean(
                R.bool.config_wifi_framework_cellular_handover_enable_user_triggered_adjustment))
            .thenReturn(true);
    }

    /**
     *  Pulls the final score from a report string
     *
     *  The report string is essentially free-form, intended for debugging,
     *  but we would like to know that the score is in there somewhere.
     *
     *  Currently, the score is found as the last value in a comma-separated
     *  list enclosed in square brackets.
     */
    private int fishScoreFromReportString(String report) {
        int score = 0;
        if (report != null) {
            String[] f = report.split("]");
            assertTrue(f.length > 1);
            f = f[f.length - 2].split(",");
            score = Integer.parseInt(f[f.length - 1]);
            // clipping happens after stashing in report string, so do that here.
            score = Integer.min(score, NetworkAgent.WIFI_BASE_SCORE);
        }
        return score;
    }

    /**
     * Sets up for unit test
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        setUpResources(mResources);
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "nooooooooooo";
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        config.hiddenSSID = false;
        when(mWifiConfigManager.getSavedNetworks()).thenReturn(Arrays.asList(config));
        when(mWifiConfigManager.getConfiguredNetwork(anyInt())).thenReturn(config);
        mWifiConfiguration = config;
        int maxSize = 10;
        int trimSize = 5;
        mScanDetailCache = new ScanDetailCache(config, maxSize, trimSize);
        // TODO: populate the cache, but probably in the test cases, not here.
        when(mWifiConfigManager.getScanDetailCacheForNetwork(anyInt()))
                .thenReturn(mScanDetailCache);
        when(mContext.getResources()).thenReturn(mResources);
        mWifiScoreReport = new WifiScoreReport(mContext, mWifiConfigManager);
    }

    /**
     * Cleans up after test
     */
    @After
    public void tearDown() throws Exception {
        mResources = null;
        mWifiScoreReport = null;
        mWifiConfigManager = null;
        mWifiMetrics = null;
    }

    /**
     * Test for score reporting
     *
     * The score should be sent to both the NetworkAgent and the
     * WifiMetrics
     */
    @Test
    public void calculateAndReportScoreSucceeds() throws Exception {
        when(mWifiInfo.getRssi()).thenReturn(-77);
        int aggressiveHandover = 0;
        mWifiScoreReport.calculateAndReportScore(mWifiInfo,
                mNetworkAgent, aggressiveHandover, mWifiMetrics);
        verify(mNetworkAgent).sendNetworkScore(anyInt());
        verify(mWifiMetrics).incrementWifiScoreCount(anyInt());
    }

    /**
     * Test for operation with null NetworkAgent
     *
     * Expect to not die, and to calculate the score and report to metrics.
     */
    @Test
    public void networkAgentMayBeNull() throws Exception {
        when(mWifiInfo.getRssi()).thenReturn(-33);
        mWifiScoreReport.enableVerboseLogging(true);
        mWifiScoreReport.calculateAndReportScore(mWifiInfo, null, 0, mWifiMetrics);
        verify(mWifiMetrics).incrementWifiScoreCount(anyInt());
    }

    /**
     * Test operation of saved last report
     *
     * One score is calculated
     * Expect: last report is not valid before any score is calculated
     * Expect: last report is valid after a score is calculated
     * Expect: the score in the last report string matches the reported score
     * Expect: reset makes the last report invalid again
     */
    @Test
    public void makeSureLastReportWorks() throws Exception {
        when(mWifiInfo.getRssi()).thenReturn(-33);
        assertFalse(mWifiScoreReport.isLastReportValid());
        mWifiScoreReport.enableVerboseLogging(true);
        mWifiScoreReport.calculateAndReportScore(mWifiInfo, mNetworkAgent, 0, mWifiMetrics);
        assertTrue(mWifiScoreReport.isLastReportValid());
        String report = mWifiScoreReport.getLastReport();
        int score = fishScoreFromReportString(report);
        verify(mWifiMetrics).incrementWifiScoreCount(score);
        verify(mNetworkAgent).sendNetworkScore(score);
        mWifiScoreReport.reset();
        assertFalse(mWifiScoreReport.isLastReportValid());
        assertTrue(mWifiScoreReport.getLastReport().equals(""));
    }

    /**
     * Test bad linkspeed counter
     *
     * Expect badLinkspeed count to be incemented based on bad link speed.
     * Expect counter to be pinned at a maximum value.
     * Expect counter to be cleared by reset.
     */
    @Test
    public void badLinkspeedCounter() throws Exception {
        when(mWifiInfo.getRssi()).thenReturn(-123);
        when(mWifiInfo.getLinkSpeed()).thenReturn(1);
        when(mWifiInfo.is24GHz()).thenReturn(true);
        assertFalse(mWifiScoreReport.isLastReportValid());
        mWifiScoreReport.enableVerboseLogging(true);
        assertEquals(0, mWifiScoreReport.getLastBadLinkspeedcount());
        mWifiScoreReport.calculateAndReportScore(mWifiInfo, null, 0, mWifiMetrics);
        assertEquals(1, mWifiScoreReport.getLastBadLinkspeedcount());
        mWifiScoreReport.calculateAndReportScore(mWifiInfo, null, 0, mWifiMetrics);
        assertEquals(2, mWifiScoreReport.getLastBadLinkspeedcount());
        mWifiScoreReport.calculateAndReportScore(mWifiInfo, null, 0, mWifiMetrics);
        assertEquals(3, mWifiScoreReport.getLastBadLinkspeedcount());
        mWifiScoreReport.calculateAndReportScore(mWifiInfo, null, 1, mWifiMetrics);
        mWifiScoreReport.calculateAndReportScore(mWifiInfo, null, 0, mWifiMetrics);
        mWifiScoreReport.calculateAndReportScore(mWifiInfo, null, 1, mWifiMetrics);
        mWifiScoreReport.calculateAndReportScore(mWifiInfo, null, 0, mWifiMetrics);
        mWifiScoreReport.calculateAndReportScore(mWifiInfo, null, 0, mWifiMetrics);
        mWifiScoreReport.calculateAndReportScore(mWifiInfo, null, 0, mWifiMetrics);
        assertEquals(6, mWifiScoreReport.getLastBadLinkspeedcount()); // pinned at limit
        verify(mWifiMetrics, times(9)).incrementWifiScoreCount(anyInt());
        verify(mWifiInfo, atLeast(9)).is24GHz();
        assertTrue(mWifiScoreReport.isLastReportValid());
        mWifiScoreReport.reset();
        assertEquals(0, mWifiScoreReport.getLastBadLinkspeedcount());
    }

    /**
     * Exercise the rates
     *
     * If the rate of successful transmissions stays large enough for long enough,
     * the user-disconnect penalty should fade.
     *
     * The setup has a low (not bad) RSSI, and data movement (txSuccessRate) above
     * the threshold. A large initial value of numTicksAtLowRSSI avoids having
     * to run the test for a lot of cycles.
     *
     * Expect a score above threshold.
     * Expect numTicksAtLowRSSI to be reset sometime during the test.
     * Expect numUserTriggeredWifiDisableLowRSSI to decrease.
     */
    @Test
    public void allowLowRssiIfDataIsMoving() throws Exception {
        when(mWifiInfo.getRssi()).thenReturn(-80);
        when(mWifiInfo.getLinkSpeed()).thenReturn(6); // Mbps
        when(mWifiInfo.is24GHz()).thenReturn(true);
        mWifiInfo.txSuccessRate = 5.1; // Mbps
        mWifiInfo.rxSuccessRate = 5.1;
        mWifiConfiguration.numUserTriggeredWifiDisableLowRSSI = 1;
        int minNumTicksAtState = 1000; // MIN_NUM_TICKS_AT_STATE
        mWifiConfiguration.numTicksAtLowRSSI = minNumTicksAtState - 3;
        for (int i = 0; i < 10; i++) {
            mWifiScoreReport.calculateAndReportScore(mWifiInfo, mNetworkAgent, 0, mWifiMetrics);
        }
        assertTrue(mWifiScoreReport.isLastReportValid());
        int score = fishScoreFromReportString(mWifiScoreReport.getLastReport());
        assertTrue(score > CELLULAR_THRESHOLD_SCORE);
        assertTrue(mWifiConfiguration.numTicksAtLowRSSI < 10);
        assertEquals(0, mWifiConfiguration.numUserTriggeredWifiDisableLowRSSI);
    }

    /**
     * Bad RSSI without data moving should allow handoff
     *
     * The setup has a bad RSSI, and the txSuccessRate is below threshold; several
     * scoring iterations are performed.
     *
     * Expect the score to drop below the handoff threshold.
     */
    @Test
    public void giveUpOnBadRssiWhenDataIsNotMoving() throws Exception {
        when(mWifiInfo.getRssi()).thenReturn(-100);
        when(mWifiInfo.getLinkSpeed()).thenReturn(6); // Mbps
        when(mWifiInfo.is24GHz()).thenReturn(true);
        mWifiScoreReport.enableVerboseLogging(true);
        mWifiInfo.txSuccessRate = 0.1;
        mWifiInfo.rxSuccessRate = 0.1;
        for (int i = 0; i < 10; i++) {
            mWifiScoreReport.calculateAndReportScore(mWifiInfo, mNetworkAgent, 0, mWifiMetrics);
            String report = mWifiScoreReport.getLastReport();
            assertTrue(report.contains(" br "));
        }
        assertTrue(mWifiScoreReport.isLastReportValid());
        int score = fishScoreFromReportString(mWifiScoreReport.getLastReport());
        assertTrue(score < CELLULAR_THRESHOLD_SCORE);
        verify(mNetworkAgent, atLeast(1)).sendNetworkScore(score);
    }
}
