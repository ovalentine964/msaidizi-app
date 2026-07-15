# Decision Council Report — Msaidizi + Angavu Intelligence

**Date:** 2026-07-16  
**Council:** Automated Senior Engineering Review  
**Sources:** Feature Test Report, Integration Test Report, Backend Test Report  
**Scope:** All issues across mobile app, backend, and integration layer

---

## Executive Summary

The **Msaidizi mobile app** is feature-complete with all 6 subsystems passing verification (voice, agent, CFO, gamification, finance, security). The **Angavu backend** is mature with 45+ agents, 293 endpoints, real PQC, and privacy-preserving FL.

**The primary risk is in the integration layer.** 5 of 7 integration points between the app and backend have alignment issues that would cause runtime failures. The feature test found zero issues; the backend test found minor operational concerns. All critical blockers are in the app↔backend contract.

| Category | Critical | High | Medium | Low | Total |
|----------|----------|------|--------|-----|-------|
| Integration Issues | 3 | 2 | 4 | 1 | 10 |
| Backend Operational | 0 | 1 | 2 | 2 | 5 |
| Feature Issues | 0 | 0 | 0 | 0 | 0 |
| **Total** | **3** | **3** | **6** | **3** | **15** |

---

## Prioritized Issue List

### 🔴 CRITICAL — Fix Before Any Release

#### C1: OTP Authentication Field Mismatch
- **Source:** Integration Test Report
- **Severity:** Critical
- **Impact:** Login completely broken. App sends `otp` field, backend expects `code`. Backend also requires `device_id` which app doesn't send. Every user gets a 422 validation error on OTP verify. **Zero users can authenticate.**
- **Effort:** 1–2 hours
- **Priority:** **FIX NOW**
- **Fix:** In `OtpVerifyRequest.kt`: rename field `otp` → `code`, add `device_id` field. Also add `user` object to backend auth response (app expects it, backend returns only `user_id`).

#### C2: General Sync Schema Mismatch
- **Source:** Integration Test Report
- **Severity:** Critical
- **Impact:** Transaction sync completely broken. Field names differ (`type` vs `transaction_type`, `category` vs `item_category`, `total_amount` vs `amount`), timestamp type mismatch (`Long` vs `datetime`), and incompatible conflict resolution models (app sends `vector_clock`, backend ignores it and uses composite-key dedup). **All offline-recorded transactions fail to sync.**
- **Effort:** 4–8 hours
- **Priority:** **FIX NOW**
- **Fix:** Add a mapping/adapter layer in the app's sync service that transforms `CreateTransactionRequest` → `TransactionRecord` field names and types. Align timestamp format (both use Unix seconds or ISO 8601). Decide on single conflict resolution strategy.

#### C3: Federated Learning Encryption Scheme Mismatch
- **Source:** Integration Test Report
- **Severity:** Critical
- **Impact:** FL gradient uploads fail silently or error out. App uses Android Keystore `CryptoUtils.encrypt()` (AES-256), backend expects ML-KEM-768 PQC encryption. **Federated learning is non-functional — no model improvements propagate to devices.**
- **Effort:** 4–6 hours
- **Priority:** **FIX NOW**
- **Fix:** App's `FederatedLearningClient` must implement ML-KEM-768 encapsulation using the backend's public key (`GET /fl/pqc-public-key`). The app already has `MlKemProvider` via Bouncy Castle — wire it into the FL upload path.

---

### 🟠 HIGH — Fix Before Beta

#### H1: Federated Learning Endpoint Path Mismatch
- **Source:** Integration Test Report
- **Severity:** High
- **Impact:** FL upload and download return 404. App calls `/api/v1/federated/upload` and `/api/v1/federated/models/{language}`, backend registers `/fl/upload-update` and `/fl/global-model/{dialect}`. **Same encryption issue as C3 but at the routing level — even with fixed encryption, requests don't reach the handler.**
- **Effort:** 1–2 hours
- **Priority:** **FIX NOW** (pair with C3)
- **Fix:** Add route aliases in backend (`/federated/upload` → `/fl/upload-update`, `/federated/models/{lang}` → `/fl/global-model/{lang}`) OR update app paths. Also align `{language}` vs `{dialect}` parameter naming.

#### H2: Model Distribution Gap
- **Source:** Integration Test Report
- **Severity:** High
- **Impact:** No mechanism for app to discover or download backend-aggregated FL models. App downloads from HuggingFace/GitHub CDN independently. Backend produces aggregated models via FL but has no serving endpoint. **FL aggregation work is wasted — improved models never reach devices.**
- **Effort:** 3–4 hours
- **Priority:** **FIX LATER** (before beta)
- **Fix:** Add `GET /models/{model_id}/download` endpoint to backend, or explicitly document CDN-only distribution as intentional architecture. Add `GET /models/latest?device_tier=basic` for version checking.

#### H3: Mutable FL Singleton State
- **Source:** Backend Test Report
- **Severity:** High
- **Impact:** `_FLState` is a mutable singleton. In multi-worker (uvicorn workers > 1) or restart scenarios, FL state is lost or inconsistent. **FL aggregation state, contribution tracking, and model versions may be corrupted or lost.**
- **Effort:** 4–6 hours
- **Priority:** **FIX LATER** (before beta)
- **Fix:** Migrate FL state to Redis or PostgreSQL. The `FLPersistence` module already exists for SQLite — extend it to be the primary state store rather than a fallback.

---

### 🟡 MEDIUM — Fix Before GA

#### M1: Intelligence Response Format Mismatch
- **Source:** Integration Test Report
- **Severity:** Medium
- **Impact:** App expects flat `Insight[]` with `type`, `title`, `body`, `priority`, `action_url`. Backend returns structured `IntelligenceUpdateResponse` with `SokoPulseData`, `AlamaScoreData`, etc. **Intelligence insights display incorrectly or are empty in the app UI.**
- **Effort:** 2–3 hours
- **Priority:** **FIX LATER** (before GA)
- **Fix:** Add a response mapper/adapter that flattens backend intelligence products into the app's `Insight` format, OR add a `/ai/insights` endpoint to backend that returns flat format.

#### M2: Auth Response User Object Missing
- **Source:** Integration Test Report
- **Severity:** Medium
- **Impact:** App expects full `user` object in auth response, backend returns only `user_id`. App may crash or show empty user profile after login. **Partially blocked by C1 (fix together).**
- **Effort:** 1 hour
- **Priority:** **FIX NOW** (bundle with C1)
- **Fix:** Add `user` field (id, phone, name, created_at) to backend's `OTPVerifyResponse`.

#### M3: FL Adapter Deltas Encoding Mismatch
- **Source:** Integration Test Report
- **Severity:** Medium
- **Impact:** App sends `adapterDeltas` as raw `ByteArray`, backend expects base64-encoded string. FL uploads fail deserialization. **Blocked by C3 (fix encryption first).**
- **Effort:** 30 minutes
- **Priority:** **FIX NOW** (bundle with C3)
- **Fix:** Base64-encode `adapterDeltas` before upload in `FederatedLearningClient.kt`.

#### M4: Missing FL Unit Tests
- **Source:** Backend Test Report
- **Severity:** Medium
- **Impact:** No dedicated test file for `FederatedLearningService` (1,064 lines). Only indirect coverage via `test_evolution_fl_fixes.py`. Regressions in aggregation logic, privacy noise, or quality validation could ship undetected.
- **Effort:** 4–6 hours
- **Priority:** **FIX LATER** (before GA)
- **Fix:** Create `tests/test_federated_learning.py` with unit tests for FedAvg aggregation, differential privacy noise, quality validation, and rollback logic.

#### M5: HybridKeyExchange Testing Placeholder
- **Source:** Backend Test Report
- **Severity:** Medium
- **Impact:** `HybridKeyExchange.complete()` uses X25519 placeholder for testing. If accidentally used in production, hybrid KEX degrades to classical-only. **Documented with warnings but a potential footgun.**
- **Effort:** 1–2 hours
- **Priority:** **FIX LATER** (before GA)
- **Fix:** Add runtime guard that raises if `complete()` is called in non-test environment. Ensure `complete_as_server()` is always used in production paths.

#### M6: Sync Conflict Resolution Incompatibility
- **Source:** Integration Test Report
- **Severity:** Medium
- **Impact:** App uses vector-clock CRDT merge strategies, backend uses composite-key deduplication. Different conflict resolution models mean concurrent edits from multiple devices may produce inconsistent results.
- **Effort:** 6–8 hours
- **Priority:** **FIX LATER** (before GA)
- **Fix:** Choose one strategy. Recommended: keep backend's simpler dedup for v1, have app send standard fields without vector_clock, add CRDT support in backend for v2.

---

### 🟢 LOW — Accept Risk or Fix When Convenient

#### L1: FL Model Path Parameter Naming
- **Source:** Integration Test Report
- **Severity:** Low
- **Impact:** App uses `{language}` in URL path, backend uses `{dialect}`. Cosmetic but could cause confusion in docs and debugging.
- **Effort:** 15 minutes
- **Priority:** **FIX NOW** (bundle with H1)
- **Fix:** Standardize on `{dialect}` across both codebases.

#### L2: Empty Performance Test Directory
- **Source:** Backend Test Report
- **Severity:** Low
- **Impact:** No load/stress tests exist. 293 endpoints and 45+ agents have no performance baseline. Could ship with unexpected latency regressions under load.
- **Effort:** 8–12 hours
- **Priority:** **FIX LATER** (ongoing)
- **Fix:** Add load tests for critical paths: sync upload, FL aggregation, intelligence generation, WhatsApp webhook processing.

#### L3: Missing Integration Features
- **Source:** Integration Test Report
- **Severity:** Low
- **Impact:** No biometric auth endpoint, no push notifications (FCM), no offline queue health reporting, no cloud ASR fallback. These are feature gaps, not bugs — the app works without them using existing fallbacks.
- **Effort:** 2–4 hours each
- **Priority:** **ACCEPT RISK** (backlog for post-GA)
- **Fix:** Add to product roadmap. Biometric auth and push notifications are highest-value additions.

---

## Action Plan

### Sprint 1: Unbreak Core Flows (Days 1–2)

| # | Issue | Owner | Effort | Dependencies |
|---|-------|-------|--------|--------------|
| 1 | C1 + M2: Fix OTP auth (field rename + device_id + user object) | App + Backend | 2h | None |
| 2 | C2: Fix sync schema (mapping layer + timestamp alignment) | App | 6h | None |
| 3 | C3 + M3 + H1 + L1: Fix FL pipeline (encryption + paths + encoding) | App + Backend | 8h | None |

**Exit criteria:** User can register, login, record transactions offline, sync online, and FL uploads succeed.

### Sprint 2: Close Integration Gaps (Days 3–5)

| # | Issue | Owner | Effort | Dependencies |
|---|-------|-------|--------|--------------|
| 4 | H2: Add model distribution endpoint or document CDN strategy | Backend | 4h | None |
| 5 | M1: Add intelligence response adapter | Backend | 3h | None |
| 6 | M6: Align sync conflict resolution strategy | App + Backend | 8h | C2 done |
| 7 | M5: Add runtime guard on HybridKeyExchange.complete() | Backend | 1h | None |

**Exit criteria:** All 7 integration points pass end-to-end testing.

### Sprint 3: Harden & Test (Days 6–10)

| # | Issue | Owner | Effort | Dependencies |
|---|-------|-------|--------|--------------|
| 8 | H3: Migrate FL state to Redis/PostgreSQL | Backend | 6h | None |
| 9 | M4: Write FL unit tests | Backend | 6h | None |
| 10 | L2: Add performance tests for critical paths | Backend | 12h | None |

**Exit criteria:** FL state is persistent, test coverage meets 80% target, performance baselines established.

### Backlog (Post-GA)

- L3: Biometric auth endpoint
- L3: Push notifications (FCM)
- L3: Offline queue health reporting
- L3: Cloud ASR fallback
- C2 (stretch): Full CRDT conflict resolution in backend

---

## Risk Matrix

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Auth completely broken (C1) | **Certain** (every user) | **Total** (no login) | Fix in Sprint 1, Day 1 |
| Sync failure (C2) | **Certain** (every offline user) | **Total** (data loss perception) | Fix in Sprint 1, Day 1–2 |
| FL non-functional (C3) | **Certain** (every FL upload) | **High** (no model improvement) | Fix in Sprint 1, Day 2 |
| FL state loss on restart (H3) | **Likely** (any deployment) | **High** (aggregation reset) | Fix in Sprint 3 |
| Intelligence display broken (M1) | **Certain** (every insight pull) | **Medium** (empty dashboard) | Fix in Sprint 2 |
| Conflict resolution data inconsistency (M6) | **Possible** (multi-device users) | **Medium** (duplicate/lost edits) | Fix in Sprint 2 |

---

## Summary

**3 critical blockers** must be fixed before any testing can proceed meaningfully — they affect authentication, data sync, and federated learning. All are fixable within 2 days.

**The backend is solid.** The feature test found zero issues. The backend test found only operational concerns (mutable state, missing tests). The problems are almost entirely in the **contract between app and backend** — mismatched field names, encoding, paths, and encryption schemes.

**Recommended immediate action:** Assign 2 engineers to Sprint 1. The fixes are well-defined, low-risk schema/routing changes with no architectural rework needed. After Sprint 1, the system should be end-to-end functional for a basic user flow.

---

*Report compiled by Decision Council from 3 independent test reports covering 168K+ lines of backend code, 20+ voice subsystem files, and 7 integration points.*
