package com.msaidizi.core.common.model

/**
 * Tool tracker for workers who use physical tools (fundi, artisans, boda boda).
 *
 * Tracks tool ownership, condition, depreciation, and maintenance schedules.
 * Helps workers understand the true cost of their business operations.
 *
 * ## Examples
 * - Fundi's drill: bought for KSh 5,000, depreciates over 2 years
 * - Boda boda: bought for KSh 80,000, depreciates over 5 years
 * - Mama fua's iron: bought for KSh 2,000, depreciates over 3 years
 */
data class ToolTracker(
    val id: Long = 0,

    // ═══ IDENTITY ═══
    /** Tool name — "drill", "boda boda", "iron", "pikipiki" */
    val toolName: String = "",
    /** Category — "power_tool", "vehicle", "hand_tool", "equipment" */
    val category: String = "",
    /** Brand/model if known */
    val brand: String = "",
    /** Serial number (for valuable items) */
    val serialNumber: String = "",

    // ═══ FINANCIAL ═══
    /** Purchase price (KSh) */
    val purchasePrice: Double = 0.0,
    /** Current estimated value (KSh) */
    val currentValue: Double = 0.0,
    /** Depreciation method — "straight_line", "declining_balance" */
    val depreciationMethod: String = "straight_line",
    /**
     * Useful life in months.
     * - Hand tools: 36 months
     * - Power tools: 24 months
     * - Vehicles: 60 months
     * - Electronics: 24 months
     */
    val usefulLifeMonths: Int = 24,
    /** Salvage value at end of useful life (KSh) */
    val salvageValue: Double = 0.0,
    /** Monthly depreciation amount (calculated) */
    val monthlyDepreciation: Double = 0.0,

    // ═══ CONDITION ═══
    /** Current condition — "excellent", "good", "fair", "poor", "broken" */
    val condition: String = "good",
    /** Last maintenance date */
    val lastMaintenanceDate: Long? = null,
    /** Next scheduled maintenance */
    val nextMaintenanceDate: Long? = null,
    /** Maintenance cost to date (KSh) */
    val totalMaintenanceCost: Double = 0.0,

    // ═══ TRACKING ═══
    /** Whether tool is currently in use (vs. stored/broken) */
    val isInUse: Boolean = true,
    /** Where the tool is stored/located */
    val location: String = "",
    /** Who is using it (if lent out) */
    val usedBy: String = "",
    /** Date acquired */
    val purchaseDate: Long = System.currentTimeMillis(),

    // ═══ TIMESTAMPS ═══
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null
) {
    /**
     * Age of the tool in months.
     */
    val ageMonths: Int
        get() {
            val elapsed = System.currentTimeMillis() - purchaseDate
            return (elapsed / (1000L * 60 * 60 * 24 * 30)).toInt()
        }

    /**
     * Remaining useful life in months.
     */
    val remainingLifeMonths: Int
        get() = (usefulLifeMonths - ageMonths).coerceAtLeast(0)

    /**
     * Percentage of useful life consumed (0.0 - 1.0).
     */
    val lifePercentUsed: Double
        get() = (ageMonths.toDouble() / usefulLifeMonths).coerceIn(0.0, 1.0)

    /**
     * Calculate current value using straight-line depreciation.
     * currentValue = purchasePrice - (monthlyDepreciation × ageMonths)
     */
    val calculatedValue: Double
        get() {
            val monthly = (purchasePrice - salvageValue) / usefulLifeMonths
            val depreciated = purchasePrice - (monthly * ageMonths)
            return depreciated.coerceAtLeast(salvageValue)
        }

    /**
     * Whether the tool needs maintenance.
     */
    val needsMaintenance: Boolean
        get() {
            if (nextMaintenanceDate == null) return false
            return System.currentTimeMillis() >= nextMaintenanceDate
        }

    /**
     * Whether the tool should be replaced.
     * True if: broken, fully depreciated, or repair cost > 50% of current value.
     */
    val shouldReplace: Boolean
        get() = condition == "broken" || remainingLifeMonths <= 0

    /**
     * Total cost of ownership = purchasePrice + totalMaintenanceCost
     */
    val totalCostOfOwnership: Double
        get() = purchasePrice + totalMaintenanceCost

    /**
     * Cost per month of use = totalCostOfOwnership / ageMonths
     */
    val costPerMonth: Double
        get() {
            if (ageMonths <= 0) return purchasePrice
            return totalCostOfOwnership / ageMonths
        }
}

/**
 * Default useful life for common tool categories.
 */
object ToolDefaults {
    val USEFUL_LIFE_MAP = mapOf(
        "hand_tool" to 36,       // Hammers, spanners, pliers
        "power_tool" to 24,      // Drills, grinders, saws
        "vehicle" to 60,         // Boda boda, tuk-tuk
        "pikipiki" to 48,        // Motorcycle
        "boda_boda" to 36,       // Bicycle boda
        "equipment" to 36,       // General equipment
        "electronics" to 24,     // Phones, tablets
        "sewing_machine" to 60,  // Sewing machines
        "cooking_equipment" to 36, // Pots, pans, stoves
        "fridge" to 84,          // Refrigerators (7 years)
        "generator" to 60        // Generators
    )

    fun getUsefulLife(category: String): Int {
        return USEFUL_LIFE_MAP[category.lowercase().trim()] ?: 24
    }

    fun getSalvageValue(purchasePrice: Double, category: String): Double {
        return when (category.lowercase().trim()) {
            "vehicle", "pikipiki" -> purchasePrice * 0.15  // 15% salvage
            "fridge", "generator" -> purchasePrice * 0.10  // 10% salvage
            else -> 0.0  // Most tools have no salvage value
        }
    }
}
