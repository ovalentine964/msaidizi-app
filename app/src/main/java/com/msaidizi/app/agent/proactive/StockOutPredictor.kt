package com.msaidizi.app.agent.proactive

import com.msaidizi.app.core.database.InventoryDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Stock-Out Predictor — predicts when inventory items will run out.
 *
 * Uses Holt-Winters exponential smoothing on sales velocity to
 * forecast daily demand, then divides current stock by the forecast
 * to estimate days until stockout.
 *
 * Algorithm:
 *   1. Build daily sales quantity series per item (last 28 days)
 *   2. Apply Holt-Winters triple exponential smoothing:
 *      - Level (l_t): smoothed value
 *      - Trend (b_t): smoothed slope
 *      - Seasonal (s_t): day-of-week pattern (period=7)
 *   3. Forecast next day's demand
 *   4. days_until_stockout = current_stock / forecasted_daily_demand
 *   5. Alert if < 3 days remaining
 *
 * All math is pure Kotlin — no LLM dependency.
 * Designed for 2GB devices: O(n) time, O(1) memory per item.
 *
 * @param inventoryDao Inventory data (stock levels)
 * @param transactionDao Transaction history (sales velocity)
 */
class StockOutPredictor(
    private val inventoryDao: InventoryDao,
    private val transactionDao: TransactionDao
) {
    companion object {
        // Holt-Winters smoothing parameters
        private const val ALPHA = 0.3   // Level smoothing (0-1, higher = more reactive)
        private const val BETA = 0.1    // Trend smoothing (0-1)
        private const val GAMMA = 0.2   // Seasonal smoothing (0-1)
        private const val SEASON_LENGTH = 7  // Weekly seasonality

        // Alert thresholds
        private const val STOCKOUT_ALERT_DAYS = 3.0
        private const val MIN_DATA_DAYS = 7
        private const val LOOKBACK_DAYS = 28L
    }

    // ═══════════════ PREDICTION ═══════════════

    /**
     * Predict stock-out for all inventory items.
     * Returns a list of alerts for items at risk.
     */
    suspend fun predictAll(): List<StockOutPrediction> = withContext(Dispatchers.IO) {
        val items = inventoryDao.getInStockItems()
        if (items.isEmpty()) return@withContext emptyList()

        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(LOOKBACK_DAYS)
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val allTransactions = transactionDao.getTransactionsInRangeSuspend(startEpoch, endEpoch)
        val salesTransactions = allTransactions.filter { it.type == TransactionType.SALE }

        items.mapNotNull { item ->
            // Build daily sales quantity series for this item
            val dailySales = buildDailySalesSeries(
                item = item.item,
                salesTransactions = salesTransactions,
                startDate = startDate,
                endDate = endDate
            )

            // Need minimum data for meaningful prediction
            if (dailySales.size < MIN_DATA_DAYS) {
                // Fall back to simple average if insufficient data
                val simpleAvg = if (dailySales.isNotEmpty()) dailySales.average() else 0.0
                if (simpleAvg > 0 && item.currentStock > 0) {
                    val daysUntil = item.currentStock / simpleAvg
                    if (daysUntil < STOCKOUT_ALERT_DAYS) {
                        return@mapNotNull createPrediction(
                            item = item.item,
                            currentStock = item.currentStock,
                            dailyDemand = simpleAvg,
                            daysUntilStockout = daysUntil,
                            confidence = 0.3,
                            isLowData = true
                        )
                    }
                }
                return@mapNotNull null
            }

            // Apply Holt-Winters exponential smoothing
            val forecast = holtWintersForecast(dailySales)

            if (forecast <= 0.0) return@mapNotNull null

            val daysUntilStockout = item.currentStock / forecast

            if (daysUntilStockout < STOCKOUT_ALERT_DAYS) {
                createPrediction(
                    item = item.item,
                    currentStock = item.currentStock,
                    dailyDemand = forecast,
                    daysUntilStockout = daysUntilStockout,
                    confidence = calculateConfidence(dailySales.size),
                    isLowData = false
                )
            } else null
        }.sortedBy { it.daysUntilStockout }
    }

    /**
     * Predict stock-out for a specific item.
     */
    suspend fun predictForItem(itemName: String): StockOutPrediction? = withContext(Dispatchers.IO) {
        val item = inventoryDao.getItem(itemName) ?: return@withContext null
        if (item.currentStock <= 0) return@withContext null

        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(LOOKBACK_DAYS)
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val salesTransactions = transactionDao.getTransactionsInRangeSuspend(startEpoch, endEpoch)
            .filter { it.type == TransactionType.SALE && it.item.equals(itemName, ignoreCase = true) }

        val dailySales = buildDailySalesSeries(
            item = itemName,
            salesTransactions = salesTransactions,
            startDate = startDate,
            endDate = endDate
        )

        if (dailySales.isEmpty()) return@withContext null

        val forecast = if (dailySales.size >= MIN_DATA_DAYS) {
            holtWintersForecast(dailySales)
        } else {
            dailySales.average()
        }

        if (forecast <= 0) return@withContext null

        val daysUntilStockout = item.currentStock / forecast

        createPrediction(
            item = itemName,
            currentStock = item.currentStock,
            dailyDemand = forecast,
            daysUntilStockout = daysUntilStockout,
            confidence = calculateConfidence(dailySales.size),
            isLowData = dailySales.size < MIN_DATA_DAYS
        )
    }

    // ═══════════════ HOLT-WINTERS EXPONENTIAL SMOOTHING ═══════════════

    /**
     * Apply Holt-Winters triple exponential smoothing and forecast next value.
     *
     * The additive form:
     *   Level:    l_t = α * (y_t - s_{t-m}) + (1-α) * (l_{t-1} + b_{t-1})
     *   Trend:    b_t = β * (l_t - l_{t-1}) + (1-β) * b_{t-1}
     *   Season:   s_t = γ * (y_t - l_t) + (1-γ) * s_{t-m}
     *   Forecast: ŷ_{t+1} = l_t + b_t + s_{t+1-m}
     *
     * @param data Daily sales quantities (ordered oldest to newest)
     * @return Forecasted value for the next day
     */
    private fun holtWintersForecast(data: List<Double>): Double {
        val n = data.size
        if (n < SEASON_LENGTH * 2) return data.average()

        // Initialize level as average of first season
        var level = data.take(SEASON_LENGTH).average()

        // Initialize trend as average change across first two seasons
        val firstSeasonAvg = data.take(SEASON_LENGTH).average()
        val secondSeasonAvg = data.drop(SEASON_LENGTH).take(SEASON_LENGTH).average()
        var trend = (secondSeasonAvg - firstSeasonAvg) / SEASON_LENGTH

        // Initialize seasonal indices
        val seasonal = DoubleArray(SEASON_LENGTH)
        for (i in 0 until SEASON_LENGTH) {
            seasonal[i] = data[i] - level
        }

        // Apply smoothing through the series
        for (t in SEASON_LENGTH until n) {
            val seasonIdx = t % SEASON_LENGTH
            val y = data[t]

            val prevLevel = level
            val prevTrend = trend

            // Update level
            level = ALPHA * (y - seasonal[seasonIdx]) + (1 - ALPHA) * (prevLevel + prevTrend)

            // Update trend
            trend = BETA * (level - prevLevel) + (1 - BETA) * prevTrend

            // Update seasonal
            seasonal[seasonIdx] = GAMMA * (y - level) + (1 - GAMMA) * seasonal[seasonIdx]
        }

        // Forecast next day
        val nextSeasonIdx = n % SEASON_LENGTH
        val forecast = level + trend + seasonal[nextSeasonIdx]

        // Sales quantity can't be negative
        return maxOf(forecast, 0.0)
    }

    // ═══════════════ DATA BUILDING ═══════════════

    /**
     * Build a daily sales quantity series for an item.
     * Returns array of quantities sold per day (0 for days with no sales).
     */
    private fun buildDailySalesSeries(
        item: String,
        salesTransactions: List<com.msaidizi.app.core.model.Transaction>,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<Double> {
        // Group sales by day
        val salesByDay = mutableMapOf<Long, Double>()
        for (tx in salesTransactions) {
            if (!tx.item.equals(item, ignoreCase = true)) continue
            val dayEpoch = (tx.createdAt / 86400) * 86400  // Floor to day
            salesByDay[dayEpoch] = (salesByDay[dayEpoch] ?: 0.0) + tx.quantity
        }

        // Build continuous series (fill gaps with 0)
        val series = mutableListOf<Double>()
        var date = startDate
        while (!date.isAfter(endDate)) {
            val dayEpoch = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            series.add(salesByDay[dayEpoch] ?: 0.0)
            date = date.plusDays(1)
        }

        return series
    }

    // ═══════════════ HELPERS ═══════════════

    private fun createPrediction(
        item: String,
        currentStock: Double,
        dailyDemand: Double,
        daysUntilStockout: Double,
        confidence: Double,
        isLowData: Boolean
    ): StockOutPrediction {
        val daysRounded = daysUntilStockout.toInt()
        val message = when {
            daysRounded <= 0 -> "$item zako zimeisha! Ongeza stock sasa hivi!"
            daysRounded == 1 -> "$item zako zitaisha kesho — uongeze stock!"
            daysRounded == 2 -> "$item zako zitaisha siku mbili zijazo — andaa kununua zaidi."
            else -> "$item zitakamilika baada ya siku $daysRounded."
        }

        return StockOutPrediction(
            item = item,
            currentStock = currentStock,
            dailyDemand = dailyDemand,
            daysUntilStockout = daysUntilStockout,
            alertMessage = message,
            confidence = confidence,
            isLowData = isLowData
        )
    }

    private fun calculateConfidence(dataPoints: Int): Double {
        // More data = higher confidence, diminishing returns
        return (1.0 - 1.0 / (1.0 + dataPoints.toDouble() / 14.0)).coerceIn(0.1, 0.95)
    }
}

/**
 * Stock-out prediction result.
 */
data class StockOutPrediction(
    val item: String,
    val currentStock: Double,
    val dailyDemand: Double,
    val daysUntilStockout: Double,
    val alertMessage: String,
    val confidence: Double,
    val isLowData: Boolean
) {
    /** Is this item at critical stock level? (< 3 days) */
    val isCritical: Boolean get() = daysUntilStockout < 3.0

    /** Formatted days string for display */
    val daysDisplay: String
        get() = when {
            daysUntilStockout < 1.0 -> "leo"
            daysUntilStockout < 2.0 -> "kesho"
            else -> "siku ${daysUntilStockout.toInt()}"
        }
}
