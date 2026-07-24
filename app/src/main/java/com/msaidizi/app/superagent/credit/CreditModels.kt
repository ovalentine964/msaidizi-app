package com.msaidizi.superagent.credit

// ═══════════════════════════════════════════════════════════════
// CREDIT MODULE — Data Models
// ═══════════════════════════════════════════════════════════════
// All data classes and enums for the Alama Score credit module.
// ═══════════════════════════════════════════════════════════════

/**
 * Worker work pattern classification.
 *
 * Different workers have different business rhythms. The Alama Score
 * must account for these patterns to avoid penalizing seasonal or
 * project-based workers.
 */
enum class WorkPattern {
    /** Works and records transactions daily (e.g., mama mboga, food vendor) */
    DAILY,

    /** Works specific days per week (e.g., market days: Wed, Sat) */
    WEEKLY,

    /** Income peaks in certain seasons (e.g., farming, holiday retail) */
    SEASONAL,

    /** Works on specific projects/events (e.g., catering, construction) */
    PROJECT_BASED,

    /** Works whenever there's demand (e.g., boda boda, casual labor) */
    ON_DEMAND
}

/**
 * Alama Score tier classification.
 *
 * Maps score ranges to credit readiness tiers with product eligibility.
 */
enum class AlamaTier(
    val displayName: String,
    val emoji: String,
    val minScore: Int,
    val maxScore: Int
) {
    /** < 25 or insufficient data — education only */
    BUILDING("Building", "🌱", 0, 24),

    /** 25-44 — savings products, financial tips */
    STARTING("Starting", "🌿", 25, 44),

    /** 45-59 — micro-loans up to KSh 2,000 */
    GROWING("Growing", "🌿", 45, 59),

    /** 60-74 — loans up to KSh 10,000, micro-insurance */
    ESTABLISHED("Established", "🌳", 60, 74),

    /** 75-89 — loans up to KSh 50,000, full insurance */
    THRIVING("Thriving", "🏆", 75, 89),

    /** 90-100 — premium products, lowest rates */
    EXCELLENT("Excellent", "⭐", 90, 100);

    companion object {
        fun fromScore(score: Int): AlamaTier {
            return entries.find { score in it.minScore..it.maxScore } ?: BUILDING
        }
    }
}

/**
 * Confidence level for the Alama Score.
 *
 * Score is meaningless without confidence. Low confidence means
 * not enough data to make reliable assessments.
 */
enum class ConfidenceLevel {
    /** < 0.25 — not enough data, no score shown */
    INSUFFICIENT,

    /** 0.25-0.50 — score shown with caveat */
    PRELIMINARY,

    /** 0.50-0.75 — score shown normally */
    MODERATE,

    /** 0.75-1.00 — eligible for products */
    HIGH
}

/**
 * The eight pillars of the Alama Score.
 */
enum class Pillar(val displayName: String, val weight: Double) {
    /** How consistently the worker records transactions */
    FREQUENCY("Transaction Frequency", 0.15),

    /** Whether income is growing, stable, or declining */
    REVENUE_TREND("Revenue Trends", 0.15),

    /** Is the business actually profitable? */
    MARGINS("Profit Margins", 0.15),

    /** Risk concentration — one product vs many */
    DIVERSITY("Product Diversity", 0.10),

    /** Does the worker operate on a predictable schedule? */
    REGULARITY("Regularity", 0.10),

    /** Is the business improving over time? */
    GROWTH("Growth Trajectory", 0.10),

    /** Is the worker disciplined about spending? */
    EXPENSE_CONTROL("Expense Control", 0.10),

    /** Does the worker save? How consistently? */
    SAVINGS("Savings Behavior", 0.15);

    companion object {
        /** Total weight should be 1.0 */
        val TOTAL_WEIGHT = entries.sumOf { it.weight }
    }
}

/**
 * A single pillar score with contributing data.
 *
 * @property pillar The pillar being scored
 * @property rawValue Raw score 0-100
 * @property contributingData Key data points that contributed to the score
 * @property message Human-readable explanation
 * @property tip Actionable improvement tip
 */
data class PillarScore(
    val pillar: Pillar,
    val rawValue: Double,
    val contributingData: Map<String, Any> = emptyMap(),
    val message: String = "",
    val tip: String = ""
) {
    /** Weighted contribution to final score */
    val weightedContribution: Double
        get() = rawValue * pillar.weight
}

/**
 * The complete Alama Score result.
 *
 * @property score Final score 0-100 (confidence-adjusted)
 * @property rawScore Score before confidence adjustment
 * @property confidence Confidence in the score (0.0-1.0)
 * @property confidenceLevel Categorical confidence level
 * @property tier Credit readiness tier
 * @property pillars Individual pillar scores
 * @property transactionCount Number of transactions used
 * @property activeDays Number of active days in window
 * @property windowDays Size of the data window
 * @property computedAt When the score was computed (Unix timestamp)
 * @property workPattern Detected work pattern
 * @property message Human-readable summary in Swahili
 */
data class AlamaScore(
    val score: Double,
    val rawScore: Double,
    val confidence: Double,
    val confidenceLevel: ConfidenceLevel,
    val tier: AlamaTier,
    val pillars: List<PillarScore>,
    val transactionCount: Int,
    val activeDays: Int,
    val windowDays: Int,
    val computedAt: Long,
    val workPattern: WorkPattern,
    val message: String
) {
    /** Whether the score is eligible for financial products */
    val isEligibleForProducts: Boolean
        get() = confidenceLevel == ConfidenceLevel.HIGH ||
            confidenceLevel == ConfidenceLevel.MODERATE

    /** Estimated maximum loan amount based on tier */
    val estimatedMaxLoan: Int
        get() = when (tier) {
            AlamaTier.BUILDING -> 0
            AlamaTier.STARTING -> 2_000
            AlamaTier.GROWING -> 5_000
            AlamaTier.ESTABLISHED -> 10_000
            AlamaTier.THRIVING -> 50_000
            AlamaTier.EXCELLENT -> 100_000
        }
}

/**
 * What-if simulation result.
 *
 * Shows how the Alama Score would change if the worker
 * made specific behavioral changes.
 *
 * @property currentScore Current Alama Score
 * @property simulatedScore Projected score after changes
 * @property changes List of changes simulated
 * @property message Human-readable explanation
 */
data class WhatIfResult(
    val currentScore: AlamaScore,
    val simulatedScore: AlamaScore,
    val changes: List<SimulatedChange>,
    val message: String
)

/**
 * A single simulated change.
 *
 * @property pillar Which pillar would be affected
 * @property description What change is being simulated
 * @property currentValue Current pillar value
 * @property simulatedValue Projected pillar value
 * @property scoreImpact Expected score change
 */
data class SimulatedChange(
    val pillar: Pillar,
    val description: String,
    val currentValue: Double,
    val simulatedValue: Double,
    val scoreImpact: Double
)

/**
 * Transaction data for Alama Score calculation.
 * Simplified version that doesn't depend on the app's Transaction class.
 *
 * @property type Transaction type (SALE, PURCHASE, EXPENSE)
 * @property amount Total amount in KSh
 * @property costBasis Cost for margin calculation
 * @property category Product category
 * @property item Item name
 * @property timestamp Unix timestamp in seconds
 * @property confidence ASR confidence score
 */
data class AlamaTransaction(
    val type: String, // "SALE", "PURCHASE", "EXPENSE"
    val amount: Double,
    val costBasis: Double = 0.0,
    val category: String = "",
    val item: String = "",
    val timestamp: Long = System.currentTimeMillis() / 1000,
    val confidence: Float = 1.0f
) {
    val isSale: Boolean get() = type == "SALE"
    val isExpense: Boolean get() = type == "PURCHASE" || type == "EXPENSE"
    val margin: Double get() = amount - costBasis
    val marginPercent: Double get() = if (amount > 0) margin / amount else 0.0
    val dayNumber: Long get() = timestamp / 86_400
}
