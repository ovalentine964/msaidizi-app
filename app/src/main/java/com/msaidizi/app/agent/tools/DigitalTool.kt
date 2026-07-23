package com.msaidizi.app.agent.tools

import com.msaidizi.app.data.dao.TransactionDao
import com.msaidizi.app.data.entity.TransactionEntity

/**
 * DigitalTool — Digital/freelance worker intelligence.
 */
class DigitalTool(
    private val transactionDao: TransactionDao
) : Tool {
    override val name = "digital"
    override val description = "Kidijitali — Digital/freelance worker tracking"
    override val supportedIntents = listOf("digital", "digital_income", "digital_client")
    override val memoryRequiredMB = 5

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val amount = (args["amount"] as? Number)?.toDouble()
            ?: args["amount"]?.toString()?.toDoubleOrNull()
        val workerId = args["workerId"]?.toString() ?: throw IllegalStateException("workerId must be provided by SuperAgent")

        if (amount != null) {
            val item = args["item"]?.toString() ?: "Digital service"
            val transaction = TransactionEntity(
                type = "SALE",
                item = "Digital: $item",
                amount = amount,
                category = "digital",
                workerId = workerId
            )
            transactionDao.insert(transaction)

            return ToolResult(
                text = if (language == "sw") {
                    "💻 Kazi ya kidijitali imerekodiwa: $item — KSh ${"%,.0f".format(amount)}"
                } else {
                    "💻 Digital work recorded: $item — KSh ${"%,.0f".format(amount)}"
                },
                data = mapOf("item" to item, "amount" to amount.toString()),
                success = true
            )
        }

        return ToolResult(
            text = if (language == "sw") {
                "💻 Kazi ya kidijitali — Rekodi:\n• \"Nimefanya project kwa 3000\" — Rekodi mapato\n• \"Client amenilipa 5000\" — Rekodi malipo"
            } else {
                "💻 Digital work — Record:\n• \"Project income 3000\" — Record earnings\n• \"Client paid 5000\" — Record payment"
            },
            data = emptyMap(),
            success = true
        )
    }

    override fun onLowMemory() {}
}
