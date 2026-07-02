![Biashara Intelligence](docs/logo-banner.svg)

# Msaidizi — The CFO for Africa's 600M+ Informal Workers

**Not an assistant. A CFO. Proactive, voice-first, offline-first. Speaks your language.**

**Version:** 0.1.0

## What Is Msaidizi?

Msaidizi is the on-device app that captures data from informal workers and delivers economic intelligence. It's the data source for Biashara Intelligence (cloud platform).

- 🎤 **Voice-first** — record transactions by speaking in your language
- 📴 **Offline-first** — works without internet, syncs when connected
- 🧑‍💼 **CFO, not assistant** — proactive daily briefings, cash flow forecasting, credit readiness
- 🌍 **14 dialects** — Swahili, Sheng, Kikuyu, Dholuo, Luhya, Kalenjin, Maasai, Migori, Somali, Amharic, Yoruba, Igbo, Hausa, Zulu, Xhosa
- 📊 **Business Flow** — see your money flow like M-Pesa, but for your business
- 🎮 **Gamification** — points, levels, streaks, and badges to build healthy financial habits
- 💰 **Wealth Mindset** — 10 daily "Rich Habits" tracking to build wealth discipline
- 🤲 **Tithe & Giving** — track tithes, offerings, and charitable giving with voice commands
- 🎯 **Goals & Loans** — set savings goals, track loan repayments, monitor progress
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
│  🎮 Pointi: 1,240  Lv.3        │
│  🔥 Streak: siku 7 mfululizo   │
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

### 5-Agent System
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

### Gamification System
| Feature | What It Does |
|---------|-------------|
| Points | Earn points for recording transactions, checking reports, hitting streaks |
| Levels | Progress through 6 levels (Beginner → Mogul) |
| Streaks | Track consecutive days of business activity |
| Badges | Unlock achievements for milestones |
| Leaderboards | Compare with other workers (anonymized) |

### Wealth Mindset — Rich Habits
10 daily habits tracked to build financial discipline:
1. Record all sales
2. Check your balance
3. Separate business & personal money
4. Save before spending
5. Track every expense
6. Review weekly performance
7. Set a financial goal
8. Learn one new thing
9. Help another business owner
10. Rest and reflect

### Tithe & Giving
- **Voice commands**: "Nilichanga KSh 500 kanisani" → recorded
- **Giving history**: Track tithes, offerings, charitable donations
- **Reports**: Monthly giving summary, percentage of income
- **Consistency tracking**: Streaks for regular giving

### Goals & Loans
- **Savings goals**: "Nataka kusave KSh 10,000 kwa duka jipya"
- **Goal tracking**: Visual progress bars, daily contribution recommendations
- **Loan tracking**: Record loans given and taken, repayment schedules
- **Credit readiness**: 4-factor score (consistency, savings, revenue growth, stability)

## Voice Pipeline

```
Audio → Silero VAD → Whisper STT (ONNX, INT4) →
  Language Detection → Dialect Adapter (14 available) →
  Intent Classification → Business Agent →
  Piper TTS / MMS TTS → Audio Response
```

### On-Device LLM (llama.cpp NDK)
| Component | Details |
|-----------|---------|
| Engine | llama.cpp via NDK (2-5x faster than pure Java) |
| Model | Qwen 0.5B (GGUF, ~300 MB) |
| Purpose | Intent classification, advice generation, conversation |
| Inference | On-device, no cloud required |
| Optimization | ARM NEON, quantized (Q4_K_M) |

### Models
| Model | Format | Size | Purpose |
|-------|--------|------|---------|
| Silero VAD | ONNX | 2.5 MB | Voice activity detection |
| Whisper Tiny INT4 | ONNX | 40 MB | Speech-to-text |
| Piper Swahili | ONNX | 25 MB | Text-to-speech |
| Meta MMS | ONNX | 65 MB each | TTS for 10+ African languages |
| Qwen 0.5B | GGUF | 300 MB | On-device LLM (optional) |

## 14 Dialect Adapters

| Region | Dialects |
|--------|----------|
| East Africa | Swahili (base), Sheng, Kikuyu, Dholuo, Luhya, Kalenjin, Maasai, Migori |
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

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Language | Kotlin 1.9.22 | Android app development |
| Architecture | MVVM + Clean Architecture | Code organization |
| DI | Hilt | Dependency injection |
| Database | Room + SQLCipher | Encrypted local storage |
| ML Inference | ONNX Runtime | Voice models (Whisper, Piper, Silero) |
| LLM | llama.cpp NDK | On-device large language model |
| HTTP | Ktor | Network client |
| Async | Coroutines | Asynchronous operations |
| Serialization | kotlinx.serialization | Data models |
| Build | Gradle (Kotlin DSL) | Build system |
| Target | Android SDK 34 | API level |
| Min SDK | Android 11 (API 30) | Minimum supported |

## Installation (For Users)

### Download & Install
1. **Download** the APK from the link below (~380 MB, everything included)
2. **Tap Install** when Android asks — no extra settings needed
3. **Open Msaidizi** and start talking in your language

**[⬇ Download Msaidizi APK](https://github.com/ovalentine964/msaidizi-app/releases/download/latest/msaidizi.apk)**

> **Requirements:** Android 11+ · ARM processor · 2 GB RAM minimum

## Build (For Developers)

```bash
# Clone the repository
git clone https://github.com/ovalentine964/msaidizi-app.git
cd msaidizi-app

# Build debug APK
./gradlew assembleDebug

# The APK will be at:
# app/build/outputs/apk/debug/app-debug.apk
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
