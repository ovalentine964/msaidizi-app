#!/usr/bin/env bash
# ============================================================
# Generate a release keystore for signing Msaidizi APK
# ============================================================
# Usage: ./scripts/generate-keystore.sh
# Creates: release.keystore (JKS format, RSA 2048-bit, 20-year validity)
# Also creates: keystore.properties (template — fill in your passwords)
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
KEYSTORE_PATH="$PROJECT_ROOT/release.keystore"
PROPS_PATH="$PROJECT_ROOT/keystore.properties"
PROPS_TEMPLATE="$PROJECT_ROOT/keystore.properties.template"

echo "🔑 Msaidizi Release Keystore Generator"
echo "========================================"
echo ""

# Check if keystore already exists
if [ -f "$KEYSTORE_PATH" ]; then
    echo "⚠️  Release keystore already exists at: $KEYSTORE_PATH"
    read -p "Overwrite? (y/N): " OVERWRITE
    if [ "$OVERWRITE" != "y" ] && [ "$OVERWRITE" != "Y" ]; then
        echo "Aborted."
        exit 0
    fi
fi

# Prompt for passwords (or use defaults for dev)
read -sp "Keystore password (min 6 chars): " STORE_PASSWORD
echo ""
read -sp "Key password (min 6 chars): " KEY_PASSWORD
echo ""

if [ ${#STORE_PASSWORD} -lt 6 ] || [ ${#KEY_PASSWORD} -lt 6 ]; then
    echo "❌ Passwords must be at least 6 characters"
    exit 1
fi

KEY_ALIAS="msaidizi-release"

echo ""
echo "📝 Generating keystore..."

keytool -genkeypair -v \
    -keystore "$KEYSTORE_PATH" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 7300 \
    -storepass "$STORE_PASSWORD" \
    -keypass "$KEY_PASSWORD" \
    -dname "CN=Msaidizi Release,OU=Mobile,O=Angavu Intelligence Ltd,L=Migori,ST=Migori,C=KE"

echo ""
echo "✅ Keystore created: $KEYSTORE_PATH"
echo ""

# Create keystore.properties
cat > "$PROPS_PATH" << EOF
# Msaidizi Release Keystore Configuration
# Generated: $(date -u +"%Y-%m-%d %H:%M:%S UTC")
#
# This file is in .gitignore — NEVER commit it.
# Back up the keystore file separately in a secure location.

storeFile=release.keystore
storePassword=$STORE_PASSWORD
keyAlias=$KEY_ALIAS
keyPassword=$KEY_PASSWORD
EOF

echo "✅ keystore.properties created: $PROPS_PATH"
echo ""

# Generate base64 for CI
BASE64_FILE="$PROJECT_ROOT/release-keystore-base64.txt"
base64 -w 0 "$KEYSTORE_PATH" > "$BASE64_FILE" 2>/dev/null || \
base64 -i "$KEYSTORE_PATH" > "$BASE64_FILE" 2>/dev/null || \
base64 "$KEYSTORE_PATH" > "$BASE64_FILE" 2>/dev/null

if [ -f "$BASE64_FILE" ]; then
    echo "✅ Base64-encoded keystore: $BASE64_FILE"
    echo "   → Set this as GitHub secret: RELEASE_KEYSTORE_BASE64"
fi

echo ""
echo "========================================"
echo "🎉 Done! Next steps:"
echo ""
echo "  1. Build signed APK: ./scripts/build-release.sh"
echo "  2. Verify signing:   ./scripts/verify-apk-signing.sh app/build/outputs/apk/release/app-release.apk"
echo "  3. For CI: paste base64 content as RELEASE_KEYSTORE_BASE64 secret"
echo ""
echo "⚠️  SECURITY: Back up $KEYSTORE_PATH in a secure location!"
echo "   Losing it means you cannot update the app on Play Store."
