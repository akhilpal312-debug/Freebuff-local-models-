# Native sources

Two optional native engines live here. Neither is required for the Kotlin app to
build; without them the app says so plainly and never fabricates a reply.

```
cpp/
├── CMakeLists.txt      builds freebuff_llama (llama.cpp) when -PwithNative=true
├── llama_jni.cpp       JNI shim: model load, sampling loop, token callback
├── whisper_jni.cpp     JNI shim: whisper_full over 16 kHz float PCM
├── llama.cpp/          cloned by ../../../../scripts/fetch-native.sh (gitignored)
└── whisper.cpp/        cloned by the same script (gitignored)
```

## Build llama.cpp (the LLM engine)

```bash
./scripts/fetch-native.sh
sdkmanager "ndk;27.2.12479018" "cmake;3.22.1"
./gradlew assembleDebug -PwithNative=true
```

`LlamaNative` loads `libfreebuff_llama.so`. If the library is absent the Kotlin
`System.loadLibrary` throws, `LlamaNative.available` stays false, and
`NativeLlamaEngine.load()` returns `EngineFailure.NOT_BUILT` with a message that
names this exact command.

## Build whisper.cpp (speech recognition)

whisper.cpp vendors its own `ggml` target, which collides with llama.cpp's in a
single CMake project. Two supported options:

1. **Separate module (recommended).** Copy this directory's whisper shim into its
   own Gradle library module with its own `externalNativeBuild` block, producing
   `libfreebuff_whisper.so`. The Kotlin side (`WhisperStt`) needs no changes: it
   already loads `freebuff_whisper` independently and degrades with a clear
   message when it is missing.
2. **Prebuilt.** Run whisper.cpp's own Android build once and drop the resulting
   `libfreebuff_whisper.so` into `app/src/main/jniLibs/<abi>/`.

## Pinned versions

`scripts/fetch-native.sh` pins the tags so the shims match the C API. When you
bump a tag, re-check these symbols:

* `llama_model_load_from_file`, `llama_init_from_model`, `llama_model_get_vocab`
* `llama_batch_init` / `llama_batch_get_one`, `llama_decode`
* `llama_sampler_chain_init` + `llama_sampler_init_greedy/top_k/top_p/temp/dist`
* `llama_vocab_is_eog`, `llama_token_to_piece`
* `whisper_init_from_file_with_params`, `whisper_full`, `whisper_full_n_segments`,
  `whisper_full_get_segment_text`

## Memory notes for 2 GB devices

* `n_gpu_layers = 0`, mmap on, mlock off.
* Context default 2048, hard-capped at 4096 by `EngineManager`.
* Threads = `cores - 1`, capped at 4.
* Only one model is resident: `EngineManager` unloads before every load.
