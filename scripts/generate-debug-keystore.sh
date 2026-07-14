#!/usr/bin/env bash
# ============================================================
# generate-debug-keystore.sh
# Generates a project-local debug keystore for signing debug APKs.
# Run this once on any machine with JDK 17+ installed.
#
# Usage: ./scripts/generate-debug-keystore.sh
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
KEYSTORE_PATH="$PROJECT_ROOT/debug.keystore"

if [ -f "$KEYSTORE_PATH" ]; then
    echo "✅ Debug keystore already exists at $KEYSTORE_PATH"
    echo "   Delete it first if you want to regenerate: rm $KEYSTORE_PATH"
    exit 0
fi

echo "🔑 Generating debug keystore..."
keytool -genkeypair \
    -v \
    -keystore "$KEYSTORE_PATH" \
    -alias androiddebugkey \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -storepass android \
    -keypass android \
    -dname "CN=Android Debug,O=Msaidizi,C=KE"

echo ""
echo "✅ Debug keystore generated: $KEYSTORE_PATH"
echo "   Alias:    androiddebugkey"
echo "   Password: android"
echo ""
echo "This keystore is for DEBUG builds only."
echo "For release builds, create a proper keystore and update keystore.properties."
