package com.msaidizi.app.superagent.tools

import javax.inject.Inject
import javax.inject.Singleton

/**
 * PricingAdvisor — Advise on product pricing based on market data.
 */
@Singleton
class PricingAdvisor @Inject constructor() : Tool {

    override val name = "pricing_advisor"
    override val description = "Advise on product pricing based on market comparison"

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
        return when {
            diff > 20 -> ToolResult.success(
                name,
                mapOf("product" to product, "current_price" to currentPrice, "market_price" to marketPrice, "diff_pct" to diff),
                "Your price is ${diff.toInt()}% above market. Consider lowering to Ksh ${"%,.0f".format(marketPrice)}"
            )
            diff < -20 -> ToolResult.success(
                name,
                mapOf("product" to product, "current_price" to currentPrice, "market_price" to marketPrice, "diff_pct" to diff),
                "Your price is ${Math.abs(diff).toInt()}% below market. You could charge up to Ksh ${"%,.0f".format(marketPrice)}"
            )
            else -> ToolResult.success(
                name,
                mapOf("product" to product, "current_price" to currentPrice, "market_price" to marketPrice, "diff_pct" to diff),
                "Your price is competitive. Market average: Ksh ${"%,.0f".format(marketPrice)}"
            )
        }
    }

    fun updateMarketPrice(product: String, price: Double) { marketPrices[product] = price }
}
