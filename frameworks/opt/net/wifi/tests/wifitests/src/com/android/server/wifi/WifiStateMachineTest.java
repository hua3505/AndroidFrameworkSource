/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

import android.app.ActivityManager;
import android.app.test.MockAnswerUtil.AnswerWithArguments;
import android.app.test.TestAlarmManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.DhcpResults;
import android.net.LinkProperties;
import android.net.dhcp.DhcpClient;
import android.net.ip.IpManager;
import android.net.wifi.IClientInterface;
import android.net.wifi.IWificond;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiSsid;
import android.net.wifi.p2p.IWifiP2pManager;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IInterface;
import android.os.INetworkManagementService;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.security.KeyStore;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.IState;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.p2p.WifiP2pServiceImpl;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Unit tests for {@link com.android.server.wifi.WifiStateMachine}.
 */
@SmallTest
public class WifiStateMachineTest {
    public static final String TAG = "WifiStateMachineTest";

    private static final int MANAGED_PROFILE_UID = 1100000;
    private static final int OTHER_USER_UID = 1200000;
    private static final int LOG_REC_LIMIT_IN_VERBOSE_MODE =
            (ActivityManager.isLowRamDeviceStatic()
                    ? WifiStateMachine.NUM_LOG_RECS_VERBOSE_LOW_MEMORY
                    : WifiStateMachine.NUM_LOG_RECS_VERBOSE);
    private static final String DEFAULT_TEST_SSID = "\"GoogleGuest\"";

    private long mBinderToken;

    private static <T> T mockWithInterfaces(Class<T> class1, Class<?>... interfaces) {
        return mock(class1, withSettings().extraInterfaces(interfaces));
    }

    private static <T, I> IBinder mockService(Class<T> class1, Class<I> iface) {
        T tImpl = mockWithInterfaces(class1, iface);
        IBinder binder = mock(IBinder.class);
        when(((IInterface) tImpl).asBinder()).thenReturn(binder);
        when(binder.queryLocalInterface(iface.getCanonicalName()))
                .thenReturn((IInterface) tImpl);
        return binder;
    }

    private void enableDebugLogs() {
        mWsm.enableVerboseLogging(1);
    }

    private class TestIpManager extends IpManager {
        TestIpManager(Context context, String ifname, IpManager.Callback callback) {
            // Call dependency-injection superclass constructor.
            super(context, ifname, callback, mock(INetworkManagementService.class));
        }

        @Override
        public void startProvisioning(IpManager.ProvisioningConfiguration config) {}

        @Override
        public void stop() {}

        @Override
        public void confirmConfiguration() {}

        void injectDhcpSuccess(DhcpResults dhcpResults) {
            mCallback.onNewDhcpResults(dhcpResults);
            mCallback.onProvisioningSuccess(new LinkProperties());
        }

        void injectDhcpFailure() {
            mCallback.onNewDhcpResults(null);
            mCallback.onProvisioningFailure(new LinkProperties());
        }
    }

    private FrameworkFacade getFrameworkFacade() throws Exception {
        FrameworkFacade facade = mock(FrameworkFacade.class);

        when(facade.getService(Context.NETWORKMANAGEMENT_SERVICE)).thenReturn(
                mockWithInterfaces(IBinder.class, INetworkManagementService.class));

        IBinder p2pBinder = mockService(WifiP2pServiceImpl.class, IWifiP2pManager.class);
        when(facade.getService(Context.WIFI_P2P_SERVICE)).thenReturn(p2pBinder);

        WifiP2pServiceImpl p2pm = (WifiP2pServiceImpl) p2pBinder.queryLocalInterface(
                IWifiP2pManager.class.getCanonicalName());

        final CountDownLatch untilDone = new CountDownLatch(1);
        mP2pThread = new HandlerThread("WifiP2pMockThread") {
            @Override
            protected void onLooperPrepared() {
                untilDone.countDown();
            }
        };

        mP2pThread.start();
        untilDone.await();

        Handler handler = new Handler(mP2pThread.getLooper());
        when(p2pm.getP2pStateMachineMessenger()).thenReturn(new Messenger(handler));

        IBinder batteryStatsBinder = mockService(BatteryStats.class, IBatteryStats.class);
        when(facade.getService(BatteryStats.SERVICE_NAME)).thenReturn(batteryStatsBinder);

        when(facade.makeIpManager(any(Context.class), anyString(), any(IpManager.Callback.class)))
                .then(new AnswerWithArguments() {
                    public IpManager answer(
                            Context context, String ifname, IpManager.Callback callback) {
                        mTestIpManager = new TestIpManager(context, ifname, callback);
                        return mTestIpManager;
                    }
                });

        when(facade.checkUidPermission(eq(android.Manifest.permission.OVERRIDE_WIFI_CONFIG),
                anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        return facade;
    }

    private Context getContext() throws Exception {
        PackageManager pkgMgr = mock(PackageManager.class);
        when(pkgMgr.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)).thenReturn(true);

        Context context = mock(Context.class);
        when(context.getPackageManager()).thenReturn(pkgMgr);
        when(context.getContentResolver()).thenReturn(mock(ContentResolver.class));

        MockResources resources = new com.android.server.wifi.MockResources();
        when(context.getResources()).thenReturn(resources);

        ContentResolver cr = mock(ContentResolver.class);
        when(context.getContentResolver()).thenReturn(cr);

        when(context.getSystemService(Context.POWER_SERVICE)).thenReturn(
                new PowerManager(context, mock(IPowerManager.class), new Handler()));

        mAlarmManager = new TestAlarmManager();
        when(context.getSystemService(Context.ALARM_SERVICE)).thenReturn(
                mAlarmManager.getAlarmManager());

        when(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(
                mock(ConnectivityManager.class));

        return context;
    }

    private Resources getMockResources() {
        MockResources resources = new MockResources();
        resources.setBoolean(R.bool.config_wifi_enable_wifi_firmware_debugging, false);
        return resources;
    }

    private IState getCurrentState() throws
            NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = StateMachine.class.getDeclaredMethod("getCurrentState");
        method.setAccessible(true);
        return (IState) method.invoke(mWsm);
    }

    private static HandlerThread getWsmHandlerThread(WifiStateMachine wsm) throws
            NoSuchFieldException, InvocationTargetException, IllegalAccessException {
        Field field = StateMachine.class.getDeclaredField("mSmThread");
        field.setAccessible(true);
        return (HandlerThread) field.get(wsm);
    }

    private static void stopLooper(final Looper looper) throws Exception {
        new Handler(looper).post(new Runnable() {
            @Override
            public void run() {
                looper.quitSafely();
            }
        });
    }

    private void dumpState() {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(stream);
        mWsm.dump(null, writer, null);
        writer.flush();
        Log.d(TAG, "WifiStateMachine state -" + stream.toString());
    }

    private static ScanDetail getGoogleGuestScanDetail(int rssi) {
        ScanResult.InformationElement ie[] = new ScanResult.InformationElement[1];
        ie[0] = ScanResults.generateSsidIe(sSSID);
        NetworkDetail nd = new NetworkDetail(sBSSID, ie, new ArrayList<String>(), sFreq);
        ScanDetail detail = new ScanDetail(nd, sWifiSsid, sBSSID, "", rssi, sFreq,
                Long.MAX_VALUE, /* needed so that scan results aren't rejected because
                                   there older than scan start */
                ie, new ArrayList<String>());
        return detail;
    }

    private ArrayList<ScanDetail> getMockScanResults() {
        ScanResults sr = ScanResults.create(0, 2412, 2437, 2462, 5180, 5220, 5745, 5825);
        ArrayList<ScanDetail> list = sr.getScanDetailArrayList();

        int rssi = -65;
        list.add(getGoogleGuestScanDetail(rssi));
        return list;
    }

    static final String   sSSID = "\"GoogleGuest\"";
    static final WifiSsid sWifiSsid = WifiSsid.createFromAsciiEncoded(sSSID);
    static final String   sHexSSID = sWifiSsid.getHexString().replace("0x", "").replace("22", "");
    static final String   sBSSID = "01:02:03:04:05:06";
    static final int      sFreq = 2437;

    WifiStateMachine mWsm;
    HandlerThread mWsmThread;
    HandlerThread mP2pThread;
    HandlerThread mSyncThread;
    AsyncChannel  mWsmAsyncChannel;
    TestAlarmManager mAlarmManager;
    MockWifiMonitor mWifiMonitor;
    TestIpManager mTestIpManager;
    TestLooper mLooper;

    @Mock WifiNative mWifiNative;
    @Mock WifiScanner mWifiScanner;
    @Mock SupplicantStateTracker mSupplicantStateTracker;
    @Mock WifiMetrics mWifiMetrics;
    @Mock UserManager mUserManager;
    @Mock WifiApConfigStore mApConfigStore;
    @Mock BackupManagerProxy mBackupManagerProxy;
    @Mock WifiCountryCode mCountryCode;
    @Mock WifiInjector mWifiInjector;
    @Mock WifiLastResortWatchdog mWifiLastResortWatchdog;
    @Mock PropertyService mPropertyService;
    @Mock BuildProperties mBuildProperties;
    @Mock IWificond mWificond;
    @Mock IClientInterface mClientInterface;
    @Mock IBinder mClientInterfaceBinder;
    @Mock WifiConfigManager mWifiConfigManager;
    @Mock WifiSupplicantControl mWifiSupplicantControl;

    public WifiStateMachineTest() throws Exception {
    }

    @Before
    public void setUp() throws Exception {
        Log.d(TAG, "Setting up ...");

        // Ensure looper exists
        mLooper = new TestLooper();

        MockitoAnnotations.initMocks(this);

        /** uncomment this to enable logs from WifiStateMachines */
        // enableDebugLogs();

        mWifiMonitor = new MockWifiMonitor();
        when(mWifiInjector.getWifiMetrics()).thenReturn(mWifiMetrics);
        when(mWifiInjector.getClock()).thenReturn(mock(Clock.class));
        when(mWifiInjector.getWifiLastResortWatchdog()).thenReturn(mWifiLastResortWatchdog);
        when(mWifiInjector.getPropertyService()).thenReturn(mPropertyService);
        when(mWifiInjector.getBuildProperties()).thenReturn(mBuildProperties);
        when(mWifiInjector.getKeyStore()).thenReturn(mock(KeyStore.class));
        when(mWifiInjector.getWifiBackupRestore()).thenReturn(mock(WifiBackupRestore.class));
        when(mWifiInjector.makeWifiDiagnostics(anyObject())).thenReturn(
                mock(BaseWifiDiagnostics.class));
        when(mWifiInjector.makeWificond()).thenReturn(mWificond);
        when(mWifiInjector.getWifiConfigManager()).thenReturn(mWifiConfigManager);
        when(mWifiInjector.getWifiSupplicantControl()).thenReturn(mWifiSupplicantControl);
        when(mWifiInjector.getWifiScanner()).thenReturn(mWifiScanner);
        when(mWifiInjector.getWifiNetworkSelector()).thenReturn(mock(WifiNetworkSelector.class));

        when(mWifiNative.getInterfaceName()).thenReturn("mockWlan");
        when(mWifiSupplicantControl.getFrameworkNetworkId(anyInt())).thenReturn(0);


        FrameworkFacade factory = getFrameworkFacade();
        Context context = getContext();

        Resources resources = getMockResources();
        when(context.getResources()).thenReturn(resources);

        when(factory.getIntegerSetting(context,
                Settings.Global.WIFI_FREQUENCY_BAND,
                WifiManager.WIFI_FREQUENCY_BAND_AUTO)).thenReturn(
                WifiManager.WIFI_FREQUENCY_BAND_AUTO);

        when(factory.makeApConfigStore(eq(context), eq(mBackupManagerProxy)))
                .thenReturn(mApConfigStore);

        when(factory.makeSupplicantStateTracker(
                any(Context.class), any(WifiConfigManager.class),
                any(Handler.class))).thenReturn(mSupplicantStateTracker);

        when(mUserManager.getProfileParent(11))
                .thenReturn(new UserInfo(UserHandle.USER_SYSTEM, "owner", 0));
        when(mUserManager.getProfiles(UserHandle.USER_SYSTEM)).thenReturn(Arrays.asList(
                new UserInfo(UserHandle.USER_SYSTEM, "owner", 0),
                new UserInfo(11, "managed profile", 0)));

        when(mWificond.createClientInterface()).thenReturn(mClientInterface);
        when(mClientInterface.asBinder()).thenReturn(mClientInterfaceBinder);
        when(mClientInterface.enableSupplicant()).thenReturn(true);
        when(mClientInterface.disableSupplicant()).thenReturn(true);

        mWsm = new WifiStateMachine(context, factory, mLooper.getLooper(),
            mUserManager, mWifiInjector, mBackupManagerProxy, mCountryCode, mWifiNative);
        mWsmThread = getWsmHandlerThread(mWsm);

        final AsyncChannel channel = new AsyncChannel();
        Handler handler = new Handler(mLooper.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                        if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                            mWsmAsyncChannel = channel;
                        } else {
                            Log.d(TAG, "Failed to connect Command channel " + this);
                        }
                        break;
                    case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                        Log.d(TAG, "Command channel disconnected" + this);
                        break;
                }
            }
        };

        channel.connect(context, handler, mWsm.getMessenger());
        mLooper.dispatchAll();
        /* Now channel is supposed to be connected */

        mBinderToken = Binder.clearCallingIdentity();
    }

    @After
    public void cleanUp() throws Exception {
        Binder.restoreCallingIdentity(mBinderToken);

        if (mSyncThread != null) stopLooper(mSyncThread.getLooper());
        if (mWsmThread != null) stopLooper(mWsmThread.getLooper());
        if (mP2pThread != null) stopLooper(mP2pThread.getLooper());

        mWsmThread = null;
        mP2pThread = null;
        mSyncThread = null;
        mWsmAsyncChannel = null;
        mWsm = null;
    }

    @Test
    public void createNew() throws Exception {
        assertEquals("InitialState", getCurrentState().getName());

        mWsm.sendMessage(WifiStateMachine.CMD_BOOT_COMPLETED);
        mLooper.dispatchAll();
        assertEquals("InitialState", getCurrentState().getName());
    }

    @Test
    public void loadComponents() throws Exception {
        when(mWifiNative.startHal()).thenReturn(true);
        mWsm.setSupplicantRunning(true);
        mLooper.dispatchAll();

        assertEquals("SupplicantStartingState", getCurrentState().getName());

        when(mWifiNative.setDeviceName(anyString())).thenReturn(true);
        when(mWifiNative.setManufacturer(anyString())).thenReturn(true);
        when(mWifiNative.setModelName(anyString())).thenReturn(true);
        when(mWifiNative.setModelNumber(anyString())).thenReturn(true);
        when(mWifiNative.setSerialNumber(anyString())).thenReturn(true);
        when(mWifiNative.setConfigMethods(anyString())).thenReturn(true);
        when(mWifiNative.setDeviceType(anyString())).thenReturn(true);
        when(mWifiNative.setSerialNumber(anyString())).thenReturn(true);
        when(mWifiNative.setScanningMacOui(any(byte[].class))).thenReturn(true);

        mWsm.sendMessage(WifiMonitor.SUP_CONNECTION_EVENT);
        mLooper.dispatchAll();

        assertEquals("DisconnectedState", getCurrentState().getName());
    }

    @Test
    public void shouldRequireSupplicantStartupToLeaveInitialState() throws Exception {
        when(mClientInterface.enableSupplicant()).thenReturn(false);
        mWsm.setSupplicantRunning(true);
        mLooper.dispatchAll();
        assertEquals("InitialState", getCurrentState().getName());
    }

    @Test
    public void shouldRequireWificondToLeaveInitialState() throws Exception {
        // We start out with valid default values, break them going backwards so that
        // we test all the bailout cases.

        // ClientInterface dies after creation.
        doThrow(new RemoteException()).when(mClientInterfaceBinder).linkToDeath(any(), anyInt());
        mWsm.setSupplicantRunning(true);
        mLooper.dispatchAll();
        assertEquals("InitialState", getCurrentState().getName());

        // Failed to even create the client interface.
        when(mWificond.createClientInterface()).thenReturn(null);
        mWsm.setSupplicantRunning(true);
        mLooper.dispatchAll();
        assertEquals("InitialState", getCurrentState().getName());

        // Failed to get wificond proxy.
        when(mWifiInjector.makeWificond()).thenReturn(null);
        mWsm.setSupplicantRunning(true);
        mLooper.dispatchAll();
        assertEquals("InitialState", getCurrentState().getName());
    }

    @Test
    public void loadComponentsFailure() throws Exception {
        when(mWifiNative.startHal()).thenReturn(false);
        when(mClientInterface.enableSupplicant()).thenReturn(false);

        mWsm.setSupplicantRunning(true);
        mLooper.dispatchAll();
        assertEquals("InitialState", getCurrentState().getName());

        when(mWifiNative.startHal()).thenReturn(true);
        mWsm.setSupplicantRunning(true);
        mLooper.dispatchAll();
        assertEquals("InitialState", getCurrentState().getName());
    }

    @Test
    public void checkInitialStateStickyWhenDisabledMode() throws Exception {
        mLooper.dispatchAll();
        assertEquals("InitialState", getCurrentState().getName());
        assertEquals(WifiStateMachine.CONNECT_MODE, mWsm.getOperationalModeForTest());

        mWsm.setOperationalMode(WifiStateMachine.DISABLED_MODE);
        mLooper.dispatchAll();
        assertEquals(WifiStateMachine.DISABLED_MODE, mWsm.getOperationalModeForTest());
        assertEquals("InitialState", getCurrentState().getName());
    }

    @Test
    public void shouldStartSupplicantWhenConnectModeRequested() throws Exception {
        when(mWifiNative.startHal()).thenReturn(true);

        // The first time we start out in InitialState, we sit around here.
        mLooper.dispatchAll();
        assertEquals("InitialState", getCurrentState().getName());
        assertEquals(WifiStateMachine.CONNECT_MODE, mWsm.getOperationalModeForTest());

        // But if someone tells us to enter connect mode, we start up supplicant
        mWsm.setOperationalMode(WifiStateMachine.CONNECT_MODE);
        mLooper.dispatchAll();
        assertEquals("SupplicantStartingState", getCurrentState().getName());
    }

    /**
     * Test that mode changes for WifiStateMachine in the InitialState are realized when supplicant
     * is started.
     */
    @Test
    public void checkStartInCorrectStateAfterChangingInitialState() throws Exception {
        when(mWifiNative.startHal()).thenReturn(true);

        // Check initial state
        mLooper.dispatchAll();
        assertEquals("InitialState", getCurrentState().getName());
        assertEquals(WifiStateMachine.CONNECT_MODE, mWsm.getOperationalModeForTest());

        // Update the mode
        mWsm.setOperationalMode(WifiStateMachine.SCAN_ONLY_MODE);
        mLooper.dispatchAll();
        assertEquals(WifiStateMachine.SCAN_ONLY_MODE, mWsm.getOperationalModeForTest());

        // Start supplicant so we move to the next state
        mWsm.setSupplicantRunning(true);
        mLooper.dispatchAll();
        assertEquals("SupplicantStartingState", getCurrentState().getName());
        when(mWifiNative.setDeviceName(anyString())).thenReturn(true);
        when(mWifiNative.setManufacturer(anyString())).thenReturn(true);
        when(mWifiNative.setModelName(anyString())).thenReturn(true);
        when(mWifiNative.setModelNumber(anyString())).thenReturn(true);
        when(mWifiNative.setSerialNumber(anyString())).thenReturn(true);
        when(mWifiNative.setConfigMethods(anyString())).thenReturn(true);
        when(mWifiNative.setDeviceType(anyString())).thenReturn(true);
        when(mWifiNative.setSerialNumber(anyString())).thenReturn(true);
        when(mWifiNative.setScanningMacOui(any(byte[].class))).thenReturn(true);

        mWsm.sendMessage(WifiMonitor.SUP_CONNECTION_EVENT);
        mLooper.dispatchAll();

        assertEquals("ScanModeState", getCurrentState().getName());
    }

    private void addNetworkAndVerifySuccess() throws Exception {
        addNetworkAndVerifySuccess(false);
    }

    private void addNetworkAndVerifySuccess(boolean isHidden) throws Exception {
        loadComponents();

        WifiConfiguration config = new WifiConfiguration();
        config.SSID = sSSID;
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        config.hiddenSSID = isHidden;

        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt()))
                .thenReturn(new NetworkUpdateResult(0));
        when(mWifiConfigManager.getSavedNetworks()).thenReturn(Arrays.asList(config));
        when(mWifiConfigManager.getConfiguredNetwork(0)).thenReturn(config);

        mLooper.startAutoDispatch();
        mWsm.syncAddOrUpdateNetwork(mWsmAsyncChannel, config);
        mLooper.stopAutoDispatch();

        verify(mWifiConfigManager).addOrUpdateNetwork(eq(config), anyInt());

        mLooper.startAutoDispatch();
        List<WifiConfiguration> configs = mWsm.syncGetConfiguredNetworks(-1, mWsmAsyncChannel);
        mLooper.stopAutoDispatch();
        assertEquals(1, configs.size());

        WifiConfiguration config2 = configs.get(0);
        assertEquals("\"GoogleGuest\"", config2.SSID);
        assertTrue(config2.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE));
    }

    /**
     * Helper method to retrieve WifiConfiguration by SSID.
     *
     * Returns the associated WifiConfiguration if it is found, null otherwise.
     */
    private WifiConfiguration getWifiConfigurationForNetwork(String ssid) {
        mLooper.startAutoDispatch();
        List<WifiConfiguration> configs = mWsm.syncGetConfiguredNetworks(-1, mWsmAsyncChannel);
        mLooper.stopAutoDispatch();

        for (WifiConfiguration checkConfig : configs) {
            if (checkConfig.SSID.equals(ssid)) {
                return checkConfig;
            }
        }
        return null;
    }

    private void verifyScan(int band, int reportEvents, Set<String> hiddenNetworkSSIDSet) {
        ArgumentCaptor<WifiScanner.ScanSettings> scanSettingsCaptor =
                ArgumentCaptor.forClass(WifiScanner.ScanSettings.class);
        ArgumentCaptor<WifiScanner.ScanListener> scanListenerCaptor =
                ArgumentCaptor.forClass(WifiScanner.ScanListener.class);
        verify(mWifiScanner).startScan(scanSettingsCaptor.capture(), scanListenerCaptor.capture(),
                eq(null));
        WifiScanner.ScanSettings actualSettings = scanSettingsCaptor.getValue();
        assertEquals("band", band, actualSettings.band);
        assertEquals("reportEvents", reportEvents, actualSettings.reportEvents);

        if (hiddenNetworkSSIDSet == null) {
            hiddenNetworkSSIDSet = new HashSet<>();
        }
        Set<String> actualHiddenNetworkSSIDSet = new HashSet<>();
        if (actualSettings.hiddenNetworks != null) {
            for (int i = 0; i < actualSettings.hiddenNetworks.length; ++i) {
                actualHiddenNetworkSSIDSet.add(actualSettings.hiddenNetworks[i].ssid);
            }
        }
        assertEquals("hidden networks", hiddenNetworkSSIDSet, actualHiddenNetworkSSIDSet);

        when(mWifiNative.getScanResults()).thenReturn(getMockScanResults());
        mWsm.sendMessage(WifiMonitor.SCAN_RESULTS_EVENT);

        mLooper.dispatchAll();

        List<ScanResult> reportedResults = mWsm.syncGetScanResultsList();
        assertEquals(8, reportedResults.size());
    }

    @Test
    public void scan() throws Exception {
        addNetworkAndVerifySuccess();

        mWsm.setOperationalMode(WifiStateMachine.CONNECT_MODE);
        mWsm.startScan(-1, 0, null, null);
        mLooper.dispatchAll();

        verifyScan(WifiScanner.WIFI_BAND_BOTH_WITH_DFS,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN
                | WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT, null);
    }

    @Test
    public void scanWithHiddenNetwork() throws Exception {
        addNetworkAndVerifySuccess(true);

        Set<String> hiddenNetworkSet = new HashSet<>();
        hiddenNetworkSet.add(sSSID);
        List<WifiScanner.ScanSettings.HiddenNetwork> hiddenNetworkList = new ArrayList<>();
        hiddenNetworkList.add(new WifiScanner.ScanSettings.HiddenNetwork(sSSID));
        when(mWifiConfigManager.retrieveHiddenNetworkList()).thenReturn(hiddenNetworkList);

        mWsm.setOperationalMode(WifiStateMachine.CONNECT_MODE);
        mWsm.startScan(-1, 0, null, null);
        mLooper.dispatchAll();

        verifyScan(WifiScanner.WIFI_BAND_BOTH_WITH_DFS,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN
                | WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT,
                hiddenNetworkSet);
    }

    @Test
    public void connect() throws Exception {
        addNetworkAndVerifySuccess();

        mWsm.setOperationalMode(WifiStateMachine.CONNECT_MODE);
        mLooper.dispatchAll();

        mLooper.startAutoDispatch();
        mWsm.syncEnableNetwork(mWsmAsyncChannel, 0, true);
        mLooper.stopAutoDispatch();

        verify(mWifiConfigManager).enableNetwork(eq(0), eq(true), anyInt());

        mWsm.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        mWsm.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mLooper.dispatchAll();

        assertEquals("ObtainingIpState", getCurrentState().getName());

        DhcpResults dhcpResults = new DhcpResults();
        dhcpResults.setGateway("1.2.3.4");
        dhcpResults.setIpAddress("192.168.1.100", 0);
        dhcpResults.addDns("8.8.8.8");
        dhcpResults.setLeaseDuration(3600);

        mTestIpManager.injectDhcpSuccess(dhcpResults);
        mLooper.dispatchAll();

        assertEquals("ConnectedState", getCurrentState().getName());
    }

    @Test
    public void testDhcpFailure() throws Exception {
        addNetworkAndVerifySuccess();

        mWsm.setOperationalMode(WifiStateMachine.CONNECT_MODE);
        mLooper.dispatchAll();

        mLooper.startAutoDispatch();
        mWsm.syncEnableNetwork(mWsmAsyncChannel, 0, true);
        mLooper.stopAutoDispatch();

        verify(mWifiConfigManager).enableNetwork(eq(0), eq(true), anyInt());

        mWsm.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        mWsm.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mLooper.dispatchAll();

        assertEquals("ObtainingIpState", getCurrentState().getName());

        mTestIpManager.injectDhcpFailure();
        mLooper.dispatchAll();

        assertEquals("DisconnectingState", getCurrentState().getName());
    }

    @Test
    public void testBadNetworkEvent() throws Exception {
        addNetworkAndVerifySuccess();

        mWsm.setOperationalMode(WifiStateMachine.CONNECT_MODE);
        mLooper.dispatchAll();

        mLooper.startAutoDispatch();
        mWsm.syncEnableNetwork(mWsmAsyncChannel, 0, true);
        mLooper.stopAutoDispatch();

        verify(mWifiConfigManager).enableNetwork(eq(0), eq(true), anyInt());

        mWsm.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        mWsm.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mLooper.dispatchAll();

        assertEquals("DisconnectedState", getCurrentState().getName());
    }


    @Test
    public void smToString() throws Exception {
        assertEquals("CMD_CHANNEL_HALF_CONNECTED", mWsm.smToString(
                AsyncChannel.CMD_CHANNEL_HALF_CONNECTED));
        assertEquals("CMD_PRE_DHCP_ACTION", mWsm.smToString(
                DhcpClient.CMD_PRE_DHCP_ACTION));
        assertEquals("CMD_IP_REACHABILITY_LOST", mWsm.smToString(
                WifiStateMachine.CMD_IP_REACHABILITY_LOST));
    }

    @Test
    public void disconnect() throws Exception {
        connect();

        mWsm.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, -1, 3, "01:02:03:04:05:06");
        mLooper.dispatchAll();
        mWsm.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.DISCONNECTED));
        mLooper.dispatchAll();

        assertEquals("DisconnectedState", getCurrentState().getName());
    }

    /**
     * Successfully connecting to a network will set WifiConfiguration's value of HasEverConnected
     * to true.
     *
     * Test: Successfully create and connect to a network. Check the config and verify
     * WifiConfiguration.getHasEverConnected() is true.
     */
    @Test
    public void setHasEverConnectedTrueOnConnect() throws Exception {
        connect();
        verify(mWifiConfigManager, atLeastOnce()).updateNetworkAfterConnect(0);
    }

    /**
     * Fail network connection attempt and verify HasEverConnected remains false.
     *
     * Test: Successfully create a network but fail when connecting. Check the config and verify
     * WifiConfiguration.getHasEverConnected() is false.
     */
    @Test
    public void connectionFailureDoesNotSetHasEverConnectedTrue() throws Exception {
        testDhcpFailure();
        verify(mWifiConfigManager, never()).updateNetworkAfterConnect(0);
    }

    @Test
    public void iconQueryTest() throws Exception {
        // TODO(b/31065385): Passpoint config management.
    }

    /**
     * Verifies that, by default, we allow only the "normal" number of log records.
     */
    @Test
    public void normalLogRecSizeIsUsedByDefault() {
        for (int i = 0; i < WifiStateMachine.NUM_LOG_RECS_NORMAL * 2; i++) {
            mWsm.sendMessage(WifiStateMachine.CMD_BOOT_COMPLETED);
        }
        mLooper.dispatchAll();
        assertEquals(WifiStateMachine.NUM_LOG_RECS_NORMAL, mWsm.getLogRecSize());
    }

    /**
     * Verifies that, in verbose mode, we allow a larger number of log records.
     */
    @Test
    public void enablingVerboseLoggingIncreasesLogRecSize() {
        assertTrue(LOG_REC_LIMIT_IN_VERBOSE_MODE > WifiStateMachine.NUM_LOG_RECS_NORMAL);
        mWsm.enableVerboseLogging(1);
        for (int i = 0; i < LOG_REC_LIMIT_IN_VERBOSE_MODE * 2; i++) {
            mWsm.sendMessage(WifiStateMachine.CMD_BOOT_COMPLETED);
        }
        mLooper.dispatchAll();
        assertEquals(LOG_REC_LIMIT_IN_VERBOSE_MODE, mWsm.getLogRecSize());
    }

    /**
     * Verifies that moving from verbose mode to normal mode resets the buffer, and limits new
     * records to a small number of entries.
     */
    @Test
    public void disablingVerboseLoggingClearsRecordsAndDecreasesLogRecSize() {
        mWsm.enableVerboseLogging(1);
        for (int i = 0; i < LOG_REC_LIMIT_IN_VERBOSE_MODE; i++) {
            mWsm.sendMessage(WifiStateMachine.CMD_BOOT_COMPLETED);
        }
        mLooper.dispatchAll();
        assertEquals(LOG_REC_LIMIT_IN_VERBOSE_MODE, mWsm.getLogRecSize());

        mWsm.enableVerboseLogging(0);
        assertEquals(0, mWsm.getLogRecSize());
        for (int i = 0; i < LOG_REC_LIMIT_IN_VERBOSE_MODE; i++) {
            mWsm.sendMessage(WifiStateMachine.CMD_BOOT_COMPLETED);
        }
        mLooper.dispatchAll();
        assertEquals(WifiStateMachine.NUM_LOG_RECS_NORMAL, mWsm.getLogRecSize());
    }

    /** Verifies that enabling verbose logging sets the hal log property in eng builds. */
    @Test
    public void enablingVerboseLoggingSetsHalLogPropertyInEngBuilds() {
        reset(mPropertyService);  // Ignore calls made in setUp()
        when(mBuildProperties.isEngBuild()).thenReturn(true);
        when(mBuildProperties.isUserdebugBuild()).thenReturn(false);
        when(mBuildProperties.isUserBuild()).thenReturn(false);
        mWsm.enableVerboseLogging(1);
        verify(mPropertyService).set("log.tag.WifiHAL", "V");
    }

    /** Verifies that enabling verbose logging sets the hal log property in userdebug builds. */
    @Test
    public void enablingVerboseLoggingSetsHalLogPropertyInUserdebugBuilds() {
        reset(mPropertyService);  // Ignore calls made in setUp()
        when(mBuildProperties.isUserdebugBuild()).thenReturn(true);
        when(mBuildProperties.isEngBuild()).thenReturn(false);
        when(mBuildProperties.isUserBuild()).thenReturn(false);
        mWsm.enableVerboseLogging(1);
        verify(mPropertyService).set("log.tag.WifiHAL", "V");
    }

    /** Verifies that enabling verbose logging does NOT set the hal log property in user builds. */
    @Test
    public void enablingVerboseLoggingDoeNotSetHalLogPropertyInUserBuilds() {
        reset(mPropertyService);  // Ignore calls made in setUp()
        when(mBuildProperties.isUserBuild()).thenReturn(true);
        when(mBuildProperties.isEngBuild()).thenReturn(false);
        when(mBuildProperties.isUserdebugBuild()).thenReturn(false);
        mWsm.enableVerboseLogging(1);
        verify(mPropertyService, never()).set(anyString(), anyString());
    }

    private int testGetSupportedFeaturesCase(int supportedFeatures, boolean rttConfigured) {
        AsyncChannel channel = mock(AsyncChannel.class);
        Message reply = Message.obtain();
        reply.arg1 = supportedFeatures;
        reset(mPropertyService);  // Ignore calls made in setUp()
        when(channel.sendMessageSynchronously(WifiStateMachine.CMD_GET_SUPPORTED_FEATURES))
                .thenReturn(reply);
        when(mPropertyService.getBoolean("config.disable_rtt", false))
                .thenReturn(rttConfigured);
        return mWsm.syncGetSupportedFeatures(channel);
    }

    /** Verifies that syncGetSupportedFeatures() masks out capabilities based on system flags. */
    @Test
    public void syncGetSupportedFeatures() {
        final int featureAware = WifiManager.WIFI_FEATURE_AWARE;
        final int featureInfra = WifiManager.WIFI_FEATURE_INFRA;
        final int featureD2dRtt = WifiManager.WIFI_FEATURE_D2D_RTT;
        final int featureD2apRtt = WifiManager.WIFI_FEATURE_D2AP_RTT;

        assertEquals(0, testGetSupportedFeaturesCase(0, false));
        assertEquals(0, testGetSupportedFeaturesCase(0, true));
        assertEquals(featureAware | featureInfra,
                testGetSupportedFeaturesCase(featureAware | featureInfra, false));
        assertEquals(featureAware | featureInfra,
                testGetSupportedFeaturesCase(featureAware | featureInfra, true));
        assertEquals(featureInfra | featureD2dRtt,
                testGetSupportedFeaturesCase(featureInfra | featureD2dRtt, false));
        assertEquals(featureInfra,
                testGetSupportedFeaturesCase(featureInfra | featureD2dRtt, true));
        assertEquals(featureInfra | featureD2apRtt,
                testGetSupportedFeaturesCase(featureInfra | featureD2apRtt, false));
        assertEquals(featureInfra,
                testGetSupportedFeaturesCase(featureInfra | featureD2apRtt, true));
        assertEquals(featureInfra | featureD2dRtt | featureD2apRtt,
                testGetSupportedFeaturesCase(featureInfra | featureD2dRtt | featureD2apRtt, false));
        assertEquals(featureInfra,
                testGetSupportedFeaturesCase(featureInfra | featureD2dRtt | featureD2apRtt, true));
    }
}
