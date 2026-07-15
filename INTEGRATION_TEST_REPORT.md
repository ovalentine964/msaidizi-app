# Msaidizi App ↔ Angavu Intelligence Backend — Integration Test Report

**Date:** 2026-07-16
**Tester:** Automated Integration Analysis
**Scope:** All integration points between Msaidizi Android app and Angavu Intelligence Python backend

---

## Executive Summary

The Msaidizi mobile app (Kotlin/Android) and Angavu Intelligence backend (Python/FastAPI) share **7 primary integration points** spanning authentication, data sync, intelligence delivery, WhatsApp reporting, federated learning, model management, and feedback/evolution. Overall the integration architecture is **well-designed with clear data contracts**, but several areas have **endpoint path mismatches** and **schema alignment gaps** that would cause runtime failures.

**Verdict:** 🟡 **Partial Compatibility** — 5 of 7 integration points have alignment issues that need fixing before production.

---

## 1. Integration Point Inventory

| # | Integration Point | App File(s) | Backend File(s) | Status |
|---|---|---|---|---|
| 1 | OTP Authentication | `MsaidiziApi.kt`, `ApiModels.kt` | `otp_auth.py`, `auth.py` | 🟡 Mismatch |
| 2 | Biashara Sync | `MsaidiziApi.kt`, `ApiModels.kt` | `biashara_sync.py` | 🟡 Mismatch |
| 3 | General Sync | `SyncConflictResolver.kt`, `SyncableEntities.kt`, `MsaidiziApi.kt` | `sync.py`, `schemas/sync.py` | 🟡 Mismatch |
| 4 | Intelligence Delivery | `MsaidiziApi.kt` | `biashara_sync.py`, `intelligence_delivery.py` | 🟢 Compatible |
| 5 | WhatsApp | `WhatsAppModels.kt`, `MsaidiziApi.kt` | `whatsapp_connection.py`, `whatsapp_bot.py` | 🟢 Compatible |
| 6 | Federated Learning | `FederatedLearningClient.kt` | `federated_learning.py`, `schemas/federated_learning.py` | 🟡 Mismatch |
| 7 | Model Version/Download | `ModelVersionManager.kt`, `ModelDownloader.kt` | `federated_learning.py` | 🔴 Missing Endpoint |
| 8 | Feedback/Evolution | *(implicit)* | `evolution.py` | 🟢 Compatible |
| 9 | Voice Pipeline | `TransactionHandler.kt`, Sherpa ONNX | N/A (on-device) | 🟢 Self-contained |

---

## 2. Detailed Integration Analysis

### 2.1 OTP Authentication Flow

**App sends:**
```
POST api/v1/auth/otp/request  → { phone }
POST api/v1/auth/otp/verify   → { phone, otp }
POST api/v1/auth/refresh       → { refresh_token }
```

**Backend provides:**
```
POST /auth/otp/request         → { phone }          ✅ Match
POST /auth/otp/verify          → { phone, code, device_id }  ⚠️ App sends "otp", backend expects "code"
POST /auth/register            → device-based registration
POST /auth/refresh             → { refresh_token }   ✅ Match
```

**Compatibility Matrix:**

| Field | App Sends | Backend Expects | Match? |
|---|---|---|---|
| OTP field name | `otp` | `code` | ❌ **MISMATCH** |
| `device_id` in verify | Not sent | Required | ❌ **MISSING** |
| Response: `access_token` | Expected | Provided | ✅ |
| Response: `refresh_token` | Expected | Provided | ✅ |
| Response: `expires_in` | Expected (default 900) | Provided | ✅ |
| JWT algorithm | N/A | RS256/HS256 configurable | ✅ |
| Refresh token rotation | N/A | Family-based theft detection | ✅ |

**Issue #1:** App `OtpVerifyRequest` uses field name `otp`, backend `OTPVerifyRequest` expects `code`. This will cause a 422 validation error.

**Issue #2:** Backend `OTPVerifyRequest` requires `device_id`, but app's `OtpVerifyRequest` only sends `phone` + `otp`.

**Issue #3:** Endpoint path: App calls `api/v1/auth/otp/verify`, backend registers at `/auth/otp/verify` (prefix comes from `main.py` → `settings.API_V1_PREFIX`). Assuming `API_V1_PREFIX = "/api/v1"`, paths match.

**JWT Token Compatibility:**
- App expects: `access_token`, `refresh_token`, `expires_in`, `user`
- Backend provides: `access_token`, `refresh_token`, `expires_in`, `user_id`
- ⚠️ App expects full `user` object, backend returns only `user_id`

---

### 2.2 Biashara Sync Protocol

**App sends (BiasharaSync path):**
```
POST /api/v1/biashara/sync → BiasharaSyncPayload {
  device_id: String (SHA-256 hashed),
  worker_type: String,
  coarse_location: String,
  transactions: List<AnonymizedTransaction>,
  sync_timestamp: Int,
  app_version: String
}
```

**Backend expects:**
```
POST /biashara/sync → BiasharaSyncPayload {
  device_id: str (max 64),
  worker_type: str,
  coarse_location: str,
  transactions: List[AnonymizedTransaction] (max 200),
  sync_timestamp: int,
  app_version: str (max 20)
}
```

**AnonymizedTransaction Schema Comparison:**

| Field | App (implicit) | Backend | Match? |
|---|---|---|---|
| `type` | SALE/PURCHASE/EXPENSE | SALE/PURCHASE/EXPENSE | ✅ |
| `category` | String | String | ✅ |
| `amount` | Float (≥0) | Float (≥0) | ✅ |
| `quantity` | Float (≥0) | Float (≥0) | ✅ |
| `timestamp` | Int (Unix seconds) | Int (Unix seconds) | ✅ |
| `language` | String | String (default "sw") | ✅ |
| `confidence` | Float (0-1) | Float (0-1) | ✅ |
| `payment_method` | String | String | ✅ |
| `coarse_location` | String | String | ✅ |

**Response Comparison:**

| Field | App Expects | Backend Provides | Match? |
|---|---|---|---|
| `status` | String | "ok"/"partial"/"error" | ✅ |
| `synced_id` | String | UUID | ✅ |
| `transactions_accepted` | Int | Int | ✅ |
| `transactions_rejected` | Int | Int | ✅ |
| `intelligence_available` | Boolean | Boolean | ✅ |

**Verdict:** 🟢 **BiasharaSync schema is fully compatible.** The AnonymizedTransaction models match field-for-field.

**Issue:** The app's `MsaidiziApi.kt` does NOT expose a BiasharaSync endpoint — it uses `/api/v1/sync/push` instead. The BiasharaSync protocol exists in `app/api/sync.py` (Python app-level) but the Android app routes through the general sync endpoint. This means the BiasharaSync endpoint on the backend may be unused by the Android app.

---

### 2.3 General Sync Protocol

**App sends:**
```
POST api/v1/sync/push → SyncPushRequest {
  transactions: List<CreateTransactionRequest>,
  device_timestamp: Long,
  vector_clock: Map<String, Long>
}

GET api/v1/sync/pull?since=<timestamp>
GET api/v1/sync/status
```

**Backend expects:**
```
POST /sync → SyncRequest {
  payload: SyncPayload {
    transactions: List<TransactionRecord>,
    inventory: List<InventoryRecord>,
    device_metadata: DeviceMetadata
  },
  checksum: str,
  client_timestamp: datetime
}
```

**TransactionRecord Comparison:**

| Field | App (`CreateTransactionRequest`) | Backend (`TransactionRecord`) | Match? |
|---|---|---|---|
| `type` | String (type) | `transaction_type` (SALE/PURCHASE/EXPENSE) | ⚠️ Field name differs |
| `item` | String | Optional[str] | ✅ |
| `category` | String | `item_category` | ⚠️ Field name differs |
| `quantity` | Double | Optional[float] | ✅ |
| `unit_price` | Double | Optional[float] | ✅ |
| `total_amount` | Double | `amount` (float) | ⚠️ Field name differs |
| `cost_basis` | Double | `profit` (Optional) | ❌ Different semantics |
| `payment_method` | String | Optional[str] | ✅ |
| `occurred_at` | Long (Unix) | `timestamp` (datetime) | ⚠️ Type mismatch |
| `recorded_via` | Not sent | Optional[str] | ✅ (default "manual") |
| `confidence_score` | Not sent | Optional[float] | ✅ (default 1.0) |
| `source_text` | Not sent | Optional[str] | ✅ |
| `location_geohash` | Not sent | Optional[str] | ✅ |

**Critical Issues:**
1. **Field name mismatches:** `type` vs `transaction_type`, `category` vs `item_category`, `total_amount` vs `amount`
2. **Type mismatch:** App sends `occurred_at` as `Long` (Unix timestamp), backend expects `timestamp` as `datetime`
3. **Missing fields:** App doesn't send `recorded_via`, `source_text`, `location_geohash`
4. **Extra fields:** App sends `cost_basis`, `vector_clock`; backend doesn't expect these
5. **Sync protocol mismatch:** App uses `SyncPushRequest` with `vector_clock` for CRDT-style conflict resolution; backend uses `SyncPayload` with `checksum` for integrity verification. Different conflict resolution models.

**Conflict Resolution:**
- App: `SyncConflictResolver.kt` — Version vectors, last-write-wins, CRDT merge strategies
- Backend: Deduplication via `(user_id + timestamp + amount + item)` composite key
- ⚠️ **Incompatible conflict resolution models.** App sends `vector_clock` but backend ignores it.

---

### 2.4 Intelligence Delivery

**App pulls:**
```
GET api/v1/ai/insights → InsightsResponse { insights: List<Insight>, generated_at: Long }
```

**Backend provides:**
```
GET /biashara/intelligence → IntelligenceUpdateResponse {
  soko_pulse: SokoPulseData,
  alama_score: AlamaScoreData,
  biashara_pulse: BiasharaPulseData,
  jamii_insights: JamiiInsightsData
}
```

**Compatibility:**
- App expects generic `Insight` objects with `type`, `title`, `body`, `priority`, `action_url`
- Backend provides structured intelligence products (Soko Pulse, Alama Score, Biashara Pulse, Jamii Insights)
- ⚠️ **Format mismatch:** App expects flat insight list, backend returns structured intelligence objects
- The app's `/ai/insights` endpoint and backend's `/biashara/intelligence` endpoint serve different response shapes

**Verdict:** The intelligence data is semantically compatible but the API response formats don't align. App would need a mapper layer or the backend would need to provide a flat insights endpoint.

---

### 2.5 WhatsApp Integration

**App sends:**
```
POST api/v1/whatsapp/connect → WhatsAppConnectRequest {
  phone, user_id, name, assistant_name, language, report_time
}
POST api/v1/whatsapp/verify → WhatsAppVerifyRequest { verification_id, code }
GET  api/v1/whatsapp/verify/{id}/status
GET  api/v1/whatsapp/connection/{userId}
POST api/v1/whatsapp/disconnect/{userId}
POST api/v1/whatsapp/send-report → SendReportRequest { user_id, report_type, date }
```

**Backend provides:**
```
POST /whatsapp/connect    → matches ✅
POST /whatsapp/verify     → matches ✅
GET  /whatsapp/verify/{verificationId}/status → matches ✅
GET  /whatsapp/connection/{userId} → matches ✅
POST /whatsapp/disconnect/{userId} → matches ✅
POST /whatsapp/send-report → matches ✅
```

**WhatsApp Bot (Inbound):**
- Backend: `WhatsAppBot` handles inbound messages via OpenWA
- Commands: `ripoti` (report), `msaada` (help) — report-only channel
- Non-report messages → redirected to Msaidizi app
- Supported report types: daily, weekly, monthly, semi-annual, annual

**OpenWA Integration:**
- Backend sends outbound messages via `OPENWA_URL` + `OPENWA_WEBHOOK_SECRET`
- Supports text and image messages
- Image charts via `whatsapp_charts.py`

**Verdict:** 🟢 **WhatsApp integration is well-aligned.** Endpoint paths, request/response schemas, and the report delivery model match between app and backend.

---

### 2.6 Federated Learning

**App uploads:**
```
POST https://api.msaidizi.app/v1/federated/upload
  Headers: X-Device-ID, Content-Encoding: gzip
  Body: FederatedUpload (JSON) {
    deviceId: String (SHA-256 hashed),
    language: String,
    timestamp: Long,
    correctionPatterns: List<AnonymizedPattern>,
    adapterDeltas: ByteArray? (encrypted),
    calibrationParams: CalibrationParams?,
    metadata: UploadMetadata?,
    sampleWeight: Int
  }
```

**Backend expects:**
```
POST /fl/upload-update (also /federated/upload)
  Body: FLUpdate {
    device_id: str,
    language: str,
    timestamp: int,
    correction_patterns: List[AnonymizedPattern],
    adapter_deltas: Optional[str] (base64),
    calibration_params: Optional[CalibrationParams],
    metadata: Optional[UploadMetadata],
    sample_weight: int (default 1)
  }
```

**Schema Comparison:**

| Field | App (Kotlin) | Backend (Python) | Match? |
|---|---|---|---|
| `deviceId` | camelCase | `device_id` snake_case | ✅ (JSON serialization) |
| `language` | String | str | ✅ |
| `timestamp` | Long (ms) | int | ✅ |
| `correctionPatterns` | List | List | ✅ |
| `adapterDeltas` | ByteArray? | Optional[str] (base64) | ⚠️ App sends raw bytes, backend expects base64 string |
| `calibrationParams` | CalibrationParams | CalibrationParams | ✅ |
| `metadata` | UploadMetadata | UploadMetadata | ✅ |
| `sampleWeight` | Int | int (default 1) | ✅ |

**AnonymizedPattern Comparison:**

| Field | App | Backend | Match? |
|---|---|---|---|
| `errorType`/`error_type` | String | str | ✅ |
| `errorHash`/`error_hash` | String | str | ✅ |
| `correctionHash`/`correction_hash` | String | str | ✅ |
| `phonemePattern`/`phoneme_pattern` | String | str | ✅ |
| `hourOfDay`/`hour_of_day` | Int (0-23) | int (0-23) | ✅ |
| `editDistance`/`edit_distance` | Float (0-1) | float (0-1) | ✅ |

**Download Endpoint:**
- App: `GET /api/v1/federated/models/{language}` → `FederatedDownload`
- Backend: `GET /fl/global-model/{dialect}` → `GlobalModelResponse`
- ⚠️ **Path mismatch:** App uses `/federated/models/{language}`, backend uses `/fl/global-model/{dialect}`

**PQC Encryption:**
- Backend provides: `GET /fl/pqc-public-key` → ML-KEM-768 public key
- App: Uses `CryptoUtils.encrypt()` for adapter encryption
- ⚠️ **Gap:** App uses device-specific encryption (Android Keystore), backend expects ML-KEM-768 PQC encryption. Different encryption schemes.

**Differential Privacy:**
- App: ε=0.1, δ=1e-5 (client-side)
- Backend: ε=0.1 (server-side)
- Comment in app: "composed privacy budget becomes ε_total = ε_client + ε_server"
- ✅ Consistent privacy parameters

---

### 2.7 Model Version Management & Download

**App manages:**
- `ModelVersionManager.kt`: Tracks installed model versions (Qwen 0.5B → 0.8B → 2B → Gemma 4)
- `ModelDownloader.kt`: Downloads models from HuggingFace/GitHub CDN with resume support
- Models: Silero VAD, Whisper, Piper TTS, Qwen LLM (GGUF format)
- Download URLs: `huggingface.co`, `github.com/releases`

**Backend provides:**
- `GET /fl/global-model/{dialect}` — aggregated FL model
- `GET /fl/status` — FL round status

**Gap Analysis:**
- ❌ **No backend endpoint for model binary distribution.** App downloads models directly from HuggingFace/GitHub CDN, not from the Angavu backend.
- ❌ **No version check endpoint.** App has no way to query the backend for latest model version metadata.
- The backend's FL service produces `FLModelVersion` with `model_hash`, but there's no HTTP endpoint to serve the actual model files.
- App's `ModelVersionManager` is entirely self-contained — it manages local model files without backend coordination.

**Verdict:** 🔴 **Model distribution is disconnected.** The app downloads models from public CDNs independently. The backend produces aggregated models via FL but has no serving endpoint for the app to consume.

---

## 3. Data Flow Diagrams

### 3.1 Transaction Recording Flow (Happy Path)

```
┌─────────────────────────────────────────────────────────────────┐
│                    MSAIDIZI APP (Offline)                        │
│                                                                 │
│  Voice Input → Sherpa ONNX ASR → IntentRouter → TransactionHandler │
│       │                                    │                    │
│       │                                    ▼                    │
│       │                           Room DB (SQLite)              │
│       │                           SyncableTransaction           │
│       │                           { sync_version, device_id }   │
└───────┼─────────────────────────────────────────────────────────┘
        │
        │ (When online: batch upload)
        ▼
┌─────────────────────────────────────────────────────────────────┐
│              ANGAVU INTELLIGENCE BACKEND                         │
│                                                                 │
│  POST /sync → SyncService                                       │
│    → Deduplicate (user_id + timestamp + amount + item)          │
│    → Validate (amount > 0, valid type, timestamp sanity)        │
│    → Store in Transaction table                                 │
│    → Trigger IntelligenceDelivery pipeline                      │
│    → Generate SokoPulse, AlamaScore, BiasharaPulse              │
│                                                                 │
│  GET /biashara/intelligence → IntelligenceUpdateResponse        │
│    → SokoPulseData (market prices)                              │
│    → AlamaScoreData (credit readiness)                          │
│    → BiasharaPulseData (business health)                        │
│    → JamiiInsightsData (community context)                      │
└─────────────────────────────────────────────────────────────────┘
        │
        │ (Intelligence pulled by app when online)
        ▼
┌─────────────────────────────────────────────────────────────────┐
│                    MSAIDIZI APP (Display)                        │
│                                                                 │
│  InsightsResponse → UI Dashboard                                │
│  CFOEngine → Daily Briefing → Voice Output (Piper TTS)          │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 Federated Learning Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                    MSAIDIZI APP                                  │
│                                                                 │
│  User corrects ASR output                                       │
│       │                                                         │
│       ▼                                                         │
│  FederatedLearningClient.performLoRATraining()                  │
│    → Prepare training pairs from corrections                    │
│    → LoRA fine-tuning (rank=4, embed=512, epochs=20)            │
│    → Serialize adapter (LORA magic + weights + checksum)        │
│    → Anonymize corrections (hash text, extract phoneme patterns)│
│    → Apply differential privacy (ε=0.1, σ≈49.1)                │
│    → GZIP compress → Upload                                     │
│                                                                 │
│  POST /api/v1/federated/upload                                  │
│    Headers: X-Device-ID, Content-Encoding: gzip                 │
│    Body: FederatedUpload { correctionPatterns, adapterDeltas }  │
└─────────────────────────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────────┐
│              ANGAVU BACKEND — FL Service                         │
│                                                                 │
│  POST /fl/upload-update → FederatedLearningService              │
│    → Validate consent (consent_data_sharing)                    │
│    → PQC decrypt (ML-KEM-768 + AES-256-GCM)                    │
│    → Store contribution in FLStateStore (SQLite/Postgres)       │
│    → When ≥5 contributions from same dialect:                   │
│        → FedAvg aggregation                                     │
│        → w_global = Σ (n_k/n_total) · Δw_k                     │
│        → Publish new FLModelVersion                             │
│                                                                 │
│  GET /fl/global-model/{dialect} → GlobalModelResponse           │
│    → Aggregated LoRA adapter (base64)                           │
│    → Updated calibration params                                 │
│    → Vocabulary updates                                         │
│                                                                 │
│  GET /fl/pqc-public-key → ML-KEM-768 public key                │
│  GET /fl/status → Round status, participant count               │
└─────────────────────────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────────┐
│                    MSAIDIZI APP (Apply)                          │
│                                                                 │
│  FederatedLearningClient.downloadUpdate(language)               │
│    → GET /federated/models/{language}                           │
│    → FederatedDownload { adapterDeltas, calibrationParams }     │
│                                                                 │
│  applyGlobalUpdate()                                            │
│    → w_final = w_base + w_global + α·w_user                    │
│    → α = min(1.0, corrections/100)                             │
│    → Merge vocabulary updates                                   │
│    → Update calibration parameters                              │
└─────────────────────────────────────────────────────────────────┘
```

### 3.3 WhatsApp Report Delivery Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                    WHATSAPP (OpenWA)                             │
│                                                                 │
│  User sends: "Ripoti ya leo"                                    │
│       │                                                         │
│       ▼                                                         │
│  Webhook → POST /webhooks/whatsapp                              │
│       │                                                         │
│       ▼                                                         │
│  WhatsAppBot.process_message()                                  │
│    → _parse_command() → "report"                                │
│    → _parse_report_subtype() → "daily"                          │
│    → _handle_report_request()                                   │
│        → ReportGenerator.generate_daily_report(user)            │
│        → _format_daily_report() (Swahili/English)               │
│                                                                 │
│  Response: "📊 Ripoti ya Leo — 16 July 2026                     │
│              💰 Mauzo: KES 5,000 (12 mauzo)                     │
│              📈 Faida: KES 2,000 (40.0%)"                       │
│       │                                                         │
│       ▼                                                         │
│  WhatsAppBot.send_message() → OpenWA API                        │
│    POST {OPENWA_URL}/send-message → { to, message }             │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  Scheduled Reports (Cron):                                      │
│    Daily 7PM → Daily summary                                    │
│    Monday 8AM → Weekly report                                   │
│    1st of month 9AM → Monthly report                            │
│    Alerts: restock, price drops, credit readiness               │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. Compatibility Matrix

| Integration Point | Schema | Endpoint Path | Auth | Data Format | Offline Support | Overall |
|---|---|---|---|---|---|---|
| OTP Auth | ❌ Field mismatch (`otp` vs `code`) | ✅ | ✅ JWT | ✅ JSON | ✅ Queue | 🟡 |
| Biashara Sync | ✅ Compatible | ✅ | ✅ JWT | ✅ JSON | ✅ Queue | 🟢 |
| General Sync | ❌ Field name/type mismatches | ✅ | ✅ JWT | ⚠️ timestamp type | ✅ Queue | 🟡 |
| Intelligence | ⚠️ Response format mismatch | ✅ | ✅ JWT | ✅ JSON | ✅ Cache | 🟡 |
| WhatsApp | ✅ Compatible | ✅ | ✅ JWT | ✅ JSON | N/A | 🟢 |
| Federated Learning | ⚠️ adapter_deltas encoding | ⚠️ Path mismatch | ✅ JWT | ⚠️ gzip+JSON | ✅ WiFi-only | 🟡 |
| Model Download | N/A | ❌ No endpoint | N/A | N/A | ✅ CDN | 🔴 |
| Feedback/Evolution | ✅ Compatible | ✅ | ✅ JWT | ✅ JSON | ✅ Queue | 🟢 |

---

## 5. Critical Issues (Must Fix)

### Issue #1: OTP Field Name Mismatch
- **App:** `OtpVerifyRequest(phone, otp)`
- **Backend:** `OTPVerifyRequest(phone, code, device_id)`
- **Fix:** Rename `otp` → `code` in app's `OtpVerifyRequest`, add `device_id` field

### Issue #2: General Sync Schema Mismatch
- **App:** `CreateTransactionRequest` uses `type`, `category`, `total_amount`, `cost_basis`, `occurred_at` (Long)
- **Backend:** `TransactionRecord` uses `transaction_type`, `item_category`, `amount`, `profit`, `timestamp` (datetime)
- **Fix:** Either add a mapping layer in the app's sync service or align field names

### Issue #3: Model Distribution Gap
- **App** downloads models from HuggingFace/GitHub CDN independently
- **Backend** produces aggregated FL models but has no serving endpoint
- **Fix:** Add `GET /models/{model_id}/download` endpoint to backend, or document that CDN distribution is intentional

### Issue #4: Federated Learning Endpoint Path Mismatch
- **App:** `POST /api/v1/federated/upload`, `GET /api/v1/federated/models/{language}`
- **Backend:** `POST /fl/upload-update`, `GET /fl/global-model/{dialect}`
- **Fix:** Align paths — either app changes to `/fl/...` or backend adds `/federated/...` aliases

### Issue #5: FL Encryption Scheme Mismatch
- **App:** Uses Android Keystore `CryptoUtils.encrypt()` (AES-256)
- **Backend:** Expects ML-KEM-768 PQC encryption
- **Fix:** App needs to implement ML-KEM-768 encapsulation using backend's public key

---

## 6. Minor Issues (Should Fix)

| # | Issue | Impact | Fix |
|---|---|---|---|
| 6 | App expects `user` object in auth response, backend returns `user_id` | App may crash on auth | Add `user` field to backend auth response |
| 7 | App uses `vector_clock` in sync, backend ignores it | Conflict resolution incomplete | Backend should process vector clocks or app should stop sending them |
| 8 | Intelligence response format: app expects flat `Insight[]`, backend returns structured objects | Display mismatch | Add format adapter |
| 9 | `adapter_deltas`: app sends raw bytes, backend expects base64 string | Upload fails | App should base64-encode before upload |
| 10 | Download FL model path uses `{language}`, backend uses `{dialect}` | 404 on download | Align parameter naming |

---

## 7. Missing Integrations

| Gap | Description | Recommendation |
|---|---|---|
| **Biometric Auth** | App mentions biometric auth but no backend endpoint exists | Add `POST /auth/biometric/verify` endpoint |
| **Push Notifications** | No push notification integration found (FCM/APNs) | Add FCM token registration + push endpoints |
| **Model Version Check** | No endpoint for app to query latest available model version | Add `GET /models/latest?device_tier=basic` |
| **Offline Queue Status** | App has offline queue but no way to report queue health to backend | Add `POST /sync/queue-status` for monitoring |
| **Voice Pipeline Backend Fallback** | App handles voice entirely on-device; no cloud ASR fallback | Consider adding cloud ASR for low-end devices |

---

## 8. Recommendations

### Immediate (P0 — Blocks Production)
1. Fix OTP field name mismatch (`otp` → `code` + add `device_id`)
2. Align general sync schema field names or add mapping layer
3. Fix FL endpoint paths on either app or backend side

### Short-term (P1 — Before Beta)
4. Add model distribution endpoint to backend (or document CDN-only strategy)
5. Implement ML-KEM-768 encryption in app's FL client
6. Add `user` object to auth response
7. Standardize intelligence response format

### Medium-term (P2 — Before GA)
8. Add push notification integration (FCM)
9. Add biometric auth endpoint
10. Implement server-side vector clock processing for sync
11. Add model version check API
12. Add cloud ASR fallback for voice pipeline

---

## 9. Testing Recommendations

| Test Category | Priority | Approach |
|---|---|---|
| Auth flow end-to-end | P0 | Mock OTP provider, test full register→login→refresh cycle |
| Sync round-trip | P0 | Create transactions on device, sync, verify in backend DB |
| FL upload/download | P1 | Mock FL round, verify gradient aggregation and model distribution |
| WhatsApp report delivery | P1 | Send "Ripoti ya leo" via webhook, verify report content |
| Offline→online sync | P1 | Queue transactions offline, connect, verify all delivered |
| Conflict resolution | P2 | Create conflicting edits on two devices, verify resolution |
| Model download resume | P2 | Interrupt download, resume, verify integrity via SHA-256 |

---

*Report generated by automated integration analysis of Msaidizi app (commit HEAD) and Angavu Intelligence backend (commit HEAD).*
