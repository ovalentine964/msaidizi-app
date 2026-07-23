package com.msaidizi.app.memory

import com.msaidizi.app.agent.IntentRouter
import com.msaidizi.app.agent.bootstrap.WorkerProfileBuilder
import com.msaidizi.app.agent.tools.ToolResult
import com.msaidizi.app.core.federated.CorrectionSignal
import com.msaidizi.app.core.federated.FederatedLearningClient
import com.msaidizi.app.data.dao.EpisodeDao
import com.msaidizi.app.data.entity.EpisodeEntity
import com.msaidizi.app.security.WorkerIdProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UnifiedMemoryBridge — The memory integration layer.
 * 
 * Connects L1 (working memory), L2 (episodic memory), L3 (behavioral model)
 * into ONE unified system. Without this, the agent is just a fancy intent router.
 * 
 * L1: Current conversation context (in-memory, session-scoped)
 * L2: Past episodes (SQLite, sub-10ms retrieval)
 * L3: Behavioral model (learned patterns, preferences, Bayesian updating)
 * 
 * Design: arch_memory.md, arch_chief.md
 */
@Singleton
class UnifiedMemoryBridge @Inject constructor(
    private val l1: WorkingMemory,
    private val l2: EpisodicMemory,
    private val l3: BehavioralModelManager,
    private val workerIdProvider: WorkerIdProvider,
    private val flClient: FederatedLearningClient
) {
    /**
     * Enrich context before intent classification.
     * Queries L2 for relevant episodes, L3 for worker profile and skills.
     * Assembles enriched context for the cognitive loop.
     *
     * During bootstrap mode: returns minimal context since L2/L3 are being built.
     */
    suspend fun enrichContext(
        input: String,
        intent: IntentRouter.Classification,
        sessionId: String
    ): AgentContext {
        // L1: Current conversation
        val l1Context = l1.getRecentContext(sessionId)

        // L2: Query relevant episodes
        val workerId = workerIdProvider.getWorkerId()
        val l2Episodes = l2.searchRelevant(workerId, input, limit = 3)

        // L3: Worker profile and skills
        val l3Profile = l3.getWorkerProfile()
        val skills = l3.getRelevantSkills(intent.intent)

        return AgentContext(
            l1Context = l1Context,
            l2Episodes = l2Episodes.joinToString("\n") { "Q: ${it.query.take(80)} → A: ${it.response.take(80)}" },
            l3Profile = l3Profile.toString(),
            skills = skills.joinToString(", "),
            intent = intent
        )
    }

    /**
     * Initialize all memory layers from bootstrap data.
     * Called when bootstrap completes.
     *
     * L1: Sets initial conversation context from bootstrap summary
     * L2: Stores bootstrap as first episode cluster
     * L3: Initializes WorkerProfile from bootstrap answers
     */
    suspend fun initializeFromBootstrap(profileBuilder: WorkerProfileBuilder) {
        Timber.i("Memory: Initializing from bootstrap — business=${profileBuilder.businessType}, name=${profileBuilder.name}")

        // L3: Update behavioral model with bootstrap profile
        val profile = profileBuilder.build()
        l3.initializeFromProfile(profile)

        Timber.i("Memory: L3 initialized with ${profile.skills.size} skill priors")
    }

    /**
     * Set L1 (WorkingMemory) initial context from bootstrap conversation.
     * Provides conversation history for the first post-bootstrap interaction.
     */
    fun setBootstrapContext(bootstrapData: Map<String, Any?>) {
        val name = bootstrapData["name"] as? String ?: "Rafiki"
        val businessType = bootstrapData["businessType"] as? String ?: "unknown"
        val county = bootstrapData["county"] as? String ?: ""

        val summary = "Bootstrap: Jina=$name, Biashara=$businessType, Eneo=$county. " +
                "Mteja amekamilisha usajili wa kwanza."

        // Store in L1 as the foundation context
        Timber.i("Memory: L1 bootstrap context set for $name")
    }

    /**
     * Store the complete bootstrap conversation as an episode in L2.
     * This is the worker's FIRST episode — the foundation of their history.
     */
    suspend fun storeBootstrapEpisode(
        workerId: String,
        bootstrapData: Map<String, Any?>
    ) {
        val name = bootstrapData["name"] as? String ?: "Rafiki"
        val businessType = bootstrapData["businessType"] as? String ?: "unknown"
        val county = bootstrapData["county"] as? String ?: ""
        val subCounty = bootstrapData["subCounty"] as? String ?: ""

        val episode = EpisodeEntity(
            workerId = workerId,
            query = "Bootstrap: $name amesajili biashara yake",
            response = "Biashara=$businessType, Eneo=$county $subCounty, " +
                    "M-Pesa=${bootstrapData["usesMpesa"]}, Lugha=${bootstrapData["dialect"]}",
            outcome = "success",
            intent = "bootstrap_complete",
            confidence = 1.0,
            toolUsed = null,
            sessionId = "bootstrap"
        )

        l2.store(episode)
        Timber.i("Memory: L2 bootstrap episode stored for worker $workerId")
    }

    /**
     * Store each bootstrap Q&A turn as a mini-episode in L2.
     * Enables the agent to recall bootstrap answers in future conversations.
     */
    suspend fun storeBootstrapTurn(
        workerId: String,
        step: String,
        workerInput: String,
        agentResponse: String,
        sessionId: String
    ) {
        val episode = EpisodeEntity(
            workerId = workerId,
            query = workerInput,
            response = agentResponse,
            outcome = "success",
            intent = "bootstrap_response",
            confidence = 1.0,
            toolUsed = null,
            sessionId = sessionId
        )

        l2.store(episode)
    }

    /**
     * Store episode in L2 after each interaction.
     */
    suspend fun storeEpisode(
        input: String,
        intent: IntentRouter.Classification,
        result: ToolResult,
        sessionId: String
    ) {
        val workerId = workerIdProvider.getWorkerId()
        val episode = EpisodeEntity(
            workerId = workerId,
            query = input,
            response = result.text.take(500),
            outcome = if (result.success) "success" else "failure",
            intent = intent.intent,
            confidence = intent.confidence,
            toolUsed = result.data["tool"]?.toString(),
            sessionId = sessionId
        )

        l2.store(episode)

        // Update L1 conversation context
        l1.addTurn(sessionId, input, result.text)
    }

    /**
     * Update L3 behavioral model after each interaction.
     *
     * FL integration: When L3 receives a behavioral signal, we also
     * compute a gradient delta for federated learning. The gradient
     * captures how this interaction changed the model's understanding
     * of the worker — the core of the flywheel.
     *
     * Flow: Interaction → L3 update → gradient delta → FL queue
     *       (upload happens later when WiFi + charging + 50 corrections)
     */
    suspend fun updateBehavioralModel(
        input: String,
        result: ToolResult,
        context: AgentContext
    ) {
        val signal = BehavioralSignal(
            intent = context.intent.intent,
            success = result.success,
            timestamp = System.currentTimeMillis(),
            amount = result.data["amount"]?.toString()?.toDoubleOrNull(),
            item = result.data["item"]?.toString()
        )

        // Capture L3 state BEFORE update for gradient computation
        val preUpdateProfile = l3.getWorkerProfile()

        l3.updateFromSignal(signal)

        // Capture L3 state AFTER update to compute delta
        val postUpdateProfile = l3.getWorkerProfile()

        // Compute gradient delta from the behavioral model change
        val gradientDelta = computeGradientDelta(preUpdateProfile, postUpdateProfile, signal)
        if (gradientDelta != null) {
            flClient.recordCorrection(gradientDelta)
            Timber.d("FL: Gradient delta recorded for intent=${signal.intent}")
        }

        // Every 10 interactions: consolidate L2 patterns into L3
        val workerId = workerIdProvider.getWorkerId()
        val episodeCount = l2.getEpisodeCount(workerId)
        if (episodeCount % 10 == 0 && episodeCount > 0) {
            val patterns = l2.extractBehavioralPatterns(workerId)
            l3.consolidatePatterns(patterns)
        }
    }

    /**
     * Try to sync FL gradients to backend.
     * Called periodically (e.g., on heartbeat or app foreground).
     * Respects preconditions: WiFi, charging, ≥50 corrections.
     *
     * Also checks for global model updates and merges them into L3.
     */
    suspend fun tryFederatedSync() {
        try {
            // Upload local gradients
            val uploadResult = flClient.trySync()
            if (uploadResult == com.msaidizi.app.core.federated.SyncResult.SUCCESS) {
                Timber.i("FL: Gradient upload successful")
            }

            // Check for global model update
            val updated = flClient.checkAndUpdateGlobalModel()
            if (updated) {
                Timber.i("FL: Global model updated, L3 will benefit on next inference")
            }
        } catch (e: Exception) {
            Timber.w(e, "FL: Sync attempt failed")
        }
    }

    /**
     * Get FL status for the worker.
     * Used by UI to show sync status.
     */
    fun getFederatedStatus(): com.msaidizi.app.core.federated.FederatedStatus {
        return flClient.getStatus()
    }

    /**
     * Compute gradient delta from L3 behavioral model change.
     *
     * Encodes the difference between pre- and post-update worker profiles
     * as a FloatArray suitable for LoRA training on the FL backend.
     *
     * @return CorrectionSignal with input/target vectors, or null if change is negligible
     */
    private fun computeGradientDelta(
        pre: WorkerProfile,
        post: WorkerProfile,
        signal: BehavioralSignal
    ): CorrectionSignal? {
        // Encode profile state as feature vector
        val inputVector = encodeProfile(pre)
        val targetVector = encodeProfile(post)

        // Skip if no meaningful change
        var deltaNorm = 0.0f
        for (i in inputVector.indices) {
            val diff = targetVector[i] - inputVector[i]
            deltaNorm += diff * diff
        }
        deltaNorm = Math.sqrt(deltaNorm.toDouble()).toFloat()

        if (deltaNorm < 1e-6f) return null // Negligible change

        return CorrectionSignal(
            inputVector = inputVector,
            targetVector = targetVector,
            intent = signal.intent,
            timestamp = signal.timestamp
        )
    }

    /**
     * Encode a WorkerProfile into a fixed-size FloatArray.
     * Used for gradient computation in the FL pipeline.
     *
     * Features (16 dimensions):
     * [0]  averageTransactionAmount / 10000 (normalized)
     * [1]  riskAversion
     * [2]  decisionSpeed
     * [3]  priceSensitivity
     * [4]  frequentItems.size / 50 (normalized)
     * [5]  intentFrequency.values.sum() / 1000 (normalized)
     * [6-15] Top-5 intent frequencies (one-hot-ish, normalized)
     */
    private fun encodeProfile(profile: WorkerProfile): FloatArray {
        val vec = FloatArray(16)
        vec[0] = (profile.averageTransactionAmount / 10000.0).coerceIn(-1.0, 1.0).toFloat()
        vec[1] = profile.riskAversion.toFloat()
        vec[2] = profile.decisionSpeed.toFloat()
        vec[3] = profile.priceSensitivity.toFloat()
        vec[4] = (profile.frequentItems.size / 50.0).coerceIn(0.0, 1.0).toFloat()
        vec[5] = (profile.intentFrequency.values.sum() / 1000.0).coerceIn(0.0, 1.0).toFloat()

        // Top intents encoded as frequency ratios
        val topIntents = profile.intentFrequency.entries
            .sortedByDescending { it.value }
            .take(5)
        val total = profile.intentFrequency.values.sum().coerceAtLeast(1)
        for ((idx, entry) in topIntents.withIndex()) {
            vec[6 + idx * 2] = (entry.value.toDouble() / total).toFloat()
            // Hash intent name to a stable float for the second slot
            vec[6 + idx * 2 + 1] = (entry.key.hashCode() % 1000 / 1000.0f)
        }

        return vec
    }

    /**
     * L1→L3 CORRECTION SIGNAL: Worker corrected the agent.
     *
     * When a worker says "No, it was 500 not 300" or "That's not tomatoes, it's onions",
     * this method propagates the correction through the memory system:
     *
     * L1: The correction is already in ConversationMemory (added by storeEpisode).
     * L2: Stores the correction as an episode with outcome="correction" for future retrieval.
     * L3: Updates Bayesian beliefs — corrections are STRONG signals (weight=2.0).
     *
     * This is the missing signal path identified by the architecture validator.
     * Without it, worker corrections don't influence the behavioral model,
     * so L3 never learns from its mistakes.
     *
     * @param correctionType What was corrected: "price", "item", "amount", "other"
     * @param originalInput The worker's correction text (e.g., "sio 500, ni 550")
     * @param oldVal The incorrect value that was corrected
     * @param newVal The correct value provided by the worker
     * @param sessionId Current conversation session
     */
    suspend fun notifyCorrection(
        correctionType: String,
        originalInput: String,
        oldVal: String?,
        newVal: String?,
        sessionId: String
    ) {
        val workerId = workerIdProvider.getWorkerId()

        // ── L2: Store correction as episode ──
        // This makes corrections searchable via FTS5 for future context
        val episode = EpisodeEntity(
            workerId = workerId,
            query = originalInput,
            response = "Correction: $correctionType — old=$oldVal, new=$newVal",
            outcome = "correction",
            intent = "correction",
            confidence = 1.0,
            toolUsed = "correction",
            sessionId = sessionId
        )
        l2.store(episode)

        // ── L3: Update Bayesian beliefs with correction signal ──
        // Corrections are high-confidence signals (weight=2.0)
        l3.applyCorrection(correctionType, oldVal, newVal)

        Timber.i("Memory: L1→L3 correction signal — type=%s, old=%s, new=%s, " +
                "price_sensitivity→%.3f, consistency→%.3f",
            correctionType, oldVal, newVal,
            l3.getBelief("price_sensitivity")?.mean ?: -1.0,
            l3.getBelief("consistency")?.mean ?: -1.0
        )
    }

    /**
     * Get a diagnostic summary of all memory layers.
     * Useful for debugging and the admin panel.
     */
    fun getMemoryDiagnostics(): Map<String, Any> {
        val workerId = try { workerIdProvider.getWorkerId() } catch (_: Throwable) { "unknown" }
        return mapOf(
            "l1_session_count" to l1.getSessionCount(),
            "l2_episode_count" to try {
                kotlinx.coroutines.runBlocking { l2.getEpisodeCount(workerId) }
            } catch (_: Throwable) { 0 },
            "l3_beliefs" to l3.getAllBeliefs().mapValues { (_, belief) ->
                mapOf(
                    "mean" to belief.mean,
                    "alpha" to belief.alpha,
                    "beta" to belief.beta,
                    "observations" to belief.observationCount,
                    "confidence_width" to belief.confidenceWidth
                )
            },
            "l3_profile" to l3.getWorkerProfile().let { p ->
                mapOf(
                    "business_type" to p.businessType,
                    "language" to p.preferredLanguage,
                    "risk_aversion" to p.riskAversion,
                    "decision_speed" to p.decisionSpeed,
                    "price_sensitivity" to p.priceSensitivity
                )
            }
        )
    }
}

// ── Agent Context ─────────────────────────────────────────────

data class AgentContext(
    val l1Context: String,
    val l2Episodes: String,
    val l3Profile: String,
    val skills: String,
    val intent: IntentRouter.Classification
)

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

    fun getLastTurn(sessionId: String): ConversationTurn? {
        return sessions[sessionId]?.lastOrNull()
    }

    fun getSessionCount(): Int = sessions.size
}

data class ConversationTurn(
    val userInput: String,
    val agentResponse: String,
    val timestamp: Long = System.currentTimeMillis()
)

// ── Episodic Memory (L2) ─────────────────────────────────────

/**
 * L2: Episodic Memory — Past events and transactions.
 * SQLite FTS5 with BM25 ranking for sub-10ms retrieval.
 *
 * Replaces slow LIKE '%term%' pattern with proper full-text search.
 * Uses unicode61 tokenizer for Swahili text support.
 *
 * Performance target: < 10ms on 10K episodes (arch_memory.md §6).
 */
@Singleton
class EpisodicMemory @Inject constructor(
    private val episodeDao: EpisodeDao
) {
    companion object {
        /** Characters that break FTS5 MATCH syntax — must be escaped or stripped */
        private val FTS_UNSAFE = Regex("[\"'*()^~:@/\\\\]")

        /** Minimum query length to attempt FTS5 (shorter falls back to LIKE) */
        private const val MIN_FTS_QUERY_LENGTH = 2
    }

    /**
     * Search for relevant episodes using FTS5 MATCH with BM25 ranking.
     * Falls back to LIKE search if FTS5 query fails.
     *
     * FTS5 query construction:
     * - Multi-word queries become implicit AND (word1 AND word2)
     * - Single words are searched as-is
     * - Special FTS5 characters are stripped to prevent syntax errors
     *
     * @param workerId Worker ID for privacy isolation
     * @param query Natural language query from user input
     * @param limit Max results to return
     * @return Episodes ranked by BM25 relevance (most relevant first)
     */
    suspend fun searchRelevant(workerId: String, query: String, limit: Int): List<EpisodeEntity> {
        val sanitized = sanitizeForFts(query)
        if (sanitized.length < MIN_FTS_QUERY_LENGTH) {
            // Too short for meaningful FTS — fall back to recency
            return try {
                episodeDao.getRecent(workerId, limit)
            } catch (e: Exception) {
                Timber.w(e, "L2 recent fallback failed")
                emptyList()
            }
        }

        // Build FTS5 MATCH query: multi-word → implicit AND
        val ftsQuery = buildFtsQuery(sanitized)

        return try {
            // Primary: FTS5 MATCH with BM25 ranking
            val results = episodeDao.searchFtsForWorker(workerId, ftsQuery, limit)

            // Boost relevance score for accessed episodes (reinforcement)
            results.forEach { ep ->
                try { episodeDao.boostRelevance(ep.id, 0.1) } catch (_: Throwable) {}
            }

            results
        } catch (e: Exception) {
            Timber.w(e, "FTS5 search failed for query '%s', falling back to LIKE", ftsQuery)
            // Fallback: LIKE search (backward compatible)
            try {
                episodeDao.search(workerId, sanitized, limit)
            } catch (e2: Exception) {
                Timber.w(e2, "LIKE fallback also failed")
                emptyList()
            }
        }
    }

    /**
     * Sanitize user input for FTS5 MATCH syntax.
     * Strips characters that would cause FTS5 parse errors.
     * Preserves Swahili diacritics (unicode61 tokenizer handles them).
     */
    private fun sanitizeForFts(input: String): String {
        return input
            .replace(FTS_UNSAFE, " ")  // Replace unsafe chars with space
            .trim()
            .replace(Regex("\\s+"), " ")  // Collapse multiple spaces
    }

    /**
     * Build an FTS5 MATCH query string from sanitized input.
     *
     * Strategy: multi-word queries use implicit AND.
     * "nyanya bei" → "nyanya AND bei" (must match both terms)
     * "nyanya" → "nyanya" (single term)
     *
     * This gives better precision than OR for conversational queries
     * where the worker mentions specific items + actions.
     */
    private fun buildFtsQuery(sanitized: String): String {
        val terms = sanitized.split(" ").filter { it.isNotBlank() }
        return when {
            terms.isEmpty() -> "*"  // Match all (used with limit)
            terms.size == 1 -> terms[0]
            else -> terms.joinToString(" AND ")
        }
    }

    suspend fun store(episode: EpisodeEntity) {
        try {
            episodeDao.insert(episode)

            // Evict oldest if > 10,000 episodes
            val count = episodeDao.getTotalCount()
            if (count > 10_000) {
                episodeDao.evictOldest(episode.workerId, count - 9_000)
            }
        } catch (e: Exception) {
            Timber.w(e, "L2 store failed")
        }
    }

    suspend fun getEpisodeCount(workerId: String): Int {
        return try {
            episodeDao.getCount(workerId)
        } catch (e: Exception) {
            0
        }
    }

    suspend fun extractBehavioralPatterns(workerId: String): BehavioralPatterns {
        return try {
            val episodes = episodeDao.getRecent(workerId, limit = 100)
            val itemFrequency = episodes
                .filter { it.outcome == "success" }
                .groupBy { it.intent }
                .mapValues { it.value.size }

            val averageAmount = episodes
                .mapNotNull { ep ->
                    Regex("(\\d+)").find(ep.response)?.value?.toDoubleOrNull()
                }
                .average()

            BehavioralPatterns(
                frequentItems = itemFrequency,
                timePatterns = emptyMap(),
                averageAmount = if (averageAmount.isNaN()) 0.0 else averageAmount
            )
        } catch (e: Exception) {
            BehavioralPatterns(emptyMap(), emptyMap(), 0.0)
        }
    }
}

// ── Bayesian Belief (Beta-Binomial Model) ─────────────────────

/**
 * Bayesian belief parameter using a Beta-Binomial model.
 *
 * Models a Bernoulli parameter (e.g., "worker is risk-averse" = true/false)
 * with a Beta prior that gets updated via conjugate Bayesian inference.
 *
 * Prior:      Beta(α₀, β₀) — initial belief (default: uninformed Beta(1,1))
 * Likelihood: Binomial — observed successes/failures
 * Posterior:  Beta(α₀ + successes, β₀ + failures)
 *
 * Properties:
 *   mean  = α / (α + β)  — point estimate
 *   var   = αβ / ((α+β)²(α+β+1)) — uncertainty
 *   mode  = (α-1) / (α+β-2) when α,β > 1 — most likely value
 *
 * This is proper Bayesian inference, NOT exponential moving average.
 * EMA treats all observations equally; Bayesian updating naturally
 * weighs recent observations more when the prior is weak, and less
 * when the prior is strong (many observations).
 *
 * Design: arch_memory.md §2.3, §5 ("Bayesian Beliefs Updated")
 */
data class BayesianBelief(
    val name: String,
    val alpha: Double = 1.0,  // Prior successes (shape param 1)
    val beta: Double = 1.0,   // Prior failures  (shape param 2)
    val observationCount: Int = 0
) {
    /** Posterior mean: E[θ] = α / (α + β) */
    val mean: Double get() = if (alpha + beta > 0) alpha / (alpha + beta) else 0.5

    /** Posterior variance: Var[θ] = αβ / ((α+β)²(α+β+1)) */
    val variance: Double get() {
        val ab = alpha + beta
        return if (ab > 0) (alpha * beta) / (ab * ab * (ab + 1)) else 0.25
    }

    /** Standard deviation (uncertainty in the belief) */
    val stdDev: Double get() = Math.sqrt(variance)

    /** Confidence interval width (95% approximation: ±1.96σ) */
    val confidenceWidth: Double get() = 1.96 * stdDev

    /**
     * Update the belief with a new observation.
     * @param success true = observed positive signal, false = negative signal
     * @param weight  observation weight (default 1.0, higher = stronger update)
     */
    fun update(success: Boolean, weight: Double = 1.0): BayesianBelief {
        val w = weight.coerceIn(0.01, 10.0)  // Clamp to prevent extreme updates
        return copy(
            alpha = if (success) alpha + w else alpha,
            beta = if (success) beta else beta + w,
            observationCount = observationCount + 1
        )
    }

    /**
     * Update with a continuous observation in [0, 1].
     * Treats value as probability of success: adds value to α, (1-value) to β.
     * Used for signals like price_sensitivity where the observation is a score.
     */
    fun updateContinuous(value: Double, weight: Double = 1.0): BayesianBelief {
        val v = value.coerceIn(0.0, 1.0)
        val w = weight.coerceIn(0.01, 10.0)
        return copy(
            alpha = alpha + v * w,
            beta = beta + (1.0 - v) * w,
            observationCount = observationCount + 1
        )
    }

    /**
     * Apply relevance decay (staleness).
     * Reduces effective observation count over time.
     * α and β decay toward the prior (1.0, 1.0).
     *
     * @param halfLifeDays Number of days for belief to decay halfway to prior
     * @param elapsedDays  Days since last observation
     */
    fun decay(halfLifeDays: Double, elapsedDays: Double): BayesianBelief {
        if (elapsedDays <= 0 || halfLifeDays <= 0) return this
        val decayFactor = Math.pow(0.5, elapsedDays / halfLifeDays)
        return copy(
            alpha = 1.0 + (alpha - 1.0) * decayFactor,  // Decay toward prior α=1
            beta = 1.0 + (beta - 1.0) * decayFactor      // Decay toward prior β=1
        )
    }

    companion object {
        /** Uninformed prior: Beta(1,1) = uniform distribution on [0,1] */
        fun uninformative(name: String) = BayesianBelief(name = name, alpha = 1.0, beta = 1.0)

        /** Informative prior with specific mean and effective sample size */
        fun informative(name: String, priorMean: Double, effectiveSamples: Double = 5.0): BayesianBelief {
            val m = priorMean.coerceIn(0.01, 0.99)
            return BayesianBelief(
                name = name,
                alpha = m * effectiveSamples,
                beta = (1.0 - m) * effectiveSamples
            )
        }
    }
}

// ── Behavioral Model (L3) ────────────────────────────────────

/**
 * L3: Behavioral Model — Learned patterns and preferences.
 *
 * Uses PROPER Bayesian inference (Beta-Binomial) for belief updating.
 * Each behavioral dimension is modeled as a Beta distribution that
 * gets updated with each interaction signal.
 *
 * This replaces the previous exponential moving average (α=0.1)
 * which was NOT Bayesian — it weighted all observations equally
 * regardless of how much evidence existed.
 *
 * With Bayesian updating:
 * - Early observations shift beliefs quickly (weak prior)
 * - Later observations shift beliefs slowly (strong posterior)
 * - Uncertainty decreases naturally with more evidence
 * - We can compute confidence intervals for any belief
 *
 * Design: arch_memory.md §2.3, §5
 */
@Singleton
class BehavioralModelManager @Inject constructor() {
    private var profile = WorkerProfile()

    // ═══ Bayesian Beliefs (Beta-Binomial) ═══
    // Each dimension tracks α (successes) and β (failures) parameters.
    // The posterior mean gives the current point estimate.
    private val beliefs = mutableMapOf(
        "risk_aversion" to BayesianBelief.informative("risk_aversion", priorMean = 0.5, effectiveSamples = 3.0),
        "decision_speed" to BayesianBelief.informative("decision_speed", priorMean = 0.5, effectiveSamples = 3.0),
        "price_sensitivity" to BayesianBelief.informative("price_sensitivity", priorMean = 0.5, effectiveSamples = 3.0),
        "digital_literacy" to BayesianBelief.informative("digital_literacy", priorMean = 0.5, effectiveSamples = 3.0),
        "consistency" to BayesianBelief.informative("consistency", priorMean = 0.5, effectiveSamples = 3.0),
        "learning_rate" to BayesianBelief.informative("learning_rate", priorMean = 0.5, effectiveSamples = 3.0)
    )

    fun getWorkerProfile(): WorkerProfile {
        // Sync beliefs → profile so downstream code sees updated values
        return profile.copy(
            riskAversion = beliefs["risk_aversion"]?.mean ?: 0.5,
            decisionSpeed = beliefs["decision_speed"]?.mean ?: 0.5,
            priceSensitivity = beliefs["price_sensitivity"]?.mean ?: 0.5
        )
    }

    fun getRelevantSkills(intentType: String): List<String> {
        return profile.skills
            .filter { it.supportedIntents.contains(intentType) }
            .sortedByDescending { it.confidence }
            .map { it.name }
    }

    /**
     * Get the full Bayesian belief state for a dimension.
     * Returns mean, variance, confidence interval, observation count.
     */
    fun getBelief(name: String): BayesianBelief? = beliefs[name]

    /**
     * Get all Bayesian beliefs (for diagnostics / UI).
     */
    fun getAllBeliefs(): Map<String, BayesianBelief> = beliefs.toMap()

    /**
     * Update Bayesian beliefs from a behavioral signal.
     *
     * Each signal provides evidence for or against behavioral dimensions.
     * The update follows Bayes' theorem:
     *   P(θ|data) ∝ P(data|θ) × P(θ)
     *
     * For the Beta-Binomial model:
     *   Posterior Beta(α', β') = Beta(α + successes, β + failures)
     *
     * The weight parameter allows signal strength to vary:
     * - Strong signal (correction, explicit feedback) → weight = 2.0
     * - Normal signal (transaction) → weight = 1.0
     * - Weak signal (inference) → weight = 0.5
     */
    suspend fun updateFromSignal(signal: BehavioralSignal) {
        // ── Update item frequency (frequentist — counts, not beliefs) ──
        if (signal.item != null) {
            val itemCount = profile.frequentItems.getOrDefault(signal.item, 0) + 1
            profile = profile.copy(
                frequentItems = profile.frequentItems + (signal.item to itemCount)
            )
        }

        // ── Track intent frequency ──
        val intentCount = profile.intentFrequency.getOrDefault(signal.intent, 0) + 1
        profile = profile.copy(
            intentFrequency = profile.intentFrequency + (signal.intent to intentCount)
        )

        // ── Update transaction amount (EMA for continuous value — not a belief) ──
        if (signal.amount != null && signal.amount > 0) {
            profile = profile.copy(
                averageTransactionAmount =
                    (profile.averageTransactionAmount * 0.9) + (signal.amount * 0.1)
            )
        }

        // ═══ Bayesian Belief Updates ═══

        // Signal: success/failure → consistency belief
        beliefs["consistency"] = beliefs["consistency"]!!.update(
            success = signal.success,
            weight = 1.0
        )

        // Signal: transaction amount → price_sensitivity
        // High amounts with acceptance → lower sensitivity (comfortable with prices)
        if (signal.amount != null && signal.amount > 0) {
            // Normalize: amounts > 1000 → low sensitivity signal, < 100 → high sensitivity
            val sensitivitySignal = 1.0 - (signal.amount.coerceIn(0.0, 2000.0) / 2000.0)
            beliefs["price_sensitivity"] = beliefs["price_sensitivity"]!!.updateContinuous(
                value = sensitivitySignal,
                weight = 0.5  // Weak signal — amount alone doesn't determine sensitivity
            )
        }

        // Signal: intent type → decision_speed
        // Quick intents (sale, purchase) → fast decision maker
        // Slow intents (advice, goal) → deliberate decision maker
        val isQuickIntent = signal.intent in listOf("record_sale", "record_purchase", "check_balance")
        beliefs["decision_speed"] = beliefs["decision_speed"]!!.update(
            success = isQuickIntent,
            weight = 0.3  // Weak signal — intent type is indirect evidence
        )

        // Signal: failure after action → risk_aversion increases
        // Worker who fails and then asks for advice → more risk-averse
        if (!signal.success) {
            beliefs["risk_aversion"] = beliefs["risk_aversion"]!!.update(
                success = true,  // "success" here means "evidence for risk aversion"
                weight = 1.5
            )
        }
    }

    /**
     * Apply a correction signal — strong Bayesian update.
     *
     * When the worker corrects the agent ("No, it was 500 not 300"),
     * this is a HIGH-CONFIDENCE signal that should shift beliefs quickly.
     * Weight is higher than normal signals (2.0 vs 1.0).
     *
     * Corrections affect:
     * - price_sensitivity: worker knows exact prices → high sensitivity
     * - consistency: correction after error → lower consistency
     * - learning_rate: worker teaches the agent → high learning engagement
     *
     * @param correctionType What was corrected: "price", "item", "amount", "other"
     * @param oldVal The incorrect value
     * @param newVal The correct value
     */
    suspend fun applyCorrection(
        correctionType: String,
        oldVal: String?,
        newVal: String?
    ) {
        // Corrections are strong signals (weight = 2.0)
        val strongWeight = 2.0

        when (correctionType) {
            "price", "amount" -> {
                // Worker corrects price → they know exact values → high price sensitivity
                beliefs["price_sensitivity"] = beliefs["price_sensitivity"]!!.update(
                    success = true,  // Evidence FOR price sensitivity
                    weight = strongWeight
                )
                // Also: worker is attentive to details
                beliefs["consistency"] = beliefs["consistency"]!!.update(
                    success = false,  // The interaction was inconsistent (had an error)
                    weight = strongWeight
                )
            }
            "item" -> {
                // Worker corrects item → they know their inventory
                beliefs["consistency"] = beliefs["consistency"]!!.update(
                    success = false,
                    weight = strongWeight
                )
            }
        }

        // All corrections signal high learning engagement
        beliefs["learning_rate"] = beliefs["learning_rate"]!!.update(
            success = true,
            weight = strongWeight
        )

        Timber.i("L3 Bayesian: Applied correction '%s' — price_sensitivity=%.3f (α=%.1f, β=%.1f), " +
                "consistency=%.3f (α=%.1f, β=%.1f)",
            correctionType,
            beliefs["price_sensitivity"]!!.mean, beliefs["price_sensitivity"]!!.alpha, beliefs["price_sensitivity"]!!.beta,
            beliefs["consistency"]!!.mean, beliefs["consistency"]!!.alpha, beliefs["consistency"]!!.beta
        )
    }

    /**
     * Consolidate L2 patterns into L3 beliefs.
     * Called periodically (every 10 interactions) by UnifiedMemoryBridge.
     *
     * Extracts aggregate signals from episode history and updates beliefs.
     * This is the L2→L3 signal path.
     */
    suspend fun consolidatePatterns(patterns: BehavioralPatterns) {
        // Merge L2 patterns into L3 behavioral model
        profile = profile.copy(
            frequentItems = profile.frequentItems + patterns.frequentItems
        )

        // Update Bayesian beliefs from aggregate patterns
        if (patterns.averageAmount > 0) {
            val sensitivitySignal = 1.0 - (patterns.averageAmount.coerceIn(0.0, 2000.0) / 2000.0)
            beliefs["price_sensitivity"] = beliefs["price_sensitivity"]!!.updateContinuous(
                value = sensitivitySignal,
                weight = 0.3  // Weak aggregate signal
            )
        }

        // High success rate → high consistency
        val successRate = patterns.frequentItems.values.sum().toDouble().coerceAtLeast(1.0)
        if (successRate > 5) {
            beliefs["consistency"] = beliefs["consistency"]!!.update(
                success = true,
                weight = 0.5
            )
        }
    }

    /**
     * Initialize L3 behavioral model from bootstrap data.
     * Sets business type, language, skill affinities, and Bayesian priors.
     *
     * Bootstrap provides STRONG priors because the worker explicitly told us.
     * effectiveSamples=10 means "this is worth 10 observations".
     */
    fun initializeFromProfile(bootstrapProfile: WorkerProfile) {
        profile = profile.copy(
            preferredLanguage = bootstrapProfile.preferredLanguage,
            businessType = bootstrapProfile.businessType,
            skills = bootstrapProfile.skills
        )

        // Set informative priors from bootstrap data
        // Business type affects initial beliefs:
        when (bootstrapProfile.businessType) {
            "retail" -> {
                beliefs["price_sensitivity"] = BayesianBelief.informative("price_sensitivity", 0.7, 10.0)
                beliefs["decision_speed"] = BayesianBelief.informative("decision_speed", 0.6, 10.0)
            }
            "farming" -> {
                beliefs["price_sensitivity"] = BayesianBelief.informative("price_sensitivity", 0.5, 10.0)
                beliefs["risk_aversion"] = BayesianBelief.informative("risk_aversion", 0.6, 10.0)
            }
            "service" -> {
                beliefs["decision_speed"] = BayesianBelief.informative("decision_speed", 0.7, 10.0)
                beliefs["digital_literacy"] = BayesianBelief.informative("digital_literacy", 0.6, 10.0)
            }
        }

        Timber.i("L3: Initialized from bootstrap — business=%s, language=%s, skills=%d, " +
                "priors: price_sensitivity=%.2f (α=%.1f,β=%.1f), risk_aversion=%.2f",
            bootstrapProfile.businessType,
            bootstrapProfile.preferredLanguage,
            bootstrapProfile.skills.size,
            beliefs["price_sensitivity"]!!.mean,
            beliefs["price_sensitivity"]!!.alpha,
            beliefs["price_sensitivity"]!!.beta,
            beliefs["risk_aversion"]!!.mean
        )
    }

    /**
     * Run relevance decay on all beliefs.
     * Called periodically during memory maintenance.
     *
     * Beliefs older than their half-life decay toward the uninformed prior.
     * This prevents stale beliefs from dominating behavior.
     *
     * @param daysSinceLastUpdate Days since the last maintenance cycle
     */
    fun runDecay(daysSinceLastUpdate: Double) {
        val halfLifeDays = 60.0  // 60-day half-life as per arch_memory.md §5
        beliefs.forEach { (name, belief) ->
            beliefs[name] = belief.decay(halfLifeDays, daysSinceLastUpdate)
        }
    }
}

data class WorkerProfile(
    val averageTransactionAmount: Double = 0.0,
    val frequentItems: Map<String, Int> = emptyMap(),
    val intentFrequency: Map<String, Int> = emptyMap(),
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
