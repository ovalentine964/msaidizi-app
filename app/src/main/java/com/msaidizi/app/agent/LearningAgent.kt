package com.msaidizi.app.agent

import com.msaidizi.app.core.database.InventoryDao
import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.core.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Learning Agent — adapts to user's vocabulary, business patterns, preferences.
 * Code-based pattern tracking + data collection for future LoRA fine-tuning.
 *
 * What gets learned (without LLM):
 * - User vocabulary ("mahindi" → "maize")
 * - Restocking cycles
 * - Price trends
 * - Peak selling hours
 * - Day-of-week patterns
 */
@Singleton
class LearningAgent @Inject constructor(
    private val patternDao: PatternDao,
    private val inventoryDao: InventoryDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    // === VOCABULARY LEARNING ===

    /**
     * Record a new vocabulary mapping.
     * Example: user says "mahindi", system knows it means "maize"
     */
    suspend fun recordUserTerm(spoken: String, canonical: String, language: String = "sw") {
        val existing = patternDao.getVocabularyEntry(spoken)
        if (existing != null) {
            patternDao.incrementFrequency(spoken)
        } else {
            patternDao.upsertVocabulary(VocabularyEntry(
                spokenForm = spoken.lowercase(),
                canonicalForm = canonical.lowercase(),
                language = language
            ))
        }
        Timber.d("Vocabulary: '%s' → '%s'", spoken, canonical)
    }

    /**
     * Look up canonical form for a spoken term.
     */
    suspend fun getCanonicalForm(spoken: String): String? {
        return patternDao.getCanonicalForm(spoken.lowercase())
    }

    /**
     * Get the user's most frequently used terms.
     */
    suspend fun getTopVocabulary(limit: Int = 20): List<VocabularyEntry> {
        return patternDao.getTopVocabulary(limit)
    }

    // === PATTERN LEARNING ===

    /**
     * Record a business pattern.
     */
    suspend fun recordPattern(
        type: PatternType,
        data: Map<String, Any>,
        confidence: Double = 0.5
    ) {
        val dataJson = json.encodeToString(data.mapValues { it.value.toString() })

        // Check if pattern already exists
        val key = data.keys.firstOrNull() ?: "default"
        val existing = patternDao.getPatternByKey(type, key)

        if (existing != null) {
            // Update existing pattern
            patternDao.updatePattern(existing.copy(
                data = dataJson,
                confidence = (existing.confidence + confidence) / 2,
                updatedAt = System.currentTimeMillis() / 1000
            ))
        } else {
            // Create new pattern
            patternDao.insertPattern(BusinessPattern(
                patternType = type,
                data = dataJson,
                confidence = confidence
            ))
        }
    }

    /**
     * Learn restocking cycle for an item.
     * Analyzes purchase history to predict when user will need to restock.
     */
    suspend fun learnRestockCycle(item: String) {
        // This would analyze purchase timestamps to find patterns
        // For now, store the data for future analysis
        val inventoryItem = inventoryDao.getItem(item)
        if (inventoryItem != null) {
            recordPattern(
                PatternType.RESTOCK_CYCLE,
                mapOf(
                    "item" to item,
                    "currentStock" to inventoryItem.currentStock,
                    "avgCost" to inventoryItem.avgCost,
                    "threshold" to inventoryItem.restockThreshold
                )
            )
        }
    }

    /**
     * Record a price observation for trend analysis.
     */
    suspend fun recordPriceObservation(item: String, price: Double) {
        recordPattern(
            PatternType.PRICE_TREND,
            mapOf(
                "item" to item,
                "price" to price,
                "timestamp" to System.currentTimeMillis()
            ),
            confidence = 0.8
        )
    }

    /**
     * Record a sale time for peak hours analysis.
     */
    suspend fun recordSaleTime(hour: Int, dayOfWeek: Int) {
        recordPattern(
            PatternType.PEAK_HOURS,
            mapOf(
                "hour" to hour,
                "dayOfWeek" to dayOfWeek
            ),
            confidence = 0.6
        )
    }

    /**
     * Record a language switch event.
     */
    suspend fun recordLanguageSwitch(from: String, to: String) {
        recordPattern(
            PatternType.LANGUAGE_SWITCH,
            mapOf("from" to from, "to" to to),
            confidence = 0.9
        )
    }

    /**
     * Record day-of-week sales pattern.
     */
    suspend fun recordDayOfWeekSales(dayOfWeek: Int, sales: Double) {
        recordPattern(
            PatternType.DAY_OF_WEEK,
            mapOf(
                "dayOfWeek" to dayOfWeek,
                "sales" to sales
            ),
            confidence = 0.7
        )
    }

    // === PATTERN RETRIEVAL ===

    /**
     * Get patterns of a specific type.
     */
    suspend fun getPatterns(type: PatternType): List<BusinessPattern> {
        return patternDao.getPatternsByType(type)
    }

    /**
     * Get all learned vocabulary for a language.
     */
    suspend fun getVocabulary(language: String = "sw"): List<VocabularyEntry> {
        return patternDao.getVocabularyForLanguage(language)
    }

    /**
     * Check if the system has learned enough about this user.
     */
    suspend fun getLearningProgress(): LearningProgress {
        val vocabulary = patternDao.getTopVocabulary(100)
        val patterns = patternDao.getPatternsByType(PatternType.RESTOCK_CYCLE)

        return LearningProgress(
            vocabularySize = vocabulary.size,
            patternCount = patterns.size,
            isReady = vocabulary.size >= 10 && patterns.size >= 3
        )
    }

    /**
     * Export learned data for future LoRA fine-tuning.
     * Returns interaction pairs suitable for training.
     */
    suspend fun exportTrainingData(): List<Pair<String, String>> {
        // TODO: Collect user interaction history
        // This would be used for on-device LoRA fine-tuning
        return emptyList()
    }
}

/**
 * Learning progress status.
 */
data class LearningProgress(
    val vocabularySize: Int,
    val patternCount: Int,
    val isReady: Boolean
)
