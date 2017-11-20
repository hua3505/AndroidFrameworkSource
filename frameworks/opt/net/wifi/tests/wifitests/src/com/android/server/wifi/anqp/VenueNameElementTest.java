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

import static org.junit.Assert.*;

import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Test;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Unit tests for {@link com.android.server.wifi.anqp.VenueNameElement}.
 */
@SmallTest
public class VenueNameElementTest {
    private static class VenueNameElementTestMapping {
        byte[] mBytes;
        List<I18Name> mExpectedNames;
        VenueNameElementTestMapping(byte[] bytes, List<I18Name> names) {
            this.mBytes = bytes;
            this.mExpectedNames = names;
        }
    }

    // Raw bytes, laid out in little endian, that represent malformed Venue Name Element payloads
    // that do not conform to IEEE802.11-2012 section 8.4.4.4.
    private static final byte[][] MALFORMED_VENUE_NAME_ELEMENT_BYTES =
            new byte[][] {
                    // Too short.
                    new byte[0],
                    new byte[] {(byte) 0x01},
                    // 1 trailing byte.
                    new byte[] {
                        (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x65,
                        (byte) 0x6e, (byte) 0x00, (byte) 0xab},
                    // Length field (0xff) exceeds remaining payload size.
                    new byte[] {
                        (byte) 0x00, (byte) 0x00, (byte) 0xff, (byte) 0x65,
                        (byte) 0x6e, (byte) 0x00, (byte) 0xab},
            };

    private static final VenueNameElementTestMapping[] VENUE_NAME_ELEMENT_MAPPINGS =
            new VenueNameElementTestMapping[] {
                // 0 Venue name Duples (i.e. Venue info field only).
                new VenueNameElementTestMapping(
                        new byte[] {
                            (byte) 0x00, (byte) 0x00},
                        new ArrayList<I18Name>()),
                // 1 Venue name Duple.
                new VenueNameElementTestMapping(
                        new byte[] {
                            (byte) 0x00, (byte) 0x00, (byte) 0x0d, (byte) 0x65,
                            (byte) 0x6e, (byte) 0x00, (byte) 0x74, (byte) 0x65,
                            (byte) 0x73, (byte) 0x74, (byte) 0x56, (byte) 0x65,
                            (byte) 0x6e, (byte) 0x75, (byte) 0x65, (byte) 0x31},
                        new ArrayList<I18Name>() {{
                            add(new I18Name("en", Locale.ENGLISH, "testVenue1"));
                        }}),
                // 2 Venue Name Duples.
                new VenueNameElementTestMapping(
                        new byte[] {
                            (byte) 0x00, (byte) 0x00, (byte) 0x0d, (byte) 0x65,
                            (byte) 0x6e, (byte) 0x00, (byte) 0x74, (byte) 0x65,
                            (byte) 0x73, (byte) 0x74, (byte) 0x56, (byte) 0x65,
                            (byte) 0x6e, (byte) 0x75, (byte) 0x65, (byte) 0x31,
                            (byte) 0x0d, (byte) 0x66, (byte) 0x72, (byte) 0x00,
                            (byte) 0x74, (byte) 0x65, (byte) 0x73, (byte) 0x74,
                            (byte) 0x56, (byte) 0x65, (byte) 0x6e, (byte) 0x75,
                            (byte) 0x65, (byte) 0x32},
                        new ArrayList<I18Name>() {{
                            add(new I18Name("en", Locale.ENGLISH, "testVenue1"));
                            add(new I18Name("fr", Locale.FRENCH, "testVenue2"));
                        }}),
            };

    /**
     * Verifies that parsing malformed Venue Name Element bytes results in a ProtocolException.
     */
    @Test
    public void testMalformedVenueNameElementBytes() {
        for (byte[] invalidBytes : MALFORMED_VENUE_NAME_ELEMENT_BYTES) {
            try {
                VenueNameElement venueNameElement = new VenueNameElement(
                        Constants.ANQPElementType.ANQPVenueName,
                        ByteBuffer.wrap(invalidBytes).order(ByteOrder.LITTLE_ENDIAN));
            } catch (ProtocolException e) {
                continue;
            }
            fail("Expected exception while parsing malformed Venue Name Element bytes: "
                    + invalidBytes);
        }
    }

    /**
     * Verifies that valid Venue Name Element bytes are properly parsed.
     */
    @Test
    public void testVenueNameElementParsing() {
        try {
            for (VenueNameElementTestMapping testMapping : VENUE_NAME_ELEMENT_MAPPINGS) {
                VenueNameElement venueNameElement =
                        new VenueNameElement(
                                Constants.ANQPElementType.ANQPVenueName,
                                ByteBuffer.wrap(testMapping.mBytes).order(ByteOrder.LITTLE_ENDIAN));
                assertEquals(testMapping.mExpectedNames, venueNameElement.getNames());
            }
        } catch (ProtocolException e) {
            fail("Exception encountered during parsing: " + e);
        }
    }
}
