/*
 * Copyright (C) 2026 小狗便利店投屏 contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package cn.local.livingroomcast;

import android.content.*;
import android.os.Build;

/** Opt-in standby after a cold boot. Xiaomi TVs usually resume from STR standby instead, where the service may still run. */
public final class BootReceiver extends BroadcastReceiver {
    static final String PREF = "boot_standby";
    static boolean enabled(Context context) { return context.getSharedPreferences("receiver", Context.MODE_PRIVATE).getBoolean(PREF, false); }
    static void setEnabled(Context context, boolean value) { context.getSharedPreferences("receiver", Context.MODE_PRIVATE).edit().putBoolean(PREF, value).apply(); }
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || !enabled(context)) return;
        Intent service = new Intent(context, ReceiverService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service); else context.startService(service);
        } catch (RuntimeException ignored) { /* The system may refuse; the user can still open the app. */ }
    }
}
