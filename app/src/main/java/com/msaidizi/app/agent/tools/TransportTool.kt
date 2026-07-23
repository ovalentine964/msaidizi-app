package com.msaidizi.app.agent.tools

import com.msaidizi.app.data.dao.TransactionDao
import com.msaidizi.app.data.entity.TransactionEntity

/**
 * TransportTool — Transport business intelligence (boda-boda, matatu).
 */
class TransportTool(
    private val transactionDao: TransactionDao
) : Tool {
    override val name = "transport"
    override val description = "Usafiri — Transport business tracking"
    override val supportedIntents = listOf("transport", "transport_income", "transport_expense")
    override val memoryRequiredMB = 5

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val action = args["action"]?.toString() ?: "record"
        val amount = (args["amount"] as? Number)?.toDouble()
            ?: args["amount"]?.toString()?.toDoubleOrNull()
        val workerId = args["workerId"]?.toString() ?: throw IllegalStateException("workerId must be provided by SuperAgent")

        if (amount != null) {
            // Record transport transaction
            val type = args["type"]?.toString() ?: "income"
            val description = args["item"]?.toString()
                ?: if (type == "income") "Trip income" else "Transport expense"

            val transaction = TransactionEntity(
                type = if (type == "income") "SALE" else "EXPENSE",
                item = "Transport: $description",
                amount = amount,
                category = "transport",
                workerId = workerId
            )
            transactionDao.insert(transaction)

            return ToolResult(
                text = if (language == "sw") {
                    "🚗 Usafiri umerekodiwa: $description — KSh ${"%,.0f".format(amount)}"
                } else {
                    "🚗 Transport recorded: $description — KSh ${"%,.0f".format(amount)}"
                },
                data = mapOf("type" to type, "amount" to amount.toString()),
                success = true
            )
        }

        // Show transport summary
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis
        val now = System.currentTimeMillis()

        val todayTrips = transactionDao.getByTimeRange(workerId, todayStart, now)
            .filter { it.category == "transport" || it.item.startsWith("Transport:") }
        val todayIncome = todayTrips.filter { it.type == "SALE" }.sumOf { it.amount }
        val todayExpense = todayTrips.filter { it.type == "EXPENSE" }.sumOf { it.amount }

        return ToolResult(
            text = if (language == "sw") {
                "🚗 Usafiri leo:\n• Mapato: KSh ${"%,.0f".format(todayIncome)}\n• Gharama: KSh ${"%,.0f".format(todayExpense)}\n• Faida: KSh ${"%,.0f".format(todayIncome - todayExpense)}\n\nSema: \"Nimefanya trip kwa 300\" kurekodi"
            } else {
                "🚗 Transport today:\n• Income: KSh ${"%,.0f".format(todayIncome)}\n• Expenses: KSh ${"%,.0f".format(todayExpense)}\n• Profit: KSh ${"%,.0f".format(todayIncome - todayExpense)}\n\nSay: \"Trip income 300\" to record"
            },
            data = mapOf("todayIncome" to todayIncome.toString(), "todayExpense" to todayExpense.toString()),
            success = true
        )
    }

    override fun onLowMemory() {}
}
