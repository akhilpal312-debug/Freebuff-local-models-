# Native build log

This file records what the native (C/C++) build actually produced in this
workspace, so nobody has to take it on faith.

## Environment

* Android NDK `27.2.12479018`
* CMake `3.22.1` + Ninja (from the Android SDK)
* llama.cpp `v1.0.0` (latest upstream release at build time, `ggml-org/llama.cpp`)
* whisper.cpp `v1.9.5` (`ggml-org/whisper.cpp`)
* 2-core Linux x86_64 host, JDK 17, AGP 8.7.3

## llama.cpp — SUCCEEDED

```
./scripts/fetch-native.sh
./gradlew :app:assembleDebug -PwithNative=true
```

Result:

```
BUILD SUCCESSFUL in 3m 5s
app/build/outputs/apk/debug/app-debug.apk
  lib/arm64-v8a/libfreebuff_llama.so     5.74 MB
  lib/armeabi-v7a/libfreebuff_llama.so   4.32 MB
BuildConfig.NATIVE_ENGINE_BUILT = true
```

Exported JNI symbols verified with `llvm-nm -D --defined-only`:

```
Java_dev_akhil_freebuff_inference_LlamaNative_nativeBackendInfo
Java_dev_akhil_freebuff_inference_LlamaNative_nativeFreeModel
Java_dev_akhil_freebuff_inference_LlamaNative_nativeGenerate
Java_dev_akhil_freebuff_inference_LlamaNative_nativeLastError
Java_dev_akhil_freebuff_inference_LlamaNative_nativeLoadModel
Java_dev_akhil_freebuff_inference_LlamaNative_nativeStop
```

One API change was required for this llama.cpp release: `llama_model_params`
replaced the `use_mmap` / `use_mlock` booleans with a `load_mode` enum. The shim
now sets `LLAMA_LOAD_MODE_MMAP` (or `NONE`); everything else in `llama.h` matched
what the shim already used.

## whisper.cpp — BUILT SEPARATELY

whisper.cpp vendors its own `ggml` target, and CMake target names are global, so
it cannot share one CMake project with llama.cpp. It is built by
`scripts/build-whisper-android.sh` as a standalone project:

```
./scripts/build-whisper-android.sh            # both ABIs
  -> app/src/main/jniLibs/arm64-v8a/libfreebuff_whisper.so    (16 MB, 2.9 MB in APK)
  -> app/src/main/jniLibs/armeabi-v7a/libfreebuff_whisper.so  (13 MB, 2.1 MB in APK)
```

Final packaging (`./gradlew assembleDebug -PwithNative=true`, `BUILD SUCCESSFUL`):

```
artifacts/freebuff-mobile-1.0.0-debug.apk   60.6 MB
  lib/arm64-v8a/libfreebuff_llama.so         5.74 MB
  lib/arm64-v8a/libfreebuff_whisper.so       2.90 MB
  lib/armeabi-v7a/libfreebuff_llama.so       4.32 MB
  lib/armeabi-v7a/libfreebuff_whisper.so     2.06 MB
  lib/*/libonnxruntime*.so                   (Piper TTS runtime)
```

The Kotlin side (`WhisperStt`) loads `freebuff_whisper` independently, so the app
still works if a given build has no speech library — it reports that speech
recognition is unavailable instead of returning dummy text.

## What is still NOT verified

The APK was built and inspected here, but this environment has no Android device
or emulator (no KVM). Therefore:

* the app has never been launched;
* no model has been loaded into llama.cpp on real hardware;
* no audio has passed through Whisper;
* no Piper voice has been synthesised.

Those are device checks, listed in `docs/TESTING.md`. Every one of them fails with
a specific message rather than a fake success, so a device run tells you exactly
what works.
