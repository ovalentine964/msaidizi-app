# Msaidizi Proactive Alert System — Architecture Design

> **Document Type:** System Architecture  
> **Version:** 1.0  
> **Date:** 2026-07-24  
> **Status:** Design Phase  
> **Author:** Architecture Agent

---

## 1. Overview

The Proactive Alert System is Msaidizi's nervous system — it watches, thinks, and speaks *before* the worker asks. Built on the OODA loop (Observe → Orient → Decide → Act), it transforms raw business signals into timely, spoken warnings and opportunities in the worker's own language.

**Design Principles:**
- **Voice-first:** Every alert is spoken, not displayed
- **Offline-first:** Alerts generated from local patterns, no cloud dependency
- **Culturally aware:** Timing respects work rhythms, language respects identity
- **Anti-fatigue:** Silence is a feature — most observations never become alerts
- **Personalized:** Thresholds adapt to each worker's unique business patterns

---

## 2. Alert Trigger Catalog

### 2.1 Cash Flow Warning

**What:** Worker is projected to run short of cash before upcoming bills/obligations fall due.

**Observation Sources:**
- Current cash on hand (voice-logged balances + M-Pesa integration)
- Known recurring expenses (rent, school fees, supplier payments)
- Recent revenue trend (7-day rolling average vs. 30-day baseline)
- Day-of-week seasonality (e.g., Mondays are always slow)

**Trigger Logic:**
```
projected_balance = current_cash + expected_revenue_next_7d - known_expenses_next_7d
if projected_balance < (safety_margin * avg_weekly_expense):
    trigger ALERT_CASH_FLOW
```

**Thresholds (adaptive):**
| Severity | Condition | Priority |
|----------|-----------|----------|
| Critical | Projected balance < 0 in ≤3 days | P0 — Immediate |
| Warning | Projected balance < 20% of avg weekly expense in ≤7 days | P1 — Next check-in |
| Advisory | Revenue trending down >15% week-over-week | P2 — Morning briefing |

**Voice Example (Swahili):**
> *"Samahani, kuna hatari. Pesa zako zinakaribia kuisha. Kwa sasa una elfu tatu, lakini kodi ya wiki ijayo ni elfu tano. Fikiria kukusanya madeni au kupunguza manunuzi."*

---

### 2.2 Restock Recommendation

**What:** A product is selling faster than stock remains; time to reorder before a stockout.

**Observation Sources:**
- Current inventory levels (voice-logged stock counts)
- Sales velocity per product (units/day, weighted by recency)
- Supplier lead time (learned per supplier)
- Day-of-week demand patterns (weekend spikes, etc.)

**Trigger Logic:**
```
days_of_stock = current_qty / avg_daily_sales(7d_weighted)
if days_of_stock < supplier_lead_time + safety_buffer:
    trigger ALERT_RESTOCK
```

**Thresholds (per-product adaptive):**
| Severity | Condition | Priority |
|----------|-----------|----------|
| Critical | Stockout projected within ≤2 days | P0 — Immediate |
| Warning | Stockout projected within lead_time + 3 days | P1 — Next check-in |
| Advisory | Fast-moving item crossed 50% depletion | P2 — Morning briefing |

**Voice Example (Sheng):**
> *"Mkuu, unga ya ugali inaisha! Uko na magunia mawili tu, na unauza magunia moja kwa siku. Wasiliana na supplier wako kabla ya Jumatano."*

---

### 2.3 Market Opportunity

**What:** External price movement or demand spike creates a buying/selling opportunity.

**Observation Sources:**
- Price feeds (aggregated from peer reports, USSD market prices, cooperative bulletins)
- Demand signals (unusual product requests, peer activity patterns)
- Seasonal calendar (holidays, planting season, school terms)
- Location context (nearby events, market days)

**Trigger Logic:**
```
if product_price_deviation > +2_stddev from 30d_avg:
    trigger ALERT_PRICE_SPIKE  # Sell opportunity
if product_price_deviation < -2_stddev from 30d_avg:
    trigger ALERT_PRICE_DIP    # Buy opportunity
if demand_index(product) > 90th_percentile AND has_stock:
    trigger ALERT_HIGH_DEMAND  # Pricing opportunity
```

**Thresholds:**
| Severity | Condition | Priority |
|----------|-----------|----------|
| Critical | Price spike >40% on product you stock | P0 — Immediate |
| Advisory | Price dip >20% on product you buy regularly | P2 — Morning briefing |
| Advisory | Demand spike for product in your area | P2 — Morning briefing |

**Voice Example (Kikuyu):**
> *"Wĩ na oportunity! Mũtĩ wa sukari ũrĩ gũkara gatandatũ kũria gatandatũ ya mũthenya. Ũkĩũria ũmũ, ũngĩgũra na profit."*

*(Note: Exact Kikuyu phrasing would be validated with native speakers during localization.)*

---

### 2.4 Savings Milestone

**What:** Worker is on track to hit a savings goal, or has just reached one.

**Observation Sources:**
- Savings goal (worker-declared target + deadline)
- Savings balance (cumulative deposits)
- Contribution frequency (weekly/monthly pattern)
- Projected completion date (linear extrapolation)

**Trigger Logic:**
```
pct_complete = current_savings / goal_amount
projected_date = linear_extrapolate(contribution_history)
if pct_complete >= milestone_threshold:  # 25%, 50%, 75%, 100%
    trigger ALERT_SAVINGS_MILESTONE
if projected_date > goal_deadline AND pct_complete < 80%:
    trigger ALERT_SAVINGS_OFF_TRACK
```

**Voice Examples:**
> *"Hongera! Umefikia nusu ya lengo lako la akiba. Umek elfu kumi kati ya elfu ishirini. Endelea hivyo!"* (Celebration)

> *"Angalia, kwa kasi hii ya akiba, utafikia lengo lako mwezi mmoja baadaye. Je, uongeze akiba ya wiki hii?"* (Course correction)

---

### 2.5 Credit Readiness (Alama Score)

**What:** Worker's Alama Score has improved enough that loan products become available or cheaper.

**Observation Sources:**
- Alama Score (periodic sync from credit system)
- Score trend (improving/stable/declining)
- Available loan products (pre-qualified offers)
- Current debt obligations

**Trigger Logic:**
```
if score crossed product_threshold AND trend == improving:
    trigger ALERT_CREDIT_READY
if score improved by >50 points in 30d:
    trigger ALERT_SCORE_IMPROVEMENT
```

**Voice Example:**
> *"Habari njema! Alama yako imepanda kutoka mia mbili hamsini hadi mia tatu kumi. Sasa unastahili mkopo wa elfu hamsini kutoka M-Shwari. Kama unahitaji, nitakusaidia kuomba."*

---

### 2.6 Anomaly Detection

**What:** Something unusual is happening — unexpected spending, revenue drop, or pattern break.

**Observation Sources:**
- Transaction history (rolling statistics per category)
- Revenue patterns (daily, weekly, seasonal baselines)
- Expense patterns (recurring vs. one-time)
- Peer comparison (optional, anonymized)

**Trigger Logic:**
```
for each category in expenses:
    z_score = (today_amount - 30d_mean) / 30d_stddev
    if abs(z_score) > 2.5:
        trigger ALERT_ANOMALY_EXPENSE(category)

if daily_revenue < 0.5 * rolling_7d_avg for 2_consecutive_days:
    trigger ALERT_REVENUE_DROP

if transaction_volume > 3x normal AND no_known_event:
    trigger ALERT_UNUSUAL_ACTIVITY
```

**Voice Example:**
> *"Samahani, nimeona kitu cha kushangaza. Umefanya manunuzi ya elfu nane leo, wakati wastani wako ni elfu mbili. Je, hii ni sahihi?"*

---

### 2.7 Chama Reminder

**What:** Upcoming contribution due, meeting scheduled, or Chama-related obligation.

**Observation Sources:**
- Chama schedule (contribution dates, meeting dates)
- Contribution history (on-time / late pattern)
- Current balance vs. expected contribution
- Calendar proximity (days until next event)

**Trigger Logic:**
```
days_until_contribution = next_contribution_date - today
if days_until_contribution <= 3 AND current_cash >= contribution_amount:
    trigger ALERT_CHAMA_REMINDER
if days_until_contribution <= 3 AND current_cash < contribution_amount:
    trigger ALERT_CHAMA_FUNDS_LOW
if days_until_meeting <= 1:
    trigger ALERT_CHAMA_MEETING
```

**Voice Example:**
> *"Kesho ni siku ya mchango wa Chama. Unahitaji elfu mbili. Kwa sasa una elfu tatu, kwa hivyo uko tayari. Usisahau!"*

---

## 3. Alert Delivery System

### 3.1 Priority Levels & Delivery Timing

```
┌─────────────────────────────────────────────────────────────┐
│                    PRIORITY FRAMEWORK                        │
├──────────┬──────────────────┬───────────────────────────────┤
│ Priority │ Delivery Window  │ Behavior                      │
├──────────┼──────────────────┼───────────────────────────────┤
│ P0       │ Immediate        │ Interrupt with voice alert.   │
│ Critical │ (within minutes) │ Vibrate + speak. Even if in   │
│          │                  │ brief idle window.             │
├──────────┼──────────────────┼───────────────────────────────┤
│ P1       │ Next check-in    │ Queue. Deliver at next natural│
│ Important│ (within hours)   │ break: end of sale, walking   │
│          │                  │ between locations, rest.      │
├──────────┼──────────────────┼───────────────────────────────┤
│ P2       │ Morning briefing │ Batch with other P2 alerts.   │
│ Info     │ (next 6-8 AM)   │ Deliver as daily summary.     │
├──────────┼──────────────────┼───────────────────────────────┤
│ Silent   │ Never spoken     │ Logged only. Pattern data.    │
│ (P3)     │                  │ Used for future threshold     │
│          │                  │ calibration.                  │
└──────────┴──────────────────┴───────────────────────────────┘
```

### 3.2 Timing Intelligence — "Quiet Hours" & "Busy Hours"

**Respect the worker's rhythm. Alerts must never interrupt livelihood.**

```
┌─────────────────────────────────────────────────────────┐
│              DAILY ALERT WINDOW MAP                      │
├──────────┬──────────┬───────────────────────────────────┤
│ Time     │ State    │ Alert Policy                      │
├──────────┼──────────┼───────────────────────────────────┤
│ 22:00-   │ SLEEP    │ ❌ NO alerts. All queued.         │
│ 05:30    │          │                                   │
├──────────┼──────────┼───────────────────────────────────┤
│ 05:30-   │ WAKE-UP  │ ✅ P0 only. Brief, gentle.       │
│ 06:00    │          │                                   │
├──────────┼──────────┼───────────────────────────────────┤
│ 06:00-   │ MORNING  │ ✅ Morning briefing window.       │
│ 07:00    │ BRIEFING │ Batch P2 alerts + daily summary.  │
├──────────┼──────────┼───────────────────────────────────┤
│ 07:00-   │ MARKET   │ ❌ P1/P2 queued. P0 only if       │
│ 13:00    │ HOURS    │ critical. Worker is busy selling. │
├──────────┼──────────┼───────────────────────────────────┤
│ 13:00-   │ LUNCH    │ ✅ P1 window. Natural break.      │
│ 14:00    │ BREAK    │                                   │
├──────────┼──────────┼───────────────────────────────────┤
│ 14:00-   │ AFTERNOON│ ❌ P1/P2 queued. P0 only.        │
│ 17:00    │ WORK     │                                   │
├──────────┼──────────┼───────────────────────────────────┤
│ 17:00-   │ WRAP-UP  │ ✅ P1 window. End-of-day context. │
│ 18:30    │          │                                   │
├──────────┼──────────┼───────────────────────────────────┤
│ 18:30-   │ EVENING  │ ✅ P1 window. Good for Chama      │
│ 20:00    │          │ reminders, savings updates.       │
├──────────┼──────────┼───────────────────────────────────┤
│ 20:00-   │ WIND-DOWN│ ⚠️ P0 only. Worker is resting.   │
│ 22:00    │          │                                   │
└──────────┴──────────┴───────────────────────────────────┘
```

**Context Modifiers:**
- **Market day** (e.g., Wed/Sat): Extend MARKET HOURS window, compress briefing
- **Sunday/Church day**: No alerts before 10 AM
- **Ramadan/Fasting**: Adjust meal-break windows
- **Location at market**: Treat as MARKET HOURS regardless of clock
- **Location at home during work hours**: Possible sick day — reduce to P0 only

### 3.3 Voice Delivery Pipeline

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ Alert Engine │───▶│ Message      │───▶│ TTS Engine   │
│ (triggers    │    │ Compositor   │    │ (offline,    │
│  alert)      │    │ (template +  │    │  dialect-    │
│              │    │  context +   │    │  aware)      │
│              │    │  language)   │    │              │
└──────────────┘    └──────────────┘    └──────┬───────┘
                                               │
                                               ▼
                                        ┌──────────────┐
                                        │ Audio Output  │
                                        │ (speaker/     │
                                        │  earpiece)    │
                                        └──────────────┘
```

**Message Compositor Rules:**
1. **Lead with the most important fact** — workers may only hear the first sentence
2. **Use concrete numbers** — "una elfu tatu" not "unakaribia kuisha"
3. **Suggest one action** — not a menu of options
4. **Keep it under 30 seconds** — attention is scarce
5. **Match formality to context** — morning briefing can be longer, P0 must be crisp

**Template Structure:**
```
[Attention word] + [Situation] + [Number/Detail] + [Suggested action]

Examples:
- "Habari! Unga wa ugali inaisha — una magunia mawili tu. Wasiliana na supplier wiki hii."
- "Samahani! Pesa zako zinakaribia kuisha. Kodi ni elfu tano, una elfu tatu. Fikiria kukusanya madeni."
```

### 3.4 Offline Alert Generation

**All alert logic runs locally. No cloud dependency for triggering.**

```
┌─────────────────────────────────────────────────────────┐
│                LOCAL ALERT ENGINE                        │
│                                                         │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │ Data Store  │  │ Pattern      │  │ Threshold     │  │
│  │ (SQLite)    │─▶│ Analyzer     │─▶│ Evaluator     │  │
│  │             │  │ (rolling     │  │ (adaptive,    │  │
│  │ - Txns      │  │  stats,      │  │  per-worker)  │  │
│  │ - Inventory │  │  trends,     │  │               │  │
│  │ - Balance   │  │  seasonality)│  │               │  │
│  │ - Schedule  │  │              │  │               │  │
│  └─────────────┘  └──────────────┘  └───────┬───────┘  │
│                                             │          │
│                                             ▼          │
│                                      ┌──────────────┐  │
│                                      │ Alert Queue  │  │
│                                      │ (prioritized,│  │
│                                      │  timed)      │  │
│                                      └──────────────┘  │
└─────────────────────────────────────────────────────────┘
```

**Data Sources (all local):**
| Data | Source | Update Frequency |
|------|--------|-----------------|
| Transactions | Voice-logged sales/expenses | Real-time |
| Inventory | Voice stock counts | Daily or on change |
| Balance | Voice + M-Pesa (if available) | Daily |
| Schedule | Worker-declared + learned | Weekly |
| Market prices | Cached from last sync | On connectivity |
| Alama Score | Cached from last sync | Weekly |

**Pattern Analyzer Capabilities:**
- Rolling statistics (7d, 14d, 30d windows)
- Day-of-week seasonality detection
- Trend detection (linear regression on recent data)
- Anomaly detection (Z-score based, adaptive thresholds)
- Depletion forecasting (current stock ÷ velocity)

---

## 4. Alert Intelligence — When to Speak, When to Stay Silent

### 4.1 The Anti-Fatigue System

**Problem:** Too many alerts = worker ignores all alerts.  
**Solution:** Aggressive filtering, adaptive thresholds, and a "silence budget."

**Silence Budget:**
```
MAX_ALERTS_PER_DAY = {
    P0: unlimited,      # Critical always gets through
    P1: 3,              # Max 3 important alerts per day
    P2: 5,              # Max 5 informational per day
    morning_briefing: 1 # One consolidated briefing, not 5 separate alerts
}

if daily_alert_count(priority) >= MAX_ALERTS_PER_DAY[priority]:
    suppress alert → log for next day's briefing
```

**Deduplication:**
- Same alert type + same product: suppress for 24 hours
- Same underlying cause (e.g., cash flow): escalate severity instead of creating new alert
- Related alerts: merge into single voice message

**Suppression Rules:**
| Rule | Behavior |
|------|----------|
| Already spoken today | Suppress, log silently |
| Worker acknowledged (said "sawa" or similar) | Suppress for 48h |
| Worker dismissed 3x consecutively | Raise threshold for that alert type |
| Value change < 10% from last alert | Suppress (no new information) |
| Contradicts recent worker action | Suppress (e.g., worker just restocked) |

### 4.2 Adaptive Thresholds

**Each worker has a unique business. Thresholds must learn.**

```
Initial thresholds: Community averages (cold start)
After 30 days: Personalized baselines
After 90 days: Seasonal-adjusted, trend-aware thresholds
```

**Calibration Feedback Loop:**
```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ Alert        │───▶│ Worker       │───▶│ Feedback     │
│ Generated    │    │ Response     │    │ Processor    │
│              │    │ (voice/cmd)  │    │              │
└──────────────┘    └──────────────┘    └──────┬───────┘
                                               │
                                               ▼
                                        ┌──────────────┐
                                        │ Threshold    │
                                        │ Adjuster     │
                                        │              │
                                        │ - "Sawa"     │
                                        │   → threshold ok │
                                        │ - "Hapana"   │
                                        │   → raise threshold │
                                        │ - No response │
                                        │   → lower priority │
                                        │ - Took action │
                                        │   → validate alert │
                                        └──────────────┘
```

**Worker Responses:**
| Voice Input | Interpretation | Action |
|-------------|---------------|--------|
| "Sawa" / "Sikia" | Acknowledged | Log, suppress similar for 24h |
| "Hapana" / "Sio sasa" | Dismissed | Raise threshold for this type |
| Silence + took action | Alert was useful | Reinforce threshold |
| Silence + no action | Alert was noise | Lower priority, increase suppression |
| "Zidi" / "Zaidi" | Wants more detail | Expand, log preference for detail |
| "Punguza" | Too many alerts | Increase silence budget for 7d |

### 4.3 Context-Aware Intelligence

**Location Awareness:**
```
if location == "home" AND time in work_hours:
    # Possible sick day or holiday
    suppress non-critical alerts
    if pattern continues 2+ days:
        ALERT_CHECK_IN: "Hujambo? Siku mbili hujafika sokoni."

if location == "market" AND time in market_hours:
    # Prime selling time — only P0
    suppress all P1/P2
    queue for next break

if location == "supplier_area":
    # Potential restock opportunity
    if has_pending_restock_alert:
        ALERT_OPPORTUNITY: "Uko karibu na supplier wa sukari. Unahitaji kukaguliwa."
```

**Day-of-Week Awareness:**
```
if day == "market_day":  # e.g., Wednesday, Saturday
    extend selling windows
    compress briefing
    suppress Chama reminders (unless due today)

if day == "Sunday":
    no alerts before 10 AM
    evening briefing for week ahead
```

**Seasonal Awareness:**
```
if season == "planting":
    expect higher input costs
    adjust cash flow thresholds upward
    surface agricultural supply opportunities

if season == "harvest":
    expect higher revenue
    surface storage/buyer opportunities
    adjust savings projections

if near("school_fees_due"):
    elevate cash flow sensitivity
    suggest savings top-up
```

---

## 5. OODA Loop Integration

The alert system IS the Act phase of Msaidizi's continuous OODA loop.

```
┌─────────────────────────────────────────────────────────────────┐
│                    OODA LOOP (Continuous)                        │
│                                                                 │
│   ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐ │
│   │ OBSERVE  │───▶│ ORIENT   │───▶│ DECIDE   │───▶│ ACT      │ │
│   │          │    │          │    │          │    │          │ │
│   │ Collect  │    │ Compare  │    │ Evaluate │    │ Alert    │ │
│   │ raw data │    │ against  │    │ threshold│    │ worker   │ │
│   │ (txns,   │    │ baseline │    │ breach + │    │ via      │ │
│   │ stock,   │    │ & trend  │    │ priority │    │ voice    │ │
│   │ balance, │    │ analysis │    │ + timing │    │          │ │
│   │ schedule)│    │          │    │          │    │          │ │
│   └──────────┘    └──────────┘    └──────────┘    └──────────┘ │
│        ▲                                               │        │
│        │                                               │        │
│        └───────────────────────────────────────────────┘        │
│                    (Continuous Cycle)                            │
└─────────────────────────────────────────────────────────────────┘
```

### 5.1 Observe Phase
- **Inputs:** Transaction logs, inventory counts, balance updates, schedule entries, market price cache, location data
- **Frequency:** Continuous (event-driven) + periodic scan (every 15 min)
- **Storage:** Local SQLite, append-only

### 5.2 Orient Phase
- **Pattern analysis:** Rolling statistics, trend detection, seasonality
- **Baseline comparison:** Current state vs. personalized baselines
- **Context enrichment:** Time of day, location, day of week, season
- **Cross-signal correlation:** "Revenue dropped AND inventory high" = potential demand issue, not just slow day

### 5.3 Decide Phase
- **Threshold evaluation:** Does this signal breach a threshold?
- **Priority assignment:** P0/P1/P2/Silent based on severity + impact
- **Timing decision:** Is now an appropriate time to alert?
- **Fatigue check:** Have we alerted too much today? Is this a duplicate?
- **Merging:** Can this be combined with queued alerts?

### 5.4 Act Phase (Alert Delivery)
- **Compose:** Select template, fill with data, choose language/dialect
- **Deliver:** TTS playback at appropriate volume/timing
- **Log:** Record alert delivered, timestamp, priority
- **Await response:** Listen for acknowledgment/dismissal
- **Learn:** Update thresholds based on response

---

## 6. Alert Queue Management

### 6.1 Queue Structure

```
┌─────────────────────────────────────────────┐
│              ALERT QUEUE                     │
├─────────┬───────────────────────────────────┤
│ Queue   │ Contents                          │
├─────────┼───────────────────────────────────┤
│ P0      │ Immediate delivery buffer          │
│ (hot)   │ Max 3 items, FIFO                  │
│         │ Delivered within 60 seconds        │
├─────────┼───────────────────────────────────┤
│ P1      │ Next-check-in buffer               │
│ (warm)  │ Max 10 items, priority-sorted      │
│         │ Delivered at next natural break    │
├─────────┼───────────────────────────────────┤
│ P2      │ Morning briefing accumulator       │
│ (cold)  │ Max 20 items, deduplicated         │
│         │ Delivered as single briefing 6-7AM │
├─────────┼───────────────────────────────────┤
│ Silent  │ Pattern log only                   │
│ (log)   │ No delivery, used for calibration  │
└─────────┴───────────────────────────────────┘
```

### 6.2 Queue Lifecycle

```
Alert Created
    │
    ├─ Priority P0? ──▶ Send immediately (if not sleep)
    │                   └─ Sleep? → Vibrate only, speak at 05:30
    │
    ├─ Priority P1? ──▶ Add to warm queue
    │                   └─ Check-in window? → Flush top 3
    │
    ├─ Priority P2? ──▶ Add to cold queue
    │                   └─ Morning window? → Compose briefing
    │
    └─ Priority P3? ──▶ Log only

Queue Maintenance (every hour):
    - Remove expired alerts (older than 48h)
    - Merge duplicates
    - Re-prioritize if conditions changed
    - Flush if window opens
```

### 6.3 Morning Briefing Composition

The morning briefing is NOT 5 separate alerts read sequentially. It's a single, composed summary:

```
Morning Briefing Template:
" Habari za asubuhi! [Greeting]

  [Summary sentence: overall status]
  "Biashara yako iko sawa wiki hii" OR
  "Kuna mambo machache ya kuzingatia"

  [Top 1-3 items, most important first]
  1. "Unga wa ugali inaisha — wasiliana na supplier"
  2. "Akiba yako ni elfu kumi, umefikia nusu ya lengo"
  3. "Sukari imepanda bei — kama uko na stock, fikiria kuuza"

  [Closing]
  "Nikuulize chochote?" OR "Siku njema!"
"
```

**Briefing Rules:**
- Max 3 items (worker attention limit)
- 60 seconds total duration
- Most important first
- End with opening for follow-up questions

---

## 7. Data Model

### 7.1 Alert Record

```sql
CREATE TABLE alerts (
    id              TEXT PRIMARY KEY,
    type            TEXT NOT NULL,       -- CASH_FLOW, RESTOCK, MARKET_OPP, etc.
    priority        INTEGER NOT NULL,    -- 0=P0, 1=P1, 2=P2, 3=Silent
    status          TEXT NOT NULL,       -- QUEUED, DELIVERED, ACKNOWLEDGED, DISMISSED, EXPIRED
    
    -- Content
    title           TEXT NOT NULL,       -- Short identifier
    message_template TEXT NOT NULL,      -- Template with placeholders
    message_filled  TEXT,                -- Actual message delivered
    language        TEXT NOT NULL,       -- sw, sh, ki, luo, etc.
    
    -- Context
    trigger_data    TEXT,                -- JSON: what values triggered this
    context_data    TEXT,                -- JSON: location, time, market state
    
    -- Lifecycle
    created_at      INTEGER NOT NULL,
    queued_at       INTEGER,
    delivered_at    INTEGER,
    acknowledged_at INTEGER,
    dismissed_at    INTEGER,
    expired_at      INTEGER,
    
    -- Learning
    worker_response TEXT,                -- sawa, hapana, silence, action_taken
    was_useful      INTEGER,             -- 1=yes, 0=no, NULL=unknown
    threshold_at_fire REAL,              -- What threshold was used
    value_at_fire   REAL                 -- What value triggered it
);
```

### 7.2 Threshold Configuration

```sql
CREATE TABLE thresholds (
    id              TEXT PRIMARY KEY,
    worker_id       TEXT NOT NULL,
    alert_type      TEXT NOT NULL,
    
    -- Threshold values
    critical_value  REAL,                -- P0 trigger
    warning_value   REAL,                -- P1 trigger
    advisory_value  REAL,                -- P2 trigger
    
    -- Adaptive parameters
    baseline_mean   REAL,                -- Learned average
    baseline_stddev REAL,                -- Learned variance
    seasonality     TEXT,                -- JSON: day-of-week factors
    
    -- Calibration
    last_calibrated INTEGER,
    sample_count    INTEGER,
    false_positive_rate REAL,            -- How often worker dismissed
    
    UNIQUE(worker_id, alert_type)
);
```

### 7.3 Alert Suppression Log

```sql
CREATE TABLE suppression_log (
    id              TEXT PRIMARY KEY,
    alert_type      TEXT NOT NULL,
    suppressed_at   INTEGER NOT NULL,
    reason          TEXT NOT NULL,       -- DUPLICATE, FATIGUE, QUIET_HOURS, etc.
    original_priority INTEGER,
    deferred_to     INTEGER              -- When it should be reconsidered
);
```

---

## 8. Implementation Phases

### Phase 1: Foundation (Weeks 1-4)
- [ ] Local SQLite data model
- [ ] Basic threshold engine (static thresholds)
- [ ] Cash flow warning (P0/P1 only)
- [ ] Restock recommendation (P0/P1 only)
- [ ] Voice delivery pipeline (single language)
- [ ] Sleep hours respect

### Phase 2: Intelligence (Weeks 5-8)
- [ ] Adaptive threshold calibration
- [ ] Anomaly detection (Z-score)
- [ ] Chama reminders
- [ ] Savings milestones
- [ ] Morning briefing composer
- [ ] Silence budget + deduplication
- [ ] Worker feedback loop

### Phase 3: Context (Weeks 9-12)
- [ ] Location-aware timing
- [ ] Day-of-week seasonality
- [ ] Market opportunity alerts
- [ ] Credit readiness alerts
- [ ] Multi-language support
- [ ] Alert queue management

### Phase 4: Polish (Weeks 13-16)
- [ ] Seasonal awareness
- [ ] Cross-signal correlation
- [ ] Advanced suppression rules
- [ ] Alert effectiveness analytics
- [ ] Threshold auto-tuning
- [ ] Community benchmark (opt-in, anonymized)

---

## 9. Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Cloud dependency | None for triggering | Offline-first; network only for data enrichment |
| Alert language | Worker's primary dialect | Voice must feel native, not translated |
| Max alerts/day | 3 P1 + 5 P2 + 1 briefing | Anti-fatigue; most workers can't absorb more |
| Morning briefing time | 6:00-7:00 AM | Before market hours, after waking |
| P0 delivery | Interrupt with voice | Critical means critical |
| Threshold learning | 30-day rolling window | Enough data for personalization, recent enough to adapt |
| Silence as default | Yes | Msaidizi speaks when it has something valuable, not to fill air |

---

## 10. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| Alert fatigue → worker ignores all | High | Silence budget, adaptive thresholds, feedback loop |
| Wrong language/dialect | Medium | Explicit language preference + dialect detection |
| Alert during critical moment (serving customer) | High | P0 only during market hours, context-aware timing |
| False positive (alert for non-issue) | Medium | Conservative thresholds initially, learn from dismissals |
| TTS unintelligible in noisy market | Medium | Shorter messages, key numbers repeated, vibrate pre-alert |
| Offline model drifts (stale baselines) | Low | Periodic recalibration, confidence decay on old data |
| Worker never acknowledges → can't learn | Medium | Default to "useful if not dismissed within 24h" |

---

*This system makes Msaidizi a partner, not a tool. It watches while the worker works, speaks only when it matters, and learns what matters to each individual.*
