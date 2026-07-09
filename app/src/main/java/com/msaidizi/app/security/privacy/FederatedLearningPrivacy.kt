package com.msaidizi.app.security.privacy

import timber.log.Timber
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Federated Learning Privacy — protects user data during ML training.
 *
 * Implements local differential privacy (LDP) for federated learning:
 * 1. Each device adds noise to its gradient BEFORE sending to server
 * 2. Server never sees raw gradients — only noised versions
 * 3. Aggregate model learns patterns without exposing individuals
 *
 * Privacy guarantees:
 * - ε-DP per round (ε = 0.1 by default)
 * - Gradient clipping bounds sensitivity
 * - Secure aggregation support (optional)
 * - Gradient compression reduces bandwidth and limits info leakage
 *
 * Architecture:
 *   Device → clip(gradient) → add_noise(gradient) → encrypt(PQC) → Server
 *   Server → aggregate(noised_gradients) → update_model
 *
 * Per McMahan et al. (2017) "Communication-Efficient Learning of Deep
 * Networks from Decentralized Data" and Abadi et al. (2016) "Deep
 * Learning with Differential Privacy".
 */
@Singleton
class FederatedLearningPrivacy @Inject constructor() {

    companion object {
        /** Default privacy budget per training round */
        const val DEFAULT_EPSILON = 0.1

        /** Maximum gradient norm before clipping */
        const val DEFAULT_MAX_GRADIENT_NORM = 1.0

        /** Minimum number of participants per round (for privacy amplification) */
        const val MIN_PARTICIPANTS = 100

        /** Maximum gradient dimensions to transmit (compression) */
        const val MAX_GRADIENT_DIMENSIONS = 10_000

        /** Probability of participating in a round (subsampling for amplification) */
        const val PARTICIPATION_RATE = 0.01  // 1% of devices per round

        private val secureRandom = SecureRandom()
    }

    private val dp = DifferentialPrivacy()

    /**
     * Prepare a gradient for private upload.
     *
     * Steps:
     * 1. Clip gradient to bound sensitivity
     * 2. Add Laplace noise for differential privacy
     * 3. Compress by keeping top-k components (sparse upload)
     * 4. Quantize to reduce precision (limits info leakage)
     *
     * @param rawGradient The raw gradient from local training
     * @param epsilon Privacy budget for this round
     * @param maxNorm Maximum L2 norm for clipping
     * @return Privatized gradient ready for encrypted upload
     */
    fun privatizeGradient(
        rawGradient: FloatArray,
        epsilon: Double = DEFAULT_EPSILON,
        maxNorm: Double = DEFAULT_MAX_GRADIENT_NORM
    ): PrivateGradient {
        // Step 1: Clip gradient to bound sensitivity
        val clipped = dp.clipGradient(rawGradient, maxNorm)

        // Step 2: Add Laplace noise
        // Sensitivity = maxNorm (after clipping, each gradient has bounded L2 norm)
        val noised = dp.addNoiseToVector(clipped, sensitivity = maxNorm, epsilon = epsilon)

        // Step 3: Compress — keep top-k components by magnitude
        val compressed = topKCompress(noised, k = MAX_GRADIENT_DIMENSIONS)

        // Step 4: Quantize to 8-bit (reduces precision, limits reconstruction)
        val quantized = quantizeToInt8(compressed.values)

        val gradientHash = computeGradientHash(noised)

        Timber.d(
            "FL privacy: gradient privatized (size=%d, compressed=%d, epsilon=%.2f)",
            rawGradient.size, compressed.indices.size, epsilon
        )

        return PrivateGradient(
            values = quantized,
            indices = compressed.indices,
            originalSize = rawGradient.size,
            epsilon = epsilon,
            gradientHash = gradientHash,
            noiseScale = (maxNorm / epsilon).toFloat()
        )
    }

    /**
     * Check if this device should participate in this training round.
     * Subsampling amplifies privacy: if each device participates with
     * probability q, the effective privacy is approximately q × ε.
     *
     * @param participationRate Probability of participating
     * @return true if this device should participate
     */
    fun shouldParticipate(participationRate: Double = PARTICIPATION_RATE): Boolean {
        return secureRandom.nextDouble() < participationRate
    }

    /**
     * Compute the amplified privacy budget accounting for subsampling.
     *
     * Using the subsampling amplification theorem:
     * If each user participates with probability q, and the mechanism
     * is ε-DP, then the subsampled mechanism is approximately (qε)-DP.
     *
     * @param epsilon Base epsilon
     * @param participationRate Sampling rate
     * @return Amplified (effective) epsilon
     */
    fun computeAmplifiedEpsilon(
        epsilon: Double = DEFAULT_EPSILON,
        participationRate: Double = PARTICIPATION_RATE
    ): Double {
        // Tight bound: ε_amp = ln(1 + q(e^ε - 1))
        val amplified = ln(1 + participationRate * (exp(epsilon) - 1))
        Timber.d("FL privacy: amplified epsilon %.4f -> %.4f (rate=%.4f)", epsilon, amplified, participationRate)
        return amplified
    }

    /**
     * Validate that the server's aggregate update doesn't leak individual data.
     * Checks that the aggregate was computed from enough participants.
     *
     * @param participantCount Number of participants in the round
     * @param minParticipants Minimum required for privacy
     * @return true if the aggregate is safe to use
     */
    fun validateAggregatePrivacy(
        participantCount: Int,
        minParticipants: Int = MIN_PARTICIPANTS
    ): Boolean {
        if (participantCount < minParticipants) {
            Timber.w(
                "FL privacy: insufficient participants (%d < %d) — aggregate may leak individual data",
                participantCount, minParticipants
            )
            return false
        }
        return true
    }

    /**
     * Top-k compression: keep only the k largest components.
     * Reduces bandwidth and limits information leakage.
     */
    private fun topKCompress(values: FloatArray, k: Int): SparseVector {
        if (values.size <= k) {
            return SparseVector(
                indices = IntArray(values.size) { it },
                values = values.copyOf()
            )
        }

        // Find indices of top-k components by absolute value
        val indexed = values.mapIndexed { i, v -> i to Math.abs(v) }
            .sortedByDescending { it.second }
            .take(k)

        val indices = IntArray(indexed.size) { indexed[it].first }
        val compressed = FloatArray(indexed.size) { values[indexed[it].first] }

        return SparseVector(indices = indices, values = compressed)
    }

    /**
     * Quantize float values to 8-bit integers.
     * Reduces precision to limit gradient inversion attacks.
     */
    private fun quantizeToInt8(values: FloatArray): ByteArray {
        if (values.isEmpty()) return ByteArray(0)

        val maxAbs = values.maxOfOrNull { Math.abs(it) } ?: 1f
        val scale = maxAbs / 127f

        return ByteArray(values.size) { i ->
            (values[i] / scale).toInt().coerceIn(-128, 127).toByte()
        }
    }

    /**
     * Compute a hash of the gradient for integrity verification.
     */
    private fun computeGradientHash(gradient: FloatArray): String {
        val bytes = ByteArray(gradient.size * 4)
        for (i in gradient.indices) {
            val bits = java.lang.Float.floatToIntBits(gradient[i])
            bytes[i * 4] = (bits shr 24).toByte()
            bytes[i * 4 + 1] = (bits shr 16).toByte()
            bytes[i * 4 + 2] = (bits shr 8).toByte()
            bytes[i * 4 + 3] = bits.toByte()
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /**
     * Private gradient ready for encrypted upload.
     */
    data class PrivateGradient(
        /** Quantized gradient values (8-bit) */
        val values: ByteArray,
        /** Indices of non-zero components (sparse representation) */
        val indices: IntArray,
        /** Original gradient size (for decompression) */
        val originalSize: Int,
        /** Privacy budget used */
        val epsilon: Double,
        /** SHA-256 hash for integrity verification */
        val gradientHash: String,
        /** Noise scale used (for auditing) */
        val noiseScale: Float
    ) {
        fun toBytes(): ByteArray {
            // Serialize: [4B originalSize][4B numComponents][indices...][values...]
            val buf = java.nio.ByteBuffer.allocate(4 + 4 + indices.size * 4 + values.size)
            buf.putInt(originalSize)
            buf.putInt(indices.size)
            for (idx in indices) buf.putInt(idx)
            buf.put(values)
            return buf.array()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PrivateGradient) return false
            return gradientHash == other.gradientHash
        }

        override fun hashCode(): Int = gradientHash.hashCode()
    }

    /**
     * Sparse vector representation.
     */
    data class SparseVector(
        val indices: IntArray,
        val values: FloatArray
    )
}
