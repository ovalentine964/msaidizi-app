package com.msaidizi.app.agent.proactive

import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Cash Flow Predictor — forecasts tomorrow's expected income.
 *
 * Uses Holt's double exponential smoothing (no seasonality) to
 * predict daily income and expenses based on historical patterns.
 *
 * Algorithm:
 *   1. Build daily net cash flow series (income - expenses) for last 28 days
 *   2. Apply Holt's linear trend model:
 *      - Level: l_t = α * y_t + (1-α) * (l_{t-1} + b_{t-1})
 *      - Trend: b_t = β * (l_t - l_{t-1}) + (1-β) * b_{t-1}
 *   3. Forecast: ŷ_{t+h} = l_t + h * b_t  (h=1 for tomorrow)
 *   4. Also predicts income and expenses separately for richer insights
 *
 * Output example:
 *   "Kesho unatarajia KSh 3,200 kulingana na mwenendo wako"
 *   "Mapato ya kesho: ~KSh 5,000, Matumizi: ~KSh 1,800"
 *
 * @param transactionDao Transaction history
 */
class CashFlowPredictor(
    private val transactionDao: TransactionDao
) {
    companion object {
        // Holt's smoothing parameters
        private const val ALPHA = 0.3   // Level smoothing
        private const val BETA = 0.1    // Trend smoothing
        private const val LOOKBACK_DAYS = 28L
        private const val MIN_DATA_DAYS = 5
    }

    // ═══════════════ PREDICTION ═══════════════

    /**
     * Predict tomorrow's cash flow based on recent patterns.
     * Returns a detailed prediction with income, expenses, and net flow.
     */
    suspend fun predictTomorrow(): CashFlowPrediction = withContext(Dispatchers.IO) {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(LOOKBACK_DAYS)
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val transactions = transactionDao.getTransactionsInRangeSuspend(startEpoch, endEpoch)

        if (transactions.isEmpty()) {
            return@withContext CashFlowPrediction(
                predictedIncome = 0.0,
                predictedExpenses = 0.0,
                predictedNet = 0.0,
                message = "Hakuna data ya kutosha kutabiri mkondo wa pesa.",
                confidence = 0.0,
                trend = CashFlowTrend.INSUFFICIENT_DATA
            )
        }

        // Build daily series
        val dailyIncome = buildDailySeries(
            transactions = transactions,
            startDate = startDate,
            endDate = endDate,
            filter = { it.type == TransactionType.SALE || it.type == TransactionType.DEPOSIT || it.type == TransactionType.REFUND }
        )
        val dailyExpenses = buildDailySeries(
            transactions = transactions,
            startDate = startDate,
            endDate = endDate,
            filter = { it.type == TransactionType.PURCHASE || it.type == TransactionType.EXPENSE || it.type == TransactionType.WITHDRAWAL || it.type == TransactionType.FEE }
        )
        val dailyNet = dailyIncome.zip(dailyExpenses) { income, expense -> income - expense }

        // Apply Holt's smoothing
        val predictedIncome = if (dailyIncome.size >= MIN_DATA_DAYS) {
            holtForecast(dailyIncome)
        } else {
            dailyIncome.average()
        }

        val predictedExpenses = if (dailyExpenses.size >= MIN_DATA_DAYS) {
            holtForecast(dailyExpenses)
        } else {
            dailyExpenses.average()
        }

        val predictedNet = predictedIncome - predictedExpenses

        // Detect trend direction
        val trend = detectTrend(dailyNet)

        // Generate Swahili message
        val message = generateMessage(predictedIncome, predictedExpenses, predictedNet, trend)

        val confidence = calculateConfidence(dailyIncome.size)

        Timber.d(
            "Cash flow prediction: income=%.0f, expenses=%.0f, net=%.0f, trend=%s",
            predictedIncome, predictedExpenses, predictedNet, trend
        )

        CashFlowPrediction(
            predictedIncome = maxOf(predictedIncome, 0.0),
            predictedExpenses = maxOf(predictedExpenses, 0.0),
            predictedNet = predictedNet,
            message = message,
            confidence = confidence,
            trend = trend
        )
    }

    /**
     * Predict cash flow for a specific number of days ahead.
     * Uses multi-step Holt forecast.
     */
    suspend fun predictDaysAhead(days: Int): CashFlowPrediction = withContext(Dispatchers.IO) {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(LOOKBACK_DAYS)
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val transactions = transactionDao.getTransactionsInRangeSuspend(startEpoch, endEpoch)

        val dailyIncome = buildDailySeries(
            transactions, startDate, endDate,
            filter = { it.type == TransactionType.SALE || it.type == TransactionType.DEPOSIT || it.type == TransactionType.REFUND }
        )
        val dailyExpenses = buildDailySeries(
            transactions, startDate, endDate,
            filter = { it.type == TransactionType.PURCHASE || it.type == TransactionType.EXPENSE || it.type == TransactionType.WITHDRAWAL || it.type == TransactionType.FEE }
        )

        val predictedIncome = if (dailyIncome.size >= MIN_DATA_DAYS) {
            holtForecast(dailyIncome, stepsAhead = days)
        } else {
            dailyIncome.average()
        }

        val predictedExpenses = if (dailyExpenses.size >= MIN_DATA_DAYS) {
            holtForecast(dailyExpenses, stepsAhead = days)
        } else {
            dailyExpenses.average()
        }

        val predictedNet = predictedIncome - predictedExpenses
        val dailyNet = dailyIncome.zip(dailyExpenses) { i, e -> i - e }
        val trend = detectTrend(dailyNet)

        CashFlowPrediction(
            predictedIncome = maxOf(predictedIncome, 0.0),
            predictedExpenses = maxOf(predictedExpenses, 0.0),
            predictedNet = predictedNet,
            message = if (days == 1) {
                generateMessage(predictedIncome, predictedExpenses, predictedNet, trend)
            } else {
                "Baada ya siku $days, unatarajia kupata KSh ${formatKes(predictedNet)}."
            },
            confidence = calculateConfidence(dailyIncome.size),
            trend = trend
        )
    }

    // ═══════════════ HOLT'S DOUBLE EXPONENTIAL SMOOTHING ═══════════════

    /**
     * Apply Holt's double exponential smoothing and forecast.
     *
     * Level:    l_t = α * y_t + (1-α) * (l_{t-1} + b_{t-1})
     * Trend:    b_t = β * (l_t - l_{t-1}) + (1-β) * b_{t-1}
     * Forecast: ŷ_{t+h} = l_t + h * b_t
     *
     * @param data Daily values (ordered oldest to newest)
     * @param stepsAhead How many days ahead to forecast (default 1)
     * @return Forecasted value
     */
    private fun holtForecast(data: List<Double>, stepsAhead: Int = 1): Double {
        if (data.size < 2) return data.firstOrNull() ?: 0.0

        // Initialize level with first value
        var level = data[0]

        // Initialize trend with difference of first two values
        var trend = data[1] - data[0]

        // Apply smoothing through the series
        for (t in 1 until data.size) {
            val y = data[t]
            val prevLevel = level

            // Update level
            level = ALPHA * y + (1 - ALPHA) * (prevLevel + trend)

            // Update trend
            trend = BETA * (level - prevLevel) + (1 - BETA) * trend
        }

        // Forecast h steps ahead
        return level + stepsAhead * trend
    }

    // ═══════════════ TREND DETECTION ═══════════════

    /**
     * Detect the direction of the cash flow trend.
     * Uses the final trend component from Holt's model.
     */
    private fun detectTrend(dailyNet: List<Double>): CashFlowTrend {
        if (dailyNet.size < MIN_DATA_DAYS) return CashFlowTrend.INSUFFICIENT_DATA

        // Apply Holt's to get the trend component
        var level = dailyNet[0]
        var trend = dailyNet[1] - dailyNet[0]

        for (t in 1 until dailyNet.size) {
            val y = dailyNet[t]
            val prevLevel = level
            level = ALPHA * y + (1 - ALPHA) * (prevLevel + trend)
            trend = BETA * (level - prevLevel) + (1 - BETA) * trend
        }

        // Classify trend based on direction and magnitude
        val avgDaily = dailyNet.average()
        val relativeTrend = if (avgDaily != 0.0) trend / Math.abs(avgDaily) else trend

        return when {
            relativeTrend > 0.15 -> CashFlowTrend.IMPROVING
            relativeTrend < -0.15 -> CashFlowTrend.DECLINING
            else -> CashFlowTrend.STABLE
        }
    }

    // ═══════════════ DATA BUILDING ═══════════════

    /**
     * Build a daily totals series from transactions.
     * Fills gaps with 0.0 for continuous time series.
     */
    private fun buildDailySeries(
        transactions: List<Transaction>,
        startDate: LocalDate,
        endDate: LocalDate,
        filter: (Transaction) -> Boolean
    ): List<Double> {
        // Group by day
        val dailyTotals = mutableMapOf<Long, Double>()
        for (tx in transactions.filter(filter)) {
            val dayEpoch = (tx.createdAt / 86400) * 86400
            dailyTotals[dayEpoch] = (dailyTotals[dayEpoch] ?: 0.0) + tx.totalAmount
        }

        // Build continuous series
        val series = mutableListOf<Double>()
        var date = startDate
        while (!date.isAfter(endDate)) {
            val dayEpoch = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            series.add(dailyTotals[dayEpoch] ?: 0.0)
            date = date.plusDays(1)
        }

        return series
    }

    // ═══════════════ MESSAGE GENERATION ═══════════════

    /**
     * Generate a Swahili prediction message.
     */
    private fun generateMessage(
        income: Double,
        expenses: Double,
        net: Double,
        trend: CashFlowTrend
    ): String {
        val netFormatted = formatKes(net)
        val incomeFormatted = formatKes(income)
        val expensesFormatted = formatKes(expenses)

        val trendSuffix = when (trend) {
            CashFlowTrend.IMPROVING -> " Mwenendo wako unaenda vizuri!"
            CashFlowTrend.DECLINING -> " Mwenendo wako unashuka — jiangalie."
            CashFlowTrend.STABLE -> ""
            CashFlowTrend.INSUFFICIENT_DATA -> ""
        }

        return when {
            net > 0 -> "Kesho unatarajia kupata KSh $netFormatted. " +
                    "Mapato: ~KSh $incomeFormatted, Matumizi: ~KSh $expensesFormatted.$trendSuffix"
            net < 0 -> "Kesho unatarajia kutumia zaidi ya unavyopata. " +
                    "Mapato: ~KSh $incomeFormatted, Matumizi: ~KSh $expensesFormatted. " +
                    "Punguza matumizi.$trendSuffix"
            else -> "Kesho mkondo wa pesa utakuwa sawa. " +
                    "Mapato: ~KSh $incomeFormatted, Matumizi: ~KSh $expensesFormatted.$trendSuffix"
        }
    }

    // ═══════════════ HELPERS ═══════════════

    private fun calculateConfidence(dataPoints: Int): Double {
        return (1.0 - 1.0 / (1.0 + dataPoints.toDouble() / 14.0)).coerceIn(0.1, 0.95)
    }

    private fun formatKes(amount: Double): String {
        val rounded = Math.round(kotlin.math.abs(amount))
        return if (rounded >= 1000) {
            String.format("%,d", rounded)
        } else {
            rounded.toString()
        }
    }
}

/**
 * Cash flow prediction result.
 */
data class CashFlowPrediction(
    val predictedIncome: Double,
    val predictedExpenses: Double,
    val predictedNet: Double,
    val message: String,
    val confidence: Double,
    val trend: CashFlowTrend
) {
    /** Is the predicted cash flow positive? */
    val isPositive: Boolean get() = predictedNet > 0

    /** Formatted net amount */
    val netFormatted: String
        get() {
            val abs = Math.round(kotlin.math.abs(predictedNet))
            val formatted = if (abs >= 1000) String.format("%,d", abs) else abs.toString()
            return if (predictedNet >= 0) "KSh $formatted" else "-KSh $formatted"
        }
}

/**
 * Cash flow trend direction.
 */
enum class CashFlowTrend {
    IMPROVING,   // Income growing faster than expenses
    DECLINING,   // Expenses growing faster than income
    STABLE,      // No significant change
    INSUFFICIENT_DATA  // Not enough data to determine
}
