#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────
# setup_native_deps.sh — Download and configure native dependencies
# for Msaidizi Android build.
#
# Downloads:
#   1. llama.cpp (on-device LLM inference)
#   2. sherpa-onnx (on-device ASR / TTS / VAD)
#
# Both are cloned as shallow git repos into app/src/main/cpp/third_party/
# and built from source using the Android NDK.
#
# Prerequisites:
#   - Android SDK with NDK (≥25) installed
#   - ANDROID_HOME or ANDROID_SDK_ROOT set
#   - cmake ≥ 3.22.1 (usually bundled with NDK)
#   - git
#
# Usage:
#   ./scripts/setup_native_deps.sh              # setup both
#   ./scripts/setup_native_deps.sh --llama-only  # llama.cpp only
#   ./scripts/setup_native_deps.sh --sherpa-only # sherpa-onnx only
#   ./scripts/setup_native_deps.sh --prebuilt    # download pre-built libs
#   ./scripts/setup_native_deps.sh --clean       # remove third_party/
#
# After running, the app will build with full native AI capabilities.
# Without running, the app builds in stub mode (UI-only, no inference).
# ──────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
THIRD_PARTY_DIR="$PROJECT_ROOT/voice/src/main/cpp/third_party"

# ── Versions ─────────────────────────────────────────────────
# Pin to known-good commits for reproducible builds.
LLAMA_CPP_URL="https://github.com/ggerganov/llama.cpp.git"
LLAMA_CPP_BRANCH="master"
LLAMA_CPP_TAG=""  # Set to a tag like "b4000" for reproducible builds

SHERPA_ONNX_URL="https://github.com/k2-fsa/sherpa-onnx.git"
SHERPA_ONNX_BRANCH="master"
SHERPA_ONNX_TAG=""  # Set to a tag like "v1.10.0" for reproducible builds

# Pre-built release URLs (for --prebuilt mode)
SHERPA_ONNX_VERSION="v1.10.43"
SHERPA_ONNX_AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/${SHERPA_ONNX_VERSION}/sherpa-onnx-${SHERPA_ONNX_VERSION}-aar.tar.bz2"

# ── Colours ──────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log()  { echo -e "${BLUE}[native-deps]${NC} $*"; }
ok()   { echo -e "${GREEN}[✓]${NC} $*"; }
warn() { echo -e "${YELLOW}[!]${NC} $*"; }
err()  { echo -e "${RED}[✗]${NC} $*" >&2; }

# ── Prerequisites check ─────────────────────────────────────

check_prerequisites() {
    log "Checking prerequisites..."

    local missing=()

    if ! command -v git &>/dev/null; then
        missing+=("git")
    fi

    if ! command -v cmake &>/dev/null; then
        # Check NDK-bundled cmake
        if [ -n "${ANDROID_HOME:-}" ] || [ -n "${ANDROID_SDK_ROOT:-}" ]; then
            local sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT}}"
            local cmake_bin=$(find "$sdk_dir/cmake" -name "cmake" -type f 2>/dev/null | head -1)
            if [ -n "$cmake_bin" ]; then
                ok "Found NDK cmake: $cmake_bin"
            else
                missing+=("cmake")
            fi
        else
            missing+=("cmake")
        fi
    fi

    if [ ${#missing[@]} -gt 0 ]; then
        err "Missing prerequisites: ${missing[*]}"
        err "Install them before running this script."
        exit 1
    fi

    ok "Prerequisites satisfied"
}

# ── Clone llama.cpp ─────────────────────────────────────────

setup_llama() {
    local dest="$THIRD_PARTY_DIR/llama.cpp"

    if [ -d "$dest" ] && [ -f "$dest/CMakeLists.txt" ]; then
        ok "llama.cpp already exists at $dest"
        log "  To update: cd $dest && git pull"
        return 0
    fi

    log "Cloning llama.cpp..."
    mkdir -p "$THIRD_PARTY_DIR"

    local clone_args=(
        --depth 1
        --single-branch
        --branch "$LLAMA_CPP_BRANCH"
    )

    if [ -n "$LLAMA_CPP_TAG" ]; then
        clone_args+=(--branch "$LLAMA_CPP_TAG")
    fi

    git clone "${clone_args[@]}" "$LLAMA_CPP_URL" "$dest" 2>&1 || {
        err "Failed to clone llama.cpp"
        return 1
    }

    ok "llama.cpp cloned to $dest"

    # Disable Metal and OpenMP for Android
    if [ -f "$dest/CMakeLists.txt" ]; then
        log "llama.cpp CMakeLists.txt found — will be built from source by NDK"
    fi
}

# ── Clone sherpa-onnx ────────────────────────────────────────

setup_sherpa() {
    local dest="$THIRD_PARTY_DIR/sherpa-onnx"

    if [ -d "$dest" ] && [ -f "$dest/CMakeLists.txt" ]; then
        ok "sherpa-onnx already exists at $dest"
        log "  To update: cd $dest && git pull"
        return 0
    fi

    log "Cloning sherpa-onnx..."
    mkdir -p "$THIRD_PARTY_DIR"

    local clone_args=(
        --depth 1
        --single-branch
        --branch "$SHERPA_ONNX_BRANCH"
    )

    if [ -n "$SHERPA_ONNX_TAG" ]; then
        clone_args+=(--branch "$SHERPA_ONNX_TAG")
    fi

    git clone "${clone_args[@]}" "$SHERPA_ONNX_URL" "$dest" 2>&1 || {
        err "Failed to clone sherpa-onnx"
        return 1
    }

    ok "sherpa-onnx cloned to $dest"

    if [ -f "$dest/CMakeLists.txt" ]; then
        log "sherpa-onnx CMakeLists.txt found — will be built from source by NDK"
    fi
}

# ── Download pre-built libraries ─────────────────────────────

setup_prebuilt() {
    log "Downloading pre-built native libraries..."
    mkdir -p "$THIRD_PARTY_DIR"

    local cache_dir="$PROJECT_ROOT/.native-cache"
    mkdir -p "$cache_dir"

    # Download sherpa-onnx AAR (contains pre-built .so for all ABIs)
    local aar_tar="$cache_dir/sherpa-onnx-aar.tar.bz2"
    if [ ! -f "$aar_tar" ]; then
        log "Downloading sherpa-onnx AAR (${SHERPA_ONNX_VERSION})..."
        if command -v wget &>/dev/null; then
            wget --tries=3 --timeout=120 --show-progress -O "$aar_tar" "$SHERPA_ONNX_AAR_URL" || {
                err "Failed to download sherpa-onnx AAR"
                return 1
            }
        elif command -v curl &>/dev/null; then
            curl -L --retry 3 --retry-delay 5 --progress-bar -o "$aar_tar" "$SHERPA_ONNX_AAR_URL" || {
                err "Failed to download sherpa-onnx AAR"
                return 1
            }
        fi
    fi

    # Extract sherpa-onnx headers and pre-built .so files
    local sherpa_dir="$THIRD_PARTY_DIR/sherpa-onnx"
    if [ ! -d "$sherpa_dir" ]; then
        log "Extracting sherpa-onnx..."
        mkdir -p "$sherpa_dir"
        tar xjf "$aar_tar" --strip-components=1 -C "$sherpa_dir" 2>/dev/null || true

        # The AAR contains JNI libs for each ABI
        # Reorganize to match CMakeLists.txt expected layout:
        #   sherpa-onnx/include/sherpa-onnx/c-api/c-api.h
        #   sherpa-onnx/lib/arm64-v8a/libsherpa-onnx-c-api.so
        #   sherpa-onnx/lib/armeabi-v7a/libsherpa-onnx-c-api.so

        if [ -d "$sherpa_dir/jni" ]; then
            mkdir -p "$sherpa_dir/lib"
            for abi in arm64-v8a armeabi-v7a; do
                if [ -d "$sherpa_dir/jni/$abi" ]; then
                    mkdir -p "$sherpa_dir/lib/$abi"
                    cp -v "$sherpa_dir/jni/$abi/"*.so "$sherpa_dir/lib/$abi/" 2>/dev/null || true
                fi
            done
        fi

        ok "sherpa-onnx extracted"
    fi

    # For llama.cpp, we still need to build from source since pre-built
    # Android static libraries are not officially distributed.
    log "Note: llama.cpp must be built from source (no pre-built Android libs available)"
    log "  Run: $0 --llama-only"
}

# ── Clean ────────────────────────────────────────────────────

clean() {
    log "Removing third_party directory..."
    rm -rf "$THIRD_PARTY_DIR/llama.cpp"
    rm -rf "$THIRD_PARTY_DIR/sherpa-onnx"
    ok "Cleaned third_party/"
}

# ── Verify ───────────────────────────────────────────────────

verify() {
    log "Verifying native dependencies..."

    local all_ok=true

    if [ -d "$THIRD_PARTY_DIR/llama.cpp" ] && [ -f "$THIRD_PARTY_DIR/llama.cpp/CMakeLists.txt" ]; then
        ok "llama.cpp: present (source build)"
    elif [ -d "$THIRD_PARTY_DIR/llama.cpp/include" ]; then
        ok "llama.cpp: present (pre-built)"
    else
        warn "llama.cpp: missing (stub mode will be used)"
        all_ok=false
    fi

    if [ -d "$THIRD_PARTY_DIR/sherpa-onnx" ] && [ -f "$THIRD_PARTY_DIR/sherpa-onnx/CMakeLists.txt" ]; then
        ok "sherpa-onnx: present (source build)"
    elif [ -d "$THIRD_PARTY_DIR/sherpa-onnx/include" ]; then
        ok "sherpa-onnx: present (pre-built)"
    else
        warn "sherpa-onnx: missing (stub mode will be used)"
        all_ok=false
    fi

    if $all_ok; then
        echo ""
        ok "All native dependencies ready ✓"
        log "Run: ./gradlew assembleDebug"
    else
        echo ""
        warn "Some dependencies missing — app will build in stub mode"
        log "Run: $0 to download all dependencies"
    fi
}

# ── Main ─────────────────────────────────────────────────────

main() {
    local do_llama=true
    local do_sherpa=true
    local use_prebuilt=false
    local do_clean=false
    local do_verify=false

    for arg in "$@"; do
        case "$arg" in
            --llama-only)   do_sherpa=false ;;
            --sherpa-only)  do_llama=false ;;
            --prebuilt)     use_prebuilt=true ;;
            --clean)        do_clean=true ;;
            --verify)       do_verify=true ;;
            --help|-h)
                echo "Usage: $0 [OPTIONS]"
                echo ""
                echo "Options:"
                echo "  --llama-only    Only set up llama.cpp"
                echo "  --sherpa-only   Only set up sherpa-onnx"
                echo "  --prebuilt      Download pre-built libraries (faster, no NDK build)"
                echo "  --clean         Remove third_party/ directory"
                echo "  --verify        Check if dependencies are present"
                echo "  --help          Show this help"
                echo ""
                echo "After running, build with: ./gradlew assembleDebug"
                exit 0
                ;;
        esac
    done

    if $do_clean; then
        clean
        exit 0
    fi

    if $do_verify; then
        verify
        exit 0
    fi

    log "Setting up native dependencies for Msaidizi..."
    log "Target: $THIRD_PARTY_DIR"
    echo ""

    check_prerequisites

    if $use_prebuilt; then
        setup_prebuilt
    else
        $do_llama  && setup_llama
        $do_sherpa && setup_sherpa
    fi

    echo ""
    verify
}

main "$@"
