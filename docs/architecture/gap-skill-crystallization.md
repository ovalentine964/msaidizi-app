# Skill Crystallization System — Msaidizi

> **Design Doc v1.0** | 2026-07-24
> Pattern detection → automatic skill creation → lifecycle management

---

## 1. Detection Layer

### 1.1 What Counts as a "Repeated Pattern"?

Msaidizi monitors three signal types across all worker interactions:

| Signal Type | What It Detects | Example |
|---|---|---|
| **Time-based** | Same request at similar times | "What are today's market prices?" every morning at 7 AM |
| **Action-based** | Same sequence of tool calls / responses | Worker asks for restock check → compares 3 suppliers → places order |
| **Context-based** | Same request triggered by same conditions | "How much did I sell today?" asked after every market day |

### 1.2 Detection Mechanism

**Intent Fingerprinting:**
Every worker request is converted to a normalized intent fingerprint:
```
fingerprint = hash(normalized_intent + context_slots)
```

- Normalized intent: strip time references, replace specific values with slots (e.g., "price of tomatoes" → "price of {item}")
- Context slots: time of day, day of week, location, recent events (market day, restock trigger)

**Pattern Buffer:**
A rolling window of the last 100 interaction fingerprints, tagged with timestamps and outcomes.

**Match Algorithm:**
```
For each new interaction:
  1. Compute fingerprint
  2. Search buffer for ≥2 prior matches with same fingerprint
  3. If matches found:
     a. Check temporal regularity (time-based patterns)
     b. Check sequential co-occurrence (action-based patterns)
     c. Check conditional triggers (context-based patterns)
  4. If pattern score > threshold → flag for crystallization
```

### 1.3 Crystallization Threshold

| Pattern Type | Minimum Repetitions | Time Window | Regularity Requirement |
|---|---|---|---|
| Time-based | 3 occurrences | 7 days | ≥70% same time slot (±1hr) |
| Action-based | 3 occurrences | 14 days | ≥80% same action sequence |
| Context-based | 4 occurrences | 14 days | Same trigger condition detected |

**Why these numbers:**
- 3 repetitions filters out one-offs without requiring months of data
- 7–14 day windows catch weekly rhythms (market days, restock cycles)
- High regularity thresholds avoid false positives from coincidental patterns

**Anti-pattern rules (do NOT crystallize):**
- Patterns involving sensitive actions (payments, deletions) require 5+ occurrences
- Patterns that only occur under external prompting ("try doing X every day")
- Patterns with declining frequency (worker is stopping, not repeating)

---

## 2. Crystallization Pipeline

### 2.1 Pipeline Stages

```
┌─────────────┐     ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  Detection   │────▶│  Proposal    │────▶│  Confirmation│────▶│  Activation  │
│  (automatic) │     │  (auto-gen)  │     │  (worker)    │     │  (runtime)   │
└─────────────┘     └──────────────┘     └──────────────┘     └──────────────┘
     PATTERN            SKILL               YES/NO/ADJUST        SKILL
     DETECTED           TEMPLATE             RESPONSE            LIVE
```

### 2.2 Stage Details

#### Stage 1: Detection (Automatic)
- Pattern detection fires (see §1)
- System logs: pattern fingerprint, frequency, time distribution, associated context

#### Stage 2: Proposal Generation (Automatic)
- Msaidizi generates a **skill proposal** — a human-readable description of what the skill would do
- Proposal includes:
  - **Name**: descriptive, worker-friendly ("Morning Market Brief")
  - **Trigger**: when it fires (time, event, or request)
  - **Action**: what the agent will do
  - **Output**: what the worker receives
  - **Confidence**: how strong the pattern evidence is

**Example proposal:**
> *"I've noticed you ask about market prices almost every morning around 7 AM. Would you like me to prepare a daily market brief for you automatically? I'll check prices for your usual items and send you a summary before you head out."*

#### Stage 3: Worker Confirmation (Interactive)
Worker responds with one of:
- **✅ Yes** → skill activates as-proposed
- **🔧 Adjust** → worker modifies (different time, different items, different format)
- **❌ No** → pattern is suppressed for 30 days (don't nag)
- **🔇 Never** → this pattern type is permanently suppressed

**Key design principle:** The proposal is always in the worker's language (Swahili, Sheng, etc.), never in technical jargon. The worker should feel like the agent *noticed* something, not that it's running an algorithm.

#### Stage 4: Activation (Runtime)
- Skill is written as a `SKILL.md` module in the worker's skill directory
- Skill is registered in the worker's skill manifest
- Scheduled skills get a cron/heartbeat entry
- First run is flagged as a "trial" — worker can cancel after seeing it

### 2.3 Concrete Example: Morning Market Brief

```
Day 1:  Worker: "What are tomato prices in Kariakoo today?"
Day 2:  Worker: "Check market prices — tomatoes, onions, peppers"
Day 3:  Worker: "Morning! What's the market like today?"
        ─── DETECTION TRIGGERED (3x time-based, same time slot) ───
Day 3:  Msaidizi: "I see you check market prices most mornings. Want me to
         send you a price summary every morning before 7 AM? I'll cover
         tomatoes, onions, and peppers at Kariakoo market."
Day 3:  Worker: "Yes, and add cooking oil"
        ─── SKILL ACTIVATED ───
Day 4+: Msaidizi sends morning brief at 6:45 AM automatically
```

**Generated SKILL.md:**
```markdown
# skill: morning-market-brief
trigger: cron 0 6 45 * * *  # 6:45 AM daily
context: worker_location=Kariakoo, items=[tomatoes, onions, peppers, cooking_oil]
action: |
  1. Query current market prices for specified items
  2. Compare with yesterday's prices (flag >10% changes)
  3. Note any supply shortages reported
  4. Format as brief summary in worker's language
output: send_to_worker via preferred channel
lifecycle: active, created 2026-07-24, last_ran null
```

---

## 3. Skill Types for Informal Workers

### 3.1 Skill Catalog

Each skill below is a crystallizable pattern type. Msaidizi can create any of these when the pattern is detected.

---

#### 📋 Morning Briefing
- **Trigger:** Time-based (worker's morning routine)
- **Content:** Market conditions, weather, daily reminders, price highlights
- **Personalization:** Learns which items/categories the worker cares about
- **Output:** Short message (≤5 lines), voice option for low-literacy workers

#### 📦 Restock Alert
- **Trigger:** Context-based (estimated inventory depletion)
- **Logic:**
  ```
  For each tracked product:
    days_remaining = current_stock / avg_daily_sales
    if days_remaining < restock_threshold (default: 3 days):
      fire alert with suggested order quantity
  ```
- **Data source:** Sales patterns inferred from transaction history or worker's manual updates
- **Output:** "You may run out of cooking oil in 2 days. Your usual supplier (Mama Njeri) had it at TSh 12,000/jerry can last week. Want me to check current price?"

#### 💰 Price Check
- **Trigger:** Worker mentions buying a product, OR scheduled comparison
- **Action:** Compare prices across known suppliers, flag best deal
- **Intelligence:** Learns worker's preferred suppliers over time, includes transport cost if relevant
- **Output:** Ranked price list with supplier name and last-known price

#### 💸 Savings Nudge
- **Trigger:** Context-based (spending exceeds rolling average)
- **Logic:**
  ```
  if weekly_spending > 1.3 * rolling_4_week_avg:
    send nudge: "You've spent 30% more than usual this week.
    Biggest increase: [category]. Want to review?"
  ```
- **Sensitivity:** Configurable threshold (default 30% above average)
- **Tone:** Supportive, never judgmental. Uses worker's preferred framing.

#### 📅 Market Day Preparation
- **Trigger:** Time-based (day before known market days)
- **Content:**
  - What sold well on recent market days of same type
  - Weather forecast (rain = different demand)
  - Suggested stock quantities based on recent sales velocity
  - Any known supply disruptions
- **Output:** Actionable checklist, evening before market day

#### 📊 Weekly Report
- **Trigger:** Time-based (end of week, typically Sunday evening)
- **Content:**
  - Total revenue estimate (from recorded transactions)
  - Top-selling items
  - Comparison with previous week
  - Savings progress (if savings goal is set)
  - One actionable insight ("Cooking oil sales doubled — consider stocking more")
- **Output:** Simple summary, visual chart if channel supports it

### 3.2 Skill Composition

Skills can chain. Example:
```
Market Day Preparation triggers →
  Price Check (compare suppliers for needed stock) →
    Restock Alert (if any items below threshold) →
      Morning Briefing (includes prep summary)
```

The agent handles chaining automatically — the worker just sees coherent, timely help.

---

## 4. Skill Lifecycle

### 4.1 Lifecycle States

```
   ┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐
   │ PROPOSED │────▶│  TRIAL   │────▶│  ACTIVE  │────▶│ RETIRED  │
   └──────────┘     └──────────┘     └──────────┘     └──────────┘
        │                │                │    │              ▲
        │                │                │    │              │
        ▼                ▼                │    ▼              │
   ┌──────────┐    ┌──────────┐          │ ┌──────────┐      │
   │ REJECTED │    │ DISABLED │◀─────────┘ │ REFINED  │──────┘
   └──────────┘    └──────────┘            └──────────┘
                                                  │
                                                  ▼
                                           ┌──────────┐
                                           │  SHARED  │
                                           └──────────┘
```

### 4.2 State Transitions

| From | To | Trigger |
|---|---|---|
| PROPOSED | TRIAL | Worker confirms |
| PROPOSED | REJECTED | Worker declines |
| TRIAL | ACTIVE | First successful run + worker doesn't cancel |
| TRIAL | DISABLED | Worker cancels during trial |
| ACTIVE | REFINED | Worker adjusts parameters |
| ACTIVE | DISABLED | Worker pauses (temporary) |
| ACTIVE | RETIRED | Skill unused for 30+ days OR worker explicitly retires |
| REFINED | ACTIVE | Refinement saved |
| ACTIVE/REFINED | SHARED | Worker opts in to share anonymized version |
| DISABLED | ACTIVE | Worker re-enables |
| DISABLED | RETIRED | Disabled for 60+ days |

### 4.3 Refinement

Workers refine skills through natural language:
- "Can you send the market brief earlier? 6 AM instead of 6:45"
- "Stop including peppers in the price check"
- "Add rice to the morning brief"

The agent parses the adjustment and updates the SKILL.md. No technical interface needed.

**Refinement is tracked:**
```yaml
refinements:
  - date: 2026-07-28
    change: "time adjusted from 06:45 to 06:00"
  - date: 2026-08-03
    change: "removed peppers, added rice"
```

### 4.4 Sharing (Community Skills)

When a skill proves valuable, Msaidizi can suggest sharing it:

> *"Your morning market brief routine is really organized. Want to share the template with other Mama Lishe in your area? Your personal details stay private — just the general setup."*

**Sharing process:**
1. Worker opts in
2. Skill is anonymized (remove personal data, supplier names, specific quantities)
3. Generalized version is tagged with business type (e.g., "mama_lishe", "duka_owner", "mjumbe")
4. Shared skill appears as a suggestion for new workers with matching business type
5. Original worker gets credit ("This skill was shared by a fellow Mama Lishe")

**Privacy guarantees:**
- No personal financial data is shared
- No location data below city level
- No supplier relationships are exposed
- Only the *structure* of the skill is shared, not the worker's specific data

### 4.5 Retirement

Skills retire when:
- **Unused for 30 days**: Agent asks "You haven't used [skill] in a while. Still want it?" → if no response or "no", retire
- **Worker explicitly retires**: "Stop the morning brief"
- **Context changes**: Worker changes business type, location, or patterns fundamentally shift

Retired skills are archived (not deleted) — can be reactivated if the pattern returns.

---

## 5. Integration with Superagent Engine

### 5.1 Architecture Principle: Skills as Capabilities, Not Agents

```
┌─────────────────────────────────────────────────────────┐
│                    MSAIDIZI (ONE AGENT)                  │
│                                                         │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐     │
│  │   Context    │  │  Pattern    │  │   Skill     │     │
│  │   Engine     │  │  Detector   │  │   Registry  │     │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘     │
│         │                │                │             │
│  ┌──────┴────────────────┴────────────────┴──────┐      │
│  │              Core Reasoning Engine             │      │
│  └──────┬────────────────┬────────────────┬──────┘      │
│         │                │                │             │
│  ┌──────┴──────┐  ┌──────┴──────┐  ┌──────┴──────┐     │
│  │  Skill A    │  │  Skill B    │  │  Skill N    │     │
│  │  (on-demand)│  │  (on-demand)│  │  (on-demand)│     │
│  └─────────────┘  └─────────────┘  └─────────────┘     │
│                                                         │
│  Single context window. Single memory. Single identity. │
└─────────────────────────────────────────────────────────┘
```

**Key constraints:**
- Skills are **NOT** separate agents — they don't have their own context, memory, or API keys
- Skills are **loadable modules** — injected into the agent's context when triggered
- The agent always has access to the full worker context, regardless of which skill is active
- Multiple skills can compose in a single turn (morning brief + price check + restock alert)

### 5.2 Skill Loading (DeerFlow Pattern)

Skills use progressive loading to minimize context window usage:

```
Level 0: Skill name + trigger in manifest (always loaded, ~50 tokens)
Level 1: Skill SKILL.md loaded when trigger fires (~200-500 tokens)
Level 2: Skill-specific tools/prompts loaded when skill executes (~500-2000 tokens)
```

**Manifest (always in context):**
```json
{
  "skills": [
    {
      "name": "morning-market-brief",
      "trigger": "cron:06:45",
      "status": "active",
      "load_path": "skills/morning-market-brief/SKILL.md"
    },
    {
      "name": "restock-alert",
      "trigger": "context:inventory_low",
      "status": "active",
      "load_path": "skills/restock-alert/SKILL.md"
    }
  ]
}
```

When a trigger fires, the full SKILL.md is loaded into context. When the skill completes, it can be unloaded to free context space.

### 5.3 Shared Context Engine

All skills share:
- **Worker profile** (business type, preferences, language, literacy level)
- **Transaction history** (what was bought/sold, when, how much)
- **Relationship graph** (suppliers, customers, family)
- **Temporal context** (market days, seasons, holidays)
- **Conversation history** (recent interactions, open questions)

This means a skill never operates in isolation. The morning brief knows what the worker sold yesterday. The restock alert knows which supplier the worker prefers. The savings nudge knows the worker's income cycle.

### 5.4 Skill Execution Flow

```
1. TRIGGER fires (cron event, context condition, or worker request)
2. Skill manifest checked → matching skill found
3. SKILL.md loaded into context (Level 1)
4. Agent reads skill instructions + current worker context
5. Agent executes skill actions (may call tools: price APIs, calculations, etc.)
6. Agent formats output per skill spec + worker preferences
7. Output delivered to worker via preferred channel
8. Execution logged (for lifecycle management + pattern refinement)
```

### 5.5 Skill Creation at Runtime

When crystallization triggers, the agent creates a skill *during conversation*:

```
1. Agent detects pattern → generates proposal (Stage 2 of pipeline)
2. Worker confirms → agent writes SKILL.md file to worker's skill directory
3. Agent updates skill manifest
4. If scheduled: agent registers cron/heartbeat entry
5. Skill is live immediately — next trigger will execute it
```

No separate build step. No deployment. The agent writes its own capabilities.

---

## 6. Technical Implementation Notes

### 6.1 Storage

```
worker-data/
├── {worker_id}/
│   ├── profile.json          # Worker profile
│   ├── transactions/         # Transaction history
│   ├── patterns/
│   │   ├── buffer.json       # Rolling interaction fingerprint buffer
│   │   └── detections.json   # Confirmed pattern detections
│   ├── skills/
│   │   ├── manifest.json     # Active skill registry
│   │   ├── morning-market-brief/
│   │   │   └── SKILL.md      # Skill definition
│   │   ├── restock-alert/
│   │   │   └── SKILL.md
│   │   └── ...
│   └── skill-history/        # Retired/shared skill archive
```

### 6.2 Pattern Buffer Schema

```json
{
  "fingerprints": [
    {
      "id": "fp_20260724_001",
      "hash": "a3f2c9...",
      "intent": "market_price_check",
      "slots": {"items": ["tomatoes", "onions"], "market": "Kariakoo"},
      "timestamp": "2026-07-24T06:52:00+03:00",
      "context_tags": ["morning", "pre_market"],
      "outcome": "answered"
    }
  ],
  "patterns": [
    {
      "id": "pat_001",
      "fingerprint_hash": "a3f2c9...",
      "occurrences": [3, 4, 5],
      "first_seen": "2026-07-22",
      "last_seen": "2026-07-24",
      "time_distribution": {"06:00-07:00": 3},
      "status": "detected"
    }
  ]
}
```

### 6.3 Multi-Language Support

All skill proposals, confirmations, and outputs are generated in the worker's preferred language. The SKILL.md itself is stored in a language-neutral format with i18n hooks:

```yaml
output_template:
  en: "Market prices for {date}: {items}"
  sw: "Bei za soko tarehe {date}: {items}"
```

---

## 7. Design Principles

1. **Invisible until valuable.** The worker should never see "pattern detection algorithms." They should feel like the agent *knows them* and *anticipates their needs.*

2. **Worker is always in control.** Every skill requires explicit confirmation to activate. The agent proposes, the worker disposes.

3. **Fail gracefully.** A skill that sends a wrong price is worse than no skill. Skills should have confidence thresholds and default to "I'm not sure, let me check" rather than hallucinating.

4. **Progressive complexity.** Start with simple time-based triggers. Add context-based and action-based patterns as the worker's trust and data grow.

5. **Privacy by design.** Skills are personal. Sharing is opt-in and anonymized. The worker's data never leaves their control without explicit consent.

6. **One agent, many skills.** Skills enhance the agent; they don't fragment it. The worker always talks to Msaidizi, not to "the morning brief agent" or "the restock agent."

---

## 8. Future Considerations

- **Cross-worker skill inheritance:** New workers in the same business type get suggested skills from the community pool
- **Seasonal pattern detection:** Skills that activate during specific seasons (rainy season = different demand patterns)
- **Multi-worker coordination:** Skills that coordinate across a group (e.g., group purchasing alerts for a cooperative)
- **Skill marketplace:** Curated, verified skills that can be "installed" by workers (similar to app store, but for agent capabilities)
- **Feedback loops:** Skill accuracy tracked over time; skills that deliver bad info get flagged for refinement or retirement
