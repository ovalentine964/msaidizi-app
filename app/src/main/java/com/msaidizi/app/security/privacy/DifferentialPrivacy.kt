package com.msaidizi.app.security.privacy

import android.content.SharedPreferences
import timber.log.Timber
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * DifferentialPrivacy — Laplace mechanism and privacy budget tracking.
 *
 * Implements ε-differential privacy for federated learning gradient uploads.
 * Uses the Laplace mechanism: noise ~ Lap(Δf / ε) where Δf is the L1 sensitivity.
 *
 * Privacy guarantee: Any single worker's contribution changes the output
 * distribution by at most a factor of e^ε — with ε=0.1, that's ~10%,
 * providing strong plausible deniability.
 *
 * Budget tracking: Each worker has a rolling 365-day ε budget (default ε_max=1.0).
 * When exhausted, FL uploads are blocked until the window resets.
 *
 * Design: arch_security.md §5.2, arch_flywheel.md §10
 */
@Singleton
class DifferentialPrivacy @Inject constructor(
    private val prefs: SharedPreferences
) {
    private val random = SecureRandom()

    companion object {
        /** Default privacy epsilon — strong guarantee (ε=0.1) */
        const val DEFAULT_EPSILON = 0.1

        /** Default failure probability */
        const val DEFAULT_DELTA = 1e-5

        /** Default gradient clipping L2 norm bound */
        const val DEFAULT_CLIP_NORM = 1.0f

        /** Maximum cumulative epsilon per worker per year */
        const val MAX_EPSILON_BUDGET = 1.0

        /** Budget window in days */
        const val BUDGET_WINDOW_DAYS = 365

        private const val PREF_PREFIX = "dp_budget_"
        private const val PREF_LOG_PREFIX = "dp_log_"
    }

    // ═══════════════════════════════════════════════════════════════
    // LAPLACE MECHANISM
    // ═══════════════════════════════════════════════════════════════

    /**
     * Add Laplace noise to a single numeric value.
     *
     * Noise scale: b = Δf / ε
     * Sample: X = μ - b · sgn(U) · ln(1 - 2|U|) where U ~ Uniform(-0.5, 0.5)
     *
     * @param value The true value to privatize
     * @param epsilon Privacy parameter (smaller = more private)
     * @param sensitivity L1 sensitivity (max change one record can cause)
     * @return The noised value
     */
    fun addLaplaceNoise(
        value: Double,
        epsilon: Double = DEFAULT_EPSILON,
        sensitivity: Double = 1.0
    ): Double {
        val scale = sensitivity / epsilon
        val noise = sampleLaplace(scale)
        return value + noise
    }

    /**
     * Add Laplace noise to a float array (gradient vector).
     *
     * Each element gets independent noise with the given scale.
     * Used to privatize LoRA adapter weight deltas before upload.
     *
     * @param gradient The true gradient vector
     * @param epsilon Privacy parameter
     * @param sensitivity Per-element L1 sensitivity
     * @return New array with noise added (original unchanged)
     */
    fun addNoiseToVector(
        gradient: FloatArray,
        epsilon: Double = DEFAULT_EPSILON,
        sensitivity: Double = 1.0
    ): FloatArray {
        val scale = sensitivity / epsilon
        val noised = FloatArray(gradient.size)
        for (i in gradient.indices) {
            noised[i] = gradient[i] + sampleLaplace(scale).toFloat()
        }
        return noised
    }

    /**
     * Add Laplace noise to a histogram (map of counts).
     * Used to privatize aggregated correction pattern frequencies.
     */
    fun addNoiseToHistogram(
        histogram: Map<String, Int>,
        epsilon: Double = DEFAULT_EPSILON
    ): Map<String, Double> {
        // Post-processing: divide epsilon among all buckets
        val perBucketEpsilon = epsilon / max(histogram.size, 1)
        return histogram.mapValues { (_, count) ->
            count.toDouble() + sampleLaplace(1.0 / perBucketEpsilon)
        }
    }

    /**
     * Randomized response mechanism for binary data.
     *
     * With probability p = e^ε / (e^ε + 1), report true value.
     * With probability 1-p, report random value.
     *
     * Used for boolean signals (e.g., "did the worker correct the agent?").
     */
    fun randomizedResponse(trueValue: Boolean, epsilon: Double = DEFAULT_EPSILON): Boolean {
        val p = Math.exp(epsilon) / (Math.exp(epsilon) + 1.0)
        return if (random.nextDouble() < p) trueValue else random.nextBoolean()
    }

    // ═══════════════════════════════════════════════════════════════
    // GRADIENT CLIPPING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Clip gradient to L2 norm bound.
     *
     * If ||gradient||₂ > clipNorm, scales gradient down:
     *   gradient = gradient · (clipNorm / ||gradient||₂)
     *
     * This bounds sensitivity for the Laplace mechanism.
     * Standard approach from Abadi et al. "Deep Learning with Differential Privacy" (2016).
     *
     * @param gradient The gradient to clip (modified in-place)
     * @param clipNorm Maximum L2 norm
     * @return The clipped gradient (same array)
     */
    fun clipGradient(gradient: FloatArray, clipNorm: Float = DEFAULT_CLIP_NORM): FloatArray {
        var norm = 0.0f
        for (g in gradient) norm += g * g
        norm = Math.sqrt(norm.toDouble()).toFloat()

        if (norm > clipNorm) {
            val scale = clipNorm / norm
            for (i in gradient.indices) {
                gradient[i] *= scale
            }
        }
        return gradient
    }

    /**
     * Clip + noise: the standard DP-SGD pipeline for a gradient vector.
     * 1. Clip to L2 norm bound
     * 2. Add calibrated Laplace noise
     *
     * @return New noised+clipped array (original unchanged)
     */
    fun clipAndAddNoise(
        gradient: FloatArray,
        clipNorm: Float = DEFAULT_CLIP_NORM,
        epsilon: Double = DEFAULT_EPSILON
    ): FloatArray {
        // Clone to avoid mutating original
        val clipped = gradient.copyOf()
        clipGradient(clipped, clipNorm)
        // Sensitivity = clipNorm (after clipping, max L2 change is clipNorm)
        return addNoiseToVector(clipped, epsilon, sensitivity = clipNorm.toDouble())
    }

    // ═══════════════════════════════════════════════════════════════
    // PRIVACY BUDGET TRACKING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record epsilon consumption for a worker.
     * Call this AFTER a successful FL upload that used DP.
     *
     * @param workerId The anonymized worker ID
     * @param epsilon The epsilon consumed by this operation
     * @param operation Description (e.g., "gradient_upload")
     */
    fun recordConsumption(workerId: String, epsilon: Double, operation: String) {
        val key = "$PREF_PREFIX$workerId"
        val currentBudget = prefs.getFloat(key, 0f).toDouble()
        val newBudget = currentBudget + epsilon
        prefs.edit().putFloat(key, newBudget.toFloat()).apply()

        // Append to audit log (comma-separated timestamps:epsilons)
        val logKey = "$PREF_LOG_PREFIX$workerId"
        val existingLog = prefs.getString(logKey, "") ?: ""
        val entry = "${System.currentTimeMillis()}:$epsilon"
        val newLog = if (existingLog.isEmpty()) entry else "$existingLog,$entry"
        prefs.edit().putString(logKey, newLog).apply()

        Timber.i("DP: ε consumed=$epsilon for op=$operation, total=${"%.4f".format(newBudget)}")
    }

    /**
     * Check if a worker has remaining privacy budget.
     *
     * @param workerId The anonymized worker ID
     * @param epsilonNeeded The epsilon the next operation would consume
     * @return true if the operation is allowed (budget not exhausted)
     */
    fun hasBudget(workerId: String, epsilonNeeded: Double = DEFAULT_EPSILON): Boolean {
        return remainingBudget(workerId) >= epsilonNeeded
    }

    /**
     * Get remaining privacy budget for a worker.
     *
     * Prunes entries older than BUDGET_WINDOW_DAYS before computing.
     *
     * @return Remaining epsilon (0.0 if exhausted)
     */
    fun remainingBudget(workerId: String): Double {
        val consumed = getConsumedBudget(workerId)
        return max(0.0, MAX_EPSILON_BUDGET - consumed)
    }

    /**
     * Get total epsilon consumed within the rolling window.
     */
    fun getConsumedBudget(workerId: String): Double {
        val logKey = "$PREF_LOG_PREFIX$workerId"
        val log = prefs.getString(logKey, "") ?: ""
        if (log.isEmpty()) return 0.0

        val cutoff = System.currentTimeMillis() - BUDGET_WINDOW_DAYS * 24L * 3600L * 1000L
        var total = 0.0
        val survivingEntries = mutableListOf<String>()

        for (entry in log.split(",")) {
            val parts = entry.split(":")
            if (parts.size == 2) {
                val timestamp = parts[0].toLongOrNull() ?: continue
                val eps = parts[1].toDoubleOrNull() ?: continue
                if (timestamp >= cutoff) {
                    total += eps
                    survivingEntries.add(entry)
                }
            }
        }

        // Prune old entries
        if (survivingEntries.size < log.split(",").size) {
            prefs.edit().putString(logKey, survivingEntries.joinToString(",")).apply()
        }

        return total
    }

    /**
     * Reset privacy budget for a worker (e.g., after opt-out or annual reset).
     */
    fun resetBudget(workerId: String) {
        prefs.edit()
            .remove("$PREF_PREFIX$workerId")
            .remove("$PREF_LOG_PREFIX$workerId")
            .apply()
        Timber.i("DP: Budget reset for worker")
    }

    /**
     * Get a summary of the worker's privacy budget status.
     */
    fun getBudgetSummary(workerId: String): PrivacyBudgetSummary {
        val consumed = getConsumedBudget(workerId)
        val remaining = max(0.0, MAX_EPSILON_BUDGET - consumed)
        return PrivacyBudgetSummary(
            epsilonConsumed = consumed,
            epsilonRemaining = remaining,
            budgetExhausted = remaining <= 0.0,
            windowDays = BUDGET_WINDOW_DAYS,
            maxEpsilon = MAX_EPSILON_BUDGET
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // NOISE SCALE COMPUTATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Compute the Gaussian noise scale for a given (ε, δ) pair.
     *
     * σ = Δf · √(2 · ln(1.25/δ)) / ε
     *
     * With ε=0.1, δ=1e-5: σ ≈ 49.1 · Δf
     */
    fun computeGaussianSigma(
        epsilon: Double = DEFAULT_EPSILON,
        delta: Double = DEFAULT_DELTA,
        sensitivity: Double = 1.0
    ): Double {
        return sensitivity * Math.sqrt(2.0 * ln(1.25 / delta)) / epsilon
    }

    /**
     * Compute the Laplace noise scale for a given epsilon.
     *
     * b = Δf / ε
     */
    fun computeLaplaceScale(
        epsilon: Double = DEFAULT_EPSILON,
        sensitivity: Double = 1.0
    ): Double {
        return sensitivity / epsilon
    }

    // ═══════════════════════════════════════════════════════════════
    // INTERNAL HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Sample from Laplace(0, scale) distribution.
     *
     * If U ~ Uniform(-0.5, 0.5), then X = -b · sgn(U) · ln(1 - 2|U|)
     * is distributed as Laplace(0, b).
     */
    private fun sampleLaplace(scale: Double): Double {
        val u = random.nextDouble() - 0.5
        return -scale * Math.signum(u) * ln(1.0 - 2.0 * abs(u))
    }
}

/**
 * Privacy budget status for a worker.
 */
data class PrivacyBudgetSummary(
    val epsilonConsumed: Double,
    val epsilonRemaining: Double,
    val budgetExhausted: Boolean,
    val windowDays: Int,
    val maxEpsilon: Double
)
