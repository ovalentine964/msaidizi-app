package com.msaidizi.app.superagent.flywheel

// ═══════════════════════════════════════════════════════════════════
// FLYWHEEL MODELS — Data structures for the learning flywheel
// ═══════════════════════════════════════════════════════════════════

/**
 * Models for the Msaidizi learning flywheel.
 *
 * The flywheel implements the M-KOPA proof accumulation model:
 * every interaction is a data point, every data point is proof,
 * and proof unlocks capabilities.
 */
object FlywheelModels {

    // ═══════════════════════════════════════════════════════════════
    // PROOF SYSTEM (M-KOPA Model)
    // ═══════════════════════════════════════════════════════════════

    /**
     * A proof point — evidence of worker reliability and business activity.
     *
     * M-KOPA's genius: every phone payment was proof. For Msaidizi,
     * every voice-recorded transaction is proof of business existence,
     * revenue patterns, and reliability.
     *
     * @property type What kind of proof this is
     * @property weight How much this proof counts (1.0 = standard)
     * @property data Associated data
     * @property timestamp When this proof was recorded
     */
    data class ProofPoint(
        val type: ProofType,
        val weight: Double = 1.0,
        val data: Map<String, Any> = emptyMap(),
        val timestamp: Long = System.currentTimeMillis()
    )

    /** Types of proof points */
    enum class ProofType {
        TRANSACTION,        // Recorded a sale/purchase/expense
        GOAL_PROGRESS,      // Made progress on a goal
        CONSISTENCY_DAY,    // Logged in and recorded for a day
        STREAK_MILESTONE,   // Hit a streak milestone
        ADVICE_FOLLOWED,    // Followed agent's advice
        DATA_COMPLETENESS,  // Provided complete data (price, qty, item)
        MARKET_INTEL,       // Shared market price data
        FEEDBACK_GIVEN,     // Provided feedback (correction or confirmation)
        PROFILE_COMPLETE    // Completed worker profile
    }

    /**
     * Alama Score tiers — M-KOPA-style progressive unlock.
     *
     * Each tier unlocks new capabilities. The worker doesn't buy
     * features — they earn them through proof accumulation.
     */
    enum class AlamaTier(
        val displayName: String,
        val minProofPoints: Int,
        val minDaysActive: Int,
        val description: String
    ) {
        /** Just started — basic tracking only */
        BUILDING(
            displayName = "Inajengwa",
            minProofPoints = 0,
            minDaysActive = 0,
            description = "Building your business profile"
        ),

        /** 30+ proof points, 7+ days — basic insights */
        EMERGING(
            displayName = "Inaanza",
            minProofPoints = 30,
            minDaysActive = 7,
            description = "Basic business insights unlocked"
        ),

        /** 100+ proof points, 30+ days — credit readiness assessment */
        ESTABLISHED(
            displayName = "Imara",
            minProofPoints = 100,
            minDaysActive = 30,
            description = "Credit readiness assessment available"
        ),

        /** 300+ proof points, 90+ days — formal finance eligibility */
        PROVEN(
            displayName = "Imethibitishwa",
            minProofPoints = 300,
            minDaysActive = 90,
            description = "Formal finance eligibility unlocked"
        ),

        /** 1000+ proof points, 180+ days — full platform access */
        TRUSTED(
            displayName = "Imeaminiwa",
            minProofPoints = 1000,
            minDaysActive = 180,
            description = "Full platform access and priority features"
        );

        /** Message to show when unlocking this tier */
        val unlockMessage: String
            get() = "Hongera! Umeifikia ngazi ya $displayName. $description"

        companion object {
            /**
             * Determine the tier for a given proof count and days active.
             */
            fun fromProgress(proofPoints: Int, daysActive: Int): AlamaTier {
                return entries.reversed().firstOrNull { tier ->
                    proofPoints >= tier.minProofPoints && daysActive >= tier.minDaysActive
                } ?: BUILDING
            }
        }
    }

    /**
     * Alama Score — the worker's credit readiness score.
     *
     * @property proofPoints Total proof points accumulated
     * @property daysActive Number of days the worker has been active
     * @property currentTier Current Alama tier
     * @property previousTier Previous tier (for unlock detection)
     * @property consistencyScore How consistent the worker is (0.0–1.0)
     * @property dataQualityScore How complete their data is (0.0–1.0)
     * @property creditReadiness Credit readiness assessment
     */
    data class AlamaScore(
        val proofPoints: Int = 0,
        val daysActive: Int = 0,
        val currentTier: AlamaTier = AlamaTier.BUILDING,
        val previousTier: AlamaTier = AlamaTier.BUILDING,
        val consistencyScore: Double = 0.0,
        val dataQualityScore: Double = 0.0,
        val creditReadiness: CreditReadiness = CreditReadiness.NOT_READY
    ) {
        /** Whether the worker just unlocked a new tier */
        val tierUnlocked: Boolean
            get() = currentTier != previousTier && currentTier.ordinal > previousTier.ordinal
    }

    /** Credit readiness levels */
    enum class CreditReadiness {
        NOT_READY,          // Too few proof points
        BUILDING,           // Accumulating proof
        EARLY_ASSESSMENT,   // Can estimate readiness
        READY_FOR_REVIEW,   // Ready for lender review
        PRE_QUALIFIED       // Pre-qualified for products
    }

    // ═══════════════════════════════════════════════════════════════
    // LEARNING SIGNALS
    // ═══════════════════════════════════════════════════════════════

    /**
     * A feedback signal from a worker interaction.
     *
     * @property type What kind of feedback
     * @property originalInput The worker's original input
     * @property correctionInput The correction (if correction type)
     * @property originalIntent What the system classified
     * @property timestamp When the feedback was received
     */
    data class FeedbackSignal(
        val type: FeedbackType,
        val originalInput: String,
        val correctionInput: String = "",
        val originalIntent: String = "",
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class FeedbackType {
        CONFIRMATION,   // Worker confirmed ("Sawa", "Asante")
        CORRECTION,     // Worker corrected ("Sio X, ni Y")
        CLARIFICATION,  // Worker asked for more info
        IGNORE          // Worker didn't engage
    }

    /**
     * A learned pattern extracted from transaction history.
     *
     * @property key Unique pattern identifier
     * @property description Human-readable description
     * @property confidence Confidence in this pattern (0.0–1.0)
     * @property sampleCount Number of samples supporting this pattern
     * @property lastUpdated When this pattern was last updated
     */
    data class LearnedPattern(
        val key: String,
        val description: String,
        val confidence: Double = 0.0,
        val sampleCount: Int = 0,
        val lastUpdated: Long = System.currentTimeMillis()
    )

    /**
     * User preference learned from behavior.
     *
     * @property key Preference identifier
     * @property value Preferred value
     * @property confidence How confident we are (0.0–1.0)
     * @property observations Number of observations
     */
    data class UserPreference(
        val key: String,
        val value: String,
        val confidence: Double = 0.0,
        val observations: Int = 0
    )
}
