# Msaidizi App — Council Architecture Review

**Date:** 2026-07-24  
**Documents Reviewed:** 7 (1 main architecture + 6 gap designs)  
**Reviewers:** 5 specialized panels  
**Status:** Final Council Verdict

---

## Documents Under Review

| # | Document | Scope | Lines |
|---|----------|-------|-------|
| 1 | `architecture-msaidizi-superagent.md` | Main architecture — Superagent, module structure, M-KOPA model | ~2,500 |
| 2 | `gap-ooda-loop.md` | OODA reasoning loop — Observe→Orient→Decide→Act | ~1,800 |
| 3 | `gap-skill-crystallization.md` | Skill system — pattern detection, auto-creation, lifecycle | ~800 |
| 4 | `gap-proactive-alerts.md` | Alert system — 7 trigger types, anti-fatigue, timing | ~1,200 |
| 5 | `gap-alama-score.md` | Credit scoring — 8 pillars, offline-first, consent | ~1,500 |
| 6 | `gap-federated-learning.md` | Federated learning — personal LoRA, DP, PQC | ~1,200 |
| 7 | `gap-dialect-scaling.md` | Dialect architecture — 100+ dialects, adapter stack | ~1,500 |

---

## 1. Security Reviewer

### Approval Status: **APPROVED WITH CONDITIONS**

### Key Findings

1. **PQC Implementation Is Ambitious but Appropriate.** The architecture specifies ML-KEM-768 (NIST FIPS 203) for federated gradient transport and ML-DSA for document signing. This is forward-looking and correct for a financial application targeting 2026+ deployment. However, the documents specify *what* to use but not *how* to integrate. No mention of which PQC library (liboqs? PQClean? BoringSSL PQC?) or JNI bridge strategy for Android.

2. **Consent Architecture Is Well-Designed.** The Alama Score consent model (explicit grant, granular scope, revocable, expiring, auditable) is textbook privacy engineering. The immutable consent ledger with cryptographic signatures is strong. The distinction between "credit scoring" (regulated) and "financial readiness" (educational) is legally smart.

3. **SIM Swap Detection Gap.** The architecture mentions `DeviceBinder` and `SuspiciousLoginDetector` in the security module but provides zero design detail. For an app handling financial data in Kenya (where SIM swap fraud is endemic), this is a critical omission. M-Pesa integration without SIM swap detection is a liability.

4. **Input Sanitization Is Mentioned, Not Designed.** `InputSanitizer` appears in the module structure but no gap document covers it. Voice input is inherently harder to sanitize than text — adversarial audio attacks, prompt injection via voice commands, and transcript poisoning are all vectors.

5. **Federated Learning Privacy Stack Is Strong.** The 7-layer privacy guarantee (data minimization → DP → k-anonymity → secure aggregation → PQC → local DP → opt-out) is comprehensive. The ε=0.1 differential privacy budget is very conservative (good). k=5 anonymity threshold is reasonable for diverse dialect groups.

### Risks

| Risk | Severity | Likelihood |
|------|----------|------------|
| SIM swap attack on M-Pesa-linked accounts | **CRITICAL** | HIGH (Kenya context) |
| Adversarial voice input (audio injection) | HIGH | MEDIUM |
| PQC library immaturity on Android/ARM | MEDIUM | MEDIUM |
| Consent ledger key compromise (device theft) | HIGH | LOW |
| Gradient inference attack despite DP noise | MEDIUM | LOW |

### Recommendations

1. **MUST FIX:** Design SIM swap detection in detail before M-Pesa integration ships. Include carrier API integration (Safaricom), device fingerprint binding, and behavioral anomaly detection.
2. **MUST FIX:** Add voice input adversarial robustness — audio liveness detection, transcript confidence thresholds, and command injection filtering.
3. **SHOULD FIX:** Specify PQC library choice and benchmark ML-KEM-768 on target devices (2GB RAM Tecno/Infinix). If too slow, fall back to hybrid RSA+ML-KEM.
4. **SHOULD FIX:** Add biometric binding to consent ledger signing (fingerprint required to grant/revoke sharing).

---

## 2. Performance Reviewer

### Approval Status: **APPROVED WITH CONDITIONS**

### Key Findings

1. **Memory Budget Is Tight But Accounted For.** The federated learning document explicitly budgets 2048MB RAM: 512MB OS + 250MB base model + 10MB LoRA + 20MB optimizer + 50MB activations + 50MB training buffer + 200MB app = 1092MB, leaving 956MB headroom. This is honest and plausible for Tecno Spark / Infinix Smart series devices.

2. **Voice Pipeline Latency Is Well-Budgeted.** The OODA loop targets 50-200ms for code path and 500-2000ms for LLM path. This is achievable. However, the **end-to-end voice latency** (STT → OODA → TTS) is never explicitly budgeted. If STT takes 500ms, OODA takes 200ms, and TTS takes 300ms, that's 1000ms total — acceptable but needs explicit validation.

3. **Cold Start Concern.** The architecture specifies lazy module initialization and sequential model loading, but the first-launch experience expects voice interaction within 60 seconds. On a 2GB device, loading a 250MB base STT model + 30MB TTS model + initializing Room DB + Hilt DI graph will likely exceed 60 seconds on first launch. The bootstrap flow should account for this.

4. **Model Download Strategy Is Smart.** Background model download while worker is already using cloud mode is correct. The 50MB STT + 30MB TTS + 500MB LLM download sizes are reasonable for Kenyan data plans, especially with the data saver awareness.

5. **No Profiling or Benchmarking Plan.** The architecture has no mention of on-device profiling, memory leak detection, or frame rate monitoring. For a 14-module Android app targeting low-end devices, this is a gap.

### Risks

| Risk | Severity | Likelihood |
|------|----------|------------|
| OOM crash on 2GB device with all models loaded | **CRITICAL** | HIGH |
| Cold start >60s breaks first-launch onboarding | HIGH | HIGH |
| Voice pipeline end-to-end latency >2s feels broken | HIGH | MEDIUM |
| Memory leaks from lazy-loaded modules not freed | MEDIUM | MEDIUM |
| Battery drain from background federated learning | MEDIUM | MEDIUM |

### Recommendations

1. **MUST FIX:** Add explicit end-to-end voice pipeline latency budget: STT + OODA + TTS < 1.5s target, < 3s max. Profile on actual Tecno/Infinix hardware.
2. **MUST FIX:** Redesign first-launch to work in cloud-only mode for the first interaction. Don't load any models. Use cloud STT/TTS for the "record your first transaction" moment. Load models in background afterward.
3. **MUST FIX:** Add memory pressure monitoring. When available RAM < 200MB, aggressively unload: (a) LLM model, (b) global LoRA, (c) non-essential modules. Keep only STT + TTS + core engine.
4. **SHOULD FIX:** Add `StrictMode` and memory profiling to debug builds. Track P50/P95/P99 latency for each OODA phase in production telemetry.
5. **SHOULD FIX:** Add battery budget for federated learning. Cap at 5% battery drain per training session. Abort if device is below 30% battery.

---

## 3. Voice/AI Reviewer

### Approval Status: **APPROVED WITH CONDITIONS**

### Key Findings

1. **OODA Loop Is the Right Architecture.** The Observe→Orient→Decide→Act pattern with per-phase fallback is excellent for a voice assistant that must work offline. The 90% code-first / 10% LLM split is practical. The correction loop (re-enter OBSERVE on worker correction) is critical for voice UX and is well-designed.

2. **Dialect Scaling Strategy Is Realistic.** The layered adapter stack (base → language → dialect → personal LoRA) is the correct approach. The transfer learning matrix (Kikuyu from Swahili needs 2-4 hours vs. 15-20 from scratch) shows real understanding of the data scarcity problem. The 5-phase rollout from 15→100+ dialects is ambitious but structured.

3. **STT WER Gap Is Acknowledged But Not Solved.** The documents acknowledge 20-50% WER on African dialects vs. 3-8% for European languages. The personal LoRA approach (learn from 30+ minutes of user speech) is correct, but there's no design for what happens during those first 30 minutes when WER is 30-50%. The worker will have a terrible experience and churn.

4. **Code-Switching Detection Is Designed But Unproven.** The multi-dialect encoder + boundary detector + segment decoder architecture is academically sound but has no benchmark results. Real Nairobi Sheng mixes 3-4 languages per sentence. If this doesn't work, the entire voice pipeline degrades.

5. **Skill Crystallization Is Clever But Untested.** The pattern detection → proposal → confirmation → activation flow is well-designed. The anti-pattern rules (5+ occurrences for sensitive actions) are smart. But the "fingerprint = hash(normalized_intent + context_slots)" approach may produce too many false positives in early days when the worker's patterns are still forming.

### Risks

| Risk | Severity | Likelihood |
|------|----------|------------|
| 30-50% WER during onboarding causes immediate churn | **CRITICAL** | HIGH |
| Code-switching detection fails on real Sheng speech | HIGH | MEDIUM |
| Skill crystallization false positives annoy workers | MEDIUM | MEDIUM |
| LLM fallback (Qwen 0.8B) quality too low for ambiguous inputs | MEDIUM | MEDIUM |
| TTS quality (Kokoro/Piper) unintelligible in noisy markets | HIGH | MEDIUM |

### Recommendations

1. **MUST FIX:** Design a "degraded mode" onboarding that uses cloud STT for the first session. Show the worker how good the experience *can be* before asking them to tolerate local model quality during personalization.
2. **MUST FIX:** Test TTS intelligibility in actual Kenyan market noise (70-80dB ambient). If Kokoro/Piper can't cut through noise, design a "quiet mode" that uses shorter responses + vibration alerts.
3. **MUST FIX:** Add a WER monitoring loop. If WER > 25% after personalization, trigger a re-enrollment flow rather than silently degrading the experience.
4. **SHOULD FIX:** Add explicit handling for the "I don't understand" case in the OODA loop. When all confidence levels are low, offer a simple menu ("Did you mean: Sale? Purchase? Expense?") rather than asking the worker to repeat.
5. **SHOULD FIX:** Validate code-switching detection with real Nairobi Sheng recordings before committing to the architecture. If it doesn't work, fall back to a single "Sheng-aware" adapter that handles mixing internally.

---

## 4. Product/UX Reviewer

### Approval Status: **APPROVED**

### Key Findings

1. **M-KOPA Onboarding Model Is Brilliant.** The "transaction first, profile second" approach is the single best design decision in the entire architecture. Recording one transaction in 60 seconds before asking for *any* profile data mirrors M-KOPA's "get the phone first" philosophy perfectly. This will dramatically reduce first-session drop-off.

2. **Proactive Alert Anti-Fatigue Is Well-Designed.** The silence budget (max 3 P1 + 5 P2 + 1 briefing per day), quiet hours (22:00-05:30 no alerts), and adaptive thresholds based on worker feedback are all correct. The P0/P1/P2/Silent priority system with timing windows respects the worker's rhythm.

3. **Voice-First Is Right But Needs Fallback.** The entire architecture assumes voice as primary input. This is correct for the target demographic (informal workers, many with limited literacy). But there's no mention of a text fallback for deaf/hard-of-hearing workers, or for situations where voice is inappropriate (quiet environments, privacy concerns).

4. **Gamification Is Appropriate.** Streaks, badges, and micro-rewards are correctly designed as passive (triggered by other modules, not standalone). The streak protection system and variable rewards prevent the "points fatigue" that kills most gamification systems.

5. **Alama Score Transparency Is Excellent.** The "every score has an explanation" principle with actionable improvement tips is exactly right for building trust. The tier system (🌱 Starting → 🏆 Excellent) with clear unlock criteria gives workers a visible path forward.

### Risks

| Risk | Severity | Likelihood |
|------|----------|------------|
| Worker doesn't understand why they need Msaidizi (value proposition unclear) | HIGH | MEDIUM |
| "Transaction first" onboarding confuses workers who expect a tutorial | MEDIUM | MEDIUM |
| Proactive alerts feel like surveillance, not help | MEDIUM | LOW |
| Alama Score creates anxiety for low-scoring workers | MEDIUM | MEDIUM |
| No accessibility for deaf/hard-of-hearing workers | MEDIUM | LOW |

### Recommendations

1. **MUST FIX:** Add a 5-second value proposition before the first transaction prompt. "Msaidizi tracks your business so you can see your real profits. Let's start — tell me about a sale you made today." The worker needs to know *why* before *what*.
2. **SHOULD FIX:** Add a text input option alongside voice. Some workers will prefer typing, especially for sensitive financial data. Don't force voice-only.
3. **SHOULD FIX:** Design the Alama Score "Building" tier messaging carefully. Avoid creating anxiety. Frame it as "You're just getting started — every transaction builds your score" rather than "Your score is too low."
4. **SHOULD FIX:** Add a "Why am I getting this alert?" explainer for every proactive alert. Transparency prevents the surveillance feeling.
5. **NICE TO HAVE:** Add community features (anonymized peer comparison) to the Alama Score. "Workers like you typically have a score of X after 30 days" creates social proof without exposing individual data.

---

## 5. Code Quality Reviewer

### Approval Status: **APPROVED WITH CONDITIONS**

### Key Findings

1. **Module Structure Is Clean.** The 14-module split (app + 5 core + 7 superagent + data + onboarding) is well-organized. Each module has a clear responsibility. The dependency graph is acyclic (core → superagent → app). This is maintainable.

2. **Migration Strategy Is Underdeveloped.** The architecture has a detailed file migration map (what moves where) but no migration *strategy*. How do you migrate 400 files across 14 modules without breaking the build? No mention of feature flags, parallel paths, or incremental migration. The "delete ~12K lines" approach risks a big-bang migration that fails.

3. **Testing Strategy Exists But Is Thin.** The OODA loop document includes unit test examples per phase and integration tests. But there's no mention of: (a) test coverage targets, (b) CI/CD pipeline, (c) instrumentation tests for Android, (d) voice pipeline integration tests, (e) database migration tests.

4. **Dependency Management Not Addressed.** 14 modules means 14 `build.gradle.kts` files with potentially conflicting dependency versions. No mention of version catalogs (`libs.versions.toml`), dependency verification, or BOM (Bill of Materials) for consistent versions.

5. **Technical Debt Acknowledged.** The architecture explicitly identifies ~12K lines to delete (A2A protocol, AgentEventBus, MoE router, agent wrappers). This is honest and commendable. But there's no plan for the *other* technical debt — the existing 127K lines that are being reorganized, not rewritten.

### Risks

| Risk | Severity | Likelihood |
|------|----------|------------|
| Big-bang migration breaks build for weeks | **CRITICAL** | HIGH |
| 14 modules create Gradle build time explosion (>5 min) | HIGH | MEDIUM |
| No CI/CD means regressions ship to production | HIGH | MEDIUM |
| Database migration between versions fails silently | HIGH | MEDIUM |
| DI (Hilt) graph becomes unmanageable with 14 modules | MEDIUM | MEDIUM |

### Recommendations

1. **MUST FIX:** Design an incremental migration strategy. Migrate one module at a time, keeping the old code working alongside. Use feature flags to switch between old and new paths. Never have a broken build.
2. **MUST FIX:** Set up CI/CD with build + test on every PR before starting migration. Include: unit tests, Android instrumented tests, lint checks, and dependency vulnerability scanning.
3. **MUST FIX:** Use Gradle version catalogs (`libs.versions.toml`) for dependency management from day one. Define a BOM for consistent versions across all 14 modules.
4. **SHOULD FIX:** Add build time optimization. Use Gradle build cache, parallel module compilation, and configuration cache. Target < 3 min for full build.
5. **SHOULD FIX:** Design Room database migration strategy. Every schema change needs a migration path. Test migrations with `MigrationTestHelper`. Never use `fallbackToDestructiveMigration()` in production.
6. **SHOULD FIX:** Set test coverage targets: 80% for core modules, 70% for superagent modules, 60% for app module. Enforce in CI.

---

## Council Verdict

### **APPROVED WITH CONDITIONS**

### Go/No-Go for Engineering: **YES — with 7 mandatory conditions**

The architecture is sound. The Superagent + OODA + M-KOPA proof model is the right design for this problem. The gap designs show deep understanding of the domain (informal economies, African linguistics, privacy engineering, low-end devices). This is not a committee-designed monolith — it's a focused, opinionated architecture that makes hard tradeoffs correctly.

However, the architecture is a *design*, not an *implementation plan*. Several critical areas need detail before engineering begins.

### Mandatory Conditions (Must Fix Before Engineering Starts)

| # | Condition | Owner | Document |
|---|-----------|-------|----------|
| 1 | **SIM swap detection design** — Detail the DeviceBinder, carrier API integration, and behavioral anomaly detection for M-Pesa security | Security | `gap-alama-score.md` |
| 2 | **End-to-end voice latency budget** — STT + OODA + TTS < 1.5s target. Profile on actual Tecno/Infinix hardware. Include cold start | Performance | `architecture-msaidizi-superagent.md` |
| 3 | **First-launch cloud fallback** — First "record your transaction" moment must work via cloud STT/TTS, not local models. Local models load in background | Performance / UX | `architecture-msaidizi-superagent.md` |
| 4 | **Incremental migration strategy** — One module at a time, feature flags, never broken build. No big-bang migration | Code Quality | `architecture-msaidizi-superagent.md` |
| 5 | **CI/CD pipeline** — Build + test + lint on every PR. Set up before writing any migration code | Code Quality | N/A (new) |
| 6 | **WER degradation handling** — Design the "what happens when STT is terrible" path. Cloud fallback, re-enrollment, menu-based input | Voice/AI | `gap-dialect-scaling.md` |
| 7 | **Voice input adversarial robustness** — Audio liveness, transcript confidence thresholds, command injection filtering | Security | `architecture-msaidizi-superagent.md` |

### Advisory Conditions (Fix During Engineering)

| # | Condition | Priority |
|---|-----------|----------|
| 8 | PQC library selection and on-device benchmarking | HIGH |
| 9 | Memory pressure monitoring and model eviction strategy | HIGH |
| 10 | TTS intelligibility testing in market noise | HIGH |
| 11 | Database migration testing strategy | HIGH |
| 12 | Test coverage targets and enforcement | MEDIUM |
| 13 | Gradle build time optimization | MEDIUM |
| 14 | Text input fallback for accessibility | MEDIUM |
| 15 | Battery budget for federated learning | MEDIUM |
| 16 | Alama Score "Building" tier messaging | LOW |
| 17 | Code-switching validation with real recordings | HIGH |

### Strengths Worth Preserving

- **M-KOPA proof model** — The "transaction first, profile second" onboarding is the killer feature
- **OODA loop** — Per-phase fallback with graceful degradation is the right reasoning architecture
- **Privacy stack** — 7-layer federated learning privacy (DP + k-anon + PQC) is industry-leading
- **Anti-fatigue alerts** — Silence budget + adaptive thresholds + quiet hours will prevent alert exhaustion
- **Dialect adapter stack** — Layered adapters with transfer learning is the correct scaling strategy
- **Alama Score transparency** — "Every score has an explanation" builds trust
- **Honest deletion** — Identifying 12K lines of architectural cosplay to delete shows engineering maturity

### Summary

This architecture is ready for engineering. The design is strong, the tradeoffs are correct, and the team clearly understands the problem domain. Fix the 7 mandatory conditions, set up CI/CD, and start migrating one module at a time. Ship the M-KOPA onboarding flow first — it's the feature that will prove the thesis.

---

*Council Review completed 2026-07-24*  
*5 reviewers · 7 documents · 1 verdict*
