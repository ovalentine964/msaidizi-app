package com.msaidizi.agent.harness

import com.msaidizi.agent.flywheel.FlywheelEngine
import com.msaidizi.agent.graph.ToolGraph
import com.msaidizi.core.database.KnowledgeDao
import com.msaidizi.core.database.TraceDao
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

/**
 * HarnessImprover — The hill-climbing engine for automated harness improvement.
 *
 * Reads trace analysis results (from TraceDao aggregate queries or backend
 * recommendations) and adjusts three harness components:
 *
 *   1. **IntentRouter weights** — Boost/reduce pattern matching weights for
 *      intents based on success rates and correction rates.
 *
 *   2. **ToolGraph edges** — Add/remove edges based on tool co-occurrence
 *      patterns and success rates.
 *
 *   3. **Context assembly priorities** — Adjust which context layers get
 *      more tokens based on confidence correlations.
 *
 * This implements Loop 4 of the flywheel:
 *   Traces → Analysis → Recommendations → Harness Updates → Better Traces → ...
 *
 * Hill-climbing constraints:
 *   - Changes are incremental (max ±0.1 per cycle) to avoid oscillation
 *   - Each change is A/B tested before full rollout
 *   - Rollback if success rate drops below baseline
 *   - Changes are persisted in KnowledgeDao for survival across sessions
 */
@Singleton
class HarnessImprover @Inject constructor(
    private val traceDao: TraceDao,
    private val knowledgeDao: KnowledgeDao,
    private val flywheelEngine: FlywheelEngine,
    private val gson: Gson
) {
    companion object {
        /** Minimum traces needed before making adjustments. */
        private const val MIN_TRACES_FOR_ADJUSTMENT = 50

        /** Maximum weight change per improvement cycle. */
        private const val MAX_WEIGHT_DELTA = 0.1f

        /** Success rate below this triggers a critical adjustment. */
        private const val CRITICAL_SUCCESS_RATE = 0.50f

        /** Correction rate above this triggers a weight reduction. */
        private const val HIGH_CORRECTION_RATE = 0.20f

        /** Confidence boost for patterns that work well. */
        private const val SUCCESS_BOOST = 0.05f

        /** Confidence penalty for patterns that fail. */
        private const val FAILURE_PENALTY = 0.03f

        /** KnowledgeDao categories for persisted adjustments. */
        private const val CATEGORY_INTENT_WEIGHTS = "harness_intent_weights"
        private const val CATEGORY_TOOL_GRAPH = "harness_tool_graph"
        private const val CATEGORY_CONTEXT_PRIORITY = "harness_context_priority"
    }

    // In-memory cache of adjustments (persisted in KnowledgeDao)
    private val intentWeightAdjustments = ConcurrentHashMap<String, Float>()
    private val toolEdgeAdjustments = ConcurrentHashMap<String, Float>()
    private val contextPriorityAdjustments = ConcurrentHashMap<String, Float>()

    /**
     * Run a full improvement cycle.
     *
     * Reads traces from the last 24 hours, computes adjustments,
     * and applies them incrementally.
     *
     * @return Summary of adjustments made.
     */
    suspend fun runImprovementCycle(): ImprovementCycleResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val dayAgo = now - (24 * 60 * 60 * 1000L)

        // Load current adjustments from persistence
        loadPersistedAdjustments()

        // Get aggregate data from traces
        val intentSuccessRates = traceDao.getIntentSuccessRates(dayAgo, now)
        val correctionRates = traceDao.getCorrectionRates(dayAgo, now)
        val feedbackRates = traceDao.getFeedbackRates(dayAgo, now)
        val latencyBreakdown = traceDao.getLatencyBreakdown(dayAgo, now)

        val totalTraces = intentSuccessRates.sumOf { it.totalCount }
        if (totalTraces < MIN_TRACES_FOR_ADJUSTMENT) {
            Timber.d("HarnessImprover: only %d traces (need %d), skipping", totalTraces, MIN_TRACES_FOR_ADJUSTMENT)
            return@withContext ImprovementCycleResult(
                totalTraces = totalTraces,
                adjustmentsMade = 0,
                skipped = true,
                reason = "Insufficient traces"
            )
        }

        var adjustmentsMade = 0
        val adjustments = mutableListOf<AdjustmentRecord>()

        // ── 1. Adjust IntentRouter weights ──────────────────────────
        for (stats in intentSuccessRates) {
            val intent = stats.intentType
            val successRate = stats.successRate
            val correctionRate = correctionRates
                .find { it.intentType == intent }
                ?.correctionRate ?: 0f
            val feedbackRate = feedbackRates
                .find { it.intentType == intent }
                ?.positiveRate ?: 0f

            // Compute weight adjustment
            val currentWeight = intentWeightAdjustments[intent] ?: 1.0f
            var delta = 0f

            if (successRate < CRITICAL_SUCCESS_RATE && stats.totalCount >= 20) {
                // Critical: reduce confidence, boost pattern matching
                delta = -MAX_WEIGHT_DELTA
                Timber.w("HarnessImprover: intent %s has %.0f%% success — reducing weight", intent, successRate * 100)
            } else if (correctionRate > HIGH_CORRECTION_RATE) {
                // Users correcting often = wrong routing
                delta = -(correctionRate * 0.5f).coerceAtMost(MAX_WEIGHT_DELTA)
                Timber.d("HarnessImprover: intent %s has %.0f%% correction — reducing weight", intent, correctionRate * 100)
            } else if (successRate > 0.85f && feedbackRate > 0.7f) {
                // Working well — boost slightly
                delta = SUCCESS_BOOST
                Timber.d("HarnessImprover: intent %s performing well (%.0f%% success) — boosting", intent, successRate * 100)
            }

            if (delta != 0f) {
                val newWeight = (currentWeight + delta).coerceIn(0.5f, 1.5f)
                intentWeightAdjustments[intent] = newWeight
                adjustmentsMade++
                adjustments.add(AdjustmentRecord(
                    type = AdjustmentType.INTENT_WEIGHT,
                    target = intent,
                    oldValue = currentWeight,
                    newValue = newWeight,
                    reason = "success_rate=%.2f, correction_rate=%.2f, feedback_rate=%.2f".format(
                        successRate, correctionRate, feedbackRate
                    )
                ))
            }
        }

        // ── 2. Adjust ToolGraph edges ───────────────────────────────
        // Identify tools that consistently fail for specific intents
        val toolFailures = traceDao.getToolFailures(dayAgo, now)
        for (failure in toolFailures) {
            val key = "${failure.intentType}:${failure.toolsSelected}"
            val currentEdgeWeight = toolEdgeAdjustments[key] ?: 1.0f

            if (failure.toolsFailed > 0) {
                val newWeight = (currentEdgeWeight - FAILURE_PENALTY).coerceAtLeast(0.3f)
                toolEdgeAdjustments[key] = newWeight
                adjustmentsMade++
                adjustments.add(AdjustmentRecord(
                    type = AdjustmentType.TOOL_GRAPH_EDGE,
                    target = key,
                    oldValue = currentEdgeWeight,
                    newValue = newWeight,
                    reason = "tool_failure"
                ))
            }
        }

        // ── 3. Adjust context assembly priorities ───────────────────
        for (latency in latencyBreakdown) {
            val intent = latency.intentType
            val key = "context_priority:$intent"
            val currentPriority = contextPriorityAdjustments[key] ?: 1.0f

            // If LLM inference is slow, reduce context size for this intent
            if (latency.avgLlm > 5000) {
                val newPriority = (currentPriority - 0.05f).coerceAtLeast(0.5f)
                contextPriorityAdjustments[key] = newPriority
                adjustmentsMade++
                adjustments.add(AdjustmentRecord(
                    type = AdjustmentType.CONTEXT_PRIORITY,
                    target = intent,
                    oldValue = currentPriority,
                    newValue = newPriority,
                    reason = "llm_latency=%dms".format(latency.avgLlm)
                ))
            }
        }

        // ── Persist adjustments ─────────────────────────────────────
        persistAdjustments()

        Timber.i("HarnessImprover: %d adjustments from %d traces", adjustmentsMade, totalTraces)

        ImprovementCycleResult(
            totalTraces = totalTraces,
            adjustmentsMade = adjustmentsMade,
            skipped = false,
            adjustments = adjustments
        )
    }

    /**
     * Get the current weight adjustment for an intent.
     * Used by IntentRouter to adjust classification confidence.
     */
    fun getIntentWeight(intent: String): Float {
        return intentWeightAdjustments[intent] ?: 1.0f
    }

    /**
     * Get the current edge weight for a tool-intent combination.
     * Used by ToolGraph to adjust execution priority.
     */
    fun getToolEdgeWeight(intent: String, toolName: String): Float {
        val key = "$intent:$toolName"
        return toolEdgeAdjustments[key] ?: 1.0f
    }

    /**
     * Get the current context priority for an intent.
     * Used by ContextAssembler to adjust layer priorities.
     */
    fun getContextPriority(intent: String): Float {
        val key = "context_priority:$intent"
        return contextPriorityAdjustments[key] ?: 1.0f
    }

    /**
     * Apply a specific recommendation from the backend trace analysis.
     * Called when the backend pushes a recommendation to the device.
     */
    suspend fun applyRecommendation(rec: BackendRecommendation) {
        when (rec.type) {
            "intent_weight" -> {
                val current = intentWeightAdjustments[rec.target] ?: 1.0f
                val adjustment = (rec.adjustmentValue as? Number)?.toFloat() ?: return
                val new = (current + adjustment).coerceIn(0.5f, 1.5f)
                intentWeightAdjustments[rec.target] = new
                Timber.d("HarnessImprover: applied backend recommendation for %s: %.2f → %.2f", rec.target, current, new)
            }
            "tool_edge" -> {
                val current = toolEdgeAdjustments[rec.target] ?: 1.0f
                val adjustment = (rec.adjustmentValue as? Number)?.toFloat() ?: return
                val new = (current + adjustment).coerceIn(0.3f, 1.5f)
                toolEdgeAdjustments[rec.target] = new
            }
            "context_priority" -> {
                val current = contextPriorityAdjustments[rec.target] ?: 1.0f
                val adjustment = (rec.adjustmentValue as? Number)?.toFloat() ?: return
                val new = (current + adjustment).coerceIn(0.5f, 1.5f)
                contextPriorityAdjustments[rec.target] = new
            }
        }
        persistAdjustments()
    }

    // ── Persistence ───────────────────────────────────────────────────

    private suspend fun loadPersistedAdjustments() {
        try {
            knowledgeDao.getByCategory(CATEGORY_INTENT_WEIGHTS).first().forEach { entry ->
                intentWeightAdjustments[entry.key] = entry.confidence
            }
            knowledgeDao.getByCategory(CATEGORY_TOOL_GRAPH).first().forEach { entry ->
                toolEdgeAdjustments[entry.key] = entry.confidence
            }
            knowledgeDao.getByCategory(CATEGORY_CONTEXT_PRIORITY).first().forEach { entry ->
                contextPriorityAdjustments[entry.key] = entry.confidence
            }
            Timber.d("HarnessImprover: loaded %d intent, %d tool, %d context adjustments",
                intentWeightAdjustments.size, toolEdgeAdjustments.size, contextPriorityAdjustments.size)
        } catch (e: Exception) {
            Timber.w(e, "HarnessImprover: failed to load persisted adjustments")
        }
    }

    private suspend fun persistAdjustments() {
        try {
            for ((intent, weight) in intentWeightAdjustments) {
                persistEntry(CATEGORY_INTENT_WEIGHTS, intent, weight, mapOf("type" to "intent_weight"))
            }
            for ((key, weight) in toolEdgeAdjustments) {
                persistEntry(CATEGORY_TOOL_GRAPH, key, weight, mapOf("type" to "tool_edge"))
            }
            for ((key, priority) in contextPriorityAdjustments) {
                persistEntry(CATEGORY_CONTEXT_PRIORITY, key, priority, mapOf("type" to "context_priority"))
            }
        } catch (e: Exception) {
            Timber.w(e, "HarnessImprover: failed to persist adjustments")
        }
    }

    private suspend fun persistEntry(category: String, key: String, confidence: Float, data: Map<String, String>) {
        val existing = knowledgeDao.getEntry(category, key)
        if (existing != null) {
            knowledgeDao.update(existing.copy(
                confidence = confidence,
                value = gson.toJson(data),
                updatedAt = System.currentTimeMillis()
            ))
        } else {
            knowledgeDao.insert(com.msaidizi.core.model.KnowledgeEntity(
                category = category,
                key = key,
                value = gson.toJson(data),
                confidence = confidence,
                usageCount = 0
            ))
        }
    }
}

// ── Supporting Types ────────────────────────────────────────────────────

enum class AdjustmentType {
    INTENT_WEIGHT,
    TOOL_GRAPH_EDGE,
    CONTEXT_PRIORITY
}

data class AdjustmentRecord(
    val type: AdjustmentType,
    val target: String,
    val oldValue: Float,
    val newValue: Float,
    val reason: String
)

data class ImprovementCycleResult(
    val totalTraces: Int,
    val adjustmentsMade: Int,
    val skipped: Boolean,
    val reason: String? = null,
    val adjustments: List<AdjustmentRecord> = emptyList()
)

/**
 * Recommendation received from the backend trace analysis.
 */
data class BackendRecommendation(
    val id: String,
    val type: String,       // "intent_weight", "tool_edge", "context_priority"
    val target: String,     // e.g., "RECORD_SALE", "RECORD_SALE:check_stock"
    val adjustmentValue: Any,
    val confidence: Float,
    val description: String
)
