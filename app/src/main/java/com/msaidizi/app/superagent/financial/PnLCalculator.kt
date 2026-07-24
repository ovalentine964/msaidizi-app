package com.msaidizi.app.superagent.financial

import timber.log.Timber
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Profit & Loss Calculator — tracks business profitability.
 *
 * Computes P&L statements across daily, weekly, and monthly periods.
 * All calculations are pure Kotlin with no LLM dependency.
 *
 * ## Academic Foundations
 * - **FIN 201 (Corporate Finance):** Revenue recognition, cost of goods sold,
 *   gross vs net profit, operating margin analysis
 * - **ECO 201 (Microeconomics):** Profit maximization, marginal analysis
 * - **STA 201 (Descriptive Statistics):** Mean, variance, trend analysis
 *
 * ## Margin Analysis
 * - **Gross Margin** = (Revenue - COGS) / Revenue → measures pricing power
 * - **Net Margin** = (Revenue - COGS - OpEx) / Revenue → measures overall health
 * - Target for informal economy: 20%+ gross margin
 *
 * @author Msaidizi Financial Team
 */
class PnLCalculator {

    companion object {
        private const val TAG = "PnLCalculator"

        /** Healthy gross margin threshold */
        private const val HEALTHY_GROSS_MARGIN = 0.20

        /** Healthy net margin threshold */
        private const val HEALTHY_NET_MARGIN = 0.10

        /** Seconds in a day */
        private const val SECONDS_PER_DAY = 86_400L
    }

    // ═══════════════════════════════════════════════════════════════
    // DAILY P&L
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calculate daily P&L statement.
     *
     * @param transactions All transactions for the day
     * @param workerName Worker's name (for personalization)
     * @return [PnLStatement] with daily breakdown
     */
    fun calculateDaily(
        transactions: List<Transaction>,
        workerName: String = ""
    ): PnLStatement {
        return calculateForPeriod(transactions, "Leo", workerName)
    }

    // ═══════════════════════════════════════════════════════════════
    // WEEKLY P&L
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calculate weekly P&L statement.
     *
     * @param transactions All transactions for the week
     * @param workerName Worker's name
     * @return [PnLStatement] with weekly breakdown
     */
    fun calculateWeekly(
        transactions: List<Transaction>,
        workerName: String = ""
    ): PnLStatement {
        return calculateForPeriod(transactions, "Wiki hii", workerName)
    }

    // ═══════════════════════════════════════════════════════════════
    // MONTHLY P&L
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calculate monthly P&L statement.
     *
     * @param transactions All transactions for the month
     * @param workerName Worker's name
     * @return [PnLStatement] with monthly breakdown
     */
    fun calculateMonthly(
        transactions: List<Transaction>,
        workerName: String = ""
    ): PnLStatement {
        return calculateForPeriod(transactions, "Mwezi huu", workerName)
    }

    // ═══════════════════════════════════════════════════════════════
    // COMPARATIVE ANALYSIS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Compare two P&L periods (e.g., this week vs last week).
     *
     * @param current Current period P&L
     * @param previous Previous period P&L
     * @return Human-readable comparison message in Swahili
     */
    fun compare(current: PnLStatement, previous: PnLStatement): String {
        if (previous.totalRevenue == 0.0 && previous.netProfit == 0.0) {
            return "Hakuna data ya kulinganisha nayo."
        }

        val revenueChange = if (previous.totalRevenue > 0) {
            ((current.totalRevenue - previous.totalRevenue) / previous.totalRevenue * 100).roundToInt()
        } else if (current.totalRevenue > 0) 100 else 0

        val profitChange = if (previous.netProfit > 0) {
            ((current.netProfit - previous.netProfit) / previous.netProfit * 100).roundToInt()
        } else if (current.netProfit > 0) 100 else 0

        return buildString {
            when {
                revenueChange > 0 -> append("📈 Mauzo yaliongezeka $revenueChange%. ")
                revenueChange < 0 -> append("📉 Mauzo yalipungua ${abs(revenueChange)}%. ")
                else -> append("➡️ Mauzo yalisawazika. ")
            }

            when {
                profitChange > 0 -> append("Faida iliongezeka $profitChange%!")
                profitChange < 0 -> append("Faida ilipungua ${abs(profitChange)}%.")
                else -> append("Faida ilisawazika.")
            }
        }
    }

    /**
     * Calculate profit trend over multiple days.
     *
     * @param dailyTransactions Map of day label to transactions
     * @return List of daily P&L statements in chronological order
     */
    fun calculateTrend(dailyTransactions: Map<String, List<Transaction>>): List<PnLStatement> {
        return dailyTransactions.map { (day, txns) ->
            calculateForPeriod(txns, day)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CORE CALCULATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Core P&L calculation for any period.
     */
    private fun calculateForPeriod(
        transactions: List<Transaction>,
        period: String,
        workerName: String = ""
    ): PnLStatement {
        if (transactions.isEmpty()) {
            return PnLStatement(
                period = period,
                totalRevenue = 0.0,
                totalCostOfGoods = 0.0,
                grossProfit = 0.0,
                totalExpenses = 0.0,
                netProfit = 0.0,
                grossMarginPercent = 0.0,
                netMarginPercent = 0.0,
                transactionCount = 0,
                message = "Hakuna data ya $period."
            )
        }

        // Revenue from sales
        val totalRevenue = transactions
            .filter { it.type == TransactionType.SALE }
            .sumOf { it.totalAmount }

        // Cost of goods sold (purchases used for resale)
        val totalCostOfGoods = transactions
            .filter { it.type == TransactionType.PURCHASE }
            .sumOf { it.totalAmount }

        // Operating expenses
        val totalExpenses = transactions
            .filter { it.type == TransactionType.EXPENSE || it.type == TransactionType.FEE }
            .sumOf { it.totalAmount }

        // Deposits and withdrawals
        val deposits = transactions
            .filter { it.type == TransactionType.DEPOSIT }
            .sumOf { it.totalAmount }
        val withdrawals = transactions
            .filter { it.type == TransactionType.WITHDRAWAL }
            .sumOf { it.totalAmount }

        val grossProfit = totalRevenue - totalCostOfGoods
        val netProfit = grossProfit - totalExpenses + deposits - withdrawals

        // Safe margin calculations (avoid division by zero)
        val grossMargin = if (totalRevenue > 0) {
            (grossProfit / totalRevenue).coerceIn(-1.0, 1.0)
        } else 0.0

        val netMargin = if (totalRevenue > 0) {
            (netProfit / totalRevenue).coerceIn(-1.0, 1.0)
        } else 0.0

        // Build message
        val message = buildPnLMessage(
            period, workerName, totalRevenue, totalCostOfGoods,
            grossProfit, totalExpenses, netProfit, grossMargin, netMargin,
            transactions.size
        )

        return PnLStatement(
            period = period,
            totalRevenue = totalRevenue,
            totalCostOfGoods = totalCostOfGoods,
            grossProfit = grossProfit,
            totalExpenses = totalExpenses,
            netProfit = netProfit,
            grossMarginPercent = (grossMargin * 100).roundToInt() / 100.0,
            netMarginPercent = (netMargin * 100).roundToInt() / 100.0,
            transactionCount = transactions.size,
            message = message
        )
    }

    /**
     * Build a Swahili P&L summary message.
     */
    private fun buildPnLMessage(
        period: String,
        workerName: String,
        revenue: Double,
        cogs: Double,
        grossProfit: Double,
        expenses: Double,
        netProfit: Double,
        grossMargin: Double,
        netMargin: Double,
        txnCount: Int
    ): String {
        return buildString {
            append("📊 Ripoti ya $period")
            if (workerName.isNotBlank()) append(" — $workerName")
            append("\n\n")

            append("Mauzo: KSh ${formatAmount(revenue)}\n")
            append("Gharama ya bidhaa: KSh ${formatAmount(cogs)}\n")
            append("Faida ya jumla: KSh ${formatAmount(grossProfit)} ")
            append("(${(grossMargin * 100).roundToInt()}%)\n")
            append("Matumizi: KSh ${formatAmount(expenses)}\n")
            append("Faida halisi: KSh ${formatAmount(netProfit)} ")
            append("(${(netMargin * 100).roundToInt()}%)\n\n")

            // Health assessment
            when {
                netMargin >= HEALTHY_NET_MARGIN -> {
                    append("✅ Biashara yako iko na faida nzuri!")
                }
                netMargin > 0 -> {
                    append("⚠️ Faida ni ndogo. Fikiria kupunguza gharama au kuongeza bei.")
                }
                netMargin == 0.0 && revenue > 0 -> {
                    append("➡️ Huna faida wala hasara. Angalia bei na gharama zako.")
                }
                else -> {
                    append("🚨 Una hasara! Gharama zako ni zaidi ya mauzo. Hatua za haraka zinahitajika.")
                }
            }

            append("\n\nJumla ya miamala: $txnCount")
        }
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
