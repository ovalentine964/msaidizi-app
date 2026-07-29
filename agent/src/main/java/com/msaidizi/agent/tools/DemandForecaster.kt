package com.msaidizi.agent.tools

import com.msaidizi.core.database.DailySummaryDao
import com.msaidizi.core.database.SaleDao
import com.msaidizi.core.database.StockMovementDao
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * DemandForecaster — Predict demand for perishable goods.
 *
 * Based on research, mama mbogas lose 15-30% to spoilage because they
 * buy too much on low-demand days. This tool predicts demand based on:
 *   1. Day of week patterns (Monday = high, Sunday = low)
 *   2. Market day patterns (local market schedules)
 *   3. Historical sales data
 *   4. Seasonal patterns
 *   5. Weather (future integration)
 *
 * Output: "Buy less tomatoes today — low demand day"
 *
 * Key patterns from Nairobi research:
 *   - Monday: HIGH demand (restocking after weekend)
 *   - Tuesday-Thursday: MEDIUM demand
 *   - Friday: HIGH demand (weekend prep)
 *   - Saturday: VERY HIGH demand (peak market day)
 *   - Sunday: LOW demand (church day, fewer shoppers)
 */
@Singleton
class DemandForecaster @Inject constructor(
    private val saleDao: SaleDao,
    private val dailySummaryDao: DailySummaryDao,
    private val stockMovementDao: StockMovementDao
) : Tool {

    override val name = "demand_forecaster"
    override val description = "Predict demand for perishable goods based on day-of-week, historical patterns, and market days"

    override val argsSchema = argSchema {
        enum("action", "Action to perform",
            listOf(
                "today", "tomorrow", "week_forecast", "product_forecast", "buying_advice",
                // STA 244 Time Series Analysis
                "moving_average", "exponential_smoothing", "seasonal_decomposition",
                "trend_detection", "forecast_with_ci"
            ),
            required = false)
        string("product", "Product name for product-specific forecast", required = false)
        string("data", "Comma-separated numeric time series data", required = false)
        integer("window", "Window size for moving average (default 7)", required = false)
        number("alpha", "Smoothing parameter for exponential smoothing (default 0.3)", required = false)
        integer("period", "Seasonal period for decomposition (default 7 = weekly)", required = false)
        integer("horizon", "Forecast horizon (number of periods ahead)", required = false)
    }

    // Base demand multipliers by day of week (1=Sunday, 7=Saturday)
    // Calibrated from Nairobi informal market research
    private val baseDemandByDay = mapOf(
        Calendar.SUNDAY to 0.5,     // Low — church day
        Calendar.MONDAY to 1.3,     // High — restocking
        Calendar.TUESDAY to 1.0,    // Normal
        Calendar.WEDNESDAY to 1.0,  // Normal
        Calendar.THURSDAY to 1.2,   // Above normal — pre-weekend
        Calendar.FRIDAY to 1.5,     // High — weekend prep
        Calendar.SATURDAY to 1.8    // Very high — peak market day
    )

    // Product-specific demand patterns (relative to base)
    // Some products sell better on certain days
    private val productDayPatterns = mapOf(
        "nyanya" to mapOf(
            Calendar.MONDAY to 1.4,  // Stew day
            Calendar.WEDNESDAY to 1.2, // Mid-week cooking
            Calendar.FRIDAY to 1.6,  // Weekend cooking
            Calendar.SATURDAY to 1.5
        ),
        "sukuma wiki" to mapOf(
            Calendar.MONDAY to 1.3,
            Calendar.TUESDAY to 1.2,
            Calendar.WEDNESDAY to 1.2,
            Calendar.THURSDAY to 1.1,
            Calendar.FRIDAY to 1.0,  // Less on weekend (people eat out)
            Calendar.SATURDAY to 0.9,
            Calendar.SUNDAY to 0.6
        ),
        "viazi" to mapOf(
            Calendar.FRIDAY to 1.4,  // Chips Friday
            Calendar.SATURDAY to 1.6,
            Calendar.SUNDAY to 1.3
        ),
        "ndizi" to mapOf(
            Calendar.MONDAY to 1.2,
            Calendar.SATURDAY to 1.4,
            Calendar.SUNDAY to 1.3
        ),
        "mahindi" to mapOf(
            Calendar.FRIDAY to 1.5,
            Calendar.SATURDAY to 1.7,
            Calendar.SUNDAY to 1.4
        )
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "today"
        return when (action.lowercase()) {
            "today" -> forecastToday(params)
            "tomorrow" -> forecastTomorrow(params)
            "week_forecast" -> forecastWeek()
            "product_forecast" -> forecastProduct(params)
            "buying_advice" -> getBuyingAdvice(params)
            // STA 244: Time Series Analysis
            "moving_average" -> movingAverage(params)
            "exponential_smoothing" -> exponentialSmoothing(params)
            "seasonal_decomposition" -> seasonalDecomposition(params)
            "trend_detection" -> trendDetection(params)
            "forecast_with_ci" -> forecastWithConfidenceIntervals(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    /**
     * Forecast demand for today.
     * Main output: "Buy less tomatoes today — low demand day"
     */
    private suspend fun forecastToday(params: Map<String, String>): ToolResult {
        return try {
            val calendar = Calendar.getInstance()
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
            val dayName = getDayName(dayOfWeek)
            val baseDemand = baseDemandByDay[dayOfWeek] ?: 1.0

            // Get historical data for this day of week
            val historicalMultiplier = getHistoricalDayMultiplier(dayOfWeek)

            // Combine base + historical
            val adjustedDemand = baseDemand * historicalMultiplier

            val demandLevel = categorizeDemand(adjustedDemand)
            val emoji = getDemandEmoji(demandLevel)

            // Generate product-specific advice
            val productAdvice = generateProductAdvice(dayOfWeek, adjustedDemand)

            val message = buildString {
                append("$emoji Mahitaji ya Leo ($dayName):\n\n")

                when (demandLevel) {
                    "VERY HIGH" -> {
                        append("🔥 Leo ni siku ya mahitaji makubwa!\n")
                        append("Today is a high demand day!\n\n")
                        append("💡 Nunua zaidi ya kawaida. Hakikisha una stock ya kutosha.\n")
                        append("💡 Buy more than usual. Make sure you have enough stock.\n")
                    }
                    "HIGH" -> {
                        append("📈 Mahitaji ni mazuri leo.\n")
                        append("Demand is good today.\n\n")
                        append("💡 Nunua kiasi cha kawaida au zaidi kidogo.\n")
                        append("💡 Buy normal amount or slightly more.\n")
                    }
                    "MEDIUM" -> {
                        append("➡️ Mahitaji ya kawaida leo.\n")
                        append("Normal demand today.\n\n")
                        append("💡 Nunua kiasi cha kawaida. Usinunue sana.\n")
                        append("💡 Buy normal amount. Don't over-stock.\n")
                    }
                    "LOW" -> {
                        append("📉 Mahitaji ni madogo leo.\n")
                        append("Demand is low today.\n\n")
                        append("💡 NUNUA KIDOGO! Bidhaa zinaweza kuharibika.\n")
                        append("💡 BUY LESS! Stock may go to waste.\n")
                    }
                }

                if (productAdvice.isNotEmpty()) {
                    append("\n📊 Bidhaa mahususi / Specific products:\n")
                    productAdvice.forEach { (product, advice) ->
                        append("• $product: $advice\n")
                    }
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "day" to dayName,
                    "demand_level" to demandLevel,
                    "demand_multiplier" to adjustedDemand,
                    "base_multiplier" to baseDemand,
                    "historical_multiplier" to historicalMultiplier,
                    "product_advice" to productAdvice
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to forecast today's demand")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * Forecast demand for tomorrow.
     */
    private suspend fun forecastTomorrow(params: Map<String, String>): ToolResult {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val tomorrowDay = calendar.get(Calendar.DAY_OF_WEEK)
        val dayName = getDayName(tomorrowDay)
        val baseDemand = baseDemandByDay[tomorrowDay] ?: 1.0
        val demandLevel = categorizeDemand(baseDemand)
        val emoji = getDemandEmoji(demandLevel)

        val message = buildString {
            append("$emoji Mahitaji ya Kesho ($dayName):\n\n")
            when (demandLevel) {
                "VERY HIGH", "HIGH" -> {
                    append("📈 Kesho mahitaji ni makubwa.\n")
                    append("Tomorrow demand will be high.\n\n")
                    append("💡 Tayarisha stock ya kutosha leo usiku.\n")
                    append("💡 Prepare enough stock tonight.\n")
                }
                "MEDIUM" -> {
                    append("➡️ Kesho mahitaji ya kawaida.\n")
                    append("Tomorrow: normal demand.\n\n")
                    append("💡 Stock ya kawaida inatosha.\n")
                    append("💡 Normal stock will be enough.\n")
                }
                "LOW" -> {
                    append("📉 Kesho mahitaji ni madogo.\n")
                    append("Tomorrow: low demand.\n\n")
                    append("💡 Usinunue sana — punguza stock.\n")
                    append("💡 Don't buy too much — reduce stock.\n")
                }
            }
        }

        return ToolResult.success(
            toolName = name,
            data = mapOf("day" to dayName, "demand_level" to demandLevel, "demand_multiplier" to baseDemand),
            message = message
        )
    }

    /**
     * Forecast demand for the whole week.
     */
    private suspend fun forecastWeek(): ToolResult {
        val calendar = Calendar.getInstance()

        val forecasts = (0..6).map { dayOffset ->
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_MONTH, dayOffset)
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
            val demand = baseDemandByDay[dayOfWeek] ?: 1.0
            val dayName = getDayName(dayOfWeek)
            val level = categorizeDemand(demand)
            Triple(dayName, level, demand)
        }

        val bestDay = forecasts.maxByOrNull { it.third }
        val worstDay = forecasts.minByOrNull { it.third }

        val message = buildString {
            append("📊 Mahitaji ya Wiki / Week Demand Forecast:\n\n")
            forecasts.forEach { (day, level, demand) ->
                val emoji = getDemandEmoji(level)
                val bar = "█".repeat((demand * 5).toInt())
                append("$emoji $day: $bar ($level)\n")
            }
            append("\n🟢 Siku bora: ${bestDay?.first} (nunua zaidi)")
            append("\n🔴 Siku mbaya: ${worstDay?.first} (nunua kidogo)")
            append("\n\n💡 Best day: ${bestDay?.first} (buy more)")
            append("\n💡 Worst day: ${worstDay?.first} (buy less)")
        }

        return ToolResult.success(
            toolName = name,
            data = mapOf(
                "forecasts" to forecasts.map { mapOf("day" to it.first, "level" to it.second, "demand" to it.third) },
                "best_day" to bestDay?.first,
                "worst_day" to worstDay?.first
            ),
            message = message
        )
    }

    /**
     * Forecast demand for a specific product.
     */
    private suspend fun forecastProduct(params: Map<String, String>): ToolResult {
        val productName = params["product"]
            ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")

        val calendar = Calendar.getInstance()
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val dayName = getDayName(dayOfWeek)

        // Check product-specific patterns
        val productLower = productName.lowercase()
        val productPatterns = productDayPatterns.entries
            .firstOrNull { productLower.contains(it.key) }
            ?.value

        val baseDemand = baseDemandByDay[dayOfWeek] ?: 1.0
        val productMultiplier = productPatterns?.get(dayOfWeek) ?: 1.0
        val historicalMultiplier = getHistoricalProductMultiplier(productName, dayOfWeek)
        val finalDemand = baseDemand * productMultiplier * historicalMultiplier

        val level = categorizeDemand(finalDemand)
        val emoji = getDemandEmoji(level)

        val advice = when {
            finalDemand > 1.4 -> "Nunua zaidi ya $productName leo! Mahitaji ni makubwa."
            finalDemand > 1.1 -> "Nunua kiasi cha kawaida cha $productName."
            finalDemand > 0.8 -> "Nunua $productName kidogo tu."
            else -> "Punguza ununuzi wa $productName leo — mahitaji ni madogo."
        }
        val adviceEn = when {
            finalDemand > 1.4 -> "Buy more $productName today! High demand expected."
            finalDemand > 1.1 -> "Buy normal amount of $productName."
            finalDemand > 0.8 -> "Buy less $productName than usual."
            else -> "Reduce $productName purchases today — low demand."
        }

        val message = buildString {
            append("$emoji Mahitaji ya $productName leo ($dayName):\n\n")
            append("Kiwango / Level: $level (${(finalDemand * 100).toInt()}%)\n\n")
            append("💡 $advice\n")
            append("💡 $adviceEn")
        }

        return ToolResult.success(
            toolName = name,
            data = mapOf(
                "product" to productName,
                "day" to dayName,
                "demand_level" to level,
                "demand_multiplier" to finalDemand,
                "base_demand" to baseDemand,
                "product_multiplier" to productMultiplier,
                "historical_multiplier" to historicalMultiplier
            ),
            message = message
        )
    }

    /**
     * Get buying advice for today.
     * Main output: "Buy less tomatoes today — low demand day"
     */
    private suspend fun getBuyingAdvice(params: Map<String, String>): ToolResult {
        val calendar = Calendar.getInstance()
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val dayName = getDayName(dayOfWeek)
        val baseDemand = baseDemandByDay[dayOfWeek] ?: 1.0
        val level = categorizeDemand(baseDemand)

        val commonProducts = listOf(
            "Nyanya", "Sukuma Wiki", "Vitunguu", "Viazi", "Pilipili", "Ndizi"
        )

        val adviceList = commonProducts.map { product ->
            val productLower = product.lowercase()
            val productPatterns = productDayPatterns.entries
                .firstOrNull { productLower.contains(it.key) }
                ?.value
            val productMultiplier = productPatterns?.get(dayOfWeek) ?: 1.0
            val finalDemand = baseDemand * productMultiplier

            val buyAdvice = when {
                finalDemand > 1.4 -> "🟢 NUNUA ZAIDI / BUY MORE"
                finalDemand > 1.1 -> "🟢 Normal / Kiasi cha kawaida"
                finalDemand > 0.8 -> "🟡 Punguza kidogo / Buy slightly less"
                else -> "🔴 NUNUA KIDOGO / BUY LESS"
            }
            product to buyAdvice
        }

        val message = buildString {
            append("🛒 Ushauri wa Ununuzi wa Leo ($dayName):\n")
            append("Today's Buying Advice:\n\n")
            adviceList.forEach { (product, advice) ->
                append("• $product: $advice\n")
            }
            append("\n💡 Kumbuka: Kuharibika ni hasara kubwa! Nunua kulingana na mahitaji.")
            append("\n💡 Remember: Spoilage is your biggest loss! Buy according to demand.")
        }

        return ToolResult.success(
            toolName = name,
            data = mapOf(
                "day" to dayName,
                "demand_level" to level,
                "advice" to adviceList.toMap()
            ),
            message = message
        )
    }

    // ══════════════════════════════════════════════════════════════
    // STA 244: Time Series Analysis Methods
    // ══════════════════════════════════════════════════════════════

    private suspend fun movingAverage(params: Map<String, String>): ToolResult {
        val data = parseTimeSeriesData(params) ?: return ToolResult.error(name, "data required", "MISSING_DATA")
        val window = params["window"]?.toIntOrNull() ?: 7
        if (data.size < window) return ToolResult.error(name, "Need ≥$window data points", "INSUFFICIENT_DATA")

        val sma = (window - 1 until data.size).map { i -> data.subList(i - window + 1, i + 1).average() }
        val weights = (1..window).map { it.toDouble() }; val wSum = weights.sum()
        val wma = (window - 1 until data.size).map { i -> data.subList(i - window + 1, i + 1).zip(weights).sumOf { (v, w) -> v * w } / wSum }

        val message = buildString {
            append("📊 Moving Average (window=$window)\n\n")
            append("Recent SMA: ${sma.takeLast(5).joinToString(", ") { "%.2f".format(it) }}\n")
            append("Recent WMA: ${wma.takeLast(5).joinToString(", ") { "%.2f".format(it) }}\n\n")
            append("Forecast (SMA): ${"%.2f".format(sma.last())}\n")
            append("Forecast (WMA): ${"%.2f".format(wma.last())}\n")
        }
        return ToolResult.success(name, mapOf("sma" to sma, "wma" to wma, "forecast_sma" to sma.last(), "forecast_wma" to wma.last()), message)
    }

    private suspend fun exponentialSmoothing(params: Map<String, String>): ToolResult {
        val data = parseTimeSeriesData(params) ?: return ToolResult.error(name, "data required", "MISSING_DATA")
        val alpha = params["alpha"]?.toDoubleOrNull() ?: 0.3
        if (alpha <= 0 || alpha > 1) return ToolResult.error(name, "alpha must be (0, 1]", "INVALID_ALPHA")

        val smoothed = mutableListOf(data[0])
        for (i in 1 until data.size) smoothed.add(alpha * data[i] + (1 - alpha) * smoothed[i - 1])
        val errors = data.zip(smoothed).drop(1).map { (a, s) -> a - s }
        val mae = errors.map { abs(it) }.average(); val rmse = sqrt(errors.map { it * it }.average())
        val forecast = smoothed.last(); val ci95 = 1.96 * rmse

        val message = buildString {
            append("📊 Exponential Smoothing (α=${"%.2f".format(alpha)})\n\n")
            append("MAE: ${"%.2f".format(mae)}, RMSE: ${"%.2f".format(rmse)}\n")
            append("Forecast: ${"%.2f".format(forecast)} [${"%.2f".format(forecast - ci95)}, ${"%.2f".format(forecast + ci95)}]\n")
        }
        return ToolResult.success(name, mapOf("smoothed" to smoothed, "forecast" to forecast, "mae" to mae, "rmse" to rmse, "ci_lower" to (forecast - ci95), "ci_upper" to (forecast + ci95)), message)
    }

    private suspend fun seasonalDecomposition(params: Map<String, String>): ToolResult {
        val data = parseTimeSeriesData(params) ?: return ToolResult.error(name, "data required", "MISSING_DATA")
        val period = params["period"]?.toIntOrNull() ?: 7
        if (data.size < period * 2) return ToolResult.error(name, "Need ≥${period * 2} points", "INSUFFICIENT_DATA")

        val halfP = period / 2; val trend = DoubleArray(data.size)
        for (i in halfP until data.size - halfP) trend[i] = data.subList(i - halfP, i + halfP + 1).average()
        for (i in 0 until halfP) trend[i] = trend[halfP]; for (i in data.size - halfP until data.size) trend[i] = trend[data.size - halfP - 1]
        val detrended = data.zip(trend).map { (d, t) -> d - t }
        val seasonalAvg = DoubleArray(period); val cnt = IntArray(period)
        for (i in detrended.indices) { seasonalAvg[i % period] += detrended[i]; cnt[i % period]++ }
        for (i in 0 until period) seasonalAvg[i] /= cnt[i].coerceAtLeast(1)
        val sMean = seasonalAvg.average(); for (i in 0 until period) seasonalAvg[i] -= sMean
        val residual = data.indices.map { data[it] - trend[it] - seasonalAvg[it % period] }
        val varD = detrended.map { it * it }.average(); val varR = residual.map { it * it }.average()
        val strength = if (varD > 0) 1 - varR / varD else 0.0

        val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val message = buildString {
            append("📊 Seasonal Decomposition (period=$period)\n\n")
            append("Seasonal strength: ${"%.1f".format(strength * 100)}%\n\n")
            if (period == 7) { for (i in 0..6) append("  ${dayNames[i]}: ${if (seasonalAvg[i] >= 0) "+" else ""}${"%.2f".format(seasonalAvg[i])}\n") }
            append("\n💡 Strength >50% means seasonality drives demand.")
        }
        return ToolResult.success(name, mapOf("trend" to trend.toList(), "seasonal" to seasonalAvg.toList(), "residual" to residual, "seasonal_strength" to strength), message)
    }

    private suspend fun trendDetection(params: Map<String, String>): ToolResult {
        val data = parseTimeSeriesData(params) ?: return ToolResult.error(name, "data required", "MISSING_DATA")
        val n = data.size; if (n < 5) return ToolResult.error(name, "Need ≥5 points", "INSUFFICIENT_DATA")
        val x = (0 until n).map { it.toDouble() }; val mx = x.average(); val my = data.average()
        var ssXY = 0.0; var ssXX = 0.0; for (i in 0 until n) { ssXY += (x[i] - mx) * (data[i] - my); ssXX += (x[i] - mx).pow(2) }
        val slope = if (ssXX > 0) ssXY / ssXX else 0.0; val intercept = my - slope * mx
        val predicted = x.map { intercept + slope * it }; val ssRes = data.zip(predicted).sumOf { (y, p) -> (y - p).pow(2) }; val ssTot = data.sumOf { (it - my).pow(2) }
        val rSq = if (ssTot > 0) 1 - ssRes / ssTot else 0.0
        val dir = when { slope > 0.01 * abs(my) -> "INCREASING"; slope < -0.01 * abs(my) -> "DECREASING"; else -> "STABLE" }

        val message = buildString {
            append("📊 Trend Detection\n\nDirection: $dir\n")
            append("Slope: ${"%.4f".format(slope)}/period, R²: ${"%.3f".format(rSq)}\n")
            when (dir) { "INCREASING" -> append("📈 Trending UP"); "DECREASING" -> append("📉 Trending DOWN"); else -> append("➡️ STABLE") }
        }
        return ToolResult.success(name, mapOf("direction" to dir, "slope" to slope, "r_squared" to rSq, "intercept" to intercept), message)
    }

    private suspend fun forecastWithConfidenceIntervals(params: Map<String, String>): ToolResult {
        val data = parseTimeSeriesData(params) ?: return ToolResult.error(name, "data required", "MISSING_DATA")
        val horizon = params["horizon"]?.toIntOrNull() ?: 3; val alpha = params["alpha"]?.toDoubleOrNull() ?: 0.3
        val smoothed = mutableListOf(data[0]); for (i in 1 until data.size) smoothed.add(alpha * data[i] + (1 - alpha) * smoothed[i - 1])
        val residuals = data.zip(smoothed).drop(1).map { (a, s) -> a - s }; val rSD = sqrt(residuals.map { it * it }.average())
        val forecasts = (1..horizon).map { h -> val pt = smoothed.last(); val ci = 1.96 * rSD * sqrt(h.toDouble()); mapOf("period" to h, "forecast" to pt, "ci_lower" to (pt - ci), "ci_upper" to (pt + ci)) }

        val message = buildString {
            append("📊 Forecast with CIs (horizon=$horizon)\n\n")
            forecasts.forEach { f -> append("+${f["period"]}: ${"%.2f".format(f["forecast"] as Double)} [${"%.2f".format(f["ci_lower"] as Double)}, ${"%.2f".format(f["ci_upper"] as Double)}]\n") }
            append("\n💡 CIs widen further into the future.")
        }
        return ToolResult.success(name, mapOf("forecasts" to forecasts, "residual_sd" to rSD), message)
    }

    private suspend fun parseTimeSeriesData(params: Map<String, String>): List<Double>? {
        val explicit = params["data"]
        if (!explicit.isNullOrBlank()) return try { explicit.split(",").map { it.trim().toDouble() } } catch (e: Exception) { null }
        return try { val s = dailySummaryDao.getRecentSummaries(60).first(); if (s.size >= 7) s.sortedBy { it.date }.map { it.totalSales } else null } catch (e: Exception) { null }
    }

    // ── Helper Methods ──

    private fun getDayName(dayOfWeek: Int): String {
        return when (dayOfWeek) {
            Calendar.SUNDAY -> "Jumapili"
            Calendar.MONDAY -> "Jumatatu"
            Calendar.TUESDAY -> "Jumanne"
            Calendar.WEDNESDAY -> "Jumatano"
            Calendar.THURSDAY -> "Alhamisi"
            Calendar.FRIDAY -> "Ijumaa"
            Calendar.SATURDAY -> "Jumamosi"
            else -> "Leo"
        }
    }

    private fun categorizeDemand(multiplier: Double): String {
        return when {
            multiplier >= 1.6 -> "VERY HIGH"
            multiplier >= 1.2 -> "HIGH"
            multiplier >= 0.9 -> "MEDIUM"
            else -> "LOW"
        }
    }

    private fun getDemandEmoji(level: String): String {
        return when (level) {
            "VERY HIGH" -> "🔥"
            "HIGH" -> "📈"
            "MEDIUM" -> "➡️"
            "LOW" -> "📉"
            else -> "➡️"
        }
    }

    /**
     * Get historical demand multiplier for a specific day of week.
     * Analyzes past sales data to learn patterns.
     */
    private suspend fun getHistoricalDayMultiplier(dayOfWeek: Int): Double {
        return try {
            val summaries = dailySummaryDao.getRecentSummaries(30).first()
            if (summaries.size < 7) return 1.0 // Not enough data

            val calendar = Calendar.getInstance()
            val daySales = summaries.filter { summary ->
                calendar.timeInMillis = summary.createdAt
                calendar.get(Calendar.DAY_OF_WEEK) == dayOfWeek
            }

            if (daySales.isEmpty()) return 1.0

            val avgForDay = daySales.map { it.totalSales }.average()
            val avgOverall = summaries.map { it.totalSales }.average()

            if (avgOverall > 0) (avgForDay / avgOverall) else 1.0
        } catch (e: Exception) {
            1.0
        }
    }

    /**
     * Get historical demand multiplier for a specific product on a specific day.
     */
    private suspend fun getHistoricalProductMultiplier(productName: String, dayOfWeek: Int): Double {
        // In production, this would analyze product-level sales by day of week
        // For now, return 1.0 (neutral)
        return 1.0
    }

    /**
     * Generate product-specific advice based on day of week patterns.
     */
    private fun generateProductAdvice(dayOfWeek: Int, baseDemand: Double): Map<String, String> {
        val advice = mutableMapOf<String, String>()

        productDayPatterns.forEach { (product, patterns) ->
            val productMultiplier = patterns[dayOfWeek] ?: 1.0
            val combined = baseDemand * productMultiplier

            advice[product] = when {
                combined > 1.5 -> "Nunua zaidi! Mahitaji makubwa sana."
                combined > 1.2 -> "Nunua zaidi kidogo."
                combined > 0.8 -> "Kiasi cha kawaida."
                else -> "Punguza! Mahitaji ni madogo."
            }
        }

        return advice
    }
}
