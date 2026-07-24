package com.msaidizi.app.superagent.tools
import javax.inject.Inject
class RestockPredictor @Inject constructor() {
    private val salesHistory = mutableMapOf<String, MutableList<Int>>()
    fun predict(product: String, currentStock: Int): ToolResult {
        val dailySales = salesHistory[product] ?: mutableListOf()
        if (dailySales.size < 3) return ToolResult.Success("Need more sales data for $product to predict restock")
        val avgDaily = dailySales.takeLast(7).average()
        val daysUntilStockout = if (avgDaily > 0) (currentStock / avgDaily).toInt() else 999
        return when {
            daysUntilStockout <= 1 -> ToolResult.Success("URGENT: Restock $product today! Only $currentStock left, selling ${avgDaily.toInt()}/day")
            daysUntilStockout <= 3 -> ToolResult.Success("Restock $product within $daysUntilStockout days")
            else -> ToolResult.Success("$product stock OK for $daysUntilStockout days")
        }
    }
    fun recordSale(product: String, quantity: Int) { salesHistory.getOrPut(product) { mutableListOf() }.add(quantity) }
}
