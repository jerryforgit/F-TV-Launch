package com.jerry.ftvlaunch;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/** 体感遥控的光标：悬浮在最上层，只画不接收触摸。需要「显示在其他应用上层」权限。 */
final class CursorOverlay {

    private final Context ctx;
    private final WindowManager wm;
    private View dot;
    private WindowManager.LayoutParams lp;
    private boolean shown;
    private int x = 960, y = 540;

    CursorOverlay(Context c) {
        ctx = c.getApplicationContext();
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean canShow() {
        if (Build.VERSION.SDK_INT >= 23) return android.provider.Settings.canDrawOverlays(ctx);
        return true;
    }

    void show() {
        if (shown || wm == null) return;
        if (!canShow()) return;
        dot = new View(ctx);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.parseColor("#F2FFFFFF"));
        g.setStroke(6, Color.parseColor("#FF2F6BFF"));
        dot.setBackground(g);

        int size = Math.round(22 * ctx.getResources().getDisplayMetrics().density);
        lp = new WindowManager.LayoutParams(size, size,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = x;
        lp.y = y;
        try {
            wm.addView(dot, lp);
            shown = true;
        } catch (Exception ignored) {
        }
    }

    void hide() {
        if (!shown || wm == null) return;
        try {
            wm.removeView(dot);
        } catch (Exception ignored) {
        }
        shown = false;
    }

    boolean isShown() {
        return shown;
    }

    void move(double dx, double dy) {
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        x = clamp(x + (int) Math.round(dx), dm.widthPixels - 10);
        y = clamp(y + (int) Math.round(dy), dm.heightPixels - 10);
        if (!shown) return;
        lp.x = x;
        lp.y = y;
        try {
            wm.updateViewLayout(dot, lp);
        } catch (Exception ignored) {
        }
    }

    int[] pos() {
        return new int[]{x, y};
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(v, max));
    }
}
