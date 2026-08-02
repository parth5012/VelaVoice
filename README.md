# Vela Voice SDK

On-device voice transcription and text-cleaning SDK for the Vela Voice keyboard (VelaBoard).

The SDK is a set of Android library modules published to mavenLocal under `com.velavoice.sdk:*`:

| Module | Artifact | Purpose |
|--------|----------|---------|
| `vela-core` | `com.velavoice.sdk:vela-core` | Facade (`VelaTranscriber`) tying recording, transcription, and cleaning together |
| `vela-whisper` | `com.velavoice.sdk:vela-whisper` | Whisper transcription engine (native JNI) |
| `vela-cleaner` | `com.velavoice.sdk:vela-cleaner` | Rule-based text cleanup + optional on-device LLM cleanup / Scribe rewrite |
| `vela-voice-ui` | `com.velavoice.sdk:vela-voice-ui` | Recording UI components (waveform, recording pane) — classic Views (appcompat/material) |

`vela-cleaner` requires **Android minSdk 24** (`onnxruntime-genai-android` enforces it);
the other modules target minSdk 23. All modules use Java/Kotlin 17.

---

## Modules

### vela-core

Public facade for the whole SDK. The core entry point is `VelaTranscriber` (builder pattern):

```kotlin
val transcriber = VelaTranscriber.Builder(context)
    .whisperModel(modelPath)                 // path to a whisper GGML model (.bin)
    .language("en")
    .threads(4)
    .useLlmCleaner(true, llmModelPath)       // optional on-device LLM cleanup
    .personalDictionary(dict)                // optional per-user replacements
    .customFillers(listOf("um", "ah"))       // optional filler words to strip
    .scribe(true, defaultStyle = "Professional")
    .build()

// Pre-recorded PCM 16-bit 16 kHz mono audio
val result: TranscriptionResult = transcriber.transcribe(audioBytes)

// Live recording
transcriber.startRecording(callback)
transcriber.stopRecording()

transcriber.release()
```

`ScribeInput` carries editor context from the IME for a single rewrite call. When
`privacySensitive = true`, Scribe/LLM cleanup is force-disabled for that call and only
local rule-based cleanup runs (passwords/PII never reach the LLM).

### vela-whisper

Native Whisper transcription engine. Loads `libwhisper.so` via `System.loadLibrary`
and runs a GGML model file on-device. Configure via `WhisperConfig` or use
`VelaTranscriber` directly.

### vela-cleaner

Text cleaning pipeline:

1. **Rule-based** cleanup first (filler-word removal, personal dictionary, whitespace).
2. **Optional LLM cleanup** via ONNX Runtime GenAI when `useLlm = true` and a model is loaded.
3. **Scribe** (intent-based rewrite) when `scribeEnabled = true` — the raw input is rewritten
   in a requested style (Professional, Casual, Bullet Points, Email Draft, Proofread) using
   surrounding editor context and app metadata.

If the LLM model is missing, fails to load, or generation fails, `TextCleaner` silently
falls back to the rule-based result (verified by the 34 unit tests).

### vela-voice-ui

Classic (non-Compose) recording UI components (`VoiceRecordingPane`, `WaveformView`)
built on appcompat/material.

---

## On-device LLM dependencies

`vela-cleaner` uses **Microsoft ONNX Runtime GenAI** for on-device LLM inference:

- `com.microsoft.onnxruntime:onnxruntime-genai-android:0.15.0` — GenAI Java API + native
  `libonnxruntime-genai.so` / `libonnxruntime-genai-jni.so`. **This artifact is NOT on
  Maven Central.**
- `com.microsoft.onnxruntime:onnxruntime-android:1.22.0` — provides `libonnxruntime.so`,
  which `libonnxruntime-genai.so` loads at runtime (Maven Central).

### One-time local setup (required before building)

Because `onnxruntime-genai-android` is not published to a Maven repository, it must be
published to **mavenLocal** on each machine that builds the SDK or VelaBoard:

```powershell
# 1. Download the release AAR (20.6 MB)
#    https://github.com/microsoft/onnxruntime-genai/releases/download/v0.15.0/onnxruntime-genai-android-0.15.0.aar
#    Save it as: vela-cleaner/libs/onnxruntime-genai-android-0.15.0.aar
#    (the file is gitignored; keep it out of version control)

# 2. Publish it to mavenLocal as a normal Maven coordinate
#    A standalone Gradle build is provided under
#    C:\Users\DELL\AppData\Local\Temp\opencode\genai-aar-publish
#    or re-create it: a minimal project with maven-publish that declares
#    groupId=com.microsoft.onnxruntime, artifactId=onnxruntime-genai-android,
#    version=0.15.0 and publishes the AAR file. Then run:
gradlew.bat --no-daemon -p <path-to-publish-project> publishToMavenLocal
```

After this step, `:vela-cleaner:publishReleasePublicationToMavenLocal` resolves the
GenAI dependency from `~/.m2/repository`.

> **Telemetry note:** the GenAI AAR bundles a Microsoft 1DS telemetry SDK
> (`com.microsoft.applications.events`, `libmat.so`). The SDK calls
> `GenAI.setTelemetry(false)` before loading any model so telemetry is disabled.
> The AAR's `INTERNET` permission may still merge into the consuming app manifest.

### Model format

The LLM path passed to `useLlmCleaner` / `CleanerConfig.llmModelPath` should point to a
**model directory** containing `genai_config.json` plus the ONNX model files, or a single
`.onnx` file. Example compatible models include
`onnx-community/Llama-3.2-1B-Instruct-ONNX` (note: this repo uses external data files —
the full `model.onnx_data*` set must be downloaded, not just `model.onnx`).

---

## Building & publishing

```powershell
# Compile + test the whole SDK
gradlew.bat :vela-core:compileDebugKotlin :vela-whisper:compileDebugKotlin :vela-cleaner:compileDebugKotlin :vela-voice-ui:compileDebugKotlin

# Unit tests (vela-cleaner: 34 tests)
gradlew.bat :vela-cleaner:testDebugUnitTest

# Publish all modules to mavenLocal (consumed by VelaBoard)
gradlew.bat :vela-core:publishReleasePublicationToMavenLocal :vela-whisper:publishReleasePublicationToMavenLocal :vela-cleaner:publishReleasePublicationToMavenLocal :vela-voice-ui:publishReleasePublicationToMavenLocal
```

If the Gradle daemon stalls, stop it with `gradlew.bat --stop`; prefer `--no-daemon`
for verification builds.

---

## Consuming from VelaBoard

VelaBoard's `app` module depends on the SDK modules via mavenLocal:

```kotlin
implementation("com.velavoice.sdk:vela-core:1.0.0")
implementation("com.velavoice.sdk:vela-cleaner:1.0.0")
implementation("com.velavoice.sdk:vela-voice-ui:1.0.0")
```

`mavenLocal()` must be present in the app's repositories (VelaBoard already declares it).
The app's `minSdk` must be **24 or higher**.

---

## License

Proprietary. See the Vela Voice project documentation for details.
