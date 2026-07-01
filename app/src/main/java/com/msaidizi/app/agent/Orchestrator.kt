package com.msaidizi.app.agent

import com.msaidizi.app.core.model.*
import com.msaidizi.app.core.util.SwahiliParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber


/**
 * Main agent orchestrator.
 * Coordinates all agents and handles the request pipeline.
 *
 * Flow: Voice Input → AdaptiveLearning → IntentRouter → Agent → Response
 *
 * 90% of requests are handled by code alone (no LLM).
 * 10% need LLM for natural language generation.
 *
 * Adaptive Learning Integration:
 * - Before intent classification: enhance input with learned vocabulary
 * - After intent classification: apply learned corrections
 * - After transaction recording: learn from the transaction
 * - For advice generation: inject personalized context
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

    /**
     * Process user input text and generate a response.
     * This is the main entry point for the agent system.
     *
     * Pipeline:
     * 1. Check for corrections first
     * 2. Classify intent with regex/code
     * 3. Enhance intent with adaptive learning (vocabulary, corrections)
     * 4. Route to appropriate agent
     * 5. Learn from the transaction
     * 6. Trigger background learning periodically
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
        // This resolves vocabulary ("mahindi" → "maize"), applies corrections,
        // and suggests prices if missing
        intentResult = adaptiveLearning.enhanceIntentWithLearning(intentResult, text)

        Timber.d("Enhanced intent: %s (data=%s)", intentResult.intent, intentResult.extractedData)

        // Step 3: Route to appropriate agent
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

        // Step 4: Record vocabulary for learning (legacy)
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

    // === INTENT HANDLERS ===

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

        // Learn from this transaction (adaptive learning)
        adaptiveLearning.learnFromTransaction(transaction)

        // Record sale time for pattern learning (legacy)
        learningAgent.recordSaleTime(
            java.time.LocalTime.now().hour,
            java.time.LocalDate.now().dayOfWeek.value - 1
        )

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

        // Learn from this transaction
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

    private suspend fun handleAdvice(language: String): AgentResponse {
        // Use adaptive learning for personalized advice
        val personalizedContext = adaptiveLearning.generatePersonalizedContext(
            maxTokens = 200,
            language = language
        )

        val text = if (personalizedContext.isNotBlank()) {
            // Enhance advisor's advice with personalized context
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

        // Try to parse and record the correction through adaptive learning
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

        // If we couldn't parse the correction, ask for clarification
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
     * Can be displayed in settings or debug screen.
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
