# Unified Hermes Memory Architecture — L1↔L2↔L3 as ONE System

**Author:** Chief Memory Architect
**Date:** 2026-07-24
**Status:** Architecture Design — Ready for Implementation

---

## 1. Current State Analysis: Three Islands, No Bridges

### What Exists

| Layer | Android (On-Device) | Backend (Python) |
|-------|--------------------|--------------------|
| **L1** | `ConversationMemory.kt` — FIFO queue, 10 turns, 30-min window, pronoun resolution | `WorkingMemory` — priority-weighted items, 50 capacity |
| **L2** | `EpisodicMemory.kt` — SQLite FTS5, 10K episodes, BM25 search, relevance decay | `SQLiteFTS5Store` + `EpisodicMemory` — same pattern, Python |
| **L3** | `HermesSessionManager.kt` — WorkerProfile, LearnedSkill, skill discovery (in-memory only) | `WorkerBehavioralModel` — Bayesian beliefs (10 dimensions), SQLite persistence |
| **Bridge** | `AdaptiveLearningEngine.kt` — vocabulary, corrections, context injection | `TieredMemoryManager` — observe→think→act→reflect, event bus |

### How They're Disconnected (The Problem)

```
ANDROID (Current):

  ConversationMemory (L1)
       │
       │ addTurn() / getContext()
       │ (self-contained, never queries L2 or L3)
       │
       ▼
  Orchestrator.processInput()
       │
       ├── intentRouter.classify()
       ├── adaptiveLearning.enhanceIntentWithLearning()  ← Only checks UserVocabulary
       ├── routeToHandler()
       └── conversationManager.postProcess()
              │
              └── conversationMemory.addTurn()  ← CIRCULAR, never touches L2/L3

  EpisodicMemory (L2) ← EXISTS BUT NEVER QUERIED DURING CONVERSATION
       │
       └── storeEpisode() called... nowhere in the main pipeline
           search() called... nowhere in the main pipeline

  HermesSessionManager (L3)
       │
       ├── discoverSkills() ← EXISTS BUT NOT WIRED INTO Orchestrator
       ├── startTrace() / completeInteraction() ← EXISTS BUT NOT CALLED
       └── WorkerProfile ← UPDATED ONLY BY consolidateMemory(), never by L2
```

**Root Cause:** Each layer was built independently. The Orchestrator only uses L1 (ConversationMemory) and AdaptiveLearningEngine. L2 and L3 are dead code in the live pipeline.

### Specific Disconnections

1. **L2 → L1 (Missing):** When a worker says "How much did I sell tomatoes last week?", the system has NO mechanism to search L2 (EpisodicMemory) for past tomato sales episodes and inject them into the current conversation context.

2. **L3 ← L2 (Missing):** When L2 accumulates patterns (e.g., "this worker always asks about prices on Mondays"), L3's WorkerProfile never receives these insights. The Bayesian beliefs stay at their priors.

3. **L1 → L3 (Missing):** When L1 detects a correction ("no, that was 500 not 300"), L3's behavioral model doesn't update its price_sensitivity or decision_speed beliefs.

4. **Cross-session (Missing):** A worker switching from app to WhatsApp loses all L1 context. HermesSessionManager has the cross-session architecture but it's not connected to the conversation flow.

---

## 2. Unified Memory Design: The Complete Flow

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        ORCHESTRATOR                                  │
│                                                                     │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────────────┐  │
│  │ Conversation  │    │   Unified    │    │  HermesSession       │  │
│  │   Memory      │◄──│   Memory     │──►│  Manager (L3)        │  │
│  │   (L1)        │    │   Bridge     │    │  WorkerProfile       │  │
│  │               │    │              │    │  LearnedSkill        │  │
│  │  10 turns     │    │  THE GLUE    │    │  Bayesian Beliefs    │  │
│  │  30-min win   │    │              │    │                      │  │
│  └──────┬───────┘    └──────┬───────┘    └──────────┬───────────┘  │
│         │                   │                        │              │
│         │          ┌────────▼────────┐               │              │
│         │          │  Episodic Memory │               │              │
│         └─────────►│  (L2)           │◄──────────────┘              │
│                    │  SQLite FTS5     │                              │
│                    │  10K episodes    │                              │
│                    │  BM25 search     │                              │
│                    └─────────────────┘                              │
└─────────────────────────────────────────────────────────────────────┘
```

### The Unified Memory Bridge

The core addition is a `UnifiedMemoryBridge` class that sits between the Orchestrator and all three layers. It orchestrates the memory flow for every interaction.

### 2.1 Complete Memory Flow for One Interaction

```
Worker: "Nimeuza nyanya kwa 500"  (I sold tomatoes for 500)

STEP 1: PRE-PROCESS (Memory Bridge activates)
  ├── L1: Check ConversationMemory for follow-up context
  │   └── Result: lastItem="sukari", lastAmount=300
  │
  ├── L2: Search EpisodicMemory for "nyanya" episodes
  │   └── FTS5 query: "nyanya" → 3 past episodes
  │       ├── Episode: "Nimeuza nyanya kwa 450" (2 days ago)
  │       ├── Episode: "Nyanya bei ni 400-550" (pattern)
  │       └── Episode: "Nyanya zimepungua sokoni" (market note)
  │
  └── L3: Query HermesSessionManager for relevant skills
      └── Skill: "Tomato Pricing Protocol" (confidence: 0.72)
          └── Procedure: ["Check market price", "Record at EMA price"]

STEP 2: CONTEXT ASSEMBLY
  UnifiedMemoryBridge assembles enriched context:
  ┌─────────────────────────────────────────────┐
  │ Conversation Context (L1):                  │
  │   Last item: sukari, Last amount: 300       │
  │                                             │
  │ Relevant Episodes (L2):                     │
  │   - 2 days ago: nyanya @ 450                │
  │   - Price range: 400-550                    │
  │   - Market note: supply low                 │
  │                                             │
  │ Active Skills (L3):                         │
  │   - Tomato Pricing Protocol (72% conf)      │
  │   - Suggested: record at EMA price          │
  │                                             │
  │ Worker Profile (L3):                        │
  │   - Risk tolerance: 0.6 (moderate)          │
  │   - Price sensitivity: 0.8 (high)           │
  │   - Usually sells tomatoes on Tue/Fri       │
  └─────────────────────────────────────────────┘

STEP 3: INTENT CLASSIFICATION (enhanced)
  IntentRouter.classify() receives enriched context:
  - Original: "Nimeuza nyanya kwa 500"
  - L2 hint: price is within normal range (400-550) ✓
  - L3 hint: worker's avg tomato price is 475 (EMA)
  - Confidence boost: 0.85 → 0.93 (L2 context confirms intent)

STEP 4: RESPONSE GENERATION
  Handler records transaction with L2/L3 awareness:
  - L2 price anomaly check: 500 is within range ✓
  - L3 belief update: daily_revenue += 500
  - L1 turn recorded

STEP 5: POST-PROCESS (Memory Bridge updates)
  ├── L1: addTurn("worker", "Nimeuza nyanya kwa 500", intent)
  ├── L2: storeEpisode(workerId, query, response, outcome, lessons)
  ├── L3: hermesSession.completeInteraction() → may generate skill
  ├── L3: workerModel.update(observations={daily_revenue: 500})
  └── L3: adaptiveLearning.learnFromTransaction(transaction)
```

### 2.2 How L2 Feeds Relevant Episodes into L1

**Mechanism:** Before every intent classification, the UnifiedMemoryBridge queries L2 with the current input text and injects the top-K relevant episodes as "conversation context hints."

```kotlin
// In UnifiedMemoryBridge.kt
class UnifiedMemoryBridge(
    private val conversationMemory: ConversationMemory,     // L1
    private val episodicMemory: EpisodicMemory,             // L2
    private val hermesSession: HermesSessionManager,        // L3
    private val adaptiveLearning: AdaptiveLearningEngine,   // Learning
    private val workerProfile: WorkerProfile? = null        // L3 profile
) {
    /**
     * Pre-process: enrich conversation context with L2/L3 data.
     * Called BEFORE intent classification.
     */
    fun enrichContext(
        currentInput: String,
        workerId: String
    ): EnrichedContext {
        // L1: Get current conversation context
        val l1Context = conversationMemory.getContext()

        // L2: Search for relevant episodes (sub-10ms via FTS5)
        val relevantEpisodes = episodicMemory.search(
            query = currentInput,
            workerId = workerId,
            limit = 5
        )

        // L2: Search for relevant skills
        val relevantSkills = episodicMemory.searchSkills(
            query = currentInput,
            workerId = workerId,
            limit = 3
        )

        // L3: Get worker behavioral signals
        val workerSignals = hermesSession.getWorkerProfile(workerId)

        // L3: Discover learned skills from Hermes
        val hermesSkills = hermesSession.discoverSkills(workerId, currentInput)

        return EnrichedContext(
            l1Context = l1Context,
            relevantEpisodes = relevantEpisodes,
            relevantSkills = relevantSkills,
            hermesSkills = hermesSkills,
            workerProfile = workerSignals,
            priceContext = buildPriceContext(relevantEpisodes, currentInput),
            patternHints = buildPatternHints(workerSignals, l1Context)
        )
    }

    /**
     * Build price context from L2 episodes.
     * If the current input mentions a price, check against historical data.
     */
    private fun buildPriceContext(
        episodes: List<Episode>,
        currentInput: String
    ): PriceContext? {
        // Extract price from current input
        val priceMatch = Regex("""(\d+)""").findAll(currentInput)
            .map { it.value.toDouble() }
            .firstOrNull() ?: return null

        // Extract item from current input
        val item = extractItemFromInput(currentInput) ?: return null

        // Find historical prices for this item from episodes
        val historicalPrices = episodes
            .filter { it.query.contains(item, ignoreCase = true) }
            .mapNotNull { extractPriceFromEpisode(it) }

        if (historicalPrices.isEmpty()) return null

        val avgPrice = historicalPrices.average()
        val minPrice = historicalPrices.min()
        val maxPrice = historicalPrices.max()
        val isAnomaly = pricePrice < minPrice * 0.5 || price > maxPrice * 1.5

        return PriceContext(
            item = item,
            currentPrice = price,
            avgHistoricalPrice = avgPrice,
            minHistoricalPrice = minPrice,
            maxHistoricalPrice = maxPrice,
            isPriceAnomaly = isAnomaly,
            observationCount = historicalPrices.size
        )
    }
}
```

### 2.3 How L3 Updates from L2 Patterns

**Mechanism:** After every interaction, the UnifiedMemoryBridge extracts observations from the interaction and updates L3's Bayesian beliefs.

```kotlin
// In UnifiedMemoryBridge.kt
/**
 * Post-process: update all memory layers after interaction.
 * Called AFTER response generation.
 */
suspend fun postProcessInteraction(
    workerId: String,
    query: String,
    response: String,
    intentResult: IntentResult,
    transaction: Transaction?,
    outcome: String
) {
    val now = System.currentTimeMillis()

    // ── L1: Update conversation memory ──
    conversationMemory.addTurn("worker", query, intentResult, intentResult.extractedData)
    conversationMemory.addTurn("msaidizi", response)

    // ── L2: Store episode in SQLite FTS5 ──
    val episodeId = episodicMemory.storeEpisode(
        workerId = workerId,
        query = query,
        response = response,
        outcome = outcome,
        lessons = extractLessons(intentResult, outcome),
        context = mapOf(
            "intent" to intentResult.intent.name,
            "confidence" to intentResult.confidence.toString(),
            "channel" to "app"
        )
    )

    // ── L3: Update Hermes session trace ──
    val traceId = hermesSession.getSession(workerId)?.activeTraceId
    if (traceId != null) {
        hermesSession.recordTraceStep(
            workerId = workerId,
            traceId = traceId,
            action = intentResult.intent.name,
            toolUsed = "transaction_handler",
            success = outcome == "success"
        )
        val generatedSkill = hermesSession.completeInteraction(
            workerId = workerId,
            traceId = traceId,
            response = response,
            outcome = outcome
        )
        // Store generated skill in L2 for FTS5 searchability
        if (generatedSkill != null) {
            episodicMemory.storeSkill(
                skillId = generatedSkill.skillId,
                title = generatedSkill.title,
                category = generatedSkill.category,
                content = generatedSkill.procedure.joinToString("\n"),
                keywords = generatedSkill.keywords,
                confidence = generatedSkill.confidence,
                workerId = workerId
            )
        }
    }

    // ── L3: Update Bayesian behavioral model ──
    if (transaction != null) {
        val observations = mutableMapOf<String, Double>()

        // Revenue signal
        if (transaction.type == TransactionType.SALE) {
            observations["daily_revenue"] = transaction.totalAmount
        }

        // Price signal
        if (transaction.totalAmount > 0 && transaction.quantity > 0) {
            val unitPrice = transaction.totalAmount / transaction.quantity
            observations["price_sensitivity"] = calculatePriceSensitivity(
                item = transaction.item,
                price = unitPrice,
                episodes = episodicMemory.search(transaction.item, workerId, limit=10)
            )
        }

        // Timing signal
        val hour = java.time.LocalTime.now().hour
        observations["preferred_early_morning"] = if (hour < 10) 1.0 else 0.0

        // Restock frequency signal
        observations["restock_frequency_days"] = estimateRestockFrequency(workerId)

        // Update L3 via Hermes
        hermesSession.updateWorkerBeliefs(workerId, observations)
    }

    // ── L3: Update AdaptiveLearningEngine ──
    if (transaction != null) {
        adaptiveLearning.learnFromTransaction(transaction)
    }

    // ── Cross-layer: Feed L2 patterns to L3 periodically ──
    maybeConsolidateL2toL3(workerId)
}

/**
 * Extract behavioral observations from L2 episodes and update L3 beliefs.
 * Runs periodically (every 10 interactions or during idle time).
 */
private suspend fun maybeConsolidateL2toL3(workerId: String) {
    val session = hermesSession.getSession(workerId) ?: return

    // Only consolidate every 10 interactions
    if (session.contextWindow.size % 10 != 0) return

    // Get recent success/failure patterns from L2
    val successEpisodes = episodicMemory.search(
        query = "*", workerId = workerId, limit = 20
    ).filter { it.outcome == "success" }

    val failureEpisodes = episodicMemory.search(
        query = "*", workerId = workerId, limit = 20
    ).filter { it.outcome == "failure" }

    // Calculate behavioral signals from L2 data
    val observations = mutableMapOf<String, Double>()

    // Decision speed: how quickly the worker completes transactions
    if (successEpisodes.isNotEmpty()) {
        val avgResponseLength = successEpisodes
            .map { it.query.length }
            .average()
        observations["decision_speed"] = (1.0 - (avgResponseLength / 200.0)).coerceIn(0.0, 1.0)
    }

    // Risk aversion: ratio of corrections to total interactions
    val correctionCount = failureEpisodes.count {
        it.lessons.any { lesson -> lesson.contains("correction", ignoreCase = true) }
    }
    if (successEpisodes.size + failureEpisodes.size > 5) {
        observations["risk_aversion"] = correctionCount.toDouble() /
            (successEpisodes.size + failureEpisodes.size).coerceAtLeast(1)
    }

    // Update L3 with consolidated L2 signals
    hermesSession.updateWorkerBeliefs(workerId, observations)
}
```

### 2.4 How L1 Context Influences L3 Learning

**Mechanism:** The conversation flow (L1) generates signals that L3 uses to refine its behavioral model.

```kotlin
/**
 * L1 → L3 signal flow:
 * - Correction patterns → update price_sensitivity belief
 * - Follow-up patterns → update decision_speed belief
 * - Topic chains → update business_domain in WorkerProfile
 * - Confidence levels → update trust_score in ProgressiveAutonomy
 */

// In UnifiedMemoryBridge
fun extractL1SignalsForL3(
    l1Context: ConversationContext,
    intentResult: IntentResult,
    outcome: String
): Map<String, Double> {
    val signals = mutableMapOf<String, Double>()

    // Signal 1: Correction frequency → risk_aversion
    val correctionCount = l1Context.turns.count {
        it.intent?.intent == IntentType.CORRECTION
    }
    if (correctionCount > 0) {
        signals["risk_aversion"] = min(1.0, correctionCount * 0.2)
    }

    // Signal 2: Follow-up frequency → decision_speed
    val followUpCount = l1Context.turns.count {
        it.speaker == "worker" && isFollowUpPattern(it.text)
    }
    if (followUpCount > 2) {
        signals["decision_speed"] = 0.8  // Quick decision-maker
    }

    // Signal 3: Topic concentration → business_domain
    val topicFrequency = l1Context.topicChain.groupingBy { it }.eachCount()
    val dominantTopic = topicFrequency.maxByOrNull { it.value }
    if (dominantTopic != null && dominantTopic.value >= 3) {
        // Worker is focused on one domain → update profile
        signals["domain_focus"] = dominantTopic.value.toDouble() / l1Context.topicChain.size
    }

    // Signal 4: Price queries → price_sensitivity
    val priceQueries = l1Context.turns.count {
        it.text.contains(Regex("(?i)(bei|price|gharama|cost)"))
    }
    if (priceQueries > 0) {
        signals["price_sensitivity"] = min(1.0, 0.5 + priceQueries * 0.15)
    }

    return signals
}
```

---

## 3. On-Device vs Backend: Where Memory Lives

### 3.1 Memory Placement Rules

```
┌─────────────────────────────────────────────────────────────────┐
│                    ON-DEVICE (Android)                           │
│                    Privacy-sensitive, real-time                  │
│                                                                 │
│  L1: ConversationMemory                                         │
│    ├── Current session turns (10 max)                           │
│    ├── Pronoun resolution state                                 │
│    └── Topic chain                                              │
│                                                                 │
│  L2: EpisodicMemory (SQLite FTS5)                               │
│    ├── All interaction episodes (10K max)                       │
│    ├── Generated skills                                         │
│    ├── Worker-hashed IDs (SHA-256)                              │
│    └── Relevance decay + eviction                               │
│                                                                 │
│  L3: HermesSessionManager + WorkerProfile                       │
│    ├── Active session state                                     │
│    ├── In-memory skill store                                    │
│    ├── Worker behavioral profile                                │
│    └── Bayesian beliefs (10 dimensions)                         │
│                                                                 │
│  AdaptiveLearningEngine                                         │
│    ├── UserVocabulary (spoken → canonical)                      │
│    ├── UserCorrections                                          │
│    ├── BusinessPatternTracker patterns                          │
│    └── Price/quantity tracking                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    BACKEND (Python/Node)                         │
│                    Collective intelligence, aggregation          │
│                                                                 │
│  L2-Aggregate: Cross-worker episodic patterns                   │
│    ├── Anonymized episode patterns (no raw data)                │
│    ├── Market-wide price trends                                 │
│    └── Regional business patterns                               │
│                                                                 │
│  L3-Aggregate: Collective behavioral models                     │
│    ├── Worker archetype clustering                              │
│    ├── Sector-wide Bayesian priors                              │
│    └── Skill effectiveness aggregation                          │
│                                                                 │
│  Skill Marketplace                                              │
│    ├── Skills anonymized + aggregated from workers              │
│    ├── Ranked by cross-worker success rate                      │
│    └── Distributed to new workers as "starter skills"           │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 What Stays On-Device (Never Leaves the Phone)

| Data | Reason |
|------|--------|
| Raw conversation text | Privacy — contains business details |
| Worker ID (real) | Only hashed version leaves device |
| Individual transaction amounts | Financial privacy |
| Correction patterns | Reveals business mistakes |
| Worker behavioral beliefs | Personal profile |
| L1 conversation turns | Session-scoped, ephemeral |
| L2 episodes (raw) | Full interaction history |
| L3 individual Bayesian beliefs | Personal behavioral model |

### 3.3 What Goes to Backend (Anonymized)

| Data | Aggregation Method |
|------|-------------------|
| Price trends per item | Differential privacy (add noise ±5%) |
| Popular skill categories | Count aggregation (no worker ID) |
| Skill success rates | Binary success/failure, no details |
| Worker archetypes | K-means clustering on feature vectors |
| Market demand signals | Regional aggregation (no individual) |
| Failure pattern types | Categorized, anonymized |

### 3.4 Federated Learning Sync Protocol

```kotlin
/**
 * Federated Learning Sync — updates collective intelligence
 * without sharing raw data.
 *
 * Flow:
 * 1. Device computes local model update (gradient)
 * 2. Update is anonymized + noise added (differential privacy)
 * 3. Sent to backend for aggregation
 * 4. Backend aggregates across workers → global model update
 * 5. Global update pushed back to device
 * 6. Device applies update to local model
 */
class FederatedMemorySync(
    private val episodicMemory: EpisodicMemory,
    private val hermesSession: HermesSessionManager,
    private val syncApi: SyncApi,
    private val privacyBudget: Double = 1.0  // ε for differential privacy
) {
    /**
     * Compute local model update for sync.
     * Extracts anonymized patterns from L2/L3.
     */
    suspend fun computeLocalUpdate(workerId: String): LocalModelUpdate {
        // Extract price trends (anonymized)
        val priceTrends = extractAnonymizedPriceTrends(workerId)

        // Extract skill effectiveness (anonymized)
        val skillMetrics = extractAnonymizedSkillMetrics(workerId)

        // Extract behavioral archetype features
        val archetypeFeatures = extractArchetypeFeatures(workerId)

        // Add differential privacy noise
        val noisyTrends = addLaplaceNoise(priceTrends, privacyBudget)
        val noisyFeatures = addLaplaceNoise(archetypeFeatures, privacyBudget)

        return LocalModelUpdate(
            workerHash = hashWorkerId(workerId),
            priceTrends = noisyTrends,
            skillMetrics = skillMetrics,
            archetypeFeatures = noisyFeatures,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Apply global model update from backend.
     * Updates L3 priors and skill marketplace.
     */
    suspend fun applyGlobalUpdate(update: GlobalModelUpdate) {
        // Update L3 Bayesian priors with collective intelligence
        update.collectivePriors?.forEach { (belief, prior) ->
            hermesSession.updateGlobalPrior(belief, prior)
        }

        // Download new skills from marketplace
        update.newSkills?.forEach { skill ->
            episodicMemory.storeSkill(
                skillId = skill.skillId,
                title = skill.title,
                category = skill.category,
                content = skill.content,
                keywords = skill.keywords,
                confidence = skill.confidence
            )
        }

        // Update sector-wide patterns
        update.sectorPatterns?.forEach { pattern ->
            // Store as a "collective" episode for FTS5 searchability
            episodicMemory.storeEpisode(
                workerId = "COLLECTIVE",
                query = pattern.description,
                response = pattern.insight,
                outcome = "success",
                lessons = pattern.recommendations
            )
        }
    }
}
```

---

## 4. Implementation: Specific Code Changes

### 4.1 New File: `UnifiedMemoryBridge.kt`

**Location:** `app/src/main/java/com/msaidizi/app/agent/memory/UnifiedMemoryBridge.kt`

```kotlin
package com.msaidizi.app.agent.memory

import com.msaidizi.app.agent.*
import com.msaidizi.app.agent.hermes.HermesSessionManager
import com.msaidizi.app.memory.EpisodicMemory
import com.msaidizi.app.core.model.*
import kotlinx.coroutines.*
import timber.log.Timber

/**
 * Unified Memory Bridge — THE GLUE that connects L1↔L2↔L3.
 *
 * This class is the single point of coordination for all memory layers.
 * The Orchestrator calls this instead of managing memory layers directly.
 *
 * Flow:
 *   enrichContext() → called before intent classification
 *   postProcess()   → called after response generation
 *   consolidate()   → called periodically (idle time / heartbeat)
 */
class UnifiedMemoryBridge(
    private val conversationMemory: ConversationMemory,         // L1
    private val episodicMemory: EpisodicMemory,                 // L2
    private val hermesSession: HermesSessionManager,            // L3
    private val adaptiveLearning: AdaptiveLearningEngine,       // Learning
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "MemoryBridge"

        /** How many L2 episodes to inject into context */
        private const val L2_EPISODE_LIMIT = 5

        /** How many L2 skills to inject into context */
        private const val L2_SKILL_LIMIT = 3

        /** How many L3 Hermes skills to inject */
        private const val L3_SKILL_LIMIT = 3

        /** Consolidation interval (every N interactions) */
        private const val CONSOLIDATION_INTERVAL = 10

        /** Maximum context token budget for L2/L3 injection */
        private const val MAX_CONTEXT_TOKENS = 300
    }

    /** Interaction counter for periodic consolidation */
    private var interactionCount = 0

    // ═══════════════════════════════════════════════════════════════
    // PRE-PROCESS: Enrich context before intent classification
    // ═══════════════════════════════════════════════════════════════

    /**
     * Enrich the current conversation context with L2/L3 data.
     * Called by Orchestrator BEFORE intent classification.
     *
     * This is the key integration point that makes L2/L3 useful:
     * - L2 episodes provide historical context for similar queries
     * - L2 skills provide learned procedures
     * - L3 skills provide worker-specific learned patterns
     * - L3 profile provides behavioral signals
     */
    fun enrichContext(currentInput: String, workerId: String): EnrichedContext {
        val startTime = System.currentTimeMillis()

        // L1: Current conversation context
        val l1Context = conversationMemory.getContext()

        // L2: Search for relevant episodes (sub-10ms via FTS5)
        val relevantEpisodes = try {
            episodicMemory.search(
                query = currentInput,
                workerId = workerId,
                limit = L2_EPISODE_LIMIT
            )
        } catch (e: Throwable) {
            Timber.w(e, "L2 episode search failed")
            emptyList()
        }

        // L2: Search for relevant skills
        val relevantSkills = try {
            episodicMemory.searchSkills(
                query = currentInput,
                workerId = workerId,
                limit = L2_SKILL_LIMIT
            )
        } catch (e: Throwable) {
            Timber.w(e, "L2 skill search failed")
            emptyList()
        }

        // L3: Discover Hermes skills
        val hermesSkills = try {
            hermesSession.discoverSkills(workerId, currentInput, L3_SKILL_LIMIT)
        } catch (e: Throwable) {
            Timber.w(e, "L3 Hermes skill discovery failed")
            emptyList()
        }

        // L3: Worker profile
        val workerProfile = hermesSession.getWorkerProfile(workerId)

        // Build price context from L2 episodes
        val priceContext = buildPriceContext(relevantEpisodes, currentInput)

        // Build pattern hints from L3 profile
        val patternHints = buildPatternHints(workerProfile, l1Context)

        val elapsed = System.currentTimeMillis() - startTime
        Timber.d("MemoryBridge.enrichContext() completed in %dms: " +
            "%d episodes, %d L2 skills, %d L3 skills",
            elapsed, relevantEpisodes.size, relevantSkills.size, hermesSkills.size)

        return EnrichedContext(
            l1Context = l1Context,
            relevantEpisodes = relevantEpisodes,
            relevantSkills = relevantSkills,
            hermesSkills = hermesSkills,
            workerProfile = workerProfile,
            priceContext = priceContext,
            patternHints = patternHints,
            enrichmentTimeMs = elapsed
        )
    }

    /**
     * Build a context string from enriched data for LLM injection.
     * Compact format to fit within token budget.
     */
    fun buildEnrichedContextString(ctx: EnrichedContext): String {
        val sb = StringBuilder()

        // L2 episodes (most valuable for context)
        if (ctx.relevantEpisodes.isNotEmpty()) {
            sb.append("Historical: ")
            ctx.relevantEpisodes.take(3).forEach { ep ->
                val price = extractPrice(ep.query) ?: extractPrice(ep.response)
                val priceStr = if (price != null) " @KSh$price" else ""
                sb.append("${ep.query.take(40)}$priceStr; ")
            }
        }

        // L2/L3 skills
        val allSkills = ctx.relevantSkills + ctx.hermesSkills.map {
            SkillEntry(it.skillId, it.title, it.category, it.confidence, it.procedure.joinToString("; "))
        }
        if (allSkills.isNotEmpty()) {
            sb.append("Skills: ")
            allSkills.take(2).forEach { skill ->
                sb.append("${skill.title} (${(skill.confidence * 100).toInt()}%); ")
            }
        }

        // Price context
        ctx.priceContext?.let { pc ->
            if (pc.isPriceAnomaly) {
                sb.append("⚠️ Price anomaly: ${pc.item} avg=KSh${pc.avgHistoricalPrice.toInt()}, " +
                    "current=KSh${pc.currentPrice.toInt()}. ")
            }
        }

        // Worker behavioral hints
        ctx.patternHints?.let { hints ->
            if (hints.isNotEmpty()) {
                sb.append("Worker: ${hints.joinToString(", ")}. ")
            }
        }

        val result = sb.toString().take(MAX_CONTEXT_TOKENS * 3)  // ~3 chars per token
        return result
    }

    // ═══════════════════════════════════════════════════════════════
    // POST-PROCESS: Update all layers after interaction
    // ═══════════════════════════════════════════════════════════════

    /**
     * Post-process an interaction across all memory layers.
     * Called by Orchestrator AFTER response generation.
     */
    suspend fun postProcess(
        workerId: String,
        query: String,
        response: String,
        intentResult: IntentResult,
        transaction: Transaction?,
        outcome: String,
        enrichedContext: EnrichedContext? = null
    ) = withContext(Dispatchers.IO) {
        interactionCount++
        val startTime = System.currentTimeMillis()

        // ── L1: Conversation memory (already handled by ConversationManager) ──
        // (ConversationManager.postProcess() adds turns to ConversationMemory)

        // ── L2: Store episode ──
        try {
            val lessons = mutableListOf<String>()

            // Extract lessons from price context
            enrichedContext?.priceContext?.let { pc ->
                if (pc.isPriceAnomaly) {
                    lessons.add("Price anomaly detected: ${pc.item} at ${pc.currentPrice} " +
                        "(historical avg: ${pc.avgHistoricalPrice})")
                }
            }

            // Extract lessons from outcome
            if (outcome == "failure") {
                lessons.add("Interaction failed for intent: ${intentResult.intent.name}")
            }

            episodicMemory.storeEpisode(
                workerId = workerId,
                query = query,
                response = response,
                outcome = outcome,
                lessons = lessons.joinToString("; "),
                dialect = "sw",
                context = mapOf(
                    "intent" to intentResult.intent.name,
                    "confidence" to intentResult.confidence.toString(),
                    "item" to (intentResult.extractedData["item"] ?: ""),
                    "amount" to (intentResult.extractedData["amount"] ?: "")
                )
            )
        } catch (e: Throwable) {
            Timber.w(e, "L2 episode storage failed")
        }

        // ── L3: Update Hermes session ──
        try {
            val session = hermesSession.getSession(workerId)
            val traceId = session?.activeTraceId

            if (traceId != null) {
                hermesSession.recordTraceStep(
                    workerId = workerId,
                    traceId = traceId,
                    action = intentResult.intent.name,
                    toolUsed = "memory_bridge",
                    success = outcome == "success"
                )

                val generatedSkill = hermesSession.completeInteraction(
                    workerId = workerId,
                    traceId = traceId,
                    response = response,
                    outcome = outcome
                )

                // Store generated skill in L2 for FTS5 searchability
                if (generatedSkill != null) {
                    episodicMemory.storeSkill(
                        skillId = generatedSkill.skillId,
                        title = generatedSkill.title,
                        category = generatedSkill.category,
                        content = generatedSkill.procedure.joinToString("\n"),
                        keywords = generatedSkill.keywords,
                        confidence = generatedSkill.confidence,
                        workerId = workerId
                    )
                    Timber.i("L3→L2: Stored Hermes skill '%s' in episodic FTS5",
                        generatedSkill.title)
                }
            }
        } catch (e: Throwable) {
            Timber.w(e, "L3 Hermes update failed")
        }

        // ── L3: Update Bayesian beliefs from L1 signals ──
        try {
            val l1Signals = extractL1SignalsForL3(
                conversationMemory.getContext(), intentResult, outcome
            )
            if (l1Signals.isNotEmpty()) {
                hermesSession.updateWorkerBeliefs(workerId, l1Signals)
                Timber.d("L1→L3: Updated %d Bayesian beliefs from conversation signals",
                    l1Signals.size)
            }
        } catch (e: Throwable) {
            Timber.w(e, "L1→L3 signal extraction failed")
        }

        // ── L3: Update AdaptiveLearningEngine ──
        try {
            if (transaction != null) {
                adaptiveLearning.learnFromTransaction(transaction)
            }
        } catch (e: Throwable) {
            Timber.w(e, "AdaptiveLearning update failed")
        }

        // ── Periodic: Consolidate L2 patterns to L3 ──
        if (interactionCount % CONSOLIDATION_INTERVAL == 0) {
            scope.launch { consolidateL2toL3(workerId) }
        }

        val elapsed = System.currentTimeMillis() - startTime
        Timber.d("MemoryBridge.postProcess() completed in %dms", elapsed)
    }

    // ═══════════════════════════════════════════════════════════════
    // CONSOLIDATION: L2 patterns → L3 beliefs
    // ═══════════════════════════════════════════════════════════════

    /**
     * Consolidate L2 episodic patterns into L3 behavioral model.
     * Runs periodically (every 10 interactions or during idle time).
     *
     * This is the bridge that makes L3 learn from L2 history:
     * - Success/failure patterns → risk_aversion belief
     * - Price patterns → price_sensitivity belief
     * - Timing patterns → restock_frequency belief
     * - Topic patterns → business_domain update
     */
    private suspend fun consolidateL2toL3(workerId: String) = withContext(Dispatchers.IO) {
        try {
            Timber.d("Consolidating L2→L3 for worker %s", workerId.take(8))

            // Get recent episodes from L2
            val recentEpisodes = episodicMemory.search(
                query = "*",  // All episodes
                workerId = workerId,
                limit = 30
            )

            if (recentEpisodes.size < 5) {
                Timber.d("Not enough episodes for consolidation (%d)", recentEpisodes.size)
                return@withContext
            }

            val observations = mutableMapOf<String, Double>()

            // ── Signal 1: Success rate → general competence ──
            val successCount = recentEpisodes.count { it.outcome == "success" }
            val successRate = successCount.toDouble() / recentEpisodes.size
            observations["decision_confidence"] = successRate

            // ── Signal 2: Price consistency → price_sensitivity ──
            val priceEpisodes = recentEpisodes.filter {
                it.query.contains(Regex("\\d+"))
            }
            if (priceEpisodes.size >= 3) {
                // Low variance in prices → low price sensitivity
                val prices = priceEpisodes.mapNotNull { extractPrice(it.query) }
                if (prices.size >= 3) {
                    val cv = coefficientOfVariation(prices)
                    observations["price_sensitivity"] = (1.0 - cv).coerceIn(0.0, 1.0)
                }
            }

            // ── Signal 3: Timing patterns → preferred hours ──
            val hours = recentEpisodes.mapNotNull { ep ->
                try {
                    val ts = ep.timestamp
                    java.time.Instant.ofEpochMilli(ts)
                        .atZone(java.time.ZoneId.systemDefault())
                        .hour
                } catch (_: Throwable) { null }
            }
            if (hours.isNotEmpty()) {
                val earlyMorningCount = hours.count { it < 10 }
                observations["preferred_early_morning"] =
                    earlyMorningCount.toDouble() / hours.size
            }

            // ── Signal 4: Query complexity → decision_speed ──
            val avgQueryLength = recentEpisodes.map { it.query.length }.average()
            observations["decision_speed"] = (1.0 - (avgQueryLength / 150.0)).coerceIn(0.0, 1.0)

            // ── Signal 5: Correction frequency → risk_aversion ──
            val correctionEpisodes = recentEpisodes.filter {
                it.lessons.contains("correction", ignoreCase = true) ||
                it.query.contains(Regex("(?i)(hapana|siyo|wrong|correct)"))
            }
            observations["risk_aversion"] = correctionEpisodes.size.toDouble() /
                recentEpisodes.size.coerceAtLeast(1)

            // ── Signal 6: Topic concentration → business domain ──
            val topics = recentEpisodes.mapNotNull { ep ->
                extractTopic(ep.query)
            }.groupingBy { it }.eachCount()
            val dominantTopic = topics.maxByOrNull { it.value }
            if (dominantTopic != null) {
                hermesSession.updateWorkerProfile(workerId) { profile ->
                    profile.businessDomain = dominantTopic.key
                }
            }

            // Apply consolidated observations to L3
            hermesSession.updateWorkerBeliefs(workerId, observations)

            Timber.i("L2→L3 consolidated: %d observations from %d episodes",
                observations.size, recentEpisodes.size)

        } catch (e: Throwable) {
            Timber.w(e, "L2→L3 consolidation failed")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // MEMORY COMPRESSION & EVICTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Run memory maintenance across all layers.
     * Call during app idle time or heartbeat.
     */
    suspend fun runMaintenance() = withContext(Dispatchers.IO) {
        Timber.d("Running memory maintenance...")

        // L2: Run relevance decay (30-day half-life for episodes, 60-day for skills)
        try {
            episodicMemory.runDecay()
        } catch (e: Throwable) {
            Timber.w(e, "L2 decay failed")
        }

        // L3: Run Hermes memory consolidation
        try {
            val activeSessions = hermesSession.getActiveSessions()
            for ((workerId, _) in activeSessions) {
                hermesSession.consolidateMemory(workerId)
            }
        } catch (e: Throwable) {
            Timber.w(e, "L3 consolidation failed")
        }

        // L3: Cleanup expired sessions (7-day TTL)
        try {
            hermesSession.cleanupExpiredSessions()
        } catch (e: Throwable) {
            Timber.w(e, "L3 session cleanup failed")
        }

        // AdaptiveLearning: Background learning
        try {
            adaptiveLearning.runBackgroundLearning()
        } catch (e: Throwable) {
            Timber.w(e, "Background learning failed")
        }

        Timber.d("Memory maintenance complete")
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun buildPriceContext(
        episodes: List<Episode>,
        currentInput: String
    ): PriceContext? {
        val currentPrice = extractPrice(currentInput) ?: return null
        val item = extractItem(currentInput) ?: return null

        val historicalPrices = episodes
            .filter { it.query.contains(item, ignoreCase = true) }
            .mapNotNull { extractPrice(it.query) ?: extractPrice(it.response) }

        if (historicalPrices.isEmpty()) return null

        val avg = historicalPrices.average()
        val min = historicalPrices.min()
        val max = historicalPrices.max()

        return PriceContext(
            item = item,
            currentPrice = currentPrice,
            avgHistoricalPrice = avg,
            minHistoricalPrice = min,
            maxHistoricalPrice = max,
            isPriceAnomaly = currentPrice < min * 0.5 || currentPrice > max * 1.5,
            observationCount = historicalPrices.size
        )
    }

    private fun buildPatternHints(
        profile: WorkerProfile?,
        l1Context: ConversationContext
    ): List<String>? {
        if (profile == null) return null

        val hints = mutableListOf<String>()

        // Business domain hint
        if (profile.businessDomain.isNotBlank()) {
            hints.add("domain: ${profile.businessDomain}")
        }

        // Frequent topics
        if (profile.frequentTopics.isNotEmpty()) {
            hints.add("topics: ${profile.frequentTopics.take(3).joinToString(",")}")
        }

        // Satisfaction trend
        if (profile.satisfactionTrend.size >= 5) {
            val recentAvg = profile.satisfactionTrend.takeLast(5).average()
            if (recentAvg < 0.5) {
                hints.add("satisfaction: low (needs attention)")
            }
        }

        return if (hints.isEmpty()) null else hints
    }

    private fun extractL1SignalsForL3(
        l1Context: ConversationContext,
        intentResult: IntentResult,
        outcome: String
    ): Map<String, Double> {
        val signals = mutableMapOf<String, Double>()

        // Correction frequency → risk_aversion
        val corrections = l1Context.turns.count {
            it.intent?.intent == IntentType.CORRECTION
        }
        if (corrections > 0) {
            signals["risk_aversion"] = min(1.0, corrections * 0.2)
        }

        // Price queries → price_sensitivity
        val priceQueries = l1Context.turns.count {
            it.text.contains(Regex("(?i)(bei|price|gharama|cost)"))
        }
        if (priceQueries > 0) {
            signals["price_sensitivity"] = min(1.0, 0.5 + priceQueries * 0.15)
        }

        // Follow-up patterns → decision_speed
        val followUps = l1Context.turns.count { turn ->
            turn.speaker == "worker" &&
            listOf("na", "pia", "hizo", "zile", "ile").any {
                turn.text.lowercase().contains(it)
            }
        }
        if (followUps > 2) {
            signals["decision_speed"] = 0.8
        }

        return signals
    }

    private fun extractPrice(text: String): Double? {
        return Regex("""(\d+(?:\.\d+)?)""").findAll(text)
            .map { it.value.toDouble() }
            .firstOrNull { it in 1.0..1_000_000.0 }
    }

    private fun extractItem(text: String): String? {
        val items = listOf("nyanya", "sukari", "unga", "mchele", "mafuta",
            "nyama", "kuku", "samaki", "mayai", "viazi", "vitunguu",
            "sukuma", "maharagwe", "mahindi", "mandazi", "chapati")
        val lower = text.lowercase()
        return items.firstOrNull { lower.contains(it) }
    }

    private fun extractTopic(query: String): String? {
        val lower = query.lowercase()
        return when {
            lower.contains(Regex("(nimeuza|sold|nauza)")) -> "sales"
            lower.contains(Regex("(nimenunua|bought)")) -> "purchases"
            lower.contains(Regex("(nimetumia|spent|matumizi)")) -> "expenses"
            lower.contains(Regex("(faida|profit)")) -> "profit"
            lower.contains(Regex("(salio|balance)")) -> "balance"
            lower.contains(Regex("(bei|price|gharama)")) -> "pricing"
            lower.contains(Regex("(stock|hifadhi|inventory)")) -> "inventory"
            else -> null
        }
    }

    private fun coefficientOfVariation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        if (mean == 0.0) return 0.0
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return Math.sqrt(variance) / mean
    }

    fun getStats(): Map<String, Any> = mapOf(
        "interactionCount" to interactionCount,
        "l1_turns" to conversationMemory.turnCount(),
        "l2_stats" to try { episodicMemory.getStats() } catch (_: Throwable) { "unavailable" },
        "l3_sessions" to hermesSession.getActiveSessions().size,
        "l3_stats" to try { hermesSession.getStats() } catch (_: Throwable) { "unavailable" }
    )
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

/**
 * Enriched context assembled from all three memory layers.
 * Passed to intent classification and response generation.
 */
data class EnrichedContext(
    val l1Context: ConversationContext,
    val relevantEpisodes: List<Episode>,
    val relevantSkills: List<Skill>,
    val hermesSkills: List<LearnedSkill>,
    val workerProfile: WorkerProfile?,
    val priceContext: PriceContext?,
    val patternHints: List<String>?,
    val enrichmentTimeMs: Long
)

/**
 * Price context from L2 historical data.
 */
data class PriceContext(
    val item: String,
    val currentPrice: Double,
    val avgHistoricalPrice: Double,
    val minHistoricalPrice: Double,
    val maxHistoricalPrice: Double,
    val isPriceAnomaly: Boolean,
    val observationCount: Int
)

/**
 * Skill entry for unified skill display.
 */
data class SkillEntry(
    val skillId: String,
    val title: String,
    val category: String,
    val confidence: Double,
    val content: String
)
```

### 4.2 Changes to Orchestrator.kt

```diff
// In Orchestrator.kt — add UnifiedMemoryBridge dependency

class Orchestrator(
    // ... existing dependencies ...
+   private val memoryBridge: UnifiedMemoryBridge,
    // ...
) {

    suspend fun processInput(text: String, language: String = "sw"): AgentResponse {
        // ... existing code ...

        // Step 0.5: Memory Bridge — enrich context from L2/L3
+       val enrichedContext = memoryBridge.enrichContext(enhancedText, workerId ?: "anonymous")
+       val enrichedContextStr = memoryBridge.buildEnrichedContextString(enrichedContext)
+       
+       // Inject enriched context into intent classification
+       val enhancedTextWithContext = if (enrichedContextStr.isNotBlank()) {
+           "$enhancedText\n[Context: $enrichedContextStr]"
+       } else {
+           enhancedText
+       }

        // Step 1: Classify intent (now with L2/L3 context)
-       var intentResult = intentRouter.classify(enhancedText)
+       var intentResult = intentRouter.classify(enhancedTextWithContext)

        // ... Steps 2-8 (existing code) ...

        // Step 8b: Memory Bridge — post-process across all layers
+       memoryBridge.postProcess(
+           workerId = workerId ?: "anonymous",
+           query = enhancedText,
+           response = finalResponse.text,
+           intentResult = intentResult,
+           transaction = transactionHandler.lastTransaction,
+           outcome = if (finalResponse.type != ResponseType.ERROR) "success" else "failure",
+           enrichedContext = enrichedContext
+       )

        return finalResponse
    }
}
```

### 4.3 Changes to ConversationManager.kt

```diff
// In ConversationManager.kt — wire Hermes trace into conversation flow

class ConversationManager(
    // ... existing dependencies ...
) {
    // Start Hermes trace before processing
    fun startHermesTrace(text: String): String? {
        val workerId = currentWorkerId ?: return null
        val traceId = hermesSession?.startTrace(workerId, text)
        hermesTraceId = traceId
        return traceId
    }

    // Complete Hermes trace after processing (already exists but not called)
    // This needs to be called from Orchestrator.processInput()
}
```

### 4.4 Changes to HermesSessionManager.kt

```diff
// In HermesSessionManager.kt — add methods for UnifiedMemoryBridge

+   /**
+    * Update worker Bayesian beliefs from external observations.
+    * Called by UnifiedMemoryBridge with consolidated signals.
+    */
+   fun updateWorkerBeliefs(workerId: String, observations: Map<String, Double>) {
+       val profile = profiles[workerId] ?: return
+       
+       // Update skill affinities based on observations
+       observations.forEach { (key, value) ->
+           when (key) {
+               "price_sensitivity" -> {
+                   profile.skillAffinities["pricing"] = 
+                       (profile.skillAffinities["pricing"] ?: 0.5) * 0.8 + value * 0.2
+               }
+               "risk_aversion" -> {
+                   profile.skillAffinities["savings"] = 
+                       (profile.skillAffinities["savings"] ?: 0.5) * 0.8 + value * 0.2
+               }
+               "decision_speed" -> {
+                   // Store as metadata for context generation
+                   profile.frequentTopics = profile.frequentTopics + "fast_decisions"
+               }
+           }
+       }
+       
+       profile.lastActive = System.currentTimeMillis()
+       Timber.d("Updated %d beliefs for worker %s", observations.size, workerId.take(8))
+   }
+
+   /**
+    * Update worker profile with a transform function.
+    */
+   fun updateWorkerProfile(workerId: String, transform: (WorkerProfile) -> Unit) {
+       val profile = profiles[workerId] ?: return
+       transform(profile)
+       profile.lastActive = System.currentTimeMillis()
+   }
+
+   /**
+    * Update a global prior from collective intelligence.
+    * Called by FederatedMemorySync after backend aggregation.
+    */
+   fun updateGlobalPrior(belief: String, prior: Double) {
+       // Store as a global skill affinity adjustment
+       // This shifts the baseline for all workers
+       Timber.d("Global prior updated: %s = %.3f", belief, prior)
+   }
```

### 4.5 Changes to EpisodicMemory.kt

```diff
// In EpisodicMemory.kt — add storeSkill() method (already exists but verify)

// The existing storeSkill() and searchSkills() methods are already present.
// No changes needed — they're ready for the bridge to use.

// Add one convenience method:
+   /**
+    * Get recent episodes for a worker (for consolidation).
+    */
+   fun getRecentEpisodes(workerId: String, limit: Int = 30): List<Episode> {
+       return search(query = "*", workerId = workerId, limit = limit)
+   }
```

### 4.6 DI Module Changes (AIModule.kt)

```diff
// In AIModule.kt — provide UnifiedMemoryBridge

+   @Provides
+   @Singleton
+   fun provideUnifiedMemoryBridge(
+       conversationMemory: ConversationMemory,
+       episodicMemory: EpisodicMemory,
+       hermesSession: HermesSessionManager,
+       adaptiveLearning: AdaptiveLearningEngine
+   ): UnifiedMemoryBridge {
+       return UnifiedMemoryBridge(
+           conversationMemory = conversationMemory,
+           episodicMemory = episodicMemory,
+           hermesSession = hermesSession,
+           adaptiveLearning = adaptiveLearning
+       )
+   }
```

---

## 5. Memory Lifecycle: Birth to Death of a Memory

```
INTERACTION ARRIVES
       │
       ▼
┌──────────────────┐
│ L1: Turn Created │ ── Lifetime: 30 minutes, 10 turns max
│ (ConversationMem)│ ── Eviction: FIFO when > 10 turns
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ L2: Episode Born │ ── Lifetime: permanent (until relevance < 0.01)
│ (SQLite FTS5)    │ ── Decay: 30-day half-life, exponential
│                  │ ── Eviction: when > 10K episodes, remove bottom 10%
│                  │ ── Access: boosted on retrieval (BM25 * relevance)
└────────┬─────────┘
         │
         ├── If complex (3+ steps) + success → SKILL GENERATED
         │   └── Stored in L2 skills table, FTS5 indexed
         │       ── Lifetime: permanent (until confidence < 0.1)
         │       ── Decay: 60-day half-life
         │
         ▼
┌──────────────────┐
│ L3: Beliefs      │ ── Lifetime: permanent (worker lifetime)
│ Updated          │ ── Decay: 60-day half-life for staleness
│ (Bayesian)       │ ── Confidence: grows with each observation
│                  │ ── Sync: federated learning every 24h
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ L3: Skills       │ ── Lifetime: permanent (until unused 90 days)
│ (HermesLearned)  │ ── Confidence: 0.5 + successRate * 0.5
│                  │ ── Eviction: confidence < 0.1
└──────────────────┘

CONSOLIDATION CYCLE (every 10 interactions):
  L2 episodes → extract patterns → L3 beliefs
  L2 success patterns → L3 skill generation
  L1 correction signals → L3 behavioral updates
```

---

## 6. Performance Budget

| Operation | Target | Method |
|-----------|--------|--------|
| L2 FTS5 search | < 10ms | SQLite FTS5 BM25, indexed |
| L2 skill search | < 10ms | SQLite FTS5, indexed |
| L3 skill discovery | < 5ms | In-memory ConcurrentHashMap |
| Context enrichment | < 20ms | L2 + L3 parallel |
| Episode storage | < 5ms | SQLite insert with FTS trigger |
| Bayesian update | < 1ms | In-memory arithmetic |
| Consolidation (L2→L3) | < 100ms | Runs async, non-blocking |
| Full maintenance cycle | < 500ms | Runs during idle time |

---

## 7. Summary: What Changes

| File | Change Type | Description |
|------|-------------|-------------|
| **NEW** `UnifiedMemoryBridge.kt` | New file | The glue connecting L1↔L2↔L3 |
| `Orchestrator.kt` | Modify | Add memoryBridge dependency, call enrichContext() + postProcess() |
| `HermesSessionManager.kt` | Modify | Add updateWorkerBeliefs(), updateWorkerProfile(), updateGlobalPrior() |
| `ConversationManager.kt` | Modify | Wire Hermes traces into conversation flow |
| `EpisodicMemory.kt` | Minor | Add getRecentEpisodes() convenience method |
| `AIModule.kt` (DI) | Modify | Provide UnifiedMemoryBridge |
| **NEW** `FederatedMemorySync.kt` | New file | Backend sync with differential privacy |

The core insight: **the code for L1, L2, and L3 already exists and is well-built. What's missing is the BRIDGE — a single class that orchestrates the flow between them.** The UnifiedMemoryBridge is that bridge. It doesn't replace any existing code; it wires the existing pieces together into one coherent system.
