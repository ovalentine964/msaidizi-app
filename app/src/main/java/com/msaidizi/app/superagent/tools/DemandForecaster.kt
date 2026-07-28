package com.msaidizi.app.superagent.tools

import com.msaidizi.app.core.database.DailySummaryDao
import com.msaidizi.app.core.database.SaleDao
import com.msaidizi.app.core.database.StockMovementDao
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

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
            listOf("today", "tomorrow", "week_forecast", "product_forecast", "buying_advice"),
            required = false)
        string("product", "Product name for product-specific forecast", required = false)
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
