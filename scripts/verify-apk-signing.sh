#!/usr/bin/env bash
# ============================================================
# verify-apk-signing.sh — Verify APK signature and print cert info
# ============================================================
# Usage: ./scripts/verify-apk-signing.sh <path-to-apk>
#
# Verifies that an APK is properly signed and prints:
#   - Certificate fingerprint (SHA-256)
#   - Signer info
#   - Whether the APK is v1/v2/v3 signed
# ============================================================

set -euo pipefail

APK_PATH="${1:-}"

if [ -z "$APK_PATH" ]; then
    # Auto-detect APK
    APK_PATH=$(find app/build/outputs/apk/release -name "*.apk" 2>/dev/null | head -1)
    if [ -z "$APK_PATH" ]; then
        APK_PATH=$(find app/build/outputs/apk/debug -name "*.apk" 2>/dev/null | head -1)
    fi
fi

if [ -z "$APK_PATH" ] || [ ! -f "$APK_PATH" ]; then
    echo "❌ No APK found. Usage: $0 <path-to-apk>"
    echo "   Or run from project root after building."
    exit 1
fi

echo "============================================================"
echo "  APK Signing Verification"
echo "============================================================"
echo ""
echo "  APK: $APK_PATH"
echo "  Size: $(du -h "$APK_PATH" | cut -f1)"
echo ""

# Find apksigner
APKSIGNER=""
for dir in "$ANDROID_HOME/build-tools"/*/; do
    if [ -f "${dir}apksigner" ]; then
        APKSIGNER="${dir}apksigner"
    fi
done

if [ -z "$APKSIGNER" ]; then
    echo "❌ apksigner not found. Set ANDROID_HOME or install build-tools."
    exit 1
fi

echo "📋 Certificate info:"
echo "------------------------------------------------------------"
"$APKSIGNER" verify --print-certs "$APK_PATH"
echo ""

echo "📋 Verification details:"
echo "------------------------------------------------------------"
"$APKSIGNER" verify -v "$APK_PATH" 2>&1 || true
echo ""

# Check for debug signing
CERT_OUTPUT=$("$APKSIGNER" verify --print-certs "$APK_PATH" 2>&1)
if echo "$CERT_OUTPUT" | grep -q "CN=Android Debug"; then
    echo "⚠️  WARNING: APK is signed with a DEBUG certificate!"
    echo "   This will trigger Play Protect warnings."
    echo "   Use a release keystore for distribution."
    echo ""
else
    echo "✅ APK is signed with a release certificate."
    echo ""
fi

echo "============================================================"
echo "  Verification complete"
echo "============================================================"
