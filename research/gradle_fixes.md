# Gradle & Dependency Fixes — Msaidizi Android App

**Date:** 2026-07-27  
**Scope:** Build system only — no app functionality changes

---

## Executive Summary

Found and fixed **5 issues** (2 critical, 3 moderate) across the Gradle build configuration. The two critical issues would have prevented compilation or caused runtime crashes.

---

## Issues Found & Fixes Applied

### 🔴 CRITICAL #1: Missing Kotlin Serialization Plugin

**Problem:** The codebase uses `@Serializable` annotations (in `SyncModels.kt` and `BusinessModels.kt`) and `kotlinx.serialization.json.Json` (in `NetworkModule.kt`), but the `kotlin("plugin.serialization")` Gradle plugin was **never declared**. Without this plugin, the Kotlin compiler does not generate the `serializer()` companion methods for `@Serializable` classes, causing **compilation failure**.

**Evidence:**
- `SyncModels.kt` — 5 classes annotated with `@Serializable`
- `BusinessModels.kt` — 6 classes annotated with `@Serializable`
- `NetworkModule.kt` — uses `kotlinx.serialization.json.Json`

**Fix:** Added `kotlin-serialization` plugin to all three build files:
- `gradle/libs.versions.toml` — added plugin entry: `kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }`
- `build.gradle.kts` — added `alias(libs.plugins.kotlin.serialization) apply false`
- `app/build.gradle.kts` — added `alias(libs.plugins.kotlin.serialization)`

---

### 🔴 CRITICAL #2: Retrofit Converter Package Mismatch

**Problem:** The code in `NetworkModule.kt` imported from Jake Wharton's deprecated converter library:
```kotlin
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
```
But the dependency declared in `libs.versions.toml` was Square's official replacement:
```toml
retrofit-serialization = { group = "com.squareup.retrofit2", name = "converter-kotlinx-serialization", ... }
```
These are **different artifacts with different package names**. The Square artifact exposes `retrofit2.converter.kotlinx.serialization`, not `com.jakewharton.retrofit2.converter.kotlinx.serialization`. This would cause a **compilation error** (unresolved reference).

**Fix:** Updated the import in `NetworkModule.kt` to match the Square dependency:
```kotlin
import retrofit2.converter.kotlinx.serialization.asConverterFactory
```
The function signature (`Json.asConverterFactory()`) is identical — no behavioral change.

---

### 🟡 MODERATE #3: Hardcoded CameraX Dependencies

**Problem:** Three CameraX dependencies were hardcoded with inline version strings in `app/build.gradle.kts`:
```kotlin
implementation("androidx.camera:camera-camera2:1.4.1")
implementation("androidx.camera:camera-lifecycle:1.4.1")
implementation("androidx.camera:camera-view:1.4.1")
```
This bypasses the version catalog, making version management inconsistent.

**Fix:** Migrated to version catalog:
- `libs.versions.toml` — added `cameraX = "1.4.1"` version and 3 library entries (`camera-camera2`, `camera-lifecycle`, `camera-view`)
- `app/build.gradle.kts` — replaced hardcoded strings with `libs.camera.camera2`, `libs.camera.lifecycle`, `libs.camera.view`

**Note:** CameraX is declared as a dependency but no Kotlin source file currently imports `androidx.camera.*`. The `ReceiptScannerCV.kt` uses only ML Kit for OCR. The ProGuard rules do reference CameraX (`-keep class androidx.camera.** { *; }`). These dependencies may be for planned/future camera integration or used via JNI reflection.

---

### 🟡 MODERATE #4: Hardcoded ML Kit Dependency

**Problem:** ML Kit text recognition was hardcoded:
```kotlin
implementation("com.google.mlkit:text-recognition:16.0.1")
```

**Fix:** Migrated to version catalog:
- `libs.versions.toml` — added `mlkitTextRecognition = "16.0.1"` and `mlkit-text-recognition` library entry
- `app/build.gradle.kts` — replaced with `libs.mlkit.text.recognition`

---

### 🟢 MINOR #5: Unused `sqldelight` Version Entry

**Problem:** `libs.versions.toml` declared `sqldelight = "2.0.2"` but this version was never referenced by any library or plugin entry. Dead configuration.

**Fix:** Removed the `sqldelight` version entry.

---

## Dependency Audit — All Declared Dependencies Verified

| Category | Dependencies | Status |
|----------|-------------|--------|
| **Compose** | BOM, UI, Material3, Icons, Navigation, Activity | ✅ All via BOM |
| **Lifecycle** | viewmodel-compose, runtime-compose | ✅ Catalog |
| **Hilt** | android, compiler (KSP), navigation-compose | ✅ Catalog |
| **Room** | runtime, compiler (KSP), ktx | ✅ Catalog |
| **SQLCipher** | android-database-sqlcipher | ✅ Catalog |
| **Networking** | Retrofit, Retrofit-serialization, OkHttp, Logging | ✅ Catalog (fixed import) |
| **Serialization** | kotlinx-serialization-json + plugin | ✅ Catalog (plugin was missing) |
| **Coroutines** | core, android | ✅ Catalog |
| **DataStore** | preferences | ✅ Catalog |
| **Security** | security-crypto, biometric | ✅ Catalog |
| **WorkManager** | work-runtime-ktx, hilt-work, hilt-work-compiler | ✅ Catalog |
| **CameraX** | camera2, lifecycle, view | ✅ Catalog (was hardcoded) |
| **ML Kit** | text-recognition | ✅ Catalog (was hardcoded) |
| **Logging** | Timber | ✅ Catalog |
| **Gson** | gson | ✅ Catalog |
| **Testing** | JUnit, Coroutines-test, MockK, Turbine, Espresso, Compose-test | ✅ Catalog |

## Plugin Audit

| Plugin | Declared | Applied (app) | Status |
|--------|----------|---------------|--------|
| android-application | ✅ | ✅ | OK |
| kotlin-android | ✅ | ✅ | OK |
| kotlin-compose | ✅ | ✅ | OK |
| kotlin-serialization | ✅ (NEW) | ✅ (NEW) | **Fixed** |
| hilt-android | ✅ | ✅ | OK |
| ksp | ✅ | ✅ | OK |

## Version Compatibility Matrix

| Component | Version | Notes |
|-----------|---------|-------|
| AGP | 8.7.3 | Compatible with Gradle 8.11.1 |
| Kotlin | 2.1.0 | KSP 2.1.0-1.0.29 matches |
| Hilt | 2.53.1 | Stable |
| Room | 2.6.1 | Stable, KSP-compatible |
| Compose BOM | 2024.12.01 | Latest stable |
| Coroutines | 1.9.0 | Latest stable |
| Gradle | 8.11.1 | Via wrapper |

No version conflicts detected.

---

## Files Modified

1. `gradle/libs.versions.toml` — Added serialization plugin, CameraX libs, ML Kit lib; removed unused sqldelight version
2. `build.gradle.kts` — Added serialization plugin declaration
3. `app/build.gradle.kts` — Added serialization plugin; migrated CameraX + ML Kit to catalog refs
4. `app/src/main/java/com/msaidizi/app/core/network/NetworkModule.kt` — Fixed converter import path
