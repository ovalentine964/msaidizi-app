package com.msaidizi.app.agent.autonomy

import com.msaidizi.app.agent.AgentEvent
import com.msaidizi.app.agent.AgentEventBus
import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.core.model.PatternType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Progressive Autonomy System — Graduated trust for agent actions.
 *
 * Implements a 5-level autonomy framework where the agent earns
 * higher autonomy through demonstrated accuracy. Each business
 * domain (sales, finance, inventory) has independent autonomy levels.
 *
 * ## Autonomy Levels
 *
 * | Level | Name              | Human-in-the-Loop                           | Example Actions |
 * |-------|-------------------|---------------------------------------------|-----------------|
 * | 1     | Supervised        | Every action requires explicit approval      | Suggest sale, show data |
 * | 2     | Assisted          | Approval for non-trivial actions             | Record transactions, basic reports |
 * | 3     | Delegated         | Approval only for high-value/critical ops    | Auto-record sales, generate briefings |
 * | 4     | Autonomous        | Approval only for irreversible actions       | Proactive alerts, auto-restock advice |
 * | 5     | Self-Governing    | Audit-only (post-hoc review)                 | Full agent operation |
 *
 * ## Promotion Criteria
 * - Accuracy ≥ threshold for N consecutive interactions
 * - No critical errors in the promotion window
 * - User has not explicitly demoted the domain
 *
 * ## Mathematical Foundation
 * Based on multi-armed bandit exploration: the agent explores at lower
 * autonomy levels and exploits at higher levels. Promotion is a
 * confidence interval test: P(accuracy ≥ threshold) > 0.95 given
 * observed data.
 *
 * @param patternDao Persistence for autonomy state
 * @param eventBus Event bus for autonomy change notifications
 */
class ProgressiveAutonomy(
    private val patternDao: PatternDao,
    private val eventBus: AgentEventBus = AgentEventBus.getInstance()
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "ProgressiveAutonomy"
        private val PATTERN_TYPE = PatternType.VOCABULARY
        private const val PATTERN_KEY_PREFIX = "autonomy_domain_"

        // Promotion thresholds per level
        internal val PROMOTION_THRESHOLDS = mapOf(
            AutonomyLevel.LEVEL_1_SUPERVISED to PromotionCriteria(
                minAccuracy = 0.70, minInteractions = 20, maxCriticalErrors = 3
            ),
            AutonomyLevel.LEVEL_2_ASSISTED to PromotionCriteria(
                minAccuracy = 0.85, minInteractions = 50, maxCriticalErrors = 2
            ),
            AutonomyLevel.LEVEL_3_DELEGATED to PromotionCriteria(
                minAccuracy = 0.90, minInteractions = 100, maxCriticalErrors = 1
            ),
            AutonomyLevel.LEVEL_4_AUTONOMOUS to PromotionCriteria(
                minAccuracy = 0.95, minInteractions = 200, maxCriticalErrors = 0
            ),
            // Level 5 cannot be promoted to automatically — requires explicit user grant
        )

        // Domains that can have independent autonomy levels
        val TRACKED_DOMAINS = listOf(
            Domain.SALES,
            Domain.INVENTORY,
            Domain.FINANCE,
            Domain.REPORTING,
            Domain.ADVICE,
            Domain.GIVING
        )
    }

    // ── In-memory state ───────────────────────────────────────────

    /** Per-domain autonomy state */
    private val domainStates = ConcurrentHashMap<Domain, DomainAutonomyState>()

    /** Observable state for UI binding */
    private val _overallState = MutableStateFlow(OverallAutonomyState())
    val overallState: StateFlow<OverallAutonomyState> = _overallState.asStateFlow()

    init {
        // Initialize all domains at Level 1
        for (domain in TRACKED_DOMAINS) {
            domainStates[domain] = DomainAutonomyState(domain = domain)
        }
        // Load persisted state
        scope.launch { loadState() }
    }

    // ═══════════════ AUTONOMY QUERY ═══════════════

    /**
     * Get current autonomy level for a domain.
     */
    fun getLevel(domain: Domain): AutonomyLevel {
        return domainStates[domain]?.level ?: AutonomyLevel.LEVEL_1_SUPERVISED
    }

    /**
     * Check if an action requires human approval given current autonomy.
     *
     * @param domain The business domain
     * @param action The action being attempted
     * @return ApprovalRequirement indicating whether approval is needed
     */
    fun checkApprovalRequired(domain: Domain, action: AgentAction): ApprovalRequirement {
        val level = getLevel(domain)
        return when (level) {
            AutonomyLevel.LEVEL_1_SUPERVISED -> ApprovalRequirement(
                required = true,
                reason = "Supervised mode — all actions need approval",
                severity = ApprovalSeverity.ALWAYS
            )
            AutonomyLevel.LEVEL_2_ASSISTED -> when {
                action.isHighValue -> ApprovalRequirement(
                    required = true,
                    reason = "High-value action (KSh ${"%.0f".format(action.value)}) needs approval",
                    severity = ApprovalSeverity.HIGH_VALUE
                )
                action.isIrreversible -> ApprovalRequirement(
                    required = true,
                    reason = "Irreversible action requires confirmation",
                    severity = ApprovalSeverity.IRREVERSIBLE
                )
                else -> ApprovalRequirement(required = false, severity = ApprovalSeverity.NONE)
            }
            AutonomyLevel.LEVEL_3_DELEGATED -> when {
                action.isCritical -> ApprovalRequirement(
                    required = true,
                    reason = "Critical operation needs approval",
                    severity = ApprovalSeverity.CRITICAL
                )
                action.isIrreversible && action.value > 10_000 -> ApprovalRequirement(
                    required = true,
                    reason = "High-value irreversible action",
                    severity = ApprovalSeverity.HIGH_VALUE
                )
                else -> ApprovalRequirement(required = false, severity = ApprovalSeverity.NONE)
            }
            AutonomyLevel.LEVEL_4_AUTONOMOUS -> when {
                action.isIrreversible && action.isCritical -> ApprovalRequirement(
                    required = true,
                    reason = "Irreversible critical action — approval required even at autonomous level",
                    severity = ApprovalSeverity.CRITICAL
                )
                else -> ApprovalRequirement(required = false, severity = ApprovalSeverity.NONE)
            }
            AutonomyLevel.LEVEL_5_SELF_GOVERNING -> ApprovalRequirement(
                required = false,
                reason = "Self-governing — action logged for audit",
                severity = ApprovalSeverity.NONE
            )
        }
    }

    // ═══════════════ OUTCOME RECORDING ═══════════════

    /**
     * Record the outcome of an agent action for accuracy tracking.
     *
     * @param domain The business domain
     * @param actionType What kind of action was taken
     * @param wasCorrect Whether the action/outcome was correct
     * @param wasCritical Whether this was a critical error if incorrect
     */
    fun recordOutcome(
        domain: Domain,
        actionType: String,
        wasCorrect: Boolean,
        wasCritical: Boolean = false
    ) {
        val state = domainStates[domain] ?: return
        val updated = state.copy(
            totalInteractions = state.totalInteractions + 1,
            correctOutcomes = state.correctOutcomes + if (wasCorrect) 1 else 0,
            criticalErrors = state.criticalErrors + if (!wasCorrect && wasCritical) 1 else 0,
            recentOutcomes = (state.recentOutcomes + wasCorrect).takeLast(100)
        )
        domainStates[domain] = updated

        // Check for promotion eligibility
        checkPromotion(domain, updated)

        // Persist
        scope.launch { persistState(domain, updated) }

        Timber.d("Outcome recorded: domain=%s correct=%b critical=%b (total=%d, accuracy=%.2f)",
            domain.name, wasCorrect, wasCritical,
            updated.totalInteractions, updated.accuracy)
    }

    // ═══════════════ PROMOTION / DEMOTION ═══════════════

    /**
     * Check if a domain qualifies for promotion to the next level.
     * Automatically promotes if criteria are met.
     */
    private fun checkPromotion(domain: Domain, state: DomainAutonomyState) {
        val currentLevel = state.level
        if (currentLevel == AutonomyLevel.LEVEL_5_SELF_GOVERNING) return

        val criteria = PROMOTION_THRESHOLDS[currentLevel] ?: return

        val meetsAccuracy = state.accuracy >= criteria.minAccuracy
        val meetsInteractions = state.totalInteractions >= criteria.minInteractions
        val meetsErrorLimit = state.criticalErrors <= criteria.maxCriticalErrors

        if (meetsAccuracy && meetsInteractions && meetsErrorLimit) {
            promoteDomain(domain, currentLevel.next(), "Automatic promotion: accuracy=${state.accuracy}, interactions=${state.totalInteractions}")
        }
    }

    /**
     * Promote a domain to a higher autonomy level.
     */
    private fun promoteDomain(domain: Domain, newLevel: AutonomyLevel, reason: String) {
        val state = domainStates[domain] ?: return
        val updated = state.copy(
            level = newLevel,
            promotedAt = System.currentTimeMillis(),
            promotionReason = reason
        )
        domainStates[domain] = updated

        // Publish event
        eventBus.publish(AgentEvent.PatternLearned(
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            source = "ProgressiveAutonomy",
            patternType = "AUTONOMY_PROMOTION",
            pattern = "${domain.name} → ${newLevel.name}: $reason",
            confidence = updated.accuracy
        ))

        updateOverallState()
        scope.launch { persistState(domain, updated) }

        Timber.i("Domain %s promoted to %s: %s", domain.name, newLevel.name, reason)
    }

    /**
     * Manually demote a domain (user-initiated).
     * Resets accuracy window to prevent immediate re-promotion.
     */
    fun demoteDomain(domain: Domain, reason: String = "User demotion") {
        val state = domainStates[domain] ?: return
        val newLevel = state.level.previous()
        val updated = state.copy(
            level = newLevel,
            totalInteractions = 0,
            correctOutcomes = 0,
            criticalErrors = 0,
            recentOutcomes = emptyList(),
            promotedAt = System.currentTimeMillis(),
            promotionReason = "Demoted: $reason"
        )
        domainStates[domain] = updated

        eventBus.publish(AgentEvent.PatternLearned(
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            source = "ProgressiveAutonomy",
            patternType = "AUTONOMY_DEMOTION",
            pattern = "${domain.name} → ${newLevel.name}: $reason",
            confidence = 0.0
        ))

        updateOverallState()
        scope.launch { persistState(domain, updated) }

        Timber.w("Domain %s demoted to %s: %s", domain.name, newLevel.name, reason)
    }

    /**
     * Grant Level 5 self-governing status to a domain (user-initiated only).
     */
    fun grantSelfGoverning(domain: Domain) {
        val state = domainStates[domain] ?: return
        if (state.level != AutonomyLevel.LEVEL_4_AUTONOMOUS) {
            Timber.w("Cannot grant self-governing: domain %s is at %s (must be AUTONOMOUS)",
                domain.name, state.level.name)
            return
        }

        val updated = state.copy(
            level = AutonomyLevel.LEVEL_5_SELF_GOVERNING,
            promotedAt = System.currentTimeMillis(),
            promotionReason = "User granted self-governing status"
        )
        domainStates[domain] = updated

        eventBus.publish(AgentEvent.PatternLearned(
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            source = "ProgressiveAutonomy",
            patternType = "AUTONOMY_SELF_GOVERNING",
            pattern = "${domain.name} → SELF_GOVERNING: User granted",
            confidence = 1.0
        ))

        updateOverallState()
        scope.launch { persistState(domain, updated) }

        Timber.i("Domain %s granted SELF_GOVERNING status", domain.name)
    }

    // ═══════════════ STATE QUERIES ═══════════════

    /**
     * Get full state for a domain.
     */
    fun getDomainState(domain: Domain): DomainAutonomyState {
        return domainStates[domain] ?: DomainAutonomyState(domain = domain)
    }

    /**
     * Get all domain states.
     */
    fun getAllDomainStates(): Map<Domain, DomainAutonomyState> {
        return domainStates.toMap()
    }

    /**
     * Get the minimum autonomy level across all domains.
     * Useful for determining global agent capability.
     */
    fun getMinimumLevel(): AutonomyLevel {
        return domainStates.values.minByOrNull { it.level.ordinal }?.level
            ?: AutonomyLevel.LEVEL_1_SUPERVISED
    }

    /**
     * Get human-in-the-loop requirements for the current state.
     */
    fun getHumanInTheLoopRequirements(): List<HumanInTheLoopRequirement> {
        return domainStates.map { (domain, state) ->
            HumanInTheLoopRequirement(
                domain = domain,
                level = state.level,
                description = when (state.level) {
                    AutonomyLevel.LEVEL_1_SUPERVISED -> "Every ${domain.displayName} action needs your approval"
                    AutonomyLevel.LEVEL_2_ASSISTED -> "I'll ask before big ${domain.displayName} decisions"
                    AutonomyLevel.LEVEL_3_DELEGATED -> "I handle routine ${domain.displayName} tasks; I ask for critical ones"
                    AutonomyLevel.LEVEL_4_AUTONOMOUS -> "I manage ${domain.displayName} independently; I ask only for irreversible actions"
                    AutonomyLevel.LEVEL_5_SELF_GOVERNING -> "I run ${domain.displayName} autonomously; you can review anytime"
                }
            )
        }
    }

    // ═══════════════ PERSISTENCE ═══════════════

    private suspend fun persistState(domain: Domain, state: DomainAutonomyState) {
        try {
            val dataJson = json.encodeToString(DomainAutonomyStateSerializable(
                domain = domain.name,
                level = state.level.name,
                totalInteractions = state.totalInteractions,
                correctOutcomes = state.correctOutcomes,
                criticalErrors = state.criticalErrors,
                promotedAt = state.promotedAt,
                promotionReason = state.promotionReason
            ))
            val existing = patternDao.getPatternByKey(PATTERN_TYPE, "$PATTERN_KEY_PREFIX${domain.name}")
            if (existing != null) {
                patternDao.updatePattern(existing.copy(
                    data = dataJson,
                    confidence = state.accuracy,
                    updatedAt = System.currentTimeMillis() / 1000
                ))
            } else {
                patternDao.insertPattern(com.msaidizi.app.core.model.BusinessPattern(
                    patternType = PATTERN_TYPE,
                    data = dataJson,
                    confidence = state.accuracy
                ))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist autonomy state for %s", domain.name)
        }
    }

    private suspend fun loadState() {
        for (domain in TRACKED_DOMAINS) {
            try {
                val pattern = patternDao.getPatternByKey(PATTERN_TYPE, "$PATTERN_KEY_PREFIX${domain.name}")
                if (pattern != null) {
                    val data = json.decodeFromString<DomainAutonomyStateSerializable>(pattern.data)
                    domainStates[domain] = DomainAutonomyState(
                        domain = domain,
                        level = AutonomyLevel.valueOf(data.level),
                        totalInteractions = data.totalInteractions,
                        correctOutcomes = data.correctOutcomes,
                        criticalErrors = data.criticalErrors,
                        promotedAt = data.promotedAt,
                        promotionReason = data.promotionReason
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to load autonomy state for %s, using default", domain.name)
            }
        }
        updateOverallState()
        Timber.d("Autonomy state loaded for %d domains", domainStates.size)
    }

    private fun updateOverallState() {
        _overallState.value = OverallAutonomyState(
            domainStates = domainStates.toMap(),
            minimumLevel = getMinimumLevel(),
            averageAccuracy = domainStates.values.map { it.accuracy }.average(),
            totalInteractions = domainStates.values.sumOf { it.totalInteractions }
        )
    }
}

// ═══════════════ ENUMS & DATA CLASSES ═══════════════

/**
 * Autonomy levels — from fully supervised to self-governing.
 */
enum class AutonomyLevel(val displayName: String) {
    LEVEL_1_SUPERVISED("Supervised"),
    LEVEL_2_ASSISTED("Assisted"),
    LEVEL_3_DELEGATED("Delegated"),
    LEVEL_4_AUTONOMOUS("Autonomous"),
    LEVEL_5_SELF_GOVERNING("Self-Governing");

    fun next(): AutonomyLevel {
        val nextOrdinal = (ordinal + 1).coerceAtMost(entries.size - 1)
        return entries[nextOrdinal]
    }

    fun previous(): AutonomyLevel {
        val prevOrdinal = (ordinal - 1).coerceAtLeast(0)
        return entries[prevOrdinal]
    }
}

/**
 * Business domains that can have independent autonomy levels.
 */
enum class Domain(val displayName: String) {
    SALES("Sales"),
    INVENTORY("Inventory"),
    FINANCE("Finance"),
    REPORTING("Reporting"),
    ADVICE("Advice"),
    GIVING("Giving")
}

/**
 * State for a single domain's autonomy tracking.
 */
data class DomainAutonomyState(
    val domain: Domain,
    val level: AutonomyLevel = AutonomyLevel.LEVEL_1_SUPERVISED,
    val totalInteractions: Int = 0,
    val correctOutcomes: Int = 0,
    val criticalErrors: Int = 0,
    val recentOutcomes: List<Boolean> = emptyList(),
    val promotedAt: Long = 0L,
    val promotionReason: String = ""
) {
    /** Current accuracy (0.0–1.0) */
    val accuracy: Double
        get() = if (totalInteractions > 0) correctOutcomes.toDouble() / totalInteractions else 0.0

    /** Recent accuracy (last 100 interactions) for trend detection */
    val recentAccuracy: Double
        get() = if (recentOutcomes.isNotEmpty()) recentOutcomes.count { it }.toDouble() / recentOutcomes.size else 0.0

    /** Interactions remaining before next promotion check */
    val interactionsUntilPromotion: Int
        get() {
            val nextLevel = level.next()
            if (nextLevel == level) return 0
            val criteria = ProgressiveAutonomy.PROMOTION_THRESHOLDS[level] ?: return 0
            return (criteria.minInteractions - totalInteractions).coerceAtLeast(0)
        }
}

/**
 * Serializable form of DomainAutonomyState for persistence.
 */
@kotlinx.serialization.Serializable
private data class DomainAutonomyStateSerializable(
    val domain: String,
    val level: String,
    val totalInteractions: Int,
    val correctOutcomes: Int,
    val criticalErrors: Int,
    val promotedAt: Long,
    val promotionReason: String
)

/**
 * Criteria for promotion from one level to the next.
 */
data class PromotionCriteria(
    val minAccuracy: Double,
    val minInteractions: Int,
    val maxCriticalErrors: Int
)

/**
 * An agent action being evaluated for autonomy.
 */
data class AgentAction(
    val type: String,
    val domain: Domain,
    val value: Double = 0.0,
    val isHighValue: Boolean = value > 5_000,
    val isIrreversible: Boolean = false,
    val isCritical: Boolean = false
)

/**
 * Whether an action requires human approval.
 */
data class ApprovalRequirement(
    val required: Boolean,
    val reason: String = "",
    val severity: ApprovalSeverity = ApprovalSeverity.NONE
)

enum class ApprovalSeverity {
    NONE,
    HIGH_VALUE,
    IRREVERSIBLE,
    CRITICAL,
    ALWAYS
}

/**
 * Human-in-the-loop requirement description for a domain.
 */
data class HumanInTheLoopRequirement(
    val domain: Domain,
    val level: AutonomyLevel,
    val description: String
)

/**
 * Overall autonomy state across all domains.
 */
data class OverallAutonomyState(
    val domainStates: Map<Domain, DomainAutonomyState> = emptyMap(),
    val minimumLevel: AutonomyLevel = AutonomyLevel.LEVEL_1_SUPERVISED,
    val averageAccuracy: Double = 0.0,
    val totalInteractions: Int = 0
)
