package com.msaidizi.app.agent

import com.msaidizi.app.memory.UnifiedMemoryBridge
import com.msaidizi.app.voice.VoicePipeline
import com.msaidizi.app.agent.tools.Tool
import com.msaidizi.app.agent.tools.ToolRegistry
import com.msaidizi.app.agent.tools.ToolResult
import com.msaidizi.app.core.metrics.PhaseMetrics
import com.msaidizi.app.security.SafetyChecker
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
    private val voicePipeline: VoicePipeline
) {
    /**
     * Process a single user input through the cognitive loop.
     * This is the ONLY entry point for all interactions.
     */
    suspend fun processInput(input: AgentInput): AgentOutput {
        val startTime = System.currentTimeMillis()
        
        return try {
            // Phase 1: PERCEIVE — Parse intent, entities, emotion
            val perception = perceive(input)
            
            // Phase 2: REMEMBER — Load L1+L2+L3 context
            val context = remember(perception)
            
            // Phase 3: REASON — Decide what to do
            val decision = reason(perception, context)
            
            // Phase 4: REFLECT (optional) — Self-evaluate for high-stakes decisions
            val finalDecision = if (shouldReflect(decision)) {
                reflect(decision, context)
            } else {
                decision
            }
            
            // Phase 5: ACT — Execute tools
            val result = act(finalDecision)
            
            // Phase 6: LEARN — Update memory, extract patterns
            learn(input, result, context)
            
            // Phase 7: OUTPUT — Generate response
            val output = generateOutput(result, context)
            
            metrics.recordSuccess(System.currentTimeMillis() - startTime)
            output
            
        } catch (e: Exception) {
            metrics.recordError(System.currentTimeMillis() - startTime)
            AgentOutput(
                text = "Samahani, kuna hitilafu. Jaribu tena.",
                confidence = 0.0,
                toolsUsed = emptyList()
            )
        }
    }
    
    /**
     * Phase 1: PERCEIVE — Parse the input into structured data.
     * Uses IntentRouter (regex, 90% accuracy) for fast classification.
     */
    private suspend fun perceive(input: AgentInput): Perception {
        metrics.startPhase("perceive")
        val startTime = System.currentTimeMillis()
        
        val intent = intentRouter.classify(input.text, input.language)
        val entities = intentRouter.extractEntities(input.text)
        val emotion = intentRouter.detectEmotion(input.text)
        
        metrics.endPhase("perceive", System.currentTimeMillis() - startTime)
        
        return Perception(
            rawInput = input,
            intent = intent,
            entities = entities,
            emotion = emotion,
            confidence = intent.confidence
        )
    }
    
    /**
     * Phase 2: REMEMBER — Load context from all memory layers.
     * L1: Current conversation (working memory)
     * L2: Past episodes (episodic memory, SQLite FTS5)
     * L3: Behavioral model (learned patterns, preferences)
     */
    private suspend fun remember(perception: Perception): AgentContext {
        metrics.startPhase("remember")
        val startTime = System.currentTimeMillis()
        
        val context = memoryBridge.enrichContext(
            input = perception.rawInput.text,
            intent = perception.intent,
            sessionId = perception.rawInput.sessionId
        )
        
        metrics.endPhase("remember", System.currentTimeMillis() - startTime)
        return context
    }
    
    /**
     * Phase 3: REASON — Decide what action to take.
     * 90% of requests: code-only (IntentRouter → Tool)
     * 10% of requests: LLM escalation (Qwen 0.8B on-device)
     */
    private suspend fun reason(perception: Perception, context: AgentContext): Decision {
        metrics.startPhase("reason")
        val startTime = System.currentTimeMillis()
        
        // Check if this is a multi-step intent (needs planning)
        if (isMultiStepIntent(perception)) {
            val plan = planSteps(perception, context)
            metrics.endPhase("reason", System.currentTimeMillis() - startTime)
            return Decision.PlannedDecision(plan)
        }
        
        // High confidence → direct tool execution
        if (perception.confidence > 0.8) {
            val tool = toolRegistry.findTool(perception.intent)
            if (tool != null) {
                metrics.endPhase("reason", System.currentTimeMillis() - startTime)
                return Decision.ToolDecision(tool, perception.entities)
            }
        }
        
        // Low confidence → LLM escalation
        val llmResponse = llmEngine.generate(
            prompt = buildPrompt(perception, context),
            maxTokens = 256
        )
        
        metrics.endPhase("reason", System.currentTimeMillis() - startTime)
        return Decision.LlmDecision(llmResponse)
    }
    
    /**
     * Phase 4: REFLECT — Self-evaluate for high-stakes decisions.
     * Activates for: financial transactions ≥KSh 5,000, loans, multi-step intents.
     * Non-blocking: if reflection fails, proceed with original decision.
     */
    private fun shouldReflect(decision: Decision): Boolean {
        return when (decision) {
            is Decision.ToolDecision -> {
                decision.tool.name in listOf("mpesa_transaction", "loan_apply", "inventory_bulk") &&
                (decision.entities.amount ?: 0.0) >= 5000.0
            }
            is Decision.PlannedDecision -> true
            else -> false
        }
    }
    
    private suspend fun reflect(decision: Decision, context: AgentContext): Decision {
        metrics.startPhase("reflect")
        val startTime = System.currentTimeMillis()
        
        val critique = safetyChecker.evaluateDecision(decision, context)
        
        metrics.endPhase("reflect", System.currentTimeMillis() - startTime)
        
        return if (critique.isSafe) decision
        else Decision.RevisedDecision(decision, critique.concerns)
    }
    
    /**
     * Phase 5: ACT — Execute the decision.
     * Single-step: execute one tool
     * Multi-step: execute plan sequentially with dependency tracking
     */
    private suspend fun act(decision: Decision): ToolResult {
        metrics.startPhase("act")
        val startTime = System.currentTimeMillis()
        
        val result = when (decision) {
            is Decision.ToolDecision -> {
                decision.tool.execute(
                    args = decision.entities.toMap(),
                    language = decision.entities.language ?: "sw"
                )
            }
            is Decision.PlannedDecision -> {
                executePlan(decision.plan)
            }
            is Decision.LlmDecision -> {
                ToolResult(
                    text = decision.response,
                    data = emptyMap(),
                    success = true
                )
            }
            is Decision.RevisedDecision -> {
                // Safety checker flagged concerns — provide safe alternative
                ToolResult(
                    text = "Kuna wasiwasi na ombi lako. Tafadhali thibitisha: ${decision.concerns.joinToString()}",
                    data = mapOf("requires_confirmation" to true),
                    success = true
                )
            }
        }
        
        metrics.endPhase("act", System.currentTimeMillis() - startTime)
        return result
    }
    
    /**
     * Phase 6: LEARN — Update memory with what happened.
     * Updates L2 (episodic memory) and L3 (behavioral model).
     */
    private suspend fun learn(
        input: AgentInput,
        result: ToolResult,
        context: AgentContext
    ) {
        metrics.startPhase("learn")
        val startTime = System.currentTimeMillis()
        
        // Store episode in L2
        memoryBridge.storeEpisode(
            input = input.text,
            intent = context.intent,
            result = result,
            sessionId = input.sessionId
        )
        
        // Update L3 behavioral model
        memoryBridge.updateBehavioralModel(
            input = input.text,
            result = result,
            context = context
        )
        
        metrics.endPhase("learn", System.currentTimeMillis() - startTime)
    }
    
    /**
     * Phase 7: OUTPUT — Generate the final response.
     */
    private fun generateOutput(result: ToolResult, context: AgentContext): AgentOutput {
        return AgentOutput(
            text = result.text,
            confidence = if (result.success) 0.9 else 0.3,
            toolsUsed = result.data.keys.toList(),
            data = result.data
        )
    }
    
    // ── Planning ──────────────────────────────────────────────
    
    /**
     * Detect multi-step intents that need planning.
     * Examples: "compare my sales this week to last week and suggest pricing changes"
     */
    private fun isMultiStepIntent(perception: Perception): Boolean {
        val text = perception.rawInput.text.lowercase()
        return text.contains("compare") && text.contains("and") ||
               text.contains("analyze") && text.contains("recommend") ||
               text.contains("check") && text.contains("suggest")
    }
    
    /**
     * Decompose a multi-step intent into ordered steps with dependencies.
     */
    private fun planSteps(perception: Perception, context: AgentContext): ExecutionPlan {
        val steps = mutableListOf<PlannedStep>()
        
        // Example: "compare sales and suggest pricing"
        if (perception.rawInput.text.contains("compare")) {
            steps.add(PlannedStep(
                order = 1,
                toolName = "query_sales",
                args = mapOf("period" to "this_week"),
                description = "Get this week's sales data"
            ))
            steps.add(PlannedStep(
                order = 2,
                toolName = "query_sales",
                args = mapOf("period" to "last_week"),
                dependsOn = listOf(1),
                description = "Get last week's sales data"
            ))
            steps.add(PlannedStep(
                order = 3,
                toolName = "compare_analysis",
                args = emptyMap(),
                dependsOn = listOf(1, 2),
                description = "Compare and analyze differences"
            ))
        }
        
        return ExecutionPlan(steps = steps)
    }
    
    /**
     * Execute a multi-step plan sequentially, passing prior results as context.
     */
    private suspend fun executePlan(plan: ExecutionPlan): ToolResult {
        val results = mutableMapOf<Int, ToolResult>()
        
        for (step in plan.steps.sortedBy { it.order }) {
            val priorResults = step.dependsOn.map { dep ->
                results[dep] ?: throw IllegalStateException("Dependency $dep not found")
            }
            
            val tool = toolRegistry.findToolByName(step.toolName)
                ?: throw IllegalStateException("Tool ${step.toolName} not found")
            
            val enrichedArgs = step.args.toMutableMap()
            enrichedArgs["_prior_results"] = priorResults.map { it.data }.toString()
            
            val result = tool.execute(enrichedArgs, "sw")
            results[step.order] = result
        }
        
        val finalResult = results.values.last()
        return ToolResult(
            text = finalResult.text,
            data = results.values.flatMap { it.data.entries }.associate { it.key to it.value },
            success = results.values.all { it.success }
        )
    }
}

// ── Data Classes ──────────────────────────────────────────────

data class AgentInput(
    val text: String,
    val language: String = "sw",
    val sessionId: String,
    val audioData: ByteArray? = null,
    val source: InputSource = InputSource.VOICE
)

enum class InputSource { VOICE, TEXT, MPESA_CALLBACK }

data class AgentOutput(
    val text: String,
    val confidence: Double,
    val toolsUsed: List<String>,
    val data: Map<String, Any> = emptyMap()
)

data class Perception(
    val rawInput: AgentInput,
    val intent: IntentResult,
    val entities: EntityResult,
    val emotion: EmotionResult?,
    val confidence: Double
)

sealed class Decision {
    data class ToolDecision(val tool: Tool, val entities: EntityResult) : Decision()
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

data class IntentResult(
    val type: String,
    val confidence: Double,
    val isSheng: Boolean = false
)

data class EntityResult(
    val amount: Double? = null,
    val item: String? = null,
    val quantity: Int? = null,
    val language: String? = null
) {
    fun toMap(): Map<String, Any> = buildMap {
        amount?.let { put("amount", it) }
        item?.let { put("item", it) }
        quantity?.let { put("quantity", it) }
    }
}

data class EmotionResult(
    val primary: String,
    val confidence: Double
)

data class AgentContext(
    val l1Context: String,      // Current conversation
    val l2Episodes: String,    // Relevant past episodes
    val l3Profile: String,     // Worker behavioral model
    val skills: String,        // Relevant skills
    val intent: IntentResult
)
