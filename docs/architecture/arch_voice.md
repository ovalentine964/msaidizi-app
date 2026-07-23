# Msaidizi Voice Architecture — Offline-First, Voice-Only, 100+ Dialects

> **Author:** Chief Voice Architect  
> **Date:** 2026-07-24  
> **Target:** $50 phones (Tecno/Infinix/Itel, 2GB RAM, Helio G25)  
> **Constraint:** Fully offline, voice-only, no keyboard/screen interaction  
> **Foundation:** Sherpa-ONNX for STT/TTS, Kokoro + Piper + MMS for TTS

---

## 1. Current State Analysis

### 1.1 What Exists (and What's Broken)

#### ✅ What's Working

| Component | File | Status | Notes |
|-----------|------|--------|-------|
| **VoicePipeline** | `VoicePipeline.kt` | ✅ Solid | Mutual exclusion model (Kokoro↔Whisper) is well-designed for 2GB devices. Audio focus management, graceful degradation to text input. |
| **SpeechRecognizer** | `SpeechRecognizer.kt` | ✅ Functional | Multi-tier ASR (Whisper Tiny INT4 → Moonshine → Turbo). Streaming ASR with ring buffer. Language token mapping for 19 languages. |
| **KokoroTtsEngine** | `KokoroTtsEngine.kt` | ✅ Functional | StyleTTS 2 based. 4 voice personalities (default/empathetic/excited/professional). Emotion-aware voice selection. Character-level phonemizer fallback. |
| **SherpaVoiceEngine** | `SherpaVoiceEngine.kt` | ✅ Functional | Unified engine for ASR/TTS/VAD via sherpa-onnx JNI. Native streaming ASR with OnlineRecognizer. Simulated streaming fallback. |
| **MMSTextToSpeech** | `MMSTextToSpeech.kt` | ✅ Functional | 10 African languages. Character-level tokenization. ~35MB per language model. |
| **VoicePipelineHarness** | `VoicePipelineHarness.kt` | ✅ Excellent | Quality gates at every stage. STT confidence < 0.6 → ask to repeat. Processing feedback in Swahili ("Sawa, nimesikia..."). Metrics collection. |
| **DialectLearningEngine** | `DialectLearningEngine.kt` | ✅ Comprehensive | 14+ dialect detection. Phoneme confusion matrices. Code-switching detection. Federated learning gradient accumulation. Dialect lock after 5 consecutive detections. |
| **ModelRegistry** | `ModelRegistry.kt` | ✅ Solid | SHA-256 verification. Resume-capable downloads. Progressive tier loading (BUNDLED → FIRST_LAUNCH → ON_DEMAND). |
| **Dialect Detection (Python)** | `dialect_detection/__init__.py` | ✅ Good | Multi-signal ensemble: lexical markers (0.35), script detection (0.25), n-gram LM (0.25), morphological (0.10), context (0.05). 15+ dialect marker sets. |
| **Federated Learning (Python)** | `federated_learning/__init__.py` | ✅ Strong | FedAvg/Krum/TrimmedMean. Differential privacy (ε=0.1, δ=10⁻⁵). Anomaly detection. Per-round encryption keys. |
| **Fine-Tuning (Python)** | `fine_tuning/__init__.py` | ✅ Good | LoRA rank 8-16. Qwen 0.5B base. Training during charging/idle (2-5 AM). Per-user + per-dialect adapter layering. |
| **Voice Collection (Python)** | `voice_collection/__init__.py` | ✅ Strong | CARE Principles. 3-tier consent (Offline/Federated/Cloud). On-device encrypted storage. Right to erasure. |
| **Code Switching (Python)** | `code_switching/__init__.py` | ✅ Functional | Sliding window segmentation. User code-switch profiles. Swahili-English-Sheng mixing. |
| **Seed Vocabulary** | `seed_vocabulary_sw.json` | ✅ Excellent | 195 market terms. Measurement units, currency slang (mbao=KSh20, thao=KSh1000), ASR error corrections. |

#### ❌ What's Broken or Missing

| Gap | Severity | Description |
|-----|----------|-------------|
| **No VAD in legacy pipeline** | 🔴 Critical | `VoiceActivityDetector.kt` (legacy) uses raw ONNX Runtime. Silero VAD only available via Sherpa-ONNX. If Sherpa-ONNX JNI fails to load, there's no VAD — the pipeline can't detect speech boundaries. |
| **Sherpa-ONNX JNI dependency** | 🔴 Critical | `SherpaVoiceEngine` checks `SherpaOnnxLoader.isAvailable` and silently disables everything if JNI libs are missing. The entire Sherpa pipeline is gated behind native library availability. |
| **No interruption handling during TTS** | 🟡 Major | When user speaks during TTS playback, there's no barge-in detection. The system waits for TTS to finish before listening again. Voice-only UX requires interruption support. |
| **Phonemizer gap for non-Swahili** | 🟡 Major | `KokoroTtsEngine.tryEspeakPhonemize()` always returns `null` — espeak-ng isn't available on Android. Falls back to character-level, which works for Swahili (phonetic) but fails for tonal languages (Yoruba, Igbo). |
| **No model pre-bundling** | 🟡 Major | All models download on first launch. On 2G networks in rural Africa, this is a showstopper. Core models (VAD + tiny ASR + Piper TTS) should be APK-bundled. |
| **Streaming ASR quality** | 🟡 Major | Simulated streaming (sliding window Whisper) has high latency (~2s windows). True streaming requires a transducer model (Zipformer/Conformer) that isn't shipped yet. |
| **No wake word / always-listening** | 🟡 Major | Voice-only interface needs a wake word ("Msaidizi") to activate. Currently requires a button press — incompatible with voice-only mandate. |
| **Dialect learning offline sync** | 🟠 Minor | `DialectLearningEngine.triggerGradientUpload()` requires network. No queue-and-retry for offline-first federated learning. |
| **Code-switching in STT** | 🟠 Minor | Whisper handles multilingual input but doesn't explicitly segment code-switched speech. The Python `CodeSwitchHandler` operates on text post-STT, not during recognition. |
| **TTS for learned dialects** | 🟠 Minor | When the system learns a new dialect vocabulary, there's no mechanism to generate TTS for dialect-specific words. Only Kokoro (Swahili/English) and MMS (10 languages) have pre-trained voices. |

### 1.2 Memory Budget (2GB Device)

```
┌─────────────────────────────────────────────────────────┐
│                  2GB RAM Budget                          │
├──────────────────────────┬──────────────────────────────┤
│ Android OS + framework   │ ~800 MB                      │
│ App heap (Dalvik)        │ ~256 MB                      │
│ Available for ML models  │ ~944 MB                      │
├──────────────────────────┼──────────────────────────────┤
│ ONE heavy model at a time:                               │
│   Whisper Tiny INT4      │ ~40 MB (ASR)                 │
│   Kokoro Swahili         │ ~90 MB (TTS, high quality)   │
│   Piper Swahili          │ ~25 MB (TTS, fast)           │
│   Silero VAD             │ ~5 MB  (always loaded)       │
│   Qwen 0.5B Q4_K_M      │ ~580 MB (LLM)               │
├──────────────────────────┼──────────────────────────────┤
│ Mutually exclusive pairs:                                │
│   Whisper + Kokoro       │ ❌ NEVER simultaneously       │
│   Whisper + Qwen         │ ❌ NEVER simultaneously       │
│   Kokoro + Qwen          │ ❌ NEVER simultaneously       │
├──────────────────────────┼──────────────────────────────┤
│ Always loaded:                                            │
│   VAD + Piper TTS        │ ~30 MB (lightweight fallback) │
└──────────────────────────┴──────────────────────────────┘
```

---

## 2. Voice Pipeline Design — Complete Flow

### 2.1 Pipeline Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                    VOICE-ONLY INTERACTION LOOP                       │
│                                                                      │
│  ┌──────────┐    ┌──────┐    ┌──────┐    ┌───────┐    ┌──────────┐  │
│  │ Wake Word│───▶│ VAD  │───▶│ STT  │───▶│ Intent│───▶│  Agent   │  │
│  │ Detector │    │      │    │      │    │Router │    │(LLM/Rule)│  │
│  └──────────┘    └──────┘    └──────┘    └───────┘    └────┬─────┘  │
│       ▲                                                     │        │
│       │          ┌──────────┐    ┌──────┐    ┌───────┐     │        │
│       └──────────│  Audio   │◀───│ TTS  │◀───│Format │◀────┘        │
│                  │  Output  │    │      │    │Response│              │
│                  └──────────┘    └──────┘    └───────┘              │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 Detailed Flow with Sherpa-ONNX

```
STAGE 1: ALWAYS-ON WAKE WORD DETECTION
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
AudioRecord (16kHz, mono, 16-bit PCM)
    │
    ▼
┌─────────────────────────────────────────────┐
│ Sherpa-ONNX VAD (Silero, ~5MB, always on)   │
│ - Processes 512-sample chunks (~32ms)        │
│ - Detects speech start/end                   │
│ - Filters background noise                   │
│ - Threshold: 0.5, min speech: 250ms          │
│ - Max speech: 30s (auto-endpoint)            │
└─────────────────────┬───────────────────────┘
                      │ speech detected
                      ▼
STAGE 2: SPEECH-TO-TEXT
━━━━━━━━━━━━━━━━━━━━━━
┌─────────────────────────────────────────────┐
│ Sherpa-ONNX OfflineRecognizer (Whisper Tiny) │
│ - Loads ONLY when speech detected (lazy)     │
│ - Unloads Kokoro TTS first (mutual exclusion)│
│ - Processes accumulated VAD speech segments   │
│ - Language hint: "sw" (default)              │
│ - Output: text + confidence + timestamps     │
│                                              │
│ On BASIC tier:                                │
│   1. Unload Kokoro TTS (~90MB freed)         │
│   2. Load Whisper Tiny (~40MB)               │
│   3. Transcribe                              │
│   4. Unload Whisper (~40MB freed)            │
│   5. Piper TTS remains available (~25MB)     │
└─────────────────────┬───────────────────────┘
                      │ text + confidence
                      ▼
STAGE 3: QUALITY GATE — STT
━━━━━━━━━━━━━━━━━━━━━━━━━━
┌─────────────────────────────────────────────┐
│ VoicePipelineHarness.sttQualityGate()        │
│                                              │
│ Checks:                                      │
│ - confidence >= 0.6 → proceed                │
│ - confidence < 0.6 → "Sikusikia vizuri.     │
│                        Tafadhali rudia."     │
│ - text length >= 2 chars                     │
│ - not gibberish (repetition check)           │
│ - noise level < 0.8                          │
│                                              │
│ On FAIL: emit Swahili repeat request,        │
│          return to listening                  │
└─────────────────────┬───────────────────────┘
                      │ validated text
                      ▼
STAGE 4: DIALECT DETECTION + LEARNING
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
┌─────────────────────────────────────────────┐
│ DialectLearningEngine.processUtterance()     │
│                                              │
│ 1. Detect dialect (lexical + n-gram +        │
│    morphological + context ensemble)         │
│ 2. Update dialect lock (5 consecutive → lock)│
│ 3. Extract speech patterns:                  │
│    - Pronunciation variants (ASR errors)     │
│    - Vocabulary extensions (new words)       │
│    - Phoneme substitutions (l→r, th→s)      │
│    - Code-switching boundaries               │
│ 4. Update dialect profile                    │
│ 5. Apply immediate adaptations:              │
│    - Vocabulary injection (3+ sightings)     │
│    - N-gram prior boost (5+ sightings)       │
│    - Phoneme mapping (3+ sightings)          │
│ 6. Accumulate gradients for federated sync   │
└─────────────────────┬───────────────────────┘
                      │ enriched text + dialect context
                      ▼
STAGE 5: INTENT ROUTING
━━━━━━━━━━━━━━━━━━━━━━
┌─────────────────────────────────────────────┐
│ IntentRouter / DomainRouter                  │
│                                              │
│ Voice-only constraint: ALL intents must have │
│ voice responses. No "show on screen" intents.│
│                                              │
│ Intent categories:                           │
│ - TRANSACTION: "Nimeuzia mchele kilo mbili"  │
│ - QUERY: "Bei ya tomato ni gapi?"            │
│ - ADVICE: "Nifanye nini na deni langu?"      │
│ - BRIEFING: "Habari za biashara leo"         │
│ - NAVIGATION: "Nenda kwenye orodha"          │
│ - CONFIRMATION: "Ndio" / "Hapana"            │
│ - CLARIFICATION: "Umesema nini?"             │
└─────────────────────┬───────────────────────┘
                      │ intent + context
                      ▼
STAGE 6: AGENT EXECUTION
━━━━━━━━━━━━━━━━━━━━━━━
┌─────────────────────────────────────────────┐
│ Orchestrator → Domain-specific Agent         │
│                                              │
│ For 2GB devices:                             │
│ - Rule-based agents preferred (no LLM load)  │
│ - LLM loaded on-demand for complex queries   │
│ - LLM + TTS mutually exclusive               │
│                                              │
│ Processing feedback during execution:        │
│ "Nafikiria..." (Thinking...)                 │
│ "Sawa, ngoja..." (OK, wait...)               │
└─────────────────────┬───────────────────────┘
                      │ response text
                      ▼
STAGE 7: RESPONSE FORMATTING
━━━━━━━━━━━━━━━━━━━━━━━━━━━
┌─────────────────────────────────────────────┐
│ OutputSanitizer + ResponseFormatter          │
│                                              │
│ Voice-only constraints:                      │
│ - No URLs, no tables, no markdown            │
│ - Numbers spoken as words ("mia mbili" not   │
│   "200")                                     │
│ - Currency in local slang ("thao" for 1000)  │
│ - Short sentences (< 15 words per sentence)  │
│ - Confirmation questions at the end          │
│ - Match user's code-switching pattern        │
└─────────────────────┬───────────────────────┘
                      │ formatted response
                      ▼
STAGE 8: TEXT-TO-SPEECH
━━━━━━━━━━━━━━━━━━━━━━━
┌─────────────────────────────────────────────┐
│ TTS Engine Selection (per language/dialect):  │
│                                              │
│ Swahili/English (2GB):                       │
│   Piper (~25MB) → always available           │
│   Kokoro (~90MB) → loaded if memory allows   │
│                                              │
│ Swahili/English (3GB+):                      │
│   Kokoro (~90MB) → primary (best quality)    │
│   Piper (~25MB) → fallback                   │
│                                              │
│ Other African languages:                     │
│   MMS (~65MB per language) → on-demand       │
│   Character-level fallback → last resort     │
│                                              │
│ Emotion-aware voice selection:               │
│   Frustrated → Empathetic (slow, warm)       │
│   Happy → Excited (fast, energetic)          │
│   Urgent → Professional (clear, measured)    │
└─────────────────────┬───────────────────────┘
                      │ audio playback
                      ▼
STAGE 9: AUDIO OUTPUT + BARGE-IN
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
┌─────────────────────────────────────────────┐
│ AudioTrack playback with:                    │
│ - Audio focus management (duck/transient)    │
│ - Barge-in detection: if VAD detects speech  │
│   during TTS playback → stop TTS, start STT │
│ - Completion wait before returning to IDLE   │
│ - Abandon audio focus after playback         │
└─────────────────────────────────────────────┘
```

### 2.3 Handling Interruptions, Noise, and Background Sounds

#### Barge-In Detection (Critical for Voice-Only UX)

```kotlin
// During TTS playback, run lightweight VAD on parallel AudioRecord
// If speech detected → stop TTS immediately → start STT pipeline

suspend fun handleBargeIn() {
    // 1. VAD detects speech during TTS playback
    // 2. Stop all TTS engines immediately
    stopSpeaking()
    
    // 3. Abandon audio focus
    abandonAudioFocus()
    
    // 4. Start listening for the interruption
    startListening(scope)
}
```

**Implementation strategy:**
- Run a lightweight VAD thread (Silero, ~5MB) that monitors the microphone even during TTS playback
- VAD threshold for barge-in: 0.6 (higher than normal 0.5 to avoid false triggers from TTS bleed)
- When barge-in detected: immediate TTS stop → flush AudioTrack → start STT
- **Audio echo cancellation**: Use Android's `AcousticEchoCanceler` if available on the device to prevent TTS output from being picked up by the microphone

#### Noise Handling

```
Audio Input
    │
    ▼
┌─────────────────────────────────────┐
│ Noise Gate (pre-VAD)                │
│ - Amplitude threshold: -40dBFS      │
│ - Gate open: 10ms attack            │
│ - Gate close: 100ms release         │
│ - Purpose: reject low-level noise   │
│   before it reaches VAD             │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│ Silero VAD (speech vs non-speech)   │
│ - Threshold: 0.5                    │
│ - Min speech duration: 250ms        │
│ - Min silence duration: 500ms       │
│ - Max speech duration: 30s          │
│ - Internal RNN state (no manual     │
│   state management needed)          │
└──────────────┬──────────────────────┘
               │ speech segments only
               ▼
┌─────────────────────────────────────┐
│ Audio Normalization                 │
│ - Peak normalize to -3dBFS          │
│ - Dynamic range compression         │
│   (ratio 3:1, threshold -20dBFS)   │
│ - Purpose: consistent volume for    │
│   ASR regardless of mic distance    │
└─────────────────────────────────────┘
```

#### Audio Focus Management

```kotlin
// Current implementation (VoicePipeline.kt) handles:
// - AUDIOFOCUS_LOSS: Permanent loss → stop TTS
// - AUDIOFOCUS_LOSS_TRANSIENT: Temporary → pause TTS, set interrupted flag
// - AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: Duck to 20% volume
// - AUDIOFOCUS_GAIN: Resume if was interrupted

// MISSING: Barge-in integration
// When AUDIOFOCUS_LOSS_TRANSIENT happens during TTS,
// AND the cause is the user speaking (VAD active),
// → treat as barge-in, not as external app interruption
```

### 2.4 On-Device Inference Only (No Cloud)

The entire pipeline runs without internet:

| Component | Offline Implementation |
|-----------|----------------------|
| **STT** | Sherpa-ONNX Whisper Tiny INT4 (~40MB ONNX) |
| **TTS** | Sherpa-ONNX Piper (~25MB) or Kokoro (~90MB ONNX) |
| **VAD** | Sherpa-ONNX Silero VAD (~2.5MB ONNX) |
| **LLM** | llama.cpp Qwen 0.5B Q4_K_M (~580MB GGUF) |
| **Intent** | Rule-based pattern matching (no model needed) |
| **Dialect Detection** | On-device lexical + n-gram classifier |
| **Dialect Learning** | On-device LoRA adapter training |
| **Federated Sync** | Queue gradients, sync when WiFi available |

---

## 3. Dialect Learning System

### 3.1 Starting with Swahili

**Phase 1: Swahili Foundation (Day 1)**

The system starts with Swahili as the base language because:
1. Whisper multilingual model has strong Swahili support (trained on Common Voice, Fleurs)
2. Swahili is largely phonetic → character-level TTS works well
3. Swahili is the lingua franca of East Africa — most users speak it
4. Seed vocabulary (`seed_vocabulary_sw.json`) provides 195 market-specific terms

```
Cold Start:
┌─────────────────────────────────────────────────┐
│ Pre-loaded models (bundled in APK):              │
│ - Silero VAD (~2.5MB)                            │
│ - Piper Swahili TTS (~25MB)                      │
│                                                  │
│ Downloaded on first launch (WiFi):               │
│ - Whisper Tiny INT4 (~40MB)                      │
│ - Kokoro Swahili (~90MB) — optional upgrade      │
│                                                  │
│ Pre-seeded data:                                  │
│ - 195 Swahili market terms                       │
│ - ASR error corrections (9 common mistakes)      │
│ - Currency slang (mbao, thao, finje)             │
│ - Measurement units (kilo, debe, gunia)          │
└─────────────────────────────────────────────────┘
```

### 3.2 How the System Learns New Dialects from Worker Corrections

**Learning Loop:**

```
Worker speaks → ASR transcribes → Worker corrects → System learns
     │                                    │
     │                                    │
     ▼                                    ▼
┌──────────────┐                  ┌──────────────────┐
│ Raw ASR:     │                  │ Worker says:      │
│ "nika uza    │                  │ "nimeuza mchele   │
│  mchele      │                  │  kilo mbili"      │
│  kilo mbili" │                  │                   │
└──────┬───────┘                  └────────┬──────────┘
       │                                   │
       └──────────┬────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────┐
│ DialectLearningEngine.processUtterance()             │
│                                                      │
│ 1. ALIGNMENT: Match raw → corrected word-by-word     │
│    "nika uza" → "nimeuza" (pronunciation variant)    │
│                                                      │
│ 2. PATTERN EXTRACTION:                               │
│    - Phoneme substitution: "ika" → "ime" (tense)     │
│    - Vocabulary: all words in corrected transcript   │
│    - N-grams: "mchele kilo mbili" (market pattern)   │
│                                                      │
│ 3. DIALECT PROFILE UPDATE:                           │
│    - Add to pronunciation variants map               │
│    - Increment n-gram counts                         │
│    - Record phoneme confusion matrix                 │
│                                                      │
│ 4. IMMEDIATE ADAPTATION (real-time):                 │
│    - If word seen 3+ times → inject into ASR vocab   │
│    - If n-gram seen 5+ times → boost ASR prior       │
│    - If phoneme sub seen 3+ times → apply mapping    │
│                                                      │
│ 5. THRESHOLD TRIGGERS:                               │
│    - 30 samples → on-device LoRA adaptation          │
│    - 100 samples → gradient ready for federated sync │
└─────────────────────────────────────────────────────┘
```

**Voice-Only Correction Flow:**

Since there's no keyboard, corrections happen through voice:

```
ASR: "nika uza mchele kilo mbili"
System: "Nimesikia: nika uza mchele kilo mbili. Sawa?" (I heard: ... OK?)

Worker: "Hapana. Nimeuza mchele kilo mbili." (No. I sold rice two kilos.)
System: "Sawa. Nimeuza mchele kilo mbili. Nimesahihisha." (OK. Sold rice 2kg. Corrected.)

→ System learns: "nika uza" is a pronunciation of "nimeuza"
→ Pattern stored in dialect profile
→ After 30 such corrections → LoRA fine-tuning triggers
```

### 3.3 How LoRA Adapters Fine-Tune Per Dialect

**Adapter Layering Architecture:**

```
┌─────────────────────────────────────────────────┐
│ Layer 4: User Adapter (on-device, personalized)  │
│   - Learns individual pronunciation patterns     │
│   - Learns personal vocabulary preferences       │
│   - Rank 4-8, ~0.5MB                             │
├─────────────────────────────────────────────────┤
│ Layer 3: Dialect Adapter (shared, downloaded)    │
│   - Sheng, Coastal Swahili, Kikuyu-Swahili, etc.│
│   - Trained from federated aggregation           │
│   - Rank 8, ~1MB per dialect                     │
├─────────────────────────────────────────────────┤
│ Layer 2: Domain Adapter (shared, bundled)        │
│   - Finance/business vocabulary                  │
│   - Market transaction patterns                  │
│   - Rank 8, ~1MB                                 │
├─────────────────────────────────────────────────┤
│ Layer 1: Base Model (frozen)                     │
│   - Whisper Tiny INT4 for ASR                    │
│   - Qwen 0.5B for LLM                           │
│   - Never modified                               │
└─────────────────────────────────────────────────┘
```

**On-Device LoRA Training (Fine-Tuning Pipeline):**

```python
# fine_tuning/__init__.py — adapted for on-device

TrainingConfig:
    base_model: "Qwen/Qwen2.5-0.5B"
    lora_rank: 8          # ~1M trainable params
    lora_alpha: 16
    learning_rate: 2e-4
    batch_size: 1         # 2GB device constraint
    gradient_accumulation: 8  # Effective batch = 8
    max_steps: 100        # ~5 min on Helio G25
    use_mixed_precision: True  # INT8 forward, FP16 backward
    use_gradient_checkpointing: True

DeviceConstraints (2GB phone):
    can_train = (
        ram_mb >= 3000 and         # Need headroom
        storage_free_mb >= 500 and # LoRA weights + checkpoints
        battery_level >= 50 and    # Don't drain battery
        is_charging and            # Only while charging
        is_idle and                # User not actively using app
        2 <= current_hour <= 5     # 2-5 AM window
    )
```

**Training data comes from:**
1. User corrections (weight: 3.0 — highest quality)
2. Confirmed interactions (weight: 1.0)
3. Repeated patterns (weight: proportional to frequency)

### 3.4 How Federated Learning Shares Dialect Knowledge

```
┌──────────┐  ┌──────────┐  ┌──────────┐
│ Device A │  │ Device B │  │ Device C │
│ Nairobi  │  │ Mombasa  │  │ Kisumu   │
│ Sheng    │  │ Coastal  │  │ Dholuo-  │
│ patterns │  │ patterns │  │ Swahili  │
└────┬─────┘  └────┬─────┘  └────┬─────┘
     │              │              │
     │ LoRA Δ       │ LoRA Δ       │ LoRA Δ
     │ (~1MB)       │ (~1MB)       │ (~1MB)
     │              │              │
     ▼              ▼              ▼
┌──────────────────────────────────────────┐
│ Federated Learning Server                 │
│                                           │
│ 1. Receive encrypted gradients (TLS 1.3)  │
│ 2. Clip gradients (L2 norm ≤ 1.0)        │
│ 3. Add DP noise (ε=0.1, δ=10⁻⁵)        │
│ 4. Anomaly detection (z-score > 3σ → flag)│
│ 5. Trimmed mean aggregation               │
│    (remove top/bottom 10%)                │
│ 6. Produce global dialect model update    │
│ 7. Push improved adapter to devices       │
└──────────────────────────────────────────┘
     │
     │ Global dialect adapter update
     ▼
┌──────────┐  ┌──────────┐  ┌──────────┐
│ Device A │  │ Device B │  │ Device C │
│ now has  │  │ now has  │  │ now has  │
│ Coastal  │  │ Sheng +  │  │ Sheng +  │
│ + Sheng  │  │ Coastal  │  │ Coastal  │
│ patterns │  │ patterns │  │ patterns │
└──────────┘  └──────────┘  └──────────┘
```

**Privacy guarantees:**
- Raw audio NEVER leaves the device
- Raw transcripts NEVER leave the device
- Only LoRA weight deltas are transmitted (~1MB)
- Differential privacy: ε=0.1 (strong privacy for financial data)
- Secure aggregation: server cannot see individual contributions
- Byzantine-robust: Krum/TrimmedMean prevents poisoning attacks
- Consent-gated: Level 0 (offline) → no sharing at all

---

## 4. Offline TTS Strategy

### 4.1 Kokoro vs Piper — When to Use Which

```
┌──────────────────────────────────────────────────────────────────────┐
│                     TTS ENGINE SELECTION MATRIX                       │
├──────────────┬──────────────┬──────────────┬────────────────────────┤
│ Engine       │ Size         │ Quality      │ Use When               │
├──────────────┼──────────────┼──────────────┼────────────────────────┤
│ Kokoro       │ ~90MB ONNX   │ ★★★★★       │ 3GB+ devices           │
│ (StyleTTS 2) │              │ Natural,     │ Swahili + English      │
│              │              │ emotion-aware│ Voice personality needed│
├──────────────┼──────────────┼──────────────┼────────────────────────┤
│ Piper        │ ~25MB ONNX   │ ★★★☆☆       │ 2GB devices (default)  │
│ (VITS)       │              │ Clear, fast  │ Fallback for all       │
│              │              │ Slightly     │ Quick responses         │
│              │              │ robotic      │                        │
├──────────────┼──────────────┼──────────────┼────────────────────────┤
│ MMS (Meta)   │ ~65MB/language│ ★★★☆☆      │ Non-Swahili African    │
│ (VITS)       │              │ Moderate     │ 10 languages:          │
│              │              │ quality      │ Yoruba, Hausa, Amharic │
│              │              │              │ Zulu, Igbo, Xhosa...   │
├──────────────┼──────────────┼──────────────┼────────────────────────┤
│ Android TTS  │ ~2MB         │ ★★☆☆☆       │ Last resort fallback   │
│ (built-in)   │              │ Basic,       │ When all ONNX models   │
│              │              │ limited      │ fail to load           │
│              │              │ languages    │                        │
├──────────────┼──────────────┼──────────────┼────────────────────────┤
│ Character-   │ 0MB          │ ★☆☆☆☆       │ Absolute last resort   │
│ level spell  │              │ Spelled-out  │ Unsupported language   │
│              │              │ letters      │ with no TTS model      │
└──────────────┴──────────────┴──────────────┴────────────────────────┘
```

### 4.2 Handling TTS for Dialects Without Pre-Trained Voices

**The Problem:** The system learns Sheng, Kikuyu-Swahili, Dholuo-Swahili, etc. through dialect learning. But there are no pre-trained TTS voices for these dialects. How do you speak dialect-specific words?

**Solution: Dialect-Aware TTS with Fallback Chain**

```
Input text with dialect-specific word: "Niko na mbogi poa"
                                          │        │
                                          │        └── Sheng: "mbogi" (group), "poa" (cool)
                                          └── Standard Swahili

TTS Resolution:
┌─────────────────────────────────────────────────────────┐
│ 1. PRIMARY: Use Kokoro/Piper (Swahili) for the whole    │
│    sentence. Swahili TTS can approximate Sheng words    │
│    because Sheng is phonologically similar.             │
│                                                          │
│ 2. WORD-LEVEL: If a word has a known pronunciation      │
│    variant in the dialect profile, apply it:            │
│    - Check DialectProfile.pronunciationVariants         │
│    - Apply phoneme substitution before TTS              │
│    - Example: "mbogi" → phonemes: m-b-o-g-i            │
│      (Swahili phonemizer handles this fine)             │
│                                                          │
│ 3. CODE-SWITCH: For English words in Sheng:             │
│    - Detect English span via CodeSwitchHandler          │
│    - Synthesize English span with English Piper voice   │
│    - Synthesize Swahili span with Swahili voice         │
│    - Concatenate audio with crossfade (50ms)            │
│                                                          │
│ 4. MMS FALLBACK: For completely new languages:          │
│    - If dialect maps to a supported MMS language,       │
│      download MMS model on-demand (~65MB)               │
│    - Example: Dholuo → could use Luo MMS model          │
│                                                          │
│ 5. CHARACTER-LEVEL: Absolute last resort:               │
│    - Spell out the word letter by letter                │
│    - "mbogi" → "em-bee-oh-gee-eye"                      │
│    - Only for truly unsupported words                   │
└─────────────────────────────────────────────────────────┘
```

### 4.3 Character-Level Fallback for Unsupported Languages

When no TTS model exists for a language (e.g., a newly discovered dialect):

```kotlin
// Fallback chain for unknown dialect words
fun speakDialectWord(word: String, dialect: String, language: String) {
    // 1. Try primary TTS engine (Swahili approximation)
    if (primaryTts.canApproximate(word)) {
        primaryTts.speak(word)
        return
    }
    
    // 2. Check if MMS has this language
    val mmsLang = mapDialectToMmsLanguage(dialect)
    if (mmsLang != null && mmsTts.isLanguageSupported(mmsLang)) {
        mmsTts.speak(word, mmsLang)
        return
    }
    
    // 3. Character-level spelling (last resort)
    val spelled = spellPhonetically(word, language)
    primaryTts.speak(spelled)
}

// Phonetic spelling for Swahili (largely phonetic language)
fun spellPhonetically(word: String, language: String): String {
    // Swahili is phonetic — each letter has one sound
    // Just speak the word normally, the Swahili TTS will
    // produce reasonable approximations
    return word
}
```

---

## 5. Model Bundling Strategy

### 5.1 Fitting Models in 2GB Phone

**APK Bundle (core, never removed):**
```
┌─────────────────────────────────────────────┐
│ BUNDLED IN APK (~30MB)                       │
├─────────────────────────────────────────────┤
│ silero-vad.onnx          │ 0.6 MB           │
│ piper-swahili.onnx       │ 25 MB            │
│ piper-tokens.txt         │ 0.05 MB          │
│ espeak-ng-data/          │ 3 MB             │
│ seed_vocabulary_sw.json  │ 0.02 MB          │
│ dialect_markers.json     │ 0.1 MB           │
├─────────────────────────────────────────────┤
│ Total bundled            │ ~30 MB           │
└─────────────────────────────────────────────┘

After install, app can immediately:
- Listen for speech (VAD)
- Respond in Swahili (Piper TTS)
- Recognize basic commands (rule-based intent)
```

**First Launch Download (WiFi required):**
```
┌─────────────────────────────────────────────┐
│ FIRST_LAUNCH TIER (~130MB)                   │
├─────────────────────────────────────────────┤
│ whisper-tiny-int4 (encoder + decoder + tok)  │
│   encoder: 10 MB                             │
│   decoder: 30 MB                             │
│   tokens: 2.5 MB                             │
│ Total ASR: ~43 MB                            │
├─────────────────────────────────────────────┤
│ kokoro-swahili.onnx + voices + config        │
│   model: 82 MB                               │
│   voices: 5 MB                               │
│   config: 0.01 MB                            │
│ Total TTS upgrade: ~87 MB                    │
├─────────────────────────────────────────────┤
│ Total first launch: ~130 MB                  │
└─────────────────────────────────────────────┘
```

**On-Demand Download (user triggers or WiFi background):**
```
┌─────────────────────────────────────────────┐
│ ON_DEMAND TIER (per-model)                   │
├─────────────────────────────────────────────┤
│ LLM: gemma-4-e2b-q4km     │ 1,500 MB       │
│      (or qwen-3.5-0.8b    │ 580 MB)        │
│ MMS TTS per language:      │ 65 MB each     │
│ WAXAL adapter:             │ 5 MB           │
│ Moonshine Tiny:            │ 40 MB          │
└─────────────────────────────────────────────┘
```

### 5.2 Dynamic Model Loading (Mutual Exclusion)

```
MEMORY STATE MACHINE (2GB device)
═══════════════════════════════════

State: IDLE (listening for wake word)
┌──────────────────────────────────────┐
│ Loaded: VAD (5MB) + Piper (25MB)     │
│ Free: ~914 MB                        │
│ Total used: ~30 MB for ML            │
└──────────────────────────────────────┘
        │
        │ User speaks → VAD detects speech
        ▼
State: STT (transcribing)
┌──────────────────────────────────────┐
│ Action: Unload Piper TTS first       │
│ Action: Load Whisper Tiny (40MB)     │
│ Loaded: VAD (5MB) + Whisper (40MB)   │
│ Free: ~899 MB                        │
│ Total used: ~45 MB for ML            │
└──────────────────────────────────────┘
        │
        │ Transcription complete
        ▼
State: PROCESSING (agent running)
┌──────────────────────────────────────┐
│ Action: Unload Whisper (40MB freed)  │
│ Action: Load LLM if needed (580MB)  │
│   OR: Use rule-based agent (0MB)    │
│ Loaded: VAD (5MB) + LLM (580MB)     │
│ Free: ~359 MB (with LLM)            │
│ Total used: ~585 MB for ML          │
└──────────────────────────────────────┘
        │
        │ Response generated
        ▼
State: TTS (speaking response)
┌──────────────────────────────────────┐
│ Action: Unload LLM (580MB freed)    │
│ Action: Load Kokoro if memory allows │
│   OR: Use Piper (already available)  │
│ Loaded: VAD (5MB) + Piper (25MB)     │
│   OR: VAD (5MB) + Kokoro (90MB)     │
│ Free: ~914 MB (Piper) or ~849 MB    │
└──────────────────────────────────────┘
        │
        │ Speech complete
        ▼
State: IDLE (return to listening)
```

### 5.3 Progressive Download Strategy

```
PHASE 1: INSTALL (offline, via APK)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
✅ VAD (0.6MB) — bundled
✅ Piper TTS (25MB) — bundled
✅ Seed vocabulary — bundled
✅ Rule-based intent matching — bundled

→ App works immediately for basic voice commands
→ "Habari" → "Nzuri! Karibu Msaidizi."
→ "Bei ya mchele?" → "Mchele ni shilingi mia moja hamsini kwa kilo."

PHASE 2: FIRST LAUNCH (WiFi, ~130MB download)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
✅ Whisper Tiny INT4 (43MB) — ASR now works
✅ Kokoro Swahili (87MB) — better TTS quality

→ Full voice interaction enabled
→ Dialect detection begins
→ Learning pipeline activates

PHASE 3: USAGE-TRIGGERED (background, WiFi)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Trigger: User speaks Yoruba phrases detected
→ Download MMS TTS Yoruba (65MB)

Trigger: User asks complex business question
→ Download Qwen 0.5B LLM (580MB)
→ Rule-based agent insufficient

Trigger: User consistently uses Sheng
→ Download Sheng dialect adapter (1MB)
→ Better ASR for Sheng vocabulary

PHASE 4: FEDERATED SYNC (background, WiFi)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Weekly: Upload anonymized gradients
→ Download improved dialect adapters
→ Download improved domain adapters
→ Total download: ~1-5MB per week
```

---

## 6. Wake Word Detection (Always-On)

Since the interface is voice-only with no screen interaction, a wake word is essential.

### 6.1 Implementation Strategy

```
ALWAYS-ON LOW-POWER WAKE WORD DETECTOR
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Option A: Sherpa-ONNX Keyword Spotter (preferred)
┌─────────────────────────────────────────────┐
│ Model: sherpa-onnx keyword spotting          │
│ Size: ~3MB                                   │
│ Power: <1% CPU (runs on DSP if available)    │
│ Wake word: "Msaidizi" (or "Habari")          │
│ False positive rate: <1 per hour             │
│ Latency: <100ms from word end to activation  │
└─────────────────────────────────────────────┘

Option B: VAD + Lightweight Classifier (fallback)
┌─────────────────────────────────────────────┐
│ Silero VAD detects speech onset              │
│ → Capture first 1.5s of audio                │
│ → Run Whisper Tiny on just that snippet      │
│ → Check if transcript contains wake word     │
│ → If yes: activate full pipeline             │
│ → If no: discard, return to listening        │
│                                              │
│ Cost: ~40MB Whisper loaded during check      │
│ Latency: ~500ms (acceptable)                 │
│ Power: higher than Option A but no extra     │
│        model needed                          │
└─────────────────────────────────────────────┘

Option C: DSP-based (hardware-dependent)
┌─────────────────────────────────────────────┐
│ Some MediaTek chips have DSP cores that can  │
│ run keyword spotting at <0.1% battery drain  │
│ → Use Android's SoundTrigger API             │
│ → Hardware-accelerated, near-zero power      │
│ → Only available on ~30% of target devices   │
└─────────────────────────────────────────────┘
```

### 6.2 Voice-Only Activation Flow

```
User: "Msaidizi" (wake word)
    │
    ▼
System: *chime sound* (audio confirmation it's listening)
    │
    ▼
User: "Bei ya tomato ni gapi?" (How much is tomato?)
    │
    ▼
System: "Bei ya tomato ni shilingi themanini kwa kilo." (Tomato is KSh 80/kg)
    │
    ▼
System: *returns to listening for wake word*

OR (continuous conversation mode):

User: "Msaidizi"
System: *chime*
User: "Bei ya tomato?"
System: "Themanini kwa kilo."
User: "Nunua kilo mbili." (Buy 2 kilos)
System: "Sawa. Nimenunua tomato kilo mbili, shilingi mia moja sitini."
         (OK. Bought 2kg tomato, KSh 160.)
User: "Asante."
System: *chime off* (conversation ended, back to wake word)
```

---

## 7. Voice-Only UX Patterns

### 7.1 Conversation State Machine

```
┌─────────┐   wake word    ┌───────────┐
│ SLEEPING│───────────────▶│ LISTENING │
│         │◀───────────────│           │
└─────────┘   timeout 30s  └─────┬─────┘
                                  │ speech detected
                                  ▼
                            ┌───────────┐
                            │ PROCESSING│
                            │           │
                            └─────┬─────┘
                                  │ response ready
                                  ▼
                            ┌───────────┐
                            │ SPEAKING  │
                            │           │
                            └─────┬─────┘
                                  │ speech done
                                  ▼
                            ┌───────────┐
                     ┌──────│ WAITING   │◀─────┐
                     │      │ (for      │      │
                     │      │ follow-up)│      │
                     │      └─────┬─────┘      │
                     │            │             │ follow-up speech
                     │            │ timeout 5s  │ detected
                     │            ▼             │
                     │      ┌───────────┐       │
                     │      │ PROCESSING│───────┘
                     │      └───────────┘
                     │
                     │ no follow-up
                     ▼
               ┌─────────┐
               │ SLEEPING│
               └─────────┘
```

### 7.2 Voice-Only Response Formatting Rules

```
RULES FOR VOICE-ONLY RESPONSES:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. NO SCREEN ARTIFACTS
   ❌ "Check the table below"
   ❌ "See the URL above"
   ❌ "Tap the button"
   ✅ "Kwa muhtasari..." (For the summary...)

2. NUMBERS AS WORDS
   ❌ "KSh 1,250"
   ✅ "Shilingi elfu moja mia mbili hamsini"
   ✅ (or slang) "Elfu moja na mbao" (1000 + 250)

3. SHORT SENTENCES
   ❌ "Your total sales for today across all items including rice, beans, tomatoes, and cooking oil amount to KSh 4,500 with a profit margin of approximately 23%."
   ✅ "Mauzo ya leo ni shilingi elfu nne mia tano. Faida ni asilimia ishirini na tatu."

4. CONFIRMATION QUESTIONS
   After every transaction:
   ✅ "Sawa?" (OK?)
   ✅ "Unataka kuongeza kitu kingine?" (Want to add something else?)
   ✅ "Nimesahihisha?" (Correct?)

5. ERROR RECOVERY
   ❌ "Error: could not process"
   ✅ "Sikusikia vizuri. Tafadhali rudia." (Didn't hear well. Please repeat.)
   ✅ "Samahani, jaribu tena." (Sorry, try again.)

6. MATCH CODE-SWITCHING PATTERN
   If user mixes Swahili-English:
   ✅ "Total ni elfu mbili. Unataka receipt?" (Total is 2000. Want receipt?)
```

---

## 8. Summary of Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Sherpa-ONNX as primary engine** | Optimized ARM NEON paths, 2-3x faster than raw ONNX Runtime. Built-in preprocessing. Active maintenance by k2-fsa team. |
| **Mutual exclusion (Whisper↔Kokoro)** | Both can't fit in 2GB simultaneously. Unload one before loading the other. |
| **Piper as always-available TTS** | Small enough (25MB) to stay loaded even during STT. Provides fallback voice output. |
| **Swahili-first with dialect learning** | Start with what works. Learn everything else from users. Don't require pre-trained models for 100+ dialects. |
| **Voice-only corrections** | No keyboard. User says "hapana" (no) and restates. System compares ASR output vs correction. |
| **LoRA adapters for dialects** | Small (~1MB per dialect). Can layer on frozen base model. Trainable on-device during charging. |
| **Federated learning with DP** | Share knowledge without sharing data. ε=0.1 for financial privacy. TrimmedMean for Byzantine robustness. |
| **Progressive model download** | App works immediately (VAD + Piper). ASR downloads on WiFi. LLM is optional. |
| **Wake word activation** | Voice-only needs always-on listening. Keyword spotter is lightweight (~3MB). |
| **Character-level TTS fallback** | Last resort for unsupported languages. Swahili is phonetic, so this works reasonably well. |
| **Barge-in detection** | Voice-only UX requires interruption support. VAD monitors during TTS playback. |
| **Code-switching TTS** | Blend Swahili + English audio for Sheng speakers. Crossfade at language boundaries. |

---

## 9. Implementation Priority

### Phase 1: Core Voice Loop (Week 1-2)
- [ ] Bundle Silero VAD + Piper TTS in APK
- [ ] Implement wake word detection (Option B: VAD + Whisper snippet)
- [ ] Wire Sherpa-ONNX as primary engine (with legacy fallback)
- [ ] Implement barge-in detection during TTS playback
- [ ] Voice-only response formatting rules

### Phase 2: Dialect Learning (Week 3-4)
- [ ] Wire DialectLearningEngine into VoicePipeline
- [ ] Implement voice-only correction flow ("hapana" → restate)
- [ ] On-device LoRA training during charging
- [ ] Dialect-aware TTS (code-switch audio blending)

### Phase 3: Federated Learning (Week 5-6)
- [ ] Implement offline gradient queue (store locally, upload on WiFi)
- [ ] Backend aggregation server (FedAvg + DP)
- [ ] Progressive dialect adapter download
- [ ] Consent management (voice-based consent explanation)

### Phase 4: Scale to 100+ Dialects (Week 7+)
- [ ] MMS TTS models for additional African languages
- [ ] Community-contributed dialect marker sets
- [ ] Cross-lingual transfer (Bantu family shared adapters)
- [ ] Dialect-specific TTS voice cloning (future)

---

*This architecture enables a $50 phone in rural Kenya to have a voice conversation in Sheng, learn the user's specific pronunciation patterns overnight, and share that knowledge with phones across East Africa — all without ever connecting to the internet during a conversation.*
