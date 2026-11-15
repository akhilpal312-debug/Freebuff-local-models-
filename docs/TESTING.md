# Test report and verification notes

Everything below was actually executed in this workspace unless marked otherwise.
Nothing in this file is aspirational.

## Environment used

* JDK 17 (Temurin 17.0.21), Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21
* Android SDK platform 35, build-tools 35.0.0
* Linux x86_64, 2 cores, no physical Android device and no emulator (no KVM) —
  **so nothing was run on a device**. Every on-device statement below is marked.

## 1. Unit tests — PASSED (19 tests, 0 failures)

```
./gradlew :app:testDebugUnitTest
```

| Suite | Tests | Result | What it covers |
| --- | --- | --- | --- |
| `GgufInspectorTest` | 4 | pass | GGUF magic/version validation, architecture + quantisation parsing, recursive array skipping, rejection of non-GGUF files |
| `PromptRendererTest` | 4 | pass | Gemma/ChatML template shape, Qwen3 think-block stripping, unterminated think detection |
| `AgentIntentTest` | 4 | pass | intent classification, plan shape (CHECK+VERIFY present), code-block extraction |
| `HindiPhonemizerTest` | 2 | pass | Devanagari detection, consonant/vowel phoneme output |
| `FormatTest` | 2 | pass | byte formatting, filesystem-safe slugs |

Test results: `app/build/test-results/testDebugUnitTest/*.xml`.

## 2. Build — PASSED

```
./gradlew :app:assembleDebug
```

Produced `app/build/outputs/apk/debug/app-debug.apk`, shipped as
`artifacts/freebuff-mobile-1.0.0-debug.apk` (60.7 MB, arm64-v8a + armeabi-v7a).

Artifact checks performed:

* `aapt2 dump badging` → `package: dev.akhil.freebuff`, versionCode 1, versionName
  1.0.0, minSdk 26, targetSdk 35, compileSdk 35.
* `unzip -l` → `AndroidManifest.xml`, dex files, and all four native libraries:
  `libfreebuff_llama.so` + `libfreebuff_whisper.so` for arm64-v8a and armeabi-v7a,
  plus ONNX Runtime for Piper.
* Manifest permission list inspected: no storage/location/contacts/camera
  permissions.

## 3. Model sources — VERIFIED against the live hub

`GET https://huggingface.co/api/models/<repo>?blobs=true` for the three GGUF
repositories, and `HEAD` on the CDN URL for the whisper and Piper files. Confirmed
existence, exact byte size, `gated: false`, and the LFS SHA-256 where published:

| File | Size | SHA-256 (from `x-linked-etag`) |
| --- | --- | --- |
| `gemma-3-270m-it-q4_k_m.gguf` | 253,114,880 | `55aed1673d2f3014a739732c659c4995fc57abf6e4e9c68462fb2ceee820ea2f` |
| `qwen2.5-coder-0.5b-instruct-q4_k_m.gguf` | 491,400,064 | `1d9614638d18024d0fbb36575a15f1302a3adf044df10345688ec4f6e1c4ff32` |
| `Qwen3-0.6B-Q4_0.gguf` | 428,970,080 | `da2572f16c06133561ce56accaa822216f2391ef4d37fba427801cd6736417d4` |
| `ggml-tiny.bin` | 77,691,713 | `be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21` |
| `hi_IN-pratham-medium.onnx` | 63,516,050 | not published upstream |
| `hi_IN-pratham-medium.onnx.json` | 4,970 | not published upstream |
| `en_US-lessac-medium.onnx` | 63,201,294 | not published upstream |
| `en_US-lessac-medium.onnx.json` | 4,885 | not published upstream |

## 4. Native build

**Status: PASSED** — both engines compiled and are packaged in the APK.

* llama.cpp `v1.0.0` via `./gradlew assembleDebug -PwithNative=true`, `BUILD
  SUCCESSFUL in 3m 5s`, `libfreebuff_llama.so` for both ABIs.
* whisper.cpp `v1.9.5` via `./scripts/build-whisper-android.sh`, a separate CMake
  project because whisper vendors its own `ggml` target.
* All six `LlamaNative` JNI symbols confirmed present with `llvm-nm -D`.
* One upstream API change was needed: `llama_model_params.use_mmap/use_mlock`
  became the `load_mode` enum.

What this does **not** prove: that a model loads at runtime, that tokens stream,
or that audio transcribes — those need a device. See `docs/BUILD-LOG.md`.

## 5. Not tested here (requires a physical device)

These need a real phone and are listed so nobody mistakes them for verified:

* installing/launching the APK, and every screen's layout on real screen sizes
* downloading a model over a real network and surviving process death
* loading a GGUF into memory and streaming tokens
* microphone capture, VAD end-of-utterance, Whisper transcription quality for
  Hindi/Hinglish/English
* Piper synthesis quality on device, ONNX Runtime session creation
* WorkManager foreground notification behaviour across manufacturers
* terminal execution of `/system/bin` binaries under Android's SELinux policy
* WebView preview of local files

Each of these has a deterministic failure message in the app rather than a silent
fallback, so a device test either works or reports precisely why not.

## 6. How to re-run everything

```bash
export JAVA_HOME=/path/to/jdk17
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:assembleDebug          # APK without native engines
./scripts/fetch-native.sh
./gradlew :app:assembleDebug -PwithNative=true   # APK with llama.cpp + whisper.cpp
```

## 5. Startup regression test — PASSED (Robolectric, 3 tests)

`app/src/test/java/dev/akhil/freebuff/LaunchTest.kt` runs the real
`Application` (which builds `AppContainer` and bootstraps storage), then the real
`MainActivity`, then recreates it — all headless on SDK 33. It exists because a
release that only compiles can still close the instant it is tapped, and that is
exactly the failure a device-less build cannot see.

```
./gradlew :app:testDebugUnitTest --tests "dev.akhil.freebuff.LaunchTest"
```

What this proves: the container, the Room database, DataStore, the theme and the
first Compose tree all initialise without throwing.
What it cannot prove: any device-only failure (missing native library, an OEM
keystore quirk, a 32-bit-only device). Those are covered by the crash recorder
below instead of being guessed at.

## 6. Crash recorder

`core/CrashReporter.kt` installs an uncaught-exception handler that writes the
real stack trace plus the device fingerprint to `files/last-crash.txt` before the
process dies, then delegates to the previous handler. On the next successful
launch `MainActivity` shows that record in a dialog with Copy and Dismiss.

Startup itself is now defensive: `FreebuffApp.onCreate`, `bootstrap()`, model
auto-load, voice warm-up and conversation restore all run inside `runCatching`,
and a failure is recorded rather than propagated. A missing model, a broken
audio device or an unreadable database degrades one feature instead of closing
the app.
