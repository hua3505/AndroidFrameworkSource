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

package com.android.server.wifi.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Venue Name ANQP Element, IEEE802.11-2012 section 8.4.4.4.
 *
 * Format:
 *
 * | Info ID | Length | Venue Info | Venue Name Duple #1 (optional) | ...
 *      2        2          2                  variable
 * | Venue Name Duple #N (optional) |
 *             variable
 *
 * Refer to {@link com.android.server.wifi.anqp.I18Name} for the format of the Venue Name Duple
 * fields.
 *
 * Note: The payload parsed by this class already has 'Info ID' and 'Length' stripped off.
 */
public class VenueNameElement extends ANQPElement {
    private final List<I18Name> mNames;

    public VenueNameElement(Constants.ANQPElementType infoID, ByteBuffer payload)
            throws ProtocolException {
        super(infoID);

        if (payload.remaining() < 2) {
            throw new ProtocolException("Venue Name Element cannot contain less than 2 bytes");
        }

        // Skip the Venue Info field, which we don't use.
        for (int i = 0; i < Constants.VENUE_INFO_LENGTH; ++i) {
            payload.get();
        }

        mNames = new ArrayList<I18Name>();
        while (payload.hasRemaining()) {
            mNames.add(new I18Name(payload));
        }
    }

    public List<I18Name> getNames() {
        return Collections.unmodifiableList(mNames);
    }

    @Override
    public String toString() {
        return "VenueName{ mNames=" + mNames + "}";
    }
}
