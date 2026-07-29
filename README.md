<p align="center">
  <img src=".github/banner.svg" alt="Msaidizi — AI-Powered CFO for 600 Million Informal Workers" width="100%">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android">
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/Offline--First-FF6B35?style=flat-square" alt="Offline-First">
  <img src="https://img.shields.io/badge/Voice--First-E8A838?style=flat-square" alt="Voice-First">
  <img src="https://img.shields.io/badge/Tools-52+-1B4965?style=flat-square" alt="52+ Tools">
  <img src="https://img.shields.io/badge/CI-Passing-3DDC84?style=flat-square" alt="CI">
  <img src="https://img.shields.io/badge/License-Apache%202.0-blue?style=flat-square" alt="License">
</p>

> **Every mama mboga deserves a CFO.** Msaidizi is a free, voice-first, offline-first AI CFO that runs on your phone. Speak in your language — track your business, understand your profit, build your credit.
>
> 📋 **New to the codebase?** Read [`program.md`](program.md) for a declarative overview of the superagent's identity, intent types, guardrails, memory hierarchy, and all operational parameters — extracted from the source code into one file.
> For programmatic access, use [`program.json`](program.json).

**Built by [Angavu Intelligence Ltd.](https://ovalentine964.github.io/angavu-intelligence/)** — *Making the Invisible Economy Visible*

---

## What Msaidizi Does

| CFO Capability | How It Works |
|----------------|-------------|
| 🎤 **Transaction Recording** | "Nimeuza nyanya kilo 5, elfu moja" → recorded instantly |
| 📊 **Daily CFO Report** | Morning briefing: revenue, expenses, profit, top products |
| 💰 **Cash Flow Prediction** | "Next Tuesday you may need extra cash for restocking" |
| 🏦 **Credit Building (Alama Score)** | Build credit history from real business data |
| 📱 **WhatsApp Reports** | Daily/weekly business reports via WhatsApp |
| 🎮 **Financial Literacy** | Gamified learning — points, badges, streaks |

---

## 52+ Specialized Tools

Msaidizi is a **superagent** — one domain-specific AI brain connected to **52+ specialized tools** across 13 categories:

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
| 🔒 **Security** | GuardrailsEngine, EncryptionManager, FraudDetector | Financial integrity & data protection |
| 🤖 **AI** | AdaptiveLearner, PatternRecognizer, AnomalyDetector | On-device learning & anomaly detection |
| 📚 **Learning** | GamificationEngine, FinancialLiteracy, GoalTracker | Gamified financial education |
| 🌾 **Agriculture** | HarvestTracker, ProducePriceTracker, YieldPredictor, PostHarvestLossTracker, FishingLog, MiningLog | Farming, fishing & mining intelligence |
| 💼 **Marketplace** | JobMatcher, CustomerMatcher, ServiceMarketBroadcaster, WageCalculator, ServicePriceAdvisor | Job & customer matching for service workers |

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

---

## Superagent Architecture

Msaidizi uses a **graph-aware, loop-driven, council-orchestrated** superagent architecture:

### Graph Engineering (`superagent/graph/`)
- **ToolGraph** — 52+ tools connected by dependency, trigger, feeds-into, and conditional edges
- **KnowledgeGraph** — Products, customers, suppliers, and categories as a graph database
- **WorkflowDAG** — Multi-step workflows (daily reports, custom analysis) as directed acyclic graphs
- **GraphAwareContextAssembler** — Enriches LLM context with graph neighborhood data

### Loop Engineering (`superagent/loops/`)
- **OODALoop** — Observe → Orient → Decide → Act cycle for every user interaction
- **AdviceRefinementLoop** — Multi-pass advice generation with quality scoring
- **SelfCorrectionLoop** — Automatic retry with exponential backoff on tool failures
- **CircuitBreaker** — External service protection (WhatsApp, sync engine, model downloads)
- **FeedbackLoopIntegration** — User feedback → model improvement pipeline

### Council Engineering (`superagent/council/`)
- **CouncilManager** — Routes intents to specialized councils (Finance, Inventory, Market, Growth, Agriculture, Extractive)
- **CouncilEventBus** — Async pub/sub for inter-council communication
- **AgentSpawner** — Spawns sub-agents for multi-council tasks (coroutines, ~2KB stack each)
- **CouncilSupervisor** — Manages council lifecycle and error recovery
- **ContextScope** — Scoped context per council (financial data for Finance, inventory for Inventory)

### Voice-First UI (`ui/screens/`)
- **14 Compose screens** — Dashboard, Onboarding, Voice Interaction, Harvest, Jobs, Pricing, Credit, Inventory, Customers, Goals, Chama, Reports, Services, Settings
- **Always-on voice** — Tap-to-speak on every screen, partial text display
- **Swahili-first** — 240+ Swahili prompts, 14 language support
- **Design system** — Custom colors, typography, cards, charts, navigation components

---

## Tech Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| **Language** | Kotlin | Primary application language |
| **UI** | Jetpack Compose | Modern declarative UI toolkit |
| **LLM Engine** | C++ llama.cpp (Qwen 0.8B) | On-device natural language understanding |
| **Voice Engine** | C++ sherpa-onnx | On-device STT/TTS in 15+ languages |
| **Database** | Room + SQLCipher | Encrypted local transaction storage |
| **Camera** | CameraX + ML Kit | Receipt OCR & computer vision |
| **Architecture** | SuperagentHarness | 52+ tool orchestration with graph/loop/council engineering |
| **CI/CD** | GitHub Actions | Automated build, test, security scan & release |

---

## Download

**[Download Msaidizi APK](https://github.com/ovalentine964/msaidizi-app/releases/download/latest/msaidizi-release.apk)** — Free, no registration needed

- 📱 Android 8.0+
- 💾 ~500MB (all models bundled)
- 🗣️ ARM64 + ARM32
- 🌐 Works offline

---

## Screenshots

### CFO Dashboard
<!-- TODO: Add CFO Dashboard screenshots -->
*Coming soon — dashboard screenshots showing daily briefings, cash flow predictions, and Alama credit score.*

---

## Documentation

- [Superagent Architecture](docs/architecture/arch_superagent_design.md)
- [52+ Tools Definition](docs/architecture/superagent_tools_definition.md)
- [Graph Engineering](docs/architecture/graph_engineering_report.md)
- [Loop Engineering](docs/architecture/loop_engineering_report.md)
- [Council Engineering](docs/architecture/hernes_engineering_report.md)
- [Grand Synthesis](docs/architecture/grand_synthesis_architecture.md)
- [Growth Model Evolution](docs/architecture/growth_model_evolution.md)
- [On-Device Learning](docs/architecture/growth_ondevice_learning.md)

---

## Company

**Angavu Intelligence Ltd.** — Making the Invisible Economy Visible

- 🌐 [Website](https://ovalentine964.github.io/angavu-intelligence/)
- 📧 hello@angavuintelligence.com
- 📍 Migori, Kenya

---

*Built for Africa's 600 million informal workers. Free forever.*
