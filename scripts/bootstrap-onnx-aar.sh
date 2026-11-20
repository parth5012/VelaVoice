#!/usr/bin/env bash
set -euo pipefail

# Bootstrap ONNX GenAI Android AAR dependency for Vela Voice
# 1. Reads expected SHA-256 checksums from checked-in reference files
# 2. Downloads the pinned Microsoft.ML.OnnxRuntimeGenAI NuGet package
# 3. Verifies the package SHA-256 and extracts the Android AAR from it
# 4. Validates the AAR SHA-256 (byte-identical to the former GitHub release asset)
# 5. Publishes to mavenLocal (~/.m2/repository)
#
# The microsoft/onnxruntime-genai GitHub repositories were made private on
# 2026-11-17, so the old releases/download/v0.15.0 asset URL returns 404.
# NuGet is a Microsoft-operated feed and still serves the same 0.15.0 AAR.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

AAR_NAME="onnxruntime-genai-android-0.15.0.aar"
LIBS_DIR="$REPO_ROOT/sdk/vela-cleaner/libs"
TARGET_AAR="$LIBS_DIR/$AAR_NAME"
CHECKSUM_FILE="$LIBS_DIR/$AAR_NAME.sha256"
DEFAULT_SHA256="a4aeadcd4d70b877c56a74ece7778324a5ee4686f395ef29e4d2a83908b83a6c"

NUPKG_NAME="microsoft.ml.onnxruntimegenai.0.15.0.nupkg"
NUPKG_CHECKSUM_FILE="$LIBS_DIR/$NUPKG_NAME.sha256"
DEFAULT_NUPKG_SHA256="f5fdaef183f5bdf16f08dd00da383dca905f63932b9b26abfeea56c657765b7f"
UPSTREAM_URL="https://api.nuget.org/v3-flatcontainer/microsoft.ml.onnxruntimegenai/0.15.0/microsoft.ml.onnxruntimegenai.0.15.0.nupkg"
AAR_MEMBER="runtimes/android/native/onnxruntime-genai.aar"

NUPKG_TMP=""
NUPKG_DOWNLOADED=0
trap 'if [ "$NUPKG_DOWNLOADED" -eq 1 ]; then rm -f "$NUPKG_TMP"; fi; rm -f "$TARGET_AAR.tmp"' EXIT

# Windows' python3 can be a non-functional Microsoft Store stub; verify it runs.
if command -v python3 >/dev/null 2>&1 && python3 -c 'pass' >/dev/null 2>&1; then
    PYTHON=python3
elif command -v python >/dev/null 2>&1 && python -c 'pass' >/dev/null 2>&1; then
    PYTHON=python
else
    echo "ERROR: a working python3 (or python) is required to extract the AAR" >&2
    exit 1
fi

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        "$PYTHON" -c 'import hashlib, sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$1"
    fi
}

mkdir -p "$LIBS_DIR"

echo "=== ONNX GenAI Android AAR Bootstrap ==="

# Read expected checksums from checked-in reference files
EXPECTED_SHA256="$DEFAULT_SHA256"
if [ -f "$CHECKSUM_FILE" ]; then
    EXPECTED_SHA256="$(awk '{print $1}' "$CHECKSUM_FILE")"
fi
EXPECTED_NUPKG_SHA256="$DEFAULT_NUPKG_SHA256"
if [ -f "$NUPKG_CHECKSUM_FILE" ]; then
    EXPECTED_NUPKG_SHA256="$(awk '{print $1}' "$NUPKG_CHECKSUM_FILE")"
fi

# Step 1: Download or copy from optional cache if not present
if [ ! -f "$TARGET_AAR" ]; then
    if [ -n "${ONNX_AAR_CACHE:-}" ] && [ -f "${ONNX_AAR_CACHE}" ]; then
        echo "Copying cached AAR from $ONNX_AAR_CACHE..."
        cp "$ONNX_AAR_CACHE" "$TARGET_AAR"
    else
        if [ -n "${ONNX_NUPKG_CACHE:-}" ] && [ -f "${ONNX_NUPKG_CACHE}" ]; then
            echo "Using cached NuGet package from $ONNX_NUPKG_CACHE..."
            NUPKG_TMP="$ONNX_NUPKG_CACHE"
        else
            echo "Downloading pinned $NUPKG_NAME from the Microsoft NuGet feed..."
            NUPKG_TMP="$(mktemp "${TMPDIR:-${TEMP:-${TMP:-/tmp}}}/onnx-genai-nupkg.XXXXXX")"
            NUPKG_DOWNLOADED=1
            curl -fsSL --retry 3 -o "$NUPKG_TMP" "$UPSTREAM_URL"
        fi

        echo "Verifying NuGet package SHA-256..."
        ACTUAL_NUPKG_SHA256="$(sha256_of "$NUPKG_TMP")"
        if [ "$ACTUAL_NUPKG_SHA256" != "$EXPECTED_NUPKG_SHA256" ]; then
            echo "ERROR: SHA-256 checksum mismatch for $NUPKG_NAME!" >&2
            echo "  Expected: $EXPECTED_NUPKG_SHA256" >&2
            echo "  Actual:   $ACTUAL_NUPKG_SHA256" >&2
            exit 1
        fi
        echo "NuGet package SHA-256 verified: $ACTUAL_NUPKG_SHA256"

        echo "Extracting $AAR_MEMBER from $NUPKG_NAME..."
        "$PYTHON" - "$NUPKG_TMP" "$AAR_MEMBER" "$TARGET_AAR.tmp" <<'PY'
import shutil
import sys
import zipfile

nupkg, member, destination = sys.argv[1:4]
with zipfile.ZipFile(nupkg) as archive:
    with archive.open(member) as source, open(destination, "wb") as target:
        shutil.copyfileobj(source, target)
PY
        mv "$TARGET_AAR.tmp" "$TARGET_AAR"
        if [ "$NUPKG_DOWNLOADED" -eq 1 ]; then
            rm -f "$NUPKG_TMP"
            NUPKG_TMP=""
            NUPKG_DOWNLOADED=0
        fi
    fi
fi

# Step 2: Verify checksum of the AAR that will be published
echo "Verifying SHA-256 checksum..."
ACTUAL_SHA256="$(sha256_of "$TARGET_AAR")"

if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
    echo "ERROR: SHA-256 checksum mismatch for $AAR_NAME!" >&2
    echo "  Expected: $EXPECTED_SHA256" >&2
    echo "  Actual:   $ACTUAL_SHA256" >&2
    rm -f "$TARGET_AAR"
    exit 1
fi

echo "SHA-256 verified successfully: $ACTUAL_SHA256"

# Step 3: Publish to mavenLocal
M2_TARGET_DIR="$HOME/.m2/repository/com/microsoft/onnxruntime/onnxruntime-genai-android/0.15.0"
echo "Publishing to mavenLocal at $M2_TARGET_DIR..."
mkdir -p "$M2_TARGET_DIR"

cp "$TARGET_AAR" "$M2_TARGET_DIR/$AAR_NAME"

# Generate minimal POM file
POM_FILE="$M2_TARGET_DIR/onnxruntime-genai-android-0.15.0.pom"
cat <<'EOF' > "$POM_FILE"
<?xml version="1.0" encoding="UTF-8"?>
<project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.microsoft.onnxruntime</groupId>
  <artifactId>onnxruntime-genai-android</artifactId>
  <version>0.15.0</version>
  <packaging>aar</packaging>
</project>
EOF

echo "SUCCESS: onnxruntime-genai-android:0.15.0 installed to mavenLocal."
