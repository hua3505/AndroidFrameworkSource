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

import static com.android.server.wifi.anqp.Constants.BYTE_MASK;

import com.android.internal.annotations.VisibleForTesting;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A generic Internationalized name field used in the Operator Friendly Name ANQP element
 * (see HS2.0 R2 Spec 4.2), and the Venue Name ANQP element (see 802.11-2012 8.4.4.4).
 *
 * Format:
 *
 * | Length | Language Code |   Name   |
 *      1           3         variable
 */
public class I18Name {
    private final String mLanguage;
    private final Locale mLocale;
    private final String mText;

    public I18Name(ByteBuffer payload) throws ProtocolException {
        if (payload.remaining() < Constants.LANG_CODE_LENGTH + 1) {
            throw new ProtocolException("Truncated I18Name payload of length "
                    + payload.remaining());
        }
        int length = payload.get() & BYTE_MASK;
        if (length < Constants.LANG_CODE_LENGTH || length > payload.remaining()) {
            throw new ProtocolException("Invalid I18Name length field value " + length);
        }
        mLanguage = Constants.getTrimmedString(payload,
                Constants.LANG_CODE_LENGTH, StandardCharsets.US_ASCII);
        mLocale = Locale.forLanguageTag(mLanguage);
        mText = Constants.getString(payload, length
                - Constants.LANG_CODE_LENGTH, StandardCharsets.UTF_8);
    }

    @VisibleForTesting
    public I18Name(String language, Locale locale, String text) {
        mLanguage = language;
        mLocale = locale;
        mText = text;
    }

    public String getLanguage() {
        return mLanguage;
    }

    public Locale getLocale() {
        return mLocale;
    }

    public String getText() {
        return mText;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (thatObject == null || getClass() != thatObject.getClass()) {
            return false;
        }

        I18Name that = (I18Name) thatObject;
        return mLanguage.equals(that.mLanguage) && mText.equals(that.mText);
    }

    @Override
    public int hashCode() {
        int result = mLanguage.hashCode();
        result = 31 * result + mText.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return mText + ':' + mLocale.getLanguage();
    }
}
