#!/usr/bin/env bash
# ============================================================
# setup-llama-cpp.sh — Pre-download llama.cpp source for offline builds
# ============================================================
# The CMake build uses FetchContent to download llama.cpp from GitHub.
# This script pre-downloads it into the vendor directory so builds
# work offline or behind corporate firewalls.
#
# Usage:
#   ./scripts/setup-llama-cpp.sh           # Download to vendor/llama.cpp
#   ./scripts/setup-llama-cpp.sh --clean   # Remove vendored copy
#
# After running this, CMake will automatically use the local copy
# instead of downloading from GitHub.
# ============================================================

set -euo pipefail

LLAMA_CPP_TAG="b4651"
VENDOR_DIR="app/src/main/cpp/vendor/llama.cpp"

# ─────────────────────────────────────────────────────────────
# Clean mode
# ─────────────────────────────────────────────────────────────
if [ "${1:-}" = "--clean" ]; then
  echo "🗑️  Removing vendored llama.cpp from $VENDOR_DIR ..."
  rm -rf "$VENDOR_DIR"
  echo "✅ Cleaned. Builds will download llama.cpp from GitHub."
  exit 0
fi

# ─────────────────────────────────────────────────────────────
# Check if already vendored
# ─────────────────────────────────────────────────────────────
if [ -f "$VENDOR_DIR/CMakeLists.txt" ]; then
  echo "✅ llama.cpp already vendored at $VENDOR_DIR"
  echo "   To update, run: $0 --clean && $0"
  exit 0
fi

# ─────────────────────────────────────────────────────────────
# Download
# ─────────────────────────────────────────────────────────────
echo "📦 Downloading llama.cpp (tag: $LLAMA_CPP_TAG) to $VENDOR_DIR ..."
mkdir -p "$(dirname "$VENDOR_DIR")"

# Use git clone with shallow depth for speed
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT

echo "  ⬇️  Cloning llama.cpp (shallow, tag $LLAMA_CPP_TAG) ..."
git clone --depth 1 --branch "$LLAMA_CPP_TAG" \
  https://github.com/ggerganov/llama.cpp.git "$TEMP_DIR/llama.cpp" 2>&1

# Move to vendor directory
mv "$TEMP_DIR/llama.cpp" "$VENDOR_DIR"

# Remove .git to save space (we don't need history)
rm -rf "$VENDOR_DIR/.git"

SIZE=$(du -sh "$VENDOR_DIR" | cut -f1)
echo ""
echo "✅ llama.cpp vendored at $VENDOR_DIR ($SIZE)"
echo "   CMake will now use this local copy instead of downloading."
echo "   To update: $0 --clean && $0"
