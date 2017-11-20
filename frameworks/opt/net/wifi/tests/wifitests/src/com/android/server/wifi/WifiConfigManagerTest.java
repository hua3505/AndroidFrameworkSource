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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.test.MockAnswerUtil.AnswerWithArguments;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.net.IpConfiguration;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiSsid;
import android.os.UserHandle;
import android.os.UserManager;
import android.telephony.TelephonyManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.TextUtils;

import com.android.internal.R;
import com.android.server.wifi.WifiConfigStoreLegacy.WifiConfigStoreDataLegacy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Unit tests for {@link com.android.server.wifi.WifiConfigManager}.
 */
@SmallTest
public class WifiConfigManagerTest {

    private static final String TEST_BSSID = "0a:08:5c:67:89:00";
    private static final long TEST_WALLCLOCK_CREATION_TIME_MILLIS = 9845637;
    private static final long TEST_WALLCLOCK_UPDATE_TIME_MILLIS = 75455637;
    private static final long TEST_ELAPSED_UPDATE_NETWORK_SELECTION_TIME_MILLIS = 29457631;
    private static final int TEST_CREATOR_UID = 5;
    private static final int TEST_UPDATE_UID = 4;
    private static final int TEST_SYSUI_UID = 56;
    private static final int TEST_DEFAULT_USER = UserHandle.USER_SYSTEM;
    private static final int TEST_MAX_NUM_ACTIVE_CHANNELS_FOR_PARTIAL_SCAN = 5;
    private static final Integer[] TEST_FREQ_LIST = {2400, 2450, 5150, 5175, 5650};
    private static final String TEST_CREATOR_NAME = "com.wificonfigmanagerNew.creator";
    private static final String TEST_UPDATE_NAME = "com.wificonfigmanagerNew.update";
    private static final String TEST_DEFAULT_GW_MAC_ADDRESS = "0f:67:ad:ef:09:34";

    @Mock private Context mContext;
    @Mock private FrameworkFacade mFrameworkFacade;
    @Mock private Clock mClock;
    @Mock private UserManager mUserManager;
    @Mock private TelephonyManager mTelephonyManager;
    @Mock private WifiKeyStore mWifiKeyStore;
    @Mock private WifiConfigStore mWifiConfigStore;
    @Mock private WifiConfigStoreLegacy mWifiConfigStoreLegacy;
    @Mock private PackageManager mPackageManager;

    private MockResources mResources;
    private InOrder mContextConfigStoreMockOrder;
    private WifiConfigManager mWifiConfigManager;

    /**
     * Setup the mocks and an instance of WifiConfigManager before each test.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        // Set up the inorder for verifications. This is needed to verify that the broadcasts,
        // store writes for network updates followed by network additions are in the expected order.
        mContextConfigStoreMockOrder = inOrder(mContext, mWifiConfigStore);

        // Set up the package name stuff & permission override.
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        mResources = new MockResources();
        mResources.setBoolean(
                R.bool.config_wifi_only_link_same_credential_configurations, true);
        mResources.setInteger(
                R.integer.config_wifi_framework_associated_partial_scan_max_num_active_channels,
                TEST_MAX_NUM_ACTIVE_CHANNELS_FOR_PARTIAL_SCAN);
        when(mContext.getResources()).thenReturn(mResources);

        // Setup UserManager profiles for the default user.
        setupUserProfiles(TEST_DEFAULT_USER);

        doAnswer(new AnswerWithArguments() {
            public String answer(int uid) throws Exception {
                if (uid == TEST_CREATOR_UID) {
                    return TEST_CREATOR_NAME;
                } else if (uid == TEST_UPDATE_UID) {
                    return TEST_UPDATE_NAME;
                } else if (uid == TEST_SYSUI_UID) {
                    return WifiConfigManager.SYSUI_PACKAGE_NAME;
                }
                fail("Unexpected UID: " + uid);
                return "";
            }
        }).when(mPackageManager).getNameForUid(anyInt());
        doAnswer(new AnswerWithArguments() {
            public int answer(String packageName, int flags, int userId) throws Exception {
                if (packageName.equals(WifiConfigManager.SYSUI_PACKAGE_NAME)) {
                    return TEST_SYSUI_UID;
                } else {
                    return 0;
                }
            }
        }).when(mPackageManager).getPackageUidAsUser(anyString(), anyInt(), anyInt());

        // Both the UID's in the test have the configuration override permission granted by
        // default. This maybe modified for particular tests if needed.
        doAnswer(new AnswerWithArguments() {
            public int answer(String permName, int uid) throws Exception {
                if (uid == TEST_CREATOR_UID || uid == TEST_UPDATE_UID) {
                    return PackageManager.PERMISSION_GRANTED;
                }
                return PackageManager.PERMISSION_DENIED;
            }
        }).when(mFrameworkFacade).checkUidPermission(anyString(), anyInt());

        when(mWifiKeyStore
                .updateNetworkKeys(any(WifiConfiguration.class), any(WifiConfiguration.class)))
                .thenReturn(true);

        when(mWifiConfigStore.areStoresPresent()).thenReturn(true);

        createWifiConfigManager();
    }

    /**
     * Called after each test
     */
    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    /**
     * Verifies the addition of a single network using
     * {@link WifiConfigManager#addOrUpdateNetwork(WifiConfiguration, int)}
     */
    @Test
    public void testAddSingleOpenNetwork() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(openNetwork);

        verifyAddNetworkToWifiConfigManager(openNetwork);

        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);
        // Ensure that the newly added network is disabled.
        assertEquals(WifiConfiguration.Status.DISABLED, retrievedNetworks.get(0).status);
    }

    /**
     * Verifies the modification of a single network using
     * {@link WifiConfigManager#addOrUpdateNetwork(WifiConfiguration, int)}
     */
    @Test
    public void testUpdateSingleOpenNetwork() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(openNetwork);

        verifyAddNetworkToWifiConfigManager(openNetwork);

        // Now change BSSID for the network.
        assertAndSetNetworkBSSID(openNetwork, TEST_BSSID);
        verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(openNetwork);

        // Now verify that the modification has been effective.
        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);
    }

    /**
     * Verifies the addition of a single ephemeral network using
     * {@link WifiConfigManager#addOrUpdateNetwork(WifiConfiguration, int)} and verifies that
     * the {@link WifiConfigManager#getSavedNetworks()} does not return this network.
     */
    @Test
    public void testAddSingleEphemeralNetwork() throws Exception {
        WifiConfiguration ephemeralNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        ephemeralNetwork.ephemeral = true;
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(ephemeralNetwork);

        verifyAddEphemeralNetworkToWifiConfigManager(ephemeralNetwork);

        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);

        // Ensure that this is not returned in the saved network list.
        assertTrue(mWifiConfigManager.getSavedNetworks().isEmpty());
    }

    /**
     * Verifies the addition of 2 networks (1 normal and 1 ephemeral) using
     * {@link WifiConfigManager#addOrUpdateNetwork(WifiConfiguration, int)} and ensures that
     * the ephemeral network configuration is not persisted in config store.
     */
    @Test
    public void testAddMultipleNetworksAndEnsureEphemeralNetworkNotPersisted() {
        WifiConfiguration ephemeralNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        ephemeralNetwork.ephemeral = true;
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();

        assertTrue(addNetworkToWifiConfigManager(ephemeralNetwork).isSuccess());
        assertTrue(addNetworkToWifiConfigManager(openNetwork).isSuccess());

        // The open network addition should trigger a store write.
        WifiConfigStoreData storeData = captureWriteStoreData();
        assertFalse(isNetworkInConfigStoreData(ephemeralNetwork, storeData));
        assertTrue(isNetworkInConfigStoreData(openNetwork, storeData));
    }

    /**
     * Verifies that the modification of a single open network using
     * {@link WifiConfigManager#addOrUpdateNetwork(WifiConfiguration, int)} with a UID which
     * has no permission to modify the network fails.
     */
    @Test
    public void testUpdateSingleOpenNetworkFailedDueToPermissionDenied() throws Exception {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(openNetwork);

        verifyAddNetworkToWifiConfigManager(openNetwork);

        // Now change BSSID of the network.
        assertAndSetNetworkBSSID(openNetwork, TEST_BSSID);

        // Deny permission for |UPDATE_UID|.
        doAnswer(new AnswerWithArguments() {
            public int answer(String permName, int uid) throws Exception {
                if (uid == TEST_CREATOR_UID) {
                    return PackageManager.PERMISSION_GRANTED;
                }
                return PackageManager.PERMISSION_DENIED;
            }
        }).when(mFrameworkFacade).checkUidPermission(anyString(), anyInt());

        // Update the same configuration and ensure that the operation failed.
        NetworkUpdateResult result = updateNetworkToWifiConfigManager(openNetwork);
        assertTrue(result.getNetworkId() == WifiConfiguration.INVALID_NETWORK_ID);
    }

    /**
     * Verifies that the modification of a single open network using
     * {@link WifiConfigManager#addOrUpdateNetwork(WifiConfiguration, int)} with the creator UID
     * should always succeed.
     */
    @Test
    public void testUpdateSingleOpenNetworkSuccessWithCreatorUID() throws Exception {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(openNetwork);

        verifyAddNetworkToWifiConfigManager(openNetwork);

        // Now change BSSID of the network.
        assertAndSetNetworkBSSID(openNetwork, TEST_BSSID);

        // Deny permission for all UIDs.
        doAnswer(new AnswerWithArguments() {
            public int answer(String permName, int uid) throws Exception {
                return PackageManager.PERMISSION_DENIED;
            }
        }).when(mFrameworkFacade).checkUidPermission(anyString(), anyInt());

        // Update the same configuration using the creator UID.
        NetworkUpdateResult result =
                mWifiConfigManager.addOrUpdateNetwork(openNetwork, TEST_CREATOR_UID);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);

        // Now verify that the modification has been effective.
        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);
    }

    /**
     * Verifies the addition of a single PSK network using
     * {@link WifiConfigManager#addOrUpdateNetwork(WifiConfiguration, int)} and verifies that
     * {@link WifiConfigManager#getSavedNetworks()} masks the password.
     */
    @Test
    public void testAddSinglePskNetwork() {
        WifiConfiguration pskNetwork = WifiConfigurationTestUtil.createPskNetwork();
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(pskNetwork);

        verifyAddNetworkToWifiConfigManager(pskNetwork);

        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);

        List<WifiConfiguration> retrievedSavedNetworks = mWifiConfigManager.getSavedNetworks();
        assertEquals(retrievedSavedNetworks.size(), 1);
        assertEquals(retrievedSavedNetworks.get(0).configKey(), pskNetwork.configKey());
        assertPasswordsMaskedInWifiConfiguration(retrievedSavedNetworks.get(0));
    }

    /**
     * Verifies the addition of a single WEP network using
     * {@link WifiConfigManager#addOrUpdateNetwork(WifiConfiguration, int)} and verifies that
     * {@link WifiConfigManager#getSavedNetworks()} masks the password.
     */
    @Test
    public void testAddSingleWepNetwork() {
        WifiConfiguration wepNetwork = WifiConfigurationTestUtil.createWepNetwork();
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(wepNetwork);

        verifyAddNetworkToWifiConfigManager(wepNetwork);

        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);

        List<WifiConfiguration> retrievedSavedNetworks = mWifiConfigManager.getSavedNetworks();
        assertEquals(retrievedSavedNetworks.size(), 1);
        assertEquals(retrievedSavedNetworks.get(0).configKey(), wepNetwork.configKey());
        assertPasswordsMaskedInWifiConfiguration(retrievedSavedNetworks.get(0));
    }

    /**
     * Verifies the modification of an IpConfiguration using
     * {@link WifiConfigManager#addOrUpdateNetwork(WifiConfiguration, int)}
     */
    @Test
    public void testUpdateIpConfiguration() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(openNetwork);

        verifyAddNetworkToWifiConfigManager(openNetwork);

        // Now change BSSID of the network.
        assertAndSetNetworkBSSID(openNetwork, TEST_BSSID);

        // Update the same configuration and ensure that the IP configuration change flags
        // are not set.
        verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(openNetwork);

        // Change the IpConfiguration now and ensure that the IP configuration flags are set now.
        assertAndSetNetworkIpConfiguration(
                openNetwork,
                WifiConfigurationTestUtil.createStaticIpConfigurationWithStaticProxy());
        verifyUpdateNetworkToWifiConfigManagerWithIpChange(openNetwork);

        // Now verify that all the modifications have been effective.
        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);
    }

    /**
     * Verifies the removal of a single network using
     * {@link WifiConfigManager#removeNetwork(int)}
     */
    @Test
    public void testRemoveSingleOpenNetwork() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();

        verifyAddNetworkToWifiConfigManager(openNetwork);
        // Ensure that configured network list is not empty.
        assertFalse(mWifiConfigManager.getConfiguredNetworks().isEmpty());

        verifyRemoveNetworkFromWifiConfigManager(openNetwork);
        // Ensure that configured network list is empty now.
        assertTrue(mWifiConfigManager.getConfiguredNetworks().isEmpty());
    }

    /**
     * Verifies the removal of an ephemeral network using
     * {@link WifiConfigManager#removeNetwork(int)}
     */
    @Test
    public void testRemoveSingleEphemeralNetwork() throws Exception {
        WifiConfiguration ephemeralNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        ephemeralNetwork.ephemeral = true;

        verifyAddEphemeralNetworkToWifiConfigManager(ephemeralNetwork);
        // Ensure that configured network list is not empty.
        assertFalse(mWifiConfigManager.getConfiguredNetworks().isEmpty());

        verifyRemoveEphemeralNetworkFromWifiConfigManager(ephemeralNetwork);
        // Ensure that configured network list is empty now.
        assertTrue(mWifiConfigManager.getConfiguredNetworks().isEmpty());
    }

    /**
     * Verifies the addition & update of multiple networks using
     * {@link WifiConfigManager#addOrUpdateNetwork(WifiConfiguration, int)} and the
     * removal of networks using
     * {@link WifiConfigManager#removeNetwork(int)}
     */
    @Test
    public void testAddUpdateRemoveMultipleNetworks() {
        List<WifiConfiguration> networks = new ArrayList<>();
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        WifiConfiguration pskNetwork = WifiConfigurationTestUtil.createPskNetwork();
        WifiConfiguration wepNetwork = WifiConfigurationTestUtil.createWepNetwork();
        networks.add(openNetwork);
        networks.add(pskNetwork);
        networks.add(wepNetwork);

        verifyAddNetworkToWifiConfigManager(openNetwork);
        verifyAddNetworkToWifiConfigManager(pskNetwork);
        verifyAddNetworkToWifiConfigManager(wepNetwork);

        // Now verify that all the additions has been effective.
        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);

        // Modify all the 3 configurations and update it to WifiConfigManager.
        assertAndSetNetworkBSSID(openNetwork, TEST_BSSID);
        assertAndSetNetworkBSSID(pskNetwork, TEST_BSSID);
        assertAndSetNetworkIpConfiguration(
                wepNetwork,
                WifiConfigurationTestUtil.createStaticIpConfigurationWithPacProxy());

        verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(openNetwork);
        verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(pskNetwork);
        verifyUpdateNetworkToWifiConfigManagerWithIpChange(wepNetwork);
        // Now verify that all the modifications has been effective.
        retrievedNetworks = mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);

        // Now remove all 3 networks.
        verifyRemoveNetworkFromWifiConfigManager(openNetwork);
        verifyRemoveNetworkFromWifiConfigManager(pskNetwork);
        verifyRemoveNetworkFromWifiConfigManager(wepNetwork);

        // Ensure that configured network list is empty now.
        assertTrue(mWifiConfigManager.getConfiguredNetworks().isEmpty());
    }

    /**
     * Verifies the update of network status using
     * {@link WifiConfigManager#updateNetworkSelectionStatus(int, int)}.
     */
    @Test
    public void testNetworkSelectionStatus() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();

        NetworkUpdateResult result = verifyAddNetworkToWifiConfigManager(openNetwork);

        // First set it to enabled.
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.NETWORK_SELECTION_ENABLE, 0);

        // Now set it to temporarily disabled. The threshold for association rejection is 5, so
        // disable it 5 times to actually mark it temporarily disabled.
        int assocRejectReason = NetworkSelectionStatus.DISABLED_ASSOCIATION_REJECTION;
        int assocRejectThreshold =
                WifiConfigManager.NETWORK_SELECTION_DISABLE_THRESHOLD[assocRejectReason];
        for (int i = 1; i <= assocRejectThreshold; i++) {
            verifyUpdateNetworkSelectionStatus(result.getNetworkId(), assocRejectReason, i);
        }

        // Now set it to permanently disabled.
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.DISABLED_BY_WIFI_MANAGER, 0);

        // Now set it back to enabled.
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.NETWORK_SELECTION_ENABLE, 0);
    }

    /**
     * Verifies the update of network status using
     * {@link WifiConfigManager#updateNetworkSelectionStatus(int, int)} and ensures that
     * enabling a network clears out all the temporary disable counters.
     */
    @Test
    public void testNetworkSelectionStatusEnableClearsDisableCounters() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();

        NetworkUpdateResult result = verifyAddNetworkToWifiConfigManager(openNetwork);

        // First set it to enabled.
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.NETWORK_SELECTION_ENABLE, 0);

        // Now set it to temporarily disabled 2 times for 2 different reasons.
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.DISABLED_ASSOCIATION_REJECTION, 1);
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.DISABLED_ASSOCIATION_REJECTION, 2);
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.DISABLED_AUTHENTICATION_FAILURE, 1);
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.DISABLED_AUTHENTICATION_FAILURE, 2);

        // Now set it back to enabled.
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.NETWORK_SELECTION_ENABLE, 0);

        // Ensure that the counters have all been reset now.
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.DISABLED_ASSOCIATION_REJECTION, 1);
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.DISABLED_AUTHENTICATION_FAILURE, 1);
    }

    /**
     * Verifies the enabling of temporarily disabled network using
     * {@link WifiConfigManager#tryEnableNetwork(int)}.
     */
    @Test
    public void testTryEnableNetwork() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();

        NetworkUpdateResult result = verifyAddNetworkToWifiConfigManager(openNetwork);

        // First set it to enabled.
        verifyUpdateNetworkSelectionStatus(
                result.getNetworkId(), NetworkSelectionStatus.NETWORK_SELECTION_ENABLE, 0);

        // Now set it to temporarily disabled. The threshold for association rejection is 5, so
        // disable it 5 times to actually mark it temporarily disabled.
        int assocRejectReason = NetworkSelectionStatus.DISABLED_ASSOCIATION_REJECTION;
        int assocRejectThreshold =
                WifiConfigManager.NETWORK_SELECTION_DISABLE_THRESHOLD[assocRejectReason];
        for (int i = 1; i <= assocRejectThreshold; i++) {
            verifyUpdateNetworkSelectionStatus(result.getNetworkId(), assocRejectReason, i);
        }

        // Now let's try enabling this network without changing the time, this should fail and the
        // status remains temporarily disabled.
        assertFalse(mWifiConfigManager.tryEnableNetwork(result.getNetworkId()));
        NetworkSelectionStatus retrievedStatus =
                mWifiConfigManager.getConfiguredNetwork(result.getNetworkId())
                        .getNetworkSelectionStatus();
        assertTrue(retrievedStatus.isNetworkTemporaryDisabled());

        // Now advance time by the timeout for association rejection and ensure that the network
        // is now enabled.
        int assocRejectTimeout =
                WifiConfigManager.NETWORK_SELECTION_DISABLE_TIMEOUT_MS[assocRejectReason];
        when(mClock.getElapsedSinceBootMillis())
                .thenReturn(TEST_ELAPSED_UPDATE_NETWORK_SELECTION_TIME_MILLIS + assocRejectTimeout);

        assertTrue(mWifiConfigManager.tryEnableNetwork(result.getNetworkId()));
        retrievedStatus =
                mWifiConfigManager.getConfiguredNetwork(result.getNetworkId())
                        .getNetworkSelectionStatus();
        assertTrue(retrievedStatus.isNetworkEnabled());
    }

    /**
     * Verifies the enabling of network using
     * {@link WifiConfigManager#enableNetwork(int, boolean, int)} and
     * {@link WifiConfigManager#disableNetwork(int, int)}.
     */
    @Test
    public void testEnableDisableNetwork() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();

        NetworkUpdateResult result = verifyAddNetworkToWifiConfigManager(openNetwork);

        assertTrue(mWifiConfigManager.enableNetwork(
                result.getNetworkId(), false, TEST_CREATOR_UID));
        WifiConfiguration retrievedNetwork =
                mWifiConfigManager.getConfiguredNetwork(result.getNetworkId());
        NetworkSelectionStatus retrievedStatus = retrievedNetwork.getNetworkSelectionStatus();
        assertTrue(retrievedStatus.isNetworkEnabled());
        verifyUpdateNetworkStatus(retrievedNetwork, WifiConfiguration.Status.ENABLED);

        // Now set it disabled.
        assertTrue(mWifiConfigManager.disableNetwork(result.getNetworkId(), TEST_CREATOR_UID));
        retrievedNetwork = mWifiConfigManager.getConfiguredNetwork(result.getNetworkId());
        retrievedStatus = retrievedNetwork.getNetworkSelectionStatus();
        assertTrue(retrievedStatus.isNetworkPermanentlyDisabled());
        verifyUpdateNetworkStatus(retrievedNetwork, WifiConfiguration.Status.DISABLED);
    }

    /**
     * Verifies the enabling of network using
     * {@link WifiConfigManager#enableNetwork(int, boolean, int)} with a UID which
     * has no permission to modify the network fails..
     */
    @Test
    public void testEnableDisableNetworkFailedDueToPermissionDenied() throws Exception {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();

        NetworkUpdateResult result = verifyAddNetworkToWifiConfigManager(openNetwork);

        assertTrue(mWifiConfigManager.enableNetwork(
                result.getNetworkId(), false, TEST_CREATOR_UID));
        WifiConfiguration retrievedNetwork =
                mWifiConfigManager.getConfiguredNetwork(result.getNetworkId());
        NetworkSelectionStatus retrievedStatus = retrievedNetwork.getNetworkSelectionStatus();
        assertTrue(retrievedStatus.isNetworkEnabled());
        verifyUpdateNetworkStatus(retrievedNetwork, WifiConfiguration.Status.ENABLED);

        // Deny permission for |UPDATE_UID|.
        doAnswer(new AnswerWithArguments() {
            public int answer(String permName, int uid) throws Exception {
                if (uid == TEST_CREATOR_UID) {
                    return PackageManager.PERMISSION_GRANTED;
                }
                return PackageManager.PERMISSION_DENIED;
            }
        }).when(mFrameworkFacade).checkUidPermission(anyString(), anyInt());

        // Now try to set it disabled with |TEST_UPDATE_UID|, it should fail and the network
        // should remain enabled.
        assertFalse(mWifiConfigManager.disableNetwork(result.getNetworkId(), TEST_UPDATE_UID));
        retrievedStatus =
                mWifiConfigManager.getConfiguredNetwork(result.getNetworkId())
                        .getNetworkSelectionStatus();
        assertTrue(retrievedStatus.isNetworkEnabled());
        assertEquals(WifiConfiguration.Status.ENABLED, retrievedNetwork.status);
    }

    /**
     * Verifies the updation of network's connectUid using
     * {@link WifiConfigManager#checkAndUpdateLastConnectUid(int, int)}.
     */
    @Test
    public void testUpdateLastConnectUid() throws Exception {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();

        NetworkUpdateResult result = verifyAddNetworkToWifiConfigManager(openNetwork);

        assertTrue(
                mWifiConfigManager.checkAndUpdateLastConnectUid(
                        result.getNetworkId(), TEST_CREATOR_UID));
        WifiConfiguration retrievedNetwork =
                mWifiConfigManager.getConfiguredNetwork(result.getNetworkId());
        assertEquals(TEST_CREATOR_UID, retrievedNetwork.lastConnectUid);

        // Deny permission for |UPDATE_UID|.
        doAnswer(new AnswerWithArguments() {
            public int answer(String permName, int uid) throws Exception {
                if (uid == TEST_CREATOR_UID) {
                    return PackageManager.PERMISSION_GRANTED;
                }
                return PackageManager.PERMISSION_DENIED;
            }
        }).when(mFrameworkFacade).checkUidPermission(anyString(), anyInt());

        // Now try to update the last connect UID with |TEST_UPDATE_UID|, it should fail and
        // the lastConnectUid should remain the same.
        assertFalse(
                mWifiConfigManager.checkAndUpdateLastConnectUid(
                        result.getNetworkId(), TEST_UPDATE_UID));
        retrievedNetwork = mWifiConfigManager.getConfiguredNetwork(result.getNetworkId());
        assertEquals(TEST_CREATOR_UID, retrievedNetwork.lastConnectUid);
    }

    /**
     * Verifies that any configuration update attempt with an null config is gracefully
     * handled.
     * This invokes {@link WifiConfigManager#addOrUpdateNetwork(WifiConfiguration, int)}.
     */
    @Test
    public void testAddOrUpdateNetworkWithNullConfig() {
        NetworkUpdateResult result = mWifiConfigManager.addOrUpdateNetwork(null, TEST_CREATOR_UID);
        assertFalse(result.isSuccess());
    }

    /**
     * Verifies that any configuration removal attempt with an invalid networkID is gracefully
     * handled.
     * This invokes {@link WifiConfigManager#removeNetwork(int)}.
     */
    @Test
    public void testRemoveNetworkWithInvalidNetworkId() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();

        verifyAddNetworkToWifiConfigManager(openNetwork);

        // Change the networkID to an invalid one.
        openNetwork.networkId++;
        assertFalse(mWifiConfigManager.removeNetwork(openNetwork.networkId, TEST_CREATOR_UID));
    }

    /**
     * Verifies that any configuration update attempt with an invalid networkID is gracefully
     * handled.
     * This invokes {@link WifiConfigManager#enableNetwork(int, boolean, int)},
     * {@link WifiConfigManager#disableNetwork(int, int)},
     * {@link WifiConfigManager#updateNetworkSelectionStatus(int, int)} and
     * {@link WifiConfigManager#checkAndUpdateLastConnectUid(int, int)}.
     */
    @Test
    public void testChangeConfigurationWithInvalidNetworkId() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();

        NetworkUpdateResult result = verifyAddNetworkToWifiConfigManager(openNetwork);

        assertFalse(mWifiConfigManager.enableNetwork(
                result.getNetworkId() + 1, false, TEST_CREATOR_UID));
        assertFalse(mWifiConfigManager.disableNetwork(result.getNetworkId() + 1, TEST_CREATOR_UID));
        assertFalse(mWifiConfigManager.updateNetworkSelectionStatus(
                result.getNetworkId() + 1, NetworkSelectionStatus.DISABLED_BY_WIFI_MANAGER));
        assertFalse(mWifiConfigManager.checkAndUpdateLastConnectUid(
                result.getNetworkId() + 1, TEST_CREATOR_UID));
    }

    /**
     * Verifies multiple modification of a single network using
     * {@link WifiConfigManager#addOrUpdateNetwork(WifiConfiguration, int)}.
     * This test is basically checking if the apps can reset some of the fields of the config after
     * addition. The fields being reset in this test are the |preSharedKey| and |wepKeys|.
     * 1. Create an open network initially.
     * 2. Modify the added network config to a WEP network config with all the 4 keys set.
     * 3. Modify the added network config to a WEP network config with only 1 key set.
     * 4. Modify the added network config to a PSK network config.
     */
    @Test
    public void testMultipleUpdatesSingleNetwork() {
        WifiConfiguration network = WifiConfigurationTestUtil.createOpenNetwork();
        verifyAddNetworkToWifiConfigManager(network);

        // Now add |wepKeys| to the network. We don't need to update the |allowedKeyManagement|
        // fields for open to WEP conversion.
        String[] wepKeys =
                Arrays.copyOf(WifiConfigurationTestUtil.TEST_WEP_KEYS,
                        WifiConfigurationTestUtil.TEST_WEP_KEYS.length);
        int wepTxKeyIdx = WifiConfigurationTestUtil.TEST_WEP_TX_KEY_INDEX;
        assertAndSetNetworkWepKeysAndTxIndex(network, wepKeys, wepTxKeyIdx);

        verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(network);
        WifiConfigurationTestUtil.assertConfigurationEqualForConfigManagerAddOrUpdate(
                network, mWifiConfigManager.getConfiguredNetworkWithPassword(network.networkId));

        // Now empty out 3 of the |wepKeys[]| and ensure that those keys have been reset correctly.
        for (int i = 1; i < network.wepKeys.length; i++) {
            wepKeys[i] = "";
        }
        wepTxKeyIdx = 0;
        assertAndSetNetworkWepKeysAndTxIndex(network, wepKeys, wepTxKeyIdx);

        verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(network);
        WifiConfigurationTestUtil.assertConfigurationEqualForConfigManagerAddOrUpdate(
                network, mWifiConfigManager.getConfiguredNetworkWithPassword(network.networkId));

        // Now change the config to a PSK network config by resetting the remaining |wepKey[0]|
        // field and setting the |preSharedKey| and |allowedKeyManagement| fields.
        wepKeys[0] = "";
        wepTxKeyIdx = -1;
        assertAndSetNetworkWepKeysAndTxIndex(network, wepKeys, wepTxKeyIdx);
        network.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        assertAndSetNetworkPreSharedKey(network, WifiConfigurationTestUtil.TEST_PSK);

        verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(network);
        WifiConfigurationTestUtil.assertConfigurationEqualForConfigManagerAddOrUpdate(
                network, mWifiConfigManager.getConfiguredNetworkWithPassword(network.networkId));
    }

    /**
     * Verifies the modification of a WifiEnteriseConfig using
     * {@link WifiConfigManager#addOrUpdateNetwork(WifiConfiguration, int)}.
     */
    @Test
    public void testUpdateWifiEnterpriseConfig() {
        WifiConfiguration network = WifiConfigurationTestUtil.createEapNetwork();
        verifyAddNetworkToWifiConfigManager(network);

        // Set the |password| field in WifiEnterpriseConfig and modify the config to PEAP/GTC.
        network.enterpriseConfig =
                WifiConfigurationTestUtil.createPEAPWifiEnterpriseConfigWithGTCPhase2();
        assertAndSetNetworkEnterprisePassword(network, "test");

        verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(network);
        WifiConfigurationTestUtil.assertConfigurationEqualForConfigManagerAddOrUpdate(
                network, mWifiConfigManager.getConfiguredNetworkWithPassword(network.networkId));

        // Reset the |password| field in WifiEnterpriseConfig and modify the config to TLS/None.
        network.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
        network.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.NONE);
        assertAndSetNetworkEnterprisePassword(network, "");

        verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(network);
        WifiConfigurationTestUtil.assertConfigurationEqualForConfigManagerAddOrUpdate(
                network, mWifiConfigManager.getConfiguredNetworkWithPassword(network.networkId));
    }

    /**
     * Verifies the modification of a single network using
     * {@link WifiConfigManager#addOrUpdateNetwork(WifiConfiguration, int)} by passing in nulls
     * in all the publicly exposed fields.
     */
    @Test
    public void testUpdateSingleNetworkWithNullValues() {
        WifiConfiguration network = WifiConfigurationTestUtil.createEapNetwork();
        verifyAddNetworkToWifiConfigManager(network);

        // Save a copy of the original network for comparison.
        WifiConfiguration originalNetwork = new WifiConfiguration(network);

        // Now set all the public fields to null and try updating the network.
        network.allowedAuthAlgorithms = null;
        network.allowedProtocols = null;
        network.allowedKeyManagement = null;
        network.allowedPairwiseCiphers = null;
        network.allowedGroupCiphers = null;
        network.setIpConfiguration(null);
        network.enterpriseConfig = null;

        verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(network);

        // Copy over the updated debug params to the original network config before comparison.
        originalNetwork.lastUpdateUid = network.lastUpdateUid;
        originalNetwork.lastUpdateName = network.lastUpdateName;
        originalNetwork.updateTime = network.updateTime;

        // Now verify that there was no change to the network configurations.
        WifiConfigurationTestUtil.assertConfigurationEqualForConfigManagerAddOrUpdate(
                originalNetwork,
                mWifiConfigManager.getConfiguredNetworkWithPassword(originalNetwork.networkId));
    }

    /**
     * Verifies that the modification of a single network using
     * {@link WifiConfigManager#addOrUpdateNetwork(WifiConfiguration, int)} does not modify
     * existing configuration if there is a failure.
     */
    @Test
    public void testUpdateSingleNetworkFailureDoesNotModifyOriginal() {
        WifiConfiguration network = WifiConfigurationTestUtil.createEapNetwork();
        network.enterpriseConfig =
                WifiConfigurationTestUtil.createPEAPWifiEnterpriseConfigWithGTCPhase2();
        verifyAddNetworkToWifiConfigManager(network);

        // Save a copy of the original network for comparison.
        WifiConfiguration originalNetwork = new WifiConfiguration(network);

        // Now modify the network's EAP method.
        network.enterpriseConfig =
                WifiConfigurationTestUtil.createTLSWifiEnterpriseConfigWithNonePhase2();

        // Fail this update because of cert installation failure.
        when(mWifiKeyStore
                .updateNetworkKeys(any(WifiConfiguration.class), any(WifiConfiguration.class)))
                .thenReturn(false);
        NetworkUpdateResult result =
                mWifiConfigManager.addOrUpdateNetwork(network, TEST_UPDATE_UID);
        assertTrue(result.getNetworkId() == WifiConfiguration.INVALID_NETWORK_ID);

        // Now verify that there was no change to the network configurations.
        WifiConfigurationTestUtil.assertConfigurationEqualForConfigManagerAddOrUpdate(
                originalNetwork,
                mWifiConfigManager.getConfiguredNetworkWithPassword(originalNetwork.networkId));
    }

    /**
     * Verifies the matching of networks with different encryption types with the
     * corresponding scan detail using
     * {@link WifiConfigManager#getSavedNetworkForScanDetailAndCache(ScanDetail)}.
     * The test also verifies that the provided scan detail was cached,
     */
    @Test
    public void testMatchScanDetailToNetworksAndCache() {
        // Create networks of different types and ensure that they're all matched using
        // the corresponding ScanDetail correctly.
        verifyAddSingleNetworkAndMatchScanDetailToNetworkAndCache(
                WifiConfigurationTestUtil.createOpenNetwork());
        verifyAddSingleNetworkAndMatchScanDetailToNetworkAndCache(
                WifiConfigurationTestUtil.createWepNetwork());
        verifyAddSingleNetworkAndMatchScanDetailToNetworkAndCache(
                WifiConfigurationTestUtil.createPskNetwork());
        verifyAddSingleNetworkAndMatchScanDetailToNetworkAndCache(
                WifiConfigurationTestUtil.createEapNetwork());
    }

    /**
     * Verifies that scan details with wrong SSID/authentication types are not matched using
     * {@link WifiConfigManager#getSavedNetworkForScanDetailAndCache(ScanDetail)}
     * to the added networks.
     */
    @Test
    public void testNoMatchScanDetailToNetwork() {
        // First create networks of different types.
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        WifiConfiguration wepNetwork = WifiConfigurationTestUtil.createWepNetwork();
        WifiConfiguration pskNetwork = WifiConfigurationTestUtil.createPskNetwork();
        WifiConfiguration eapNetwork = WifiConfigurationTestUtil.createEapNetwork();

        // Now add them to WifiConfigManager.
        verifyAddNetworkToWifiConfigManager(openNetwork);
        verifyAddNetworkToWifiConfigManager(wepNetwork);
        verifyAddNetworkToWifiConfigManager(pskNetwork);
        verifyAddNetworkToWifiConfigManager(eapNetwork);

        // Now create dummy scan detail corresponding to the networks.
        ScanDetail openNetworkScanDetail = createScanDetailForNetwork(openNetwork);
        ScanDetail wepNetworkScanDetail = createScanDetailForNetwork(wepNetwork);
        ScanDetail pskNetworkScanDetail = createScanDetailForNetwork(pskNetwork);
        ScanDetail eapNetworkScanDetail = createScanDetailForNetwork(eapNetwork);

        // Now mix and match parameters from different scan details.
        openNetworkScanDetail.getScanResult().SSID =
                wepNetworkScanDetail.getScanResult().SSID;
        wepNetworkScanDetail.getScanResult().capabilities =
                pskNetworkScanDetail.getScanResult().capabilities;
        pskNetworkScanDetail.getScanResult().capabilities =
                eapNetworkScanDetail.getScanResult().capabilities;
        eapNetworkScanDetail.getScanResult().capabilities =
                openNetworkScanDetail.getScanResult().capabilities;

        // Try to lookup a saved network using the modified scan details. All of these should fail.
        assertNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(openNetworkScanDetail));
        assertNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(wepNetworkScanDetail));
        assertNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(pskNetworkScanDetail));
        assertNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(eapNetworkScanDetail));

        // All the cache's should be empty as well.
        assertNull(mWifiConfigManager.getScanDetailCacheForNetwork(openNetwork.networkId));
        assertNull(mWifiConfigManager.getScanDetailCacheForNetwork(wepNetwork.networkId));
        assertNull(mWifiConfigManager.getScanDetailCacheForNetwork(pskNetwork.networkId));
        assertNull(mWifiConfigManager.getScanDetailCacheForNetwork(eapNetwork.networkId));
    }

    /**
     * Verifies that scan detail cache is trimmed down when the size of the cache for a network
     * exceeds {@link WifiConfigManager#SCAN_CACHE_ENTRIES_MAX_SIZE}.
     */
    @Test
    public void testScanDetailCacheTrimForNetwork() {
        // Add a single network.
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        verifyAddNetworkToWifiConfigManager(openNetwork);

        ScanDetailCache scanDetailCache;
        String testBssidPrefix = "00:a5:b8:c9:45:";

        // Modify |BSSID| field in the scan result and add copies of scan detail
        // |SCAN_CACHE_ENTRIES_MAX_SIZE| times.
        int scanDetailNum = 1;
        for (; scanDetailNum <= WifiConfigManager.SCAN_CACHE_ENTRIES_MAX_SIZE; scanDetailNum++) {
            // Create dummy scan detail caches with different BSSID for the network.
            ScanDetail scanDetail =
                    createScanDetailForNetwork(
                            openNetwork, String.format("%s%02x", testBssidPrefix, scanDetailNum));
            assertNotNull(
                    mWifiConfigManager.getSavedNetworkForScanDetailAndCache(scanDetail));

            // The size of scan detail cache should keep growing until it hits
            // |SCAN_CACHE_ENTRIES_MAX_SIZE|.
            scanDetailCache =
                    mWifiConfigManager.getScanDetailCacheForNetwork(openNetwork.networkId);
            assertEquals(scanDetailNum, scanDetailCache.size());
        }

        // Now add the |SCAN_CACHE_ENTRIES_MAX_SIZE + 1| entry. This should trigger the trim.
        ScanDetail scanDetail =
                createScanDetailForNetwork(
                        openNetwork, String.format("%s%02x", testBssidPrefix, scanDetailNum));
        assertNotNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(scanDetail));

        // Retrieve the scan detail cache and ensure that the size was trimmed down to
        // |SCAN_CACHE_ENTRIES_TRIM_SIZE + 1|. The "+1" is to account for the new entry that
        // was added after the trim.
        scanDetailCache = mWifiConfigManager.getScanDetailCacheForNetwork(openNetwork.networkId);
        assertEquals(WifiConfigManager.SCAN_CACHE_ENTRIES_TRIM_SIZE + 1, scanDetailCache.size());
    }

    /**
     * Verifies that hasEverConnected is false for a newly added network.
     */
    @Test
    public void testAddNetworkHasEverConnectedFalse() {
        verifyAddNetworkHasEverConnectedFalse(WifiConfigurationTestUtil.createOpenNetwork());
    }

    /**
     * Verifies that hasEverConnected is false for a newly added network even when new config has
     * mistakenly set HasEverConnected to true.
     */
    @Test
    public void testAddNetworkOverridesHasEverConnectedWhenTrueInNewConfig() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        openNetwork.getNetworkSelectionStatus().setHasEverConnected(true);
        verifyAddNetworkHasEverConnectedFalse(openNetwork);
    }

    /**
     * Verify that the |HasEverConnected| is set when
     * {@link WifiConfigManager#updateNetworkAfterConnect(int)} is invoked.
     */
    @Test
    public void testUpdateConfigAfterConnectHasEverConnectedTrue() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        verifyAddNetworkHasEverConnectedFalse(openNetwork);
        verifyUpdateNetworkAfterConnectHasEverConnectedTrue(openNetwork.networkId);
    }

    /**
     * Verifies that hasEverConnected is cleared when a network config |preSharedKey| is updated.
     */
    @Test
    public void testUpdatePreSharedKeyClearsHasEverConnected() {
        WifiConfiguration pskNetwork = WifiConfigurationTestUtil.createPskNetwork();
        verifyAddNetworkHasEverConnectedFalse(pskNetwork);
        verifyUpdateNetworkAfterConnectHasEverConnectedTrue(pskNetwork.networkId);

        // Now update the same network with a different psk.
        assertFalse(pskNetwork.preSharedKey.equals("newpassword"));
        pskNetwork.preSharedKey = "newpassword";
        verifyUpdateNetworkWithCredentialChangeHasEverConnectedFalse(pskNetwork);
    }

    /**
     * Verifies that hasEverConnected is cleared when a network config |wepKeys| is updated.
     */
    @Test
    public void testUpdateWepKeysClearsHasEverConnected() {
        WifiConfiguration wepNetwork = WifiConfigurationTestUtil.createWepNetwork();
        verifyAddNetworkHasEverConnectedFalse(wepNetwork);
        verifyUpdateNetworkAfterConnectHasEverConnectedTrue(wepNetwork.networkId);

        // Now update the same network with a different wep.
        assertFalse(wepNetwork.wepKeys[0].equals("newpassword"));
        wepNetwork.wepKeys[0] = "newpassword";
        verifyUpdateNetworkWithCredentialChangeHasEverConnectedFalse(wepNetwork);
    }

    /**
     * Verifies that hasEverConnected is cleared when a network config |wepTxKeyIndex| is updated.
     */
    @Test
    public void testUpdateWepTxKeyClearsHasEverConnected() {
        WifiConfiguration wepNetwork = WifiConfigurationTestUtil.createWepNetwork();
        verifyAddNetworkHasEverConnectedFalse(wepNetwork);
        verifyUpdateNetworkAfterConnectHasEverConnectedTrue(wepNetwork.networkId);

        // Now update the same network with a different wep.
        assertFalse(wepNetwork.wepTxKeyIndex == 3);
        wepNetwork.wepTxKeyIndex = 3;
        verifyUpdateNetworkWithCredentialChangeHasEverConnectedFalse(wepNetwork);
    }

    /**
     * Verifies that hasEverConnected is cleared when a network config |allowedKeyManagement| is
     * updated.
     */
    @Test
    public void testUpdateAllowedKeyManagementClearsHasEverConnected() {
        WifiConfiguration pskNetwork = WifiConfigurationTestUtil.createPskNetwork();
        verifyAddNetworkHasEverConnectedFalse(pskNetwork);
        verifyUpdateNetworkAfterConnectHasEverConnectedTrue(pskNetwork.networkId);

        assertFalse(pskNetwork.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X));
        pskNetwork.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X);
        verifyUpdateNetworkWithCredentialChangeHasEverConnectedFalse(pskNetwork);
    }

    /**
     * Verifies that hasEverConnected is cleared when a network config |allowedProtocol| is
     * updated.
     */
    @Test
    public void testUpdateProtocolsClearsHasEverConnected() {
        WifiConfiguration pskNetwork = WifiConfigurationTestUtil.createPskNetwork();
        verifyAddNetworkHasEverConnectedFalse(pskNetwork);
        verifyUpdateNetworkAfterConnectHasEverConnectedTrue(pskNetwork.networkId);

        assertFalse(pskNetwork.allowedProtocols.get(WifiConfiguration.Protocol.OSEN));
        pskNetwork.allowedProtocols.set(WifiConfiguration.Protocol.OSEN);
        verifyUpdateNetworkWithCredentialChangeHasEverConnectedFalse(pskNetwork);
    }

    /**
     * Verifies that hasEverConnected is cleared when a network config |allowedAuthAlgorithms| is
     * updated.
     */
    @Test
    public void testUpdateAllowedAuthAlgorithmsClearsHasEverConnected() {
        WifiConfiguration pskNetwork = WifiConfigurationTestUtil.createPskNetwork();
        verifyAddNetworkHasEverConnectedFalse(pskNetwork);
        verifyUpdateNetworkAfterConnectHasEverConnectedTrue(pskNetwork.networkId);

        assertFalse(pskNetwork.allowedAuthAlgorithms.get(WifiConfiguration.AuthAlgorithm.LEAP));
        pskNetwork.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.LEAP);
        verifyUpdateNetworkWithCredentialChangeHasEverConnectedFalse(pskNetwork);
    }

    /**
     * Verifies that hasEverConnected is cleared when a network config |allowedPairwiseCiphers| is
     * updated.
     */
    @Test
    public void testUpdateAllowedPairwiseCiphersClearsHasEverConnected() {
        WifiConfiguration pskNetwork = WifiConfigurationTestUtil.createPskNetwork();
        verifyAddNetworkHasEverConnectedFalse(pskNetwork);
        verifyUpdateNetworkAfterConnectHasEverConnectedTrue(pskNetwork.networkId);

        assertFalse(pskNetwork.allowedPairwiseCiphers.get(WifiConfiguration.PairwiseCipher.NONE));
        pskNetwork.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.NONE);
        verifyUpdateNetworkWithCredentialChangeHasEverConnectedFalse(pskNetwork);
    }

    /**
     * Verifies that hasEverConnected is cleared when a network config |allowedGroup| is
     * updated.
     */
    @Test
    public void testUpdateAllowedGroupCiphersClearsHasEverConnected() {
        WifiConfiguration pskNetwork = WifiConfigurationTestUtil.createPskNetwork();
        verifyAddNetworkHasEverConnectedFalse(pskNetwork);
        verifyUpdateNetworkAfterConnectHasEverConnectedTrue(pskNetwork.networkId);

        assertTrue(pskNetwork.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.WEP104));
        pskNetwork.allowedGroupCiphers.clear(WifiConfiguration.GroupCipher.WEP104);
        verifyUpdateNetworkWithCredentialChangeHasEverConnectedFalse(pskNetwork);
    }

    /**
     * Verifies that hasEverConnected is cleared when a network config |hiddenSSID| is
     * updated.
     */
    @Test
    public void testUpdateHiddenSSIDClearsHasEverConnected() {
        WifiConfiguration pskNetwork = WifiConfigurationTestUtil.createPskNetwork();
        verifyAddNetworkHasEverConnectedFalse(pskNetwork);
        verifyUpdateNetworkAfterConnectHasEverConnectedTrue(pskNetwork.networkId);

        assertFalse(pskNetwork.hiddenSSID);
        pskNetwork.hiddenSSID = true;
        verifyUpdateNetworkWithCredentialChangeHasEverConnectedFalse(pskNetwork);
    }

    /**
     * Verifies that hasEverConnected is not cleared when a network config |requirePMF| is
     * updated.
     */
    @Test
    public void testUpdateRequirePMFDoesNotClearHasEverConnected() {
        WifiConfiguration pskNetwork = WifiConfigurationTestUtil.createPskNetwork();
        verifyAddNetworkHasEverConnectedFalse(pskNetwork);
        verifyUpdateNetworkAfterConnectHasEverConnectedTrue(pskNetwork.networkId);

        assertFalse(pskNetwork.requirePMF);
        pskNetwork.requirePMF = true;

        NetworkUpdateResult result =
                verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(pskNetwork);
        WifiConfiguration retrievedNetwork =
                mWifiConfigManager.getConfiguredNetwork(result.getNetworkId());
        assertTrue("Updating network non-credentials config should not clear hasEverConnected.",
                retrievedNetwork.getNetworkSelectionStatus().getHasEverConnected());
    }

    /**
     * Verifies that hasEverConnected is cleared when a network config |enterpriseConfig| is
     * updated.
     */
    @Test
    public void testUpdateEnterpriseConfigClearsHasEverConnected() {
        WifiConfiguration eapNetwork = WifiConfigurationTestUtil.createEapNetwork();
        eapNetwork.enterpriseConfig =
                WifiConfigurationTestUtil.createPEAPWifiEnterpriseConfigWithGTCPhase2();
        verifyAddNetworkHasEverConnectedFalse(eapNetwork);
        verifyUpdateNetworkAfterConnectHasEverConnectedTrue(eapNetwork.networkId);

        assertFalse(eapNetwork.enterpriseConfig.getEapMethod() == WifiEnterpriseConfig.Eap.TLS);
        eapNetwork.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
        verifyUpdateNetworkWithCredentialChangeHasEverConnectedFalse(eapNetwork);
    }

    /**
     * Verifies the ordering of network list generated using
     * {@link WifiConfigManager#retrievePnoNetworkList()}.
     */
    @Test
    public void testRetrievePnoList() {
        // Create and add 3 networks.
        WifiConfiguration network1 = WifiConfigurationTestUtil.createEapNetwork();
        WifiConfiguration network2 = WifiConfigurationTestUtil.createPskNetwork();
        WifiConfiguration network3 = WifiConfigurationTestUtil.createOpenHiddenNetwork();
        verifyAddNetworkToWifiConfigManager(network1);
        verifyAddNetworkToWifiConfigManager(network2);
        verifyAddNetworkToWifiConfigManager(network3);

        // Enable all of them.
        assertTrue(mWifiConfigManager.enableNetwork(network1.networkId, false, TEST_CREATOR_UID));
        assertTrue(mWifiConfigManager.enableNetwork(network2.networkId, false, TEST_CREATOR_UID));
        assertTrue(mWifiConfigManager.enableNetwork(network3.networkId, false, TEST_CREATOR_UID));

        // Now set scan results in 2 of them to set the corresponding
        // {@link NetworkSelectionStatus#mSeenInLastQualifiedNetworkSelection} field.
        assertTrue(mWifiConfigManager.setNetworkCandidateScanResult(
                network1.networkId, createScanDetailForNetwork(network1).getScanResult(), 54));
        assertTrue(mWifiConfigManager.setNetworkCandidateScanResult(
                network3.networkId, createScanDetailForNetwork(network3).getScanResult(), 54));

        // Now increment |network3|'s association count. This should ensure that this network
        // is preferred over |network1|.
        assertTrue(mWifiConfigManager.updateNetworkAfterConnect(network3.networkId));

        // Retrieve the Pno network list & verify the order of the networks returned.
        List<WifiScanner.PnoSettings.PnoNetwork> pnoNetworks =
                mWifiConfigManager.retrievePnoNetworkList();
        assertEquals(3, pnoNetworks.size());
        assertEquals(network3.SSID, pnoNetworks.get(0).ssid);
        assertEquals(network1.SSID, pnoNetworks.get(1).ssid);
        assertEquals(network2.SSID, pnoNetworks.get(2).ssid);

        // Now permanently disable |network3|. This should remove network 3 from the list.
        assertTrue(mWifiConfigManager.disableNetwork(network3.networkId, TEST_CREATOR_UID));

        // Retrieve the Pno network list again & verify the order of the networks returned.
        pnoNetworks = mWifiConfigManager.retrievePnoNetworkList();
        assertEquals(2, pnoNetworks.size());
        assertEquals(network1.SSID, pnoNetworks.get(0).ssid);
        assertEquals(network2.SSID, pnoNetworks.get(1).ssid);
    }

    /**
     * Verifies the linking of networks when they have the same default GW Mac address in
     * {@link WifiConfigManager#getOrCreateScanDetailCacheForNetwork(WifiConfiguration)}.
     */
    @Test
    public void testNetworkLinkUsingGwMacAddress() {
        WifiConfiguration network1 = WifiConfigurationTestUtil.createPskNetwork();
        WifiConfiguration network2 = WifiConfigurationTestUtil.createPskNetwork();
        WifiConfiguration network3 = WifiConfigurationTestUtil.createPskNetwork();
        verifyAddNetworkToWifiConfigManager(network1);
        verifyAddNetworkToWifiConfigManager(network2);
        verifyAddNetworkToWifiConfigManager(network3);

        // Set the same default GW mac address for all of the networks.
        assertTrue(mWifiConfigManager.setNetworkDefaultGwMacAddress(
                network1.networkId, TEST_DEFAULT_GW_MAC_ADDRESS));
        assertTrue(mWifiConfigManager.setNetworkDefaultGwMacAddress(
                network2.networkId, TEST_DEFAULT_GW_MAC_ADDRESS));
        assertTrue(mWifiConfigManager.setNetworkDefaultGwMacAddress(
                network3.networkId, TEST_DEFAULT_GW_MAC_ADDRESS));

        // Now create dummy scan detail corresponding to the networks.
        ScanDetail networkScanDetail1 = createScanDetailForNetwork(network1);
        ScanDetail networkScanDetail2 = createScanDetailForNetwork(network2);
        ScanDetail networkScanDetail3 = createScanDetailForNetwork(network3);

        // Now save all these scan details corresponding to each of this network and expect
        // all of these networks to be linked with each other.
        assertNotNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(networkScanDetail1));
        assertNotNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(networkScanDetail2));
        assertNotNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(networkScanDetail3));

        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworks();
        for (WifiConfiguration network : retrievedNetworks) {
            assertEquals(2, network.linkedConfigurations.size());
            for (WifiConfiguration otherNetwork : retrievedNetworks) {
                if (otherNetwork == network) {
                    continue;
                }
                assertNotNull(network.linkedConfigurations.get(otherNetwork.configKey()));
            }
        }
    }

    /**
     * Verifies the linking of networks when they have scan results with same first 16 ASCII of
     * bssid in
     * {@link WifiConfigManager#getOrCreateScanDetailCacheForNetwork(WifiConfiguration)}.
     */
    @Test
    public void testNetworkLinkUsingBSSIDMatch() {
        WifiConfiguration network1 = WifiConfigurationTestUtil.createPskNetwork();
        WifiConfiguration network2 = WifiConfigurationTestUtil.createPskNetwork();
        WifiConfiguration network3 = WifiConfigurationTestUtil.createPskNetwork();
        verifyAddNetworkToWifiConfigManager(network1);
        verifyAddNetworkToWifiConfigManager(network2);
        verifyAddNetworkToWifiConfigManager(network3);

        // Create scan results with bssid which is different in only the last char.
        ScanDetail networkScanDetail1 = createScanDetailForNetwork(network1, "af:89:56:34:56:67");
        ScanDetail networkScanDetail2 = createScanDetailForNetwork(network2, "af:89:56:34:56:68");
        ScanDetail networkScanDetail3 = createScanDetailForNetwork(network3, "af:89:56:34:56:69");

        // Now save all these scan details corresponding to each of this network and expect
        // all of these networks to be linked with each other.
        assertNotNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(networkScanDetail1));
        assertNotNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(networkScanDetail2));
        assertNotNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(networkScanDetail3));

        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworks();
        for (WifiConfiguration network : retrievedNetworks) {
            assertEquals(2, network.linkedConfigurations.size());
            for (WifiConfiguration otherNetwork : retrievedNetworks) {
                if (otherNetwork == network) {
                    continue;
                }
                assertNotNull(network.linkedConfigurations.get(otherNetwork.configKey()));
            }
        }
    }

    /**
     * Verifies the linking of networks does not happen for non WPA networks when they have scan
     * results with same first 16 ASCII of bssid in
     * {@link WifiConfigManager#getOrCreateScanDetailCacheForNetwork(WifiConfiguration)}.
     */
    @Test
    public void testNoNetworkLinkUsingBSSIDMatchForNonWpaNetworks() {
        WifiConfiguration network1 = WifiConfigurationTestUtil.createOpenNetwork();
        WifiConfiguration network2 = WifiConfigurationTestUtil.createPskNetwork();
        verifyAddNetworkToWifiConfigManager(network1);
        verifyAddNetworkToWifiConfigManager(network2);

        // Create scan results with bssid which is different in only the last char.
        ScanDetail networkScanDetail1 = createScanDetailForNetwork(network1, "af:89:56:34:56:67");
        ScanDetail networkScanDetail2 = createScanDetailForNetwork(network2, "af:89:56:34:56:68");

        assertNotNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(networkScanDetail1));
        assertNotNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(networkScanDetail2));

        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworks();
        for (WifiConfiguration network : retrievedNetworks) {
            assertNull(network.linkedConfigurations);
        }
    }

    /**
     * Verifies the linking of networks does not happen for networks with more than
     * {@link WifiConfigManager#LINK_CONFIGURATION_MAX_SCAN_CACHE_ENTRIES} scan
     * results with same first 16 ASCII of bssid in
     * {@link WifiConfigManager#getOrCreateScanDetailCacheForNetwork(WifiConfiguration)}.
     */
    @Test
    public void testNoNetworkLinkUsingBSSIDMatchForNetworksWithHighScanDetailCacheSize() {
        WifiConfiguration network1 = WifiConfigurationTestUtil.createPskNetwork();
        WifiConfiguration network2 = WifiConfigurationTestUtil.createPskNetwork();
        verifyAddNetworkToWifiConfigManager(network1);
        verifyAddNetworkToWifiConfigManager(network2);

        // Create 7 scan results with bssid which is different in only the last char.
        String test_bssid_base = "af:89:56:34:56:6";
        int scan_result_num = 0;
        for (; scan_result_num < WifiConfigManager.LINK_CONFIGURATION_MAX_SCAN_CACHE_ENTRIES + 1;
             scan_result_num++) {
            ScanDetail networkScanDetail =
                    createScanDetailForNetwork(
                            network1, test_bssid_base + Integer.toString(scan_result_num));
            assertNotNull(
                    mWifiConfigManager.getSavedNetworkForScanDetailAndCache(networkScanDetail));
        }

        // Now add 1 scan result to the other network with bssid which is different in only the
        // last char.
        ScanDetail networkScanDetail2 =
                createScanDetailForNetwork(
                        network2, test_bssid_base + Integer.toString(scan_result_num++));
        assertNotNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(networkScanDetail2));

        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworks();
        for (WifiConfiguration network : retrievedNetworks) {
            assertNull(network.linkedConfigurations);
        }
    }

    /**
     * Verifies the linking of networks when they have scan results with same first 16 ASCII of
     * bssid in {@link WifiConfigManager#getOrCreateScanDetailCacheForNetwork(WifiConfiguration)}
     * and then subsequently delinked when the networks have default gateway set which do not match.
     */
    @Test
    public void testNetworkLinkUsingBSSIDMatchAndThenUnlinkDueToGwMacAddress() {
        WifiConfiguration network1 = WifiConfigurationTestUtil.createPskNetwork();
        WifiConfiguration network2 = WifiConfigurationTestUtil.createPskNetwork();
        verifyAddNetworkToWifiConfigManager(network1);
        verifyAddNetworkToWifiConfigManager(network2);

        // Create scan results with bssid which is different in only the last char.
        ScanDetail networkScanDetail1 = createScanDetailForNetwork(network1, "af:89:56:34:56:67");
        ScanDetail networkScanDetail2 = createScanDetailForNetwork(network2, "af:89:56:34:56:68");

        // Now save all these scan details corresponding to each of this network and expect
        // all of these networks to be linked with each other.
        assertNotNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(networkScanDetail1));
        assertNotNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(networkScanDetail2));

        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworks();
        for (WifiConfiguration network : retrievedNetworks) {
            assertEquals(1, network.linkedConfigurations.size());
            for (WifiConfiguration otherNetwork : retrievedNetworks) {
                if (otherNetwork == network) {
                    continue;
                }
                assertNotNull(network.linkedConfigurations.get(otherNetwork.configKey()));
            }
        }

        // Now Set different GW mac address for both the networks and ensure they're unlinked.
        assertTrue(mWifiConfigManager.setNetworkDefaultGwMacAddress(
                network1.networkId, "de:ad:fe:45:23:34"));
        assertTrue(mWifiConfigManager.setNetworkDefaultGwMacAddress(
                network2.networkId, "ad:de:fe:45:23:34"));

        // Add some dummy scan results again to re-evaluate the linking of networks.
        assertNotNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(
                createScanDetailForNetwork(network1, "af:89:56:34:45:67")));
        assertNotNull(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(
                createScanDetailForNetwork(network1, "af:89:56:34:45:68")));

        retrievedNetworks = mWifiConfigManager.getConfiguredNetworks();
        for (WifiConfiguration network : retrievedNetworks) {
            assertNull(network.linkedConfigurations);
        }
    }

    /**
     * Verifies the creation of channel list using
     * {@link WifiConfigManager#fetchChannelSetForNetworkForPartialScan(int, long, int)}.
     */
    @Test
    public void testFetchChannelSetForNetwork() {
        WifiConfiguration network = WifiConfigurationTestUtil.createPskNetwork();
        verifyAddNetworkToWifiConfigManager(network);

        // Create 5 scan results with different bssid's & frequencies.
        String test_bssid_base = "af:89:56:34:56:6";
        for (int i = 0; i < TEST_FREQ_LIST.length; i++) {
            ScanDetail networkScanDetail =
                    createScanDetailForNetwork(
                            network, test_bssid_base + Integer.toString(i), 0, TEST_FREQ_LIST[i]);
            assertNotNull(
                    mWifiConfigManager.getSavedNetworkForScanDetailAndCache(networkScanDetail));

        }
        assertEquals(new HashSet<Integer>(Arrays.asList(TEST_FREQ_LIST)),
                mWifiConfigManager.fetchChannelSetForNetworkForPartialScan(network.networkId, 1,
                      TEST_FREQ_LIST[4]));
    }

    /**
     * Verifies the creation of channel list using
     * {@link WifiConfigManager#fetchChannelSetForNetworkForPartialScan(int, long, int)} and
     * ensures that the frequenecy of the currently connected network is in the returned
     * channel set.
     */
    @Test
    public void testFetchChannelSetForNetworkIncludeCurrentNetwork() {
        WifiConfiguration network = WifiConfigurationTestUtil.createPskNetwork();
        verifyAddNetworkToWifiConfigManager(network);

        // Create 5 scan results with different bssid's & frequencies.
        String test_bssid_base = "af:89:56:34:56:6";
        for (int i = 0; i < TEST_FREQ_LIST.length; i++) {
            ScanDetail networkScanDetail =
                    createScanDetailForNetwork(
                            network, test_bssid_base + Integer.toString(i), 0, TEST_FREQ_LIST[i]);
            assertNotNull(
                    mWifiConfigManager.getSavedNetworkForScanDetailAndCache(networkScanDetail));

        }

        // Currently connected network frequency 2427 is not in the TEST_FREQ_LIST
        Set<Integer> freqs = mWifiConfigManager.fetchChannelSetForNetworkForPartialScan(
                network.networkId, 1, 2427);

        assertEquals(true, freqs.contains(2427));
    }

    /**
     * Verifies the creation of channel list using
     * {@link WifiConfigManager#fetchChannelSetForNetworkForPartialScan(int, long, int)} and
     * ensures that scan results which have a timestamp  beyond the provided age are not used
     * in the channel list.
     */
    @Test
    public void testFetchChannelSetForNetworkIgnoresStaleScanResults() {
        WifiConfiguration network = WifiConfigurationTestUtil.createPskNetwork();
        verifyAddNetworkToWifiConfigManager(network);

        long wallClockBase = 0;
        // Create 5 scan results with different bssid's & frequencies.
        String test_bssid_base = "af:89:56:34:56:6";
        for (int i = 0; i < TEST_FREQ_LIST.length; i++) {
            // Increment the seen value in the scan results for each of them.
            when(mClock.getWallClockMillis()).thenReturn(wallClockBase + i);
            ScanDetail networkScanDetail =
                    createScanDetailForNetwork(
                            network, test_bssid_base + Integer.toString(i), 0, TEST_FREQ_LIST[i]);
            assertNotNull(
                    mWifiConfigManager.getSavedNetworkForScanDetailAndCache(networkScanDetail));

        }
        int ageInMillis = 4;
        // Now fetch only scan results which are 4 millis stale. This should ignore the first
        // scan result.
        assertEquals(
                new HashSet<>(Arrays.asList(
                        Arrays.copyOfRange(
                                TEST_FREQ_LIST,
                                TEST_FREQ_LIST.length - ageInMillis, TEST_FREQ_LIST.length))),
                mWifiConfigManager.fetchChannelSetForNetworkForPartialScan(
                        network.networkId, ageInMillis, TEST_FREQ_LIST[4]));
    }

    /**
     * Verifies the creation of channel list using
     * {@link WifiConfigManager#fetchChannelSetForNetworkForPartialScan(int, long, int)} and
     * ensures that the list size does not exceed the max configured for the device.
     */
    @Test
    public void testFetchChannelSetForNetworkIsLimitedToConfiguredSize() {
        // Need to recreate the WifiConfigManager instance for this test to modify the config
        // value which is read only in the constructor.
        int maxListSize = 3;
        mResources.setInteger(
                R.integer.config_wifi_framework_associated_partial_scan_max_num_active_channels,
                maxListSize);
        createWifiConfigManager();

        WifiConfiguration network = WifiConfigurationTestUtil.createPskNetwork();
        verifyAddNetworkToWifiConfigManager(network);

        // Create 5 scan results with different bssid's & frequencies.
        String test_bssid_base = "af:89:56:34:56:6";
        for (int i = 0; i < TEST_FREQ_LIST.length; i++) {
            ScanDetail networkScanDetail =
                    createScanDetailForNetwork(
                            network, test_bssid_base + Integer.toString(i), 0, TEST_FREQ_LIST[i]);
            assertNotNull(
                    mWifiConfigManager.getSavedNetworkForScanDetailAndCache(networkScanDetail));

        }
        // Ensure that the fetched list size is limited.
        assertEquals(maxListSize,
                mWifiConfigManager.fetchChannelSetForNetworkForPartialScan(
                        network.networkId, 1, TEST_FREQ_LIST[4]).size());
    }

    /**
     * Verifies the creation of channel list using
     * {@link WifiConfigManager#fetchChannelSetForNetworkForPartialScan(int, long, int)} and
     * ensures that scan results from linked networks are used in the channel list.
     */
    @Test
    public void testFetchChannelSetForNetworkIncludesLinkedNetworks() {
        WifiConfiguration network1 = WifiConfigurationTestUtil.createPskNetwork();
        WifiConfiguration network2 = WifiConfigurationTestUtil.createPskNetwork();
        verifyAddNetworkToWifiConfigManager(network1);
        verifyAddNetworkToWifiConfigManager(network2);

        String test_bssid_base = "af:89:56:34:56:6";
        int TEST_FREQ_LISTIdx = 0;
        // Create 3 scan results with different bssid's & frequencies for network 1.
        for (; TEST_FREQ_LISTIdx < TEST_FREQ_LIST.length / 2; TEST_FREQ_LISTIdx++) {
            ScanDetail networkScanDetail =
                    createScanDetailForNetwork(
                            network1, test_bssid_base + Integer.toString(TEST_FREQ_LISTIdx), 0,
                            TEST_FREQ_LIST[TEST_FREQ_LISTIdx]);
            assertNotNull(
                    mWifiConfigManager.getSavedNetworkForScanDetailAndCache(networkScanDetail));

        }
        // Create 3 scan results with different bssid's & frequencies for network 2.
        for (; TEST_FREQ_LISTIdx < TEST_FREQ_LIST.length; TEST_FREQ_LISTIdx++) {
            ScanDetail networkScanDetail =
                    createScanDetailForNetwork(
                            network2, test_bssid_base + Integer.toString(TEST_FREQ_LISTIdx), 0,
                            TEST_FREQ_LIST[TEST_FREQ_LISTIdx]);
            assertNotNull(
                    mWifiConfigManager.getSavedNetworkForScanDetailAndCache(networkScanDetail));
        }

        // Link the 2 configurations together using the GwMacAddress.
        assertTrue(mWifiConfigManager.setNetworkDefaultGwMacAddress(
                network1.networkId, TEST_DEFAULT_GW_MAC_ADDRESS));
        assertTrue(mWifiConfigManager.setNetworkDefaultGwMacAddress(
                network2.networkId, TEST_DEFAULT_GW_MAC_ADDRESS));

        // The channel list fetched should include scan results from both the linked networks.
        assertEquals(new HashSet<Integer>(Arrays.asList(TEST_FREQ_LIST)),
                mWifiConfigManager.fetchChannelSetForNetworkForPartialScan(network1.networkId, 1,
                      TEST_FREQ_LIST[0]));
        assertEquals(new HashSet<Integer>(Arrays.asList(TEST_FREQ_LIST)),
                mWifiConfigManager.fetchChannelSetForNetworkForPartialScan(network2.networkId, 1,
                      TEST_FREQ_LIST[0]));
    }

    /**
     * Verifies the creation of channel list using
     * {@link WifiConfigManager#fetchChannelSetForNetworkForPartialScan(int, long, int)} and
     * ensures that scan results from linked networks are used in the channel list and that the
     * list size does not exceed the max configured for the device.
     */
    @Test
    public void testFetchChannelSetForNetworkIncludesLinkedNetworksIsLimitedToConfiguredSize() {
        // Need to recreate the WifiConfigManager instance for this test to modify the config
        // value which is read only in the constructor.
        int maxListSize = 3;
        mResources.setInteger(
                R.integer.config_wifi_framework_associated_partial_scan_max_num_active_channels,
                maxListSize);

        createWifiConfigManager();
        WifiConfiguration network1 = WifiConfigurationTestUtil.createPskNetwork();
        WifiConfiguration network2 = WifiConfigurationTestUtil.createPskNetwork();
        verifyAddNetworkToWifiConfigManager(network1);
        verifyAddNetworkToWifiConfigManager(network2);

        String test_bssid_base = "af:89:56:34:56:6";
        int TEST_FREQ_LISTIdx = 0;
        // Create 3 scan results with different bssid's & frequencies for network 1.
        for (; TEST_FREQ_LISTIdx < TEST_FREQ_LIST.length / 2; TEST_FREQ_LISTIdx++) {
            ScanDetail networkScanDetail =
                    createScanDetailForNetwork(
                            network1, test_bssid_base + Integer.toString(TEST_FREQ_LISTIdx), 0,
                            TEST_FREQ_LIST[TEST_FREQ_LISTIdx]);
            assertNotNull(
                    mWifiConfigManager.getSavedNetworkForScanDetailAndCache(networkScanDetail));

        }
        // Create 3 scan results with different bssid's & frequencies for network 2.
        for (; TEST_FREQ_LISTIdx < TEST_FREQ_LIST.length; TEST_FREQ_LISTIdx++) {
            ScanDetail networkScanDetail =
                    createScanDetailForNetwork(
                            network2, test_bssid_base + Integer.toString(TEST_FREQ_LISTIdx), 0,
                            TEST_FREQ_LIST[TEST_FREQ_LISTIdx]);
            assertNotNull(
                    mWifiConfigManager.getSavedNetworkForScanDetailAndCache(networkScanDetail));
        }

        // Link the 2 configurations together using the GwMacAddress.
        assertTrue(mWifiConfigManager.setNetworkDefaultGwMacAddress(
                network1.networkId, TEST_DEFAULT_GW_MAC_ADDRESS));
        assertTrue(mWifiConfigManager.setNetworkDefaultGwMacAddress(
                network2.networkId, TEST_DEFAULT_GW_MAC_ADDRESS));

        // Ensure that the fetched list size is limited.
        assertEquals(maxListSize,
                mWifiConfigManager.fetchChannelSetForNetworkForPartialScan(
                        network1.networkId, 1, TEST_FREQ_LIST[0]).size());
        assertEquals(maxListSize,
                mWifiConfigManager.fetchChannelSetForNetworkForPartialScan(
                        network2.networkId, 1, TEST_FREQ_LIST[0]).size());
    }

    /**
     * Verifies the foreground user switch using {@link WifiConfigManager#handleUserSwitch(int)}
     * and ensures that any non current user private networks are moved to shared store file.
     */
    @Test
    public void testHandleUserSwitchPushesOtherPrivateNetworksToSharedStore() throws Exception {
        int user1 = TEST_DEFAULT_USER;
        int user2 = TEST_DEFAULT_USER + 1;
        setupUserProfiles(user2);

        int appId = 674;

        // Create 3 networks. 1 for user1, 1 for user2 and 1 shared.
        final WifiConfiguration user1Network = WifiConfigurationTestUtil.createPskNetwork();
        user1Network.shared = false;
        user1Network.creatorUid = UserHandle.getUid(user1, appId);
        final WifiConfiguration user2Network = WifiConfigurationTestUtil.createPskNetwork();
        user2Network.shared = false;
        user2Network.creatorUid = UserHandle.getUid(user2, appId);
        final WifiConfiguration sharedNetwork = WifiConfigurationTestUtil.createPskNetwork();

        // Set up the store data first that is loaded.
        List<WifiConfiguration> sharedNetworks = new ArrayList<WifiConfiguration>() {
            {
                add(sharedNetwork);
                add(user2Network);
            }
        };
        List<WifiConfiguration> userNetworks = new ArrayList<WifiConfiguration>() {
            {
                add(user1Network);
            }
        };
        WifiConfigStoreData loadStoreData =
                new WifiConfigStoreData(sharedNetworks, userNetworks, new HashSet<String>());
        when(mWifiConfigStore.read()).thenReturn(loadStoreData);

        // Now switch the user to user2
        mWifiConfigManager.handleUserSwitch(user2);

        // Set the expected network list before comparing. user1Network should be in shared data.
        // Note: In the real world, user1Network will no longer be visible now because it should
        // already be in user1's private store file. But, we're purposefully exposing it
        // via |loadStoreData| to test if other user's private networks are pushed to shared store.
        List<WifiConfiguration> expectedSharedNetworks = new ArrayList<WifiConfiguration>() {
            {
                add(sharedNetwork);
                add(user1Network);
            }
        };
        List<WifiConfiguration> expectedUserNetworks = new ArrayList<WifiConfiguration>() {
            {
                add(user2Network);
            }
        };
        // Capture the written data for the old user and ensure that it was empty.
        WifiConfigStoreData writtenStoreData = captureWriteStoreData();
        assertTrue(writtenStoreData.getConfigurations().isEmpty());

        // Now capture the next written data and ensure that user1Network is now in shared data.
        writtenStoreData = captureWriteStoreData();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                expectedSharedNetworks, writtenStoreData.getSharedConfigurations());
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                expectedUserNetworks, writtenStoreData.getUserConfigurations());
    }

    /**
     * Verifies the foreground user switch using {@link WifiConfigManager#handleUserSwitch(int)}
     * and {@link WifiConfigManager#handleUserUnlock(int)} and ensures that the new store is
     * read immediately if the user is unlocked during the switch.
     */
    @Test
    public void testHandleUserSwitchWhenUnlocked() throws Exception {
        int user1 = TEST_DEFAULT_USER;
        int user2 = TEST_DEFAULT_USER + 1;
        setupUserProfiles(user2);

        when(mWifiConfigStore.read()).thenReturn(
                new WifiConfigStoreData(
                        new ArrayList<WifiConfiguration>(), new ArrayList<WifiConfiguration>(),
                        new HashSet<String>()));

        // user2 is unlocked and switched to foreground.
        when(mUserManager.isUserUnlockingOrUnlocked(user2)).thenReturn(true);
        mWifiConfigManager.handleUserSwitch(user2);
        // Ensure that the read was invoked.
        mContextConfigStoreMockOrder.verify(mWifiConfigStore).read();
    }

    /**
     * Verifies the foreground user switch using {@link WifiConfigManager#handleUserSwitch(int)}
     * and {@link WifiConfigManager#handleUserUnlock(int)} and ensures that the new store is not
     * read until the user is unlocked.
     */
    public void testHandleUserSwitchWhenLocked() throws Exception {
        int user1 = TEST_DEFAULT_USER;
        int user2 = TEST_DEFAULT_USER + 1;
        setupUserProfiles(user2);

        // user2 is locked and switched to foreground.
        when(mUserManager.isUserUnlockingOrUnlocked(user2)).thenReturn(false);
        mWifiConfigManager.handleUserSwitch(user2);

        // Ensure that the read was not invoked.
        mContextConfigStoreMockOrder.verify(mWifiConfigStore, never()).read();

        // Now try unlocking some other user (user1), this should be ignored.
        mWifiConfigManager.handleUserUnlock(user1);
        mContextConfigStoreMockOrder.verify(mWifiConfigStore, never()).read();

        when(mWifiConfigStore.read()).thenReturn(
                new WifiConfigStoreData(
                        new ArrayList<WifiConfiguration>(), new ArrayList<WifiConfiguration>(),
                        new HashSet<String>()));

        // Unlock the user2 and ensure that we read the data now.
        mWifiConfigManager.handleUserUnlock(user2);
        mContextConfigStoreMockOrder.verify(mWifiConfigStore).read();
    }

    /**
     * Verifies that the foreground user stop using {@link WifiConfigManager#handleUserStop(int)}
     * and ensures that the store is written only when the foreground user is stopped.
     */
    @Test
    public void testHandleUserStop() throws Exception {
        int user1 = TEST_DEFAULT_USER;
        int user2 = TEST_DEFAULT_USER + 1;
        setupUserProfiles(user2);

        // Try stopping background user2 first, this should not do anything.
        when(mUserManager.isUserUnlockingOrUnlocked(user2)).thenReturn(false);
        mWifiConfigManager.handleUserStop(user2);
        mContextConfigStoreMockOrder.verify(mWifiConfigStore, never()).read();

        // Now try stopping the foreground user1, this should trigger a write to store.
        mWifiConfigManager.handleUserStop(user1);
        mContextConfigStoreMockOrder.verify(mWifiConfigStore, never()).read();
        mContextConfigStoreMockOrder.verify(mWifiConfigStore).write(
                anyBoolean(), any(WifiConfigStoreData.class));
    }

    /**
     * Verifies the foreground user unlock via {@link WifiConfigManager#handleUserUnlock(int)}
     * results in a store read after bootup.
     */
    @Test
    public void testHandleUserUnlockAfterBootup() throws Exception {
        int user1 = TEST_DEFAULT_USER;

        when(mWifiConfigStore.read()).thenReturn(
                new WifiConfigStoreData(
                        new ArrayList<WifiConfiguration>(), new ArrayList<WifiConfiguration>(),
                        new HashSet<String>()));

        // Unlock the user1 (default user) for the first time and ensure that we read the data.
        mWifiConfigManager.handleUserUnlock(user1);
        mContextConfigStoreMockOrder.verify(mWifiConfigStore).read();
    }

    /**
     * Verifies the foreground user unlock via {@link WifiConfigManager#handleUserUnlock(int)} does
     * not always result in a store read unless the user had switched or just booted up.
     */
    @Test
    public void testHandleUserUnlockWithoutSwitchOrBootup() throws Exception {
        int user1 = TEST_DEFAULT_USER;
        int user2 = TEST_DEFAULT_USER + 1;
        setupUserProfiles(user2);

        when(mWifiConfigStore.read()).thenReturn(
                new WifiConfigStoreData(
                        new ArrayList<WifiConfiguration>(), new ArrayList<WifiConfiguration>(),
                        new HashSet<String>()));

        // user2 is unlocked and switched to foreground.
        when(mUserManager.isUserUnlockingOrUnlocked(user2)).thenReturn(true);
        mWifiConfigManager.handleUserSwitch(user2);
        // Ensure that the read was invoked.
        mContextConfigStoreMockOrder.verify(mWifiConfigStore).read();

        // Unlock the user2 again and ensure that we don't read the data now.
        mWifiConfigManager.handleUserUnlock(user2);
        mContextConfigStoreMockOrder.verify(mWifiConfigStore, never()).read();
    }

    /**
     * Verifies the private network addition using
     * {@link WifiConfigManager#addOrUpdateNetwork(WifiConfiguration, int)}
     * by a non foreground user is rejected.
     */
    @Test
    public void testAddNetworkUsingBackgroundUserUId() throws Exception {
        int user2 = TEST_DEFAULT_USER + 1;
        setupUserProfiles(user2);

        int creatorUid = UserHandle.getUid(user2, 674);

        // Create a network for user2 try adding it. This should be rejected.
        final WifiConfiguration user2Network = WifiConfigurationTestUtil.createPskNetwork();
        NetworkUpdateResult result =
                mWifiConfigManager.addOrUpdateNetwork(user2Network, creatorUid);
        assertFalse(result.isSuccess());
    }

    /**
     * Verifies the private network addition using
     * {@link WifiConfigManager#addOrUpdateNetwork(WifiConfiguration, int)}
     * by SysUI is always accepted.
     */
    @Test
    public void testAddNetworkUsingSysUiUid() throws Exception {
        // Set up the user profiles stuff. Needed for |WifiConfigurationUtil.isVisibleToAnyProfile|
        int user2 = TEST_DEFAULT_USER + 1;
        setupUserProfiles(user2);

        when(mUserManager.isUserUnlockingOrUnlocked(user2)).thenReturn(false);
        mWifiConfigManager.handleUserSwitch(user2);

        // Create a network for user2 try adding it. This should be rejected.
        final WifiConfiguration user2Network = WifiConfigurationTestUtil.createPskNetwork();
        NetworkUpdateResult result =
                mWifiConfigManager.addOrUpdateNetwork(user2Network, TEST_SYSUI_UID);
        assertTrue(result.isSuccess());
    }

    /**
     * Verifies the loading of networks using {@link WifiConfigManager#loadFromStore()} attempts
     * to migrate data from legacy stores when the new store files are absent.
     */
    @Test
    public void testMigrationFromLegacyStore() throws Exception {
        // Create the store data to be returned from legacy stores.
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(WifiConfigurationTestUtil.createPskNetwork());
        networks.add(WifiConfigurationTestUtil.createEapNetwork());
        networks.add(WifiConfigurationTestUtil.createWepNetwork());
        String deletedEphemeralSSID = "EphemeralSSID";
        Set<String> deletedEphermalSSIDs = new HashSet<>(Arrays.asList(deletedEphemeralSSID));
        WifiConfigStoreDataLegacy storeData =
                new WifiConfigStoreDataLegacy(networks, deletedEphermalSSIDs);

        // New store files not present, so migrate from the old store.
        when(mWifiConfigStore.areStoresPresent()).thenReturn(false);
        when(mWifiConfigStoreLegacy.areStoresPresent()).thenReturn(true);
        when(mWifiConfigStoreLegacy.read()).thenReturn(storeData);

        // Now trigger a load from store. This should populate the in memory list with all the
        // networks above from the legacy store.
        mWifiConfigManager.loadFromStore();

        verify(mWifiConfigStore, never()).read();
        verify(mWifiConfigStoreLegacy).read();
        verify(mWifiConfigStoreLegacy).removeStores();

        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);
        assertTrue(mWifiConfigManager.wasEphemeralNetworkDeleted(deletedEphemeralSSID));
    }

    /**
     * Verifies the loading of networks using {@link WifiConfigManager#loadFromStore()} does
     * not attempt to read from any of the stores (new or legacy) when the store files are
     * not present.
     */
    @Test
    public void testFreshInstallDoesNotLoadFromStore() throws Exception {
        // New store files not present, so migrate from the old store.
        when(mWifiConfigStore.areStoresPresent()).thenReturn(false);
        when(mWifiConfigStoreLegacy.areStoresPresent()).thenReturn(false);

        // Now trigger a load from store. This should populate the in memory list with all the
        // networks above.
        mWifiConfigManager.loadFromStore();

        verify(mWifiConfigStore, never()).read();
        verify(mWifiConfigStoreLegacy, never()).read();

        assertTrue(mWifiConfigManager.getConfiguredNetworksWithPasswords().isEmpty());
    }

    /**
     * Verifies that the last user selected network parameter is set when
     * {@link WifiConfigManager#enableNetwork(int, boolean, int)} with disableOthers flag is set
     * to true and cleared when either {@link WifiConfigManager#disableNetwork(int, int)} or
     * {@link WifiConfigManager#removeNetwork(int, int)} is invoked using the same network ID.
     */
    @Test
    public void testLastSelectedNetwork() throws Exception {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        NetworkUpdateResult result = verifyAddNetworkToWifiConfigManager(openNetwork);

        when(mClock.getElapsedSinceBootMillis()).thenReturn(67L);
        assertTrue(mWifiConfigManager.enableNetwork(
                result.getNetworkId(), true, TEST_CREATOR_UID));
        assertEquals(result.getNetworkId(), mWifiConfigManager.getLastSelectedNetwork());
        assertEquals(67, mWifiConfigManager.getLastSelectedTimeStamp());

        // Now disable the network and ensure that the last selected flag is cleared.
        assertTrue(mWifiConfigManager.disableNetwork(result.getNetworkId(), TEST_CREATOR_UID));
        assertEquals(
                WifiConfiguration.INVALID_NETWORK_ID, mWifiConfigManager.getLastSelectedNetwork());

        // Enable it again and remove the network to ensure that the last selected flag was cleared.
        assertTrue(mWifiConfigManager.enableNetwork(
                result.getNetworkId(), true, TEST_CREATOR_UID));
        assertEquals(result.getNetworkId(), mWifiConfigManager.getLastSelectedNetwork());
        assertEquals(openNetwork.configKey(), mWifiConfigManager.getLastSelectedNetworkConfigKey());

        assertTrue(mWifiConfigManager.removeNetwork(result.getNetworkId(), TEST_CREATOR_UID));
        assertEquals(
                WifiConfiguration.INVALID_NETWORK_ID, mWifiConfigManager.getLastSelectedNetwork());
    }

    /**
     * Verifies that all the networks for the provided app is removed when
     * {@link WifiConfigManager#removeNetworksForApp(ApplicationInfo)} is invoked.
     */
    @Test
    public void testRemoveNetworksForApp() throws Exception {
        verifyAddNetworkToWifiConfigManager(WifiConfigurationTestUtil.createOpenNetwork());
        verifyAddNetworkToWifiConfigManager(WifiConfigurationTestUtil.createPskNetwork());
        verifyAddNetworkToWifiConfigManager(WifiConfigurationTestUtil.createWepNetwork());

        assertFalse(mWifiConfigManager.getConfiguredNetworks().isEmpty());

        ApplicationInfo app = new ApplicationInfo();
        app.uid = TEST_CREATOR_UID;
        app.packageName = TEST_CREATOR_NAME;
        assertTrue(mWifiConfigManager.removeNetworksForApp(app));

        // Ensure all the networks are removed now.
        assertTrue(mWifiConfigManager.getConfiguredNetworks().isEmpty());
    }

    /**
     * Verifies that all the networks for the provided user is removed when
     * {@link WifiConfigManager#removeNetworksForUser(int)} is invoked.
     */
    @Test
    public void testRemoveNetworksForUser() throws Exception {
        verifyAddNetworkToWifiConfigManager(WifiConfigurationTestUtil.createOpenNetwork());
        verifyAddNetworkToWifiConfigManager(WifiConfigurationTestUtil.createPskNetwork());
        verifyAddNetworkToWifiConfigManager(WifiConfigurationTestUtil.createWepNetwork());

        assertFalse(mWifiConfigManager.getConfiguredNetworks().isEmpty());

        assertTrue(mWifiConfigManager.removeNetworksForUser(TEST_DEFAULT_USER));

        // Ensure all the networks are removed now.
        assertTrue(mWifiConfigManager.getConfiguredNetworks().isEmpty());
    }

    /**
     * Verifies that the connect choice is removed from all networks when
     * {@link WifiConfigManager#removeNetwork(int, int)} is invoked.
     */
    @Test
    public void testRemoveNetworkRemovesConnectChoice() throws Exception {
        WifiConfiguration network1 = WifiConfigurationTestUtil.createOpenNetwork();
        WifiConfiguration network2 = WifiConfigurationTestUtil.createPskNetwork();
        WifiConfiguration network3 = WifiConfigurationTestUtil.createPskNetwork();
        verifyAddNetworkToWifiConfigManager(network1);
        verifyAddNetworkToWifiConfigManager(network2);
        verifyAddNetworkToWifiConfigManager(network3);

        // Set connect choice of network 2 over network 1.
        assertTrue(
                mWifiConfigManager.setNetworkConnectChoice(
                        network1.networkId, network2.configKey(), 78L));

        WifiConfiguration retrievedNetwork =
                mWifiConfigManager.getConfiguredNetwork(network1.networkId);
        assertEquals(
                network2.configKey(),
                retrievedNetwork.getNetworkSelectionStatus().getConnectChoice());

        // Remove network 3 and ensure that the connect choice on network 1 is not removed.
        assertTrue(mWifiConfigManager.removeNetwork(network3.networkId, TEST_CREATOR_UID));
        retrievedNetwork = mWifiConfigManager.getConfiguredNetwork(network1.networkId);
        assertEquals(
                network2.configKey(),
                retrievedNetwork.getNetworkSelectionStatus().getConnectChoice());

        // Now remove network 2 and ensure that the connect choice on network 1 is removed..
        assertTrue(mWifiConfigManager.removeNetwork(network2.networkId, TEST_CREATOR_UID));
        retrievedNetwork = mWifiConfigManager.getConfiguredNetwork(network1.networkId);
        assertNotEquals(
                network2.configKey(),
                retrievedNetwork.getNetworkSelectionStatus().getConnectChoice());

        // This should have triggered 2 buffered writes. 1 for setting the connect choice, 1 for
        // clearing it after network removal.
        mContextConfigStoreMockOrder.verify(mWifiConfigStore, times(2))
                .write(eq(false), any(WifiConfigStoreData.class));
    }

    /**
     * Verifies that the modification of a single network using
     * {@link WifiConfigManager#addOrUpdateNetwork(WifiConfiguration, int)} and ensures that any
     * updates to the network config in
     * {@link WifiKeyStore#updateNetworkKeys(WifiConfiguration, WifiConfiguration)} is reflected
     * in the internal database.
     */
    @Test
    public void testUpdateSingleNetworkWithKeysUpdate() {
        WifiConfiguration network = WifiConfigurationTestUtil.createEapNetwork();
        network.enterpriseConfig =
                WifiConfigurationTestUtil.createPEAPWifiEnterpriseConfigWithGTCPhase2();
        verifyAddNetworkToWifiConfigManager(network);

        // Now verify that network configurations match before we make any change.
        WifiConfigurationTestUtil.assertConfigurationEqualForConfigManagerAddOrUpdate(
                network,
                mWifiConfigManager.getConfiguredNetworkWithPassword(network.networkId));

        // Modify the network ca_cert field in updateNetworkKeys method during a network
        // config update.
        final String newCaCertAlias = "test";
        assertNotEquals(newCaCertAlias, network.enterpriseConfig.getCaCertificateAlias());

        doAnswer(new AnswerWithArguments() {
            public boolean answer(WifiConfiguration newConfig, WifiConfiguration existingConfig) {
                newConfig.enterpriseConfig.setCaCertificateAlias(newCaCertAlias);
                return true;
            }
        }).when(mWifiKeyStore).updateNetworkKeys(
                any(WifiConfiguration.class), any(WifiConfiguration.class));

        verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(network);

        // Now verify that the keys update is reflected in the configuration fetched from internal
        // db.
        network.enterpriseConfig.setCaCertificateAlias(newCaCertAlias);
        WifiConfigurationTestUtil.assertConfigurationEqualForConfigManagerAddOrUpdate(
                network,
                mWifiConfigManager.getConfiguredNetworkWithPassword(network.networkId));
    }

    /**
     * Verifies that the dump method prints out all the saved network details with passwords masked.
     * {@link WifiConfigManager#dump(FileDescriptor, PrintWriter, String[])}.
     */
    @Test
    public void testDump() {
        WifiConfiguration pskNetwork = WifiConfigurationTestUtil.createPskNetwork();
        WifiConfiguration eapNetwork = WifiConfigurationTestUtil.createEapNetwork();
        eapNetwork.enterpriseConfig.setPassword("blah");

        verifyAddNetworkToWifiConfigManager(pskNetwork);
        verifyAddNetworkToWifiConfigManager(eapNetwork);

        StringWriter stringWriter = new StringWriter();
        mWifiConfigManager.dump(
                new FileDescriptor(), new PrintWriter(stringWriter), new String[0]);
        String dumpString = stringWriter.toString();

        // Ensure that the network SSIDs were dumped out.
        assertTrue(dumpString.contains(pskNetwork.SSID));
        assertTrue(dumpString.contains(eapNetwork.SSID));

        // Ensure that the network passwords were not dumped out.
        assertFalse(dumpString.contains(pskNetwork.preSharedKey));
        assertFalse(dumpString.contains(eapNetwork.enterpriseConfig.getPassword()));
    }

    /**
     * Verifies the ordering of network list generated using
     * {@link WifiConfigManager#retrieveHiddenNetworkList()}.
     */
    @Test
    public void testRetrieveHiddenList() {
        // Create and add 3 networks.
        WifiConfiguration network1 = WifiConfigurationTestUtil.createWepHiddenNetwork();
        WifiConfiguration network2 = WifiConfigurationTestUtil.createPskHiddenNetwork();
        WifiConfiguration network3 = WifiConfigurationTestUtil.createOpenHiddenNetwork();
        verifyAddNetworkToWifiConfigManager(network1);
        verifyAddNetworkToWifiConfigManager(network2);
        verifyAddNetworkToWifiConfigManager(network3);

        // Enable all of them.
        assertTrue(mWifiConfigManager.enableNetwork(network1.networkId, false, TEST_CREATOR_UID));
        assertTrue(mWifiConfigManager.enableNetwork(network2.networkId, false, TEST_CREATOR_UID));
        assertTrue(mWifiConfigManager.enableNetwork(network3.networkId, false, TEST_CREATOR_UID));

        // Now set scan results in 2 of them to set the corresponding
        // {@link NetworkSelectionStatus#mSeenInLastQualifiedNetworkSelection} field.
        assertTrue(mWifiConfigManager.setNetworkCandidateScanResult(
                network1.networkId, createScanDetailForNetwork(network1).getScanResult(), 54));
        assertTrue(mWifiConfigManager.setNetworkCandidateScanResult(
                network3.networkId, createScanDetailForNetwork(network3).getScanResult(), 54));

        // Now increment |network3|'s association count. This should ensure that this network
        // is preferred over |network1|.
        assertTrue(mWifiConfigManager.updateNetworkAfterConnect(network3.networkId));

        // Retrieve the hidden network list & verify the order of the networks returned.
        List<WifiScanner.ScanSettings.HiddenNetwork> hiddenNetworks =
                mWifiConfigManager.retrieveHiddenNetworkList();
        assertEquals(3, hiddenNetworks.size());
        assertEquals(network3.SSID, hiddenNetworks.get(0).ssid);
        assertEquals(network1.SSID, hiddenNetworks.get(1).ssid);
        assertEquals(network2.SSID, hiddenNetworks.get(2).ssid);

        // Now permanently disable |network3|. This should remove network 3 from the list.
        assertTrue(mWifiConfigManager.disableNetwork(network3.networkId, TEST_CREATOR_UID));

        // Retrieve the hidden network list again & verify the order of the networks returned.
        hiddenNetworks = mWifiConfigManager.retrieveHiddenNetworkList();
        assertEquals(2, hiddenNetworks.size());
        assertEquals(network1.SSID, hiddenNetworks.get(0).ssid);
        assertEquals(network2.SSID, hiddenNetworks.get(1).ssid);
    }

    /**
     * Verifies the addition of network configurations using
     * {@link WifiConfigManager#addOrUpdateNetwork(WifiConfiguration, int)} with same SSID and
     * default key mgmt does not add duplicate network configs.
     */
    @Test
    public void testAddMultipleNetworksWithSameSSIDAndDefaultKeyMgmt() {
        final String ssid = "test_blah";
        // Add a network with the above SSID and default key mgmt and ensure it was added
        // successfully.
        WifiConfiguration network1 = new WifiConfiguration();
        network1.SSID = ssid;
        NetworkUpdateResult result = addNetworkToWifiConfigManager(network1);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);
        assertTrue(result.isNewNetwork());

        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        assertEquals(1, retrievedNetworks.size());
        WifiConfigurationTestUtil.assertConfigurationEqualForConfigManagerAddOrUpdate(
                network1, retrievedNetworks.get(0));

        // Now add a second network with the same SSID and default key mgmt and ensure that it
        // didn't add a new duplicate network.
        WifiConfiguration network2 = new WifiConfiguration();
        network2.SSID = ssid;
        result = addNetworkToWifiConfigManager(network2);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);
        assertFalse(result.isNewNetwork());

        retrievedNetworks = mWifiConfigManager.getConfiguredNetworksWithPasswords();
        assertEquals(1, retrievedNetworks.size());
        WifiConfigurationTestUtil.assertConfigurationEqualForConfigManagerAddOrUpdate(
                network2, retrievedNetworks.get(0));
    }

    /**
     * Verifies the addition of network configurations using
     * {@link WifiConfigManager#addOrUpdateNetwork(WifiConfiguration, int)} with same SSID and
     * different key mgmt should add different network configs.
     */
    @Test
    public void testAddMultipleNetworksWithSameSSIDAndDifferentKeyMgmt() {
        final String ssid = "test_blah";
        // Add a network with the above SSID and WPA_PSK key mgmt and ensure it was added
        // successfully.
        WifiConfiguration network1 = new WifiConfiguration();
        network1.SSID = ssid;
        network1.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        NetworkUpdateResult result = addNetworkToWifiConfigManager(network1);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);
        assertTrue(result.isNewNetwork());

        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        assertEquals(1, retrievedNetworks.size());
        WifiConfigurationTestUtil.assertConfigurationEqualForConfigManagerAddOrUpdate(
                network1, retrievedNetworks.get(0));

        // Now add a second network with the same SSID and NONE key mgmt and ensure that it
        // does add a new network.
        WifiConfiguration network2 = new WifiConfiguration();
        network2.SSID = ssid;
        network2.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        result = addNetworkToWifiConfigManager(network2);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);
        assertTrue(result.isNewNetwork());

        retrievedNetworks = mWifiConfigManager.getConfiguredNetworksWithPasswords();
        assertEquals(2, retrievedNetworks.size());
        List<WifiConfiguration> networks = Arrays.asList(network1, network2);
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);
    }

    private void createWifiConfigManager() {
        mWifiConfigManager =
                new WifiConfigManager(
                        mContext, mFrameworkFacade, mClock, mUserManager, mTelephonyManager,
                        mWifiKeyStore, mWifiConfigStore, mWifiConfigStoreLegacy);
        mWifiConfigManager.enableVerboseLogging(1);
    }

    /**
     * This method sets defaults in the provided WifiConfiguration object if not set
     * so that it can be used for comparison with the configuration retrieved from
     * WifiConfigManager.
     */
    private void setDefaults(WifiConfiguration configuration) {
        if (configuration.allowedAuthAlgorithms.isEmpty()) {
            configuration.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
        }
        if (configuration.allowedProtocols.isEmpty()) {
            configuration.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
            configuration.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
        }
        if (configuration.allowedKeyManagement.isEmpty()) {
            configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
        }
        if (configuration.allowedPairwiseCiphers.isEmpty()) {
            configuration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
            configuration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
        }
        if (configuration.allowedGroupCiphers.isEmpty()) {
            configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
            configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
            configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
            configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104);
        }
        if (configuration.getIpAssignment() == IpConfiguration.IpAssignment.UNASSIGNED) {
            configuration.setIpAssignment(IpConfiguration.IpAssignment.DHCP);
        }
        if (configuration.getProxySettings() == IpConfiguration.ProxySettings.UNASSIGNED) {
            configuration.setProxySettings(IpConfiguration.ProxySettings.NONE);
        }
        configuration.status = WifiConfiguration.Status.DISABLED;
        configuration.getNetworkSelectionStatus().setNetworkSelectionStatus(
                NetworkSelectionStatus.NETWORK_SELECTION_PERMANENTLY_DISABLED);
    }

    /**
     * Modifies the provided configuration with creator uid, package name
     * and time.
     */
    private void setCreationDebugParams(WifiConfiguration configuration) {
        configuration.creatorUid = configuration.lastUpdateUid = TEST_CREATOR_UID;
        configuration.creatorName = configuration.lastUpdateName = TEST_CREATOR_NAME;
        configuration.creationTime = configuration.updateTime =
                WifiConfigManager.createDebugTimeStampString(
                        TEST_WALLCLOCK_CREATION_TIME_MILLIS);
    }

    /**
     * Modifies the provided configuration with update uid, package name
     * and time.
     */
    private void setUpdateDebugParams(WifiConfiguration configuration) {
        configuration.lastUpdateUid = TEST_UPDATE_UID;
        configuration.lastUpdateName = TEST_UPDATE_NAME;
        configuration.updateTime =
                WifiConfigManager.createDebugTimeStampString(TEST_WALLCLOCK_UPDATE_TIME_MILLIS);
    }

    private void assertNotEquals(Object expected, Object actual) {
        if (actual != null) {
            assertFalse(actual.equals(expected));
        } else {
            assertNotNull(expected);
        }
    }

    /**
     * Modifies the provided WifiConfiguration with the specified bssid value. Also, asserts that
     * the existing |BSSID| field is not the same value as the one being set
     */
    private void assertAndSetNetworkBSSID(WifiConfiguration configuration, String bssid) {
        assertNotEquals(bssid, configuration.BSSID);
        configuration.BSSID = bssid;
    }

    /**
     * Modifies the provided WifiConfiguration with the specified |IpConfiguration| object. Also,
     * asserts that the existing |mIpConfiguration| field is not the same value as the one being set
     */
    private void assertAndSetNetworkIpConfiguration(
            WifiConfiguration configuration, IpConfiguration ipConfiguration) {
        assertNotEquals(ipConfiguration, configuration.getIpConfiguration());
        configuration.setIpConfiguration(ipConfiguration);
    }

    /**
     * Modifies the provided WifiConfiguration with the specified |wepKeys| value and
     * |wepTxKeyIndex|.
     */
    private void assertAndSetNetworkWepKeysAndTxIndex(
            WifiConfiguration configuration, String[] wepKeys, int wepTxKeyIdx) {
        assertNotEquals(wepKeys, configuration.wepKeys);
        assertNotEquals(wepTxKeyIdx, configuration.wepTxKeyIndex);
        configuration.wepKeys = Arrays.copyOf(wepKeys, wepKeys.length);
        configuration.wepTxKeyIndex = wepTxKeyIdx;
    }

    /**
     * Modifies the provided WifiConfiguration with the specified |preSharedKey| value.
     */
    private void assertAndSetNetworkPreSharedKey(
            WifiConfiguration configuration, String preSharedKey) {
        assertNotEquals(preSharedKey, configuration.preSharedKey);
        configuration.preSharedKey = preSharedKey;
    }

    /**
     * Modifies the provided WifiConfiguration with the specified enteprise |password| value.
     */
    private void assertAndSetNetworkEnterprisePassword(
            WifiConfiguration configuration, String password) {
        assertNotEquals(password, configuration.enterpriseConfig.getPassword());
        configuration.enterpriseConfig.setPassword(password);
    }

    /**
     * Helper method to capture the store data written in WifiConfigStore.write() method.
     */
    private WifiConfigStoreData captureWriteStoreData() {
        try {
            ArgumentCaptor<WifiConfigStoreData> storeDataCaptor =
                    ArgumentCaptor.forClass(WifiConfigStoreData.class);
            mContextConfigStoreMockOrder.verify(mWifiConfigStore)
                    .write(anyBoolean(), storeDataCaptor.capture());
            return storeDataCaptor.getValue();
        } catch (Exception e) {
            fail("Exception encountered during write " + e);
        }
        return null;
    }

    /**
     * Returns whether the provided network was in the store data or not.
     */
    private boolean isNetworkInConfigStoreData(WifiConfiguration configuration) {
        WifiConfigStoreData storeData = captureWriteStoreData();
        if (storeData == null) {
            return false;
        }
        return isNetworkInConfigStoreData(configuration, storeData);
    }

    /**
     * Returns whether the provided network was in the store data or not.
     */
    private boolean isNetworkInConfigStoreData(
            WifiConfiguration configuration, WifiConfigStoreData storeData) {
        boolean foundNetworkInStoreData = false;
        for (WifiConfiguration retrievedConfig : storeData.getConfigurations()) {
            if (retrievedConfig.configKey().equals(configuration.configKey())) {
                foundNetworkInStoreData = true;
                break;
            }
        }
        return foundNetworkInStoreData;
    }

    /**
     * Verifies that the provided network was not present in the last config store write.
     */
    private void verifyNetworkNotInConfigStoreData(WifiConfiguration configuration) {
        assertFalse(isNetworkInConfigStoreData(configuration));
    }

    /**
     * Verifies that the provided network was present in the last config store write.
     */
    private void verifyNetworkInConfigStoreData(WifiConfiguration configuration) {
        assertTrue(isNetworkInConfigStoreData(configuration));
    }

    private void assertPasswordsMaskedInWifiConfiguration(WifiConfiguration configuration) {
        if (!TextUtils.isEmpty(configuration.preSharedKey)) {
            assertEquals(WifiConfigManager.PASSWORD_MASK, configuration.preSharedKey);
        }
        if (configuration.wepKeys != null) {
            for (int i = 0; i < configuration.wepKeys.length; i++) {
                if (!TextUtils.isEmpty(configuration.wepKeys[i])) {
                    assertEquals(WifiConfigManager.PASSWORD_MASK, configuration.wepKeys[i]);
                }
            }
        }
        if (!TextUtils.isEmpty(configuration.enterpriseConfig.getPassword())) {
            assertEquals(
                    WifiConfigManager.PASSWORD_MASK,
                    configuration.enterpriseConfig.getPassword());
        }
    }

    /**
     * Verifies that the network was present in the network change broadcast and returns the
     * change reason.
     */
    private int verifyNetworkInBroadcastAndReturnReason(WifiConfiguration configuration) {
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        ArgumentCaptor<UserHandle> userHandleCaptor = ArgumentCaptor.forClass(UserHandle.class);
        mContextConfigStoreMockOrder.verify(mContext)
                .sendBroadcastAsUser(intentCaptor.capture(), userHandleCaptor.capture());

        assertEquals(userHandleCaptor.getValue(), UserHandle.ALL);
        Intent intent = intentCaptor.getValue();

        int changeReason = intent.getIntExtra(WifiManager.EXTRA_CHANGE_REASON, -1);
        WifiConfiguration retrievedConfig =
                (WifiConfiguration) intent.getExtra(WifiManager.EXTRA_WIFI_CONFIGURATION);
        assertEquals(retrievedConfig.configKey(), configuration.configKey());

        // Verify that all the passwords are masked in the broadcast configuration.
        assertPasswordsMaskedInWifiConfiguration(retrievedConfig);

        return changeReason;
    }

    /**
     * Verifies that we sent out an add broadcast with the provided network.
     */
    private void verifyNetworkAddBroadcast(WifiConfiguration configuration) {
        assertEquals(
                verifyNetworkInBroadcastAndReturnReason(configuration),
                WifiManager.CHANGE_REASON_ADDED);
    }

    /**
     * Verifies that we sent out an update broadcast with the provided network.
     */
    private void verifyNetworkUpdateBroadcast(WifiConfiguration configuration) {
        assertEquals(
                verifyNetworkInBroadcastAndReturnReason(configuration),
                WifiManager.CHANGE_REASON_CONFIG_CHANGE);
    }

    /**
     * Verifies that we sent out a remove broadcast with the provided network.
     */
    private void verifyNetworkRemoveBroadcast(WifiConfiguration configuration) {
        assertEquals(
                verifyNetworkInBroadcastAndReturnReason(configuration),
                WifiManager.CHANGE_REASON_REMOVED);
    }

    /**
     * Adds the provided configuration to WifiConfigManager and modifies the provided configuration
     * with creator/update uid, package name and time. This also sets defaults for fields not
     * populated.
     * These fields are populated internally by WifiConfigManager and hence we need
     * to modify the configuration before we compare the added network with the retrieved network.
     */
    private NetworkUpdateResult addNetworkToWifiConfigManager(WifiConfiguration configuration) {
        when(mClock.getWallClockMillis()).thenReturn(TEST_WALLCLOCK_CREATION_TIME_MILLIS);
        NetworkUpdateResult result =
                mWifiConfigManager.addOrUpdateNetwork(configuration, TEST_CREATOR_UID);
        setDefaults(configuration);
        setCreationDebugParams(configuration);
        configuration.networkId = result.getNetworkId();
        return result;
    }

    /**
     * Add network to WifiConfigManager and ensure that it was successful.
     */
    private NetworkUpdateResult verifyAddNetworkToWifiConfigManager(
            WifiConfiguration configuration) {
        NetworkUpdateResult result = addNetworkToWifiConfigManager(configuration);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);
        assertTrue(result.isNewNetwork());
        assertTrue(result.hasIpChanged());
        assertTrue(result.hasProxyChanged());

        verifyNetworkAddBroadcast(configuration);
        // Verify that the config store write was triggered with this new configuration.
        verifyNetworkInConfigStoreData(configuration);
        return result;
    }

    /**
     * Add ephemeral network to WifiConfigManager and ensure that it was successful.
     */
    private NetworkUpdateResult verifyAddEphemeralNetworkToWifiConfigManager(
            WifiConfiguration configuration) throws Exception {
        NetworkUpdateResult result = addNetworkToWifiConfigManager(configuration);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);
        assertTrue(result.isNewNetwork());
        assertTrue(result.hasIpChanged());
        assertTrue(result.hasProxyChanged());

        verifyNetworkAddBroadcast(configuration);
        // Ensure that the write was not invoked for ephemeral network addition.
        mContextConfigStoreMockOrder.verify(mWifiConfigStore, never())
                .write(anyBoolean(), any(WifiConfigStoreData.class));
        return result;
    }

    /**
     * Updates the provided configuration to WifiConfigManager and modifies the provided
     * configuration with update uid, package name and time.
     * These fields are populated internally by WifiConfigManager and hence we need
     * to modify the configuration before we compare the added network with the retrieved network.
     */
    private NetworkUpdateResult updateNetworkToWifiConfigManager(WifiConfiguration configuration) {
        when(mClock.getWallClockMillis()).thenReturn(TEST_WALLCLOCK_UPDATE_TIME_MILLIS);
        NetworkUpdateResult result =
                mWifiConfigManager.addOrUpdateNetwork(configuration, TEST_UPDATE_UID);
        setUpdateDebugParams(configuration);
        return result;
    }

    /**
     * Update network to WifiConfigManager config change and ensure that it was successful.
     */
    private NetworkUpdateResult verifyUpdateNetworkToWifiConfigManager(
            WifiConfiguration configuration) {
        NetworkUpdateResult result = updateNetworkToWifiConfigManager(configuration);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);
        assertFalse(result.isNewNetwork());

        verifyNetworkUpdateBroadcast(configuration);
        // Verify that the config store write was triggered with this new configuration.
        verifyNetworkInConfigStoreData(configuration);
        return result;
    }

    /**
     * Update network to WifiConfigManager without IP config change and ensure that it was
     * successful.
     */
    private NetworkUpdateResult verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(
            WifiConfiguration configuration) {
        NetworkUpdateResult result = verifyUpdateNetworkToWifiConfigManager(configuration);
        assertFalse(result.hasIpChanged());
        assertFalse(result.hasProxyChanged());
        return result;
    }

    /**
     * Update network to WifiConfigManager with IP config change and ensure that it was
     * successful.
     */
    private NetworkUpdateResult verifyUpdateNetworkToWifiConfigManagerWithIpChange(
            WifiConfiguration configuration) {
        NetworkUpdateResult result = verifyUpdateNetworkToWifiConfigManager(configuration);
        assertTrue(result.hasIpChanged());
        assertTrue(result.hasProxyChanged());
        return result;
    }

    /**
     * Removes network from WifiConfigManager and ensure that it was successful.
     */
    private void verifyRemoveNetworkFromWifiConfigManager(
            WifiConfiguration configuration) {
        assertTrue(mWifiConfigManager.removeNetwork(configuration.networkId, TEST_CREATOR_UID));

        verifyNetworkRemoveBroadcast(configuration);
        // Verify if the config store write was triggered without this new configuration.
        verifyNetworkNotInConfigStoreData(configuration);
    }

    /**
     * Removes ephemeral network from WifiConfigManager and ensure that it was successful.
     */
    private void verifyRemoveEphemeralNetworkFromWifiConfigManager(
            WifiConfiguration configuration) throws  Exception {
        assertTrue(mWifiConfigManager.removeNetwork(configuration.networkId, TEST_CREATOR_UID));

        verifyNetworkRemoveBroadcast(configuration);
        // Ensure that the write was not invoked for ephemeral network remove.
        mContextConfigStoreMockOrder.verify(mWifiConfigStore, never())
                .write(anyBoolean(), any(WifiConfigStoreData.class));
    }

    /**
     * Verifies the provided network's public status and ensures that the network change broadcast
     * has been sent out.
     */
    private void verifyUpdateNetworkStatus(WifiConfiguration configuration, int status) {
        assertEquals(status, configuration.status);
        verifyNetworkUpdateBroadcast(configuration);
    }

    /**
     * Verifies the network's selection status update.
     *
     * For temporarily disabled reasons, the method ensures that the status has changed only if
     * disable reason counter has exceeded the threshold.
     *
     * For permanently disabled/enabled reasons, the method ensures that the public status has
     * changed and the network change broadcast has been sent out.
     */
    private void verifyUpdateNetworkSelectionStatus(
            int networkId, int reason, int temporaryDisableReasonCounter) {
        when(mClock.getElapsedSinceBootMillis())
                .thenReturn(TEST_ELAPSED_UPDATE_NETWORK_SELECTION_TIME_MILLIS);

        // Fetch the current status of the network before we try to update the status.
        WifiConfiguration retrievedNetwork = mWifiConfigManager.getConfiguredNetwork(networkId);
        NetworkSelectionStatus currentStatus = retrievedNetwork.getNetworkSelectionStatus();
        int currentDisableReason = currentStatus.getNetworkSelectionDisableReason();

        // First set the status to the provided reason.
        assertTrue(mWifiConfigManager.updateNetworkSelectionStatus(networkId, reason));

        // Now fetch the network configuration and verify the new status of the network.
        retrievedNetwork = mWifiConfigManager.getConfiguredNetwork(networkId);

        NetworkSelectionStatus retrievedStatus = retrievedNetwork.getNetworkSelectionStatus();
        int retrievedDisableReason = retrievedStatus.getNetworkSelectionDisableReason();
        long retrievedDisableTime = retrievedStatus.getDisableTime();
        int retrievedDisableReasonCounter = retrievedStatus.getDisableReasonCounter(reason);
        int disableReasonThreshold =
                WifiConfigManager.NETWORK_SELECTION_DISABLE_THRESHOLD[reason];

        if (reason == NetworkSelectionStatus.NETWORK_SELECTION_ENABLE) {
            assertEquals(reason, retrievedDisableReason);
            assertTrue(retrievedStatus.isNetworkEnabled());
            assertEquals(
                    NetworkSelectionStatus.INVALID_NETWORK_SELECTION_DISABLE_TIMESTAMP,
                    retrievedDisableTime);
            verifyUpdateNetworkStatus(retrievedNetwork, WifiConfiguration.Status.ENABLED);
        } else if (reason < NetworkSelectionStatus.DISABLED_TLS_VERSION_MISMATCH) {
            // For temporarily disabled networks, we need to ensure that the current status remains
            // until the threshold is crossed.
            assertEquals(temporaryDisableReasonCounter, retrievedDisableReasonCounter);
            if (retrievedDisableReasonCounter < disableReasonThreshold) {
                assertEquals(currentDisableReason, retrievedDisableReason);
                assertEquals(
                        currentStatus.getNetworkSelectionStatus(),
                        retrievedStatus.getNetworkSelectionStatus());
            } else {
                assertEquals(reason, retrievedDisableReason);
                assertTrue(retrievedStatus.isNetworkTemporaryDisabled());
                assertEquals(
                        TEST_ELAPSED_UPDATE_NETWORK_SELECTION_TIME_MILLIS, retrievedDisableTime);
            }
        } else if (reason < NetworkSelectionStatus.NETWORK_SELECTION_DISABLED_MAX) {
            assertEquals(reason, retrievedDisableReason);
            assertTrue(retrievedStatus.isNetworkPermanentlyDisabled());
            assertEquals(
                    NetworkSelectionStatus.INVALID_NETWORK_SELECTION_DISABLE_TIMESTAMP,
                    retrievedDisableTime);
            verifyUpdateNetworkStatus(retrievedNetwork, WifiConfiguration.Status.DISABLED);
        }
    }

    /**
     * Creates a scan detail corresponding to the provided network and given BSSID, level &frequency
     * values.
     */
    private ScanDetail createScanDetailForNetwork(
            WifiConfiguration configuration, String bssid, int level, int frequency) {
        String caps;
        if (configuration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) {
            caps = "[WPA2-PSK-CCMP]";
        } else if (configuration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP)
                || configuration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X)) {
            caps = "[WPA2-EAP-CCMP]";
        } else if (configuration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)
                && WifiConfigurationUtil.hasAnyValidWepKey(configuration.wepKeys)) {
            caps = "[WEP]";
        } else {
            caps = "[]";
        }
        WifiSsid ssid = WifiSsid.createFromAsciiEncoded(configuration.getPrintableSsid());
        // Fill in 0's in the fields we don't care about.
        return new ScanDetail(
                ssid, bssid, caps, level, frequency, mClock.getUptimeSinceBootMillis(),
                mClock.getWallClockMillis());
    }

    /**
     * Creates a scan detail corresponding to the provided network and BSSID value.
     */
    private ScanDetail createScanDetailForNetwork(WifiConfiguration configuration, String bssid) {
        return createScanDetailForNetwork(configuration, bssid, 0, 0);
    }

    /**
     * Creates a scan detail corresponding to the provided network and fixed BSSID value.
     */
    private ScanDetail createScanDetailForNetwork(WifiConfiguration configuration) {
        return createScanDetailForNetwork(configuration, TEST_BSSID);
    }

    /**
     * Adds the provided network and then creates a scan detail corresponding to the network. The
     * method then creates a ScanDetail corresponding to the network and ensures that the network
     * is properly matched using
     * {@link WifiConfigManager#getSavedNetworkForScanDetailAndCache(ScanDetail)} and also
     * verifies that the provided scan detail was cached,
     */
    private void verifyAddSingleNetworkAndMatchScanDetailToNetworkAndCache(
            WifiConfiguration network) {
        // First add the provided network.
        verifyAddNetworkToWifiConfigManager(network);

        // Now create a dummy scan detail corresponding to the network.
        ScanDetail scanDetail = createScanDetailForNetwork(network);
        ScanResult scanResult = scanDetail.getScanResult();

        WifiConfiguration retrievedNetwork =
                mWifiConfigManager.getSavedNetworkForScanDetailAndCache(scanDetail);
        // Retrieve the network with password data for comparison.
        retrievedNetwork =
                mWifiConfigManager.getConfiguredNetworkWithPassword(retrievedNetwork.networkId);

        WifiConfigurationTestUtil.assertConfigurationEqualForConfigManagerAddOrUpdate(
                network, retrievedNetwork);

        // Now retrieve the scan detail cache and ensure that the new scan detail is in cache.
        ScanDetailCache retrievedScanDetailCache =
                mWifiConfigManager.getScanDetailCacheForNetwork(network.networkId);
        assertEquals(1, retrievedScanDetailCache.size());
        ScanResult retrievedScanResult = retrievedScanDetailCache.get(scanResult.BSSID);

        ScanTestUtil.assertScanResultEquals(scanResult, retrievedScanResult);
    }

    /**
     * Adds a new network and verifies that the |HasEverConnected| flag is set to false.
     */
    private void verifyAddNetworkHasEverConnectedFalse(WifiConfiguration network) {
        NetworkUpdateResult result = verifyAddNetworkToWifiConfigManager(network);
        WifiConfiguration retrievedNetwork =
                mWifiConfigManager.getConfiguredNetwork(result.getNetworkId());
        assertFalse("Adding a new network should not have hasEverConnected set to true.",
                retrievedNetwork.getNetworkSelectionStatus().getHasEverConnected());
    }

    /**
     * Updates an existing network with some credential change and verifies that the
     * |HasEverConnected| flag is set to false.
     */
    private void verifyUpdateNetworkWithCredentialChangeHasEverConnectedFalse(
            WifiConfiguration network) {
        NetworkUpdateResult result = verifyUpdateNetworkToWifiConfigManagerWithoutIpChange(network);
        WifiConfiguration retrievedNetwork =
                mWifiConfigManager.getConfiguredNetwork(result.getNetworkId());
        assertFalse("Updating network credentials config should clear hasEverConnected.",
                retrievedNetwork.getNetworkSelectionStatus().getHasEverConnected());
    }

    /**
     * Updates an existing network after connection using
     * {@link WifiConfigManager#updateNetworkAfterConnect(int)} and asserts that the
     * |HasEverConnected| flag is set to true.
     */
    private void verifyUpdateNetworkAfterConnectHasEverConnectedTrue(int networkId) {
        assertTrue(mWifiConfigManager.updateNetworkAfterConnect(networkId));
        WifiConfiguration retrievedNetwork = mWifiConfigManager.getConfiguredNetwork(networkId);
        assertTrue("hasEverConnected expected to be true after connection.",
                retrievedNetwork.getNetworkSelectionStatus().getHasEverConnected());
    }

    /**
     * Sets up a user profiles for WifiConfigManager testing.
     *
     * @param userId Id of the user.
     */
    private void setupUserProfiles(int userId) {
        final UserInfo userInfo =
                new UserInfo(userId, Integer.toString(userId), UserInfo.FLAG_PRIMARY);
        List<UserInfo> userProfiles = Arrays.asList(userInfo);
        when(mUserManager.getProfiles(userId)).thenReturn(userProfiles);
        when(mUserManager.isUserUnlockingOrUnlocked(userId)).thenReturn(true);
    }

}
