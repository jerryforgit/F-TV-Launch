package com.jerry.ftvlaunch;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 手机扫码传文件到电视，APK 可以直接安装。 */
public class FileTransferActivity extends Activity implements MiniHttpServer.Actions {

    private static final int PORT = 8088;

    private MiniHttpServer server;
    private File dir;
    private LinearLayout filesBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        IconButton.applyDesignScale(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfer);

        findViewById(R.id.root_transfer).setBackgroundColor(Theme.bg(this));
        ((TextView) findViewById(R.id.url)).setTextColor(Theme.text(this));

        dir = new File(getExternalFilesDir(null), "uploads");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        filesBox = findViewById(R.id.files);
        findViewById(R.id.btn_browse).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, ImagePickerActivity.class)));

        String ip = localIp();
        String url = "http://" + (ip == null ? "0.0.0.0" : ip) + ":" + PORT;
        ((TextView) findViewById(R.id.url)).setText(url);
        showQr(url);

        server = new MiniHttpServer(PORT, dir, (name, file) -> runOnUiThread(() -> {
            refresh();
            Toast.makeText(this, "收到：" + name, Toast.LENGTH_SHORT).show();
        }), this);
        server.start();
        refresh();
        filesBox.post(this::focusFirst);
    }

    private void focusFirst() {
        if (filesBox.getChildCount() > 0) filesBox.getChildAt(0).requestFocus();
    }

    private void showQr(String text) {
        try {
            int size = 700;
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            ((ImageView) findViewById(R.id.qr)).setImageBitmap(bmp);
        } catch (Exception e) {
            ((TextView) findViewById(R.id.url)).setText(text + "\n（二维码生成失败）");
        }
    }

    private void refresh() {
        filesBox.removeAllViews();
        File[] list = dir.listFiles();
        List<File> files = new ArrayList<>();
        if (list != null) Collections.addAll(files, list);
        files.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        if (files.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有文件，用手机扫左边的二维码上传");
            empty.setTextColor(Theme.subText(this));
            empty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(26));
            filesBox.addView(empty);
            return;
        }
        for (File f : files) {
            filesBox.addView(row(f));
        }
    }

    private View row(File f) {
        String lower = f.getName().toLowerCase(Locale.US);
        boolean apk = lower.endsWith(".apk");
        boolean image = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp");
        String action = apk ? "安装" : (image ? "设为背景" : "—");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(24), 0, dp(24), 0);
        row.setBackground(Theme.rowBackground(this));
        row.setFocusable(true);
        row.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(100));
        lp.bottomMargin = dp(12);
        row.setLayoutParams(lp);

        TextView name = new TextView(this);
        name.setText(f.getName() + "   " + (f.length() / 1024) + " KB");
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(28));
        name.setTextColor(Theme.text(this));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        name.setLayoutParams(nlp);
        row.addView(name);

        TextView act = new TextView(this);
        act.setText(action);
        act.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(26));
        act.setTextColor(action.equals("—") ? Theme.subText(this) : Theme.text(this));
        act.setPadding(dp(16), dp(8), dp(16), dp(8));
        row.addView(act);

        row.setOnClickListener(v -> {
            if (apk) install(f);
            else if (image) {
                if (setWallpaper(f.getName())) {
                    Toast.makeText(this, "已设为桌面背景，回桌面可见", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "设置失败", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "这个类型没有可用操作", Toast.LENGTH_SHORT).show();
            }
        });
        return row;
    }

    /** 复制到系统下载目录再用系统安装器打开（Android 10+ 的 Scoped Storage 要求）。 */
    private void install(File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, apk.getName());
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IllegalStateException("insert failed");
                try (FileInputStream in = new FileInputStream(apk);
                     OutputStream out = getContentResolver().openOutputStream(uri)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0 && out != null) out.write(buf, 0, n);
                }
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } else {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        } catch (Exception e) {
            Toast.makeText(this, "安装失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------- 网页端动作 ----------------

    @Override
    public String filesJson() {
        StringBuilder sb = new StringBuilder("{\"files\":[");
        File[] list = dir.listFiles();
        List<File> files = new ArrayList<>();
        if (list != null) Collections.addAll(files, list);
        files.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        boolean first = true;
        for (File f : files) {
            String n = f.getName();
            String lower = n.toLowerCase(Locale.US);
            String kind = lower.endsWith(".apk") ? "apk"
                    : (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                    || lower.endsWith(".webp") ? "image" : "file");
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":\"").append(escape(n)).append("\",\"size\":").append(f.length())
                    .append(",\"kind\":\"").append(kind).append("\"}");
        }
        return sb.append("]}").toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public boolean setWallpaper(String name) {
        if (name == null || name.contains("/") || name.contains("..")) return false;
        File src = new File(dir, name);
        if (!src.exists()) return false;
        File dst = new File(getFilesDir(), "wallpaper.jpg");
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean install(String name) {
        if (name == null || name.contains("/") || name.contains("..")) return false;
        File f = new File(dir, name);
        if (!f.exists() || !name.toLowerCase(Locale.US).endsWith(".apk")) return false;
        install(f);
        return true;
    }

    private String localIp() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp()) continue;
                if (!nic.getName().startsWith("wlan") && !nic.getName().startsWith("eth")) continue;
                for (InetAddress addr : Collections.list(nic.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (server != null) server.stop();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
