#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────
# setup_native_deps.sh — Clone/build native dependencies for Msaidizi.
#
# Downloads:
#   1. llama.cpp — On-device LLM inference (C++ library)
#   2. sherpa-onnx — On-device ASR/TTS (C++ library)
#
# Usage:
#   ./scripts/setup_native_deps.sh              # full setup
#   ./scripts/setup_native_deps.sh --verify     # verify only
#   ./scripts/setup_native_deps.sh --shallow    # shallow clone (CI)
# ──────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
THIRD_PARTY_DIR="$PROJECT_ROOT/app/src/main/cpp/third_party"

SHALLOW=false
VERIFY_ONLY=false

for arg in "$@"; do
    case "$arg" in
        --shallow) SHALLOW=true ;;
        --verify) VERIFY_ONLY=true ;;
    esac
done

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log()  { echo -e "${BLUE}[native]${NC} $*"; }
ok()   { echo -e "${GREEN}[✓]${NC} $*"; }
warn() { echo -e "${YELLOW}[!]${NC} $*"; }
err()  { echo -e "${RED}[✗]${NC} $*" >&2; }

# ── Verify ───────────────────────────────────────────────────
verify() {
    local all_ok=true

    if [ -f "$THIRD_PARTY_DIR/llama.cpp/CMakeLists.txt" ] || [ -f "$THIRD_PARTY_DIR/llama.cpp/include/llama.h" ]; then
        ok "llama.cpp present"
    else
        err "llama.cpp missing"
        all_ok=false
    fi

    if [ -f "$THIRD_PARTY_DIR/sherpa-onnx/CMakeLists.txt" ] || [ -f "$THIRD_PARTY_DIR/sherpa-onnx/sherpa-onnx/c-api/c-api.h" ]; then
        ok "sherpa-onnx present"
    else
        err "sherpa-onnx missing"
        all_ok=false
    fi

    if $all_ok; then
        ok "All native dependencies present"
        return 0
    else
        err "Some native dependencies missing — run without --verify"
        return 1
    fi
}

if $VERIFY_ONLY; then
    verify
    exit $?
fi

# ── Clone llama.cpp ──────────────────────────────────────────
mkdir -p "$THIRD_PARTY_DIR"

if [ -f "$THIRD_PARTY_DIR/llama.cpp/CMakeLists.txt" ]; then
    ok "llama.cpp already present"
else
    log "Cloning llama.cpp..."
    CLONE_ARGS="--depth 1 --single-branch"
    if $SHALLOW; then
        CLONE_ARGS="--depth 1 --single-branch --no-tags"
    fi
    git clone $CLONE_ARGS https://github.com/ggerganov/llama.cpp.git \
        "$THIRD_PARTY_DIR/llama.cpp" 2>&1 || {
        err "Failed to clone llama.cpp"
        exit 1
    }
    ok "llama.cpp cloned"
fi

# ── Clone sherpa-onnx ────────────────────────────────────────
if [ -f "$THIRD_PARTY_DIR/sherpa-onnx/CMakeLists.txt" ]; then
    ok "sherpa-onnx already present"
else
    log "Cloning sherpa-onnx..."
    CLONE_ARGS="--depth 1 --single-branch"
    if $SHALLOW; then
        CLONE_ARGS="--depth 1 --single-branch --no-tags"
    fi
    git clone $CLONE_ARGS https://github.com/k2-fsa/sherpa-onnx.git \
        "$THIRD_PARTY_DIR/sherpa-onnx" 2>&1 || {
        err "Failed to clone sherpa-onnx"
        exit 1
    }
    ok "sherpa-onnx cloned"
fi

# ── Final verify ─────────────────────────────────────────────
verify
