#!/usr/bin/env bash
# ============================================================
# download-models.sh — Download AI models for Msaidizi app
# ============================================================
# Usage:
#   ./scripts/download-models.sh          # Download all models
#   ./scripts/download-models.sh --verify  # Verify models exist
#
# Models are cached in CI via actions/cache.
# ============================================================

set -euo pipefail

MODELS_DIR="app/src/main/assets/models"
MAX_RETRIES=3
RETRY_DELAY=5

# ─────────────────────────────────────────────────────────────
# Model definitions: name | filename | URL
# ─────────────────────────────────────────────────────────────
declare -A MODEL_URLS=(
  ["ggml-tiny.en-q5_1.bin"]="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin"
  ["piper-swahili.onnx"]="https://huggingface.co/rhasspy/piper-voices/resolve/main/vi/vi_VN/vais1000/medium/vi_VN-vais1000-medium.onnx"
  ["piper-swahili.onnx.json"]="https://huggingface.co/rhasspy/piper-voices/resolve/main/vi/vi_VN/vais1000/medium/vi_VN-vais1000-medium.onnx.json"
  ["silero_vad.onnx"]="https://huggingface.co/snakers4/silero-vad/resolve/main/src/silero_vad/silero_vad.onnx"
  ["qwen3.5-0.8b-q4_k_m.gguf"]="https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf"
)

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
if [ "${1:-}" = "--verify" ]; then
  echo "🔍 Verifying models in $MODELS_DIR ..."
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
