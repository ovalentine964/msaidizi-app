package com.msaidizi.core.common.model

/**
 * Client profile for tracking recurring customer relationships.
 *
 * When a worker has repeat customers, Msaidizi learns their patterns
 * and builds a relationship profile. This enables:
 * - Credit tracking per customer
 * - Purchase pattern recognition
 * - Personalized reminders ("Mteja wako Amina hajanunua wiki hii")
 * - Customer lifetime value estimation
 *
 * ## Voice Recognition
 * Workers identify customers by name: "Amina amenunua nyanya"
 * Msaidizi auto-creates/updates the client profile.
 */
data class ClientProfile(
    val id: Long = 0,

    // ═══ IDENTITY ═══
    /** Customer name as spoken by worker — "Amina", "Mama Njeri" */
    val name: String = "",
    /** Phone number (if known, for M-Pesa matching) */
    val phoneNumber: String = "",
    /** Relationship to worker — "mtu wa kawaida", "mteja wa kudumu" */
    val relationship: String = "",

    // ═══ TRANSACTION HISTORY ═══
    /** Total number of transactions with this customer */
    val totalTransactions: Int = 0,
    /** Total amount spent by this customer (KSh) */
    val totalSpent: Double = 0.0,
    /** Average transaction amount (KSh) */
    val avgTransactionAmount: Double = 0.0,
    /** Most frequently purchased items (comma-separated) */
    val frequentItems: String = "",
    /** Preferred payment method */
    val preferredPayment: String = "cash",

    // ═══ CREDIT TRACKING ═══
    /** Whether customer currently has outstanding credit */
    val hasCredit: Boolean = false,
    /** Total outstanding credit amount (KSh) */
    val creditBalance: Double = 0.0,
    /** Total credit ever given (KSh) */
    val totalCreditGiven: Double = 0.0,
    /** Total credit repaid (KSh) */
    val totalCreditRepaid: Double = 0.0,
    /** Credit reliability score (0.0-1.0) */
    val creditReliability: Double = 0.0,

    // ═══ PATTERNS ═══
    /** How often they visit — "daily", "weekly", "monthly", "occasional" */
    val visitFrequency: String = "occasional",
    /** Typical visit day of week (1-7, 0=unknown) */
    val typicalVisitDay: Int = 0,
    /** Days since last visit */
    val daysSinceLastVisit: Int = 0,
    /** Whether this is a recurring customer (3+ visits) */
    val isRecurring: Boolean = false,

    // ═══ TIMESTAMPS ═══
    val firstTransactionAt: Long = System.currentTimeMillis(),
    val lastTransactionAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null
) {
    /**
     * Customer lifetime value = totalSpent / totalTransactions
     * Higher CLV = more valuable customer relationship.
     */
    val customerLifetimeValue: Double
        get() = if (totalTransactions > 0) totalSpent / totalTransactions else 0.0

    /**
     * Credit repayment rate = totalCreditRepaid / totalCreditGiven
     * 1.0 = always repays, 0.0 = never repays
     */
    val repaymentRate: Double
        get() = if (totalCreditGiven > 0) totalCreditRepaid / totalCreditGiven else 1.0

    /**
     * Whether customer is at risk of churning.
     * At risk if: recurring customer hasn't visited in 2x their typical frequency.
     */
    val isAtRiskOfChurn: Boolean
        get() {
            if (!isRecurring) return false
            val expectedDays = when (visitFrequency) {
                "daily" -> 3
                "weekly" -> 14
                "monthly" -> 60
                else -> return false
            }
            return daysSinceLastVisit > expectedDays
        }
}
