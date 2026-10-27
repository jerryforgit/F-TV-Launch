package com.jerry.ftvlaunch;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/** 浅色 / 深色主题的取色中心。 */
final class Theme {

    private static final String PREFS = "theme";
    private static final String KEY_DARK = "dark";

    private Theme() {
    }

    static boolean isDark(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DARK, false);
    }

    static void setDark(Context c, boolean dark) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DARK, dark).apply();
    }

    static int bg(Context c) {
        return isDark(c) ? Color.parseColor("#14161B") : Color.parseColor("#FFD3DCEA");
    }

    static int cell(Context c) {
        return isDark(c) ? Color.parseColor("#22252D") : Color.parseColor("#FFE9EDF4");
    }

    static int cellSelected(Context c) {
        return isDark(c) ? Color.parseColor("#31353F") : Color.WHITE;
    }

    static int text(Context c) {
        return isDark(c) ? Color.parseColor("#FFE8E8E8") : Color.parseColor("#FF4D4D4D");
    }

    static int subText(Context c) {
        return isDark(c) ? Color.parseColor("#FF9AA0AA") : Color.parseColor("#FF8A8A8A");
    }

    static int iconTint(Context c) {
        return isDark(c) ? Color.parseColor("#FFC8C8C8") : Color.parseColor("#FF808080");
    }

    static int panel(Context c) {
        return isDark(c) ? Color.parseColor("#1B1E24") : Color.parseColor("#FFD3DCEA");
    }

    static android.graphics.drawable.Drawable filled(Context c, int color) {
        return rounded(c, color);
    }

    static android.graphics.drawable.Drawable rowBackground(Context c) {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, rounded(c, rowSelected(c)));
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(c, rowSelected(c)));
        s.addState(new int[]{}, rounded(c, cell(c)));
        return s;
    }

    private static android.graphics.drawable.GradientDrawable rounded(Context c, int color) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(14 * c.getResources().getDisplayMetrics().density);
        g.setColor(color);
        return g;
    }

    static int rowSelected(Context c) {
        return isDark(c) ? Color.parseColor("#3C4250") : Color.WHITE;
    }
}
