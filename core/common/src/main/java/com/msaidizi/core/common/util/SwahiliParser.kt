package com.msaidizi.core.common.util

/**
 * Swahili text parsing utilities for voice input processing.
 * Handles common Swahili number words, transaction patterns, and
 * Sheng (urban slang) normalization.
 */
object SwahiliParser {

    /**
     * Swahili number word → numeric value mapping.
     */
    private val NUMBER_WORDS = mapOf(
        // Basic numbers
        "sifuri" to 0, "moja" to 1, "mbili" to 2, "tatu" to 3, "nne" to 4,
        "tano" to 5, "sita" to 6, "saba" to 7, "nane" to 8, "tisa" to 9,
        "kumi" to 10,

        // Teens
        "kumi na moja" to 11, "kumi na mbili" to 12, "kumi na tatu" to 13,
        "kumi na nne" to 14, "kumi na tano" to 15, "kumi na sita" to 16,
        "kumi na saba" to 17, "kumi na nane" to 18, "kumi na tisa" to 19,

        // Tens
        "ishirini" to 20, "thelathini" to 30, "arobaini" to 40,
        "hamsini" to 50, "sitini" to 60, "sabini" to 70,
        "themanini" to 80, "tisini" to 90,

        // Hundreds
        "mia" to 100, "mia moja" to 100, "mia mbili" to 200,
        "mia tatu" to 300, "mia nne" to 400, "mia tano" to 500,
        "mia sita" to 600, "mia saba" to 700, "mia nane" to 800,
        "mia tisa" to 900,

        // Thousands
        "elfu" to 1_000, "elfu moja" to 1_000, "elfu mbili" to 2_000,
        "elfu tatu" to 3_000, "elfu nne" to 4_000, "elfu tano" to 5_000,

        // Common amounts
        "nusu" to 0.5, "robo" to 0.25, "theluthi" to 0.33
    )

    /**
     * Transaction type keywords.
     */
    private val SALE_KEYWORDS = listOf(
        "nimeuziwa", "nimeuza", "nimeuzia", "wameninunulia",
        "imenunuliwa", "mauzo", "nauza", "nimeshauza",
        "nimetokeza", "walinunua"
    )

    private val PURCHASE_KEYWORDS = listOf(
        "nimenunua", "nimetumia kununua", "nimepata",
        "nimetokeza kununua", "nimelipia"
    )

    private val EXPENSE_KEYWORDS = listOf(
        "nimetumia", "nimelipia", "gharama", "matumizi",
        "nimetumia kwa", "nimelipia kwa"
    )

    /**
     * Parse a Swahili number expression to a numeric value.
     * Handles compound expressions like "elfu mbili na mia tano" (2500).
     */
    fun parseNumber(text: String): Double? {
        val cleaned = text.lowercase().trim()

        // Try direct numeric parse
        cleaned.toDoubleOrNull()?.let { return it }

        // Try simple lookup
        NUMBER_WORDS[cleaned]?.let { return it.toDouble() }

        // Parse compound expressions
        var total = 0.0
        var remaining = cleaned

        // Process from largest to smallest multiplier
        val multipliers = listOf(
            "milioni" to 1_000_000.0,
            "laki" to 100_000.0,
            "elfu" to 1_000.0,
            "mia" to 100.0
        )

        for ((prefix, multiplier) in multipliers) {
            if (remaining.contains(prefix)) {
                val idx = remaining.indexOf(prefix)
                val before = remaining.substring(0, idx).trim()
                val number = NUMBER_WORDS[before] ?: 1
                total += number * multiplier
                remaining = remaining.substring(idx + prefix.length).trim()
                if (remaining.startsWith("na")) {
                    remaining = remaining.removePrefix("na").trim()
                }
            }
        }

        // If we found multipliers, return total + any remaining
        if (total > 0) {
            NUMBER_WORDS[remaining]?.let { total += it }
            return total
        }

        // Try parsing "kumi na tano" style (15)
        if (remaining.contains(" na ")) {
            val parts = remaining.split(" na ").map { it.trim() }
            var sum = 0.0
            for (part in parts) {
                NUMBER_WORDS[part]?.let { sum += it }
            }
            if (sum > 0) return sum
        }

        return null
    }

    /**
     * Detect transaction type from voice input.
     */
    fun detectTransactionType(text: String): TransactionTypeHint? {
        val lower = text.lowercase()
        return when {
            SALE_KEYWORDS.any { lower.contains(it) } -> TransactionTypeHint.SALE
            PURCHASE_KEYWORDS.any { lower.contains(it) } -> TransactionTypeHint.PURCHASE
            EXPENSE_KEYWORDS.any { lower.contains(it) } -> TransactionTypeHint.EXPENSE
            else -> null
        }
    }

    /**
     * Extract item name from voice input.
     * "Nimeuziwa mandazi kumi" → "mandazi"
     */
    fun extractItemName(text: String): String? {
        val lower = text.lowercase()
        // Remove transaction keywords
        var cleaned = lower
        for (keyword in SALE_KEYWORDS + PURCHASE_KEYWORDS + EXPENSE_KEYWORDS) {
            cleaned = cleaned.replace(keyword, "")
        }
        // Remove numbers
        cleaned = cleaned.replace(Regex("\\b\\d+\\b"), "")
        for ((word, _) in NUMBER_WORDS) {
            cleaned = cleaned.replace(word, "")
        }
        // Clean up whitespace
        val words = cleaned.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.firstOrNull()
    }

    /**
     * Normalize Sheng (urban slang) to standard Swahili.
     * Helps with intent classification when workers use Sheng.
     */
    fun normalizeSheng(text: String): String {
        var normalized = text.lowercase()
        val shengMap = mapOf(
            "nimebuy" to "nimenunua",
            "nimesell" to "nimeuza",
            "nimepiga" to "nimeuza",
            "nimemove" to "nimeuza",
            "nimestaki" to "nimetaka",
            "nimefanya" to "nimetumia",
            "niko" to "nipo",
            "aje" to "vipi",
            "niaje" to "habari",
            "sasa" to "habari",
            "poa" to "nzuri",
            "mbogi" to "rafiki",
            "ndege" to "simu",
            "guoko" to "pesa",
            "munde" to "pesa",
            "ngwai" to "bangi",
            "kanyaga" to "piga",
            "bwana" to "",
            "msee" to "mtu",
            "mse" to "mtu",
            "vile" to "kama",
            "niko" to "nipo",
            "nikiwa" to "nipatapo"
        )

        for ((sheng, standard) in shengMap) {
            normalized = normalized.replace(sheng, standard)
        }

        return normalized.trim()
    }

    enum class TransactionTypeHint {
        SALE, PURCHASE, EXPENSE
    }
}
