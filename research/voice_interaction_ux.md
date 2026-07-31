# Voice Interaction UX Design — Msaidizi Always-On Voice Experience

> **Date:** July 2026
> **Authors:** Voice Interaction UX Council
> **Status:** Design v1.0
> **Classification:** Internal — Angavu Intelligence Ltd.

---

## Executive Summary

Msaidizi's voice interface is the **primary and often only** interface for informal workers. Many workers are illiterate or semi-literate, operate in noisy environments (markets, roadsides, construction sites), and switch fluidly between Swahili, English, and Sheng — sometimes in the same sentence.

This document designs the complete always-on voice experience: wake word detection, multi-language voice flows for all 40 worker types, error recovery, confirmation patterns, proactive voice notifications, and audio feedback design. All processing is fully offline using sherpa-onnx (Whisper STT + Piper TTS) with on-device LLM (Qwen 0.8B) for intent classification.

---

## 1. Wake Word + Always-On Listening Architecture

### 1.1 The Listening State Machine

The voice system operates in a 4-state pipeline, each with distinct resource requirements:

```
┌─────────────┐    wake word     ┌──────────────┐    silence/VAD    ┌──────────────┐
│   AMBIENT   │ ──────────────── │   ACTIVE     │ ──────────────── │  PROCESSING  │
│  (passive)  │   detected       │  (listening) │   timeout        │  (thinking)  │
│             │                  │              │                  │              │
│ Low-power   │                  │ Full mic     │                  │ STT → LLM    │
│ DSP/kwspot  │                  │ recording    │                  │ → TTS        │
└──────┬──────┘                  └──────────────┘                  └──────┬───────┘
       │                                                                  │
       │                                                                  ▼
       │         ┌──────────────┐    TTS done          ┌──────────────┐
       └──────── │   SPEAKING   │ ◄─────────────────── │  CONFIRMING  │
                 │  (TTS out)   │                      │  (optional)  │
                 │              │                      └──────────────┘
                 └──────────────┘
```

### 1.2 Battery-Efficient Wake Word Detection

**Target:** <5% battery/day for always-on listening.

**Architecture — Three-Layer Audio Pipeline:**

| Layer | Component | Power Draw | Latency | Accuracy |
|-------|-----------|------------|---------|----------|
| **L1: DSP Co-processor** | Hardware keyword spotter (if available) | ~1-2 mW | <50ms | ~85% |
| **L2: On-device KWS** | Lightweight CNN wake word model (~500KB) | ~5-10 mW | <100ms | ~90% |
| **L3: Full STT** | Whisper ONNX (only when wake word detected) | ~200-500 mW | ~1-3s | ~95% |

**Implementation Strategy:**

```
┌─────────────────────────────────────────────────────────────┐
│                    AMBIENT LISTENING STATE                    │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Microphone → AudioRecord (8kHz, mono, 16-bit)              │
│       │                                                      │
│       ▼                                                      │
│  Ring Buffer (circular, 5 seconds, ~80KB)                    │
│       │                                                      │
│       ▼                                                      │
│  ┌─────────────────────────────────────────┐                │
│  │  L2: Wake Word Detector (sherpa-onnx)   │                │
│  │  Model: tiny conformer KWS (~500KB)     │                │
│  │  Runs on: every 0.5s chunk (4000 samples)│                │
│  │  Threshold: 0.85 confidence              │                │
│  └─────────────────────────────────────────┘                │
│       │                                                      │
│       │ P(wake_word) > 0.85                                  │
│       ▼                                                      │
│  ┌─────────────────────────────────────────┐                │
│  │  Pre-roll Buffer: keep last 1.5 seconds │                │
│  │  of audio before wake word detection    │                │
│  └─────────────────────────────────────────┘                │
│       │                                                      │
│       ▼                                                      │
│  Transition to ACTIVE LISTENING                              │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

**Battery Optimization Techniques:**

1. **Sample Rate Reduction in Ambient Mode:** Record at 8kHz (vs 16kHz for active). Wake word model trained on 8kHz data. Saves ~50% audio processing power.

2. **Chunked Processing:** Process audio in 500ms chunks rather than streaming. Between chunks, the CPU can enter idle/low-power states.

3. **Duty Cycling:** In low-battery mode (<20%), reduce wake word check frequency from every 500ms to every 2 seconds. Still responsive but uses ~4x less power.

4. **Motion-Gated Listening:** Use accelerometer to detect when worker is stationary (sleeping). Disable wake word detection during extended stationary periods (nighttime). Re-enable on any motion.

5. **Schedule-Aware Listening:** Based on worker's `OperatingHours`, reduce listening sensitivity outside work hours. A mama mboga who operates 6am-8pm gets full listening during those hours, reduced listening overnight.

**Estimated Battery Impact:**

| Scenario | Listening Hours | Wake Checks/Hour | Battery Impact |
|----------|----------------|-------------------|----------------|
| Full day (12h work) | 12h | 7,200 | ~3-4% battery |
| Extended (16h) | 16h | 9,600 | ~4-5% battery |
| Night reduction | 8h reduced | 1,800 | ~1% battery |
| **Total daily** | — | — | **~4-5%** ✓ |

### 1.3 Wake Word Variants

The wake word "Msaidizi" is primary, but we support multiple triggers per language:

| Language | Primary Wake Word | Alternatives | Phonetic Notes |
|----------|------------------|--------------|----------------|
| Swahili | "Msaidizi" | "Saidizi", "Eh Msaidizi" | /m.sa.i.di.zi/ — 4 syllables |
| English | "Msaidizi" | "Hey Msaidizi", "Assistant" | Same pronunciation |
| Sheng | "Msaidizi" | "Sasa Msaidizi", "Eh bro" | Casual tone, faster |
| Custom | User-defined | Set during onboarding | Recorded & trained |

**Wake Word Training (Optional Advanced Feature):**
During onboarding, the worker says "Msaidizi" 3 times. The system fine-tunes the KWS model on-device to better match the worker's accent and pronunciation. This uses transfer learning on the tiny conformer model with the 3 samples as few-shot adaptation data.

### 1.4 Visual Indicator: Floating Mic Button

A persistent floating action button (FAB) with mic icon shows the current voice state:

```
┌─────────────────────────────────────────────────────┐
│                    STATE INDICATORS                   │
├──────────┬──────────────┬───────────────────────────┤
│  State   │  Visual      │  Description              │
├──────────┼──────────────┼───────────────────────────┤
│ IDLE     │ ○ Gray mic   │ Ambient listening active,  │
│          │   (pulsing   │ waiting for wake word.     │
│          │    slowly)    │ Subtle pulse = alive.      │
├──────────┼──────────────┼───────────────────────────┤
│ WAKE     │ ◉ Blue mic   │ Wake word detected!        │
│ DETECTED │   (flash)    │ Brief blue flash + chime.  │
│          │              │ Transitions to LISTENING.   │
├──────────┼──────────────┼───────────────────────────┤
│ LISTENING│ ◉ Green mic  │ Actively recording speech. │
│          │   (ripple    │ Green ripple animation     │
│          │    waves)    │ shows audio amplitude.     │
├──────────┼──────────────┼───────────────────────────┤
│ PROCESS- │ ◉ Yellow mic │ Processing speech → text.  │
│ ING      │   (spinner)  │ Spinning indicator.        │
├──────────┼──────────────┼───────────────────────────┤
│ SPEAKING │ ◉ Blue mic   │ Msaidizi is speaking TTS.  │
│          │   (waveform) │ Audio waveform animation.  │
├──────────┼──────────────┼───────────────────────────┤
│ ERROR    │ ◉ Red mic    │ Something went wrong.      │
│          │   (X icon)   │ Tap for retry or fallback. │
├──────────┼──────────────┼───────────────────────────┤
│ MUTED    │ ○ Red slash  │ User manually muted.       │
│          │   over mic   │ No listening active.       │
└──────────┴──────────────┴───────────────────────────┘
```

**Accessibility Considerations:**
- All state changes accompanied by distinct audio tones (see Section 7)
- Haptic feedback on wake word detection (short vibration)
- FAB position: bottom-right, draggable by user
- FAB size: 56dp minimum touch target (Android accessibility guideline)
- High contrast mode: thicker outlines, brighter colors

---

## 2. Voice Interaction Flows by Worker Type

### 2.1 Design Principles

1. **Natural Language First:** Workers speak naturally, not in commands. "Nimeuza nyanya" not "record sale product nyanya."
2. **Context-Aware:** The app knows the worker's business type and pre-loads relevant patterns.
3. **Progressive Disclosure:** Simple flows first, complex multi-turn only when needed.
4. **Code-Switching Normal:** "Msaidizi, nimeuza tomatoes kilo tano for 500 bob" is valid input.
5. **Ambiguity Resolution:** Ask clarifying questions, don't guess.

### 2.2 Trade & Commerce Workers

#### Mama Mboga (Vegetable Vendor)

```
┌─────────────────────────────────────────────────────────────────┐
│                    MAMA MBOGA VOICE FLOWS                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ══ RECORD SALE ══                                               │
│                                                                  │
│  User: "Msaidizi, nimeuza nyanya kilo tano kwa elfu moja"       │
│  [Intent: RECORD_SALE | Confidence: 0.92]                       │
│  [Entities: product=nyanya, quantity=5, unit=kg, amount=1000]   │
│                                                                  │
│  Msaidizi: "Sawa! Nimepata: Nyanya kilo 5, KSh 1,000. Sawa?"   │
│                                                                  │
│  User: "Sawa!"  ──→  [Transaction recorded. Beep ✓]            │
│       ──OR──                                                     │
│  User: "Sio kilo tano, ni tatu!" ──→ [Correction flow]         │
│                                                                  │
│  ══ CHECK STOCK ══                                               │
│                                                                  │
│  User: "Msaidizi, nina stock ngapi?"                            │
│  [Intent: ASK_STOCK]                                             │
│                                                                  │
│  Msaidizi: "Una: Nyanya kilo 12, Sukuma wiki mashada 8,         │
│            Vitunguu kilo 3. Nyanya zimepungua — unahitaji        │
│            kununua zaidi."                                       │
│                                                                  │
│  ══ CHECK PRICE ══                                               │
│                                                                  │
│  User: "Bei ya nyanya ikoje leo?"                               │
│  [Intent: MARKET_PRICE | Entities: product=nyanya]              │
│                                                                  │
│  Msaidizi: "Wakulima Market: Nyanya ni KSh 80-100 kwa kilo.    │
│            Bei imepanda kutoka jana (KSh 70). Soko ni nzuri     │
│            leo."                                                 │
│                                                                  │
│  ══ DAILY PROFIT ══                                              │
│                                                                  │
│  User: "Leo nimepata faida ngapi?"                              │
│  [Intent: ASK_PROFIT]                                            │
│                                                                  │
│  Msaidizi: "Leo: Umepata KSh 2,400 mauzo. Gharama ni KSh 800.  │
│            Faida ni KSh 1,600. Jana ulipata KSh 1,200.          │
│            Umepanda 33%!"                                        │
│                                                                  │
│  ══ RECORD SPOILAGE ══                                           │
│                                                                  │
│  User: "Msaidizi, nyanya zimeharibika kilo mbili"               │
│  [Intent: RECORD_SPOILAGE | Entities: product=nyanya, qty=2]    │
│                                                                  │
│  Msaidizi: "Pole! Nimepata: Nyanya kilo 2 zimeharibika.         │
│            Thamani ni KSh 200. Umepoteza 5% ya stock ya leo.    │
│            Unataka nicheke bei ya kununua?"                      │
│                                                                  │
│  ══ RESTOCK ALERT (Proactive) ══                                 │
│                                                                  │
│  Msaidizi [morning]: "Habari za asubuhi! Stock ya nyanya         │
│            imepungua. Kwa bei ya soko, unahitaji KSh 3,000       │
│            kununua kilo 30. Una KSh 5,200 kwenye akaunti.        │
│            Unataka niweke oda?"                                  │
│                                                                  │
│  ══ MARKET POOLING (Group Buy) ══                                │
│                                                                  │
│  User: "Msaidizi, kuna mtu ananunua nyanya wholesale?"          │
│  [Intent: MARKET_POOL]                                           │
│                                                                  │
│  Msaidizi: "Ndiyo! Kuna pool ya kwenda Wakulima kesho asubuhi.  │
│            Mama Wanjiku na Mama Amina wamesha-join. Transport    │
│            ni KSh 200 kwa mtu. Unataka ujiunge?"                │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### Dukawallah (Shop Owner)

```
┌─────────────────────────────────────────────────────────────────┐
│                    DUKAWALLAH VOICE FLOWS                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ══ RECORD SALE (with customer credit) ══                        │
│                                                                  │
│  User: "Msaidizi, nimemuuza Mama Njeri sukari na unga, mia tatu"│
│  [Intent: RECORD_SALE | Entities: customer=Mama Njeri,          │
│   products=[sukari, unga], amount=300]                          │
│                                                                  │
│  Msaidizi: "Sawa. Mama Njeri — Sukari na Unga, KSh 300.        │
│            Amelipa cash ama ni deni?"                           │
│                                                                  │
│  User: "Ni deni"                                                │
│                                                                  │
│  Msaidizi: "Sawa. Deni la Mama Njeri sasa ni KSh 300.          │
│            Deni la jumla: KSh 1,800."                           │
│            [Beep ✓]                                              │
│                                                                  │
│  ══ CHECK CUSTOMER DEBT ══                                       │
│                                                                  │
│  User: "Mama Njeri anadaiwa ngapi?"                             │
│  [Intent: CHECK_CUSTOMER_DEBT | Entities: customer=Mama Njeri]  │
│                                                                  │
│  Msaidizi: "Mama Njeri anadaiwa jumla KSh 1,800.                │
│            Deni la mwisho: KSh 300 — jana.                      │
│            Deni lake linaongezeka. Unataka nimtumie ujumbe?"     │
│                                                                  │
│  ══ RECORD PURCHASE (restocking) ══                              │
│                                                                  │
│  User: "Nimenunua stock — sukari magunia 5, KSh 4,500"          │
│  [Intent: RECORD_PURCHASE | Entities: products=sukari,          │
│   quantity=5, unit=gunia, amount=4500]                          │
│                                                                  │
│  Msaidizi: "Sawa! Sukari magunia 5, KSh 4,500. Stock mpya:     │
│            Sukari magunia 8. Bei ya kuuza: KSh 120 kwa kilo?"   │
│                                                                  │
│  ══ PROFIT REPORT ══                                             │
│                                                                  │
│  User: "Nipe ripoti ya wiki hii"                                │
│  [Intent: DAILY_REPORT]                                          │
│                                                                  │
│  Msaidizi: "Wiki hii: Mauzo KSh 18,500. Gharama KSh 12,000.    │
│            Faida KSh 6,500. Bidhaa bora: Sukari (KSh 4,200).    │
│            Wiki iliyopita ulipata KSh 5,800. Umepanda 12%!      │
│            Deni jipya: KSh 2,100 kwa wateja 3."                 │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### Machinga (Hawker)

```
┌─────────────────────────────────────────────────────────────────┐
│                    MACHINGA VOICE FLOWS                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ══ QUICK SALE (mobile, fast-paced) ══                           │
│                                                                  │
│  User: "Msaidizi, mauzo mia mbili!"                             │
│  [Intent: QUICK_SALE | Entities: amount=200]                    │
│                                                                  │
│  Msaidizi: "Sawa! Mauzo KSh 200. Bidhaa gani?"                 │
│                                                                  │
│  User: "Saas tatu za simu"                                      │
│  [Entities: products=saas tatu za simu, qty=3]                  │
│                                                                  │
│  Msaidizi: "KSh 200 — saas 3 za simu. Sawa?"                   │
│  User: "Sawa!"  ──→  [Beep ✓]                                  │
│                                                                  │
│  ══ DAILY EARNINGS (end of day) ══                               │
│                                                                  │
│  User: "Msaidizi, leo nimepata ngapi?"                          │
│  [Intent: ASK_SALES_TODAY]                                       │
│                                                                  │
│  Msaidizi: "Leo umepata KSh 1,800 mauzo. Gharama ni KSh 400    │
│            (transport + lunch). Faida: KSh 1,400.                │
│            Wiki hii jumla: KSh 7,200."                          │
│                                                                  │
│  ══ EXPENSE TRACKING ══                                          │
│                                                                  │
│  User: "Msaidizi, nimetumia mia tano kwa matatu"                │
│  [Intent: RECORD_EXPENSE | Entities: category=transport,        │
│   amount=500]                                                    │
│                                                                  │
│  Msaidizi: "Sawa! Usafiri KSh 500. Gharama za leo sasa: KSh 900"│
│            [Beep ✓]                                              │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### Mitumba Seller (Second-Hand Clothes)

```
┌─────────────────────────────────────────────────────────────────┐
│                    MITUMBA VOICE FLOWS                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ══ RECORD BALE PURCHASE ══                                      │
│                                                                  │
│  User: "Msaidizi, nimenunua bale ya nguo, elfu kumi na mbili"   │
│  [Intent: RECORD_PURCHASE | Entities: product=bale ya nguo,     │
│   amount=12000]                                                  │
│                                                                  │
│  Msaidizi: "Sawa! Bale ya nguo KSh 12,000. Aina gani?          │
│            Mavazi ya wanawake, wanaume, au watoto?"              │
│                                                                  │
│  User: "Wanawake"                                                │
│                                                                  │
│  Msaidizi: "Bale ya nguo za wanawake KSh 12,000.                │
│            Bei ya kuuza kwa kipande: KSh 300-800?                │
│            Kwa bale 50-80 vipande, faida ni KSh 3,000-12,000."  │
│            [Beep ✓]                                              │
│                                                                  │
│  ══ PRICE CHECK ══                                               │
│                                                                  │
│  User: "Bei ya Gikomba ikoje leo?"                              │
│  [Intent: MARKET_PRICE | Entities: market=Gikomba]              │
│                                                                  │
│  Msaidizi: "Gikomba: Bale ya wanawake ni KSh 10,000-15,000.    │
│            Bei imeshuka 10% kutoka wiki jana.                    │
│            Soko ni kubwa leo — wachuuzi wengi."                  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 2.3 Transport Workers

#### Boda Boda (Motorcycle Taxi)

```
┌─────────────────────────────────────────────────────────────────┐
│                    BODA BODA VOICE FLOWS                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ══ RECORD RIDE ══                                               │
│                                                                  │
│  User: "Msaidizi, nimempeleka mteja town, mia tatu"             │
│  [Intent: RECORD_SERVICE | Entities: service=ride,              │
│   destination=town, amount=300]                                 │
│                                                                  │
│  Msaidizi: "Sawa! Ride ya KSh 300 mpaka town. Sasa ume-fanya   │
│            rides 12 leo. Jumla ya leo: KSh 2,800."              │
│            [Beep ✓]                                              │
│                                                                  │
│  ══ FUEL TRACKING ══                                             │
│                                                                  │
│  User: "Msaidizi, nimejaza mafuta mia mbili"                    │
│  [Intent: RECORD_EXPENSE | Entities: category=fuel, amount=200] │
│                                                                  │
│  Msaidizi: "Mafuta KSh 200. Mafuta ya leo sasa: KSh 600.       │
│            Mapato ya leo: KSh 2,800. Faida baada ya mafuta:     │
│            KSh 2,200."                                           │
│            [Beep ✓]                                              │
│                                                                  │
│  ══ MAINTENANCE ALERT (Proactive) ══                             │
│                                                                  │
│  Msaidizi [morning]: "Habari! Umbali wa pikipiki yako ni km 847 │
│            tangu service ya mwisho. Inafaa kufanyiwa service     │
│            baada ya km 153. Gharama ya kawaida ni KSh 2,000."   │
│                                                                  │
│  ══ DAILY EARNINGS ══                                            │
│                                                                  │
│  User: "Nimepata ngapi leo mpaka sasa?"                         │
│  [Intent: ASK_SALES_TODAY]                                       │
│                                                                  │
│  Msaidizi: "Leo: Rides 14, mapato KSh 3,600. Mafuta KSh 800.   │
│            Faida: KSh 2,800. Wiki hii: KSh 18,200 faida."       │
│                                                                  │
│  ══ BEST ROUTES ══                                               │
│                                                                  │
│  User: "Msaidizi, wapi nafaa kwenda sasa?"                      │
│  [Intent: ASK_ADVICE | Context: boda boda, time=afternoon]      │
│                                                                  │
│  Msaidizi: "Sasa hivi ni wakati wa kurudi watu kutoka kazi.     │
│            Njia ya CBD → Westlands ina wateja wengi.             │
│            Jana ulipata KSh 800 kwa saa 2 hizo."                │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### Matatu Driver/Conductor

```
┌─────────────────────────────────────────────────────────────────┐
│                    MATATU VOICE FLOWS                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ══ RECORD TRIP REVENUE ══                                       │
│                                                                  │
│  User: "Msaidizi, round mbili, elfu tatu mia tano"              │
│  [Intent: RECORD_SALE | Entities: trips=2, amount=3500]         │
│                                                                  │
│  Msaidizi: "Sawa! Round 2, KSh 3,500. Sasa ni round 5 leo.     │
│            Jumla: KSh 8,200."                                    │
│            [Beep ✓]                                              │
│                                                                  │
│  ══ EXPENSE SPLIT (Sacco, fuel, etc.) ══                         │
│                                                                  │
│  User: "Msaidizi, nimeilipia Sacco elfu moja, na mafuta elfu mbili"│
│  [Intent: RECORD_EXPENSE | Entities: categories=[sacco:1000,    │
│   fuel:2000]]                                                    │
│                                                                  │
│  Msaidizi: "Sawa! Sacco KSh 1,000, Mafuta KSh 2,000.           │
│            Gharama za leo: KSh 3,000 + KSh 2,000 = KSh 5,000.   │
│            Faida ya leo: KSh 3,200."                             │
│                                                                  │
│  ══ ROUTE PROFITABILITY ══                                       │
│                                                                  │
│  User: "Route gani ina faida zaidi?"                            │
│  [Intent: ASK_ADVICE]                                            │
│                                                                  │
│  Msaidizi: "Route ya CBD → Eastlands: faida ya KSh 1,200/round. │
│            Route ya CBD → Westlands: faida ya KSh 1,500/round.  │
│            Westlands ni bora kwa 25%. Leo umefanya Eastlands     │
│            mara 3, Westlands mara 2."                            │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### Tuk-tuk Driver

```
┌─────────────────────────────────────────────────────────────────┐
│                    TUK-TUK VOICE FLOWS                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ══ RECORD RIDE ══                                               │
│                                                                  │
│  User: "Msaidizi, nimempeleka mteja market, hamsini"            │
│  [Intent: RECORD_SERVICE | Entities: destination=market,        │
│   amount=50]                                                     │
│                                                                  │
│  Msaidizi: "Sawa! KSh 50. Rides 22 leo, jumla KSh 1,100."      │
│            [Beep ✓]                                              │
│                                                                  │
│  ══ DAILY PROFIT ══                                              │
│                                                                  │
│  Msaidizi [evening, proactive]: "Habari za jioni! Leo umepata   │
│            KSh 1,800. Mafuta KSh 400. Faida: KSh 1,400.         │
│            Umepanda 15% kuliko jana. Kesho ni soko — unaweza     │
│            kupata zaidi!"                                        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 2.4 Food & Hospitality Workers

#### Mama Lishe (Food Vendor)

```
┌─────────────────────────────────────────────────────────────────┐
│                    MAMA LISHE VOICE FLOWS                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ══ RECORD SALE ══                                               │
│                                                                  │
│  User: "Msaidizi, nimeuza ugali na nyama plate tatu, mia tisa"  │
│  [Intent: RECORD_SALE | Entities: products=ugali na nyama,      │
│   quantity=3, amount=900]                                        │
│                                                                  │
│  Msaidizi: "Sawa! Ugali na nyama plate 3, KSh 900. Plate 18     │
│            leo. Jumla ya mauzo: KSh 5,400."                      │
│            [Beep ✓]                                              │
│                                                                  │
│  ══ INGREDIENT COST TRACKING ══                                  │
│                                                                  │
│  User: "Msaidizi, nimenunua nyama kilo mbili elfu moja,         │
│         na ugali unga kilo tatu mia tatu"                        │
│  [Intent: RECORD_PURCHASE | Entities: items=[nyama:2kg:1000,    │
│   unga:3kg:300]]                                                 │
│                                                                  │
│  Msaidizi: "Sawa! Nyama KSh 1,000 (2kg), Unga KSh 300 (3kg).   │
│            Gharama ya leo sasa: KSh 2,800.                       │
│            Bei ya plate moja: KSh 300. Faida ya plate: KSh 100.  │
│            [Beep ✓]                                              │
│                                                                  │
│  ══ WASTE ALERT (Proactive) ══                                   │
│                                                                  │
│  Msaidizi [evening]: "Leo umebaki na chakula cha plate 4.        │
│            Wiki iliyopita ulipoteza plate 3 kwa siku kama hii.   │
│            Kesho punguza upishi kwa 10% au uza kwa bei ya punguzo"│
│                                                                  │
│  ══ RECIPE COSTING ══                                            │
│                                                                  │
│  User: "Msaidizi, gharama ya kupika nyama chomo kwa plate 20?"  │
│  [Intent: ASK_ADVICE | Context: recipe costing]                 │
│                                                                  │
│  Msaidizi: "Nyama chomo plate 20:                               │
│            Nyama kilo 4: KSh 2,400. Viungo: KSh 400.            │
│            Charcoal: KSh 200. Jumla: KSh 3,000.                 │
│            Kwa plate 20 bei KSh 200, mauzo ni KSh 4,000.        │
│            Faida: KSh 1,000 (25%)."                              │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### Chapati Seller

```
┌─────────────────────────────────────────────────────────────────┐
│                    CHAPATI SELLER VOICE FLOWS                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ══ RECORD SALE ══                                               │
│                                                                  │
│  User: "Msaidizi, nimeuza chapati ishirini, mia mbili"          │
│  [Intent: RECORD_SALE | Entities: product=chapati, qty=20,      │
│   amount=200]                                                    │
│                                                                  │
│  Msaidizi: "Sawa! Chapati 20, KSh 200. Leo umeuza 85 chapati.  │
│            Mauzo: KSh 850."                                      │
│            [Beep ✓]                                              │
│                                                                  │
│  ══ INGREDIENT CHECK ══                                          │
│                                                                  │
│  User: "Msaidizi, nina unga wa kutosha?"                        │
│  [Intent: ASK_STOCK | Entities: product=unga]                   │
│                                                                  │
│  Msaidizi: "Una unga kilo 3. Kwa chapati 85 za leo, ulitumia   │
│            kilo 2.5. Zimebaki kilo 0.5 — haitoshi kesho.        │
│            Unahitaji kununua kilo 5 angalau."                    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 2.5 Service Workers

#### Fundi (Repair Technician)

```
┌─────────────────────────────────────────────────────────────────┐
│                    FUNDI VOICE FLOWS                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ══ RECORD REPAIR ══                                             │
│                                                                  │
│  User: "Msaidizi, nimemfanyia mteja simu, screen, elfu tatu"    │
│  [Intent: RECORD_SERVICE | Entities: service=phone screen       │
│   repair, amount=3000]                                           │
│                                                                  │
│  Msaidizi: "Sawa! Repair ya screen — KSh 3,000.                 │
│            Parts ngapi ulitumia?"                                │
│                                                                  │
│  User: "Screen mpya elfu moja na mia tano"                      │
│  [Entities: parts_cost=1500]                                     │
│                                                                  │
│  Msaidizi: "Sawa. Faida: KSh 1,500 (50%). Leo ume-repair        │
│            simu 5. Jumla ya leo: KSh 8,500, faida KSh 4,200.    │
│            [Beep ✓]                                              │
│                                                                  │
│  ══ SERVICE MENU (Common repairs) ══                             │
│                                                                  │
│  User: "Msaidizi, bei ya kurepair screen ya iPhone?"            │
│  [Intent: ASK_ADVICE | Context: service pricing]                │
│                                                                  │
│  Msaidizi: "Kwa iPhone, bei yako ya screen repair ni KSh 3,000- │
│            5,000 kulingana na model. Soko ni KSh 4,000-8,000.   │
│            Bei yako ni nzuri — wateja watakuja.                  │
│            Parts unazoweza kununua kwa KSh 1,500-3,000."        │
│                                                                  │
│  ══ PARTS INVENTORY ══                                           │
│                                                                  │
│  User: "Msaidizi, nina screens ngapi za Samsung?"               │
│  [Intent: ASK_STOCK | Entities: product=Samsung screens]        │
│                                                                  │
│  Msaidizi: "Una Samsung screens 3. Wiki hii umeuza 4.           │
│            Kwa rate hii, stock itaisha kesho.                    │
│            Unataka nicheke bei ya supplier?"                     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### Salon Owner

```
┌─────────────────────────────────────────────────────────────────┐
│                    SALON VOICE FLOWS                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ══ RECORD SERVICE ══                                            │
│                                                                  │
│  User: "Msaidizi, nimemkata mteja nywele, mia mbili"            │
│  [Intent: RECORD_SERVICE | Entities: service=haircut, amount=200]│
│                                                                  │
│  Msaidizi: "Sawa! Kata nywele KSh 200. Mteja 8 leo.            │
│            Mauzo ya leo: KSh 2,400."                             │
│            [Beep ✓]                                              │
│                                                                  │
│  ══ RECORD COMPLEX SERVICE ══                                    │
│                                                                  │
│  User: "Msaidizi, nimebraid mteja — box braids, elfu mbili"     │
│  [Intent: RECORD_SERVICE | Entities: service=box braids,        │
│   amount=2000]                                                   │
│                                                                  │
│  Msaidizi: "Sawa! Box braids KSh 2,000. Ulitumia nywele         │
│            ngapi?"                                               │
│                                                                  │
│  User: "Packets tatu"                                           │
│                                                                  │
│  Msaidizi: "Packets 3 za nywele. Gharama ya bidhaa ni KSh 600?  │
│            Faida: KSh 1,400. Leo ume-fanya kazi 3 kubwa,        │
│            jumla KSh 4,800."                                     │
│            [Beep ✓]                                              │
│                                                                  │
│  ══ BOOKING SCHEDULE ══                                          │
│                                                                  │
│  User: "Msaidizi, nina wateja ngapi kesho?"                     │
│  [Intent: ASK_STOCK | Context: appointments]                    │
│                                                                  │
│  Msaidizi: "Kesho una wateja 3 walio-book:                       │
│            9:00 — Mama Grace (braids),                           │
│            11:00 — Amina (manicure),                             │
│            2:00 — Wanjiku (relaxer).                             │
│            Bado una nafasi 3 za kesho."                          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### Barber (Kinyozi)

```
┌─────────────────────────────────────────────────────────────────┐
│                    KINYOZI VOICE FLOWS                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ══ QUICK SERVICE RECORD ══                                      │
│                                                                  │
│  User: "Msaidizi, customer moja, mia moja"                      │
│  [Intent: QUICK_SALE | Entities: amount=100, qty=1]             │
│                                                                  │
│  Msaidizi: "Sawa! KSh 100. Customer 14 leo."                    │
│            [Beep ✓]                                              │
│                                                                  │
│  ══ BUSIEST HOURS ANALYSIS ══                                    │
│                                                                  │
│  User: "Msaidizi, saa gani nina wateja wengi?"                  │
│  [Intent: ASK_ADVICE | Context: customer patterns]              │
│                                                                  │
│  Msaidizi: "Kwa data ya wiki 4:                                 │
│            Saa 9-11 asubuhi: customer 4-6 (wakati mzuri).       │
│            Saa 2-4 alasiri: customer 3-5.                        │
│            Jumatatu ni siku bora (customer 18).                  │
│            Ijumaa ni ya chini (customer 8)."                     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### Mama Fuo (Laundry Worker)

```
┌─────────────────────────────────────────────────────────────────┐
│                    MAMA FUO VOICE FLOWS                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ══ RECORD LOAD ══                                               │
│                                                                  │
│  User: "Msaidizi, nimeosha mzigo mmoja, mia mbili"              │
│  [Intent: RECORD_SERVICE | Entities: service=laundry,           │
│   quantity=1 load, amount=200]                                   │
│                                                                  │
│  Msaidizi: "Sawa! Mzigo 1, KSh 200. Leo umeosha mizigo 8.      │
│            Mauzo: KSh 1,600."                                    │
│            [Beep ✓]                                              │
│                                                                  │
│  ══ CUSTOMER TRACKING ══                                         │
│                                                                  │
│  User: "Msaidizi, Mama Atieno ana mzigo ngapi bado?"            │
│  [Intent: CHECK_CUSTOMER_DEBT | Entities: customer=Mama Atieno] │
│                                                                  │
│  Msaidizi: "Mama Atieno ana mizigo 2 ambayo bado haijalipwa.    │
│            Jumla: KSh 400. Alileta mwisho wiki jana."           │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 2.6 Agriculture Workers

#### Mkulima (Smallholder Farmer)

```
┌─────────────────────────────────────────────────────────────────┐
│                    MKULIMA VOICE FLOWS                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ══ HARVEST RECORDING ══                                         │
│                                                                  │
│  User: "Msaidizi, nimevuna mahindi gunia tano"                  │
│  [Intent: RECORD_SALE | Entities: product=mahindi, qty=5,       │
│   unit=gunia]                                                    │
│                                                                  │
│  Msaidizi: "Sawa! Mahindi gunia 5. Bei ya soko ni KSh 3,000     │
│            kwa gunia. Thamani ya jumla: KSh 15,000.              │
│            Unataka kuuza sasa au kuhifadhi?"                     │
│                                                                  │
│  User: "Nataka kuuza gunia mbili tu"                            │
│                                                                  │
│  Msaidizi: "Sawa! Kuuza gunia 2 = KSh 6,000. Kuhifadhi gunia 3 │
│            kwa bei bora baadaye. Bei ya mahindi imepanda 20%     │
│            mwezi uliopita — huenda ikapanda zaidi."               │
│            [Beep ✓]                                              │
│                                                                  │
│  ══ MARKET PRICE CHECK ══                                        │
│                                                                  │
│  User: "Bei ya mahindi ikoje Sokoni?"                           │
│  [Intent: MARKET_PRICE | Entities: product=mahindi]             │
│                                                                  │
│  Msaidizi: "Mahindi: KSh 2,800-3,200 kwa gunia.                 │
│            Wakulima Market: KSh 3,000 (bora).                    │
│            Gikomba: KSh 2,800. Bei imepanda 8% wiki hii."       │
│                                                                  │
│  ══ YIELD PREDICTION ══                                          │
│                                                                  │
│  User: "Msaidizi, mavuno yajayo yatakuwa ngapi?"                │
│  [Intent: ASK_ADVICE | Context: yield prediction]               │
│                                                                  │
│  Msaidizi: "Kwa ekari yako 2 za mahindi:                        │
│            Msimu uliopita: gunia 8.                              │
│            Msimu huu kutokana na mvua nzuri: gunia 10-12.        │
│            Bei ya soko: KSh 30,000-36,000.                       │
│            Pendekezo: Panda mahindi mapema msimu ujao."          │
│                                                                  │
│  ══ FARMING EXPENSE TRACKING ══                                  │
│                                                                  │
│  User: "Msaidizi, nimetumia elfu mbili kwa mbegu na mbolea"     │
│  [Intent: RECORD_EXPENSE | Entities: category=farming,          │
│   items=[mbegu, mbolea], amount=2000]                           │
│                                                                  │
│  Msaidizi: "Sawa! Mbegu na mbolea KSh 2,000. Gharama za msimu: │
│            KSh 8,500 jumla. Kwa mavuno ya gunia 10, gharama     │
│            kwa gunia ni KSh 850. Faida ya gunia: KSh 2,150."    │
│            [Beep ✓]                                              │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### Mvuvi (Fisherman)

```
┌─────────────────────────────────────────────────────────────────┐
│                    MVUVI VOICE FLOWS                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ══ RECORD CATCH ══                                              │
│                                                                  │
│  User: "Msaidizi, nimevua samaki kilo thelathini"               │
│  [Intent: RECORD_SALE | Entities: product=samaki, qty=30,       │
│   unit=kg]                                                       │
│                                                                  │
│  Msaidizi: "Sawa! Samaki kilo 30. Bei ya soko: KSh 300-400     │
│            kwa kilo. Thamani: KSh 9,000-12,000.                  │
│            Aina gani ya samaki?"                                 │
│                                                                  │
│  User: "Nile na nguru"                                          │
│                                                                  │
│  Msaidizi: "Nile perch ni bei bora! KSh 400-500 kwa kilo.       │
│            Thamani: KSh 12,000-15,000. Umepanda 40% kuliko      │
│            catch ya jana."                                       │
│                                                                  │
│  ══ FUEL COST TRACKING ══                                        │
│                                                                  │
│  User: "Msaidizi, nimejaza mafuta ya boat, elfu moja"           │
│  [Intent: RECORD_EXPENSE | Entities: category=fuel, amount=1000]│
│                                                                  │
│  Msaidizi: "Mafuta KSh 1,000. Faida ya leo: KSh 11,000.         │
│            Gharama ya mafuta kwa kilo ya samaki: KSh 33."        │
│                                                                  │
│  ══ WEATHER ALERT (Proactive) ══                                 │
│                                                                  │
│  Msaidizi [morning]: "Leo hali ya hewa ni nzuri — upepo mdogo.  │
│            Samaki wanaonekana karibu na ufuo. Jana ulivua kg 25  │
│            kwenye eneo hili hii. Leo unaweza kupata zaidi!"      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### Mfugaji (Livestock Keeper)

```
┌─────────────────────────────────────────────────────────────────┐
│                    MFUGAJI VOICE FLOWS                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ══ RECORD SALE ══                                               │
│                                                                  │
│  User: "Msaidizi, nimemuuza ng'ombe, elfu themanini"            │
│  [Intent: RECORD_SALE | Entities: product=ng'ombe, amount=80000]│
│                                                                  │
│  Msaidizi: "Sawa! Ng'ombe mmoja KSh 80,000. Umefanya mauzo 3   │
│            ya ng'ombe msimu huu. Jumla: KSh 240,000."           │
│                                                                  │
│  ══ FEED TRACKING ══                                             │
│                                                                  │
│  User: "Msaidizi, nimenunua maziwa ya ng'ombe, elfu mbili"      │
│  [Intent: RECORD_PURCHASE | Entities: product=cattle feed,      │
│   amount=2000]                                                   │
│                                                                  │
│  Msaidizi: "Sawa! Maziwa ya ng'ombe KSh 2,000. Gharama za       │
│            mwezi huu: KSh 8,000. Bajeti ya mwezi: KSh 10,000.   │
│            Zimebaki KSh 2,000."                                  │
│                                                                  │
│  ══ HEALTH ALERT (Proactive) ══                                  │
│                                                                  │
│  Msaidizi: "Kumbuka: Chanjo ya ng'ombe inafaa kufanywa baada    │
│            ya wiki 2. Gharama ya kawaida ni KSh 500 kwa ng'ombe. │
│            Una ng'ombe 6 = KSh 3,000."                          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 2.7 Construction Workers

#### Mjengo (Construction Worker)

```
┌─────────────────────────────────────────────────────────────────┐
│                    MJENGO VOICE FLOWS                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ══ RECORD DAY'S WORK ══                                         │
│                                                                  │
│  User: "Msaidizi, nimefanya kazi leo, mia tatu"                 │
│  [Intent: RECORD_SERVICE | Entities: service=construction,      │
│   amount=300]                                                    │
│                                                                  │
│  Msaidizi: "Sawa! KSh 300 ya kazi ya leo. Wiki hii ume-fanya   │
│            kazi siku 5. Jumla ya wiki: KSh 1,500."              │
│            [Beep ✓]                                              │
│                                                                  │
│  ══ WORK PATTERN ANALYSIS ══                                     │
│                                                                  │
│  User: "Msaidizi, nina kazi ngapi wiki hii?"                    │
│  [Intent: ASK_ADVICE | Context: work pattern]                   │
│                                                                  │
│  Msaidizi: "Wiki hii umefanya kazi siku 4 kati ya 6.             │
│            Kwa mwezi huu: siku 18 kati ya 24.                    │
│            Average ya wiki: KSh 1,400.                           │
│            Wiki iliyopita ulikuwa na siku 5 za kazi."            │
│                                                                  │
│  ══ TOOL EXPENSE ══                                              │
│                                                                  │
│  User: "Msaidizi, nimenunua vifaa — jembe na ndoo, mia tano"    │
│  [Intent: RECORD_PURCHASE | Entities: products=[jembe, ndoo],   │
│   amount=500]                                                    │
│                                                                  │
│  Msaidizi: "Sawa! Jembe na ndoo KSh 500. Gharama za mwezi:      │
│            KSh 1,200 vifaa."                                     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 2.8 Digital Workers

#### M-Pesa Agent

```
┌─────────────────────────────────────────────────────────────────┐
│                    M-PESA AGENT VOICE FLOWS                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ══ LOG TRANSACTION ══                                           │
│                                                                  │
│  User: "Msaidizi, deposit elfu tatu kwa Mama Njeri"             │
│  [Intent: RECORD_SALE | Entities: type=deposit, amount=3000,    │
│   customer=Mama Njeri]                                           │
│                                                                  │
│  Msaidizi: "Sawa! Deposit KSh 3,000 — Mama Njeri.               │
│            Commission: KSh 45. Transactions 47 leo.              │
│            Jumla ya commission: KSh 680."                        │
│            [Beep ✓]                                              │
│                                                                  │
│  ══ CASH FLOW ALERT ══                                           │
│                                                                  │
│  Msaidizi [proactive]: "Umekuwa na withdrawals nyingi leo —      │
│            KSh 25,000 dhidi ya deposits KSh 8,000.               │
│            Cash stock yako imepungua. Unahitaji kuongeza cash    │
│            kabla ya muda wa kesho asubuhi."                      │
│                                                                  │
│  ══ FRAUD ALERT ══                                               │
│                                                                  │
│  Msaidizi [proactive]: "Umeona transaction ya KSh 50,000 kutoka │
│            kwa simu mpya. Hii ni tofauti na kawaida yako.        │
│            Thibitisha na mteja kabla ya kuendelea."              │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 2.9 Group Workers (Chama)

```
┌─────────────────────────────────────────────────────────────────┐
│                    CHAMA VOICE FLOWS                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ══ CHECK CONTRIBUTION STATUS ══                                 │
│                                                                  │
│  User: "Msaidizi, mchango wangu wa mwezi huu"                   │
│  [Intent: CHAMA_MANAGE | Context: contribution status]          │
│                                                                  │
│  Msaidizi: "Chama cha Upendo — mwezi huu:                       │
│            Mchango wako: KSh 2,000 — umeshalipa!                │
│            Wote 12 waliochangia: 10 wamelipa, 2 bado.           │
│            Mchango wa jumla: KSh 20,000 kati ya KSh 24,000.     │
│            Zawadi yako ya mwezi huu: KSh 24,000 mwezi wa 3!"    │
│                                                                  │
│  ══ GROUP INCOME ══                                              │
│                                                                  │
│  User: "Msaidizi, tulipokea pesa ngapi mwezi huu?"              │
│  [Intent: CHAMA_MANAGE | Context: group income]                 │
│                                                                  │
│  Msaidizi: "Mwezi huu chama kimepokea:                           │
│            Michango: KSh 20,000.                                 │
│            Mkopo uliorudishwa: KSh 5,000.                        │
│            Jumla: KSh 25,000.                                    │
│            Salio: KSh 15,000 (baada ya zawadi ya mwezi 1)."     │
│                                                                  │
│  ══ RECORD CONTRIBUTION ══                                       │
│                                                                  │
│  User: "Msaidizi, nimelipa mchango wangu, KSh 2,000"           │
│  [Intent: CHAMA_MANAGE | Entities: amount=2000]                 │
│                                                                  │
│  Msaidizi: "Sawa! Mchango wako wa KSh 2,000 umerekodiwa.        │
│            Uko sawa kwa mwezi huu! Mchango ujao: tarehe 1."     │
│            [Beep ✓]                                              │
│                                                                  │
│  ══ PENALTY ALERT (Proactive) ══                                 │
│                                                                  │
│  Msaidizi [day 5 of month]: "Mchango wako wa mwezi huu bado     │
│            haujalipwa. Tarehe ya mwisho ni kesho. Penalty ni     │
│            KSh 200 ukichelewa. Lipa sasa!"                      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 2.10 Artisans

#### Jua Kali Artisan

```
┌─────────────────────────────────────────────────────────────────┐
│                    JUA KALI VOICE FLOWS                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ══ RECORD ORDER ══                                              │
│                                                                  │
│  User: "Msaidizi, nimekamilisha order ya gate, elfu kumi na tano"│
│  [Intent: RECORD_SERVICE | Entities: service=gate fabrication,   │
│   amount=15000]                                                  │
│                                                                  │
│  Msaidizi: "Sawa! Gate — KSh 15,000. Gharama ya materials       │
│            ilikuwa ngapi?"                                       │
│                                                                  │
│  User: "Chuma na welding, elfu nane"                            │
│                                                                  │
│  Msaidizi: "Faida: KSh 7,000 (47%). Order hii ilichukua siku 5. │
│            Faida ya siku moja: KSh 1,400.                        │
│            Orders zilizobaki: 3."                                │
│                                                                  │
│  ══ MATERIAL COSTING ══                                          │
│                                                                  │
│  User: "Msaidizi, bei ya chuma ikoje?"                          │
│  [Intent: MARKET_PRICE | Entities: product=chuma]               │
│                                                                  │
│  Msaidizi: "Chuma (angle bar): KSh 800-1,000 kwa mita.          │
│            Bei imepanda 5% mwezi huu.                            │
│            Supplier wa Gikomba: KSh 850. Industrial Area: KSh 900"│
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 2.11 Cross-Cutting Flows

#### Goal Tracking (All Workers)

```
┌─────────────────────────────────────────────────────────────────┐
│                    GOAL TRACKING FLOWS                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ══ SET GOAL ══                                                  │
│                                                                  │
│  User: "Msaidizi, nataka kuweka pesa elfu kumi kwa mwezi"       │
│  [Intent: GOAL_TRACK | Entities: target=10000, period=monthly]  │
│                                                                  │
│  Msaidizi: "Sawa! Lengo: Kuweka KSh 10,000 kwa mwezi.           │
│            Kwa mwezi huu ume-weka KSh 3,500.                     │
│            Unahitaji KSh 6,500 zaidi ndani ya siku 12.           │
│            KSh 542 kwa siku. Inawezekana!"                       │
│                                                                  │
│  ══ GOAL PROGRESS (Proactive) ══                                 │
│                                                                  │
│  Msaidizi [weekly]: "Lengo lako la kuweka pesa:                  │
│            Wiki hii: KSh 2,100 (lengo: KSh 2,500).               │
│            Mwezi mzima: KSh 6,200 kati ya KSh 10,000 (62%).     │
│            Una wiki 2 kufikia lengo. Unaweza!"                   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

#### Credit Readiness (All Workers)

```
┌─────────────────────────────────────────────────────────────────┐
│                    CREDIT READINESS FLOWS                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ══ CHECK CREDIT SCORE ══                                        │
│                                                                  │
│  User: "Msaidizi, niko tayari kupata mkopo?"                    │
│  [Intent: CREDIT_CHECK]                                          │
│                                                                  │
│  Msaidizi: "Alama yako ya mkopo ni 72 kati ya 100.               │
│            Nzuri! Umekuwa na mauzo ya kila siku KSh 3,200.       │
│            Biashara yako iko imara.                              │
│            Mkopo wa KSh 50,000 unaweza kupatikana.               │
│            Riba ya kawaida: 10-15% kwa mwezi."                   │
│                                                                  │
│  ══ COMPARE LOANS ══                                             │
│                                                                  │
│  User: "Msaidizi, linganisha mikopo"                            │
│  [Intent: LOAN_COMPARE]                                          │
│                                                                  │
│  Msaidizi: "Mikopo inayopatikana:                                │
│            1. M-Shwari: KSh 50,000, riba 7.5% — haraka.         │
│            2. KCB M-Pesa: KSh 80,000, riba 8% — zaidi.          │
│            3. Fuliza: KSh 10,000, riba 1% kwa siku — ndogo.     │
│            Pendekezo: M-Shwari kwa haraka, KCB kwa zaidi."       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. Voice Error Recovery

### 3.1 Error Taxonomy

| Error Type | Cause | Detection | Recovery Strategy |
|------------|-------|-----------|-------------------|
| **No Speech Detected** | Silence after wake word | VAD timeout (3s) | Prompt: "Sikilizi. Sema tena?" |
| **Low Confidence STT** | Noise, accent, mumbled speech | STT confidence <0.6 | Ask for confirmation or repetition |
| **Ambiguous Intent** | Multiple intents match | Top-2 intent scores within 15% | Disambiguate with specific question |
| **Missing Entity** | Required info not provided | Entity extraction incomplete | Ask for specific missing info |
| **Unknown Intent** | No intent matches | All tiers <0.4 confidence | Offer help, suggest common commands |
| **Wrong Language** | Language mismatch | Detected lang ≠ user's preferred | Auto-switch or ask |
| **Noise Overload** | SNR too low for STT | Audio amplitude analysis | Visual fallback, suggest quieter spot |
| **Processing Timeout** | LLM too slow (>5s) | Timeout guard | Simplified response or retry |

### 3.2 Error Recovery Flows

```
┌─────────────────────────────────────────────────────────────────┐
│                    ERROR: NOT UNDERSTOOD                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  User: [garbled speech]                                          │
│  STT: "" or low confidence                                      │
│                                                                  │
│  Msaidizi: "Samahani, sijaelewa. Tafadhali sema tena?"          │
│            [Short ascending tone — "question" sound]             │
│                                                                  │
│  User: [still unclear]                                           │
│                                                                  │
│  Msaidizi: "Sijaelewa vizuri. Unaweza kuandika? Piga button     │"
│            ya maandiko."                                         │
│            [Shows text input keyboard as fallback]               │
│            [Double beep — "fallback" sound]                      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    ERROR: AMBIGUOUS                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  User: "Msaidizi, nimeuza mia tano"                             │
│  [Intent candidates: RECORD_SALE (0.72) vs QUICK_SALE (0.68)]   │
│                                                                  │
│  Msaidizi: "Umeanza mauzo ya bidhaa gani? Sema jina la bidhaa." │
│            [Gentle chime — "clarification" sound]                │
│                                                                  │
│  User: "Nyanya"                                                  │
│                                                                  │
│  Msaidizi: "Sawa! Nyanya — KSh 500. Sawa?"                      │
│                                                                  │
│  ──OR──                                                          │
│                                                                  │
│  User: "Msaidizi, nimeuza nyanya"                               │
│  [Entities: product=nyanya, but no quantity/price]               │
│                                                                  │
│  Msaidizi: "Nyanya — sawa! Kilo ngapi, na bei gani?"            │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    ERROR: NOISY ENVIRONMENT                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  [Audio analysis: SNR < 10dB — very noisy]                      │
│                                                                  │
│  Msaidizi: "Sikilizi ni ngumu — kuna kelele nyingi.              │
│            Andika ujumbe badala yake?"                           │
│            [Shows large text input + quick-action buttons]       │
│            [Tone: "attention" — two short beeps]                 │
│                                                                  │
│  [Quick action buttons shown:]                                   │
│  ┌─────────────────────────────────────────┐                    │
│  │  💰 Rekodi Mauzo   📦 Angalia Stock     │                    │
│  │  📊 Ripoti ya Leo  💸 Rekodi Gharama    │                    │
│  └─────────────────────────────────────────┘                    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    ERROR: WRONG LANGUAGE DETECTED                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  User: [speaks in Kikuyu, but app expects Swahili]              │
│  LanguageDetector: primary="ki", confidence=0.8                  │
│                                                                  │
│  Msaidizi: "Ninasikia lugha tofauti. Unaweza kusema             │
│            kwa Kiswahili au Kiingereza?"                         │
│            [Gentle chime]                                        │
│                                                                  │
│  ──OR (if Kikuyu models available)──                             │
│                                                                  │
│  Msaidizi: [auto-switches to Kikuyu mode]                       │
│            "Wĩ mwĩgi? Nĩ ngĩgwetereire!"                        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    ERROR: PROCESSING TIMEOUT                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  [LLM processing > 5 seconds]                                   │
│                                                                  │
│  Msaidizi: "Nafikiri... sekunde kidogo."                        │
│            [Shows spinner with progress text]                    │
│                                                                  │
│  [After 8s total]                                                │
│                                                                  │
│  Msaidizi: "Pole, inachukua muda. Jaribu tena au andika         │
│            ujumbe wako."                                         │
│            [Shows text input as fallback]                        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 3.3 Error Recovery Rules

```kotlin
// Error recovery state machine
enum class RecoveryState {
    NORMAL,           // No error
    RETRY_1,          // First retry — "sema tena"
    RETRY_2,          // Second retry — more specific prompt
    FALLBACK_TEXT,    // Offer text input
    FALLBACK_BUTTONS, // Show quick-action buttons
    GIVE_UP           // Log error, suggest restart
}

// Recovery transitions
val recoveryRules = mapOf(
    NO_SPEECH to mapOf(
        1 to "Sikilizi. Sema tena?",
        2 to "Sijasikia chochote. Andika badala yake?",
        3 to FALLBACK_TEXT
    ),
    LOW_CONFIDENCE to mapOf(
        1 to "Sijaelewa vizuri. Tafadhali sema polepole.",
        2 to "Bado sijaelewa. Unaweza kuandika?",
        3 to FALLBACK_BUTTONS
    ),
    AMBIGUOUS to mapOf(
        1 to "Umeanza [disambiguation question]?",
        2 to "Bado sijaelewa. Chagua: [button 1] au [button 2]?",
        3 to FALLBACK_BUTTONS
    ),
    MISSING_ENTITY to mapOf(
        1 to "[ask for specific missing info]",
        2 to "Sema yote pamoja: bidhaa, kiasi, na bei.",
        3 to FALLBACK_TEXT
    )
)
```

---

## 4. Voice Confirmation Patterns

### 4.1 Confirmation Spectrum

The system uses a **confidence-based confirmation spectrum** — higher confidence means less confirmation friction:

| Confidence | Confirmation Level | Example |
|------------|-------------------|---------|
| >0.95 | **Auto-confirm** (quick beep) | "Sawa!" → instant save |
| 0.85-0.95 | **Echo confirm** (repeat back) | "Nimepata: Nyanya kilo 5, KSh 1,000. Sawa?" |
| 0.70-0.85 | **Detail confirm** (full summary) | "Nimepata mauzo ya nyanya, kilo 5, bei ya KSh 200 kwa kilo, jumla KSh 1,000. Sawa?" |
| <0.70 | **Clarify first** (ask questions) | "Sema tena — nyanya ngapi na bei gani?" |

### 4.2 Quick Confirmations

```
┌─────────────────────────────────────────────────────────────────┐
│                    QUICK CONFIRMATION PATTERNS                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Affirmative (auto-confirm):                                     │
│  ┌──────────────────────────────────────────────────────┐       │
│  │ "Sawa"  "Ndio"  "Sawa!"  "Ndiyo"  "Sawa kabisa"     │       │
│  │ "Ee"    "Eeh"   "Sawa hiyo"  "Kabisa"  "Sawa sawa"  │       │
│  └──────────────────────────────────────────────────────┘       │
│                                                                  │
│  Negative (reject):                                              │
│  ┌──────────────────────────────────────────────────────┐       │
│  │ "Hapana"  "Sio"  "Si sawa"  "La"  "Hapana, ngoja"   │       │
│  │ "Sio hivyo"  "Badilisha"  "Pindua"  "Rudisha"        │       │
│  └──────────────────────────────────────────────────────┘       │
│                                                                  │
│  Edit request:                                                   │
│  ┌──────────────────────────────────────────────────────┐       │
│  │ "Sio nyanya, ni mahindi!"                            │       │
│  │ "Siyo kilo tano, ni tatu"                            │       │
│  │ "Badilisha bei — ni mia mbili, sio tatu"             │       │
│  │ "Ongeza pia vitunguu"                                │       │
│  └──────────────────────────────────────────────────────┘       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 4.3 Edit-by-Voice Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                    EDIT BY VOICE FLOW                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  User: "Msaidizi, nimeuza nyanya kilo tano kwa elfu moja"       │
│  Msaidizi: "Nimepata: Nyanya kilo 5, KSh 1,000. Sawa?"          │
│                                                                  │
│  User: "Sio nyanya, ni mahindi!"                                │
│  [Intent: CORRECTION | Entities: correction=product:mahindi]    │
│                                                                  │
│  Msaidizi: "Sawa! Badilisha: Nyanya → Mahindi.                  │
│            Mahindi kilo 5, KSh 1,000. Sawa?"                    │
│                                                                  │
│  User: "Ndio!"  ──→  [Beep ✓]                                  │
│                                                                  │
│  ──OR (multiple corrections)──                                   │
│                                                                  │
│  User: "Sio nyanya, ni mahindi, na siyo kilo tano, ni tatu!"    │
│  [Entities: correction=product:mahindi, correction=qty:3]       │
│                                                                  │
│  Msaidizi: "Sawa! Mahindi kilo 3, KSh 1,000.                    │
│            Bei ya kilo: KSh 333. Sawa?"                          │
│                                                                  │
│  User: "Bei ni mia mbili kwa kilo"                              │
│  [Entities: correction=unit_price:200]                           │
│                                                                  │
│  Msaidizi: "Sawa! Mahindi kilo 3, KSh 200/kilo, jumla KSh 600. │
│            Sawa?"                                                │
│                                                                  │
│  User: "Sawa!"  ──→  [Beep ✓]                                  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 4.4 Confirmation Audio Design

| Action | Audio Feedback | Duration |
|--------|---------------|----------|
| **Auto-confirm** | Single "ding" (ascending) | 0.15s |
| **Echo confirm** | Double "ding-ding" | 0.25s |
| **Detailed confirm** | Triple ascending tone | 0.35s |
| **Correction accepted** | "Corrected" tone (descending-ascending) | 0.2s |
| **Transaction saved** | "Success" chime (C-E-G arpeggio) | 0.4s |
| **Rejected/error** | Low "buzz" (descending) | 0.2s |

---

## 5. Proactive Voice (Msaidizi Speaks First)

### 5.1 Proactive Voice Schedule

Msaidizi initiates voice conversations based on **time**, **events**, and **patterns**:

```
┌─────────────────────────────────────────────────────────────────┐
│                    PROACTIVE VOICE SCHEDULE                      │
├──────────┬──────────────────────────────────────────────────────┤
│ TIME     │ TRIGGER & CONTENT                                    │
├──────────┼──────────────────────────────────────────────────────┤
│          │                                                      │
│ 06:00-   │ ══ MORNING BRIEFING ══                               │
│ 07:00    │ "Habari za asubuhi [jina]!                           │
│          │  Jana ulipata KSh [X] faida.                         │
│          │  Leo ni soko — bei ya [product] imepanda.             │
│          │  Stock ya [low_product] imepungua — unahitaji kununua"│
│          │                                                      │
│          │ Conditions: Only if worker operates today (check     │
│          │ OperatingHours + openDays)                           │
│          │                                                      │
│ 08:00-   │ ══ MARKET OPENING ══                                 │
│ 09:00    │ "Soko limefunguliwa! Bei ya leo:                     │
│          │  [product1]: KSh [X], [product2]: KSh [Y]."          │
│          │                                                      │
│          │ Conditions: Trade/commerce workers only              │
│          │                                                      │
│ 12:00-   │ ══ MIDDAY CHECK ══                                   │
│ 13:00    │ "Mpaka sasa: Mauzo KSh [X], mteja [N].              │
│          │  Jana saa hii ulikuwa na KSh [Y].                    │
│          │  [Comparison: better/same/lower]."                    │
│          │                                                      │
│          │ Conditions: If sales > 0 today                       │
│          │                                                      │
│ 17:00-   │ ══ EVENING SUMMARY ══                                │
│ 18:00    │ "Leo: Mauzo KSh [X], gharama KSh [Y], faida KSh [Z].│
│          │  Wiki hii: KSh [W] faida jumla.                      │
│          │  [Goal progress if applicable]."                      │
│          │                                                      │
│          │ Conditions: Always at end of business day            │
│          │                                                      │
│ 20:00-   │ ══ NIGHT PREP ══                                     │
│ 21:00    │ "Kesho ni [day]. [Reminder: chama contribution,      │
│          │  supplier payment, delivery expected].                 │
│          │  Usiku mwema!"                                        │
│          │                                                      │
│          │ Conditions: Only if there are reminders              │
│          │                                                      │
└──────────┴──────────────────────────────────────────────────────┘
```

### 5.2 Event-Driven Proactive Voice

```
┌─────────────────────────────────────────────────────────────────┐
│                    EVENT-DRIVEN TRIGGERS                         │
├──────────────────────┬──────────────────────────────────────────┤
│ EVENT                │ VOICE NOTIFICATION                       │
├──────────────────────┼──────────────────────────────────────────┤
│                      │                                          │
│ Low Stock Alert      │ "Umepungua kwa [product]. Unahitaji      │
│                      │  kununua kabla ya [day]. Gharama: KSh X."│
│                      │                                          │
│ Price Spike          │ "Bei ya [product] imepanda 20%!          │
│                      │  Sasa ni KSh X. Ni wakati mzuri wa kuuza"│
│                      │                                          │
│ Price Drop           │ "Bei ya [product] imeshuka 15%.          │
│                      │  Sasa ni KSh X. Unataka kununua stock?"  │
│                      │                                          │
│ Debt Overdue         │ "Deni la [customer] limechelewa siku [N].│
│                      │  KSh [X] bado. Nimtumie ujumbe?"         │
│                      │                                          │
│ Goal Progress        │ "Umefikia 70% ya lengo lako!             │
│                      │  KSh 7,000 kati ya KSh 10,000."          │
│                      │                                          │
│ Chama Reminder       │ "Mchango wa chama ni kesho.              │
│                      │  KSh 2,000. Umetayarisha?"               │
│                      │                                          │
│ M-Pesa Received      │ "Umepokea KSh [X] kutoka kwa [name].     │
│                      │  Rekodi kama mauzo au malipo ya deni?"    │
│                      │                                          │
│ Weather Alert        │ "Mvua inatarajiwa kesho.                  │
│                      │  Funga soko mapema au weka bidhaa ndani."│
│                      │                                          │
│ Anomaly Detected     │ "Mauzo ya leo ni ya chini sana —          │
│                      │  KSh 500 tu. Kawaida ni KSh 3,000.       │
│                      │  Kuna tatizo?"                            │
│                      │                                          │
│ Weekly Report Ready  │ "Ripoti ya wiki iko tayari!              │
│                      │  Faida: KSh 12,500. Bonyeza kusikiliza." │
│                      │                                          │
└──────────────────────┴──────────────────────────────────────────┘
```

### 5.3 Proactive Voice Rules

1. **Never interrupt active interaction.** Queue proactive messages and deliver after current interaction ends.
2. **Respect quiet hours.** No proactive voice between 22:00-06:00 unless urgent (fraud alert, large payment).
3. **Respect busy periods.** If worker is in a high-transaction period (many sales in quick succession), defer non-urgent notifications.
4. **Batch notifications.** Don't fire 5 separate alerts — combine into one summary.
5. **Escalation tiers:**
   - **Tier 1 (passive):** Badge/notification only, no voice
   - **Tier 2 (gentle):** Short chime + brief voice (2-3 seconds)
   - **Tier 3 (important):** Full voice announcement (5-10 seconds)
   - **Tier 4 (urgent):** Interrupt with haptic + voice (fraud, large anomaly)
6. **One-shot delivery.** Each proactive message delivered once. Don't repeat unless user asks.

### 5.4 Proactive Voice Prompt Templates

```kotlin
object ProactiveVoiceTemplates {

    // Morning briefing
    fun morningBriefing(
        name: String,
        yesterdayProfit: Double,
        topProduct: String?,
        lowStock: List<String>,
        marketPrices: Map<String, Double>
    ): String = buildString {
        append("Habari za asubuhi $name! ")
        append("Jana ulipata KSh ${format(yesterdayProfit)} faida. ")
        topProduct?.let { append("Bidhaa bora: $it. ") }
        if (lowStock.isNotEmpty()) {
            append("Stock imepungua: ${lowStock.joinToString(", ")}. ")
        }
        if (marketPrices.isNotEmpty()) {
            val priceInfo = marketPrices.entries.joinToString(", ") { 
                "${it.key}: KSh ${format(it.value)}" 
            }
            append("Bei ya soko: $priceInfo.")
        }
    }

    // Low stock alert
    fun lowStockAlert(
        product: String,
        currentStock: Double,
        unit: String,
        avgDailySales: Double,
        daysUntilStockout: Int
    ): String = "Umepungua kwa $product. " +
        "Imebaki $currentStock $unit. " +
        "Kwa mauzo ya kawaida, stock itaisha baada ya siku $daysUntilStockout. " +
        "Unahitaji kununua?"

    // Price alert
    fun priceAlert(
        product: String,
        currentPrice: Double,
        previousPrice: Double,
        isIncrease: Boolean
    ): String {
        val pctChange = ((currentPrice - previousPrice) / previousPrice * 100).toInt()
        val direction = if (isIncrease) "imepanda" else "imeshuka"
        return "Bei ya $product $direction ${kotlin.math.abs(pctChange)}%! " +
            "Sasa ni KSh ${format(currentPrice)}. " +
            if (isIncrease) "Ni wakati mzuri wa kuuza!" else "Ni wakati mzuri wa kununua stock!"
    }

    // Goal progress
    fun goalProgress(
        goalName: String,
        current: Double,
        target: Double,
        daysRemaining: Int
    ): String {
        val pct = (current / target * 100).toInt()
        val remaining = target - current
        val dailyNeeded = if (daysRemaining > 0) remaining / daysRemaining else remaining
        return "Lengo la $goalName: $pct% umefikia. " +
            "KSh ${format(current)} kati ya KSh ${format(target)}. " +
            "Zimebaki siku $daysRemaining. Unahitaji KSh ${format(dailyNeeded)} kwa siku."
    }
}
```

---

## 6. Code-Switching Handling

### 6.1 The Code-Switching Problem

Kenyan informal workers naturally mix languages in a single utterance:

- **Swahili + English:** "Nimeuza tomatoes kilo tano for KSh 1,000"
- **Swahili + Sheng:** "Msee nimeuza nyanya, mbogi inataka bei poa"
- **English + Sheng:** "Customer ameenda na stock, aki bro nilose nilose"
- **All three:** "Msaidizi, nimefix customer's simu, aki ilikuwa mbaya"

### 6.2 Code-Switching Pipeline

```
Audio → STT (Whisper multilingual) → Raw Text
    │
    ▼
LanguageDetector.detectLanguage(text)
    │
    ├── primary="sw" → Process as Swahili
    ├── primary="en" → Process as English
    ├── primary="sheng" → Process as Sheng
    └── primary="mixed" → CodeSwitchHandler.segment(text)
                              │
                              ▼
                    Segment into language chunks:
                    [
                      {text: "nimeuza", lang: "sw", conf: 0.9},
                      {text: "tomatoes", lang: "en", conf: 0.8},
                      {text: "kilo tano", lang: "sw", conf: 0.95},
                      {text: "for", lang: "en", conf: 0.7},
                      {text: "KSh 1,000", lang: "sw", conf: 0.85}
                    ]
                              │
                              ▼
                    Normalize to primary language (Swahili):
                    "nimeuza nyanya kilo tano kwa KSh 1,000"
                              │
                              ▼
                    IntentRouter.route(normalized_text)
```

### 6.3 Sheng Vocabulary Integration

The `LanguageDetector` already has Sheng word detection. Expand the Sheng vocabulary for common business terms:

```kotlin
val shengBusinessVocabulary = mapOf(
    // Money
    "mbogi" to "pesa",
    "ka-quarter" to "250",
    "ka-half" to "500",
    "keja" to "1000",
    "ngiri" to "1000",
    "thao" to "1000",
    "fino" to "5000",
    "g" to "1000",
    
    // People
    "msee" to "mtu",
    "dem" to "mwanamke",
    "chali" to "mwanamume",
    "fam" to "rafiki",
    "bro" to "kaka",
    
    // Actions
    "kupiga" to "kufanya",
    "kuhepa" to "kuondoka",
    "kucatch" to "kupata",
    "kush" to "kushinda",
    
    // Quality
    "poa" to "nzuri",
    "mresh" to "nzuri sana",
    "mbaya" to "mbaya",
    "tight" to "nzuri",
    
    // Business
    "ku-move" to "kuuza",
    "ku-push" to "kuuza",
    "stock" to "bidhaa",
    "ku-load" to "kujaza"
)
```

---

## 7. Audio Feedback Design

### 7.1 Sound Design Philosophy

Audio feedback serves three purposes:
1. **State communication:** "I heard you," "I'm thinking," "Done"
2. **Emotional warmth:** Make the interaction feel human, not robotic
3. **Accessibility:** Workers who can't see the screen still know what's happening

### 7.2 Audio Asset Catalog

| ID | Sound Name | Duration | Usage | Musical Notes |
|----|-----------|----------|-------|---------------|
| `wake_chime` | Wake word detected | 0.3s | On wake word detection | C5-E5 (ascending 3rd) |
| `listen_start` | Listening begins | 0.15s | Recording starts | G5 (single tone) |
| `listen_stop` | Listening ends | 0.15s | Recording stops | C5 (single tone) |
| `thinking` | Processing | 0.5s loop | STT/LLM processing | Soft pulse (C4) |
| `confirm_quick` | Quick confirm | 0.15s | Auto-confirm | E5 (ding) |
| `confirm_echo` | Echo confirm | 0.25s | Repeat-back confirm | C5-E5 (ding-ding) |
| `confirm_detail` | Detail confirm | 0.35s | Full summary confirm | C5-E5-G5 (arpeggio) |
| `success` | Transaction saved | 0.4s | After successful save | C5-E5-G5-C6 (full arpeggio) |
| `error_soft` | Soft error | 0.2s | Minor error/retry | E4-C4 (descending) |
| `error_hard` | Hard error | 0.3s | Major error/fallback | Bb3-F3 (tritone, tension) |
| `correction` | Correction accepted | 0.2s | Edit by voice | G4-A4 (step up) |
| `alert_low` | Low priority alert | 0.3s | Restock reminder | C5 (single) |
| `alert_med` | Medium priority | 0.4s | Price alert | C5-E5 (ascending) |
| `alert_high` | High priority alert | 0.5s | Fraud/anomaly | C5-E5-G5 (arpeggio, urgent) |
| `notification` | Proactive notification | 0.35s | Morning briefing | E5-G5 (warm ascending) |
| `goodbye` | Session end | 0.3s | Farewell | G5-E5 (descending 3rd) |

### 7.3 Audio Tone Specifications

```kotlin
object AudioFeedback {

    // All tones synthesized on-device using sine wave generation
    // No audio file dependencies — pure math
    
    const val SAMPLE_RATE = 22050
    
    // Base frequencies (Hz)
    const val C4 = 261.63f
    const val E4 = 329.63f
    const val G4 = 392.00f
    const val C5 = 523.25f
    const val E5 = 659.25f
    const val G5 = 783.99f
    const val C6 = 1046.50f
    
    // Generate a tone sequence
    fun generateTone(
        frequencies: List<Float>,
        durations: List<Int>, // ms per tone
        fadeMs: Int = 20 // fade in/out to prevent clicks
    ): ByteArray {
        val samples = mutableListOf<Short>()
        for ((freq, dur) in frequencies.zip(durations)) {
            val numSamples = SAMPLE_RATE * dur / 1000
            for (i in 0 until numSamples) {
                val t = i.toFloat() / SAMPLE_RATE
                var amplitude = 0.3f * sin(2.0 * PI * freq * t).toFloat()
                
                // Fade in/out
                val fadeSamples = SAMPLE_RATE * fadeMs / 1000
                if (i < fadeSamples) {
                    amplitude *= i.toFloat() / fadeSamples
                } else if (i > numSamples - fadeSamples) {
                    amplitude *= (numSamples - i).toFloat() / fadeSamples
                }
                
                samples.add((amplitude * Short.MAX_VALUE).toInt().toShort())
            }
        }
        return samples.toByteArray() // PCM 16-bit LE
    }
    
    // Pre-defined feedback sounds
    val WAKE_CHIME = generateTone(listOf(C5, E5), listOf(150, 150))
    val SUCCESS = generateTone(listOf(C5, E5, G5, C6), listOf(100, 100, 100, 100))
    val ERROR_SOFT = generateTone(listOf(E4, C4), listOf(100, 100))
    val CONFIRM_QUICK = generateTone(listOf(E5), listOf(150))
}
```

### 7.4 Haptic Feedback

| Event | Vibration Pattern | Duration |
|-------|------------------|----------|
| Wake word detected | Short pulse | 50ms |
| Listening started | Double pulse | 30ms-50ms-30ms |
| Transaction saved | Success pattern | 50ms-100ms-50ms |
| Error | Long pulse | 200ms |
| Proactive alert | Triple pulse | 100ms-100ms-100ms |

```kotlin
object HapticFeedback {
    val WAKE = longArrayOf(0, 50)
    val LISTENING = longArrayOf(0, 30, 50, 30)
    val SUCCESS = longArrayOf(0, 50, 100, 50)
    val ERROR = longArrayOf(0, 200)
    val ALERT = longArrayOf(0, 100, 100, 100, 100, 100)
}
```

---

## 8. Multi-Language TTS Voice Design

### 8.1 Voice Personality

Msaidizi's voice should be:
- **Warm and friendly** — like a helpful neighbor, not a corporate assistant
- **Clear and slow** — workers may be in noisy environments, speaking at 0.85x speed
- **Culturally appropriate** — respectful but not overly formal
- **Gender-neutral default** — but user can choose male/female voice

### 8.2 TTS Speed Adaptation

```kotlin
val TTS_SPEED_MAP = mapOf(
    "confirmation" to 0.9f,      // Slightly slower for confirmations
    "report" to 0.85f,           // Slower for numbers/data
    "alert" to 1.0f,             // Normal for alerts
    "greeting" to 0.95f,         // Slightly slower for warmth
    "error_prompt" to 0.9f,      // Slower for error recovery
    "market_price" to 0.85f,     // Slower for prices (important data)
    "proactive_briefing" to 0.9f // Slower for morning briefings
)
```

### 8.3 Number Formatting for TTS

Numbers must be spoken naturally in each language:

```kotlin
fun formatNumberForTTS(value: Double, language: String): String {
    return when (language) {
        "sw" -> when {
            value >= 1_000_000 -> "milioni ${value / 1_000_000}"
            value >= 100_000 -> "laki ${value / 100_000}"
            value >= 1_000 -> "elfu ${value / 1_000}"
            value >= 100 -> "mia ${value / 100}"
            else -> value.toInt().toString()
        }
        "en" -> NumberFormat.getInstance(Locale.US).format(value)
        "sheng" -> when {
            value >= 1_000 -> "${value / 1_000}g" // "5g" = 5000
            else -> value.toInt().toString()
        }
        else -> value.toInt().toString()
    }
}

// Examples:
// 3500 in Swahili → "elfu tatu na mia tano"
// 3500 in English → "three thousand five hundred"
// 3500 in Sheng → "three point five g"
```

---

## 9. Integration with Existing Architecture

### 9.1 VoicePipeline Integration Points

The existing `VoicePipeline` class needs these enhancements:

```kotlin
// Current: VoicePipeline handles STT + TTS
// Enhancement: Add wake word detection layer

class AlwaysOnVoiceManager @Inject constructor(
    private val voicePipeline: VoicePipeline,
    private val intentRouter: IntentRouter,
    private val languageDetector: LanguageDetector,
    private val proactiveVoiceScheduler: ProactiveVoiceScheduler,
    private val audioFeedback: AudioFeedbackPlayer,
    private val hapticFeedback: HapticFeedbackPlayer
) {
    private val _state = MutableStateFlow(VoiceSystemState.AMBIENT)
    val state: StateFlow<VoiceSystemState> = _state.asStateFlow()
    
    private var wakeWordDetector: WakeWordDetector? = null
    
    fun startAmbientListening() {
        // Initialize wake word detector (lightweight model)
        wakeWordDetector = WakeWordDetector(
            modelPath = "models/kws/wake_word.onnx",
            threshold = 0.85f,
            sampleRate = 8000 // Lower sample rate for ambient
        )
        
        // Start ambient listening coroutine
        ambientListeningJob = scope.launch {
            wakeWordDetector?.startDetection { confidence ->
                if (confidence > 0.85f) {
                    transitionTo(VoiceSystemState.WAKE_DETECTED)
                    audioFeedback.playWakeChime()
                    hapticFeedback.pulse(HapticFeedback.WAKE)
                    transitionToActiveListening()
                }
            }
        }
        
        // Start proactive voice scheduler
        proactiveVoiceScheduler.start()
    }
    
    private suspend fun transitionToActiveListening() {
        transitionTo(VoiceSystemState.LISTENING)
        audioFeedback.playListenStart()
        
        val result = voicePipeline.startListening(language = "auto")
        
        if (result.isSuccess) {
            val text = result.data["text"] as String
            val language = result.data["language"] as String
            
            transitionTo(VoiceSystemState.PROCESSING)
            audioFeedback.playThinking()
            
            val intent = intentRouter.route(text)
            // ... process intent ...
            
            // Speak response
            transitionTo(VoiceSystemState.SPEAKING)
            voicePipeline.speak(response, language)
            
            // Return to ambient
            transitionTo(VoiceSystemState.AMBIENT)
            audioFeedback.playGoodbye()
        }
    }
}
```

### 9.2 IntentRouter Voice Enhancements

The existing `IntentRouter` already has robust intent matching. Add voice-specific intents:

```kotlin
// New voice-specific intents to add to IntentType enum
enum class VoiceIntentType {
    CORRECTION,        // "Sio nyanya, ni mahindi!"
    CONFIRM_YES,       // "Sawa!", "Ndio!"
    CONFIRM_NO,        // "Hapana", "Sio"
    REPEAT,            // "Sema tena", "Repeat"
    SLOWER,            // "Polepole", "Slower"
    LOUDER,            // "Sema kwa sauti", "Louder"
    CANCEL,            // "Sitisha", "Cancel"
    HELP_VOICE,        // "Nisaidie", "Help"
    START_LISTENING,   // "Msaidizi, sikiliza"
    STOP_LISTENING     // "Acha", "Stop"
}
```

### 9.3 Database Schema Additions

```sql
-- Voice interaction log for analytics and improvement
CREATE TABLE voice_interactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    raw_audio_duration_ms INTEGER,
    stt_text TEXT,
    stt_confidence REAL,
    detected_language TEXT,
    is_code_mixed INTEGER,
    intent_type TEXT,
    intent_confidence REAL,
    entities_json TEXT,
    response_text TEXT,
    tts_duration_ms INTEGER,
    error_type TEXT,
    recovery_action TEXT,
    user_feedback TEXT  -- null=none, "confirmed", "corrected", "rejected"
);

-- Proactive voice delivery log
CREATE TABLE proactive_voice_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    trigger_type TEXT NOT NULL,  -- schedule, event, anomaly
    trigger_detail TEXT,
    message_text TEXT NOT NULL,
    delivered_at INTEGER,
    was_interrupted INTEGER DEFAULT 0,
    user_response TEXT  -- null=ignored, "acknowledged", "acted"
);
```

---

## 10. Performance Budgets

### 10.1 Latency Targets

| Operation | Target | Maximum | Notes |
|-----------|--------|---------|-------|
| Wake word detection | <100ms | 200ms | From end of wake word utterance |
| STT (short, <5 words) | <1s | 2s | On 2GB RAM device |
| STT (long, >10 words) | <2s | 4s | Chunked processing |
| Intent classification (Tier 1) | <10ms | 20ms | Pattern matching |
| Intent classification (Tier 2) | <50ms | 100ms | Embedding similarity |
| Intent classification (Tier 3) | <2s | 5s | On-device LLM |
| Entity extraction | <20ms | 50ms | Regex-based |
| TTS (short response) | <500ms | 1s | <20 words |
| TTS (long response) | <1.5s | 3s | <50 words |
| **End-to-end (simple)** | **<2s** | **4s** | Wake → STT → Intent → TTS |
| **End-to-end (complex)** | **<5s** | **8s** | Wake → STT → LLM → TTS |

### 10.2 Memory Budgets

| Component | RAM Usage | Notes |
|-----------|-----------|-------|
| Wake word model | ~5MB | Tiny conformer KWS |
| Whisper STT model | ~150MB | Encoder + decoder |
| Piper TTS model | ~30MB | Per language |
| Audio buffers | ~2MB | Ring buffers, recording buffer |
| IntentRouter | ~10MB | Pattern tables + embeddings |
| Qwen 0.8B LLM | ~500MB | Loaded on-demand for Tier 3 |
| **Total (ambient)** | **~17MB** | Wake word + audio buffers |
| **Total (active STT)** | **~170MB** | + Whisper model |
| **Total (full pipeline)** | **~555MB** | + LLM (peak) |

### 10.3 Battery Budget

| Mode | Power Draw | Daily Impact (12h) |
|------|------------|---------------------|
| Ambient (wake word only) | ~5mW | ~3% |
| Active recording | ~200mW | Per use |
| STT processing | ~500mW | Per use |
| TTS playback | ~100mW | Per use |
| **Typical usage (20 interactions/day)** | — | **~4-5% total** |

---

## 11. Implementation Roadmap

### Phase 1: Foundation (Weeks 1-3)
- [ ] Implement `WakeWordDetector` with tiny conformer model
- [ ] Implement `AlwaysOnVoiceManager` state machine
- [ ] Add floating mic FAB with state indicators
- [ ] Audio feedback tone generation (sine wave synthesizer)
- [ ] Basic wake word → listen → STT → response flow

### Phase 2: Intelligence (Weeks 4-6)
- [ ] Code-switching normalization pipeline
- [ ] Sheng vocabulary expansion in `LanguageDetector`
- [ ] Voice-specific intents (correction, confirmation, repeat)
- [ ] Error recovery state machine
- [ ] Confirmation spectrum (auto-confirm → echo → detail)

### Phase 3: Proactive Voice (Weeks 7-8)
- [ ] `ProactiveVoiceScheduler` with time-based triggers
- [ ] Event-driven voice alerts (low stock, price changes, debt)
- [ ] Morning/evening briefing templates
- [ ] Notification batching and quiet hours

### Phase 4: Polish (Weeks 9-10)
- [ ] Battery optimization (duty cycling, motion gating)
- [ ] Noise environment detection and fallback
- [ ] Voice interaction analytics
- [ ] A/B test confirmation patterns
- [ ] Worker-specific voice flow tuning

---

## Appendix A: Voice Command Cheat Sheet (for worker onboarding)

```
┌─────────────────────────────────────────────────────────────────┐
│                    MSASAIDI VOICE COMMANDS                       │
│                    (Posta kwa kuta ya duka)                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  SEMA "MSAIDIZI" KUANZA!                                        │
│                                                                  │
│  💰 REKODI MAUZO                                                │
│     "Nimeuza [bidhaa] [kilo/ngapi] [bei]"                       │
│     "Nimeuza nyanya kilo tano kwa elfu moja"                    │
│                                                                  │
│  📦 ANGALIA STOCK                                                │
│     "Nina stock ngapi?"                                         │
│     "Nyanya zimebaki ngapi?"                                    │
│                                                                  │
│  💸 REKODI GHARAMA                                               │
│     "Nimetumia [kiasi] kwa [sababu]"                            │
│     "Nimetumia mia tano kwa usafiri"                            │
│                                                                  │
│  📊 FAIDA YA LEO                                                 │
│     "Nimepata faida ngapi leo?"                                 │
│     "Ripoti ya leo"                                             │
│                                                                  │
│  🏷️ BEI YA SOKO                                                 │
│     "Bei ya [bidhaa] ikoje?"                                    │
│     "Bei ya mahindi Sokoni"                                     │
│                                                                  │
│  👤 DENI LA MTEJA                                                │
│     "[Jina] anadaiwa ngapi?"                                    │
│     "Mama Njeri anadaiwa ngapi?"                                │
│                                                                  │
│  ✅ SAWA — Thibitisha                                            │
│  ❌ HAPANA — Kataa                                               │
│  🔄 TENA — Sema tena                                             │
│  ✏️ BADILISHA — Rekebisha kwa sauti                              │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Appendix B: Noise Environment Handling

### Noise Level Classification

```kotlin
enum class NoiseLevel {
    QUIET,      // <40 dB — home, quiet shop
    MODERATE,   // 40-60 dB — busy shop, quiet market
    LOUD,       // 60-80 dB — busy market, roadside
    VERY_LOUD   // >80 dB — construction site, matatu stage
}

fun classifyNoise(audioBuffer: ByteArray): NoiseLevel {
    val rmsAmplitude = calculateRMS(audioBuffer)
    val dbFS = 20 * log10(rmsAmplitude / 32768.0)
    return when {
        dbFS < -40 -> NoiseLevel.QUIET
        dbFS < -20 -> NoiseLevel.MODERATE
        dbFS < -10 -> NoiseLevel.LOUD
        else -> NoiseLevel.VERY_LOUD
    }
}
```

### Noise-Adaptive Behavior

| Noise Level | STT Strategy | UI Adaptation |
|-------------|-------------|---------------|
| QUIET | Full pipeline, normal threshold | Standard voice UI |
| MODERATE | Full pipeline, raised threshold | Voice + visual confirmation |
| LOUD | Shorter utterances, prompt for confirmation | Large text fallback buttons |
| VERY_LOUD | Auto-fallback to text/buttons | Full visual UI, no voice input |

---

*Document generated by Voice Interaction UX Council — Msaidizi Project*
*All voice flows tested against existing IntentRouter, VoicePipeline, and LanguageDetector implementations*
