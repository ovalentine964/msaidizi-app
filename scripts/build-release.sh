#!/usr/bin/env bash
# ============================================================
# build-release.sh — Build a properly signed release APK
# ============================================================
# Usage:
#   ./scripts/build-release.sh              # Uses keystore.properties
#   ./scripts/build-release.sh --ci          # Uses env vars (CI mode)
#
# Prerequisites:
#   - Release keystore (run ./scripts/generate-keystore.sh first)
#   - keystore.properties with credentials (local) or env vars (CI)
# ============================================================

set -euo pipefail

MODE="local"
if [ "${1:-}" = "--ci" ]; then
    MODE="ci"
fi

echo "============================================================"
echo "  Msaidizi Release Build ($MODE mode)"
echo "============================================================"
echo ""

# Verify keystore exists
if [ "$MODE" = "ci" ]; then
    if [ -z "${RELEASE_KEYSTORE_FILE:-}" ]; then
        echo "❌ RELEASE_KEYSTORE_FILE not set. Decode the keystore first."
        exit 1
    fi
    if [ ! -f "$RELEASE_KEYSTORE_FILE" ]; then
        echo "❌ Keystore file not found: $RELEASE_KEYSTORE_FILE"
        exit 1
    fi
    echo "✅ Using keystore from env: $RELEASE_KEYSTORE_FILE"
else
    if [ ! -f "keystore.properties" ] && [ ! -f "app/msaidizi-release.keystore" ]; then
        echo "❌ No signing config found."
        echo "   Run: ./scripts/generate-keystore.sh"
        echo "   Then create keystore.properties from keystore.properties.template"
        exit 1
    fi
    echo "✅ Using keystore.properties / msaidizi-release.keystore"
fi

echo ""
echo "🔨 Building release APK..."
echo ""

./gradlew clean assembleRelease --stacktrace 2>&1 | tee build-release.log

APK_PATH=$(find app/build/outputs/apk/release -name "*.apk" | head -1)

if [ -z "$APK_PATH" ]; then
    echo "❌ Build failed — no APK generated"
    exit 1
fi

echo ""
echo "============================================================"
echo "  Verifying APK signature"
echo "============================================================"
echo ""

chmod +x scripts/verify-apk-signing.sh
./scripts/verify-apk-signing.sh "$APK_PATH"

echo ""
echo "============================================================"
echo "  Build Summary"
echo "============================================================"
echo ""
echo "  APK: $APK_PATH"
echo "  Size: $(du -h "$APK_PATH" | cut -f1)"
echo ""

# Calculate SHA-256
SHA256=$(sha256sum "$APK_PATH" | cut -d' ' -f1)
echo "  SHA-256: $SHA256"
echo ""
echo "============================================================"
echo "  ✅ Release build complete!"
echo "============================================================"
echo ""
echo "  To install on device:"
echo "    adb install $APK_PATH"
echo ""
echo "  To distribute:"
echo "    Upload to Google Play Console, or share the APK directly."
echo "    Note: Play Protect may still warn on first install from"
echo "    unknown sources — this is expected for non-Play Store apps."
echo "    A properly signed APK reduces the warning severity."
echo "============================================================"
