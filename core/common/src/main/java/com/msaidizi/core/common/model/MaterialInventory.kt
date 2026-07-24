package com.msaidizi.core.common.model

/**
 * Material inventory with reorder levels for supply chain tracking.
 *
 * Extends basic inventory with supply chain intelligence:
 * - Reorder points (when to buy more)
 * - Lead times (how long delivery takes)
 * - Supplier tracking (who supplies what)
 * - Price trends (is this getting more expensive?)
 *
 * ## Soko Pulse Integration
 * MaterialInventory data feeds into the backend's Soko Pulse product
 * for market-wide price intelligence and supply chain insights.
 */
data class MaterialInventory(
    val id: Long = 0,

    // ═══ IDENTITY ═══
    /** Material name — "unga wa mahindi", "mafuta ya kupikia" */
    val materialName: String = "",
    /** Category — "staple", "cooking", "packaging", "cleaning" */
    val category: String = "",
    /** Supplier name — "Juma's Wholesale", "Gikomba Market" */
    val supplier: String = "",
    /** Supplier phone (for ordering) */
    val supplierPhone: String = "",

    // ═══ STOCK LEVELS ═══
    /** Current quantity in stock */
    val currentStock: Double = 0.0,
    /** Unit of measurement — "kg", "liters", "pieces", "bags" */
    val unit: String = "kg",
    /**
     * Reorder level — trigger alert when stock drops below this.
     * Based on: average daily usage × lead time days × safety factor
     */
    val reorderLevel: Double = 0.0,
    /** Reorder quantity — how much to order when restocking */
    val reorderQuantity: Double = 0.0,
    /** Safety stock — minimum buffer to avoid stockouts */
    val safetyStock: Double = 0.0,

    // ═══ PRICING ═══
    /** Current purchase price per unit (KSh) */
    val currentPrice: Double = 0.0,
    /** Previous purchase price (for trend tracking) */
    val previousPrice: Double = 0.0,
    /** Average price over last 30 days */
    val avgPrice30Days: Double = 0.0,
    /** Price trend — "rising", "falling", "stable" */
    val priceTrend: String = "stable",

    // ═══ USAGE PATTERNS ═══
    /** Average daily usage (units per day) */
    val avgDailyUsage: Double = 0.0,
    /** Days of stock remaining at current usage rate */
    val daysOfStockRemaining: Int = 0,
    /** Last restock date */
    val lastRestockDate: Long = System.currentTimeMillis(),
    /** Typical lead time in days (order to delivery) */
    val leadTimeDays: Int = 1,

    // ═══ ALERTS ═══
    /** Whether stock is below reorder level */
    val isBelowReorderLevel: Boolean = false,
    /** Whether stock will run out before next delivery */
    val willStockOut: Boolean = false,
    /** Whether price has increased significantly (>10%) */
    val priceIncreased: Boolean = false,

    // ═══ TIMESTAMPS ═══
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null
) {
    /**
     * Whether this material needs to be reordered now.
     */
    val needsReorder: Boolean
        get() = currentStock <= reorderLevel || willStockOut

    /**
     * Suggested order quantity based on usage patterns.
     * = avgDailyUsage × (leadTimeDays + 7) - currentStock + safetyStock
     * Orders enough for lead time + 1 week buffer.
     */
    val suggestedOrderQuantity: Double
        get() {
            val needed = avgDailyUsage * (leadTimeDays + 7) - currentStock + safetyStock
            return needed.coerceAtLeast(reorderQuantity).coerceAtLeast(0.0)
        }

    /**
     * Estimated cost of suggested order.
     */
    val suggestedOrderCost: Double
        get() = suggestedOrderQuantity * currentPrice

    /**
     * Update usage patterns based on recent transactions.
     * Called periodically with last N days of usage data.
     */
    fun updateUsagePatterns(
        totalUsedLast30Days: Double,
        daysWithActivity: Int
    ): MaterialInventory {
        val avgDaily = if (daysWithActivity > 0) totalUsedLast30Days / daysWithActivity else 0.0
        val daysRemaining = if (avgDaily > 0) (currentStock / avgDaily).toInt() else Int.MAX_VALUE
        val willOut = daysRemaining < leadTimeDays

        return copy(
            avgDailyUsage = avgDaily,
            daysOfStockRemaining = daysRemaining,
            willStockOut = willOut,
            isBelowReorderLevel = currentStock <= reorderLevel,
            updatedAt = System.currentTimeMillis()
        )
    }
}

/**
 * Reorder alert — generated when a material needs restocking.
 */
data class ReorderAlert(
    val materialName: String,
    val currentStock: Double,
    val reorderLevel: Double,
    val suggestedQuantity: Double,
    val estimatedCost: Double,
    val supplier: String,
    val supplierPhone: String,
    val urgency: ReorderUrgency,
    val message: String
)

/**
 * Reorder urgency levels.
 */
enum class ReorderUrgency {
    /** Stock will last > 7 days */
    LOW,
    /** Stock will last 3-7 days */
    MEDIUM,
    /** Stock will last 1-3 days */
    HIGH,
    /** Stock will run out before delivery */
    CRITICAL
}
