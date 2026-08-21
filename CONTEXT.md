# Vela Voice - System & Domain Context

## System & Domain Context

Vela Voice is an on-device, privacy-first Android voice transcription and text-cleaning ecosystem. It pairs an offline C++/JNI Whisper transcription engine with an on-device LLM/rule-based cleaning pipeline, integrated into a customized privacy-focused AOSP Android keyboard provider (`velaboard`) and an Expo/React Native companion application (`velavoice app`).

---

## Ubiquitous Language & Terminology

To maintain domain clarity across teams and codebase documentation, strictly follow these naming standards:

- **Voice InputMethod (IME)**: Custom Android keyboard provider that captures audio, coordinates transcription, and inserts text directly into targeted input fields.
  - _Avoid_: Voice keyboard, transcription IME, custom keyboard.
- **Transcriber**: Core engine responsible for converting captured raw PCM audio into raw text offline on-device (`vela-core` facade + `vela-whisper` native runner).
  - _Avoid_: Speech-to-text, STT engine, Whisper runner.
- **Raw Transcript**: Direct, unformatted, unpunctuated text string output produced by the Transcriber from raw audio.
  - _Avoid_: Initial transcript, rough text.
- **Cleaner**: On-device post-processing pipeline (`vela-cleaner`) that refines, formats, strips filler words, applies custom dictionary replacements, and performs optional LLM/Scribe rewrites on the Raw Transcript.
  - _Avoid_: Post-processor, text cleaner, refiner.
- **Cleaned Transcript**: Final formatted, grammatically correct, filler-free text committed to the target text field.
  - _Avoid_: Final transcript, formatted text.
- **Scribe**: Context-aware intent-based rewrite system in `vela-cleaner` that restructures raw transcripts according to user-selected styles (*Professional*, *Casual*, *Bullet Points*, *Email Draft*, *Proofread*) using editor context (`ScribeInput`).
- **Privacy Guard**: Automatic security fallback that forces rule-based cleanup and disables LLM/Scribe whenever input fields are flagged as `privacySensitive = true` (password fields, PII).

---

## Architectural Breakdown & Project Modules

The codebase is organized as a multi-project workspace sharing the core Vela Voice Android SDK.

### 1. SDK (`sdk/` — `vela-transcription-sdk`)
Gradle Android Library modules published to `mavenLocal` under `com.velavoice.sdk:*`:
- **`vela-core`** (`com.velavoice.sdk:vela-core`): Public facade exposing `VelaTranscriber` (Builder pattern) tying together audio recording, Whisper transcription, and cleaner pipelines. Includes streaming capabilities (`StreamingPipeline`, `StreamingTranscriber`, `LocalStreamingTranscriber`, `CloudStreamingTranscriber`).
- **`vela-whisper`** (`com.velavoice.sdk:vela-whisper`): Native Whisper transcription engine wrapping `whisper.cpp` and `ggml` C++ libraries via Kotlin JNI (`WhisperEngine`, `WhisperConfig`, `AudioConverter`). Loads `libwhisper.so` to run GGML model files (`.bin`) on-device.
- **`vela-cleaner`** (`com.velavoice.sdk:vela-cleaner`): Multi-stage text processing pipeline:
  1. Rule-based cleanup (filler word removal, personal dictionary replacements, whitespace normalization).
  2. Optional on-device LLM cleanup via Microsoft ONNX Runtime GenAI (`onnxruntime-genai-android:0.15.0`).
  3. Scribe intent-based rewrite using surrounding editor context (`ScribeInput`).
  - *Requirement*: Requires Android `minSdk 24` due to ONNX Runtime GenAI runtime limits.
- **`vela-voice-ui`** (`com.velavoice.sdk:vela-voice-ui`): Native classic View components (`VoiceRecordingPane`, `WaveformView`) for recording controls and real-time audio visualization.

### 2. Keyboard Integration (`velaboard/`)
- **Location**: `velaboard/`
- **Fork**: Fork of [HeliBoard](https://github.com/HeliBorg/HeliBoard) (privacy-focused AOSP/OpenBoard keyboard).
- **Build Configurations**: NDK 27.1, `compileSdk 35`, `minSdk 23`, Kotlin 2.3.20.
- **SDK Dependencies**: Uses `com.velavoice.sdk:vela-core:1.0.0` and `vela-voice-ui:1.0.0` from `mavenLocal`.
- **Mic Trigger**: `LatinIME.java` checks `isVelaReady()` (verifying GGML Whisper model presence) to show the Vela voice pane or fallback gracefully to the system voice IME.
- **Voice Settings**: Configurable via **Settings -> Voice Input Settings** (model selection, local/Groq/OpenAI engine mode, LLM cleaner toggle, custom personal dictionary, target language, threading, filler word lists).

### 3. Companion Application (`velavoice app/`)
- **Location**: `velavoice app/`
- **Framework**: React Native / Expo (Expo ~51, RN 0.74.1, TypeScript).
- **Core Capabilities**:
  - `TranscriptionEditor`: Interactive UI canvas for reviewing, editing, and comparing Raw vs. Cleaned transcripts.
  - `ModelManager`: Download manager and switcher for Whisper GGML models and ONNX cleaner models.
  - `e2eCorrectionTracking`: SQLite-backed tracking logging user edits to evaluate model accuracy over time.
  - `editCalculator`: Levenshtein edit distance utility calculating transcript correction rates.

### 4. Design & PRD Assets (`ui/`)
- **Location**: `ui/`
- **Design Specifications**: `velavoice_project_prd.md` defining OLED-efficient dark design system, liquid audio visualizations, and 3 core operational hubs:
  - *The Voice Hub*: Recording interface and live library access.
  - *The Studio*: Transcript canvas with Raw vs. Cleaned diff visualizer.
  - *The Engine Room*: Model configuration center and API fallback keys.

### 5. Utility Scripts (`scripts/`)
- `export_corrections.py`: Python tool for exporting local correction tracking SQLite data for offline model fine-tuning and evaluation.

---

## Data Flow & Processing Lifecycle

```
[User Mic Input / Audio Bytes]
          │
          ▼
[vela-core: VelaTranscriber / AudioRecorder]
          │ (16 kHz 16-bit Mono PCM)
          ▼
[vela-whisper: WhisperEngine JNI -> libwhisper.so + GGML Model]
          │
          ▼ (Raw Transcript)
[vela-cleaner: TextCleaner Pipeline]
     ├── 1. Rule-Based Pass (Fillers, Personal Dict, Spacing)
     ├── 2. On-Device LLM Pass (ONNX Runtime GenAI) [Skipped if privacySensitive=true]
     └── 3. Scribe Pass (Intent Rewrite: Professional/Casual/etc.)
          │
          ▼ (Cleaned Transcript)
[Target Input Field / LatinIME / Companion App Canvas]
```

---

## Build & Setup Notes

1. **ONNX Runtime GenAI Dependency**:
   - `onnxruntime-genai-android:0.15.0` is published to `mavenLocal` from `sdk/vela-cleaner/libs/onnxruntime-genai-android-0.15.0.aar`.
2. **Whisper Model File**:
   - Requires `ggml-tiny.en.bin` or `ggml-base.en.bin` stored at the configured path on-device for local offline operation.
3. **Graphify Knowledge Graph**:
   - Knowledge graph is stored under `graphify-out/`.
   - Query code structure: `graphify query "<question>"`.
   - Update after edits: `graphify update .`.

---

## Additional Features Roadmap (Exploration & Recommended Additions)

To further enhance Vela Voice into an industry-leading on-device voice IME and companion application, the following feature additions are recommended:

### 1. Real-Time Streaming Transcription & Live Waveform Preview
- **Objective**: Replace batch post-recording processing with real-time streaming feedback inside `velaboard` and `vela-voice-ui`.
- **Implementation**: Expand `LocalStreamingTranscriber` in `vela-core` to process audio in 500ms sliding windows using Whisper's partial inference capability.
- **Value**: Reduces perceived latency to near zero; users see words appear on screen as they speak.

### 2. Dynamic Multi-Language Detection & Adaptive Cleaner Rules
- **Objective**: Automatically detect spoken language and dynamically switch Whisper transcription parameters and cleaning rules.
- **Implementation**: Utilize `whisper_lang_auto_detect` in JNI wrappers and maintain language-specific filler word dictionaries (e.g., German "ähm", Spanish "este", French "euh").
- **Value**: Enables seamless multilingual voice typing without forcing manual language switching in settings.

### 3. Unified Personal Dictionary & Correction Sync System
- **Objective**: Synchronize user vocabulary, acronyms, and custom replacements across `velaboard`, `velavoice app`, and SDK modules.
- **Implementation**: Build an Android `ContentProvider` or shared SQLite database allowing user edits in `TranscriptionEditor` (React Native app) to instantly train the personal dictionary in `vela-cleaner` (IME).
- **Value**: Learns user names, technical jargon, and domain-specific terms over time automatically.

### 4. Voice Activity Detection (VAD) & Intelligent Auto-Stop
- **Objective**: Auto-detect pause/silence end of utterance to automatically stop recording without forcing a manual mic tap.
- **Implementation**: Integrate Silero VAD or WebRTC VAD C++ engine into `AudioRecorder` in `vela-core`.
- **Value**: Hands-free voice typing experience; keyboard automatically transcribes and commits text upon natural speech pauses.

### 5. Customizable Scribe Presets & Prompt Engineering System
- **Objective**: Empower power users to define custom Scribe intent styles (e.g., "Code Commenter", "Slack Summary", "German Translator").
- **Implementation**: Expose a custom prompt template editor in `The Engine Room` (React Native app) and pass structured system prompts to `ScribeInput` in `vela-cleaner`.
- **Value**: Transforms Vela Voice from a simple voice keyboard into an arbitrary voice-driven AI productivity assistant.

### 6. In-App / In-Keyboard Offline Model Downloader & Manager UI
- **Objective**: Allow single-tap downloading, sha256 checksum verification, and switching of GGML Whisper models (Tiny, Base, Small) and ONNX LLMs directly inside the app/IME settings.
- **Implementation**: Implement an Android `DownloadManager` service in `ModelManager` with HuggingFace Hub mirror endpoints and local storage management.
- **Value**: Removes manual ADB file copying prerequisites for end users.

### 7. Encrypted Local Audio Snippet Cache & Re-Transcription Studio
- **Objective**: Save temporary encrypted PCM snippets locally to enable re-listening or re-cleaning raw voice notes with different Scribe styles.
- **Implementation**: Implement AES-GCM encrypted audio storage in `velavoice app` paired with `TranscriptionEditor` timeline controls.
- **Value**: Ensures users never lose valuable audio dumps if transcription output needs re-formatting or manual review.

### 8. End-to-End Encrypted Cloud Boost Fallback (Groq / OpenAI)
- **Objective**: Provide high-accuracy cloud fallback when offline models are unavailable or complex audio requires higher parameter models.
- **Implementation**: Enhance `CloudStreamingTranscriber` in `vela-core` with client-side API key encryption, custom endpoints (Groq, OpenAI, Ollama local server), and local PII redaction before transmission.
- **Value**: Gives users the flexibility of instant offline speed or cloud-grade accuracy on demand.

### 9. Smartwatch / Wear OS Voice Shortcut Companion
- **Objective**: Record voice notes on Wear OS smartwatches and sync raw/cleaned transcripts directly to the Android phone IME clipboard or companion app library.
- **Implementation**: Build a lightweight Wear OS companion tile communicating via Android Wearable Data Layer API.
- **Value**: Expands input modality to wearable devices.

### 10. Privacy-Preserving On-Device Evaluation Harness & Benchmarking
- **Objective**: Continuously evaluate transcription accuracy (Word Error Rate - WER) and LLM cleaner quality locally without sending data to external servers.
- **Implementation**: Extend `scripts/export_corrections.py` and `editCalculator.ts` into an automated benchmark suite comparing Raw vs. Cleaned vs. User Corrected text.
- **Value**: Provides concrete metrics on cleaner efficacy and helps fine-tune ONNX models locally.
