# CI/CD Pipeline Fix Report

**Date:** 2026-07-15  
**Scope:** All 6 workflow files + 2 build scripts

---

## Issues Found & Fixed

### 🔴 Critical Issues (Build Blockers)

| # | File | Issue | Fix |
|---|------|-------|-----|
| 1 | `ci.yml` build job | **No model download step** — build would fail because models aren't in assets | Added model cache + download steps matching build.yml |
| 2 | `ci.yml` release-build job | **No model download step** — release build would fail | Added model cache + download steps |
| 3 | `ci.yml` build job | **No NDK/CMake install** — native CMake build would fail | Added NDK/CMake cache + install steps |
| 4 | `ci.yml` release-build job | **No NDK/CMake install** | Added NDK/CMake cache + install steps |
| 5 | `auto-fix.yml` | **Used bare `gradle` instead of `./gradlew`** — would fail if Gradle not on PATH | Changed to `./gradlew` |
| 6 | `auto-fix.yml` | **No NDK/CMake, no models, no keystore** — build would fail | Added all missing setup steps |
| 7 | `download-models.sh` | **Broken fallback URL** (`Qwen/Qwen3.5-0.8B-GGUF` returns 401) | Replaced with working `unsloth/Qwen3-0.6B-GGUF` fallback |

### 🟡 Significant Issues (Reliability/Correctness)

| # | File | Issue | Fix |
|---|------|-------|-----|
| 8 | `build.yml` | Used `setup-gradle@v3` — outdated | Upgraded to `@v4` |
| 9 | `release.yml` | Used `setup-gradle@v3` — outdated | Upgraded to `@v4` |
| 10 | `build.yml` | Double-cached Gradle (setup-gradle + actions/cache) — redundant | Removed redundant `actions/cache` step |
| 11 | `release.yml` | Double-cached Gradle | Removed redundant `actions/cache` step |
| 12 | `build.yml` | Build step `echo "Gradle exit code: $?"` doesn't capture pipe failures | Used `PIPESTATUS[0]` for proper exit code capture |
| 13 | `ci.yml` build job | Same pipe exit code issue | Fixed with `PIPESTATUS[0]` |
| 14 | `release.yml` | Same pipe exit code issue in both AAB and APK build steps | Fixed with `PIPESTATUS[0]` |
| 15 | `ci.yml` model-integrity job | Ran on fresh checkout without models — would always skip | Changed to download APK artifact and verify models inside it |
| 16 | `release.yml` | Keystore decode `if` condition used env var that's never set | Changed to `${{ secrets.RELEASE_KEYSTORE_BASE64 != '' }}` |
| 17 | `download-models.sh` | Verify mode didn't exit with error on missing models | Added `VERIFY_FAILED` tracking and proper exit code |
| 18 | `manual-build.sh` | Step numbering jumped from 1 to 3 (missing step 2) | Renumbered all steps, added model download + keystore generation |

### 🟢 Optimizations

| # | File | Optimization | Impact |
|---|------|-------------|--------|
| 19 | All workflows | **NDK/CMake caching** — avoids 2-3 min download every run | ~3 min saved per job |
| 20 | All workflows | **Consistent `GRADLE_OPTS` env** — disables daemon, enables parallel + caching | Faster builds |
| 21 | `build.yml` | Timeout raised from 60 → 90 min | Prevents timeout on slow model downloads |
| 22 | `ci.yml` build job | Timeout raised from 25 → 60 min | Adequate time for model download + build |
| 23 | `ci.yml` release-build | Timeout raised from 30 → 90 min | Adequate for full release build |
| 24 | `auto-fix.yml` | Added `timeout-minutes: 45` | Prevents runaway builds |
| 25 | `pre-commit.yml` | Added `timeout-minutes: 15` | Prevents runaway validation |
| 26 | `8-dimension-validation.yml` | Added `timeout-minutes: 10` | Prevents runaway checks |
| 27 | `ci.yml` dependency-check | Added OWASP data cache | Avoids re-downloading CVE database |
| 28 | `ci.yml` lint job | Fixed Detekt error detection — `grep "error"` was too broad | Changed to `grep "Severity.*error"` |

---

## Model URL Verification

| Model | URL | Status |
|-------|-----|--------|
| Whisper tiny.en Q5_1 | `huggingface.co/ggerganov/whisper.cpp/.../ggml-tiny.en-q5_1.bin` | ✅ 200 |
| Piper Swahili ONNX | `huggingface.co/rhasspy/piper-voices/.../sw_CD-lanfrica-medium.onnx` | ✅ 200 |
| Piper Swahili config | `huggingface.co/rhasspy/piper-voices/.../sw_CD-lanfrica-medium.onnx.json` | ✅ 200 |
| Qwen3.5-0.8B Q4_K_M (primary) | `huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/.../Qwen3.5-0.8B-Q4_K_M.gguf` | ✅ 200 |
| Qwen3.5-0.8B Q4_K_M (old fallback) | `huggingface.co/Qwen/Qwen3.5-0.8B-GGUF/...` | ❌ 401 (removed) |
| Qwen3-0.6B Q4_K_M (new fallback) | `huggingface.co/unsloth/Qwen3-0.6B-GGUF/.../Qwen3-0.6B-Q4_K_M.gguf` | ✅ 200 |

---

## Cache Strategy

### What's Cached

| Cache | Key | TTL | Impact |
|-------|-----|-----|--------|
| Gradle (wrapper + caches) | Built into `setup-gradle@v4` | Auto | ~2 min saved |
| NDK r26b + CMake 3.22.1 | `ndk-cmake-26.1.10909125-3.22.1` | Manual invalidation | ~3 min saved |
| AI Models | `models-v2-whisper-piper-qwen` | Manual bump | ~15 min saved |
| OWASP DC database | `owasp-dc-data-Linux` | Auto | ~5 min saved |

### Cache Invalidation

- **NDK/CMake:** Bump version in `NDK_VERSION`/`CMAKE_VERSION` env vars
- **Models:** Bump `models-v2-*` → `models-v3-*` in cache key
- **Gradle:** Automatic on `*.gradle*` or `gradle-wrapper.properties` change

---

## Files Changed

```
.github/workflows/build.yml         — NDK cache, Gradle v4, pipe exit codes, timeout
.github/workflows/ci.yml            — NDK cache, model download, model-integrity fix, timeouts
.github/workflows/release.yml       — NDK cache, Gradle v4, pipe exit codes, timeout
.github/workflows/auto-fix.yml      — ./gradlew, NDK/models/keystore, timeout
.github/workflows/pre-commit.yml    — timeout
.github/workflows/8-dimension-validation.yml — timeout
scripts/download-models.sh          — fixed URLs, proper verify exit codes
scripts/manual-build.sh             — fixed step numbering, added model download
CI_FIX.md                           — this report
```

---

## Build Pipeline Flow (After Fix)

```
push/PR → secret-scan
              ├→ lint ─────────────────┐
              ├→ unit-tests ───────────┤
              └→ dependency-check ─────┤
                                       ▼
                                   build (with NDK cache + model cache)
                                       │
                                       ▼
                               model-integrity (verifies APK contents)
                                       │
                                       ▼
                           release-build (main only, signed)
```

---

## Remaining Recommendations

1. **Sherpa-ONNX JNI libs:** The `build.gradle.kts` references `scripts/setup-sherpa-onnx.sh` for JNI libs but this script doesn't exist. If sherpa-onnx is needed for voice features, create this script and add it to CI.
2. **Release signing:** The release build type has signing commented out in `build.gradle.kts`. For production, uncomment and configure the release signing config.
3. **Model hash pinning:** The model-integrity job has `PLACEHOLDER` hashes. Compute actual SHA-256 hashes after first successful build and pin them.
4. **Dependency check suppressions:** `.github/dependency-check-suppressions.xml` exists — review it periodically.
5. **8-dimension validation:** Currently informational only (no `exit 1` on missing files). Consider making critical dimensions fail the gate.
