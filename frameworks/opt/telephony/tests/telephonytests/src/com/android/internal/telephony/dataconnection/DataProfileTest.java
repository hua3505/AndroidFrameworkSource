/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.telephony.dataconnection;

import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

public class DataProfileTest extends TestCase {

    private ApnSetting mApn1 = new ApnSetting(
            2163,                   // id
            "44010",                // numeric
            "sp-mode",              // name
            "fake_apn",             // apn
            "",                     // proxy
            "",                     // port
            "",                     // mmsc
            "",                     // mmsproxy
            "",                     // mmsport
            "user",                 // user
            "passwd",               // password
            -1,                     // authtype
            new String[]{"default", "supl"},     // types
            "IPV6",                 // protocol
            "IP",                   // roaming_protocol
            true,                   // carrier_enabled
            0,                      // bearer
            0,                      // bearer_bitmask
            1234,                   // profile_id
            false,                  // modem_cognitive
            321,                    // max_conns
            456,                    // wait_time
            789,                    // max_conns_time
            0,                      // mtu
            "",                     // mvno_type
            "");                    // mnvo_match_data

    private ApnSetting mApn2 = new ApnSetting(
            2163,                   // id
            "44010",                // numeric
            "sp-mode",              // name
            "fake_apn",             // apn
            "",                     // proxy
            "",                     // port
            "",                     // mmsc
            "",                     // mmsproxy
            "",                     // mmsport
            "user",                 // user
            "passwd",               // password
            -1,                     // authtype
            new String[]{"default", "supl"},     // types
            "IP",                   // protocol
            "IP",                   // roaming_protocol
            true,                   // carrier_enabled
            0,                      // bearer
            0,                      // bearer_bitmask
            1234,                   // profile_id
            false,                  // modem_cognitive
            111,                    // max_conns
            456,                    // wait_time
            789,                    // max_conns_time
            0,                      // mtu
            "",                     // mvno_type
            "");                    // mnvo_match_data

    @SmallTest
    public void testCreateFromApnSetting() throws Exception {
        DataProfile dp = new DataProfile(mApn1, false);
        assertEquals(mApn1.profileId, dp.profileId);
        assertEquals(mApn1.apn, dp.apn);
        assertEquals(mApn1.protocol, dp.protocol);
        assertEquals(mApn1.authType, dp.authType);
        assertEquals(mApn1.user, dp.user);
        assertEquals(mApn1.password, dp.password);
        assertEquals(0, dp.type);
        assertEquals(mApn1.maxConnsTime, dp.maxConnsTime);
        assertEquals(mApn1.maxConns, dp.maxConns);
        assertEquals(mApn1.waitTime, dp.waitTime);
        assertEquals(mApn1.carrierEnabled, dp.enabled);
    }

    @SmallTest
    public void testParcel() throws Exception {
        Parcel p = Parcel.obtain();

        DataProfile[] dps = new DataProfile[]{new DataProfile(mApn1, false),
                new DataProfile(mApn1, false)};

        DataProfile.toParcel(p, dps);
        p.setDataPosition(0);

        assertEquals(dps.length, p.readInt());
        for (int i = 0; i < dps.length; i++) {
            assertEquals("i = " + i, mApn1.profileId, p.readInt());
            assertEquals("i = " + i, mApn1.apn, p.readString());
            assertEquals("i = " + i, mApn1.protocol, p.readString());
            assertEquals("i = " + i, mApn1.authType, p.readInt());
            assertEquals("i = " + i, mApn1.user, p.readString());
            assertEquals("i = " + i, mApn1.password, p.readString());
            assertEquals("i = " + i, 0, p.readInt());
            assertEquals("i = " + i, mApn1.maxConnsTime, p.readInt());
            assertEquals("i = " + i, mApn1.maxConns, p.readInt());
            assertEquals("i = " + i, mApn1.waitTime, p.readInt());
            assertEquals("i = " + i, mApn1.carrierEnabled?1:0, p.readInt());
        }
    }

    @SmallTest
    public void testEquals() throws Exception {
        DataProfile dp1 = new DataProfile(mApn1, false);
        DataProfile dp2 = new DataProfile(mApn1, false);
        assertEquals(dp1, dp2);

        dp2 = new DataProfile(mApn1, true);
        assertFalse(dp1.equals(dp2));

        dp2 = new DataProfile(mApn2, false);
        assertFalse(dp1.equals(dp2));
    }
}
