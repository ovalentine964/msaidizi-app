package com.msaidizi.app.security.privacy

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Consent management — granular consent per purpose.
 *
 * Per SECURITY_ARCHITECTURE.md and regulatory requirements (DPA 2019, NDPA 2023, POPIA, GDPR):
 * - Consent must be freely given, specific, informed, unambiguous
 * - No pre-ticked boxes
 * - Users can withdraw consent at any time
 * - Consent recorded with timestamp for audit trail
 * - Separate consent per purpose (service, marketing, analytics, biometrics)
 */
@Singleton
class ConsentManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "angavu_consent"
        private const val KEY_CONSENT_PREFIX = "consent_"
        private const val KEY_CONSENT_TIMESTAMP_PREFIX = "consent_ts_"
        private const val KEY_WITHDRAWAL_PREFIX = "withdrawal_"
    }

    /**
     * Consent purposes — each requires separate consent.
     * Includes Swahili descriptions for East African users.
     */
    enum class ConsentPurpose(
        val key: String,
        val description: String,
        val swahiliDescription: String
    ) {
        SERVICE_CORE(
            "service_core",
            "Core service delivery (account, transactions)",
            "Utumizi wa huduma msingi (akaunti, miamala)"
        ),
        KYC_DATA(
            "kyc_data",
            "Identity verification and KYC documents",
            "Uthibitisho wa utambulisho na nyaraka za KYC"
        ),
        BIOMETRIC_FACE(
            "biometric_face",
            "Face biometric for authentication",
            "Uso wa kibiometriki kwa uthibitishaji"
        ),
        BIOMETRIC_VOICE(
            "biometric_voice",
            "Voice biometric for authentication",
            "Sauti ya kibiometriki kwa uthibitishaji"
        ),
        FINANCIAL_DATA(
            "financial_data",
            "Financial transaction data processing",
            "Usindikaji wa data ya miamala ya kifedha"
        ),
        ANALYTICS(
            "analytics",
            "Usage analytics and service improvement",
            "Uchambuzi wa matumizi na uboreshaji wa huduma"
        ),
        MARKETING(
            "marketing",
            "Marketing communications and promotions",
            "Mawasiliano ya masoko na matangazo"
        ),
        CREDIT_SCORING(
            "credit_scoring",
            "Automated credit scoring and assessment",
            "Uthibitishaji wa mikopo wa kiotomatiki"
        ),
        THIRD_PARTY_SHARING(
            "third_party",
            "Sharing data with third-party partners",
            "Kushiriki data na washirika wa tatu"
        ),
        LOCATION(
            "location",
            "Location data for fraud detection and services",
            "Data ya eneo kwa ugunduzi wa ulaghai na huduma"
        )
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Record user consent for a specific purpose.
     */
    fun grantConsent(purpose: ConsentPurpose, userId: String) {
        prefs.edit()
            .putBoolean("$KEY_CONSENT_PREFIX${purpose.key}", true)
            .putLong("$KEY_CONSENT_TIMESTAMP_PREFIX${purpose.key}", System.currentTimeMillis())
            .remove("$KEY_WITHDRAWAL_PREFIX${purpose.key}") // Clear any previous withdrawal
            .apply()
        Timber.i("Consent granted: %s for user %s", purpose.key, userId)
    }

    /**
     * Withdraw consent for a specific purpose.
     */
    fun withdrawConsent(purpose: ConsentPurpose, userId: String) {
        prefs.edit()
            .putBoolean("$KEY_CONSENT_PREFIX${purpose.key}", false)
            .putLong("$KEY_WITHDRAWAL_PREFIX${purpose.key}", System.currentTimeMillis())
            .apply()
        Timber.i("Consent withdrawn: %s for user %s", purpose.key, userId)
    }

    /**
     * Check if user has granted consent for a purpose.
     */
    fun hasConsent(purpose: ConsentPurpose): Boolean {
        return prefs.getBoolean("$KEY_CONSENT_PREFIX${purpose.key}", false)
    }

    /**
     * Get consent timestamp (when consent was last granted).
     */
    fun getConsentTimestamp(purpose: ConsentPurpose): Long {
        return prefs.getLong("$KEY_CONSENT_TIMESTAMP_PREFIX${purpose.key}", 0)
    }

    /**
     * Get all consent states — for audit and data subject access requests.
     */
    fun getAllConsentStates(): Map<ConsentPurpose, ConsentState> {
        return ConsentPurpose.entries.associateWith { purpose ->
            ConsentState(
                purpose = purpose,
                granted = hasConsent(purpose),
                grantedAt = getConsentTimestamp(purpose),
                withdrawnAt = prefs.getLong("$KEY_WITHDRAWAL_PREFIX${purpose.key}", 0)
            )
        }
    }

    /**
     * Check if core consents are granted (required for service to function).
     */
    fun hasCoreConsents(): Boolean {
        return hasConsent(ConsentPurpose.SERVICE_CORE) &&
            hasConsent(ConsentPurpose.FINANCIAL_DATA)
    }

    /**
     * Clear all consent records (e.g., on account deletion).
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        Timber.i("All consent records cleared")
    }

    data class ConsentState(
        val purpose: ConsentPurpose,
        val granted: Boolean,
        val grantedAt: Long,
        val withdrawnAt: Long
    )

    /**
     * One-tap right-to-delete: wipe all user data and consent records.
     * Per GDPR Article 17, DPA 2019 (Kenya), and NDPA 2023 (Nigeria).
     *
     * Returns a WipeResult indicating what was deleted.
     */
    fun requestFullDataWipe(
        userId: String,
        encryptedStorage: com.msaidizi.app.security.crypto.EncryptedStorage,
        tokenStorage: com.msaidizi.app.security.auth.SecureTokenStorage
    ): WipeResult {
        val wiped = mutableListOf<String>()

        // 1. Clear all consent records
        clearAll()
        wiped.add("consent_records")

        // 2. Clear all encrypted storage (financial data, PII)
        encryptedStorage.clearAll()
        wiped.add("encrypted_storage")

        // 3. Clear all auth tokens
        tokenStorage.clearAll()
        wiped.add("auth_tokens")

        // 4. Clear device binding
        val prefs = context.getSharedPreferences("angavu_device_binding", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        wiped.add("device_binding")

        // 5. Clear OTP state
        val otpPrefs = context.getSharedPreferences("angavu_consent", Context.MODE_PRIVATE)
        otpPrefs.edit().clear().apply()

        // 6. Clear session state
        val sessionPrefs = context.getSharedPreferences("angavu_secure_tokens", Context.MODE_PRIVATE)
        sessionPrefs.edit().clear().apply()
        wiped.add("session_state")

        Timber.w("Full data wipe completed for user %s — categories: %s", userId, wiped)

        return WipeResult(
            success = true,
            wipedCategories = wiped,
            timestamp = System.currentTimeMillis()
        )
    }

    data class WipeResult(
        val success: Boolean,
        val wipedCategories: List<String>,
        val timestamp: Long
    )
}
