#!/usr/bin/env bash
# ============================================================
# setup-sherpa-onnx.sh — Download sherpa-onnx JNI libs for Android
# ============================================================
# Downloads pre-built sherpa-onnx JNI libraries for arm64-v8a AND armeabi-v7a.
# These are the native shared libraries needed for offline
# ASR (speech recognition), TTS (text-to-speech), and VAD.
#
# 32-bit (armeabi-v7a) support enables voice features on budget phones
# common in Africa (Tecno, Infinix, Itel). On-device LLM is NOT available
# on 32-bit — those devices use cloud-only mode for text inference.
# ============================================================

set -euo pipefail

KT_API_DIR="app/src/main/java/com/k2fsa/sherpa/onnx"
MAX_RETRIES=3
RETRY_DELAY=5

# sherpa-onnx version — update when upgrading
SHERPA_VERSION="1.13.4"

# Download for both architectures
# arm64-v8a: 64-bit devices (primary)
# armeabi-v7a: 32-bit devices (budget phones in Africa)
ARCHITECTURES=("arm64-v8a" "armeabi-v7a")

echo "📦 Setting up sherpa-onnx v${SHERPA_VERSION} JNI libs for: ${ARCHITECTURES[*]}..."

# ─────────────────────────────────────────────────────────────
# Helper: download with retry
# ─────────────────────────────────────────────────────────────
download_with_retry() {
  local url="$1"
  local dest="$2"
  local attempt=0

  while [ $attempt -lt $MAX_RETRIES ]; do
    attempt=$((attempt + 1))
    echo "  ⬇️  Downloading (attempt $attempt/$MAX_RETRIES)..."

    if curl -fSL --retry 3 --retry-delay 5 --connect-timeout 30 --max-time 600 \
         -o "$dest" "$url" 2>&1; then
      if [ -f "$dest" ] && [ -s "$dest" ]; then
        echo "  ✅ Downloaded: $(du -h "$dest" | cut -f1)"
        return 0
      fi
    fi

    echo "  ⚠️  Download failed, retrying in ${RETRY_DELAY}s..."
    rm -f "$dest"
    sleep $((RETRY_DELAY * attempt))
  done

  echo "  ❌ Failed to download after $MAX_RETRIES attempts"
  return 1
}

# ─────────────────────────────────────────────────────────────
# Check if libs already exist for all architectures
# ─────────────────────────────────────────────────────────────
ALL_PRESENT=true
for ARCH in "${ARCHITECTURES[@]}"; do
  JNI_DIR="app/src/main/jniLibs/${ARCH}"
  if [ ! -f "$JNI_DIR/libsherpa-onnx-jni.so" ] || [ ! -f "$JNI_DIR/libonnxruntime.so" ]; then
    ALL_PRESENT=false
    break
  fi
done

if [ "$ALL_PRESENT" = true ]; then
  echo "✅ sherpa-onnx JNI libs already present for all architectures"
  for ARCH in "${ARCHITECTURES[@]}"; do
    JNI_DIR="app/src/main/jniLibs/${ARCH}"
    ls -lh "$JNI_DIR/"*.so
  done
  exit 0
fi

# ─────────────────────────────────────────────────────────────
# Download and extract for all architectures
# ─────────────────────────────────────────────────────────────

# v1.13+ ships a single archive with all architectures
SHERPA_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-v${SHERPA_VERSION}-android.tar.bz2"

# Check if all architectures already have libs
ALL_DONE=true
for ARCH in "${ARCHITECTURES[@]}"; do
  JNI_DIR="app/src/main/jniLibs/${ARCH}"
  if [ ! -f "$JNI_DIR/libsherpa-onnx-jni.so" ] || [ ! -f "$JNI_DIR/libonnxruntime.so" ]; then
    ALL_DONE=false
    break
  fi
done

if [ "$ALL_DONE" = true ]; then
  echo "✅ sherpa-onnx JNI libs already present for all architectures"
  exit 0
fi

TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

ARCHIVE="$TMPDIR/sherpa-onnx-android.tar.bz2"
echo ""
echo "  ⬇️  Downloading sherpa-onnx v${SHERPA_VERSION} (all architectures)..."

if ! download_with_retry "$SHERPA_URL" "$ARCHIVE"; then
  echo "❌ Failed to download sherpa-onnx"
  exit 1
fi

echo "  📂 Extracting..."
tar -xjf "$ARCHIVE" -C "$TMPDIR"

# Copy .so files to each architecture
KT_SRC_COPIED=false
for ARCH in "${ARCHITECTURES[@]}"; do
  JNI_DIR="app/src/main/jniLibs/${ARCH}"

  # Check if libs already exist for this architecture
  if [ -f "$JNI_DIR/libsherpa-onnx-jni.so" ] && [ -f "$JNI_DIR/libonnxruntime.so" ]; then
    echo "✅ sherpa-onnx JNI libs already present for ${ARCH}"
    ls -lh "$JNI_DIR/"*.so
    continue
  fi

  mkdir -p "$JNI_DIR"

  # Find .so files for this architecture
  EXTRACTED_DIR=$(find "$TMPDIR" -maxdepth 3 -type d -path "*/lib/${ARCH}" | head -1)
  if [ -n "$EXTRACTED_DIR" ]; then
    cp "$EXTRACTED_DIR/"*.so "$JNI_DIR/" 2>/dev/null || true
    echo "  ✅ Copied .so files for ${ARCH}"
  else
    # Fallback: find .so files directly and match by arch
    SO_FILES=$(find "$TMPDIR" -name "libsherpa-onnx-jni.so" -o -name "libonnxruntime.so")
    if [ -n "$SO_FILES" ]; then
      echo "$SO_FILES" | while read -r f; do
        # Check if the path contains the architecture
        if echo "$f" | grep -q "$ARCH"; then
          cp "$f" "$JNI_DIR/"
          echo "  ✅ Copied: $(basename "$f")"
        fi
      done
    else
      echo "❌ Could not find .so files in extracted archive for ${ARCH}"
      echo "  Contents of $TMPDIR:"
      find "$TMPDIR" -type f | head -20
      exit 1
    fi
  fi

  # Setup Kotlin API source files (only once — same for all architectures)
  if [ "$KT_SRC_COPIED" = false ]; then
    KT_SRC=$(find "$TMPDIR" -path "*/com/k2fsa/sherpa/onnx/*.kt" -type f 2>/dev/null | head -1)
    if [ -n "$KT_SRC" ]; then
      mkdir -p "$KT_API_DIR"
      KT_SRC_DIR=$(dirname "$KT_SRC")
      cp "$KT_SRC_DIR"/*.kt "$KT_API_DIR/" 2>/dev/null || true
      KT_COUNT=$(find "$KT_API_DIR" -name "*.kt" | wc -l)
      echo "  ✅ Installed $KT_COUNT Kotlin API files to $KT_API_DIR"
      KT_SRC_COPIED=true
    else
      echo "  ℹ️  No Kotlin API files in archive (already in source tree)"
      KT_SRC_COPIED=true
    fi
  fi

done

# ─────────────────────────────────────────────────────────────
# Verify all architectures
# ─────────────────────────────────────────────────────────────
echo ""
echo "🔍 Verifying JNI libs for all architectures..."
TOTAL_MISSING=0
for ARCH in "${ARCHITECTURES[@]}"; do
  JNI_DIR="app/src/main/jniLibs/${ARCH}"
  echo "  [${ARCH}]"
  MISSING=0
  for lib in libsherpa-onnx-jni.so libonnxruntime.so; do
    if [ -f "$JNI_DIR/$lib" ] && [ -s "$JNI_DIR/$lib" ]; then
      SIZE=$(stat -c%s "$JNI_DIR/$lib" 2>/dev/null || stat -f%z "$JNI_DIR/$lib" 2>/dev/null || echo 0)
      echo "    ✅ $lib ($(( SIZE / 1024 )) KB)"
    else
      echo "    ❌ MISSING: $lib"
      MISSING=$((MISSING + 1))
      TOTAL_MISSING=$((TOTAL_MISSING + 1))
    fi
  done
done

if [ "$TOTAL_MISSING" -gt 0 ]; then
  echo "❌ $TOTAL_MISSING required lib(s) missing!"
  exit 1
fi

echo "✅ sherpa-onnx setup complete for ${ARCHITECTURES[*]}"
