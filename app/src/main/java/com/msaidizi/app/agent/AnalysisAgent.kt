package com.msaidizi.app.agent

import com.msaidizi.app.core.database.DailyTotalTuple
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.ItemRanking
import com.msaidizi.app.core.model.Trend
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneOffset


/**
 * Analysis Agent — calculates trends, patterns, and forecasts.
 * Pure code + statistical algorithms — no LLM needed.
 *
 * Provides business intelligence:
 * - Sales trends (moving averages)
 * - ABC inventory classification
 * - Price analysis
 * - Day-of-week patterns
 */
class AnalysisAgent(
    private val transactionDao: TransactionDao
) {
    /**
     * Calculate sales trend using 7-day moving average.
     */
    suspend fun salesTrend(days: Int = 7): Trend {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays((days * 2).toLong())
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val dailyTotals = transactionDao.getDailySalesTotals(startEpoch)

        if (dailyTotals.size < days) {
            return Trend.INSUFFICIENT_DATA
        }

        val recentDays = dailyTotals.takeLast(days)
        val previousDays = dailyTotals.dropLast(days).takeLast(days)

        val recentAvg = recentDays.map { it.total }.average()
        val previousAvg = if (previousDays.isNotEmpty()) {
            previousDays.map { it.total }.average()
        } else {
            recentAvg
        }

        return when {
            recentAvg > previousAvg * 1.1 -> Trend.RISING
            recentAvg < previousAvg * 0.9 -> Trend.FALLING
            else -> Trend.STABLE
        }
    }

    /**
     * ABC analysis — classify inventory by revenue contribution.
     * A = top 80% revenue, B = next 15%, C = bottom 5%.
     */
    suspend fun abcAnalysis(days: Int = 30): Map<String, Char> {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(days.toLong())
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val items = transactionDao.getTopSellingItems(startEpoch, endEpoch, 100)
        val totalRevenue = items.sumOf { it.totalRev }

        if (totalRevenue <= 0) return emptyMap()

        var cumulative = 0.0
        return items.associate { item ->
            cumulative += item.totalRev
            item.item to when {
                cumulative / totalRevenue <= 0.80 -> 'A'
                cumulative / totalRevenue <= 0.95 -> 'B'
                else -> 'C'
            }
        }
    }

    /**
     * Get top selling items.
     */
    suspend fun topItems(days: Int = 7, limit: Int = 5): List<ItemRanking> {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(days.toLong())
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        return transactionDao.getTopSellingItems(startEpoch, endEpoch, limit).map { tuple ->
            ItemRanking(
                item = tuple.item,
                totalQuantity = tuple.totalQty,
                totalRevenue = tuple.totalRev,
                transactionCount = tuple.txCount
            )
        }
    }

    /**
     * Get daily sales totals for charting.
     */
    suspend fun getDailySalesData(days: Int = 7): List<Pair<String, Double>> {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(days.toLong())
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val totals = transactionDao.getDailySalesTotals(startEpoch)

        // Fill in missing days with 0
        val result = mutableListOf<Pair<String, Double>>()
        var currentDate = startDate
        val totalsMap = totals.associate { it.day to it.total }

        while (!currentDate.isAfter(endDate)) {
            val epoch = currentDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            val total = totalsMap[epoch] ?: 0.0
            result.add(Pair(currentDate.toString(), total))
            currentDate = currentDate.plusDays(1)
        }

        return result
    }

    /**
     * Simple price elasticity estimation.
     * Uses log-log regression: ln(Q) = a + b * ln(P)
     * b = price elasticity
     */
    suspend fun priceElasticity(item: String, days: Int = 30): Double {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(days.toLong())
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val salesHistory = transactionDao.getItemSalesHistory(item, startEpoch)

        if (salesHistory.size < 5) return 0.0  // Not enough data

        // Simple OLS regression on log-transformed data
        val data = salesHistory.filter { it.quantity > 0 && it.totalAmount > 0 }
            .map { sale ->
                val unitPrice = sale.totalAmount / sale.quantity
                Pair(
                    Math.log(unitPrice),  // ln(P)
                    Math.log(sale.quantity)  // ln(Q)
                )
            }

        if (data.size < 3) return 0.0

        // Simple linear regression
        val n = data.size
        val sumX = data.sumOf { it.first }
        val sumY = data.sumOf { it.second }
        val sumXY = data.sumOf { it.first * it.second }
        val sumX2 = data.sumOf { it.first * it.first }

        val denominator = n * sumX2 - sumX * sumX
        if (denominator == 0.0) return 0.0

        return (n * sumXY - sumX * sumY) / denominator
    }

    /**
     * Get day-of-week sales pattern.
     * Returns average sales for each day of the week.
     */
    suspend fun getDayOfWeekPattern(days: Int = 28): Map<String, Double> {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(days.toLong())
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val totals = transactionDao.getDailySalesTotals(startEpoch)

        val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val dayTotals = mutableMapOf<Int, MutableList<Double>>()

        for (total in totals) {
            val date = java.time.Instant.ofEpochSecond(total.day)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
            val dayOfWeek = date.dayOfWeek.value - 1  // 0=Mon, 6=Sun
            dayTotals.getOrPut(dayOfWeek) { mutableListOf() }.add(total.total)
        }

        return dayNames.mapIndexed { index, name ->
            val sales = dayTotals[index] ?: emptyList()
            name to if (sales.isNotEmpty()) sales.average() else 0.0
        }.toMap()
    }

    /**
     * Get sales velocity (average daily sales over period).
     */
    suspend fun getSalesVelocity(days: Int = 7): Double {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(days.toLong())
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val totalSales = transactionDao.getSalesTotal(startEpoch, endEpoch)
        return totalSales / days
    }

    /**
     * Forecast next day sales based on moving average.
     */
    suspend fun forecastNextDaySales(): Double {
        return getSalesVelocity(7)  // Simple: use 7-day average
    }

    /**
     * Get profit margin percentage.
     */
    suspend fun getProfitMargin(days: Int = 7): Double {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(days.toLong())
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val sales = transactionDao.getSalesTotal(startEpoch, endEpoch)
        val profit = transactionDao.getProfit(startEpoch, endEpoch)

        return if (sales > 0) (profit / sales) * 100 else 0.0
    }
}
