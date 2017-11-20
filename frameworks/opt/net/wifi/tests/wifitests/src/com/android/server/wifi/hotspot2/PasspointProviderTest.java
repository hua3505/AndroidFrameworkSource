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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.HomeSP;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Test;

/**
 * Unit tests for {@link com.android.server.wifi.hotspot2.PasspointProvider}.
 */
@SmallTest
public class PasspointProviderTest {
    PasspointProvider mProvider;

    /**
     * Verify that the configuration associated with the provider is the same or not the same
     * as the expected configuration.
     *
     * @param expectedConfig The expected configuration
     * @param equals Flag indicating equality or inequality check
     */
    private void verifyInstalledConfig(PasspointConfiguration expectedConfig, boolean equals) {
        PasspointConfiguration actualConfig = mProvider.getConfig();
        if (equals) {
            assertTrue(actualConfig.equals(expectedConfig));
        } else {
            assertFalse(actualConfig.equals(expectedConfig));
        }
    }

    /**
     * Verify that modification to the configuration used for creating PasspointProvider
     * will not change the configuration stored inside the PasspointProvider.
     *
     * @throws Exception
     */
    @Test
    public void verifyModifyOriginalConfig() throws Exception {
        // Create a dummy PasspointConfiguration.
        PasspointConfiguration config = new PasspointConfiguration();
        config.homeSp = new HomeSP();
        config.homeSp.fqdn = "test1";
        mProvider = new PasspointProvider(config);
        verifyInstalledConfig(config, true);

        // Modify the original configuration, the configuration maintained by the provider
        // should be unchanged.
        config.homeSp.fqdn = "test2";
        verifyInstalledConfig(config, false);
    }

    /**
     * Verify that modification to the configuration retrieved from the PasspointProvider
     * will not change the configuration stored inside the PasspointProvider.
     *
     * @throws Exception
     */
    @Test
    public void verifyModifyRetrievedConfig() throws Exception {
        // Create a dummy PasspointConfiguration.
        PasspointConfiguration config = new PasspointConfiguration();
        config.homeSp = new HomeSP();
        config.homeSp.fqdn = "test1";
        mProvider = new PasspointProvider(config);
        verifyInstalledConfig(config, true);

        // Modify the retrieved configuration, verify the configuration maintained by the
        // provider should be unchanged.
        PasspointConfiguration retrievedConfig = mProvider.getConfig();
        retrievedConfig.homeSp.fqdn = "test2";
        verifyInstalledConfig(retrievedConfig, false);
    }
}
