package com.msaidizi.app.agent

/**
 * Sealed class hierarchy for agent events.
 *
 * All events flowing through the agent system are typed instances
 * of this sealed class, enabling exhaustive when() matching and
 * compile-time safety.
 *
 * Architecture:
 *   ┌─────────────┐     ┌──────────────┐     ┌─────────────┐
 *   │   Source     │────▶│  AgentEvent  │────▶│  Subscriber │
 *   │   Agent      │     │   (typed)    │     │   Agent     │
 *   └─────────────┘     └──────────────┘     └─────────────┘
 *
 * Event categories:
 * 1. Intent Events — User input classified
 * 2. Transaction Events — Business data recorded
 * 3. Intelligence Events — Analysis completed
 * 4. Agent Lifecycle Events — Agent state changes
 * 5. Error Events — Failures and recovery
 * 6. Learning Events — Adaptation signals
 */
sealed class AgentEvent {

    /** Unique event identifier for tracing. */
    abstract val eventId: String

    /** Event timestamp (epoch millis). */
    abstract val timestamp: Long

    /** Source agent or component that produced this event. */
    abstract val source: String

    // ═══════════════════════════════════════════════════════════════
    // Intent Events
    // ═══════════════════════════════════════════════════════════════

    /**
     * User input has been classified with an intent.
     * Dispatched by IntentRouter after classification.
     */
    data class IntentClassified(
        override val eventId: String,
        override val timestamp: Long,
        override val source: String,
        val intent: String,
        val confidence: Double,
        val extractedData: Map<String, String>,
        val language: String,
        val rawText: String,
    ) : AgentEvent()

    /**
     * Intent classification failed or was ambiguous.
     * Triggers clarification flow.
     */
    data class IntentAmbiguous(
        override val eventId: String,
        override val timestamp: Long,
        override val source: String,
        val rawText: String,
        val candidateIntents: List<String>,
        val bestConfidence: Double,
    ) : AgentEvent()

    // ═══════════════════════════════════════════════════════════════
    // Transaction Events
    // ═══════════════════════════════════════════════════════════════

    /**
     * A business transaction was recorded (sale, purchase, expense).
     */
    data class TransactionRecorded(
        override val eventId: String,
        override val timestamp: Long,
        override val source: String,
        val transactionId: Long,
        val type: String,        // SALE, PURCHASE, EXPENSE
        val item: String,
        val amount: Double,
        val quantity: Double,
        val language: String,
    ) : AgentEvent()

    /**
     * A transaction was corrected by the user.
     */
    data class TransactionCorrected(
        override val eventId: String,
        override val timestamp: Long,
        override val source: String,
        val originalTransactionId: Long,
        val corrections: Map<String, String>,
    ) : AgentEvent()

    // ═══════════════════════════════════════════════════════════════
    // Intelligence Events
    // ═══════════════════════════════════════════════════════════════

    /**
     * Business intelligence analysis completed.
     */
    data class IntelligenceGenerated(
        override val eventId: String,
        override val timestamp: Long,
        override val source: String,
        val analysisType: String,  // TREND, PATTERN, ANOMALY, PREDICTION
        val summary: String,
        val confidence: Double,
        val dataPoints: Int,
    ) : AgentEvent()

    /**
     * A report was generated and delivered.
     */
    data class ReportDelivered(
        override val eventId: String,
        override val timestamp: Long,
        override val source: String,
        val reportType: String,   // DAILY, WEEKLY, MONTHLY
        val deliveryChannel: String,  // WHATSAPP, IN_APP
        val language: String,
    ) : AgentEvent()

    // ═══════════════════════════════════════════════════════════════
    // Agent Lifecycle Events
    // ═══════════════════════════════════════════════════════════════

    /**
     * Agent started processing a task.
     */
    data class AgentTaskStarted(
        override val eventId: String,
        override val timestamp: Long,
        override val source: String,
        val taskType: String,
        val agentName: String,
    ) : AgentEvent()

    /**
     * Agent completed a task successfully.
     */
    data class AgentTaskCompleted(
        override val eventId: String,
        override val timestamp: Long,
        override val source: String,
        val taskType: String,
        val agentName: String,
        val durationMs: Long,
        val resultSummary: String,
    ) : AgentEvent()

    /**
     * Agent task failed.
     */
    data class AgentTaskFailed(
        override val eventId: String,
        override val timestamp: Long,
        override val source: String,
        val taskType: String,
        val agentName: String,
        val error: String,
        val shouldRetry: Boolean,
    ) : AgentEvent()

    // ═══════════════════════════════════════════════════════════════
    // Error Events
    // ═══════════════════════════════════════════════════════════════

    /**
     * An error occurred that needs attention.
     */
    data class ErrorOccurred(
        override val eventId: String,
        override val timestamp: Long,
        override val source: String,
        val errorCode: String,
        val message: String,
        val recoverable: Boolean,
        val context: Map<String, String> = emptyMap(),
    ) : AgentEvent()

    // ═══════════════════════════════════════════════════════════════
    // Learning Events
    // ═══════════════════════════════════════════════════════════════

    /**
     * The system learned a new pattern or correction.
     */
    data class PatternLearned(
        override val eventId: String,
        override val timestamp: Long,
        override val source: String,
        val patternType: String,  // VOCABULARY, CORRECTION, PREFERENCE
        val pattern: String,
        val confidence: Double,
    ) : AgentEvent()

    /**
     * Self-evolution cycle completed.
     */
    data class EvolutionCycleCompleted(
        override val eventId: String,
        override val timestamp: Long,
        override val source: String,
        val correctionsApplied: Int,
        val patternsLearned: Int,
        val metricsUpdated: Boolean,
    ) : AgentEvent()

    // ═══════════════════════════════════════════════════════════════
    // UI/Gamification Events
    // ═══════════════════════════════════════════════════════════════

    /**
     * User achieved a gamification milestone.
     */
    data class MilestoneReached(
        override val eventId: String,
        override val timestamp: Long,
        override val source: String,
        val milestoneType: String,  // STREAK, SALES_TARGET, GOAL
        val milestoneValue: Int,
        val celebrationMessage: String,
    ) : AgentEvent()

    /**
     * Streak is at risk — trigger protection flow.
     */
    data class StreakAtRisk(
        override val eventId: String,
        override val timestamp: Long,
        override val source: String,
        val currentStreak: Int,
        val hoursRemaining: Int,
        val reminderMessage: String,
    ) : AgentEvent()
}
