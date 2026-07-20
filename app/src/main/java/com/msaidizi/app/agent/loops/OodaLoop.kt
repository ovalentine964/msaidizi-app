package com.msaidizi.app.agent.loops

import com.msaidizi.app.agent.AgentEvent
import com.msaidizi.app.agent.AgentEventBus
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * On-device OODA Loop — Observe-Orient-Decide-Act for CFO decisions.
 *
 * Ported from the backend's OODAAgent, optimized for 2GB Android devices.
 * Maintains a persistent orientation state that evolves across cycles,
 * enabling rapid context-aware decisions without re-deriving context each time.
 *
 * Key difference from ReAct: OODA prioritizes speed over thoroughness.
 * ReAct reasons explicitly (expensive); OODA orients implicitly (cheap, fast).
 *
 * Architecture:
 *   ┌─────────────┐
 *   │   OBSERVE   │ ← Gather signals from AgentEventBus
 *   └──────┬──────┘
 *          ▼
 *   ┌─────────────┐
 *   │   ORIENT    │ ← Update persistent orientation axes
 *   └──────┬──────┘
 *          ▼
 *   ┌─────────────┐
 *   │   DECIDE    │ ← Fast heuristic or LLM-assisted
 *   └──────┬──────┘
 *          ▼
 *   ┌─────────────┐
 *   │    ACT      │ ← Execute and record outcome
 *   └──────┬──────┘
 *          │
 *          └──→ Update orientation → Loop back to OBSERVE
 *
 * ## Mathematical Foundation
 *
 * ### Exponential Moving Average for Orientation
 * Each axis is updated with EMA: new = old × (1 - weight) + observation × weight
 * This gives a smoothed mental model that resists noise but tracks real changes.
 *
 * ### Escalation Decision
 * If confidence < threshold, escalate to slower, more thorough reasoning.
 * P(escalate) = 1 if confidence < threshold, 0 otherwise (deterministic gate).
 *
 * @param eventBus The agent event bus for subscribing to events
 * @param escalationThreshold Confidence below which we escalate
 * @param maxCycleMs Maximum time budget per cycle (for mobile power management)
 */
class OodaLoop(
    private val eventBus: AgentEventBus = AgentEventBus.getInstance(),
    private val escalationThreshold: Double = 0.3,
    private val maxCycleMs: Long = 500L
) {
    // ── Persistent Orientation State ──────────────────────────────

    /**
     * Orientation axes — the agent's persistent mental model.
     * Updated with exponential moving average on each cycle.
     * Range: -1.0 to 1.0 for each axis.
     */
    private val orientation = ConcurrentHashMap<String, Double>().apply {
        put("market_trend", 0.0)     // -1.0 (bearish) to 1.0 (bullish)
        put("volatility", 0.0)       // 0.0 (calm) to 1.0 (volatile)
        put("urgency", 0.0)          // 0.0 (routine) to 1.0 (critical)
        put("confidence", 0.5)       // 0.0 (uncertain) to 1.0 (certain)
        put("risk_level", 0.0)       // 0.0 (safe) to 1.0 (dangerous)
        put("sentiment", 0.0)        // -1.0 (negative) to 1.0 (positive)
        put("supply_demand", 0.0)    // -1.0 (surplus) to 1.0 (shortage)
    }

    /** Drift history for volatility detection. */
    private val driftHistory = ArrayDeque<Map<String, Double>>(MAX_DRIFT_HISTORY)

    /** Cycle counter. */
    private val cycleCount = AtomicInteger(0)

    /** Total successful cycles. */
    private val successCount = AtomicInteger(0)

    /** Total escalations. */
    private val escalationCount = AtomicInteger(0)

    /** Average cycle time (EMA). */
    private val avgCycleMs = AtomicLong(0)

    /** Registered OODA handlers keyed by event type pattern. */
    private val handlers = ConcurrentHashMap<String, OodaHandler>()

    // ═══════════════ PUBLIC API ═══════════════

    /**
     * Register an OODA handler for a specific event type pattern.
     * The handler provides domain-specific observe/orient/decide/act logic.
     */
    fun registerHandler(eventPattern: String, handler: OodaHandler) {
        handlers[eventPattern] = handler
        Timber.d("OODA handler registered for: %s", eventPattern)
    }

    /**
     * Start listening to the event bus and running OODA cycles
     * for registered event types.
     */
    fun startListening(scope: CoroutineScope) {
        scope.launch {
            eventBus.events.collect { event ->
                val handler = matchHandler(event) ?: return@collect
                val cycleResult = runCycle(event, handler)

                if (cycleResult.escalated) {
                    escalationCount.incrementAndGet()
                    eventBus.publish(AgentEvent.ProactiveAlert(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        source = "OodaLoop",
                        alertType = "OODA_ESCALATION",
                        severity = "WARNING",
                        title = "Low confidence decision",
                        message = "Confidence ${"%.2f".format(cycleResult.confidence)} " +
                                "below threshold ${"%.2f".format(escalationThreshold)}. " +
                                "Escalating to thorough reasoning.",
                        data = mapOf(
                            "cycle_id" to cycleResult.cycleId,
                            "confidence" to cycleResult.confidence.toString()
                        )
                    ))
                }
            }
        }
        Timber.i("OODA loop listening on event bus")
    }

    /**
     * Run a single OODA cycle for an event.
     * Returns the cycle result with timing and outcome.
     */
    suspend fun runCycle(
        event: AgentEvent,
        handler: OodaHandler
    ): OodaCycleResult {
        val cycleId = UUID.randomUUID().toString().take(12)
        val cycleNum = cycleCount.incrementAndGet()
        val startTime = System.currentTimeMillis()

        Timber.d("OODA cycle %d starting: %s", cycleNum, event::class.simpleName)

        try {
            // ── OBSERVE ──
            val observeStart = System.currentTimeMillis()
            val observations = handler.observe(event, getOrientationSnapshot())
            val observeMs = System.currentTimeMillis() - observeStart

            // ── ORIENT ──
            val orientStart = System.currentTimeMillis()
            val orientationUpdate = handler.orient(observations, getOrientationSnapshot())
            applyOrientationUpdate(orientationUpdate)
            recordDrift()
            val orientMs = System.currentTimeMillis() - orientStart

            // ── DECIDE ──
            val decideStart = System.currentTimeMillis()
            val decision = handler.decide(observations, getOrientationSnapshot())
            val decideMs = System.currentTimeMillis() - decideStart

            // Check escalation
            if (decision.confidence < escalationThreshold) {
                val totalMs = System.currentTimeMillis() - startTime
                updateAvgCycleTime(totalMs)
                Timber.w("OODA cycle %d escalated: confidence %.2f < threshold %.2f",
                    cycleNum, decision.confidence, escalationThreshold)
                return OodaCycleResult(
                    cycleId = cycleId,
                    cycleNumber = cycleNum,
                    success = false,
                    escalated = true,
                    confidence = decision.confidence,
                    totalMs = totalMs,
                    phaseMs = mapOf("observe" to observeMs, "orient" to orientMs, "decide" to decideMs)
                )
            }

            // ── ACT ──
            val actStart = System.currentTimeMillis()
            val actResult = handler.act(decision)
            val actMs = System.currentTimeMillis() - actStart

            // Post-act orientation update
            if (actResult.success) {
                updateAxis("confidence", 1.0, weight = 0.1)
            } else {
                updateAxis("confidence", 0.0, weight = 0.15)
                updateAxis("risk_level", 0.8, weight = 0.2)
            }

            val totalMs = System.currentTimeMillis() - startTime
            updateAvgCycleTime(totalMs)
            if (actResult.success) successCount.incrementAndGet()

            Timber.d("OODA cycle %d completed: success=%b, total=%dms",
                cycleNum, actResult.success, totalMs)

            return OodaCycleResult(
                cycleId = cycleId,
                cycleNumber = cycleNum,
                success = actResult.success,
                escalated = false,
                confidence = decision.confidence,
                totalMs = totalMs,
                phaseMs = mapOf(
                    "observe" to observeMs, "orient" to orientMs,
                    "decide" to decideMs, "act" to actMs
                ),
                observations = observations,
                decision = decision,
                actResult = actResult
            )

        } catch (e: Throwable) {
            val totalMs = System.currentTimeMillis() - startTime
            Timber.e(e, "OODA cycle %d failed", cycleNum)
            return OodaCycleResult(
                cycleId = cycleId,
                cycleNumber = cycleNum,
                success = false,
                escalated = false,
                confidence = 0.0,
                totalMs = totalMs,
                error = e.message
            )
        }
    }

    // ═══════════════ ORIENTATION STATE ═══════════════

    /**
     * Get a snapshot of current orientation state.
     */
    fun getOrientationSnapshot(): Map<String, Double> = orientation.toMap()

    /**
     * Update an orientation axis with exponential moving average.
     *
     * @param axis The axis to update
     * @param value New observation value (-1.0 to 1.0)
     * @param weight How much to weight the new observation (0.0-1.0)
     */
    fun updateAxis(axis: String, value: Double, weight: Double = 0.3) {
        val old = orientation[axis] ?: 0.0
        val clamped = value.coerceIn(-1.0, 1.0)
        orientation[axis] = old * (1 - weight) + clamped * weight
    }

    /**
     * Apply a map of orientation updates.
     */
    private fun applyOrientationUpdate(updates: Map<String, Double>) {
        for ((axis, value) in updates) {
            if (orientation.containsKey(axis)) {
                updateAxis(axis, value)
            }
        }
    }

    /**
     * Record current orientation for drift analysis.
     */
    private fun recordDrift() {
        synchronized(driftHistory) {
            if (driftHistory.size >= MAX_DRIFT_HISTORY) {
                driftHistory.removeFirst()
            }
            driftHistory.addLast(orientation.toMap())
        }
    }

    /**
     * Get orientation drift (how much the mental model changed).
     */
    fun getOrientationDrift(): Map<String, Double> {
        synchronized(driftHistory) {
            if (driftHistory.size < 2) return emptyMap()
            val prev = driftHistory[driftHistory.size - 2]
            return orientation.mapValues { (axis, current) ->
                kotlin.math.abs(current - (prev[axis] ?: 0.0))
            }
        }
    }

    /**
     * Check if the environment is volatile (large recent drift).
     */
    fun isVolatile(threshold: Double = 0.5): Boolean {
        val drift = getOrientationDrift()
        return drift.values.any { it > threshold }
    }

    // ═══════════════ METRICS ═══════════════

    fun getMetrics(): OodaMetrics {
        val total = cycleCount.get()
        val successful = successCount.get()
        val drift = getOrientationDrift()
        val avgDrift = if (drift.isNotEmpty()) drift.values.average() else 0.0

        return OodaMetrics(
            totalCycles = total,
            successfulCycles = successful,
            failedCycles = total - successful,
            avgCycleMs = avgCycleMs.get().toDouble(),
            escalationCount = escalationCount.get(),
            orientationStability = (1.0 - minOf(1.0, avgDrift * 5)).coerceIn(0.0, 1.0),
            orientation = getOrientationSnapshot(),
            isVolatile = isVolatile()
        )
    }

    // ═══════════════ INTERNALS ═══════════════

    private fun matchHandler(event: AgentEvent): OodaHandler? {
        val eventName = event::class.simpleName ?: return null
        // Try exact match first, then pattern match
        return handlers[eventName]
            ?: handlers.entries.firstOrNull { (pattern, _) ->
                eventName.contains(pattern, ignoreCase = true)
            }?.value
    }

    private fun updateAvgCycleTime(cycleMs: Long) {
        val old = avgCycleMs.get()
        val n = cycleCount.get().coerceAtLeast(1)
        val newAvg = old + (cycleMs - old) / n
        avgCycleMs.set(newAvg)
    }

    companion object {
        private const val MAX_DRIFT_HISTORY = 100
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/**
 * Interface for domain-specific OODA handlers.
 * Register implementations for different event types.
 */
interface OodaHandler {
    /**
     * OBSERVE: Extract observations from the event.
     */
    suspend fun observe(event: AgentEvent, orientation: Map<String, Double>): Map<String, Any>

    /**
     * ORIENT: Compute orientation axis updates from observations.
     * Return map of axis → new value (will be EMA-blended).
     */
    suspend fun orient(observations: Map<String, Any>, orientation: Map<String, Double>): Map<String, Double>

    /**
     * DECIDE: Choose an action based on observations and orientation.
     */
    suspend fun decide(observations: Map<String, Any>, orientation: Map<String, Double>): OodaDecision

    /**
     * ACT: Execute the decision.
     */
    suspend fun act(decision: OodaDecision): OodaActResult
}

/**
 * A decision made during the OODA decide phase.
 */
data class OodaDecision(
    val action: String,
    val parameters: Map<String, Any> = emptyMap(),
    val confidence: Double,
    val reasoning: String = ""
)

/**
 * Result of executing an action in the OODA act phase.
 */
data class OodaActResult(
    val success: Boolean,
    val data: Map<String, Any>? = null,
    val error: String? = null
)

/**
 * Result of a complete OODA cycle.
 */
data class OodaCycleResult(
    val cycleId: String,
    val cycleNumber: Int,
    val success: Boolean,
    val escalated: Boolean,
    val confidence: Double,
    val totalMs: Long,
    val phaseMs: Map<String, Long> = emptyMap(),
    val observations: Map<String, Any>? = null,
    val decision: OodaDecision? = null,
    val actResult: OodaActResult? = null,
    val error: String? = null
)

/**
 * Aggregated OODA loop metrics.
 */
data class OodaMetrics(
    val totalCycles: Int,
    val successfulCycles: Int,
    val failedCycles: Int,
    val avgCycleMs: Double,
    val escalationCount: Int,
    val orientationStability: Double,
    val orientation: Map<String, Double>,
    val isVolatile: Boolean
) {
    val successRate: Double
        get() = if (totalCycles > 0) successfulCycles.toDouble() / totalCycles else 0.0
}
