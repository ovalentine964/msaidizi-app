package com.msaidizi.app.core.validation

import com.msaidizi.app.core.model.AfricanCurrency
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Currency display formatter for Msaidizi.
 *
 * Formats money for humans who need to TRUST what they see.
 *
 * Rules:
 * - KES 1,000 (not KES 1000.000000001 — floating point drift)
 * - Show "≈" for estimates
 * - Show confidence when uncertain
 * - Handle edge cases: zero, very large, very small
 * - No scientific notation ever
 *
 * Target audience: mama mboga on a $50 phone reading in Swahili.
 * Every format must be instantly readable.
 */
object CurrencyFormatter {

    private val KES_LOCALE = Locale("sw", "KE") // Swahili, Kenya

    /** Integer format: KES 1,000 */
    private val integerFormat = DecimalFormat("#,###", DecimalFormatSymbols(KES_LOCALE).apply {
        groupingSeparator = ','
        decimalSeparator = '.'
    })

    /** Decimal format: KES 1,000.50 */
    private val decimalFormat = DecimalFormat("#,###.00", DecimalFormatSymbols(KES_LOCALE).apply {
        groupingSeparator = ','
        decimalSeparator = '.'
    })

    // =====================================================================
    // PRIMARY FORMATTING
    // =====================================================================

    /**
     * Format a KES amount for display.
     * Default: no decimals (KES 1,000 not KES 1,000.00)
     *
     * @param amount Amount in KES (main units)
     * @param showDecimals Whether to show .00 (for M-Pesa consistency)
     * @return Formatted string like "KES 1,000"
     */
    fun format(amount: Double, showDecimals: Boolean = false): String {
        val safe = sanitizeAmount(amount)

        return when {
            safe == 0.0 -> "KES 0"
            showDecimals -> "KES ${decimalFormat.format(safe)}"
            else -> "KES ${integerFormat.format(Math.round(safe))}"
        }
    }

    /**
     * Format using the currency's symbol.
     * Example: format(1000.0, AfricanCurrency.KES) → "KSh 1,000"
     */
    fun format(amount: Double, currency: AfricanCurrency, showDecimals: Boolean = false): String {
        val safe = sanitizeAmount(amount)

        return when {
            safe == 0.0 -> "${currency.symbol} 0"
            showDecimals -> "${currency.symbol} ${decimalFormat.format(safe)}"
            else -> "${currency.symbol} ${integerFormat.format(Math.round(safe))}"
        }
    }

    /**
     * Format without currency prefix (for inline use).
     * Example: 1500 → "1,500"
     */
    fun formatNumber(amount: Double): String {
        val safe = sanitizeAmount(amount)
        return integerFormat.format(Math.round(safe))
    }

    // =====================================================================
    // ESTIMATE FORMATTING (with ranges and confidence)
    // =====================================================================

    /**
     * Format an estimate with range.
     * Example: formatEstimate(200.0, 400.0) → "≈ KES 200-400"
     *
     * @param low Low end of estimate range
     * @param high High end of estimate range
     */
    fun formatEstimate(low: Double, high: Double): String {
        val safeLow = sanitizeAmount(low)
        val safeHigh = sanitizeAmount(high)

        return when {
            safeLow == safeHigh -> "≈ KES ${integerFormat.format(Math.round(safeLow))}"
            else -> "≈ KES ${integerFormat.format(Math.round(safeLow))}-${integerFormat.format(Math.round(safeHigh))}"
        }
    }

    /**
     * Format an estimate with confidence level.
     * Example: formatWithConfidence(200.0, 0.9) → "KES 200 (90% uhakika)"
     *
     * @param amount Estimated amount
     * @param confidence Confidence level (0.0-1.0)
     */
    fun formatWithConfidence(amount: Double, confidence: Double): String {
        val safe = sanitizeAmount(amount)
        val confPct = (confidence.coerceIn(0.0, 1.0) * 100).toInt()

        return when {
            confPct >= 95 -> "KES ${integerFormat.format(Math.round(safe))}"
            confPct >= 70 -> "KES ${integerFormat.format(Math.round(safe))} ($confPct% uhakika)"
            confPct >= 50 -> "≈ KES ${integerFormat.format(Math.round(safe))} ($confPct% uhakika)"
            else -> "≈ KES ${integerFormat.format(Math.round(safe))} (uhakika mdogo: $confPct%)"
        }
    }

    /**
     * Format a range with confidence.
     * Example: formatEstimateWithConfidence(200, 400, 0.85) → "≈ KES 200-400 (85% uhakika)"
     */
    fun formatEstimateWithConfidence(
        low: Double,
        high: Double,
        confidence: Double
    ): String {
        val safeLow = sanitizeAmount(low)
        val safeHigh = sanitizeAmount(high)
        val confPct = (confidence.coerceIn(0.0, 1.0) * 100).toInt()

        val range = when {
            safeLow == safeHigh -> "KES ${integerFormat.format(Math.round(safeLow))}"
            else -> "KES ${integerFormat.format(Math.round(safeLow))}-${integerFormat.format(Math.round(safeHigh))}"
        }

        return when {
            confPct >= 90 -> "≈ $range"
            else -> "≈ $range ($confPct% uhakika)"
        }
    }

    // =====================================================================
    // PERCENTAGE FORMATTING
    // =====================================================================

    /**
     * Format a percentage for display.
     * Example: 0.15 → "15%"
     */
    fun formatPercentage(value: Double, decimals: Int = 0): String {
        val pct = value.coerceIn(0.0, 1.0) * 100
        return when (decimals) {
            0 -> "${Math.round(pct)}%"
            1 -> "${"%.1f".format(pct)}%"
            else -> "${"%.2f".format(pct)}%"
        }
    }

    /**
     * Format a percentage change.
     * Example: 0.15 → "+15%", -0.05 → "-5%"
     */
    fun formatPercentageChange(value: Double): String {
        val pct = value * 100
        val sign = if (pct >= 0) "+" else ""
        return "$sign${Math.round(pct)}%"
    }

    // =====================================================================
    // PROGRESS FORMATTING
    // =====================================================================

    /**
     * Format progress toward a goal.
     * Example: formatProgress(750.0, 1000.0) → "KES 750 ya KES 1,000 (75%)"
     */
    fun formatProgress(current: Double, target: Double): String {
        val safeCurrent = sanitizeAmount(current)
        val safeTarget = sanitizeAmount(target)

        val pct = if (safeTarget > 0) {
            ((safeCurrent / safeTarget) * 100).coerceIn(0.0, 100.0)
        } else {
            0.0
        }

        return "KES ${integerFormat.format(Math.round(safeCurrent))} ya " +
            "KES ${integerFormat.format(Math.round(safeTarget))} " +
            "(${Math.round(pct)}%)"
    }

    /**
     * Format a balance.
     * Special handling for zero and negative.
     */
    fun formatBalance(balance: Double): String {
        val safe = sanitizeAmount(balance)

        return when {
            safe == 0.0 -> "Salio: KES 0"
            safe < 0 -> "Salio: -KES ${integerFormat.format(Math.round(Math.abs(safe)))}"
            else -> "Salio: KES ${integerFormat.format(Math.round(safe))}"
        }
    }

    // =====================================================================
    // PROFIT / LOSS FORMATTING
    // =====================================================================

    /**
     * Format profit or loss.
     * Example: formatProfit(500.0) → "Faida: KES 500"
     * Example: formatProfit(-200.0) → "Hasara: KES 200"
     */
    fun formatProfit(amount: Double): String {
        val safe = sanitizeAmount(amount)

        return when {
            safe > 0 -> "Faida: KES ${integerFormat.format(Math.round(safe))}"
            safe < 0 -> "Hasara: KES ${integerFormat.format(Math.round(Math.abs(safe)))}"
            else -> "Faida: KES 0"
        }
    }

    /**
     * Format profit range estimate.
     * Example: "Faida ≈ KES 200-400"
     */
    fun formatProfitEstimate(low: Double, high: Double): String {
        val safeLow = sanitizeAmount(low)
        val safeHigh = sanitizeAmount(high)

        return when {
            safeLow >= 0 && safeHigh >= 0 ->
                "Faida ≈ KES ${integerFormat.format(Math.round(safeLow))}-${integerFormat.format(Math.round(safeHigh))}"
            safeLow < 0 && safeHigh < 0 ->
                "Hasara ≈ KES ${integerFormat.format(Math.round(Math.abs(safeHigh)))}-${integerFormat.format(Math.round(Math.abs(safeLow)))}"
            else ->
                "Faida ≈ KES ${integerFormat.format(Math.round(safeLow))}-${integerFormat.format(Math.round(safeHigh))}"
        }
    }

    // =====================================================================
    // LARGE NUMBER FORMATTING
    // =====================================================================

    /**
     * Format large numbers for readability.
     * Example: 1500000 → "KES 1.5M", 150000 → "KES 150K"
     */
    fun formatCompact(amount: Double): String {
        val safe = sanitizeAmount(amount)

        return when {
            safe >= 1_000_000_000 -> "KES ${"%.1f".format(safe / 1_000_000_000)}B"
            safe >= 1_000_000 -> "KES ${"%.1f".format(safe / 1_000_000)}M"
            safe >= 1_000 -> "KES ${"%.1f".format(safe / 1_000)}K"
            safe == 0.0 -> "KES 0"
            else -> "KES ${integerFormat.format(Math.round(safe))}"
        }
    }

    /**
     * Format for speech (TTS-friendly).
     * Example: 1500 → "KES elfu moja na mia tano"
     */
    fun formatForSpeech(amount: Double, currency: AfricanCurrency = AfricanCurrency.KES): String {
        val safe = sanitizeAmount(amount)
        return currency.formatForSpeech((safe * currency.subunitFactor).toLong())
    }

    // =====================================================================
    // SANITIZATION
    // =====================================================================

    /**
     * Sanitize an amount for display.
     * Handles floating point drift, NaN, Infinity.
     */
    private fun sanitizeAmount(amount: Double): Double {
        if (amount.isNaN() || amount.isInfinite()) return 0.0

        // Round to 2 decimal places to kill floating point drift
        // e.g., 1000.000000001 → 1000.0
        val rounded = Math.round(amount * 100.0) / 100.0

        // Final safety: if still weird after rounding, return 0
        if (rounded.isNaN() || rounded.isInfinite()) return 0.0

        return rounded
    }

    // =====================================================================
    // FORMATTED OUTPUT FOR COMMON UI PATTERNS
    // =====================================================================

    /**
     * Format a transaction line for display.
     * Example: "🟢 +KES 500 (M-Pesa, 2:30 PM)"
     */
    fun formatTransactionLine(
        amount: Double,
        type: String,       // "SALE", "PURCHASE", etc.
        method: String,     // "mpesa", "cash"
        time: String        // "2:30 PM"
    ): String {
        val safe = sanitizeAmount(amount)
        val formatted = integerFormat.format(Math.round(safe))

        val emoji = when (type) {
            "SALE", "DEPOSIT", "REFUND" -> "🟢"
            "PURCHASE", "EXPENSE", "FEE", "WITHDRAWAL" -> "🔴"
            else -> "⚪"
        }

        val sign = when (type) {
            "SALE", "DEPOSIT", "REFUND" -> "+"
            else -> "-"
        }

        val methodLabel = when (method.lowercase()) {
            "mpesa" -> "M-Pesa"
            "cash" -> "Pesa taslimu"
            "credit" -> "Mkopo"
            else -> method
        }

        return "$emoji ${sign}KES $formatted ($methodLabel, $time)"
    }

    /**
     * Format daily summary.
     * Example:
     * "Leo: Umeingiza KES 2,500 | Umepoteza KES 1,200 | Faida: KES 1,300"
     */
    fun formatDailySummary(
        income: Double,
        expenses: Double,
        profit: Double
    ): String {
        val incomeFmt = formatNumber(income)
        val expensesFmt = formatNumber(expenses)
        val profitFmt = formatProfit(profit)

        return "Leo: Umeingiza KES $incomeFmt | Umepoteza KES $expensesFmt | $profitFmt"
    }

    /**
     * Format M-Pesa balance display.
     * Example: "Salio la M-Pesa: KES 12,500"
     */
    fun formatMpesaBalance(balance: Double): String {
        val safe = sanitizeAmount(balance)
        return "Salio la M-Pesa: KES ${integerFormat.format(Math.round(safe))}"
    }
}
