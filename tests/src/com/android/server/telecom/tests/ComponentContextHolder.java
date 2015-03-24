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

package com.android.server.telecom.tests;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import com.android.internal.telecom.IConnectionService;
import com.android.internal.telecom.IInCallService;

import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IContentProvider;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.media.AudioManager;
import android.os.IInterface;
import android.os.UserHandle;
import android.telecom.ConnectionService;
import android.telecom.InCallService;
import android.telecom.PhoneAccount;
import android.telephony.TelephonyManager;
import android.test.mock.MockContext;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;

/**
 * Controls a test {@link Context} as would be provided by the Android framework to an
 * {@code Activity}, {@code Service} or other system-instantiated component.
 *
 * The {@link Context} created by this object is "hollow" but its {@code applicationContext}
 * property points to an application context implementing all the nontrivial functionality.
 */
public class ComponentContextHolder implements TestDoubleHolder<Context> {

    public class TestApplicationContext extends MockContext {
        @Override
        public PackageManager getPackageManager() {
            return mPackageManager;
        }

        @Override
        public File getFilesDir() {
            try {
                return File.createTempFile("temp", "temp").getParentFile();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean bindServiceAsUser(
                Intent serviceIntent,
                ServiceConnection connection,
                int flags,
                UserHandle userHandle) {
            // TODO: Implement "as user" functionality
            return bindService(serviceIntent, connection, flags);
        }

        @Override
        public boolean bindService(
                Intent serviceIntent,
                ServiceConnection connection,
                int flags) {
            if (mServiceByServiceConnection.containsKey(connection)) {
                throw new RuntimeException("ServiceConnection already bound: " + connection);
            }
            IInterface service = mServiceByComponentName.get(serviceIntent.getComponent());
            if (service == null) {
                throw new RuntimeException("ServiceConnection not found: "
                        + serviceIntent.getComponent());
            }
            mServiceByServiceConnection.put(connection, service);
            connection.onServiceConnected(serviceIntent.getComponent(), service.asBinder());
            return true;
        }

        @Override
        public void unbindService(
                ServiceConnection connection) {
            IInterface service = mServiceByServiceConnection.remove(connection);
            if (service == null) {
                throw new RuntimeException("ServiceConnection not found: " + connection);
            }
            connection.onServiceDisconnected(mComponentNameByService.get(service));
        }

        @Override
        public Object getSystemService(String name) {
            switch (name) {
                case Context.AUDIO_SERVICE:
                    return mAudioManager;
                case Context.TELEPHONY_SERVICE:
                    return mTelephonyManager;
                default:
                    return null;
            }
        }

        @Override
        public Resources getResources() {
            return mResources;
        }

        @Override
        public String getOpPackageName() {
            return "test";
        }

        @Override
        public ContentResolver getContentResolver() {
            return new ContentResolver(this) {
                @Override
                protected IContentProvider acquireProvider(Context c, String name) {
                    return null;
                }

                @Override
                public boolean releaseProvider(IContentProvider icp) {
                    return false;
                }

                @Override
                protected IContentProvider acquireUnstableProvider(Context c, String name) {
                    return null;
                }

                @Override
                public boolean releaseUnstableProvider(IContentProvider icp) {
                    return false;
                }

                @Override
                public void unstableProviderDied(IContentProvider icp) {

                }
            };
        }

        @Override
        public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
            // TODO -- this is called by WiredHeadsetManager!!!
            return null;
        }

        @Override
        public void sendBroadcast(Intent intent) {
            // TODO -- need to ensure this is captured
        }

        @Override
        public void sendBroadcast(Intent intent, String receiverPermission) {
            // TODO -- need to ensure this is captured
        }
    };

    private final Multimap<String, ComponentName> mComponentNamesByAction =
            ArrayListMultimap.create();
    private final Map<ComponentName, IInterface> mServiceByComponentName = new HashMap<>();
    private final Map<ComponentName, ServiceInfo> mServiceInfoByComponentName = new HashMap<>();
    private final Map<IInterface, ComponentName> mComponentNameByService = new HashMap<>();
    private final Map<ServiceConnection, IInterface> mServiceByServiceConnection = new HashMap<>();

    private final Context mContext = new MockContext() {
        @Override
        public Context getApplicationContext() {
            return mApplicationContextSpy;
        }
    };

    // The application context is the most important object this class provides to the system
    // under test.
    private final Context mApplicationContext = new TestApplicationContext();

    // We then create a spy on the application context allowing standard Mockito-style
    // when(...) logic to be used to add specific little responses where needed.

    private final Context mApplicationContextSpy = Mockito.spy(mApplicationContext);
    private final PackageManager mPackageManager = Mockito.mock(PackageManager.class);
    private final AudioManager mAudioManager = Mockito.mock(AudioManager.class);
    private final TelephonyManager mTelephonyManager = Mockito.mock(TelephonyManager.class);
    private final Resources mResources = Mockito.mock(Resources.class);
    private final Configuration mResourceConfiguration = new Configuration();

    public ComponentContextHolder() {
        MockitoAnnotations.initMocks(this);
        when(mResources.getConfiguration()).thenReturn(mResourceConfiguration);
        mResourceConfiguration.setLocale(Locale.TAIWAN);

        // TODO: Move into actual tests
        when(mAudioManager.isWiredHeadsetOn()).thenReturn(false);

        doAnswer(new Answer<List<ResolveInfo>>() {
            @Override
            public List<ResolveInfo> answer(InvocationOnMock invocation) throws Throwable {
                return doQueryIntentServices(
                        (Intent) invocation.getArguments()[0],
                        (Integer) invocation.getArguments()[1]);
            }
        }).when(mPackageManager).queryIntentServices((Intent) any(), anyInt());

        doAnswer(new Answer<List<ResolveInfo>>() {
            @Override
            public List<ResolveInfo> answer(InvocationOnMock invocation) throws Throwable {
                return doQueryIntentServices(
                        (Intent) invocation.getArguments()[0],
                        (Integer) invocation.getArguments()[1]);
            }
        }).when(mPackageManager).queryIntentServicesAsUser((Intent) any(), anyInt(), anyInt());

        when(mTelephonyManager.getSubIdForPhoneAccount((PhoneAccount) any())).thenReturn(1);
    }

    @Override
    public Context getTestDouble() {
        return mContext;
    }

    public void addConnectionService(
            ComponentName componentName,
            IConnectionService service)
            throws Exception {
        addService(ConnectionService.SERVICE_INTERFACE, componentName, service);
        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.permission = android.Manifest.permission.BIND_CONNECTION_SERVICE;
        serviceInfo.packageName = componentName.getPackageName();
        serviceInfo.name = componentName.getClassName();
        mServiceInfoByComponentName.put(componentName, serviceInfo);
    }

    public void addInCallService(
            ComponentName componentName,
            IInCallService service)
            throws Exception {
        addService(InCallService.SERVICE_INTERFACE, componentName, service);
        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.permission = android.Manifest.permission.BIND_INCALL_SERVICE;
        serviceInfo.packageName = componentName.getPackageName();
        serviceInfo.name = componentName.getClassName();
        mServiceInfoByComponentName.put(componentName, serviceInfo);
    }

    public void putResource(int id, String value) {
        when(mResources.getString(eq(id))).thenReturn(value);
    }

    private void addService(String action, ComponentName name, IInterface service) {
        mComponentNamesByAction.put(action, name);
        mServiceByComponentName.put(name, service);
        mComponentNameByService.put(service, name);
    }

    private List<ResolveInfo> doQueryIntentServices(Intent intent, int flags) {
        List<ResolveInfo> result = new ArrayList<>();
        for (ComponentName componentName : mComponentNamesByAction.get(intent.getAction())) {
            ResolveInfo resolveInfo = new ResolveInfo();
            resolveInfo.serviceInfo = mServiceInfoByComponentName.get(componentName);
            result.add(resolveInfo);
        }
        return result;
    }
}
