# Architecture

> Actual project architecture, based on graphify exploration and source inspection.

## Project Structure

The repo is a multi-project workspace — several independent apps that share the Vela Voice SDK.

| Directory | Purpose |
|-----------|---------|
| `sdk/` | `vela-transcription-sdk` — Android library modules (Gradle Kotlin DSL), published to mavenLocal under `com.velavoice.sdk:*` |
| `velaboard/` | HeliBoard IME fork (privacy keyboard) integrating the SDK as its voice engine; nested git repo with its own graphify-out/ and AGENTS.md |
| `velavoice app/` | React Native / Expo companion app (Expo ~51, RN 0.74.1, TypeScript) with ModelManager, api services, correction-tracking tests |
| `ui/` | Design/PRD assets: logos, shader experiments, `velavoice_project_prd.md` |
| `scripts/` | Python helpers (`export_corrections.py` + pytest) |
| `docs/` | ADRs (`docs/adr/`), agents config (`docs/agents/`), research, init notes |
| `graphify-out/` | Knowledge graph (`graph.json`, `GRAPH_REPORT.md`) for agent queries |

## Key Modules

### SDK (`sdk/`) — `vela-transcription-sdk`

- **`vela-core`** (`com.velavoice.sdk:vela-core`) — Facade `VelaTranscriber` (builder pattern) tying recording, transcription, and cleaning. Streaming layer: `StreamingPipeline`, `StreamingTranscriber` (interface), `LocalStreamingTranscriber`, `CloudStreamingTranscriber`, `AudioRecorder`, `StreamingTranscriber`. Result types: `TranscriptionResult`, `VelaException`, `VelaRecordingCallback`.
- **`vela-whisper`** (`com.velavoice.sdk:vela-whisper`) — Whisper transcription engine. Kotlin JNI wrappers `WhisperEngine`, `WhisperConfig`, `AudioConverter` over native `cpp/` (bundled `whisper.cpp` + `ggml`). Loads `libwhisper.so` via `System.loadLibrary`, runs GGML `.bin` models on-device.
- **`vela-cleaner`** (`com.velavoice.sdk:vela-cleaner`) — `TextCleaner` pipeline: rule-based cleanup first (fillers, personal dictionary, whitespace) → optional ONNX Runtime GenAI LLM cleanup (`CleanerConfig`, `PersonalDictionary`, `DictionaryKeywords`). Requires minSdk 24.
- **`vela-voice-ui`** (`com.velavoice.sdk:vela-voice-ui`) — Classic (non-Compose) recording UI: `VoiceRecordingPane`, `WaveformView` (appcompat/material).

### velaboard (HeliBoard fork)

`app/` module: `LatinIME` service, `KeyboardSwitcher` (`.showVelaVoicePane()` gate via `isVelaReady()`), `VoiceInputMethodService` (in `velavoice app/src/native/`), settings screens (`VoiceSettingsScreen`, etc.). Depends on `vela-core` + `vela-voice-ui` from mavenLocal.

### velavoice app (React Native / Expo)

Entry `App.tsx` / `App.js` → components (`TranscriptionEditor`, `OverlayLogo`) → services (`api.ts`, `ModelManager.ts`) → utils (`editCalculator.ts`). SQLite-backed correction tracking with `*.test.ts`/`*.test.js` tests.

## Data Flow

1. **Input**: pre-recorded PCM 16-bit 16 kHz mono audio bytes, or live mic audio via `VelaTranscriber.startRecording(callback)` / `startStreaming()`.
2. **Transcription**: `vela-whisper` runs GGML Whisper on-device (JNI into `whisper.cpp`).
3. **Cleaning**: `TextCleaner` — rule-based pass, then optional on-device LLM (`onnxruntime-genai`) pass, then optional Scribe intent-based rewrite (Professional/Casual/Bullet Points/Email Draft/Proofread). `privacySensitive` inputs force-disable LLM/Scribe.
4. **Output**: `TranscriptionResult` committed to the target text field by the IME.

## External Dependencies & Integrations

- **Microsoft ONNX Runtime GenAI** `onnxruntime-genai-android:0.15.0` — NOT on Maven Central; published to mavenLocal from a local AAR (gitignored at `sdk/vela-cleaner/libs/`). Paired with `onnxruntime-android:1.22.0` from Maven Central.
- **Whisper/GGML** — vendored native code in `sdk/vela-whisper/src/main/cpp/` (`whisper.cpp`, `ggml`).
- **HeliBoard** — upstream AOSP keyboard fork.
- **Expo / React Native** for the companion app.
- Cloud transcription backends (Groq/OpenAI) supported as `CloudStreamingTranscriber` alternatives in settings.

## Entry Points

- **SDK facade**: `sdk/vela-core/src/main/kotlin/com/velavoice/sdk/VelaTranscriber.kt` — builder + `transcribe()` / `startRecording()` / `stopRecording()` / `release()`.
- **velaboard IME**: `velaboard/app/src/main/java/helium314/keyboard/latin/LatinIME.java` (mic trigger), `KeyboardSwitcher.java`.
- **RN app**: `velavoice app/App.tsx` (Expo entry), services `api.ts`, `ModelManager.ts`.
- **Scripts**: `scripts/export_corrections.py`.
- **CLI/build**: Gradle (`sdk/gradlew.bat`, `velaboard/gradlew.bat`), npm scripts (`velavoice app/package.json`).

## Build & Test Commands

- SDK compile: `cd sdk; .\gradlew.bat :vela-core:compileDebugKotlin :vela-whisper:compileDebugKotlin :vela-cleaner:compileDebugKotlin :vela-voice-ui:compileDebugKotlin`
- SDK tests: `cd sdk; .\gradlew.bat :vela-cleaner:testDebugUnitTest` (34 tests) — or per-module `:<module>:testDebugUnitTest`
- SDK publish: `cd sdk; .\gradlew.bat :vela-core:publishReleasePublicationToMavenLocal ...` (all 4 modules)
- velaboard: `cd velaboard; .\gradlew.bat :app:compileDebugKotlin` / `:app:testDebugUnitTest`
- RN app: `cd "velavoice app"; npm run ts:check` (tsc); tests via jest (`npx jest`)
- Python: `python scripts/test_export_corrections.py` (pytest-style)
- Prefer `--no-daemon`; stop a stalled daemon with `gradlew.bat --stop`.

## Knowledge Graph

- `graphify-out/graph.json` — knowledge graph (god nodes, communities, cross-file edges). Also `graphify-out/GRAPH_REPORT.md`.
- `velaboard/graphify-out/` — nested graph for the HeliBoard fork.
- Use `graphify query "<question>"`, `graphify path "<A>" "<B>"`, `graphify explain "<concept>"` before browsing source. Run `graphify update .` after modifying code.
