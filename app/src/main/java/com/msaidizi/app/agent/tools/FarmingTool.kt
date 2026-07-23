package com.msaidizi.app.agent.tools

import com.msaidizi.app.data.dao.TransactionDao
import com.msaidizi.app.data.dao.InventoryDao
import com.msaidizi.app.data.entity.TransactionEntity

/**
 * FarmingTool — Agricultural business intelligence.
 */
class FarmingTool(
    private val transactionDao: TransactionDao,
    private val inventoryDao: InventoryDao
) : Tool {
    override val name = "farming"
    override val description = "Kilimo — Farming business tracking"
    override val supportedIntents = listOf("farming", "farming_activity", "farming_input")
    override val memoryRequiredMB = 5

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val amount = (args["amount"] as? Number)?.toDouble()
            ?: args["amount"]?.toString()?.toDoubleOrNull()
        val workerId = args["workerId"]?.toString() ?: throw IllegalStateException("workerId must be provided by SuperAgent")

        if (amount != null) {
            val item = args["item"]?.toString() ?: "mazao"
            val type = args["type"]?.toString() ?: "sale"
            val quantity = (args["quantity"] as? Number)?.toDouble() ?: 1.0

            val transaction = TransactionEntity(
                type = when (type) {
                    "sale" -> "SALE"
                    "input", "seed", "fertilizer" -> "PURCHASE"
                    else -> "EXPENSE"
                },
                item = "Farming: $item",
                quantity = quantity,
                amount = amount,
                category = "farming",
                workerId = workerId
            )
            transactionDao.insert(transaction)

            // Add to inventory if it's a harvest
            if (type == "sale") {
                inventoryDao.incrementStock(workerId, item, quantity)
            }

            return ToolResult(
                text = if (language == "sw") {
                    "🌾 Kilimo: $item imerekodiwa — KSh ${"%,.0f".format(amount)}"
                } else {
                    "🌾 Farming: $item recorded — KSh ${"%,.0f".format(amount)}"
                },
                data = mapOf("item" to item, "amount" to amount.toString(), "type" to type),
                success = true
            )
        }

        return ToolResult(
            text = if (language == "sw") {
                "🌾 Kilimo — Rekodi shughuli zako:\n• \"Nimevuna mahindi kwa 5000\" — Rekodi mauzo\n• \"Nimenunua mbegu kwa 200\" — Rekodi gharama\n• \"Stock ya mahindi\" — Angalia stock"
            } else {
                "🌾 Farming — Record your activities:\n• \"Harvested maize for 5000\" — Record sale\n• \"Bought seeds for 200\" — Record cost\n• \"Maize stock\" — Check stock"
            },
            data = emptyMap(),
            success = true
        )
    }

    override fun onLowMemory() {}
}
