package com.jerry.ftvlaunch;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.util.DisplayMetrics;

import java.util.Random;

/** 桌面小控件：作为网格里的一个特殊格子存在，应用可以围绕它排列。 */
final class Widgets {

    static final String PLACEHOLDER = "__widget__";

    private static final String PREFS = "widgets";
    private static final String KEY_HOST = "host_id";
    private static final String KEY_WIDGET = "widget_id";
    private static final String KEY_PENDING = "pending_id";

    private static AppWidgetHost host;

    private Widgets() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static AppWidgetHost host(Context c) {
        if (host == null) {
            SharedPreferences sp = prefs(c);
            int id = sp.getInt(KEY_HOST, 0);
            if (id == 0) {
                id = 1000 + new Random().nextInt(9000);
                sp.edit().putInt(KEY_HOST, id).apply();
            }
            host = new AppWidgetHost(c.getApplicationContext(), id);
        }
        return host;
    }

    static boolean has(Context c) {
        return prefs(c).getInt(KEY_WIDGET, -1) >= 0;
    }

    static void remove(Context c) {
        int id = prefs(c).getInt(KEY_WIDGET, -1);
        if (id >= 0) {
            try {
                host(c).deleteAppWidgetId(id);
            } catch (RuntimeException ignored) {
            }
        }
        prefs(c).edit().remove(KEY_WIDGET).apply();
    }

    static int allocate(Context c) {
        int id = host(c).allocateAppWidgetId();
        prefs(c).edit().putInt(KEY_PENDING, id).apply();
        return id;
    }

    static void commitPending(Context c, boolean ok) {
        SharedPreferences sp = prefs(c);
        int id = sp.getInt(KEY_PENDING, -1);
        if (id < 0) return;
        if (ok) {
            sp.edit().putInt(KEY_WIDGET, id).remove(KEY_PENDING).apply();
            java.util.List<String> home = HomeStore.loadHome(c);
            if (!home.contains(PLACEHOLDER)) {
                home.add(PLACEHOLDER);
                HomeStore.saveHome(c, home);
            }
        } else {
            try {
                host(c).deleteAppWidgetId(id);
            } catch (RuntimeException ignored) {
            }
            sp.edit().remove(KEY_PENDING).apply();
        }
    }

    static Intent bindIntent(Context c, int id, AppWidgetProviderInfo info) {
        Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider);
        return i;
    }

    /** 用系统原始密度渲染控件，否则 1:1 布局改过的 density 会把控件字体算成 1px。 */
    private static android.content.Context widgetContext(Activity a) {
        DisplayMetrics dm = new DisplayMetrics();
        dm.setTo(a.getResources().getDisplayMetrics());
        if (IconButton.originalDensity > 0) {
            dm.density = IconButton.originalDensity;
            dm.scaledDensity = IconButton.originalScaledDensity;
        }
        final Resources res = new Resources(a.getAssets(), dm, a.getResources().getConfiguration());
        return new ContextWrapper(a) {
            @Override
            public Resources getResources() {
                return res;
            }
        };
    }

    /** 生成一个可嵌进格子的控件视图；没绑定或系统不认时返回 null。 */
    static AppWidgetHostView createView(Activity a) {
        if (!has(a)) return null;
        int id = prefs(a).getInt(KEY_WIDGET, -1);
        AppWidgetManager mgr = AppWidgetManager.getInstance(a);
        AppWidgetProviderInfo info = mgr.getAppWidgetInfo(id);
        if (info == null) return null;
        try {
            AppWidgetHostView view = host(a).createView(widgetContext(a), id, info);
            view.setFocusable(false);
            view.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            return view;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 模拟一次点击，转发给控件里的 RemoteViews。 */
    static void sendClick(AppWidgetHostView view) {
        if (view == null || view.getWidth() == 0) return;
        try {
            float x = view.getWidth() / 2f;
            float y = view.getHeight() / 2f;
            long now = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now,
                    android.view.MotionEvent.ACTION_DOWN, x, y, 0);
            view.dispatchTouchEvent(down);
            android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 60,
                    android.view.MotionEvent.ACTION_UP, x, y, 0);
            view.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
        } catch (RuntimeException ignored) {
        }
    }
}
