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

import android.annotation.Nullable;
import android.content.Context;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.WifiKey;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.os.Process;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Pair;

import com.android.server.wifi.util.ScanResultUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * This class is the WifiNetworkSelector.NetworkEvaluator implementation for
 * externally scored networks.
 */
public class ExternalScoreEvaluator implements WifiNetworkSelector.NetworkEvaluator {
    private static final String NAME = "WifiExternalScoreEvaluator";
    private final WifiConfigManager mWifiConfigManager;
    private final Clock mClock;
    private final LocalLog mLocalLog;
    private final NetworkScoreManager mScoreManager;
    private final WifiNetworkScoreCache mScoreCache;

    ExternalScoreEvaluator(Context context, WifiConfigManager configManager, Clock clock,
                        LocalLog localLog) {
        mWifiConfigManager = configManager;
        mClock = clock;
        mLocalLog = localLog;
        mScoreManager =
                (NetworkScoreManager) context.getSystemService(Context.NETWORK_SCORE_SERVICE);
        if (mScoreManager != null) {
            mScoreCache = new WifiNetworkScoreCache(context);
            mScoreManager.registerNetworkScoreCache(NetworkKey.TYPE_WIFI, mScoreCache);
        } else {
            localLog("Couldn't get NETWORK_SCORE_SERVICE.");
            mScoreCache = null;
        }
    }

    private void localLog(String log) {
        if (mLocalLog != null) {
            mLocalLog.log(log);
        }
    }

    /**
     * Get the evaluator name.
     */
    public String getName() {
        return NAME;
    }

    private void updateNetworkScoreCache(List<ScanDetail> scanDetails) {
        ArrayList<NetworkKey> unscoredNetworks = new ArrayList<NetworkKey>();

        for (ScanDetail scanDetail : scanDetails) {
            ScanResult scanResult = scanDetail.getScanResult();

            // Is there a score for this network? If not, request a score.
            if (mScoreCache != null && !mScoreCache.isScoredNetwork(scanResult)) {
                WifiKey wifiKey;

                try {
                    wifiKey = new WifiKey("\"" + scanResult.SSID + "\"", scanResult.BSSID);
                    NetworkKey ntwkKey = new NetworkKey(wifiKey);
                    unscoredNetworks.add(ntwkKey);
                } catch (IllegalArgumentException e) {
                    localLog("Invalid SSID=" + scanResult.SSID + " BSSID=" + scanResult.BSSID
                            + " for network score. Skip.");
                }
            }
        }

        // Kick the score manager if there is any unscored network.
        if (mScoreManager != null && unscoredNetworks.size() != 0) {
            NetworkKey[] unscoredNetworkKeys =
                    unscoredNetworks.toArray(new NetworkKey[unscoredNetworks.size()]);
            mScoreManager.requestScores(unscoredNetworkKeys);
        }
    }

    /**
     * Update the evaluator.
     */
    public void update(List<ScanDetail> scanDetails) {
        updateNetworkScoreCache(scanDetails);
    }

    private boolean isPotentialEphemeralNetwork(List<WifiConfiguration> associatedConfigurations) {
        if (associatedConfigurations == null) {
            return true;
        } else if (associatedConfigurations.size() == 1) {
            // If there is more than one associated networks, it must be a passpoint network.
            // Hence it is not a ephemeral network.
            WifiConfiguration network = associatedConfigurations.get(0);
            if (network.ephemeral) {
                return true;
            }
        }
        return false;
    }

    private WifiConfiguration getPotentialEphemeralNetworkConfiguration(
            List<WifiConfiguration> associatedConfigurations) {
        if (associatedConfigurations == null) {
            return null;
        } else {
            WifiConfiguration network = associatedConfigurations.get(0);
            return network;
        }
    }

    /**
     * Returns the available external network score or null if no score is available.
     *
     * @param scanResult The scan result of the network to score.
     * @param scoreCache Wifi network score cache.
     * @param active Flag which indicates whether this is the currently connected network.
     * @return A valid external score if one is available or NULL.
     */
    @Nullable
    Integer getNetworkScore(ScanResult scanResult, WifiNetworkScoreCache scoreCache,
                boolean active) {
        if (scoreCache != null && scoreCache.isScoredNetwork(scanResult)) {
            int score = scoreCache.getNetworkScore(scanResult, active);
            localLog(WifiNetworkSelector.toScanId(scanResult) + " has score: " + score
                    + " active network: " + active);
            return score;
        }
        return null;
    }

    /**
     * Returns the best candidate network according to the given ExternalScoreEvaluator.
     */
    @Nullable
    WifiConfiguration getExternalScoreCandidate(ExternalScoreTracker scoreTracker,
                WifiNetworkScoreCache scoreCache) {
        int candidateNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
        switch (scoreTracker.getBestCandidateType()) {
            case ExternalScoreTracker.EXTERNAL_SCORED_UNTRUSTED_NETWORK:
                ScanResult untrustedScanResultCandidate =
                        scoreTracker.getScanResultCandidate();
                WifiConfiguration unTrustedNetworkCandidate =
                        ScanResultUtil.createNetworkFromScanResult(untrustedScanResultCandidate);

                // Mark this config as ephemeral so it isn't persisted.
                unTrustedNetworkCandidate.ephemeral = true;
                if (scoreCache != null) {
                    unTrustedNetworkCandidate.meteredHint =
                            scoreCache.getMeteredHint(untrustedScanResultCandidate);
                }
                NetworkUpdateResult result =
                        mWifiConfigManager.addOrUpdateNetwork(unTrustedNetworkCandidate,
                                Process.WIFI_UID);
                if (!result.isSuccess()) {
                    localLog("Failed to add ephemeral network");
                    break;
                }
                candidateNetworkId = result.getNetworkId();
                mWifiConfigManager.setNetworkCandidateScanResult(candidateNetworkId,
                        untrustedScanResultCandidate, 0);
                localLog(String.format("new ephemeral candidate %s network ID:%d, "
                                + "meteredHint=%b",
                        WifiNetworkSelector.toScanId(untrustedScanResultCandidate),
                        candidateNetworkId,
                        unTrustedNetworkCandidate.meteredHint));
                break;

            case ExternalScoreTracker.EXTERNAL_SCORED_SAVED_NETWORK:
                ScanResult scanResultCandidate = scoreTracker.getScanResultCandidate();
                candidateNetworkId = scoreTracker.getSavedConfig().networkId;
                mWifiConfigManager.setNetworkCandidateScanResult(candidateNetworkId,
                        scanResultCandidate, 0);
                localLog(String.format("new saved network candidate %s network ID:%d",
                        WifiNetworkSelector.toScanId(scanResultCandidate),
                        candidateNetworkId));
                break;

            case ExternalScoreTracker.EXTERNAL_SCORED_NONE:
                localLog("did not see any good candidates.");
                break;

            default:
                localLog("Unhandled case. No candidate selected.");
                break;
        }
        return mWifiConfigManager.getConfiguredNetwork(candidateNetworkId);
    }

    /**
     * Evaluate all the networks from the scan results and return
     * the WifiConfiguration of the network chosen for connection.
     *
     * @return configuration of the chosen network;
     *         null if no network in this category is available.
     */
    public WifiConfiguration evaluateNetworks(List<ScanDetail> scanDetails,
                    WifiConfiguration currentNetwork, String currentBssid, boolean connected,
                    boolean untrustedNetworkAllowed,
                    List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks) {
        if (mScoreCache == null) {
            localLog("has no network score cache.");
            return null;
        }

        final ExternalScoreTracker externalScoreTracker = new ExternalScoreTracker(mLocalLog);
        ArrayList<NetworkKey> unscoredNetworks = new ArrayList<>();

        for (ScanDetail scanDetail : scanDetails) {
            ScanResult scanResult = scanDetail.getScanResult();

            // One ScanResult can be associated with more than one networks, hence we calculate all
            // the scores and use the highest one as the ScanResult's score.
            // TODO(b/31065385): WifiConfigManager does not support passpoint networks currently.
            // So this list has just one entry always.
            List<WifiConfiguration> associatedConfigs = null;
            WifiConfiguration associatedConfig =
                    mWifiConfigManager.getSavedNetworkForScanDetailAndCache(scanDetail);
            if (associatedConfig != null) {
                associatedConfigs =
                    new ArrayList<>(Arrays.asList(associatedConfig));
            }

            if (isPotentialEphemeralNetwork(associatedConfigs)) {
                if (untrustedNetworkAllowed) {
                    if (!mWifiConfigManager.wasEphemeralNetworkDeleted(
                                ScanResultUtil.createQuotedSSID(scanResult.SSID))) {
                        // Ephemeral network has either one WifiConfiguration or none yet.
                        // Checking BSSID is sufficient to determine whether this is the
                        // currently connected network.
                        boolean active = TextUtils.equals(currentBssid, scanResult.BSSID);
                        Integer score = getNetworkScore(scanResult, mScoreCache, active);
                        externalScoreTracker.trackUntrustedCandidate(score, scanResult);
                        if (connectableNetworks != null) {
                            connectableNetworks.add(Pair.create(scanDetail,
                                    getPotentialEphemeralNetworkConfiguration(associatedConfigs)));
                        }
                    }
                }
                continue;
            }

            for (WifiConfiguration network : associatedConfigs) {
                WifiConfiguration.NetworkSelectionStatus status =
                        network.getNetworkSelectionStatus();
                status.setSeenInLastQualifiedNetworkSelection(true);
                if (!status.isNetworkEnabled()) {
                    continue;
                } else if (network.BSSID != null &&  !network.BSSID.equals("any")
                        && !network.BSSID.equals(scanResult.BSSID)) {
                    // App has specified the only BSSID to connect for this
                    // configuration. So only the matching ScanResult can be a candidate.
                    localLog("Network " + WifiNetworkSelector.toNetworkString(network)
                            + " has specified BSSID " + network.BSSID + ". Skip "
                            + scanResult.BSSID);
                    continue;
                }

                // Saved network wth an external score.
                if (network.useExternalScores) {
                    localLog("Network " + WifiNetworkSelector.toNetworkString(network)
                            + " uses external score");
                    boolean active = currentNetwork != null && currentNetwork == network
                                && TextUtils.equals(currentBssid, scanResult.BSSID);
                    Integer score = getNetworkScore(scanResult, mScoreCache, active);
                    externalScoreTracker.trackSavedCandidate(score, network, scanResult);
                    if (connectableNetworks != null) {
                        connectableNetworks.add(Pair.create(scanDetail, network));
                    }
                }
            }
        }

        WifiConfiguration candidate = getExternalScoreCandidate(externalScoreTracker, mScoreCache);

        if (candidate != null
                && candidate.getNetworkSelectionStatus().getCandidate() != null) {
            return candidate;
        } else {
            return null;
        }
    }

    /**
     * Used to track the network with the highest score.
     */
    static class ExternalScoreTracker {
        public static final int EXTERNAL_SCORED_NONE = 0;
        public static final int EXTERNAL_SCORED_SAVED_NETWORK = 1;
        public static final int EXTERNAL_SCORED_UNTRUSTED_NETWORK = 2;

        private int mBestCandidateType = EXTERNAL_SCORED_NONE;
        private int mHighScore = WifiNetworkScoreCache.INVALID_NETWORK_SCORE;
        private WifiConfiguration mSavedConfig;
        private ScanResult mScanResultCandidate;
        private final LocalLog mLocalLog;

        ExternalScoreTracker(LocalLog localLog) {
            mLocalLog = localLog;
        }

        // Determines whether or not the given scan result is the best one its seen so far.
        void trackUntrustedCandidate(@Nullable Integer score, ScanResult scanResult) {
            if (score != null && score > mHighScore) {
                mHighScore = score;
                mScanResultCandidate = scanResult;
                mBestCandidateType = EXTERNAL_SCORED_UNTRUSTED_NETWORK;
                localLog(WifiNetworkSelector.toScanId(scanResult)
                        + " becomes the new untrusted candidate.");
            }
        }

        // Determines whether or not the given saved network is the best one its seen so far.
        void trackSavedCandidate(@Nullable Integer score, WifiConfiguration config,
                ScanResult scanResult) {
            // Always take the highest score. If there's a tie and an untrusted network is currently
            // the best then pick the saved network.
            if (score != null
                    && (score > mHighScore
                        || (mBestCandidateType == EXTERNAL_SCORED_UNTRUSTED_NETWORK
                            && score == mHighScore))) {
                mHighScore = score;
                mSavedConfig = config;
                mScanResultCandidate = scanResult;
                mBestCandidateType = EXTERNAL_SCORED_SAVED_NETWORK;
                localLog(WifiNetworkSelector.toScanId(scanResult)
                        + " becomes the new externally scored saved network candidate.");
            }
        }

        int getBestCandidateType() {
            return mBestCandidateType;
        }

        int getHighScore() {
            return mHighScore;
        }

        public ScanResult getScanResultCandidate() {
            return mScanResultCandidate;
        }

        WifiConfiguration getSavedConfig() {
            return mSavedConfig;
        }

        private void localLog(String log) {
            if (mLocalLog != null) {
                mLocalLog.log(log);
            }
        }
    }
}
