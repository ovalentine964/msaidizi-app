package com.msaidizi.app.cfo

import com.msaidizi.app.agent.WorkerType
import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Msaidizi CFO Engine — Proactive financial management for informal workers.
 *
 * The difference between an "assistant" and a "CFO":
 * - Assistant: reactive, answers questions when asked
 * - CFO: proactive, drives financial health without being asked
 *
 * Msaidizi doesn't wait for the worker to ask "how am I doing?"
 * It tells them every morning. It alerts them before problems hit.
 * It recommends actions, not just answers questions.
 *
 * This engine generates the insights that get delivered proactively:
 * - Daily briefings (every morning)
 * - Cash flow forecasts (when money is running low)
 * - Restock alerts (before stockouts)
 * - Savings recommendations (based on today's profit)
 * - Credit readiness tracking (building toward formal finance)
 * - Weekly/monthly reports (without being asked)
 * - Risk alerts (market changes, trends)
 *
 * Academic foundations:
 * - ECO 201 (Micro): Profit maximization, cost minimization
 * - ECO 206 (Microfinance): Credit readiness, savings behavior
 * - ECO 210 (Quant Methods): Break-even analysis, optimization
 * - STA 341 (Estimation): Revenue forecasting, confidence intervals
 * - STA 342 (Hypothesis Testing): Is business improving?
 * - STA 244 (Time Series): Trend detection in revenue/expenses
 * - FIN 201 (Corporate Finance): Capital allocation, working capital
 */
class CFOEngine {

    companion object {
        /** Minimum transactions for reliable daily briefing */
        private const val MIN_TRANSACTIONS_FOR_BRIEFING = 3

        /** Savings target as percentage of daily profit */
        private const val SAVINGS_TARGET_PERCENT = 0.20  // 20%

        /** Emergency fund target in KSh */
        private const val EMERGENCY_FUND_TARGET = 10_000.0

        /** Credit readiness threshold (score out of 100) */
        private const val CREDIT_READY_THRESHOLD = 60

        /** Days of stock remaining before alert */
        private const val LOW_STOCK_DAYS_THRESHOLD = 3.0

        /** Cash flow warning threshold in days */
        private const val CASH_FLOW_WARNING_DAYS = 7
    }

    // ═══════════════════════════════════════════════════════════════
    // DAILY BRIEFING — delivered every morning without being asked
    // ═══════════════════════════════════════════════════════════════

    /**
     * Proactive daily briefing — the core CFO value.
     *
     * "Hii leo, umefanya mauzo ya KSh 3,200. Faida ni KSh 800.
     *  Unashauriwa kununua nyanya zaidi kesho — bei itapanda."
     *
     * Delivered every morning. No need to ask.
     *
     * @param workerName Worker's name (for personalization)
     * @param assistantName What worker calls Msaidizi
     * @param todayTransactions Today's transactions so far
     * @param yesterdayTransactions Yesterday's transactions (for comparison)
     * @param recentTransactions Last 7 days of transactions (for trends)
     * @return DailyBriefing with Swahili message and data
     */
    fun getDailyBriefing(
        workerName: String,
        assistantName: String,
        todayTransactions: List<Transaction>,
        yesterdayTransactions: List<Transaction>,
        recentTransactions: List<Transaction>
    ): DailyBriefing {
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
        } else 0

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
                // Morning briefing before any sales
                append("Leo bado hujafanya mauzo. ")
                append("Jana ulifanya mauzo ya KSh ${formatAmount(yesterdaySales)}. ")

                if (yesterdayProfit > 0) {
                    append("Faida ilikuwa KSh ${formatAmount(yesterdayProfit)}. ")
                }

                append("\nLengo la leo: piga mauzo zaidi ya jana!")
            } else {
                // Has sales today
                append("Leo umefanya mauzo ya KSh ${formatAmount(todaySales)}. ")
                append("Faida ni KSh ${formatAmount(todayProfit)}. ")

                if (salesTrend > 0) {
                    append("Ni ${abs(salesTrend)}% zaidi ya jana! 👍")
                } else if (salesTrend < 0) {
                    append("Ni ${abs(salesTrend)}% chini ya jana. ")
                    append("Hebu tuongeze bidii!")
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
     * STA 341 (Estimation): Uses moving average for forecast.
     *
     * @param currentCash Current cash on hand
     * @param dailyExpenses Average daily expenses (last 7 days)
     * @param dailyRevenue Average daily revenue (last 7 days)
     * @return CashFlowForecast with days remaining and message
     */
    fun getCashFlowForecast(
        currentCash: Double,
        dailyExpenses: Double,
        dailyRevenue: Double
    ): CashFlowForecast {
        val netDailyBurn = dailyExpenses - dailyRevenue
        val daysRemaining = if (netDailyBurn > 0) {
            (currentCash / netDailyBurn).toInt()
        } else {
            // Revenue exceeds expenses — cash is growing
            Int.MAX_VALUE
        }

        val message = when {
            daysRemaining == Int.MAX_VALUE ->
                "Biashara yako inakua! Unapata zaidi ya unavyotumia."
            daysRemaining <= CASH_FLOW_WARNING_DAYS ->
                "⚠️ Tahadhari: Kwa kasi hii, pesa yako ya biashara itaisha siku $daysRemaining. " +
                "Ongeza mauzo au punguza matumizi."
            daysRemaining <= 30 ->
                "Pesa yako ya biashara itatoshea siku $daysRemaining. " +
                "Fikiria kupunguza gharama au kuongeza mauzo."
            else ->
                "Biashara yako iko salama. Pesa inatoshea siku $daysRemaining+."
        }

        return CashFlowForecast(
            message = message,
            currentCash = currentCash,
            dailyBurnRate = netDailyBurn,
            daysRemaining = daysRemaining,
            isHealthy = daysRemaining > 30 || daysRemaining == Int.MAX_VALUE
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // RESTOCK RECOMMENDATION — what to buy, when, from where
    // ═══════════════════════════════════════════════════════════════

    /**
     * Restock recommendation based on sales velocity and stock levels.
     *
     * "Nunua nyanya kutoka kwa Supplier A — ni 15% bei rahisi kuliko Supplier B."
     *
     * @param recentSales Last 14 days of sales
     * @param currentStock Map of item → quantity in stock
     * @return RestockAdvice with items to restock and urgency
     */
    fun getRestockRecommendation(
        recentSales: List<Transaction>,
        currentStock: Map<String, Double>
    ): RestockAdvice {
        // Calculate daily sales velocity per item (STA 341)
        val salesByItem = recentSales
            .filter { it.type == TransactionType.SALE }
            .groupBy { it.item.lowercase() }

        val recommendations = mutableListOf<RestockItem>()

        for ((item, sales) in salesByItem) {
            val totalSold = sales.sumOf { it.quantity }
            val daysOfData = 14.0  // 2 weeks of data
            val dailyVelocity = totalSold / daysOfData

            val stockRemaining = currentStock[item.lowercase()] ?: 0.0
            val daysOfStock = if (dailyVelocity > 0) stockRemaining / dailyVelocity else Double.MAX_VALUE

            if (daysOfStock <= LOW_STOCK_DAYS_THRESHOLD) {
                // Need to restock
                val suggestedQuantity = (dailyVelocity * 7).roundToInt()  // 1 week supply
                val avgCost = sales.map { it.unitPrice }.average()

                recommendations.add(
                    RestockItem(
                        item = item,
                        currentStock = stockRemaining,
                        dailyVelocity = dailyVelocity,
                        daysOfStockRemaining = daysOfStock,
                        suggestedQuantity = suggestedQuantity,
                        estimatedCost = suggestedQuantity * avgCost,
                        urgency = when {
                            daysOfStock <= 1 -> RestockUrgency.CRITICAL
                            daysOfStock <= 2 -> RestockUrgency.HIGH
                            else -> RestockUrgency.MEDIUM
                        }
                    )
                )
            }
        }

        val message = if (recommendations.isEmpty()) {
            "Stock yako iko sawa. Hakuna bidhaa inayohitajika kununua sasa hivi."
        } else {
            val urgent = recommendations.filter { it.urgency == RestockUrgency.CRITICAL }
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
                val others = recommendations.filter { it.urgency != RestockUrgency.CRITICAL }
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
            items = recommendations.sortedBy { it.urgency.ordinal }
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
     * ECO 206 (Microfinance): Savings behavior and financial inclusion.
     *
     * @param todayProfit Today's profit so far
     * @param totalSaved Total saved so far
     * @return SavingsAdvice with amount and message
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
    // CREDIT READINESS — is the worker ready for a loan?
    // ═══════════════════════════════════════════════════════════════

    /**
     * Credit readiness assessment — is the worker ready for formal finance?
     *
     * "Uko tayari kwa mkopo wa KSh 10,000. Alama yako ni 72/100."
     *
     * ECO 206 (Microfinance): Credit scoring for informal workers.
     *
     * Factors:
     * - Transaction consistency (how regular are sales?)
     * - Record keeping (how many days with recorded transactions?)
     * - Profit margin (is the business profitable?)
     * - Savings behavior (do they save?)
     *
     * @param transactions Last 30 days of transactions
     * @param totalSaved Total savings
     * @return CreditReadiness with score and message
     */
    fun getCreditReadiness(
        transactions: List<Transaction>,
        totalSaved: Double
    ): CreditReadiness {
        // Factor 1: Transaction consistency (0-25 points)
        val activeDays = transactions
            .groupBy { it.createdAt / 86400 }
            .size
        val consistencyScore = ((activeDays / 30.0) * 25).roundToInt().coerceIn(0, 25)

        // Factor 2: Record keeping depth (0-25 points)
        val totalTxns = transactions.size
        val recordScore = when {
            totalTxns >= 100 -> 25
            totalTxns >= 50 -> 20
            totalTxns >= 20 -> 15
            totalTxns >= 10 -> 10
            else -> 5
        }

        // Factor 3: Profit margin (0-25 points)
        val sales = transactions.filter { it.type == TransactionType.SALE }.sumOf { it.totalAmount }
        val costs = transactions.filter {
            it.type == TransactionType.EXPENSE || it.type == TransactionType.PURCHASE
        }.sumOf { it.totalAmount }
        val margin = if (sales > 0) (sales - costs) / sales else 0.0
        val marginScore = when {
            margin >= 0.30 -> 25
            margin >= 0.20 -> 20
            margin >= 0.10 -> 15
            margin >= 0.05 -> 10
            else -> 5
        }

        // Factor 4: Savings behavior (0-25 points)
        val savingsScore = when {
            totalSaved >= EMERGENCY_FUND_TARGET -> 25
            totalSaved >= EMERGENCY_FUND_TARGET * 0.5 -> 20
            totalSaved >= EMERGENCY_FUND_TARGET * 0.25 -> 15
            totalSaved > 0 -> 10
            else -> 0
        }

        val totalScore = consistencyScore + recordScore + marginScore + savingsScore
        val isReady = totalScore >= CREDIT_READY_THRESHOLD

        val estimatedLoanAmount = when {
            totalScore >= 80 -> 50_000
            totalScore >= 70 -> 20_000
            totalScore >= 60 -> 10_000
            totalScore >= 50 -> 5_000
            else -> 0
        }

        val message = if (isReady) {
            "Alama yako ya mkopo ni $totalScore/100. " +
            "Uko tayari kwa mkopo wa hadi KSh ${formatAmount(estimatedLoanAmount.toDouble())}! " +
            "Rekodi zako nzuri zitakusaidia."
        } else {
            "Alama yako ya mkopo ni $totalScore/100. " +
            "Bado huja tayari, lakini uko njiani! " +
            "Endelea kurekodi mauzo yako kila siku."
        }

        val breakdown = mapOf(
            "Usahihi wa mauzo" to consistencyScore,
            "Rekodi za biashara" to recordScore,
            "Faida ya biashara" to marginScore,
            "Tabia ya kuhifadhi" to savingsScore
        )

        return CreditReadiness(
            message = message,
            score = totalScore,
            isReady = isReady,
            estimatedLoanAmount = estimatedLoanAmount,
            breakdown = breakdown
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // WEEKLY REPORT — P&L, trends, recommendations
    // ═══════════════════════════════════════════════════════════════

    /**
     * Weekly financial report — delivered every Monday without being asked.
     *
     * @param workerName Worker's name
     * @param assistantName What worker calls Msaidizi
     * @param thisWeek This week's transactions
     * @param lastWeek Last week's transactions (for comparison)
     * @return WeeklyReport with summary and message
     */
    fun getWeeklyReport(
        workerName: String,
        assistantName: String,
        thisWeek: List<Transaction>,
        lastWeek: List<Transaction>
    ): WeeklyReport {
        val weekSales = thisWeek.filter { it.type == TransactionType.SALE }.sumOf { it.totalAmount }
        val weekExpenses = thisWeek.filter {
            it.type == TransactionType.EXPENSE || it.type == TransactionType.PURCHASE
        }.sumOf { it.totalAmount }
        val weekProfit = weekSales - weekExpenses

        val lastWeekSales = lastWeek.filter { it.type == TransactionType.SALE }.sumOf { it.totalAmount }
        val lastWeekProfit = lastWeekSales - lastWeek.filter {
            it.type == TransactionType.EXPENSE || it.type == TransactionType.PURCHASE
        }.sumOf { it.totalAmount }

        val salesGrowth = if (lastWeekSales > 0) {
            ((weekSales - lastWeekSales) / lastWeekSales * 100).roundToInt()
        } else 0

        val bestDay = thisWeek
            .filter { it.type == TransactionType.SALE }
            .groupBy { it.createdAt / 86400 }
            .maxByOrNull { it.value.sumOf { t -> t.totalAmount } }
            ?.let { "Siku bora zaidi: mauzo ya KSh ${formatAmount(it.value.sumOf { t -> t.totalAmount })}" }
            ?: ""

        val topProduct = thisWeek
            .filter { it.type == TransactionType.SALE }
            .groupBy { it.item }
            .maxByOrNull { it.value.sumOf { t -> t.totalAmount } }
            ?.key

        val message = buildString {
            append("📋 Ripoti ya Wiki — $workerName\n")
            append("Kutoka $assistantName wako\n\n")
            append("Mauzo: KSh ${formatAmount(weekSales)}\n")
            append("Gharama: KSh ${formatAmount(weekExpenses)}\n")
            append("Faida: KSh ${formatAmount(weekProfit)}\n\n")

            if (salesGrowth > 0) {
                append("📈 Mauzo yaliongezeka $salesGrowth% kuliko wiki iliyopita!\n")
            } else if (salesGrowth < 0) {
                append("📉 Mauzo yalipungua ${abs(salesGrowth)}% kuliko wiki iliyopita.\n")
            } else {
                append("➡️ Mauzo sawa na wiki iliyopita.\n")
            }

            if (bestDay.isNotEmpty()) append("\n$bestDay")
            topProduct?.let { append("\nBidhaa bora: $it") }

            // Weekly savings recommendation
            if (weekProfit > 0) {
                val weeklySavings = (weekProfit * SAVINGS_TARGET_PERCENT).roundToInt()
                append("\n\nUshauri: weka KSh $weeklySavings kwenye akiba wiki hii.")
            }
        }

        return WeeklyReport(
            message = message,
            totalSales = weekSales,
            totalExpenses = weekExpenses,
            totalProfit = weekProfit,
            salesGrowthPercent = salesGrowth,
            topProduct = topProduct,
            transactionCount = thisWeek.size
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
     * STA 342 (Hypothesis Testing): Is this trend statistically significant?
     *
     * @param recentTransactions Last 14 days of transactions
     * @param olderTransactions Days 15-28 (for trend comparison)
     * @return List of RiskAlert
     */
    fun getRiskAlerts(
        recentTransactions: List<Transaction>,
        olderTransactions: List<Transaction>
    ): List<RiskAlert> {
        val alerts = mutableListOf<RiskAlert>()

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

        val recentMargin = if (recentSales > 0) recentProfit / recentSales else 0.0
        val olderMargin = if (olderSales > 0) olderProfit / olderSales else 0.0

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
        val topItemShare = salesByItem.values.maxOfOrNull { it.sumOf { t -> t.totalAmount } }?.let {
            it / totalSalesAmount
        } ?: 0.0

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
    // UTILITY
    // ═══════════════════════════════════════════════════════════════

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

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES — CFO Engine outputs
// ═══════════════════════════════════════════════════════════════

/** Daily briefing output */
data class DailyBriefing(
    val message: String,
    val todaySales: Double,
    val todayExpenses: Double,
    val todayProfit: Double,
    val yesterdaySales: Double,
    val yesterdayProfit: Double,
    val salesTrendPercent: Int,
    val topSellingItem: String?,
    val savingsRecommendation: Double
)

/** Cash flow forecast output */
data class CashFlowForecast(
    val message: String,
    val currentCash: Double,
    val dailyBurnRate: Double,
    val daysRemaining: Int,
    val isHealthy: Boolean
)

/** Restock advice output */
data class RestockAdvice(
    val message: String,
    val items: List<RestockItem>
)

/** Single restock item */
data class RestockItem(
    val item: String,
    val currentStock: Double,
    val dailyVelocity: Double,
    val daysOfStockRemaining: Double,
    val suggestedQuantity: Int,
    val estimatedCost: Double,
    val urgency: RestockUrgency
)

enum class RestockUrgency {
    CRITICAL,  // Stock out today/tomorrow
    HIGH,      // Stock out in 2 days
    MEDIUM,    // Stock out in 3 days
    LOW        // More than 3 days
}

/** Savings advice output */
data class SavingsAdvice(
    val message: String,
    val recommendedAmount: Double,
    val totalSaved: Double,
    val targetAmount: Double,
    val progressPercent: Int
)

/** Credit readiness output */
data class CreditReadiness(
    val message: String,
    val score: Int,
    val isReady: Boolean,
    val estimatedLoanAmount: Int,
    val breakdown: Map<String, Int>
)

/** Weekly report output */
data class WeeklyReport(
    val message: String,
    val totalSales: Double,
    val totalExpenses: Double,
    val totalProfit: Double,
    val salesGrowthPercent: Int,
    val topProduct: String?,
    val transactionCount: Int
)

/** Risk alert output */
data class RiskAlert(
    val type: RiskType,
    val severity: RiskSeverity,
    val message: String,
    val recommendation: String
)

enum class RiskType {
    REVENUE_DECLINE,
    MARGIN_COMPRESSION,
    CONCENTRATION_RISK,
    IRREGULAR_ACTIVITY,
    NONE
}

enum class RiskSeverity {
    HIGH,
    MEDIUM,
    LOW,
    NONE
}
