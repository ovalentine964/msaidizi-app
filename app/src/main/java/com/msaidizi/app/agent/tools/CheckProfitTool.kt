package com.msaidizi.app.agent.tools

import com.msaidizi.app.data.dao.TransactionDao
import java.util.Calendar

/**
 * CheckProfitTool — Query profit/loss for a period.
 */
class CheckProfitTool(
    private val transactionDao: TransactionDao
) : Tool {
    override val name = "check_profit"
    override val description = "Angalia faida — Check profit/loss"
    override val supportedIntents = listOf("profit_query", "check_profit")
    override val memoryRequiredMB = 5

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val workerId = args["workerId"]?.toString()
            ?: throw IllegalStateException("workerId must be provided by SuperAgent")

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis
        val now = System.currentTimeMillis()

        // This week
        cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        val weekStart = cal.timeInMillis

        // This month
        cal.timeInMillis = now
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val monthStart = cal.timeInMillis

        val todayProfit = calculateProfit(workerId, todayStart, now)
        val weekProfit = calculateProfit(workerId, weekStart, now)
        val monthProfit = calculateProfit(workerId, monthStart, now)
        val allTimeProfit = (transactionDao.getAllTimeSales(workerId) ?: 0.0) -
                           (transactionDao.getAllTimeExpenses(workerId) ?: 0.0)

        val profitEmoji = { p: Double -> if (p >= 0) "📈" else "📉" }

        return ToolResult(
            text = if (language == "sw") {
                buildString {
                    append("💰 Faida yako:\n")
                    append("• Leo: ${profitEmoji(todayProfit)} KSh ${"%,.0f".format(todayProfit)}\n")
                    append("• Wiki hii: ${profitEmoji(weekProfit)} KSh ${"%,.0f".format(weekProfit)}\n")
                    append("• Mwezi huu: ${profitEmoji(monthProfit)} KSh ${"%,.0f".format(monthProfit)}\n")
                    append("• Jumla: ${profitEmoji(allTimeProfit)} KSh ${"%,.0f".format(allTimeProfit)}")
                }
            } else {
                buildString {
                    append("💰 Your profit:\n")
                    append("• Today: ${profitEmoji(todayProfit)} KSh ${"%,.0f".format(todayProfit)}\n")
                    append("• This week: ${profitEmoji(weekProfit)} KSh ${"%,.0f".format(weekProfit)}\n")
                    append("• This month: ${profitEmoji(monthProfit)} KSh ${"%,.0f".format(monthProfit)}\n")
                    append("• All time: ${profitEmoji(allTimeProfit)} KSh ${"%,.0f".format(allTimeProfit)}")
                }
            },
            data = mapOf(
                "todayProfit" to todayProfit.toString(),
                "weekProfit" to weekProfit.toString(),
                "monthProfit" to monthProfit.toString(),
                "allTimeProfit" to allTimeProfit.toString()
            ),
            success = true
        )
    }

    private suspend fun calculateProfit(workerId: String, start: Long, end: Long): Double {
        val sales = transactionDao.getTotalSales(workerId, start, end) ?: 0.0
        val purchases = transactionDao.getTotalPurchases(workerId, start, end) ?: 0.0
        val expenses = transactionDao.getTotalExpenses(workerId, start, end) ?: 0.0
        return sales - purchases - expenses
    }

    override fun onLowMemory() {}
}
