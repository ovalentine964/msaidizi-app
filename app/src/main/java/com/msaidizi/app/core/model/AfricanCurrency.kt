package com.msaidizi.app.core.model

/**
 * Multi-currency support for African markets.
 *
 * Msaidizi serves workers across East, West, and Southern Africa.
 * Each country has its own currency with different denominations
 * and common denominations for informal trade.
 *
 * Design principles:
 * - Store all amounts in the smallest unit (cents/kobo/pesewas)
 * - Display with proper locale formatting
 * - Support M-Pesa style short codes for common amounts
 */
enum class AfricanCurrency(
    val code: String,
    val symbol: String,
    val displayName: String,
    val nameLocal: String,
    val country: String,
    val subunitFactor: Int,  // 1 main unit = N subunits (e.g., 1 KES = 100 cents)
    val commonDenominations: List<Long>,  // Common note/coin values in main units
    val mshortCodes: Map<String, Long> = emptyMap()  // M-Pesa style short codes
) {
    KES(
        code = "KES",
        symbol = "KSh",
        displayName = "Kenyan Shilling",
        nameLocal = "Shilingi ya Kenya",
        country = "Kenya",
        subunitFactor = 100,
        commonDenominations = listOf(50, 100, 200, 500, 1000, 2000, 5000, 10000),
        mshortCodes = mapOf(
            "mia" to 100, "elfu" to 1000, "laki" to 100_000,
            "milioni" to 1_000_000, "ngiri" to 10_000_000
        )
    ),
    NGN(
        code = "NGN",
        symbol = "₦",
        displayName = "Nigerian Naira",
        nameLocal = "Naira ta Nigeria",
        country = "Nigeria",
        subunitFactor = 100,
        commonDenominations = listOf(50, 100, 200, 500, 1000, 2000, 5000),
        mshortCodes = mapOf(
            "naira" to 1, "k" to 1000, "m" to 1_000_000
        )
    ),
    ETB(
        code = "ETB",
        symbol = "Br",
        displayName = "Ethiopian Birr",
        nameLocal = "Birr Itiyoophiyaa",
        country = "Ethiopia",
        subunitFactor = 100,
        commonDenominations = listOf(5, 10, 50, 100, 200, 500, 1000)
    ),
    TZS(
        code = "TZS",
        symbol = "TSh",
        displayName = "Tanzanian Shilling",
        nameLocal = "Shilingi ya Tanzania",
        country = "Tanzania",
        subunitFactor = 100,
        commonDenominations = listOf(500, 1000, 2000, 5000, 10000),
        mshortCodes = mapOf(
            "mia" to 100, "elfu" to 1000, "laki" to 100_000
        )
    ),
    ZAR(
        code = "ZAR",
        symbol = "R",
        displayName = "South African Rand",
        nameLocal = "iRandi yaseNingizimu Afrika",
        country = "South Africa",
        subunitFactor = 100,
        commonDenominations = listOf(10, 20, 50, 100, 200)
    ),
    GHS(
        code = "GHS",
        symbol = "GH₵",
        displayName = "Ghanaian Cedi",
        nameLocal = "Sidi a Ghana",
        country = "Ghana",
        subunitFactor = 100,
        commonDenominations = listOf(1, 2, 5, 10, 20, 50, 100, 200)
    );

    /**
     * Format an amount (in subunits) for display.
     * Example: 50000 cents → "KSh 500" or "KSh 500.00"
     */
    fun formatAmount(amountSubunits: Long, showDecimals: Boolean = false): String {
        val mainAmount = amountSubunits.toDouble() / subunitFactor
        return if (showDecimals) {
            "$symbol ${"%.2f".format(mainAmount)}"
        } else {
            "$symbol ${"%,.0f".format(mainAmount)}"
        }
    }

    /**
     * Format amount for speech (Swahili-friendly).
     * Example: 50000 → "shilingi mia tano"
     */
    fun formatForSpeech(amountSubunits: Long): String {
        val mainAmount = amountSubunits / subunitFactor
        return when {
            mainAmount >= 1_000_000 -> {
                val millions = mainAmount / 1_000_000
                val remainder = mainAmount % 1_000_000
                if (remainder > 0) "$nameLocal milioni $millions na ${formatSmallAmount(remainder)}"
                else "$nameLocal milioni $millions"
            }
            mainAmount >= 100_000 -> {
                val laki = mainAmount / 100_000
                val remainder = mainAmount % 100_000
                if (remainder > 0) "$nameLocal laki $laki na ${formatSmallAmount(remainder)}"
                else "$nameLocal laki $laki"
            }
            mainAmount >= 1_000 -> {
                val elfu = mainAmount / 1_000
                val remainder = mainAmount % 1_000
                if (remainder > 0) "$nameLocal elfu $elfu na ${formatSmallAmount(remainder)}"
                else "$nameLocal elfu $elfu"
            }
            else -> "$nameLocal $mainAmount"
        }
    }

    private fun formatSmallAmount(amount: Long): String {
        return when {
            amount >= 1000 -> "${amount / 1000} elfu ${amount % 1000}"
            amount >= 100 -> "mia ${amount / 100}${if (amount % 100 > 0) " na ${amount % 100}" else ""}"
            else -> amount.toString()
        }
    }

    companion object {
        /**
         * Get currency by ISO code.
         */
        fun fromCode(code: String): AfricanCurrency? {
            return entries.find { it.code.equals(code, ignoreCase = true) }
        }

        /**
         * Get currency by country name.
         */
        fun forCountry(country: String): AfricanCurrency? {
            return entries.find { it.country.equals(country, ignoreCase = true) }
        }

        /**
         * Parse an amount string with currency symbol.
         * Example: "KSh 500" → (50000, KES)
         */
        fun parseAmount(text: String): Pair<Long, AfricanCurrency>? {
            for (currency in entries) {
                if (text.contains(currency.symbol, ignoreCase = true)) {
                    val numberStr = text.replace(currency.symbol, "")
                        .replace(",", "")
                        .trim()
                    val amount = numberStr.toDoubleOrNull() ?: continue
                    return Pair((amount * currency.subunitFactor).toLong(), currency)
                }
            }
            return null
        }
    }
}

/**
 * Currency preference for a worker.
 */
data class CurrencyPreference(
    val primaryCurrency: AfricanCurrency,
    val secondaryCurrency: AfricanCurrency? = null,  // For cross-border traders
    val exchangeRate: Double = 1.0  // Rate from primary to secondary
)
