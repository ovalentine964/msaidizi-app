package com.msaidizi.app.superagent.council

import com.msaidizi.app.model.BusinessProfile
import com.msaidizi.app.model.UserProfileEntity
import com.msaidizi.app.superagent.harness.AssembledContext
import com.msaidizi.app.superagent.harness.OodaPhase
import com.msaidizi.app.superagent.tools.AlamaScoreResult
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * ContextScope — Hierarchical context sharing between supervisor and councils.
 *
 * Architecture:
 *   Supervisor holds GLOBAL context (full AssembledContext)
 *     → Each council gets a SCOPED view (only relevant subset)
 *       → Councils can REQUEST additional context from supervisor
 *
 * Design principles:
 *   1. Least privilege: councils see only what they need
 *   2. Lazy loading: additional context fetched on demand, not upfront
 *   3. Immutable snapshots: councils get a point-in-time copy, not a live reference
 *   4. Memory efficient: scoped contexts share underlying data (no deep copy)
 *
 * Memory overhead per council scope: ~200 bytes (references, not copies)
 * Total overhead for 6 councils: ~1.2KB — negligible on 2GB devices
 */
@Singleton
class ContextScope @Inject constructor() {

    /**
     * Cache of per-council scoped contexts.
     * Invalidated when global context changes.
     */
    private val scopedCache = ConcurrentHashMap<CouncilType, ScopedContext>(8)

    /**
     * The current global context snapshot.
     * Updated by the supervisor before delegating to councils.
     */
    @Volatile
    private var globalContext: AssembledContext? = null

    /**
     * Pending context requests from councils.
     * Key: request ID. Value: the requesting council and what it needs.
     */
    private val pendingRequests = ConcurrentHashMap<String, ContextRequest>(8)

    // ── Supervisor API ──────────────────────────────────────────

    /**
     * Set the global context. Called by the supervisor before dispatching work.
     * Invalidates all cached scoped contexts.
     */
    fun setGlobalContext(context: AssembledContext) {
        globalContext = context
        scopedCache.clear() // Invalidate all cached scopes
        Timber.d("Global context updated, scoped caches invalidated")
    }

    /**
     * Get a scoped context for a specific council.
     * Returns only the context layers relevant to that council.
     * Cached until global context changes.
     */
    fun getScopedContext(council: CouncilType): ScopedContext {
        return scopedCache.getOrPut(council) {
            buildScopedContext(council)
        }
    }

    /**
     * Fulfill a context request from a council.
     * Returns the requested context data, or null if request ID not found.
     */
    fun fulfillRequest(requestId: String): Map<String, Any>? {
        val request = pendingRequests.remove(requestId) ?: return null
        val context = globalContext ?: return null

        return when (request.requestedData) {
            ContextDataType.FULL_CONVERSATION -> mapOf(
                "conversation" to (context.recentConversation.map {
                    mapOf("role" to it.role, "content" to it.content)
                })
            )
            ContextDataType.FINANCIAL_SUMMARY -> mapOf(
                "financial_summary" to (context.recentFinancialSummary ?: "No data")
            )
            ContextDataType.MARKET_INSIGHTS -> mapOf(
                "market_insights" to context.marketInsights
            )
            ContextDataType.KNOWLEDGE_BASE -> mapOf(
                "knowledge" to context.knowledgeContext
            )
            ContextDataType.FLYWHEEL_PATTERNS -> mapOf(
                "patterns" to context.relevantPatterns,
                "vocabulary" to context.learnedVocabulary,
                "rhythms" to context.businessRhythms
            )
            ContextDataType.OODA_STATE -> mapOf(
                "phase" to context.oodaPhase.name,
                "observations" to context.oodaObservations,
                "decisions" to context.oodaDecisions
            )
            ContextDataType.FULL -> buildFullExport(context)
        }
    }

    // ── Council API ─────────────────────────────────────────────

    /**
     * Request additional context from the supervisor.
     * Returns a request ID that can be used to poll for the result.
     */
    fun requestContext(
        council: CouncilType,
        data: ContextDataType,
        reason: String = ""
    ): String {
        val requestId = "${council.name}_${System.nanoTime()}"
        pendingRequests[requestId] = ContextRequest(
            requestId = requestId,
            council = council,
            requestedData = data,
            reason = reason,
            timestamp = System.currentTimeMillis()
        )
        Timber.d("Context request: $council → $data (id=$requestId)")
        return requestId
    }

    /**
     * Get all pending context requests. Called by supervisor during its loop.
     */
    fun getPendingRequests(): List<ContextRequest> {
        return pendingRequests.values.toList()
    }

    // ── Internal: Scope Building ────────────────────────────────

    /**
     * Build a scoped context for a specific council type.
     * Each council gets a different slice of the global context.
     */
    private fun buildScopedContext(council: CouncilType): ScopedContext {
        val ctx = globalContext ?: return ScopedContext.EMPTY

        return when (council) {
            CouncilType.FINANCE -> ScopedContext(
                councilType = council,
                userProfile = ctx.userProfile,
                businessProfile = ctx.businessProfile,
                alamaScore = ctx.alamaScore,
                oodaPhase = ctx.oodaPhase,
                recentFinancialSummary = ctx.recentFinancialSummary,
                knowledgeContext = ctx.knowledgeContext.filter { 
                    it.contains("sale", ignoreCase = true) || 
                    it.contains("profit", ignoreCase = true) ||
                    it.contains("expense", ignoreCase = true) ||
                    it.contains("cash", ignoreCase = true) 
                },
                sessionSummaries = ctx.sessionSummaries.take(2),
                relevantPatterns = ctx.relevantPatterns.filter {
                    it.contains("revenue", ignoreCase = true) ||
                    it.contains("payment", ignoreCase = true)
                }
            )

            CouncilType.INVENTORY -> ScopedContext(
                councilType = council,
                userProfile = ctx.userProfile,
                businessProfile = ctx.businessProfile,
                oodaPhase = ctx.oodaPhase,
                knowledgeContext = ctx.knowledgeContext.filter {
                    it.contains("stock", ignoreCase = true) ||
                    it.contains("inventory", ignoreCase = true) ||
                    it.contains("restock", ignoreCase = true)
                },
                relevantPatterns = ctx.relevantPatterns.filter {
                    it.contains("restock", ignoreCase = true) ||
                    it.contains("stock", ignoreCase = true)
                },
                businessRhythms = ctx.businessRhythms
            )

            CouncilType.MARKET -> ScopedContext(
                councilType = council,
                businessProfile = ctx.businessProfile,
                oodaPhase = ctx.oodaPhase,
                marketInsights = ctx.marketInsights,
                knowledgeContext = ctx.knowledgeContext.filter {
                    it.contains("price", ignoreCase = true) ||
                    it.contains("market", ignoreCase = true) ||
                    it.contains("competitor", ignoreCase = true)
                },
                relevantPatterns = ctx.relevantPatterns.filter {
                    it.contains("pricing", ignoreCase = true) ||
                    it.contains("demand", ignoreCase = true)
                }
            )

            CouncilType.GROWTH -> ScopedContext(
                councilType = council,
                userProfile = ctx.userProfile,
                businessProfile = ctx.businessProfile,
                alamaScore = ctx.alamaScore,
                oodaPhase = ctx.oodaPhase,
                relevantPatterns = ctx.relevantPatterns,
                learnedVocabulary = ctx.learnedVocabulary,
                knowledgeContext = ctx.knowledgeContext.filter {
                    it.contains("goal", ignoreCase = true) ||
                    it.contains("credit", ignoreCase = true) ||
                    it.contains("gamif", ignoreCase = true)
                }
            )

            CouncilType.VOICE -> ScopedContext(
                councilType = council,
                businessProfile = ctx.businessProfile,
                learnedVocabulary = ctx.learnedVocabulary,
                oodaPhase = ctx.oodaPhase
            )

            CouncilType.SECURITY -> ScopedContext(
                councilType = council,
                userProfile = ctx.userProfile,
                oodaPhase = ctx.oodaPhase,
                oodaObservations = ctx.oodaObservations,
                oodaDecisions = ctx.oodaDecisions
            )
        }
    }

    /**
     * Build a full context export for cross-council requests.
     */
    private fun buildFullExport(ctx: AssembledContext): Map<String, Any> {
        return mapOf(
            "user_name" to (ctx.userProfile?.userName ?: "unknown"),
            "business_type" to (ctx.businessProfile?.businessType?.displayName ?: "unknown"),
            "location" to (ctx.businessProfile?.location ?: "unknown"),
            "alama_score" to (ctx.alamaScore?.score ?: 0),
            "ooda_phase" to ctx.oodaPhase.name,
            "financial_summary" to (ctx.recentFinancialSummary ?: "No data"),
            "knowledge_count" to ctx.knowledgeContext.size,
            "pattern_count" to ctx.relevantPatterns.size,
            "session_count" to ctx.sessionSummaries.size
        )
    }

    /**
     * Clear all cached contexts. Called on session end.
     */
    fun clear() {
        globalContext = null
        scopedCache.clear()
        pendingRequests.clear()
        Timber.i("ContextScope cleared")
    }
}

// ──────────────────────────────────────────────
// Supporting Types
// ──────────────────────────────────────────────

/**
 * A scoped context view for a specific council.
 * Contains only the context layers relevant to that council's domain.
 * Lightweight: holds references, not copies of data.
 */
data class ScopedContext(
    val councilType: CouncilType,
    val userProfile: UserProfileEntity? = null,
    val businessProfile: BusinessProfile? = null,
    val alamaScore: AlamaScoreResult? = null,
    val oodaPhase: OodaPhase = OodaPhase.OBSERVE,
    val oodaObservations: List<String> = emptyList(),
    val oodaDecisions: List<String> = emptyList(),
    val recentFinancialSummary: String? = null,
    val knowledgeContext: List<String> = emptyList(),
    val marketInsights: List<String> = emptyList(),
    val sessionSummaries: List<String> = emptyList(),
    val relevantPatterns: List<String> = emptyList(),
    val learnedVocabulary: String = "",
    val businessRhythms: String = ""
) {
    companion object {
        val EMPTY = ScopedContext(councilType = CouncilType.FINANCE)
    }

    /**
     * Convert to a human-readable summary for logging/debugging.
     */
    fun toSummary(): String = buildString {
        appendLine("ScopedContext[$councilType]:")
        appendLine("  user=${userProfile?.userName ?: "none"}")
        appendLine("  business=${businessProfile?.businessType?.displayName ?: "none"}")
        appendLine("  ooda=$oodaPhase")
        appendLine("  knowledge=${knowledgeContext.size} entries")
        appendLine("  patterns=${relevantPatterns.size} entries")
    }
}

/**
 * What additional data a council is requesting from the supervisor.
 */
enum class ContextDataType {
    FULL_CONVERSATION,   // All recent conversation turns
    FINANCIAL_SUMMARY,   // Today's financial summary
    MARKET_INSIGHTS,     // Market/sector data
    KNOWLEDGE_BASE,      // Full knowledge base entries
    FLYWHEEL_PATTERNS,   // Learned patterns + vocabulary
    OODA_STATE,          // Current OODA phase + observations
    FULL                 // Everything (use sparingly)
}

/**
 * A pending context request from a council.
 */
data class ContextRequest(
    val requestId: String,
    val council: CouncilType,
    val requestedData: ContextDataType,
    val reason: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
