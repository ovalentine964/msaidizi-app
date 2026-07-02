package com.msaidizi.app.ui.flow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.agent.AnalysisAgent
import com.msaidizi.app.agent.BusinessAgent
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.ItemRanking
import com.msaidizi.app.core.model.TransactionType
import com.msaidizi.app.core.model.Trend
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

/**
 * ViewModel for Business Flow visualization.
 *
 * Loads and processes business data for the flow view.
 * Supports Today, Week, Month, and Year views.
 *
 * Like M-Pesa's transaction history, but for business understanding.
 */
@HiltViewModel
class BusinessFlowViewModel @Inject constructor(
    private val businessAgent: BusinessAgent,
    private val analysisAgent: AnalysisAgent,
    private val transactionDao: TransactionDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(BusinessFlowUiState())
    val uiState: StateFlow<BusinessFlowUiState> = _uiState.asStateFlow()

    init {
        loadFlowData(FlowPeriod.TODAY)
    }

    /**
     * Switch to a different time period.
     */
    fun switchPeriod(period: FlowPeriod) {
        loadFlowData(period)
    }

    /**
     * Refresh current period data.
     */
    fun refresh() {
        loadFlowData(_uiState.value.currentPeriod)
    }

    private fun loadFlowData(period: FlowPeriod) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    currentPeriod = period
                )

                val data = when (period) {
                    FlowPeriod.TODAY -> loadTodayFlow()
                    FlowPeriod.WEEK -> loadWeeklyFlow()
                    FlowPeriod.MONTH -> loadMonthlyFlow()
                    FlowPeriod.YEAR -> loadYearlyFlow()
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    flowData = data,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load flow data: ${e.message}"
                )
            }
        }
    }

    private suspend fun loadTodayFlow(): FlowData {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)

        val todaySales = businessAgent.getDailySales(today)
        val todayPurchases = businessAgent.getDailyPurchases(today)
        val todayProfit = businessAgent.getDailyProfit(today)
        val todayTxCount = businessAgent.getDailyTransactionCount(today)

        val yesterdaySales = businessAgent.getDailySales(yesterday)
        val yesterdayProfit = businessAgent.getDailyProfit(yesterday)

        val topItems = analysisAgent.topItems(1, 5)
        val balance = businessAgent.getBalance()
        val savingsTarget = 10_000.0
        val savings = (balance * 0.2).coerceAtLeast(0.0) // Estimate: 20% of balance as savings

        val revenueChange = if (yesterdaySales > 0) ((todaySales - yesterdaySales) / yesterdaySales * 100) else 0.0
        val profitChange = if (yesterdayProfit != 0.0) ((todayProfit - yesterdayProfit) / kotlin.math.abs(yesterdayProfit) * 100) else 0.0

        val dayFlow = DayFlow(
            label = "Leo",
            timestamp = today.atStartOfDay().toEpochSecond(ZoneOffset.UTC),
            revenue = todaySales,
            expenses = todayPurchases,
            profit = todayProfit,
            transactionCount = todayTxCount
        )

        return FlowData(
            period = FlowPeriod.TODAY,
            revenue = todaySales,
            expenses = todayPurchases,
            profit = todayProfit,
            savings = savings,
            savingsTarget = savingsTarget,
            transactionCount = todayTxCount,
            topItems = topItems,
            dailyBreakdown = listOf(dayFlow),
            revenueByCategory = emptyMap(), // Will be populated from transactions
            expensesByCategory = emptyMap(),
            trend = if (todayProfit > yesterdayProfit) Trend.RISING else if (todayProfit < yesterdayProfit) Trend.FALLING else Trend.STABLE,
            healthScore = calculateHealthScore(todayProfit, todaySales, todayTxCount),
            creditReadiness = calculateCreditReadiness(todayTxCount, todayProfit, todaySales),
            profitMargin = if (todaySales > 0) todayProfit / todaySales * 100 else 0.0,
            salesVelocity = todaySales,
            previousPeriod = PeriodComparison(
                revenueChange = revenueChange,
                expenseChange = 0.0, // Would need yesterday's purchases
                profitChange = profitChange,
                transactionCountChange = 0
            ),
            cashPosition = balance,
            bestDay = dayFlow,
            worstDay = null
        )
    }

    private suspend fun loadWeeklyFlow(): FlowData {
        val today = LocalDate.now()
        val weekStart = today.minusDays(6)

        // Get daily data for the week
        val dailyBreakdown = mutableListOf<DayFlow>()
        for (i in 0..6) {
            val date = weekStart.plusDays(i.toLong())
            val sales = businessAgent.getDailySales(date)
            val purchases = businessAgent.getDailyPurchases(date)
            val profit = businessAgent.getDailyProfit(date)
            val txCount = businessAgent.getDailyTransactionCount(date)

            dailyBreakdown.add(
                DayFlow(
                    label = formatDayLabel(date),
                    timestamp = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC),
                    revenue = sales,
                    expenses = purchases,
                    profit = profit,
                    transactionCount = txCount
                )
            )
        }

        val totalRevenue = dailyBreakdown.sumOf { it.revenue }
        val totalExpenses = dailyBreakdown.sumOf { it.expenses }
        val totalProfit = dailyBreakdown.sumOf { it.profit }
        val totalTxCount = dailyBreakdown.sumOf { it.transactionCount }

        // Last week comparison
        val lastWeekStart = weekStart.minusDays(7)
        val lastWeekEnd = weekStart.minusDays(1)
        val lastWeekCashFlow = businessAgent.getCashFlow(7)

        val topItems = analysisAgent.topItems(7, 5)
        val balance = businessAgent.getBalance()
        val savingsTarget = 10_000.0
        val savings = (balance * 0.2).coerceAtLeast(0.0)

        val bestDay = dailyBreakdown.maxByOrNull { it.profit }
        val worstDay = dailyBreakdown.minByOrNull { it.profit }

        val previousPeriod = if (lastWeekCashFlow.inflow > 0) {
            PeriodComparison(
                revenueChange = ((totalRevenue - lastWeekCashFlow.inflow) / lastWeekCashFlow.inflow * 100),
                expenseChange = if (lastWeekCashFlow.outflow > 0) ((totalExpenses - lastWeekCashFlow.outflow) / lastWeekCashFlow.outflow * 100) else 0.0,
                profitChange = if (lastWeekCashFlow.net != 0.0) ((totalProfit - lastWeekCashFlow.net) / kotlin.math.abs(lastWeekCashFlow.net) * 100) else 0.0,
                transactionCountChange = 0
            )
        } else null

        return FlowData(
            period = FlowPeriod.WEEK,
            revenue = totalRevenue,
            expenses = totalExpenses,
            profit = totalProfit,
            savings = savings,
            savingsTarget = savingsTarget,
            transactionCount = totalTxCount,
            topItems = topItems,
            dailyBreakdown = dailyBreakdown,
            revenueByCategory = emptyMap(),
            expensesByCategory = emptyMap(),
            trend = analysisAgent.salesTrend(7),
            healthScore = calculateHealthScore(totalProfit, totalRevenue, totalTxCount),
            creditReadiness = calculateCreditReadiness(totalTxCount, totalProfit, totalRevenue),
            profitMargin = if (totalRevenue > 0) totalProfit / totalRevenue * 100 else 0.0,
            salesVelocity = analysisAgent.getSalesVelocity(7),
            previousPeriod = previousPeriod,
            cashPosition = balance,
            bestDay = bestDay,
            worstDay = worstDay
        )
    }

    private suspend fun loadMonthlyFlow(): FlowData {
        val today = LocalDate.now()
        val monthStart = today.minusDays(29)

        // Get daily data for the month (aggregate by week for display)
        val dailyBreakdown = mutableListOf<DayFlow>()
        for (i in 0..29) {
            val date = monthStart.plusDays(i.toLong())
            val sales = businessAgent.getDailySales(date)
            val purchases = businessAgent.getDailyPurchases(date)
            val profit = businessAgent.getDailyProfit(date)
            val txCount = businessAgent.getDailyTransactionCount(date)

            dailyBreakdown.add(
                DayFlow(
                    label = date.dayOfMonth.toString(),
                    timestamp = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC),
                    revenue = sales,
                    expenses = purchases,
                    profit = profit,
                    transactionCount = txCount
                )
            )
        }

        val totalRevenue = dailyBreakdown.sumOf { it.revenue }
        val totalExpenses = dailyBreakdown.sumOf { it.expenses }
        val totalProfit = dailyBreakdown.sumOf { it.profit }
        val totalTxCount = dailyBreakdown.sumOf { it.transactionCount }

        val topItems = analysisAgent.topItems(30, 5)
        val balance = businessAgent.getBalance()
        val savingsTarget = 10_000.0
        val savings = (balance * 0.2).coerceAtLeast(0.0)

        val bestDay = dailyBreakdown.maxByOrNull { it.profit }
        val worstDay = dailyBreakdown.minByOrNull { it.profit }

        // Last month comparison
        val lastMonthCashFlow = businessAgent.getCashFlow(30)
        val previousPeriod = if (lastMonthCashFlow.inflow > 0) {
            PeriodComparison(
                revenueChange = ((totalRevenue - lastMonthCashFlow.inflow) / lastMonthCashFlow.inflow * 100),
                expenseChange = if (lastMonthCashFlow.outflow > 0) ((totalExpenses - lastMonthCashFlow.outflow) / lastMonthCashFlow.outflow * 100) else 0.0,
                profitChange = if (lastMonthCashFlow.net != 0.0) ((totalProfit - lastMonthCashFlow.net) / kotlin.math.abs(lastMonthCashFlow.net) * 100) else 0.0,
                transactionCountChange = 0
            )
        } else null

        return FlowData(
            period = FlowPeriod.MONTH,
            revenue = totalRevenue,
            expenses = totalExpenses,
            profit = totalProfit,
            savings = savings,
            savingsTarget = savingsTarget,
            transactionCount = totalTxCount,
            topItems = topItems,
            dailyBreakdown = dailyBreakdown,
            revenueByCategory = emptyMap(),
            expensesByCategory = emptyMap(),
            trend = analysisAgent.salesTrend(30),
            healthScore = calculateHealthScore(totalProfit, totalRevenue, totalTxCount),
            creditReadiness = calculateCreditReadiness(totalTxCount, totalProfit, totalRevenue),
            profitMargin = if (totalRevenue > 0) totalProfit / totalRevenue * 100 else 0.0,
            salesVelocity = analysisAgent.getSalesVelocity(30),
            previousPeriod = previousPeriod,
            cashPosition = balance,
            bestDay = bestDay,
            worstDay = worstDay
        )
    }

    private suspend fun loadYearlyFlow(): FlowData {
        val today = LocalDate.now()

        // Get monthly data for the year
        val dailyBreakdown = mutableListOf<DayFlow>()
        for (month in 1..12) {
            val monthDate = today.withMonth(month).withDayOfMonth(1)
            if (monthDate.isAfter(today)) break

            val daysInMonth = if (month == today.monthValue) {
                today.dayOfMonth.toLong()
            } else {
                monthDate.lengthOfMonth().toLong()
            }

            // Get cash flow for this month
            val monthCashFlow = businessAgent.getCashFlow(daysInMonth.toInt())

            dailyBreakdown.add(
                DayFlow(
                    label = getMonthName(month),
                    timestamp = monthDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC),
                    revenue = monthCashFlow.inflow,
                    expenses = monthCashFlow.outflow,
                    profit = monthCashFlow.net,
                    transactionCount = 0 // Would need separate query
                )
            )
        }

        val totalRevenue = dailyBreakdown.sumOf { it.revenue }
        val totalExpenses = dailyBreakdown.sumOf { it.expenses }
        val totalProfit = dailyBreakdown.sumOf { it.profit }

        val topItems = analysisAgent.topItems(365, 5)
        val balance = businessAgent.getBalance()
        val savingsTarget = 10_000.0
        val savings = (balance * 0.2).coerceAtLeast(0.0)

        val bestDay = dailyBreakdown.maxByOrNull { it.profit }
        val worstDay = dailyBreakdown.minByOrNull { it.profit }

        return FlowData(
            period = FlowPeriod.YEAR,
            revenue = totalRevenue,
            expenses = totalExpenses,
            profit = totalProfit,
            savings = savings,
            savingsTarget = savingsTarget,
            transactionCount = 0,
            topItems = topItems,
            dailyBreakdown = dailyBreakdown,
            revenueByCategory = emptyMap(),
            expensesByCategory = emptyMap(),
            trend = analysisAgent.salesTrend(30),
            healthScore = calculateHealthScore(totalProfit, totalRevenue, 0),
            creditReadiness = calculateCreditReadiness(0, totalProfit, totalRevenue),
            profitMargin = if (totalRevenue > 0) totalProfit / totalRevenue * 100 else 0.0,
            salesVelocity = analysisAgent.getSalesVelocity(30),
            previousPeriod = null,
            cashPosition = balance,
            bestDay = bestDay,
            worstDay = worstDay
        )
    }

    /**
     * Calculate business health score (0-100).
     *
     * Factors:
     * - Profitability (is the business making money?)
     * - Consistency (regular transactions)
     * - Growth (improving over time)
     */
    private fun calculateHealthScore(profit: Double, revenue: Double, txCount: Int): Int {
        var score = 0

        // Profitability (0-40 points)
        if (profit > 0) score += 20
        if (revenue > 0 && profit / revenue > 0.1) score += 10
        if (revenue > 0 && profit / revenue > 0.2) score += 10

        // Activity (0-30 points)
        if (txCount > 0) score += 10
        if (txCount >= 5) score += 10
        if (txCount >= 10) score += 10

        // Revenue level (0-30 points)
        if (revenue > 1000) score += 10
        if (revenue > 5000) score += 10
        if (revenue > 10000) score += 10

        return score.coerceIn(0, 100)
    }

    /**
     * Calculate credit readiness score (0-100).
     *
     * Based on CFOEngine's credit readiness assessment.
     */
    private fun calculateCreditReadiness(txCount: Int, profit: Double, revenue: Double): Int {
        var score = 0

        // Record keeping (0-25 points)
        if (txCount >= 10) score += 15
        if (txCount >= 20) score += 10

        // Profitability (0-25 points)
        if (profit > 0) score += 15
        if (revenue > 0 && profit / revenue > 0.1) score += 10

        // Activity level (0-25 points)
        if (revenue > 5000) score += 15
        if (revenue > 10000) score += 10

        // Consistency (0-25 points)
        if (txCount >= 5) score += 15
        if (txCount >= 10) score += 10

        return score.coerceIn(0, 100)
    }

    private fun formatDayLabel(date: LocalDate): String {
        val days = listOf("Jum", "Jtn", "Jmn", "Alh", "Ijum", "Jmos", "Jpl")
        return days[date.dayOfWeek.value - 1]
    }

    private fun getMonthName(month: Int): String {
        val months = listOf("Jan", "Feb", "Mac", "Apr", "Mei", "Jun", "Jul", "Ago", "Sep", "Okt", "Nov", "Des")
        return months[month - 1]
    }
}

/**
 * UI State for Business Flow screen.
 */
data class BusinessFlowUiState(
    val isLoading: Boolean = false,
    val currentPeriod: FlowPeriod = FlowPeriod.TODAY,
    val flowData: FlowData? = null,
    val error: String? = null
)
