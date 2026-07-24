package com.msaidizi.app.superagent.context

import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * ContextEngine — The shared context system for the Msaidizi superagent.
 *
 * All modules read from and write to the same context. This replaces the
 * fragmented per-agent memory (ContextManager, ConversationManager,
 * ConversationMemory, EpisodicMemory, HermesSessionManager).
 *
 * ## Memory Hierarchy
 *
 * The context engine implements a 4-layer memory hierarchy inspired by
 * human cognitive architecture:
 *
 * ```
 * L1: Working Memory  — current session (last N turns, active topic)
 * L2: Episodic Memory — recent transactions and interactions (last 30 days)
 * L3: Semantic Memory — learned patterns, worker profile, baselines
 * L4: Procedural Memory — crystallized skills (what advice works for whom)
 * ```
 *
 * ## Key Design Principles
 *
 * 1. **Single source of truth** — one context engine, not per-agent copies
 * 2. **Fast reads** — L1 cached in memory, L2/L3 lazy-loaded
 * 3. **Bounded memory** — designed for 2GB Android devices
 * 4. **Thread-safe** — concurrent reads from multiple coroutines
 *
 * @param workerProfileStore Backing store for worker profile data
 * @param transactionDao DAO for transaction history queries
 * @param patternDao DAO for learned pattern queries
 * @param maxWorkingMemoryTurns Maximum turns in L1 working memory
 */
class ContextEngine(
    private val workerProfileStore: WorkerProfileStore,
    private val transactionDao: TransactionDaoBridge? = null,
    private val patternDao: PatternDaoBridge? = null,
    private val maxWorkingMemoryTurns: Int = DEFAULT_WORKING_MEMORY_TURNS
) {
    companion object {
        private const val TAG = "ContextEngine"
        const val DEFAULT_WORKING_MEMORY_TURNS = 10

        /** Time window for conversation context relevance (30 minutes) */
        private const val CONTEXT_WINDOW_MS = 30 * 60 * 1000L
    }

    // ═══════════════════════════════════════════════════════════════
    // L1: WORKING MEMORY — Current Session
    // ═══════════════════════════════════════════════════════════════

    /** Recent conversation turns (in-memory, session-scoped) */
    private val workingMemory = ConcurrentLinkedDeque<ConversationTurn>()

    /** Cached worker profile (refreshed from store) */
    private var cachedProfile: WorkerProfile? = null

    /** Session metadata */
    private var sessionStartTime: Long = System.currentTimeMillis()
    private var sessionTurnCount: Int = 0

    // ═══════════════════════════════════════════════════════════════
    // INTERACTION STORAGE
    // ═══════════════════════════════════════════════════════════════

    /**
     * Store a completed interaction in the context engine.
     *
     * Updates:
     * - L1 working memory (conversation turn)
     * - Session turn count
     * - Cached profile (if profile-relevant data)
     *
     * @param input The worker's input text
     * @param intent The classified intent
     * @param response The agent's response text
     */
    fun storeInteraction(input: String, intent: String, response: String) {
        val turn = ConversationTurn(
            input = input,
            intent = intent,
            response = response,
            timestamp = System.currentTimeMillis()
        )

        // Add to L1 working memory
        workingMemory.addLast(turn)

        // Evict old turns (bounded memory)
        while (workingMemory.size > maxWorkingMemoryTurns) {
            workingMemory.removeFirst()
        }

        sessionTurnCount++

        Timber.d(TAG, "Stored interaction: intent=$intent, turns=${workingMemory.size}")
    }

    /**
     * Clear the working memory (new session).
     */
    fun clearSession() {
        workingMemory.clear()
        sessionStartTime = System.currentTimeMillis()
        sessionTurnCount = 0
        Timber.d(TAG, "Session cleared")
    }

    // ═══════════════════════════════════════════════════════════════
    // CONTEXT RETRIEVAL — Used by ReasoningEngine ORIENT phase
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get the worker's profile (L3 semantic memory).
     * Cached in memory; refreshes from store if stale.
     */
    fun getWorkerProfile(): WorkerProfile {
        return cachedProfile ?: workerProfileStore.loadProfile().also {
            cachedProfile = it
        }
    }

    /**
     * Update a field in the worker's profile.
     *
     * @param field The field name (e.g., "name", "location", "businessType")
     * @param value The new value
     */
    fun updateWorkerProfile(field: String, value: String) {
        val profile = getWorkerProfile()
        val updated = when (field) {
            "name" -> profile.copy(name = value)
            "location" -> profile.copy(location = value)
            "businessType" -> profile.copy(businessType = value)
            "language" -> profile.copy(language = value)
            "dialect" -> profile.copy(dialect = value)
            else -> profile.copy(customFields = profile.customFields + (field to value))
        }
        cachedProfile = updated
        workerProfileStore.saveProfile(updated)
        Timber.d(TAG, "Updated profile: $field = $value")
    }

    /**
     * Finalize the worker profile after onboarding.
     * Marks the profile as complete and ready for full features.
     */
    fun finalizeWorkerProfile() {
        val profile = getWorkerProfile()
        val finalized = profile.copy(isComplete = true)
        cachedProfile = finalized
        workerProfileStore.saveProfile(finalized)
        Timber.d(TAG, "Worker profile finalized")
    }

    /**
     * Get a summary of the worker for LLM context.
     */
    fun getWorkerSummary(): String {
        val profile = getWorkerProfile()
        return buildString {
            append("Worker: ${profile.name.ifEmpty { "Unknown" }}")
            if (profile.businessType.isNotEmpty()) append(", Business: ${profile.businessType}")
            if (profile.location.isNotEmpty()) append(", Location: ${profile.location}")
            append(", Language: ${profile.language}")
            if (profile.streakDays > 0) append(", Streak: ${profile.streakDays} days")
        }
    }

    /**
     * Get a summary of recent transactions for LLM context.
     */
    suspend fun getRecentTransactionSummary(): String {
        return try {
            transactionDao?.getRecentSummary(limit = 5) ?: "No recent transactions."
        } catch (e: Exception) {
            Timber.w(e, "Failed to get recent transactions")
            "Transaction data unavailable."
        }
    }

    /**
     * Get a summary of the current conversation for LLM context.
     */
    fun getConversationSummary(): String {
        return workingMemory.takeLast(5).joinToString("\n") { turn ->
            "User: ${turn.input}\nMsaidizi: ${turn.response}"
        }
    }

    /**
     * Get recent conversation turns (L1 working memory).
     */
    fun getRecentTurns(limit: Int = 5): List<ConversationTurn> {
        return workingMemory.takeLast(limit).toList()
    }

    /**
     * Get the current topic from recent conversation.
     */
    fun getCurrentTopic(): String? {
        return workingMemory.lastOrNull()?.intent
    }

    /**
     * Check if there's a pending correction context.
     */
    fun hasPendingCorrection(): Boolean {
        val lastTurn = workingMemory.lastOrNull() ?: return false
        return lastTurn.intent == "CORRECTION" ||
            lastTurn.input.lowercase().let { it.startsWith("sio ") || it.startsWith("si ") }
    }

    /**
     * Get session metadata.
     */
    fun getSessionInfo(): SessionInfo {
        return SessionInfo(
            startTime = sessionStartTime,
            turnCount = sessionTurnCount,
            durationMs = System.currentTimeMillis() - sessionStartTime
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // L2: EPISODIC MEMORY — Recent Transactions
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get recent transactions for the worker (L2 episodic memory).
     *
     * @param days Number of days to look back
     * @return List of recent transactions, newest first
     */
    suspend fun getRecentTransactions(days: Int = 7): List<TransactionSummary> {
        return try {
            transactionDao?.getRecent(days = days) ?: emptyList()
        } catch (e: Exception) {
            Timber.w(e, "Failed to get recent transactions")
            emptyList()
        }
    }

    /**
     * Find similar past interactions for contextualization.
     *
     * @param intent The intent to match
     * @param item The item to match (optional)
     * @param limit Maximum results
     * @return List of matching past interactions
     */
    suspend fun findSimilarPastInteractions(
        intent: String,
        item: String? = null,
        limit: Int = 10
    ): List<TransactionSummary> {
        return try {
            transactionDao?.findSimilar(intent = intent, item = item, limit = limit) ?: emptyList()
        } catch (e: Exception) {
            Timber.w(e, "Failed to find similar interactions")
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // L3: SEMANTIC MEMORY — Learned Patterns
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get a learned pattern by key (L3 semantic memory).
     *
     * @param patternType The pattern category
     * @param key The pattern key
     * @return The pattern data, or null if not found
     */
    suspend fun getPattern(patternType: String, key: String): String? {
        return try {
            patternDao?.getPattern(patternType, key)
        } catch (e: Exception) {
            Timber.w(e, "Failed to get pattern: $patternType/$key")
            null
        }
    }

    /**
     * Store a learned pattern (L3 semantic memory).
     *
     * @param patternType The pattern category
     * @param key The pattern key
     * @param data The pattern data
     * @param confidence Confidence in this pattern (0.0–1.0)
     */
    suspend fun storePattern(patternType: String, key: String, data: String, confidence: Float) {
        try {
            patternDao?.storePattern(patternType, key, data, confidence)
            Timber.d(TAG, "Stored pattern: $patternType/$key (confidence=$confidence)")
        } catch (e: Exception) {
            Timber.w(e, "Failed to store pattern: $patternType/$key")
        }
    }

    /**
     * Get the item baseline (average amount for a specific item).
     *
     * @param item The item name
     * @return Baseline data, or null if no history
     */
    suspend fun getItemBaseline(item: String?): ItemBaseline? {
        if (item == null) return null
        return try {
            transactionDao?.getItemBaseline(item)
        } catch (e: Exception) {
            Timber.w(e, "Failed to get item baseline: $item")
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CACHE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════

    /**
     * Refresh the cached worker profile from the backing store.
     */
    fun refreshProfile() {
        cachedProfile = null
        getWorkerProfile() // triggers reload
        Timber.d(TAG, "Profile refreshed from store")
    }

    /**
     * Get context engine metrics for debugging.
     */
    fun getMetrics(): ContextMetrics {
        return ContextMetrics(
            workingMemorySize = workingMemory.size,
            sessionTurnCount = sessionTurnCount,
            sessionDurationMs = System.currentTimeMillis() - sessionStartTime,
            profileCached = cachedProfile != null
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════════

/** A single conversation turn in working memory */
data class ConversationTurn(
    val input: String,
    val intent: String,
    val response: String,
    val timestamp: Long
)

/** Worker profile — persistent identity and preferences */
data class WorkerProfile(
    val id: String = "",
    val name: String = "",
    val businessType: String = "",
    val location: String = "",
    val language: String = "sw",
    val dialect: String = "standard",
    val streakDays: Int = 0,
    val alamaTier: String = "BUILDING",
    val isComplete: Boolean = false,
    val customFields: Map<String, String> = emptyMap()
)

/** Transaction summary for context retrieval */
data class TransactionSummary(
    val id: Long = 0,
    val type: String = "",      // SALE, PURCHASE, EXPENSE
    val item: String = "",
    val amount: Double = 0.0,
    val timestamp: Long = 0,
    val confidence: Float = 1.0f
)

/** Item baseline — average amount for a specific product */
data class ItemBaseline(
    val item: String,
    val averageAmount: Double,
    val transactionCount: Int,
    val lastSeen: Long
)

/** Session information */
data class SessionInfo(
    val startTime: Long,
    val turnCount: Int,
    val durationMs: Long
)

/** Context engine metrics */
data class ContextMetrics(
    val workingMemorySize: Int,
    val sessionTurnCount: Int,
    val sessionDurationMs: Long,
    val profileCached: Boolean
)

// ═══════════════════════════════════════════════════════════════════
// BRIDGE INTERFACES — Connect to existing Room DAOs
// ═══════════════════════════════════════════════════════════════════

/**
 * Backing store for worker profile data.
 * Implementations bridge to Room database or SharedPreferences.
 */
interface WorkerProfileStore {
    fun loadProfile(): WorkerProfile
    fun saveProfile(profile: WorkerProfile)
}

/**
 * Bridge to the TransactionDao for transaction history queries.
 * Keeps ContextEngine decoupled from Room directly.
 */
interface TransactionDaoBridge {
    suspend fun getRecentSummary(limit: Int): String
    suspend fun getRecent(days: Int): List<TransactionSummary>
    suspend fun findSimilar(intent: String, item: String?, limit: Int): List<TransactionSummary>
    suspend fun getItemBaseline(item: String): ItemBaseline?
}

/**
 * Bridge to the PatternDao for learned pattern queries.
 */
interface PatternDaoBridge {
    suspend fun getPattern(patternType: String, key: String): String?
    suspend fun storePattern(patternType: String, key: String, data: String, confidence: Float)
}
