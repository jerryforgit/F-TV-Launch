#include <jni.h>
#include <cstdint>
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "AlacJni", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AlacJni", __VA_ARGS__)
#include "alac/ALACDecoder.h"
#include "alac/ALACBitUtilities.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_jerry_ftvlaunch_AlacNative_create(JNIEnv *env, jclass, jbyteArray cookie) {
    jsize n = env->GetArrayLength(cookie);
    jbyte *c = env->GetByteArrayElements(cookie, nullptr);
    ALACDecoder *dec = new ALACDecoder();
    int32_t st = dec->Init(c, (uint32_t) n);
    LOGI("init status=%d cookie=%d bytes: %02x %02x %02x %02x %02x %02x %02x %02x",
         st, (int) n, (unsigned char) c[0], (unsigned char) c[1], (unsigned char) c[2],
         (unsigned char) c[3], (unsigned char) c[4], (unsigned char) c[5],
         (unsigned char) c[6], (unsigned char) c[7]);
    env->ReleaseByteArrayElements(cookie, c, JNI_ABORT);
    if (st != 0) {
        LOGI("config fl=%u ch=%u bits=%u sr=%u", dec->mConfig.frameLength,
             dec->mConfig.numChannels, dec->mConfig.bitDepth, dec->mConfig.sampleRate);
        delete dec;
        return 0;
    }
    return (jlong) (intptr_t) dec;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jerry_ftvlaunch_AlacNative_decode(JNIEnv *env, jclass, jlong h,
                                           jbyteArray in, jint inLen, jbyteArray out) {
    ALACDecoder *dec = (ALACDecoder *) (intptr_t) h;
    if (!dec) return -1;
    jsize n = env->GetArrayLength(in);
    if (inLen > 0 && inLen < n) n = inLen;
    jbyte *b = env->GetByteArrayElements(in, nullptr);
    jsize on = env->GetArrayLength(out);
    jbyte *o = env->GetByteArrayElements(out, nullptr);
    BitBuffer bits;
    BitBufferInit(&bits, (uint8_t *) b, (uint32_t) n);
    uint32_t outSamples = 0;
    int32_t st = dec->Decode(&bits, (uint8_t *) o, dec->mConfig.frameLength,
                             dec->mConfig.numChannels, &outSamples);
    env->ReleaseByteArrayElements(in, b, JNI_ABORT);
    env->ReleaseByteArrayElements(out, o, 0);
    return st == 0 ? (jint) outSamples : -st;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jerry_ftvlaunch_AlacNative_channels(JNIEnv *, jclass, jlong h) {
    ALACDecoder *dec = (ALACDecoder *) (intptr_t) h;
    return dec ? dec->mConfig.numChannels : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jerry_ftvlaunch_AlacNative_frameLength(JNIEnv *, jclass, jlong h) {
    ALACDecoder *dec = (ALACDecoder *) (intptr_t) h;
    return dec ? dec->mConfig.frameLength : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jerry_ftvlaunch_AlacNative_destroy(JNIEnv *, jclass, jlong h) {
    delete (ALACDecoder *) (intptr_t) h;
}
