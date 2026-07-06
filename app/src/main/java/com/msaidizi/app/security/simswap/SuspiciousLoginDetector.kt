package com.msaidizi.app.security.simswap

import android.content.Context
import com.msaidizi.app.security.crypto.EncryptedStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Suspicious login detection and risk scoring engine.
 *
 * Per SECURITY_ARCHITECTURE.md Section 3.6:
 * Risk score = weighted_sum(
 *   sim_change_detected(0.4),
 *   new_device(0.15),
 *   unusual_location(0.15),
 *   unusual_time(0.1),
 *   velocity_spike(0.1),
 *   failed_biometric(0.1)
 * )
 *
 * Response actions based on risk score:
 *   0.0–0.3: Normal flow
 *   0.3–0.5: Step-up biometric for transactions
 *   0.5–0.7: Biometric + knowledge-based questions
 *   0.7–0.9: Block outgoing transactions, notify user
 *   0.9–1.0: Account freeze, require in-person verification
 */
@Singleton
class SuspiciousLoginDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceBinder: DeviceBinder,
    private val encryptedStorage: EncryptedStorage
) {
    companion object {
        // Risk score weights
        private const val WEIGHT_SIM_CHANGE = 0.4
        private const val WEIGHT_NEW_DEVICE = 0.15
        private const val WEIGHT_UNUSUAL_LOCATION = 0.15
        private const val WEIGHT_UNUSUAL_TIME = 0.1
        private const val WEIGHT_VELOCITY_SPIKE = 0.1
        private const val WEIGHT_FAILED_BIOMETRIC = 0.1

        // Thresholds
        private const val COOLING_PERIOD_MS = 48 * 60 * 60 * 1000L  // 48 hours
        private const val MAX_LOGIN_VELOCITY = 5  // max logins per hour
        private const val SUSPICIOUS_HOUR_START = 1  // 1 AM
        private const val SUSPICIOUS_HOUR_END = 5    // 5 AM

        private const val KEY_LOGIN_TIMES = "login_times"
        private const val KEY_SIM_CHANGE_TIME = "sim_change_time"
        private const val KEY_LAST_LOGIN_LOCATION = "last_login_location"
    }

    data class RiskAssessment(
        val score: Double,
        val factors: Map<String, Double>,
        val action: RiskAction,
        val coolingPeriodActive: Boolean
    )

    enum class RiskAction {
        ALLOW,              // 0.0–0.3: Normal flow
        STEP_UP_BIOMETRIC,  // 0.3–0.5: Require biometric
        STEP_UP_FULL,       // 0.5–0.7: Biometric + KBA
        BLOCK_TRANSACTIONS, // 0.7–0.9: Block transactions
        FREEZE_ACCOUNT      // 0.9–1.0: Account freeze
    }

    /**
     * Assess login risk based on multiple signals.
     */
    fun assessLoginRisk(
        userId: String,
        appVersion: String,
        latitude: Double? = null,
        longitude: Double? = null,
        failedBiometric: Boolean = false
    ): RiskAssessment {
        val factors = mutableMapOf<String, Double>()

        // Factor 1: SIM change detection
        val simChangeTime = encryptedStorage.getString("$userId:$KEY_SIM_CHANGE_TIME")?.toLongOrNull()
        val simChanged = simChangeTime != null &&
            (System.currentTimeMillis() - simChangeTime) < COOLING_PERIOD_MS
        factors["sim_change"] = if (simChanged) 1.0 else 0.0

        // Factor 2: New device detection
        val deviceVerification = deviceBinder.verifyDevice(userId, appVersion)
        factors["new_device"] = when (deviceVerification) {
            DeviceBinder.DeviceVerification.MATCH -> 0.0
            DeviceBinder.DeviceVerification.MISMATCH -> 1.0
            DeviceBinder.DeviceVerification.NO_BINDING -> 0.5
        }

        // Factor 3: Unusual location
        factors["unusual_location"] = calculateLocationRisk(userId, latitude, longitude)

        // Factor 4: Unusual time
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        factors["unusual_time"] = if (hour in SUSPICIOUS_HOUR_START..SUSPICIOUS_HOUR_END) 1.0 else 0.0

        // Factor 5: Login velocity spike
        factors["velocity_spike"] = calculateVelocityRisk(userId)

        // Factor 6: Failed biometric
        factors["failed_biometric"] = if (failedBiometric) 1.0 else 0.0

        // Calculate weighted risk score
        val score = (
            (factors["sim_change"] ?: 0.0) * WEIGHT_SIM_CHANGE +
            (factors["new_device"] ?: 0.0) * WEIGHT_NEW_DEVICE +
            (factors["unusual_location"] ?: 0.0) * WEIGHT_UNUSUAL_LOCATION +
            (factors["unusual_time"] ?: 0.0) * WEIGHT_UNUSUAL_TIME +
            (factors["velocity_spike"] ?: 0.0) * WEIGHT_VELOCITY_SPIKE +
            (factors["failed_biometric"] ?: 0.0) * WEIGHT_FAILED_BIOMETRIC
        ).coerceIn(0.0, 1.0)

        val action = when {
            score < 0.3 -> RiskAction.ALLOW
            score < 0.5 -> RiskAction.STEP_UP_BIOMETRIC
            score < 0.7 -> RiskAction.STEP_UP_FULL
            score < 0.9 -> RiskAction.BLOCK_TRANSACTIONS
            else -> RiskAction.FREEZE_ACCOUNT
        }

        // Record login attempt
        recordLogin(userId)

        // Update last known location
        if (latitude != null && longitude != null) {
            encryptedStorage.putString(
                "$userId:$KEY_LAST_LOGIN_LOCATION",
                "$latitude,$longitude"
            )
        }

        val assessment = RiskAssessment(
            score = score,
            factors = factors,
            action = action,
            coolingPeriodActive = simChanged
        )

        Timber.i("Login risk assessment for user %s: score=%.2f, action=%s, factors=%s",
            userId, score, action, factors)

        return assessment
    }

    /**
     * Record a SIM change event (called when carrier API detects SIM swap).
     */
    fun recordSimChange(userId: String) {
        encryptedStorage.putString(
            "$userId:$KEY_SIM_CHANGE_TIME",
            System.currentTimeMillis().toString()
        )
        Timber.w("SIM change recorded for user %s — 48h cooling period active", userId)
    }

    /**
     * Check if user is in SIM change cooling period.
     */
    fun isInCoolingPeriod(userId: String): Boolean {
        val changeTime = encryptedStorage.getString("$userId:$KEY_SIM_CHANGE_TIME")?.toLongOrNull()
            ?: return false
        return (System.currentTimeMillis() - changeTime) < COOLING_PERIOD_MS
    }

    private fun calculateLocationRisk(userId: String, lat: Double?, lon: Double?): Double {
        if (lat == null || lon == null) return 0.3 // Unknown location = moderate risk

        val lastLocation = encryptedStorage.getString("$userId:$KEY_LAST_LOGIN_LOCATION")
            ?: return 0.0 // First login, no baseline

        val parts = lastLocation.split(",")
        if (parts.size != 2) return 0.0

        val lastLat = parts[0].toDoubleOrNull() ?: return 0.0
        val lastLon = parts[1].toDoubleOrNull() ?: return 0.0

        // Calculate approximate distance using Haversine
        val distanceKm = haversineDistance(lat, lon, lastLat, lastLon)

        return when {
            distanceKm < 50 -> 0.0      // Same city
            distanceKm < 200 -> 0.3     // Same region
            distanceKm < 1000 -> 0.7    // Same country, different region
            else -> 1.0                  // Different country
        }
    }

    private fun calculateVelocityRisk(userId: String): Double {
        val loginTimes = encryptedStorage.getString("$userId:$KEY_LOGIN_TIMES")
            ?.split(",")?.mapNotNull { it.toLongOrNull() } ?: return 0.0

        val oneHourAgo = System.currentTimeMillis() - 3600_000
        val recentLogins = loginTimes.count { it > oneHourAgo }

        return when {
            recentLogins <= 2 -> 0.0
            recentLogins <= MAX_LOGIN_VELOCITY -> 0.5
            else -> 1.0
        }
    }

    private fun recordLogin(userId: String) {
        val now = System.currentTimeMillis()
        val oneHourAgo = now - 3600_000
        val existing = encryptedStorage.getString("$userId:$KEY_LOGIN_TIMES")
            ?.split(",")?.mapNotNull { it.toLongOrNull() }
            ?.filter { it > oneHourAgo }?.toMutableList() ?: mutableListOf()

        existing.add(now)
        // Keep only last 20 logins
        val trimmed = existing.takeLast(20)
        encryptedStorage.putString("$userId:$KEY_LOGIN_TIMES", trimmed.joinToString(","))
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // Earth radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }
}
