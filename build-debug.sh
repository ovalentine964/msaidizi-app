#!/usr/bin/env bash
# ============================================================
# build-debug.sh — Build a signed debug APK
# ============================================================
# Usage: ./build-debug.sh
#
# Prerequisites:
#   - JDK 17+ installed
#   - Android SDK (or Android Studio)
#   - JAVA_HOME set
#
# The debug keystore is auto-generated if it doesn't exist.
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "🏗️  Building Msaidizi Debug APK..."
echo ""

# Step 1: Ensure debug keystore exists
if [ ! -f "debug.keystore" ]; then
    echo "🔑 No debug.keystore found — generating one..."
    if command -v keytool &>/dev/null; then
        keytool -genkeypair -v \
            -keystore debug.keystore \
            -alias androiddebugkey \
            -keyalg RSA \
            -keysize 2048 \
            -validity 10000 \
            -storepass android \
            -keypass android \
            -dname "CN=Android Debug,O=Msaidizi,C=KE"
        echo "✅ Debug keystore generated"
    else
        echo "⚠️  keytool not found. Using default ~/.android/debug.keystore"
        echo "   Install JDK 17+ or run: ./scripts/generate-debug-keystore.sh"
    fi
fi

# Step 2: Run the build
echo ""
echo "📦 Running ./gradlew assembleDebug..."
echo ""

./gradlew assembleDebug --stacktrace "$@"

# Step 3: Report result
echo ""
APK_PATH=$(find app/build/outputs/apk/debug -name "*.apk" 2>/dev/null | head -1)
if [ -n "$APK_PATH" ]; then
    SIZE=$(stat -c%s "$APK_PATH" 2>/dev/null || stat -f%z "$APK_PATH" 2>/dev/null)
    SIZE_MB=$((SIZE / 1048576))
    echo "✅ BUILD SUCCESSFUL"
    echo "   APK: $APK_PATH"
    echo "   Size: ${SIZE_MB}MB"
    echo ""
    echo "Install on device:"
    echo "   adb install $APK_PATH"
else
    echo "❌ BUILD FAILED — no APK found"
    exit 1
fi
