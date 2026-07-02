![Biashara Intelligence](docs/logo-banner.svg)

# Msaidizi — The CFO for Africa's 600M+ Informal Workers

**Not an assistant. A CFO. Proactive, voice-first, offline-first. Speaks your language.**

## What Is Msaidizi?

Msaidizi is the on-device app that captures data from informal workers and delivers economic intelligence. It's the data source for Biashara Intelligence (cloud platform).

- 🎤 **Voice-first** — record transactions by speaking in your language
- 📴 **Offline-first** — works without internet, syncs when connected
- 🧑‍💼 **CFO, not assistant** — proactive daily briefings, cash flow forecasting, credit readiness
- 🌍 **13+ African dialects** — Swahili, Sheng, Kikuyu, Dholuo, Yoruba, Hausa, Zulu, and more
- 📊 **Business Flow** — see your money flow like M-Pesa, but for your business
- 🔒 **Privacy-first** — data stays on device, federated learning for improvements

## Bootstrap: Name Your CFO

When a worker first opens Msaidizi:
1. Msaidizi introduces itself as their CFO
2. Asks the worker's name
3. Learns about their business
4. **Worker names Msaidizi** (e.g., "Rafiki", "Msaada")
5. First transaction recorded together

This creates psychological ownership — it's YOUR CFO, not just an app.

## Business Flow (Like M-Pesa for Business)

```
┌─────────────────────────────────┐
│  Habari Maria! ☀️               │
│  Rafiki wako wa biashara        │
├─────────────────────────────────┤
│  💰 LEO                         │
│  Mauzo:    KSh 3,200 ↑          │
│  Gharama:  KSh 1,800            │
│  Faida:    KSh 1,400 ↑          │
│                                 │
│  📊 Biashara Flow               │
│  ┌─💰─→ 🏪 ─→ 📈─→ 💵─→ 🏦    │
│                                 │
│  ⚠️ Arifu: Nyanya zinaisha     │
│  🏦 Alama: 72/100 (mkopo tayari)│
└─────────────────────────────────┘
```

## Architecture

```
Worker speaks → Whisper STT → Intent Classification →
  Transaction recorded (Room DB) →
  Business Flow updated →
  CFO Engine generates advice →
  When online: Sync to Biashara Intelligence →
  Intelligence returned → Displayed in worker's language
```

### 5 Agent System
| Agent | Role | Degree Units |
|-------|------|-------------|
| **Orchestrator** | Routes intents, manages agents | ECO 103/104, MAT 121/124 |
| **BusinessAgent** | Records transactions, tracks business | ECO 101/201, BCB 108 |
| **AnalysisAgent** | Statistics, trends, forecasting | STA 142/241, ECO 202/203, STA 244 |
| **AdvisorAgent** | Financial advice, credit readiness | ECO 206/209/210/322 |
| **LearningAgent** | Pattern learning, A/B testing | STA 342/343/347, ECO 315 |

### CFO Engine (Proactive, Not Reactive)
| Feature | What It Does | Degree Unit |
|---------|-------------|-------------|
| Daily Briefing | Morning P&L without asking | ECO 201 |
| Cash Flow Forecast | "Your money runs out in 12 days" | STA 341 |
| Restock Alerts | Velocity-based, before stockouts | ECO 210 |
| Savings Advice | 20% of daily profit toward goals | ECO 206 |
| Credit Readiness | 4-factor score out of 100 | STA 341 |
| Risk Alerts | Revenue decline, margin compression | STA 342 |

## Voice Pipeline

```
Audio → Silero VAD → Whisper STT (ONNX, INT4) →
  Language Detection → Dialect Adapter (15 available) →
  Intent Classification → Business Agent →
  Piper TTS / MMS TTS → Audio Response
```

### Models
| Model | Format | Size | Purpose |
|-------|--------|------|---------|
| Silero VAD | ONNX | 2.5 MB | Voice activity detection |
| Whisper Tiny INT4 | ONNX | 40 MB | Speech-to-text |
| Piper Swahili | ONNX | 25 MB | Text-to-speech |
| Meta MMS | ONNX | 65 MB each | TTS for 10+ African languages |
| Qwen 0.5B | GGUF | 300 MB | On-device LLM (optional) |

## 13+ Dialect Adapters

| Region | Dialects |
|--------|----------|
| East Africa | Swahili, Sheng, Kikuyu, Dholuo, Luhya, Kalenjin, Maasai |
| Horn of Africa | Somali, Amharic |
| West Africa | Yoruba, Igbo, Hausa |
| Southern Africa | Zulu, Xhosa |

Each adapter maps phonemes, provides business vocabulary, handles number formats, and includes cultural greetings.

## Worker Types Supported (25+)

| Category | Workers |
|----------|---------|
| Trade | Mama mboga, dukawallah, cross-border traders, mitumba sellers, hawkers |
| Transport | Boda boda, matatu, tuk-tuk, taxi/ride-hail |
| Agriculture | Smallholder farmers, fish traders, food processors |
| Services | Hairdressers, mechanics, tailors, laundry |
| Manufacturing | Jua kali, furniture makers, brick makers |
| Digital | M-Pesa agents, phone repair, social media sellers |

## Reports Delivered via WhatsApp

| Report | Frequency | Content |
|--------|-----------|---------|
| Daily | 7 PM | P&L, restock alerts, forecast |
| Weekly | Mon 8 AM | Trends, customer insights, health score |
| Monthly | 1st | Revenue growth, credit readiness, recommendations |
| 6-Month | Jun/Dec | Business review, formalization pathway |
| Yearly | Dec 31 | Annual review, tax summary, goals |

## Tech Stack

- **Kotlin 1.9.22** + Android SDK 34
- **MVVM + Clean Architecture**
- **Hilt** (dependency injection)
- **Room + SQLCipher** (encrypted local database)
- **ONNX Runtime** (ML inference)
- **Ktor** (HTTP client)
- **Coroutines** (async)

## Build

```bash
./gradlew assembleDebug
```

Target: Samsung Galaxy A03 (2GB RAM, ARM, Android 11+)

## Security

- SQLCipher encrypted database
- Certificate pinning
- SHA-256 model integrity verification
- Federated learning (data stays on device)
- HMAC-SHA256 worker ID hashing

## License

Proprietary — Biashara Intelligence
