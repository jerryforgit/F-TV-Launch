package com.jerry.ftvlaunch;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/** 显示配对二维码 + 注入模式 + 无障碍开关。 */
public class RemoteActivity extends Activity {

    private TextView urlView;
    private TextView modeView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        IconButton.applyDesignScale(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote);
        findViewById(R.id.root_remote).setBackgroundColor(Theme.bg(this));
        ((TextView) findViewById(R.id.remote_title)).setTextColor(Theme.text(this));
        urlView = findViewById(R.id.remote_url);
        modeView = findViewById(R.id.remote_mode);
        urlView.setTextColor(Theme.text(this));
        modeView.setTextColor(Theme.subText(this));
        ((TextView) findViewById(R.id.remote_desc)).setTextColor(Theme.subText(this));
        findViewById(R.id.remote_a11y).setBackground(Theme.rowBackground(this));
        findViewById(R.id.remote_reset).setBackground(Theme.rowBackground(this));
        ((TextView) findViewById(R.id.remote_a11y)).setTextColor(Theme.text(this));
        ((TextView) findViewById(R.id.remote_reset)).setTextColor(Theme.text(this));
        ((TextView) findViewById(R.id.remote_overlay)).setTextColor(Theme.text(this));
        findViewById(R.id.remote_overlay).setBackground(Theme.rowBackground(this));

        ControlServer.get(this).start();

        findViewById(R.id.remote_a11y).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (RuntimeException ignored) {
            }
        });
        findViewById(R.id.remote_overlay).setOnClickListener(v -> {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName())));
                } catch (RuntimeException ignored) {
                }
            }
        });
        findViewById(R.id.remote_reset).setOnClickListener(v -> {
            ControlServer.newToken(this);
            render();
        });
        render();
    }

    private void render() {
        String ip = ControlServer.localIp();
        String url = "http://" + ip + ":" + ControlServer.PORT + "/?t=" + ControlServer.token(this);
        qr(url);
        urlView.setText(ip + ":" + ControlServer.PORT);
        String mode = ControlServer.get(this).mode();
        String label = "root".equals(mode) ? "root 模式（全部按键可用）"
                : "a11y".equals(mode) ? "无障碍模式（返回/主页可用，方向键受限）"
                : "正在检测注入方式…";
        modeView.setText(label);
    }

    private void qr(String text) {
        try {
            int size = 660;
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bmp.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            ((ImageView) findViewById(R.id.remote_qr)).setImageBitmap(bmp);
        } catch (Exception ignored) {
        }
    }
}
