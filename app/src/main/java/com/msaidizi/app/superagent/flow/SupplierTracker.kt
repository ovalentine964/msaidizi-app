package com.msaidizi.app.superagent.flow

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * SupplierTracker — "Mauzo ya wauzaji ni kina nani?"
 * Tracks supplier prices, reliability, and finds cheaper alternatives.
 * Helps workers negotiate better and switch suppliers when needed.
 */
class SupplierTracker {

    private val suppliers = mutableMapOf<String, Supplier>()
    private val orders = mutableListOf<SupplierOrder>()
    private val priceHistory = mutableMapOf<String, MutableList<PricePoint>>() // productId → prices

    // ── Data Management ────────────────────────

    fun registerSupplier(supplier: Supplier) {
        suppliers[supplier.id] = supplier
    }

    fun recordOrder(order: SupplierOrder) {
        orders.add(order)

        // Track price history
        val key = "${order.productId}_${order.supplierId}"
        val history = priceHistory.getOrPut(order.productId) { mutableListOf() }
        history.add(PricePoint(
            supplierId = order.supplierId,
            productId = order.productId,
            price = order.pricePerUnit,
            date = order.date,
            quantity = order.quantity
        ))

        // Update supplier last order date
        suppliers[order.supplierId]?.let { supplier ->
            suppliers[order.supplierId] = supplier.copy(lastOrderDate = order.date)
        }
    }

    fun clearData() {
        suppliers.clear()
        orders.clear()
        priceHistory.clear()
    }

    // ── Supplier Summary ───────────────────────

    /**
     * Overview of all suppliers.
     * "Wauzaji wangu ni kina nani? Ninanunua wapi?"
     */
    fun getSummary(period: ReportPeriod, customRange: DateRange? = null): SupplierSummary {
        val range = resolveDateRange(period, customRange)
        val periodOrders = filterOrders(range)

        // Spend by supplier
        val totalSpendBySupplier = periodOrders
            .groupBy { order ->
                suppliers[order.supplierId]?.name ?: order.supplierId
            }
            .mapValues { (_, supplierOrders) -> supplierOrders.sumOf { it.totalCost } }

        // Price comparison alerts
        val priceAlerts = generatePriceAlerts()

        // Reliability alerts
        val reliabilityAlerts = suppliers.values
            .filter { it.deliveryReliability < 0.7 }
            .map { supplier ->
                ReliabilityAlert(
                    supplierName = supplier.name,
                    reliabilityScore = supplier.deliveryReliability,
                    message = "${supplier.name} delivers on time only ${"%.0f".format(supplier.deliveryReliability * 100)}% of the time.",
                    messageSw = "${supplier.name} anapeleka wakati asilimia ${"%.0f".format(supplier.deliveryReliability * 100)} tu."
                )
            }

        return SupplierSummary(
            suppliers = suppliers.values.toList(),
            totalSpendBySupplier = totalSpendBySupplier,
            priceComparisonAlerts = priceAlerts,
            reliabilityAlerts = reliabilityAlerts
        )
    }

    // ── Price Comparisons ──────────────────────

    /**
     * Find cheaper alternatives for each product.
     * "Kuna wauzaji wengine wanauza bei ndogo?"
     */
    fun generatePriceAlerts(): List<PriceAlert> {
        val alerts = mutableListOf<PriceAlert>()

        for ((productId, prices) in priceHistory) {
            if (prices.size < 2) continue

            // Group by supplier and get average price per supplier
            val avgBySupplier = prices
                .groupBy { it.supplierId }
                .mapValues { (_, points) -> points.map { it.price }.average() }

            if (avgBySupplier.size < 2) continue

            val mostExpensive = avgBySupplier.maxByOrNull { it.value } ?: continue
            val cheapest = avgBySupplier.minByOrNull { it.value } ?: continue

            if (mostExpensive.key == cheapest.key) continue

            val savings = mostExpensive.value - cheapest.value
            val savingsPercent = (savings / mostExpensive.value) * 100

            // Only alert if savings > 5%
            if (savingsPercent < 5) continue

            val expensiveName = suppliers[mostExpensive.key]?.name ?: mostExpensive.key
            val cheapName = suppliers[cheapest.key]?.name ?: cheapest.key

            alerts.add(PriceAlert(
                productName = productId,
                currentSupplier = expensiveName,
                currentPrice = mostExpensive.value,
                cheaperSupplier = cheapName,
                cheaperPrice = cheapest.value,
                savings = savings,
                savingsPercent = savingsPercent,
                message = "You're paying KES ${"%.0f".format(mostExpensive.value)} from $expensiveName, but $cheapName sells for KES ${"%.0f".format(cheapest.value)}. Save KES ${"%.0f".format(savings)}/unit!",
                messageSw = "Unalipa KES ${"%.0f".format(mostExpensive.value)} kwa $expensiveName, lakini $cheapName anauza KES ${"%.0f".format(cheapest.value)}. Okoa KES ${"%.0f".format(savings)} kwa kila kitu!"
            ))
        }

        return alerts.sortedByDescending { it.savings }
    }

    // ── Supplier Reliability ───────────────────

    /**
     * Rate suppliers on delivery reliability.
     * "Mauzaji gani anaweza kutegemewa?"
     */
    fun getReliabilityReport(): List<SupplierReliability> {
        return suppliers.values.map { supplier ->
            val supplierOrders = orders.filter { it.supplierId == supplier.id }
            val onTimeOrders = supplierOrders.count { it.deliveredOnTime }
            val totalOrders = supplierOrders.size

            val reliability = if (totalOrders > 0) {
                onTimeOrders.toDouble() / totalOrders
            } else supplier.deliveryReliability

            SupplierReliability(
                supplierId = supplier.id,
                name = supplier.name,
                totalOrders = totalOrders,
                onTimeOrders = onTimeOrders,
                reliabilityPercent = reliability * 100,
                qualityRating = supplier.qualityRating,
                averagePrice = supplier.averagePrice,
                grade = when {
                    reliability >= 0.95 -> SupplierGrade.A
                    reliability >= 0.85 -> SupplierGrade.B
                    reliability >= 0.70 -> SupplierGrade.C
                    reliability >= 0.50 -> SupplierGrade.D
                    else -> SupplierGrade.F
                }
            )
        }.sortedByDescending { it.reliabilityPercent }
    }

    // ── Price Trends ───────────────────────────

    /**
     * Track price changes over time for a product.
     * "Bei imepanda au kushuka?"
     */
    fun getPriceTrend(productId: String): PriceTrend? {
        val history = priceHistory[productId] ?: return null
        if (history.size < 2) return null

        val sorted = history.sortedBy { it.date }
        val latest = sorted.last()
        val previous = sorted[sorted.size - 2]
        val change = latest.price - previous.price
        val changePercent = if (previous.price > 0) (change / previous.price) * 100 else 0.0

        val direction = when {
            changePercent > 3 -> TrendDirection.UP
            changePercent < -3 -> TrendDirection.DOWN
            else -> TrendDirection.FLAT
        }

        return PriceTrend(
            productId = productId,
            latestPrice = latest.price,
            previousPrice = previous.price,
            change = change,
            changePercent = changePercent,
            direction = direction,
            supplierId = latest.supplierId,
            date = latest.date,
            messageEn = when (direction) {
                TrendDirection.UP -> "Price of $productId went UP ${"%.0f".format(changePercent)}% (KES ${"%.0f".format(latest.price)} from ${suppliers[latest.supplierId]?.name ?: "supplier"})"
                TrendDirection.DOWN -> "Price of $productId went DOWN ${"%.0f".format(kotlin.math.abs(changePercent))}% (KES ${"%.0f".format(latest.price)})"
                TrendDirection.FLAT -> "Price of $productId is stable at KES ${"%.0f".format(latest.price)}"
            },
            messageSw = when (direction) {
                TrendDirection.UP -> "Bei ya $productId imePANDA asilimia ${"%.0f".format(changePercent)} (KES ${"%.0f".format(latest.price)} kutoka ${suppliers[latest.supplierId]?.name ?: "muuzaji"})"
                TrendDirection.DOWN -> "Bei ya $productId imeSHUKA asilimia ${"%.0f".format(kotlin.math.abs(changePercent))}% (KES ${"%.0f".format(latest.price)})"
                TrendDirection.FLAT -> "Bei ya $productId imebaki KES ${"%.0f".format(latest.price)}"
            }
        )
    }

    // ── Best Deals ─────────────────────────────

    /**
     * Find the cheapest supplier for each product.
     * "Ninunue wapi bei ndogo?"
     */
    fun getBestDeals(): List<BestDeal> {
        val deals = mutableListOf<BestDeal>()

        for ((productId, prices) in priceHistory) {
            val bySupplier = prices
                .groupBy { it.supplierId }
                .mapValues { (_, points) -> points.map { it.price }.average() }

            val cheapest = bySupplier.minByOrNull { it.value } ?: continue
            val marketAvg = bySupplier.values.average()
            val savings = marketAvg - cheapest.value

            deals.add(BestDeal(
                productId = productId,
                bestSupplierId = cheapest.key,
                bestSupplierName = suppliers[cheapest.key]?.name ?: cheapest.key,
                bestPrice = cheapest.value,
                marketAverage = marketAvg,
                savingsPerUnit = savings,
                savingsPercent = if (marketAvg > 0) (savings / marketAvg) * 100 else 0.0
            ))
        }

        return deals.sortedByDescending { it.savingsPerUnit }
    }

    // ── Voice Summary ──────────────────────────

    fun getVoiceSummary(period: ReportPeriod, customRange: DateRange? = null): String {
        val summary = getSummary(period, customRange)
        val periodName = getPeriodNameSw(period)
        val deals = getBestDeals()

        return buildString {
            append("Wauzaji $periodName: ${suppliers.size}. ")
            summary.totalSpendBySupplier.entries
                .sortedByDescending { it.value }
                .take(3)
                .forEach { (name, amount) ->
                    append("$name: KES ${"%,.0f".format(amount)}. ")
                }
            if (summary.priceComparisonAlerts.isNotEmpty()) {
                val alert = summary.priceComparisonAlerts.first()
                append("Okoa pesa: ${alert.messageSw} ")
            }
            if (deals.isNotEmpty()) {
                val bestDeal = deals.first()
                append("Bei bora: ${bestDeal.productId} kutoka ${bestDeal.bestSupplierName} kwa KES ${"%.0f".format(bestDeal.bestPrice)}.")
            }
        }
    }

    fun getEnglishSummary(period: ReportPeriod, customRange: DateRange? = null): String {
        val summary = getSummary(period, customRange)
        val deals = getBestDeals()

        return buildString {
            append("Suppliers: ${suppliers.size}. ")
            summary.totalSpendBySupplier.entries
                .sortedByDescending { it.value }
                .take(3)
                .forEach { (name, amount) ->
                    append("$name: KES ${"%,.0f".format(amount)}. ")
                }
            if (summary.priceComparisonAlerts.isNotEmpty()) {
                append("${summary.priceComparisonAlerts.size} savings opportunities found. ")
            }
            if (deals.isNotEmpty()) {
                append("Best deal: ${deals.first().productId} at ${deals.first().bestSupplierName}.")
            }
        }
    }

    // ── Helpers ────────────────────────────────

    private fun filterOrders(range: DateRange): List<SupplierOrder> {
        return orders.filter { order ->
            !order.date.isBefore(range.start) && !order.date.isAfter(range.end)
        }
    }

    private fun resolveDateRange(period: ReportPeriod, custom: DateRange?): DateRange {
        return when (period) {
            ReportPeriod.TODAY -> DateRange.today()
            ReportPeriod.YESTERDAY -> {
                val yesterday = LocalDate.now().minusDays(1)
                DateRange(yesterday, yesterday)
            }
            ReportPeriod.THIS_WEEK -> DateRange.thisWeek()
            ReportPeriod.LAST_WEEK -> {
                val end = LocalDate.now().minusDays(LocalDate.now().dayOfWeek.value.toLong())
                val start = end.minusDays(6)
                DateRange(start, end)
            }
            ReportPeriod.THIS_MONTH -> DateRange.thisMonth()
            ReportPeriod.LAST_MONTH -> {
                val firstOfThisMonth = LocalDate.now().withDayOfMonth(1)
                val end = firstOfThisMonth.minusDays(1)
                val start = end.withDayOfMonth(1)
                DateRange(start, end)
            }
            ReportPeriod.CUSTOM -> custom ?: DateRange.today()
        }
    }

    private fun getPeriodNameSw(period: ReportPeriod): String = when (period) {
        ReportPeriod.TODAY -> "ya leo"
        ReportPeriod.YESTERDAY -> "ya jana"
        ReportPeriod.THIS_WEEK -> "ya wiki hii"
        ReportPeriod.LAST_WEEK -> "ya wiki iliyopita"
        ReportPeriod.THIS_MONTH -> "ya mwezi huu"
        ReportPeriod.LAST_MONTH -> "ya mwezi uliopita"
        ReportPeriod.CUSTOM -> "ya kipindi hiki"
    }
}

// ── Supporting data classes ──────────────────

data class SupplierOrder(
    val id: String,
    val supplierId: String,
    val productId: String,
    val quantity: Int,
    val pricePerUnit: Double,
    val totalCost: Double,
    val date: LocalDate = LocalDate.now(),
    val deliveredOnTime: Boolean = true,
    val qualityOk: Boolean = true
)

private data class PricePoint(
    val supplierId: String,
    val productId: String,
    val price: Double,
    val date: LocalDate,
    val quantity: Int
)

data class SupplierReliability(
    val supplierId: String,
    val name: String,
    val totalOrders: Int,
    val onTimeOrders: Int,
    val reliabilityPercent: Double,
    val qualityRating: Double,
    val averagePrice: Double,
    val grade: SupplierGrade
)

enum class SupplierGrade {
    A, B, C, D, F
}

data class PriceTrend(
    val productId: String,
    val latestPrice: Double,
    val previousPrice: Double,
    val change: Double,
    val changePercent: Double,
    val direction: TrendDirection,
    val supplierId: String,
    val date: LocalDate,
    val messageEn: String,
    val messageSw: String
)

data class BestDeal(
    val productId: String,
    val bestSupplierId: String,
    val bestSupplierName: String,
    val bestPrice: Double,
    val marketAverage: Double,
    val savingsPerUnit: Double,
    val savingsPercent: Double
)
