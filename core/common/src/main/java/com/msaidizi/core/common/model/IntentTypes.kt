package com.msaidizi.core.common.model

/**
 * Data completeness scoring for backend optimization.
 *
 * Tracks how complete each transaction's data is. Higher completeness
 * means the backend can make better calculations for Alama Score,
 * Soko Pulse, and Distribution Intelligence.
 *
 * ## Design Rule
 * If the worker omits price, Msaidizi should ask "Bei ngapi?" before
 * confirming. The backend needs prices for Alama Score and Soko Pulse.
 * Data completeness > conversational speed.
 */
data class DataCompleteness(
    val hasItem: Boolean = false,
    val hasQuantity: Boolean = false,
    val hasPrice: Boolean = false,
    val hasCategory: Boolean = false,
    val hasLocation: Boolean = false,
    val hasPaymentMethod: Boolean = false,
    val hasCustomer: Boolean = false,
    val hasSupplier: Boolean = false
) {
    /**
     * Weighted completeness score (0.0 - 1.0).
     */
    val score: Float
        get() {
            var total = 0f
            var present = 0f

            // Item (essential) — weight 3
            total += 3f; if (hasItem) present += 3f
            // Quantity — weight 2
            total += 2f; if (hasQuantity) present += 2f
            // Price — weight 3
            total += 3f; if (hasPrice) present += 3f
            // Category — weight 1
            total += 1f; if (hasCategory) present += 1f
            // Location — weight 1
            total += 1f; if (hasLocation) present += 1f
            // Payment method — weight 1
            total += 1f; if (hasPaymentMethod) present += 1f
            // Customer — weight 0.5
            total += 0.5f; if (hasCustomer) present += 0.5f
            // Supplier — weight 0.5
            total += 0.5f; if (hasSupplier) present += 0.5f

            return if (total > 0) present / total else 0f
        }

    /**
     * Whether a follow-up question is needed.
     * Missing essential fields (item, price) trigger follow-up.
     */
    fun needsFollowUp(): Boolean = !hasItem || !hasPrice

    /**
     * List of missing field names.
     */
    val missingFields: List<String>
        get() = buildList {
            if (!hasItem) add("item")
            if (!hasQuantity) add("quantity")
            if (!hasPrice) add("price")
            if (!hasCategory) add("category")
            if (!hasLocation) add("location")
            if (!hasPaymentMethod) add("paymentMethod")
            if (!hasCustomer) add("customer")
            if (!hasSupplier) add("supplier")
        }

    companion object {
        /**
         * Create from a Transaction, checking which fields are populated.
         */
        fun fromTransaction(tx: com.msaidizi.core.common.model.Transaction): DataCompleteness {
            return DataCompleteness(
                hasItem = tx.item.isNotBlank(),
                hasQuantity = tx.quantity > 0,
                hasPrice = tx.totalAmount > 0,
                hasCategory = tx.category.isNotBlank(),
                hasLocation = tx.locationLat != null,
                hasPaymentMethod = tx.paymentMethod.isNotBlank() && tx.paymentMethod != "cash",
                hasCustomer = tx.customer.isNotBlank(),
                hasSupplier = tx.supplier.isNotBlank()
            )
        }
    }
}

/**
 * Intent classification result.
 */
data class IntentResult(
    val intent: IntentType,
    val confidence: Float = 0f,
    val domain: Domain = Domain.FINANCIAL,
    val extractedData: Map<String, Any> = emptyMap(),
    val needsLLM: Boolean = false,
    val language: String = "sw",
    val dialect: String = ""
)

/**
 * Intent types supported by Msaidizi.
 */
enum class IntentType {
    // Financial — Transactions
    SALE,
    PURCHASE,
    EXPENSE,
    SPOLIAGE,

    // Financial — Queries
    CHECK_BALANCE,
    PROFIT_QUERY,
    STOCK_QUERY,
    DAILY_SUMMARY,
    WEEKLY_SUMMARY,
    MONTHLY_SUMMARY,

    // Financial — Receipt
    RECEIPT_SCAN,

    // Credit
    LOAN_RECORD,
    LOAN_QUERY,
    LOAN_REPORT,
    LOAN_DEADLINE,
    CREDIT_SCORE,
    DEBT_ADVICE,

    // Goals
    GOAL_CREATE,
    GOAL_PROGRESS,
    GOAL_REPORT,
    GOAL_TIME_FORECAST,
    GOAL_ADJUST,
    GOAL_ENCOURAGEMENT,

    // Giving/Tithe
    GIVING_RECORD,
    GIVING_QUERY,
    GIVING_GOAL,

    // Education
    ASK_ADVICE,
    MINDSET_LESSON,
    HABITS_CHECK,

    // Gamification
    BADGE_QUERY,
    LEADERBOARD,

    // General
    GREETING,
    HELP,
    CORRECTION,
    CANCEL,
    UNKNOWN
}

/**
 * Domain classification for intent routing.
 */
enum class Domain {
    FINANCIAL,
    CREDIT,
    GOALS,
    EDUCATION,
    GAMIFICATION
}

/**
 * Agent response — the output of the superagent.
 */
data class AgentResponse(
    val text: String,
    val type: ResponseType = ResponseType.TRANSACTION_RECORDED,
    val shouldSpeak: Boolean = true,
    val data: Map<String, String> = emptyMap(),
    val confidence: Float = 1.0f,
    val language: String = "sw"
)

/**
 * Response types for categorization.
 */
enum class ResponseType {
    TRANSACTION_RECORDED,
    QUERY_RESULT,
    ADVICE,
    CLARIFICATION,
    INFORMATION,
    ERROR,
    GREETING,
    BRIEFING,
    ALERT,
    GOAL_UPDATE,
    CREDIT_ASSESSMENT
}

/**
 * Resolved intent — intent enriched with context.
 */
data class ResolvedIntent(
    val originalText: String,
    val intent: IntentResult,
    val context: Map<String, Any> = emptyMap(),
    val language: String = "sw",
    val dialect: String = ""
)

/**
 * Spoilage reasons for waste tracking.
 */
enum class SpoilageReason {
    EXPIRED,
    DAMAGED,
    PEST,
    POWER_OUTAGE,
    THEFT,
    QUALITY_DEGRADED,
    OVERSTOCK,
    OTHER
}

/**
 * Spoilage record for loss tracking.
 */
data class SpoilageRecord(
    val id: Long = 0,
    val inventoryItemId: Long = 0,
    val itemName: String = "",
    val quantitySpoiled: Double = 0.0,
    val unit: String = "pieces",
    val unitCost: Double = 0.0,
    val estimatedCost: Double = 0.0,
    val reason: SpoilageReason = SpoilageReason.EXPIRED,
    val reasonDetail: String = "",
    val recordedAt: Long = System.currentTimeMillis(),
    val locationName: String = "",
    val preventable: Boolean = true,
    val syncedAt: Long? = null
)

/**
 * Capabilities unlocked at each Alama Score tier.
 */
enum class Capability {
    // Tier 0: MTOTO
    TRANSACTION_RECORDING,
    BASIC_SUMMARY,

    // Tier 1: MBEGU
    DAILY_BRIEFING,
    PROFIT_TRACKING,
    GOAL_SETTING,
    BASIC_ADVICE,

    // Tier 2: MZAZI
    WEEKLY_REPORT,
    CASH_FLOW_FORECAST,
    STOCK_ALERTS,
    SAVINGS_RECOMMENDATIONS,
    ALAMA_SCORE_VISIBLE,

    // Tier 3: MKUU
    CREDIT_READINESS,
    MONTHLY_REPORT,
    MARKET_INTELLIGENCE,
    BUSINESS_OPTIMIZATION,
    PEER_COMPARISON,

    // Tier 4: JIJI
    FORMAL_FINANCE_ELIGIBLE,
    INSURANCE_ACCESS,
    SUPPLY_CHAIN_OPTIMIZATION,
    TAX_ESTIMATION,

    // Tier 5: DUNIA
    FINANCIAL_IDENTITY_EXPORT,
    BANK_CREDIT_SCORE,
    BUSINESS_EXPANSION_LOANS,
    SUPPLIER_CREDIT_TERMS
}
