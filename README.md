<div align="center">

# 📱 Msaidizi

### AI-Powered CFO for Informal Workers

**Your CFO in your pocket.** Msaidizi is your Chief Financial Officer — tracks revenue, expenses, profit, and cash flow. Runs fully offline on budget Android phones (2GB+ RAM), understands Kiswahili and English, and delivers CFO-level financial intelligence to workers who never had one — no internet required.

> **Free CFO for 600 million informal workers.**

[![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen.svg)](https://developer.android.com/about/versions/oreo)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-purple.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Build](https://img.shields.io/badge/Build-Passing-brightgreen.svg)](#build-from-source)
[![Offline](https://img.shields.io/badge/Offline-First-orange.svg)](#architecture)
[![AI](https://img.shields.io/badge/AI-On--Device-red.svg)](#key-components)

[Download APK](../../releases/latest/download/msaidizi-release.apk) · [Report Bug](../../issues/new?template=bug_report.md) · [Request Feature](../../issues/new?template=feature_request.md)

</div>

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  User (Voice / Text)                                        │
│       │                                                     │
│       ▼                                                     │
│  ┌─────────────────────────────────────────────────────┐    │
│  │           SuperagentHarness (CFO Brain)              │    │
│  │                                                      │    │
│  │  IntentRouter → ContextAssembler → GuardrailsEngine  │    │
│  │       │              │                   │           │    │
│  │       ▼              ▼                   ▼           │    │
│  │  ToolRegistry   MemoryManager      FlywheelEngine    │    │
│  │       │                                          │    │    │
│  │       ▼                                          ▼    │    │
│  │  ┌──────────────────────────────────────────┐         │    │
│  │  │         LlmEngine (On-Device)            │         │    │
│  │  │  Qwen 0.8B GGUF via llama.cpp JNI       │         │    │
│  │  └──────────────────────────────────────────┘         │    │
│  └─────────────────────────────────────────────────────┘    │
│       │                                                     │
│       ▼                                                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────────────┐   │
│  │ Whisper  │  │  Piper   │  │ Room + SQLCipher (Local) │   │
│  │ (STT)    │  │  (TTS)   │  │ Conversations, Inventory │   │
│  └──────────┘  └──────────┘  └──────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### Key Components

| Component | Role | Tech |
|-----------|------|------|
| **SuperagentHarness** | CFO brain — unified financial orchestration | Kotlin, Coroutines |
| **LlmEngine** | On-device LLM reasoning | llama.cpp JNI → Qwen 0.8B GGUF |
| **IntentRouter** | Classifies user intent | Rule-based + LLM fallback |
| **ContextAssembler** | Gathers context from all memory layers | Room DB, Knowledge base |
| **ToolRegistry** | Executes business tools (sales, stock, etc.) | Kotlin |
| **GuardrailsEngine** | Safety & output filtering | Rule-based |
| **FlywheelEngine** | Learns your business patterns over time | Pattern extraction |
| **Voice Pipeline** | Speech-to-text / text-to-speech | Whisper (ONNX), Piper (ONNX) |
| **Database** | Encrypted local storage | Room + SQLCipher |

### Model Files

| Model | Format | Size | Purpose |
|-------|--------|------|---------|
| Qwen 0.8B | GGUF (Q4_K_M) | ~500MB | Reasoning, conversation |
| Whisper Tiny | ONNX | ~75MB | Speech-to-text |
| Piper TTS | ONNX | ~15MB | Text-to-speech |

## 📱 Requirements

- **Android 8.0+** (API 26)
- **Architecture:** ARM64 (`arm64-v8a`) or ARM32 (`armeabi-v7a`)
- **RAM:** 2GB minimum (3GB+ recommended)
- **Storage:** ~700MB for models + app

## 🚀 Download

**[Download Latest Release](../../releases/latest/download/msaidizi-release.apk)**

Or build from source (see below).

## 🛠️ Build from Source

### Prerequisites

- **JDK 17** (Temurin recommended)
- **Android Studio Ladybug** (2024.2+) or command-line SDK
- **Android SDK 35** (compileSdk)
- **NDK** (for llama.cpp JNI — installed automatically by SDK Manager)
- **Git LFS** (for model files, if stored in-repo)

### Quick Build

```bash
# Clone
git clone https://github.com/your-org/msaidizi-app.git
cd msaidizi-app

# Build debug APK (no signing required)
./gradlew assembleDebug

# Output: app/build/outputs/apk/debug/msaidizi-app-debug.apk
```

### Release Build

```bash
# 1. Create keystore (first time only)
keytool -genkey -v -keystore release-keystore.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias msaidizi

# 2. Configure signing (choose one):

# Option A: keystore.properties (local dev)
cat > keystore.properties << EOF
store.file=release-keystore.jks
store.password=YOUR_PASSWORD
key.alias=msaidizi
key.password=YOUR_KEY_PASSWORD
EOF

# Option B: Environment variables (CI)
export APK_SIGNING_KEYSTORE_FILE=release-keystore.jks
export APK_KEYSTORE_PASSWORD=YOUR_PASSWORD
export APK_KEY_ALIAS=msaidizi
export APK_KEY_PASSWORD=YOUR_KEY_PASSWORD

# 3. Build
./gradlew assembleRelease

# Output: app/build/outputs/apk/release/msaidizi-app-release.apk
```

### Model Assets

Model files are placed in `app/src/main/assets/models/`:

```
app/src/main/assets/models/
├── gguf/
│   └── qwen-0.8b-q4_k_m.gguf    # LLM (~500MB)
├── onnx-whisper/
│   └── whisper-tiny.onnx          # STT (~75MB)
└── onnx-piper/
    └── piper-voice.onnx           # TTS (~15MB)
```

**⚠️ Model files are NOT in the git repo.** They are either:
1. Downloaded at first launch (default APK ~15MB)
2. Bundled into the APK for offline distribution (~700MB)

To bundle models, place them in the directories above before building.

### Build Variants

| Variant | Models | Signing | Use Case |
|---------|--------|---------|----------|
| `debug` | Optional | Debug key | Development |
| `release` | Optional | Release key | Production |

## 🧪 Testing

```bash
# Unit tests
./gradlew testDebugUnitTest

# Lint
./gradlew lint

# Full CI check
./gradlew check
```

## 📂 Project Structure

```
msaidizi-app/
├── app/
│   ├── src/main/
│   │   ├── java/com/msaidizi/app/
│   │   │   ├── core/                    # Database, DI, Security, Utils
│   │   │   ├── model/                   # Data models
│   │   │   ├── superagent/              # AI brain
│   │   │   │   ├── harness/             #   Core pipeline
│   │   │   │   ├── tools/               #   Business tools
│   │   │   │   ├── memory/              #   Memory management
│   │   │   │   ├── guardrails/          #   Safety filters
│   │   │   │   └── flywheel/            #   Learning engine
│   │   │   ├── ui/                      # Compose UI
│   │   │   ├── voice/                   # Voice pipeline
│   │   │   └── bootstrap/               # First-launch setup
│   │   ├── assets/models/               # Model files (gitignored)
│   │   └── res/                         # Android resources
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── .github/workflows/
│   ├── build-apk.yml                    # APK build & release
│   └── ci.yml                           # Lint, test, security
├── gradle/libs.versions.toml            # Version catalog
├── build.gradle.kts                     # Root build file
└── settings.gradle.kts                  # Project settings
```

## 🤝 Contributing

We welcome contributions! Here's how:

### Getting Started

1. **Fork** the repository
2. **Clone** your fork
3. **Create** a feature branch: `git checkout -b feature/my-feature`
4. **Make** your changes
5. **Test**: `./gradlew testDebugUnitTest lint`
6. **Commit**: `git commit -m "feat: add my feature"`
7. **Push**: `git push origin feature/my-feature`
8. **Open** a Pull Request

### Commit Convention

We follow [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add voice command for stock check
fix: crash when model file is corrupted
docs: update build instructions
chore: bump Gradle to 8.7
test: add unit tests for IntentRouter
```

### Code Guidelines

- **Kotlin** with official code style
- **Compose** for all UI
- **Hilt** for dependency injection
- **Coroutines** for async (no RxJava)
- **No hardcoded strings** — use `strings.xml`
- **Test your changes** — unit tests for business logic

### Architecture Rules

- The SuperagentHarness is the **single entry point** for all AI processing
- Tools are registered in `ToolRegistry` — don't bypass it
- All database access goes through DAOs (no raw SQL)
- Models are loaded lazily and cached as singletons
- Voice pipeline is separate from the text pipeline (they converge at `processInput`)

### Areas We Need Help

- 🌍 **Translations** — Better Kiswahili coverage for CFO briefings
- 🧠 **Tool implementations** — Real CFO logic in tools (cash flow, credit, savings)
- 🎨 **UI/UX** — Material 3 design for financial dashboards
- 📊 **Analytics** — CFO-grade business insights and reports
- 🧪 **Testing** — Coverage for superagent modules
- 📱 **Device testing** — Budget phone optimization

## 📄 License

[Apache License 2.0](LICENSE)

## 🔒 Security

Please see [SECURITY.md](SECURITY.md) for our security policy and vulnerability reporting process.

## 📬 Contact

- **GitHub**: [@ovalentine964](https://github.com/ovalentine964)
- **Issues**: [GitHub Issues](../../issues)
- **Discussions**: [GitHub Discussions](../../discussions)

## 🙏 Acknowledgments

- [llama.cpp](https://github.com/ggerganov/llama.cpp) — On-device LLM inference
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — Speech recognition & TTS
- [Qwen](https://github.com/QwenLM/Qwen) — Language model
- [Whisper](https://github.com/openai/whisper) — Speech-to-text
- [Piper](https://github.com/rhasspy/piper) — Text-to-speech

---

<div align="center">

**Built with ❤️ for Kenya's small business owners**

*Every dukawalla deserves a CFO.*

</div>
