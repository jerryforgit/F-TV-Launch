package com.jerry.ftvlaunch;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private GridView grid;
    private HomeAdapter adapter;
    private TextView tvDate, tvTime;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView hint;
    private boolean editMode;
    private int movingPos = -1;
    private int lastFocusedPos = 0;
    private int selectedPos = 0;
    private int lastSelected = 0;
    private ArrowsView moveArrows;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.CHINA);

    private static final String[] WEEK = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, 20_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        IconButton.applyDesignScale(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvDate = findViewById(R.id.tv_date);
        tvTime = findViewById(R.id.tv_time);
        grid = findViewById(R.id.grid);

        hint = findViewById(R.id.hint);
        moveArrows = findViewById(R.id.move_arrows);
        adapter = new HomeAdapter();
        grid.setAdapter(adapter);
        grid.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(android.widget.AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(android.widget.AbsListView view, int firstVisibleItem,
                                int visibleItemCount, int totalItemCount) {
                updateTopBarScrim();
            }
        });
        grid.getViewTreeObserver().addOnScrollChangedListener(this::updateTopBarScrim);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (editMode) {
                exitEditMode();
            } else if (selectedPos < adapter.getCount()) {
                Object o = adapter.items.get(Math.max(0, selectedPos));
                if (Widgets.PLACEHOLDER.equals(o)) {
                    if (adapter.widgetCell != null) Widgets.sendClick(adapter.widgetCell.hostView);
                } else {
                    launch(((AppRepo.App) o).pkg);
                }
            }
        });

        findViewById(R.id.btn_apps).setOnClickListener(v ->
                startActivity(new Intent(this, AllAppsActivity.class)));
        findViewById(R.id.btn_settings).setOnClickListener(v -> openSettings());
        findViewById(R.id.btn_settings).setOnLongClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        });
        findViewById(R.id.btn_network).setOnClickListener(v -> {
            Intent i = new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
            } catch (RuntimeException e) {
                Toast.makeText(this, "没有可用的 Wi-Fi 设置", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btn_transfer).setOnClickListener(v ->
                startActivity(new Intent(this, FileTransferActivity.class)));
        findViewById(R.id.btn_desktop_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btn_clean).setOnClickListener(v ->
                startActivity(new Intent(this, CleanActivity.class)));

        try {
            Widgets.host(this).startListening();
        } catch (RuntimeException ignored) {
        }
        applyTheme();
        updateClock();
        grid.post(() -> {
            focusFirstTile();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyTheme();
        if (Theme.isDark(this)) findViewById(R.id.root).setBackgroundColor(Theme.bg(this));
        else Wallpaper.apply(this, findViewById(R.id.root));
        try {
            Widgets.host(this).startListening();
        } catch (RuntimeException ignored) {
        }
        adapter.reload();
        lastSelected = getSharedPreferences("ui", MODE_PRIVATE).getInt("sel", lastSelected);
        if (lastSelected >= adapter.getCount()) lastSelected = Math.max(0, adapter.getCount() - 1);
        selectedPos = lastSelected;
        grid.postDelayed(() -> {
            grid.setSelection(lastSelected);
            markSelected(lastSelected);
            grid.requestFocus();
        }, 150);
        if (UiState.pendingMove != null) {
            enterEditMode(UiState.pendingMove);
            UiState.pendingMove = null;
        }
        grid.post(() -> {
            focusFirstTile();
        });
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !editMode) {
            grid.postDelayed(() -> {
                grid.setSelection(lastSelected);
                markSelected(lastSelected);
            }, 60);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            Widgets.host(this).startListening();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            Widgets.host(this).stopListening();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!editMode) lastSelected = Math.max(0, selectedPos);
        handler.removeCallbacks(tick);
    }

    private boolean longFired;

    private final Runnable longAction = () -> {
        String pkg = focusedPkg();
        if (pkg != null) {
            longFired = true;
            openMenu(pkg);
        }
    };

    private String focusedPkg() {
        for (int i = 0; i < adapter.getCount(); i++) {
            View child = grid.getChildAt(i);
            if (child != null && child.hasFocus()) {
                AppRepo.App a = adapter.appAt(i);
                return a == null ? Widgets.PLACEHOLDER : a.pkg;
            }
        }
        return null;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();

        if (code == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (editMode) {
                exitEditMode();
            } else {
                String pkg = focusedPkg();
                if (pkg == null) {
                    int idx = Math.max(0, selectedPos);
                    if (idx >= 0 && idx < adapter.getCount()) {
                        Object o = adapter.items.get(idx);
                        pkg = Widgets.PLACEHOLDER.equals(o) ? Widgets.PLACEHOLDER
                                : ((AppRepo.App) o).pkg;
                    }
                }
                if (pkg != null) openMenu(pkg);
            }
            return true;
        }

        if (editMode) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) { moveBy(-1, 0); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { moveBy(1, 0); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_UP) { moveBy(0, -1); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) { moveBy(0, 1); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                        || code == KeyEvent.KEYCODE_BACK) {
                    exitEditMode();
                    return true;
                }
            }
            return true;
        }

        // 网格方向键完全自己管，避免触摸模式下 GridView 的 selected 不生效
        if (!editMode && event.getAction() == KeyEvent.ACTION_DOWN && grid.hasFocus()
                && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
            int count = adapter.getCount();
            int col = selectedPos % 5;
            int next = selectedPos;
            int dir = 0;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && col > 0) dir = -1;
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && col < 4 && selectedPos + 1 < count) dir = 1;
            else if (code == KeyEvent.KEYCODE_DPAD_UP && selectedPos - 5 >= 0) dir = -5;
            else if (code == KeyEvent.KEYCODE_DPAD_UP && selectedPos < 5) {
                focusTopBarForColumn(selectedPos);
                return true;
            }
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN && selectedPos + 5 < count) dir = 5;
            if (dir != 0) {
                int t = nextNonEmpty(selectedPos + dir, dir);
                if (t >= 0) {
                    next = t;
                } else if (dir < 0) {
                    // 上面只剩空位：直接进顶栏
                    focusTopBarForColumn(selectedPos);
                    return true;
                }
            }
            if (next != selectedPos) {
                selectedPos = next;
                getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("sel", next).apply();
                markSelected(next);
                grid.setSelection(next);
            }
            return true;
        }

        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)
                && event.getAction() == KeyEvent.ACTION_UP
                && !editMode && grid.hasFocus() && grid.getSelectedItemPosition() < 0
                && selectedPos >= 0 && selectedPos < adapter.getCount()) {
            AppRepo.App a = adapter.appAt(selectedPos);
            if (a != null) launch(a.pkg);
            else if (adapter.widgetCell != null) Widgets.sendClick(adapter.widgetCell.hostView);
            return true;
        }

        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    longFired = false;
                    handler.postDelayed(longAction, 700);
                } else {
                    if (!longFired) {
                        handler.removeCallbacks(longAction);
                        longAction.run();
                    }
                    return true;
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(longAction);
                if (longFired) {
                    longFired = false;
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return super.onKeyDown(keyCode, event);
    }

    private void focusFirstTile() {
        grid.postDelayed(() -> {
            if (adapter.getCount() == 0) return;
            grid.setSelection(0);
            grid.requestFocus();
        }, 200);
    }

    private void enterEditMode(String pkg) {
        java.util.List<String> home = HomeStore.loadHome(this);
        int index = home.indexOf(pkg);
        if (index < 0) return;
        editMode = true;
        movingPos = index;
        hint.setText("移动中：方向键移动一格，OK / BACK 确定");
        hint.setVisibility(View.VISIBLE);
        adapter.reload();
        grid.setSelection(index);
        grid.requestFocus();
        grid.postDelayed(() -> {
            markSelected(movingPos);
            updateMoveVisuals();
        }, 150);
    }

    private void exitEditMode() {
        if (editMode && movingPos >= 0) {
            lastSelected = movingPos;
            selectedPos = movingPos;
            getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("sel", movingPos).apply();
        }
        editMode = false;
        movingPos = -1;
        hint.setVisibility(View.GONE);
        moveArrows.setVisibility(View.GONE);
        findViewById(R.id.top_bar).setAlpha(1f);
        for (int i = 0; i < grid.getChildCount(); i++) {
            View c = grid.getChildAt(i);
            c.setAlpha(1f);
            if (c instanceof AppCell) ((AppCell) c).setMoving(false);
        }
        markSelected(lastSelected);
    }

    private void moveBy(int dx, int dy) {
        java.util.List<String> home = HomeStore.loadHome(this);
        if (movingPos < 0 || movingPos >= home.size()) return;
        int col = movingPos % 5;
        int target;
        if (dx != 0) {
            if (dx < 0 && col == 0) return;
            if (dx > 0 && col == 4) return;
            target = movingPos + dx;
        } else {
            target = movingPos + dy * 5;
        }
        if (target < 0) return;
        while (home.size() <= target && home.size() < 15) home.add(HomeStore.EMPTY);
        if (target >= home.size()) return;
        String pkg = home.remove(movingPos);
        home.add(target, pkg);
        HomeStore.saveHome(this, home);
        movingPos = target;
        adapter.reload();
        grid.setSelection(target);
        grid.postDelayed(() -> {
            markSelected(movingPos);
            updateMoveVisuals();
        }, 150);
    }

    /** 当前格子高亮 + 四向箭头，其余变暗。 */
    private void updateMoveVisuals() {
        View cell = grid.getChildAt(movingPos);
        if (cell == null) {
            moveArrows.setVisibility(View.GONE);
            return;
        }
        for (int i = 0; i < grid.getChildCount(); i++) {
            View c = grid.getChildAt(i);
            c.setAlpha(i == movingPos ? 1f : 0.25f);
        }
        findViewById(R.id.top_bar).setAlpha(0.25f);

        int[] gridLoc = new int[2];
        grid.getLocationInWindow(gridLoc);
        int[] rootLoc = new int[2];
        ((View) cell.getRootView()).getLocationInWindow(rootLoc);
        int left = gridLoc[0] - rootLoc[0] + cell.getLeft();
        int top = gridLoc[1] - rootLoc[1] + cell.getTop();
        int w = cell.getWidth();
        int h = cell.getHeight();

        int count = adapter.getCount();
        int col = movingPos % 5;
        boolean canLeft = col > 0;
        boolean canRight = col < 4 && movingPos + 1 < count;
        boolean canUp = movingPos - 5 >= 0;
        boolean canDown = movingPos + 5 < count;
        updateArrows(left, top, left + w, top + h, canUp, canDown, canLeft, canRight);
    }

    private void applyTheme() {
        findViewById(R.id.root).setBackgroundColor(Theme.bg(this));
        updateTopBarScrim();
        tvDate.setTextColor(Theme.text(this));
        tvTime.setTextColor(Theme.text(this));
        for (int id : new int[]{R.id.btn_apps, R.id.btn_settings, R.id.btn_network,
                R.id.btn_clean, R.id.btn_desktop_settings, R.id.btn_transfer}) {
            View v = findViewById(id);
            if (v instanceof IconButton) ((IconButton) v).applyTheme(this);
        }
        View bar = findViewById(R.id.top_bar);
        bar.setElevation(dp(12));
        bar.setOutlineProvider(null);
    }

    private void markSelected() {
        markSelected(Math.max(0, selectedPos));
    }

    private boolean isEmptySlot(int pos) {
        return pos >= 0 && pos < adapter.items.size() && HomeStore.EMPTY.equals(adapter.items.get(pos));
    }

    /** 方向键跳过空位，落到下一个有内容的格子。 */
    private int nextNonEmpty(int from, int step) {
        int i = from;
        while (i >= 0 && i < adapter.getCount()) {
            if (!isEmptySlot(i)) return i;
            i += step;
        }
        return -1;
    }

    private void markSelected(int pos) {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View c = grid.getChildAt(i);
            boolean moving = editMode && i == movingPos;
            if (c instanceof AppCell) {
                ((AppCell) c).setHighlight(i == pos && !editMode);
                ((AppCell) c).setMoving(moving);
            } else if (c instanceof WidgetCell) {
                ((WidgetCell) c).setHighlight(i == pos);
            }
        }
    }

    private void updateArrows(int l, int t, int r, int b, boolean u, boolean d, boolean la, boolean ra) {
        moveArrows.update(l, t, r, b, u, d, la, ra, Theme.isDark(this));
        moveArrows.setVisibility(View.VISIBLE);
        moveArrows.bringToFront();
    }

    /** 第一行某一列往上：按列位置等比映射到顶栏按钮，避免右边几列都跳到最后一个。 */
    /** 只有网格滚动了才给顶栏加半透明底，默认完全透明。 */
    private void updateTopBarScrim() {
        View bar = findViewById(R.id.top_bar);
        if (bar == null) return;
        boolean scrolled = grid.getChildCount() > 0 && grid.getChildAt(0).getTop() < grid.getPaddingTop();
        if (scrolled) {
            bar.setBackgroundColor(Theme.isDark(this)
                    ? android.graphics.Color.parseColor("#D014161B")
                    : android.graphics.Color.parseColor("#D0D3DCEA"));
        } else {
            bar.setBackground(null);
        }
    }

    private void focusTopBarForColumn(int pos) {
        int[] ids = {R.id.btn_apps, R.id.btn_settings, R.id.btn_network, R.id.btn_clean,
                R.id.btn_desktop_settings, R.id.btn_transfer};
        int col = pos % 5;
        int index = Math.min(col, ids.length - 1);
        View v = findViewById(ids[index]);
        if (v != null) v.requestFocus();
    }

    private void updateClock() {
        Calendar c = Calendar.getInstance();
        tvTime.setText(timeFmt.format(new Date()));
        tvDate.setText(String.format(Locale.CHINA, "%d月%d日 %s",
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), WEEK[c.get(Calendar.DAY_OF_WEEK) - 1]));
    }

    private void launch(String pkg) {
        Intent i = AppRepo.launchIntent(this, pkg);
        if (i == null) {
            Toast.makeText(this, "无法启动：" + pkg, Toast.LENGTH_SHORT).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            Toast.makeText(this, "启动失败：" + pkg, Toast.LENGTH_SHORT).show();
        }
    }

    private void openMenu(String pkg) {
        Intent i = new Intent(this, MenuActivity.class);
        i.putExtra(MenuActivity.EXTRA_PKG, pkg);
        startActivity(i);
    }

    private void openSettings() {
        Intent i = new Intent(android.provider.Settings.ACTION_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            Toast.makeText(this, "没有可用的设置应用", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- 网络面板 ----------------

    // ---------------- 网格 ----------------

    private class HomeAdapter extends BaseAdapter {
        static final int TYPE_APP = 0;
        static final int TYPE_WIDGET = 1;
        final List<Object> items = new ArrayList<>();
        WidgetCell widgetCell;

        void reload() {
            List<AppRepo.App> all = AppRepo.load(MainActivity.this);
            List<String> home = HomeStore.loadHome(MainActivity.this);
            items.clear();
            for (String pkg : home) {
                if (HomeStore.EMPTY.equals(pkg)) {
                    items.add(HomeStore.EMPTY);
                    continue;
                }
                if (Widgets.PLACEHOLDER.equals(pkg)) {
                    if (Widgets.has(MainActivity.this)) items.add(Widgets.PLACEHOLDER);
                    else items.add(HomeStore.EMPTY);
                    continue;
                }
                for (AppRepo.App a : all) {
                    if (a.pkg.equals(pkg)) {
                        items.add(a);
                        break;
                    }
                }
            }
            notifyDataSetChanged();
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return Widgets.PLACEHOLDER.equals(items.get(position)) ? TYPE_WIDGET : TYPE_APP;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            if (position < 0 || position >= items.size()) return position;
            Object o = items.get(position);
            if (o instanceof AppRepo.App) return ((AppRepo.App) o).pkg.hashCode();
            return o == null ? 0 : o.hashCode() + 1000003L;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Object o = items.get(position);
            if (HomeStore.EMPTY.equals(o)) {
                View blank = new View(MainActivity.this);
                blank.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));
                blank.setFocusable(false);
                blank.setClickable(false);
                return blank;
            }
            if (Widgets.PLACEHOLDER.equals(o)) {
                if (widgetCell == null || widgetCell.darkAtBuild != Theme.isDark(MainActivity.this)) {
                    widgetCell = new WidgetCell(MainActivity.this);
                }
                return widgetCell;
            }
            AppCell cellView;
            if (convertView instanceof AppCell) {
                cellView = (AppCell) convertView;
            } else {
                cellView = new AppCell(MainActivity.this);
            }
            cellView.bind((AppRepo.App) o);
            return cellView;
        }

        AppRepo.App appAt(int position) {
            Object o = items.get(position);
            return o instanceof AppRepo.App ? (AppRepo.App) o : null;
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
