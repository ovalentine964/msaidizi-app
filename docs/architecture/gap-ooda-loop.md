# OODA Loop — Core Reasoning Pattern for Msaidizi Superagent

**Version:** 1.0.0
**Date:** 2026-07-24
**Author:** Architecture Agent
**Status:** Design Specification — Ready for Implementation
**Replaces:** Orchestrator → IntentRouter → Agent → Handler chain

---

## Table of Contents

1. [Why OODA](#1-why-ooda)
2. [The Loop at a Glance](#2-the-loop-at-a-glance)
3. [Phase 1: OBSERVE](#3-phase-1-observe)
4. [Phase 2: ORIENT](#4-phase-2-orient)
5. [Phase 3: DECIDE](#5-phase-3-decide)
6. [Phase 4: ACT](#6-phase-4-act)
7. [The Feedback Loop](#7-the-feedback-loop)
8. [Data Structures](#8-data-structures)
9. [Implementation](#9-implementation)
10. [Performance Budget](#10-performance-budget)
11. [Offline Behavior](#11-offline-behavior)
12. [Error Handling & Fallback](#12-error-handling--fallback)
13. [Integration Points](#13-integration-points)
14. [Testing Strategy](#14-testing-strategy)

---

## 1. Why OODA

### 1.1 The Problem with the Current Chain

The current flow is a linear pipeline:

```
Voice → Orchestrator → IntentRouter → Agent → Handler → Response
```

Each step passes a baton. If any step fails, the whole chain breaks. The Orchestrator has 40+ constructor parameters. The IntentRouter doesn't know what happened yesterday. The Handler doesn't know if the worker is in distress. Nobody learns.

### 1.2 What OODA Gives Us

John Boyd's OODA Loop (Observe → Orient → Decide → Act) is the reasoning pattern of fighter pilots who need to make life-or-death decisions faster than the enemy. The key insight: **speed comes from better orientation, not faster action.**

For Msaidizi, this means:
- **Observe** everything — voice, context, market state, anomalies — before reasoning
- **Orient** with deep context — what does this mean for *this specific worker*?
- **Decide** with confidence thresholds — don't act unless sure
- **Act** with measurable outcomes — every action produces a learning signal

### 1.3 OODA vs Multi-Agent

| Aspect | Multi-Agent (Old) | OODA Superagent (New) |
|--------|-------------------|----------------------|
| Reasoning | Distributed across agents | Single loop, one brain |
| Context | Fragmented per agent | Shared, unified |
| Learning | Per-agent (isolated) | Cross-domain (one flywheel) |
| Latency | N calls per request | 1 pass + optional LLM fallback |
| Debugging | Trace across agents | One loop trace |
| Failure mode | Cascade across agents | Graceful degradation per phase |

---

## 2. The Loop at a Glance

```
┌─────────────────────────────────────────────────────────────────┐
│                      THE OODA LOOP                              │
│                                                                 │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐  │
│  │ OBSERVE  │───▶│  ORIENT  │───▶│  DECIDE  │───▶│   ACT    │  │
│  │          │    │          │    │          │    │          │  │
│  │ Collect  │    │ Context  │    │ Select   │    │ Execute  │  │
│  │ all      │    │ engine   │    │ action   │    │ response │  │
│  │ signals  │    │ lookup   │    │ + route  │    │ + learn  │  │
│  └──────────┘    └──────────┘    └──────────┘    └────┬─────┘  │
│       ▲                                               │        │
│       │              FEEDBACK LOOP                     │        │
│       └───────────────────────────────────────────────┘        │
│                                                                 │
│  Each cycle: ~50-200ms (code path) or ~500-2000ms (LLM path)  │
│  The loop is the ENTIRE reasoning engine — no external routing │
└─────────────────────────────────────────────────────────────────┘
```

**Critical property:** The loop is not a pipeline. Each phase can short-circuit back to OBSERVE if new information arrives, or skip ahead if the situation is trivially resolvable. This is what makes it a *loop*, not a chain.

---

## 3. Phase 1: OBSERVE

### 3.1 Purpose

Collect all available signals into a single, structured observation. No reasoning happens here — just data gathering.

### 3.2 Signal Sources

```
┌─────────────────────────────────────────────────────────────┐
│                    OBSERVE PHASE                             │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  VOICE SIGNAL                                       │    │
│  │  ├── STT result: "Nimeuziwa mandazi kumi, mia mbili"│    │
│  │  ├── Language: Swahili                               │    │
│  │  ├── Dialect: Sheng-influenced                       │    │
│  │  ├── ASR confidence: 0.95                            │    │
│  │  ├── Voice emotion: neutral                          │    │
│  │  └── Audio features: pitch, pace, volume             │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  MARKET SIGNAL                                      │    │
│  │  ├── Current prices for known products               │    │
│  │  ├── Demand trends (from backend Soko Pulse)         │    │
│  │  ├── Anomaly flags (price spike? shortage?)          │    │
│  │  └── Time context: market day? holiday? weekend?     │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  WORKER SIGNAL                                      │    │
│  │  ├── Location: GPS coordinates + named location      │    │
│  │  ├── Time of day: morning / afternoon / evening      │    │
│  │  ├── Day of week + market calendar                   │    │
│  │  ├── Recent transactions (last 7 days)               │    │
│  │  ├── Active goals and their progress                 │    │
│  │  ├── Pending alerts or reminders                     │    │
│  │  ├── Current streak status                           │    │
│  │  └── Alama Score tier + capabilities                 │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  PROACTIVE SIGNAL                                   │    │
│  │  ├── Scheduled alerts (bills due, stock low)         │    │
│  │  ├── Anomaly detected (unusual spending pattern)     │    │
│  │  ├── Opportunity spotted (price drop on staple)      │    │
│  │  └── External event (weather alert, market closure)  │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

### 3.3 Signal Collection Architecture

Each signal source is a **SignalProvider** — a lightweight interface that returns data asynchronously:

```kotlin
// superagent/engine/OodaLoop.kt

/**
 * A source of observational data. Implementations should be fast (< 50ms)
 * and never block the loop. If data isn't available, return a default/empty value.
 */
interface SignalProvider<T> {
    suspend fun observe(context: ObservationContext): T
    val priority: SignalPriority  // CRITICAL, HIGH, MEDIUM, LOW
    val maxLatencyMs: Long        // Timeout for this signal
}

enum class SignalPriority { CRITICAL, HIGH, MEDIUM, LOW }

/**
 * The raw observation bundle. Contains all signals for one OODA cycle.
 */
data class Observation(
    // Voice signal (always present for voice input)
    val voice: VoiceSignal?,

    // Text input (always present — either from STT or direct text)
    val text: String,
    val language: String,
    val dialect: String,

    // Market context
    val market: MarketSignal,

    // Worker context
    val worker: WorkerSignal,

    // Proactive triggers (non-null only if this cycle was triggered proactively)
    val proactive: ProactiveSignal?,

    // Metadata
    val timestamp: Long = System.currentTimeMillis(),
    val triggerType: TriggerType  // VOICE_INPUT, TEXT_INPUT, PROACTIVE, FOLLOW_UP
)

data class VoiceSignal(
    val sttResult: String,
    val asrConfidence: Float,
    val language: String,
    val dialect: String,
    val emotion: VoiceEmotion?,
    val pitch: Float?,
    val pace: Float?,
    val volume: Float?
)

data class MarketSignal(
    val relevantPrices: Map<String, Double>,     // product → current price
    val priceAnomalies: List<PriceAnomaly>,      // unusual prices detected
    val demandTrend: DemandTrend?,               // UP, DOWN, STABLE
    val isMarketDay: Boolean,
    val isHoliday: Boolean,
    val isWeekend: Boolean
)

data class WorkerSignal(
    val recentTransactions: List<Transaction>,   // last 7 days
    val dailyAverage: Double,                    // rolling 30-day avg revenue
    val weeklyTrend: TrendDirection,             // UP, DOWN, STABLE
    val activeGoals: List<Goal>,
    val pendingAlerts: List<Alert>,
    val streakDays: Int,
    val alamaTier: AlamaTier,
    val unlockedCapabilities: List<Capability>,
    val lastInteractionTime: Long,
    val interactionCount: Int                    // total interactions
)

data class ProactiveSignal(
    val trigger: ProactiveTrigger,
    val urgency: Urgency,         // LOW, MEDIUM, HIGH, CRITICAL
    val message: String,
    val relatedData: Map<String, Any>
)

enum class TriggerType { VOICE_INPUT, TEXT_INPUT, PROACTIVE, FOLLOW_UP }
enum class ProactiveTrigger {
    BILL_DUE, STOCK_LOW, GOAL_BEHIND, ANOMALY_DETECTED,
    PRICE_OPPORTUNITY, STREAK_RISK, MARKET_ALERT, WEATHER_ALERT
}
```

### 3.4 Signal Collection Strategy

Not all signals are equal. The collection strategy is **parallel with priority cutoffs**:

```kotlin
// superagent/engine/OodaLoop.kt — observe() method

private suspend fun observe(
    text: String,
    voice: VoiceSignal?,
    triggerType: TriggerType
): Observation = coroutineScope {
    // CRITICAL signals: collect in parallel, wait for all
    val criticalSignals = listOf(
        async { workerSignalProvider.observe(text) },    // worker context
        async { contextSignalProvider.observe(text) }     // conversation context
    )

    // HIGH signals: collect in parallel, wait with timeout
    val highSignals = listOf(
        async { marketSignalProvider.observe(text) },     // market data
        async { proactiveSignalProvider.observe(text) }   // alerts
    )

    // Wait for critical (never skip)
    val workerSignal = criticalSignals[0].await()
    val contextSignal = criticalSignals[1].await()

    // Wait for high with timeout (skip if too slow)
    val marketSignal = withTimeoutOrNull(200) {
        highSignals[0].await()
    } ?: MarketSignal.empty()

    val proactiveSignal = withTimeoutOrNull(100) {
        highSignals[1].await()
    }

    Observation(
        voice = voice,
        text = text,
        language = voice?.language ?: detectLanguage(text),
        dialect = voice?.dialect ?: "standard",
        market = marketSignal,
        worker = workerSignal,
        proactive = proactiveSignal,
        triggerType = triggerType
    )
}
```

### 3.5 Data Completeness Check

Before moving to ORIENT, check if we have enough data to proceed. This is the M-KOPA principle — collect what the backend needs:

```kotlin
// superagent/engine/DataCompletenessChecker.kt

/**
 * Checks if the observation has enough data for the backend's needs.
 * If critical data is missing, short-circuit back with a follow-up question.
 */
fun checkCompleteness(observation: Observation): CompletenessResult {
    val missing = mutableListOf<String>()

    // For transaction-type inputs, we need: WHAT, HOW MUCH
    if (isTransactionInput(observation.text)) {
        val parsed = quickParse(observation.text)
        if (parsed.item == null) missing.add("item")
        if (parsed.amount == null) missing.add("amount")
    }

    return CompletenessResult(
        isComplete = missing.isEmpty(),
        missingFields = missing,
        followUpQuestion = generateFollowUp(missing, observation.language)
    )
}

private fun generateFollowUp(missing: List<String>, language: String): String? {
    if (missing.isEmpty()) return null
    return when {
        "amount" in missing && language == "sw" -> "Bei ngapi?"
        "amount" in missing -> "How much?"
        "item" in missing && language == "sw" -> "Umeuza/nunua nini?"
        "item" in missing -> "What did you sell/buy?"
        else -> null
    }
}
```

---

## 4. Phase 2: ORIENT

### 4.1 Purpose

Transform raw observations into **meaning**. This is the most important phase — it's where Msaidizi understands what's happening for *this specific worker* in *this specific context*. Boyd said the pilot who orients fastest wins. Msaidizi wins by knowing this worker deeply.

### 4.2 Orientation Layers

```
┌─────────────────────────────────────────────────────────────┐
│                    ORIENT PHASE                              │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  LAYER 1: PARSE                                     │    │
│  │  Extract structured data from raw text              │    │
│  │  "Nimeuziwa mandazi kumi, mia mbili" →               │    │
│  │  {intent: SALE, item: "mandazi", qty: 10, price: 200}│    │
│  └─────────────────────────────────────────────────────┘    │
│                         │                                    │
│                         ▼                                    │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  LAYER 2: CONTEXTUALIZE                             │    │
│  │  Enrich with worker history and patterns            │    │
│  │  "This worker sells mandazi every morning at Gikomba│    │
│  │   Average: 15 units/day. Today: 10 — below average." │    │
│  └─────────────────────────────────────────────────────┘    │
│                         │                                    │
│                         ▼                                    │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  LAYER 3: ANALYZE                                   │    │
│  │  Detect anomalies, opportunities, risks             │    │
│  │  "Sales 33% below average. No anomaly in market     │    │
│  │   prices. Worker may be at a different location or   │    │
│  │   it's a slow day."                                  │    │
│  └─────────────────────────────────────────────────────┘    │
│                         │                                    │
│                         ▼                                    │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  LAYER 4: ALIGN                                     │    │
│  │  Map to worker goals and system objectives          │    │
│  │  "Worker's goal: save KSh 50,000 by December.       │    │
│  │   Current savings: KSh 12,000. Daily target: KSh 300.│    │
│  │   Today's profit: KSh 200 (below target)."           │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

### 4.3 Layer 1: Parse

The parse layer extracts structured intent and data from raw text. This is code-first (no LLM), handling 90% of inputs:

```kotlin
// superagent/engine/OodaLoop.kt — orient() internals

/**
 * Parse raw text into structured intent + extracted data.
 * Code-first: pattern matching, regex, dictionary lookup.
 * LLM fallback only when confidence < threshold.
 */
private suspend fun parse(text: String, language: String): ParseResult {
    // Step 1: Normalize (expand Sheng, fix dialect)
    val normalized = dialectNormalizer.normalize(text, language)

    // Step 2: Pattern match (90% hit rate)
    val patternResult = intentClassifier.classify(normalized)
    if (patternResult.confidence >= HIGH_CONFIDENCE_THRESHOLD) {
        return ParseResult(
            intent = patternResult.intent,
            extractedData = patternResult.extractedData,
            confidence = patternResult.confidence,
            method = ParseMethod.PATTERN
        )
    }

    // Step 3: Fuzzy match (handles typos, partial input)
    val fuzzyResult = intentClassifier.fuzzyMatch(normalized)
    if (fuzzyResult.confidence >= MEDIUM_CONFIDENCE_THRESHOLD) {
        return ParseResult(
            intent = fuzzyResult.intent,
            extractedData = fuzzyResult.extractedData,
            confidence = fuzzyResult.confidence,
            method = ParseMethod.FUZZY
        )
    }

    // Step 4: LLM fallback (complex, ambiguous input)
    val llmResult = llmEngine.classify(normalized, language)
    return ParseResult(
        intent = llmResult.intent,
        extractedData = llmResult.extractedData,
        confidence = llmResult.confidence,
        method = ParseMethod.LLM
    )
}
```

### 4.4 Layer 2: Contextualize

Enrich the parsed intent with what we know about this worker:

```kotlin
// superagent/engine/Orientation.kt

/**
 * Enrich parsed data with worker context.
 * This is what makes Msaidizi a superagent — it knows THIS worker.
 */
private fun contextualize(parseResult: ParseResult, observation: Observation): ContextualizedIntent {
    val worker = observation.worker
    val profile = contextEngine.getWorkerProfile()

    // Pattern matching: what happened last time?
    val historicalPattern = contextEngine.findSimilarPastInteractions(
        intent = parseResult.intent,
        item = parseResult.extractedData["item"],
        limit = 10
    )

    // Baseline comparison: how does this compare to normal?
    val baseline = when (parseResult.intent) {
        IntentType.SALE, IntentType.PURCHASE -> {
            val item = parseResult.extractedData["item"] as? String
            val itemBaseline = profile.getItemBaseline(item)
            BaselineComparison(
                average = itemBaseline?.averageAmount ?: 0.0,
                isAboveAverage = (parseResult.extractedData["amount"] as? Double ?: 0.0) >
                    (itemBaseline?.averageAmount ?: 0.0),
                deviation = calculateDeviation(
                    parseResult.extractedData["amount"] as? Double ?: 0.0,
                    itemBaseline?.averageAmount ?: 0.0
                )
            )
        }
        else -> null
    }

    // Goal relevance: does this relate to an active goal?
    val goalRelevance = worker.activeGoals.mapNotNull { goal ->
        val relevance = calculateGoalRelevance(parseResult, goal)
        if (relevance > 0.1) GoalRelevance(goal, relevance) else null
    }

    // Market context: is the price normal?
    val marketContext = observation.market.let { market ->
        val item = parseResult.extractedData["item"] as? String
        val currentPrice = item?.let { market.relevantPrices[it] }
        val recordedPrice = parseResult.extractedData["amount"] as? Double
        if (currentPrice != null && recordedPrice != null) {
            MarketComparison(
                marketPrice = currentPrice,
                recordedPrice = recordedPrice,
                priceDeviation = (recordedPrice - currentPrice) / currentPrice
            )
        } else null
    }

    return ContextualizedIntent(
        parsed = parseResult,
        baseline = baseline,
        historicalPatterns = historicalPattern,
        goalRelevance = goalRelevance,
        marketContext = marketContext,
        workerState = WorkerState(
            streakDays = worker.streakDays,
            alamaTier = worker.alamaTier,
            recentTrend = worker.weeklyTrend,
            daysSinceLastInteraction = daysSince(worker.lastInteractionTime)
        )
    )
}
```

### 4.5 Layer 3: Analyze

Detect anomalies, opportunities, and risks:

```kotlin
// superagent/engine/Orientation.kt — analyze layer

/**
 * Analyze the contextualized intent for anomalies, opportunities, and risks.
 * Returns an analysis that informs the DECIDE phase.
 */
private fun analyze(contextualized: ContextualizedIntent, observation: Observation): Analysis {
    val signals = mutableListOf<Signal>()

    // ── ANOMALY DETECTION ──

    // Low sales day?
    if (contextualized.parsed.intent == IntentType.SALE) {
        val amount = contextualized.parsed.extractedData["amount"] as? Double ?: 0.0
        val baseline = contextualized.baseline
        if (baseline != null && baseline.deviation < -0.3) {
            signals.add(Signal(
                type = SignalType.ANOMALY,
                severity = Severity.MEDIUM,
                message = "Sales ${(-baseline.deviation * 100).toInt()}% below average",
                data = mapOf("deviation" to baseline.deviation)
            ))
        }
    }

    // Price anomaly?
    contextualized.marketContext?.let { market ->
        if (kotlin.math.abs(market.priceDeviation) > 0.2) {
            signals.add(Signal(
                type = if (market.priceDeviation > 0) SignalType.OVERPRICE else SignalType.UNDERPRICE,
                severity = Severity.LOW,
                message = "Price ${if (market.priceDeviation > 0) "above" else "below"} market by ${(kotlin.math.abs(market.priceDeviation) * 100).toInt()}%",
                data = mapOf("deviation" to market.priceDeviation)
            ))
        }
    }

    // ── OPPORTUNITY DETECTION ──

    // Price drop on a product the worker buys?
    observation.market.priceAnomalies.forEach { anomaly ->
        if (anomaly.direction == PriceDirection.DOWN && anomaly.product in profile.frequentPurchases) {
            signals.add(Signal(
                type = SignalType.OPPORTUNITY,
                severity = Severity.LOW,
                message = "${anomaly.product} price dropped ${(anomaly.changePercent * 100).toInt()}%",
                data = mapOf("product" to anomaly.product, "drop" to anomaly.changePercent)
            ))
        }
    }

    // Goal milestone approaching?
    contextualized.goalRelevance.forEach { gr ->
        if (gr.goal.progressPercent > 90) {
            signals.add(Signal(
                type = SignalType.GOAL_NEAR,
                severity = Severity.LOW,
                message = "Goal '${gr.goal.name}' is ${gr.goal.progressPercent.toInt()}% complete!",
                data = mapOf("goalId" to gr.goal.id)
            ))
        }
    }

    // ── RISK DETECTION ──

    // Streak at risk?
    if (contextualized.workerState.daysSinceLastInteraction > 1) {
        signals.add(Signal(
            type = SignalType.STREAK_RISK,
            severity = if (contextualized.workerState.daysSinceLastInteraction > 2) Severity.HIGH else Severity.MEDIUM,
            message = "Streak: ${contextualized.workerState.streakDays} days. Last interaction: ${contextualized.workerState.daysSinceLastInteraction} days ago.",
            data = mapOf("daysSince" to contextualized.workerState.daysSinceLastInteraction)
        ))
    }

    // Stock running low?
    // (detected from purchase patterns — if last purchase was N days ago and typical cycle is M days)

    return Analysis(
        signals = signals,
        hasAnomaly = signals.any { it.type == SignalType.ANOMALY },
        hasOpportunity = signals.any { it.type == SignalType.OPPORTUNITY },
        hasRisk = signals.any { it.type in listOf(SignalType.STREAK_RISK, SignalType.STOCK_LOW) },
        overallSentiment = calculateSentiment(signals)
    )
}
```

### 4.6 Layer 4: Align

Map the situation to the worker's goals and Msaidizi's objectives:

```kotlin
// superagent/engine/Orientation.kt — align layer

/**
 * Align the current situation with worker goals and system objectives.
 * This produces the Orientation — the full understanding of "what's happening
 * and what it means."
 */
private fun align(
    contextualized: ContextualizedIntent,
    analysis: Analysis,
    observation: Observation
): Orientation {
    val worker = observation.worker

    // Goal impact: how does today's activity affect each goal?
    val goalImpacts = worker.activeGoals.map { goal ->
        GoalImpact(
            goal = goal,
            impact = calculateGoalImpact(contextualized, goal),
            onTrack = isGoalOnTrack(goal, contextualized, worker),
            daysRemaining = goal.deadline?.let { daysUntil(it) }
        )
    }

    // Proof impact: does this interaction generate proof?
    val proofImpact = assessProofImpact(contextualized)

    // System objectives alignment
    val systemAlignment = SystemAlignment(
        dataCompleteness = assessDataCompleteness(contextualized.parsed.extractedData),
        flywheelSignal = assessFlywheelValue(contextualized, analysis),
        tierProgress = assessTierProgress(proofImpact, worker.alamaTier)
    )

    // Urgency: how time-sensitive is this?
    val urgency = calculateUrgency(analysis, observation.proactive, contextualized)

    return Orientation(
        intent = contextualized.parsed.intent,
        extractedData = contextualized.parsed.extractedData,
        confidence = contextualized.parsed.confidence,
        parseMethod = contextualized.parsed.method,
        analysis = analysis,
        goalImpacts = goalImpacts,
        proofImpact = proofImpact,
        systemAlignment = systemAlignment,
        urgency = urgency,
        workerState = contextualized.workerState,
        baseline = contextualized.baseline,
        marketContext = contextualized.marketContext,
        historicalPatterns = contextualized.historicalPatterns
    )
}

data class Orientation(
    // What the worker wants
    val intent: IntentType,
    val extractedData: Map<String, Any>,
    val confidence: Float,
    val parseMethod: ParseMethod,

    // What we detected
    val analysis: Analysis,

    // What it means
    val goalImpacts: List<GoalImpact>,
    val proofImpact: ProofImpact,
    val systemAlignment: SystemAlignment,
    val urgency: Urgency,

    // Context for decision
    val workerState: WorkerState,
    val baseline: BaselineComparison?,
    val marketContext: MarketComparison?,
    val historicalPatterns: List<PastInteraction>
)
```

---

## 5. Phase 3: DECIDE

### 5.1 Purpose

Select the action to take based on the orientation. This is where routing happens — which module handles this, what response type, and whether we're confident enough to act.

### 5.2 Decision Tree

```
┌─────────────────────────────────────────────────────────────┐
│                    DECIDE PHASE                              │
│                                                             │
│  Orientation received from ORIENT phase                     │
│           │                                                 │
│           ▼                                                 │
│  ┌─────────────────────────────────┐                       │
│  │  CONFIDENCE CHECK               │                       │
│  │  confidence >= 0.7?             │                       │
│  │  ├── YES → proceed to routing   │                       │
│  │  └── NO  → request clarification│                       │
│  └──────────────┬──────────────────┘                       │
│                 │                                            │
│                 ▼                                            │
│  ┌─────────────────────────────────┐                       │
│  │  URGENCY CHECK                  │                       │
│  │  urgency == CRITICAL?           │                       │
│  │  ├── YES → skip to ACT (alert)  │                       │
│  │  └── NO  → normal routing       │                       │
│  └──────────────┬──────────────────┘                       │
│                 │                                            │
│                 ▼                                            │
│  ┌─────────────────────────────────┐                       │
│  │  MODULE ROUTING                 │                       │
│  │  Based on intent domain:        │                       │
│  │  ├── SALE/PURCHASE/EXPENSE      │                       │
│  │  │   → FinancialModule          │                       │
│  │  ├── PROFIT/BALANCE/STOCK       │                       │
│  │  │   → FinancialModule (query)  │                       │
│  │  ├── LOAN/CREDIT               │                       │
│  │  │   → CreditModule             │                       │
│  │  ├── GOAL/TITHE                │                       │
│  │  │   → GoalsModule              │                       │
│  │  ├── ADVICE/HELP/LESSON        │                       │
│  │  │   → EducationModule          │                       │
│  │  ├── GREETING                  │                       │
│  │  │   → CommunicationModule      │                       │
│  │  └── UNKNOWN                   │                       │
│  │      → LLM escalation          │                       │
│  └──────────────┬──────────────────┘                       │
│                 │                                            │
│                 ▼                                            │
│  ┌─────────────────────────────────┐                       │
│  │  ACTION TYPE SELECTION          │                       │
│  │  Based on analysis signals:     │                       │
│  │  ├── Has anomaly + risk?        │                       │
│  │  │   → RESPOND + ALERT          │                       │
│  │  ├── Has opportunity?           │                       │
│  │  │   → RESPOND + SUGGEST        │                       │
│  │  ├── Normal transaction?        │                       │
│  │  │   → RESPOND + RECORD         │                       │
│  │  ├── Goal milestone?            │                       │
│  │   → RESPOND + CELEBRATE         │                       │
│  │  └── No signals?                │                       │
│  │      → RESPOND only             │                       │
│  └──────────────┬──────────────────┘                       │
│                 │                                            │
│                 ▼                                            │
│  ┌─────────────────────────────────┐                       │
│  │  PROACTIVE DECISIONS            │                       │
│  │  Should we add unsolicited info?│                       │
│  │  ├── Bill due in 2 days?        │                       │
│  │  │   → Add reminder             │                       │
│  │  ├── Streak at risk?            │                       │
│  │  │   → Add encouragement        │                       │
│  │  ├── Market opportunity?        │                       │
│  │  │   → Add suggestion           │                       │
│  │  └── Nothing notable?           │                       │
│  │      → Skip proactive content   │                       │
│  └──────────────┬──────────────────┘                       │
│                 │                                            │
│                 ▼                                            │
│  ┌─────────────────────────────────┐                       │
│  │  DECISION BUILT                 │                       │
│  │  ActionPlan = {                 │                       │
│  │    module, intent, actionType,  │                       │
│  │    proactiveAdditions,          │                       │
│  │    responseTemplate,            │                       │
│  │    priority, confidence         │                       │
│  │  }                              │                       │
│  └─────────────────────────────────┘                       │
└─────────────────────────────────────────────────────────────┘
```

### 5.3 Decision Implementation

```kotlin
// superagent/engine/OodaLoop.kt — decide() method

private fun decide(orientation: Orientation, observation: Observation): ActionPlan {
    // ── CONFIDENCE GATE ──
    if (orientation.confidence < CLARIFICATION_THRESHOLD) {
        return ActionPlan(
            module = null,
            actionType = ActionType.CLARIFICATION,
            responseType = ResponseType.CLARIFICATION,
            confidence = orientation.confidence,
            clarificationQuestion = generateClarification(orientation, observation.language),
            priority = Priority.HIGH
        )
    }

    // ── URGENCY OVERRIDE ──
    if (orientation.urgency == Urgency.CRITICAL) {
        return ActionPlan(
            module = selectModule(orientation.intent),
            actionType = ActionType.ALERT,
            responseType = ResponseType.ALERT,
            confidence = orientation.confidence,
            priority = Priority.CRITICAL,
            proactiveAdditions = listOf(
                ProactiveAddition(
                    type = AdditionType.ALERT,
                    content = orientation.analysis.signals
                        .first { it.severity == Severity.HIGH }.message
                )
            )
        )
    }

    // ── MODULE ROUTING ──
    val module = selectModule(orientation.intent)
    if (module == null) {
        // Unknown intent — LLM escalation
        return ActionPlan(
            module = null,
            actionType = ActionType.LLM_ESCALATION,
            responseType = ResponseType.LLM_GENERATED,
            confidence = orientation.confidence,
            priority = Priority.MEDIUM
        )
    }

    // ── ACTION TYPE SELECTION ──
    val actionType = selectActionType(orientation)

    // ── PROACTIVE ADDITIONS ──
    val proactiveAdditions = selectProactiveAdditions(orientation, observation)

    return ActionPlan(
        module = module,
        actionType = actionType,
        responseType = selectResponseType(actionType, orientation),
        confidence = orientation.confidence,
        priority = orientation.urgency.toPriority(),
        proactiveAdditions = proactiveAdditions,
        proofImpact = orientation.proofImpact,
        goalImpacts = orientation.goalImpacts
    )
}

private fun selectModule(intent: IntentType): CapabilityModule? {
    return when (intent) {
        IntentType.SALE, IntentType.PURCHASE, IntentType.EXPENSE,
        IntentType.PROFIT_QUERY, IntentType.CHECK_BALANCE, IntentType.STOCK_QUERY,
        IntentType.DAILY_SUMMARY, IntentType.WEEKLY_SUMMARY,
        IntentType.RECEIPT_SCAN ->
            financialModule

        IntentType.LOAN_RECORD, IntentType.LOAN_QUERY, IntentType.LOAN_REPORT,
        IntentType.LOAN_DEADLINE, IntentType.CREDIT_SCORE, IntentType.DEBT_ADVICE ->
            creditModule

        IntentType.GOAL_CREATE, IntentType.GOAL_PROGRESS, IntentType.GOAL_REPORT,
        IntentType.GOAL_TIME_FORECAST, IntentType.GOAL_ADJUST,
        IntentType.GIVING_RECORD, IntentType.GIVING_QUERY ->
            goalsModule

        IntentType.ASK_ADVICE, IntentType.GREETING, IntentType.HELP,
        IntentType.MINDSET_LESSON, IntentType.HABITS_CHECK ->
            educationModule

        IntentType.BADGE_QUERY, IntentType.LEADERBOARD ->
            gamificationModule

        else -> null  // triggers LLM escalation
    }
}

private fun selectActionType(orientation: Orientation): ActionType {
    val hasAnomaly = orientation.analysis.hasAnomaly
    val hasRisk = orientation.analysis.hasRisk
    val hasOpportunity = orientation.analysis.hasOpportunity
    val hasGoalMilestone = orientation.goalImpacts.any { it.goal.progressPercent > 90 }

    return when {
        hasAnomaly && hasRisk -> ActionType.RESPOND_AND_ALERT
        hasOpportunity -> ActionType.RESPOND_AND_SUGGEST
        hasGoalMilestone -> ActionType.RESPOND_AND_CELEBRATE
        orientation.intent in transactionIntents -> ActionType.RESPOND_AND_RECORD
        else -> ActionType.RESPOND
    }
}

private fun selectProactiveAdditions(
    orientation: Orientation,
    observation: Observation
): List<ProactiveAddition> {
    val additions = mutableListOf<ProactiveAddition>()

    // Bill due soon?
    observation.worker.pendingAlerts.forEach { alert ->
        if (alert.daysUntil <= 2) {
            additions.add(ProactiveAddition(
                type = AdditionType.REMINDER,
                content = alert.message,
                data = mapOf("alertId" to alert.id)
            ))
        }
    }

    // Streak at risk?
    if (orientation.workerState.daysSinceLastInteraction > 1) {
        additions.add(ProactiveAddition(
            type = AdditionType.ENCOURAGEMENT,
            content = "Streak yako ni siku ${orientation.workerState.streakDays}! Usipoteze."
        ))
    }

    // Market opportunity?
    orientation.analysis.signals
        .filter { it.type == SignalType.OPPORTUNITY }
        .forEach { signal ->
            additions.add(ProactiveAddition(
                type = AdditionType.SUGGESTION,
                content = signal.message
            ))
        }

    // Goal progress worth mentioning?
    orientation.goalImpacts
        .filter { it.impact > 0.05 }  // >5% progress
        .forEach { gi ->
            additions.add(ProactiveAddition(
                type = AdditionType.GOAL_UPDATE,
                content = "Goal '${gi.goal.name}': ${gi.goal.progressPercent.toInt()}% done."
            ))
        }

    return additions
}
```

---

## 6. Phase 4: ACT

### 6.1 Purpose

Execute the decision. This is where the chosen module runs, the response is generated, voice output is produced, and learning signals are captured.

### 6.2 Act Sequence

```
┌─────────────────────────────────────────────────────────────┐
│                    ACT PHASE                                 │
│                                                             │
│  ActionPlan received from DECIDE phase                      │
│           │                                                 │
│           ▼                                                 │
│  ┌─────────────────────────────────┐                       │
│  │  1. EXECUTE MODULE              │                       │
│  │  Call module.handle(resolved)   │                       │
│  │  → AgentResponse                │                       │
│  └──────────────┬──────────────────┘                       │
│                 │                                            │
│                 ▼                                            │
│  ┌─────────────────────────────────┐                       │
│  │  2. SAFETY CHECK                │                       │
│  │  SafetyGuard.verify(response)   │                       │
│  │  → No harmful advice?           │                       │
│  │  → Financial disclaimer added?  │                       │
│  │  → Output sanitized?            │                       │
│  └──────────────┬──────────────────┘                       │
│                 │                                            │
│                 ▼                                            │
│  ┌─────────────────────────────────┐                       │
│  │  3. PERSONALIZE                 │                       │
│  │  CommunicationModule.wrap()     │                       │
│  │  → Add warmth, proverbs,        │                       │
│  │    dialect markers               │                       │
│  └──────────────┬──────────────────┘                       │
│                 │                                            │
│                 ▼                                            │
│  ┌─────────────────────────────────┐                       │
│  │  4. RECORD PROOF                │                       │
│  │  FlywheelEngine.recordProof()   │                       │
│  │  → Alama Score updated          │                       │
│  │  → Tier check → unlock if new   │                       │
│  └──────────────┬──────────────────┘                       │
│                 │                                            │
│                 ▼                                            │
│  ┌─────────────────────────────────┐                       │
│  │  5. STORE CONTEXT               │                       │
│  │  ContextEngine.store()          │                       │
│  │  → L1 conversation updated      │                       │
│  │  → L2 episodic memory updated   │                       │
│  │  → L3 worker profile updated    │                       │
│  └──────────────┬──────────────────┘                       │
│                 │                                            │
│                 ▼                                            │
│  ┌─────────────────────────────────┐                       │
│  │  6. LEARN                       │                       │
│  │  FlywheelEngine.record()        │                       │
│  │  → Pattern tracker updated      │                       │
│  │  → Preference learner updated   │                       │
│  │  → Adaptive learning updated    │                       │
│  └──────────────┬──────────────────┘                       │
│                 │                                            │
│                 ▼                                            │
│  ┌─────────────────────────────────┐                       │
│  │  7. VOICE OUTPUT                │                       │
│  │  TTS generates audio            │                       │
│  │  → Return OodaResult            │                       │
│  └─────────────────────────────────┘                       │
└─────────────────────────────────────────────────────────────┘
```

### 6.3 Act Implementation

```kotlin
// superagent/engine/OodaLoop.kt — act() method

private suspend fun act(
    actionPlan: ActionPlan,
    observation: Observation,
    orientation: Orientation
): OodaResult {
    // ── 1. CLARIFICATION SHORT-CIRCUIT ──
    if (actionPlan.actionType == ActionType.CLARIFICATION) {
        val response = AgentResponse(
            text = actionPlan.clarificationQuestion!!,
            type = ResponseType.CLARIFICATION,
            shouldSpeak = true
        )
        return OodaResult(
            response = response,
            proofPoint = null,
            learningSignal = null,
            actionType = ActionType.CLARIFICATION
        )
    }

    // ── 2. EXECUTE MODULE ──
    val resolvedIntent = ResolvedIntent(
        intent = orientation.intent,
        extractedData = orientation.extractedData,
        confidence = orientation.confidence,
        context = orientation
    )
    val moduleResponse = actionPlan.module!!.handle(resolvedIntent)

    // ── 3. SAFETY CHECK ──
    val safeResponse = safetyGuard.check(
        response = moduleResponse,
        originalInput = observation.text,
        language = observation.language
    )

    // ── 4. PERSONALIZE ──
    val personalized = communicationModule.personalize(
        response = safeResponse,
        language = observation.language,
        workerProfile = contextEngine.getWorkerProfile()
    )

    // ── 5. ATTACH PROACTIVE ADDITIONS ──
    val withProactive = attachProactiveAdditions(personalized, actionPlan.proactiveAdditions)

    // ── 6. RECORD PROOF ──
    val proofPoint = actionPlan.proofImpact?.let { impact ->
        ProofPoint(
            type = impact.type,
            weight = impact.weight,
            data = orientation.extractedData
        )
    }
    if (proofPoint != null) {
        flywheel.recordProof(proofPoint)
        // Check for tier unlock
        val currentTier = flywheel.getCurrentTier()
        if (currentTier != flywheel.getPreviousTier()) {
            // Add tier unlock message to response
            val unlockMsg = currentTier.unlockMessage
            // Will be included in the response
        }
    }

    // ── 7. STORE CONTEXT ──
    contextEngine.store(
        input = observation.text,
        intent = resolvedIntent,
        response = withProactive
    )

    // ── 8. LEARN ──
    val learningSignal = LearningSignal(
        input = observation.text,
        intent = orientation.intent,
        confidence = orientation.confidence,
        parseMethod = orientation.parseMethod,
        actionType = actionPlan.actionType,
        module = actionPlan.module::class.simpleName,
        signals = orientation.analysis.signals.map { it.type },
        timestamp = System.currentTimeMillis()
    )
    flywheel.record(learningSignal)

    // ── 9. RETURN RESULT ──
    return OodaResult(
        response = withProactive,
        proofPoint = proofPoint,
        learningSignal = learningSignal,
        actionType = actionPlan.actionType,
        orientation = orientation  // for debugging/tracing
    )
}
```

### 6.4 LLM Escalation Path

When the intent can't be resolved by code, the OODA loop escalates to the LLM:

```kotlin
// superagent/engine/OodaLoop.kt — LLM escalation

/**
 * LLM escalation: when pattern matching fails, use the language model.
 * This is the fallback path — 90% of inputs don't reach here.
 */
private suspend fun escalateToLlm(
    observation: Observation,
    orientation: Orientation
): OodaResult {
    // Build context for the LLM
    val llmContext = LlmContext(
        workerProfile = contextEngine.getWorkerProfile(),
        recentTransactions = observation.worker.recentTransactions.take(5),
        activeGoals = observation.worker.activeGoals,
        conversationHistory = contextEngine.getConversationMemory().getLastTurns(5),
        input = observation.text,
        language = observation.language
    )

    // Ask the LLM to classify and respond
    val llmResult = llmEngine.classifyAndRespond(llmContext)

    // Parse the LLM's classification
    val parsed = llmResult.toParsedIntent()

    // Re-enter the DECIDE phase with the LLM's classification
    val updatedOrientation = orientation.copy(
        intent = parsed.intent,
        extractedData = parsed.extractedData,
        confidence = parsed.confidence,
        parseMethod = ParseMethod.LLM
    )

    val actionPlan = decide(updatedOrientation, observation)
    return act(actionPlan, observation, updatedOrientation)
}
```

---

## 7. The Feedback Loop

### 7.1 Purpose

After each ACT, observe the outcome. Did the worker follow the advice? Did the prediction come true? This closes the loop and feeds the flywheel.

### 7.2 Feedback Types

```
┌─────────────────────────────────────────────────────────────┐
│                    FEEDBACK LOOP                             │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  IMMEDIATE FEEDBACK (same session)                   │    │
│  │  ├── Worker confirms: "Sawa" → positive signal       │    │
│  │  ├── Worker corrects: "Sio mandazi, ni maandazi"     │    │
│  │  │   → correction signal → re-enter OBSERVE          │    │
│  │  ├── Worker asks follow-up → re-enter OBSERVE        │    │
│  │  └── Worker ignores/disconnects → neutral signal     │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  DELAYED FEEDBACK (next session)                     │    │
│  │  ├── Did worker return the next day?                 │    │
│  │  │   → retention signal                              │    │
│  │  ├── Did worker record more transactions?            │    │
│  │  │   → engagement signal                             │    │
│  │  └── Did worker follow the advice?                   │    │
│  │      → advice compliance signal                      │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  OUTCOME FEEDBACK (days/weeks later)                 │    │
│  │  ├── Did savings goal get closer?                    │    │
│  │  │   → goal progress signal                          │    │
│  │  ├── Did the price prediction come true?             │    │
│  │  │   → prediction accuracy signal                    │    │
│  │  ├── Did the anomaly resolve?                        │    │
│  │  │   → anomaly detection tuning signal               │    │
│  │  └── Did the advice improve financial health?        │    │
│  │      → advice quality signal                         │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

### 7.3 Feedback Collection

```kotlin
// superagent/flywheel/FeedbackCollector.kt

class FeedbackCollector(
    private val patternTracker: PatternTracker,
    private val adaptiveLearning: AdaptiveLearning,
    private val contextEngine: ContextEngine
) {
    /**
     * Record immediate feedback from the current interaction.
     * Called when the worker responds to Msaidizi's output.
     */
    fun recordImmediateFeedback(
        originalInteraction: OodaResult,
        workerResponse: String,
        language: String
    ) {
        val feedbackType = classifyFeedback(workerResponse, language)

        val feedback = ImmediateFeedback(
            originalInteractionId = originalInteraction.learningSignal?.timestamp,
            feedbackType = feedbackType,
            workerResponse = workerResponse,
            timestamp = System.currentTimeMillis()
        )

        when (feedbackType) {
            FeedbackType.CONFIRMATION -> {
                // Worker confirmed — positive signal
                adaptiveLearning.recordSuccess(originalInteraction.learningSignal!!)
            }
            FeedbackType.CORRECTION -> {
                // Worker corrected us — learning signal
                val correction = parseCorrection(workerResponse, originalInteraction)
                adaptiveLearning.recordCorrection(
                    originalInteraction.learningSignal!!,
                    correction
                )
            }
            FeedbackType.CLARIFICATION -> {
                // Worker needs more info — response quality signal
                adaptiveLearning.recordClarificationNeeded(originalInteraction.learningSignal!!)
            }
            FeedbackType.IGNORE -> {
                // Worker didn't engage — possible disinterest
                // Don't penalize — might just be busy
            }
        }
    }

    /**
     * Record delayed feedback from the next session.
     * Called when a worker returns after a gap.
     */
    fun recordDelayedFeedback(
        workerId: String,
        previousSession: SessionSummary,
        currentSession: SessionSummary
    ) {
        // Retention: did the worker come back?
        val daysBetween = daysBetween(previousSession.endTime, currentSession.startTime)
        patternTracker.recordRetention(workerId, daysBetween)

        // Engagement: did they record more transactions?
        val transactionDelta = currentSession.transactionCount - previousSession.transactionCount
        patternTracker.recordEngagement(workerId, transactionDelta)

        // Advice compliance: did they follow through?
        previousSession.adviceGiven.forEach { advice ->
            val outcome = checkAdviceOutcome(advice, currentSession)
            if (outcome != null) {
                adaptiveLearning.recordAdviceOutcome(advice, outcome)
            }
        }
    }

    /**
     * Record outcome feedback from analysis.
     * Called periodically (daily/weekly) by the flywheel improvement cycle.
     */
    fun recordOutcomeFeedback(workerId: String) {
        val profile = contextEngine.getWorkerProfile()
        val goals = contextEngine.getActiveGoals()

        // Goal progress
        goals.forEach { goal ->
            val progress = calculateGoalProgress(goal, profile.recentTransactions)
            patternTracker.recordGoalProgress(workerId, goal.id, progress)
        }

        // Prediction accuracy
        val pastPredictions = contextEngine.getPastPredictions(workerId, days = 30)
        pastPredictions.forEach { prediction ->
            val actual = getActualOutcome(prediction)
            if (actual != null) {
                patternTracker.recordPredictionAccuracy(
                    prediction, actual,
                    accuracy = 1.0 - kotlin.math.abs(prediction.value - actual.value) / actual.value
                )
            }
        }
    }

    private fun classifyFeedback(response: String, language: String): FeedbackType {
        val normalized = response.lowercase().trim()
        return when {
            normalized in listOf("sawa", "asante", "ndiyo", "yes", "ok", "a", "sawa sawa") ->
                FeedbackType.CONFIRMATION
            normalized.startsWith("sio ") || normalized.startsWith("si ") ||
            normalized.contains("nimaanisha") || normalized.contains("i meant") ->
                FeedbackType.CORRECTION
            normalized.contains("?") || normalized.contains("nini") ||
            normalized.contains("what") || normalized.contains("how") ->
                FeedbackType.CLARIFICATION
            else -> FeedbackType.IGNORE
        }
    }
}
```

### 7.4 The Flywheel Integration

The feedback loop feeds directly into the flywheel:

```kotlin
// superagent/flywheel/FlywheelEngine.kt — feedback integration

/**
 * The improvement cycle. Runs periodically (every N interactions or on schedule).
 * Uses feedback to improve all aspects of the system.
 */
fun improve() {
    // ── 1. INTENT ACCURACY ──
    // How well are we classifying intents?
    val corrections = feedbackCollector.getRecentCorrections()
    if (corrections.isNotEmpty()) {
        adaptiveLearning.retrain(corrections)
        // Update intent patterns based on what we got wrong
        intentClassifier.updatePatterns(adaptiveLearning.getNewPatterns())
    }

    // ── 2. RESPONSE QUALITY ──
    // Are our responses helpful?
    val clarifications = feedbackCollector.getClarificationRequests()
    if (clarifications.size > threshold) {
        // Too many clarification requests — responses may be unclear
        communicationModule.adjustVerbosity(increase = true)
    }

    // ── 3. PREDICTION ACCURACY ──
    // Are our predictions coming true?
    val predictionAccuracy = patternTracker.getPredictionAccuracy()
    if (predictionAccuracy < 0.7) {
        // Predictions are unreliable — be more conservative
        adjustPredictionConfidence(decrease = true)
    }

    // ── 4. ADVICE EFFECTIVENESS ──
    // Is our advice actually helping?
    val adviceOutcomes = feedbackCollector.getAdviceOutcomes()
    val effectiveAdvice = adviceOutcomes.filter { it.outcome == AdviceOutcome.POSITIVE }
    val ineffectiveAdvice = adviceOutcomes.filter { it.outcome == AdviceOutcome.NEGATIVE }

    // Reinforce patterns that led to positive outcomes
    effectiveAdvice.forEach { patternTracker.reinforce(it.pattern) }
    // Suppress patterns that led to negative outcomes
    ineffectiveAdvice.forEach { patternTracker.suppress(it.pattern) }

    // ── 5. PERSONALIZATION ──
    // What does THIS worker respond to?
    val preferences = preferenceLearner.consolidate()
    communicationModule.updatePreferences(preferences)

    // ── 6. PATTERN EVOLUTION ──
    // Are new patterns emerging?
    patternTracker.updatePatterns()
}
```

### 7.5 Correction Loop

When the worker corrects Msaidizi, the loop re-enters OBSERVE:

```kotlin
// superagent/engine/OodaLoop.kt — correction handling

/**
 * When the worker corrects a previous response, re-enter the OODA loop
 * with the correction as new input.
 */
suspend fun handleCorrection(
    correctionInput: String,
    previousResult: OodaResult,
    language: String
): OodaResult {
    // Record the correction as feedback
    feedbackCollector.recordImmediateFeedback(
        originalInteraction = previousResult,
        workerResponse = correctionInput,
        language = language
    )

    // Re-enter OBSERVE with the correction as new input
    // The observation includes the previous context for disambiguation
    val observation = observe(
        text = correctionInput,
        voice = null,
        triggerType = TriggerType.FOLLOW_UP
    ).copy(
        // Attach previous context for disambiguation
        previousInteraction = previousResult.orientation
    )

    // Run the full loop again
    val orientation = orient(observation)
    val actionPlan = decide(orientation, observation)
    return act(actionPlan, observation, orientation)
}
```

---

## 8. Data Structures

### 8.1 Core OODA Types

```kotlin
// superagent/engine/OodaModels.kt

/**
 * The result of one complete OODA cycle.
 */
data class OodaResult(
    val response: AgentResponse,
    val proofPoint: ProofPoint?,
    val learningSignal: LearningSignal?,
    val actionType: ActionType,
    val orientation: Orientation? = null,  // null for LLM-generated responses
    val cycleTimeMs: Long = 0
)

/**
 * The decision from the DECIDE phase.
 */
data class ActionPlan(
    val module: CapabilityModule?,
    val actionType: ActionType,
    val responseType: ResponseType,
    val confidence: Float,
    val priority: Priority,
    val proactiveAdditions: List<ProactiveAddition> = emptyList(),
    val proofImpact: ProofImpact? = null,
    val goalImpacts: List<GoalImpact> = emptyList(),
    val clarificationQuestion: String? = null
)

enum class ActionType {
    RESPOND,                    // Simple response
    RESPOND_AND_RECORD,         // Record transaction + respond
    RESPOND_AND_ALERT,          // Respond + anomaly/risk alert
    RESPOND_AND_SUGGEST,        // Respond + opportunity suggestion
    RESPOND_AND_CELEBRATE,      // Respond + goal milestone celebration
    CLARIFICATION,              // Ask for more info
    ALERT_ONLY,                 // Proactive alert (no user input)
    LLM_ESCALATION              // Route to LLM
}

enum class ResponseType {
    TRANSACTION_CONFIRMATION,
    QUERY_RESULT,
    ADVICE,
    ALERT,
    CELEBRATION,
    CLARIFICATION,
    INFORMATION,
    LLM_GENERATED
}

enum class Priority { LOW, MEDIUM, HIGH, CRITICAL }

data class ProactiveAddition(
    val type: AdditionType,
    val content: String,
    val data: Map<String, Any> = emptyMap()
)

enum class AdditionType {
    REMINDER, ENCOURAGEMENT, SUGGESTION, GOAL_UPDATE, ALERT
}

data class ProofImpact(
    val type: ProofType,
    val weight: Double
)

data class GoalImpact(
    val goal: Goal,
    val impact: Double,       // -1.0 to 1.0 (negative = setback, positive = progress)
    val onTrack: Boolean,
    val daysRemaining: Int?
)

data class LearningSignal(
    val input: String,
    val intent: IntentType,
    val confidence: Float,
    val parseMethod: ParseMethod,
    val actionType: ActionType,
    val module: String?,
    val signals: List<SignalType>,
    val timestamp: Long
)
```

### 8.2 Signal Types

```kotlin
// superagent/engine/SignalModels.kt

data class Signal(
    val type: SignalType,
    val severity: Severity,
    val message: String,
    val data: Map<String, Any> = emptyMap()
)

enum class SignalType {
    // Anomalies
    ANOMALY, OVERPRICE, UNDERPRICE, UNUSUAL_VOLUME,
    // Opportunities
    OPPORTUNITY, PRICE_DROP, MARKET_SHIFT,
    // Risks
    STREAK_RISK, STOCK_LOW, GOAL_BEHIND, BUDGET_OVERRUN,
    // Milestones
    GOAL_NEAR, TIER_UNLOCK, CONSISTENCY_MILESTONE
}

enum class Severity { LOW, MEDIUM, HIGH, CRITICAL }
enum class Urgency { LOW, MEDIUM, HIGH, CRITICAL }

fun Urgency.toPriority(): Priority = when (this) {
    Urgency.LOW -> Priority.LOW
    Urgency.MEDIUM -> Priority.MEDIUM
    Urgency.HIGH -> Priority.HIGH
    Urgency.CRITICAL -> Priority.CRITICAL
}
```

---

## 9. Implementation

### 9.1 The OodaLoop Class

The complete OODA loop implementation. This is the **single entry point** for all user input — replacing the Orchestrator:

```kotlin
// superagent/engine/OodaLoop.kt

/**
 * The OODA Loop — the unified reasoning engine for Msaidizi.
 *
 * Replaces: Orchestrator, IntentRouter, DomainRouter, Agent routing
 *
 * Every user input enters through processInput(). The loop:
 * 1. OBSERVES all available signals
 * 2. ORIENTS with deep context
 * 3. DECIDES what action to take
 * 4. ACTS with measurable outcomes
 * 5. FEEDS BACK the results for learning
 *
 * The loop is the brain. Modules are the hands.
 */
class OodaLoop @Inject constructor(
    // Signal providers (OBSERVE)
    private val workerSignalProvider: WorkerSignalProvider,
    private val marketSignalProvider: MarketSignalProvider,
    private val proactiveSignalProvider: ProactiveSignalProvider,
    private val contextSignalProvider: ContextSignalProvider,

    // Reasoning (ORIENT + DECIDE)
    private val intentClassifier: IntentClassifier,
    private val dialectNormalizer: DialectNormalizer,
    private val dataCompletenessChecker: DataCompletenessChecker,

    // Capability modules (ACT)
    private val financialModule: FinancialModule,
    private val creditModule: CreditModule,
    private val goalsModule: GoalsModule,
    private val educationModule: EducationModule,
    private val gamificationModule: GamificationModule,
    private val communicationModule: CommunicationModule,

    // Shared systems
    private val contextEngine: ContextEngine,
    private val flywheel: FlywheelEngine,
    private val safetyGuard: SafetyGuard,
    private val llmEngine: LlmEngine,
    private val feedbackCollector: FeedbackCollector
) {
    companion object {
        const val HIGH_CONFIDENCE_THRESHOLD = 0.85f
        const val MEDIUM_CONFIDENCE_THRESHOLD = 0.60f
        const val CLARIFICATION_THRESHOLD = 0.40f
    }

    /**
     * Process a voice input through the full OODA loop.
     * This is the primary entry point for voice interactions.
     */
    suspend fun processVoice(
        sttResult: String,
        voiceSignal: VoiceSignal
    ): OodaResult {
        val startTime = System.currentTimeMillis()

        // ── OBSERVE ──
        val observation = observe(
            text = sttResult,
            voice = voiceSignal,
            triggerType = TriggerType.VOICE_INPUT
        )

        // ── Data completeness check (M-KOPA: collect RIGHT data) ──
        val completeness = dataCompletenessChecker.check(observation)
        if (!completeness.isComplete) {
            return OodaResult(
                response = AgentResponse(
                    text = completeness.followUpQuestion!!,
                    type = ResponseType.CLARIFICATION,
                    shouldSpeak = true
                ),
                proofPoint = null,
                learningSignal = null,
                actionType = ActionType.CLARIFICATION,
                cycleTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // ── ORIENT → DECIDE → ACT ──
        return runLoop(observation, startTime)
    }

    /**
     * Process a text input through the full OODA loop.
     * Used for text-based interactions (USSD, app, WhatsApp text).
     */
    suspend fun processText(text: String, language: String = "sw"): OodaResult {
        val startTime = System.currentTimeMillis()

        val observation = observe(
            text = text,
            voice = null,
            triggerType = TriggerType.TEXT_INPUT
        )

        val completeness = dataCompletenessChecker.check(observation)
        if (!completeness.isComplete) {
            return OodaResult(
                response = AgentResponse(
                    text = completeness.followUpQuestion!!,
                    type = ResponseType.CLARIFICATION,
                    shouldSpeak = false
                ),
                proofPoint = null,
                learningSignal = null,
                actionType = ActionType.CLARIFICATION,
                cycleTimeMs = System.currentTimeMillis() - startTime
            )
        }

        return runLoop(observation, startTime)
    }

    /**
     * Process a proactive trigger (system-initiated, not user-initiated).
     * Used for alerts, reminders, and nudges.
     */
    suspend fun processProactive(
        trigger: ProactiveTrigger,
        relatedData: Map<String, Any>
    ): OodaResult {
        val startTime = System.currentTimeMillis()

        val observation = observe(
            text = "",  // no user input
            voice = null,
            triggerType = TriggerType.PROACTIVE
        )

        val orientation = orient(observation)
        val actionPlan = decide(orientation, observation)
        return act(actionPlan, observation, orientation).copy(
            cycleTimeMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * Handle a correction from the worker.
     * Re-enters the OODA loop with the correction as new input.
     */
    suspend fun handleCorrection(
        correctionInput: String,
        previousResult: OodaResult,
        language: String
    ): OodaResult {
        return handleCorrection(correctionInput, previousResult, language)
    }

    /**
     * The core loop: OBSERVE → ORIENT → DECIDE → ACT
     */
    private suspend fun runLoop(observation: Observation, startTime: Long): OodaResult {
        // ── ORIENT ──
        val orientation = orient(observation)

        // ── DECIDE ──
        val actionPlan = decide(orientation, observation)

        // ── LLM escalation if needed ──
        if (actionPlan.actionType == ActionType.LLM_ESCALATION) {
            return escalateToLlm(observation, orientation).copy(
                cycleTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // ── ACT ──
        return act(actionPlan, observation, orientation).copy(
            cycleTimeMs = System.currentTimeMillis() - startTime
        )
    }
}
```

### 9.2 Wiring in Hilt

```kotlin
// superagent/engine/di/OodaModule.kt

@Module
@InstallIn(SingletonComponent::class)
object OodaModule {

    @Provides
    @Singleton
    fun provideOodaLoop(
        workerSignalProvider: WorkerSignalProvider,
        marketSignalProvider: MarketSignalProvider,
        proactiveSignalProvider: ProactiveSignalProvider,
        contextSignalProvider: ContextSignalProvider,
        intentClassifier: IntentClassifier,
        dialectNormalizer: DialectNormalizer,
        dataCompletenessChecker: DataCompletenessChecker,
        financialModule: FinancialModule,
        creditModule: CreditModule,
        goalsModule: GoalsModule,
        educationModule: EducationModule,
        gamificationModule: GamificationModule,
        communicationModule: CommunicationModule,
        contextEngine: ContextEngine,
        flywheel: FlywheelEngine,
        safetyGuard: SafetyGuard,
        llmEngine: LlmEngine,
        feedbackCollector: FeedbackCollector
    ): OodaLoop = OodaLoop(
        workerSignalProvider, marketSignalProvider,
        proactiveSignalProvider, contextSignalProvider,
        intentClassifier, dialectNormalizer, dataCompletenessChecker,
        financialModule, creditModule, goalsModule,
        educationModule, gamificationModule, communicationModule,
        contextEngine, flywheel, safetyGuard, llmEngine, feedbackCollector
    )
}
```

### 9.3 Replacing the Orchestrator

The migration is straightforward — replace the Orchestrator call with OodaLoop:

```kotlin
// BEFORE (Orchestrator):
class VoicePipeline(
    private val orchestrator: Orchestrator
) {
    suspend fun onVoiceInput(sttResult: String) {
        val response = orchestrator.processInput(sttResult)
        speak(response.text)
    }
}

// AFTER (OodaLoop):
class VoicePipeline(
    private val oodaLoop: OodaLoop
) {
    suspend fun onVoiceInput(sttResult: String, voiceSignal: VoiceSignal) {
        val result = oodaLoop.processVoice(sttResult, voiceSignal)
        speak(result.response.text)
    }
}
```

---

## 10. Performance Budget

### 10.1 Target Latencies

| Path | Target | Max Acceptable |
|------|--------|----------------|
| Code path (pattern match) | 50-100ms | 200ms |
| Fuzzy match path | 100-200ms | 400ms |
| LLM path (on-device) | 500-1000ms | 2000ms |
| LLM path (cloud) | 1000-2000ms | 5000ms |
| Proactive alert | 200-500ms | 1000ms |

### 10.2 Phase Timing Breakdown

```
OBSERVE phase:   20-50ms   (parallel signal collection)
ORIENT phase:    10-30ms   (context lookup + analysis)
DECIDE phase:    5-15ms    (routing + action selection)
ACT phase:       20-100ms  (module execution + personalization)
───────────────────────────
TOTAL (code):    55-195ms  ✓ Well under 200ms target

LLM escalation:  +500-2000ms (only for ambiguous inputs)
```

### 10.3 Optimization Strategies

1. **Parallel signal collection:** Critical signals collected in parallel via `async`
2. **Timeout cutoffs:** Non-critical signals skip if too slow
3. **Code-first routing:** 90% of inputs skip LLM entirely
4. **Cached context:** Worker profile cached in memory, refreshed periodically
5. **Lazy module initialization:** Modules initialized on first use, not at startup

---

## 11. Offline Behavior

### 11.1 Full Offline OODA

The entire OODA loop works offline. The only difference is signal availability:

```
┌─────────────────────────────────────────────────────────────┐
│                    OFFLINE OODA                              │
│                                                             │
│  OBSERVE:                                                   │
│  ├── Voice signal: ✓ (on-device STT)                       │
│  ├── Market signal: ✗ (no internet) → use cached prices    │
│  ├── Worker signal: ✓ (local Room DB)                      │
│  └── Proactive signal: ✓ (local alerts)                    │
│                                                             │
│  ORIENT:                                                    │
│  ├── Parse: ✓ (on-device pattern matching)                 │
│  ├── Contextualize: ✓ (local context engine)               │
│  ├── Analyze: ✓ (local analysis)                           │
│  └── Align: ✓ (local goal tracking)                        │
│                                                             │
│  DECIDE:                                                    │
│  ├── Confidence check: ✓                                    │
│  ├── Module routing: ✓                                      │
│  └── Action selection: ✓                                    │
│                                                             │
│  ACT:                                                       │
│  ├── Module execution: ✓                                    │
│  ├── Safety check: ✓                                        │
│  ├── Personalization: ✓                                     │
│  ├── Proof recording: ✓ (local Alama Score)                │
│  ├── Context storage: ✓ (local Room DB)                    │
│  ├── Learning: ✓ (local flywheel)                          │
│  └── Voice output: ✓ (on-device TTS)                      │
│                                                             │
│  ★ ONLY MISSING: real-time market data ★                    │
│  ★ Everything else works offline ★                          │
└─────────────────────────────────────────────────────────────┘
```

### 11.2 Degraded Mode Indicators

When operating offline, include a flag in the response so the UI can indicate degraded mode:

```kotlin
data class OodaResult(
    // ... existing fields
    val offlineMode: Boolean = false,
    val degradedSignals: List<String> = emptyList()  // e.g., ["market_data"]
)
```

---

## 12. Error Handling & Fallback

### 12.1 Per-Phase Error Handling

Each phase has its own fallback. Errors don't cascade:

```
OBSERVE fails:
├── Voice signal fails → use text-only observation
├── Market signal fails → use empty market signal
├── Worker signal fails → use minimal context
└── All signals fail → proceed with text-only (degraded but functional)

ORIENT fails:
├── Parse fails → LLM escalation
├── Contextualize fails → proceed with parsed data only
├── Analyze fails → proceed without signals
└── Align fails → proceed without goal context

DECIDE fails:
├── Module routing fails → LLM escalation
├── Confidence too low → clarification response
└── Action selection fails → default RESPOND

ACT fails:
├── Module execution fails → error response + retry
├── Safety check fails → suppress response + log
├── Personalization fails → use raw response
├── Proof recording fails → skip proof (non-critical)
├── Context storage fails → skip storage (non-critical)
├── Learning fails → skip learning (non-critical)
└── TTS fails → return text-only response
```

### 12.2 Graceful Degradation

```kotlin
// superagent/engine/OodaLoop.kt — error handling

private suspend fun runLoop(observation: Observation, startTime: Long): OodaResult {
    return try {
        // Normal path
        val orientation = orient(observation)
        val actionPlan = decide(orientation, observation)

        if (actionPlan.actionType == ActionType.LLM_ESCALATION) {
            return escalateToLlm(observation, orientation)
        }

        act(actionPlan, observation, orientation)
    } catch (e: Exception) {
        // Graceful degradation
        when (e) {
            is OrientationException -> {
                // Can't orient — respond with basic acknowledgment
                OodaResult(
                    response = AgentResponse(
                        text = generateFallbackResponse(observation),
                        type = ResponseType.INFORMATION,
                        shouldSpeak = observation.triggerType == TriggerType.VOICE_INPUT
                    ),
                    actionType = ActionType.RESPOND,
                    cycleTimeMs = System.currentTimeMillis() - startTime
                )
            }
            is ModuleExecutionException -> {
                // Module failed — try LLM as last resort
                try {
                    escalateToLlm(observation, orient(observation))
                } catch (e2: Exception) {
                    OodaResult(
                        response = AgentResponse(
                            text = "Pole, kuna tatizo. Jaribu tena.",
                            type = ResponseType.ERROR,
                            shouldSpeak = true
                        ),
                        actionType = ActionType.RESPOND,
                        cycleTimeMs = System.currentTimeMillis() - startTime
                    )
                }
            }
            else -> {
                // Unknown error — safe fallback
                OodaResult(
                    response = AgentResponse(
                        text = "Pole, sijaelewa. Jaribu tena.",
                        type = ResponseType.ERROR,
                        shouldSpeak = true
                    ),
                    actionType = ActionType.RESPOND,
                    cycleTimeMs = System.currentTimeMillis() - startTime
                )
            }
        }
    }
}
```

---

## 13. Integration Points

### 13.1 How Modules Plug In

Modules implement the `CapabilityModule` interface:

```kotlin
// superagent/engine/CapabilityModule.kt

/**
 * A capability module plugs into the OODA loop's ACT phase.
 * Modules handle specific domains (financial, credit, goals, etc.)
 */
interface CapabilityModule {
    /**
     * Handle a resolved intent and produce a response.
     * Called by the OODA loop during the ACT phase.
     */
    suspend fun handle(resolved: ResolvedIntent): AgentResponse

    /**
     * The set of intents this module can handle.
     * Used by the DECIDE phase for routing.
     */
    val supportedIntents: Set<IntentType>
}

data class ResolvedIntent(
    val intent: IntentType,
    val extractedData: Map<String, Any>,
    val confidence: Float,
    val context: Orientation
)
```

### 13.2 How the Voice Pipeline Connects

```kotlin
// core/voice/pipeline/VoicePipeline.kt

class VoicePipeline @Inject constructor(
    private val sherpaEngine: SherpaVoiceEngine,
    private val ttsEngine: KokoroTtsEngine,
    private val vad: VoiceActivityDetector,
    private val oodaLoop: OodaLoop,
    private val dialectDetector: DialectDetectionEngine,
    private val emotionDetector: VoiceEmotionDetector
) {
    suspend fun processVoiceInput(audioData: FloatArray): OodaResult {
        // STT
        val sttResult = sherpaEngine.transcribe(audioData)

        // Voice signal extraction
        val voiceSignal = VoiceSignal(
            sttResult = sttResult.text,
            asrConfidence = sttResult.confidence,
            language = dialectDetector.detectLanguage(sttResult.text),
            dialect = dialectDetector.detectDialect(sttResult.text),
            emotion = emotionDetector.detect(audioData),
            pitch = extractPitch(audioData),
            pace = extractPace(audioData),
            volume = extractVolume(audioData)
        )

        // OODA loop
        val result = oodaLoop.processVoice(sttResult.text, voiceSignal)

        // TTS
        if (result.response.shouldSpeak) {
            ttsEngine.speak(result.response.text)
        }

        return result
    }
}
```

### 13.3 How Proactive Triggers Connect

```kotlin
// superagent/financial/ProactiveAlerts.kt

class ProactiveAlerts @Inject constructor(
    private val oodaLoop: OodaLoop,
    private val contextEngine: ContextEngine
) {
    /**
     * Called by scheduled jobs (cron/heartbeat) to check for proactive triggers.
     */
    suspend fun checkAndAlert() {
        val alerts = mutableListOf<ProactiveTrigger>()

        // Bill due?
        val upcomingBills = contextEngine.getUpcomingBills(days = 3)
        if (upcomingBills.isNotEmpty()) {
            alerts.add(ProactiveTrigger.BILL_DUE)
        }

        // Stock low?
        val lowStock = contextEngine.getLowStockItems()
        if (lowStock.isNotEmpty()) {
            alerts.add(ProactiveTrigger.STOCK_LOW)
        }

        // Goal behind?
        val behindGoals = contextEngine.getBehindGoals()
        if (behindGoals.isNotEmpty()) {
            alerts.add(ProactiveTrigger.GOAL_BEHIND)
        }

        // Process each trigger through the OODA loop
        alerts.forEach { trigger ->
            oodaLoop.processProactive(trigger, mapOf("alerts" to alerts))
        }
    }
}
```

---

## 14. Testing Strategy

### 14.1 Unit Tests Per Phase

```kotlin
// superagent/engine/src/test/OodaObserveTest.kt

@Test
fun `observe collects voice signal correctly`() = runTest {
    val observation = oodaLoop.observe(
        text = "Nimeuziwa mandazi kumi",
        voice = VoiceSignal(
            sttResult = "Nimeuziwa mandazi kumi",
            asrConfidence = 0.95f,
            language = "sw",
            dialect = "standard",
            emotion = null, pitch = null, pace = null, volume = null
        ),
        triggerType = TriggerType.VOICE_INPUT
    )

    assertEquals("Nimeuziwa mandazi kumi", observation.text)
    assertEquals("sw", observation.language)
    assertEquals(TriggerType.VOICE_INPUT, observation.triggerType)
}

@Test
fun `observe handles missing market data gracefully`() = runTest {
    // Simulate market signal timeout
    val observation = oodaLoop.observe(
        text = "Nimeuziwa mandazi kumi",
        voice = null,
        triggerType = TriggerType.TEXT_INPUT
    )

    // Should still produce a valid observation
    assertNotNull(observation)
    assertTrue(observation.market.relevantPrices.isEmpty())
}
```

```kotlin
// superagent/engine/src/test/OodaOrientTest.kt

@Test
fun `orient correctly parses sale intent`() = runTest {
    val observation = createObservation("Nimeuziwa mandazi kumi, mia mbili")
    val orientation = oodaLoop.orient(observation)

    assertEquals(IntentType.SALE, orientation.intent)
    assertEquals("mandazi", orientation.extractedData["item"])
    assertEquals(10.0, orientation.extractedData["quantity"])
    assertEquals(200.0, orientation.extractedData["amount"])
    assertTrue(orientation.confidence > 0.8f)
}

@Test
fun `orient detects below-average sales`() = runTest {
    // Worker typically sells 15 mandazi, today only 5
    val observation = createObservation("Nimeuziwa mandazi tano")
    val orientation = oodaLoop.orient(observation)

    assertTrue(orientation.analysis.hasAnomaly)
    assertTrue(orientation.analysis.signals.any {
        it.type == SignalType.ANOMALY && it.message.contains("below average")
    })
}
```

```kotlin
// superagent/engine/src/test/OodaDecideTest.kt

@Test
fun `decide routes sale to financial module`() = runTest {
    val orientation = createOrientation(intent = IntentType.SALE)
    val observation = createObservation("Nimeuziwa mandazi kumi")
    val actionPlan = oodaLoop.decide(orientation, observation)

    assertEquals(financialModule, actionPlan.module)
    assertEquals(ActionType.RESPOND_AND_RECORD, actionPlan.actionType)
}

@Test
fun `decide requests clarification for low confidence`() = runTest {
    val orientation = createOrientation(confidence = 0.3f)
    val observation = createObservation("kitu fulani")
    val actionPlan = oodaLoop.decide(orientation, observation)

    assertEquals(ActionType.CLARIFICATION, actionPlan.actionType)
    assertNotNull(actionPlan.clarificationQuestion)
}
```

```kotlin
// superagent/engine/src/test/OodaActTest.kt

@Test
fun `act records proof point for transaction`() = runTest {
    val actionPlan = createActionPlan(proofImpact = ProofImpact(ProofType.TRANSACTION, 1.0))
    val observation = createObservation("Nimeuziwa mandazi kumi")
    val orientation = createOrientation(intent = IntentType.SALE)

    val result = oodaLoop.act(actionPlan, observation, orientation)

    assertNotNull(result.proofPoint)
    assertEquals(ProofType.TRANSACTION, result.proofPoint!!.type)
}

@Test
fun `act stores interaction in context engine`() = runTest {
    val actionPlan = createActionPlan()
    val observation = createObservation("Nimeuziwa mandazi kumi")
    val orientation = createOrientation(intent = IntentType.SALE)

    oodaLoop.act(actionPlan, observation, orientation)

    // Verify context was updated
    val lastInteraction = contextEngine.getLastInteraction()
    assertEquals("Nimeuziwa mandazi kumi", lastInteraction?.input)
}
```

### 14.2 Integration Tests

```kotlin
// superagent/engine/src/test/OodaIntegrationTest.kt

@Test
fun `full loop processes sale correctly`() = runTest {
    val result = oodaLoop.processText("Nimeuziwa mandazi kumi, mia mbili")

    // Response is a sale confirmation
    assertEquals(ResponseType.TRANSACTION_CONFIRMATION, result.response.type)
    assertTrue(result.response.text.contains("mandazi"))

    // Proof point recorded
    assertNotNull(result.proofPoint)

    // Learning signal captured
    assertNotNull(result.learningSignal)
    assertEquals(IntentType.SALE, result.learningSignal!!.intent)

    // Transaction saved to database
    val transactions = transactionDao.getToday()
    assertEquals(1, transactions.size)
    assertEquals("mandazi", transactions[0].item)
    assertEquals(200.0, transactions[0].totalAmount)
}

@Test
fun `correction re-enters loop and fixes data`() = runTest {
    // First: record with wrong item
    val first = oodaLoop.processText("Nimeuziwa mandazi kumi")

    // Worker corrects
    val corrected = oodaLoop.handleCorrection(
        correctionInput = "Sio mandazi, ni maandazi",
        previousResult = first,
        language = "sw"
    )

    // Verify correction was applied
    val transactions = transactionDao.getToday()
    assertEquals("maandazi", transactions[0].item)
}
```

### 14.3 Performance Tests

```kotlin
// superagent/engine/src/test/OodaPerformanceTest.kt

@Test
fun `code path completes under 200ms`() = runTest {
    val start = System.currentTimeMillis()
    oodaLoop.processText("Nimeuziwa mandazi kumi, mia mbili")
    val elapsed = System.currentTimeMillis() - start

    assertTrue(elapsed < 200, "Code path took ${elapsed}ms, target < 200ms")
}

@Test
fun `parallel signal collection is faster than sequential`() = runTest {
    val parallelStart = System.currentTimeMillis()
    oodaLoop.processText("Faida ya leo ni ngapi?")
    val parallelTime = System.currentTimeMillis() - parallelStart

    // Parallel should be significantly faster than collecting signals one-by-one
    assertTrue(parallelTime < 150, "Parallel collection took ${parallelTime}ms")
}
```

---

## Appendix A: OODA vs Old Orchestrator

| Aspect | Old Orchestrator | New OODA Loop |
|--------|-----------------|---------------|
| Constructor params | 40+ | 18 |
| Lines of code | ~400 | ~500 (but structured) |
| Entry points | `processInput()` | `processVoice()`, `processText()`, `processProactive()` |
| Routing | IntentRouter → Agent → Handler | DECIDE phase → direct module call |
| Learning | None (handled elsewhere) | Integrated (feedback loop) |
| Proof accumulation | None | Integrated (every transaction = proof) |
| Proactive capability | None | Integrated (proactive triggers) |
| Error handling | Crash → retry | Per-phase fallback |
| Offline support | Partial | Full |
| Debugging | Trace across agents | One loop trace |

## Appendix B: Boyd's Original OODA → Msaidizi Mapping

| Boyd's Concept | Msaidizi Equivalent |
|----------------|---------------------|
| Observe the environment | Collect voice, market, worker, proactive signals |
| Orient (cultural traditions, previous experience, new information, genetic heritage) | Parse intent + contextualize with history + analyze anomalies + align with goals |
| Decide (hypothesis generation) | Select module, action type, proactive additions |
| Act (test hypothesis) | Execute module, safety check, personalize, record proof, learn |
| Feedback (observation of results) | Did worker confirm? Follow advice? Return tomorrow? |
| Speed of loop | 50-200ms code path; faster orientation = faster decisions |
| Inside opponent's loop | Msaidizi knows the worker's business better than they do |

## Appendix C: File Locations

| Component | File Path |
|-----------|-----------|
| OodaLoop | `superagent/engine/OodaLoop.kt` |
| OodaModels | `superagent/engine/OodaModels.kt` |
| SignalModels | `superagent/engine/SignalModels.kt` |
| CapabilityModule | `superagent/engine/CapabilityModule.kt` |
| SignalProvider | `superagent/engine/SignalProvider.kt` |
| WorkerSignalProvider | `superagent/context/WorkerSignalProvider.kt` |
| MarketSignalProvider | `superagent/financial/MarketSignalProvider.kt` |
| ProactiveSignalProvider | `superagent/financial/ProactiveSignalProvider.kt` |
| ContextSignalProvider | `superagent/context/ContextSignalProvider.kt` |
| DataCompletenessChecker | `superagent/engine/DataCompletenessChecker.kt` |
| FeedbackCollector | `superagent/flywheel/FeedbackCollector.kt` |
| FlywheelEngine | `superagent/flywheel/FlywheelEngine.kt` |
| Orientation | `superagent/engine/Orientation.kt` |

---

*End of OODA Loop Design Document*
