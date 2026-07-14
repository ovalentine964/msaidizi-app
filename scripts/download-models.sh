#!/bin/bash
# ============================================================
# Download AI Models for Msaidizi APK Build
# ============================================================
# Downloads Whisper, Piper TTS, and Qwen models into assets/models/
# Called during CI/CD build or manual build before Gradle assemble.
#
# Usage:
#   ./scripts/download-models.sh          # Download all models
#   ./scripts/download-models.sh --verify # Verify only (skip download)
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
MODELS_DIR="$PROJECT_ROOT/app/src/main/assets/models"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

VERIFY_ONLY=false
if [ "$1" = "--verify" ]; then
    VERIFY_ONLY=true
fi

echo -e "${GREEN}🧠 Msaidizi Model Downloader${NC}"
echo "================================"
echo "Models dir: $MODELS_DIR"
echo ""

mkdir -p "$MODELS_DIR"

# ── Helper: download with retry ──
download() {
    local url="$1"
    local dest="$2"
    local name="$3"
    local expected_size="$4"

    if [ -f "$dest" ] && [ "$(stat -c%s "$dest" 2>/dev/null || stat -f%z "$dest" 2>/dev/null)" -gt 1000 ]; then
        echo -e "  ${GREEN}✓${NC} $name already exists ($(du -h "$dest" | cut -f1))"
        return 0
    fi

    if [ "$VERIFY_ONLY" = true ]; then
        echo -e "  ${RED}✗${NC} $name MISSING"
        return 1
    fi

    echo -e "  ${YELLOW}↓${NC} Downloading $name..."
    for attempt in 1 2 3; do
        if curl -L --progress-bar --fail -o "$dest" "$url"; then
            local size=$(stat -c%s "$dest" 2>/dev/null || stat -f%z "$dest" 2>/dev/null)
            if [ "$size" -gt 1000 ]; then
                echo -e "  ${GREEN}✓${NC} $name downloaded ($(echo "scale=0; $size/1048576" | bc)MB)"
                return 0
            fi
        fi
        echo -e "  ${YELLOW}↻${NC} Retry $attempt/3..."
        sleep 2
    done

    echo -e "  ${RED}✗${NC} Failed to download $name"
    return 1
}

# ── 1. Whisper tiny.en (Q5_1 quantized, whisper.cpp format) ──
echo "📋 Whisper Speech Recognition (ggml Q5_1):"
download \
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin" \
    "$MODELS_DIR/ggml-tiny.en-q5_1.bin" \
    "Whisper tiny.en (Q5_1)" \
    25000000

# ── 2. Piper Swahili TTS (ONNX) ──
echo ""
echo "📋 Piper Swahili TTS:"
download \
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/sw/sw_CD/lanfrica/medium/sw_CD-lanfrica-medium.onnx" \
    "$MODELS_DIR/piper-swahili.onnx" \
    "Piper Swahili TTS (ONNX)" \
    50000000

# Also download the Piper config JSON
download \
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/sw/sw_CD/lanfrica/medium/sw_CD-lanfrica-medium.onnx.json" \
    "$MODELS_DIR/piper-swahili.onnx.json" \
    "Piper Swahili config" \
    1000

# ── 3. Qwen3.5-0.8B Q4_K_M (GGUF) ──
echo ""
echo "📋 Qwen 3.5 0.8B LLM:"
# Try unsloth first, fallback to bartowski
download \
    "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf" \
    "$MODELS_DIR/qwen3.5-0.8b-q4_k_m.gguf" \
    "Qwen LLM (Q4_K_M)" \
    500000000

# Fallback: try alternative GGUF source if primary fails
if [ ! -f "$MODELS_DIR/qwen3.5-0.8b-q4_k_m.gguf" ] || [ "$(stat -c%s "$MODELS_DIR/qwen3.5-0.8b-q4_k_m.gguf" 2>/dev/null || echo 0)" -lt 100000 ]; then
    echo -e "  ${YELLOW}↻${NC} Trying Qwen official mirror..."
    download \
        "https://huggingface.co/Qwen/Qwen3.5-0.8B-GGUF/resolve/main/qwen3.5-0.8b-q4_k_m.gguf" \
        "$MODELS_DIR/qwen3.5-0.8b-q4_k_m.gguf" \
        "Qwen LLM (Q4_K_M, Qwen official)" \
        500000000
fi

# ── Summary ──
echo ""
echo "================================"
TOTAL=$(du -sh "$MODELS_DIR" 2>/dev/null | cut -f1)
echo -e "${GREEN}Total models size: $TOTAL${NC}"
echo ""

# List all model files
echo "Model files:"
ls -lh "$MODELS_DIR"/*.onnx "$MODELS_DIR"/*.gguf "$MODELS_DIR"/*.bin "$MODELS_DIR"/*.json 2>/dev/null | awk '{print "  "$NF" ("$5")"}'

echo ""
echo "Expected APK size breakdown:"
echo "  Whisper tiny.en (Q5_1 bin):     ~30MB"
echo "  Piper Swahili TTS (ONNX):       ~60MB"
echo "  Qwen 3.5 0.8B (Q4_K_M GGUF):   ~508MB"
echo "  App code + resources:            ~10MB"
echo "  ─────────────────────────────────"
echo "  Estimated total APK:             ~610MB"
echo ""
echo "Note: Models are stored uncompressed (noCompress) in assets/"
echo "      for mmap access at runtime. APK will be large but runtime"
echo "      memory usage is efficient via memory-mapped file I/O."
echo ""
if [ "$VERIFY_ONLY" = true ]; then
    echo -e "${GREEN}✅ Verification complete${NC}"
else
    echo -e "${GREEN}✅ Models ready for APK build${NC}"
fi
