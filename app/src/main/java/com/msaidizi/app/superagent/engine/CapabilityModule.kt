package com.msaidizi.app.superagent.engine

// ═══════════════════════════════════════════════════════════════════
// CAPABILITY MODULE — Interface for domain-specific modules
// ═══════════════════════════════════════════════════════════════════

/**
 * A capability module plugs into the OODA loop's ACT phase.
 *
 * Modules handle specific domains (financial, credit, goals, etc.)
 * and produce [AgentResponse] results. They are NOT agents — they are
 * skills of the single superagent.
 *
 * Each module:
 * - Declares which intents it handles via [supportedIntents]
 * - Receives a [ResolvedIntent] with full context
 * - Returns an [AgentResponse] ready for personalization
 *
 * Modules are direct function calls — no event bus, no A2A protocol.
 * This is intentional: in-process modules don't need messaging overhead.
 */
interface CapabilityModule {

    /**
     * Handle a resolved intent and produce a response.
     * Called by the OODA loop during the ACT phase.
     *
     * @param intent The resolved intent with extracted data and context
     * @return The agent's response
     */
    suspend fun handle(intent: ResolvedIntent): AgentResponse

    /**
     * The set of intent strings this module can handle.
     * Used by the DECIDE phase for routing.
     */
    val supportedIntents: Set<String>
}

/**
 * A resolved intent — the full picture of what the worker wants.
 *
 * @property intent The classified intent string (e.g., "SALE", "PROFIT_QUERY")
 * @property extractedData Structured data extracted from the input
 * @property confidence How confident the classifier is (0.0–1.0)
 * @property context Full orientation context
 */
data class ResolvedIntent(
    val intent: String,
    val extractedData: Map<String, Any> = emptyMap(),
    val confidence: Float = 0f,
    val context: Orientation = Orientation(intent = "")
)

// ═══════════════════════════════════════════════════════════════════
// SIGNAL PROVIDERS — Data sources for the OBSERVE phase
// ═══════════════════════════════════════════════════════════════════

/**
 * A source of observational data for the OBSERVE phase.
 *
 * Implementations should be fast (< 50ms) and never block the loop.
 * If data isn't available, return a default/empty value.
 *
 * @param T The type of signal this provider produces
 */
interface SignalProvider<T> {
    /**
     * Collect this signal for the given observation context.
     *
     * @param text The input text (for context-aware collection)
     * @return The signal data, or a default if unavailable
     */
    suspend fun observe(text: String): T

    /** How important this signal is */
    val priority: SignalPriority

    /** Maximum time to wait for this signal (ms) */
    val maxLatencyMs: Long
}

/** Signal priority levels */
enum class SignalPriority { CRITICAL, HIGH, MEDIUM, LOW }

/**
 * Provides worker context signals (recent transactions, goals, streaks).
 */
interface WorkerSignalProvider : SignalProvider<WorkerSignal>

/**
 * Provides market context signals (prices, anomalies, trends).
 */
interface MarketSignalProvider : SignalProvider<MarketSignal>

/**
 * Provides proactive trigger signals (alerts, reminders).
 */
interface ProactiveSignalProvider : SignalProvider<ProactiveSignal?>

/**
 * Provides conversation context signals (recent turns, current topic).
 */
interface ContextSignalProvider : SignalProvider<ContextSignal>

/** Context about the current conversation */
data class ContextSignal(
    val recentTurns: List<String> = emptyList(),
    val currentTopic: String? = null,
    val pendingCorrection: Boolean = false,
    val sessionTurnCount: Int = 0
)

// ═══════════════════════════════════════════════════════════════════
// INTENT CLASSIFIER INTERFACE
// ═══════════════════════════════════════════════════════════════════

/**
 * Classifies raw text into structured intents.
 * Code-first: pattern matching handles 90% of inputs without LLM.
 */
interface IntentClassifier {
    /**
     * Classify text into an intent.
     *
     * @param text Normalized input text
     * @return Parse result with intent, extracted data, and confidence
     */
    suspend fun classify(text: String): ParseResult

    /**
     * Fuzzy match for handling typos and partial input.
     *
     * @param text Normalized input text
     * @return Parse result (lower confidence than classify)
     */
    suspend fun fuzzyMatch(text: String): ParseResult
}

/**
 * Normalizes dialect/Sheng to standard Swahili.
 */
interface DialectNormalizer {
    /**
     * Normalize text by expanding Sheng and fixing dialect variations.
     *
     * @param text Raw input text
     * @param language Detected language
     * @return Normalized text
     */
    fun normalize(text: String, language: String): String
}

/**
 * Checks data completeness for backend needs (M-KOPA model).
 */
interface DataCompletenessChecker {
    /**
     * Check if the observation has enough data for the backend.
     *
     * @param observation The current observation
     * @return Completeness result with missing fields and follow-up question
     */
    fun check(observation: Observation): CompletenessResult
}

/**
 * Safety guard — checks responses for harmful content.
 */
interface SafetyGuard {
    /**
     * Verify a response is safe to deliver.
     *
     * @param response The module's response
     * @param originalInput The worker's original input
     * @param language The worker's language
     * @return A safe version of the response
     */
    fun check(response: AgentResponse, originalInput: String, language: String): AgentResponse
}

/**
 * Manages progressive autonomy levels.
 */
interface AutonomyManager {
    /**
     * Check if an action is approved at the current autonomy level.
     *
     * @param response The proposed response
     * @param orientation The current orientation
     * @return An approved version of the response
     */
    fun check(response: AgentResponse, orientation: Orientation): AgentResponse
}

/**
 * LLM engine for escalation when code-first classification fails.
 */
interface LlmEngine {
    /**
     * Classify and respond using the LLM.
     *
     * @param text Input text
     * @param language Worker's language
     * @param context Additional context for the LLM
     * @return Parse result with intent and extracted data
     */
    suspend fun classify(text: String, language: String, context: String = ""): ParseResult

    /**
     * Classify and generate a full response using the LLM.
     *
     * @param llmContext Full context for the LLM
     * @return LLM result with intent and response text
     */
    suspend fun classifyAndRespond(llmContext: LlmContext): LlmResult
}

/** Context provided to the LLM for classification + response */
data class LlmContext(
    val input: String,
    val language: String = "sw",
    val workerProfile: String = "",
    val recentTransactions: String = "",
    val activeGoals: String = "",
    val conversationHistory: String = ""
)

/** Result from LLM classification + response */
data class LlmResult(
    val intent: String,
    val extractedData: Map<String, Any> = emptyMap(),
    val confidence: Float = 0.5f,
    val responseText: String = ""
) {
    fun toParsedIntent(): ParseResult = ParseResult(
        intent = intent,
        extractedData = extractedData,
        confidence = confidence,
        method = ParseMethod.LLM
    )
}

// ═══════════════════════════════════════════════════════════════════
// EXCEPTIONS
// ═══════════════════════════════════════════════════════════════════

/** Thrown when the ORIENT phase fails */
class OrientationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown when a module fails to execute */
class ModuleExecutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown when an intent is not supported by any module */
class UnsupportedIntentException(intent: String) : Exception("No module handles intent: $intent")
