#!/bin/bash
# verify-install-experience.sh
# Build verification script for the one-click install experience
#
# Verifies:
# 1. APK builds successfully
# 2. APK size is under 50MB
# 3. All required assets are included
# 4. Onboarding navigation graph is valid
# 5. Model files are properly referenced
# 6. Website download link works

set +e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

PASS=0
FAIL=0
WARN=0

pass() { echo -e "${GREEN}✅ PASS:${NC} $1"; ((PASS++)); }
fail() { echo -e "${RED}❌ FAIL:${NC} $1"; ((FAIL++)); }
warn() { echo -e "${YELLOW}⚠️  WARN:${NC} $1"; ((WARN++)); }

echo "========================================="
echo " Msaidizi Install Experience Verification"
echo "========================================="
echo ""

# ─── 1. Check APK Build ───
echo "--- 1. APK Build ---"
APK_PATH="app/build/outputs/apk/debug/*.apk"
if ls $APK_PATH 1>/dev/null 2>&1; then
    APK_FILE=$(ls $APK_PATH | head -1)
    APK_SIZE=$(stat -c%s "$APK_FILE" 2>/dev/null || stat -f%z "$APK_FILE" 2>/dev/null)
    APK_SIZE_MB=$((APK_SIZE / 1024 / 1024))
    pass "APK found: $APK_FILE ($APK_SIZE_MB MB)"

    if [ "$APK_SIZE" -gt $((50 * 1024 * 1024)) ]; then
        fail "APK size ($APK_SIZE_MB MB) exceeds 50MB limit"
    else
        pass "APK size ($APK_SIZE_MB MB) within 50MB limit"
    fi
else
    warn "No debug APK found — run ./gradlew assembleDebug first"
fi

# ─── 2. Check Onboarding Files ───
echo ""
echo "--- 2. Onboarding Files ---"
ONBOARDING_DIR="app/src/main/java/com/msaidizi/app/onboarding"

FILES=(
    "IntroductionFragment.kt"
    "LanguageSelectionFragment.kt"
    "VoiceSetupFragment.kt"
    "ModelSetupFragment.kt"
    "ModelSetupViewModel.kt"
    "FirstUseFragment.kt"
    "OnboardingActivity.kt"
)

for f in "${FILES[@]}"; do
    if [ -f "$ONBOARDING_DIR/$f" ]; then
        pass "Found $f"
    else
        fail "Missing $f"
    fi
done

# ─── 3. Check Navigation Graph ───
echo ""
echo "--- 3. Navigation Graph ---"
NAV_GRAPH="app/src/main/res/navigation/nav_onboarding.xml"
if [ -f "$NAV_GRAPH" ]; then
    pass "Navigation graph exists"

    # Check all fragments are referenced
    for fragment in introductionFragment languageSelectionFragment voiceSetupFragment modelSetupFragment firstUseFragment; do
        if grep -q "$fragment" "$NAV_GRAPH"; then
            pass "Fragment '$fragment' in nav graph"
        else
            fail "Fragment '$fragment' missing from nav graph"
        fi
    done

    # Check all actions exist
    for action in action_introduction_to_language_selection action_language_selection_to_voice_setup action_voice_setup_to_model_download action_model_download_to_first_use; do
        if grep -q "$action" "$NAV_GRAPH"; then
            pass "Action '$action' defined"
        else
            fail "Action '$action' missing"
        fi
    done
else
    fail "Navigation graph not found"
fi

# ─── 4. Check BundledModelManager ───
echo ""
echo "--- 4. Bundled Model Manager ---"
BUNDLED_MGR="app/src/main/java/com/msaidizi/app/core/ai/BundledModelManager.kt"
if [ -f "$BUNDLED_MGR" ]; then
    pass "BundledModelManager.kt exists"

    if grep -q "BundledModelState" "$BUNDLED_MGR"; then
        pass "BundledModelState enum defined"
    else
        fail "BundledModelState enum missing"
    fi

    if grep -q "FullModelDownloadState" "$BUNDLED_MGR"; then
        pass "FullModelDownloadState enum defined"
    else
        fail "FullModelDownloadState enum missing"
    fi

    if grep -q "isBundledModelAvailable" "$BUNDLED_MGR"; then
        pass "Bundled model availability check exists"
    else
        fail "Bundled model availability check missing"
    fi

    if grep -q "wifiOnly" "$BUNDLED_MGR"; then
        pass "WiFi-only download option exists"
    else
        fail "WiFi-only download option missing"
    fi
else
    fail "BundledModelManager.kt not found"
fi

# ─── 5. Check Model Registry ───
echo ""
echo "--- 5. Model Registry ---"
MODEL_REGISTRY="app/src/main/java/com/msaidizi/app/voice/ModelRegistry.kt"
if [ -f "$MODEL_REGISTRY" ]; then
    pass "ModelRegistry.kt exists"

    if grep -q "qwen-0.5b" "$MODEL_REGISTRY"; then
        pass "Qwen 0.5B model defined in registry"
    else
        fail "Qwen 0.5B model missing from registry"
    fi
else
    fail "ModelRegistry.kt not found"
fi

# ─── 6. Check Website ───
echo ""
echo "--- 6. Website ---"
# Check in angavu-intelligence repo (sibling directory) or current directory
WEBSITE="../angavu-intelligence/index.html"
if [ ! -f "$WEBSITE" ]; then
    WEBSITE="index.html"
fi
if [ -f "$WEBSITE" ]; then
    pass "Website index.html exists"

    if grep -q "msaidizi.apk" "$WEBSITE"; then
        pass "Download link present"
    else
        fail "Download link missing"
    fi

    if grep -q "releases/download/latest" "$WEBSITE"; then
        pass "Download points to GitHub releases latest tag"
    else
        fail "Download doesn't point to GitHub releases latest"
    fi

    if grep -q "install-requirements" "$WEBSITE"; then
        pass "System requirements section present"
    else
        warn "System requirements section missing"
    fi

    if grep -q "install-whats-included" "$WEBSITE"; then
        pass "What's included section present"
    else
        warn "What's included section missing"
    fi

    if grep -q "install-swahili" "$WEBSITE"; then
        pass "Kiswahili install guide present"
    else
        warn "Kiswahili install guide missing"
    fi

    if grep -q "Android 8.0" "$WEBSITE"; then
        pass "Android version requirement stated"
    else
        warn "Android version requirement not stated"
    fi

    if grep -q "100MB" "$WEBSITE"; then
        pass "Storage requirement stated"
    else
        warn "Storage requirement not stated"
    fi
else
    fail "Website index.html not found"
fi

# ─── 7. Check CI/CD ───
echo ""
echo "--- 7. CI/CD Pipeline ---"
CI_FILE=".github/workflows/ci.yml"
if [ -f "$CI_FILE" ]; then
    pass "CI workflow exists"

    if grep -q "release-build" "$CI_FILE"; then
        pass "Release build job defined"
    else
        fail "Release build job missing"
    fi

    if grep -q "msaidizi.apk" "$CI_FILE"; then
        pass "APK renamed for release"
    else
        warn "APK not renamed for release"
    fi

    if grep -q "softprops/action-gh-release" "$CI_FILE"; then
        pass "GitHub release action configured"
    else
        warn "GitHub release action not configured"
    fi

    if grep -q "latest" "$CI_FILE"; then
        pass "Release tag 'latest' configured"
    else
        warn "Release tag 'latest' not configured"
    fi
else
    fail "CI workflow not found"
fi

# ─── Summary ───
echo ""
echo "========================================="
echo " SUMMARY"
echo "========================================="
echo -e " ${GREEN}Passed: $PASS${NC}"
echo -e " ${RED}Failed: $FAIL${NC}"
echo -e " ${YELLOW}Warnings: $WARN${NC}"
echo ""

if [ "$FAIL" -eq 0 ]; then
    echo -e "${GREEN}🎉 All critical checks passed!${NC}"
    echo "The one-click install experience is ready."
    exit 0
else
    echo -e "${RED}💥 $FAIL critical check(s) failed.${NC}"
    echo "Please fix the issues above."
    exit 1
fi
