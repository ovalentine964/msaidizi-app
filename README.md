# Msaidizi — AI Superagent for Informal Workers

> **Voice-first. Offline-first. Learns your business. Gets smarter every day.**

Msaidizi is an AI-powered business assistant built for Africa's 600+ million informal workers. It speaks your language, understands your business, and helps you make better decisions — all from your phone, even without internet.

## What Is Msaidizi?

Msaidizi is not just an app. It's a **superagent** — an AI that:
- **Records your business** by voice (just speak, no typing)
- **Shows where your money goes** (revenue, costs, profit, inventory)
- **Predicts your cash flow** before problems hit
- **Builds your credit score** from your actual business data
- **Gets smarter every day** as it learns your patterns

## Who Is It For?

Every informal worker in Africa:
- 🥬 **Mama mboga** (vegetable vendor)
- 🏍️ **Boda boda** rider
- 🏪 **Dukawallah** (shop owner)
- 🔧 **Fundi** (repair technician)
- 🍲 **Mama lishe** (food vendor)
- 👗 **Machinga** (hawker)
- 🌾 **Mkulima** (farmer)
- And 18+ more worker types

## Key Features

| Feature | What It Does |
|---------|-------------|
| **Voice Recording** | Speak in Swahili/Sheng — "Nimeuza nyanya kwa elfu tano" |
| **Business Flow** | See where your money goes: revenue, costs, profit |
| **Alama Score** | Credit score from your business (not your phone) |
| **Cash Flow Prediction** | "Next week will be tight — buy stock now" |
| **WhatsApp Reports** | Text "nipatie ripoti ya benki" → get bank-ready PDF |
| **Growth Rewards** | Daily streaks, referral bonuses, peer comparison |

## Architecture

```
┌─────────────────────────────────────────────────┐
│              MSAIDIZI SUPERAGENT                 │
├─────────────────────────────────────────────────┤
│  ON-DEVICE (Qwen 1.8B via llama.cpp)            │
│  ├── Voice Pipeline (STT → Agent → TTS)         │
│  ├── Business Flow Engine                        │
│  ├── Alama Score Engine                          │
│  ├── Cash Flow Predictor                         │
│  ├── Growth Engine (referral, streaks)           │
│  └── Adaptive Language Learning                  │
├─────────────────────────────────────────────────┤
│  CLOUD (Angavu Intelligence Backend)             │
│  ├── Collective Intelligence                     │
│  ├── Federated Learning                          │
│  ├── WhatsApp Business Reports                   │
│  └── Outcome-Based Pricing Engine                │
└─────────────────────────────────────────────────┘
```

## Tech Stack

| Layer | Technology |
|-------|-----------|
| **Language** | Kotlin (Android) |
| **UI** | Jetpack Compose |
| **On-Device AI** | Qwen 1.8B via llama.cpp NDK |
| **Voice** | Whisper STT + Kokoro TTS |
| **Database** | Room (SQLite) |
| **Security** | Post-quantum crypto (ML-KEM/ML-DSA) |
| **Backend** | Python + Rust via PyO3 |

## Download

[Download Msaidizi APK](https://github.com/ovalentine964/msaidizi-app/releases/download/latest/msaidizi-release.apk) (~700MB, all models bundled)

## License

Proprietary — Angavu Intelligence Ltd.

---

*Built for the 83%. Powered by AI. Voice-first.*
