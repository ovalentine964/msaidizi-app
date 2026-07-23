#!/bin/bash
# Msaidizi — Build Script
# Builds the full APK with all models bundled

set -e

echo "🔨 Building Msaidizi — Super Agent for Africa's Informal Workers"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Check prerequisites
if ! command -v java &> /dev/null; then
    echo "❌ Java not found. Install JDK 17."
    exit 1
fi

# Clean
echo "🧹 Cleaning..."
./gradlew clean --no-daemon

# Build full release
echo "🔨 Building full release APK..."
./gradlew assembleFullRelease --no-daemon

# Check output
APK_PATH="app/build/outputs/apk/full/release/app-full-release.apk"
if [ -f "$APK_PATH" ]; then
    SIZE=$(du -h "$APK_PATH" | cut -f1)
    echo ""
    echo "✅ Build successful!"
    echo "📱 APK: $APK_PATH"
    echo "📦 Size: $SIZE"
    echo ""
    echo "To install on device:"
    echo "  adb install $APK_PATH"
    echo ""
    echo "To create a release:"
    echo "  git tag v0.1.0"
    echo "  git push origin v0.1.0"
else
    echo "❌ Build failed — APK not found"
    exit 1
fi
