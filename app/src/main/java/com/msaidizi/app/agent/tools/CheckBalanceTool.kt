package com.msaidizi.app.agent.tools

import com.msaidizi.app.data.dao.TransactionDao
import java.util.Calendar

/**
 * CheckBalanceTool — Query current financial balance.
 */
class CheckBalanceTool(
    private val transactionDao: TransactionDao
) : Tool {
    override val name = "check_balance"
    override val description = "Angalia salio — Check financial balance"
    override val supportedIntents = listOf("check_balance", "balance")
    override val memoryRequiredMB = 5

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val workerId = args["workerId"]?.toString()
            ?: throw IllegalStateException("workerId must be provided by SuperAgent")

        val totalSales = transactionDao.getAllTimeSales(workerId) ?: 0.0
        val totalExpenses = transactionDao.getAllTimeExpenses(workerId) ?: 0.0
        val balance = totalSales - totalExpenses

        // Today's figures
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis
        val now = System.currentTimeMillis()

        val todaySales = transactionDao.getTotalSales(workerId, todayStart, now) ?: 0.0
        val todayExpenses = (transactionDao.getTotalPurchases(workerId, todayStart, now) ?: 0.0) +
                           (transactionDao.getTotalExpenses(workerId, todayStart, now) ?: 0.0)

        return ToolResult(
            text = if (language == "sw") {
                buildString {
                    append("📊 Salio lako:\n")
                    append("• Leo: Mauzo KSh ${"%,.0f".format(todaySales)}, Matumizi KSh ${"%,.0f".format(todayExpenses)}\n")
                    append("• Jumla: Mauzo KSh ${"%,.0f".format(totalSales)}, Gharama KSh ${"%,.0f".format(totalExpenses)}\n")
                    append("• Salio: KSh ${"%,.0f".format(balance)}")
                }
            } else {
                buildString {
                    append("📊 Your balance:\n")
                    append("• Today: Sales KSh ${"%,.0f".format(todaySales)}, Expenses KSh ${"%,.0f".format(todayExpenses)}\n")
                    append("• Total: Sales KSh ${"%,.0f".format(totalSales)}, Costs KSh ${"%,.0f".format(totalExpenses)}\n")
                    append("• Balance: KSh ${"%,.0f".format(balance)}")
                }
            },
            data = mapOf(
                "todaySales" to todaySales.toString(),
                "todayExpenses" to todayExpenses.toString(),
                "totalSales" to totalSales.toString(),
                "totalExpenses" to totalExpenses.toString(),
                "balance" to balance.toString()
            ),
            success = true
        )
    }

    override fun onLowMemory() {}
}
