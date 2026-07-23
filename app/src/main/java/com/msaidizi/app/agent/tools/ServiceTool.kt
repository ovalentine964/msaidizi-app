package com.msaidizi.app.agent.tools

import com.msaidizi.app.data.dao.TransactionDao
import com.msaidizi.app.data.entity.TransactionEntity

/**
 * ServiceTool — Service business intelligence (salon, mechanic, etc.)
 */
class ServiceTool(
    private val transactionDao: TransactionDao
) : Tool {
    override val name = "service"
    override val description = "Huduma — Service business tracking"
    override val supportedIntents = listOf("service", "service_client", "service_job")
    override val memoryRequiredMB = 5

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val amount = (args["amount"] as? Number)?.toDouble()
            ?: args["amount"]?.toString()?.toDoubleOrNull()
        val workerId = args["workerId"]?.toString() ?: throw IllegalStateException("workerId must be provided by SuperAgent")

        if (amount != null) {
            val item = args["item"]?.toString() ?: "Service"
            val client = args["client"]?.toString() ?: ""
            val description = if (client.isNotEmpty()) "$item ($client)" else item

            val transaction = TransactionEntity(
                type = "SALE",
                item = "Service: $description",
                amount = amount,
                category = "service",
                workerId = workerId
            )
            transactionDao.insert(transaction)

            return ToolResult(
                text = if (language == "sw") {
                    "🔧 Huduma imerekodiwa: $description — KSh ${"%,.0f".format(amount)}"
                } else {
                    "🔧 Service recorded: $description — KSh ${"%,.0f".format(amount)}"
                },
                data = mapOf("item" to item, "amount" to amount.toString(), "client" to client),
                success = true
            )
        }

        return ToolResult(
            text = if (language == "sw") {
                "🔧 Huduma — Rekodi:\n• \"Nimekata nywele kwa 200\" — Rekodi huduma\n• \"Nimetengeneza gari kwa 3000\" — Rekodi kazi"
            } else {
                "🔧 Service — Record:\n• \"Haircut for 200\" — Record service\n• \"Car repair for 3000\" — Record job"
            },
            data = emptyMap(),
            success = true
        )
    }

    override fun onLowMemory() {}
}
