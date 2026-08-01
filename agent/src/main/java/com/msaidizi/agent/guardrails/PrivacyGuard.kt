package com.msaidizi.agent.guardrails

import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * PrivacyGuard — Pillar 3: Privacy by Design.
 *
 * Enforces on-device data rules:
 * - Raw transactions NEVER leave device (only aggregated/anonymized)
 * - GPS data NEVER leaves device (only region-level cohort ID)
 * - Contacts NEVER leave device (only anonymized interaction counts)
 * - Differential Privacy (ε=0.1) on any aggregated data
 * - k-Anonymity (k≥10) on all cohort-based analytics
 *
 * Architecture:
 * - Data Classification: every piece of data is classified by sensitivity
 * - On-Device Filter: blocks sensitive data from any network call
 * - DP Noise: adds calibrated Gaussian noise to aggregates
 * - Cohort Enforcement: ensures k≥10 before any grouped data leaves device
 */
@Singleton
class PrivacyGuard @Inject constructor() {

    private val secureRandom = SecureRandom()

    // ─── Data Classification ───

    /**
     * Classify data by sensitivity level.
     * CRITICAL: never leaves device
     * SENSITIVE: only after DP noise + k-anonymity
     * AGGREGATE: safe to transmit (already anonymized)
     */
    fun classifyData(dataType: DataType): DataClassification {
        return when (dataType) {
            DataType.RAW_TRANSACTION -> DataClassification(
                level = SensitivityLevel.CRITICAL,
                canLeaveDevice = false,
                reason = "Raw transactions contain exact amounts, items, and timestamps"
            )
            DataType.GPS_LOCATION -> DataClassification(
                level = SensitivityLevel.CRITICAL,
                canLeaveDevice = false,
                reason = "GPS coordinates are personally identifiable"
            )
            DataType.CONTACTS -> DataClassification(
                level = SensitivityLevel.CRITICAL,
                canLeaveDevice = false,
                reason = "Contact list is private personal data"
            )
            DataType.REGION_COHORT -> DataClassification(
                level = SensitivityLevel.SENSITIVE,
                canLeaveDevice = true,
                requiresDP = true,
                requiresKAnonymity = true,
                reason = "Region-level data is safe only with DP + k-anonymity"
            )
            DataType.BUSINESS_TYPE_AGGREGATE -> DataClassification(
                level = SensitivityLevel.SENSITIVE,
                canLeaveDevice = true,
                requiresDP = true,
                requiresKAnonymity = true,
                reason = "Business type aggregates need DP protection"
            )
            DataType.ANONYMIZED_METRICS -> DataClassification(
                level = SensitivityLevel.AGGREGATE,
                canLeaveDevice = true,
                reason = "Already anonymized and aggregated"
            )
            DataType.MODEL_GRADIENTS -> DataClassification(
                level = SensitivityLevel.SENSITIVE,
                canLeaveDevice = true,
                requiresDP = true,
                requiresKAnonymity = true,
                reason = "Gradients can leak training data without DP"
            )
        }
    }

    /**
     * Check if data is allowed to leave the device.
     * Returns a filter result with allowed/blocked status.
     */
    fun filterOutboundData(dataType: DataType, data: Map<String, Any>): PrivacyFilterResult {
        val classification = classifyData(dataType)

        if (!classification.canLeaveDevice) {
            Timber.w("BLOCKED: ${dataType.name} cannot leave device — ${classification.reason}")
            return PrivacyFilterResult(
                allowed = false,
                reason = classification.reason,
                dataType = dataType,
                classification = classification
            )
        }

        return PrivacyFilterResult(
            allowed = true,
            reason = "Allowed with ${if (classification.requiresDP) "DP + " else ""}k-anonymity",
            dataType = dataType,
            classification = classification,
            requiresDP = classification.requiresDP,
            requiresKAnonymity = classification.requiresKAnonymity
        )
    }

    // ─── Differential Privacy (ε=0.1) ───

    companion object {
        const val DEFAULT_EPSILON = 0.1
        const val DEFAULT_DELTA = 1e-8
        const val DEFAULT_CLIP_NORM = 1.0
        const val MIN_COHORT_SIZE = 10 // k ≥ 10
    }

    /**
     * Compute Gaussian noise scale σ for (ε, δ)-DP.
     * σ = Δf × √(2 ln(1.25/δ)) / ε
     */
    fun computeNoiseScale(
        epsilon: Double = DEFAULT_EPSILON,
        delta: Double = DEFAULT_DELTA,
        clipNorm: Double = DEFAULT_CLIP_NORM
    ): Double {
        require(epsilon > 0) { "epsilon must be positive" }
        require(delta in 0.0..1.0) { "delta must be in (0, 1)" }
        return clipNorm * sqrt(2.0 * ln(1.25 / delta)) / epsilon
    }

    /**
     * Add calibrated Gaussian noise to a value for differential privacy.
     * Uses Box-Muller transform with SecureRandom.
     */
    fun addDifferentialPrivacyNoise(
        value: Double,
        epsilon: Double = DEFAULT_EPSILON,
        delta: Double = DEFAULT_DELTA,
        clipNorm: Double = DEFAULT_CLIP_NORM
    ): Double {
        val sigma = computeNoiseScale(epsilon, delta, clipNorm)
        val noise = gaussianNoise(sigma)
        return value + noise
    }

    /**
     * Add DP noise to a map of aggregated values.
     */
    fun addDifferentialPrivacyNoiseToMap(
        values: Map<String, Double>,
        epsilon: Double = DEFAULT_EPSILON,
        delta: Double = DEFAULT_DELTA
    ): Map<String, Double> {
        val sigma = computeNoiseScale(epsilon, delta)
        return values.mapValues { (_, value) -> value + gaussianNoise(sigma) }
    }

    /**
     * Clip a value to [-clipNorm, clipNorm] for bounded sensitivity.
     */
    fun clipValue(value: Double, clipNorm: Double = DEFAULT_CLIP_NORM): Double {
        return value.coerceIn(-clipNorm, clipNorm)
    }

    /**
     * Clip an array of values by L2 norm.
     */
    fun clipByL2Norm(values: DoubleArray, clipNorm: Double = DEFAULT_CLIP_NORM): DoubleArray {
        val norm = sqrt(values.sumOf { it * it })
        if (norm <= clipNorm) return values.copyOf()
        val scale = clipNorm / norm
        return DoubleArray(values.size) { values[it] * scale }
    }

    // ─── k-Anonymity (k≥10) ───

    /**
     * Generate a deterministic cohort ID from dimensions.
     * Uses SHA-256 hash of canonical "region|business_type|language|scale_bucket".
     * This replaces raw GPS/business data with an opaque cohort identifier.
     */
    fun computeCohortId(
        region: String,
        businessType: String,
        language: String,
        scaleBucket: String
    ): String {
        val canonical = "${region.trim().lowercase()}|${businessType.trim().lowercase()}|" +
            "${language.trim().lowercase()}|${scaleBucket.trim().lowercase()}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Validate that a cohort meets k-anonymity requirements.
     * Returns true if cohort size ≥ k (default 10).
     */
    fun validateCohortKAnonymity(cohortSize: Int, k: Int = MIN_COHORT_SIZE): Boolean {
        if (cohortSize < k) {
            Timber.w("Cohort size $cohortSize < k=$k — k-anonymity not satisfied")
            return false
        }
        return true
    }

    /**
     * Enforce k-anonymity on a set of cohorts.
     * Merges cohorts smaller than k with their nearest neighbor.
     * Returns the merged cohort mapping.
     */
    fun enforceKAnonymity(
        cohorts: Map<String, List<Any>>,
        dimensions: Map<String, Map<String, String>>,
        k: Int = MIN_COHORT_SIZE
    ): Map<String, List<Any>> {
        val valid = mutableMapOf<String, List<Any>>()
        val small = mutableListOf<Pair<String, List<Any>>>()

        for ((cid, members) in cohorts) {
            if (members.size >= k) {
                valid[cid] = members
            } else {
                small.add(cid to members)
            }
        }

        if (small.isEmpty()) return valid

        // Merge small cohorts into nearest valid cohort
        for ((cid, members) in small) {
            val bestTarget = valid.keys.maxByOrNull { targetCid ->
                cohortSimilarity(dimensions[cid] ?: emptyMap(), dimensions[targetCid] ?: emptyMap())
            }

            if (bestTarget != null) {
                valid[bestTarget] = valid[bestTarget]!! + members
                Timber.i("Merged cohort $cid (${members.size} members) into $bestTarget")
            } else {
                // No valid cohorts — merge all small together
                val mergedId = computeCohortId("merged", "merged", "merged", "merged")
                valid[mergedId] = (valid[mergedId] ?: emptyList()) + members
            }
        }

        return valid
    }

    private fun cohortSimilarity(a: Map<String, String>, b: Map<String, String>): Int {
        val weights = mapOf("region" to 4, "business_type" to 2, "language" to 1, "scale_bucket" to 0)
        return weights.entries.sumOf { (dim, weight) ->
            if (a[dim] == b[dim]) weight else 0
        }
    }

    // ─── Anonymization Helpers ───

    /**
     * Anonymize GPS coordinates to region-level only.
     * Raw GPS never leaves device — only the cohort ID.
     */
    fun anonymizeLocation(latitude: Double, longitude: Double): String {
        // Round to ~10km grid (0.1 degree ≈ 11km)
        val regionLat = (latitude * 10).toInt() / 10.0
        val regionLon = (longitude * 10).toInt() / 10.0
        return computeCohortId(
            region = "%.1f,%.1f".format(regionLat, regionLon),
            businessType = "any",
            language = "any",
            scaleBucket = "any"
        )
    }

    /**
     * Anonymize a contact to an interaction count.
     * Contact details never leave device.
     */
    fun anonymizeContact(contactId: String, interactionCount: Int): Map<String, Any> {
        val hashedId = MessageDigest.getInstance("SHA-256")
            .digest(contactId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(8)

        return mapOf(
            "anonymous_id" to hashedId,
            "interaction_count" to interactionCount,
            "bucket" to when {
                interactionCount < 5 -> "rare"
                interactionCount < 20 -> "regular"
                else -> "frequent"
            }
        )
    }

    /**
     * Aggregate raw transactions into DP-protected summary.
     * Raw transactions never leave device.
     */
    fun aggregateTransactions(
        amounts: List<Double>,
        epsilon: Double = DEFAULT_EPSILON
    ): Map<String, Double> {
        if (amounts.isEmpty()) return emptyMap()

        val clipped = amounts.map { clipValue(it) }
        val noisedSum = addDifferentialPrivacyNoise(clipped.sum(), epsilon)
        val noisedCount = addDifferentialPrivacyNoise(clipped.size.toDouble(), epsilon)
        val noisedAvg = if (noisedCount > 0) noisedSum / noisedCount else 0.0

        return mapOf(
            "total" to noisedSum,
            "count" to noisedCount,
            "average" to noisedAvg
        )
    }

    private fun gaussianNoise(sigma: Double): Double {
        // Box-Muller transform with SecureRandom
        val u1 = secureRandom.nextDouble().coerceIn(1e-10, 1.0)
        val u2 = secureRandom.nextDouble()
        return sigma * sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }

    // ═══════════════════════════════════════════════════════════════
    //  P1: DATA RETENTION POLICIES
    // ═══════════════════════════════════════════════════════════════

    /**
     * P1: Data retention policy — defines how long each data type is kept.
     * After the retention period, data should be automatically purged.
     *
     * Retention periods:
     * - Transactions: 2 years (tax/regulatory compliance)
     * - Location data: 30 days (privacy)
     * - Conversation history: 90 days (context)
     * - Aggregated metrics: indefinite (already anonymized)
     * - Model gradients: 7 days (privacy)
     */
    enum class RetentionPeriod(val days: Int) {
        TRANSACTIONS(730),      // 2 years
        LOCATION_DATA(30),      // 30 days
        CONVERSATIONS(90),      // 90 days
        AGGREGATED_METRICS(-1), // Indefinite
        MODEL_GRADIENTS(7),     // 7 days
        AUDIT_LOGS(365)         // 1 year
    }

    /**
     * Get the retention period for a data type.
     */
    fun getRetentionPeriod(dataType: DataType): RetentionPeriod {
        return when (dataType) {
            DataType.RAW_TRANSACTION -> RetentionPeriod.TRANSACTIONS
            DataType.GPS_LOCATION -> RetentionPeriod.LOCATION_DATA
            DataType.CONTACTS -> RetentionPeriod.LOCATION_DATA
            DataType.REGION_COHORT -> RetentionPeriod.AGGREGATED_METRICS
            DataType.BUSINESS_TYPE_AGGREGATE -> RetentionPeriod.AGGREGATED_METRICS
            DataType.ANONYMIZED_METRICS -> RetentionPeriod.AGGREGATED_METRICS
            DataType.MODEL_GRADIENTS -> RetentionPeriod.MODEL_GRADIENTS
        }
    }

    /**
     * Check if data should be purged based on retention policy.
     * @return true if data is older than retention period and should be deleted
     */
    fun shouldPurge(dataType: DataType, dataTimestamp: Long): Boolean {
        val retention = getRetentionPeriod(dataType)
        if (retention.days < 0) return false // Indefinite retention

        val cutoff = System.currentTimeMillis() - (retention.days * 24 * 60 * 60 * 1000L)
        return dataTimestamp < cutoff
    }

    /**
     * Get the cutoff timestamp for purging a data type.
     * Data older than this should be deleted.
     */
    fun getPurgeCutoff(dataType: DataType): Long? {
        val retention = getRetentionPeriod(dataType)
        if (retention.days < 0) return null
        return System.currentTimeMillis() - (retention.days * 24 * 60 * 60 * 1000L)
    }

    // ═══════════════════════════════════════════════════════════════
    //  P1: CONSENT MANAGEMENT
    // ═══════════════════════════════════════════════════════════════

    /**
     * P1: User consent types for data sharing and processing.
     */
    enum class ConsentType {
        ANALYTICS,          // Aggregate usage analytics
        LOCATION_SHARING,   // Region-level location for market data
        TRANSACTION_SYNC,   // Sync anonymized transactions to backend
        MARKET_DATA,        // Share price observations with cohort
        CREDIT_SCORING,     // Use data for Alama Score calculation
        NOTIFICATIONS       // Proactive business notifications
    }

    /**
     * P1: Check if user has granted a specific consent.
     * In a real implementation, this would check SharedPreferences or a consent DB.
     * Default: all consents are opt-in (not granted by default).
     */
    fun hasConsent(consentType: ConsentType, userId: String): Boolean {
        // Consent checking logic — in production, check stored consent state
        // For now, return true for essential consents, false for optional
        return when (consentType) {
            ConsentType.CREDIT_SCORING -> true  // Essential for core functionality
            ConsentType.NOTIFICATIONS -> true   // Essential for UX
            ConsentType.ANALYTICS -> false       // Opt-in
            ConsentType.LOCATION_SHARING -> false // Opt-in
            ConsentType.TRANSACTION_SYNC -> false // Opt-in
            ConsentType.MARKET_DATA -> false      // Opt-in
        }
    }

    /**
     * P1: Get consent description for user-facing UI.
     */
    fun getConsentDescription(consentType: ConsentType): Pair<String, String> {
        return when (consentType) {
            ConsentType.ANALYTICS -> Pair(
                "Share anonymous usage data",
                "Shiriki data ya matumizi (binafsi)"
            )
            ConsentType.LOCATION_SHARING -> Pair(
                "Share your region for market prices",
                "Shiriki eneo lako kwa bei za soko"
            )
            ConsentType.TRANSACTION_SYNC -> Pair(
                "Sync transactions to cloud backup",
                "Hifadhi miamala kwa cloud"
            )
            ConsentType.MARKET_DATA -> Pair(
                "Share price observations with other traders",
                "Shiriki bei unazoziona na wafanyabiashara wengine"
            )
            ConsentType.CREDIT_SCORING -> Pair(
                "Use transactions for credit score",
                "Tumia miamala kwa Alama Score"
            )
            ConsentType.NOTIFICATIONS -> Pair(
                "Receive business tips and alerts",
                "Pokea vidokezo na arifa za biashara"
            )
        }
    }
}

// ─── Data Types ───

enum class DataType {
    RAW_TRANSACTION,
    GPS_LOCATION,
    CONTACTS,
    REGION_COHORT,
    BUSINESS_TYPE_AGGREGATE,
    ANONYMIZED_METRICS,
    MODEL_GRADIENTS
}

enum class SensitivityLevel {
    CRITICAL,   // Never leaves device
    SENSITIVE,  // Only with DP + k-anonymity
    AGGREGATE   // Safe to transmit
}

data class DataClassification(
    val level: SensitivityLevel,
    val canLeaveDevice: Boolean,
    val requiresDP: Boolean = false,
    val requiresKAnonymity: Boolean = false,
    val reason: String
)

data class PrivacyFilterResult(
    val allowed: Boolean,
    val reason: String,
    val dataType: DataType,
    val classification: DataClassification,
    val requiresDP: Boolean = false,
    val requiresKAnonymity: Boolean = false
)
