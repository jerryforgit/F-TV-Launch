package com.jerry.ftvlaunch;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.UUID;

/** 用 Rust 核心跑 DLNA，Java 只负责把播放请求变成播放页。 */
final class RustDlna {

    private static final String TAG = "RustDlna";
    static final int PORT = 8090;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean started;

    static synchronized void start(Context ctx) {
        if (started) return;
        started = true;

        SharedPreferences sp = ctx.getSharedPreferences("rustdlna", Context.MODE_PRIVATE);
        String udn = sp.getString("udn", null);
        if (udn == null) {
            udn = "ftv-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            sp.edit().putString("udn", udn).apply();
        }
        final String id = udn;
        final String ip = ControlServer.localIp();

        new Thread(() -> {
            try {
                int rc = RustCast.start(PORT, ip, id, "F TV 桌面");
                Log.i(TAG, "rust dlna start rc=" + rc + " ip=" + ip);
            } catch (Throwable t) {
                Log.e(TAG, "rust dlna failed", t);
            }
        }, "rust-dlna-start").start();

        new Thread(() -> {
            while (true) {
                try {
                    String pending = RustCast.takePlay();
                    if (pending != null) {
                        int nl = pending.indexOf('\n');
                        String url = nl >= 0 ? pending.substring(0, nl) : pending;
                        String title = nl >= 0 ? pending.substring(nl + 1) : "";
                        main.post(() -> {
                            Intent i = new Intent(ctx, DlnaPlayerActivity.class);
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            i.putExtra(DlnaPlayerActivity.EXTRA_URI, url);
                            i.putExtra(DlnaPlayerActivity.EXTRA_TITLE, title);
                            ctx.startActivity(i);
                        });
                    }
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    Log.e(TAG, "poll failed", t);
                }
            }
        }, "rust-dlna-poll").start();
    }

    private RustDlna() {
    }
}
