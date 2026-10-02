#!/usr/bin/env python3
"""
Test Root CI Pipeline Invariants:
1. Workflow file exists at .github/workflows/ci.yml
2. Nested workflows in velaboard/.github/workflows are removed (inert dead workflows deleted)
3. Top-level permissions block contains `contents: read`
4. Every `uses:` step is pinned to a full 40-character hex commit SHA with comment
5. No mutable action tags used in any workflow
6. Path filters reference paths that actually exist in the repository
7. Java setup is pinned to JDK 17 with distribution 'temurin'
8. Pinned Android NDK (27.1.12297006) and CMake (3.22.1) are installed via sdkmanager before Gradle builds
9. Repo offline verification test suite is executed early in CI
10. Bootstrap step for ONNX GenAI AAR is executed before SDK build step
11. SDK publishes to mavenLocal before velaboard build/test runs
12. Companion app Node setup, ts:check, npm test, and withVoiceIme plugin test steps are present
13. Execute permissions granted for gradlew and bootstrap scripts
"""

import os
import re
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
WORKFLOWS_DIR = os.path.join(REPO_ROOT, ".github", "workflows")
CI_WORKFLOW = os.path.join(WORKFLOWS_DIR, "ci.yml")
VELABOARD_WORKFLOWS_DIR = os.path.join(REPO_ROOT, "velaboard", ".github", "workflows")

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
    print("=== Running Root CI Pipeline Verification Tests ===")

    # 1. Workflow file existence
    check(os.path.isdir(WORKFLOWS_DIR), ".github/workflows directory exists at repo root")
    check(os.path.isfile(CI_WORKFLOW), ".github/workflows/ci.yml exists")

    # 2. Nested velaboard workflows removed
    check(not os.path.exists(VELABOARD_WORKFLOWS_DIR), "velaboard/.github/workflows directory removed (no inert nested workflows)")

    if not os.path.isfile(CI_WORKFLOW):
        print(f"\nTest Summary: {passed} passed, {failed} failed")
        sys.exit(1)

    with open(CI_WORKFLOW, "r", encoding="utf-8") as f:
        content = f.read()

    # 3. Top-level permissions: contents: read
    has_top_level_permissions = False
    perm_match = re.search(r'^permissions:\s*(?:\n\s+contents:\s*read|\{\s*contents:\s*read\s*\})', content, re.MULTILINE)
    if perm_match:
        has_top_level_permissions = True
    check(has_top_level_permissions, "Top-level permissions block restricts to 'contents: read'")

    # 4. Action pins: Every `uses:` is pinned to a 40-char commit SHA
    uses_lines = re.findall(r'^\s*-\s*(?:name:.*?\n\s+)?uses:\s*([^\n]+)', content, re.MULTILINE)
    check(len(uses_lines) > 0, f"Found {len(uses_lines)} action uses in workflow")

    sha_pattern = re.compile(r'^[a-zA-Z0-9_\-\./]+@([0-9a-fA-F]{40})(?:\s*#.*)?$')
    mutable_tag_pattern = re.compile(r'@[vV]?\d+(\.\d+)*(\s*#.*)?$')

    all_pinned = True
    no_mutable_tags = True
    for use in uses_lines:
        use_stripped = use.strip()
        m = sha_pattern.match(use_stripped)
        if not m:
            all_pinned = False
            print(f"  [DEBUG] Unpinned action use: {use_stripped}")
        if mutable_tag_pattern.search(use_stripped) and not m:
            no_mutable_tags = False
            print(f"  [DEBUG] Mutable tag detected: {use_stripped}")

    check(all_pinned, "All actions are pinned to a full 40-character commit SHA")
    check(no_mutable_tags, "No mutable action tags used (@v4, @v3, etc.)")

    # 5. Path filters reference real repo paths
    paths_block = re.findall(r'paths:\s*\n((?:\s+-\s+[^\n]+\n)+)', content)
    check(len(paths_block) > 0, "Workflow specifies path filters")
    for block in paths_block:
        paths = re.findall(r'-\s*[\'"]?([^\'"\n]+)[\'"]?', block)
        for p in paths:
            clean_p = re.sub(r'/\*\*?$', '', p)
            real_path = os.path.join(REPO_ROOT, clean_p)
            check(os.path.exists(real_path), f"Path filter '{p}' resolves to existing repo path: {clean_p}")

    # 6. JDK 17 with distribution temurin
    has_java_17 = re.search(r'java-version:\s*[\'"]?17[\'"]?', content) is not None
    check(has_java_17, "setup-java pins java-version: '17'")
    has_temurin = re.search(r'distribution:\s*[\'"]?temurin[\'"]?', content) is not None
    check(has_temurin, "setup-java specifies distribution: 'temurin'")

    # 7. Pinned NDK (27.1.12297006) and CMake (3.22.1) installation step
    has_ndk_install = "ndk;27.1.12297006" in content
    check(has_ndk_install, "NDK install step installs pinned ndk;27.1.12297006")
    has_cmake_install = "cmake;3.22.1" in content
    check(has_cmake_install, "NDK install step installs cmake;3.22.1")
    has_sdkmanager = "sdkmanager" in content
    check(has_sdkmanager, "NDK install step uses sdkmanager")

    ndk_pos = content.find("ndk;27.1.12297006")
    gradle_pos = content.find("./gradlew")
    if ndk_pos != -1 and gradle_pos != -1:
        check(ndk_pos < gradle_pos, "NDK install step occurs BEFORE Gradle execution")

    # 8. Offline verification scripts run early in CI
    repo_tests = [
        "scripts/test_dependency_integrity.py",
        "scripts/test_build_script_hygiene.py",
        "scripts/test_proguard_rules.py",
        "scripts/test_toolchain_hardening.py",
        "scripts/test_export_corrections.py",
        "scripts/test_root_ci.py",
    ]
    for test_script in repo_tests:
        check(test_script in content, f"Offline verification step runs {test_script}")

    test_suite_pos = content.find("scripts/test_root_ci.py")
    if test_suite_pos != -1 and gradle_pos != -1:
        check(test_suite_pos < gradle_pos, "Offline verification scripts run BEFORE Gradle execution")

    # 9. Bootstrap step exists and occurs before sdk build
    bootstrap_pos = content.find("bootstrap-onnx-aar.sh")
    check(bootstrap_pos != -1, "scripts/bootstrap-onnx-aar.sh step is present")

    sdk_test_pos = content.find("./gradlew test")
    check(sdk_test_pos != -1, "SDK test step (./gradlew test) is present")
    if bootstrap_pos != -1 and sdk_test_pos != -1:
        check(bootstrap_pos < sdk_test_pos, "bootstrap-onnx-aar.sh runs BEFORE SDK test step")

    # 10. SDK publish to mavenLocal occurs before velaboard build
    publish_pos = content.find("publishToMavenLocal")
    check(publish_pos != -1, "SDK publishToMavenLocal step is present")

    velaboard_build_pos = content.find("testRunTestsUnitTest")
    if velaboard_build_pos == -1:
        velaboard_build_pos = content.find("assembleDebug")
    check(velaboard_build_pos != -1, "Velaboard build/test step is present")

    if publish_pos != -1 and velaboard_build_pos != -1:
        check(publish_pos < velaboard_build_pos, "SDK publishToMavenLocal runs BEFORE Velaboard build/test step")

    # 11. Companion app steps: Node setup, ts:check, test, and withVoiceIme plugin test
    has_setup_node = "actions/setup-node" in content
    check(has_setup_node, "actions/setup-node is used for companion app")

    has_ts_check = "ts:check" in content
    check(has_ts_check, "Companion app ts:check step is present")

    has_expo_test = "npm test" in content or "npm run test" in content
    check(has_expo_test, "Companion app npm test step is present")

    has_plugin_test = "withVoiceIme.gradle.test.js" in content
    check(has_plugin_test, "Companion app runs withVoiceIme.gradle.test.js explicitly")

    # 12. Executable permissions granted
    has_chmod = "chmod +x" in content and "gradlew" in content
    check(has_chmod, "Workflow grants execute permissions to gradlew and bootstrap scripts")

    # 13. Velaboard Gradle heap is large enough to package the APK.
    # A clean CI runner OOMs in :app:packageDebug with -Xmx1024m
    # (java.lang.OutOfMemoryError: Java heap space).
    gradle_props_path = os.path.join(REPO_ROOT, "velaboard", "gradle.properties")
    has_gradle_props = os.path.isfile(gradle_props_path)
    check(has_gradle_props, "velaboard/gradle.properties exists on disk")
    if has_gradle_props:
        gradle_props = open(gradle_props_path).read()
        heap_match = re.search(
            r"org\.gradle\.jvmargs\s*=\s*.*-Xmx(\d+)([mMgG])", gradle_props
        )
        check(heap_match is not None, "velaboard sets org.gradle.jvmargs with -Xmx")
        if heap_match:
            heap_value = int(heap_match.group(1))
            if heap_match.group(2).lower() == "g":
                heap_value *= 1024
            check(
                heap_value >= 4096,
                f"Velaboard Gradle heap -Xmx{heap_match.group(1)}"
                f"{heap_match.group(2)} is at least 4096m (packageDebug needs it)",
            )

    print(f"\nTest Summary: {passed} passed, {failed} failed")
    if failed > 0:
        sys.exit(1)
    print("SUCCESS: All root CI pipeline assertions passed!")

if __name__ == "__main__":
    run_tests()
