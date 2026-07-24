package com.msaidizi.app.superagent.financial

import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Tax Tracker — KRA compliance and deductible expense tracking.
 *
 * Helps informal workers in Kenya track their tax obligations and
 * deductible expenses. Supports the most common tax types for small
 * businesses and informal traders.
 *
 * ## Supported Tax Types
 * 1. **Turnover Tax (TOT):** 1% of gross sales for businesses under KSh 25M
 * 2. **Residential Rental Income Tax (RRIT):** 7.5% for rental income KSh 288K-15M
 * 3. **VAT:** 16% for registered businesses (threshold KSh 5M)
 *
 * ## Deductible Expenses
 * Under Kenyan tax law, business expenses are deductible:
 * - Stock purchases (cost of goods sold)
 * - Transport costs (to/from market)
 * - Market fees and licenses
 * - Phone/communication costs (business portion)
 * - Packaging materials
 *
 * ## Academic Foundations
 * - **LAW 301 (Tax Law):** Kenyan Income Tax Act, VAT Act
 * - **ACC 201 (Accounting):** Expense classification, tax compliance
 * - **ECO 206 (Microfinance):** Financial inclusion, formalization
 *
 * @author Msaidizi Financial Team
 */
class TaxTracker {

    companion object {
        private const val TAG = "TaxTracker"

        // KRA Tax Rates
        private const val TOT_RATE = 0.01           // 1% Turnover Tax
        private const val TOT_THRESHOLD = 25_000_000.0 // KSh 25M annual
        private const val RRIT_RATE = 0.075         // 7.5% Rental Income Tax
        private const val VAT_RATE = 0.16           // 16% VAT
        private const val VAT_THRESHOLD = 5_000_000.0 // KSh 5M annual

        // Deductible expense categories
        private val DEDUCTIBLE_CATEGORIES = setOf(
            "stock", "purchase", "transport", "market_fee",
            "license", "phone", "packaging", "rent"
        )

        // Seconds in a day
        private const val SECONDS_PER_DAY = 86_400L
    }

    // ═══════════════════════════════════════════════════════════════
    // TAX CALCULATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calculate tax liability for a period.
     *
     * @param transactions Transactions for the period
     * @param periodStart Start of period (Unix timestamp)
     * @param periodEnd End of period (Unix timestamp)
     * @param taxObligation Type of tax to calculate
     * @return [TaxReport] with tax details
     */
    fun calculateTax(
        transactions: List<Transaction>,
        periodStart: Long,
        periodEnd: Long,
        taxObligation: TaxObligation = TaxObligation.TURNOVER_TAX
    ): TaxReport {
        val periodTransactions = transactions.filter {
            it.createdAt in periodStart..periodEnd
        }

        if (periodTransactions.isEmpty()) {
            return TaxReport(
                period = formatPeriod(periodStart, periodEnd),
                totalRevenue = 0.0,
                totalExpenses = 0.0,
                estimatedTax = 0.0,
                deductibleExpenses = emptyMap(),
                obligation = taxObligation,
                dueDate = calculateDueDate(periodEnd, taxObligation),
                message = "Hakuna miamala kwa kipindi hiki."
            )
        }

        // Calculate revenue
        val totalRevenue = periodTransactions
            .filter { it.type == TransactionType.SALE }
            .sumOf { it.totalAmount }

        // Calculate deductible expenses
        val deductibleExpenses = calculateDeductibleExpenses(periodTransactions)
        val totalDeductible = deductibleExpenses.values.sum()

        // Calculate non-deductible expenses
        val totalExpenses = periodTransactions
            .filter { it.isExpense }
            .sumOf { it.totalAmount }

        // Calculate tax based on obligation type
        val estimatedTax = when (taxObligation) {
            TaxObligation.TURNOVER_TAX -> calculateTOT(totalRevenue)
            TaxObligation.RENTAL_INCOME -> calculateRRIT(totalRevenue)
            TaxObligation.VAT -> calculateVAT(totalRevenue)
            else -> calculateTOT(totalRevenue) // Default to TOT
        }

        val dueDate = calculateDueDate(periodEnd, taxObligation)

        val message = buildTaxMessage(
            taxObligation, totalRevenue, totalExpenses,
            totalDeductible, estimatedTax, dueDate
        )

        return TaxReport(
            period = formatPeriod(periodStart, periodEnd),
            totalRevenue = totalRevenue,
            totalExpenses = totalExpenses,
            estimatedTax = estimatedTax,
            deductibleExpenses = deductibleExpenses,
            obligation = taxObligation,
            dueDate = dueDate,
            message = message
        )
    }

    /**
     * Calculate deductible expenses from transactions.
     *
     * @return Map of expense category → total deductible amount
     */
    private fun calculateDeductibleExpenses(
        transactions: List<Transaction>
    ): Map<String, Double> {
        return transactions
            .filter { it.isExpense && isDeductible(it) }
            .groupBy { categorizeForTax(it) }
            .mapValues { (_, txns) -> txns.sumOf { it.totalAmount } }
    }

    /**
     * Check if a transaction is tax-deductible.
     */
    private fun isDeductible(transaction: Transaction): Boolean {
        val category = transaction.category.lowercase()
        val item = transaction.item.lowercase()

        return DEDUCTIBLE_CATEGORIES.any { deductible ->
            category.contains(deductible) || item.contains(deductible)
        }
    }

    /**
     * Categorize a transaction for tax purposes.
     */
    private fun categorizeForTax(transaction: Transaction): String {
        val category = transaction.category.lowercase()
        val item = transaction.item.lowercase()

        return when {
            category.contains("stock") || category.contains("purchase") -> "Gharama ya bidhaa"
            category.contains("transport") || item.contains("matatu") -> "Usafiri"
            category.contains("market") || item.contains("soko") -> "Ada ya soko"
            category.contains("license") || item.contains("leseni") -> "Leseni"
            category.contains("phone") || item.contains("simu") -> "Mawasiliano"
            category.contains("packaging") || item.contains("mfuko") -> "Ufungashaji"
            category.contains("rent") || item.contains("kodi") -> "Kodi"
            else -> "Matumizi mengine"
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TAX TYPE CALCULATIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calculate Turnover Tax (TOT).
     * 1% of gross sales for businesses under KSh 25M annual turnover.
     */
    private fun calculateTOT(grossRevenue: Double): Double {
        return if (grossRevenue <= TOT_THRESHOLD) {
            grossRevenue * TOT_RATE
        } else {
            0.0 // Not eligible for TOT — must register for VAT
        }
    }

    /**
     * Calculate Residential Rental Income Tax (RRIT).
     * 7.5% for rental income between KSh 288,000 and KSh 15,000,000.
     */
    private fun calculateRRIT(rentalIncome: Double): Double {
        val annualIncome = rentalIncome * 12 // Assume monthly
        return when {
            annualIncome < 288_000 -> 0.0
            annualIncome <= 15_000_000 -> annualIncome * RRIT_RATE / 12
            else -> 0.0 // Must file normal income tax
        }
    }

    /**
     * Calculate VAT.
     * 16% for registered businesses above KSh 5M threshold.
     */
    private fun calculateVAT(revenue: Double): Double {
        return if (revenue >= VAT_THRESHOLD) {
            revenue * VAT_RATE
        } else {
            0.0 // Below VAT threshold
        }
    }

    /**
     * Calculate tax due date based on period end and obligation type.
     */
    private fun calculateDueDate(periodEnd: Long, obligation: TaxObligation): Long {
        return when (obligation) {
            TaxObligation.TURNOVER_TAX -> {
                // TOT is due by 20th of the following month
                val cal = Calendar.getInstance()
                cal.timeInMillis = periodEnd * 1000
                cal.add(Calendar.MONTH, 1)
                cal.set(Calendar.DAY_OF_MONTH, 20)
                cal.timeInMillis / 1000
            }
            TaxObligation.VAT -> {
                // VAT is due by 20th of the following month
                val cal = Calendar.getInstance()
                cal.timeInMillis = periodEnd * 1000
                cal.add(Calendar.MONTH, 1)
                cal.set(Calendar.DAY_OF_MONTH, 20)
                cal.timeInMillis / 1000
            }
            else -> {
                // Default: 30 days after period end
                periodEnd + TimeUnit.DAYS.toSeconds(30)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TAX OPTIMIZATION TIPS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate tax optimization tips based on transaction history.
     *
     * @param transactions Recent transactions
     * @return List of actionable tips in Swahili
     */
    fun getOptimizationTips(transactions: List<Transaction>): List<String> {
        val tips = mutableListOf<String>()

        // Check if tracking deductible expenses
        val totalExpenses = transactions
            .filter { it.isExpense }
            .sumOf { it.totalAmount }
        val deductibleExpenses = calculateDeductibleExpenses(transactions)
        val deductibleTotal = deductibleExpenses.values.sum()

        if (totalExpenses > 0 && deductibleTotal < totalExpenses * 0.5) {
            tips.add("💡 Unaweza kukata gharama zaidi! Hakikisha unarekodi " +
                "gharama zote za biashara: usafiri, ada za soko, mawasiliano.")
        }

        // Check if eligible for TOT
        val revenue = transactions
            .filter { it.type == TransactionType.SALE }
            .sumOf { it.totalAmount }
        if (revenue > 0 && revenue < TOT_THRESHOLD) {
            tips.add("📋 Biashara yako inastahili Turnover Tax (TOT) — 1% ya mauzo. " +
                "Ni rahisi kulipa na haikuchukui muda mwingi.")
        }

        // Check for receipt keeping
        val hasReceipts = transactions.any { it.confidence > 0.9f }
        if (!hasReceipts) {
            tips.add("🧾 Hifadhi risiti za ununuzi! Zitakusaidia kuthibitisha " +
                "gharama za biashara kwa KRA.")
        }

        return tips
    }

    // ═══════════════════════════════════════════════════════════════
    // MESSAGE GENERATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build a tax report message in Swahili.
     */
    private fun buildTaxMessage(
        obligation: TaxObligation,
        revenue: Double,
        expenses: Double,
        deductible: Double,
        tax: Double,
        dueDate: Long
    ): String {
        val obligationName = when (obligation) {
            TaxObligation.TURNOVER_TAX -> "Turnover Tax (TOT)"
            TaxObligation.RENTAL_INCOME -> "Rental Income Tax"
            TaxObligation.VAT -> "VAT"
            else -> "Kodi"
        }

        val daysUntilDue = ((dueDate - System.currentTimeMillis() / 1000) / SECONDS_PER_DAY).toInt()

        return buildString {
            append("🏛️ Ripoti ya $obligationName:\n\n")
            append("Mauzo: KSh ${formatAmount(revenue)}\n")
            append("Gharama jumla: KSh ${formatAmount(expenses)}\n")
            append("Gharama zinazokubalika: KSh ${formatAmount(deductible)}\n")
            append("Kodi inayokadiriwa: KSh ${formatAmount(tax)}\n\n")

            when {
                daysUntilDue <= 0 -> {
                    append("🚨 Muda wa kulipa umepita! Lipa haraka ili kuepuka faini.")
                }
                daysUntilDue <= 7 -> {
                    append("⚠️ Muda wa kulipa ni siku $daysUntilDue. Lipa kabla ya muda!")
                }
                else -> {
                    append("📅 Muda wa kulipa: siku $daysUntilDue zijazo.")
                }
            }

            if (deductible > 0) {
                append("\n\n💡 Gharama zako za biashara (KSh ${formatAmount(deductible)}) ")
                append("zinaweza kupunguza kodi yako.")
            }
        }
    }

    /**
     * Format period for display.
     */
    private fun formatPeriod(start: Long, end: Long): String {
        val days = ((end - start) / SECONDS_PER_DAY).toInt()
        return when {
            days <= 1 -> "Leo"
            days <= 7 -> "Wiki hii"
            days <= 31 -> "Mwezi huu"
            else -> "Kipindi cha siku $days"
        }
    }

    /**
     * Format amount for display.
     */
    private fun formatAmount(amount: Double): String {
        return when {
            amount >= 1_000_000 -> String.format("%.1fM", amount / 1_000_000.0)
            amount >= 1_000 -> String.format("%,.0f", amount)
            else -> String.format("%.0f", amount)
        }
    }
}
