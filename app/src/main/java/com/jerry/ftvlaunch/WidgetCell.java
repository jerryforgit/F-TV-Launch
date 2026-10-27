package com.jerry.ftvlaunch;

import android.appwidget.AppWidgetHostView;
import android.content.Context;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridView;

/** 网格里的控件格子：外观和应用格子一致，可被选中/移动。 */
class WidgetCell extends FrameLayout {

    AppWidgetHostView hostView;
    boolean darkAtBuild;

    WidgetCell(Context c) {
        super(c);
        darkAtBuild = Theme.isDark(c);
        setBackground(null);
        setFocusable(false);
        setClickable(false);
        setLayoutParams(new GridView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));

        hostView = Widgets.createView((android.app.Activity) c);
        if (hostView != null) {
            int side = dp(284);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(side, side);
            lp.gravity = android.view.Gravity.CENTER;
            addView(hostView, lp);
            try {
                int w = dp(322);
                int h = dp(292);
                hostView.updateAppWidgetSize(new android.os.Bundle(), w, w, h, h);
            } catch (RuntimeException ignored) {
            }
            hostView.setOnLongClickListener(v -> {
                android.content.Intent i = new android.content.Intent(getContext(), MenuActivity.class);
                i.putExtra(MenuActivity.EXTRA_PKG, Widgets.PLACEHOLDER);
                getContext().startActivity(i);
                return true;
            });
        }
    }

    void setHighlight(boolean selected) {
        setBackgroundResource(selected ? R.drawable.widget_border : 0);
        animate()
                .scaleX(selected ? 1.04f : 1f)
                .scaleY(selected ? 1.04f : 1f)
                .setDuration(140)
                .start();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
