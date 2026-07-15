#!/usr/bin/env bash
# ============================================================
# build-full-apk.sh — Comprehensive Msaidizi APK Build Script
# ============================================================
# Checks prerequisites, resolves dependencies, builds debug APK,
# and reports status. Handles model bundling configuration.
#
# Usage:
#   ./build-full-apk.sh                  # Full build (debug)
#   ./build-full-apk.sh --release        # Full build (release)
#   ./build-full-apk.sh --check-only     # Only check prerequisites
#   ./build-full-apk.sh --deps-only      # Only resolve dependencies
#   ./build-full-apk.sh --clean          # Clean before build
#   ./build-full-apk.sh --skip-models    # Skip model download/verify
#   ./build-full-apk.sh --skip-sherpa    # Skip sherpa-onnx JNI setup
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# ── Colors ──────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

log()  { echo -e "${GREEN}✅ $*${NC}"; }
warn() { echo -e "${YELLOW}⚠️  $*${NC}"; }
err()  { echo -e "${RED}❌ $*${NC}"; }
info() { echo -e "${BLUE}ℹ️  $*${NC}"; }
header() { echo -e "\n${BOLD}${CYAN}═══ $* ═══${NC}\n"; }

# ── Parse arguments ─────────────────────────────────────────
BUILD_TYPE="debug"
CHECK_ONLY=false
DEPS_ONLY=false
DO_CLEAN=false
SKIP_MODELS=false
SKIP_SHERPA=false

for arg in "$@"; do
    case "$arg" in
        --release)      BUILD_TYPE="release" ;;
        --check-only)   CHECK_ONLY=true ;;
        --deps-only)    DEPS_ONLY=true ;;
        --clean)        DO_CLEAN=true ;;
        --skip-models)  SKIP_MODELS=true ;;
        --skip-sherpa)  SKIP_SHERPA=true ;;
        -h|--help)
            echo "Usage: $0 [--release] [--check-only] [--deps-only] [--clean] [--skip-models] [--skip-sherpa]"
            exit 0
            ;;
        *)
            warn "Unknown argument: $arg"
            ;;
    esac
done

# ── Timing ──────────────────────────────────────────────────
BUILD_START=$(date +%s)

# ── Error tracking ──────────────────────────────────────────
ERRORS=()
WARNINGS=()

add_error()   { ERRORS+=("$1"); err "$1"; }
add_warning() { WARNINGS+=("$1"); warn "$1"; }

# ============================================================
# PHASE 1: Prerequisites Check
# ============================================================
header "PHASE 1: Prerequisites Check"

# 1a. JDK 17
info "Checking JDK 17..."
if command -v java &>/dev/null; then
    JAVA_VER=$(java -version 2>&1 | head -1 | awk -F '"' '{print $2}' | cut -d. -f1)
    if [ "$JAVA_VER" -ge 17 ] 2>/dev/null; then
        log "JDK $JAVA_VER found: $(java -version 2>&1 | head -1)"
    else
        add_error "JDK 17+ required, found JDK $JAVA_VER"
    fi
else
    add_error "Java not found. Install JDK 17: https://adoptium.net/"
fi

if [ -n "${JAVA_HOME:-}" ]; then
    log "JAVA_HOME=$JAVA_HOME"
else
    add_warning "JAVA_HOME not set — Gradle may not find JDK"
fi

# 1b. Android SDK
info "Checking Android SDK..."
if [ -n "${ANDROID_HOME:-}" ]; then
    log "ANDROID_HOME=$ANDROID_HOME"
    if [ -d "$ANDROID_HOME/cmdline-tools" ]; then
        log "Android cmdline-tools found"
    else
        add_warning "Android cmdline-tools not found in ANDROID_HOME"
    fi
elif [ -d "$HOME/Android/Sdk" ]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
    log "ANDROID_HOME auto-detected: $ANDROID_HOME"
else
    add_error "ANDROID_HOME not set and Android SDK not found"
fi

# 1c. NDK
NDK_VERSION="26.1.10909125"
info "Checking NDK $NDK_VERSION..."
if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk/$NDK_VERSION" ]; then
    log "NDK $NDK_VERSION found"
    export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_VERSION"
else
    add_warning "NDK $NDK_VERSION not found — will attempt install via sdkmanager"
    if command -v sdkmanager &>/dev/null || [ -x "${ANDROID_HOME:-}/cmdline-tools/latest/bin/sdkmanager" ]; then
        info "Installing NDK via sdkmanager..."
        echo "y" | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" "ndk;$NDK_VERSION" || \
            add_error "Failed to install NDK $NDK_VERSION"
    else
        add_error "sdkmanager not available — install Android SDK cmdline-tools first"
    fi
fi

# 1d. CMake
CMAKE_VERSION="3.22.1"
info "Checking CMake $CMAKE_VERSION..."
if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/cmake/$CMAKE_VERSION" ]; then
    log "CMake $CMAKE_VERSION found"
elif command -v cmake &>/dev/null; then
    CMAKE_VER=$(cmake --version | head -1 | awk '{print $3}')
    log "System CMake $CMAKE_VER found"
else
    add_warning "CMake not found — will attempt install via sdkmanager"
    if [ -n "${ANDROID_HOME:-}" ] && [ -x "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" ]; then
        echo "y" | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" "cmake;$CMAKE_VERSION" || \
            add_error "Failed to install CMake $CMAKE_VERSION"
    fi
fi

# 1e. Gradle wrapper
info "Checking Gradle wrapper..."
if [ -x "./gradlew" ]; then
    GRADLE_VER=$(./gradlew --version 2>/dev/null | grep "Gradle " | awk '{print $2}' || echo "unknown")
    log "Gradle wrapper $GRADLE_VER"
else
    add_error "gradlew not found or not executable"
fi

# 1f. Summary
if [ ${#ERRORS[@]} -gt 0 ]; then
    echo ""
    err "Prerequisites check FAILED with ${#ERRORS[@]} error(s):"
    for e in "${ERRORS[@]}"; do
        echo "  • $e"
    done
    if [ "$CHECK_ONLY" = true ]; then
        exit 1
    fi
    echo ""
    warn "Continuing despite errors — build may fail..."
else
    log "All prerequisites satisfied"
fi

if [ "$CHECK_ONLY" = true ]; then
    echo ""
    info "Check-only mode complete. ${#WARNINGS[@]} warning(s)."
    exit 0
fi

# ============================================================
# PHASE 2: Clean (optional)
# ============================================================
if [ "$DO_CLEAN" = true ]; then
    header "PHASE 2: Clean Previous Builds"
    info "Cleaning build directories..."
    ./gradlew clean --quiet 2>/dev/null || true
    rm -rf app/build/ .gradle/ 2>/dev/null || true
    log "Clean complete"
else
    info "Skipping clean (use --clean to enable)"
fi

# ============================================================
# PHASE 3: Model Setup
# ============================================================
if [ "$SKIP_MODELS" = false ]; then
    header "PHASE 3: AI Model Setup"
    MODELS_DIR="app/src/main/assets/models"

    if [ -f "scripts/download-models.sh" ]; then
        # Check if models already exist
        MODELS_NEEDED=false
        for model in "ggml-tiny.en-q5_1.bin" "piper-swahili.onnx"; do
            if [ ! -f "$MODELS_DIR/$model" ]; then
                MODELS_NEEDED=true
                break
            fi
        done

        if [ "$MODELS_NEEDED" = true ]; then
            info "Downloading AI models (this may take a while)..."
            chmod +x scripts/download-models.sh
            ./scripts/download-models.sh || add_warning "Model download failed — build may lack bundled models"
        else
            log "AI models already present"
        fi

        # Verify models
        info "Verifying model integrity..."
        ./scripts/download-models.sh --verify || add_warning "Model verification failed"
    else
        add_warning "scripts/download-models.sh not found — skipping model setup"
    fi
else
    info "Skipping model setup (--skip-models)"
fi

# ============================================================
# PHASE 4: Sherpa-ONNX JNI Setup
# ============================================================
if [ "$SKIP_SHERPA" = false ]; then
    header "PHASE 4: Sherpa-ONNX JNI Setup"
    JNI_DIR="app/src/main/jniLibs/arm64-v8a"

    SHERPA_NEEDED=false
    for lib in "libsherpa-onnx-jni.so" "libonnxruntime.so"; do
        if [ ! -f "$JNI_DIR/$lib" ]; then
            SHERPA_NEEDED=true
            break
        fi
    done

    if [ "$SHERPA_NEEDED" = true ]; then
        if [ -f "scripts/setup-sherpa-onnx.sh" ]; then
            info "Setting up sherpa-onnx JNI libs..."
            chmod +x scripts/setup-sherpa-onnx.sh
            ./scripts/setup-sherpa-onnx.sh || add_warning "Sherpa-ONNX setup failed"
        else
            add_warning "scripts/setup-sherpa-onnx.sh not found"
        fi
    else
        log "Sherpa-ONNX JNI libs already present"
    fi

    # Verify JNI libs
    if [ -d "$JNI_DIR" ]; then
        JNI_COUNT=$(find "$JNI_DIR" -name "*.so" | wc -l)
        log "JNI libs in $JNI_DIR: $JNI_COUNT .so files"
    fi
else
    info "Skipping sherpa-onnx setup (--skip-sherpa)"
fi

# ============================================================
# PHASE 5: Debug Keystore
# ============================================================
header "PHASE 5: Signing Setup"

if [ "$BUILD_TYPE" = "debug" ]; then
    if [ ! -f "debug.keystore" ]; then
        info "Generating debug keystore..."
        if command -v keytool &>/dev/null; then
            keytool -genkeypair -v \
                -keystore debug.keystore \
                -alias androiddebugkey \
                -keyalg RSA \
                -keysize 2048 \
                -validity 10000 \
                -storepass android \
                -keypass android \
                -dname "CN=Android Debug,O=Msaidizi,C=KE" 2>/dev/null
            log "Debug keystore generated"
        else
            warn "keytool not found — will use ~/.android/debug.keystore"
        fi
    else
        log "Debug keystore already exists"
    fi
else
    # Release build — check for keystore
    if [ -n "${RELEASE_KEYSTORE_FILE:-}" ]; then
        if [ -f "$RELEASE_KEYSTORE_FILE" ]; then
            log "Release keystore found at $RELEASE_KEYSTORE_FILE"
        else
            add_error "Release keystore not found at $RELEASE_KEYSTORE_FILE"
        fi
    elif [ -f "keystore.properties" ]; then
        log "Release keystore config found (keystore.properties)"
    else
        add_warning "No release keystore configured — build may use debug signing"
    fi
fi

# ============================================================
# PHASE 6: Dependency Resolution (dry-run)
# ============================================================
header "PHASE 6: Dependency Resolution"

info "Resolving dependencies (dry-run)..."
if ./gradlew dependencies --configuration debugRuntimeClasspath --quiet 2>&1 | tail -5; then
    log "Dependency resolution succeeded"
else
    add_warning "Dependency resolution had issues — check output above"
fi

if [ "$DEPS_ONLY" = true ]; then
    echo ""
    info "Dependencies-only mode complete."
    exit 0
fi

# ============================================================
# PHASE 7: Build APK
# ============================================================
header "PHASE 7: Build ${BUILD_TYPE^} APK"

BUILD_TASK="assemble${BUILD_TYPE^}"
info "Running: ./gradlew $BUILD_TASK --stacktrace"

set -o pipefail
./gradlew "$BUILD_TASK" --stacktrace 2>&1 | tee "build-${BUILD_TYPE}.log"
EXIT_CODE=${PIPESTATUS[0]}

if [ "$EXIT_CODE" -ne 0 ]; then
    err "Gradle $BUILD_TASK failed (exit code $EXIT_CODE)"
    echo ""
    echo "=== Last 30 lines of build log ==="
    tail -30 "build-${BUILD_TYPE}.log"
    echo ""
    echo "=== Compilation errors ==="
    grep -i "error:" "build-${BUILD_TYPE}.log" | head -20 || true
    echo ""
    err "Build failed. See build-${BUILD_TYPE}.log for full details."
    exit "$EXIT_CODE"
fi

# ============================================================
# PHASE 8: Results
# ============================================================
header "BUILD RESULTS"

BUILD_END=$(date +%s)
BUILD_DURATION=$((BUILD_END - BUILD_START))
BUILD_MINUTES=$((BUILD_DURATION / 60))
BUILD_SECONDS=$((BUILD_DURATION % 60))

APK_PATH=$(find "app/build/outputs/apk/${BUILD_TYPE}" -name "*.apk" 2>/dev/null | head -1)

if [ -n "$APK_PATH" ]; then
    SIZE=$(stat -c%s "$APK_PATH" 2>/dev/null || stat -f%z "$APK_PATH" 2>/dev/null || echo "0")
    SIZE_MB=$((SIZE / 1048576))

    echo -e "${GREEN}${BOLD}"
    echo "  ╔══════════════════════════════════════════════╗"
    echo "  ║          BUILD SUCCESSFUL ✅                 ║"
    echo "  ╠══════════════════════════════════════════════╣"
    echo "  ║  Type:     ${BUILD_TYPE^}                           ║"
    echo "  ║  APK:      $(basename "$APK_PATH")"
    echo "  ║  Size:     ${SIZE_MB} MB"
    echo "  ║  Duration: ${BUILD_MINUTES}m ${BUILD_SECONDS}s"
    echo "  ╚══════════════════════════════════════════════╝"
    echo -e "${NC}"

    if [ "$SIZE_MB" -lt 100 ]; then
        warn "APK is only ${SIZE_MB}MB — AI models may not be bundled!"
    fi
    if [ "$SIZE_MB" -gt 2048 ]; then
        err "APK exceeds 2GB limit (${SIZE_MB}MB)"
    fi

    echo ""
    info "Install on device:"
    echo "   adb install -r $APK_PATH"
else
    err "No APK found at app/build/outputs/apk/${BUILD_TYPE}/"
    exit 1
fi

# ── Warnings summary ────────────────────────────────────────
if [ ${#WARNINGS[@]} -gt 0 ]; then
    echo ""
    warn "${#WARNINGS[@]} warning(s) during build:"
    for w in "${WARNINGS[@]}"; do
        echo "  • $w"
    done
fi

echo ""
log "Build log saved to build-${BUILD_TYPE}.log"
