package com.msaidizi.app.agent

import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset


/**
 * Business Pattern Tracker — learns and tracks user's business patterns.
 *
 * Builds on LearningAgent with deeper statistical analysis:
 * - Daily/weekly/monthly sales patterns
 * - Peak hours identification
 * - Best-selling items with trend analysis
 * - Profit margin tracking per product
 * - Seasonal pattern detection
 * - Business health scoring
 *
 * All analysis is code-based (no LLM) for 2GB device compatibility.
 * Uses exponential moving averages for smooth adaptation.
 *
 * Performance: All queries use indexed SQLite columns.
 * Memory: Stateless — all data in Room, ~0 additional RAM.
 */
class BusinessPatternTracker(
    private val transactionDao: TransactionDao,
    private val patternDao: PatternDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Smoothing factor for exponential moving averages
    // Higher = more weight on recent observations (faster adaptation)
    private companion object {
        const val EMA_ALPHA = 0.3          // Price/quantity smoothing
        const val PATTERN_ALPHA = 0.2      // Pattern confidence smoothing
        const val MIN_OBSERVATIONS = 3     // Minimum data points for a pattern
        const val PEAK_THRESHOLD = 1.3     // 30% above average = peak
    }

    // ═══════════════ DAILY / WEEKLY / MONTHLY PATTERNS ═══════════════

    /**
     * Analyze daily sales patterns over the last N weeks.
     * Returns average sales for each day of the week.
     *
     * Uses exponential moving average so recent weeks have more weight.
     * Pattern becomes reliable after ~2 weeks of data.
     */
    suspend fun analyzeDayOfWeekPatterns(weeks: Int = 4): Map<String, DayPattern> =
        withContext(Dispatchers.IO) {
            val endDate = LocalDate.now()
            val startDate = endDate.minusDays((weeks * 7).toLong())
            val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)

            val dailyTotals = transactionDao.getDailySalesTotals(startEpoch)
            if (dailyTotals.isEmpty()) {
                Timber.d("No daily sales data for day-of-week analysis")
                return@withContext emptyMap()
            }

            val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            val dayBuckets = Array(7) { mutableListOf<Double>() }

            for (total in dailyTotals) {
                val date = java.time.Instant.ofEpochSecond(total.day)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                val dayOfWeek = date.dayOfWeek.value - 1 // 0=Mon, 6=Sun
                dayBuckets[dayOfWeek].add(total.total)
            }

            val overallAvg = dailyTotals.map { it.total }.average()

            val result = mutableMapOf<String, DayPattern>()
            for (i in 0..6) {
                val sales = dayBuckets[i]
                if (sales.isEmpty()) continue

                val avg = sales.average()
                val isPeak = avg > overallAvg * PEAK_THRESHOLD
                val isLow = avg < overallAvg / PEAK_THRESHOLD

                result[dayNames[i]] = DayPattern(
                    dayOfWeek = i,
                    averageSales = avg,
                    observationCount = sales.size,
                    isPeakDay = isPeak,
                    isLowDay = isLow,
                    confidence = calculateConfidence(sales.size, weeks)
                )
            }

            // Store pattern
            storePattern(
                PatternType.DAY_OF_WEEK,
                "weekly_sales",
                mapOf("days" to result.mapKeys { it.key }),
                result.values.map { it.confidence }.average()
            )

            Timber.d("Day-of-week patterns: %s", result.map { "${it.key}=${it.value.averageSales.toInt()}" })
            result
        }

    /**
     * Analyze peak selling hours.
     * Returns which hours of the day have the most sales.
     *
     * Useful for:
     * - Knowing when to expect customers
     * - Planning restocking times
     * - Optimizing business hours
     */
    suspend fun analyzePeakHours(days: Int = 14): List<HourPattern> =
        withContext(Dispatchers.IO) {
            val endDate = LocalDate.now()
            val startDate = endDate.minusDays(days.toLong())
            val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

            val transactions = transactionDao.getTransactionsInRangeSuspend(startEpoch, endEpoch)
            if (transactions.isEmpty()) {
                Timber.d("No transactions for peak hours analysis")
                return@withContext emptyList()
            }

            // Group sales by hour
            val hourSales = Array(24) { mutableListOf<Double>() }
            val hourCounts = IntArray(24)

            for (tx in transactions.filter { it.type == TransactionType.SALE }) {
                val hour = java.time.Instant.ofEpochSecond(tx.createdAt)
                    .atZone(ZoneOffset.UTC)
                    .hour
                hourSales[hour].add(tx.totalAmount)
                hourCounts[hour]++
            }

            val totalSales = hourSales.sumOf { it.sum() }
            val peakThreshold = if (totalSales > 0) totalSales / 24 * PEAK_THRESHOLD else 0.0

            val result = (0..23).mapNotNull { hour ->
                val sales = hourSales[hour]
                if (sales.isEmpty()) return@mapNotNull null

                HourPattern(
                    hour = hour,
                    totalSales = sales.sum(),
                    transactionCount = hourCounts[hour],
                    averageSale = sales.average(),
                    isPeakHour = sales.sum() > peakThreshold,
                    confidence = calculateConfidence(hourCounts[hour], days)
                )
            }.sortedByDescending { it.totalSales }

            // Store pattern
            storePattern(
                PatternType.PEAK_HOURS,
                "hourly_distribution",
                mapOf("hours" to result.associate { "${it.hour}" to it.totalSales }),
                result.map { it.confidence }.average()
            )

            Timber.d("Peak hours: %s", result.filter { it.isPeakHour }.map { "${it.hour}:00" })
            result
        }

    // ═══════════════ PRODUCT ANALYSIS ═══════════════

    /**
     * Analyze product performance: revenue, margins, trends.
     * Returns ranked list of products by profitability.
     */
    suspend fun analyzeProductPerformance(days: Int = 30): List<ProductInsight> =
        withContext(Dispatchers.IO) {
            val endDate = LocalDate.now()
            val startDate = endDate.minusDays(days.toLong())
            val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

            val topItems = transactionDao.getTopSellingItems(startEpoch, endEpoch, 50)
            if (topItems.isEmpty()) {
                Timber.d("No items for product analysis")
                return@withContext emptyList()
            }

            val result = topItems.map { item ->
                val avgCost = transactionDao.getAverageCost(item.item)
                val margin = if (item.totalRev > 0) {
                    ((item.totalRev - avgCost * item.totalQty) / item.totalRev * 100)
                } else 0.0

                // Calculate sales velocity (transactions per day)
                val velocity = item.txCount.toDouble() / days

                ProductInsight(
                    item = item.item,
                    totalRevenue = item.totalRev,
                    totalQuantity = item.totalQty,
                    transactionCount = item.txCount,
                    averageCost = avgCost,
                    profitMargin = margin,
                    salesVelocity = velocity,
                    isTopSeller = false, // Set below
                    isHighMargin = margin > 30.0
                )
            }.sortedByDescending { it.totalRevenue }

            // Mark top 3 as top sellers
            val withTopMarked = result.mapIndexed { index, insight ->
                insight.copy(isTopSeller = index < 3)
            }

            Timber.d("Product analysis: %d items, top=%s",
                withTopMarked.size,
                withTopMarked.firstOrNull()?.item ?: "none"
            )
            withTopMarked
        }

    /**
     * Track price changes for an item over time.
     * Uses exponential moving average for smooth price tracking.
     */
    suspend fun trackPriceChange(item: String, newPrice: Double, quantity: Double = 1.0) =
        withContext(Dispatchers.IO) {
            val existing = patternDao.getPatternByKey(PatternType.PRICE_TREND, item)

            if (existing != null) {
                try {
                    val priceData = json.decodeFromString<PriceData>(existing.data)
                    val emaPrice = priceData.emaPrice * (1 - EMA_ALPHA) + newPrice * EMA_ALPHA

                    val updated = priceData.copy(
                        emaPrice = emaPrice,
                        lastPrice = newPrice,
                        minPrice = minOf(priceData.minPrice, newPrice),
                        maxPrice = maxOf(priceData.maxPrice, newPrice),
                        observations = priceData.observations + 1,
                        lastUpdated = System.currentTimeMillis() / 1000
                    )

                    patternDao.updatePattern(existing.copy(
                        data = json.encodeToString(updated),
                        confidence = calculateConfidence(updated.observations, 30),
                        updatedAt = System.currentTimeMillis() / 1000
                    ))

                    Timber.d("Price updated: %s EMA=%.0f last=%.0f (n=%d)",
                        item, emaPrice, newPrice, updated.observations)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse price data for %s, resetting", item)
                    createNewPricePattern(item, newPrice)
                }
            } else {
                createNewPricePattern(item, newPrice)
            }
        }

    private suspend fun createNewPricePattern(item: String, price: Double) {
        val priceData = PriceData(
            emaPrice = price,
            lastPrice = price,
            minPrice = price,
            maxPrice = price,
            observations = 1,
            lastUpdated = System.currentTimeMillis() / 1000
        )
        patternDao.insertPattern(BusinessPattern(
            patternType = PatternType.PRICE_TREND,
            data = json.encodeToString(priceData),
            confidence = 0.1
        ))
        Timber.d("New price pattern: %s = %.0f", item, price)
    }

    /**
     * Get price insight for an item.
     * Returns null if no price data exists.
     */
    suspend fun getPriceInsight(item: String): PriceInsight? = withContext(Dispatchers.IO) {
        val pattern = patternDao.getPatternByKey(PatternType.PRICE_TREND, item) ?: return@withContext null
        try {
            val data = json.decodeFromString<PriceData>(pattern.data)
            PriceInsight(
                item = item,
                averagePrice = data.emaPrice,
                lastPrice = data.lastPrice,
                minPrice = data.minPrice,
                maxPrice = data.maxPrice,
                observationCount = data.observations,
                confidence = pattern.confidence
            )
        } catch (e: Exception) {
            null
        }
    }

    // ═══════════════ PROFIT MARGIN TRACKING ═══════════════

    /**
     * Calculate profit margins per product over a period.
     * Returns products sorted by profit margin.
     */
    suspend fun analyzeProfitMargins(days: Int = 30): List<ProfitMarginInsight> =
        withContext(Dispatchers.IO) {
            val endDate = LocalDate.now()
            val startDate = endDate.minusDays(days.toLong())
            val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

            val topItems = transactionDao.getTopSellingItems(startEpoch, endEpoch, 50)

            topItems.mapNotNull { item ->
                val avgCost = transactionDao.getAverageCost(item.item)
                val revenue = item.totalRev
                val cost = avgCost * item.totalQty
                val profit = revenue - cost
                val margin = if (revenue > 0) (profit / revenue * 100) else 0.0

                if (revenue <= 0) return@mapNotNull null

                ProfitMarginInsight(
                    item = item.item,
                    revenue = revenue,
                    cost = cost,
                    profit = profit,
                    marginPercent = margin,
                    transactionCount = item.txCount
                )
            }.sortedByDescending { it.profit }
        }

    // ═══════════════ SEASONAL / TREND DETECTION ═══════════════

    /**
     * Detect weekly sales trend (rising, falling, stable).
     * Compares current week to previous week using moving averages.
     */
    suspend fun detectWeeklyTrend(): SalesTrend = withContext(Dispatchers.IO) {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(21) // 3 weeks of data
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val dailyTotals = transactionDao.getDailySalesTotals(startEpoch)
        if (dailyTotals.size < 7) {
            return@withContext SalesTrend(
                direction = Trend.INSUFFICIENT_DATA,
                currentWeekAvg = 0.0,
                previousWeekAvg = 0.0,
                changePercent = 0.0,
                confidence = 0.0
            )
        }

        val sorted = dailyTotals.sortedBy { it.day }
        val currentWeek = sorted.takeLast(7)
        val previousWeek = sorted.dropLast(7).takeLast(7)

        val currentAvg = currentWeek.map { it.total }.average()
        val previousAvg = if (previousWeek.isNotEmpty()) {
            previousWeek.map { it.total }.average()
        } else {
            currentAvg
        }

        val changePercent = if (previousAvg > 0) {
            ((currentAvg - previousAvg) / previousAvg * 100)
        } else 0.0

        val direction = when {
            changePercent > 10.0 -> Trend.RISING
            changePercent < -10.0 -> Trend.FALLING
            else -> Trend.STABLE
        }

        SalesTrend(
            direction = direction,
            currentWeekAvg = currentWeek.map { it.total }.average(),
            previousWeekAvg = previousWeek.map { it.total }.average(),
            changePercent = changePercent,
            confidence = calculateConfidence(dailyTotals.size, 21)
        )
    }

    /**
     * Generate a comprehensive business health score (0–100).
     *
     * Components:
     * - Profit margin (30%)
     * - Sales trend (20%)
     * - Cash flow consistency (20%)
     * - Product diversity (15%)
     * - Inventory management (15%)
     */
    suspend fun calculateBusinessHealthScore(): BusinessHealthScore =
        withContext(Dispatchers.IO) {
            val margin = transactionDao.getProfit(
                LocalDate.now().minusDays(30).atStartOfDay().toEpochSecond(ZoneOffset.UTC),
                LocalDate.now().plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            )
            val sales = transactionDao.getSalesTotal(
                LocalDate.now().minusDays(30).atStartOfDay().toEpochSecond(ZoneOffset.UTC),
                LocalDate.now().plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            )
            val marginPercent = if (sales > 0) (margin / sales * 100) else 0.0
            val marginScore = (marginPercent * 3).coerceIn(0.0, 30.0)

            val trend = detectWeeklyTrend()
            val trendScore = when (trend.direction) {
                Trend.RISING -> 20.0
                Trend.STABLE -> 15.0
                Trend.FALLING -> 5.0
                Trend.INSUFFICIENT_DATA -> 10.0
            }

            // Cash flow consistency: check how many of last 7 days had positive profit
            val cashFlowScore = calculateCashFlowScore()

            // Product diversity: more products = better score
            val topItems = transactionDao.getTopSellingItems(
                LocalDate.now().minusDays(30).atStartOfDay().toEpochSecond(ZoneOffset.UTC),
                LocalDate.now().plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC),
                50
            )
            val diversityScore = (topItems.size * 3.0).coerceIn(0.0, 15.0)

            // Inventory management: items with good stock levels
            val inventoryScore = 10.0 // Placeholder — would need inventory data

            val totalScore = marginScore + trendScore + cashFlowScore + diversityScore + inventoryScore

            BusinessHealthScore(
                totalScore = totalScore.coerceIn(0.0, 100.0),
                marginScore = marginScore,
                trendScore = trendScore,
                cashFlowScore = cashFlowScore,
                diversityScore = diversityScore,
                inventoryScore = inventoryScore,
                marginPercent = marginPercent,
                trend = trend.direction
            )
        }

    private suspend fun calculateCashFlowScore(): Double {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(7)
        var positiveDays = 0

        var date = startDate
        while (!date.isAfter(endDate)) {
            val start = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            val end = date.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            val profit = transactionDao.getProfit(start, end)
            if (profit > 0) positiveDays++
            date = date.plusDays(1)
        }

        return (positiveDays * 2.86).coerceIn(0.0, 20.0) // Max 20 points
    }

    // ═══════════════ CONTEXT GENERATION FOR LLM ═══════════════

    /**
     * Generate a context string for LLM prompt injection.
     * This is the key output of Level 2 adaptive learning —
     * personalized context that makes the LLM's advice relevant
     * to this specific user's business.
     *
     * @param maxTokens Approximate max tokens (3 chars ≈ 1 token for Swahili)
     */
    suspend fun generateContextForLLM(maxTokens: Int = 200): String =
        withContext(Dispatchers.IO) {
            val context = StringBuilder()
            val maxChars = maxTokens * 3

            // Product insights (most important for personalization)
            val products = analyzeProductPerformance(14)
            if (products.isNotEmpty()) {
                context.append("Bidhaa za mteja: ")
                context.append(products.take(5).joinToString(", ") {
                    "${it.item} (margin ${it.marginPercent.toInt()}%)"
                })
                context.append(". ")
            }

            // Peak hours
            val peakHours = analyzePeakHours(14)
            val peaks = peakHours.filter { it.isPeakHour }
            if (peaks.isNotEmpty()) {
                context.append("Masaa ya kazi: ")
                context.append(peaks.joinToString(", ") { "${it.hour}:00" })
                context.append(". ")
            }

            // Weekly trend
            val trend = detectWeeklyTrend()
            if (trend.direction != Trend.INSUFFICIENT_DATA) {
                context.append("Mwelekeo wa mauzo: ${trend.direction.name.lowercase()}")
                context.append(" (${trend.changePercent.toInt()}%). ")
            }

            // Day-of-week patterns
            val dayPatterns = analyzeDayOfWeekPatterns(4)
            val peakDays = dayPatterns.filter { it.value.isPeakDay }.keys
            if (peakDays.isNotEmpty()) {
                context.append("Siku bora: ${peakDays.joinToString(", ")}. ")
            }

            // Top price insights
            val topProduct = products.firstOrNull()
            if (topProduct != null) {
                val priceInsight = getPriceInsight(topProduct.item)
                if (priceInsight != null) {
                    context.append("Bei ya ${topProduct.item}: ")
                    context.append("avg ${priceInsight.averagePrice.toInt()}")
                    context.append(" (min ${priceInsight.minPrice.toInt()}")
                    context.append("-max ${priceInsight.maxPrice.toInt()}). ")
                }
            }

            // Truncate if too long
            val result = context.toString()
            if (result.length > maxChars) {
                result.take(maxChars)
            } else {
                result
            }
        }

    // ═══════════════ HELPERS ═══════════════

    /**
     * Calculate confidence based on number of observations.
     * More observations = higher confidence, with diminishing returns.
     * Uses logarithmic scaling: 3 obs → 0.3, 10 → 0.5, 30 → 0.7, 100 → 0.9
     */
    private fun calculateConfidence(observations: Int, maxPeriod: Int): Double {
        if (observations <= 0) return 0.0
        val ratio = observations.toDouble() / maxPeriod
        return (1.0 - 1.0 / (1.0 + ratio * 3)).coerceIn(0.0, 1.0)
    }

    private suspend fun storePattern(
        type: PatternType,
        key: String,
        data: Map<String, Any?>,
        confidence: Double
    ) {
        val dataJson = json.encodeToString(data.mapValues { it.value.toString() })
        val existing = patternDao.getPatternByKey(type, key)

        if (existing != null) {
            patternDao.updatePattern(existing.copy(
                data = dataJson,
                confidence = (existing.confidence * (1 - PATTERN_ALPHA)) + (confidence * PATTERN_ALPHA),
                updatedAt = System.currentTimeMillis() / 1000
            ))
        } else {
            patternDao.insertPattern(BusinessPattern(
                patternType = type,
                data = dataJson,
                confidence = confidence
            ))
        }
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/** Day-of-week sales pattern */
data class DayPattern(
    val dayOfWeek: Int,
    val averageSales: Double,
    val observationCount: Int,
    val isPeakDay: Boolean,
    val isLowDay: Boolean,
    val confidence: Double
)

/** Hour-of-day sales pattern */
data class HourPattern(
    val hour: Int,
    val totalSales: Double,
    val transactionCount: Int,
    val averageSale: Double,
    val isPeakHour: Boolean,
    val confidence: Double
)

/** Product performance insight */
data class ProductInsight(
    val item: String,
    val totalRevenue: Double,
    val totalQuantity: Double,
    val transactionCount: Int,
    val averageCost: Double,
    val profitMargin: Double,
    val salesVelocity: Double,
    val isTopSeller: Boolean,
    val isHighMargin: Boolean
)

/** Price tracking data (stored as JSON in patterns table) */
@kotlinx.serialization.Serializable
data class PriceData(
    val emaPrice: Double,
    val lastPrice: Double,
    val minPrice: Double,
    val maxPrice: Double,
    val observations: Int,
    val lastUpdated: Long
)

/** Price insight for a product */
data class PriceInsight(
    val item: String,
    val averagePrice: Double,
    val lastPrice: Double,
    val minPrice: Double,
    val maxPrice: Double,
    val observationCount: Int,
    val confidence: Double
)

/** Profit margin insight per product */
data class ProfitMarginInsight(
    val item: String,
    val revenue: Double,
    val cost: Double,
    val profit: Double,
    val marginPercent: Double,
    val transactionCount: Int
)

/** Weekly sales trend */
data class SalesTrend(
    val direction: Trend,
    val currentWeekAvg: Double,
    val previousWeekAvg: Double,
    val changePercent: Double,
    val confidence: Double
)

/** Business health score (0–100) */
data class BusinessHealthScore(
    val totalScore: Double,
    val marginScore: Double,
    val trendScore: Double,
    val cashFlowScore: Double,
    val diversityScore: Double,
    val inventoryScore: Double,
    val marginPercent: Double,
    val trend: Trend
)
