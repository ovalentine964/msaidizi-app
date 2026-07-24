package com.msaidizi.app.superagent.engine

import com.msaidizi.app.superagent.context.ContextEngine
import com.msaidizi.app.superagent.flywheel.FlywheelEngine
import com.msaidizi.app.superagent.flywheel.FlywheelModels
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * ReasoningEngine — The unified brain of the Msaidizi superagent.
 *
 * Implements the OODA (Observe → Orient → Decide → Act) reasoning loop.
 * This is the **single entry point** for all user input, replacing the old
 * Orchestrator + IntentRouter + Agent + Handler chain.
 *
 * ## Why OODA?
 *
 * John Boyd's OODA Loop is the reasoning pattern of fighter pilots who need
 * to make decisions faster than the enemy. The key insight: **speed comes
 * from better orientation, not faster action.**
 *
 * For Msaidizi:
 * - **Observe** — collect voice, market, worker context, proactive signals
 * - **Orient** — parse intent, contextualize with history, analyze patterns
 * - **Decide** — confidence gating, module routing, action selection
 * - **Act** — execute module, safety check, voice output, learning signal
 *
 * ## Performance Budget
 * - Code path (pattern match): 50–200ms
 * - LLM path (on-device fallback): 500–2000ms
 *
 * ## Key Differences from Old Orchestrator
 * - No AgentEventBus — direct module calls
 * - No A2A Protocol — in-process function calls
 * - No separate Agent classes — modules are skills, not agents
 * - ~18 constructor params, not 40+
 * - Unified context engine, not per-agent memory
 * - Integrated learning via FlywheelEngine
 *
 * @param intentClassifier Classifies text into intents (code-first, 90% no LLM)
 * @param dialectNormalizer Expands Sheng/dialect to standard Swahili
 * @param dataCompletenessChecker Ensures backend gets complete data (M-KOPA model)
 * @param safetyGuard Checks responses for harmful content
 * @param autonomyManager Progressive autonomy level management
 * @param financialModule Handles sales, purchases, expenses, queries
 * @param creditModule Handles loans, credit scoring, debt advice
 * @param goalsModule Handles goals, tithe, savings targets
 * @param educationModule Handles advice, greetings, mindset lessons
 * @param gamificationModule Handles badges, leaderboards, streaks
 * @param communicationModule Personalizes responses with warmth and dialect
 * @param contextEngine Shared context (L1 working, L2 episodic, L3 semantic, L4 procedural)
 * @param flywheel Learning flywheel (Use → Learn → Improve → Use More)
 * @param llmEngine LLM fallback for ambiguous inputs
 * @param workerSignalProvider Collects worker context signals
 * @param marketSignalProvider Collects market data signals
 * @param proactiveSignalProvider Collects proactive alert signals
 */
class ReasoningEngine(
    // ── Core reasoning ──
    private val intentClassifier: IntentClassifier,
    private val dialectNormalizer: DialectNormalizer,
    private val dataCompletenessChecker: DataCompletenessChecker,
    private val safetyGuard: SafetyGuard,
    private val autonomyManager: AutonomyManager,

    // ── Capability modules (direct references, no event bus) ──
    private val financialModule: CapabilityModule,
    private val creditModule: CapabilityModule,
    private val goalsModule: CapabilityModule,
    private val educationModule: CapabilityModule,
    private val gamificationModule: CapabilityModule,
    private val communicationModule: CommunicationModule,

    // ── Shared systems ──
    private val contextEngine: ContextEngine,
    private val flywheel: FlywheelEngine,
    private val llmEngine: LlmEngine,

    // ── Signal providers ──
    private val workerSignalProvider: WorkerSignalProvider,
    private val marketSignalProvider: MarketSignalProvider,
    private val proactiveSignalProvider: ProactiveSignalProvider
) {
    companion object {
        private const val TAG = "ReasoningEngine"

        /** Intent classified with high confidence — route directly */
        const val HIGH_CONFIDENCE_THRESHOLD = 0.85f

        /** Intent classified with medium confidence — may need confirmation */
        const val MEDIUM_CONFIDENCE_THRESHOLD = 0.60f

        /** Below this — request clarification */
        const val CLARIFICATION_THRESHOLD = 0.40f

        // ── Intent groups for module routing ──
        private val FINANCIAL_INTENTS = setOf(
            "SALE", "PURCHASE", "EXPENSE",
            "PROFIT_QUERY", "CHECK_BALANCE", "STOCK_QUERY",
            "DAILY_SUMMARY", "WEEKLY_SUMMARY", "MONTHLY_SUMMARY",
            "RECEIPT_SCAN", "INVENTORY_CHECK"
        )
        private val CREDIT_INTENTS = setOf(
            "LOAN_RECORD", "LOAN_QUERY", "LOAN_REPORT",
            "LOAN_DEADLINE", "CREDIT_SCORE", "DEBT_ADVICE"
        )
        private val GOALS_INTENTS = setOf(
            "GOAL_CREATE", "GOAL_PROGRESS", "GOAL_REPORT",
            "GOAL_TIME_FORECAST", "GOAL_ADJUST", "GOAL_ENCOURAGEMENT",
            "GIVING_RECORD", "GIVING_QUERY", "GIVING_GOAL"
        )
        private val EDUCATION_INTENTS = setOf(
            "ASK_ADVICE", "GREETING", "HELP",
            "MINDSET_LESSON", "HABITS_CHECK"
        )
        private val GAMIFICATION_INTENTS = setOf(
            "BADGE_QUERY", "LEADERBOARD", "STREAK_CHECK"
        )
        private val TRANSACTION_INTENTS = setOf(
            "SALE", "PURCHASE", "EXPENSE"
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // PUBLIC ENTRY POINTS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Process a voice input through the full OODA loop.
     *
     * This is the primary entry point for voice interactions.
     * The voice pipeline calls this after STT produces text.
     *
     * @param sttResult The text from speech-to-text
     * @param voiceSignal Voice-specific signals (confidence, emotion, etc.)
     * @return The complete OODA result with response, proof point, and learning signal
     */
    suspend fun processVoice(sttResult: String, voiceSignal: VoiceSignal): OodaResult {
        val startTime = System.currentTimeMillis()
        Timber.d(TAG, "processVoice: '$sttResult' (confidence=${voiceSignal.asrConfidence})")

        // ── OBSERVE ──
        val observation = observe(
            text = sttResult,
            voice = voiceSignal,
            triggerType = TriggerType.VOICE_INPUT
        )

        // ── Data completeness check (M-KOPA: collect RIGHT data) ──
        val completeness = dataCompletenessChecker.check(observation)
        if (!completeness.isComplete) {
            Timber.d(TAG, "Incomplete data, asking: ${completeness.followUpQuestion}")
            return OodaResult(
                response = AgentResponse(
                    text = completeness.followUpQuestion ?: "Bei ngapi?",
                    type = ResponseType.CLARIFICATION,
                    shouldSpeak = true
                ),
                actionType = ActionType.CLARIFICATION,
                cycleTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // ── ORIENT → DECIDE → ACT ──
        return runLoop(observation, startTime)
    }

    /**
     * Process a text input through the full OODA loop.
     *
     * Used for text-based interactions (USSD, app text input, WhatsApp text).
     *
     * @param text The input text
     * @param language The worker's language
     * @return The complete OODA result
     */
    suspend fun processText(text: String, language: String = "sw"): OodaResult {
        val startTime = System.currentTimeMillis()
        Timber.d(TAG, "processText: '$text' (lang=$language)")

        val observation = observe(
            text = text,
            voice = null,
            triggerType = TriggerType.TEXT_INPUT
        )

        val completeness = dataCompletenessChecker.check(observation)
        if (!completeness.isComplete) {
            return OodaResult(
                response = AgentResponse(
                    text = completeness.followUpQuestion ?: "Bei ngapi?",
                    type = ResponseType.CLARIFICATION,
                    shouldSpeak = false
                ),
                actionType = ActionType.CLARIFICATION,
                cycleTimeMs = System.currentTimeMillis() - startTime
            )
        }

        return runLoop(observation, startTime)
    }

    /**
     * Process a proactive trigger (system-initiated, not user-initiated).
     *
     * Used for alerts, reminders, and nudges triggered by scheduled jobs.
     *
     * @param trigger The proactive trigger type
     * @param relatedData Data related to the trigger
     * @return The OODA result (response to deliver proactively)
     */
    suspend fun processProactive(
        trigger: ProactiveTrigger,
        relatedData: Map<String, Any> = emptyMap()
    ): OodaResult {
        val startTime = System.currentTimeMillis()
        Timber.d(TAG, "processProactive: $trigger")

        val observation = observe(
            text = "",
            voice = null,
            triggerType = TriggerType.PROACTIVE
        ).copy(
            proactive = ProactiveSignal(
                trigger = trigger,
                urgency = Urgency.MEDIUM,
                message = relatedData["message"]?.toString() ?: "",
                relatedData = relatedData
            )
        )

        val orientation = orient(observation)
        val actionPlan = decide(orientation, observation)
        return act(actionPlan, observation, orientation).copy(
            cycleTimeMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * Handle a correction from the worker.
     *
     * When the worker corrects a previous response ("Sio mandazi, ni maandazi"),
     * re-enters the OODA loop with the correction as new input.
     *
     * @param correctionInput The correction text
     * @param previousResult The result being corrected
     * @param language The worker's language
     * @return New OODA result with the correction applied
     */
    suspend fun handleCorrection(
        correctionInput: String,
        previousResult: OodaResult,
        language: String
    ): OodaResult {
        Timber.d(TAG, "handleCorrection: '$correctionInput'")
        // Record the correction as feedback for the flywheel
        flywheel.recordCorrection(
            originalInput = previousResult.learningSignal?.input ?: "",
            correctionInput = correctionInput,
            originalIntent = previousResult.learningSignal?.intent ?: ""
        )

        // Re-enter OBSERVE with the correction as new input
        val observation = observe(
            text = correctionInput,
            voice = null,
            triggerType = TriggerType.FOLLOW_UP
        )

        // Run the full loop again
        val startTime = System.currentTimeMillis()
        val orientation = orient(observation)
        val actionPlan = decide(orientation, observation)
        return act(actionPlan, observation, orientation).copy(
            cycleTimeMs = System.currentTimeMillis() - startTime
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // THE OODA LOOP — Core Implementation
    // ═══════════════════════════════════════════════════════════════

    /**
     * The core loop: OBSERVE → ORIENT → DECIDE → ACT
     */
    private suspend fun runLoop(observation: Observation, startTime: Long): OodaResult {
        return try {
            // ── ORIENT ──
            val orientation = orient(observation)

            // ── DECIDE ──
            val actionPlan = decide(orientation, observation)

            // ── LLM escalation if needed ──
            if (actionPlan.actionType == ActionType.LLM_ESCALATION) {
                return escalateToLlm(observation, orientation).copy(
                    cycleTimeMs = System.currentTimeMillis() - startTime
                )
            }

            // ── ACT ──
            act(actionPlan, observation, orientation).copy(
                cycleTimeMs = System.currentTimeMillis() - startTime
            )
        } catch (e: OrientationException) {
            Timber.e(e, "Orientation failed, using fallback")
            OodaResult(
                response = AgentResponse(
                    text = generateFallbackResponse(observation),
                    type = ResponseType.INFORMATION,
                    shouldSpeak = observation.triggerType == TriggerType.VOICE_INPUT
                ),
                actionType = ActionType.RESPOND,
                cycleTimeMs = System.currentTimeMillis() - startTime
            )
        } catch (e: ModuleExecutionException) {
            Timber.e(e, "Module execution failed, trying LLM")
            try {
                escalateToLlm(observation, orient(observation)).copy(
                    cycleTimeMs = System.currentTimeMillis() - startTime
                )
            } catch (e2: Exception) {
                OodaResult(
                    response = AgentResponse(
                        text = "Pole, kuna tatizo. Jaribu tena.",
                        type = ResponseType.ERROR,
                        shouldSpeak = true
                    ),
                    actionType = ActionType.RESPOND,
                    cycleTimeMs = System.currentTimeMillis() - startTime
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "OODA loop failed")
            OodaResult(
                response = AgentResponse(
                    text = "Pole, sijaelewa. Jaribu tena.",
                    type = ResponseType.ERROR,
                    shouldSpeak = observation.triggerType == TriggerType.VOICE_INPUT
                ),
                actionType = ActionType.RESPOND,
                cycleTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PHASE 1: OBSERVE — Collect all signals
    // ═══════════════════════════════════════════════════════════════

    /**
     * OBSERVE: Collect all available signals into a structured observation.
     * No reasoning happens here — just data gathering.
     *
     * Signal collection is parallel with priority cutoffs:
     * - CRITICAL signals: always collected
     * - HIGH signals: collected with timeout (skip if too slow)
     */
    private suspend fun observe(
        text: String,
        voice: VoiceSignal?,
        triggerType: TriggerType
    ): Observation = coroutineScope {
        // CRITICAL signals: collect in parallel, wait for all
        val workerDeferred = async {
            try {
                workerSignalProvider.observe(text)
            } catch (e: Exception) {
                Timber.w(e, "WorkerSignalProvider failed")
                WorkerSignal.empty()
            }
        }

        // HIGH signals: collect in parallel, wait with timeout
        val marketDeferred = async {
            try {
                withTimeoutOrNull(marketSignalProvider.maxLatencyMs) {
                    marketSignalProvider.observe(text)
                } ?: MarketSignal.empty()
            } catch (e: Exception) {
                Timber.w(e, "MarketSignalProvider failed")
                MarketSignal.empty()
            }
        }

        val proactiveDeferred = async {
            try {
                withTimeoutOrNull(proactiveSignalProvider.maxLatencyMs) {
                    proactiveSignalProvider.observe(text)
                }
            } catch (e: Exception) {
                Timber.w(e, "ProactiveSignalProvider failed")
                null
            }
        }

        // Wait for all signals
        val workerSignal = workerDeferred.await()
        val marketSignal = marketDeferred.await()
        val proactiveSignal = proactiveDeferred.await()

        Observation(
            voice = voice,
            text = text,
            language = voice?.language ?: detectLanguage(text),
            dialect = voice?.dialect ?: "standard",
            market = marketSignal,
            worker = workerSignal,
            proactive = proactiveSignal,
            triggerType = triggerType
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // PHASE 2: ORIENT — Transform observations into meaning
    // ═══════════════════════════════════════════════════════════════

    /**
     * ORIENT: Transform raw observations into meaning.
     *
     * This is the most important phase — it's where Msaidizi understands
     * what's happening for *this specific worker* in *this specific context*.
     *
     * Four layers:
     * 1. **Parse** — extract structured data from raw text
     * 2. **Contextualize** — enrich with worker history and patterns
     * 3. **Analyze** — detect anomalies, opportunities, risks
     * 4. **Align** — map to worker goals and system objectives
     */
    private suspend fun orient(observation: Observation): Orientation {
        // ── Layer 1: PARSE ──
        val parseResult = parse(observation.text, observation.language)

        // ── Layer 2: CONTEXTUALIZE ──
        val contextualized = contextualize(parseResult, observation)

        // ── Layer 3: ANALYZE ──
        val analysis = analyze(contextualized, observation)

        // ── Layer 4: ALIGN ──
        return align(contextualized, analysis, observation)
    }

    /**
     * Parse raw text into structured intent + extracted data.
     * Code-first: pattern matching, regex, dictionary lookup.
     * LLM fallback only when confidence < threshold.
     */
    private suspend fun parse(text: String, language: String): ParseResult {
        // Step 1: Normalize (expand Sheng, fix dialect)
        val normalized = dialectNormalizer.normalize(text, language)

        // Step 2: Pattern match (90% hit rate)
        val patternResult = intentClassifier.classify(normalized)
        if (patternResult.confidence >= HIGH_CONFIDENCE_THRESHOLD) {
            return patternResult
        }

        // Step 3: Fuzzy match (handles typos, partial input)
        val fuzzyResult = intentClassifier.fuzzyMatch(normalized)
        if (fuzzyResult.confidence >= MEDIUM_CONFIDENCE_THRESHOLD) {
            return fuzzyResult
        }

        // Step 4: LLM fallback (complex, ambiguous input)
        return llmEngine.classify(normalized, language)
    }

    /**
     * Enrich parsed data with worker context.
     * This is what makes Msaidizi a superagent — it knows THIS worker.
     */
    private fun contextualize(parseResult: ParseResult, observation: Observation): ContextualizedIntent {
        val worker = observation.worker

        // Baseline comparison: how does this compare to normal?
        val baseline: BaselineComparison? = if (parseResult.intent in TRANSACTION_INTENTS) {
            val amount = parseResult.extractedData["amount"] as? Double ?: 0.0
            val avg = worker.dailyAverage
            if (avg > 0) {
                BaselineComparison(
                    average = avg,
                    isAboveAverage = amount > avg,
                    deviation = if (avg > 0) (amount - avg) / avg else 0.0
                )
            } else null
        } else null

        // Market context: is the price normal?
        val marketContext: MarketComparison? = if (parseResult.intent in TRANSACTION_INTENTS) {
            val item = parseResult.extractedData["item"] as? String
            val recordedPrice = parseResult.extractedData["amount"] as? Double
            val marketPrice = item?.let { observation.market.relevantPrices[it] }
            if (marketPrice != null && recordedPrice != null && marketPrice > 0) {
                MarketComparison(
                    marketPrice = marketPrice,
                    recordedPrice = recordedPrice,
                    priceDeviation = (recordedPrice - marketPrice) / marketPrice
                )
            } else null
        } else null

        return ContextualizedIntent(
            parsed = parseResult,
            baseline = baseline,
            marketContext = marketContext,
            workerState = WorkerState(
                streakDays = worker.streakDays,
                alamaTier = worker.alamaTier,
                recentTrend = worker.weeklyTrend,
                daysSinceLastInteraction = daysSince(worker.lastInteractionTime)
            )
        )
    }

    /**
     * Analyze the contextualized intent for anomalies, opportunities, and risks.
     */
    private fun analyze(contextualized: ContextualizedIntent, observation: Observation): Analysis {
        val signals = mutableListOf<Signal>()

        // ── ANOMALY DETECTION ──
        if (contextualized.parsed.intent in TRANSACTION_INTENTS) {
            val baseline = contextualized.baseline
            if (baseline != null && baseline.deviation < -0.3) {
                signals.add(Signal(
                    type = SignalType.ANOMALY,
                    severity = Severity.MEDIUM,
                    message = "Sales ${(-baseline.deviation * 100).toInt()}% below average",
                    data = mapOf("deviation" to baseline.deviation)
                ))
            }
        }

        // Price anomaly?
        contextualized.marketContext?.let { market ->
            if (kotlin.math.abs(market.priceDeviation) > 0.2) {
                signals.add(Signal(
                    type = if (market.priceDeviation > 0) SignalType.OVERPRICE else SignalType.UNDERPRICE,
                    severity = Severity.LOW,
                    message = "Price ${if (market.priceDeviation > 0) "above" else "below"} market by ${(kotlin.math.abs(market.priceDeviation) * 100).toInt()}%",
                    data = mapOf("deviation" to market.priceDeviation)
                ))
            }
        }

        // ── RISK DETECTION ──
        if (contextualized.workerState.daysSinceLastInteraction > 1) {
            signals.add(Signal(
                type = SignalType.STREAK_RISK,
                severity = if (contextualized.workerState.daysSinceLastInteraction > 2) Severity.HIGH else Severity.MEDIUM,
                message = "Streak: ${contextualized.workerState.streakDays} days. Last interaction: ${contextualized.workerState.daysSinceLastInteraction} days ago.",
                data = mapOf("daysSince" to contextualized.workerState.daysSinceLastInteraction)
            ))
        }

        // ── OPPORTUNITY DETECTION ──
        observation.market.priceAnomalies.forEach { anomaly ->
            if (anomaly.direction == PriceDirection.DOWN) {
                signals.add(Signal(
                    type = SignalType.OPPORTUNITY,
                    severity = Severity.LOW,
                    message = "${anomaly.product} price dropped ${(anomaly.changePercent * 100).toInt()}%",
                    data = mapOf("product" to anomaly.product, "drop" to anomaly.changePercent)
                ))
            }
        }

        return Analysis(
            signals = signals,
            hasAnomaly = signals.any { it.type == SignalType.ANOMALY },
            hasOpportunity = signals.any { it.type == SignalType.OPPORTUNITY },
            hasRisk = signals.any { it.type in listOf(SignalType.STREAK_RISK, SignalType.STOCK_LOW) }
        )
    }

    /**
     * Align the current situation with worker goals and system objectives.
     * Produces the Orientation — the full understanding of what's happening.
     */
    private fun align(
        contextualized: ContextualizedIntent,
        analysis: Analysis,
        observation: Observation
    ): Orientation {
        val urgency = when {
            analysis.signals.any { it.severity == Severity.CRITICAL } -> Urgency.CRITICAL
            analysis.signals.any { it.severity == Severity.HIGH } -> Urgency.HIGH
            analysis.signals.any { it.severity == Severity.MEDIUM } -> Urgency.MEDIUM
            else -> Urgency.LOW
        }

        return Orientation(
            intent = contextualized.parsed.intent,
            extractedData = contextualized.parsed.extractedData,
            confidence = contextualized.parsed.confidence,
            parseMethod = contextualized.parsed.method,
            analysis = analysis,
            urgency = urgency,
            workerState = contextualized.workerState,
            baseline = contextualized.baseline,
            marketContext = contextualized.marketContext
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // PHASE 3: DECIDE — Select the action to take
    // ═══════════════════════════════════════════════════════════════

    /**
     * DECIDE: Select the action to take based on the orientation.
     *
     * Decision tree:
     * 1. Confidence check — below threshold? Request clarification.
     * 2. Urgency override — critical? Skip to alert.
     * 3. Module routing — which module handles this intent?
     * 4. Action type selection — respond, alert, celebrate?
     * 5. Proactive additions — should we add unsolicited info?
     */
    private fun decide(orientation: Orientation, observation: Observation): ActionPlan {
        // ── CONFIDENCE GATE ──
        if (orientation.confidence < CLARIFICATION_THRESHOLD) {
            return ActionPlan(
                module = null,
                actionType = ActionType.CLARIFICATION,
                responseType = ResponseType.CLARIFICATION,
                confidence = orientation.confidence,
                priority = Priority.HIGH,
                clarificationQuestion = generateClarification(orientation, observation.language)
            )
        }

        // ── URGENCY OVERRIDE ──
        if (orientation.urgency == Urgency.CRITICAL) {
            val module = selectModule(orientation.intent)
            return ActionPlan(
                module = module,
                actionType = ActionType.ALERT_ONLY,
                responseType = ResponseType.ALERT,
                confidence = orientation.confidence,
                priority = Priority.CRITICAL
            )
        }

        // ── MODULE ROUTING ──
        val module = selectModule(orientation.intent)
        if (module == null) {
            return ActionPlan(
                module = null,
                actionType = ActionType.LLM_ESCALATION,
                responseType = ResponseType.LLM_GENERATED,
                confidence = orientation.confidence,
                priority = Priority.MEDIUM
            )
        }

        // ── ACTION TYPE SELECTION ──
        val actionType = selectActionType(orientation)

        // ── PROACTIVE ADDITIONS ──
        val proactiveAdditions = selectProactiveAdditions(orientation, observation)

        return ActionPlan(
            module = module,
            actionType = actionType,
            responseType = selectResponseType(actionType),
            confidence = orientation.confidence,
            priority = orientation.urgency.toPriority(),
            proactiveAdditions = proactiveAdditions
        )
    }

    /**
     * Select which capability module handles this intent.
     * Direct function calls — no event bus, no routing overhead.
     */
    private fun selectModule(intent: String): CapabilityModule? {
        return when (intent) {
            in FINANCIAL_INTENTS -> financialModule
            in CREDIT_INTENTS -> creditModule
            in GOALS_INTENTS -> goalsModule
            in EDUCATION_INTENTS -> educationModule
            in GAMIFICATION_INTENTS -> gamificationModule
            else -> null  // triggers LLM escalation
        }
    }

    /**
     * Select the action type based on analysis signals.
     */
    private fun selectActionType(orientation: Orientation): ActionType {
        val hasAnomaly = orientation.analysis.hasAnomaly
        val hasRisk = orientation.analysis.hasRisk
        val hasOpportunity = orientation.analysis.hasOpportunity

        return when {
            hasAnomaly && hasRisk -> ActionType.RESPOND_AND_ALERT
            hasOpportunity -> ActionType.RESPOND_AND_SUGGEST
            orientation.intent in TRANSACTION_INTENTS -> ActionType.RESPOND_AND_RECORD
            else -> ActionType.RESPOND
        }
    }

    /**
     * Select proactive additions — unsolicited info to attach.
     */
    private fun selectProactiveAdditions(
        orientation: Orientation,
        observation: Observation
    ): List<ProactiveAddition> {
        val additions = mutableListOf<ProactiveAddition>()

        // Streak at risk?
        if (orientation.workerState.daysSinceLastInteraction > 1) {
            additions.add(ProactiveAddition(
                type = AdditionType.ENCOURAGEMENT,
                content = "Streak yako ni siku ${orientation.workerState.streakDays}! Usipoteze."
            ))
        }

        // Market opportunity?
        orientation.analysis.signals
            .filter { it.type == SignalType.OPPORTUNITY }
            .forEach { signal ->
                additions.add(ProactiveAddition(
                    type = AdditionType.SUGGESTION,
                    content = signal.message
                ))
            }

        return additions
    }

    private fun selectResponseType(actionType: ActionType): ResponseType {
        return when (actionType) {
            ActionType.RESPOND_AND_RECORD -> ResponseType.TRANSACTION_CONFIRMATION
            ActionType.RESPOND_AND_ALERT -> ResponseType.ALERT
            ActionType.RESPOND_AND_SUGGEST -> ResponseType.ADVICE
            ActionType.RESPOND_AND_CELEBRATE -> ResponseType.CELEBRATION
            ActionType.CLARIFICATION -> ResponseType.CLARIFICATION
            ActionType.ALERT_ONLY -> ResponseType.ALERT
            ActionType.LLM_ESCALATION -> ResponseType.LLM_GENERATED
            ActionType.RESPOND -> ResponseType.INFORMATION
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PHASE 4: ACT — Execute the decision
    // ═══════════════════════════════════════════════════════════════

    /**
     * ACT: Execute the decision.
     *
     * Sequence:
     * 1. Execute module (direct call)
     * 2. Safety check
     * 3. Autonomy check
     * 4. Personalize (warmth, proverbs, dialect)
     * 5. Record proof point (for Alama Score)
     * 6. Store in context engine
     * 7. Capture learning signal (for flywheel)
     */
    private suspend fun act(
        actionPlan: ActionPlan,
        observation: Observation,
        orientation: Orientation
    ): OodaResult {
        // ── 1. CLARIFICATION SHORT-CIRCUIT ──
        if (actionPlan.actionType == ActionType.CLARIFICATION) {
            return OodaResult(
                response = AgentResponse(
                    text = actionPlan.clarificationQuestion ?: "Sema tena?",
                    type = ResponseType.CLARIFICATION,
                    shouldSpeak = observation.triggerType == TriggerType.VOICE_INPUT
                ),
                actionType = ActionType.CLARIFICATION
            )
        }

        // ── 2. EXECUTE MODULE ──
        val resolvedIntent = ResolvedIntent(
            intent = orientation.intent,
            extractedData = orientation.extractedData,
            confidence = orientation.confidence,
            context = orientation
        )

        val moduleResponse = try {
            actionPlan.module!!.handle(resolvedIntent)
        } catch (e: Exception) {
            Timber.e(e, "Module execution failed")
            throw ModuleExecutionException("Module failed: ${e.message}", e)
        }

        // ── 3. SAFETY CHECK ──
        val safeResponse = safetyGuard.check(
            response = moduleResponse,
            originalInput = observation.text,
            language = observation.language
        )

        // ── 4. AUTONOMY CHECK ──
        val approvedResponse = autonomyManager.check(safeResponse, orientation)

        // ── 5. PERSONALIZE ──
        val personalized = communicationModule.personalize(approvedResponse, observation.language)

        // ── 6. ATTACH PROACTIVE ADDITIONS ──
        val withProactive = attachProactiveAdditions(personalized, actionPlan.proactiveAdditions)

        // ── 7. RECORD PROOF ──
        val proofPoint = if (orientation.intent in TRANSACTION_INTENTS) {
            FlywheelModels.ProofPoint(
                type = FlywheelModels.ProofType.TRANSACTION,
                weight = 1.0,
                data = orientation.extractedData
            ).also { flywheel.recordProof(it) }
        } else null

        // ── 8. STORE CONTEXT ──
        contextEngine.storeInteraction(
            input = observation.text,
            intent = orientation.intent,
            response = withProactive.text
        )

        // ── 9. CAPTURE LEARNING SIGNAL ──
        val learningSignal = LearningSignal(
            input = observation.text,
            intent = orientation.intent,
            confidence = orientation.confidence,
            parseMethod = orientation.parseMethod,
            actionType = actionPlan.actionType,
            module = actionPlan.module?.let { it::class.simpleName },
            signals = orientation.analysis.signals.map { it.type }
        )
        flywheel.recordLearningSignal(learningSignal)

        return OodaResult(
            response = withProactive,
            proofPoint = proofPoint,
            learningSignal = learningSignal,
            actionType = actionPlan.actionType,
            orientation = orientation
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // LLM ESCALATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * LLM escalation: when pattern matching fails, use the language model.
     * This is the fallback path — 90% of inputs don't reach here.
     */
    private suspend fun escalateToLlm(
        observation: Observation,
        orientation: Orientation
    ): OodaResult {
        Timber.d(TAG, "Escalating to LLM for: '${observation.text}'")

        val llmContext = LlmContext(
            input = observation.text,
            language = observation.language,
            workerProfile = contextEngine.getWorkerSummary(),
            recentTransactions = contextEngine.getRecentTransactionSummary(),
            conversationHistory = contextEngine.getConversationSummary()
        )

        val llmResult = llmEngine.classifyAndRespond(llmContext)
        val parsed = llmResult.toParsedIntent()

        // Re-enter DECIDE with the LLM's classification
        val updatedOrientation = orientation.copy(
            intent = parsed.intent,
            extractedData = parsed.extractedData,
            confidence = parsed.confidence,
            parseMethod = ParseMethod.LLM
        )

        val actionPlan = decide(updatedOrientation, observation)

        // If LLM also can't figure it out, return the LLM's response directly
        if (actionPlan.actionType == ActionType.LLM_ESCALATION) {
            return OodaResult(
                response = AgentResponse(
                    text = llmResult.responseText.ifEmpty { "Pole, sijaelewa. Jaribu tena." },
                    type = ResponseType.LLM_GENERATED,
                    shouldSpeak = observation.triggerType == TriggerType.VOICE_INPUT
                ),
                actionType = ActionType.LLM_ESCALATION,
                learningSignal = LearningSignal(
                    input = observation.text,
                    intent = llmResult.intent,
                    confidence = llmResult.confidence,
                    parseMethod = ParseMethod.LLM,
                    actionType = ActionType.LLM_ESCALATION,
                    module = null,
                    signals = emptyList()
                )
            )
        }

        return act(actionPlan, observation, updatedOrientation)
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Attach proactive additions to the response.
     * Adds reminders, encouragement, suggestions after the main response.
     */
    private fun attachProactiveAdditions(
        response: AgentResponse,
        additions: List<ProactiveAddition>
    ): AgentResponse {
        if (additions.isEmpty()) return response

        val proactiveText = additions.joinToString("\n") { it.content }
        return response.copy(
            text = "${response.text}\n\n$proactiveText"
        )
    }

    /**
     * Generate a clarification question when confidence is low.
     */
    private fun generateClarification(orientation: Orientation, language: String): String {
        return if (language == "sw") {
            "Sema tena polepole? Sijaelewa vizuri."
        } else {
            "Could you say that again? I didn't understand."
        }
    }

    /**
     * Generate a fallback response when orientation fails.
     */
    private fun generateFallbackResponse(observation: Observation): String {
        return if (observation.language == "sw") {
            "Pole, kuna kidogo tatizo. Sema tena?"
        } else {
            "Sorry, something went wrong. Please try again."
        }
    }

    /**
     * Simple language detection from text content.
     */
    private fun detectLanguage(text: String): String {
        val swahiliMarkers = listOf("nime", "nina", "ya", "za", "la", "kwa", "ni", "sana", "leo", "jana")
        val lower = text.lowercase()
        val swahiliHits = swahiliMarkers.count { lower.contains(it) }
        return if (swahiliHits >= 2) "sw" else "en"
    }

    /**
     * Calculate days since a timestamp.
     */
    private fun daysSince(timestamp: Long): Int {
        if (timestamp == 0) return 999
        val now = System.currentTimeMillis()
        return ((now - timestamp) / (24 * 60 * 60 * 1000)).toInt()
    }
}

// ═══════════════════════════════════════════════════════════════════
// COMMUNICATION MODULE INTERFACE
// ═══════════════════════════════════════════════════════════════════

/**
 * Communication module — personalizes responses with warmth, proverbs, and dialect.
 */
interface CommunicationModule {
    /**
     * Personalize a response with personality and dialect adaptation.
     *
     * @param response The raw response from a capability module
     * @param language The worker's language
     * @return Personalized response
     */
    fun personalize(response: AgentResponse, language: String): AgentResponse
}
