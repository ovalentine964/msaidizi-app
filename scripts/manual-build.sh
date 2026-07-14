#!/bin/bash
# ============================================================
# Manual Build Script for Msaidizi APK
# ============================================================
# Use this when CI/CD fails or you need a local build.
#
# Prerequisites:
#   - JDK 17+ (recommended: JDK 21)
#   - Android SDK (API 26+)
#   - Gradle 8.5+
#
# Usage:
#   ./scripts/manual-build.sh          # Build debug APK
#   ./scripts/manual-build.sh release  # Build release APK
#   ./scripts/manual-build.sh upload   # Build and upload to GitHub release
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_ROOT"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

BUILD_TYPE="${1:-debug}"

echo -e "${GREEN}🔧 Msaidizi Manual Build${NC}"
echo "========================"
echo "Build type: $BUILD_TYPE"
echo ""

# ── Step 1: Validate ──
echo -e "${YELLOW}📋 Step 1: Validating build...${NC}"
if [ -f "scripts/validate-build.sh" ]; then
    bash scripts/validate-build.sh || {
        echo -e "${RED}❌ Validation failed. Fix issues above.${NC}"
        exit 1
    }
else
    echo "  ⚠️  No validation script found, skipping"
fi

# ── Step 2: Build ──
echo ""
echo -e "${YELLOW}📋 Step 3: Building APK...${NC}"

if [ "$BUILD_TYPE" = "release" ]; then
    echo "  Building release APK..."
    ./gradlew assembleRelease --stacktrace 2>&1 | tee build-release.log
    APK_PATH="app/build/outputs/apk/release/*.apk"
else
    echo "  Building debug APK..."
    ./gradlew assembleDebug --stacktrace 2>&1 | tee build-debug.log
    APK_PATH="app/build/outputs/apk/debug/*.apk"
fi

# ── Step 4: Copy APK ──
echo ""
echo -e "${YELLOW}📋 Step 4: Copying APK...${NC}"
mkdir -p release-apk
cp $APK_PATH release-apk/msaidizi.apk 2>/dev/null || {
    echo -e "${RED}❌ No APK found at $APK_PATH${NC}"
    exit 1
}

APK_SIZE=$(stat -c%s release-apk/msaidizi.apk 2>/dev/null || stat -f%z release-apk/msaidizi.apk 2>/dev/null)
echo -e "  ✅ APK ready: release-apk/msaidizi.apk (${APK_SIZE} bytes)"

# ── Step 5: Upload (optional) ──
if [ "$BUILD_TYPE" = "upload" ]; then
    echo ""
    echo -e "${YELLOW}📋 Step 5: Uploading to GitHub release...${NC}"
    
    # Check for gh CLI
    if command -v gh &> /dev/null; then
        gh release upload latest release-apk/msaidizi.apk --clobber
        echo -e "  ✅ Uploaded to latest release"
    else
        echo -e "${RED}  ❌ gh CLI not found. Install it or upload manually.${NC}"
        echo "  Manual upload: https://github.com/ovalentine964/msaidizi-app/releases"
    fi
fi

echo ""
echo -e "${GREEN}✅ Build complete!${NC}"
echo "APK: release-apk/msaidizi.apk"
echo "Size: $(ls -lh release-apk/msaidizi.apk | awk '{print $5}')"
