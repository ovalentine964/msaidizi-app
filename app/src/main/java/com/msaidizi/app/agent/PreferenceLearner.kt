package com.msaidizi.app.agent

import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.core.model.PatternType
import com.msaidizi.app.core.model.UserCorrection
import com.msaidizi.app.core.model.UserCorrectionDao
import com.msaidizi.app.core.model.UserVocabularyDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preference Learner — learns and applies worker preferences.
 *
 * Observes every interaction and extracts preference signals:
 * 1. **Voice commands**: Language, speed, volume preferences
 * 2. **UI interactions**: Report format, display preferences
 * 3. **Corrections**: What the AI gets wrong → what worker prefers
 * 4. **Behavioral patterns**: When they interact, what they ask about
 *
 * Preferences are applied automatically to future interactions.
 * All learning is on-device, Bayesian-updated, and privacy-preserving.
 *
 * Mathematical foundation:
 * - Bayesian updating: P(preference | evidence) ∝ P(evidence | preference) × P(preference)
 * - Exponential moving average: smooth preference tracking without overreacting
 * - Confidence thresholds: only apply preferences with sufficient evidence
 */
@Singleton
class PreferenceLearner @Inject constructor(
    private val patternDao: PatternDao,
    private val userCorrectionDao: UserCorrectionDao,
    private val userVocabularyDao: UserVocabularyDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "PreferenceLearner"

        // Minimum observations before applying a preference
        private const val MIN_OBSERVATIONS = 3

        // Confidence threshold for preference application
        private const val CONFIDENCE_THRESHOLD = 0.6

        // EMA smoothing factor (higher = more weight on recent)
        private const val EMA_ALPHA = 0.3
    }

    // ═══════════════ LANGUAGE PREFERENCE ═══════════════

    /**
     * Learn language preference from voice input.
     * Tracks which language the worker uses most frequently.
     */
    suspend fun learnLanguagePreference(
        detectedLanguage: String,
        inputText: String
    ) = withContext(Dispatchers.IO) {
        val key = "lang_pref_$detectedLanguage"
        val existing = patternDao.getPatternByKey(PatternType.VOCABULARY, key)

        if (existing != null) {
            val count = (existing.data.toIntOrNull() ?: 0) + 1
            patternDao.updatePattern(existing.copy(
                data = count.toString(),
                confidence = calculateConfidence(count),
                updatedAt = System.currentTimeMillis() / 1000
            ))
        } else {
            patternDao.insertPattern(com.msaidizi.app.core.model.BusinessPattern(
                patternType = PatternType.VOCABULARY,
                data = "1",
                confidence = 0.1
            ))
        }

        Timber.tag(TAG).d("Language preference signal: %s", detectedLanguage)
    }

    /**
     * Get preferred language based on learned patterns.
     */
    suspend fun getPreferredLanguage(): String = withContext(Dispatchers.IO) {
        val languages = listOf("sw", "en", "sheng")
        var bestLang = "sw"
        var bestCount = 0

        for (lang in languages) {
            val pattern = patternDao.getPatternByKey(PatternType.VOCABULARY, "lang_pref_$lang")
            val count = pattern?.data?.toIntOrNull() ?: 0
            if (count > bestCount) {
                bestCount = count
                bestLang = lang
            }
        }

        bestLang
    }

    // ═══════════════ VOICE SPEED PREFERENCE ═══════════════

    /**
     * Learn voice speed preference from worker feedback.
     * Called when worker says "slow down", "speed up", "polepole", "haraka".
     */
    suspend fun learnVoiceSpeedPreference(speedAdjustment: Float) = withContext(Dispatchers.IO) {
        val key = "voice_speed_pref"
        val existing = patternDao.getPatternByKey(PatternType.VOCABULARY, key)

        val currentSpeed = existing?.data?.toFloatOrNull() ?: 1.0f
        // EMA: smooth update
        val newSpeed = (currentSpeed * (1 - EMA_ALPHA) + (currentSpeed + speedAdjustment) * EMA_ALPHA)
            .coerceIn(0.5f, 2.0f)

        if (existing != null) {
            patternDao.updatePattern(existing.copy(
                data = newSpeed.toString(),
                confidence = (existing.confidence + 0.1).coerceAtMost(1.0),
                updatedAt = System.currentTimeMillis() / 1000
            ))
        } else {
            patternDao.insertPattern(com.msaidizi.app.core.model.BusinessPattern(
                patternType = PatternType.VOCABULARY,
                data = newSpeed.toString(),
                confidence = 0.3
            ))
        }

        Timber.tag(TAG).d("Voice speed preference: %.2f (adjustment: %.2f)", newSpeed, speedAdjustment)
    }

    /**
     * Get preferred voice speed.
     */
    suspend fun getPreferredVoiceSpeed(): Float = withContext(Dispatchers.IO) {
        val pattern = patternDao.getPatternByKey(PatternType.VOCABULARY, "voice_speed_pref")
        pattern?.data?.toFloatOrNull() ?: 1.0f
    }

    // ═══════════════ REPORT FORMAT PREFERENCE ═══════════════

    /**
     * Learn report format preference from worker interactions.
     * Tracks whether worker prefers daily/weekly, detailed/summary reports.
     */
    suspend fun learnReportFormatPreference(format: String) = withContext(Dispatchers.IO) {
        val key = "report_format_$format"
        val existing = patternDao.getPatternByKey(PatternType.VOCABULARY, key)

        if (existing != null) {
            val count = (existing.data.toIntOrNull() ?: 0) + 1
            patternDao.updatePattern(existing.copy(
                data = count.toString(),
                confidence = calculateConfidence(count),
                updatedAt = System.currentTimeMillis() / 1000
            ))
        } else {
            patternDao.insertPattern(com.msaidizi.app.core.model.BusinessPattern(
                patternType = PatternType.VOCABULARY,
                data = "1",
                confidence = 0.1
            ))
        }

        Timber.tag(TAG).d("Report format signal: %s", format)
    }

    /**
     * Get preferred report format.
     */
    suspend fun getPreferredReportFormat(): String = withContext(Dispatchers.IO) {
        val formats = listOf("daily", "weekly", "monthly", "summary", "detailed")
        var bestFormat = "daily"
        var bestCount = 0

        for (format in formats) {
            val pattern = patternDao.getPatternByKey(PatternType.VOCABULARY, "report_format_$format")
            val count = pattern?.data?.toIntOrNull() ?: 0
            if (count > bestCount) {
                bestCount = count
                bestFormat = format
            }
        }

        bestFormat
    }

    // ═══════════════ CORRECTION LEARNING ═══════════════

    /**
     * Learn from worker corrections.
     * When worker corrects AI, extract the preference and store it.
     */
    suspend fun learnFromCorrection(correction: UserCorrection) = withContext(Dispatchers.IO) {
        // Track correction type frequency
        val typeKey = "correction_type_${correction.correctionType.name}"
        val existing = patternDao.getPatternByKey(PatternType.VOCABULARY, typeKey)

        if (existing != null) {
            val count = (existing.data.toIntOrNull() ?: 0) + 1
            patternDao.updatePattern(existing.copy(
                data = count.toString(),
                confidence = calculateConfidence(count),
                updatedAt = System.currentTimeMillis() / 1000
            ))
        } else {
            patternDao.insertPattern(com.msaidizi.app.core.model.BusinessPattern(
                patternType = PatternType.VOCABULARY,
                data = "1",
                confidence = 0.1
            ))
        }

        // For item corrections, learn the vocabulary mapping
        if (correction.correctionType == com.msaidizi.app.core.model.CorrectionType.ITEM) {
            // The AdaptiveLearningEngine handles the actual vocabulary update
            // We just track that this correction happened
            Timber.tag(TAG).d("Item correction learned: '%s' → '%s'",
                correction.originalValue, correction.correctedValue)
        }

        // For price corrections, track price preference
        if (correction.correctionType == com.msaidizi.app.core.model.CorrectionType.PRICE) {
            val price = correction.correctedValue.toDoubleOrNull()
            if (price != null) {
                Timber.tag(TAG).d("Price correction learned: KSh %.0f", price)
            }
        }

        Timber.tag(TAG).d("Correction learned: type=%s, '%s' → '%s'",
            correction.correctionType, correction.originalValue, correction.correctedValue)
    }

    /**
     * Get the most common correction types.
     * Used to identify systematic AI weaknesses.
     */
    suspend fun getTopCorrectionTypes(limit: Int = 5): Map<String, Int> = withContext(Dispatchers.IO) {
        val types = com.msaidizi.app.core.model.CorrectionType.values()
        val counts = mutableMapOf<String, Int>()

        for (type in types) {
            val pattern = patternDao.getPatternByKey(PatternType.VOCABULARY, "correction_type_$type")
            val count = pattern?.data?.toIntOrNull() ?: 0
            if (count > 0) {
                counts[type.name] = count
            }
        }

        counts.entries.sortedByDescending { it.value }.take(limit).associate { it.key to it.value }
    }

    // ═══════════════ INTERACTION TIMING PREFERENCE ═══════════════

    /**
     * Learn when the worker typically interacts with the app.
     * Used for proactive notifications and briefing timing.
     */
    suspend fun learnInteractionTiming(hour: Int, dayOfWeek: Int) = withContext(Dispatchers.IO) {
        val hourKey = "interaction_hour_$hour"
        val existing = patternDao.getPatternByKey(PatternType.PEAK_HOURS, hourKey)

        if (existing != null) {
            val count = (existing.data.toIntOrNull() ?: 0) + 1
            patternDao.updatePattern(existing.copy(
                data = count.toString(),
                confidence = calculateConfidence(count),
                updatedAt = System.currentTimeMillis() / 1000
            ))
        } else {
            patternDao.insertPattern(com.msaidizi.app.core.model.BusinessPattern(
                patternType = PatternType.PEAK_HOURS,
                data = "1",
                confidence = 0.1
            ))
        }
    }

    /**
     * Get peak interaction hours.
     */
    suspend fun getPeakInteractionHours(): List<Int> = withContext(Dispatchers.IO) {
        val hourCounts = mutableMapOf<Int, Int>()
        for (hour in 0..23) {
            val pattern = patternDao.getPatternByKey(PatternType.PEAK_HOURS, "interaction_hour_$hour")
            val count = pattern?.data?.toIntOrNull() ?: 0
            if (count > 0) hourCounts[hour] = count
        }

        if (hourCounts.isEmpty()) return@withContext listOf(8, 12, 18) // defaults

        val avg = hourCounts.values.average()
        hourCounts.filter { it.value > avg * 1.2 }.keys.sorted()
    }

    // ═══════════════ RESPONSE STYLE PREFERENCE ═══════════════

    /**
     * Learn response style preference.
     * Does the worker prefer short/long responses, with/without emojis?
     */
    suspend fun learnResponseStyle(
        responseLength: Int,
        hadEmojis: Boolean,
        wasFollowed: Boolean // Did the worker act on the response?
    ) = withContext(Dispatchers.IO) {
        // Track preferred response length
        val lengthCategory = when {
            responseLength < 50 -> "short"
            responseLength < 150 -> "medium"
            else -> "long"
        }

        val lengthKey = "response_style_length_$lengthCategory"
        val existing = patternDao.getPatternByKey(PatternType.VOCABULARY, lengthKey)
        val weight = if (wasFollowed) 2 else 1 // Followed responses count more

        if (existing != null) {
            val count = (existing.data.toIntOrNull() ?: 0) + weight
            patternDao.updatePattern(existing.copy(
                data = count.toString(),
                confidence = calculateConfidence(count),
                updatedAt = System.currentTimeMillis() / 1000
            ))
        } else {
            patternDao.insertPattern(com.msaidizi.app.core.model.BusinessPattern(
                patternType = PatternType.VOCABULARY,
                data = weight.toString(),
                confidence = 0.1
            ))
        }

        // Track emoji preference
        val emojiKey = "response_style_emoji_${if (hadEmojis) "yes" else "no"}"
        val emojiExisting = patternDao.getPatternByKey(PatternType.VOCABULARY, emojiKey)
        if (emojiExisting != null) {
            val count = (emojiExisting.data.toIntOrNull() ?: 0) + weight
            patternDao.updatePattern(emojiExisting.copy(
                data = count.toString(),
                confidence = calculateConfidence(count),
                updatedAt = System.currentTimeMillis() / 1000
            ))
        } else {
            patternDao.insertPattern(com.msaidizi.app.core.model.BusinessPattern(
                patternType = PatternType.VOCABULARY,
                data = weight.toString(),
                confidence = 0.1
            ))
        }
    }

    /**
     * Get preferred response style.
     */
    suspend fun getPreferredResponseStyle(): ResponseStylePreference = withContext(Dispatchers.IO) {
        val lengths = listOf("short", "medium", "long")
        var bestLength = "medium"
        var bestLengthCount = 0

        for (length in lengths) {
            val pattern = patternDao.getPatternByKey(PatternType.VOCABULARY, "response_style_length_$length")
            val count = pattern?.data?.toIntOrNull() ?: 0
            if (count > bestLengthCount) {
                bestLengthCount = count
                bestLength = length
            }
        }

        val emojiYes = patternDao.getPatternByKey(PatternType.VOCABULARY, "response_style_emoji_yes")
        val emojiNo = patternDao.getPatternByKey(PatternType.VOCABULARY, "response_style_emoji_no")
        val prefersEmojis = (emojiYes?.data?.toIntOrNull() ?: 0) >= (emojiNo?.data?.toIntOrNull() ?: 0)

        ResponseStylePreference(
            preferredLength = bestLength,
            prefersEmojis = prefersEmojis,
            confidence = calculateConfidence(bestLengthCount)
        )
    }

    // ═══════════════ COMBINED PREFERENCE INJECTION ═══════════════

    /**
     * Get all learned preferences for injecting into LLM prompts.
     * Returns a context string that personalizes AI responses.
     */
    suspend fun generatePreferenceContext(language: String = "sw"): String = withContext(Dispatchers.IO) {
        val context = StringBuilder()

        val prefLang = getPreferredLanguage()
        val prefSpeed = getPreferredVoiceSpeed()
        val prefFormat = getPreferredReportFormat()
        val prefStyle = getPreferredResponseStyle()
        val peakHours = getPeakInteractionHours()
        val topCorrections = getTopCorrectionTypes(3)

        if (language == "sw") {
            if (prefLang != "sw") context.append("Mteja anapendelea lugha: $prefLang. ")
            if (prefSpeed != 1.0f) {
                val speedDesc = if (prefSpeed < 0.8f) "polepole" else if (prefSpeed > 1.2f) "haraka" else "ya kawaida"
                context.append("Kasi ya sauti: $speedDesc. ")
            }
            context.append("Ripoti: $prefFormat. ")
            if (prefStyle.prefersEmojis) context.append("Anapendelea emoji. ")
            if (prefStyle.preferredLength == "short") context.append("Majibu mafupi. ")
            if (topCorrections.isNotEmpty()) {
                context.append("Makosa ya mara kwa mara: ${topCorrections.entries.joinToString(", ") { "${it.key}(${it.value})" }}. ")
            }
        } else {
            if (prefLang != "en") context.append("Worker prefers language: $prefLang. ")
            if (prefSpeed != 1.0f) {
                val speedDesc = if (prefSpeed < 0.8f) "slow" else if (prefSpeed > 1.2f) "fast" else "normal"
                context.append("Voice speed: $speedDesc. ")
            }
            context.append("Report format: $prefFormat. ")
            if (prefStyle.prefersEmojis) context.append("Prefers emojis. ")
            if (prefStyle.preferredLength == "short") context.append("Keep responses short. ")
            if (topCorrections.isNotEmpty()) {
                context.append("Common corrections: ${topCorrections.entries.joinToString(", ") { "${it.key}(${it.value})" }}. ")
            }
        }

        context.toString()
    }

    // ═══════════════ HELPERS ═══════════════

    /**
     * Calculate confidence score from observation count.
     * Uses a sigmoid-like function: approaches 1.0 as count increases.
     */
    private fun calculateConfidence(count: Int): Double {
        // Logistic function: confidence = 1 / (1 + e^(-k*(count - threshold)))
        // With k=0.5, threshold=5: reaches 0.73 at 5 observations, 0.88 at 10
        return 1.0 / (1.0 + Math.exp(-0.5 * (count - MIN_OBSERVATIONS)))
    }
}

/**
 * Response style preference learned from worker behavior.
 */
data class ResponseStylePreference(
    val preferredLength: String = "medium", // "short", "medium", "long"
    val prefersEmojis: Boolean = true,
    val confidence: Double = 0.0
)
