# Msaidizi Build Fix Report

**Date:** 2026-07-16
**Engineer:** Swarm 6 — Build & CI Fix

---

## Executive Summary

Fixed all build configuration issues, upgraded the Kotlin toolchain from 1.9.24 to 2.0.21, resolved a critical syntax error in `app/build.gradle.kts`, updated all Qwen 0.5B model references to Qwen 3.5 0.8B across 15+ source files, and modernized the CI/CD pipeline.

---

## Phase 1: Issues Found

### Critical Build Errors
1. **Syntax error in `app/build.gradle.kts`** — Misplaced closing brace in `packaging {}` block caused `jniLibs {}` to be outside the `android {}` scope. The `aaptOptions {}` block was also outside `android {}`.
2. **Gradle 8.5 incompatible with AGP 8.7.3** — AGP 8.7.x requires Gradle 8.9+.

### Version Conflicts
3. **Kotlin 1.9.24** — outdated; Ktor 3.x, Room 2.7.x, and kotlinx-serialization 1.7.x require Kotlin 2.0+.
4. **`resolutionStrategy.force` hacks** — 6 force rules masking version conflicts between Kotlin 1.9.x and transitive deps.
5. **Ktor 2.3.12** — last Kotlin 1.9.x-compatible series; blocked upgrade path.
6. **kotlinx-serialization 1.6.3** — pinned to Kotlin 1.9.x; force-blocked by resolutionStrategy.
7. **Room 2.6.1** — lacks Kotlin 2.0 KSP support.
8. **Hilt 2.51.1** — lacks Kotlin 2.0 KSP support.
9. **Java 11 target** — suboptimal for Kotlin 2.0 and modern Android.

### Model References (Qwen 0.5B → 3.5 0.8B)
10. **15+ source files** still referenced `qwen-0.5b-q4km` or `qwen-0.5b-q4_k_m.gguf`.
11. **RAM estimates** were ~350MB (0.5B model) instead of ~500MB (0.8B model).
12. **Token/sec estimates** were ~15 tok/s (0.5B) instead of ~10 tok/s (0.8B).

---

## Phase 2: Changes Made

### 2.1 Root `build.gradle.kts`

| Plugin | Before | After |
|--------|--------|-------|
| AGP | 8.2.2 | 8.7.3 |
| Kotlin | 1.9.24 | 2.0.21 |
| KSP | 1.9.24-1.0.20 | 2.0.21-1.0.28 |
| Hilt | 2.51.1 | 2.52 |
| kotlinx-serialization | 1.9.24 | 2.0.21 |

### 2.2 App `build.gradle.kts`

| Dependency | Before | After |
|-----------|--------|-------|
| Room | 2.6.1 | 2.7.0-alpha12 |
| Hilt | 2.51.1 | 2.52 |
| kotlin-reflect | 1.9.24 | 2.0.21 |
| kotlinx-serialization-json | 1.6.3 | 1.7.3 |
| Ktor (all modules) | 2.3.12 | 3.0.3 |
| Java target | 11 | 17 |
| jvmTarget | "11" | "17" |

**Removed:**
- Entire `configurations.all { resolutionStrategy { force(...) } }` block (6 force rules)
- `aaptOptions {}` block (replaced with `androidResources {}` inside `android {}`)

**Fixed:**
- Syntax error: misplaced `}` in `packaging {}` that put `jniLibs` outside `android {}`
- `aaptOptions { noCompress(...) }` → `androidResources { noCompress += listOf(...) }` (modern API)
- `ksp.incremental` changed from `false` to `true` (Kotlin 2.0 KSP handles cross-processor resolution)
- `room.incremental` changed from `false` to `true`

### 2.3 `gradle.properties`

| Property | Before | After |
|----------|--------|-------|
| `org.gradle.jvmargs` | `-Xmx2048m` | `-Xmx4096m` |
| `ksp.incremental` | `false` | `true` |

### 2.4 Gradle Wrapper

| Property | Before | After |
|----------|--------|-------|
| Gradle version | 8.5 | 8.11.1 |

### 2.5 Model Reference Updates (Qwen 0.5B → 3.5 0.8B)

| File | Change |
|------|--------|
| `LlamaCppEngine.kt` | Updated `loadDefaultModel()` path from `qwen-0.5b-q4_k_m.gguf` → `qwen3.5-0.8b-q4_k_m.gguf`. Updated RAM/tok-s estimates. |
| `LlmEngine.kt` | Updated model path and RAM estimates (~350MB → ~500MB). |
| `ReasoningModelManager.kt` | Updated all comments from "Qwen 0.5B" → "Qwen 3.5 0.8B". Updated latency estimates. |
| `ModelRouter.kt` | Updated comments. Removed old `qwen-0.5b` from version options. |
| `ExpertRegistry.kt` | Updated comment reference. |
| `MoERouter.kt` | Updated expert model ID from `qwen-0.5b-fl-sw` → `qwen-3.5-0.8b-q4km`. |
| `ModelVersionManager.kt` | Marked QWEN_0_5B as "Deprecated", QWEN3_5_0_8B as "Current". Updated size to 580MB. |
| `BundledModelManager.kt` | Updated `BUNDLED_MODEL_ID` and bundled asset filename. |
| `ModelDownloader.kt` | Updated model ID from `qwen-0.5b-q4km` → `qwen-3.5-0.8b-q4km`. |
| `ModelDownloadManager.kt` | Updated model ID, size (300MB → 580MB), and download URL. |
| `OnboardingConversation.kt` | Updated model ID in download priority list. |
| `ModelDownloadFragment.kt` | Updated model ID in UI handler. |
| `ModelDownloadViewModel.kt` | Updated model ID in display name/description maps. |
| `VoiceModelRegistry.kt` | Updated comment references. |
| `SdCardModelLoader.kt` | Updated example model path. |
| `.gitkeep` (res/raw) | Updated model filename reference. |

### 2.6 CI/CD Pipeline Updates

| Workflow | Change |
|----------|--------|
| `build.yml` | Model cache key: `models-v2-*` → `models-v3-whisper-piper-qwen35-08b` |
| `ci.yml` | Model cache key: `models-v2-*` → `models-v3-whisper-piper-qwen35-08b` (2 occurrences) |
| `auto-fix.yml` | Model cache key updated. |
| `release.yml` | Model cache key updated. |
| `auto-release.yml` | Model cache key updated. Gradle setup action: `v3` → `v4`, removed pinned `gradle-version: '8.5'`. |

---

## Phase 3: Backward Compatibility

### Model ID Alias
The `ModelRegistry.MODEL_ID_ALIASES` map preserves backward compatibility:
```kotlin
"qwen-0.5b-q4km" to "qwen-3.5-0.8b-q4km"
```
Any code using the old `qwen-0.5b-q4km` ID will be automatically resolved to the new ID.

### Deprecated Enum Entry
`ModelVersionManager.ModelVersion.QWEN_0_5B` is retained and labeled "Deprecated" for version tracking/history purposes.

---

## Phase 4: Manual Steps Required

1. **Model File Download** — Run `./scripts/download-models.sh` to download the Qwen 3.5 0.8B GGUF (~580MB) before building.

2. **Sherpa-ONNX JNI Libs** — Run `./scripts/setup-sherpa-onnx.sh` to install native libraries for ASR/TTS/VAD.

3. **CI Cache Invalidation** — The model cache key changed from `models-v2-*` to `models-v3-*`. The first CI run after this change will re-download all models (~610MB total).

4. **Release Keystore** — For release builds, ensure `keystore.properties` or CI secrets are configured (see `app/build.gradle.kts` signing config comments).

5. **SHA-256 Hashes** — The Qwen 3.5 0.8B model hash in `ModelRegistry.kt` (`fb044e93...`) should be verified against the actual downloaded file before production release.

---

## Phase 5: Build Verification Checklist

- [x] Root `build.gradle.kts` — all plugin versions consistent (Kotlin 2.0.21)
- [x] App `build.gradle.kts` — no syntax errors, all deps upgraded
- [x] `gradle.properties` — KSP incremental enabled, JVM heap increased
- [x] Gradle wrapper — 8.11.1 (compatible with AGP 8.7.3)
- [x] No `resolutionStrategy.force` hacks remaining
- [x] All Qwen 0.5B references updated (15+ files)
- [x] Model download script already uses Qwen 3.5 0.8B URLs
- [x] CI cache keys updated to force fresh model download
- [x] `androidResources { noCompress }` uses modern API
- [x] Java 17 target throughout

---

## Dependency Version Matrix (Final)

| Component | Version | Notes |
|-----------|---------|-------|
| AGP | 8.7.3 | Requires Gradle 8.9+ |
| Gradle | 8.11.1 | Latest 8.x stable |
| Kotlin | 2.0.21 | Latest 2.0.x stable |
| KSP | 2.0.21-1.0.28 | Matches Kotlin 2.0.21 |
| Hilt | 2.52 | Kotlin 2.0 KSP support |
| Room | 2.7.0-alpha12 | Kotlin 2.0 KSP support |
| Ktor | 3.0.3 | Requires Kotlin 2.0+ |
| kotlinx-serialization | 1.7.3 | Kotlin 2.0+ native |
| kotlinx-coroutines | 1.9.0 | Compatible |
| compileSdk | 35 | Android 15 |
| targetSdk | 35 | Android 15 |
| minSdk | 26 | Android 8.0 |
| NDK | r26b (26.1.10909125) | Stable with AGP 8.x |
| CMake | 3.22.1 | Required by native build |
| Java target | 17 | Modern Android standard |
