# Msaidizi Android App

## Overview

Msaidizi (meaning "Helper" in Swahili) is an AI-powered business CFO assistant designed for Kenya's informal economy workers. It runs entirely offline on a Samsung Galaxy A03 with 2GB RAM.

## Architecture

**MVVM + Clean Architecture**

- **Voice Pipeline**: AudioRecord → VAD → Whisper ASR → IntentRouter → Agent → TTS → AudioTrack
- **Agent System**: Code-based intent classification (90%+ accuracy without LLM)
- **Database**: Room/SQLite with WAL mode for concurrent reads
- **DI**: Hilt for dependency injection
- **Sync**: Store-forward with zstd compression and AES-256 encryption

## Key Features

- 🎤 **Voice Input**: Record sales, purchases, and expenses by speaking Swahili
- 📊 **Business Analytics**: Daily/weekly summaries, profit tracking, trend analysis
- 🤖 **Smart Agents**: Intent classification, business advice, pattern learning
- 📱 **Offline First**: Works without internet, syncs when connected
- 🌍 **Multi-Language**: Swahili, English, Sheng support

## Target Device

- **RAM**: 2GB (Samsung Galaxy A03)
- **Android**: 11+ (API 26+)
- **Processor**: MediaTek Helio G25 or similar

## Project Structure

```
app/src/main/java/com/msaidizi/app/
├── MsaidiziApp.kt          # Application class
├── MainActivity.kt         # Single activity
├── core/
│   ├── di/AppModule.kt     # Hilt DI
│   ├── database/           # Room database
│   ├── model/              # Data models
│   └── util/               # Utilities
├── voice/                  # Voice pipeline
├── agent/                  # Business agents
├── sync/                   # Cloud sync
└── ui/                     # UI layer
```

## Building

1. Clone the repository
2. Open in Android Studio
3. Sync Gradle
4. Run on device or emulator

## Dependencies

- **Room**: Local database
- **Hilt**: Dependency injection
- **Coroutines**: Async operations
- **Ktor**: HTTP client for sync
- **ONNX Runtime**: ML model inference
- **MPAndroidChart**: Charts
- **Lottie**: Animations

## Memory Management

The app is optimized for 2GB devices:

- **Lazy loading**: Models loaded only when needed
- **Memory mapping**: Model files mapped to virtual memory
- **Background release**: Models freed when app goes to background
- **Progressive enhancement**: Features scale based on device capabilities

## Voice Pipeline

1. **Audio Recording**: 16kHz PCM via AudioRecord
2. **Voice Activity Detection**: Energy-based VAD (code, not model)
3. **Speech Recognition**: Whisper Tiny INT4 (~40MB)
4. **Intent Classification**: Regex patterns for Swahili business commands
5. **Business Logic**: Pure code for calculations (0 LLM overhead)
6. **Response Generation**: Template-based or LLM-generated
7. **Text-to-Speech**: Piper TTS or Android built-in

## Agent System

- **IntentRouter**: Classifies user input (sale, purchase, query, etc.)
- **BusinessAgent**: Records transactions, manages inventory
- **AnalysisAgent**: Calculates trends, patterns, forecasts
- **AdvisorAgent**: Generates business advice
- **LearningAgent**: Adapts to user vocabulary and patterns

## Database Schema

- **transactions**: All business records
- **inventory**: Stock levels and costs
- **patterns**: Learned business patterns
- **vocabulary**: User's spoken terms
- **daily_summaries**: Pre-computed daily stats

## Sync Architecture

- **Offline-first**: All data stored locally
- **Store-forward**: Queue in SQLite until connected
- **Compression**: zstd for minimal bandwidth
- **Encryption**: AES-256-GCM for data security
- **Retry**: Exponential backoff for failed uploads

## License

Proprietary - Msaidizi Technologies Ltd.
