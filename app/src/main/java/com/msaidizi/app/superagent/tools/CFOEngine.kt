package com.msaidizi.app.superagent.tools

import com.msaidizi.app.core.database.DailySummaryDao
import com.msaidizi.app.core.database.ExpenseDao
import com.msaidizi.app.core.database.ProductDao
import com.msaidizi.app.core.database.SaleDao
import com.msaidizi.app.core.util.DateTimeUtil
import com.msaidizi.app.model.DailySummaryEntity
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CFOEngine — Daily briefings, cash flow predictions, savings advice.
 *
 * Acts as the user's Chief Financial Officer:
 * - Generates morning/evening briefings
 * - Predicts cash flow based on historical patterns
 * - Provides savings and growth advice
 */
@Singleton
class CFOEngine @Inject constructor(
    private val saleDao: SaleDao,
    private val expenseDao: ExpenseDao,
    private val productDao: ProductDao,
    private val dailySummaryDao: DailySummaryDao,
    private val gson: Gson
) : Tool {

    override val name = "cfo_engine"
    override val description = "Daily briefings, cash flow predictions, and savings advice"

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "briefing"
        return when (action.lowercase()) {
            "briefing" -> generateDailyBriefing()
            "cashflow" -> predictCashFlow()
            "savings" -> getSavingsAdvice()
            "weekly" -> generateWeeklyReport()
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    /**
     * Generate a daily business briefing.
     */
    suspend fun generateDailyBriefing(): ToolResult {
        return try {
            val todayStart = DateTimeUtil.startOfDay()
            val todayEnd = DateTimeUtil.endOfDay()
            val yesterdayStart = todayStart - 24 * 60 * 60 * 1000L
            val yesterdayEnd = todayStart - 1

            // Today's numbers
            val todaySales = saleDao.getTotalSalesBetween(todayStart, todayEnd).first() ?: 0.0
            val todayExpenses = expenseDao.getTotalExpensesBetween(todayStart, todayEnd).first() ?: 0.0
            val todayTxCount = saleDao.getTransactionCountBetween(todayStart, todayEnd).first()
            val todayProfit = todaySales - todayExpenses

            // Yesterday's numbers for comparison
            val yesterdaySales = saleDao.getTotalSalesBetween(yesterdayStart, yesterdayEnd).first() ?: 0.0
            val yesterdayProfit = yesterdaySales - (expenseDao.getTotalExpensesBetween(yesterdayStart, yesterdayEnd).first() ?: 0.0)

            // Sales change percentage
            val salesChange = if (yesterdaySales > 0) {
                ((todaySales - yesterdaySales) / yesterdaySales * 100)
            } else 0.0

            // Top products today
            val topProducts = saleDao.getTopProducts(todayStart, todayEnd, 3).first()

            // Payment method breakdown
            val mpesaSales = saleDao.getMpesaSalesBetween(todayStart, todayEnd).first() ?: 0.0
            val creditSales = saleDao.getCreditSalesBetween(todayStart, todayEnd).first() ?: 0.0
            val cashSales = todaySales - mpesaSales - creditSales

            // Low stock alerts
            val lowStock = productDao.getLowStock().first()

            // Build briefing
            val briefing = buildString {
                appendLine("📊 *Daily Briefing — ${DateTimeUtil.today()}*")
                appendLine()
                appendLine("💰 Sales: Ksh ${"%,.0f".format(todaySales)} ($todayTxCount transactions)")
                if (salesChange != 0.0) {
                    val arrow = if (salesChange > 0) "📈" else "📉"
                    appendLine("   $arrow ${if (salesChange > 0) "+" else ""}${"%.0f".format(salesChange)}% vs yesterday")
                }
                appendLine("💸 Expenses: Ksh ${"%,.0f".format(todayExpenses)}")
                appendLine("✅ Profit: Ksh ${"%,.0f".format(todayProfit)}")
                appendLine()
                appendLine("📱 Payment mix:")
                appendLine("   Cash: Ksh ${"%,.0f".format(cashSales)}")
                appendLine("   M-Pesa: Ksh ${"%,.0f".format(mpesaSales)}")
                if (creditSales > 0) {
                    appendLine("   Credit: Ksh ${"%,.0f".format(creditSales)}")
                }

                if (topProducts.isNotEmpty()) {
                    appendLine()
                    appendLine("🏆 Top products:")
                    topProducts.forEachIndexed { i, p ->
                        appendLine("   ${i + 1}. ${p.productName}: Ksh ${"%,.0f".format(p.totalRevenue)} (${p.totalQty.toInt()} sold)")
                    }
                }

                if (lowStock.isNotEmpty()) {
                    appendLine()
                    appendLine("⚠️ Low stock: ${lowStock.joinToString(", ") { it.name }}")
                }
            }

            // Save daily summary
            dailySummaryDao.insert(
                DailySummaryEntity(
                    date = DateTimeUtil.today(),
                    totalSales = todaySales,
                    totalExpenses = todayExpenses,
                    profit = todayProfit,
                    transactionCount = todayTxCount,
                    topProduct = topProducts.firstOrNull()?.productName,
                    cashSales = cashSales,
                    mpesaSales = mpesaSales,
                    creditSales = creditSales
                )
            )

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "sales" to todaySales,
                    "expenses" to todayExpenses,
                    "profit" to todayProfit,
                    "transactions" to todayTxCount
                ),
                message = briefing
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate briefing")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * Predict cash flow for the next 7 days based on historical patterns.
     */
    suspend fun predictCashFlow(): ToolResult {
        return try {
            val summaries = dailySummaryDao.getRecentSummaries(30).first()

            if (summaries.size < 3) {
                return ToolResult.success(
                    name,
                    message = "Not enough data for predictions yet. I need at least 3 days of data. Keep recording!"
                )
            }

            val avgDailySales = summaries.map { it.totalSales }.average()
            val avgDailyExpenses = summaries.map { it.totalExpenses }.average()
            val avgDailyProfit = avgDailySales - avgDailyExpenses

            // Day-of-week patterns
            val calendar = Calendar.getInstance()
            val dayOfWeekSales = summaries.groupBy {
                calendar.timeInMillis = it.createdAt
                calendar.get(Calendar.DAY_OF_WEEK)
            }.mapValues { (_, days) -> days.map { it.totalSales }.average() }

            val predictions = (1..7).map { dayOffset ->
                calendar.timeInMillis = System.currentTimeMillis()
                calendar.add(Calendar.DAY_OF_MONTH, dayOffset)
                val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
                val predictedSales = dayOfWeekSales[dayOfWeek] ?: avgDailySales
                val predictedExpenses = avgDailyExpenses
                mapOf(
                    "date" to DateTimeUtil.formatDate(calendar.timeInMillis),
                    "predicted_sales" to predictedSales,
                    "predicted_expenses" to predictedExpenses,
                    "predicted_profit" to (predictedSales - predictedExpenses)
                )
            }

            val weeklySales = predictions.sumOf { it["predicted_sales"] as Double }
            val weeklyProfit = predictions.sumOf { it["predicted_profit"] as Double }

            val forecast = buildString {
                appendLine("📈 *7-Day Cash Flow Forecast*")
                appendLine()
                appendLine("Average daily: Ksh ${"%,.0f".format(avgDailySales)} sales, Ksh ${"%,.0f".format(avgDailyProfit)} profit")
                appendLine("Predicted weekly: Ksh ${"%,.0f".format(weeklySales)} sales, Ksh ${"%,.0f".format(weeklyProfit)} profit")
                appendLine()
                predictions.forEach { p ->
                    appendLine("  ${p["date"]}: Ksh ${"%,.0f".format(p["predicted_sales"])} sales")
                }
            }

            ToolResult.success(
                toolName = name,
                data = predictions,
                message = forecast
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to predict cash flow")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * Provide savings and growth advice based on business data.
     */
    suspend fun getSavingsAdvice(): ToolResult {
        return try {
            val summaries = dailySummaryDao.getRecentSummaries(14).first()

            if (summaries.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "Start recording your daily sales and I'll give you personalized savings advice! 💡"
                )
            }

            val avgDailyProfit = summaries.map { it.profit }.average()
            val avgDailySales = summaries.map { it.totalSales }.average()
            val profitMargin = if (avgDailySales > 0) (avgDailyProfit / avgDailySales * 100) else 0.0

            val advice = mutableListOf<String>()

            // Savings recommendation
            val suggestedSavings = avgDailyProfit * 0.2 // 20% of profit
            advice.add("💰 Try saving Ksh ${"%,.0f".format(suggestedSavings)} daily (20% of your average profit)")

            // Profit margin advice
            when {
                profitMargin < 10 -> {
                    advice.add("📉 Your profit margin is low (${ "%.0f".format(profitMargin)}%). Consider:")
                    advice.add("   • Raising prices slightly")
                    advice.add("   • Finding cheaper suppliers")
                    advice.add("   • Reducing daily expenses")
                }
                profitMargin < 25 -> {
                    advice.add("📊 Profit margin: ${"%.0f".format(profitMargin)}%. Room to improve:")
                    advice.add("   • Focus on high-margin products")
                    advice.add("   • Negotiate bulk discounts from suppliers")
                }
                else -> {
                    advice.add("✅ Great profit margin (${ "%.0f".format(profitMargin)}%)! Keep it up!")
                }
            }

            // Weekly savings target
            val weeklySavings = suggestedSavings * 6 // 6 working days
            advice.add("🎯 Weekly savings target: Ksh ${"%,.0f".format(weeklySavings)}")
            advice.add("📅 Monthly savings potential: Ksh ${"%,.0f".format(weeklySavings * 4)}")

            // M-Pesa tip
            val mpesaRatio = summaries.map { it.mpesaSales }.average() / (avgDailySales.coerceAtLeast(1.0))
            if (mpesaRatio > 0.5) {
                advice.add("📱 Most sales via M-Pesa — good! Track your float carefully.")
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "avg_daily_profit" to avgDailyProfit,
                    "profit_margin" to profitMargin,
                    "suggested_daily_savings" to suggestedSavings
                ),
                message = advice.joinToString("\n")
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate savings advice")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * Generate a weekly report comparing this week to last week.
     */
    suspend fun generateWeeklyReport(): ToolResult {
        return try {
            val thisWeekStart = DateTimeUtil.startOfWeek()
            val lastWeekStart = thisWeekStart - 7 * 24 * 60 * 60 * 1000L
            val now = System.currentTimeMillis()

            val thisWeekSales = saleDao.getTotalSalesBetween(thisWeekStart, now).first() ?: 0.0
            val thisWeekExpenses = expenseDao.getTotalExpensesBetween(thisWeekStart, now).first() ?: 0.0
            val lastWeekSales = saleDao.getTotalSalesBetween(lastWeekStart, thisWeekStart - 1).first() ?: 0.0
            val lastWeekExpenses = expenseDao.getTotalExpensesBetween(lastWeekStart, thisWeekStart - 1).first() ?: 0.0

            val salesChange = if (lastWeekSales > 0) ((thisWeekSales - lastWeekSales) / lastWeekSales * 100) else 0.0

            val report = buildString {
                appendLine("📊 *Weekly Report*")
                appendLine()
                appendLine("This week:")
                appendLine("  Sales: Ksh ${"%,.0f".format(thisWeekSales)}")
                appendLine("  Expenses: Ksh ${"%,.0f".format(thisWeekExpenses)}")
                appendLine("  Profit: Ksh ${"%,.0f".format(thisWeekSales - thisWeekExpenses)}")
                appendLine()
                appendLine("Last week:")
                appendLine("  Sales: Ksh ${"%,.0f".format(lastWeekSales)}")
                appendLine("  Profit: Ksh ${"%,.0f".format(lastWeekSales - lastWeekExpenses)}")
                appendLine()
                val arrow = if (salesChange > 0) "📈" else "📉"
                appendLine("$arrow Sales: ${if (salesChange > 0) "+" else ""}${"%.0f".format(salesChange)}% vs last week")
            }

            ToolResult.success(name, message = report)
        } catch (e: Exception) {
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }
}
