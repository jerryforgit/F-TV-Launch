package com.jerry.ftvlaunch;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 读取所有可启动的应用。 */
public final class AppRepo {

    public static final class App {
        public final String pkg;
        public final String label;
        public final Drawable icon;

        App(String pkg, String label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    private AppRepo() {
    }

    public static List<App> load(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, App> byPkg = new LinkedHashMap<>();

        add(ctx, pm, byPkg, Intent.CATEGORY_LAUNCHER);
        add(ctx, pm, byPkg, Intent.CATEGORY_LEANBACK_LAUNCHER);

        List<App> list = new ArrayList<>(byPkg.values());
        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(list, (a, b) -> collator.compare(a.label, b.label));
        return list;
    }

    private static void add(Context ctx, PackageManager pm, Map<String, App> out, String category) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(category);
        List<ResolveInfo> resolved;
        try {
            resolved = pm.queryIntentActivities(intent, 0);
        } catch (RuntimeException e) {
            return;
        }
        String self = ctx.getPackageName();
        for (ResolveInfo ri : resolved) {
            if (ri.activityInfo == null || ri.activityInfo.packageName == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(self) || out.containsKey(pkg)) continue;
            if (!isEnabled(pm, pkg)) continue;
            CharSequence label = ri.loadLabel(pm);
            Drawable icon;
            try {
                icon = ri.loadIcon(pm);
            } catch (RuntimeException e) {
                icon = null;
            }
            out.put(pkg, new App(pkg, label == null ? pkg : label.toString(), icon));
        }
    }

    private static boolean isEnabled(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationInfo(pkg, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** 兼容只有 LEANBACK_LAUNCHER 的电视应用：getLaunchIntentForPackage 对它们会返回 null。 */
    public static Intent launchIntent(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
        ResolveInfo ri = pm.resolveActivity(probe, 0);
        if (ri == null || ri.activityInfo == null) {
            probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg);
            ri = pm.resolveActivity(probe, 0);
        }
        if (ri != null && ri.activityInfo != null) {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return i;
        }
        Intent fallback = pm.getLaunchIntentForPackage(pkg);
        if (fallback != null) fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return fallback;
    }

    public static String labelOf(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    public static Drawable iconOf(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    public static Set<String> installed(Context ctx) {
        Set<String> s = new HashSet<>();
        for (android.content.pm.ApplicationInfo ai :
                ctx.getPackageManager().getInstalledApplications(0)) {
            s.add(ai.packageName);
        }
        return s;
    }
}
