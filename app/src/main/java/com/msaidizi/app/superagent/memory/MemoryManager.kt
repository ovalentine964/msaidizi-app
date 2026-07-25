package com.msaidizi.app.superagent.memory

import com.msaidizi.app.core.database.ConversationDao
import com.msaidizi.app.core.database.KnowledgeDao
import com.msaidizi.app.core.database.UserProfileDao
import com.msaidizi.app.superagent.tools.argSchema
import com.msaidizi.app.core.util.DateTimeUtil
import com.msaidizi.app.model.ConversationEntity
import com.msaidizi.app.model.KnowledgeEntity
import com.msaidizi.app.superagent.harness.IntentType
import com.msaidizi.app.superagent.harness.UserIntent
import com.msaidizi.app.superagent.tools.Tool
import com.msaidizi.app.superagent.tools.ToolResult
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MemoryManager — Unified 4-layer memory hierarchy for the superagent.
 *
 * Implements [Tool] so it can be wired into the tool execution pipeline.
 *
 * Layers (fast → slow, volatile → persistent):
 *   L1  Working Memory   — RAM, current session, last N turns (evicts oldest)
 *   L2  Conversation     — SQLite, recent conversations across sessions
 *   L3  Daily Summaries  — SQLite, compressed daily patterns (knowledge_entries)
 *   L4  Long-Term Patterns — SQLite, weekly/monthly patterns + vocabulary (knowledge_entries)
 *
 * Eviction policy: each layer is capped at [MAX_ENTRIES_PER_LAYER].
 * Cross-session: L2-L4 survive app restarts; L1 is rebuilt from L2 on init.
 */
@Singleton
class MemoryManager @Inject constructor(
    private val conversationDao: ConversationDao,
    private val knowledgeDao: KnowledgeDao,
    private val userProfileDao: UserProfileDao,
    private val gson: Gson
) : Tool {

    // ── Tool interface ──────────────────────────────────────────────
    override val name = "memory_manager"
    override val description = "4-layer persistent memory: working, conversation, daily summaries, long-term patterns"

    override val argsSchema = argSchema {
        enum("action", "Memory action",
            listOf("store", "retrieve", "compress", "status", "search", "forget"), required = false)
        string("key", "Memory key (for store)", required = false)
        string("value", "Value to store", required = false)
        string("layer", "Memory layer: working, conversation, daily, patterns",
            required = false)
        string("query", "Search query (for retrieve/search)", required = false)
        integer("max_tokens", "Maximum tokens to retrieve", required = false)
    }

    // ── Constants ───────────────────────────────────────────────────
    companion object {
        const val MAX_ENTRIES_PER_LAYER = 1000
        private const val WORKING_WINDOW = 10          // last N turns kept in L1
        private const val RECENT_CONVERSATION_LIMIT = 20
    }

    // ── L1: Working Memory (RAM, session-scoped) ────────────────────
    // Thread-safe deque; newest at tail, oldest auto-evicted from head.
    private val workingMemory = ConcurrentLinkedDeque<WorkingMemoryEntry>()

    // ── Tool.execute ────────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "retrieve"
        return when (action.lowercase()) {
            "store" -> {
                val key = params["key"]
                    ?: return ToolResult.error(name, "Key required", "MISSING_KEY")
                val value = params["value"]
                    ?: return ToolResult.error(name, "Value required", "MISSING_VALUE")
                val layer = params["layer"] ?: "long_term"
                storeMemory(key, value, layer)
                ToolResult.success(name, mapOf("key" to key, "layer" to layer), "Stored in $layer: $key")
            }
            "retrieve" -> {
                val query = params["query"]
                    ?: return ToolResult.error(name, "Query required", "MISSING_QUERY")
                val maxTokens = params["max_tokens"]?.toIntOrNull() ?: 1500
                val result = retrieve(query, maxTokens)
                ToolResult.success(name, mapOf("query" to query, "result_length" to result.length),
                    result.ifEmpty { "No relevant memory found" })
            }
            "compress" -> {
                compressDaily()
                ToolResult.success(name, message = "Daily memory compressed")
            }
            "status" -> {
                val stats = getLayerStats()
                ToolResult.success(name, stats, "L1=${stats["working"]}, L2=${stats["conversation"]}, " +
                    "L3=${stats["daily"]}, L4=${stats["patterns"]}")
            }
            "consolidate" -> {
                consolidateL1ToL2()
                ToolResult.success(name, message = "L1 → L2 consolidation complete")
            }
            "search_sessions" -> {
                val query = params["query"]
                    ?: return ToolResult.error(name, "Query required", "MISSING_QUERY")
                val results = searchAcrossSessions(query)
                ToolResult.success(name, mapOf("hit_count" to results.size), results.joinToString("\n"))
            }
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ── L1 → Working Memory ─────────────────────────────────────────

    /**
     * Update L1 after each interaction.
     * Called by the harness after every turn.
     */
    suspend fun updateWorkingMemory(input: String, response: String, intent: UserIntent) {
        val entry = WorkingMemoryEntry(
            input = input,
            response = response,
            intentType = intent.type,
            timestamp = System.currentTimeMillis()
        )
        workingMemory.addLast(entry)
        // Evict oldest if over capacity
        while (workingMemory.size > MAX_ENTRIES_PER_LAYER) {
            workingMemory.pollFirst()
        }
        // Also keep a tight rolling window for context assembly
        while (workingMemory.size > WORKING_WINDOW * 2) {
            workingMemory.pollFirst()
        }

        // Learn vocabulary inline
        storeLearnedVocabulary(input)
    }

    /**
     * Retrieve context from all 4 layers for the current query.
     */
    suspend fun retrieveContext(query: String, intentType: IntentType): MemoryContext {
        // L1: Working memory (last few turns)
        val recent = workingMemory.toList().takeLast(5)
            .map { "${it.input} → ${it.response}" }

        // L2: Recent conversation (cross-session, from SQLite)
        val conversation = try {
            conversationDao.getRecent(RECENT_CONVERSATION_LIMIT).first()
                .takeLast(10)
                .map { "${it.role}: ${it.content}" }
        } catch (e: Exception) {
            Timber.w(e, "L2 retrieve failed")
            emptyList()
        }

        // L3: Daily patterns
        val dailyPatterns = try {
            knowledgeDao.getByCategory(LAYER_DAILY).first()
                .sortedByDescending { it.updatedAt }
                .take(3)
                .map { it.value }
        } catch (e: Exception) {
            Timber.w(e, "L3 retrieve failed")
            emptyList()
        }

        // L4: Long-term patterns
        val businessPatterns = try {
            knowledgeDao.getByCategory(LAYER_PATTERNS).first()
                .sortedByDescending { it.confidence }
                .take(3)
                .map { it.value }
        } catch (e: Exception) {
            Timber.w(e, "L4 retrieve failed")
            emptyList()
        }

        // L4+: Knowledge base
        val knowledge = getRelevantKnowledge(query, intentType)

        return MemoryContext(
            workingMemory = recent,
            conversationHistory = conversation,
            dailyPatterns = dailyPatterns,
            businessPatterns = businessPatterns,
            knowledgeEntries = knowledge
        )
    }

    // ── L1 → L2 Consolidation ───────────────────────────────────────

    /**
     * Flush L1 working memory into L2 conversation store.
     * Call on session end, heartbeat, or when L1 is full.
     */
    suspend fun consolidateL1ToL2() {
        val entries = workingMemory.toList()
        if (entries.isEmpty()) return

        val sessionId = "consolidated_${DateTimeUtil.today()}"
        var stored = 0
        for (entry in entries) {
            try {
                conversationDao.insert(
                    ConversationEntity(
                        sessionId = sessionId,
                        role = "user",
                        content = entry.input,
                        intent = entry.intentType.name,
                        timestamp = entry.timestamp
                    )
                )
                conversationDao.insert(
                    ConversationEntity(
                        sessionId = sessionId,
                        role = "assistant",
                        content = entry.response,
                        intent = entry.intentType.name,
                        timestamp = entry.timestamp + 1
                    )
                )
                stored += 2
            } catch (e: Exception) {
                Timber.w(e, "Failed to consolidate L1 entry to L2")
            }
        }

        // Evict L2 if over capacity
        evictLayer("conversation")
        Timber.d("L1→L2 consolidated $stored entries, L1 size=${workingMemory.size}")
    }

    // ── L2 → L3 Daily Compression ───────────────────────────────────

    /**
     * Compress today's L2 conversations into an L3 daily summary.
     * Call at end of day or during heartbeat.
     */
    suspend fun compressDaily() {
        try {
            val todayStart = DateTimeUtil.startOfDay()
            val todayEnd = DateTimeUtil.endOfDay()
            val todayConversations = conversationDao.getRecent(200).first()
                .filter { it.timestamp in todayStart..todayEnd }

            if (todayConversations.isEmpty()) return

            val userMessages = todayConversations.filter { it.role == "user" }
            val intents = todayConversations.mapNotNull { it.intent }
                .groupingBy { it }.eachCount()
            val topIntent = intents.maxByOrNull { it.value }

            val summary = KnowledgeEntity(
                category = LAYER_DAILY,
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

            // Evict L3 if over capacity
            evictLayer(LAYER_DAILY)
            Timber.d("L3 compressed: ${todayConversations.size} messages → daily summary")
        } catch (e: Exception) {
            Timber.e(e, "L3 daily compression failed")
        }
    }

    // ── L3 → L4 Pattern Extraction ──────────────────────────────────

    /**
     * Promote repeated daily patterns into L4 long-term patterns.
     * Call weekly or when enough daily summaries accumulate.
     */
    suspend fun extractLongTermPatterns() {
        try {
            val dailies = knowledgeDao.getByCategory(LAYER_DAILY).first()
                .sortedByDescending { it.updatedAt }
                .take(7)

            if (dailies.size < 3) return

            // Aggregate intent distributions across days
            val aggregatedIntents = mutableMapOf<String, Int>()
            var totalMessages = 0
            for (daily in dailies) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val data = gson.fromJson(daily.value, Map::class.java) as Map<String, Any>
                    totalMessages += (data["totalMessages"] as? Double)?.toInt() ?: 0
                    @Suppress("UNCHECKED_CAST")
                    val dist = data["intentDistribution"] as? Map<String, Double> ?: emptyMap()
                    dist.forEach { (k, v) -> aggregatedIntents[k] = (aggregatedIntents[k] ?: 0) + v.toInt() }
                } catch (_: Exception) { }
            }

            val pattern = KnowledgeEntity(
                category = LAYER_PATTERNS,
                key = "weekly_${DateTimeUtil.today()}",
                value = gson.toJson(
                    mapOf(
                        "period" to "weekly",
                        "daysAnalyzed" to dailies.size,
                        "totalMessages" to totalMessages,
                        "topIntents" to aggregatedIntents.entries
                            .sortedByDescending { it.value }
                            .take(5)
                            .associate { it.key to it.value }
                    )
                ),
                confidence = 0.9f
            )
            knowledgeDao.insert(pattern)

            // Evict L4 if over capacity
            evictLayer(LAYER_PATTERNS)
            Timber.d("L4 pattern extracted from ${dailies.size} daily summaries")
        } catch (e: Exception) {
            Timber.e(e, "L4 pattern extraction failed")
        }
    }

    // ── Cross-Session Retrieval ─────────────────────────────────────

    /**
     * Search across all sessions for relevant context.
     * Enables cross-session memory persistence.
     */
    suspend fun searchAcrossSessions(query: String, limit: Int = 10): List<String> {
        return try {
            conversationDao.getRecent(200).first()
                .filter { it.content.contains(query, ignoreCase = true) }
                .take(limit)
                .map { "[${it.sessionId}] ${it.role}: ${it.content}" }
        } catch (e: Exception) {
            Timber.w(e, "Cross-session search failed")
            emptyList()
        }
    }

    /**
     * Get a summary of all past sessions for context assembly.
     */
    suspend fun getSessionSummaries(): List<String> {
        return try {
            val sessionIds = conversationDao.getAllSessionIds()
            sessionIds.take(10).map { sessionId ->
                val messages = conversationDao.getSession(sessionId).first()
                val userMsgs = messages.count { it.role == "user" }
                val lastMsg = messages.lastOrNull()?.content?.take(50) ?: ""
                "Session $sessionId: $userMsgs exchanges, last: $lastMsg"
            }
        } catch (e: Exception) {
            Timber.w(e, "Session summary failed")
            emptyList()
        }
    }

    // ── Eviction Policy ─────────────────────────────────────────────

    /**
     * Enforce MAX_ENTRIES_PER_LAYER on a given layer.
     * Evicts least-recently-used / lowest-confidence entries.
     */
    private suspend fun evictLayer(layer: String) {
        try {
            val count = when (layer) {
                "conversation" -> {
                    // For conversations, we evict oldest by timestamp
                    // Approximate: get recent count via query
                    val recent = conversationDao.getRecent(MAX_ENTRIES_PER_LAYER + 1).first()
                    recent.size
                }
                else -> knowledgeDao.getCategoryCount(layer)
            }

            if (count <= MAX_ENTRIES_PER_LAYER) return

            val excess = count - MAX_ENTRIES_PER_LAYER
            when (layer) {
                "conversation" -> {
                    // Delete oldest conversations beyond limit
                    val sessions = conversationDao.getAllSessionIds()
                    if (sessions.size > 1) {
                        // Keep only the most recent sessions that fit
                        val cutoff = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
                        conversationDao.deleteOlderThan(cutoff)
                    }
                }
                else -> {
                    knowledgeDao.deleteLeastUsedForCategory(layer, excess)
                }
            }
            Timber.d("Evicted $excess entries from $layer")
        } catch (e: Exception) {
            Timber.w(e, "Eviction failed for layer: $layer")
        }
    }

    // ── Store to Specific Layer ──────────────────────────────────────

    /**
     * Store a key-value pair in a specific memory layer.
     */
    suspend fun storeMemory(key: String, value: String, layer: String) {
        when (layer) {
            LAYER_WORKING -> {
                workingMemory.addLast(WorkingMemoryEntry(
                    input = key,
                    response = value,
                    intentType = IntentType.UNKNOWN,
                    timestamp = System.currentTimeMillis()
                ))
                while (workingMemory.size > MAX_ENTRIES_PER_LAYER) {
                    workingMemory.pollFirst()
                }
            }
            LAYER_CONVERSATION -> {
                conversationDao.insert(
                    ConversationEntity(
                        sessionId = "tool_stored",
                        role = "system",
                        content = "$key: $value",
                        timestamp = System.currentTimeMillis()
                    )
                )
                evictLayer(LAYER_CONVERSATION)
            }
            LAYER_DAILY, LAYER_PATTERNS -> {
                knowledgeDao.insert(
                    KnowledgeEntity(
                        category = layer,
                        key = key,
                        value = value,
                        confidence = 1.0f
                    )
                )
                evictLayer(layer)
            }
            else -> {
                // Default to long-term patterns
                knowledgeDao.insert(
                    KnowledgeEntity(
                        category = LAYER_PATTERNS,
                        key = key,
                        value = value,
                        confidence = 0.8f
                    )
                )
                evictLayer(LAYER_PATTERNS)
            }
        }
    }

    // ── Retrieve Across Layers ───────────────────────────────────────

    /**
     * Retrieve relevant context across all layers for a query.
     * Used by the Tool interface.
     */
    suspend fun retrieve(query: String, maxTokens: Int = 1500): String {
        val context = mutableListOf<String>()

        // L1: Working memory
        workingMemory.toList().takeLast(3).forEach {
            context.add("L1: ${it.input} → ${it.response}")
        }

        // L2: Conversation matches
        try {
            conversationDao.getRecent(50).first()
                .filter { it.content.contains(query, ignoreCase = true) }
                .take(3)
                .forEach { context.add("L2: ${it.role}: ${it.content}") }
        } catch (_: Exception) {}

        // L3: Daily patterns
        try {
            knowledgeDao.getByCategory(LAYER_DAILY).first()
                .takeLast(1)
                .forEach { context.add("L3: ${it.value}") }
        } catch (_: Exception) {}

        // L4: Patterns matching query
        try {
            knowledgeDao.getByCategory(LAYER_PATTERNS).first()
                .filter { it.value.contains(query, ignoreCase = true) || it.key.contains(query, ignoreCase = true) }
                .take(2)
                .forEach { context.add("L4: ${it.key}: ${it.value}") }
        } catch (_: Exception) {}

        return context.joinToString("\n").take(maxTokens)
    }

    // ── Layer Stats ──────────────────────────────────────────────────

    suspend fun getLayerStats(): Map<String, Int> {
        val conversationCount = try {
            conversationDao.getRecent(MAX_ENTRIES_PER_LAYER + 1).first().size
        } catch (_: Exception) { 0 }

        val dailyCount = try {
            knowledgeDao.getCategoryCount(LAYER_DAILY)
        } catch (_: Exception) { 0 }

        val patternCount = try {
            knowledgeDao.getCategoryCount(LAYER_PATTERNS)
        } catch (_: Exception) { 0 }

        val vocabCount = try {
            knowledgeDao.getCategoryCount("vocab")
        } catch (_: Exception) { 0 }

        return mapOf(
            "working" to workingMemory.size,
            "conversation" to conversationCount,
            "daily" to dailyCount,
            "patterns" to patternCount,
            "vocab" to vocabCount
        )
    }

    // ── Pruning ──────────────────────────────────────────────────────

    /**
     * Prune old entries across all layers.
     */
    suspend fun pruneOld(maxAgeDays: Int = 30) {
        val cutoff = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000L)
        conversationDao.deleteOlderThan(cutoff)
        Timber.d("Pruned conversations older than $maxAgeDays days")
    }

    // ── Private Helpers ──────────────────────────────────────────────

    private suspend fun storeLearnedVocabulary(input: String) {
        val words = input.lowercase().split(Regex("\\s+"))
        val knownWords = try {
            knowledgeDao.getByCategory("vocab").first().map { it.key.lowercase() }.toSet()
        } catch (_: Exception) { emptySet() }

        for (word in words) {
            if (word.length > 3 && word !in knownWords && !word.matches(Regex(".*\\d.*"))) {
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
        // Evict vocab layer
        evictLayer("vocab")
    }

    private suspend fun getRelevantKnowledge(query: String, intentType: IntentType): List<String> {
        val category = when (intentType) {
            IntentType.RECORD_SALE, IntentType.ASK_SALES_TODAY -> LAYER_PATTERNS
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

    // ── Layer Name Constants ─────────────────────────────────────────
    // (public for external callers)
}

/** Layer name constants for the 4-layer memory hierarchy. */
const val LAYER_WORKING = "working"
const val LAYER_CONVERSATION = "conversation"
const val LAYER_DAILY = "daily_pattern"
const val LAYER_PATTERNS = "business_pattern"

// ── Data Classes ────────────────────────────────────────────────────

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
