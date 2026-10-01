#!/usr/bin/env python3
"""
Test toolchain hardening and matrix consistency:
1. compileSdk consistency across SDK modules and velaboard/app (target: 35)
2. minSdk consistency across SDK modules and velaboard/app (target: 24)
3. ndkVersion consistency across sdk/vela-whisper and velaboard/app (target: 27.1.12297006)
4. Native hardening flags in sdk/vela-whisper/src/main/cpp/CMakeLists.txt:
   - Target compile options: -fstack-protector-strong, -D_FORTIFY_SOURCE=2, -fvisibility=hidden
   - Target link options: -Wl,-z,relro, -Wl,-z,now
5. Native hardening flags in velaboard/app/src/main/jni/Android.mk:
   - LOCAL_CFLAGS: -fstack-protector-strong, -D_FORTIFY_SOURCE=2, -fvisibility=hidden
   - LOCAL_LDFLAGS: -Wl,-z,relro, -Wl,-z,now
6. JDK consistency: make-emoji-keys normalized to JavaVersion.VERSION_17 matching SDK and app
"""

import os
import re
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SDK_DIR = os.path.join(REPO_ROOT, "sdk")
VELABOARD_DIR = os.path.join(REPO_ROOT, "velaboard")

SDK_MODULES = ["vela-core", "vela-whisper", "vela-cleaner", "vela-voice-ui"]
APP_BUILD_GRADLE = os.path.join(VELABOARD_DIR, "app", "build.gradle.kts")
MAKE_EMOJI_KEYS_GRADLE = os.path.join(VELABOARD_DIR, "tools", "make-emoji-keys", "build.gradle")
WHISPER_CMAKELISTS = os.path.join(SDK_DIR, "vela-whisper", "src", "main", "cpp", "CMakeLists.txt")
APP_ANDROID_MK = os.path.join(VELABOARD_DIR, "app", "src", "main", "jni", "Android.mk")

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

def extract_gradle_int(pattern, content):
    m = re.search(pattern, content)
    return int(m.group(1)) if m else None

def extract_gradle_str(pattern, content):
    m = re.search(pattern, content)
    return m.group(1) if m else None

def run_tests():
    global passed, failed
    print("=== Running Toolchain Hardening & Matrix Consistency Tests ===")

    # 1. Verify existence of build files
    for mod in SDK_MODULES:
        build_file = os.path.join(SDK_DIR, mod, "build.gradle.kts")
        check(os.path.isfile(build_file), f"sdk/{mod}/build.gradle.kts exists")

    check(os.path.isfile(APP_BUILD_GRADLE), "velaboard/app/build.gradle.kts exists")
    check(os.path.isfile(MAKE_EMOJI_KEYS_GRADLE), "velaboard/tools/make-emoji-keys/build.gradle exists")
    check(os.path.isfile(WHISPER_CMAKELISTS), "sdk/vela-whisper/src/main/cpp/CMakeLists.txt exists")
    check(os.path.isfile(APP_ANDROID_MK), "velaboard/app/src/main/jni/Android.mk exists")

    # Read all build files
    sdk_contents = {}
    for mod in SDK_MODULES:
        with open(os.path.join(SDK_DIR, mod, "build.gradle.kts"), "r", encoding="utf-8") as f:
            sdk_contents[mod] = f.read()

    with open(APP_BUILD_GRADLE, "r", encoding="utf-8") as f:
        app_content = f.read()

    with open(MAKE_EMOJI_KEYS_GRADLE, "r", encoding="utf-8") as f:
        make_emoji_content = f.read()

    with open(WHISPER_CMAKELISTS, "r", encoding="utf-8") as f:
        cmake_content = f.read()

    with open(APP_ANDROID_MK, "r", encoding="utf-8") as f:
        android_mk_content = f.read()

    # 2. compileSdk consistency (target: 35)
    app_compile_sdk = extract_gradle_int(r'compileSdk\s*=\s*(\d+)', app_content)
    check(app_compile_sdk == 35, f"velaboard/app declares compileSdk = 35 (actual: {app_compile_sdk})")

    for mod in SDK_MODULES:
        mod_compile_sdk = extract_gradle_int(r'compileSdk\s*=\s*(\d+)', sdk_contents[mod])
        check(mod_compile_sdk == 35, f"sdk/{mod} declares compileSdk = 35 (actual: {mod_compile_sdk})")

    # 3. minSdk consistency (target: 24)
    app_min_sdk = extract_gradle_int(r'minSdk\s*=\s*(\d+)', app_content)
    check(app_min_sdk == 24, f"velaboard/app declares minSdk = 24 (actual: {app_min_sdk})")

    for mod in SDK_MODULES:
        mod_min_sdk = extract_gradle_int(r'minSdk\s*=\s*(\d+)', sdk_contents[mod])
        check(mod_min_sdk == 24, f"sdk/{mod} declares minSdk = 24 (actual: {mod_min_sdk})")

    # 4. ndkVersion consistency (target: 27.1.12297006)
    app_ndk_version = extract_gradle_str(r'ndkVersion\s*=\s*["\']([^"\']+)["\']', app_content)
    check(app_ndk_version == "27.1.12297006", f"velaboard/app declares ndkVersion = '27.1.12297006' (actual: '{app_ndk_version}')")

    whisper_ndk_version = extract_gradle_str(r'ndkVersion\s*=\s*["\']([^"\']+)["\']', sdk_contents["vela-whisper"])
    check(whisper_ndk_version == "27.1.12297006", f"sdk/vela-whisper declares ndkVersion = '27.1.12297006' (actual: '{whisper_ndk_version}')")
    check(whisper_ndk_version == app_ndk_version, "ndkVersion matches between sdk/vela-whisper and velaboard/app")

    # 5. CMakeLists.txt hardening flags on target whisper
    cmake_min_match = re.search(r'cmake_minimum_required\s*\(\s*VERSION\s+([0-9.]+)\s*\)', cmake_content)
    check(cmake_min_match is not None, "CMakeLists.txt declares cmake_minimum_required")
    if cmake_min_match:
        ver_parts = [int(p) for p in cmake_min_match.group(1).split(".")]
        is_at_least_3_13 = ver_parts >= [3, 13]
        check(is_at_least_3_13, f"CMakeLists.txt cmake_minimum_required >= 3.13 for target_link_options (actual: {cmake_min_match.group(1)})")

    compile_match = re.search(r'target_compile_options\s*\(\s*whisper\b([^)]*)\)', cmake_content, re.DOTALL)
    check(compile_match is not None, "CMakeLists.txt configures target_compile_options on target whisper")
    compile_opts = compile_match.group(1) if compile_match else ""

    check("-fstack-protector-strong" in compile_opts, "target_compile_options(whisper) explicitly specifies -fstack-protector-strong")
    check("-D_FORTIFY_SOURCE=2" in compile_opts, "target_compile_options(whisper) explicitly specifies -D_FORTIFY_SOURCE=2")
    check("-fvisibility=hidden" in compile_opts, "target_compile_options(whisper) explicitly specifies -fvisibility=hidden")

    link_match = re.search(r'target_link_options\s*\(\s*whisper\b([^)]*)\)', cmake_content, re.DOTALL)
    check(link_match is not None, "CMakeLists.txt configures target_link_options on target whisper")
    link_opts = link_match.group(1) if link_match else ""

    check("-Wl,-z,relro" in link_opts, "target_link_options(whisper) explicitly specifies -Wl,-z,relro")
    check("-Wl,-z,now" in link_opts, "target_link_options(whisper) explicitly specifies -Wl,-z,now")

    # 6. Android.mk hardening flags in velaboard/app
    check("-fstack-protector-strong" in android_mk_content, "Android.mk explicitly specifies -fstack-protector-strong")
    check("-D_FORTIFY_SOURCE=2" in android_mk_content, "Android.mk explicitly specifies -D_FORTIFY_SOURCE=2")
    check("-fvisibility=hidden" in android_mk_content, "Android.mk explicitly specifies -fvisibility=hidden")
    check("-Wl,-z,relro" in android_mk_content, "Android.mk explicitly specifies -Wl,-z,relro")
    check("-Wl,-z,now" in android_mk_content, "Android.mk explicitly specifies -Wl,-z,now")

    # Verify compile flags are in LOCAL_CFLAGS and linker flags in LOCAL_LDFLAGS
    has_cflags_hardening = re.search(r'LOCAL_CFLAGS\s*\+=.*-fstack-protector-strong.*-D_FORTIFY_SOURCE=2.*-fvisibility=hidden', android_mk_content, re.DOTALL) is not None
    check(has_cflags_hardening, "Android.mk adds hardening flags to LOCAL_CFLAGS")

    has_ldflags_hardening = re.search(r'LOCAL_LDFLAGS\s*\+=.*-Wl,-z,relro.*-Wl,-z,now', android_mk_content, re.DOTALL) is not None or \
                            ("-Wl,-z,relro" in android_mk_content and "-Wl,-z,now" in android_mk_content and "LOCAL_LDFLAGS" in android_mk_content)
    check(has_ldflags_hardening, "Android.mk adds hardening flags to LOCAL_LDFLAGS")

    # 7. JDK consistency
    check("VERSION_21" not in make_emoji_content, "make-emoji-keys build.gradle no longer references VERSION_21")
    has_java_17_source = re.search(r'sourceCompatibility\s*=\s*JavaVersion\.VERSION_17', make_emoji_content) is not None
    has_java_17_target = re.search(r'targetCompatibility\s*=\s*JavaVersion\.VERSION_17', make_emoji_content) is not None
    check(has_java_17_source, "make-emoji-keys sets sourceCompatibility = JavaVersion.VERSION_17")
    check(has_java_17_target, "make-emoji-keys sets targetCompatibility = JavaVersion.VERSION_17")

    print(f"\nTest Summary: {passed} passed, {failed} failed")
    if failed > 0:
        sys.exit(1)
    print("SUCCESS: All toolchain hardening and matrix consistency assertions passed!")

if __name__ == "__main__":
    run_tests()
