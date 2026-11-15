package com.jerry.ftvlaunch;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** DLNA 投屏播放页：视频/音频用 VideoView，图片用 ImageView。 */
public class DlnaPlayerActivity extends Activity {

    static final String EXTRA_URI = "uri";
    static final String EXTRA_TITLE = "title";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private VideoView video;
    private ImageView photo;
    private TextView titleView;
    private TextView hintView;
    private String uri = "";
    private String title = "";
    private boolean prepared;
    private boolean imageMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildViews();
        handleIntent(getIntent());
    }

    private void buildViews() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new VideoView(this);
        video.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        root.addView(video);

        photo = new ImageView(this);
        photo.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        photo.setVisibility(View.GONE);
        root.addView(photo);

        titleView = new TextView(this);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2);
        tp.gravity = Gravity.TOP | Gravity.START;
        tp.setMargins(dp(40), dp(28), dp(40), 0);
        titleView.setLayoutParams(tp);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(24);
        titleView.setShadowLayer(6, 0, 2, Color.BLACK);
        root.addView(titleView);

        hintView = new TextView(this);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2);
        hp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(28);
        hintView.setLayoutParams(hp);
        hintView.setTextColor(0xCCFFFFFF);
        hintView.setTextSize(16);
        hintView.setText("OK 播放/暂停 · ◀▶ 快退/快进 10 秒 · 返回 退出");
        hintView.setShadowLayer(6, 0, 2, Color.BLACK);
        root.addView(hintView);

        setContentView(root);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(android.content.Intent intent) {
        if (intent == null) return;
        String u = intent.getStringExtra(EXTRA_URI);
        if (u == null || u.isEmpty()) return;
        uri = u;
        title = intent.getStringExtra(EXTRA_TITLE);
        if (title == null) title = "";
        titleView.setText(title);
        hintView.setText("OK 播放/暂停 · ◀▶ 快退/快进 10 秒 · 返回 退出");
        DlnaServer.currentUri = uri;
        DlnaServer.durationMs = 0;
        DlnaServer.positionMs = 0;

        String lower = uri.toLowerCase();
        boolean isImage = lower.contains(".jpg") || lower.contains(".jpeg")
                || lower.contains(".png") || lower.contains(".bmp") || lower.contains(".webp")
                || lower.contains("image%2f") || lower.contains("image/");
        if (isImage) {
            showImage(uri);
            return;
        }
        imageMode = false;
        photo.setVisibility(View.GONE);
        video.setVisibility(View.VISIBLE);
        prepared = false;
        video.setVideoURI(android.net.Uri.parse(uri));
        video.setOnPreparedListener(mp -> {
            prepared = true;
            DlnaServer.durationMs = video.getDuration();
            DlnaServer.transportState = "PLAYING";
            video.start();
        });
        video.setOnCompletionListener(mp -> {
            DlnaServer.transportState = "STOPPED";
            DlnaServer.positionMs = DlnaServer.durationMs;
            hintView.setText("播放结束 · OK 重新播放 · 返回 退出");
        });
        video.setOnErrorListener((mp, what, extra) -> {
            DlnaServer.transportState = "STOPPED";
            hintView.setText("播放失败，试试其他格式");
            return true;
        });
    }

    private void showImage(String url) {
        imageMode = true;
        video.setVisibility(View.GONE);
        photo.setVisibility(View.VISIBLE);
        DlnaServer.transportState = "PLAYING";
        DlnaServer.durationMs = 0;
        new Thread(() -> {
            Bitmap bmp = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                try (InputStream in = c.getInputStream()) {
                    bmp = BitmapFactory.decodeStream(in);
                }
            } catch (Exception ignored) {
            }
            final Bitmap b = bmp;
            handler.post(() -> {
                if (b != null) {
                    photo.setImageBitmap(b);
                } else {
                    hintView.setText("图片加载失败");
                }
            });
        }, "dlna-image").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        DlnaServer.player = this;
        tick();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (DlnaServer.player == this) DlnaServer.player = null;
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!imageMode && prepared) {
                DlnaServer.positionMs = video.getCurrentPosition();
                boolean playing = video.isPlaying();
                RustCast.setProgress(video.getDuration() / 1000,
                        video.getCurrentPosition() / 1000, playing ? 1 : 0);
            }
            handler.postDelayed(this, 500);
        }
    };

    private void tick() {
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    void doPlay() {
        if (imageMode) return;
        if (prepared) {
            video.start();
            DlnaServer.transportState = "PLAYING";
        } else {
            DlnaServer.transportState = "PLAYING";
            video.start();
        }
    }

    void doPause() {
        if (imageMode) return;
        if (prepared && video.isPlaying()) video.pause();
        DlnaServer.transportState = "PAUSED_PLAYBACK";
        DlnaServer.positionMs = prepared ? video.getCurrentPosition() : DlnaServer.positionMs;
    }

    void doStop() {
        if (!imageMode) {
            try {
                video.stopPlayback();
            } catch (Exception ignored) {
            }
        }
        DlnaServer.transportState = "STOPPED";
        RustCast.setStopped();
        finish();
    }

    void doSeek(long ms) {
        if (imageMode || !prepared) return;
        video.seekTo((int) Math.max(0, Math.min(ms, video.getDuration())));
        DlnaServer.positionMs = ms;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (DlnaServer.transportState.equals("PAUSED_PLAYBACK")) doPlay();
                    else doPause();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    doSeek(DlnaServer.positionMs - 10000);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    doSeek(DlnaServer.positionMs + 10000);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    adjustVolume(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    adjustVolume(-1);
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    doStop();
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void adjustVolume(int dir) {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI);
        } catch (Exception ignored) {
        }
    }
}
