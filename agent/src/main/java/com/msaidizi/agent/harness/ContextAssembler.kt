package com.msaidizi.agent.harness

import com.msaidizi.core.database.*
import com.msaidizi.core.util.DateTimeUtil
import com.msaidizi.core.model.BusinessProfile
import com.msaidizi.core.model.ConversationEntity
import com.msaidizi.core.model.KnowledgeEntity
import com.msaidizi.core.model.UserProfileEntity
import com.msaidizi.agent.flywheel.FlywheelEngine
import com.msaidizi.agent.memory.LAYER_DAILY
import com.msaidizi.agent.memory.LAYER_PATTERNS
import com.msaidizi.agent.memory.MemoryManager
import com.msaidizi.agent.tools.AlamaScore
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ContextAssembler — 5-Layer Context Assembly for the Superagent.
 *
 * Assembles context in priority order (most relevant first) so the LLM
 * always sees the highest-signal information within its context window.
 *
 * Layer 1 — System Identity (static, cached):
 *   Worker profile, business type, language preference, Alama Score.
 *   Rarely changes; cached in-memory and refreshed on profile update.
 *
 * Layer 2 — Working Memory (OODA state):
 *   Current OODA phase (Observe → Orient → Decide → Act),
 *   recent observations, active decisions. Volatile, session-scoped.
 *
 * Layer 3 — Session Memory (conversation):
 *   Recent messages from current + cross-session history,
 *   with older context compressed into summaries.
 *
 * Layer 4 — Knowledge Base (retrieval):
 *   Relevant business patterns, market data, financial insights,
 *   retrieved via intent-matched embedding search.
 *
 * Layer 5 — Flywheel Insights (learned):
 *   Personalized patterns, vocabulary, business rhythms the agent
 *   has learned from past interactions via the FlywheelEngine.
 */
@Singleton
class ContextAssembler @Inject constructor(
    private val userProfileDao: UserProfileDao,
    private val saleDao: SaleDao,
    private val expenseDao: ExpenseDao,
    private val productDao: ProductDao,
    private val customerDao: CustomerDao,
    private val knowledgeDao: KnowledgeDao,
    private val memoryManager: MemoryManager,
    private val flywheelEngine: FlywheelEngine,
    private val alamaScore: AlamaScore,
    private val gson: Gson
) {

    // ── Layer 1 Cache ───────────────────────────────────────────────
    // System Identity is expensive to build but rarely changes.
    // Cached in memory; invalidated on profile update.
    private var cachedIdentity: SystemIdentity? = null
    private var identityCacheTimestamp: 0L = 0
    private val IDENTITY_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes

    // ── Layer 2: OODA State (session-scoped, volatile) ──────────────
    private var currentOodaPhase: OodaPhase = OodaPhase.OBSERVE
    private val oodaObservations = ConcurrentLinkedDeque<String>()
    private val oodaDecisions = ConcurrentLinkedDeque<String>()
    private var lastOodaTransition: Long = System.currentTimeMillis()

    // ── Session Summaries Cache ─────────────────────────────────────
    private var cachedSessionSummaries: List<String>? = null
    private var sessionSummariesTimestamp: 0L = 0
    private val SESSION_SUMMARIES_TTL_MS = 2 * 60 * 1000L // 2 minutes

    /**
     * Assemble full 5-layer context for the LLM.
     * Layers are populated in priority order; each layer can be truncated
     * to fit the overall token budget.
     */
    suspend fun assemble(
        intent: UserIntent,
        sessionId: String,
        recentConversation: Flow<List<ConversationEntity>>
    ): AssembledContext {
        // ── Layer 1: System Identity (static, cached) ───────────────
        val identity = buildOrGetIdentity()

        // ── Layer 2: Working Memory / OODA State ────────────────────
        updateOodaState(intent)
        val oodaState = buildOodaState()

        // ── Layer 3: Session Memory (conversation) ──────────────────
        val conversation = try {
            recentConversation.first()
        } catch (e: Exception) {
            Timber.w(e, "Failed to load recent conversation")
            emptyList()
        }
        val sessionSummaries = getSessionSummaries()

        // ── Layer 4: Knowledge Base (retrieval) ─────────────────────
        val financialSummary = buildFinancialSummary()
        val knowledge = getRelevantKnowledge(intent)
        val marketInsights = getMarketInsights(intent)

        // ── Layer 5: Flywheel Insights (learned) ────────────────────
        val flywheelPatterns = getFlywheelInsights(intent)

        return AssembledContext(
            // Layer 1
            userProfile = identity.userProfile,
            businessProfile = identity.businessProfile,
            alamaScore = identity.alamaScore,
            // Layer 2
            oodaPhase = oodaState.phase,
            oodaObservations = oodaState.observations,
            oodaDecisions = oodaState.decisions,
            // Layer 3
            recentConversation = conversation,
            sessionSummaries = sessionSummaries,
            // Layer 4
            recentFinancialSummary = financialSummary,
            knowledgeContext = knowledge,
            marketInsights = marketInsights,
            // Layer 5
            relevantPatterns = flywheelPatterns.learnedPatterns,
            learnedVocabulary = flywheelPatterns.learnedVocabulary,
            businessRhythms = flywheelPatterns.businessRhythms,
            // Legacy compat
            memoryContext = null
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // LAYER 1 — System Identity (static, cached)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build or return cached SystemIdentity.
     * Profile + business info are expensive to parse; Alama Score is
     * expensive to compute. Cache both for 5 minutes.
     */
    private suspend fun buildOrGetIdentity(): SystemIdentity {
        val now = System.currentTimeMillis()
        cachedIdentity?.let { cached ->
            if (now - identityCacheTimestamp < IDENTITY_CACHE_TTL_MS) {
                return cached
            }
        }

        val profile = userProfileDao.getProfileOnce()

        val businessProfile = profile?.businessProfile?.let {
            try {
                gson.fromJson(it, BusinessProfile::class.java)
            } catch (e: Exception) {
                Timber.w(e, "Failed to parse business profile")
                null
            }
        }

        // Alama Score — compute once and cache
        val alamaResult = try {
            alamaScore.calculateScore()
        } catch (e: Exception) {
            Timber.w(e, "Failed to calculate Alama Score for context")
            null
        }

        val identity = SystemIdentity(
            userProfile = profile,
            businessProfile = businessProfile,
            alamaScore = alamaResult
        )

        cachedIdentity = identity
        identityCacheTimestamp = now
        return identity
    }

    /**
     * Invalidate the identity cache. Call when profile is updated.
     */
    fun invalidateIdentityCache() {
        cachedIdentity = null
        identityCacheTimestamp = 0
    }

    // ═══════════════════════════════════════════════════════════════
    // LAYER 2 — Working Memory / OODA State
    // ═══════════════════════════════════════════════════════════════

    /**
     * Advance the OODA loop based on the current intent.
     *
     * OODA = Observe → Orient → Decide → Act
     *   OBSERVE:  User is querying / checking state (ASK_*, CHECK_*, reports)
     *   ORIENT:   User is asking for advice or analysis (ASK_ADVICE)
     *   DECIDE:   User is evaluating options (ASK_PROFIT, weekly/monthly reports)
     *   ACT:      User is recording transactions (RECORD_*, ADD_*, UPDATE_*)
     */
    private fun updateOodaState(intent: UserIntent) {
        val newPhase = when (intent.type) {
            IntentType.ASK_SALES_TODAY, IntentType.ASK_STOCK,
            IntentType.ASK_EXPENSES, IntentType.ASK_DEBTORS,
            IntentType.ASK_SERVICES_TODAY, IntentType.CHECK_STOCK,
            IntentType.CHECK_CUSTOMER_DEBT, IntentType.GREETING,
            IntentType.HELP, IntentType.CHITCHAT -> OodaPhase.OBSERVE

            IntentType.ASK_ADVICE -> OodaPhase.ORIENT

            IntentType.ASK_PROFIT, IntentType.DAILY_REPORT,
            IntentType.WEEKLY_REPORT, IntentType.MONTHLY_REPORT -> OodaPhase.DECIDE

            IntentType.RECORD_SALE, IntentType.RECORD_EXPENSE,
            IntentType.RECORD_PURCHASE, IntentType.RECORD_SERVICE,
            IntentType.ADD_PRODUCT, IntentType.UPDATE_STOCK,
            IntentType.ADD_CUSTOMER, IntentType.RECORD_PAYMENT -> OodaPhase.ACT

            else -> currentOodaPhase // stay in current phase
        }

        // Record observation for the OODA loop
        val observation = "${intent.type.name}: ${intent.rawText.take(80)}"
        oodaObservations.addLast(observation)
        // Keep only last 10 observations
        while (oodaObservations.size > 10) {
            oodaObservations.pollFirst()
        }

        // Record decision if phase changed to ACT
        if (newPhase == OodaPhase.ACT && currentOodaPhase != OodaPhase.ACT) {
            oodaDecisions.addLast("Acting on: ${intent.type.name}")
            while (oodaDecisions.size > 5) {
                oodaDecisions.pollFirst()
            }
        }

        if (newPhase != currentOodaPhase) {
            Timber.d("OODA phase: $currentOodaPhase → $newPhase")
            lastOodaTransition = System.currentTimeMillis()
        }
        currentOodaPhase = newPhase
    }

    private fun buildOodaState(): OodaState {
        return OodaState(
            phase = currentOodaPhase,
            observations = oodaObservations.toList(),
            decisions = oodaDecisions.toList()
        )
    }

    /**
     * Reset OODA state for a new session.
     */
    fun resetOodaState() {
        currentOodaPhase = OodaPhase.OBSERVE
        oodaObservations.clear()
        oodaDecisions.clear()
        lastOodaTransition = System.currentTimeMillis()
    }

    // ═══════════════════════════════════════════════════════════════
    // LAYER 3 — Session Memory (conversation)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get summarized older sessions to compress context.
     * Cached briefly to avoid repeated DB hits.
     */
    private suspend fun getSessionSummaries(): List<String> {
        val now = System.currentTimeMillis()
        cachedSessionSummaries?.let { cached ->
            if (now - sessionSummariesTimestamp < SESSION_SUMMARIES_TTL_MS) {
                return cached
            }
        }

        val summaries = try {
            memoryManager.getSessionSummaries()
        } catch (e: Exception) {
            Timber.w(e, "Failed to get session summaries")
            emptyList()
        }

        cachedSessionSummaries = summaries
        sessionSummariesTimestamp = now
        return summaries
    }

    // ═══════════════════════════════════════════════════════════════
    // LAYER 4 — Knowledge Base (retrieval)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build a text summary of today's financials.
     */
    private suspend fun buildFinancialSummary(): String? {
        val todayStart = DateTimeUtil.startOfDay()
        val todayEnd = DateTimeUtil.endOfDay()

        val totalSales = saleDao.getTotalSalesBetween(todayStart, todayEnd).first() ?: 0.0
        val totalExpenses = expenseDao.getTotalExpensesBetween(todayStart, todayEnd).first() ?: 0.0
        val transactionCount = saleDao.getTransactionCountBetween(todayStart, todayEnd).first()

        if (totalSales == 0.0 && totalExpenses == 0.0) return null

        val profit = totalSales - totalExpenses
        val lowStock = productDao.getLowStock().first()

        return buildString {
            appendLine("Today (${DateTimeUtil.today()}):")
            appendLine("- Sales: ${DateTimeUtil.formatCurrency(totalSales)} ($transactionCount transactions)")
            appendLine("- Expenses: ${DateTimeUtil.formatCurrency(totalExpenses)}")
            appendLine("- Profit: ${DateTimeUtil.formatCurrency(profit)}")
            if (lowStock.isNotEmpty()) {
                appendLine("- LOW STOCK ALERT: ${lowStock.joinToString(", ") { it.name }}")
            }
        }
    }

    /**
     * Get knowledge entries relevant to the current intent.
     * Uses intent-matched category retrieval for embedding-like search.
     */
    private suspend fun getRelevantKnowledge(intent: UserIntent): List<String> {
        val knowledge = mutableListOf<String>()

        when (intent.type) {
            IntentType.RECORD_SALE, IntentType.ASK_SALES_TODAY -> {
                knowledgeDao.getByCategory("business_patterns").first()
                    .take(3)
                    .forEach { knowledge.add(it.value) }
            }
            IntentType.ASK_ADVICE -> {
                knowledgeDao.getByCategory("advice").first()
                    .sortedByDescending { it.confidence }
                    .take(5)
                    .forEach { knowledge.add(it.value) }
            }
            IntentType.ASK_STOCK, IntentType.RECORD_PURCHASE -> {
                knowledgeDao.getByCategory("stock_patterns").first()
                    .take(3)
                    .forEach { knowledge.add(it.value) }
            }
            IntentType.ASK_PROFIT, IntentType.DAILY_REPORT,
            IntentType.WEEKLY_REPORT, IntentType.MONTHLY_REPORT -> {
                // Pull daily summaries for report context
                knowledgeDao.getByCategory(LAYER_DAILY).first()
                    .sortedByDescending { it.updatedAt }
                    .take(3)
                    .forEach { knowledge.add(it.value) }
                // Also pull business pattern trends
                knowledgeDao.getByCategory(LAYER_PATTERNS).first()
                    .sortedByDescending { it.confidence }
                    .take(2)
                    .forEach { knowledge.add(it.value) }
            }
            IntentType.ASK_DEBTORS, IntentType.CHECK_CUSTOMER_DEBT,
            IntentType.RECORD_PAYMENT -> {
                knowledgeDao.getByCategory("customer_patterns").first()
                    .take(3)
                    .forEach { knowledge.add(it.value) }
            }
            else -> {}
        }

        return knowledge
    }

    /**
     * Get market-level insights: comparative data, sector trends.
     * Uses knowledge entries tagged with "market" or "sector" categories.
     */
    private suspend fun getMarketInsights(intent: UserIntent): List<String> {
        val insights = mutableListOf<String>()

        // Market/sector knowledge relevant to the business type
        try {
            knowledgeDao.getByCategory("market_insights").first()
                .sortedByDescending { it.confidence }
                .take(2)
                .forEach { insights.add(it.value) }
        } catch (e: Exception) {
            Timber.w(e, "Failed to get market insights")
        }

        // Sector-specific patterns
        try {
            knowledgeDao.getByCategory("sector_trends").first()
                .sortedByDescending { it.updatedAt }
                .take(2)
                .forEach { insights.add(it.value) }
        } catch (e: Exception) {
            Timber.w(e, "Failed to get sector trends")
        }

        return insights
    }

    // ═══════════════════════════════════════════════════════════════
    // LAYER 5 — Flywheel Insights (learned)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Gather all flywheel-learned insights: patterns, vocabulary,
     * and business rhythms personalized to this worker.
     */
    private suspend fun getFlywheelInsights(intent: UserIntent): FlywheelInsights {
        // Learned patterns from FlywheelEngine
        val patterns = try {
            flywheelEngine.getLearnedPatterns()
                .sortedByDescending { it.confidence }
                .take(5)
                .map { pattern ->
                    "${pattern.key}: ${pattern.data.entries.joinToString(", ") { "${it.key}=${it.value}" }} " +
                        "(confidence=${String.format("%.1f", pattern.confidence)}, uses=${pattern.usageCount})"
                }
        } catch (e: Exception) {
            Timber.w(e, "Failed to get flywheel patterns")
            emptyList()
        }

        // Learned vocabulary — words the worker uses that aren't standard
        val vocabulary = try {
            flywheelEngine.getLearnedVocabulary()
                .entries
                .sortedByDescending { it.value }
                .take(10)
                .joinToString(", ") { "${it.key}(${String.format("%.0f", it.value * 100)}%)" }
        } catch (e: Exception) {
            Timber.w(e, "Failed to get flywheel vocabulary")
            ""
        }

        // Business rhythms — hourly activity patterns
        val rhythms = try {
            val hourlyPatterns = flywheelEngine.getHourlyPatterns()
            if (hourlyPatterns.isNotEmpty()) {
                val peakHours = hourlyPatterns.entries
                    .sortedByDescending { it.value }
                    .take(3)
                    .map { "${it.key}:00" }
                val adviceConfidence = flywheelEngine.getAdviceConfidence()
                val confidenceNote = if (adviceConfidence.isNotEmpty()) {
                    val top = adviceConfidence.entries.maxByOrNull { it.value }
                    " | Top intent confidence: ${top?.key}=${String.format("%.0f", (top?.value ?: 0f) * 100)}%"
                } else ""
                "Peak hours: ${peakHours.joinToString(", ")}$confidenceNote"
            } else {
                ""
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to get flywheel rhythms")
            ""
        }

        return FlywheelInsights(
            learnedPatterns = patterns,
            learnedVocabulary = vocabulary,
            businessRhythms = rhythms
        )
    }

    /**
     * Invalidate session summaries cache. Call on new session start.
     */
    fun invalidateSessionCache() {
        cachedSessionSummaries = null
        sessionSummariesTimestamp = 0
    }
}

// ═══════════════════════════════════════════════════════════════════
// Supporting Data Classes
// ═══════════════════════════════════════════════════════════════════

/**
 * OODA loop phases for working memory state tracking.
 */
enum class OodaPhase(val displayName: String) {
    OBSERVE("Observing — gathering data"),
    ORIENT("Orienting — analyzing context"),
    DECIDE("Deciding — evaluating options"),
    ACT("Acting — executing decisions")
}

/**
 * Snapshot of the current OODA loop state.
 */
data class OodaState(
    val phase: OodaPhase,
    val observations: List<String>,
    val decisions: List<String>
)

/**
 * Layer 1: Cached system identity.
 */
data class SystemIdentity(
    val userProfile: UserProfileEntity?,
    val businessProfile: BusinessProfile?,
    val alamaScore: com.msaidizi.app.superagent.tools.AlamaScoreResult?
)

/**
 * Layer 5: Aggregated flywheel insights.
 */
data class FlywheelInsights(
    val learnedPatterns: List<String>,
    val learnedVocabulary: String,
    val businessRhythms: String
)
