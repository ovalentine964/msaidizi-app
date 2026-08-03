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
# Update these periodically and verify builds still pass.
LLAMA_CPP_URL="https://github.com/ggerganov/llama.cpp.git"
LLAMA_CPP_COMMIT="f5919bf458ef190468b5c329bb293f8a54a1e69c"  # 2026-08-02

SHERPA_ONNX_URL="https://github.com/k2-fsa/sherpa-onnx.git"
SHERPA_ONNX_COMMIT="116a44e72c5bb631dcdbdb9c176f0304f5fc6fb0"  # 2026-08-02, v1.13.4

# Pre-built release URLs (for --prebuilt mode)
SHERPA_ONNX_VERSION="v1.13.4"
SHERPA_ONNX_AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/${SHERPA_ONNX_VERSION}/sherpa-onnx-${SHERPA_ONNX_VERSION#v}.aar"
SHERPA_ONNX_HEADER_URL="https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/${SHERPA_ONNX_COMMIT}/sherpa-onnx/c-api/c-api.h"

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

    log "Cloning llama.cpp (commit: ${LLAMA_CPP_COMMIT:0:12})..."
    mkdir -p "$THIRD_PARTY_DIR"

    git clone --depth 1 "$LLAMA_CPP_URL" "$dest" 2>&1 || {
        err "Failed to clone llama.cpp"
        return 1
    }

    (
        cd "$dest"
        git fetch --depth 1 origin "$LLAMA_CPP_COMMIT" 2>&1
        git checkout "$LLAMA_CPP_COMMIT" 2>&1
    ) || {
        err "Failed to checkout llama.cpp at $LLAMA_CPP_COMMIT"
        return 1
    }

    ok "llama.cpp cloned to $dest (pinned to ${LLAMA_CPP_COMMIT:0:12})"

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

    log "Cloning sherpa-onnx (commit: ${SHERPA_ONNX_COMMIT:0:12})..."
    mkdir -p "$THIRD_PARTY_DIR"

    git clone --depth 1 "$SHERPA_ONNX_URL" "$dest" 2>&1 || {
        err "Failed to clone sherpa-onnx"
        return 1
    }

    (
        cd "$dest"
        git fetch --depth 1 origin "$SHERPA_ONNX_COMMIT" 2>&1
        git checkout "$SHERPA_ONNX_COMMIT" 2>&1
    ) || {
        err "Failed to checkout sherpa-onnx at $SHERPA_ONNX_COMMIT"
        return 1
    }

    ok "sherpa-onnx cloned to $dest (pinned to ${SHERPA_ONNX_COMMIT:0:12})"

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
    local aar_file="$cache_dir/sherpa-onnx.aar"
    if [ ! -f "$aar_file" ]; then
        log "Downloading sherpa-onnx AAR (${SHERPA_ONNX_VERSION})..."
        if command -v wget &>/dev/null; then
            wget --tries=3 --timeout=120 --show-progress -O "$aar_file" "$SHERPA_ONNX_AAR_URL" || {
                err "Failed to download sherpa-onnx AAR"
                rm -f "$aar_file"
                return 1
            }
        elif command -v curl &>/dev/null; then
            curl -L --retry 3 --retry-delay 5 --progress-bar -o "$aar_file" "$SHERPA_ONNX_AAR_URL" || {
                err "Failed to download sherpa-onnx AAR"
                rm -f "$aar_file"
                return 1
            }
        else
            err "Neither wget nor curl available — cannot download AAR"
            return 1
        fi
    fi

    # Validate AAR file (must be a valid ZIP > 1MB)
    local aar_size
    aar_size=$(stat -c%s "$aar_file" 2>/dev/null || stat -f%z "$aar_file" 2>/dev/null || echo 0)
    if [ "$aar_size" -lt 1000000 ]; then
        err "AAR file is too small (${aar_size} bytes) — likely a failed download"
        rm -f "$aar_file"
        return 1
    fi

    # Extract sherpa-onnx AAR (it's a ZIP file)
    local sherpa_dir="$THIRD_PARTY_DIR/sherpa-onnx"
    if [ ! -d "$sherpa_dir" ]; then
        log "Extracting sherpa-onnx AAR..."
        mkdir -p "$sherpa_dir"
        # AAR is a ZIP file
        unzip -q "$aar_file" -d "$sherpa_dir/aar-extracted" || {
            err "Failed to extract AAR — file may be corrupt"
            rm -rf "$sherpa_dir"
            rm -f "$aar_file"
            return 1
        }

        # AAR contains jni/ with pre-built .so files for each ABI
        if [ -d "$sherpa_dir/aar-extracted/jni" ]; then
            mkdir -p "$sherpa_dir/lib"
            for abi in arm64-v8a armeabi-v7a; do
                if [ -d "$sherpa_dir/aar-extracted/jni/$abi" ]; then
                    mkdir -p "$sherpa_dir/lib/$abi"
                    cp -v "$sherpa_dir/aar-extracted/jni/$abi/"*.so "$sherpa_dir/lib/$abi/" 2>/dev/null || true
                fi
            done
        fi

        # Cleanup extracted AAR
        rm -rf "$sherpa_dir/aar-extracted"

        # Verify critical .so was extracted
        if [ ! -f "$sherpa_dir/lib/arm64-v8a/libsherpa-onnx-c-api.so" ]; then
            err "AAR did not contain libsherpa-onnx-c-api.so for arm64-v8a"
            rm -rf "$sherpa_dir"
            return 1
        fi

        ok "sherpa-onnx libraries extracted from AAR"
    fi

    # Always ensure the C API header is present and valid.
    # This handles both fresh installs and cache restores that have
    # the .so files but lost the header.
    local include_dir="$sherpa_dir/include/sherpa-onnx/c-api"
    local header_file="$include_dir/c-api.h"
    local need_header=false

    if [ ! -f "$header_file" ]; then
        need_header=true
    else
        # Validate header content (must start with C/C++, not HTML error page)
        local first_bytes
        first_bytes=$(head -c 20 "$header_file" 2>/dev/null || true)
        if ! echo "$first_bytes" | grep -qE '^(/\*|//|#)'; then
            warn "Header file is corrupted (not a valid C header) — re-downloading"
            rm -f "$header_file"
            need_header=true
        fi
    fi

    if $need_header; then
        mkdir -p "$include_dir"
        log "Downloading sherpa-onnx C API header..."
        if command -v curl &>/dev/null; then
            curl -sL "$SHERPA_ONNX_HEADER_URL" -o "$header_file" || {
                err "Failed to download C API header"
                rm -f "$header_file"
                return 1
            }
        elif command -v wget &>/dev/null; then
            wget -q -O "$header_file" "$SHERPA_ONNX_HEADER_URL" || {
                err "Failed to download C API header"
                rm -f "$header_file"
                return 1
            }
        fi

        # Validate downloaded header is a real C header, not an HTML error page
        if [ -f "$header_file" ]; then
            local first_bytes
            first_bytes=$(head -c 20 "$header_file" 2>/dev/null || true)
            if ! echo "$first_bytes" | grep -qE '^(/\*|//|#)'; then
                err "Downloaded header is not a valid C header (got HTML error page?)"
                rm -f "$header_file"
                rm -rf "$sherpa_dir/include"
                return 1
            fi
            ok "sherpa-onnx C API header downloaded"
        else
            err "Header file was not created"
            return 1
        fi
    fi

    # Final verification
    if [ -f "$sherpa_dir/lib/arm64-v8a/libsherpa-onnx-c-api.so" ] && \
       [ -f "$sherpa_dir/include/sherpa-onnx/c-api/c-api.h" ]; then
        ok "sherpa-onnx pre-built setup complete"
    else
        err "sherpa-onnx pre-built setup incomplete"
        return 1
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
