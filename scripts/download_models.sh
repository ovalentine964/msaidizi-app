#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────
# download_models.sh — Download AI models for Msaidizi APK bundling.
#
# Downloads:
#   1. Qwen3 0.6B GGUF (Q4_K_M quantised) — on-device LLM
#   2. sherpa-onnx Whisper Tiny (multilingual STT)
#   3. Piper Swahili TTS
#
# Models are placed into app/src/main/assets/models/ for APK bundling.
#
# Usage:
#   ./scripts/download_models.sh              # download all
#   ./scripts/download_models.sh --stt-only   # download STT only
#   ./scripts/download_models.sh --tts-only   # download TTS only
#   ./scripts/download_models.sh --llm-only   # download LLM only
#   ./scripts/download_models.sh --verify     # verify checksums only
# ──────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="$PROJECT_ROOT/app/src/main/assets/models"
CACHE_DIR="${MODEL_CACHE_DIR:-$PROJECT_ROOT/.model-cache}"

# ── URLs ─────────────────────────────────────────────────────
QWEN_URL="https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf"
WHISPER_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2"
PIPER_SW_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/piper-swahili.tar.bz2"

# ── Expected checksums (SHA-256) — update after first download ──
# These are placeholders; the script will compute and display checksums
# on first run. Fill in after verifying downloads.
declare -A EXPECTED_SHA256=(
    # ["Qwen3-0.6B-Q4_K_M.gguf"]="sha256hash"
    # ["sherpa-onnx-whisper-tiny.tar.bz2"]="sha256hash"
    # ["piper-swahili.tar.bz2"]="sha256hash"
)

# ── Colours ──────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log()  { echo -e "${BLUE}[models]${NC} $*"; }
ok()   { echo -e "${GREEN}[✓]${NC} $*"; }
warn() { echo -e "${YELLOW}[!]${NC} $*"; }
err()  { echo -e "${RED}[✗]${NC} $*" >&2; }

# ── Helpers ──────────────────────────────────────────────────

download() {
    local url="$1"
    local dest="$2"
    local name
    name="$(basename "$dest")"

    if [ -f "$dest" ]; then
        ok "Already cached: $name ($(du -h "$dest" | cut -f1))"
        return 0
    fi

    log "Downloading: $name"
    log "  URL: $url"
    mkdir -p "$(dirname "$dest")"

    # Use wget with retry, or curl as fallback
    if command -v wget &>/dev/null; then
        wget --tries=3 --timeout=60 --continue \
             --show-progress -O "$dest" "$url" 2>&1 || {
            rm -f "$dest"
            err "Download failed: $name"
            return 1
        }
    elif command -v curl &>/dev/null; then
        curl -L --retry 3 --retry-delay 5 \
             --progress-bar -C - -o "$dest" "$url" || {
            rm -f "$dest"
            err "Download failed: $name"
            return 1
        }
    else
        err "Neither wget nor curl found. Install one."
        return 1
    fi

    ok "Downloaded: $name ($(du -h "$dest" | cut -f1))"
}

verify_checksum() {
    local file="$1"
    local name
    name="$(basename "$file")"

    if [ -z "${EXPECTED_SHA256[$name]:-}" ]; then
        local actual
        actual="$(sha256sum "$file" | cut -d' ' -f1)"
        log "SHA-256 ($name): $actual"
        log "  (no expected checksum configured — add to EXPECTED_SHA256 map)"
        return 0
    fi

    local actual
    actual="$(sha256sum "$file" | cut -d' ' -f1)"
    if [ "$actual" = "${EXPECTED_SHA256[$name]}" ]; then
        ok "Checksum verified: $name"
    else
        err "Checksum mismatch for $name!"
        err "  Expected: ${EXPECTED_SHA256[$name]}"
        err "  Got:      $actual"
        return 1
    fi
}

extract_tar() {
    local archive="$1"
    local dest="$2"
    log "Extracting $(basename "$archive") → $dest"
    mkdir -p "$dest"
    tar xjf "$archive" --strip-components=1 -C "$dest"
    ok "Extracted to $dest"
}

# ── Download LLM model ──────────────────────────────────────

download_llm() {
    log "═══ LLM: Qwen3 0.6B (GGUF Q4_K_M) ═══"
    local dest_dir="$ASSETS_DIR/gguf"
    local cache_file="$CACHE_DIR/Qwen3-0.6B-Q4_K_M.gguf"

    download "$QWEN_URL" "$cache_file"
    verify_checksum "$cache_file"

    mkdir -p "$dest_dir"
    cp -v "$cache_file" "$dest_dir/Qwen3-0.6B-Q4_K_M.gguf"
    ok "LLM model placed in assets"
}

# ── Download STT model ──────────────────────────────────────

download_stt() {
    log "═══ STT: sherpa-onnx Whisper Tiny (multilingual) ═══"
    local cache_file="$CACHE_DIR/sherpa-onnx-whisper-tiny.tar.bz2"
    local extract_dir="$CACHE_DIR/whisper-tiny-extracted"
    local dest_dir="$ASSETS_DIR/onnx-whisper"

    download "$WHISPER_URL" "$cache_file"
    verify_checksum "$cache_file"

    # Extract
    extract_tar "$cache_file" "$extract_dir"

    # The archive contains a directory like sherpa-onnx-whisper-tiny/
    # with model files inside. We need to find the actual model files.
    local model_root
    model_root="$(find "$extract_dir" -name "encoder.onnx" -o -name "model.onnx" | head -1 | xargs dirname 2>/dev/null || echo "$extract_dir")"

    mkdir -p "$dest_dir"

    # Copy the expected files
    if [ -f "$model_root/encoder.onnx" ]; then
        cp -v "$model_root/encoder.onnx" "$dest_dir/"
        cp -v "$model_root/decoder.onnx" "$dest_dir/" 2>/dev/null || warn "No decoder.onnx found"
    fi
    # Also handle single-model layout (e.g., Paraformer)
    if [ -f "$model_root/model.onnx" ] && [ ! -f "$dest_dir/encoder.onnx" ]; then
        cp -v "$model_root/model.onnx" "$dest_dir/"
    fi
    # Tokens
    find "$model_root" -name "tokens.txt" -exec cp -v {} "$dest_dir/" \; 2>/dev/null || true

    # Verify we have the minimum required files
    if [ ! -f "$dest_dir/tokens.txt" ]; then
        err "tokens.txt not found in STT model archive!"
        # Try broader search
        find "$extract_dir" -name "tokens.txt" -exec cp -v {} "$dest_dir/" \;
    fi

    ok "STT model placed in assets"
    log "Files:"
    ls -lh "$dest_dir/"
}

# ── Download TTS model ──────────────────────────────────────

download_tts() {
    log "═══ TTS: Piper Swahili ═══"
    local cache_file="$CACHE_DIR/piper-swahili.tar.bz2"
    local extract_dir="$CACHE_DIR/piper-sw-extracted"
    local dest_dir="$ASSETS_DIR/onnx-piper/piper-sw"

    download "$PIPER_SW_URL" "$cache_file"
    verify_checksum "$cache_file"

    extract_tar "$cache_file" "$extract_dir"

    # Find the model root (contains model.onnx or *.onnx)
    local model_root
    model_root="$(find "$extract_dir" -name "*.onnx" | head -1 | xargs dirname 2>/dev/null || echo "$extract_dir")"

    mkdir -p "$dest_dir"

    # Copy model files
    find "$model_root" -name "*.onnx" -exec cp -v {} "$dest_dir/model.onnx" \; 2>/dev/null || true
    find "$model_root" -name "tokens.txt" -exec cp -v {} "$dest_dir/" \; 2>/dev/null || true
    find "$model_root" -name "tokens.json" -exec cp -v {} "$dest_dir/" \; 2>/dev/null || true

    # Copy espeak-ng-data directory if present
    if [ -d "$model_root/espeak-ng-data" ]; then
        cp -rv "$model_root/espeak-ng-data" "$dest_dir/"
    elif [ -d "$extract_dir/espeak-ng-data" ]; then
        cp -rv "$extract_dir/espeak-ng-data" "$dest_dir/"
    fi

    # Fallback: if no .onnx found, look for .pt or other formats
    if [ ! -f "$dest_dir/model.onnx" ]; then
        warn "No .onnx model found. Looking for alternatives..."
        find "$extract_dir" -type f \( -name "*.onnx" -o -name "*.pt" \) -ls
    fi

    ok "TTS model placed in assets"
    log "Files:"
    ls -lhR "$dest_dir/" 2>/dev/null || ls -lh "$dest_dir/"
}

# ── Verify existing assets ──────────────────────────────────

verify_assets() {
    log "═══ Verifying model assets ═══"
    local all_ok=true

    # LLM
    local gguf="$ASSETS_DIR/gguf/Qwen3-0.6B-Q4_K_M.gguf"
    if [ -f "$gguf" ]; then
        local size
        size="$(du -h "$gguf" | cut -f1)"
        ok "LLM: Qwen3-0.6B-Q4_K_M.gguf ($size)"
    else
        err "LLM model missing: $gguf"
        all_ok=false
    fi

    # STT
    if [ -f "$ASSETS_DIR/onnx-whisper/encoder.onnx" ] || [ -f "$ASSETS_DIR/onnx-whisper/model.onnx" ]; then
        ok "STT: Whisper model present"
        [ -f "$ASSETS_DIR/onnx-whisper/tokens.txt" ] && ok "  tokens.txt present" || { warn "  tokens.txt missing"; all_ok=false; }
    else
        err "STT model missing"
        all_ok=false
    fi

    # TTS
    if [ -f "$ASSETS_DIR/onnx-piper/piper-sw/model.onnx" ]; then
        ok "TTS: Piper Swahili model present"
    else
        err "TTS model missing: $ASSETS_DIR/onnx-piper/piper-sw/model.onnx"
        all_ok=false
    fi

    if $all_ok; then
        echo ""
        ok "All model assets verified ✓"
    else
        echo ""
        err "Some model assets are missing. Run without --verify to download."
        return 1
    fi
}

# ── Main ─────────────────────────────────────────────────────

main() {
    local do_llm=true
    local do_stt=true
    local do_tts=true
    local verify_only=false

    for arg in "$@"; do
        case "$arg" in
            --llm-only)  do_stt=false; do_tts=false ;;
            --stt-only)  do_llm=false; do_tts=false ;;
            --tts-only)  do_llm=false; do_stt=false ;;
            --verify)    verify_only=true ;;
            --help|-h)
                echo "Usage: $0 [--llm-only|--stt-only|--tts-only|--verify]"
                exit 0
                ;;
        esac
    done

    mkdir -p "$CACHE_DIR" "$ASSETS_DIR"

    if $verify_only; then
        verify_assets
        exit $?
    fi

    log "Model download starting..."
    log "Cache: $CACHE_DIR"
    log "Assets: $ASSETS_DIR"
    echo ""

    $do_llm  && download_llm
    $do_stt  && download_stt
    $do_tts  && download_tts

    echo ""
    log "═══ Summary ═══"
    verify_assets
}

main "$@"
