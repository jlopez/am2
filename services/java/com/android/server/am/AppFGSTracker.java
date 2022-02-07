/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.Manifest.permission.ACCESS_BACKGROUND_LOCATION;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE;
import static android.content.pm.ServiceInfo.NUM_OF_FOREGROUND_SERVICE_TYPES;
import static android.content.pm.ServiceInfo.foregroundServiceTypeToLabel;
import static android.os.PowerExemptionManager.REASON_DENIED;

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.AppRestrictionController.DEVICE_CONFIG_SUBNAMESPACE_PREFIX;
import static com.android.server.am.BaseAppStateTracker.ONE_DAY;
import static com.android.server.am.BaseAppStateTracker.ONE_HOUR;

import android.annotation.NonNull;
import android.app.ActivityManagerInternal.ForegroundServiceStateListener;
import android.app.IProcessObserver;
import android.content.Context;
import android.content.pm.ServiceInfo.ForegroundServiceType;
import android.os.Handler;
import android.os.Message;
import android.os.PowerExemptionManager.ReasonCode;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.am.AppFGSTracker.AppFGSPolicy;
import com.android.server.am.AppFGSTracker.PackageDurations;
import com.android.server.am.BaseAppStateEventsTracker.BaseAppStateEventsPolicy;
import com.android.server.am.BaseAppStateTimeEvents.BaseTimeEvent;
import com.android.server.am.BaseAppStateTracker.Injector;
import com.android.server.notification.NotificationManagerInternal;

import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.LinkedList;

/**
 * The tracker for monitoring abusive (long-running) FGS.
 */
final class AppFGSTracker extends BaseAppStateDurationsTracker<AppFGSPolicy, PackageDurations>
        implements ForegroundServiceStateListener {
    static final String TAG = TAG_WITH_CLASS_NAME ? "AppFGSTracker" : TAG_AM;

    static final boolean DEBUG_BACKGROUND_FGS_TRACKER = false;

    private final MyHandler mHandler;

    @GuardedBy("mLock")
    private final UidProcessMap<ArraySet<Integer>> mFGSNotificationIDs = new UidProcessMap<>();

    // Unlocked since it's only accessed in single thread.
    private final ArrayMap<PackageDurations, Long> mTmpPkgDurations = new ArrayMap<>();

    final IProcessObserver.Stub mProcessObserver = new IProcessObserver.Stub() {
        @Override
        public void onForegroundActivitiesChanged(int pid, int uid, boolean fg) {
        }

        @Override
        public void onForegroundServicesChanged(int pid, int uid, int serviceTypes) {
            final String packageName = mAppRestrictionController.getPackageName(pid);
            if (packageName != null) {
                mHandler.obtainMessage(MyHandler.MSG_FOREGROUND_SERVICES_CHANGED,
                        uid, serviceTypes, packageName).sendToTarget();
            }
        }

        @Override
        public void onProcessDied(int pid, int uid) {
        }
    };

    @Override
    public void onForegroundServiceStateChanged(String packageName,
            int uid, int pid, boolean started) {
        mHandler.obtainMessage(started ? MyHandler.MSG_FOREGROUND_SERVICES_STARTED
                : MyHandler.MSG_FOREGROUND_SERVICES_STOPPED, pid, uid, packageName).sendToTarget();
    }

    @Override
    public void onForegroundServiceNotificationUpdated(String packageName, int uid,
            int foregroundId) {
        mHandler.obtainMessage(MyHandler.MSG_FOREGROUND_SERVICES_NOTIFICATION_UPDATED,
                uid, foregroundId, packageName).sendToTarget();
    }

    private static class MyHandler extends Handler {
        static final int MSG_FOREGROUND_SERVICES_STARTED = 0;
        static final int MSG_FOREGROUND_SERVICES_STOPPED = 1;
        static final int MSG_FOREGROUND_SERVICES_CHANGED = 2;
        static final int MSG_FOREGROUND_SERVICES_NOTIFICATION_UPDATED = 3;
        static final int MSG_CHECK_LONG_RUNNING_FGS = 4;

        private final AppFGSTracker mTracker;

        MyHandler(AppFGSTracker tracker) {
            super(tracker.mBgHandler.getLooper());
            mTracker = tracker;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_FOREGROUND_SERVICES_STARTED:
                    mTracker.handleForegroundServicesChanged(
                            (String) msg.obj, msg.arg1, msg.arg2, true);
                    break;
                case MSG_FOREGROUND_SERVICES_STOPPED:
                    mTracker.handleForegroundServicesChanged(
                            (String) msg.obj, msg.arg1, msg.arg2, false);
                    break;
                case MSG_FOREGROUND_SERVICES_CHANGED:
                    mTracker.handleForegroundServicesChanged(
                            (String) msg.obj, msg.arg1, msg.arg2);
                    break;
                case MSG_FOREGROUND_SERVICES_NOTIFICATION_UPDATED:
                    mTracker.handleForegroundServiceNotificationUpdated(
                            (String) msg.obj, msg.arg1, msg.arg2);
                    break;
                case MSG_CHECK_LONG_RUNNING_FGS:
                    mTracker.checkLongRunningFgs();
                    break;
            }
        }
    }

    AppFGSTracker(Context context, AppRestrictionController controller) {
        this(context, controller, null, null);
    }

    AppFGSTracker(Context context, AppRestrictionController controller,
            Constructor<? extends Injector<AppFGSPolicy>> injector, Object outerContext) {
        super(context, controller, injector, outerContext);
        mHandler = new MyHandler(this);
        mInjector.setPolicy(new AppFGSPolicy(mInjector, this));
    }

    @Override
    void onSystemReady() {
        super.onSystemReady();
        mInjector.getActivityManagerInternal().addForegroundServiceStateListener(this);
        mInjector.getActivityManagerInternal().registerProcessObserver(mProcessObserver);
    }

    @VisibleForTesting
    @Override
    void reset() {
        mHandler.removeMessages(MyHandler.MSG_CHECK_LONG_RUNNING_FGS);
        super.reset();
    }

    @Override
    public PackageDurations createAppStateEvents(int uid, String packageName) {
        return new PackageDurations(uid, packageName, mInjector.getPolicy(), this);
    }

    @Override
    public PackageDurations createAppStateEvents(PackageDurations other) {
        return new PackageDurations(other);
    }

    private void handleForegroundServicesChanged(String packageName, int pid, int uid,
            boolean started) {
        if (!mInjector.getPolicy().isEnabled()) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        boolean longRunningFGSGone = false;
        final int exemptReason = mInjector.getPolicy().shouldExemptUid(uid);
        if (DEBUG_BACKGROUND_FGS_TRACKER) {
            Slog.i(TAG, (started ? "Starting" : "Stopping") + " fgs in "
                    + packageName + "/" + UserHandle.formatUid(uid)
                    + " exemptReason=" + exemptReason);
        }
        synchronized (mLock) {
            PackageDurations pkg = mPkgEvents.get(uid, packageName);
            if (pkg == null) {
                pkg = createAppStateEvents(uid, packageName);
                mPkgEvents.put(uid, packageName, pkg);
            }
            final boolean wasLongRunning = pkg.isLongRunning();
            pkg.addEvent(started, now);
            longRunningFGSGone = wasLongRunning && !pkg.hasForegroundServices();
            if (longRunningFGSGone) {
                pkg.setIsLongRunning(false);
            }
            pkg.mExemptReason = exemptReason;
            // Reschedule the checks.
            scheduleDurationCheckLocked(now);
        }
        if (longRunningFGSGone) {
            // The long-running FGS is gone, cancel the notification.
            mInjector.getPolicy().onLongRunningFgsGone(packageName, uid);
        }
    }

    private void handleForegroundServiceNotificationUpdated(String packageName, int uid,
            int notificationId) {
        synchronized (mLock) {
            if (notificationId > 0) {
                ArraySet<Integer> notificationIDs = mFGSNotificationIDs.get(uid, packageName);
                if (notificationIDs == null) {
                    notificationIDs = new ArraySet<>();
                    mFGSNotificationIDs.put(uid, packageName, notificationIDs);
                }
                notificationIDs.add(notificationId);
            } else if (notificationId < 0) {
                final ArraySet<Integer> notificationIDs = mFGSNotificationIDs.get(uid, packageName);
                if (notificationIDs != null) {
                    notificationIDs.remove(-notificationId);
                    if (notificationIDs.isEmpty()) {
                        mFGSNotificationIDs.remove(uid, packageName);
                    }
                }
            }
        }
    }

    @GuardedBy("mLock")
    private boolean hasForegroundServiceNotificationsLocked(String packageName, int uid) {
        final ArraySet<Integer> notificationIDs = mFGSNotificationIDs.get(uid, packageName);
        if (notificationIDs == null || notificationIDs.isEmpty()) {
            return false;
        }
        final NotificationManagerInternal nm = mInjector.getNotificationManagerInternal();
        final int userId = UserHandle.getUserId(uid);
        for (int i = notificationIDs.size() - 1; i >= 0; i--) {
            if (nm.isNotificationShown(packageName, null, notificationIDs.valueAt(i), userId)) {
                return true;
            }
        }
        return false;
    }

    @GuardedBy("mLock")
    private void scheduleDurationCheckLocked(long now) {
        // Look for the active FGS with longest running time till now.
        final SparseArray<ArrayMap<String, PackageDurations>> map = mPkgEvents.getMap();
        long longest = -1;
        for (int i = map.size() - 1; i >= 0; i--) {
            final ArrayMap<String, PackageDurations> val = map.valueAt(i);
            for (int j = val.size() - 1; j >= 0; j--) {
                final PackageDurations pkg = val.valueAt(j);
                if (!pkg.hasForegroundServices() || pkg.isLongRunning()) {
                    // No FGS or it's a known long-running FGS, ignore it.
                    continue;
                }
                longest = Math.max(getTotalDurations(pkg, now), longest);
            }
        }
        // Schedule a check in the future.
        mHandler.removeMessages(MyHandler.MSG_CHECK_LONG_RUNNING_FGS);
        if (longest >= 0) {
            // We'd add the "service start foreground timeout", as the apps are allowed
            // to call startForeground() within that timeout after the FGS being started.
            final long future = mInjector.getServiceStartForegroundTimeout()
                    + Math.max(0, mInjector.getPolicy().getFgsLongRunningThreshold() - longest);
            if (DEBUG_BACKGROUND_FGS_TRACKER) {
                Slog.i(TAG, "Scheduling a FGS duration check at "
                        + TimeUtils.formatDuration(future));
            }
            mHandler.sendEmptyMessageDelayed(MyHandler.MSG_CHECK_LONG_RUNNING_FGS, future);
        } else if (DEBUG_BACKGROUND_FGS_TRACKER) {
            Slog.i(TAG, "Not scheduling FGS duration check");
        }
    }

    private void checkLongRunningFgs() {
        final AppFGSPolicy policy = mInjector.getPolicy();
        final ArrayMap<PackageDurations, Long> pkgWithLongFgs = mTmpPkgDurations;
        final long now = SystemClock.elapsedRealtime();
        final long threshold = policy.getFgsLongRunningThreshold();
        final long windowSize = policy.getFgsLongRunningWindowSize();
        final long trimTo = Math.max(0, now - windowSize);

        synchronized (mLock) {
            final SparseArray<ArrayMap<String, PackageDurations>> map = mPkgEvents.getMap();
            for (int i = map.size() - 1; i >= 0; i--) {
                final ArrayMap<String, PackageDurations> val = map.valueAt(i);
                for (int j = val.size() - 1; j >= 0; j--) {
                    final PackageDurations pkg = val.valueAt(j);
                    if (pkg.hasForegroundServices() && !pkg.isLongRunning()) {
                        final long totalDuration = getTotalDurations(pkg, now);
                        if (totalDuration >= threshold) {
                            pkgWithLongFgs.put(pkg, totalDuration);
                            pkg.setIsLongRunning(true);
                            if (DEBUG_BACKGROUND_FGS_TRACKER) {
                                Slog.i(TAG, pkg.mPackageName
                                        + "/" + UserHandle.formatUid(pkg.mUid)
                                        + " has FGS running for "
                                        + TimeUtils.formatDuration(totalDuration)
                                        + " over " + TimeUtils.formatDuration(windowSize));
                            }
                        }
                    }
                }
            }
            // Trim the duration list, we don't need to keep track of all old records.
            trim(trimTo);
        }

        final int size = pkgWithLongFgs.size();
        if (size > 0) {
            // Sort it by the durations.
            final Integer[] indices = new Integer[size];
            for (int i = 0; i < size; i++) {
                indices[i] = i;
            }
            Arrays.sort(indices, (a, b) -> Long.compare(
                    pkgWithLongFgs.valueAt(a), pkgWithLongFgs.valueAt(b)));
            // Notify it in the order of from longest to shortest durations.
            for (int i = size - 1; i >= 0; i--) {
                final PackageDurations pkg = pkgWithLongFgs.keyAt(indices[i]);
                policy.onLongRunningFgs(pkg.mPackageName, pkg.mUid, pkg.mExemptReason);
            }
            pkgWithLongFgs.clear();
        }

        synchronized (mLock) {
            scheduleDurationCheckLocked(now);
        }
    }

    private void handleForegroundServicesChanged(String packageName, int uid, int serviceTypes) {
        if (!mInjector.getPolicy().isEnabled()) {
            return;
        }
        final int exemptReason = mInjector.getPolicy().shouldExemptUid(uid);
        final long now = SystemClock.elapsedRealtime();
        if (DEBUG_BACKGROUND_FGS_TRACKER) {
            Slog.i(TAG, "Updating fgs type for " + packageName + "/" + UserHandle.formatUid(uid)
                    + " to " + Integer.toHexString(serviceTypes)
                    + " exemptReason=" + exemptReason);
        }
        synchronized (mLock) {
            PackageDurations pkg = mPkgEvents.get(uid, packageName);
            if (pkg == null) {
                pkg = new PackageDurations(uid, packageName, mInjector.getPolicy(), this);
                mPkgEvents.put(uid, packageName, pkg);
            }
            pkg.setForegroundServiceType(serviceTypes, now);
            pkg.mExemptReason = exemptReason;
        }
    }

    private void onBgFgsMonitorEnabled(boolean enabled) {
        if (enabled) {
            synchronized (mLock) {
                scheduleDurationCheckLocked(SystemClock.elapsedRealtime());
            }
        } else {
            mHandler.removeMessages(MyHandler.MSG_CHECK_LONG_RUNNING_FGS);
            synchronized (mLock) {
                mPkgEvents.clear();
            }
        }
    }

    private void onBgFgsLongRunningThresholdChanged() {
        synchronized (mLock) {
            if (mInjector.getPolicy().isEnabled()) {
                scheduleDurationCheckLocked(SystemClock.elapsedRealtime());
            }
        }
    }

    static int foregroundServiceTypeToIndex(@ForegroundServiceType int serviceType) {
        return serviceType == FOREGROUND_SERVICE_TYPE_NONE ? 0
                : Integer.numberOfTrailingZeros(serviceType) + 1;
    }

    static @ForegroundServiceType int indexToForegroundServiceType(int index) {
        return index == PackageDurations.DEFAULT_INDEX
                ? FOREGROUND_SERVICE_TYPE_NONE : (1 << (index - 1));
    }

    long getTotalDurations(PackageDurations pkg, long now) {
        return getTotalDurations(pkg.mPackageName, pkg.mUid, now,
                foregroundServiceTypeToIndex(FOREGROUND_SERVICE_TYPE_NONE));
    }

    boolean hasForegroundServices(String packageName, int uid) {
        synchronized (mLock) {
            final PackageDurations pkg = mPkgEvents.get(uid, packageName);
            return pkg != null && pkg.hasForegroundServices();
        }
    }

    boolean hasForegroundServices(int uid) {
        synchronized (mLock) {
            final SparseArray<ArrayMap<String, PackageDurations>> map = mPkgEvents.getMap();
            final ArrayMap<String, PackageDurations> pkgs = map.get(uid);
            if (pkgs != null) {
                for (int i = pkgs.size() - 1; i >= 0; i--) {
                    final PackageDurations pkg = pkgs.valueAt(i);
                    if (pkg.hasForegroundServices()) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    boolean hasForegroundServiceNotifications(String packageName, int uid) {
        synchronized (mLock) {
            return hasForegroundServiceNotificationsLocked(packageName, uid);
        }
    }

    boolean hasForegroundServiceNotifications(int uid) {
        synchronized (mLock) {
            final SparseArray<ArrayMap<String, ArraySet<Integer>>> map =
                    mFGSNotificationIDs.getMap();
            final ArrayMap<String, ArraySet<Integer>> pkgs = map.get(uid);
            if (pkgs != null) {
                for (int i = pkgs.size() - 1; i >= 0; i--) {
                    if (hasForegroundServiceNotificationsLocked(pkgs.keyAt(i), uid)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.println("APP FOREGROUND SERVICE TRACKER:");
        super.dump(pw, "  " + prefix);
    }

    @Override
    void dumpOthers(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.println("APPS WITH ACTIVE FOREGROUND SERVICES:");
        prefix = "  " + prefix;
        synchronized (mLock) {
            final SparseArray<ArrayMap<String, ArraySet<Integer>>> map =
                    mFGSNotificationIDs.getMap();
            if (map.size() == 0) {
                pw.print(prefix);
                pw.println("(none)");
            }
            for (int i = 0, size = map.size(); i < size; i++) {
                final int uid = map.keyAt(i);
                final String uidString = UserHandle.formatUid(uid);
                final ArrayMap<String, ArraySet<Integer>> pkgs = map.valueAt(i);
                for (int j = 0, numOfPkgs = pkgs.size(); j < numOfPkgs; j++) {
                    final String pkgName = pkgs.keyAt(j);
                    pw.print(prefix);
                    pw.print(pkgName);
                    pw.print('/');
                    pw.print(uidString);
                    pw.print(" notification=");
                    pw.println(hasForegroundServiceNotificationsLocked(pkgName, uid));
                }
            }
        }
    }

    /**
     * Tracks the durations with active FGS for a given package.
     */
    static class PackageDurations extends BaseAppStateDurations<BaseTimeEvent> {
        private final AppFGSTracker mTracker;

        /**
         * Whether or not this package is considered as having long-running FGS.
         */
        private boolean mIsLongRunning;

        /**
         * The current foreground service types, should be a combination of the values in
         * {@link android.content.pm.ServiceInfo.ForegroundServiceType}.
         */
        private int mForegroundServiceTypes;

        /**
         * The index to the duration list array, where it holds the overall FGS stats of this
         * package.
         */
        static final int DEFAULT_INDEX = foregroundServiceTypeToIndex(FOREGROUND_SERVICE_TYPE_NONE);

        PackageDurations(int uid, String packageName,
                MaxTrackingDurationConfig maxTrackingDurationConfig, AppFGSTracker tracker) {
            super(uid, packageName, NUM_OF_FOREGROUND_SERVICE_TYPES + 1, TAG,
                    maxTrackingDurationConfig);
            mEvents[DEFAULT_INDEX] = new LinkedList<>();
            mTracker = tracker;
        }

        PackageDurations(@NonNull PackageDurations other) {
            super(other);
            mIsLongRunning = other.mIsLongRunning;
            mForegroundServiceTypes = other.mForegroundServiceTypes;
            mTracker = other.mTracker;
        }

        /**
         * Add a foreground service start/stop event.
         */
        void addEvent(boolean startFgs, long now) {
            addEvent(startFgs, new BaseTimeEvent(now), DEFAULT_INDEX);
            if (!startFgs && !hasForegroundServices()) {
                mIsLongRunning = false;
            }

            if (!startFgs && mForegroundServiceTypes != FOREGROUND_SERVICE_TYPE_NONE) {
                // Save the stop time per service type.
                for (int i = 1; i < mEvents.length; i++) {
                    if (mEvents[i] == null) {
                        continue;
                    }
                    if (isActive(i)) {
                        mEvents[i].add(new BaseTimeEvent(now));
                        notifyListenersOnEventIfNecessary(false, now,
                                indexToForegroundServiceType(i));
                    }
                }
                mForegroundServiceTypes = FOREGROUND_SERVICE_TYPE_NONE;
            }
        }

        /**
         * Called on the service type changes via the {@link android.app.Service#startForeground}.
         */
        void setForegroundServiceType(int serviceTypes, long now) {
            if (serviceTypes == mForegroundServiceTypes || !hasForegroundServices()) {
                // Nothing to do.
                return;
            }
            int changes = serviceTypes ^ mForegroundServiceTypes;
            for (int serviceType = Integer.highestOneBit(changes); serviceType != 0;) {
                final int i = foregroundServiceTypeToIndex(serviceType);
                if ((serviceTypes & serviceType) != 0) {
                    // Start this type.
                    if (mEvents[i] == null) {
                        mEvents[i] = new LinkedList<>();
                    }
                    if (!isActive(i)) {
                        mEvents[i].add(new BaseTimeEvent(now));
                        notifyListenersOnEventIfNecessary(true, now, serviceType);
                    }
                } else {
                    // Stop this type.
                    if (mEvents[i] != null && isActive(i)) {
                        mEvents[i].add(new BaseTimeEvent(now));
                        notifyListenersOnEventIfNecessary(false, now, serviceType);
                    }
                }
                changes &= ~serviceType;
                serviceType = Integer.highestOneBit(changes);
            }
            mForegroundServiceTypes = serviceTypes;
        }

        private void notifyListenersOnEventIfNecessary(boolean start, long now,
                @ForegroundServiceType int serviceType) {
            int eventType;
            switch (serviceType) {
                case FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK:
                    eventType = BaseAppStateDurationsTracker.EVENT_TYPE_FGS_MEDIA_PLAYBACK;
                    break;
                case FOREGROUND_SERVICE_TYPE_LOCATION:
                    eventType = BaseAppStateDurationsTracker.EVENT_TYPE_FGS_LOCATION;
                    break;
                default:
                    return;
            }
            mTracker.notifyListenersOnEvent(mUid, mPackageName, start, now, eventType);
        }

        void setIsLongRunning(boolean isLongRunning) {
            mIsLongRunning = isLongRunning;
        }

        boolean isLongRunning() {
            return mIsLongRunning;
        }

        boolean hasForegroundServices() {
            return isActive(DEFAULT_INDEX);
        }

        @Override
        String formatEventTypeLabel(int index) {
            if (index == DEFAULT_INDEX) {
                return "Overall foreground services: ";
            } else {
                return foregroundServiceTypeToLabel(indexToForegroundServiceType(index)) + ": ";
            }
        }
    }

    static final class AppFGSPolicy extends BaseAppStateEventsPolicy<AppFGSTracker> {
        /**
         * Whether or not we should enable the monitoring on abusive FGS.
         */
        static final String KEY_BG_FGS_MONITOR_ENABLED =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "fgs_monitor_enabled";

        /**
         * The size of the sliding window in which the accumulated FGS durations are checked
         * against the threshold.
         */
        static final String KEY_BG_FGS_LONG_RUNNING_WINDOW =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "fgs_long_running_window";

        /**
         * The threshold at where the accumulated FGS durations are considered as "long-running"
         * within the given window.
         */
        static final String KEY_BG_FGS_LONG_RUNNING_THRESHOLD =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "fgs_long_running_threshold";

        /**
         * If a package has run FGS with "mediaPlayback" over this threshold, it won't be considered
         * as a long-running FGS.
         */
        static final String KEY_BG_FGS_MEDIA_PLAYBACK_THRESHOLD =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "fgs_media_playback_threshold";

        /**
         * If a package has run FGS with "location" over this threshold, it won't be considered
         * as a long-running FGS.
         */
        static final String KEY_BG_FGS_LOCATION_THRESHOLD =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "fgs_location_threshold";

        /**
         * Default value to {@link #mTrackerEnabled}.
         */
        static final boolean DEFAULT_BG_FGS_MONITOR_ENABLED = true;

        /**
         * Default value to {@link #mMaxTrackingDuration}.
         */
        static final long DEFAULT_BG_FGS_LONG_RUNNING_WINDOW = ONE_DAY;

        /**
         * Default value to {@link #mBgFgsLongRunningThresholdMs}.
         */
        static final long DEFAULT_BG_FGS_LONG_RUNNING_THRESHOLD = 20 * ONE_HOUR;

        /**
         * Default value to {@link #mBgFgsMediaPlaybackThresholdMs}.
         */
        static final long DEFAULT_BG_FGS_MEDIA_PLAYBACK_THRESHOLD = 4 * ONE_HOUR;

        /**
         * Default value to {@link #mBgFgsLocationThresholdMs}.
         */
        static final long DEFAULT_BG_FGS_LOCATION_THRESHOLD = 4 * ONE_HOUR;

        /**
         * @see #KEY_BG_FGS_LONG_RUNNING_THRESHOLD.
         */
        private volatile long mBgFgsLongRunningThresholdMs = DEFAULT_BG_FGS_LONG_RUNNING_THRESHOLD;

        /**
         * @see #KEY_BG_FGS_MEDIA_PLAYBACK_THRESHOLD.
         */
        private volatile long mBgFgsMediaPlaybackThresholdMs =
                DEFAULT_BG_FGS_MEDIA_PLAYBACK_THRESHOLD;

        /**
         * @see #KEY_BG_FGS_LOCATION_THRESHOLD.
         */
        private volatile long mBgFgsLocationThresholdMs = DEFAULT_BG_FGS_LOCATION_THRESHOLD;

        AppFGSPolicy(@NonNull Injector injector, @NonNull AppFGSTracker tracker) {
            super(injector, tracker, KEY_BG_FGS_MONITOR_ENABLED, DEFAULT_BG_FGS_MONITOR_ENABLED,
                    KEY_BG_FGS_LONG_RUNNING_WINDOW, DEFAULT_BG_FGS_LONG_RUNNING_WINDOW);
        }

        @Override
        public void onSystemReady() {
            super.onSystemReady();
            updateBgFgsLongRunningThreshold();
            updateBgFgsMediaPlaybackThreshold();
            updateBgFgsLocationThreshold();
        }

        @Override
        public void onPropertiesChanged(String name) {
            switch (name) {
                case KEY_BG_FGS_LONG_RUNNING_THRESHOLD:
                    updateBgFgsLongRunningThreshold();
                    break;
                case KEY_BG_FGS_MEDIA_PLAYBACK_THRESHOLD:
                    updateBgFgsMediaPlaybackThreshold();
                    break;
                case KEY_BG_FGS_LOCATION_THRESHOLD:
                    updateBgFgsLocationThreshold();
                    break;
                default:
                    super.onPropertiesChanged(name);
                    break;
            }
        }

        @Override
        public void onTrackerEnabled(boolean enabled) {
            mTracker.onBgFgsMonitorEnabled(enabled);
        }

        @Override
        public void onMaxTrackingDurationChanged(long maxDuration) {
            mTracker.onBgFgsLongRunningThresholdChanged();
        }

        private void updateBgFgsLongRunningThreshold() {
            final long threshold = DeviceConfig.getLong(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_FGS_LONG_RUNNING_THRESHOLD,
                    DEFAULT_BG_FGS_LONG_RUNNING_THRESHOLD);
            if (threshold != mBgFgsLongRunningThresholdMs) {
                mBgFgsLongRunningThresholdMs = threshold;
                mTracker.onBgFgsLongRunningThresholdChanged();
            }
        }

        private void updateBgFgsMediaPlaybackThreshold() {
            mBgFgsMediaPlaybackThresholdMs = DeviceConfig.getLong(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_FGS_MEDIA_PLAYBACK_THRESHOLD,
                    DEFAULT_BG_FGS_MEDIA_PLAYBACK_THRESHOLD);
        }

        private void updateBgFgsLocationThreshold() {
            mBgFgsLocationThresholdMs = DeviceConfig.getLong(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_FGS_LOCATION_THRESHOLD,
                    DEFAULT_BG_FGS_LOCATION_THRESHOLD);
        }

        long getFgsLongRunningThreshold() {
            return mBgFgsLongRunningThresholdMs;
        }

        long getFgsLongRunningWindowSize() {
            return getMaxTrackingDuration();
        }

        long getFGSMediaPlaybackThreshold() {
            return mBgFgsMediaPlaybackThresholdMs;
        }

        long getLocationFGSThreshold() {
            return mBgFgsLocationThresholdMs;
        }

        void onLongRunningFgs(String packageName, int uid, @ReasonCode int exemptReason) {
            if (exemptReason != REASON_DENIED) {
                return;
            }
            final long now = SystemClock.elapsedRealtime();
            final long window = getFgsLongRunningWindowSize();
            final long since = Math.max(0, now - window);
            if (shouldExemptMediaPlaybackFGS(packageName, uid, now, window)) {
                return;
            }
            if (shouldExemptLocationFGS(packageName, uid, now, since)) {
                return;
            }
            if (hasBackgroundLocationPermission(packageName, uid)) {
                // This package has background location permission, ignore it.
                return;
            }
            mTracker.mAppRestrictionController.postLongRunningFgsIfNecessary(packageName, uid);
        }

        boolean shouldExemptMediaPlaybackFGS(String packageName, int uid, long now, long window) {
            final long mediaPlaybackMs = mTracker.mAppRestrictionController
                    .getCompositeMediaPlaybackDurations(packageName, uid, now, window);
            if (mediaPlaybackMs > 0 && mediaPlaybackMs >= getFGSMediaPlaybackThreshold()) {
                if (DEBUG_BACKGROUND_FGS_TRACKER) {
                    Slog.i(TAG, "Ignoring long-running FGS in " + packageName + "/"
                            + UserHandle.formatUid(uid) + " media playback for "
                            + TimeUtils.formatDuration(mediaPlaybackMs));
                }
                return true;
            }
            return false;
        }

        boolean shouldExemptLocationFGS(String packageName, int uid, long now, long since) {
            final long locationMs = mTracker.mAppRestrictionController
                    .getForegroundServiceTotalDurationsSince(packageName, uid, since, now,
                            FOREGROUND_SERVICE_TYPE_LOCATION);
            if (locationMs > 0 && locationMs >= getLocationFGSThreshold()) {
                if (DEBUG_BACKGROUND_FGS_TRACKER) {
                    Slog.i(TAG, "Ignoring long-running FGS in " + packageName + "/"
                            + UserHandle.formatUid(uid) + " location for "
                            + TimeUtils.formatDuration(locationMs));
                }
                return true;
            }
            return false;
        }

        boolean hasBackgroundLocationPermission(String packageName, int uid) {
            if (mInjector.getPermissionManagerServiceInternal().checkPermission(
                    packageName, ACCESS_BACKGROUND_LOCATION, UserHandle.getUserId(uid))
                    == PERMISSION_GRANTED) {
                if (DEBUG_BACKGROUND_FGS_TRACKER) {
                    Slog.i(TAG, "Ignoring bg-location FGS in " + packageName + "/"
                            + UserHandle.formatUid(uid));
                }
                return true;
            }
            return false;
        }

        @Override
        String getExemptionReasonString(String packageName, int uid, @ReasonCode int reason) {
            if (reason != REASON_DENIED) {
                return super.getExemptionReasonString(packageName, uid, reason);
            }
            final long now = SystemClock.elapsedRealtime();
            final long window = getFgsLongRunningWindowSize();
            final long since = Math.max(0, now - getFgsLongRunningWindowSize());
            return "{mediaPlayback=" + shouldExemptMediaPlaybackFGS(packageName, uid, now, window)
                    + ", location=" + shouldExemptLocationFGS(packageName, uid, now, since)
                    + ", bgLocationPerm=" + hasBackgroundLocationPermission(packageName, uid) + "}";
        }

        void onLongRunningFgsGone(String packageName, int uid) {
            mTracker.mAppRestrictionController
                    .cancelLongRunningFGSNotificationIfNecessary(packageName, uid);
        }

        @Override
        void dump(PrintWriter pw, String prefix) {
            pw.print(prefix);
            pw.println("APP FOREGROUND SERVICE TRACKER POLICY SETTINGS:");
            final String indent = "  ";
            prefix = indent + prefix;
            super.dump(pw, prefix);
            if (isEnabled()) {
                pw.print(prefix);
                pw.print(KEY_BG_FGS_LONG_RUNNING_THRESHOLD);
                pw.print('=');
                pw.println(mBgFgsLongRunningThresholdMs);
                pw.print(prefix);
                pw.print(KEY_BG_FGS_MEDIA_PLAYBACK_THRESHOLD);
                pw.print('=');
                pw.println(mBgFgsMediaPlaybackThresholdMs);
                pw.print(prefix);
                pw.print(KEY_BG_FGS_LOCATION_THRESHOLD);
                pw.print('=');
                pw.println(mBgFgsLocationThresholdMs);
            }
        }
    }
}
