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
import android.app.ActivityManager;
import android.content.Context;
import android.net.NetworkKey;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Pair;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * This class looks at all the connectivity scan results then
 * selects a network for the phone to connect or roam to.
 */
public class WifiNetworkSelector {
    private static final long INVALID_TIME_STAMP = Long.MIN_VALUE;
    // Minimum time gap between last successful network selection and a new selection
    // attempt.
    @VisibleForTesting
    public static final int MINIMUM_NETWORK_SELECTION_INTERVAL_MS = 10 * 1000;

    // Constants for BSSID blacklist.
    public static final int BSSID_BLACKLIST_THRESHOLD = 3;
    public static final int BSSID_BLACKLIST_EXPIRE_TIME_MS = 5 * 60 * 1000;

    private WifiConfigManager mWifiConfigManager;
    private Clock mClock;
    private static class BssidBlacklistStatus {
        // Number of times this BSSID has been rejected for association.
        public int counter;
        public boolean isBlacklisted;
        public long blacklistedTimeStamp = INVALID_TIME_STAMP;
    }
    private Map<String, BssidBlacklistStatus> mBssidBlacklist =
            new HashMap<>();

    private final LocalLog mLocalLog =
            new LocalLog(ActivityManager.isLowRamDeviceStatic() ? 256 : 512);
    private long mLastNetworkSelectionTimeStamp = INVALID_TIME_STAMP;
    // Buffer of filtered scan results (Scan results considered by network selection) & associated
    // WifiConfiguration (if any).
    private volatile List<Pair<ScanDetail, WifiConfiguration>> mConnectableNetworks =
            new ArrayList<>();
    private final int mThresholdQualifiedRssi24;
    private final int mThresholdQualifiedRssi5;
    private final int mThresholdMinimumRssi24;
    private final int mThresholdMinimumRssi5;
    private final boolean mEnableAutoJoinWhenAssociated;

    /**
     * WiFi Network Selector supports various types of networks. Each type can
     * have its evaluator to choose the best WiFi network for the device to connect
     * to. When registering a WiFi network evaluator with the WiFi Network Selector,
     * the priority of the network must be specified, and it must be a value between
     * 0 and (EVALUATOR_MIN_PIRORITY - 1) with 0 being the highest priority. Wifi
     * Network Selector iterates through the registered scorers from the highest priority
     * to the lowest till a network is selected.
     */
    public static final int EVALUATOR_MIN_PRIORITY = 6;

    /**
     * Maximum number of evaluators can be registered with Wifi Network Selector.
     */
    public static final int MAX_NUM_EVALUATORS = EVALUATOR_MIN_PRIORITY;

    /**
     * Interface for WiFi Network Evaluator
     *
     * A network scorer evaulates all the networks from the scan results and
     * recommends the best network in its category to connect or roam to.
     */
    public interface NetworkEvaluator {
        /**
         * Get the evaluator name.
         */
        String getName();

        /**
         * Update the evaluator.
         *
         * Certain evaluators have to be updated with the new scan results. For example
         * the ExternalScoreEvalutor needs to refresh its Score Cache.
         *
         * @param scanDetails    a list of scan details constructed from the scan results
         */
        void update(List<ScanDetail> scanDetails);

        /**
         * Evaluate all the networks from the scan results.
         *
         * @param scanDetails    a list of scan details constructed from the scan results
         * @param currentNetwork configuration of the current connected network
         *                       or null if disconnected
         * @param currentBssid   BSSID of the current connected network or null if
         *                       disconnected
         * @param connected      a flag to indicate if WifiStateMachine is in connected
         *                       state
         * @param untrustedNetworkAllowed a flag to indidate if untrusted networks like
         *                                ephemeral networks are allowed
         * @param connectableNetworks     a list of the ScanDetail and WifiConfiguration
         *                                pair which is used by the WifiLastResortWatchdog
         * @return configuration of the chosen network;
         *         null if no network in this category is available.
         */
        @Nullable
        WifiConfiguration evaluateNetworks(List<ScanDetail> scanDetails,
                        WifiConfiguration currentNetwork, String currentBssid,
                        boolean connected, boolean untrustedNetworkAllowed,
                        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks);
    }

    private final NetworkEvaluator[] mEvaluators = new NetworkEvaluator[MAX_NUM_EVALUATORS];

    // A helper to log debugging information in the local log buffer, which can
    // be retrieved in bugreport.
    private void localLog(String log) {
        mLocalLog.log(log);
    }

    private boolean isCurrentNetworkSufficient(WifiInfo wifiInfo) {
        WifiConfiguration network =
                            mWifiConfigManager.getConfiguredNetwork(wifiInfo.getNetworkId());

        // Currently connected?
        if (network == null) {
            localLog("No current connected network.");
            return false;
        } else {
            localLog("Current connected network: " + network.SSID
                    + " , ID: " + network.networkId);
        }

        // Ephemeral network is not qualified.
        if (network.ephemeral) {
            localLog("Current network is an ephemeral one.");
            return false;
        }

        // Open network is not qualified.
        if (WifiConfigurationUtil.isConfigForOpenNetwork(network)) {
            localLog("Current network is a open one.");
            return false;
        }

        // 2.4GHz networks is not qualified.
        if (wifiInfo.is24GHz()) {
            localLog("Current network is 2.4GHz.");
            return false;
        }

        // Is the current network's singnal strength qualified? It can only
        // be a 5GHz network if we reach here.
        int currentRssi = wifiInfo.getRssi();
        if (wifiInfo.is5GHz() && currentRssi < mThresholdQualifiedRssi5) {
            localLog("Current network band=" + (wifiInfo.is5GHz() ? "5GHz" : "2.4GHz")
                    + ", RSSI[" + currentRssi + "]-acceptable but not qualified.");
            return false;
        }

        return true;
    }

    private boolean isNetworkSelectionNeeded(List<ScanDetail> scanDetails, WifiInfo wifiInfo,
                        boolean connected, boolean disconnected) {
        if (scanDetails.size() == 0) {
            localLog("Empty connectivity scan results. Skip network selection.");
            return false;
        }

        if (connected) {
            // Is roaming allowed?
            if (!mEnableAutoJoinWhenAssociated) {
                localLog("Switching networks in connected state is not allowed."
                        + " Skip network selection.");
                return false;
            }

            // Has it been at least the minimum interval since last network selection?
            if (mLastNetworkSelectionTimeStamp != INVALID_TIME_STAMP) {
                long gap = mClock.getElapsedSinceBootMillis()
                            - mLastNetworkSelectionTimeStamp;
                if (gap < MINIMUM_NETWORK_SELECTION_INTERVAL_MS) {
                    localLog("Too short since last network selection: " + gap + " ms."
                            + " Skip network selection.");
                    return false;
                }
            }

            if (isCurrentNetworkSufficient(wifiInfo)) {
                localLog("Current connected network already sufficient. Skip network selection.");
                return false;
            } else {
                localLog("Current connected network is not sufficient.");
                return true;
            }
        } else if (disconnected) {
            return true;
        } else {
            // No network selection if WifiStateMachine is in a state other than
            // CONNECTED or DISCONNECTED.
            localLog("WifiStateMachine is in neither CONNECTED nor DISCONNECTED state."
                    + " Skip network selection.");
            return false;
        }
    }

    /**
     * Format the given ScanResult as a scan ID for logging.
     */
    public static String toScanId(@Nullable ScanResult scanResult) {
        return scanResult == null ? "NULL"
                                  : String.format("%s:%s", scanResult.SSID, scanResult.BSSID);
    }

    /**
     * Format the given WifiConfiguration as a SSID:netId string
     */
    public static String toNetworkString(WifiConfiguration network) {
        if (network == null) {
            return null;
        }

        return (network.SSID + ":" + network.networkId);
    }

    private List<ScanDetail> filterScanResults(List<ScanDetail> scanDetails, boolean isConnected,
                    String currentBssid) {
        ArrayList<NetworkKey> unscoredNetworks = new ArrayList<NetworkKey>();
        List<ScanDetail> validScanDetails = new ArrayList<ScanDetail>();
        StringBuffer noValidSsid = new StringBuffer();
        StringBuffer blacklistedBssid = new StringBuffer();
        StringBuffer lowRssi = new StringBuffer();
        boolean scanResultsHaveCurrentBssid = false;

        for (ScanDetail scanDetail : scanDetails) {
            ScanResult scanResult = scanDetail.getScanResult();

            if (TextUtils.isEmpty(scanResult.SSID)) {
                noValidSsid.append(scanResult.BSSID).append(" / ");
                continue;
            }

            // Check if the scan results contain the currently connected BSSID
            if (scanResult.BSSID.equals(currentBssid)) {
                scanResultsHaveCurrentBssid = true;
            }

            final String scanId = toScanId(scanResult);

            if (isBssidDisabled(scanResult.BSSID)) {
                blacklistedBssid.append(scanId).append(" / ");
                continue;
            }

            // Skip network with too weak signals.
            if ((scanResult.is24GHz() && scanResult.level
                    < mThresholdMinimumRssi24)
                    || (scanResult.is5GHz() && scanResult.level
                    < mThresholdMinimumRssi5)) {
                lowRssi.append(scanId).append("(")
                    .append(scanResult.is24GHz() ? "2.4GHz" : "5GHz")
                    .append(")").append(scanResult.level).append(" / ");
                continue;
            }

            validScanDetails.add(scanDetail);
        }

        // WNS listens to all single scan results. Some scan requests may not include
        // the channel of the currently connected network, so the currently connected
        // network won't show up in the scan results. We don't act on these scan results
        // to avoid aggressive network switching which might trigger disconnection.
        if (isConnected && !scanResultsHaveCurrentBssid) {
            localLog("Current connected BSSID " + currentBssid + " is not in the scan results."
                    + " Skip network selection.");
            validScanDetails.clear();
            return validScanDetails;
        }

        if (noValidSsid.length() != 0) {
            localLog("Networks filtered out due to invalid SSID: " + noValidSsid);
        }

        if (blacklistedBssid.length() != 0) {
            localLog("Networks filtered out due to blacklist: " + blacklistedBssid);
        }

        if (lowRssi.length() != 0) {
            localLog("Networks filtered out due to low signal strength: " + lowRssi);
        }

        return validScanDetails;
    }

    /**
     * @return the list of ScanDetails scored as potential candidates by the last run of
     * selectNetwork, this will be empty if Network selector determined no selection was
     * needed on last run. This includes scan details of sufficient signal strength, and
     * had an associated WifiConfiguration.
     */
    public List<Pair<ScanDetail, WifiConfiguration>> getFilteredScanDetails() {
        return mConnectableNetworks;
    }

    /**
     * This API is called when user explicitly selects a network. Currently, it is used in following
     * cases:
     * (1) User explicitly chooses to connect to a saved network.
     * (2) User saves a network after adding a new network.
     * (3) User saves a network after modifying a saved network.
     * Following actions will be triggered:
     * 1. If this network is disabled, we need re-enable it again.
     * 2. This network is favored over all the other networks visible in latest network
     *    selection procedure.
     *
     * @param netId  ID for the network chosen by the user
     * @return true -- There is change made to connection choice of any saved network.
     *         false -- There is no change made to connection choice of any saved network.
     */
    public boolean setUserConnectChoice(int netId) {
        localLog("userSelectNetwork: network ID=" + netId);
        WifiConfiguration selected = mWifiConfigManager.getConfiguredNetwork(netId);

        if (selected == null || selected.SSID == null) {
            localLog("userSelectNetwork: Invalid configuration with nid=" + netId);
            return false;
        }

        // Enable the network if it is disabled.
        if (!selected.getNetworkSelectionStatus().isNetworkEnabled()) {
            mWifiConfigManager.updateNetworkSelectionStatus(netId,
                    WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLE);
        }

        boolean change = false;
        String key = selected.configKey();
        // This is only used for setting the connect choice timestamp for debugging purposes.
        long currentTime = mClock.getWallClockMillis();
        List<WifiConfiguration> savedNetworks = mWifiConfigManager.getSavedNetworks();

        for (WifiConfiguration network : savedNetworks) {
            WifiConfiguration.NetworkSelectionStatus status = network.getNetworkSelectionStatus();
            if (network.networkId == selected.networkId) {
                if (status.getConnectChoice() != null) {
                    localLog("Remove user selection preference of " + status.getConnectChoice()
                            + " Set Time: " + status.getConnectChoiceTimestamp() + " from "
                            + network.SSID + " : " + network.networkId);
                    mWifiConfigManager.clearNetworkConnectChoice(network.networkId);
                    change = true;
                }
                continue;
            }

            if (status.getSeenInLastQualifiedNetworkSelection()
                    && (status.getConnectChoice() == null
                    || !status.getConnectChoice().equals(key))) {
                localLog("Add key: " + key + " Set Time: " + currentTime + " to "
                        + toNetworkString(network));
                mWifiConfigManager.setNetworkConnectChoice(network.networkId, key, currentTime);
                change = true;
            }
        }

        return change;
    }

    /**
     * Enable/disable a BSSID for Network Selection
     * When an association rejection event is obtained, Network Selector will disable this
     * BSSID but supplicant still can try to connect to this bssid. If supplicant connect to it
     * successfully later, this bssid can be re-enabled.
     *
     * @param bssid the bssid to be enabled / disabled
     * @param enable -- true enable a bssid if it has been disabled
     *               -- false disable a bssid
     */
    public boolean enableBssidForNetworkSelection(String bssid, boolean enable) {
        if (enable) {
            return (mBssidBlacklist.remove(bssid) != null);
        } else {
            if (bssid != null) {
                BssidBlacklistStatus status = mBssidBlacklist.get(bssid);
                if (status == null) {
                    // First time for this BSSID
                    BssidBlacklistStatus newStatus = new BssidBlacklistStatus();
                    newStatus.counter++;
                    mBssidBlacklist.put(bssid, newStatus);
                } else if (!status.isBlacklisted) {
                    status.counter++;
                    if (status.counter >= BSSID_BLACKLIST_THRESHOLD) {
                        status.isBlacklisted = true;
                        status.blacklistedTimeStamp = mClock.getElapsedSinceBootMillis();
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Update the BSSID blacklist
     *
     * Go through the BSSID blacklist and check when a BSSID was blocked. If it
     * has been blacklisted for BSSID_BLACKLIST_EXPIRE_TIME_MS, then re-enable it.
     */
    private void updateBssidBlacklist() {
        Iterator<BssidBlacklistStatus> iter = mBssidBlacklist.values().iterator();
        while (iter.hasNext()) {
            BssidBlacklistStatus status = iter.next();
            if (status != null && status.isBlacklisted) {
                if (mClock.getElapsedSinceBootMillis() - status.blacklistedTimeStamp
                            >= BSSID_BLACKLIST_EXPIRE_TIME_MS) {
                    iter.remove();
                }
            }
        }
    }

    /**
     * Check whether a bssid is disabled
     * @param bssid -- the bssid to check
     */
    private boolean isBssidDisabled(String bssid) {
        BssidBlacklistStatus status = mBssidBlacklist.get(bssid);
        return status == null ? false : status.isBlacklisted;
    }

    /**
     *
     */
    @Nullable
    public WifiConfiguration selectNetwork(List<ScanDetail> scanDetails, WifiInfo wifiInfo,
            boolean connected, boolean disconnected, boolean untrustedNetworkAllowed) {
        mConnectableNetworks.clear();
        if (scanDetails.size() == 0) {
            localLog("Empty connectivity scan result");
            return null;
        }

        WifiConfiguration currentNetwork =
                mWifiConfigManager.getConfiguredNetwork(wifiInfo.getNetworkId());

        // Always get the current BSSID from WifiInfo in case that firmware initiated
        // roaming happened.
        String currentBssid = wifiInfo.getBSSID();

        // Shall we start network selection at all?
        if (!isNetworkSelectionNeeded(scanDetails, wifiInfo, connected, disconnected)) {
            return null;
        }

        // Update the registered network evaluators.
        for (NetworkEvaluator registeredEvaluator : mEvaluators) {
            if (registeredEvaluator != null) {
                registeredEvaluator.update(scanDetails);
            }
        }

        // Check if any BSSID can be freed from the blacklist.
        updateBssidBlacklist();

        // Filter out unwanted networks.
        List<ScanDetail> filteredScanDetails = filterScanResults(scanDetails, connected,
                currentBssid);
        if (filteredScanDetails.size() == 0) {
            return null;
        }

        // Go through the registered network evaluators from the highest priority
        // one to the lowest till a network is selected.
        WifiConfiguration selectedNetwork = null;
        for (NetworkEvaluator registeredEvaluator : mEvaluators) {
            if (registeredEvaluator != null) {
                selectedNetwork = registeredEvaluator.evaluateNetworks(filteredScanDetails,
                            currentNetwork, currentBssid, connected,
                            untrustedNetworkAllowed, mConnectableNetworks);
                if (selectedNetwork != null) {
                    break;
                }
            }
        }

        if (selectedNetwork != null) {
            mLastNetworkSelectionTimeStamp = mClock.getElapsedSinceBootMillis();
        }

        return selectedNetwork;
    }

    /**
     * Register a network evaluator
     *
     * @param evaluator the network evaluator to be registered
     * @param priority a value between 0 and (SCORER_MIN_PRIORITY-1)
     *
     * @return true if the evaluator is successfully registered with QNS;
     *         false if failed to register the evaluator
     */
    public boolean registerNetworkEvaluator(NetworkEvaluator evaluator, int priority) {
        if (priority < 0 || priority >= EVALUATOR_MIN_PRIORITY) {
            localLog("Invalid network evaluator priority: " + priority);
            return false;
        }

        if (mEvaluators[priority] != null) {
            localLog("Priority " + priority + " is already registered by "
                    + mEvaluators[priority].getName());
            return false;
        }

        mEvaluators[priority] = evaluator;
        return true;
    }

    /**
     * Unregister a network evaluator
     *
     * @param evaluator the network evaluator to be unregistered from QNS
     *
     * @return true if the evaluator is successfully unregistered from;
     *         false if failed to unregister the evaluator
     */
    public boolean unregisterNetworkEvaluator(NetworkEvaluator evaluator) {
        for (NetworkEvaluator registeredEvaluator : mEvaluators) {
            if (registeredEvaluator == evaluator) {
                localLog("Unregistered network evaluator: " + evaluator.getName());
                return true;
            }
        }

        localLog("Couldn't unregister network evaluator: " + evaluator.getName());
        return false;
    }

    WifiNetworkSelector(Context context, WifiConfigManager configManager, Clock clock) {
        mWifiConfigManager = configManager;
        mClock = clock;

        mThresholdQualifiedRssi24 = context.getResources().getInteger(
                            R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz);
        mThresholdQualifiedRssi5 = context.getResources().getInteger(
                            R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz);
        mThresholdMinimumRssi24 = context.getResources().getInteger(
                            R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_24GHz);
        mThresholdMinimumRssi5 = context.getResources().getInteger(
                            R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_5GHz);
        mEnableAutoJoinWhenAssociated = context.getResources().getBoolean(
                            R.bool.config_wifi_framework_enable_associated_network_selection);
    }

    /**
     * Retrieve the local log buffer created by WifiNetworkSelector.
     */
    public LocalLog getLocalLog() {
        return mLocalLog;
    }

    /**
     * Dump the local logs.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WifiNetworkSelector");
        pw.println("WifiNetworkSelector - Log Begin ----");
        mLocalLog.dump(fd, pw, args);
        pw.println("WifiNetworkSelector - Log End ----");
    }
}
