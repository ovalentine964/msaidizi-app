package com.msaidizi.superagent.financial

import timber.log.Timber
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pricing Advisor — recommends pricing based on costs and market conditions.
 *
 * Analyzes cost basis, competitor pricing, demand patterns, and margin
 * targets to recommend optimal selling prices for informal market vendors.
 *
 * ## Pricing Strategy
 * - **Cost-plus pricing:** Minimum price = cost + target margin
 * - **Market-based pricing:** Adjust based on competitor prices
 * - **Demand-based pricing:** Higher prices when demand is high
 * - **Perishable pricing:** Lower prices as expiry approaches
 *
 * ## Academic Foundations
 * - **ECO 201 (Microeconomics):** Price elasticity, marginal revenue
 * - **MKT 301 (Marketing):** Pricing strategies, competitive positioning
 * - **FIN 201 (Corporate Finance):** Margin analysis, break-even pricing
 *
 * @author Msaidizi Financial Team
 */
class PricingAdvisor {

    companion object {
        private const val TAG = "PricingAdvisor"

        /** Minimum acceptable gross margin */
        private const val MIN_GROSS_MARGIN = 0.15

        /** Target gross margin */
        private const val TARGET_GROSS_MARGIN = 0.30

        /** Maximum price increase recommendation (% above market) */
        private const val MAX_PREMIUM_PERCENT = 15.0

        /** Price reduction for slow-moving items */
        private const val SLOW_MOVING_DISCOUNT = 0.10

        /** Price reduction for items near expiry */
        private const val NEAR_EXPIRY_DISCOUNT = 0.30
    }

    // ═══════════════════════════════════════════════════════════════
    // PRICE RECOMMENDATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Recommend a selling price for an item.
     *
     * @param item Item name
     * @param costBasis Cost per unit in KSh
     * @param currentPrice Current selling price (0 if not set)
     * @param marketAverage Average market price for this item
     * @param dailySalesVelocity Units sold per day (for demand assessment)
     * @param daysUntilExpiry Days until item expires (null if non-perishable)
     * @return [PricingRecommendation] with recommended price
     */
    fun recommend(
        item: String,
        costBasis: Double,
        currentPrice: Double = 0.0,
        marketAverage: Double = 0.0,
        dailySalesVelocity: Double = 0.0,
        daysUntilExpiry: Int? = null
    ): PricingRecommendation {
        if (costBasis <= 0) {
            return PricingRecommendation(
                item = item,
                currentPrice = currentPrice,
                recommendedPrice = currentPrice,
                costBasis = 0.0,
                expectedMargin = 0.0,
                marketAverage = marketAverage,
                message = "Gharama ya $item haijulikani. Weka bei ya ununuzi ili niendekeze bei ya kuuza."
            )
        }

        // Step 1: Calculate cost-plus minimum price
        val minPrice = costBasis * (1 + MIN_GROSS_MARGIN)
        val targetPrice = costBasis * (1 + TARGET_GROSS_MARGIN)

        // Step 2: Consider market pricing
        var recommendedPrice = if (marketAverage > 0) {
            // Start from market average, adjust toward target
            val marketBasedPrice = marketAverage
            val blendedPrice = (targetPrice * 0.4 + marketBasedPrice * 0.6)
            max(blendedPrice, minPrice) // Never go below minimum margin
        } else {
            targetPrice
        }

        // Step 3: Demand adjustment
        if (dailySalesVelocity > 0) {
            // High demand → can charge slightly more
            // Low demand → may need to lower price
            val demandFactor = when {
                dailySalesVelocity > 10 -> 1.05  // Very high demand
                dailySalesVelocity > 5 -> 1.02   // Good demand
                dailySalesVelocity > 1 -> 1.0     // Normal demand
                dailySalesVelocity > 0.5 -> 0.98  // Slow moving
                else -> 0.95                       // Very slow
            }
            recommendedPrice *= demandFactor
        }

        // Step 4: Perishable adjustment
        if (daysUntilExpiry != null) {
            when {
                daysUntilExpiry <= 1 -> {
                    // Last day — heavy discount to avoid total loss
                    recommendedPrice *= (1 - NEAR_EXPIRY_DISCOUNT * 1.5)
                }
                daysUntilExpiry <= 2 -> {
                    // Near expiry — discount
                    recommendedPrice *= (1 - NEAR_EXPIRY_DISCOUNT)
                }
                daysUntilExpiry <= 3 -> {
                    // Approaching expiry — slight discount
                    recommendedPrice *= (1 - NEAR_EXPIRY_DISCOUNT * 0.5)
                }
            }
        }

        // Step 5: Round to sensible price point
        recommendedPrice = roundToPricePoint(max(recommendedPrice, minPrice))

        // Step 6: Ensure we don't recommend more than market premium
        if (marketAverage > 0) {
            val maxPrice = marketAverage * (1 + MAX_PREMIUM_PERCENT / 100)
            recommendedPrice = min(recommendedPrice, maxPrice)
        }

        // Calculate expected margin
        val expectedMargin = if (recommendedPrice > 0) {
            (recommendedPrice - costBasis) / recommendedPrice
        } else 0.0

        // Generate message
        val message = buildPricingMessage(
            item, costBasis, currentPrice, recommendedPrice,
            marketAverage, expectedMargin, daysUntilExpiry
        )

        return PricingRecommendation(
            item = item,
            currentPrice = currentPrice,
            recommendedPrice = recommendedPrice,
            costBasis = costBasis,
            expectedMargin = (expectedMargin * 100).roundToInt() / 100.0,
            marketAverage = marketAverage,
            message = message
        )
    }

    /**
     * Recommend prices for multiple items.
     *
     * @param items List of item pricing data
     * @return List of pricing recommendations
     */
    fun recommendBulk(items: List<PricingInput>): List<PricingRecommendation> {
        return items.map { input ->
            recommend(
                item = input.item,
                costBasis = input.costBasis,
                currentPrice = input.currentPrice,
                marketAverage = input.marketAverage,
                dailySalesVelocity = input.dailySalesVelocity,
                daysUntilExpiry = input.daysUntilExpiry
            )
        }
    }

    /**
     * Calculate break-even price for an item.
     *
     * @param costBasis Cost per unit
     * @param fixedOverhead Fixed costs to allocate (e.g., rent, transport)
     * @param expectedVolume Expected units to sell
     * @return Break-even price per unit
     */
    fun calculateBreakEven(
        costBasis: Double,
        fixedOverhead: Double,
        expectedVolume: Double
    ): Double {
        if (expectedVolume <= 0) return costBasis
        return costBasis + (fixedOverhead / expectedVolume)
    }

    // ═══════════════════════════════════════════════════════════════
    // PRICE POINT ROUNDING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Round price to a sensible price point for informal markets.
     * Rounds to nearest 5 or 10 KSh depending on magnitude.
     */
    private fun roundToPricePoint(price: Double): Double {
        return when {
            price >= 1000 -> (price / 50).roundToInt() * 50.0   // Round to 50
            price >= 100 -> (price / 10).roundToInt() * 10.0    // Round to 10
            price >= 20 -> (price / 5).roundToInt() * 5.0       // Round to 5
            else -> price.roundToInt().toDouble()                 // Round to 1
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // MESSAGE GENERATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build a Swahili pricing recommendation message.
     */
    private fun buildPricingMessage(
        item: String,
        costBasis: Double,
        currentPrice: Double,
        recommendedPrice: Double,
        marketAverage: Double,
        expectedMargin: Double,
        daysUntilExpiry: Int?
    ): String {
        return buildString {
            append("💰 Ushauri wa bei ya $item:\n\n")

            append("Gharama: KSh ${formatAmount(costBasis)}\n")

            if (currentPrice > 0) {
                append("Bei ya sasa: KSh ${formatAmount(currentPrice)}\n")
            }

            append("Bei inayopendekezwa: KSh ${formatAmount(recommendedPrice)}\n")

            if (marketAverage > 0) {
                append("Bei ya soko: KSh ${formatAmount(marketAverage)}\n")
            }

            append("Faida inayotarajiwa: ${(expectedMargin * 100).roundToInt()}%\n\n")

            // Margin assessment
            when {
                expectedMargin >= TARGET_GROSS_MARGIN -> {
                    append("✅ Faida nzuri! Endelea na bei hii.")
                }
                expectedMargin >= MIN_GROSS_MARGIN -> {
                    append("⚠️ Faida ni ndogo. Fikiria kupunguza gharama za ununuzi.")
                }
                else -> {
                    append("🚨 Faida ni ndogo sana! Bei hii haiwezi kubeba biashara yako.")
                }
            }

            // Expiry warning
            if (daysUntilExpiry != null && daysUntilExpiry <= 3) {
                append("\n\n⏰ Bidhaa hii inakaribia kuharibika (siku $daysUntilExpiry). ")
                append("Punguza bei ili kuuza haraka!")
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

/**
 * Input data for pricing recommendation.
 *
 * @property item Item name
 * @property costBasis Cost per unit
 * @property currentPrice Current selling price
 * @property marketAverage Average market price
 * @property dailySalesVelocity Units sold per day
 * @property daysUntilExpiry Days until expiry (null if non-perishable)
 */
data class PricingInput(
    val item: String,
    val costBasis: Double,
    val currentPrice: Double = 0.0,
    val marketAverage: Double = 0.0,
    val dailySalesVelocity: Double = 0.0,
    val daysUntilExpiry: Int? = null
)
