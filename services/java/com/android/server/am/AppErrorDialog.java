/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.pm.IPackageDataObserver;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.List;

import static com.android.server.am.ActivityManagerService.IS_USER_BUILD;

final class AppErrorDialog extends BaseErrorDialog implements View.OnClickListener {

    private final ActivityManagerService mService;
    private final AppErrorResult mResult;
    private final ProcessRecord mProc;
    private final boolean mRepeating;

    private CharSequence mName;

    static int CANT_SHOW = -1;
    static int BACKGROUND_USER = -2;
    static int ALREADY_SHOWING = -3;

    // Event 'what' codes
    static final int FORCE_QUIT = 1;
    static final int FORCE_QUIT_AND_REPORT = 2;
    static final int RESTART = 3;
    static final int RESET = 4;
    static final int MUTE = 5;
    static final int TIMEOUT = 6;

    // 5-minute timeout, then we automatically dismiss the crash dialog
    static final long DISMISS_TIMEOUT = 1000 * 60 * 5;

    public AppErrorDialog(Context context, ActivityManagerService service, Data data) {
        super(context);
        Resources res = context.getResources();

        mService = service;
        mProc = data.proc;
        mResult = data.result;
        mRepeating = data.repeating;
        if ((mProc.pkgList.size() == 1) &&
                (mName = context.getPackageManager().getApplicationLabel(mProc.info)) != null) {
            setTitle(res.getString(
                    mRepeating ? com.android.internal.R.string.aerr_application_repeated
                            : com.android.internal.R.string.aerr_application,
                    mName.toString(), mProc.info.processName));
        } else {
            mName = mProc.processName;
            setTitle(res.getString(
                    mRepeating ? com.android.internal.R.string.aerr_process_repeated
                            : com.android.internal.R.string.aerr_process,
                    mName.toString()));
        }

        setCancelable(false);

        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.setTitle("Application Error: " + mProc.info.processName);
        attrs.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SYSTEM_ERROR
                | WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        getWindow().setAttributes(attrs);
        if (mProc.persistent) {
            getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
        }

        // After the timeout, pretend the user clicked the quit button
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(TIMEOUT),
                DISMISS_TIMEOUT);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final FrameLayout frame = (FrameLayout) findViewById(android.R.id.custom);
        final Context context = getContext();
        LayoutInflater.from(context).inflate(
                com.android.internal.R.layout.app_error_dialog, frame, true);

        final TextView restart = (TextView) findViewById(com.android.internal.R.id.aerr_restart);
        restart.setOnClickListener(this);
        restart.setVisibility(!mRepeating ? View.VISIBLE : View.GONE);
        final TextView reset = (TextView) findViewById(com.android.internal.R.id.aerr_reset);
        reset.setOnClickListener(this);
        reset.setVisibility(mRepeating ? View.VISIBLE : View.GONE);
        final TextView report = (TextView) findViewById(com.android.internal.R.id.aerr_report);
        report.setOnClickListener(this);
        final boolean hasReceiver = mProc.errorReportReceiver != null;
        report.setVisibility(hasReceiver ? View.VISIBLE : View.GONE);
        final TextView close = (TextView) findViewById(com.android.internal.R.id.aerr_close);
        close.setOnClickListener(this);
        final TextView mute = (TextView) findViewById(com.android.internal.R.id.aerr_mute);
        mute.setOnClickListener(this);
        mute.setVisibility(!IS_USER_BUILD ? View.VISIBLE : View.GONE);

        findViewById(com.android.internal.R.id.customPanel).setVisibility(View.VISIBLE);
    }

    private final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            final int result = msg.what;

            synchronized (mService) {
                if (mProc != null && mProc.crashDialog == AppErrorDialog.this) {
                    mProc.crashDialog = null;
                }
            }
            mResult.set(result);

            // Make sure we don't have time timeout still hanging around.
            removeMessages(TIMEOUT);

            dismiss();
        }
    };

    @Override
    public void dismiss() {
        if (!mResult.mHasResult) {
            // We are dismissing and the result has not been set...go ahead and set.
            mResult.set(FORCE_QUIT);
        }
        super.dismiss();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case com.android.internal.R.id.aerr_restart:
                mHandler.obtainMessage(RESTART).sendToTarget();
                break;
            case com.android.internal.R.id.aerr_reset:
                mHandler.obtainMessage(RESET).sendToTarget();
                break;
            case com.android.internal.R.id.aerr_report:
                mHandler.obtainMessage(FORCE_QUIT_AND_REPORT).sendToTarget();
                break;
            case com.android.internal.R.id.aerr_close:
                mHandler.obtainMessage(FORCE_QUIT).sendToTarget();
                break;
            case com.android.internal.R.id.aerr_mute:
                mHandler.obtainMessage(MUTE).sendToTarget();
                break;
            default:
                break;
        }
    }

    static class Data {
        AppErrorResult result;
        TaskRecord task;
        boolean repeating;
        ProcessRecord proc;
    }
}
