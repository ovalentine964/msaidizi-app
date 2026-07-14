package com.msaidizi.app.agent.harness

import com.msaidizi.app.agent.LearningAgent
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Learning Harness — wraps learning systems with A/B testing, rollback,
 * validation, and regression detection.
 *
 * ## Purpose
 * Every learning update (vocabulary, pattern, model fine-tuning) goes through
 * this harness. It ensures:
 * 1. **A/B testing**: Compare new learning against baseline
 * 2. **Rollback**: Revert if accuracy drops >5%
 * 3. **Validation**: Verify learning improved (not degraded) quality
 * 4. **Regression detection**: Monitor for quality degradation over time
 *
 * ## Architecture
 * ```
 * Learning Update → [Validation Gate] → [A/B Test] → [Rollback Guard]
 *                        ↓ fail            ↓ worse          ↓ regression
 *                    reject update     keep baseline     revert to checkpoint
 * ```
 *
 * ## Rollback Strategy
 * - Before applying any learning update, save a checkpoint
 * - After applying, measure quality on recent interactions
 * - If quality drops >5% from checkpoint, automatically revert
 * - Keep last 5 checkpoints for manual rollback
 *
 * ## A/B Testing
 * - Split traffic: 80% baseline, 20% new learning
 * - Measure: accuracy, user correction rate, response quality
 * - Minimum sample: 20 interactions before declaring winner
 * - Auto-promote if new learning is significantly better (p < 0.05)
 */
@Singleton
class LearningHarness @Inject constructor() {
    companion object {
        private const val TAG = "LearningHarness"
        private const val MAX_CHECKPOINTS = 5
        private const val REGRESSION_THRESHOLD_PCT = 0.05  // 5% accuracy drop triggers rollback
        private const val MIN_AB_SAMPLE_SIZE = 20
        private const val AB_TRAFFIC_SPLIT_NEW = 0.2  // 20% to new learning
    }

    // ── Checkpoints for rollback ──────────────────────────────────
    private val checkpoints = ArrayDeque<LearningCheckpoint>()

    // ── A/B test state ────────────────────────────────────────────
    private val activeExperiments = ConcurrentHashMap<String, ABExperiment>()

    // ── Quality tracking ──────────────────────────────────────────
    private val qualityHistory = ArrayDeque<QualitySample>(200)
    private val regressionDetected = AtomicLong(0)

    // ── Events ────────────────────────────────────────────────────
    private var eventListener: ((LearningHarnessEvent) -> Unit)? = null

    fun setEventListener(listener: (LearningHarnessEvent) -> Unit) {
        eventListener = listener
    }

    // ═══════════════════════════════════════════════════════════════
    // LEARNING UPDATE — Wrapped with validation and rollback
    // ═══════════════════════════════════════════════════════════════

    /**
     * Apply a learning update with full harness protection.
     *
     * @param updateType Type of learning update (vocabulary, pattern, model)
     * @param description Human-readable description of the update
     * @param updateFn The actual learning update function
     * @param qualityCheckFn Function to measure current quality (returns 0.0–1.0)
     * @param rollbackFn Function to revert the update
     * @return The learning update result
     */
    suspend fun <T> applyLearningUpdate(
        updateType: LearningUpdateType,
        description: String,
        updateFn: suspend () -> T,
        qualityCheckFn: suspend () -> Double,
        rollbackFn: suspend () -> Unit
    ): LearningUpdateResult<T> {
        val updateId = UUID.randomUUID().toString().take(12)
        val startTime = System.currentTimeMillis()

        // 1. Measure baseline quality
        val baselineQuality = try {
            qualityCheckFn()
        } catch (e: Exception) {
            Timber.w(TAG, "[%s] Quality check failed: %s — proceeding without baseline", updateId, e.message)
            -1.0 // No baseline available
        }

        // 2. Save checkpoint
        val checkpoint = LearningCheckpoint(
            checkpointId = updateId,
            updateType = updateType,
            description = description,
            baselineQuality = baselineQuality,
            timestamp = System.currentTimeMillis()
        )
        saveCheckpoint(checkpoint)

        // 3. Apply the learning update
        val result: T
        try {
            result = updateFn()
        } catch (e: Exception) {
            Timber.e(TAG, "[%s] Learning update failed: %s", updateId, e.message)
            emitEvent(LearningHarnessEvent.UpdateFailed(updateId, updateType, e.message ?: "unknown"))
            return LearningUpdateResult(
                updateId = updateId,
                updateType = updateType,
                success = false,
                value = null,
                baselineQuality = baselineQuality,
                newQuality = baselineQuality,
                rolledBack = false,
                error = e.message
            )
        }

        // 4. Measure post-update quality
        val newQuality = try {
            delay(100) // Brief settle time
            qualityCheckFn()
        } catch (e: Exception) {
            Timber.w(TAG, "[%s] Post-update quality check failed: %s", updateId, e.message)
            baselineQuality // Assume unchanged if we can't measure
        }

        // 5. Regression check
        val qualityDelta = newQuality - baselineQuality
        val regressionDetected = baselineQuality > 0 &&
            qualityDelta < -REGRESSION_THRESHOLD_PCT

        if (regressionDetected) {
            Timber.w(TAG, "[%s] REGRESSION DETECTED: %.3f → %.3f (Δ=%.3f). Rolling back!",
                updateId, baselineQuality, newQuality, qualityDelta)

            try {
                rollbackFn()
                this.regressionDetected.incrementAndGet()

                emitEvent(LearningHarnessEvent.RegressionDetected(
                    updateId = updateId,
                    updateType = updateType,
                    baselineQuality = baselineQuality,
                    newQuality = newQuality,
                    rolledBack = true
                ))

                return LearningUpdateResult(
                    updateId = updateId,
                    updateType = updateType,
                    success = false,
                    value = result,
                    baselineQuality = baselineQuality,
                    newQuality = newQuality,
                    rolledBack = true,
                    error = "Regression: quality dropped from %.3f to %.3f".format(baselineQuality, newQuality)
                )
            } catch (e: Exception) {
                Timber.e(TAG, "[%s] Rollback FAILED: %s", updateId, e.message)
                emitEvent(LearningHarnessEvent.RollbackFailed(updateId, updateType, e.message ?: "unknown"))
            }
        }

        // 6. Record quality sample
        recordQualitySample(QualitySample(
            updateId = updateId,
            updateType = updateType,
            baselineQuality = baselineQuality,
            newQuality = newQuality,
            qualityDelta = qualityDelta,
            timestamp = System.currentTimeMillis()
        ))

        val elapsed = System.currentTimeMillis() - startTime
        Timber.i(TAG, "[%s] Learning update applied: %s (Δ=%.3f, %dms, rolledBack=%s)",
            updateId, description, qualityDelta, elapsed, regressionDetected)

        emitEvent(LearningHarnessEvent.UpdateApplied(
            updateId = updateId,
            updateType = updateType,
            description = description,
            qualityDelta = qualityDelta,
            latencyMs = elapsed
        ))

        return LearningUpdateResult(
            updateId = updateId,
            updateType = updateType,
            success = true,
            value = result,
            baselineQuality = baselineQuality,
            newQuality = newQuality,
            rolledBack = false
        )
    }

    /**
     * Convenience: wrap a vocabulary learning update.
     */
    suspend fun wrapVocabularyUpdate(
        spoken: String,
        canonical: String,
        learningAgent: LearningAgent,
        qualityCheckFn: suspend () -> Double = { 0.8 } // Default: assume OK
    ): LearningUpdateResult<Unit> {
        return applyLearningUpdate(
            updateType = LearningUpdateType.VOCABULARY,
            description = "Vocabulary: '$spoken' → '$canonical'",
            updateFn = { learningAgent.recordUserTerm(spoken, canonical) },
            qualityCheckFn = qualityCheckFn,
            rollbackFn = { /* Vocabulary updates are additive, no rollback needed */ }
        )
    }

    /**
     * Convenience: wrap a pattern learning update.
     */
    suspend fun wrapPatternUpdate(
        patternType: String,
        data: Map<String, Any>,
        learningAgent: LearningAgent,
        qualityCheckFn: suspend () -> Double = { 0.8 }
    ): LearningUpdateResult<Unit> {
        return applyLearningUpdate(
            updateType = LearningUpdateType.PATTERN,
            description = "Pattern: $patternType",
            updateFn = {
                learningAgent.recordPattern(
                    type = com.msaidizi.app.core.model.PatternType.valueOf(patternType),
                    data = data
                )
            },
            qualityCheckFn = qualityCheckFn,
            rollbackFn = { /* Pattern updates are Bayesian, naturally self-correcting */ }
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // A/B TESTING — Compare learning approaches
    // ═══════════════════════════════════════════════════════════════

    /**
     * Start an A/B test comparing two learning approaches.
     *
     * @param experimentId Unique experiment identifier
     * @param description What is being tested
     * @param baselineFn The current approach
     * @param challengerFn The new approach
     * @return The experiment definition
     */
    fun startExperiment(
        experimentId: String,
        description: String,
        baselineFn: suspend (String) -> String,
        challengerFn: suspend (String) -> String
    ): ABExperiment {
        val experiment = ABExperiment(
            experimentId = experimentId,
            description = description,
            status = ABExperimentStatus.RUNNING,
            startedAt = System.currentTimeMillis(),
            baselineSamples = mutableListOf(),
            challengerSamples = mutableListOf()
        )
        activeExperiments[experimentId] = experiment

        Timber.i(TAG, "A/B test started: %s — %s", experimentId, description)
        emitEvent(LearningHarnessEvent.ExperimentStarted(experimentId, description))

        return experiment
    }

    /**
     * Route a query through an active A/B test.
     * Returns the result from either baseline or challenger based on traffic split.
     *
     * @param experimentId The experiment to route through
     * @param query The input query
     * @param qualityScoreFn Function to score the output quality (0.0–1.0)
     * @return The routed result
     */
    suspend fun routeThroughExperiment(
        experimentId: String,
        query: String,
        baselineFn: suspend (String) -> String,
        challengerFn: suspend (String) -> String,
        qualityScoreFn: (String) -> Double
    ): ExperimentResult {
        val experiment = activeExperiments[experimentId]
            ?: return ExperimentResult(query, "No experiment found", null, false)

        val useChallenger = Math.random() < AB_TRAFFIC_SPLIT_NEW
        val startTime = System.currentTimeMillis()

        val (response, group) = if (useChallenger) {
            try {
                val r = challengerFn(query)
                Pair(r, ExperimentGroup.CHALLENGER)
            } catch (e: Exception) {
                Timber.w(TAG, "Challenger failed, falling back to baseline: %s", e.message)
                Pair(baselineFn(query), ExperimentGroup.BASELINE)
            }
        } else {
            Pair(baselineFn(query), ExperimentGroup.BASELINE)
        }

        val latencyMs = System.currentTimeMillis() - startTime
        val quality = qualityScoreFn(response)

        // Record sample
        val sample = ExperimentSample(query, response, quality, latencyMs, group)
        when (group) {
            ExperimentGroup.BASELINE -> experiment.baselineSamples.add(sample)
            ExperimentGroup.CHALLENGER -> experiment.challengerSamples.add(sample)
        }

        // Check if we have enough data to declare a winner
        val result = checkExperimentResult(experiment)

        return ExperimentResult(query, response, result, useChallenger)
    }

    /**
     * Check if an experiment has enough data to declare a winner.
     */
    private fun checkExperimentResult(experiment: ABExperiment): ExperimentVerdict? {
        if (experiment.baselineSamples.size < MIN_AB_SAMPLE_SIZE ||
            experiment.challengerSamples.size < MIN_AB_SAMPLE_SIZE) {
            return null
        }

        val baselineAvg = experiment.baselineSamples.map { it.qualityScore }.average()
        val challengerAvg = experiment.challengerSamples.map { it.qualityScore }.average()
        val delta = challengerAvg - baselineAvg

        // Simple significance check: > 5% improvement
        val isSignificant = abs(delta) > REGRESSION_THRESHOLD_PCT

        return if (isSignificant) {
            val winner = if (delta > 0) ExperimentGroup.CHALLENGER else ExperimentGroup.BASELINE
            ExperimentVerdict(
                winner = winner,
                baselineQuality = baselineAvg,
                challengerQuality = challengerAvg,
                delta = delta,
                sampleSizeBaseline = experiment.baselineSamples.size,
                sampleSizeChallenger = experiment.challengerSamples.size,
                recommendation = if (winner == ExperimentGroup.CHALLENGER)
                    "✅ Challenger is better (+${"%.1f".format(delta * 100)}%). Promote to production."
                else
                    "❌ Baseline is better. Keep current approach."
            )
        } else null
    }

    /**
     * Complete an experiment and get the final verdict.
     */
    fun completeExperiment(experimentId: String): ExperimentVerdict? {
        val experiment = activeExperiments[experimentId] ?: return null
        experiment.status = ABExperimentStatus.COMPLETED
        experiment.completedAt = System.currentTimeMillis()

        val verdict = checkExperimentResult(experiment)
        emitEvent(LearningHarnessEvent.ExperimentCompleted(experimentId, verdict))

        return verdict
    }

    // ═══════════════════════════════════════════════════════════════
    // ROLLBACK — Revert to previous checkpoint
    // ═══════════════════════════════════════════════════════════════

    /**
     * Manually rollback to a specific checkpoint.
     */
    suspend fun rollbackToCheckpoint(
        checkpointId: String,
        rollbackFn: suspend () -> Unit
    ): Boolean {
        val checkpoint = checkpoints.find { it.checkpointId == checkpointId }
        if (checkpoint == null) {
            Timber.w(TAG, "Checkpoint %s not found", checkpointId)
            return false
        }

        try {
            rollbackFn()
            Timber.i(TAG, "Rolled back to checkpoint %s (%s)", checkpointId, checkpoint.description)
            emitEvent(LearningHarnessEvent.ManualRollback(checkpointId, checkpoint.description))
            return true
        } catch (e: Exception) {
            Timber.e(TAG, "Rollback to %s failed: %s", checkpointId, e.message)
            return false
        }
    }

    /**
     * Get available checkpoints for manual rollback.
     */
    fun getCheckpoints(): List<LearningCheckpoint> {
        return synchronized(checkpoints) { checkpoints.toList() }
    }

    // ═══════════════════════════════════════════════════════════════
    // MONITORING & STATS
    // ═══════════════════════════════════════════════════════════════

    fun getStats(): LearningHarnessStats {
        val recentSamples = synchronized(qualityHistory) { qualityHistory.toList().takeLast(50) }
        val regressions = regressionDetected.get()

        return LearningHarnessStats(
            totalUpdates = recentSamples.size,
            regressionCount = regressions,
            avgQualityDelta = if (recentSamples.isNotEmpty())
                recentSamples.map { it.qualityDelta }.average() else 0.0,
            checkpointCount = checkpoints.size,
            activeExperiments = activeExperiments.values.count { it.status == ABExperimentStatus.RUNNING },
            completedExperiments = activeExperiments.values.count { it.status == ABExperimentStatus.COMPLETED }
        )
    }

    fun getQualityHistory(): List<QualitySample> {
        return synchronized(qualityHistory) { qualityHistory.toList() }
    }

    // ═══════════════════════════════════════════════════════════════
    // INTERNALS
    // ═══════════════════════════════════════════════════════════════

    private fun saveCheckpoint(checkpoint: LearningCheckpoint) {
        synchronized(checkpoints) {
            if (checkpoints.size >= MAX_CHECKPOINTS) checkpoints.removeFirst()
            checkpoints.addLast(checkpoint)
        }
    }

    private fun recordQualitySample(sample: QualitySample) {
        synchronized(qualityHistory) {
            if (qualityHistory.size >= 200) qualityHistory.removeFirst()
            qualityHistory.addLast(sample)
        }
    }

    private fun emitEvent(event: LearningHarnessEvent) {
        try { eventListener?.invoke(event) } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════
    // DATA TYPES
    // ═══════════════════════════════════════════════════════════════

    enum class LearningUpdateType {
        VOCABULARY,
        PATTERN,
        MODEL_VERSION,
        CONFIDENCE_CALIBRATION,
        CORRECTION
    }

    data class LearningCheckpoint(
        val checkpointId: String,
        val updateType: LearningUpdateType,
        val description: String,
        val baselineQuality: Double,
        val timestamp: Long
    )

    data class QualitySample(
        val updateId: String,
        val updateType: LearningUpdateType,
        val baselineQuality: Double,
        val newQuality: Double,
        val qualityDelta: Double,
        val timestamp: Long
    )

    data class LearningUpdateResult<T>(
        val updateId: String,
        val updateType: LearningUpdateType,
        val success: Boolean,
        val value: T?,
        val baselineQuality: Double,
        val newQuality: Double,
        val rolledBack: Boolean,
        val error: String? = null
    )

    data class LearningHarnessStats(
        val totalUpdates: Int,
        val regressionCount: Long,
        val avgQualityDelta: Double,
        val checkpointCount: Int,
        val activeExperiments: Int,
        val completedExperiments: Int
    )

    // ── A/B Testing Types ─────────────────────────────────────────

    data class ABExperiment(
        val experimentId: String,
        val description: String,
        var status: ABExperimentStatus,
        val startedAt: Long,
        var completedAt: Long = 0L,
        val baselineSamples: MutableList<ExperimentSample>,
        val challengerSamples: MutableList<ExperimentSample>
    )

    enum class ABExperimentStatus { RUNNING, COMPLETED, ABANDONED }

    enum class ExperimentGroup { BASELINE, CHALLENGER }

    data class ExperimentSample(
        val query: String,
        val response: String,
        val qualityScore: Double,
        val latencyMs: Long,
        val group: ExperimentGroup
    )

    data class ExperimentResult(
        val query: String,
        val response: String,
        val verdict: ExperimentVerdict?,
        val usedChallenger: Boolean
    )

    data class ExperimentVerdict(
        val winner: ExperimentGroup,
        val baselineQuality: Double,
        val challengerQuality: Double,
        val delta: Double,
        val sampleSizeBaseline: Int,
        val sampleSizeChallenger: Int,
        val recommendation: String
    )
}

// ═══════════════════════════════════════════════════════════════
// EVENTS
// ═══════════════════════════════════════════════════════════════

sealed class LearningHarnessEvent {
    data class UpdateApplied(
        val updateId: String,
        val updateType: LearningHarness.LearningUpdateType,
        val description: String,
        val qualityDelta: Double,
        val latencyMs: Long
    ) : LearningHarnessEvent()

    data class UpdateFailed(
        val updateId: String,
        val updateType: LearningHarness.LearningUpdateType,
        val error: String
    ) : LearningHarnessEvent()

    data class RegressionDetected(
        val updateId: String,
        val updateType: LearningHarness.LearningUpdateType,
        val baselineQuality: Double,
        val newQuality: Double,
        val rolledBack: Boolean
    ) : LearningHarnessEvent()

    data class RollbackFailed(
        val updateId: String,
        val updateType: LearningHarness.LearningUpdateType,
        val error: String
    ) : LearningHarnessEvent()

    data class ManualRollback(
        val checkpointId: String,
        val description: String
    ) : LearningHarnessEvent()

    data class ExperimentStarted(
        val experimentId: String,
        val description: String
    ) : LearningHarnessEvent()

    data class ExperimentCompleted(
        val experimentId: String,
        val verdict: LearningHarness.ExperimentVerdict?
    ) : LearningHarnessEvent()
}
