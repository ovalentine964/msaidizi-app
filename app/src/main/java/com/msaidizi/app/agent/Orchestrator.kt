package com.msaidizi.app.agent

import com.msaidizi.app.core.model.*
import com.msaidizi.app.core.util.SwahiliParser
import com.msaidizi.app.core.database.TitheDao
import com.msaidizi.app.core.database.GoalDao
import com.msaidizi.app.core.database.LoanDao
import com.msaidizi.app.voice.LlmEngine
import com.msaidizi.app.finance.TitheTracker
import com.msaidizi.app.finance.GoalPlanner
import com.msaidizi.app.finance.LoanManager
import com.msaidizi.app.gamification.GamificationEngine
import com.msaidizi.app.onboarding.AhaMomentFlow
import com.msaidizi.app.mindset.RichHabitsScore
import com.msaidizi.app.mindset.MindsetAcademy
import com.msaidizi.app.cfo.BriefingDelivery
import com.msaidizi.app.loops.MorningBriefingLoop
import com.msaidizi.app.loops.StreakProtectionLoop
import com.msaidizi.app.loops.VariableRewardsLoop
import com.msaidizi.app.loops.ReActLoop
import com.msaidizi.app.loops.ReflexionLoop
import com.msaidizi.app.loops.PlanExecuteLoop
import com.msaidizi.app.core.dialect.AdaptiveVocabulary
import com.msaidizi.app.evolution.SelfEvolutionManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber
import java.util.UUID


/**
 * Thin coordinator — routes user input to domain-specific handlers.
 *
 * Decomposed from a 1,664-line god class into focused handlers:
 * - [TransactionHandler] — sale, purchase, expense recording
 * - [QueryHandler] — balance, profit, stock, summaries
 * - [AdviceHandler] — advice, greeting, help, correction
 * - [GamificationHandler] — giving, goals, loans
 * - [DomainRouter] — transport, farming, digital, service
 * - [ConversationManager] — memory, context, LLM escalation, confidence handling
 *
 * Flow: Voice Input → AdaptiveLearning → IntentRouter → Handler → Response
 * 90% of requests handled by code alone (no LLM).
 *
 * ## Mathematical & Economic Foundations
 *
 * ### ECO 104 §1.2 — Optimization
 * The orchestrator solves a routing optimization: given the intent,
 * which handler maximizes response value subject to computation budget
 * and latency constraints.
 *
 * ### STA 443 §1.2 — Probability Spaces
 * Each user interaction is a point in (Ω, F, P). We maximize
 * E[Quality | Intent, Context] through confidence-based escalation.
 *
 * @see IntentRouter for intent classification
 * @see TransactionHandler for transaction processing
 * @see QueryHandler for data queries
 * @see AdviceHandler for recommendations
 * @see GamificationHandler for giving/goals/loans
 * @see ConversationManager for memory and LLM escalation
 */
class Orchestrator(
    private val intentRouter: IntentRouter,
    private val businessAgent: BusinessAgent,
    private val analysisAgent: AnalysisAgent,
    private val advisorAgent: AdvisorAgent,
    private val learningAgent: LearningAgent,
    private val adaptiveLearning: AdaptiveLearningEngine,
    // Domain handlers (decomposed from god class)
    private val transactionHandler: TransactionHandler,
    private val queryHandler: QueryHandler,
    private val adviceHandler: AdviceHandler,
    private val gamificationHandler: GamificationHandler,
    private val domainRouter: DomainRouter,
    private val conversationManager: ConversationManager,
    // Legacy dependencies (kept for initialization lifecycle)
    private val gamificationEngine: GamificationEngine? = null,
    private val ahaMomentFlow: AhaMomentFlow? = null,
    private val richHabitsScore: RichHabitsScore? = null,
    private val mindsetAcademy: MindsetAcademy? = null,
    private val titheTracker: TitheTracker? = null,
    private val goalPlanner: GoalPlanner? = null,
    private val loanManager: LoanManager? = null,
    private val titheDao: TitheDao? = null,
    private val goalDao: GoalDao? = null,
    private val loanDao: LoanDao? = null,
    private val briefingDelivery: BriefingDelivery? = null,
    private val morningBriefingLoop: MorningBriefingLoop? = null,
    private val streakProtectionLoop: StreakProtectionLoop? = null,
    private val variableRewardsLoop: VariableRewardsLoop? = null,
    private val selfEvolution: SelfEvolutionManager? = null,
    private val preferenceLearner: PreferenceLearner? = null,
    private val adaptiveVocabulary: AdaptiveVocabulary? = null,
    private val reActLoop: ReActLoop = ReActLoop(),
    private val reflexionLoop: ReflexionLoop = ReflexionLoop(),
    private val planExecuteLoop: PlanExecuteLoop = PlanExecuteLoop(),
    private val eventBus: AgentEventBus = AgentEventBus.getInstance(),
    private val llmEngine: LlmEngine? = null
) {
    private val _responses = MutableSharedFlow<AgentResponse>(extraBufferCapacity = 8)
    val responses: SharedFlow<AgentResponse> = _responses

    /**
     * Process user input and generate a response.
     * Pipeline: classify → context → enhance → route → learn → reflect
     */
    suspend fun processInput(text: String, language: String = "sw"): AgentResponse {
        val trace = reActLoop.startTrace("process_input:$language")
        Timber.d("Processing input: '%s' (lang=%s)", text, language)
        reActLoop.think(trace, "Received input: \"${text.take(50)}\" in language=$language")

        // Self-evolution signals
        conversationManager.recordEvolutionSignals(language)
        conversationManager.publishTaskStarted()

        // Text enhancement
        val enhancedText = adaptiveVocabulary?.applyToTranscription(
            conversationManager.enhanceText(text)
        ) ?: conversationManager.enhanceText(text)
        reActLoop.observe(trace, "Applied vocabulary enhancement: \"${enhancedText.take(50)}\"")

        // Step 0: Check for corrections
        val correctionResponse = conversationManager.checkForCorrection(
            enhancedText, language, transactionHandler.lastTransaction
        )
        if (correctionResponse != null) {
            reActLoop.act(trace, "correction_detected", "Correction detected and recorded")
            _responses.emit(correctionResponse)
            reActLoop.complete(trace, true, correctionResponse.text)
            return correctionResponse
        }

        // Step 1: Classify intent
        var intentResult = intentRouter.classify(enhancedText)
        reActLoop.think(trace, "Intent classified: ${intentResult.intent} (confidence=${String.format("%.2f", intentResult.confidence)}, needsLLM=${intentResult.needsLLM})", intentResult.confidence)
        conversationManager.publishIntentEvent(intentResult, language, enhancedText)

        // Step 2: Conversation context resolution
        val memory = conversationManager.conversationMemory
        if (memory.isFollowUp(enhancedText)) {
            intentResult = memory.resolveReferences(enhancedText, intentResult)
            reActLoop.think(trace, "Resolved as follow-up, intent now: ${intentResult.intent}")
        }

        // Step 3: Adaptive learning enhancement
        intentResult = adaptiveLearning.enhanceIntentWithLearning(intentResult, enhancedText)
        reActLoop.think(trace, "Enhanced intent: ${intentResult.intent} (confidence=${String.format("%.2f", intentResult.confidence)})", intentResult.confidence)

        // Step 4: Confidence-based routing
        val confidenceLevel = conversationManager.classifyConfidence(intentResult)
        val response = when {
            intentResult.needsLLM && llmEngine != null -> {
                reActLoop.act(trace, "llm_escalation", "Escalating to on-device LLM")
                conversationManager.handleLlmEscalation(intentResult, enhancedText, language)
                    ?: routeToHandler(intentResult, text, language)
            }
            confidenceLevel == ConfidenceLevel.LOW -> {
                reActLoop.act(trace, "low_confidence_clarification", "Confidence below threshold")
                conversationManager.handleLowConfidence(intentResult, enhancedText, language)
            }
            confidenceLevel == ConfidenceLevel.MEDIUM -> {
                reActLoop.act(trace, "medium_confidence_confirm", "Confirming with user")
                conversationManager.handleMediumConfidence(intentResult, enhancedText, language)
            }
            else -> {
                reActLoop.act(trace, "route_to_agent", "Routing to handler for intent: ${intentResult.intent}")
                routeToHandler(intentResult, text, language)
            }
        }

        // Steps 5-8: Post-processing
        val critiqueScore = conversationManager.postProcess(enhancedText, intentResult, response, language, trace)
        _responses.emit(response)
        reActLoop.complete(trace, response.type != ResponseType.ERROR, response.text)

        conversationManager.publishTaskCompleted(trace, response)

        return response
    }

    // ═══════════════ ROUTING — delegates to domain handlers ═══════════════

    /**
     * Route intent to the appropriate domain handler.
     * Wraps all handler calls in try-catch for crash prevention.
     */
    private suspend fun routeToHandler(intentResult: IntentResult, text: String, language: String): AgentResponse {
        return try {
            when (intentResult.intent) {
                // Transactions → TransactionHandler
                IntentType.SALE -> transactionHandler.handleSale(intentResult, language)
                IntentType.PURCHASE -> transactionHandler.handlePurchase(intentResult, language)
                IntentType.EXPENSE -> transactionHandler.handleExpense(intentResult, language)

                // Queries → QueryHandler
                IntentType.PROFIT_QUERY -> queryHandler.handleProfitQuery(language)
                IntentType.CHECK_BALANCE -> queryHandler.handleBalanceQuery(language)
                IntentType.STOCK_QUERY -> queryHandler.handleStockQuery(intentResult, language)
                IntentType.DAILY_SUMMARY -> queryHandler.handleDailySummary(language)
                IntentType.WEEKLY_SUMMARY -> queryHandler.handleWeeklySummary(language)

                // Advice → AdviceHandler
                IntentType.ASK_ADVICE -> adviceHandler.handleAdvice(language)
                IntentType.GREETING -> adviceHandler.handleGreeting(language)
                IntentType.HELP -> adviceHandler.handleHelp(language)
                IntentType.CORRECTION -> adviceHandler.handleCorrection(text, language, transactionHandler.lastTransaction)
                IntentType.UNKNOWN -> adviceHandler.handleUnknown(text, language)

                // Domain-specific → DomainRouter
                IntentType.TRANSPORT_TRIP, IntentType.TRANSPORT_EXPENSE,
                IntentType.FARMING_ACTIVITY, IntentType.FARMING_INPUT,
                IntentType.DIGITAL_COMMISSION, IntentType.DIGITAL_TRANSACTION,
                IntentType.SERVICE_CLIENT, IntentType.SERVICE_JOB -> domainRouter.handleDomainIntent(intentResult, language)

                // Giving → GamificationHandler
                IntentType.GIVING_RECORD -> gamificationHandler.handleGivingRecord(intentResult, language) { domainRouter.handleDomainIntent(intentResult, language) }
                IntentType.GIVING_QUERY -> gamificationHandler.handleGivingQuery(intentResult, language) { domainRouter.handleDomainIntent(intentResult, language) }
                IntentType.GIVING_GOAL -> gamificationHandler.handleGivingGoal(intentResult, language) { domainRouter.handleDomainIntent(intentResult, language) }

                // Goals → GamificationHandler
                IntentType.GOAL_CREATE -> gamificationHandler.handleGoalCreate(intentResult, language) { domainRouter.handleDomainIntent(intentResult, language) }
                IntentType.GOAL_PROGRESS -> gamificationHandler.handleGoalProgress(intentResult, language) { domainRouter.handleDomainIntent(intentResult, language) }
                IntentType.GOAL_REPORT -> gamificationHandler.handleGoalReport(language)
                IntentType.GOAL_TIME_FORECAST -> gamificationHandler.handleGoalTimeForecast(language)
                IntentType.GOAL_ADJUST -> gamificationHandler.handleGoalAdjust(intentResult, language) { domainRouter.handleDomainIntent(intentResult, language) }
                IntentType.GOAL_ENCOURAGEMENT -> gamificationHandler.handleGoalEncouragement(language)

                // Loans → GamificationHandler
                IntentType.LOAN_RECORD -> gamificationHandler.handleLoanRecord(intentResult, language) { domainRouter.handleDomainIntent(intentResult, language) }
                IntentType.LOAN_QUERY -> gamificationHandler.handleLoanQuery(language)
                IntentType.LOAN_REPORT -> gamificationHandler.handleLoanReport(language)
                IntentType.LOAN_DEADLINE -> gamificationHandler.handleLoanDeadline(language)
            }
        } catch (e: OutOfMemoryError) {
            Timber.e(e, "OOM during agent routing for intent: %s", intentResult.intent)
            AgentResponse(
                text = if (language == "sw") "⚠️ Kuna tatizo la kumbukumbu. Funga app nyingine jaribu tena."
                else "⚠️ Memory issue. Close other apps and try again.",
                type = ResponseType.ERROR
            )
        } catch (e: Exception) {
            Timber.e(e, "Error during agent routing for intent: %s", intentResult.intent)
            AgentResponse(
                text = if (language == "sw") "⚠️ Kuna tatizo. Jaribu tena."
                else "⚠️ Something went wrong. Try again.",
                type = ResponseType.ERROR,
                data = mapOf("error" to (e.message ?: "unknown"), "intent" to intentResult.intent.name)
            )
        }
    }

    // ═══════════════ LIFECYCLE ═══════════════

    suspend fun getLearningStats(): LearningStats = adaptiveLearning.getLearningStats()
    fun triggerBackgroundLearning() = adaptiveLearning.launchBackgroundLearning()

    suspend fun initializeStickiness() {
        gamificationEngine?.initialize()
        mindsetAcademy?.seedLessons()
        titheTracker?.loadFromDb()
        gamificationEngine?.let { ge ->
            try {
                ge.getStreakRiskReminder()?.let { _responses.emit(AgentResponse(text = it, type = ResponseType.INFORMATION, shouldSpeak = true)) }
                ge.getStreakRecoveryMessage()?.let { _responses.emit(AgentResponse(text = it, type = ResponseType.INFORMATION, shouldSpeak = true)) }
            } catch (_: Exception) {}
        }
        Timber.d("Stickiness features initialized")
    }

    fun getSessionWelcome(language: String = "sw"): String? = ahaMomentFlow?.onSessionStart(language)?.getText(language)
    suspend fun getGamificationState() = gamificationEngine?.getState()
    suspend fun getStreakProtection() = gamificationEngine?.getStreakFreezeStatus()
    suspend fun getBriefingLoopPerformance() = briefingDelivery?.getBriefingPerformance()
    suspend fun getRichHabitsScore() = richHabitsScore?.getTodayScore()
    suspend fun getMindsetProgress() = mindsetAcademy?.getProgress()
    fun getReActTraces(n: Int = 10): List<Map<String, Any>> = reActLoop.getRecentTraces(n)
    fun getReasoningExamples(n: Int = 5): List<Map<String, Any>> = reActLoop.getReasoningExamples(n)
    fun getReflexionCritiques(n: Int = 10): List<Map<String, Any>> = reflexionLoop.getCritiqueHistory(n)
    fun getReflexionAverageScore(): Double = reflexionLoop.getAverageScore()
    fun getPlanHistory(n: Int = 10): List<Map<String, Any>> = planExecuteLoop.getPlanHistory(n)
    fun getConversationMemory(): ConversationMemory = conversationManager.getConversationMemory()
    fun clearConversationMemory() = conversationManager.clearConversationMemory()

    fun triggerEvolutionCycle() = selfEvolution?.launchEvolutionCycle()
    fun getWorkerPreferences() = selfEvolution?.getPreferences()
    fun getEvolutionMetrics() = selfEvolution?.evolutionMetrics?.value
    suspend fun getGoalAdaptation(goalId: Long, currentTarget: Double, actualProgress: Double, daysElapsed: Int, totalDays: Int) = selfEvolution?.analyzeGoalAdaptation(goalId, currentTarget, actualProgress, daysElapsed, totalDays)
    suspend fun recordAdviceFollowed(adviceId: String) { selfEvolution?.recordAdviceOutcome(adviceId, followed = true, outcomeScore = 0.9) }
    suspend fun getPreferredReportFormat() = preferenceLearner?.getPreferredReportFormat() ?: "daily"
    suspend fun getPreferredVoiceSpeed() = preferenceLearner?.getPreferredVoiceSpeed() ?: 1.0f
    suspend fun getPreferredLanguage() = preferenceLearner?.getPreferredLanguage() ?: "sw"
}

enum class ResponseType {
    CONFIRMATION, INFORMATION, ADVICE, GREETING, HELP, CLARIFICATION, ERROR, UNKNOWN
}

data class AgentResponse(
    val text: String,
    val type: ResponseType,
    val data: Map<String, String> = emptyMap(),
    val shouldSpeak: Boolean = true
)
