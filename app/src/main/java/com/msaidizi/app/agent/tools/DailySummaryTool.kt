package com.msaidizi.app.agent.tools

import com.msaidizi.app.data.dao.TransactionDao
import java.util.Calendar

/**
 * DailySummaryTool — Generate a daily business summary.
 */
class DailySummaryTool(
    private val transactionDao: TransactionDao
) : Tool {
    override val name = "daily_summary"
    override val description = "Muhtasari wa leo — Daily business summary"
    override val supportedIntents = listOf("daily_summary")
    override val memoryRequiredMB = 10

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val workerId = args["workerId"]?.toString() ?: throw IllegalStateException("workerId must be provided by SuperAgent")

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis
        val now = System.currentTimeMillis()

        val totalSales = transactionDao.getTotalSales(workerId, todayStart, now) ?: 0.0
        val totalPurchases = transactionDao.getTotalPurchases(workerId, todayStart, now) ?: 0.0
        val totalExpenses = transactionDao.getTotalExpenses(workerId, todayStart, now) ?: 0.0
        val transactionCount = transactionDao.getTransactionCount(workerId, todayStart, now)
        val topItems = transactionDao.getTopSellingItems(workerId, todayStart, now, limit = 3)
        val profit = totalSales - totalPurchases - totalExpenses

        if (transactionCount == 0) {
            return ToolResult(
                text = if (language == "sw") {
                    "📋 Leo bado hujarekodi miamala yoyote. Sema: Nimeuza [bidhaa] kwa [bei]"
                } else {
                    "📋 No transactions recorded today. Say: I sold [item] for [price]"
                },
                data = emptyMap(),
                success = true
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
                    append("📋 Muhtasari wa leo:\n")
                    append("• Miamala: $transactionCount\n")
                    append("• Mauzo: KSh ${"%,.0f".format(totalSales)}\n")
                    append("• Manunuzi: KSh ${"%,.0f".format(totalPurchases)}\n")
                    append("• Gharama: KSh ${"%,.0f".format(totalExpenses)}\n")
                    append("• Faida: KSh ${"%,.0f".format(profit)}")
                    append(topItemsText)
                }
            } else {
                buildString {
                    append("📋 Today's summary:\n")
                    append("• Transactions: $transactionCount\n")
                    append("• Sales: KSh ${"%,.0f".format(totalSales)}\n")
                    append("• Purchases: KSh ${"%,.0f".format(totalPurchases)}\n")
                    append("• Expenses: KSh ${"%,.0f".format(totalExpenses)}\n")
                    append("• Profit: KSh ${"%,.0f".format(profit)}")
                    append(topItemsText)
                }
            },
            data = mapOf(
                "transactionCount" to transactionCount.toString(),
                "totalSales" to totalSales.toString(),
                "totalPurchases" to totalPurchases.toString(),
                "totalExpenses" to totalExpenses.toString(),
                "profit" to profit.toString()
            ),
            success = true
        )
    }

    override fun onLowMemory() {}
}
