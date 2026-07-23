package com.msaidizi.app.agent

import com.msaidizi.app.agent.bootstrap.BootstrapResponse
import com.msaidizi.app.agent.bootstrap.BootstrapState
import com.msaidizi.app.agent.bootstrap.BootstrapStateMachine
import com.msaidizi.app.agent.bootstrap.BootstrapStep
import com.msaidizi.app.agent.bootstrap.SoulPrompt
import com.msaidizi.app.agent.bootstrap.WorkerProfileBuilder
import com.msaidizi.app.agent.tools.Tool
import com.msaidizi.app.agent.tools.ToolRegistry
import com.msaidizi.app.agent.tools.ToolResult
import com.msaidizi.app.core.metrics.PhaseMetrics
import com.msaidizi.app.memory.AgentContext
import com.msaidizi.app.memory.UnifiedMemoryBridge
import com.msaidizi.app.security.SafetyChecker
import com.msaidizi.app.voice.TranscriptionResult
import com.msaidizi.app.voice.VoicePipeline
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SuperAgent — The unified cognitive loop for Msaidizi.
 * 
 * ONE intelligence. ONE model. MANY tools. NOT 33 agents.
 * 
 * Cognitive Loop: INPUT → PERCEIVE → REMEMBER → REASON → [REFLECT] → ACT → LEARN → OUTPUT
 * 
 * Based on Jensen Huang's super agent vision:
 * - The harness makes the model deliver frontier capabilities
 * - Tools are the hands, the model is the brain
 * - Every interaction makes the agent smarter (flywheel)
 * 
 * Architecture: arch_android.md, arch_chief.md, synthesize_all.md
 */
@Singleton
class SuperAgent @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val memoryBridge: UnifiedMemoryBridge,
    private val safetyChecker: SafetyChecker,
    private val metrics: PhaseMetrics,
    private val llmEngine: LlmEngine,
    private val intentRouter: IntentRouter,
    private val voicePipeline: VoicePipeline,
    private val workerIdProvider: com.msaidizi.app.security.WorkerIdProvider
) {
    // ── Bootstrap State ─────────────────────────────────────────
    @Volatile
    private var bootstrapMachine: BootstrapStateMachine? = null

    /**
     * Check if bootstrap is currently active.
     * Used by UnifiedMemoryBridge to alter context enrichment.
     */
    fun isBootstrapActive(): Boolean {
        val machine = bootstrapMachine
        return machine != null && machine.isActive
    }

    /**
     * Get current bootstrap step (null if not in bootstrap).
     */
    fun getBootstrapStep(): BootstrapStep? = bootstrapMachine?.currentStep

    /**
     * Start bootstrap for a new worker.
     * Returns the introduction message.
     */
    fun startBootstrap(): BootstrapResponse {
        val machine = BootstrapStateMachine(workerIdProvider.getWorkerId())
        bootstrapMachine = machine
        Timber.i("Bootstrap started for worker: ${machine.profileBuilder.name}")
        return machine.start()
    }

    /**
     * Restore bootstrap from persisted state (app restart recovery).
     */
    fun restoreBootstrap(persistedState: Map<String, Any?>) {
        bootstrapMachine = BootstrapStateMachine.fromPersistableMap(persistedState)
        Timber.i("Bootstrap restored at step: ${bootstrapMachine?.currentStep}")
    }

    /**
     * Finalize bootstrap — initialize all memory layers.
     * Called when bootstrap reaches COMPLETE state.
     */
    private suspend fun finalizeBootstrap(machine: BootstrapStateMachine) {
        val profile = machine.profileBuilder
        Timber.i("Bootstrap complete. Initializing memory layers for: ${profile.name}")

        // L3: Initialize behavioral model with bootstrap data
        memoryBridge.initializeFromBootstrap(profile)

        // L1: Set initial context from bootstrap conversation
        memoryBridge.setBootstrapContext(machine.toPersistableMap())

        // L2: Store bootstrap as first episode
        memoryBridge.storeBootstrapEpisode(
            workerId = workerIdProvider.getWorkerId(),
            bootstrapData = machine.toPersistableMap()
        )

        bootstrapMachine = null
        Timber.i("Bootstrap finalized. Agent ready for normal operation.")
    }
    /**
     * Process a single user input through the cognitive loop.
     * This is the ONLY entry point for all interactions.
     *
     * During bootstrap, routes through the bootstrap state machine
     * instead of normal tool-based processing.
     */
    suspend fun processInput(input: AgentInput): AgentOutput {
        val startTime = System.currentTimeMillis()
        val sessionId = input.sessionId

        // ═══ BOOTSTRAP INTERCEPT ═══
        // If bootstrap is active, route through bootstrap state machine
        val machine = bootstrapMachine
        if (machine != null && machine.isActive) {
            return handleBootstrapInput(input, machine, sessionId, startTime)
        }

        return try {
            // ═══ PHASE 1: PERCEIVE ═══
            val perceiveStart = metrics.startPhase("perceive")
            val rawText = if (input.audioData != null) {
                val transcription = voicePipeline.transcribe(input.audioData)
                if (!transcription.success || transcription.text.isBlank()) {
                    return AgentOutput(
                        text = if (input.language == "sw") "Sikuskia vizuri. Sema tena taratibu."
                        else "I didn't hear clearly. Please say again slowly.",
                        confidence = 0.0,
                        toolsUsed = emptyList()
                    )
                }
                transcription.text
            } else {
                input.text
            }

            // Sanitize input
            val sanitizedText = safetyChecker.sanitizeInput(rawText)
            if (sanitizedText == null) {
                return AgentOutput(
                    text = if (input.language == "sw") "Samahani, sijaelewa. Sema tena."
                    else "Sorry, I didn't understand. Please try again.",
                    confidence = 0.0,
                    toolsUsed = emptyList()
                )
            }

            // Classify intent
            val intent = intentRouter.classify(sanitizedText)
            val entities = intentRouter.extractEntities(sanitizedText)
            metrics.endPhase("perceive", perceiveStart)

            // ═══ PHASE 2: REMEMBER ═══
            val rememberStart = metrics.startPhase("remember")
            val context = memoryBridge.enrichContext(sanitizedText, intent, sessionId)
            metrics.endPhase("remember", rememberStart)

            // ═══ PHASE 3: REASON ═══
            val reasonStart = metrics.startPhase("reason")
            val decision = if (intent.confidence > 0.6) {
                // High confidence → direct tool execution
                val tool = toolRegistry.findToolByIntent(intent.intent)
                if (tool != null) {
                    Decision.ToolDecision(tool, entities + mapOf("workerId" to workerIdProvider.getWorkerId(), "language" to input.language))
                } else {
                    // Unknown intent → LLM escalation
                    val llmResponse = llmEngine.generateResponse(
                        userInput = sanitizedText,
                        context = context.l2Episodes,
                        language = input.language
                    )
                    Decision.LlmDecision(llmResponse)
                }
            } else if (isMultiStepIntent(sanitizedText)) {
                // Multi-step intent → plan
                val plan = planSteps(sanitizedText, intent, context)
                Decision.PlannedDecision(plan)
            } else {
                // Low confidence → LLM escalation
                val llmResponse = llmEngine.generateResponse(
                    userInput = sanitizedText,
                    context = context.l2Episodes,
                    language = input.language
                )
                Decision.LlmDecision(llmResponse)
            }
            metrics.endPhase("reason", reasonStart)

            // ═══ PHASE 4: REFLECT (optional) ═══
            val finalDecision = if (shouldReflect(decision)) {
                val reflectStart = metrics.startPhase("reflect")
                val result = reflect(decision, context, input.language)
                metrics.endPhase("reflect", reflectStart)
                result
            } else {
                decision
            }

            // ═══ PHASE 5: ACT ═══
            val actStart = metrics.startPhase("act")
            val result = act(finalDecision, input.language)
            metrics.endPhase("act", actStart)

            // ═══ PHASE 6: LEARN ═══
            val learnStart = metrics.startPhase("learn")
            memoryBridge.storeEpisode(sanitizedText, intent, result, sessionId)
            memoryBridge.updateBehavioralModel(sanitizedText, result, context)

            // ═══ L1→L3 CORRECTION SIGNAL ═══
            // When the worker corrects the agent, propagate to L3 Bayesian model.
            // This is the missing signal path identified by the architecture validator.
            // Without it, corrections don't update behavioral beliefs.
            if (result.data["correction_applied"] == "true") {
                val correctionType = result.data["correction_type"]?.toString() ?: "other"
                val oldVal = result.data["oldAmount"]?.toString() ?: result.data["oldItem"]?.toString()
                val newVal = result.data["newAmount"]?.toString() ?: result.data["newItem"]?.toString()
                memoryBridge.notifyCorrection(
                    correctionType = correctionType,
                    originalInput = sanitizedText,
                    oldVal = oldVal,
                    newVal = newVal,
                    sessionId = sessionId
                )
                Timber.i("L1→L3: Correction signal propagated — type=%s", correctionType)
            }

            metrics.endPhase("learn", learnStart)

            // ═══ PHASE 7: OUTPUT ═══
            val safeText = safetyChecker.checkOutput(result.text, input.language)

            metrics.recordSuccess(System.currentTimeMillis() - startTime)

            AgentOutput(
                text = safeText,
                confidence = if (result.success) intent.confidence else 0.3,
                toolsUsed = result.data.keys.toList(),
                data = result.data + mapOf(
                    "intent" to intent.intent,
                    "language" to input.language
                )
            )

        } catch (e: Exception) {
            Timber.e(e, "Cognitive loop failed")
            metrics.recordError(System.currentTimeMillis() - startTime)
            AgentOutput(
                text = if (input.language == "sw") "Samahani, kuna hitilafu. Jaribu tena."
                else "Sorry, there was an error. Please try again.",
                confidence = 0.0,
                toolsUsed = emptyList()
            )
        }
    }

    // ═══ BOOTSTRAP PROCESSING ═══════════════════════════════════

    /**
     * Handle input during bootstrap mode.
     * Routes through the bootstrap state machine, which runs
     * through the cognitive loop's 7 phases:
     *   INPUT → PERCEIVE → REMEMBER → REASON → ACT → LEARN → OUTPUT
     *
     * The bootstrap state machine replaces the normal REASON/ACT
     * phases with deterministic bootstrap logic (no LLM needed).
     */
    private suspend fun handleBootstrapInput(
        input: AgentInput,
        machine: BootstrapStateMachine,
        sessionId: String,
        startTime: Long
    ): AgentOutput {
        return try {
            // ═══ PHASE 1: INPUT + PERCEIVE ═══
            val perceiveStart = metrics.startPhase("perceive")
            val rawText = if (input.audioData != null) {
                val transcription = voicePipeline.transcribe(input.audioData)
                if (!transcription.success || transcription.text.isBlank()) {
                    return AgentOutput(
                        text = "Sikuskia vizuri. Sema tena taratibu.",
                        confidence = 0.0,
                        toolsUsed = emptyList()
                    )
                }
                transcription.text
            } else {
                input.text
            }
            val sanitizedText = safetyChecker.sanitizeInput(rawText) ?: rawText
            val sttConfidence = 0.8 // Default; real confidence from STT engine
            metrics.endPhase("perceive", perceiveStart)

            // ═══ PHASE 2: REMEMBER (bootstrap mode — minimal context) ═══
            val rememberStart = metrics.startPhase("remember")
            // During bootstrap: L2 has no episodes, L3 is being built.
            // The bootstrap state machine IS the memory for now.
            metrics.endPhase("remember", rememberStart)

            // ═══ PHASE 3: REASON (bootstrap state machine decides) ═══
            val reasonStart = metrics.startPhase("reason")
            val bootstrapResponse = machine.processInput(sanitizedText, sttConfidence)
            metrics.endPhase("reason", reasonStart)

            // ═══ PHASE 4: REFLECT (skip during bootstrap — deterministic) ═══

            // ═══ PHASE 5: ACT ═══
            val actStart = metrics.startPhase("act")
            // The bootstrap state machine already updated its state in processInput.
            // If we've reached COMPLETE, finalize bootstrap.
            if (bootstrapResponse.isComplete) {
                finalizeBootstrap(machine)
            }
            metrics.endPhase("act", actStart)

            // ═══ PHASE 6: LEARN ═══
            val learnStart = metrics.startPhase("learn")
            // Store bootstrap exchange as L2 episode
            memoryBridge.storeBootstrapTurn(
                workerId = workerIdProvider.getWorkerId(),
                step = machine.currentStep.name,
                workerInput = sanitizedText,
                agentResponse = bootstrapResponse.text,
                sessionId = sessionId
            )
            metrics.endPhase("learn", learnStart)

            // ═══ PHASE 7: OUTPUT ═══
            val safeText = safetyChecker.checkOutput(bootstrapResponse.text, input.language)
            metrics.recordSuccess(System.currentTimeMillis() - startTime)

            AgentOutput(
                text = safeText,
                confidence = 0.9,
                toolsUsed = emptyList(),
                data = mapOf(
                    "intent" to "bootstrap_response",
                    "bootstrap_step" to machine.currentStep.name,
                    "language" to input.language
                )
            )

        } catch (e: Exception) {
            Timber.e(e, "Bootstrap processing failed")
            metrics.recordError(System.currentTimeMillis() - startTime)
            AgentOutput(
                text = "Pole, kuna hitilafu. Jaribu tena.",
                confidence = 0.0,
                toolsUsed = emptyList()
            )
        }
    }

    /**
     * Phase 5: ACT — Execute the decision.
     */
    private suspend fun act(decision: Decision, language: String): ToolResult {
        return when (decision) {
            is Decision.ToolDecision -> {
                try {
                    decision.tool.execute(decision.args, language)
                } catch (e: Exception) {
                    Timber.e(e, "Tool execution failed: ${decision.tool.name}")
                    ToolResult(
                        text = if (language == "sw") "Samahani, kuna hitilafu. Jaribu tena."
                        else "Sorry, there was an error. Please try again.",
                        data = emptyMap(),
                        success = false,
                        errorCode = "TOOL_ERROR"
                    )
                }
            }
            is Decision.PlannedDecision -> {
                executePlan(decision.plan, language)
            }
            is Decision.LlmDecision -> {
                ToolResult(
                    text = decision.response.ifBlank {
                        if (language == "sw") "Samahani, sijaweza kujibu."
                        else "Sorry, I couldn't generate a response."
                    },
                    data = emptyMap(),
                    success = decision.response.isNotBlank()
                )
            }
            is Decision.RevisedDecision -> {
                ToolResult(
                    text = decision.concerns.joinToString("\n"),
                    data = mapOf("requires_confirmation" to "true"),
                    success = true
                )
            }
        }
    }

    /**
     * Phase 4: REFLECT — Self-evaluate for high-stakes decisions.
     */
    private fun shouldReflect(decision: Decision): Boolean {
        return when (decision) {
            is Decision.ToolDecision -> {
                safetyChecker.requiresConfirmation(
                    decision.tool.name,
                    (decision.args["amount"] as? Number)?.toDouble()
                )
            }
            is Decision.PlannedDecision -> true
            else -> false
        }
    }

    private suspend fun reflect(decision: Decision, context: AgentContext, language: String): Decision {
        return when (decision) {
            is Decision.ToolDecision -> {
                val critique = safetyChecker.evaluateDecision(decision.tool.name, decision.args)
                if (critique.isSafe) decision
                else Decision.RevisedDecision(decision, critique.concerns)
            }
            else -> decision
        }
    }

    /**
     * Execute a multi-step plan sequentially.
     */
    private suspend fun executePlan(plan: ExecutionPlan, language: String): ToolResult {
        val results = mutableMapOf<Int, ToolResult>()

        for (step in plan.steps.sortedBy { it.order }) {
            // Check dependencies
            val failedDeps = step.dependsOn.filter { depIdx -> depIdx !in results }
            if (failedDeps.isNotEmpty()) {
                results[step.order] = ToolResult(
                    text = "Skipped: dependency failed",
                    data = emptyMap(),
                    success = false
                )
                continue
            }

            // Enrich args with prior results
            val enrichedArgs = step.args.toMutableMap()
            if (step.dependsOn.isNotEmpty()) {
                val priorSummary = step.dependsOn
                    .mapNotNull { results[it] }
                    .joinToString("; ") { it.text.take(120) }
                if (priorSummary.isNotEmpty()) {
                    enrichedArgs["_prior_results"] = priorSummary
                }
            }
            enrichedArgs["language"] = language

            val tool = toolRegistry.findToolByName(step.toolName)
            if (tool == null) {
                results[step.order] = ToolResult(
                    text = "Tool not found: ${step.toolName}",
                    data = emptyMap(),
                    success = false
                )
                continue
            }

            try {
                results[step.order] = tool.execute(enrichedArgs, language)
            } catch (e: Exception) {
                Timber.e(e, "Plan step ${step.order} failed")
                results[step.order] = ToolResult(
                    text = "Step failed: ${step.description}",
                    data = emptyMap(),
                    success = false
                )
            }
        }

        val finalResult = results.values.lastOrNull()
        return ToolResult(
            text = results.values.filter { it.success }.joinToString("\n\n") { it.text },
            data = results.values.flatMap { it.data.entries }.associate { it.key to it.value },
            success = results.values.all { it.success }
        )
    }

    /**
     * Detect multi-step intents.
     */
    private fun isMultiStepIntent(text: String): Boolean {
        val lower = text.lowercase()
        return (lower.contains("compare") && lower.contains("and")) ||
               (lower.contains("analyze") && lower.contains("recommend")) ||
               (lower.contains("check") && lower.contains("suggest")) ||
               (lower.contains("first") && lower.contains("then"))
    }

    /**
     * Decompose a multi-step intent into ordered steps.
     */
    private suspend fun planSteps(
        text: String,
        intent: IntentRouter.Classification,
        context: AgentContext
    ): ExecutionPlan {
        val llmResponse = llmEngine.generateResponse(
            userInput = "Break this into 2-3 steps using tools: ${toolRegistry.getToolNames().joinToString(", ")}\nRequest: $text\nReply as: step1:tool_name, step2:tool_name",
            language = "en",
            maxTokens = 128
        )

        // Parse LLM response into steps (fallback to simple decomposition)
        val steps = parsePlanFromLlm(llmResponse).ifEmpty {
            // Simple fallback decomposition
            listOf(
                PlannedStep(1, "check_balance", emptyMap(), emptyList(), "Check current state"),
                PlannedStep(2, "advice", mapOf("topic" to text), listOf(1), "Provide advice")
            )
        }

        return ExecutionPlan(steps)
    }

    private fun parsePlanFromLlm(response: String): List<PlannedStep> {
        val steps = mutableListOf<PlannedStep>()
        val pattern = Regex("step(\\d+)\\s*:\\s*(\\w+)", RegexOption.IGNORE_CASE)
        pattern.findAll(response).forEach { match ->
            steps.add(PlannedStep(
                order = match.groupValues[1].toInt(),
                toolName = match.groupValues[2],
                args = emptyMap(),
                dependsOn = if (steps.isNotEmpty()) listOf(steps.size) else emptyList(),
                description = "Step ${match.groupValues[1]}"
            ))
        }
        return steps
    }
}

// ── Data Classes ──────────────────────────────────────────────

data class AgentInput(
    val text: String,
    val language: String = "sw",
    val sessionId: String,
    val workerId: String,
    val audioData: ByteArray? = null,
    val source: InputSource = InputSource.VOICE
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AgentInput) return false
        return text == other.text && sessionId == other.sessionId
    }
    override fun hashCode(): Int = text.hashCode() * 31 + sessionId.hashCode()
}

enum class InputSource { VOICE, TEXT, MPESA_CALLBACK }

data class AgentOutput(
    val text: String,
    val confidence: Double,
    val toolsUsed: List<String>,
    val data: Map<String, Any> = emptyMap()
)

sealed class Decision {
    data class ToolDecision(val tool: Tool, val args: Map<String, Any>) : Decision()
    data class PlannedDecision(val plan: ExecutionPlan) : Decision()
    data class LlmDecision(val response: String) : Decision()
    data class RevisedDecision(val original: Decision, val concerns: List<String>) : Decision()
}

data class PlannedStep(
    val order: Int,
    val toolName: String,
    val args: Map<String, Any>,
    val dependsOn: List<Int> = emptyList(),
    val description: String
)

data class ExecutionPlan(val steps: List<PlannedStep>)
