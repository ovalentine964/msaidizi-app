package com.msaidizi.core.common.util

/**
 * Currency formatting utilities for East African currencies.
 * Handles KSh (Kenya), TSh (Tanzania), USh (Uganda).
 */
object CurrencyFormatter {

    /**
     * Format amount as KSh (Kenya Shillings).
     * Examples: "KSh 500", "KSh 1,200", "KSh 10,000"
     */
    fun formatKsh(amount: Double): String {
        val intAmount = amount.toLong()
        return when {
            intAmount >= 1_000_000 -> "KSh ${formatWithCommas(intAmount)}"
            intAmount >= 1_000 -> "KSh ${formatWithCommas(intAmount)}"
            intAmount == amount -> "KSh $intAmount"
            else -> "KSh ${String.format("%.2f", amount)}"
        }
    }

    /**
     * Format amount in Swahili verbal style.
     * Examples: "mia tano" (500), "elfu moja" (1000), "laki moja" (100,000)
     */
    fun formatSwahili(amount: Double): String {
        val intAmount = amount.toLong()
        return when {
            intAmount >= 1_000_000 -> {
                val millions = intAmount / 1_000_000
                val remainder = intAmount % 1_000_000
                if (remainder == 0L) "milioni $millions"
                else "milioni $millions na ${formatSwahili(remainder.toDouble())}"
            }
            intAmount >= 100_000 -> {
                val lakhs = intAmount / 100_000
                val remainder = intAmount % 100_000
                if (remainder == 0L) "laki $lakhs"
                else "laki $lakhs na ${formatSwahili(remainder.toDouble())}"
            }
            intAmount >= 1_000 -> {
                val thousands = intAmount / 1_000
                val remainder = intAmount % 1_000
                if (remainder == 0L) "elfu $thousands"
                else "elfu $thousands na ${formatSwahili(remainder.toDouble())}"
            }
            intAmount >= 100 -> {
                val hundreds = intAmount / 100
                val remainder = intAmount % 100
                if (remainder == 0L) "mia $hundreds"
                else "mia $hundreds na ${formatSwahili(remainder.toDouble())}"
            }
            else -> intAmount.toString()
        }
    }

    /**
     * Parse Swahili verbal amount to numeric.
     * Examples: "mia mbili" → 200, "elfu tatu" → 3000
     */
    fun parseSwahili(text: String): Double? {
        val cleaned = text.lowercase().trim()

        // Try direct numeric parse first
        cleaned.toDoubleOrNull()?.let { return it }

        // Swahili number words
        val swahiliNumbers = mapOf(
            "moja" to 1, "mbili" to 2, "tatu" to 3, "nne" to 4, "tano" to 5,
            "sita" to 6, "saba" to 7, "nane" to 8, "tisa" to 9, "kumi" to 10,
            "kumi na moja" to 11, "kumi na mbili" to 12, "kumi na tatu" to 13,
            "kumi na nne" to 14, "kumi na tano" to 15, "kumi na sita" to 16,
            "kumi na saba" to 17, "kumi na nane" to 18, "kumi na tisa" to 19,
            "ishirini" to 20, "thelathini" to 30, "arobaini" to 40,
            "hamsini" to 50, "sitini" to 60, "sabini" to 70,
            "themanini" to 80, "tisini" to 90
        )

        val multipliers = mapOf(
            "mia" to 100,
            "elfu" to 1_000,
            "laki" to 100_000,
            "milioni" to 1_000_000
        )

        // Parse "mia mbili" style
        for ((prefix, multiplier) in multipliers) {
            if (cleaned.startsWith(prefix)) {
                val rest = cleaned.removePrefix(prefix).trim()
                val number = swahiliNumbers[rest] ?: 1
                return (number * multiplier).toDouble()
            }
        }

        // Parse "elfu tatu na mia mbili" style (compound)
        var total = 0.0
        var remaining = cleaned
        for ((prefix, multiplier) in multipliers) {
            val idx = remaining.indexOf(prefix)
            if (idx >= 0) {
                val before = remaining.substring(0, idx).trim()
                val number = swahiliNumbers[before] ?: 1
                total += number * multiplier
                remaining = remaining.substring(idx + prefix.length).trim()
                if (remaining.startsWith("na")) remaining = remaining.removePrefix("na").trim()
            }
        }

        return if (total > 0) total else null
    }

    /**
     * Format with comma separators.
     */
    private fun formatWithCommas(number: Long): String {
        return String.format("%,d", number)
    }
}
