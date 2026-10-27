package com.jerry.ftvlaunch;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jcifs.CIFSContext;
import jcifs.smb.SmbAuthException;
import jcifs.smb.SmbFile;

/** 遥控器可操作的背景选择器：本机图片 + SMB 局域网文件（显示全部非隐藏文件，图片可设为背景）。 */
public class ImagePickerActivity extends Activity {

    private static final String PREFS = "smb";
    private static final String KEY_URL = "url";
    private static final String KEY_USER = "user";
    private static final String KEY_PASS = "pass";

    private final List<Entry> items = new ArrayList<>();
    private GridView grid;
    private Adapter adapter;
    private TextView title;
    private TextView empty;
    private TextView netButton;

    private boolean smbMode;
    private String smbUrl;
    private CIFSContext smbCtx;
    private String smbUser = "";
    private String smbPass = "";

    private final java.util.Map<String, Bitmap> thumbs = new java.util.HashMap<>();
    private final java.util.Set<String> thumbLoading = new java.util.HashSet<>();
    private final java.util.concurrent.ExecutorService thumbPool =
            java.util.concurrent.Executors.newFixedThreadPool(3);

    private static class Entry {
        String name;
        boolean dir;
        boolean image;
        boolean apk;
        File local;
        String smbUrl;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        IconButton.applyDesignScale(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_picker);
        findViewById(R.id.root_picker).setBackgroundColor(Theme.bg(this));

        title = findViewById(R.id.picker_title);
        empty = findViewById(R.id.picker_empty);
        netButton = findViewById(R.id.picker_network);
        title.setTextColor(Theme.text(this));
        empty.setTextColor(Theme.subText(this));
        netButton.setTextColor(Theme.text(this));
        netButton.setOnClickListener(v ->
                startActivityForResult(new android.content.Intent(this, SmbSetupActivity.class), 20));

        smbUser = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_USER, "");
        smbPass = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PASS, "");

        grid = findViewById(R.id.picker_grid);
        grid.setBackgroundColor(Theme.bg(this));
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> onEntry(items.get(position)));

        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 10);
        }
        loadLocal();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 20 && resultCode == RESULT_OK) {
            smbUser = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_USER, "");
            smbPass = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PASS, "");
            String u = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, "");
            if (!u.isEmpty()) connect(u, smbUser, smbPass);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (!smbMode) loadLocal();
    }

    // ---------------- 本机 ----------------

    private void loadLocal() {
        smbMode = false;
        title.setText("本机图片 / APK（图片设为背景，APK 安装；右上角浏览局域网）");
        items.clear();
        collect(new File(getExternalFilesDir(null), "uploads"));
        collect(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ""));
        collect(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), ""));
        collect(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ""));
        refreshGrid();
    }

    private void collect(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) {
                collect(f);
                continue;
            }
            String n = f.getName();
            if (isImage(n) || isApk(n)) {
                Entry e = new Entry();
                e.name = n;
                e.image = isImage(n);
                e.local = f;
                items.add(e);
            }
            if (items.size() >= 300) return;
        }
    }

    // ---------------- SMB ----------------

    private void showLocationDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, pad);

        EditText url = new EditText(this);
        url.setHint("smb://192.168.1.10/share  或 192.168.1.10/share");
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, ""));
        box.addView(url);

        EditText user = new EditText(this);
        user.setHint("账号（可空，匿名/guest 不填）");
        user.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_USER, ""));
        box.addView(user);

        EditText pass = new EditText(this);
        pass.setHint("密码（可空）");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PASS, ""));
        box.addView(pass);

        new AlertDialog.Builder(this)
                .setTitle("局域网位置（SMB）")
                .setView(box)
                .setPositiveButton("连接", (d, w) -> {
                    String u = url.getText().toString().trim();
                    if (u.isEmpty()) return;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_URL, u)
                            .putString(KEY_USER, user.getText().toString())
                            .putString(KEY_PASS, pass.getText().toString())
                            .apply();
                    connect(u, user.getText().toString(), pass.getText().toString());
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("扫描局域网", (d, w) -> scanLan())
                .show();
    }

    /** 扫描本机所在 /24 网段里开着 445 端口的主机。 */
    private void scanLan() {
        String ip = localIp();
        if (ip == null) {
            Toast.makeText(this, "没有连上网络", Toast.LENGTH_SHORT).show();
            return;
        }
        final String prefix = ip.substring(0, ip.lastIndexOf('.') + 1);
        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("正在扫描 " + prefix + "0/24 …")
                .setMessage("查找局域网里的 SMB 服务")
                .setCancelable(true)
                .create();
        progress.show();

        new Thread(() -> {
            java.util.List<String> hosts = java.util.Collections.synchronizedList(new ArrayList<>());
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(32);
            for (int i = 1; i <= 254; i++) {
                final String host = prefix + i;
                pool.submit(() -> {
                    try (java.net.Socket s = new java.net.Socket()) {
                        s.connect(new java.net.InetSocketAddress(host, 445), 400);
                        hosts.add(host);
                    } catch (Exception ignored) {
                    }
                });
            }
            pool.shutdown();
            try {
                pool.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            hosts.sort((a, b) -> {
                int ia = Integer.parseInt(a.substring(a.lastIndexOf('.') + 1));
                int ib = Integer.parseInt(b.substring(b.lastIndexOf('.') + 1));
                return Integer.compare(ia, ib);
            });
            runOnUiThread(() -> {
                progress.dismiss();
                if (hosts.isEmpty()) {
                    Toast.makeText(this, "没找到 SMB 主机（445 端口）", Toast.LENGTH_LONG).show();
                    return;
                }
                String[] arr = hosts.toArray(new String[0]);
                new AlertDialog.Builder(this)
                        .setTitle("发现 " + arr.length + " 台 SMB 主机")
                        .setItems(arr, (d, w) -> listShares(arr[w]))
                        .setNegativeButton("取消", null)
                        .show();
            });
        }).start();
    }

    private void listShares(String host) {
        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("连接 " + host)
                .setMessage("正在读取共享列表…")
                .setCancelable(true)
                .create();
        progress.show();
        new Thread(() -> {
            try {
                SmbFile[] shares = SmbBrowser.list(ensureCtx(), "smb://" + host + "/");
                java.util.List<String> names = new ArrayList<>();
                for (SmbFile f : shares) {
                    String n = f.getName();
                    if (n.endsWith("/")) n = n.substring(0, n.length() - 1);
                    if (n.isEmpty() || n.startsWith("$") || n.startsWith(".")) continue;
                    names.add(n);
                }
                if (names.isEmpty()) {
                    runOnUiThread(() -> {
                        progress.dismiss();
                        Toast.makeText(this, "没有可访问的共享（可能需要账号）", Toast.LENGTH_LONG).show();
                        askCredentialsFor(host);
                    });
                    return;
                }
                runOnUiThread(() -> {
                    progress.dismiss();
                    String[] arr = names.toArray(new String[0]);
                    new AlertDialog.Builder(this)
                            .setTitle(host + " 的共享")
                            .setItems(arr, (d, w) -> connect("smb://" + host + "/" + arr[w] + "/", smbUser, smbPass))
                            .setNegativeButton("取消", null)
                            .show();
                });
            } catch (SmbAuthException e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    askCredentialsFor(host);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(this, "读取失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void askCredentialsFor(String host) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, pad);
        EditText user = new EditText(this);
        user.setHint("账号");
        user.setText(smbUser);
        EditText pass = new EditText(this);
        pass.setHint("密码");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(user);
        box.addView(pass);
        new AlertDialog.Builder(this)
                .setTitle(host + " 需要账号密码")
                .setView(box)
                .setPositiveButton("连接", (d, w) -> {
                    smbUser = user.getText().toString();
                    smbPass = pass.getText().toString();
                    smbCtx = null;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_USER, smbUser).putString(KEY_PASS, smbPass).apply();
                    listShares(host);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String localIp() {
        try {
            for (java.net.NetworkInterface nic : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                if (!nic.getName().startsWith("wlan") && !nic.getName().startsWith("eth")) continue;
                for (java.net.InetAddress a : java.util.Collections.list(nic.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void connect(String url, String user, String pass) {
        smbUser = user;
        smbPass = pass;
        smbCtx = null;
        listSmb(SmbBrowser.normalize(url));
    }

    /** jcifs 初始化会做网络操作，必须放后台线程。 */
    private CIFSContext ensureCtx() {
        if (smbCtx == null) smbCtx = SmbBrowser.context(smbUser, smbPass);
        return smbCtx;
    }

    private void listSmb(String url) {
        empty.setVisibility(View.GONE);
        title.setText("局域网：" + url);
        new Thread(() -> {
            try {
                SmbFile[] files = SmbBrowser.list(ensureCtx(), url);
                List<Entry> out = new ArrayList<>();
                String base = url;
                if (!base.equals(SmbBrowser.normalize(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, "")))) {
                    Entry up = new Entry();
                    up.name = "..";
                    up.dir = true;
                    up.smbUrl = parent(url);
                    out.add(up);
                }
                for (SmbFile f : files) {
                    String name = f.getName();
                    if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
                    if (name.startsWith(".")) continue;
                    try {
                        if (f.isHidden()) continue;
                    } catch (Exception ignored) {
                    }
                    Entry e = new Entry();
                    e.name = name;
                    e.dir = f.isDirectory();
                    e.image = !e.dir && isImage(name);
                    e.apk = !e.dir && isApk(name);
                    e.smbUrl = f.getURL().toString();
                    out.add(e);
                }
                runOnUiThread(() -> {
                    smbMode = true;
                    smbUrl = url;
                    items.clear();
                    items.addAll(out);
                    refreshGrid();
                });
            } catch (SmbAuthException e) {
                runOnUiThread(this::askCredentials);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "连接失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void askCredentials() {
        android.content.Intent i = new android.content.Intent(this, SmbSetupActivity.class);
        i.putExtra(SmbSetupActivity.EXTRA_NEED_AUTH, true);
        startActivityForResult(i, 20);
    }

    private static String parent(String url) {
        String u = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        int i = u.lastIndexOf('/');
        return i > 6 ? u.substring(0, i + 1) : u + "/";
    }

    // ---------------- 点击 ----------------

    private void onEntry(Entry e) {
        if (e.dir) {
            listSmb(e.smbUrl);
        } else if (e.local != null) {
            if (e.apk) {
                installApk(e.local);
            } else if (setWallpaper(e.local)) {
                done();
            }
        } else if (e.smbUrl != null) {
            if (e.apk) {
                downloadAndInstall(e);
                return;
            }
            if (!e.image) {
                Toast.makeText(this, "只有图片可以设为背景", Toast.LENGTH_SHORT).show();
                return;
            }
            downloadAndPreview(e);
        }
    }

    private void downloadAndPreview(Entry e) {
        Toast.makeText(this, "正在下载…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File tmp = new File(getCacheDir(), "smb_wallpaper.jpg");
                SmbBrowser.download(smbCtx, e.smbUrl, tmp);
                runOnUiThread(() -> preview(tmp));
            } catch (Exception ex) {
                runOnUiThread(() -> Toast.makeText(this, "下载失败：" + ex.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void downloadAndInstall(Entry e) {
        Toast.makeText(this, "正在下载…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File tmp = new File(getCacheDir(), e.name);
                SmbBrowser.download(ensureCtx(), e.smbUrl, tmp);
                runOnUiThread(() -> installApk(tmp));
            } catch (Exception ex) {
                runOnUiThread(() -> Toast.makeText(this, "下载失败：" + ex.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /** 复制到系统下载目录再交给系统安装器（Android 10+ 的 Scoped Storage 要求）。 */
    private void installApk(File apk) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, apk.getName());
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive");
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download");
                android.net.Uri uri = getContentResolver()
                        .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IllegalStateException("insert failed");
                try (FileInputStream in = new FileInputStream(apk);
                     java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0 && out != null) out.write(buf, 0, n);
                }
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } else {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                i.setDataAndType(android.net.Uri.fromFile(apk), "application/vnd.android.package-archive");
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        } catch (Exception e) {
            Toast.makeText(this, "安装失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void preview(File f) {
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setImageBitmap(decode(f, 960));
        iv.setPadding(dp(16), dp(16), dp(16), dp(16));
        new AlertDialog.Builder(this)
                .setTitle("设为桌面背景？")
                .setView(iv)
                .setPositiveButton("设为背景", (d, w) -> {
                    if (setWallpaper(f)) done();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void done() {
        Toast.makeText(this, "已设为桌面背景", Toast.LENGTH_SHORT).show();
        finish();
    }

    private boolean setWallpaper(File src) {
        File dst = new File(getFilesDir(), "wallpaper.jpg");
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isApk(String name) {
        return name.toLowerCase(Locale.US).endsWith(".apk");
    }

    private static boolean isImage(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
                || n.endsWith(".bmp");
    }

    // ---------------- 网格 ----------------

    private void refreshGrid() {
        adapter.notifyDataSetChanged();
        boolean noItems = items.isEmpty();
        empty.setVisibility(noItems ? View.VISIBLE : View.GONE);
        empty.setText(smbMode ? "这个目录是空的" : "没有找到图片，先用「传文件」传一张上来");
        grid.post(() -> {
            if (!items.isEmpty()) {
                grid.setSelection(0);
                grid.requestFocus();
            } else {
                netButton.requestFocus();
            }
        });
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).name.hashCode();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout cell;
            ImageView icon;
            TextView label;
            if (convertView == null) {
                cell = new LinearLayout(ImagePickerActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setBackgroundResource(R.drawable.cell_bg);
                cell.setFocusable(false);
                cell.setPadding(dp(10), dp(10), dp(10), dp(10));
                cell.setLayoutParams(new GridView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));

                icon = new ImageView(ImagePickerActivity.this);
                icon.setLayoutParams(new LinearLayout.LayoutParams(dp(200), dp(160)));
                icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cell.addView(icon);

                label = new TextView(ImagePickerActivity.this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(8);
                label.setLayoutParams(lp);
                label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(20));
                label.setTextColor(Theme.text(ImagePickerActivity.this));
                label.setSingleLine(true);
                label.setGravity(Gravity.CENTER);
                cell.addView(label);

                cell.setTag(new Object[]{icon, label});
                convertView = cell;
            } else {
                Object[] tag = (Object[]) convertView.getTag();
                icon = (ImageView) tag[0];
                label = (TextView) tag[1];
            }

            Entry e = items.get(position);
            label.setText(e.name);
            if (e.dir) {
                icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                icon.setImageResource(R.drawable.ic_folder);
            } else if (e.image && e.local != null) {
                icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                icon.setImageBitmap(decode(e.local, 480));
            } else if (e.image && e.smbUrl != null) {
                Bitmap cached = thumbs.get(e.smbUrl);
                if (cached != null) {
                    icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    icon.setImageBitmap(cached);
                } else {
                    icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                    icon.setImageResource(R.drawable.ic_image);
                    loadThumb(e.smbUrl, f0(e));
                }
            } else if (e.image) {
                icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                icon.setImageResource(R.drawable.ic_image);
            } else if (e.apk) {
                icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                icon.setImageResource(R.drawable.ic_file);
            } else {
                icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                icon.setImageResource(R.drawable.ic_file);
            }
            return convertView;
        }
    }

    private Entry f0(Entry e) {
        return e;
    }

    private void loadThumb(String url, Entry e) {
        if (thumbLoading.contains(url)) return;
        thumbLoading.add(url);
        thumbPool.submit(() -> {
            Bitmap bmp = null;
            try {
                File cache = new File(getCacheDir(), "smbthumb_" + (url.hashCode() & 0x7fffffff) + ".jpg");
                if (!cache.exists()) {
                    File tmp = new File(getCacheDir(), "smbtmp_" + (url.hashCode() & 0x7fffffff));
                    SmbBrowser.download(ensureCtx(), url, tmp);
                    if (tmp.length() > 25L * 1024 * 1024) {
                        tmp.delete();
                        throw new IllegalStateException("文件太大");
                    }
                    //noinspection ResultOfMethodCallIgnored
                    tmp.renameTo(cache);
                }
                bmp = decode(cache, 300);
            } catch (Exception ignored) {
            }
            final Bitmap result = bmp;
            runOnUiThread(() -> {
                thumbLoading.remove(url);
                if (result != null) {
                    thumbs.put(url, result);
                    adapter.notifyDataSetChanged();
                }
            });
        });
    }

    private Bitmap decode(File f, int target) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= target) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        thumbPool.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (smbMode) {
            String root = SmbBrowser.normalize(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, ""));
            if (!smbUrl.equals(root)) {
                listSmb(parent(smbUrl));
                return;
            }
            loadLocal();
            return;
        }
        super.onBackPressed();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
