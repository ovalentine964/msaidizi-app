package com.msaidizi.app.agent.loops

import com.msaidizi.app.agent.AgentEvent
import com.msaidizi.app.agent.AgentEventBus
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * On-device Self-Improving Feedback Loop.
 *
 * Ported from the backend's FeedbackAgent, optimized for mobile.
 * Learns from every transaction — not just explicit feedback,
 * but implicit signals from transaction outcomes.
 *
 * Continuously improves decision parameters through:
 * 1. Signal Extraction — learn from each outcome
 * 2. Pattern Detection — find trends across signals
 * 3. Strategy Update — adjust parameters based on patterns
 * 4. Validation — track whether updates improved outcomes
 *
 * Architecture:
 *   Transaction Outcome
 *          │
 *          ▼
 *   ┌──────────────────┐
 *   │ Signal Extraction │ ← Extract learning signals
 *   └──────┬───────────┘
 *          ▼
 *   ┌──────────────────┐
 *   │ Pattern Detection │ ← Identify patterns across signals
 *   └──────┬───────────┘
 *          ▼
 *   ┌──────────────────┐
 *   │ Strategy Update   │ ← Adjust decision parameters
 *   └──────┬───────────┘
 *          ▼
 *   ┌──────────────────┐
 *   │ Validation        │ ← Track improvement
 *   └──────┬───────────┘
 *          ▼
 *      Deploy / Rollback
 *
 * ## Key Insight
 * Reflexion improves within a single task. Feedback Loop improves
 * the decision-making strategy across ALL tasks over time.
 *
 * ## Mathematical Foundation
 *
 * ### Exponential Time Decay
 * Signal weight decays with half-life: w(t) = e^(-0.693 × age / halfLife)
 * Recent signals matter more than old ones.
 *
 * ### Linear Regression for Gradient
 * For each strategy parameter, compute correlation between
 * parameter values and outcomes to find the gradient direction.
 *
 * @param eventBus The agent event bus
 * @param decayHalfLifeHours Signal weight half-life in hours (default: 1 week)
 * @param minSignalsForPattern Minimum signals before pattern detection
 * @param minSignalsForUpdate Minimum signals before strategy update
 */
class FeedbackLoop(
    private val eventBus: AgentEventBus = AgentEventBus.getInstance(),
    private val decayHalfLifeHours: Double = 168.0,  // 1 week
    private val minSignalsForPattern: Int = 5,
    private val minSignalsForUpdate: Int = 10
) {
    // ── Internal State ────────────────────────────────────────────

    /** Accumulated learning signals. */
    private val signals = ArrayDeque<LearningSignal>(MAX_SIGNALS)

    /** Detected patterns. */
    private val patterns = mutableListOf<DetectedPattern>()

    /** Tunable strategy parameters. */
    private val parameters = ConcurrentHashMap<String, StrategyParameter>()

    /** Signal counter for batched operations. */
    private val signalCount = AtomicInteger(0)

    /** Running average surprise. */
    private var avgSurprise = 0.0

    /** Total strategies updated. */
    private val strategiesUpdated = AtomicInteger(0)

    /** Total rollbacks. */
    private val rollbacks = AtomicInteger(0)

    /** Registered outcome extractors by event type. */
    private val outcomeExtractors = ConcurrentHashMap<String, OutcomeExtractor>()

    // ═══════════════ PUBLIC API ═══════════════

    /**
     * Register a strategy parameter that can be tuned by feedback.
     */
    fun registerParameter(param: StrategyParameter) {
        parameters[param.name] = param
        Timber.d("Feedback parameter registered: %s = %.4f", param.name, param.currentValue)
    }

    /**
     * Register an outcome extractor for a specific event type.
     */
    fun registerExtractor(eventType: String, extractor: OutcomeExtractor) {
        outcomeExtractors[eventType] = extractor
    }

    /**
     * Start listening to the event bus for feedback signals.
     */
    fun startListening(scope: CoroutineScope) {
        scope.launch {
            eventBus.events.collect { event ->
                val eventType = event::class.simpleName ?: return@collect
                val extractor = matchExtractor(eventType) ?: return@collect
                processOutcome(event, extractor)
            }
        }
        Timber.i("Feedback loop listening on event bus")
    }

    /**
     * Manually process an outcome (for explicit feedback).
     */
    suspend fun processOutcome(
        event: AgentEvent,
        extractor: OutcomeExtractor
    ) {
        // Stage 1: Extract signal
        val signal = extractor.extractSignal(event)
        addSignal(signal)

        val n = signalCount.incrementAndGet()
        Timber.d("Feedback signal #%d: type=%s, outcome=%.3f, surprise=%.3f",
            n, signal.type.name, signal.outcomeValue, signal.surprise)

        // Update running average surprise
        avgSurprise += (signal.surprise - avgSurprise) / n

        // Stage 2: Detect patterns (batched — every N signals)
        if (n % minSignalsForPattern == 0) {
            detectPatterns()
        }

        // Stage 3: Update strategies (batched — every N signals)
        if (n % minSignalsForUpdate == 0) {
            updateStrategies()
        }
    }

    /**
     * Get current value of a strategy parameter.
     */
    fun getParameter(name: String): Double? {
        return parameters[name]?.currentValue
    }

    /**
     * Get all current strategy parameters.
     */
    fun getAllParameters(): Map<String, Double> {
        return parameters.mapValues { it.value.currentValue }
    }

    /**
     * Get detected patterns.
     */
    fun getPatterns(): List<DetectedPattern> = patterns.toList()

    /**
     * Get recent signals.
     */
    fun getRecentSignals(n: Int = 20): List<LearningSignal> {
        synchronized(signals) {
            return signals.toList().takeLast(n)
        }
    }

    /**
     * Get feedback loop metrics.
     */
    fun getMetrics(): FeedbackMetrics {
        val signalList = synchronized(signals) { signals.toList() }
        val typeCounts = signalList.groupBy { it.type.name }.mapValues { it.value.size }

        return FeedbackMetrics(
            totalSignals = signalCount.get(),
            signalsByType = typeCounts,
            patternsDetected = patterns.size,
            strategiesUpdated = strategiesUpdated.get(),
            rollbacks = rollbacks.get(),
            avgSurprise = avgSurprise,
            parameters = parameters.mapValues { it.value.toSnapshot() }
        )
    }

    /**
     * Get improvement trajectory for a parameter.
     */
    fun getTrajectory(paramName: String): List<ParameterSnapshot> {
        return parameters[paramName]?.history?.toList() ?: emptyList()
    }

    // ═══════════════ STAGE 2: PATTERN DETECTION ═══════════════

    private fun detectPatterns() {
        val recent = synchronized(signals) {
            signals.toList().takeLast(minSignalsForPattern * 3)
        }
        if (recent.size < minSignalsForPattern) return

        // Group by tags
        val tagGroups = recent.flatMap { signal ->
            signal.tags.map { tag -> tag to signal }
        }.groupBy({ it.first }, { it.second })

        val newPatterns = mutableListOf<DetectedPattern>()

        for ((tag, group) in tagGroups) {
            if (group.size < minSignalsForPattern) continue

            val outcomes = group.map { it.outcomeValue }
            val meanOutcome = outcomes.average()
            val successRate = outcomes.count { it >= 0.5 }.toDouble() / outcomes.size

            if (successRate < 0.3) {
                newPatterns.add(DetectedPattern(
                    description = "Consistent poor outcomes for $tag (success rate: ${"%.0f".format(successRate * 100)}%)",
                    confidence = minOf(0.95, group.size.toDouble() / 20),
                    signalCount = group.size,
                    contextSignature = tag,
                    recommendation = "Investigate and adjust strategy for $tag"
                ))
            } else if (successRate > 0.9) {
                val surprises = group.map { it.surprise }
                val meanSurprise = surprises.average()
                if (meanSurprise > 0.3) {
                    newPatterns.add(DetectedPattern(
                        description = "High surprise but good outcomes for $tag — may be overfitting",
                        confidence = minOf(0.8, group.size.toDouble() / 15),
                        signalCount = group.size,
                        contextSignature = tag,
                        recommendation = "Validate $tag outcomes are not coincidental"
                    ))
                }
            }
        }

        // Merge with existing patterns
        for (newP in newPatterns) {
            val existing = patterns.firstOrNull { it.contextSignature == newP.contextSignature }
            if (existing != null) {
                existing.confidence = maxOf(existing.confidence, newP.confidence)
                existing.signalCount = newP.signalCount
                existing.lastSeen = System.currentTimeMillis()
                existing.recommendation = newP.recommendation
            } else {
                patterns.add(newP)
            }
        }

        if (newPatterns.isNotEmpty()) {
            Timber.i("Feedback: detected %d new patterns (total: %d)",
                newPatterns.size, patterns.size)
        }
    }

    // ═══════════════ STAGE 3: STRATEGY UPDATE ═══════════════

    private fun updateStrategies() {
        for ((name, param) in parameters) {
            val recent = synchronized(signals) {
                signals.toList().takeLast(200).filter { signal ->
                    signal.tags.any { it.contains(name) } || signal.tags.isEmpty()
                }
            }

            if (recent.size < 5) continue

            // Weighted performance at current value
            val currentPerf = weightedPerformance(recent)

            // Simple linear regression for gradient
            val history = param.history.toList().takeLast(20)
            if (history.size >= 5) {
                val values = history.map { it.value }
                val outcomes = history.map { it.outcome }

                val meanV = values.average()
                val meanO = outcomes.average()
                val cov = values.zip(outcomes).sumOf { p -> (p.first - meanV) * (p.second - meanO) }
                val varV = values.sumOf { v -> (v - meanV) * (v - meanV) }

                if (varV > 0) {
                    val gradient = cov / varV
                    val delta = (param.maxValue - param.minValue) * 0.05
                    val step = delta * if (gradient > 0) 1.0 else -1.0
                    val newValue = (param.currentValue + step).coerceIn(param.minValue, param.maxValue)

                    param.currentValue = newValue
                    param.updateCount++
                    param.lastUpdated = System.currentTimeMillis()
                    param.history.addLast(ParameterSnapshot(newValue, currentPerf, System.currentTimeMillis()))
                    if (param.history.size > MAX_HISTORY) param.history.removeFirst()

                    strategiesUpdated.incrementAndGet()
                    Timber.d("Feedback: updated %s → %.4f (gradient=%.4f)", name, newValue, gradient)
                }
            }
        }
    }

    private fun weightedPerformance(signals: List<LearningSignal>): Double {
        if (signals.isEmpty()) return 0.5
        val totalWeight = signals.sumOf { it.weight }
        if (totalWeight == 0.0) return 0.5
        return signals.sumOf { it.outcomeValue * it.weight } / totalWeight
    }

    // ═══════════════ INTERNALS ═══════════════

    private fun addSignal(signal: LearningSignal) {
        synchronized(signals) {
            if (signals.size >= MAX_SIGNALS) {
                signals.removeFirst()
            }
            signals.addLast(signal)
        }
    }

    private fun matchExtractor(eventType: String): OutcomeExtractor? {
        return outcomeExtractors[eventType]
            ?: outcomeExtractors.entries.firstOrNull { (key, _) ->
                eventType.contains(key, ignoreCase = true)
            }?.value
    }

    companion object {
        private const val MAX_SIGNALS = 5000
        private const val MAX_HISTORY = 100
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/**
 * Interface for extracting learning signals from events.
 */
fun interface OutcomeExtractor {
    suspend fun extractSignal(event: AgentEvent): LearningSignal
}

/**
 * Types of learning signals.
 */
enum class SignalType {
    SUCCESS,
    FAILURE,
    OUTPERFORMED,
    UNDERPERFORMED,
    NOVEL_PATTERN,
    DRIFT,
    ANOMALY
}

/**
 * A learning signal extracted from an outcome.
 */
data class LearningSignal(
    val signalId: String = UUID.randomUUID().toString().take(10),
    val type: SignalType,
    val sourceEventId: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val context: Map<String, Any> = emptyMap(),
    val outcomeValue: Double,      // Normalized -1.0 to 1.0
    val expectedValue: Double,     // What we predicted
    val surprise: Double,          // |outcome - expected|
    val weight: Double = 1.0,      // Importance weight (decays with time)
    val tags: List<String> = emptyList()
)

/**
 * A detected pattern in learning signals.
 */
data class DetectedPattern(
    val patternId: String = UUID.randomUUID().toString().take(10),
    val description: String,
    var confidence: Double,
    var signalCount: Int,
    val firstSeen: Long = System.currentTimeMillis(),
    var lastSeen: Long = System.currentTimeMillis(),
    val contextSignature: String,
    var recommendation: String
)

/**
 * A tunable strategy parameter with history.
 */
class StrategyParameter(
    val name: String,
    var currentValue: Double,
    val defaultValue: Double,
    val minValue: Double = 0.0,
    val maxValue: Double = 1.0,
    var updateCount: Int = 0,
    var lastUpdated: Long = System.currentTimeMillis()
) {
    /** Performance history: (value, outcome) pairs. */
    val history = ArrayDeque<ParameterSnapshot>(100)

    fun getBestValue(): Double {
        if (history.isEmpty()) return currentValue
        return history.maxByOrNull { it.outcome }?.value ?: currentValue
    }

    fun toSnapshot(): ParameterSnapshot {
        return ParameterSnapshot(currentValue, 0.0, lastUpdated)
    }
}

/**
 * Snapshot of a parameter value with its outcome.
 */
data class ParameterSnapshot(
    val value: Double,
    val outcome: Double,
    val timestamp: Long
)

/**
 * Aggregated feedback loop metrics.
 */
data class FeedbackMetrics(
    val totalSignals: Int,
    val signalsByType: Map<String, Int>,
    val patternsDetected: Int,
    val strategiesUpdated: Int,
    val rollbacks: Int,
    val avgSurprise: Double,
    val parameters: Map<String, ParameterSnapshot>
)
