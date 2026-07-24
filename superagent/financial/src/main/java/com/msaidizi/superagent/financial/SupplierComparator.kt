package com.msaidizi.superagent.financial

import timber.log.Timber
import kotlin.math.roundToInt

/**
 * Supplier Comparator — compares prices across suppliers.
 *
 * Helps informal workers find the best deals by comparing supplier
 * prices, delivery times, and reliability scores.
 *
 * ## Comparison Factors
 * - **Price:** Unit cost (lower is better)
 * - **Delivery time:** Days until delivery (faster is better)
 * - **Reliability:** Historical delivery consistency (higher is better)
 * - **Minimum order:** Minimum quantity required (lower = more flexible)
 *
 * ## Academic Foundations
 * - **ECO 201 (Microeconomics):** Cost minimization, supplier selection
 * - **OPS 301 (Operations):** Supplier evaluation, total cost of ownership
 * - **MGT 301 (Procurement):** Vendor scoring methodology
 *
 * @author Msaidizi Financial Team
 */
class SupplierComparator {

    companion object {
        private const val TAG = "SupplierComparator"

        // Weighting factors for supplier scoring
        private const val PRICE_WEIGHT = 0.45        // Price is most important
        private const val DELIVERY_WEIGHT = 0.20      // Delivery speed matters
        private const val RELIABILITY_WEIGHT = 0.25   // Reliability is crucial
        private const val FLEXIBILITY_WEIGHT = 0.10   // Low minimums are nice

        /** Days considered "fast" delivery */
        private const val FAST_DELIVERY_DAYS = 1

        /** Days considered "slow" delivery */
        private const val SLOW_DELIVERY_DAYS = 7
    }

    // ═══════════════════════════════════════════════════════════════
    // SUPPLIER COMPARISON
    // ═══════════════════════════════════════════════════════════════

    /**
     * Compare suppliers for a specific item.
     *
     * @param item Item name to compare
     * @param suppliers List of supplier prices for this item
     * @return [SupplierComparison] with ranked suppliers
     */
    fun compare(
        item: String,
        suppliers: List<SupplierPrice>
    ): SupplierComparison {
        if (suppliers.isEmpty()) {
            return SupplierComparison(
                item = item,
                suppliers = emptyList(),
                message = "Hakuna wauzaji waliopatikana kwa $item."
            )
        }

        if (suppliers.size == 1) {
            val single = suppliers[0]
            return SupplierComparison(
                item = item,
                suppliers = listOf(
                    SupplierRanking(
                        supplierName = single.supplierName,
                        price = single.price,
                        deliveryDays = single.deliveryDays,
                        reliabilityScore = single.reliabilityScore,
                        overallScore = 1.0,
                        savingsVsAverage = 0.0
                    )
                ),
                message = "Mauzaji pekee wa $item ni ${single.supplierName} " +
                    "kwa KSh ${formatAmount(single.price)} kwa ${single.unit}."
            )
        }

        // Calculate average price for savings comparison
        val avgPrice = suppliers.map { it.price }.average()

        // Normalize scores for each factor
        val minPrice = suppliers.minOf { it.price }
        val maxPrice = suppliers.maxOf { it.price }
        val minDelivery = suppliers.minOf { it.deliveryDays }
        val maxDelivery = suppliers.maxOf { it.deliveryDays }
        val minOrder = suppliers.minOf { it.minimumOrder }
        val maxOrder = suppliers.maxOf { it.minimumOrder }

        // Score each supplier
        val rankings = suppliers.map { supplier ->
            // Price score (0-1, higher = cheaper = better)
            val priceScore = if (maxPrice > minPrice) {
                1.0 - (supplier.price - minPrice) / (maxPrice - minPrice)
            } else 1.0

            // Delivery score (0-1, higher = faster = better)
            val deliveryScore = if (maxDelivery > minDelivery) {
                1.0 - (supplier.deliveryDays - minDelivery).toDouble() / (maxDelivery - minDelivery)
            } else 1.0

            // Reliability score (already 0-1)
            val reliabilityScore = supplier.reliabilityScore

            // Flexibility score (lower minimum = better)
            val flexibilityScore = if (maxOrder > minOrder) {
                1.0 - (supplier.minimumOrder - minOrder) / (maxOrder - minOrder)
            } else 1.0

            // Weighted composite score
            val overallScore = (priceScore * PRICE_WEIGHT +
                    deliveryScore * DELIVERY_WEIGHT +
                    reliabilityScore * RELIABILITY_WEIGHT +
                    flexibilityScore * FLEXIBILITY_WEIGHT)

            // Savings vs average
            val savings = avgPrice - supplier.price

            SupplierRanking(
                supplierName = supplier.supplierName,
                price = supplier.price,
                deliveryDays = supplier.deliveryDays,
                reliabilityScore = supplier.reliabilityScore,
                overallScore = (overallScore * 100).roundToInt() / 100.0,
                savingsVsAverage = (savings * 100).roundToInt() / 100.0
            )
        }.sortedByDescending { it.overallScore }

        // Generate recommendation message
        val best = rankings.first()
        val message = buildComparisonMessage(item, rankings, avgPrice)

        return SupplierComparison(
            item = item,
            suppliers = rankings,
            message = message
        )
    }

    /**
     * Find the best supplier for multiple items.
     *
     * @param items List of item names to compare
     * @param allSupplierPrices All available supplier prices
     * @return Map of item → best supplier recommendation
     */
    fun findBestSuppliers(
        items: List<String>,
        allSupplierPrices: List<SupplierPrice>
    ): Map<String, SupplierComparison> {
        return items.associateWith { item ->
            val itemSuppliers = allSupplierPrices.filter {
                it.item.lowercase() == item.lowercase()
            }
            compare(item, itemSuppliers)
        }
    }

    /**
     * Calculate potential savings from switching suppliers.
     *
     * @param currentSupplier Current supplier and their price
     * @param alternatives Alternative suppliers
     * @param monthlyVolume Monthly purchase volume in units
     * @return Estimated monthly savings in KSh
     */
    fun calculateSavings(
        currentSupplier: SupplierPrice,
        alternatives: List<SupplierPrice>,
        monthlyVolume: Double
    ): Map<String, Double> {
        return alternatives.associate { supplier ->
            val savingsPerUnit = currentSupplier.price - supplier.price
            val monthlySavings = savingsPerUnit * monthlyVolume
            supplier.supplierName to monthlySavings
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // MESSAGE GENERATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build a comparison message in Swahili.
     */
    private fun buildComparisonMessage(
        item: String,
        rankings: List<SupplierRanking>,
        avgPrice: Double
    ): String {
        val best = rankings.first()

        return buildString {
            append("🔍 Ulinganishaji wa wauzaji wa $item:\n\n")

            append("🥇 Bora: ${best.supplierName}\n")
            append("   Bei: KSh ${formatAmount(best.price)}")
            if (best.savingsVsAverage > 0) {
                append(" (okoa KSh ${formatAmount(best.savingsVsAverage)} kwa kila ${item})")
            }
            append("\n")
            append("   Uwasilishaji: siku ${best.deliveryDays}\n")
            append("   Uaminifu: ${(best.reliabilityScore * 100).roundToInt()}%\n\n")

            if (rankings.size > 1) {
                append("Wengine:\n")
                rankings.drop(1).take(3).forEachIndexed { index, supplier ->
                    val emoji = when (index) {
                        0 -> "🥈"
                        1 -> "🥉"
                        else -> "  "
                    }
                    append("$emoji ${supplier.supplierName}: KSh ${formatAmount(supplier.price)}")
                    if (supplier.savingsVsAverage < 0) {
                        append(" (+KSh ${formatAmount(-supplier.savingsVsAverage)} zaidi)")
                    }
                    append("\n")
                }
            }

            append("\nBei ya wastani: KSh ${formatAmount(avgPrice)}")
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
