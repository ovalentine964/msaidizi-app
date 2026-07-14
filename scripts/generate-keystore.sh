#!/usr/bin/env bash
# ============================================================
# generate-keystore.sh — Generate a release keystore for Msaidizi
# ============================================================
# Usage: ./scripts/generate-keystore.sh [output-path]
#
# Creates a release keystore with proper signing configuration.
# The keystore is used for signing release APKs/AABs.
#
# IMPORTANT: Keep the generated keystore and passwords SAFE.
#            Losing the keystore means you cannot update the app
#            on the Play Store (the signing key must match).
# ============================================================

set -euo pipefail

KEYSTORE_PATH="${1:-release.keystore}"
KEY_ALIAS="${RELEASE_KEY_ALIAS:-msaidizi-release}"
VALIDITY_DAYS=10000  # ~27 years

echo "============================================================"
echo "  Msaidizi Release Keystore Generator"
echo "============================================================"
echo ""
echo "  Keystore: $KEYSTORE_PATH"
echo "  Alias:    $KEY_ALIAS"
echo "  Validity: $VALIDITY_DAYS days (~27 years)"
echo ""

# Check if keystore already exists

if [ -f "$KEYSTORE_PATH" ]; then
    echo "⚠️  Keystore already exists: $KEYSTORE_PATH"
    echo "   Delete it first if you want to regenerate."
    exit 1
fi

# Prompt for passwords (or use env vars)
if [ -z "${RELEASE_STORE_PASSWORD:-}" ]; then
    echo "Enter keystore password (min 6 characters):"
    read -rs STORE_PASSWORD
    echo ""
    echo "Confirm keystore password:"
    read -rs STORE_PASSWORD_CONFIRM
    echo ""
    if [ "$STORE_PASSWORD" != "$STORE_PASSWORD_CONFIRM" ]; then
        echo "❌ Passwords don't match"
        exit 1
    fi
else
    STORE_PASSWORD="$RELEASE_STORE_PASSWORD"
fi

if [ -z "${RELEASE_KEY_PASSWORD:-}" ]; then
    echo "Enter key password (min 6 characters):"
    read -rs KEY_PASSWORD
    echo ""
    echo "Confirm key password:"
    read -rs KEY_PASSWORD_CONFIRM
    echo ""
    if [ "$KEY_PASSWORD" != "$KEY_PASSWORD_CONFIRM" ]; then
        echo "❌ Passwords don't match"
        exit 1
    fi
else
    KEY_PASSWORD="$RELEASE_KEY_PASSWORD"
fi

echo "🔐 Generating release keystore..."

keytool -genkeypair -v \
    -keystore "$KEYSTORE_PATH" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity "$VALIDITY_DAYS" \
    -storepass "$STORE_PASSWORD" \
    -keypass "$KEY_PASSWORD" \
    -dname "CN=Msaidizi App,O=Msaidizi,C=KE"

echo ""
echo "✅ Release keystore generated: $KEYSTORE_PATH"
echo ""
echo "============================================================"
echo "  Next Steps"
echo "============================================================"
echo ""
echo "  1. Create keystore.properties (DO NOT commit this file):"
echo ""
echo "     storeFile=$KEYSTORE_PATH"
echo "     storePassword=<your-password>"
echo "     keyAlias=$KEY_ALIAS"
echo "     keyPassword=<your-key-password>"
echo ""
echo "  2. Build a signed release APK:"
echo ""
echo "     ./gradlew assembleRelease"
echo ""
echo "  3. For CI (GitHub Actions), encode the keystore:"
echo ""
echo "     base64 -w 0 $KEYSTORE_PATH | pbcopy"
echo ""
echo "     Then add these repository secrets:"
echo "       RELEASE_KEYSTORE_BASE64  ← (paste the base64 output)"
echo "       RELEASE_STORE_PASSWORD   ← your keystore password"
echo "       RELEASE_KEY_ALIAS        ← $KEY_ALIAS"
echo "       RELEASE_KEY_PASSWORD     ← your key password"
echo ""
echo "============================================================"
echo "  ⚠️  KEEP THIS KEYSTORE SAFE!"
echo "  Losing it means you cannot update the app on Play Store."
echo "============================================================"
