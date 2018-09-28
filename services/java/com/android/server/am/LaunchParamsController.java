/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.Display.INVALID_DISPLAY;

import static com.android.server.am.LaunchParamsController.LaunchParamsModifier.RESULT_CONTINUE;
import static com.android.server.am.LaunchParamsController.LaunchParamsModifier.RESULT_DONE;
import static com.android.server.am.LaunchParamsController.LaunchParamsModifier.RESULT_SKIP;

import android.annotation.IntDef;
import android.app.ActivityOptions;
import android.content.pm.ActivityInfo.WindowLayout;
import android.graphics.Rect;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link LaunchParamsController} calculates the {@link LaunchParams} by coordinating between
 * registered {@link LaunchParamsModifier}s.
 */
class LaunchParamsController {
    private final ActivityTaskManagerService mService;
    private final List<LaunchParamsModifier> mModifiers = new ArrayList<>();

    // Temporary {@link LaunchParams} for internal calculations. This is kept separate from
    // {@code mTmpCurrent} and {@code mTmpResult} to prevent clobbering values.
    private final LaunchParams mTmpParams = new LaunchParams();

    private final LaunchParams mTmpCurrent = new LaunchParams();
    private final LaunchParams mTmpResult = new LaunchParams();

    LaunchParamsController(ActivityTaskManagerService service) {
       mService = service;
    }

    /**
     * Creates a {@link LaunchParamsController} with default registered
     * {@link LaunchParamsModifier}s.
     */
    void registerDefaultModifiers(ActivityStackSupervisor supervisor) {
        // {@link TaskLaunchParamsModifier} handles window layout preferences.
        registerModifier(new TaskLaunchParamsModifier(supervisor));
    }

    /**
     * Returns the {@link LaunchParams} calculated by the registered modifiers
     * @param task      The {@link TaskRecord} currently being positioned.
     * @param layout    The specified {@link WindowLayout}.
     * @param activity  The {@link ActivityRecord} currently being positioned.
     * @param source    The {@link ActivityRecord} from which activity was started from.
     * @param options   The {@link ActivityOptions} specified for the activity.
     * @param result    The resulting params.
     */
    void calculate(TaskRecord task, WindowLayout layout, ActivityRecord activity,
                   ActivityRecord source, ActivityOptions options, LaunchParams result) {
        result.reset();

        // We start at the last registered {@link LaunchParamsModifier} as this represents
        // The modifier closest to the product level. Moving back through the list moves closer to
        // the platform logic.
        for (int i = mModifiers.size() - 1; i >= 0; --i) {
            mTmpCurrent.set(result);
            mTmpResult.reset();
            final LaunchParamsModifier modifier = mModifiers.get(i);

            switch(modifier.onCalculate(task, layout, activity, source, options, mTmpCurrent,
                    mTmpResult)) {
                case RESULT_SKIP:
                    // Do not apply any results when we are told to skip
                    continue;
                case RESULT_DONE:
                    // Set result and return immediately.
                    result.set(mTmpResult);
                    return;
                case RESULT_CONTINUE:
                    // Set result and continue
                    result.set(mTmpResult);
                    break;
            }
        }
    }

    /**
     * A convenience method for laying out a task.
     * @return {@code true} if bounds were set on the task. {@code false} otherwise.
     */
    boolean layoutTask(TaskRecord task, WindowLayout layout) {
        return layoutTask(task, layout, null /*activity*/, null /*source*/, null /*options*/);
    }

    boolean layoutTask(TaskRecord task, WindowLayout layout, ActivityRecord activity,
            ActivityRecord source, ActivityOptions options) {
        calculate(task, layout, activity, source, options, mTmpParams);

        // No changes, return.
        if (mTmpParams.isEmpty()) {
            return false;
        }

        mService.mWindowManager.deferSurfaceLayout();

        try {
            if (mTmpParams.hasPreferredDisplay()
                    && mTmpParams.mPreferredDisplayId != task.getStack().getDisplay().mDisplayId) {
                mService.moveStackToDisplay(task.getStackId(), mTmpParams.mPreferredDisplayId);
            }

            if (mTmpParams.hasWindowingMode()
                    && mTmpParams.mWindowingMode != task.getStack().getWindowingMode()) {
                task.getStack().setWindowingMode(mTmpParams.mWindowingMode);
            }

            if (!mTmpParams.mBounds.isEmpty()) {
                task.updateOverrideConfiguration(mTmpParams.mBounds);
                return true;
            } else {
                return false;
            }
        } finally {
            mService.mWindowManager.continueSurfaceLayout();
        }
    }

    /**
     * Adds a modifier to participate in future bounds calculation. Note that the last registered
     * {@link LaunchParamsModifier} will be the first to calculate the bounds.
     */
    void registerModifier(LaunchParamsModifier modifier) {
        if (mModifiers.contains(modifier)) {
            return;
        }

        mModifiers.add(modifier);
    }

    /**
     * A container for holding launch related fields.
     */
    static class LaunchParams {
        /** The bounds within the parent container. */
        final Rect mBounds = new Rect();

        /** The id of the display the {@link TaskRecord} would prefer to be on. */
        int mPreferredDisplayId;

        /** The windowing mode to be in. */
        int mWindowingMode;

        /** Sets values back to default. {@link #isEmpty} will return {@code true} once called. */
        void reset() {
            mBounds.setEmpty();
            mPreferredDisplayId = INVALID_DISPLAY;
            mWindowingMode = WINDOWING_MODE_UNDEFINED;
        }

        /** Copies the values set on the passed in {@link LaunchParams}. */
        void set(LaunchParams params) {
            mBounds.set(params.mBounds);
            mPreferredDisplayId = params.mPreferredDisplayId;
            mWindowingMode = params.mWindowingMode;
        }

        /** Returns {@code true} if no values have been explicitly set. */
        boolean isEmpty() {
            return mBounds.isEmpty() && mPreferredDisplayId == INVALID_DISPLAY
                    && mWindowingMode == WINDOWING_MODE_UNDEFINED;
        }

        boolean hasWindowingMode() {
            return mWindowingMode != WINDOWING_MODE_UNDEFINED;
        }

        boolean hasPreferredDisplay() {
            return mPreferredDisplayId != INVALID_DISPLAY;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            LaunchParams that = (LaunchParams) o;

            if (mPreferredDisplayId != that.mPreferredDisplayId) return false;
            if (mWindowingMode != that.mWindowingMode) return false;
            return mBounds != null ? mBounds.equals(that.mBounds) : that.mBounds == null;
        }

        @Override
        public int hashCode() {
            int result = mBounds != null ? mBounds.hashCode() : 0;
            result = 31 * result + mPreferredDisplayId;
            result = 31 * result + mWindowingMode;
            return result;
        }
    }

    /**
     * An interface implemented by those wanting to participate in bounds calculation.
     */
    interface LaunchParamsModifier {
        @Retention(RetentionPolicy.SOURCE)
        @IntDef({RESULT_SKIP, RESULT_DONE, RESULT_CONTINUE})
        @interface Result {}

        /** Returned when the modifier does not want to influence the bounds calculation */
        int RESULT_SKIP = 0;
        /**
         * Returned when the modifier has changed the bounds and would like its results to be the
         * final bounds applied.
         */
        int RESULT_DONE = 1;
        /**
         * Returned when the modifier has changed the bounds but is okay with other modifiers
         * influencing the bounds.
         */
        int RESULT_CONTINUE = 2;

        /**
         * Returns the launch params that the provided activity launch params should be overridden
         * to. {@link LaunchParamsModifier} can use this for various purposes, including: 1)
         * Providing default bounds if the launch bounds have not been provided. 2) Repositioning
         * the task so it doesn't get placed over an existing task. 3) Resizing the task so that its
         * dimensions match the activity's requested orientation.
         *
         * @param task          Can be: 1) the target task in which the source activity wants to
         *                      launch the target activity; 2) a newly created task that Android
         *                      gives a chance to override its launching bounds; 3) {@code null} if
         *                      this is called to override an activity's launching bounds.
         * @param layout        Desired layout when activity is first launched.
         * @param activity      Activity that is being started. This can be {@code null} on
         *                      re-parenting an activity to a new task (e.g. for
         *                      Picture-In-Picture). Tasks being created because an activity was
         *                      launched should have this be non-null.
         * @param source        the Activity that launched a new task. Could be {@code null}.
         * @param options       {@link ActivityOptions} used to start the activity with.
         * @param currentParams launching params after the process of last {@link
         *                      LaunchParamsModifier}.
         * @param outParams     the result params to be set.
         * @return see {@link LaunchParamsModifier.Result}
         */
        @Result
        int onCalculate(TaskRecord task, WindowLayout layout, ActivityRecord activity,
                ActivityRecord source, ActivityOptions options, LaunchParams currentParams,
                LaunchParams outParams);
    }
}
