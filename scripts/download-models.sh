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

# ── 1. Whisper Encoder (INT8 quantized, Optimum format) ──
echo "📋 Whisper Speech Recognition (encoder + decoder):"
download \
    "https://huggingface.co/Xenova/whisper-tiny.en/resolve/main/onnx/encoder_model_quantized.onnx" \
    "$MODELS_DIR/whisper-encoder-int8.onnx" \
    "Whisper encoder (INT8)" \
    9000000

# ── 2. Whisper Decoder (merged, INT8 quantized, Optimum format) ──
download \
    "https://huggingface.co/Xenova/whisper-tiny.en/resolve/main/onnx/decoder_model_merged_quantized.onnx" \
    "$MODELS_DIR/whisper-decoder-int8.onnx" \
    "Whisper decoder merged (INT8)" \
    29000000

# ── 3. Whisper Tokenizer (JSON format) ──
download \
    "https://huggingface.co/Xenova/whisper-tiny.en/resolve/main/tokenizer.json" \
    "$MODELS_DIR/whisper-tokens.json" \
    "Whisper tokenizer" \
    1000000

# ── 4. Piper Swahili TTS ──
echo ""
echo "📋 Piper Swahili TTS:"
PIPER_ARCHIVE="$MODELS_DIR/piper-swahili.tar.bz2"
if [ -f "$MODELS_DIR/piper-swahili.onnx" ] && [ -s "$MODELS_DIR/piper-swahili.onnx" ]; then
    echo -e "  ${GREEN}✓${NC} Piper model already extracted"
elif [ "$VERIFY_ONLY" = true ]; then
    echo -e "  ${RED}✗${NC} Piper model MISSING"
else
    echo -e "  ${YELLOW}↓${NC} Downloading Piper Swahili TTS archive..."
    for attempt in 1 2 3; do
        if curl -L --progress-bar --fail -o "$PIPER_ARCHIVE" \
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-sw_CD-lanfrica-medium.tar.bz2"; then
            echo -e "  ${YELLOW}📦${NC} Extracting Piper archive..."
            tar xjf "$PIPER_ARCHIVE" -C "$MODELS_DIR" 2>/dev/null

            # Copy files to expected locations
            PIPER_SRC=$(find "$MODELS_DIR" -maxdepth 2 -name "*.onnx" -path "*/piper*" -o -name "*.onnx" -path "*/sw_*" 2>/dev/null | head -1)
            if [ -n "$PIPER_SRC" ]; then
                PIPER_DIR=$(dirname "$PIPER_SRC")
                cp "$PIPER_SRC" "$MODELS_DIR/piper-swahili.onnx" 2>/dev/null || true
                cp "$PIPER_DIR/tokens.txt" "$MODELS_DIR/piper-tokens.txt" 2>/dev/null || true
                cp -r "$PIPER_DIR/espeak-ng-data" "$MODELS_DIR/" 2>/dev/null || true
                rm -rf "$MODELS_DIR/vits-piper-"* "$PIPER_ARCHIVE"
                echo -e "  ${GREEN}✓${NC} Piper TTS extracted"
                break
            fi
        fi
        echo -e "  ${YELLOW}↻${NC} Retry $attempt/3..."
        sleep 2
    done
fi

# ── 5. Qwen3.5-0.8B Q4_K_M ──
echo ""
echo "📋 Qwen 3.5 0.8B LLM:"
download \
    "https://huggingface.co/bartowski/Qwen_Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf" \
    "$MODELS_DIR/qwen-0.5b-q4_k_m.gguf" \
    "Qwen LLM (Q4_K_M)" \
    500000000

# ── Summary ──
echo ""
echo "================================"
TOTAL=$(du -sh "$MODELS_DIR" 2>/dev/null | cut -f1)
echo -e "${GREEN}Total models size: $TOTAL${NC}"
echo ""

# List all model files
echo "Model files:"
ls -lh "$MODELS_DIR"/*.onnx "$MODELS_DIR"/*.gguf "$MODELS_DIR"/*.json "$MODELS_DIR"/*.txt 2>/dev/null | awk '{print "  "$NF" ("$5")"}'

echo ""
if [ "$VERIFY_ONLY" = true ]; then
    echo -e "${GREEN}✅ Verification complete${NC}"
else
    echo -e "${GREEN}✅ Models ready for APK build${NC}"
fi
