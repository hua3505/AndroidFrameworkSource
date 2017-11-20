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

import android.net.IpConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.os.Environment;
import android.util.Log;
import android.util.SparseArray;

import com.android.server.net.IpConfigStore;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class provides the API's to load network configurations from legacy store
 * mechanism (Pre O release).
 * This class loads network configurations from:
 * 1. /data/misc/wifi/networkHistory.txt
 * 2. /data/misc/wifi/wpa_supplicant.conf
 * 3. /data/misc/wifi/ipconfig.txt
 * 4. /data/misc/wifi/PerProviderSubscription.conf
 *
 * The order of invocation of the public methods during migration is the following:
 * 1. Check if legacy stores are present using {@link #areStoresPresent()}.
 * 2. Load all the store data using {@link #read()}
 * 3. Write the store data to the new store.
 * 4. Remove all the legacy stores using {@link #removeStores()}
 *
 * NOTE: This class should only be used from WifiConfigManager and is not thread-safe!
 *
 * TODO(b/31065385): Passpoint config store data migration & deletion.
 */
public class WifiConfigStoreLegacy {
    /**
     * Log tag.
     */
    private static final String TAG = "WifiConfigStoreLegacy";
    /**
     * NetworkHistory config store file path.
     */
    private static final File NETWORK_HISTORY_FILE =
            new File(WifiNetworkHistory.NETWORK_HISTORY_CONFIG_FILE);
    /**
     * Passpoint config store file path.
     */
    private static final File PPS_FILE =
            new File(Environment.getDataMiscDirectory(), "wifi/PerProviderSubscription.conf");
    /**
     * IpConfig config store file path.
     */
    private static final File IP_CONFIG_FILE =
            new File(Environment.getDataMiscDirectory(), "wifi/ipconfig.txt");
    /**
     * List of external dependencies for WifiConfigManager.
     */
    private final WifiNetworkHistory mWifiNetworkHistory;
    private final WifiSupplicantControl mWifiSupplicantControl;
    private final IpConfigStore mIpconfigStore;

    WifiConfigStoreLegacy(WifiNetworkHistory wifiNetworkHistory,
            WifiSupplicantControl wifiSupplicantControl, IpConfigStore ipConfigStore) {
        mWifiNetworkHistory = wifiNetworkHistory;
        mWifiSupplicantControl = wifiSupplicantControl;
        mIpconfigStore = ipConfigStore;
    }

    /**
     * Helper function to lookup the WifiConfiguration object from configKey to WifiConfiguration
     * object map using the hashcode of the configKey.
     *
     * @param configurationMap Map of configKey to WifiConfiguration object.
     * @param hashCode         hash code of the configKey to match.
     * @return
     */
    private static WifiConfiguration lookupWifiConfigurationUsingConfigKeyHash(
            Map<String, WifiConfiguration> configurationMap, int hashCode) {
        for (Map.Entry<String, WifiConfiguration> entry : configurationMap.entrySet()) {
            if (entry.getKey().hashCode() == hashCode) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Helper function to load {@link IpConfiguration} data from the ip config store file and
     * populate the provided configuration map.
     *
     * @param configurationMap Map of configKey to WifiConfiguration object.
     */
    private void loadFromIpConfigStore(Map<String, WifiConfiguration> configurationMap) {
        // This is a map of the hash code of the network's configKey to the corresponding
        // IpConfiguration.
        SparseArray<IpConfiguration> ipConfigurations =
                mIpconfigStore.readIpAndProxyConfigurations(IP_CONFIG_FILE.getAbsolutePath());
        if (ipConfigurations == null || ipConfigurations.size() == 0) {
            Log.w(TAG, "No ip configurations found in ipconfig store");
            return;
        }
        for (int i = 0; i < ipConfigurations.size(); i++) {
            int id = ipConfigurations.keyAt(i);
            WifiConfiguration config =
                    lookupWifiConfigurationUsingConfigKeyHash(configurationMap, id);
            // This is the only place the map is looked up through a (dangerous) hash-value!
            if (config == null || config.ephemeral) {
                Log.w(TAG, "configuration found for missing network, nid=" + id
                        + ", ignored, networks.size=" + Integer.toString(ipConfigurations.size()));
            } else {
                config.setIpConfiguration(ipConfigurations.valueAt(i));
            }
        }
    }

    /**
     * Helper function to load {@link WifiConfiguration} data from networkHistory file and populate
     * the provided configuration map and deleted ephemeral ssid list.
     *
     * @param configurationMap      Map of configKey to WifiConfiguration object.
     * @param deletedEphemeralSSIDs Map of configKey to WifiConfiguration object.
     */
    private void loadFromNetworkHistory(
            Map<String, WifiConfiguration> configurationMap, Set<String> deletedEphemeralSSIDs) {
        // TODO: Need  to revisit the scan detail cache persistance. We're not doing it in the new
        // config store, so ignore it here as well.
        Map<Integer, ScanDetailCache> scanDetailCaches = new HashMap<>();
        mWifiNetworkHistory.readNetworkHistory(
                configurationMap, scanDetailCaches, deletedEphemeralSSIDs);
    }

    /**
     * Populate the provided masked fieldName for all the configurations provided from
     * wpa_supplicant.conf file.
     *
     * @param fieldName        field name to be retrieved from wpa_supplicant.conf file.
     * @param configurationMap map of configKey to WifiConfiguration object.
     * @param setter           callback function to be invoked to set the value retrieved on each
     *                         configuration.
     */
    private void populateMaskedFieldFromWpaSupplicantFile(
            String fieldName,
            Map<String, WifiConfiguration> configurationMap,
            MaskedWpaSupplicantFieldSetter setter) {
        Map<String, String> configKeyToValueMap =
                mWifiSupplicantControl.readNetworkVariablesFromSupplicantFile(fieldName);
        if (configKeyToValueMap == null || configKeyToValueMap.isEmpty()) {
            Log.w(TAG, "Cannot retrieve field: " + fieldName + " values");
            return;
        }
        for (Map.Entry<String, WifiConfiguration> entry : configurationMap.entrySet()) {
            if (configKeyToValueMap.containsKey(entry.getKey())) {
                WifiConfiguration config = entry.getValue();
                setter.setValue(config, configKeyToValueMap.get(entry.getKey()));
            }
        }
    }

    /**
     * Populate all the masked fields in all the configurations provided from wpa_supplicant.conf
     * file.
     * These are the fields which are populated in the method:
     * 1. psk
     * 2. wep_key0
     * 3. wep_key1
     * 4. wep_key2
     * 5. wep_key2
     * 6. password
     *
     * @param configurationMap Map of configKey to WifiConfiguration object.
     */
    private void populateMaskedFieldsFromWpaSupplicantFile(
            Map<String, WifiConfiguration> configurationMap) {
        populateMaskedFieldFromWpaSupplicantFile(
                WifiConfiguration.pskVarName, configurationMap,
                (WifiConfiguration config, String value) -> config.preSharedKey = value);
        populateMaskedFieldFromWpaSupplicantFile(
                WifiConfiguration.wepKeyVarNames[0], configurationMap,
                (WifiConfiguration config, String value) -> config.wepKeys[0] = value);
        populateMaskedFieldFromWpaSupplicantFile(
                WifiConfiguration.wepKeyVarNames[1], configurationMap,
                (WifiConfiguration config, String value) -> config.wepKeys[1] = value);
        populateMaskedFieldFromWpaSupplicantFile(
                WifiConfiguration.wepKeyVarNames[2], configurationMap,
                (WifiConfiguration config, String value) -> config.wepKeys[2] = value);
        populateMaskedFieldFromWpaSupplicantFile(
                WifiConfiguration.wepKeyVarNames[3], configurationMap,
                (WifiConfiguration config, String value) -> config.wepKeys[3] = value);
        populateMaskedFieldFromWpaSupplicantFile(
                WifiEnterpriseConfig.PASSWORD_KEY, configurationMap,
                (WifiConfiguration config, String value) ->
                        config.enterpriseConfig.setPassword(value));
    }

    /**
     * Helper function to load {@link WifiConfiguration} data from wpa_supplicant and populate
     * the provided configuration map and network extras.
     *
     * This method needs to manually parse the wpa_supplicant.conf file to retrieve some of the
     * password fields like psk, wep_keys. password, etc.
     *
     * @param configurationMap Map of configKey to WifiConfiguration object.
     * @param networkExtras    Map of network extras parsed from wpa_supplicant.
     */
    private void loadFromWpaSupplicant(
            Map<String, WifiConfiguration> configurationMap,
            SparseArray<Map<String, String>> networkExtras) {
        mWifiSupplicantControl.loadNetworks(configurationMap, networkExtras);
        if (configurationMap.isEmpty()) {
            Log.w(TAG, "No wifi configurations found in wpa_supplicant");
            return;
        }
        // Now parse wpa_supplicant.conf for the masked fields.
        populateMaskedFieldsFromWpaSupplicantFile(configurationMap);
    }

    /**
     * Helper function to load from the different legacy stores:
     * 1. Read the network configurations from wpa_supplicant using {@link WifiSupplicantControl}.
     * 2. Read the network configurations from networkHistory.txt using {@link WifiNetworkHistory}.
     * 3. Read the Ip configurations from ipconfig.txt using {@link IpConfigStore}.
     * 4. Read all the passpoint info from PerProviderSubscription.conf using
     * {@link com.android.hotspot2.osu.OSUManager}.
     */
    public WifiConfigStoreDataLegacy read() {
        final Map<String, WifiConfiguration> configurationMap = new HashMap<>();
        final SparseArray<Map<String, String>> networkExtras = new SparseArray<>();
        final Set<String> deletedEphemeralSSIDs = new HashSet<>();

        loadFromWpaSupplicant(configurationMap, networkExtras);
        loadFromNetworkHistory(configurationMap, deletedEphemeralSSIDs);
        loadFromIpConfigStore(configurationMap);
        // TODO: readPasspointConfig(configurationMap, networkExtras);

        // Now create config store data instance to be returned.
        return new WifiConfigStoreDataLegacy(
                new ArrayList<>(configurationMap.values()), deletedEphemeralSSIDs);
    }

    /**
     * Function to check if the legacy store files are present and hence load from those stores and
     * then delete them.
     *
     * @return true if legacy store files are present, false otherwise.
     */
    public boolean areStoresPresent() {
        // We may have to keep the wpa_supplicant.conf file around. So, just use networkhistory.txt
        // as a check to see if we have not yet migrated or not. This should be the last file
        // that is deleted after migration.
        File file = new File(WifiNetworkHistory.NETWORK_HISTORY_CONFIG_FILE);
        return file.exists();
    }

    /**
     * Method to remove all the legacy store files. This should only be invoked once all
     * the data has been migrated to the new store file.
     * 1. Removes all networks from wpa_supplicant and saves it to wpa_supplicant.conf
     * 2. Deletes ipconfig.txt
     * 3. Deletes networkHistory.txt
     *
     * @return true if all the store files were deleted successfully, false otherwise.
     */
    public boolean removeStores() {
        // TODO(b/29352330): Delete wpa_supplicant.conf file instead.
        // First remove all networks from wpa_supplicant and save configuration.
        if (!mWifiSupplicantControl.removeAllNetworks()) {
            Log.e(TAG, "Removing networks from wpa_supplicant failed");
            return false;
        }
        mWifiSupplicantControl.saveConfig();

        // Now remove the ipconfig.txt file.
        if (!IP_CONFIG_FILE.delete()) {
            Log.e(TAG, "Removing ipconfig.txt failed");
            return false;
        }

        // Now finally remove network history.txt
        if (!NETWORK_HISTORY_FILE.delete()) {
            Log.e(TAG, "Removing networkHistory.txt failed");
            return false;
        }

        Log.i(TAG, "All legacy stores removed!");
        return true;
    }

    /**
     * Interface used to set a masked value in the provided configuration. The masked value is
     * retrieved by parsing the wpa_supplicant.conf file.
     */
    private interface MaskedWpaSupplicantFieldSetter {
        void setValue(WifiConfiguration config, String value);
    }

    /**
     * Class used to encapsulate all the store data retrieved from the legacy (Pre O) store files.
     */
    public static class WifiConfigStoreDataLegacy {
        private List<WifiConfiguration> mConfigurations;
        private Set<String> mDeletedEphemeralSSIDs;
        // private List<HomeSP> mHomeSps;

        WifiConfigStoreDataLegacy(List<WifiConfiguration> configurations,
                Set<String> deletedEphemeralSSIDs) {
            mConfigurations = configurations;
            mDeletedEphemeralSSIDs = deletedEphemeralSSIDs;
        }

        public List<WifiConfiguration> getConfigurations() {
            return mConfigurations;
        }

        public Set<String> getDeletedEphemeralSSIDs() {
            return mDeletedEphemeralSSIDs;
        }
    }
}
