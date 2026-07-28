package com.msaidizi.agent.flywheel

import com.msaidizi.agent.harness.HarnessImprover
import com.msaidizi.agent.harness.IntentType
import com.msaidizi.core.database.KnowledgeDao
import com.msaidizi.core.database.TraceDao
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TraceDrivenLearning — Extension of FlywheelEngine for Loop 7: Harness Loop.
 *
 * While the base FlywheelEngine learns vocabulary, business patterns, and
 * market intelligence, this extension focuses specifically on learning from
 * agent execution traces to improve the harness itself.
 *
 * This is the "self-improving" component that implements the hill-climbing
 * algorithm: measure → analyze → adjust → measure again.
 *
 * Key responsibilities:
 *   1. Track per-intent success/failure patterns over time
 *   2. Identify tool selection mismatches ("tool X selected but fails 40%")
 *   3. Generate personalized recommendations per worker
 *   4. Feed improvements into HarnessImprover
 *   5. Track the effectiveness of past improvements (A/B comparison)
 *
 * This completes the flywheel:
 *   User Input → Agent Run → Trace → Analysis → Improvement → Better Run → ...
 */
@Singleton
class TraceDrivenLearning @Inject constructor(
    private val traceDao: TraceDao,
    private val knowledgeDao: KnowledgeDao,
    private val harnessImprover: HarnessImprover,
    private val gson: Gson
) {
    companion object {
        private const val CATEGORY_TRACE_INSIGHTS = "trace_insights"
        private const val CATEGORY_IMPROVEMENT_HISTORY = "improvement_history"
        private const val CATEGORY_PERSONALIZED_RECS = "personalized_recommendations"

        /** Rolling window for pattern analysis (7 days). */
        private const val ANALYSIS_WINDOW_MS = 7 * 24 * 60 * 60 * 1000L

        /** Minimum observations before generating a recommendation. */
        private const val MIN_OBSERVATIONS = 10
    }

    // In-memory cache of recent insights
    private val intentInsights = ConcurrentHashMap<String, IntentInsight>()
    private val toolInsights = ConcurrentHashMap<String, ToolInsight>()

    /**
     * Run a trace-driven learning cycle.
     * Called periodically (e.g., daily) or after N new traces.
     */
    suspend fun runLearningCycle(): LearningCycleResult {
        val now = System.currentTimeMillis()
        val windowStart = now - ANALYSIS_WINDOW_MS

        // 1. Gather trace aggregates
        val intentSuccessRates = traceDao.getIntentSuccessRates(windowStart, now)
        val correctionRates = traceDao.getCorrectionRates(windowStart, now)
        val feedbackRates = traceDao.getFeedbackRates(windowStart, now)
        val latencyBreakdown = traceDao.getLatencyBreakdown(windowStart, now)
        val tierDistribution = traceDao.getTierDistribution(windowStart, now)

        val totalTraces = intentSuccessRates.sumOf { it.totalCount }
        if (totalTraces < MIN_OBSERVATIONS) {
            return LearningCycleResult(
                totalTraces = totalTraces,
                insightsGenerated = 0,
                recommendationsGenerated = 0,
                skipped = true
            )
        }

        var insightsGenerated = 0
        val recommendations = mutableListOf<PersonalizedRecommendation>()

        // 2. Analyze per-intent patterns
        for (stats in intentSuccessRates) {
            val intent = stats.intentType
            val correctionRate = correctionRates.find { it.intentType == intent }?.correctionRate ?: 0f
            val feedbackRate = feedbackRates.find { it.intentType == intent }?.positiveRate ?: 0f
            val latency = latencyBreakdown.find { it.intentType == intent }

            val insight = IntentInsight(
                intentType = intent,
                successRate = stats.successRate,
                correctionRate = correctionRate,
                positiveFeedbackRate = feedbackRate,
                avgLatencyMs = latency?.avgTotal ?: 0,
                totalRuns = stats.totalCount,
                trend = computeTrend(intent, stats.successRate),
                lastUpdated = now
            )

            intentInsights[intent] = insight
            insightsGenerated++

            // Generate personalized recommendations for problematic intents
            if (stats.successRate < 0.60f && stats.totalCount >= MIN_OBSERVATIONS) {
                recommendations.add(PersonalizedRecommendation(
                    type = "intent_improvement",
                    target = intent,
                    description = buildString {
                        append("Intent '$intent' has ${"%.0f".format(stats.successRate * 100)}% success rate")
                        if (correctionRate > 0.15f) {
                            append(" and ${"%.0f".format(correctionRate * 100)}% correction rate")
                        }
                        append(". Consider adding more specific keywords for this intent.")
                    },
                    priority = if (stats.successRate < 0.40f) "critical" else "high",
                    evidence = mapOf(
                        "success_rate" to stats.successRate,
                        "correction_rate" to correctionRate,
                        "feedback_rate" to feedbackRate,
                        "total_runs" to stats.totalCount
                    )
                ))
            }

            // High correction rate = misrouting
            if (correctionRate > 0.25f && stats.totalCount >= MIN_OBSERVATIONS) {
                recommendations.add(PersonalizedRecommendation(
                    type = "routing_fix",
                    target = intent,
                    description = "Users rephrase ${"%.0f".format(correctionRate * 100)}% of the time for '$intent'. " +
                        "The router may be misclassifying these inputs.",
                    priority = "high",
                    evidence = mapOf(
                        "correction_rate" to correctionRate,
                        "corrections" to (correctionRates.find { it.intentType == intent }?.corrections ?: 0)
                    )
                ))
            }

            // Low confidence but high success = context assembly issue
            if (latency != null && stats.successRate > 0.80f) {
                // Check if LLM inference is slow
                if (latency.avgLlm > 5000) {
                    recommendations.add(PersonalizedRecommendation(
                        type = "latency_optimization",
                        target = intent,
                        description = "Intent '$intent' succeeds but LLM takes ${latency.avgLlm}ms avg. " +
                            "Consider reducing context size or using a faster tier.",
                        priority = "medium",
                        evidence = mapOf(
                            "avg_llm_ms" to latency.avgLlm,
                            "avg_total_ms" to latency.avgTotal
                        )
                    ))
                }
            }
        }

        // 3. Analyze tool usage patterns
        val toolFailures = traceDao.getToolFailures(windowStart, now)
        for (failure in toolFailures) {
            val key = failure.intentType
            val toolInsight = toolInsights.getOrPut(key) {
                ToolInsight(intentType = key, toolFailures = mutableListOf())
            }
            toolInsight.toolFailures.add(ToolFailure(
                toolsSelected = failure.toolsSelected,
                toolsFailed = failure.toolsFailed
            ))
        }

        // 4. Persist insights
        persistInsights(insightsGenerated)

        // 5. Feed into HarnessImprover
        harnessImprover.runImprovementCycle()

        Timber.i("TraceDrivenLearning: %d insights, %d recommendations from %d traces",
            insightsGenerated, recommendations.size, totalTraces)

        return LearningCycleResult(
            totalTraces = totalTraces,
            insightsGenerated = insightsGenerated,
            recommendationsGenerated = recommendations.size,
            skipped = false,
            recommendations = recommendations
        )
    }

    /**
     * Get current intent insight for a specific intent.
     */
    fun getIntentInsight(intent: String): IntentInsight? {
        return intentInsights[intent]
    }

    /**
     * Get all intent insights.
     */
    fun getAllIntentInsights(): Map<String, IntentInsight> {
        return intentInsights.toMap()
    }

    /**
     * Get personalized recommendations.
     */
    suspend fun getPersonalizedRecommendations(): List<PersonalizedRecommendation> {
        return try {
            knowledgeDao.getByCategory(CATEGORY_PERSONALIZED_RECS).first()
                .sortedByDescending { it.confidence }
                .take(10)
                .map { entry ->
                    gson.fromJson(entry.value, PersonalizedRecommendation::class.java)
                }
        } catch (e: Exception) {
            Timber.w(e, "TraceDrivenLearning: failed to load recommendations")
            emptyList()
        }
    }

    /**
     * Track the effectiveness of a past improvement.
     * Compares success rate before and after the adjustment.
     */
    suspend fun trackImprovementEffectiveness(
        intent: String,
        adjustmentTime: Long,
        windowDays: Int = 3
    ): ImprovementEffectiveness? {
        val beforeStart = adjustmentTime - (windowDays * 24 * 60 * 60 * 1000L)
        val afterEnd = adjustmentTime + (windowDays * 24 * 60 * 60 * 1000L)

        val beforeRates = traceDao.getIntentSuccessRates(beforeStart, adjustmentTime)
        val afterRates = traceDao.getIntentSuccessRates(adjustmentTime, afterEnd)

        val beforeRate = beforeRates.find { it.intentType == intent }?.successRate ?: return null
        val afterRate = afterRates.find { it.intentType == intent }?.successRate ?: return null

        val improvement = afterRate - beforeRate
        return ImprovementEffectiveness(
            intent = intent,
            beforeSuccessRate = beforeRate,
            afterSuccessRate = afterRate,
            improvement = improvement,
            effective = improvement > 0.02f  // 2% threshold for "effective"
        )
    }

    // ── Private Helpers ────────────────────────────────────────────────

    private fun computeTrend(intent: String, currentRate: Float): InsightTrend {
        val previous = intentInsights[intent]
        if (previous == null) return InsightTrend.NEW

        val delta = currentRate - previous.successRate
        return when {
            delta > 0.05f -> InsightTrend.IMPROVING
            delta < -0.05f -> InsightTrend.DEGRADING
            else -> InsightTrend.STABLE
        }
    }

    private suspend fun persistInsights(count: Int) {
        try {
            // Persist intent insights
            for ((intent, insight) in intentInsights) {
                val existing = knowledgeDao.getEntry(CATEGORY_TRACE_INSIGHTS, "intent:$intent")
                val value = gson.toJson(mapOf(
                    "successRate" to insight.successRate,
                    "correctionRate" to insight.correctionRate,
                    "feedbackRate" to insight.positiveFeedbackRate,
                    "avgLatency" to insight.avgLatencyMs,
                    "totalRuns" to insight.totalRuns,
                    "trend" to insight.trend.name
                ))
                if (existing != null) {
                    knowledgeDao.update(existing.copy(
                        confidence = insight.successRate,
                        value = value,
                        usageCount = existing.usageCount + 1,
                        updatedAt = System.currentTimeMillis()
                    ))
                } else {
                    knowledgeDao.insert(com.msaidizi.core.model.KnowledgeEntity(
                        category = CATEGORY_TRACE_INSIGHTS,
                        key = "intent:$intent",
                        value = value,
                        confidence = insight.successRate,
                        usageCount = 1
                    ))
                }
            }

            Timber.d("TraceDrivenLearning: persisted %d insights", count)
        } catch (e: Exception) {
            Timber.w(e, "TraceDrivenLearning: failed to persist insights")
        }
    }
}

// ── Supporting Types ────────────────────────────────────────────────────

data class IntentInsight(
    val intentType: String,
    val successRate: Float,
    val correctionRate: Float,
    val positiveFeedbackRate: Float,
    val avgLatencyMs: Long,
    val totalRuns: Int,
    val trend: InsightTrend,
    val lastUpdated: Long
)

enum class InsightTrend {
    NEW,
    IMPROVING,
    STABLE,
    DEGRADING
}

data class ToolInsight(
    val intentType: String,
    val toolFailures: MutableList<ToolFailure>
)

data class ToolFailure(
    val toolsSelected: String,
    val toolsFailed: Int
)

data class PersonalizedRecommendation(
    val type: String,
    val target: String,
    val description: String,
    val priority: String,
    val evidence: Map<String, Any>
)

data class ImprovementEffectiveness(
    val intent: String,
    val beforeSuccessRate: Float,
    val afterSuccessRate: Float,
    val improvement: Float,
    val effective: Boolean
)

data class LearningCycleResult(
    val totalTraces: Int,
    val insightsGenerated: Int,
    val recommendationsGenerated: Int,
    val skipped: Boolean,
    val recommendations: List<PersonalizedRecommendation> = emptyList()
)
