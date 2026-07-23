#!/usr/bin/env bash
# ============================================================
# download-models.sh — Download AI models for Msaidizi app
# ============================================================
# Usage:
#   ./scripts/download-models.sh                # Download all models (full flavor)
#   ./scripts/download-models.sh --lite         # Download voice models only (lite flavor)
#   ./scripts/download-models.sh --verify       # Verify all models exist
#   ./scripts/download-models.sh --lite --verify # Verify voice models only
#
# Models are cached in CI via actions/cache.
#
# Flavor strategy:
#   full (default): bundles all models (voice + LLM ~650MB)
#   lite:           bundles voice models only (~65MB, LLM downloads at runtime)
# ============================================================

set -euo pipefail

MODELS_DIR="app/src/main/assets/models"
MAX_RETRIES=3
RETRY_DELAY=5

# ─────────────────────────────────────────────────────────────
# Model definitions: name | filename | URL
# ─────────────────────────────────────────────────────────────
declare -A VOICE_MODELS=(
  # Whisper Tiny INT4 ONNX — multilingual (not English-only), ONNX format (not GGML)
  ["whisper-encoder-int8.onnx"]="https://huggingface.co/Xenova/whisper-tiny/resolve/main/onnx/encoder_model_quantized.onnx"
  ["whisper-decoder-int8.onnx"]="https://huggingface.co/Xenova/whisper-tiny/resolve/main/onnx/decoder_model_merged_quantized.onnx"
  ["whisper-tokens.json"]="https://huggingface.co/Xenova/whisper-tiny/resolve/main/tokenizer.json"
  # Piper Swahili (sw_CD, lanfrica medium) — actual Swahili voice
  ["piper-swahili.onnx"]="https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/sw/sw_CD/lanfrica/medium/sw_CD-lanfrica-medium.onnx"
  ["piper-swahili.onnx.json"]="https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/sw/sw_CD/lanfrica/medium/sw_CD-lanfrica-medium.onnx.json"
  ["silero_vad.onnx"]="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
)

declare -A LLM_MODELS=(
  ["Qwen3.5-0.8B-Q4_K_M.gguf"]="https://huggingface.co/bartowski/Qwen_Qwen3.5-0.8B-GGUF/resolve/main/Qwen_Qwen3.5-0.8B-Q4_K_M.gguf"
)

# Parse flags (--lite and --verify can appear in any order)
LITE_MODE=false
VERIFY_MODE=false
for arg in "$@"; do
  case "$arg" in
    --lite) LITE_MODE=true ;;
    --verify) VERIFY_MODE=true ;;
  esac

done

declare -A MODEL_URLS
for key in "${!VOICE_MODELS[@]}"; do
  MODEL_URLS["$key"]="${VOICE_MODELS[$key]}"
done

if [ "$LITE_MODE" = "false" ]; then
  for key in "${!LLM_MODELS[@]}"; do
    MODEL_URLS["$key"]="${LLM_MODELS[$key]}"
  done
  echo "📦 Downloading ALL models (voice + LLM) to $MODELS_DIR ..."
else
  echo "📦 Downloading VOICE models only (lite flavor) to $MODELS_DIR ..."
fi

# ─────────────────────────────────────────────────────────────
# Helper: download with retry
# ─────────────────────────────────────────────────────────────
download_with_retry() {
  local url="$1"
  local dest="$2"
  local attempt=0

  while [ $attempt -lt $MAX_RETRIES ]; do
    attempt=$((attempt + 1))
    echo "  ⬇️  Downloading (attempt $attempt/$MAX_RETRIES): $(basename "$dest")"

    if curl -fSL --retry 3 --retry-delay 5 --connect-timeout 30 --max-time 1800 \
         -o "$dest" "$url" 2>&1; then
      if [ -f "$dest" ] && [ -s "$dest" ]; then
        echo "  ✅ Downloaded: $(basename "$dest") ($(du -h "$dest" | cut -f1))"
        return 0
      fi
    fi

    echo "  ⚠️  Download failed, retrying in ${RETRY_DELAY}s..."
    rm -f "$dest"
    sleep $((RETRY_DELAY * attempt))
  done

  echo "  ❌ Failed to download after $MAX_RETRIES attempts: $(basename "$dest")"
  return 1
}

# ─────────────────────────────────────────────────────────────
# Verify mode: just check files exist
# ─────────────────────────────────────────────────────────────
if [ "$VERIFY_MODE" = "true" ]; then
  echo "🔍 Verifying models in $MODELS_DIR ..."
  if [ "$LITE_MODE" = "true" ]; then
    echo "   (lite flavor — checking voice models only)"
  fi
  VERIFY_FAILED=0

  for model in "${!MODEL_URLS[@]}"; do
    filepath="$MODELS_DIR/$model"
    if [ -f "$filepath" ] && [ -s "$filepath" ]; then
      size=$(du -h "$filepath" | cut -f1)
      echo "  ✅ $model ($size)"
    else
      echo "  ❌ MISSING: $model"
      VERIFY_FAILED=1
    fi
  done

  if [ "$VERIFY_FAILED" -eq 1 ]; then
    echo "❌ Model verification failed — missing models!"
    exit 1
  fi

  echo "✅ All models verified"
  exit 0
fi

# ─────────────────────────────────────────────────────────────
# Download mode
# ─────────────────────────────────────────────────────────────
echo "📦 Downloading AI models to $MODELS_DIR ..."
mkdir -p "$MODELS_DIR"

FAILED=0
for model in "${!MODEL_URLS[@]}"; do
  filepath="$MODELS_DIR/$model"
  url="${MODEL_URLS[$model]}"

  # Skip if already downloaded and non-empty
  if [ -f "$filepath" ] && [ -s "$filepath" ]; then
    echo "  ⏭️  Already exists: $model ($(du -h "$filepath" | cut -f1))"
    continue
  fi

  if ! download_with_retry "$url" "$filepath"; then
    FAILED=$((FAILED + 1))
  fi
done

if [ "$FAILED" -gt 0 ]; then
  echo "❌ $FAILED model(s) failed to download"
  exit 1
fi

echo ""
echo "✅ All models downloaded successfully"
ls -lh "$MODELS_DIR/"
