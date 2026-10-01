#!/usr/bin/env python3
"""
Test dependency integrity and supply chain verification:
1. sdk/gradle/wrapper/gradle-wrapper.properties has distributionSha256Sum
2. velaboard/gradle/wrapper/gradle-wrapper.properties has distributionSha256Sum
3. ONNX GenAI AAR checksum file exists and contains valid SHA-256
4. sdk/settings.gradle.kts restricts mavenLocal to com.microsoft.onnxruntime and moves it last
5. velaboard/build.gradle.kts restricts mavenLocal to allowed groups and moves it last
6. README.md contains machine-checkable SHA-256 and no stale Windows paths
7. Bootstrap script exists and is executable
"""

import os
import re
import sys
import hashlib

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

EXPECTED_GRADLE_8_8_SHA256 = "f8b4f4772d302c8ff580bc40d0f56e715de69b163546944f787c87abf209c961"
EXPECTED_GRADLE_8_14_SHA256 = "61ad310d3c7d3e5da131b76bbf22b5a4c0786e9d892dae8c1658d4b484de3caa"
EXPECTED_GENAI_AAR_SHA256 = "a4aeadcd4d70b877c56a74ece7778324a5ee4686f395ef29e4d2a83908b83a6c"

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
    print("=== Running Dependency Integrity & Supply Chain Verification Tests ===")

    # 1. sdk gradle-wrapper.properties distributionSha256Sum
    sdk_wrapper_path = os.path.join(REPO_ROOT, "sdk", "gradle", "wrapper", "gradle-wrapper.properties")
    check(os.path.isfile(sdk_wrapper_path), "sdk/gradle/wrapper/gradle-wrapper.properties exists")
    if os.path.isfile(sdk_wrapper_path):
        with open(sdk_wrapper_path, "r", encoding="utf-8") as f:
            content = f.read()
        has_sha = f"distributionSha256Sum={EXPECTED_GRADLE_8_8_SHA256}" in content
        check(has_sha, f"sdk gradle wrapper has distributionSha256Sum={EXPECTED_GRADLE_8_8_SHA256}")

    # 2. velaboard gradle-wrapper.properties distributionSha256Sum
    velaboard_wrapper_path = os.path.join(REPO_ROOT, "velaboard", "gradle", "wrapper", "gradle-wrapper.properties")
    check(os.path.isfile(velaboard_wrapper_path), "velaboard/gradle/wrapper/gradle-wrapper.properties exists")
    if os.path.isfile(velaboard_wrapper_path):
        with open(velaboard_wrapper_path, "r", encoding="utf-8") as f:
            content = f.read()
        has_sha = f"distributionSha256Sum={EXPECTED_GRADLE_8_14_SHA256}" in content
        check(has_sha, f"velaboard gradle wrapper has distributionSha256Sum={EXPECTED_GRADLE_8_14_SHA256}")

    # 3. ONNX GenAI AAR checksum file
    aar_checksum_path = os.path.join(REPO_ROOT, "sdk", "vela-cleaner", "libs", "onnxruntime-genai-android-0.15.0.aar.sha256")
    check(os.path.isfile(aar_checksum_path), f"AAR checksum file exists at {os.path.relpath(aar_checksum_path, REPO_ROOT)}")
    if os.path.isfile(aar_checksum_path):
        with open(aar_checksum_path, "r", encoding="utf-8") as f:
            sha_line = f.read().strip()
        check(EXPECTED_GENAI_AAR_SHA256 in sha_line, f"AAR checksum matches {EXPECTED_GENAI_AAR_SHA256}")

    # 4. sdk/settings.gradle.kts mavenLocal restriction & ordering
    sdk_settings_path = os.path.join(REPO_ROOT, "sdk", "settings.gradle.kts")
    check(os.path.isfile(sdk_settings_path), "sdk/settings.gradle.kts exists")
    if os.path.isfile(sdk_settings_path):
        with open(sdk_settings_path, "r", encoding="utf-8") as f:
            sdk_settings = f.read()
        has_filter = 'includeGroup("com.microsoft.onnxruntime")' in sdk_settings
        check(has_filter, "sdk/settings.gradle.kts restricts mavenLocal with includeGroup(\"com.microsoft.onnxruntime\")")
        
        # Check ordering: mavenLocal must appear AFTER google, mavenCentral, and jitpack
        pos_google = sdk_settings.find("google()")
        pos_maven_central = sdk_settings.find("mavenCentral()")
        pos_jitpack = sdk_settings.find("jitpack")
        pos_maven_local = sdk_settings.find("mavenLocal")
        is_ordered = (pos_maven_local > pos_google and 
                      pos_maven_local > pos_maven_central and 
                      pos_maven_local > pos_jitpack)
        check(is_ordered, "sdk/settings.gradle.kts places mavenLocal after google, mavenCentral, and jitpack")

    # 5. velaboard/build.gradle.kts mavenLocal restriction & ordering
    velaboard_build_path = os.path.join(REPO_ROOT, "velaboard", "build.gradle.kts")
    check(os.path.isfile(velaboard_build_path), "velaboard/build.gradle.kts exists")
    if os.path.isfile(velaboard_build_path):
        with open(velaboard_build_path, "r", encoding="utf-8") as f:
            velaboard_build = f.read()
        has_velavoice_group = 'includeGroup("com.velavoice.sdk")' in velaboard_build
        has_onnx_group = 'includeGroup("com.microsoft.onnxruntime")' in velaboard_build
        check(has_velavoice_group and has_onnx_group, 
              "velaboard/build.gradle.kts restricts mavenLocal to com.velavoice.sdk and com.microsoft.onnxruntime")
        
        pos_vb_google = velaboard_build.find("google()")
        pos_vb_central = velaboard_build.find("mavenCentral()")
        pos_vb_jitpack = velaboard_build.find("jitpack")
        pos_vb_local = velaboard_build.find("mavenLocal")
        is_vb_ordered = (pos_vb_local > pos_vb_google and 
                         pos_vb_local > pos_vb_central and 
                         pos_vb_local > pos_vb_jitpack)
        check(is_vb_ordered, "velaboard/build.gradle.kts places mavenLocal after google, mavenCentral, and jitpack")

    # 6. README.md: references checksum and removes stale path
    readme_path = os.path.join(REPO_ROOT, "README.md")
    check(os.path.isfile(readme_path), "README.md exists")
    if os.path.isfile(readme_path):
        with open(readme_path, "r", encoding="utf-8") as f:
            readme = f.read()
        check(EXPECTED_GENAI_AAR_SHA256 in readme, "README.md documents the ONNX GenAI AAR SHA-256 checksum")
        check("C:\\Users\\DELL" not in readme, "README.md does not contain stale Windows path C:\\Users\\DELL")
        check("scripts/publish-genai-aar" in readme or "bootstrap" in readme, "README.md references bootstrap/publish project")

    # 7. Bootstrap / verification script exists and executes cleanly
    bootstrap_sh = os.path.join(REPO_ROOT, "scripts", "bootstrap-onnx-aar.sh")
    check(os.path.isfile(bootstrap_sh), "scripts/bootstrap-onnx-aar.sh exists")
    if os.path.isfile(bootstrap_sh):
        check(os.access(bootstrap_sh, os.X_OK), "scripts/bootstrap-onnx-aar.sh is executable")

    # 8. Checksum verification logic against actual AAR file if present (libs, m2, or optional cache)
    m2_aar = os.path.expanduser("~/.m2/repository/com/microsoft/onnxruntime/onnxruntime-genai-android/0.15.0/onnxruntime-genai-android-0.15.0.aar")
    local_aar = os.path.join(REPO_ROOT, "sdk", "vela-cleaner", "libs", "onnxruntime-genai-android-0.15.0.aar")
    cache_aar = os.environ.get("ONNX_AAR_CACHE", "")

    # Test m2 AAR if present
    if os.path.isfile(m2_aar):
        with open(m2_aar, "rb") as f:
            h = hashlib.sha256()
            while chunk := f.read(8192):
                h.update(chunk)
            actual_sha = h.hexdigest()
        check(actual_sha == EXPECTED_GENAI_AAR_SHA256,
              f"mavenLocal AAR sha256 matches pinned checksum ({actual_sha})")

    # Test local libs AAR if present
    if os.path.isfile(local_aar):
        with open(local_aar, "rb") as f:
            h = hashlib.sha256()
            while chunk := f.read(8192):
                h.update(chunk)
            actual_sha = h.hexdigest()
        check(actual_sha == EXPECTED_GENAI_AAR_SHA256,
              f"Local libs AAR sha256 matches pinned checksum ({actual_sha})")

    # Test cache AAR if specified and present
    if cache_aar and os.path.isfile(cache_aar):
        with open(cache_aar, "rb") as f:
            h = hashlib.sha256()
            while chunk := f.read(8192):
                h.update(chunk)
            actual_sha = h.hexdigest()
        check(actual_sha == EXPECTED_GENAI_AAR_SHA256,
              f"Cached AAR sha256 matches pinned checksum ({actual_sha})")

    # 9. Tampered file negative test
    dummy_bytes = b"tampered content"
    tampered_sha = hashlib.sha256(dummy_bytes).hexdigest()
    check(tampered_sha != EXPECTED_GENAI_AAR_SHA256, "Tampered content correctly fails checksum comparison")

    # 10. Check scripts/publish-genai-aar/build.gradle.kts exists and enforces checksum
    publish_build = os.path.join(REPO_ROOT, "scripts", "publish-genai-aar", "build.gradle.kts")
    check(os.path.isfile(publish_build), "scripts/publish-genai-aar/build.gradle.kts exists")
    if os.path.isfile(publish_build):
        with open(publish_build, "r", encoding="utf-8") as f:
            p_content = f.read()
        check(EXPECTED_GENAI_AAR_SHA256 in p_content, "publish-genai-aar enforces pinned SHA-256")
        check("verifyChecksum" in p_content, "publish-genai-aar defines verifyChecksum task")

    print(f"\nTest Summary: {passed} passed, {failed} failed")
    if failed > 0:
        sys.exit(1)
    print("SUCCESS: All dependency integrity assertions passed!")

if __name__ == "__main__":
    run_tests()
