package com.msaidizi.superagent.financial

import timber.log.Timber
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Cash Flow Predictor — forecasts cash position 7-30 days ahead.
 *
 * Uses Holt's double exponential smoothing (no seasonality required) to
 * predict daily income and expenses based on historical patterns.
 *
 * ## Algorithm
 * 1. Build daily net cash flow series (income - expenses) for last 28 days
 * 2. Apply Holt's linear trend model:
 *    - Level: `l_t = α * y_t + (1-α) * (l_{t-1} + b_{t-1})`
 *    - Trend: `b_t = β * (l_t - l_{t-1}) + (1-β) * b_{t-1}`
 * 3. Forecast: `ŷ_{t+h} = l_t + h * b_t`
 * 4. Predict income and expenses separately for richer insights
 * 5. Project cumulative cash position forward
 *
 * ## Academic Foundations
 * - **STA 244 (Time Series):** Holt's double exponential smoothing
 * - **STA 341 (Estimation):** Confidence intervals for forecasts
 * - **ECO 210 (Quantitative Methods):** Cash flow management
 *
 * All math is pure Kotlin — no LLM dependency.
 * Designed for 2GB devices: O(n) time, O(1) memory.
 *
 * @author Msaidizi Financial Team
 */
class CashFlowPredictor {

    companion object {
        private const val TAG = "CashFlowPredictor"

        // Holt's smoothing parameters
        private const val ALPHA = 0.3  // Level smoothing (0-1, higher = more reactive)
        private const val BETA = 0.1   // Trend smoothing (0-1)

        // Prediction parameters
        private const val LOOKBACK_DAYS = 28
        private const val MIN_DATA_DAYS = 5
        private const val MAX_FORECAST_DAYS = 30

        // Safety margin: warn when projected cash falls below this many days of expenses
        private const val SAFETY_MARGIN_DAYS = 3.0
    }

    // ═══════════════════════════════════════════════════════════════
    // CASH FLOW PREDICTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Predict cash flow for the next N days.
     *
     * @param currentCash Current cash on hand in KSh
     * @param dailyRevenues List of daily revenue for last 28 days (most recent last)
     * @param dailyExpenses List of daily expenses for last 28 days (most recent last)
     * @param knownUpcomingExpenses Known upcoming expenses (e.g., rent, school fees)
     *        Map of day-offset to amount (e.g., {7 to 5000.0} means KSh 5000 due in 7 days)
     * @param forecastDays How many days to forecast (default 14)
     * @return [CashFlowForecast] with prediction details
     */
    fun predict(
        currentCash: Double,
        dailyRevenues: List<Double>,
        dailyExpenses: List<Double>,
        knownUpcomingExpenses: Map<Int, Double> = emptyMap(),
        forecastDays: Int = 14
    ): CashFlowForecast {
        val safeForecastDays = forecastDays.coerceIn(1, MAX_FORECAST_DAYS)

        // Validate input data
        if (dailyRevenues.size < MIN_DATA_DAYS || dailyExpenses.size < MIN_DATA_DAYS) {
            return CashFlowForecast(
                message = "Hakuna data ya kutosha kutabiri mtiririko wa pesa. Rekodi mauzo yako kwa siku $MIN_DATA_DAYS au zaidi.",
                currentCash = currentCash,
                dailyBurnRate = 0.0,
                daysRemaining = -1,
                isHealthy = true,
                predictedIncome = 0.0,
                predictedExpenses = 0.0
            )
        }

        // Apply Holt's smoothing to revenue and expenses separately
        val revenueLevel = applyHoltSmoothing(dailyRevenues, ALPHA, BETA)
        val expenseLevel = applyHoltSmoothing(dailyExpenses, ALPHA, BETA)

        // Forecast next N days
        val predictedIncome = revenueLevel.first + revenueLevel.second * safeForecastDays
        val predictedExpenses = expenseLevel.first + expenseLevel.second * safeForecastDays

        // Project daily cash position
        var projectedCash = currentCash
        var daysUntilShortage = Int.MAX_VALUE
        val dailyBurnRate = expenseLevel.first - revenueLevel.first

        for (day in 1..safeForecastDays) {
            val dayRevenue = revenueLevel.first + revenueLevel.second * day
            val dayExpense = expenseLevel.first + expenseLevel.second * day

            projectedCash += dayRevenue - dayExpense

            // Add known upcoming expenses
            knownUpcomingExpenses[day]?.let { expense ->
                projectedCash -= expense
            }

            // Check if cash goes negative
            if (projectedCash < 0 && daysUntilShortage == Int.MAX_VALUE) {
                daysUntilShortage = day
            }
        }

        // Calculate how many days cash would last at current burn rate
        val daysRemaining = if (dailyBurnRate > 0 && currentCash > 0) {
            (currentCash / dailyBurnRate).toInt()
        } else if (dailyBurnRate <= 0) {
            Int.MAX_VALUE // Revenue >= expenses
        } else {
            0 // No cash and burning
        }

        val isHealthy = daysUntilShortage > safeForecastDays &&
                daysRemaining > SAFETY_MARGIN_DAYS

        // Build message
        val message = buildPredictionMessage(
            currentCash, projectedCash, daysUntilShortage,
            dailyBurnRate, predictedIncome, predictedExpenses,
            safeForecastDays, isHealthy
        )

        return CashFlowForecast(
            message = message,
            currentCash = currentCash,
            dailyBurnRate = dailyBurnRate,
            daysRemaining = min(daysRemaining, daysUntilShortage),
            isHealthy = isHealthy,
            predictedIncome = predictedIncome,
            predictedExpenses = predictedExpenses
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // HOLT'S DOUBLE EXPONENTIAL SMOOTHING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Apply Holt's double exponential smoothing to a time series.
     *
     * Returns a Pair of (level, trend) where:
     * - level: the smoothed value at the last data point
     * - trend: the estimated per-period change
     *
     * @param data Time series data (oldest first, newest last)
     * @param alpha Level smoothing parameter (0-1)
     * @param beta Trend smoothing parameter (0-1)
     * @return Pair(level, trend)
     */
    private fun applyHoltSmoothing(
        data: List<Double>,
        alpha: Double,
        beta: Double
    ): Pair<Double, Double> {
        if (data.isEmpty()) return Pair(0.0, 0.0)
        if (data.size == 1) return Pair(data[0], 0.0)

        // Initialize level and trend
        var level = data[0]
        var trend = data[1] - data[0]

        // Apply Holt's equations
        for (i in 1 until data.size) {
            val y = data[i]
            val prevLevel = level

            // Level equation: l_t = α * y_t + (1-α) * (l_{t-1} + b_{t-1})
            level = alpha * y + (1 - alpha) * (prevLevel + trend)

            // Trend equation: b_t = β * (l_t - l_{t-1}) + (1-β) * b_{t-1}
            trend = beta * (level - prevLevel) + (1 - beta) * trend
        }

        return Pair(level, trend)
    }

    // ═══════════════════════════════════════════════════════════════
    // MESSAGE GENERATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build a Swahili prediction message.
     */
    private fun buildPredictionMessage(
        currentCash: Double,
        projectedCash: Double,
        daysUntilShortage: Int,
        dailyBurnRate: Double,
        predictedIncome: Double,
        predictedExpenses: Double,
        forecastDays: Int,
        isHealthy: Boolean
    ): String {
        return buildString {
            append("🔮 Utabiri wa pesa kwa siku $forecastDays zijazo:\n\n")

            append("Sasa: KSh ${formatAmount(currentCash)}\n")
            append("Mapato yatatabiriwa: KSh ${formatAmount(predictedIncome)}\n")
            append("Matumizi yatatabiriwa: KSh ${formatAmount(predictedExpenses)}\n")
            append("Kiasi mwisho wa kipindi: KSh ${formatAmount(projectedCash)}\n\n")

            when {
                isHealthy -> {
                    append("✅ Pesa yako iko salama kwa siku $forecastDays zijazo.")
                }
                daysUntilShortage in 1..forecastDays -> {
                    append("⚠️ Tahadhari! Pesa zinakaribia kuisha siku ya $daysUntilShortage. ")
                    append("Fikiria kuongeza mauzo au kupunguza matumizi.")
                }
                daysUntilShortage == 0 -> {
                    append("🚨 Pesa zako zimeisha! Hatua za haraka zinahitajika.")
                }
                else -> {
                    append("⚠️ Mtiririko wa pesa si mzuri. Ongeza mauzo au punguza gharama.")
                }
            }
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
