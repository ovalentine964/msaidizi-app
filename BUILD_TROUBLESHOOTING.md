# Build Troubleshooting Guide

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Common Build Errors](#common-build-errors)
3. [NDK Setup](#ndk-setup)
4. [Model File Setup](#model-file-setup)
5. [CI/CD Debugging](#cicd-debugging)
6. [Dependency Issues](#dependency-issues)
7. [Signing Issues](#signing-issues)
8. [Performance Tips](#performance-tips)

---

## Prerequisites

| Tool | Version | Check Command |
|------|---------|---------------|
| JDK | 17+ | `java -version` |
| Android SDK | API 35 | `echo $ANDROID_HOME` |
| NDK | r26b (26.1.10909125) | `ls $ANDROID_HOME/ndk/` |
| CMake | 3.22.1 | `cmake --version` |
| Gradle | 8.11.1 (via wrapper) | `./gradlew --version` |
| Kotlin | 2.0.21 | (managed by Gradle) |

### Quick Setup (Ubuntu/Debian)

```bash
# JDK 17
sudo apt install openjdk-17-jdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# Android SDK (if not installed)
# Download cmdline-tools from https://developer.android.com/studio#command-line-tools-only
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

# NDK + CMake
sdkmanager "ndk;26.1.10909125" "cmake;3.22.1"
```

### Quick Setup (macOS)

```bash
# JDK 17
brew install openjdk@17
export JAVA_HOME=/opt/homebrew/opt/openjdk@17

# Android SDK
brew install --cask android-sdk
export ANDROID_HOME=$HOME/Library/Android/sdk

# NDK + CMake
sdkmanager "ndk;26.1.10909125" "cmake;3.22.1"
```

---

## Common Build Errors

### Error: `Could not find method ksp() for arguments`

**Cause:** KSP plugin not applied or version mismatch.

**Fix:** Ensure `build.gradle.kts` (root) has:
```kotlin
id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
```
And `app/build.gradle.kts` has:
```kotlin
id("com.google.devtools.ksp")
```

---

### Error: `Could not load module <Error module>` (kapt)

**Cause:** Old kapt configuration still present. This project uses KSP, not kapt.

**Fix:** Ensure NO `kapt(...)` lines exist in `app/build.gradle.kts`. All annotation processing should use `ksp(...)`:
```kotlin
// ✅ Correct (KSP)
ksp("androidx.room:room-compiler:2.7.0-alpha12")
ksp("com.google.dagger:hilt-android-compiler:2.52")
ksp("androidx.hilt:hilt-compiler:1.2.0")

// ❌ Wrong (kapt — DO NOT USE)
// kapt("androidx.room:room-compiler:...")
```

---

### Error: `Unresolved reference: kapt` or `kapt` plugin not found

**Cause:** The `kapt` plugin was removed. KSP is used instead.

**Fix:** Remove any `id("org.jetbrains.kotlin.kapt")` from plugin blocks. Replace with:
```kotlin
id("com.google.devtools.ksp")
```

---

### Error: `NDK not configured` or `ndk-build not found`

**Cause:** NDK r26b not installed or `ANDROID_NDK_HOME` not set.

**Fix:**
```bash
# Install NDK
sdkmanager "ndk;26.1.10909125"

# Set environment
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125
```

See [NDK Setup](#ndk-setup) for detailed instructions.

---

### Error: `CMake 3.22.1 not found`

**Cause:** CMake not installed or wrong version.

**Fix:**
```bash
# Via Android SDK
sdkmanager "cmake;3.22.1"

# Or install system CMake
# Ubuntu: sudo apt install cmake
# macOS:  brew install cmake
```

---

### Error: `Execution failed for task ':app:mergeDebugNativeLibs'`

**Cause:** Missing JNI libs (sherpa-onnx).

**Fix:**
```bash
./scripts/setup-sherpa-onnx.sh
```

This downloads `libsherpa-onnx-jni.so` and `libonnxruntime.so` to `app/src/main/jniLibs/arm64-v8a/`.

---

### Error: `OutOfMemoryError: Java heap space`

**Cause:** Gradle JVM heap too small for large model files.

**Fix:** In `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
```

---

### Error: `Duplicate class` or dependency version conflict

**Cause:** Transitive dependency version mismatch.

**Fix:** Check dependency tree:
```bash
./gradlew app:dependencies --configuration debugRuntimeClasspath | grep -i "conflict\|->"
```

Common conflicts:
- `kotlinx-serialization-json` versions (must be 1.7.3+ for Kotlin 2.0)
- `kotlinx-coroutines` versions (must be 1.9.0+)
- Multiple ONNX Runtime versions

---

### Error: `Unsupported class file major version 65` (Java 21 bytecode)

**Cause:** JDK 21+ compiling to Java 21 bytecode, but AGP requires Java 17.

**Fix:** Ensure JDK 17 is used (not 21):
```bash
export JAVA_HOME=/path/to/jdk17
./gradlew assembleDebug
```

---

### Error: Room schema export missing

**Cause:** KSP schema location not configured.

**Fix:** Already configured in `app/build.gradle.kts`:
```kotlin
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}
```

If schema directory is missing, create it:
```bash
mkdir -p app/schemas
```

---

## NDK Setup

### What the NDK is Used For

The NDK compiles native C/C++ code for Sherpa-ONNX (offline ASR/TTS/VAD engine). The `CMakeLists.txt` at `app/src/main/cpp/CMakeLists.txt` defines the native build.

### Installation

```bash
# Method 1: Via sdkmanager (recommended)
sdkmanager "ndk;26.1.10909125"

# Method 2: Download manually
# https://developer.android.com/ndk/downloads
# Extract to $ANDROID_HOME/ndk/26.1.10909125/
```

### Environment Variables

```bash
export ANDROID_HOME=$HOME/Android/Sdk        # or ~/Library/Android/sdk on macOS
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125
```

### Verify Installation

```bash
ls $ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake
# Should exist — confirms NDK is properly installed
```

### Version Mismatch Errors

If you see `NDK version X.X.X did not match requested version`:

```bash
# Check what the project expects
grep ndkVersion app/build.gradle.kts
# Output: ndkVersion = "26.1.10909125"

# Install exactly that version
sdkmanager "ndk;26.1.10909125"
```

---

## Model File Setup

### Required Models

| Model | Purpose | Size | Location |
|-------|---------|------|----------|
| `ggml-tiny.en-q5_1.bin` | Whisper ASR (speech-to-text) | ~75MB | `app/src/main/assets/models/` |
| `piper-swahili.onnx` | Piper TTS (text-to-speech) | ~40MB | `app/src/main/assets/models/` |
| `qwen3.5-0.8b-q4_k_m.gguf` | Qwen 3.5 0.8B LLM | ~580MB | `app/src/main/assets/models/` |
| `silero_vad.onnx` | Voice Activity Detection | ~2MB | `app/src/main/assets/models/` |
| `gemma-4-e2b-q4_k_m.gguf` | Gemma 4 E2B (alternative LLM) | ~500MB | `app/src/main/assets/models/` |

### Download Models

```bash
# Download all models
./scripts/download-models.sh

# Verify models only (no download)
./scripts/download-models.sh --verify
```

### Manual Model Setup

If the download script fails:

1. Create the models directory:
   ```bash
   mkdir -p app/src/main/assets/models
   ```

2. Download each model manually from its source (see `scripts/download-models.sh` for URLs)

3. Verify checksums match what the CI expects

### Models Not Bundled (APK too small)

If your APK is < 100MB, models are likely not bundled. Check:

```bash
# List what's in the APK
unzip -l app/build/outputs/apk/debug/*.apk | grep models/
```

If empty, ensure models exist at build time:
```bash
ls -lh app/src/main/assets/models/
```

### Model Compression

Models use `noCompress` in `build.gradle.kts` — they're stored uncompressed for memory-mapped access. This is intentional and necessary for GGUF/ONNX inference performance.

---

## CI/CD Debugging

### GitHub Actions Workflow Overview

| Workflow | Trigger | Purpose |
|----------|---------|---------|
| `ci.yml` | push/PR to main, develop | Full pipeline: lint → test → build → release |
| `build.yml` | push/PR to main | Simple build-only pipeline |
| `release.yml` | push to main | Signed release + GitHub Release |
| `auto-fix.yml` | scheduled | Auto-fix common issues |
| `pre-commit.yml` | push/PR | Pre-commit validation |

### Common CI Failures

#### 1. NDK/CMake cache miss

**Symptom:** `Install NDK r26b and CMake 3.22.1` step runs every time.

**Fix:** The cache key `ndk-cmake-${{ env.NDK_VERSION }}-${{ env.CMAKE_VERSION }}` should be stable. If it's changing, check that `NDK_VERSION` and `CMAKE_VERSION` env vars are consistent.

#### 2. Model download timeout

**Symptom:** `Download AI Models` step times out after 30 minutes.

**Fix:**
- Increase timeout in the workflow
- Use a mirror or pre-baked cache
- Check network connectivity to model download URLs

#### 3. sherpa-onnx JNI libs missing

**Symptom:** Build fails with `UnsatisfiedLinkError` or JNI verification fails.

**Fix:** Ensure `scripts/setup-sherpa-onnx.sh` runs before build. Check the workflow has the sherpa-onnx cache and setup steps.

#### 4. Release signing fails

**Symptom:** `RELEASE_KEYSTORE_FILE` env var not set or keystore decode fails.

**Fix:** Verify GitHub repository secrets:
- `ANDROID_KEYSTORE_BASE64` — base64-encoded `.keystore` file
- `RELEASE_STORE_PASSWORD` — keystore password
- `RELEASE_KEY_ALIAS` — key alias (e.g., `msaidizi-release`)
- `RELEASE_KEY_PASSWORD` — key password

The workflow decodes the base64 keystore to `/tmp/release.keystore` and passes the path via `RELEASE_KEYSTORE_FILE` env var.

#### 5. Detekt lint failures

**Symptom:** `detektMain` or `detektTest` tasks fail.

**Fix:**
```bash
# Run detekt locally to see issues
./gradlew detektMain detektTest --continue

# Auto-format
./gradlew detektFormat
```

#### 6. Unit test failures

**Symptom:** `testDebugUnitTest` fails.

**Fix:**
```bash
# Run tests locally
./gradlew testDebugUnitTest --stacktrace

# Check test report
open app/build/reports/tests/testDebugUnitTest/index.html
```

### Debugging CI Locally

Use [act](https://github.com/nektos/act) to run GitHub Actions locally:

```bash
# Install act
brew install act  # macOS
# or: curl -s https://raw.githubusercontent.com/nektos/act/master/install.sh | sudo bash

# Run the build job
act -j build --container-architecture linux/amd64

# Run with secrets
act -j release-build --secret-file .env
```

### Workflow Artifact Inspection

Download artifacts from the GitHub Actions run page:
- `debug-apk` — The built APK
- `build-debug-log` — Full Gradle build log (on failure)
- `detekt-reports` — Detekt lint results
- `test-results` — Unit test results + coverage
- `model-integrity` — Model checksum verification

---

## Dependency Issues

### Version Compatibility Matrix

| Dependency | Version | Kotlin Compat | Notes |
|-----------|---------|--------------|-------|
| Kotlin | 2.0.21 | — | Base version |
| KSP | 2.0.21-1.0.28 | 2.0.21 | Must match Kotlin version |
| Hilt | 2.52 | 2.0+ | KSP-based |
| Room | 2.7.0-alpha12 | 2.0+ | KSP-based, KMP support |
| Ktor | 3.0.3 | 2.0+ | Pulls kotlinx-serialization 1.7.x |
| Coroutines | 1.9.0 | 2.0+ | — |
| Serialization | 1.7.3 | 2.0+ | — |
| AGP | 8.7.3 | — | Requires Gradle 8.9+ |
| Gradle | 8.11.1 | — | — |

### Checking for Conflicts

```bash
# Full dependency tree
./gradlew app:dependencies --configuration debugRuntimeClasspath

# Check for version conflicts
./gradlew app:dependencyInsight --dependency kotlinx-serialization-json --configuration debugRuntimeClasspath

# Check Kotlin version alignment
./gradlew app:dependencyInsight --dependency org.jetbrains.kotlin --configuration debugRuntimeClasspath
```

### Common Dependency Fixes

**Serialization version mismatch:**
```kotlin
// Ensure 1.7.3+ for Kotlin 2.0
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
```

**Coroutines version mismatch:**
```kotlin
// Ensure 1.9.0+ for Kotlin 2.0
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
```

---

## Signing Issues

### Debug Signing

Debug builds use an auto-generated keystore. If you see signing errors:

```bash
# Option 1: Let the build script generate it
./build-full-apk.sh

# Option 2: Generate manually
keytool -genkeypair -v \
    -keystore debug.keystore \
    -alias androiddebugkey \
    -keyalg RSA -keysize 2048 \
    -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=Android Debug,O=Msaidizi,C=KE"
```

### Release Signing

1. Generate a keystore:
   ```bash
   ./scripts/generate-keystore.sh
   ```

2. Fill in `keystore.properties`:
   ```properties
   storeFile=release.keystore
   storePassword=your-password
   keyAlias=msaidizi-release
   keyPassword=your-key-password
   ```

3. Build:
   ```bash
   ./gradlew assembleRelease
   ```

### CI Release Signing

Set these GitHub repository secrets:

| Secret | Description |
|--------|-------------|
| `ANDROID_KEYSTORE_BASE64` | `base64 release.keystore` output |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias (e.g., `msaidizi-release`) |
| `RELEASE_KEY_PASSWORD` | Key password |

Encode keystore for CI:
```bash
base64 -w0 release.keystore
# Copy output to ANDROID_KEYSTORE_BASE64 secret
```

---

## Performance Tips

### Speed Up Builds

```bash
# Enable Gradle daemon (default, but verify)
./gradlew assembleDebug --daemon

# Parallel execution (already in gradle.properties)
org.gradle.parallel=true

# Build cache (already in gradle.properties)
org.gradle.caching=true

# Increase JVM heap for large model handling
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8

# Build only debug (skip release)
./gradlew assembleDebug

# Skip tests during development
./gradlew assembleDebug -x test
```

### CI Cache Strategy

The workflows cache:
1. **Gradle dependencies** — via `gradle/actions/setup-gradle@v4`
2. **NDK + CMake** — via `actions/cache@v4` (keyed by version)
3. **AI models** — via `actions/cache@v4` (keyed by model version)
4. **Sherpa-ONNX JNI libs** — via `actions/cache@v4`

If caches are stale, delete them from GitHub repo settings → Actions → Caches.

---

## Quick Reference

### Build Commands

```bash
# Full build with all checks
./build-full-apk.sh

# Quick debug build
./build-debug.sh

# Release build
./build-full-apk.sh --release

# Check prerequisites only
./build-full-apk.sh --check-only

# Dependency resolution only
./build-full-apk.sh --deps-only

# Clean build
./build-full-apk.sh --clean

# Skip model setup (if models already present)
./build-full-apk.sh --skip-models

# Gradle direct
./gradlew assembleDebug --stacktrace
./gradlew assembleRelease --stacktrace
```

### Useful Diagnostics

```bash
# Gradle version info
./gradlew --version

# Project structure
./gradlew projects

# Build environment
./gradlew buildEnvironment

# Dependency report
./gradlew app:dependencies --configuration debugRuntimeClasspath

# Task list
./gradlew tasks --group=build
```
