package com.msaidizi.app.agent.tools

import com.msaidizi.app.data.dao.TransactionDao
import java.util.Calendar

/**
 * WeeklySummaryTool — Generate a weekly business summary.
 */
class WeeklySummaryTool(
    private val transactionDao: TransactionDao
) : Tool {
    override val name = "weekly_summary"
    override val description = "Muhtasari wa wiki — Weekly business summary"
    override val supportedIntents = listOf("weekly_summary")
    override val memoryRequiredMB = 10

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val workerId = args["workerId"]?.toString() ?: throw IllegalStateException("workerId must be provided by SuperAgent")

        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val weekStart = cal.timeInMillis
        val now = System.currentTimeMillis()

        val totalSales = transactionDao.getTotalSales(workerId, weekStart, now) ?: 0.0
        val totalPurchases = transactionDao.getTotalPurchases(workerId, weekStart, now) ?: 0.0
        val totalExpenses = transactionDao.getTotalExpenses(workerId, weekStart, now) ?: 0.0
        val transactionCount = transactionDao.getTransactionCount(workerId, weekStart, now)
        val topItems = transactionDao.getTopSellingItems(workerId, weekStart, now, limit = 5)
        val profit = totalSales - totalPurchases - totalExpenses
        val dailyAvg = if (transactionCount > 0) profit / 7 else 0.0

        if (transactionCount == 0) {
            return ToolResult(
                text = if (language == "sw") {
                    "📋 Wiki hii bado hujarekodi miamala yoyote."
                } else {
                    "📋 No transactions recorded this week."
                },
                data = emptyMap(), success = true
            )
        }

        val topItemsText = if (topItems.isNotEmpty()) {
            val header = if (language == "sw") "Bidhaa bora:" else "Top items:"
            val items = topItems.joinToString("\n") { "  • ${it.item}: KSh ${"%,.0f".format(it.totalAmount)} (${it.count}x)" }
            "\n$header\n$items"
        } else ""

        return ToolResult(
            text = if (language == "sw") {
                buildString {
                    append("📋 Muhtasari wa wiki:\n")
                    append("• Miamala: $transactionCount\n")
                    append("• Mauzo: KSh ${"%,.0f".format(totalSales)}\n")
                    append("• Manunuzi: KSh ${"%,.0f".format(totalPurchases)}\n")
                    append("• Gharama: KSh ${"%,.0f".format(totalExpenses)}\n")
                    append("• Faida: KSh ${"%,.0f".format(profit)}\n")
                    append("• Wastani wa kila siku: KSh ${"%,.0f".format(dailyAvg)}")
                    append(topItemsText)
                }
            } else {
                buildString {
                    append("📋 Weekly summary:\n")
                    append("• Transactions: $transactionCount\n")
                    append("• Sales: KSh ${"%,.0f".format(totalSales)}\n")
                    append("• Purchases: KSh ${"%,.0f".format(totalPurchases)}\n")
                    append("• Expenses: KSh ${"%,.0f".format(totalExpenses)}\n")
                    append("• Profit: KSh ${"%,.0f".format(profit)}\n")
                    append("• Daily average: KSh ${"%,.0f".format(dailyAvg)}")
                    append(topItemsText)
                }
            },
            data = mapOf(
                "transactionCount" to transactionCount.toString(),
                "totalSales" to totalSales.toString(),
                "profit" to profit.toString(),
                "dailyAvg" to dailyAvg.toString()
            ),
            success = true
        )
    }

    override fun onLowMemory() {}
}
