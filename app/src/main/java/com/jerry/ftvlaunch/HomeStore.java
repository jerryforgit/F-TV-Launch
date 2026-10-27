package com.jerry.ftvlaunch;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 主页与开机启动列表，格式与原版 home_data.txt / boot_data.txt 一致：[{"pg_name":"..."}]。 */
public final class HomeStore {

    private static final String TAG = "FTV2.Store";
    private static final String HOME_FILE = "home.json";
    private static final String BOOT_FILE = "boot.json";

    private HomeStore() {
    }

    public static final String EMPTY = "__empty__";

    public static List<String> loadHome(Context ctx) {
        List<String> list = read(ctx, HOME_FILE);
        if (list.isEmpty()) {
            list = new ArrayList<>();
            for (String p : ctx.getResources().getStringArray(R.array.default_home)) {
                if (!p.trim().isEmpty()) list.add(p.trim());
            }
            saveHome(ctx, list);
        }
        while (list.size() < 10) list.add(EMPTY);
        return list;
    }

    public static void saveHome(Context ctx, List<String> pkgs) {
        write(ctx, HOME_FILE, pkgs);
    }

    public static List<String> loadBoot(Context ctx) {
        return read(ctx, BOOT_FILE);
    }

    public static void saveBoot(Context ctx, List<String> pkgs) {
        write(ctx, BOOT_FILE, pkgs);
    }

    public static boolean isBoot(Context ctx, String pkg) {
        return loadBoot(ctx).contains(pkg);
    }

    private static List<String> read(Context ctx, String name) {
        List<String> list = new ArrayList<>();
        File f = new File(ctx.getFilesDir(), name);
        if (!f.exists()) return list;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String text = new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) return list;
            JSONArray arr;
            if (text.startsWith("[")) {
                arr = new JSONArray(text);
            } else {
                return list;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String p = o.optString("pg_name", "");
                if (!p.isEmpty()) list.add(p);
            }
        } catch (Exception e) {
            Log.w(TAG, "read " + name, e);
        }
        return list;
    }

    private static void write(Context ctx, String name, List<String> pkgs) {
        JSONArray arr = new JSONArray();
        for (String p : pkgs) {
            JSONObject o = new JSONObject();
            try {
                o.put("pg_name", p);
            } catch (Exception ignored) {
            }
            arr.put(o);
        }
        File f = new File(ctx.getFilesDir(), name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "write " + name, e);
        }
    }
}
