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

package com.android.server.wifi.hotspot2;

import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_ICON_BSSID;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_ICON_DATA;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_ICON_FILE;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_WNM_BSSID;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_WNM_DELAY;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_WNM_ESS;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_WNM_METHOD;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_WNM_URL;
import static android.net.wifi.WifiManager.PASSPOINT_ICON_RECEIVED_ACTION;
import static android.net.wifi.WifiManager.PASSPOINT_WNM_FRAME_RECEIVED_ACTION;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import com.android.server.wifi.IMSIParameter;
import com.android.server.wifi.SIMAccessor;
import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.anqp.ANQPElement;
import com.android.server.wifi.anqp.Constants;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Responsible for managing passpoint networks.
 */
public class PasspointManager {
    private static final String TAG = "PasspointManager";

    private final PasspointEventHandler mHandler;
    private final SIMAccessor mSimAccessor;
    private final Map<String, PasspointProvider> mProviders;

    private class CallbackHandler implements PasspointEventHandler.Callbacks {
        private final Context mContext;
        CallbackHandler(Context context) {
            mContext = context;
        }

        @Override
        public void onANQPResponse(long bssid,
                Map<Constants.ANQPElementType, ANQPElement> anqpElements) {
            // TO BE IMPLEMENTED.
        }

        @Override
        public void onIconResponse(long bssid, String fileName, byte[] data) {
            Intent intent = new Intent(PASSPOINT_ICON_RECEIVED_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(EXTRA_PASSPOINT_ICON_BSSID, bssid);
            intent.putExtra(EXTRA_PASSPOINT_ICON_FILE, fileName);
            if (data != null) {
                intent.putExtra(EXTRA_PASSPOINT_ICON_DATA, data);
            }
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }

        @Override
        public void onWnmFrameReceived(WnmData event) {
            // %012x HS20-SUBSCRIPTION-REMEDIATION "%u %s", osu_method, url
            // %012x HS20-DEAUTH-IMMINENT-NOTICE "%u %u %s", code, reauth_delay, url
            Intent intent = new Intent(PASSPOINT_WNM_FRAME_RECEIVED_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);

            intent.putExtra(EXTRA_PASSPOINT_WNM_BSSID, event.getBssid());
            intent.putExtra(EXTRA_PASSPOINT_WNM_URL, event.getUrl());

            if (event.isDeauthEvent()) {
                intent.putExtra(EXTRA_PASSPOINT_WNM_ESS, event.isEss());
                intent.putExtra(EXTRA_PASSPOINT_WNM_DELAY, event.getDelay());
            } else {
                intent.putExtra(EXTRA_PASSPOINT_WNM_METHOD, event.getMethod());
                // TODO(zqiu): set the passpoint matching status with the respect to the
                // current connected network (e.g. HomeProvider, RoamingProvider, None,
                // Declined).
            }
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    public PasspointManager(Context context, WifiInjector wifiInjector, SIMAccessor simAccessor) {
        mHandler = wifiInjector.makePasspointEventHandler(new CallbackHandler(context));
        mSimAccessor = simAccessor;
        mProviders = new HashMap<>();
        // TODO(zqiu): load providers from the persistent storage.
    }

    /**
     * Add or install a Passpoint provider with the given configuration.
     *
     * Each provider is uniquely identified by its FQDN (Fully Qualified Domain Name).
     * In the case when there is an existing configuration with the same base
     * domain, a provider with the new configuration will replace the existing provider.
     *
     * @param config Configuration of the Passpoint provider to be added
     * @return true if provider is added, false otherwise
     */
    public boolean addProvider(PasspointConfiguration config) {
        if (config == null) {
            Log.e(TAG, "Configuration not provided");
            return false;
        }
        if (!config.validate()) {
            Log.e(TAG, "Invalid configuration");
            return false;
        }

        // Verify IMSI against the IMSI of the installed SIM cards for SIM credential.
        if (config.credential.simCredential != null) {
            try {
                if (mSimAccessor.getMatchingImsis(
                        new IMSIParameter(config.credential.simCredential.imsi)) == null) {
                    Log.e(TAG, "IMSI does not match any SIM card");
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
        }

        // TODO(b/32619189): install new key and certificates to the keystore.

        // Detect existing configuration in the same base domain.
        PasspointProvider existingProvider = findProviderInSameBaseDomain(config.homeSp.fqdn);
        if (existingProvider != null) {
            Log.d(TAG, "Replacing configuration for " + existingProvider.getConfig().homeSp.fqdn
                    + " with " + config.homeSp.fqdn);
            // TODO(b/32619189): Remove existing key and certificates from the keystore.

            mProviders.remove(existingProvider.getConfig().homeSp.fqdn);
        }

        mProviders.put(config.homeSp.fqdn, new PasspointProvider(config));

        // TODO(b/31065385): Persist updated providers configuration to the persistent storage.

        return true;
    }

    /**
     * Remove a Passpoint provider identified by the given FQDN.
     *
     * @param fqdn The FQDN of the provider to remove
     * @return true if a provider is removed, false otherwise
     */
    public boolean removeProvider(String fqdn) {
        if (!mProviders.containsKey(fqdn)) {
            Log.e(TAG, "Config doesn't exist");
            return false;
        }

        // TODO(b/32619189): Remove key and certificates from the keystore.

        mProviders.remove(fqdn);
        return true;
    }

    /**
     * Return the installed Passpoint provider configurations.
     *
     * @return A list of {@link PasspointConfiguration} or null if none is installed
     */
    public List<PasspointConfiguration> getProviderConfigs() {
        if (mProviders.size() == 0) {
            return null;
        }

        List<PasspointConfiguration> configs = new ArrayList<>();
        for (Map.Entry<String, PasspointProvider> entry : mProviders.entrySet()) {
            configs.add(entry.getValue().getConfig());
        }
        return configs;
    }

    /**
     * Notify the completion of an ANQP request.
     * TODO(zqiu): currently the notification is done through WifiMonitor,
     * will no longer be the case once we switch over to use wificond.
     */
    public void notifyANQPDone(long bssid, boolean success) {
        mHandler.notifyANQPDone(bssid, success);
    }

    /**
     * Notify the completion of an icon request.
     * TODO(zqiu): currently the notification is done through WifiMonitor,
     * will no longer be the case once we switch over to use wificond.
     */
    public void notifyIconDone(long bssid, IconEvent iconEvent) {
        mHandler.notifyIconDone(bssid, iconEvent);
    }

    /**
     * Notify the reception of a Wireless Network Management (WNM) frame.
     * TODO(zqiu): currently the notification is done through WifiMonitor,
     * will no longer be the case once we switch over to use wificond.
     */
    public void receivedWnmFrame(WnmData data) {
        mHandler.notifyWnmFrameReceived(data);
    }

    /**
     * Request the specified icon file |fileName| from the specified AP |bssid|.
     * @return true if the request is sent successfully, false otherwise
     */
    public boolean queryPasspointIcon(long bssid, String fileName) {
        return mHandler.requestIcon(bssid, fileName);
    }

    /**
     * Find a provider that have FQDN in the same base domain as the given domain.
     *
     * @param domain The domain to be compared
     * @return {@link PasspointProvider} if a match is found, null otherwise
     */
    private PasspointProvider findProviderInSameBaseDomain(String domain) {
        for (Map.Entry<String, PasspointProvider> entry : mProviders.entrySet()) {
            if (isSameBaseDomain(entry.getKey(), domain)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Check if one domain is the base domain for the other.  For example, "test1.test.com"
     * and "test.com" should return true.
     *
     * @param domain1 First domain to be compared
     * @param domain2 Second domain to be compared
     * @return true if one domain is a base domain for the other, false otherwise.
     */
    private static boolean isSameBaseDomain(String domain1, String domain2) {
        if (domain1 == null || domain2 == null) {
            return false;
        }

        List<String> labelList1 = Utils.splitDomain(domain1);
        List<String> labelList2 = Utils.splitDomain(domain2);
        Iterator<String> l1 = labelList1.iterator();
        Iterator<String> l2 = labelList2.iterator();

        while (l1.hasNext() && l2.hasNext()) {
            if (!TextUtils.equals(l1.next(), l2.next())) {
                return false;
            }
        }
        return true;
    }
}
