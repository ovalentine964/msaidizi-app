<p align="center">
  <img src=".github/banner.svg" alt="Msaidizi — AI-Powered CFO for 600 Million Informal Workers" width="100%">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android">
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/Offline--First-FF6B35?style=flat-square" alt="Offline-First">
  <img src="https://img.shields.io/badge/Voice--First-E8A838?style=flat-square" alt="Voice-First">
  <img src="https://img.shields.io/badge/Tools-55+-1B4965?style=flat-square" alt="55+ Tools">
  <img src="https://img.shields.io/badge/CI-Passing-3DDC84?style=flat-square" alt="CI">
  <img src="https://img.shields.io/badge/License-Apache%202.0-blue?style=flat-square" alt="License">
</p>

> **Every mama mboga deserves a CFO.** Msaidizi is a free, voice-first, offline-first AI CFO that runs on your phone. Speak in your language — track your business, understand your profit, build your credit.
>
> **New in v0.2:** "Meet Your CFO" onboarding, per-worker-type tool activation, device↔server graph sync, Alama Score validation, RCT impact framework, Luo + Kikuyu language support.
>
> **New in v0.3 (26+ Councils):** Streaming STT (real-time partial transcription), context window upgrade (4096/8192), debt trap detection, weighted job matching, aquaculture/fishing tools, Hawker & Entertainment worker types, battery saver mode, inter-council debate, LLM-powered OODA, graph embeddings, data retention & consent management, Firebase App Distribution, OTA model updates.
>
> 📋 **New to the codebase?** Read [`program.md`](program.md) for a declarative overview of the superagent's identity, intent types, guardrails, memory hierarchy, and all operational parameters — extracted from the source code into one file.
> For programmatic access, use [`program.json`](program.json).

**Built by [Angavu Intelligence Ltd.](https://ovalentine964.github.io/angavu-intelligence/)** — *Making the Invisible Economy Visible*

---

## What Msaidizi Does

| CFO Capability | How It Works |
|----------------|-------------|
| 🎤 **Transaction Recording** | "Nimeuza nyanya kilo 5, elfu moja" → recorded instantly |
| 🎙️ **Streaming STT** | Real-time partial transcription — see words as you speak (Moonshine v2/Parakeet ready) |
| 📊 **Daily CFO Report** | Morning briefing: revenue, expenses, profit, top products |
| 💰 **Cash Flow Prediction** | "Next Tuesday you may need extra cash for restocking" |
| 🏦 **Credit Building (Alama Score)** | Build credit history from real business data with calibration tracking |
| 📱 **WhatsApp Reports** | Daily/weekly business reports via WhatsApp |
| 🎮 **Financial Literacy** | Gamified learning — points, badges, streaks |
| 🤝 **"Meet Your CFO" Onboarding** | Voice-first 7-step onboarding that learns your business type |
| 🔬 **Impact Measurement** | RCT framework for measuring Msaidizi's real-world impact |

---

## 55+ Specialized Tools (9 Domain Packages)

Msaidizi is a **superagent** — one domain-specific AI brain connected to **55+ specialized tools** organized into **9 domain packages** (`tools/core/`, `tools/financial/`, `tools/market/`, `tools/voice/`, `tools/agriculture/`, `tools/transport/`, `tools/social/`, `tools/credit/`, `tools/inventory/`):

| Category | Tools | Purpose |
|----------|-------|---------|
| 🎯 **Core** | TransactionRecorder, InventoryTracker, CFOEngine, MemoryManager, IntentRouter | Business recording & analysis |
| 🎤 **Voice** | VoicePipeline, SpeechToText, TextToSpeech, ServiceVoiceCommands | Voice-first interaction in 15+ languages |
| 📈 **Market** | MarketPriceTracker, SupplierNetwork, SmartRestock | Real-time pricing & supplier intelligence |
| 💳 **Credit** | AlamaScore, CreditBuilder, MFIIntegration | Alternative credit scoring for informal workers |
| 🤝 **Coordination** | ChamaManager, GroupSavings, SACCOIntegration | Group savings & cooperative management |
| 💰 **Financial** | CashFlowPredictor, ProfitAnalyzer, ExpenseCategorizer | Financial forecasting & analysis |
| 👁️ **Visibility** | ReceiptOCR, ComputerVision, DocumentScanner | CameraX + ML Kit receipt digitization |
| ⏱️ **Time-saving** | SmartNotifications, AutoReport, WhatsAppBridge | Automated reporting & alerts |
| 🔒 **Security** | GuardrailsEngine, EncryptionManager, FraudDetector, PrivacyGuard | Financial integrity, data protection, consent management & data retention |
| 🤖 **AI** | AdaptiveLearner, PatternRecognizer, AnomalyDetector | On-device learning & anomaly detection |
| 📚 **Learning** | GamificationEngine, FinancialLiteracy, GoalTracker | Gamified financial education |
| 🌾 **Agriculture** | HarvestTracker, ProducePriceTracker, YieldPredictor, PostHarvestLossTracker, FishingLog, MiningLog, AquacultureTracker | Farming, fishing, mining & aquaculture intelligence |
| 💼 **Marketplace** | JobMatcher (weighted 5-factor matching), CustomerMatcher, ServiceMarketBroadcaster, WageCalculator, ServicePriceAdvisor | Job & customer matching for service workers |

### Academic Foundations

| Formula / Model | Application in Msaidizi |
|----------------|------------------------|
| **Nash Bargaining Solution** | Fair price negotiation between buyer & seller |
| **Bayesian Updating** | Adaptive learning from transaction patterns |
| **Linear Programming** | Optimal inventory restocking schedules |
| **Monte Carlo Simulation** | Cash flow risk forecasting |
| **Markov Chains** | Customer behavior prediction |
| **Kalman Filter** | Noisy sensor data smoothing (voice, OCR) |
| **Reinforcement Learning** | Personalized financial advice optimization |
| **Graph Theory** | Supplier & customer network analysis |
| **Time Series Analysis** | Revenue & expense trend forecasting |
| **Game Theory** | Chama (group) contribution dynamics |
| **Brier Score** | Alama Score calibration validation |
| **Cohen's d / Welch's t-test** | RCT impact measurement (J-PAL methodology) |
| **Weighted Bipartite Matching** | Skill×0.30 + proximity×0.25 + rating×0.20 + availability×0.15 + price_fit×0.10 |
| **Shapley Values** | SHAP-based credit score explainability (EU AI Act compliance) |

---

## Superagent Architecture

Msaidizi uses a **graph-aware, loop-driven, council-orchestrated** superagent architecture:

### Graph Engineering (`superagent/graph/`)
- **ToolGraph** — 52+ tools connected by dependency, trigger, feeds-into, and conditional edges
- **KnowledgeGraph** — Products, customers, suppliers, and categories as a graph database
- **WorkflowDAG** — Multi-step workflows (daily reports, custom analysis) as directed acyclic graphs
- **GraphAwareContextAssembler** — Enriches LLM context with graph neighborhood data
- **GraphSyncManager** — Delta-based device↔server sync (products, suppliers, prices up; demand signals, credit scores down). Battery-aware, Wi-Fi-only, k-anonymity enforced server-side

### Loop Engineering (`superagent/loops/`)
- **OODALoop** — Observe → Orient → Decide → Act cycle with **LLM-powered DECIDE phase** (tool suggestion via LLM)
- **AdviceRefinementLoop** — Multi-pass advice generation with quality scoring
- **SelfCorrectionLoop** — Automatic retry with **memory-augmented Reflexion pattern** (stored reflections injected into retry context)
- **CircuitBreaker** — External service protection (WhatsApp, sync engine, model downloads)
- **FeedbackLoopIntegration** — User feedback → model improvement pipeline

### Council Engineering (`superagent/council/`)
- **CouncilManager** — Routes intents to specialized councils (Finance, Inventory, Market, Growth, Agriculture, Extractive)
- **CouncilEventBus** — Async pub/sub for inter-council communication with **event sourcing** (all events persisted to KG for audit/replay)
- **InterCouncilDebate** — Multi-council collaborative reasoning (Finance→Market→Growth debate with LLM synthesis)
- **AgentSpawner** — Spawns sub-agents for multi-council tasks (coroutines, ~2KB stack each)
- **CouncilSupervisor** — Manages council lifecycle and error recovery
- **ContextScope** — Scoped context per council (financial data for Finance, inventory for Inventory)

### Onboarding & Worker Types (`superagent/onboarding/`)
- **OnboardingController** — 7-step "Meet Your CFO" state machine (intro → name → business → operations → financial → assessment → first task)
- **OnboardingConversation** — Voice-first Swahili + English conversation with visual card responses
- **WorkerTypeRegistry** — 14 worker types (Mama Mboga, Boda Boda, Jua Kali, Mkulima, M-Pesa, **Hawker**, **Entertainment**, etc.) with per-type tool activation, guardrails, mission documents, and contextual questions
- **AccessControlManager** — Intersects role-based AND worker-type-based tool access

### Impact Measurement (`agent/flywheel/`)
- **AlamaScoreValidator** — Logs every score computation + loan outcome for Brier score, AUC-ROC, calibration curves, drift detection, and bias analysis
- **RCTFramework** — J-PAL methodology RCT with stratified randomization, power analysis, Welch's t-test, Cohen's d, Bonferroni correction, and automated POSITIVE/NEGATIVE/INCONCLUSIVE conclusions
- **ABTestEngine** — A/B testing for feature variants and advice strategies

### Voice-First UI (`ui/screens/`)
- **14 Compose screens** — Dashboard, Onboarding, Voice Interaction, Harvest, Jobs, Pricing, Credit, Inventory, Customers, Goals, Chama, Reports, Services, Settings
- **Always-on voice** — Tap-to-speak on every screen, partial text display with **"Nasikiliza live..." streaming indicator**
- **Swahili-first** — 240+ Swahili prompts, 14 language support
- **Design system** — Custom colors, typography, cards, charts, navigation components

### New Files Added (v0.3)
- `voice/.../StreamingSttEngine.kt` — Real-time streaming STT (20ms chunks)
- `voice/.../DeviceCapabilityDetector.kt` — Auto-detect device tier for model selection
- `agent/.../council/InterCouncilDebate.kt` — Multi-council collaborative reasoning
- `agent/.../guardrails/BatterySaverManager.kt` — 3-mode battery saver
- `core/.../database/SyncQueueDao.kt` — Persisted sync queue (Room)
- `.github/dependabot.yml` — Automated dependency updates
- `scripts/model_versions.json` — OTA model update manifest

---

## Tech Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| **Language** | Kotlin | Primary application language |
| **UI** | Jetpack Compose | Modern declarative UI toolkit (WCAG AA compliant) |
| **LLM Engine** | C++ llama.cpp (Qwen 0.8B) | On-device natural language understanding |
| **Voice Engine** | C++ sherpa-onnx + Silero VAD | On-device STT/TTS in 15+ languages with voice activity detection |
| **Streaming STT** | sherpa-onnx Online Recognizer | Real-time partial transcription (20ms chunks, greedy search) |
| **Database** | Room + SQLCipher (v14) | Encrypted local storage with 7 domain-specific index sets |
| **Camera** | CameraX + ML Kit | Receipt OCR & computer vision |
| **Architecture** | SuperagentHarness | 52+ tools in 9 domain packages with graph/loop/council engineering |
| **Crash Reporting** | Firebase Crashlytics | Production crash reporting (release builds only) |
| **CI/CD** | GitHub Actions | Automated build, test, security scan, Firebase Distribution & release |
| **OTA Updates** | model_versions.json | Device-tier-aware model manifest with checksums & version tracking |
| **Battery Management** | BatterySaverManager | 3-mode battery saver (OFF/LITE/FULL) with graceful degradation |
| **Languages** | 16 | Swahili, English, Luo, Kikuyu + 12 more |

---

## What's New in v0.3 (26+ Implementation Councils)

| Category | Changes |
|----------|--------|
| 🎙️ **Voice** | Streaming STT engine (real-time partial transcription), DeviceCapabilityDetector (auto model selection per device tier) |
| 🧠 **LLM** | Thinking mode for complex intents, dynamic context windows (4096→8192), dynamic temperature |
| 🔗 **Graph** | TransE-style 32-dim embeddings, temporal edges, LLM-based category classification |
| 🔄 **Loops** | LLM-powered OODA DECIDE phase, memory-augmented Reflexion pattern |
| 🏛️ **Councils** | Inter-council debate, event sourcing on CouncilEventBus |
| 💳 **Credit** | Debt trap detection (Fuliza >3x/week), livestock + weather-index insurance |
| 🤝 **Matching** | Weighted 5-factor job matching (skill×0.30 + proximity×0.25 + rating×0.20 + availability×0.15 + price_fit×0.10) |
| 🌾 **Agriculture** | Aquaculture tracker, expanded insurance products |
| 👷 **Workers** | Hawker & Entertainment worker type profiles |
| 🔒 **Privacy** | Data retention policies (6 types), consent management (6 types, bilingual) |
| 📱 **Distribution** | Firebase App Distribution, OTA model updates, Dependabot |
| ⚡ **Performance** | Battery saver mode, Room DB indices (12 new), persisted sync queue (v15→v16) |
| 🤖 **Superagent** | WorkflowDAG checkpointing for multi-step intents, LLM-based tool selection |

---

## Download

**[Download Msaidizi APK](https://github.com/ovalentine964/msaidizi-app/releases/download/v0.1.0/msaidizi-full-release.apk)** — Free, no registration needed

| Variant | Size | Models |
|---------|------|--------|
| **Full** | ~550MB | Qwen 0.8B + Whisper + Piper bundled |
| **Cloud** | ~44MB | Models downloaded on first launch |

- 📱 Android 8.0+
- 🗣️ ARM64 + ARM32
- 🌐 Works offline (Full variant)

---

## Screenshots

### CFO Dashboard
<!-- TODO: Add CFO Dashboard screenshots -->
*Coming soon — dashboard screenshots showing daily briefings, cash flow predictions, and Alama credit score.*

---

## Documentation

- [Superagent Architecture](docs/architecture/arch_superagent_design.md)
- [55+ Tools Definition](docs/architecture/superagent_tools_definition.md)
- [Grand Synthesis](docs/architecture/grand_synthesis_architecture.md)
- [Growth Model Evolution](docs/architecture/growth_model_evolution.md)
- [On-Device Learning](docs/architecture/growth_ondevice_learning.md)
- [Onboarding Spec](../council-reports/specs/ONBOARDING-SPEC.md)
- [Worker Type Taxonomy](../council-reports/validation/01-worker-taxonomy.md)

---

## Company

**Angavu Intelligence Ltd.** — Making the Invisible Economy Visible

- 🌐 [Website](https://ovalentine964.github.io/angavu-intelligence/)
- 📧 hello@angavuintelligence.com
- 📍 Migori, Kenya

---

---

## Version

**v0.3.0** — Built by 26+ implementation councils

*Built for Africa's 600 million informal workers. Free forever.*
