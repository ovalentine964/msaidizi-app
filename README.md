# Biashara Intelligence: Msaidizi — Android App

**Your Business Assistant. Works Offline. Speaks Swahili.**

Msaidizi is a voice-based Multi-Agentic CFO for Africa's informal economy workers. It runs on 2GB Android phones, works offline, and speaks Swahili, Sheng, and English.

## Features

- 🎤 Voice input in Swahili/Sheng (Whisper ASR)
- 🔊 Voice output (Piper TTS)
- 📊 Business tracking (sales, purchases, profit)
- 🧠 AI-powered business advice
- 📱 Works offline (syncs when connected)
- 🌍 Multi-language (Swahili, Sheng, English)

## Architecture

- MVVM + Clean Architecture
- Room database (SQLite)
- Memory-mapped model loading (2GB optimized)
- Offline-first with store-forward sync

## Building

### Prerequisites

- Android Studio Hedgehog (2023.1.1)
- JDK 17
- Android SDK 34

### Build

```bash
./gradlew assembleDebug
```

### Models

Download models to `app/src/main/assets/models/`:

| Model | Size | Description |
|-------|------|-------------|
| `whisper-tiny-int4.bin` | 40MB | Speech-to-text (Whisper ASR) |
| `piper-swahili.onnx` | 25MB | Text-to-speech (Piper TTS) |
| `silero-vad.onnx` | 2.5MB | Voice activity detection |
| `qwen2.5-0.5b-int4.bin` | 300MB | AI reasoning engine |

## Target Device

- Samsung Galaxy A03 or similar
- 2GB RAM, Android 11+
- MediaTek Helio G25 or similar

## Project Structure

```
app/
├── src/main/
│   ├── java/com/biashara/ai/
│   │   ├── data/          # Repository implementations, Room DAOs
│   │   ├── di/            # Hilt dependency injection modules
│   │   ├── domain/        # Use cases, domain models
│   │   ├── ui/            # ViewModels, Composables, Activities
│   │   └── voice/         # ASR, TTS, VAD engine wrappers
│   ├── assets/models/     # ONNX and GGUF model files
│   └── res/               # Android resources
├── build.gradle.kts
└── proguard-rules.pro
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## Security

See [SECURITY.md](SECURITY.md) for vulnerability reporting.

## License

Proprietary — Biashara Intelligence
