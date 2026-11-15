package com.jerry.ftvlaunch;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class AllAppsActivity extends Activity {

    static AllAppsActivity current;

    private GridView grid;
    private final List<AppRepo.App> items = new ArrayList<>();
    private Adapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int lastFocusedPos = 0;
    private int selectedPos = 0;
    private boolean longFired;

    private final Runnable longAction = () -> {
        int pos = focusedIndex();
        if (pos < 0) pos = lastFocusedPos;
        if (pos >= 0 && pos < items.size()) {
            longFired = true;
            openMenu(items.get(pos).pkg);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        IconButton.applyDesignScale(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_all);


        grid = findViewById(R.id.grid);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> launch(selectedPos));

    }

    private int focusedIndex() {
        return (selectedPos >= 0 && selectedPos < items.size()) ? selectedPos : -1;
    }

    private void markSelected(int pos) {
        int first = grid.getFirstVisiblePosition();
        for (int i = 0; i < grid.getChildCount(); i++) {
            View c = grid.getChildAt(i);
            if (c instanceof AppCell) ((AppCell) c).setHighlight(first + i == pos);
        }
    }

    /** 整行滚动：让选中那一行完整露出来，并且顶部永远是一整行（不会露出被切一半的格子）。 */
    private void ensureRowVisible(int pos) {
        if (items.isEmpty()) return;
        int rowStart = pos - pos % 5;
        int rowEnd = Math.min(rowStart + 4, items.size() - 1);
        int first = grid.getFirstVisiblePosition();
        int last = grid.getLastVisiblePosition();

        // 目标行还没进视口：直接滚到这一行的顶部（只有一个动画，不会先跳再滚）
        if (rowStart > last || rowEnd < first) {
            grid.smoothScrollToPositionFromTop(rowStart, 0, 140);
            return;
        }

        View top = null;
        View bottom = null;
        for (int i = 0; i < grid.getChildCount(); i++) {
            int p = first + i;
            if (p == rowStart) top = grid.getChildAt(i);
            if (p == rowEnd) bottom = grid.getChildAt(i);
        }
        if (top == null || bottom == null) return;

        int contentTop = grid.getPaddingTop();
        int contentBottom = grid.getHeight() - grid.getPaddingBottom();
        int pitch = rowPitch();
        int dy = 0;
        if (bottom.getBottom() > contentBottom) dy = pitch;
        else if (top.getTop() < contentTop) dy = -pitch;
        if (dy == 0) return;
        grid.scrollListBy(dy);
        grid.post(() -> markSelected(pos));
    }

    /** 补底部留白，让列表滚到底时顶部也是一整行（不会露出半行）。 */
    private void alignBottomPadding() {
        int rows = (items.size() + 4) / 5;
        int pitch = rowPitch();
        int viewport = grid.getHeight();
        if (rows <= 0 || pitch <= 0 || viewport <= 0) return;
        int content = rows * pitch - grid.getVerticalSpacing();
        int over = content - viewport;
        int pad = over <= 0 ? 0 : ((pitch - over % pitch) % pitch);
        if (grid.getPaddingBottom() == pad) return;
        grid.setPadding(grid.getPaddingLeft(), grid.getPaddingTop(),
                grid.getPaddingRight(), pad);
    }

    /** 一行占的高度（格子高 + 行间距）。 */
    private int rowPitch() {
        int h = 0;
        for (int i = 0; i < grid.getChildCount(); i++) {
            h = Math.max(h, grid.getChildAt(i).getHeight());
        }
        return h + grid.getVerticalSpacing();
    }

    private void launch(int position) {
        if (position < 0 || position >= items.size()) return;
        Intent i = AppRepo.launchIntent(this, items.get(position).pkg);
        if (i == null) {
            Toast.makeText(this, "无法启动", Toast.LENGTH_SHORT).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish();
    }

    private void openMenu(String pkg) {
        Intent i = new Intent(this, MenuActivity.class);
        i.putExtra(MenuActivity.EXTRA_PKG, pkg);
        i.putExtra(MenuActivity.EXTRA_FROM_DRAWER, true);
        startActivity(i);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
            int count = items.size();
            int col = selectedPos % 5;
            int next = selectedPos;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && col > 0) next--;
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && col < 4 && selectedPos + 1 < count) next++;
            else if (code == KeyEvent.KEYCODE_DPAD_UP && selectedPos - 5 >= 0) next -= 5;
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN && selectedPos + 5 < count) next += 5;
            if (next != selectedPos) {
                selectedPos = next;
                markSelected(next);
                final int target = next;
                grid.postDelayed(() -> markSelected(target), 80);
                ensureRowVisible(next);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_DOWN) {
            int pos = focusedIndex();
            if (pos < 0) pos = lastFocusedPos;
            if (pos >= 0 && pos < items.size()) openMenu(items.get(pos).pkg);
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
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) grid.postDelayed(() -> markSelected(selectedPos), 60);
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = this;
        findViewById(R.id.root_all).setBackgroundColor(Theme.bg(this));
        findViewById(R.id.all_title_bar).setBackgroundColor(Theme.bg(this));
        ((android.widget.TextView) findViewById(R.id.title)).setTextColor(Theme.text(this));
        items.clear();
        items.addAll(AppRepo.load(this));
        adapter.notifyDataSetChanged();
        grid.postDelayed(() -> {
            grid.requestFocus();
            markSelected(selectedPos);
            alignBottomPadding();
        }, 200);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (current == this) current = null;
    }

    private class Adapter extends BaseAdapter {
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
            return items.get(position).pkg.hashCode();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppCell cellView;
            if (convertView instanceof AppCell) {
                cellView = (AppCell) convertView;
            } else {
                cellView = new AppCell(AllAppsActivity.this);
            }
            cellView.bind(items.get(position));
            cellView.setHighlight(position == selectedPos);
            return cellView;
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
