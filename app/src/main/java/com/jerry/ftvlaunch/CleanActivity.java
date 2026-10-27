package com.jerry.ftvlaunch;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

/** 清理后台：先播扫把动画，再显示真实释放的内存。 */
public class CleanActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        IconButton.applyDesignScale(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_clean);

        final ImageView icon = findViewById(R.id.clean_icon);
        final TextView text = findViewById(R.id.clean_text);

        // 扫把左右扫动
        icon.setTranslationX(-90f);
        icon.animate().translationX(90f).setDuration(320)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> icon.animate().translationX(-90f).setDuration(320)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .withEndAction(() -> icon.animate().translationX(90f).setDuration(320)
                                .withEndAction(() -> icon.animate().translationX(0f).setDuration(200).start())
                                .start())
                        .start())
                .start();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            String result = clean();
            text.setText(result);
            text.animate().alpha(1f).setDuration(200).start();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                findViewById(android.R.id.content).animate().alpha(0f).setDuration(260)
                        .withEndAction(this::finish).start();
            }, 1100);
        }, 1100);
    }

    private String clean() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return "清理完成";
        long before = freeMem(am);
        int killed = 0;
        List<ApplicationInfo> apps = getPackageManager().getInstalledApplications(0);
        for (ApplicationInfo ai : apps) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (ai.packageName.equals(getPackageName())) continue;
            try {
                am.killBackgroundProcesses(ai.packageName);
                killed++;
            } catch (RuntimeException ignored) {
            }
        }
        long freed = Math.max(0, freeMem(am) - before);
        return freed >= 1024 * 1024
                ? String.format("已清理 %d 个后台应用\n释放 %.0f MB", killed, freed / 1024f / 1024f)
                : String.format("已清理 %d 个后台应用", killed);
    }

    private long freeMem(ActivityManager am) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem;
    }
}
