package com.msaidizi.app.agent.loops

import com.msaidizi.app.agent.AgentEvent
import com.msaidizi.app.agent.AgentEventBus
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-Device Human-in-the-Loop — Progressive Autonomy for CFO Decisions.
 *
 * The agent earns trust over time. Starts fully supervised, gradually
 * gains autonomy as the user confirms good decisions.
 *
 * Autonomy Levels:
 *   Level 0: ASK      — Agent asks permission before every action
 *   Level 1: SUGGEST  — Agent suggests, user confirms
 *   Level 2: ACT+NOTIFY — Agent acts, notifies user after
 *   Level 3: AUTONOMOUS — Agent acts independently (user can override)
 *
 * Trust is earned per DOMAIN (transactions, goals, loans, etc.),
 * not globally. A user might trust the agent with transaction categorization
 * (Level 3) but want confirmation on loan decisions (Level 1).
 *
 * Architecture:
 *   ┌─────────────┐
 *   │   EVENT     │ ← AgentEvent from AgentEventBus
 *   └──────┬──────┘
 *          ▼
 *   ┌─────────────┐
 *   │  TRUST      │ ← Check trust level for this domain
 *   │  ASSESSMENT │
 *   └──────┬──────┘
 *          ▼
 *   ┌─────────────┐     ┌─────────────┐
 *   │  ASK USER   │────►│  USER       │
 *   │  (Level 0)  │     │  RESPONSE   │
 *   └──────┬──────┘     └──────┬──────┘
 *          │                   │
 *          ▼                   ▼
 *   ┌─────────────┐     ┌─────────────┐
 *   │  EXECUTE    │◄────│  CONFIRM /  │
 *   │  + RECORD   │     │  OVERRIDE   │
 *   └──────┬──────┘     └─────────────┘
 *          ▼
 *   ┌─────────────┐
 *   │  TRUST      │ ← Update trust score based on outcome
 *   │  UPDATE     │
 *   └─────────────┘
 */
class HumanInTheLoop(
    private val agentEventBus: AgentEventBus,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "HITL"
        private const val TRUST_INCREMENT = 0.05f
        private const val TRUST_DECREMENT = 0.15f
        private const val LEVEL_UP_THRESHOLD = 0.8f
        private const val LEVEL_DOWN_THRESHOLD = 0.3f
        private const val MAX_CONFIRMATION_WAIT_MS = 300_000L // 5 minutes
    }

    /** Autonomy levels for progressive trust. */
    enum class AutonomyLevel(val value: Int) {
        ASK(0),           // Ask permission before every action
        SUGGEST(1),       // Suggest, user confirms
        ACT_NOTIFY(2),    // Act, notify user after
        AUTONOMOUS(3);    // Act independently

        companion object {
            fun fromInt(value: Int) = entries.firstOrNull { it.value == value } ?: ASK
        }
    }

    /** Decision domains — trust is tracked per domain. */
    enum class Domain {
        TRANSACTION_CATEGORIZATION,
        PRICE_ESTIMATION,
        GOAL_PLANNING,
        LOAN_ADVICE,
        TITHE_CALCULATION,
        CASH_FLOW_PREDICTION,
        BUSINESS_ADVICE,
        GAMIFICATION,
        GENERAL
    }

    /** A pending decision waiting for user confirmation. */
    data class PendingDecision(
        val id: String = UUID.randomUUID().toString(),
        val domain: Domain,
        val action: String,
        val context: Map<String, Any>,
        val proposedResult: Any,
        val timestamp: Long = System.currentTimeMillis(),
        val confidence: Float
    )

    /** Trust state for a specific domain. */
    data class DomainTrust(
        var score: Float = 0.5f,
        var level: AutonomyLevel = AutonomyLevel.ASK,
        var totalDecisions: Int = 0,
        var confirmedDecisions: Int = 0,
        var overriddenDecisions: Int = 0
    )

    // Trust scores per domain
    private val trustScores = ConcurrentHashMap<Domain, DomainTrust>()

    // Pending decisions awaiting user confirmation
    private val pendingDecisions = ConcurrentHashMap<String, PendingDecision>()

    // Decision history for learning
    private val decisionHistory = mutableListOf<DecisionRecord>()

    data class DecisionRecord(
        val decisionId: String,
        val domain: Domain,
        val action: String,
        val userConfirmed: Boolean,
        val userOverride: String?,
        val outcome: Outcome?,
        val timestamp: Long
    )

    enum class Outcome {
        SUCCESS,      // User confirmed and outcome was good
        FAILURE,      // User confirmed but outcome was bad
        NEUTRAL,      // No measurable outcome
        UNKNOWN       // Outcome not yet determined
    }

    init {
        // Initialize all domains at Level 0 (ASK)
        Domain.entries.forEach { domain ->
            trustScores[domain] = DomainTrust()
        }
    }

    /**
     * Get the current autonomy level for a domain.
     */
    fun getAutonomyLevel(domain: Domain): AutonomyLevel {
        return trustScores[domain]?.level ?: AutonomyLevel.ASK
    }

    /**
     * Get trust score for a domain (0.0 to 1.0).
     */
    fun getTrustScore(domain: Domain): Float {
        return trustScores[domain]?.score ?: 0.5f
    }

    /**
     * Evaluate whether an action can be taken autonomously.
     * Returns Either a PendingDecision (needs confirmation) or null (can proceed).
     */
    fun evaluateDecision(
        domain: Domain,
        action: String,
        context: Map<String, Any>,
        proposedResult: Any,
        confidence: Float
    ): PendingDecision? {
        val trust = trustScores[domain] ?: return createPendingDecision(domain, action, context, proposedResult, confidence)

        return when (trust.level) {
            AutonomyLevel.ASK -> {
                // Always ask — create pending decision
                createPendingDecision(domain, action, context, proposedResult, confidence)
            }
            AutonomyLevel.SUGGEST -> {
                // Show suggestion but don't block
                createPendingDecision(domain, action, context, proposedResult, confidence)
            }
            AutonomyLevel.ACT_NOTIFY -> {
                // Act immediately, notify user after
                scope.launch { notifyUser(domain, action, proposedResult) }
                null // Proceed without blocking
            }
            AutonomyLevel.AUTONOMOUS -> {
                // Full autonomy — proceed silently
                null
            }
        }
    }

    /**
     * User confirms a pending decision.
     */
    fun confirmDecision(decisionId: String): Boolean {
        val decision = pendingDecisions.remove(decisionId) ?: return false

        // Update trust
        updateTrust(decision.domain, confirmed = true)

        // Record decision
        decisionHistory.add(
            DecisionRecord(
                decisionId = decision.id,
                domain = decision.domain,
                action = decision.action,
                userConfirmed = true,
                userOverride = null,
                outcome = Outcome.UNKNOWN,
                timestamp = System.currentTimeMillis()
            )
        )

        Timber.tag(TAG).d("Decision confirmed: ${decision.action} in ${decision.domain}")
        return true
    }

    /**
     * User overrides a pending decision with a different action.
     */
    fun overrideDecision(decisionId: String, overrideAction: String): Boolean {
        val decision = pendingDecisions.remove(decisionId) ?: return false

        // Update trust (negative)
        updateTrust(decision.domain, confirmed = false)

        // Record decision
        decisionHistory.add(
            DecisionRecord(
                decisionId = decision.id,
                domain = decision.domain,
                action = decision.action,
                userConfirmed = false,
                userOverride = overrideAction,
                outcome = Outcome.UNKNOWN,
                timestamp = System.currentTimeMillis()
            )
        )

        Timber.tag(TAG).w("Decision overridden: ${decision.action} → $overrideAction in ${decision.domain}")
        return true
    }

    /**
     * Record the outcome of a decision for trust learning.
     */
    fun recordOutcome(decisionId: String, outcome: Outcome) {
        val record = decisionHistory.find { it.decisionId == decisionId } ?: return
        val idx = decisionHistory.indexOf(record)
        decisionHistory[idx] = record.copy(outcome = outcome)

        // Adjust trust based on outcome
        when (outcome) {
            Outcome.SUCCESS -> {
                val trust = trustScores[record.domain]
                if (trust != null) {
                    trust.score = (trust.score + TRUST_INCREMENT).coerceIn(0f, 1f)
                    checkLevelUp(record.domain, trust)
                }
            }
            Outcome.FAILURE -> {
                val trust = trustScores[record.domain]
                if (trust != null) {
                    trust.score = (trust.score - TRUST_DECREMENT).coerceIn(0f, 1f)
                    checkLevelDown(record.domain, trust)
                }
            }
            else -> { /* NEUTRAL / UNKNOWN — no trust change */ }
        }
    }

    /**
     * Get a summary of trust levels across all domains.
     */
    fun getTrustSummary(): Map<Domain, Map<String, Any>> {
        return trustScores.mapValues { (_, trust) ->
            mapOf(
                "score" to trust.score,
                "level" to trust.level.name,
                "totalDecisions" to trust.totalDecisions,
                "confirmed" to trust.confirmedDecisions,
                "overridden" to trust.overriddenDecisions
            )
        }
    }

    /**
     * Get pending decisions that need user attention.
     */
    fun getPendingDecisions(): List<PendingDecision> {
        // Clean up expired decisions
        val now = System.currentTimeMillis()
        pendingDecisions.entries.removeIf { now - it.value.timestamp > MAX_CONFIRMATION_WAIT_MS }
        return pendingDecisions.values.sortedByDescending { it.timestamp }
    }

    /**
     * Force set autonomy level for a domain (user preference).
     */
    fun setAutonomyLevel(domain: Domain, level: AutonomyLevel) {
        val trust = trustScores.getOrPut(domain) { DomainTrust() }
        trust.level = level
        Timber.tag(TAG).i("Forced autonomy level for $domain: $level")
    }

    // ── Private helpers ──────────────────────────────────────────

    private fun createPendingDecision(
        domain: Domain,
        action: String,
        context: Map<String, Any>,
        proposedResult: Any,
        confidence: Float
    ): PendingDecision {
        val decision = PendingDecision(
            domain = domain,
            action = action,
            context = context,
            proposedResult = proposedResult,
            confidence = confidence
        )
        pendingDecisions[decision.id] = decision

        // Emit event for UI to pick up
        scope.launch {
            agentEventBus.publish(
                AgentEvent.ProactiveAlert(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    source = TAG,
                    alertType = "AGENT_DECISION_PENDING",
                    severity = "INFO",
                    title = "Decision pending: $action",
                    message = "Agent needs confirmation for: $action in ${domain.name}",
                    data = mapOf(
                        "decisionId" to decision.id,
                        "domain" to domain.name,
                        "action" to action,
                        "confidence" to confidence.toString()
                    )
                )
            )
        }

        return decision
    }

    private fun updateTrust(domain: Domain, confirmed: Boolean) {
        val trust = trustScores.getOrPut(domain) { DomainTrust() }
        trust.totalDecisions++

        if (confirmed) {
            trust.confirmedDecisions++
            trust.score = (trust.score + TRUST_INCREMENT).coerceIn(0f, 1f)
            checkLevelUp(domain, trust)
        } else {
            trust.overriddenDecisions++
            trust.score = (trust.score - TRUST_DECREMENT).coerceIn(0f, 1f)
            checkLevelDown(domain, trust)
        }
    }

    private fun checkLevelUp(domain: Domain, trust: DomainTrust) {
        if (trust.score >= LEVEL_UP_THRESHOLD && trust.level.value < AutonomyLevel.AUTONOMOUS.value) {
            val newLevel = AutonomyLevel.fromInt(trust.level.value + 1)
            trust.level = newLevel
            Timber.tag(TAG).i("🎯 $domain leveled up to $newLevel (score: ${trust.score})")

            // Notify user of level up
            scope.launch {
                agentEventBus.publish(
                    AgentEvent.ProactiveAlert(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        source = TAG,
                        alertType = "AUTONOMY_LEVEL_UP",
                        severity = "INFO",
                        title = "$domain leveled up to $newLevel",
                        message = "Trust score: ${trust.score}. Autonomy increased to $newLevel.",
                        data = mapOf(
                            "event" to "autonomy_level_up",
                            "domain" to domain.name,
                            "newLevel" to newLevel.name,
                            "score" to trust.score.toString()
                        )
                    )
                )
            }
        }
    }

    private fun checkLevelDown(domain: Domain, trust: DomainTrust) {
        if (trust.score <= LEVEL_DOWN_THRESHOLD && trust.level.value > AutonomyLevel.ASK.value) {
            val newLevel = AutonomyLevel.fromInt(trust.level.value - 1)
            trust.level = newLevel
            Timber.tag(TAG).w("⚠️ $domain leveled down to $newLevel (score: ${trust.score})")
        }
    }

    private suspend fun notifyUser(domain: Domain, action: String, result: Any) {
        agentEventBus.publish(
            AgentEvent.ProactiveAlert(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                source = TAG,
                alertType = "AGENT_DECISION_MADE",
                severity = "INFO",
                title = "Action taken: $action",
                message = "Agent executed: $action in ${domain.name}",
                data = mapOf(
                    "domain" to domain.name,
                    "action" to action,
                    "result" to result.toString(),
                    "autonomyLevel" to "ACT_NOTIFY"
                )
            )
        )
    }
}
