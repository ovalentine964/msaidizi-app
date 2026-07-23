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
  ["whisper-encoder-int8.onnx"]="https://huggingface.co/Xenova/whisper-tiny/resolve/main/onnx/encoder_model_quantized.onnx"
  ["whisper-decoder-int8.onnx"]="https://huggingface.co/Xenova/whisper-tiny/resolve/main/onnx/decoder_model_merged_quantized.onnx"
  ["whisper-tokens.json"]="https://huggingface.co/Xenova/whisper-tiny/resolve/main/tokenizer.json"
  ["silero_vad.onnx"]="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
)

# Piper Swahili is distributed as a tar.bz2 archive and must be extracted.
PIPER_ARCHIVE_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-sw_CD-lanfrica-medium.tar.bz2"
PIPER_ARCHIVE_NAME="vits-piper-sw_CD-lanfrica-medium.tar.bz2"

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

# ─────────────────────────────────────────────────────────────
# Piper Swahili: download tar.bz2 archive and extract ONNX model
# ─────────────────────────────────────────────────────────────
PIPER_ONNX="$MODELS_DIR/piper-swahili.onnx"
if [ ! -f "$PIPER_ONNX" ] || [ ! -s "$PIPER_ONNX" ]; then
  PIPER_ARCHIVE="$MODELS_DIR/$PIPER_ARCHIVE_NAME"
  echo "  📦 Piper Swahili: downloading archive..."
  if download_with_retry "$PIPER_ARCHIVE_URL" "$PIPER_ARCHIVE"; then
    echo "  📦 Extracting Piper Swahili model..."
    EXTRACT_DIR="$MODELS_DIR/_piper_extract"
    mkdir -p "$EXTRACT_DIR"
    if tar xjf "$PIPER_ARCHIVE" -C "$EXTRACT_DIR" 2>&1; then
      # Find the .onnx model file in the extracted contents
      ONNX_SRC=$(find "$EXTRACT_DIR" -name '*.onnx' -type f | head -1)
      if [ -n "$ONNX_SRC" ]; then
        cp "$ONNX_SRC" "$PIPER_ONNX"
        echo "  ✅ Extracted: piper-swahili.onnx ($(du -h "$PIPER_ONNX" | cut -f1))"
      else
        echo "  ❌ No .onnx file found in Piper archive"
        FAILED=$((FAILED + 1))
      fi
      # Also extract tokens.txt and espeak-ng-data if present
      TOKENS_SRC=$(find "$EXTRACT_DIR" -name 'tokens.txt' -type f | head -1)
      if [ -n "$TOKENS_SRC" ]; then
        cp "$TOKENS_SRC" "$MODELS_DIR/piper-tokens.txt"
      fi
      ESPEAK_SRC=$(find "$EXTRACT_DIR" -name 'espeak-ng-data' -type d | head -1)
      if [ -n "$ESPEAK_SRC" ]; then
        cp -r "$ESPEAK_SRC" "$MODELS_DIR/espeak-ng-data"
      fi
    else
      echo "  ❌ Failed to extract Piper archive"
      FAILED=$((FAILED + 1))
    fi
    rm -rf "$EXTRACT_DIR" "$PIPER_ARCHIVE"
  else
    echo "  ❌ Failed to download Piper Swahili archive"
    FAILED=$((FAILED + 1))
  fi
else
  echo "  ⏭️  Already exists: piper-swahili.onnx ($(du -h "$PIPER_ONNX" | cut -f1))"
fi

if [ "$FAILED" -gt 0 ]; then
  echo "❌ $FAILED model(s) failed to download"
  exit 1
fi

# ─────────────────────────────────────────────────────────────
# Post-download: Copy LLM models to app's filesDir location
# ─────────────────────────────────────────────────────────────
# Models are downloaded to assets/models/ for APK bundling (full flavor),
# but at runtime the app loads them from context.filesDir/models/.
# For development/testing, we also copy to the expected runtime location.
FILES_DIR_MODELS="app/src/main/files/models"
if [ "$LITE_MODE" = "false" ]; then
  mkdir -p "$FILES_DIR_MODELS"
  for model in "${!LLM_MODELS[@]}"; do
    src="$MODELS_DIR/$model"
    dst="$FILES_DIR_MODELS/$model"
    if [ -f "$src" ] && [ -s "$src" ]; then
      if [ ! -f "$dst" ] || [ ! -s "$dst" ]; then
        echo "📋 Copying $model to runtime location..."
        cp "$src" "$dst"
      fi
    fi
  done
fi

echo ""
echo "✅ All models downloaded successfully"
ls -lh "$MODELS_DIR/"
