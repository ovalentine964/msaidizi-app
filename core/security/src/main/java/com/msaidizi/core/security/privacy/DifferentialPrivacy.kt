package com.msaidizi.core.security.privacy

import kotlin.math.ln
import kotlin.math.max
import kotlin.random.Random

/**
 * Differential privacy implementation for Msaidizi.
 *
 * Ensures that individual worker data cannot be reverse-engineered
 * from aggregated statistics shared with the backend. Critical for
 * protecting worker privacy while enabling market intelligence.
 *
 * ## Parameters
 * - ε (epsilon) = 0.1: Strong privacy guarantee (lower = more private)
 * - δ (delta) = 1e-5: Probability of privacy breach
 *
 * ## Use Cases
 * - Soko Pulse: Aggregate market prices without revealing individual prices
 * - Peer comparison: Compare metrics without exposing exact values
 * - Research: Share anonymized business patterns
 *
 * ## How It Works
 * Adds calibrated Laplacian noise to numerical values before sharing.
 * The noise magnitude is proportional to 1/ε (sensitivity/epsilon).
 * With ε=0.1, the noise is 10x the sensitivity — strong privacy.
 */
class DifferentialPrivacy(
    private val epsilon: Double = 0.1,
    private val delta: Double = 1e-5
) {
    private val random = Random.Default

    /**
     * Add Laplacian noise to a numerical value.
     *
     * @param value Original value
     * @param sensitivity Maximum change one individual can cause (usually 1.0)
     * @return Noised value
     */
    fun addNoise(value: Double, sensitivity: Double = 1.0): Double {
        val scale = sensitivity / epsilon
        val noise = laplacianNoise(scale)
        return value + noise
    }

    /**
     * Add Laplacian noise to an integer value.
     */
    fun addNoise(value: Int, sensitivity: Double = 1.0): Int {
        return addNoise(value.toDouble(), sensitivity).toInt()
    }

    /**
     * Add noise to a list of values (for aggregation).
     * Each value gets independent noise.
     */
    fun addNoiseToValues(values: List<Double>, sensitivity: Double = 1.0): List<Double> {
        return values.map { addNoise(it, sensitivity) }
    }

    /**
     * Create a differentially private count.
     * Used for: "How many workers sold mandazi today?"
     */
    fun privateCount(count: Int): Int {
        return max(0, addNoise(count, sensitivity = 1.0))
    }

    /**
     * Create a differentially private sum.
     * Used for: "Total revenue in Gikomba today"
     */
    fun privateSum(sum: Double, maxContribution: Double = 10000.0): Double {
        return addNoise(sum, sensitivity = maxContribution)
    }

    /**
     * Create a differentially private average.
     * Used for: "Average daily profit for food vendors"
     */
    fun privateAverage(
        sum: Double,
        count: Int,
        maxContribution: Double = 10000.0
    ): Double {
        if (count == 0) return 0.0
        val noisySum = privateSum(sum, maxContribution)
        val noisyCount = privateCount(count)
        return if (noisyCount > 0) noisySum / noisyCount else 0.0
    }

    /**
     * Create a differentially private histogram bin count.
     * Used for: "How many workers fall in each revenue bucket?"
     */
    fun privateHistogramBin(count: Int): Int {
        return privateCount(count)
    }

    /**
     * Randomized response for binary questions.
     * Used for: "Do you have a bank account?" (without revealing true answer)
     *
     * With probability p = e^ε / (e^ε + 1), answer truthfully.
     * With probability 1-p, answer randomly.
     */
    fun randomizedResponse(trueAnswer: Boolean): Boolean {
        val p = Math.exp(epsilon) / (Math.exp(epsilon) + 1.0)
        return if (random.nextDouble() < p) {
            trueAnswer
        } else {
            random.nextBoolean()
        }
    }

    /**
     * Generate Laplacian noise with given scale parameter.
     * Laplace(0, scale) = -scale × sgn(u) × ln(1 - 2|u|) where u ~ Uniform(-0.5, 0.5)
     */
    private fun laplacianNoise(scale: Double): Double {
        val u = random.nextDouble() - 0.5
        return -scale * Math.signum(u) * ln(1.0 - 2.0 * Math.abs(u))
    }

    /**
     * Privacy budget tracker.
     * Tracks total privacy expenditure to prevent budget exhaustion.
     */
    class PrivacyBudget(private val totalBudget: Double = 1.0) {
        private var spent: Double = 0.0

        /**
         * Check if a query can be made within the privacy budget.
         */
        fun canQuery(queryEpsilon: Double): Boolean {
            return (spent + queryEpsilon) <= totalBudget
        }

        /**
         * Record privacy expenditure.
         * @return true if budget was available, false if exceeded
         */
        fun spend(queryEpsilon: Double): Boolean {
            if (!canQuery(queryEpsilon)) return false
            spent += queryEpsilon
            return true
        }

        /**
         * Remaining privacy budget.
         */
        val remaining: Double
            get() = max(0.0, totalBudget - spent)

        /**
         * Reset budget (e.g., daily reset).
         */
        fun reset() {
            spent = 0.0
        }
    }
}
