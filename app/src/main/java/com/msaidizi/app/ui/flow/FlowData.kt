package com.msaidizi.app.ui.flow

import com.msaidizi.app.core.model.ItemRanking
import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.Trend

/**
 * Data model for Business Flow visualization.
 *
 * Like M-Pesa shows cash flow (money in, money out),
 * Msaidizi shows business flow — how money moves through the business:
 * Revenue → Expenses → Profit → Savings → Growth
 *
 * Academic foundations:
 * - ECO 201 (Micro): Revenue, cost, profit concepts
 * - STA 244 (Time Series): Trend visualization
 * - STA 142 (Descriptive Stats): Summary statistics
 * - ECO 206 (Microfinance): Savings and credit tracking
 */
data class FlowData(
    /** Time period for this data */
    val period: FlowPeriod,

    /** Total revenue (money in) in KSh */
    val revenue: Double,

    /** Total expenses + purchases (money out) in KSh */
    val expenses: Double,

    /** Net profit (revenue - expenses) in KSh */
    val profit: Double,

    /** Total savings in KSh */
    val savings: Double,

    /** Savings target in KSh */
    val savingsTarget: Double,

    /** Transaction count for the period */
    val transactionCount: Int,

    /** Top selling items by revenue */
    val topItems: List<ItemRanking>,

    /** Daily breakdown: day label → (revenue, expenses, profit) */
    val dailyBreakdown: List<DayFlow>,

    /** Revenue by category */
    val revenueByCategory: Map<String, Double>,

    /** Expenses by category */
    val expensesByCategory: Map<String, Double>,

    /** Sales trend direction */
    val trend: Trend,

    /** Business health score (0-100) */
    val healthScore: Int,

    /** Credit readiness score (0-100) */
    val creditReadiness: Int,

    /** Profit margin as percentage */
    val profitMargin: Double,

    /** Sales velocity (KSh per day) */
    val salesVelocity: Double,

    /** Comparison with previous period */
    val previousPeriod: PeriodComparison?,

    /** Cash on hand estimate */
    val cashPosition: Double,

    /** Best performing day in period */
    val bestDay: DayFlow?,

    /** Worst performing day in period */
    val worstDay: DayFlow?
)

/**
 * Single day's flow data.
 */
data class DayFlow(
    /** Day label (e.g., "Mon", "Jul 1") */
    val label: String,

    /** Unix timestamp for the day start */
    val timestamp: Long,

    /** Revenue for the day */
    val revenue: Double,

    /** Expenses for the day */
    val expenses: Double,

    /** Profit for the day */
    val profit: Double,

    /** Number of transactions */
    val transactionCount: Int
)

/**
 * Comparison with previous period.
 */
data class PeriodComparison(
    /** Revenue change percentage */
    val revenueChange: Double,

    /** Expense change percentage */
    val expenseChange: Double,

    /** Profit change percentage */
    val profitChange: Double,

    /** Transaction count change */
    val transactionCountChange: Int
)

/**
 * Time period for flow visualization.
 */
enum class FlowPeriod(val displayName: String, val displayNameSw: String) {
    TODAY("Today", "Leo"),
    WEEK("This Week", "Wiki Hii"),
    MONTH("This Month", "Mwezi Huu"),
    YEAR("This Year", "Mwaka Huu");

    companion object {
        fun fromOrdinal(ordinal: Int): FlowPeriod {
            return entries.getOrElse(ordinal) { TODAY }
        }
    }
}

/**
 * Health status derived from health score.
 */
enum class HealthStatus(val label: String, val labelSw: String, val emoji: String) {
    EXCELLENT("Excellent", "Nzuri Sana", "🟢"),
    GOOD("Good", "Nzuri", "🟡"),
    FAIR("Fair", "Wastani", "🟠"),
    POOR("Poor", "Dhaifu", "🔴");

    companion object {
        fun fromScore(score: Int): HealthStatus = when {
            score >= 80 -> EXCELLENT
            score >= 60 -> GOOD
            score >= 40 -> FAIR
            else -> POOR
        }
    }
}

/**
 * Empty/default flow data for loading states.
 */
fun emptyFlowData(period: FlowPeriod = FlowPeriod.TODAY) = FlowData(
    period = period,
    revenue = 0.0,
    expenses = 0.0,
    profit = 0.0,
    savings = 0.0,
    savingsTarget = 10_000.0,
    transactionCount = 0,
    topItems = emptyList(),
    dailyBreakdown = emptyList(),
    revenueByCategory = emptyMap(),
    expensesByCategory = emptyMap(),
    trend = Trend.INSUFFICIENT_DATA,
    healthScore = 0,
    creditReadiness = 0,
    profitMargin = 0.0,
    salesVelocity = 0.0,
    previousPeriod = null,
    cashPosition = 0.0,
    bestDay = null,
    worstDay = null
)
