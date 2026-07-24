package com.msaidizi.app.agent

import com.msaidizi.app.core.model.IntentType

/**
 * Stub: Pattern-based intent classifier.
 * Classifies Swahili/English/Sheng business inputs without LLM.
 */
class IntentRouter(private val config: IntentPatternConfig? = null) {
    fun classify(text: String): IntentResult {
        if (text.isBlank()) return IntentResult(IntentType.UNKNOWN, 0.0, emptyMap(), needsLLM = false)
        // Basic pattern matching
        val lower = text.lowercase()
        return when {
            lower.contains("nimeuza") || lower.contains("sold") -> IntentResult(IntentType.SALE, 0.9, extractSaleData(text))
            lower.contains("nimenunua") || lower.contains("bought") -> IntentResult(IntentType.PURCHASE, 0.9, extractPurchaseData(text))
            lower.contains("nimetumia") || lower.contains("spent") -> IntentResult(IntentType.EXPENSE, 0.85, extractExpenseData(text))
            lower.contains("salio") || lower.contains("balance") -> IntentResult(IntentType.CHECK_BALANCE, 0.95, emptyMap())
            lower.contains("faida") || lower.contains("profit") -> IntentResult(IntentType.PROFIT_QUERY, 0.9, emptyMap())
            lower.contains("habari") || lower.contains("jambo") || lower.contains("hello") -> IntentResult(IntentType.GREETING, 0.95, emptyMap())
            lower.contains("msaada") || lower.contains("help") -> IntentResult(IntentType.HELP, 0.9, emptyMap())
            lower.contains("ripoti") || lower.contains("report") || lower.contains("summary") -> IntentResult(IntentType.DAILY_SUMMARY, 0.9, emptyMap())
            lower.contains("lengo") || lower.contains("goal") -> IntentResult(IntentType.GOAL_CREATE, 0.85, emptyMap())
            lower.contains("mkopo") || lower.contains("loan") -> IntentResult(IntentType.LOAN_RECORD, 0.85, emptyMap())
            lower.contains("sadaka") || lower.contains("zaka") || lower.contains("nimektoa") -> IntentResult(IntentType.GIVING_RECORD, 0.85, emptyMap())
            else -> IntentResult(IntentType.UNKNOWN, 0.0, emptyMap(), needsLLM = true)
        }
    }

    private fun extractSaleData(text: String): Map<String, String> {
        val amountRegex = Regex("(?:kwa|sh|for)\\s*(?:sh)?\\s*(\\d+)")
        val itemRegex = Regex("(?:nimeuza|sold)\\s+(.+?)\\s+(?:kwa|for|sh)")
        val data = mutableMapOf<String, String>()
        amountRegex.find(text)?.let { data["amount"] = it.groupValues[1] }
        itemRegex.find(text)?.let { data["item"] = it.groupValues[1].trim() }
        return data
    }

    private fun extractPurchaseData(text: String): Map<String, String> {
        val amountRegex = Regex("(?:kwa|sh|for)\\s*(?:sh)?\\s*(\\d+)")
        val data = mutableMapOf<String, String>()
        amountRegex.find(text)?.let { data["amount"] = it.groupValues[1] }
        return data
    }

    private fun extractExpenseData(text: String): Map<String, String> {
        val amountRegex = Regex("(?:sh)?\\s*(\\d+)\\s+(?:kwa|kwenye|on)")
        val data = mutableMapOf<String, String>()
        amountRegex.find(text)?.let { data["amount"] = it.groupValues[1] }
        return data
    }
}

data class IntentResult(
    val intent: IntentType,
    val confidence: Double,
    val extractedData: Map<String, String>,
    val needsLLM: Boolean = false
)
