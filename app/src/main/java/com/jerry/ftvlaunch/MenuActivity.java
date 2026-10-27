package com.jerry.ftvlaunch;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MenuActivity extends Activity {

    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_FROM_DRAWER = "from_drawer";

    private String pkg;
    private LinearLayout container;
    private long createdAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        IconButton.applyDesignScale(this);
        super.onCreate(savedInstanceState);
        createdAt = android.os.SystemClock.uptimeMillis();
        pkg = getIntent().getStringExtra(EXTRA_PKG);
        if (pkg == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_menu);
        findViewById(R.id.menu_items).setBackgroundColor(Theme.panel(this));
        getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        container = findViewById(R.id.menu_items);

        buildItems();
        final View panel = findViewById(R.id.menu_items);
        panel.post(() -> {
            panel.setTranslationX(panel.getWidth());
            panel.animate().translationX(0f).setDuration(190).start();
        });

        container.post(() -> {
            List<View> focusables = new ArrayList<>();
            collect(container, focusables);
            if (!focusables.isEmpty()) focusables.get(0).requestFocus();
        });
    }

    @Override
    public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
        // 长按打开菜单时，遥控器松手会再发一次 UP，忽略它，避免误触第一项
        if (android.os.SystemClock.uptimeMillis() - createdAt < 400) return true;
        return super.onKeyUp(keyCode, event);
    }

    private void collect(View v, List<View> out) {
        if (v.isFocusable() && v.getVisibility() == View.VISIBLE) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    private void buildItems() {
        container.removeAllViews();
        if (Widgets.PLACEHOLDER.equals(pkg)) {
            addItem(R.drawable.ic_list, "移动位置", R.id.action_move);
            addItem(R.drawable.ic_remove, "移出主页", R.id.action_widget_remove);
            return;
        }
        List<String> home = HomeStore.loadHome(this);
        boolean onHome = home.contains(pkg);
        boolean boot = HomeStore.isBoot(this, pkg);

        boolean fromDrawer = getIntent().getBooleanExtra(EXTRA_FROM_DRAWER, false);
        addItem(R.drawable.ic_to_top, fromDrawer ? "置顶桌面" : getString(R.string.to_top), R.id.action_top);
        if (!fromDrawer) addItem(R.drawable.ic_list, "上方留空一行", R.id.action_blank_row);
        if (!getIntent().getBooleanExtra(EXTRA_FROM_DRAWER, false)) {
            addItem(R.drawable.ic_list, "移动位置", R.id.action_move);
        }
        addItem(onHome ? R.drawable.ic_remove : R.drawable.ic_home,
                getString(onHome ? R.string.remove_home : R.string.add_home), R.id.action_home);
        addItem(R.drawable.ic_start,
                getString(boot ? R.string.boot_off : R.string.boot_on), R.id.action_boot);
        addItem(R.drawable.ic_uninstall, getString(R.string.uninstall), R.id.action_uninstall);
    }

    private void addItem(int iconRes, String text, int actionId) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        android.graphics.drawable.StateListDrawable rowBg = new android.graphics.drawable.StateListDrawable();
        rowBg.addState(new int[]{android.R.attr.state_focused},
                new android.graphics.drawable.ColorDrawable(Theme.rowSelected(this)));
        rowBg.addState(new int[]{android.R.attr.state_pressed},
                new android.graphics.drawable.ColorDrawable(Theme.rowSelected(this)));
        rowBg.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        row.setBackground(rowBg);
        row.setPadding(dp(30), 0, dp(30), 0);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(120)));

        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(52)));
        row.addView(iv);

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(42));
        tv.setTextColor(Theme.text(this));
        tv.setSingleLine(true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = dp(20);
        tv.setLayoutParams(tp);
        row.addView(tv);

        row.setOnClickListener(v -> onAction(actionId));
        container.addView(row);
    }

    private void onAction(int actionId) {
        List<String> home = HomeStore.loadHome(this);
        if (actionId == R.id.action_top) {
            home.remove(pkg);
            home.add(0, pkg);
            HomeStore.saveHome(this, home);
        } else if (actionId == R.id.action_home) {
            if (home.contains(pkg)) home.remove(pkg);
            else home.add(pkg);
            HomeStore.saveHome(this, home);
        } else if (actionId == R.id.action_boot) {
            List<String> boot = HomeStore.loadBoot(this);
            if (boot.contains(pkg)) boot.remove(pkg);
            else boot.add(pkg);
            HomeStore.saveBoot(this, boot);
        } else if (actionId == R.id.action_widget_remove) {
            home.remove(Widgets.PLACEHOLDER);
            HomeStore.saveHome(this, home);
            Widgets.remove(this);
        } else if (actionId == R.id.action_blank_row) {
            int index = home.indexOf(pkg);
            if (index < 0) {
                home.add(pkg);
                index = home.size() - 1;
            }
            // 先去掉尾部空位，避免无限增长
            while (!home.isEmpty() && HomeStore.EMPTY.equals(home.get(home.size() - 1))) {
                home.remove(home.size() - 1);
            }
            int rowStart = (Math.min(index, home.size() - 1) / 5) * 5;
            if (home.size() < 30) {
                for (int i = 0; i < 5; i++) home.add(rowStart, HomeStore.EMPTY);
            }
            HomeStore.saveHome(this, home);
        } else if (actionId == R.id.action_move) {
            UiState.pendingMove = pkg;
        } else if (actionId == R.id.action_uninstall) {
            try {
                Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (RuntimeException e) {
                Toast.makeText(this, "无法卸载", Toast.LENGTH_SHORT).show();
            }
        }
        finish();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
