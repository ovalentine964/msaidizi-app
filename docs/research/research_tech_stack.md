# Msaidizi — Tech Stack Research & Recommendation

**Date:** 2025-07-24  
**Project:** AI-powered mobile app for informal sector workers in Africa  
**Target markets:** Kenya, Tanzania, Uganda, Nigeria, Ghana (Android-dominant)

---

## 1. Mobile Framework Decision

### Options Evaluated

| Framework | Pros | Cons |
|-----------|------|------|
| **Kotlin Native (Android)** | Best on-device AI integration; direct NDK access for llama.cpp/MLC; best performance; native Android ecosystem | Android-only; higher dev cost if iOS later |
| **Flutter** | Cross-platform; good UI; Dart is easy | FFI for native AI libs is painful; no mature on-device LLM integration; Flutter GPU access limited |
| **Kotlin Multiplatform (KMP)** | Share business logic; native UI per platform | Young ecosystem; limited AI library bindings; still need platform-specific AI code |

### ✅ Recommendation: **Kotlin Native (Android-first)**

**Rationale:**
- **85-90% smartphone penetration in Africa is Android** (GSMA 2024). iOS is negligible in target markets.
- On-device AI (llama.cpp, MLC LLM, ONNX Runtime) requires **direct NDK/JNI access** — Kotlin native gives this naturally. Flutter FFI adds latency and complexity.
- The voice pipeline (STT/TTS) needs low-level audio buffer access for offline processing — native Android APIs are essential.
- **Kotlin is the official Android language**, best supported by Google, best tooling, best hiring pool.
- If iOS is needed later, KMP can share the business logic layer (data models, networking, AI orchestration) while keeping Android UI native.

**Hybrid approach (future-proofing):**
- Keep the AI/voice/data layers as Kotlin libraries with clean interfaces
- Business logic (multi-agent orchestration, financial calculations) in KMP-compatible modules
- If iOS ever needed, port UI layer only

---

## 2. On-Device LLM — Framework Comparison

### Options Evaluated

| Framework | GPU Backend | Android Support | Model Format | Maturity |
|-----------|-------------|----------------|--------------|----------|
| **llama.cpp** | Vulkan, OpenCL, CPU (ARM NEON) | ✅ Excellent | GGUF | ★★★★★ Most mature |
| **MLC LLM** | Vulkan, OpenCL, Metal, WebGPU | ✅ Good | MLC compiled | ★★★★ Compiler-driven, fast |
| **MediaPipe LLM** | GPU Delegate, NNAPI | ✅ Good | TFLite | ★★★★ Google-backed |
| **ONNX Runtime Mobile** | NNAPI, XNNPACK | ✅ Good | ONNX | ★★★ Microsoft-backed |
| **ExecuTorch** | Vulkan, CPU | ⚠️ Early | PyTorch | ★★ Meta, still maturing |
| **MNN** | OpenCL, Vulkan, CPU | ✅ Good | MNN | ★★★ Alibaba, good for Qwen |

### ✅ Recommendation: **llama.cpp (primary) + MLC LLM (performance fallback)**

**Rationale:**
- **llama.cpp** is the de facto standard for on-device LLM inference. Best community, best model support (GGUF format), excellent Android support via JNI bindings.
- Supports Vulkan GPU acceleration on Adreno (Qualcomm) and Mali (MediaTek) — the two dominant mobile GPU families in African markets.
- **MLC LLM** as a secondary option: compiler-driven approach (TVM) can squeeze more performance on specific chipsets, especially with OpenCL on Adreno GPUs.
- **MNN** (Alibaba) is worth monitoring — specifically optimized for Qwen models and has excellent ARM NEON performance on budget chips.

**Integration approach:**
```
Kotlin App
  → JNI bridge (llama.cpp native lib)
  → GGUF model loaded from app storage
  → Inference in background thread
  → Results streamed back to Kotlin coroutine
```

---

## 3. Quantized Models for Mobile

### Target Hardware Profile
African market phones (2024-2025):
- **Budget:** MediaTek Helio G-series, 3-4GB RAM, Android 12+
- **Mid-range:** Snapdragon 6-series, 4-6GB RAM
- **Best case:** Snapdragon 7/8-series, 6-8GB RAM

### Model Candidates

| Model | Params | GGUF Q4_K_M Size | RAM Needed | Quality | Multilingual |
|-------|--------|-------------------|------------|---------|-------------|
| **Qwen2.5-1.5B-Instruct** | 1.5B | ~1.0 GB | ~1.5 GB | ★★★★ Best for size | ✅ 29+ languages |
| **Qwen2.5-0.5B-Instruct** | 0.5B | ~0.4 GB | ~0.8 GB | ★★★ Decent | ✅ 29+ languages |
| **Phi-3.5-mini (3.8B)** | 3.8B | ~2.3 GB | ~3.0 GB | ★★★★★ Best quality | ⚠️ English-focused |
| **Gemma-2-2B** | 2B | ~1.4 GB | ~2.0 GB | ★★★★ Good | ⚠️ Limited multilingual |
| **SmolLM2-1.7B** | 1.7B | ~1.1 GB | ~1.6 GB | ★★★★ Good | ✅ Decent |
| **Llama-3.2-1B** | 1B | ~0.7 GB | ~1.2 GB | ★★★ OK | ⚠️ English-focused |

### ✅ Recommendation: **Qwen2.5-1.5B-Instruct (Q4_K_M primary, Q3_K_M for low-RAM devices)**

**Rationale:**
- **Best multilingual support** among small models — critical for Swahili, and other African languages.
- 1.5B is the sweet spot: small enough to run on 3GB RAM phones, large enough to be useful.
- Q4_K_M quantization: ~1GB model size, fits comfortably on any phone with 3GB+ RAM.
- **Q3_K_M fallback** (~0.7GB) for budget devices with only 2-3GB available RAM.
- Qwen2.5 has strong instruction following, structured output (JSON), and function calling — essential for the multi-agent system.
- Official GGUF quantizations available from Alibaba/Qwen on HuggingFace.
- Supports context windows up to 32K tokens (enough for financial conversations).

**Model delivery strategy:**
- Ship Q3_K_M (~700MB) in initial APK download
- Offer Q4_K_M (~1GB) as optional download for better quality
- Model stored in app-private storage, downloaded once, verified with checksum
- Delta updates for model versions to save bandwidth

---

## 4. Voice Pipeline — Offline STT/TTS

### STT (Speech-to-Text) Options

| Engine | Offline | Android | Languages | Size | Quality |
|--------|---------|---------|-----------|------|---------|
| **Whisper.cpp** | ✅ | ✅ JNI | 99 languages | ~140MB (tiny) to 3GB (large) | ★★★★★ Best accuracy |
| **Vosk** | ✅ | ✅ Native | 20+ languages | ~50MB per lang | ★★★ Lightweight |
| **sherpa-onnx** | ✅ | ✅ Native | 100+ models | Varies | ★★★★ Excellent variety |
| **Coqui STT** | ✅ | ⚠️ Needs work | English-focused | ~100MB | ★★★ Discontinued |
| **Android SpeechRecognizer** | ⚠️ Partial | ✅ Native | Limited offline | Built-in | ★★ Needs Google |

### TTS (Text-to-Speech) Options

| Engine | Offline | Android | Languages | Size | Naturalness |
|--------|---------|---------|-----------|------|-------------|
| **sherpa-onnx TTS** | ✅ | ✅ | Many (VITS/Matcha) | ~50-100MB | ★★★★ Good |
| **Coqui TTS** | ✅ | ⚠️ Needs JNI | 100+ | ~100-200MB | ★★★★★ Best quality |
| **eSpeak-ng** | ✅ | ✅ | 100+ | ~5MB | ★★ Robotic |
| **Piper TTS** | ✅ | ✅ JNI | 30+ languages | ~15-50MB | ★★★★ Good |
| **Android TTS** | ⚠️ Partial | ✅ Native | Varies | Built-in | ★★★ OK |

### ✅ Recommendation: **sherpa-onnx (unified STT+TTS)**

**Rationale:**
- **sherpa-onnx** (from k2-fsa/next-gen Kaldi) provides **both STT and TTS in one unified framework** with ONNX Runtime backend.
- Supports Android natively with JNI bindings — no separate engine integration needed.
- STT: Supports Whisper, Paraformer, NeMo CTC models — can use Whisper-small for Swahili.
- TTS: Supports VITS, Matcha-TTS, Coqui models — can find Swahili TTS models.
- **Fully offline**, no internet required.
- Actively maintained, 2000+ GitHub stars, backed by Xiaomi (relevant — Xiaomi phones are very popular in Africa).
- Smaller memory footprint than running whisper.cpp + separate TTS engine.
- Supports Voice Activity Detection (VAD) built-in — important for battery life.

**Fallback architecture:**
```
User speaks → VAD detects speech → sherpa-onnx STT → text
  → On-device Qwen2.5 processes text → response text
  → sherpa-onnx TTS → audio output

When online: Use cloud STT/TTS for better quality (Google/Azure)
```

**Swahili language support:**
- Whisper models have reasonable Swahili support (trained on Common Voice + FLEURS)
- For other local languages (Kikuyu, Luo, etc.): may need fine-tuned models or fallback to cloud

---

## 5. Multi-Agent System Architecture

### Design Pattern: **Orchestrator + Specialized Agents**

```
┌─────────────────────────────────────┐
│         User Interface (Voice/Text) │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│     Orchestrator Agent (Router)     │
│  - Intent classification            │
│  - Language detection               │
│  - Agent selection                  │
│  - Context management               │
└──┬───────┬───────┬───────┬──────────┘
   │       │       │       │
┌──▼──┐ ┌──▼──┐ ┌──▼──┐ ┌──▼──┐
│ CFO │ │ TXN │ │ EDU │ │ GEN │
│Agent│ │Agent│ │Agent│ │Agent│
└─────┘ └─────┘ └─────┘ └─────┘
```

### Agent Implementations

| Agent | Role | On-Device Model | Cloud Fallback |
|-------|------|-----------------|----------------|
| **Orchestrator** | Intent routing, language detection | Qwen2.5-1.5B (classifier prompt) | GPT-4o-mini / Claude Haiku |
| **CFO Agent** | Financial advice, budgeting | Qwen2.5-1.5B + financial RAG | GPT-4o / Claude Sonnet |
| **Transaction Agent** | Log income/expenses, parse receipts | Qwen2.5-1.5B (structured output) | Same |
| **Education Agent** | Financial literacy, explanations | Qwen2.5-1.5B + curriculum knowledge | GPT-4o |
| **General Agent** | General queries, small talk | Qwen2.5-1.5B | Same |

### On-Device vs Cloud Routing Logic

```kotlin
enum class InferenceMode {
    DEVICE_ONLY,      // No internet, use local Qwen2.5
    DEVICE_FIRST,     // Try device, cloud if quality insufficient
    CLOUD_FIRST,      // Try cloud, fallback to device
    CLOUD_ONLY        // Complex tasks requiring large models
}

fun selectMode(task: Task, connectivity: Boolean): InferenceMode {
    return when {
        !connectivity -> DEVICE_ONLY
        task.complexity == HIGH -> CLOUD_FIRST
        task.type == TRANSACTION_LOG -> DEVICE_ONLY  // Speed critical
        task.type == FINANCIAL_ADVICE -> DEVICE_FIRST
        else -> DEVICE_ONLY  // Default to save bandwidth/cost
    }
}
```

### ✅ Recommendation: **Kotlin coroutine-based agent orchestration**

**Rationale:**
- No need for heavyweight frameworks (LangChain, CrewAI) on mobile — they're designed for Python servers.
- Use Kotlin coroutines + Flow for async agent communication.
- Each agent is a Kotlin class with a `process(input, context): Flow<AgentResponse>` interface.
- Orchestrator uses the on-device LLM's function-calling capability to route to correct agent.
- Keep agent prompts as embedded assets — small, versioned, updateable.

---

## 6. Backend Stack

### Options Evaluated

| Stack | Pros | Cons |
|-------|------|------|
| **Python FastAPI** | Best AI/ML ecosystem; easy LLM integration; great for prototyping | Slower than Go/Node for high concurrency; GIL limitations |
| **Node.js (Express/Fastify)** | Fast; great async; huge ecosystem; TypeScript | Less native ML support; callback complexity |
| **Go** | Excellent performance; great concurrency; low memory | Less AI/ML ecosystem; steeper learning curve |
| **Rust (Axum/Actix)** | Best performance; memory safety | Steep learning curve; smaller ecosystem |

### ✅ Recommendation: **Python FastAPI (API layer) + Go (high-throughput services)**

**Rationale:**
- **FastAPI** for the main API server:
  - Best ecosystem for LLM integration (OpenAI SDK, Anthropic SDK, LangChain)
  - Async support via asyncio/uvicorn — good enough for MVP
  - Easy to hire Python developers in East Africa
  - Pydantic for request validation — matches Kotlin data classes well
  - OpenAPI auto-docs for API contract

- **Go** for specific high-throughput services (future):
  - M-Pesa webhook handler (needs ultra-low latency)
  - Real-time notification service
  - Background job processing

- **Why not Node.js?** Python's AI ecosystem advantage is decisive. Node.js LLM libraries are wrappers around Python services anyway.

### API Architecture

```
Android App (Kotlin)
  ↓ HTTPS (REST + WebSocket)
API Gateway (nginx/Cloudflare)
  ↓
FastAPI Server (Python)
  ├── /auth/*        → Authentication
  ├── /transactions/* → Financial CRUD
  ├── /agents/*      → Cloud LLM proxy
  ├── /sync/*        → Data sync
  ├── /mpesa/*       → M-Pesa integration
  └── /admin/*       → Admin panel
  ↓
PostgreSQL + Redis
```

---

## 7. Database Strategy

### On-Device (SQLite)

| Component | Technology | Purpose |
|-----------|-----------|---------|
| **Main DB** | Room (SQLite) | Transactions, user data, settings |
| **Key-Value** | DataStore | Preferences, feature flags |
| **Cache** | SQLite (separate DB) | LLM conversation cache, model metadata |
| **Encrypted store** | EncryptedSharedPreferences / SQLCipher | Auth tokens, sensitive data |

### Backend Database

| Option | Pros | Cons |
|--------|------|------|
| **PostgreSQL** | Best for financial data; ACID; JSON support; great extensions | Self-managed |
| **Supabase** | PostgreSQL + Auth + Realtime + Storage; generous free tier | Vendor lock-in; limited customization |
| **Firebase** | Easy setup; realtime; good mobile SDK | NoSQL (bad for financial data); vendor lock-in; expensive at scale |
| **PlanetScale** | MySQL-compatible; branching | Financial data needs PostgreSQL |

### ✅ Recommendation: **PostgreSQL (self-hosted or Supabase)**

**Rationale:**
- **Financial data demands ACID compliance** — PostgreSQL is the gold standard.
- JSONB columns for flexible agent conversation storage.
- `pg_cron` for scheduled tasks (daily summaries, overdue reminders).
- Row Level Security (RLS) for multi-tenant data isolation.
- **Supabase** for MVP: PostgreSQL + Auth + Realtime subscriptions + Storage, all in one. Reduces backend boilerplate by 60%.
- Migrate to self-hosted PostgreSQL when scale demands it (Supabase is just managed PostgreSQL).

**Schema highlights:**
```sql
-- Core tables
users (id, phone, name, language, created_at)
transactions (id, user_id, type, amount, currency, category, description, date, source)
budgets (id, user_id, category, amount, period, start_date)
agent_conversations (id, user_id, agent_type, messages JSONB, created_at)
user_goals (id, user_id, goal_type, target_amount, current_amount, deadline)
achievements (id, user_id, achievement_type, earned_at, xp_earned)
```

### Data Sync Strategy

```
Device (SQLite) ←→ Sync Layer ←→ Backend (PostgreSQL)

Sync approach: Conflict-free Replicated Data Types (CRDT) or last-write-wins
- Transactions: Append-only (no conflicts)
- User profile: Last-write-wins with timestamps
- Agent conversations: Device-primary, sync to cloud for continuity
```

---

## 8. Security Considerations for Financial Data

### On-Device Security

| Layer | Implementation |
|-------|---------------|
| **Data at rest** | SQLCipher (256-bit AES) for SQLite database |
| **Key storage** | Android Keystore (hardware-backed on modern devices) |
| **Auth tokens** | EncryptedSharedPreferences |
| **Network** | Certificate pinning (OkHttp + pins) |
| **App integrity** | SafetyNet/Play Integrity API |
| **Root detection** | RootBeer library |
| **Screenshot prevention** | `FLAG_SECURE` on sensitive screens |
| **Biometric auth** | BiometricPrompt API (fingerprint/face) |

### Backend Security

| Layer | Implementation |
|-------|---------------|
| **Authentication** | JWT + refresh tokens; phone OTP for signup |
| **Authorization** | RBAC with row-level security |
| **API security** | Rate limiting; input validation (Pydantic); CORS |
| **Data encryption** | TLS 1.3 in transit; AES-256 at rest |
| **Audit logging** | All financial operations logged with timestamps |
| **Secrets management** | Environment variables + AWS Secrets Manager / Vault |
| **OWASP** | Follow OWASP Mobile Top 10 |

### Financial Data Compliance

- **Kenya Data Protection Act 2019** — GDPR-like; requires consent, data minimization
- **PCI DSS** — If handling card data (likely not needed for M-Pesa integration)
- **CBK Regulations** — If offering financial advice, may need licensing
- **Data residency** — Consider hosting in Africa (AWS Cape Town, Azure South Africa)

---

## 9. Competitor & Landscape Analysis

### Mobile Money (Direct Competitors for Transactions)

| Product | What They Do | Gap Msaidizi Fills |
|---------|-------------|-------------------|
| **M-Pesa** | Mobile money transfer, payments | No financial literacy, no AI, no budgeting |
| **M-Shwari** | Savings + loans via M-Pesa | No AI advice, limited education |
| **Airtel Money** | Mobile money (Uganda, Tanzania) | Same gaps as M-Pesa |

### Digital Lending

| Product | What They Do | Gap Msaidizi Fills |
|---------|-------------|-------------------|
| **Branch** | Microloans via app | No financial education; encourages debt |
| **Tala** | Credit scoring + loans | Loan-focused, not holistic financial health |
| **FairMoney** | Loans + savings (Nigeria) | Limited AI; no voice interface |
| **Carbon** | Digital bank (Nigeria) | Better, but still no AI assistant |

### AI-Powered Financial Apps (Emerging)

| Product | Market | What They Do | Relevance |
|---------|--------|-------------|-----------|
| **JUMO** | Africa | AI credit scoring for telcos | B2B, not consumer-facing |
| **Apollo Agriculture** | Kenya | AI for farmer financing | Agriculture-specific |
| **Pula** | Africa | AI crop insurance | Insurance-specific |
| **Chipper Cash** | Africa | Cross-border payments | Payments-focused, no AI assistant |

### Key Differentiators for Msaidizi

1. **Voice-first interface** — Most competitors are text/UI-only. Many informal workers are semi-literate. Voice in local languages is a massive differentiator.
2. **On-device AI** — Works without internet. Critical for areas with intermittent connectivity.
3. **Financial literacy** — Not just transactions, but education. Gamified learning.
4. **Holistic financial assistant** — Not just lending or payments, but budgeting, savings goals, financial advice.
5. **Multi-agent system** — Specialized agents for different needs, not a generic chatbot.

---

## 10. Gamification Strategy

### Engagement Mechanics

| Mechanic | Implementation | Purpose |
|----------|---------------|---------|
| **XP System** | Earn XP for logging transactions, completing lessons, saving | Progress tracking |
| **Levels** | Financial Literacy Levels (Beginner → Expert) | Clear progression |
| **Daily Streaks** | Track consecutive days of logging expenses | Habit formation |
| **Achievements/Badges** | "First Budget", "7-Day Streak", "Savings Champion" | Milestone rewards |
| **Challenges** | "Save 10% this week", "Log all expenses for 5 days" | Active engagement |
| **Leaderboard** | Anonymous opt-in comparison with peers | Social motivation |
| **Financial Health Score** | Composite score based on savings, spending, literacy | Holistic metric |

### Implementation Notes

- Store gamification data locally (SQLite) for offline access
- Sync achievements to backend when online
- Use XP as currency for unlocking features (not monetization)
- Keep it positive — never punish, always encourage

---

## 11. Multi-Language Support

### Priority Languages

| Tier | Languages | Population | Strategy |
|------|-----------|------------|----------|
| **Tier 1** | English, Swahili | 150M+ speakers | Full support at launch |
| **Tier 2** | Kinyarwanda, Luganda, Amharic | 50M+ speakers | Phase 2 |
| **Tier 3** | Yoruba, Hausa, Zulu, Wolof | 100M+ speakers | Phase 3 |

### Technical Implementation

- **Android:** Use `strings.xml` resource qualifiers (`values-sw/` for Swahili)
- **LLM prompts:** Include language instruction in system prompt; Qwen2.5 handles 29+ languages natively
- **STT:** Use language-specific Whisper models or multilingual models
- **TTS:** Use language-specific VITS models via sherpa-onnx
- **UI:** All user-facing strings externalized; RTL support for Amharic/Arabic if needed

---

## 12. Recommended Tech Stack Summary

### Frontend (Mobile)

| Component | Technology | Version |
|-----------|-----------|---------|
| Language | Kotlin | 2.0+ |
| UI | Jetpack Compose | Latest |
| Architecture | MVVM + Clean Architecture | — |
| DI | Hilt (Dagger) | Latest |
| Networking | Retrofit + OkHttp | Latest |
| Local DB | Room (SQLite) | Latest |
| On-device LLM | llama.cpp (JNI) | Latest |
| Voice Pipeline | sherpa-onnx | Latest |
| Async | Kotlin Coroutines + Flow | Latest |
| Navigation | Compose Navigation | Latest |

### Backend

| Component | Technology | Notes |
|-----------|-----------|-------|
| API Framework | FastAPI (Python 3.12+) | Async, OpenAPI docs |
| Database | PostgreSQL (via Supabase) | ACID, JSONB, RLS |
| Cache | Redis | Session, rate limiting |
| Auth | Supabase Auth / custom JWT | Phone OTP |
| Cloud LLM | OpenAI / Anthropic API | GPT-4o-mini, Claude Haiku |
| File Storage | Supabase Storage / S3 | Model files, receipts |
| Deployment | Docker + Railway/Fly.io | Or AWS if scaling |
| Monitoring | Sentry + Prometheus | Error tracking, metrics |

### AI/ML

| Component | Technology | Notes |
|-----------|-----------|-------|
| On-device LLM | Qwen2.5-1.5B-Instruct (GGUF Q4_K_M) | Via llama.cpp |
| Cloud LLM | GPT-4o-mini / Claude Haiku | Fallback when online |
| STT | sherpa-onnx (Whisper-small) | Offline capable |
| TTS | sherpa-onnx (VITS) | Offline capable |
| Embeddings | all-MiniLM-L6-v2 (ONNX) | For RAG on device |
| Agent Framework | Custom Kotlin (coroutines) | No heavy frameworks |

### Infrastructure

| Component | Technology | Notes |
|-----------|-----------|-------|
| Hosting | AWS (Cape Town) / Supabase | Africa-region for latency |
| CI/CD | GitHub Actions | Automated builds |
| Crash Reporting | Firebase Crashlytics | Free, good Android support |
| Analytics | PostHog (self-hosted) or Mixpanel | Privacy-friendly |
| Push Notifications | Firebase Cloud Messaging | Free |

---

## 13. Architecture Diagram

```
┌──────────────────────────────────────────────────────────┐
│                    ANDROID DEVICE                         │
│                                                          │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐     │
│  │  Jetpack    │  │   Voice     │  │  On-device  │     │
│  │  Compose UI │  │   Pipeline  │  │  LLM Engine │     │
│  │             │  │ (sherpa-    │  │ (llama.cpp +│     │
│  │  Gamificatn │  │  onnx)      │  │  Qwen2.5)   │     │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘     │
│         │                │                │              │
│  ┌──────▼────────────────▼────────────────▼──────┐      │
│  │         Agent Orchestrator (Kotlin)           │      │
│  │  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐            │      │
│  │  │ CFO │ │ TXN │ │ EDU │ │ GEN │            │      │
│  │  └─────┘ └─────┘ └─────┘ └─────┘            │      │
│  └──────────────────┬───────────────────────────┘      │
│                     │                                    │
│  ┌──────────────────▼───────────────────────────┐      │
│  │         Data Layer (Room SQLite)             │      │
│  │  Transactions │ Goals │ Achievements │ Cache  │      │
│  └──────────────────┬───────────────────────────┘      │
└─────────────────────┼──────────────────────────────────┘
                      │ HTTPS (offline-first sync)
                      ▼
┌──────────────────────────────────────────────────────────┐
│                    BACKEND (Cloud)                        │
│                                                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐              │
│  │ FastAPI  │  │ Supabase │  │ Cloud    │              │
│  │ API      │  │ Auth +   │  │ LLM      │              │
│  │ Server   │  │ Postgres │  │ Proxy    │              │
│  └──────────┘  └──────────┘  └──────────┘              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐              │
│  │ M-Pesa   │  │ Redis    │  │ S3/CDN   │              │
│  │ Gateway  │  │ Cache    │  │ Storage  │              │
│  └──────────┘  └──────────┘  └──────────┘              │
└──────────────────────────────────────────────────────────┘
```

---

## 14. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Budget phones can't run 1.5B model | High | Q3_K_M fallback (0.5B Qwen if needed); cloud-first when online |
| Swahili STT/TTS quality insufficient | High | Use Whisper multilingual; fine-tune on Swahili data; crowdsource voice data |
| M-Pesa API access difficult | High | Use Safaricom Daraja API; consider M-Pesa STK Push for payments |
| Low connectivity areas | Medium | Offline-first architecture; sync when connected; SMS fallback for critical ops |
| User adoption/trust | Medium | Voice interface builds trust; local language; community ambassadors |
| Regulatory (financial advice) | Medium | Frame as "financial education" not "financial advice"; legal review |
| Model hallucination on financial data | High | Guardrails: structured output validation; human-in-the-loop for large transactions |

---

## 15. Development Roadmap (Suggested)

| Phase | Duration | Focus |
|-------|----------|-------|
| **Phase 1: Core** | 3 months | Android app skeleton, SQLite, basic transaction logging, UI |
| **Phase 2: Voice** | 2 months | sherpa-onnx integration, Swahili STT/TTS, voice commands |
| **Phase 3: AI** | 2 months | On-device Qwen2.5, agent orchestrator, offline reasoning |
| **Phase 4: Cloud** | 2 months | FastAPI backend, Supabase, M-Pesa integration, cloud LLM |
| **Phase 5: Polish** | 1 month | Gamification, multi-language, performance optimization |
| **Phase 6: Beta** | 2 months | User testing in Kenya/Tanzania, iterate based on feedback |

---

## Key Takeaways

1. **Kotlin Native is the right call** — Android dominates Africa, and on-device AI needs native performance.
2. **Qwen2.5-1.5B is the model to beat** — Best multilingual support in its size class, official GGUF quantizations.
3. **llama.cpp + sherpa-onnx** covers your entire on-device AI stack (LLM + voice) with two well-maintained libraries.
4. **Offline-first architecture is non-negotiable** — Design everything to work without internet, sync when available.
5. **Voice-first UX is the differentiator** — Semi-literate users need voice interfaces in local languages.
6. **PostgreSQL/Supabase for backend** — Financial data demands ACID compliance; don't use Firebase/NoSQL.
7. **Supabase gives you the fastest path to MVP** — Auth + DB + Realtime + Storage in one service.

---

*Research conducted July 2025. Technology landscape evolves rapidly — revisit key decisions quarterly.*
