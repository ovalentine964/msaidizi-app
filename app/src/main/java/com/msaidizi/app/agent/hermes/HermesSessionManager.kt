package com.msaidizi.app.agent.hermes

import com.msaidizi.app.agent.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Hermes Session Manager — Worker-keyed session management with
 * cross-session memory and skill adaptation.
 *
 * Implements the Hermes agent protocol pattern from Nous Research:
 * - Sessions keyed by WORKER (not channel)
 * - Cross-session memory consolidation
 * - Skill adaptation based on user patterns
 * - Closed learning loop: interaction → trace → feedback → improvement
 *
 * ## Architecture
 *
 *   ┌────────────────────┐     ┌──────────────────────┐
 *   │ ConversationManager│────▶│  HermesSessionManager │
 *   │   (intent routing) │     │  (worker-keyed state) │
 *   └────────────────────┘     └──────────────────────┘
 *            │                          │
 *            │                          ▼
 *            │                  ┌───────────────┐
 *            │                  │  SkillStore   │
 *            │                  │  (learned     │
 *            │                  │   procedures) │
 *            │                  └───────────────┘
 *            │                          │
 *            ▼                          ▼
 *   ┌────────────────────┐     ┌───────────────┐
 *   │ AdaptiveLearning   │     │  MemoryLayer  │
 *   │   Engine           │     │  (L1/L2/L3)  │
 *   └────────────────────┘     └───────────────┘
 *
 * ## Integration with ConversationManager
 *
 * The HermesSessionManager sits alongside ConversationManager:
 * - ConversationManager handles intent classification and response generation
 * - HermesSessionManager handles worker identity, session continuity, and skill learning
 * - They share data through the HermesSessionState
 *
 * @see ConversationManager for intent routing
 * @see AdaptiveLearningEngine for on-device learning
 */
class HermesSessionManager(
    private val eventBus: AgentEventBus = AgentEventBus.getInstance(),
    private val adaptiveLearning: AdaptiveLearningEngine? = null,
    private val conversationMemory: ConversationMemory = ConversationMemory()
) {
    // ═══════════════ STATE ═══════════════

    /** Active Hermes sessions keyed by worker_id */
    private val sessions = ConcurrentHashMap<String, HermesSessionState>()

    /** Worker profiles aggregated across sessions */
    private val profiles = ConcurrentHashMap<String, WorkerProfile>()

    /** Learned skills (in-memory store, persisted via SharedPreferences) */
    private val skillStore = ConcurrentHashMap<String, LearnedSkill>()

    /** Background scope for consolidation tasks */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** JSON serializer */
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    companion object {
        private const val TAG = "HermesSession"

        /** Maximum interactions before memory consolidation */
        private const val CONSOLIDATION_THRESHOLD = 30

        /** Maximum context window size */
        private const val MAX_CONTEXT_WINDOW = 20

        /** Minimum steps to consider an interaction "complex" */
        private const val MIN_COMPLEXITY_FOR_SKILL = 3

        /** Skill confidence decay rate per unused day */
        private const val SKILL_CONFIDENCE_DECAY = 0.01
    }

    // ═══════════════ SESSION MANAGEMENT ═══════════════

    /**
     * Get or create a Hermes session for a worker.
     *
     * Sessions are keyed by worker_id, NOT by channel.
     * A worker switching from app to WhatsApp keeps full context.
     *
     * @param workerId The worker's unique identifier
     * @param channel The current channel (app, whatsapp, ussd)
     * @return The Hermes session state with full context
     */
    fun getOrCreateSession(
        workerId: String,
        channel: String = "app"
    ): HermesSessionState {
        val existing = sessions[workerId]
        if (existing != null) {
            existing.lastChannel = channel
            existing.lastActive = System.currentTimeMillis()

            eventBus.publish(
                AgentEvent.AgentTaskCompleted(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    source = TAG,
                    taskType = "session_resumed",
                    agentName = TAG,
                    durationMs = 0,
                    resultSummary = "Resumed session for $workerId on $channel"
                )
            )

            Timber.d("Resumed Hermes session for worker %s on %s", workerId, channel)
            return existing
        }

        // Create new session
        val session = HermesSessionState(
            sessionId = UUID.randomUUID().toString(),
            workerId = workerId,
            createdAt = System.currentTimeMillis(),
            lastActive = System.currentTimeMillis(),
            lastChannel = channel
        )
        sessions[workerId] = session

        // Load or create worker profile
        loadWorkerProfile(workerId)

        eventBus.publish(
            AgentEvent.AgentTaskStarted(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                source = TAG,
                taskType = "session_created",
                agentName = TAG
            )
        )

        Timber.i("Created Hermes session for worker %s", workerId)
        return session
    }

    /**
     * Get the current session for a worker, if any.
     */
    fun getSession(workerId: String): HermesSessionState? = sessions[workerId]

    /**
     * Get all active sessions.
     */
    fun getActiveSessions(): Map<String, HermesSessionState> = sessions.toMap()

    // ═══════════════ SKILL DISCOVERY ═══════════════

    /**
     * Discover relevant skills for a worker's query.
     *
     * Searches the learned skill store for matching procedures,
     * scored by worker affinity and skill confidence.
     *
     * @param workerId The worker's identifier
     * @param query The worker's current query/intent
     * @param limit Maximum skills to return
     * @return List of relevant skills sorted by relevance
     */
    fun discoverSkills(
        workerId: String,
        query: String,
        limit: Int = 3
    ): List<LearnedSkill> {
        val queryLower = query.lowercase()
        val profile = profiles[workerId]

        val scored = skillStore.values.mapNotNull { skill ->
            // Keyword match score
            val keywordScore = skill.keywords.count { it in queryLower }.toDouble() /
                    (skill.keywords.size.coerceAtLeast(1))

            // Worker affinity bonus
            val affinityBonus = profile?.skillAffinities?.get(skill.category) ?: 0.5

            // Confidence factor
            val combinedScore = (keywordScore * 0.6) + (affinityBonus * 0.3) + (skill.confidence * 0.1)

            if (combinedScore > 0.1) Pair(combinedScore, skill) else null
        }

        val result = scored
            .sortedByDescending { it.first }
            .take(limit)
            .map { it.second }

        // Update session state
        sessions[workerId]?.let { state ->
            state.lastSkillQuery = query
            state.activeSkillIds = result.map { it.skillId }
        }

        if (result.isNotEmpty()) {
            Timber.d("Discovered %d skills for worker %s query: %s",
                result.size, workerId, query.take(50))
        }

        return result
    }

    // ═══════════════ INTERACTION TRACING ═══════════════

    /**
     * Start tracing an interaction for the closed learning loop.
     *
     * @param workerId The worker's identifier
     * @param query The worker's query
     * @return The trace ID for later steps
     */
    fun startTrace(workerId: String, query: String): String {
        val traceId = UUID.randomUUID().toString()
        val session = sessions[workerId]

        session?.let {
            it.activeTraceId = traceId
            it.contextWindow.add(
                ContextEntry(
                    role = "worker",
                    content = query,
                    timestamp = System.currentTimeMillis()
                )
            )

            // Trim context window
            if (it.contextWindow.size > MAX_CONTEXT_WINDOW) {
                it.contextWindow = it.contextWindow.takeLast(MAX_CONTEXT_WINDOW).toMutableList()
            }
        }

        Timber.d("Started trace %s for worker %s", traceId, workerId)
        return traceId
    }

    /**
     * Record a step in an active trace.
     */
    fun recordTraceStep(
        workerId: String,
        traceId: String,
        action: String,
        toolUsed: String? = null,
        success: Boolean = true,
        error: String? = null
    ) {
        val session = sessions[workerId] ?: return
        if (session.activeTraceId != traceId) return

        session.traceSteps.add(
            TraceStep(
                action = action,
                toolUsed = toolUsed,
                success = success,
                error = error
            )
        )
    }

    /**
     * Complete an interaction and potentially generate a skill.
     *
     * This is the core of the closed learning loop:
     * 1. End the trace
     * 2. Check if complex + successful → generate skill
     * 3. Update worker profile
     * 4. Check if memory consolidation needed
     *
     * @return A LearnedSkill if generated, null otherwise
     */
    fun completeInteraction(
        workerId: String,
        traceId: String,
        response: String,
        outcome: String = "success"
    ): LearnedSkill? {
        val session = sessions[workerId] ?: return null
        if (session.activeTraceId != traceId) return null

        // Add response to context window
        session.contextWindow.add(
            ContextEntry(
                role = "msaidizi",
                content = response,
                timestamp = System.currentTimeMillis(),
                outcome = outcome
            )
        )

        val steps = session.traceSteps.toList()
        session.traceSteps.clear()
        session.activeTraceId = null

        // Check if we should generate a skill
        var generatedSkill: LearnedSkill? = null

        if (steps.size >= MIN_COMPLEXITY_FOR_SKILL && outcome == "success") {
            generatedSkill = generateSkill(workerId, steps, response)
            skillStore[generatedSkill.skillId] = generatedSkill

            // Update skill affinity
            val profile = profiles[workerId]
            profile?.let {
                val current = it.skillAffinities[generatedSkill.category] ?: 0.5
                it.skillAffinities[generatedSkill.category] = (current + 0.1).coerceAtMost(1.0)
            }

            Timber.i("Generated skill: %s (%s) for worker %s",
                generatedSkill.title, generatedSkill.category, workerId)
        }

        // Update profile
        updateWorkerProfile(workerId, outcome)

        // Check consolidation
        if (session.contextWindow.size >= CONSOLIDATION_THRESHOLD) {
            scope.launch { consolidateMemory(workerId) }
        }

        return generatedSkill
    }

    // ═══════════════ FEEDBACK LOOP ═══════════════

    /**
     * Record feedback on a skill application.
     *
     * Closes the feedback loop:
     * Skill applied → user feedback → confidence update → improvement
     */
    fun recordFeedback(
        workerId: String,
        skillId: String,
        success: Boolean,
        feedbackText: String? = null
    ) {
        val skill = skillStore[skillId] ?: return

        skill.usageCount++
        if (success) skill.successCount++
        skill.lastUsedAt = System.currentTimeMillis()

        // Update confidence based on success rate
        if (skill.usageCount >= 3) {
            skill.confidence = 0.5 + (skill.successRate * 0.5)
        }

        // Update worker affinity
        val profile = profiles[workerId]
        profile?.let {
            val current = it.skillAffinities[skill.category] ?: 0.5
            if (success) {
                it.skillAffinities[skill.category] = (current + 0.05).coerceAtMost(1.0)
            }
        }

        eventBus.publish(
            AgentEvent.PatternLearned(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                source = TAG,
                patternType = if (success) "SKILL_SUCCESS" else "SKILL_FAILURE",
                pattern = "${skill.title}: ${if (success) "helpful" else "not helpful"}",
                confidence = skill.confidence
            )
        )

        Timber.d("Feedback for skill %s: %s (new confidence: %.2f)",
            skillId, if (success) "positive" else "negative", skill.confidence)
    }

    // ═══════════════ MEMORY CONSOLIDATION ═══════════════

    /**
     * Consolidate short-term memory into long-term.
     *
     * Takes recent interactions, extracts patterns, and stores
     * them in the worker profile. This is the "sleep" cycle
     * of the Hermes pattern.
     *
     * Runs asynchronously to avoid blocking the main thread.
     */
    suspend fun consolidateMemory(workerId: String) = withContext(Dispatchers.IO) {
        val session = sessions[workerId] ?: return@withContext
        val context = session.contextWindow.toList()

        if (context.size < 3) return@withContext

        Timber.d("Consolidating memory for worker %s (%d interactions)",
            workerId, context.size)

        // Extract patterns
        val patterns = extractPatterns(context)
        val topics = extractTopics(context)

        // Update profile with consolidated knowledge
        val profile = profiles[workerId]
        profile?.let {
            it.frequentTopics = topics
            it.consolidationCount++
            it.lastConsolidation = System.currentTimeMillis()
        }

        // Trim context window (keep last 10)
        session.contextWindow = session.contextWindow.takeLast(10).toMutableList()

        eventBus.publish(
            AgentEvent.EvolutionCycleCompleted(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                source = TAG,
                correctionsApplied = 0,
                patternsLearned = patterns.size,
                metricsUpdated = true
            )
        )

        Timber.i("Memory consolidated for worker %s: %d patterns, %d topics",
            workerId, patterns.size, topics.size)
    }

    // ═══════════════ PROFILE ACCESS ═══════════════

    /**
     * Get the worker profile.
     */
    fun getWorkerProfile(workerId: String): WorkerProfile? = profiles[workerId]

    /**
     * Get all learned skills.
     */
    fun getAllSkills(): List<LearnedSkill> = skillStore.values.toList()

    /**
     * Get skills by category.
     */
    fun getSkillsByCategory(category: String): List<LearnedSkill> =
        skillStore.values.filter { it.category == category }

    // ═══════════════ DIAGNOSTICS ═══════════════

    /**
     * Get Hermes session manager statistics.
     */
    fun getStats(): HermesStats = HermesStats(
        activeSessions = sessions.size,
        workerProfiles = profiles.size,
        totalSkills = skillStore.size,
        skillsByCategory = skillStore.values.groupBy { it.category }
            .mapValues { it.value.size },
        totalInteractions = profiles.values.sumOf { it.totalInteractions }
    )

    /**
     * Shutdown the session manager.
     */
    fun shutdown() {
        scope.cancel()
        sessions.clear()
        Timber.i("HermesSessionManager shutdown")
    }

    // ═══════════════ INTERNAL ═══════════════

    private fun loadWorkerProfile(workerId: String) {
        if (profiles.containsKey(workerId)) return

        profiles[workerId] = WorkerProfile(
            workerId = workerId,
            firstSeen = System.currentTimeMillis(),
            lastActive = System.currentTimeMillis()
        )
        Timber.d("Created worker profile for %s", workerId)
    }

    private fun updateWorkerProfile(workerId: String, outcome: String) {
        val profile = profiles[workerId] ?: return
        profile.totalInteractions++
        profile.lastActive = System.currentTimeMillis()

        val satisfaction = when (outcome) {
            "success" -> 1.0
            "partial" -> 0.5
            else -> 0.0
        }
        profile.satisfactionTrend.add(satisfaction)
        if (profile.satisfactionTrend.size > 50) {
            profile.satisfactionTrend = profile.satisfactionTrend.takeLast(50).toMutableList()
        }
    }

    private fun generateSkill(
        workerId: String,
        steps: List<TraceStep>,
        response: String
    ): LearnedSkill {
        // Classify category from steps
        val category = classifyCategory(steps)

        // Extract procedure from successful steps
        val procedure = steps.filter { it.success }.map { step ->
            if (step.toolUsed != null) "${step.action} (using ${step.toolUsed})"
            else step.action
        }

        // Extract pitfalls from failed steps
        val pitfalls = steps.filter { !it.success && it.error != null }
            .map { "Watch out: ${it.error}" }
            .take(5)

        // Extract keywords
        val keywords = extractKeywords(steps, response)

        // Calculate initial confidence
        val allSuccess = steps.all { it.success }
        val baseConfidence = 0.5 + (steps.size * 0.05) + (if (allSuccess) 0.15 else 0.0)

        return LearnedSkill(
            skillId = UUID.randomUUID().toString(),
            title = "$category Protocol: ${steps.first().action.take(40)}",
            category = category,
            procedure = procedure,
            pitfalls = pitfalls,
            keywords = keywords,
            confidence = baseConfidence.coerceAtMost(1.0),
            complexity = steps.size,
            sourceWorkerId = workerId,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun classifyCategory(steps: List<TraceStep>): String {
        val content = steps.joinToString(" ") { "${it.action} ${it.toolUsed ?: ""}" }.lowercase()
        return when {
            content.contains("bei") || content.contains("price") || content.contains("cost") -> "pricing"
            content.contains("stock") || content.contains("inventory") || content.contains("hifadhi") -> "inventory"
            content.contains("akiba") || content.contains("savings") || content.contains("deposit") -> "savings"
            content.contains("soko") || content.contains("market") || content.contains("supplier") -> "market"
            content.contains("usafiri") || content.contains("transport") || content.contains("delivery") -> "transport"
            content.contains("rekodi") || content.contains("records") || content.contains("report") -> "records"
            else -> "general"
        }
    }

    private fun extractKeywords(steps: List<TraceStep>, response: String): List<String> {
        val words = mutableSetOf<String>()
        val text = "${steps.joinToString(" ") { it.action }} $response"
        text.lowercase().split(Regex("\\s+")).forEach { word ->
            val clean = word.trim(".,!?;:")
            if (clean.length > 3) words.add(clean)
        }
        return words.sorted().take(10)
    }

    private fun extractPatterns(context: List<ContextEntry>): List<String> {
        val patterns = mutableListOf<String>()

        // Count intent types from context
        val intentCounts = mutableMapOf<String, Int>()
        for (entry in context) {
            val msg = entry.content.lowercase()
            when {
                msg.contains("nimeuza") || msg.contains("sold") -> intentCounts["sale"] = (intentCounts["sale"] ?: 0) + 1
                msg.contains("nimenunua") || msg.contains("bought") -> intentCounts["purchase"] = (intentCounts["purchase"] ?: 0) + 1
                msg.contains("matumizi") || msg.contains("expense") -> intentCounts["expense"] = (intentCounts["expense"] ?: 0) + 1
            }
        }

        for ((intent, count) in intentCounts.entries.sortedByDescending { it.value }) {
            patterns.add("Frequent $intent interactions (${count}x)")
        }

        return patterns.take(10)
    }

    private fun extractTopics(context: List<ContextEntry>): List<String> {
        val wordFreq = mutableMapOf<String, Int>()
        for (entry in context) {
            entry.content.lowercase().split(Regex("\\s+")).forEach { word ->
                val clean = word.trim(".,!?;:")
                if (clean.length > 3) wordFreq[clean] = (wordFreq[clean] ?: 0) + 1
            }
        }
        return wordFreq.entries.sortedByDescending { it.value }.take(10).map { it.key }
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/**
 * Hermes session state — extends basic session with learning context.
 */
@Serializable
data class HermesSessionState(
    val sessionId: String,
    val workerId: String,
    val createdAt: Long,
    var lastActive: Long,
    var lastChannel: String = "app",
    var activeTraceId: String? = null,
    var activeSkillIds: List<String> = emptyList(),
    var lastSkillQuery: String? = null,
    var contextWindow: MutableList<ContextEntry> = mutableListOf(),
    var traceSteps: MutableList<TraceStep> = mutableListOf()
)

/**
 * A single entry in the context window.
 */
@Serializable
data class ContextEntry(
    val role: String,       // "worker" or "msaidizi"
    val content: String,
    val timestamp: Long,
    val outcome: String? = null
)

/**
 * A step in an interaction trace.
 */
@Serializable
data class TraceStep(
    val action: String,
    val toolUsed: String? = null,
    val success: Boolean = true,
    val error: String? = null,
    val durationMs: Long = 0
)

/**
 * Worker profile — aggregated across sessions.
 */
@Serializable
data class WorkerProfile(
    val workerId: String,
    val firstSeen: Long,
    var lastActive: Long,
    var totalInteractions: Int = 0,
    var preferredLanguage: String = "sw",
    var businessDomain: String = "",
    var skillAffinities: MutableMap<String, Double> = mutableMapOf(),
    var frequentTopics: List<String> = emptyList(),
    var satisfactionTrend: MutableList<Double> = mutableListOf(),
    var consolidationCount: Int = 0,
    var lastConsolidation: Long = 0
)

/**
 * A learned skill — reusable procedure extracted from successful interactions.
 */
@Serializable
data class LearnedSkill(
    val skillId: String,
    val title: String,
    val category: String,
    val procedure: List<String>,
    val pitfalls: List<String>,
    val keywords: List<String>,
    var confidence: Double,
    val complexity: Int,
    val sourceWorkerId: String = "",
    val createdAt: Long,
    var usageCount: Int = 0,
    var successCount: Int = 0,
    var lastUsedAt: Long = 0
) {
    val successRate: Double
        get() = if (usageCount > 0) successCount.toDouble() / usageCount else 0.0
}

/**
 * Hermes statistics for diagnostics.
 */
data class HermesStats(
    val activeSessions: Int,
    val workerProfiles: Int,
    val totalSkills: Int,
    val skillsByCategory: Map<String, Int>,
    val totalInteractions: Int
)
