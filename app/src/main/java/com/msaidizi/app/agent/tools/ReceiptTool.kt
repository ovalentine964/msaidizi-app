package com.msaidizi.app.agent.tools

/**
 * ReceiptTool — Receipt scanning and OCR.
 * For now, provides guidance on manual entry. OCR integration is a future enhancement.
 */
class ReceiptTool : Tool {
    override val name = "receipt"
    override val description = "Risiti — Receipt scanning"
    override val supportedIntents = listOf("receipt_scan", "receipt")
    override val memoryRequiredMB = 50

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        return ToolResult(
            text = if (language == "sw") {
                "📸 Skania risiti bado haijapatikana. Kwa sasa, sema tu:\n• \"Nimenunua [bidhaa] kwa [bei]\" — Rekodi manunuzi\n• \"Nimetumia [kiasi] kwa [sababu]\" — Rekodi gharama"
            } else {
                "📸 Receipt scanning not yet available. For now, just say:\n• \"I bought [item] for [price]\" — Record purchase\n• \"I spent [amount] on [reason]\" — Record expense"
            },
            data = emptyMap(),
            success = true
        )
    }

    override fun onLowMemory() {}
}
