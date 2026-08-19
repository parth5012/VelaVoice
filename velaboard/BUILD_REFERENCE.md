# Build Reference — HeliBoard + Vela Voice

## Prerequisites

| Requirement | Notes |
|-------------|-------|
| Android SDK 36 | `compileSdk` / `targetSdk` |
| NDK 27.1.12297006 | Needed for native JNI libraries |
| JDK 17+ | Gradle JVM target |
| Gradle 8.x | Bundled wrapper (`gradlew.bat`) |

---

## Build Commands

### Debug (for testing / USB deployment)

```powershell
# Install directly to a connected device
.\gradlew.bat installDebug

# Build APK only (no install)
.\gradlew.bat assembleDebug

# Locations of built files
# APK → app/build/outputs/apk/debug/HeliBoard_4.0-dev1-debug.apk
```

### Release (for production / Play Store)

```powershell
# APK (direct distribution)
.\gradlew.bat assembleRelease

# Android App Bundle (Google Play)
.\gradlew.bat bundleRelease

# Locations of built files
# APK  → app/build/outputs/apk/release/
# AAB  → app/build/outputs/bundle/release/
```

---

## Signing

Release builds need to be signed before they can be installed or uploaded.

### 1. Configure a keystore (one-time)

Add this inside the `android { }` block in **`app/build.gradle.kts`**:

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("path/to/keystore.jks")
        storePassword = System.getenv("SIGNING_STORE_PASSWORD")
        keyAlias = System.getenv("SIGNING_KEY_ALIAS")
        keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
    }
}

buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
        // ...
    }
}
```

### 2. Sign manually (without Gradle config)

```powershell
& "path\to\android-sdk\build-tools\36.0.0\apksigner.bat" sign `
    --ks my-release-key.jks `
    --ks-key-alias my-alias `
    app/build/outputs/apk/release/HeliBoard_4.0-dev1-release-unsigned.apk
```

---

## USB Deployment

```powershell
# Check connected devices
adb devices

# Build & install debug variant
.\gradlew.bat installDebug

# Launch HeliBoard settings directly
adb shell am start -n helium314.keyboard.latin/helium314.keyboard.settings.SettingsActivity

# Launch the keyboard's voice pane trigger
# (Usually done by tapping the mic key on the keyboard itself)
```

---

## Vela Voice Settings

Once installed, configure voice input inside **HeliBoard Settings → Voice Input Settings**:

| Setting | Key | Default |
|---------|-----|---------|
| Whisper Model Path | `vela_model_path` | `/sdcard/Models/ggml-tiny.en.bin` |
| Enable LLM Cleaner | `vela_llm_toggle` | `false` |

**Note:** Place the Whisper `.bin` model file (e.g. `ggml-tiny.en.bin`) on the device at the configured path, or update the path in settings.

---

## Quick Reference

| Task | Command |
|------|---------|
| Build debug APK | `.\gradlew.bat assembleDebug` |
| Build & install debug | `.\gradlew.bat installDebug` |
| Build release APK | `.\gradlew.bat assembleRelease` |
| Build release AAB | `.\gradlew.bat bundleRelease` |
| Full clean build | `.\gradlew.bat clean assembleDebug` |
