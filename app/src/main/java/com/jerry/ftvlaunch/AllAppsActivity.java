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
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> launch(selectedPos));

    }

    private int focusedIndex() {
        return (selectedPos >= 0 && selectedPos < items.size()) ? selectedPos : -1;
    }

    private void markSelected(int pos) {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View c = grid.getChildAt(i);
            if (c instanceof AppCell) ((AppCell) c).setHighlight(i == pos);
        }
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
        if (event.getAction() == KeyEvent.ACTION_DOWN && grid.hasFocus()
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
                grid.setSelection(next);
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
    protected void onResume() {
        super.onResume();
        findViewById(R.id.root_all).setBackgroundColor(Theme.bg(this));
        ((android.widget.TextView) findViewById(R.id.title)).setTextColor(Theme.text(this));
        items.clear();
        items.addAll(AppRepo.load(this));
        adapter.notifyDataSetChanged();
        grid.postDelayed(() -> {
            grid.setSelection(selectedPos);
            grid.requestFocus();
            markSelected(selectedPos);
        }, 200);
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
            return cellView;
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
