package com.msaidizi.app.agent.tools

import com.msaidizi.app.data.dao.TransactionDao

/**
 * SavingsTool — Track savings and savings goals.
 */
class SavingsTool(
    private val transactionDao: TransactionDao
) : Tool {
    override val name = "savings"
    override val description = "Akiba — Savings tracking"
    override val supportedIntents = listOf("savings_check", "savings_goal", "savings_update")
    override val memoryRequiredMB = 5

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val workerId = args["workerId"]?.toString() ?: throw IllegalStateException("workerId must be provided by SuperAgent")

        // Calculate savings potential from transaction data
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val monthStart = cal.timeInMillis
        val now = System.currentTimeMillis()

        val monthSales = transactionDao.getTotalSales(workerId, monthStart, now) ?: 0.0
        val monthExpenses = (transactionDao.getTotalPurchases(workerId, monthStart, now) ?: 0.0) +
                           (transactionDao.getTotalExpenses(workerId, monthStart, now) ?: 0.0)
        val monthProfit = monthSales - monthExpenses
        val recommendedSavings = (monthProfit * 0.1).coerceAtLeast(0.0)

        return ToolResult(
            text = if (language == "sw") {
                buildString {
                    append("💰 Akiba — Mwezi huu:\n")
                    append("• Faida: KSh ${"%,.0f".format(monthProfit)}\n")
                    append("• Akiba inayopendekezwa (10%): KSh ${"%,.0f".format(recommendedSavings)}\n\n")
                    if (recommendedSavings > 0) {
                        append("💡 Weka KSh ${"%,.0f".format(recommendedSavings)} kwenye akiba kila mwezi!")
                    } else {
                        append("💡 Ongeza mauzo ili uweze kuweka akiba.")
                    }
                }
            } else {
                buildString {
                    append("💰 Savings — This month:\n")
                    append("• Profit: KSh ${"%,.0f".format(monthProfit)}\n")
                    append("• Recommended savings (10%): KSh ${"%,.0f".format(recommendedSavings)}\n\n")
                    if (recommendedSavings > 0) {
                        append("💡 Save KSh ${"%,.0f".format(recommendedSavings)} each month!")
                    } else {
                        append("💡 Increase sales to build savings capacity.")
                    }
                }
            },
            data = mapOf(
                "monthProfit" to monthProfit.toString(),
                "recommendedSavings" to recommendedSavings.toString()
            ),
            success = true
        )
    }

    override fun onLowMemory() {}
}
