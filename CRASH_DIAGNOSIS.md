# Msaidizi App — Crash Diagnosis Report

**Date:** July 2026
**Symptom:** App crashes immediately on launch after installation. No UI renders.

---

## Root Cause Summary

After analyzing the full codebase (399 Kotlin files, 8 Hilt modules, NDK/CMake config, ProGuard rules), I've identified **5 crash vectors** ranked by likelihood. The most probable cause is a **Hilt dependency injection chain failure** triggered by missing native libraries.

---

## 🔴 CRASH CAUSE #1: Missing Native Libraries (HIGHEST LIKELIHOOD)

### The Problem

The app uses **3 native libraries** that are built from source via CMake at compile time:

| Library | Used By | Purpose |
|---------|---------|---------|
| `llama_jni` (llama.cpp) | `LlamaCppEngine` | On-device LLM inference |
| `sherpa-onnx` JNI | `SherpaVoiceEngine` | ASR, TTS, VAD |
| `libncnn.so` | Sherpa-ONNX internal | Neural network inference |

**These `.so` files are NOT in the repository.** They are built during `./gradlew assembleDebug` via CMake FetchContent (downloads llama.cpp from GitHub) and Gradle dependencies (sherpa-onnx AAR).

If the APK was built:
- **Without NDK installed** → CMake can't compile llama.cpp → `llama_jni.so` missing
- **Without internet during build** → FetchContent can't download llama.cpp source → build fails silently
- **With wrong ABI filter** → .so built for arm64 but device is 32-bit → `UnsatisfiedLinkError`

### Where It Crashes

```
BootstrapActivity.onCreate()
  → Hilt injects VoicePipeline
    → VoicePipeline constructor calls SherpaVoiceEngine
      → SherpaVoiceEngine uses com.k2fsa.sherpa.onnx.* classes
        → These classes have static initializers that call System.loadLibrary("sherpa-onnx-jni")
          → 💥 UnsatisfiedLinkError → app crashes
```

### The Fix

**Step 1:** Verify the APK actually contains native libraries:
```bash
# Check if .so files are in the APK
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep "\.so"
```

Expected output should include:
```
lib/arm64-v8a/libllama_jni.so
lib/arm64-v8a/libsherpa-onnx-jni.so
lib/arm64-v8a/libncnn.so
lib/armeabi-v7a/libllama_jni.so    (stub only)
```

**Step 2:** If missing, ensure NDK is installed:
```bash
# In Android Studio: SDK Manager → SDK Tools → NDK (Side by side)
# Required: NDK r26b (26.1.10909125)
```

**Step 3:** Ensure CMake 3.22.1 is installed:
```bash
# In Android Studio: SDK Manager → SDK Tools → CMake 3.22.1
```

**Step 4:** Build with proper ABI filter:
```bash
./gradlew assembleDebug --stacktrace 2>&1 | tail -50
```

---

## 🔴 CRASH CAUSE #2: Hilt Dependency Chain Failure (HIGH LIKELIHOOD)

### The Problem

`BootstrapActivity` (the launcher) injects `VoicePipeline`, which has **11 constructor dependencies**:

```kotlin
@Singleton
class VoicePipeline @Inject constructor(
    private val audioRecorder: AudioRecorder,           // 1
    private val vad: VoiceActivityDetector,             // 2
    private val speechRecognizer: SpeechRecognizer,     // 3
    private val kokoroTts: KokoroTtsEngine,             // 4
    private val piperTts: TextToSpeech,                 // 5
    private val mmsTtsEngine: MMSTextToSpeech,          // 6
    private val adaptiveAsrEngine: AdaptiveAsrEngine,   // 7
    private val memoryManager: MemoryManager,           // 8
    private val conversationLearningPipeline: ...,      // 9
    private val harness: VoicePipelineHarness,          // 10
    private val sherpaVoiceEngine: SherpaVoiceEngine    // 11 ← depends on native libs
)
```

Each of these has its own dependency chain from `AIModule` (40+ singletons). **If ANY single provider throws, Hilt aborts the entire injection and the app crashes.**

### Why This Cascades

Hilt's `@Singleton` components are created lazily on first access. But `BootstrapActivity` is the **first Activity** launched. When it tries to inject `VoicePipeline`, Hilt must construct the entire dependency tree. One failure = total crash.

### The Fix

**Option A (Recommended):** Make `VoicePipeline` injection graceful in `BootstrapActivity`:

```kotlin
// In BootstrapActivity
@Inject lateinit var voicePipelineProvider: dagger.Lazy<VoicePipeline>
private val voicePipeline by lazy { 
    try { voicePipelineProvider.get() } 
    catch (e: Exception) { 
        Timber.e(e, "VoicePipeline unavailable — using text-only mode")
        null 
    }
}
```

**Option B:** Add try-catch around the entire `BootstrapActivity.onCreate()`:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    try {
        // existing code
    } catch (e: Throwable) {
        Timber.e(e, "BootstrapActivity crashed — falling back to MainActivity")
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
```

**Option C:** Create a `SafeBootstrapActivity` that doesn't inject VoicePipeline at all — just shows a simple text-based onboarding, then redirects to the full app.

---

## 🟡 CRASH CAUSE #3: SQLCipher Native Library (MEDIUM LIKELIHOOD)

### The Problem

`DatabaseModule` uses `net.sqlcipher.database.SupportFactory` which requires `libsqlcipher.so`. This comes from the `net.zetetic:android-database-sqlcipher` Gradle dependency.

If the dependency is missing or the AAR doesn't include the native lib for the target ABI, the app crashes when `DatabaseModule.provideDatabase()` runs.

### Where It Crashes

```
MsaidiziApp.onCreate()
  → super.onCreate() (Hilt injection)
    → DatabaseModule.provideDatabase()
      → SupportFactory(passphrase)
        → SQLiteDatabase.loadLibs(context)
          → 💥 UnsatisfiedLinkError
```

### The Fix

The code already has a fallback (encrypted → unencrypted), but the `SupportFactory` constructor itself might throw BEFORE the try-catch:

```kotlin
// Current code:
val db: AppDatabase = try {
    val factory = SupportFactory(passphrase)  // ← THIS LINE CAN CRASH
    // ...
} catch (e: Throwable) {
    // fallback to unencrypted
}
```

**Fix:** Wrap the `SupportFactory` construction itself:

```kotlin
val factory = try {
    SupportFactory(passphrase)
} catch (e: Throwable) {
    Timber.e(e, "SQLCipher SupportFactory failed — using unencrypted DB")
    null
}

val db = if (factory != null) {
    // encrypted path
} else {
    // unencrypted path
}
```

---

## 🟡 CRASH CAUSE #4: Model File Not Found at Startup (MEDIUM LIKELIHOOD)

### The Problem

The app references model files that are ~580MB and must be downloaded separately:

| File | Size | Purpose |
|------|------|---------|
| `Qwen3.5-0.8B-Q4_K_M.gguf` | ~580MB | On-device LLM |
| `ggml-tiny.en-q5_1.bin` | ~40MB | Whisper ASR |
| `piper-swahili.onnx` | ~25MB | TTS |
| `silero_vad.onnx` | ~5MB | Voice activity detection |

If the app tries to load these at startup and they don't exist, it **should** handle gracefully (the code has fallback logic). But if `LlmEngine.loadModel()` or `SherpaVoiceEngine` tries to load before checking file existence, it crashes.

### The Fix

The code already has existence checks in most places (`if (!qwenPath.exists())`). But verify that `BundledModelManager` and `SequentialModelLoader` don't try to load models during Hilt construction (only on first use).

---

## 🟡 CRASH CAUSE #5: BouncyCastle Provider Conflict (LOW LIKELIHOOD)

### The Problem

`MsaidiziApp.initializeApp()` replaces Android's built-in BouncyCastle:

```kotlin
Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
Security.insertProviderAt(BouncyCastleProvider(), 1)
```

On some Android versions (especially 12+), this can cause `NoSuchAlgorithmException` for algorithms that Android's BC provides but the full BC doesn't, or vice versa.

### The Fix

This is wrapped in try-catch, so it should not crash. But if other code depends on a specific BC algorithm being available, it could fail later. **Low priority.**

---

## 🟢 CRASH CAUSE #6: ProGuard/R8 Stripping (LOW LIKELIHOOD)

### The Problem

The ProGuard rules look correct for Room, Hilt, and Kotlin serialization. But if R8 aggressively strips classes that are only referenced via reflection (e.g., sherpa-onnx JNI callbacks), the app crashes at runtime.

### The Fix

Add to `proguard-rules.pro`:

```proguard
# Keep sherpa-onnx JNI classes
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { native <methods>; }

# Keep llama.cpp JNI
-keep class com.msaidizi.app.voice.LlamaCppJNI { *; }
```

---

## Priority Fix Order

| Priority | Issue | Effort | Impact |
|----------|-------|--------|--------|
| 1 | Verify APK contains .so files | 5 min | Blocks everything |
| 2 | Make VoicePipeline injection graceful | 30 min | Prevents Hilt cascade crash |
| 3 | Wrap SupportFactory in try-catch | 10 min | Prevents DB crash |
| 4 | Add ProGuard keep rules for JNI | 5 min | Prevents release build crashes |
| 5 | Verify model loading is lazy | 15 min | Prevents startup model crash |

---

## Immediate Action Items

### Step 1: Check the APK (5 minutes)
```bash
# Build and inspect
cd msaidizi-app
./gradlew assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep "\.so"
```

If no `.so` files appear → **NDK/CMake not installed or build failed silently.**

### Step 2: Check crash log on device (2 minutes)
```bash
# Connect phone via USB, enable USB debugging
adb shell "cat /sdcard/Android/data/com.msaidizi.app/files/crash_logs/*.txt 2>/dev/null | tail -50"
# Or check logcat
adb logcat -s AndroidRuntime:E | head -100
```

### Step 3: Build with --stacktrace (5 minutes)
```bash
./gradlew assembleDebug --stacktrace 2>&1 | grep -i "error\|fail\|exception" | head -20
```

---

## Valentine — I Need You To

1. **Run `adb logcat`** while opening the app and paste the crash log here
2. **Tell me your phone model** so I can check ABI compatibility
3. **Tell me how you built the APK** (Android Studio / CI / downloaded)

The crash log will tell us exactly which of these 6 causes is hitting you. Once I know, I can write the specific code fix.
