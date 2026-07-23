package com.msaidizi.app.agent.tools

import com.msaidizi.app.data.dao.InventoryDao
import com.msaidizi.app.data.entity.InventoryEntity

/**
 * InventoryTool — Manage inventory (add, update, check).
 */
class InventoryTool(
    private val inventoryDao: InventoryDao
) : Tool {
    override val name = "inventory"
    override val description = "Stock — Manage inventory"
    override val supportedIntents = listOf("stock_check", "stock_update", "inventory")
    override val memoryRequiredMB = 5

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val action = args["action"]?.toString() ?: "check"
        val workerId = args["workerId"]?.toString() ?: throw IllegalStateException("workerId must be provided by SuperAgent")
        val item = args["item"]?.toString()

        return when (action) {
            "add" -> addItem(args, workerId, language)
            "update" -> updateStock(args, workerId, language)
            "check" -> checkStock(workerId, item, language)
            else -> checkStock(workerId, item, language)
        }
    }

    private suspend fun addItem(args: Map<String, Any>, workerId: String, language: String): ToolResult {
        val item = args["item"]?.toString()
            ?: return ToolResult(
                text = if (language == "sw") "Bidhaa gani?" else "What item?",
                data = emptyMap(), success = false, errorCode = "MISSING_ITEM"
            )
        val quantity = (args["quantity"] as? Number)?.toDouble() ?: 0.0
        val unitCost = (args["unitCost"] as? Number)?.toDouble() ?: 0.0

        val entity = InventoryEntity(
            itemName = item,
            quantity = quantity,
            unitCost = unitCost,
            workerId = workerId
        )
        inventoryDao.insert(entity)

        return ToolResult(
            text = if (language == "sw") {
                "📦 $item imeongezwa kwenye stock: $quantity kwa KSh ${"%,.0f".format(unitCost)} kila moja."
            } else {
                "📦 $item added to stock: $quantity at KSh ${"%,.0f".format(unitCost)} each."
            },
            data = mapOf("item" to item, "quantity" to quantity.toString()),
            success = true
        )
    }

    private suspend fun updateStock(args: Map<String, Any>, workerId: String, language: String): ToolResult {
        val item = args["item"]?.toString()
            ?: return ToolResult(
                text = if (language == "sw") "Bidhaa gani?" else "What item?",
                data = emptyMap(), success = false, errorCode = "MISSING_ITEM"
            )
        val quantity = (args["quantity"] as? Number)?.toDouble()
            ?: return ToolResult(
                text = if (language == "sw") "Kiasi ni ngapi?" else "What quantity?",
                data = emptyMap(), success = false, errorCode = "MISSING_QUANTITY"
            )

        inventoryDao.setStock(workerId, item, quantity)

        return ToolResult(
            text = if (language == "sw") {
                "📦 Stock ya $item imesasishwa: $quantity."
            } else {
                "📦 Stock of $item updated: $quantity."
            },
            data = mapOf("item" to item, "quantity" to quantity.toString()),
            success = true
        )
    }

    private suspend fun checkStock(workerId: String, item: String?, language: String): ToolResult {
        if (item != null) {
            val items = inventoryDao.searchByName(workerId, item)
            return if (items.isNotEmpty()) {
                val found = items.first()
                ToolResult(
                    text = if (language == "sw") {
                        "📦 ${found.itemName}: ${found.quantity} ${found.unit}"
                    } else {
                        "📦 ${found.itemName}: ${found.quantity} ${found.unit}"
                    },
                    data = mapOf("item" to found.itemName, "quantity" to found.quantity.toString()),
                    success = true
                )
            } else {
                ToolResult(
                    text = if (language == "sw") "📦 Hakuna $item kwenye stock." else "📦 No $item in stock.",
                    data = emptyMap(), success = true
                )
            }
        }

        val allItems = inventoryDao.getByWorker(workerId)
        if (allItems.isEmpty()) {
            return ToolResult(
                text = if (language == "sw") "📦 Stock bado haijarekodiwa." else "📦 No stock recorded yet.",
                data = emptyMap(), success = true
            )
        }

        val list = allItems.joinToString("\n") { "📦 ${it.itemName}: ${it.quantity} ${it.unit}" }
        return ToolResult(
            text = list,
            data = mapOf("count" to allItems.size.toString()),
            success = true
        )
    }

    override fun onLowMemory() {}
}
