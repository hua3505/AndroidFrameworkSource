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
import static android.net.wifi.WifiManager.PASSPOINT_ICON_RECEIVED_ACTION;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.EAPConstants;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.HomeSP;
import android.os.UserHandle;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.wifi.FakeKeys;
import com.android.server.wifi.IMSIParameter;
import com.android.server.wifi.SIMAccessor;
import com.android.server.wifi.WifiInjector;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link com.android.server.wifi.hotspot2.PasspointManager}.
 */
@SmallTest
public class PasspointManagerTest {
    private static final long BSSID = 0x112233445566L;
    private static final String ICON_FILENAME = "test";
    private static final String TEST_FQDN = "test1.test.com";
    private static final String TEST_FQDN1 = "test.com";
    private static final String TEST_FRIENDLY_NAME = "friendly name";
    private static final String TEST_REALM = "realm.test.com";
    private static final String TEST_IMSI = "1234*";

    @Mock Context mContext;
    @Mock WifiInjector mWifiInjector;
    @Mock PasspointEventHandler.Callbacks mCallbacks;
    @Mock SIMAccessor mSimAccessor;
    PasspointManager mManager;

    /** Sets up test. */
    @Before
    public void setUp() throws Exception {
        initMocks(this);
        mManager = new PasspointManager(mContext, mWifiInjector, mSimAccessor);
        ArgumentCaptor<PasspointEventHandler.Callbacks> callbacks =
                ArgumentCaptor.forClass(PasspointEventHandler.Callbacks.class);
        verify(mWifiInjector).makePasspointEventHandler(callbacks.capture());
        mCallbacks = callbacks.getValue();
    }

    /**
     * Verify PASSPOINT_ICON_RECEIVED_ACTION broadcast intent.
     * @param bssid BSSID of the AP
     * @param fileName Name of the icon file
     * @param data icon data byte array
     */
    private void verifyIconIntent(long bssid, String fileName, byte[] data) {
        ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);
        verify(mContext).sendBroadcastAsUser(intent.capture(), eq(UserHandle.ALL));
        assertEquals(PASSPOINT_ICON_RECEIVED_ACTION, intent.getValue().getAction());
        assertTrue(intent.getValue().getExtras().containsKey(EXTRA_PASSPOINT_ICON_BSSID));
        assertEquals(bssid, intent.getValue().getExtras().getLong(EXTRA_PASSPOINT_ICON_BSSID));
        assertTrue(intent.getValue().getExtras().containsKey(EXTRA_PASSPOINT_ICON_FILE));
        assertEquals(fileName, intent.getValue().getExtras().getString(EXTRA_PASSPOINT_ICON_FILE));
        if (data != null) {
            assertTrue(intent.getValue().getExtras().containsKey(EXTRA_PASSPOINT_ICON_DATA));
            assertEquals(data,
                         intent.getValue().getExtras().getByteArray(EXTRA_PASSPOINT_ICON_DATA));
        }
    }

    /**
     * Verify that the given Passpoint configuration matches the one that's added to
     * the PasspointManager.
     *
     * @param expectedConfig The expected installed Passpoint configuration
     */
    private void verifyInstalledConfig(PasspointConfiguration expectedConfig) {
        List<PasspointConfiguration> installedConfigs = mManager.getProviderConfigs();
        assertEquals(1, installedConfigs.size());
        assertEquals(expectedConfig, installedConfigs.get(0));
    }

    /**
     * Validate the broadcast intent when icon file retrieval succeeded.
     *
     * @throws Exception
     */
    @Test
    public void iconResponseSuccess() throws Exception {
        byte[] iconData = new byte[] {0x00, 0x11};
        mCallbacks.onIconResponse(BSSID, ICON_FILENAME, iconData);
        verifyIconIntent(BSSID, ICON_FILENAME, iconData);
    }

    /**
     * Validate the broadcast intent when icon file retrieval failed.
     *
     * @throws Exception
     */
    @Test
    public void iconResponseFailure() throws Exception {
        mCallbacks.onIconResponse(BSSID, ICON_FILENAME, null);
        verifyIconIntent(BSSID, ICON_FILENAME, null);
    }

    /**
     * Verify that adding a provider with a null configuration will fail.
     *
     * @throws Exception
     */
    @Test
    public void addProviderWithNullConfig() throws Exception {
        assertFalse(mManager.addProvider(null));
    }

    /**
     * Verify that adding a provider with a empty configuration will fail.
     *
     * @throws Exception
     */
    @Test
    public void addProviderWithEmptyConfig() throws Exception {
        assertFalse(mManager.addProvider(new PasspointConfiguration()));
    }

    /**
     * Verify taht adding a provider with an invalid credential will fail (using EAP-TLS
     * for user credential).
     *
     * @throws Exception
     */
    @Test
    public void addProviderWithInvalidCredential() throws Exception {
        PasspointConfiguration config = new PasspointConfiguration();
        config.homeSp = new HomeSP();
        config.homeSp.fqdn = TEST_FQDN;
        config.homeSp.friendlyName = TEST_FRIENDLY_NAME;
        config.credential = new Credential();
        config.credential.realm = TEST_REALM;
        config.credential.caCertificate = FakeKeys.CA_CERT0;
        config.credential.userCredential = new Credential.UserCredential();
        config.credential.userCredential.username = "username";
        config.credential.userCredential.password = "password";
        // EAP-TLS not allowed for user credential.
        config.credential.userCredential.eapType = EAPConstants.EAP_TLS;
        config.credential.userCredential.nonEapInnerMethod = "MS-CHAP";
        assertFalse(mManager.addProvider(config));
    }

    /**
     * Verify that adding a provider with a valid configuration and user credential will succeed.
     *
     * @throws Exception
     */
    @Test
    public void addRemoveProviderWithValidUserCredential() throws Exception {
        PasspointConfiguration config = new PasspointConfiguration();
        config.homeSp = new HomeSP();
        config.homeSp.fqdn = TEST_FQDN;
        config.homeSp.friendlyName = TEST_FRIENDLY_NAME;
        config.credential = new Credential();
        config.credential.realm = TEST_REALM;
        config.credential.caCertificate = FakeKeys.CA_CERT0;
        config.credential.userCredential = new Credential.UserCredential();
        config.credential.userCredential.username = "username";
        config.credential.userCredential.password = "password";
        config.credential.userCredential.eapType = EAPConstants.EAP_TTLS;
        config.credential.userCredential.nonEapInnerMethod = "MS-CHAP";
        assertTrue(mManager.addProvider(config));
        verifyInstalledConfig(config);

        // Remove the provider.
        assertTrue(mManager.removeProvider(TEST_FQDN));
        assertEquals(null, mManager.getProviderConfigs());
    }

    /**
     * Verify that adding a provider with a valid configuration and SIM credential will succeed.
     *
     * @throws Exception
     */
    @Test
    public void addRemoveProviderWithValidSimCredential() throws Exception {
        PasspointConfiguration config = new PasspointConfiguration();
        config.homeSp = new HomeSP();
        config.homeSp.fqdn = TEST_FQDN;
        config.homeSp.friendlyName = TEST_FRIENDLY_NAME;
        config.credential = new Credential();
        config.credential.realm = TEST_REALM;
        config.credential.simCredential = new Credential.SimCredential();
        config.credential.simCredential.imsi = TEST_IMSI;
        config.credential.simCredential.eapType = EAPConstants.EAP_SIM;
        when(mSimAccessor.getMatchingImsis(new IMSIParameter(TEST_IMSI)))
                .thenReturn(new ArrayList<String>());
        assertTrue(mManager.addProvider(config));
        verifyInstalledConfig(config);

        // Remove the provider.
        assertTrue(mManager.removeProvider(TEST_FQDN));
        assertEquals(null, mManager.getProviderConfigs());
    }

    /**
     * Verify that adding a provider with an invalid SIM credential (configured IMSI doesn't
     * match the IMSI of the installed SIM cards) will fail.
     *
     * @throws Exception
     */
    @Test
    public void addProviderWithValidSimCredentialWithInvalidIMSI() throws Exception {
        PasspointConfiguration config = new PasspointConfiguration();
        config.homeSp = new HomeSP();
        config.homeSp.fqdn = TEST_FQDN;
        config.homeSp.friendlyName = TEST_FRIENDLY_NAME;
        config.credential = new Credential();
        config.credential.realm = TEST_REALM;
        config.credential.simCredential = new Credential.SimCredential();
        config.credential.simCredential.imsi = TEST_IMSI;
        config.credential.simCredential.eapType = EAPConstants.EAP_SIM;
        when(mSimAccessor.getMatchingImsis(new IMSIParameter(TEST_IMSI))).thenReturn(null);
        assertFalse(mManager.addProvider(config));
    }

    /**
     * Verify that adding a provider with the same base domain as the existing provider will
     * succeed, and verify that the existing provider is replaced by the new provider with
     * the new configuration.
     *
     * @throws Exception
     */
    @Test
    public void addProviderWithExistingConfig() throws Exception {
        // Add a provider with the original configuration.
        PasspointConfiguration origConfig = new PasspointConfiguration();
        origConfig.homeSp = new HomeSP();
        origConfig.homeSp.fqdn = TEST_FQDN;
        origConfig.homeSp.friendlyName = TEST_FRIENDLY_NAME;
        origConfig.credential = new Credential();
        origConfig.credential.realm = TEST_REALM;
        origConfig.credential.simCredential = new Credential.SimCredential();
        origConfig.credential.simCredential.imsi = TEST_IMSI;
        origConfig.credential.simCredential.eapType = EAPConstants.EAP_SIM;
        when(mSimAccessor.getMatchingImsis(new IMSIParameter(TEST_IMSI)))
                .thenReturn(new ArrayList<String>());
        assertTrue(mManager.addProvider(origConfig));
        verifyInstalledConfig(origConfig);

        // Add another provider with the same base domain as the existing provider.
        // This should replace the existing provider with the new configuration.
        PasspointConfiguration newConfig = new PasspointConfiguration();
        newConfig.homeSp = new HomeSP();
        newConfig.homeSp.fqdn = TEST_FQDN1;
        newConfig.homeSp.friendlyName = TEST_FRIENDLY_NAME;
        newConfig.credential = new Credential();
        newConfig.credential.realm = TEST_REALM;
        newConfig.credential.caCertificate = FakeKeys.CA_CERT0;
        newConfig.credential.userCredential = new Credential.UserCredential();
        newConfig.credential.userCredential.username = "username";
        newConfig.credential.userCredential.password = "password";
        newConfig.credential.userCredential.eapType = EAPConstants.EAP_TTLS;
        newConfig.credential.userCredential.nonEapInnerMethod = "MS-CHAP";
        assertTrue(mManager.addProvider(newConfig));
        verifyInstalledConfig(newConfig);
    }
}
