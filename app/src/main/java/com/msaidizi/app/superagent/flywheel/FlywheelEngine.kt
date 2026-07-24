package com.msaidizi.app.superagent.flywheel

import com.msaidizi.app.superagent.engine.LearningSignal
import com.msaidizi.app.superagent.flywheel.FlywheelModels.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * FlywheelEngine — The learning flywheel for the Msaidizi superagent.
 *
 * Implements the **Use → Learn → Improve → Use More** cycle:
 *
 * ```
 * ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
 * │    USE       │────▶│   LEARN     │────▶│   IMPROVE   │
 * │              │     │             │     │             │
 * │ Worker logs  │     │ Agent learns│     │ Better      │
 * │ transactions │     │ patterns    │     │ predictions │
 * │ via voice    │     │ from data   │     │ and advice  │
 * └──────┬───────┘     └─────────────┘     └──────┬──────┘
 *        │                                         │
 *        │         ┌─────────────┐                 │
 *        │         │   USE MORE  │◀────────────────┘
 *        │         │             │
 *        │         │ Worker sees │
 *        │         │ value →     │
 *        └────────▶│ uses more   │
 *                  └─────────────┘
 * ```
 *
 * ## M-KOPA Proof Model
 *
 * Every interaction generates proof points. Proof accumulates into
 * the Alama Score. Higher tiers unlock more capabilities:
 *
 * - **BUILDING** (0 points): Basic tracking
 * - **EMERGING** (30+ points, 7+ days): Basic insights
 * - **ESTABLISHED** (100+ points, 30+ days): Credit readiness
 * - **PROVEN** (300+ points, 90+ days): Formal finance eligibility
 * - **TRUSTED** (1000+ points, 180+ days): Full platform access
 *
 * ## Components
 *
 * - **ProofAccumulator** — tracks proof points and Alama Score
 * - **AdaptiveLearning** — learns from corrections and feedback
 * - **PatternTracker** — extracts patterns from transaction history
 * - **PreferenceLearner** — learns worker preferences
 * - **FeedbackCollector** — ingests feedback from every interaction
 *
 * @param adaptiveLearning Learns from corrections
 * @param preferenceLearner Tracks worker preferences
 * @param patternTracker Extracts business patterns
 * @param feedbackCollector Ingests feedback signals
 * @param proofStore Backing store for proof points
 */
class FlywheelEngine(
    private val adaptiveLearning: AdaptiveLearning,
    private val preferenceLearner: PreferenceLearner,
    private val patternTracker: PatternTracker,
    private val feedbackCollector: FeedbackCollector,
    private val proofStore: ProofStore
) {
    companion object {
        private const val TAG = "FlywheelEngine"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Current Alama Score (cached) */
    private var cachedAlamaScore: AlamaScore? = null

    /** Previous Alama Score (for tier unlock detection) */
    private var previousAlamaScore: AlamaScore? = null

    // ═══════════════════════════════════════════════════════════════
    // PROOF ACCUMULATION (M-KOPA Model)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a proof point.
     *
     * Every transaction, goal progress, and consistency day generates
     * proof. Proof accumulates into the Alama Score.
     *
     * @param proofPoint The proof point to record
     */
    fun recordProof(proofPoint: ProofPoint) {
        scope.launch {
            try {
                proofStore.saveProofPoint(proofPoint)

                // Update cached score
                previousAlamaScore = cachedAlamaScore
                cachedAlamaScore = null  // force refresh on next get

                Timber.d(TAG, "Recorded proof: ${proofPoint.type} (weight=${proofPoint.weight})")

                // Check for tier unlock
                val newScore = getCurrentAlamaScore()
                if (newScore.tierUnlocked) {
                    Timber.i(TAG, "TIER UNLOCKED: ${newScore.previousTier} → ${newScore.currentTier}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to record proof point")
            }
        }
    }

    /**
     * Get the current Alama Score.
     *
     * Calculates from accumulated proof points and days active.
     */
    fun getCurrentAlamaScore(): AlamaScore {
        cachedAlamaScore?.let { return it }

        val score = try {
            val proofCount = proofStore.getTotalProofPoints()
            val daysActive = proofStore.getDaysActive()
            val consistency = proofStore.getConsistencyScore()
            val dataQuality = proofStore.getDataQualityScore()
            val tier = AlamaTier.fromProgress(proofCount, daysActive)
            val prevTier = previousAlamaScore?.currentTier ?: tier

            val creditReadiness = when {
                proofCount >= 300 && daysActive >= 90 -> CreditReadiness.PRE_QUALIFIED
                proofCount >= 100 && daysActive >= 30 -> CreditReadiness.READY_FOR_REVIEW
                proofCount >= 30 && daysActive >= 7 -> CreditReadiness.EARLY_ASSESSMENT
                proofCount >= 10 -> CreditReadiness.BUILDING
                else -> CreditReadiness.NOT_READY
            }

            AlamaScore(
                proofPoints = proofCount,
                daysActive = daysActive,
                currentTier = tier,
                previousTier = prevTier,
                consistencyScore = consistency,
                dataQualityScore = dataQuality,
                creditReadiness = creditReadiness
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to calculate Alama Score")
            AlamaScore()
        }

        cachedAlamaScore = score
        return score
    }

    /**
     * Get the previous Alama Score (before the last proof point).
     */
    fun getPreviousAlamaScore(): AlamaScore {
        return previousAlamaScore ?: getCurrentAlamaScore()
    }

    // ═══════════════════════════════════════════════════════════════
    // LEARNING SIGNAL RECORDING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a learning signal from an interaction.
     *
     * Every interaction through the OODA loop produces a learning signal.
     * This feeds into adaptive learning and pattern tracking.
     *
     * @param signal The learning signal from the OODA loop
     */
    fun recordLearningSignal(signal: LearningSignal) {
        scope.launch {
            try {
                // Feed into adaptive learning
                adaptiveLearning.recordSignal(signal)

                // Feed into preference learner
                preferenceLearner.observe(signal)

                // Feed into pattern tracker
                patternTracker.observe(signal)

                Timber.d(TAG, "Recorded learning signal: intent=${signal.intent}, confidence=${signal.confidence}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to record learning signal")
            }
        }
    }

    /**
     * Record a correction from the worker.
     *
     * When the worker corrects the agent ("Sio mandazi, ni maandazi"),
     * this is the most valuable learning signal.
     *
     * @param originalInput The original input
     * @param correctionInput The correction
     * @param originalIntent What the system classified
     */
    fun recordCorrection(
        originalInput: String,
        correctionInput: String,
        originalIntent: String
    ) {
        scope.launch {
            try {
                val feedback = FeedbackSignal(
                    type = FeedbackType.CORRECTION,
                    originalInput = originalInput,
                    correctionInput = correctionInput,
                    originalIntent = originalIntent
                )
                feedbackCollector.record(feedback)
                adaptiveLearning.recordCorrection(originalInput, correctionInput, originalIntent)

                Timber.d(TAG, "Recorded correction: '$originalInput' → '$correctionInput'")
            } catch (e: Exception) {
                Timber.e(e, "Failed to record correction")
            }
        }
    }

    /**
     * Record immediate feedback from the worker.
     *
     * @param workerResponse The worker's response to the agent's output
     * @param originalInput The original input that triggered the response
     */
    fun recordFeedback(workerResponse: String, originalInput: String) {
        scope.launch {
            try {
                val feedbackType = classifyFeedback(workerResponse)
                val feedback = FeedbackSignal(
                    type = feedbackType,
                    originalInput = originalInput,
                    correctionInput = workerResponse
                )
                feedbackCollector.record(feedback)

                if (feedbackType == FeedbackType.CONFIRMATION) {
                    adaptiveLearning.recordSuccess(originalInput)
                }

                Timber.d(TAG, "Recorded feedback: type=$feedbackType")
            } catch (e: Exception) {
                Timber.e(e, "Failed to record feedback")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // IMPROVEMENT CYCLE
    // ═══════════════════════════════════════════════════════════════

    /**
     * Run the improvement cycle.
     *
     * Uses accumulated feedback to improve all aspects of the system:
     * 1. Intent accuracy — learn from corrections
     * 2. Response quality — adjust based on clarification requests
     * 3. Prediction accuracy — tune based on outcomes
     * 4. Personalization — consolidate preferences
     *
     * Should be called periodically (every N interactions or on schedule).
     */
    fun improve() {
        scope.launch {
            try {
                Timber.d(TAG, "Running improvement cycle")

                // 1. Intent accuracy
                adaptiveLearning.consolidate()

                // 2. Preferences
                preferenceLearner.consolidate()

                // 3. Patterns
                patternTracker.updatePatterns()

                // 4. Feedback analysis
                feedbackCollector.analyze()

                Timber.d(TAG, "Improvement cycle complete")
            } catch (e: Exception) {
                Timber.e(e, "Improvement cycle failed")
            }
        }
    }

    /**
     * Get the current flywheel metrics for debugging/monitoring.
     */
    fun getMetrics(): FlywheelMetrics {
        val score = getCurrentAlamaScore()
        return FlywheelMetrics(
            alamaScore = score,
            totalProofPoints = score.proofPoints,
            daysActive = score.daysActive,
            currentTier = score.currentTier,
            creditReadiness = score.creditReadiness
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Classify worker feedback from their response text.
     */
    private fun classifyFeedback(response: String): FeedbackType {
        val normalized = response.lowercase().trim()
        return when {
            normalized in listOf("sawa", "asante", "ndiyo", "yes", "ok", "a", "sawa sawa", "poa") ->
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

    /**
     * Shutdown the flywheel engine.
     */
    fun shutdown() {
        // CoroutineScope with SupervisorJob will be GC'd
        Timber.d(TAG, "FlywheelEngine shutdown")
    }
}

// ═══════════════════════════════════════════════════════════════════
// FLYWHEEL METRICS
// ═══════════════════════════════════════════════════════════════════

data class FlywheelMetrics(
    val alamaScore: FlywheelModels.AlamaScore,
    val totalProofPoints: Int,
    val daysActive: Int,
    val currentTier: FlywheelModels.AlamaTier,
    val creditReadiness: FlywheelModels.CreditReadiness
)

// ═══════════════════════════════════════════════════════════════════
// COMPONENT INTERFACES
// ═══════════════════════════════════════════════════════════════════

/**
 * Learns from corrections and feedback to improve intent classification.
 */
interface AdaptiveLearning {
    fun recordSignal(signal: LearningSignal)
    fun recordCorrection(originalInput: String, correctionInput: String, originalIntent: String)
    fun recordSuccess(originalInput: String)
    fun consolidate()
}

/**
 * Tracks and learns worker preferences from behavior.
 */
interface PreferenceLearner {
    fun observe(signal: LearningSignal)
    fun consolidate()
    fun getPreferences(): Map<String, String>
}

/**
 * Extracts patterns from transaction history and interactions.
 */
interface PatternTracker {
    fun observe(signal: LearningSignal)
    fun updatePatterns()
    fun getPatterns(): List<FlywheelModels.LearnedPattern>
}

/**
 * Collects and analyzes feedback signals.
 */
interface FeedbackCollector {
    fun record(feedback: FlywheelModels.FeedbackSignal)
    fun analyze()
    fun getRecentFeedback(limit: Int = 10): List<FlywheelModels.FeedbackSignal>
}

/**
 * Backing store for proof points and Alama Score data.
 */
interface ProofStore {
    fun saveProofPoint(proof: FlywheelModels.ProofPoint)
    fun getTotalProofPoints(): Int
    fun getDaysActive(): Int
    fun getConsistencyScore(): Double
    fun getDataQualityScore(): Double
}
