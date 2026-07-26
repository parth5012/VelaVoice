# Technical Blueprint: vela-transcription-sdk + HeliBoard Integration

**Status:** Draft  
**Date:** 2026-07-26  

---

## 1. Overview & Goals

### Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│ Phase 1: vela-transcription-sdk (Android Library, Apache 2.0)  │
│                                                                 │
│  ┌────────────┐ ┌──────────────┐ ┌────────────┐                │
│  │vela-whisper│ │vela-cleaner  │ │vela-voice- │                │
│  │KKKt+JNI+CPP│ │KKKt          │ │ui          │                │
│  │WhisperEngine│ │TextCleaner   │ │WaveformView│                │
│  │whisper.cpp │ │PersonalDict  │ │VoicePane   │                │
│  └──────┬─────┘ └──────┬───────┘ └──────┬─────┘                │
│         └──────────────┼────────────────┘                       │
│                  ┌─────┴──────┐                                 │
│                  │vela-core   │  ← Public Facade                │
│                  │VelaTranscriber │                              │
│                  └────────────┘                                 │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼ (implementation dependency)
┌─────────────────────────────────────────────────────────────────┐
│ Phase 2: HeliBoard Fork (GPL-3.0)                              │
│                                                                 │
│  LatinIME.java ──→ onEvent(VOICE_INPUT) ──→ VelaTranscriber     │
│  KeyboardSwitcher ──→ showVelaVoicePane()                       │
│  build.gradle ──→ implementation("com.velavoice:sdk:1.0")       │
└─────────────────────────────────────────────────────────────────┘
```

### Goals

1. **Decouple** the transcription engine from the Expo/React Native shell into a pure Android library
2. **Open it up** so any keyboard (HeliBoard, FlorisBoard, etc.) can use offline voice typing
3. **Fork HeliBoard** to demonstrate integration and give users a full-featured keyboard with voice
4. **Preserve the existing Vela Voice app** — it becomes an SDK-dependent management UI instead of containing inline engine code

---

## 2. Phase 1: vela-transcription-sdk

### 2.1 Module Structure

```
vela-transcription-sdk/
├── settings.gradle.kts
├── build.gradle.kts                    ← Root build config
├── gradle.properties
├── gradle/
│   └── libs.versions.toml              ← Version catalog
│
├── vela-core/                          ← Public Facade Module
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/velavoice/sdk/
│       ├── VelaTranscriber.kt          ← Builder pattern entry point
│       ├── TranscriptionResult.kt      ← Data class
│       ├── VelaRecordingCallback.kt    ← Callback interface
│       ├── AudioRecorder.kt            ← PCM 16kHz capture logic
│       └── VelaException.kt            ← Typed exceptions
│
├── vela-whisper/                       ← Whisper Engine Module
│   ├── build.gradle.kts
│   ├── src/main/kotlin/com/velavoice/sdk/whisper/
│   │   ├── WhisperEngine.kt            ← JNI bridge
│   │   ├── WhisperConfig.kt            ← Model path, language, threads
│   │   └── AudioConverter.kt           ← PCM byte → float conversion
│   └── src/main/cpp/
│       ├── CMakeLists.txt
│       ├── whisper.cpp / whisper.h
│       ├── ggml.c / ggml.h
│       ├── ggml-alloc.c / ggml-alloc.h
│       ├── ggml-backend.c / ggml-backend.h
│       ├── ggml-quants.c / ggml-quants.h
│       ├── ggml-impl.h
│       ├── ggml-backend-impl.h
│       └── whisper-jni.cpp
│
├── vela-cleaner/                       ← Text Cleaning Module
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/velavoice/sdk/cleaner/
│       ├── TextCleaner.kt              ← Regex + LLM cleaner
│       ├── CleanerConfig.kt            ← Filler list, LLM toggle
│       └── PersonalDictionary.kt       ← Interface (was SQLite-dependent)
│
└── vela-voice-ui/                      ← Optional UI Module
    ├── build.gradle.kts
    └── src/main/kotlin/com/velavoice/sdk/ui/
        ├── WaveformView.kt             ← Audio visualizer bar view
        ├── VoiceRecordingPane.kt       ← Self-contained recording layout
        └── VoiceRecordingPane.kt       ← … companion builder
```

### 2.2 Public API Surface

#### `VelaTranscriber` — Entry Point

```kotlin
// vela-core/src/main/kotlin/com/velavoice/sdk/VelaTranscriber.kt
package com.velavoice.sdk

data class TranscriptionResult(
    val rawTranscript: String,
    val cleanedTranscript: String,
    val durationMs: Long
)

interface VelaRecordingCallback {
    fun onAmplitude(normalized: Float)
    fun onResult(result: TranscriptionResult)
    fun onError(error: VelaException)
}

sealed class VelaException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ModelNotFound(modelPath: String) : VelaException("Model not found: $modelPath")
    class WhisperError(msg: String) : VelaException(msg)
    class AudioCaptureFailed(msg: String) : VelaException(msg)
    class InvalidAudio(msg: String) : VelaException(msg)
}

class VelaTranscriber private constructor(
    private val whisperEngine: WhisperEngine,
    private val textCleaner: TextCleaner?,
    private val audioRecorder: AudioRecorder
) {
    class Builder(private val context: Context) {
        private var whisperModelPath: String? = null
        private var language: String = "en"
        private var threads: Int = 4
        private var useLlmCleaner: Boolean = false
        private var llmModelPath: String? = null
        private var personalDictionary: PersonalDictionary? = null
        private var customFillers: List<String>? = null

        fun whisperModel(path: String) = apply { whisperModelPath = path }
        fun language(lang: String) = apply { language = lang }
        fun threads(n: Int) = apply { threads = n }
        fun useLlmCleaner(enable: Boolean, modelPath: String? = null) = apply {
            useLlmCleaner = enable; llmModelPath = modelPath
        }
        fun personalDictionary(dict: PersonalDictionary) = apply { personalDictionary = dict }
        fun customFillers(fillers: List<String>) = apply { customFillers = fillers }

        fun build(): VelaTranscriber {
            val modelPath = whisperModelPath
                ?: throw IllegalStateException("whisperModel() is required")
            val whisperConfig = WhisperConfig(modelPath, language, threads)
            val engine = WhisperEngine(whisperConfig)
            val cleaner = if (personalDictionary != null || customFillers != null) {
                TextCleaner(CleanerConfig(
                    useLlm = useLlmCleaner,
                    llmModelPath = llmModelPath,
                    personalDictionary = personalDictionary,
                    customFillers = customFillers
                ))
            } else null
            val recorder = AudioRecorder()
            return VelaTranscriber(engine, cleaner, recorder)
        }
    }

    /** Transcribe pre-recorded PCM 16-bit 16kHz mono audio bytes */
    fun transcribe(audioBytes: ByteArray): TranscriptionResult {
        val raw = whisperEngine.transcribe(audioBytes)
        val cleaned = textCleaner?.clean(raw) ?: raw
        val durationMs = ((audioBytes.size / 2) / 16L)  // 16 samples/ms
        return TranscriptionResult(raw, cleaned, durationMs)
    }

    /** Start recording and transcribe live */
    fun startRecording(callback: VelaRecordingCallback) {
        audioRecorder.start(whisperEngine, textCleaner, callback)
    }

    /** Stop recording and commit transcription */
    fun stopRecording(clean: Boolean = true) {
        audioRecorder.stop(clean)
    }

    fun release() { whisperEngine.free(); audioRecorder.release() }
}
```

#### `PersonalDictionary` — Interface (Replaces SQLite Dependency)

```kotlin
// vela-cleaner/src/main/kotlin/com/velavoice/sdk/cleaner/PersonalDictionary.kt
package com.velavoice.sdk.cleaner

interface PersonalDictionary {
    /** Return word → replacement pairs. Applied in order, case-insensitive. */
    fun getEntries(): List<Pair<String, String>>
}
```

#### `AudioRecorder` — Extracted from VoiceInputMethodService

```kotlin
// vela-core/src/main/kotlin/com/velavoice/sdk/AudioRecorder.kt
package com.velavoice.sdk

/** PCM 16kHz 16-bit mono capture, extracted from VoiceInputMethodService lines 180-301 */
class AudioRecorder {
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private val recordedAudioData = ByteArrayOutputStream()
    private var recordingThread: Thread? = null

    fun start(whisper: WhisperEngine, cleaner: TextCleaner?, callback: VelaRecordingCallback) { /* ... */ }
    fun stop(clean: Boolean) { /* ... */ }
    fun release() { /* ... */ }
}
```

### 2.3 File Migration Map

| Source File | Lines | Target Module | Target File | Changes Needed |
|---|---|---|---|---|
| `WhisperEngine.kt` | All 91 | `vela-whisper` | `WhisperEngine.kt` | Package `com.velavoice.app` → `com.velavoice.sdk.whisper`. Remove fallback (no hardcoded strings). Accept `WhisperConfig` in constructor instead of bare path. |
| `whisper-jni.cpp` | All 59 | `vela-whisper` cpp/ | `whisper-jni.cpp` | Rename JNI functions from `Java_com_velavoice_app_WhisperEngine_*` → `Java_com_velavoice_sdk_whisper_WhisperEngine_*` |
| `whisper.cpp/h` | All | `vela-whisper` cpp/ | as-is | No changes |
| `ggml.*` (7 files) | All | `vela-whisper` cpp/ | as-is | No changes |
| `CMakeLists.txt` | All 26 | `vela-whisper` cpp/ | as-is | No changes |
| `TextCleaner.kt` | All 134 | `vela-cleaner` | `TextCleaner.kt` | Package change. Remove `applyPersonalDictionary()` SQLite → use `PersonalDictionary` interface. `clean()` takes `String` not `Context`. Remove `findDatabaseFile()`. |
| `WaveformView.kt` | All 63 | `vela-voice-ui` | `WaveformView.kt` | Package change only. |
| `VoiceInputMethodService.kt` | 180-301 | `vela-core` | `AudioRecorder.kt` | Extract recording thread, AudioRecord setup/teardown. Remove IME-specific: `commitText()`, model path from SQLite. Accept callback instead. |
| `VoiceInputMethodService.kt` | 42-170 | `vela-voice-ui` | `VoiceRecordingPane.kt` | Extract the view builder as a reusable pane that returns LinearLayout. Accept `VelaRecordingCallback` instead of calling methods on `this`. |

### 2.4 JNI Renames

```cpp
// OLD: package com.velavoice.app
Java_com_velavoice_app_WhisperEngine_nativeInit
Java_com_velavoice_app_WhisperEngine_nativeTranscribe
Java_com_velavoice_app_WhisperEngine_nativeFree

// NEW: package com.velavoice.sdk.whisper
Java_com_velavoice_sdk_whisper_WhisperEngine_nativeInit
Java_com_velavoice_sdk_whisper_WhisperEngine_nativeTranscribe
Java_com_velavoice_sdk_whisper_WhisperEngine_nativeFree
```

**ABI Targets (in `build.gradle.kts`):**
```kotlin
android {
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }
}
```

### 2.5 Build Configuration

**Root `build.gradle.kts`:**
```kotlin
plugins {
    id("com.android.library") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
```

**`vela-whisper/build.gradle.kts`:**
```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.velavoice.sdk.whisper"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            cmake {
                cppFlags("-O3 -std=c++11")
                abiFilters("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }
    externalNativeBuild {
        cmake { path("src/main/cpp/CMakeLists.txt") }
    }
}
```

**Publishing:** Use JitPack (simplest for open-source):
```kotlin
// root build.gradle.kts
plugins {
    id("com.android.library") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.vanniktech.maven.publish") version "0.28.0" apply false
}
```
Or publish as a local AAR via `./gradlew :vela-core:assembleRelease` and consumers `implementation files('libs/vela-core-release.aar')`.

### 2.6 Vela Voice App Migration

The existing Expo app switches from inline code to SDK dependency:

```
vela-voice-app/                         ← renamed from Vela Voice
├── android/app/build.gradle
│   dependencies {
│       implementation("com.velavoice:vela-transcription-sdk:1.0.0")
│       // Remove inline references to WhisperEngine.kt, TextCleaner.kt, etc.
│   }
├── src/native/                          ← DELETE these files:
│   ├── WhisperEngine.kt                 (now in SDK)
│   ├── TextCleaner.kt                   (now in SDK)
│   ├── WaveformView.kt                  (now in SDK)
│   ├── whisper/*                        (now in SDK)
│   └── AudioRecorder.kt                 (now in SDK)
├── src/native/VoiceInputMethodService.kt ← REWRITE
│   // Instead of inline WhisperEngine, use:
│   val vela = VelaTranscriber.Builder(this)
│       .whisperModel(modelPath)
│       .language("en")
│       .build()
```

---

## 3. Phase 2: HeliBoard Fork Integration

### 3.1 Fork Setup

```bash
# Clone HeliBoard
git clone https://github.com/Helium314/HeliBoard.git VelaBoard
cd VelaBoard
git remote rename origin upstream
git remote add origin https://github.com/velavoice/VelaBoard.git
```

**Dependency addition (`app/build.gradle.kts`):**
```kotlin
dependencies {
    implementation("com.velavoice:vela-transcription-sdk:1.0.0")
    // OR local module:
    // implementation(project(":vela-core"))
    // (if SDK sources are in the same repo for easy iteration)
}
```

### 3.2 Exact Code Changes

#### File 1: `app/src/main/AndroidManifest.xml` — Add Permission

```xml
<manifest>
    <!-- Add this line -->
    <uses-permission android:name="android.permission.RECORD_AUDIO"/>
    <!-- rest of existing manifest -->
```

#### File 2: `LatinIME.java` — Replace Voice Handler

```java
// ~line 1414 — THIS IS THE ONLY LINE TOUCHED
public void onEvent(@NonNull final Event event) {
    if (KeyCode.VOICE_INPUT == event.getKeyCode()) {
        // OLD: mRichImm.switchToShortcutIme(this);
        // NEW: Show Vela voice pane
        mKeyboardSwitcher.showVelaVoicePane(this);
    }
    // ... rest unchanged
}
```

#### File 3: `KeyboardSwitcher.java` — Add Voice Pane

```java
// Add new method to KeyboardSwitcher

import com.velavoice.sdk.VelaTranscriber;
import com.velavoice.sdk.VelaRecordingCallback;
import com.velavoice.sdk.TranscriptionResult;

private VelaTranscriber velaTranscriber;

public void showVelaVoicePane(LatinIME latinIME) {
    if (velaTranscriber == null) {
        // In production: load path from settings/preferences
        String modelPath = loadWhisperModelPath();
        velaTranscriber = new VelaTranscriber.Builder(mDisplayContext)
            .whisperModel(modelPath)
            .language("en")
            .threads(4)
            .build();
    }

    VoiceRecordingPane voicePane = new VoiceRecordingPane(mDisplayContext);
    voicePane.setCallback(new VelaRecordingCallback() {
        @Override public void onAmplitude(float normalized) { /* pass to wave */ }
        @Override public void onResult(TranscriptionResult result) {
            latinIME.onTextInput(result.getCleanedTranscript());
            showMainKeyboard();  // swap back
        }
        @Override public void onError(VelaException e) { /* show toast */ }
    });

    // Swap input view — LatinIME's setInputView() handles this
    latinIME.setInputView(voicePane);
}

public void showMainKeyboard() {
    View mainKeyboard = onCreateInputView(mDisplayContext, /* ... */);
    latinIME.setInputView(mainKeyboard);
}
```

#### File 4: `ToolbarUtils.kt` — Verify VOICE Key

Already handled. `ToolbarKey.VOICE` maps to `KeyCode.VOICE_INPUT`, which LatinIME's `onEvent()` catches. No toolbar change needed—users just tap the existing 🎤 icon.

#### File 5: New Settings — Model Path Configuration

```kotlin
// app/src/main/java/helium314/keyboard/settings/screens/VoiceSettingsScreen.kt
class VoiceSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.voice_settings)
        findPreference<EditTextPreference>("whisper_model_path")?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                // Store path in SharedPreferences
                // KeyboardSwitcher reads this when building VelaTranscriber
                true
            }
    }
}
```

```xml
<!-- app/src/main/res/xml/voice_settings.xml -->
<PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android">
    <EditTextPreference
        android:key="whisper_model_path"
        android:title="Whisper Model File"
        android:summary="Full path to ggml .bin model file"
        android:defaultValue="/sdcard/Models/ggml-tiny.en.bin" />
    <SwitchPreference
        android:key="use_llm_cleaner"
        android:title="Use LLM Cleaner"
        android:summary="Enable grammatical post-processing" />
    <EditTextPreference
        android:key="llm_model_path"
        android:title="LLM Model File"
        android:dependency="use_llm_cleaner" />
</PreferenceScreen>
```

### 3.3 Model Management for HeliBoard Users

Three tiers:

| Tier | Approach | Complexity | User Experience |
|---|---|---|---|
| **Basic** | User downloads `.bin` file from HuggingFace manually, sets path in settings | Low | Works, requires manual step |
| **Good** | Bundled tiny micro-model in assets (if <5MB) | Medium | Voice works on first launch |
| **Best** | In-app download screen (reuse Vela's ModelManager logic in Kotlin) | High | Full user-friendly experience |

**Recommendation:** Start with **Basic**, add **Best** later. The fork should ship with a "Download Whisper Model" button that opens HuggingFace in a browser tab, then the user sets the path.

### 3.4 Voice Pane Integration Flow

```
User taps 🎤 toolbar key in any app
    │
    ▼
onEvent(VOICE_INPUT) fires in LatinIME.java
    │
    ▼
mKeyboardSwitcher.showVelaVoicePane(this)
    │
    ▼
VoiceRecordingPane inflates (WaveformView + buttons)
    │
    ▼
setInputView(voicePane) replaces keyboard layout
    │
    ▼
User speaks, taps Stop & Clean / Stop Raw / Cancel
    │
    ▼
VelaRecordingCallback.onResult() fires
    │
    ▼
latinIME.onTextInput(cleaned transcript) → InputConnection.commitText()
    │
    ▼
showMainKeyboard() swaps back to QWERTY layout
```

---

## 4. Licensing

| Component | License | Notes |
|---|---|---|
| `vela-transcription-sdk` | **Apache 2.0** or **MIT** | Permissive, allows GPL consumers like HeliBoard to use it. Keep this separate from copy-left code. |
| HeliBoard Fork (`VelaBoard`) | **GPL-3.0** | Required by upstream HeliBoard/AOSP. The fork contains only the glue code (settings UI, 1-line LatinIME change). SDK is a library dependency, not merged source. |
| `whisper.cpp` (vendored) | **MIT** | Compatible with both Apache 2.0 and GPL-3.0 |
| `ggml` (vendored) | **MIT** | Compatible with both |

**Critical:** The SDK `.aar` must be a *separate* artifact. Do not copy SDK sources into the fork's source tree. This maintains clean licensing boundaries.

---

## 5. Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **JNI function name mismatch** after package rename | Medium | Crash at runtime | Write unit test that verifies `System.loadLibrary()` + `nativeInit()` succeeds |
| **HeliBoard upstream diverges** making fork maintenance hard | Low | Feature lag | Keep fork changes minimal (~5 files). Use git merge for upstream updates. |
| **whisper.cpp compilation fails** on some NDK versions | Medium | Build failure | Pin NDK version (`25.x`). Build with CI. Test on arm64 + x86_64 emulators. |
| **Model file size** (tiny.en = 75MB) shipping hassle | High | Poor UX | Start with manual path setting. Bundle the *much smaller* `tiny.en-q5_0` (~30MB) if we bundle anything. |
| **RECORD_AUDIO permission scares privacy users** | Medium | Negative reviews | HeliBoard already has internet-permission-free branding. Position voice as opt-in feature with clear first-run dialog. |
| **SDK introduces new ProGuard/R8 rules** | Medium | Release crashes in obfuscated builds | Add `-keep class com.velavoice.sdk.**` to HeliBoard fork's proguard-rules.pro |
| **HeliBoard uses Java, Vela SDK is Kotlin** | None | — | Kotlin compiles to JVM bytecode. Java consumers call it normally. No interop issues. |
| **Multiple ABIs explode APK size** | Medium | APK >10MB over baseline | Use Android App Bundle (Play) or abiSplit (F-Droid). whisper.cpp native libs are ~3MB/ABI. |

---

## 6. Implementation Order

### Phase 1: SDK (Estimated: 3-5 sessions)

```
Dependency graph:
vela-whisper ──→ vela-core ──→ Vela Voice app migration
vela-cleaner ──→ vela-core ──→ Vela Voice app migration
vela-voice-ui ──→ (optional for Vela Voice)
```

| Step | Task | Files | Depends On |
|---|---|---|---|
| 1.1 | Create SDK Gradle project structure (root + 4 modules) | `settings.gradle.kts`, `build.gradle.kts` | Nothing |
| 1.2 | **Migrate** `vela-whisper`: copy whisper.cpp/ggml sources, rename JNI, update CMake paths | `whisper-jni.cpp`, `CMakeLists.txt`, `WhisperEngine.kt` | 1.1 |
| 1.3 | **Build & test** vela-whisper: verify JNI loads and transcribes | `WhisperEngine.kt`, gradle sync | 1.2 |
| 1.4 | **Migrate** `vela-cleaner`: extract `PersonalDictionary` interface, remove SQLite coupling | `TextCleaner.kt`, `PersonalDictionary.kt` | 1.1 |
| 1.5 | **Migrate** `vela-voice-ui`: extract `WaveformView` + `VoiceRecordingPane` | `WaveformView.kt`, `VoiceRecordingPane.kt` | 1.1 |
| 1.6 | **Create** `vela-core` facade: `VelaTranscriber` builder, `AudioRecorder`, callback interfaces | `VelaTranscriber.kt`, `AudioRecorder.kt`, etc. | 1.3, 1.4, 1.5 |
| 1.7 | **Publish** SDK locally (or to JitPack) | `build.gradle.kts` | 1.6 |
| 1.8 | **Migrate** Vela Voice app: remove inline engine files, add SDK dependency, rewrite VoiceInputMethodService | `build.gradle`, `VoiceInputMethodService.kt` | 1.7 |

### Phase 2: HeliBoard Fork (Estimated: 2-3 sessions)

| Step | Task | Files | Depends On |
|---|---|---|---|
| 2.1 | Fork HeliBoard, set up remote, verify build | — | Nothing |
| 2.2 | Add SDK dependency + RECORD_AUDIO permission | `app/build.gradle.kts`, `AndroidManifest.xml` | 1.7 |
| 2.3 | Replace voice handler in `LatinIME.java` (1 line + import) | `LatinIME.java` | 2.2 |
| 2.4 | Add `showVelaVoicePane()` in `KeyboardSwitcher.java` with VelaTranscriber setup | `KeyboardSwitcher.java` | 2.3 |
| 2.5 | Add voice settings screen (model path, LLM toggle) | `VoiceSettingsFragment.kt`, `voice_settings.xml` | 2.4 |
| 2.6 | Add model download helper (optional; opens browser + path picker) | `ModelDownloadHelper.kt` | 2.5 |
| 2.7 | Build full APK, test on device: voice in WhatsApp, Chrome, etc. | — | 2.6 |
| 2.8 | ProGuard/R8 rules, shrink, release | `proguard-rules.pro` | 2.7 |

---

## Appendix: Current Code That STAYS in Vela Voice App

The following remain in the Expo app because they are UI/settings/RN-specific:

| File | Reason to Stay |
|---|---|
| `ModelManager.ts` | TypeScript module downloader, SQLite model tracking, Expo RN specific |
| `ModelVerifierModule.kt` | React Native bridge module (extends `ReactContextBaseJavaModule`) — RN specific |
| `VoiceAccessibilityService.kt` | Accessibility service for floating button — app-specific feature |
| `VoiceImePackage.kt` | React Native package registration |
| `App.tsx` | Expo UI — model management screen |
| `app.config.js` | Expo config |

These will be updated to consume the SDK instead of containing inline engine code.
