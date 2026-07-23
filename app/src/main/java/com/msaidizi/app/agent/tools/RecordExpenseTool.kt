package com.msaidizi.app.agent.tools

import com.msaidizi.app.data.dao.TransactionDao
import com.msaidizi.app.data.entity.TransactionEntity

/**
 * RecordExpenseTool — Record a business expense (rent, transport, etc.)
 */
class RecordExpenseTool(
    private val transactionDao: TransactionDao
) : Tool {
    override val name = "record_expense"
    override val description = "Rekodi gharama — Record a business expense"
    override val supportedIntents = listOf("expense", "record_expense")
    override val memoryRequiredMB = 5

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val amount = (args["amount"] as? Number)?.toDouble()
            ?: args["amount"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult(
                text = if (language == "sw") "Gharama ni ngapi?" else "How much was the expense?",
                data = emptyMap(), success = false, errorCode = "MISSING_AMOUNT"
            )

        val item = args["item"]?.toString()
            ?: args["category"]?.toString()
            ?: "gharama"

        val transaction = TransactionEntity(
            type = "EXPENSE",
            item = item,
            quantity = 1.0,
            unitPrice = amount,
            amount = amount,
            category = args["category"]?.toString() ?: "",
            workerId = args["workerId"]?.toString() ?: throw IllegalStateException("workerId must be provided by SuperAgent")
        )
        val id = transactionDao.insert(transaction)

        return ToolResult(
            text = if (language == "sw") {
                "✅ Gharama imerekodiwa: $item — KSh ${"%,.0f".format(amount)}"
            } else {
                "✅ Recorded expense: $item — KSh ${"%,.0f".format(amount)}"
            },
            data = mapOf(
                "id" to id.toString(),
                "item" to item,
                "amount" to amount.toString(),
                "type" to "EXPENSE"
            ),
            success = true
        )
    }

    override fun onLowMemory() {}
}
