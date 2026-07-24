# 🏦 Msaidizi — AI-Powered CFO for 600 Million Informal Workers

[![Android](https://img.shields.io/badge/Platform-Android-green)]()
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple)]()
[![License](https://img.shields.io/badge/License-Proprietary-red)]()

> **Every mama mboga deserves a CFO.** Msaidizi is a free, voice-first, offline-first AI CFO that runs on your phone. Speak in your language — track your business, understand your profit, build your credit.

**Built by [Angavu Intelligence Ltd.](https://ovalentine964.github.io/angavu-intelligence/)** — Migori, Kenya

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

## Superagent Architecture

Msaidizi is a **superagent** — one domain-specific AI brain connected to 20 specialized tools:

```
ONE BRAIN (SuperagentHarness)
├── TransactionRecorder — records sales by voice
├── InventoryTracker — tracks stock levels
├── CFOEngine — daily briefings, predictions, advice
├── VoicePipeline — STT/TTS in 15+ languages
├── GamificationEngine — points, levels, badges
├── GoalTracker — savings goals, loan tracking
├── MemoryManager — 5-layer memory hierarchy
├── GuardrailsEngine — financial integrity checks
├── AdaptiveLearner — learns YOUR patterns
└── ... (12 more tools)
```

**Tech Stack:** Kotlin • Jetpack Compose • llama.cpp (Qwen 0.8B) • sherpa-onnx (Voice) • Room + SQLCipher

---

## Download

**[Download Msaidizi APK](https://github.com/ovalentine964/msaidizi-app/releases/download/latest/msaidizi-release.apk)** — Free, no registration needed

- 📱 Android 8.0+
- 💾 ~500MB (all models bundled)
- 🗣️ ARM64 + ARM32
- 🌐 Works offline

---

## Documentation

- [Superagent Architecture](docs/architecture/arch_superagent_design.md)
- [40 Tools Definition](docs/architecture/superagent_tools_definition.md)
- [Grand Synthesis](docs/architecture/grand_synthesis_architecture.md)
- [Worker Type Validation](docs/research/deep_worker_validation.md)

---

## Company

**Angavu Intelligence Ltd.** — Africa's Economic Nervous System

- 🌐 [Website](https://ovalentine964.github.io/angavu-intelligence/)
- 📧 hello@angavuintelligence.com
- 📍 Migori, Kenya

---

*Built for Africa's 600 million informal workers. Free forever.*
