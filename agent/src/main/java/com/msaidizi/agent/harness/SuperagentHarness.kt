package com.msaidizi.agent.harness

import com.msaidizi.core.database.ConversationDao
import com.msaidizi.core.database.KnowledgeDao
import com.msaidizi.core.database.UserProfileDao
import com.msaidizi.core.model.ConversationEntity
import com.msaidizi.agent.guardrails.GuardrailsEngine
import com.msaidizi.agent.memory.MemoryManager
import com.msaidizi.agent.tools.ToolRegistry
import com.msaidizi.agent.tools.ToolResult
import com.msaidizi.agent.flywheel.FlywheelEngine
import com.msaidizi.agent.loops.AdviceRefinementLoop
import com.msaidizi.agent.loops.FeedbackLoopIntegration
import com.msaidizi.agent.loops.OODALoop
import com.msaidizi.agent.loops.OODAResult
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SuperagentHarness — The unified brain of Msaidizi.
 *
 * Pipeline: Intent Router → Context Assembly → Capability Activation → Guardrails → Response
 *
 * This is NOT 5 separate agents. It's 1 brain with capability modules.
 */
@Singleton
class SuperagentHarness @Inject constructor(
    private val llmEngine: LlmEngine,
    private val intentRouter: IntentRouter,
    private val contextAssembler: ContextAssembler,
    private val memoryManager: MemoryManager,
    private val guardrailsEngine: GuardrailsEngine,
    private val flywheelEngine: FlywheelEngine,
    private val toolRegistry: ToolRegistry,
    private val conversationDao: ConversationDao,
    private val userProfileDao: UserProfileDao,
    private val knowledgeDao: KnowledgeDao,
    private val oodaLoop: OODALoop,
    private val adviceRefinementLoop: AdviceRefinementLoop,
    private val feedbackLoopIntegration: FeedbackLoopIntegration,
    private val gson: Gson
) {
    private val sessionId = UUID.randomUUID().toString()

    private val _processingState = MutableStateFlow(ProcessingState.IDLE)
    val processingState: StateFlow<ProcessingState> = _processingState.asStateFlow()

    /**
     * Main entry point: process user input through the full pipeline.
     */
    suspend fun processInput(
        input: String,
        isVoice: Boolean = false
    ): HarnessResponse = withContext(Dispatchers.Default) {
        _processingState.value = ProcessingState.ROUTING

        try {
            // 1. Save user message to conversation
            conversationDao.insert(
                ConversationEntity(
                    sessionId = sessionId,
                    role = "user",
                    content = input
                )
            )

            // 2. INTENT ROUTING — Understand what the user wants
            val intent = intentRouter.route(input)
            Timber.d("Intent: ${intent.type} (${intent.confidence})")

            // 3. CONTEXT ASSEMBLY — Gather relevant context from all memory layers
            _processingState.value = ProcessingState.ASSEMBLING_CONTEXT
            val context = contextAssembler.assemble(
                intent = intent,
                sessionId = sessionId,
                recentConversation = conversationDao.getRecent(20)
            )

            // 4. GUARDRAILS CHECK — Ensure safety before processing
            _processingState.value = ProcessingState.CHECKING_GUARDRAILS
            val guardrailResult = guardrailsEngine.check(intent, context)
            if (guardrailResult.blocked) {
                return@withContext HarnessResponse(
                    text = guardrailResult.message ?: "Samahani, I can't do that.",
                    intent = intent,
                    blocked = true
                )
            }

            // 5. EXECUTE via OODA LOOP (replaces linear pipeline)
            _processingState.value = ProcessingState.EXECUTING

            val oodaResult: OODAResult = if (intent.type == IntentType.ASK_ADVICE) {
                // ── Advice path: Use AdviceRefinementLoop ──────────────
                val adviceResult = adviceRefinementLoop.generateRefinedAdvice(
                    intent = intent,
                    context = context,
                    llmEngine = llmEngine,
                    buildPrompt = { ctx ->
                        val base = buildSystemPrompt(ctx)
                        HermesPromptBuilder.buildFunctionCallingSystemPrompt(base, toolRegistry)
                    }
                )
                OODAResult(
                    response = adviceResult.advice,
                    confidence = adviceResult.qualityScore,
                    iterations = adviceResult.iterations,
                    totalDurationMs = adviceResult.totalDurationMs,
                    iterationLogs = emptyList(),
                    terminatedBy = com.msaidizi.app.superagent.loops.TerminationReason.CONFIDENCE_MET
                )
            } else {
                // ── Standard path: Use OODA Loop ──────────────────────
                oodaLoop.execute(
                    input = input,
                    intent = intent,
                    context = context,
                    llmEngine = llmEngine,
                    toolRegistry = toolRegistry,
                    guardrails = guardrailsEngine,
                    flywheel = flywheelEngine,
                    memoryManager = memoryManager,
                    buildPrompt = { ctx ->
                        val base = buildSystemPrompt(ctx)
                        HermesPromptBuilder.buildFunctionCallingSystemPrompt(base, toolRegistry)
                    }
                )
            }

            val finalResponse = oodaResult.response
            val toolResults: List<ToolResult> = emptyList()

            // 8. Save assistant response
            conversationDao.insert(
                ConversationEntity(
                    sessionId = sessionId,
                    role = "assistant",
                    content = finalResponse,
                    intent = intent.type.name
                )
            )

            // 9. FLYWHEEL — Learn from this interaction
            _processingState.value = ProcessingState.LEARNING
            flywheelEngine.processInteraction(
                input = input,
                response = finalResponse,
                intent = intent,
                toolResults = toolResults
            )

            // 10. Update working memory
            memoryManager.updateWorkingMemory(input, finalResponse, intent)

            _processingState.value = ProcessingState.IDLE

            HarnessResponse(
                text = finalResponse,
                intent = intent,
                toolResults = toolResults
            )

        } catch (e: Exception) {
            Timber.e(e, "Harness processing failed")
            _processingState.value = ProcessingState.IDLE
            HarnessResponse(
                text = "Pole sana, something went wrong. Please try again.",
                error = e.message
            )
        }
    }

    /**
     * Build the system prompt with all context.
     */
    private fun buildSystemPrompt(context: AssembledContext): String {
        val profile = context.userProfile
        val msaidiziName = profile?.msaidiziName ?: "Msaidizi"

        return buildString {
            // ═══ LAYER 1: System Identity (static, cached) ═══
            appendLine("=== IDENTITY ===")
            appendLine("You are $msaidiziName, an AI business assistant for ${profile?.userName ?: "my boss"}.")
            appendLine("Business: ${context.businessProfile?.businessType?.swahiliName ?: "business"} (${context.businessProfile?.businessType?.displayName ?: "Other"})")
            appendLine("Location: ${context.businessProfile?.location ?: "Kenya"}")
            appendLine("Language: ${context.businessProfile?.language?.displayName ?: "Kiswahili"}")
            if (context.alamaScore != null) {
                appendLine("Alama Score: ${context.alamaScore.score} (${context.alamaScore.level})")
                if (context.alamaScore.creditReady) appendLine("Credit ready: Yes")
            }
            appendLine()

            // ═══ LAYER 2: Working Memory / OODA State ═══
            appendLine("=== CURRENT STATE (${context.oodaPhase.displayName}) ===")
            if (context.oodaObservations.isNotEmpty()) {
                appendLine("Recent observations:")
                context.oodaObservations.takeLast(3).forEach { appendLine("  - $it") }
            }
            if (context.oodaDecisions.isNotEmpty()) {
                appendLine("Active decisions:")
                context.oodaDecisions.takeLast(2).forEach { appendLine("  - $it") }
            }
            appendLine()

            // ═══ LAYER 3: Session Memory ═══
            appendLine("=== SESSION CONTEXT ===")
            appendLine("PERSONALITY: Warm, friendly, like a trusted business partner. Speak naturally, mix Kiswahili and English. Be concise. Give practical, actionable advice. Always confirm financial transactions before recording.")
            appendLine()
            appendLine("CAPABILITIES: Record sales/expenses/purchases, track inventory, calculate profit, manage customer credit (deni), give data-driven advice.")
            appendLine()
            if (context.sessionSummaries.isNotEmpty()) {
                appendLine("Previous sessions:")
                context.sessionSummaries.take(3).forEach { appendLine("  - $it") }
                appendLine()
            }

            // ═══ LAYER 4: Knowledge Base (retrieval) ═══
            appendLine("=== KNOWLEDGE ===")
            if (context.recentFinancialSummary != null) {
                appendLine("Today's business:")
                appendLine(context.recentFinancialSummary)
            }
            if (context.knowledgeContext.isNotEmpty()) {
                appendLine("Relevant patterns:")
                context.knowledgeContext.forEach { appendLine("  - $it") }
            }
            if (context.marketInsights.isNotEmpty()) {
                appendLine("Market/sector data:")
                context.marketInsights.forEach { appendLine("  - $it") }
            }
            appendLine()

            // ═══ LAYER 5: Flywheel Insights (learned) ═══
            if (context.relevantPatterns.isNotEmpty() || context.learnedVocabulary.isNotBlank() || context.businessRhythms.isNotBlank()) {
                appendLine("=== LEARNED INSIGHTS ===")
                if (context.relevantPatterns.isNotEmpty()) {
                    appendLine("Your patterns:")
                    context.relevantPatterns.forEach { appendLine("  - $it") }
                }
                if (context.learnedVocabulary.isNotBlank()) {
                    appendLine("Worker vocabulary: ${context.learnedVocabulary}")
                }
                if (context.businessRhythms.isNotBlank()) {
                    appendLine("Business rhythms: ${context.businessRhythms}")
                }
                appendLine()
            }

            // ═══ Response Guidelines ═══
            appendLine("Respond naturally. Use ${context.businessProfile?.language?.displayName ?: "Kiswahili"} unless the user speaks another language.")
            appendLine("Keep responses short — 1-3 sentences for simple queries.")
        }
    }
}

// ──────────────────────────────────────────────
// Supporting Types
// ──────────────────────────────────────────────

enum class ProcessingState {
    IDLE, ROUTING, ASSEMBLING_CONTEXT, CHECKING_GUARDRAILS,
    EXECUTING, GENERATING, LEARNING,
    OODA_ITERATING,   // OODA loop is running (sub-state of EXECUTING)
    REFINING_ADVICE   // Advice refinement loop is running
}

data class HarnessResponse(
    val text: String,
    val intent: UserIntent? = null,
    val toolResults: List<ToolResult> = emptyList(),
    val blocked: Boolean = false,
    val error: String? = null
)

data class UserIntent(
    val type: IntentType,
    val confidence: Float,
    val entities: Map<String, String> = emptyMap(),
    val requiredTools: List<String> = emptyList(),
    val toolParams: Map<String, Map<String, String>> = emptyMap(),
    val rawText: String = ""
)

enum class IntentType {
    // Business operations
    RECORD_SALE,
    RECORD_EXPENSE,
    RECORD_PURCHASE,
    RECORD_SERVICE,
    CHECK_STOCK,
    ADD_PRODUCT,
    UPDATE_STOCK,

    // Queries
    ASK_SALES_TODAY,
    ASK_PROFIT,
    ASK_EXPENSES,
    ASK_STOCK,
    ASK_DEBTORS,
    ASK_SERVICES_TODAY,
    ASK_ADVICE,

    // Customer management
    ADD_CUSTOMER,
    CHECK_CUSTOMER_DEBT,
    RECORD_PAYMENT,

    // Reports
    DAILY_REPORT,
    WEEKLY_REPORT,
    MONTHLY_REPORT,

    // Conversational
    GREETING,
    FAREWELL,
    THANKS,
    HELP,
    CHITCHAT,

    // Scanner & Dashboard
    SCAN_RECEIPT,
    VIEW_DASHBOARD,

    // Extended tools
    QUICK_SALE,
    CHAMA_MANAGE,
    CREDIT_CHECK,
    LOAN_COMPARE,
    INSURANCE_MATCH,
    RIDE_SHARE,
    MARKET_PRICE,
    PROOF_OF_INCOME,
    GOAL_TRACK,
    WHATSAPP_REPORT,

    // System
    UNKNOWN,
    VOICE_COMMAND
}

data class AssembledContext(
    // Layer 1: System Identity (static, cached)
    val userProfile: com.msaidizi.app.model.UserProfileEntity? = null,
    val businessProfile: com.msaidizi.app.model.BusinessProfile? = null,
    val alamaScore: com.msaidizi.app.superagent.tools.AlamaScoreResult? = null,

    // Layer 2: Working Memory (OODA state)
    val oodaPhase: OodaPhase = OodaPhase.OBSERVE,
    val oodaObservations: List<String> = emptyList(),
    val oodaDecisions: List<String> = emptyList(),

    // Layer 3: Session Memory (conversation)
    val recentConversation: List<ConversationEntity> = emptyList(),
    val sessionSummaries: List<String> = emptyList(),

    // Layer 4: Knowledge Base (retrieval)
    val recentFinancialSummary: String? = null,
    val knowledgeContext: List<String> = emptyList(),
    val marketInsights: List<String> = emptyList(),

    // Layer 5: Flywheel Insights (learned)
    val relevantPatterns: List<String> = emptyList(),
    val learnedVocabulary: String = "",
    val businessRhythms: String = "",

    // Legacy compat
    val memoryContext: String? = null
)
