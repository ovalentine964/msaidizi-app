package com.msaidizi.agent.tools

import com.msaidizi.agent.flywheel.FlywheelEngine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PricingAdvisor — Advise on product pricing based on market data.
 *
 * Enhanced with flywheel market intelligence for data-driven pricing advice.
 */
@Singleton
class PricingAdvisor @Inject constructor(
    private val flywheelEngine: FlywheelEngine
) : Tool {

    override val name = "pricing_advisor"
    override val description = "Advise on product pricing based on market comparison"

    override val argsSchema = argSchema {
        enum("action", "Action to perform",
            listOf("advise", "update_market", "list"), required = false)
        string("product", "Product name to get pricing advice for", required = false)
        number("price", "Current or market price in KES", required = false)
    }

    private val marketPrices = mutableMapOf<String, Double>()

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "advise"
        return when (action.lowercase()) {
            "advise" -> {
                val product = params["product"]
                    ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
                val currentPrice = params["price"]?.toDoubleOrNull()
                    ?: return ToolResult.error(name, "Current price required", "MISSING_PRICE")
                advise(product, currentPrice)
            }
            "update_market" -> {
                val product = params["product"]
                    ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
                val price = params["price"]?.toDoubleOrNull()
                    ?: return ToolResult.error(name, "Price required", "MISSING_PRICE")
                updateMarketPrice(product, price)
                ToolResult.success(name, mapOf("product" to product, "market_price" to price), "Market price updated for $product")
            }
            "list" -> {
                val list = marketPrices.entries.joinToString("\n") { (p, price) -> "$p: Ksh ${"%,.0f".format(price)}" }
                ToolResult.success(name, message = if (list.isEmpty()) "No market prices recorded" else list)
            }
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    fun advise(product: String, currentPrice: Double): ToolResult {
        val marketPrice = marketPrices[product] ?: currentPrice
        val diff = ((currentPrice - marketPrice) / marketPrice * 100)

        // ── Flywheel: Enrich with market intelligence ──
        val flywheelAdvice = try {
            val marketIntel = flywheelEngine.getMarketIntelligence()
            val priceSignals = marketIntel.filter { it.key.startsWith("price_signal") }
            if (priceSignals.isNotEmpty()) {
                val avgConfidence = priceSignals.map { it.confidence }.average().toFloat()
                val signalCount = priceSignals.size
                "\n📊 Market signals: $signalCount data points (confidence: ${(avgConfidence * 100).toInt()}%)"
            } else null
        } catch (e: Exception) {
            null
        }

        return when {
            diff > 20 -> ToolResult.success(
                name,
                mapOf("product" to product, "current_price" to currentPrice, "market_price" to marketPrice, "diff_pct" to diff),
                "Your price is ${diff.toInt()}% above market. Consider lowering to Ksh ${"%,.0f".format(marketPrice)}${flywheelAdvice ?: ""}"
            )
            diff < -20 -> ToolResult.success(
                name,
                mapOf("product" to product, "current_price" to currentPrice, "market_price" to marketPrice, "diff_pct" to diff),
                "Your price is ${Math.abs(diff).toInt()}% below market. You could charge up to Ksh ${"%,.0f".format(marketPrice)}${flywheelAdvice ?: ""}"
            )
            else -> ToolResult.success(
                name,
                mapOf("product" to product, "current_price" to currentPrice, "market_price" to marketPrice, "diff_pct" to diff),
                "Your price is competitive. Market average: Ksh ${"%,.0f".format(marketPrice)}${flywheelAdvice ?: ""}"
            )
        }
    }

    fun updateMarketPrice(product: String, price: Double) { marketPrices[product] = price }
}
