package com.msaidizi.app.superagent.memory

import com.msaidizi.app.core.database.ConversationDao
import com.msaidizi.app.core.database.KnowledgeDao
import com.msaidizi.app.core.database.UserProfileDao
import com.msaidizi.app.core.util.DateTimeUtil
import com.msaidizi.app.model.ConversationEntity
import com.msaidizi.app.model.KnowledgeEntity
import com.msaidizi.app.superagent.harness.IntentType
import com.msaidizi.app.superagent.harness.UserIntent
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MemoryManager — 5-layer memory hierarchy for the superagent.
 *
 * Layers (fast → slow):
 * 1. Working Memory — Current session, last few turns
 * 2. Conversation Memory — Recent conversations (last 24-48h)
 * 3. Daily Summary — Compressed daily patterns
 * 4. Pattern Memory — Weekly/monthly business patterns
 * 5. Knowledge Base — Long-term learned vocabulary, facts
 */
@Singleton
class MemoryManager @Inject constructor(
    private val conversationDao: ConversationDao,
    private val knowledgeDao: KnowledgeDao,
    private val userProfileDao: UserProfileDao,
    private val gson: Gson
) {
    // Working memory — last few interactions (in-memory only)
    private val workingMemory = mutableListOf<WorkingMemoryEntry>()
    private val maxWorkingMemory = 10

    /**
     * Update working memory after each interaction.
     */
    suspend fun updateWorkingMemory(input: String, response: String, intent: UserIntent) {
        workingMemory.add(
            WorkingMemoryEntry(
                input = input,
                response = response,
                intentType = intent.type,
                timestamp = System.currentTimeMillis()
            )
        )
        // Trim to max size
        while (workingMemory.size > maxWorkingMemory) {
            workingMemory.removeAt(0)
        }

        // Store learned vocabulary if new words detected
        storeLearnedVocabulary(input)
    }

    /**
     * Retrieve context relevant to the current query.
     */
    suspend fun retrieveContext(query: String, intentType: IntentType): MemoryContext {
        // Layer 1: Working memory
        val recent = workingMemory.takeLast(5).map { "${it.input} → ${it.response}" }

        // Layer 2: Recent conversation
        val conversation = try {
            conversationDao.getRecent(20).first().takeLast(10).map { "${it.role}: ${it.content}" }
        } catch (e: Exception) {
            emptyList()
        }

        // Layer 3: Daily patterns
        val dailyPatterns = knowledgeDao.getByCategory("daily_pattern").first()
            .sortedByDescending { it.updatedAt }
            .take(3)
            .map { it.value }

        // Layer 4: Business patterns
        val businessPatterns = knowledgeDao.getByCategory("business_pattern").first()
            .sortedByDescending { it.confidence }
            .take(3)
            .map { it.value }

        // Layer 5: Knowledge base
        val knowledge = getRelevantKnowledge(query, intentType)

        return MemoryContext(
            workingMemory = recent,
            conversationHistory = conversation,
            dailyPatterns = dailyPatterns,
            businessPatterns = businessPatterns,
            knowledgeEntries = knowledge
        )
    }

    /**
     * Compress daily conversation into patterns.
     * Call this at end of day or during heartbeat.
     */
    suspend fun compressDaily() {
        try {
            val todayStart = DateTimeUtil.startOfDay()
            val todayEnd = DateTimeUtil.endOfDay()
            val todayConversations = conversationDao.getRecent(200).first()
                .filter { it.timestamp in todayStart..todayEnd }

            if (todayConversations.isEmpty()) return

            // Extract key stats
            val userMessages = todayConversations.filter { it.role == "user" }
            val intents = todayConversations.mapNotNull { it.intent }.groupingBy { it }.eachCount()
            val topIntent = intents.maxByOrNull { it.value }

            val summary = KnowledgeEntity(
                category = "daily_pattern",
                key = "daily_${DateTimeUtil.today()}",
                value = gson.toJson(
                    mapOf(
                        "date" to DateTimeUtil.today(),
                        "totalMessages" to todayConversations.size,
                        "userMessages" to userMessages.size,
                        "topIntent" to (topIntent?.key ?: "none"),
                        "intentDistribution" to intents
                    )
                ),
                confidence = 1.0f
            )
            knowledgeDao.insert(summary)

            Timber.d("Compressed daily memory: ${todayConversations.size} messages")
        } catch (e: Exception) {
            Timber.e(e, "Failed to compress daily memory")
        }
    }

    /**
     * Prune old memory entries.
     */
    suspend fun pruneOld(maxAgeDays: Int = 30) {
        val cutoff = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000L)
        conversationDao.deleteOlderThan(cutoff)
        Timber.d("Pruned conversations older than $maxAgeDays days")
    }

    private suspend fun storeLearnedVocabulary(input: String) {
        // Detect non-standard words that might be local dialect or slang
        val words = input.lowercase().split(Regex("\\s+"))
        val knownWords = knowledgeDao.getByCategory("vocab").first().map { it.key.lowercase() }.toSet()

        for (word in words) {
            if (word.length > 3 && word !in knownWords && !word.matches(Regex(".*\\d.*"))) {
                // Store as potential new vocabulary — low confidence until confirmed
                knowledgeDao.insert(
                    KnowledgeEntity(
                        category = "vocab",
                        key = word,
                        value = "unconfirmed",
                        confidence = 0.3f
                    )
                )
            }
        }
    }

    private suspend fun getRelevantKnowledge(query: String, intentType: IntentType): List<String> {
        val category = when (intentType) {
            IntentType.RECORD_SALE, IntentType.ASK_SALES_TODAY -> "business_pattern"
            IntentType.ASK_ADVICE -> "advice"
            IntentType.ASK_STOCK, IntentType.RECORD_PURCHASE -> "stock_pattern"
            else -> "general"
        }

        return try {
            knowledgeDao.getByCategory(category).first()
                .sortedByDescending { it.confidence * it.usageCount }
                .take(5)
                .map { "${it.key}: ${it.value}" }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

data class WorkingMemoryEntry(
    val input: String,
    val response: String,
    val intentType: IntentType,
    val timestamp: Long
)

data class MemoryContext(
    val workingMemory: List<String>,
    val conversationHistory: List<String>,
    val dailyPatterns: List<String>,
    val businessPatterns: List<String>,
    val knowledgeEntries: List<String>
)
