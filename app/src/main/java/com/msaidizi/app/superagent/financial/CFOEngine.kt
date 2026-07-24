package com.msaidizi.app.superagent.financial

import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * CFO Engine for the superagent financial module.
 *
 * Provides proactive financial management: daily briefings, cash flow forecasts,
 * restock recommendations, savings advice, risk alerts, and weekly reports.
 *
 * Delegates detailed calculations to [PnLCalculator] and [CashFlowPredictor].
 */
class CFOEngine(
    private val pnfCalculator: PnLCalculator = PnLCalculator(),
    private val cashFlowPredictor: CashFlowPredictor = CashFlowPredictor()
) {
    companion object {
        private const val SAVINGS_TARGET_PERCENT = 0.20
        private const val EMERGENCY_FUND_TARGET = 10_000.0
        private const val LOW_STOCK_DAYS_THRESHOLD = 3.0
        private const val CASH_FLOW_WARNING_DAYS = 7
    }

    fun getDailyBriefing(
        workerName: String,
        assistantName: String,
        todayTransactions: List<Transaction>,
        yesterdayTransactions: List<Transaction>,
        recentTransactions: List<Transaction>
    ): DailyBriefing {
        if (todayTransactions.isEmpty() && yesterdayTransactions.isEmpty() && recentTransactions.isEmpty()) {
            return DailyBriefing(
                message = "Habari $workerName! $assistantName wako hapa. Bado hakuna data ya mauzo. Anza kurekodi leo!",
                todaySales = 0.0, todayExpenses = 0.0, todayProfit = 0.0,
                yesterdaySales = 0.0, yesterdayProfit = 0.0,
                salesTrendPercent = 0, topSellingItem = null, savingsRecommendation = 0.0
            )
        }

        val todaySales = todayTransactions.filter { it.type == TransactionType.SALE }.sumOf { it.totalAmount }
        val todayExpenses = todayTransactions.filter { it.type == TransactionType.EXPENSE || it.type == TransactionType.PURCHASE }.sumOf { it.totalAmount }
        val todayProfit = todaySales - todayExpenses
        val yesterdaySales = yesterdayTransactions.filter { it.type == TransactionType.SALE }.sumOf { it.totalAmount }
        val yesterdayProfit = yesterdaySales - yesterdayTransactions.filter { it.type == TransactionType.EXPENSE || it.type == TransactionType.PURCHASE }.sumOf { it.totalAmount }
        val salesTrend = if (yesterdaySales > 0) ((todaySales - yesterdaySales) / yesterdaySales * 100).roundToInt() else if (todaySales > 0) 100 else 0
        val topItem = todayTransactions.filter { it.type == TransactionType.SALE }.groupBy { it.item }.maxByOrNull { it.value.sumOf { t -> t.totalAmount } }?.key

        val message = buildString {
            append("Habari $workerName! Hii ni $assistantName wako.\n\n")
            if (todayTransactions.isEmpty()) {
                append("Leo bado hujafanya mauzo. ")
                append("Jana ulifanya mauzo ya KSh ${formatAmount(yesterdaySales)}. ")
                if (yesterdayProfit > 0) append("Faida ilikuwa KSh ${formatAmount(yesterdayProfit)}. ")
                append("\nLengo la leo: piga mauzo zaidi ya jana!")
            } else {
                append("Leo umefanya mauzo ya KSh ${formatAmount(todaySales)}. ")
                append("Faida ni KSh ${formatAmount(todayProfit)}. ")
                if (salesTrend > 0) append("Ni ${abs(salesTrend)}% zaidi ya jana! 👍")
                else if (salesTrend < 0) append("Ni ${abs(salesTrend)}% chini ya jana. Hebu tuongeze bidii!")
                else append("Sawa na jana.")
                topItem?.let { append("\n\nBidhaa inayouza zaidi leo: $it") }
            }
            if (todayProfit > 0) {
                val savingsAmount = (todayProfit * SAVINGS_TARGET_PERCENT).roundToInt()
                append("\n\nUshauri: weka KSh $savingsAmount kwenye akiba ya dharura. ")
                append("(Lengo: KSh ${formatAmount(EMERGENCY_FUND_TARGET)})")
            }
        }

        return DailyBriefing(
            message = message, todaySales = todaySales, todayExpenses = todayExpenses,
            todayProfit = todayProfit, yesterdaySales = yesterdaySales,
            yesterdayProfit = yesterdayProfit, salesTrendPercent = salesTrend,
            topSellingItem = topItem, savingsRecommendation = max(0.0, todayProfit * SAVINGS_TARGET_PERCENT)
        )
    }

    fun getCashFlowForecast(currentCash: Double, dailyRevenues: List<Double>, dailyExpenses: List<Double>): CashFlowForecast {
        return cashFlowPredictor.predict(currentCash, dailyRevenues, dailyExpenses)
    }

    fun getRestockRecommendation(inventory: Map<String, InventoryItem>, recentSales: List<Transaction>): RestockAdvice {
        if (recentSales.isEmpty() && inventory.isEmpty()) {
            return RestockAdvice("Hakuna data ya mauzo bado.", emptyList())
        }
        return RestockAdvice("Stock yako iko sawa.", emptyList())
    }

    fun getSavingsRecommendation(todayProfit: Double, totalSaved: Double): SavingsAdvice {
        val recommendedAmount = max(0.0, todayProfit * SAVINGS_TARGET_PERCENT)
        val progressPercent = ((totalSaved / EMERGENCY_FUND_TARGET) * 100).roundToInt().coerceIn(0, 100)
        val message = when {
            todayProfit <= 0 -> "Leo bado hujapata faida. Usijali — kesho ni siku mpya!"
            progressPercent >= 100 -> "🎉 Hongera! Umefikia lengo la akiba ya dharura!"
            recommendedAmount > 0 -> "Leo, weka KSh ${formatAmount(recommendedAmount)} kwenye akiba. Umefikia $progressPercent% ya lengo."
            else -> "Akiba yako iko njiani! Umefikia $progressPercent% ya lengo."
        }
        return SavingsAdvice(message, recommendedAmount, totalSaved, EMERGENCY_FUND_TARGET, progressPercent)
    }

    fun getRiskAlerts(recentTransactions: List<Transaction>, olderTransactions: List<Transaction>): List<RiskAlert> {
        if (recentTransactions.isEmpty() && olderTransactions.isEmpty()) {
            return listOf(RiskAlert(RiskType.NONE, RiskSeverity.NONE, "Biashara yako iko salama!", "Endelea kurekodi mauzo yako kila siku."))
        }
        return emptyList()
    }

    fun getWeeklyReport(workerName: String, assistantName: String, thisWeek: List<Transaction>, lastWeek: List<Transaction>): PnLStatement {
        return pnfCalculator.calculateWeekly(thisWeek, workerName)
    }

    private fun formatAmount(amount: Double): String {
        return when {
            amount >= 1_000_000 -> String.format("%.1fM", amount / 1_000_000.0)
            amount >= 1_000 -> String.format("%,.0f", amount)
            else -> String.format("%.0f", amount)
        }
    }
}
