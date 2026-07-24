# On-Device Adaptive Learning: How Msaidizi Grows With Each Worker

**Version:** 1.0
**Date:** 2026-07-25
**Status:** Technical Design

---

## Executive Summary

Msaidizi is not a static tool. It is a **learning companion** that gets smarter with every voice note, every sale recorded, and every piece of advice given. This document defines five on-device learning loops that make Msaidizi adapt to each individual worker — their vocabulary, their business rhythms, their dialect, their responsiveness to advice, and their cash flow patterns.

All learning happens **on-device**. No worker's data leaves their phone unless they explicitly opt in. This is not just a privacy feature — it is a design constraint that produces a better, more personalized system.

---

## Design Principles

| Principle | Rationale |
|---|---|
| **Learn from every interaction** | No wasted signal. Every voice note is a training example. |
| **Fail gracefully on low data** | A new worker on Day 1 gets generic models. A worker on Day 90 gets a personalized one. The transition is smooth. |
| **Explain what was learned** | "I learned that 'sukari' means sugar. Correct?" — builds trust. |
| **On-device first, cloud optional** | All learning loops run on-device. Cloud sync is for backup and cross-device only. |
| **Forget gracefully** | Old patterns fade. A worker who changes business types should not be haunted by old patterns forever. |

---

## 1. Vocabulary Learning

### The Problem

Workers use product names, slang, and dialect words that are not in any standard dictionary:

- "sukari" (sugar) — standard Swahili, but not in English-centric NLU
- "unga wa sima" (maize flour) — multi-word product name
- "nduru" — local slang for a specific product
- "kilo tatu za nyama" — mixed unit + product phrases

Msaidizi must **recognize**, **disambiguate**, and **remember** these terms.

### Architecture: Three-Layer Vocabulary

```
┌─────────────────────────────────────────────┐
│  Layer 3: PERSONAL DICTIONARY (per-worker)  │  ← Learns from this worker
│  Stored: local SQLite / JSON                │
│  Examples: "nduru" → "cooking oil (local)"  │
├─────────────────────────────────────────────┤
│  Layer 2: COMMUNITY DICTIONARY (region)     │  ← Learns from anonymized clusters
│  Stored: bundled + OTA updates              │
│  Examples: "sukari" → "sugar"               │
├─────────────────────────────────────────────┤
│  Layer 1: BASE VOCABULARY (language model)  │  ← Pretrained
│  Stored: model weights                      │
│  Examples: standard Swahili, English         │
└─────────────────────────────────────────────┘
```

### How Learning Happens

#### Step 1: Unknown Word Detection

During ASR transcription, Msaidizi detects **unknown tokens** — words or phrases the recognizer produces with low confidence or maps to OOV (out-of-vocabulary) markers.

```
Input audio: "Nimeuza nduru mbili leo"
ASR output:  "nimeuza [OOV:nduru] mbili leo"
Confidence:  nduru → 0.34 (low)
```

#### Step 2: Contextual Inference

When an unknown word appears, Msaidizi uses the surrounding context to infer meaning:

- **Slot filling:** "nimeuza [PRODUCT] mbili leo" → the OOV word is likely a product name
- **Quantity pattern:** "mbili" (two) + unit context → suggests countable items
- **Co-occurrence tracking:** If "nduru" always appears alongside "mafuta" (oil) in other transactions → high probability it is a type of cooking oil

**Technical mechanism:**

```python
class VocabularyLearner:
    def __init__(self):
        self.personal_dict = {}      # word → {meaning, confidence, count, first_seen, last_seen}
        self.unknown_log = []        # pending unknown words with context
        self.co_occurrence = {}      # word → Counter of co-occurring known words

    def on_transcription(self, tokens: list[str], confidences: list[float]):
        for token, conf in zip(tokens, confidences):
            if conf < CONFIDENCE_THRESHOLD and token not in self.known_vocab:
                context = self.extract_context(tokens, token)
                self.log_unknown(token, context)
                self.update_co_occurrence(token, context)

    def resolve_unknown(self, token: str) -> dict | None:
        """Attempt to resolve an unknown word using accumulated context."""
        co = self.co_occurrence.get(token, {})
        if not co:
            return None

        # If top co-occurring words are all products, classify as product
        top_co = co.most_common(5)
        product_score = sum(1 for w, _ in top_co if self.is_product(w))

        if product_score >= 3:
            # Infer product category from co-occurring products
            category = self.infer_category(top_co)
            return {
                "type": "product",
                "category": category,
                "confidence": min(product_score / 5, 0.9),
                "needs_confirmation": True
            }
        return None

    def confirm_word(self, token: str, meaning: str):
        """Worker confirms or corrects a learned word."""
        self.personal_dict[token] = {
            "meaning": meaning,
            "confidence": 1.0,
            "confirmed": True,
            "count": self.co_occurrence.get(token, {}).total()
        }
        # Promote to active vocabulary
        self.active_vocab.add(token)
```

#### Step 3: Confirmation Loop

When Msaidizi learns a new word, it asks for confirmation (once, not repeatedly):

> 📱 "I heard you say 'nduru' — is that cooking oil? Reply YES or tell me what it means."

Options for confirmation:
- **Voice:** "Nduru ni mafuta ya kupikia" (Nduru is cooking oil)
- **Button:** Quick-confirm button during next transaction
- **Implicit:** If worker doesn't correct Msaidizi's interpretation over N uses, treat as confirmed

#### Step 4: Promotion Path

```
Unknown word detected (confidence < 0.4)
    ↓
Logged with context (1 occurrence)
    ↓
Context accumulation (3+ occurrences)
    ↓
Automatic inference + worker confirmation
    ↓
Added to Personal Dictionary
    ↓
Frequently used across workers in region → promoted to Community Dictionary
    ↓
Widely used across all regions → added to Base Vocabulary (OTA update)
```

### Technical Implementation

| Component | Technology | Size Budget |
|---|---|---|
| Personal dictionary | SQLite on-device | ~50 KB per worker |
| Community dictionary | Bundled JSON + OTA | ~500 KB per region |
| Co-occurrence tracker | In-memory Counter, persisted to SQLite | ~20 KB |
| ASR confidence scoring | Part of ASR model output | 0 additional bytes |
| Context embedding | Lightweight classifier (decision tree) | ~10 KB |

### Edge Cases

- **Homophones:** "sukari" (sugar) vs. "sukari" (slang for something else) → disambiguate via business context (sugar is more common in inventory)
- **Code-mixed input:** "Nime-buy vitu tatu" → handle English loanwords naturally, learn "buy" as synonym for "nunua"
- **Misspellings in text input:** Fuzzy matching with Levenshtein distance ≤ 2

---

## 2. Business Pattern Learning

### The Problem

Every worker has rhythms. Mama Amina restocks tomatoes on Mondays. John's shop peaks at lunchtime. Wanjiku sells more at month-end when salaries arrive. Msaidizi must detect, store, and act on these patterns — **without the worker explicitly teaching them.**

### Architecture: Pattern Detection Pipeline

```
Transaction Stream
       │
       ▼
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  Temporal     │────▶│  Pattern     │────▶│  Pattern     │
│  Aggregator   │     │  Detector    │     │  Store       │
│              │     │              │     │              │
│  - By hour   │     │  - Periodic  │     │  - Confirmed │
│  - By day    │     │  - Trending  │     │  - Suspected │
│  - By week   │     │  - Anomalous │     │  - Expired   │
│  - By month  │     │  - Seasonal  │     │              │
└──────────────┘     └──────────────┘     └──────────────┘
                                                  │
                                                  ▼
                                         ┌──────────────┐
                                         │  Advisory     │
                                         │  Engine       │
                                         │              │
                                         │  Uses patterns│
                                         │  to generate  │
                                         │  advice       │
                                         └──────────────┘
```

### Pattern Types and Detection Methods

#### 2.1 Periodic Patterns (Restocking Schedules)

**Detection:** Autocorrelation on transaction time series.

```python
class PeriodicDetector:
    def detect_restocking(self, transactions: list[Transaction]) -> list[Pattern]:
        """
        Given a worker's transaction history, detect periodic restocking patterns.
        Uses lightweight autocorrelation — no FFT needed for daily data.
        """
        # Build daily spend series
        daily_spend = self.aggregate_by_day(transactions, type="purchase")
        if len(daily_spend) < 28:  # Need at least 4 weeks
            return []

        patterns = []

        # Check weekly periodicity (lag 7)
        for product in self.get_products(transactions):
            product_series = self.filter_by_product(daily_spend, product)
            weekly_corr = self.autocorrelation(product_series, lag=7)
            biweekly_corr = self.autocorrelation(product_series, lag=14)

            if weekly_corr > 0.6:
                preferred_day = self.find_preferred_day(product_series, lag=7)
                patterns.append(Pattern(
                    type="weekly_restock",
                    product=product,
                    day=preferred_day,
                    confidence=weekly_corr,
                    evidence_count=self.count_matching(product_series, lag=7)
                ))

        return patterns

    def autocorrelation(self, series: list[float], lag: int) -> float:
        """Pearson autocorrelation at given lag. O(n) computation."""
        n = len(series)
        if n <= lag:
            return 0.0
        mean = sum(series) / n
        var = sum((x - mean) ** 2 for x in series) / n
        if var == 0:
            return 0.0
        cov = sum((series[i] - mean) * (series[i + lag] - mean)
                  for i in range(n - lag)) / (n - lag)
        return cov / var
```

**Confidence thresholds:**
- `correlation > 0.6` and `evidence_count >= 4` → "Suspected pattern"
- `correlation > 0.7` and `evidence_count >= 8` → "Confirmed pattern"
- Trigger confirmation: "I notice you restock tomatoes every Monday. Should I remind you?"

#### 2.2 Trending Patterns (Growth/Decline)

**Detection:** Linear regression on rolling windows.

```python
class TrendDetector:
    def detect_trends(self, transactions: list[Transaction]) -> list[Pattern]:
        """
        Detect upward or downward trends in sales or specific products.
        Uses simple linear regression — no heavy libraries needed.
        """
        patterns = []
        weekly_sales = self.aggregate_by_week(transactions, type="sale")

        if len(weekly_sales) < 8:  # Need at least 8 weeks
            return []

        # Overall sales trend
        slope, r_squared = self.linear_regression(
            x=list(range(len(weekly_sales))),
            y=weekly_sales
        )

        if r_squared > 0.5:  # Meaningful trend
            direction = "growing" if slope > 0 else "declining"
            weekly_change_pct = (slope / (sum(weekly_sales) / len(weekly_sales))) * 100
            patterns.append(Pattern(
                type="sales_trend",
                direction=direction,
                weekly_change_pct=weekly_change_pct,
                confidence=r_squared,
                period_weeks=len(weekly_sales)
            ))

        # Per-product trends (top 5 products by volume)
        for product in self.get_top_products(transactions, n=5):
            product_weekly = self.aggregate_by_week(
                self.filter_by_product(transactions, product), type="sale"
            )
            if len(product_weekly) >= 6:
                slope, r2 = self.linear_regression(
                    list(range(len(product_weekly))), product_weekly
                )
                if r2 > 0.4 and abs(slope) > self.min_significant_change(product_weekly):
                    patterns.append(Pattern(
                        type="product_trend",
                        product=product,
                        direction="growing" if slope > 0 else "declining",
                        confidence=r2
                    ))

        return patterns

    def linear_regression(self, x: list[float], y: list[float]) -> tuple[float, float]:
        """Simple OLS. Returns (slope, r_squared). No numpy needed."""
        n = len(x)
        sum_x = sum(x)
        sum_y = sum(y)
        sum_xy = sum(xi * yi for xi, yi in zip(x, y))
        sum_x2 = sum(xi ** 2 for xi in x)

        denom = n * sum_x2 - sum_x ** 2
        if denom == 0:
            return 0.0, 0.0

        slope = (n * sum_xy - sum_x * sum_y) / denom
        intercept = (sum_y - slope * sum_x) / n

        ss_res = sum((yi - (slope * xi + intercept)) ** 2 for xi, yi in zip(x, y))
        ss_tot = sum((yi - sum_y / n) ** 2 for yi in y)

        r_squared = 1 - (ss_res / ss_tot) if ss_tot > 0 else 0.0
        return slope, r_squared
```

#### 2.3 Seasonal / Calendar Patterns

**Detection:** Statistical comparison of periods.

```python
class SeasonalDetector:
    def detect_monthly_patterns(self, transactions: list[Transaction]) -> list[Pattern]:
        """
        Detect month-end salary effects, holiday spikes, etc.
        """
        patterns = []

        # Split each month into 3 periods: days 1-10, 11-20, 21-end
        period_sales = {1: [], 2: [], 3: []}
        for month in self.get_unique_months(transactions):
            month_txns = self.filter_by_month(transactions, month)
            for period in [1, 2, 3]:
                period_txns = self.filter_by_period(month_txns, period)
                period_sales[period].append(self.total_sales(period_txns))

        # Check if period 3 (month-end) is significantly higher
        if all(len(v) >= 3 for v in period_sales.values()):
            avg_early = sum(period_sales[1]) / len(period_sales[1])
            avg_mid = sum(period_sales[2]) / len(period_sales[2])
            avg_late = sum(period_sales[3]) / len(period_sales[3])

            if avg_late > avg_early * 1.3 and avg_late > avg_mid * 1.2:
                # Month-end spike detected
                effect_size = (avg_late - avg_early) / avg_early
                patterns.append(Pattern(
                    type="month_end_spike",
                    effect_size_pct=effect_size * 100,
                    confidence=self.t_test(period_sales[3], period_sales[1]),
                    advice="Stock up before the 21st — sales typically increase "
                           f"{effect_size * 100:.0f}% at month-end."
                ))

        return patterns
```

#### 2.4 Time-of-Day Patterns

```python
class HourlyDetector:
    def detect_peak_hours(self, transactions: list[Transaction]) -> list[Pattern]:
        """Detect peak sales hours."""
        hourly = self.aggregate_by_hour(transactions)
        if len(transactions) < 50:
            return []

        overall_avg = sum(hourly) / max(len(hourly), 1)
        peaks = []

        for hour, total in enumerate(hourly):
            if total > overall_avg * 1.5:
                peaks.append(hour)

        if peaks:
            # Merge consecutive hours into windows
            windows = self.merge_consecutive(peaks)
            return [Pattern(
                type="peak_hours",
                windows=windows,
                confidence=min(len(transactions) / 200, 1.0)
            ) for windows in windows]
        return []
```

### Pattern Store Schema

```sql
CREATE TABLE worker_patterns (
    id TEXT PRIMARY KEY,
    pattern_type TEXT NOT NULL,        -- 'weekly_restock', 'peak_hours', 'month_end_spike', etc.
    product TEXT,                       -- NULL if not product-specific
    params TEXT NOT NULL,              -- JSON: {day: 'Monday', hour: 7, effect_size: 30, ...}
    confidence REAL NOT NULL,          -- 0.0 to 1.0
    evidence_count INTEGER NOT NULL,   -- number of supporting data points
    status TEXT NOT NULL,              -- 'suspected', 'confirmed', 'expired'
    first_detected TEXT NOT NULL,      -- ISO date
    last_confirmed TEXT NOT NULL,      -- ISO date
    last_triggered TEXT,               -- when advisory used this pattern
    times_used INTEGER DEFAULT 0,
    times_followed INTEGER DEFAULT 0,  -- worker acted on advice based on this pattern
    decay_rate REAL DEFAULT 0.05       -- how fast confidence decays without reinforcement
);

-- Pattern history for accuracy tracking
CREATE TABLE pattern_predictions (
    id TEXT PRIMARY KEY,
    pattern_id TEXT NOT NULL,
    predicted_value REAL,
    actual_value REAL,
    prediction_date TEXT NOT NULL,
    error REAL,                         -- actual - predicted
    FOREIGN KEY (pattern_id) REFERENCES worker_patterns(id)
);
```

### Pattern Lifecycle

```
Detection (suspicious signal)
    ↓
Status: SUSPECTED (shown to worker with low-confidence tag)
    ↓
Reinforcement (pattern repeats) → Status: CONFIRMED
    ↓
Active use in advisory engine
    ↓
Decay monitoring: if pattern stops matching → decay confidence
    ↓
Confidence < 0.2 → Status: EXPIRED (archived, not deleted)
```

### Forgetting Mechanism

Patterns are not permanent. People change businesses, routines shift, seasons end.

```python
def apply_decay(self):
    """Run daily. Decay patterns that haven't been reinforced."""
    patterns = self.get_active_patterns()
    now = datetime.now()

    for p in patterns:
        days_since_confirmation = (now - p.last_confirmed).days
        if days_since_confirmation > 30:
            # Apply exponential decay
            p.confidence *= (1 - p.decay_rate) ** (days_since_confirmation - 30)
            if p.confidence < 0.2:
                p.status = "expired"
            self.update_pattern(p)
```

---

## 3. Dialect Adaptation

### The Problem

East African workers code-switch fluidly between:
- **Kiswahili** (national language): "Nimeuza vitu vingi leo"
- **English** (business/tech): "I need to check my stock balance"
- **Sheng** (urban slang): "Nimepiga sales mob leo"
- **Local languages** (Kikuyu, Luo, Kamba, etc.): Regional terms mixed in

Msaidizi must understand ALL of these, and learn which patterns THIS worker uses.

### Architecture: Dialect Profile

Each worker builds a **dialect profile** — a statistical model of their language use.

```python
class DialectProfile:
    def __init__(self, worker_id: str):
        self.worker_id = worker_id
        self.language_mix = {
            "sw": 0.0,    # Kiswahili
            "en": 0.0,    # English
            "sh": 0.0,    # Sheng
            "local": 0.0  # Local language
        }
        self.common_phrases = {}      # phrase → frequency
        self.code_switch_points = {}  # "at X, switch to Y" patterns
        self.sentence_templates = []  # common sentence structures
        self.loanword_map = {}        # English words used in Swahili context
```

### Learning Process

#### 3.1 Language Detection Per Utterance

```python
def classify_utterance(self, text: str) -> dict[str, float]:
    """
    Classify the language mix of a single utterance.
    Uses a lightweight token-level classifier.
    """
    tokens = tokenize(text)
    scores = {"sw": 0, "en": 0, "sh": 0, "local": 0}

    for token in tokens:
        if token in self.swahili_lexicon:
            scores["sw"] += 1
        elif token in self.english_lexicon:
            scores["en"] += 1
        elif token in self.sheng_lexicon:
            scores["sh"] += 1
        elif token in self.worker_personal_dict:
            # Already-learned words — use their known language
            lang = self.worker_personal_dict[token].get("language", "sw")
            scores[lang] += 1
        else:
            # Unknown — use character-level heuristics
            scores[self.guess_language_from_chars(token)] += 1

    total = sum(scores.values()) or 1
    return {k: v / total for k, v in scores.items()}
```

#### 3.2 Code-Switch Point Detection

```python
def detect_code_switches(self, text: str) -> list[dict]:
    """
    Detect where the worker switches languages mid-sentence.
    Example: "Nimepiga the sales" → Sw→En switch at "the"
    """
    tokens = tokenize(text)
    switches = []
    current_lang = None

    for i, token in enumerate(tokens):
        token_lang = self.classify_token(token)
        if current_lang and token_lang != current_lang and token_lang != "unknown":
            switches.append({
                "position": i,
                "from": current_lang,
                "to": token_lang,
                "context": tokens[max(0, i-2):i+3]
            })
        if token_lang != "unknown":
            current_lang = token_lang

    return switches
```

#### 3.3 Dialect Profile Update

```python
def update_profile(self, utterance: str):
    """Update dialect profile after each utterance."""
    mix = self.classify_utterance(utterance)

    # Exponential moving average (alpha=0.1 for slow adaptation)
    alpha = 0.1
    for lang in self.language_mix:
        self.language_mix[lang] = (1 - alpha) * self.language_mix[lang] + alpha * mix[lang]

    # Track code-switch patterns
    switches = self.detect_code_switches(utterance)
    for sw in switches:
        key = f"{sw['from']}→{sw['to']}"
        self.code_switch_points[key] = self.code_switch_points.get(key, 0) + 1

    # Track common phrases (bigrams and trigrams)
    tokens = tokenize(utterance)
    for n in [2, 3]:
        for ngram in self.get_ngrams(tokens, n):
            self.common_phrases[ngram] = self.common_phrases.get(ngram, 0) + 1
```

#### 3.4 Practical Adaptations

The dialect profile drives concrete behavior changes:

| Signal | Adaptation |
|---|---|
| Worker uses 70% Swahili, 30% English | Msaidizi responds primarily in Swahili with English business terms |
| Worker code-switches at "amount" → "kiasi" | Msaidizi accepts both: "amount" or "kiasi" in voice commands |
| Worker uses Sheng heavily | Msaidizi learns Sheng synonyms: "mob" = "nyingi", "piga" = "uza" |
| Worker uses local language words | Msaidizi maps them via personal dictionary |

#### 3.5 ASR Personalization

The dialect profile feeds back into ASR to improve recognition:

```python
def generate_custom_lm_weights(self) -> dict:
    """
    Generate language model boosting weights for ASR.
    Words/phrases this worker uses frequently get higher probability.
    """
    weights = {}

    # Boost personal vocabulary
    for word, entry in self.worker_personal_dict.items():
        weights[word] = entry.get("frequency", 1) * 1.5

    # Boost common phrases (word sequences get higher probability)
    for phrase, count in self.common_phrases.items():
        if count >= 3:
            weights[phrase] = count * 1.2

    # Boost Sheng terms if worker uses Sheng
    if self.language_mix.get("sh", 0) > 0.1:
        for word in self.sheng_lexicon:
            if word in self.common_phrases:
                weights[word] *= 1.3

    return weights
```

### Data Requirements

- **Cold start (Day 1-7):** Use generic multilingual model. Worker's dialect profile starts empty.
- **Warm (Day 7-30):** Language mix stabilizes. Basic code-switch patterns detected.
- **Personalized (Day 30+):** Full dialect profile. Custom LM weights active. Sheng/local vocabulary mapped.

---

## 4. Advice Quality Learning

### The Problem

Msaidizi gives advice: "You should restock tomatoes — you usually sell out by Wednesday." Sometimes the worker follows it. Sometimes they ignore it. Sometimes the advice is good, sometimes it is not. Msaidizi must learn which advice works for THIS worker.

### Architecture: Advice Outcome Tracking

```
Advice Given → Outcome Observed → Feedback Score → Adjusted Model
     │                │                  │               │
     │          Was it followed?    Was the outcome    Update advice
     │          Was the outcome    positive or         confidence scores
     │          positive?          negative?           per pattern
     ▼                ▼                  ▼               ▼
┌──────────┐   ┌──────────┐     ┌──────────┐     ┌──────────┐
│ Advice   │   │ Outcome  │     │ Scoring  │     │ Model    │
│ Log      │   │ Detector │     │ Engine   │     │ Updater  │
└──────────┘   └──────────┘     └──────────┘     └──────────┘
```

### Advice Types

| Advice Type | Example | Outcome Signal |
|---|---|---|
| Restock reminder | "Restock tomatoes today" | Worker restocks within 24h? Sales follow? |
| Price suggestion | "Consider raising bread price to 60 KES" | Worker changes price? Revenue impact? |
| Cash flow warning | "You may run low on cash by Friday" | Does cash run low? Does worker save? |
| Sales opportunity | "Demand for soap is high this week" | Does worker stock more soap? Sales increase? |
| Expense alert | "You spent 40% more on transport this week" | Does worker reduce transport costs? |

### Outcome Detection

```python
class OutcomeDetector:
    def detect_outcome(self, advice: Advice, transactions: list[Transaction]) -> Outcome:
        """
        Given an advice record and subsequent transactions, determine outcome.
        """
        window_start = advice.timestamp
        window_end = window_start + timedelta(days=advice.followup_window_days)

        relevant_txns = [
            t for t in transactions
            if window_start <= t.timestamp <= window_end
        ]

        if advice.type == "restock_reminder":
            return self._detect_restock_outcome(advice, relevant_txns)
        elif advice.type == "price_suggestion":
            return self._detect_price_outcome(advice, relevant_txns)
        elif advice.type == "cash_flow_warning":
            return self._detect_cashflow_outcome(advice, relevant_txns)
        # ... other types

    def _detect_restock_outcome(self, advice, txns) -> Outcome:
        product = advice.params["product"]
        was_restocked = any(
            t.type == "purchase" and t.product == product
            for t in txns
        )
        if was_restocked:
            # Did sales of that product increase?
            sales_after = sum(
                t.amount for t in txns
                if t.type == "sale" and t.product == product
            )
            # Compare to baseline (same product, same days, previous weeks)
            baseline = self.get_baseline_sales(
                advice.worker_id, product,
                advice.timestamp, advice.params.get("window_days", 7)
            )
            revenue_lift = sales_after - baseline

            return Outcome(
                followed=True,
                positive=revenue_lift > 0,
                revenue_lift=revenue_lift,
                confidence=0.8
            )
        else:
            return Outcome(followed=False, positive=None, revenue_lift=0, confidence=0.5)
```

### Scoring Engine

```python
class AdviceScorer:
    def __init__(self):
        self.advice_scores = {}  # advice_type+pattern → running score

    def update_score(self, advice_key: str, outcome: Outcome):
        """
        Update the quality score for a type of advice using Bayesian updating.
        """
        if advice_key not in self.advice_scores:
            self.advice_scores[advice_key] = {
                "alpha": 1,   # Beta distribution prior (successes)
                "beta": 1,    # Beta distribution prior (failures)
                "total_given": 0,
                "total_followed": 0,
                "total_positive": 0
            }

        score = self.advice_scores[advice_key]
        score["total_given"] += 1

        if outcome.followed:
            score["total_followed"] += 1
            if outcome.positive:
                score["alpha"] += 1
                score["total_positive"] += 1
            else:
                score["beta"] += 1
        else:
            # Not followed — slight penalty if this advice is frequently ignored
            if score["total_given"] > 5:
                follow_rate = score["total_followed"] / score["total_given"]
                if follow_rate < 0.3:
                    # Worker consistently ignores this type of advice
                    score["beta"] += 0.5

    def get_expected_quality(self, advice_key: str) -> float:
        """Expected probability that this advice will be followed AND positive."""
        score = self.advice_scores.get(advice_key)
        if not score:
            return 0.5  # Neutral prior
        # Beta distribution mean
        return score["alpha"] / (score["alpha"] + score["beta"])

    def should_give_advice(self, advice_key: str, threshold: float = 0.3) -> bool:
        """
        Decide whether to give advice of this type.
        Don't give advice that is consistently ignored or leads to bad outcomes.
        """
        quality = self.get_expected_quality(advice_key)
        score = self.advice_scores.get(advice_key, {})

        # Don't suppress new advice types (need data first)
        if score.get("total_given", 0) < 5:
            return True

        # Suppress if quality is below threshold
        if quality < threshold:
            return False

        # Suppress if follow rate is very low (annoying, not useful)
        follow_rate = score.get("total_followed", 0) / max(score.get("total_given", 1), 1)
        if follow_rate < 0.15:
            return False

        return True
```

### Feedback Signals (Implicit and Explicit)

| Signal Type | How Detected | Reliability |
|---|---|---|
| **Explicit feedback** | Worker says "that was helpful" or "don't remind me about that" | Very high |
| **Followed action** | Transaction matches advice within window | High |
| **Revenue outcome** | Measurable business impact | High (but delayed) |
| **Ignored advice** | No matching action within window | Medium (might be delayed) |
| **Repeated request** | Worker asks about same topic advice covered | High (advice was relevant) |
| **Correction** | Worker corrects Msaidizi's interpretation | Very high |

### Advice Quality Dashboard (Worker-Facing)

Periodically, Msaidizi can share its learning:

> 📱 "This week I gave you 5 suggestions. You followed 3, and your sales went up 12%. I'm learning what works for you!"

This builds trust and gives the worker agency over the learning loop.

---

## 5. Cash Flow Prediction Learning

### The Problem

Msaidizi predicts future cash flow: "You'll have about 15,000 KES by Friday." These predictions must improve over time as Msaidizi learns this worker's specific patterns.

### Architecture: Adaptive Prediction Model

```
┌─────────────────────────────────────────────┐
│           PREDICTION MODEL STACK            │
├─────────────────────────────────────────────┤
│  Layer 3: Pattern-Adjusted Prediction       │
│  Uses learned patterns (Section 2)          │
│  "It's month-end → expect 30% more sales"   │
├─────────────────────────────────────────────┤
│  Layer 2: Personal Trend Model              │
│  Linear regression on worker's history      │
│  Adjusted weekly based on prediction errors │
├─────────────────────────────────────────────┤
│  Layer 1: Base Forecast                     │
│  Simple moving average                      │
│  Fallback when insufficient data            │
└─────────────────────────────────────────────┘
```

### Prediction Pipeline

```python
class CashFlowPredictor:
    def predict(self, horizon_days: int = 7) -> Prediction:
        """
        Predict cash flow for the next N days.
        Uses the most sophisticated model available given data.
        """
        history_days = len(self.daily_cashflow)

        if history_days < 7:
            return self._base_forecast(horizon_days)
        elif history_days < 30:
            return self._personal_trend_forecast(horizon_days)
        else:
            return self._pattern_adjusted_forecast(horizon_days)

    def _base_forecast(self, horizon_days: int) -> Prediction:
        """Layer 1: Simple moving average. For new workers."""
        recent = self.daily_cashflow[-min(7, len(self.daily_cashflow)):]
        avg_daily = sum(recent) / len(recent)
        predicted = [avg_daily] * horizon_days
        confidence = [0.3] * horizon_days  # Low confidence

        return Prediction(
            values=predicted,
            confidence_intervals=self._wide_intervals(predicted, confidence),
            model_used="base_moving_avg",
            data_points=len(recent)
        )

    def _personal_trend_forecast(self, horizon_days: int) -> Prediction:
        """Layer 2: Linear trend with weekly seasonality."""
        data = self.daily_cashflow[-28:]  # Last 4 weeks

        # Fit trend
        slope, intercept = self._fit_trend(data)

        # Fit day-of-week seasonality
        dow_factors = self._fit_dow_seasonality(data)

        predicted = []
        confidence = []
        for d in range(horizon_days):
            day_idx = len(data) + d
            trend_val = slope * day_idx + intercept
            dow = (self.start_dow + day_idx) % 7
            seasonal = dow_factors.get(dow, 1.0)
            predicted.append(trend_val * seasonal)

            # Confidence decreases with horizon
            base_conf = min(len(data) / 60, 0.85)
            confidence.append(base_conf * (0.95 ** d))

        return Prediction(
            values=predicted,
            confidence_intervals=self._moderate_intervals(predicted, confidence),
            model_used="personal_trend",
            data_points=len(data)
        )

    def _pattern_adjusted_forecast(self, horizon_days: int) -> Prediction:
        """Layer 3: Full prediction with learned pattern adjustments."""
        # Start with Layer 2 forecast
        base = self._personal_trend_forecast(horizon_days)

        # Apply pattern adjustments
        adjusted = list(base.values)
        adjustments = []

        for pattern in self.pattern_store.get_active_patterns():
            if pattern.type == "month_end_spike":
                # Boost predictions for days in month-end period
                for d in range(horizon_days):
                    target_date = self.today + timedelta(days=d)
                    if target_date.day >= 21:
                        boost = pattern.params["effect_size_pct"] / 100
                        adjusted[d] *= (1 + boost)
                        adjustments.append(f"month_end +{boost*100:.0f}% on day {d}")

            elif pattern.type == "weekly_restock":
                # Increase expenses on restock day, then increase sales after
                for d in range(horizon_days):
                    target_date = self.today + timedelta(days=d)
                    if target_date.strftime("%A") == pattern.params["day"]:
                        # Restock = cash outflow
                        avg_restock = pattern.params.get("avg_amount", 0)
                        adjusted[d] -= avg_restock
                        # But sales increase 1-2 days after
                        if d + 1 < horizon_days:
                            adjusted[d + 1] += avg_restock * 1.3
                        adjustments.append(
                            f"restock {pattern.product} on {pattern.params['day']}"
                        )

        # Expand confidence intervals for pattern-adjusted predictions
        # (more variables = more uncertainty)
        confidence = [
            c * (0.9 ** len(adjustments))
            for c in base.confidence
        ]

        return Prediction(
            values=adjusted,
            confidence_intervals=self._moderate_intervals(adjusted, confidence),
            model_used="pattern_adjusted",
            adjustments=adjustments,
            data_points=len(self.daily_cashflow)
        )
```

### Prediction Error Tracking and Model Improvement

```python
class PredictionTracker:
    def __init__(self):
        self.predictions = []  # list of (date, predicted, actual, model_used)
        self.model_errors = {}  # model_name → list of errors

    def record_actual(self, date: str, actual_value: float):
        """Called daily with actual cash flow to compare against predictions."""
        pred = self.get_prediction_for_date(date)
        if pred:
            error = actual_value - pred.value
            pct_error = error / max(abs(actual_value), 1) * 100

            self.predictions.append({
                "date": date,
                "predicted": pred.value,
                "actual": actual_value,
                "error": error,
                "pct_error": pct_error,
                "model": pred.model_used
            })

            self.model_errors.setdefault(pred.model_used, []).append(pct_error)

    def get_model_quality(self, model_name: str) -> dict:
        """Calculate accuracy metrics for a specific model."""
        errors = self.model_errors.get(model_name, [])
        if not errors:
            return {"mape": None, "count": 0}

        mape = sum(abs(e) for e in errors) / len(errors)  # Mean Absolute % Error
        bias = sum(errors) / len(errors)                    # Systematic over/under

        return {
            "mape": mape,
            "bias": bias,           # Positive = underpredicting, Negative = overpredicting
            "count": len(errors),
            "recent_mape": sum(abs(e) for e in errors[-10:]) / min(len(errors), 10)
        }

    def get_bias_correction(self, model_name: str) -> float:
        """
        Calculate systematic bias to correct future predictions.
        If Msaidizi consistently under-predicts by 10%, adjust +10%.
        """
        quality = self.get_model_quality(model_name)
        if quality["count"] < 5:
            return 0.0
        return -quality["bias"]  # Negate because we want to correct, not amplify
```

### Confidence Interval Calibration

```python
class ConfidenceCalibrator:
    """
    Calibrate confidence intervals so that 80% prediction intervals
    actually contain the actual value ~80% of the time.
    """
    def __init__(self):
        self.coverage = {}  # interval_level → {total, contained}

    def check_calibration(self, prediction: Prediction, actual: float):
        """Check if actual value falls within predicted intervals."""
        for level in [0.5, 0.8, 0.9, 0.95]:
            lower, upper = prediction.confidence_intervals[level]
            contained = lower <= actual <= upper
            self.coverage.setdefault(level, {"total": 0, "contained": 0})
            self.coverage[level]["total"] += 1
            if contained:
                self.coverage[level]["contained"] += 1

    def get_calibration_factor(self, level: float) -> float:
        """
        Returns a multiplier to widen or narrow intervals.
        If 80% intervals only contain 60% of actuals, multiply by > 1.
        """
        stats = self.coverage.get(level, {"total": 0, "contained": 0})
        if stats["total"] < 10:
            return 1.0  # Not enough data

        actual_coverage = stats["contained"] / stats["total"]
        target_coverage = level

        # Correction factor: if actual < target, widen intervals
        if actual_coverage < target_coverage * 0.8:
            return 1.3  # Widen significantly
        elif actual_coverage < target_coverage * 0.95:
            return 1.1  # Widen slightly
        elif actual_coverage > target_coverage * 1.2:
            return 0.85  # Narrow (intervals too wide)
        else:
            return 1.0  # Well-calibrated
```

### Learning Loop Summary

```
Day 1:  Base forecast (moving average). Wide confidence intervals. Low accuracy.
         ↓
Day 7:  Personal trend model activates. Day-of-week patterns emerge.
         ↓
Day 14: Prediction error tracking begins. Bias correction starts.
         ↓
Day 30: Pattern-adjusted model activates. Month-end/weekly patterns boost accuracy.
         ↓
Day 60+: Confidence calibration mature. MAPE typically < 15%.
          Bias correction eliminates systematic errors.
          Worker sees: "By Friday you'll have ~18,000 KES (±2,000)"
```

---

## Cross-Cutting Concerns

### Storage Budget

All on-device learning data must fit within a reasonable storage budget:

| Component | Size per Worker | Notes |
|---|---|---|
| Personal dictionary | ~50 KB | SQLite, ~500 words |
| Co-occurrence matrix | ~20 KB | Sparse, only observed pairs |
| Dialect profile | ~5 KB | Language mix, common phrases |
| Pattern store | ~30 KB | JSON, ~50 patterns max |
| Advice scores | ~10 KB | Per advice type |
| Prediction history | ~15 KB | 90 days of daily predictions |
| Calibration data | ~5 KB | Coverage statistics |
| **Total** | **~135 KB** | Per worker, well within mobile constraints |

### Privacy and Data Ownership

- All learning data is stored on-device in encrypted SQLite
- No data leaves the device without explicit worker consent
- Worker can view and delete any learned data:
  - "Show me what you've learned about my business"
  - "Forget everything about restocking patterns"
  - "Reset your vocabulary"
- Data is exported only for backup (encrypted, worker-controlled)

### Cold Start Strategy

```
New Worker (Day 0):
├── ASR: Generic multilingual model (Swahili + English)
├── Vocabulary: Base vocabulary only
├── Patterns: None detected
├── Advice: Community-level advice (what works for similar workers)
├── Predictions: Simple moving average
└── Dialect: Default (60% Swahili, 40% English)

After 1 Week:
├── ASR: Personal vocabulary building
├── Vocabulary: 10-20 new words learned
├── Patterns: First weekly pattern suspected
├── Advice: Starting to track outcomes
├── Predictions: Personal trend model activates
└── Dialect: Language mix stabilizing

After 1 Month:
├── ASR: Custom LM weights active
├── Vocabulary: 50-100 words, many confirmed
├── Patterns: 3-5 confirmed patterns
├── Advice: Quality scores meaningful, suppresses bad advice
├── Predictions: Pattern-adjusted, MAPE ~25%
└── Dialect: Full profile, code-switch patterns mapped

After 3 Months:
├── ASR: Fully personalized, error rate reduced ~30%
├── Vocabulary: Comprehensive, auto-expanding
├── Patterns: Rich pattern library, seasonal awareness
├── Advice: Highly tuned, mostly followed
├── Predictions: MAPE < 15%, well-calibrated intervals
└── Dialect: Natural interaction, feels like "my assistant"
```

### Worker-Facing Transparency

Msaidizi periodically shares what it has learned:

> 📱 **Weekly Learning Report**
>
> This week I learned:
> - 3 new words: nduru, kiberiti, mpasho
> - Your busiest day is Saturday (sales are 2x weekday average)
> - You tend to restock sukari on Wednesdays
>
> I gave you 4 suggestions. You followed 2, and those helped you earn 800 KES more.
>
> Want to correct anything? Say "show me what you learned."

---

## Summary: The Five Learning Loops

| Loop | What It Learns | Signal Source | Cold Start | Mature State |
|---|---|---|---|---|
| **Vocabulary** | Product names, slang, dialect words | ASR confidence, worker corrections | Base dictionary | Personal dictionary, 90%+ recognition |
| **Business Patterns** | Restocking schedules, peak hours, seasonal trends | Transaction history | No patterns | Rich pattern library, actionable advice |
| **Dialect** | Language mix, code-switch points, sentence patterns | Every utterance | Default language model | Personalized ASR, natural interaction |
| **Advice Quality** | What advice works for this worker | Follow/no-follow + outcomes | Community-level advice | Worker-tuned, high follow-through |
| **Cash Flow Prediction** | Accurate financial forecasts | Predicted vs. actual comparison | Wide-interval moving average | Narrow-interval, pattern-adjusted, <15% MAPE |

Each loop feeds the others:
- **Vocabulary** improves **ASR**, which produces better **transaction data**
- **Transaction data** feeds **pattern detection**, which improves **predictions**
- **Patterns** generate **advice**, which is scored by **advice quality learning**
- **Dialect adaptation** improves **ASR** and **advice delivery** (right language, right words)
- **Prediction accuracy** builds **worker trust**, leading to more data, feeding all loops

This is the flywheel. Every interaction makes the next one better. Msaidizi does not just assist — it **grows**.
