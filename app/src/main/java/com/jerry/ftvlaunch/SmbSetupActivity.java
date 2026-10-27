package com.jerry.ftvlaunch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jcifs.CIFSContext;
import jcifs.smb.SmbAuthException;
import jcifs.smb.SmbFile;

/** 遥控器友好的 SMB 位置设置：列表页和表单页都用同一套整行 UI，不用系统弹窗。 */
public class SmbSetupActivity extends Activity {

    static final String EXTRA_NEED_AUTH = "need_auth";

    private static final String PREFS = "smb";
    private static final String KEY_URL = "url";
    private static final String KEY_USER = "user";
    private static final String KEY_PASS = "pass";

    private String url = "";
    private String user = "";
    private String pass = "";

    private View setupScroll;
    private View listScroll;
    private TextView rowUrl;
    private TextView rowUser;
    private TextView rowPass;
    private TextView listTitle;
    private TextView listStatus;
    private LinearLayout listRows;

    private String pendingHost;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        IconButton.applyDesignScale(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_smb_setup);
        findViewById(R.id.root_smb).setBackgroundColor(Theme.bg(this));

        url = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, "");
        user = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_USER, "");
        pass = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PASS, "");

        setupScroll = findViewById(R.id.setup_scroll);
        listScroll = findViewById(R.id.list_scroll);
        rowUrl = findViewById(R.id.row_url);
        rowUser = findViewById(R.id.row_user);
        rowPass = findViewById(R.id.row_pass);
        listTitle = findViewById(R.id.list_title);
        listStatus = findViewById(R.id.list_status);
        listRows = findViewById(R.id.list_rows);

        for (int id : new int[]{R.id.row_scan, R.id.row_url, R.id.row_user, R.id.row_pass, R.id.row_connect}) {
            View v = findViewById(id);
            if (v instanceof TextView) {
                ((TextView) v).setTextColor(Theme.text(this));
                v.setBackground(Theme.rowBackground(this));
            }
        }
        ((TextView) findViewById(R.id.smb_title)).setTextColor(Theme.text(this));
        ((TextView) findViewById(R.id.smb_sub)).setTextColor(Theme.subText(this));
        ((TextView) findViewById(R.id.status)).setTextColor(Theme.subText(this));
        listTitle.setTextColor(Theme.text(this));
        listStatus.setTextColor(Theme.subText(this));

        findViewById(R.id.row_scan).setOnClickListener(v -> scan());
        rowUrl.setOnClickListener(v -> edit("地址（smb://IP/共享 或 IP/共享）", url, false, s -> {
            url = s;
            updateRows();
        }));
        rowUser.setOnClickListener(v -> edit("账号（留空表示匿名 / guest）", user, false, s -> {
            user = s;
            updateRows();
        }));
        rowPass.setOnClickListener(v -> edit("密码", pass, true, s -> {
            pass = s;
            updateRows();
        }));
        findViewById(R.id.row_connect).setOnClickListener(v -> connect());

        updateRows();
        findViewById(R.id.row_scan).requestFocus();

        if (getIntent().getBooleanExtra(EXTRA_NEED_AUTH, true)) {
            // 从选择器带过来的认证提示
        }
    }

    private void updateRows() {
        rowUrl.setText("地址：" + (url.isEmpty() ? "未填写" : url));
        rowUser.setText("账号：" + (user.isEmpty() ? "不填（匿名 / guest）" : user));
        rowPass.setText("密码：" + (pass.isEmpty() ? "不填" : "已设置"));
    }

    private OnText editDone;
    private EditText editInput;
    private View editBox;

    /** 页面内输入：EditText 在 Activity 窗口里，输入法才稳定弹出来。 */
    private void edit(String title, String value, boolean secret, OnText done) {
        editDone = done;
        editBox = findViewById(R.id.edit_box);
        editInput = findViewById(R.id.edit_input);
        ((TextView) findViewById(R.id.edit_title)).setText(title);
        ((TextView) findViewById(R.id.edit_title)).setTextColor(Theme.text(this));
        editInput.setText(value);
        editInput.setSelection(editInput.getText().length());
        editInput.setTextColor(Theme.text(this));
        editInput.setInputType(secret
                ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
                : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI));
        editBox.setBackgroundColor(Theme.bg(this));

        buildKeyboard();
        editBox.setVisibility(View.VISIBLE);
        editInput.setFocusable(false);
        final LinearLayout keys = findViewById(R.id.edit_keys);
        keys.post(() -> {
            if (keys.getChildCount() > 0) {
                LinearLayout firstRow = (LinearLayout) keys.getChildAt(0);
                if (firstRow.getChildCount() > 0) firstRow.getChildAt(0).requestFocus();
            }
        });
    }

    private boolean upperCase;
    private TextView capsKey;

    /** 盒子上的软键盘经常不弹（有遥控器被当成硬件键盘），所以自带一个方向键可用的键盘。 */
    private void buildKeyboard() {
        LinearLayout keys = findViewById(R.id.edit_keys);
        keys.removeAllViews();
        String[] rows = {"1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm,._-/:@\\"};
        for (String row : rows) {
            keys.addView(keyRow(row));
        }
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(70)));
        String[] acts = {"大写", "空格", "退格", "清空", "确定", "取消"};
        for (String a : acts) {
            TextView k = keyView(a);
            k.setLayoutParams(new LinearLayout.LayoutParams(0, dp(70), 1f));
            if (a.equals("大写")) {
                capsKey = k;
                k.setText(upperCase ? "小写" : "大写");
                if (upperCase) {
                    k.setBackground(Theme.filled(this, android.graphics.Color.parseColor("#FF2F6BFF")));
                    k.setTextColor(android.graphics.Color.WHITE);
                }
            }
            k.setOnClickListener(v -> onKey(a));
            actions.addView(k);
        }
        keys.addView(actions);
    }

    private LinearLayout keyRow(String chars) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(70));
        rlp.bottomMargin = dp(4);
        row.setLayoutParams(rlp);
        for (char c : chars.toCharArray()) {
            TextView k = keyView(String.valueOf(c));
            k.setLayoutParams(new LinearLayout.LayoutParams(0, dp(70), 1f));
            k.setOnClickListener(v -> append(upperCase ? Character.toUpperCase(c) : c));
            row.addView(k);
        }
        return row;
    }

    private TextView keyView(String text) {
        TextView k = new TextView(this);
        k.setText(text);
        k.setGravity(android.view.Gravity.CENTER);
        k.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(26));
        k.setTextColor(Theme.text(this));
        k.setBackground(Theme.rowBackground(this));
        k.setFocusable(true);
        k.setClickable(true);
        return k;
    }

    private void append(char c) {
        editInput.setText(editInput.getText().toString() + c);
        editInput.setSelection(editInput.getText().length());
    }

    private void onKey(String key) {
        switch (key) {
            case "大写":
                upperCase = !upperCase;
                if (capsKey != null) {
                    capsKey.setText(upperCase ? "小写" : "大写");
                    if (upperCase) {
                        capsKey.setBackground(Theme.filled(this, android.graphics.Color.parseColor("#FF2F6BFF")));
                        capsKey.setTextColor(android.graphics.Color.WHITE);
                    } else {
                        capsKey.setBackground(Theme.rowBackground(this));
                        capsKey.setTextColor(Theme.text(this));
                    }
                }
                break;
            case "空格":
                append(' ');
                break;
            case "退格": {
                String t = editInput.getText().toString();
                if (!t.isEmpty()) editInput.setText(t.substring(0, t.length() - 1));
                break;
            }
            case "清空":
                editInput.setText("");
                break;
            case "确定":
                finishEdit(editInput.getText().toString().trim());
                break;
            case "取消":
                finishEdit(null);
                break;
        }
    }

    private void finishEdit(String value) {
        EditText input = editInput;
        View box = editBox;
        OnText done = editDone;
        if (imm() != null) imm().hideSoftInputFromWindow(input.getWindowToken(), 0);
        editBox.setVisibility(View.GONE);
        editDone = null;
        if (done != null && value != null) done.on(value);
        if (done != null && value == null) {
            // 取消：回到原来的页面状态
            if (listScroll.getVisibility() == View.VISIBLE) listTitle.requestFocus();
            else findViewById(R.id.row_scan).requestFocus();
        }
    }

    private android.view.inputmethod.InputMethodManager imm() {
        return (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
    }

    @Override
    public void onBackPressed() {
        if (editBox != null && editBox.getVisibility() == View.VISIBLE) {
            finishEdit(null);
            return;
        }
        if (listScroll.getVisibility() == View.VISIBLE) {
            showSetup();
            return;
        }
        super.onBackPressed();
    }

    interface OnText {
        void on(String value);
    }

    // ---------------- 列表页 ----------------

    private void showList(String title, String status, List<String> items, OnPick pick) {
        setupScroll.setVisibility(View.GONE);
        listScroll.setVisibility(View.VISIBLE);
        listScroll.scrollTo(0, 0);
        listTitle.setText(title);
        listStatus.setText(status == null ? "" : status);
        listStatus.setVisibility(status == null ? View.GONE : View.VISIBLE);
        listRows.removeAllViews();

        for (String item : items) {
            listRows.addView(listRow(item, v -> pick.on(item)));
        }
        listRows.addView(listRow("返回", v -> showSetup()));

        if (listRows.getChildCount() > 0) listRows.getChildAt(0).requestFocus();
    }

    private TextView listRow(String text, View.OnClickListener click) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(32));
        row.setTextColor(Theme.text(this));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(32), 0, dp(32), 0);
        row.setBackground(Theme.rowBackground(this));
        row.setFocusable(true);
        row.setClickable(true);
        row.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(96));
        lp.bottomMargin = dp(14);
        row.setLayoutParams(lp);
        row.setOnClickListener(click);
        return row;
    }

    private void showSetup() {
        listScroll.setVisibility(View.GONE);
        setupScroll.setVisibility(View.VISIBLE);
        findViewById(R.id.row_scan).requestFocus();
    }

    interface OnPick {
        void on(String value);
    }

    // ---------------- 扫描 ----------------

    private void scan() {
        String ip = localIp();
        if (ip == null) {
            Toast.makeText(this, "没有连上网络", Toast.LENGTH_SHORT).show();
            return;
        }
        final String prefix = ip.substring(0, ip.lastIndexOf('.') + 1);
        showList("正在扫描局域网…", prefix + "0/24，查找 SMB(445) 主机", new ArrayList<>(), v -> {
        });
        listRows.removeAllViews();
        listRows.addView(listRow("返回", v -> showSetup()));

        new Thread(() -> {
            List<String> hosts = Collections.synchronizedList(new ArrayList<>());
            ExecutorService pool = Executors.newFixedThreadPool(32);
            for (int i = 1; i <= 254; i++) {
                final String host = prefix + i;
                pool.submit(() -> {
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(host, 445), 400);
                        hosts.add(host);
                    } catch (Exception ignored) {
                    }
                });
            }
            pool.shutdown();
            try {
                pool.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            hosts.sort((a, b) -> Integer.compare(
                    Integer.parseInt(a.substring(a.lastIndexOf('.') + 1)),
                    Integer.parseInt(b.substring(b.lastIndexOf('.') + 1))));
            runOnUiThread(() -> {
                if (hosts.isEmpty()) {
                    showList("没有找到 SMB 主机", "确认电视和电脑/NAS 在同一个局域网", new ArrayList<>(), v -> {
                    });
                    return;
                }
                showList("发现 " + hosts.size() + " 台主机", "选择一台读取共享", hosts, this::listShares);
            });
        }).start();
    }

    private void listShares(String host) {
        pendingHost = host;
        showList("连接 " + host, "正在读取共享列表…", new ArrayList<>(), v -> {
        });
        new Thread(() -> {
            try {
                CIFSContext ctx = SmbBrowser.context(user, pass);
                SmbFile[] shares = SmbBrowser.list(ctx, "smb://" + host + "/");
                List<String> names = new ArrayList<>();
                for (SmbFile f : shares) {
                    String n = f.getName();
                    if (n.endsWith("/")) n = n.substring(0, n.length() - 1);
                    if (n.isEmpty() || n.startsWith("$") || n.startsWith(".")) continue;
                    names.add(n);
                }
                runOnUiThread(() -> {
                    if (names.isEmpty()) {
                        askAuth(host);
                        return;
                    }
                    showList(host + " 的共享", "选择后自动填好地址", names, share -> {
                        url = "smb://" + host + "/" + share;
                        updateRows();
                        showSetup();
                        findViewById(R.id.row_connect).requestFocus();
                    });
                });
            } catch (SmbAuthException e) {
                runOnUiThread(() -> askAuth(host));
            } catch (Exception e) {
                runOnUiThread(() -> showList("读取失败", String.valueOf(e.getMessage()),
                        new ArrayList<>(), v -> {
                        }));
            }
        }).start();
    }

    /** 认证失败：先让用户填账号，再填密码，然后重试列共享。 */
    private void askAuth(String host) {
        listStatus.setText("这台主机需要账号密码");
        edit("账号", user, false, u -> {
            user = u;
            updateRows();
            edit("密码", pass, true, p -> {
                pass = p;
                updateRows();
                listShares(host);
            });
        });
    }

    private void connect() {
        if (url.trim().isEmpty()) {
            Toast.makeText(this, "先填地址，或扫描局域网", Toast.LENGTH_SHORT).show();
            showSetup();
            rowUrl.requestFocus();
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_URL, url.trim())
                .putString(KEY_USER, user)
                .putString(KEY_PASS, pass)
                .apply();
        setResult(RESULT_OK, new Intent());
        finish();
    }

    private String localIp() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                if (!nic.getName().startsWith("wlan") && !nic.getName().startsWith("eth")) continue;
                for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
