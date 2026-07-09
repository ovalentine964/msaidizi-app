package com.msaidizi.app.security.privacy

import timber.log.Timber
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.exp

/**
 * Differential Privacy implementation for Msaidizi analytics and ML training.
 *
 * Protects individual user data while enabling aggregate analysis.
 * Uses the Laplace mechanism for numeric queries and randomized response
 * for categorical data.
 *
 * Privacy parameter: ε = 0.1 (strong privacy guarantee)
 * - ε ≤ 0.1: Very strong privacy (suitable for financial data)
 * - ε ≤ 1.0: Strong privacy
 * - ε ≤ 10.0: Moderate privacy
 *
 * Per the definition: for any two neighboring datasets D₁ and D₂ (differing
 * in one record), and any output set S:
 *   Pr[M(D₁) ∈ S] ≤ e^ε × Pr[M(D₂) ∈ S]
 *
 * This means an attacker cannot determine whether any specific individual's
 * data was included in the dataset, even with access to all other records.
 *
 * Use cases in Msaidizi:
 * - Aggregate spending pattern analytics
 * - Business income estimation for credit scoring
 * - Market intelligence (anonymized)
 * - Federated learning gradient perturbation
 */
@Singleton
class DifferentialPrivacy @Inject constructor() {

    companion object {
        /** Privacy budget — ε = 0.1 for strong privacy on financial data */
        const val DEFAULT_EPSILON = 0.1

        /** Sensitivity for monetary amounts (max KES change one person can cause) */
        const val AMOUNT_SENSITIVITY = 100_000.0  // KES 100,000

        /** Sensitivity for transaction counts */
        const val COUNT_SENSITIVITY = 1.0

        /** Sensitivity for time-based queries (hours) */
        const val TIME_SENSITIVITY = 24.0

        /** Minimum noise magnitude to prevent trivial denoising */
        const val MIN_NOISE_MAGNITUDE = 0.001

        private val secureRandom = SecureRandom()
    }

    /**
     * Add Laplace noise to a numeric value.
     *
     * The Laplace mechanism: M(x) = f(x) + Lap(Δf / ε)
     * where Δf is the sensitivity and ε is the privacy budget.
     *
     * @param value The true value to protect
     * @param sensitivity Maximum change one individual can cause
     * @param epsilon Privacy budget (smaller = more private)
     * @return Noised value
     */
    fun addLaplaceNoise(
        value: Double,
        sensitivity: Double = AMOUNT_SENSITIVITY,
        epsilon: Double = DEFAULT_EPSILON
    ): Double {
        val scale = sensitivity / epsilon
        val noise = sampleLaplace(scale)
        val noised = value + noise

        Timber.d("DP: added Laplace noise (scale=%.2f) to value", scale)
        return noised
    }

    /**
     * Add Laplace noise to an integer count.
     *
     * @param count The true count
     * @param sensitivity Maximum change one individual can cause (usually 1)
     * @param epsilon Privacy budget
     * @return Noised count (rounded to integer, floored at 0)
     */
    fun addNoiseToCount(
        count: Int,
        sensitivity: Double = COUNT_SENSITIVITY,
        epsilon: Double = DEFAULT_EPSILON
    ): Int {
        val noised = addLaplaceNoise(count.toDouble(), sensitivity, epsilon)
        return noised.toInt().coerceAtLeast(0)
    }

    /**
     * Add noise to a histogram (distribution of categories).
     * Uses the Laplace mechanism on each bin independently.
     *
     * @param histogram Map of category → count
     * @param epsilon Privacy budget (split across bins)
     * @return Noised histogram
     */
    fun addNoiseToHistogram(
        histogram: Map<String, Int>,
        epsilon: Double = DEFAULT_EPSILON
    ): Map<String, Int> {
        // Split epsilon across bins for composition
        val perBinEpsilon = epsilon / histogram.size.coerceAtLeast(1)

        return histogram.mapValues { (_, count) ->
            addNoiseToCount(count, COUNT_SENSITIVITY, perBinEpsilon)
        }
    }

    /**
     * Randomized response for binary (yes/no) questions.
     *
     * Used for categorical data where individuals report truthfully with
     * probability p, and randomly otherwise.
     *
     * With p = (e^ε + 1)^(-1), this satisfies ε-differential privacy.
     *
     * @param truthfulAnswer The actual answer
     * @param epsilon Privacy budget
     * @return The reported answer (possibly flipped)
     */
    fun randomizedResponse(truthfulAnswer: Boolean, epsilon: Double = DEFAULT_EPSILON): Boolean {
        val p = exp(epsilon) / (exp(epsilon) + 1)  // Probability of truth
        val random = secureRandom.nextDouble()

        return if (random < p) {
            truthfulAnswer
        } else {
            !truthfulAnswer
        }
    }

    /**
     * Privatize a spending category for analytics.
     * Uses randomized response to protect individual spending patterns.
     *
     * @param category The actual spending category
     * @param allCategories List of all possible categories
     * @param epsilon Privacy budget
     * @return The reported category (possibly changed)
     */
    fun privatizeCategory(
        category: String,
        allCategories: List<String>,
        epsilon: Double = DEFAULT_EPSILON
    ): String {
        val p = exp(epsilon) / (exp(epsilon) + allCategories.size - 1)

        return if (secureRandom.nextDouble() < p) {
            category  // Report truthfully
        } else {
            // Report a random different category
            val otherCategories = allCategories.filter { it != category }
            if (otherCategories.isEmpty()) return category
            otherCategories[secureRandom.nextInt(otherCategories.size)]
        }
    }

    /**
     * Add noise to a vector of values (for federated learning gradients).
     *
     * Each component gets independent Laplace noise.
     * This is the core mechanism for private federated learning.
     *
     * @param values The true gradient vector
     * @param sensitivity L2 sensitivity of the gradient
     * @param epsilon Privacy budget
     * @return Noised gradient vector
     */
    fun addNoiseToVector(
        values: FloatArray,
        sensitivity: Double = 1.0,
        epsilon: Double = DEFAULT_EPSILON
    ): FloatArray {
        val scale = (sensitivity / epsilon).toFloat()
        val noised = FloatArray(values.size)

        for (i in values.indices) {
            noised[i] = values[i] + sampleLaplace(scale).toFloat()
        }

        return noised
    }

    /**
     * Clip a gradient vector to bound sensitivity.
     * This is essential before adding noise — without clipping,
     * a single outlier could dominate the noised result.
     *
     * @param gradient The gradient vector
     * @param maxNorm Maximum L2 norm
     * @return Clipped gradient
     */
    fun clipGradient(gradient: FloatArray, maxNorm: Double = 1.0): FloatArray {
        var norm = 0.0
        for (g in gradient) {
            norm += g.toDouble() * g.toDouble()
        }
        norm = Math.sqrt(norm)

        if (norm <= maxNorm) return gradient.copyOf()

        val scale = (maxNorm / norm).toFloat()
        return FloatArray(gradient.size) { gradient[it] * scale }
    }

    /**
     * Sample from the Laplace distribution Lap(0, scale).
     *
     * Uses the inverse CDF method:
     *   X = scale × sign(U - 0.5) × ln(1 - 2|U - 0.5|)
     * where U ~ Uniform(0, 1)
     */
    private fun sampleLaplace(scale: Double): Double {
        val u = secureRandom.nextDouble() - 0.5
        return -scale * Math.signum(u) * ln(1 - 2 * Math.abs(u))
    }

    /**
     * Compute the privacy loss for a series of queries.
     * Uses basic composition: total ε = sum of individual ε values.
     *
     * @param epsilons List of epsilon values used
     * @return Total epsilon consumed
     */
    fun computeTotalEpsilon(epsilons: List<Double>): Double {
        return epsilons.sum()
    }

    /**
     * Check if the privacy budget is exhausted.
     *
     * @param consumedEpsilon Total epsilon consumed so far
     * @param maxEpsilon Maximum allowed epsilon
     * @return true if budget is exhausted
     */
    fun isBudgetExhausted(consumedEpsilon: Double, maxEpsilon: Double = 1.0): Boolean {
        return consumedEpsilon >= maxEpsilon
    }
}
