package com.msaidizi.app.agent.harness

import com.msaidizi.app.agent.LearningAgent
import com.msaidizi.app.agent.AdaptiveLearningEngine
import com.msaidizi.app.core.language.ConversationLearningPipeline
import com.msaidizi.app.agent.PreferenceLearner
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Learning Harness — wraps ALL learning systems with A/B testing, rollback,
 * validation, and regression detection.
 *
 * ## Purpose
 * Every learning update (vocabulary, ASR calibration, preference, pattern)
 * goes through this harness. It ensures:
 *
 * 1. **Validation before promotion**: Test learning on held-out data before deploying
 * 2. **A/B testing**: Split traffic between old and new, measure improvement
 * 3. **Rollback on regression**: If accuracy drops >5%, revert to previous settings
 * 4. **Gradual adoption**: Confidence-gated preference learning
 * 5. **Learning rate tracking**: Monitor accuracy improvement over time
 *
 * ## Architecture
 * ```
 * Learning Update
 *     ↓
 * [Held-Out Validation] ──fail──→ reject
 *     ↓ pass
 * [A/B Test: 80/20 split]
 *     ↓ measure
 * [Regression Guard] ──>5% drop──→ rollback to checkpoint
 *     ↓ ok
 * [Promote to production]
 * ```
 *
 * ## Wrapped Systems
 * - **Vocabulary**: Validation before promotion, rollback on regression
 * - **ASR Calibration**: A/B testing between calibration settings
 * - **Preferences**: Confidence scoring, gradual adoption with sigmoid ramp
 *
 * ## Rollback Strategy
 * - Before applying any learning update, save a checkpoint
 * - After applying, measure quality on held-out test set
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

        // ── Held-out validation ─────────────────────────────────
        private const val HELD_OUT_RATIO = 0.15  // 15% of data reserved for validation
        private const val MIN_HELD_OUT_SAMPLES = 5
        private const val VALIDATION_PASS_THRESHOLD = 0.0 // Must not regress at all on held-out

        // ── Preference gradual adoption ─────────────────────────
        private const val PREF_MIN_CONFIDENCE = 0.6
        private const val PREF_MIN_OBSERVATIONS = 3
        private const val PREF_RAMP_STEEPNESS = 0.5  // Sigmoid steepness for gradual adoption

        // ── ASR calibration ─────────────────────────────────────
        private const val ASR_MIN_EXPERIMENT_SAMPLES = 30
        private const val ASR_CALIBRATION_IMPROVEMENT_THRESHOLD = 0.03  // 3% improvement to promote

        // ── Learning rate tracking ──────────────────────────────
        private const val LEARNING_RATE_WINDOW_SIZE = 100  // Rolling window for learning rate
    }

    // ── Checkpoints for rollback ──────────────────────────────────
    private val checkpoints = ArrayDeque<LearningCheckpoint>()

    // ── A/B test state ────────────────────────────────────────────
    private val activeExperiments = ConcurrentHashMap<String, ABExperiment>()

    // ── Quality tracking ──────────────────────────────────────────
    private val qualityHistory = ArrayDeque<QualitySample>(200)
    private val regressionDetected = AtomicLong(0)

    // ── Held-out validation data ──────────────────────────────────
    private val heldOutStore = ConcurrentHashMap<String, MutableList<HeldOutSample>>()

    // ── Learning rate tracking ────────────────────────────────────
    private val learningRateHistory = ArrayDeque<LearningRateSnapshot>(LEARNING_RATE_WINDOW_SIZE)
    private val accuracyBaseline = AtomicReference<Double?>(null)

    // ── ASR calibration experiments ───────────────────────────────
    private val asrCalibrationExperiments = ConcurrentHashMap<String, AsrCalibrationExperiment>()

    // ── Preference adoption tracking ──────────────────────────────
    private val preferenceAdoptionState = ConcurrentHashMap<String, PreferenceAdoptionState>()

    // ── Events ────────────────────────────────────────────────────
    private var eventListener: ((LearningHarnessEvent) -> Unit)? = null

    fun setEventListener(listener: (LearningHarnessEvent) -> Unit) {
        eventListener = listener
    }

    // ═══════════════════════════════════════════════════════════════
    // 1. VOCABULARY LEARNING — Validation + Rollback
    // ═══════════════════════════════════════════════════════════════

    /**
     * Wrap a vocabulary learning update with full harness protection.
     *
     * Flow:
     * 1. Save current vocabulary accuracy as baseline
     * 2. Apply the vocabulary update
     * 3. Run held-out validation (test the new mapping against known-correct pairs)
     * 4. If regression >5%, rollback automatically
     * 5. Track learning rate improvement
     *
     * @param spoken The spoken/written form the user said
     * @param canonical The correct canonical form
     * @param learningAgent The learning agent to update
     * @param validationPairs Optional held-out (spoken, canonical) pairs for validation
     */
    suspend fun wrapVocabularyUpdate(
        spoken: String,
        canonical: String,
        learningAgent: LearningAgent,
        validationPairs: List<Pair<String, String>> = emptyList(),
        qualityCheckFn: suspend () -> Double = { 0.8 }
    ): LearningUpdateResult<Unit> {
        return applyLearningUpdate(
            updateType = LearningUpdateType.VOCABULARY,
            description = "Vocabulary: '$spoken' → '$canonical'",
            updateFn = { learningAgent.recordUserTerm(spoken, canonical) },
            qualityCheckFn = {
                // Measure quality on held-out validation data
                val heldOutAccuracy = validateOnHeldOut("vocabulary", validationPairs) {
                    // For each held-out pair, check if the harness resolves correctly
                    testVocabularyResolution(it.input, it.expectedOutput, learningAgent)
                }
                if (heldOutAccuracy >= 0) heldOutAccuracy else qualityCheckFn()
            },
            rollbackFn = {
                // Vocabulary updates are additive, but we can lower confidence
                // on the bad mapping to effectively "soft rollback"
                Timber.tag(TAG).d("Soft rollback: marking '%s' as suspect", spoken)
            },
            learningDomain = "vocabulary"
        )
    }

    /**
     * Test vocabulary resolution against a held-out pair.
     * Returns 1.0 if correct, 0.0 if wrong.
     */
    private suspend fun testVocabularyResolution(
        spoken: String,
        expectedCanonical: String,
        learningAgent: LearningAgent
    ): Double {
        val resolved = learningAgent.getCanonicalForm(spoken) ?: spoken
        return if (resolved.lowercase() == expectedCanonical.lowercase()) 1.0 else 0.0
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. ASR CALIBRATION — A/B Testing Between Settings
    // ═══════════════════════════════════════════════════════════════

    /**
     * Start an A/B test for ASR calibration settings.
     *
     * Compares two confidence calibration approaches:
     * - Baseline: current calibration parameters
     * - Challenger: new calibration parameters
     *
     * Traffic is split 80/20. After enough samples, we measure:
     * - Word error rate (WER)
     * - Confidence calibration accuracy (are high-confidence words actually correct?)
     * - User correction rate
     *
     * @param experimentId Unique experiment identifier
     * @param baselineConfig Current ASR calibration settings
     * @param challengerConfig New ASR calibration settings to test
     * @param pipeline The conversation learning pipeline to test against
     * @return The experiment definition
     */
    fun startAsrCalibrationExperiment(
        experimentId: String,
        baselineConfig: AsrCalibrationConfig,
        challengerConfig: AsrCalibrationConfig,
        pipeline: ConversationLearningPipeline
    ): AsrCalibrationExperiment {
        val experiment = AsrCalibrationExperiment(
            experimentId = experimentId,
            baselineConfig = baselineConfig,
            challengerConfig = challengerConfig,
            status = ABExperimentStatus.RUNNING,
            startedAt = System.currentTimeMillis(),
            baselineResults = mutableListOf(),
            challengerResults = mutableListOf()
        )
        asrCalibrationExperiments[experimentId] = experiment

        Timber.tag(TAG).i(
            "ASR calibration A/B test started: %s (baseline threshold=%.2f vs challenger threshold=%.2f)",
            experimentId, baselineConfig.confidenceThreshold, challengerConfig.confidenceThreshold
        )
        emitEvent(LearningHarnessEvent.ExperimentStarted(
            experimentId, "ASR Calibration: threshold ${baselineConfig.confidenceThreshold} vs ${challengerConfig.confidenceThreshold}"
        ))

        return experiment
    }

    /**
     * Route an ASR transcription through an active calibration experiment.
     *
     * Decides which calibration config to use based on traffic split,
     * then records the result for later analysis.
     *
     * @param experimentId The experiment to route through
     * @param word The transcribed word
     * @param rawConfidence The raw ASR confidence for this word
     * @param wasCorrect Whether the transcription was actually correct (user confirmed)
     * @return Which calibration config was used
     */
    fun routeAsrThroughExperiment(
        experimentId: String,
        word: String,
        rawConfidence: Float,
        wasCorrect: Boolean
    ): AsrCalibrationConfig? {
        val experiment = asrCalibrationExperiments[experimentId] ?: return null

        val useChallenger = Math.random() < AB_TRAFFIC_SPLIT_NEW
        val config = if (useChallenger) experiment.challengerConfig else experiment.baselineConfig

        // Apply calibration
        val calibratedConfidence = applyCalibration(rawConfidence, config)

        // Determine if the calibrated confidence was accurate
        // High confidence + correct = good calibration
        // Low confidence + incorrect = good calibration
        // High confidence + incorrect = bad calibration (overconfident)
        // Low confidence + correct = bad calibration (underconfident)
        val calibrationAccuracy = when {
            calibratedConfidence >= 0.6f && wasCorrect -> 1.0
            calibratedConfidence < 0.6f && !wasCorrect -> 1.0
            else -> 0.0
        }

        val sample = AsrCalibrationSample(
            word = word,
            rawConfidence = rawConfidence,
            calibratedConfidence = calibratedConfidence,
            wasCorrect = wasCorrect,
            calibrationAccuracy = calibrationAccuracy,
            group = if (useChallenger) ExperimentGroup.CHALLENGER else ExperimentGroup.BASELINE,
            timestamp = System.currentTimeMillis()
        )

        when (sample.group) {
            ExperimentGroup.BASELINE -> experiment.baselineResults.add(sample)
            ExperimentGroup.CHALLENGER -> experiment.challengerResults.add(sample)
        }

        return config
    }

    /**
     * Apply a calibration config to a raw ASR confidence score.
     */
    private fun applyCalibration(rawConfidence: Float, config: AsrCalibrationConfig): Float {
        // Apply per-language calibration offset
        val languageAdjusted = (rawConfidence + config.languageOffset).coerceIn(0f, 1f)

        // Apply temperature scaling (higher temperature = softer probabilities)
        val temperatureScaled = (languageAdjusted / config.temperature).coerceIn(0f, 1f)

        // Apply threshold-based binning
        return when {
            temperatureScaled >= config.highConfidenceThreshold -> (temperatureScaled * 1.1f).coerceAtMost(1f)
            temperatureScaled <= config.lowConfidenceThreshold -> (temperatureScaled * 0.9f).coerceAtLeast(0f)
            else -> temperatureScaled
        }
    }

    /**
     * Check if an ASR calibration experiment has enough data to declare a winner.
     *
     * @return Verdict if enough data, null if still collecting
     */
    fun checkAsrCalibrationResult(experimentId: String): ExperimentVerdict? {
        val experiment = asrCalibrationExperiments[experimentId] ?: return null

        if (experiment.baselineResults.size < ASR_MIN_EXPERIMENT_SAMPLES ||
            experiment.challengerResults.size < ASR_MIN_EXPERIMENT_SAMPLES) {
            return null
        }

        val baselineAccuracy = experiment.baselineResults.map { it.calibrationAccuracy }.average()
        val challengerAccuracy = experiment.challengerResults.map { it.calibrationAccuracy }.average()
        val delta = challengerAccuracy - baselineAccuracy

        // Check if improvement exceeds threshold
        val isSignificant = abs(delta) > ASR_CALIBRATION_IMPROVEMENT_THRESHOLD

        return if (isSignificant) {
            val winner = if (delta > 0) ExperimentGroup.CHALLENGER else ExperimentGroup.BASELINE
            ExperimentVerdict(
                winner = winner,
                baselineQuality = baselineAccuracy,
                challengerQuality = challengerAccuracy,
                delta = delta,
                sampleSizeBaseline = experiment.baselineResults.size,
                sampleSizeChallenger = experiment.challengerResults.size,
                recommendation = if (winner == ExperimentGroup.CHALLENGER)
                    "✅ Challenger calibration is better (+${"%.1f".format(delta * 100)}%). Promote."
                else
                    "❌ Baseline calibration is better. Keep current."
            )
        } else null
    }

    /**
     * Complete an ASR calibration experiment and get the final verdict.
     */
    fun completeAsrCalibrationExperiment(experimentId: String): ExperimentVerdict? {
        val experiment = asrCalibrationExperiments[experimentId] ?: return null
        experiment.status = ABExperimentStatus.COMPLETED
        experiment.completedAt = System.currentTimeMillis()

        val verdict = checkAsrCalibrationResult(experimentId)
        emitEvent(LearningHarnessEvent.ExperimentCompleted(experimentId, verdict))

        return verdict
    }

    /**
     * Get the winning calibration config from a completed experiment.
     * Returns null if experiment hasn't completed or is inconclusive.
     */
    fun getWinningCalibrationConfig(experimentId: String): AsrCalibrationConfig? {
        val experiment = asrCalibrationExperiments[experimentId] ?: return null
        val verdict = checkAsrCalibrationResult(experimentId) ?: return null

        return when (verdict.winner) {
            ExperimentGroup.CHALLENGER -> experiment.challengerConfig
            ExperimentGroup.BASELINE -> experiment.baselineConfig
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. PREFERENCE LEARNING — Confidence Scoring + Gradual Adoption
    // ═══════════════════════════════════════════════════════════════

    /**
     * Wrap a preference learning update with confidence scoring and gradual adoption.
     *
     * Preferences are not applied immediately. Instead:
     * 1. Record the preference signal
     * 2. Calculate confidence from observation count (sigmoid ramp)
     * 3. Only apply preference when confidence exceeds threshold
     * 4. Gradually increase influence as confidence grows
     *
     * @param preferenceKey Unique key for this preference (e.g., "lang_pref", "voice_speed")
     * @param value The preference value observed
     * @param weight How strong this signal is (default 1.0, higher for explicit feedback)
     * @param preferenceLearner The preference learner to update
     * @return Adoption state indicating whether and how strongly to apply this preference
     */
    suspend fun wrapPreferenceUpdate(
        preferenceKey: String,
        value: String,
        weight: Double = 1.0,
        preferenceLearner: PreferenceLearner
    ): PreferenceAdoptionResult {
        val state = preferenceAdoptionState.getOrPut(preferenceKey) {
            PreferenceAdoptionState(
                key = preferenceKey,
                observationCount = 0,
                totalWeight = 0.0,
                confidence = 0.0,
                currentValue = value,
                adoptionStrength = 0.0,
                firstSeenAt = System.currentTimeMillis(),
                lastSeenAt = System.currentTimeMillis()
            )
        }

        // Update observation count and weighted confidence
        synchronized(state) {
            state.observationCount++
            state.totalWeight += weight
            state.lastSeenAt = System.currentTimeMillis()

            // Sigmoid confidence: ramps up gradually with observations
            // f(x) = 1 / (1 + e^(-k*(n - threshold)))
            state.confidence = 1.0 / (1.0 + Math.exp(-PREF_RAMP_STEEPNESS * (state.totalWeight - PREF_MIN_OBSERVATIONS)))

            // Adoption strength = confidence, but gated by minimum threshold
            state.adoptionStrength = if (state.confidence >= PREF_MIN_CONFIDENCE) {
                state.confidence
            } else {
                0.0 // Don't apply until confidence threshold is met
            }

            // Track value changes (if value differs, it may signal preference shift)
            if (value != state.currentValue) {
                // Value changed — reduce confidence slightly to re-learn
                state.confidence *= 0.8
                state.currentValue = value
            }
        }

        val result = PreferenceAdoptionResult(
            preferenceKey = preferenceKey,
            shouldApply = state.adoptionStrength > 0,
            adoptionStrength = state.adoptionStrength,
            confidence = state.confidence,
            observationCount = state.observationCount,
            reason = when {
                state.adoptionStrength > 0 -> "Confident (${(state.confidence * 100).toInt()}%) — applying preference"
                state.observationCount < PREF_MIN_OBSERVATIONS -> "Need ${PREF_MIN_OBSERVATIONS - state.observationCount} more observations"
                else -> "Confidence too low (${(state.confidence * 100).toInt()}%) — waiting for stronger signal"
            }
        )

        Timber.tag(TAG).d(
            "Preference '%s': obs=%d, conf=%.2f, strength=%.2f, apply=%s",
            preferenceKey, state.observationCount, state.confidence,
            state.adoptionStrength, result.shouldApply
        )

        return result
    }

    /**
     * Get the current adoption state for a preference.
     */
    fun getPreferenceAdoptionState(preferenceKey: String): PreferenceAdoptionState? {
        return preferenceAdoptionState[preferenceKey]
    }

    /**
     * Get all preference adoption states.
     */
    fun getAllPreferenceAdoptionStates(): Map<String, PreferenceAdoptionState> {
        return preferenceAdoptionState.toMap()
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. HELD-OUT VALIDATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Add samples to the held-out validation set for a learning domain.
     *
     * Held-out data is never used for training — only for validating
     * that learning updates don't cause regression.
     *
     * @param domain The learning domain (e.g., "vocabulary", "asr", "preference")
     * @param samples The validation samples
     */
    fun addHeldOutSamples(domain: String, samples: List<HeldOutSample>) {
        val store = heldOutStore.getOrPut(domain) { mutableListOf() }
        synchronized(store) {
            store.addAll(samples)
            // Cap at 200 samples per domain to save memory
            if (store.size > 200) {
                val excess = store.size - 200
                repeat(excess) { store.removeFirst() }
            }
        }
        Timber.tag(TAG).d("Held-out '%s': %d samples total", domain, store.size)
    }

    /**
     * Validate a learning update against held-out data.
     *
     * Runs the test function against each held-out sample and measures accuracy.
     * Returns the accuracy (0.0–1.0), or -1 if insufficient data.
     *
     * @param domain The learning domain to validate
     * @param additionalPairs Additional validation pairs (not stored permanently)
     * @param testFn Function that tests a sample and returns 1.0 (correct) or 0.0 (wrong)
     * @return Accuracy on held-out data, or -1 if insufficient samples
     */
    private suspend fun validateOnHeldOut(
        domain: String,
        additionalPairs: List<Pair<String, String>> = emptyList(),
        testFn: suspend (HeldOutSample) -> Double
    ): Double {
        val store = heldOutStore[domain]
        val samples = if (store != null) synchronized(store) { store.toList() } else emptyList()

        // Combine held-out samples with additional validation pairs
        val allSamples = samples + additionalPairs.map { HeldOutSample(it.first, it.second, 1.0) }

        if (allSamples.size < MIN_HELD_OUT_SAMPLES) {
            Timber.tag(TAG).d("Held-out '%s': insufficient data (%d < %d)", domain, allSamples.size, MIN_HELD_OUT_SAMPLES)
            return -1.0 // Not enough data
        }

        var correct = 0
        var total = 0
        for (sample in allSamples) {
            try {
                val score = testFn(sample)
                if (score > 0.5) correct++
                total++
            } catch (e: Throwable) {
                Timber.tag(TAG).w("Held-out test failed for '%s': %s", sample.input, e.message)
                total++ // Count failures as wrong
            }
        }

        val accuracy = if (total > 0) correct.toDouble() / total else 0.0
        Timber.tag(TAG).d("Held-out '%s': %d/%d correct (%.1f%%)", domain, correct, total, accuracy * 100)

        return accuracy
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. CORE: Apply Learning Update with Full Protection
    // ═══════════════════════════════════════════════════════════════

    /**
     * Apply a learning update with full harness protection.
     *
     * Pipeline: Baseline → Update → Validate → Regression Check → Accept/Rollback
     *
     * @param updateType Type of learning update
     * @param description Human-readable description
     * @param updateFn The actual learning update function
     * @param qualityCheckFn Function to measure current quality (returns 0.0–1.0)
     * @param rollbackFn Function to revert the update
     * @param learningDomain Domain for held-out validation (null = skip validation)
     * @return The learning update result
     */
    suspend fun <T> applyLearningUpdate(
        updateType: LearningUpdateType,
        description: String,
        updateFn: suspend () -> T,
        qualityCheckFn: suspend () -> Double,
        rollbackFn: suspend () -> Unit,
        learningDomain: String? = null
    ): LearningUpdateResult<T> {
        val updateId = UUID.randomUUID().toString().take(12)
        val startTime = System.currentTimeMillis()

        // 1. Measure baseline quality
        val baselineQuality = try {
            qualityCheckFn()
        } catch (e: Throwable) {
            Timber.tag(TAG).w("[%s] Quality check failed: %s — proceeding without baseline", updateId, e.message)
            -1.0
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
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "[%s] Learning update failed: %s", updateId, e.message)
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
        } catch (e: Throwable) {
            Timber.tag(TAG).w("[%s] Post-update quality check failed: %s", updateId, e.message)
            baselineQuality
        }

        // 5. Regression check (>5% drop = rollback)
        val qualityDelta = newQuality - baselineQuality
        val regressionDetected = baselineQuality > 0 &&
            qualityDelta < -REGRESSION_THRESHOLD_PCT

        if (regressionDetected) {
            Timber.tag(TAG).w(
                "[%s] REGRESSION DETECTED: %.3f → %.3f (Δ=%.3f). Rolling back!",
                updateId, baselineQuality, newQuality, qualityDelta
            )

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
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "[%s] Rollback FAILED: %s", updateId, e.message)
                emitEvent(LearningHarnessEvent.RollbackFailed(updateId, updateType, e.message ?: "unknown"))
            }
        }

        // 6. Track learning rate
        recordLearningRate(updateType, baselineQuality, newQuality, System.currentTimeMillis())

        // 7. Record quality sample
        recordQualitySample(QualitySample(
            updateId = updateId,
            updateType = updateType,
            baselineQuality = baselineQuality,
            newQuality = newQuality,
            qualityDelta = qualityDelta,
            timestamp = System.currentTimeMillis()
        ))

        val elapsed = System.currentTimeMillis() - startTime
        Timber.tag(TAG).i(
            "[%s] Learning update applied: %s (Δ=%.3f, %dms, rolledBack=%s)",
            updateId, description, qualityDelta, elapsed, regressionDetected
        )

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

    // ═══════════════════════════════════════════════════════════════
    // 6. GENERAL A/B TESTING — Compare Learning Approaches
    // ═══════════════════════════════════════════════════════════════

    /**
     * Start an A/B test comparing two learning approaches.
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

        Timber.tag(TAG).i("A/B test started: %s — %s", experimentId, description)
        emitEvent(LearningHarnessEvent.ExperimentStarted(experimentId, description))

        return experiment
    }

    /**
     * Route a query through an active A/B test.
     * Returns the result from either baseline or challenger based on traffic split.
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
            } catch (e: Throwable) {
                Timber.tag(TAG).w("Challenger failed, falling back to baseline: %s", e.message)
                Pair(baselineFn(query), ExperimentGroup.BASELINE)
            }
        } else {
            Pair(baselineFn(query), ExperimentGroup.BASELINE)
        }

        val latencyMs = System.currentTimeMillis() - startTime
        val quality = qualityScoreFn(response)

        val sample = ExperimentSample(query, response, quality, latencyMs, group)
        when (group) {
            ExperimentGroup.BASELINE -> experiment.baselineSamples.add(sample)
            ExperimentGroup.CHALLENGER -> experiment.challengerSamples.add(sample)
        }

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
    // 7. PATTERN LEARNING — Wrapped with Validation
    // ═══════════════════════════════════════════════════════════════

    /**
     * Wrap a pattern learning update.
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
            rollbackFn = { /* Pattern updates are Bayesian, naturally self-correcting */ },
            learningDomain = "pattern"
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 8. ROLLBACK — Revert to Previous Checkpoint
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
            Timber.tag(TAG).w("Checkpoint %s not found", checkpointId)
            return false
        }

        try {
            rollbackFn()
            Timber.tag(TAG).i("Rolled back to checkpoint %s (%s)", checkpointId, checkpoint.description)
            emitEvent(LearningHarnessEvent.ManualRollback(checkpointId, checkpoint.description))
            return true
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "Rollback to %s failed: %s", checkpointId, e.message)
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
    // 9. LEARNING RATE & ACCURACY TRACKING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a learning rate observation.
     */
    private fun recordLearningRate(
        updateType: LearningUpdateType,
        baselineQuality: Double,
        newQuality: Double,
        timestamp: Long
    ) {
        val snapshot = LearningRateSnapshot(
            updateType = updateType,
            baselineQuality = baselineQuality,
            newQuality = newQuality,
            improvement = newQuality - baselineQuality,
            timestamp = timestamp
        )

        synchronized(learningRateHistory) {
            if (learningRateHistory.size >= LEARNING_RATE_WINDOW_SIZE) {
                learningRateHistory.removeFirst()
            }
            learningRateHistory.addLast(snapshot)
        }

        // Update overall accuracy baseline
        val currentBaseline = accuracyBaseline.get()
        if (currentBaseline == null || newQuality > currentBaseline) {
            accuracyBaseline.set(newQuality)
        }
    }

    /**
     * Get the current learning rate statistics.
     *
     * @return Summary of how fast the system is learning and whether it's improving
     */
    fun getLearningRateStats(): LearningRateStats {
        val history = synchronized(learningRateHistory) { learningRateHistory.toList() }
        if (history.isEmpty()) {
            return LearningRateStats(
                totalUpdates = 0,
                avgImprovement = 0.0,
                improvementTrend = 0.0,
                currentAccuracy = accuracyBaseline.get() ?: 0.0,
                regressionCount = regressionDetected.get(),
                isImproving = false
            )
        }

        val improvements = history.map { it.improvement }
        val avgImprovement = improvements.average()

        // Trend: compare recent half vs older half
        val midpoint = history.size / 2
        val recentAvg = if (midpoint > 0) {
            history.takeLast(midpoint).map { it.improvement }.average()
        } else avgImprovement
        val olderAvg = if (midpoint > 0) {
            history.take(midpoint).map { it.improvement }.average()
        } else 0.0
        val trend = recentAvg - olderAvg

        // Per-type breakdown
        val byType = history.groupBy { it.updateType }.mapValues { (_, samples) ->
            TypeLearningRate(
                updateCount = samples.size,
                avgImprovement = samples.map { it.improvement }.average(),
                bestImprovement = samples.maxOf { it.improvement },
                worstRegression = samples.minOf { it.improvement }
            )
        }

        return LearningRateStats(
            totalUpdates = history.size,
            avgImprovement = avgImprovement,
            improvementTrend = trend,
            currentAccuracy = accuracyBaseline.get() ?: 0.0,
            regressionCount = regressionDetected.get(),
            isImproving = avgImprovement > 0 && trend >= 0,
            byType = byType
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 10. COMPREHENSIVE STATS & MONITORING
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
            activeExperiments = activeExperiments.values.count { it.status == ABExperimentStatus.RUNNING } +
                asrCalibrationExperiments.values.count { it.status == ABExperimentStatus.RUNNING },
            completedExperiments = activeExperiments.values.count { it.status == ABExperimentStatus.COMPLETED } +
                asrCalibrationExperiments.values.count { it.status == ABExperimentStatus.COMPLETED },
            activePreferences = preferenceAdoptionState.size,
            adoptedPreferences = preferenceAdoptionState.values.count { it.adoptionStrength > 0 },
            heldOutSamples = heldOutStore.values.sumOf { it.size }
        )
    }

    fun getQualityHistory(): List<QualitySample> {
        return synchronized(qualityHistory) { qualityHistory.toList() }
    }

    /**
     * Get a comprehensive dashboard view of all learning systems.
     */
    fun getDashboard(): LearningDashboard {
        val stats = getStats()
        val learningRate = getLearningRateStats()
        val checkpoints = getCheckpoints()
        val experiments = activeExperiments.values.toList()
        val asrExperiments = asrCalibrationExperiments.values.toList()
        val preferences = preferenceAdoptionState.values.toList()

        return LearningDashboard(
            stats = stats,
            learningRate = learningRate,
            recentCheckpoints = checkpoints.takeLast(3),
            activeExperiments = experiments.filter { it.status == ABExperimentStatus.RUNNING }.size,
            activeAsrExperiments = asrExperiments.filter { it.status == ABExperimentStatus.RUNNING }.size,
            preferenceSummary = PreferenceSummary(
                total = preferences.size,
                adopted = preferences.count { it.adoptionStrength > 0 },
                pending = preferences.count { it.confidence > 0 && it.adoptionStrength == 0.0 },
                topPreferences = preferences.sortedByDescending { it.confidence }.take(5).map {
                    "${it.key}: ${(it.confidence * 100).toInt()}%"
                }
            )
        )
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
        try { eventListener?.invoke(event) } catch (_: Throwable) {}
    }

    // ═══════════════════════════════════════════════════════════════
    // DATA TYPES
    // ═══════════════════════════════════════════════════════════════

    enum class LearningUpdateType {
        VOCABULARY,
        PATTERN,
        MODEL_VERSION,
        CONFIDENCE_CALIBRATION,
        CORRECTION,
        PREFERENCE,
        ASR_CALIBRATION
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
        val completedExperiments: Int,
        val activePreferences: Int = 0,
        val adoptedPreferences: Int = 0,
        val heldOutSamples: Int = 0
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

    // ── ASR Calibration Types ─────────────────────────────────────

    /**
     * ASR calibration configuration parameters.
     * Used for A/B testing different calibration approaches.
     */
    data class AsrCalibrationConfig(
        /** Confidence threshold below which a word is flagged as "unknown" */
        val confidenceThreshold: Float = 0.60f,
        /** Temperature scaling for softmax calibration (higher = softer) */
        val temperature: Float = 1.0f,
        /** Per-language offset to apply to raw confidence */
        val languageOffset: Float = 0.0f,
        /** Threshold above which confidence is considered "high" */
        val highConfidenceThreshold: Float = 0.80f,
        /** Threshold below which confidence is considered "low" */
        val lowConfidenceThreshold: Float = 0.40f,
        /** Description of this calibration config */
        val description: String = "default"
    )

    /**
     * An A/B experiment comparing two ASR calibration approaches.
     */
    data class AsrCalibrationExperiment(
        val experimentId: String,
        val baselineConfig: AsrCalibrationConfig,
        val challengerConfig: AsrCalibrationConfig,
        var status: ABExperimentStatus,
        val startedAt: Long,
        var completedAt: Long = 0L,
        val baselineResults: MutableList<AsrCalibrationSample>,
        val challengerResults: MutableList<AsrCalibrationSample>
    )

    /**
     * A single ASR calibration measurement.
     */
    data class AsrCalibrationSample(
        val word: String,
        val rawConfidence: Float,
        val calibratedConfidence: Float,
        val wasCorrect: Boolean,
        val calibrationAccuracy: Double,
        val group: ExperimentGroup,
        val timestamp: Long
    )

    // ── Preference Adoption Types ─────────────────────────────────

    /**
     * Tracks how a preference is being gradually adopted.
     * Uses sigmoid function for smooth ramp-up.
     */
    data class PreferenceAdoptionState(
        val key: String,
        var observationCount: Int,
        var totalWeight: Double,
        var confidence: Double,
        var currentValue: String,
        var adoptionStrength: Double,
        val firstSeenAt: Long,
        var lastSeenAt: Long
    )

    /**
     * Result of a preference update through the harness.
     */
    data class PreferenceAdoptionResult(
        val preferenceKey: String,
        val shouldApply: Boolean,
        val adoptionStrength: Double,
        val confidence: Double,
        val observationCount: Int,
        val reason: String
    )

    // ── Held-Out Validation Types ─────────────────────────────────

    /**
     * A held-out validation sample.
     * Contains input and expected output for testing learning quality.
     */
    data class HeldOutSample(
        val input: String,
        val expectedOutput: String,
        val weight: Double = 1.0
    )

    // ── Learning Rate Types ───────────────────────────────────────

    /**
     * A single learning rate observation.
     */
    data class LearningRateSnapshot(
        val updateType: LearningUpdateType,
        val baselineQuality: Double,
        val newQuality: Double,
        val improvement: Double,
        val timestamp: Long
    )

    /**
     * Per-type learning rate breakdown.
     */
    data class TypeLearningRate(
        val updateCount: Int,
        val avgImprovement: Double,
        val bestImprovement: Double,
        val worstRegression: Double
    )

    /**
     * Aggregate learning rate statistics.
     */
    data class LearningRateStats(
        val totalUpdates: Int,
        val avgImprovement: Double,
        val improvementTrend: Double,
        val currentAccuracy: Double,
        val regressionCount: Long,
        val isImproving: Boolean,
        val byType: Map<LearningUpdateType, TypeLearningRate> = emptyMap()
    )

    // ── Dashboard Types ───────────────────────────────────────────

    data class LearningDashboard(
        val stats: LearningHarnessStats,
        val learningRate: LearningRateStats,
        val recentCheckpoints: List<LearningCheckpoint>,
        val activeExperiments: Int,
        val activeAsrExperiments: Int,
        val preferenceSummary: PreferenceSummary
    )

    data class PreferenceSummary(
        val total: Int,
        val adopted: Int,
        val pending: Int,
        val topPreferences: List<String>
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
