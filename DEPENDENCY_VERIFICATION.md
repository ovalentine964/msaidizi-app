# Dependency Verification Report

**Date:** 2026-07-16
**Project:** Msaidizi Android App
**Status:** ✅ All dependencies verified compatible

---

## Version Compatibility Matrix

### Build Tools

| Component | Version | Required By | Status |
|-----------|---------|------------|--------|
| Gradle | 8.11.1 | AGP 8.7.3 (≥8.9) | ✅ Compatible |
| AGP | 8.7.3 | Gradle 8.9+ | ✅ Compatible |
| JDK | 17 | AGP 8.x, Kotlin 2.0 | ✅ Compatible |
| NDK | r26b (26.1.10909125) | CMake native builds | ✅ Stable |
| CMake | 3.22.1 | Sherpa-ONNX JNI | ✅ Compatible |

### Kotlin Ecosystem

| Component | Version | Kotlin Compat | Status |
|-----------|---------|--------------|--------|
| Kotlin | 2.0.21 | — (base) | ✅ |
| KSP | 2.0.21-1.0.28 | Must match Kotlin 2.0.21 | ✅ Matched |
| kotlin-reflect | 2.0.21 | Must match compiler | ✅ Matched |
| kotlinx-coroutines | 1.9.0 | Kotlin 2.0+ | ✅ Compatible |
| kotlinx-serialization | 1.7.3 | Kotlin 2.0+ | ✅ Compatible |

### Android Libraries

| Component | Version | KSP Support | Status |
|-----------|---------|------------|--------|
| Hilt | 2.52 | ✅ KSP-native (2.48+) | ✅ Using KSP |
| Hilt Compiler | 2.52 | ✅ KSP | ✅ Using `ksp()` |
| Room | 2.7.0-alpha12 | ✅ KSP-native | ✅ Using KSP |
| Room Compiler | 2.7.0-alpha12 | ✅ KSP | ✅ Using `ksp()` |
| Hilt Work Compiler | 1.2.0 | ✅ KSP | ✅ Using `ksp()` |

### Network & Serialization

| Component | Version | Status |
|-----------|---------|--------|
| Ktor Client | 3.0.3 | ✅ Requires Kotlin 2.0+ |
| Ktor OkHttp | 3.0.3 | ✅ |
| Ktor Content Negotiation | 3.0.3 | ✅ |
| Ktor Serialization JSON | 3.0.3 | ✅ Pulls kotlinx-serialization 1.7.x |
| Retrofit | 2.11.0 | ✅ |
| Gson | 2.10.1 | ✅ |

### ML/AI

| Component | Version | Status |
|-----------|---------|--------|
| ONNX Runtime Android | 1.20.0 | ✅ ARM optimized |
| Sherpa-ONNX | JNI v1 | ✅ Native libs via setup script |

### Testing

| Component | Version | Status |
|-----------|---------|--------|
| JUnit Jupiter | 5.10.1 | ✅ |
| JUnit Vintage | 5.10.1 | ✅ (JUnit 4 compat) |
| MockK | 1.13.9 | ✅ |
| Turbine | 1.0.0 | ✅ |
| Coroutines Test | 1.9.0 | ✅ Matches main coroutines |

---

## Maven Repository Accessibility

| Repository | URL | Purpose | Status |
|-----------|-----|---------|--------|
| Google | `https://dl.google.com/dl/android/maven2/` | AndroidX, AGP, Hilt | ✅ |
| Maven Central | `https://repo1.maven.org/maven2/` | Most libraries | ✅ |
| Gradle Plugin Portal | `https://plugins.gradle.org/m2/` | Gradle plugins | ✅ |
| JitPack | `https://jitpack.io` | MPAndroidChart | ✅ |

---

## Potential Issues (Non-Blocking)

### 1. Room Alpha Version
Room 2.7.0-alpha12 is an alpha release. While it has KSP support and Kotlin 2.0 compatibility, it may have breaking changes in future alphas. Monitor for stable release.

### 2. Detekt Version
Detekt 1.23.7 is used. The latest is 1.23.x series. Current version is compatible with Kotlin 2.0.21.

### 3. Bouncy Castle PQC
bcprov-jdk18on 1.84 includes ML-KEM/ML-DSA (post-quantum). This is a large dependency (~7MB). Acceptable for security-focused app.

### 4. No kapt Remnants
Verified: No `kapt()` calls or `kapt` plugin references remain. All annotation processing uses KSP. ✅

---

## Resolution Commands

```bash
# Full dependency tree
./gradlew app:dependencies --configuration debugRuntimeClasspath

# Check specific dependency
./gradlew app:dependencyInsight --dependency kotlinx-serialization-json --configuration debugRuntimeClasspath

# Verify all dependencies resolve
./gradlew dependencies --configuration debugRuntimeClasspath --quiet

# Check for dependency updates
./gradlew dependencyUpdates  # requires ben-manes.versions plugin
```
