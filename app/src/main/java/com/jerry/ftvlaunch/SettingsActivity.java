package com.jerry.ftvlaunch;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** 桌面设置：开机启动延迟、桌面背景、编辑提示。 */
public class SettingsActivity extends Activity {

    private static final int[] DELAYS = {5, 10, 15, 30, 60};
    private static final int PICK_WALLPAPER = 20;
    private static final int REQ_BIND_WIDGET = 30;

    private TextView rowDelay;
    private TextView rowWallpaper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        IconButton.applyDesignScale(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        findViewById(R.id.root_settings).setBackgroundColor(Theme.bg(this));
        ((android.widget.TextView) findViewById(R.id.hint_settings)).setTextColor(Theme.subText(this));
        for (int id : new int[]{R.id.row_delay, R.id.row_boot_count, R.id.row_wallpaper,
                R.id.row_wallpaper_reset, R.id.row_widget, R.id.row_widget_remove, R.id.row_dark}) {
            android.view.View v = findViewById(id);
            if (v instanceof android.widget.TextView) {
                android.widget.TextView tv = (android.widget.TextView) v;
                tv.setTextColor(Theme.text(this));
                tv.setBackground(Theme.rowBackground(this));
            }
        }
        getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        rowDelay = findViewById(R.id.row_delay);
        rowDelay.setOnClickListener(v -> {
            int current = BootReceiver.delaySeconds(this);
            int next = DELAYS[0];
            for (int i = 0; i < DELAYS.length; i++) {
                if (DELAYS[i] == current) {
                    next = DELAYS[(i + 1) % DELAYS.length];
                    break;
                }
            }
            BootReceiver.setDelaySeconds(this, next);
            update();
        });

        rowWallpaper = findViewById(R.id.row_wallpaper);
        rowWallpaper.setOnClickListener(v ->
                startActivity(new Intent(this, ImagePickerActivity.class)));

        findViewById(R.id.row_wallpaper_reset).setOnClickListener(v -> {
            Wallpaper.clear(this);
            Toast.makeText(this, "已恢复默认背景", Toast.LENGTH_SHORT).show();
            update();
        });

        ((android.widget.TextView) findViewById(R.id.row_dark)).setText(
                "深色主题    " + (Theme.isDark(this) ? "开" : "关"));
        findViewById(R.id.row_dark).setOnClickListener(v -> {
            Theme.setDark(this, !Theme.isDark(this));
            recreate();
        });
        findViewById(R.id.row_widget).setOnClickListener(v -> pickWidget());
        findViewById(R.id.row_widget_remove).setOnClickListener(v -> {
            java.util.List<String> home = HomeStore.loadHome(this);
            home.remove(Widgets.PLACEHOLDER);
            HomeStore.saveHome(this, home);
            Widgets.remove(this);
            Toast.makeText(this, "已移除桌面小控件", Toast.LENGTH_SHORT).show();
            update();
        });

        update();
    }

    private void pickWidget() {
        AppWidgetManager mgr = AppWidgetManager.getInstance(this);
        final java.util.List<AppWidgetProviderInfo> infos = mgr.getInstalledProviders();
        if (infos.isEmpty()) {
            Toast.makeText(this, "系统里没有可用的小控件", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[infos.size()];
        for (int i = 0; i < infos.size(); i++) {
            AppWidgetProviderInfo info = infos.get(i);
            labels[i] = info.loadLabel(getPackageManager());
        }
        new AlertDialog.Builder(this)
                .setTitle("选择小控件")
                .setItems(labels, (dialog, which) -> bindWidget(mgr, infos.get(which)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void bindWidget(AppWidgetManager mgr, AppWidgetProviderInfo info) {
        int id = Widgets.allocate(this);
        boolean ok;
        try {
            ok = mgr.bindAppWidgetIdIfAllowed(id, info.provider);
        } catch (RuntimeException e) {
            ok = false;
        }
        if (ok) {
            Widgets.commitPending(this, true);
            Toast.makeText(this, "小控件已添加，返回桌面可见", Toast.LENGTH_SHORT).show();
        } else {
            try {
                startActivityForResult(Widgets.bindIntent(this, id, info), REQ_BIND_WIDGET);
            } catch (RuntimeException e) {
                Widgets.commitPending(this, false);
                Toast.makeText(this, "系统拒绝绑定小控件", Toast.LENGTH_SHORT).show();
            }
        }
        update();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_BIND_WIDGET) {
            Widgets.commitPending(this, resultCode == RESULT_OK);
            update();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_WALLPAPER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            boolean ok = Wallpaper.saveFrom(this, data.getData());
            Toast.makeText(this, ok ? "背景已更新，返回桌面可见" : "图片读取失败", Toast.LENGTH_SHORT).show();
            update();
        }
    }

    private void update() {
        rowDelay.setText("开机启动延迟    " + BootReceiver.delaySeconds(this) + " 秒（点击切换）");
        List<String> boot = HomeStore.loadBoot(this);
        TextView count = findViewById(R.id.row_boot_count);
        count.setText("开机启动应用    " + boot.size() + " 个");
        rowWallpaper.setText(Wallpaper.exists(this) ? "桌面背景    更换图片" : "桌面背景    选择图片");
    }
}
