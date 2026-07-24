# Msaidizi Dialect Scaling Architecture

> **Status:** Design Document  
> **Scope:** Scale from 15 dialect adapters → 100+ African dialects  
> **Constraints:** Voice-first, offline-first, 20-50% WER baseline on African speech  
> **Date:** 2026-07-24

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Base Architecture](#2-base-architecture)
3. [Dialect Discovery Engine](#3-dialect-discovery-engine)
4. [Transfer Learning Framework](#4-transfer-learning-framework)
5. [On-Device Personalization](#5-on-device-personalization)
6. [Scaling Strategy (5 Phases)](#6-scaling-strategy)
7. [Data Pipeline](#7-data-pipeline)
8. [Deployment & Storage Budget](#8-deployment--storage-budget)
9. [Evaluation & Quality Gates](#9-evaluation--quality-gates)
10. [Risks & Mitigations](#10-risks--mitigations)

---

## 1. Problem Statement

### Current State
- **15+ dialect adapters** already coded (Swahili, Sheng, Kikuyu, Dholuo, etc.)
- **Base STT model** fine-tuned on ~2,000 hours of African speech
- **WER:** 20-50% on African dialects (vs. 3-8% for European languages)
- **LoRA adapters** for personalization (~5-10MB per user)

### Target State
- **100+ dialects** with functional STT support
- **WER < 15%** on all Tier-1 dialects, < 25% on Tier-2
- **Sub-2-week** pipeline to onboard a new dialect
- **Offline-first:** All inference runs on-device after initial model download

### Why This Is Hard
1. **Data scarcity:** Most African dialects have < 10 hours of transcribed speech
2. **Orthographic inconsistency:** Many dialects lack standardized spelling
3. **Code-switching:** Real speakers mix 2-4 languages in a single utterance
4. **Dynamic lexicons:** Sheng and similar slang layers evolve monthly
5. **Device constraints:** Phones have 2-4GB RAM; models must be small

---

## 2. Base Architecture

### 2.1 Layered Adapter Stack

```
┌─────────────────────────────────────────────────────────┐
│                   ON-DEVICE INFERENCE                    │
│                                                         │
│  ┌───────────────────────────────────────────────────┐  │
│  │ Personal LoRA (5-10MB)                            │  │
│  │ Speaker voice, vocabulary, micro-dialect          │  │
│  ├───────────────────────────────────────────────────┤  │
│  │ Dialect Adapter (~8-15MB)                         │  │
│  │ Phoneme inventory, tone patterns, phonotactics   │  │
│  ├───────────────────────────────────────────────────┤  │
│  │ Language Adapter (~10-20MB)                       │  │
│  │ Grammar, morphology, base vocabulary              │  │
│  ├───────────────────────────────────────────────────┤  │
│  │ Base STT Model (~150-300MB)                       │  │
│  │ Multilingual acoustic + language model backbone   │  │
│  └───────────────────────────────────────────────────┘  │
│                                                         │
│  Total on-device: ~180-345MB (fits any modern phone)    │
└─────────────────────────────────────────────────────────┘
```

### 2.2 Model Specifications

| Component | Size | Parameters | Train Time | Data Required |
|---|---|---|---|---|
| Base STT Model | 150-300MB | 60-120M | Pre-trained | 10,000+ hours (multilingual) |
| Language Adapter | 10-20MB | 2-5M | 1-3 days | 50-200 hours per language |
| Dialect Adapter | 8-15MB | 1-3M | 3-7 days | 2-20 hours per dialect |
| Personal LoRA | 5-10MB | 0.5-1.5M | 30-60 min on-device | 10-30 min of user speech |

### 2.3 Inference Pipeline

```
Audio Input (16kHz PCM)
    │
    ▼
┌─────────────────┐
│ Voice Activity   │  ── Silence / music / noise rejection
│ Detection (VAD)  │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Dialect Router   │  ── Detects language/dialect from first ~2s of speech
│ (lightweight)    │     Selects which adapters to activate
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Acoustic Encoder │  ── Base model + Language Adapter (always loaded)
│ (shared)         │     Produces frame-level features
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Dialect Decoder  │  ── Dialect Adapter + Personal LoRA
│ (swappable)      │     Language model rescoring
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Post-Processing  │  ── Spell-check, normalization, code-switch tagging
│ & Normalization  │     Punctuation, capitalization
└────────┬────────┘
         │
         ▼
    Text Output (with dialect/confidence metadata)
```

### 2.4 Key Design Decisions

**Decision 1: LoRA over full fine-tuning**
- Full fine-tuning per dialect = 150-300MB × 100 dialects = 15-30GB (impossible on-device)
- LoRA adapters = 8-15MB × 100 dialects = 800MB-1.5GB (feasible with lazy loading)
- User downloads only the dialects they need

**Decision 2: Dialect Router as separate lightweight model**
- Tiny classifier (~1MB) runs first to detect dialect from acoustic features
- Avoids running all adapters simultaneously
- Latency: <50ms on mobile CPU

**Decision 3: Shared base model across all dialects**
- One base model handles acoustic features for all African languages
- Dialect-specific behavior is entirely in the adapter layers
- Reduces storage: only one base model download needed

---

## 3. Dialect Discovery Engine

### 3.1 Automatic Dialect Detection

The Dialect Discovery Engine identifies when a speaker is using a dialect not yet in our catalog.

#### Architecture

```
┌─────────────────────────────────────────────────┐
│            DIALECT DISCOVERY ENGINE              │
│                                                  │
│  ┌──────────────┐    ┌───────────────────────┐  │
│  │ Acoustic      │    │ Linguistic            │  │
│  │ Fingerprinter │    │ Anomaly Detector      │  │
│  │               │    │                       │  │
│  │ • Phoneme     │    │ • OOV rate spike      │  │
│  │   distribution│    │ • Unusual n-grams     │  │
│  │ • Tone contour│    │ • Unknown morphemes   │  │
│  │ • Prosody     │    │ • Novel compounds     │  │
│  │   patterns    │    │                       │  │
│  └──────┬───────┘    └──────────┬────────────┘  │
│         │                       │                │
│         ▼                       ▼                │
│  ┌──────────────────────────────────────────┐   │
│  │ Dialect Similarity Scorer                 │   │
│  │                                           │   │
│  │ Compares against all known dialect        │   │
│  │ embeddings. If best match < threshold:    │   │
│  │ → Flag as "potential new dialect"         │   │
│  │ → Cluster with similar unknown samples    │   │
│  └──────────────────────────────────────────┘   │
│                                                  │
│  ┌──────────────────────────────────────────┐   │
│  │ Dialect Cluster Manager                   │   │
│  │                                           │   │
│  │ Groups flagged utterances by similarity.  │   │
│  │ When cluster reaches ~2 hours of audio    │   │
│  │ from 5+ speakers → triggers human review  │   │
│  │ → initiates adapter training pipeline     │   │
│  └──────────────────────────────────────────┘   │
└─────────────────────────────────────────────────┘
```

#### Detection Signals

| Signal | What It Measures | Confidence Weight |
|---|---|---|
| Phoneme distribution shift | New sounds or missing expected sounds | 0.30 |
| OOV rate (>15% vs. nearest dialect) | Vocabulary divergence | 0.25 |
| Tone contour anomaly | Tonal pattern differences | 0.20 |
| N-gram perplexity spike | Grammar/word order differences | 0.15 |
| Speaker metadata (geography) | Location-based prior | 0.10 |

#### Decision Logic

```python
def detect_dialect(audio_features, text_hypothesis, speaker_location):
    # 1. Get acoustic embedding from base model
    acoustic_emb = base_model.encode(audio_features)
    
    # 2. Compare against all known dialect centroids
    similarities = {
        dialect: cosine_sim(acoustic_emb, centroid)
        for dialect, centroid in known_dialect_centroids.items()
    }
    best_match = max(similarities, key=similarities.get)
    best_score = similarities[best_match]
    
    # 3. Check linguistic signals
    oov_rate = compute_oov_rate(text_hypothesis, best_match)
    perplexity = compute_perplexity(text_hypothesis, best_match)
    
    # 4. Combined decision
    if best_score > 0.85 and oov_rate < 0.08:
        return DialectMatch(best_match, confidence=best_score)
    elif best_score > 0.70:
        return DialectVariant(best_match, confidence=best_score)
        # → Use nearest adapter with adaptation flag
    else:
        return UnknownDialect(acoustic_emb, text_hypothesis, speaker_location)
        # → Submit to dialect cluster manager
```

### 3.2 Code-Switching Detection

Real African speech constantly mixes languages. A Nairobi worker might say:

> "Boss, hii order ya **maize flour** iko **ready**. **Niko** na **delivery** ya **mbili** hivi."

This mixes Sheng, Swahili, and English in a single sentence.

#### Code-Switching Architecture

```
Audio Input
    │
    ▼
┌──────────────────────────────────────────┐
│ Multi-Dialect Encoder                     │
│                                           │
│ Runs base acoustic model once.            │
│ Produces frame-level embeddings that      │
│ are dialect-agnostic.                     │
└──────────────────┬───────────────────────┘
                   │
                   ▼
┌──────────────────────────────────────────┐
│ Code-Switch Boundary Detector             │
│                                           │
│ Per-frame classifier:                     │
│   P(lang_A), P(lang_B), P(lang_C), ...   │
│                                           │
│ Uses:                                     │
│ • Acoustic transition patterns            │
│ • Phoneme inventory shifts                │
│ • Previous context (sliding window)       │
└──────────────────┬───────────────────────┘
                   │
                   ▼
┌──────────────────────────────────────────┐
│ Segment Decoder                           │
│                                           │
│ For each detected segment:                │
│ 1. Activate matching dialect adapter      │
│ 2. Decode with segment-specific LM        │
│ 3. Stitch segments back together          │
│ 4. Apply cross-segment language model     │
│    for coherent output                    │
└──────────────────────────────────────────┘
```

#### Code-Switching Data Augmentation

Since real code-switched data is scarce, we generate synthetic training data:

1. **Corpus mixing:** Take parallel sentences in Language A and Language B, substitute aligned phrases to create natural code-switch points
2. **Template-based:** Define common code-switch patterns (e.g., nouns in English, verbs in Swahili) and generate sentences
3. **Back-translation pivot:** Translate through a third language to create natural mixing points
4. **Crowdsourced prompts:** Ask bilingual speakers to describe scenarios that trigger code-switching

### 3.3 Sheng: Dynamic Slang Layer

Sheng is not a static dialect — it's a living, evolving layer of slang on top of Swahili. New words emerge monthly. The architecture must handle this.

#### Sheng Architecture

```
┌─────────────────────────────────────────────────────┐
│                  SHENG PROCESSING LAYER               │
│                                                       │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────┐ │
│  │ Base Swahili │  │ Sheng Lexicon│  │ Sheng       │ │
│  │ STT          │  │ (dynamic)    │  │ Neologism   │ │
│  │ Adapter      │  │              │  │ Detector    │ │
│  └──────┬──────┘  └──────┬───────┘  └──────┬──────┘ │
│         │                │                  │        │
│         ▼                ▼                  ▼        │
│  ┌──────────────────────────────────────────────┐   │
│  │ Sheng-Aware Decoder                           │   │
│  │                                               │   │
│  │ 1. Decode using base Swahili adapter          │   │
│  │ 2. Check OOV tokens against Sheng lexicon     │   │
│  │ 3. If OOV + high confidence → flag as new     │   │
│  │    Sheng word (potential neologism)            │   │
│  │ 4. Apply Sheng-specific language model         │   │
│  │    (trained on latest Sheng corpus)            │   │
│  └──────────────────────────────────────────────┘   │
│                                                       │
│  ┌──────────────────────────────────────────────┐   │
│  │ Sheng Lexicon Update Pipeline                 │   │
│  │                                               │   │
│  │ • Weekly scrape: Twitter/X, TikTok, urban     │   │
│  │   Kenyan forums for new Sheng terms           │   │
│  │ • Community submissions via SMS/USSD          │   │
│  │ • OOV clustering from production logs         │   │
│  │ • Human review → lexicon update               │   │
│  │ • Push delta updates to devices (~50KB/week)  │   │
│  └──────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

#### Sheng-Specific Challenges

| Challenge | Solution |
|---|---|
| Words change meaning fast ("snoop" = look → stalk → investigate) | Contextual embeddings, frequent LM updates |
| Regional Sheng variants (Eastlands vs. Westlands Nairobi) | Sub-regional Sheng adapters |
| No standardized spelling | Phoneme-based matching + fuzzy search |
| Generational shifts (Gen Z vs. millennial Sheng) | Age-cohort language models |

---

## 4. Transfer Learning Framework

### 4.1 Language Family Taxonomy

African languages form distinct families. Transfer learning exploits structural similarities within families and shared vocabulary across families.

```
                    ┌─────────────────────┐
                    │  BASE MULTILINGUAL   │
                    │  STT MODEL           │
                    │  (all of Africa)     │
                    └──────────┬──────────┘
                               │
            ┌──────────────────┼──────────────────┐
            │                  │                   │
    ┌───────▼───────┐  ┌──────▼──────┐  ┌────────▼────────┐
    │    BANTU       │  │  CUSHITIC    │  │  NILO-SAHARAN   │
    │    FAMILY      │  │  FAMILY      │  │  FAMILY         │
    │                │  │              │  │                 │
    │ • Swahili      │  │ • Somali     │  │ • Dinka         │
    │ • Kikuyu       │  │ • Oromo      │  │ • Luo/Dholuo    │
    │ • Zulu         │  │ • Afar       │  │ • Maasai        │
    │ • Kinyarwanda  │  │ • Sidamo     │  │ • Nuer          │
    │ • Luhya        │  │              │  │ • Kalenjin      │
    │ • Shona        │  │              │  │                 │
    │ • Chichewa     │  │              │  │                 │
    └───────┬───────┘  └──────┬──────┘  └────────┬────────┘
            │                  │                   │
    ┌───────▼───────┐         │                   │
    │ BANTU BRANCHES │         │                   │
    │                │         │                   │
    │ East Bantu:    │         │                   │
    │  Swahili→Kikuyu│        │                   │
    │  →Luhya→Kalenjin│       │                   │
    │                │         │                   │
    │ South Bantu:   │         │                   │
    │  Zulu→Xhosa    │         │                   │
    │  →Shona→Ndebele│        │                   │
    │                │         │                   │
    │ Central Bantu: │         │                   │
    │  Kinyarwanda   │         │                   │
    │  →Kirundi      │         │                   │
    └────────────────┘         │                   │
                               │                   │
                    ┌──────────▼───────────────────▼──┐
                    │  CROSS-FAMILY BRIDGE ADAPTERS    │
                    │                                  │
                    │  Shared loanwords (Arabic,       │
                    │  English, French) across families│
                    │  Trade language patterns         │
                    │  Religious/colonial vocabulary    │
                    └──────────────────────────────────┘
```

### 4.2 Transfer Learning Strategy

#### Level 1: Intra-Family Transfer (Highest Leverage)

When adding a new dialect within an existing language family:

```python
# Example: Adding Kikuyu when Swahili adapter exists
def train_dialect_adapter(new_dialect_data, parent_adapter):
    # 1. Start from parent adapter weights (not random)
    adapter = copy_adapter(parent_adapter)  # e.g., Swahili adapter
    
    # 2. Freeze most layers, fine-tune top layers only
    for layer in adapter.layers[:N-2]:
        layer.freeze()
    
    # 3. Train on new dialect data
    # Need only ~2 hours instead of ~20 hours from scratch
    train(adapter, new_dialect_data, epochs=50)
    
    return adapter
```

**Transfer matrix — estimated WER improvement from parent adapter:**

| New Dialect | Best Parent | Hours Needed (with transfer) | Hours Needed (from scratch) |
|---|---|---|---|
| Kikuyu | Swahili | 2-4 hours | 15-20 hours |
| Luhya | Swahili | 3-5 hours | 15-20 hours |
| Xhosa | Zulu | 2-3 hours | 12-15 hours |
| Oromo | Somali | 3-5 hours | 15-20 hours |
| Kirundi | Kinyarwanda | 1-2 hours | 10-15 hours |
| Dholuo | (Nilo-Saharan, limited transfer) | 8-12 hours | 20-25 hours |
| Kalenjin | (Nilo-Saharan, some Bantu borrowings) | 6-10 hours | 20-25 hours |

#### Level 2: Cross-Family Transfer (Shared Vocabulary)

Many African languages share loanwords from Arabic, English, French, and Portuguese due to trade and colonialism.

```python
class CrossFamilyBridgeAdapter:
    """Handles shared vocabulary across language families."""
    
    def __init__(self):
        self.shared_vocab_embeddings = {}  # Arabic/English/French loanwords
        self.code_switch_patterns = {}     # Common switching patterns
    
    def align_vocabularies(self, source_lang, target_lang):
        """Find cognates and loanwords between unrelated families."""
        # Example: "kompyuta" (computer) exists in Swahili, Sheng, and
        # has similar forms in Yoruba ("kọmputa"), Igbo ("kompụta")
        cognates = find_phonetic_cognates(
            source_lang.vocab, 
            target_lang.vocab,
            threshold=0.7  # phonetic similarity
        )
        return cognates
```

#### Level 3: Zero-Shot Transfer (No Target Data)

For dialects with literally zero data, we use:

1. **Phoneme mapping from related languages** — infer likely phoneme inventory
2. **Grapheme-to-phoneme rules** — if orthography exists, derive pronunciation
3. **Geographic/language-family priors** — if we know it's an East Bantu language, start from Swahili adapter
4. **Bootstrap with synthetic data** — use text-to-speech in related language, apply phonetic transforms

### 4.3 Adapter Composition

When multiple adapters overlap (e.g., user speaks Sheng in Kikuyu accent), we compose them:

```
Final Adapter = α × LanguageAdapter(Swahili) 
              + β × DialectAdapter(Sheng) 
              + γ × PersonalLoRA(Kikuyu_accent)

Where α + β + γ = 1, learned during personalization
```

The composition weights are learned per-user during the personalization phase, not set globally.

---

## 5. On-Device Personalization

### 5.1 Three-Layer Personalization

```
┌────────────────────────────────────────────────────────────┐
│                    PERSONALIZATION LAYERS                    │
│                                                             │
│  Layer 1: SPEAKER ADAPTATION                                │
│  ─────────────────────────                                  │
│  What: Learns the user's voice characteristics              │
│  Input: 5-10 minutes of enrollment speech                   │
│  Output: Voice embedding + acoustic normalization params    │
│  Size: ~2MB                                                 │
│  Update: Every session (incremental)                        │
│                                                             │
│  Layer 2: VOCABULARY ADAPTATION                             │
│  ────────────────────────────                               │
│  What: Learns business-specific terms and names             │
│  Input: User corrections + domain word list                 │
│  Output: Boosted LM probabilities for domain terms          │
│  Size: ~1-3MB                                               │
│  Update: When new terms detected or corrections given       │
│                                                             │
│  Layer 3: DIALECT ADAPTATION                                │
│  ────────────────────────                                   │
│  What: Learns the user's specific dialect features          │
│  Input: 30+ minutes of natural speech across contexts       │
│  Output: Dialect-specific acoustic + LM adjustments         │
│  Size: ~3-5MB                                               │
│  Update: Weekly (batched incremental learning)              │
│                                                             │
└────────────────────────────────────────────────────────────┘
```

### 5.2 Continuous Learning Without Forgetting

The biggest risk in on-device personalization is **catastrophic forgetting** — the model overfits to the user's recent speech and forgets how to handle other speakers or contexts.

#### Elastic Weight Consolidation (EWC) Approach

```python
class OnDevicePersonalizer:
    def __init__(self, base_adapter):
        self.adapter = base_adapter
        self.fisher_matrix = None  # Importance weights from pre-training
        self.replay_buffer = []    # Small buffer of diverse examples
    
    def update(self, new_audio, new_transcript):
        # 1. Compute loss on new data
        new_loss = self.adapter.compute_loss(new_audio, new_transcript)
        
        # 2. EWC penalty — prevent changing important weights too much
        ewc_loss = 0
        for param, fisher, old_param in zip(
            self.adapter.parameters(), 
            self.fisher_matrix, 
            self.reference_params
        ):
            ewc_loss += (fisher * (param - old_param) ** 2).sum()
        
        # 3. Replay buffer loss — remember diverse examples
        replay_loss = 0
        if self.replay_buffer:
            replay_batch = sample(self.replay_buffer, k=8)
            replay_loss = self.adapter.compute_loss(*replay_batch)
        
        # 4. Combined loss
        total_loss = new_loss + λ_ewc * ewc_loss + λ_replay * replay_loss
        total_loss.backward()
        
        # 5. Update replay buffer (reservoir sampling)
        reservoir_update(self.replay_buffer, (new_audio, new_transcript), max_size=500)
```

#### Personalization Safety Rails

| Guard | Purpose | Action if Triggered |
|---|---|---|
| WER regression check | Catch overfitting | Revert to last checkpoint |
| Diversity monitor | Ensure replay buffer stays diverse | Force re-sampling |
| Drift detector | Detect when user's speech patterns shift | Trigger adaptation reset |
| Privacy guard | Ensure no data leaves device | Hard block on any upload attempt |

### 5.3 Enrollment Flow

```
User opens Msaidizi for first time
    │
    ▼
┌──────────────────────────────────┐
│ Step 1: Language Selection        │
│ "What language do you speak?"     │
│ [Swahili] [Kikuyu] [Yoruba] ...  │
│ + "I mix languages" checkbox      │
└────────────────┬─────────────────┘
                 │
                 ▼
┌──────────────────────────────────┐
│ Step 2: Voice Enrollment (5 min) │
│ "Read these sentences aloud"     │
│                                  │
│ Carefully chosen sentences that: │
│ • Cover all phonemes in dialect  │
│ • Include common code-switch pts │
│ • Vary in pace and intonation    │
│ • Include domain-relevant vocab  │
└────────────────┬─────────────────┘
                 │
                 ▼
┌──────────────────────────────────┐
│ Step 3: Domain Selection         │
│ "What's your work about?"        │
│ [Delivery] [Farm] [Shop] [Other] │
│                                  │
│ Loads domain vocabulary adapter  │
└────────────────┬─────────────────┘
                 │
                 ▼
┌──────────────────────────────────┐
│ Step 4: Background Adaptation    │
│ "Great! I'll keep learning your  │
│  voice as we talk. Accuracy will │
│  improve over the next few days."│
│                                  │
│ Runs incremental personalization │
│ in background after each use     │
└──────────────────────────────────┘
```

---

## 6. Scaling Strategy

### Phase Overview

```
Phase    Dialects    Timeline     Focus                    Data Target
─────────────────────────────────────────────────────────────────────────
Phase 1  5           DONE ✓       Kiswahili core           200+ hrs
Phase 2  15→25       Q3-Q4 '26   East African major       50 hrs/dialect
Phase 3  25→50       Q1-Q2 '27   West African             20 hrs/dialect
Phase 4  50→75       Q3-Q4 '27   Pan-African              10 hrs/dialect
Phase 5  75→100+     2028+       Full coverage             5 hrs/dialect
```

### Phase 1: Kiswahili Foundation (DONE)

**Status:** Complete. 15+ adapters coded.

Achievements:
- Base Swahili STT: ~12% WER
- Sheng adapter: ~18% WER
- Kikuyu, Dholuo, Kalenjin adapters: ~22-30% WER
- Personal LoRA pipeline: operational

### Phase 2: Major East African (Q3-Q4 2026)

**Goal:** 25 dialects, all < 20% WER

| Dialect | Family | Data Status | Parent Adapter | Est. WER |
|---|---|---|---|---|
| Kikuyu (improved) | Bantu | 8 hrs collected | Swahili | 15% |
| Dholuo (improved) | Nilo-Saharan | 12 hrs collected | (limited transfer) | 18% |
| Kalenjin (improved) | Nilo-Saharan | 6 hrs collected | (limited transfer) | 20% |
| Kamba | Bantu | 3 hrs collected | Swahili | 18% |
| Meru | Bantu | 2 hrs collected | Kikuyu | 16% |
| Luhya | Bantu | 4 hrs collected | Swahili | 17% |
| Teso | Nilo-Saharan | 1 hr collected | Kalenjin | 22% |
| Maasai | Nilo-Saharan | 0.5 hrs collected | (from scratch) | 28% |
| Embu | Bantu | 1 hr collected | Kikuyu | 18% |
| Mijikenda | Bantu | 2 hrs collected | Swahili | 19% |

**Data collection strategy:**
- Partner with Kenyan universities (UoN, Kenyatta, Moi)
- Community data drives in marketplaces and matatu stages
- USSD-based data collection (record 5 sentences, get airtime reward)
- Target: 50 active contributors per dialect, 1 hour each

### Phase 3: West African Expansion (Q1-Q2 2027)

**Goal:** 50 dialects total, major West African languages online

| Dialect | Family | Data Status | Parent Adapter | Est. WER |
|---|---|---|---|---|
| Yoruba | Volta-Niger | 5 hrs (external datasets) | (new family root) | 20% |
| Igbo | Volta-Niger | 3 hrs (external datasets) | Yoruba | 22% |
| Hausa | Afro-Asiatic | 10 hrs (external datasets) | (new family root) | 18% |
| Twi/Akan | Kwa | 4 hrs (external datasets) | (new family root) | 22% |
| Wolof | Atlantic | 3 hrs (external datasets) | (new family root) | 24% |
| Pidgin English (Naija) | Creole | 8 hrs (external datasets) | English base | 15% |
| Amharic | Semitic | 15 hrs (external datasets) | (new family root) | 16% |
| Tigrinya | Semitic | 5 hrs | Amharic | 18% |

**Strategy:**
- Leverage existing academic datasets (Mozilla Common Voices, OpenSLR, ALFFA)
- West African university partnerships (University of Ibadan, University of Ghana, Bayero University Kano)
- Diaspora community contributions (London, Houston, Paris African communities)

### Phase 4: Pan-African Coverage (Q3-Q4 2027)

**Goal:** 75 dialects, covering all major African language families

Focus areas:
- **Central Africa:** Lingala, Kikongo, Sango, Fang
- **Southern Africa:** Zulu (improved), Xhosa, Tswana, Sotho, Shona, Ndebele
- **Horn of Africa:** Somali (improved), Oromo, Afar, Sidamo
- **Sahel:** Bambara, Fula, Mandinka, Soninke
- **Island:** Malagasy, Comorian, Mauritian Creole

### Phase 5: Full Coverage 100+ (2028+)

**Goal:** 100+ dialects, including minority languages

- Focus on dialects with < 100,000 speakers
- Heavy reliance on transfer learning (minimal data per dialect)
- Community-driven data collection via gamification
- Research partnerships for endangered languages

---

## 7. Data Pipeline

### 7.1 Data Collection Funnel

```
┌─────────────────────────────────────────────────────┐
│                 DATA COLLECTION FUNNEL                │
│                                                       │
│  Source 1: Community Contributors                     │
│  ┌─────────────────────────────────────────────┐     │
│  │ • USSD prompts: "Record 5 sentences"        │     │
│  │ • WhatsApp voice note collection             │     │
│  │ • Marketplace/stage kiosks with mic+tablet   │     │
│  │ • Airtime rewards: 1 min recording = KES 10  │     │
│  │ • Target: 50-100 contributors per dialect    │     │
│  └─────────────────────────────────────────────┘     │
│                                                       │
│  Source 2: Existing Datasets                          │
│  ┌─────────────────────────────────────────────┐     │
│  │ • Mozilla Common Voice (African languages)   │     │
│  │ • OpenSLR African speech corpora             │     │
│  │ • ALFFA (African Languages in the FF Analysis)│    │
│  │ • Bible/Gospel recordings (many languages)   │     │
│  │ • Radio broadcast archives                   │     │
│  └─────────────────────────────────────────────┘     │
│                                                       │
│  Source 3: Passive Collection (Opt-in)                │
│  ┌─────────────────────────────────────────────┐     │
│  │ • After successful STT, ask: "Help improve?" │     │
│  │ • Store anonymized audio + transcript        │     │
│  │ • User controls: view, delete, export        │     │
│  │ • Federated: stays on device unless shared   │     │
│  └─────────────────────────────────────────────┘     │
│                                                       │
│  Source 4: Synthetic Augmentation                     │
│  ┌─────────────────────────────────────────────┐     │
│  │ • TTS → perturbed audio (speed, noise, codec)│     │
│  │ • Code-switching generation (see §3.2)       │     │
│  │ • Phoneme substitution from related langs    │     │
│  │ • Noise augmentation (market, traffic, wind) │     │
│  └─────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────┘
```

### 7.2 Data Quality Pipeline

```
Raw Audio → Transcription
    │
    ▼
┌─────────────────┐
│ Automatic QC     │
│ • SNR check (>10dB)
│ • Duration check (2-30s)
│ • Clipping detection
│ • Duplicate detection (audio fingerprint)
│ • Transcript length vs audio duration ratio
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Linguistic QC    │
│ • Spell-check against dialect lexicon
│ • Grammar plausibility (perplexity check)
│ • Code-switch annotation validation
│ • Speaker ID consistency
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Human Review     │
│ • 10% random sample audited by native speaker
│ • Flagged items (low confidence) reviewed
│ • Adjudication for disagreements
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Clean Dataset    │
│ • Version-controlled (DVC)
│ • Split: train/dev/test (80/10/10)
│ • Metadata: speaker demographics, recording conditions
│ • License tracking per source
└─────────────────┘
```

### 7.3 Dialect Adapter Training Pipeline

```
┌─────────────────────────────────────────────────┐
│           ADAPTER TRAINING PIPELINE              │
│                                                  │
│  Input: Clean dialect dataset (2-20 hrs)         │
│         + Parent adapter (if available)          │
│                                                  │
│  Step 1: Phoneme Inventory Discovery             │
│  ─────────────────────────────────────           │
│  • Run forced alignment with parent adapter      │
│  • Identify new phonemes not in parent           │
│  • Map phoneme inventory for new dialect         │
│                                                  │
│  Step 2: Acoustic Adaptation                     │
│  ────────────────────────────                    │
│  • Initialize from parent adapter weights        │
│  • Fine-tune with LoRA (rank 16-32)             │
│  • EWC regularization to prevent forgetting     │
│  • Train 50-200 epochs (3-7 days on GPU)        │
│                                                  │
│  Step 3: Language Model Adaptation               │
│  ────────────────────────────────                │
│  • Collect text corpus for dialect               │
│  • Train n-gram LM (for fast on-device decode)  │
│  • Fine-tune neural LM adapter                   │
│                                                  │
│  Step 4: Evaluation Gate                         │
│  ─────────────────────                           │
│  • WER on held-out test set                      │
│  • Must meet tier threshold to deploy            │
│  • Per-speaker WER variance check                │
│  • Code-switch test set evaluation               │
│                                                  │
│  Step 5: Packaging                               │
│  ────────────────                                │
│  • Quantize to INT8 (2-4x size reduction)       │
│  • Package as .msaidizi-adapter file             │
│  • Sign with release key                         │
│  • Upload to adapter registry                    │
│                                                  │
│  Output: Signed dialect adapter (8-15MB)         │
└─────────────────────────────────────────────────┘
```

---

## 8. Deployment & Storage Budget

### 8.1 On-Device Storage Model

Users don't download all 100+ dialects. They download what they need.

```
┌─────────────────────────────────────────────────────┐
│              ON-DEVICE STORAGE MODEL                  │
│                                                       │
│  Always Present (required):                           │
│  ├── Base STT Model .................. 150-300MB      │
│  ├── Dialect Router .................. 1MB            │
│  ├── Default Language Adapter ........ 10-20MB        │
│  └── System overhead ................ 10MB            │
│                              Total: ~180-330MB        │
│                                                       │
│  User-Selected (download on demand):                  │
│  ├── Additional Language Adapters .... 10-20MB each   │
│  ├── Dialect Adapters ............... 8-15MB each     │
│  └── Domain Vocabulary Packs ........ 1-5MB each      │
│                              Typical: +20-40MB        │
│                                                       │
│  Personalization (generated on-device):               │
│  ├── Speaker Adaptation ............. 2MB             │
│  ├── Vocabulary Adaptation .......... 1-3MB           │
│  └── Dialect Adaptation ............. 3-5MB           │
│                              Total: ~6-10MB           │
│                                                       │
│  ─────────────────────────────────────────────────── │
│  TOTAL TYPICAL INSTALLATION: 210-380MB                │
│  Fits comfortably on any phone with 2GB+ storage      │
└─────────────────────────────────────────────────────┘
```

### 8.2 Adapter Distribution

```
┌──────────────────────────────────────────────────┐
│            ADAPTER DISTRIBUTION SYSTEM            │
│                                                   │
│  Device registers with Msaidizi Cloud             │
│  │                                                │
│  ▼                                                │
│  Cloud checks:                                    │
│  • Device storage available                       │
│  • User's selected languages                      │
│  • Geographic region (auto-detect nearby dialects)│
│  • Adapter version compatibility                  │
│  │                                                │
│  ▼                                                │
│  Differential download:                           │
│  • Only download changed layers (not full adapter)│
│  • Background download on WiFi                    │
│  • Resume on connection loss                      │
│  • Verify checksums                               │
│  │                                                │
│  ▼                                                │
│  Device activates adapter:                        │
│  • Signature verification                         │
│  • Compatibility check with base model version    │
│  • Hot-swap: no app restart needed                │
│  • Lazy loading: only load when dialect detected  │
└──────────────────────────────────────────────────┘
```

### 8.3 Network-Efficient Updates

| Update Type | Size | Frequency | Delivery |
|---|---|---|---|
| Sheng lexicon delta | 20-50KB | Weekly | Push notification → download |
| Dialect adapter patch | 1-3MB | Monthly | Background on WiFi |
| Full adapter update | 8-15MB | Quarterly | User prompted |
| Base model update | 50-100MB | Bi-annual | Major release, user prompted |
| Personal LoRA | N/A | N/A | Never leaves device |

---

## 9. Evaluation & Quality Gates

### 9.1 Dialect Tier System

Not all dialects can achieve the same quality. We define tiers with explicit quality targets:

| Tier | Speakers | Data Available | WER Target | Examples |
|---|---|---|---|---|
| Tier 1 — Core | >10M | >100 hrs | < 12% | Swahili, Hausa, Yoruba, Amharic |
| Tier 2 — Major | 1-10M | 10-100 hrs | < 18% | Kikuyu, Dholuo, Igbo, Wolof |
| Tier 3 — Regional | 100K-1M | 2-10 hrs | < 25% | Luhya, Kamba, Tigrinya, Twi |
| Tier 4 — Local | 10K-100K | 0.5-2 hrs | < 35% | Embu, Meru, minority dialects |
| Tier 5 — Emerging | <10K | <0.5 hrs | Best effort | Endangered/newly documented |

### 9.2 Evaluation Metrics

```python
class DialectEvaluation:
    metrics = {
        # Primary
        "wer": "Word Error Rate on held-out test set",
        "cer": "Character Error Rate (important for tonal languages)",
        
        # Robustness
        "wer_noisy": "WER with background noise (SNR 5-15dB)",
        "wer_codeswitch": "WER on code-switched utterances",
        "wer_oov": "WER on out-of-vocabulary words",
        "wer_speakers": "WER variance across speakers (fairness)",
        
        # Latency
        "rtf": "Real-time factor (must be < 1.0 on target device)",
        "first_token_latency": "Time to first output token",
        
        # Personalization
        "wer_after_adaptation": "WER after 30 min personalization",
        "adaptation_speed": "Utterances to reach 90% of final accuracy",
        "forgetting_rate": "WER increase on general speech after adaptation",
    }
```

### 9.3 Quality Gate for New Dialect Release

```
┌─────────────────────────────────────────────────────┐
│              DIALECT RELEASE QUALITY GATE            │
│                                                       │
│  Gate 1: Data Quality                                 │
│  ☐ Minimum audio hours met for tier                  │
│  ☐ Speaker diversity: ≥ 5 speakers, ≥ 2 genders      │
│  ☐ Recording quality: avg SNR > 15dB                 │
│  ☐ Transcription agreement: > 95% (if multi-annotator)│
│                                                       │
│  Gate 2: Model Performance                            │
│  ☐ WER on test set ≤ tier threshold                  │
│  ☐ WER variance across speakers < 10% absolute       │
│  ☐ RTF < 0.8 on target device class                  │
│  ☐ No regression on parent language WER              │
│                                                       │
│  Gate 3: User Experience                              │
│  ☐ Code-switch handling tested (if applicable)        │
│  ☐ Domain vocabulary coverage > 80%                  │
│  ☐ Enrollment flow tested with 3+ native speakers    │
│  ☐ Personalization convergence verified              │
│                                                       │
│  Gate 4: Operational                                  │
│  ☐ Adapter size within budget                        │
│  ☐ Download/install flow tested                      │
│  ☐ Rollback procedure documented                     │
│  ☐ Monitoring dashboards configured                  │
│                                                       │
│  ALL GATES PASS → Dialect approved for release        │
└─────────────────────────────────────────────────────┘
```

---

## 10. Risks & Mitigations

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| 1 | Data collection too slow for 100 dialects | High | High | Multi-pronged collection (community, academic, synthetic). Accept lower quality for Tier 4-5. |
| 2 | Transfer learning fails for distant language families | Medium | Medium | Invest in family-specific base models. Cushitic and Nilo-Saharan may need separate base. |
| 3 | Code-switching accuracy remains low | High | Medium | Frame it as "best effort" for now. Prioritize monolingual accuracy first, add CS as enhancement. |
| 4 | On-device personalization causes forgetting | Medium | High | EWC + replay buffer. Aggressive regression testing. Quick rollback to base adapter. |
| 5 | Sheng evolves faster than we can update | High | Low | Accept 2-4 week lag. Community lexicon submissions. Position as "constantly improving." |
| 6 | Orthographic inconsistency causes LM confusion | High | Medium | Phoneme-based decoding as primary. Character-level LM as fallback. Normalize to canonical forms. |
| 7 | Device storage limits (cheap phones = 16GB) | Medium | Medium | Aggressive quantization. Lazy loading. Cloud-assisted decoding for complex utterances. |
| 8 | Cultural/political sensitivity around dialect classification | Medium | High | Community advisory board. Let speakers self-identify. Avoid imposing external classification. |
| 9 | Adapters interfere with each other | Low | High | Extensive cross-adapter testing. Adapter isolation (no shared mutable state). |
| 10 | Regulatory issues with voice data collection | Medium | High | On-device-first design. Explicit consent flows. GDPR/African data protection compliance. |

---

## Appendix A: Adapter File Format

```
.msaidizi-adapter (v2)
├── manifest.json
│   ├── dialect_id: "kikuyu-central-v3"
│   ├── language_family: "bantu"
│   ├── parent_adapter: "swahili-v5"
│   ├── base_model_version: "msaidizi-base-v2.1"
│   ├── created: "2026-07-24T10:00:00Z"
│   ├── tier: 2
│   ├── wer_test: 0.156
│   ├── speakers_in_training: 12
│   ├── audio_hours: 8.5
│   └── signature: "ed25519:..."
├── acoustic_adapter.bin          # LoRA weights for acoustic model
├── language_model.bin            # n-gram LM + neural LM adapter
├── phoneme_inventory.json        # Dialect-specific phoneme set
├── lexicon.txt                   # Pronunciation dictionary
├── normalization_rules.json      # Text normalization rules
└── metadata/
    ├── speaker_demographics.json # Anonymized training speaker info
    ├── evaluation_results.json   # Full eval metrics
    └── changelog.md              # Version history
```

## Appendix B: Language Family Reference

```
AFRO-ASIATIC (North Africa + Horn)
├── Semitic: Amharic, Tigrinya, Arabic dialects
├── Cushitic: Somali, Oromo, Afar, Sidamo, Beja
├── Chadic: Hausa, Margi, Bura
└── Berber: Tashelhit, Kabyle, Tamazight

NILO-SAHARAN (East/Central Africa)
├── Nilotic: Dholuo, Kalenjin, Maasai, Dinka, Nuer, Turkana
├── Saharan: Kanuri, Tebu
├── Central Sudanic: Lugbara, Lendu
└── Songhai: Zarma, Songhai

NIGER-CONGO (West/Central/Southern Africa — includes Bantu)
├── Atlantic: Wolof, Fula, Serer
├── Mande: Bambara, Mandinka, Soninke, Dyula
├── Gur: Mossi, Dagbani
├── Kwa: Twi/Akan, Ewe, Ga
├── Volta-Niger: Yoruba, Igbo, Edo, Fon
├── Benue-Congo → BANTU:
│   ├── East: Swahili, Kikuyu, Kamba, Luhya, Meru, Embu
│   ├── South: Zulu, Xhosa, Shona, Ndebele, Tswana, Sotho
│   ├── Central: Kinyarwanda, Kirundi, Lingala, Kikongo
│   └── West: Fang, Duala
└── Adamawa-Ubangi: Sango, Gbaya

KHOISAN (Southern Africa — click languages)
├── Khoe: Nama, Khoekhoe
├── Tuu: ǂXam (extinct), Taa
└── Kx'a: ǂHoan, Juǀʼhoan

CREOLES & PIDGINS
├── Naija Pidgin (Nigeria)
├── Pidgin English (Cameroon, Ghana)
├── Cape Verdean Creole
├── Mauritian Creole
├── Seychellois Creole
└── Kituba (Congo)

OTHER
├── Malagasy (Austronesian — unique in Africa)
├── Afrikaans (Germanic)
└── Sign languages (various national)
```

## Appendix C: Glossary

| Term | Definition |
|---|---|
| **WER** | Word Error Rate — percentage of words incorrectly transcribed |
| **CER** | Character Error Rate — same but at character level |
| **LoRA** | Low-Rank Adaptation — parameter-efficient fine-tuning method |
| **EWC** | Elastic Weight Consolidation — technique to prevent catastrophic forgetting |
| **OOV** | Out-of-Vocabulary — words not in the model's lexicon |
| **RTF** | Real-Time Factor — ratio of processing time to audio duration (must be < 1.0) |
| **SNR** | Signal-to-Noise Ratio — audio quality measure |
| **VAD** | Voice Activity Detection — detecting speech vs. silence/noise |
| **Adapter** | Small neural network module that specializes a base model |
| **Dialect Router** | Lightweight classifier that detects which dialect is being spoken |
| **Phoneme** | Smallest unit of sound in a language |
| **Code-switching** | Alternating between languages within a conversation or sentence |
| **Forgetting** | Model losing previously learned abilities when trained on new data |

---

*Document version: 1.0 | Last updated: 2026-07-24 | Author: Msaidizi Architecture Team*
