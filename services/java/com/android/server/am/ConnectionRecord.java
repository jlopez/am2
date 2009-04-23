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

import android.app.IServiceConnection;

import java.io.PrintWriter;

/**
 * Description of a single binding to a service.
 */
class ConnectionRecord {
    final AppBindRecord binding;    // The application/service binding.
    final HistoryRecord activity;   // If non-null, the owning activity.
    final IServiceConnection conn;  // The client connection.
    final int flags;                // Binding options.
    String stringName;              // Caching of toString.
    
    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + this);
        pw.println(prefix + "binding=" + binding);
        pw.println(prefix + "activity=" + activity);
        pw.println(prefix + "conn=" + conn.asBinder()
                + " flags=0x" + Integer.toHexString(flags));
    }
    
    ConnectionRecord(AppBindRecord _binding, HistoryRecord _activity,
               IServiceConnection _conn, int _flags) {
        binding = _binding;
        activity = _activity;
        conn = _conn;
        flags = _flags;
    }

    public String toString() {
        if (stringName != null) {
            return stringName;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("ConnectionRecord{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(' ');
        sb.append(binding.service.shortName);
        sb.append(":@");
        sb.append(Integer.toHexString(System.identityHashCode(conn.asBinder())));
        sb.append('}');
        return stringName = sb.toString();
    }
}
