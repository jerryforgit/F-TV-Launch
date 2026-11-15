#!/usr/bin/env bash
# 编 Rust 投屏核心到 Android（产物直接进 app/src/main/jniLibs）
set -e
cd "$(dirname "$0")"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"
cargo ndk -t armeabi-v7a -t arm64-v8a --platform 21 \
  -o ../app/src/main/jniLibs build --release -p ftv-cast-ffi
echo "built: $(ls ../app/src/main/jniLibs/*/libftv_cast_ffi.so | tr '\n' ' ')"
