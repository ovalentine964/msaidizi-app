package com.msaidizi.app.security.simswap

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.msaidizi.app.security.auth.SecureTokenStorage
import com.msaidizi.app.security.crypto.CryptoService
import com.msaidizi.app.security.crypto.EncryptedStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device binding mechanism for SIM swap defense.
 *
 * Per SECURITY_ARCHITECTURE.md Section 3.6:
 * - Users can register up to 3 trusted devices
 * - New devices require additional verification
 * - Device fingerprint tracked for anomaly detection
 * - SIM change triggers 48-hour cooling period
 */
@Singleton
class DeviceBinder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenStorage: SecureTokenStorage,
    private val encryptedStorage: EncryptedStorage,
    private val cryptoService: CryptoService
) {
    companion object {
        private const val KEY_DEVICE_FINGERPRINT = "device_fingerprint"
        private const val KEY_BOUND_AT = "device_bound_at"
        private const val KEY_SIM_SERIAL = "sim_serial_hash"
    }

    data class DeviceFingerprint(
        val deviceId: String,
        val model: String,
        val manufacturer: String,
        val osVersion: String,
        val appVersion: String,
        val hardwareId: String
    ) {
        fun toHash(): String {
            val combined = "$deviceId|$model|$manufacturer|$osVersion|$appVersion|$hardwareId"
            return cryptoService.sha256(combined)
        }
    }

    /**
     * Generate a device fingerprint for binding.
     */
    fun generateFingerprint(appVersion: String): DeviceFingerprint {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"

        // Hardware-level identifiers (non-resettable)
        val hardwareId = cryptoService.sha256(
            "${Build.BOARD}|${Build.BRAND}|${Build.DEVICE}|${Build.HARDWARE}|${Build.MANUFACTURER}|${Build.MODEL}"
        )

        return DeviceFingerprint(
            deviceId = androidId,
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            osVersion = Build.VERSION.RELEASE,
            appVersion = appVersion,
            hardwareId = hardwareId
        )
    }

    /**
     * Bind the current device to a user account.
     * Stores the device fingerprint securely.
     */
    fun bindDevice(userId: String, appVersion: String): Boolean {
        val fingerprint = generateFingerprint(appVersion)
        val fingerprintHash = fingerprint.toHash()

        // Store binding info encrypted
        encryptedStorage.putString(
            "$userId:$KEY_DEVICE_FINGERPRINT",
            fingerprintHash
        )
        encryptedStorage.putString(
            "$userId:$KEY_BOUND_AT",
            System.currentTimeMillis().toString()
        )

        Timber.i("Device bound for user %s (fingerprint: %s...)",
            userId, fingerprintHash.take(16))
        return true
    }

    /**
     * Verify that the current device matches the bound device.
     * Returns true if device matches, false if device changed.
     */
    fun verifyDevice(userId: String, appVersion: String): DeviceVerification {
        val storedHash = encryptedStorage.getString("$userId:$KEY_DEVICE_FINGERPRINT")
            ?: return DeviceVerification.NO_BINDING

        val currentFingerprint = generateFingerprint(appVersion)
        val currentHash = currentFingerprint.toHash()

        return if (storedHash == currentHash) {
            DeviceVerification.MATCH
        } else {
            // Check if it's a minor change (e.g., app update changed version)
            val storedWithoutVersion = encryptedStorage.getString("$userId:$KEY_DEVICE_FINGERPRINT")
            // For now, any mismatch is flagged
            Timber.w("Device fingerprint mismatch for user %s — possible new device", userId)
            DeviceVerification.MISMATCH
        }
    }

    /**
     * Record SIM serial hash for SIM swap detection.
     * Requires READ_PHONE_STATE permission.
     */
    fun recordSimInfo(userId: String, simSerial: String?) {
        if (simSerial != null) {
            val hash = cryptoService.sha256(simSerial)
            encryptedStorage.putString("$userId:$KEY_SIM_SERIAL", hash)
        }
    }

    /**
     * Check if SIM has changed since last binding.
     */
    fun hasSimChanged(userId: String, currentSimSerial: String?): Boolean {
        if (currentSimSerial == null) return false
        val storedHash = encryptedStorage.getString("$userId:$KEY_SIM_SERIAL") ?: return false
        val currentHash = cryptoService.sha256(currentSimSerial)
        return storedHash != currentHash
    }

    enum class DeviceVerification {
        MATCH,          // Device matches binding
        MISMATCH,       // Device fingerprint changed
        NO_BINDING      // No device binding exists
    }
}
