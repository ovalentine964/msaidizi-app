package com.msaidizi.agent.loops

import com.msaidizi.core.database.KnowledgeDao
import com.msaidizi.agent.flywheel.FlywheelEngine
import com.msaidizi.agent.harness.IntentType
import com.msaidizi.agent.harness.IntentRouter
import com.msaidizi.agent.harness.UserIntent
import com.msaidizi.agent.memory.MemoryManager
import com.msaidizi.agent.tools.core.ToolResult
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FeedbackLoopIntegration — Connect FlywheelEngine outcomes back into the pipeline.
 *
 * Implements the self-improving feedback loops described in growth_feedback_loops.md:
 *
 *   Loop A: Correction → Vocabulary → Re-routing
 *     If worker corrects a transaction → update vocabulary → improve intent routing
 *
 *   Loop B: Advice Outcome → Strategy Adjustment
 *     If advice was followed → reinforce pattern
 *     If advice was ignored → adjust advice strategy
 *
 *   Loop C: Prediction Error → Model Recalibration
 *     If financial prediction was wrong → recalibrate prediction weights
 *
 *   Loop D: Tool Failure → Pattern Learning
 *     If tool execution failed → learn failure pattern → adjust tool selection
 *
 * This is the "outer loop" that makes the pipeline SELF-IMPROVING over time,
 * not just a one-pass processor.
 *
 * Reference: growth_feedback_loops.md §1.2, §4.4; loop_engineering_report.md §2.4
 */
@Singleton
class FeedbackLoopIntegration @Inject constructor(
    private val flywheelEngine: FlywheelEngine,
    private val knowledgeDao: KnowledgeDao,
    private val selfCorrectionLoop: SelfCorrectionLoop,
    private val circuitBreaker: CircuitBreaker,
    private val gson: Gson
) {
    companion object {
        /** Minimum interactions before feedback patterns are trusted. */
        const val MIN_INTERACTIONS_FOR_PATTERN = 5

        /** Confidence boost for patterns that have been validated. */
        const val VALIDATED_PATTERN_BOOST = 0.1f

        /** Advice follow-up tracking window (ms). 24 hours. */
        const val ADVICE_TRACKING_WINDOW_MS = 24 * 60 * 60 * 1000L
    }

    // ═══════════════════════════════════════════════════════════════
    // LOOP A: Correction → Vocabulary → Re-routing
    // ═══════════════════════════════════════════════════════════════

    /**
     * Process a user correction and update the vocabulary/intent routing.
     *
     * When a worker corrects a transaction (e.g., "sio nyanya, viazi!"):
     *   1. Record the correction in the flywheel
     *   2. Update the learned vocabulary
     *   3. The IntentRouter will pick up the new vocabulary on next route()
     *
     * @param originalInput    What the system heard/understood
     * @param correctedInput   What the user said it should be
     * @param correctionType   What was corrected (product, amount, category, etc.)
     */
    suspend fun processCorrection(
        originalInput: String,
        correctedInput: String,
        correctionType: CorrectionType
    ) {
        Timber.d("FeedbackLoop A: Processing correction (%s): '%s' → '%s'",
            correctionType, originalInput, correctedInput)

        // Store correction in knowledge base for vocabulary learning
        val correctionKey = "correction_${correctionType.name.lowercase()}_${System.currentTimeMillis()}"
        knowledgeDao.insert(
            com.msaidizi.core.model.KnowledgeEntity(
                category = "corrections",
                key = correctionKey,
                value = gson.toJson(mapOf(
                    "original" to originalInput,
                    "corrected" to correctedInput,
                    "type" to correctionType.name,
                    "timestamp" to System.currentTimeMillis()
                )),
                confidence = 0.8f,
                usageCount = 1
            )
        )

        // Update vocabulary: if corrected input contains new words, learn them
        val newWords = correctedInput.lowercase().split(Regex("\\s+"))
            .filter { it.length > 3 }
        for (word in newWords) {
            val existing = knowledgeDao.getEntry("vocab", word)
            if (existing != null) {
                knowledgeDao.update(existing.copy(
                    confidence = (existing.confidence + 0.2f).coerceAtMost(1.0f),
                    usageCount = existing.usageCount + 1,
                    updatedAt = System.currentTimeMillis()
                ))
            } else {
                knowledgeDao.insert(
                    com.msaidizi.core.model.KnowledgeEntity(
                        category = "vocab",
                        key = word,
                        value = gson.toJson(mapOf(
                            "source" to "correction",
                            "timestamp" to System.currentTimeMillis()
                        )),
                        confidence = 0.6f,
                        usageCount = 1
                    )
                )
            }
        }

        // If it was a product name correction, update the product vocabulary mapping
        if (correctionType == CorrectionType.PRODUCT_NAME) {
            knowledgeDao.insert(
                com.msaidizi.core.model.KnowledgeEntity(
                    category = "product_corrections",
                    key = originalInput.lowercase().take(50),
                    value = correctedInput.lowercase(),
                    confidence = 0.9f,
                    usageCount = 1
                )
            )
        }

        Timber.i("FeedbackLoop A: Correction processed, vocabulary updated")
    }

    // ═══════════════════════════════════════════════════════════════
    // LOOP B: Advice Outcome → Strategy Adjustment
    // ═══════════════════════════════════════════════════════════════

    /**
     * Track whether the user followed the advice, and adjust strategy.
     *
     * Called when we detect the user took action that relates to prior advice.
     * Uses the signal weights from growth_feedback_loops.md §1.2:
     *   - Followed within 1h: +1.0 (strong positive)
     *   - Followed within 24h: +0.8 (positive)
     *   - Ignored for 7+ days: -0.5 (negative)
     *   - Explicitly dismissed: -1.0 (strong negative)
     *   - Followed then reversed: -0.7 (weak negative)
     *
     * @param adviceType     Type of advice that was given
     * @param adviceId       Unique identifier for the advice instance
     * @param outcome        What the user did
     */
    suspend fun trackAdviceOutcome(
        adviceType: String,
        adviceId: String,
        outcome: AdviceOutcome
    ) {
        val signalStrength = when (outcome) {
            AdviceOutcome.FOLLOWED_WITHIN_1H -> 1.0f
            AdviceOutcome.FOLLOWED_WITHIN_24H -> 0.8f
            AdviceOutcome.FOLLOWED_BUT_REVERSED -> -0.7f
            AdviceOutcome.IGNORED_7_DAYS -> -0.5f
            AdviceOutcome.EXPLICITLY_DISMISSED -> -1.0f
            AdviceOutcome.ASKED_FOLLOWUP -> 0.6f
        }

        Timber.d("FeedbackLoop B: Advice outcome (%s): %s (signal=%.1f)",
            adviceType, outcome, signalStrength)

        // Update advice pattern confidence in knowledge base
        val patternKey = "advice_pattern_${adviceType.lowercase()}"
        val existing = knowledgeDao.getEntry("advice_feedback", patternKey)

        if (existing != null) {
            val currentConfidence = existing.confidence
            val newConfidence = (currentConfidence + signalStrength * 0.1f).coerceIn(0.0f, 1.0f)
            knowledgeDao.update(existing.copy(
                confidence = newConfidence,
                usageCount = existing.usageCount + 1,
                updatedAt = System.currentTimeMillis()
            ))

            // If confidence drops below threshold, flag for review
            if (newConfidence < 0.3f && existing.usageCount >= MIN_INTERACTIONS_FOR_PATTERN) {
                Timber.w("FeedbackLoop B: Advice type '%s' confidence critically low (%.2f) — flagging for review",
                    adviceType, newConfidence)
                flagAdviceForReview(adviceType, newConfidence)
            }
        } else {
            knowledgeDao.insert(
                com.msaidizi.core.model.KnowledgeEntity(
                    category = "advice_feedback",
                    key = patternKey,
                    value = gson.toJson(mapOf(
                        "adviceType" to adviceType,
                        "lastOutcome" to outcome.name,
                        "signalStrength" to signalStrength,
                        "timestamp" to System.currentTimeMillis()
                    )),
                    confidence = (0.5f + signalStrength * 0.1f).coerceIn(0.0f, 1.0f),
                    usageCount = 1
                )
            )
        }
    }

    /**
     * Get advice effectiveness scores. Used by AdviceRefinementLoop
     * to adjust advice strategy.
     */
    suspend fun getAdviceEffectiveness(): Map<String, Float> {
        return try {
            knowledgeDao.getByCategory("advice_feedback").first()
                .associate { it.key.removePrefix("advice_pattern_") to it.confidence }
        } catch (e: Exception) {
            Timber.w(e, "FeedbackLoop B: Failed to get advice effectiveness")
            emptyMap()
        }
    }

    /**
     * Flag an advice type for human review when confidence drops critically.
     */
    private suspend fun flagAdviceForReview(adviceType: String, confidence: Float) {
        knowledgeDao.insert(
            com.msaidizi.core.model.KnowledgeEntity(
                category = "advice_review",
                key = "review_${adviceType}_${System.currentTimeMillis()}",
                value = gson.toJson(mapOf(
                    "adviceType" to adviceType,
                    "confidence" to confidence,
                    "reason" to "Confidence below 0.3 after ${MIN_INTERACTIONS_FOR_PATTERN}+ interactions",
                    "timestamp" to System.currentTimeMillis()
                )),
                confidence = 0.0f,
                usageCount = 0
            )
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // LOOP C: Prediction Error → Model Recalibration
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a prediction vs actual outcome for recalibration.
     *
     * When the CFOEngine predicts cash flow or sales, and the actual
     * numbers come in later, we compare and adjust prediction weights.
     *
     * @param predictionType  Type of prediction (daily_sales, weekly_profit, etc.)
     * @param predictedValue  What the model predicted
     * @param actualValue     What actually happened
     */
    suspend fun recordPredictionOutcome(
        predictionType: String,
        predictedValue: Double,
        actualValue: Double
    ) {
        val error = if (predictedValue > 0) {
            ((actualValue - predictedValue) / predictedValue)
        } else if (actualValue > 0) {
            1.0 // predicted zero but got something
        } else {
            0.0 // both zero
        }

        val absoluteError = kotlin.math.abs(actualValue - predictedValue)

        Timber.d("FeedbackLoop C: Prediction outcome (%s): predicted=%.0f, actual=%.0f, error=%.2f",
            predictionType, predictedValue, actualValue, error)

        // Store prediction error
        val errorKey = "prediction_error_${predictionType.lowercase()}"
        val existing = knowledgeDao.getEntry("prediction_calibration", errorKey)

        if (existing != null) {
            // Update rolling average error
            val data = try {
                @Suppress("UNCHECKED_CAST")
                gson.fromJson(existing.value, Map::class.java) as Map<String, Any>
            } catch (e: Exception) {
                emptyMap<String, Any>()
            }

            val prevAvgError = (data["avgError"] as? Number)?.toDouble() ?: 0.0
            val sampleCount = (data["sampleCount"] as? Number)?.toInt() ?: 0
            val newAvgError = (prevAvgError * sampleCount + error) / (sampleCount + 1)

            knowledgeDao.update(existing.copy(
                value = gson.toJson(mapOf(
                    "predictionType" to predictionType,
                    "avgError" to newAvgError,
                    "lastError" to error,
                    "lastAbsoluteError" to absoluteError,
                    "sampleCount" to sampleCount + 1,
                    "lastPrediction" to predictedValue,
                    "lastActual" to actualValue,
                    "timestamp" to System.currentTimeMillis()
                )),
                confidence = (1.0 - kotlin.math.abs(newAvgError)).coerceIn(0.0f, 1.0f),
                usageCount = sampleCount + 1,
                updatedAt = System.currentTimeMillis()
            ))
        } else {
            knowledgeDao.insert(
                com.msaidizi.core.model.KnowledgeEntity(
                    category = "prediction_calibration",
                    key = errorKey,
                    value = gson.toJson(mapOf(
                        "predictionType" to predictionType,
                        "avgError" to error,
                        "lastError" to error,
                        "lastAbsoluteError" to absoluteError,
                        "sampleCount" to 1,
                        "lastPrediction" to predictedValue,
                        "lastActual" to actualValue,
                        "timestamp" to System.currentTimeMillis()
                    )),
                    confidence = (1.0 - kotlin.math.abs(error)).coerceIn(0.0f, 1.0f),
                    usageCount = 1
                )
            )
        }

        // If prediction was significantly off, recalibrate
        if (kotlin.math.abs(error) > 0.3) {
            Timber.w("FeedbackLoop C: Prediction significantly off (%.0f%% error) for %s — recalibrating",
                error * 100, predictionType)
            recalibratePrediction(predictionType, error)
        }
    }

    /**
     * Recalibrate prediction weights based on accumulated errors.
     */
    private suspend fun recalibratePrediction(predictionType: String, recentError: Double) {
        // Store recalibration signal
        knowledgeDao.insert(
            com.msaidizi.core.model.KnowledgeEntity(
                category = "prediction_recalibration",
                key = "recal_${predictionType}_${System.currentTimeMillis()}",
                value = gson.toJson(mapOf(
                    "predictionType" to predictionType,
                    "recentError" to recentError,
                    "adjustment" to if (recentError > 0) "predictions_too_low" else "predictions_too_high",
                    "timestamp" to System.currentTimeMillis()
                )),
                confidence = 0.5f,
                usageCount = 0
            )
        )
    }

    /**
     * Get prediction calibration data. Used by CFOEngine to adjust predictions.
     */
    suspend fun getPredictionCalibration(predictionType: String): PredictionCalibration? {
        val key = "prediction_error_${predictionType.lowercase()}"
        val entry = knowledgeDao.getEntry("prediction_calibration", key) ?: return null

        return try {
            @Suppress("UNCHECKED_CAST")
            val data = gson.fromJson(entry.value, Map::class.java) as Map<String, Any>
            PredictionCalibration(
                predictionType = predictionType,
                averageError = (data["avgError"] as? Number)?.toDouble() ?: 0.0,
                sampleCount = (data["sampleCount"] as? Number)?.toInt() ?: 0,
                confidence = entry.confidence,
                bias = if ((data["avgError"] as? Number)?.toDouble() ?: 0.0 > 0) {
                    PredictionBias.TOO_LOW
                } else {
                    PredictionBias.TOO_HIGH
                }
            )
        } catch (e: Exception) {
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LOOP D: Tool Failure → Pattern Learning
    // ═══════════════════════════════════════════════════════════════

    /**
     * Process tool failure signals and update tool selection patterns.
     *
     * When a tool consistently fails, the system learns to:
     *   1. Avoid that tool for similar requests
     *   2. Prefer alternative tools
     *   3. Trigger circuit breaker earlier
     *
     * Called by SelfCorrectionLoop when failures are recorded.
     */
    suspend fun processToolFailure(
        toolName: String,
        errorCode: String,
        context: String
    ) {
        Timber.d("FeedbackLoop D: Tool failure (%s): %s", toolName, errorCode)

        // Get failure stats from SelfCorrectionLoop
        val stats = selfCorrectionLoop.getFailureStats(toolName)

        // If tool has high failure rate, record a negative pattern
        if (stats.recentFailures >= 3) {
            val patternKey = "tool_reliability_${toolName}"
            val existing = knowledgeDao.getEntry("tool_patterns", patternKey)

            val reliability = if (stats.totalFailures > 0) {
                1.0f - (stats.recentFailures.toFloat() / (stats.recentFailures + 5).toFloat())
            } else {
                1.0f
            }

            if (existing != null) {
                knowledgeDao.update(existing.copy(
                    confidence = reliability,
                    usageCount = existing.usageCount + 1,
                    updatedAt = System.currentTimeMillis()
                ))
            } else {
                knowledgeDao.insert(
                    com.msaidizi.core.model.KnowledgeEntity(
                        category = "tool_patterns",
                        key = patternKey,
                        value = gson.toJson(mapOf(
                            "toolName" to toolName,
                            "reliability" to reliability,
                            "recentFailures" to stats.recentFailures,
                            "topErrors" to stats.topErrorCodes,
                            "timestamp" to System.currentTimeMillis()
                        )),
                        confidence = reliability,
                        usageCount = 1
                    )
                )
            }

            // If reliability drops below 30%, suggest circuit breaker activation
            if (reliability < 0.3f) {
                Timber.w("FeedbackLoop D: Tool '%s' reliability critically low (%.2f) — consider circuit breaker",
                    toolName, reliability)
            }
        }
    }

    /**
     * Get tool reliability scores. Used for intelligent tool selection.
     */
    suspend fun getToolReliability(): Map<String, Float> {
        return try {
            knowledgeDao.getByCategory("tool_patterns").first()
                .associate { it.key.removePrefix("tool_reliability_") to it.confidence }
        } catch (e: Exception) {
            Timber.w(e, "FeedbackLoop D: Failed to get tool reliability")
            emptyMap()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CROSS-LOOP SYNTHESIS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get a comprehensive feedback summary across all loops.
     * Used for system health monitoring and debugging.
     */
    suspend fun getFeedbackSummary(): FeedbackSummary {
        val adviceEffectiveness = getAdviceEffectiveness()
        val toolReliability = getToolReliability()
        val circuitStatuses = circuitBreaker.getAllCircuitStatuses()
        val highFailureTools = selfCorrectionLoop.getHighFailureTools()

        return FeedbackSummary(
            adviceEffectivenessScores = adviceEffectiveness,
            toolReliabilityScores = toolReliability,
            circuitBreakerStatuses = circuitStatuses.map { it.toDisplayString() },
            highFailureTools = highFailureTools,
            totalAdvicePatterns = adviceEffectiveness.size,
            totalToolPatterns = toolReliability.size
        )
    }

    /**
     * Run periodic feedback maintenance. Call during heartbeats.
     *
     * - Prune stale feedback data
     * - Recompute rolling averages
     * - Identify patterns that need attention
     */
    suspend fun runMaintenance() {
        Timber.d("FeedbackLoop: Running periodic maintenance")

        // Prune old correction data (keep last 30 days)
        try {
            val cutoff = System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000L)
            knowledgeDao.getByCategory("corrections").first()
                .filter { it.updatedAt < cutoff }
                .forEach { knowledgeDao.delete(it) }
        } catch (e: Exception) {
            Timber.w(e, "FeedbackLoop maintenance: Failed to prune corrections")
        }

        // Log summary
        val summary = getFeedbackSummary()
        Timber.i("FeedbackLoop maintenance: %d advice patterns, %d tool patterns, %d high-failure tools",
            summary.totalAdvicePatterns, summary.totalToolPatterns, summary.highFailureTools.size)
    }
}

// ═══════════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════════

enum class CorrectionType {
    PRODUCT_NAME,    // Worker corrected a product name
    AMOUNT,          // Worker corrected a transaction amount
    CATEGORY,        // Worker corrected a transaction category
    CUSTOMER_NAME,   // Worker corrected a customer name
    TRANSACTION_TYPE // Worker corrected sale vs expense vs purchase
}

enum class AdviceOutcome {
    FOLLOWED_WITHIN_1H,     // Strong positive: advice immediately compelling
    FOLLOWED_WITHIN_24H,    // Positive: advice was relevant and trusted
    FOLLOWED_BUT_REVERSED,  // Weak negative: advice led to bad outcome
    IGNORED_7_DAYS,         // Negative: advice not relevant or not trusted
    EXPLICITLY_DISMISSED,   // Strong negative: advice was wrong
    ASKED_FOLLOWUP          // Engagement: advice sparked interest but unclear
}

enum class PredictionBias {
    TOO_LOW,   // Model consistently under-predicts
    TOO_HIGH   // Model consistently over-predicts
}

data class PredictionCalibration(
    val predictionType: String,
    val averageError: Double,
    val sampleCount: Int,
    val confidence: Float,
    val bias: PredictionBias
)

data class FeedbackSummary(
    val adviceEffectivenessScores: Map<String, Float>,
    val toolReliabilityScores: Map<String, Float>,
    val circuitBreakerStatuses: List<String>,
    val highFailureTools: List<String>,
    val totalAdvicePatterns: Int,
    val totalToolPatterns: Int
)
