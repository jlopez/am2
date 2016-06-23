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
import android.app.AppGlobals;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;
import com.android.internal.os.BackgroundThread;
import com.android.internal.R;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.am.UserInactivityCountdownDialog.OnCountDownExpiredListener;

import java.io.File;

public class RetailDemoModeService extends SystemService {
    private static final boolean DEBUG = false;

    private static final String TAG = RetailDemoModeService.class.getSimpleName();
    private static final String DEMO_USER_NAME = "Demo";
    private static final String ACTION_RESET_DEMO = "com.android.server.am.ACTION_RESET_DEMO";

    private static final int MSG_TURN_SCREEN_ON = 0;
    private static final int MSG_INACTIVITY_TIME_OUT = 1;
    private static final int MSG_START_NEW_SESSION = 2;

    private static final long SCREEN_WAKEUP_DELAY = 2500;
    private static final long USER_INACTIVITY_TIMEOUT = 30000;
    private static final long WARNING_DIALOG_TIMEOUT = 6000;
    private static final long MILLIS_PER_SECOND = 1000;

    boolean mDeviceInDemoMode = false;
    private ActivityManagerService mAms;
    private NotificationManager mNm;
    private UserManager mUm;
    private PowerManager mPm;
    private PowerManager.WakeLock mWakeLock;
    Handler mHandler;
    private ServiceThread mHandlerThread;
    private PendingIntent mResetDemoPendingIntent;

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mDeviceInDemoMode) {
                return;
            }
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_OFF:
                    mHandler.removeMessages(MSG_TURN_SCREEN_ON);
                    mHandler.sendEmptyMessageDelayed(MSG_TURN_SCREEN_ON, SCREEN_WAKEUP_DELAY);
                    break;
                case ACTION_RESET_DEMO:
                    mHandler.sendEmptyMessage(MSG_START_NEW_SESSION);
                    break;
            }
        }
    };

    final class MainHandler extends Handler {

        MainHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_TURN_SCREEN_ON:
                    if (mWakeLock.isHeld()) {
                        mWakeLock.release();
                    }
                    mWakeLock.acquire();
                    break;
                case MSG_INACTIVITY_TIME_OUT:
                    final IPackageManager pm = AppGlobals.getPackageManager();
                    int enabledState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
                    String demoLauncherComponent = getContext().getResources()
                            .getString(R.string.config_demoModeLauncherComponent);
                    try {
                        enabledState = pm.getComponentEnabledSetting(
                                ComponentName.unflattenFromString(demoLauncherComponent),
                            getActivityManager().getCurrentUser().id);
                    } catch (RemoteException exc) {
                        // XXX: shouldn't happen
                    }
                    if (enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                        Slog.i(TAG, "User inactivity timeout reached");
                        showInactivityCountdownDialog();
                    }
                    break;
                case MSG_START_NEW_SESSION:
                    if (DEBUG) {
                        Slog.d(TAG, "Switching to a new demo user");
                    }
                    removeMessages(MSG_START_NEW_SESSION);
                    removeMessages(MSG_INACTIVITY_TIME_OUT);
                    final UserInfo demoUser = getUserManager().createUser(DEMO_USER_NAME,
                            UserInfo.FLAG_DEMO | UserInfo.FLAG_EPHEMERAL);
                    if (demoUser != null) {
                        setupDemoUser(demoUser);
                        getActivityManager().switchUser(demoUser.id);
                    }
                    break;
            }
        }
    }

    private void showInactivityCountdownDialog() {
        UserInactivityCountdownDialog dialog = new UserInactivityCountdownDialog(getContext(),
                WARNING_DIALOG_TIMEOUT, MILLIS_PER_SECOND);
        dialog.setPositiveButtonClickListener(null);
        dialog.setNegativeButtonClickListener(new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mHandler.sendEmptyMessage(MSG_START_NEW_SESSION);
            }
        });
        dialog.setOnCountDownExpiredListener(new OnCountDownExpiredListener() {
            @Override
            public void onCountDownExpired() {
                mHandler.sendEmptyMessage(MSG_START_NEW_SESSION);
            }
        });
        dialog.show();
    }

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
                .setColor(getContext().getColor(R.color.system_notification_accent_color))
                .build();
    }

    private PendingIntent getResetDemoPendingIntent() {
        if (mResetDemoPendingIntent == null) {
            Intent intent = new Intent(ACTION_RESET_DEMO);
            mResetDemoPendingIntent = PendingIntent.getBroadcast(getContext(), 0, intent, 0);
        }
        return mResetDemoPendingIntent;
    }

    private void setupDemoUser(UserInfo userInfo) {
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
                if (deviceDemoModeUri.equals(uri)) {
                    mDeviceInDemoMode = UserManager.isDeviceInDemoMode(getContext());
                    if (mDeviceInDemoMode) {
                        mHandler.sendEmptyMessage(MSG_START_NEW_SESSION);
                    } else if (mWakeLock.isHeld()) {
                        mWakeLock.release();
                    }
                }
                // If device is provisioned and left demo mode - run the cleanup in demo folder
                if (!mDeviceInDemoMode && isDeviceProvisioned()) {
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

    private boolean isDeviceProvisioned() {
        return Settings.Global.getInt(
                getContext().getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 0) != 0;
    }

    private boolean deleteDemoFolderContents() {
        final File dir = Environment.getDataPreloadsDemoDirectory();
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
        mHandler = new MainHandler(mHandlerThread.getLooper());
        publishLocalService(RetailDemoModeServiceInternal.class, mLocalService);
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
            mDeviceInDemoMode = true;
            mHandler.sendEmptyMessage(MSG_START_NEW_SESSION);
        }
        registerSettingsChangeObserver();
        registerBroadcastReceiver();
    }

    @Override
    public void onSwitchUser(int userId) {
        if (!mDeviceInDemoMode) {
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, "onSwitchUser: " + userId);
        }
        final UserInfo ui = getUserManager().getUserInfo(userId);
        if (!ui.isDemo()) {
            Slog.wtf(TAG, "Should not allow switch to non-demo user in demo mode");
            return;
        }
        if (!mWakeLock.isHeld()) {
            mWakeLock.acquire();
        }
        mNm.notifyAsUser(TAG, 1, createResetNotification(), UserHandle.of(userId));
    }

    public RetailDemoModeServiceInternal mLocalService = new RetailDemoModeServiceInternal() {
        private static final long USER_ACTIVITY_DEBOUNCE_TIME = 2000;
        private long mLastUserActivityTime = 0;

        @Override
        public void onUserActivity() {
            if (!mDeviceInDemoMode) {
                return;
            }
            long timeOfActivity = SystemClock.uptimeMillis();
            if (timeOfActivity < mLastUserActivityTime + USER_ACTIVITY_DEBOUNCE_TIME) {
                return;
            }
            mLastUserActivityTime = timeOfActivity;
            mHandler.removeMessages(MSG_INACTIVITY_TIME_OUT);
            mHandler.sendEmptyMessageDelayed(MSG_INACTIVITY_TIME_OUT, USER_INACTIVITY_TIMEOUT);
        }
    };
}
