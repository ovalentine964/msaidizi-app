#!/usr/bin/env bash
# ============================================================
# setup-sherpa-onnx.sh
# Downloads sherpa-onnx pre-built Android JNI libraries and
# places them in app/src/main/jniLibs/arm64-v8a/ for the
# Msaidizi APK build.
#
# Required libs:
#   - libsherpa-onnx-jni.so  (~4.5MB) — JNI bridge
#   - libonnxruntime.so      (~21MB)  — ONNX Runtime (bundled)
#
# Usage:
#   ./scripts/setup-sherpa-onnx.sh [--version v1.13.4]
#
# The script is idempotent — re-running it skips the download
# if the target files already exist (use --force to re-download).
# ============================================================
set -euo pipefail

# ── Configuration ────────────────────────────────────────────
SHERPA_VERSION="${SHERPA_VERSION:-v1.13.4}"
DOWNLOAD_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}-android.tar.bz2"

# Resolve project root (parent of scripts/ dir)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

JNILIBS_DIR="${PROJECT_ROOT}/app/src/main/jniLibs/arm64-v8a"
TMP_DIR="${PROJECT_ROOT}/.openclaw/tmp/sherpa-onnx-setup"

# Only these two are needed for JNI-based usage (per sherpa-onnx docs)
REQUIRED_LIBS=(
    "libsherpa-onnx-jni.so"
    "libonnxruntime.so"
)

# ── Argument parsing ─────────────────────────────────────────
FORCE=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --version)  SHERPA_VERSION="$2"; shift 2 ;;
        --force)    FORCE=true; shift ;;
        -h|--help)
            echo "Usage: $0 [--version vX.Y.Z] [--force]"
            echo ""
            echo "Options:"
            echo "  --version   sherpa-onnx release tag (default: v1.13.4)"
            echo "  --force     re-download even if libs exist"
            exit 0
            ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

# Update URL after arg parsing
DOWNLOAD_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}-android.tar.bz2"

# ── Helper functions ─────────────────────────────────────────
info()  { echo "ℹ️  $*"; }
ok()    { echo "✅ $*"; }
warn()  { echo "⚠️  $*"; }
fail()  { echo "❌ $*" >&2; exit 1; }

check_libs_exist() {
    for lib in "${REQUIRED_LIBS[@]}"; do
        [[ -f "${JNILIBS_DIR}/${lib}" ]] || return 1
    done
    return 0
}

# ── Main ─────────────────────────────────────────────────────
echo ""
echo "╔══════════════════════════════════════════════════════╗"
echo "║       Sherpa-ONNX JNI Library Setup for Msaidizi    ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""
info "Version:  ${SHERPA_VERSION}"
info "Target:   ${JNILIBS_DIR}"
echo ""

# Check if libs already exist
if check_libs_exist && [[ "$FORCE" != "true" ]]; then
    ok "JNI libraries already present. Use --force to re-download."
    echo ""
    ls -lh "${JNILIBS_DIR}"/lib*.so 2>/dev/null
    echo ""
    exit 0
fi

# Verify tools
for cmd in wget tar; do
    command -v "$cmd" >/dev/null 2>&1 || fail "'$cmd' is required but not installed."
done

# Create directories
mkdir -p "${JNILIBS_DIR}"
mkdir -p "${TMP_DIR}"

# Download
ARCHIVE="${TMP_DIR}/sherpa-onnx-${SHERPA_VERSION}-android.tar.bz2"
if [[ -f "${ARCHIVE}" ]] && [[ "$FORCE" != "true" ]]; then
    info "Using cached archive: ${ARCHIVE}"
else
    info "Downloading sherpa-onnx ${SHERPA_VERSION} Android pre-built libs..."
    info "URL: ${DOWNLOAD_URL}"
    if ! wget -q --show-progress -O "${ARCHIVE}" "${DOWNLOAD_URL}"; then
        fail "Download failed. Check your network connection and the release URL."
    fi
    ok "Download complete."
fi

# Extract only arm64-v8a libs
info "Extracting arm64-v8a libraries..."
tar xjf "${ARCHIVE}" -C "${TMP_DIR}" \
    --wildcards "*/arm64-v8a/*.so" 2>/dev/null \
    || tar xjf "${ARCHIVE}" -C "${TMP_DIR}" 2>/dev/null

# Find extracted arm64-v8a directory
EXTRACTED_DIR=""
for candidate in \
    "${TMP_DIR}/jniLibs/arm64-v8a" \
    "${TMP_DIR}/sherpa-onnx-${SHERPA_VERSION}-android/jniLibs/arm64-v8a" \
    "${TMP_DIR}/build-android-arm64-v8a/install/lib"; do
    if [[ -d "$candidate" ]]; then
        EXTRACTED_DIR="$candidate"
        break
    fi
done

# Fallback: search for it
if [[ -z "$EXTRACTED_DIR" ]]; then
    EXTRACTED_DIR=$(find "${TMP_DIR}" -type d -name "arm64-v8a" | head -1)
fi

[[ -n "$EXTRACTED_DIR" ]] || fail "Could not find arm64-v8a directory in extracted archive."
info "Found libs in: ${EXTRACTED_DIR}"

# Copy required libs to jniLibs
info "Copying libraries to ${JNILIBS_DIR}..."
for lib in "${REQUIRED_LIBS[@]}"; do
    src="${EXTRACTED_DIR}/${lib}"
    if [[ -f "$src" ]]; then
        cp -v "$src" "${JNILIBS_DIR}/"
        ok "Installed: ${lib}"
    else
        fail "Required library not found in archive: ${lib}"
    fi
done

# Also copy optional C/C++ API libs (harmless, may be useful)
for lib in libsherpa-onnx-c-api.so libsherpa-onnx-cxx-api.so; do
    src="${EXTRACTED_DIR}/${lib}"
    if [[ -f "$src" ]]; then
        cp -v "$src" "${JNILIBS_DIR}/"
        info "Optional: ${lib} (for non-JNI C/C++ usage)"
    fi
done

# Clean up extracted files (keep the cached archive)
rm -rf "${TMP_DIR}/jniLibs" 2>/dev/null
rm -rf "${TMP_DIR}/sherpa-onnx-"* 2>/dev/null
rm -rf "${TMP_DIR}/build-android-"* 2>/dev/null

# ── Verify ───────────────────────────────────────────────────
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
info "Verification — ${JNILIBS_DIR}:"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
ls -lh "${JNILIBS_DIR}"/lib*.so 2>/dev/null

ALL_OK=true
for lib in "${REQUIRED_LIBS[@]}"; do
    if [[ -f "${JNILIBS_DIR}/${lib}" ]]; then
        size=$(stat -c%s "${JNILIBS_DIR}/${lib}" 2>/dev/null || stat -f%z "${JNILIBS_DIR}/${lib}" 2>/dev/null)
        if [[ "$size" -gt 100000 ]]; then
            ok "${lib}  ($(numfmt --to=iec "$size" 2>/dev/null || echo "${size} bytes"))"
        else
            warn "${lib} exists but seems too small (${size} bytes) — may be corrupted."
            ALL_OK=false
        fi
    else
        fail "${lib} — MISSING"
        ALL_OK=false
    fi
done

echo ""
if [[ "$ALL_OK" == "true" ]]; then
    echo "╔══════════════════════════════════════════════════════╗"
    echo "║  ✅  Sherpa-ONNX JNI libraries installed!           ║"
    echo "║                                                     ║"
    echo "║  The APK build should now find:                     ║"
    echo "║    • libsherpa-onnx-jni.so  (JNI bridge)            ║"
    echo "║    • libonnxruntime.so      (ONNX Runtime)          ║"
    echo "║                                                     ║"
    echo "║  Next: ./gradlew assembleDebug                      ║"
    echo "╚══════════════════════════════════════════════════════╝"
else
    fail "Some libraries are missing or corrupted. Check the output above."
fi
echo ""
