/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.am;

import static com.android.server.am.ActivityManagerService.localLOGV;
import static com.android.server.am.ActivityManagerService.DEBUG_CONFIGURATION;
import static com.android.server.am.ActivityManagerService.DEBUG_SWITCH;
import static com.android.server.am.ActivityManagerService.TAG;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.IApplicationThread;
import android.app.IThumbnailReceiver;
import android.app.PendingIntent;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.IActivityManager.WaitResult;
import android.app.ResultInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.EventLog;
import android.util.Slog;

import com.android.internal.app.HeavyWeightSwitcherActivity;
import com.android.server.am.ActivityStack.ActivityState;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class ActivityStackSupervisor {
    static final boolean DEBUG_ADD_REMOVE = false;
    static final boolean DEBUG_APP = false;
    static final boolean DEBUG_SAVED_STATE = false;
    static final boolean DEBUG_STATES = false;

    public static final int HOME_STACK_ID = 0;

    final ActivityManagerService mService;
    final Context mContext;
    final Looper mLooper;

    /** Dismiss the keyguard after the next activity is displayed? */
    private boolean mDismissKeyguardOnNextActivity = false;

    /** Identifier counter for all ActivityStacks */
    private int mLastStackId = 0;

    /** Task identifier that activities are currently being started in.  Incremented each time a
     * new task is created. */
    private int mCurTaskId = 0;

    /** The current user */
    private int mCurrentUser;

    /** The stack containing the launcher app */
    private ActivityStack mHomeStack;

    /** The stack currently receiving input or launching the next activity */
    private ActivityStack mMainStack;

    /** All the non-launcher stacks */
    private ArrayList<ActivityStack> mStacks = new ArrayList<ActivityStack>();

    public ActivityStackSupervisor(ActivityManagerService service, Context context,
            Looper looper) {
        mService = service;
        mContext = context;
        mLooper = looper;
    }

    void init(int userId) {
        mHomeStack = new ActivityStack(mService, mContext, mLooper, HOME_STACK_ID, this, userId);
        setMainStack(mHomeStack);
        mStacks.add(mHomeStack);
    }

    void dismissKeyguard() {
        if (mDismissKeyguardOnNextActivity) {
            mDismissKeyguardOnNextActivity = false;
            mService.mWindowManager.dismissKeyguard();
        }
    }

    boolean isHomeStackMain() {
        return mHomeStack == mMainStack;
    }

    boolean isMainStack(ActivityStack stack) {
        return stack == mMainStack;
    }

    ActivityStack getMainStack() {
        return mMainStack;
    }

    void setMainStack(ActivityStack stack) {
        mMainStack = stack;
    }

    void setDismissKeyguard(boolean dismiss) {
        mDismissKeyguardOnNextActivity = dismiss;
    }

    TaskRecord anyTaskForIdLocked(int id) {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            ActivityStack stack = mStacks.get(stackNdx);
            TaskRecord task = stack.taskForIdLocked(id);
            if (task != null) {
                return task;
            }
        }
        return null;
    }

    int getNextTaskId() {
        do {
            mCurTaskId++;
            if (mCurTaskId <= 0) {
                mCurTaskId = 1;
            }
        } while (anyTaskForIdLocked(mCurTaskId) != null);
        return mCurTaskId;
    }

    boolean attachApplicationLocked(ProcessRecord app, boolean headless) throws Exception {
        boolean didSomething = false;
        final String processName = app.processName;
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            ActivityRecord hr = stack.topRunningActivityLocked(null);
            if (hr != null) {
                if (hr.app == null && app.uid == hr.info.applicationInfo.uid
                        && processName.equals(hr.processName)) {
                    try {
                        if (headless) {
                            Slog.e(TAG, "Starting activities not supported on headless device: "
                                    + hr);
                        } else if (realStartActivityLocked(hr, app, true, true)) {
                            didSomething = true;
                        }
                    } catch (Exception e) {
                        Slog.w(TAG, "Exception in new application when starting activity "
                              + hr.intent.getComponent().flattenToShortString(), e);
                        throw e;
                    }
                } else {
                    stack.ensureActivitiesVisibleLocked(hr, null, processName, 0);
                }
            }
        }
        return didSomething;
    }

    boolean allResumedActivitiesIdle() {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            if (mStacks.get(stackNdx).mResumedActivity == null ||
                    !mStacks.get(stackNdx).mResumedActivity.idle) {
                return false;
            }
        }
        return true;
    }

    ActivityRecord getTasksLocked(int maxNum, IThumbnailReceiver receiver,
            PendingThumbnailsRecord pending, List<RunningTaskInfo> list) {
        ActivityRecord r = null;
        final int numStacks = mStacks.size();
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            final ActivityRecord ar =
                    stack.getTasksLocked(maxNum - list.size(), receiver, pending, list);
            if (isMainStack(stack)) {
                r = ar;
            }
        }
        return r;
    }

    ActivityInfo resolveActivity(Intent intent, String resolvedType, int startFlags,
            String profileFile, ParcelFileDescriptor profileFd, int userId) {
        // Collect information about the target of the Intent.
        ActivityInfo aInfo;
        try {
            ResolveInfo rInfo =
                AppGlobals.getPackageManager().resolveIntent(
                        intent, resolvedType,
                        PackageManager.MATCH_DEFAULT_ONLY
                                    | ActivityManagerService.STOCK_PM_FLAGS, userId);
            aInfo = rInfo != null ? rInfo.activityInfo : null;
        } catch (RemoteException e) {
            aInfo = null;
        }

        if (aInfo != null) {
            // Store the found target back into the intent, because now that
            // we have it we never want to do this again.  For example, if the
            // user navigates back to this point in the history, we should
            // always restart the exact same activity.
            intent.setComponent(new ComponentName(
                    aInfo.applicationInfo.packageName, aInfo.name));

            // Don't debug things in the system process
            if ((startFlags&ActivityManager.START_FLAG_DEBUG) != 0) {
                if (!aInfo.processName.equals("system")) {
                    mService.setDebugApp(aInfo.processName, true, false);
                }
            }

            if ((startFlags&ActivityManager.START_FLAG_OPENGL_TRACES) != 0) {
                if (!aInfo.processName.equals("system")) {
                    mService.setOpenGlTraceApp(aInfo.applicationInfo, aInfo.processName);
                }
            }

            if (profileFile != null) {
                if (!aInfo.processName.equals("system")) {
                    mService.setProfileApp(aInfo.applicationInfo, aInfo.processName,
                            profileFile, profileFd,
                            (startFlags&ActivityManager.START_FLAG_AUTO_STOP_PROFILER) != 0);
                }
            }
        }
        return aInfo;
    }

    void startHomeActivity(Intent intent, ActivityInfo aInfo) {
        mHomeStack.startActivityLocked(null, intent, null, aInfo, null, null, 0, 0, 0, null, 0,
                null, false, null);
    }

    final int startActivityMayWait(IApplicationThread caller, int callingUid,
            String callingPackage, Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, int startFlags, String profileFile,
            ParcelFileDescriptor profileFd, WaitResult outResult, Configuration config,
            Bundle options, int userId) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        boolean componentSpecified = intent.getComponent() != null;

        // Don't modify the client's object!
        intent = new Intent(intent);

        // Collect information about the target of the Intent.
        ActivityInfo aInfo = resolveActivity(intent, resolvedType, startFlags,
                profileFile, profileFd, userId);

        synchronized (mService) {
            int callingPid;
            if (callingUid >= 0) {
                callingPid = -1;
            } else if (caller == null) {
                callingPid = Binder.getCallingPid();
                callingUid = Binder.getCallingUid();
            } else {
                callingPid = callingUid = -1;
            }

            mMainStack.mConfigWillChange = config != null
                    && mService.mConfiguration.diff(config) != 0;
            if (DEBUG_CONFIGURATION) Slog.v(TAG,
                    "Starting activity when config will change = " + mMainStack.mConfigWillChange);

            final long origId = Binder.clearCallingIdentity();

            if (aInfo != null &&
                    (aInfo.applicationInfo.flags&ApplicationInfo.FLAG_CANT_SAVE_STATE) != 0) {
                // This may be a heavy-weight process!  Check to see if we already
                // have another, different heavy-weight process running.
                if (aInfo.processName.equals(aInfo.applicationInfo.packageName)) {
                    if (mService.mHeavyWeightProcess != null &&
                            (mService.mHeavyWeightProcess.info.uid != aInfo.applicationInfo.uid ||
                            !mService.mHeavyWeightProcess.processName.equals(aInfo.processName))) {
                        int realCallingPid = callingPid;
                        int realCallingUid = callingUid;
                        if (caller != null) {
                            ProcessRecord callerApp = mService.getRecordForAppLocked(caller);
                            if (callerApp != null) {
                                realCallingPid = callerApp.pid;
                                realCallingUid = callerApp.info.uid;
                            } else {
                                Slog.w(TAG, "Unable to find app for caller " + caller
                                      + " (pid=" + realCallingPid + ") when starting: "
                                      + intent.toString());
                                ActivityOptions.abort(options);
                                return ActivityManager.START_PERMISSION_DENIED;
                            }
                        }

                        IIntentSender target = mService.getIntentSenderLocked(
                                ActivityManager.INTENT_SENDER_ACTIVITY, "android",
                                realCallingUid, userId, null, null, 0, new Intent[] { intent },
                                new String[] { resolvedType }, PendingIntent.FLAG_CANCEL_CURRENT
                                | PendingIntent.FLAG_ONE_SHOT, null);

                        Intent newIntent = new Intent();
                        if (requestCode >= 0) {
                            // Caller is requesting a result.
                            newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_HAS_RESULT, true);
                        }
                        newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_INTENT,
                                new IntentSender(target));
                        if (mService.mHeavyWeightProcess.activities.size() > 0) {
                            ActivityRecord hist = mService.mHeavyWeightProcess.activities.get(0);
                            newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_CUR_APP,
                                    hist.packageName);
                            newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_CUR_TASK,
                                    hist.task.taskId);
                        }
                        newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_NEW_APP,
                                aInfo.packageName);
                        newIntent.setFlags(intent.getFlags());
                        newIntent.setClassName("android",
                                HeavyWeightSwitcherActivity.class.getName());
                        intent = newIntent;
                        resolvedType = null;
                        caller = null;
                        callingUid = Binder.getCallingUid();
                        callingPid = Binder.getCallingPid();
                        componentSpecified = true;
                        try {
                            ResolveInfo rInfo =
                                AppGlobals.getPackageManager().resolveIntent(
                                        intent, null,
                                        PackageManager.MATCH_DEFAULT_ONLY
                                        | ActivityManagerService.STOCK_PM_FLAGS, userId);
                            aInfo = rInfo != null ? rInfo.activityInfo : null;
                            aInfo = mService.getActivityInfoForUser(aInfo, userId);
                        } catch (RemoteException e) {
                            aInfo = null;
                        }
                    }
                }
            }

            int res = mMainStack.startActivityLocked(caller, intent, resolvedType,
                    aInfo, resultTo, resultWho, requestCode, callingPid, callingUid,
                    callingPackage, startFlags, options, componentSpecified, null);

            if (mMainStack.mConfigWillChange) {
                // If the caller also wants to switch to a new configuration,
                // do so now.  This allows a clean switch, as we are waiting
                // for the current activity to pause (so we will not destroy
                // it), and have not yet started the next activity.
                mService.enforceCallingPermission(android.Manifest.permission.CHANGE_CONFIGURATION,
                        "updateConfiguration()");
                mMainStack.mConfigWillChange = false;
                if (DEBUG_CONFIGURATION) Slog.v(TAG,
                        "Updating to new configuration after starting activity.");
                mService.updateConfigurationLocked(config, null, false, false);
            }

            Binder.restoreCallingIdentity(origId);

            if (outResult != null) {
                outResult.result = res;
                if (res == ActivityManager.START_SUCCESS) {
                    mMainStack.mWaitingActivityLaunched.add(outResult);
                    do {
                        try {
                            mService.wait();
                        } catch (InterruptedException e) {
                        }
                    } while (!outResult.timeout && outResult.who == null);
                } else if (res == ActivityManager.START_TASK_TO_FRONT) {
                    ActivityRecord r = mMainStack.topRunningActivityLocked(null);
                    if (r.nowVisible) {
                        outResult.timeout = false;
                        outResult.who = new ComponentName(r.info.packageName, r.info.name);
                        outResult.totalTime = 0;
                        outResult.thisTime = 0;
                    } else {
                        outResult.thisTime = SystemClock.uptimeMillis();
                        mMainStack.mWaitingActivityVisible.add(outResult);
                        do {
                            try {
                                mService.wait();
                            } catch (InterruptedException e) {
                            }
                        } while (!outResult.timeout && outResult.who == null);
                    }
                }
            }

            return res;
        }
    }

    final int startActivities(IApplicationThread caller, int callingUid, String callingPackage,
            Intent[] intents, String[] resolvedTypes, IBinder resultTo,
            Bundle options, int userId) {
        if (intents == null) {
            throw new NullPointerException("intents is null");
        }
        if (resolvedTypes == null) {
            throw new NullPointerException("resolvedTypes is null");
        }
        if (intents.length != resolvedTypes.length) {
            throw new IllegalArgumentException("intents are length different than resolvedTypes");
        }

        ActivityRecord[] outActivity = new ActivityRecord[1];

        int callingPid;
        if (callingUid >= 0) {
            callingPid = -1;
        } else if (caller == null) {
            callingPid = Binder.getCallingPid();
            callingUid = Binder.getCallingUid();
        } else {
            callingPid = callingUid = -1;
        }
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mService) {

                for (int i=0; i<intents.length; i++) {
                    Intent intent = intents[i];
                    if (intent == null) {
                        continue;
                    }

                    // Refuse possible leaked file descriptors
                    if (intent != null && intent.hasFileDescriptors()) {
                        throw new IllegalArgumentException("File descriptors passed in Intent");
                    }

                    boolean componentSpecified = intent.getComponent() != null;

                    // Don't modify the client's object!
                    intent = new Intent(intent);

                    // Collect information about the target of the Intent.
                    ActivityInfo aInfo = resolveActivity(intent, resolvedTypes[i],
                            0, null, null, userId);
                    // TODO: New, check if this is correct
                    aInfo = mService.getActivityInfoForUser(aInfo, userId);

                    if (aInfo != null &&
                            (aInfo.applicationInfo.flags & ApplicationInfo.FLAG_CANT_SAVE_STATE)
                                    != 0) {
                        throw new IllegalArgumentException(
                                "FLAG_CANT_SAVE_STATE not supported here");
                    }

                    Bundle theseOptions;
                    if (options != null && i == intents.length-1) {
                        theseOptions = options;
                    } else {
                        theseOptions = null;
                    }
                    int res = mMainStack.startActivityLocked(caller, intent, resolvedTypes[i],
                            aInfo, resultTo, null, -1, callingPid, callingUid, callingPackage,
                            0, theseOptions, componentSpecified, outActivity);
                    if (res < 0) {
                        return res;
                    }

                    resultTo = outActivity[0] != null ? outActivity[0].appToken : null;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }

        return ActivityManager.START_SUCCESS;
    }

    final boolean realStartActivityLocked(ActivityRecord r,
            ProcessRecord app, boolean andResume, boolean checkConfig)
            throws RemoteException {

        r.startFreezingScreenLocked(app, 0);
        mService.mWindowManager.setAppVisibility(r.appToken, true);

        // schedule launch ticks to collect information about slow apps.
        r.startLaunchTickingLocked();

        // Have the window manager re-evaluate the orientation of
        // the screen based on the new activity order.  Note that
        // as a result of this, it can call back into the activity
        // manager with a new orientation.  We don't care about that,
        // because the activity is not currently running so we are
        // just restarting it anyway.
        if (checkConfig) {
            Configuration config = mService.mWindowManager.updateOrientationFromAppTokens(
                    mService.mConfiguration,
                    r.mayFreezeScreenLocked(app) ? r.appToken : null);
            mService.updateConfigurationLocked(config, r, false, false);
        }

        r.app = app;
        app.waitingToKill = null;
        r.launchCount++;
        r.lastLaunchTime = SystemClock.uptimeMillis();

        if (localLOGV) Slog.v(TAG, "Launching: " + r);

        int idx = app.activities.indexOf(r);
        if (idx < 0) {
            app.activities.add(r);
        }
        mService.updateLruProcessLocked(app, true);

        final ActivityStack stack = r.task.stack;
        try {
            if (app.thread == null) {
                throw new RemoteException();
            }
            List<ResultInfo> results = null;
            List<Intent> newIntents = null;
            if (andResume) {
                results = r.results;
                newIntents = r.newIntents;
            }
            if (DEBUG_SWITCH) Slog.v(TAG, "Launching: " + r
                    + " icicle=" + r.icicle
                    + " with results=" + results + " newIntents=" + newIntents
                    + " andResume=" + andResume);
            if (andResume) {
                EventLog.writeEvent(EventLogTags.AM_RESTART_ACTIVITY,
                        r.userId, System.identityHashCode(r),
                        r.task.taskId, r.shortComponentName);
            }
            if (r.isHomeActivity) {
                mService.mHomeProcess = app;
            }
            mService.ensurePackageDexOpt(r.intent.getComponent().getPackageName());
            r.sleeping = false;
            r.forceNewConfig = false;
            mService.showAskCompatModeDialogLocked(r);
            r.compat = mService.compatibilityInfoForPackageLocked(r.info.applicationInfo);
            String profileFile = null;
            ParcelFileDescriptor profileFd = null;
            boolean profileAutoStop = false;
            if (mService.mProfileApp != null && mService.mProfileApp.equals(app.processName)) {
                if (mService.mProfileProc == null || mService.mProfileProc == app) {
                    mService.mProfileProc = app;
                    profileFile = mService.mProfileFile;
                    profileFd = mService.mProfileFd;
                    profileAutoStop = mService.mAutoStopProfiler;
                }
            }
            app.hasShownUi = true;
            app.pendingUiClean = true;
            if (profileFd != null) {
                try {
                    profileFd = profileFd.dup();
                } catch (IOException e) {
                    if (profileFd != null) {
                        try {
                            profileFd.close();
                        } catch (IOException o) {
                        }
                        profileFd = null;
                    }
                }
            }
            app.thread.scheduleLaunchActivity(new Intent(r.intent), r.appToken,
                    System.identityHashCode(r), r.info,
                    new Configuration(mService.mConfiguration),
                    r.compat, r.icicle, results, newIntents, !andResume,
                    mService.isNextTransitionForward(), profileFile, profileFd,
                    profileAutoStop);

            if ((app.info.flags&ApplicationInfo.FLAG_CANT_SAVE_STATE) != 0) {
                // This may be a heavy-weight process!  Note that the package
                // manager will ensure that only activity can run in the main
                // process of the .apk, which is the only thing that will be
                // considered heavy-weight.
                if (app.processName.equals(app.info.packageName)) {
                    if (mService.mHeavyWeightProcess != null
                            && mService.mHeavyWeightProcess != app) {
                        Slog.w(TAG, "Starting new heavy weight process " + app
                                + " when already running "
                                + mService.mHeavyWeightProcess);
                    }
                    mService.mHeavyWeightProcess = app;
                    Message msg = mService.mHandler.obtainMessage(
                            ActivityManagerService.POST_HEAVY_NOTIFICATION_MSG);
                    msg.obj = r;
                    mService.mHandler.sendMessage(msg);
                }
            }

        } catch (RemoteException e) {
            if (r.launchFailed) {
                // This is the second time we failed -- finish activity
                // and give up.
                Slog.e(TAG, "Second failure launching "
                      + r.intent.getComponent().flattenToShortString()
                      + ", giving up", e);
                mService.appDiedLocked(app, app.pid, app.thread);
                stack.requestFinishActivityLocked(r.appToken, Activity.RESULT_CANCELED, null,
                        "2nd-crash", false);
                return false;
            }

            // This is the first time we failed -- restart process and
            // retry.
            app.activities.remove(r);
            throw e;
        }

        r.launchFailed = false;
        if (stack.updateLRUListLocked(r)) {
            Slog.w(TAG, "Activity " + r
                  + " being launched, but already in LRU list");
        }

        if (andResume) {
            // As part of the process of launching, ActivityThread also performs
            // a resume.
            stack.minimalResumeActivityLocked(r);
        } else {
            // This activity is not starting in the resumed state... which
            // should look like we asked it to pause+stop (but remain visible),
            // and it has done so and reported back the current icicle and
            // other state.
            if (DEBUG_STATES) Slog.v(TAG, "Moving to STOPPED: " + r
                    + " (starting in stopped state)");
            r.state = ActivityState.STOPPED;
            r.stopped = true;
        }

        // Launch the new version setup screen if needed.  We do this -after-
        // launching the initial activity (that is, home), so that it can have
        // a chance to initialize itself while in the background, making the
        // switch back to it faster and look better.
        if (isMainStack(stack)) {
            mService.startSetupActivityLocked();
        }

        return true;
    }

    void startSpecificActivityLocked(ActivityRecord r,
            boolean andResume, boolean checkConfig) {
        // Is this activity's application already running?
        ProcessRecord app = mService.getProcessRecordLocked(r.processName,
                r.info.applicationInfo.uid);

        r.task.stack.setLaunchTime(r);

        if (app != null && app.thread != null) {
            try {
                app.addPackage(r.info.packageName);
                realStartActivityLocked(r, app, andResume, checkConfig);
                return;
            } catch (RemoteException e) {
                Slog.w(TAG, "Exception when starting activity "
                        + r.intent.getComponent().flattenToShortString(), e);
            }

            // If a dead object exception was thrown -- fall through to
            // restart the application.
        }

        mService.startProcessLocked(r.processName, r.info.applicationInfo, true, 0,
                "activity", r.intent.getComponent(), false, false);
    }

    void handleAppDiedLocked(ProcessRecord app, boolean restarting) {
        // Just in case.
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            mStacks.get(stackNdx).handleAppDiedLocked(app, restarting);
        }
    }

    void closeSystemDialogsLocked() {
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            stack.closeSystemDialogsLocked();
        }
    }

    /**
     * @return true if some activity was finished (or would have finished if doit were true).
     */
    boolean forceStopPackageLocked(String name, boolean doit, boolean evenPersistent, int userId) {
        boolean didSomething = false;
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            if (stack.forceStopPackageLocked(name, doit, evenPersistent, userId)) {
                didSomething = true;
            }
        }
        return didSomething;
    }

    void resumeTopActivityLocked() {
        final int start, end;
        if (isHomeStackMain()) {
            start = 0;
            end = 1;
        } else {
            start = 1;
            end = mStacks.size();
        }
        for (int stackNdx = start; stackNdx < end; ++stackNdx) {
            mStacks.get(stackNdx).resumeTopActivityLocked(null);
        }
    }

    void finishTopRunningActivityLocked(ProcessRecord app) {
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            stack.finishTopRunningActivityLocked(app);
        }
    }

    void scheduleIdleLocked() {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            mStacks.get(stackNdx).scheduleIdleLocked();
        }
    }

    void findTaskToMoveToFrontLocked(int taskId, int flags, Bundle options) {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            if (mStacks.get(stackNdx).findTaskToMoveToFrontLocked(taskId, flags, options)) {
                return;
            }
        }
    }

    private ActivityStack getStack(int stackId) {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            if (stack.getStackId() == stackId) {
                return stack;
            }
        }
        return null;
    }

    int createStack(int relativeStackId, int position, float weight) {
        synchronized (this) {
            while (true) {
                if (++mLastStackId <= HOME_STACK_ID) {
                    mLastStackId = HOME_STACK_ID + 1;
                }
                if (getStack(mLastStackId) == null) {
                    break;
                }
            }
            mStacks.add(new ActivityStack(mService, mContext, mLooper, mLastStackId, this,
                    mCurrentUser));
            return mLastStackId;
        }
    }

    void moveTaskToStack(int taskId, int stackId, boolean toTop) {
        final ActivityStack stack = getStack(stackId);
        if (stack == null) {
            Slog.w(TAG, "moveTaskToStack: no stack for id=" + stackId);
            return;
        }
        stack.moveTask(taskId, toTop);
    }

    void goingToSleepLocked() {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            mStacks.get(stackNdx).stopIfSleepingLocked();
        }
    }

    boolean shutdownLocked(int timeout) {
        boolean timedout = false;
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            if (stack.mResumedActivity != null) {
                stack.stopIfSleepingLocked();
                final long endTime = System.currentTimeMillis() + timeout;
                while (stack.mResumedActivity != null || stack.mPausingActivity != null) {
                    long delay = endTime - System.currentTimeMillis();
                    if (delay <= 0) {
                        Slog.w(TAG, "Activity manager shutdown timed out");
                        timedout = true;
                        break;
                    }
                    try {
                        mService.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
        }
        return timedout;
    }

    void comeOutOfSleepIfNeededLocked() {
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            stack.awakeFromSleepingLocked();
            stack.resumeTopActivityLocked(null);
        }
    }

    void handleAppCrashLocked(ProcessRecord app) {
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            stack.handleAppCrashLocked(app);
        }
    }

    boolean updateConfigurationLocked(int changes, ActivityRecord starting) {
        boolean kept = true;
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            if (changes != 0 && starting == null) {
                // If the configuration changed, and the caller is not already
                // in the process of starting an activity, then find the top
                // activity to check if its configuration needs to change.
                starting = stack.topRunningActivityLocked(null);
            }

            if (starting != null) {
                if (!stack.ensureActivityConfigurationLocked(starting, changes)) {
                    kept = false;
                }
                // And we need to make sure at this point that all other activities
                // are made visible with the correct configuration.
                stack.ensureActivitiesVisibleLocked(starting, changes);
            }
        }
        return kept;
    }

    void scheduleDestroyAllActivities(ProcessRecord app, String reason) {
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            stack.scheduleDestroyActivities(app, false, reason);
        }
    }

    boolean switchUserLocked(int userId, UserStartedState uss) {
        mCurrentUser = userId;
        boolean haveActivities = false;
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            haveActivities |= stack.switchUserLocked(userId, uss);
        }
        return haveActivities;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mDismissKeyguardOnNextActivity:");
                pw.println(mDismissKeyguardOnNextActivity);
    }

    ArrayList<ActivityRecord> getDumpActivitiesLocked(String name) {
        return mMainStack.getDumpActivitiesLocked(name);
    }

    boolean dumpActivitiesLocked(FileDescriptor fd, PrintWriter pw, boolean dumpAll,
            boolean dumpClient, String dumpPackage) {
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            pw.print("  Stack #"); pw.print(mStacks.indexOf(stack)); pw.println(":");
            stack.dumpActivitiesLocked(fd, pw, dumpAll, dumpClient, dumpPackage);
            pw.println(" ");
            pw.println("  Running activities (most recent first):");
            dumpHistoryList(fd, pw, stack.mLRUActivities, "  ", "Run", false, !dumpAll, false,
                    dumpPackage);
            if (stack.mWaitingVisibleActivities.size() > 0) {
                pw.println(" ");
                pw.println("  Activities waiting for another to become visible:");
                dumpHistoryList(fd, pw, stack.mWaitingVisibleActivities, "  ", "Wait", false,
                        !dumpAll, false, dumpPackage);
            }
            if (stack.mStoppingActivities.size() > 0) {
                pw.println(" ");
                pw.println("  Activities waiting to stop:");
                dumpHistoryList(fd, pw, stack.mStoppingActivities, "  ", "Stop", false,
                        !dumpAll, false, dumpPackage);
            }
            if (stack.mGoingToSleepActivities.size() > 0) {
                pw.println(" ");
                pw.println("  Activities waiting to sleep:");
                dumpHistoryList(fd, pw, stack.mGoingToSleepActivities, "  ", "Sleep", false,
                        !dumpAll, false, dumpPackage);
            }
            if (stack.mFinishingActivities.size() > 0) {
                pw.println(" ");
                pw.println("  Activities waiting to finish:");
                dumpHistoryList(fd, pw, stack.mFinishingActivities, "  ", "Fin", false,
                        !dumpAll, false, dumpPackage);
            }
        }

        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            pw.print("  Stack #"); pw.println(mStacks.indexOf(stack));
            if (stack.mPausingActivity != null) {
                pw.println("  mPausingActivity: " + stack.mPausingActivity);
            }
            pw.println("  mResumedActivity: " + stack.mResumedActivity);
            if (dumpAll) {
                pw.println("  mLastPausedActivity: " + stack.mLastPausedActivity);
                pw.println("  mSleepTimeout: " + stack.mSleepTimeout);
            }
        }

        if (dumpAll) {
            pw.println(" ");
            pw.println("  mCurTaskId: " + mCurTaskId);
        }
        return true;
    }

    static final void dumpHistoryList(FileDescriptor fd, PrintWriter pw, List<ActivityRecord> list,
            String prefix, String label, boolean complete, boolean brief, boolean client,
            String dumpPackage) {
        TaskRecord lastTask = null;
        boolean needNL = false;
        final String innerPrefix = prefix + "      ";
        final String[] args = new String[0];
        for (int i=list.size()-1; i>=0; i--) {
            final ActivityRecord r = list.get(i);
            if (dumpPackage != null && !dumpPackage.equals(r.packageName)) {
                continue;
            }
            final boolean full = !brief && (complete || !r.isInHistory());
            if (needNL) {
                pw.println(" ");
                needNL = false;
            }
            if (lastTask != r.task) {
                lastTask = r.task;
                pw.print(prefix);
                pw.print(full ? "* " : "  ");
                pw.println(lastTask);
                if (full) {
                    lastTask.dump(pw, prefix + "  ");
                } else if (complete) {
                    // Complete + brief == give a summary.  Isn't that obvious?!?
                    if (lastTask.intent != null) {
                        pw.print(prefix); pw.print("  ");
                                pw.println(lastTask.intent.toInsecureStringWithClip());
                    }
                }
            }
            pw.print(prefix); pw.print(full ? "  * " : "    "); pw.print(label);
            pw.print(" #"); pw.print(i); pw.print(": ");
            pw.println(r);
            if (full) {
                r.dump(pw, innerPrefix);
            } else if (complete) {
                // Complete + brief == give a summary.  Isn't that obvious?!?
                pw.print(innerPrefix); pw.println(r.intent.toInsecureString());
                if (r.app != null) {
                    pw.print(innerPrefix); pw.println(r.app);
                }
            }
            if (client && r.app != null && r.app.thread != null) {
                // flush anything that is already in the PrintWriter since the thread is going
                // to write to the file descriptor directly
                pw.flush();
                try {
                    TransferPipe tp = new TransferPipe();
                    try {
                        r.app.thread.dumpActivity(tp.getWriteFd().getFileDescriptor(),
                                r.appToken, innerPrefix, args);
                        // Short timeout, since blocking here can
                        // deadlock with the application.
                        tp.go(fd, 2000);
                    } finally {
                        tp.kill();
                    }
                } catch (IOException e) {
                    pw.println(innerPrefix + "Failure while dumping the activity: " + e);
                } catch (RemoteException e) {
                    pw.println(innerPrefix + "Got a RemoteException while dumping the activity");
                }
                needNL = true;
            }
        }
    }
}
