package com.msaidizi.app.agent.tools

import com.msaidizi.app.data.dao.TransactionDao
import com.msaidizi.app.data.dao.InventoryDao
import com.msaidizi.app.data.entity.TransactionEntity

/**
 * RecordSaleTool — Record a sale transaction.
 * Replaces: TransactionHandler.handleSale() + BusinessAgent.recordSale()
 */
class RecordSaleTool(
    private val transactionDao: TransactionDao,
    private val inventoryDao: InventoryDao
) : Tool {
    override val name = "record_sale"
    override val description = "Rekodi mauzo — Record a sale"
    override val supportedIntents = listOf("sale", "record_sale")
    override val memoryRequiredMB = 5

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val amount = (args["amount"] as? Number)?.toDouble()
            ?: args["amount"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult(
                text = if (language == "sw") "Bei ni ngapi? Sema kwa mfano: Nimeuza nyanya kwa 500"
                else "What was the price? Say for example: I sold tomatoes for 500",
                data = emptyMap(), success = false, errorCode = "MISSING_AMOUNT"
            )

        val item = args["item"]?.toString() ?: "bidhaa"
        val quantity = (args["quantity"] as? Number)?.toDouble() ?: 1.0
        val unitPrice = if (quantity > 0) amount / quantity else amount

        // Record transaction
        val transaction = TransactionEntity(
            type = "SALE",
            item = item,
            quantity = quantity,
            unitPrice = unitPrice,
            amount = amount,
            workerId = args["workerId"]?.toString() ?: throw IllegalStateException("workerId must be provided by SuperAgent")
        )
        val id = transactionDao.insert(transaction)

        // Update inventory
        val decremented = inventoryDao.decrementStock(
            workerId = transaction.workerId,
            itemName = item,
            amount = quantity
        )

        val stockMsg = if (decremented > 0) {
            if (language == "sw") " Stock imepungua." else " Stock updated."
        } else ""

        return ToolResult(
            text = if (language == "sw") {
                "✅ Umefanya mauzo ya $item${if (quantity != 1.0) " (${quantity})" else ""}, KSh ${"%,.0f".format(amount)}.$stockMsg"
            } else {
                "✅ Recorded sale: $item${if (quantity != 1.0) " ($quantity)" else ""}, KSh ${"%,.0f".format(amount)}.$stockMsg"
            },
            data = mapOf(
                "id" to id.toString(),
                "item" to item,
                "amount" to amount.toString(),
                "quantity" to quantity.toString(),
                "type" to "SALE"
            ),
            success = true
        )
    }

    override fun onLowMemory() {}
}
