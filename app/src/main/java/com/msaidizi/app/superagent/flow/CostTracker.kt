package com.msaidizi.app.superagent.flow

import java.time.LocalDate

/**
 * CostTracker — "Pesa inatoka wapi?"
 * Tracks ALL money going out: stock purchases, transport, rent, waste, utilities.
 * The goal: show workers exactly where their money disappears to.
 */
class CostTracker {

    private val expenses = mutableListOf<Expense>()

    // ── Data Management ────────────────────────

    fun recordExpense(expense: Expense) {
        expenses.add(expense)
    }

    fun recordExpenses(newExpenses: List<Expense>) {
        expenses.addAll(newExpenses)
    }

    fun clearData() {
        expenses.clear()
    }

    // ── Cost Summary ───────────────────────────

    /**
     * Full cost breakdown for a period.
     * "Nilitumia pesa gani wiki hii?"
     */
    fun getSummary(period: ReportPeriod, customRange: DateRange? = null): CostSummary {
        val range = resolveDateRange(period, customRange)
        val periodExpenses = filterExpenses(range)

        val totalCosts = periodExpenses.sumOf { it.amount }
        val daysInRange = range.days.coerceAtLeast(1)

        // Breakdown by category
        val costsByCategory = ExpenseCategory.values().associateWith { cat ->
            periodExpenses.filter { it.category == cat }.sumOf { it.amount }
        }

        val cogs = costsByCategory[ExpenseCategory.COGS] ?: 0.0
        val transport = costsByCategory[ExpenseCategory.TRANSPORT] ?: 0.0
        val rent = costsByCategory[ExpenseCategory.RENT] ?: 0.0
        val waste = costsByCategory[ExpenseCategory.WASTE] ?: 0.0
        val utilities = costsByCategory[ExpenseCategory.UTILITIES] ?: 0.0
        val labor = costsByCategory[ExpenseCategory.LABOR] ?: 0.0
        val otherCosts = costsByCategory.entries
            .filter { it.key !in listOf(ExpenseCategory.COGS, ExpenseCategory.TRANSPORT, ExpenseCategory.RENT, ExpenseCategory.WASTE, ExpenseCategory.UTILITIES, ExpenseCategory.LABOR) }
            .sumOf { it.value }

        // Percentage breakdown
        val costBreakdownPercent = if (totalCosts > 0) {
            costsByCategory.mapValues { (_, amount) -> (amount / totalCosts) * 100 }
        } else {
            costsByCategory.mapValues { 0.0 }
        }

        // Top expense category
        val topExpense = costsByCategory.maxByOrNull { it.value }?.key ?: ExpenseCategory.OTHER

        // Trend vs previous period
        val previousRange = getPreviousPeriod(range)
        val previousExpenses = filterExpenses(previousRange)
        val previousTotal = previousExpenses.sumOf { it.amount }
        val trendPercent = if (previousTotal > 0) {
            ((totalCosts - previousTotal) / previousTotal) * 100
        } else if (totalCosts > 0) 100.0 else 0.0

        return CostSummary(
            period = range,
            totalCosts = totalCosts,
            costsByCategory = costsByCategory,
            cogs = cogs,
            transport = transport,
            rent = rent,
            waste = waste,
            utilities = utilities,
            labor = labor,
            otherCosts = otherCosts,
            dailyAverage = totalCosts / daysInRange,
            trendPercent = trendPercent,
            topExpense = topExpense,
            costBreakdownPercent = costBreakdownPercent
        )
    }

    // ── Category Deep Dives ────────────────────

    /**
     * Detailed transport costs breakdown.
     * "Nimetumia pesa ngapi usafiri?"
     */
    fun getTransportDetails(period: ReportPeriod, customRange: DateRange? = null): List<Expense> {
        val range = resolveDateRange(period, customRange)
        return filterExpenses(range).filter { it.category == ExpenseCategory.TRANSPORT }
    }

    /**
     * Detailed waste costs — what was wasted and why.
     * "Nimepoteza pesa gani?"
     */
    fun getWasteDetails(period: ReportPeriod, customRange: DateRange? = null): List<Expense> {
        val range = resolveDateRange(period, customRange)
        return filterExpenses(range).filter { it.category == ExpenseCategory.WASTE }
    }

    /**
     * COGS breakdown — how much spent on stock purchases.
     * "Nimenunua stock kwa pesa ngapi?"
     */
    fun getCOGSDetails(period: ReportPeriod, customRange: DateRange? = null): List<Expense> {
        val range = resolveDateRange(period, customRange)
        return filterExpenses(range).filter { it.category == ExpenseCategory.COGS }
    }

    // ── Recurring Costs ────────────────────────

    /**
     * Get all recurring costs (rent, subscriptions, etc.)
     * "Gharama za kila mwezi ni zipi?"
     */
    fun getRecurringCosts(period: ReportPeriod, customRange: DateRange? = null): List<Expense> {
        val range = resolveDateRange(period, customRange)
        return filterExpenses(range).filter { it.isRecurring }
    }

    // ── Daily Cost Trend ───────────────────────

    /**
     * Day-by-day cost trend for visualization.
     */
    fun getDailyCostTrend(period: ReportPeriod, customRange: DateRange? = null): List<DailyCost> {
        val range = resolveDateRange(period, customRange)
        val periodExpenses = filterExpenses(range)

        return (0 until range.days).map { dayOffset ->
            val date = range.start.plusDays(dayOffset)
            val dayExpenses = periodExpenses.filter {
                it.timestamp.toLocalDate() == date
            }
            DailyCost(
                date = date,
                totalCost = dayExpenses.sumOf { it.amount },
                breakdown = dayExpenses.groupBy { it.category }
                    .mapValues { (_, exps) -> exps.sumOf { it.amount } }
            )
        }
    }

    // ── Waste Analysis ─────────────────────────

    /**
     * Calculate waste as percentage of revenue.
     * Critical for perishable goods businesses.
     */
    fun getWasteRate(period: ReportPeriod, totalRevenue: Double, customRange: DateRange? = null): WasteRate {
        val range = resolveDateRange(period, customRange)
        val wasteAmount = filterExpenses(range)
            .filter { it.category == ExpenseCategory.WASTE }
            .sumOf { it.amount }

        val wastePercent = if (totalRevenue > 0) (wasteAmount / totalRevenue) * 100 else 0.0

        val severity = when {
            wastePercent > 10 -> WasteSeverity.CRITICAL   // losing >10% to waste
            wastePercent > 5 -> WasteSeverity.HIGH         // 5-10%
            wastePercent > 2 -> WasteSeverity.MODERATE     // 2-5%
            else -> WasteSeverity.LOW                       // <2%, acceptable
        }

        return WasteRate(
            wasteAmount = wasteAmount,
            wastePercent = wastePercent,
            severity = severity,
            messageEn = when (severity) {
                WasteSeverity.CRITICAL -> "⚠️ UMEPOTEZA KES ${"%,.0f".format(wasteAmount)} kupotea — ondoa haraka!"
                WasteSeverity.HIGH -> "Pesa nyingi imepotea kupotea: KES ${"%,.0f".format(wasteAmount)}"
                WasteSeverity.MODERATE -> "Potea ni wastani: KES ${"%,.0f".format(wasteAmount)}"
                WasteSeverity.LOW -> "Potea ni kidogo — vizuri!"
            },
            messageSw = when (severity) {
                WasteSeverity.CRITICAL -> "Umeshindwa! Pesa nyingi imepotea — fanya kitu haraka!"
                WasteSeverity.HIGH -> "Pesa nyingi imepotea kupotea."
                WasteSeverity.MODERATE -> "Potea ni wastani — jaribu kuboresha."
                WasteSeverity.LOW -> "Potea ni kidogo — hali ni nzuri!"
            }
        )
    }

    // ── Voice Summaries ────────────────────────

    fun getVoiceSummary(period: ReportPeriod, customRange: DateRange? = null): String {
        val summary = getSummary(period, customRange)
        val periodName = getPeriodNameSw(period)

        return buildString {
            append("Gharama $periodName: ${formatKes(summary.totalCosts)}. ")
            append("Stock: ${formatKes(summary.cogs)}, ")
            append("Usafiri: ${formatKes(summary.transport)}, ")
            append("Kodi: ${formatKes(summary.rent)}, ")
            append("Potea: ${formatKes(summary.waste)}, ")
            append("Umeme na simu: ${formatKes(summary.utilities)}. ")
            append("Gharama kubwa zaidi: ${getCategoryNameSw(summary.topExpense)}.")
        }
    }

    fun getEnglishSummary(period: ReportPeriod, customRange: DateRange? = null): String {
        val summary = getSummary(period, customRange)
        val periodName = getPeriodNameEn(period)

        return buildString {
            append("Costs $periodName: KES ${"%,.0f".format(summary.totalCosts)} total. ")
            append("Stock: KES ${"%,.0f".format(summary.cogs)}, ")
            append("Transport: KES ${"%,.0f".format(summary.transport)}, ")
            append("Rent: KES ${"%,.0f".format(summary.rent)}, ")
            append("Waste: KES ${"%,.0f".format(summary.waste)}, ")
            append("Utilities: KES ${"%,.0f".format(summary.utilities)}. ")
            append("Biggest expense: ${summary.topExpense.name.lowercase()}.")
        }
    }

    // ── Helpers ────────────────────────────────

    private fun filterExpenses(range: DateRange): List<Expense> {
        return expenses.filter { expense ->
            val date = expense.timestamp.toLocalDate()
            !date.isBefore(range.start) && !date.isAfter(range.end)
        }
    }

    private fun resolveDateRange(period: ReportPeriod, custom: DateRange?): DateRange {
        return when (period) {
            ReportPeriod.TODAY -> DateRange.today()
            ReportPeriod.YESTERDAY -> {
                val yesterday = LocalDate.now().minusDays(1)
                DateRange(yesterday, yesterday)
            }
            ReportPeriod.THIS_WEEK -> DateRange.thisWeek()
            ReportPeriod.LAST_WEEK -> {
                val end = LocalDate.now().minusDays(LocalDate.now().dayOfWeek.value.toLong())
                val start = end.minusDays(6)
                DateRange(start, end)
            }
            ReportPeriod.THIS_MONTH -> DateRange.thisMonth()
            ReportPeriod.LAST_MONTH -> {
                val firstOfThisMonth = LocalDate.now().withDayOfMonth(1)
                val end = firstOfThisMonth.minusDays(1)
                val start = end.withDayOfMonth(1)
                DateRange(start, end)
            }
            ReportPeriod.CUSTOM -> custom ?: DateRange.today()
        }
    }

    private fun getPreviousPeriod(range: DateRange): DateRange {
        val days = range.days
        return DateRange(
            start = range.start.minusDays(days),
            end = range.start.minusDays(1)
        )
    }

    private fun formatKes(amount: Double): String {
        return when {
            amount >= 1_000_000 -> "KES ${"%.1f".format(amount / 1_000_000)} milioni"
            amount >= 1_000 -> "KES ${"%,.0f".format(amount)}"
            else -> "KES ${"%.0f".format(amount)}"
        }
    }

    private fun getCategoryNameSw(cat: ExpenseCategory): String {
        return when (cat) {
            ExpenseCategory.COGS -> "Stock"
            ExpenseCategory.TRANSPORT -> "Usafiri"
            ExpenseCategory.RENT -> "Kodi"
            ExpenseCategory.WASTE -> "Potea"
            ExpenseCategory.UTILITIES -> "Umeme na simu"
            ExpenseCategory.LABOR -> "Mshahara"
            ExpenseCategory.PACKAGING -> "Vifungashio"
            ExpenseCategory.TAX_FEES -> "Kodi na ada"
            ExpenseCategory.OTHER -> "Nyingine"
        }
    }

    private fun getPeriodNameSw(period: ReportPeriod): String {
        return when (period) {
            ReportPeriod.TODAY -> "ya leo"
            ReportPeriod.YESTERDAY -> "ya jana"
            ReportPeriod.THIS_WEEK -> "ya wiki hii"
            ReportPeriod.LAST_WEEK -> "ya wiki iliyopita"
            ReportPeriod.THIS_MONTH -> "ya mwezi huu"
            ReportPeriod.LAST_MONTH -> "ya mwezi uliopita"
            ReportPeriod.CUSTOM -> "ya kipindi hiki"
        }
    }

    private fun getPeriodNameEn(period: ReportPeriod): String {
        return when (period) {
            ReportPeriod.TODAY -> "today"
            ReportPeriod.YESTERDAY -> "yesterday"
            ReportPeriod.THIS_WEEK -> "this week"
            ReportPeriod.LAST_WEEK -> "last week"
            ReportPeriod.THIS_MONTH -> "this month"
            ReportPeriod.LAST_MONTH -> "last month"
            ReportPeriod.CUSTOM -> "this period"
        }
    }
}

// ── Supporting data classes ──────────────────

data class DailyCost(
    val date: LocalDate,
    val totalCost: Double,
    val breakdown: Map<ExpenseCategory, Double>
)

data class WasteRate(
    val wasteAmount: Double,
    val wastePercent: Double,
    val severity: WasteSeverity,
    val messageEn: String,
    val messageSw: String
)

enum class WasteSeverity {
    LOW, MODERATE, HIGH, CRITICAL
}
