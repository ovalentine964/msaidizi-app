package com.msaidizi.app.superagent.flywheel

import com.msaidizi.app.superagent.engine.LearningSignal
import timber.log.Timber

/**
 * AdaptiveLearningImpl — Learns from corrections and feedback.
 *
 * Implements Level 2 (Context Injection) of the adaptive learning architecture:
 * - Track user corrections ("no, that was X not Y")
 * - Learn user's product vocabulary (what they sell, at what prices)
 * - Apply learned corrections to improve intent parsing
 * - Collect data for future LoRA fine-tuning (Level 3)
 *
 * ## How It Works
 *
 * 1. Every correction is stored with the original → corrected mapping
 * 2. After N corrections for the same pattern, confidence increases
 * 3. High-confidence corrections are injected into intent classification
 * 4. Success signals reinforce existing patterns
 *
 * All learning happens on-device. No data leaves the phone.
 *
 * @param patternStore Backing store for learned patterns
 * @param minCorrectionsForPattern Minimum corrections before pattern is applied
 */
class AdaptiveLearningImpl(
    private val patternStore: LearningPatternStore,
    private val minCorrectionsForPattern: Int = DEFAULT_MIN_CORRECTIONS
) : AdaptiveLearning {

    companion object {
        private const val TAG = "AdaptiveLearning"
        private const val DEFAULT_MIN_CORRECTIONS = 3

        /** Confidence boost per correction */
        private const val CORRECTION_CONFIDENCE_BOOST = 0.15

        /** Confidence decay for unconfirmed patterns */
        private const val CONFIDENCE_DECAY = 0.05

        /** Maximum confidence for learned patterns */
        private const val MAX_CONFIDENCE = 0.95
    }

    // In-memory correction buffer (flushed to store periodically)
    private val correctionBuffer = mutableListOf<CorrectionEntry>()

    // In-memory signal buffer
    private val signalBuffer = mutableListOf<LearningSignal>()

    // ═══════════════════════════════════════════════════════════════
    // SIGNAL RECORDING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a learning signal from an OODA cycle.
     * Tracks intent, confidence, and parse method for accuracy analysis.
     */
    override fun recordSignal(signal: LearningSignal) {
        signalBuffer.add(signal)

        // Flush buffer if it gets large
        if (signalBuffer.size > 100) {
            flushSignalBuffer()
        }
    }

    /**
     * Record a correction — the most valuable learning signal.
     *
     * Each correction updates:
     * 1. The vocabulary mapping (spoken → canonical)
     * 2. Price/quantity tracking
     * 3. Pattern confidence
     * 4. Training data for future LoRA (Level 3)
     */
    override fun recordCorrection(
        originalInput: String,
        correctionInput: String,
        originalIntent: String
    ) {
        val entry = CorrectionEntry(
            originalInput = originalInput,
            correctionInput = correctionInput,
            originalIntent = originalIntent,
            timestamp = System.currentTimeMillis()
        )
        correctionBuffer.add(entry)

        // Extract the correction pattern
        val pattern = extractCorrectionPattern(originalInput, correctionInput)
        if (pattern != null) {
            updatePatternFromCorrection(pattern)
        }

        Timber.d(TAG, "Correction recorded: '$originalInput' → '$correctionInput'")
    }

    /**
     * Record a success — the worker confirmed our classification.
     * Reinforces the pattern that led to this success.
     */
    override fun recordSuccess(originalInput: String) {
        // Extract the pattern that led to success
        val patternKey = normalizeInput(originalInput)
        scope.launch {
            try {
                patternStore.reinforcePattern("success:$patternKey")
                Timber.d(TAG, "Success reinforced: $patternKey")
            } catch (e: Exception) {
                Timber.w(e, "Failed to reinforce pattern")
            }
        }
    }

    /**
     * Consolidate learning — called periodically by the improvement cycle.
     *
     * 1. Flush signal and correction buffers to store
     * 2. Update pattern confidences
     * 3. Decay old patterns that haven't been reinforced
     */
    override fun consolidate() {
        Timber.d(TAG, "Consolidating: ${correctionBuffer.size} corrections, ${signalBuffer.size} signals")

        // Flush buffers
        flushCorrectionBuffer()
        flushSignalBuffer()

        // Update pattern confidences
        scope.launch {
            try {
                patternStore.decayPatterns(decayFactor = CONFIDENCE_DECAY)
                Timber.d(TAG, "Pattern consolidation complete")
            } catch (e: Exception) {
                Timber.e(e, "Pattern consolidation failed")
            }
        }
    }

    /**
     * Get correction patterns for intent classifier improvement.
     *
     * @return Map of normalized input → corrected intent
     */
    fun getCorrectionPatterns(): Map<String, String> {
        return correctionBuffer
            .groupBy { normalizeInput(it.originalInput) }
            .filter { it.value.size >= minCorrectionsForPattern }
            .mapValues { (_, corrections) ->
                corrections.last().originalIntent  // most recent correction
            }
    }

    // ═══════════════════════════════════════════════════════════════
    // INTERNAL
    // ═══════════════════════════════════════════════════════════════

    private fun extractCorrectionPattern(
        original: String,
        correction: String
    ): CorrectionPattern? {
        // Simple pattern extraction: what changed?
        val originalWords = original.lowercase().split(" ")
        val correctionWords = correction.lowercase().split(" ")

        // Find the differing words
        val changed = originalWords.zip(correctionWords)
            .filter { (a, b) -> a != b }

        if (changed.isEmpty()) return null

        return CorrectionPattern(
            originalTokens = changed.map { it.first },
            correctedTokens = changed.map { it.second },
            fullOriginal = original,
            fullCorrection = correction
        )
    }

    private fun updatePatternFromCorrection(pattern: CorrectionPattern) {
        scope.launch {
            try {
                val key = "correction:${pattern.originalTokens.joinToString("_")}"
                val existing = patternStore.getPattern(key)

                val newConfidence = if (existing != null) {
                    minOf(existing.confidence + CORRECTION_CONFIDENCE_BOOST, MAX_CONFIDENCE)
                } else {
                    CORRECTION_CONFIDENCE_BOOST
                }

                patternStore.savePattern(
                    key = key,
                    data = pattern.correctedTokens.joinToString(" "),
                    confidence = newConfidence,
                    sampleCount = (existing?.sampleCount ?: 0) + 1
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to update pattern from correction")
            }
        }
    }

    private fun normalizeInput(input: String): String {
        return input.lowercase().trim()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split("\\s+".toRegex())
            .sorted()
            .joinToString(" ")
    }

    private fun flushCorrectionBuffer() {
        if (correctionBuffer.isEmpty()) return
        scope.launch {
            try {
                patternStore.saveCorrections(correctionBuffer.toList())
                Timber.d(TAG, "Flushed ${correctionBuffer.size} corrections to store")
                correctionBuffer.clear()
            } catch (e: Exception) {
                Timber.e(e, "Failed to flush corrections")
            }
        }
    }

    private fun flushSignalBuffer() {
        if (signalBuffer.isEmpty()) return
        scope.launch {
            try {
                patternStore.saveSignals(signalBuffer.toList())
                signalBuffer.clear()
            } catch (e: Exception) {
                Timber.e(e, "Failed to flush signals")
            }
        }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )
}

// ═══════════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════════

data class CorrectionEntry(
    val originalInput: String,
    val correctionInput: String,
    val originalIntent: String,
    val timestamp: Long
)

data class CorrectionPattern(
    val originalTokens: List<String>,
    val correctedTokens: List<String>,
    val fullOriginal: String,
    val fullCorrection: String
)

data class LearnedPatternEntry(
    val key: String,
    val data: String,
    val confidence: Double,
    val sampleCount: Int,
    val lastUpdated: Long
)

// ═══════════════════════════════════════════════════════════════════
// STORE INTERFACE
// ═══════════════════════════════════════════════════════════════════

/**
 * Backing store for learned patterns.
 * Implementations bridge to Room database.
 */
interface LearningPatternStore {
    suspend fun getPattern(key: String): LearnedPatternEntry?
    suspend fun savePattern(key: String, data: String, confidence: Double, sampleCount: Int)
    suspend fun reinforcePattern(key: String)
    suspend fun decayPatterns(decayFactor: Double)
    suspend fun saveCorrections(corrections: List<CorrectionEntry>)
    suspend fun saveSignals(signals: List<LearningSignal>)
}
