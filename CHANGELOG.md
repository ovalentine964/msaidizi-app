# Changelog

All notable changes to Msaidizi will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-07-02

### Added
- **Voice-first transaction recording** — speak in 14 African dialects
- **5-Agent System** — Orchestrator, BusinessAgent, AnalysisAgent, AdvisorAgent, LearningAgent
- **CFO Engine** — proactive daily briefings, cash flow forecasting, restock alerts, savings advice, credit readiness, risk alerts
- **On-device LLM** — llama.cpp NDK integration for 2-5x faster inference
- **Gamification System** — points, levels (Beginner → Mogul), streaks, badges
- **Wealth Mindset (Rich Habits)** — 10 daily habits for financial discipline
- **Tithe & Giving** — voice-based giving tracking with consistency streaks
- **Goals & Loans** — savings goals with visual progress, loan repayment tracking
- **14 Dialect Adapters** — Swahili, Sheng, Kikuyu, Dholuo, Luhya, Kalenjin, Maasai, Migori, Somali, Amharic, Yoruba, Igbo, Hausa, Zulu, Xhosa
- **Business Flow View** — M-Pesa-style transaction visualization
- **Conversation Memory** — context-aware multi-turn conversations
- **Adaptive Vocabulary** — learns worker's product names over time
- **Self-Evolution System** — feedback collection, feature tracking, evolution dashboard
- **Infrastructure Dashboard** — workers see their data center impact
- **WhatsApp Integration** — onboarding, verification, report delivery, command handlers
- **Angavu Sync** — on-device to cloud synchronization pipeline
- **Worker Classification** — auto-detect worker type from transactions
- **Business Pattern Tracker** — identify trends and anomalies in business data

### Fixed
- **Serialization plugin** — re-enabled after kapt build failure diagnosis
- **SyncResult rename** — renamed to SyncStatus to resolve duplicate class name conflict
- **@Serializable removal** — removed from Room @Entity classes to fix kapt stub generation
- **llama.cpp tag** — corrected b4650 → b4651
- **App theme colors** — Angavu Intelligence palette instead of WhatsApp green
- **SVG resource cleanup** — removed invalid SVG files from mipmap resources
- **Retrofit + Gson dependencies** — added missing dependencies causing kapt failure
- **Sheng support** — conversation memory and error recovery for Sheng dialect
- **APK release attachment** — fixed GitHub release asset attachment

### Changed
- Updated architecture documentation with 5-agent system details
- Improved README with current feature set, simplified installation instructions
- Version set to 0.1.0 across all repos until real users

### Technical
- Kotlin 1.9.22 + Android SDK 34
- MVVM + Clean Architecture with Hilt DI
- Room + SQLCipher encrypted database
- ONNX Runtime for voice models (Whisper, Piper, Silero)
- llama.cpp NDK for on-device LLM
- Ktor HTTP client
- Coroutines for async operations
- 126 Kotlin source files

## [Unreleased]

### Added
- WhatsApp connection onboarding step
- Phone validation for Kenyan numbers (07XX, 01XX, +254)
- WhatsApp verification flow with polling
- Report generation (daily and weekly)
- WhatsApp command handlers (ripoti, mauzo, faida, etc.)
- Multi-language support (Swahili, Sheng, English)
- Report scheduling (morning, afternoon, evening)
- Share functionality
- OpenWA integration
- Backend API endpoints
- Database schema
- Docker deployment configuration
- Nginx reverse proxy setup
- Comprehensive documentation

### Changed
- Updated onboarding flow to include WhatsApp connection
- Enhanced phone validation with carrier detection
- Improved error handling with localized messages

### Fixed
- Phone number normalization edge cases
- Verification timeout handling
- Rate limiting configuration
