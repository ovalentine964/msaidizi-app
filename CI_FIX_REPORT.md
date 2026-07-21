# CI/CD Pipeline Fix Report

**Date:** 2026-07-21  
**Scope:** All GitHub Actions workflows + detekt configuration

---

## Issues Found & Fixed

### 1. 🔴 CRITICAL: Product Flavor Task Names (All Workflows)

**Problem:** The `app/build.gradle.kts` adds product flavors (`full`, `lite`, `play`) with `flavorDimensions += "distribution"`. This changes all Gradle task names:
- `assembleDebug` → `assembleFullDebug`
- `assembleRelease` → `assembleFullRelease`
- `bundleRelease` → `bundleFullRelease`
- `testDebugUnitTest` → `testFullDebugUnitTest`

And output paths:
- `app/build/outputs/apk/debug/` → `app/build/outputs/apk/full/debug/`
- `app/build/outputs/apk/release/` → `app/build/outputs/apk/full/release/`
- `app/build/outputs/bundle/release/` → `app/build/outputs/bundle/full/release/`

**Impact:** Every CI workflow would fail with "Task 'assembleDebug' not found".

**Fix:** Updated all 5 build workflows (`build.yml`, `ci.yml`, `auto-fix.yml`, `auto-release.yml`, `release.yml`) to use the `full` flavor task names and output paths.

### 2. 🟡 HIGH: Sherpa-ONNX Cache Key Mismatch (All Workflows)

**Problem:** Cache key was `sherpa-onnx-jni-v2-arm64` but `setup-sherpa-onnx.sh` downloads libs for **both** `arm64-v8a` and `armeabi-v7a`. Cache misses would cause unnecessary re-downloads.

**Fix:** Updated cache key to `sherpa-onnx-jni-v2-all` across all 5 build workflows (8 occurrences).

### 3. 🟡 HIGH: auto-release.yml Missing Release Signing

**Problem:** `auto-release.yml` builds `assembleRelease` but never decodes the release keystore from secrets. The "release" APK was always signed with the debug keystore.

**Fix:** Added the `Decode release keystore` step (matching `build.yml` and `release.yml` pattern) before the build step.

### 4. 🟡 MEDIUM: detekt.yml Duplicate MaxLineLength Key

**Problem:** `config/detekt/detekt.yml` had `MaxLineLength` defined twice under `style:` — first with `maxLineLength: 120`, then again with `maxLineLength: 150` and `ignoreBackTickedIdentifier: true`. YAML silently uses the last value, making the first one dead config.

**Fix:** Merged into a single `MaxLineLength` entry with `maxLineLength: 150` and `ignoreBackTickedIdentifier: true`.

### 5. 🟢 LOW: No CMake FetchContent Cache (All Build Workflows)

**Problem:** The CMake build fetches llama.cpp source from GitHub via `FetchContent` on every CI run. The llama.cpp repo is ~200MB+, making builds slow on cache misses.

**Fix:** Added `app/.cxx` directory cache with key `cmake-cxx-llama-b4651-{NDK_VERSION}` to all 5 build workflows (7 occurrences across build, ci, auto-fix, auto-release, release).

---

## Files Modified

| File | Changes |
|------|---------|
| `.github/workflows/build.yml` | Flavor tasks, sherpa cache key, CMake cache |
| `.github/workflows/ci.yml` | Flavor tasks, sherpa cache key, CMake cache |
| `.github/workflows/auto-fix.yml` | Flavor tasks, sherpa cache key, CMake cache |
| `.github/workflows/auto-release.yml` | Flavor tasks, sherpa cache key, CMake cache, release signing |
| `.github/workflows/release.yml` | Flavor tasks, sherpa cache key, CMake cache |
| `config/detekt/detekt.yml` | Removed duplicate MaxLineLength key |

---

## Pre-Existing Changes (Already in Working Tree)

These changes were already present before this fix session:

1. **Product flavors** (`app/build.gradle.kts`) — `full`/`lite`/`play` distribution flavors
2. **Model filename case fix** — `qwen3.5-0.8b-q4_k_m.gguf` → `Qwen3.5-0.8B-Q4_K_M.gguf`
3. **CMakeLists.txt** — Local vendor fallback for llama.cpp + timeout
4. **download-models.sh** — `--lite` flag support, separated voice/LLM models
5. **DatabaseModule.kt** — New Hilt module for SQLCipher-encrypted Room database
6. **BundledModelManager.kt** — Flavor-aware model bundling
7. **setupSherpaOnnx task** — Gradle task that runs sherpa-onnx setup before build

---

## CI Pipeline Summary (Post-Fix)

### Build Pipeline (build.yml)
```
Checkout → JDK 17 → Gradle Setup → NDK/CMake Install
  → CMake FetchContent Cache → Debug Keystore
  → AI Models Cache → Sherpa-ONNX JNI Cache
  → assembleFullDebug → APK Size Check → Upload Artifact
```

### CI Pipeline (ci.yml)
```
Secret Scan (gate)
  ├→ Detekt Lint
  ├→ Unit Tests (testFullDebugUnitTest)
  ├→ OWASP Dependency Check
  ├→ Build Debug (assembleFullDebug)
  └→ Release Build (assembleFullRelease, main only)
```

### Key Environment Variables
- `NDK_VERSION`: 26.1.10909125 (r26b)
- `CMAKE_VERSION`: 3.22.1
- `JAVA_VERSION`: 17 (Temurin)
- `Gradle`: 8.11.1
- `AGP`: 8.7.3
- `Kotlin`: 2.0.21
