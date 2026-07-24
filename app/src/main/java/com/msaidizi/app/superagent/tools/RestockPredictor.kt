package com.msaidizi.app.superagent.tools

import javax.inject.Inject
import javax.inject.Singleton

/**
 * RestockPredictor — Predict when products need restocking based on sales history.
 */
@Singleton
class RestockPredictor @Inject constructor() : Tool {

    override val name = "restock_predictor"
    override val description = "Predict when products need restocking based on sales velocity"

    private val salesHistory = mutableMapOf<String, MutableList<Int>>()

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "predict"
        return when (action.lowercase()) {
            "predict" -> {
                val product = params["product"]
                    ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
                val currentStock = params["stock"]?.toIntOrNull()
                    ?: return ToolResult.error(name, "Current stock required", "MISSING_STOCK")
                predict(product, currentStock)
            }
            "record_sale" -> {
                val product = params["product"]
                    ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
                val quantity = params["quantity"]?.toIntOrNull() ?: 1
                recordSale(product, quantity)
                ToolResult.success(name, mapOf("product" to product, "quantity" to quantity), "Sale recorded for $product")
            }
            "history" -> {
                val product = params["product"]
                    ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
                val history = salesHistory[product] ?: emptyList()
                ToolResult.success(
                    name,
                    mapOf("product" to product, "days" to history.size, "total_sold" to history.sum()),
                    "$product: ${history.size} days of data, total sold: ${history.sum()}"
                )
            }
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    fun predict(product: String, currentStock: Int): ToolResult {
        val dailySales = salesHistory[product] ?: mutableListOf()
        if (dailySales.size < 3) {
            return ToolResult.success(
                name,
                mapOf("product" to product, "data_points" to dailySales.size),
                "Need more sales data for $product to predict restock (have ${dailySales.size}, need 3+)"
            )
        }
        val avgDaily = dailySales.takeLast(7).average()
        val daysUntilStockout = if (avgDaily > 0) (currentStock / avgDaily).toInt() else 999
        return when {
            daysUntilStockout <= 1 -> ToolResult.error(
                name,
                "URGENT: Restock $product today! Only $currentStock left, selling ${avgDaily.toInt()}/day",
                "LOW_STOCK"
            )
            daysUntilStockout <= 3 -> ToolResult.success(
                name,
                mapOf("product" to product, "days_left" to daysUntilStockout, "avg_daily" to avgDaily),
                "Restock $product within $daysUntilStockout days"
            )
            else -> ToolResult.success(
                name,
                mapOf("product" to product, "days_left" to daysUntilStockout, "avg_daily" to avgDaily),
                "$product stock OK for $daysUntilStockout days"
            )
        }
    }

    fun recordSale(product: String, quantity: Int) {
        salesHistory.getOrPut(product) { mutableListOf() }.add(quantity)
    }
}
