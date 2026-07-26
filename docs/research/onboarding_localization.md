# Msaidizi — Onboarding & Localization Strategy

> **Council**: Onboarding & Localization  
> **Status**: Design Complete  
> **Last Updated**: 2026-07-27  
> **Target**: Informal workers in Africa — many illiterate or semi-literate, first-time smartphone users  

---

## Table of Contents

1. [Design Principles](#1-design-principles)
2. [Onboarding Flow (3-Step Voice-First)](#2-onboarding-flow)
3. [Localization Architecture](#3-localization-architecture)
4. [Swahili Voice Prompt Library (200+ Prompts)](#4-swahili-voice-prompt-library)
5. [Sheng Integration Strategy](#5-sheng-integration-strategy)
6. [Local Language Voice Support Plan](#6-local-language-voice-support-plan)
7. [Technical Implementation Notes](#7-technical-implementation-notes)
8. [Success Metrics](#8-success-metrics)

---

## 1. Design Principles

### The "Mama Mboga Test"

Every screen, every prompt, every interaction must pass this test:

> **Can Mama Mboga — who left school at Class 6, speaks Sheng with her friends, Swahili at market, and has never held a smartphone — complete this task using only her voice and what she sees on screen?**

If the answer is no, redesign.

### Core Principles

| Principle | Description |
|-----------|-------------|
| **Voice-First** | Every interaction must work by voice. Text is supplementary. |
| **Icon-Over-Text** | All choices use large, recognizable icons (≥64dp touch targets) |
| **Zero Reading Required** | No text must be read to complete onboarding. Voice guides everything. |
| **<2 Minutes Total** | Onboarding from app launch to "ready" in under 120 seconds. |
| **Tolerant of Errors** | Misrecognition, wrong taps, stutters — all recoverable without frustration. |
| **Code-Switch Native** | Users naturally mix languages; the system must handle this from Step 1. |
| **Offline-First** | Onboarding must complete without network. Voice prompts bundled as assets. |
| **Culturally Warm** | Swahili prompts are friendly, not corporate. Use familiar idioms. |

### User Personas

| Persona | Profile | Primary Language | Literacy | Smartphone Experience |
|---------|---------|-----------------|----------|----------------------|
| **Mama Mboga** | Vegetable vendor, 35, market stall | Swahili + Sheng | Semi-literate | First phone |
| **Boda Rider** | Motorcycle taxi, 22, Nairobi | Sheng + Swahili | Literate | Basic (calls, WhatsApp) |
| **Mkulima** | Farmer, 50, rural Nyanza | Dholuo + some Swahili | Illiterate | None |
| **Fundi** | Mechanic, 28, Mombasa | Swahili + English | Literate | Moderate |
| **Mama Lishe** | Food vendor, 40, Dar es Salaam | Swahili | Semi-literate | Basic (M-Pesa) |

---

## 2. Onboarding Flow

### Overview

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│   LANGUAGE   │───▶│   BUSINESS   │───▶│    VOICE     │───▶│    READY!    │
│   SELECTION  │    │    TYPE      │    │ CALIBRATION  │    │   🎉         │
│  (~20 sec)   │    │  (~30 sec)   │    │  (~30 sec)   │    │              │
└──────────────┘    └──────────────┘    └──────────────┘    └──────────────┘
     Step 1              Step 2              Step 3            Complete
```

**Total time budget**: 80 seconds (with 40-second buffer for user hesitation)

---

### Step 1: Language Selection (~20 seconds)

#### Voice Prompt
```
🔊 "Karibu Msaidizi! Chagua lugha yako."
   (Welcome to Msaidizi! Choose your language.)
```

#### Screen Layout

```
┌─────────────────────────────────────┐
│                                     │
│     🎤 [Voice prompt auto-plays]    │
│                                     │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐
│  │  🇹🇿🇰🇪  │  │  🇬🇧    │  │  🤙    │
│  │         │  │         │  │         │
│  │Kiswahili│  │ English │  │  Sheng  │
│  └─────────┘  └─────────┘  └─────────┘
│                                     │
│  ┌─────────────────────────────┐    │
│  │  🎤 Ongea lugha yako...    │    │
│  │  (Speak your language...)   │    │
│  └─────────────────────────────┘    │
│                                     │
└─────────────────────────────────────┘
```

#### Interaction Flow

1. **Auto-play voice prompt** on screen load (no tap needed)
2. **Three large cards** (80×80dp) with flags/icons:
   - 🇹🇿🇰🇪 **Kiswahili** — Flag composite (Tanzania + Kenya)
   - 🇬🇧 **English** — Union Jack
   - 🤙 **Sheng** — Handshake/madem gesture icon (culturally resonant)
3. **User can**: tap a card OR speak the language name
4. **Voice recognition keywords** (tolerant matching):
   - Swahili: "swahili", "kiswahili", "swahili", "sw"
   - English: "english", "kiingereza", "ingereza"
   - Sheng: "sheng", "seng", "mixture"
5. **Visual feedback**: Selected card pulses green, voice says confirmation

#### Confirmation Prompt (per language)

| Selected | Voice Confirmation |
|----------|-------------------|
| Kiswahili | 🔊 "Sawa! Tutaongea Kiswahili." (Okay! We'll speak Swahili.) |
| English | 🔊 "Great! We'll use English." |
| Sheng | 🔊 "Poa! Tutaongea Sheng, boss." (Cool! We'll speak Sheng, boss.) |

#### Edge Cases

- **No selection after 10 seconds**: Re-prompt with gentle nudge: "Bado? Chagua lugha." (Still there? Choose a language.)
- **Voice not recognized**: "Sikuelewi. Jaribu tena au gusa skrini." (I didn't understand. Try again or tap the screen.)
- **Language detected from speech** (auto-select): If user speaks before tapping, detect language and pre-select.

---

### Step 2: Business Type Selection (~30 seconds)

#### Voice Prompt
```
🔊 "Biashara yako ni ipi? Gusa picha au sema."
   (What is your business? Tap the picture or speak.)
```

#### Screen Layout — Icon Grid (Scrollable)

```
┌─────────────────────────────────────────────┐
│  🎤 "Biashara yako ni ipi?"                │
│                                             │
│  ┌───────┐  ┌───────┐  ┌───────┐  ┌───────┐│
│  │  🥬   │  │  🏪   │  │  🏍️   │  │  🔧   ││
│  │ Mama  │  │ Duka  │  │ Boda  │  │ Fundi ││
│  │ Mboga │  │       │  │ Boda  │  │       ││
│  └───────┘  └───────┘  └───────┘  └───────┘│
│  ┌───────┐  ┌───────┐  ┌───────┐  ┌───────┐│
│  │  💇   │  │  🌾   │  │  🐟   │  │  📱   ││
│  │Salon/ │  │Mkulima│  │Mvuvi  │  │M-Pesa ││
│  │Kinyozi│  │       │  │       │  │       ││
│  └───────┘  └───────┘  └───────┘  └───────┘│
│  ┌───────┐  ┌───────┐  ┌───────┐  ┌───────┐│
│  │  🍳   │  │  🏗️   │  │  💰   │  │  📦   ││
│  │Mama   │  │Mjengo │  │ Chama │  │Nyingine│
│  │Lishe  │  │       │  │       │  │(Other)││
│  └───────┘  └───────┘  └───────┘  └───────┘│
│                                             │
│  🎤 Ongea biashara yako...                  │
└─────────────────────────────────────────────┘
```

#### Business Type Icon Mapping

| Icon | Business Type | Swahili Name | Voice Keywords (Swahili) | Voice Keywords (Sheng) |
|------|--------------|--------------|-------------------------|------------------------|
| 🥬 | Mama Mboga | Mama Mboga | "mama mboga", "mboga", "mama" | "mama mboga", "mboga" |
| 🏪 | Duka | Dukawallah | "duka", "dukawallah", "duka la" | "duka", "shop" |
| 🏍️ | Boda Boda | Boda Boda | "boda", "boda boda", "pikipiki" | "boda", "nduthi" |
| 🔧 | Fundi | Fundi | "fundi", "fundi simu", "fundi gari" | "fundi", "mechanic" |
| 💇 | Salon/Kinyozi | Salon | "salon", "kinyozi", "barber" | "salon", "kinyozi" |
| 🌾 | Mkulima | Mkulima | "mkulima", "kilimo", "shamba" | "mkulima", "farmer" |
| 🐟 | Mvuvi | Mvuvi | "mvuvi", "samaki", "uvuvi" | "mvuvi", "fishing" |
| 📱 | M-Pesa | M-Pesa | "mpesa", "m-pesa", "pesa" | "mpesa", "lipa" |
| 🍳 | Mama Lishe | Mama Lishe | "mama lishe", "hoteli", "chakula" | "mama lishe", "food" |
| 🏗️ | Mjengo | Mjengo | "mjengo", "ujenzi", "construction" | "mjengo", "site" |
| 💰 | Chama | Chama | "chama", "kikundi", "savings" | "chama", "harambee" |
| 📦 | Nyingine | Nyingine | "nyingine", "nyingine", "other" | "nyingine", "other" |

#### Interaction Flow

1. **Grid displays with voice prompt** auto-playing
2. **User taps icon** (most common) or **speaks** business name
3. **Selected icon scales up 1.2x**, border glows green
4. **Voice confirms**: "Sawa! [Business Type]. Twende!" (Okay! [Business Type]. Let's go!)
5. **Auto-advance** to Step 3 after 1.5-second delay

#### Category Expansion (for "Other" / voice users who describe their business)

If user says something not in the keyword list, the system uses `LanguageDetector` + `CodeSwitchHandler` to:

1. Extract business-relevant terms
2. Map to closest `BusinessType` enum
3. If no match: select `OTHER` and store the description in `UserProfileEntity`

#### Edge Cases

- **"Other" selected**: Voice asks "Sema biashara yako" (Describe your business). Stores free-text description.
- **Long hesitation** (>15s): "Bado? Gusa picha ya biashara yako." (Still there? Tap your business picture.)
- **Multiple businesses**: After first selection, ask "Una biashara nyingine pia?" (Do you have another business too?) — can add secondary.

---

### Step 3: Voice Calibration (~30 seconds)

#### Purpose
- Record wake word sample for personalized voice recognition
- Test that the system understands the user's speech
- Build initial voice profile for accent/dialect adaptation

#### Voice Prompts & Flow

```
┌─────────────────────────────────────────────┐
│                                             │
│  🎤 "Sasa nitakusikiliza. Sema: Msaidizi"  │
│     (Now I'll listen to you. Say: Msaidizi) │
│                                             │
│         ┌─────────────┐                     │
│         │             │                     │
│         │   🎙️ BIG   │                     │
│         │  MICROPHONE │                     │
│         │             │                     │
│         └─────────────┘                     │
│                                             │
│     [Pulsing animation when listening]      │
│                                             │
│     ✅ "Nimekusikiliza!" (I heard you!)     │
│                                             │
│  🎤 "Sema: Nimeuza nyanya"                  │
│     (Say: I sold tomatoes)                  │
│                                             │
│         ┌─────────────┐                     │
│         │  🎙️ BIG    │                     │
│         └─────────────┘                     │
│                                             │
│     ✅ "Sawa! Nakuelewa vizuri!"            │
│        (Great! I understand you well!)       │
│                                             │
└─────────────────────────────────────────────┘
```

#### Calibration Steps

| Phase | Prompt | Duration | Purpose |
|-------|--------|----------|---------|
| 3a | "Sema: Msaidizi" | 5 sec | Wake word sample |
| 3b | Listen + process | 3 sec | Voice profile creation |
| 3c | "Sema: Nimeuza nyanya" | 5 sec | Business vocabulary test |
| 3d | Listen + process | 5 sec | Accent/dialect detection |
| 3e | Confirmation | 3 sec | "Nakuelewa vizuri!" |

#### Voice Recognition Adaptation

During calibration, the system:

1. **Records wake word** → stores in `UserProfileEntity.voiceProfile`
2. **Measures speech characteristics**:
   - Speaking rate (words per minute)
   - Pitch range
   - Common accent markers (e.g., Luo speaker drops /r/, Kikuyu speaker adds /ĩ/)
3. **Tests with business sentence** → validates understanding of domain vocabulary
4. **Stores adaptation parameters** for on-device ASR model

#### Fallback: No Microphone / ASR Failure

If microphone unavailable or ASR fails:

```
🔊 "Sikusikii. Tumia skrini badala yake."
   (I can't hear you. Use the screen instead.)

[Show: "Gusa na useme" — Tap and speak (visual-only mode)]
```

User can complete onboarding by tapping only. Voice features are marked as "pending calibration" and retried later.

#### Success Screen

```
┌─────────────────────────────────────────────┐
│                                             │
│                 🎉 🎉 🎉                    │
│                                             │
│       "Msaidizi wako tayari!"               │
│       (Your Msaidizi is ready!)             │
│                                             │
│    ┌─────────────────────────────┐          │
│    │  👤 Jina: [User Name]      │          │
│    │  🏪 Biashara: [Type]       │          │
│    │  🌍 Lugha: Kiswahili       │          │
│    │  🎤 Sauti: ✅ Imewekwa     │          │
│    └─────────────────────────────┘          │
│                                             │
│         ┌──────────────────┐                │
│         │  🚀 Anza Kazi!   │                │
│         │   (Start Work!)  │                │
│         └──────────────────┘                │
│                                             │
└─────────────────────────────────────────────┘
```

Voice: "Karibu biashara yako! Mimi ni Msaidizi wako. Sema 'Msaidizi' nikusaidie."
(Welcome to your business! I'm your assistant. Say 'Msaidizi' and I'll help you.)

---

### Complete Onboarding Voice Script (Full Sequence)

```
[APP LAUNCH — Step 1]
🔊 "Karibu Msaidizi! Chagua lugha yako."
   → [User taps Kiswahili]
🔊 "Sawa! Tutaongea Kiswahili."

[Step 2]
🔊 "Biashara yako ni ipi? Gusa picha au sema."
   → [User taps 🥬 Mama Mboga]
🔊 "Sawa! Mama Mboga. Twende!"

[Step 3a]
🔊 "Sasa nitakusikiliza. Sema: Msaidizi."
   → [User says "Msaidizi"]
🔊 "Nimekusikiliza!"

[Step 3b]
🔊 "Sema: Nimeuza nyanya."
   → [User says "Nimeuza nyanya"]
🔊 "Sawa! Nakuelewa vizuri!"

[Complete]
🔊 "Msaidizi wako tayari! Karibu biashara yako. Sema 'Msaidizi' nikusaidie."
```

**Total voice time**: ~45 seconds  
**User interaction time**: ~35 seconds (taps + speech)  
**Total onboarding time**: ~80 seconds ✅ (<2 minutes)

---

## 3. Localization Architecture

### Layered Localization Model

```
┌─────────────────────────────────────────────────────────────────┐
│                    LOCALIZATION LAYERS                          │
├─────────────────────────────────────────────────────────────────┤
│ Layer 1: UI Strings (strings.xml)                              │
│   → Primary: Swahili (values-sw/)                              │
│   → Fallback: English (values/)                                │
│   → All UI text, labels, buttons, menus                        │
├─────────────────────────────────────────────────────────────────┤
│ Layer 2: Voice Prompts (audio assets)                          │
│   → Bundled: Swahili, English, Sheng                           │
│   → Downloadable: 14+ local languages                          │
│   → Format: OGG Vorbis, 16kHz, mono                            │
├─────────────────────────────────────────────────────────────────┤
│ Layer 3: Business Term Glossary (JSON)                         │
│   → 500+ business terms mapped across languages                │
│   → Used by CodeSwitchHandler + LanguageDetector               │
│   → Supports code-switching at term level                      │
├─────────────────────────────────────────────────────────────────┤
│ Layer 4: ASR Language Models (on-device)                       │
│   → Primary: Swahili acoustic model                            │
│   → Secondary: English                                         │
│   → Tertiary: Sheng (code-switch model)                        │
│   → Local languages: Phrase-spotting models                    │
├─────────────────────────────────────────────────────────────────┤
│ Layer 5: TTS Voice Profiles                                    │
│   → Swahili female voice (primary — warm, market-woman tone)   │
│   → Swahili male voice (secondary)                             │
│   → English neutral voice (fallback)                           │
│   → Sheng voice (youth, informal)                              │
└─────────────────────────────────────────────────────────────────┘
```

### Android Resource Structure

```
app/src/main/res/
├── values/
│   └── strings.xml                    ← English (default/fallback)
├── values-sw/
│   └── strings.xml                    ← Kiswahili (primary)
├── values-sw-rKE/
│   └── strings.xml                    ← Kenyan Swahili variants
├── values-sw-rTZ/
│   └── strings.xml                    ← Tanzanian Swahili variants
├── values-sheng/
│   └── strings.xml                    ← Sheng (unofficial locale code)
│
├── raw/
│   ├── voice_onboarding_sw.ogg        ← Swahili onboarding prompts
│   ├── voice_onboarding_en.ogg        ← English onboarding prompts
│   ├── voice_onboarding_sheng.ogg     ← Sheng onboarding prompts
│   ├── voice_confirmations_sw.ogg     ← Confirmation phrases
│   ├── voice_errors_sw.ogg            ← Error messages
│   ├── voice_business_sw.ogg          ← Business term pronunciations
│   └── voice_greetings_sw.ogg         ← Time-of-day greetings
│
├── raw-local/                         ← Downloadable language packs
│   ├── voice_luo/
│   ├── voice_kikuyu/
│   ├── voice_kalenjin/
│   ├── voice_kamba/
│   ├── voice_luhya/
│   ├── voice_meru/
│   ├── voice_kisii/
│   ├── voice_taita/
│   ├── voice_maasai/
│   ├── voice_turkana/
│   ├── voice_somali/
│   ├── voice_oromo/
│   ├── voice_amharic/
│   └── voice_hausa/
│
└── assets/
    └── localization/
        ├── business_terms.json        ← Cross-language term glossary
        ├── sheng_dictionary.json      ← Sheng → Swahili/English mappings
        ├── voice_prompts_catalog.json ← Prompt metadata & file mappings
        └── dialect_markers.json       ← Accent/dialect detection patterns
```

### strings.xml — Primary (Swahili)

```xml
<!-- values-sw/strings.xml -->
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Msaidizi</string>
    <string name="app_tagline">Mshirika wako wa biashara</string>

    <!-- Onboarding -->
    <string name="onboard_welcome">Karibu Msaidizi!</string>
    <string name="onboard_choose_language">Chagua lugha yako</string>
    <string name="onboard_language_sw">Kiswahili</string>
    <string name="onboard_language_en">English</string>
    <string name="onboard_language_sheng">Sheng</string>
    <string name="onboard_business_prompt">Biashara yako ni ipi?</string>
    <string name="onboard_voice_prompt">Sema: Msaidizi</string>
    <string name="onboard_voice_test">Sema: Nimeuza nyanya</string>
    <string name="onboard_complete">Msaidizi wako tayari!</string>
    <string name="onboard_start">Anza Kazi!</string>

    <!-- Business Types -->
    <string name="biz_mama_mboga">Mama Mboga</string>
    <string name="biz_duka">Duka</string>
    <string name="biz_boda_boda">Boda Boda</string>
    <string name="biz_fundi">Fundi</string>
    <string name="biz_salon">Salon / Kinyozi</string>
    <string name="biz_mkulima">Mkulima</string>
    <string name="biz_mvuvi">Mvuvi</string>
    <string name="biz_mpesa">M-Pesa</string>
    <string name="biz_mama_lishe">Mama Lishe</string>
    <string name="biz_mjengo">Mjengo</string>
    <string name="biz_chama">Chama</string>
    <string name="biz_nyingine">Nyingine</string>

    <!-- Navigation -->
    <string name="nav_home">Nyumbani</string>
    <string name="nav_sales">Mauzo</string>
    <string name="nav_stock">Stock</string>
    <string name="nav_reports">Ripoti</string>
    <string name="nav_settings">Mipangilio</string>

    <!-- Common Actions -->
    <string name="action_save">Hifadhi</string>
    <string name="action_cancel">Ghairi</string>
    <string name="action_delete">Futa</string>
    <string name="action_confirm">Thibitisha</string>
    <string name="action_retry">Jaribu tena</string>
    <string name="action_undo">Tendua</string>

    <!-- Dashboard -->
    <string name="dash_greeting_morning">Habari za asubuhi, %s!</string>
    <string name="dash_greeting_afternoon">Habari za mchana, %s!</string>
    <string name="dash_greeting_evening">Habari za jioni, %s!</string>
    <string name="dash_today_sales">Mauzo ya leo</string>
    <string name="dash_today_expenses">Matumizi ya leo</string>
    <string name="dash_today_profit">Faida ya leo</string>
    <string name="dash_transactions">Miamala</string>

    <!-- Sales -->
    <string name="sale_add">Ongeza Mauzo</string>
    <string name="sale_product">Bidhaa</string>
    <string name="sale_quantity">Kiasi</string>
    <string name="sale_price">Bei</string>
    <string name="sale_total">Jumla</string>
    <string name="sale_payment_cash">Pesa Taslimu</string>
    <string name="sale_payment_mpesa">M-Pesa</string>
    <string name="sale_payment_credit">Deni</string>
    <string name="sale_confirm">Thibitisha Mauzo</string>
    <string name="sale_success">Mauzo yamehifadhiwa!</string>

    <!-- Stock/Inventory -->
    <string name="stock_current">Stock ya Sasa</string>
    <string name="stock_low">Stock Ndogo!</string>
    <string name="stock_add">Ongeza Stock</string>
    <string name="stock_reorder">Agiza Zaidi</string>
    <string name="stock_spoilage">Kuharibika</string>

    <!-- Financial -->
    <string name="finance_profit">Faida</string>
    <string name="finance_loss">Hasara</string>
    <string name="finance_debt">Deni</string>
    <string name="finance_loan">Mkopo</string>
    <string name="finance_savings">Akiba</string>
    <string name="finance_interest">Riba</string>
    <string name="finance_insurance">Bima</string>
    <string name="finance_chama">Chama</string>
    <string name="finance_mpesa_balance">Salio la M-Pesa</string>

    <!-- Customers -->
    <string name="customer_mteja">Mteja</string>
    <string name="customer_list">Orodha ya Wateja</string>
    <string name="customer_debt">Deni la Mteja</string>
    <string name="customer_payment">Lipa Deni</string>

    <!-- Time -->
    <string name="time_today">Leo</string>
    <string name="time_yesterday">Jana</string>
    <string name="time_tomorrow">Kesho</string>
    <string name="time_this_week">Wiki hii</string>
    <string name="time_this_month">Mwezi huu</string>
    <string name="time_morning">Asubuhi</string>
    <string name="time_afternoon">Mchana</string>
    <string name="time_evening">Jioni</string>

    <!-- Voice States -->
    <string name="voice_listening">Ninasikiliza…</string>
    <string name="voice_thinking">Ninafikiri…</string>
    <string name="voice_speaking">Nasema…</string>
    <string name="voice_tap_to_speak">Gusa na useme</string>
    <string name="voice_not_understood">Sikuelewi. Jaribu tena.</string>
    <string name="voice_error">Kuna tatizo. Jaribu tena.</string>

    <!-- Errors -->
    <string name="error_generic">Kuna kitu kimeenda vibaya. Jaribu tena.</string>
    <string name="error_network">Hakuna intaneti. Inafanya kazi bila mtandao.</string>
    <string name="error_microphone">Inahitaji ruhusa ya maikrofoni.</string>
    <string name="error_voice_failed">Sikusikii. Tumia skrini.</string>
    <string name="error_save_failed">Imeshindwa kuhifadhi. Jaribu tena.</string>

    <!-- Agriculture -->
    <string name="agri_harvest">Mavuno</string>
    <string name="agri_market_price">Bei ya Soko</string>
    <string name="agri_seeds">Mbegu</string>
    <string name="agri_fertilizer">Mbolea</string>
    <string name="agri_season">Msimu</string>
    <string name="agri_crop">Mazao</string>

    <!-- Chama -->
    <string name="chama_group">Kikundi</string>
    <string name="chama_contribution">Mchango</string>
    <string name="chama_payout">Malipo</string>
    <string name="chama_members">Wanachama</string>
    <string name="chama_cycle">Mzunguko</string>

    <!-- Settings -->
    <string name="settings_language">Lugha</string>
    <string name="settings_voice">Sauti</string>
    <string name="settings_notifications">Arifa</string>
    <string name="settings_help">Msaada</string>
    <string name="settings_about">Kuhusu</string>
</resources>
```

### strings.xml — English (Fallback)

```xml
<!-- values/strings.xml (English — already exists, extend) -->
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Msaidizi</string>
    <string name="app_tagline">Your AI Business Partner</string>

    <!-- Onboarding -->
    <string name="onboard_welcome">Welcome to Msaidizi!</string>
    <string name="onboard_choose_language">Choose your language</string>
    <string name="onboard_business_prompt">What is your business?</string>
    <string name="onboard_voice_prompt">Say: Msaidizi</string>
    <string name="onboard_voice_test">Say: I sold tomatoes</string>
    <string name="onboard_complete">Your Msaidizi is ready!</string>
    <string name="onboard_start">Start Working!</string>

    <!-- ... (existing English strings remain) ... -->
</resources>
```

### Language Selection Logic

```kotlin
// LanguageResolver.kt — Determines which locale to apply
object LanguageResolver {

    /**
     * Resolve the app locale from user's onboarding choice.
     * Handles the mapping between user-facing language names
     * and Android resource qualifiers.
     */
    fun resolveLocale(languageChoice: String): Locale {
        return when (languageChoice.lowercase()) {
            "sw", "kiswahili", "swahili" -> Locale("sw", "KE")  // Swahili, Kenya
            "en", "english" -> Locale("en", "KE")               // English, Kenya
            "sheng" -> Locale("sw", "KE")                       // Sheng uses Swahili base
                // Sheng has no ISO code; we use sw-KE as base
                // and handle Sheng-specific terms via CodeSwitchHandler
            else -> Locale("sw", "KE")                          // Default to Swahili
        }
    }

    /**
     * For Sheng mode: still use Swahili strings.xml but
     * enable code-switching in TTS and use Sheng prompts
     * for voice responses.
     */
    fun isShengMode(languageChoice: String): Boolean {
        return languageChoice.lowercase() == "sheng"
    }
}
```

---

## 4. Swahili Voice Prompt Library

### Prompt Catalog Structure

Each prompt is stored as a JSON entry in `assets/localization/voice_prompts_catalog.json`:

```json
{
  "id": "onboard_welcome",
  "text_sw": "Karibu Msaidizi! Chagua lugha yako.",
  "text_en": "Welcome to Msaidizi! Choose your language.",
  "text_sheng": "Karibu Msaidizi! Pick lugha yako, boss.",
  "audio_sw": "raw/voice_onboarding_sw.ogg#0",
  "audio_en": "raw/voice_onboarding_en.ogg#0",
  "audio_sheng": "raw/voice_onboarding_sheng.ogg#0",
  "category": "onboarding",
  "priority": "critical",
  "context": "First screen on app launch"
}
```

### Complete Prompt Library (200+ Prompts)

#### Category 1: Onboarding & Setup (25 prompts)

| # | Prompt ID | Swahili | English | Category |
|---|-----------|---------|---------|----------|
| 1 | `onboard_welcome` | Karibu Msaidizi! Chagua lugha yako. | Welcome to Msaidizi! Choose your language. | Onboarding |
| 2 | `onboard_lang_sw` | Sawa! Tutaongea Kiswahili. | Okay! We'll speak Swahili. | Onboarding |
| 3 | `onboard_lang_en` | Great! We'll use English. | Great! We'll use English. | Onboarding |
| 4 | `onboard_lang_sheng` | Poa! Tutaongea Sheng, boss. | Cool! We'll speak Sheng, boss. | Onboarding |
| 5 | `onboard_business_ask` | Biashara yako ni ipi? Gusa picha au sema. | What is your business? Tap the picture or speak. | Onboarding |
| 6 | `onboard_business_confirm` | Sawa! %s. Twende! | Okay! %s. Let's go! | Onboarding |
| 7 | `onboard_voice_intro` | Sasa nitakusikiliza. Sema: Msaidizi. | Now I'll listen to you. Say: Msaidizi. | Onboarding |
| 8 | `onboard_voice_heard` | Nimekusikiliza! | I heard you! | Onboarding |
| 9 | `onboard_voice_test` | Sema: Nimeuza nyanya. | Say: I sold tomatoes. | Onboarding |
| 10 | `onboard_voice_success` | Sawa! Nakuelewa vizuri! | Great! I understand you well! | Onboarding |
| 11 | `onboard_voice_fail` | Sikusikii. Tumia skrini badala yake. | I can't hear you. Use the screen instead. | Onboarding |
| 12 | `onboard_complete` | Msaidizi wako tayari! | Your Msaidizi is ready! | Onboarding |
| 13 | `onboard_start` | Anza Kazi! | Start Working! | Onboarding |
| 14 | `onboard_welcome_msg` | Karibu biashara yako! Mimi ni Msaidizi wako. | Welcome to your business! I'm your assistant. | Onboarding |
| 15 | `onboard_wake_word` | Sema 'Msaidizi' nikusaidie. | Say 'Msaidizi' and I'll help you. | Onboarding |
| 16 | `onboard_nudge_language` | Bado? Chagua lugha. | Still there? Choose a language. | Onboarding |
| 17 | `onboard_nudge_business` | Bado? Gusa picha ya biashara yako. | Still there? Tap your business picture. | Onboarding |
| 18 | `onboard_voice_retry` | Jaribu tena. Sema: Msaidizi. | Try again. Say: Msaidizi. | Onboarding |
| 19 | `onboard_other_describe` | Sema biashara yako. | Describe your business. | Onboarding |
| 20 | `onboard_other_saved` | Sawa! Nimerekodi biashara yako. | Okay! I've recorded your business. | Onboarding |
| 21 | `onboard_ask_name` | Jina lako nani? | What is your name? | Onboarding |
| 22 | `onboard_name_saved` | Sawa, %s! | Okay, %s! | Onboarding |
| 23 | `onboard_secondary_biz` | Una biashara nyingine pia? | Do you have another business too? | Onboarding |
| 24 | `onboard_permissions` | Nahitaji ruhusa ya maikrofoni. | I need microphone permission. | Onboarding |
| 25 | `onboard_permissions_granted` | Asante! Sasa naweza kukusikiliza. | Thanks! Now I can hear you. | Onboarding |

#### Category 2: Greetings & Time (20 prompts)

| # | Prompt ID | Swahili | English | Context |
|---|-----------|---------|---------|---------|
| 26 | `greet_morning` | Habari za asubuhi, %s! | Good morning, %s! | 6am-12pm |
| 27 | `greet_afternoon` | Habari za mchana, %s! | Good afternoon, %s! | 12pm-5pm |
| 28 | `greet_evening` | Habari za jioni, %s! | Good evening, %s! | 5pm-9pm |
| 29 | `greet_night` | Habari za usiku, %s! | Good night, %s! | 9pm-6am |
| 30 | `greet_first_today` | Karibu leo! Leo ni siku mpya ya biashara. | Welcome today! Today is a new business day. | First interaction |
| 31 | `greet_return` | Karibu tena, %s! Umekosa siku %d. | Welcome back, %s! You've missed %d days. | Returning user |
| 32 | `greet_weekend` | Wikiendi njema! Biashara ikoje? | Good weekend! How's business? | Saturday/Sunday |
| 33 | `greet_generic` | Habari, %s! | Hello, %s! | Default |
| 34 | `time_now` | Sasa ni %s. | It's %s now. | Time query |
| 35 | `time_today` | Leo ni %s. | Today is %s. | Date query |
| 36 | `time_market_day` | Leo ni soko la %s! | Today is %s market day! | Market day |
| 37 | `farewell_goodbye` | Kwaheri, %s! Nitakuona kesho. | Goodbye, %s! See you tomorrow. | Session end |
| 38 | `farewell_evening` | Usiku mwema, %s! Pumzika. | Good night, %s! Rest well. | Evening |
| 39 | `greet_howru` | Biashara ikoje leo? | How's business today? | Check-in |
| 40 | `greet_positive` | Vizuri sana! Biashara inaenda poa! | Very good! Business is going well! | Positive response |
| 41 | `greet_encourage` | Sawa! Kila siku ni fursa mpya. | Okay! Every day is a new opportunity. | Encouragement |
| 42 | `greet_ramadan` | Ramadan Kareem, %s! | Ramadan Kareem, %s! | Ramadan |
| 43 | `greet_christmas` | Krismasi njema, %s! | Merry Christmas, %s! | Christmas |
| 44 | `greet_easter` | Pasaka njema, %s! | Happy Easter, %s! | Easter |
| 45 | `greet_new_year` | Mwaka Mpya mwema, %s! | Happy New Year, %s! | New Year |

#### Category 3: Sales & Transactions (30 prompts)

| # | Prompt ID | Swahili | English |
|---|-----------|---------|---------|
| 46 | `sale_add` | Ongeza mauzo mpya. | Add new sale. |
| 47 | `sale_what_product` | Umeuza nini? | What did you sell? |
| 48 | `sale_how_many` | Kiasi gani? | How many? |
| 49 | `sale_what_price` | Bei ngapi? | What price? |
| 50 | `sale_confirm` | Thibitisha: %s, %s kwa %s. Sawa? | Confirm: %s, %s for %s. Correct? |
| 51 | `sale_success` | Mauzo yamehifadhiwa! %s imerekodwa. | Sale saved! %s recorded. |
| 52 | `sale_undo` | Umefuta mauzo. | You've deleted the sale. |
| 53 | `sale_payment_method` | Lipa vipi? Pesa taslimu, M-Pesa, au deni? | How to pay? Cash, M-Pesa, or credit? |
| 54 | `sale_cash` | Sawa, pesa taslimu. | Okay, cash. |
| 55 | `sale_mpesa` | Sawa, M-Pesa. | Okay, M-Pesa. |
| 56 | `sale_credit` | Sawa, deni. Nimerekodi. | Okay, credit. I've recorded it. |
| 57 | `sale_mpesa_confirm` | Lipa na M-Pesa. Nunua bidhaa %s. | Pay with M-Pesa. Buy product %s. |
| 58 | `sale_daily_total` | Mauzo ya leo ni %s. | Today's sales are %s. |
| 59 | `sale_weekly_total` | Mauzo ya wiki hii ni %s. | This week's sales are %s. |
| 60 | `sale_monthly_total` | Mauzo ya mwezi huu ni %s. | This month's sales are %s. |
| 61 | `sale_no_sales` | Bado haujauza chochote leo. | You haven't sold anything yet today. |
| 62 | `sale_best_product` | Bidhaa bora leo ni %s. | Best product today is %s. |
| 63 | `sale_bulk_record` | Nimerekodi mauzo mengi. | I've recorded multiple sales. |
| 64 | `sale_price_suggestion` | Bei ya soko ni %s. Unauza kwa %s? | Market price is %s. Selling at %s? |
| 65 | `sale_discount` | Punguzo la %s? | Discount of %s? |
| 66 | `sale_receipt` | Unataka risiti? | Want a receipt? |
| 67 | `sale_receipt_sent` | Risiti imetumwa kwa %s. | Receipt sent to %s. |
| 68 | `sale_refund` | Umerefund %s kwa %s. | You've refunded %s for %s. |
| 69 | `sale_exchange` | Kubadilisha bidhaa? | Exchange product? |
| 70 | `sale_customer_new` | Mteja mpya? Jina lake nani? | New customer? What's their name? |
| 71 | `sale_customer_return` | Karibu tena, %s! | Welcome back, %s! |
| 72 | `sale_customer_credit_check` | %s ana deni la %s. Anaweza kununua? | %s has debt of %s. Can they buy? |
| 73 | `sale_target_met` | Umefikia lengo la leo! Hongera! | You've met today's target! Congratulations! |
| 74 | `sale_target_progress` | Umefikia %s ya lengo la leo. | You've reached %s of today's target. |
| 75 | `sale_peak_hour` | Sasa ni wakati wa mauzo mengi! | Now is peak sales time! |

#### Category 4: Stock & Inventory (25 prompts)

| # | Prompt ID | Swahili | English |
|---|-----------|---------|---------|
| 76 | `stock_check` | Stock ya %s ni %s. | Stock of %s is %s. |
| 77 | `stock_low` | Onyo! Stock ya %s ni ndogo. Iliyobaki: %s. | Warning! Stock of %s is low. Remaining: %s. |
| 78 | `stock_out` | %s imeisha! Agiza zaidi. | %s is finished! Order more. |
| 79 | `stock_add` | Ongeza stock ya %s. Kiasi gani? | Add stock of %s. How much? |
| 80 | `stock_added` | Stock ya %s imeongezwa kwa %s. | Stock of %s increased by %s. |
| 81 | `stock_reorder` | Agiza %s. Umekuwa ukiuza %s kwa siku. | Order %s. You've been selling %s per day. |
| 82 | `stock_spoilage` | %s imeharibika. Kiasi: %s. | %s is spoiled. Quantity: %s. |
| 83 | `stock_expiry` | %s inakaribia kuisha muda. Tarehe: %s. | %s is expiring soon. Date: %s. |
| 84 | `stock_price_change` | Bei ya %s imebadilika. Zamani: %s, Sasa: %s. | Price of %s changed. Was: %s, Now: %s. |
| 85 | `stock_new_product` | Bidhaa mpya: %s. Bei ya kununua: %s, Bei ya kuuza: %s. | New product: %s. Buy price: %s, Sell price: %s. |
| 86 | `stock_list` | Una bidhaa %s. | You have %s products. |
| 87 | `stock_value` | Thamani ya stock yako ni %s. | Your stock value is %s. |
| 88 | `stock_turnover` | Stock yako inazunguka kila siku %s. | Your stock turns over every %s days. |
| 89 | `stock_slow_movers` | %s haijauzwa kwa siku %d. Fikiria kupunguza bei. | %s hasn't sold in %d days. Consider reducing price. |
| 90 | `stock_fast_movers` | %s inauza haraka! Agiza zaidi. | %s sells fast! Order more. |
| 91 | `stock_reorder_all` | Agiza: %s | Order: %s |
| 92 | `stock_supplier` | Mnunuzi wa %s ni %s. | Supplier of %s is %s. |
| 93 | `stock_delivered` | %s imefika! Angalia kabla ya kusaini. | %s has arrived! Check before signing. |
| 94 | `stock_shortage` | %s ilikuwa chini! Ulitakiwa %s, umepewa %s. | %s was short! Expected %s, got %s. |
| 95 | `stock_price_alert` | Bei ya %s imepanda sokoni! | Price of %s has risen in the market! |
| 96 | `stock_best_margin` | Faida kubwa ni %s: %s kwa bidhaa. | Highest margin is %s: %s per product. |
| 97 | `stock_count_prompt` | Hesabu stock ya %s. | Count stock of %s. |
| 98 | `stock_count_done` | Stock imerahisishwa! | Stock counted! |
| 99 | `stock_discrepancy` | Tofauti ya %s katika %s. | Discrepancy of %s in %s. |
| 100 | `stock_transfer` | Hamisha %s kutoka %s hadi %s. | Transfer %s from %s to %s. |

#### Category 5: Financial & Accounting (30 prompts)

| # | Prompt ID | Swahili | English |
|---|-----------|---------|---------|
| 101 | `finance_profit_today` | Faida ya leo ni %s. | Today's profit is %s. |
| 102 | `finance_loss_today` | Hasara ya leo ni %s. | Today's loss is %s. |
| 103 | `finance_expense_add` | Ongeza gharama. Ni ya nini? | Add expense. What for? |
| 104 | `finance_expense_categories` | Usafiri, kodi, stock, maji, nyingine. | Transport, rent, stock, utilities, other. |
| 105 | `finance_expense_saved` | Gharama ya %s imerekodwa: %s. | Expense for %s recorded: %s. |
| 106 | `finance_weekly_summary` | Wiki hii: Mauzo %s, Gharama %s, Faida %s. | This week: Sales %s, Expenses %s, Profit %s. |
| 107 | `finance_monthly_summary` | Mwezi huu: Mauzo %s, Gharama %s, Faida %s. | This month: Sales %s, Expenses %s, Profit %s. |
| 108 | `finance_break_even` | Umefikia break-even. Faida baada ya %s. | You've reached break-even. Profit after %s. |
| 109 | `finance_cash_flow` | Pesa ulizonazo sasa: %s. | Cash you have now: %s. |
| 110 | `finance_debt_owed_to_you` | Wateja wanakudai %s jumla. | Customers owe you %s total. |
| 111 | `finance_debt_you_owe` | Unadaiwa %s. | You owe %s. |
| 112 | `finance_debt_reminder` | %s ana deni la %s. Tangu %s. | %s has debt of %s. Since %s. |
| 113 | `finance_debt_payment` | %s amelipa %s ya deni. | %s paid %s of debt. |
| 114 | `finance_debt_settled` | Deni la %s limeisha! | %s's debt is cleared! |
| 115 | `finance_loan_offer` | Mkopo wa %s unapatikana. Riba: %s. | Loan of %s available. Interest: %s. |
| 116 | `finance_loan_repayment` | Mkopo: Lipa %s kabla ya %s. | Loan: Pay %s before %s. |
| 117 | `finance_savings_goal` | Lengo la akiba: %s. Umefikia %s. | Savings goal: %s. You've reached %s. |
| 118 | `finance_mpesa_balance` | Salio la M-Pesa ni %s. | M-Pesa balance is %s. |
| 119 | `finance_mpesa_received` | Umepokea %s kutoka %s kwa M-Pesa. | You received %s from %s via M-Pesa. |
| 120 | `finance_mpesa_sent` | Umetuma %s kwa %s kupitia M-Pesa. | You sent %s to %s via M-Pesa. |
| 121 | `finance_tax_reminder` | Kodi ya biashara inakaribia. Tarehe: %s. | Business tax is due. Date: %s. |
| 122 | `finance_insurance` | Bima ya biashara yako ni %s. | Your business insurance is %s. |
| 123 | `finance_profit_trend` | Faida yako inaongezeka! %s wiki hii. | Your profit is increasing! %s this week. |
| 124 | `finance_loss_trend` | Faida yako imepungua. Angalia gharama. | Your profit has decreased. Check expenses. |
| 125 | `finance_best_day` | Siku bora zaidi ilikuwa %s: Faida %s. | Best day was %s: Profit %s. |
| 126 | `finance_worst_day` | Siku mbaya zaidi ilikuwa %s: Hasara %s. | Worst day was %s: Loss %s. |
| 127 | `finance_comparison` | Wiki hii ni %s kuliko wiki iliyopita. | This week is %s than last week. |
| 128 | `finance_advice_save` | Jaribu kuweka akiba %s kwa siku. | Try to save %s per day. |
| 129 | `finance_advice_reduce` | Gharama za %s ni kubwa. Fikiria kupunguza. | %s expenses are high. Consider reducing. |
| 130 | `finance_goal_progress` | Lengo la mwezi: %s. Umefikia %s. | Monthly goal: %s. You've reached %s. |

#### Category 6: Customers (20 prompts)

| # | Prompt ID | Swahili | English |
|---|-----------|---------|---------|
| 131 | `customer_add` | Ongeza mteja mpya. Jina lake? | Add new customer. Their name? |
| 132 | `customer_added` | %s ameongezwa kama mteja. | %s added as customer. |
| 133 | `customer_phone` | Nambari ya simu ya %s? | %s's phone number? |
| 134 | `customer_history` | %s amenunua mara %s jumla %s. | %s has purchased %s times, total %s. |
| 135 | `customer_segment_vip` | %s ni mteja VIP! Amekuwa akinunua %s. | %s is a VIP customer! They've been buying %s. |
| 136 | `customer_segment_regular` | %s ni mteja wa kawaida. | %s is a regular customer. |
| 137 | `customer_segment_lapsed` | %s hajaonekana kwa siku %d. | %s hasn't visited in %d days. |
| 138 | `customer_reactivate` | Tuma ujumbe kwa %s? "Karibu tena!" | Send message to %s? "Welcome back!" |
| 139 | `customer_top_products` | %s anapenda: %s. | %s likes: %s. |
| 140 | `customer_birthday` | %s ana birthday leo! | %s has a birthday today! |
| 141 | `customer_list` | Una wateja %s. | You have %s customers. |
| 142 | `customer_search` | Nani unayemtafuta? | Who are you looking for? |
| 143 | `customer_not_found` | Sijampata mteja huyo. | I didn't find that customer. |
| 144 | `customer_credit_limit` | %s amefikia kikomo cha deni. | %s has reached credit limit. |
| 145 | `customer_visit_today` | Wateja %s wamekuja leo. | %s customers visited today. |
| 146 | `customer_thank_you` | Asante %s kwa kununua! | Thank you %s for buying! |
| 147 | `customer_feedback` | %s alisema nini kuhusu bidhaa? | What did %s say about the product? |
| 148 | `customer_location` | %s yuko wapi? | Where is %s? |
| 149 | `customer_referral` | %s alitaja rafiki yake, %s. | %s referred their friend, %s. |
| 150 | `customer_inactive_alert` | Mteja %s hajaonekana mwezi mzima! | Customer %s hasn't visited in a month! |

#### Category 7: Agriculture (20 prompts)

| # | Prompt ID | Swahili | English |
|---|-----------|---------|---------|
| 151 | `agri_weather_today` | Hali ya hewa leo: %s. | Weather today: %s. |
| 152 | `agri_weather_rain` | Mvua inatarajiwa. Hifadhi mazao! | Rain expected. Protect crops! |
| 153 | `agri_weather_drought` | Ukame unaweza kuja. Maji ya kutosha? | Drought may come. Enough water? |
| 154 | `agri_market_price` | Bei ya %s sokoni ni %s. | Price of %s in market is %s. |
| 155 | `agri_harvest_ready` | %s iko tayari kuvuna! | %s is ready to harvest! |
| 156 | `agri_harvest_record` | Umepanda %s kg za %s. | You harvested %s kg of %s. |
| 157 | `agri_season_planting` | Msimu wa kupanda umeanza! Panda %s. | Planting season has started! Plant %s. |
| 158 | `agri_season_harvest` | Msimu wa kuvuna! | Harvest season! |
| 159 | `agri_fertilizer` | Mbolea ya %s: Tumia %s kwa ekari. | Fertilizer for %s: Use %s per acre. |
| 160 | `agri_pest_alert` | Onyo: %s imeonekana kwenye mashamba! | Warning: %s spotted in fields! |
| 161 | `agri_storage` | Hifadhi %s mahali pakavu na baridi. | Store %s in a dry, cool place. |
| 162 | `agri_transport` | Usafiri wa %s kutoka shamba: %s. | Transport of %s from farm: %s. |
| 163 | `agri_cooperative` | Ushirika wako: %s wanachama. | Your cooperative: %s members. |
| 164 | `agri_subsidy` | Ruzuku ya mbolea inapatikana. Omba! | Fertilizer subsidy available. Apply! |
| 165 | `agri_irrigation` | Umwagiliaji: Maji ya kutosha kwa %s siku. | Irrigation: Enough water for %s days. |
| 166 | `agri_crop_health` | Mazao yako yana afya nzuri! | Your crops are healthy! |
| 167 | `agri_price_forecast` | Bei ya %s inaweza kupanda %s wiki ijayo. | Price of %s may rise %s next week. |
| 168 | `agri_sell_now` | Sasa ni wakati mzuri wa kuuza %s! | Now is a good time to sell %s! |
| 169 | `agri_wait_sell` | Subiri! Bei ya %s inaweza kupanda. | Wait! Price of %s may rise. |
| 170 | `agri_input_cost` | Gharama za pembejeo: %s. | Input costs: %s. |

#### Category 8: Chama & Savings Groups (20 prompts)

| # | Prompt ID | Swahili | English |
|---|-----------|---------|---------|
| 171 | `chama_create` | Unda chama mpya. Jina lake? | Create new chama. Its name? |
| 172 | `chama_created` | Chama %s imeundwa! | Chama %s created! |
| 173 | `chama_add_member` | Ongeza mwanachama. Jina lake? | Add member. Their name? |
| 174 | `chama_member_added` | %s ameongezwa kama mwanachama %s. | %s added as member %s. |
| 175 | `chama_contribute` | Mchango wa %s: %s. | Contribution for %s: %s. |
| 176 | `chama_contributed` | %s amechangia %s. Asante! | %s contributed %s. Thank you! |
| 177 | `chama_payout_due` | Zamu ya %s kupokea %s! | It's %s's turn to receive %s! |
| 178 | `chama_payout_done` | %s amepokea %s. Hongera! | %s received %s. Congratulations! |
| 179 | `chama_cycle_complete` | Mzunguko %s umekamilika! | Cycle %s complete! |
| 180 | `chama_balance` | Salio la chama: %s. | Chama balance: %s. |
| 181 | `chama_next_meeting` | Mkutano ujao: %s. | Next meeting: %s. |
| 182 | `chama_late_payment` | %s amechelewesha mchango. | %s has delayed contribution. |
| 183 | `chama_penalty` | Faini ya %s: %s. | Late fee for %s: %s. |
| 184 | `chama_total_saved` | Jumla ya akiba: %s. | Total saved: %s. |
| 185 | `chama_rotation` | Zamu ya sasa: %s. | Current turn: %s. |
| 186 | `chama_reminder` | Kumbusho: Mchango wa %s ni %s. | Reminder: Contribution for %s is %s. |
| 187 | `chama_report` | Ripoti ya chama: Wanachama %s, Jumla %s. | Chama report: %s members, Total %s. |
| 188 | `chama_dispute` | Kuna mgogoro. Ongea na mwenyekiti. | There's a dispute. Talk to the chairperson. |
| 189 | `chama_goal_met` | Lengo la chama limefikiwa! %s! | Chama goal reached! %s! |
| 190 | `chama_new_goal` | Lengo jipya la chama: %s. | New chama goal: %s. |

#### Category 9: Errors & System (20 prompts)

| # | Prompt ID | Swahili | English |
|---|-----------|---------|---------|
| 191 | `error_generic` | Kuna kitu kimeenda vibaya. Jaribu tena. | Something went wrong. Try again. |
| 192 | `error_network` | Hakuna intaneti. Inafanya kazi bila mtandao. | No internet. Working offline. |
| 193 | `error_microphone` | Sikusikii. Angalia maikrofoni. | I can't hear you. Check microphone. |
| 194 | `error_not_understood` | Sikuelewi. Jaribu tena kwa maneno rahisi. | I don't understand. Try simpler words. |
| 195 | `error_no_product` | Sijapata bidhaa hiyo. Sema jina lingine. | I didn't find that product. Say another name. |
| 196 | `error_no_customer` | Sijapata mteja huyo. | I didn't find that customer. |
| 197 | `error_save_failed` | Imeshindwa kuhifadhi. Jaribu tena. | Failed to save. Try again. |
| 198 | `error_permission` | Nahitaji ruhusa ya %s. | I need %s permission. |
| 199 | `error_voice_timeout` | Sikuenda kwa muda. Gusa skrini. | Timed out. Tap screen. |
| 200 | `error_low_battery` | Betari ni chini! Weka simu kwenye charger. | Battery is low! Plug in your phone. |
| 201 | `error_storage_full` | Hifadhi imejaa! Futa vitu vya zamani. | Storage is full! Delete old items. |
| 202 | `error_app_update` | Sasisha mpya inapatikana. Sasisha? | New update available. Update? |
| 203 | `error_sync_failed` | Usawazishaji umeshindwa. Jaribu baadaye. | Sync failed. Try later. |
| 204 | `error_data_corrupt` | Data imeharibika. Rejesha cheche? | Data corrupted. Restore backup? |
| 205 | `error_unknown_command` | Sikuelewi. Sema "Msaada" kuona chaguo. | I don't understand. Say "Help" to see options. |
| 206 | `error_rate_limit` | Pole! Nimechoka. Subiri sekunde chache. | Sorry! I'm tired. Wait a few seconds. |
| 207 | `error_server` | Seva iko busy. Jaribu tena baada ya muda. | Server is busy. Try again later. |
| 208 | `error_crash_recovery` | Nimerudi! Tulipotea wapi? | I'm back! Where did we leave off? |
| 209 | `error_offline_mode` | Uko offline. Kazi itahifadhiwa baadaye. | You're offline. Work will save later. |
| 210 | `error_unsupported` | Hii bado haijapatikana. Inakuja hivi karibuni! | This isn't available yet. Coming soon! |

#### Category 10: Help & Guidance (15 prompts)

| # | Prompt ID | Swahili | English |
|---|-----------|---------|---------|
| 211 | `help_intro` | Mimi ni Msaidizi. Naweza kukusaidia na mauzo, stock, na fedha. | I'm Msaidizi. I can help with sales, stock, and money. |
| 212 | `help_voice_commands` | Sema: "Nimeuza" kuuza, "Stock" kuangalia stock, "Faida" kuangalia faida. | Say: "I sold" to sell, "Stock" to check stock, "Profit" to check profit. |
| 213 | `help_examples` | Mfano: "Nimeuza nyanya kilo tano, elfu moja." | Example: "I sold five kilos of tomatoes, one thousand." |
| 214 | `help_tips` | Kidokezo: Sema wazi, polepole. | Tip: Speak clearly, slowly. |
| 215 | `help_shortcuts` | Sema "Leo" kuona mauzo ya leo. | Say "Today" to see today's sales. |
| 216 | `help_undo` | Sema "Tendua" kufuta kitu. | Say "Undo" to delete something. |
| 217 | `help_voice_only` | Sema chochote! Sisikilizi. | Say anything! I'm listening. |
| 218 | `help_keyboard` | Au andika ujumbe hapa chini. | Or type a message below. |
| 219 | `help_language_change` | Kubadilisha lugha: Nenda Mipangilio > Lugha. | Change language: Go to Settings > Language. |
| 220 | `help_business_change` | Kubadilisha biashara: Nenda Mipangilio > Biashara. | Change business: Go to Settings > Business. |
| 221 | `help_contact` | Una swali? Piga %s. | Have a question? Call %s. |
| 222 | `help_feedback` | Toa maoni yako! Sema "Maoni." | Give your feedback! Say "Feedback." |
| 223 | `help_tutorial` | Unataka mafunzo? Sema "Mafunzo." | Want a tutorial? Say "Tutorial." |
| 224 | `help_whatsapp` | Pia unaweza kutuma WhatsApp: %s. | You can also WhatsApp: %s. |
| 225 | `help_community` | Jiunge na kikundi chetu: %s. | Join our group: %s. |

#### Category 11: M-Pesa & Mobile Money (15 prompts)

| # | Prompt ID | Swahili | English |
|---|-----------|---------|---------|
| 226 | `mpesa_received` | Umepokea %s kutoka %s. | You received %s from %s. |
| 227 | `mpesa_sent` | Umetuma %s kwa %s. | You sent %s to %s. |
| 228 | `mpesa_balance` | Salio la M-Pesa: %s. | M-Pesa balance: %s. |
| 229 | `mpesa_confirm_pay` | Lipa %s kwa %s? | Pay %s to %s? |
| 230 | `mpesa_confirm_send` | Tuma %s kwa %s? | Send %s to %s? |
| 231 | `mpesa_stk_push` | Lipa M-Pesa! Angalia simu yako. | Pay M-Pesa! Check your phone. |
| 232 | `mpesa_transaction_done` | Muamala umekamilika! | Transaction complete! |
| 233 | `mpesa_transaction_failed` | Muamala umeshindwa. Jaribu tena. | Transaction failed. Try again. |
| 234 | `mpesa_fuliza` | Fuliza inapatikana: %s. Tumia? | Fuliza available: %s. Use? |
| 235 | `mpesa_statement` | Taarifa ya M-Pesa: Mwezi huu %s. | M-Pesa statement: This month %s. |
| 236 | `mpesa_till_number` | Lipa kwa till: %s. | Pay to till: %s. |
| 237 | `mpesa_paybill` | Paybill: %s, Account: %s. | Paybill: %s, Account: %s. |
| 238 | `mpesa_withdraw` | Ondoa %s kutoka M-Pesa. | Withdraw %s from M-Pesa. |
| 239 | `mpesa_float_low` | Float ya M-Pesa ni ndogo! Ongeza. | M-Pesa float is low! Top up. |
| 240 | `mpesa_commission` | Kamisheni ya leo: %s. | Today's commission: %s. |

---

## 5. Sheng Integration Strategy

### What is Sheng?

Sheng is a dynamic urban slang spoken primarily in Nairobi and other Kenyan cities. It's a **creole** that blends:
- **Swahili** (base grammar, 60% of vocabulary)
- **English** (technical/modern terms, 25%)
- **Local languages** (Luo, Kikuyu, Luhya, etc., 15%)
- **Constantly evolving** — new slang terms emerge weekly

### Why Sheng Matters for Msaidizi

| Stat | Source |
|------|--------|
| 60%+ of Nairobi youth speak Sheng daily | KNBS Census 2019 |
| 40% of informal workers under 35 use Sheng as primary language | World Bank 2022 |
| Sheng speakers code-switch 3-5x per sentence | Linguistic studies |
| Illiterate Sheng speakers can't use standard Swahili apps | Field research |

### Sheng Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    SHENG LANGUAGE PIPELINE                  │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  User speaks ──▶ LanguageDetector ──▶ "sheng" detected     │
│       │                                      │              │
│       ▼                                      ▼              │
│  CodeSwitchHandler.segment()     ShengDictionary.lookup()   │
│       │                                      │              │
│       ▼                                      ▼              │
│  Normalize: "Nimebuy nganya" → "Nimenunua nyanya"          │
│       │                                                     │
│       ▼                                                     │
│  Business Logic (in Swahili)                                │
│       │                                                     │
│       ▼                                                     │
│  Response: Mix Swahili + Sheng based on user preference     │
│  "Poa! Umefanya mauzo ya elfu moja leo, boss."             │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Sheng Dictionary (Core — `sheng_dictionary.json`)

```json
{
  "version": "1.0.0",
  "last_updated": "2026-07-27",
  "entries": [
    {
      "sheng": "sasa",
      "swahili": "habari",
      "english": "hello/how are you",
      "category": "greeting",
      "usage": "universal",
      "age_group": "all"
    },
    {
      "sheng": "niaje",
      "swahili": "vipi/habari gani",
      "english": "what's up",
      "category": "greeting",
      "usage": "informal",
      "age_group": "youth"
    },
    {
      "sheng": "poa",
      "swahili": "nzuri/sawa",
      "english": "cool/good",
      "category": "response",
      "usage": "universal",
      "age_group": "all"
    },
    {
      "sheng": "mambo",
      "swahili": "habari",
      "english": "what's up",
      "category": "greeting",
      "usage": "informal",
      "age_group": "youth"
    },
    {
      "sheng": "vipi",
      "swahili": "vipi/habari",
      "english": "how is it",
      "category": "greeting",
      "usage": "universal",
      "age_group": "all"
    },
    {
      "sheng": "nduthi",
      "swahili": "pikipiki/boda boda",
      "english": "motorcycle",
      "category": "transport",
      "usage": "nairobi",
      "age_group": "youth"
    },
    {
      "sheng": "msee",
      "swahili": "mtu",
      "english": "person/guy",
      "category": "people",
      "usage": "informal",
      "age_group": "youth"
    },
    {
      "sheng": "dem",
      "swahili": "msichana",
      "english": "girl",
      "category": "people",
      "usage": "informal",
      "age_group": "youth"
    },
    {
      "sheng": "chali",
      "swahili": "kijana/mvulana",
      "english": "guy/boy",
      "category": "people",
      "usage": "informal",
      "age_group": "youth"
    },
    {
      "sheng": "mbogi",
      "swahili": "marafiki/kundi",
      "english": "friends/group",
      "category": "people",
      "usage": "informal",
      "age_group": "youth"
    },
    {
      "sheng": "ka-quarter",
      "swahili": "robo",
      "english": "quarter",
      "category": "quantity",
      "usage": "market",
      "age_group": "all"
    },
    {
      "sheng": "kush",
      "swahili": "kuchukua",
      "english": "to take",
      "category": "verb",
      "usage": "informal",
      "age_group": "youth"
    },
    {
      "sheng": "kuhepa",
      "swahili": "kuondoka/kutoroka",
      "english": "to leave/escape",
      "category": "verb",
      "usage": "informal",
      "age_group": "youth"
    },
    {
      "sheng": "kublack",
      "swahili": "kuficha",
      "english": "to hide",
      "category": "verb",
      "usage": "informal",
      "age_group": "youth"
    },
    {
      "sheng": "manze",
      "swahili": "kweli/ee",
      "english": "really/yes",
      "category": "response",
      "usage": "informal",
      "age_group": "youth"
    },
    {
      "sheng": "maze",
      "swahili": "rafiki",
      "english": "friend/buddy",
      "category": "people",
      "usage": "informal",
      "age_group": "youth"
    },
    {
      "sheng": "aki",
      "swahili": "kweli",
      "english": "really/I swear",
      "category": "emphasis",
      "usage": "informal",
      "age_group": "all"
    },
    {
      "sheng": "woiye",
      "swahili": "pole",
      "english": "sorry/oh no",
      "category": "expression",
      "usage": "universal",
      "age_group": "all"
    },
    {
      "sheng": "ata",
      "swahili": "hata",
      "english": "even",
      "category": "conjunction",
      "usage": "informal",
      "age_group": "youth"
    },
    {
      "sheng": "juu",
      "swahili": "kwa sababu",
      "english": "because",
      "category": "conjunction",
      "usage": "informal",
      "age_group": "youth"
    }
  ],
  "business_terms": [
    {
      "sheng": "kupiga",
      "swahili": "kupiga hesabu/kupiga mauzo",
      "english": "to calculate/to make sales",
      "category": "business",
      "context": "Mama mboga: 'Nimepiga elfu mbili leo'"
    },
    {
      "sheng": "kumess",
      "swahili": "kupoteza/kuharibu",
      "english": "to mess up/to lose",
      "category": "business",
      "context": "Stock ime-mess"
    },
    {
      "sheng": "kucatch",
      "swahili": "kupata/kupokea",
      "english": "to get/to catch",
      "category": "business",
      "context": "Nimecatch customer mpya"
    },
    {
      "sheng": "kudrop",
      "swahili": "kushusha bei",
      "english": "to drop price",
      "category": "business",
      "context": "Drop bei ya nyanya"
    },
    {
      "sheng": "kupick",
      "swahili": "kuchukua",
      "english": "to pick up",
      "category": "business",
      "context": "Nenda upick stock"
    }
  ]
}
```

### Sheng Response Generation

When user selects Sheng mode, responses use a **mixing strategy**:

| Response Type | Swahili % | Sheng % | English % | Example |
|--------------|-----------|---------|-----------|---------|
| Greeting | 30% | 60% | 10% | "Sasa boss! Biashara ikoje leo?" |
| Sales confirm | 50% | 40% | 10% | "Poa! Umefanya mauzo ya elfu moja." |
| Error | 60% | 30% | 10% | "Aki, kuna problem. Jaribu tena." |
| Financial | 50% | 30% | 20% | "Faida yako ni elfu tatu, boss. Poa!" |
| Encouragement | 40% | 50% | 10% | "Vipi! Biashara inaenda poa sana!" |

### Dynamic Sheng Updates

Sheng evolves fast. The system includes:

1. **On-device learning**: When user says a word not in the dictionary, `AdaptiveLearner` stores it as a potential new Sheng term
2. **Cloud sync**: New Sheng terms verified and distributed via dictionary updates
3. **Regional variants**: Nairobi Sheng ≠ Mombasa Sheng ≠ Kisumu Sheng
4. **Age-stratified**: Youth Sheng (18-25) differs from adult Sheng (30+)

---

## 6. Local Language Voice Support Plan

### Supported Languages (14+)

| # | Language | Code | Region | Speakers (Kenya) | Voice Support Level |
|---|----------|------|--------|-------------------|---------------------|
| 1 | Kiswahili | sw | National | 15M+ native, 30M+ L2 | **Full** — UI + Voice + ASR + TTS |
| 2 | English | en | National | 5M+ fluent | **Full** — UI + Voice + ASR + TTS |
| 3 | Sheng | sheng | Urban | 10M+ (unofficial) | **Full** — Voice + ASR, uses Swahili UI |
| 4 | Dholuo (Luo) | luo | Nyanza | 5M+ | **Voice only** — Phrase spotting + TTS |
| 5 | Gĩkũyũ (Kikuyu) | ki | Central | 8M+ | **Voice only** — Phrase spotting + TTS |
| 6 | Kalenjin | kln | Rift Valley | 6M+ | **Voice only** — Phrase spotting + TTS |
| 7 | Kamba | kam | Eastern | 5M+ | **Voice only** — Phrase spotting + TTS |
| 8 | Luhya | luy | Western | 6M+ | **Voice only** — Phrase spotting + TTS |
| 9 | Meru | mer | Eastern | 2M+ | **Voice only** — Phrase spotting |
| 10 | Kisii (Gusii) | guz | Nyanza | 3M+ | **Voice only** — Phrase spotting |
| 11 | Taita | dav | Coast | 400K+ | **Voice only** — Phrase spotting |
| 12 | Maasai | mas | Rift Valley | 1M+ | **Voice only** — Phrase spotting |
| 13 | Turkana | tkl | Northern | 1M+ | **Voice only** — Phrase spotting |
| 14 | Somali | so | NE Kenya | 3M+ | **Voice only** — Phrase spotting |
| 15 | Oromo | om | NE Kenya | 500K+ | **Voice only** — Phrase spotting |
| 16 | Amharic | am | Urban (refugee) | 200K+ | **Voice only** — Phrase spotting |
| 17 | Hausa | ha | Urban (trade) | 100K+ | **Voice only** — Phrase spotting |

### Voice Support Tiers

```
┌─────────────────────────────────────────────────────────────────┐
│                 LOCAL LANGUAGE SUPPORT TIERS                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  TIER 1: FULL SUPPORT (Swahili, English, Sheng)                │
│  ┌─────────────────────────────────────────────┐               │
│  │ • Complete UI in language                    │               │
│  │ • Full ASR (speech-to-text)                  │               │
│  │ • Full TTS (text-to-speech)                  │               │
│  │ • Voice command recognition                  │               │
│  │ • Code-switching support                     │               │
│  │ • All 240+ voice prompts                     │               │
│  └─────────────────────────────────────────────┘               │
│                                                                 │
│  TIER 2: VOICE-ASSISTED (Luo, Kikuyu, Kalenjin, Kamba, Luhya) │
│  ┌─────────────────────────────────────────────┐               │
│  │ • Swahili UI (primary)                       │               │
│  │ • Phrase-spotting ASR (key commands only)     │               │
│  │ • TTS in local language (greetings + basics)  │               │
│  │ • Code-switching: local ↔ Swahili            │               │
│  │ • ~50 core prompts per language               │               │
│  │ • Downloadable language pack (~5MB)           │               │
│  └─────────────────────────────────────────────┘               │
│                                                                 │
│  TIER 3: PHRASE-ONLY (Meru, Kisii, Taita, Maasai, etc.)       │
│  ┌─────────────────────────────────────────────┐               │
│  │ • Swahili UI (primary)                       │               │
│  │ • No ASR in local language                   │               │
│  │ • TTS for greetings + numbers only            │               │
│  │ • ~20 core phrases per language               │               │
│  │ • Bundled (small, <1MB)                      │               │
│  └─────────────────────────────────────────────┘               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Tier 2: Core Phrase Set (50 phrases per language)

Each Tier 2 language includes these phrase categories:

| Category | Count | Examples |
|----------|-------|---------|
| Greetings | 8 | Hello, Good morning, How are you, Goodbye, etc. |
| Numbers 0-100 | 5 | Zero through one hundred (key thresholds) |
| Business confirmations | 10 | Sold, Bought, Profit, Loss, Yes, No, Correct, Wrong |
| Money terms | 8 | How much, Expensive, Cheap, Pay, Change, M-Pesa |
| Product names | 10 | Tomatoes, Onions, Maize, Beans, etc. (regional produce) |
| Error/help | 9 | I don't understand, Repeat, Help, Slow down, etc. |

### Tier 2 Implementation: Phrase-Spotting Model

```
┌──────────────────────────────────────────────────────────────┐
│              PHRASE-SPOTTING ARCHITECTURE                     │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  Audio Input ──▶ VAD (Voice Activity Detection)             │
│                      │                                       │
│                      ▼                                       │
│              On-device ASR (Swahili model)                   │
│                      │                                       │
│                      ▼                                       │
│          ┌───────────────────────┐                           │
│          │  Swahili recognized?  │                           │
│          │  Confidence > 0.7?    │──Yes──▶ Process normally  │
│          └───────────────────────┘                           │
│                      │                                       │
│                      No (low confidence — might be local     │
│                          language)                           │
│                      ▼                                       │
│          ┌───────────────────────┐                           │
│          │  Phrase-spotter:      │                           │
│          │  Local language model │                           │
│          │  (50-phrase set)      │                           │
│          └───────────────────────┘                           │
│                      │                                       │
│                      ▼                                       │
│          Match found? ──Yes──▶ Map to Swahili equivalent    │
│                      │          Process command              │
│                      No                                      │
│                      ▼                                       │
│          "Sikuelewi. Jaribu Kiswahili au gusa skrini."      │
│          (I don't understand. Try Swahili or tap screen.)   │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### Language Pack Download System

```kotlin
// LanguagePackManager.kt
data class LanguagePack(
    val code: String,           // "luo", "ki", "kln"
    val name: String,           // "Dholuo", "Gikuyu", "Kalenjin"
    val tier: Int,              // 2 or 3
    val sizeBytes: Long,        // ~5MB for Tier 2, ~1MB for Tier 3
    val phrases: Int,           // 50 for Tier 2, 20 for Tier 3
    val downloadUrl: String,
    val version: Int
)

// Available for download from assets or CDN
val availablePacks = listOf(
    LanguagePack("luo", "Dholuo", 2, 5_242_880, 50, "...", 1),
    LanguagePack("ki", "Gikuyu", 2, 5_242_880, 50, "...", 1),
    LanguagePack("kln", "Kalenjin", 2, 4_718_592, 50, "...", 1),
    LanguagePack("kam", "Kamba", 2, 4_718_592, 50, "...", 1),
    LanguagePack("luy", "Luhya", 2, 5_242_880, 50, "...", 1),
    LanguagePack("mer", "Meru", 3, 1_048_576, 20, "...", 1),
    LanguagePack("guz", "Kisii", 3, 1_048_576, 20, "...", 1),
    LanguagePack("dav", "Taita", 3, 1_048_576, 20, "...", 1),
    LanguagePack("mas", "Maasai", 3, 1_048_576, 20, "...", 1),
    LanguagePack("tkl", "Turkana", 3, 1_048_576, 20, "...", 1),
    LanguagePack("so", "Somali", 3, 1_048_576, 20, "...", 1)
)
```

### Code-Switching Across Languages

Users naturally mix languages. The system handles:

1. **Swahili ↔ English** (most common — handled by `CodeSwitchHandler`)
2. **Local language ↔ Swahili** (common in rural areas)
3. **Sheng ↔ Swahili ↔ English** (Nairobi youth)
4. **Local language ↔ English** (educated rural workers)

**Detection strategy**:
```
Input → LanguageDetector detects primary language
      → If primary is "sw" but confidence < 0.6:
        → Run phrase-spotter for user's registered local language
        → If match found: treat as code-switch
        → Extract Swahili equivalent from phrase mapping
      → Process unified Swahili command
```

---

## 7. Technical Implementation Notes

### OnboardingViewModel.kt (Proposed)

```kotlin
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val ttsManager: TtsManager,
    private val voiceInputManager: VoiceInputManager,
    private val languageDetector: LanguageDetector,
    private val codeSwitchHandler: CodeSwitchHandler,
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun startOnboarding() {
        // Auto-play welcome prompt
        ttsManager.speak("onboard_welcome")
        _state.update { it.copy(currentStep = OnboardingStep.LANGUAGE) }
    }

    fun selectLanguage(language: String) {
        _state.update { it.copy(language = language) }
        // Play confirmation in selected language
        ttsManager.speak("onboard_lang_$language")
        // Auto-advance after confirmation
        viewModelScope.launch {
            delay(1500)
            advanceToBusiness()
        }
    }

    fun selectBusinessType(businessType: BusinessType) {
        _state.update { it.copy(businessType = businessType) }
        ttsManager.speak("onboard_business_confirm", businessType.swahiliName)
        viewModelScope.launch {
            delay(1500)
            advanceToVoice()
        }
    }

    private fun advanceToVoice() {
        _state.update { it.copy(currentStep = OnboardingStep.VOICE_CALIBRATION) }
        ttsManager.speak("onboard_voice_intro")
        voiceInputManager.startListening(
            expectedPhrase = "Msaidizi",
            onResult = { result ->
                if (result.confidence > 0.5f) {
                    _state.update { it.copy(voiceCalibrated = true) }
                    ttsManager.speak("onboard_voice_heard")
                    // Proceed to test phrase
                    startVoiceTest()
                } else {
                    ttsManager.speak("onboard_voice_retry")
                }
            },
            onError = {
                // Fallback to screen-only mode
                _state.update { it.copy(voiceEnabled = false) }
                ttsManager.speak("onboard_voice_fail")
                completeOnboarding()
            }
        )
    }

    private fun completeOnboarding() {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[LANGUAGE_KEY] = _state.value.language
                prefs[BUSINESS_TYPE_KEY] = _state.value.businessType.name
                prefs[ONBOARDED_KEY] = true
                prefs[VOICE_ENABLED_KEY] = _state.value.voiceEnabled
            }
            ttsManager.speak("onboard_complete")
            _state.update { it.copy(currentStep = OnboardingStep.COMPLETE) }
        }
    }
}

data class OnboardingState(
    val currentStep: OnboardingStep = OnboardingStep.LANGUAGE,
    val language: String = "sw",
    val businessType: BusinessType = BusinessType.OTHER,
    val voiceEnabled: Boolean = true,
    val voiceCalibrated: Boolean = false,
    val userName: String = ""
)

enum class OnboardingStep {
    LANGUAGE, BUSINESS, VOICE_CALIBRATION, COMPLETE
}
```

### TTS Architecture

```kotlin
// TtsManager.kt — Voice output management
@Singleton
class TtsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val promptCatalog: VoicePromptCatalog
) {
    private var tts: TextToSpeech? = null
    private var currentLanguage: String = "sw"

    fun initialize(language: String) {
        currentLanguage = language
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val locale = when (language) {
                    "sw" -> Locale("sw", "KE")
                    "en" -> Locale("en", "KE")
                    "sheng" -> Locale("sw", "KE") // Sheng uses Swahili TTS
                    else -> Locale("sw", "KE")
                }
                tts?.language = locale
                tts?.setSpeechRate(0.85f) // Slightly slower for clarity
            }
        }
    }

    /**
     * Speak a prompt by ID.
     * 1. Try bundled audio asset first (better quality)
     * 2. Fall back to TTS engine
     */
    fun speak(promptId: String, vararg args: Any) {
        val prompt = promptCatalog.getPrompt(promptId, currentLanguage)
        val text = prompt.format(*args)

        // Try audio asset first
        val audioFile = promptCatalog.getAudioFile(promptId, currentLanguage)
        if (audioFile != null) {
            playAudioAsset(audioFile)
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, promptId)
        }
    }
}
```

### Voice Prompt Audio Asset Bundling

Voice prompts are pre-recorded and bundled as OGG Vorbis files:

```
Format: OGG Vorbis, 16kHz, mono, ~32kbps
Size per prompt: ~5-15KB
Total for 240 prompts (Swahili): ~2.5MB
Total for all 3 languages: ~7.5MB
```

**Recording guidelines**:
- Voice: Female, 25-35 years old, clear Nairobi Swahili accent
- Tone: Warm, friendly, encouraging (like a helpful market neighbor)
- Speed: 120-140 words per minute (slightly slower than natural)
- Environment: Clean studio, no background noise
- Emphasis: Key words (numbers, product names, confirmations) slightly louder

---

## 8. Success Metrics

### Onboarding Metrics

| Metric | Target | Measurement |
|--------|--------|-------------|
| Completion rate | >85% | % of users who reach "Anza Kazi!" |
| Completion time | <2 min | Time from launch to "Anza Kazi!" |
| Voice calibration success | >70% | % who successfully calibrate voice |
| Step drop-off rate | <10% per step | Users abandoning at each step |
| Language selection accuracy | >95% | Correct language selected |
| Business type match | >90% | Correct business type selected |

### Localization Metrics

| Metric | Target | Measurement |
|--------|--------|-------------|
| Swahili string coverage | 100% | All strings.xml entries translated |
| Voice prompt coverage | 100% (Tier 1) | All 240 prompts recorded |
| Sheng term coverage | >500 terms | Dictionary entries |
| Local language packs | 5 (Tier 2), 10 (Tier 3) | Downloadable packs |
| ASR accuracy (Swahili) | >85% | Word error rate on test set |
| ASR accuracy (Sheng) | >75% | Word error rate on test set |
| TTS naturalness (MOS) | >3.5 | Mean opinion score |

### User Experience Metrics

| Metric | Target | Measurement |
|--------|--------|-------------|
| First-session retention | >60% | Users returning after 24h |
| Voice usage rate | >40% | % of interactions via voice |
| Error recovery rate | >90% | Users who recover from errors |
| Support requests | <5% | % of users needing help |
| NPS (Net Promoter Score) | >50 | User satisfaction survey |

---

## Appendix A: Onboarding Flow Diagram (Detailed)

```
                        ┌─────────────┐
                        │  APP LAUNCH │
                        └──────┬──────┘
                               │
                        ┌──────▼──────┐
                        │  Bootstrap   │
                        │  Complete?   │
                        └──────┬──────┘
                          Yes/ \No
                         /     \
                   ┌────▼──┐  ┌▼────────────┐
                   │ Main  │  │  ONBOARDING  │
                   │  App  │  │   START      │
                   └───────┘  └──────┬───────┘
                                     │
                              ┌──────▼───────┐
                              │   STEP 1:    │
                              │  LANGUAGE    │
                              │  SELECTION   │
                              └──────┬───────┘
                                     │
                        ┌────────────┼────────────┐
                        │            │            │
                   ┌────▼───┐  ┌────▼───┐  ┌────▼───┐
                   │Swahili │  │English │  │ Sheng  │
                   └────┬───┘  └────┬───┘  └────┬───┘
                        │           │           │
                        └───────────┼───────────┘
                                    │
                              ┌─────▼──────┐
                              │  STEP 2:   │
                              │ BUSINESS   │
                              │  TYPE      │
                              └─────┬──────┘
                                    │
                    ┌───────────────┼───────────────┐
                    │               │               │
              ┌─────▼─────┐  ┌─────▼─────┐  ┌─────▼─────┐
              │ Mama Mboga│  │  Boda     │  │   Other   │
              │  🥬       │  │  Boda 🏍️  │  │   📦     │
              └─────┬─────┘  └─────┬─────┘  └─────┬─────┘
                    │               │               │
                    └───────────────┼───────────────┘
                                    │
                              ┌─────▼──────┐
                              │  STEP 3:   │
                              │   VOICE    │
                              │CALIBRATION │
                              └─────┬──────┘
                                    │
                           ┌────────┼────────┐
                           │                 │
                    ┌──────▼──────┐  ┌──────▼──────┐
                    │   SUCCESS   │  │   FALLBACK  │
                    │ Voice Ready │  │ Screen Only │
                    └──────┬──────┘  └──────┬──────┘
                           │                 │
                           └────────┬────────┘
                                    │
                              ┌─────▼──────┐
                              │  COMPLETE  │
                              │  🎉 Anza   │
                              │   Kazi!    │
                              └────────────┘
```

## Appendix B: Swahili Grammar Notes for Prompt Design

### Noun Classes (Relevant for Dynamic Prompts)

Swahili uses noun classes that affect agreement. For dynamic prompts like "Stock ya %s imeongezwa":

| Class | Prefix | Example | Agreement |
|-------|--------|---------|-----------|
| M-/Wa- (people) | m-/wa- | mteja/wateja | mteja ame-... / wateja wame-... |
| Ki-/Vi- (things) | ki-/vi- | kikapu/vikapu | kikapu ki-... / vikapu vi-... |
| N-/N- (animals, plants) | n-/n- | nyanya/nyanya | nyanya i-... / nyanya zi-... |
| Ma- (plurals) | ma- | mauzo | mauzo ya-... |
| U- (abstract) | u- | faida | faida i-... |

### Verb Constructions for Prompts

| Tense | Prefix | Example |
|-------|--------|---------|
| Perfect (completed) | ni-me- | Nimeuza (I have sold) |
| Present (ongoing) | ni-na- | Ninauza (I am selling) |
| Future | ni-ta- | Nitauza (I will sell) |
| Conditional | ni-ki- | Nikiuza (If I sell) |
| Habitual | ni-hu- | Ninahuuza (I usually sell) |

### Number System

| Number | Swahili | Pronunciation Note |
|--------|---------|-------------------|
| 0 | sifuri | |
| 1 | moja | |
| 2 | mbili | |
| 3 | tatu | |
| 4 | nne | |
| 5 | tano | |
| 10 | kumi | |
| 20 | ishirini | |
| 100 | mia moja | |
| 1,000 | elfu moja | |
| 100,000 | laki moja | |
| 1,000,000 | milioni moja | |

### Currency in Prompts

Always use **KSh** (Kenya Shillings) or **TSh** (Tanzania Shillings) in prompts. When the amount is spoken:

- "Elfu tano" = 5,000 KSh
- "Laki mbili" = 200,000 KSh
- "Milioni moja" = 1,000,000 KSh

---

## Appendix C: Cultural Considerations

### Greetings

- **Always greet before business** — Swahili culture requires warm greetings before any transaction
- **Time-appropriate greetings** — asubuhi/mchana/jioni (morning/afternoon/evening)
- **Use "Habari" not "Hello"** — even in English mode, acknowledge cultural norm

### Respect Markers

- **"Mama" / "Baba"** — respectful address for older users
- **"Boss" / "Mdau"** — friendly address in Sheng
- **"Rafiki"** — warm, neutral address

### Religious Sensitivity

- **Ramadan/Eid** — many coastal and NEP users are Muslim
- **Sunday** — Christian users may be at church
- **Avoid** scheduling reminders during prayer times

### Market Culture

- **"Bei ya kwanza"** — first price is always negotiable
- **"Punguzo"** — discounts are expected for bulk/regular customers
- **"Soko la jioni"** — evening market prices drop (perishables)

---

*This document is the canonical reference for Msaidizi onboarding and localization. All implementations must align with these specifications.*

*— Onboarding & Localization Council*
