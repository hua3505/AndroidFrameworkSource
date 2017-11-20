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

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.util.LocalLog;
import android.util.Pair;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * This class is the WifiNetworkSelector.NetworkEvaluator implementation for
 * saved networks.
 */
public class SavedNetworkEvaluator implements WifiNetworkSelector.NetworkEvaluator {
    private static final String NAME = "WifiSavedNetworkEvaluator";
    private final WifiConfigManager mWifiConfigManager;
    private final Clock mClock;
    private final LocalLog mLocalLog;
    private final int mRssiScoreSlope;
    private final int mRssiScoreOffset;
    private final int mSameBssidAward;
    private final int mSameNetworkAward;
    private final int mBand5GHzAward;
    private final int mLastSelectionAward;
    private final int mPasspointSecurityAward;
    private final int mSecurityAward;
    private final int mNoInternetPenalty;
    private final int mThresholdSaturatedRssi24;

    SavedNetworkEvaluator(Context context, WifiConfigManager configManager,
                        Clock clock, LocalLog localLog) {
        mWifiConfigManager = configManager;
        mClock = clock;
        mLocalLog = localLog;

        mRssiScoreSlope = context.getResources().getInteger(
                R.integer.config_wifi_framework_RSSI_SCORE_SLOPE);
        mRssiScoreOffset = context.getResources().getInteger(
                R.integer.config_wifi_framework_RSSI_SCORE_OFFSET);
        mSameBssidAward = context.getResources().getInteger(
                R.integer.config_wifi_framework_SAME_BSSID_AWARD);
        mSameNetworkAward = context.getResources().getInteger(
                R.integer.config_wifi_framework_current_network_boost);
        mLastSelectionAward = context.getResources().getInteger(
                R.integer.config_wifi_framework_LAST_SELECTION_AWARD);
        mPasspointSecurityAward = context.getResources().getInteger(
                R.integer.config_wifi_framework_PASSPOINT_SECURITY_AWARD);
        mSecurityAward = context.getResources().getInteger(
                R.integer.config_wifi_framework_SECURITY_AWARD);
        mBand5GHzAward = context.getResources().getInteger(
                R.integer.config_wifi_framework_5GHz_preference_boost_factor);
        mThresholdSaturatedRssi24 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_24GHz);
        mNoInternetPenalty = (mThresholdSaturatedRssi24 + mRssiScoreOffset)
                * mRssiScoreSlope + mBand5GHzAward + mSameNetworkAward
                + mSameBssidAward + mSecurityAward;
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

    /**
     * Update all the saved networks' selection status
     */
    private void updateSavedNetworkSelectionStatus() {
        List<WifiConfiguration> savedNetworks = mWifiConfigManager.getSavedNetworks();
        if (savedNetworks.size() == 0) {
            localLog("No saved networks.");
            return;
        }

        StringBuffer sbuf = new StringBuffer("Saved Networks List: \n");
        for (WifiConfiguration network : savedNetworks) {
            WifiConfiguration.NetworkSelectionStatus status =
                    network.getNetworkSelectionStatus();

            // If a configuration is temporarily disabled, re-enable it before trying
            // to connect to it.
            mWifiConfigManager.tryEnableNetwork(network.networkId);

            //TODO(b/30928589): Enable "permanently" disabled networks if we are in DISCONNECTED
            // state.

            // Clear the cached candidate, score and seen.
            mWifiConfigManager.clearNetworkCandidateScanResult(network.networkId);

            sbuf.append(" ").append(WifiNetworkSelector.toNetworkString(network)).append(" ")
                    .append(" User Preferred BSSID: ").append(network.BSSID)
                    .append(" FQDN: ").append(network.FQDN).append(" ")
                    .append(status.getNetworkStatusString()).append(" Disable account: ");
            for (int index = WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLE;
                    index < WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_DISABLED_MAX;
                    index++) {
                sbuf.append(status.getDisableReasonCounter(index)).append(" ");
            }
            sbuf.append("Connect Choice: ").append(status.getConnectChoice())
                .append(" set time: ").append(status.getConnectChoiceTimestamp())
                .append("\n");
        }
        localLog(sbuf.toString());
    }

    /**
     * Update the evaluator.
     */
    public void update(List<ScanDetail> scanDetails) {
        updateSavedNetworkSelectionStatus();
    }

    private int calculateBssidScore(ScanResult scanResult, WifiConfiguration network,
                        WifiConfiguration currentNetwork, String currentBssid,
                        StringBuffer sbuf) {
        int score = 0;

        sbuf.append("[ ").append(scanResult).append("] ");
        // Calculate the RSSI score.
        int rssi = scanResult.level <= mThresholdSaturatedRssi24
                ? scanResult.level : mThresholdSaturatedRssi24;
        score += (rssi + mRssiScoreOffset) * mRssiScoreSlope;
        sbuf.append(" RSSI score: ").append(score).append(",");

        // 5GHz band bonus.
        if (scanResult.is5GHz()) {
            score += mBand5GHzAward;
            sbuf.append(" 5GHz bonus: ").append(mBand5GHzAward)
                .append(",");
        }

        // Last user selection award.
        int lastUserSelectedNetworkId = mWifiConfigManager.getLastSelectedNetwork();
        if (lastUserSelectedNetworkId != WifiConfiguration.INVALID_NETWORK_ID
                && lastUserSelectedNetworkId == network.networkId) {
            long timeDifference = mClock.getElapsedSinceBootMillis()
                    - mWifiConfigManager.getLastSelectedTimeStamp();
            if (timeDifference > 0) {
                int bonus = mLastSelectionAward - (int) (timeDifference / 1000 / 60);
                score += bonus > 0 ? bonus : 0;
                sbuf.append(" User selected it last time ").append(timeDifference / 1000 / 60)
                        .append(" minutes ago, bonus: ").append(bonus).append(",");
            }
        }

        // Same network award.
        if (currentNetwork != null
                && (network == currentNetwork || network.isLinked(currentNetwork))) {
            score += mSameNetworkAward;
            sbuf.append(" Same network bonus as the current one bonus: ")
                    .append(mSameNetworkAward).append(",");
        }

        // Same BSSID award.
        if (currentBssid != null && currentBssid.equals(scanResult.BSSID)) {
            score += mSameBssidAward;
            sbuf.append(" Same BSSID as the current one bonus: ").append(mSameBssidAward)
                .append(",");
        }

        // Security award.
        if (network.isPasspoint()) {
            score += mPasspointSecurityAward;
            sbuf.append(" Passpoint bonus: ").append(mPasspointSecurityAward).append(",");
        } else if (!WifiConfigurationUtil.isConfigForOpenNetwork(network)) {
            score += mSecurityAward;
            sbuf.append(" Secure network bonus: ").append(mSecurityAward).append(",");
        }

        // No internet penalty.
        if (network.numNoInternetAccessReports > 0 && !network.validatedInternetAccess) {
            score -= mNoInternetPenalty;
            sbuf.append(" No internet penalty: -").append(mNoInternetPenalty).append(",");
        }

        sbuf.append(" ## Total score: ").append(score).append("\n");

        return score;
    }

    private WifiConfiguration adjustCandidateWithUserSelection(WifiConfiguration candidate,
                        ScanResult scanResultCandidate) {
        WifiConfiguration tempConfig = candidate;

        while (tempConfig.getNetworkSelectionStatus().getConnectChoice() != null) {
            String key = tempConfig.getNetworkSelectionStatus().getConnectChoice();
            tempConfig = mWifiConfigManager.getConfiguredNetwork(key);

            if (tempConfig != null) {
                WifiConfiguration.NetworkSelectionStatus tempStatus =
                        tempConfig.getNetworkSelectionStatus();
                if (tempStatus.getCandidate() != null && tempStatus.isNetworkEnabled()) {
                    scanResultCandidate = tempStatus.getCandidate();
                    candidate = tempConfig;
                }
            } else {
                localLog("Connect choice: " + key + " has no corresponding saved config.");
                break;
            }
        }
        localLog("After user selection adjustment, the final candidate is:"
                + WifiNetworkSelector.toNetworkString(candidate) + " : "
                + scanResultCandidate.BSSID);
        return candidate;
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
        int highestScore = Integer.MIN_VALUE;
        ScanResult scanResultCandidate = null;
        WifiConfiguration candidate = null;
        StringBuffer scoreHistory = new StringBuffer();

        for (ScanDetail scanDetail : scanDetails) {
            ScanResult scanResult = scanDetail.getScanResult();
            int highestScoreOfScanResult = Integer.MIN_VALUE;
            int score;
            int candidateIdOfScanResult = WifiConfiguration.INVALID_NETWORK_ID;

            // One ScanResult can be associated with more than one networks, hence we calculate all
            // the scores and use the highest one as the ScanResult's score.
            // TODO(b/31065385): WifiConfigManager does not support passpoint networks currently.
            // So this list has just one entry always.
            List<WifiConfiguration> associatedConfigurations = null;
            WifiConfiguration associatedConfiguration =
                    mWifiConfigManager.getSavedNetworkForScanDetailAndCache(scanDetail);

            if (associatedConfiguration == null) {
                continue;
            } else {
                associatedConfigurations =
                    new ArrayList<>(Arrays.asList(associatedConfiguration));
            }

            for (WifiConfiguration network : associatedConfigurations) {
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

                // If the network is marked to use external scores, leave it to the
                // external score evaluator to handle it.
                if (network.useExternalScores) {
                    localLog("Network " + WifiNetworkSelector.toNetworkString(network)
                            + " has external score.");
                    continue;
                }

                score = calculateBssidScore(scanResult, network, currentNetwork, currentBssid,
                                               scoreHistory);

                if (score > highestScoreOfScanResult) {
                    highestScoreOfScanResult = score;
                    candidateIdOfScanResult = network.networkId;
                }

                if (score > status.getCandidateScore() || (score == status.getCandidateScore()
                          && status.getCandidate() != null
                          && scanResult.level > status.getCandidate().level)) {
                    mWifiConfigManager.setNetworkCandidateScanResult(
                            candidateIdOfScanResult, scanResult, score);
                }
            }

            if (connectableNetworks != null) {
                connectableNetworks.add(Pair.create(scanDetail,
                        mWifiConfigManager.getConfiguredNetwork(candidateIdOfScanResult)));
            }

            if (highestScoreOfScanResult > highestScore
                    || (highestScoreOfScanResult == highestScore
                    && scanResultCandidate != null
                    && scanResult.level > scanResultCandidate.level)) {
                highestScore = highestScoreOfScanResult;
                scanResultCandidate = scanResult;
                mWifiConfigManager.setNetworkCandidateScanResult(
                        candidateIdOfScanResult, scanResultCandidate, highestScore);
                // Reload the network config with the updated info.
                candidate = mWifiConfigManager.getConfiguredNetwork(candidateIdOfScanResult);
            }
        }

        if (scoreHistory.length() > 0) {
            localLog("\n" + scoreHistory.toString());
        }

        if (scanResultCandidate != null) {
            return adjustCandidateWithUserSelection(candidate, scanResultCandidate);
        } else {
            localLog("did not see any good candidates.");
            return null;
        }
    }
}
