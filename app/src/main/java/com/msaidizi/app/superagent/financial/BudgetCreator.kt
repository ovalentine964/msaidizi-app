package com.msaidizi.superagent.financial

import timber.log.Timber
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Voice-based Budget Creator for informal workers.
 *
 * Creates and manages budgets from voice input. Workers can set
 * spending limits by category and track adherence over time.
 *
 * ## Budget Philosophy for Informal Workers
 * - **Simple:** 3-5 categories max (not 20 like corporate budgets)
 * - **Voice-first:** Set budgets by speaking, not typing
 * - **Flexible:** Adjust based on income fluctuations
 * - **Visual:** Track progress with simple percentages
 *
 * ## Default Budget Categories
 * Based on research into informal worker spending patterns:
 * 1. **Stock/Business** (50-60%): Reinvestment in inventory
 * 2. **Household** (20-25%): Rent, food, utilities
 * 3. **Transport** (5-10%): Getting to market
 * 4. **Savings** (10-20%): Emergency fund, goals
 * 5. **Other** (5%): Miscellaneous
 *
 * ## Academic Foundations
 * - **ECO 206 (Microfinance):** Savings behavior, budget constraints
 * - **PSY 200 (Behavioral Economics):** Mental accounting, budget framing
 * - **FIN 201 (Corporate Finance):** Budget allocation, variance analysis
 *
 * @author Msaidizi Financial Team
 */
class BudgetCreator {

    companion object {
        private const val TAG = "BudgetCreator"

        /** Default budget allocation percentages */
        private val DEFAULT_ALLOCATIONS = mapOf(
            "stock" to 0.55,        // 55% for business reinvestment
            "household" to 0.20,    // 20% for household expenses
            "transport" to 0.08,    // 8% for transport
            "savings" to 0.12,      // 12% for savings
            "other" to 0.05         // 5% for miscellaneous
        )

        /** Minimum number of budget categories */
        private const val MIN_CATEGORIES = 3

        /** Maximum number of budget categories */
        private const val MAX_CATEGORIES = 7
    }

    // ═══════════════════════════════════════════════════════════════
    // BUDGET CREATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Create a budget from expected monthly income.
     *
     * Uses default allocation percentages optimized for informal workers.
     *
     * @param monthlyIncome Expected monthly income in KSh
     * @param customAllocations Optional custom category allocations (overrides defaults)
     * @param period Budget period ("daily", "weekly", "monthly")
     * @return [BudgetPlan] with category allocations
     */
    fun createFromIncome(
        monthlyIncome: Double,
        customAllocations: Map<String, Double>? = null,
        period: String = "monthly"
    ): BudgetPlan {
        if (monthlyIncome <= 0) {
            return BudgetPlan(
                categories = emptyList(),
                totalIncome = 0.0,
                totalAllocated = 0.0,
                surplus = 0.0,
                message = "Hakuna mapato yaliyowekwa. Weka mapato yako ili nibudgeti."
            )
        }

        val allocations = customAllocations ?: DEFAULT_ALLOCATIONS

        // Normalize allocations to ensure they sum to 1.0
        val totalWeight = allocations.values.sum()
        val normalizedAllocations = allocations.mapValues { it.value / totalWeight }

        // Create budget categories
        val categories = normalizedAllocations.map { (category, percentage) ->
            val allocated = (monthlyIncome * percentage).roundToInt().toDouble()
            BudgetCategory(
                category = category,
                allocatedAmount = allocated,
                period = period
            )
        }.sortedByDescending { it.allocatedAmount }

        val totalAllocated = categories.sumOf { it.allocatedAmount }
        val surplus = monthlyIncome - totalAllocated

        val message = buildBudgetMessage(monthlyIncome, categories, period)

        return BudgetPlan(
            categories = categories,
            totalIncome = monthlyIncome,
            totalAllocated = totalAllocated,
            surplus = surplus,
            message = message
        )
    }

    /**
     * Create a budget from voice input.
     *
     * Parses voice commands like:
     * - "Nataka kutumia elfu tatu kwa stock, elfu moja kwa nyumba"
     * - "Budgeti yangu: stock 50%, nyumba 20%, akiba 30%"
     *
     * @param voiceInput Voice text with budget preferences
     * @param monthlyIncome Monthly income for percentage calculations
     * @return [BudgetPlan] with parsed allocations
     */
    fun createFromVoice(
        voiceInput: String,
        monthlyIncome: Double
    ): BudgetPlan {
        val normalized = voiceInput.lowercase().trim()

        // Try to parse explicit amounts
        val parsedAllocations = parseExplicitAllocations(normalized)

        if (parsedAllocations.isNotEmpty()) {
            return createFromIncome(
                monthlyIncome = monthlyIncome,
                customAllocations = parsedAllocations
            )
        }

        // Try to parse percentages
        val percentageAllocations = parsePercentageAllocations(normalized)

        if (percentageAllocations.isNotEmpty()) {
            return createFromIncome(
                monthlyIncome = monthlyIncome,
                customAllocations = percentageAllocations
            )
        }

        // Fall back to default allocations
        return createFromIncome(monthlyIncome)
    }

    // ═══════════════════════════════════════════════════════════════
    // BUDGET TRACKING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Track spending against budget.
     *
     * @param budget Current budget plan
     * @param transactions Transactions to track
     * @return Updated budget with spending amounts
     */
    fun trackSpending(
        budget: BudgetPlan,
        transactions: List<Transaction>
    ): BudgetPlan {
        // Calculate spending by category
        val spendingByCategory = transactions
            .filter { it.isExpense }
            .groupBy { categorizeExpense(it) }
            .mapValues { (_, txns) -> txns.sumOf { it.totalAmount } }

        // Update budget categories with spending
        val updatedCategories = budget.categories.map { category ->
            val spent = spendingByCategory[category.category] ?: 0.0
            category.copy(spentAmount = spent)
        }

        val totalSpent = updatedCategories.sumOf { it.spentAmount }
        val surplus = budget.totalIncome - totalSpent

        return budget.copy(
            categories = updatedCategories,
            surplus = surplus,
            message = buildTrackingMessage(updatedCategories, budget.totalIncome, totalSpent)
        )
    }

    /**
     * Generate budget variance report.
     *
     * @param budget Budget with actual spending data
     * @return Human-readable variance report in Swahili
     */
    fun getVarianceReport(budget: BudgetPlan): String {
        if (budget.categories.isEmpty()) {
            return "Hakuna budgeti iliyowekwa."
        }

        return buildString {
            append("📊 Ripoti ya Budgeti:\n\n")

            budget.categories.forEach { category ->
                val utilization = category.utilizationPercent
                val emoji = when {
                    utilization > 100 -> "🔴"
                    utilization > 80 -> "🟡"
                    else -> "🟢"
                }

                append("$emoji ${category.category.capitalize()}: ")
                append("KSh ${formatAmount(category.spentAmount)} / ")
                append("KSh ${formatAmount(category.allocatedAmount)} ")
                append("(${utilization.roundToInt()}%)\n")

                if (category.isOverBudget) {
                    val overage = category.spentAmount - category.allocatedAmount
                    append("   ⚠️ Umepitwa na KSh ${formatAmount(overage)}!\n")
                }
            }

            append("\nJumla: KSh ${formatAmount(budget.categories.sumOf { it.spentAmount })} ")
            append("/ KSh ${formatAmount(budget.totalAllocated)}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // VOICE PARSING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Parse explicit amount allocations from voice input.
     * Example: "stock elfu tatu, nyumba elfu moja"
     */
    private fun parseExplicitAllocations(input: String): Map<String, Double> {
        val allocations = mutableMapOf<String, Double>()
        val parts = input.split(",")

        for (part in parts) {
            val trimmed = part.trim()
            // Look for category name and amount
            for ((category, _) in DEFAULT_ALLOCATIONS) {
                if (trimmed.contains(category)) {
                    // Extract amount after category name
                    val afterCategory = trimmed.substringAfter(category).trim()
                    val amount = TransactionRecorder().extractAmount(afterCategory)
                    if (amount > 0) {
                        allocations[category] = amount
                    }
                }
            }
        }

        return allocations
    }

    /**
     * Parse percentage allocations from voice input.
     * Example: "stock 50%, nyumba 20%, akiba 30%"
     */
    private fun parsePercentageAllocations(input: String): Map<String, Double> {
        val allocations = mutableMapOf<String, Double>()

        for ((category, _) in DEFAULT_ALLOCATIONS) {
            if (input.contains(category)) {
                // Look for percentage after category name
                val regex = Regex("$category\\s*(\\d+)%")
                val match = regex.find(input)
                if (match != null) {
                    val percent = match.groupValues[1].toDoubleOrNull()
                    if (percent != null && percent > 0) {
                        allocations[category] = percent / 100.0
                    }
                }
            }
        }

        return allocations
    }

    /**
     * Categorize an expense transaction into budget categories.
     */
    private fun categorizeExpense(transaction: Transaction): String {
        val category = transaction.category.lowercase()
        val item = transaction.item.lowercase()

        return when {
            category.contains("stock") || category.contains("purchase") ||
                item.contains("stock") || item.contains("bidhaa") -> "stock"
            category.contains("household") || category.contains("rent") ||
                category.contains("food") || category.contains("utilities") -> "household"
            category.contains("transport") || category.contains("fare") ||
                category.contains("matatu") -> "transport"
            category.contains("savings") || category.contains("akiba") -> "savings"
            else -> "other"
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // MESSAGE GENERATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build a budget creation message in Swahili.
     */
    private fun buildBudgetMessage(
        income: Double,
        categories: List<BudgetCategory>,
        period: String
    ): String {
        return buildString {
            append("📋 Budgeti yako ya $period:\n")
            append("Mapato: KSh ${formatAmount(income)}\n\n")

            categories.forEach { category ->
                val percent = category.utilizationPercent.roundToInt()
                append("• ${category.category.capitalize()}: ")
                append("KSh ${formatAmount(category.allocatedAmount)} ($percent%)\n")
            }
        }
    }

    /**
     * Build a budget tracking message in Swahili.
     */
    private fun buildTrackingMessage(
        categories: List<BudgetCategory>,
        totalIncome: Double,
        totalSpent: Double
    ): String {
        return buildString {
            val overallUtilization = if (totalIncome > 0) {
                (totalSpent / totalIncome * 100).roundToInt()
            } else 0

            append("📊 Matumizi ya budgeti:\n")
            append("Jumla: KSh ${formatAmount(totalSpent)} / KSh ${formatAmount(totalIncome)} ")
            append("($overallUtilization%)\n\n")

            categories.forEach { category ->
                val emoji = when {
                    category.isOverBudget -> "🔴"
                    category.utilizationPercent > 80 -> "🟡"
                    else -> "🟢"
                }
                append("$emoji ${category.category.capitalize()}: ")
                append("${category.utilizationPercent.roundToInt()}%\n")
            }
        }
    }

    /**
     * Capitalize first letter of a string.
     */
    private fun String.capitalize(): String {
        return replaceFirstChar { it.uppercase() }
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
