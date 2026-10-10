#!/usr/bin/env python3
"""
Test ProGuard and consumer keep rules for VelaVoice SDK modules:
1. Verify all four SDK modules exist: vela-core, vela-whisper, vela-cleaner, vela-voice-ui.
2. Verify no dangling `proguardFiles` references exist in any SDK module.
3. Verify `consumerProguardFiles("consumer-rules.pro")` is declared in `defaultConfig` for all four SDK modules.
4. Verify `consumer-rules.pro` exists on disk for all four SDK modules.
5. Verify `proguard-rules.pro` exists on disk for all four SDK modules (preventing dangling references).
6. Verify keep rules cover all enumerated native methods and JNI surface:
   - Native methods in WhisperEngine: nativeInit, nativeTranscribe, nativeCancel, nativeFree
   - C++ JNI symbols in whisper-jni.cpp: Java_com_velavoice_sdk_whisper_WhisperEngine_*
   - Class keep rule preserving WhisperEngine from renaming
   - General native methods keep rule (-keepclasseswithmembernames)
7. Verify keep rules cover the public SDK API for each module:
   - vela-core: com.velavoice.sdk.**
   - vela-whisper: com.velavoice.sdk.whisper.**
   - vela-cleaner: com.velavoice.sdk.cleaner.**
   - vela-voice-ui: com.velavoice.sdk.ui.** and View subclasses
8. Verify consumer application (velaboard/app) minification configuration compatibility.
"""

import os
import re
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SDK_DIR = os.path.join(REPO_ROOT, "sdk")
MODULES = ["vela-core", "vela-whisper", "vela-cleaner", "vela-voice-ui"]

passed = 0
failed = 0

def check(condition, message):
    global passed, failed
    if condition:
        print(f"[PASS] {message}")
        passed += 1
    else:
        print(f"[FAIL] {message}")
        failed += 1

def run_tests():
    global passed, failed
    print("=== Running ProGuard & Consumer Keep Rules Verification Tests ===")

    # 1. Verify module directories exist
    for mod in MODULES:
        mod_dir = os.path.join(SDK_DIR, mod)
        check(os.path.isdir(mod_dir), f"SDK module directory exists: sdk/{mod}")

    # 2. Check build.gradle.kts in each module
    for mod in MODULES:
        build_file = os.path.join(SDK_DIR, mod, "build.gradle.kts")
        check(os.path.isfile(build_file), f"sdk/{mod}/build.gradle.kts exists")
        if not os.path.isfile(build_file):
            continue

        with open(build_file, "r", encoding="utf-8") as f:
            content = f.read()

        # Check for consumerProguardFiles declaration in defaultConfig
        has_consumer_proguard = re.search(
            r'consumerProguardFiles\s*\(\s*["\']consumer-rules\.pro["\']\s*\)',
            content
        ) is not None
        check(has_consumer_proguard, f"sdk/{mod}/build.gradle.kts declares consumerProguardFiles(\"consumer-rules.pro\")")

        # Check for dangling proguardFiles references
        # Find all files referenced in proguardFiles(...)
        proguard_matches = re.findall(r'proguardFiles\s*\((.*?)\)', content, re.DOTALL)
        for match in proguard_matches:
            # Extract quoted file names that are not getDefaultProguardFile(...)
            raw_files = re.findall(r'["\']([^"\']+\.pro)["\']', match)
            for pf in raw_files:
                target_path = os.path.join(SDK_DIR, mod, pf)
                check(os.path.isfile(target_path), f"sdk/{mod}: referenced proguard file '{pf}' exists on disk (no dangling reference)")

        # Verify consumer-rules.pro file exists
        consumer_rules_file = os.path.join(SDK_DIR, mod, "consumer-rules.pro")
        check(os.path.isfile(consumer_rules_file), f"sdk/{mod}/consumer-rules.pro exists on disk")

        # Verify proguard-rules.pro file exists
        proguard_rules_file = os.path.join(SDK_DIR, mod, "proguard-rules.pro")
        check(os.path.isfile(proguard_rules_file), f"sdk/{mod}/proguard-rules.pro exists on disk")

    # 3. Verify JNI keep rules in sdk/vela-whisper/consumer-rules.pro
    whisper_consumer_rules = os.path.join(SDK_DIR, "vela-whisper", "consumer-rules.pro")
    if os.path.isfile(whisper_consumer_rules):
        with open(whisper_consumer_rules, "r", encoding="utf-8") as f:
            whisper_rules = f.read()

        # Check general native methods rule
        has_native_rule = re.search(r'-keepclasseswithmembernames[^\n]*class\s+\*[^\n]*\{\s*\n?\s*native\s+<methods>;', whisper_rules) is not None
        check(has_native_rule, "sdk/vela-whisper/consumer-rules.pro keeps native <methods> with class names")

        # Check specific WhisperEngine class keep rule to prevent class renaming
        has_whisper_engine_rule = re.search(
            r'-keep\s+class\s+com\.velavoice\.sdk\.whisper\.WhisperEngine\s*\{[^}]*native\s+<methods>;',
            whisper_rules
        ) is not None
        check(has_whisper_engine_rule, "sdk/vela-whisper/consumer-rules.pro explicitly preserves com.velavoice.sdk.whisper.WhisperEngine with native <methods>")

        # Check public API keep rule for vela-whisper
        has_public_whisper_api = re.search(r'-keep\s+public\s+class\s+com\.velavoice\.sdk\.whisper\.', whisper_rules) is not None
        check(has_public_whisper_api, "sdk/vela-whisper/consumer-rules.pro keeps public class com.velavoice.sdk.whisper.*")
    else:
        check(False, "sdk/vela-whisper/consumer-rules.pro exists for rule inspection")

    # 4. Verify JNI and public API keep rules in sdk/vela-cleaner/consumer-rules.pro
    cleaner_consumer_rules = os.path.join(SDK_DIR, "vela-cleaner", "consumer-rules.pro")
    if os.path.isfile(cleaner_consumer_rules):
        with open(cleaner_consumer_rules, "r", encoding="utf-8") as f:
            cleaner_rules = f.read()

        # Check native methods rule for ONNX GenAI JNI
        has_cleaner_native = re.search(r'-keepclasseswithmembernames[^\n]*class\s+\*[^\n]*\{\s*\n?\s*native\s+<methods>;', cleaner_rules) is not None
        check(has_cleaner_native, "sdk/vela-cleaner/consumer-rules.pro preserves native <methods> (ONNX GenAI JNI)")

        # Check public API keep rule for vela-cleaner
        has_public_cleaner_api = re.search(r'-keep\s+public\s+class\s+com\.velavoice\.sdk\.cleaner\.', cleaner_rules) is not None
        check(has_public_cleaner_api, "sdk/vela-cleaner/consumer-rules.pro keeps public class com.velavoice.sdk.cleaner.*")
    else:
        check(False, "sdk/vela-cleaner/consumer-rules.pro exists for rule inspection")

    # 5. Verify public API keep rules in sdk/vela-core/consumer-rules.pro
    core_consumer_rules = os.path.join(SDK_DIR, "vela-core", "consumer-rules.pro")
    if os.path.isfile(core_consumer_rules):
        with open(core_consumer_rules, "r", encoding="utf-8") as f:
            core_rules = f.read()

        # Should-fix 2: Disallow recursive com.velavoice.sdk.** catch-all
        has_recursive_catchall = "com.velavoice.sdk.**" in core_rules
        check(not has_recursive_catchall, "sdk/vela-core/consumer-rules.pro has no recursive com.velavoice.sdk.** catch-all")

        # Must scope to core's own packages: com.velavoice.sdk.* and com.velavoice.sdk.audio.*
        has_scoped_core = re.search(r'-keep\s+public\s+class\s+com\.velavoice\.sdk\.\*\s*\{', core_rules) is not None
        has_scoped_audio = re.search(r'-keep\s+public\s+class\s+com\.velavoice\.sdk\.audio\.\*\s*\{', core_rules) is not None
        check(has_scoped_core, "sdk/vela-core/consumer-rules.pro keeps public class com.velavoice.sdk.* (scoped to core)")
        check(has_scoped_audio, "sdk/vela-core/consumer-rules.pro keeps public class com.velavoice.sdk.audio.* (scoped to core audio)")
    else:
        check(False, "sdk/vela-core/consumer-rules.pro exists for rule inspection")

    # 6. Verify public API and View keep rules in sdk/vela-voice-ui/consumer-rules.pro
    ui_consumer_rules = os.path.join(SDK_DIR, "vela-voice-ui", "consumer-rules.pro")
    if os.path.isfile(ui_consumer_rules):
        with open(ui_consumer_rules, "r", encoding="utf-8") as f:
            ui_rules = f.read()

        # Should-fix 1: Disallow unscoped -keep public class * extends android.view.View
        has_unscoped_view = re.search(r'-keep[^\n]*class\s+\*\s+extends\s+android\.view\.View', ui_rules) is not None
        check(not has_unscoped_view, "sdk/vela-voice-ui/consumer-rules.pro has no unscoped `-keep ... class * extends android.view.View`")

        # Must scope View keep rule to com.velavoice.sdk.ui.*
        has_scoped_view = re.search(r'-keep\s+public\s+class\s+com\.velavoice\.sdk\.ui\.\*?\s+extends\s+android\.view\.View', ui_rules) is not None
        check(has_scoped_view, "sdk/vela-voice-ui/consumer-rules.pro scopes View keep rule to com.velavoice.sdk.ui.*")

        has_public_ui_api = re.search(r'-keep\s+public\s+class\s+com\.velavoice\.sdk\.ui\.', ui_rules) is not None
        check(has_public_ui_api, "sdk/vela-voice-ui/consumer-rules.pro keeps public class com.velavoice.sdk.ui.*")
    else:
        check(False, "sdk/vela-voice-ui/consumer-rules.pro exists for rule inspection")

    # 7. Global scoping verification across all consumer-rules.pro files
    for mod in MODULES:
        rule_path = os.path.join(SDK_DIR, mod, "consumer-rules.pro")
        if os.path.isfile(rule_path):
            with open(rule_path, "r", encoding="utf-8") as f:
                r_content = f.read()
            check("com.velavoice.sdk.**" not in r_content,
                  f"sdk/{mod}/consumer-rules.pro does not contain recursive com.velavoice.sdk.**")
            check(not re.search(r'-keep[^\n]*class\s+\*\s+extends\s+android\.view\.View', r_content),
                  f"sdk/{mod}/consumer-rules.pro does not contain unscoped View rule")

    # 7. Verify JNI source matches keep assumptions
    whisper_engine_kt = os.path.join(SDK_DIR, "vela-whisper", "src", "main", "kotlin", "com", "velavoice", "sdk", "whisper", "WhisperEngine.kt")
    whisper_jni_cpp = os.path.join(SDK_DIR, "vela-whisper", "src", "main", "cpp", "whisper-jni.cpp")
    check(os.path.isfile(whisper_engine_kt), "WhisperEngine.kt exists")
    check(os.path.isfile(whisper_jni_cpp), "whisper-jni.cpp exists")

    if os.path.isfile(whisper_engine_kt) and os.path.isfile(whisper_jni_cpp):
        with open(whisper_engine_kt, "r", encoding="utf-8") as f:
            kt_src = f.read()
        with open(whisper_jni_cpp, "r", encoding="utf-8") as f:
            cpp_src = f.read()

        # Enumerate native methods from Kotlin source
        kt_native_methods = re.findall(r'external\s+fun\s+(\w+)', kt_src)
        check(len(kt_native_methods) == 4, f"Enumerated exactly 4 native methods in WhisperEngine: {kt_native_methods}")
        check("nativeInit" in kt_native_methods, "WhisperEngine declares nativeInit")
        check("nativeTranscribe" in kt_native_methods, "WhisperEngine declares nativeTranscribe")
        check("nativeCancel" in kt_native_methods, "WhisperEngine declares nativeCancel")
        check("nativeFree" in kt_native_methods, "WhisperEngine declares nativeFree")

        # Verify JNI C++ symbols correspond exactly to the package and methods
        for method in kt_native_methods:
            expected_jni_sym = f"Java_com_velavoice_sdk_whisper_WhisperEngine_{method}"
            check(expected_jni_sym in cpp_src, f"whisper-jni.cpp defines JNI export symbol {expected_jni_sym}")

    # 8. Consumer application (velaboard/app) sanity check
    app_build_gradle = os.path.join(REPO_ROOT, "velaboard", "app", "build.gradle.kts")
    check(os.path.isfile(app_build_gradle), "velaboard/app/build.gradle.kts exists")
    if os.path.isfile(app_build_gradle):
        with open(app_build_gradle, "r", encoding="utf-8") as f:
            app_gradle = f.read()
        # Verify minification is enabled in release build type
        has_release_minify = re.search(r'release\s*\{[^}]*isMinifyEnabled\s*=\s*true', app_gradle) is not None
        check(has_release_minify, "velaboard/app release buildType enables minification (isMinifyEnabled = true)")

    print(f"\nTest Summary: {passed} passed, {failed} failed")
    if failed > 0:
        sys.exit(1)
    print("SUCCESS: All ProGuard and consumer keep rules assertions passed!")

if __name__ == "__main__":
    run_tests()
