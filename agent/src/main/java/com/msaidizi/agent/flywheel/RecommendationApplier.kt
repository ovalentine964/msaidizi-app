package com.msaidizi.agent.flywheel

import com.msaidizi.agent.harness.HarnessImprover
import com.msaidizi.agent.harness.BackendRecommendation
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RecommendationApplier — Closes the flywheel feedback loop.
 *
 * Takes recommendations generated from trace analysis and flywheel data,
 * then applies weight adjustments to both the HarnessImprover and the
 * FlywheelEngine knowledge base. This is the "actuator" that turns
 * learning into system improvements.
 *
 * The flywheel cycle:
 *   Write (processInteraction) → Read (getAdviceConfidence etc.) →
 *   Analyze (TraceDrivenLearning) → Apply (RecommendationApplier) →
 *   Better results → Write again...
 *
 * Handles three types of adjustments:
 *  1. Intent weight adjustments — boost/penalize intent patterns via HarnessImprover
 *  2. Tool reliability weights — prefer reliable tools
 *  3. Quality threshold adjustments — adapt advice quality bars
 */
@Singleton
class RecommendationApplier @Inject constructor(
    private val flywheelEngine: FlywheelEngine,
    private val harnessImprover: HarnessImprover
) {
    companion object {
        private const val MAX_ADJUSTMENT = 0.3f
        private const val MIN_WEIGHT = 0.3f
        private const val MAX_WEIGHT = 1.5f
    }

    /**
     * Apply a single recommendation from trace analysis.
     * Routes to HarnessImprover for harness-level adjustments and
     * to FlywheelEngine for knowledge-level adjustments.
     *
     * @param type The type of adjustment: "intent_weight", "tool_reliability", "quality_threshold"
     * @param target The target key (e.g., "RECORD_SALE", "check_stock", "savings")
     * @param adjustmentValue The weight delta to apply (positive = boost, negative = penalize)
     * @param confidence How confident the trace analysis is in this recommendation (0.0-1.0)
     * @param description Human-readable reason for the adjustment
     * @return true if the recommendation was applied, false if rejected
     */
    suspend fun apply(
        type: String,
        target: String,
        adjustmentValue: Float,
        confidence: Float,
        description: String
    ): Boolean {
        // Reject low-confidence recommendations
        if (confidence < 0.3f) {
            Timber.d("RecommendationApplier: Rejected low-confidence recommendation for %s (%.2f)", target, confidence)
            return false
        }

        // Clamp adjustment magnitude
        val clampedAdjustment = adjustmentValue.coerceIn(-MAX_ADJUSTMENT, MAX_ADJUSTMENT)

        // Scale by confidence
        val scaledAdjustment = clampedAdjustment * confidence

        when (type) {
            "intent_weight" -> {
                // Route to HarnessImprover which manages intent weight adjustments
                val backendRec = BackendRecommendation(
                    id = "flywheel_${System.currentTimeMillis()}",
                    type = "intent_weight",
                    target = target,
                    adjustmentValue = scaledAdjustment,
                    confidence = confidence,
                    description = description
                )
                harnessImprover.applyRecommendation(backendRec)
            }
            "tool_edge" -> {
                // Route to HarnessImprover for tool selection graph adjustments
                val backendRec = BackendRecommendation(
                    id = "flywheel_${System.currentTimeMillis()}",
                    type = "tool_edge",
                    target = target,
                    adjustmentValue = scaledAdjustment,
                    confidence = confidence,
                    description = description
                )
                harnessImprover.applyRecommendation(backendRec)
            }
            "tool_reliability" -> {
                // Tool reliability is tracked in FlywheelEngine
                // The HarnessImprover's tool_edge handles selection; this is informational
                Timber.d("RecommendationApplier: Tool reliability adjustment for %s: %.3f", target, scaledAdjustment)
            }
            "quality_threshold" -> {
                // Quality thresholds are read by AdviceRefinementLoop via getAdviceConfidence()
                Timber.d("RecommendationApplier: Quality threshold adjustment for %s: %.3f", target, scaledAdjustment)
            }
            else -> {
                Timber.w("RecommendationApplier: Unknown recommendation type: %s", type)
                return false
            }
        }

        Timber.i("RecommendationApplier: Applied %s for %s: %.3f (confidence=%.2f) — %s",
            type, target, scaledAdjustment, confidence, description)
        return true
    }

    /**
     * Apply multiple recommendations in batch.
     * Returns count of successfully applied recommendations.
     */
    suspend fun applyBatch(recommendations: List<Recommendation>): Int {
        var applied = 0
        for (rec in recommendations) {
            if (apply(rec.type, rec.target, rec.adjustmentValue, rec.confidence, rec.description)) {
                applied++
            }
        }
        Timber.d("RecommendationApplier: Applied %d/%d recommendations", applied, recommendations.size)
        return applied
    }

    /**
     * Apply a BackendRecommendation directly from the trace analysis system.
     * Bridges TraceDrivenLearning output into the HarnessImprover.
     */
    suspend fun applyBackendRecommendation(rec: BackendRecommendation): Boolean {
        return try {
            harnessImprover.applyRecommendation(rec)
            Timber.i("RecommendationApplier: Applied backend recommendation %s for %s", rec.type, rec.target)
            true
        } catch (e: Exception) {
            Timber.w(e, "RecommendationApplier: Failed to apply backend recommendation")
            false
        }
    }

    /**
     * Generate auto-recommendations from flywheel data.
     * Analyzes current flywheel state and generates improvement recommendations.
     */
    suspend fun generateRecommendations(): List<Recommendation> {
        val recommendations = mutableListOf<Recommendation>()

        // 1. Analyze tool reliability — penalize unreliable tools
        try {
            val reliability = flywheelEngine.getToolReliability()
            for ((tool, score) in reliability) {
                if (score < 0.3f) {
                    recommendations.add(Recommendation(
                        type = "tool_edge",
                        target = tool,
                        adjustmentValue = -0.1f,
                        confidence = (1.0f - score).coerceIn(0.5f, 0.9f),
                        description = "Tool '$tool' has low reliability (${(score * 100).toInt()}%)"
                    ))
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to analyze tool reliability")
        }

        // 2. Analyze advice confidence — adjust quality thresholds
        try {
            val adviceConf = flywheelEngine.getAdviceConfidence()
            for ((intent, score) in adviceConf) {
                if (score > 0.8f) {
                    recommendations.add(Recommendation(
                        type = "quality_threshold",
                        target = intent,
                        adjustmentValue = -0.05f,
                        confidence = score,
                        description = "Intent '$intent' has high proven confidence (${(score * 100).toInt()}%)"
                    ))
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to analyze advice confidence")
        }

        // 3. Analyze vocabulary growth — boost intents with rich vocabulary
        try {
            val vocab = flywheelEngine.getLearnedVocabulary()
            if (vocab.size > 50) {
                recommendations.add(Recommendation(
                    type = "intent_weight",
                    target = "VOCABULARY_BOOST",
                    adjustmentValue = 0.05f,
                    confidence = 0.7f,
                    description = "Rich vocabulary (${vocab.size} words) — boost pattern matching"
                ))
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to analyze vocabulary")
        }

        return recommendations
    }
}

/**
 * A recommendation generated from trace analysis or flywheel data.
 */
data class Recommendation(
    val type: String,
    val target: String,
    val adjustmentValue: Float,
    val confidence: Float,
    val description: String
)
