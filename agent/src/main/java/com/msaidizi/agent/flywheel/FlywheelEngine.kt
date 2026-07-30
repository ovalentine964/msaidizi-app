package com.msaidizi.agent.flywheel

import com.msaidizi.core.database.KnowledgeDao
import com.msaidizi.core.util.DateTimeUtil
import com.msaidizi.core.model.KnowledgeEntity
import com.msaidizi.agent.harness.UserIntent
import com.msaidizi.agent.tools.ToolResult
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FlywheelEngine — 6 Interlocking Compound Growth Loops.
 *
 * Each loop runs independently with its own metrics. Growth stages
 * auto-transition based on worker count:
 *   Seed (0-1K) → Sprout (1K-10K) → Growth (10K-100K) → Scale (100K-1M) → Compound (1M+)
 *
 * Loop 1: Vocabulary Loop — Learns worker's unique terms, product names, slang.
 *          Improves intent classification and STT accuracy.
 * Loop 2: Business Pattern Loop — Learns daily/weekly/monthly rhythms.
 *          Improves cash flow prediction and restock advice.
 * Loop 3: Market Intelligence Loop — Aggregates anonymized price signals.
 *          Improves pricing advice (Soko Pulse).
 * Loop 4: Credit Loop — Tracks payment patterns and repayment behavior.
 *          Improves Alama Score accuracy for financial partners.
 * Loop 5: Model Evolution Loop — Collects high-quality training data.
 *          Improves the LLM through post-training and federated updates.
 * Loop 6: Network Effect Loop — Each new worker makes intelligence better for all.
 *          Amplifies all other loops by up to 1.5×.
 *
 * Architecture reference: grand_synthesis_architecture.md §2.2
 */
@Singleton
class FlywheelEngine @Inject constructor(
    private val knowledgeDao: KnowledgeDao,
    private val gson: Gson
) {

    // ─────────────────────────────────────────────────────────
    // Growth Stages
    // ─────────────────────────────────────────────────────────

    enum class GrowthStage(val label: String, val minWorkers: Int, val maxWorkers: Int) {
        SEED("seed", 0, 1_000),
        SPROUT("sprout", 1_000, 10_000),
        GROWTH("growth", 10_000, 100_000),
        SCALE("scale", 100_000, 1_000_000),
        COMPOUND("compound", 1_000_000, Int.MAX_VALUE);

        companion object {
            fun fromWorkerCount(count: Int): GrowthStage =
                entries.firstOrNull { count in it.minWorkers until it.maxWorkers } ?: SEED
        }
    }

    // ─────────────────────────────────────────────────────────
    // Loop Definitions
    // ─────────────────────────────────────────────────────────

    enum class Loop(val label: String, val minStage: GrowthStage) {
        VOCABULARY("Vocabulary", GrowthStage.SEED),
        BUSINESS_PATTERN("Business Pattern", GrowthStage.SEED),
        MARKET_INTELLIGENCE("Market Intelligence", GrowthStage.SPROUT),
        CREDIT("Credit", GrowthStage.GROWTH),
        MODEL_EVOLUTION("Model Evolution", GrowthStage.SCALE),
        NETWORK_EFFECT("Network Effect", GrowthStage.COMPOUND);

        fun isActiveAt(stage: GrowthStage): Boolean =
            stage.ordinal >= minStage.ordinal
    }

    data class LoopMetrics(
        val loop: Loop,
        var eventsProcessed: Int = 0,
        var improvementsGenerated: Int = 0,
        var revenueAttributed: Double = 0.0,
        var workersAffected: Int = 0,
        var lastEventTime: Long = 0L,
        var velocity: Double = 0.0, // events per hour (rolling)
        private val recentTimestamps: MutableList<Long> = mutableListOf()
    ) {
        fun recordEvent() {
            val now = System.currentTimeMillis()
            eventsProcessed++
            lastEventTime = now
            recentTimestamps.add(now)
            // Keep last hour for velocity
            val cutoff = now - 3_600_000L
            recentTimestamps.removeAll { it < cutoff }
            velocity = recentTimestamps.size.toDouble()
        }
    }

    // ─────────────────────────────────────────────────────────
    // Main Entry Point
    // ─────────────────────────────────────────────────────────

    /**
     * Process an interaction for learning signals across all 6 loops.
     * Runs after every successful interaction.
     */
    suspend fun processInteraction(
        input: String,
        response: String,
        intent: UserIntent,
        toolResults: List<ToolResult>,
        workerCount: Int = 0
    ) {
        val stage = GrowthStage.fromWorkerCount(workerCount)
        val metrics = loadMetrics()

        // Loop 1: Vocabulary — learn new words, slang, product names
        if (Loop.VOCABULARY.isActiveAt(stage)) {
            learnVocabulary(input, metrics.getOrPut(Loop.VOCABULARY) { LoopMetrics(Loop.VOCABULARY) })
        }

        // Loop 2: Business Pattern — learn rhythms from transactions
        if (Loop.BUSINESS_PATTERN.isActiveAt(stage)) {
            learnBusinessPattern(intent, metrics.getOrPut(Loop.BUSINESS_PATTERN) { LoopMetrics(Loop.BUSINESS_PATTERN) })
        }

        // Loop 3: Market Intelligence — track price signals
        if (Loop.MARKET_INTELLIGENCE.isActiveAt(stage)) {
            learnMarketIntelligence(input, intent, toolResults, metrics.getOrPut(Loop.MARKET_INTELLIGENCE) { LoopMetrics(Loop.MARKET_INTELLIGENCE) })
        }

        // Loop 4: Credit — track payment and repayment patterns
        if (Loop.CREDIT.isActiveAt(stage)) {
            learnCreditPatterns(intent, toolResults, metrics.getOrPut(Loop.CREDIT) { LoopMetrics(Loop.CREDIT) })
        }

        // Loop 5: Model Evolution — collect high-quality training signals
        if (Loop.MODEL_EVOLUTION.isActiveAt(stage)) {
            collectTrainingData(input, response, intent, toolResults, metrics.getOrPut(Loop.MODEL_EVOLUTION) { LoopMetrics(Loop.MODEL_EVOLUTION) })
        }

        // Loop 6: Network Effect — aggregate signals for collective intelligence
        if (Loop.NETWORK_EFFECT.isActiveAt(stage)) {
            learnNetworkEffect(input, intent, workerCount, metrics.getOrPut(Loop.NETWORK_EFFECT) { LoopMetrics(Loop.NETWORK_EFFECT) })
        }

        // Reinforce successful patterns across all active loops
        for (result in toolResults) {
            if (result.success) {
                reinforcePattern(intent.type, result)
            }
            // Track per-tool reliability for flywheel read-back
            try {
                recordToolReliability(result.toolName, result.success)
            } catch (e: Exception) {
                Timber.w(e, "Failed to track tool reliability")
            }
        }

        // Track intent patterns
        trackIntentPattern(input, intent)

        // Persist metrics
        saveMetrics(metrics)

        // Log compound velocity
        val compoundVelocity = computeCompoundVelocity(metrics, stage)
        Timber.d("Flywheel [%s]: compound velocity=%.1f events/hr, workers=%d",
            stage.label, compoundVelocity, workerCount)
    }

    // ─────────────────────────────────────────────────────────
    // Loop 1: Vocabulary Loop
    // ─────────────────────────────────────────────────────────

    private suspend fun learnVocabulary(input: String, metrics: LoopMetrics) {
        val words = input.lowercase().split(Regex("\\s+")).filter { it.length > 3 }
        val known = knowledgeDao.getByCategory("vocab").first().map { it.key.lowercase() }.toSet()

        var newWords = 0
        for (word in words) {
            if (word !in known && !word.matches(Regex(".*\\d.*"))) {
                knowledgeDao.insert(
                    KnowledgeEntity(
                        category = "vocab",
                        key = word,
                        value = gson.toJson(mapOf(
                            "source" to "user_input",
                            "timestamp" to System.currentTimeMillis()
                        )),
                        confidence = 0.2f
                    )
                )
                newWords++
            }
        }

        if (newWords > 0) {
            metrics.recordEvent()
            metrics.improvementsGenerated += newWords
        }
    }

    // ─────────────────────────────────────────────────────────
    // Loop 2: Business Pattern Loop
    // ─────────────────────────────────────────────────────────

    private suspend fun learnBusinessPattern(intent: UserIntent, metrics: LoopMetrics) {
        if (intent.type !in listOf(
                com.msaidizi.agent.harness.IntentType.RECORD_SALE,
                com.msaidizi.agent.harness.IntentType.RECORD_EXPENSE
            )
        ) return

        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val weekOfMonth = cal.get(java.util.Calendar.WEEK_OF_MONTH)

        // Hourly pattern
        persistPattern("business_pattern", "hourly_pattern_$hour",
            mapOf("hour" to hour, "type" to intent.type.name))

        // Daily pattern (day of week)
        persistPattern("business_pattern", "daily_pattern_$dayOfWeek",
            mapOf("dayOfWeek" to dayOfWeek, "type" to intent.type.name))

        // Weekly pattern (week of month)
        persistPattern("business_pattern", "weekly_pattern_$weekOfMonth",
            mapOf("weekOfMonth" to weekOfMonth, "type" to intent.type.name))

        // Monthly rhythm
        val dayOfMonth = cal.get(java.util.Calendar.DAY_OF_MONTH)
        if (dayOfMonth <= 7) {
            persistPattern("business_pattern", "monthly_start",
                mapOf("phase" to "month_start", "type" to intent.type.name))
        } else if (dayOfMonth >= 25) {
            persistPattern("business_pattern", "monthly_end",
                mapOf("phase" to "month_end", "type" to intent.type.name))
        }

        metrics.recordEvent()
    }

    // ─────────────────────────────────────────────────────────
    // Loop 3: Market Intelligence Loop
    // ─────────────────────────────────────────────────────────

    private suspend fun learnMarketIntelligence(
        input: String,
        intent: UserIntent,
        toolResults: List<ToolResult>,
        metrics: LoopMetrics
    ) {
        val lower = input.lowercase()

        // Detect price-related signals
        val priceSignals = listOf("price", "bei", "gharama", "cost", "expensive", "cheap", "discount")
        if (priceSignals.any { lower.contains(it) }) {
            // Extract price mentions for anonymized aggregation
            val priceRegex = Regex("""(\d[\d,]*\.?\d*)""")
            val prices = priceRegex.findAll(input).map { it.value.replace(",", "") }.toList()

            if (prices.isNotEmpty()) {
                persistPattern("market_intelligence", "price_signal_${System.currentTimeMillis()}",
                    mapOf(
                        "prices" to prices.joinToString(","),
                        "intent" to intent.type.name,
                        "context" to input.take(100)
                    ))
                metrics.recordEvent()
            }
        }

        // Detect competitor/market signals
        val marketSignals = listOf("competitor", "market", "demand", "supply", "stock", "inventory")
        if (marketSignals.any { lower.contains(it) }) {
            persistPattern("market_intelligence", "market_signal",
                mapOf("context" to input.take(200), "timestamp" to System.currentTimeMillis()))
            metrics.recordEvent()
        }
    }

    // ─────────────────────────────────────────────────────────
    // Loop 4: Credit Loop
    // ─────────────────────────────────────────────────────────

    private suspend fun learnCreditPatterns(
        intent: UserIntent,
        toolResults: List<ToolResult>,
        metrics: LoopMetrics
    ) {
        // Track payment behavior from tool results
        for (result in toolResults) {
            if (result.success) {
                val resultData = result.data?.toString() ?: ""

                // Detect payment/repayment signals
                if (intent.type == com.msaidizi.agent.harness.IntentType.RECORD_SALE) {
                    val isPaid = resultData.lowercase().let {
                        it.contains("paid") || it.contains("cash") || it.contains("complete")
                    }
                    persistPattern("credit", "payment_pattern",
                        mapOf(
                            "paid" to isPaid,
                            "intent" to intent.type.name,
                            "timestamp" to System.currentTimeMillis()
                        ))
                    metrics.recordEvent()
                }

                // Track debt/credit signals
                if (intent.type == com.msaidizi.agent.harness.IntentType.RECORD_EXPENSE) {
                    persistPattern("credit", "expense_pattern",
                        mapOf(
                            "type" to intent.type.name,
                            "timestamp" to System.currentTimeMillis()
                        ))
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // Loop 5: Model Evolution Loop
    // ─────────────────────────────────────────────────────────

    private suspend fun collectTrainingData(
        input: String,
        response: String,
        intent: UserIntent,
        toolResults: List<ToolResult>,
        metrics: LoopMetrics
    ) {
        // Only collect high-quality training examples
        val allToolsSucceeded = toolResults.isNotEmpty() && toolResults.all { it.success }
        if (!allToolsSucceeded) return

        // Store as a training pair (input → intent + successful response)
        val trainingExample = mapOf(
            "input" to input.take(500),
            "intent" to intent.type.name,
            "response_summary" to response.take(200),
            "tool_count" to toolResults.size,
            "timestamp" to System.currentTimeMillis()
        )

        persistPattern("model_evolution", "training_example_${System.currentTimeMillis()}",
            trainingExample)

        // Track confidence signals for intent classification
        val key = "intent_confidence_${intent.type.name.lowercase()}"
        val existing = knowledgeDao.getEntry("model_evolution", key)
        if (existing != null) {
            knowledgeDao.update(existing.copy(
                confidence = (existing.confidence + 0.02f).coerceAtMost(1.0f),
                usageCount = existing.usageCount + 1,
                updatedAt = System.currentTimeMillis()
            ))
        } else {
            knowledgeDao.insert(KnowledgeEntity(
                category = "model_evolution",
                key = key,
                value = gson.toJson(mapOf("intent" to intent.type.name, "sample_count" to 1)),
                confidence = 0.5f,
                usageCount = 1
            ))
        }

        metrics.recordEvent()
        metrics.improvementsGenerated++
    }

    // ─────────────────────────────────────────────────────────
    // Loop 6: Network Effect Loop
    // ─────────────────────────────────────────────────────────

    private suspend fun learnNetworkEffect(
        input: String,
        intent: UserIntent,
        workerCount: Int,
        metrics: LoopMetrics
    ) {
        // Track collective intelligence signals
        val lower = input.lowercase()
        val referralSignals = listOf("referral", "referred", "invited", "joined", "mtaani", "watu")
        val isReferral = referralSignals.any { lower.contains(it) }

        if (isReferral) {
            persistPattern("network_effect", "referral_signal",
                mapOf("context" to input.take(100), "workerCount" to workerCount))
            metrics.recordEvent()
            metrics.workersAffected += workerCount
        }

        // Track collective vocabulary (words that appear across multiple workers)
        // This is aggregated server-side; locally we just flag contribution
        persistPattern("network_effect", "collective_contribution",
            mapOf(
                "intent" to intent.type.name,
                "workerCount" to workerCount,
                "timestamp" to System.currentTimeMillis()
            ))

        // Network effect amplification factor
        val amplificationKey = "network_amplification"
        val existing = knowledgeDao.getEntry("network_effect", amplificationKey)
        val newFactor = computeNetworkAmplification(workerCount)
        if (existing != null) {
            knowledgeDao.update(existing.copy(
                confidence = newFactor,
                usageCount = existing.usageCount + 1,
                updatedAt = System.currentTimeMillis()
            ))
        } else {
            knowledgeDao.insert(KnowledgeEntity(
                category = "network_effect",
                key = amplificationKey,
                value = gson.toJson(mapOf("factor" to newFactor, "workers" to workerCount)),
                confidence = newFactor,
                usageCount = 1
            ))
        }
    }

    /**
     * Compute network amplification factor.
     * More workers → stronger network effects → up to 1.5× amplification.
     */
    private fun computeNetworkAmplification(workerCount: Int): Float {
        return when {
            workerCount >= 1_000_000 -> 1.5f
            workerCount >= 100_000 -> 1.4f
            workerCount >= 10_000 -> 1.3f
            workerCount >= 1_000 -> 1.2f
            workerCount >= 100 -> 1.1f
            else -> 1.0f
        }
    }

    // ─────────────────────────────────────────────────────────
    // Shared Helpers
    // ─────────────────────────────────────────────────────────

    private suspend fun reinforcePattern(
        intentType: com.msaidizi.agent.harness.IntentType,
        result: ToolResult
    ) {
        val key = "pattern_${intentType.name.lowercase()}"
        val existing = knowledgeDao.getEntry("business_pattern", key)

        if (existing != null) {
            knowledgeDao.update(
                existing.copy(
                    confidence = (existing.confidence + 0.05f).coerceAtMost(1.0f),
                    usageCount = existing.usageCount + 1,
                    updatedAt = System.currentTimeMillis()
                )
            )
        } else {
            knowledgeDao.insert(
                KnowledgeEntity(
                    category = "business_pattern",
                    key = key,
                    value = gson.toJson(mapOf(
                        "intent" to intentType.name,
                        "lastSuccess" to DateTimeUtil.today()
                    )),
                    confidence = 0.5f,
                    usageCount = 1
                )
            )
        }
    }

    private suspend fun trackIntentPattern(input: String, intent: UserIntent) {
        val key = "intent_${intent.type.name.lowercase()}_${input.take(30).replace(" ", "_")}"
        val existing = knowledgeDao.getEntry("intent_pattern", key)

        if (existing != null) {
            knowledgeDao.update(
                existing.copy(
                    usageCount = existing.usageCount + 1,
                    confidence = (existing.confidence + 0.1f).coerceAtMost(1.0f),
                    updatedAt = System.currentTimeMillis()
                )
            )
        } else {
            knowledgeDao.insert(
                KnowledgeEntity(
                    category = "intent_pattern",
                    key = key,
                    value = gson.toJson(mapOf(
                        "input" to input.take(100),
                        "intent" to intent.type.name
                    )),
                    confidence = 0.3f,
                    usageCount = 1
                )
            )
        }
    }

    private suspend fun persistPattern(
        category: String,
        key: String,
        data: Map<String, Any>
    ) {
        val existing = knowledgeDao.getEntry(category, key)
        if (existing != null) {
            knowledgeDao.update(existing.copy(
                usageCount = existing.usageCount + 1,
                confidence = (existing.confidence + 0.05f).coerceAtMost(1.0f),
                updatedAt = System.currentTimeMillis()
            ))
        } else {
            knowledgeDao.insert(KnowledgeEntity(
                category = category,
                key = key,
                value = gson.toJson(data),
                confidence = 0.4f,
                usageCount = 1
            ))
        }
    }

    // ─────────────────────────────────────────────────────────
    // Metrics Persistence
    // ─────────────────────────────────────────────────────────

    private suspend fun loadMetrics(): MutableMap<Loop, LoopMetrics> {
        val metrics = mutableMapOf<Loop, LoopMetrics>()
        for (loop in Loop.entries) {
            val key = "flywheel_metrics_${loop.name.lowercase()}"
            val existing = knowledgeDao.getEntry("flywheel_metrics", key)
            if (existing != null) {
                try {
                    val data = gson.fromJson(existing.value, Map::class.java) as Map<*, *>
                    metrics[loop] = LoopMetrics(
                        loop = loop,
                        eventsProcessed = (data["eventsProcessed"] as? Number)?.toInt() ?: 0,
                        improvementsGenerated = (data["improvementsGenerated"] as? Number)?.toInt() ?: 0,
                        revenueAttributed = (data["revenueAttributed"] as? Number)?.toDouble() ?: 0.0,
                        workersAffected = (data["workersAffected"] as? Number)?.toInt() ?: 0,
                        velocity = (data["velocity"] as? Number)?.toDouble() ?: 0.0
                    )
                } catch (e: Exception) {
                    metrics[loop] = LoopMetrics(loop = loop)
                }
            } else {
                metrics[loop] = LoopMetrics(loop = loop)
            }
        }
        return metrics
    }

    private suspend fun saveMetrics(metrics: Map<Loop, LoopMetrics>) {
        for ((loop, m) in metrics) {
            val key = "flywheel_metrics_${loop.name.lowercase()}"
            val data = mapOf(
                "eventsProcessed" to m.eventsProcessed,
                "improvementsGenerated" to m.improvementsGenerated,
                "revenueAttributed" to m.revenueAttributed,
                "workersAffected" to m.workersAffected,
                "velocity" to m.velocity,
                "lastEventTime" to m.lastEventTime
            )
            val existing = knowledgeDao.getEntry("flywheel_metrics", key)
            if (existing != null) {
                knowledgeDao.update(existing.copy(
                    value = gson.toJson(data),
                    updatedAt = System.currentTimeMillis()
                ))
            } else {
                knowledgeDao.insert(KnowledgeEntity(
                    category = "flywheel_metrics",
                    key = key,
                    value = gson.toJson(data),
                    confidence = 1.0f,
                    usageCount = 0
                ))
            }
        }
    }

    private fun computeCompoundVelocity(
        metrics: Map<Loop, LoopMetrics>,
        stage: GrowthStage
    ): Double {
        var total = 0.0
        for (loop in Loop.entries) {
            if (loop.isActiveAt(stage)) {
                total += metrics[loop]?.velocity ?: 0.0
            }
        }
        // Network effect amplification
        if (Loop.NETWORK_EFFECT.isActiveAt(stage)) {
            val networkVelocity = metrics[Loop.NETWORK_EFFECT]?.velocity ?: 0.0
            if (networkVelocity > 0) {
                total *= 1.0 + minOf(networkVelocity / 10.0, 0.5) // Up to 1.5×
            }
        }
        return total
    }

    // ─────────────────────────────────────────────────────────
    // Tool Reliability Tracking
    // ─────────────────────────────────────────────────────────

    /**
     * Record a tool execution result for reliability tracking.
     * Called after every tool execution to build per-tool success rates.
     */
    suspend fun recordToolReliability(toolName: String, success: Boolean) {
        val key = "tool_reliability_${toolName}"
        val existing = knowledgeDao.getEntry("tool_reliability", key)
        if (existing != null) {
            val data: MutableMap<String, Any> = try {
                @Suppress("UNCHECKED_CAST")
                gson.fromJson(existing.value, MutableMap::class.java) as MutableMap<String, Any>
            } catch (e: Exception) {
                mutableMapOf<String, Any>("total" to 0, "successes" to 0)
            }
            val total = ((data["total"] as? Number)?.toInt() ?: 0) + 1
            val successes = ((data["successes"] as? Number)?.toInt() ?: 0) + if (success) 1 else 0
            val reliability = successes.toFloat() / total.coerceAtLeast(1)
            data["total"] = total
            data["successes"] = successes
            data["reliability"] = reliability
            knowledgeDao.update(existing.copy(
                value = gson.toJson(data),
                confidence = reliability,
                usageCount = total,
                updatedAt = System.currentTimeMillis()
            ))
        } else {
            knowledgeDao.insert(KnowledgeEntity(
                category = "tool_reliability",
                key = key,
                value = gson.toJson(mapOf(
                    "tool" to toolName,
                    "total" to 1,
                    "successes" to if (success) 1 else 0,
                    "reliability" to if (success) 1.0f else 0.0f
                )),
                confidence = if (success) 1.0f else 0.0f,
                usageCount = 1
            ))
        }
    }

    /**
     * Get tool reliability scores — maps tool name to success rate (0.0-1.0).
     * Used by ToolRegistry to prefer reliable tools.
     */
    suspend fun getToolReliability(): Map<String, Float> {
        return try {
            knowledgeDao.getByCategory("tool_reliability").first()
                .associate { entry ->
                    val toolName = entry.key.removePrefix("tool_reliability_")
                    toolName to entry.confidence
                }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read tool reliability")
            emptyMap()
        }
    }

    // ─────────────────────────────────────────────────────────
    // Read-back Methods
    // ─────────────────────────────────────────────────────────

    /**
     * Get learned vocabulary — words the worker has used that aren't in the base dictionary.
     */
    suspend fun getLearnedVocabulary(): Map<String, Float> {
        return try {
            knowledgeDao.getByCategory("vocab").first()
                .sortedByDescending { it.usageCount }
                .associate { it.key to it.confidence }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read learned vocabulary")
            emptyMap()
        }
    }

    /**
     * Get learned business patterns — hourly, daily, weekly, monthly rhythms.
     */
    suspend fun getLearnedPatterns(): List<LearnedPattern> {
        return try {
            knowledgeDao.getByCategory("business_pattern").first()
                .sortedByDescending { it.confidence }
                .map { entry ->
                    val data = try {
                        gson.fromJson(entry.value, Map::class.java) as? Map<*, *> ?: emptyMap<String, Any>()
                    } catch (e: Exception) {
                        emptyMap<String, Any>()
                    }
                    LearnedPattern(
                        key = entry.key,
                        data = data.mapKeys { it.key.toString() }.mapValues { it.value?.toString() ?: "" },
                        confidence = entry.confidence,
                        usageCount = entry.usageCount
                    )
                }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read learned patterns")
            emptyList()
        }
    }

    /**
     * Get advice confidence — which advice/intent patterns have worked before.
     */
    suspend fun getAdviceConfidence(): Map<String, Float> {
        return try {
            knowledgeDao.getByCategory("intent_pattern").first()
                .groupBy { entry ->
                    entry.key.removePrefix("intent_").substringBefore("_")
                }
                .mapValues { (_, entries) ->
                    entries.maxOf { it.confidence }
                }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read advice confidence")
            emptyMap()
        }
    }

    /**
     * Get vocabulary words as a flat set for quick lookup.
     */
    suspend fun getVocabularyWords(): Set<String> {
        return try {
            knowledgeDao.getByCategory("vocab").first()
                .map { it.key.lowercase() }
                .toSet()
        } catch (e: Exception) {
            Timber.w(e, "Failed to read vocabulary words")
            emptySet()
        }
    }

    /**
     * Get hourly business activity pattern.
     */
    suspend fun getHourlyPatterns(): Map<Int, Float> {
        return try {
            knowledgeDao.getByCategory("business_pattern").first()
                .filter { it.key.startsWith("hourly_pattern_") }
                .associate { entry ->
                    val hour = entry.key.removePrefix("hourly_pattern_").toIntOrNull() ?: 0
                    hour to entry.confidence
                }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read hourly patterns")
            emptyMap()
        }
    }

    /**
     * Get market intelligence signals — anonymized price and demand data.
     */
    suspend fun getMarketIntelligence(): List<LearnedPattern> {
        return try {
            knowledgeDao.getByCategory("market_intelligence").first()
                .sortedByDescending { it.updatedAt }
                .map { entry ->
                    val data = try {
                        gson.fromJson(entry.value, Map::class.java) as? Map<*, *> ?: emptyMap<String, Any>()
                    } catch (e: Exception) {
                        emptyMap<String, Any>()
                    }
                    LearnedPattern(
                        key = entry.key,
                        data = data.mapKeys { it.key.toString() }.mapValues { it.value?.toString() ?: "" },
                        confidence = entry.confidence,
                        usageCount = entry.usageCount
                    )
                }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read market intelligence")
            emptyList()
        }
    }

    /**
     * Get credit patterns — payment behavior and Alama Score signals.
     */
    suspend fun getCreditPatterns(): List<LearnedPattern> {
        return try {
            knowledgeDao.getByCategory("credit").first()
                .sortedByDescending { it.confidence }
                .map { entry ->
                    val data = try {
                        gson.fromJson(entry.value, Map::class.java) as? Map<*, *> ?: emptyMap<String, Any>()
                    } catch (e: Exception) {
                        emptyMap<String, Any>()
                    }
                    LearnedPattern(
                        key = entry.key,
                        data = data.mapKeys { it.key.toString() }.mapValues { it.value?.toString() ?: "" },
                        confidence = entry.confidence,
                        usageCount = entry.usageCount
                    )
                }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read credit patterns")
            emptyList()
        }
    }

    /**
     * Get model evolution metrics — training data quality and intent confidence.
     */
    suspend fun getModelEvolutionMetrics(): Map<String, Float> {
        return try {
            knowledgeDao.getByCategory("model_evolution").first()
                .associate { it.key to it.confidence }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read model evolution metrics")
            emptyMap()
        }
    }

    /**
     * Get network effect metrics — amplification factor and collective signals.
     */
    suspend fun getNetworkEffectMetrics(): Map<String, Any> {
        return try {
            val entries = knowledgeDao.getByCategory("network_effect").first()
            val amplification = entries.find { it.key == "network_amplification" }
            mapOf(
                "amplification_factor" to (amplification?.confidence ?: 1.0f),
                "total_signals" to entries.sumOf { it.usageCount },
                "referral_signals" to entries.count { it.key == "referral_signal" }
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to read network effect metrics")
            emptyMap()
        }
    }

    /**
     * Get full flywheel status — all loops with their metrics and current stage.
     */
    suspend fun getFlywheelStatus(workerCount: Int = 0): FlywheelStatus {
        val stage = GrowthStage.fromWorkerCount(workerCount)
        val metrics = loadMetrics()
        val compoundVelocity = computeCompoundVelocity(metrics, stage)

        return FlywheelStatus(
            stage = stage,
            workerCount = workerCount,
            compoundVelocity = compoundVelocity,
            loops = Loop.entries.map { loop ->
                val m = metrics[loop] ?: LoopMetrics(loop = loop)
                LoopStatus(
                    loop = loop,
                    isActive = loop.isActiveAt(stage),
                    eventsProcessed = m.eventsProcessed,
                    improvementsGenerated = m.improvementsGenerated,
                    velocity = m.velocity
                )
            }
        )
    }
}

// ─────────────────────────────────────────────────────────
// Data Classes
// ─────────────────────────────────────────────────────────

data class LearnedPattern(
    val key: String,
    val data: Map<String, String>,
    val confidence: Float,
    val usageCount: Int
)

data class LoopStatus(
    val loop: FlywheelEngine.Loop,
    val isActive: Boolean,
    val eventsProcessed: Int,
    val improvementsGenerated: Int,
    val velocity: Double
)

data class FlywheelStatus(
    val stage: FlywheelEngine.GrowthStage,
    val workerCount: Int,
    val compoundVelocity: Double,
    val loops: List<LoopStatus>
) {
    fun toReport(): String {
        val sb = StringBuilder()
        sb.appendLine("[FLYWHEEL STATUS | Stage: ${stage.label} | Workers: $workerCount]")
        sb.appendLine("Compound velocity: ${"%.1f".format(compoundVelocity)} events/hr")
        sb.appendLine()
        for (ls in loops) {
            val status = if (ls.isActive) "●" else "○"
            sb.appendLine("  $status ${ls.loop.label}: ${ls.eventsProcessed} events, " +
                "${"%.0f".format(ls.velocity)}/hr, ${ls.improvementsGenerated} improvements")
        }
        return sb.toString()
    }
}
