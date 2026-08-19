---
phase: vela-voice-sdk-review
reviewed: 2026-07-27T12:00:00Z
depth: standard
files_reviewed: 4
files_reviewed_list:
  - app/src/main/java/helium314/keyboard/latin/permissions/PermissionsActivity.java
  - app/src/main/java/helium314/keyboard/latin/LatinIME.java
  - app/src/main/java/helium314/keyboard/latin/RichInputMethodManager.kt
  - app/src/main/AndroidManifest.xml
findings:
  critical: 3
  warning: 5
  info: 4
  total: 12
status: issues_found
---

# Phase Vela Voice SDK Integration: Code Review Report

**Reviewed:** 2026-07-27T12:00:00Z
**Depth:** standard
**Files Reviewed:** 4
**Status:** issues_found

## Summary

This report reviews the changes and additions introduced for the Vela Voice SDK integration within HeliBoard. While the core functionality has been integrated, several critical issues must be resolved before this code is ready to ship. 

Key concerns identified include:
1. **Critical Privacy & Security Vulnerability:** The microphone is not stopped or cleaned up when the IME is hidden or destroyed, causing the app to continue recording audio in the background.
2. **Critical Memory Leak:** A static context leak path exists from the `KeyboardSwitcher` singleton to `LatinIME` via the `VelaTranscriber` instance.
3. **Severe UI Blocking (ANR):** Loading a 75MB+ machine learning model (Whisper) is done synchronously on the UI/main thread.
4. **UX / State issues:** Rotation during permission requests causes redundant dialog prompts, and granting permission does not automatically start the recording pane, requiring a double-tap by the user.

---

## Critical Issues

### CR-01: Privacy Vulnerability & Battery Drain: Microphone Left Recording in Background

**File:** `app/src/main/java/helium314/keyboard/latin/LatinIME.java:1015`
**Issue:** The voice transcription recording is started when voice input is triggered. However, the `LatinIME` service does not stop the transcription or release the `VelaTranscriber` when the keyboard window is hidden (`onWindowHidden()`), when input finishes (`onFinishInputView()` / `onFinishInput()`), or when the IME service is destroyed (`onDestroy()`). This leads to a critical privacy violation where the microphone remains active in the background, violating app store policies and severely draining the device's battery and CPU resources.
**Fix:** Stop recording and release transcription resources in the IME's lifecycle methods.

In `LatinIME.java`:
```java
@Override
public void onWindowHidden() {
    super.onWindowHidden();
    Log.i(TAG, "onWindowHidden");
    mKeyboardSwitcher.stopVelaRecording(); // Stop recording when hidden
    final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
    if (mainKeyboardView != null) {
        mainKeyboardView.closing();
    }
    clearNavigationBarColor();
}

@Override
public void onDestroy() {
    mKeyboardSwitcher.releaseVelaTranscriber(); // Release resources on destroy
    mClipboardHistoryManager.onDestroy();
    mSettings.onDestroy();
    mStatsUtilsManager.onDestroy(/* context */);
    super.onDestroy();
}
```

In `KeyboardSwitcher.java`:
```java
public void stopVelaRecording() {
    if (velaTranscriber != null) {
        velaTranscriber.stopRecording(false);
    }
}

public void releaseVelaTranscriber() {
    if (velaTranscriber != null) {
        velaTranscriber.release();
        velaTranscriber = null;
    }
    mCachedVelaModelPath = null;
    mCachedVelaLlmToggle = null;
}
```

---

### CR-02: Severe Memory Leak: Static Context Leak of LatinIME

**File:** `app/src/main/java/helium314/keyboard/keyboard/KeyboardSwitcher.java:75`
**Issue:** `KeyboardSwitcher` is a static singleton (`sInstance`) that persists throughout the application's process lifecycle. It holds a strong reference to `velaTranscriber` in an instance field. The `velaTranscriber` is constructed via `new VelaTranscriber.Builder(latinIME)` which holds a strong reference to the `LatinIME` service context. This creates a chain from the static singleton `KeyboardSwitcher` to `LatinIME`. When `LatinIME` is destroyed and recreated (such as during settings updates or language switching), the old `LatinIME` instance is leaked indefinitely.
**Fix:** Explicitly release and nullify `velaTranscriber` when the IME is destroyed (see fix in CR-01).

---

### CR-03: Main Thread Block / UI Freeze (ANR Risk) during Whisper Model Load

**File:** `app/src/main/java/helium314/keyboard/keyboard/KeyboardSwitcher.java:218`
**Issue:** Building the `VelaTranscriber` via the builder (`new VelaTranscriber.Builder(latinIME).whisperModel(modelPath)...build()`) is performed on the UI/main thread. Loading and parsing a Whisper model (even a tiny model like `ggml-tiny.en.bin` which is ~75MB) is an expensive I/O and CPU operation that blocks the main thread. This causes the keyboard UI to freeze and lag for several seconds, risking an Application Not Responding (ANR) crash.
**Fix:** Initialize and load the `VelaTranscriber` model asynchronously on a background thread. While the model is loading, display a loading spinner or buffering state in the `VoiceRecordingPane` and disable recording controls until the model is ready.

---

## Warnings

### WR-01: Redundant Permission Requests on Device Rotation

**File:** `app/src/main/java/helium314/keyboard/latin/permissions/PermissionsActivity.java:24`
**Issue:** In `onCreate`, `ActivityCompat.requestPermissions` is called directly without checking if the activity is being recreated. If the device is rotated while the permission dialog is displayed, `onCreate` runs again, initiating a duplicate request.
**Fix:** Only invoke the permission request if `savedInstanceState` is null.
```java
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
        finish();
    } else if (savedInstanceState == null) {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
    }
}
```

---

### WR-02: Disjointed UX: Permission Grant Does Not Auto-Start Voice Input

**File:** `app/src/main/java/helium314/keyboard/latin/permissions/PermissionsActivity.java:35`
**Issue:** When the user grants the microphone permission, `PermissionsActivity` finishes but does not notify the `LatinIME` service or trigger the voice pane automatically. The user is returned to the keyboard in its regular state and must tap the microphone key a second time to start recording.
**Fix:** Trigger a callback or broadcast when the permission is successfully granted to automatically launch the recording pane.
```java
if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
    // Notify LatinIME/KeyboardSwitcher to immediately show the voice pane
    // e.g. via local broadcast, static callback, or starting a relaunch action
}
```

---

### WR-03: Flashing Screen Loop for Permanently Denied Permission

**File:** `app/src/main/java/helium314/keyboard/latin/permissions/PermissionsActivity.java:35`
**Issue:** If a user permanently denies the `RECORD_AUDIO` permission (checks "Don't ask again"), starting `PermissionsActivity` instantly triggers `onRequestPermissionsResult` with a denial and calls `finish()`. This causes the transparent activity to flash on and off the screen, presenting a jarring visual glitch when the user repeatedly clicks the microphone key.
**Fix:** Check `ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)`. If it returns `false` and the permission is denied, show a dialog explaining that the microphone permission is required and provide a button redirecting the user to system settings to enable it.

---

### WR-04: Task Stack Bloat / Multiple Activity Instances

**File:** `app/src/main/AndroidManifest.xml:91`
**Issue:** `PermissionsActivity` is declared without any launch mode constraints. Because it is started from a Service using `FLAG_ACTIVITY_NEW_TASK`, rapid double-tapping on the microphone button will launch multiple instances of the activity and stack them in the task.
**Fix:** Set the activity's launch mode to `singleInstance` or `singleTop` in the manifest.
```xml
<activity android:name="helium314.keyboard.latin.permissions.PermissionsActivity"
    android:theme="@android:style/Theme.Translucent.NoTitleBar"
    android:exported="false"
    android:excludeFromRecents="true"
    android:noHistory="true"
    android:launchMode="singleInstance" />
```

---

### WR-05: Forced Shortcut Key & Broken System Fallback

**File:** `app/src/main/java/helium314/keyboard/latin/RichInputMethodManager.kt:57`
**Issue:** The property `isShortcutImeReady` has been hardcoded to return `true`. This causes the keyboard's microphone button to always be visible even if Voice Settings are disabled, the model path is invalid, or the SDK is not supported. It also breaks the fallback mechanism that switched to external system voice input methods (such as Google Voice Typing) when Vela Voice was unavailable.
**Fix:** Check configuration status and model file existence before returning `true`, and support falling back to system shortcut IMEs when Vela Voice is unavailable.

---

## Info

### IN-01: Inconsistent Prefs Default Values

**File:** `app/src/main/java/helium314/keyboard/latin/settings/Defaults.kt:196`
**Issue:** `Defaults.kt` defines `PREF_VELA_LLM_TOGGLE = true`, but `KeyboardSwitcher.java` uses `false` as the default fallback value in `prefs.getBoolean(Settings.PREF_VELA_LLM_TOGGLE, false);`.
**Fix:** Use `Defaults.PREF_VELA_LLM_TOGGLE` as the fallback argument inside `KeyboardSwitcher`.

---

### IN-02: Hardcoded Whisper Settings

**File:** `app/src/main/java/helium314/keyboard/keyboard/KeyboardSwitcher.java:221`
**Issue:** Whisper transcription language is hardcoded to `"en"` and thread utilization is hardcoded to `4`. This ignores the active keyboard layout/subtype locale and can cause thread starvation and freezes on low-end devices.
**Fix:** Retrieve the active locale from the current keyboard subtype and dynamically allocate threads using the device's CPU capabilities (e.g. `Runtime.getRuntime().availableProcessors() / 2`).

---

### IN-03: Fully-Qualified Inline References in LatinIME

**File:** `app/src/main/java/helium314/keyboard/latin/LatinIME.java:1415`
**Issue:** Classes under the `helium314.keyboard.latin.permissions` package are referenced inline with their fully-qualified names.
**Fix:** Clean up code by importing `PermissionsUtil` and `PermissionsActivity` at the top of the file.

---

### IN-04: Lack of Layout Content View in PermissionsActivity

**File:** `app/src/main/java/helium314/keyboard/latin/permissions/PermissionsActivity.java:24`
**Issue:** `PermissionsActivity` does not call `setContentView()` and relies entirely on a translucent window style. While functional, this can cause visual anomalies or transition artifacts on some customized Android OS variants.
**Fix:** Add a simple transparent layout content view or display a dialog-themed interface.

---

_Reviewed: 2026-07-27T12:00:00Z_
_Reviewer: OpenCode (gsd-code-reviewer)_
_Depth: standard_
