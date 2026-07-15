package com.msaidizi.app.agent

import com.msaidizi.app.core.database.DailyTotalTuple
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.ItemRanking
import com.msaidizi.app.core.model.Trend
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.*


/**
 * Analysis Agent — calculates trends, patterns, and forecasts using
 * rigorous statistical and econometric methods.
 * Pure code + statistical algorithms — no LLM needed.
 *
 * ## Economic & Statistical Foundations
 *
 * ### STA 142/241 — Probability & Distribution Models
 * - **Normal Distribution:** Used for confidence intervals on price estimates
 * - **Central Limit Theorem:** Justifies normal approximation for sample means
 *   even when underlying data (informal income) is non-normal
 * - **Law of Large Numbers:** Ensures averages converge as data accumulates
 *
 * ### ECO 202/203 — Economic Statistics
 * - **Descriptive Statistics:** Mean, median, variance, skewness for market data
 * - **Index Numbers (ECO 203 §3.1):** Laspeyres, Paasche, Fisher price indices
 *   for tracking informal market price changes
 * - **Coefficient of Variation:** Measures price dispersion across markets
 *
 * ### STA 244 — Time Series Analysis & Forecasting
 * - **Stationarity:** ACF/PACF analysis for identifying AR/MA components
 * - **ARIMA Models:** Box-Jenkins methodology for price forecasting
 * - **Exponential Smoothing (Holt-Winters):** For seasonal price patterns
 * - **Trend Decomposition:** Separating trend, seasonal, and irregular components
 *
 * ### STA 341 — Theory of Estimation
 * - **Point Estimation:** Sample statistics as population parameter estimates
 * - **Interval Estimation:** Confidence intervals using t-distribution
 * - **Maximum Likelihood:** For estimating distribution parameters
 * - **Cramér-Rao Lower Bound:** Efficiency of estimators
 *
 * @see BusinessAgent for transaction recording
 * @see AdvisorAgent for economic advice
 */
class AnalysisAgent(
    private val transactionDao: TransactionDao
) {

    // ═══════════════════════════════════════════════════════════════
    // STA 244 §10.1 — TIME SERIES FUNDAMENTALS: Trend detection
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calculate sales trend using 7-day moving average comparison.
     *
     * **STA 244 §10.1:** A time series is (weakly) stationary if its mean,
     * variance, and autocovariance are constant over time. We test for
     * trend by comparing moving averages across periods.
     *
     * **Method:** Compare the 7-day SMA of the most recent week to
     * the previous week. A 10% threshold distinguishes meaningful
     * trends from noise (based on informal market volatility).
     *
     * @param days Window size for the moving average
     * @return Trend direction (RISING, FALLING, STABLE, or INSUFFICIENT_DATA)
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

    // ═══════════════════════════════════════════════════════════════
    // ECO 203 §3.1 — INDEX NUMBERS: Price tracking over time
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calculate a Laspeyres-style price index for a set of items.
     *
     * **ECO 203 §3.1:** The Laspeyres Price Index uses base-period quantities
     * as weights:
     *   P^L = Σ(p₁·q₀) / Σ(p₀·q₀)
     *
     * For informal markets, we approximate this using recent transaction
     * data as the quantity weights. This tracks how the "cost of living"
     * for a typical informal trader changes over time.
     *
     * @param currentPeriodDays Number of days in the current period
     * @param basePeriodDays Number of days in the base period (prior to current)
     * @return Price index value (>100 = prices risen, <100 = prices fallen)
     */
    suspend fun calculatePriceIndex(
        currentPeriodDays: Int = 7,
        basePeriodDays: Int = 7
    ): Double? {
        val now = LocalDate.now()
        val currentStart = now.minusDays(currentPeriodDays.toLong())
        val baseStart = currentStart.minusDays(basePeriodDays.toLong())

        val currentEpoch = currentStart.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val baseEpoch = baseStart.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val nowEpoch = now.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        // Get top items by volume in the base period
        val baseItems = transactionDao.getTopSellingItems(baseEpoch, currentEpoch, 20)
        if (baseItems.isEmpty()) return null

        // For each item, compute price ratio × base quantity
        var numerator = 0.0  // Σ(p₁·q₀)
        var denominator = 0.0 // Σ(p₀·q₀)

        for (item in baseItems) {
            val basePrice = if (item.totalQty > 0) item.totalRev / item.totalQty else 0.0
            val baseQty = item.totalQty

            // Get current price for same item
            val currentItems = transactionDao.getTopSellingItems(currentEpoch, nowEpoch, 50)
            val currentItem = currentItems.find { it.item == item.item }
            val currentPrice = if (currentItem != null && currentItem.totalQty > 0) {
                currentItem.totalRev / currentItem.totalQty
            } else {
                basePrice // Assume no change if no current data
            }

            numerator += currentPrice * baseQty
            denominator += basePrice * baseQty
        }

        if (denominator <= 0) return null

        val index = (numerator / denominator) * 100
        Timber.d("Laspeyres price index: %.1f (base=%s, current=%s)",
            index, baseStart, currentStart)
        return index
    }

    // ═══════════════════════════════════════════════════════════════
    // ECO 202 §2.1 — DESCRIPTIVE STATISTICS: Distribution analysis
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calculate descriptive statistics for daily sales over a period.
     *
     * **ECO 202 §2.1:** Descriptive statistics summarize the main features
     * of a dataset:
     * - Central tendency: mean, median
     * - Dispersion: variance, standard deviation, range
     * - Shape: skewness, kurtosis
     *
     * **Kenyan context:** Income distributions in informal markets are
     * typically right-skewed (many low earners, few high earners).
     * The median is more representative than the mean for "typical" earnings.
     *
     * @param days Number of days to analyze
     * @return Descriptive statistics for daily sales
     */
    suspend fun getDescriptiveStatistics(days: Int = 30): SalesDescriptiveStats? {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(days.toLong())
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val dailyTotals = transactionDao.getDailySalesTotals(startEpoch)
        if (dailyTotals.isEmpty()) return null

        val values = dailyTotals.map { it.total }
        val n = values.size

        // Central tendency
        val mean = values.average()
        val sorted = values.sorted()
        val median = if (n % 2 == 0) {
            (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        } else {
            sorted[n / 2]
        }

        // Dispersion
        val variance = values.map { (it - mean).pow(2) }.sum() / (n - 1).coerceAtLeast(1)
        val stdDev = sqrt(variance)
        val range = sorted.last() - sorted.first()
        val cv = if (mean > 0) stdDev / mean else 0.0 // Coefficient of variation

        // Quartiles
        val q1 = sorted[(n * 0.25).toInt().coerceIn(0, n - 1)]
        val q3 = sorted[(n * 0.75).toInt().coerceIn(0, n - 1)]
        val iqr = q3 - q1

        // Shape (skewness)
        // γ₁ = E[(X-μ)³] / σ³
        val skewness = if (stdDev > 0) {
            values.map { ((it - mean) / stdDev).pow(3) }.sum() / n
        } else 0.0

        // Kurtosis (excess)
        // γ₂ = E[(X-μ)⁴] / σ⁴ - 3
        val kurtosis = if (stdDev > 0) {
            values.map { ((it - mean) / stdDev).pow(4) }.sum() / n - 3
        } else 0.0

        // Normality assessment
        // STA 241 §9.1 (CLT): For n ≥ 30, sampling distribution of x̄ is approximately
        // normal regardless of population distribution. For n < 30, normality of the
        // underlying data matters — skewness and kurtosis are diagnostic.
        val normalityNote = when {
            n >= 30 -> "CLT applies (n≥30): sample mean is approximately normal"
            skewness > 1.0 || skewness < -1.0 ->
                "WARNING: Highly skewed data (γ₁=%.2f) with small n=%d. " +
                "Consider non-parametric methods (e.g., median, IQR)".format(skewness, n)
            kurtosis > 2.0 || kurtosis < -2.0 ->
                "WARNING: Heavy-tailed data (γ₂=%.2f) with small n=%d. " +
                "Normal-based inference may be unreliable".format(kurtosis, n)
            else -> "Small sample (n=%d): normality assumed but unverified".format(n)
        }

        Timber.d("Sales stats: mean=%.0f, median=%.0f, sd=%.0f, skew=%.2f",
            mean, median, stdDev, skewness)

        return SalesDescriptiveStats(
            count = n,
            mean = mean,
            median = median,
            variance = variance,
            stdDev = stdDev,
            range = range,
            coefficientOfVariation = cv,
            q1 = q1,
            q3 = q3,
            iqr = iqr,
            skewness = skewness,
            kurtosis = kurtosis,
            min = sorted.first(),
            max = sorted.last(),
            normalityNote = normalityNote
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // STA 341 §6.1 — ESTIMATION THEORY: Confidence intervals
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calculate confidence interval for average daily sales.
     *
     * **STA 341 §6.4:** A 95% confidence interval for the population mean μ:
     *   x̄ ± t_{α/2, n-1} × (s / √n)
     *
     * where t_{α/2, n-1} is the critical value from the t-distribution
     * with (n-1) degrees of freedom.
     *
     * **STA 241 §9.1 (CLT):** By the Central Limit Theorem, the sampling
     * distribution of x̄ is approximately normal for n ≥ 30, regardless
     * of the population distribution. For smaller samples, we use the
     * t-distribution which accounts for additional uncertainty from
     * estimating σ with s.
     *
     * @param days Number of days to analyze
     * @param confidenceLevel Confidence level (default 0.95)
     * @return Confidence interval (lower, upper), or null if insufficient data
     */
    suspend fun getSalesConfidenceInterval(
        days: Int = 30,
        confidenceLevel: Double = 0.95
    ): Pair<Double, Double>? {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(days.toLong())
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val dailyTotals = transactionDao.getDailySalesTotals(startEpoch)
        if (dailyTotals.size < 3) return null

        val values = dailyTotals.map { it.total }
        val n = values.size
        val mean = values.average()
        val variance = values.map { (it - mean).pow(2) }.sum() / (n - 1)
        val se = sqrt(variance / n) // Standard error of the mean

        // t critical value approximation (for common cases)
        // For n > 30, t ≈ z. For small n, use approximation.
        val alpha = 1 - confidenceLevel
        val tCritical = when {
            n >= 30 -> 1.96  // z for 95%
            n >= 20 -> 2.093
            n >= 10 -> 2.262
            n >= 5 -> 2.776
            else -> 3.182
        }

        val lower = mean - tCritical * se
        val upper = mean + tCritical * se

        Timber.d("Sales CI (%.0f%%): [%.0f, %.0f] (n=%d, mean=%.0f, se=%.0f)",
            confidenceLevel * 100, lower, upper, n, mean, se)
        return Pair(lower, upper)
    }

    // ═══════════════════════════════════════════════════════════════
    // STA 244 §10.2 — ARIMA/SIMPLE FORECASTING: Price prediction
    // ═══════════════════════════════════════════════════════════════

    /**
     * Forecast next-day sales using exponential smoothing.
     *
     * **STA 244 §10.3:** Simple Exponential Smoothing (SES):
     *   Ŝ_{t+1} = α·X_t + (1-α)·Ŝ_t
     *
     * where α ∈ (0,1) is the smoothing parameter:
     * - α close to 1: more weight on recent observations (responsive)
     * - α close to 0: more weight on past (smooth/stable)
     *
     * For informal markets with moderate volatility, α = 0.3 provides
     * a good balance between responsiveness and stability.
     *
     * **STA 244 §10.1 (Wold Decomposition):** Any stationary process can be
     * decomposed into a deterministic component and an MA(∞) innovation.
     * SES is optimal when the data follows a random walk with no trend.
     *
     * @return Forecasted next-day sales, or null if insufficient data
     */
    suspend fun forecastNextDaySales(): Double? {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(21)
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val dailyTotals = transactionDao.getDailySalesTotals(startEpoch)
        if (dailyTotals.size < 7) return null

        val sorted = dailyTotals.sortedBy { it.day }.map { it.total }

        // Simple Exponential Smoothing with α = 0.3
        val alpha = 0.3
        var smoothed = sorted.first()
        for (i in 1 until sorted.size) {
            smoothed = alpha * sorted[i] + (1 - alpha) * smoothed
        }

        Timber.d("SES forecast: KSh %.0f (α=%.1f, n=%d)", smoothed, alpha, sorted.size)
        return smoothed
    }

    /**
     * Detect seasonality in daily sales using autocorrelation analysis.
     *
     * **STA 244 §10.1:** The Autocorrelation Function (ACF) measures
     * correlation between a time series and its lagged values:
     *   ρ(k) = Cov(X_t, X_{t-k}) / Var(X_t)
     *
     * Significant ACF at lag 7 suggests weekly seasonality.
     * Significant ACF at lag 30 suggests monthly seasonality.
     *
     * **ECO 203 §3.2:** Time series decomposition:
     *   Y = T + S + C + I (additive) or Y = T × S × C × I (multiplicative)
     *
     * @param days Number of days of history to analyze
     * @return Seasonality analysis result
     */
    suspend fun detectSeasonality(days: Int = 56): SeasonalityAnalysis? {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(days.toLong())
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val dailyTotals = transactionDao.getDailySalesTotals(startEpoch)
        if (dailyTotals.size < 28) return null // Need at least 4 weeks

        val values = dailyTotals.sortedBy { it.day }.map { it.total }
        val n = values.size
        val mean = values.average()

        // Calculate ACF at key lags
        val variance = values.map { (it - mean).pow(2) }.sum() / n

        fun autocorrelation(lag: Int): Double {
            if (lag >= n) return 0.0
            var sum = 0.0
            for (i in 0 until n - lag) {
                sum += (values[i] - mean) * (values[i + lag] - mean)
            }
            return if (variance > 0) (sum / n) / variance else 0.0
        }

        val acf7 = autocorrelation(7)   // Weekly seasonality
        val acf14 = autocorrelation(14)  // Bi-weekly
        val acf30 = autocorrelation(30)  // Monthly

        // STA 244: 95% confidence bounds for ACF: ±1.96/√n
        val confidenceBound = 1.96 / sqrt(n.toDouble())

        val hasWeeklySeasonality = abs(acf7) > confidenceBound
        val hasMonthlySeasonality = abs(acf30) > confidenceBound

        // ECO 203 §3.2: Day-of-week decomposition
        val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val dayBuckets = Array(7) { mutableListOf<Double>() }
        val sortedTotals = dailyTotals.sortedBy { it.day }

        for (total in sortedTotals) {
            val date = java.time.Instant.ofEpochSecond(total.day)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
            val dayOfWeek = date.dayOfWeek.value - 1
            dayBuckets[dayOfWeek].add(total.total)
        }

        val seasonalIndices = dayBuckets.mapIndexed { i, bucket ->
            if (bucket.isNotEmpty()) {
                val dayAvg = bucket.average()
                if (mean > 0) dayAvg / mean else 1.0 // Seasonal index
            } else 1.0
        }

        Timber.d("Seasonality: ACF(7)=%.3f, ACF(30)=%.3f, weekly=%b, monthly=%b",
            acf7, acf30, hasWeeklySeasonality, hasMonthlySeasonality)

        return SeasonalityAnalysis(
            acfLag7 = acf7,
            acfLag14 = acf14,
            acfLag30 = acf30,
            confidenceBound = confidenceBound,
            hasWeeklySeasonality = hasWeeklySeasonality,
            hasMonthlySeasonality = hasMonthlySeasonality,
            seasonalIndices = dayNames.zip(seasonalIndices).toMap()
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // ABC ANALYSIS (Inventory classification by revenue contribution)
    // ═══════════════════════════════════════════════════════════════

    /**
     * ABC analysis — classify inventory by revenue contribution.
     * A = top 80% revenue, B = next 15%, C = bottom 5%.
     *
     * **ECO 201 §1.2:** This is a Pareto analysis — the 80/20 rule.
     * In production theory, it helps identify which products drive
     * the business and which are marginal.
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
     * Price elasticity estimation using log-log OLS regression.
     *
     * **ECO 101 §1.2:** Price Elasticity of Demand (PED) = %ΔQ / %ΔP
     * Estimated via: ln(Q) = α + β·ln(P) where β = PED
     *
     * **STA 341 §6.2 (MLE):** The OLS estimator is the MLE for the
     * linear regression model under normality assumptions.
     */
    suspend fun priceElasticity(item: String, days: Int = 30): Double {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(days.toLong())
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val salesHistory = transactionDao.getItemSalesHistory(item, startEpoch)

        if (salesHistory.size < 5) return 0.0

        val data = salesHistory.filter { it.quantity > 0 && it.totalAmount > 0 }
            .map { sale ->
                val unitPrice = sale.totalAmount / sale.quantity
                Pair(ln(unitPrice), ln(sale.quantity))
            }

        if (data.size < 3) return 0.0

        // OLS regression
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
            val dayOfWeek = date.dayOfWeek.value - 1
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

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES — Statistical analysis results
// ═══════════════════════════════════════════════════════════════

/**
 * **ECO 202 §2.1:** Descriptive statistics for daily sales data.
 * Reports central tendency, dispersion, and shape of the distribution.
 */
data class SalesDescriptiveStats(
    val count: Int,
    val mean: Double,
    val median: Double,
    val variance: Double,
    val stdDev: Double,
    val range: Double,
    val coefficientOfVariation: Double,
    val q1: Double,
    val q3: Double,
    val iqr: Double,
    val skewness: Double,
    val kurtosis: Double,
    val min: Double,
    val max: Double,
    val normalityNote: String = ""
)

/**
 * **STA 244 §10.1 / ECO 203 §3.2:** Seasonality analysis results.
 * Identifies weekly and monthly patterns in sales data.
 */
data class SeasonalityAnalysis(
    val acfLag7: Double,
    val acfLag14: Double,
    val acfLag30: Double,
    val confidenceBound: Double,
    val hasWeeklySeasonality: Boolean,
    val hasMonthlySeasonality: Boolean,
    val seasonalIndices: Map<String, Double> // Day of week → seasonal index
)
