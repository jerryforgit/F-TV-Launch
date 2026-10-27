package com.jerry.ftvlaunch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/** 延迟到期后逐个拉起开机启动列表里的应用（每个间隔 2 秒，避免同时抢 CPU）。 */
public class BootStartReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context ctx = context.getApplicationContext();
        final java.util.List<String> pkgs = HomeStore.loadBoot(ctx);
        if (pkgs.isEmpty()) return;

        final Handler handler = new Handler(Looper.getMainLooper());
        for (int i = 0; i < pkgs.size(); i++) {
            final String pkg = pkgs.get(i);
            handler.postDelayed(() -> {
                Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch == null) return;
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    ctx.startActivity(launch);
                } catch (RuntimeException ignored) {
                }
            }, i * 2000L);
        }
    }
}
