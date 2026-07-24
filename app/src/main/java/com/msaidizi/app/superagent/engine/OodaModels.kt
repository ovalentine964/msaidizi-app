package com.msaidizi.app.superagent.engine

import com.msaidizi.app.superagent.context.ContextEngine
import com.msaidizi.app.superagent.flywheel.FlywheelModels

// ═══════════════════════════════════════════════════════════════════
// OODA MODELS — Core data structures for the OODA reasoning loop
// ═══════════════════════════════════════════════════════════════════

/**
 * The result of one complete OODA cycle.
 *
 * Contains the response to speak, any proof point generated,
 * and the learning signal captured for the flywheel.
 *
 * @property response The agent's response to the worker
 * @property proofPoint Proof point recorded (for Alama Score)
 * @property learningSignal Signal captured for the learning flywheel
 * @property actionType What kind of action was taken
 * @property orientation The full orientation (for debugging/tracing)
 * @property cycleTimeMs How long the full cycle took
 * @property offlineMode Whether operating in offline mode
 * @property degradedSignals Signals that were unavailable
 */
data class OodaResult(
    val response: AgentResponse,
    val proofPoint: FlywheelModels.ProofPoint? = null,
    val learningSignal: LearningSignal? = null,
    val actionType: ActionType = ActionType.RESPOND,
    val orientation: Orientation? = null,
    val cycleTimeMs: Long = 0,
    val offlineMode: Boolean = false,
    val degradedSignals: List<String> = emptyList()
)

/**
 * The agent's response to the worker.
 *
 * @property text The text to speak or display
 * @property type The type of response
 * @property shouldSpeak Whether to speak via TTS
 * @property data Additional data attached to the response
 */
data class AgentResponse(
    val text: String,
    val type: ResponseType = ResponseType.INFORMATION,
    val shouldSpeak: Boolean = false,
    val data: Map<String, String> = emptyMap()
)

// ═══════════════════════════════════════════════════════════════════
// ENUMS
// ═══════════════════════════════════════════════════════════════════

/** What kind of action the OODA loop took */
enum class ActionType {
    RESPOND,
    RESPOND_AND_RECORD,
    RESPOND_AND_ALERT,
    RESPOND_AND_SUGGEST,
    RESPOND_AND_CELEBRATE,
    CLARIFICATION,
    ALERT_ONLY,
    LLM_ESCALATION
}

/** Type of response generated */
enum class ResponseType {
    TRANSACTION_CONFIRMATION,
    QUERY_RESULT,
    ADVICE,
    ALERT,
    CELEBRATION,
    CLARIFICATION,
    INFORMATION,
    ERROR,
    LLM_GENERATED
}

/** Priority level */
enum class Priority { LOW, MEDIUM, HIGH, CRITICAL }

/** How the intent was parsed */
enum class ParseMethod { PATTERN, FUZZY, LLM }

/** Urgency level */
enum class Urgency { LOW, MEDIUM, HIGH, CRITICAL }

fun Urgency.toPriority(): Priority = when (this) {
    Urgency.LOW -> Priority.LOW
    Urgency.MEDIUM -> Priority.MEDIUM
    Urgency.HIGH -> Priority.HIGH
    Urgency.CRITICAL -> Priority.CRITICAL
}

/** Trigger that started this OODA cycle */
enum class TriggerType { VOICE_INPUT, TEXT_INPUT, PROACTIVE, FOLLOW_UP }

// ═══════════════════════════════════════════════════════════════════
// OBSERVATION MODELS
// ═══════════════════════════════════════════════════════════════════

/**
 * Raw observation bundle. Contains all signals for one OODA cycle.
 *
 * @property text The input text (from STT or direct text)
 * @property language Detected language
 * @property dialect Detected dialect
 * @property voice Voice-specific signals (null for text input)
 * @property market Market context signals
 * @property worker Worker context signals
 * @property proactive Proactive trigger signals
 * @property triggerType What triggered this cycle
 * @property timestamp When the observation was created
 */
data class Observation(
    val text: String,
    val language: String = "sw",
    val dialect: String = "standard",
    val voice: VoiceSignal? = null,
    val market: MarketSignal = MarketSignal.empty(),
    val worker: WorkerSignal = WorkerSignal.empty(),
    val proactive: ProactiveSignal? = null,
    val triggerType: TriggerType = TriggerType.TEXT_INPUT,
    val timestamp: Long = System.currentTimeMillis()
)

/** Voice-specific signals from STT */
data class VoiceSignal(
    val sttResult: String,
    val asrConfidence: Float,
    val language: String,
    val dialect: String,
    val emotion: String? = null,
    val pitch: Float? = null,
    val pace: Float? = null,
    val volume: Float? = null
)

/** Market context signals */
data class MarketSignal(
    val relevantPrices: Map<String, Double> = emptyMap(),
    val priceAnomalies: List<PriceAnomaly> = emptyList(),
    val demandTrend: String? = null,  // UP, DOWN, STABLE
    val isMarketDay: Boolean = false,
    val isHoliday: Boolean = false,
    val isWeekend: Boolean = false
) {
    companion object {
        fun empty() = MarketSignal()
    }
}

data class PriceAnomaly(
    val product: String,
    val currentPrice: Double,
    val normalPrice: Double,
    val changePercent: Double,
    val direction: PriceDirection
)

enum class PriceDirection { UP, DOWN }

/** Worker context signals */
data class WorkerSignal(
    val recentTransactions: List<Any> = emptyList(),
    val dailyAverage: Double = 0.0,
    val weeklyTrend: String = "STABLE",
    val activeGoals: List<Any> = emptyList(),
    val pendingAlerts: List<Any> = emptyList(),
    val streakDays: Int = 0,
    val alamaTier: String = "BUILDING",
    val lastInteractionTime: Long = 0,
    val interactionCount: Int = 0
) {
    companion object {
        fun empty() = WorkerSignal()
    }
}

/** Proactive trigger signals */
data class ProactiveSignal(
    val trigger: ProactiveTrigger,
    val urgency: Urgency,
    val message: String,
    val relatedData: Map<String, Any> = emptyMap()
)

enum class ProactiveTrigger {
    BILL_DUE, STOCK_LOW, GOAL_BEHIND, ANOMALY_DETECTED,
    PRICE_OPPORTUNITY, STREAK_RISK, MARKET_ALERT, WEATHER_ALERT
}

// ═══════════════════════════════════════════════════════════════════
// ORIENTATION MODELS
// ═══════════════════════════════════════════════════════════════════

/**
 * The full orientation — what's happening and what it means.
 * Produced by the ORIENT phase of the OODA loop.
 */
data class Orientation(
    val intent: String,
    val extractedData: Map<String, Any> = emptyMap(),
    val confidence: Float = 0f,
    val parseMethod: ParseMethod = ParseMethod.PATTERN,
    val analysis: Analysis = Analysis(),
    val goalImpacts: List<GoalImpact> = emptyList(),
    val urgency: Urgency = Urgency.LOW,
    val workerState: WorkerState = WorkerState(),
    val baseline: BaselineComparison? = null,
    val marketContext: MarketComparison? = null
)

/** Analysis results from the ORIENT phase */
data class Analysis(
    val signals: List<Signal> = emptyList(),
    val hasAnomaly: Boolean = false,
    val hasOpportunity: Boolean = false,
    val hasRisk: Boolean = false
)

/** A detected signal (anomaly, opportunity, risk) */
data class Signal(
    val type: SignalType,
    val severity: Severity,
    val message: String,
    val data: Map<String, Any> = emptyMap()
)

enum class SignalType {
    ANOMALY, OVERPRICE, UNDERPRICE, UNUSUAL_VOLUME,
    OPPORTUNITY, PRICE_DROP, MARKET_SHIFT,
    STREAK_RISK, STOCK_LOW, GOAL_BEHIND, BUDGET_OVERRUN,
    GOAL_NEAR, TIER_UNLOCK, CONSISTENCY_MILESTONE
}

enum class Severity { LOW, MEDIUM, HIGH, CRITICAL }

/** Impact on a worker's goal */
data class GoalImpact(
    val goalId: Long,
    val goalName: String,
    val impact: Double,       // -1.0 to 1.0
    val onTrack: Boolean,
    val daysRemaining: Int? = null
)

/** Worker state snapshot */
data class WorkerState(
    val streakDays: Int = 0,
    val alamaTier: String = "BUILDING",
    val recentTrend: String = "STABLE",
    val daysSinceLastInteraction: Int = 0
)

/** Baseline comparison for current transaction vs historical */
data class BaselineComparison(
    val average: Double = 0.0,
    val isAboveAverage: Boolean = false,
    val deviation: Double = 0.0
)

/** Market price comparison */
data class MarketComparison(
    val marketPrice: Double,
    val recordedPrice: Double,
    val priceDeviation: Double
)

// ═══════════════════════════════════════════════════════════════════
// ACTION PLAN
// ═══════════════════════════════════════════════════════════════════

/**
 * The decision from the DECIDE phase.
 *
 * @property module The capability module to invoke (null for LLM escalation)
 * @property actionType What kind of action to take
 * @property responseType What type of response to generate
 * @property confidence How confident we are in this decision
 * @property priority How urgent this is
 * @property proactiveAdditions Unsolicited info to attach
 * @property clarificationQuestion Question to ask if clarification needed
 */
data class ActionPlan(
    val module: CapabilityModule? = null,
    val actionType: ActionType = ActionType.RESPOND,
    val responseType: ResponseType = ResponseType.INFORMATION,
    val confidence: Float = 0f,
    val priority: Priority = Priority.MEDIUM,
    val proactiveAdditions: List<ProactiveAddition> = emptyList(),
    val clarificationQuestion: String? = null
)

/** Unsolicited information to attach to the response */
data class ProactiveAddition(
    val type: AdditionType,
    val content: String,
    val data: Map<String, Any> = emptyMap()
)

enum class AdditionType {
    REMINDER, ENCOURAGEMENT, SUGGESTION, GOAL_UPDATE, ALERT
}

// ═══════════════════════════════════════════════════════════════════
// LEARNING SIGNAL
// ═══════════════════════════════════════════════════════════════════

/**
 * Learning signal captured from every interaction.
 * Fed into the FlywheelEngine for improvement.
 */
data class LearningSignal(
    val input: String,
    val intent: String,
    val confidence: Float,
    val parseMethod: ParseMethod,
    val actionType: ActionType,
    val module: String?,
    val signals: List<SignalType>,
    val timestamp: Long = System.currentTimeMillis()
)

// ═══════════════════════════════════════════════════════════════════
// PARSE RESULT
// ═══════════════════════════════════════════════════════════════════

/**
 * Result of intent parsing in the ORIENT phase.
 */
data class ParseResult(
    val intent: String,
    val extractedData: Map<String, Any> = emptyMap(),
    val confidence: Float = 0f,
    val method: ParseMethod = ParseMethod.PATTERN
)

/** Contextualized intent — parsed + enriched with context */
data class ContextualizedIntent(
    val parsed: ParseResult,
    val baseline: BaselineComparison? = null,
    val historicalPatterns: List<Any> = emptyList(),
    val goalRelevance: List<Any> = emptyList(),
    val marketContext: MarketComparison? = null,
    val workerState: WorkerState = WorkerState()
)

/** Data completeness check result */
data class CompletenessResult(
    val isComplete: Boolean,
    val missingFields: List<String> = emptyList(),
    val followUpQuestion: String? = null
)
