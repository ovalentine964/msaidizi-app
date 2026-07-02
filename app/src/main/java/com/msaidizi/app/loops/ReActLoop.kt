package com.msaidizi.app.loops

import timber.log.Timber
import java.util.UUID

/**
 * ReAct Loop — Reasoning + Acting with explicit trace.
 *
 * Every agent decision now comes with a visible reasoning chain:
 * - What the agent observed
 * - What it reasoned about the observation
 * - What action it decided to take
 * - What result it got
 * - What it reflected on
 *
 * This makes debugging agent behavior trivial — just read the trace.
 *
 * ## Theoretical Foundation
 *
 * ### STA 443 — Measure and Probability Theory
 * - **Conditional Expectation (§1.2.5):** Each reasoning step computes
 *   E[Quality | Observation, Context] and selects the action that
 *   maximizes this conditional expectation.
 * - **Bayesian Updating:** Each observation updates the agent's beliefs:
 *   P(Intent | Data) ∝ P(Data | Intent) × P(Intent)
 *
 * ### ECO 103 — Mathematics for Economists
 * - **Sequences (§1.3):** The reasoning chain is a sequence of logical
 *   steps, each building on the previous result.
 * - **Optimization (§1.2):** The agent solves an optimization problem
 *   at each step: choose the action that maximizes expected utility.
 *
 * @see Orchestrator for the main agent that uses this loop
 */
data class ReasoningStep(
    val stepId: String = UUID.randomUUID().toString().take(10),
    val timestamp: Long = System.currentTimeMillis(),
    val phase: Phase,
    val reasoning: String,
    val action: String = "",
    val observation: String = "",
    val confidence: Double = 1.0,
    val metadata: Map<String, Any> = emptyMap()
) {
    enum class Phase {
        THINK,      // Analyzing the situation
        ACT,        // Taking action
        OBSERVE,    // Seeing the result
        REFLECT     // Learning from the outcome
    }

    fun toMap(): Map<String, Any> = mapOf(
        "stepId" to stepId,
        "timestamp" to timestamp,
        "phase" to phase.name,
        "reasoning" to reasoning,
        "action" to action,
        "observation" to observation,
        "confidence" to confidence,
        "metadata" to metadata
    )
}

/**
 * Full trace of a ReAct loop execution.
 *
 * Captures the complete reasoning chain for a single request,
 * from initial observation to final reflection.
 */
data class ReActTrace(
    val traceId: String = UUID.randomUUID().toString().take(16),
    val task: String,
    val steps: MutableList<ReasoningStep> = mutableListOf(),
    val startedAt: Long = System.currentTimeMillis(),
    var endedAt: Long? = null,
    var success: Boolean = false,
    var finalResult: String? = null
) {
    fun addStep(step: ReasoningStep) {
        steps.add(step)
    }

    fun getReasoningChain(): List<String> =
        steps.filter { it.reasoning.isNotBlank() }.map { it.reasoning }

    fun complete(success: Boolean, result: String? = null) {
        this.endedAt = System.currentTimeMillis()
        this.success = success
        this.finalResult = result
    }

    fun durationMs(): Long = (endedAt ?: System.currentTimeMillis()) - startedAt

    fun toMap(): Map<String, Any> = mapOf(
        "traceId" to traceId,
        "task" to task,
        "steps" to steps.map { it.toMap() },
        "startedAt" to startedAt,
        "endedAt" to (endedAt ?: System.currentTimeMillis()),
        "success" to success,
        "durationMs" to durationMs(),
        "stepCount" to steps.size,
        "reasoningChain" to getReasoningChain()
    )
}

/**
 * ReAct Loop Manager — wraps agent execution with explicit reasoning traces.
 *
 * Usage:
 * ```
 * val trace = reactLoop.startTrace("process_sale")
 * reactLoop.think(trace, "Observing sale of mandazi x10 for KSh 500")
 * val result = reactLoop.act(trace, "record_sale") { doSale() }
 * reactLoop.reflect(trace, result)
 * reactLoop.complete(trace, success = true)
 * ```
 *
 * The trace is stored for debugging and can be queried via the API.
 */
class ReActLoop(
    private val maxTraceHistory: Int = 100
) {
    private val traceHistory = mutableListOf<ReActTrace>()
    private var currentTrace: ReActTrace? = null

    /**
     * Start a new reasoning trace for a task.
     */
    fun startTrace(task: String): ReActTrace {
        val trace = ReActTrace(task = task)
        currentTrace = trace
        Timber.d("ReAct trace started: %s", task)
        return trace
    }

    /**
     * Record a thinking step — what the agent is reasoning about.
     */
    fun think(
        trace: ReActTrace,
        reasoning: String,
        confidence: Double = 1.0,
        metadata: Map<String, Any> = emptyMap()
    ): ReasoningStep {
        val step = ReasoningStep(
            phase = ReasoningStep.Phase.THINK,
            reasoning = reasoning,
            confidence = confidence,
            metadata = metadata
        )
        trace.addStep(step)
        Timber.d("ReAct think: %s", reasoning.take(100))
        return step
    }

    /**
     * Record an action step — what the agent decided to do.
     */
    fun act(
        trace: ReActTrace,
        action: String,
        reasoning: String = ""
    ): ReasoningStep {
        val step = ReasoningStep(
            phase = ReasoningStep.Phase.ACT,
            reasoning = reasoning.ifBlank { "Executing: $action" },
            action = action
        )
        trace.addStep(step)
        Timber.d("ReAct act: %s", action)
        return step
    }

    /**
     * Record an observation step — what the agent saw after acting.
     */
    fun observe(
        trace: ReActTrace,
        observation: String,
        confidence: Double = 1.0
    ): ReasoningStep {
        val step = ReasoningStep(
            phase = ReasoningStep.Phase.OBSERVE,
            reasoning = "Observed: $observation",
            observation = observation,
            confidence = confidence
        )
        trace.addStep(step)
        Timber.d("ReAct observe: %s", observation.take(100))
        return step
    }

    /**
     * Record a reflection step — what the agent learned.
     */
    fun reflect(
        trace: ReActTrace,
        reflection: String,
        confidence: Double = 1.0
    ): ReasoningStep {
        val step = ReasoningStep(
            phase = ReasoningStep.Phase.REFLECT,
            reasoning = reflection,
            confidence = confidence
        )
        trace.addStep(step)
        Timber.d("ReAct reflect: %s", reflection.take(100))
        return step
    }

    /**
     * Complete a trace.
     */
    fun complete(trace: ReActTrace, success: Boolean, result: String? = null) {
        trace.complete(success, result)
        traceHistory.add(trace)
        if (traceHistory.size > maxTraceHistory) {
            traceHistory.removeAt(0)
        }
        currentTrace = null
        Timber.d("ReAct trace completed: success=%b, steps=%d", success, trace.steps.size)
    }

    /**
     * Get recent traces for debugging.
     */
    fun getRecentTraces(n: Int = 10): List<Map<String, Any>> =
        traceHistory.takeLast(n).map { it.toMap() }

    /**
     * Get successful reasoning chains for few-shot learning.
     */
    fun getReasoningExamples(n: Int = 5): List<Map<String, Any>> =
        traceHistory.filter { it.success }.takeLast(n).map {
            mapOf(
                "task" to it.task,
                "reasoningChain" to it.getReasoningChain(),
                "steps" to it.steps.size
            )
        }

    /**
     * Get the current active trace (if any).
     */
    fun getCurrentTrace(): ReActTrace? = currentTrace

    /**
     * Get trace history count.
     */
    fun getTraceCount(): Int = traceHistory.size
}
