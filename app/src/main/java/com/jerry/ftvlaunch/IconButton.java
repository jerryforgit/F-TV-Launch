package com.jerry.ftvlaunch;

import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 顶栏的图标+文字按钮，等价于原版用的 TextViewDrawable。 */
public class IconButton extends LinearLayout {

    private final ImageView image;
    private final TextView label;

    public IconButton(Context c) {
        this(c, null);
    }

    public IconButton(Context c, AttributeSet attrs) {
        super(c, attrs);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setFocusable(true);
        setClickable(true);

        setPadding(dp(4), dp(4), dp(4), dp(10));

        image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LayoutParams ip = new LayoutParams(dp(62), dp(62));
        image.setLayoutParams(ip);
        addView(image);

        label = new TextView(c);
        LayoutParams tp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(4);
        label.setLayoutParams(tp);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(20));
        label.setTextColor(Color.parseColor("#FF808080"));
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f); // 原版：聚焦时淡入，同时图标上移
        addView(label);

        image.setTranslationY(dp(8));
        setOnFocusChangeListener((v, hasFocus) -> {
            label.animate().alpha(hasFocus ? 1f : 0f).setDuration(120).start();
            image.animate().translationY(hasFocus ? 0 : dp(8)).setDuration(140).start();
            setBackgroundResource(hasFocus ? R.drawable.icon_focus_bg : 0);
        });

        if (attrs != null) {
            TypedArray a = c.obtainStyledAttributes(attrs,
                    new int[]{android.R.attr.icon, android.R.attr.text});
            int icon = a.getResourceId(0, 0);
            CharSequence text = a.getText(1);
            a.recycle();
            if (icon != 0) image.setImageResource(icon);
            if (text != null) {
                label.setText(text);
                fitLabel();
            }
        }
    }

    public void setLabel(CharSequence s) {
        label.setText(s);
        fitLabel();
    }

    /** 文字比按钮还宽时自动缩小，避免被裁掉两边。 */
    private void fitLabel() {
        float size = dp(19);
        int avail = dp(80);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size);
        for (int i = 0; i < 8; i++) {
            label.measure(android.view.View.MeasureSpec.UNSPECIFIED,
                    android.view.View.MeasureSpec.UNSPECIFIED);
            if (label.getMeasuredWidth() <= avail) break;
            size -= 1;
            if (size < dp(11)) break;
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size);
        }
    }

    public void applyTheme(android.content.Context c) {
        int tint = Theme.iconTint(c);
        image.setColorFilter(tint);
        label.setTextColor(tint);
    }

    public void setIcon(int res) {
        image.setImageResource(res);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 让 1dp == 1920 宽屏幕上的 1px，复刻原版 autosize 的效果。 */
    static float originalDensity = 0f;
    static float originalScaledDensity = 0f;

    public static void applyDesignScale(Activity a) {
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        if (originalDensity == 0f) {
            originalDensity = dm.density;
            originalScaledDensity = dm.scaledDensity;
        }
        float d = dm.widthPixels / 1920f;
        dm.density = d;
        dm.scaledDensity = d;
    }
}
