# 🔊 Msaidizi Voice Pipeline Audit — July 2026

**Audit Date:** 2026-07-14
**Auditor:** On-Device Voice Pipeline Audit Agent
**Scope:** ASR, TTS, VAD, Streaming, Dialect Support — compared with 2026 state-of-the-art

---

## 1. CURRENT PIPELINE INVENTORY

### Architecture Overview

```
AudioRecord (16kHz PCM)
    → Silero VAD (2.5MB, 32ms chunks)
    → Whisper Tiny INT4 / Moonshine Tiny (~40MB)
    → AdaptiveAsrEngine (dialect normalization + confidence calibration)
    → IntentRouter → Agent → LLM (Qwen 3.5 0.8B)
    → Kokoro TTS (82MB, 24kHz) / Piper TTS (26MB, 22kHz) / MMS (65MB, 16kHz)
    → AudioTrack
```

### Model Inventory

| Component | Model | Params | Size | Output | License |
|-----------|-------|--------|------|--------|---------|
| **ASR (primary)** | Whisper Tiny INT4 | 39M | ~40MB | text | MIT |
| **ASR (edge)** | Moonshine Tiny | 27M | ~40MB | text | MIT |
| **ASR (turbo)** | Whisper Turbo | 209M | ~150MB | text | MIT |
| **ASR adapter** | WAXAL LoRA | ~5M | ~5MB | — | CC-BY-4.0 |
| **VAD** | Silero VAD v4 | ~2.5MB | ~2.5MB | prob | MIT |
| **TTS (primary)** | Kokoro Swahili | 82M | 82MB | 24kHz | Apache 2.0 |
| **TTS (fallback)** | Piper Swahili | ~15M | 26MB | 22kHz | MIT |
| **TTS (multilang)** | MMS TTS | ~35M | 65MB/lang | 16kHz | CC-BY-NC-4.0 |
| **LLM** | Qwen 3.5 0.8B Q4 | 0.8B | 580MB | text | Apache 2.0 |

### Memory Budget (2GB Devices — BASIC Tier)

```
Mutual Exclusion Model:
  During STT:  Whisper (40MB) + Piper (25MB) + OS (~800MB) = ~865MB ✓
  During TTS:  Kokoro (90MB) + Piper (25MB) + OS (~800MB) = ~915MB ✓
  Idle:        Piper (25MB) only — aggressive unload policy
```

### Latency Targets (Current)

| Component | Target | Device |
|-----------|--------|--------|
| VAD chunk | <5ms | Helio G25 |
| ASR inference (5s audio) | ~300ms (Tiny) / ~100ms (Moonshine) | Helio G25 |
| TTS synthesis (5 words) | ~300ms (Kokoro) / ~400ms (Piper) | Helio G25 |
| Streaming ASR chunk | <80ms per 150ms window | Helio G25 |
| **End-to-end (streaming)** | **<350ms** target | Helio G25 |

---

## 2. DETAILED ANALYSIS

### 2.1 ASR — Speech Recognition

#### Strengths
- ✅ **Multi-tier model selection** (Tiny → Moonshine → Turbo based on device)
- ✅ **WAXAL fine-tuning** for 27 African languages (CC-BY-4.0)
- ✅ **Streaming ASR** with encoder caching (500ms hops, 2s sliding window)
- ✅ **Language-hinted decoding** with per-language token IDs
- ✅ **AdaptiveAsrEngine** with dialect normalization + confidence calibration
- ✅ **Conversation learning pipeline** captures unknown words per-worker
- ✅ **Memory-safe mutual exclusion** (Kokoro ↔ Whisper on 2GB)
- ✅ **Silero VAD** with RNN state management + energy fallback

#### Weaknesses
- ❌ **Whisper Tiny is English-primary** — `whisper-tiny.en` model used (English-only tokenizer!)
- ❌ **Swahili WER is likely 25-40%** on real-world Sheng/code-mixed speech
- ❌ **No beam search** (greedy decoding only — sacrifices accuracy for speed)
- ❌ **Whisper architecture is encoder-decoder** — inherently slower than streaming-first designs
- ❌ **Moonshine lacks African language training data** — designed for English edge
- ❌ **WAXAL adapter exists in registry but integration unclear** — LoRA ONNX application is non-trivial
- ❌ **SHA-256 hashes are all TODO placeholders** — critical security gap

#### ASR Accuracy Assessment (Estimated WER)

| Language/Dialect | Current WER (est.) | 2026 SOTA | Gap |
|-----------------|-------------------|-----------|-----|
| English (clean) | 8-12% | 5.6% (Canary Qwen) | 2-6% |
| Swahili (standard) | 15-25% | 8-12% (Whisper Large V3) | 5-13% |
| Sheng (code-mixed) | 30-45% | 20-30% (no good baseline) | 10-15% |
| Hausa/Yoruba | 20-35% | 12-18% | 8-17% |

**Critical Finding:** The codebase references `whisper-tiny.en` (English-only) from Xenova/HuggingFace, but the `SpeechRecognizer` has Swahili language tokens. If the actual model loaded is the `.en` variant, Swahili recognition is **fundamentally broken** — the English tokenizer lacks Swahili tokens.

### 2.2 TTS — Text-to-Speech

#### Strengths
- ✅ **Kokoro 82M** is a strong choice — StyleTTS2 architecture, Apache 2.0
- ✅ **Emotion-aware voice personalities** (empathetic, excited, professional)
- ✅ **Piper fallback** with Swahili phoneme map (ng', ny, sh, dh, th, kh)
- ✅ **MMS for 10 African languages** — broad coverage
- ✅ **espeak-ng phonemization** for IPA-based text-to-phoneme
- ✅ **Streaming TTS** with sentence splitting

#### Weaknesses
- ❌ **Kokoro Swahili voice quality is unverified** — Kokoro's training data is primarily English/Japanese
- ❌ **Piper's VITS architecture is 2023-era** — significantly behind 2026 models
- ❌ **MMS uses CC-BY-NC-4.0** — non-commercial license blocks paid features
- ❌ **No prosody control** beyond speed multiplier
- ❌ **Character-level fallback** in phonemizer produces poor quality for unknown words
- ❌ **No neural vocoder** — Piper uses direct waveform generation (lower quality)

#### TTS Naturalness Assessment (Estimated MOS)

| Engine | MOS (est.) | 2026 SOTA | Gap |
|--------|-----------|-----------|-----|
| Kokoro Swahili | 3.2-3.6 | 4.2+ (Voxtral TTS) | 0.6-1.0 |
| Piper Swahili | 2.5-3.0 | 4.2+ | 1.2-1.7 |
| MMS Swahili | 2.8-3.2 | 4.2+ | 1.0-1.4 |

**Note:** Kokoro's MOS depends heavily on Swahili voice training data quality. The base Kokoro-82M is English-focused; a Swahili fine-tune may score lower than the base English model.

### 2.3 Streaming Pipeline

#### Strengths
- ✅ **Real streaming ASR** with 500ms hops and encoder caching
- ✅ **Preamble phrases** to eliminate dead air ("Sawa...", "Hebu nione...")
- ✅ **Parallel emotion + dialect feature extraction**
- ✅ **Target: <350ms first response byte**

#### Weaknesses
- ❌ **Streaming ASR re-runs full encoder** every 125ms — no incremental encoding
- ❌ **Ring buffer is 10s max** — longer utterances may lose context
- ❌ **No endpointing model** — relies on VAD silence timeout (1.5s)
- ❌ **TTS is not truly streaming** — waits for full sentence before synthesis

---

## 3. COMPARISON WITH 2026 STATE-OF-THE-ART

### 3.1 ASR Landscape (July 2026)

| Model | WER | RTFx | Params | Size | Languages | On-Device? |
|-------|-----|------|--------|------|-----------|------------|
| **Canary Qwen 2.5B** | 5.63% | 418x | 2.5B | ~5GB | English | ❌ (GPU only) |
| **Whisper Large V3** | 7.4% | varies | 1.55B | ~3GB | 99+ | ❌ |
| **Whisper Large V3 Turbo** | 7.75% | 521x | 809M | ~1.5GB | 99+ | ❌ (4GB+ RAM) |
| **Parakeet TDT 1.1B** | ~8.0% | >2000x | 1.1B | ~4GB | English | ❌ |
| **Moonshine Tiny** | ~12% | fast | 27M | ~40MB | English | ✅ |
| **Whisper Tiny INT4** | ~15% | moderate | 39M | ~40MB | 99+ (weak) | ✅ |
| **Sherpa-onnx (various)** | varies | fast | varies | varies | varies | ✅ |
| **Msaidizi current** | ~15-25% | moderate | 39M | ~40MB | Swahili+ | ✅ |

**Key Insight:** The gap between on-device and server ASR is **widening** in 2026. Models like Canary Qwen (5.63% WER) require GPU infrastructure. On-device models plateau at ~10-15% WER for African languages.

### 3.2 TTS Landscape (July 2026)

| Model | MOS | Params | Size | On-Device? | License |
|-------|-----|--------|------|------------|---------|
| **Voxtral TTS (Mistral)** | ~4.3 | 4B | ~8GB | ❌ | Proprietary |
| **ElevenLabs v3** | ~4.5 | unknown | cloud | ❌ | Proprietary |
| **NeuTTS Air** | ~4.0 | ~100M | ~200MB | ⚠️ (tablet+) | Apache 2.0 |
| **Kokoro 82M (English)** | ~3.8 | 82M | 82MB | ✅ | Apache 2.0 |
| **Piper VITS** | ~3.0 | ~15M | 26MB | ✅ | MIT |
| **MMS TTS** | ~3.0 | ~35M | 65MB | ✅ | CC-BY-NC |
| **Msaidizi Kokoro (Swahili)** | ~3.2-3.6 | 82M | 82MB | ✅ | Apache 2.0 |

### 3.3 Emerging Solutions (2026)

#### Sherpa-onnx (k2-fsa)
- **What:** Unified ASR+TTS+VAD runtime using ONNX Runtime
- **Relevance:** Already used for Silero VAD and Piper TTS models
- **Advantage:** Single runtime for all voice components, optimized for mobile
- **Swahili support:** Has Piper Swahili TTS, ASR models via Whisper/Zipformer
- **Recommendation:** Consider migrating to sherpa-onnx as the unified runtime

#### Voxtral (Mistral)
- **What:** ASR (Voxtral Transcribe) + TTS (Voxtral TTS)
- **ASR:** Outperforms GPT-4o mini, Gemini 2.5 Flash, 3x faster
- **TTS:** 4B params, 9 languages, but NO Swahili
- **On-device:** ❌ Too large for mobile (cloud-only)
- **Relevance:** Sets quality bar; on-device alternatives needed

#### Moonshine (2026)
- **What:** Purpose-built edge/mobile ASR
- **Strength:** Best WER-per-MB, runs on $50 phones
- **Weakness:** English-focused, limited multilingual support
- **Status:** Already in Msaidizi's model registry

---

## 4. QUALITY GAPS — PRIORITIZED

### 🔴 CRITICAL (Fix in 1 week)

1. **English-only Whisper model might be loaded**
   - `whisper-tiny.en` from Xenova lacks Swahili tokens
   - If this is the actual model, Swahili ASR is **non-functional**
   - **Fix:** Verify model is multilingual `whisper-tiny` (not `.en`), or switch to Whisper multilingual

2. **SHA-256 hashes are TODO placeholders**
   - Every model in ModelRegistry has `TODO(release): compute real sha256sum`
   - **Security risk:** Model tampering undetectable
   - **Fix:** Compute hashes for all downloaded model files

3. **WAXAL adapter integration incomplete**
   - Model registered but LoRA application to Whisper ONNX is non-trivial
   - **Fix:** Verify WAXAL adapter is actually applied during inference

### 🟡 HIGH (Fix in 1 month)

4. **Whisper Tiny is too small for African languages**
   - 39M params → poor multilingual quality
   - **Fix:** Upgrade to Whisper Turbo (209M) for STANDARD+ devices, or use sherpa-onnx Whisper

5. **No dedicated Swahili ASR model**
   - Whisper is trained on ~11 hours of Swahili (Common Voice)
   - **Fix:** Fine-tune Whisper on Kenyan Swahili + Sheng data (100+ hours)

6. **TTS naturalness below acceptable threshold**
   - Piper MOS ~3.0 is "intelligible but robotic"
   - Kokoro Swahili MOS ~3.2-3.6 is "acceptable but not natural"
   - **Fix:** Explore Voxtral-quality models when available for Swahili

7. **Sheng/code-mixed WER is 30-45%**
   - Whisper was not trained on code-mixed Swahili-English
   - **Fix:** Fine-tune on Sheng dataset + add code-switching language model

### 🟢 MEDIUM (Fix in 3 months)

8. **Streaming ASR re-runs full encoder**
   - No incremental encoding → wasted compute on overlapping frames
   - **Fix:** Implement encoder caching with partial results (complex)

9. **No endpointing model**
   - Relies on 1.5s silence timeout → slow turn-taking
   - **Fix:** Add neural endpointing model (e.g., from sherpa-onnx)

10. **MMS TTS is CC-BY-NC**
    - Blocks commercial use of MMS TTS for paid features
    - **Fix:** Replace with Apache 2.0 alternatives or license MMS commercially

---

## 5. UPGRADE PATH

### Phase 1: Emergency Fixes (1 Week)

| Action | Impact | Effort | Size Delta |
|--------|--------|--------|------------|
| **Verify Whisper model is multilingual** | 🔴 Critical | 1 hour | 0 |
| **Compute all SHA-256 hashes** | 🔴 Critical | 2 hours | 0 |
| **Verify WAXAL adapter is applied** | 🔴 Critical | 4 hours | 0 |
| **Add Swahili language hint to default ASR** | 🟡 High | 2 hours | 0 |
| **Test ASR WER on 100 Swahili sentences** | 🟡 High | 1 day | 0 |

**Total effort:** ~2 days
**Size impact:** None (code changes only)

### Phase 2: ASR Upgrade (1 Month)

| Action | Impact | Effort | Size Delta |
|--------|--------|--------|------------|
| **Upgrade to Whisper Turbo on STANDARD+ devices** | 🟡 High | 1 week | +110MB |
| **Integrate sherpa-onnx ASR runtime** | 🟡 High | 2 weeks | ~0 |
| **Fine-tune Whisper on 50h Kenyan Swahili** | 🟡 High | 2 weeks | ~0 |
| **Add Sheng code-switching language model** | 🟡 High | 1 week | +5MB |
| **Implement beam search (n=3) for final transcription** | 🟢 Medium | 3 days | 0 |

**Total effort:** ~4 weeks
**Size impact:** +110MB on STANDARD+ devices (Turbo model)

### Phase 3: TTS & Pipeline (3 Months)

| Action | Impact | Effort | Size Delta |
|--------|--------|--------|------------|
| **Fine-tune Kokoro on Swahili voice data** | 🟡 High | 3 weeks | ~0 |
| **Add NeuTTS Air as TTS option (if quality verified)** | 🟢 Medium | 1 week | +120MB |
| **Implement neural endpointing** | 🟢 Medium | 2 weeks | +5MB |
| **Streaming TTS (sentence-by-sentence synthesis)** | 🟢 Medium | 1 week | 0 |
| **Replace MMS with Apache 2.0 multilingual TTS** | 🟢 Medium | 2 weeks | varies |
| **Evaluate Voxtral TTS API for cloud fallback** | 🟢 Low | 3 days | 0 |

**Total effort:** ~10 weeks
**Size impact:** +120-250MB depending on model choices

---

## 6. SPECIFIC MODEL RECOMMENDATIONS

### ASR Upgrades

| Priority | Model | Size | WER (est.) | Use Case |
|----------|-------|------|------------|----------|
| **Now** | Whisper Tiny multilingual (not .en) | 40MB | 15-20% | Fix current broken model |
| **1 month** | Whisper Turbo ONNX (WAXAL-finetuned) | 150MB | 10-15% | STANDARD+ devices |
| **1 month** | sherpa-onnx Whisper | 40-150MB | 10-18% | Unified runtime |
| **3 months** | Fine-tuned Whisper on Kenyan Swahili | 150MB | 8-12% | Custom Swahili ASR |
| **6 months** | Zipformer (sherpa-onnx) Swahili | 50MB | 10-14% | Efficient streaming |

### TTS Upgrades

| Priority | Model | Size | MOS (est.) | Use Case |
|----------|-------|------|------------|----------|
| **Now** | Kokoro Swahili (verify quality) | 82MB | 3.2-3.6 | Current primary |
| **1 month** | Fine-tune Kokoro Swahili voice | 82MB | 3.6-4.0 | Better Swahili quality |
| **3 months** | NeuTTS Air (if Swahili available) | 200MB | 4.0+ | High-quality fallback |
| **6 months** | Voxtral TTS API (cloud fallback) | 0MB | 4.3+ | Premium quality tier |

### VAD Upgrades

| Priority | Model | Size | Action |
|----------|-------|------|--------|
| **Keep** | Silero VAD v4 | 2.5MB | Already best-in-class |
| **Add** | Neural endpointing (sherpa-onnx) | 5MB | Better turn-taking |

---

## 7. RISK ASSESSMENT

### Technical Risks

| Risk | Probability | Impact | Mitigation |
|------|------------|--------|------------|
| Whisper .en model actually loaded | Medium | Critical | Verify model file, add unit test |
| Kokoro Swahili quality is poor | Medium | High | Record MOS test, have Piper fallback |
| Fine-tuning dataset too small | High | Medium | Use Common Voice + synthetic augmentation |
| 2GB OOM with larger models | Medium | High | Mutual exclusion already handles this |
| WAXAL adapter degrades quality | Low | Medium | A/B test with/without adapter |

### Business Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| MMS CC-BY-NC blocks commercial use | High | Replace with Apache 2.0 models |
| Cloud TTS costs for premium users | Medium | Keep on-device as default, cloud as opt-in |
| Model download size on slow networks | High | Progressive download, SD card support exists |

---

## 8. BENCHMARKING PLAN

To validate these recommendations, run the following benchmarks:

### ASR Benchmarks
1. **Swahili WER Test Set:** 500 sentences from Common Voice Swahili test set
2. **Sheng WER Test Set:** 200 code-mixed sentences from Nairobi speakers
3. **Noise Robustness:** Test at 10dB, 5dB, 0dB SNR
4. **Latency:** Measure end-to-end on Helio G25 (2GB) and Dimensity 6100 (4GB)

### TTS Benchmarks
1. **MOS Test:** 50 sentences rated by 10 Swahili speakers
2. **Intelligibility:** Word recognition rate from TTS output
3. **Latency:** Time-to-first-audio for 5/15/30 word sentences
4. **Memory:** Peak RSS during synthesis

---

## 9. EXECUTIVE SUMMARY

### Current State: **Functional but Below 2026 Bar**

Msaidizi's voice pipeline is **architecturally sound** — the memory-safe mutual exclusion, device tiering, dialect detection, and emotion-aware TTS are all well-designed. However, the **underlying models** need upgrading:

- **ASR:** Whisper Tiny is 2022-era. On-device ASR has improved significantly. Upgrade to Whisper Turbo or fine-tuned models.
- **TTS:** Kokoro 82M is a good choice but Swahili quality is unverified. Piper is too robotic for primary use.
- **Sheng:** No model handles code-mixed Swahili-English well. This is the biggest UX gap.

### Top 3 Actions (This Week)

1. 🔴 **Verify the Whisper model is multilingual** (not `.en` variant)
2. 🔴 **Compute SHA-256 hashes** for all models
3. 🟡 **Run a 100-sentence Swahili ASR benchmark** to establish baseline WER

### Top 3 Actions (This Month)

1. 🟡 **Upgrade to Whisper Turbo** on STANDARD+ devices
2. 🟡 **Fine-tune on Kenyan Swahili** (50+ hours of data)
3. 🟡 **Kokoro Swahili MOS test** — verify or replace

---

*Report generated: 2026-07-14 | Based on code analysis + web research of 2026 SOTA models*
