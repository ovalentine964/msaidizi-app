package com.msaidizi.app.superagent.tools

import javax.inject.Inject
import javax.inject.Singleton

data class ReceiptItem(val name: String, val quantity: Int, val unitPrice: Double, val total: Double)
data class ReceiptResult(val items: List<ReceiptItem>, val totalAmount: Double, val merchantName: String?)

/**
 * ReceiptScanner — Parse OCR text from receipts into structured data.
 */
@Singleton
class ReceiptScanner @Inject constructor() : Tool {

    override val name = "receipt_scanner"
    override val description = "Parse receipt OCR text into structured transaction data"

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "scan"
        return when (action.lowercase()) {
            "scan" -> {
                val ocrText = params["text"]
                    ?: return ToolResult.error(name, "OCR text required", "MISSING_TEXT")
                scanReceipt(ocrText)
            }
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    fun scanReceipt(ocrText: String): ToolResult {
        val lines = ocrText.lines().filter { it.isNotBlank() }
        val items = mutableListOf<ReceiptItem>()
        var total = 0.0
        for (line in lines) {
            val match = Regex("(.+?)\\s+(\\d+)\\s+([\\d,.]+)").find(line)
            if (match != null) {
                val itemName = match.groupValues[1].trim()
                val qty = match.groupValues[2].toInt()
                val price = match.groupValues[3].replace(",", "").toDouble()
                items.add(ReceiptItem(itemName, qty, price, qty * price))
                total += qty * price
            }
        }
        return if (items.isNotEmpty()) {
            val summary = items.joinToString("\n") { "  ${it.name}: ${it.quantity} x Ksh ${"%,.0f".format(it.unitPrice)} = Ksh ${"%,.0f".format(it.total)}" }
            ToolResult.success(
                name,
                mapOf("items" to items, "total" to total, "item_count" to items.size),
                "Receipt parsed (${items.size} items, total: Ksh ${"%,.0f".format(total)})\n$summary"
            )
        } else {
            ToolResult.error(name, "Could not parse receipt text", "PARSE_ERROR")
        }
    }
}
