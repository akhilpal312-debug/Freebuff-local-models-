# Architecture

Single Gradle module (`:app`), strict package boundaries. Twenty separate Gradle
modules would multiply configuration and incremental-compile cost on the 2-core
machines and 2 GB phones this project targets, so the boundaries are enforced by
package + interface instead.

```
dev.akhil.freebuff
├── core/           AppContainer, AppStorage, DeviceProfile, NetworkMonitor, Format, Notifications
├── data/db/        Room entities, DAOs, AppDatabase
├── data/prefs/     DataStore settings (AppSettings)
├── security/       SecureStore (Android Keystore, AES-GCM)
├── design/         Theme, components, vendored icon set
├── ai/             AiMode, SystemPrompts, ChatRepository, ChatOrchestrator
├── inference/      InferenceEngine, NativeLlamaEngine (+ LlamaNative), RemoteEngine, EngineManager, PromptRenderer
├── models/         ModelSpec, BuiltInCatalog, GgufInspector, ModelInstaller, DownloadWorker
├── networking/     HuggingFaceClient (metadata resolution)
├── projects/       WorkspaceManager, ProjectTemplates
├── terminal/       TerminalRunner (allowlisted, path-jailed)
├── agent/          AgentTools/ToolRouter, TaskOrchestrator (+ IntentParser, ContentExtractor)
├── research/       SearchProviders, PageFetcher, ResearchEngine
├── build/          BuildRunner (capability probe, Gradle invocation, artifact verification)
├── voice/          VoiceState, AudioCapture, VoiceAssistant, stt/, tts/
└── ui/             MainActivity, AppViewModel, shell/, chat/, models/, projects/, terminal/, tasks/, research/, history/, settings/, onboarding/
```

## Layering

```
Compose screens  →  AppViewModel  →  coordinators (Chat, Task, Voice, Research)
                                          │
                       interfaces ────────┤
                       InferenceEngine    ├── NativeLlamaEngine (llama.cpp JNI)
                       SearchProvider     ├── RemoteEngine (opt-in, labelled online)
                       TtsEngine          ├── DuckDuckGo / Wikipedia / Tavily
                                          └── SystemTts / PiperTts
                                          │
                                   Room + DataStore + app-private files
```

`AppContainer` is a manual service locator: no annotation processor, no generated
graph, and every dependency is trivially replaceable in a test.

## Data model

| Table | Purpose |
| --- | --- |
| `conversations` | Chat threads (title, model, mode, timestamps) |
| `messages` | Turns with status (`STREAMING`/`COMPLETE`/`STOPPED`/`ERROR`), token count, elapsed time, remote flag |
| `model_records` | Catalogue + install state (`downloadedBytes`, `installedPath`, `sha256`) |
| `projects` | Workspace records pointing at app-private directories |
| `tasks`, `task_steps` | Agent runs, per-step status, verification text, reports |
| `research_sessions`, `research_sources` | Questions, plans, reports, cited URLs with verification state |
| `terminal_entries` | Command history with exit codes |
| `build_records` | Build attempts, log paths, artifact paths, verification flag |

## Chat turn flow

```
send(text)
  → ChatRepository.addUserMessage
  → ChatOrchestrator.generate
      → SystemPrompts.forMode(mode)   (+ project file listing in coding/build modes)
      → context budget trim (tail of conversation)
      → EngineManager.active engine .stream(GenRequest)
          → PromptRenderer applies the model's chat template (Gemma / ChatML / Llama-2)
          → llama.cpp samples tokens → TokenSink → Flow<GenEvent.Token>
      → throttled DB writes (~3/s) while streaming
      → finish status: COMPLETE | STOPPED | ERROR   (ERROR stores the real reason)
```

No branch of this path can produce text without an engine. When
`LlamaNative.available` is false the engine returns
`EngineFailure.NOT_BUILT`, and the UI shows exactly that.

## Model lifecycle

```
Models screen → ModelInstaller.enqueueInstall(list)
  → one unique WorkManager chain (sequential; KEEP policy)
  → DownloadWorker per asset
      constraints: unmetered (unless allowed), storage-not-low
      HTTP Range resume → .part file → digest + length check
      GGUF header parse (architecture must be in SupportedArchitectures)
      atomic rename → DB status INSTALLED
  → EngineManager.loadLocal(id)
      RAM headroom check → unload previous model → llama_model_load_from_file
      only one resident model, ever
```

`ModelInstaller.seedCatalog()` reconciles DB state against the filesystem on every
cold start, so a deleted or truncated file is never reported as installed.

## Agent flow

```
TaskOrchestrator.submit(request, mode, projectId)
  → IntentParser.classify      (deterministic keyword intent, not model guesswork)
  → IntentParser.plan          (bounded step list)
  → per step: create project / generate one file / check / verify / report
      GENERATE_FILE asks the model for one file, extracts the code block,
      writes it, and fails the task if the model returned nothing usable
      CHECK parses the manifest XML or verifies index.html references exist
      VERIFY lists files on disk and fails when nothing was produced
  → status COMPLETED only when every step passed and files exist on disk
```

Tools available to the model (`list_files`, `read_file`, `write_file`,
`search_files`, `run_tests`, `delete_file`) are typed, parsed from
`TOOL <name> {json}` lines, and executed by `ToolRouter` inside the project
directory only. `run_tests` reports honestly that Android has no JDK/Node/Python.

## Memory strategy (2–3 GB devices)

* one LLM resident (unload-before-load, verified by `EngineManager` mutex)
* context window configurable 512–4096, default 2048
* mmap loading, `n_gpu_layers = 0`, threads capped at `cores - 1` (max 4)
* audio buffers released before transcription; TTS and microphone never overlap
* streaming with bounded prompt length; hard caps on tool output (64 KB) and task logs (20 KB)
* sequential downloads, `requiresStorageNotLow`, 64 KB buffers

## Native layer

`app/src/main/cpp/llama_jni.cpp` and `whisper_jni.cpp` are thin JNI shims over the
upstream C APIs, built by CMake only when `-PwithNative=true`. They are pinned by
`scripts/fetch-native.sh` to `llama.cpp b4739` and `whisper.cpp v1.7.4`; bump the
pins and re-check the shims together.
