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

    /** 文字输入：直接写进当前获得焦点的输入框。 */
    boolean setText(String text) {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            android.view.accessibility.AccessibilityNodeInfo f =
                    root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
            if (f == null) return false;
            android.os.Bundle args = new android.os.Bundle();
            args.putCharSequence(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text);
            return f.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } catch (Exception e) {
            return false;
        }
    }

    /** OK 键：点当前焦点（或第一个可点节点）。 */
    boolean clickFocused() {
        android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        android.view.accessibility.AccessibilityNodeInfo f = root.findFocus(
                android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        if (f == null) f = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null) f = firstClickable(root);
        while (f != null) {
            if (f.isClickable() && f.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return true;
            f = f.getParent();
        }
        return false;
    }

    private android.view.accessibility.AccessibilityNodeInfo firstClickable(
            android.view.accessibility.AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isClickable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo c = firstClickable(node.getChild(i));
            if (c != null) return c;
        }
        return null;
    }

    /** 方向键：拿不到按键权限时用滚动动作近似。 */
    boolean scroll(boolean forward) {
        android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        android.view.accessibility.AccessibilityNodeInfo f = root.findFocus(
                android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        if (f == null) f = root;
        int action = forward
                ? android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        if (f.performAction(action)) return true;
        return root.performAction(action);
    }

    boolean global(int action) {
        return performGlobalAction(action);
    }
}
