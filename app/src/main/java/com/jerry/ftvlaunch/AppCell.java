package com.jerry.ftvlaunch;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 应用格子：外层浅灰圆角，聚焦时浮起白色卡片并轻微放大。 */
class AppCell extends LinearLayout {

    private final LinearLayout card;
    private final ImageView icon;
    private final TextView label;
    private final Drawable focusedCard;
    private final Drawable normalCard;

    AppCell(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Theme.cell(c));
        setBackground(bg);
        setFocusable(false);
        setClickable(false);
        setClipChildren(false);
        setClipToPadding(false);
        setLayoutParams(new android.widget.GridView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));

        focusedCard = roundedFilled(dp(10), Theme.cellSelected(c));
        normalCard = new ColorDrawable(Color.TRANSPARENT);

        card = new LinearLayout(c);
        card.setOrientation(VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(normalCard);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        cp.setMargins(0, 0, 0, 0);
        card.setLayoutParams(cp);
        addView(card);

        icon = new ImageView(c);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(150), dp(150)));
        card.addView(icon);

        label = new TextView(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(22);
        label.setLayoutParams(lp);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(30));
        label.setTextColor(Theme.text(c));
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        card.addView(label);
    }

    private boolean highlighted;

    void setMoving(boolean moving) {
        this.moving = moving;
        applyCardState();
    }

    private boolean moving;

    private void applyCardState() {
        if (moving) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(10));
            boolean dark = Theme.isDark(getContext());
            g.setColor(dark ? Color.parseColor("#FF31353F") : Color.WHITE);
            g.setStroke(Math.round(3 * getResources().getDisplayMetrics().density), Color.WHITE);
            card.setBackground(g);
            card.setScaleX(1.04f);
            card.setScaleY(1.04f);
            card.setTranslationZ(0);
        } else {
            card.setBackground(highlighted ? focusedCard : normalCard);
            card.setScaleX(highlighted ? 1.04f : 1f);
            card.setScaleY(highlighted ? 1.04f : 1f);
            card.setTranslationZ(0);
        }
    }

    void bind(AppRepo.App app) {
        Drawable d = app.icon;
        if (d != null) icon.setImageDrawable(d);
        label.setText(app.label);
        label.setTextColor(Theme.text(getContext()));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Theme.cell(getContext()));
        setBackground(bg);
        ((GradientDrawable) focusedCard).setColor(Theme.cellSelected(getContext()));
    }

    /** GridView 会自己调用 setSelected，这里只做自己控制的“高亮”，避免被它重置。 */
    void setHighlight(boolean selected) {
        highlighted = selected;
        applyCardState();
    }

    private GradientDrawable roundedFilled(int radius, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radius);
        g.setColor(color);
        return g;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
