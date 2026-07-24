package com.msaidizi.core.common.model

/**
 * Represents a financial transaction in the Msaidizi system.
 *
 * Every transaction is a **proof point** in the M-KOPA model — evidence that
 * the worker's business exists and generates revenue. The data model is
 * optimized for what the backend intelligence engine needs to compute
 * Alama Score, Soko Pulse, and Distribution Intelligence.
 *
 * ## Voice → Data Mapping
 * ```
 * Worker says: "Nimeuziwa mandazi kumi, mia mbili"
 * → type=SALE, item="mandazi", quantity=10, totalAmount=200.0
 * ```
 *
 * ## Backend Contract
 * Every field maps to a backend need:
 * - WHAT → item, category, subcategory, productCode
 * - HOW MANY → quantity, unit
 * - HOW MUCH → unitPrice, totalAmount, costBasis, margin
 * - WHEN → createdAt, timeOfDay, dayOfWeek
 * - WHERE → locationLat, locationLng, locationName
 * - WHO → customer, supplier
 * - HOW → paymentMethod, mpesaCode
 * - PROOF → confidence, language, verificationSource
 */
data class Transaction(
    val id: Long = 0,

    // ═══ WHAT: Product identification ═══
    val type: TransactionType,
    val item: String,
    val category: String = "",
    val subcategory: String = "",
    val productCode: String = "",

    // ═══ HOW MANY: Quantity ═══
    val quantity: Double = 1.0,
    val unit: String = "pieces",

    // ═══ HOW MUCH: Pricing ═══
    val unitPrice: Double = 0.0,
    val totalAmount: Double,
    val costBasis: Double = 0.0,
    val margin: Double = 0.0,
    val marginPercent: Double = 0.0,
    val currency: String = "KSh",

    // ═══ WHEN: Temporal data ═══
    val createdAt: Long = System.currentTimeMillis() / 1000,
    val timeOfDay: String = "",
    val dayOfWeek: Int = 0,
    val isWeekend: Boolean = false,
    val month: Int = 0,

    // ═══ WHERE: Location data ═══
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val locationName: String = "",
    val marketId: String = "",

    // ═══ WHO: Customer/Supplier ═══
    val customer: String = "",
    val supplier: String = "",
    val isRecurringCustomer: Boolean = false,

    // ═══ HOW: Payment & context ═══
    val paymentMethod: String = "cash",
    val mpesaCode: String = "",
    val isOnCredit: Boolean = false,
    val creditDueDate: Long? = null,

    // ═══ PROOF: Verification data ═══
    val confidence: Float = 1.0f,
    val language: String = "sw",
    val dialect: String = "",
    val hasReceipt: Boolean = false,
    val receiptImageUrl: String = "",
    val verificationSource: String = "voice",

    // ═══ SYNC: Cloud sync ═══
    val syncedAt: Long? = null,
    val syncBatchId: String = "",
    val backendTransactionId: String = "",

    // ═══ NOTES ═══
    val notes: String = ""
) {
    /**
     * Calculate margin if not explicitly set.
     * Margin = totalAmount - costBasis
     */
    val calculatedMargin: Double
        get() = if (margin != 0.0) margin else totalAmount - costBasis

    /**
     * Calculate margin percentage if not explicitly set.
     * marginPercent = margin / totalAmount
     */
    val calculatedMarginPercent: Double
        get() = if (marginPercent != 0.0) marginPercent
                else if (totalAmount > 0) calculatedMargin / totalAmount
                else 0.0

    /**
     * Whether this transaction has been synced to the backend.
     */
    val isSynced: Boolean
        get() = syncedAt != null

    /**
     * Data completeness score (0.0 - 1.0) for backend optimization.
     * Higher completeness = more useful for Alama Score calculation.
     */
    val dataCompleteness: Float
        get() {
            var score = 0f
            var total = 0f

            // Item (essential) — weight 3
            total += 3f; if (item.isNotBlank()) score += 3f
            // Quantity — weight 2
            total += 2f; if (quantity > 0) score += 2f
            // Price — weight 3
            total += 3f; if (totalAmount > 0) score += 3f
            // Category (auto-classified) — weight 1
            total += 1f; if (category.isNotBlank()) score += 1f
            // Location — weight 1
            total += 1f; if (locationLat != null) score += 1f
            // Payment method — weight 1
            total += 1f; if (paymentMethod.isNotBlank() && paymentMethod != "cash") score += 1f
            // Customer — weight 0.5
            total += 0.5f; if (customer.isNotBlank()) score += 0.5f
            // Supplier — weight 0.5
            total += 0.5f; if (supplier.isNotBlank()) score += 0.5f

            return if (total > 0) score / total else 0f
        }

    /**
     * Convert to map for proof point data storage.
     */
    fun toMap(): Map<String, String> = buildMap {
        put("type", type.name)
        put("item", item)
        put("quantity", quantity.toString())
        put("totalAmount", totalAmount.toString())
        put("margin", calculatedMargin.toString())
        put("paymentMethod", paymentMethod)
        if (locationName.isNotBlank()) put("location", locationName)
        if (customer.isNotBlank()) put("customer", customer)
    }
}

/**
 * Types of financial transactions.
 */
enum class TransactionType {
    /** Worker sold something — "Nimeuziwa mandazi kumi" */
    SALE,
    /** Worker bought stock/supplies — "Nimenunua unga kwa 500" */
    PURCHASE,
    /** Worker incurred an expense — "Nimetumia 200 kwa usafiri" */
    EXPENSE,
    /** Spoilage/waste — "Nyanya tatu zimeharibika" */
    SPOILAGE
}

/**
 * Extended transaction for service-based workers (e.g., fundi, mama fua).
 * Captures service-specific data like labor time, skill level, and materials used.
 */
data class ServiceTransaction(
    val transaction: Transaction,
    /** Type of service provided */
    val serviceType: String = "",
    /** Duration of service in minutes */
    val durationMinutes: Int = 0,
    /** Skill level required: "basic", "intermediate", "advanced" */
    val skillLevel: String = "basic",
    /** Materials used (item names, comma-separated) */
    val materialsUsed: String = "",
    /** Cost of materials used */
    val materialsCost: Double = 0.0,
    /** Whether the service is recurring (e.g., weekly cleaning) */
    val isRecurring: Boolean = false,
    /** Next scheduled service date (Unix timestamp) */
    val nextServiceDate: Long? = null,
    /** Location of service delivery */
    val serviceLocation: String = "",
    /** Customer satisfaction rating (1-5, if provided) */
    val satisfactionRating: Int = 0
) {
    /**
     * Pure labor profit = totalAmount - materialsCost
     */
    val laborProfit: Double
        get() = transaction.totalAmount - materialsCost

    /**
     * Effective hourly rate = laborProfit / (durationMinutes / 60)
     */
    val hourlyRate: Double
        get() {
            if (durationMinutes <= 0) return 0.0
            return laborProfit / (durationMinutes / 60.0)
        }
}
