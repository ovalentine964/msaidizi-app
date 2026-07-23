package com.msaidizi.app.agent.tools

import com.msaidizi.app.data.dao.InventoryDao

/**
 * CheckStockTool — Query current inventory/stock levels.
 */
class CheckStockTool(
    private val inventoryDao: InventoryDao
) : Tool {
    override val name = "check_stock"
    override val description = "Angalia stock — Check inventory levels"
    override val supportedIntents = listOf("stock_query", "check_stock")
    override val memoryRequiredMB = 5

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val workerId = args["workerId"]?.toString() ?: throw IllegalStateException("workerId must be provided by SuperAgent")
        val itemFilter = args["item"]?.toString()

        if (itemFilter != null) {
            // Check specific item
            val items = inventoryDao.searchByName(workerId, itemFilter)
            return if (items.isNotEmpty()) {
                val item = items.first()
                val stockStatus = when {
                    item.quantity <= 0 -> if (language == "sw") "❌ IMEISHA" else "❌ OUT OF STOCK"
                    item.quantity <= item.reorderLevel -> if (language == "sw") "⚠️ NI CHINI" else "⚠️ LOW"
                    else -> if (language == "sw") "✅ IPo" else "✅ IN STOCK"
                }
                ToolResult(
                    text = if (language == "sw") {
                        "📦 $stockStatus — ${item.itemName}: ${item.quantity} ${item.unit}"
                    } else {
                        "📦 $stockStatus — ${item.itemName}: ${item.quantity} ${item.unit}"
                    },
                    data = mapOf(
                        "item" to item.itemName,
                        "quantity" to item.quantity.toString(),
                        "unit" to item.unit,
                        "status" to stockStatus
                    ),
                    success = true
                )
            } else {
                ToolResult(
                    text = if (language == "sw") {
                        "📦 Hakuna $itemFilter kwenye stock. Unaweza kuongeza?"
                    } else {
                        "📦 No $itemFilter in stock. Would you like to add it?"
                    },
                    data = emptyMap(),
                    success = true
                )
            }
        }

        // Show all stock
        val allItems = inventoryDao.getByWorker(workerId)
        val lowStock = inventoryDao.getLowStock(workerId)

        if (allItems.isEmpty()) {
            return ToolResult(
                text = if (language == "sw") {
                    "📦 Stock yako bado haijarekodiwa. Sema: Nimenunua [bidhaa] [kiasi]"
                } else {
                    "📦 Your stock hasn't been recorded yet. Say: I bought [item] [quantity]"
                },
                data = emptyMap(),
                success = true
            )
        }

        val stockList = allItems.joinToString("\n") { item ->
            val status = when {
                item.quantity <= 0 -> "❌"
                item.quantity <= item.reorderLevel -> "⚠️"
                else -> "✅"
            }
            "$status ${item.itemName}: ${item.quantity} ${item.unit}"
        }

        val warningMsg = if (lowStock.isNotEmpty()) {
            if (language == "sw") {
                "\n\n⚠️ Bidhaa ${lowStock.size} ziko chini ya kiwango!"
            } else {
                "\n\n⚠️ ${lowStock.size} items are below reorder level!"
            }
        } else ""

        return ToolResult(
            text = if (language == "sw") {
                "📦 Stock yako:\n$stockList$warningMsg"
            } else {
                "📦 Your stock:\n$stockList$warningMsg"
            },
            data = mapOf(
                "totalItems" to allItems.size.toString(),
                "lowStockCount" to lowStock.size.toString()
            ),
            success = true
        )
    }

    override fun onLowMemory() {}
}
