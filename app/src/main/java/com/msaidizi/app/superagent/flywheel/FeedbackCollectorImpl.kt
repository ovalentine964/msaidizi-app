package com.msaidizi.app.superagent.flywheel

import timber.log.Timber

/**
 * FeedbackCollectorImpl — Collects and analyzes feedback signals.
 *
 * Feedback is the fuel of the flywheel. Every interaction produces
 * feedback — explicit (worker confirms/corrects) or implicit
 * (worker returns/ignores).
 *
 * ## Feedback Types
 *
 * | Type | Signal | Value |
 * |------|--------|-------|
 * | CONFIRMATION | "Sawa", "Asante" | Positive — reinforce pattern |
 * | CORRECTION | "Sio X, ni Y" | Learning — update classification |
 * | CLARIFICATION | "Nini?" | Quality — improve responses |
 * | IGNORE | No response | Neutral — don't penalize |
 *
 * ## Analysis
 *
 * Periodically analyzes accumulated feedback to:
 * 1. Calculate confirmation rate (how often worker confirms)
 * 2. Calculate correction rate (how often we get it wrong)
 * 3. Identify problem intents (frequently corrected)
 * 4. Track feedback trends over time
 *
 * @param feedbackStore Backing store for feedback
 */
class FeedbackCollectorImpl(
    private val feedbackStore: FeedbackStore
) : FeedbackCollector {

    companion object {
        private const val TAG = "FeedbackCollector"

        /** Size of the in-memory buffer before flushing */
        private const val BUFFER_SIZE = 50
    }

    /** In-memory feedback buffer */
    private val buffer = mutableListOf<FlywheelModels.FeedbackSignal>()

    /** Cached analysis results */
    private var cachedAnalysis: FeedbackAnalysis? = null

    // ═══════════════════════════════════════════════════════════════
    // FEEDBACK RECORDING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a feedback signal.
     *
     * @param feedback The feedback to record
     */
    override fun record(feedback: FlywheelModels.FeedbackSignal) {
        buffer.add(feedback)
        cachedAnalysis = null  // invalidate cache

        Timber.d(TAG, "Feedback recorded: type=${feedback.type}, input='${feedback.originalInput.take(30)}'")

        // Flush if buffer is full
        if (buffer.size >= BUFFER_SIZE) {
            flush()
        }
    }

    /**
     * Analyze accumulated feedback.
     * Called periodically by the improvement cycle.
     */
    override fun analyze() {
        flush()

        scope.launch {
            try {
                val allFeedback = feedbackStore.getRecentFeedback(limit = 500)

                val confirmations = allFeedback.count { it.type == FlywheelModels.FeedbackType.CONFIRMATION }
                val corrections = allFeedback.count { it.type == FlywheelModels.FeedbackType.CORRECTION }
                val clarifications = allFeedback.count { it.type == FlywheelModels.FeedbackType.CLARIFICATION }
                val ignores = allFeedback.count { it.type == FlywheelModels.FeedbackType.IGNORE }
                val total = allFeedback.size

                // Identify problem intents (frequently corrected)
                val problemInputs = allFeedback
                    .filter { it.type == FlywheelModels.FeedbackType.CORRECTION }
                    .groupBy { normalizeInput(it.originalInput) }
                    .filter { it.value.size >= 2 }
                    .keys

                cachedAnalysis = FeedbackAnalysis(
                    totalFeedback = total,
                    confirmationRate = if (total > 0) confirmations.toDouble() / total else 0.0,
                    correctionRate = if (total > 0) corrections.toDouble() / total else 0.0,
                    clarificationRate = if (total > 0) clarifications.toDouble() / total else 0.0,
                    ignoreRate = if (total > 0) ignores.toDouble() / total else 0.0,
                    problemInputs = problemInputs.toList(),
                    analyzedAt = System.currentTimeMillis()
                )

                Timber.d(TAG, "Analysis complete: confirmations=$confirmations, corrections=$corrections, clarifications=$clarifications")
            } catch (e: Exception) {
                Timber.e(e, "Feedback analysis failed")
            }
        }
    }

    /**
     * Get recent feedback signals.
     */
    override fun getRecentFeedback(limit: Int): List<FlywheelModels.FeedbackSignal> {
        return buffer.takeLast(limit) + try {
            feedbackStore.getRecentFeedback(limit = limit)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get the feedback analysis results.
     */
    fun getAnalysis(): FeedbackAnalysis? {
        return cachedAnalysis
    }

    /**
     * Get the confirmation rate (how often the worker confirms our responses).
     */
    fun getConfirmationRate(): Double {
        return cachedAnalysis?.confirmationRate ?: run {
            val total = buffer.size
            if (total == 0) return 0.0
            buffer.count { it.type == FlywheelModels.FeedbackType.CONFIRMATION }.toDouble() / total
        }
    }

    /**
     * Get the correction rate (how often the worker corrects us).
     */
    fun getCorrectionRate(): Double {
        return cachedAnalysis?.correctionRate ?: run {
            val total = buffer.size
            if (total == 0) return 0.0
            buffer.count { it.type == FlywheelModels.FeedbackType.CORRECTION }.toDouble() / total
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // INTERNAL
    // ═══════════════════════════════════════════════════════════════

    /**
     * Flush the in-memory buffer to the backing store.
     */
    private fun flush() {
        if (buffer.isEmpty()) return

        val toFlush = buffer.toList()
        buffer.clear()

        scope.launch {
            try {
                feedbackStore.saveFeedback(toFlush)
                Timber.d(TAG, "Flushed ${toFlush.size} feedback signals")
            } catch (e: Exception) {
                Timber.e(e, "Failed to flush feedback")
                // Re-add to buffer on failure
                buffer.addAll(0, toFlush)
            }
        }
    }

    private fun normalizeInput(input: String): String {
        return input.lowercase().trim()
            .replace(Regex("[^a-z0-9\\s]"), "")
    }

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )
}

// ═══════════════════════════════════════════════════════════════════
// ANALYSIS RESULT
// ═══════════════════════════════════════════════════════════════════

/**
 * Result of feedback analysis.
 *
 * @property totalFeedback Total feedback signals analyzed
 * @property confirmationRate Rate of confirmations (0.0–1.0)
 * @property correctionRate Rate of corrections (0.0–1.0)
 * @property clarificationRate Rate of clarification requests (0.0–1.0)
 * @property ignoreRate Rate of ignored responses (0.0–1.0)
 * @property problemInputs Inputs that are frequently corrected
 * @property analyzedAt When this analysis was performed
 */
data class FeedbackAnalysis(
    val totalFeedback: Int,
    val confirmationRate: Double,
    val correctionRate: Double,
    val clarificationRate: Double,
    val ignoreRate: Double,
    val problemInputs: List<String>,
    val analyzedAt: Long
)

// ═══════════════════════════════════════════════════════════════════
// STORE INTERFACE
// ═══════════════════════════════════════════════════════════════════

/**
 * Backing store for feedback signals.
 */
interface FeedbackStore {
    suspend fun saveFeedback(signals: List<FlywheelModels.FeedbackSignal>)
    suspend fun getRecentFeedback(limit: Int): List<FlywheelModels.FeedbackSignal>
}
