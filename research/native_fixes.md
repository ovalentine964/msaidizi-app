# Native/JNI Compilation Fixes — Msaidizi Android App

**Date:** 2026-07-27  
**Scope:** `app/src/main/cpp/` (CMake), JNI bridge classes, ProGuard, build config

---

## Summary

The native code layer consists of three JNI libraries (`llama_jni`, `sherpa_jni`, `vad_jni`) built via CMake + NDK. The C++ source files are well-structured with conditional compilation (`#if __has_include(...)`) that gracefully falls back to stub implementations when the actual native libraries (llama.cpp, sherpa-onnx) are not present. **Three issues were found and fixed.**

---

## Issues Found & Fixes Applied

### 1. ❌ Missing `VadEngine.kt` — JNI Kotlin Wrapper (CRITICAL)

**Problem:**  
`vad_jni.cpp` exports 5 JNI functions under `com.msaidizi.app.voice.VadEngine`, but **no corresponding Kotlin class existed**. This means:
- The native library compiles and links correctly
- But no Kotlin code ever calls `System.loadLibrary("vad_jni")` or invokes the native methods
- The VAD JNI bridge is dead code — unusable from the app

**Fix:**  
Created `app/src/main/java/com/msaidizi/app/voice/VadEngine.kt` with:
- `System.loadLibrary("vad_jni")` in companion `init` block (with `try/catch` for graceful failure)
- All 5 `external fun` declarations matching the C++ JNI signatures exactly:
  - `nativeCreateVad(modelPath, threshold, minSilenceDuration, minSpeechDuration, maxSpeechDuration): Long`
  - `nativeProcessAudio(handle, audioData): Boolean`
  - `nativeIsSpeech(handle): Boolean`
  - `nativeReset(handle)`
  - `nativeDestroyVad(handle)`
- Public Kotlin API wrapping the native calls with error handling and logging
- `@Singleton` + `@Inject constructor()` for Hilt DI compatibility
- Status/diagnostic methods matching the pattern of `LlamaCppEngine` and `SherpaOnnxEngine`

### 2. ❌ ProGuard Missing Keep Rules for Voice JNI Classes (HIGH)

**Problem:**  
`proguard-rules.pro` had:
```proguard
-keep class com.msaidizi.app.superagent.tools.** { *; }
-keep class com.msaidizi.app.superagent.harness.** { *; }
```
But the JNI engine classes live in `com.msaidizi.app.voice.**`:
- `LlamaCppEngine` — native methods for LLM
- `SherpaOnnxEngine` — native methods for ASR/TTS
- `VadEngine` — native methods for VAD

With `isMinifyEnabled = true` in release builds, R8 could:
- Strip the `external fun` declarations
- Rename/remove the companion object `init` blocks that call `System.loadLibrary()`
- Cause `UnsatisfiedLinkError` at runtime on release builds

**Fix:**  
Added explicit keep rules:
```proguard
# Keep native JNI voice engine classes (llama_jni, sherpa_jni, vad_jni)
-keep class com.msaidizi.app.voice.LlamaCppEngine { *; }
-keep class com.msaidizi.app.voice.SherpaOnnxEngine { *; }
-keep class com.msaidizi.app.voice.VadEngine { *; }
```

### 3. ⚠️ Missing `third_party/` Directory (LOW — already handled gracefully)

**Problem:**  
`CMakeLists.txt` references `third_party/llama.cpp` and `third_party/sherpa-onnx`, but the directory does not exist. This is expected for initial checkout (large native deps shouldn't be in git), but there was no documentation explaining what to put there.

**Fix:**  
- Created `app/src/main/cpp/third_party/README.md` documenting:
  - How to add llama.cpp (source or pre-built)
  - How to add sherpa-onnx (source or pre-built)
  - That stub mode is available when neither is present

**Note:** The CMake already handles this correctly — it prints a warning and compiles in stub mode. No CMake changes needed.

---

## Verification: JNI Signature Audit

All JNI function signatures were verified to match exactly between Kotlin `external fun` declarations and C++ `JNIEXPORT` implementations:

| Library | Kotlin Class | C++ File | JNI Functions | Status |
|---------|-------------|----------|---------------|--------|
| llama_jni | `LlamaCppEngine.kt` | `llama_jni.cpp` | 3 (loadModel, generate, unloadModel) | ✅ Match |
| sherpa_jni | `SherpaOnnxEngine.kt` | `sherpa_jni.cpp` | 6 (createRecognize, recognize, destroyRecognizer, createSynthesizer, synthesize, destroySynthesizer) | ✅ Match |
| vad_jni | `VadEngine.kt` | `vad_jni.cpp` | 5 (createVad, processAudio, isSpeech, reset, destroyVad) | ✅ Match |

---

## Build Configuration Review

### `build.gradle.kts` — ✅ Correct
- `externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }` — points to correct CMakeLists
- `cppFlags += "-std=c++17"` — matches CMake's `CMAKE_CXX_STANDARD 17`
- `arguments += listOf("-DANDROID_STL=c++_shared")` — shared STL for all JNI libs
- `ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }` — standard Android ABIs
- `jniLibs { useLegacyPackaging = true }` — required for loading .so from APK

### `CMakeLists.txt` — ✅ Correct
- Produces 3 shared libraries: `libllama_jni.so`, `libsherpa_jni.so`, `libvad_jni.so`
- Conditional library detection with graceful stub fallback
- All three libs link against Android `log` library
- `-fvisibility=hidden` hides non-JNI symbols (correct)
- `llama_jni` additionally links `android` (for ANativeWindow if needed)

### C++ Source Files — ✅ Correct
- All three `.cpp` files use `#if __has_include(...)` for conditional compilation
- Stub paths return sensible defaults (non-zero handles, empty strings, false booleans)
- All stubs log warnings via `__android_log_print`
- All files include `JNI_OnLoad` for library initialization logging
- Thread safety: per-handle mutexes for concurrent access

### ProGuard — ✅ Fixed
- JNI native methods preserved via `-keepclasseswithmembernames class * { native <methods>; }`
- Voice engine classes now explicitly kept (was missing)

---

## Files Changed

| File | Action | Description |
|------|--------|-------------|
| `app/src/main/java/com/msaidizi/app/voice/VadEngine.kt` | **Created** | Kotlin JNI wrapper for VAD native library |
| `app/proguard-rules.pro` | **Modified** | Added keep rules for voice JNI classes |
| `app/src/main/cpp/third_party/README.md` | **Created** | Documentation for native dependency setup |
| `research/native_fixes.md` | **Created** | This report |

---

## Recommendations

1. **VoicePipeline integration**: `VoicePipeline.kt` currently uses a simple amplitude-based VAD (`calculateRMSAmplitude`). Consider integrating `VadEngine` for more robust speech endpoint detection.

2. **Model download**: The `ModelManager` handles asset extraction, but the VAD model (`silero_vad.onnx`) is not listed in `assetMappings`. Add it if VAD is to be used.

3. **CI testing**: With stub mode, the app compiles and runs without native libraries. CI can verify compilation without downloading ~500MB of model files.

4. **ABI coverage**: Currently targets `arm64-v8a` and `armeabi-v7a`. If x86_64 emulator testing is needed, add `x86_64` to `abiFilters` and provide corresponding native libs.
