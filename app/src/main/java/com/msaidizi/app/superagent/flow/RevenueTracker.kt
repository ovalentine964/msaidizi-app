package com.msaidizi.app.superagent.flow

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * RevenueTracker — "Pesa imeingia wapi?"
 * Tracks all money coming in: total sales, by product, peak hours, trends.
 * Designed for small business owners who need to see the money flow clearly.
 */
class RevenueTracker {

    private val sales = mutableListOf<Sale>()

    // ── Data Management ────────────────────────

    fun recordSale(sale: Sale) {
        sales.add(sale)
    }

    fun recordSales(newSales: List<Sale>) {
        sales.addAll(newSales)
    }

    fun clearData() {
        sales.clear()
    }

    // ── Revenue Summary ────────────────────────

    /**
     * Generate a full revenue summary for the given period.
     * This answers: "Nilipata pesa ngapi wiki hii?"
     */
    fun getSummary(period: ReportPeriod, customRange: DateRange? = null): RevenueSummary {
        val range = resolveDateRange(period, customRange)
        val periodSales = filterSales(range)

        val totalRevenue = periodSales.sumOf { it.amount }
        val totalTransactions = periodSales.size

        // Revenue by product
        val revenueByProduct = periodSales
            .groupBy { it.productName }
            .mapValues { (_, sales) -> sales.sumOf { it.amount } }
            .toSortedMap(compareByDescending { revenueByProduct(it) })

        // Revenue by payment method
        val revenueByPaymentMethod = periodSales
            .groupBy { it.paymentMethod }
            .mapValues { (_, sales) -> sales.sumOf { it.amount } }

        // Revenue by hour of day
        val revenueByHour = periodSales
            .groupBy { it.timestamp.hour }
            .mapValues { (_, sales) -> sales.sumOf { it.amount } }

        val peakHour = revenueByHour.maxByOrNull { it.value }?.key ?: 12

        // Daily stats
        val daysInRange = range.days.coerceAtLeast(1)
        val dailyAverage = totalRevenue / daysInRange

        // Peak single day
        val peakDayRevenue = periodSales
            .groupBy { it.timestamp.toLocalDate() }
            .maxOfOrNull { (_, daySales) -> daySales.sumOf { it.amount } } ?: 0.0

        // Trend vs previous period
        val previousRange = getPreviousPeriod(range)
        val previousSales = filterSales(previousRange)
        val previousRevenue = previousSales.sumOf { it.amount }
        val trendPercent = if (previousRevenue > 0) {
            ((totalRevenue - previousRevenue) / previousRevenue) * 100
        } else if (totalRevenue > 0) 100.0 else 0.0

        val trendDirection = when {
            trendPercent > 5 -> TrendDirection.UP
            trendPercent < -5 -> TrendDirection.DOWN
            else -> TrendDirection.FLAT
        }

        return RevenueSummary(
            period = range,
            totalRevenue = totalRevenue,
            totalTransactions = totalTransactions,
            revenueByProduct = revenueByProduct,
            revenueByPaymentMethod = revenueByPaymentMethod,
            revenueByHour = revenueByHour,
            peakHour = peakHour,
            peakDayRevenue = peakDayRevenue,
            dailyAverage = dailyAverage,
            trendPercent = trendPercent,
            trendDirection = trendDirection
        )
    }

    // ── Product Breakdown ──────────────────────

    /**
     * Get revenue broken down by product with percentages.
     * "Bidhaa gani zinanipatia pesa zaidi?"
     */
    fun getRevenueByProduct(period: ReportPeriod, customRange: DateRange? = null): List<RevenueByProduct> {
        val range = resolveDateRange(period, customRange)
        val periodSales = filterSales(range)
        val totalRevenue = periodSales.sumOf { it.amount }

        return periodSales
            .groupBy { it.productId }
            .map { (productId, productSales) ->
                val revenue = productSales.sumOf { it.amount }
                RevenueByProduct(
                    productId = productId,
                    productName = productSales.first().productName,
                    totalRevenue = revenue,
                    totalQuantity = productSales.sumOf { it.quantity },
                    totalTransactions = productSales.size,
                    percentOfTotal = if (totalRevenue > 0) (revenue / totalRevenue) * 100 else 0.0
                )
            }
            .sortedByDescending { it.totalRevenue }
    }

    // ── Peak Hours Analysis ────────────────────

    /**
     * Find the busiest hours. Helps worker know when to stock up.
     * "Saa ngapi nina wateja wengi?"
     */
    fun getPeakHours(period: ReportPeriod, customRange: DateRange? = null): List<HourlyRevenue> {
        val range = resolveDateRange(period, customRange)
        val periodSales = filterSales(range)

        return (0..23).map { hour ->
            val hourSales = periodSales.filter { it.timestamp.hour == hour }
            HourlyRevenue(
                hour = hour,
                revenue = hourSales.sumOf { it.amount },
                transactions = hourSales.size,
                averagePerTransaction = if (hourSales.isNotEmpty()) {
                    hourSales.sumOf { it.amount } / hourSales.size
                } else 0.0
            )
        }.sortedByDescending { it.revenue }
    }

    // ── Daily Trend ────────────────────────────

    /**
     * Day-by-day revenue for charting trends.
     * "Pesa inaongezeka au kupungua?"
     */
    fun getDailyTrend(period: ReportPeriod, customRange: DateRange? = null): List<DailyRevenue> {
        val range = resolveDateRange(period, customRange)
        val periodSales = filterSales(range)

        return (0 until range.days).map { dayOffset ->
            val date = range.start.plusDays(dayOffset)
            val daySales = periodSales.filter { it.timestamp.toLocalDate() == date }
            DailyRevenue(
                date = date,
                revenue = daySales.sumOf { it.amount },
                transactions = daySales.size
            )
        }
    }

    // ── Payment Method Breakdown ───────────────

    /**
     * How customers pay: cash, M-Pesa, credit, etc.
     * "Wateja wanalipaje?"
     */
    fun getPaymentMethodBreakdown(period: ReportPeriod, customRange: DateRange? = null): Map<PaymentMethod, Double> {
        val range = resolveDateRange(period, customRange)
        return filterSales(range)
            .groupBy { it.paymentMethod }
            .mapValues { (_, sales) -> sales.sumOf { it.amount } }
    }

    // ── Swahili Voice Summaries ────────────────

    /**
     * Generate a voice-friendly Swahili summary of revenue.
     * Optimized for TTS output — sounds natural when spoken.
     */
    fun getVoiceSummary(period: ReportPeriod, customRange: DateRange? = null): String {
        val summary = getSummary(period, customRange)
        val periodName = getPeriodNameSw(period)
        val range = resolveDateRange(period, customRange)

        val totalStr = formatKes(summary.totalRevenue)
        val dailyStr = formatKes(summary.dailyAverage)

        val trendText = when (summary.trendDirection) {
            TrendDirection.UP -> "imeongezeka kwa asilimia ${"%.0f".format(summary.trendPercent)}"
            TrendDirection.DOWN -> "imepungua kwa asilimia ${"%.0f".format(kotlin.math.abs(summary.trendPercent))}"
            TrendDirection.FLAT -> "imebaki sawa"
        }

        val peakHourStr = formatHourSw(summary.peakHour)

        // Top 3 products
        val topProducts = summary.revenueByProduct.entries.take(3)
        val productText = if (topProducts.isNotEmpty()) {
            val items = topProducts.joinToString(", ") { (name, amount) ->
                "$name (${formatKes(amount)})"
            }
            " Bidhaa bora ni: $items."
        } else ""

        return buildString {
            append("Pesa $periodName: $totalStr. ")
            append("Wastani wa kila siku: $dailyStr. ")
            append("Trendi: $trendText. ")
            append("Saa yenye mauzo mengi: $peakHourStr.")
            append(productText)
        }
    }

    /**
     * English summary for dashboard display.
     */
    fun getEnglishSummary(period: ReportPeriod, customRange: DateRange? = null): String {
        val summary = getSummary(period, customRange)
        val periodName = getPeriodNameEn(period)

        val trendText = when (summary.trendDirection) {
            TrendDirection.UP -> "up ${"%.0f".format(summary.trendPercent)}%"
            TrendDirection.DOWN -> "down ${"%.0f".format(kotlin.math.abs(summary.trendPercent))}%"
            TrendDirection.FLAT -> "flat"
        }

        return buildString {
            append("Revenue $periodName: KES ${"%,.0f".format(summary.totalRevenue)} ")
            append("(${summary.totalTransactions} sales, avg KES ${"%,.0f".format(summary.dailyAverage)}/day). ")
            append("Trend: $trendText vs previous period. ")
            append("Peak hour: ${summary.peakHour}:00.")
        }
    }

    // ── Helpers ────────────────────────────────

    private fun filterSales(range: DateRange): List<Sale> {
        return sales.filter { sale ->
            val date = sale.timestamp.toLocalDate()
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

    private fun formatHourSw(hour: Int): String {
        return when (hour) {
            0 -> "saa sita usiku"
            1 -> "saa moja usiku"
            2 -> "saa mbili usiku"
            3 -> "saa tatu usiku"
            4 -> "saa nne usiku"
            5 -> "saa tano usiku"
            6 -> "saa sita asubuhi"
            7 -> "saa saba asubuhi"
            8 -> "saa nane asubuhi"
            9 -> "saa tisa asubuhi"
            10 -> "saa kumi asubuhi"
            11 -> "saa kumi na moja asubuhi"
            12 -> "saa sita mchana"
            13 -> "saa moja mchana"
            14 -> "saa mbili mchana"
            15 -> "saa tatu mchana"
            16 -> "saa nne mchana"
            17 -> "saa tano mchana"
            18 -> "saa sita jioni"
            19 -> "saa saba jioni"
            20 -> "saa nane usiku"
            21 -> "saa tisa usiku"
            22 -> "saa kumi usiku"
            23 -> "saa kumi na moja usiku"
            else -> "saa $hour"
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

    private fun revenueByProduct(entry: Map.Entry<String, Double>): Double = entry.value
}

// ── Supporting data classes ──────────────────

data class HourlyRevenue(
    val hour: Int,
    val revenue: Double,
    val transactions: Int,
    val averagePerTransaction: Double
)

data class DailyRevenue(
    val date: LocalDate,
    val revenue: Double,
    val transactions: Int
)
