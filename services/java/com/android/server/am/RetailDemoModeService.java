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
 * limitations under the License
 */

package com.android.server.am;

import android.app.ActivityManagerNative;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;
import com.android.internal.R;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.pm.UserManagerService;

import java.io.File;

public class RetailDemoModeService extends SystemService {
    private static final boolean DEBUG = false;

    private static final String TAG = RetailDemoModeService.class.getSimpleName();
    private static final String DEMO_USER_NAME = "Demo";
    private static final String ACTION_RESET_DEMO = "com.android.server.am.ACTION_RESET_DEMO";

    private static final long SCREEN_WAKEUP_DELAY = 5000;

    private ActivityManagerService mAms;
    private NotificationManager mNm;
    private UserManager mUm;
    private PowerManager mPm;
    private PowerManager.WakeLock mWakeLock;
    private Handler mHandler;
    private ServiceThread mHandlerThread;
    private PendingIntent mResetDemoPendingIntent;

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!UserManager.isDeviceInDemoMode(getContext())) {
                return;
            }
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_OFF:
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (mWakeLock.isHeld()) {
                                mWakeLock.release();
                            }
                            mWakeLock.acquire();
                        }
                    }, SCREEN_WAKEUP_DELAY);
                    break;
                case ACTION_RESET_DEMO:
                    createAndSwitchToDemoUser();
                    break;
            }
        }
    };

    public RetailDemoModeService(Context context) {
        super(context);
    }

    private Notification createResetNotification() {
        return new Notification.Builder(getContext())
                .setContentTitle(getContext().getString(R.string.reset_retail_demo_mode_title))
                .setContentText(getContext().getString(R.string.reset_retail_demo_mode_text))
                .setOngoing(true)
                .setSmallIcon(R.drawable.platlogo)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(getResetDemoPendingIntent())
                .build();
    }

    private PendingIntent getResetDemoPendingIntent() {
        if (mResetDemoPendingIntent == null) {
            Intent intent = new Intent(ACTION_RESET_DEMO);
            mResetDemoPendingIntent = PendingIntent.getBroadcast(getContext(), 0, intent, 0);
        }
        return mResetDemoPendingIntent;
    }

    private void createAndSwitchToDemoUser() {
        if (DEBUG) {
            Slog.d(TAG, "Switching to a new demo user");
        }
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                UserInfo demoUser = getUserManager().createUser(DEMO_USER_NAME,
                        UserInfo.FLAG_DEMO | UserInfo.FLAG_EPHEMERAL);
                if (demoUser != null) {
                    setupDemoUser(demoUser);
                    getActivityManager().switchUser(demoUser.id);
                }
            }
        });
    }

    void setupDemoUser(UserInfo userInfo) {
        UserManager um = getUserManager();
        UserHandle user = UserHandle.of(userInfo.id);
        LockPatternUtils lockPatternUtils = new LockPatternUtils(getContext());
        lockPatternUtils.setLockScreenDisabled(true, userInfo.id);
        um.setUserRestriction(UserManager.DISALLOW_CONFIG_WIFI, true, user);
        um.setUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, true, user);
        um.setUserRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, true, user);
        um.setUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER, true, user);
        Settings.Secure.putIntForUser(getContext().getContentResolver(),
                Settings.Secure.SKIP_FIRST_USE_HINTS, 1, userInfo.id);
    }

    private ActivityManagerService getActivityManager() {
        if (mAms == null) {
            mAms = (ActivityManagerService) ActivityManagerNative.getDefault();
        }
        return mAms;
    }

    private UserManager getUserManager() {
        if (mUm == null) {
            mUm = getContext().getSystemService(UserManager.class);
        }
        return mUm;
    }

    private void registerSettingsChangeObserver() {
        final Uri deviceDemoModeUri = Settings.Global.getUriFor(Settings.Global.DEVICE_DEMO_MODE);
        final Uri deviceProvisionedUri = Settings.Global.getUriFor(
                Settings.Global.DEVICE_PROVISIONED);
        final ContentResolver cr = getContext().getContentResolver();
        final ContentObserver deviceDemoModeSettingObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri, int userId) {
                boolean deviceInDemoMode = UserManager.isDeviceInDemoMode(getContext());
                if (deviceDemoModeUri.equals(uri)) {
                    if (deviceInDemoMode) {
                        createAndSwitchToDemoUser();
                    }
                }
                // If device is provisioned and left demo mode - run the cleanup in demo folder
                if (!deviceInDemoMode && isDeviceProvisioned()) {
                    // Run on the bg thread to not block the fg thread
                    BackgroundThread.getHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            if (!deleteDemoFolderContents()) {
                                Slog.w(TAG, "Failed to delete demo folder contents");
                            }
                        }
                    });
                }
            }
        };
        cr.registerContentObserver(deviceDemoModeUri, false, deviceDemoModeSettingObserver,
                UserHandle.USER_SYSTEM);
        cr.registerContentObserver(deviceProvisionedUri, false, deviceDemoModeSettingObserver,
                UserHandle.USER_SYSTEM);
    }

    boolean isDeviceProvisioned() {
        return Settings.Global.getInt(
                getContext().getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 0) != 0;
    }

    private boolean deleteDemoFolderContents() {
        File dir = Environment.getDataPreloadsDemoDirectory();
        Slog.i(TAG, "Deleting contents of " + dir);
        return FileUtils.deleteContents(dir);
    }

    private void registerBroadcastReceiver() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(ACTION_RESET_DEMO);
        getContext().registerReceiver(mBroadcastReceiver, filter);
    }

    @Override
    public void onStart() {
        if (DEBUG) {
            Slog.d(TAG, "Service starting up");
        }
        mHandlerThread = new ServiceThread(TAG, android.os.Process.THREAD_PRIORITY_FOREGROUND,
                false);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper(), null, true);
    }

    @Override
    public void onBootPhase(int bootPhase) {
        if (bootPhase != PHASE_THIRD_PARTY_APPS_CAN_START) {
            return;
        }
        mPm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        mWakeLock = mPm
                .newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG);
        mNm = NotificationManager.from(getContext());

        if (UserManager.isDeviceInDemoMode(getContext())) {
            createAndSwitchToDemoUser();
        }
        registerSettingsChangeObserver();
        registerBroadcastReceiver();
    }

    @Override
    public void onSwitchUser(int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onSwitchUser: " + userId);
        }
        UserInfo ui = getUserManager().getUserInfo(userId);
        if (!ui.isDemo()) {
            if (UserManager.isDeviceInDemoMode(getContext())) {
                Slog.wtf(TAG, "Should not allow switch to non-demo user in demo mode");
            } else if (mWakeLock.isHeld()) {
                mWakeLock.release();
            }
            return;
        }
        if (!mWakeLock.isHeld()) {
            mWakeLock.acquire();
        }
        mNm.notifyAsUser(TAG, 1, createResetNotification(), UserHandle.of(userId));
    }
}
