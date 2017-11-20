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

package com.android.internal.telephony.dataconnection;

import android.net.LinkProperties;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.telephony.DctConstants;
import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.dataconnection.DataConnection.ConnectionParams;
import com.android.internal.telephony.dataconnection.DataConnection.UpdateLinkPropertyResult;
import com.android.internal.util.IState;
import com.android.internal.util.StateMachine;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;

import static com.android.internal.telephony.TelephonyTestUtils.waitForMs;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class DcControllerTest extends TelephonyTest {

    private static final int DATA_CONNECTION_ACTIVE_PH_LINK_DORMANT = 1;
    private static final int EVENT_DATA_STATE_CHANGED = 0x00040007;

    @Mock
    DataConnection mDc;
    @Mock
    HashMap<ApnContext, ConnectionParams> mApnContexts;

    UpdateLinkPropertyResult mResult;

    private DcController mDcc;

    private class DcControllerTestHandler extends HandlerThread {

        private DcControllerTestHandler(String name) {
            super(name);
        }

        private Handler mHandler;

        @Override
        public void onLooperPrepared() {
            mHandler = new Handler();
            mDcc = DcController.makeDcc(mPhone, mDcTracker, mHandler);
            setReady(true);
        }
    }

    private IState getCurrentState() {
        try {
            Method method = StateMachine.class.getDeclaredMethod("getCurrentState");
            method.setAccessible(true);
            return (IState) method.invoke(mDcc);
        } catch (Exception e) {
            fail(e.toString());
            return null;
        }
    }

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());

        doReturn("fake.action_detached").when(mPhone).getActionDetached();
        mDc.mApnContexts = mApnContexts;
        doReturn(1).when(mApnContexts).size();

        LinkProperties lp = new LinkProperties();
        mResult = new UpdateLinkPropertyResult(lp);
        doReturn(mResult).when(mDc).updateLinkProperty(any(DataCallResponse.class));

        new DcControllerTestHandler(TAG).start();
        waitUntilReady();
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    @SmallTest
    public void testDataDormant() {
        assertEquals("DccDefaultState", getCurrentState().getName());
        ArrayList<DataCallResponse> l = new ArrayList<DataCallResponse>();
        DataCallResponse dcResponse = DcTrackerTest.createDataCallResponse();
        dcResponse.active = DATA_CONNECTION_ACTIVE_PH_LINK_DORMANT;
        l.add(dcResponse);

        mDc.mCid = 1;
        mDcc.addActiveDcByCid(mDc);

        mDcc.sendMessage(EVENT_DATA_STATE_CHANGED, new AsyncResult(null, l, null));
        waitForMs(100);

        verify(mDcTracker, times(1)).sendStopNetStatPoll(eq(DctConstants.Activity.DORMANT));
    }
}