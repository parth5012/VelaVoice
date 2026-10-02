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

def wrapper_active_sha(path):
    """Active (uncommented) distributionSha256Sum values in a properties file."""
    values = []
    try:
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                stripped = line.strip()
                if not stripped or stripped.startswith("#") or stripped.startswith("!"):
                    continue
                if stripped.startswith("distributionSha256Sum="):
                    values.append(stripped.split("=", 1)[1].strip())
    except OSError:
        return []
    return values

def active_block(text, header_pattern):
    """Body of the first `{ ... }` block opened by header_pattern, or None."""
    match = re.search(header_pattern, text)
    if not match:
        return None
    start = match.end() - 1  # the opening brace of the header
    if start < 0 or text[start] != "{":
        return None
    depth = 0
    for index in range(start, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1:index]
    return None

def maven_local_content_blocks(repos_text):
    """Content-filter body of every active mavenLocal block; None when it has no content{}."""
    blocks = []
    for match in re.finditer(r"mavenLocal\b", strip_comments(repos_text)):
        body = active_block(strip_comments(repos_text)[match.start():], r"mavenLocal\s*\{")
        blocks.append(None if body is None else active_block(body, r"content\s*\{"))
    return blocks

def strip_comments(text):
    """Remove Kotlin/Java comments so commented-out calls are not counted."""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)

def run_tests():
    global passed, failed
    print("=== Running Dependency Integrity & Supply Chain Verification Tests ===")

    # 1. sdk gradle-wrapper.properties distributionSha256Sum
    sdk_wrapper_path = os.path.join(REPO_ROOT, "sdk", "gradle", "wrapper", "gradle-wrapper.properties")
    check(os.path.isfile(sdk_wrapper_path), "sdk/gradle/wrapper/gradle-wrapper.properties exists")
    if os.path.isfile(sdk_wrapper_path):
        active_shas = wrapper_active_sha(sdk_wrapper_path)
        check(
            active_shas == [EXPECTED_GRADLE_8_8_SHA256],
            f"sdk gradle wrapper declares exactly one active distributionSha256Sum={EXPECTED_GRADLE_8_8_SHA256}",
        )

    # 2. velaboard gradle-wrapper.properties distributionSha256Sum
    velaboard_wrapper_path = os.path.join(REPO_ROOT, "velaboard", "gradle", "wrapper", "gradle-wrapper.properties")
    check(os.path.isfile(velaboard_wrapper_path), "velaboard/gradle/wrapper/gradle-wrapper.properties exists")
    if os.path.isfile(velaboard_wrapper_path):
        active_shas = wrapper_active_sha(velaboard_wrapper_path)
        check(
            active_shas == [EXPECTED_GRADLE_8_14_SHA256],
            f"velaboard gradle wrapper declares exactly one active distributionSha256Sum={EXPECTED_GRADLE_8_14_SHA256}",
        )

    # 3. ONNX GenAI AAR checksum file
    aar_checksum_path = os.path.join(REPO_ROOT, "sdk", "vela-cleaner", "libs", "onnxruntime-genai-android-0.15.0.aar.sha256")
    check(os.path.isfile(aar_checksum_path), f"AAR checksum file exists at {os.path.relpath(aar_checksum_path, REPO_ROOT)}")
    if os.path.isfile(aar_checksum_path):
        with open(aar_checksum_path, "r", encoding="utf-8") as f:
            sha_line = f.read().strip()
        checksum_fields = sha_line.split()
        check(
            bool(checksum_fields) and checksum_fields[0] == EXPECTED_GENAI_AAR_SHA256,
            f"AAR checksum matches {EXPECTED_GENAI_AAR_SHA256}",
        )

    # 4. sdk/settings.gradle.kts mavenLocal restriction & ordering.
    # Only the active dependencyResolutionManagement repositories block counts:
    # a declaration in pluginManagement (or a comment) must not satisfy the check.
    sdk_settings_path = os.path.join(REPO_ROOT, "sdk", "settings.gradle.kts")
    check(os.path.isfile(sdk_settings_path), "sdk/settings.gradle.kts exists")
    if os.path.isfile(sdk_settings_path):
        with open(sdk_settings_path, "r", encoding="utf-8") as f:
            sdk_settings = f.read()
        sdk_repos = active_block(
            active_block(sdk_settings, r"dependencyResolutionManagement\s*\{") or "",
            r"repositories\s*\{",
        )
        check(
            sdk_repos is not None,
            "sdk/settings.gradle.kts declares dependencyResolutionManagement { repositories { ... } }",
        )
        if sdk_repos is not None:
            sdk_filters = maven_local_content_blocks(sdk_repos)
            check(bool(sdk_filters), "sdk repositories block declares at least one mavenLocal")
            for filter_index, content in enumerate(sdk_filters):
                check(
                    content is not None
                    and 'includeGroup("com.microsoft.onnxruntime")' in content,
                    "sdk mavenLocal declaration {} restricts its own content block to "
                    'includeGroup("com.microsoft.onnxruntime")'.format(filter_index + 1),
                )
            required = ("google()", "mavenCentral()", "jitpack", "mavenLocal")
            missing = [decl for decl in required if decl not in sdk_repos]
            check(
                not missing,
                f"sdk repositories block declares google, mavenCentral, jitpack, mavenLocal (missing: {missing})",
            )
            if not missing:
                declared_before = [sdk_repos.find(decl) for decl in required[:3]]
                check(
                    sdk_repos.find("mavenLocal") > max(declared_before),
                    "sdk repositories block places mavenLocal after google, mavenCentral, and jitpack",
                )

    # 5. velaboard/build.gradle.kts mavenLocal restriction & ordering.
    # Only the allprojects repositories block counts, never the buildscript one.
    velaboard_build_path = os.path.join(REPO_ROOT, "velaboard", "build.gradle.kts")
    check(os.path.isfile(velaboard_build_path), "velaboard/build.gradle.kts exists")
    if os.path.isfile(velaboard_build_path):
        with open(velaboard_build_path, "r", encoding="utf-8") as f:
            velaboard_build = f.read()
        vb_repos = active_block(
            active_block(velaboard_build, r"allprojects\s*\{") or "",
            r"repositories\s*\{",
        )
        check(
            vb_repos is not None,
            "velaboard/build.gradle.kts declares allprojects { repositories { ... } }",
        )
        if vb_repos is not None:
            vb_filters = maven_local_content_blocks(vb_repos)
            check(bool(vb_filters), "velaboard repositories block declares at least one mavenLocal")
            for filter_index, content in enumerate(vb_filters):
                check(
                    content is not None
                    and 'includeGroup("com.velavoice.sdk")' in content
                    and 'includeGroup("com.microsoft.onnxruntime")' in content,
                    "velaboard mavenLocal declaration {} restricts its own content block to "
                    "com.velavoice.sdk and com.microsoft.onnxruntime".format(filter_index + 1),
                )
            required = ("google()", "mavenCentral()", "jitpack", "mavenLocal")
            missing = [decl for decl in required if decl not in vb_repos]
            check(
                not missing,
                f"velaboard repositories block declares google, mavenCentral, jitpack, mavenLocal (missing: {missing})",
            )
            if not missing:
                declared_before = [vb_repos.find(decl) for decl in required[:3]]
                check(
                    vb_repos.find("mavenLocal") > max(declared_before),
                    "velaboard repositories block places mavenLocal after google, mavenCentral, and jitpack",
                )

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
        # verifyChecksum must gate the publication task itself: publishToMavenLocal
        # only lists it as a sibling, and Gradle gives no ordering guarantee there.
        wiring = re.search(
            r'tasks\.named\("publishGenaiAarPublicationToMavenLocal"\)\s*\{([^}]*)\}',
            p_content,
        )
        # Strip comments first so `// dependsOn(verifyChecksum)` cannot pass this.
        wiring_body = strip_comments(wiring.group(1)) if wiring else ""
        check(
            wiring is not None and "dependsOn(verifyChecksum)" in wiring_body,
            "publish-genai-aar gates publishGenaiAarPublicationToMavenLocal on verifyChecksum",
        )
        # OCR medium/security: the named task is not the only publish path.
        # Every repository-publish task must wait for verification too.
        with_type = re.search(
            r"tasks\.withType<PublishToMavenRepository>\(\)\.configureEach\s*\{([^}]*)\}",
            p_content,
        )
        with_type_body = strip_comments(with_type.group(1)) if with_type else ""
        check(
            with_type is not None and "dependsOn(verifyChecksum)" in with_type_body,
            "publish-genai-aar gates every PublishToMavenRepository task on verifyChecksum",
        )

    print(f"\nTest Summary: {passed} passed, {failed} failed")
    if failed > 0:
        sys.exit(1)
    print("SUCCESS: All dependency integrity assertions passed!")

if __name__ == "__main__":
    run_tests()
