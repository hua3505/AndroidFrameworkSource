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

package com.android.internal.telephony.cdma;

import android.os.HandlerThread;
import android.os.Message;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.telephony.ImsSMSDispatcher;
import com.android.internal.telephony.SMSDispatcher;
import com.android.internal.telephony.TelephonyTest;

import static org.mockito.Mockito.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

public class CdmaSmsDispatcherTest extends TelephonyTest {
    @Mock
    private android.telephony.SmsMessage mSmsMessage;
    @Mock
    private SmsMessage mCdmaSmsMessage;
    @Mock
    private ImsSMSDispatcher mImsSmsDispatcher;
    @Mock
    private SMSDispatcher.SmsTracker mSmsTracker;

    private CdmaSMSDispatcher mCdmaSmsDispatcher;

    private class CdmaSmsDispatcherTestHandler extends HandlerThread {

        private CdmaSmsDispatcherTestHandler(String name) {
            super(name);
        }

        @Override
        public void onLooperPrepared() {
            mCdmaSmsDispatcher = new CdmaSMSDispatcher(mPhone, mSmsUsageMonitor,
                    mImsSmsDispatcher);
            setReady(true);
        }
    }

    @Before
    public void setUp() throws Exception {
        super.setUp(this.getClass().getSimpleName());

        setupMockPackagePermissionChecks();

        new CdmaSmsDispatcherTestHandler(TAG).start();
        waitUntilReady();
    }

    @After
    public void tearDown() throws Exception {
        mCdmaSmsDispatcher = null;
        super.tearDown();
    }

    @Test @SmallTest
    public void testSendSms() {
        doReturn(mServiceState).when(mPhone).getServiceState();
        mCdmaSmsDispatcher.sendSms(mSmsTracker);
        verify(mSimulatedCommandsVerifier).sendCdmaSms(any(byte[].class), any(Message.class));
    }

    @Test @SmallTest
    public void testSendText() {
        mCdmaSmsDispatcher.sendText("111"/* desAddr*/, "222" /*scAddr*/, TAG,
                null, null, null, null, false);
        verify(mSimulatedCommandsVerifier).sendCdmaSms(any(byte[].class), any(Message.class));
    }

    @Test @SmallTest
    public void testSendTextWithOutDesAddr() {
        mCdmaSmsDispatcher.sendText(null, "222" /*scAddr*/, TAG,
                null, null, null, null, false);
        verify(mSimulatedCommandsVerifier, times(0)).sendImsGsmSms(anyString(), anyString(),
                anyInt(), anyInt(), any(Message.class));
    }
}
