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
 * limitations under the License
 */

package com.android.server.am;

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.app.ActivityManager.USER_OP_IS_CURRENT;
import static android.app.ActivityManager.USER_OP_SUCCESS;
import static android.os.Process.SYSTEM_UID;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_MU;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityManagerService.ALLOW_FULL_ONLY;
import static com.android.server.am.ActivityManagerService.ALLOW_NON_FULL;
import static com.android.server.am.ActivityManagerService.ALLOW_NON_FULL_IN_PROFILE;
import static com.android.server.am.ActivityManagerService.MY_PID;
import static com.android.server.am.ActivityManagerService.REPORT_USER_SWITCH_COMPLETE_MSG;
import static com.android.server.am.ActivityManagerService.REPORT_USER_SWITCH_MSG;
import static com.android.server.am.ActivityManagerService.SYSTEM_USER_CURRENT_MSG;
import static com.android.server.am.ActivityManagerService.SYSTEM_USER_START_MSG;
import static com.android.server.am.ActivityManagerService.USER_SWITCH_TIMEOUT_MSG;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.Dialog;
import android.app.IStopUserCallback;
import android.app.IUserSwitchObserver;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.IUserManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.IMountService;
import android.os.storage.StorageManager;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.server.pm.UserManagerService;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Helper class for {@link ActivityManagerService} responsible for multi-user functionality.
 */
final class UserController {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "UserController" : TAG_AM;
    // Maximum number of users we allow to be running at a time.
    static final int MAX_RUNNING_USERS = 3;

    // Amount of time we wait for observers to handle a user switch before
    // giving up on them and unfreezing the screen.
    static final int USER_SWITCH_TIMEOUT = 2 * 1000;

    private final ActivityManagerService mService;
    private final Handler mHandler;

    // Holds the current foreground user's id
    private int mCurrentUserId = UserHandle.USER_SYSTEM;
    // Holds the target user's id during a user switch
    private int mTargetUserId = UserHandle.USER_NULL;

    /**
     * Which users have been started, so are allowed to run code.
     */
    @GuardedBy("mService")
    private final SparseArray<UserState> mStartedUsers = new SparseArray<>();

    /**
     * LRU list of history of current users.  Most recently current is at the end.
     */
    private final ArrayList<Integer> mUserLru = new ArrayList<>();

    /**
     * Constant array of the users that are currently started.
     */
    private int[] mStartedUserArray = new int[] { 0 };

    // If there are multiple profiles for the current user, their ids are here
    // Currently only the primary user can have managed profiles
    private int[] mCurrentProfileIds = new int[] {};

    /**
     * Mapping from each known user ID to the profile group ID it is associated with.
     */
    private final SparseIntArray mUserProfileGroupIdsSelfLocked = new SparseIntArray();

    /**
     * Registered observers of the user switching mechanics.
     */
    private final RemoteCallbackList<IUserSwitchObserver> mUserSwitchObservers
            = new RemoteCallbackList<>();

    /**
     * Currently active user switch.
     */
    Object mCurUserSwitchCallback;

    private volatile UserManagerService mUserManager;

    UserController(ActivityManagerService service) {
        mService = service;
        mHandler = mService.mHandler;
        // User 0 is the first and only user that runs at boot.
        final UserState uss = new UserState(UserHandle.SYSTEM);
        mStartedUsers.put(UserHandle.USER_SYSTEM, uss);
        updateUserUnlockedState(uss);
        mUserLru.add(UserHandle.USER_SYSTEM);
        updateStartedUserArrayLocked();
    }

    void finishUserSwitch(UserState uss) {
        synchronized (mService) {
            finishUserBoot(uss);

            startProfilesLocked();

            int num = mUserLru.size();
            int i = 0;
            while (num > MAX_RUNNING_USERS && i < mUserLru.size()) {
                Integer oldUserId = mUserLru.get(i);
                UserState oldUss = mStartedUsers.get(oldUserId);
                if (oldUss == null) {
                    // Shouldn't happen, but be sane if it does.
                    mUserLru.remove(i);
                    num--;
                    continue;
                }
                if (oldUss.mState == UserState.STATE_STOPPING
                        || oldUss.mState == UserState.STATE_SHUTDOWN) {
                    // This user is already stopping, doesn't count.
                    num--;
                    i++;
                    continue;
                }
                if (oldUserId == UserHandle.USER_SYSTEM || oldUserId == mCurrentUserId) {
                    // Owner/System user and current user can't be stopped. We count it as running
                    // when it is not a pure system user.
                    if (UserInfo.isSystemOnly(oldUserId)) {
                        num--;
                    }
                    i++;
                    continue;
                }
                // This is a user to be stopped.
                stopUserLocked(oldUserId, null);
                num--;
                i++;
            }
        }
    }

    void finishUserBoot(UserState uss) {
        synchronized (mService) {
            if (uss.mState == UserState.STATE_BOOTING
                    && mStartedUsers.get(uss.mHandle.getIdentifier()) == uss) {
                uss.mState = UserState.STATE_RUNNING;
                final int userId = uss.mHandle.getIdentifier();
                Intent intent = new Intent(Intent.ACTION_BOOT_COMPLETED, null);
                intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
                intent.addFlags(Intent.FLAG_RECEIVER_NO_ABORT);
                mService.broadcastIntentLocked(null, null, intent,
                        null, null, 0, null, null,
                        new String[]{android.Manifest.permission.RECEIVE_BOOT_COMPLETED},
                        AppOpsManager.OP_NONE, null, true, false, MY_PID, SYSTEM_UID, userId);
            }
        }
    }

    int stopUser(final int userId, final IStopUserCallback callback) {
        if (mService.checkCallingPermission(INTERACT_ACROSS_USERS_FULL)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: switchUser() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + INTERACT_ACROSS_USERS_FULL;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        if (userId < 0 || userId == UserHandle.USER_SYSTEM) {
            throw new IllegalArgumentException("Can't stop system user " + userId);
        }
        mService.enforceShellRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES,
                userId);
        synchronized (mService) {
            return stopUserLocked(userId, callback);
        }
    }

    private int stopUserLocked(final int userId, final IStopUserCallback callback) {
        if (DEBUG_MU) Slog.i(TAG, "stopUserLocked userId=" + userId);
        if (mCurrentUserId == userId && mTargetUserId == UserHandle.USER_NULL) {
            return USER_OP_IS_CURRENT;
        }

        final UserState uss = mStartedUsers.get(userId);
        if (uss == null) {
            // User is not started, nothing to do...  but we do need to
            // callback if requested.
            if (callback != null) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            callback.userStopped(userId);
                        } catch (RemoteException e) {
                        }
                    }
                });
            }
            return USER_OP_SUCCESS;
        }

        if (callback != null) {
            uss.mStopCallbacks.add(callback);
        }

        if (uss.mState != UserState.STATE_STOPPING
                && uss.mState != UserState.STATE_SHUTDOWN) {
            uss.mState = UserState.STATE_STOPPING;
            updateStartedUserArrayLocked();

            long ident = Binder.clearCallingIdentity();
            try {
                // We are going to broadcast ACTION_USER_STOPPING and then
                // once that is done send a final ACTION_SHUTDOWN and then
                // stop the user.
                final Intent stoppingIntent = new Intent(Intent.ACTION_USER_STOPPING);
                stoppingIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                stoppingIntent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
                stoppingIntent.putExtra(Intent.EXTRA_SHUTDOWN_USERSPACE_ONLY, true);
                final Intent shutdownIntent = new Intent(Intent.ACTION_SHUTDOWN);
                // This is the result receiver for the final shutdown broadcast.
                final IIntentReceiver shutdownReceiver = new IIntentReceiver.Stub() {
                    @Override
                    public void performReceive(Intent intent, int resultCode, String data,
                            Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
                        finishUserStop(uss);
                    }
                };
                // This is the result receiver for the initial stopping broadcast.
                final IIntentReceiver stoppingReceiver = new IIntentReceiver.Stub() {
                    @Override
                    public void performReceive(Intent intent, int resultCode, String data,
                            Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
                        // On to the next.
                        synchronized (mService) {
                            if (uss.mState != UserState.STATE_STOPPING) {
                                // Whoops, we are being started back up.  Abort, abort!
                                return;
                            }
                            uss.mState = UserState.STATE_SHUTDOWN;
                        }
                        mService.mBatteryStatsService.noteEvent(
                                BatteryStats.HistoryItem.EVENT_USER_RUNNING_FINISH,
                                Integer.toString(userId), userId);
                        mService.mSystemServiceManager.stopUser(userId);
                        mService.broadcastIntentLocked(null, null, shutdownIntent,
                                null, shutdownReceiver, 0, null, null, null, AppOpsManager.OP_NONE,
                                null, true, false, MY_PID, SYSTEM_UID, userId);
                    }
                };
                // Kick things off.
                mService.broadcastIntentLocked(null, null, stoppingIntent,
                        null, stoppingReceiver, 0, null, null,
                        new String[]{INTERACT_ACROSS_USERS}, AppOpsManager.OP_NONE,
                        null, true, false, MY_PID, SYSTEM_UID, UserHandle.USER_ALL);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        return USER_OP_SUCCESS;
    }

    void finishUserStop(UserState uss) {
        final int userId = uss.mHandle.getIdentifier();
        boolean stopped;
        ArrayList<IStopUserCallback> callbacks;
        synchronized (mService) {
            callbacks = new ArrayList<>(uss.mStopCallbacks);
            if (mStartedUsers.get(userId) != uss) {
                stopped = false;
            } else if (uss.mState != UserState.STATE_SHUTDOWN) {
                stopped = false;
            } else {
                stopped = true;
                // User can no longer run.
                mStartedUsers.remove(userId);
                mUserLru.remove(Integer.valueOf(userId));
                updateStartedUserArrayLocked();

                // Clean up all state and processes associated with the user.
                // Kill all the processes for the user.
                forceStopUserLocked(userId, "finish user");
            }
        }

        for (int i = 0; i < callbacks.size(); i++) {
            try {
                if (stopped) callbacks.get(i).userStopped(userId);
                else callbacks.get(i).userStopAborted(userId);
            } catch (RemoteException e) {
            }
        }

        if (stopped) {
            mService.mSystemServiceManager.cleanupUser(userId);
            synchronized (mService) {
                mService.mStackSupervisor.removeUserLocked(userId);
            }
        }
    }

    private void forceStopUserLocked(int userId, String reason) {
        mService.forceStopPackageLocked(null, -1, false, false, true, false, false,
                userId, reason);
        Intent intent = new Intent(Intent.ACTION_USER_STOPPED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                | Intent.FLAG_RECEIVER_FOREGROUND);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        mService.broadcastIntentLocked(null, null, intent,
                null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                null, false, false, MY_PID, SYSTEM_UID, UserHandle.USER_ALL);
    }


    /**
     * Stops the guest user if it has gone to the background.
     */
    private void stopGuestUserIfBackground() {
        synchronized (mService) {
            final int num = mUserLru.size();
            for (int i = 0; i < num; i++) {
                Integer oldUserId = mUserLru.get(i);
                UserState oldUss = mStartedUsers.get(oldUserId);
                if (oldUserId == UserHandle.USER_SYSTEM || oldUserId == mCurrentUserId
                        || oldUss.mState == UserState.STATE_STOPPING
                        || oldUss.mState == UserState.STATE_SHUTDOWN) {
                    continue;
                }
                UserInfo userInfo = getUserInfo(oldUserId);
                if (userInfo.isGuest()) {
                    // This is a user to be stopped.
                    stopUserLocked(oldUserId, null);
                    break;
                }
            }
        }
    }

    void startProfilesLocked() {
        if (DEBUG_MU) Slog.i(TAG, "startProfilesLocked");
        List<UserInfo> profiles = getUserManager().getProfiles(
                mCurrentUserId, false /* enabledOnly */);
        List<UserInfo> profilesToStart = new ArrayList<>(profiles.size());
        for (UserInfo user : profiles) {
            if ((user.flags & UserInfo.FLAG_INITIALIZED) == UserInfo.FLAG_INITIALIZED
                    && user.id != mCurrentUserId) {
                profilesToStart.add(user);
            }
        }
        final int profilesToStartSize = profilesToStart.size();
        int i = 0;
        for (; i < profilesToStartSize && i < (MAX_RUNNING_USERS - 1); ++i) {
            startUser(profilesToStart.get(i).id, /* foreground= */ false);
        }
        if (i < profilesToStartSize) {
            Slog.w(TAG, "More profiles than MAX_RUNNING_USERS");
        }
    }

    private UserManagerService getUserManager() {
        UserManagerService userManager = mUserManager;
        if (userManager == null) {
            IBinder b = ServiceManager.getService(Context.USER_SERVICE);
            userManager = mUserManager = (UserManagerService) IUserManager.Stub.asInterface(b);
        }
        return userManager;
    }

    private void updateUserUnlockedState(UserState uss) {
        final IMountService mountService = IMountService.Stub
                .asInterface(ServiceManager.getService("mount"));
        if (mountService != null) {
            try {
                uss.unlocked = mountService.isUserKeyUnlocked(uss.mHandle.getIdentifier());
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        } else {
            // System isn't fully booted yet, so guess based on property
            uss.unlocked = !StorageManager.isFileBasedEncryptionEnabled();
        }
    }

    boolean startUser(final int userId, final boolean foreground) {
        if (mService.checkCallingPermission(INTERACT_ACROSS_USERS_FULL)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: switchUser() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + INTERACT_ACROSS_USERS_FULL;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        if (DEBUG_MU) Slog.i(TAG, "starting userid:" + userId + " fore:" + foreground);

        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mService) {
                final int oldUserId = mCurrentUserId;
                if (oldUserId == userId) {
                    return true;
                }

                mService.mStackSupervisor.setLockTaskModeLocked(null,
                        ActivityManager.LOCK_TASK_MODE_NONE, "startUser", false);

                final UserInfo userInfo = getUserInfo(userId);
                if (userInfo == null) {
                    Slog.w(TAG, "No user info for user #" + userId);
                    return false;
                }
                if (foreground && userInfo.isManagedProfile()) {
                    Slog.w(TAG, "Cannot switch to User #" + userId + ": not a full user");
                    return false;
                }

                if (foreground) {
                    mService.mWindowManager.startFreezingScreen(
                            R.anim.screen_user_exit, R.anim.screen_user_enter);
                }

                boolean needStart = false;

                // If the user we are switching to is not currently started, then
                // we need to start it now.
                if (mStartedUsers.get(userId) == null) {
                    mStartedUsers.put(userId, new UserState(new UserHandle(userId)));
                    updateStartedUserArrayLocked();
                    needStart = true;
                }

                final UserState uss = mStartedUsers.get(userId);
                updateUserUnlockedState(uss);

                final Integer userIdInt = userId;
                mUserLru.remove(userIdInt);
                mUserLru.add(userIdInt);

                if (foreground) {
                    mCurrentUserId = userId;
                    mService.updateUserConfigurationLocked();
                    mTargetUserId = UserHandle.USER_NULL; // reset, mCurrentUserId has caught up
                    updateCurrentProfileIdsLocked();
                    mService.mWindowManager.setCurrentUser(userId, mCurrentProfileIds);
                    // Once the internal notion of the active user has switched, we lock the device
                    // with the option to show the user switcher on the keyguard.
                    mService.mWindowManager.lockNow(null);
                } else {
                    final Integer currentUserIdInt = mCurrentUserId;
                    updateCurrentProfileIdsLocked();
                    mService.mWindowManager.setCurrentProfileIds(mCurrentProfileIds);
                    mUserLru.remove(currentUserIdInt);
                    mUserLru.add(currentUserIdInt);
                }

                // Make sure user is in the started state.  If it is currently
                // stopping, we need to knock that off.
                if (uss.mState == UserState.STATE_STOPPING) {
                    // If we are stopping, we haven't sent ACTION_SHUTDOWN,
                    // so we can just fairly silently bring the user back from
                    // the almost-dead.
                    uss.mState = UserState.STATE_RUNNING;
                    updateStartedUserArrayLocked();
                    needStart = true;
                } else if (uss.mState == UserState.STATE_SHUTDOWN) {
                    // This means ACTION_SHUTDOWN has been sent, so we will
                    // need to treat this as a new boot of the user.
                    uss.mState = UserState.STATE_BOOTING;
                    updateStartedUserArrayLocked();
                    needStart = true;
                }

                if (uss.mState == UserState.STATE_BOOTING) {
                    // Let user manager propagate user restrictions to other services.
                    getUserManager().onBeforeStartUser(userId);

                    // Booting up a new user, need to tell system services about it.
                    // Note that this is on the same handler as scheduling of broadcasts,
                    // which is important because it needs to go first.
                    mHandler.sendMessage(mHandler.obtainMessage(SYSTEM_USER_START_MSG, userId, 0));
                }

                if (foreground) {
                    mHandler.sendMessage(mHandler.obtainMessage(SYSTEM_USER_CURRENT_MSG, userId,
                            oldUserId));
                    mHandler.removeMessages(REPORT_USER_SWITCH_MSG);
                    mHandler.removeMessages(USER_SWITCH_TIMEOUT_MSG);
                    mHandler.sendMessage(mHandler.obtainMessage(REPORT_USER_SWITCH_MSG,
                            oldUserId, userId, uss));
                    mHandler.sendMessageDelayed(mHandler.obtainMessage(USER_SWITCH_TIMEOUT_MSG,
                            oldUserId, userId, uss), USER_SWITCH_TIMEOUT);
                }

                if (needStart) {
                    // Send USER_STARTED broadcast
                    Intent intent = new Intent(Intent.ACTION_USER_STARTED);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                            | Intent.FLAG_RECEIVER_FOREGROUND);
                    intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
                    mService.broadcastIntentLocked(null, null, intent,
                            null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                            null, false, false, MY_PID, SYSTEM_UID, userId);
                }

                if ((userInfo.flags&UserInfo.FLAG_INITIALIZED) == 0) {
                    if (userId != UserHandle.USER_SYSTEM) {
                        Intent intent = new Intent(Intent.ACTION_USER_INITIALIZE);
                        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                        mService.broadcastIntentLocked(null, null, intent, null,
                                new IIntentReceiver.Stub() {
                                    public void performReceive(Intent intent, int resultCode,
                                            String data, Bundle extras, boolean ordered,
                                            boolean sticky, int sendingUser) {
                                        onUserInitialized(uss, foreground, oldUserId, userId);
                                    }
                                }, 0, null, null, null, AppOpsManager.OP_NONE,
                                null, true, false, MY_PID, SYSTEM_UID, userId);
                        uss.initializing = true;
                    } else {
                        getUserManager().makeInitialized(userInfo.id);
                    }
                }

                if (foreground) {
                    if (!uss.initializing) {
                        moveUserToForegroundLocked(uss, oldUserId, userId);
                    }
                } else {
                    mService.mStackSupervisor.startBackgroundUserLocked(userId, uss);
                }

                if (needStart) {
                    Intent intent = new Intent(Intent.ACTION_USER_STARTING);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                    intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
                    mService.broadcastIntentLocked(null, null, intent,
                            null, new IIntentReceiver.Stub() {
                                @Override
                                public void performReceive(Intent intent, int resultCode,
                                        String data, Bundle extras, boolean ordered, boolean sticky,
                                        int sendingUser) throws RemoteException {
                                }
                            }, 0, null, null,
                            new String[] {INTERACT_ACROSS_USERS}, AppOpsManager.OP_NONE,
                            null, true, false, MY_PID, SYSTEM_UID, UserHandle.USER_ALL);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        return true;
    }

    /**
     * Start user, if its not already running, and bring it to foreground.
     */
    boolean startUserInForeground(final int userId, Dialog dlg) {
        boolean result = startUser(userId, /* foreground */ true);
        dlg.dismiss();
        return result;
    }

    boolean unlockUser(final int userId, byte[] token) {
        if (mService.checkCallingPermission(INTERACT_ACROSS_USERS_FULL)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: unlockUser() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + INTERACT_ACROSS_USERS_FULL;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        final UserInfo userInfo = getUserInfo(userId);
        final IMountService mountService = IMountService.Stub
                .asInterface(ServiceManager.getService("mount"));
        try {
            mountService.unlockUserKey(userId, userInfo.serialNumber, token);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to unlock: " + e.getMessage());
            throw e.rethrowAsRuntimeException();
        }

        synchronized (mService) {
            final UserState uss = mStartedUsers.get(userId);
            updateUserUnlockedState(uss);
        }

        return true;
    }

    void showUserSwitchDialog(Pair<UserInfo, UserInfo> fromToUserPair) {
        // The dialog will show and then initiate the user switch by calling startUserInForeground
        Dialog d = new UserSwitchingDialog(mService, mService.mContext, fromToUserPair.first,
                fromToUserPair.second, true /* above system */);
        d.show();
    }

    void dispatchForegroundProfileChanged(int userId) {
        final int observerCount = mUserSwitchObservers.beginBroadcast();
        for (int i = 0; i < observerCount; i++) {
            try {
                mUserSwitchObservers.getBroadcastItem(i).onForegroundProfileSwitch(userId);
            } catch (RemoteException e) {
                // Ignore
            }
        }
        mUserSwitchObservers.finishBroadcast();
    }

    /** Called on handler thread */
    void dispatchUserSwitchComplete(int userId) {
        final int observerCount = mUserSwitchObservers.beginBroadcast();
        for (int i = 0; i < observerCount; i++) {
            try {
                mUserSwitchObservers.getBroadcastItem(i).onUserSwitchComplete(userId);
            } catch (RemoteException e) {
            }
        }
        mUserSwitchObservers.finishBroadcast();
    }

    void timeoutUserSwitch(UserState uss, int oldUserId, int newUserId) {
        synchronized (mService) {
            Slog.wtf(TAG, "User switch timeout: from " + oldUserId + " to " + newUserId);
            sendContinueUserSwitchLocked(uss, oldUserId, newUserId);
        }
    }

    void dispatchUserSwitch(final UserState uss, final int oldUserId,
            final int newUserId) {
        final int observerCount = mUserSwitchObservers.beginBroadcast();
        if (observerCount > 0) {
            final IRemoteCallback callback = new IRemoteCallback.Stub() {
                int mCount = 0;
                @Override
                public void sendResult(Bundle data) throws RemoteException {
                    synchronized (mService) {
                        if (mCurUserSwitchCallback == this) {
                            mCount++;
                            if (mCount == observerCount) {
                                sendContinueUserSwitchLocked(uss, oldUserId, newUserId);
                            }
                        }
                    }
                }
            };
            synchronized (mService) {
                uss.switching = true;
                mCurUserSwitchCallback = callback;
            }
            for (int i = 0; i < observerCount; i++) {
                try {
                    mUserSwitchObservers.getBroadcastItem(i).onUserSwitching(
                            newUserId, callback);
                } catch (RemoteException e) {
                }
            }
        } else {
            synchronized (mService) {
                sendContinueUserSwitchLocked(uss, oldUserId, newUserId);
            }
        }
        mUserSwitchObservers.finishBroadcast();
    }

    void sendContinueUserSwitchLocked(UserState uss, int oldUserId, int newUserId) {
        mCurUserSwitchCallback = null;
        mHandler.removeMessages(USER_SWITCH_TIMEOUT_MSG);
        mHandler.sendMessage(mHandler.obtainMessage(ActivityManagerService.CONTINUE_USER_SWITCH_MSG,
                oldUserId, newUserId, uss));
    }

    void continueUserSwitch(UserState uss, int oldUserId, int newUserId) {
        completeSwitchAndInitialize(uss, newUserId, false, true);
    }

    void onUserInitialized(UserState uss, boolean foreground, int oldUserId, int newUserId) {
        synchronized (mService) {
            if (foreground) {
                moveUserToForegroundLocked(uss, oldUserId, newUserId);
            }
        }
        completeSwitchAndInitialize(uss, newUserId, true, false);
    }

    void completeSwitchAndInitialize(UserState uss, int newUserId,
            boolean clearInitializing, boolean clearSwitching) {
        boolean unfrozen = false;
        synchronized (mService) {
            if (clearInitializing) {
                uss.initializing = false;
                getUserManager().makeInitialized(uss.mHandle.getIdentifier());
            }
            if (clearSwitching) {
                uss.switching = false;
            }
            if (!uss.switching && !uss.initializing) {
                mService.mWindowManager.stopFreezingScreen();
                unfrozen = true;
            }
        }
        if (unfrozen) {
            mHandler.removeMessages(REPORT_USER_SWITCH_COMPLETE_MSG);
            mHandler.sendMessage(mHandler.obtainMessage(REPORT_USER_SWITCH_COMPLETE_MSG,
                    newUserId, 0));
        }
        stopGuestUserIfBackground();
    }

    void moveUserToForegroundLocked(UserState uss, int oldUserId, int newUserId) {
        boolean homeInFront = mService.mStackSupervisor.switchUserLocked(newUserId, uss);
        if (homeInFront) {
            mService.startHomeActivityLocked(newUserId, "moveUserToForeground");
        } else {
            mService.mStackSupervisor.resumeTopActivitiesLocked();
        }
        EventLogTags.writeAmSwitchUser(newUserId);
        getUserManager().onUserForeground(newUserId);
        sendUserSwitchBroadcastsLocked(oldUserId, newUserId);
    }

    void sendUserSwitchBroadcastsLocked(int oldUserId, int newUserId) {
        long ident = Binder.clearCallingIdentity();
        try {
            Intent intent;
            if (oldUserId >= 0) {
                // Send USER_BACKGROUND broadcast to all profiles of the outgoing user
                List<UserInfo> profiles = getUserManager().getProfiles(oldUserId, false);
                int count = profiles.size();
                for (int i = 0; i < count; i++) {
                    int profileUserId = profiles.get(i).id;
                    intent = new Intent(Intent.ACTION_USER_BACKGROUND);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                            | Intent.FLAG_RECEIVER_FOREGROUND);
                    intent.putExtra(Intent.EXTRA_USER_HANDLE, profileUserId);
                    mService.broadcastIntentLocked(null, null, intent,
                            null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                            null, false, false, MY_PID, SYSTEM_UID, profileUserId);
                }
            }
            if (newUserId >= 0) {
                // Send USER_FOREGROUND broadcast to all profiles of the incoming user
                List<UserInfo> profiles = getUserManager().getProfiles(newUserId, false);
                int count = profiles.size();
                for (int i = 0; i < count; i++) {
                    int profileUserId = profiles.get(i).id;
                    intent = new Intent(Intent.ACTION_USER_FOREGROUND);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                            | Intent.FLAG_RECEIVER_FOREGROUND);
                    intent.putExtra(Intent.EXTRA_USER_HANDLE, profileUserId);
                    mService.broadcastIntentLocked(null, null, intent,
                            null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                            null, false, false, MY_PID, SYSTEM_UID, profileUserId);
                }
                intent = new Intent(Intent.ACTION_USER_SWITCHED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                        | Intent.FLAG_RECEIVER_FOREGROUND);
                intent.putExtra(Intent.EXTRA_USER_HANDLE, newUserId);
                mService.broadcastIntentLocked(null, null, intent,
                        null, null, 0, null, null,
                        new String[] {android.Manifest.permission.MANAGE_USERS},
                        AppOpsManager.OP_NONE, null, false, false, MY_PID, SYSTEM_UID,
                        UserHandle.USER_ALL);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }


    int handleIncomingUser(int callingPid, int callingUid, int userId, boolean allowAll,
            int allowMode, String name, String callerPackage) {
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (callingUserId == userId) {
            return userId;
        }

        // Note that we may be accessing mCurrentUserId outside of a lock...
        // shouldn't be a big deal, if this is being called outside
        // of a locked context there is intrinsically a race with
        // the value the caller will receive and someone else changing it.
        // We assume that USER_CURRENT_OR_SELF will use the current user; later
        // we will switch to the calling user if access to the current user fails.
        int targetUserId = unsafeConvertIncomingUserLocked(userId);

        if (callingUid != 0 && callingUid != SYSTEM_UID) {
            final boolean allow;
            if (mService.checkComponentPermission(INTERACT_ACROSS_USERS_FULL, callingPid,
                    callingUid, -1, true) == PackageManager.PERMISSION_GRANTED) {
                // If the caller has this permission, they always pass go.  And collect $200.
                allow = true;
            } else if (allowMode == ALLOW_FULL_ONLY) {
                // We require full access, sucks to be you.
                allow = false;
            } else if (mService.checkComponentPermission(INTERACT_ACROSS_USERS, callingPid,
                    callingUid, -1, true) != PackageManager.PERMISSION_GRANTED) {
                // If the caller does not have either permission, they are always doomed.
                allow = false;
            } else if (allowMode == ALLOW_NON_FULL) {
                // We are blanket allowing non-full access, you lucky caller!
                allow = true;
            } else if (allowMode == ALLOW_NON_FULL_IN_PROFILE) {
                // We may or may not allow this depending on whether the two users are
                // in the same profile.
                allow = isSameProfileGroup(callingUserId, targetUserId);
            } else {
                throw new IllegalArgumentException("Unknown mode: " + allowMode);
            }
            if (!allow) {
                if (userId == UserHandle.USER_CURRENT_OR_SELF) {
                    // In this case, they would like to just execute as their
                    // owner user instead of failing.
                    targetUserId = callingUserId;
                } else {
                    StringBuilder builder = new StringBuilder(128);
                    builder.append("Permission Denial: ");
                    builder.append(name);
                    if (callerPackage != null) {
                        builder.append(" from ");
                        builder.append(callerPackage);
                    }
                    builder.append(" asks to run as user ");
                    builder.append(userId);
                    builder.append(" but is calling from user ");
                    builder.append(UserHandle.getUserId(callingUid));
                    builder.append("; this requires ");
                    builder.append(INTERACT_ACROSS_USERS_FULL);
                    if (allowMode != ALLOW_FULL_ONLY) {
                        builder.append(" or ");
                        builder.append(INTERACT_ACROSS_USERS);
                    }
                    String msg = builder.toString();
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                }
            }
        }
        if (!allowAll && targetUserId < 0) {
            throw new IllegalArgumentException(
                    "Call does not support special user #" + targetUserId);
        }
        // Check shell permission
        if (callingUid == Process.SHELL_UID && targetUserId >= UserHandle.USER_SYSTEM) {
            if (hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, targetUserId)) {
                throw new SecurityException("Shell does not have permission to access user "
                        + targetUserId + "\n " + Debug.getCallers(3));
            }
        }
        return targetUserId;
    }

    int unsafeConvertIncomingUserLocked(int userId) {
        return (userId == UserHandle.USER_CURRENT || userId == UserHandle.USER_CURRENT_OR_SELF)
                ? getCurrentUserIdLocked(): userId;
    }

    void registerUserSwitchObserver(IUserSwitchObserver observer) {
        if (mService.checkCallingPermission(INTERACT_ACROSS_USERS_FULL)
                != PackageManager.PERMISSION_GRANTED) {
            final String msg = "Permission Denial: registerUserSwitchObserver() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + INTERACT_ACROSS_USERS_FULL;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        mUserSwitchObservers.register(observer);
    }

    void unregisterUserSwitchObserver(IUserSwitchObserver observer) {
        mUserSwitchObservers.unregister(observer);
    }

    UserState getStartedUserStateLocked(int userId) {
        return mStartedUsers.get(userId);
    }

    boolean hasStartedUserState(int userId) {
        return mStartedUsers.get(userId) != null;
    }

    private void updateStartedUserArrayLocked() {
        int num = 0;
        for (int i = 0; i < mStartedUsers.size(); i++) {
            UserState uss = mStartedUsers.valueAt(i);
            // This list does not include stopping users.
            if (uss.mState != UserState.STATE_STOPPING
                    && uss.mState != UserState.STATE_SHUTDOWN) {
                num++;
            }
        }
        mStartedUserArray = new int[num];
        num = 0;
        for (int i = 0; i < mStartedUsers.size(); i++) {
            UserState uss = mStartedUsers.valueAt(i);
            if (uss.mState != UserState.STATE_STOPPING
                    && uss.mState != UserState.STATE_SHUTDOWN) {
                mStartedUserArray[num] = mStartedUsers.keyAt(i);
                num++;
            }
        }
    }

    void sendBootCompletedLocked(IIntentReceiver resultTo) {
        for (int i = 0; i < mStartedUsers.size(); i++) {
            UserState uss = mStartedUsers.valueAt(i);
            if (uss.mState == UserState.STATE_BOOTING) {
                uss.mState = UserState.STATE_RUNNING;
                final int userId = mStartedUsers.keyAt(i);
                Intent intent = new Intent(Intent.ACTION_BOOT_COMPLETED, null);
                intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
                intent.addFlags(Intent.FLAG_RECEIVER_NO_ABORT);
                mService.broadcastIntentLocked(null, null, intent, null,
                        resultTo, 0, null, null,
                        new String[] {android.Manifest.permission.RECEIVE_BOOT_COMPLETED},
                        AppOpsManager.OP_NONE, null, true, false, MY_PID, SYSTEM_UID, userId);
            }
        }
    }

    /**
     * Refreshes the list of users related to the current user when either a
     * user switch happens or when a new related user is started in the
     * background.
     */
    void updateCurrentProfileIdsLocked() {
        final List<UserInfo> profiles = getUserManager().getProfiles(mCurrentUserId,
                false /* enabledOnly */);
        int[] currentProfileIds = new int[profiles.size()]; // profiles will not be null
        for (int i = 0; i < currentProfileIds.length; i++) {
            currentProfileIds[i] = profiles.get(i).id;
        }
        mCurrentProfileIds = currentProfileIds;

        synchronized (mUserProfileGroupIdsSelfLocked) {
            mUserProfileGroupIdsSelfLocked.clear();
            final List<UserInfo> users = getUserManager().getUsers(false);
            for (int i = 0; i < users.size(); i++) {
                UserInfo user = users.get(i);
                if (user.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID) {
                    mUserProfileGroupIdsSelfLocked.put(user.id, user.profileGroupId);
                }
            }
        }
    }

    int[] getStartedUserArrayLocked() {
        return mStartedUserArray;
    }

    boolean isUserRunningLocked(int userId, int flags) {
        UserState state = getStartedUserStateLocked(userId);
        if (state == null) {
            return false;
        }
        if ((flags & ActivityManager.FLAG_OR_STOPPED) != 0) {
            return true;
        }
        if ((flags & ActivityManager.FLAG_WITH_AMNESIA) != 0) {
            // If user is currently locked, we fall through to default "running"
            // behavior below
            if (state.unlocked) {
                return false;
            }
        }
        return state.mState != UserState.STATE_STOPPING
                && state.mState != UserState.STATE_SHUTDOWN;
    }

    UserInfo getCurrentUser() {
        if ((mService.checkCallingPermission(INTERACT_ACROSS_USERS)
                != PackageManager.PERMISSION_GRANTED) && (
                mService.checkCallingPermission(INTERACT_ACROSS_USERS_FULL)
                        != PackageManager.PERMISSION_GRANTED)) {
            String msg = "Permission Denial: getCurrentUser() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + INTERACT_ACROSS_USERS;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        synchronized (mService) {
            return getCurrentUserLocked();
        }
    }

    UserInfo getCurrentUserLocked() {
        int userId = mTargetUserId != UserHandle.USER_NULL ? mTargetUserId : mCurrentUserId;
        return getUserInfo(userId);
    }

    int getCurrentOrTargetUserIdLocked() {
        return mTargetUserId != UserHandle.USER_NULL ? mTargetUserId : mCurrentUserId;
    }

    int getCurrentUserIdLocked() {
        return mCurrentUserId;
    }

    int setTargetUserIdLocked(int targetUserId) {
        return mTargetUserId = targetUserId;
    }

    int[] getUsers() {
        UserManagerService ums = getUserManager();
        return ums != null ? ums.getUserIds() : new int[] { 0 };
    }

    UserInfo getUserInfo(int userId) {
        return getUserManager().getUserInfo(userId);
    }

    int[] getUserIds() {
        return getUserManager().getUserIds();
    }

    boolean exists(int userId) {
        return getUserManager().exists(userId);
    }

    boolean hasUserRestriction(String restriction, int userId) {
        return getUserManager().hasUserRestriction(restriction, userId);
    }

    Set<Integer> getProfileIds(int userId) {
        Set<Integer> userIds = new HashSet<>();
        final List<UserInfo> profiles = getUserManager().getProfiles(userId,
                false /* enabledOnly */);
        for (UserInfo user : profiles) {
            userIds.add(user.id);
        }
        return userIds;
    }

    boolean isSameProfileGroup(int callingUserId, int targetUserId) {
        synchronized (mUserProfileGroupIdsSelfLocked) {
            int callingProfile = mUserProfileGroupIdsSelfLocked.get(callingUserId,
                    UserInfo.NO_PROFILE_GROUP_ID);
            int targetProfile = mUserProfileGroupIdsSelfLocked.get(targetUserId,
                    UserInfo.NO_PROFILE_GROUP_ID);
            return callingProfile != UserInfo.NO_PROFILE_GROUP_ID
                    && callingProfile == targetProfile;
        }
    }

    boolean isCurrentProfileLocked(int userId) {
        return ArrayUtils.contains(mCurrentProfileIds, userId);
    }

    int[] getCurrentProfileIdsLocked() {
        return mCurrentProfileIds;
    }

    void dump(PrintWriter pw, boolean dumpAll) {
        pw.println("  mStartedUsers:");
        for (int i = 0; i < mStartedUsers.size(); i++) {
            UserState uss = mStartedUsers.valueAt(i);
            pw.print("    User #"); pw.print(uss.mHandle.getIdentifier());
            pw.print(": "); uss.dump("", pw);
        }
        pw.print("  mStartedUserArray: [");
        for (int i = 0; i < mStartedUserArray.length; i++) {
            if (i > 0) pw.print(", ");
            pw.print(mStartedUserArray[i]);
        }
        pw.println("]");
        pw.print("  mUserLru: [");
        for (int i = 0; i < mUserLru.size(); i++) {
            if (i > 0) pw.print(", ");
            pw.print(mUserLru.get(i));
        }
        pw.println("]");
        if (dumpAll) {
            pw.print("  mStartedUserArray: "); pw.println(Arrays.toString(mStartedUserArray));
        }
        synchronized (mUserProfileGroupIdsSelfLocked) {
            if (mUserProfileGroupIdsSelfLocked.size() > 0) {
                pw.println("  mUserProfileGroupIds:");
                for (int i=0; i<mUserProfileGroupIdsSelfLocked.size(); i++) {
                    pw.print("    User #");
                    pw.print(mUserProfileGroupIdsSelfLocked.keyAt(i));
                    pw.print(" -> profile #");
                    pw.println(mUserProfileGroupIdsSelfLocked.valueAt(i));
                }
            }
        }
    }
}
