package com.msaidizi.app.superagent.credit

import timber.log.Timber

/**
 * Credit Module — Entry point for the :superagent:credit capability.
 *
 * This module implements the Alama Score system, an 8-pillar financial
 * readiness assessment engine for informal workers in Kenya.
 *
 * ## Architecture
 *
 * The credit module replaces the scattered credit-related code from the
 * multi-agent architecture (CreditScoringLogic, DebtAdvisorAgent, etc.)
 * into one cohesive module with offline-first scoring.
 *
 * ## Key Features
 *
 * | Feature                 | Description                                    |
 * |-------------------------|------------------------------------------------|
 * | 8-Pillar Scoring        | Frequency, Revenue, Margins, Diversity,        |
 * |                         | Regularity, Growth, Expenses, Savings          |
 * | Work Pattern Aware      | DAILY, WEEKLY, SEASONAL, PROJECT_BASED, ON_DEMAND |
 * | Offline-First           | Computes locally, syncs when connected         |
 * | Confidence Dampening    | Low confidence → pull toward neutral (50)      |
 * | What-If Simulation      | "If I save more, what happens to my score?"    |
 * | Transparent             | Worker always sees why their score is what it is |
 *
 * ## Usage
 *
 * ```kotlin
 * val creditModule = CreditModule()
 *
 * // Compute Alama Score
 * val score = creditModule.computeScore(transactions, savingsAmount = 5000.0)
 *
 * // What-if simulation
 * val result = creditModule.simulate(score, mapOf(Pillar.SAVINGS to 80.0))
 *
 * // Get explanation
 * val explanation = creditModule.explainScore(score)
 * ```
 *
 * @author Msaidizi Financial Team
 */
class CreditModule(
    val alamaEngine: AlamaScoreEngine = AlamaScoreEngine()
) {

    companion object {
        private const val TAG = "CreditModule"
    }

    // ═══════════════════════════════════════════════════════════════
    // SCORE COMPUTATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Compute the Alama Score from transaction history.
     *
     * @param transactions All available transactions
     * @param savingsAmount Total savings amount (if tracked separately)
     * @param workPattern Worker's business rhythm (auto-detected if null)
     * @return [AlamaScore] with full breakdown
     */
    fun computeScore(
        transactions: List<AlamaTransaction>,
        savingsAmount: Double = 0.0,
        workPattern: WorkPattern? = null
    ): AlamaScore {
        Timber.tag(TAG).d("Computing Alama Score for %d transactions", transactions.size)
        return alamaEngine.compute(transactions, savingsAmount, workPattern)
    }

    // ═══════════════════════════════════════════════════════════════
    // SCORE EXPLANATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate a detailed, transparent explanation of the score.
     *
     * Every worker can always ask "why is my score X?" and get a clear answer.
     *
     * @param score The Alama Score to explain
     * @return List of pillar explanations with tips
     */
    fun explainScore(score: AlamaScore): List<PillarExplanation> {
        return score.pillars
            .sortedByDescending { it.rawValue * it.pillar.weight }
            .map { pillar ->
                val tone = when {
                    pillar.rawValue >= 70 -> ExplanationTone.STRENGTH
                    pillar.rawValue >= 40 -> ExplanationTone.NEUTRAL
                    else -> ExplanationTone.IMPROVEMENT
                }

                PillarExplanation(
                    pillar = pillar.pillar,
                    score = pillar.rawValue,
                    impact = pillar.weight * pillar.rawValue,
                    tone = tone,
                    message = pillar.message,
                    tip = pillar.tip,
                    contributingData = pillar.contributingData
                )
            }
    }

    /**
     * Get a summary message for the current score tier.
     *
     * @param score The Alama Score
     * @return Tier-appropriate message in Swahili
     */
    fun getTierSummary(score: AlamaScore): String {
        return when (score.tier) {
            AlamaTier.BUILDING ->
                "Alama yako bado inajengwa. Endelea kurekodi mauzo yako kila siku!"
            AlamaTier.STARTING ->
                "Umefanya vizuri! Alama yako inaanza kuonekana. Endelea kurekodi."
            AlamaTier.GROWING ->
                "Biashara yako inakua! Unaweza kupata mikopo midogo sasa."
            AlamaTier.ESTABLISHED ->
                "Biashara yako imara! Unastahili mikopo na bima ya biashara."
            AlamaTier.THRIVING ->
                "Biashara yako inafanya vizuri sana! Fursa nyingi zinakungoja."
            AlamaTier.EXCELLENT ->
                "Alama yako ni bora! Unastahili bidhaa bora zaidi."
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // WHAT-IF SIMULATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Simulate how the score would change with specific improvements.
     *
     * "If I save KES 100 more per day, what happens to my score?"
     *
     * @param currentScore Current Alama Score
     * @param changes Simulated changes to pillar values (0-100)
     * @return [WhatIfResult] with projected score and explanation
     */
    fun simulate(
        currentScore: AlamaScore,
        changes: Map<Pillar, Double>
    ): WhatIfResult {
        Timber.tag(TAG).d("Running what-if simulation with %d changes", changes.size)
        return alamaEngine.simulate(currentScore, changes)
    }

    /**
     * Simulate common what-if scenarios.
     *
     * @param currentScore Current Alama Score
     * @return List of common scenario results
     */
    fun simulateCommonScenarios(currentScore: AlamaScore): List<WhatIfResult> {
        val scenarios = mutableListOf<WhatIfResult>()

        // Scenario 1: Save more
        val currentSavingsScore = currentScore.pillars
            .find { it.pillar == Pillar.SAVINGS }?.rawValue ?: 0.0
        if (currentSavingsScore < 80) {
            scenarios.add(
                simulate(currentScore, mapOf(Pillar.SAVINGS to min(100.0, currentSavingsScore + 20)))
            )
        }

        // Scenario 2: Record more consistently
        val currentFreqScore = currentScore.pillars
            .find { it.pillar == Pillar.FREQUENCY }?.rawValue ?: 0.0
        if (currentFreqScore < 80) {
            scenarios.add(
                simulate(currentScore, mapOf(Pillar.FREQUENCY to min(100.0, currentFreqScore + 20)))
            )
        }

        // Scenario 3: Add more product diversity
        val currentDivScore = currentScore.pillars
            .find { it.pillar == Pillar.DIVERSITY }?.rawValue ?: 0.0
        if (currentDivScore < 80) {
            scenarios.add(
                simulate(currentScore, mapOf(Pillar.DIVERSITY to min(100.0, currentDivScore + 20)))
            )
        }

        return scenarios
    }

    // ═══════════════════════════════════════════════════════════════
    // CREDIT READINESS ASSESSMENT
    // ═══════════════════════════════════════════════════════════════

    /**
     * Assess credit readiness — what products does the worker qualify for?
     *
     * @param score The Alama Score
     * @return [CreditReadiness] with eligibility details
     */
    fun assessCreditReadiness(score: AlamaScore): CreditReadiness {
        if (!score.isEligibleForProducts) {
            return CreditReadiness(
                isEligible = false,
                maxLoanAmount = 0,
                monthlyRatePercent = 0.0,
                confidenceLevel = score.confidenceLevel,
                message = "Bado hujastahili mkopo. Endelea kurekodi mauzo yako " +
                    "kujenga Alama yako!",
                factors = explainScore(score)
            )
        }

        val maxLoan = score.estimatedMaxLoan
        val baseRate = 0.25 - (score.score / 100 * 0.17)
        val rate = max(0.08, baseRate) * (1 + (1 - score.confidence) * 0.5)

        return CreditReadiness(
            isEligible = maxLoan >= 500,
            maxLoanAmount = maxLoan,
            monthlyRatePercent = (rate * 100 * 10).roundToInt() / 10.0,
            confidenceLevel = score.confidenceLevel,
            message = buildString {
                if (maxLoan >= 500) {
                    append("Unastahili mkopo wa hadi KSh ${formatAmount(maxLoan.toDouble())} ")
                    append("kwa kiwango cha ${(rate * 100).roundToInt()}% kwa mwezi.")
                } else {
                    append("Alama yako bado ni ndogo. Endelea kurekodi ili kustahili mkopo.")
                }
            },
            factors = explainScore(score)
        )
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

// ═══════════════════════════════════════════════════════════════
// SUPPORTING DATA CLASSES
// ═══════════════════════════════════════════════════════════════

/**
 * Explanation tone for a pillar.
 */
enum class ExplanationTone {
    /** Pillar is a strength (score >= 70) */
    STRENGTH,
    /** Pillar is neutral (score 40-69) */
    NEUTRAL,
    /** Pillar needs improvement (score < 40) */
    IMPROVEMENT
}

/**
 * Detailed explanation of a single pillar.
 *
 * @property pillar The pillar being explained
 * @property score Raw pillar score (0-100)
 * @property impact Weighted impact on final score
 * @property tone Whether this is a strength, neutral, or improvement area
 * @property message Human-readable explanation
 * @property tip Actionable improvement tip
 * @property contributingData Key data points
 */
data class PillarExplanation(
    val pillar: Pillar,
    val score: Double,
    val impact: Double,
    val tone: ExplanationTone,
    val message: String,
    val tip: String,
    val contributingData: Map<String, Any> = emptyMap()
)

/**
 * Credit readiness assessment result.
 *
 * @property isEligible Whether the worker qualifies for any products
 * @property maxLoanAmount Maximum loan amount in KSh
 * @property monthlyRatePercent Monthly interest rate
 * @property confidenceLevel Confidence in the assessment
 * @property message Human-readable explanation
 * @property factors Pillar-level explanations
 */
data class CreditReadiness(
    val isEligible: Boolean,
    val maxLoanAmount: Int,
    val monthlyRatePercent: Double,
    val confidenceLevel: ConfidenceLevel,
    val message: String,
    val factors: List<PillarExplanation>
)
