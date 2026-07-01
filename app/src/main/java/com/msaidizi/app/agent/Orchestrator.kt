package com.msaidizi.app.agent

import com.msaidizi.app.core.model.*
import com.msaidizi.app.core.util.SwahiliParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber


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
    private val adaptiveLearning: AdaptiveLearningEngine
) {
    // Response flow for UI
    private val _responses = MutableSharedFlow<AgentResponse>(extraBufferCapacity = 8)
    val responses: SharedFlow<AgentResponse> = _responses

    // Last transaction for corrections
    private var lastTransaction: Transaction? = null
    private var lastResponse: String = ""

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

        // Step 0: Check if this is a correction to the last transaction
        if (lastTransaction != null) {
            val isCorrection = adaptiveLearning.parseAndRecordCorrection(
                text = text,
                lastTransaction = lastTransaction,
                language = language
            )
            if (isCorrection) {
                Timber.d("Correction detected and recorded")
                val response = AgentResponse(
                    text = if (language == "sw") {
                        "✅ Nimekumbuka! Nitakumbuka kwa mara ijayo."
                    } else {
                        "✅ Got it! I'll remember that for next time."
                    },
                    type = ResponseType.CONFIRMATION
                )
                _responses.emit(response)
                return response
            }
        }

        // Step 1: Classify intent
        var intentResult = intentRouter.classify(text)

        Timber.d("Intent: %s (confidence=%.2f, needsLLM=%b)",
            intentResult.intent, intentResult.confidence, intentResult.needsLLM)

        // Step 2: Enhance intent with adaptive learning
        // STA 443 §1.2.5: Bayesian updating of intent classification
        intentResult = adaptiveLearning.enhanceIntentWithLearning(intentResult, text)

        Timber.d("Enhanced intent: %s (data=%s)", intentResult.intent, intentResult.extractedData)

        // Step 3: Route to appropriate agent
        // ECO 104 §1.2: This is the optimization step — choose the agent
        // that maximizes expected response quality for this intent.
        val response = when (intentResult.intent) {
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
        }

        // Step 4: Record vocabulary for learning
        // STA 347: Each interaction is a data point for on-device ML
        learningAgent.recordPattern(
            PatternType.VOCABULARY,
            mapOf(
                "input" to text,
                "intent" to intentResult.intent.name,
                "language" to language
            )
        )

        lastResponse = response.text
        _responses.emit(response)

        return response
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

        return AgentResponse(
            text = if (language == "sw") {
                "✅ Umeuza ${item} x${quantity.toInt()}, KSh ${"%.0f".format(amount)}. " +
                "Faida: KSh ${"%.0f".format(profit)}"
            } else {
                "✅ Sold ${item} x${quantity.toInt()}, KSh ${"%.0f".format(amount)}. " +
                "Profit: KSh ${"%.0f".format(profit)}"
            },
            type = ResponseType.CONFIRMATION,
            data = mapOf(
                "transactionId" to transaction.id.toString(),
                "item" to item,
                "quantity" to quantity.toString(),
                "amount" to amount.toString(),
                "profit" to profit.toString()
            )
        )
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

        val transaction = businessAgent.recordPurchase(item, quantity, amount, language)
        lastTransaction = transaction

        adaptiveLearning.learnFromTransaction(transaction)

        return AgentResponse(
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

        val transaction = businessAgent.recordExpense(category, amount, language = language)

        return AgentResponse(
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

        return AgentResponse(
            text = if (language == "sw") {
                "💰 Salio lako ni KSh ${"%.0f".format(balance)}"
            } else {
                "💰 Your balance is KSh ${"%.0f".format(balance)}"
            },
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

        val text = if (personalizedContext.isNotBlank()) {
            val baseAdvice = advisorAgent.getAdvice(language)
            if (language == "sw") {
                "$baseAdvice\n\n📋 Kulingana na biashara yako: $personalizedContext"
            } else {
                "$baseAdvice\n\n📋 Based on your business: $personalizedContext"
            }
        } else {
            advisorAgent.getAdvice(language)
        }

        return AgentResponse(
            text = text,
            type = ResponseType.ADVICE
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
