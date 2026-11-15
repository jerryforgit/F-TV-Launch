LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE    := alac
LOCAL_SRC_FILES := alac_jni.cpp \
                   alac/ALACDecoder.cpp \
                   alac/ALACBitUtilities.c \
                   alac/dp_dec.c \
                   alac/ag_dec.c \
                   alac/EndianPortable.c \
                   alac/matrix_dec.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/alac
LOCAL_CFLAGS     := -O2 -DNDEBUG -DTARGET_RT_LITTLE_ENDIAN=1
LOCAL_CPPFLAGS   := -O2 -std=c++11
LOCAL_LDLIBS     := -llog
include $(BUILD_SHARED_LIBRARY)
