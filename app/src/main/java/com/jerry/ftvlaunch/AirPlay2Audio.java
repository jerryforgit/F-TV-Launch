package com.jerry.ftvlaunch;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

/**
 * AirPlay 2 实时音频输出。
 *
 * Rust 侧负责 RTP 接收、AES-CBC 解密和 ALAC 解码，解出的 16-bit PCM
 * 放在队列里；这里只做 AudioTrack 输出，避免把音频解码再搬到 JNI。
 */
final class AirPlay2Audio {

    private static final String TAG = "AirPlay2Audio";
    private static final int RATE = 44100;
    private static volatile boolean started;

    static synchronized void start() {
        if (started) return;
        started = true;
        new Thread(AirPlay2Audio::loop, "airplay2-audio").start();
    }

    private static AudioTrack newTrack() {
        int min = AudioTrack.getMinBufferSize(
                RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(Math.max(min * 4, 16384))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();
        return track;
    }

    private static void loop() {
        short[] pcm = new short[4096];
        AudioTrack track = null;
        long idleSince = System.currentTimeMillis();
        while (true) {
            try {
                int n = RustCast.takeAudio(pcm);
                if (n > 0) {
                    if (track == null) {
                        track = newTrack();
                        Log.i(TAG, "AudioTrack started");
                    }
                    track.write(pcm, 0, n);
                    idleSince = System.currentTimeMillis();
                } else {
                    if (track != null && System.currentTimeMillis() - idleSince > 10000) {
                        try {
                            track.pause();
                            track.flush();
                            track.release();
                        } catch (Throwable ignored) {
                        }
                        track = null;
                        Log.i(TAG, "AudioTrack released (idle)");
                    }
                    Thread.sleep(10);
                }
            } catch (Throwable t) {
                Log.e(TAG, "audio loop failed", t);
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private AirPlay2Audio() {
    }
}
