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
                    String event = RustCast.takeEvent();
                    if (event != null) {
                        String[] parts = event.split("\n", 3);
                        String cmd = parts[0];
                        main.post(() -> dispatch(ctx, cmd, parts));
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

    private static void dispatch(Context ctx, String cmd, String[] parts) {
        if ("play".equals(cmd)) {
            String url = parts.length > 1 ? parts[1] : "";
            String title = parts.length > 2 ? parts[2] : "";
            Intent i = new Intent(ctx, DlnaPlayerActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra(DlnaPlayerActivity.EXTRA_URI, url);
            i.putExtra(DlnaPlayerActivity.EXTRA_TITLE, title);
            ctx.startActivity(i);
            return;
        }
        DlnaPlayerActivity p = DlnaPlayerActivity.current;
        if (p == null) return;
        switch (cmd) {
            case "stop":
                p.doStop();
                break;
            case "pause":
                p.doPause();
                break;
            case "resume":
                p.doPlay();
                break;
            case "seek":
                if (parts.length > 1) {
                    try {
                        p.doSeek(Long.parseLong(parts[1].trim()) * 1000);
                    } catch (NumberFormatException ignored) {
                    }
                }
                break;
            case "volume":
                if (parts.length > 1) {
                    try {
                        p.setVolumePercent(Integer.parseInt(parts[1].trim()));
                    } catch (NumberFormatException ignored) {
                    }
                }
                break;
            default:
                break;
        }
    }

    private RustDlna() {
    }
}
