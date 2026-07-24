package com.msaidizi.app.superagent.tools
import javax.inject.Inject
class PricingAdvisor @Inject constructor() {
    private val marketPrices = mutableMapOf<String, Double>()
    fun advise(product: String, currentPrice: Double): ToolResult {
        val marketPrice = marketPrices[product] ?: currentPrice
        val diff = ((currentPrice - marketPrice) / marketPrice * 100)
        return when {
            diff > 20 -> ToolResult.Success("Your price is ${diff.toInt()}% above market. Consider lowering to $marketPrice")
            diff < -20 -> ToolResult.Success("Your price is ${Math.abs(diff).toInt()}% below market. You could charge up to $marketPrice")
            else -> ToolResult.Success("Your price is competitive. Market average: $marketPrice")
        }
    }
    fun updateMarketPrice(product: String, price: Double) { marketPrices[product] = price }
}
