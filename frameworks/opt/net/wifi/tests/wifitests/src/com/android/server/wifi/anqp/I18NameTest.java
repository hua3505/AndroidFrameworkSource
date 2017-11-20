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
import java.util.Locale;

/**
 * Unit tests for {@link com.android.server.wifi.anqp.I18Name}.
 */
@SmallTest
public class I18NameTest {
    private static class I18NameTestMapping {
        byte[] mBytes;
        String mExpectedLanguage;
        Locale mExpectedLocale;
        String mExpectedText;
        I18NameTestMapping(byte[] bytes, String language, Locale locale, String text) {
            this.mBytes = bytes;
            this.mExpectedLanguage = language;
            this.mExpectedLocale = locale;
            this.mExpectedText = text;
        }
    }

    private static final byte[][] MALFORMED_I18_NAME_BYTES =
            new byte[][] {
                    // Too short.
                    new byte[0],
                    new byte[] {(byte) 0x01},
                    new byte[] {(byte) 0x01, (byte) 0x02},
                    // Length value of 0x02 shorter than the length of the language code field
                    // (i.e. 3).
                    new byte[] {
                        (byte) 0x02, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                        (byte) 0xb0, (byte) 0xb1},
                    // Length value of 0xff longer than payload size.
                    new byte[] {
                        (byte) 0xff, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                        (byte) 0xb0, (byte) 0xb1}
            };

    private static final I18NameTestMapping[] I18_NAME_MAPPINGS =
            new I18NameTestMapping[] {
                new I18NameTestMapping(
                        new byte[] {
                            (byte) 0x09, (byte) 0x65, (byte) 0x6e, (byte) 0x00,
                            (byte) 0x74, (byte) 0x65, (byte) 0x73, (byte) 0x74,
                            (byte) 0x41, (byte) 0x70},
                        "en", Locale.ENGLISH, "testAp"),
                // TODO: Make sure the ISO-639 alpha-3 code is properly parsed (b/30311144).
                /*
                new I18NameTestMapping(
                        new byte[] {
                            (byte) 0x09, (byte) 0x65, (byte) 0x6e, (byte) 0x67,
                            (byte) 0x74, (byte) 0x65, (byte) 0x73, (byte) 0x74,
                            (byte) 0x41, (byte) 0x70},
                        "eng", Locale.ENGLISH, "testAp"),
                */
                new I18NameTestMapping(
                        new byte[] {
                            (byte) 0x0b, (byte) 0x66, (byte) 0x72, (byte) 0x00,
                            (byte) 0x62, (byte) 0x6c, (byte) 0x61, (byte) 0x68,
                            (byte) 0x62, (byte) 0x6c, (byte) 0x61, (byte) 0x68},
                        "fr", Locale.FRENCH, "blahblah")
            };

    /**
     * Verifies that parsing malformed I18Name bytes results in a ProtocolException.
     */
    @Test
    public void testMalformedI18NameBytes() {
        for (byte[] malformedBytes : MALFORMED_I18_NAME_BYTES) {
            try {
                I18Name i18Name = new I18Name(ByteBuffer.wrap(
                        malformedBytes).order(ByteOrder.LITTLE_ENDIAN));
            } catch (ProtocolException e) {
                continue;
            }
            fail("Expected exception while parsing malformed I18 Name bytes: " + malformedBytes);
        }
    }

    /**
     * Verifies that a sampling of valid I18Name bytes are properly parsed.
     */
    @Test
    public void testI18NameParsing() {

        for (I18NameTestMapping testMapping : I18_NAME_MAPPINGS) {
            try {

                I18Name i18Name = new I18Name(ByteBuffer.wrap(
                        testMapping.mBytes).order(ByteOrder.LITTLE_ENDIAN));
                assertEquals(testMapping.mExpectedLanguage, i18Name.getLanguage());
                assertEquals(testMapping.mExpectedLocale, i18Name.getLocale());
                assertEquals(testMapping.mExpectedText, i18Name.getText());
            } catch (ProtocolException e) {
                fail("Exception encountered during parsing: " + e);
            }
        }
    }
}
