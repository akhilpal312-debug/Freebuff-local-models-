# Freebuff Mobile

A local-first AI workstation for Android. Built by **Akhil**.

Freebuff Mobile runs open-source language models, speech recognition, speech
synthesis, a coding agent, a sandboxed terminal, a project workspace and a
research module **on the phone**. There is no account, no sign-in and no cloud
dependency. Optional remote providers exist but are off by default and are
labelled as online wherever they are used.

---

## Current status (honest)

| Area | State |
| --- | --- |
| Android app (Kotlin + Compose) | Builds to a signed debug APK with `./gradlew assembleDebug` |
| Chat, history, modes, model selector | Implemented, persisted in Room |
| Model catalogue, downloads, verification | Implemented; every repository/file/size/SHA-256 verified against the live Hugging Face hub |
| Background downloads | WorkManager foreground worker, resumable, sequential, survives app close |
| GGUF validation | Real GGUF header parser (architecture, quantisation, tensor count) |
| Voice state machine, VAD, half-duplex | Implemented |
| Local LLM inference (llama.cpp) | **Compiled and packaged**: `libfreebuff_llama.so` for arm64-v8a + armeabi-v7a, all six JNI symbols verified |
| Local STT (whisper.cpp) | **Compiled and packaged**: `libfreebuff_whisper.so` (see `scripts/build-whisper-android.sh`) |
| Hindi TTS (Piper + ONNX Runtime) | Implemented (Devanagari G2P + phoneme id map); needs the Piper voice files downloaded |
| English TTS | Platform Android speech engine (offline once voice data is installed) |
| Coding agent, tasks, reports | Implemented with real on-disk verification |
| Terminal | Allowlisted, path-jailed, timeout + confirmation for destructive commands |
| Website preview | In-app WebView on app-private files |
| Android APK/AAB build | Device capability detector runs for real and reports that Android has no JDK/SDK/Gradle |
| Web search / deep research | Real providers (DuckDuckGo HTML, Wikipedia, optional Tavily), real page fetching, citation verification |

The prebuilt debug APK in `artifacts/` **includes both native engines** (llama.cpp
and whisper.cpp) for `arm64-v8a` and `armeabi-v7a`. It was built and inspected
here; it has not been run on a physical device, because this environment has none.
See `docs/BUILD-LOG.md` for the exact build output and `docs/TESTING.md` for what
was and was not verified.

---

## Build

Requirements: JDK 17, Android SDK (compileSdk 35, build-tools 35), Android Studio
or the command line.

```bash
# Plain app (no on-device inference)
./gradlew assembleDebug

# Full app with llama.cpp + whisper.cpp compiled in
./scripts/fetch-native.sh                     # clones the pinned sources
sdkmanager "ndk;27.2.12479018" "cmake;3.22.1" # NDK + CMake
./gradlew assembleDebug -PwithNative=true
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

A release build needs your own signing config; none is committed:

```bash
./gradlew assembleRelease      # unsigned until you add signingConfigs
```

`local.properties` must contain `sdk.dir=/path/to/Android/sdk` (Android Studio
writes it for you).

---

## First run

Onboarding lists every component with its exact size and licence and asks for
consent before anything downloads. Downloads are sequential:

1. Gemma 3 270M IT (Q4_K_M) — 241 MB
2. Qwen2.5-Coder 0.5B Instruct (Q4_K_M) — 469 MB
3. Qwen3 0.6B (Q4_0) — 409 MB
4. Whisper Tiny multilingual — 74 MB
5. Piper Hindi (Pratham, medium) — 61 MB + config
6. Piper English (Lessac, medium) — 60 MB + config

Each file is resumed with an HTTP Range request, checked against its SHA-256
(when upstream publishes one) and its byte length, then renamed into place.
Nothing is marked installed until both checks pass.

You never type an installation command. The app does not ship or need the
Hugging Face CLI.

---

## Verified model sources

| Component | Repository | File | Bytes | SHA-256 |
| --- | --- | --- | --- | --- |
| Gemma 3 270M IT | `gguf-org/gemma-3-270m-it-gguf` | `gemma-3-270m-it-q4_k_m.gguf` | 253,114,880 | `55aed167…20ea2f` |
| Qwen2.5-Coder 0.5B | `Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF` | `qwen2.5-coder-0.5b-instruct-q4_k_m.gguf` | 491,400,064 | `1d961463…c4ff32` |
| Qwen3 0.6B | `ggml-org/Qwen3-0.6B-GGUF` | `Qwen3-0.6B-Q4_0.gguf` | 428,970,080 | `da2572f1…417d4` |
| Whisper Tiny | `ggerganov/whisper.cpp` | `ggml-tiny.bin` | 77,691,713 | `be07e048…6e1b21` |
| Piper Hindi | `rhasspy/piper-voices` | `hi/hi_IN/pratham/medium/hi_IN-pratham-medium.onnx` | 63,516,050 | (not published) |
| Piper English | `rhasspy/piper-voices` | `en/en_US/lessac/medium/en_US-lessac-medium.onnx` | 63,201,294 | (not published) |

Full digests live in `app/src/main/java/dev/akhil/freebuff/models/BuiltInCatalog.kt`.
Licences: Gemma Terms of Use, Apache-2.0 ×2, MIT (whisper.cpp runtime and
weights), and each Piper voice card for its own voice.

---

## What the app deliberately does not do

* It does not claim a build succeeded without an artifact that passes zip/apk
  verification.
* It does not run a build on a remote server unless you configure and enable one.
* It does not listen in the background, use a wake word, or re-open the
  microphone after a reply.
* It does not hand a model's shell commands straight to the OS: the terminal has
  an allowlist, and the agent's tools are typed and path-checked.
* It does not hide Android limits: force-stop, revoked permissions and some
  manufacturer battery managers stop background downloads, and the UI says so.

---

## Documentation

* [docs/BUILD-LOG.md](docs/BUILD-LOG.md) — native build results and JNI symbol checks
* [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — module map, data model, execution flow
* [docs/PRIVACY.md](docs/PRIVACY.md) — permissions, storage, what leaves the device
* [docs/TESTING.md](docs/TESTING.md) — what was verified, how to re-run it
* [docs/PHASES.md](docs/PHASES.md) — the 14-phase plan and real status per phase

Developer: **Akhil**. Freebuff Mobile is developed by Akhil.
