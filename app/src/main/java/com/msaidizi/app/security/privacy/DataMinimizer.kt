package com.msaidizi.app.security.privacy

import android.content.Context
import com.msaidizi.app.core.util.CryptoUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data minimization — collect only what's needed, mask what's stored.
 *
 * Per SECURITY_ARCHITECTURE.md Section 2.4:
 * 1. Collect only what is needed
 * 2. Purpose limitation — KYC data not used for marketing without separate consent
 * 3. Anonymization pipeline for ML training data
 * 4. Field-level encryption for PII
 * 5. Partial masking for all identifiers
 */
@Singleton
class DataMinimizer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // Minimum data required for different service levels
        val MINIMAL_FIELDS = setOf("phone_number")
        val BASIC_FIELDS = setOf("phone_number", "name", "language")
        val VERIFIED_FIELDS = setOf("phone_number", "name", "language", "national_id", "selfie")
    }

    /**
     * Mask a phone number for display.
     * +254712345678 → +254***678
     */
    fun maskPhone(phone: String): String {
        if (phone.length < 7) return "***"
        return phone.take(4) + "***" + phone.takeLast(3)
    }

    /**
     * Mask a national ID for display.
     * 12345678 → 12***78
     */
    fun maskNationalId(id: String): String {
        if (id.length < 4) return "***"
        return id.take(2) + "***" + id.takeLast(2)
    }

    /**
     * Mask a name for display.
     * John Doe → J*** D**
     */
    fun maskName(name: String): String {
        return name.split(" ").joinToString(" ") { part ->
            if (part.length <= 1) "*"
            else part.take(1) + "*".repeat(part.length - 1)
        }
    }

    /**
     * Mask an account number for display.
     * 1234567890 → ****7890
     */
    fun maskAccountNumber(account: String): String {
        if (account.length < 4) return "****"
        return "*".repeat(account.length - 4) + account.takeLast(4)
    }

    /**
     * Hash PII for storage where original value isn't needed.
     * Used for phone numbers in logs, analytics, etc.
     */
    fun hashForStorage(value: String): String {
        return CryptoUtils.sha256("angavu_pii:$value")
    }

    /**
     * Anonymize a record for ML training.
     * Removes or generalizes identifying fields.
     */
    fun anonymizeForTraining(data: Map<String, Any?>): Map<String, Any?> {
        val anonymized = data.toMutableMap()

        // Remove direct identifiers
        anonymized.remove("phone_number")
        anonymized.remove("national_id")
        anonymized.remove("name")
        anonymized.remove("email")
        anonymized.remove("selfie_url")

        // Generalize location (keep region, remove precise coords)
        if (anonymized.containsKey("latitude") || anonymized.containsKey("longitude")) {
            anonymized.remove("latitude")
            anonymized.remove("longitude")
            // Keep only region/city level
            anonymized["location_region"] = data["region"] ?: "unknown"
        }

        // Generalize time (replace exact timestamp with time bin)
        if (anonymized.containsKey("timestamp")) {
            val ts = data["timestamp"] as? Long
            if (ts != null) {
                anonymized["time_bin"] = getTimeBin(ts)
                anonymized.remove("timestamp")
            }
        }

        // Replace user ID with session pseudonym
        if (anonymized.containsKey("user_id")) {
            anonymized["session_id"] = hashForStorage(data["user_id"].toString()).take(16)
            anonymized.remove("user_id")
        }

        return anonymized
    }

    /**
     * Check if data collection is minimal for the stated purpose.
     * Returns fields that may be unnecessary.
     */
    fun checkMinimization(collectedFields: Set<String>, purpose: ServicePurpose): Set<String> {
        val required = when (purpose) {
            ServicePurpose.BASIC_ACCESS -> MINIMAL_FIELDS
            ServicePurpose.FULL_SERVICE -> BASIC_FIELDS
            ServicePurpose.KYC -> VERIFIED_FIELDS
            ServicePurpose.ANALYTICS -> emptySet() // Analytics should use anonymized data
        }
        return collectedFields - required
    }

    /**
     * Get a time bin for anonymization.
     * Exact timestamps → morning/afternoon/evening/night
     */
    private fun getTimeBin(timestampMs: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = timestampMs
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 6..11 -> "morning"
            in 12..17 -> "afternoon"
            in 18..21 -> "evening"
            else -> "night"
        }
    }

    enum class ServicePurpose {
        BASIC_ACCESS,
        FULL_SERVICE,
        KYC,
        ANALYTICS
    }
}
