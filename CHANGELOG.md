# Changelog — Msaidizi App

All notable changes to the Msaidizi Android app are documented here.

---

## [v0.3.0] — 2026-08-01

### 🎙️ Voice & Streaming
- **NEW: Streaming STT Engine** (`StreamingSttEngine.kt`) — Real-time partial transcription using sherpa-onnx online recognizer. Processes 20ms audio chunks for instant feedback. Automatic endpoint detection (2.4s/1.2s/20s rules).
- **NEW: JNI Streaming Bridge** — 8 new JNI functions for streaming recognizer (create, feed, decode, getResult, isEndpoint, reset, destroy).
- **NEW: DeviceCapabilityDetector** — Auto-detects device tier (BUDGET/MID_RANGE/FLAGSHIP) based on RAM, CPU cores, SDK version, NNAPI support. Selects optimal Whisper model variant.
- VoicePipeline refactored: new `listenWithStreaming()` method, streaming-first with offline Whisper fallback.
- Streaming indicator in UI: "Nasikiliza live... — Listening live..." with hearing icon.

### 🧠 LLM & Reasoning
- **Thinking mode** for complex intents (ASK_ADVICE, LOAN_COMPARE, INSURANCE_MATCH, reports) — strips `<think>` tags from output.
- **Dynamic context windows**: 4096 default, 8192 for advice/report/profit intents (was 2048).
- **Dynamic max tokens**: 768 for advice, 512 for reports, 256 for simple queries (was 256 fixed).
- **Dynamic temperature**: 0.4 for factual tasks, 0.7 for creative advice.
- ContextAssembler: intent-aware token budget allocation (L1-L4 layers).

### 🔗 Graph Engineering
- **TransE-style 32-dim embeddings** with structural hashing for semantic similarity search.
- **Temporal edges**: `addEdge()` now tracks `created_at`/`updated_at` timestamps.
- **LLM-based category classification**: 3-tier approach (hardcoded → cached KG facts → LLM). Expanded product coverage (cassava, avocado, sorghum, millet, etc.).

### 🔄 Loop Engineering
- **LLM-powered OODA DECIDE phase**: `suggestToolsViaLlm()` for intelligent tool selection.
- **Memory-augmented Reflexion pattern**: `StoredReflection` — failures stored in knowledge base, matching reflections injected into retry context.
- ORIENT phase now accepts LLM engine for anomaly detection.

### 🏛️ Council Engineering
- **InterCouncilDebate** — Multi-council collaborative reasoning (Finance→Market→Growth debate with LLM synthesis).
- **Event sourcing** on CouncilEventBus — all events persisted to KG facts for audit/replay. `replayEvents()` method.

### 💳 Credit & Financial
- **Debt trap detection** — New `debt_trap_check` action in CreditReadiness. Detects Fuliza dependency (>3x/week), debt-to-income ratio >40%, multiple concurrent loans, declining Alama Score.
- **Livestock insurance** (Britam Mifugo) and **weather-index insurance** (AICE Africa) products added to InsuranceMatcher.
- Risk levels: LOW/WASTANI/JUU with actionable Swahili recommendations.

### 🤝 Matching & Marketplace
- **Weighted 5-factor job matching** in JobMatcher: skill×0.30 + proximity×0.25 + rating×0.20 + availability×0.15 + price_fit×0.10.
- `calculatePriceFit()` — budget compatibility scoring.
- Results now show match percentage.

### 👷 Worker Types
- **Hawker** and **Entertainment** worker type profiles added to WorkerTypeRegistry.
- Total worker types: 14 (was 12).

### 🌾 Agriculture
- **AquacultureTracker** tool added.
- Expanded insurance products for livestock and weather-index coverage.

### 🔒 Privacy & Security
- **Data retention policies** — `RetentionPeriod` enum with per-type policies: Transactions (2yr), Location (30d), Conversations (90d), Model gradients (7d).
- **Consent management** — 6 consent types with `hasConsent()`, `getConsentDescription()` (bilingual EN/SW).
- `shouldPurge()` and `getPurgeCutoff()` for automatic data cleanup.

### 📱 Distribution & CI/CD
- **Firebase App Distribution** — New `firebase-distribute` CI job, uploads to internal-testers group on main branch push.
- **OTA model updates** — `model_versions.json` device-tier-aware manifest with SHA-256 checksums and version tracking.
- **Dependabot** — Weekly Gradle + GitHub Actions updates with grouped PRs (Compose, Hilt, Room, Firebase, Kotlin).

### ⚡ Performance
- **BatterySaverManager** — 3-mode battery saver (OFF/LITE/FULL) with graceful degradation of LLM, TTS, sync, and proactive systems.
- **12 new Room @Index annotations** on anomaly_history, learned_vocabulary, business_patterns, hire_purchase_agreements, emergency_contacts, ride_users.
- **Persisted sync queue** — `SyncQueueEntity` + `SyncQueueDao` with Room. Survives app restarts/process death. Database v15→v16.
- LlamaCppEngine now supports `reloadWithContextSize()` for dynamic context window resizing.

### 🤖 Superagent
- **WorkflowDAG checkpointing** for multi-step intents (daily/weekly/monthly reports).
- `isWorkflowIntent()` routing in SuperagentHarness.
- LLM-based tool suggestion integrated into OODA DECIDE phase.

---

## [v0.2.0] — 2026-07-01

- "Meet Your CFO" onboarding (7-step state machine)
- Per-worker-type tool activation (12 worker types)
- Device↔server graph sync (delta-based, battery-aware, Wi-Fi-only)
- Alama Score validation (Brier score, AUC-ROC, calibration curves)
- RCT impact framework (J-PAL methodology)
- Luo + Kikuyu language support
- 52+ tools across 9 domain packages

---

## [v0.1.0] — 2026-06-01

- Initial release
- On-device LLM (Qwen 0.8B via llama.cpp)
- On-device STT/TTS (Whisper + Piper via sherpa-onnx)
- Voice-first transaction recording
- CFO daily reports
- Cash flow prediction
- Alama Score credit building
- WhatsApp reports
- Financial literacy gamification
- Knowledge graph + OODA loop
- 14 Compose screens
- 240+ Swahili prompts

---

*Built by [Angavu Intelligence Ltd.](https://ovalentine964.github.io/angavu-intelligence/) — Making the Invisible Economy Visible*
