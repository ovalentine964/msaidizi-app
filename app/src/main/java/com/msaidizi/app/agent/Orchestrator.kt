package com.msaidizi.app.agent

import com.msaidizi.app.core.model.*
import com.msaidizi.app.core.util.SwahiliParser
import com.msaidizi.app.core.database.TitheDao
import com.msaidizi.app.core.database.GoalDao
import com.msaidizi.app.core.database.LoanDao
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
import com.msaidizi.app.loops.RewardAction
import com.msaidizi.app.core.dialect.AdaptiveVocabulary
import com.msaidizi.app.evolution.SelfEvolutionManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber
import java.util.UUID


/**
 * Main agent orchestrator — coordinates all agents and handles the request pipeline.
 *
 * Flow: Voice Input → AdaptiveLearning → IntentRouter → Agent → Response
 *
 * 90% of requests are handled by code alone (no LLM).
 * 10% need LLM for natural language generation.
 *
 * ## Mathematical & Economic Foundations
 *
 * ### ECO 103/104 — Mathematics for Economists
 * - **Optimization (ECO 104 §1.2):** The orchestrator solves a routing optimization
 *   problem: given the intent, which agent maximizes the value of the response?
 *   This is a constrained optimization: maximize response quality subject to
 *   computation budget and latency constraints.
 * - **Linear Algebra (ECO 103 §1.2):** Intent classification can be viewed as
 *   a linear transformation from input space to intent space.
 * - **Sequences and Series (ECO 103 §1.3):** The processing pipeline is a
 *   sequence of transformations, each building on the previous result.
 *
 * ### MAT 121/124 — Calculus
 * - **Rate of Change (MAT 121):** We track the rate of change of business
 *   metrics (profit, sales, margin) to identify acceleration/deceleration.
 *   d(Profit)/dt > 0 and d²(Profit)/dt² > 0 → accelerating growth.
 * - **Optimization (MAT 124):** Finding the critical points of the profit
 *   function: set dπ/dQ = 0 and check d²π/dQ² < 0 for maximum.
 *
 * ### STA 443 — Measure and Probability Theory
 * - **Probability Spaces (STA 443 §1.2):** Each user interaction is a point
 *   in a probability space (Ω, F, P). The orchestrator navigates this space
 *   to find the most probable intent and route to the best agent.
 * - **Conditional Expectation (STA 443 §1.2.5):** E[Response | Intent, Context]
 *   — the expected quality of a response given the classified intent and
 *   available context. We maximize this conditional expectation.
 * - **Law of Total Probability:** P(Success) = Σ P(Success | Agent_i) × P(Agent_i)
 *   — the overall success rate is the weighted average across agents.
 *
 * ### Adaptive Learning Integration
 * - Before intent classification: enhance input with learned vocabulary
 * - After intent classification: apply learned corrections
 * - After transaction recording: learn from the transaction
 * - For advice generation: inject personalized context
 *
 * @see IntentRouter for intent classification
 * @see BusinessAgent for transaction processing
 * @see AnalysisAgent for statistical analysis
 * @see AdvisorAgent for business advice
 * @see LearningAgent for adaptive learning
 * @see AdaptiveLearningEngine for personalization
 */
class Orchestrator(
    private val intentRouter: IntentRouter,
    private val businessAgent: BusinessAgent,
    private val analysisAgent: AnalysisAgent,
    private val advisorAgent: AdvisorAgent,
    private val learningAgent: LearningAgent,
    private val adaptiveLearning: AdaptiveLearningEngine,
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
    private val adaptiveVocabulary: AdaptiveVocabulary? = null
) {
    // Response flow for UI
    private val _responses = MutableSharedFlow<AgentResponse>(extraBufferCapacity = 8)
    val responses: SharedFlow<AgentResponse> = _responses

    // Last transaction for corrections
    private var lastTransaction: Transaction? = null
    private var lastResponse: String = ""

    // Conversation memory for multi-turn context
    private val conversationMemory = ConversationMemory()

    // Confidence thresholds for escalation
    companion object {
        /** Above this threshold: auto-proceed */
        private const val CONFIDENCE_AUTO = 0.90
        /** Above this threshold: confirm with user */
        private const val CONFIDENCE_CONFIRM = 0.70
        /** Below CONFIDENCE_CONFIRM: ask for clarification */
    }

    // ═══════════════════════════════════════════════════════════════
    // STA 443 §1.2 — PROBABILITY SPACES: Intent classification
    // ═══════════════════════════════════════════════════════════════

    /**
     * Process user input text and generate a response.
     * This is the main entry point for the agent system.
     *
     * **STA 443 §1.2.5 (Conditional Expectation):**
     * The optimal response maximizes E[Quality | Intent, Context]:
     *   Response* = argmax_R E[Quality(R) | Intent = i, Context = c]
     *
     * In practice, this means routing to the agent best suited for
     * the classified intent, enhanced with personalized context.
     *
     * **Pipeline (ECO 103 §1.3 — Sequences):**
     * 1. Check for corrections first (error correction)
     * 2. Classify intent with regex/code (feature extraction)
     * 3. Enhance intent with adaptive learning (Bayesian updating)
     * 4. Route to appropriate agent (optimization)
     * 5. Learn from the transaction (online learning)
     * 6. Trigger background learning periodically (batch processing)
     */
    suspend fun processInput(text: String, language: String = "sw"): AgentResponse {
        Timber.d("Processing input: '%s' (lang=%s)", text, language)

        // ═══ Self-Evolution: Record interaction signals ═══
        selfEvolution?.recordLanguageSignal(language)
        selfEvolution?.recordTimeSignal(java.time.LocalTime.now().hour)
        preferenceLearner?.learnLanguagePreference(language, text)
        preferenceLearner?.learnInteractionTiming(
            java.time.LocalTime.now().hour,
            java.time.LocalDate.now().dayOfWeek.value - 1
        )

        // ═══ Self-Evolution: Apply learned correction patterns ═══
        val evolvedText = selfEvolution?.applyCorrectionPatterns(text) ?: text

        // ═══ Self-Evolution: Apply learned vocabulary to transcription ═══
        val vocabEnhancedText = adaptiveVocabulary?.applyToTranscription(evolvedText) ?: evolvedText

        // ═══ Step 0: Check for corrections ═══
        if (lastTransaction != null) {
            val isCorrection = adaptiveLearning.parseAndRecordCorrection(
                text = vocabEnhancedText,
                lastTransaction = lastTransaction,
                language = language
            )
            if (isCorrection) {
                Timber.d("Correction detected and recorded")
                // Self-Evolution: Learn from this correction
                selfEvolution?.recordFeatureUsage("CORRECTION")
                val response = AgentResponse(
                    text = if (language == "sw") {
                        "✅ Nimekumbuka! Nitakumbuka kwa mara ijayo."
                    } else {
                        "✅ Got it! I'll remember that for next time."
                    },
                    type = ResponseType.CONFIRMATION
                )
                conversationMemory.addTurn("msaidizi", response.text)
                lastResponse = response.text
                _responses.emit(response)
                return response
            }
        }

        // ═══ Step 1: Classify intent with context ═══
        var intentResult = intentRouter.classify(vocabEnhancedText)

        Timber.d("Intent: %s (confidence=%.2f, needsLLM=%b)",
            intentResult.intent, intentResult.confidence, intentResult.needsLLM)

        // ═══ Step 2: Apply conversation context for reference resolution ═══
        // If this looks like a follow-up, resolve pronouns/references
        if (conversationMemory.isFollowUp(vocabEnhancedText)) {
            intentResult = conversationMemory.resolveReferences(vocabEnhancedText, intentResult)
            Timber.d("Context-resolved intent: %s (data=%s)", intentResult.intent, intentResult.extractedData)
        }

        // ═══ Step 3: Enhance intent with adaptive learning ═══
        // STA 443 §1.2.5: Bayesian updating of intent classification
        intentResult = adaptiveLearning.enhanceIntentWithLearning(intentResult, vocabEnhancedText)

        Timber.d("Enhanced intent: %s (data=%s)", intentResult.intent, intentResult.extractedData)

        // ═══ Step 4: Confidence escalation ═══
        // When confidence is low, ask for clarification instead of guessing.
        // This prevents false recordings that could corrupt the worker's data.
        val response = if (intentResult.confidence < CONFIDENCE_CONFIRM &&
            intentResult.intent != IntentType.UNKNOWN &&
            intentResult.intent != IntentType.GREETING &&
            intentResult.intent != IntentType.HELP
        ) {
            // Low confidence: ask for clarification
            handleLowConfidence(intentResult, vocabEnhancedText, language)
        } else if (intentResult.confidence < CONFIDENCE_AUTO &&
            intentResult.intent != IntentType.UNKNOWN &&
            intentResult.intent != IntentType.GREETING &&
            intentResult.intent != IntentType.HELP
        ) {
            // Medium confidence: confirm before proceeding
            handleMediumConfidence(intentResult, vocabEnhancedText, language)
        } else {
            // High confidence or non-transactional: proceed normally
            // ECO 104 §1.2: Route to agent that maximizes response quality
            routeToAgent(intentResult, text, language)
        }

        // ═══ Step 5: Record in conversation memory ═══
        conversationMemory.addTurn(
            speaker = "worker",
            text = vocabEnhancedText,
            intent = intentResult,
            extractedData = intentResult.extractedData
        )
        conversationMemory.addTurn(
            speaker = "msaidizi",
            text = response.text
        )

        // ═══ Step 6: Record vocabulary for learning ═══
        // STA 347: Each interaction is a data point for on-device ML
        learningAgent.recordPattern(
            PatternType.VOCABULARY,
            mapOf(
                "input" to vocabEnhancedText,
                "intent" to intentResult.intent.name,
                "language" to language
            )
        )

        // ═══ Step 7: Self-Evolution — track feature usage and response style ═══
        selfEvolution?.recordFeatureUsage(intentResult.intent.name)
        preferenceLearner?.learnResponseStyle(
            responseLength = response.text.length,
            hadEmojis = response.text.contains(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]")),
            wasFollowed = response.type == ResponseType.CONFIRMATION
        )

        lastResponse = response.text
        _responses.emit(response)

        return response
    }

    // ═══════════════════════════════════════════════════════════════
    // CONFIDENCE ESCALATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Handle low-confidence intent classification.
     * Asks the user for clarification instead of guessing.
     * This prevents false recordings that could corrupt data.
     */
    private fun handleLowConfidence(
        intentResult: IntentResult,
        text: String,
        language: String
    ): AgentResponse {
        val intentName = when (intentResult.intent) {
            IntentType.SALE -> if (language == "sw") "mauzo" else "a sale"
            IntentType.PURCHASE -> if (language == "sw") "ununuzi" else "a purchase"
            IntentType.EXPENSE -> if (language == "sw") "matumizi" else "an expense"
            else -> if (language == "sw") "kitu" else "something"
        }

        return AgentResponse(
            text = if (language == "sw") {
                "🤔 Sijaelewa vizuri. Unamaanisha $intentName? " +
                "Sema tena kwa uwazi zaidi, mfano: 'Nimeuza mandazi kwa 500'"
            } else {
                "🤔 I'm not sure I understood. Did you mean $intentName? " +
                "Please say it again more clearly, e.g. 'I sold mandazi for 500'"
            },
            type = ResponseType.CLARIFICATION,
            data = mapOf(
                "originalText" to text,
                "detectedIntent" to intentResult.intent.name,
                "confidence" to intentResult.confidence.toString()
            )
        )
    }

    /**
     * Handle medium-confidence intent classification.
     * Confirms with the user before recording.
     * "You want to record a sale of mandazi for KSh 500, right?"
     */
    private fun handleMediumConfidence(
        intentResult: IntentResult,
        text: String,
        language: String
    ): AgentResponse {
        val item = intentResult.extractedData["item"] ?: ""
        val amount = intentResult.extractedData["amount"] ?: ""

        val confirmationText = when (intentResult.intent) {
            IntentType.SALE -> if (language == "sw") {
                "🤔 Unamaanisha umefanya mauzo ya ${item.ifBlank { "bidhaa" }}" +
                if (amount.isNotBlank()) " kwa KSh $amount?" else "? Sema 'ndio' au 'hapana'."
            } else {
                "🤔 Did you record a sale of ${item.ifBlank { "an item" }}" +
                if (amount.isNotBlank()) " for KSh $amount?" else "? Say 'yes' or 'no'."
            }
            IntentType.PURCHASE -> if (language == "sw") {
                "🤔 Unamaanisha umenunua ${item.ifBlank { "bidhaa" }}" +
                if (amount.isNotBlank()) " kwa KSh $amount?" else "? Sema 'ndio' au 'hapana'."
            } else {
                "🤔 Did you buy ${item.ifBlank { "an item" }}" +
                if (amount.isNotBlank()) " for KSh $amount?" else "? Say 'yes' or 'no'."
            }
            IntentType.EXPENSE -> if (language == "sw") {
                "🤔 Unamaanisha umetumia KSh $amount kwa ${item.ifBlank { "matumizi" }}? " +
                "Sema 'ndio' au 'hapana'."
            } else {
                "🤔 Did you spend KSh $amount on ${item.ifBlank { "an expense" }}? " +
                "Say 'yes' or 'no'."
            }
            else -> if (language == "sw") {
                "🤔 Sijaelewa vizuri. Sema tena kwa uwazi zaidi."
            } else {
                "🤔 I'm not sure. Please say it again more clearly."
            }
        }

        return AgentResponse(
            text = confirmationText,
            type = ResponseType.CLARIFICATION,
            data = mapOf(
                "originalText" to text,
                "detectedIntent" to intentResult.intent.name,
                "confidence" to intentResult.confidence.toString(),
                "item" to item,
                "amount" to amount
            )
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // AGENT ROUTING WITH ERROR RECOVERY
    // ═══════════════════════════════════════════════════════════════

    /**
     * Route the intent to the appropriate agent with error recovery.
     * Wraps all agent calls in try-catch to prevent crashes.
     * Returns a user-friendly Swahili error message on failure.
     */
    private suspend fun routeToAgent(
        intentResult: IntentResult,
        text: String,
        language: String
    ): AgentResponse {
        return try {
            when (intentResult.intent) {
                IntentType.SALE -> handleSale(intentResult, language)
                IntentType.PURCHASE -> handlePurchase(intentResult, language)
                IntentType.EXPENSE -> handleExpense(intentResult, language)
                IntentType.PROFIT_QUERY -> handleProfitQuery(language)
                IntentType.CHECK_BALANCE -> handleBalanceQuery(language)
                IntentType.STOCK_QUERY -> handleStockQuery(intentResult, language)
                IntentType.DAILY_SUMMARY -> handleDailySummary(language)
                IntentType.WEEKLY_SUMMARY -> handleWeeklySummary(language)
                IntentType.ASK_ADVICE -> handleAdvice(language)
                IntentType.GREETING -> handleGreeting(language)
                IntentType.HELP -> handleHelp(language)
                IntentType.CORRECTION -> handleCorrection(text, language)
                IntentType.UNKNOWN -> handleUnknown(text, language)
                IntentType.TRANSPORT_TRIP,
                IntentType.TRANSPORT_EXPENSE,
                IntentType.FARMING_ACTIVITY,
                IntentType.FARMING_INPUT,
                IntentType.DIGITAL_COMMISSION,
                IntentType.DIGITAL_TRANSACTION,
                IntentType.SERVICE_CLIENT,
                IntentType.SERVICE_JOB -> handleDomainIntent(intentResult, language)
                IntentType.GIVING_RECORD -> handleGivingRecord(intentResult, language)
                IntentType.GIVING_QUERY -> handleGivingQuery(intentResult, language)
                IntentType.GIVING_GOAL -> handleGivingGoal(intentResult, language)
                IntentType.GOAL_CREATE -> handleGoalCreate(intentResult, language)
                IntentType.GOAL_PROGRESS -> handleGoalProgress(intentResult, language)
                IntentType.GOAL_REPORT -> handleGoalReport(language)
                IntentType.GOAL_TIME_FORECAST -> handleGoalTimeForecast(language)
                IntentType.GOAL_ADJUST -> handleGoalAdjust(intentResult, language)
                IntentType.GOAL_ENCOURAGEMENT -> handleGoalEncouragement(language)
                IntentType.LOAN_RECORD -> handleLoanRecord(intentResult, language)
                IntentType.LOAN_QUERY -> handleLoanQuery(language)
                IntentType.LOAN_REPORT -> handleLoanReport(language)
                IntentType.LOAN_DEADLINE -> handleLoanDeadline(language)
            }
        } catch (e: OutOfMemoryError) {
            // Critical: OME means the device is under severe memory pressure
            Timber.e(e, "OOM during agent routing for intent: %s", intentResult.intent)
            AgentResponse(
                text = if (language == "sw") {
                    "⚠️ Kuna tatizo la kumbukumbu. Funga app nyingine jaribu tena."
                } else {
                    "⚠️ Memory issue. Close other apps and try again."
                },
                type = ResponseType.ERROR
            )
        } catch (e: Exception) {
            // General error recovery — log and return friendly message
            Timber.e(e, "Error during agent routing for intent: %s", intentResult.intent)
            AgentResponse(
                text = if (language == "sw") {
                    "⚠️ Kuna tatizo. Jaribu tena. " +
                    "Sema: 'Nimeuza [bidhaa] kwa [bei]' kurekodi mauzo."
                } else {
                    "⚠️ Something went wrong. Try again. " +
                    "Say: 'I sold [item] for [price]' to record a sale."
                },
                type = ResponseType.ERROR,
                data = mapOf(
                    "error" to (e.message ?: "unknown"),
                    "intent" to intentResult.intent.name
                )
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // GIVING / TITHE INTENT HANDLERS
    // ═══════════════════════════════════════════════════════════════

    private suspend fun handleGivingRecord(intentResult: IntentResult, language: String): AgentResponse {
        val tracker = titheTracker ?: return handleDomainIntent(intentResult, language)
        val text = intentResult.extractedData["originalText"] ?: ""
        val amount = intentResult.extractedData["amount"]?.toDoubleOrNull()

        return try {
            if (amount != null && amount > 0) {
                val record = tracker.parseGivingCommand(text) ?: TitheTracker.GivingRecord(
                    amount = amount,
                    type = TitheTracker.GivingType.OFFERING,
                    recipient = "",
                    date = System.currentTimeMillis()
                )
                tracker.recordGiving(record)
                AgentResponse(
                    text = tracker.generateGivingConfirmation(record),
                    type = ResponseType.CONFIRMATION
                )
            } else {
                AgentResponse(
                    text = if (language == "sw") "Umetoa pesa ngapi?" else "How much did you give?",
                    type = ResponseType.CLARIFICATION
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error recording giving")
            AgentResponse(
                text = if (language == "sw") "⚠️ Kuna tatizo. Jaribu tena." else "⚠️ Something went wrong.",
                type = ResponseType.ERROR
            )
        }
    }

    private suspend fun handleGivingQuery(intentResult: IntentResult, language: String): AgentResponse {
        val tracker = titheTracker ?: return handleDomainIntent(intentResult, language)
        return try {
            val summary = tracker.getGivingSummary("month")
            AgentResponse(
                text = tracker.generateSummaryResponse(summary),
                type = ResponseType.INFORMATION
            )
        } catch (e: Exception) {
            Timber.e(e, "Error querying giving")
            AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR)
        }
    }

    private suspend fun handleGivingGoal(intentResult: IntentResult, language: String): AgentResponse {
        val tracker = titheTracker ?: return handleDomainIntent(intentResult, language)
        return try {
            val amount = intentResult.extractedData["amount"]?.toDoubleOrNull()
            if (amount != null && amount > 0) {
                val goal = TitheTracker.GivingGoal(
                    targetType = TitheTracker.GivingType.TITHE,
                    targetAmount = amount,
                    period = "monthly"
                )
                tracker.setGivingGoal(goal)
                AgentResponse(
                    text = if (language == "sw") {
                        "🎯 Lengo la kutoa: KSh ${"%.0f".format(amount)} kwa mwezi. Mungu akubariki!"
                    } else {
                        "🎯 Giving goal: KSh ${"%.0f".format(amount)} per month."
                    },
                    type = ResponseType.CONFIRMATION
                )
            } else {
                AgentResponse(
                    text = if (language == "sw") "Lengo ni KSh ngapi?" else "What's the target amount?",
                    type = ResponseType.CLARIFICATION
                )
            }
        } catch (e: Exception) {
            AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // GOAL INTENT HANDLERS
    // ═══════════════════════════════════════════════════════════════

    private suspend fun handleGoalCreate(intentResult: IntentResult, language: String): AgentResponse {
        val planner = goalPlanner ?: return handleDomainIntent(intentResult, language)
        val description = intentResult.extractedData["item"] ?: intentResult.extractedData["description"] ?: ""
        val amount = intentResult.extractedData["amount"]?.toDoubleOrNull()

        return try {
            if (description.isNotBlank() && amount != null && amount > 0) {
                val goal = planner.createGoal(description, amount, 0L)
                AgentResponse(
                    text = if (language == "sw") {
                        "🎯 Lengo: $description — KSh ${"%.0f".format(amount)}. Twende!"
                    } else {
                        "🎯 Goal: $description — KSh ${"%.0f".format(amount)}. Let's go!"
                    },
                    type = ResponseType.CONFIRMATION
                )
            } else {
                AgentResponse(
                    text = if (language == "sw") "Lengo lako ni nini? Bei ngapi?" else "What's your goal? How much?",
                    type = ResponseType.CLARIFICATION
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error creating goal")
            AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR)
        }
    }

    private suspend fun handleGoalProgress(intentResult: IntentResult, language: String): AgentResponse {
        val planner = goalPlanner ?: return handleDomainIntent(intentResult, language)
        val amount = intentResult.extractedData["amount"]?.toDoubleOrNull()

        return try {
            val activeGoals = planner.getActiveGoals()
            val goal = activeGoals.firstOrNull()
            if (goal != null && amount != null && amount > 0) {
                val (updatedGoal, celebration) = planner.updateProgress(goal, amount)
                val percent = (updatedGoal.progress * 100).toInt()
                val baseText = if (language == "sw") {
                    "✅ Umefikia $percent% ya lengo lako!"
                } else {
                    "✅ You've reached $percent% of your goal!"
                }
                val fullText = if (celebration != null) {
                    baseText + "\n" + celebration.message
                } else baseText
                AgentResponse(text = fullText, type = ResponseType.CONFIRMATION)
            } else if (goal == null) {
                AgentResponse(
                    text = if (language == "sw") "Huna lengo. Sema 'Lengo langu ni...'" else "No goal set. Say 'My goal is...'" ,
                    type = ResponseType.CLARIFICATION
                )
            } else {
                AgentResponse(
                    text = if (language == "sw") "Umetoa KSh ngapi?" else "How much did you save?",
                    type = ResponseType.CLARIFICATION
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error updating goal progress")
            AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR)
        }
    }

    private suspend fun handleGoalReport(language: String): AgentResponse {
        val planner = goalPlanner ?: return AgentResponse(
            text = if (language == "sw") "Huna malengo bado." else "No goals yet.",
            type = ResponseType.INFORMATION
        )
        return try {
            val goals = planner.getAllGoals()
            val report = planner.getGoalReport(goals)
            AgentResponse(text = report.message, type = ResponseType.INFORMATION)
        } catch (e: Exception) {
            AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR)
        }
    }

    private suspend fun handleGoalTimeForecast(language: String): AgentResponse {
        val planner = goalPlanner ?: return AgentResponse(
            text = if (language == "sw") "Huna lengo." else "No goal.",
            type = ResponseType.INFORMATION
        )
        return try {
            val activeGoals = planner.getActiveGoals()
            val goal = activeGoals.firstOrNull()
            if (goal != null) {
                val forecast = planner.getTimeToGoal(goal)
                AgentResponse(text = forecast.message, type = ResponseType.INFORMATION)
            } else {
                AgentResponse(text = if (language == "sw") "Huna lengo." else "No goal.", type = ResponseType.INFORMATION)
            }
        } catch (e: Exception) {
            AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR)
        }
    }

    private suspend fun handleGoalAdjust(intentResult: IntentResult, language: String): AgentResponse {
        val planner = goalPlanner ?: return handleDomainIntent(intentResult, language)
        return try {
            val activeGoals = planner.getActiveGoals()
            val goal = activeGoals.firstOrNull()
            if (goal != null) {
                val amount = intentResult.extractedData["amount"]?.toDoubleOrNull()
                val adjusted = planner.adjustGoal(goal, newTarget = amount)
                AgentResponse(
                    text = if (language == "sw") "✅ Lengo limesasishwa." else "✅ Goal updated.",
                    type = ResponseType.CONFIRMATION
                )
            } else {
                AgentResponse(text = if (language == "sw") "Huna lengo." else "No goal.", type = ResponseType.INFORMATION)
            }
        } catch (e: Exception) {
            AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR)
        }
    }

    private suspend fun handleGoalEncouragement(language: String): AgentResponse {
        val planner = goalPlanner ?: return AgentResponse(
            text = if (language == "sw") "Sema 'Lengo langu ni...' kuanza!" else "Say 'My goal is...' to start!",
            type = ResponseType.INFORMATION
        )
        return try {
            val activeGoals = planner.getActiveGoals()
            val goal = activeGoals.firstOrNull()
            if (goal != null) {
                AgentResponse(text = planner.getEncouragement(goal), type = ResponseType.INFORMATION)
            } else {
                AgentResponse(
                    text = if (language == "sw") "Anza na lengo! Sema 'Lengo langu ni...'" else "Start with a goal! Say 'My goal is...'" ,
                    type = ResponseType.INFORMATION
                )
            }
        } catch (e: Exception) {
            AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LOAN INTENT HANDLERS
    // ═══════════════════════════════════════════════════════════════

    private suspend fun handleLoanRecord(intentResult: IntentResult, language: String): AgentResponse {
        val manager = loanManager ?: return handleDomainIntent(intentResult, language)
        val amount = intentResult.extractedData["amount"]?.toDoubleOrNull()
        val purpose = intentResult.extractedData["item"] ?: intentResult.extractedData["purpose"] ?: "biashara"

        return try {
            if (amount != null && amount > 0) {
                val schedule = manager.generateRepaymentSchedule(amount, 0.15, 3, System.currentTimeMillis() / 1000)
                val loan = LoanManager.Loan(
                    amount = amount,
                    purpose = purpose,
                    interestRate = 0.15,
                    repaymentSchedule = schedule,
                    startDate = System.currentTimeMillis() / 1000,
                    endDate = System.currentTimeMillis() / 1000 + (90 * 86400),
                    lender = intentResult.extractedData["lender"] ?: "M-Shwari"
                )
                val recorded = manager.recordLoan(loan)
                AgentResponse(
                    text = if (language == "sw") {
                        "✅ Mkopo wa KSh ${"%.0f".format(amount)} umerekodiwa. Malipo ya KSh ${"%.0f".format(recorded.totalToRepay / 3)} kwa mwezi."
                    } else {
                        "✅ Loan of KSh ${"%.0f".format(amount)} recorded. Payments of KSh ${"%.0f".format(recorded.totalToRepay / 3)} monthly."
                    },
                    type = ResponseType.CONFIRMATION
                )
            } else {
                AgentResponse(
                    text = if (language == "sw") "Mkopo ni KSh ngapi?" else "How much is the loan?",
                    type = ResponseType.CLARIFICATION
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error recording loan")
            AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR)
        }
    }

    private suspend fun handleLoanQuery(language: String): AgentResponse {
        val manager = loanManager ?: return AgentResponse(
            text = if (language == "sw") "Huna mkopo." else "No loans.",
            type = ResponseType.INFORMATION
        )
        return try {
            val reminder = manager.getRepaymentReminder()
            AgentResponse(text = reminder, type = ResponseType.INFORMATION)
        } catch (e: Exception) {
            AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR)
        }
    }

    private suspend fun handleLoanReport(language: String): AgentResponse {
        val manager = loanManager ?: return AgentResponse(
            text = if (language == "sw") "Huna mkopo." else "No loans.",
            type = ResponseType.INFORMATION
        )
        return try {
            AgentResponse(text = manager.getLoanReport(), type = ResponseType.INFORMATION)
        } catch (e: Exception) {
            AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR)
        }
    }

    private suspend fun handleLoanDeadline(language: String): AgentResponse {
        val manager = loanManager ?: return AgentResponse(
            text = if (language == "sw") "Huna mkopo." else "No loans.",
            type = ResponseType.INFORMATION
        )
        return try {
            val reminder = manager.getRepaymentReminder()
            AgentResponse(text = reminder, type = ResponseType.INFORMATION)
        } catch (e: Exception) {
            AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR)
        }
    }

    /**
     * Handle domain-specific intents (transport, farming, digital, service).
     * These are treated as specialized sale/expense transactions.
     */
    private suspend fun handleDomainIntent(
        intentResult: IntentResult,
        language: String
    ): AgentResponse {
        return try {
            when (intentResult.intent) {
                IntentType.TRANSPORT_TRIP,
                IntentType.FARMING_ACTIVITY,
                IntentType.DIGITAL_TRANSACTION,
                IntentType.SERVICE_CLIENT -> {
                    // These are informational queries about domain-specific data
                    val text = advisorAgent.getDomainAdvice(intentResult, language)
                    AgentResponse(text = text, type = ResponseType.INFORMATION)
                }
                IntentType.TRANSPORT_EXPENSE,
                IntentType.FARMING_INPUT,
                IntentType.DIGITAL_COMMISSION,
                IntentType.SERVICE_JOB -> {
                    // These are recordable transactions
                    val item = intentResult.extractedData["item"] ?: "service"
                    val amount = intentResult.extractedData["amount"]?.toDoubleOrNull() ?: 0.0
                    if (amount > 0) {
                        val txn = businessAgent.recordSale(item, 1.0, amount, language)
                        AgentResponse(
                            text = if (language == "sw") {
                                "✅ Umerekodi: $item, KSh ${"%.0f".format(amount)}"
                            } else {
                                "✅ Recorded: $item, KSh ${"%.0f".format(amount)}"
                            },
                            type = ResponseType.CONFIRMATION
                        )
                    } else {
                        AgentResponse(
                            text = if (language == "sw") "Bei ni ngapi?" else "What price?",
                            type = ResponseType.CLARIFICATION
                        )
                    }
                }
                else -> handleUnknown("", language)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error handling domain intent: %s", intentResult.intent)
            AgentResponse(
                text = if (language == "sw") {
                    "⚠️ Kuna tatizo. Jaribu tena."
                } else {
                    "⚠️ Something went wrong. Try again."
                },
                type = ResponseType.ERROR
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // INTENT HANDLERS — Each handler is grounded in economic theory
    // ═══════════════════════════════════════════════════════════════

    /**
     * Handle sale recording.
     *
     * **ECO 101 §1.4 (Production Theory):** Each sale updates the
     * cost basis and profit margin, enabling real-time production analysis.
     * **ECO 201 §1.2 (Producer Theory):** Profit = Revenue - Cost.
     */
    private suspend fun handleSale(intentResult: IntentResult, language: String): AgentResponse {
        val item = intentResult.extractedData["item"] ?: return AgentResponse(
            text = if (language == "sw") "Ni bidhaa gani?" else "What item?",
            type = ResponseType.CLARIFICATION
        )

        val quantity = intentResult.extractedData["quantity"]?.toDoubleOrNull() ?: 1.0
        val amount = intentResult.extractedData["amount"]?.toDoubleOrNull()
            ?: intentResult.extractedData["suggestedPrice"]?.toDoubleOrNull()
            ?: return AgentResponse(
                text = if (language == "sw") "Bei ni ngapi?" else "What price?",
                type = ResponseType.CLARIFICATION
            )

        return try {
            val transaction = businessAgent.recordSale(item, quantity, amount, language)
            lastTransaction = transaction

            // Learn from this transaction
            adaptiveLearning.learnFromTransaction(transaction)

            // Record sale time for pattern learning
            learningAgent.recordSaleTime(
                java.time.LocalTime.now().hour,
                java.time.LocalDate.now().dayOfWeek.value - 1
            )

            // ECO 101 §1.4: Calculate profit for this transaction
            val profit = transaction.totalAmount - transaction.costBasis

            // ═══ LOOP CLOSURE: Morning Briefing Feedback Cycle ═══
            morningBriefingLoop?.let { loop ->
                try {
                    loop.onTransactionAfterBriefing(transaction)
                } catch (_: Exception) {}
            } ?: run {
                // Fallback to direct briefing delivery if loop not wired
                briefingDelivery?.let { bd ->
                    try {
                        val pending = bd.getLatestPendingBriefing()
                        if (pending != null) {
                            bd.recordBriefingOutcome(
                                deliveryId = pending.id,
                                actualSales = amount,
                                actualProfit = profit,
                                adviceFollowed = null
                            )
                        }
                    } catch (_: Exception) {}
                }
            }

            // ═══ LOOP CLOSURE: Gamification + Variable Rewards ═══
            val gamificationMessages = mutableListOf<String>()
            gamificationEngine?.let { ge ->
                try {
                    val event = ge.onSaleRecorded(language)
                    gamificationMessages.addAll(event.messages)

                    // Streak protection: check milestone celebration
                    streakProtectionLoop?.let { spl ->
                        try {
                            spl.checkStreakMilestone(language)?.let {
                                gamificationMessages.add(it)
                            }
                        } catch (_: Exception) {}
                    }

                    // Variable rewards: surprise bonuses
                    variableRewardsLoop?.let { vrl ->
                        try {
                            val reward = vrl.evaluateReward(RewardAction.SALE, language)
                            if (reward != null) {
                                gamificationMessages.add(reward.message)
                            }
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }

            // Rich Habits: auto-complete "record_sales" habit
            richHabitsScore?.let { rhs ->
                try {
                    val completions = rhs.autoCompleteFromAction("sale", language)
                    completions.forEach { gamificationMessages.add(it.message) }
                } catch (_: Exception) {}
            }

            // Aha Moment: check if this triggers the aha moment
            val ahaPrompt = ahaMomentFlow?.onSaleRecorded(language)
            if (ahaPrompt != null) {
                gamificationMessages.add(ahaPrompt.getText(language))
            }

            // Build response with gamification messages
            val baseText = if (language == "sw") {
                "✅ Umeuza ${item} x${quantity.toInt()}, KSh ${"%.0f".format(amount)}. " +
                "Faida: KSh ${"%.0f".format(profit)}"
            } else {
                "✅ Sold ${item} x${quantity.toInt()}, KSh ${"%.0f".format(amount)}. " +
                "Profit: KSh ${"%.0f".format(profit)}"
            }

            val fullText = if (gamificationMessages.isNotEmpty()) {
                baseText + "\n\n" + gamificationMessages.joinToString("\n")
            } else baseText

            AgentResponse(
                text = fullText,
                type = ResponseType.CONFIRMATION,
                data = mapOf(
                    "transactionId" to transaction.id.toString(),
                    "item" to item,
                    "quantity" to quantity.toString(),
                    "amount" to amount.toString(),
                    "profit" to profit.toString()
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Error recording sale: %s x%.0f @ %.0f", item, quantity, amount)
            AgentResponse(
                text = if (language == "sw") {
                    "⚠️ Imeshindikana kurekodi mauzo. Jaribu tena."
                } else {
                    "⚠️ Failed to record sale. Please try again."
                },
                type = ResponseType.ERROR
            )
        }
    }

    /**
     * Handle purchase recording.
     *
     * **ECO 201 §1.2 (Producer Theory):** Purchases increase capital stock
     * and update the weighted average cost (AVC).
     */
    private suspend fun handlePurchase(intentResult: IntentResult, language: String): AgentResponse {
        val item = intentResult.extractedData["item"] ?: return AgentResponse(
            text = if (language == "sw") "Ni bidhaa gani?" else "What item?",
            type = ResponseType.CLARIFICATION
        )

        val quantity = intentResult.extractedData["quantity"]?.toDoubleOrNull() ?: 1.0
        val amount = intentResult.extractedData["amount"]?.toDoubleOrNull() ?: return AgentResponse(
            text = if (language == "sw") "Bei ni ngapi?" else "What price?",
            type = ResponseType.CLARIFICATION
        )

        return try {
            val transaction = businessAgent.recordPurchase(item, quantity, amount, language)
            lastTransaction = transaction
            adaptiveLearning.learnFromTransaction(transaction)

            AgentResponse(
                text = if (language == "sw") {
                    "✅ Umenunua ${item} x${quantity.toInt()}, KSh ${"%.0f".format(amount)}"
                } else {
                    "✅ Bought ${item} x${quantity.toInt()}, KSh ${"%.0f".format(amount)}"
                },
                type = ResponseType.CONFIRMATION,
                data = mapOf(
                    "transactionId" to transaction.id.toString(),
                    "item" to item,
                    "quantity" to quantity.toString(),
                    "amount" to amount.toString()
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Error recording purchase: %s x%.0f @ %.0f", item, quantity, amount)
            AgentResponse(
                text = if (language == "sw") {
                    "⚠️ Imeshindikana kurekodi ununuzi. Jaribu tena."
                } else {
                    "⚠️ Failed to record purchase. Please try again."
                },
                type = ResponseType.ERROR
            )
        }
    }

    /**
     * Handle expense recording.
     *
     * **ECO 101 §1.4:** Expenses are fixed or variable costs that reduce profit.
     */
    private suspend fun handleExpense(intentResult: IntentResult, language: String): AgentResponse {
        val category = intentResult.extractedData["category"] ?: "other"
        val amount = intentResult.extractedData["amount"]?.toDoubleOrNull() ?: return AgentResponse(
            text = if (language == "sw") "Ni pesa ngapi?" else "How much?",
            type = ResponseType.CLARIFICATION
        )

        return try {
            val transaction = businessAgent.recordExpense(category, amount, language = language)

            AgentResponse(
                text = if (language == "sw") {
                    "✅ Umerekodi matumizi: $category, KSh ${"%.0f".format(amount)}"
                } else {
                    "✅ Recorded expense: $category, KSh ${"%.0f".format(amount)}"
                },
                type = ResponseType.CONFIRMATION,
                data = mapOf(
                    "transactionId" to transaction.id.toString(),
                    "category" to category,
                    "amount" to amount.toString()
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Error recording expense: %s %.0f", category, amount)
            AgentResponse(
                text = if (language == "sw") {
                    "⚠️ Imeshindikana kurekodi matumizi. Jaribu tena."
                } else {
                    "⚠️ Failed to record expense. Please try again."
                },
                type = ResponseType.ERROR
            )
        }
    }

    /**
     * Handle profit query.
     *
     * **ECO 201 §1.2:** Profit = Total Revenue - Total Cost.
     * **ECO 101 §1.4:** Profit margin = (Profit / Revenue) × 100.
     */
    private suspend fun handleProfitQuery(language: String): AgentResponse {
        val profit = businessAgent.getDailyProfit()
        val sales = businessAgent.getDailySales()
        val margin = if (sales > 0) (profit / sales * 100) else 0.0

        return AgentResponse(
            text = if (language == "sw") {
                "💰 Faida yako leo ni KSh ${"%.0f".format(profit)} (margin ${margin.toInt()}%)"
            } else {
                "💰 Your profit today is KSh ${"%.0f".format(profit)} (margin ${margin.toInt()}%)"
            },
            type = ResponseType.INFORMATION,
            data = mapOf(
                "profit" to profit.toString(),
                "sales" to sales.toString(),
                "margin" to margin.toString()
            )
        )
    }

    private suspend fun handleBalanceQuery(language: String): AgentResponse {
        val balance = businessAgent.getBalance()

        // Gamification: award points for balance check
        val gamificationMessages = mutableListOf<String>()
        gamificationEngine?.let { ge ->
            try {
                val event = ge.onBalanceChecked(language)
                gamificationMessages.addAll(event.messages)
            } catch (_: Exception) {}
        }

        // Rich Habits: auto-complete "check_balance" habit
        richHabitsScore?.let { rhs ->
            try {
                val completions = rhs.autoCompleteFromAction("balance_check", language)
                completions.forEach { gamificationMessages.add(it.message) }
            } catch (_: Exception) {}
        }

        val baseText = if (language == "sw") {
            "💰 Salio lako ni KSh ${"%.0f".format(balance)}"
        } else {
            "💰 Your balance is KSh ${"%.0f".format(balance)}"
        }

        val fullText = if (gamificationMessages.isNotEmpty()) {
            baseText + "\n" + gamificationMessages.joinToString("\n")
        } else baseText

        return AgentResponse(
            text = fullText,
            type = ResponseType.INFORMATION,
            data = mapOf("balance" to balance.toString())
        )
    }

    private suspend fun handleStockQuery(intentResult: IntentResult, language: String): AgentResponse {
        val text = advisorAgent.getStockInfo(
            intentResult.extractedData["item"],
            language
        )
        return AgentResponse(
            text = text,
            type = ResponseType.INFORMATION
        )
    }

    private suspend fun handleDailySummary(language: String): AgentResponse {
        val text = advisorAgent.getDailySummary(language)
        return AgentResponse(
            text = text,
            type = ResponseType.INFORMATION
        )
    }

    /**
     * Handle weekly summary.
     *
     * **ECO 103 §1.3 (Sequences):** Weekly summary aggregates 7 daily
     * observations into a coherent narrative.
     * **STA 244 §10.1:** Trend analysis using moving averages.
     */
    private suspend fun handleWeeklySummary(language: String): AgentResponse {
        val cashFlow = businessAgent.getCashFlow(7)
        val topItems = analysisAgent.topItems(7, 3)
        val trend = analysisAgent.salesTrend()

        val trendText = when (trend) {
            Trend.RISING -> if (language == "sw") "📈 Inaongezeka" else "📈 Rising"
            Trend.FALLING -> if (language == "sw") "📉 Inapungua" else "📉 Falling"
            Trend.STABLE -> if (language == "sw") "➡️ Imara" else "➡️ Stable"
            Trend.INSUFFICIENT_DATA -> if (language == "sw") "📊 Data ndogo" else "📊 Limited data"
        }

        val topItemsText = topItems.joinToString("\n") { item ->
            "• ${item.item}: KSh ${"%.0f".format(item.totalRevenue)}"
        }

        return AgentResponse(
            text = if (language == "sw") {
                """
                |📊 Muhtasari wa wiki:
                |
                |💰 Mauzo: KSh ${"%.0f".format(cashFlow.inflow)}
                |🛒 Matumizi: KSh ${"%.0f".format(cashFlow.outflow)}
                |📈 Faida: KSh ${"%.0f".format(cashFlow.net)}
                |📊 Mwelekeo: $trendText
                |
                |⭐ Bidhaa bora:
                |$topItemsText
                """.trimMargin()
            } else {
                """
                |📊 Weekly Summary:
                |
                |💰 Sales: KSh ${"%.0f".format(cashFlow.inflow)}
                |🛒 Expenses: KSh ${"%.0f".format(cashFlow.outflow)}
                |📈 Profit: KSh ${"%.0f".format(cashFlow.net)}
                |📊 Trend: $trendText
                |
                |⭐ Top items:
                |$topItemsText
                """.trimMargin()
            },
            type = ResponseType.INFORMATION,
            data = mapOf(
                "sales" to cashFlow.inflow.toString(),
                "expenses" to cashFlow.outflow.toString(),
                "profit" to cashFlow.net.toString(),
                "trend" to trend.name
            )
        )
    }

    /**
     * Handle advice request.
     *
     * **ECO 206/209/210/322:** The AdvisorAgent generates advice grounded
     * in microfinance, money & banking, quantitative methods, and
     * macroeconomic theory.
     * **ECO 315:** Personalized context from adaptive learning
     * enhances the advice with user-specific data.
     */
    private suspend fun handleAdvice(language: String): AgentResponse {
        val personalizedContext = adaptiveLearning.generatePersonalizedContext(
            maxTokens = 200,
            language = language
        )

        // Self-Evolution: Inject learned preferences into advice context
        val preferenceContext = preferenceLearner?.generatePreferenceContext(language) ?: ""

        val baseAdvice = advisorAgent.getAdvice(language)
        val text = buildString {
            append(baseAdvice)
            if (personalizedContext.isNotBlank()) {
                append("\n\n")
                append(if (language == "sw") "📋 Kulingana na biashara yako: " else "📋 Based on your business: ")
                append(personalizedContext)
            }
            if (preferenceContext.isNotBlank()) {
                append("\n")
                append(preferenceContext)
            }
        }

        // Self-Evolution: Track advice delivery for outcome measurement
        val adviceId = "advice_${UUID.randomUUID().toString().take(8)}"
        selfEvolution?.trackAdviceDelivery(
            adviceId = adviceId,
            adviceType = "business_advice",
            adviceText = text,
            context = mapOf("language" to language)
        )

        return AgentResponse(
            text = text,
            type = ResponseType.ADVICE,
            data = mapOf("adviceId" to adviceId)
        )
    }

    private suspend fun handleGreeting(language: String): AgentResponse {
        val text = advisorAgent.getGreeting(language)
        return AgentResponse(
            text = text,
            type = ResponseType.GREETING
        )
    }

    private fun handleHelp(language: String): AgentResponse {
        val text = advisorAgent.getHelp(language)
        return AgentResponse(
            text = text,
            type = ResponseType.HELP
        )
    }

    private suspend fun handleCorrection(text: String, language: String): AgentResponse {
        if (lastTransaction == null) {
            return AgentResponse(
                text = if (language == "sw") {
                    "Hakuna shughuli ya kurekebisha."
                } else {
                    "No transaction to correct."
                },
                type = ResponseType.ERROR
            )
        }

        val isCorrection = adaptiveLearning.parseAndRecordCorrection(
            text = text,
            lastTransaction = lastTransaction,
            language = language
        )

        if (isCorrection) {
            return AgentResponse(
                text = if (language == "sw") {
                    "✅ Nimekumbuka marekebisho! Nitakumbuka kwa mara ijayo."
                } else {
                    "✅ Correction recorded! I'll remember that for next time."
                },
                type = ResponseType.CONFIRMATION
            )
        }

        return AgentResponse(
            text = if (language == "sw") {
                "Ni nini kibaya? Sema: 'Bei ni X' au 'Bidhaa ni Y'"
            } else {
                "What's wrong? Say: 'Price is X' or 'Item is Y'"
            },
            type = ResponseType.CLARIFICATION
        )
    }

    private fun handleUnknown(text: String, language: String): AgentResponse {
        return AgentResponse(
            text = if (language == "sw") {
                "Sijaelewa. Sema: 'Nimeuza' kurekodi mauzo, au 'Nisaidie' kwa usaidizi."
            } else {
                "I didn't understand. Say: 'I sold' to record a sale, or 'Help me' for assistance."
            },
            type = ResponseType.UNKNOWN
        )
    }

    // ═══════════════ LEARNING LIFECYCLE ═══════════════

    /**
     * Get adaptive learning statistics.
     */
    suspend fun getLearningStats(): LearningStats {
        return adaptiveLearning.getLearningStats()
    }

    /**
     * Trigger background learning.
     * Call this during heartbeats or when the device is charging.
     */
    fun triggerBackgroundLearning() {
        adaptiveLearning.launchBackgroundLearning()
    }

    /**
     * Initialize stickiness features.
     * Call on app startup to set up gamification, seed lessons, etc.
     * Also checks streak risk and delivers recovery celebrations.
     */
    suspend fun initializeStickiness() {
        gamificationEngine?.initialize()
        mindsetAcademy?.seedLessons()
        titheTracker?.loadFromDb()

        // ═══ STREAK PROTECTION LOOP: Check if streak is at risk on app open ═══
        gamificationEngine?.let { ge ->
            try {
                val riskReminder = ge.getStreakRiskReminder()
                if (riskReminder != null) {
                    _responses.emit(AgentResponse(
                        text = riskReminder,
                        type = ResponseType.INFORMATION,
                        shouldSpeak = true
                    ))
                }
                val recoveryMsg = ge.getStreakRecoveryMessage()
                if (recoveryMsg != null) {
                    _responses.emit(AgentResponse(
                        text = recoveryMsg,
                        type = ResponseType.INFORMATION,
                        shouldSpeak = true
                    ))
                }
            } catch (_: Exception) {}
        }

        Timber.d("Stickiness features initialized")
    }

    /**
     * Get the welcome prompt for first launch.
     */
    fun getSessionWelcome(language: String = "sw"): String? {
        return ahaMomentFlow?.onSessionStart(language)?.getText(language)
    }

    /**
     * Get current gamification state.
     */
    suspend fun getGamificationState() = gamificationEngine?.getState()

    /**
     * Get streak protection status (freeze available, risk level).
     */
    suspend fun getStreakProtection() = gamificationEngine?.getStreakFreezeStatus()

    /**
     * Get briefing loop performance metrics.
     */
    suspend fun getBriefingLoopPerformance() = briefingDelivery?.getBriefingPerformance()

    /**
     * Get today's rich habits score.
     */
    suspend fun getRichHabitsScore() = richHabitsScore?.getTodayScore()

    /**
     * Get mindset academy progress.
     */
    suspend fun getMindsetProgress() = mindsetAcademy?.getProgress()

    /**
     * Get conversation memory for debugging or UI display.
     */
    fun getConversationMemory(): ConversationMemory = conversationMemory

    /**
     * Clear conversation memory (e.g., on new session).
     */
    fun clearConversationMemory() {
        conversationMemory.clear()
    }

    // ═══════════════ SELF-EVOLUTION LIFECYCLE ═══════════════

    /**
     * Trigger self-evolution cycle.
     * Runs correction pattern analysis, feedback processing, and metric computation.
     * Call during heartbeats or when device is idle.
     */
    fun triggerEvolutionCycle() {
        selfEvolution?.launchEvolutionCycle()
    }

    /**
     * Get current worker preferences (learned from interactions).
     */
    fun getWorkerPreferences(): com.msaidizi.app.evolution.WorkerPreferences? =
        selfEvolution?.getPreferences()

    /**
     * Get evolution metrics showing how much the system has learned.
     */
    fun getEvolutionMetrics(): com.msaidizi.app.evolution.EvolutionMetrics? =
        selfEvolution?.evolutionMetrics?.value

    /**
     * Get goal adaptation suggestion based on worker's actual progress.
     */
    suspend fun getGoalAdaptation(
        goalId: Long,
        currentTarget: Double,
        actualProgress: Double,
        daysElapsed: Int,
        totalDays: Int
    ): com.msaidizi.app.evolution.GoalAdaptationSuggestion? =
        selfEvolution?.analyzeGoalAdaptation(goalId, currentTarget, actualProgress, daysElapsed, totalDays)

    /**
     * Record that worker acted on advice (positive satisfaction signal).
     */
    suspend fun recordAdviceFollowed(adviceId: String) {
        selfEvolution?.recordAdviceOutcome(adviceId, followed = true, outcomeScore = 0.9)
    }

    /**
     * Get preferred report format based on learned preferences.
     */
    suspend fun getPreferredReportFormat(): String =
        preferenceLearner?.getPreferredReportFormat() ?: "daily"

    /**
     * Get preferred voice speed based on learned preferences.
     */
    suspend fun getPreferredVoiceSpeed(): Float =
        preferenceLearner?.getPreferredVoiceSpeed() ?: 1.0f

    /**
     * Get preferred language based on learned preferences.
     */
    suspend fun getPreferredLanguage(): String =
        preferenceLearner?.getPreferredLanguage() ?: "sw"
}

/**
 * Agent response types.
 */
enum class ResponseType {
    CONFIRMATION,    // Transaction recorded
    INFORMATION,     // Query result
    ADVICE,          // Business advice
    GREETING,        // Hello response
    HELP,            // Help text
    CLARIFICATION,   // Need more info
    ERROR,           // Error occurred
    UNKNOWN          // Couldn't understand
}

/**
 * Agent response data class.
 */
data class AgentResponse(
    val text: String,
    val type: ResponseType,
    val data: Map<String, String> = emptyMap(),
    val shouldSpeak: Boolean = true
)
