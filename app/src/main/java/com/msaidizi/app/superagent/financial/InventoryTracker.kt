package com.msaidizi.app.superagent.financial

import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Inventory Tracker with perishability and spoilage management.
 *
 * Tracks stock levels, predicts stockouts, and alerts on spoilage risk.
 * Designed for informal market vendors who deal with perishable goods daily.
 *
 * ## Features
 * - **Stock level tracking:** Current quantity per item
 * - **Velocity calculation:** Average daily sales per item
 * - **Stockout prediction:** Days until item runs out
 * - **Spoilage alerts:** Warnings for perishable items near expiry
 * - **Perishability classification:** HIGHLY_PERISHABLE → NON_PERISHABLE
 *
 * ## Academic Foundations
 * - **ECO 201 (Microeconomics):** Inventory management, carrying costs
 * - **STA 244 (Time Series):** Sales velocity forecasting
 * - **OPS 301 (Operations):** Economic Order Quantity (EOQ) concepts
 *
 * @author Msaidizi Financial Team
 */
class InventoryTracker {

    companion object {
        private const val TAG = "InventoryTracker"

        /** Default lookback period for velocity calculation (days) */
        private const val VELOCITY_LOOKBACK_DAYS = 14

        /** Alert when stock falls below this many days of supply */
        private const val LOW_STOCK_THRESHOLD_DAYS = 3.0

        /** Spoilage warning hours before expiry */
        private const val SPOILAGE_WARNING_HOURS = 24.0

        /** Seconds in an hour */
        private const val SECONDS_PER_HOUR = 3600L
    }

    // ═══════════════════════════════════════════════════════════════
    // STOCK LEVEL TRACKING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Update stock levels based on a transaction.
     *
     * @param currentInventory Current inventory map (item → InventoryItem)
     * @param transaction The transaction to process
     * @return Updated inventory map
     */
    fun updateStock(
        currentInventory: Map<String, InventoryItem>,
        transaction: Transaction
    ): Map<String, InventoryItem> {
        val updated = currentInventory.toMutableMap()
        val key = transaction.item.lowercase()

        when (transaction.type) {
            TransactionType.SALE -> {
                updated[key]?.let { item ->
                    updated[key] = item.copy(
                        currentStock = max(0.0, item.currentStock - transaction.quantity)
                    )
                }
            }
            TransactionType.PURCHASE -> {
                val existing = updated[key]
                if (existing != null) {
                    updated[key] = existing.copy(
                        currentStock = existing.currentStock + transaction.quantity,
                        unitCost = if (transaction.unitPrice > 0) transaction.unitPrice else existing.unitCost,
                        lastRestockDate = transaction.createdAt
                    )
                } else {
                    updated[key] = InventoryItem(
                        itemName = key,
                        category = transaction.category,
                        currentStock = transaction.quantity,
                        unitCost = transaction.unitPrice,
                        lastRestockDate = transaction.createdAt
                    )
                }
            }
            else -> { /* No stock change for other transaction types */ }
        }

        return updated
    }

    // ═══════════════════════════════════════════════════════════════
    // VELOCITY CALCULATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calculate sales velocity (average daily sales) for each item.
     *
     * @param salesHistory Recent sales transactions
     * @param lookbackDays Number of days to look back
     * @return Map of item name → daily sales velocity (units/day)
     */
    fun calculateVelocity(
        salesHistory: List<Transaction>,
        lookbackDays: Int = VELOCITY_LOOKBACK_DAYS
    ): Map<String, Double> {
        if (salesHistory.isEmpty()) return emptyMap()

        val salesByItem = salesHistory
            .filter { it.type == TransactionType.SALE }
            .groupBy { it.item.lowercase() }

        return salesByItem.mapValues { (_, sales) ->
            val totalQuantity = sales.sumOf { it.quantity }
            val daysSpan = calculateDaysSpan(sales)
            if (daysSpan > 0) totalQuantity / daysSpan else 0.0
        }
    }

    /**
     * Calculate the number of unique days spanned by a list of transactions.
     */
    private fun calculateDaysSpan(transactions: List<Transaction>): Int {
        if (transactions.isEmpty()) return 0
        val uniqueDays = transactions
            .map { it.createdAt / 86_400 }
            .distinct()
            .size
        return max(uniqueDays, 1)
    }

    // ═══════════════════════════════════════════════════════════════
    // STOCKOUT PREDICTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Predict days until stockout for each inventory item.
     *
     * @param inventory Current inventory
     * @param velocity Sales velocity per item
     * @return Map of item name → days until stockout
     */
    fun predictStockoutDays(
        inventory: Map<String, InventoryItem>,
        velocity: Map<String, Double>
    ): Map<String, Double> {
        return inventory.mapValues { (key, item) ->
            val dailyVelocity = velocity[key] ?: 0.0
            if (dailyVelocity > 0) {
                item.currentStock / dailyVelocity
            } else {
                Double.MAX_VALUE // No sales = no stockout risk
            }
        }
    }

    /**
     * Get items that need restocking soon.
     *
     * @param inventory Current inventory
     * @param velocity Sales velocity per item
     * @param thresholdDays Alert when stock falls below this many days
     * @return List of items that need restocking
     */
    fun getLowStockItems(
        inventory: Map<String, InventoryItem>,
        velocity: Map<String, Double>,
        thresholdDays: Double = LOW_STOCK_THRESHOLD_DAYS
    ): List<RestockItem> {
        val stockoutDays = predictStockoutDays(inventory, velocity)

        return stockoutDays
            .filter { it.value <= thresholdDays }
            .map { (item, daysRemaining) ->
                val inv = inventory[item]!!
                val dailyVelocity = velocity[item] ?: 0.0
                val suggestedQty = (dailyVelocity * 7).roundToInt() // 1 week supply

                RestockItem(
                    item = item,
                    currentStock = inv.currentStock,
                    dailyVelocity = dailyVelocity,
                    daysOfStockRemaining = daysRemaining,
                    suggestedQuantity = max(suggestedQty, 1),
                    estimatedCost = max(suggestedQty, 1) * inv.unitCost,
                    urgency = when {
                        daysRemaining <= 1 -> RestockUrgency.CRITICAL
                        daysRemaining <= 2 -> RestockUrgency.HIGH
                        daysRemaining <= 3 -> RestockUrgency.MEDIUM
                        else -> RestockUrgency.LOW
                    }
                )
            }
            .sortedBy { it.urgency.ordinal }
    }

    // ═══════════════════════════════════════════════════════════════
    // SPOILAGE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════

    /**
     * Check for items at risk of spoilage.
     *
     * @param inventory Current inventory
     * @param currentTime Current Unix timestamp in seconds
     * @return List of spoilage alerts
     */
    fun checkSpoilageRisk(
        inventory: Map<String, InventoryItem>,
        currentTime: Long = System.currentTimeMillis() / 1000
    ): List<SpoilageAlert> {
        val alerts = mutableListOf<SpoilageAlert>()

        for ((_, item) in inventory) {
            if (item.currentStock <= 0) continue

            // Check expiry date
            val expiryDate = item.expiryDate
            if (expiryDate != null) {
                val hoursUntilExpiry = (expiryDate - currentTime).toDouble() / SECONDS_PER_HOUR

                if (hoursUntilExpiry <= SPOILAGE_WARNING_HOURS && hoursUntilExpiry > 0) {
                    val estimatedLoss = item.currentStock * item.unitCost
                    alerts.add(
                        SpoilageAlert(
                            item = item,
                            quantityAtRisk = item.currentStock,
                            estimatedLoss = estimatedLoss,
                            hoursUntilExpiry = hoursUntilExpiry,
                            recommendation = generateSpoilageRecommendation(item, hoursUntilExpiry)
                        )
                    )
                }
            }

            // Check based on perishability and last restock
            if (item.perishability == Perishability.HIGHLY_PERISHABLE ||
                item.perishability == Perishability.PERISHABLE
            ) {
                val daysSinceRestock = (currentTime - item.lastRestockDate) / 86_400
                val shelfLifeDays = item.shelfLifeDays

                if (shelfLifeDays > 0 && daysSinceRestock >= shelfLifeDays - 1) {
                    val hoursUntilExpiry = ((shelfLifeDays - daysSinceRestock) * 24).toDouble()
                    if (hoursUntilExpiry > 0 && alerts.none { it.item.itemName == item.itemName }) {
                        alerts.add(
                            SpoilageAlert(
                                item = item,
                                quantityAtRisk = item.currentStock,
                                estimatedLoss = item.currentStock * item.unitCost,
                                hoursUntilExpiry = hoursUntilExpiry,
                                recommendation = generateSpoilageRecommendation(item, hoursUntilExpiry)
                            )
                        )
                    }
                }
            }
        }

        return alerts.sortedBy { it.hoursUntilExpiry }
    }

    /**
     * Generate a recommendation for items at risk of spoilage.
     */
    private fun generateSpoilageRecommendation(
        item: InventoryItem,
        hoursUntilExpiry: Double
    ): String {
        return when {
            hoursUntilExpiry <= 6 -> {
                "Haraka! ${item.itemName} inakaribia kuharibika. " +
                "Punguza bei 50% au gawa kwa jirani."
            }
            hoursUntilExpiry <= 12 -> {
                "${item.itemName} itaharibika ndani ya masaa 12. " +
                "Fikiria kupunguza bei 30% ili kuuza haraka."
            }
            else -> {
                "${item.itemName} inaweza kuharibika kesho. " +
                "Hakikisha unauza kwa bei ya kawaida leo."
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // INVENTORY SUMMARY
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate a summary of current inventory status.
     *
     * @param inventory Current inventory
     * @param velocity Sales velocity per item
     * @return Human-readable inventory summary in Swahili
     */
    fun getInventorySummary(
        inventory: Map<String, InventoryItem>,
        velocity: Map<String, Double>
    ): String {
        if (inventory.isEmpty()) {
            return "Hakuna bidhaa kwenye stock. Anza kununua bidhaa!"
        }

        val lowStock = getLowStockItems(inventory, velocity)
        val totalValue = inventory.values.sumOf { it.currentStock * it.unitCost }

        return buildString {
            append("📦 Muhtasari wa Stock:\n\n")

            append("Jumla ya bidhaa: ${inventory.size}\n")
            append("Thamani ya stock: KSh ${formatAmount(totalValue)}\n\n")

            if (lowStock.isNotEmpty()) {
                append("⚠️ Bidhaa zinazohitaji kununuliwa:\n")
                lowStock.forEach { item ->
                    val urgencyEmoji = when (item.urgency) {
                        RestockUrgency.CRITICAL -> "🚨"
                        RestockUrgency.HIGH -> "⚠️"
                        RestockUrgency.MEDIUM -> "📋"
                        RestockUrgency.LOW -> "ℹ️"
                    }
                    append("$urgencyEmoji ${item.itemName}: siku ${item.daysOfStockRemaining.toInt()} zimebaki\n")
                }
            } else {
                append("✅ Stock yako iko sawa!")
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
