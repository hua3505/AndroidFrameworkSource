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

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Test;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Unit tests for {@link com.android.server.wifi.WifiConfigStoreData}.
 */
@SmallTest
public class WifiConfigStoreDataTest {

    private static final String TEST_SSID = "WifiConfigStoreDataSSID_";
    private static final String TEST_CONNECT_CHOICE = "XmlUtilConnectChoice";
    private static final long TEST_CONNECT_CHOICE_TIMESTAMP = 0x4566;
    private static final Set<String> TEST_DELETED_EPHEMERAL_LIST = new HashSet<String>() {
        {
            add("\"" + TEST_SSID + "1\"");
            add("\"" + TEST_SSID + "2\"");
        }
    };
    private static final String SINGLE_OPEN_NETWORK_LIST_XML_STRING_FORMAT =
            "<NetworkList>\n"
                    + "<Network>\n"
                    + "<WifiConfiguration>\n"
                    + "<string name=\"ConfigKey\">%s</string>\n"
                    + "<string name=\"SSID\">%s</string>\n"
                    + "<null name=\"BSSID\" />\n"
                    + "<null name=\"PreSharedKey\" />\n"
                    + "<null name=\"WEPKeys\" />\n"
                    + "<int name=\"WEPTxKeyIndex\" value=\"0\" />\n"
                    + "<boolean name=\"HiddenSSID\" value=\"false\" />\n"
                    + "<boolean name=\"RequirePMF\" value=\"false\" />\n"
                    + "<byte-array name=\"AllowedKeyMgmt\" num=\"1\">01</byte-array>\n"
                    + "<byte-array name=\"AllowedProtocols\" num=\"0\"></byte-array>\n"
                    + "<byte-array name=\"AllowedAuthAlgos\" num=\"0\"></byte-array>\n"
                    + "<byte-array name=\"AllowedGroupCiphers\" num=\"0\"></byte-array>\n"
                    + "<byte-array name=\"AllowedPairwiseCiphers\" num=\"0\"></byte-array>\n"
                    + "<boolean name=\"Shared\" value=\"%s\" />\n"
                    + "<null name=\"FQDN\" />\n"
                    + "<null name=\"ProviderFriendlyName\" />\n"
                    + "<null name=\"LinkedNetworksList\" />\n"
                    + "<null name=\"DefaultGwMacAddress\" />\n"
                    + "<boolean name=\"ValidatedInternetAccess\" value=\"false\" />\n"
                    + "<boolean name=\"NoInternetAccessExpected\" value=\"false\" />\n"
                    + "<int name=\"UserApproved\" value=\"0\" />\n"
                    + "<boolean name=\"MeteredHint\" value=\"false\" />\n"
                    + "<boolean name=\"UseExternalScores\" value=\"false\" />\n"
                    + "<int name=\"NumAssociation\" value=\"0\" />\n"
                    + "<int name=\"CreatorUid\" value=\"%d\" />\n"
                    + "<null name=\"CreatorName\" />\n"
                    + "<null name=\"CreationTime\" />\n"
                    + "<int name=\"LastUpdateUid\" value=\"-1\" />\n"
                    + "<null name=\"LastUpdateName\" />\n"
                    + "<int name=\"LastConnectUid\" value=\"0\" />\n"
                    + "</WifiConfiguration>\n"
                    + "<NetworkStatus>\n"
                    + "<string name=\"SelectionStatus\">NETWORK_SELECTION_ENABLED</string>\n"
                    + "<string name=\"DisableReason\">NETWORK_SELECTION_ENABLE</string>\n"
                    + "<null name=\"ConnectChoice\" />\n"
                    + "<long name=\"ConnectChoiceTimeStamp\" value=\"-1\" />\n"
                    + "<boolean name=\"HasEverConnected\" value=\"false\" />\n"
                    + "</NetworkStatus>\n"
                    + "<IpConfiguration>\n"
                    + "<string name=\"IpAssignment\">DHCP</string>\n"
                    + "<string name=\"ProxySettings\">NONE</string>\n"
                    + "</IpConfiguration>\n"
                    + "</Network>\n"
                    + "</NetworkList>\n";
    private static final String SINGLE_OPEN_NETWORK_SHARED_DATA_XML_STRING_FORMAT =
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                    + "<WifiConfigStoreData>\n"
                    + "<int name=\"Version\" value=\"1\" />\n"
                    + SINGLE_OPEN_NETWORK_LIST_XML_STRING_FORMAT
                    + "</WifiConfigStoreData>\n";
    private static final String SINGLE_OPEN_NETWORK_USER_DATA_XML_STRING_FORMAT =
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                    + "<WifiConfigStoreData>\n"
                    + "<int name=\"Version\" value=\"1\" />\n"
                    + SINGLE_OPEN_NETWORK_LIST_XML_STRING_FORMAT
                    + "<DeletedEphemeralSSIDList>\n"
                    + "<set name=\"SSIDList\" />\n"
                    + "</DeletedEphemeralSSIDList>\n"
                    + "</WifiConfigStoreData>\n";

    /**
     * Asserts that the 2 config store data are equal.
     */
    public static void assertConfigStoreDataEqual(
            WifiConfigStoreData expected, WifiConfigStoreData actual) {
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigStore(
                expected.getConfigurations(), actual.getConfigurations());
        assertEquals(expected.getDeletedEphemeralSSIDs(), actual.getDeletedEphemeralSSIDs());
    }

    /**
     * Verify that multiple shared networks with different credential types and IpConfiguration
     * types are serialized and deserialized correctly.
     */
    @Test
    public void testMultipleNetworkAllShared()
            throws XmlPullParserException, IOException {
        List<WifiConfiguration> configurations = createNetworks(true);
        serializeDeserializeConfigStoreData(configurations, new ArrayList<WifiConfiguration>());
    }

    /**
     * Verify that multiple user networks with different credential types and IpConfiguration
     * types are serialized and deserialized correctly.
     */
    @Test
    public void testMultipleNetworksAllUser()
            throws XmlPullParserException, IOException {
        List<WifiConfiguration> configurations = createNetworks(false);
        serializeDeserializeConfigStoreData(new ArrayList<WifiConfiguration>(), configurations);
    }

    /**
     * Verify that multiple networks with different credential types and IpConfiguration
     * types are serialized and deserialized correctly when both user & shared networks are present.
     */
    @Test
    public void testMultipleNetworksSharedAndUserNetworks()
            throws XmlPullParserException, IOException {
        List<WifiConfiguration> configurations = createNetworks();
        // Let's split the list of networks into 2 and make all the networks in the first list
        // shared and the second list all user networks.
        int listSize = configurations.size();
        List<WifiConfiguration> sharedConfigurations = configurations.subList(0, listSize / 2);
        List<WifiConfiguration> userConfigurations = configurations.subList(listSize / 2, listSize);
        for (WifiConfiguration config : sharedConfigurations) {
            config.shared = true;
        }
        for (WifiConfiguration config : userConfigurations) {
            config.shared = false;
        }
        serializeDeserializeConfigStoreData(sharedConfigurations, userConfigurations);
    }

    /**
     * Verify that multiple shared networks with different credential types and IpConfiguration
     * types are serialized and deserialized correctly when the shared data bytes are null in
     * |parseRawData| method.
     */
    @Test
    public void testMultipleNetworksSharedDataNullInParseRawData()
            throws XmlPullParserException, IOException {
        List<WifiConfiguration> configurations = createNetworks(false);
        serializeDeserializeConfigStoreData(
                new ArrayList<WifiConfiguration>(), configurations, true, false);
    }

    /**
     * Verify that multiple shared networks with different credential types and IpConfiguration
     * types are serialized and deserialized correctly when the user data bytes are null in
     * |parseRawData| method.
     */
    @Test
    public void testMultipleNetworksUserDataNullInParseRawData()
            throws XmlPullParserException, IOException {
        List<WifiConfiguration> configurations = createNetworks(true);
        serializeDeserializeConfigStoreData(
                configurations, new ArrayList<WifiConfiguration>(), false, true);
    }

    /**
     * Verify that a network with invalid entepriseConfig data is serialized/deserialized
     * correctly.
     */
    @Test
    public void testInvalidEnterpriseConfig()
            throws XmlPullParserException, IOException {
        WifiConfiguration eapNetwork = WifiConfigurationTestUtil.createEapNetwork();
        eapNetwork.enterpriseConfig = new WifiEnterpriseConfig();
        List<WifiConfiguration> configurations = Arrays.asList(eapNetwork);
        serializeDeserializeConfigStoreData(
                new ArrayList<WifiConfiguration>(), configurations, false, false);
    }

    /**
     * Verify that the manually populated xml string for is deserialized/serialized correctly.
     * This generates a store data corresponding to the XML string and verifies that the string
     * is indeed parsed correctly to the store data.
     */
    @Test
    public void testManualConfigStoreDataParse() {
        WifiConfiguration sharedNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        sharedNetwork.shared = true;
        sharedNetwork.setIpConfiguration(WifiConfigurationTestUtil.createDHCPIpConfigurationWithNoProxy());
        WifiConfiguration userNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        userNetwork.setIpConfiguration(WifiConfigurationTestUtil.createDHCPIpConfigurationWithNoProxy());
        userNetwork.shared = false;

        // Create the store data for comparison.
        List<WifiConfiguration> sharedNetworks = new ArrayList<>();
        List<WifiConfiguration> userNetworks = new ArrayList<>();
        sharedNetworks.add(sharedNetwork);
        userNetworks.add(userNetwork);
        WifiConfigStoreData storeData =
                new WifiConfigStoreData(sharedNetworks, userNetworks, new HashSet<String>());

        String sharedStoreXmlString =
                String.format(SINGLE_OPEN_NETWORK_SHARED_DATA_XML_STRING_FORMAT,
                        sharedNetwork.configKey().replaceAll("\"", "&quot;"),
                        sharedNetwork.SSID.replaceAll("\"", "&quot;"),
                        sharedNetwork.shared, sharedNetwork.creatorUid);
        String userStoreXmlString =
                String.format(SINGLE_OPEN_NETWORK_USER_DATA_XML_STRING_FORMAT,
                        userNetwork.configKey().replaceAll("\"", "&quot;"),
                        userNetwork.SSID.replaceAll("\"", "&quot;"),
                        userNetwork.shared, userNetwork.creatorUid);
        byte[] rawSharedData = sharedStoreXmlString.getBytes();
        byte[] rawUserData = userStoreXmlString.getBytes();
        WifiConfigStoreData retrievedStoreData = null;
        try {
            retrievedStoreData = WifiConfigStoreData.parseRawData(rawSharedData, rawUserData);
        } catch (Exception e) {
            // Assert if an exception was raised.
            fail("Error in parsing the xml data: " + e
                    + ". Shared data: " + sharedStoreXmlString
                    + ", User data: " + userStoreXmlString);
        }
        // Compare the retrieved config store data with the original.
        assertConfigStoreDataEqual(storeData, retrievedStoreData);

        // Now convert the store data to XML bytes and compare the output with the expected string.
        byte[] retrievedSharedStoreXmlBytes = null;
        byte[] retrievedUserStoreXmlBytes = null;
        try {
            retrievedSharedStoreXmlBytes = retrievedStoreData.createSharedRawData();
            retrievedUserStoreXmlBytes = retrievedStoreData.createUserRawData();
        } catch (Exception e) {
            // Assert if an exception was raised.
            fail("Error in writing the xml data: " + e);
        }
        String retrievedSharedStoreXmlString =
                new String(retrievedSharedStoreXmlBytes, StandardCharsets.UTF_8);
        String retrievedUserStoreXmlString =
                new String(retrievedUserStoreXmlBytes, StandardCharsets.UTF_8);
        assertEquals("Retrieved: " + retrievedSharedStoreXmlString
                + ", Expected: " + sharedStoreXmlString,
                sharedStoreXmlString, retrievedSharedStoreXmlString);
        assertEquals("Retrieved: " + retrievedUserStoreXmlString
                + ", Expected: " + userStoreXmlString,
                userStoreXmlString, retrievedUserStoreXmlString);
    }

    /**
     * Verify that XML with corrupted version provided to WifiConfigStoreData is ignored correctly.
     */
    @Test
    public void testCorruptVersionConfigStoreData() {
        String storeDataAsString =
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                        + "<WifiConfigStoreData>\n"
                        + "<int name=\"Version\" value=\"200\" />\n"
                        + "</WifiConfigStoreData>\n";
        byte[] rawData = storeDataAsString.getBytes();
        try {
            WifiConfigStoreData storeData = WifiConfigStoreData.parseRawData(rawData, rawData);
        } catch (Exception e) {
            return;
        }
        // Assert if there was no exception was raised.
        fail();
    }

    /**
     * Verify that XML with no network list provided to WifiConfigStoreData is ignored correctly.
     */
    @Test
    public void testCorruptNetworkListConfigStoreData() {
        String storeDataAsString =
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                        + "<WifiConfigStoreData>\n"
                        + "<int name=\"Version\" value=\"1\" />\n"
                        + "</WifiConfigStoreData>\n";
        byte[] rawData = storeDataAsString.getBytes();
        try {
            WifiConfigStoreData storeData = WifiConfigStoreData.parseRawData(rawData, rawData);
        } catch (Exception e) {
            return;
        }
        // Assert if there was no exception was raised.
        fail();
    }

    /**
     * Verify that any corrupted data provided to WifiConfigStoreData is ignored correctly.
     */
    @Test
    public void testRandomCorruptConfigStoreData() {
        Random random = new Random();
        byte[] rawData = new byte[100];
        random.nextBytes(rawData);
        try {
            WifiConfigStoreData storeData = WifiConfigStoreData.parseRawData(rawData, rawData);
        } catch (Exception e) {
            return;
        }
        // Assert if there was no exception was raised.
        fail();
    }

    /**
     * Helper method to add 4 networks with different credential types, IpConfiguration
     * types for all tests in the class.
     *
     * @return
     */
    private List<WifiConfiguration> createNetworks() {
        List<WifiConfiguration> configurations = new ArrayList<>();

        WifiConfiguration wepNetwork = WifiConfigurationTestUtil.createWepNetwork();
        wepNetwork.setIpConfiguration(WifiConfigurationTestUtil.createDHCPIpConfigurationWithPacProxy());
        wepNetwork.getNetworkSelectionStatus().setNetworkSelectionStatus(
                NetworkSelectionStatus.NETWORK_SELECTION_ENABLED);
        configurations.add(wepNetwork);

        WifiConfiguration pskNetwork = WifiConfigurationTestUtil.createPskNetwork();
        pskNetwork.setIpConfiguration(WifiConfigurationTestUtil.createStaticIpConfigurationWithPacProxy());
        pskNetwork.getNetworkSelectionStatus().setNetworkSelectionStatus(
                NetworkSelectionStatus.NETWORK_SELECTION_TEMPORARY_DISABLED);
        pskNetwork.getNetworkSelectionStatus().setNetworkSelectionDisableReason(
                NetworkSelectionStatus.DISABLED_ASSOCIATION_REJECTION);
        configurations.add(pskNetwork);

        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        openNetwork.setIpConfiguration(WifiConfigurationTestUtil.createStaticIpConfigurationWithStaticProxy());
        openNetwork.getNetworkSelectionStatus().setNetworkSelectionStatus(
                NetworkSelectionStatus.NETWORK_SELECTION_PERMANENTLY_DISABLED);
        openNetwork.getNetworkSelectionStatus().setNetworkSelectionDisableReason(
                NetworkSelectionStatus.DISABLED_BY_WIFI_MANAGER);
        configurations.add(openNetwork);

        WifiConfiguration eapNetwork = WifiConfigurationTestUtil.createEapNetwork();
        eapNetwork.setIpConfiguration(WifiConfigurationTestUtil.createPartialStaticIpConfigurationWithPacProxy());
        eapNetwork.getNetworkSelectionStatus().setConnectChoice(TEST_CONNECT_CHOICE);
        eapNetwork.getNetworkSelectionStatus().setConnectChoiceTimestamp(
                TEST_CONNECT_CHOICE_TIMESTAMP);
        eapNetwork.getNetworkSelectionStatus().setHasEverConnected(true);
        configurations.add(eapNetwork);

        return configurations;
    }

    private List<WifiConfiguration> createNetworks(boolean shared) {
        List<WifiConfiguration> configurations = createNetworks();
        for (WifiConfiguration config : configurations) {
            config.shared = shared;
        }
        return configurations;
    }

    /**
     * Helper method to serialize/deserialize store data.
     */
    private void serializeDeserializeConfigStoreData(
            List<WifiConfiguration> sharedConfigurations,
            List<WifiConfiguration> userConfigurations)
            throws XmlPullParserException, IOException {
        serializeDeserializeConfigStoreData(sharedConfigurations, userConfigurations, false, false);
    }

    /**
     * Helper method to ensure the the provided config store data is serialized/deserialized
     * correctly.
     * This method serialize the provided config store data instance to raw bytes in XML format
     * and then deserialzes the raw bytes back to a config store data instance. It then
     * compares that the original config store data matches with the deserialzed instance.
     *
     * @param sharedConfigurations list of configurations to be added in the shared store data instance.
     * @param userConfigurations list of configurations to be added in the user store data instance.
     * @param setSharedDataNull whether to set the shared data to null to simulate the non-existence
     *                          of the shared store file.
     * @param setUserDataNull whether to set the user data to null to simulate the non-existence
     *                        of the user store file.
     */
    private void serializeDeserializeConfigStoreData(
            List<WifiConfiguration> sharedConfigurations,
            List<WifiConfiguration> userConfigurations,
            boolean setSharedDataNull, boolean setUserDataNull)
            throws XmlPullParserException, IOException {
        // Will not work if both the flags are set because then we need to ignore the configuration
        // list as well.
        assertFalse(setSharedDataNull & setUserDataNull);

        Set<String> deletedEphemeralList;
        if (setUserDataNull) {
            deletedEphemeralList = new HashSet<>();
        } else {
            deletedEphemeralList = TEST_DELETED_EPHEMERAL_LIST;
        }

        // Serialize the data.
        WifiConfigStoreData storeData =
                new WifiConfigStoreData(
                        sharedConfigurations, userConfigurations, deletedEphemeralList);

        byte[] sharedDataBytes = null;
        byte[] userDataBytes = null;
        if (!setSharedDataNull) {
            sharedDataBytes = storeData.createSharedRawData();
        }
        if (!setUserDataNull) {
            userDataBytes = storeData.createUserRawData();
        }

        // Deserialize the data.
        WifiConfigStoreData retrievedStoreData =
                WifiConfigStoreData.parseRawData(sharedDataBytes, userDataBytes);
        assertConfigStoreDataEqual(storeData, retrievedStoreData);
    }
}
