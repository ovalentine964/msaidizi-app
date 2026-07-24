package com.msaidizi.app.superagent.flywheel

import com.msaidizi.app.superagent.engine.LearningSignal
import timber.log.Timber

/**
 * PatternTrackerImpl — Extracts patterns from transaction history and interactions.
 *
 * Detects patterns like:
 * - Busy days (which days have more sales)
 * - Price trends (are prices going up/down for specific items)
 * - Spending triggers (what causes overspending)
 * - Seasonal cycles (monthly/weekly patterns)
 * - Business rhythms (morning vs afternoon activity)
 *
 * Patterns are extracted from accumulated learning signals and
 * used to improve predictions and advice.
 *
 * @param patternStore Backing store for patterns
 */
class PatternTrackerImpl(
    private val patternStore: PatternStore
) : PatternTracker {

    companion object {
        private const val TAG = "PatternTracker"

        /** Minimum observations before a pattern is considered reliable */
        private const val MIN_OBSERVATIONS = 5

        /** Pattern confidence threshold for application */
        private const val PATTERN_CONFIDENCE_THRESHOLD = 0.6
    }

    /** In-memory signal accumulator */
    private val recentSignals = mutableListOf<LearningSignal>()

    /** Detected patterns (cached) */
    private var cachedPatterns: List<FlywheelModels.LearnedPattern>? = null

    // ═══════════════════════════════════════════════════════════════
    // SIGNAL OBSERVATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Observe a learning signal for pattern extraction.
     * Accumulates signals for batch analysis.
     */
    override fun observe(signal: LearningSignal) {
        recentSignals.add(signal)

        // Flush if buffer gets large
        if (recentSignals.size > 200) {
            updatePatterns()
        }
    }

    /**
     * Update patterns from accumulated signals.
     * Called periodically by the improvement cycle.
     */
    override fun updatePatterns() {
        if (recentSignals.isEmpty()) return

        Timber.d(TAG, "Updating patterns from ${recentSignals.size} signals")

        val newPatterns = mutableListOf<FlywheelModels.LearnedPattern>()

        // ── Detect intent frequency patterns ──
        val intentCounts = recentSignals.groupBy { it.intent }
            .mapValues { it.value.size }
        intentCounts.forEach { (intent, count) ->
            if (count >= MIN_OBSERVATIONS) {
                newPatterns.add(FlywheelModels.LearnedPattern(
                    key = "intent_freq:$intent",
                    description = "Worker frequently uses intent: $intent ($count times)",
                    confidence = minOf(count.toDouble() / 20.0, 1.0),
                    sampleCount = count
                ))
            }
        }

        // ── Detect confidence patterns ──
        val lowConfidenceIntents = recentSignals
            .filter { it.confidence < 0.5f }
            .groupBy { it.intent }
            .filter { it.value.size >= 3 }

        lowConfidenceIntents.forEach { (intent, signals) ->
            newPatterns.add(FlywheelModels.LearnedPattern(
                key = "low_conf:$intent",
                description = "Intent '$intent' often has low confidence (${signals.size} times)",
                confidence = 0.7,
                sampleCount = signals.size
            ))
        }

        // ── Detect parse method patterns ──
        val llmEscalations = recentSignals.filter { it.parseMethod == com.msaidizi.app.superagent.engine.ParseMethod.LLM }
        if (llmEscalations.size >= MIN_OBSERVATIONS) {
            newPatterns.add(FlywheelModels.LearnedPattern(
                key = "llm_escalation_rate",
                description = "${llmEscalations.size}/${recentSignals.size} inputs require LLM",
                confidence = 0.8,
                sampleCount = llmEscalations.size
            ))
        }

        // ── Detect signal type patterns ──
        val signalTypeCounts = recentSignals.flatMap { it.signals }
            .groupBy { it }
            .mapValues { it.value.size }

        signalTypeCounts.forEach { (type, count) ->
            if (count >= MIN_OBSERVATIONS) {
                newPatterns.add(FlywheelModels.LearnedPattern(
                    key = "signal_freq:$type",
                    description = "Frequent signal: $type ($count occurrences)",
                    confidence = minOf(count.toDouble() / 15.0, 1.0),
                    sampleCount = count
                ))
            }
        }

        // Save to store
        scope.launch {
            try {
                newPatterns.forEach { pattern ->
                    patternStore.savePattern(pattern)
                }
                cachedPatterns = null  // invalidate cache
                Timber.d(TAG, "Saved ${newPatterns.size} patterns")
            } catch (e: Exception) {
                Timber.e(e, "Failed to save patterns")
            }
        }

        // Clear processed signals (keep last 50 for continuity)
        if (recentSignals.size > 50) {
            val keep = recentSignals.takeLast(50).toMutableList()
            recentSignals.clear()
            recentSignals.addAll(keep)
        }
    }

    /**
     * Get all detected patterns.
     */
    override fun getPatterns(): List<FlywheelModels.LearnedPattern> {
        cachedPatterns?.let { return it }

        val patterns = try {
            patternStore.getAllPatterns()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get patterns")
            emptyList()
        }

        cachedPatterns = patterns
        return patterns
    }

    /**
     * Get patterns relevant to a specific intent.
     */
    fun getPatternsForIntent(intent: String): List<FlywheelModels.LearnedPattern> {
        return getPatterns().filter { it.key.contains(intent, ignoreCase = true) }
    }

    /**
     * Get the LLM escalation rate (what % of inputs need LLM).
     */
    fun getLlmEscalationRate(): Double {
        val total = recentSignals.size
        if (total == 0) return 0.0
        val llmCount = recentSignals.count {
            it.parseMethod == com.msaidizi.app.superagent.engine.ParseMethod.LLM
        }
        return llmCount.toDouble() / total
    }

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )
}

// ═══════════════════════════════════════════════════════════════════
// STORE INTERFACE
// ═══════════════════════════════════════════════════════════════════

/**
 * Backing store for detected patterns.
 */
interface PatternStore {
    suspend fun savePattern(pattern: FlywheelModels.LearnedPattern)
    suspend fun getAllPatterns(): List<FlywheelModels.LearnedPattern>
    suspend fun getPatternsByKey(keyPrefix: String): List<FlywheelModels.LearnedPattern>
    suspend fun deletePattern(key: String)
}
