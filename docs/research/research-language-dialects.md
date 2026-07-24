# Msaidizi Language & Dialect System — Research Report

**Date:** July 24, 2025  
**Purpose:** Design the adaptive multilingual voice system for Msaidizi (informal African workers)

---

## 1. Current State of African Language AI (July 2025)

### 1.1 Language-by-Language Assessment

#### Swahili (Kiswahili) — BEST COVERAGE, STILL GAPS

**STT (Speech-to-Text):**
- **Whisper (OpenAI):** Multilingual model includes Swahili. Base quality is decent for formal/clean speech but degrades significantly on accented, informal, or noisy speech. The `large-v3` model handles Swahili as one of ~100 languages.
- **Fine-tuned Whisper:** CDLI (Centre for Digital Language Inclusion) has released `whisper-large-v3_finetuned_kenyan_swahili_nonstandard_speech_v1.0` (June 2025) — specifically trained on **non-standard/informal Kenyan Swahili**. This is highly relevant for Msaidizi. 1.5B params, evaluated on speakers with diverse speech patterns.
- **SpeechBrain wav2vec2-dvoice-swahili:** An ASR model using wav2vec2 architecture, trained on Swahili data. Lighter weight option.
- **Multiple community Whisper fine-tunes** on HuggingFace (cdli versions from Oct 2025 onward for both small and large-v3).

**TTS (Text-to-Speech):**
- **MMS TTS (Meta):** Multiple fine-tuned versions available — `swahili-mms-tts-finetuned` (36M params, actively maintained, ~13K downloads). This is the best on-device TTS candidate.
- **SpeechT5 fine-tunes:** Several community models (`speecht5_tts_swahili`, `speecht5_tts_common_voice_swahili`).
- **VITS/F5-TTS:** `multilingual-tts/VITS-OpenBible-Swahili` and F5-TTS variants available (May 2025).
- **Waxal:** `Mwau/waxal_swahili-tts-mms` — 111 downloads, recent (July 2025).

**Verdict:** Swahili has the **best African language AI ecosystem** — workable STT, multiple TTS options, active community. This is the right first language for Msaidizi.

---

#### Sheng (Kenyan Slang) — CRITICAL GAP

- **No dedicated models exist.** Sheng is a mixed-code sociolect combining Swahili, English, and local languages. It's not recognized as a "language" by any major system.
- The CDLI non-standard speech model is the closest thing — it handles "informal" Swahili which includes some Sheng elements.
- **Challenge:** Sheng evolves rapidly (new slang every few months), is spoken not written, and has no standardized orthography.
- **Strategy needed:** Build a Sheng layer on top of Swahili STT — detect code-switching, maintain a dynamic slang dictionary, learn from user corrections.

---

#### Yoruba — MODERATE COVERAGE

**STT:**
- No dedicated ASR models found on HuggingFace for Yoruba speech recognition.
- Whisper includes Yoruba in its multilingual set but quality is mediocre (tonal language challenges).

**TTS:**
- Multiple SpeechT5 fine-tunes: `speecht5_finetuned_google_fleurs_yoruba`, `speecht5_tts_cv_16_1_yoruba`, `speecht5_finetuned_yoruba_500_v3` (community models, 0.1B params).
- Quality is acceptable but not production-grade.

**Challenge:** Yoruba is tonal — tones carry meaning. Most STT systems don't model tones well. TTS needs proper tone rendering.

---

#### Igbo — LOW COVERAGE

**STT:**
- No dedicated ASR models.
- Whisper multilingual baseline only.

**TTS:**
- `aybdee/Igbo-SpeechSynthesis` (0.4B params, May 2025) — the most notable model.
- `Remostart/speecht5_finetuned_igbo` (Oct 2025).
- `nexusbert/speecht5_finetuned_igbo_1` (Oct 2025).
- Very limited ecosystem.

---

#### Hausa — MODERATE COVERAGE (Surprisingly)

**STT:**
- `valacodes/whisper-small-hausa-speech` — fine-tuned Whisper.
- `Baghdad99/saad-speech-recognition-hausa-audio-to-text` (0.2B, 16 likes, 8 downloads) — dedicated Hausa ASR.
- `Tushe/wav2vec2-hausa-speech-better` (94M params, Nov 2025, 5 likes) — wav2vec2 approach.

**TTS:**
- Extensive community work! Multiple SpeechT5 iterations by `Judah04` (SpeechT5-Hausa through SpeechT5-Hausa-9).
- `Asakrg/hausa-text-to-speech-fine-tune` (36M params, Feb 2025).
- `michjosh/speecht5-hausa-tts` (May 2026).
- Active experimentation community.

**Hausa has surprisingly good TTS coverage** due to dedicated community contributors.

---

#### Amharic — LOW-MODERATE COVERAGE

**STT:**
- `speechbrain/asr-wav2vec2-dvoice-amharic` — SpeechBrain model (Feb 2024, 40 likes, 10 downloads). This is the primary ASR option.
- No fine-tuned Whisper specifically for Amharic ASR.
- Multiple emotion detection models (not ASR proper).

**TTS:**
- `AddisuSeteye/speecht5_tts_amharic2` (52 downloads).
- `Walelign/speecht5_tts_amharicspeecht5_tts_amharic` (Jan 2024).
- Limited quality options.

**Challenge:** Amharic uses Ge'ez script — unique writing system adds complexity. Vowel-heavy phonology.

---

#### Oromo — VERY LOW COVERAGE

**STT:**
- No dedicated ASR models found.

**TTS:**
- Only 3 models: `froabera/speecht5_afan_oromo1`, and two `HNDE` Fish Speech variants (Apr-May 2026).
- Extremely nascent ecosystem.

---

#### Zulu — MODERATE COVERAGE (Thanks to South African NLP investment)

**STT:**
- `asr-africa` has released **multiple models** specifically for Zulu:
  - `wav2vec2_xls_r_300m_NCHLT_Speech_corpus_zulu_50hr_v2` (0.3B)
  - `W2V2-Bert_nchlt_speech_corpus_ZULU_20hr_v1` (0.6B)
  - `W2V2-Bert_nchlt_speech_corpus_ZULU_50hr_v1` (0.6B)
  - `W2V2-Bert_nchlt_speech_corpus_Fleurs_ZULU_63hr_v1` (0.6B)
  - `whisper_NCHLT_speech_corpus_Zulu_50hr_v1` (0.2B)
  - `whisper_NCHLT_speech_corpus_Fleurs_Zulu_63hr_v1` (0.2B)
  - `mms-1B_all_NCHLT_speech_corpus_Fleurs_Zulu_63hr_v1` (1.0B)
- The NCHLT Speech Corpus (South African government initiative) provides solid training data.

**TTS:** No dedicated TTS models found.

---

#### Xhosa — LOW-MODERATE COVERAGE

**STT:**
- `asr-africa` models: whisper and mms variants trained on NCHLT + Fleurs data.
- `Alvin-Nahabwe/wav2vec2_xls_r_300m_nchlt_speech_corpus_Fleurs_XHOSA_63hr_v1`

**TTS:** No dedicated TTS models found.

---

### 1.2 The Gap: African vs. European Language AI

| Dimension | European Languages | African Languages |
|---|---|---|
| **Training data** | 10,000+ hours per language | 5-100 hours for most |
| **WER (Word Error Rate)** | 3-8% (English, German) | 20-50%+ for most |
| **TTS quality** | Natural, human-like | Robotic, limited prosody |
| **Dialect support** | Multiple variants modeled | Almost none |
| **On-device models** | Optimized, fast | Barely functional |
| **Community size** | Thousands of contributors | Dozens per language |
| **Commercial backing** | Big Tech invested | Minimal funding |

### 1.3 Why Current Models Are Bad at African Dialects

1. **Data scarcity:** African languages are primarily oral — written corpora are tiny. English has billions of words of text; Swahili has millions at best.

2. **Dialectal variation:** "Swahili" in Mombasa differs significantly from Dar es Salaam Swahili. There's no single "standard" for most African languages.

3. **Code-switching:** African speakers routinely mix 2-4 languages in a single conversation (e.g., Swahili + English + Sheng + Kikuyu). Models trained on "pure" language data fail.

4. **Tonal complexity:** Yoruba, Igbo, Zulu, Xhosa are tonal — pitch changes meaning. Most STT architectures don't model this well.

5. **Informal speech:** Market language, street language, abbreviations, slang — none of this exists in training data.

6. **Noise environments:** African informal work happens in noisy environments (markets, streets, construction). Models trained on clean studio audio fail.

7. **Speaker diversity:** Models are trained on educated, clear speakers. The actual target users (informal workers) have diverse accents, speech patterns, and may have speech impairments.

8. **Writing system issues:** Amharic (Ge'ez), and various orthographic inconsistencies across dialects.

9. **No feedback loops:** Unlike English (where billions of users provide implicit corrections), African language AI has almost no user feedback data.

---

## 2. On-Device Voice Pipeline for African Languages

### 2.1 STT (Speech-to-Text) Options

#### Option A: Whisper (OpenAI) — RECOMMENDED BASE

| Model | Size | RAM | Latency | Swahili Quality |
|---|---|---|---|---|
| `whisper-tiny` | 75MB | ~300MB | ~1s on mobile | Poor |
| `whisper-base` | 142MB | ~500MB | ~2s | Fair |
| `whisper-small` | 466MB | ~1GB | ~4s | Good (with fine-tune) |
| `whisper-medium` | 1.5GB | ~2.5GB | ~8s | Good |
| `whisper-large-v3` | 3GB | ~5GB | ~15s | Best (with fine-tune) |

**Recommendation:** Use `whisper-small` fine-tuned on Kenyan Swahili (CDLI model) as primary. On modern Android phones (4GB+ RAM), this is viable. Fall back to `whisper-tiny` for low-end devices.

**Key advantage:** Whisper handles code-switching better than most models since it's trained on diverse multilingual data.

#### Option B: Vosk — OFFLINE-FIRST

- **No Swahili model available.** Vosk's model list covers English, Chinese, Russian, French, German, Spanish, Portuguese, and some others — but **no African languages**.
- Could potentially build a custom Vosk model using Kaldi pipeline + Swahili data.
- **Lightweight:** Small models are ~50MB, run on Raspberry Pi.
- **Not recommended for MVP** — too much custom work needed.

#### Option C: wav2vec2 / W2V2-Bert (Facebook/Meta)

- `speechbrain/asr-wav2vec2-dvoice-swahili` — available, moderate quality.
- `wav2vec2_xls_r_300m` models for Zulu/Xhosa from asr-africa.
- **Advantage:** Smaller than Whisper, faster inference.
- **Disadvantage:** Needs separate language model for good results, less robust to noise.

#### Option D: NVIDIA Canary — STATUS UNCLEAR

- Canary is an enterprise ASR model. Not openly available for African languages.
- Not a viable option for Msaidizi.

#### Option E: Meta MMS (Massively Multilingual Speech)

- Covers 1,100+ languages including many African ones.
- `mms-1B_all` models available for Zulu, Xhosa.
- **1B params — too large for most mobile devices.**
- Could be used server-side for quality fallback.

#### RECOMMENDED STT STACK:

```
PRIMARY (on-device):
  whisper-small (fine-tuned CDLI Kenyan Swahili)
  └─ ~466MB, ~4s latency, good informal speech handling

FALLBACK (on-device, low-end):
  whisper-tiny (quantized INT8)
  └─ ~40MB after quantization, ~1s latency, basic quality

SERVER (when connected):
  whisper-large-v3 (fine-tuned)
  └─ Best quality, used for active learning / correction
```

---

### 2.2 TTS (Text-to-Speech) Options

#### Option A: MMS TTS (Meta) — RECOMMENDED

- `swahili-mms-tts-finetuned` — 36M params, actively maintained.
- **Small enough for mobile** (~40MB model).
- Quality is "understandable but robotic" — acceptable for functional use.
- Multiple fine-tuned variants available with different voice characteristics.

#### Option B: Piper TTS — EXCELLENT FRAMEWORK

- Fast, lightweight, designed for local/edge deployment.
- **No Swahili voice currently available** in the official repository.
- Would need to train a custom Swahili voice using the Piper training pipeline.
- Best architecture for on-device TTS if we can create the voice model.

#### Option C: SpeechT5 (Microsoft)

- Multiple community fine-tunes for Swahili, Yoruba, Hausa.
- 0.1B params — borderline for mobile.
- Quality varies widely between fine-tunes.

#### Option D: VITS / F5-TTS

- `multilingual-tts/VITS-OpenBible-Swahili` — recent (May 2025), 54 downloads.
- VITS is a strong TTS architecture, good quality.
- F5-TTS is newer, potentially better quality.
- Need to evaluate on-device inference speed.

#### RECOMMENDED TTS STACK:

```
PRIMARY (on-device):
  MMS TTS (fine-tuned Swahili)
  └─ ~40MB, fast inference, acceptable quality

UPGRADE PATH:
  Piper TTS with custom Swahili voice
  └─ Best quality/speed ratio for edge deployment

SERVER (when connected):
  VITS or F5-TTS Swahili
  └─ Higher quality for non-time-critical responses
```

---

### 2.3 Voice Activity Detection (VAD) for Noisy Environments

**Critical requirement:** Workers use Msaidizi in markets, streets, construction sites — extremely noisy.

#### Recommended VAD Solutions:

1. **Silero VAD** — RECOMMENDED
   - Open source, MIT license
   - Tiny model (~2MB), runs on anything
   - Excellent noise robustness
   - Works well with African speech patterns
   - Python, ONNX, and TFLite versions available

2. **WebRTC VAD** (Google)
   - Very lightweight, built into browsers
   - Less robust in extreme noise
   - Good as a first-pass filter

3. **py-webrtcvad + noise gate**
   - Simple energy-based detection
   - Add a noise gate with adaptive threshold
   - Works for moderate noise levels

#### Noise Handling Architecture:

```
Microphone Input
  → Noise Gate (adaptive threshold based on ambient level)
  → Silero VAD (speech detection)
  → Noise Suppression (RNNoise or spectral subtraction)
  → STT (Whisper)
```

**Key insight for African markets:** Background noise in African markets is uniquely challenging — overlapping voices, music, motorbikes, livestock. The VAD needs to be **aggressive** about filtering non-speech audio, but **sensitive** enough to not clip the speaker's voice.

---

## 3. Adaptive Learning Architecture

### 3.1 On-Device Personalization

#### 3.1.1 Speaker Adaptation (Learning the Worker's Voice)

**Approach: Speaker Embedding + LoRA Fine-tuning**

1. **Speaker Enrollment (First 5 minutes):**
   - Worker speaks 10-20 sample phrases
   - Extract speaker embedding (x-vector or ECAPA-TDNN)
   - Store embedding locally

2. **Continuous Adaptation:**
   - After each successful interaction, use the (audio, corrected-text) pair to update a small LoRA adapter.
   - LoRA adapter is ~2-10MB — fits on any phone.
   - Only fine-tune the decoder, not the encoder (faster, less forgetting).

3. **Technical Implementation:**
   ```python
   # Pseudocode for on-device LoRA adaptation
   model = load_whisper_small(base_model)
   lora_adapter = load_or_create_lora(user_id)
   
   # After each confirmed interaction
   if user_confirmed_transcription:
       loss = model(audio, confirmed_text, adapter=lora_adapter)
       loss.backward()
       # Only update LoRA weights (tiny gradient)
       optimizer.step()
       save_lora(lora_adapter, user_id)
   ```

4. **Memory budget:**
   - Base model: ~466MB (frozen)
   - LoRA adapter per user: ~5-10MB
   - Speaker embedding: ~1KB
   - Vocabulary cache: ~100KB
   - Total personalization overhead: ~10MB

#### 3.1.2 Vocabulary Adaptation (Learning Business Terms)

**Approach: Dynamic Vocabulary + N-gram Cache**

1. **Custom Vocabulary List:**
   - Worker defines their business domain (e.g., "mama mboga" = vegetable seller)
   - Pre-loaded domain-specific vocabulary (50-200 words)
   - Grows as worker uses the system

2. **N-gram Correction Cache:**
   ```
   When STT outputs "kuku" but worker corrects to "KUKU" (chicken):
   → Store correction: {"raw": "kuku", "corrected": "KUKU", "context": "selling"}
   → Future: if STT outputs "kuku" in similar context, auto-correct
   ```

3. **Contextual Vocabulary:**
   - Track what words appear together
   - Build personal language model: P(word | context, user)
   - Example: If worker often says "bei ya nyama" (meat price), boost "nyama" in meat-price contexts.

#### 3.1.3 Dialect Adaptation (Learning Specific Dialect)

**Approach: Dialect Classification + Transfer**

1. **Dialect Detection (First Interaction):**
   - Use a dialect classifier (fine-tuned on dialect variations)
   - Assign dialect ID: e.g., `sw-KE-coastal`, `sw-TZ-dar`, `sw-KE-nairobi`
   - Load appropriate base LoRA for that dialect

2. **Dialect-Specific Phoneme Mapping:**
   - Map dialect-specific pronunciations to standard text
   - Example: Coastal Swahili "sh" → standard "s" in some words
   - Store as pronunciation dictionary

3. **Continuous Dialect Learning:**
   - Track phonetic patterns unique to this speaker's dialect
   - Update dialect model incrementally
   - Share anonymized dialect patterns to backend (with consent)

---

### 3.2 Backend Learning (Getting Better for ALL Workers)

#### 3.2.1 Federated Learning for Dialect Improvement

**Architecture:**

```
Worker Device 1  ──┐
Worker Device 2  ──┼──→ Aggregation Server ──→ Updated Global Model
Worker Device 3  ──┘         │
                              ├─→ New Dialect Model
                              ├─→ Updated Vocabulary
                              └─→ Improved Language Model
```

**How it works:**

1. **Local Training:** Each worker's device trains on their personal data (LoRA adapter).
2. **Gradient Upload:** Periodically (when connected to WiFi/data), upload **gradients only** — not raw audio or text.
3. **Secure Aggregation:** Server aggregates gradients from multiple workers.
4. **Model Update:** Updated global model pushed back to devices.

**Privacy guarantees:**
- Raw audio never leaves the device
- Raw text never leaves the device (only gradients)
- Differential privacy noise added to gradients before upload
- Workers can opt out entirely

#### 3.2.2 Aggregation Strategy

```
Phase 1: Language-level aggregation
  All Swahili workers → Swahili base model improvement

Phase 2: Dialect-level aggregation
  All coastal-Swahili workers → Coastal dialect model

Phase 3: Domain-level aggregation
  All vegetable sellers → Market vocabulary model
  All boda-boda riders → Transport vocabulary model
```

**Challenge:** How to aggregate when workers have different dialects?
- Use **clustered federated learning** — group workers by dialect similarity
- Don't average across very different dialects (would hurt both)
- Use **meta-learning** — train a model that can quickly adapt to any dialect

#### 3.2.3 Privacy-Preserving Techniques

1. **Differential Privacy (DP):**
   - Add calibrated noise to gradients before upload
   - ε = 1-10 (privacy budget)
   - Individual worker's data cannot be reconstructed

2. **Secure Multi-Party Computation (SMPC):**
   - Gradients are split into shares
   - No single server sees complete gradient
   - Only aggregated result is revealed

3. **On-Device Processing:**
   - All STT/TTS runs on-device
   - Server only sees anonymized gradients
   - Audio and text stay on device

4. **Consent-Based Sharing:**
   - Workers explicitly opt in to sharing
   - Can share at different levels:
     - Level 0: Nothing (fully private)
     - Level 1: Gradients only (federated learning)
     - Level 2: Anonymized text (for corpus building)
     - Level 3: Audio + text (for maximum improvement)

---

### 3.3 How Models Get Better Over Time

#### 3.3.1 On-Device: LoRA Adapters

**Approach:** Each worker has a personal LoRA adapter that fine-tunes the base model.

```
Base Model (shared, updated periodically)
  + LoRA Adapter (personal, updated after each interaction)
  = Personalized Model
```

**Why LoRA?**
- Full fine-tuning: 466MB model, hours of compute → impossible on mobile
- LoRA: 5-10MB adapter, seconds of compute → perfect for mobile
- Can have multiple adapters: one for speaker, one for dialect, one for domain
- Adapters can be composed: base + speaker + dialect + domain

#### 3.3.2 Continuous Learning Without Forgetting

**Challenge:** If you fine-tune on new data, the model "forgets" old learning.

**Solutions:**

1. **Elastic Weight Consolidation (EWC):**
   - Identify which weights are important for existing knowledge
   - Penalize changes to those weights during new learning
   - Prevents catastrophic forgetting

2. **Replay Buffer:**
   - Keep a small buffer of past examples (100-500 samples)
   - During new training, also train on buffer examples
   - Maintains old knowledge while learning new

3. **Progressive LoRA:**
   - Don't update a single LoRA forever
   - Every N interactions, merge LoRA into base, create new LoRA
   - Periodically retrain from scratch on accumulated data (server-side)

4. **Knowledge Distillation:**
   - Student model (new) learns from Teacher model (old) + new data
   - Preserves old behavior while incorporating new learning

#### 3.3.3 Model Update Cycle

```
Daily (on-device):
  - Update personal LoRA adapter
  - Update vocabulary cache
  - Update n-gram corrections

Weekly (when connected):
  - Upload anonymized gradients
  - Download updated dialect model
  - Download updated vocabulary list

Monthly (server-side):
  - Aggregate all gradients
  - Retrain base model with new data
  - Evaluate on held-out test set
  - Push updated base model to all devices
```

---

## 4. Dialect Discovery & Learning

### 4.1 How to Detect a New Dialect Automatically

**Approach: Acoustic + Lexical Anomaly Detection**

1. **Phonetic Fingerprinting:**
   - Extract acoustic features (MFCCs, pitch contours) from speech
   - Compare against known dialect profiles
   - If distance exceeds threshold → potential new dialect

2. **Lexical Analysis:**
   - Track unknown words (not in any dialect dictionary)
   - Track unusual word combinations
   - Track pronunciation variations of known words

3. **Confidence Monitoring:**
   - If STT confidence drops consistently for a worker → dialect mismatch
   - Low confidence + consistent phonetic patterns = new dialect variant

4. **Clustering:**
   - Cluster workers by acoustic features
   - Workers in same cluster likely share dialect
   - Small clusters (< 10 workers) may be new/undocumented dialects

```
Worker audio → Feature extraction → Compare to dialect DB
                                              │
                          ┌───────────────────┼───────────────────┐
                          │                   │                   │
                     Match found        Close match          No match
                          │                   │                   │
                   Use known dialect    Adapt closest      Flag as potential
                                        dialect model      new dialect
```

### 4.2 Learning a Dialect with Minimal Data

**Approach: Few-Shot Transfer Learning**

1. **Start with closest known dialect:**
   - Find the most similar existing dialect model
   - Use it as the starting point

2. **Rapid Adaptation (5-10 minutes of speech):**
   - Collect ~50 utterances from the new dialect speaker
   - Fine-tune LoRA adapter on these utterances
   - This gives 70-80% accuracy immediately

3. **Active Learning (first week):**
   - Present uncertain transcriptions for confirmation
   - Each confirmation becomes training data
   - Focus on phonemes/words that differ from base dialect

4. **Community Learning:**
   - If multiple speakers share the same new dialect
   - Combine their data (anonymized) for faster learning
   - 5 speakers × 10 minutes = 50 minutes of data → viable model

### 4.3 Transfer Learning from Related Languages

**Language Families for Transfer:**

```
Bantu Languages (East/Southern Africa):
  Swahili ←→ Kikuyu, Luo, Kamba, Luhya, Shona, Zulu, Xhosa
  └─ Shared phonological patterns, some vocabulary overlap

Cushitic Languages (Horn of Africa):
  Oromo ←→ Somali, Afar
  └─ Similar phonological systems

Semitic Languages (Ethiopia):
  Amharic ←→ Tigrinya, Gurage
  └─ Same Ge'ez script family

West African:
  Yoruba ←→ Igbo, Edo (different families but shared context)
  Hausa ←→ Fulfulde (geographic overlap)
```

**Transfer Strategy:**
1. Pre-train on high-resource language (e.g., Swahili)
2. Fine-tune on target language with limited data
3. Use multilingual models (Whisper, MMS) as base — they already share representations

### 4.4 Active Learning — When to Ask for Clarification

**Principles:**
- **Never ask too often** — workers will abandon the app
- **Ask at the right moments** — when uncertainty is high AND the correction would be valuable
- **Make it easy** — voice confirmation, not typing

**When to Ask:**

| Situation | Action |
|---|---|
| STT confidence < 50% | "Did you say X?" (offer top 2 alternatives) |
| STT confidence 50-70% | Show transcription, allow voice correction |
| STT confidence > 70% | Auto-accept, log for later review |
| New word detected | "I heard [word]. Is that right?" |
| Dialect shift detected | "I notice you speak differently. Let me adjust." |

**Voice-Based Correction Flow:**
```
Worker: [speaks in dialect]
Msaidizi: "I heard: 'Bei ya nyama ni mia tano.' Is that correct?"
Worker: "Ndio" (Yes) → confirmed, store as training data
  OR
Worker: "La, bei ya nyama ni mia SITA" (No, price is 600)
  → Store correction pair (audio → "mia tano" → "mia sita")
  → Update LoRA adapter
```

**Smart Asking Strategy:**
- Ask about **domain-specific terms** early (first 10 interactions)
- Ask about **dialect-specific pronunciations** when detected
- Stop asking once confidence stabilizes (>80% accuracy for 50+ interactions)
- Re-engage active learning when new vocabulary is detected

---

## 5. Implementation Recommendations

### 5.1 Phase 1: Kiswahili First (Months 1-3)

**Goal:** Working voice pipeline for Kenyan Swahili speakers.

#### STT Stack:
```
Engine: whisper-small (CDLI fine-tuned for Kenyan Swahili)
Size: ~466MB (can quantize to ~150MB INT8)
Framework: whisper.cpp (C++ implementation, fast, cross-platform)
Runtime: ONNX or CoreML for mobile optimization
```

#### TTS Stack:
```
Engine: MMS TTS (swahili-mms-tts-finetuned)
Size: ~40MB
Framework: Custom lightweight inference engine
Alternative: Piper TTS with custom Swahili voice (if training pipeline available)
```

#### VAD Stack:
```
Engine: Silero VAD
Size: ~2MB
Pre-processing: RNNoise for noise suppression
```

#### Personalization (Minimal Viable):
```
- Speaker enrollment (10 phrases)
- Vocabulary list (pre-loaded market/transport terms)
- Simple correction cache (last 100 corrections)
- NO federated learning yet — just on-device
```

#### Tech Stack:
```
Mobile: React Native or Flutter
Voice Pipeline: Native (C++ via JNI/FFI)
STT: whisper.cpp
TTS: Custom MMS inference
VAD: Silero ONNX
Storage: SQLite for corrections, SharedPreferences for settings
```

#### Languages for Phase 1:
- Kenyan Swahili (primary)
- Sheng detection (basic — flag Sheng utterances, transcribe as Swahili)

---

### 5.2 Phase 2: Major East African Languages (Months 4-6)

**Goal:** Expand to Tanzania, Uganda, Ethiopia.

#### Additional Languages:
1. **Tanzanian Swahili** — dialect adaptation from Kenyan Swahili
2. **Luganda** (Uganda) — new model needed, transfer from Swahili
3. **Amharic** (Ethiopia) — use speechbrain wav2vec2-dvoice-amharic
4. **Oromo** (Ethiopia) — build from scratch using MMS base

#### New Capabilities:
- Dialect detection (Kenyan vs. Tanzanian Swahili)
- LoRA adapter system (per-user personalization)
- Domain vocabulary packs (market, transport, construction)
- Active learning flow (voice-based correction)

#### STT Expansion:
```
Swahili: whisper-small (existing fine-tune)
Amharic: wav2vec2-dvoice-amharic or whisper fine-tune
Oromo: Whisper fine-tuned on community data
Luganda: Whisper fine-tuned on available data
```

#### TTS Expansion:
```
Swahili: MMS TTS (existing)
Amharic: SpeechT5 fine-tune
Oromo: Build from MMS base
Luganda: Build from MMS base
```

---

### 5.3 Phase 3: West & Southern Africa (Months 7-12)

**Goal:** Cover Nigeria, Ghana, South Africa, Kenya's full language landscape.

#### Additional Languages:
1. **Yoruba** (Nigeria) — tonal, needs special STT handling
2. **Hausa** (Nigeria) — existing models available
3. **Igbo** (Nigeria) — build from MMS base
4. **Twi/Akan** (Ghana) — community data available
5. **Zulu** (South Africa) — asr-africa models available
6. **Xhosa** (South Africa) — asr-africa models available

#### Advanced Capabilities:
- Federated learning (gradient aggregation across workers)
- New dialect auto-detection
- Cross-dialect transfer learning
- Domain-specific language models
- Community vocabulary sharing

---

### 5.4 Scaling to 100+ Dialects

**Strategy: The "Base + Adapter" Architecture**

```
                    ┌─────────────────┐
                    │  Multilingual    │
                    │  Base Model      │
                    │  (Whisper/MMS)   │
                    └────────┬────────┘
                             │
            ┌────────────────┼────────────────┐
            │                │                │
     ┌──────▼──────┐  ┌─────▼──────┐  ┌──────▼──────┐
     │ Swahili     │  │ Yoruba     │  │ Hausa       │
     │ Language    │  │ Language   │  │ Language    │
     │ Adapter     │  │ Adapter    │  │ Adapter     │
     └──────┬──────┘  └─────┬──────┘  └──────┬──────┘
            │                │                │
     ┌──────┼──────┐        │                │
     │      │      │        │                │
  ┌──▼──┐┌──▼──┐┌──▼──┐  ┌──▼──┐         ┌──▼──┐
  │Kenya││Tanz.││Coast│  │Lagos│         │Kano │
  │Sw   ││Sw   ││Sw   │  │Yor  │         │Hau  │
  └──┬──┘└──┬──┘└──┬──┘  └──┬──┘         └──┬──┘
     │      │      │        │                │
  ┌──▼──┐┌──▼──┐┌──▼──┐  ┌──▼──┐         ┌──▼──┐
  │User1││User2││User3│  │User4│         │User5│
  │LoRA ││LoRA ││LoRA │  │LoRA │         │LoRA │
  └─────┘└─────┘└─────┘  └─────┘         └─────┘
```

**Each dialect is a small adapter (~5-10MB).** A device only loads:
1. Base model (shared, rarely changes)
2. One language adapter (~10MB)
3. One dialect adapter (~5MB)
4. One personal LoRA (~5MB)

**Total on-device footprint: ~500MB** — fits on any modern phone.

**Adding a new dialect:**
1. Collect ~2 hours of speech data from 5-10 speakers
2. Fine-tune a dialect adapter from the language adapter
3. Test with 3-5 speakers
4. Deploy — all workers in that dialect get the update

**Time to add new dialect: 1-2 weeks** (data collection + training + testing)

---

### 5.5 Complete Tech Stack Summary

| Component | Technology | Size | License |
|---|---|---|---|
| **STT Engine** | whisper.cpp | ~150MB (INT8) | MIT |
| **STT Model (Swahili)** | CDLI whisper-small fine-tune | ~466MB | Apache 2.0 |
| **TTS Engine** | MMS TTS inference | ~40MB | MIT |
| **TTS Model (Swahili)** | swahili-mms-tts-finetuned | ~36MB | Custom |
| **VAD** | Silero VAD | ~2MB | MIT |
| **Noise Reduction** | RNNoise | ~1MB | BSD |
| **Speaker Embedding** | ECAPA-TDNN (SpeechBrain) | ~20MB | MIT |
| **Personalization** | LoRA adapters (PEFT) | ~5-10MB/user | Apache 2.0 |
| **Mobile Framework** | Flutter + FFI to native | — | BSD |
| **On-Device DB** | SQLite / Hive | — | Public Domain |
| **Backend** | FastAPI + PostgreSQL | — | — |
| **FL Framework** | Flower (federated learning) | — | Apache 2.0 |
| **Model Serving** | ONNX Runtime Mobile | — | MIT |

---

## 6. Key Risks & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Swahili STT quality insufficient for market use | Critical | Use CDLI non-standard speech model; invest in noise reduction |
| TTS sounds too robotic for users | High | Use voice cloning from local speakers; prioritize clarity over naturalness |
| Users abandon due to correction fatigue | High | Minimize active learning prompts; auto-accept >70% confidence |
| Federated learning leaks private data | Critical | Use differential privacy; keep audio/text on-device; opt-in only |
| Dialect too different from base model | Medium | Invest in dialect data collection; use transfer learning |
| On-device model too slow on cheap phones | High | Offer tiered model sizes (tiny/small/medium); use INT8 quantization |
| Sheng/code-switching confuses STT | Medium | Build code-switching detector; maintain Sheng vocabulary cache |

---

## 7. Data Collection Strategy

### What Data Do We Need?

| Language | Hours Needed | Sources | Status |
|---|---|---|---|
| Kenyan Swahili | 100+ hours | Market recordings, community partners | CDLI dataset exists |
| Sheng | 50+ hours | Street recordings, social media | Need to collect |
| Tanzanian Swahili | 50+ hours | Community partners, existing corpora | Some exists |
| Amharic | 50+ hours | Market recordings, community partners | dVoice dataset exists |
| Yoruba | 50+ hours | Market recordings, community partners | Need to collect |
| Hausa | 50+ hours | Existing community models + new data | Partial exists |

### Collection Methods:
1. **Community partnerships** — pay local speakers to record in markets
2. **Existing corpora** — Common Voice (Mozilla), OpenBible, Fleurs
3. **Worker recordings** — Msaidizi users opt-in to share audio
4. **Synthetic augmentation** — use TTS to generate training data from text

---

## 8. Conclusion

### The Opportunity
Msaidizi can be the first voice-first app that truly works for African informal workers. The technology is **almost there** — Swahili STT is usable, TTS is functional, and the on-device personalization approach can make it work even when cloud models can't.

### The Key Insight
**Don't try to build perfect models for 100 languages. Build a system that gets better for each individual worker.** The base model gives 70% accuracy. On-device LoRA personalization gets it to 85%. User corrections get it to 95%. That's usable.

### The Path Forward
1. **Month 1-3:** Ship Kenyan Swahili with whisper-small + MMS TTS. Get 100 beta users.
2. **Month 4-6:** Add personalization (LoRA), expand to Tanzania/Ethiopia. Get 1,000 users.
3. **Month 7-12:** Add federated learning, expand to West/Southern Africa. Get 10,000 users.
4. **Year 2:** Scale to 100+ dialects using the adapter architecture. Target 100,000 users.

### The Moat
Every user makes the system better — not just for themselves, but for everyone who speaks their dialect. That's a network effect that no competitor can replicate without the same user base.

---

*Report compiled from HuggingFace model registry, Vosk model database, CDLI research papers, asr-africa project data, and African NLP community resources. July 2025.*
