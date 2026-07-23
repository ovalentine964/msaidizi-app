package com.msaidizi.app.memory

import com.msaidizi.app.agent.AgentContext
import com.msaidizi.app.agent.IntentResult
import com.msaidizi.app.agent.tools.ToolResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UnifiedMemoryBridge — The memory integration layer.
 * 
 * Connects L1 (working memory), L2 (episodic memory), L3 (behavioral model)
 * into ONE unified system. Without this, the agent is just a fancy intent router.
 * 
 * L1: Current conversation context (in-memory, session-scoped)
 * L2: Past episodes (SQLite FTS5, sub-10ms retrieval)
 * L3: Behavioral model (learned patterns, preferences, Room DB)
 * 
 * Design: arch_memory.md, arch_chief.md
 */
@Singleton
class UnifiedMemoryBridge @Inject constructor(
    private val l1: WorkingMemory,          // ConversationMemory
    private val l2: EpisodicMemory,         // SQLite FTS5
    private val l3: BehavioralModelManager  // HermesSessionManager + WorkerProfile
) {
    /**
     * Enrich context before intent classification.
     * Queries L2 for relevant episodes, L3 for worker profile and skills.
     * Assembles enriched context for the cognitive loop.
     */
    suspend fun enrichContext(
        input: String,
        intent: IntentResult,
        sessionId: String
    ): AgentContext {
        // L1: Current conversation
        val l1Context = l1.getRecentContext(sessionId)
        
        // L2: Query relevant episodes (sub-10ms FTS5 search)
        val l2Episodes = l2.searchRelevant(input, limit = 3)
            .joinToString("\n") { "${it.input} → ${it.result}" }
        
        // L3: Worker profile and skills
        val l3Profile = l3.getWorkerProfile()
        val skills = l3.getRelevantSkills(intent.type)
        
        return AgentContext(
            l1Context = l1Context,
            l2Episodes = l2Episodes,
            l3Profile = l3Profile.toString(),
            skills = skills.joinToString(", ") { it.name },
            intent = intent
        )
    }
    
    /**
     * Store episode in L2 after each interaction.
     */
    suspend fun storeEpisode(
        input: String,
        intent: IntentResult,
        result: ToolResult,
        sessionId: String
    ) {
        val episode = Episode(
            input = input,
            intent = intent.type,
            result = result.text,
            success = result.success,
            timestamp = System.currentTimeMillis(),
            sessionId = sessionId
        )
        
        l2.store(episode)
        
        // Update L1 conversation context
        l1.addTurn(sessionId, input, result.text)
    }
    
    /**
     * Update L3 behavioral model after each interaction.
     * Extracts patterns from the interaction.
     */
    suspend fun updateBehavioralModel(
        input: String,
        result: ToolResult,
        context: AgentContext
    ) {
        // Extract behavioral signals
        val signal = BehavioralSignal(
            intent = context.intent.type,
            success = result.success,
            timestamp = System.currentTimeMillis(),
            amount = result.data["amount"] as? Double,
            item = result.data["item"] as? String
        )
        
        // Update L3
        l3.updateFromSignal(signal)
        
        // Every 10 interactions: consolidate L2 patterns into L3
        if (l2.getEpisodeCount() % 10 == 0) {
            val patterns = l2.extractBehavioralPatterns()
            l3.consolidatePatterns(patterns)
        }
    }
}

// ── Working Memory (L1) ──────────────────────────────────────

/**
 * L1: Working Memory — Current conversation context.
 * In-memory, session-scoped, FIFO queue of recent turns.
 */
@Singleton
class WorkingMemory @Inject constructor() {
    private val sessions = mutableMapOf<String, MutableList<ConversationTurn>>()
    private val maxTurns = 10
    
    fun getRecentContext(sessionId: String): String {
        val turns = sessions[sessionId] ?: return ""
        return turns.takeLast(maxTurns).joinToString("\n") { 
            "User: ${it.userInput}\nMsaidizi: ${it.agentResponse}" 
        }
    }
    
    fun addTurn(sessionId: String, input: String, response: String) {
        val turns = sessions.getOrPut(sessionId) { mutableListOf() }
        turns.add(ConversationTurn(input, response))
        if (turns.size > maxTurns * 2) {
            turns.removeFirst()
        }
    }
    
    fun clearSession(sessionId: String) {
        sessions.remove(sessionId)
    }
}

data class ConversationTurn(
    val userInput: String,
    val agentResponse: String,
    val timestamp: Long = System.currentTimeMillis()
)

// ── Episodic Memory (L2) ─────────────────────────────────────

/**
 * L2: Episodic Memory — Past events and transactions.
 * SQLite FTS5 with BM25 ranking. Sub-10ms retrieval.
 */
@Singleton
class EpisodicMemory @Inject constructor() {
    // TODO: Implement with Room + FTS5
    private val episodes = mutableListOf<Episode>()
    
    suspend fun searchRelevant(query: String, limit: Int): List<Episode> {
        // TODO: FTS5 full-text search
        return episodes
            .sortedByDescending { it.timestamp }
            .take(limit)
    }
    
    suspend fun store(episode: Episode) {
        episodes.add(episode)
        // TODO: Store in Room FTS5
        // Evict oldest if > 10,000 episodes
        if (episodes.size > 10_000) {
            episodes.removeFirst()
        }
    }
    
    fun getEpisodeCount(): Int = episodes.size
    
    suspend fun extractBehavioralPatterns(): BehavioralPatterns {
        // TODO: Extract patterns from episodes
        return BehavioralPatterns(
            frequentItems = emptyMap(),
            timePatterns = emptyMap(),
            averageAmount = 0.0
        )
    }
}

data class Episode(
    val input: String,
    val intent: String,
    val result: String,
    val success: Boolean,
    val timestamp: Long,
    val sessionId: String
)

// ── Behavioral Model (L3) ────────────────────────────────────

/**
 * L3: Behavioral Model — Learned patterns and preferences.
 * Bayesian behavioral model per worker. Updated from L2 patterns.
 */
@Singleton
class BehavioralModelManager @Inject constructor() {
    private var profile = WorkerProfile()
    
    fun getWorkerProfile(): WorkerProfile = profile
    
    fun getRelevantSkills(intentType: String): List<Skill> {
        return profile.skills.filter { it.supportedIntents.contains(intentType) }
    }
    
    suspend fun updateFromSignal(signal: BehavioralSignal) {
        // Update Bayesian beliefs
        if (signal.amount != null) {
            profile = profile.copy(
                averageTransactionAmount = 
                    (profile.averageTransactionAmount * 0.9) + (signal.amount * 0.1)
            )
        }
        
        if (signal.item != null) {
            val itemCount = profile.frequentItems.getOrDefault(signal.item, 0) + 1
            profile = profile.copy(
                frequentItems = profile.frequentItems + (signal.item to itemCount)
            )
        }
    }
    
    suspend fun consolidatePatterns(patterns: BehavioralPatterns) {
        // Merge L2 patterns into L3 behavioral model
        profile = profile.copy(
            frequentItems = profile.frequentItems + patterns.frequentItems
        )
    }
}

data class WorkerProfile(
    val averageTransactionAmount: Double = 0.0,
    val frequentItems: Map<String, Int> = emptyMap(),
    val skills: List<Skill> = emptyList(),
    val preferredLanguage: String = "sw",
    val businessType: String = "unknown",
    val riskAversion: Double = 0.5,
    val decisionSpeed: Double = 0.5,
    val priceSensitivity: Double = 0.5
)

data class Skill(
    val name: String,
    val supportedIntents: List<String>,
    val confidence: Double
)

data class BehavioralSignal(
    val intent: String,
    val success: Boolean,
    val timestamp: Long,
    val amount: Double? = null,
    val item: String? = null
)

data class BehavioralPatterns(
    val frequentItems: Map<String, Int>,
    val timePatterns: Map<String, Int>,
    val averageAmount: Double
)
