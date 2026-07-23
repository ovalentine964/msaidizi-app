package com.msaidizi.app.agent.tools

import com.msaidizi.app.data.dao.TransactionDao
import com.msaidizi.app.data.dao.InventoryDao

/**
 * RetailTool — Retail shop (duka) specific intelligence.
 */
class RetailTool(
    private val transactionDao: TransactionDao,
    private val inventoryDao: InventoryDao
) : Tool {
    override val name = "retail"
    override val description = "Duka — Retail shop tracking"
    override val supportedIntents = listOf("retail", "retail_sales", "retail_stock")
    override val memoryRequiredMB = 5

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        // Retail is just sales + inventory — delegate to appropriate tools
        val item = args["item"]?.toString()
        val amount = (args["amount"] as? Number)?.toDouble()

        if (amount != null && item != null) {
            val saleTool = RecordSaleTool(transactionDao, inventoryDao)
            return saleTool.execute(args, language)
        }

        // Show retail summary
        val workerId = args["workerId"]?.toString() ?: throw IllegalStateException("workerId must be provided by SuperAgent")
        val lowStock = inventoryDao.getLowStock(workerId)

        return ToolResult(
            text = if (language == "sw") {
                buildString {
                    append("🏪 Duka yako:\n")
                    if (lowStock.isNotEmpty()) {
                        append("⚠️ Bidhaa ${lowStock.size} ziko chini:\n")
                        lowStock.take(5).forEach {
                            append("  • ${it.itemName}: ${it.quantity} ${it.unit}\n")
                        }
                    } else {
                        append("✅ Stock iko sawa.\n")
                    }
                    append("\nSema: \"Nimeuza [bidhaa] kwa [bei]\" kurekodi mauzo.")
                }
            } else {
                buildString {
                    append("🏪 Your shop:\n")
                    if (lowStock.isNotEmpty()) {
                        append("⚠️ ${lowStock.size} items low on stock:\n")
                        lowStock.take(5).forEach {
                            append("  • ${it.itemName}: ${it.quantity} ${it.unit}\n")
                        }
                    } else {
                        append("✅ Stock levels good.\n")
                    }
                    append("\nSay: \"I sold [item] for [price]\" to record sale.")
                }
            },
            data = mapOf("lowStockCount" to lowStock.size.toString()),
            success = true
        )
    }

    override fun onLowMemory() {}
}
