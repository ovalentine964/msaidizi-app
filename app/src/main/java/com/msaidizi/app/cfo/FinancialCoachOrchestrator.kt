package com.msaidizi.app.agent.coach

import com.msaidizi.app.agent.AgentEvent
import com.msaidizi.app.agent.AgentEventBus
import com.msaidizi.app.agent.AgentResponse
import com.msaidizi.app.agent.ResponseType
import com.msaidizi.app.core.database.LoanDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.IntentResult
import com.msaidizi.app.core.model.IntentType
import timber.log.Timber
import java.util.UUID

/**
 * Financial Coach Orchestrator — Routes financial coaching requests
 * through a 3-agent pipeline.
 *
 * Pipeline Architecture:
 *   ┌──────────────┐     ┌───────────────────┐     ┌──────────────────┐
 *   │ IntentRouter  │────▶│ BudgetAnalyzer     │────▶│ SavingsStrategist │
 *   │ (classify)    │     │ (categorize,       │     │ (strategy,         │
 *   │               │     │  detect patterns)  │     │  projections)      │
 *   └──────────────┘     └───────────────────┘     └──────────────────┘
 *                                                               │
 *                                                               ▼
 *                                                     ┌──────────────────┐
 *                                                     │ DebtAdvisor       │
 *                                                     │ (loans, repayment │
 *                                                     │  borrowing)       │
 *                                                     └──────────────────┘
 *
 * ## Integration with Orchestrator
 *
 * The FinancialCoachHandler wraps this orchestrator and integrates it
 * into Msaidizi's existing Orchestrator as a domain-specific handler.
 *
 * Flow:
 *   User: "Ninatumia pesa nyingi wapi?"
 *   → IntentRouter.classify() → IntentType.ASK_ADVICE (or coach-specific)
 *   → Orchestrator.routeToHandler() → FinancialCoachHandler
 *   → FinancialCoachOrchestrator.processRequest()
 *   → BudgetAnalyzer categorizes spending
 *   → Response to user
 *
 * ## Design Principles
 *
 * 1. **Voice-first**: All responses are short, spoken-friendly Swahili
 * 2. **No LLM needed**: Pure code analysis, instant results
 * 3. **Offline-first**: Works entirely on-device with Room DB
 * 4. **Progressive disclosure**: Summary first, details on request
 * 5. **Culturally aware**: Uses Kenyan financial concepts (chama, fuliza, M-Shwari)
 *
 * @param budgetAnalyzer Budget Analyzer Agent (stage 1)
 * @param savingsStrategist Savings Strategist Agent (stage 2)
 * @param debtAdvisor Debt Advisor Agent (stage 3)
 * @param eventBus Agent event bus for publishing coaching events
 */
class FinancialCoachOrchestrator(
    private val budgetAnalyzer: BudgetAnalyzerAgent,
    private val savingsStrategist: SavingsStrategistAgent,
    private val debtAdvisor: DebtAdvisorAgent,
    private val eventBus: AgentEventBus = AgentEventBus.getInstance()
) {
    companion object {
        private const val TAG = "FinancialCoach"
    }

    // ═══════════════════════════════════════════════════════════════
    // MAIN ENTRY POINT — Process financial coaching request
    // ═══════════════════════════════════════════════════════════════

    /**
     * Process a financial coaching request.
     *
     * Classifies the coaching intent and routes to the appropriate
     * agent pipeline. Returns a voice-friendly response.
     *
     * @param intentResult The classified intent from IntentRouter
     * @param language Output language ("sw" or "en")
     * @return AgentResponse with coaching advice
     */
    suspend fun processRequest(
        intentResult: IntentResult,
        language: String = "sw"
    ): AgentResponse {
        val coachIntent = mapToCoachIntent(intentResult)
        val startTime = System.currentTimeMillis()

        Timber.d(TAG, "Processing coach request: %s (confidence=%.2f)", coachIntent, intentResult.confidence)

        // Publish task started event
        eventBus.publish(AgentEvent.AgentTaskStarted(
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            source = "FinancialCoachOrchestrator",
            taskType = coachIntent.name,
            agentName = "FinancialCoach"
        ))

        return try {
            val response = when (coachIntent) {
                // ── Budget Analysis ──
                FinancialCoachIntent.SPENDING_ANALYSIS -> handleSpendingAnalysis(language)
                FinancialCoachIntent.OVERSPENDING_DETECTION -> handleOverspendingDetection(language)
                FinancialCoachIntent.BUDGET_STATUS -> handleBudgetStatus(language)
                FinancialCoachIntent.BUDGET_CREATE -> handleBudgetCreate(language)

                // ── Savings Strategy ──
                FinancialCoachIntent.SAVINGS_ADVICE -> handleSavingsAdvice(language)
                FinancialCoachIntent.SAVINGS_AMOUNT -> handleSavingsAmount(language)
                FinancialCoachIntent.SAVINGS_STATUS -> handleSavingsStatus(language)
                FinancialCoachIntent.SAVINGS_GOAL -> handleSavingsGoal(language)

                // ── Debt Management ──
                FinancialCoachIntent.DEBT_STATUS -> handleDebtStatus(language)
                FinancialCoachIntent.DEBT_REPAYMENT_STRATEGY -> handleRepaymentStrategy(language)
                FinancialCoachIntent.BORROW_ADVICE -> handleBorrowAdvice(intentResult, language)
                FinancialCoachIntent.DEBT_CONSOLIDATION -> handleDebtConsolidation(language)

                // ── Full Health Check ──
                FinancialCoachIntent.FULL_HEALTH_CHECK -> handleFullHealthCheck(language)
                FinancialCoachIntent.COACH_GREETING -> handleCoachGreeting(language)
                FinancialCoachIntent.COACH_UNKNOWN -> handleCoachUnknown(language)
            }

            // Publish task completed event
            val duration = System.currentTimeMillis() - startTime
            eventBus.publish(AgentEvent.AgentTaskCompleted(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                source = "FinancialCoachOrchestrator",
                taskType = coachIntent.name,
                agentName = "FinancialCoach",
                durationMs = duration,
                resultSummary = response.text.take(100)
            ))

            response
        } catch (e: Throwable) {
            Timber.e(e, "Error processing coach request: %s", coachIntent)

            eventBus.publish(AgentEvent.AgentTaskFailed(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                source = "FinancialCoachOrchestrator",
                taskType = coachIntent.name,
                agentName = "FinancialCoach",
                error = e.message ?: "unknown",
                shouldRetry = false
            ))

            AgentResponse(
                text = if (language == "sw") "⚠️ Kuna tatizo. Jaribu tena." else "⚠️ Something went wrong. Try again.",
                type = ResponseType.ERROR
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // INTENT MAPPING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Map a general IntentResult to a FinancialCoachIntent.
     *
     * Some intents come directly from the IntentRouter (if we add
     * coach-specific patterns). Others are inferred from ASK_ADVICE
     * with financial coaching keywords.
     */
    private fun mapToCoachIntent(intentResult: IntentResult): FinancialCoachIntent {
        // Check extracted data for coach-specific signals
        val rawText = intentResult.extractedData["rawText"]?.lowercase() ?: ""
        val queryType = intentResult.extractedData["queryType"]?.lowercase() ?: ""

        return when {
            // Direct mapping from extracted data
            queryType == "spending_analysis" || rawText.contains("matumizi") || rawText.contains("spending") ->
                FinancialCoachIntent.SPENDING_ANALYSIS

            queryType == "overspending" || rawText.contains("zaidi ya") || rawText.contains("overspending") ->
                FinancialCoachIntent.OVERSPENDING_DETECTION

            queryType == "budget" || rawText.contains("bajeti") || rawText.contains("budget") ->
                FinancialCoachIntent.BUDGET_STATUS

            queryType == "savings" || rawText.contains("akiba") || rawText.contains("save") || rawText.contains("savings") ->
                FinancialCoachIntent.SAVINGS_ADVICE

            queryType == "debt" || rawText.contains("mkopo") || rawText.contains("deni") || rawText.contains("loan") ->
                FinancialCoachIntent.DEBT_STATUS

            queryType == "repayment" || rawText.contains("rudisha") || rawText.contains("repay") ->
                FinancialCoachIntent.DEBT_REPAYMENT_STRATEGY

            queryType == "borrow" || rawText.contains("kopa") || rawText.contains("borrow") ->
                FinancialCoachIntent.BORROW_ADVICE

            queryType == "health" || rawText.contains("hali") || rawText.contains("health") ->
                FinancialCoachIntent.FULL_HEALTH_CHECK

            // Default: route based on intent type
            intentResult.intent == IntentType.ASK_ADVICE -> FinancialCoachIntent.FULL_HEALTH_CHECK
            intentResult.intent == IntentType.GREETING -> FinancialCoachIntent.COACH_GREETING

            else -> FinancialCoachIntent.COACH_UNKNOWN
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // BUDGET ANALYSIS HANDLERS
    // ═══════════════════════════════════════════════════════════════

    private suspend fun handleSpendingAnalysis(language: String): AgentResponse {
        val breakdown = budgetAnalyzer.categorizeSpending(30)

        val message = buildString {
            if (language == "sw") {
                appendLine("📊 Matumizi Yako ya Mwezi:")
                appendLine()
                appendLine("💰 Jumla: KSh ${formatAmount(breakdown.totalSpending)}")
                appendLine()

                for (cat in breakdown.categories.take(5)) {
                    val emoji = categoryEmoji(cat.category)
                    appendLine("$emoji ${cat.category.name}: KSh ${formatAmount(cat.totalAmount)} (${cat.percentage}%)")
                    cat.topItems.firstOrNull()?.let {
                        appendLine("   Bidhaa kuu: ${it.item}")
                    }
                }
            } else {
                appendLine("📊 Your Monthly Spending:")
                appendLine()
                appendLine("💰 Total: KSh ${formatAmount(breakdown.totalSpending)}")
                appendLine()

                for (cat in breakdown.categories.take(5)) {
                    val emoji = categoryEmoji(cat.category)
                    appendLine("$emoji ${cat.category.name}: KSh ${formatAmount(cat.totalAmount)} (${cat.percentage}%)")
                }
            }
        }

        return AgentResponse(text = message, type = ResponseType.INFORMATION)
    }

    private suspend fun handleOverspendingDetection(language: String): AgentResponse {
        val trends = budgetAnalyzer.analyzeSpendingTrends(14)
        val increasing = trends.filter { it.direction == TrendDirection.INCREASING }

        val message = if (increasing.isEmpty()) {
            if (language == "sw") {
                "✅ Matumizi yako ni thabiti. Hakuna kitu kinachoongezeka sana."
            } else {
                "✅ Your spending is stable. Nothing increasing significantly."
            }
        } else {
            buildString {
                if (language == "sw") {
                    appendLine("⚠️ Matumizi haya yanaongezeka:")
                    appendLine()
                    for (trend in increasing) {
                        val emoji = categoryEmoji(trend.category)
                        appendLine("$emoji ${trend.category.name}: +${trend.changePercent}%")
                        appendLine("   KSh ${formatAmount(trend.previousAmount)} → KSh ${formatAmount(trend.recentAmount)}")
                    }
                    appendLine()
                    appendLine("💡 Angalia kama unaweza kupunguza haya.")
                } else {
                    appendLine("⚠️ These expenses are increasing:")
                    for (trend in increasing) {
                        appendLine("• ${trend.category.name}: +${trend.changePercent}%")
                    }
                }
            }
        }

        return AgentResponse(text = message, type = ResponseType.ADVICE)
    }

    private suspend fun handleBudgetStatus(language: String): AgentResponse {
        val advice = budgetAnalyzer.generateBudgetAdvice(language)
        return AgentResponse(text = advice, type = ResponseType.ADVICE)
    }

    private suspend fun handleBudgetCreate(language: String): AgentResponse {
        // Same as budget status for now — auto-generated from spending patterns
        val advice = budgetAnalyzer.generateBudgetAdvice(language)
        return AgentResponse(text = advice, type = ResponseType.ADVICE)
    }

    // ═══════════════════════════════════════════════════════════════
    // SAVINGS STRATEGY HANDLERS
    // ═══════════════════════════════════════════════════════════════

    private suspend fun handleSavingsAdvice(language: String): AgentResponse {
        val strategy = savingsStrategist.generateSavingsStrategy(language)
        return AgentResponse(text = strategy.message, type = ResponseType.ADVICE)
    }

    private suspend fun handleSavingsAmount(language: String): AgentResponse {
        val strategy = savingsStrategist.generateSavingsStrategy(language)
        val projection = savingsStrategist.projectSavingsGrowth(strategy.recommendedDailyAmount, language)

        val message = buildString {
            append(strategy.message)
            append("\n\n")
            append(projection)
        }

        return AgentResponse(text = message, type = ResponseType.ADVICE)
    }

    private suspend fun handleSavingsStatus(language: String): AgentResponse {
        // Get savings context from budget analyzer
        val incomePattern = budgetAnalyzer.detectIncomePattern(30)

        val message = if (language == "sw") {
            "💰 Akiba yako: Angalia kwenye M-Pesa au kasha lako la akiba.\n\n" +
            "Lengo: weka KSh ${formatAmount(incomePattern.averageDaily * 0.2)}/siku " +
            "(20% ya faida ya wastani)."
        } else {
            "💰 Your savings: Check M-Pesa or your savings box.\n\n" +
            "Target: save KSh ${formatAmount(incomePattern.averageDaily * 0.2)}/day " +
            "(20% of average profit)."
        }

        return AgentResponse(text = message, type = ResponseType.INFORMATION)
    }

    private suspend fun handleSavingsGoal(language: String): AgentResponse {
        val strategy = savingsStrategist.generateSavingsStrategy(language)

        val message = if (language == "sw") {
            "🎯 Lengo la Akiba ya Dharura:\n\n" +
            "💰 Lengo: KSh ${formatAmount(strategy.emergencyFundTarget)}\n" +
            "   (Matumizi ya miezi 3)\n\n" +
            "📅 Kwa akiba ya KSh ${formatAmount(strategy.recommendedDailyAmount)}/siku:\n" +
            "   Utafikia miezi ${formatAmount(strategy.emergencyFundTarget / (strategy.recommendedDailyAmount * 30))}\n\n" +
            "💡 Anza leo — hata KSh 50 inasaidia!"
        } else {
            "🎯 Emergency Savings Goal:\n\n" +
            "💰 Target: KSh ${formatAmount(strategy.emergencyFundTarget)}\n" +
            "   (3 months of expenses)\n\n" +
            "📅 At KSh ${formatAmount(strategy.recommendedDailyAmount)}/day:\n" +
            "   You'll reach it in ~${formatAmount(strategy.emergencyFundTarget / (strategy.recommendedDailyAmount * 30))} months"
        }

        return AgentResponse(text = message, type = ResponseType.ADVICE)
    }

    // ═══════════════════════════════════════════════════════════════
    // DEBT MANAGEMENT HANDLERS
    // ═══════════════════════════════════════════════════════════════

    private suspend fun handleDebtStatus(language: String): AgentResponse {
        val status = debtAdvisor.getDebtStatus(language)
        return AgentResponse(text = status.message, type = ResponseType.INFORMATION)
    }

    private suspend fun handleRepaymentStrategy(language: String): AgentResponse {
        val strategy = debtAdvisor.getRepaymentStrategy(language)
        return AgentResponse(text = strategy.message, type = ResponseType.ADVICE)
    }

    private suspend fun handleBorrowAdvice(intentResult: IntentResult, language: String): AgentResponse {
        val amount = intentResult.extractedData["amount"]?.replace(",", "")?.toDoubleOrNull() ?: 10000.0
        val purpose = intentResult.extractedData["purpose"] ?: intentResult.extractedData["item"] ?: "biashara"

        val advice = debtAdvisor.getBorrowAdvice(amount, purpose, language)
        return AgentResponse(text = advice.message, type = ResponseType.ADVICE)
    }

    private suspend fun handleDebtConsolidation(language: String): AgentResponse {
        val status = debtAdvisor.getDebtStatus(language)

        val message = if (status.activeLoans.size <= 1) {
            if (language == "sw") {
                "✅ Una mkopo mmoja tu. Hakuna haja ya kuchanganya."
            } else {
                "✅ You have only one loan. No consolidation needed."
            }
        } else {
            buildString {
                if (language == "sw") {
                    appendLine("📋 Kuchanganya Mikopo:")
                    appendLine()
                    appendLine("Una mikopo ${status.activeLoans.size}. Angalia kama unaweza:")
                    appendLine("1. Kuomba mkopo mmoja mkubwa wa kulipa yote")
                    appendLine("2. Lipa kwanza mkopo wa riba ya juu")
                    appendLine("3. Omba chama kukusaidia")
                    appendLine()
                    appendLine("💡 Lengo: punguza riba ya jumla na urahishe malipo.")
                } else {
                    appendLine("📋 Loan Consolidation:")
                    appendLine()
                    appendLine("You have ${status.activeLoans.size} loans. Consider:")
                    appendLine("1. Getting one larger loan to pay off all others")
                    appendLine("2. Paying highest-interest loan first")
                    appendLine("3. Asking your chama for help")
                }
            }
        }

        return AgentResponse(text = message, type = ResponseType.ADVICE)
    }

    // ═══════════════════════════════════════════════════════════════
    // FULL HEALTH CHECK
    // ═══════════════════════════════════════════════════════════════

    /**
     * Full financial health check — runs all 3 agents in sequence.
     *
     * Pipeline:
     * 1. BudgetAnalyzer: categorize spending, detect anomalies
     * 2. SavingsStrategist: generate savings strategy
     * 3. DebtAdvisor: assess debt health
     * 4. Combine into a comprehensive report
     */
    private suspend fun handleFullHealthCheck(language: String): AgentResponse {
        // Stage 1: Budget analysis
        val breakdown = budgetAnalyzer.categorizeSpending(30)
        val anomalies = budgetAnalyzer.detectSpendingAnomalies(30)
        val incomePattern = budgetAnalyzer.detectIncomePattern(30)

        // Stage 2: Savings strategy
        val savingsStrategy = savingsStrategist.generateSavingsStrategy(language)

        // Stage 3: Debt assessment
        val debtStatus = debtAdvisor.getDebtStatus(language)

        val message = buildString {
            if (language == "sw") {
                appendLine("🏥 Hali Yako ya Fedha:")
                appendLine("═══════════════════")
                appendLine()

                // Income
                appendLine("💰 MAPATO:")
                appendLine("  Wastani: KSh ${formatAmount(incomePattern.averageDaily)}/siku")
                appendLine("  Thabiti: ${if (incomePattern.incomeStability == IncomeStability.STABLE) "✅ Ndiyo" else "⚠️ La"}")
                appendLine("  Siku bora: ${incomePattern.bestDay}")
                appendLine()

                // Spending
                appendLine("📊 MATUMIZI:")
                appendLine("  Jumla: KSh ${formatAmount(breakdown.totalSpending)}/mwezi")
                val topCategory = breakdown.categories.firstOrNull()
                if (topCategory != null) {
                    appendLine("  Kikubwa: ${topCategory.category.name} (${topCategory.percentage}%)")
                }
                if (anomalies.isNotEmpty()) {
                    appendLine("  ⚠️ Matumizi ya ajabu: ${anomalies.size}")
                }
                appendLine()

                // Savings
                appendLine("🏦 AKIBA:")
                appendLine("  ${savingsStrategy.message.lines().first()}")
                appendLine()

                // Debt
                appendLine("💳 DENI:")
                appendLine("  ${debtStatus.message.lines().first()}")
                if (debtStatus.activeLoans.isNotEmpty()) {
                    appendLine("  Uwiano: ${"%.0f".format(debtStatus.debtToIncomeRatio * 100)}%")
                }

                // Overall assessment
                appendLine()
                appendLine("═══════════════════")
                val overallScore = calculateHealthScore(incomePattern, breakdown, debtStatus)
                appendLine("⭐ Alama ya jumla: $overallScore/100")
                appendLine(overallMessage(overallScore, language))
            } else {
                appendLine("🏥 Your Financial Health:")
                appendLine("═══════════════════")
                appendLine()
                appendLine("💰 INCOME: KSh ${formatAmount(incomePattern.averageDaily)}/day avg")
                appendLine("📊 SPENDING: KSh ${formatAmount(breakdown.totalSpending)}/month")
                appendLine("🏦 SAVINGS: ${savingsStrategy.message.lines().first()}")
                appendLine("💳 DEBT: ${debtStatus.message.lines().first()}")

                val overallScore = calculateHealthScore(incomePattern, breakdown, debtStatus)
                appendLine()
                appendLine("⭐ Overall Score: $overallScore/100")
                appendLine(overallMessage(overallScore, language))
            }
        }

        return AgentResponse(text = message, type = ResponseType.ADVICE)
    }

    /**
     * Calculate overall financial health score (0-100).
     */
    private fun calculateHealthScore(
        incomePattern: IncomePattern,
        breakdown: SpendingBreakdown,
        debtStatus: DebtStatus
    ): Int {
        var score = 50 // Start at baseline

        // Income stability (0-20 points)
        score += when (incomePattern.incomeStability) {
            IncomeStability.STABLE -> 20
            IncomeStability.MODERATE -> 15
            IncomeStability.VOLATILE -> 8
            IncomeStability.HIGHLY_VOLATILE -> 3
            IncomeStability.NO_DATA -> 0
        }

        // Debt health (0-20 points)
        score += when (debtStatus.healthLevel) {
            DebtHealth.HEALTHY -> 20
            DebtHealth.WARNING -> 10
            DebtHealth.DANGER -> -5
            DebtHealth.CRITICAL -> -20
        }

        // Spending discipline (0-10 points)
        if (breakdown.totalSpending > 0 && incomePattern.averageDaily > 0) {
            val monthlyIncome = incomePattern.averageDaily * 30
            val spendingRatio = breakdown.totalSpending / monthlyIncome
            score += when {
                spendingRatio < 0.7 -> 10
                spendingRatio < 0.9 -> 5
                spendingRatio < 1.0 -> 0
                else -> -10
            }
        }

        return score.coerceIn(0, 100)
    }

    private fun overallMessage(score: Int, language: String): String {
        return if (language == "sw") {
            when {
                score >= 80 -> "🌟 Nzuri sana! Fedha zako ziko vizuri. Endelea hivi!"
                score >= 60 -> "👍 Vizuri! Kuna nafasi ya kuboresha. Fuata ushauri hapo juu."
                score >= 40 -> "⚠️ Wastani. Angalia matumizi na deni lako."
                else -> "🚨 Hali ngazi! Anza na: rekodi kila siku, punguza deni, weka akiba ndogo."
            }
        } else {
            when {
                score >= 80 -> "🌟 Excellent! Your finances are in great shape."
                score >= 60 -> "👍 Good! Room for improvement. Follow the advice above."
                score >= 40 -> "⚠️ Average. Check your spending and debt."
                else -> "🚨 Needs attention! Start with: record daily, reduce debt, save small."
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // COACH GREETING & UNKNOWN
    // ═══════════════════════════════════════════════════════════════

    private suspend fun handleCoachGreeting(language: String): AgentResponse {
        val incomePattern = budgetAnalyzer.detectIncomePattern(7)

        val message = if (language == "sw") {
            "👋 Habari! Mimi ni Msaidizi, CFO wako.\n\n" +
            "Ninaweza kukusaidia:\n" +
            "• 📊 Angalia matumizi yako\n" +
            "• 💰 Panga akiba yako\n" +
            "• 🏦 Simamia mikopo yako\n" +
            "• 🏥 Angalia hali ya fedha\n\n" +
            if (incomePattern.averageDaily > 0) {
                "Faida yako ya wiki hii: KSh ${formatAmount(incomePattern.averageDaily)}/siku. "
            } else {
                "Anza kurekodi mauzo yako leo. "
            } + "Unahitaji nini?"
        } else {
            "👋 Hello! I'm Msaidizi, your CFO.\n\n" +
            "I can help you:\n" +
            "• 📊 Check your spending\n" +
            "• 💰 Plan your savings\n" +
            "• 🏦 Manage your loans\n" +
            "• 🏥 Check financial health\n\n" +
            "What do you need?"
        }

        return AgentResponse(text = message, type = ResponseType.GREETING)
    }

    private fun handleCoachUnknown(language: String): AgentResponse {
        val message = if (language == "sw") {
            "💡 Sijaelewa. Unaweza kuniambia:\n" +
            "• 'Matumizi yangu' — angalia unavyotumia\n" +
            "• 'Akiba' — panga akiba\n" +
            "• 'Mkopo' — angalia deni lako\n" +
            "• 'Hali ya fedha' — angalia kila kitu"
        } else {
            "💡 I didn't understand. You can tell me:\n" +
            "• 'My spending' — check expenses\n" +
            "• 'Savings' — plan savings\n" +
            "• 'My loan' — check debt\n" +
            "• 'Financial health' — full check"
        }

        return AgentResponse(text = message, type = ResponseType.CLARIFICATION)
    }

    // ═══════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════

    private fun categoryEmoji(category: CategoryType): String {
        return when (category) {
            CategoryType.BUSINESS -> "📦"
            CategoryType.TRANSPORT -> "🚗"
            CategoryType.FOOD -> "🍽️"
            CategoryType.HOUSING -> "🏠"
            CategoryType.PERSONAL -> "👤"
            CategoryType.EDUCATION -> "📚"
            CategoryType.HEALTH -> "🏥"
            CategoryType.GIVING -> "🤲"
            CategoryType.OTHER -> "📋"
        }
    }

    private fun formatAmount(amount: Double): String {
        return if (amount >= 1_000_000) {
            String.format("%.1fM", amount / 1_000_000.0)
        } else if (amount >= 1_000) {
            String.format("%,.0f", amount)
        } else {
            String.format("%.0f", amount)
        }
    }
}
