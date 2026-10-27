package com.jerry.ftvlaunch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/** 移动模式的方向箭头：直接在 Canvas 上画，坐标由外部给定，不会有布局偏差。 */
public class ArrowsView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int left, top, right, bottom;
    private boolean up, down, leftArrow, rightArrow;
    private boolean hasRect;

    public ArrowsView(Context c, AttributeSet attrs) {
        super(c, attrs);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    void update(int l, int t, int rr, int b, boolean u, boolean d, boolean la, boolean ra, boolean dark) {
        left = l;
        top = t;
        right = rr;
        bottom = b;
        up = u;
        down = d;
        leftArrow = la;
        rightArrow = ra;
        hasRect = true;
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(dp(7));
        invalidate();
    }

    void clear() {
        hasRect = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!hasRect) return;
        int cx = (left + right) / 2;
        int cy = (top + bottom) / 2;
        int gap = dp(22);
        int size = dp(22);
        if (up) chevron(canvas, cx, top - gap - size / 2, true);
        if (down) chevron(canvas, cx, bottom + gap + size / 2, false);
        if (leftArrow) chevronH(canvas, left - gap - size / 2, cy, true);
        if (rightArrow) chevronH(canvas, right + gap + size / 2, cy, false);
    }

    /** 竖直方向的箭头：up=true 指向上 */
    private void chevron(Canvas c, int x, int y, boolean up) {
        int w = dp(22);
        Path p = new Path();
        if (up) {
            p.moveTo(x - w / 2f, y + w / 4f);
            p.lineTo(x, y - w / 4f);
            p.lineTo(x + w / 2f, y + w / 4f);
        } else {
            p.moveTo(x - w / 2f, y - w / 4f);
            p.lineTo(x, y + w / 4f);
            p.lineTo(x + w / 2f, y - w / 4f);
        }
        c.drawPath(p, paint);
    }

    /** 水平方向的箭头：left=true 指向左 */
    private void chevronH(Canvas c, int x, int y, boolean isLeft) {
        int w = dp(22);
        Path p = new Path();
        if (isLeft) {
            p.moveTo(x + w / 4f, y - w / 2f);
            p.lineTo(x - w / 4f, y);
            p.lineTo(x + w / 4f, y + w / 2f);
        } else {
            p.moveTo(x - w / 4f, y - w / 2f);
            p.lineTo(x + w / 4f, y);
            p.lineTo(x - w / 4f, y + w / 2f);
        }
        c.drawPath(p, paint);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
