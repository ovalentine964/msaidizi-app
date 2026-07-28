# Building Msaidizi

This guide covers everything you need to build the Msaidizi Android app from source.

---

## Prerequisites

### Required Software

| Tool | Version | Purpose |
|------|---------|---------|
| **Android Studio** | Hedgehog (2023.1.1) or later | IDE & build toolchain |
| **JDK** | 17 | Kotlin/Java compilation |
| **Android SDK** | API 35 (compileSdk) | Android platform |
| **Android NDK** | 25+ | Native C++ compilation (llama.cpp, sherpa-onnx) |
| **CMake** | 3.22.1+ | Native build system (bundled with NDK) |
| **Git** | 2.30+ | Source control & submodule management |

### System Requirements

- **RAM:** 16GB recommended (8GB minimum)
- **Disk:** 10GB free space (source + build + models)
- **OS:** macOS, Linux, or Windows (WSL2 recommended)

---

## Quick Start (Stub Mode)

The fastest way to build — compiles the app **without** native AI libraries.
The app will run but AI features (LLM, STT, TTS) return placeholder results.

```bash
# 1. Clone the repository
git clone https://github.com/ovalentine964/msaidizi-app.git
cd msaidizi-app

# 2. Open in Android Studio (or build from command line)
./gradlew assembleDebug

# 3. Install on device
adb install app/build/outputs/apk/debug/app-debug.apk
```

That's it! The app compiles and runs in stub mode.

---

## Full Build (with AI Capabilities)

To build with on-device LLM, STT, and TTS, you need native libraries and model files.

### Step 1: Set Up Native Dependencies

```bash
# Clone llama.cpp and sherpa-onnx into third_party/
chmod +x scripts/setup_native_deps.sh
./scripts/setup_native_deps.sh
```

This clones:
- **llama.cpp** — On-device LLM inference engine
- **sherpa-onnx** — On-device ASR (Whisper) and TTS (Piper)

Both are built from source by the Android NDK during the Gradle build.

### Step 2: Download AI Models

```bash
# Download all models (~555MB total)
chmod +x scripts/download_models.sh
./scripts/download_models.sh
```

Models downloaded:
| Model | Size | Purpose |
|-------|------|---------|
| Qwen3.5 0.8B (GGUF Q4_K_M) | ~500MB | On-device LLM |
| Whisper Tiny (ONNX) | ~40MB | Speech-to-text |
| Piper Swahili (ONNX) | ~15MB | Text-to-speech |

Models are placed in `app/src/main/assets/models/` and bundled into the APK.

### Step 3: Build

```bash
# Debug build (with models bundled)
./gradlew assembleDebug

# Release build (requires signing keystore)
./gradlew assembleRelease
```

### Step 4: Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Project Structure

```
msaidizi-app/
├── app/                    # Main application module
│   ├── src/main/
│   │   ├── java/          # UI, MainActivity, Application
│   │   ├── cpp/           # JNI bridge (now in :voice module)
│   │   └── res/           # Android resources
│   └── build.gradle.kts
├── core/                   # :core module — shared infrastructure
│   └── src/main/java/     # Database, Network, Security, Models
├── voice/                  # :voice module — AI engines
│   └── src/main/
│       ├── java/          # LlamaCppEngine, SherpaOnnxEngine, VadEngine
│       └── cpp/           # Native JNI code + CMakeLists.txt
├── agent/                  # :agent module — superagent framework
│   └── src/main/java/     # Tools, Harness, Council, Graph, etc.
├── feature/
│   ├── finance/           # :feature:finance — business finance tools
│   ├── agriculture/       # :feature:agriculture — farming tools
│   ├── market/            # :feature:market — market/pricing tools
│   └── credit/            # :feature:credit — credit/loan tools
├── scripts/
│   ├── setup_native_deps.sh   # Download llama.cpp + sherpa-onnx
│   └── download_models.sh     # Download AI models
├── docs/                   # Architecture & research docs
└── BUILD.md               # This file
```

### Module Dependencies

```
:app
 ├── :core
 ├── :voice → :core
 ├── :agent → :core, :voice
 ├── :feature:finance → :core, :agent
 ├── :feature:agriculture → :core, :agent
 ├── :feature:market → :core, :agent
 └── :feature:credit → :core, :agent
```

---

## Build Variants

| Variant | Debug | Release | Description |
|---------|-------|---------|-------------|
| **Stub** | ✅ | ✅ | No native libs — AI features return placeholders |
| **Source** | ✅ | ✅ | llama.cpp + sherpa-onnx built from source |
| **With Models** | ✅ | ✅ | Models bundled in APK (~555MB) |
| **Without Models** | ✅ | ✅ | Models downloaded on first launch (~15MB APK) |

---

## Signing

### Debug

Debug builds use the default Android debug keystore. No configuration needed.

### Release

Release signing requires a keystore. Set up via:

**Option A: Environment variables (CI)**
```bash
export APK_SIGNING_KEYSTORE_FILE=/path/to/release.jks
export APK_KEYSTORE_PASSWORD=yourpassword
export APK_KEY_ALIAS=msaidizi
export APK_KEY_PASSWORD=yourkeypassword
```

**Option B: Local properties file**
Create `keystore.properties` in the project root:
```properties
store.file=/path/to/release.jks
store.password=yourpassword
key.alias=msaidizi
key.password=yourkeypassword
```

⚠️ **Never commit `keystore.properties` or `.jks` files to git.**

---

## Native Dependencies

### llama.cpp

- **Repository:** https://github.com/ggerganov/llama.cpp
- **Purpose:** On-device LLM inference (Qwen 0.8B GGUF)
- **Build:** Compiled from source by NDK via CMake
- **Output:** `libllama_jni.so`

### sherpa-onnx

- **Repository:** https://github.com/k2-fsa/sherpa-onnx
- **Purpose:** On-device ASR (Whisper) and TTS (Piper)
- **Build:** Compiled from source by NDK via CMake
- **Output:** `libsherpa_jni.so`, `libvad_jni.so`

### Stub Mode

When native libraries are **not** present, the app builds in stub mode:
- CMake prints a warning and compiles JNI bridges with no-op implementations
- AI features return placeholder results
- UI and all non-AI features work normally
- Useful for UI development without 500MB+ of dependencies

---

## Troubleshooting

### Build fails with "NDK not configured"

1. Open Android Studio → Settings → Appearance & Behavior → System Settings → Android SDK
2. SDK Tools tab → Check "NDK (Side by side)" → Apply
3. Or set `ANDROID_HOME` environment variable

### Build fails with "CMake not found"

CMake is bundled with the NDK. Ensure NDK is installed:
```bash
ls $ANDROID_HOME/cmake/
```

### Build fails with "llama.h not found"

Run the native dependency setup script:
```bash
./scripts/setup_native_deps.sh
```

### Build fails with "sherpa-onnx/c-api/c-api.h not found"

Same as above — run `./scripts/setup_native_deps.sh`.

### APK is too large (~555MB)

Models are bundled in the APK. To reduce size:
1. Don't run `./scripts/download_models.sh` before building
2. Models will be downloaded on first launch via WorkManager
3. APK size without models: ~15-20MB

### Gradle daemon OOM

Add to `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=512m
```

### "Unsupported class file major version" error

Ensure you're using JDK 17:
```bash
java -version
# Should show: openjdk version "17.x.x"
```

### Models not working on device

1. Check `adb logcat | grep -E "llama_jni|sherpa_jni|vad_jni"` for native errors
2. Verify models are present: `adb shell ls /data/data/com.msaidizi.app/files/models/`
3. Check model status in app: Settings → Model Status

---

## CI/CD

### GitHub Actions Workflows

| Workflow | Trigger | Description |
|----------|---------|-------------|
| `ci.yml` | PR, push to main | Lint, test, security, compile, build with models |
| `build-apk.yml` | Push to main, tags | Full APK build + release |

### CI Build Steps

1. **Lint** — Android Lint + ktlint
2. **Unit Tests** — JUnit + MockK + Turbine
3. **Security** — Dependency scan, secret detection, CodeQL
4. **Compile Check** — Quick Kotlin compilation
5. **Build with Models** — Full APK with native libs + AI models

### Model Cache

CI caches downloaded models to avoid re-downloading ~555MB on every run:
```yaml
- uses: actions/cache@v4
  with:
    path: .model-cache
    key: ai-models-${{ hashFiles('scripts/download_models.sh') }}
```

### Native Dependency Cache

CI caches cloned native libraries:
```yaml
- uses: actions/cache@v4
  with:
    path: app/src/main/cpp/third_party
    key: native-deps-${{ hashFiles('scripts/setup_native_deps.sh') }}
```

---

## IDE Setup

### Android Studio

1. Open the project root directory
2. Android Studio will detect the Gradle project and sync
3. If prompted, install any missing SDK components
4. Build → Make Project (or `Ctrl+F9`)

### VS Code (with Kotlin extension)

1. Install "Kotlin" extension by fwcd
2. Open the project root
3. The extension will detect the Gradle project

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

### Code Style

- Kotlin: Follow [Android Kotlin Style Guide](https://developer.android.com/kotlin/style-guide)
- C++: C++17, `-Wall -Wextra -Wpedantic`
- Run `./gradlew ktlintCheck` before submitting PRs

### Testing

```bash
# Unit tests
./gradlew testDebugUnitTest

# Instrumented tests (requires device/emulator)
./gradlew connectedDebugAndroidTest
```

---

## FAQ

**Q: Can I build without Android Studio?**
A: Yes. Use the Gradle wrapper directly: `./gradlew assembleDebug`

**Q: How do I update native dependencies?**
A: `cd app/src/main/cpp/third_party/llama.cpp && git pull` (same for sherpa-onnx)

**Q: How do I update models?**
A: `./scripts/download_models.sh` will re-download if files are missing.

**Q: Can I use a different LLM model?**
A: Yes. Place any GGUF file in `app/src/main/assets/models/gguf/` and update `ModelManager.kt`.

**Q: Can I run on an x86 emulator?**
A: Not currently. The app targets `arm64-v8a` and `armeabi-v7a` only. Use a physical ARM device or ARM emulator image.
