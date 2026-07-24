package com.msaidizi.app.superagent.harness

import com.msaidizi.app.core.database.ConversationDao
import com.msaidizi.app.core.database.KnowledgeDao
import com.msaidizi.app.core.database.UserProfileDao
import com.msaidizi.app.model.ConversationEntity
import com.msaidizi.app.superagent.guardrails.GuardrailsEngine
import com.msaidizi.app.superagent.memory.MemoryManager
import com.msaidizi.app.superagent.tools.ToolRegistry
import com.msaidizi.app.superagent.tools.ToolResult
import com.msaidizi.app.superagent.flywheel.FlywheelEngine
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

            // 5. CAPABILITY ACTIVATION — Use tools if needed
            _processingState.value = ProcessingState.EXECUTING
            var toolResults: List<ToolResult> = emptyList()
            if (intent.requiredTools.isNotEmpty()) {
                toolResults = intent.requiredTools.mapNotNull { toolName ->
                    toolRegistry.execute(toolName, intent.toolParams[toolName] ?: emptyMap())
                }
            }

            // 6. GENERATE RESPONSE via LLM
            _processingState.value = ProcessingState.GENERATING
            val response = llmEngine.generate(
                systemPrompt = buildSystemPrompt(context),
                userMessage = input,
                context = context,
                toolResults = toolResults,
                intent = intent
            )

            // 7. GUARDRAILS CHECK on output
            val outputCheck = guardrailsEngine.checkOutput(response)
            val finalResponse = if (outputCheck.blocked) {
                outputCheck.message ?: "Samahani, I need to rephrase that."
            } else {
                response
            }

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
            appendLine("You are $msaidiziName, an AI business assistant for ${profile?.userName ?: "my boss"}.")
            appendLine("You help with their ${context.businessProfile?.businessType?.swahiliName ?: "business"} business.")
            appendLine("Location: ${context.businessProfile?.location ?: "Kenya"}")
            appendLine()
            appendLine("PERSONALITY:")
            appendLine("- Warm, friendly, like a trusted business partner")
            appendLine("- Speak naturally, mix Kiswahili and English as appropriate")
            appendLine("- Be concise — busy workers don't have time for long answers")
            appendLine("- Give practical, actionable advice")
            appendLine("- Always confirm financial transactions before recording")
            appendLine()
            appendLine("CAPABILITIES:")
            appendLine("- Record sales, expenses, stock purchases")
            appendLine("- Track inventory and alert on low stock")
            appendLine("- Calculate daily/weekly/monthly profit")
            appendLine("- Manage customer credit (deni)")
            appendLine("- Give business advice based on their data")
            appendLine("- Answer questions in Kiswahili or English")
            appendLine()
            if (context.recentFinancialSummary != null) {
                appendLine("TODAY'S BUSINESS:")
                appendLine(context.recentFinancialSummary)
                appendLine()
            }
            if (context.knowledgeContext.isNotEmpty()) {
                appendLine("RELEVANT KNOWLEDGE:")
                context.knowledgeContext.forEach { appendLine("- $it") }
                appendLine()
            }
            appendLine("Respond naturally. Use Kiswahili unless the user speaks English.")
            appendLine("Keep responses short — 1-3 sentences for simple queries.")
        }
    }
}

// ──────────────────────────────────────────────
// Supporting Types
// ──────────────────────────────────────────────

enum class ProcessingState {
    IDLE, ROUTING, ASSEMBLING_CONTEXT, CHECKING_GUARDRAILS, EXECUTING, GENERATING, LEARNING
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
    CHECK_STOCK,
    ADD_PRODUCT,
    UPDATE_STOCK,

    // Queries
    ASK_SALES_TODAY,
    ASK_PROFIT,
    ASK_EXPENSES,
    ASK_STOCK,
    ASK_DEBTORS,
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

    // System
    UNKNOWN,
    VOICE_COMMAND
}

data class AssembledContext(
    val userProfile: com.msaidizi.app.model.UserProfileEntity? = null,
    val businessProfile: com.msaidizi.app.model.BusinessProfile? = null,
    val recentConversation: List<ConversationEntity> = emptyList(),
    val recentFinancialSummary: String? = null,
    val knowledgeContext: List<String> = emptyList(),
    val relevantPatterns: List<String> = emptyList(),
    val memoryContext: String? = null
)
