# Msaidizi Flutter Migration Architecture

> **Date:** 2026-07-16  
> **Source:** Kotlin/Android codebase — 343 files, ~115,126 lines  
> **Target:** Flutter 3.x + Dart 3.x (cross-platform: Android + iOS)

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Source Codebase Inventory](#2-source-codebase-inventory)
3. [Migration Strategy: Pure Dart vs Native FFI](#3-migration-strategy-pure-dart-vs-native-ffi)
4. [Complete Kotlin → Dart Class Mapping](#4-complete-kotlin--dart-class-mapping)
5. [Flutter Project Structure](#5-flutter-project-structure)
6. [Component Architecture Details](#6-component-architecture-details)
7. [Lines of Code Estimates](#7-lines-of-code-estimates)
8. [Dependency Mapping](#8-dependency-mapping)
9. [Migration Phases](#9-migration-phases)
10. [Risk Analysis](#10-risk-analysis)

---

## 1. Executive Summary

Msaidizi is an AI-powered business assistant for African informal workers. The Android app contains:

| Subsystem | Kotlin Files | Lines of Code | Complexity |
|-----------|-------------|---------------|------------|
| Agent System | 40+ | ~23,780 | 🔴 Critical |
| Voice Pipeline | 28 | ~15,042 | 🔴 Critical (native) |
| UI Layer | 41 | ~11,973 | 🟡 Medium |
| Onboarding | 19 | ~8,666 | 🟡 Medium |
| Security/PQC | 18 | ~5,505 | 🔴 Critical (native) |
| Language/Dialect | 39 | ~8,696 | 🟡 Medium |
| Social | 7 | ~3,437 | 🟢 Standard |
| Gamification | 5 | ~3,194 | 🟢 Standard |
| Loops (Reasoning) | 7 | ~2,729 | 🟢 Standard |
| Finance | 3 | ~2,634 | 🟢 Standard |
| Vision | 5 | ~2,560 | 🟡 Medium |
| AI/Model Mgmt | 4 | ~2,372 | 🟡 Medium |
| Core Models | 15 | ~2,330 | 🟢 Standard |
| Other | ~108 | ~19,868 | 🟢 Standard |

**Key architectural decisions:**
- **~70% pure Dart** — agents, loops, models, dialect, UI, gamification, finance, social
- **~20% platform channels** — voice pipeline (Sherpa-ONNX), camera/scanner, biometrics, audio
- **~10% dart:ffi** — llama.cpp (LLM inference), PQC crypto (liboqs)

---

## 2. Source Codebase Inventory

### 2.1 Native/JNI Components (require FFI or platform channels)

| Component | Current Implementation | Lines | Flutter Strategy |
|-----------|----------------------|-------|-----------------|
| **llama.cpp LLM** | JNI via `llama_jni.cpp` + CMake | ~200 (JNI) + engine | `dart:ffi` → llama.cpp shared lib |
| **Sherpa-ONNX ASR** | JNI via sherpa-onnx .so | ~2,000 (engine) | Platform channel or `dart:ffi` |
| **Sherpa-ONNX TTS** | JNI via sherpa-onnx .so | ~1,500 (engine) | Platform channel or `dart:ffi` |
| **Sherpa-ONNX VAD** | JNI via sherpa-onnx .so | ~500 | Platform channel or `dart:ffi` |
| **ONNX Runtime (Kokoro)** | `onnxruntime-android` | ~1,200 (engine) | `flutter_onnxruntime` or FFI |
| **Bouncy Castle PQC** | Java lib (ML-KEM, ML-DSA) | ~2,500 | `dart:ffi` → liboqs |
| **Audio recording** | Android AudioRecord | ~400 | Platform channel |
| **Audio playback** | Android AudioTrack | ~300 | Platform channel |
| **CameraX** | Receipt scanning | ~800 | `camera` Flutter plugin |
| **ML Kit OCR** | Text recognition | ~200 | `google_mlkit_text_recognition` |
| **Biometric auth** | AndroidX Biometric | ~300 | `local_auth` plugin |

### 2.2 Pure Logic Components (100% portable to Dart)

| Component | Files | Lines | Dart Equivalent |
|-----------|-------|-------|----------------|
| Orchestrator + Handlers | 12 | ~5,000 | Dart classes + Riverpod |
| IntentRouter + Patterns | 4 | ~2,500 | Dart RegExp + JSON config |
| ReAct/Reflexion/OODA loops | 7 | ~2,729 | Dart async classes |
| Conversation/Context Mgmt | 5 | ~3,000 | Dart classes |
| Dialect adapters (15+ langs) | 39 | ~8,696 | Dart classes + JSON data |
| Data models | 15 | ~2,330 | Dart classes (freezed) |
| Room DAOs → Drift | 12 | ~1,211 | Drift DAOs |
| Validation/Financial logic | 8 | ~3,121 | Dart classes |
| Gamification engine | 5 | ~3,194 | Dart classes |
| Social/Community | 7 | ~3,437 | Dart classes |
| Onboarding flow | 19 | ~8,666 | Flutter widgets + Riverpod |
| Evolution/Self-learning | 4 | ~1,626 | Dart classes |
| CFO/Briefing | 2 | ~1,520 | Dart classes |
| Proactive alerts | 4 | ~1,800 | Dart classes |

---

## 3. Migration Strategy: Pure Dart vs Native FFI

### 3.1 Decision Matrix

```
┌─────────────────────────────────────────────────────────────────┐
│                    MIGRATION DECISION TREE                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Does it use Android-only APIs?                                  │
│  ├─ YES → Platform Channel (MethodChannel/EventChannel)         │
│  │   ├─ Audio (AudioRecord/AudioTrack)                          │
│  │   ├─ Camera (CameraX)                                        │
│  │   ├─ Biometric (AndroidX Biometric)                          │
│  │   ├─ SMS verification                                        │
│  │   ├─ WorkManager (background tasks)                          │
│  │   └─ Foreground Service (voice)                              │
│  │                                                               │
│  ├─ Does it need native C/C++ performance?                      │
│  │   ├─ YES → dart:ffi                                          │
│  │   │   ├─ llama.cpp (GGUF inference)                          │
│  │   │   ├─ liboqs (PQC: ML-KEM, ML-DSA)                       │
│  │   │   └─ Sherpa-ONNX (ASR/TTS/VAD)                          │
│  │   │                                                           │
│  │   └─ Does it use a Java/Kotlin library?                      │
│  │       ├─ YES → Platform Channel wrapping the library         │
│  │       │   ├─ Bouncy Castle PQC (alternative to liboqs FFI)   │
│  │       │   ├─ ONNX Runtime (alternative: flutter_onnxruntime) │
│  │       │   └─ ML Kit OCR (google_mlkit plugin exists)         │
│  │       │                                                       │
│  │       └─ NO → Pure Dart                                       │
│  │           ├─ All business logic                               │
│  │           ├─ All data models                                  │
│  │           ├─ All agent/routing logic                          │
│  │           ├─ All reasoning loops                              │
│  │           ├─ All dialect/language processing                  │
│  │           └─ All UI (Flutter widgets)                         │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 Native Library Strategy

#### llama.cpp → dart:ffi

```dart
// lib/voice/native/llama_bindings.dart
import 'dart:ffi';
import 'dart:io';

// FFI bindings to llama.cpp shared library
// Built via CMake for arm64-v8a (Android) and arm64 (iOS)
final llamaLib = DynamicLibrary.open(
  Platform.isAndroid ? 'libllama_jni.so' : 'libllama.dylib',
);

// Native function signatures
typedef NativeLoadModel = Pointer<Void> Function(
  Pointer<Utf8> path, Int32 nCtx, Int32 nThreads, Uint8 useKvCacheQ4,
);
typedef DartLoadModel = Pointer<void> Function(
  String path, int nCtx, int nThreads, bool useKvCacheQ4,
);

final loadModel = llamaLib
  .lookupFunction<NativeLoadModel, DartLoadModel>('nativeLoadModel');
```

**Build strategy:** Ship pre-built `libllama.so` / `libllama.dylib` in the Flutter plugin's native libraries. CMakeLists.txt from the Android app adapts directly.

#### Sherpa-ONNX → Platform Channel (recommended) or dart:ffi

Sherpa-ONNX has a complex JNI layer. **Recommended approach:**

1. **Create a Flutter plugin** (`sherpa_onnx_flutter`) wrapping the Kotlin API
2. The existing `com.k2fsa.sherpa.onnx` Kotlin files (5 files, ~500 lines) become the Android side of the plugin
3. iOS side: use sherpa-onnx's C API via `dart:ffi` or Swift platform channel
4. Expose: `loadAsr()`, `transcribe(audioBytes)`, `loadTts()`, `speak(text)`, `loadVad()`, `detectVoice(audioChunk)`

#### Bouncy Castle PQC → dart:ffi to liboqs (recommended)

**Option A (recommended):** Use `dart:ffi` to [liboqs](https://github.com/open-quantum-safe/liboqs) — the reference C library for post-quantum crypto.

```dart
// lib/security/native/oqs_bindings.dart
final oqsLib = DynamicLibrary.open('liboqs.so');

// ML-KEM (Kyber) key encapsulation
typedef OQS_KEM_new = Pointer<OQS_KEM> Function(Pointer<Utf8> method_name);
typedef OQS_KEM_keypair = Int32 Function(
  Pointer<OQS_KEM> kem, Pointer<Uint8> public_key, Pointer<Uint8> secret_key,
);
```

**Option B:** Wrap Bouncy Castle via platform channel (simpler but Android-only). For iOS parity, use `liboqs`.

#### ONNX Runtime → flutter_onnxruntime package

The `flutter_onnxruntime` package provides cross-platform ONNX Runtime inference. Used for:
- Kokoro TTS (StyleTTS 2 model)
- Silero VAD
- Receipt scanning models

---

## 4. Complete Kotlin → Dart Class Mapping

### 4.1 Agent System (`agent/` → `lib/agents/`)

| Kotlin Class | Dart Class | Strategy | Notes |
|-------------|-----------|----------|-------|
| `Orchestrator` | `Orchestrator` | Pure Dart | Central coordinator, ~600 lines |
| `IntentRouter` | `IntentRouter` | Pure Dart | Regex-based, loads JSON patterns |
| `IntentPatternConfig` | `IntentPatternConfig` | Pure Dart | Loads from `assets/intent_patterns.json` |
| `IntentPatternLoader` | `IntentPatternLoader` | Pure Dart | JSON → regex compilation |
| `TransactionHandler` | `TransactionHandler` | Pure Dart | Sale/purchase/expense recording |
| `QueryHandler` | `QueryHandler` | Pure Dart | Balance/profit/stock queries |
| `AdviceHandler` | `AdviceHandler` | Pure Dart | Advice/greeting/help responses |
| `GamificationHandler` | `GamificationHandler` | Pure Dart | Goals/loans/giving |
| `DomainRouter` | `DomainRouter` | Pure Dart | Transport/farming/digital/service |
| `ConversationManager` | `ConversationManager` | Pure Dart | Memory, context, LLM escalation |
| `ConversationMemory` | `ConversationMemory` | Pure Dart | Follow-up detection, reference resolution |
| `ContextManager` | `ContextManager` | Pure Dart | Session context tracking |
| `AdaptiveLearningEngine` | `AdaptiveLearningEngine` | Pure Dart | Intent enhancement from learning |
| `AgentEventBus` | `AgentEventBus` | Pure Dart | Singleton event bus (Stream-based) |
| `AgentEvent` | `AgentEvent` (sealed class) | Pure Dart | Dart sealed classes |
| `BusinessAgent` | `BusinessAgent` | Pure Dart | Domain agent |
| `AnalysisAgent` | `AnalysisAgent` | Pure Dart | Trend analysis |
| `AdvisorAgent` | `AdvisorAgent` | Pure Dart | Financial advice |
| `LearningAgent` | `LearningAgent` | Pure Dart | Adaptive learning |
| `ModelRouter` | `ModelRouter` | Pure Dart | Model tier selection |
| `OutputSanitizer` | `OutputSanitizer` | Pure Dart | 10-layer defense-in-depth |
| `VoicePersonality` | `VoicePersonality` | Pure Dart | Swahili proverbs, cultural flavor |
| `WorkerClassifier` | `WorkerClassifier` | Pure Dart | Worker type detection |
| `PreferenceLearner` | `PreferenceLearner` | Pure Dart | User preference tracking |
| `UnifiedStateManager` | `UnifiedStateManager` | Pure Dart | Global state |
| `ErrorCompactor` | `ErrorCompactor` | Pure Dart | Error message compression |
| `BusinessPatternTracker` | `BusinessPatternTracker` | Pure Dart | Pattern detection |
| `ReasoningTemplates` | `ReasoningTemplates` | Pure Dart | Prompt templates |
| `AcademicFramework` | `AcademicFramework` | Pure Dart | Math/econ foundations |
| **Sub-packages:** | | | |
| `a2a/A2AProtocol` | `A2AProtocol` | Pure Dart | Agent-to-agent communication |
| `agi/AGIReadyLayer` | `AGIReadyLayer` | Pure Dart | Safety boundaries |
| `agi/ReasoningModelManager` | `ReasoningModelManager` | Pure Dart | Model management |
| `autonomy/ProgressiveAutonomy` | `ProgressiveAutonomy` | Pure Dart | Autonomy levels |
| `cost/InferenceCostTracker` | `InferenceCostTracker` | Pure Dart | Cost tracking |
| `harness/InferenceHarness` | `InferenceHarness` | Pure Dart | Circuit breakers, monitoring |
| `harness/LearningHarness` | `LearningHarness` | Pure Dart | Learning monitoring |
| `hermes/HermesSessionManager` | `HermesSessionManager` | Pure Dart | Session management |
| `knowledge/CrossDomainKnowledgeGraph` | `CrossDomainKnowledgeGraph` | Pure Dart | Knowledge graph |
| `loops/FeedbackLoop` | `FeedbackLoop` | Pure Dart | Feedback collection |
| `loops/HumanInTheLoop` | `HumanInTheLoop` | Pure Dart | Human approval |
| `loops/OodaLoop` | `OodaLoop` | Pure Dart | OODA reasoning cycle |
| `moe/ExpertRegistry` | `ExpertRegistry` | Pure Dart | MoE expert registry |
| `moe/MoERouter` | `MoERouter` | Pure Dart | Mixture of Experts routing |
| `multimodal/MultimodalPipeline` | `MultimodalPipeline` | Pure Dart + platform | Image/audio processing |
| `proactive/CashFlowPredictor` | `CashFlowPredictor` | Pure Dart | Cash flow prediction |
| `proactive/ProactiveAlertEngine` | `ProactiveAlertEngine` | Pure Dart | Alert generation |
| `proactive/ProactiveAnomalyDetector` | `ProactiveAnomalyDetector` | Pure Dart | Anomaly detection |
| `proactive/StockOutPredictor` | `StockOutPredictor` | Pure Dart | Stock prediction |
| `version/ModelVersionManager` | `ModelVersionManager` | Pure Dart | Model versioning |

### 4.2 Voice Pipeline (`voice/` → `lib/voice/`)

| Kotlin Class | Dart Class | Strategy | Notes |
|-------------|-----------|----------|-------|
| `VoicePipeline` | `VoicePipeline` | Pure Dart (orchestrator) | Coordinates STT→LLM→TTS |
| `AudioRecorder` | `AudioRecorder` | **Platform Channel** | Android AudioRecord |
| `SpeechRecognizer` | `SpeechRecognizer` | **Platform Channel** / FFI | Sherpa-ONNX ASR |
| `KokoroTtsEngine` | `KokoroTtsEngine` | **Platform Channel** / FFI | ONNX Runtime inference |
| `TextToSpeech` (Piper) | `PiperTtsEngine` | **Platform Channel** / FFI | Sherpa-ONNX TTS |
| `MMSTextToSpeech` | `MmsTtsEngine` | **Platform Channel** / FFI | Meta MMS TTS |
| `VoiceActivityDetector` | `VoiceActivityDetector` | **Platform Channel** / FFI | Silero VAD |
| `SherpaVoiceEngine` | `SherpaVoiceEngine` | **Platform Channel** / FFI | Unified Sherpa wrapper |
| `LlamaCppEngine` | `LlamaCppEngine` | **dart:ffi** | llama.cpp native |
| `LlmEngine` | `LlmEngine` | Pure Dart (interface) | Abstract LLM interface |
| `ModelRegistry` | `ModelRegistry` | Pure Dart | Model path resolution |
| `WhisperTokenizer` | `WhisperTokenizer` | Pure Dart | Token mapping |
| `VoicePipelineHarness` | `VoicePipelineHarness` | Pure Dart | Quality gates |
| `VoiceForegroundService` | N/A | **Platform Channel** | Android foreground service |
| `DialectLearningEngine` | `DialectLearningEngine` | Pure Dart | Dialect learning |
| `dialect/DialectDetectionEngine` | `DialectDetectionEngine` | Pure Dart | Dialect detection |
| `emotion/AudioFeatureExtractor` | `AudioFeatureExtractor` | **Platform Channel** / FFI | Audio analysis |
| `emotion/VoiceEmotionDetector` | `VoiceEmotionDetector` | Pure Dart | Emotion classification |
| `integration/VoiceModelRegistry` | `VoiceModelRegistry` | Pure Dart | Voice model paths |
| `msingiai/MsingiAiEvaluator` | `MsingiAiEvaluator` | Pure Dart | Model evaluation |
| `streaming/StreamingVoicePipeline` | `StreamingVoicePipeline` | Pure Dart + platform | Streaming STT→TTS |
| `sts/SpeechToSpeechEngine` | `SpeechToSpeechEngine` | Pure Dart + platform | STS pipeline |
| `sts/StsProvider` | `StsProvider` (abstract) | Pure Dart | STS interface |
| `sts/providers/ElevenLabsProvider` | `ElevenLabsProvider` | Pure Dart (HTTP) | Cloud TTS |
| `sts/providers/GptRealtimeProvider` | `GptRealtimeProvider` | Pure Dart (WebSocket) | GPT realtime |
| `sts/providers/LocalStsProvider` | `LocalStsProvider` | Pure Dart + platform | Local STS |
| `transfer/ModelTransfer` | `ModelTransfer` | Pure Dart | Model transfer |
| `transfer/SdCardModelLoader` | `SdCardModelLoader` | **Platform Channel** | SD card access |
| `waxal/WaxalAdapter` | `WaxalAdapter` | Pure Dart | WAXAL dialect adapter |
| `waxal/WaxalDialectEnhancer` | `WaxalDialectEnhancer` | Pure Dart | Dialect enhancement |
| `work/ModelDownloadWorker` | `ModelDownloadService` | **Platform Channel** | WorkManager |

### 4.3 Security (`security/` → `lib/security/`)

| Kotlin Class | Dart Class | Strategy | Notes |
|-------------|-----------|----------|-------|
| **PQC Layer** | | | |
| `CryptoProvider` | `CryptoProvider` (abstract) | Pure Dart | Interface |
| `CryptoKeyPair` | `CryptoKeyPair` | Pure Dart | Data class |
| `EncapsulatedKey` | `EncapsulatedKey` | Pure Dart | Data class |
| `MlKemProvider` | `MlKemProvider` | **dart:ffi** (liboqs) | ML-KEM-768 key encapsulation |
| `MlDsaProvider` | `MlDsaProvider` | **dart:ffi** (liboqs) | ML-DSA-65 signatures |
| `HybridKeyExchange` | `HybridKeyExchange` | **dart:ffi** (liboqs) | X25519 + ML-KEM-768 |
| `AlgorithmRegistry` | `AlgorithmRegistry` | Pure Dart | Algorithm catalog |
| `CryptoAuditLogger` | `CryptoAuditLogger` | Pure Dart | Audit trail |
| `DocumentSigner` | `DocumentSigner` | Pure Dart + FFI | Document signing |
| `PqcConfig` | `PqcConfig` | Pure Dart | Configuration |
| **Auth Layer** | | | |
| `BiometricAuthManager` | `BiometricAuthManager` | **Plugin** (`local_auth`) | Biometric |
| `JwtTokenManager` | `JwtTokenManager` | Pure Dart | JWT handling |
| `OtpManager` | `OtpManager` | Pure Dart + platform | SMS OTP |
| `SecureTokenStorage` | `SecureTokenStorage` | **Plugin** (`flutter_secure_storage`) | Encrypted storage |
| `SessionManager` | `SessionManager` | Pure Dart | Session lifecycle |
| **Crypto Layer** | | | |
| `EncryptedStorage` | `EncryptedStorage` | **Plugin** | Encrypted prefs |
| `KeyManager` (security) | `KeyManager` | Pure Dart + platform | Key management |
| `TlsConfig` | `TlsConfig` | Pure Dart | TLS configuration |
| **Privacy Layer** | | | |
| `ConsentManager` | `ConsentManager` | Pure Dart | Consent tracking |
| `DataMinimizer` | `DataMinimizer` | Pure Dart | Data minimization |
| `DataRetentionManager` | `DataRetentionManager` | Pure Dart | Retention policies |
| `DifferentialPrivacy` | `DifferentialPrivacy` | Pure Dart | DP implementation |
| `FederatedLearningPrivacy` | `FederatedLearningPrivacy` | Pure Dart | FL privacy |
| **SIM Swap Detection** | | | |
| `DeviceBinder` (security) | `DeviceBinder` | **Platform Channel** | Device binding |
| `SuspiciousLoginDetector` | `SuspiciousLoginDetector` | Pure Dart | Login analysis |
| **Validation** | | | |
| `ApiValidator` | `ApiValidator` | Pure Dart | API validation |
| `InputSanitizer` | `InputSanitizer` | Pure Dart | Input sanitization |

### 4.4 Database (`core/database/` → `lib/data/database/`)

| Kotlin Class | Dart Class | Strategy | Notes |
|-------------|-----------|----------|-------|
| `AppDatabase` | `AppDatabase` | **Drift** (was Room) | 33 entities, 18 DAOs |
| `TransactionDao` | `TransactionDao` | Drift | CRUD + queries |
| `InventoryDao` | `InventoryDao` | Drift | Inventory management |
| `GamificationDao` | `GamificationDao` | Drift | Gamification state |
| `GoalDao` | `GoalDao` | Drift | Goal tracking |
| `LoanDao` | `LoanDao` | Drift | Loan management |
| `TitheDao` | `TitheDao` | Drift | Tithe tracking |
| `PatternDao` | `PatternDao` | Drift | Business patterns |
| `StickinessDao` | `StickinessDao` | Drift | Engagement metrics |
| `BriefingDeliveryDao` | `BriefingDeliveryDao` | Drift | Briefing tracking |
| `VocabularyLearningDao` | `VocabularyLearningDao` | Drift | Vocabulary learning |
| `SocialDao` | `SocialDao` | Drift | Social features |
| `Converters` | `Converters` | Drift `TypeConverter`s | JSON/DateTime converters |
| `QueryTuples` | Query result classes | Drift | Typed query results |

**Database migration note:** Room → Drift is a direct mapping. Drift generates type-safe Dart code from schema definitions. SQLCipher encryption maps to `drift` + `sqlcipher_flutter_libs`.

### 4.5 Core Models (`core/model/` → `lib/models/`)

| Kotlin Class | Dart Class | Strategy | Notes |
|-------------|-----------|----------|-------|
| `Transaction` | `Transaction` | **freezed** + Drift entity | Immutable data class |
| `InventoryItem` | `InventoryItem` | freezed + Drift | Inventory model |
| `BusinessPattern` | `BusinessPattern` | freezed + Drift | Pattern model |
| `Intent` / `IntentResult` | `Intent`, `IntentResult` | freezed | Intent classification |
| `GamificationModels` | Multiple freezed classes | freezed | Gamification state |
| `GoalModels` | Multiple freezed classes | freezed | Goal tracking |
| `LoanModels` | Multiple freezed classes | freezed | Loan tracking |
| `TitheModels` | Multiple freezed classes | freezed | Tithe tracking |
| `StickinessModels` | Multiple freezed classes | freezed | Engagement |
| `AfricanCurrency` | `AfricanCurrency` | Pure Dart enum | Currency support |
| `AfricanTimezone` | `AfricanTimezone` | Pure Dart enum | Timezone support |
| `ModelTier` | `ModelTier` | Pure Dart enum | Device tier |
| `UserVocabulary` | `UserVocabulary` | freezed + Drift | Vocabulary |
| `UserCorrection` | `UserCorrection` | freezed + Drift | Corrections |
| `WorkerVocabulary` | `WorkerVocabulary` | freezed + Drift | Worker vocab |

### 4.6 Reasoning Loops (`loops/` → `lib/loops/`)

| Kotlin Class | Dart Class | Strategy | Notes |
|-------------|-----------|----------|-------|
| `ReActLoop` | `ReActLoop` | Pure Dart | Think→Act→Observe→Reflect |
| `ReasoningStep` | `ReasoningStep` | freezed | Step data |
| `ReActTrace` | `ReActTrace` | freezed | Full trace |
| `ReflexionLoop` | `ReflexionLoop` | Pure Dart | Self-critique loop |
| `Critique` | `Critique` | freezed | Critique data |
| `PlanExecuteLoop` | `PlanExecuteLoop` | Pure Dart | Plan→Execute loop |
| `MorningBriefingLoop` | `MorningBriefingLoop` | Pure Dart + platform | Scheduled briefing |
| `StreakProtectionLoop` | `StreakProtectionLoop` | Pure Dart | Streak management |
| `VariableRewardsLoop` | `VariableRewardsLoop` | Pure Dart | Variable rewards |
| `BriefingNotificationWorker` | `BriefingNotificationService` | **Platform Channel** | Background work |

### 4.7 UI Layer (`ui/` → `lib/ui/`)

| Kotlin Class | Flutter Widget | Strategy | Notes |
|-------------|---------------|----------|-------|
| `HomeFragment` + `HomeViewModel` | `HomeScreen` + `HomeController` | Flutter + Riverpod | Dashboard |
| `DashboardScreen` + `DashboardViewModel` | `DashboardScreen` + `DashboardController` | Flutter + Riverpod | Analytics |
| `RecordScreen` + `RecordViewModel` | `RecordScreen` + `RecordController` | Flutter + Riverpod | Transaction recording |
| `HistoryScreen` + `HistoryViewModel` | `HistoryScreen` + `HistoryController` | Flutter + Riverpod | Transaction history |
| `GoalScreen` + `GoalViewModel` | `GoalScreen` + `GoalController` | Flutter + Riverpod | Goal management |
| `LoanScreen` + `LoanViewModel` | `LoanScreen` + `LoanController` | Flutter + Riverpod | Loan tracking |
| `TitheScreen` + `TitheViewModel` | `TitheScreen` + `TitheController` | Flutter + Riverpod | Tithe tracking |
| `GamificationScreen` + `GamificationViewModel` | `GamificationScreen` + `GamificationController` | Flutter + Riverpod | Gamification |
| `MindsetScreen` + `MindsetViewModel` | `MindsetScreen` + `MindsetController` | Flutter + Riverpod | Mindset academy |
| `SettingsScreen` + `SettingsViewModel` | `SettingsScreen` + `SettingsController` | Flutter + Riverpod | Settings |
| `BusinessFlowFragment` + `BusinessFlowViewModel` | `BusinessFlowScreen` | Flutter + Riverpod | Business flow |
| `ModelDownloadFragment` + `ModelDownloadViewModel` | `ModelDownloadScreen` | Flutter + Riverpod | Model downloads |
| `ReceiptScanActivity` | `ReceiptScanScreen` | Flutter + camera plugin | Receipt scanning |
| `ReceiptConfirmationFragment` | `ReceiptConfirmScreen` | Flutter | Receipt confirmation |
| `CameraCaptureFragment` | `CameraCaptureScreen` | Flutter + camera plugin | Camera |
| **Components:** | | | |
| `VoiceButton` | `VoiceButton` | Flutter widget | Animated voice button |
| `TransactionCard` | `TransactionCard` | Flutter widget | Transaction display |
| `StatCard` | `StatCard` | Flutter widget | Stat display |
| `LoadingIndicator` | `LoadingIndicator` | Flutter widget | Loading state |
| `ScanReceiptButton` | `ScanReceiptButton` | Flutter widget | Receipt scan |
| `BadgeCard` | `BadgeCard` | Flutter widget | Achievement badge |
| `LevelProgress` | `LevelProgress` | Flutter widget | Level progress bar |
| `PronunciationFeedbackView` | `PronunciationFeedbackView` | Flutter widget | Voice feedback |
| **Theme:** | | | |
| `Color.kt` | `app_colors.dart` | Flutter `ColorScheme` | Color palette |
| `Theme.kt` | `app_theme.dart` | Flutter `ThemeData` | Theme definition |
| `Type.kt` | `app_typography.dart` | Flutter `TextTheme` | Typography |

### 4.8 Onboarding (`onboarding/` → `lib/ui/onboarding/`)

| Kotlin Class | Flutter Widget | Strategy |
|-------------|---------------|----------|
| `OnboardingActivity` | `OnboardingFlow` (Navigator) | Flutter navigation |
| `IntroductionFragment` | `IntroductionPage` | Flutter widget |
| `LanguageSelectionFragment` | `LanguageSelectionPage` | Flutter widget |
| `PersonalityFragment` | `PersonalityPage` | Flutter widget |
| `BusinessDiscoveryFragment` | `BusinessDiscoveryPage` | Flutter widget |
| `AgentNamingFragment` / `AgentNamingDialog` | `AgentNamingPage` | Flutter widget |
| `VoiceSetupFragment` | `VoiceSetupPage` | Flutter + platform |
| `ModelSetupFragment` + `ModelSetupViewModel` | `ModelSetupPage` | Flutter + platform |
| `PhoneVerificationFragment` + `PhoneVerificationStep` | `PhoneVerificationPage` | Flutter + platform |
| `WhatsAppConnectionStep` + `WhatsAppConnectionStepFragment` | `WhatsAppConnectionPage` | Flutter |
| `FirstUseFragment` | `FirstUsePage` | Flutter widget |
| `OnboardingConversation` | `OnboardingConversation` | Pure Dart |
| `BootstrapConversation` | `BootstrapConversation` | Pure Dart |
| `BootstrapActivity` + `BootstrapViewModel` | `BootstrapScreen` | Flutter |
| `WorkerProfile` | `WorkerProfile` | freezed + Drift |
| `WorkerUnderstanding` | `WorkerUnderstanding` | Pure Dart |
| `SupportedLanguage` | `SupportedLanguage` | Pure Dart enum |
| `ModelDownloadManager` | `ModelDownloadManager` | Pure Dart + platform |
| `SmsVerificationManager` | `SmsVerificationManager` | Pure Dart + platform |
| `WhatsAppVerificationManager` | `WhatsAppVerificationManager` | Pure Dart |
| `AhaMomentFlow` | `AhaMomentFlow` | Pure Dart |

### 4.9 Other Subsystems

| Kotlin Package | Dart Package | Strategy | Files→Lines |
|---------------|-------------|----------|------------|
| `gamification/` | `lib/gamification/` | Pure Dart | 5→3,194 |
| `finance/` | `lib/finance/` | Pure Dart | 3→2,634 |
| `cfo/` | `lib/cfo/` | Pure Dart | 2→1,520 |
| `social/` | `lib/social/` | Pure Dart | 7→3,437 |
| `evolution/` | `lib/evolution/` | Pure Dart | 4→1,626 |
| `memory/` | `lib/memory/` | Pure Dart | 1→627 |
| `mindset/` | `lib/mindset/` | Pure Dart | 2→973 |
| `mpesa/` | `lib/mpesa/` | Pure Dart + platform | 3→1,276 |
| `scanner/` | `lib/scanner/` | Flutter + camera | 5→1,470 |
| `vision/` | `lib/vision/` | Flutter + ML Kit | 5→2,560 |
| `sync/` | `lib/sync/` | Pure Dart + platform | 4→1,344 |
| `data/api/` | `lib/data/api/` | Pure Dart (dio) | 3→1,191 |
| `core/dialect/` | `lib/core/dialect/` | Pure Dart | 39→3,292 |
| `core/language/` | `lib/core/language/` | Pure Dart + platform | 8→5,404 |
| `core/validation/` | `lib/core/validation/` | Pure Dart | 4→1,951 |
| `core/util/` | `lib/core/util/` | Pure Dart | 4→1,170 |
| `core/ai/` | `lib/core/ai/` | Pure Dart + platform | 4→2,372 |
| `core/security/` | `lib/core/security/` | Pure Dart + platform | 3→1,264 |
| `update/` | `lib/update/` | Pure Dart + platform | 2→493 |
| `skills/` | `lib/skills/` | Pure Dart | 1→366 |

---

## 5. Flutter Project Structure

```
msaidizi_flutter/
├── pubspec.yaml
├── analysis_options.yaml
├── android/                          # Android platform code
│   └── app/src/main/
│       ├── kotlin/                   # Platform channel implementations
│       │   └── com/msaidizi/
│       │       ├── voice/            # Sherpa-ONNX, AudioRecord, AudioTrack
│       │       ├── camera/           # CameraX bridge
│       │       └── security/         # Biometric, device binding
│       ├── cpp/                      # Native C++ (llama.cpp, liboqs)
│       │   ├── CMakeLists.txt
│       │   ├── llama_jni.cpp
│       │   └── oqs_ffi.cpp
│       └── jniLibs/arm64-v8a/       # Pre-built native libraries
│           ├── libllama.so
│           ├── liboqs.so
│           ├── libsherpa-onnx-jni.so
│           └── libonnxruntime.so
├── ios/                              # iOS platform code
│   └── Runner/
│       ├── VoiceChannel.swift        # AVAudioEngine bridge
│       ├── CameraChannel.swift       # AVFoundation bridge
│       └── Native/
│           ├── libllama.dylib
│           └── liboqs.dylib
├── lib/
│   ├── main.dart                     # App entry point
│   ├── app.dart                      # MaterialApp, routing, theme
│   ├── injection.dart                # Riverpod providers / DI
│   │
│   ├── agents/                       # ═══ MULTI-AGENT SYSTEM ═══
│   │   ├── orchestrator.dart         # Central coordinator
│   │   ├── intent_router.dart        # Intent classification
│   │   ├── intent_pattern_config.dart # Pattern loading from JSON
│   │   ├── intent_pattern_loader.dart # JSON → regex compilation
│   │   ├── models/
│   │   │   ├── agent_response.dart   # AgentResponse, ResponseType
│   │   │   ├── intent_result.dart    # IntentResult, IntentType
│   │   │   └── agent_event.dart      # AgentEvent sealed class
│   │   ├── handlers/
│   │   │   ├── transaction_handler.dart
│   │   │   ├── query_handler.dart
│   │   │   ├── advice_handler.dart
│   │   │   ├── gamification_handler.dart
│   │   │   └── domain_router.dart
│   │   ├── conversation/
│   │   │   ├── conversation_manager.dart
│   │   │   ├── conversation_memory.dart
│   │   │   └── context_manager.dart
│   │   ├── domain/
│   │   │   ├── business_agent.dart
│   │   │   ├── analysis_agent.dart
│   │   │   ├── advisor_agent.dart
│   │   │   └── learning_agent.dart
│   │   ├── learning/
│   │   │   ├── adaptive_learning_engine.dart
│   │   │   └── preference_learner.dart
│   │   ├── safety/
│   │   │   ├── agi_ready_layer.dart
│   │   │   ├── output_sanitizer.dart
│   │   │   └── progressive_autonomy.dart
│   │   ├── proactive/
│   │   │   ├── proactive_alert_engine.dart
│   │   │   ├── cash_flow_predictor.dart
│   │   │   ├── anomaly_detector.dart
│   │   │   └── stock_out_predictor.dart
│   │   ├── knowledge/
│   │   │   └── cross_domain_knowledge_graph.dart
│   │   ├── a2a/
│   │   │   └── a2a_protocol.dart
│   │   ├── moe/
│   │   │   ├── expert_registry.dart
│   │   │   └── moe_router.dart
│   │   ├── harness/
│   │   │   ├── inference_harness.dart
│   │   │   └── learning_harness.dart
│   │   ├── cost/
│   │   │   └── inference_cost_tracker.dart
│   │   ├── version/
│   │   │   └── model_version_manager.dart
│   │   ├── voice_personality.dart
│   │   ├── worker_classifier.dart
│   │   ├── model_router.dart
│   │   ├── unified_state_manager.dart
│   │   ├── error_compactor.dart
│   │   ├── business_pattern_tracker.dart
│   │   ├── reasoning_templates.dart
│   │   ├── agent_event_bus.dart
│   │   └── academic_framework.dart
│   │
│   ├── voice/                        # ═══ VOICE PIPELINE ═══
│   │   ├── voice_pipeline.dart       # Pipeline orchestrator
│   │   ├── voice_pipeline_harness.dart # Quality gates
│   │   ├── models/
│   │   │   ├── transcription_result.dart
│   │   │   └── pipeline_state.dart
│   │   ├── engines/
│   │   │   ├── llm_engine.dart       # Abstract LLM interface
│   │   │   ├── llama_cpp_engine.dart # FFI to llama.cpp
│   │   │   ├── speech_recognizer.dart # ASR interface
│   │   │   ├── kokoro_tts_engine.dart # Kokoro TTS
│   │   │   ├── piper_tts_engine.dart  # Piper TTS
│   │   │   ├── mms_tts_engine.dart    # MMS TTS
│   │   │   └── sherpa_voice_engine.dart # Sherpa-ONNX unified
│   │   ├── native/
│   │   │   ├── llama_bindings.dart   # dart:ffi bindings
│   │   │   ├── llama_types.dart      # FFI type definitions
│   │   │   ├── oqs_bindings.dart     # liboqs FFI bindings
│   │   │   └── sherpa_bindings.dart  # Sherpa FFI bindings (if used)
│   │   ├── dialect/
│   │   │   └── dialect_detection_engine.dart
│   │   ├── emotion/
│   │   │   ├── audio_feature_extractor.dart
│   │   │   └── voice_emotion_detector.dart
│   │   ├── streaming/
│   │   │   └── streaming_voice_pipeline.dart
│   │   ├── sts/
│   │   │   ├── speech_to_speech_engine.dart
│   │   │   ├── sts_provider.dart
│   │   │   └── providers/
│   │   │       ├── eleven_labs_provider.dart
│   │   │       ├── gpt_realtime_provider.dart
│   │   │       └── local_sts_provider.dart
│   │   ├── waxal/
│   │   │   ├── waxal_adapter.dart
│   │   │   └── waxal_dialect_enhancer.dart
│   │   ├── model_registry.dart
│   │   ├── whisper_tokenizer.dart
│   │   ├── audio_recorder.dart       # Platform channel wrapper
│   │   ├── voice_activity_detector.dart
│   │   └── dialect_learning_engine.dart
│   │
│   ├── security/                     # ═══ SECURITY & PQC ═══
│   │   ├── crypto/
│   │   │   ├── providers/
│   │   │   │   ├── crypto_provider.dart # Abstract interface
│   │   │   │   ├── ml_kem_provider.dart # ML-KEM via liboqs FFI
│   │   │   │   ├── ml_dsa_provider.dart # ML-DSA via liboqs FFI
│   │   │   │   └── aes_gcm_provider.dart # AES-256-GCM (dart:crypto)
│   │   │   ├── hybrid_key_exchange.dart # X25519 + ML-KEM-768
│   │   │   ├── algorithm_registry.dart
│   │   │   ├── crypto_audit_logger.dart
│   │   │   ├── document_signer.dart
│   │   │   ├── key_manager.dart
│   │   │   ├── encrypted_storage.dart
│   │   │   └── tls_config.dart
│   │   │   └── pqc_config.dart
│   │   ├── auth/
│   │   │   ├── biometric_auth_manager.dart # local_auth plugin
│   │   │   ├── jwt_token_manager.dart
│   │   │   ├── otp_manager.dart
│   │   │   ├── secure_token_storage.dart # flutter_secure_storage
│   │   │   └── session_manager.dart
│   │   ├── privacy/
│   │   │   ├── consent_manager.dart
│   │   │   ├── data_minimizer.dart
│   │   │   ├── data_retention_manager.dart
│   │   │   ├── differential_privacy.dart
│   │   │   └── federated_learning_privacy.dart
│   │   ├── sim_swap/
│   │   │   ├── device_binder.dart
│   │   │   └── suspicious_login_detector.dart
│   │   └── validation/
│   │       ├── api_validator.dart
│   │       └── input_sanitizer.dart
│   │
│   ├── loops/                        # ═══ REASONING LOOPS ═══
│   │   ├── react_loop.dart           # ReAct: Think→Act→Observe→Reflect
│   │   ├── reflexion_loop.dart       # Self-critique loop
│   │   ├── ooda_loop.dart            # OODA: Observe→Orient→Decide→Act
│   │   ├── plan_execute_loop.dart    # Plan→Execute loop
│   │   ├── morning_briefing_loop.dart
│   │   ├── streak_protection_loop.dart
│   │   ├── variable_rewards_loop.dart
│   │   └── models/
│   │       ├── reasoning_step.dart
│   │       ├── react_trace.dart
│   │       ├── critique.dart
│   │       └── ooda_models.dart
│   │
│   ├── models/                       # ═══ DATA MODELS ═══
│   │   ├── transaction.dart
│   │   ├── inventory_item.dart
│   │   ├── business_pattern.dart
│   │   ├── gamification.dart
│   │   ├── goal.dart
│   │   ├── loan.dart
│   │   ├── tithe.dart
│   │   ├── african_currency.dart
│   │   ├── african_timezone.dart
│   │   ├── model_tier.dart
│   │   ├── worker_profile.dart
│   │   ├── user_vocabulary.dart
│   │   └── briefing.dart
│   │
│   ├── data/                         # ═══ DATA LAYER ═══
│   │   ├── database/
│   │   │   ├── app_database.dart     # Drift database definition
│   │   │   ├── app_database.g.dart   # Generated
│   │   │   ├── tables/
│   │   │   │   ├── transactions_table.dart
│   │   │   │   ├── inventory_table.dart
│   │   │   │   ├── gamification_table.dart
│   │   │   │   ├── goals_table.dart
│   │   │   │   ├── loans_table.dart
│   │   │   │   ├── tithe_table.dart
│   │   │   │   ├── patterns_table.dart
│   │   │   │   ├── vocabulary_table.dart
│   │   │   │   ├── social_table.dart
│   │   │   │   └── briefing_table.dart
│   │   │   ├── daos/
│   │   │   │   ├── transaction_dao.dart
│   │   │   │   ├── inventory_dao.dart
│   │   │   │   ├── gamification_dao.dart
│   │   │   │   ├── goal_dao.dart
│   │   │   │   ├── loan_dao.dart
│   │   │   │   ├── tithe_dao.dart
│   │   │   │   ├── pattern_dao.dart
│   │   │   │   ├── vocabulary_dao.dart
│   │   │   │   ├── social_dao.dart
│   │   │   │   └── briefing_dao.dart
│   │   │   └── converters.dart
│   │   ├── api/
│   │   │   ├── msaidizi_api.dart     # dio HTTP client
│   │   │   └── api_models.dart
│   │   └── sync/
│   │       ├── sync_manager.dart
│   │       ├── sync_queue.dart
│   │       ├── biashara_sync.dart
│   │       ├── network_monitor.dart
│   │       ├── offline_manager.dart
│   │       └── sync_conflict_resolver.dart
│   │
│   ├── core/                         # ═══ CORE UTILITIES ═══
│   │   ├── dialect/
│   │   │   ├── dialect_adapter.dart   # Abstract interface
│   │   │   ├── dialect_adapter_factory.dart
│   │   │   ├── dialect_config.dart
│   │   │   ├── dialect_utils.dart
│   │   │   ├── adaptive_vocabulary.dart
│   │   │   └── adapters/             # 15+ dialect adapters
│   │   │       ├── swahili_adapter.dart
│   │   │       ├── sheng_adapter.dart
│   │   │       ├── kikuyu_adapter.dart
│   │   │       ├── dholuo_adapter.dart
│   │   │       ├── kalenjin_adapter.dart
│   │   │       ├── luhya_adapter.dart
│   │   │       ├── maasai_adapter.dart
│   │   │       ├── migori_adapter.dart
│   │   │       ├── somali_adapter.dart
│   │   │       ├── amharic_adapter.dart
│   │   │       ├── hausa_adapter.dart
│   │   │       ├── igbo_adapter.dart
│   │   │       ├── yoruba_adapter.dart
│   │   │       ├── xhosa_adapter.dart
│   │   │       └── zulu_adapter.dart
│   │   │   └── data/                 # Dialect JSON data files
│   │   │       ├── sheng_vocabulary.json
│   │   │       ├── swahili_market.json
│   │   │       └── ...
│   │   ├── language/
│   │   │   ├── adaptive_asr_engine.dart
│   │   │   ├── confidence_calibrator.dart
│   │   │   ├── conversation_learning_pipeline.dart
│   │   │   ├── language_learning_pipeline.dart
│   │   │   ├── language_model_registry.dart
│   │   │   ├── phoneme_mapper.dart
│   │   │   └── federated_learning_client.dart
│   │   ├── ai/
│   │   │   ├── model_manager.dart
│   │   │   ├── model_downloader.dart
│   │   │   ├── bundled_model_manager.dart
│   │   │   └── sequential_model_loader.dart
│   │   ├── memory_manager.dart
│   │   ├── battery_optimizer.dart
│   │   ├── language_detector.dart
│   │   ├── code_switch_detector.dart
│   │   ├── sheng_updater.dart
│   │   ├── validation/
│   │   │   ├── anomaly_detector.dart
│   │   │   ├── currency_formatter.dart
│   │   │   ├── financial_validator.dart
│   │   │   └── mpesa_reconciler.dart
│   │   ├── util/
│   │   │   ├── swahili_parser.dart
│   │   │   ├── crypto_utils.dart
│   │   │   ├── device_capability.dart
│   │   │   └── device_tier.dart
│   │   ├── network/
│   │   │   └── pinned_http_client.dart
│   │   └── security/
│   │       ├── device_binder.dart
│   │       ├── key_manager.dart
│   │       └── quantum_ready_layer.dart
│   │
│   ├── gamification/                 # ═══ GAMIFICATION ═══
│   │   ├── gamification_engine.dart
│   │   ├── insight_rewards.dart
│   │   ├── level_progression_rewards.dart
│   │   ├── micro_rewards.dart
│   │   └── streak_recovery.dart
│   │
│   ├── finance/                      # ═══ FINANCE ═══
│   │   ├── goal_planner.dart
│   │   ├── loan_manager.dart
│   │   └── tithe_tracker.dart
│   │
│   ├── cfo/                          # ═══ CFO ENGINE ═══
│   │   ├── cfo_engine.dart
│   │   └── briefing_delivery.dart
│   │
│   ├── social/                       # ═══ SOCIAL FEATURES ═══
│   │   ├── community_tips.dart
│   │   ├── leaderboard_service.dart
│   │   ├── peer_comparison.dart
│   │   ├── social_handler.dart
│   │   ├── social_models.dart
│   │   └── whatsapp_community.dart
│   │
│   ├── mpesa/                        # ═══ M-PESA INTEGRATION ═══
│   │   ├── daraja_client.dart
│   │   ├── mpesa_sms_receiver.dart   # Platform channel
│   │   └── mpesa_statement_parser.dart
│   │
│   ├── scanner/                      # ═══ RECEIPT SCANNER ═══
│   │   ├── receipt_scan_manager.dart
│   │   ├── receipt_scanner.dart
│   │   └── receipt_confirmation.dart
│   │
│   ├── vision/                       # ═══ PRODUCT VISION ═══
│   │   ├── product_classifier.dart
│   │   ├── product_database.dart
│   │   ├── product_models.dart
│   │   ├── product_recognition_handler.dart
│   │   ├── vision_correction_tracker.dart
│   │   └── vision_harness.dart
│   │
│   ├── evolution/                    # ═══ SELF-EVOLUTION ═══
│   │   ├── self_evolution_manager.dart
│   │   ├── feedback_collector.dart
│   │   ├── feature_request_tracker.dart
│   │   └── evolution_controller.dart
│   │
│   ├── memory/                       # ═══ EPISODIC MEMORY ═══
│   │   └── episodic_memory.dart
│   │
│   ├── mindset/                      # ═══ MINDSET ACADEMY ═══
│   │   ├── mindset_academy.dart
│   │   └── rich_habits_score.dart
│   │
│   ├── update/                       # ═══ AUTO-UPDATE ═══
│   │   ├── auto_updater.dart
│   │   └── update_check_service.dart
│   │
│   ├── skills/                       # ═══ SKILL BRIDGE ═══
│   │   └── skill_bridge.dart
│   │
│   ├── ui/                           # ═══ FLUTTER UI ═══
│   │   ├── app.dart                  # MaterialApp, GoRouter
│   │   ├── theme/
│   │   │   ├── app_theme.dart
│   │   │   ├── app_colors.dart
│   │   │   └── app_typography.dart
│   │   ├── components/               # Shared widgets
│   │   │   ├── voice_button.dart
│   │   │   ├── transaction_card.dart
│   │   │   ├── stat_card.dart
│   │   │   ├── loading_indicator.dart
│   │   │   ├── scan_receipt_button.dart
│   │   │   └── animated_progress.dart
│   │   ├── screens/
│   │   │   ├── home/
│   │   │   │   ├── home_screen.dart
│   │   │   │   └── home_controller.dart
│   │   │   ├── dashboard/
│   │   │   │   ├── dashboard_screen.dart
│   │   │   │   └── dashboard_controller.dart
│   │   │   ├── record/
│   │   │   │   ├── record_screen.dart
│   │   │   │   ├── record_controller.dart
│   │   │   │   └── pronunciation_feedback_view.dart
│   │   │   ├── history/
│   │   │   │   ├── history_screen.dart
│   │   │   │   └── history_controller.dart
│   │   │   ├── goals/
│   │   │   │   ├── goal_screen.dart
│   │   │   │   └── goal_controller.dart
│   │   │   ├── loans/
│   │   │   │   ├── loan_screen.dart
│   │   │   │   └── loan_controller.dart
│   │   │   ├── tithe/
│   │   │   │   ├── tithe_screen.dart
│   │   │   │   └── tithe_controller.dart
│   │   │   ├── gamification/
│   │   │   │   ├── gamification_screen.dart
│   │   │   │   ├── gamification_controller.dart
│   │   │   │   ├── badge_card.dart
│   │   │   │   └── level_progress.dart
│   │   │   ├── mindset/
│   │   │   │   ├── mindset_screen.dart
│   │   │   │   └── mindset_controller.dart
│   │   │   ├── settings/
│   │   │   │   ├── settings_screen.dart
│   │   │   │   └── settings_controller.dart
│   │   │   ├── business_flow/
│   │   │   │   ├── business_flow_screen.dart
│   │   │   │   ├── business_flow_view.dart
│   │   │   │   └── flow_data.dart
│   │   │   ├── models/
│   │   │   │   ├── model_download_screen.dart
│   │   │   │   └── model_download_controller.dart
│   │   │   ├── scanner/
│   │   │   │   ├── receipt_scan_screen.dart
│   │   │   │   └── receipt_confirm_screen.dart
│   │   │   └── camera/
│   │   │       └── camera_capture_screen.dart
│   │   ├── onboarding/
│   │   │   ├── onboarding_flow.dart   # Navigator/GoRouter
│   │   │   ├── introduction_page.dart
│   │   │   ├── language_selection_page.dart
│   │   │   ├── personality_page.dart
│   │   │   ├── business_discovery_page.dart
│   │   │   ├── agent_naming_page.dart
│   │   │   ├── voice_setup_page.dart
│   │   │   ├── model_setup_page.dart
│   │   │   ├── phone_verification_page.dart
│   │   │   ├── whatsapp_connection_page.dart
│   │   │   ├── first_use_page.dart
│   │   │   └── bootstrap_screen.dart
│   │   └── accessibility/
│   │       ├── accessibility_tts_helper.dart
│   │       └── voice_input_helper.dart
│   │
│   └── platform/                     # ═══ PLATFORM CHANNELS ═══
│       ├── voice_channel.dart        # AudioRecord, AudioTrack, Sherpa
│       ├── camera_channel.dart       # CameraX bridge
│       ├── biometric_channel.dart    # Biometric auth
│       ├── sms_channel.dart          # SMS reading
│       ├── background_channel.dart   # WorkManager/BackgroundFetch
│       ├── notification_channel.dart # Local notifications
│       └── device_channel.dart       # Device info, tier detection
│
├── assets/
│   ├── intent_patterns.json          # Intent classification patterns
│   ├── dialect/                      # Dialect data files
│   │   ├── sheng_vocabulary.json
│   │   ├── swahili_market.json
│   │   └── ...
│   ├── models/                       # Model metadata (not actual models)
│   │   └── model_manifest.json
│   └── prompts/                      # LLM prompt templates
│       └── reasoning_templates.json
│
├── test/                             # Unit tests
│   ├── agents/
│   ├── loops/
│   ├── voice/
│   ├── security/
│   └── ...
│
├── integration_test/                 # Integration tests
│   ├── transaction_flow_test.dart
│   ├── voice_pipeline_test.dart
│   └── intent_router_test.dart
│
└── scripts/
    ├── setup-native-libs.sh          # Download/build native libraries
    ├── build-llama.sh                # Build llama.cpp for Android/iOS
    └── build-liboqs.sh              # Build liboqs for Android/iOS
```

---

## 6. Component Architecture Details

### 6.1 Voice Pipeline Architecture (Flutter)

```
┌──────────────────────────────────────────────────────────────────┐
│                    VoicePipeline (Pure Dart)                      │
│                                                                   │
│  ┌─────────┐   ┌──────────┐   ┌──────────┐   ┌──────────────┐  │
│  │ Audio   │──▶│ VAD      │──▶│ STT      │──▶│ IntentRouter │  │
│  │ Recorder│   │ (Silero) │   │ (Whisper)│   │ (Pure Dart)  │  │
│  └────┬────┘   └──────────┘   └──────────┘   └──────┬───────┘  │
│       │                                              │           │
│  Platform              Platform              Pure Dart           │
│  Channel               Channel                                │
│  (Android:             (Sherpa-ONNX                           │
│   AudioRecord)          JNI)                                  │
│                                                                   │
│  ┌──────────────┐   ┌──────────┐   ┌──────────┐                │
│  │ Orchestrator  │──▶│ LLM      │──▶│ TTS      │                │
│  │ (Pure Dart)   │   │ (llama)  │   │ (Kokoro) │                │
│  └──────────────┘   └──────────┘   └──────────┘                │
│                              │              │                    │
│                        dart:ffi       Platform Channel           │
│                        (llama.cpp)    (ONNX Runtime)            │
│                                                                   │
│  Memory Management (critical for 2GB devices):                   │
│  - Mutual exclusion: Whisper ↔ Kokoro never loaded together     │
│  - Lazy loading: models loaded on-demand                         │
│  - OOM protection: graceful degradation to text-only             │
└──────────────────────────────────────────────────────────────────┘
```

### 6.2 Security Architecture (Flutter)

```
┌──────────────────────────────────────────────────────────────────┐
│                    Security Layer (Flutter)                        │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ PQC Layer (dart:ffi → liboqs)                               │ │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────────────────────┐  │ │
│  │  │ ML-KEM   │  │ ML-DSA   │  │ Hybrid Key Exchange      │  │ │
│  │  │ (Kyber)  │  │(Dilithium│  │ X25519 + ML-KEM-768     │  │ │
│  │  │ Key      │  │ Sign)    │  │ HKDF combination         │  │ │
│  │  │ Encaps.  │  │          │  │                          │  │ │
│  │  └──────────┘  └──────────┘  └──────────────────────────┘  │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ Auth Layer (plugins)                                        │ │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌───────────┐  │ │
│  │  │Biometric │  │ JWT      │  │ OTP      │  │ Secure    │  │ │
│  │  │(local_   │  │ Manager  │  │ Manager  │  │ Storage   │  │ │
│  │  │ auth)    │  │ (Dart)   │  │(Platform)│  │(flutter_  │  │ │
│  │  │          │  │          │  │          │  │ secure)   │  │ │
│  │  └──────────┘  └──────────┘  └──────────┘  └───────────┘  │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ Privacy Layer (Pure Dart)                                   │ │
│  │  ConsentManager │ DataMinimizer │ DifferentialPrivacy       │ │
│  │  DataRetentionManager │ FederatedLearningPrivacy            │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

### 6.3 Agent System Architecture (Flutter)

```
┌──────────────────────────────────────────────────────────────────┐
│                    Agent System (Pure Dart)                        │
│                                                                   │
│  User Input ──▶ AdaptiveLearning ──▶ IntentRouter ──▶ Handler   │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ Orchestrator                                                 │ │
│  │  ├─ IntentRouter (regex, 90%+ accuracy, no LLM)            │ │
│  │  ├─ ConversationManager (memory, context, LLM escalation)  │ │
│  │  ├─ OutputSanitizer (10-layer defense)                      │ │
│  │  ├─ VoicePersonality (proverbs, cultural flavor)            │ │
│  │  ├─ AGIReadyLayer (safety boundaries)                       │ │
│  │  └─ ProgressiveAutonomy (human-in-the-loop)                │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ Reasoning Loops                                              │ │
│  │  ├─ ReActLoop (Think→Act→Observe→Reflect)                  │ │
│  │  ├─ ReflexionLoop (self-critique, retry)                    │ │
│  │  ├─ OodaLoop (Observe→Orient→Decide→Act, fast)             │ │
│  │  └─ PlanExecuteLoop (plan→execute→verify)                   │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ Domain Agents                                                │ │
│  │  ├─ BusinessAgent (transactions, inventory)                 │ │
│  │  ├─ AnalysisAgent (trends, anomalies, forecasting)          │ │
│  │  ├─ AdvisorAgent (financial advice, recommendations)        │ │
│  │  └─ LearningAgent (adaptive learning, vocabulary)           │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ Proactive Layer                                              │ │
│  │  ├─ ProactiveAlertEngine (monitoring, alerts)               │ │
│  │  ├─ CashFlowPredictor (forecasting)                         │ │
│  │  ├─ AnomalyDetector (outlier detection)                     │ │
│  │  └─ StockOutPredictor (inventory prediction)                │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

---

## 7. Lines of Code Estimates

### 7.1 Per-Subsystem Estimates (Dart)

| Subsystem | Kotlin LOC | Dart LOC (est.) | Notes |
|-----------|-----------|-----------------|-------|
| **Agent System** | 23,780 | ~16,600 | -30% (Dart is more concise, no Java boilerplate) |
| **Voice Pipeline** | 15,042 | ~10,500 | -30% (pure logic parts), native wrappers ~2,000 |
| **Security/PQC** | 5,505 | ~3,850 | -30%, FFI bindings ~500 extra |
| **Core/Dialect** | 8,696 | ~6,090 | -30% |
| **Core/Language** | 5,404 | ~3,780 | -30% |
| **Core/Models** | 2,330 | ~1,630 | -30% |
| **Core/Validation** | 1,951 | ~1,370 | -30% |
| **Core/Util** | 1,170 | ~820 | -30% |
| **Core/AI** | 2,372 | ~1,660 | -30% |
| **Core/Security** | 1,264 | ~885 | -30% |
| **Core/Database** | 1,211 | ~1,500 | +25% (Drift schema defs more verbose) |
| **Loops** | 2,729 | ~1,910 | -30% |
| **UI Layer** | 11,973 | ~10,000 | -17% (Flutter widgets are concise) |
| **Onboarding** | 8,666 | ~7,360 | -15% (widget code similar) |
| **Gamification** | 3,194 | ~2,240 | -30% |
| **Finance** | 2,634 | ~1,840 | -30% |
| **CFO** | 1,520 | ~1,060 | -30% |
| **Social** | 3,437 | ~2,410 | -30% |
| **M-Pesa** | 1,276 | ~890 | -30% |
| **Scanner** | 1,470 | ~1,200 | -18% (camera plugin usage) |
| **Vision** | 2,560 | ~1,790 | -30% |
| **Evolution** | 1,626 | ~1,140 | -30% |
| **Memory** | 627 | ~440 | -30% |
| **Mindset** | 973 | ~680 | -30% |
| **Sync** | 1,344 | ~940 | -30% |
| **Data/API** | 1,191 | ~830 | -30% |
| **Update** | 493 | ~345 | -30% |
| **Skills** | 366 | ~256 | -30% |
| **Platform Channels** | 0 | ~3,500 | New: Android + iOS native bridges |
| **FFI Bindings** | ~200 (JNI) | ~1,500 | llama.cpp + liboqs + Sherpa bindings |
| **DI/Routing/Config** | ~500 | ~800 | Riverpod + GoRouter + config |
| | | | |
| **TOTAL** | **~115,126** | **~87,000** | **~76% ratio** |

### 7.2 Breakdown by Language

| Layer | Dart LOC | Native (Kotlin/Swift/C++) LOC | Total |
|-------|---------|-------------------------------|-------|
| Pure Dart business logic | ~65,000 | 0 | ~65,000 |
| Flutter UI (widgets) | ~17,000 | 0 | ~17,000 |
| Platform channels | ~3,500 | ~4,000 (Kotlin) + ~2,000 (Swift) | ~9,500 |
| FFI bindings | ~1,500 | ~500 (C++) | ~2,000 |
| **TOTAL** | **~87,000** | **~6,500** | **~93,500** |

---

## 8. Dependency Mapping

### 8.1 Kotlin/Android → Flutter/Dart

| Android Dependency | Flutter Equivalent | Notes |
|-------------------|-------------------|-------|
| `Room` (database) | `drift` + `sqlite3_flutter_libs` | Type-safe SQL, code generation |
| `SQLCipher` | `sqlcipher_flutter_libs` | Encrypted database |
| `Hilt` (DI) | `riverpod` + `get_it` | Compile-time safe DI |
| `Kotlin Coroutines` | `dart:async` + `Stream` | Native async/await |
| `Kotlin Flow` | `Stream` / `BehaviorSubject` | Reactive streams |
| `Jetpack Compose` | Flutter widgets | Declarative UI |
| `ViewModel` + `LiveData` | `StateNotifier` / `AsyncNotifier` (Riverpod) | State management |
| `Navigation` | `go_router` | Declarative routing |
| `DataStore` | `shared_preferences` + `hive` | Key-value storage |
| `WorkManager` | `workmanager` plugin | Background tasks |
| `Retrofit` + `Gson` | `dio` + `json_serializable` | HTTP client |
| `Ktor Client` | `dio` or `http` | HTTP client |
| `Kotlin Serialization` | `json_serializable` + `freezed` | JSON serialization |
| `ONNX Runtime` | `flutter_onnxruntime` or FFI | ML inference |
| `Sherpa-ONNX` | Platform channel plugin | Voice processing |
| `llama.cpp` (JNI) | `dart:ffi` | LLM inference |
| `Bouncy Castle` | `dart:ffi` → `liboqs` | PQC crypto |
| `CameraX` | `camera` plugin | Camera access |
| `ML Kit OCR` | `google_mlkit_text_recognition` | On-device OCR |
| `Biometric` | `local_auth` | Biometric auth |
| `EncryptedSharedPrefs` | `flutter_secure_storage` | Secure storage |
| `Timber` (logging) | `logger` or `talker` | Structured logging |
| `Lottie` | `lottie` | Animations |
| `MPAndroidChart` | `fl_chart` | Charts |
| `Material Design 3` | Flutter Material 3 | UI components |
| `Sentry` | `sentry_flutter` | Crash reporting |
| `zstd` | `archive` or FFI | Compression |

### 8.2 State Management: Hilt + ViewModel → Riverpod

```dart
// Kotlin (Hilt + ViewModel):
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val transactionDao: TransactionDao,
    private val orchestrator: Orchestrator,
) : ViewModel() { ... }

// Flutter (Riverpod):
final transactionDaoProvider = Provider((ref) => TransactionDao(ref.read(dbProvider)));
final orchestratorProvider = Provider((ref) => Orchestrator(...));

class HomeController extends AsyncNotifier<HomeState> {
  @override
  Future<HomeState> build() async {
    final dao = ref.read(transactionDaoProvider);
    final transactions = await dao.getTodayTransactions();
    return HomeState(transactions: transactions);
  }
}
```

---

## 9. Migration Phases

### Phase 1: Foundation (Weeks 1-3)
- Flutter project scaffold
- Data layer (Drift database, all 33 entities, 18 DAOs)
- Core models (freezed classes)
- Riverpod DI setup
- GoRouter navigation
- Theme and design system

### Phase 2: Core Logic (Weeks 4-7)
- Agent system (Orchestrator, IntentRouter, all handlers)
- Reasoning loops (ReAct, Reflexion, OODA, PlanExecute)
- Dialect system (all 15+ adapters)
- Language detection and code-switching
- Validation and financial logic

### Phase 3: Voice Pipeline (Weeks 8-11)
- Platform channel: AudioRecord/AudioTrack
- dart:ffi: llama.cpp integration
- Platform channel or FFI: Sherpa-ONNX (ASR/TTS/VAD)
- VoicePipeline orchestrator
- Memory management (mutual exclusion for 2GB devices)
- Voice personality

### Phase 4: Security (Weeks 10-12, overlaps with Phase 3)
- dart:ffi: liboqs (ML-KEM, ML-DSA)
- Hybrid key exchange (X25519 + ML-KEM-768)
- Auth layer (biometric, JWT, OTP, secure storage)
- Privacy layer (consent, data minimization, DP)
- Encrypted database (SQLCipher)

### Phase 5: UI (Weeks 9-14, overlaps)
- Onboarding flow (12 screens)
- Home/Dashboard screens
- Transaction recording
- History, Goals, Loans, Tithe screens
- Gamification UI
- Settings
- Receipt scanner (camera + OCR)
- Voice button with animations

### Phase 6: Integration & Polish (Weeks 13-16)
- M-Pesa integration (Daraja API, SMS parsing)
- Sync layer (offline-first, conflict resolution)
- Social features (leaderboard, peer comparison)
- Self-evolution system
- CFO briefing engine
- Proactive alerts
- Performance optimization for 2GB devices
- Testing (unit, integration, widget)

### Phase 7: Platform Parity (Weeks 15-18)
- iOS platform channels (AVAudioEngine, AVFoundation)
- iOS-specific adaptations
- Cross-platform testing
- App store preparation

---

## 10. Risk Analysis

### 10.1 High-Risk Items

| Risk | Impact | Mitigation |
|------|--------|------------|
| **llama.cpp iOS build** | 🔴 Critical | llama.cpp has iOS support; use pre-built xcframeworks |
| **Sherpa-ONNX iOS** | 🔴 Critical | Sherpa-ONNX has iOS C API; wrap in Swift platform channel |
| **liboqs FFI complexity** | 🟡 High | Start with platform channel wrapping Bouncy Castle on Android; use liboqs for iOS later |
| **2GB device memory** | 🔴 Critical | Port MemoryManager mutual exclusion logic exactly; test on real 2GB devices |
| **Voice pipeline latency** | 🟡 High | Benchmark STT→LLM→TTS on target devices; optimize hot paths |
| **Database migration** | 🟡 High | Provide data migration path from Room (SQLite) to Drift (SQLite) — same underlying DB |
| **Dialect accuracy** | 🟡 Medium | Port all 15+ dialect adapters with their JSON data files; validate with native speakers |

### 10.2 Medium-Risk Items

| Risk | Impact | Mitigation |
|------|--------|------------|
| **Background processing** | 🟡 Medium | Use `workmanager` plugin; iOS has stricter background limits |
| **Camera/OCR quality** | 🟡 Medium | Flutter `camera` plugin + `google_mlkit_text_recognition` are well-maintained |
| **M-Pesa SMS parsing** | 🟢 Low | Pure Dart logic; only SMS reading needs platform channel |
| **State management complexity** | 🟡 Medium | Riverpod is mature; start with simple providers, add complexity as needed |

### 10.3 Low-Risk Items

| Risk | Impact | Mitigation |
|------|--------|------------|
| Business logic migration | 🟢 Low | Pure Dart translation; no native dependencies |
| UI migration | 🟢 Low | Flutter widgets are straightforward |
| Model/data classes | 🟢 Low | freezed code generation handles this |
| Testing | 🟢 Low | Flutter has excellent testing support |

---

## Appendix A: Native Library Build Requirements

### llama.cpp (for LLM inference)
```bash
# Android arm64-v8a
cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
      -DLLAMA_STATIC=OFF -DLLAMA_NATIVE=OFF \
      -B build-android && cmake --build build-android

# iOS arm64
cmake -DCMAKE_TOOLCHAIN_FILE=cmake/ios.toolchain.cmake \
      -DPLATFORM=OS64 -DARCHS=arm64 \
      -B build-ios && cmake --build build-ios
```

### liboqs (for PQC: ML-KEM, ML-DSA)
```bash
# Android arm64-v8a
cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a -DBUILD_SHARED_LIBS=ON \
      -DOQS_BUILD_ONLY_LIB=ON \
      -B build-android && cmake --build build-android

# iOS arm64
cmake -DCMAKE_TOOLCHAIN_FILE=cmake/ios.toolchain.cmake \
      -DPLATFORM=OS64 -DBUILD_SHARED_LIBS=ON \
      -B build-ios && cmake --build build-ios
```

### Sherpa-ONNX (for ASR/TTS/VAD)
```bash
# Pre-built binaries available from k2-fsa/sherpa-onnx releases
# Android: libsherpa-onnx-jni.so + libonnxruntime.so
# iOS: libsherpa-onnx.a + libonnxruntime.a
```

---

## Appendix B: Key Architectural Patterns

### B.1 Mutual Exclusion Pattern (Voice Models on 2GB Devices)

```dart
/// Ensures Whisper (40MB) and Kokoro (90MB) are NEVER loaded simultaneously
/// on devices with ≤2GB RAM. Total would be ~130MB → OOM crash.
class HeavyModelManager {
  LoadedHeavyModel _currentModel = LoadedHeavyModel.none;
  
  Future<bool> acquireModel(LoadedHeavyModel model, int memoryMB) async {
    if (_currentModel != LoadedHeavyModel.none && _currentModel != model) {
      // Unload current model first
      await _unloadCurrentModel();
    }
    // Check available memory
    if (!await _hasEnoughMemory(memoryMB)) return false;
    _currentModel = model;
    return true;
  }
  
  void releaseModel(LoadedHeavyModel model) {
    if (_currentModel == model) _currentModel = LoadedHeavyModel.none;
  }
}
```

### B.2 Platform Channel Pattern (Voice)

```dart
// Dart side
class VoiceChannel {
  static const _channel = MethodChannel('msaidizi/voice');
  
  Future<Uint8List> recordAudio({required int durationMs}) async {
    final result = await _channel.invokeMethod('recordAudio', {'durationMs': durationMs});
    return result as Uint8List;
  }
  
  Future<void> playAudio(Uint8List pcmData, int sampleRate) async {
    await _channel.invokeMethod('playAudio', {'data': pcmData, 'sampleRate': sampleRate});
  }
  
  Stream<VadResult> get vadResults => 
    const EventChannel('msaidizi/vad').receiveBroadcastStream().map(VadResult.fromMap);
}
```

### B.3 FFI Pattern (llama.cpp)

```dart
// Dart side
class LlamaCppEngine {
  static final _lib = DynamicLibrary.open('libllama_jni.so');
  
  static final _loadModel = _lib.lookupFunction<
    Pointer<Void> Function(Pointer<Utf8>, Int32, Int32, Uint8),
    Pointer<void> Function(String, int, int, bool)
  >('nativeLoadModel');
  
  static final _generate = _lib.lookupFunction<
    Pointer<Utf8> Function(Pointer<Void>, Pointer<Utf8>, Int32, Float),
    Pointer<Utf8> Function(Pointer<void>, String, int, double)
  >('nativeGenerate');
  
  Future<String> generate(String prompt, {int maxTokens = 256, double temperature = 0.3}) async {
    return Isolate.run(() {
      final result = _generate(_handle, prompt, maxTokens, temperature);
      return result.toDartString();
    });
  }
}
```

---

*This document serves as the complete architectural blueprint for migrating Msaidizi from Kotlin/Android to Flutter/Dart. It should be reviewed and updated as implementation progresses.*
