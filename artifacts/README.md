# Release artifacts

| File | Contents | Built with |
| --- | --- | --- |
| `freebuff-mobile-1.0.0-debug.apk` | Freebuff Mobile 1.0.0 debug build, debug-signed, installable with `adb install` | `./gradlew assembleDebug -PwithNative=true` + whisper `.so` from `scripts/build-whisper-android.sh` |

Inside the APK:

* `classes*.dex` — the whole Kotlin/Compose app
* `lib/arm64-v8a/libfreebuff_llama.so`, `lib/armeabi-v7a/libfreebuff_llama.so` — llama.cpp inference
* `lib/arm64-v8a/libfreebuff_whisper.so`, `lib/armeabi-v7a/libfreebuff_whisper.so` — Whisper speech recognition
* `lib/*/libonnxruntime*.so` — ONNX Runtime for Piper speech synthesis

This is a **debug** build: it is signed with the local debug keystore, which is
fine for sideloading but not for Play. There is no release keystore in this
repository, and no release APK/AAB is claimed — producing one requires your own
signing key (`./gradlew assembleRelease` after adding `signingConfigs`).

Install:

```bash
adb install -r artifacts/freebuff-mobile-1.0.0-debug.apk
```

First launch asks for consent, then downloads ~1.38 GB of models sequentially.
