package com.msaidizi.core.common.model

/**
 * Inventory item with perishability tracking.
 *
 * Tracks stock levels, spoilage risk, and reorder points. Critical
 * for food vendors (tomatoes expire in 3 days) and all businesses
 * that hold physical inventory.
 *
 * ## Voice Interaction
 * - "Nimenunua nyanya kumi" → adds 10 tomatoes to inventory
 * - "Nimeuziwa nyanya tano" → removes 5 from inventory
 * - "Nyanya tatu zimeharibika" → records spoilage, adjusts inventory
 */
data class InventoryItem(
    val id: Long = 0,

    // ═══ IDENTITY ═══
    /** Item name — "nyanya", "unga", "mafuta" */
    val itemName: String = "",
    /** Category — "produce", "grains", "dairy", "beverages" */
    val category: String = "",
    /** Subcategory — "vegetables", "staples", "cooking_oil" */
    val subcategory: String = "",
    /** Supplier name */
    val supplier: String = "",

    // ═══ QUANTITY ═══
    /** Current stock quantity */
    val quantity: Double = 0.0,
    /** Unit of measurement — "pieces", "kg", "liters", "bundles" */
    val unit: String = "pieces",
    /** Minimum stock level before reorder alert */
    val reorderLevel: Double = 0.0,
    /** Typical reorder quantity */
    val reorderQuantity: Double = 0.0,

    // ═══ PRICING ═══
    /** Purchase cost per unit (KSh) */
    val unitCost: Double = 0.0,
    /** Selling price per unit (KSh) */
    val sellingPrice: Double = 0.0,
    /** Total value of current stock (quantity × unitCost) */
    val totalStockValue: Double = 0.0,

    // ═══ PERISHABILITY ═══
    /**
     * Shelf life in days. 0 = non-perishable.
     *
     * Common shelf lives:
     * - nyanya (tomatoes): 3 days
     * - mboga (vegetables): 3 days
     * - maziwa (milk): 3 days
     * - samaki (fish): 2 days
     * - mayai (eggs): 21 days
     * - mahindi (maize): 180 days
     * - unga (flour): 180 days
     * - mandazi (fried dough): 2 days
     */
    val shelfLifeDays: Int = 0,
    /** When the current stock was acquired */
    val purchaseDate: Long = System.currentTimeMillis(),
    /** Calculated expiry date */
    val expiryDate: Long = 0L,
    /** Alert when this percentage of shelf life is consumed (0.0-1.0) */
    val spoilageAlertThreshold: Double = 0.8,
    /** Whether this item has been flagged as approaching expiry */
    val isApproachingExpiry: Boolean = false,
    /** Whether this item is expired */
    val isExpired: Boolean = false,

    // ═══ LOCATION ═══
    /** Storage location — "stall", "warehouse", "home", "fridge" */
    val storageLocation: String = "",
    /** Batch ID (groups items bought together) */
    val batchId: String = "",

    // ═══ TIMESTAMPS ═══
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null
) {
    /**
     * Days until expiry. Int.MAX_VALUE if non-perishable.
     */
    val daysUntilExpiry: Int
        get() {
            if (shelfLifeDays <= 0) return Int.MAX_VALUE
            val elapsed = (System.currentTimeMillis() - purchaseDate) / (1000 * 60 * 60 * 24)
            return (shelfLifeDays - elapsed).toInt().coerceAtLeast(0)
        }

    /**
     * Percentage of shelf life consumed (0.0 - 1.0).
     */
    val shelfLifePercentUsed: Double
        get() {
            if (shelfLifeDays <= 0) return 0.0
            val elapsed = (System.currentTimeMillis() - purchaseDate) / (1000 * 60 * 60 * 24.0)
            return (elapsed / shelfLifeDays).coerceIn(0.0, 1.0)
        }

    /**
     * Whether this item needs attention (approaching expiry or below reorder level).
     */
    val needsAttention: Boolean
        get() = isApproachingExpiry || isExpired || quantity <= reorderLevel

    /**
     * Profit margin per unit.
     */
    val unitMargin: Double
        get() = sellingPrice - unitCost

    /**
     * Potential profit from selling all remaining stock.
     */
    val potentialProfit: Double
        get() = quantity * unitMargin

    /**
     * Update perishability status based on current time.
     */
    fun updatePerishability(): InventoryItem {
        val percentUsed = shelfLifePercentUsed
        return copy(
            isApproachingExpiry = shelfLifeDays > 0 && percentUsed >= spoilageAlertThreshold,
            isExpired = shelfLifeDays > 0 && daysUntilExpiry <= 0,
            totalStockValue = quantity * unitCost
        )
    }
}

/**
 * Default shelf life values for common items.
 * Maps item name keywords to shelf life in days.
 */
object ShelfLifeDefaults {
    val SHELF_LIFE_MAP = mapOf(
        // Produce (days)
        "nyanya" to 3,        // tomatoes
        "matunda" to 5,       // fruits
        "mboga" to 3,         // vegetables
        "maziwa" to 3,        // milk
        "samaki" to 2,        // fish
        "nyama" to 3,         // meat
        "mayai" to 21,        // eggs
        "ndizi" to 5,         // bananas
        "viazi" to 14,        // potatoes
        "vitunguu" to 30,     // onions
        "vitunguu maji" to 30, // onions
        "pilipili" to 7,      // peppers
        "sukari wiki" to 7,   // pumpkin leaves

        // Grains & staples (days)
        "mahindi" to 180,     // maize (6 months)
        "mchele" to 365,      // rice
        "unga" to 180,        // flour
        "njugu" to 120,       // groundnuts
        "maharage" to 365,    // beans
        "ngano" to 180,       // wheat

        // Processed foods (days)
        "mandazi" to 2,       // fried dough
        "chapati" to 2,       // flatbread
        "mkate" to 5,         // bread
        "keki" to 5,          // cake
        "biskuti" to 90,      // biscuits

        // Beverages
        "chai" to 730,        // tea leaves (2 years)
        "kahawa" to 365,      // coffee
        "juisi" to 3,         // juice

        // Non-perishable (0 = never expires)
        "sabuni" to 0,        // soap
        "mafuta" to 0,        // oil
        "pembe" to 0,         // charcoal
        "dawa" to 0           // medicine
    )

    /**
     * Get shelf life for an item by name matching.
     * Returns 0 (non-perishable) if no match found.
     */
    fun getShelfLife(itemName: String): Int {
        val normalized = itemName.lowercase().trim()
        return SHELF_LIFE_MAP.entries
            .firstOrNull { normalized.contains(it.key) }?.value
            ?: 0
    }
}
