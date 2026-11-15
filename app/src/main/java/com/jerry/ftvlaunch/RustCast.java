package com.jerry.ftvlaunch;

/** Rust 投屏核心的 JNI 入口（核心代码在 core/ftv-cast）。 */
final class RustCast {

    static {
        System.loadLibrary("ftv_cast_ffi");
    }

    static native int start(int port, String ip, String udn, String name);

    /** 返回一条事件："play\nurl\ntitle" / "stop" / "pause" / "resume" / "seek\n秒" / "volume\n值"，没有则 null。 */
    static native String takeEvent();

    static native void setProgress(long durationSec, long positionSec, int playing);

    static native void setStopped();

    static native void stop();

    private RustCast() {
    }
}
