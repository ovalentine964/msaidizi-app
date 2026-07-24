package com.msaidizi.app.superagent.financial

import timber.log.Timber
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * CFO Engine — Proactive financial management for informal workers.
 *
 * The difference between an "assistant" and a "CFO":
 * - Assistant: reactive, answers questions when asked
 * - CFO: proactive, drives financial health without being asked
 *
 * Msaidizi doesn't wait for the worker to ask "how am I doing?"
 * It tells them every morning. It alerts them before problems hit.
 * It recommends actions, not just answers questions.
 *
 * ## Capabilities
 * - **Daily briefings:** Delivered every morning without being asked
 * - **Cash flow forecasts:** When money is running low
 * - **Restock alerts:** Before stockouts happen
 * - **Savings recommendations:** Based on today's profit
 * - **Risk alerts:** Market changes, trends, anomalies
 * - **Weekly/monthly reports:** Periodic summaries
 *
 * ## Academic Foundations
 * - **ECO 201 (Microeconomics):** Profit maximization, cost minimization
 * - **ECO 206 (Microfinance):** Credit readiness, savings behavior
 * - **STA 341 (Estimation):** Revenue forecasting, confidence intervals
 * - **STA 342 (Hypothesis Testing):** Is business improving?
 * - **FIN 201 (Corporate Finance):** Capital allocation, working capital
 *
 * @author Msaidizi Financial Team
 */
class CFOEngine(
    private val pnfCalculator: PnLCalculator = PnLCalculator(),
    private val cashFlowPredictor: CashFlowPredictor = CashFlowPredictor(),
    private val inventoryTracker: InventoryTracker = InventoryTracker()
) {

    companion object {
        private const val TAG = "CFOEngine"

        /** Minimum transactions for reliable daily briefing */
        private const val MIN_TRANSACTIONS_FOR_BRIEFING = 3

        /** Savings target as percentage of daily profit */
        private const val SAVINGS_TARGET_PERCENT = 0.20  // 20%

        /** Emergency fund target in KSh */
        private const val EMERGENCY_FUND_TARGET = 10_000.0

        /** Days of stock remaining before alert */
        private const val LOW_STOCK_DAYS_THRESHOLD = 3.0

        /** Cash flow warning threshold in days */
        private const val CASH_FLOW_WARNING_DAYS = 7
    }

    // ═══════════════════════════════════════════════════════════════
    // DAILY BRIEFING — delivered every morning without being asked
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate proactive daily briefing.
     *
     * "Hii leo, umefanya mauzo ya KSh 3,200. Faida ni KSh 800.
     *  Unashauriwa kununua nyanya zaidi kesho — bei itapanda."
     *
     * @param workerName Worker's name (for personalization)
     * @param assistantName What worker calls Msaidizi
     * @param todayTransactions Today's transactions so far
     * @param yesterdayTransactions Yesterday's transactions (for comparison)
     * @param recentTransactions Last 7 days of transactions (for trends)
     * @return [DailyBriefing] with Swahili message and data
     */
    fun getDailyBriefing(
        workerName: String,
        assistantName: String,
        todayTransactions: List<Transaction>,
        yesterdayTransactions: List<Transaction>,
        recentTransactions: List<Transaction>
    ): DailyBriefing {
        // Guard: no data at all
        if (todayTransactions.isEmpty() && yesterdayTransactions.isEmpty() && recentTransactions.isEmpty()) {
            return DailyBriefing(
                message = "Habari $workerName! $assistantName wako hapa. Bado hakuna data ya mauzo. Anza kurekodi leo!",
                todaySales = 0.0,
                todayExpenses = 0.0,
                todayProfit = 0.0,
                yesterdaySales = 0.0,
                yesterdayProfit = 0.0,
                salesTrendPercent = 0,
                topSellingItem = null,
                savingsRecommendation = 0.0
            )
        }

        val todaySales = todayTransactions
            .filter { it.type == TransactionType.SALE }
            .sumOf { it.totalAmount }
        val todayExpenses = todayTransactions
            .filter { it.type == TransactionType.EXPENSE || it.type == TransactionType.PURCHASE }
            .sumOf { it.totalAmount }
        val todayProfit = todaySales - todayExpenses

        val yesterdaySales = yesterdayTransactions
            .filter { it.type == TransactionType.SALE }
            .sumOf { it.totalAmount }
        val yesterdayProfit = yesterdaySales - yesterdayTransactions
            .filter { it.type == TransactionType.EXPENSE || it.type == TransactionType.PURCHASE }
            .sumOf { it.totalAmount }

        // Trend comparison (STA 244: time series)
        val salesTrend = if (yesterdaySales > 0) {
            ((todaySales - yesterdaySales) / yesterdaySales * 100).roundToInt()
        } else if (todaySales > 0) 100 else 0

        // Top selling item today
        val topItem = todayTransactions
            .filter { it.type == TransactionType.SALE }
            .groupBy { it.item }
            .maxByOrNull { it.value.sumOf { t -> t.totalAmount } }
            ?.key

        // Build the briefing message
        val message = buildString {
            append("Habari $workerName! Hii ni $assistantName wako.\n\n")

            if (todayTransactions.isEmpty()) {
                append("Leo bado hujafanya mauzo. ")
                append("Jana ulifanya mauzo ya KSh ${formatAmount(yesterdaySales)}. ")

                if (yesterdayProfit > 0) {
                    append("Faida ilikuwa KSh ${formatAmount(yesterdayProfit)}. ")
                }

                append("\nLengo la leo: piga mauzo zaidi ya jana!")
            } else {
                append("Leo umefanya mauzo ya KSh ${formatAmount(todaySales)}. ")
                append("Faida ni KSh ${formatAmount(todayProfit)}. ")

                if (salesTrend > 0) {
                    append("Ni ${abs(salesTrend)}% zaidi ya jana! 👍")
                } else if (salesTrend < 0) {
                    append("Ni ${abs(salesTrend)}% chini ya jana. Hebu tuongeze bidii!")
                } else {
                    append("Sawa na jana.")
                }

                topItem?.let {
                    append("\n\nBidhaa inayouza zaidi leo: $it")
                }
            }

            // Savings recommendation
            if (todayProfit > 0) {
                val savingsAmount = (todayProfit * SAVINGS_TARGET_PERCENT).roundToInt()
                append("\n\nUshauri: weka KSh $savingsAmount kwenye akiba ya dharura. ")
                append("(Lengo: KSh ${formatAmount(EMERGENCY_FUND_TARGET)})")
            }
        }

        return DailyBriefing(
            message = message,
            todaySales = todaySales,
            todayExpenses = todayExpenses,
            todayProfit = todayProfit,
            yesterdaySales = yesterdaySales,
            yesterdayProfit = yesterdayProfit,
            salesTrendPercent = salesTrend,
            topSellingItem = topItem,
            savingsRecommendation = max(0.0, todayProfit * SAVINGS_TARGET_PERCENT)
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // CASH FLOW FORECAST — when will money run out?
    // ═══════════════════════════════════════════════════════════════

    /**
     * Cash flow forecast — predicts when working capital runs out.
     *
     * "Kwa kasi hii, pesa yako ya biashara itaisha siku 12."
     *
     * @param currentCash Current cash on hand
     * @param dailyRevenues Daily revenue for last 28 days
     * @param dailyExpenses Daily expenses for last 28 days
     * @return [CashFlowForecast] with days remaining and message
     */
    fun getCashFlowForecast(
        currentCash: Double,
        dailyRevenues: List<Double>,
        dailyExpenses: List<Double>
    ): CashFlowForecast {
        return cashFlowPredictor.predict(
            currentCash = currentCash,
            dailyRevenues = dailyRevenues,
            dailyExpenses = dailyExpenses
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // RESTOCK RECOMMENDATION — what to buy, when
    // ═══════════════════════════════════════════════════════════════

    /**
     * Restock recommendation based on sales velocity and stock levels.
     *
     * "Nunua nyanya — una siku 2 tu za stock."
     *
     * @param inventory Current inventory
     * @param recentSales Last 14 days of sales
     * @return [RestockAdvice] with items to restock and urgency
     */
    fun getRestockRecommendation(
        inventory: Map<String, InventoryItem>,
        recentSales: List<Transaction>
    ): RestockAdvice {
        if (recentSales.isEmpty() && inventory.isEmpty()) {
            return RestockAdvice(
                message = "Hakuna data ya mauzo bado. Rekodi mauzo yako ili uone ushauri wa kununua bidhaa.",
                items = emptyList()
            )
        }

        val velocity = inventoryTracker.calculateVelocity(recentSales)
        val lowStockItems = inventoryTracker.getLowStockItems(inventory, velocity)

        val message = if (lowStockItems.isEmpty()) {
            "Stock yako iko sawa. Hakuna bidhaa inayohitajika kununua sasa hivi."
        } else {
            val urgent = lowStockItems.filter { it.urgency == RestockUrgency.CRITICAL }
            buildString {
                if (urgent.isNotEmpty()) {
                    append("🚨 Haraka! Kununua:\n")
                    urgent.forEach { item ->
                        append("• ${item.item.replaceFirstChar { it.uppercase() }}: ")
                        append("imabaki siku ${item.daysOfStockRemaining.toInt()}. ")
                        append("Nunua vipande ${item.suggestedQuantity} ")
                        append("(~KSh ${formatAmount(item.estimatedCost)})\n")
                    }
                }
                val others = lowStockItems.filter { it.urgency != RestockUrgency.CRITICAL }
                if (others.isNotEmpty()) {
                    if (urgent.isNotEmpty()) append("\n")
                    append("Pia fikiria kununua:\n")
                    others.forEach { item ->
                        append("• ${item.item.replaceFirstChar { it.uppercase() }}: ")
                        append("siku ${item.daysOfStockRemaining.toInt()} zimebaki\n")
                    }
                }
            }
        }

        return RestockAdvice(
            message = message,
            items = lowStockItems.sortedBy { it.urgency.ordinal }
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // SAVINGS RECOMMENDATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Savings recommendation based on today's profit and savings history.
     *
     * "Leo, weka KSh 200 kwenye akaunti ya dharura. Umefikia 60% ya lengo."
     *
     * @param todayProfit Today's profit so far
     * @param totalSaved Total saved so far
     * @return [SavingsAdvice] with amount and message
     */
    fun getSavingsRecommendation(
        todayProfit: Double,
        totalSaved: Double
    ): SavingsAdvice {
        val recommendedAmount = max(0.0, todayProfit * SAVINGS_TARGET_PERCENT)
        val progressPercent = ((totalSaved / EMERGENCY_FUND_TARGET) * 100).roundToInt()
            .coerceIn(0, 100)

        val message = when {
            todayProfit <= 0 ->
                "Leo bado hujapata faida. Usijali — kesho ni siku mpya!"
            progressPercent >= 100 ->
                "🎉 Hongera! Umefikia lengo la akiba ya dharura! " +
                "Sasa fikiria kuwekeza kwenye biashara yako."
            recommendedAmount > 0 ->
                "Leo, weka KSh ${formatAmount(recommendedAmount)} kwenye akiba. " +
                "Umefikia $progressPercent% ya lengo la KSh ${formatAmount(EMERGENCY_FUND_TARGET)}."
            else ->
                "Akiba yako iko njiani! Umefikia $progressPercent% ya lengo."
        }

        return SavingsAdvice(
            message = message,
            recommendedAmount = recommendedAmount,
            totalSaved = totalSaved,
            targetAmount = EMERGENCY_FUND_TARGET,
            progressPercent = progressPercent
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // RISK ALERTS — identify threats to business
    // ═══════════════════════════════════════════════════════════════

    /**
     * Risk alerts — identify threats before they become problems.
     *
     * "Bei ya nyanya imepungua 20% wiki hii. Fikiria kuuza pilipili badala yake."
     *
     * @param recentTransactions Last 14 days of transactions
     * @param olderTransactions Days 15-28 (for trend comparison)
     * @return List of [RiskAlert]
     */
    fun getRiskAlerts(
        recentTransactions: List<Transaction>,
        olderTransactions: List<Transaction>
    ): List<RiskAlert> {
        val alerts = mutableListOf<RiskAlert>()

        // Guard: no data at all
        if (recentTransactions.isEmpty() && olderTransactions.isEmpty()) {
            return listOf(
                RiskAlert(
                    type = RiskType.NONE,
                    severity = RiskSeverity.NONE,
                    message = "Biashara yako iko salama! Hakuna hatari zilizogunduliwa.",
                    recommendation = "Endelea kurekodi mauzo yako kila siku."
                )
            )
        }

        // Check for declining revenue trend (STA 244)
        val recentSales = recentTransactions.filter { it.type == TransactionType.SALE }.sumOf { it.totalAmount }
        val olderSales = olderTransactions.filter { it.type == TransactionType.SALE }.sumOf { it.totalAmount }

        if (olderSales > 0 && recentSales < olderSales * 0.7) {
            val decline = ((olderSales - recentSales) / olderSales * 100).roundToInt()
            alerts.add(
                RiskAlert(
                    type = RiskType.REVENUE_DECLINE,
                    severity = if (decline > 30) RiskSeverity.HIGH else RiskSeverity.MEDIUM,
                    message = "⚠️ Mauzo yamepungua $decline% wiki hii. " +
                        "Fikiria kubadilisha bei au kuongeza bidhaa mpya.",
                    recommendation = "Angalia bei za wapinzani wako na urekebishe."
                )
            )
        }

        // Check for margin compression
        val recentProfit = recentSales - recentTransactions
            .filter { it.type == TransactionType.EXPENSE || it.type == TransactionType.PURCHASE }
            .sumOf { it.totalAmount }
        val olderProfit = olderSales - olderTransactions
            .filter { it.type == TransactionType.EXPENSE || it.type == TransactionType.PURCHASE }
            .sumOf { it.totalAmount }

        val recentMargin = if (recentSales > 0) (recentProfit / recentSales).coerceIn(-1.0, 1.0) else 0.0
        val olderMargin = if (olderSales > 0) (olderProfit / olderSales).coerceIn(-1.0, 1.0) else 0.0

        if (recentMargin < olderMargin * 0.8 && olderMargin > 0) {
            alerts.add(
                RiskAlert(
                    type = RiskType.MARGIN_COMPRESSION,
                    severity = RiskSeverity.MEDIUM,
                    message = "Faida yako imepungua. Gharama zinaongezeka haraka kuliko mauzo.",
                    recommendation = "Punguza gharama au ongeza bei kidogo."
                )
            )
        }

        // Check for single-item dependency
        val salesByItem = recentTransactions
            .filter { it.type == TransactionType.SALE }
            .groupBy { it.item }
        val totalSalesAmount = salesByItem.values.sumOf { it.sumOf { t -> t.totalAmount } }
        val topItemShare = if (totalSalesAmount > 0) {
            salesByItem.values.maxOfOrNull { it.sumOf { t -> t.totalAmount } }?.let {
                it / totalSalesAmount
            } ?: 0.0
        } else 0.0

        if (topItemShare > 0.6 && salesByItem.size > 1) {
            alerts.add(
                RiskAlert(
                    type = RiskType.CONCENTRATION_RISK,
                    severity = RiskSeverity.MEDIUM,
                    message = "Biashara yako inategemea bidhaa moja sana. " +
                        "Ikiwa bei ya bidhaa hiyo itapungua, utapata hasara.",
                    recommendation = "Ongeza bidhaa mpya ili kupunguza hatari."
                )
            )
        }

        // Check for irregular transaction patterns
        val activeDays = recentTransactions
            .groupBy { it.createdAt / 86400 }
            .size
        if (activeDays < 7 && recentTransactions.isNotEmpty()) {
            alerts.add(
                RiskAlert(
                    type = RiskType.IRREGULAR_ACTIVITY,
                    severity = RiskSeverity.LOW,
                    message = "Umerekodi mauzo siku $activeDays tu kati ya 14. " +
                        "Rekodi kila siku ili uone picha kamili ya biashara yako.",
                    recommendation = "Jaribu kurekodi mauzo yako kila siku, hata kama ni machache."
                )
            )
        }

        if (alerts.isEmpty()) {
            alerts.add(
                RiskAlert(
                    type = RiskType.NONE,
                    severity = RiskSeverity.NONE,
                    message = "Biashara yako iko salama! Hakuna hatari zilizogunduliwa.",
                    recommendation = "Endelea kurekodi mauzo yako kila siku."
                )
            )
        }

        return alerts
    }

    // ═══════════════════════════════════════════════════════════════
    // WEEKLY REPORT — P&L, trends, recommendations
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate weekly financial report.
     *
     * @param workerName Worker's name
     * @param assistantName What worker calls Msaidizi
     * @param thisWeek This week's transactions
     * @param lastWeek Last week's transactions (for comparison)
     * @return Weekly P&L statement
     */
    fun getWeeklyReport(
        workerName: String,
        assistantName: String,
        thisWeek: List<Transaction>,
        lastWeek: List<Transaction>
    ): PnLStatement {
        val current = pnfCalculator.calculateWeekly(thisWeek, workerName)
        val previous = pnfCalculator.calculateWeekly(lastWeek)

        val comparison = pnfCalculator.compare(current, previous)

        return current.copy(
            message = current.message + "\n\n" + comparison
        )
    }

    /**
     * Format amount for display.
     */
    private fun formatAmount(amount: Double): String {
        return when {
            amount >= 1_000_000 -> String.format("%.1fM", amount / 1_000_000.0)
            amount >= 1_000 -> String.format("%,.0f", amount)
            else -> String.format("%.0f", amount)
        }
    }
}
