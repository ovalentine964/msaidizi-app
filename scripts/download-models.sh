#!/bin/bash
# ============================================================
# Download AI Models for Msaidizi APK Build
# ============================================================
# Downloads Whisper (ONNX), Piper TTS (sherpa-onnx), Silero VAD,
# and Qwen 3.5 0.8B (GGUF) into assets/models/
#
# Called during CI/CD build or manual build before Gradle assemble.
# All models are bundled in the APK for offline-first operation.
#
# IMPORTANT: Model filenames MUST match BundledModelManager.kt BUNDLED_ASSETS list.
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
        VERIFY_FAILED=1
        return 1
    fi

    echo -e "  ${YELLOW}↓${NC} Downloading $name..."
    for attempt in 1 2 3; do
        if curl -L --progress-bar --fail -o "$dest" "$url"; then
            local size
            size=$(stat -c%s "$dest" 2>/dev/null || stat -f%z "$dest" 2>/dev/null)
            if [ "$size" -gt 1000 ]; then
                echo -e "  ${GREEN}✓${NC} $name downloaded ($((size / 1048576))MB)"
                return 0
            fi
        fi
        echo -e "  ${YELLOW}↻${NC} Retry $attempt/3..."
        sleep 2
    done

    echo -e "  ${RED}✗${NC} Failed to download $name"
    DOWNLOAD_FAILED=1
    return 1
}

VERIFY_FAILED=0
DOWNLOAD_FAILED=0

# ══════════════════════════════════════════════════════════════
# 1. Whisper Tiny — ONNX format (for sherpa-onnx ASR engine)
# ══════════════════════════════════════════════════════════════
# Source: Xenova/whisper-tiny ONNX export on HuggingFace
# Files: encoder (10MB) + decoder (30MB) + tokenizer (2.5MB)
# These filenames MUST match BundledModelManager.kt BUNDLED_ASSETS
echo "📋 Whisper Speech Recognition (ONNX, sherpa-onnx format):"
download \
    "https://huggingface.co/Xenova/whisper-tiny/resolve/main/onnx/encoder_model_quantized.onnx" \
    "$MODELS_DIR/whisper-encoder-int8.onnx" \
    "Whisper encoder (ONNX INT8)" \
    5000000

download \
    "https://huggingface.co/Xenova/whisper-tiny/resolve/main/onnx/decoder_model_merged_quantized.onnx" \
    "$MODELS_DIR/whisper-decoder-int8.onnx" \
    "Whisper decoder (ONNX INT8)" \
    20000000

download \
    "https://huggingface.co/Xenova/whisper-tiny/resolve/main/tokenizer.json" \
    "$MODELS_DIR/whisper-tokens.json" \
    "Whisper tokenizer" \
    1000000

# ══════════════════════════════════════════════════════════════
# 2. Silero VAD — Voice Activity Detection (for sherpa-onnx)
# ══════════════════════════════════════════════════════════════
echo ""
echo "📋 Silero VAD (Voice Activity Detection):"
download \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx" \
    "$MODELS_DIR/silero_vad.onnx" \
    "Silero VAD (ONNX)" \
    500000

# ══════════════════════════════════════════════════════════════
# 3. Piper Swahili TTS — from sherpa-onnx release (includes espeak-ng-data)
# ══════════════════════════════════════════════════════════════
# Source: k2-fsa/sherpa-onnx GitHub releases (matches ModelRegistry.kt URL)
# The tar.bz2 contains: model.onnx, tokens.txt, espeak-ng-data/
echo ""
echo "📋 Piper Swahili TTS (sherpa-onnx release):"
PIPER_ARCHIVE="$MODELS_DIR/piper-swahili.tar.bz2"
PIPER_MODEL="$MODELS_DIR/piper-swahili.onnx"
PIPER_TOKENS="$MODELS_DIR/tokens.txt"

if [ -f "$PIPER_MODEL" ] && [ "$(stat -c%s "$PIPER_MODEL" 2>/dev/null || stat -f%z "$PIPER_MODEL" 2>/dev/null)" -gt 1000 ]; then
    echo -e "  ${GREEN}✓${NC} Piper Swahili TTS already exists ($(du -h "$PIPER_MODEL" | cut -f1))"
else
    if [ "$VERIFY_ONLY" = true ]; then
        echo -e "  ${RED}✗${NC} Piper Swahili TTS MISSING"
        VERIFY_FAILED=1
    else
        echo -e "  ${YELLOW}↓${NC} Downloading Piper Swahili TTS (tar.bz2 archive)..."
        for attempt in 1 2 3; do
            if curl -L --progress-bar --fail -o "$PIPER_ARCHIVE" \
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-sw_CD-lanfrica-medium.tar.bz2"; then
                # Extract: archive contains a directory with model.onnx, tokens.txt, espeak-ng-data/
                echo -e "  ${YELLOW}↓${NC} Extracting Piper archive..."
                tar xjf "$PIPER_ARCHIVE" -C "$MODELS_DIR" 2>/dev/null

                # Find and rename extracted files to match expected names
                # Archive structure: vits-piper-sw_CD-lanfrica-medium/sw_CD-lanfrica-medium.onnx
                EXTRACTED_DIR=$(find "$MODELS_DIR" -maxdepth 2 -name "sw_CD-lanfrica-medium.onnx" -exec dirname {} \; | head -1)
                if [ -z "$EXTRACTED_DIR" ]; then
                    # Try broader search for any .onnx file in extracted dir
                    EXTRACTED_DIR=$(find "$MODELS_DIR" -maxdepth 3 -name "*.onnx" -not -name "whisper-*" -not -name "silero_*" -exec dirname {} \; | head -1)
                fi

                if [ -n "$EXTRACTED_DIR" ]; then
                    # Rename sw_CD-lanfrica-medium.onnx → piper-swahili.onnx
                    if [ -f "$EXTRACTED_DIR/sw_CD-lanfrica-medium.onnx" ]; then
                        mv "$EXTRACTED_DIR/sw_CD-lanfrica-medium.onnx" "$MODELS_DIR/piper-swahili.onnx"
                    fi
                    # Copy tokens.txt
                    if [ -f "$EXTRACTED_DIR/tokens.txt" ]; then
                        cp "$EXTRACTED_DIR/tokens.txt" "$MODELS_DIR/tokens.txt"
                    fi
                    # Move espeak-ng-data to models root
                    if [ -d "$EXTRACTED_DIR/espeak-ng-data" ]; then
                        rm -rf "$MODELS_DIR/espeak-ng-data" 2>/dev/null
                        mv "$EXTRACTED_DIR/espeak-ng-data" "$MODELS_DIR/espeak-ng-data"
                    fi
                    # Clean up extracted directory
                    rm -rf "$EXTRACTED_DIR"
                fi

                # Clean up archive
                rm -f "$PIPER_ARCHIVE"

                if [ -f "$MODELS_DIR/piper-swahili.onnx" ]; then
                    SIZE=$(stat -c%s "$MODELS_DIR/piper-swahili.onnx" 2>/dev/null || stat -f%z "$MODELS_DIR/piper-swahili.onnx" 2>/dev/null)
                    echo -e "  ${GREEN}✓${NC} Piper Swahili TTS extracted ($((SIZE / 1048576))MB)"
                    break
                fi
            fi
            echo -e "  ${YELLOW}↻${NC} Retry $attempt/3..."
            sleep 2
        done

        if [ ! -f "$MODELS_DIR/piper-swahili.onnx" ]; then
            echo -e "  ${RED}✗${NC} Failed to download/extract Piper Swahili TTS"
            DOWNLOAD_FAILED=1
        fi
    fi
fi

# ══════════════════════════════════════════════════════════════
# 4. Qwen 3.5 0.8B Q4_K_M (GGUF) — BUNDLED IN APK
# ══════════════════════════════════════════════════════════════
# Decision Council (2026-07-15): Qwen 3.5 0.8B as bundled LLM
# This is REQUIRED — the app must work offline at first launch.
# Source: bartowski/Qwen_Qwen3.5-0.8B-GGUF on HuggingFace
# Filename MUST match BundledModelManager.kt BUNDLED_ASSETS
echo ""
echo "📋 Qwen 3.5 0.8B LLM (bundled in APK):"
download \
    "https://huggingface.co/bartowski/Qwen_Qwen3.5-0.8B-GGUF/resolve/main/Qwen_Qwen3.5-0.8B-Q4_K_M.gguf" \
    "$MODELS_DIR/Qwen3.5-0.8B-Q4_K_M.gguf" \
    "Qwen 3.5 0.8B (Q4_K_M GGUF)" \
    500000000

# ══════════════════════════════════════════════════════════════
# Summary
# ══════════════════════════════════════════════════════════════
echo ""
echo "================================"
TOTAL=$(du -sh "$MODELS_DIR" 2>/dev/null | cut -f1)
echo -e "${GREEN}Total models size: $TOTAL${NC}"
echo ""

# List all model files
echo "Model files:"
ls -lh "$MODELS_DIR"/*.onnx "$MODELS_DIR"/*.gguf "$MODELS_DIR"/*.json 2>/dev/null | awk '{print "  "$NF" ("$5")"}'

echo ""
echo "Expected APK contents (all bundled for offline-first):"
echo "  Whisper encoder (ONNX INT8):     ~10MB"
echo "  Whisper decoder (ONNX INT8):     ~30MB"
echo "  Whisper tokenizer (JSON):         ~2.5MB"
echo "  Silero VAD (ONNX):               ~0.6MB"
echo "  Piper Swahili TTS (ONNX):        ~60MB"
echo "  Qwen 3.5 0.8B (GGUF Q4_K_M):   ~580MB"
echo "  App code + resources + native:    ~15MB"
echo "  ─────────────────────────────────"
echo "  Estimated total APK:             ~700MB (stored uncompressed for mmap)"
echo ""
echo "Note: Models stored uncompressed (noCompress) for memory-mapped access."
echo "      APK size reflects raw model sizes. Download once, use forever."
echo ""

if [ "$VERIFY_ONLY" = true ]; then
    if [ "$VERIFY_FAILED" -eq 1 ]; then
        echo -e "${RED}❌ Verification FAILED — missing models${NC}"
        exit 1
    else
        echo -e "${GREEN}✅ Verification complete — all models present${NC}"
    fi
elif [ "$DOWNLOAD_FAILED" -eq 1 ]; then
    echo -e "${RED}❌ Some models failed to download${NC}"
    exit 1
else
    echo -e "${GREEN}✅ All models ready for APK build${NC}"
fi
