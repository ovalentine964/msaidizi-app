package com.msaidizi.app.agent

import com.msaidizi.app.core.model.*
import com.msaidizi.app.core.util.SwahiliParser
import timber.log.Timber

/**
 * Intent Router — classifies user input into intents.
 * This is CODE, not LLM — 0 RAM overhead, instant execution.
 *
 * Uses precompiled regex patterns for Swahili business commands.
 * Handles 90%+ of user input without needing the LLM.
 */
class IntentRouter {

    // === SALE PATTERNS ===
    private val salePatterns = listOf(
        // "Nimeuza mandazi kumi kwa Sh 500"
        Regex("""(?i)(nime?uza|niliuza|nikauza|sold|nauza|nimeuzia)\s+(.+)\s+(kwa|sh|ksh)\s*(\d+(?:\.\d+)?)"""),
        // "Mandazi kumi kwa 500"
        Regex("""(?i)(mandazi|maize|unga|sukari|chai|maziwa|mkate|nyanya|viazi)\s+(\d+)\s*(kwa|sh|ksh)\s*(\d+(?:\.\d+)?)"""),
        // "10 mandazi kwa 500"
        Regex("""(?i)(\d+)\s+(mandazi|maize|unga|sukari|chai|maziwa|mkate)\s+(kwa|sh|ksh)\s*(\d+(?:\.\d+)?)"""),
        // "Nimeuza nyanya" (without explicit price)
        Regex("""(?i)(nime?uza|sold|nauza)\s+(.+?)(?:\s+(?:kwa|sh|ksh)\s*(\d+(?:\.\d+)?))?"""),
        // Simple: "Sale mandazi 500"
        Regex("""(?i)(sale|sold|uza)\s+(.+?)\s+(\d+(?:\.\d+)?)""")
    )

    // === PURCHASE PATTERNS ===
    private val purchasePatterns = listOf(
        // "Nimenunua unga kwa Sh 200"
        Regex("""(?i)(nimenunua|nilinunua|nimenunulia|nimenunua|bought|nimelog)\s+(.+)\s+(kwa|sh|ksh)\s*(\d+(?:\.\d+)?)"""),
        // "Nimenunua unga mbili kwa 200"
        Regex("""(?i)(nimenunua|bought)\s+(.+?)\s+(\w+)\s+(kwa|sh|ksh)\s*(\d+(?:\.\d+)?)"""),
        // Simple: "Buy unga 200"
        Regex("""(?i)(buy|purchase|nunua)\s+(.+?)\s+(\d+(?:\.\d+)?)""")
    )

    // === EXPENSE PATTERNS ===
    private val expensePatterns = listOf(
        // "Nimetumia Sh 100 kwa usafiri"
        Regex("""(?i)(nimetumia|nimelipa|nimetoa|spent|paid)\s+(?:sh|ksh|kwa)?\s*(\d+(?:\.\d+)?)\s+(?:kwa|for)\s+(.+)"""),
        // "Usafiri 100"
        Regex("""(?i)(usafiri|rent|kodi|stima|umeme|majani|data|bundle)\s+(\d+(?:\.\d+)?)""")
    )

    // === QUERY PATTERNS ===
    private val balancePatterns = listOf(
        Regex("""(?i)(salio|balance|pesa|how much|ngapi|nina|niko)"""),
        Regex("""(?i)(nina\s+pesa|pesa\s+yangu|how\s+much\s+do\s+i\s+have)""")
    )

    private val profitPatterns = listOf(
        Regex("""(?i)(faida|profit|loss|hasara|margin|mapato)"""),
        Regex("""(?i)(how\s+much\s+(did\s+i|have\s+i)\s+(made|earned|profit))""")
    )

    private val stockPatterns = listOf(
        Regex("""(?i)(stock|inventory|baki|remaining|imebaki|bado|gani)"""),
        Regex("""(?i)(how\s+much\s+(stock|left|remaining))""")
    )

    // === ADVICE PATTERNS ===
    private val advicePatterns = listOf(
        Regex("""(?i)(nishauri|advise|nifanye|what\s+should|saidia|msaidizi|help|usaidizi)"""),
        Regex("""(?i)(nifanye\s+nini|what\s+can\s+i|how\s+can\s+i)""")
    )

    // === SUMMARY PATTERNS ===
    private val dailySummaryPatterns = listOf(
        Regex("""(?i)(report|summary|jumla|jumlisho)\s+(ya\s+)?leo"""),
        Regex("""(?i)(today|leo)\s+(report|summary|sales|mauzo)"""),
        Regex("""(?i)(daily\s+report|report\s+ya\s+leo)""")
    )

    private val weeklySummaryPatterns = listOf(
        Regex("""(?i)(report|summary)\s+(ya\s+)?wiki"""),
        Regex("""(?i)(weekly|wiki)\s+(report|summary)""")
    )

    // === HELP / GREETING PATTERNS ===
    private val helpPatterns = listOf(
        Regex("""(?i)(help|saidia|msaidizi|nini|unaweza|what\s+can)"""),
        Regex("""(?i)(jinsi|how\s+to|how\s+do)""")
    )

    private val greetingPatterns = listOf(
        Regex("""(?i)^(habari|hi|hello|hey|sasa|niaje|vipi|mambo|salama)"""),
        Regex("""(?i)^(good\s+(morning|afternoon|evening)|siku\s+njema)""")
    )

    // === CORRECTION PATTERNS ===
    private val correctionPatterns = listOf(
        Regex("""(?i)(hapana|siyo|si|wrong|incorrect|badilisha|rekebisha|change|correct)"""),
        Regex("""(?i)(I\s+meant|alisema|kusema)""")
    )

    /**
     * Classify the intent of user input.
     * Returns IntentResult with type, confidence, and extracted data.
     */
    fun classify(text: String): IntentResult {
        val cleaned = text.trim()
        if (cleaned.isBlank()) {
            return IntentResult(IntentType.UNKNOWN, 0.0)
        }

        // Try sale patterns first (most common)
        for (pattern in salePatterns) {
            val match = pattern.find(cleaned)
            if (match != null) {
                val data = extractSaleData(cleaned, match)
                if (data != null) {
                    return IntentResult(
                        intent = IntentType.SALE,
                        confidence = 0.95,
                        extractedData = mapOf(
                            "item" to data.item,
                            "quantity" to data.quantity.toString(),
                            "amount" to data.amount.toString(),
                            "unitPrice" to data.unitPrice.toString()
                        )
                    )
                }
            }
        }

        // Try purchase patterns
        for (pattern in purchasePatterns) {
            val match = pattern.find(cleaned)
            if (match != null) {
                val data = extractPurchaseData(cleaned, match)
                if (data != null) {
                    return IntentResult(
                        intent = IntentType.PURCHASE,
                        confidence = 0.95,
                        extractedData = mapOf(
                            "item" to data.item,
                            "quantity" to data.quantity.toString(),
                            "amount" to data.amount.toString(),
                            "unitPrice" to data.unitPrice.toString()
                        )
                    )
                }
            }
        }

        // Try expense patterns
        for (pattern in expensePatterns) {
            val match = pattern.find(cleaned)
            if (match != null) {
                val data = extractExpenseData(cleaned, match)
                if (data != null) {
                    return IntentResult(
                        intent = IntentType.EXPENSE,
                        confidence = 0.90,
                        extractedData = data
                    )
                }
            }
        }

        // Try query patterns
        if (profitPatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.PROFIT_QUERY,
                confidence = 0.90
            )
        }

        if (balancePatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.CHECK_BALANCE,
                confidence = 0.90
            )
        }

        if (stockPatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.STOCK_QUERY,
                confidence = 0.85,
                extractedData = mapOf("item" to (SwahiliParser.extractItemName(cleaned) ?: ""))
            )
        }

        // Try summary patterns
        if (dailySummaryPatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.DAILY_SUMMARY,
                confidence = 0.90
            )
        }

        if (weeklySummaryPatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.WEEKLY_SUMMARY,
                confidence = 0.85
            )
        }

        // Try advice patterns
        if (advicePatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.ASK_ADVICE,
                confidence = 0.85,
                needsLLM = true
            )
        }

        // Try greeting patterns
        if (greetingPatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.GREETING,
                confidence = 0.95
            )
        }

        // Try help patterns
        if (helpPatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.HELP,
                confidence = 0.80
            )
        }

        // Try correction patterns
        if (correctionPatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.CORRECTION,
                confidence = 0.80,
                needsLLM = true
            )
        }

        // Fallback: unknown intent
        return IntentResult(
            intent = IntentType.UNKNOWN,
            confidence = 0.0,
            needsLLM = true
        )
    }

    /**
     * Extract sale data from the matched text.
     */
    private fun extractSaleData(text: String, match: MatchResult): SaleData? {
        val item = SwahiliParser.extractItemName(text) ?: return null
        val quantity = SwahiliParser.extractQuantity(text)
        val amount = SwahiliParser.extractPrice(text) ?: return null

        return SaleData(
            item = item,
            quantity = quantity,
            amount = amount
        )
    }

    /**
     * Extract purchase data from the matched text.
     */
    private fun extractPurchaseData(text: String, match: MatchResult): PurchaseData? {
        val item = SwahiliParser.extractItemName(text) ?: return null
        val quantity = SwahiliParser.extractQuantity(text)
        val amount = SwahiliParser.extractPrice(text) ?: return null

        return PurchaseData(
            item = item,
            quantity = quantity,
            amount = amount
        )
    }

    /**
     * Extract expense data from the matched text.
     */
    private fun extractExpenseData(text: String, match: MatchResult): Map<String, String>? {
        val amount = SwahiliParser.extractPrice(text) ?: return null
        val category = SwahiliParser.extractItemName(text) ?: "other"

        return mapOf(
            "category" to category,
            "amount" to amount.toString()
        )
    }
}
