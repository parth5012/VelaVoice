#!/usr/bin/env bash
set -euo pipefail

# Bootstrap ONNX GenAI Android AAR dependency for Vela Voice
# 1. Reads expected SHA-256 checksum from checked-in reference file
# 2. Verifies or downloads onnxruntime-genai-android-0.15.0.aar
# 3. Validates SHA-256 checksum
# 4. Publishes to mavenLocal (~/.m2/repository)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

AAR_NAME="onnxruntime-genai-android-0.15.0.aar"
LIBS_DIR="$REPO_ROOT/sdk/vela-cleaner/libs"
TARGET_AAR="$LIBS_DIR/$AAR_NAME"
CHECKSUM_FILE="$LIBS_DIR/$AAR_NAME.sha256"
UPSTREAM_URL="https://github.com/microsoft/onnxruntime-genai/releases/download/v0.15.0/$AAR_NAME"
DEFAULT_SHA256="a4aeadcd4d70b877c56a74ece7778324a5ee4686f395ef29e4d2a83908b83a6c"

mkdir -p "$LIBS_DIR"

echo "=== ONNX GenAI Android AAR Bootstrap ==="

# Read expected checksum from checked-in reference file
if [ -f "$CHECKSUM_FILE" ]; then
    EXPECTED_SHA256="$(awk '{print $1}' "$CHECKSUM_FILE")"
else
    EXPECTED_SHA256="$DEFAULT_SHA256"
fi

# Step 1: Download or copy from optional cache if not present
if [ ! -f "$TARGET_AAR" ]; then
    if [ -n "${ONNX_AAR_CACHE:-}" ] && [ -f "${ONNX_AAR_CACHE}" ]; then
        echo "Copying cached AAR from $ONNX_AAR_CACHE..."
        cp "$ONNX_AAR_CACHE" "$TARGET_AAR"
    else
        echo "Downloading $AAR_NAME from upstream GitHub release..."
        curl -fsSL -o "$TARGET_AAR.tmp" "$UPSTREAM_URL"
        mv "$TARGET_AAR.tmp" "$TARGET_AAR"
    fi
fi

# Step 2: Verify checksum
echo "Verifying SHA-256 checksum..."
if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL_SHA256="$(sha256sum "$TARGET_AAR" | awk '{print $1}')"
elif command -v shasum >/dev/null 2>&1; then
    ACTUAL_SHA256="$(shasum -a 256 "$TARGET_AAR" | awk '{print $1}')"
else
    ACTUAL_SHA256="$(python3 -c "import hashlib; print(hashlib.sha256(open('$TARGET_AAR','rb').read()).hexdigest())")"
fi

if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
    echo "ERROR: SHA-256 checksum mismatch for $AAR_NAME!"
    echo "  Expected: $EXPECTED_SHA256"
    echo "  Actual:   $ACTUAL_SHA256"
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
