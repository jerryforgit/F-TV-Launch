package com.jerry.ftvlaunch;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

/** 开机自启：收到 BOOT_COMPLETED 后按延迟投递一次启动任务。 */
public class BootReceiver extends BroadcastReceiver {

    public static final String ACTION_START = "com.jerry.ftvlaunch.BOOT_START";
    public static final String PREFS = "settings";
    public static final String KEY_DELAY = "boot_delay_seconds";
    public static final int DEFAULT_DELAY = 15;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (HomeStore.loadBoot(context).isEmpty()) return;

        int delay = delaySeconds(context);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent start = new Intent(context, BootStartReceiver.class).setAction(ACTION_START);
        PendingIntent pi = PendingIntent.getBroadcast(context, 100,
                start, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delay * 1000L, pi);
    }

    public static int delaySeconds(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getInt(KEY_DELAY, DEFAULT_DELAY);
    }

    public static void setDelaySeconds(Context ctx, int seconds) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_DELAY, Math.max(0, seconds)).apply();
    }
}
