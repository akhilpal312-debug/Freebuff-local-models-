# Phases and real status

The original plan had 14 phases. Here is what each one actually reached, with no
rounding up. "Implemented" means the code exists and compiles; "verified" means it
was executed (unit test or build); "device-verified" means it ran on Android
hardware, which this environment did not have.

| Phase | Scope | Code | Verified here |
| --- | --- | --- | --- |
| 1 | Architecture and project foundation | yes | builds |
| 2 | ChatGPT-style UI + local database | yes | builds; Room schema compiles (KSP) |
| 3 | llama.cpp integration and first model | JNI bridge, CMake, engine manager | native build pending (see BUILD-LOG) |
| 4 | Multi-model manager + automatic downloads | yes | catalogue hashes verified against the hub |
| 5 | Whisper + Piper voice integration | JNI, ONNX, Devanagari G2P | phonemizer + planner unit-tested |
| 6 | Sequential tap-to-talk assistant | state machine, VAD, half-duplex | state machine compiles; device audio untested |
| 7 | Coding agent + project manager | tools, task orchestrator, workspace | intent/plan/extraction unit-tested |
| 8 | Restricted developer terminal | allowlist, path jail, timeout | logic compiles; `/system/bin` untested |
| 9 | Website preview + Android project generator | WebView preview, real Gradle templates | templates are valid project skeletons |
| 10 | APK/AAB build pipeline | capability probe, artifact verifier | detector reports missing JDK/SDK/Gradle honestly |
| 11 | Deep thinking + planning modes | prompts, plan mode | prompt template unit-tested |
| 12 | Web search + deep research | 3 providers, page reader, citation check | provider adapters compile; live calls untested |
| 13 | Task manager + completed-task history | tasks/steps tables, reports | schema compiles |
| 14 | Performance, privacy, release prep | settings, memory guards, docs | APK built; release signing not configured |

## Deliberate deviations

1. **Single Gradle module.** The plan suggested 20 modules. Package boundaries
   with interfaces give the same replaceability without multiplying AGP/KSP
   overhead on low-end build machines. Documented in `docs/ARCHITECTURE.md`.
2. **No scripted model installs.** The plan's `hf download` commands are
   developer references only; the app implements HTTPS + Range + SHA-256 itself
   and never ships a CLI dependency.
3. **On-device Android builds are refused, not faked.** An Android app sandbox has
   no JDK, SDK or Gradle. `BuildRunner` probes for them, then reports the exact
   blocker and offers project export.
4. **English TTS uses the platform engine.** A rule-based English G2P would be
   guesswork; the platform engine is real, local and offline once voice data is
   installed. Hindi uses Piper with a real Devanagari phonemiser.
5. **No wake word, ever.** Only `VoiceAssistant.startTurn` opens the microphone,
   and it is called from the mic button.

## Next steps that would make this production-ready

1. Run the phase-3 native build and test on an arm64 phone with 3–4 GB RAM.
2. Add instrumented tests for Room, the voice state machine and WorkManager.
3. Replace the deterministic intent parser with a small fine-tuned router if a
   suitable model becomes available.
4. Add a second Piper English voice plus an eSpeak-compatible phonemiser so
   English neural TTS does not depend on the platform engine.
5. Release signing config + Play packaging (AAB) once device testing passes.
