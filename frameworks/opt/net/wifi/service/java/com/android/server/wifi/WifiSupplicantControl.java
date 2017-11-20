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

import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiSsid;
import android.net.wifi.WpsInfo;
import android.net.wifi.WpsResult;
import android.os.FileObserver;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Log;
import android.util.SparseArray;

import com.android.server.wifi.hotspot2.Utils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class provides methods to send control commands to wpa_supplicant from WifiConfigManager.
 * NOTE: This class should only be used from WifiConfigManager!
 */
public class WifiSupplicantControl {
    // This is the only variable whose contents will not be interpreted by wpa_supplicant. We use it
    // to store metadata that allows us to correlate a wpa_supplicant.conf entry with additional
    // information about the same network stored in other files. The metadata is stored as a
    // serialized JSON dictionary.
    public static final String ID_STRING_VAR_NAME = "id_str";
    public static final String ID_STRING_KEY_FQDN = "fqdn";
    public static final String ID_STRING_KEY_CREATOR_UID = "creatorUid";
    public static final String ID_STRING_KEY_CONFIG_KEY = "configKey";
    public static final String SUPPLICANT_CONFIG_FILE = "/data/misc/wifi/wpa_supplicant.conf";
    public static final String SUPPLICANT_CONFIG_FILE_BACKUP = SUPPLICANT_CONFIG_FILE + ".tmp";
    // Value stored by supplicant to requirePMF
    public static final int STORED_VALUE_FOR_REQUIRE_PMF = 2;
    private static final String TAG = "WifiSupplicantControl";
    private final LocalLog mLocalLog;
    private final WpaConfigFileObserver mFileObserver;
    private final TelephonyManager mTelephonyManager;
    private final WifiNative mWifiNative;

    // TODO (b/31080843): This will need to be a map when we have multiple networks in supplicant.
    // Supplicant network ID of the only configured network in wpa_supplicant.
    private int mSupplicantNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
    // Corresponding framework network ID of the only configured network in wpa_supplicant.
    private int mFrameworkNetworkId = WifiConfiguration.INVALID_NETWORK_ID;

    private boolean mVerboseLoggingEnabled = false;

    WifiSupplicantControl(TelephonyManager telephonyManager, WifiNative wifiNative,
            LocalLog localLog) {
        mTelephonyManager = telephonyManager;
        mWifiNative = wifiNative;

        mLocalLog = localLog;
        mFileObserver = new WpaConfigFileObserver();
        mFileObserver.startWatching();
    }

    private static String removeDoubleQuotes(String string) {
        int length = string.length();
        if ((length > 1) && (string.charAt(0) == '"')
                && (string.charAt(length - 1) == '"')) {
            return string.substring(1, length - 1);
        }
        return string;
    }

    /**
     * Generate a string to be used as a key value by wpa_supplicant from
     * 'set', within the set of strings from 'strings' for the variable concatenated.
     * Also transform the internal string format that uses _ (for bewildering
     * reasons) into a wpa_supplicant adjusted value, that uses - as a separator
     * (most of the time at least...).
     *
     * @param set     a bit set with a one for each corresponding string to be included from
     *                strings.
     * @param strings the set of string literals to concatenate strinfs from.
     * @return A wpa_supplicant formatted value.
     */
    private static String makeString(BitSet set, String[] strings) {
        return makeStringWithException(set, strings, null);
    }

    /**
     * Same as makeString with an exclusion parameter.
     *
     * @param set       a bit set with a one for each corresponding string to be included from
     *                  strings.
     * @param strings   the set of string literals to concatenate strinfs from.
     * @param exception literal string to be excluded from the _ to - transformation.
     * @return A wpa_supplicant formatted value.
     */
    private static String makeStringWithException(BitSet set, String[] strings, String exception) {
        StringBuilder result = new StringBuilder();

        /* Make sure all set bits are in [0, strings.length) to avoid
         * going out of bounds on strings.  (Shouldn't happen, but...) */
        BitSet trimmedSet = set.get(0, strings.length);

        List<String> valueSet = new ArrayList<>();
        for (int bit = trimmedSet.nextSetBit(0); bit >= 0; bit = trimmedSet.nextSetBit(bit + 1)) {
            String currentName = strings[bit];
            if (exception != null && currentName.equals(exception)) {
                valueSet.add(currentName);
            } else {
                // Most wpa_supplicant strings use a dash whereas (for some bizarre
                // reason) the strings are defined with underscore in the code...
                valueSet.add(currentName.replace('_', '-'));
            }
        }
        return TextUtils.join(" ", valueSet);
    }

    private int lookupString(String string, String[] strings) {
        int size = strings.length;

        string = string.replace('-', '_');

        for (int i = 0; i < size; i++) {
            if (string.equals(strings[i])) {
                return i;
            }
        }
        loge("Failed to look-up a string: " + string);
        return -1;
    }

    private void readNetworkBitsetVariable(int netId, BitSet variable, String varName,
            String[] strings) {
        String value = mWifiNative.getNetworkVariable(netId, varName);
        if (!TextUtils.isEmpty(value)) {
            variable.clear();
            String[] vals = value.split(" ");
            for (String val : vals) {
                int index = lookupString(val, strings);
                if (0 <= index) {
                    variable.set(index);
                }
            }
        }
    }

    /**
     * Read the variables from the supplicant daemon that are needed to
     * fill in the WifiConfiguration object.
     *
     * @param config the {@link WifiConfiguration} object to be filled in.
     */
    public void readNetworkVariables(WifiConfiguration config) {
        if (config == null) {
            return;
        }
        int netId = config.networkId;
        if (netId < 0) {
            return;
        }
        /*
         * TODO: maybe should have a native method that takes an array of
         * variable names and returns an array of values. But we'd still
         * be doing a round trip to the supplicant daemon for each variable.
         */
        String value;

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.ssidVarName);
        if (!TextUtils.isEmpty(value)) {
            if (value.charAt(0) != '"') {
                config.SSID = "\"" + WifiSsid.createFromHex(value).toString() + "\"";
                //TODO: convert a hex string that is not UTF-8 decodable to a P-formatted
                //supplicant string
            } else {
                config.SSID = value;
            }
        } else {
            config.SSID = null;
        }

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.bssidVarName);
        if (!TextUtils.isEmpty(value)) {
            config.getNetworkSelectionStatus().setNetworkSelectionBSSID(value);
        } else {
            config.getNetworkSelectionStatus().setNetworkSelectionBSSID(null);
        }

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.priorityVarName);
        config.priority = -1;
        if (!TextUtils.isEmpty(value)) {
            try {
                config.priority = Integer.parseInt(value);
            } catch (NumberFormatException ignore) {
            }
        }

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.hiddenSSIDVarName);
        config.hiddenSSID = false;
        if (!TextUtils.isEmpty(value)) {
            try {
                config.hiddenSSID = Integer.parseInt(value) != 0;
            } catch (NumberFormatException ignore) {
            }
        }

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.pmfVarName);
        config.requirePMF = false;
        if (!TextUtils.isEmpty(value)) {
            try {
                config.requirePMF = Integer.parseInt(value) == STORED_VALUE_FOR_REQUIRE_PMF;
            } catch (NumberFormatException ignore) {
            }
        }

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.wepTxKeyIdxVarName);
        config.wepTxKeyIndex = -1;
        if (!TextUtils.isEmpty(value)) {
            try {
                config.wepTxKeyIndex = Integer.parseInt(value);
            } catch (NumberFormatException ignore) {
            }
        }

        for (int i = 0; i < 4; i++) {
            value = mWifiNative.getNetworkVariable(netId,
                    WifiConfiguration.wepKeyVarNames[i]);
            if (!TextUtils.isEmpty(value)) {
                config.wepKeys[i] = value;
            } else {
                config.wepKeys[i] = null;
            }
        }

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.pskVarName);
        if (!TextUtils.isEmpty(value)) {
            config.preSharedKey = value;
        } else {
            config.preSharedKey = null;
        }

        readNetworkBitsetVariable(config.networkId, config.allowedProtocols,
                WifiConfiguration.Protocol.varName, WifiConfiguration.Protocol.strings);

        readNetworkBitsetVariable(config.networkId, config.allowedKeyManagement,
                WifiConfiguration.KeyMgmt.varName, WifiConfiguration.KeyMgmt.strings);

        readNetworkBitsetVariable(config.networkId, config.allowedAuthAlgorithms,
                WifiConfiguration.AuthAlgorithm.varName, WifiConfiguration.AuthAlgorithm.strings);

        readNetworkBitsetVariable(config.networkId, config.allowedPairwiseCiphers,
                WifiConfiguration.PairwiseCipher.varName, WifiConfiguration.PairwiseCipher.strings);

        readNetworkBitsetVariable(config.networkId, config.allowedGroupCiphers,
                WifiConfiguration.GroupCipher.varName, WifiConfiguration.GroupCipher.strings);

        if (config.enterpriseConfig == null) {
            config.enterpriseConfig = new WifiEnterpriseConfig();
        }
        config.enterpriseConfig.loadFromSupplicant(new SupplicantLoader(netId));
    }

    /**
     * Load all the configured networks from wpa_supplicant.
     *
     * @param configs       Map of configuration key to configuration objects corresponding to all
     *                      the networks.
     * @param networkExtras Map of extra configuration parameters stored in wpa_supplicant.conf
     * @return Max priority of all the configs.
     */
    public int loadNetworks(Map<String, WifiConfiguration> configs,
            SparseArray<Map<String, String>> networkExtras) {
        int lastPriority = 0;
        int last_id = -1;
        boolean done = false;
        while (!done) {
            String listStr = mWifiNative.listNetworks(last_id);
            if (listStr == null) {
                return lastPriority;
            }
            String[] lines = listStr.split("\n");
            if (mVerboseLoggingEnabled) {
                localLog("loadNetworks:  ");
                for (String net : lines) {
                    localLog(net);
                }
            }
            // Skip the first line, which is a header
            for (int i = 1; i < lines.length; i++) {
                String[] result = lines[i].split("\t");
                // network-id | ssid | bssid | flags
                WifiConfiguration config = new WifiConfiguration();
                try {
                    config.networkId = Integer.parseInt(result[0]);
                    last_id = config.networkId;
                } catch (NumberFormatException e) {
                    loge("Failed to read network-id '" + result[0] + "'");
                    continue;
                }
                // Ignore the supplicant status, start all networks disabled.
                config.status = WifiConfiguration.Status.DISABLED;
                readNetworkVariables(config);
                // Parse the serialized JSON dictionary in ID_STRING_VAR_NAME once and cache the
                // result for efficiency.
                Map<String, String> extras = mWifiNative.getNetworkExtra(config.networkId,
                        ID_STRING_VAR_NAME);
                if (extras == null) {
                    extras = new HashMap<String, String>();
                    // If ID_STRING_VAR_NAME did not contain a dictionary, assume that it contains
                    // just a quoted FQDN. This is the legacy format that was used in Marshmallow.
                    final String fqdn = Utils.unquote(mWifiNative.getNetworkVariable(
                            config.networkId, ID_STRING_VAR_NAME));
                    if (fqdn != null) {
                        extras.put(ID_STRING_KEY_FQDN, fqdn);
                        config.FQDN = fqdn;
                        // Mark the configuration as a Hotspot 2.0 network.
                        config.providerFriendlyName = "";
                    }
                }
                networkExtras.put(config.networkId, extras);

                if (config.priority > lastPriority) {
                    lastPriority = config.priority;
                }
                config.setIpAssignment(IpAssignment.DHCP);
                config.setProxySettings(ProxySettings.NONE);
                if (!WifiServiceImpl.isValid(config)) {
                    if (mVerboseLoggingEnabled) {
                        localLog("Ignoring network " + config.networkId + " because configuration "
                                + "loaded from wpa_supplicant.conf is not valid.");
                    }
                    continue;
                }
                // The configKey is explicitly stored in wpa_supplicant.conf, because config does
                // not contain sufficient information to compute it at this point.
                String configKey = extras.get(ID_STRING_KEY_CONFIG_KEY);
                if (configKey == null) {
                    // Handle the legacy case where the configKey is not stored in
                    // wpa_supplicant.conf but can be computed straight away.
                    // Force an update of this legacy network configuration by writing
                    // the configKey for this network into wpa_supplicant.conf.
                    configKey = config.configKey();
                    saveNetworkMetadata(config, config.networkId);
                }
                final WifiConfiguration duplicateConfig = configs.put(configKey, config);
                if (duplicateConfig != null) {
                    // The network is already known. Overwrite the duplicate entry.
                    if (mVerboseLoggingEnabled) {
                        localLog("Replacing duplicate network " + duplicateConfig.networkId
                                + " with " + config.networkId + ".");
                    }
                    // This can happen after the user manually connected to an AP and tried to use
                    // WPS to connect the AP later. In this case, the supplicant will create a new
                    // network for the AP although there is an existing network already.
                    mWifiNative.removeNetwork(duplicateConfig.networkId);
                }
            }
            done = (lines.length == 1);
        }
        return lastPriority;
    }

    /**
     * Update the network metadata info stored in wpa_supplicant network extra field.
     *
     * @param config Config corresponding to the network.
     * @param netId  Net Id of the network.
     * @return true if successful, false otherwise.
     */
    public boolean saveNetworkMetadata(WifiConfiguration config, int netId) {
        final Map<String, String> metadata = new HashMap<String, String>();
        if (config.isPasspoint()) {
            metadata.put(ID_STRING_KEY_FQDN, config.FQDN);
        }
        metadata.put(ID_STRING_KEY_CONFIG_KEY, config.configKey());
        metadata.put(ID_STRING_KEY_CREATOR_UID, Integer.toString(config.creatorUid));
        if (!mWifiNative.setNetworkExtra(netId, ID_STRING_VAR_NAME, metadata)) {
            loge("failed to set id_str: " + metadata.toString());
            return false;
        }
        return true;
    }

    /**
     * Save an entire network configuration to wpa_supplicant.
     *
     * @param config Config corresponding to the network.
     * @param netId  Net Id of the network.
     * @return true if successful, false otherwise.
     */
    private boolean saveNetworkVariables(WifiConfiguration config, int netId) {
        if (config == null) {
            return false;
        }
        if (config.SSID != null && !mWifiNative.setNetworkVariable(
                netId,
                WifiConfiguration.ssidVarName,
                WifiNative.encodeSSID(config.SSID))) {
            loge("failed to set SSID: " + config.SSID);
            return false;
        }
        if (!saveNetworkMetadata(config, netId)) {
            return false;
        }
        //set selected BSSID to supplicant
        if (config.getNetworkSelectionStatus().getNetworkSelectionBSSID() != null) {
            String bssid = config.getNetworkSelectionStatus().getNetworkSelectionBSSID();
            if (!mWifiNative.setNetworkVariable(netId, WifiConfiguration.bssidVarName, bssid)) {
                loge("failed to set BSSID: " + bssid);
                return false;
            }
        }
        String allowedKeyManagementString =
                makeString(config.allowedKeyManagement, WifiConfiguration.KeyMgmt.strings);
        if (config.allowedKeyManagement.cardinality() != 0 && !mWifiNative.setNetworkVariable(
                netId,
                WifiConfiguration.KeyMgmt.varName,
                allowedKeyManagementString)) {
            loge("failed to set key_mgmt: " + allowedKeyManagementString);
            return false;
        }
        String allowedProtocolsString =
                makeString(config.allowedProtocols, WifiConfiguration.Protocol.strings);
        if (config.allowedProtocols.cardinality() != 0 && !mWifiNative.setNetworkVariable(
                netId,
                WifiConfiguration.Protocol.varName,
                allowedProtocolsString)) {
            loge("failed to set proto: " + allowedProtocolsString);
            return false;
        }
        String allowedAuthAlgorithmsString =
                makeString(config.allowedAuthAlgorithms,
                        WifiConfiguration.AuthAlgorithm.strings);
        if (config.allowedAuthAlgorithms.cardinality() != 0 && !mWifiNative.setNetworkVariable(
                netId,
                WifiConfiguration.AuthAlgorithm.varName,
                allowedAuthAlgorithmsString)) {
            loge("failed to set auth_alg: " + allowedAuthAlgorithmsString);
            return false;
        }
        String allowedPairwiseCiphersString = makeString(config.allowedPairwiseCiphers,
                WifiConfiguration.PairwiseCipher.strings);
        if (config.allowedPairwiseCiphers.cardinality() != 0 && !mWifiNative.setNetworkVariable(
                netId,
                WifiConfiguration.PairwiseCipher.varName,
                allowedPairwiseCiphersString)) {
            loge("failed to set pairwise: " + allowedPairwiseCiphersString);
            return false;
        }
        // Make sure that the string "GTK_NOT_USED" is /not/ transformed - wpa_supplicant
        // uses this literal value and not the 'dashed' version.
        String allowedGroupCiphersString =
                makeStringWithException(config.allowedGroupCiphers,
                        WifiConfiguration.GroupCipher.strings,
                        WifiConfiguration.GroupCipher
                                .strings[WifiConfiguration.GroupCipher.GTK_NOT_USED]);
        if (config.allowedGroupCiphers.cardinality() != 0 && !mWifiNative.setNetworkVariable(
                netId,
                WifiConfiguration.GroupCipher.varName,
                allowedGroupCiphersString)) {
            loge("failed to set group: " + allowedGroupCiphersString);
            return false;
        }
        // Prevent client screw-up by passing in a WifiConfiguration we gave it
        // by preventing "*" as a key.
        if (config.preSharedKey != null && !config.preSharedKey.equals("*")
                && !mWifiNative.setNetworkVariable(
                netId,
                WifiConfiguration.pskVarName,
                config.preSharedKey)) {
            loge("failed to set psk");
            return false;
        }
        boolean hasSetKey = false;
        if (config.wepKeys != null) {
            for (int i = 0; i < config.wepKeys.length; i++) {
                // Prevent client screw-up by passing in a WifiConfiguration we gave it
                // by preventing "*" as a key.
                if (config.wepKeys[i] != null && !config.wepKeys[i].equals("*")) {
                    if (!mWifiNative.setNetworkVariable(
                            netId,
                            WifiConfiguration.wepKeyVarNames[i],
                            config.wepKeys[i])) {
                        loge("failed to set wep_key" + i + ": " + config.wepKeys[i]);
                        return false;
                    }
                    hasSetKey = true;
                }
            }
        }
        if (hasSetKey) {
            if (!mWifiNative.setNetworkVariable(
                    netId,
                    WifiConfiguration.wepTxKeyIdxVarName,
                    Integer.toString(config.wepTxKeyIndex))) {
                loge("failed to set wep_tx_keyidx: " + config.wepTxKeyIndex);
                return false;
            }
        }
        if (!mWifiNative.setNetworkVariable(
                netId,
                WifiConfiguration.priorityVarName,
                Integer.toString(config.priority))) {
            loge(config.SSID + ": failed to set priority: " + config.priority);
            return false;
        }
        if (config.hiddenSSID && !mWifiNative.setNetworkVariable(
                netId,
                WifiConfiguration.hiddenSSIDVarName,
                Integer.toString(config.hiddenSSID ? 1 : 0))) {
            loge(config.SSID + ": failed to set hiddenSSID: " + config.hiddenSSID);
            return false;
        }
        if (config.requirePMF && !mWifiNative.setNetworkVariable(
                netId,
                WifiConfiguration.pmfVarName,
                Integer.toString(STORED_VALUE_FOR_REQUIRE_PMF))) {
            loge(config.SSID + ": failed to set requirePMF: " + config.requirePMF);
            return false;
        }
        if (config.updateIdentifier != null && !mWifiNative.setNetworkVariable(
                netId,
                WifiConfiguration.updateIdentiferVarName,
                config.updateIdentifier)) {
            loge(config.SSID + ": failed to set updateIdentifier: " + config.updateIdentifier);
            return false;
        }
        if (config.enterpriseConfig.getEapMethod() != WifiEnterpriseConfig.Eap.NONE) {
            return config.enterpriseConfig.saveToSupplicant(
                    new WifiSupplicantControl.SupplicantSaver(netId, config.SSID));
        }
        return true;
    }

    /**
     * Add a network configuration to wpa_supplicant.
     *
     * @param config Config corresponding to the network.
     * @return network ID of the added network in wpa_supplicant.
     */
    private int addNetwork(WifiConfiguration config) {
        if (config == null) {
            return WifiConfiguration.INVALID_NETWORK_ID;
        }
        int netId = mWifiNative.addNetwork();
        if (netId < 0) {
            loge("Failed to add a network!");
            return WifiConfiguration.INVALID_NETWORK_ID;
        } else {
            logi("addNetwork created netId=" + netId);
        }
        if (!saveNetworkVariables(config, netId)) {
            mWifiNative.removeNetwork(netId);
            loge("Failed to set a network variable, removed network: " + netId);
            return WifiConfiguration.INVALID_NETWORK_ID;
        }
        return netId;
    }

    /**
     * Add the provided network configuration to wpa_supplicant and initiate connection to it.
     * This method does the following:
     * 1. Triggers disconnect command to wpa_supplicant (if |shouldDisconnect| is true).
     * 2. Remove any existing network in wpa_supplicant.
     * 3. Add a new network to wpa_supplicant.
     * 4. Save the provided configuration to wpa_supplicant.
     * 5. Select the new network in wpa_supplicant.
     * 6. Triggers reconnect command to wpa_supplicant.
     *
     * @param configuration WifiConfiguration parameters for the provided network.
     * @param shouldDisconnect whether to trigger a disconnection or not.
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    public boolean connectToNetwork(WifiConfiguration configuration, boolean shouldDisconnect) {
        mFrameworkNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
        mSupplicantNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
        logd("connectToNetwork " + configuration.configKey() +
                " (shouldDisconnect " + shouldDisconnect + ")");
        if (shouldDisconnect && !mWifiNative.disconnect()) {
            loge("Failed to trigger disconnect");
            return false;
        }
        if (!mWifiNative.removeAllNetworks()) {
            loge("Failed to remove existing networks");
            return false;
        }
        mSupplicantNetworkId = addNetwork(configuration);
        if (mSupplicantNetworkId == WifiConfiguration.INVALID_NETWORK_ID) {
            loge("Failed to add/save network configuration: " + configuration.configKey());
            return false;
        }
        if (!mWifiNative.selectNetwork(mSupplicantNetworkId)) {
            loge("Failed to select network configuration: " + configuration.configKey());
            return false;
        }
        if (!mWifiNative.reconnect()) {
            loge("Failed to trigger reconnect");
            return false;
        }
        mFrameworkNetworkId = configuration.networkId;
        return true;
    }

    /**
     * Initiates roaming to the already configured network in wpa_supplicant. If the network
     * configuration provided does not match the already configured network, then this triggers
     * a new connection attempt (instead of roam).
     * 1. First check if we're attempting to connect to the same network as we currently have
     * configured.
     * 2. Set the new bssid for the network in wpa_supplicant.
     * 3. Triggers reassociate command to wpa_supplicant.
     *
     * @param configuration WifiConfiguration parameters for the provided network.
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    public boolean roamToNetwork(WifiConfiguration configuration) {
        if (mFrameworkNetworkId != configuration.networkId) {
            Log.w(TAG, "Cant roam to a different network, initiate new connection. " +
                    "Current network ID: " + mFrameworkNetworkId);
            return connectToNetwork(configuration, false);
        }
        String bssid = configuration.getNetworkSelectionStatus().getNetworkSelectionBSSID();
        logd("roamToNetwork" + configuration.configKey() + " (bssid " + bssid + ")");
        if (!setConfiguredNetworkBSSID(bssid)) {
            loge("Failed to set new bssid on network: " + configuration.configKey());
            return false;
        }
        if (!mWifiNative.reassociate()) {
            loge("Failed to trigger reassociate");
            return false;
        }
        return true;
    }

    /**
     * Get the framework network ID corresponding to the provided supplicant network ID for the
     * network configured in wpa_supplicant.
     *
     * @param supplicantNetworkId network ID in wpa_supplicant for the network.
     * @return Corresponding framework network ID if found, -1 if network not found.
     */
    public int getFrameworkNetworkId(int supplicantNetworkId) {
        if (mSupplicantNetworkId == supplicantNetworkId) {
            return mFrameworkNetworkId;
        } else {
            Log.e(TAG, "Unknown wpa_supplicant network ID " + supplicantNetworkId
                    + " Current wpa_supplicant network ID " + mSupplicantNetworkId);
            return WifiConfiguration.INVALID_NETWORK_ID;
        }
    }

    /**
     * Remove all the networks.
     *
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    public boolean removeAllNetworks() {
        if (!mWifiNative.removeAllNetworks()) {
            loge("Remove all networks in wpa_supplicant failed");
            return false;
        }
        return true;
    }

    /**
     * Set the BSSID for the currently configured network in wpa_supplicant.
     *
     * @return true if successful, false otherwise.
     */
    public boolean setConfiguredNetworkBSSID(String bssid) {
        if (!mWifiNative.setNetworkVariable(
                mSupplicantNetworkId, WifiConfiguration.bssidVarName, bssid)) {
            loge("Set BSSID of network in wpa_supplicant failed on " + mSupplicantNetworkId);
            return false;
        }
        return true;
    }

    /**
     * Save the current configuration to wpa_supplicant.conf.
     */
    public boolean saveConfig() {
        return mWifiNative.saveConfig();
    }

    /**
     * Read network variables from wpa_supplicant.conf.
     *
     * @param key The parameter to be parsed.
     * @return Map of corresponding configKey to the value of the param requested.
     */
    public Map<String, String> readNetworkVariablesFromSupplicantFile(String key) {
        Map<String, String> result = new HashMap<>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(SUPPLICANT_CONFIG_FILE));
            result = readNetworkVariablesFromReader(reader, key);
        } catch (FileNotFoundException e) {
            loge("Could not open " + SUPPLICANT_CONFIG_FILE + ", " + e);
        } catch (IOException e) {
            loge("Could not read " + SUPPLICANT_CONFIG_FILE + ", " + e);
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                loge("Could not close reader for " + SUPPLICANT_CONFIG_FILE + ", " + e);
            }
        }
        return result;
    }

    /**
     * Read network variables from a given reader. This method is separate from
     * readNetworkVariablesFromSupplicantFile() for testing.
     *
     * @param reader The reader to read the network variables from.
     * @param key    The parameter to be parsed.
     * @return Map of corresponding configKey to the value of the param requested.
     */
    public Map<String, String> readNetworkVariablesFromReader(BufferedReader reader, String key)
            throws IOException {
        Map<String, String> result = new HashMap<>();
        boolean found = false;
        String configKey = null;
        String value = null;
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            if (line.matches("[ \\t]*network=\\{")) {
                found = true;
                configKey = null;
                value = null;
            } else if (line.matches("[ \\t]*\\}")) {
                found = false;
                configKey = null;
                value = null;
            }
            if (found) {
                String trimmedLine = line.trim();
                if (trimmedLine.startsWith(ID_STRING_VAR_NAME + "=")) {
                    try {
                        // Trim the quotes wrapping the id_str value.
                        final String encodedExtras = trimmedLine.substring(
                                8, trimmedLine.length() - 1);
                        final JSONObject json =
                                new JSONObject(URLDecoder.decode(encodedExtras, "UTF-8"));
                        if (json.has(ID_STRING_KEY_CONFIG_KEY)) {
                            final Object configKeyFromJson =
                                    json.get(ID_STRING_KEY_CONFIG_KEY);
                            if (configKeyFromJson instanceof String) {
                                configKey = (String) configKeyFromJson;
                            }
                        }
                    } catch (JSONException e) {
                        loge("Could not get " + ID_STRING_KEY_CONFIG_KEY + ", " + e);
                    }
                }
                if (trimmedLine.startsWith(key + "=")) {
                    value = trimmedLine.substring(key.length() + 1);
                }
                if (configKey != null && value != null) {
                    result.put(configKey, value);
                }
            }
        }
        return result;
    }

    /**
     * Start WPS pin method configuration with pin obtained
     * from the access point
     *
     * @param config WPS configuration
     * @return Wps result containing status and pin
     */
    public WpsResult startWpsWithPinFromAccessPoint(WpsInfo config) {
        WpsResult result = new WpsResult();
        if (mWifiNative.startWpsRegistrar(config.BSSID, config.pin)) {
            result.status = WpsResult.Status.SUCCESS;
        } else {
            loge("Failed to start WPS pin method configuration");
            result.status = WpsResult.Status.FAILURE;
        }
        return result;
    }

    /**
     * Start WPS pin method configuration with obtained
     * from the device
     *
     * @return WpsResult indicating status and pin
     */
    public WpsResult startWpsWithPinFromDevice(WpsInfo config) {
        WpsResult result = new WpsResult();
        result.pin = mWifiNative.startWpsPinDisplay(config.BSSID);
        if (!TextUtils.isEmpty(result.pin)) {
            result.status = WpsResult.Status.SUCCESS;
        } else {
            loge("Failed to start WPS pin method configuration");
            result.status = WpsResult.Status.FAILURE;
        }
        return result;
    }

    /**
     * Start WPS push button configuration
     *
     * @param config WPS configuration
     * @return WpsResult indicating status and pin
     */
    public WpsResult startWpsPbc(WpsInfo config) {
        WpsResult result = new WpsResult();
        if (mWifiNative.startWpsPbc(config.BSSID)) {
            result.status = WpsResult.Status.SUCCESS;
        } else {
            loge("Failed to start WPS push button configuration");
            result.status = WpsResult.Status.FAILURE;
        }
        return result;
    }

    private void logd(String s) {
        Log.d(TAG, s);
    }

    private void logi(String s) {
        Log.i(TAG, s);
    }

    private void loge(String s) {
        loge(s, false);
    }

    private void loge(String s, boolean stack) {
        if (stack) {
            Log.e(TAG, s + " stack:" + Thread.currentThread().getStackTrace()[2].getMethodName()
                    + " - " + Thread.currentThread().getStackTrace()[3].getMethodName()
                    + " - " + Thread.currentThread().getStackTrace()[4].getMethodName()
                    + " - " + Thread.currentThread().getStackTrace()[5].getMethodName());
        } else {
            Log.e(TAG, s);
        }
    }

    private void log(String s) {
        Log.d(TAG, s);
    }

    private void localLog(String s) {
        if (mLocalLog != null) {
            mLocalLog.log(TAG + ": " + s);
        }
    }

    private void localLogAndLogcat(String s) {
        localLog(s);
        Log.d(TAG, s);
    }

    /**
     * Enable verbose logging.
     */
    public void enableVerboseLogging(boolean verbose) {
        mVerboseLoggingEnabled = verbose;
    }

    private class SupplicantSaver implements WifiEnterpriseConfig.SupplicantSaver {
        private final int mNetId;
        private final String mSetterSSID;

        SupplicantSaver(int netId, String setterSSID) {
            mNetId = netId;
            mSetterSSID = setterSSID;
        }

        @Override
        public boolean saveValue(String key, String value) {
            if (key.equals(WifiEnterpriseConfig.PASSWORD_KEY)
                    && value != null && value.equals("*")) {
                // No need to try to set an obfuscated password, which will fail
                return true;
            }
            if (key.equals(WifiEnterpriseConfig.REALM_KEY)
                    || key.equals(WifiEnterpriseConfig.PLMN_KEY)) {
                // No need to save realm or PLMN in supplicant
                return true;
            }
            // TODO: We need a way to clear values in wpa_supplicant as opposed to
            // mapping unset values to empty strings.
            if (value == null) {
                value = "\"\"";
            }
            if (!mWifiNative.setNetworkVariable(mNetId, key, value)) {
                loge(mSetterSSID + ": failed to set " + key + ": " + value);
                return false;
            }
            return true;
        }
    }

    private class SupplicantLoader implements WifiEnterpriseConfig.SupplicantLoader {
        private final int mNetId;

        SupplicantLoader(int netId) {
            mNetId = netId;
        }

        @Override
        public String loadValue(String key) {
            String value = mWifiNative.getNetworkVariable(mNetId, key);
            if (!TextUtils.isEmpty(value)) {
                if (!enterpriseConfigKeyShouldBeQuoted(key)) {
                    value = removeDoubleQuotes(value);
                }
                return value;
            } else {
                return null;
            }
        }

        /**
         * Returns true if a particular config key needs to be quoted when passed to the supplicant.
         */
        private boolean enterpriseConfigKeyShouldBeQuoted(String key) {
            switch (key) {
                case WifiEnterpriseConfig.EAP_KEY:
                case WifiEnterpriseConfig.ENGINE_KEY:
                    return false;
                default:
                    return true;
            }
        }
    }

    // TODO(rpius): Remove this (see b/27377614).
    private class WpaConfigFileObserver extends FileObserver {

        WpaConfigFileObserver() {
            super(SUPPLICANT_CONFIG_FILE, CLOSE_WRITE);
        }

        @Override
        public void onEvent(int event, String path) {
            if (event == CLOSE_WRITE) {
                File file = new File(SUPPLICANT_CONFIG_FILE);
                if (mVerboseLoggingEnabled) {
                    localLog("wpa_supplicant.conf changed; new size = " + file.length());
                }
            }
        }
    }
}
