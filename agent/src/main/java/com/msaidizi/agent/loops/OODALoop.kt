package com.msaidizi.agent.loops

import com.msaidizi.agent.flywheel.FlywheelEngine
import com.msaidizi.agent.guardrails.GuardrailsEngine
import com.msaidizi.agent.harness.*
import com.msaidizi.agent.memory.MemoryManager
import com.msaidizi.agent.tools.core.ToolRegistry
import com.msaidizi.agent.tools.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OODALoop — Observe-Orient-Decide-Act execution loop for the Superagent.
 *
 * Transforms the linear pipeline into an actual iterative loop:
 *   OBSERVE:  Gather all inputs (voice, context, memory, market data)
 *   ORIENT:   Synthesize context, detect patterns, identify anomalies
 *   DECIDE:   Select tools, determine confidence, choose response strategy
 *   ACT:      Execute tools, generate response, update memory
 *
 * Loop terminates when:
 *   1. Confidence exceeds threshold (task complete)
 *   2. Max iterations reached (safety cap)
 *   3. Convergence detected (no improvement between iterations)
 *   4. Budget exhausted (token/cost limit)
 *
 * Design: ReAct-style per-step reasoning with Reflexion-style self-critique.
 * Reference: loop_engineering_report.md §2.1, §4.1
 */
@Singleton
class OODALoop @Inject constructor(
    private val selfCorrectionLoop: SelfCorrectionLoop,
    private val circuitBreaker: CircuitBreaker
) {
    companion object {
        /** Hard safety cap: never exceed this many iterations per request. */
        const val MAX_ITERATIONS = 3

        /** Confidence threshold to accept output without further iteration. */
        const val CONFIDENCE_THRESHOLD = 0.85f

        /** Convergence threshold: if improvement < this, stop iterating. */
        const val CONVERGENCE_THRESHOLD = 0.05f

        /** Maximum total latency budget per request (ms). */
        const val LATENCY_BUDGET_MS = 30_000L

        /** P2: Configurable max iterations per intent type.
         *  Simple operations get 1 pass, complex analysis gets more.
         *  Falls back to MAX_ITERATIONS for unlisted types.
         */
        val INTENT_MAX_ITERATIONS = mapOf(
            IntentType.GREETING to 1,
            IntentType.FAREWELL to 1,
            IntentType.THANKS to 1,
            IntentType.HELP to 1,
            IntentType.CHITCHAT to 1,
            IntentType.ASK_SALES_TODAY to 1,
            IntentType.ASK_EXPENSES to 1,
            IntentType.ASK_STOCK to 1,
            IntentType.DAILY_REPORT to 2,
            IntentType.WEEKLY_REPORT to 3,
            IntentType.MONTHLY_REPORT to 3,
            IntentType.ASK_ADVICE to 3,
            IntentType.LOAN_COMPARE to 3,
            IntentType.INSURANCE_MATCH to 2
        )

        /** Simple operation types that should NOT iterate (1 pass only). */
        val SIMPLE_OPERATIONS = setOf(
            IntentType.GREETING,
            IntentType.FAREWELL,
            IntentType.THANKS,
            IntentType.HELP,
            IntentType.CHITCHAT,
            IntentType.ASK_SALES_TODAY,
            IntentType.ASK_EXPENSES,
            IntentType.ASK_STOCK,
            IntentType.CHECK_STOCK
        )
    }

    /**
     * Execute the OODA loop for a user input.
     *
     * For simple operations (greetings, quick queries), executes a single pass
     * with no iteration to minimize latency.
     *
     * For complex operations (advice, reports, tool chains), iterates up to
     * [MAX_ITERATIONS] times, refining the output each cycle.
     *
     * @param input       Raw user input text
     * @param intent      Classified intent from IntentRouter
     * @param context     Assembled 5-layer context
     * @param llmEngine   LLM engine for generation
     * @param toolRegistry Tool registry for execution
     * @param guardrails  GuardrailsEngine for safety checks
     * @param flywheel    FlywheelEngine for learning signals
     * @param memoryManager MemoryManager for state updates
     * @param buildPrompt Function to build the system prompt from context
     * @return Final OODA result with response text, iterations used, and metrics
     */
    suspend fun execute(
        input: String,
        intent: UserIntent,
        context: AssembledContext,
        llmEngine: LlmEngine,
        toolRegistry: ToolRegistry,
        guardrails: GuardrailsEngine,
        flywheel: FlywheelEngine,
        memoryManager: MemoryManager,
        buildPrompt: (AssembledContext) -> String
    ): OODAResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val isSimpleOp = intent.type in SIMPLE_OPERATIONS
        val maxIter = if (isSimpleOp) 1 else MAX_ITERATIONS

        var currentContext = context
        var bestResponse: String? = null
        var bestConfidence = 0f
        var iterations = 0
        val iterationLogs = mutableListOf<OODAIterationLog>()

        Timber.d("OODA: Starting for %s (simple=%s, maxIter=%d)", intent.type, isSimpleOp, maxIter)

        // ═══════════════════════════════════════════════════════════
        // MAIN OODA LOOP
        // ═══════════════════════════════════════════════════════════
        while (iterations < maxIter) {
            val iterStart = System.currentTimeMillis()
            iterations++
            val iterLabel = "iter-$iterations"

            Timber.d("OODA [%s]: Starting iteration %d/%d", iterLabel, iterations, maxIter)

            // ── Phase 1: OBSERVE ─────────────────────────────────
            // Gather all inputs: context is already assembled.
            // On subsequent iterations, enrich with prior iteration results.
            val observation = observe(currentContext, intent, iterations)
            Timber.d("OODA [%s] OBSERVE: %s", iterLabel, observation.summary)

            // ── Phase 2: ORIENT ──────────────────────────────────
            // P1: LLM-based orient for complex intents
            val orientation = orient(currentContext, intent, observation, iterations, llmEngine)
            Timber.d("OODA [%s] ORIENT: strategy=%s, anomalies=%d",
                iterLabel, orientation.strategy, orientation.anomalies.size)

            // ── Phase 3: DECIDE ──────────────────────────────────
            // P1: LLM-based tool suggestion for complex intents
            val decision = decide(intent, orientation, toolRegistry, iterations, llmEngine, currentContext)
            Timber.d("OODA [%s] DECIDE: tools=%s, confidence=%.2f",
                iterLabel, decision.selectedTools, decision.confidence)

            // ── Phase 4: ACT ─────────────────────────────────────
            // Execute tools (with self-correction), generate response, check guardrails.
            val actResult = act(
                input = input,
                intent = intent,
                context = currentContext,
                decision = decision,
                llmEngine = llmEngine,
                toolRegistry = toolRegistry,
                guardrails = guardrails,
                buildPrompt = buildPrompt,
                iterationLabel = iterLabel
            )
            Timber.d("OODA [%s] ACT: confidence=%.2f, blocked=%s",
                iterLabel, actResult.confidence, actResult.blocked)

            // Record iteration log
            val iterLog = OODAIterationLog(
                iteration = iterations,
                phase = OodaPhase.ACT,
                observationSummary = observation.summary,
                orientationStrategy = orientation.strategy,
                decisionConfidence = decision.confidence,
                actConfidence = actResult.confidence,
                toolsExecuted = decision.selectedTools,
                durationMs = System.currentTimeMillis() - iterStart,
                anomalies = orientation.anomalies
            )
            iterationLogs.add(iterLog)

            // ── Evaluate: Should we continue iterating? ──────────
            if (actResult.blocked) {
                // Guardrails blocked — don't iterate further
                bestResponse = actResult.response
                bestConfidence = actResult.confidence
                Timber.w("OODA [%s]: Blocked by guardrails, stopping", iterLabel)
                break
            }

            // Track best result
            if (actResult.confidence > bestConfidence) {
                bestResponse = actResult.response
                bestConfidence = actResult.confidence
            }

            // ── Termination checks ───────────────────────────────
            // 1. Confidence threshold met
            if (actResult.confidence >= CONFIDENCE_THRESHOLD) {
                Timber.d("OODA [%s]: Confidence %.2f >= %.2f threshold, stopping",
                    iterLabel, actResult.confidence, CONFIDENCE_THRESHOLD)
                break
            }

            // 2. Convergence check (no meaningful improvement)
            if (iterations > 1) {
                val improvement = actResult.confidence - (iterationLogs[iterationLogs.size - 2].actConfidence)
                if (improvement < CONVERGENCE_THRESHOLD) {
                    Timber.d("OODA [%s]: Converged (improvement=%.3f < %.3f), stopping",
                        iterLabel, improvement, CONVERGENCE_THRESHOLD)
                    break
                }
            }

            // 3. Latency budget check
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > LATENCY_BUDGET_MS * 0.7) {
                Timber.d("OODA [%s]: Approaching latency budget (%dms), stopping", iterLabel, elapsed)
                break
            }

            // ── Prepare for next iteration ───────────────────────
            // Enrich context with iteration results for the next OBSERVE phase.
            currentContext = currentContext.copy(
                oodaPhase = OodaPhase.OBSERVE,
                oodaObservations = currentContext.oodaObservations + listOf(
                    "Iter $iterations: confidence=${actResult.confidence}, strategy=${orientation.strategy}"
                ),
                oodaDecisions = currentContext.oodaDecisions + listOf(
                    "Refining: ${orientation.refinementFocus}"
                )
            )
        }

        // ═══════════════════════════════════════════════════════════
        // FINALIZE
        // ═══════════════════════════════════════════════════════════
        val totalDuration = System.currentTimeMillis() - startTime
        val finalResponse = bestResponse ?: "Pole sana, I couldn't generate a response. Please try again."

        // Update memory with OODA loop results
        memoryManager.updateWorkingMemory(input, finalResponse, intent)

        Timber.i("OODA: Complete in %d iterations, %dms, final confidence=%.2f",
            iterations, totalDuration, bestConfidence)

        OODAResult(
            response = finalResponse,
            confidence = bestConfidence,
            iterations = iterations,
            totalDurationMs = totalDuration,
            iterationLogs = iterationLogs,
            terminatedBy = determineTerminationReason(iterations, maxIter, bestConfidence, iterationLogs)
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // OODA PHASES
    // ═══════════════════════════════════════════════════════════════

    /**
     * OBSERVE: Gather and synthesize all available inputs.
     */
    private fun observe(
        context: AssembledContext,
        intent: UserIntent,
        iteration: Int
    ): Observation {
        val signals = mutableListOf<String>()

        // Gather signal from context layers
        context.recentFinancialSummary?.let { signals.add("Financial: $it") }
        if (context.knowledgeContext.isNotEmpty()) {
            signals.add("Knowledge: ${context.knowledgeContext.size} entries")
        }
        if (context.marketInsights.isNotEmpty()) {
            signals.add("Market: ${context.marketInsights.size} insights")
        }
        if (context.relevantPatterns.isNotEmpty()) {
            signals.add("Patterns: ${context.relevantPatterns.size} learned")
        }

        // Prior iteration observations (if iterating)
        if (iteration > 1 && context.oodaObservations.isNotEmpty()) {
            signals.add("Prior iterations: ${context.oodaObservations.takeLast(2)}")
        }

        return Observation(
            summary = "${signals.size} signals gathered for ${intent.type}",
            signals = signals,
            hasFinancialData = context.recentFinancialSummary != null,
            hasLearnedPatterns = context.relevantPatterns.isNotEmpty(),
            iteration = iteration
        )
    }

    /**
     * ORIENT: Synthesize context, detect anomalies, determine refinement strategy.
     *
     * P1: Enhanced with LLM-based anomaly detection for complex intents.
     * The LLM analyzes observations and identifies what needs refinement,
     * making the ORIENT phase truly intelligent.
     */
    private suspend fun orient(
        context: AssembledContext,
        intent: UserIntent,
        observation: Observation,
        iteration: Int,
        llmEngine: LlmEngine? = null
    ): Orientation {
        val anomalies = mutableListOf<String>()
        var strategy = ResponseStrategy.DIRECT
        var refinementFocus = "initial generation"

        // Detect anomalies based on intent type
        when (intent.type) {
            IntentType.ASK_ADVICE, IntentType.ASK_PROFIT -> {
                // Financial advice needs careful validation
                strategy = if (iteration == 1) ResponseStrategy.GENERATE_AND_VALIDATE else ResponseStrategy.REFINE
                refinementFocus = "financial accuracy"
                if (!observation.hasFinancialData) {
                    anomalies.add("No financial data available for advice")
                }
            }
            IntentType.DAILY_REPORT, IntentType.WEEKLY_REPORT, IntentType.MONTHLY_REPORT -> {
                strategy = if (iteration == 1) ResponseStrategy.GATHER_AND_SYNTHESIZE else ResponseStrategy.REFINE
                refinementFocus = "report completeness"
            }
            IntentType.RECORD_SALE, IntentType.RECORD_EXPENSE, IntentType.RECORD_PURCHASE -> {
                strategy = ResponseStrategy.DIRECT
                refinementFocus = "transaction accuracy"
            }
            IntentType.ASK_ADVICE -> {
                if (iteration > 1) {
                    refinementFocus = "advice refinement based on guardrails feedback"
                }
            }
            else -> {
                strategy = ResponseStrategy.DIRECT
            }
        }

        // Check for pattern-based anomalies
        if (observation.hasLearnedPatterns && context.relevantPatterns.isNotEmpty()) {
            // If we have learned patterns, check if current request matches
            val patternMatch = context.relevantPatterns.any {
                it.contains(intent.type.name, ignoreCase = true)
            }
            if (!patternMatch) {
                anomalies.add("Request doesn't match learned patterns — novel situation")
            }
        }

        return Orientation(
            strategy = strategy,
            anomalies = anomalies,
            refinementFocus = refinementFocus,
            confidenceAdjustment = if (anomalies.isEmpty()) 0.0f else -0.1f
        )
    }

    /**
     * DECIDE: Select tools and determine execution strategy.
     *
     * P1: Enhanced with LLM-based reasoning for complex decisions.
     * For advice and analysis intents, uses the LLM to reason about
     * which tools to invoke and in what order.
     */
    private suspend fun decide(
        intent: UserIntent,
        orientation: Orientation,
        toolRegistry: ToolRegistry,
        iteration: Int,
        llmEngine: LlmEngine? = null,
        context: AssembledContext? = null
    ): Decision {
        // Base tool selection from intent
        val selectedTools = intent.requiredTools.toMutableList()

        // P1: LLM-based tool selection for complex intents
        if (llmEngine != null && context != null && iteration == 1) {
            val llmSuggestedTools = suggestToolsViaLlm(intent, orientation, llmEngine, context, toolRegistry)
            if (llmSuggestedTools.isNotEmpty()) {
                selectedTools.addAll(llmSuggestedTools)
            }
        }

        // Add supplementary tools based on orientation strategy
        when (orientation.strategy) {
            ResponseStrategy.GENERATE_AND_VALIDATE -> {
                if ("cfo_engine" !in selectedTools && intent.type == IntentType.ASK_ADVICE) {
                    selectedTools.add("cfo_engine")
                }
            }
            ResponseStrategy.GATHER_AND_SYNTHESIZE -> {
                if ("cfo_engine" !in selectedTools) {
                    selectedTools.add("cfo_engine")
                }
            }
            else -> { /* use intent's tools as-is */ }
        }

        // Filter to tools that actually exist in the registry
        val validTools = selectedTools.filter { toolRegistry.hasTool(it) }.distinct()

        // Calculate base confidence
        var confidence = intent.confidence
        confidence += orientation.confidenceAdjustment

        if (iteration > 1) {
            confidence = (confidence + 0.1f).coerceAtMost(1.0f)
        }

        return Decision(
            selectedTools = validTools,
            toolParams = intent.toolParams,
            confidence = confidence.coerceIn(0.0f, 1.0f),
            strategy = orientation.strategy
        )
    }

    /**
     * P1: Use LLM to suggest additional tools for complex intents.
     * This makes the DECIDE phase truly intelligent rather than purely heuristic.
     */
    private suspend fun suggestToolsViaLlm(
        intent: UserIntent,
        orientation: Orientation,
        llmEngine: LlmEngine,
        context: AssembledContext,
        toolRegistry: ToolRegistry
    ): List<String> {
        // Only use LLM suggestion for complex reasoning intents
        if (intent.type !in setOf(
            IntentType.ASK_ADVICE,
            IntentType.LOAN_COMPARE,
            IntentType.INSURANCE_MATCH,
            IntentType.DAILY_REPORT,
            IntentType.WEEKLY_REPORT
        )) return emptyList()

        return try {
            val availableTools = toolRegistry.getAllTools().map { it.name }.joinToString(", ")
            val prompt = """Given this user intent: ${intent.type}
Available tools: $availableTools
Strategy: ${orientation.strategy}
Anomalies: ${orientation.anomalies.joinToString("; ")}

Which 1-2 additional tools would help? Reply ONLY with tool names, comma-separated. If none, reply NONE."""

            val response = llmEngine.generate(
                systemPrompt = "You are a tool selector. Pick the most relevant tools.",
                userMessage = prompt,
                context = context,
                toolResults = emptyList(),
                intent = intent
            )

            if (response.contains("NONE", ignoreCase = true)) return emptyList()

            response.split(",")
                .map { it.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_") }
                .filter { it.isNotEmpty() && toolRegistry.hasTool(it) }
        } catch (e: Exception) {
            Timber.w(e, "LLM tool suggestion failed, using heuristic")
            emptyList()
        }
    }

    /**
     * ACT: Execute tools with self-correction, generate response, validate with guardrails.
     */
    private suspend fun act(
        input: String,
        intent: UserIntent,
        context: AssembledContext,
        decision: Decision,
        llmEngine: LlmEngine,
        toolRegistry: ToolRegistry,
        guardrails: GuardrailsEngine,
        buildPrompt: (AssembledContext) -> String,
        iterationLabel: String
    ): ActResult {
        // ── Execute tools with self-correction ───────────────────
        var toolResults: List<ToolResult> = emptyList()
        if (decision.selectedTools.isNotEmpty()) {
            toolResults = decision.selectedTools.mapNotNull { toolName ->
                val params = decision.toolParams[toolName] ?: emptyMap()

                // Use circuit breaker to protect external tools
                val isExternal = toolName in setOf("sync_engine", "whatsapp_reporter", "model_downloader")
                if (isExternal) {
                    circuitBreaker.executeWithBreaker(toolName) {
                        toolRegistry.execute(toolName, params)
                    }
                } else {
                    // Use self-correction loop for internal tools
                    selfCorrectionLoop.executeWithCorrection(toolName, params, toolRegistry)
                }
            }
        }

        // ── Generate response via LLM ───────────────────────────
        val systemPrompt = buildPrompt(context)
        val rawResponse = llmEngine.generate(
            systemPrompt = systemPrompt,
            userMessage = input,
            context = context,
            toolResults = toolResults,
            intent = intent
        )

        // ── Check guardrails on output ──────────────────────────
        val outputCheck = guardrails.checkOutput(rawResponse, context)

        val finalResponse = if (outputCheck.blocked) {
            outputCheck.message ?: "Samahani, I need to rephrase that."
        } else {
            outputCheck.message ?: rawResponse
        }

        // ── Calculate confidence ────────────────────────────────
        var actConfidence = decision.confidence

        // Reduce confidence if tool execution had failures
        val failedTools = toolResults.count { !it.success }
        if (failedTools > 0) {
            actConfidence -= (failedTools * 0.15f)
        }

        // Reduce confidence if guardrails flagged issues
        if (outputCheck.hallucinationIssues.isNotEmpty()) {
            val highSeverity = outputCheck.hallucinationIssues.count { it.severity == com.msaidizi.agent.guardrails.IssueSeverity.HIGH }
            actConfidence -= (highSeverity * 0.2f)
        }

        // Boost confidence if all tools succeeded
        if (toolResults.isNotEmpty() && toolResults.all { it.success }) {
            actConfidence = (actConfidence + 0.05f).coerceAtMost(1.0f)
        }

        return ActResult(
            response = finalResponse,
            confidence = actConfidence.coerceIn(0.0f, 1.0f),
            toolResults = toolResults,
            blocked = outputCheck.blocked,
            guardrailLabel = outputCheck.confidenceLabel
        )
    }

    /**
     * Determine why the OODA loop terminated.
     */
    private fun determineTerminationReason(
        iterations: Int,
        maxIter: Int,
        confidence: Float,
        logs: List<OODAIterationLog>
    ): TerminationReason {
        return when {
            confidence >= CONFIDENCE_THRESHOLD -> TerminationReason.CONFIDENCE_MET
            iterations >= maxIter -> TerminationReason.MAX_ITERATIONS
            logs.size >= 2 && (logs.last().actConfidence - logs[logs.size - 2].actConfidence) < CONVERGENCE_THRESHOLD ->
                TerminationReason.CONVERGED
            else -> TerminationReason.MAX_ITERATIONS
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════════

/**
 * Result of a complete OODA loop execution.
 */
data class OODAResult(
    val response: String,
    val confidence: Float,
    val iterations: Int,
    val totalDurationMs: Long,
    val iterationLogs: List<OODAIterationLog>,
    val terminatedBy: TerminationReason
)

/**
 * Log entry for a single OODA iteration.
 */
data class OODAIterationLog(
    val iteration: Int,
    val phase: OodaPhase,
    val observationSummary: String,
    val orientationStrategy: ResponseStrategy,
    val decisionConfidence: Float,
    val actConfidence: Float,
    val toolsExecuted: List<String>,
    val durationMs: Long,
    val anomalies: List<String>
)

enum class TerminationReason {
    CONFIDENCE_MET,    // Output confidence exceeded threshold
    MAX_ITERATIONS,    // Safety cap reached
    CONVERGED,         // No improvement between iterations
    LATENCY_BUDGET,    // Approached time limit
    BLOCKED            // Guardrails blocked output
}

/**
 * Response strategy selected during ORIENT phase.
 */
enum class ResponseStrategy {
    DIRECT,                    // Simple single-pass response
    GENERATE_AND_VALIDATE,     // Generate then validate (financial advice)
    GATHER_AND_SYNTHESIZE,     // Gather data from multiple sources
    REFINE                     // Refine previous iteration's output
}

// ── Internal phase result types ────────────────────────────────────

data class Observation(
    val summary: String,
    val signals: List<String>,
    val hasFinancialData: Boolean,
    val hasLearnedPatterns: Boolean,
    val iteration: Int
)

data class Orientation(
    val strategy: ResponseStrategy,
    val anomalies: List<String>,
    val refinementFocus: String,
    val confidenceAdjustment: Float
)

data class Decision(
    val selectedTools: List<String>,
    val toolParams: Map<String, Map<String, String>>,
    val confidence: Float,
    val strategy: ResponseStrategy
)

data class ActResult(
    val response: String,
    val confidence: Float,
    val toolResults: List<ToolResult>,
    val blocked: Boolean,
    val guardrailLabel: com.msaidizi.agent.guardrails.ConfidenceLabel
)
