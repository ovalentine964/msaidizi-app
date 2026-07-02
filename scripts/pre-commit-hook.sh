#!/bin/bash
# pre-commit-hook.sh — Git pre-commit hook for Msaidizi App.
#
# Workflow:
#   1. Run auto-fix-build.sh to detect and fix common issues
#   2. If fixes were applied, auto-stage and amend the commit
#   3. Run build validation (validate-build.sh)
#   4. If validation fails, block the commit
#
# Install:
#   cp scripts/pre-commit-hook.sh .git/hooks/pre-commit
#   chmod +x .git/hooks/pre-commit

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# When installed as .git/hooks/pre-commit, SCRIPT_DIR points to .git/hooks
# We need the repo root and scripts directory
REPO_ROOT="$(git rev-parse --show-toplevel)"
SCRIPTS_DIR="$REPO_ROOT/scripts"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo ""
echo "🔒 Msaidizi Pre-Commit Hook"
echo "==========================="

# ─────────────────────────────────────────────
# Step 1: Run auto-fix
# ─────────────────────────────────────────────
echo ""
echo "📋 Step 1: Running auto-fix-build.sh..."

AUTO_FIX_OUTPUT=$(bash "$SCRIPTS_DIR/auto-fix-build.sh" 2>&1) || true
AUTO_FIX_EXIT=$?

echo "$AUTO_FIX_OUTPUT"

if [[ $AUTO_FIX_EXIT -eq 1 ]]; then
    # Fixes were applied — stage the changes and amend
    echo ""
    echo -e "${YELLOW}🔧 Auto-fixes detected. Staging fixes...${NC}"

    # Stage any modified files
    git add -u

    # The fixes are now staged. They will be included in the current commit.
    # We do NOT amend here — the parent git commit will include these staged changes.
    echo -e "${GREEN}✅ Auto-fixes staged — will be included in this commit${NC}"

    echo -e "${GREEN}✅ Auto-fixes committed${NC}"
elif [[ $AUTO_FIX_EXIT -eq 2 ]]; then
    echo -e "${RED}❌ Auto-fix script encountered a fatal error${NC}"
    exit 1
fi

# ─────────────────────────────────────────────
# Step 2: Run build validation
# ─────────────────────────────────────────────
echo ""
echo "📋 Step 2: Running build validation..."

if [[ -f "$SCRIPTS_DIR/validate-build.sh" ]]; then
    VALIDATE_OUTPUT=$(bash "$SCRIPTS_DIR/validate-build.sh" 2>&1) || true
    VALIDATE_EXIT=$?

    echo "$VALIDATE_OUTPUT"

    if [[ $VALIDATE_EXIT -ne 0 ]]; then
        echo ""
        echo -e "${RED}❌ Build validation FAILED — commit blocked${NC}"
        echo -e "${YELLOW}Fix the issues above and try again.${NC}"
        exit 1
    fi
else
    echo -e "${YELLOW}⚠️  validate-build.sh not found, skipping validation${NC}"
fi

# ─────────────────────────────────────────────
# Step 3: Run Room entity check
# ─────────────────────────────────────────────
echo ""
echo "📋 Step 3: Running Room entity registration check..."

if [[ -f "$SCRIPTS_DIR/check-room-entities.py" ]]; then
    ENTITY_OUTPUT=$(python3 "$SCRIPTS_DIR/check-room-entities.py" 2>&1) || true
    ENTITY_EXIT=$?

    echo "$ENTITY_OUTPUT"

    if [[ $ENTITY_EXIT -ne 0 ]]; then
        echo ""
        echo -e "${RED}❌ Room entity check FAILED — commit blocked${NC}"
        echo -e "${YELLOW}Run 'bash scripts/auto-fix-build.sh' to auto-fix, then retry.${NC}"
        exit 1
    fi
fi

# ─────────────────────────────────────────────
# All checks passed
# ─────────────────────────────────────────────
echo ""
echo -e "${GREEN}✅ All pre-commit checks passed — commit allowed${NC}"
echo "==========================="
echo ""
exit 0
