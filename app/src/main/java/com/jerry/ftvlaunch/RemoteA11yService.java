package com.jerry.ftvlaunch;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/** 没有 root 时的降级通道：返回/主页/最近任务 + 手势点击。 */
public class RemoteA11yService extends AccessibilityService {

    private static RemoteA11yService instance;

    static RemoteA11yService get() {
        return instance;
    }

    static boolean isEnabled() {
        return instance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    boolean tap(float x, float y) {
        try {
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x, y);
            android.accessibilityservice.GestureDescription.StrokeDescription stroke =
                    new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60);
            return dispatchGesture(
                    new android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build(),
                    null, null);
        } catch (Exception e) {
            return false;
        }
    }

    boolean global(int action) {
        return performGlobalAction(action);
    }
}
