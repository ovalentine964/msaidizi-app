# Growth Feedback Loops: Automatic System Improvement

## Overview

Msaidizi and Angavu Backend must improve continuously through signals workers generate simply by using the system. This document defines every feedback signal, how it's captured, how it's processed, and how it triggers automatic model updates — no manual intervention required.

---

## 1. Implicit Feedback (Zero-Effort Signals)

Workers never need to "rate" anything. Their natural behavior IS the signal.

### 1.1 Transaction Lifecycle Signals

| Worker Action | Signal Type | Signal Strength | What It Tells Us |
|---|---|---|---|
| Records a transaction | Success | +0.3 | Speech-to-text worked, category matched, amount parsed |
| Edits amount after recording | Correction | -0.5 (amount field) | Amount parsing failed — update acoustic model or number normalization |
| Edits category after recording | Correction | -0.5 (category) | Category prediction wrong — retrain classifier on this product/context pair |
| Edits customer name | Correction | -0.4 (NER) | Name extraction failed — update name entity patterns |
| Deletes transaction entirely | Failure | -1.0 | Complete failure — log full context (audio, time, worker) for retraining |
| Re-records same transaction | Failure + Retry | -0.8 | First attempt unusable — likely audio quality or accent issue |
| Records transaction in <3s | Efficiency | +0.5 | System is fast and intuitive |
| Records transaction in >30s | Friction | -0.3 | Worker struggling — check for confusing prompts or slow response |

### 1.2 CFO Advice Signals

| Worker Action | Signal Type | Signal Strength | What It Tells Us |
|---|---|---|---|
| Follows CFO advice within 24h | Positive Reinforcement | +0.8 | Advice was relevant, actionable, and trusted |
| Follows CFO advice within 1h | Strong Positive | +1.0 | Advice was immediately compelling |
| Ignores advice for 7+ days | Negative Signal | -0.5 | Advice not relevant, not understood, or not trusted |
| Explicitly dismisses advice | Strong Negative | -1.0 | Advice was wrong or inappropriate |
| Asks follow-up question about advice | Engagement | +0.6 | Advice sparked interest but was unclear — improve explanation |
| Follows advice, then reverses | Weak Negative | -0.7 | Advice led to bad outcome — critical learning signal |

### 1.3 Session Behavior Signals

| Behavior | Signal | Interpretation |
|---|---|---|
| Returns daily for 7+ days | Strong positive | System is essential to workflow |
| Uses system at consistent times | Habitual | Embedded in daily routine |
| Session <30s, single transaction | Quick task | Efficient — good UX |
| Session >5min, no transaction recorded | Frustration | Worker lost — improve guidance |
| Opens app but immediately closes | Rejection | Something turned them off — check recent changes |
| Uses feature within 24h of it being introduced | Adoption | Feature discoverable and useful |
| Never uses feature after 30 days | Rejection | Feature invisible or useless — investigate |

### 1.4 Implicit Feedback Pipeline

```
Worker Action
    │
    ▼
┌─────────────────────────┐
│  Event Collector (Kafka) │  ← Raw events with timestamps
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│  Signal Aggregator       │  ← Batches events per worker per day
│  (Flink / Spark Stream)  │
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│  Feedback Scorer         │  ← Applies signal weights above
│  (Python service)        │     Computes rolling scores per model component
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│  Model Update Queue      │  ← Scored signals trigger update jobs
│  (Redis / SQS)           │
└─────────────────────────┘
```

---

## 2. Explicit Feedback (When Workers Choose to Tell Us)

### 2.1 Voice Corrections

Workers can correct Msaidizi by voice at any time:

| Swahili Phrase | English | System Action |
|---|---|---|
| "Msaidizi, hii si sahihi" | "This is not correct" | Enter correction mode — ask "Nini mbaya?" (What's wrong?) |
| "Sema viazi, sio nyanya" | "Say potatoes, not tomatoes" | Update speech model: tomato→potato for this worker's accent |
| "Kiasi ni 500, si 50" | "The amount is 500, not 50" | Update amount parser: retrain on similar audio patterns |
| "Mnunuzi ni Mama Njeri" | "The buyer is Mama Njeri" | Update NER: add to worker's customer dictionary |
| "Hii ni mauzo, si manunuzi" | "This is sales, not purchases" | Update classifier: flip transaction type for this context |
| "Sawa" / "Sahihi" | "Correct" / "Right" | Confirmation signal — reinforce current parsing |
| "Rudia" / "Tena" | "Repeat" / "Again" | First attempt was unclear — log for audio quality |

### 2.2 Post-Interaction Rating

After key interactions, Msaidizi can ask for implicit or explicit ratings:

```
Msaidizi: "Umefanikiwa kununua maharagwe kwa KSh 3,400. Sawa?" (You bought beans for KSh 3,400. Correct?)

Worker: "Sawa" (Correct)         → +1.0 confirmation
Worker: "Hapana" (No)            → Trigger correction flow
Worker: *silence / moves on*     → +0.3 (implicit acceptance, weaker signal)
```

**Design rule:** Never ask for ratings explicitly ("rate 1-5"). Instead, confirm the parsed understanding and let the worker correct or accept. This is natural conversation, not a survey.

### 2.3 Correction Capture Pipeline

```
Voice Correction Detected
    │
    ▼
┌──────────────────────────┐
│  Correction Classifier    │  ← What went wrong? (amount, category, name, type)
└────────────┬─────────────┘
             │
             ▼
┌──────────────────────────┐
│  Context Logger           │  ← Log: original audio, parsed result, 
│                           │     corrected result, worker ID, timestamp
└────────────┬─────────────┘
             │
             ▼
┌──────────────────────────┐
│  Training Data Store      │  ← (original_audio, correct_label) pairs
│  (S3 / MinIO)             │     Used for next model retraining cycle
└────────────┬─────────────┘
             │
             ▼
┌──────────────────────────┐
│  Dictionary Updater       │  ← For name/product corrections: 
│  (Redis)                  │     immediately update worker's custom dictionary
└──────────────────────────┘
```

---

## 3. Behavioral Signals (Pattern-Level Intelligence)

### 3.1 Cohort Health Metrics

Tracked daily per worker cohort (by region, language, business type):

| Metric | Healthy Range | Danger Zone | Automatic Action |
|---|---|---|---|
| Daily Active Users (DAU) | >60% of registered | <30% | Trigger outreach / check for outages |
| Transactions per session | 3-15 | <1 or >30 | Investigate UX flow |
| Session duration | 2-10 min | >20 min | System too slow or confusing |
| Feature adoption (new) | >20% in 7 days | <5% in 14 days | Improve onboarding or kill feature |
| Error correction rate | <10% | >30% | Model quality critical — fast-track retrain |
| Advice follow rate | >40% | <10% | Advice engine needs recalibration |
| Referral rate | >5% monthly | 0% | System not valuable enough to share |
| Retention (D7) | >50% | <20% | Fundamental product issue |
| Retention (D30) | >30% | <10% | Critical — workers don't see lasting value |

### 3.2 Referral Tracking

Referrals are the strongest endorsement signal:

```
Worker A refers Worker B
    │
    ▼
Track:
  - Did B register?
  - Did B record first transaction within 24h?
  - Did B retain at D7, D30?
  - What feature did A mention? (if captured in voice note)
  - A's own engagement trajectory post-referral
    │
    ▼
Signal:
  - High-referral workers → understand WHY (their workflow patterns become templates)
  - Workers who refer but then churn → something broke their trust
  - Referral clusters by market → identify viral geographies
```

### 3.3 Behavioral Signal Aggregation

```python
class WorkerBehaviorScore:
    def __init__(self, worker_id):
        self.worker_id = worker_id
        self.daily_scores = {}  # date -> score components
        
    def compute_health_score(self, window_days=7):
        """Rolling health score per worker (0-100)"""
        signals = self.get_signals(window_days)
        
        score = 0
        score += min(signals.active_days / window_days, 1.0) * 25      # Consistency
        score += min(signals.transactions_per_day / 5, 1.0) * 20       # Volume
        score += max(1 - signals.correction_rate, 0) * 20              # Accuracy
        score += min(signals.advice_follow_rate, 1.0) * 15             # Trust
        score += min(signals.session_efficiency, 1.0) * 10             # Speed
        score += signals.referral_count * 5                             # Advocacy
        
        return min(score, 100)
    
    def compute_model_feedback(self, window_days=30):
        """Aggregate signals that should trigger model updates"""
        return {
            'correction_types': self.count_corrections_by_type(window_days),
            'failed_parses': self.get_failed_transactions(window_days),
            'ignored_advice': self.get_ignored_advice(window_days),
            'custom_dictionary_adds': self.get_dictionary_updates(window_days),
            'audio_quality_issues': self.get_audio_failures(window_days),
        }
```

---

## 4. Automatic Model Updates

### 4.1 Update Trigger Rules

| Signal Threshold | Model Component | Update Type | Frequency |
|---|---|---|---|
| >50 corrections to same word/phrase | Custom Dictionary | Immediate hot-fix | Real-time |
| >100 amount errors in same pattern | Number Parser | Retrain | Daily |
| >200 category misclassifications | Category Classifier | Retrain with new examples | Weekly |
| >50 advice rejections for same advice type | Advice Engine | Adjust relevance weights | Daily |
| >30 NER corrections for same entity | NER Model | Add to entity dictionary | Real-time |
| Audio WER increase >5% | ASR Model | Investigate, potentially retrain | Weekly |
| Cohort retention drop >10% | All models | Emergency audit | Immediate alert |

### 4.2 Update Pipeline

```
┌─────────────────────────────────────────────────────────┐
│                   UPDATE ORCHESTRATOR                     │
│                                                          │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐            │
│  │ Real-time │   │  Daily   │   │  Weekly  │            │
│  │  Updates  │   │ Updates  │   │ Updates  │            │
│  └────┬─────┘   └────┬─────┘   └────┬─────┘            │
│       │              │              │                    │
│       ▼              ▼              ▼                    │
│  Dictionary     Parser tuning   Full model              │
│  hot-fixes      Weight adjust   retraining              │
│  Entity adds    Scoring calib   Architecture search     │
└─────────────────────────────────────────────────────────┘
        │              │              │
        ▼              ▼              ▼
┌──────────────────────────────────────────────────────────┐
│                  SHADOW VALIDATION                        │
│                                                          │
│  Run updated model in parallel with current model        │
│  on 5% of traffic for 24-72 hours                       │
│                                                          │
│  Compare: accuracy, latency, user satisfaction signals   │
│                                                          │
│  If better → promote to primary                          │
│  If worse → discard, flag for investigation              │
│  If same → keep current (avoid unnecessary churn)        │
└──────────────────────────────────────────────────────────┘
```

### 4.3 Real-Time Dictionary Updates

The fastest feedback loop — corrections update dictionaries immediately:

```python
class CustomDictionaryManager:
    def __init__(self, redis_client):
        self.redis = redis_client
    
    def process_correction(self, worker_id, original_text, corrected_text, correction_type):
        """Immediately update worker's custom dictionary on correction"""
        
        if correction_type == 'product_name':
            # Worker said "maharagwe" but system heard "masharabu"
            self.redis.hset(
                f"dict:product:{worker_id}",
                original_text.lower(),  # misheard version
                corrected_text.lower()  # correct version
            )
            # Also add to global corpus if >10 workers make same correction
            global_count = self.redis.incr(f"dict:global:product:{original_text}->{corrected_text}")
            if global_count >= 10:
                self.promote_to_global('product', original_text, corrected_text)
                
        elif correction_type == 'customer_name':
            self.redis.sadd(f"dict:customers:{worker_id}", corrected_text)
            # Customer names are worker-specific, never global
            
        elif correction_type == 'amount':
            # Log pattern for parser retraining (not immediate fix)
            self.redis.lpush(
                f"training:amount_errors:{worker_id}",
                json.dumps({'heard': original_text, 'correct': corrected_text, 'ts': time.time()})
            )
    
    def get_corrections_for_model(self, worker_id):
        """Called before parsing — apply known corrections"""
        products = self.redis.hgetall(f"dict:product:{worker_id}")
        customers = self.redis.smembers(f"dict:customers:{worker_id}")
        return {'product_map': products, 'customer_set': customers}
```

### 4.4 Advice Engine Recalibration

```python
class AdviceEngine:
    def recalibrate(self):
        """Run daily — adjust advice relevance based on follow/ignore signals"""
        
        advice_types = ['cash_flow', 'inventory', 'pricing', 'savings', 'credit']
        
        for advice_type in advice_types:
            stats = self.get_advice_stats(advice_type, window_days=30)
            
            # Compute follow-through rate
            follow_rate = stats.followed / stats.total_given if stats.total_given > 0 else 0
            
            # Adjust relevance weight
            if follow_rate < 0.1:
                # Workers ignore this — lower priority, investigate why
                self.adjust_weight(advice_type, delta=-0.2)
                self.flag_for_review(advice_type, 'very_low_follow_rate', follow_rate)
            elif follow_rate < 0.3:
                # Some engagement but low — refine targeting
                self.adjust_weight(advice_type, delta=-0.1)
            elif follow_rate > 0.6:
                # High engagement — increase priority, find similar workers
                self.adjust_weight(advice_type, delta=+0.1)
            
            # Segment analysis: which workers follow which advice?
            followers = self.get_followers(advice_type)
            ignorers = self.get_ignorers(advice_type)
            
            # Build targeting profile
            self.update_targeting_model(advice_type, 
                positive_examples=followers,
                negative_examples=ignorers
            )
```

### 4.5 Update Frequency Summary

| Component | Trigger | Max Latency | Rollback Time |
|---|---|---|---|
| Custom dictionary | Every correction | <1 minute | Instant (Redis revert) |
| Customer name list | Every correction | <1 minute | Instant |
| Amount parser weights | Daily batch | 24 hours | <1 hour (model swap) |
| Category classifier | Weekly retrain | 7 days | <1 hour |
| Advice relevance scores | Daily recalc | 24 hours | Instant (config revert) |
| ASR acoustic model | Monthly or on WER spike | 30 days | <1 hour |
| Full pipeline retrain | Quarterly or on major issues | 90 days | <1 hour |

---

## 5. A/B Testing Framework

### 5.1 Cohort Assignment

```python
class CohortManager:
    def __init__(self, redis_client):
        self.redis = redis_client
        self.COHORTS = {
            'control': 0.70,      # Current production model
            'treatment_a': 0.10,  # New model variant A
            'treatment_b': 0.10,  # New model variant B  
            'holdout': 0.10,      # No changes — baseline measurement
        }
    
    def assign_cohort(self, worker_id):
        """Deterministic assignment based on worker ID hash"""
        hash_val = int(hashlib.md5(worker_id.encode()).hexdigest(), 16) % 100
        
        cumulative = 0
        for cohort, fraction in self.COHORTS.items():
            cumulative += fraction * 100
            if hash_val < cumulative:
                return cohort
        return 'control'
    
    def get_model_version(self, worker_id):
        """Which model serves this worker?"""
        cohort = self.assign_cohort(worker_id)
        return self.redis.hget(f'cohort_models:{cohort}', 'active_version')
```

### 5.2 Experiment Lifecycle

```
EXPERIMENT STAGES:

1. HYPOTHESIS (1 day)
   └─ Define: What are we testing? What metric improves?
   └─ Example: "Adding local market names to dictionary reduces 
      product misclassification by 15%"

2. SHADOW MODE (3-7 days)
   └─ New model runs alongside current, results logged but not shown to workers
   └─ Compare: accuracy, latency, error types
   └─ Gate: New model must be ≥ current on all metrics

3. CANARY (7 days)
   └─ 5% of treatment cohort sees new model
   └─ Monitor: correction rate, session abandonment, transaction volume
   └─ Kill switch: auto-rollback if any metric degrades >5%

4. EXPANSION (14 days)
   └─ 10% of treatment cohort (if canary passed)
   └─ Full metric suite: accuracy, engagement, retention, referrals
   └─ Statistical significance test (p < 0.05)

5. ROLLOUT (7 days)
   └─ Gradual: 25% → 50% → 100% of all workers
   └─ Monitor at each step
   └─ Keep previous model hot for instant rollback

6. CLEANUP (1 day)
   └─ Archive experiment data
   └─ Document learnings
   └─ Remove old model if no rollback needed
```

### 5.3 Metrics Dashboard

Primary metrics tracked per experiment:

| Metric | How Measured | Minimum Detectable Effect | Sample Size Needed |
|---|---|---|---|
| Transaction accuracy | Corrections / total transactions | 5% relative | ~2000 transactions per cohort |
| Speech recognition WER | Corrections to ASR output | 3% absolute | ~5000 utterances per cohort |
| Session completion rate | Sessions with ≥1 transaction / total sessions | 5% relative | ~500 sessions per cohort |
| Advice follow rate | Advice followed / advice given | 10% relative | ~300 advice events per cohort |
| D7 retention | Workers active on day 7 / registered | 5% absolute | ~200 workers per cohort |
| NPS proxy | Referral rate + session frequency | 3% absolute | ~300 workers per cohort |

### 5.4 Experiment Guardrails

```python
class ExperimentGuardrails:
    def __init__(self, experiment_id):
        self.experiment_id = experiment_id
        self.KILL_THRESHOLDS = {
            'correction_rate_increase': 0.05,    # >5% more corrections → kill
            'session_abandonment_increase': 0.10, # >10% more abandoned sessions → kill
            'transaction_volume_decrease': 0.15,  # >15% fewer transactions → kill
            'response_latency_p99': 5000,         # >5s p99 latency → kill
            'error_rate': 0.01,                   # >1% errors → kill
        }
    
    def check_health(self, experiment_id):
        """Run every hour during experiment"""
        treatment = self.get_metrics('treatment')
        control = self.get_metrics('control')
        
        violations = []
        for metric, threshold in self.KILL_THRESHOLDS.items():
            delta = self.compute_delta(treatment[metric], control[metric])
            if self.is_degradation(metric, delta, threshold):
                violations.append({
                    'metric': metric,
                    'treatment': treatment[metric],
                    'control': control[metric],
                    'delta': delta,
                    'threshold': threshold
                })
        
        if violations:
            self.trigger_rollback(experiment_id, violations)
            self.alert_team(experiment_id, violations)
            return False
        
        return True
    
    def trigger_rollback(self, experiment_id, violations):
        """Instant rollback — switch all treatment workers to control model"""
        self.redis.hset(f'experiment:{experiment_id}', 'status', 'ROLLED_BACK')
        self.update_cohort_models('treatment', self.get_control_model())
        logger.critical(f"Experiment {experiment_id} rolled back. Violations: {violations}")
```

---

## 6. System Architecture — Full Feedback Loop

```
┌─────────────────────────────────────────────────────────────────────┐
│                        WORKER INTERACTION LAYER                      │
│                                                                      │
│  Msaidizi App  ←→  Voice I/O  ←→  NLU Pipeline  ←→  Transaction DB │
└──────────┬──────────────────────────────┬───────────────────────────┘
           │                              │
     Implicit Signals              Explicit Signals
     (usage patterns)              (corrections, ratings)
           │                              │
           ▼                              ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     SIGNAL PROCESSING LAYER                           │
│                                                                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐               │
│  │   Event      │  │  Correction  │  │  Behavioral  │               │
│  │   Stream     │  │  Classifier  │  │  Aggregator  │               │
│  │   (Kafka)    │  │  (Python)    │  │  (Flink)     │               │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘               │
│         │                 │                  │                        │
│         └────────────────┬┴──────────────────┘                        │
│                          ▼                                            │
│                 ┌──────────────┐                                      │
│                 │   Feedback   │                                      │
│                 │   Scorer     │                                      │
│                 └──────┬───────┘                                      │
└────────────────────────┼─────────────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     MODEL UPDATE LAYER                                │
│                                                                       │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐    │
│  │ Real-time  │  │   Daily    │  │   Weekly   │  │  Quarterly │    │
│  │ Dictionaries│  │ Parser/    │  │ Classifier │  │ Full       │    │
│  │ Entity adds│  │ Scoring    │  │ Retrain    │  │ Retrain    │    │
│  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘    │
│        │               │               │               │            │
│        └───────────────┴───────┬───────┴───────────────┘            │
│                                ▼                                     │
│                       ┌──────────────┐                               │
│                       │   Shadow     │                               │
│                       │  Validation  │                               │
│                       └──────┬───────┘                               │
└──────────────────────────────┼───────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     A/B TESTING LAYER                                 │
│                                                                       │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐            │
│  │ Control  │  │Treatment │  │Treatment │  │ Holdout  │            │
│  │  (70%)   │  │   A(10%) │  │   B(10%) │  │  (10%)   │            │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘            │
│                                                                       │
│  Guardrails → Auto-rollback if degradation detected                  │
│  Metrics → Statistical significance before rollout                   │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 7. Feedback Loop Timeline — Day in the Life

| Time | What Happens | Loop |
|---|---|---|
| 06:00 | Worker records "Nimepata nyanya 10 kwa 500" | Audio → ASR → Parse → Confirm |
| 06:01 | Msaidizi: "Nyanya 10, KSh 500, sawa?" | |
| 06:01 | Worker: "Sawa" | ✅ Implicit confirmation → reinforce model |
| 06:05 | Worker records "Nimepata viazi 20 kwa 1000" | |
| 06:05 | Msaidizi: "Nyanya 20, KSh 1000, sawa?" | ❌ ASR misheard "viazi" as "nyanya" |
| 06:05 | Worker: "Sio nyanya, viazi!" | 🔴 Explicit correction → dictionary update |
| 06:05 | Msaidizi: "Pole! Viazi 20, KSh 1000. Sawa?" | Correction applied, confirmed |
| 06:06 | Dictionary updated: viazi→viazi (worker accent pattern logged) | 🔄 Real-time update |
| 12:00 | CFO advice: "Uko na stock ndogo ya viazi. Nunua zaidi leo." | |
| 14:00 | Worker buys more viazi at market | ✅ Advice followed (2h latency) → positive signal |
| 18:00 | Daily batch: aggregate signals, update parser weights | 🔄 Daily update |
| 02:00 | Weekly retrain job: incorporate correction pairs | 🔄 Weekly update |
| Mon  | Shadow validation of new model on 5% traffic | 🔬 A/B test |
| +7d  | Statistical test: new model 12% fewer corrections → rollout | ✅ Rollout |

---

## 8. Key Design Principles

1. **Every interaction is training data.** Workers don't fill out surveys — they generate feedback by living their lives.

2. **Speed of adaptation varies by risk.** Dictionaries update instantly because the blast radius is one worker. Model retrains are weekly because errors affect everyone.

3. **Workers are never punished for corrections.** Correcting Msaidizi is a gift — it makes the system better for everyone. Make it easy, fast, and acknowledged.

4. **The holdout group is sacred.** Always maintain a 10% holdout that gets NO model updates. Without it, we can't measure whether changes actually help.

5. **Rollback is always faster than rollout.** Every model update ships with an instant-rollback mechanism. If we can't roll back in <1 hour, we don't ship it.

6. **Negative signals are more valuable than positive ones.** A correction tells us exactly what went wrong. A "sawa" just tells us nothing went wrong *this time*. Prioritize learning from failures.

7. **Privacy in aggregation.** Individual worker signals are private. Model updates use aggregated, anonymized patterns. No worker's specific data leaks into another worker's experience without explicit opt-in.
