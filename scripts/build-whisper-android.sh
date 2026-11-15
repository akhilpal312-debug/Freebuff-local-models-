#!/usr/bin/env bash
# Builds libfreebuff_whisper.so (whisper.cpp + our JNI shim) for Android and
# drops it into app/src/main/jniLibs/<abi>/ so Gradle packages it.
#
#   ./scripts/fetch-native.sh
#   ./scripts/build-whisper-android.sh                 # arm64-v8a + armeabi-v7a
#   ./scripts/build-whisper-android.sh arm64-v8a       # one ABI
#
# whisper.cpp vendored its own ggml target, which cannot coexist with llama.cpp's
# inside one CMake project, so this is a separate build from the Gradle one.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)}"
CMAKE_BIN="${CMAKE_BIN:-$(ls -d "$SDK"/cmake/*/bin/cmake 2>/dev/null | sort -V | tail -1)}"

if [ -z "${NDK:-}" ] || [ ! -d "$NDK" ]; then
  echo "NDK not found. Install it with: sdkmanager 'ndk;27.2.12479018'" >&2
  exit 1
fi
if [ -z "${CMAKE_BIN:-}" ] || [ ! -x "$CMAKE_BIN" ]; then
  echo "CMake not found. Install it with: sdkmanager 'cmake;3.22.1'" >&2
  exit 1
fi
if [ ! -d "$ROOT/app/src/main/cpp/whisper.cpp" ]; then
  echo "whisper.cpp sources missing. Run ./scripts/fetch-native.sh first." >&2
  exit 1
fi

ABIS=("$@")
if [ ${#ABIS[@]} -eq 0 ]; then
  ABIS=(arm64-v8a armeabi-v7a)
fi

for ABI in "${ABIS[@]}"; do
  echo "== building whisper for $ABI"
  BUILD_DIR="$ROOT/build/whisper-$ABI"
  "$CMAKE_BIN" \
    -S "$ROOT/app/src/main/cpp/whisper" \
    -B "$BUILD_DIR" \
    -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_MAKE_PROGRAM="$NDK/build/cmake/../../../../cmake/3.22.1/bin/ninja" \
    > /dev/null
  "$CMAKE_BIN" --build "$BUILD_DIR" --target freebuff_whisper -j "$(nproc)" > /dev/null
  OUT_DIR="$ROOT/app/src/main/jniLibs/$ABI"
  mkdir -p "$OUT_DIR"
  cp "$BUILD_DIR/libfreebuff_whisper.so" "$OUT_DIR/"
  echo "   -> app/src/main/jniLibs/$ABI/libfreebuff_whisper.so ($(du -h "$OUT_DIR/libfreebuff_whisper.so" | cut -f1))"
done

echo
echo "Now rebuild the APK so the speech library is packaged:"
echo "  ./gradlew assembleDebug -PwithNative=true"
