package com.msaidizi.app.superagent.flow

import java.time.LocalDate

/**
 * ProfitCalculator — "Faida yangu ni ngapi?"
 * Calculates gross and net profit, margins, and trends.
 * The KEY question every worker asks: "After everything, what did I actually keep?"
 */
class ProfitCalculator(
    private val revenueTracker: RevenueTracker,
    private val costTracker: CostTracker
) {

    // ── Main Profit Calculation ────────────────

    /**
     * Calculate profit summary for a period.
     * "Wiki hii, nilipata KES 10,000, nikatumia KES 8,000, faida ni KES 2,000."
     */
    fun getProfitSummary(period: ReportPeriod, customRange: DateRange? = null): ProfitSummary {
        val revenue = revenueTracker.getSummary(period, customRange)
        val costs = costTracker.getSummary(period, customRange)

        val grossProfit = revenue.totalRevenue - costs.cogs          // revenue minus stock cost
        val netProfit = revenue.totalRevenue - costs.totalCosts      // revenue minus ALL costs

        val grossMargin = if (revenue.totalRevenue > 0) {
            (grossProfit / revenue.totalRevenue) * 100
        } else 0.0

        val netMargin = if (revenue.totalRevenue > 0) {
            (netProfit / revenue.totalRevenue) * 100
        } else 0.0

        val daysInRange = revenue.period.days.coerceAtLeast(1)
        val dailyAverageProfit = netProfit / daysInRange

        // Trend: compare net profit to previous period
        val previousRevenue = revenueTracker.getSummary(
            getPreviousPeriodLabel(period),
            getPreviousCustomRange(revenue.period)
        )
        val previousCosts = costTracker.getSummary(
            getPreviousPeriodLabel(period),
            getPreviousCustomRange(costs.period)
        )
        val previousNetProfit = previousRevenue.totalRevenue - previousCosts.totalCosts

        val trendPercent = if (previousNetProfit != 0.0) {
            ((netProfit - previousNetProfit) / kotlin.math.abs(previousNetProfit)) * 100
        } else if (netProfit > 0) 100.0 else 0.0

        val trendDirection = when {
            trendPercent > 5 -> TrendDirection.UP
            trendPercent < -5 -> TrendDirection.DOWN
            else -> TrendDirection.FLAT
        }

        val comparisonNote = buildComparisonNote(netProfit, previousNetProfit, trendDirection)

        return ProfitSummary(
            period = revenue.period,
            totalRevenue = revenue.totalRevenue,
            totalCosts = costs.totalCosts,
            grossProfit = grossProfit,
            netProfit = netProfit,
            grossMargin = grossMargin,
            netMargin = netMargin,
            dailyAverageProfit = dailyAverageProfit,
            trendPercent = trendPercent,
            trendDirection = trendDirection,
            comparisonNote = comparisonNote
        )
    }

    // ── Margin Analysis ────────────────────────

    /**
     * Get profit margin for each product.
     * "Bidhaa gani inanipatia faida zaidi?"
     */
    fun getProfitByProduct(period: ReportPeriod, customRange: DateRange? = null): List<ProductProfit> {
        val revenue = revenueTracker.getRevenueByProduct(period, customRange)
        // For simplicity, we use average COGS ratio
        // In production, this would use actual product cost data
        return revenue.map { product ->
            val estimatedMargin = 30.0  // default, would be calculated from actual cost data
            ProductProfit(
                productName = product.productName,
                revenue = product.totalRevenue,
                estimatedProfit = product.totalRevenue * (estimatedMargin / 100),
                margin = estimatedMargin,
                quantity = product.totalQuantity
            )
        }.sortedByDescending { it.estimatedProfit }
    }

    // ── Profitability Score ────────────────────

    /**
     * Score the business health 0-100.
     * Considers margin, trend, waste rate, and cost efficiency.
     */
    fun getProfitabilityScore(period: ReportPeriod, customRange: DateRange? = null): ProfitabilityScore {
        val profit = getProfitSummary(period, customRange)
        val costs = costTracker.getSummary(period, customRange)
        val wasteRate = costTracker.getWasteRate(period, profit.totalRevenue, customRange)

        // Score components (each 0-25 points)
        val marginScore = when {
            profit.netMargin >= 30 -> 25
            profit.netMargin >= 20 -> 20
            profit.netMargin >= 10 -> 15
            profit.netMargin >= 5 -> 10
            profit.netMargin > 0 -> 5
            else -> 0
        }

        val trendScore = when (profit.trendDirection) {
            TrendDirection.UP -> if (profit.trendPercent > 20) 25 else 20
            TrendDirection.FLAT -> 15
            TrendDirection.DOWN -> if (profit.trendPercent < -20) 0 else 5
        }

        val wasteScore = when (wasteRate.severity) {
            WasteSeverity.LOW -> 25
            WasteSeverity.MODERATE -> 15
            WasteSeverity.HIGH -> 5
            WasteSeverity.CRITICAL -> 0
        }

        // Cost efficiency: transport + utilities as % of revenue
        val overheadPercent = if (profit.totalRevenue > 0) {
            ((costs.transport + costs.utilities) / profit.totalRevenue) * 100
        } else 100.0

        val efficiencyScore = when {
            overheadPercent < 5 -> 25
            overheadPercent < 10 -> 20
            overheadPercent < 15 -> 15
            overheadPercent < 25 -> 10
            else -> 5
        }

        val totalScore = marginScore + trendScore + wasteScore + efficiencyScore

        val rating = when {
            totalScore >= 80 -> ProfitRating.EXCELLENT
            totalScore >= 60 -> ProfitRating.GOOD
            totalScore >= 40 -> ProfitRating.FAIR
            totalScore >= 20 -> ProfitRating.POOR
            else -> ProfitRating.CRITICAL
        }

        return ProfitabilityScore(
            score = totalScore,
            rating = rating,
            marginScore = marginScore,
            trendScore = trendScore,
            wasteScore = wasteScore,
            efficiencyScore = efficiencyScore,
            messageEn = getScoreMessageEn(rating),
            messageSw = getScoreMessageSw(rating)
        )
    }

    // ── Breakeven Analysis ─────────────────────

    /**
     * How much more sales needed to break even (if losing money)
     * or hit a target margin.
     */
    fun getBreakevenAnalysis(
        period: ReportPeriod,
        targetMargin: Double = 20.0,
        customRange: DateRange? = null
    ): BreakevenAnalysis {
        val profit = getProfitSummary(period, customRange)
        val currentRevenue = profit.totalRevenue
        val totalCosts = profit.totalCosts

        // Revenue needed for target margin: revenue = costs / (1 - targetMargin/100)
        val revenueForTarget = totalCosts / (1 - targetMargin / 100)
        val additionalRevenueNeeded = (revenueForTarget - currentRevenue).coerceAtLeast(0.0)

        // Revenue needed just to break even (margin = 0)
        val breakevenRevenue = totalCosts
        val gapToBreakeven = (breakevenRevenue - currentRevenue).coerceAtLeast(0.0)

        return BreakevenAnalysis(
            currentRevenue = currentRevenue,
            totalCosts = totalCosts,
            currentProfit = profit.netProfit,
            currentMargin = profit.netMargin,
            targetMargin = targetMargin,
            revenueNeededForTarget = revenueForTarget,
            additionalRevenueNeeded = additionalRevenueNeeded,
            isProfitable = profit.netProfit > 0,
            messageEn = if (profit.netProfit > 0) {
                "You're profitable! To reach ${"%.0f".format(targetMargin)}% margin, you need KES ${"%,.0f".format(additionalRevenueNeeded)} more sales."
            } else {
                "You need KES ${"%,.0f".format(gapToBreakeven)} more sales to break even."
            },
            messageSw = if (profit.netProfit > 0) {
                "Uko na faida! Kufikia asilimia ${"%.0f".format(targetMargin)}, unahitaji mauzo mengine KES ${"%,.0f".format(additionalRevenueNeeded)}."
            } else {
                "Unahitaji mauzo mengine KES ${"%,.0f".format(gapToBreakeven)} kuvunja hata."
            }
        )
    }

    // ── Voice Summaries ────────────────────────

    /**
     * The money speech: "You made X, spent Y, kept Z"
     * This is THE summary workers want to hear.
     */
    fun getVoiceSummary(period: ReportPeriod, customRange: DateRange? = null): String {
        val profit = getProfitSummary(period, customRange)
        val periodName = getPeriodNameSw(period)

        val revenueStr = formatKes(profit.totalRevenue)
        val costsStr = formatKes(profit.totalCosts)
        val profitStr = formatKes(profit.netProfit)
        val marginStr = "%.0f".format(profit.netMargin)

        val trendText = when (profit.trendDirection) {
            TrendDirection.UP -> "Faida imeongezeka kwa asilimia ${"%.0f".format(profit.trendPercent)}"
            TrendDirection.DOWN -> "Faida imepungua kwa asilimia ${"%.0f".format(kotlin.math.abs(profit.trendPercent))}"
            TrendDirection.FLAT -> "Faida imebaki sawa"
        }

        return buildString {
            append("Ripoti ya faida $periodName. ")
            append("Ulipata $revenueStr. ")
            append("Ulitumia $costsStr. ")
            if (profit.netProfit > 0) {
                append("Faida yako ni $profitStr. ")
                append("Hiyo ni asilimia $marginStr ya mauzo. ")
            } else {
                append("Hukupata faida — ulipoteza ${formatKes(kotlin.math.abs(profit.netProfit))}. ")
            }
            append("$trendText ukilinganisha na kipindi kilichopita.")
        }
    }

    fun getEnglishSummary(period: ReportPeriod, customRange: DateRange? = null): String {
        val profit = getProfitSummary(period, customRange)
        val periodName = getPeriodNameEn(period)

        return buildString {
            append("Profit $periodName: KES ${"%,.0f".format(profit.netProfit)} ")
            append("(revenue KES ${"%,.0f".format(profit.totalRevenue)} - costs KES ${"%,.0f".format(profit.totalCosts)}). ")
            append("Margin: ${"%.1f".format(profit.netMargin)}%. ")
            append("Trend: ${profit.trendDirection.name.lowercase()} ${"%.0f".format(kotlin.math.abs(profit.trendPercent))}%.")
        }
    }

    // ── Helpers ────────────────────────────────

    private fun buildComparisonNote(current: Double, previous: Double, direction: TrendDirection): String {
        val diff = current - previous
        return when (direction) {
            TrendDirection.UP -> "Faida imeongezeka kwa KES ${"%,.0f".format(kotlin.math.abs(diff))}!"
            TrendDirection.DOWN -> "Faida imepungua kwa KES ${"%,.0f".format(kotlin.math.abs(diff))}."
            TrendDirection.FLAT -> "Faida imebaki karibu sawa."
        }
    }

    private fun getPreviousPeriodLabel(period: ReportPeriod): ReportPeriod {
        return when (period) {
            ReportPeriod.TODAY -> ReportPeriod.YESTERDAY
            ReportPeriod.THIS_WEEK -> ReportPeriod.LAST_WEEK
            ReportPeriod.THIS_MONTH -> ReportPeriod.LAST_MONTH
            else -> period
        }
    }

    private fun getPreviousCustomRange(range: DateRange): DateRange {
        return DateRange(
            start = range.start.minusDays(range.days),
            end = range.start.minusDays(1)
        )
    }

    private fun formatKes(amount: Double): String {
        val abs = kotlin.math.abs(amount)
        return when {
            abs >= 1_000_000 -> "KES ${"%.1f".format(abs / 1_000_000)} milioni"
            abs >= 1_000 -> "KES ${"%,.0f".format(abs)}"
            else -> "KES ${"%.0f".format(abs)}"
        }
    }

    private fun getPeriodNameSw(period: ReportPeriod): String = when (period) {
        ReportPeriod.TODAY -> "ya leo"
        ReportPeriod.YESTERDAY -> "ya jana"
        ReportPeriod.THIS_WEEK -> "ya wiki hii"
        ReportPeriod.LAST_WEEK -> "ya wiki iliyopita"
        ReportPeriod.THIS_MONTH -> "ya mwezi huu"
        ReportPeriod.LAST_MONTH -> "ya mwezi uliopita"
        ReportPeriod.CUSTOM -> "ya kipindi hiki"
    }

    private fun getPeriodNameEn(period: ReportPeriod): String = when (period) {
        ReportPeriod.TODAY -> "today"
        ReportPeriod.YESTERDAY -> "yesterday"
        ReportPeriod.THIS_WEEK -> "this week"
        ReportPeriod.LAST_WEEK -> "last week"
        ReportPeriod.THIS_MONTH -> "this month"
        ReportPeriod.LAST_MONTH -> "last month"
        ReportPeriod.CUSTOM -> "this period"
    }

    private fun getScoreMessageEn(rating: ProfitRating): String = when (rating) {
        ProfitRating.EXCELLENT -> "🌟 Business is doing great! Keep it up!"
        ProfitRating.GOOD -> "👍 Business is healthy. A few improvements possible."
        ProfitRating.FAIR -> "⚠️ Business is okay but needs attention."
        ProfitRating.POOR -> "🔴 Business is struggling. Action needed."
        ProfitRating.CRITICAL -> "🚨 Business is in trouble. Urgent action needed!"
    }

    private fun getScoreMessageSw(rating: ProfitRating): String = when (rating) {
        ProfitRating.EXCELLENT -> "🌟 Biashara yako iko vizuri sana! Endelea hivyo!"
        ProfitRating.GOOD -> "👍 Biashara iko sawa. Kuna mambo machache ya kuboresha."
        ProfitRating.FAIR -> "⚠️ Biashara iko haina mbaya, lakini inahitaji uangalizi."
        ProfitRating.POOR -> "🔴 Biashara inapata shida. Fanya kitu!"
        ProfitRating.CRITICAL -> "🚨 Biashara iko hatarini! Fanya kitu haraka!"
    }
}

// ── Supporting data classes ──────────────────

data class ProductProfit(
    val productName: String,
    val revenue: Double,
    val estimatedProfit: Double,
    val margin: Double,
    val quantity: Int
)

data class ProfitabilityScore(
    val score: Int,           // 0-100
    val rating: ProfitRating,
    val marginScore: Int,     // 0-25
    val trendScore: Int,      // 0-25
    val wasteScore: Int,      // 0-25
    val efficiencyScore: Int, // 0-25
    val messageEn: String,
    val messageSw: String
)

enum class ProfitRating {
    EXCELLENT, GOOD, FAIR, POOR, CRITICAL
}

data class BreakevenAnalysis(
    val currentRevenue: Double,
    val totalCosts: Double,
    val currentProfit: Double,
    val currentMargin: Double,
    val targetMargin: Double,
    val revenueNeededForTarget: Double,
    val additionalRevenueNeeded: Double,
    val isProfitable: Boolean,
    val messageEn: String,
    val messageSw: String
)
