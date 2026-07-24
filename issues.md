# Combined Issues: Offline Transcription IME

Status: ready-for-agent

---

## Issue 01: Setup Project Scaffolding & Native Android IME Service
- **Status**: ready-for-agent
- **Description**: Initialize the separate Expo project structure and implement the boilerplate native Android `InputMethodService` in Kotlin.
- **Tasks**:
  1. Scaffold Expo project with TypeScript.
  2. Configure `expo-build-properties` or custom config plugins to inject native Android `InputMethodService` into the Android Manifest.
  3. Implement basic Kotlin IME class extending `InputMethodService` that renders a dummy custom keyboard layout.
- **Verification**: Keyboard is selectable under Android Settings -> Language & Input and loads on the screen.

---

## Issue 02: Model Downloader and Scoped Storage Manager
- **Status**: ready-for-agent
- **Description**: Build the UI screen in Expo to download and manage the on-device model files safely.
- **Tasks**:
  1. Design a dashboard displaying download status for Transcriber (Whisper) and Cleaner (Llama/Gemma) models.
  2. Implement download queue using `expo-file-system`.
  3. Save model files to `context.filesDir` (Scoped Storage).
  4. Perform SHA-256 integrity checks post-download and record paths in an Expo SQLite database.
- **Verification**: Models download successfully, verified via SHA-256, and paths are logged in SQLite.

---

## Issue 03: Native IME Voice Typing Pane and Waveform UI
- **Status**: ready-for-agent
- **Description**: Implement the custom Kotlin layout for the Voice Typing Pane that replaces the standard keyboard key layout during recording.
- **Tasks**:
  1. Design XML layout for Voice Typing Pane containing Waveform visualizer, "Stop & Clean", "Stop Raw", and "Cancel" buttons.
  2. Bind audio capture volume levels to visualizer.
  3. Wire up button listeners to start/stop native Android `AudioRecord`.
- **Verification**: Tapping Voice Input button loads the Voice Typing Pane showing active mic volume wave.

---

## Issue 04: On-device Transcriber Engine (Whisper) Integration
- **Status**: ready-for-agent
- **Description**: Integrate the offline transcription engine into the native Android Service.
- **Tasks**:
  1. Compile and link `whisper.cpp` or TFLite Whisper binaries in Android build.gradle.
  2. Implement Kotlin bridge to pass recorded PCM 16kHz audio buffer to the Whisper engine.
  3. Load model path from Shared Preferences / SQLite and produce Raw Transcript.
- **Verification**: Spoken audio is transcribed on-device to raw text offline.

---

## Issue 05: Hybrid Cleaner Pipeline (Regex + LLM)
- **Status**: ready-for-agent
- **Description**: Implement the post-processing Cleaner module using Kotlin regex and ONNX Runtime.
- **Tasks**:
  1. Implement rule-based pre-processor in Kotlin to remove common fillers (um, ah, like).
  2. Integrate ONNX Runtime or MediaPipe LLM Inference API in Kotlin to load the downloaded Llama/Gemma model.
  3. Formulate prompts and execute LLM inference offline to refine grammatical errors and apply capitalization.
- **Verification**: Raw text with errors/fillers is cleaned and output as formatted Cleaned Transcript.

---

## Issue 06: IME Commit Integration & Final End-to-End Testing
- **Status**: ready-for-agent
- **Description**: Bind the pipeline outputs to the active Android input field and test the end-to-end user workflow.
- **Tasks**:
  1. Wire output from Transcriber / Cleaner to `InputConnection.commitText()`.
  2. Test "Stop Raw" commits transcript directly.
  3. Test "Stop & Clean" commits cleaned/formatted transcript.
  4. Measure battery/RAM consumption to prevent IME background crashes.
- **Verification**: User opens the keyboard in any app, speaks, stops, and the cleaned text is committed to the field.
