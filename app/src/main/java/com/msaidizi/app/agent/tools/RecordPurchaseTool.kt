package com.msaidizi.app.agent.tools

import com.msaidizi.app.data.dao.TransactionDao
import com.msaidizi.app.data.dao.InventoryDao
import com.msaidizi.app.data.entity.TransactionEntity

/**
 * RecordPurchaseTool — Record a purchase/supply transaction.
 */
class RecordPurchaseTool(
    private val transactionDao: TransactionDao,
    private val inventoryDao: InventoryDao
) : Tool {
    override val name = "record_purchase"
    override val description = "Rekodi manunuzi — Record a purchase"
    override val supportedIntents = listOf("purchase", "record_purchase")
    override val memoryRequiredMB = 5

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val amount = (args["amount"] as? Number)?.toDouble()
            ?: args["amount"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult(
                text = if (language == "sw") "Umelinunua kwa bei gani?" else "What was the purchase price?",
                data = emptyMap(), success = false, errorCode = "MISSING_AMOUNT"
            )

        val item = args["item"]?.toString() ?: "bidhaa"
        val quantity = (args["quantity"] as? Number)?.toDouble() ?: 1.0
        val unitPrice = if (quantity > 0) amount / quantity else amount

        val transaction = TransactionEntity(
            type = "PURCHASE",
            item = item,
            quantity = quantity,
            unitPrice = unitPrice,
            amount = amount,
            workerId = args["workerId"]?.toString() ?: throw IllegalStateException("workerId must be provided by SuperAgent")
        )
        val id = transactionDao.insert(transaction)

        // Update inventory
        inventoryDao.incrementStock(
            workerId = transaction.workerId,
            itemName = item,
            amount = quantity
        )

        return ToolResult(
            text = if (language == "sw") {
                "✅ Umenunua $item${if (quantity != 1.0) " ($quantity)" else ""}, KSh ${"%,.0f".format(amount)}. Stock imeongezeka."
            } else {
                "✅ Recorded purchase: $item${if (quantity != 1.0) " ($quantity)" else ""}, KSh ${"%,.0f".format(amount)}. Stock increased."
            },
            data = mapOf(
                "id" to id.toString(),
                "item" to item,
                "amount" to amount.toString(),
                "quantity" to quantity.toString(),
                "type" to "PURCHASE"
            ),
            success = true
        )
    }

    override fun onLowMemory() {}
}
