package com.jerry.ftvlaunch;

/** Apple 官方 ALAC 解码器的 JNI 包装（盒子系统里没有 alac 解码库）。 */
final class AlacNative {

    static {
        System.loadLibrary("alac");
    }

    static native long create(byte[] cookie);

    static native int decode(long handle, byte[] in, int inLen, byte[] out);

    static native int channels(long handle);

    static native int frameLength(long handle);

    static native void destroy(long handle);

    private AlacNative() {
    }
}
