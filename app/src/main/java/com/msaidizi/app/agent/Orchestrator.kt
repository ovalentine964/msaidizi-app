package com.msaidizi.app.agent

import com.msaidizi.app.core.model.*
import kotlinx.coroutines.*
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
import com.msaidizi.app.core.language.ConversationLearningPipeline
import com.msaidizi.app.evolution.SelfEvolutionManager
import com.msaidizi.app.agent.autonomy.ProgressiveAutonomy
import com.msaidizi.app.agent.autonomy.Domain
import com.msaidizi.app.agent.autonomy.AgentAction
import com.msaidizi.app.agent.proactive.ProactiveAlertEngine
import com.msaidizi.app.agent.a2a.A2AProtocol
import com.msaidizi.app.agent.a2a.AgentProfile
import com.msaidizi.app.agent.knowledge.CrossDomainKnowledgeGraph
import com.msaidizi.app.social.SocialHandler
import com.msaidizi.app.agent.harness.InferenceHarness
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
    private val conversationLearningPipeline: ConversationLearningPipeline? = null,
    private val reActLoop: ReActLoop = ReActLoop(),
    private val reflexionLoop: ReflexionLoop = ReflexionLoop(),
    private val planExecuteLoop: PlanExecuteLoop = PlanExecuteLoop(),
    private val eventBus: AgentEventBus = AgentEventBus.getInstance(),
    private val llmEngine: LlmEngine? = null,
    // ── Voice Personality ──
    private val voicePersonality: VoicePersonality? = null,
    // ── AGI Components ──
    private val progressiveAutonomy: ProgressiveAutonomy? = null,
    private val proactiveAlertEngine: ProactiveAlertEngine? = null,
    private val a2aProtocol: A2AProtocol? = null,
    private val knowledgeGraph: CrossDomainKnowledgeGraph? = null,
    // ── Social Layer ──
    private val socialHandler: SocialHandler? = null,
    // ── Inference Harness — wraps all model calls with monitoring/fallback/retry ──
    private val inferenceHarness: InferenceHarness? = null,
    // ── AGI Safety Layer ──
    private val agiReadyLayer: com.msaidizi.app.agent.agi.AGIReadyLayer? = null
) {
    private val _responses = MutableSharedFlow<AgentResponse>(extraBufferCapacity = 8)
    val responses: SharedFlow<AgentResponse> = _responses

    init {
        // Wire conversation learning pipeline to conversation manager
        // This enables vocabulary learning from corrections and confirmations
        conversationManager.conversationLearningPipeline = conversationLearningPipeline

        // Sync ProgressiveAutonomy level into AGIReadyLayer
        syncAutonomyLevel()
    }

    /**
     * Process user input and generate a response.
     * Pipeline: classify → context → enhance → route → autonomy check → learn → reflect
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

        // Steps 5-8: Post-processing + output sanitization (defense-in-depth)
        val sanitizedResponse = sanitizeOutput(response, language)
        val critiqueScore = conversationManager.postProcess(enhancedText, intentResult, sanitizedResponse, language, trace)

        // Step 8b: Apply voice personality — warmth, proverbs, cultural flavor
        val personalityResponse = applyPersonality(sanitizedResponse, language)
        _responses.emit(personalityResponse)
        reActLoop.complete(trace, sanitizedResponse.type != ResponseType.ERROR, sanitizedResponse.text)

        conversationManager.publishTaskCompleted(trace, response)

        // Step 9: AGI Safety enforcement — check all safety boundaries
        val safeResponse = enforceSafety(personalityResponse, text, language)

        // Step 10: Progressive autonomy enforcement — check if action needs approval
        val finalResponse = enforceAutonomy(safeResponse, intentResult, language)

        // Step 11: Record autonomy outcome and generate cross-domain insights
        recordAutonomyOutcome(intentResult, response)
        generateCrossDomainInsights()

        return finalResponse
    }

    // ═══════════════ AGI SAFETY ENFORCEMENT ═══════════════

    /**
     * Enforce all AGI safety boundaries on the response.
     * This is the defense-in-depth safety gate that runs AFTER all other processing.
     *
     * Checks:
     * - NO_DECEPTION: blocks responses with false certainty or contradictions
     * - NO_MANIPULATION: strips urgency pressure, FOMO, emotional manipulation
     * - FINANCIAL_ADVICE_DISCLAIMER: auto-injects disclaimer on financial advice
     * - TRANSPARENCY_REQUIRED: adds reasoning when user asks "why" or "how"
     *
     * @see com.msaidizi.app.agent.agi.AGIReadyLayer for boundary definitions
     */
    private fun enforceSafety(
        response: AgentResponse,
        originalInput: String,
        language: String
    ): AgentResponse {
        val agi = agiReadyLayer ?: return response

        val safetyResult = agi.checkResponseSafety(response, originalInput, language)

        return when {
            safetyResult.safe -> response
            safetyResult.blocked -> {
                Timber.w("AGI safety BLOCKED response: boundary=%s reason=%s",
                    safetyResult.violatedBoundary, safetyResult.reason)
                // Return a safe fallback response
                AgentResponse(
                    text = if (language == "sw") {
                        "⚠️ Jibu halikukubaliki kwa sababu za usalama. Tafadhali uliza swali jipya."
                    } else {
                        "⚠️ Response blocked for safety reasons. Please rephrase your question."
                    },
                    type = ResponseType.ERROR,
                    data = mapOf(
                        "safety_blocked" to "true",
                        "boundary" to (safetyResult.violatedBoundary?.name ?: "unknown"),
                        "reason" to safetyResult.reason
                    )
                )
            }
            safetyResult.modifiedResponse != null -> {
                Timber.d("AGI safety MODIFIED response: boundary=%s reason=%s",
                    safetyResult.violatedBoundary, safetyResult.reason)
                safetyResult.modifiedResponse
            }
            else -> response
        }
    }

    /**
     * Enforce progressive autonomy — check if the handler action requires
     * human approval at the current autonomy level.
     *
     * At Level 0 (TOOL): every non-trivial action gets a confirmation prompt.
     * At Level 1 (ASSISTANT): transactions and advice require confirmation.
     * At Level 2 (COLLEAGUE): only high-value or novel actions need approval.
     * At Level 3+ (DELEGATE/AUTONOMOUS): operates independently.
     */
    private fun enforceAutonomy(
        response: AgentResponse,
        intentResult: IntentResult,
        language: String
    ): AgentResponse {
        val agi = agiReadyLayer ?: return response

        // Classify the action
        val isHighValue = intentResult.intent in setOf(
            IntentType.SALE, IntentType.PURCHASE, IntentType.EXPENSE
        )
        val isIrreversible = intentResult.intent in setOf(
            IntentType.LOAN_RECORD, IntentType.GOAL_CREATE
        )
        val actionType = intentResult.intent.name.lowercase().replace('_', ' ')

        if (agi.requiresHumanApproval(actionType, isHighValue, isIrreversible)) {
            val approvalMsg = agi.getApprovalMessage(actionType, language)
            Timber.d("Autonomy level %d: wrapping response with approval request", agi.autonomyState.level)
            return response.copy(
                text = "$approvalMsg\n\n${response.text}",
                data = response.data + ("requires_approval" to "true")
            )
        }

        return response
    }

    /**
     * Sync the ProgressiveAutonomy minimum level into AGIReadyLayer.
     * Called at init and after autonomy changes.
     */
    private fun syncAutonomyLevel() {
        val pa = progressiveAutonomy ?: return
        val agi = agiReadyLayer ?: return

        val minLevel = pa.getMinimumLevel()
        agi.setAutonomyLevelFromProgressive(minLevel)
        Timber.d("Synced autonomy level: %s → AGIReadyLayer level %d",
            minLevel.name, agi.autonomyState.level)
    }

    // ═══════════════ VOICE PERSONALITY — warmth layer ═══════════════

    /**
     * Apply voice personality to make Msaidizi sound like a warm friend.
     * Adds Swahili proverbs, cultural greetings, and encouragement.
     *
     * Applied AFTER sanitization (defense-in-depth) but BEFORE emission.
     *
     * @see VoicePersonality for personality engine details
     */
    private fun applyPersonality(response: AgentResponse, language: String): AgentResponse {
        val personality = voicePersonality ?: return response

        val personalizedText = personality.wrapResponse(
            text = response.text,
            responseType = response.type,
            language = language
        )

        return if (personalizedText != response.text) {
            Timber.d("Voice personality applied: %d → %d chars", response.text.length, personalizedText.length)
            response.copy(text = personalizedText)
        } else {
            response
        }
    }

    // ═══════════════ OUTPUT SANITIZATION — defense-in-depth ═══════════════

    /**
     * Sanitize agent response through 10-layer defense.
     * Applied to ALL responses before emission.
     *
     * @see OutputSanitizer for layer details
     */
    private fun sanitizeOutput(response: AgentResponse, language: String): AgentResponse {
        val sanitized = OutputSanitizer.sanitize(response.text, language)
        return if (sanitized != response.text) {
            Timber.w("Output sanitized for intent: %s (original=%d chars, sanitized=%d chars)",
                response.type, response.text.length, sanitized.length)
            response.copy(text = sanitized)
        } else {
            response
        }
    }

    // ═══════════════ RECEIPT SCANNING ═══════════════

    /**
     * Handle receipt scan intent.
     * Returns a prompt for the UI to launch the camera activity.
     * The actual scanning is handled by ReceiptScanManager (camera → OCR → parse → confirm → save).
     */
    private fun handleReceiptScan(language: String): AgentResponse {
        return AgentResponse(
            text = if (language == "sw") {
                "📸 Piga picha risiti yako. Weka risiti mbele ya kamera na ubonyeze kitufe cha kamera."
            } else {
                "📸 Take a photo of your receipt. Hold the receipt in front of the camera and press the capture button."
            },
            type = ResponseType.INFORMATION,
            shouldSpeak = true,
            data = mapOf("action" to "LAUNCH_RECEIPT_SCANNER")
        )
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

                // Receipt Scanning → returns a prompt to launch camera
                IntentType.RECEIPT_SCAN -> handleReceiptScan(language)

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

    /**
     * Get session welcome with voice personality.
     * Uses contextual greeting based on time of day + worker name.
     */
    fun getSessionWelcome(language: String = "sw"): String? {
        // Try AhaMomentFlow first (existing system)
        val ahaWelcome = ahaMomentFlow?.onSessionStart(language)?.getText(language)

        // If VoicePersonality is available, enhance with contextual greeting
        val personality = voicePersonality
        if (personality != null) {
            val greeting = personality.getGreeting(workerName = "Msaidizi", language = language)
            return if (ahaWelcome != null) {
                "$greeting\n\n$ahaWelcome"
            } else {
                greeting
            }
        }

        return ahaWelcome
    }
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

    // ── Inference Harness metrics ──
    /** Get aggregate inference harness stats (latency, success rate, circuit breakers). */
    fun getInferenceHarnessStats() = inferenceHarness?.getAggregateStats()
    /** Get per-provider inference metrics. */
    fun getInferenceProviderMetrics() = inferenceHarness?.getAllMetrics()
    /** Get circuit breaker states for all providers. */
    fun getCircuitBreakerStates() = inferenceHarness?.getAllCircuitBreakerStates()
    /** Get daily cost breakdown for a user. */
    fun getUserDailyInferenceCosts(userId: String) = inferenceHarness?.getUserDailyCosts(userId)
    fun getConversationMemory(): ConversationMemory = conversationManager.conversationMemory
    fun clearConversationMemory() = conversationManager.clearConversationMemory()

    // ── AGI Component Accessors ──

    /** Get AGI safety layer. */
    fun getAGIReadyLayer() = agiReadyLayer

    /** Get AGI safety audit: current autonomy level, active boundaries, trust score. */
    fun getAGISafetyAudit(): Map<String, Any> {
        val agi = agiReadyLayer ?: return emptyMap()
        val state = agi.autonomyState
        return mapOf(
            "autonomyLevel" to state.level,
            "levelDescription" to agi.describeLevel(),
            "trustScore" to state.trustScore,
            "decisionsTotal" to state.decisionsMade,
            "decisionsCorrect" to state.decisionsCorrect,
            "activeBoundaries" to state.activeBoundaries.map { it.name },
            "capabilities" to state.capabilities.mapKeys { it.key.name }
        )
    }

    /** Get progressive autonomy state. */
    fun getAutonomyState() = progressiveAutonomy?.overallState?.value
    fun getAutonomyDomainStates() = progressiveAutonomy?.getAllDomainStates()
    fun getHumanInTheLoopRequirements() = progressiveAutonomy?.getHumanInTheLoopRequirements() ?: emptyList()

    /** Get proactive alerts. */
    fun getRecentAlerts() = proactiveAlertEngine?.getRecentAlerts() ?: emptyList()
    fun startProactiveMonitoring() = proactiveAlertEngine?.startMonitoring()
    fun stopProactiveMonitoring() = proactiveAlertEngine?.stopMonitoring()

    /** Get A2A protocol metrics. */
    fun getA2AMetrics() = a2aProtocol?.getMetrics()
    fun discoverAgents(capability: String) = a2aProtocol?.discoverAgents(capability) ?: emptyList()

    /** Get knowledge graph stats. */
    fun getKnowledgeGraphStats() = knowledgeGraph?.getStats()
    fun getCrossDomainInsights(limit: Int = 20) = knowledgeGraph?.getInsights(limit) ?: emptyList()
    fun getKnowledgeContext(topic: String) = knowledgeGraph?.getContextForTopic(topic) ?: ""

    fun triggerEvolutionCycle() = selfEvolution?.launchEvolutionCycle()
    fun getWorkerPreferences() = selfEvolution?.getPreferences()
    fun getEvolutionMetrics() = selfEvolution?.evolutionMetrics?.value
    suspend fun getGoalAdaptation(goalId: Long, currentTarget: Double, actualProgress: Double, daysElapsed: Int, totalDays: Int) = selfEvolution?.analyzeGoalAdaptation(goalId, currentTarget, actualProgress, daysElapsed, totalDays)
    suspend fun recordAdviceFollowed(adviceId: String) { selfEvolution?.recordAdviceOutcome(adviceId, followed = true, outcomeScore = 0.9) }
    suspend fun getPreferredReportFormat() = preferenceLearner?.getPreferredReportFormat() ?: "daily"
    suspend fun getPreferredVoiceSpeed() = preferenceLearner?.getPreferredVoiceSpeed() ?: 1.0f
    suspend fun getPreferredLanguage() = preferenceLearner?.getPreferredLanguage() ?: "sw"

    // ═══════════════ AGI INTEGRATION ═══════════════

    /**
     * Initialize AGI components: register agents, start monitoring.
     * Call after construction.
     */
    fun initializeAGI() {
        // Register orchestrator as an A2A agent
        a2aProtocol?.registerAgent(AgentProfile(
            agentId = "orchestrator",
            displayName = "Orchestrator",
            capabilities = listOf("routing", "intent_classification", "response_generation"),
            priority = 1000
        ))

        // Register domain agents
        a2aProtocol?.registerAgent(AgentProfile(
            agentId = "business-agent",
            displayName = "Business Agent",
            capabilities = listOf("transaction_recording", "sales_analysis"),
            priority = 900
        ))
        a2aProtocol?.registerAgent(AgentProfile(
            agentId = "analysis-agent",
            displayName = "Analysis Agent",
            capabilities = listOf("trend_analysis", "anomaly_detection", "forecasting"),
            priority = 800
        ))
        a2aProtocol?.registerAgent(AgentProfile(
            agentId = "advisor-agent",
            displayName = "Advisor Agent",
            capabilities = listOf("advice", "recommendations", "financial_planning"),
            priority = 700
        ))

        // Start proactive monitoring
        proactiveAlertEngine?.startMonitoring()

        Timber.d("AGI components initialized")
    }

    /**
     * Record autonomy outcome after processing.
     * Updates both ProgressiveAutonomy and AGIReadyLayer trust scores.
     */
    private fun recordAutonomyOutcome(intentResult: IntentResult, response: AgentResponse) {
        val wasCorrect = response.type != ResponseType.ERROR
        val wasCritical = intentResult.intent in setOf(
            IntentType.SALE, IntentType.PURCHASE, IntentType.EXPENSE
        ) && !wasCorrect

        // Update ProgressiveAutonomy
        val autonomy = progressiveAutonomy
        if (autonomy != null) {
            val domain = mapIntentToDomain(intentResult.intent)
            autonomy.recordOutcome(domain, intentResult.intent.name, wasCorrect, wasCritical)
        }

        // Update AGIReadyLayer trust and capability
        val agi = agiReadyLayer
        if (agi != null) {
            val capability = mapIntentToCapability(intentResult.intent)
            if (capability != null) {
                val newState = agi.updateCapability(agi.autonomyState, capability, wasCorrect)
                agi.updateAutonomyState { newState }
            }
            // Sync autonomy level after outcome
            syncAutonomyLevel()
        }
    }

    /**
     * Map an intent type to an AGIReadyLayer capability.
     */
    private fun mapIntentToCapability(intent: IntentType): com.msaidizi.app.agent.agi.AGIReadyLayer.Capability? {
        return when (intent) {
            IntentType.SALE, IntentType.PURCHASE, IntentType.EXPENSE ->
                com.msaidizi.app.agent.agi.AGIReadyLayer.Capability.TRANSACTION_RECORDING
            IntentType.PROFIT_QUERY, IntentType.CHECK_BALANCE, IntentType.DAILY_SUMMARY, IntentType.WEEKLY_SUMMARY ->
                com.msaidizi.app.agent.agi.AGIReadyLayer.Capability.CASH_FLOW_PREDICTION
            IntentType.ASK_ADVICE ->
                com.msaidizi.app.agent.agi.AGIReadyLayer.Capability.BUSINESS_ADVICE
            IntentType.GOAL_CREATE, IntentType.GOAL_PROGRESS, IntentType.GOAL_REPORT ->
                com.msaidizi.app.agent.agi.AGIReadyLayer.Capability.GOAL_MANAGEMENT
            IntentType.LOAN_RECORD, IntentType.LOAN_QUERY, IntentType.LOAN_REPORT ->
                com.msaidizi.app.agent.agi.AGIReadyLayer.Capability.LOAN_ANALYSIS
            IntentType.STOCK_QUERY ->
                com.msaidizi.app.agent.agi.AGIReadyLayer.Capability.RISK_ASSESSMENT
            else -> null
        }
    }

    /**
     * Map an intent type to an autonomy domain.
     */
    private fun mapIntentToDomain(intent: IntentType): Domain {
        return when (intent) {
            IntentType.SALE, IntentType.PURCHASE -> Domain.SALES
            IntentType.EXPENSE, IntentType.PROFIT_QUERY, IntentType.CHECK_BALANCE -> Domain.FINANCE
            IntentType.STOCK_QUERY -> Domain.INVENTORY
            IntentType.DAILY_SUMMARY, IntentType.WEEKLY_SUMMARY,
            IntentType.GOAL_REPORT, IntentType.LOAN_REPORT -> Domain.REPORTING
            IntentType.ASK_ADVICE, IntentType.GIVING_RECORD,
            IntentType.GIVING_QUERY, IntentType.GIVING_GOAL -> Domain.GIVING
            IntentType.RECEIPT_SCAN -> Domain.SALES
            else -> Domain.ADVICE
        }
    }

    /**
     * Generate cross-domain insights from knowledge graph.
     */
    private fun generateCrossDomainInsights() {
        knowledgeGraph?.let { kg ->
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    kg.generateInsights()
                } catch (e: Exception) {
                    Timber.w(e, "Cross-domain insight generation failed")
                }
            }
        }
    }
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
