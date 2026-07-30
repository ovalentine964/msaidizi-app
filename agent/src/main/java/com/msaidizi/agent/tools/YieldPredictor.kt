package com.msaidizi.agent.tools

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * YieldPredictor — Predict future yields from historical harvest data.
 *
 * Uses simple statistical models that run on-device with zero network:
 * - Moving average (last N harvests)
 * - Seasonal adjustment (same-month historical data)
 * - Linear trend detection
 * - Confidence intervals
 *
 * Designed for 2GB RAM devices: all computation is in-memory on small datasets.
 * No ML models, no TensorFlow — just math that runs instantly.
 *
 * Voice examples:
 *   "Mavuno ya wiki ijayo yatakuwa?"      → "Utabiri: gunia 8-12"
 *   "Nitarajia mahindi ngapi msimu huu?"  → Seasonal prediction
 *   "Bei itakuwa ngapi nikivuna?"          → Price × yield estimate
 */
@Singleton
class YieldPredictor @Inject constructor(
    private val context: Context,
    private val harvestTracker: HarvestTracker,
    private val producePriceTracker: ProducePriceTracker
) : Tool {

    override val name = "yield_predictor"
    override val description = "Predict future harvest yields using historical data. Moving average + seasonal adjustment. Works fully offline."

    override val argsSchema = argSchema {
        enum(
            "action", "Action to perform",
            listOf(
                "predict",          // Predict next harvest yield
                "seasonal_forecast", // Full season forecast
                "revenue_forecast",  // Predict revenue (yield × price)
                "compare_periods",   // Compare this period vs same period last year
                "trend",            // Show yield trend over time
                "what_if"           // What if I plant X? What yield to expect?
            ),
            required = true
        )
        string("product", "Crop/product name", required = false)
        string("crop_type", "Crop type for what-if analysis", required = false)
        number("area_acres", "Area in acres for what-if", required = false)
        string("period", "Prediction period: week/month/season", required = false)
        boolean("voice", "Format for voice output", required = false)
    }

    companion object {
        // Typical yields per acre for common Kenyan crops (kg/acre)
        // Used for what-if analysis when no personal data exists
        private val TYPICAL_YIELDS = mapOf(
            "maize" to YieldInfo(1400.0, "gunia", 90, "Machi-Juni, Septemba-Novemba"),
            "beans" to YieldInfo(600.0, "kg", 75, "Machi-Juni, Septemba-Novemba"),
            "potatoes" to YieldInfo(6000.0, "kg", 120, "mwaka mzima"),
            "tomatoes" to YieldInfo(8000.0, "kg", 90, "mwaka mzima"),
            "kale" to YieldInfo(5000.0, "kg", 45, "mwaka mzima"),
            "cabbage" to YieldInfo(8000.0, "kg", 90, "mwaka mzima"),
            "rice" to YieldInfo(1200.0, "kg", 150, "Aprili-Julai"),
            "wheat" to YieldInfo(1100.0, "kg", 150, "Mei-Agosti"),
            "sorghum" to YieldInfo(800.0, "kg", 120, "Machi-Juni"),
            "millet" to YieldInfo(500.0, "kg", 90, "Machi-Juni"),
            "groundnuts" to YieldInfo(700.0, "kg", 120, "Machi-Juni"),
            "avocado" to YieldInfo(3000.0, "kg", 365, "Feb-Juni"),
            "mangoes" to YieldInfo(2500.0, "kg", 365, "Novemba-Febuari"),
            "bananas" to YieldInfo(8000.0, "kanda", 365, "mwaka mzima"),
            "tea" to YieldInfo(1500.0, "kg", 365, "mwaka mzima"),
            "coffee" to YieldInfo(500.0, "kg", 365, "Oktoba-Febuari"),
            "sugarcane" to YieldInfo(40000.0, "kg", 540, "mwaka mzima (miezi 18)"),
            "cassava" to YieldInfo(5000.0, "kg", 365, "mwaka mzima"),
            "sweet_potatoes" to YieldInfo(4000.0, "kg", 150, "mwaka mzima"),
            "pineapple" to YieldInfo(15000.0, "kuni", 540, "mwaka mzima (miezi 18)")
        )
    }

    private data class YieldInfo(
        val typicalYieldPerAcre: Double,
        val unit: String,
        val daysToHarvest: Int,
        val season: String
    )

    private data class PredictionResult(
        val predicted: Double,
        val lower: Double,
        val upper: Double,
        val confidence: Double,
        val method: String,
        val dataPoints: Int
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]
            ?: return ToolResult.error(name, "Action required", "MISSING_ACTION")

        return when (action.lowercase()) {
            "predict" -> predict(params)
            "seasonal_forecast" -> seasonalForecast(params)
            "revenue_forecast" -> revenueForecast(params)
            "compare_periods" -> comparePeriods(params)
            "trend" -> showTrend(params)
            "what_if" -> whatIf(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // ACTION: predict — Predict next harvest yield
    // ──────────────────────────────────────────────

    private fun predict(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val harvests = getHarvestHistory(product)
        if (harvests.size < 2) {
            return tryTypicalYield(product, voice)
        }

        // Moving average prediction (last 5 harvests)
        val recentWindow = harvests.takeLast(5)
        val movingAvg = recentWindow.map { it.second }.average()

        // Seasonal adjustment
        val calendar = java.util.Calendar.getInstance()
        val currentMonth = calendar.get(java.util.Calendar.MONTH) + 1
        val sameMonthHarvests = harvests.filter {
            calendar.timeInMillis = it.first
            calendar.get(java.util.Calendar.MONTH) + 1 == currentMonth
        }
        val seasonalFactor = if (sameMonthHarvests.isNotEmpty()) {
            val overallAvg = harvests.map { it.second }.average()
            val sameMonthAvg = sameMonthHarvests.map { it.second }.average()
            if (overallAvg > 0) sameMonthAvg / overallAvg else 1.0
        } else 1.0

        val predicted = movingAvg * seasonalFactor

        // Standard deviation for confidence interval
        val values = recentWindow.map { it.second }
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance)
        val margin = 1.96 * stdDev / sqrt(values.size.toDouble()) // 95% CI

        val unit = getUnit(product)
        val confidence = calculateConfidence(harvests.size, seasonalFactor)

        val result = PredictionResult(
            predicted = predicted,
            lower = maxOf(0.0, predicted - margin),
            upper = predicted + margin,
            confidence = confidence,
            method = "moving_avg_seasonal",
            dataPoints = harvests.size
        )

        val message = if (voice) {
            buildString {
                append("🔮 Utabiri wa mavuno ya $product:\n")
                append("• Utabiri: ~${formatQty(result.predicted)} $unit\n")
                append("• Kiwango: ${formatQty(result.lower)}-${formatQty(result.upper)} $unit\n")
                append("• Uhakika: ${(result.confidence * 100).toInt()}%\n")
                when {
                    seasonalFactor > 1.2 -> append("• 📈 Msimu huu ni mzuri kwa $product!")
                    seasonalFactor < 0.8 -> append("• 📉 Msimu huu si mzuri kwa $product.")
                    else -> append("• → Msimu wa kawaida.")
                }
                append("\n(Data kutoka mavuno ${harvests.size})")
            }
        } else {
            buildString {
                append("$product yield prediction:\n")
                append("• Predicted: ~${formatQty(result.predicted)} $unit\n")
                append("• 95% CI: ${formatQty(result.lower)}-${formatQty(result.upper)} $unit\n")
                append("• Confidence: ${(result.confidence * 100).toInt()}%\n")
                append("• Seasonal factor: ${String.format("%.2f", seasonalFactor)}x\n")
                append("• Based on ${harvests.size} harvests")
            }
        }

        return ToolResult.success(name, mapOf(
            "product" to product, "predicted" to result.predicted,
            "lower" to result.lower, "upper" to result.upper,
            "confidence" to result.confidence, "seasonal_factor" to seasonalFactor,
            "unit" to unit, "data_points" to harvests.size
        ), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: seasonal_forecast — Full season forecast
    // ──────────────────────────────────────────────

    private fun seasonalForecast(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val harvests = getHarvestHistory(product)
        if (harvests.isEmpty()) {
            return tryTypicalYield(product, voice)
        }

        // Calculate monthly yield averages
        val calendar = java.util.Calendar.getInstance()
        val monthlyAvgs = (1..12).map { month ->
            val monthHarvests = harvests.filter {
                calendar.timeInMillis = it.first
                calendar.get(java.util.Calendar.MONTH) + 1 == month
            }
            Triple(month, if (monthHarvests.isNotEmpty()) monthHarvests.map { it.second }.average() else 0.0, monthHarvests.size)
        }

        val totalExpected = monthlyAvgs.sumOf { it.second }
        val bestMonth = monthlyAvgs.maxByOrNull { it.second }
        val worstMonth = monthlyAvgs.filter { it.second > 0 }.minByOrNull { it.second }
        val unit = getUnit(product)

        val swahiliMonths = arrayOf("", "Januari", "Februari", "Machi", "Aprili", "Mei", "Juni",
            "Julai", "Agosti", "Septemba", "Oktoba", "Novemba", "Desemba")

        val message = if (voice) {
            buildString {
                append("📊 Utabiri wa msimu mzima wa $product:\n\n")
                append("Jumla ya mwaka: ~${formatQty(totalExpected)} $unit\n\n")
                monthlyAvgs.filter { it.second > 0 }.sortedByDescending { it.second }.forEach { (month, avg, count) ->
                    val bar = "█".repeat((avg / totalExpected * 30).toInt().coerceIn(1, 20))
                    append("• ${swahiliMonths[month]}: ${formatQty(avg)} $unit $bar\n")
                }
                bestMonth?.let { append("\n🟢 Mwezi bora: ${swahiliMonths[it.first]} (${formatQty(it.second)} $unit)") }
                worstMonth?.let { append("\n🔴 Mwezi wa chini: ${swahiliMonths[it.first]} (${formatQty(it.second)} $unit)") }
            }
        } else {
            buildString {
                append("$product seasonal forecast:\n")
                append("Annual expected: ~${formatQty(totalExpected)} $unit\n\n")
                monthlyAvgs.filter { it.second > 0 }.sortedByDescending { it.second }.forEach { (month, avg, count) ->
                    append("• ${swahiliMonths[month]}: ${formatQty(avg)} $unit ($count records)\n")
                }
                bestMonth?.let { append("\nBest: ${swahiliMonths[it.first]} (${formatQty(it.second)} $unit)") }
            }
        }

        return ToolResult.success(name, mapOf(
            "product" to product, "annual_expected" to totalExpected,
            "monthly" to monthlyAvgs.filter { it.second > 0 }.map { mapOf("month" to it.first, "avg" to it.second, "count" to it.third) },
            "unit" to unit
        ), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: revenue_forecast — Predict revenue
    // ──────────────────────────────────────────────

    private fun revenueForecast(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        // Get yield prediction
        val harvests = getHarvestHistory(product)
        if (harvests.isEmpty()) {
            return ToolResult.success(
                name, mapOf("product" to product, "forecast" to null),
                if (voice) "Hakuna data ya $product. Rekodda mavuno kwanza."
                else "No harvest data for $product."
            )
        }

        val recentWindow = harvests.takeLast(5)
        val predictedYield = recentWindow.map { it.second }.average()
        val unit = getUnit(product)

        // Try to get current market price (from ProducePriceTracker)
        // In production this would call the price tracker directly
        // For now, use typical prices as baseline
        val typicalPrices = mapOf(
            "maize" to 50.0, "beans" to 120.0, "potatoes" to 40.0,
            "tomatoes" to 60.0, "kale" to 30.0, "rice" to 150.0,
            "avocado" to 80.0, "mangoes" to 40.0, "bananas" to 50.0,
            "tea" to 300.0, "coffee" to 500.0, "cassava" to 30.0
        )
        val pricePerKg = typicalPrices[product] ?: 50.0
        val estimatedRevenue = predictedYield * pricePerKg

        val message = if (voice) {
            buildString {
                append("💰 Utabiri wa mapato ya $product:\n")
                append("• Mavuno: ~${formatQty(predictedYield)} $unit\n")
                append("• Bei wastani: KES ${formatPrice(pricePerKg)} kwa $unit\n")
                append("• Mapato: ~KES ${formatPrice(estimatedRevenue)}\n")
                append("\n⚠️ Hii ni takwimu ya bei ya sasa. Bei hubadilika!")
            }
        } else {
            buildString {
                append("$product revenue forecast:\n")
                append("• Expected yield: ~${formatQty(predictedYield)} $unit\n")
                append("• Current price: ~KES ${formatPrice(pricePerKg)}/$unit\n")
                append("• Expected revenue: ~KES ${formatPrice(estimatedRevenue)}\n")
                append("⚠️ Price is approximate. Use ProducePriceTracker for current prices.")
            }
        }

        return ToolResult.success(name, mapOf(
            "product" to product, "predicted_yield" to predictedYield,
            "price_per_unit" to pricePerKg, "estimated_revenue" to estimatedRevenue,
            "unit" to unit
        ), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: compare_periods — Compare current vs last year
    // ──────────────────────────────────────────────

    private fun comparePeriods(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val harvests = getHarvestHistory(product)
        val now = System.currentTimeMillis()
        val yearMs = 365L * 24 * 60 * 60 * 1000L

        val thisYear = harvests.filter { it.first >= now - yearMs }
        val lastYear = harvests.filter { it.first in (now - 2 * yearMs)..(now - yearMs) }

        if (thisYear.isEmpty() && lastYear.isEmpty()) {
            return ToolResult.success(
                name, mapOf("product" to product, "comparison" to null),
                if (voice) "Hakuna data ya kulinganisha kwa $product."
                else "No data to compare for $product."
            )
        }

        val thisYearTotal = thisYear.sumOf { it.second }
        val lastYearTotal = lastYear.sumOf { it.second }
        val changePct = if (lastYearTotal > 0) ((thisYearTotal - lastYearTotal) / lastYearTotal * 100) else 0.0
        val unit = getUnit(product)

        val message = if (voice) {
            buildString {
                append("📊 Linganisha $product:\n")
                append("• Mwaka huu: ${formatQty(thisYearTotal)} $unit (${thisYear.size} mavuno)\n")
                append("• Mwaka jana: ${formatQty(lastYearTotal)} $unit (${lastYear.size} mavuno)\n")
                when {
                    changePct > 10 -> append("📈 Mwaka huu ni bora zaidi! +${changePct.toInt()}%")
                    changePct < -10 -> append("📉 Mwaka huu ni mbaya zaidi. ${changePct.toInt()}%")
                    else -> append("→ Mawiano ni sawa (${changePct.toInt()}%)")
                }
            }
        } else {
            buildString {
                append("$product year-over-year comparison:\n")
                append("• This year: ${formatQty(thisYearTotal)} $unit (${thisYear.size} harvests)\n")
                append("• Last year: ${formatQty(lastYearTotal)} $unit (${lastYear.size} harvests)\n")
                append("• Change: ${changePct.toInt()}%")
            }
        }

        return ToolResult.success(name, mapOf(
            "product" to product,
            "this_year_total" to thisYearTotal, "this_year_count" to thisYear.size,
            "last_year_total" to lastYearTotal, "last_year_count" to lastYear.size,
            "change_pct" to changePct, "unit" to unit
        ), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: trend — Show yield trend over time
    // ──────────────────────────────────────────────

    private fun showTrend(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val harvests = getHarvestHistory(product)
        if (harvests.size < 3) {
            return ToolResult.success(
                name, mapOf("product" to product, "trend" to null),
                if (voice) "Hakuna data ya kutosha kuonyesha mwelekeo wa $product."
                else "Insufficient data for trend analysis."
            )
        }

        // Linear regression: y = mx + b
        val n = harvests.size
        val xValues = (0 until n).map { it.toDouble() }
        val yValues = harvests.map { it.second }
        val xMean = xValues.average()
        val yMean = yValues.average()

        val numerator = xValues.zip(yValues).sumOf { (x, y) -> (x - xMean) * (y - yMean) }
        val denominator = xValues.sumOf { (it - xMean) * (it - xMean) }
        val slope = if (denominator != 0.0) numerator / denominator else 0.0

        // Interpret slope
        val avgYield = yMean
        val slopePct = if (avgYield > 0) (slope / avgYield * 100) else 0.0
        val unit = getUnit(product)

        val direction = when {
            slopePct > 5 -> "inapanda ↑"
            slopePct < -5 -> "inashuka ↓"
            else -> "imara →"
        }

        // Recent 3 vs previous 3
        val recent3 = harvests.takeLast(3).map { it.second }.average()
        val previous3 = if (harvests.size >= 6) harvests.subList(harvests.size - 6, harvests.size - 3).map { it.second }.average() else recent3
        val recentChange = if (previous3 > 0) ((recent3 - previous3) / previous3 * 100) else 0.0

        val message = if (voice) {
            buildString {
                append("📈 Mwelekeo wa mavuno ya $product:\n")
                append("• Mwelekeo: $direction\n")
                append("• Wastani: ${formatQty(avgYield)} $unit\n")
                append("• Mabadiliko: ${slopePct.toInt()}% kwa mavuno\n")
                if (recentChange > 10) append("• 📈 Mavuno ya hivi karibuni yamepanda!")
                if (recentChange < -10) append("• 📉 Mavuno ya hivi karibuni yameshuka.")
                append("\n(Data: mavuno $n)")
            }
        } else {
            buildString {
                append("$product yield trend:\n")
                append("• Direction: $direction\n")
                append("• Average yield: ${formatQty(avgYield)} $unit\n")
                append("• Slope: ${String.format("%.2f", slope)} per harvest (${slopePct.toInt()}%)\n")
                append("• Recent 3 vs previous 3: ${recentChange.toInt()}%\n")
                append("• Data points: $n")
            }
        }

        return ToolResult.success(name, mapOf(
            "product" to product, "direction" to direction,
            "avg_yield" to avgYield, "slope" to slope,
            "slope_pct" to slopePct, "recent_change_pct" to recentChange,
            "data_points" to n, "unit" to unit
        ), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: what_if — What if I plant X?
    // ──────────────────────────────────────────────

    private fun whatIf(params: Map<String, String>): ToolResult {
        val rawProduct = params["crop_type"]
            ?: params["product"]
            ?: return ToolResult.error(name, "Crop type required (e.g. 'maize', 'beans', 'tomatoes')", "MISSING_CROP")
        val product = normalizeProduct(rawProduct)
        val areaAcres = params["area_acres"]?.toDoubleOrNull() ?: 1.0
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val yieldInfo = TYPICAL_YIELDS[product]
            ?: return ToolResult.error(name, "Unknown crop type: $product. Known: ${TYPICAL_YIELDS.keys.joinToString()}", "UNKNOWN_CROP")

        // Check personal history for adjustment
        val harvests = getHarvestHistory(product)
        val personalFactor = if (harvests.size >= 3) {
            val personalAvg = harvests.map { it.second }.average()
            val typicalTotal = yieldInfo.typicalYieldPerAcre * areaAcres
            if (typicalTotal > 0) personalAvg / typicalTotal else 1.0
        } else 1.0

        val expectedYield = yieldInfo.typicalYieldPerAcre * areaAcres * personalFactor
        val lowerBound = expectedYield * 0.7
        val upperBound = expectedYield * 1.3
        val unit = yieldInfo.unit

        val message = if (voice) {
            buildString {
                append("🌱 Utabiri wa $product (${areaAcres} ekari):\n")
                append("• Mavuno: ~${formatQty(expectedYield)} $unit\n")
                append("• Kiwango: ${formatQty(lowerBound)}-${formatQty(upperBound)} $unit\n")
                append("• Muda: siku ~${yieldInfo.daysToHarvest}\n")
                append("• Msimu: ${yieldInfo.season}\n")
                if (harvests.size >= 3) {
                    append("\n📊 Kulinganisha na historia yako: ")
                    when {
                        personalFactor > 1.2 -> append("Ufanisi wako ni bora kuliko wastani! 👍")
                        personalFactor < 0.8 -> append("Ufanisi wako ni wa chini — fikiria kuboresha mbegu/mbolea.")
                        else -> append("Ufanisi wako ni wa kawaida.")
                    }
                }
            }
        } else {
            buildString {
                append("$product what-if analysis (${areaAcres} acres):\n")
                append("• Expected yield: ~${formatQty(expectedYield)} $unit\n")
                append("• Range: ${formatQty(lowerBound)}-${formatQty(upperBound)} $unit\n")
                append("• Time to harvest: ~${yieldInfo.daysToHarvest} days\n")
                append("• Best season: ${yieldInfo.season}\n")
                append("• Typical yield/acre: ${formatQty(yieldInfo.typicalYieldPerAcre)} $unit")
                if (harvests.size >= 3) {
                    append("\n• Personal factor: ${String.format("%.2f", personalFactor)}x (based on your history)")
                }
            }
        }

        return ToolResult.success(name, mapOf(
            "product" to product, "area_acres" to areaAcres,
            "expected_yield" to expectedYield, "lower" to lowerBound, "upper" to upperBound,
            "unit" to unit, "days_to_harvest" to yieldInfo.daysToHarvest,
            "season" to yieldInfo.season, "personal_factor" to personalFactor
        ), message)
    }

    // ──────────────────────────────────────────────
    // Internal Helpers
    // ──────────────────────────────────────────────

    /**
     * Get harvest history from HarvestTracker's database.
     * Returns list of (timestamp, quantity) pairs sorted by time.
     */
    private fun getHarvestHistory(product: String): List<Pair<Long, Double>> {
        return try {
            val db = harvestTracker.run {
                // Access the database through the HarvestTracker
                val field = this.javaClass.getDeclaredField("dbHelper")
                field.isAccessible = true
                val dbHelper = field.get(this) as? android.database.sqlite.SQLiteOpenHelper
                    ?: return emptyList()
                dbHelper.readableDatabase
            }

            val harvests = mutableListOf<Pair<Long, Double>>()
            val cursor = db.query(
                "harvests", arrayOf("harvested_at", "quantity"),
                "product = ?", arrayOf(product),
                null, null, "harvested_at ASC"
            )
            cursor.use {
                while (it.moveToNext()) {
                    harvests.add(Pair(it.getLong(0), it.getDouble(1)))
                }
            }
            harvests
        } catch (e: Exception) {
            Timber.e(e, "Failed to read harvest history from HarvestTracker")
            emptyList()
        }
    }

    private fun tryTypicalYield(product: String, voice: Boolean): ToolResult {
        val yieldInfo = TYPICAL_YIELDS[product]
        if (yieldInfo == null) {
            return ToolResult.success(
                name, mapOf("product" to product, "prediction" to null),
                if (voice) "Hakuna data ya $product. Rekodda mavuno kwanza au ni zao gani?"
                else "No data for $product. Record harvests first."
            )
        }

        val message = if (voice) {
            buildString {
                append("🌱 Taarifa za $product (bila historia yako):\n")
                append("• Mavuno ya kawaida: ~${formatQty(yieldInfo.typicalYieldPerAcre)} ${yieldInfo.unit}/ekari\n")
                append("• Muda: siku ~${yieldInfo.daysToHarvest}\n")
                append("• Msimu: ${yieldInfo.season}\n")
                append("\n💡 Rekodda mavuno yako ili nitabiri vizuri zaidi!")
            }
        } else {
            "$product typical yield: ~${formatQty(yieldInfo.typicalYieldPerAcre)} ${yieldInfo.unit}/acre\n" +
            "Time to harvest: ~${yieldInfo.daysToHarvest} days | Season: ${yieldInfo.season}\n" +
            "Record your harvests for personalized predictions."
        }

        return ToolResult.success(name, mapOf(
            "product" to product, "typical_yield_per_acre" to yieldInfo.typicalYieldPerAcre,
            "unit" to yieldInfo.unit, "days_to_harvest" to yieldInfo.daysToHarvest,
            "season" to yieldInfo.season, "has_personal_data" to false
        ), message)
    }

    private fun calculateConfidence(dataPoints: Int, seasonalFactor: Double): Double {
        val dataConfidence = minOf(1.0, dataPoints / 10.0) // More data = more confidence
        val seasonalConfidence = if (seasonalFactor in 0.5..2.0) 0.9 else 0.6
        return (dataConfidence * 0.6 + seasonalConfidence * 0.4).coerceIn(0.3, 0.95)
    }

    private fun normalizeProduct(raw: String): String {
        val aliases = mapOf(
            "mahindi" to "maize", "maharagwe" to "beans", "viazi" to "potatoes",
            "nyanya" to "tomatoes", "sukuma wiki" to "kale", "kabichi" to "cabbage",
            "mchele" to "rice", "ngano" to "wheat", "mtama" to "sorghum",
            "wimbi" to "millet", "njugu" to "groundnuts", "parachichi" to "avocado",
            "embe" to "mangoes", "ndizi" to "bananas", "chai" to "tea",
            "kahawa" to "coffee", "miwa" to "sugarcane", "muhogo" to "cassava",
            "viazi vitamu" to "sweet_potatoes", "nanasi" to "pineapple"
        )
        return aliases[raw.trim().lowercase()] ?: raw.trim().lowercase()
    }

    private fun getUnit(product: String): String {
        return TYPICAL_YIELDS[product]?.unit ?: "kg"
    }

    private fun formatQty(qty: Double): String {
        return if (qty == qty.toLong().toDouble()) "%,.0f".format(qty) else "%,.1f".format(qty)
    }

    private fun formatPrice(price: Double): String {
        return if (price == price.toLong().toDouble()) "%,.0f".format(price) else "%,.1f".format(price)
    }
}
