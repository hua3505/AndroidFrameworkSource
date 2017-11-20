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

import android.net.wifi.hotspot2.PasspointConfiguration;

/**
 * Abstraction for Passpoint service provider.  This class contains the both static
 * Passpoint configuration data and the runtime data (e.g. blacklisted SSIDs, statistics).
 */
public class PasspointProvider {
    private final PasspointConfiguration mConfig;

    public PasspointProvider(PasspointConfiguration config) {
        // Maintain a copy of the configuration to avoid it being updated by others.
        mConfig = new PasspointConfiguration(config);
    }

    public PasspointConfiguration getConfig() {
        // Return a copy of the configuration to avoid it being updated by others.
        return new PasspointConfiguration(mConfig);
    }
}
