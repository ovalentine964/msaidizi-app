#!/usr/bin/env bash
# ============================================================
# Verify APK signing and certificate info
# ============================================================
# Usage: ./scripts/verify-apk-signing.sh <path-to-apk>
# Checks: valid signature, certificate details, NOT debug-signed
# ============================================================

set -euo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: $0 <path-to-apk>"
    exit 1
fi

APK_PATH="$1"

if [ ! -f "$APK_PATH" ]; then
    echo "❌ File not found: $APK_PATH"
    exit 1
fi

echo "🔍 APK Signature Verification"
echo "=============================="
echo "📦 APK: $APK_PATH"
echo ""

# Try apksigner first (preferred)
APKSIGNER=""
if command -v apksigner &>/dev/null; then
    APKSIGNER="apksigner"
elif [ -n "${ANDROID_HOME:-}" ] && [ -f "$ANDROID_HOME/build-tools/*/apksigner" ]; then
    APKSIGNER=$(ls "$ANDROID_HOME/build-tools"/*/apksigner 2>/dev/null | tail -1)
fi

if [ -n "$APKSIGNER" ]; then
    echo "📝 Using apksigner..."
    echo ""

    # Verify signature
    if "$APKSIGNER" verify --verbose "$APK_PATH" 2>&1; then
        echo ""
        echo "✅ APK signature is VALID"
    else
        echo ""
        echo "❌ APK signature verification FAILED"
        exit 1
    fi

    echo ""
    echo "📋 Certificate details:"
    "$APKSIGNER" verify --print-certs "$APK_PATH" 2>&1 || true

    # Check for debug signing
    CERT_OUTPUT=$("$APKSIGNER" verify --print-certs "$APK_PATH" 2>&1 || true)
    if echo "$CERT_OUTPUT" | grep -qi "CN=Android Debug"; then
        echo ""
        echo "⚠️  WARNING: APK is signed with a DEBUG certificate!"
        echo "   This will trigger Google Play Protect warnings."
        echo "   Run ./scripts/generate-keystore.sh to create a release keystore."
    else
        echo ""
        echo "✅ APK is signed with a RELEASE certificate"
    fi

# Fall back to jarsigner
elif command -v jarsigner &>/dev/null; then
    echo "📝 Using jarsigner (apksigner not found)..."
    echo ""

    if jarsigner -verify -verbose "$APK_PATH" 2>&1 | tail -5; then
        echo ""
        echo "✅ APK signature is VALID"
    else
        echo ""
        echo "❌ APK signature verification FAILED"
        exit 1
    fi

    echo ""
    echo "📋 Certificate details:"
    jarsigner -verify -certs "$APK_PATH" 2>&1 | grep -A2 "CN=" || true

else
    echo "⚠️  Neither apksigner nor jarsigner found."
    echo "   Install Android SDK build-tools or set ANDROID_HOME."
    exit 1
fi

echo ""
echo "📊 APK info:"
SIZE=$(stat -c%s "$APK_PATH" 2>/dev/null || stat -f%z "$APK_PATH" 2>/dev/null)
SIZE_MB=$((SIZE / 1048576))
echo "   Size: ${SIZE_MB}MB"
echo "   Path: $APK_PATH"
