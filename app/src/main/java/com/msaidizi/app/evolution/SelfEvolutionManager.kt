package com.msaidizi.app.evolution

import com.msaidizi.app.agent.AdaptiveLearningEngine
import com.msaidizi.app.agent.BusinessPatternTracker
import com.msaidizi.app.agent.LearningAgent
import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.dialect.AdaptiveVocabulary
import com.msaidizi.app.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Self-Evolution Manager — the brain of Msaidizi's self-improvement.
 *
 * Closes the feedback loop by:
 * 1. Tracking worker preferences and applying them automatically
 * 2. Analyzing correction patterns to prevent repeated mistakes
 * 3. Monitoring feature usage to prioritize improvements
 * 4. Measuring satisfaction signals (did worker act on advice?)
 * 5. Adapting goals based on actual worker behavior
 * 6. Refining advice quality based on outcomes
 *
 * Architecture:
 * - Collects signals from every interaction (voice, UI, corrections)
 * - Analyzes patterns in background (during idle/charging)
 * - Applies learned preferences in real-time
 * - Exports evolution metrics for the worker dashboard
 *
 * All learning is on-device. No data leaves the phone.
 */
@Singleton
class SelfEvolutionManager @Inject constructor(
    private val patternDao: PatternDao,
    private val transactionDao: TransactionDao,
    private val feedbackCollector: FeedbackCollector,
    private val adaptiveLearning: AdaptiveLearningEngine,
    private val patternTracker: BusinessPatternTracker,
    private val learningAgent: LearningAgent,
    private val adaptiveVocabulary: AdaptiveVocabulary
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Preference State (in-memory, persisted to PatternDao) ──
    private val _preferences = MutableStateFlow(WorkerPreferences())
    val preferences: StateFlow<WorkerPreferences> = _preferences.asStateFlow()

    // ── Evolution Metrics ──
    private val _evolutionMetrics = MutableStateFlow(EvolutionMetrics())
    val evolutionMetrics: StateFlow<EvolutionMetrics> = _evolutionMetrics.asStateFlow()

    companion object {
        private const val TAG = "SelfEvolution"

        // Pattern types for evolution tracking
        private const val PREFERENCE_KEY = "worker_preferences"
        private const val CORRECTION_PATTERN_KEY = "correction_pattern"
        private const val FEATURE_USAGE_KEY = "feature_usage"
        private const val SATISFACTION_KEY = "satisfaction_signal"
        private const val ADVICE_OUTCOME_KEY = "advice_outcome"

        // Minimum data points before making adaptations
        private const val MIN_CORRECTIONS_FOR_PATTERN = 3
        private const val MIN_INTERACTIONS_FOR_PREFERENCE = 5
        private const val SATISFACTION_WINDOW_DAYS = 30
    }

    // ═══════════════ INITIALIZATION ═══════════════

    init {
        // Load persisted preferences on startup
        scope.launch {
            loadPreferences()
            computeEvolutionMetrics()
        }
    }

    // ═══════════════ PREFERENCE LEARNING ═══════════════

    /**
     * Record a language preference signal.
     * Called every time the worker speaks in a particular language.
     */
    suspend fun recordLanguageSignal(language: String) = withContext(Dispatchers.IO) {
        val current = _preferences.value
        val langCounts = current.languageCounts.toMutableMap()
        langCounts[language] = (langCounts[language] ?: 0) + 1

        val preferredLang = langCounts.maxByOrNull { it.value }?.key ?: language
        val updated = current.copy(
            languageCounts = langCounts,
            preferredLanguage = preferredLang
        )
        updatePreferences(updated)

        // Track in learning agent
        learningAgent.recordPattern(
            PatternType.LANGUAGE_SWITCH,
            mapOf("language" to language, "count" to (langCounts[language] ?: 1)),
            confidence = 0.8
        )
    }

    /**
     * Record a voice speed preference signal.
     * Called when worker adjusts TTS speed or asks to slow down/speed up.
     */
    suspend fun recordVoiceSpeedSignal(speed: Float) = withContext(Dispatchers.IO) {
        val current = _preferences.value
        // Exponential moving average for smooth adaptation
        val newSpeed = current.preferredVoiceSpeed * 0.7f + speed * 0.3f
        val updated = current.copy(preferredVoiceSpeed = newSpeed.coerceIn(0.5f, 2.0f))
        updatePreferences(updated)
    }

    /**
     * Record a report format preference signal.
     * Called when worker requests specific format (daily vs weekly, detailed vs summary).
     */
    suspend fun recordReportFormatSignal(format: String) = withContext(Dispatchers.IO) {
        val current = _preferences.value
        val formatCounts = current.reportFormatCounts.toMutableMap()
        formatCounts[format] = (formatCounts[format] ?: 0) + 1

        val preferredFormat = formatCounts.maxByOrNull { it.value }?.key ?: format
        val updated = current.copy(
            reportFormatCounts = formatCounts,
            preferredReportFormat = preferredFormat
        )
        updatePreferences(updated)
    }

    /**
     * Record a time-of-day preference signal.
     * Called when worker interacts at specific times.
     */
    suspend fun recordTimeSignal(hour: Int) = withContext(Dispatchers.IO) {
        val current = _preferences.value
        val hourCounts = current.activeHourCounts.toMutableMap()
        hourCounts[hour] = (hourCounts[hour] ?: 0) + 1

        // Find peak hours (above average)
        val avg = hourCounts.values.average()
        val peakHours = hourCounts.filter { it.value > avg * 1.3 }.keys.sorted()
        val updated = current.copy(
            activeHourCounts = hourCounts,
            peakHours = peakHours
        )
        updatePreferences(updated)
    }

    /**
     * Get the worker's current preferences for injection into responses.
     */
    fun getPreferences(): WorkerPreferences = _preferences.value

    // ═══════════════ CORRECTION PATTERN ANALYSIS ═══════════════

    /**
     * Analyze correction patterns to identify systematic errors.
     * Returns patterns that occur frequently enough to be addressed.
     */
    suspend fun analyzeCorrectionPatterns(): List<CorrectionPattern> = withContext(Dispatchers.IO) {
        val recentCorrections = patternDao.getPatternsByType(PatternType.VOCABULARY)
        val correctionData = mutableListOf<CorrectionPattern>()

        // Group corrections by type and value
        val itemTypeCorrections = mutableMapOf<String, Int>()
        val priceCorrections = mutableMapOf<String, Int>()

        for (pattern in recentCorrections) {
            try {
                val data = json.decodeFromString<Map<String, String>>(pattern.data)
                if (data["type"] == "intent_correction") {
                    val original = data["original"] ?: continue
                    val corrected = data["corrected"] ?: continue
                    val key = "$original→$corrected"
                    itemTypeCorrections[key] = (itemTypeCorrections[key] ?: 0) + 1
                }
            } catch (_: Exception) { }
        }

        // Identify patterns with enough data
        for ((key, count) in itemTypeCorrections) {
            if (count >= MIN_CORRECTIONS_FOR_PATTERN) {
                val parts = key.split("→")
                if (parts.size == 2) {
                    correctionData.add(
                        CorrectionPattern(
                            originalValue = parts[0],
                            correctedValue = parts[1],
                            frequency = count,
                            type = CorrectionPatternType.ITEM_NAME
                        )
                    )
                }
            }
        }

        Timber.tag(TAG).d("Correction patterns found: %d", correctionData.size)
        correctionData
    }

    /**
     * Apply learned correction patterns to prevent repeated mistakes.
     * Called before processing each input.
     */
    suspend fun applyCorrectionPatterns(text: String): String = withContext(Dispatchers.IO) {
        var result = text
        val patterns = analyzeCorrectionPatterns()

        for (pattern in patterns) {
            if (result.lowercase().contains(pattern.originalValue.lowercase())) {
                result = result.replace(
                    Regex("""\b${Regex.escape(pattern.originalValue)}\b""", RegexOption.IGNORE_CASE),
                    pattern.correctedValue
                )
                Timber.tag(TAG).d("Applied correction: '%s' → '%s'", pattern.originalValue, pattern.correctedValue)
            }
        }

        result
    }

    // ═══════════════ FEATURE USAGE TRACKING ═══════════════

    /**
     * Record feature usage for tracking which features are most/least used.
     */
    suspend fun recordFeatureUsage(feature: String) = withContext(Dispatchers.IO) {
        learningAgent.recordPattern(
            PatternType.VOCABULARY,
            mapOf(
                "type" to "feature_usage",
                "feature" to feature,
                "timestamp" to System.currentTimeMillis()
            ),
            confidence = 0.9
        )

        // Update in-memory metrics
        val current = _evolutionMetrics.value
        val usageCounts = current.featureUsageCounts.toMutableMap()
        usageCounts[feature] = (usageCounts[feature] ?: 0) + 1
        _evolutionMetrics.value = current.copy(featureUsageCounts = usageCounts)
    }

    /**
     * Get feature usage rankings (most to least used).
     */
    suspend fun getFeatureUsageRanking(): Map<String, Int> = withContext(Dispatchers.IO) {
        _evolutionMetrics.value.featureUsageCounts.toSortedMap(compareByDescending {
            _evolutionMetrics.value.featureUsageCounts[it] ?: 0
        })
    }

    // ═══════════════ SATISFACTION SIGNALS ═══════════════

    /**
     * Record a satisfaction signal — did the worker act on the advice?
     * Called when:
     * - Worker records a transaction after receiving advice → positive
     * - Worker ignores advice and does something different → neutral
     * - Worker explicitly says advice was unhelpful → negative
     */
    suspend fun recordSatisfactionSignal(
        adviceId: String,
        signal: SatisfactionSignal,
        context: String = ""
    ) = withContext(Dispatchers.IO) {
        learningAgent.recordPattern(
            PatternType.VOCABULARY,
            mapOf(
                "type" to SATISFACTION_KEY,
                "adviceId" to adviceId,
                "signal" to signal.name,
                "context" to context,
                "timestamp" to System.currentTimeMillis()
            ),
            confidence = when (signal) {
                SatisfactionSignal.POSITIVE -> 0.9
                SatisfactionSignal.NEUTRAL -> 0.5
                SatisfactionSignal.NEGATIVE -> 0.3
            }
        )

        // Update metrics
        val current = _evolutionMetrics.value
        _evolutionMetrics.value = current.copy(
            positiveSatisfactionSignals = current.positiveSatisfactionSignals +
                    if (signal == SatisfactionSignal.POSITIVE) 1 else 0,
            negativeSatisfactionSignals = current.negativeSatisfactionSignals +
                    if (signal == SatisfactionSignal.NEGATIVE) 1 else 0,
            totalSatisfactionSignals = current.totalSatisfactionSignals + 1
        )

        Timber.tag(TAG).d("Satisfaction signal: %s for advice %s", signal, adviceId)
    }

    // ═══════════════ FEEDBACK-TO-ACTION PIPELINE ═══════════════

    /**
     * Process collected feedback and generate actionable improvements.
     * Closes the feedback loop: collect → analyze → act.
     */
    suspend fun processFeedbackLoop(): FeedbackActionResult = withContext(Dispatchers.IO) {
        val recentFeedback = feedbackCollector.getRecentFeedback(50)
        if (recentFeedback.isEmpty()) {
            return@withContext FeedbackActionResult(
                processedCount = 0,
                actionsGenerated = 0,
                insights = emptyList()
            )
        }

        val insights = mutableListOf<String>()
        var actionsGenerated = 0

        // Group by type
        val byType = recentFeedback.groupBy { it.type }

        // Process feature requests
        val featureRequests = byType[FeedbackCollector.FeedbackType.FEATURE_REQUEST] ?: emptyList()
        if (featureRequests.size >= 3) {
            insights.add("📊 ${featureRequests.size} feature requests received — workers want: ${featureRequests.take(3).joinToString(", ") { it.text.take(50) }}")
            actionsGenerated++
        }

        // Process bug reports
        val bugReports = byType[FeedbackCollector.FeedbackType.BUG_REPORT] ?: emptyList()
        if (bugReports.isNotEmpty()) {
            insights.add("🐛 ${bugReports.size} bug reports — most common: ${bugReports.first().text.take(50)}")
            actionsGenerated++
        }

        // Process improvement suggestions
        val improvements = byType[FeedbackCollector.FeedbackType.IMPROVEMENT] ?: emptyList()
        if (improvements.isNotEmpty()) {
            // Extract language-specific preferences from feedback
            for (feedback in improvements) {
                val text = feedback.text.lowercase()
                if (text.contains("speed") || text.contains("haraka") || text.contains("polepole")) {
                    insights.add("🎤 Workers want voice speed adjustment")
                    actionsGenerated++
                }
                if (text.contains("language") || text.contains("lugha")) {
                    insights.add("🌍 Language preference feedback received")
                    actionsGenerated++
                }
            }
        }

        // Process praise — positive reinforcement
        val praise = byType[FeedbackCollector.FeedbackType.PRAISE] ?: emptyList()
        if (praise.isNotEmpty()) {
            insights.add("❤️ ${praise.size} positive feedback — workers appreciate the app")
        }

        // Store evolution insight
        if (insights.isNotEmpty()) {
            learningAgent.recordPattern(
                PatternType.VOCABULARY,
                mapOf(
                    "type" to "evolution_insight",
                    "insights" to insights.joinToString("; "),
                    "feedbackCount" to recentFeedback.size,
                    "timestamp" to System.currentTimeMillis()
                ),
                confidence = 0.8
            )
        }

        Timber.tag(TAG).d("Feedback loop: %d feedback → %d actions, %d insights",
            recentFeedback.size, actionsGenerated, insights.size)

        FeedbackActionResult(
            processedCount = recentFeedback.size,
            actionsGenerated = actionsGenerated,
            insights = insights
        )
    }

    // ═══════════════ ADVICE REFINEMENT ═══════════════

    /**
     * Track advice delivery and outcome.
     * Called when advice is given, and later when outcome is measured.
     */
    suspend fun trackAdviceDelivery(
        adviceId: String,
        adviceType: String,
        adviceText: String,
        context: Map<String, String> = emptyMap()
    ) = withContext(Dispatchers.IO) {
        learningAgent.recordPattern(
            PatternType.VOCABULARY,
            mapOf(
                "type" to ADVICE_OUTCOME_KEY,
                "adviceId" to adviceId,
                "adviceType" to adviceType,
                "adviceText" to adviceText.take(200),
                "deliveredAt" to System.currentTimeMillis(),
                "actedOn" to "pending"
            ) + context,
            confidence = 0.5
        )

        Timber.tag(TAG).d("Advice tracked: %s (%s)", adviceId, adviceType)
    }

    /**
     * Record advice outcome — did the worker follow it?
     * Used to refine future advice quality.
     */
    suspend fun recordAdviceOutcome(
        adviceId: String,
        followed: Boolean,
        outcomeScore: Double = if (followed) 0.8 else 0.3
    ) = withContext(Dispatchers.IO) {
        // Record satisfaction signal
        val signal = if (followed) SatisfactionSignal.POSITIVE else SatisfactionSignal.NEUTRAL
        recordSatisfactionSignal(adviceId, signal, "outcome_score=$outcomeScore")

        // Update advice pattern
        learningAgent.recordPattern(
            PatternType.VOCABULARY,
            mapOf(
                "type" to "advice_outcome_result",
                "adviceId" to adviceId,
                "followed" to followed.toString(),
                "outcomeScore" to outcomeScore,
                "timestamp" to System.currentTimeMillis()
            ),
            confidence = outcomeScore
        )

        Timber.tag(TAG).d("Advice outcome: %s → followed=%b, score=%.2f", adviceId, followed, outcomeScore)
    }

    /**
     * Get advice effectiveness scores by type.
     * Used to prioritize which advice types to give.
     */
    suspend fun getAdviceEffectiveness(): Map<String, Double> = withContext(Dispatchers.IO) {
        val patterns = patternDao.getPatternsByType(PatternType.VOCABULARY)
        val adviceScores = mutableMapOf<String, MutableList<Double>>()

        for (pattern in patterns) {
            try {
                val data = json.decodeFromString<Map<String, String>>(pattern.data)
                if (data["type"] == "advice_outcome_result") {
                    val score = data["outcomeScore"]?.toDoubleOrNull() ?: continue
                    // Find the original advice type
                    val adviceId = data["adviceId"] ?: continue
                    val type = adviceId.substringBefore("_", "general")
                    adviceScores.getOrPut(type) { mutableListOf() }.add(score)
                }
            } catch (_: Exception) { }
        }

        adviceScores.mapValues { (_, scores) -> scores.average() }
    }

    // ═══════════════ GOAL ADAPTATION ═══════════════

    /**
     * Analyze goal progress and suggest adaptations.
     * If worker consistently misses targets, suggest lower targets.
     * If worker consistently exceeds targets, suggest higher targets.
     */
    suspend fun analyzeGoalAdaptation(
        goalId: Long,
        currentTarget: Double,
        actualProgress: Double,
        daysElapsed: Int,
        totalDays: Int
    ): GoalAdaptationSuggestion = withContext(Dispatchers.IO) {
        if (daysElapsed < 3 || totalDays <= 0) {
            return@withContext GoalAdaptationSuggestion(
                goalId = goalId,
                suggestion = GoalSuggestion.NO_CHANGE,
                reason = "Not enough data yet",
                suggestedTarget = currentTarget
            )
        }

        val expectedProgress = (daysElapsed.toDouble() / totalDays) * currentTarget
        val progressRatio = if (expectedProgress > 0) actualProgress / expectedProgress else 1.0
        val dailyRate = actualProgress / daysElapsed
        val projectedTotal = dailyRate * totalDays
        val projectionRatio = if (currentTarget > 0) projectedTotal / currentTarget else 1.0

        val suggestion = when {
            // Worker is significantly behind (>30% under target pace)
            progressRatio < 0.7 -> {
                val adjustedTarget = (projectedTotal * 1.1).coerceAtLeast(currentTarget * 0.7)
                GoalAdaptationSuggestion(
                    goalId = goalId,
                    suggestion = GoalSuggestion.REDUCE_TARGET,
                    reason = "You're at ${(progressRatio * 100).toInt()}% of expected pace. " +
                            "Consider a more achievable target of KSh ${"%.0f".format(adjustedTarget)}.",
                    suggestedTarget = adjustedTarget,
                    confidence = 0.8
                )
            }
            // Worker is significantly ahead (>30% over target pace)
            progressRatio > 1.3 -> {
                val adjustedTarget = (projectedTotal * 0.9).coerceAtMost(currentTarget * 1.5)
                GoalAdaptationSuggestion(
                    goalId = goalId,
                    suggestion = GoalSuggestion.INCREASE_TARGET,
                    reason = "Great progress! At ${(progressRatio * 100).toInt()}% pace. " +
                            "You could aim for KSh ${"%.0f".format(adjustedTarget)}.",
                    suggestedTarget = adjustedTarget,
                    confidence = 0.7
                )
            }
            // On track
            else -> GoalAdaptationSuggestion(
                goalId = goalId,
                suggestion = GoalSuggestion.NO_CHANGE,
                reason = "On track at ${(progressRatio * 100).toInt()}% of expected pace.",
                suggestedTarget = currentTarget,
                confidence = 0.9
            )
        }

        // Record the adaptation analysis
        learningAgent.recordPattern(
            PatternType.VOCABULARY,
            mapOf(
                "type" to "goal_adaptation",
                "goalId" to goalId,
                "progressRatio" to progressRatio,
                "projectionRatio" to projectionRatio,
                "suggestion" to suggestion.suggestion.name,
                "timestamp" to System.currentTimeMillis()
            ),
            confidence = suggestion.confidence
        )

        Timber.tag(TAG).d("Goal %d adaptation: ratio=%.2f, suggestion=%s",
            goalId, progressRatio, suggestion.suggestion)

        suggestion
    }

    // ═══════════════ EVOLUTION METRICS ═══════════════

    /**
     * Compute overall evolution metrics.
     * Shows how much the system has learned and improved.
     */
    suspend fun computeEvolutionMetrics(): EvolutionMetrics = withContext(Dispatchers.IO) {
        try {
            val stats = adaptiveLearning.getLearningStats()
            val patterns = patternDao.getPatternsByType(PatternType.VOCABULARY)

            // Count feature usage
            val featureUsage = mutableMapOf<String, Int>()
            var positiveSignals = 0
            var negativeSignals = 0
            var totalSignals = 0

            for (pattern in patterns) {
                try {
                    val data = json.decodeFromString<Map<String, String>>(pattern.data)
                    when (data["type"]) {
                        "feature_usage" -> {
                            val feature = data["feature"] ?: continue
                            featureUsage[feature] = (featureUsage[feature] ?: 0) + 1
                        }
                        SATISFACTION_KEY -> {
                            totalSignals++
                            when (data["signal"]) {
                                "POSITIVE" -> positiveSignals++
                                "NEGATIVE" -> negativeSignals++
                            }
                        }
                    }
                } catch (_: Exception) { }
            }

            val metrics = EvolutionMetrics(
                vocabularySize = stats.vocabularySize,
                highConfidenceVocabulary = stats.highConfidenceVocabulary,
                averageConfidence = stats.averageConfidence,
                totalCorrections = stats.totalCorrections,
                personalizationLevel = stats.personalizationLevel,
                featureUsageCounts = featureUsage,
                positiveSatisfactionSignals = positiveSignals,
                negativeSatisfactionSignals = negativeSignals,
                totalSatisfactionSignals = totalSignals,
                satisfactionRate = if (totalSignals > 0) positiveSignals.toDouble() / totalSignals else 0.0,
                adaptationScore = calculateAdaptationScore(stats, totalSignals, positiveSignals)
            )

            _evolutionMetrics.value = metrics
            metrics
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to compute evolution metrics")
            EvolutionMetrics()
        }
    }

    /**
     * Calculate an overall adaptation score (0.0–1.0).
     * Combines vocabulary learning, correction learning, and satisfaction.
     */
    private fun calculateAdaptationScore(
        stats: com.msaidizi.app.agent.LearningStats,
        totalSignals: Int,
        positiveSignals: Int
    ): Double {
        val vocabScore = (stats.vocabularySize.coerceAtMost(100) / 100.0) * 0.3
        val confidenceScore = stats.averageConfidence * 0.3
        val correctionScore = (stats.totalCorrections.coerceAtMost(50) / 50.0) * 0.2
        val satisfactionScore = if (totalSignals > 0) {
            (positiveSignals.toDouble() / totalSignals) * 0.2
        } else 0.1

        return (vocabScore + confidenceScore + correctionScore + satisfactionScore).coerceIn(0.0, 1.0)
    }

    // ═══════════════ PERSISTENCE ═══════════════

    private suspend fun updatePreferences(updated: WorkerPreferences) {
        _preferences.value = updated
        // Persist to PatternDao
        try {
            val dataJson = json.encodeToString(updated)
            learningAgent.recordPattern(
                PatternType.VOCABULARY,
                mapOf(
                    "type" to PREFERENCE_KEY,
                    "data" to dataJson
                ),
                confidence = 0.95
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to persist preferences")
        }
    }

    private suspend fun loadPreferences() {
        try {
            val patterns = patternDao.getPatternsByType(PatternType.VOCABULARY)
            val prefPattern = patterns.lastOrNull { pattern ->
                try {
                    val data = json.decodeFromString<Map<String, String>>(pattern.data)
                    data["type"] == PREFERENCE_KEY
                } catch (_: Exception) { false }
            }

            if (prefPattern != null) {
                val data = json.decodeFromString<Map<String, String>>(prefPattern.data)
                val prefData = data["data"]
                if (prefData != null) {
                    val loaded = json.decodeFromString<WorkerPreferences>(prefData)
                    _preferences.value = loaded
                    Timber.tag(TAG).d("Loaded preferences: lang=%s, speed=%.1f",
                        loaded.preferredLanguage, loaded.preferredVoiceSpeed)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to load preferences, using defaults")
        }
    }

    // ═══════════════ BACKGROUND EVOLUTION ═══════════════

    /**
     * Run all background evolution tasks.
     * Call during heartbeats or when device is idle/charging.
     */
    suspend fun runEvolutionCycle() = withContext(Dispatchers.IO) {
        Timber.tag(TAG).d("Starting evolution cycle...")

        try {
            // 1. Analyze correction patterns
            val patterns = analyzeCorrectionPatterns()
            Timber.tag(TAG).d("Correction patterns: %d", patterns.size)

            // 2. Process feedback loop
            val feedbackResult = processFeedbackLoop()
            Timber.tag(TAG).d("Feedback processed: %d → %d actions",
                feedbackResult.processedCount, feedbackResult.actionsGenerated)

            // 3. Compute evolution metrics
            val metrics = computeEvolutionMetrics()
            Timber.tag(TAG).d("Evolution score: %.2f, personalization: %s",
                metrics.adaptationScore, metrics.personalizationLevel)

            // 4. Run adaptive learning background tasks
            adaptiveLearning.runBackgroundLearning()

            Timber.tag(TAG).d("Evolution cycle complete")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Evolution cycle failed")
        }
    }

    /**
     * Launch evolution cycle in background (fire-and-forget).
     */
    fun launchEvolutionCycle() {
        scope.launch {
            runEvolutionCycle()
        }
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/**
 * Worker preferences learned from interaction patterns.
 */
@kotlinx.serialization.Serializable
data class WorkerPreferences(
    val preferredLanguage: String = "sw",
    val languageCounts: Map<String, Int> = emptyMap(),
    val preferredVoiceSpeed: Float = 1.0f,
    val preferredReportFormat: String = "daily",
    val reportFormatCounts: Map<String, Int> = emptyMap(),
    val peakHours: List<Int> = emptyList(),
    val activeHourCounts: Map<Int, Int> = emptyMap()
)

/**
 * Evolution metrics showing system learning progress.
 */
data class EvolutionMetrics(
    val vocabularySize: Int = 0,
    val highConfidenceVocabulary: Int = 0,
    val averageConfidence: Double = 0.0,
    val totalCorrections: Int = 0,
    val personalizationLevel: com.msaidizi.app.agent.PersonalizationLevel = com.msaidizi.app.agent.PersonalizationLevel.NONE,
    val featureUsageCounts: Map<String, Int> = emptyMap(),
    val positiveSatisfactionSignals: Int = 0,
    val negativeSatisfactionSignals: Int = 0,
    val totalSatisfactionSignals: Int = 0,
    val satisfactionRate: Double = 0.0,
    val adaptationScore: Double = 0.0
)

/**
 * Correction pattern identified from repeated corrections.
 */
data class CorrectionPattern(
    val originalValue: String,
    val correctedValue: String,
    val frequency: Int,
    val type: CorrectionPatternType
)

enum class CorrectionPatternType {
    ITEM_NAME,
    PRICE,
    QUANTITY,
    CATEGORY
}

/**
 * Satisfaction signal for advice/outcome tracking.
 */
enum class SatisfactionSignal {
    POSITIVE,   // Worker acted on advice or confirmed it helped
    NEUTRAL,    // Worker ignored advice
    NEGATIVE    // Worker explicitly said it didn't help
}

/**
 * Result of processing the feedback loop.
 */
data class FeedbackActionResult(
    val processedCount: Int,
    val actionsGenerated: Int,
    val insights: List<String>
)

/**
 * Goal adaptation suggestion.
 */
data class GoalAdaptationSuggestion(
    val goalId: Long,
    val suggestion: GoalSuggestion,
    val reason: String,
    val suggestedTarget: Double,
    val confidence: Double = 0.5
)

enum class GoalSuggestion {
    REDUCE_TARGET,   // Worker falling behind — suggest lower goal
    INCREASE_TARGET,  // Worker exceeding — suggest higher goal
    NO_CHANGE         // On track
}
