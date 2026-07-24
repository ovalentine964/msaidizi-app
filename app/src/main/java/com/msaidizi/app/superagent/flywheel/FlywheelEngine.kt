package com.msaidizi.app.superagent.flywheel

import com.msaidizi.app.core.database.KnowledgeDao
import com.msaidizi.app.core.util.DateTimeUtil
import com.msaidizi.app.model.KnowledgeEntity
import com.msaidizi.app.superagent.harness.UserIntent
import com.msaidizi.app.superagent.tools.ToolResult
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FlywheelEngine — Self-improvement through interaction learning.
 *
 * After each interaction, learns:
 * - New vocabulary & dialect words
 * - Business patterns (peak hours, popular products)
 * - User preferences (language, detail level)
 * - Intent classification improvements
 */
@Singleton
class FlywheelEngine @Inject constructor(
    private val knowledgeDao: KnowledgeDao,
    private val gson: Gson
) {
    /**
     * Process an interaction for learning signals.
     */
    suspend fun processInteraction(
        input: String,
        response: String,
        intent: UserIntent,
        toolResults: List<ToolResult>
    ) {
        // Learn from successful tool executions
        for (result in toolResults) {
            if (result.success) {
                reinforcePattern(intent.type, result)
            }
        }

        // Learn vocabulary from user input
        learnVocabulary(input)

        // Track intent patterns for future classification improvement
        trackIntentPattern(input, intent)

        // Learn business patterns from transaction data
        if (intent.type in listOf(
                com.msaidizi.app.superagent.harness.IntentType.RECORD_SALE,
                com.msaidizi.app.superagent.harness.IntentType.RECORD_EXPENSE
            )
        ) {
            learnBusinessPattern(intent)
        }
    }

    private suspend fun reinforcePattern(intentType: com.msaidizi.app.superagent.harness.IntentType, result: ToolResult) {
        val key = "pattern_${intentType.name.lowercase()}"
        val existing = knowledgeDao.getEntry("business_pattern", key)

        if (existing != null) {
            knowledgeDao.update(
                existing.copy(
                    confidence = (existing.confidence + 0.05f).coerceAtMost(1.0f),
                    usageCount = existing.usageCount + 1,
                    updatedAt = System.currentTimeMillis()
                )
            )
        } else {
            knowledgeDao.insert(
                KnowledgeEntity(
                    category = "business_pattern",
                    key = key,
                    value = gson.toJson(mapOf("intent" to intentType.name, "lastSuccess" to DateTimeUtil.today())),
                    confidence = 0.5f,
                    usageCount = 1
                )
            )
        }
    }

    private suspend fun learnVocabulary(input: String) {
        val words = input.lowercase().split(Regex("\\s+")).filter { it.length > 3 }
        val known = knowledgeDao.getByCategory("vocab").first().map { it.key.lowercase() }.toSet()

        for (word in words) {
            if (word !in known && !word.matches(Regex(".*\\d.*"))) {
                knowledgeDao.insert(
                    KnowledgeEntity(
                        category = "vocab",
                        key = word,
                        value = "learned_from_input",
                        confidence = 0.2f
                    )
                )
            }
        }
    }

    private suspend fun trackIntentPattern(input: String, intent: UserIntent) {
        val key = "intent_${intent.type.name.lowercase()}_${input.take(30).replace(" ", "_")}"
        val existing = knowledgeDao.getEntry("intent_pattern", key)

        if (existing != null) {
            knowledgeDao.update(
                existing.copy(
                    usageCount = existing.usageCount + 1,
                    confidence = (existing.confidence + 0.1f).coerceAtMost(1.0f),
                    updatedAt = System.currentTimeMillis()
                )
            )
        } else {
            knowledgeDao.insert(
                KnowledgeEntity(
                    category = "intent_pattern",
                    key = key,
                    value = gson.toJson(mapOf("input" to input.take(100), "intent" to intent.type.name)),
                    confidence = 0.3f,
                    usageCount = 1
                )
            )
        }
    }

    private suspend fun learnBusinessPattern(intent: UserIntent) {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val key = "hourly_pattern_$hour"
        val existing = knowledgeDao.getEntry("business_pattern", key)

        if (existing != null) {
            knowledgeDao.update(
                existing.copy(
                    usageCount = existing.usageCount + 1,
                    updatedAt = System.currentTimeMillis()
                )
            )
        } else {
            knowledgeDao.insert(
                KnowledgeEntity(
                    category = "business_pattern",
                    key = key,
                    value = gson.toJson(mapOf("hour" to hour, "type" to intent.type.name)),
                    confidence = 0.4f,
                    usageCount = 1
                )
            )
        }
    }

    // ── Read-back methods ──────────────────────

    /**
     * Get learned vocabulary — words the worker has used that aren't in the base dictionary.
     * Returns a map of word → confidence score (higher = seen more often).
     */
    suspend fun getLearnedVocabulary(): Map<String, Float> {
        return try {
            knowledgeDao.getByCategory("vocab").first()
                .sortedByDescending { it.usageCount }
                .associate { it.key to it.confidence }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read learned vocabulary")
            emptyMap()
        }
    }

    /**
     * Get learned business patterns — hourly patterns, intent success rates, etc.
     * Returns a list of pattern entries with their metadata.
     */
    suspend fun getLearnedPatterns(): List<LearnedPattern> {
        return try {
            knowledgeDao.getByCategory("business_pattern").first()
                .sortedByDescending { it.confidence }
                .map { entry ->
                    val data = try {
                        gson.fromJson(entry.value, Map::class.java) as? Map<*, *> ?: emptyMap<String, Any>()
                    } catch (e: Exception) {
                        emptyMap<String, Any>()
                    }
                    LearnedPattern(
                        key = entry.key,
                        data = data.mapKeys { it.key.toString() }.mapValues { it.value?.toString() ?: "" },
                        confidence = entry.confidence,
                        usageCount = entry.usageCount
                    )
                }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read learned patterns")
            emptyList()
        }
    }

    /**
     * Get advice confidence — which advice/intent patterns have worked before.
     * Returns a map of intent_type → confidence score.
     */
    suspend fun getAdviceConfidence(): Map<String, Float> {
        return try {
            knowledgeDao.getByCategory("intent_pattern").first()
                .groupBy { entry ->
                    // Extract intent type from key: "intent_sale_recording_xxx" → "SALE"
                    entry.key.removePrefix("intent_").substringBefore("_")
                }
                .mapValues { (_, entries) ->
                    entries.maxOf { it.confidence }
                }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read advice confidence")
            emptyMap()
        }
    }

    /**
     * Get vocabulary words as a flat set for quick lookup.
     */
    suspend fun getVocabularyWords(): Set<String> {
        return try {
            knowledgeDao.getByCategory("vocab").first()
                .map { it.key.lowercase() }
                .toSet()
        } catch (e: Exception) {
            Timber.w(e, "Failed to read vocabulary words")
            emptySet()
        }
    }

    /**
     * Get hourly business activity pattern.
     * Returns hour (0-23) → activity score based on recorded transactions.
     */
    suspend fun getHourlyPatterns(): Map<Int, Float> {
        return try {
            knowledgeDao.getByCategory("business_pattern").first()
                .filter { it.key.startsWith("hourly_pattern_") }
                .associate { entry ->
                    val hour = entry.key.removePrefix("hourly_pattern_").toIntOrNull() ?: 0
                    hour to entry.confidence
                }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read hourly patterns")
            emptyMap()
        }
    }
}

data class LearnedPattern(
    val key: String,
    val data: Map<String, String>,
    val confidence: Float,
    val usageCount: Int
)
