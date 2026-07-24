package com.msaidizi.app.superagent.tools
import javax.inject.Inject

data class ReceiptItem(val name: String, val quantity: Int, val unitPrice: Double, val total: Double)
data class ReceiptResult(val items: List<ReceiptItem>, val totalAmount: Double, val merchantName: String?)

class ReceiptScanner @Inject constructor() {
    fun scanReceipt(ocrText: String): ToolResult {
        val lines = ocrText.lines().filter { it.isNotBlank() }
        val items = mutableListOf<ReceiptItem>()
        var total = 0.0
        for (line in lines) {
            val match = Regex("(.+?)\\s+(\\d+)\\s+([\\d,.]+)").find(line)
            if (match != null) {
                val name = match.groupValues[1].trim()
                val qty = match.groupValues[2].toInt()
                val price = match.groupValues[3].replace(",", "").toDouble()
                items.add(ReceiptItem(name, qty, price, qty * price))
                total += qty * price
            }
        }
        return if (items.isNotEmpty()) {
            ToolResult.Success(ReceiptResult(items, total, lines.firstOrNull()).toString())
        } else {
            ToolResult.Error("Could not parse receipt text")
        }
    }
}
