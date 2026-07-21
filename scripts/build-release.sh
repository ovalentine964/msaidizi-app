#!/usr/bin/env bash
# ============================================================
# Build a signed release APK for Msaidizi
# ============================================================
# Usage: ./scripts/build-release.sh
# Requires: keystore.properties OR env vars (RELEASE_KEYSTORE_FILE, etc.)
# Output: app/build/outputs/apk/full/release/app-full-release.apk
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "🏗️  Msaidizi Release Build"
echo "=========================="
echo ""

cd "$PROJECT_ROOT"

# Check signing configuration
if [ -n "${RELEASE_KEYSTORE_FILE:-}" ]; then
    echo "📝 Signing: using env var RELEASE_KEYSTORE_FILE=$RELEASE_KEYSTORE_FILE"
elif [ -f "keystore.properties" ]; then
    echo "📝 Signing: using keystore.properties"
else
    echo "⚠️  No release keystore found — will fall back to debug signing"
    echo "   Run ./scripts/generate-keystore.sh to create one"
fi

echo ""
echo "🔧 Building release APK..."
echo ""

./gradlew assembleFullRelease --stacktrace 2>&1 | tee build-release.log
EXIT_CODE=${PIPESTATUS[0]}

if [ "$EXIT_CODE" -ne 0 ]; then
    echo ""
    echo "❌ Build failed (exit code $EXIT_CODE)"
    echo "   Check build-release.log for details"
    exit "$EXIT_CODE"
fi

echo ""
echo "✅ Build succeeded"

# Find the APK
APK_PATH=$(find app/build/outputs/apk/full/release -name "*.apk" | head -1)
if [ -z "$APK_PATH" ]; then
    echo "❌ No APK found in app/build/outputs/apk/full/release/"
    exit 1
fi

SIZE=$(stat -c%s "$APK_PATH" 2>/dev/null || stat -f%z "$APK_PATH" 2>/dev/null)
SIZE_MB=$((SIZE / 1048576))
echo "📦 APK: $APK_PATH (${SIZE_MB}MB)"
echo ""

# Verify signing
echo "🔍 Verifying APK signature..."
"$SCRIPT_DIR/verify-apk-signing.sh" "$APK_PATH" || true

echo ""
echo "🎉 Release APK ready: $APK_PATH"
