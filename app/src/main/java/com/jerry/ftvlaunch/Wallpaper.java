package com.jerry.ftvlaunch;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 桌面背景：把用户选的图片存到 files/wallpaper.jpg，再叠一层浅色蒙版保证文字可读。 */
final class Wallpaper {

    private static final String FILE = "wallpaper.jpg";

    private Wallpaper() {
    }

    static File file(Activity a) {
        return new File(a.getFilesDir(), FILE);
    }

    static boolean exists(Activity a) {
        return file(a).exists();
    }

    static boolean saveFrom(Activity a, Uri uri) {
        try (InputStream in = a.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(file(a))) {
            if (in == null) return false;
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Bitmap decode(Activity a) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file(a).getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 1920) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(file(a).getAbsolutePath(), opts);
        } catch (Throwable t) {
            return null;
        }
    }

    static void clear(Activity a) {
        //noinspection ResultOfMethodCallIgnored
        file(a).delete();
    }

    static void apply(Activity a, View root) {
        if (!exists(a)) {
            root.setBackgroundColor(a.getResources().getColor(R.color.bg));
            return;
        }
        Bitmap bitmap = decode(a);
        if (bitmap == null) {
            root.setBackgroundColor(a.getResources().getColor(R.color.bg));
            return;
        }
        Drawable[] layers = new Drawable[]{
                new BitmapDrawable(a.getResources(), bitmap),
                new ColorDrawable(Color.parseColor("#8CD3DCEA"))
        };
        root.setBackground(new LayerDrawable(layers));
    }
}
