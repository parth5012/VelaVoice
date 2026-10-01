#!/usr/bin/env python3
"""
Test build script hygiene and gitignore security rules:
1. velaboard/app/build.gradle.kts has no `force(` remaining
2. androidx.core:core-ktx declared version matches actually shipped version (1.15.0) and stale minSdk comment removed
3. .env is loaded via a cache-tracked provider (providers.fileContents) without undeclared File.readLines
4. BuildConfig strings have quote-stripping and escaping applied before buildConfigField
5. Root .gitignore covers keystores, certificates, macOS cruft, and build artifacts
6. git check-ignore probes verify matching files are ignored at root and nested paths
"""

import os
import re
import subprocess
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
APP_BUILD_GRADLE = os.path.join(REPO_ROOT, "velaboard", "app", "build.gradle.kts")
ROOT_GITIGNORE = os.path.join(REPO_ROOT, ".gitignore")

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
    print("=== Running Build Script Hygiene & Security Verification Tests ===")

    check(os.path.isfile(APP_BUILD_GRADLE), "velaboard/app/build.gradle.kts exists")
    if not os.path.isfile(APP_BUILD_GRADLE):
        print(f"\nTest Summary: {passed} passed, {failed} failed")
        sys.exit(1)

    with open(APP_BUILD_GRADLE, "r", encoding="utf-8") as f:
        gradle_content = f.read()

    # 1. No force() remaining
    has_force = "force(" in gradle_content
    check(not has_force, "velaboard/app/build.gradle.kts has no `force(` calls remaining")

    # 2. androidx.core:core-ktx declared version matches 1.15.0 and stale minSdk comment removed
    has_core_1_15 = 'implementation("androidx.core:core-ktx:1.15.0")' in gradle_content
    check(has_core_1_15, "velaboard/app/build.gradle.kts declares androidx.core:core-ktx:1.15.0")
    
    stale_comment = "1.18.0 requires minSdk 23"
    check(stale_comment not in gradle_content, "Stale comment '1.18.0 requires minSdk 23' is removed")

    # 3. .env loaded via cache-tracked provider
    uses_providers_file_contents = "providers.fileContents" in gradle_content
    check(uses_providers_file_contents, ".env is loaded via providers.fileContents (cache-tracked provider)")

    has_undeclared_readlines = "readLines()" in gradle_content or "File.readLines" in gradle_content
    check(not has_undeclared_readlines, "No undeclared File.readLines() on .env file")

    # 4. Quote stripping and escaping applied before buildConfigField
    has_escaping_func = "escapeBuildConfig" in gradle_content or "escapeJavaString" in gradle_content
    check(has_escaping_func, "Escaping helper function defined for BuildConfig fields")

    # Check for unescaped direct interpolation pattern: buildConfigField(..., "\"${envConfig[...] ?: ""}\"")
    unescaped_pattern = re.search(r'buildConfigField\([^,]+,\s*"GOOGLE_DRIVE_[^"]+",\s*"\\"\$\{[^}]+\}\\""\)', gradle_content)
    check(unescaped_pattern is None, "No raw unescaped string interpolation in GOOGLE_DRIVE_* buildConfigField")

    # Check that GOOGLE_DRIVE fields use the escaping helper
    uses_escape_for_client_id = re.search(r'buildConfigField\([^,]+,\s*"GOOGLE_DRIVE_CLIENT_ID",\s*(escapeBuildConfig|escapeJavaString)\(', gradle_content) is not None
    uses_escape_for_client_sec = re.search(r'buildConfigField\([^,]+,\s*"GOOGLE_DRIVE_CLIENT_SECRET",\s*(escapeBuildConfig|escapeJavaString)\(', gradle_content) is not None
    uses_escape_for_refresh_tok = re.search(r'buildConfigField\([^,]+,\s*"GOOGLE_DRIVE_REFRESH_TOKEN",\s*(escapeBuildConfig|escapeJavaString)\(', gradle_content) is not None
    check(uses_escape_for_client_id and uses_escape_for_client_sec and uses_escape_for_refresh_tok,
          "All GOOGLE_DRIVE BuildConfig fields use escaping and quote-stripping helper")

    # Check that quote stripping is performed (removeSurrounding)
    has_quote_stripping = "removeSurrounding" in gradle_content
    check(has_quote_stripping, "Quote stripping (.removeSurrounding) is applied to .env values")

    # 5. Escaping contract verification in Kotlin source (escapeBuildConfig)
    match_func = re.search(r'fun\s+escapeBuildConfig\s*\([^)]*\)\s*:\s*String\s*\{([^}]+)\}', gradle_content)
    check(match_func is not None, "escapeBuildConfig is defined in velaboard/app/build.gradle.kts")
    if match_func:
        func_body = match_func.group(1)

        idx_unquote = func_body.find("removeSurrounding")
        idx_slash = func_body.find("""replace("\\\\", "\\\\\\\\")""")
        idx_quote = func_body.find("""replace("\\"", "\\\\\\"")""")
        idx_r = func_body.find("""replace("\\r", "\\\\r")""")
        idx_n = func_body.find("""replace("\\n", "\\\\n")""")
        idx_return = func_body.find("""return "\\"$escaped\\"" """[:-1])

        # Outer-quote stripping happens before escaping
        check(idx_unquote != -1 and idx_slash != -1 and idx_unquote < idx_slash,
              "Outer-quote stripping (.removeSurrounding) happens before character escaping")

        # Replacement order: backslash before quote
        check(idx_slash != -1 and idx_quote != -1 and idx_slash < idx_quote,
              "Replacement order is correct: .replace(\"\\\\\", \"\\\\\\\\\") appears before .replace(\"\\\"\", \"\\\\\\\"\")")

        # \r and \n replacements are present
        check(idx_r != -1, "Carriage return replacement .replace(\"\\r\", \"\\\\r\") is present")
        check(idx_n != -1, "Newline replacement .replace(\"\\n\", \"\\\\n\") is present")

        # Function returns value wrapped in double quotes
        check(idx_return != -1, "escapeBuildConfig returns value wrapped in double quotes")

    # 6. Root .gitignore covers required patterns
    check(os.path.isfile(ROOT_GITIGNORE), "Root .gitignore exists")
    if os.path.isfile(ROOT_GITIGNORE):
        with open(ROOT_GITIGNORE, "r", encoding="utf-8") as f:
            gitignore_content = f.read()

        required_patterns = [
            "*.keystore",
            "*.jks",
            "*.p12",
            "*.pem",
            ".DS_Store",
            "*.apk",
            "*.aab",
            "*.aar"
        ]
        for pattern in required_patterns:
            # Pattern should be in root gitignore as its own line (or stripped of spaces)
            lines = [l.strip() for l in gitignore_content.splitlines()]
            check(pattern in lines, f"Root .gitignore contains pattern '{pattern}'")

    # 7. git check-ignore probes
    probe_files = [
        "release.keystore",
        "upload.jks",
        "cert.p12",
        "key.pem",
        ".DS_Store",
        "app-release.apk",
        "bundle.aab",
        "library.aar",
        "sdk/release.keystore",
        "sdk/upload.jks",
        "sdk/cert.p12",
        "sdk/key.pem",
        "sdk/.DS_Store",
        "sdk/app-release.apk",
        "sdk/bundle.aab",
        "sdk/library.aar",
        "velaboard/app/release.keystore",
        "velaboard/app/.DS_Store",
        "velaboard/app/output.apk"
    ]

    for probe in probe_files:
        res = subprocess.run(
            ["git", "check-ignore", "-v", probe],
            cwd=REPO_ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )
        is_ignored = res.returncode == 0 and len(res.stdout.strip()) > 0
        check(is_ignored, f"git check-ignore correctly matches probe '{probe}'")

    print(f"\nTest Summary: {passed} passed, {failed} failed")
    if failed > 0:
        sys.exit(1)
    print("SUCCESS: All build script hygiene and security assertions passed!")

if __name__ == "__main__":
    run_tests()
