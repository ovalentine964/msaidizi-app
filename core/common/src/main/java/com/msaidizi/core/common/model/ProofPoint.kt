package com.msaidizi.core.common.model

/**
 * Proof point for M-KOPA-style proof accumulation.
 *
 * Every meaningful interaction generates a proof point that contributes
 * to the worker's Alama Score. Proof points are the building blocks
 * of financial identity for informal economy workers.
 *
 * ## Proof Types and Weights
 * - TRANSACTION (1.0): Recording a sale, purchase, or expense
 * - GOAL_PROGRESS (1.5): Saving toward a goal
 * - CONSISTENCY (2.0): 7+ consecutive days of tracking
 * - CORRECTION (0.5): Correcting a mistake (shows engagement)
 * - FINANCIAL_QUERY (0.3): Asking about profit/balance (shows awareness)
 * - MPESA_LINK (3.0): Connecting M-Pesa (verifiable income)
 * - RECEIPT (1.2): Scanning a receipt (paper trail)
 */
data class ProofPoint(
    val id: Long = 0,

    /** Type of proof */
    val type: ProofType,
    /** Weight of this proof point (see type defaults above) */
    val weight: Double = type.defaultWeight,
    /** Day number since onboarding (1-indexed) */
    val dayNumber: Int = 0,
    /** Timestamp when proof was generated */
    val timestamp: Long = System.currentTimeMillis(),
    /** Additional proof-specific data as key-value pairs */
    val data: Map<String, String> = emptyMap(),
    /** Whether this proof has been synced to backend */
    val syncedAt: Long? = null
) {
    /**
     * Age in days since this proof was generated.
     */
    val ageDays: Double
        get() = (System.currentTimeMillis() - timestamp) / (1000.0 * 60 * 60 * 24)

    /**
     * Time decay factor for Alama Score calculation.
     * Recent proofs count more: e^(-ageDays/90)
     */
    val timeDecayFactor: Double
        get() = Math.exp(-ageDays / 90.0)
}

/**
 * Types of proof in the M-KOPA model.
 */
enum class ProofType(val defaultWeight: Double) {
    /** Recording a transaction (sale, purchase, expense) */
    TRANSACTION(1.0),
    /** Progress toward a financial goal */
    GOAL_PROGRESS(1.5),
    /** Consistency bonus (7+ consecutive days) */
    CONSISTENCY(2.0),
    /** Correcting a mistake (engagement signal) */
    CORRECTION(0.5),
    /** Asking about finances (awareness signal) */
    FINANCIAL_QUERY(0.3),
    /** Connecting M-Pesa (verifiable income stream) */
    MPESA_LINK(3.0),
    /** Scanning a receipt (paper trail) */
    RECEIPT(1.2),
    /** Recording spoilage/waste (honesty signal) */
    SPOLIAGE_RECORD(0.8),
    /** Setting up inventory tracking */
    INVENTORY_SETUP(1.0),
    /** Recording a client relationship */
    CLIENT_RECORD(0.5)
}

/**
 * Alama Score — the worker's proof-based financial identity score.
 *
 * Score = Σ(proof_weight × consistency_factor × time_decay)
 *
 * Where:
 * - proof_weight: per-type weight
 * - consistency_factor: bonus for consecutive days (1.0 + 0.1 × streak)
 * - time_decay: recent proofs count more (e^(-days/90))
 */
data class AlamaScore(
    /** The calculated score value */
    val score: Double = 0.0,
    /** Current tier based on score */
    val tier: AlamaTier = AlamaTier.MTOTO,
    /** Total proof points accumulated */
    val totalProofPoints: Int = 0,
    /** Days since first proof point */
    val daysActive: Int = 0,
    /** Current consecutive days streak */
    val consistencyStreak: Int = 0,
    /** Progress toward next tier (0.0-1.0) */
    val tierProgress: Double = 0.0,
    /** When the score was last calculated */
    val calculatedAt: Long = System.currentTimeMillis()
) {
    /**
     * Whether the worker has unlocked a new capability tier.
     */
    val hasNewCapabilities: Boolean
        get() = tierProgress >= 1.0

    /**
     * Human-readable score summary.
     */
    val summary: String
        get() = "Alama Score: ${score.toInt()} (${tier.swahiliName}) | " +
                "Proof Points: $totalProofPoints | " +
                "Siku: $daysActive | " +
                "Mfululizo: $consistencyStreak"
}
