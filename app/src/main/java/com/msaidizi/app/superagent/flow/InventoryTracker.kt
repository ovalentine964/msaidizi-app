package com.msaidizi.app.superagent.flow

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * InventoryTracker — "Stock yangu ikoje?"
 * Tracks stock levels, turnover, dead stock, and reorder alerts.
 * Helps workers know: what to buy, when to buy, and what's wasting money.
 */
class InventoryTracker {

    private val products = mutableMapOf<String, Product>()
    private val inventory = mutableMapOf<String, InventoryState>()
    private val salesHistory = mutableListOf<InventorySaleRecord>()

    // ── Data Management ────────────────────────

    fun registerProduct(product: Product) {
        products[product.id] = product
        inventory.putIfAbsent(product.id, InventoryState(
            productId = product.id,
            currentStock = 0,
            lastRestocked = LocalDate.now(),
            lastSoldDate = null,
            totalSoldLast30Days = 0
        ))
    }

    fun addStock(productId: String, quantity: Int, costPerUnit: Double) {
        val current = inventory[productId] ?: return
        inventory[productId] = current.copy(
            currentStock = current.currentStock + quantity,
            lastRestocked = LocalDate.now(),
            costPerUnit = costPerUnit
        )
    }

    fun recordSale(productId: String, quantity: Int) {
        val current = inventory[productId] ?: return
        inventory[productId] = current.copy(
            currentStock = (current.currentStock - quantity).coerceAtLeast(0),
            lastSoldDate = LocalDate.now(),
            totalSoldLast30Days = current.totalSoldLast30Days + quantity
        )
        salesHistory.add(InventorySaleRecord(
            productId = productId,
            quantity = quantity,
            date = LocalDate.now()
        ))
    }

    fun clearData() {
        inventory.clear()
        products.clear()
        salesHistory.clear()
    }

    // ── Inventory Summary ──────────────────────

    /**
     * Full inventory picture.
     * "Stock yangu ikoje? Nini ninunue? Nini imebaki?"
     */
    fun getSummary(): InventorySummary {
        val items = inventory.map { (productId, state) ->
            val product = products[productId]
            val avgDailySales = calculateAverageDailySales(productId)
            val daysSinceRestock = ChronoUnit.DAYS.between(state.lastRestocked, LocalDate.now()).toInt()
            val daysSinceSale = state.lastSoldDate?.let {
                ChronoUnit.DAYS.between(it, LocalDate.now()).toInt()
            } ?: 999

            InventoryItem(
                productId = productId,
                productName = product?.name ?: productId,
                currentStock = state.currentStock,
                costPerUnit = state.costPerUnit,
                sellingPricePerUnit = product?.sellingPrice ?: 0.0,
                lastRestocked = state.lastRestocked,
                daysSinceRestock = daysSinceRestock,
                averageDailySales = avgDailySales,
                isDeadStock = daysSinceSale >= 14 && state.currentStock > 0,
                daysOfStockLeft = if (avgDailySales > 0) state.currentStock / avgDailySales else 999.0
            )
        }

        val totalStockValue = items.sumOf { it.currentStock * it.costPerUnit }
        val totalItems = items.sumOf { it.currentStock }
        val deadStock = items.filter { it.isDeadStock }
        val deadStockValue = deadStock.sumOf { it.currentStock * it.costPerUnit }
        val lowStockAlerts = items.filter { it.daysOfStockLeft < 3 && it.averageDailySales > 0 }
        val averageTurnover = items.filter { it.averageDailySales > 0 }
            .map { it.daysOfStockLeft }
            .average()

        val reorderSuggestions = generateReorderSuggestions(items)

        return InventorySummary(
            totalStockValue = totalStockValue,
            totalItems = totalItems,
            products = items.sortedBy { it.daysOfStockLeft },
            deadStock = deadStock,
            deadStockValue = deadStockValue,
            lowStockAlerts = lowStockAlerts,
            averageTurnoverDays = if (averageTurnover.isNaN()) 0.0 else averageTurnover,
            reorderSuggestions = reorderSuggestions
        )
    }

    // ── Stock Level Alerts ─────────────────────

    /**
     * What needs restocking NOW?
     * "Nini inakunishwa?"
     */
    fun getLowStockAlerts(): List<InventoryItem> {
        return getSummary().lowStockAlerts
    }

    /**
     * What's sitting there doing nothing? Dead stock = money tied up.
     * "Nini imebaki na haifanyi kazi?"
     */
    fun getDeadStock(): List<InventoryItem> {
        return getSummary().deadStock
    }

    /**
     * How much money is tied up in stock?
     * "Pesa ngapi imeshikwa na stock?"
     */
    fun getStockValue(): Double {
        return inventory.entries.sumOf { (productId, state) ->
            val product = products[productId]
            state.currentStock * (product?.costPrice ?: state.costPerUnit)
        }
    }

    // ── Reorder Suggestions ────────────────────

    /**
     * Smart reorder suggestions based on sales velocity.
     * "Ninunue nini? Ngapi? Lini?"
     */
    fun getReorderSuggestions(): List<ReorderSuggestion> {
        return generateReorderSuggestions(getSummary().products)
    }

    private fun generateReorderSuggestions(items: List<InventoryItem>): List<ReorderSuggestion> {
        return items.filter { it.averageDailySales > 0 && !it.isDeadStock }
            .map { item ->
                val daysLeft = item.daysOfStockLeft
                val urgency = when {
                    daysLeft <= 1 -> ReorderUrgency.CRITICAL
                    daysLeft <= 3 -> ReorderUrgency.HIGH
                    daysLeft <= 7 -> ReorderUrgency.MEDIUM
                    else -> ReorderUrgency.LOW
                }

                // Suggest ordering 7-14 days worth of stock
                val targetDays = 14
                val suggestedQty = ((item.averageDailySales * targetDays) - item.currentStock)
                    .toInt()
                    .coerceAtLeast(0)
                val estimatedCost = suggestedQty * item.costPerUnit

                val reason = when (urgency) {
                    ReorderUrgency.CRITICAL -> "Stock ya ${item.productName} inakaribia kuisha!"
                    ReorderUrgency.HIGH -> "${item.productName} itaisha siku ${daysLeft.toInt()}."
                    ReorderUrgency.MEDIUM -> "${item.productName} ina siku ${daysLeft.toInt()} tu."
                    ReorderUrgency.LOW -> "${item.productName} iko sawa kwa sasa."
                }

                val reasonSw = when (urgency) {
                    ReorderUrgency.CRITICAL -> "Stock ya ${item.productName} inakaribia kuisha! Nunua sasa!"
                    ReorderUrgency.HIGH -> "${item.productName} itaisha siku ${daysLeft.toInt()}. Nunua haraka."
                    ReorderUrgency.MEDIUM -> "${item.productName} ina siku ${daysLeft.toInt()} tu. Panga kununua."
                    ReorderUrgency.LOW -> "${item.productName} iko sawa kwa sasa."
                }

                ReorderSuggestion(
                    productId = item.productId,
                    productName = item.productName,
                    currentStock = item.currentStock,
                    suggestedOrderQuantity = suggestedQty,
                    estimatedCost = estimatedCost,
                    urgency = urgency,
                    reason = reason,
                    reasonSw = reasonSw
                )
            }
            .sortedBy { urgencyOrder(it.urgency) }
    }

    // ── Turnover Analysis ──────────────────────

    /**
     * How fast does stock sell? Lower is better.
     * "Stock inauzwa haraka kiasi gani?"
     */
    fun getTurnoverAnalysis(): TurnoverAnalysis {
        val items = getSummary().products.filter { it.averageDailySales > 0 }

        if (items.isEmpty()) {
            return TurnoverAnalysis(
                averageTurnoverDays = 0.0,
                fastestProduct = null,
                slowestProduct = null,
                items = emptyList(),
                messageEn = "No sales data yet to calculate turnover.",
                messageSw = "Hakuna data ya mauzo bado."
            )
        }

        val fastest = items.minByOrNull { it.daysOfStockLeft }
        val slowest = items.maxByOrNull { it.daysOfStockLeft }
        val avgTurnover = items.map { it.daysOfStockLeft }.average()

        return TurnoverAnalysis(
            averageTurnoverDays = avgTurnover,
            fastestProduct = fastest?.productName,
            slowestProduct = slowest?.productName,
            items = items.sortedBy { it.daysOfStockLeft },
            messageEn = buildString {
                append("Average turnover: ${"%.1f".format(avgTurnover)} days. ")
                append("Fastest: ${fastest?.productName} (${fastest?.daysOfStockLeft?.toInt()} days). ")
                append("Slowest: ${slowest?.productName} (${slowest?.daysOfStockLeft?.toInt()} days).")
            },
            messageSw = buildString {
                append("Wastani wa mzunguko: siku ${"%.1f".format(avgTurnover)}. ")
                append("Haraka zaidi: ${fastest?.productName} (siku ${fastest?.daysOfStockLeft?.toInt()}). ")
                append("Pole zaidi: ${slowest?.productName} (siku ${slowest?.daysOfStockLeft?.toInt()}).")
            }
        )
    }

    // ── Voice Summary ──────────────────────────

    fun getVoiceSummary(): String {
        val summary = getSummary()
        val deadStockStr = if (summary.deadStock.isNotEmpty()) {
            val names = summary.deadStock.joinToString(", ") { it.productName }
            " Stock ambayo haifanyi kazi: $names. Hii ni pesa imeshikwa!"
        } else ""

        val lowStockStr = if (summary.lowStockAlerts.isNotEmpty()) {
            val names = summary.lowStockAlerts.joinToString(", ") { it.productName }
            " Inahitaji kununua haraka: $names."
        } else ""

        return buildString {
            append("Stock yako: thamani KES ${"%,.0f".format(summary.totalStockValue)}, ")
            append("jumla ya vitu ${summary.totalItems}. ")
            if (summary.averageTurnoverDays > 0) {
                append("Wastani wa siku za kuuza: ${"%.0f".format(summary.averageTurnoverDays)}. ")
            }
            append(lowStockStr)
            append(deadStockStr)
        }
    }

    // ── Helpers ────────────────────────────────

    private fun calculateAverageDailySales(productId: String): Double {
        val thirtyDaysAgo = LocalDate.now().minusDays(30)
        val recentSales = salesHistory.filter {
            it.productId == productId && it.date.isAfter(thirtyDaysAgo)
        }
        val totalSold = recentSales.sumOf { it.quantity }
        return totalSold / 30.0
    }

    private fun urgencyOrder(urgency: ReorderUrgency): Int = when (urgency) {
        ReorderUrgency.CRITICAL -> 0
        ReorderUrgency.HIGH -> 1
        ReorderUrgency.MEDIUM -> 2
        ReorderUrgency.LOW -> 3
    }
}

// ── Internal data ────────────────────────────

private data class InventoryState(
    val productId: String,
    val currentStock: Int,
    val costPerUnit: Double = 0.0,
    val lastRestocked: LocalDate,
    val lastSoldDate: LocalDate?,
    val totalSoldLast30Days: Int
)

private data class InventorySaleRecord(
    val productId: String,
    val quantity: Int,
    val date: LocalDate
)

data class TurnoverAnalysis(
    val averageTurnoverDays: Double,
    val fastestProduct: String?,
    val slowestProduct: String?,
    val items: List<InventoryItem>,
    val messageEn: String,
    val messageSw: String
)
