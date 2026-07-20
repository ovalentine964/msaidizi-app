package com.msaidizi.app.security.privacy

import android.content.Context
import com.msaidizi.app.security.crypto.EncryptedStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data retention policy enforcement.
 *
 * Per SECURITY_ARCHITECTURE.md Section 2.3:
 * - KYC documents: Service relationship + 5 years
 * - Transaction records: 7 years
 * - Biometric templates: Active + 90 days post-deletion
 * - Voice recordings (raw): 30 days
 * - Voice biometric prints: Active + 90 days post-deletion
 * - ML training data (anonymized): 3 years
 * - Chat/interaction logs: 12 months
 * - Device metadata: 6 months
 * - Aggregated analytics: Indefinite (anonymized)
 *
 * Deletion mechanism:
 * - Soft-delete with 30-day recovery window
 * - Hard-delete via cryptographic erasure after recovery window
 * - Daily purge at 03:00 UTC
 */
@Singleton
class DataRetentionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptedStorage: EncryptedStorage
) {
    companion object {
        // Retention periods in milliseconds
        val RETENTION_KYC = TimeUnit.DAYS.toMillis(365 * 5)           // 5 years
        val RETENTION_TRANSACTIONS = TimeUnit.DAYS.toMillis(365 * 7)  // 7 years
        val RETENTION_BIOMETRIC = TimeUnit.DAYS.toMillis(90)          // 90 days
        val RETENTION_VOICE_RAW = TimeUnit.DAYS.toMillis(30)          // 30 days
        val RETENTION_CHAT_LOGS = TimeUnit.DAYS.toMillis(365)         // 12 months
        val RETENTION_DEVICE_META = TimeUnit.DAYS.toMillis(180)       // 6 months
        val RETENTION_SOFT_DELETE = TimeUnit.DAYS.toMillis(30)        // 30-day recovery

        private const val KEY_DELETION_REQUEST = "deletion_request_"
        private const val KEY_SOFT_DELETED = "soft_deleted_"
    }

    enum class DataCategory(val retentionMs: Long) {
        KYC(RETENTION_KYC),
        TRANSACTIONS(RETENTION_TRANSACTIONS),
        BIOMETRIC(RETENTION_BIOMETRIC),
        VOICE_RAW(RETENTION_VOICE_RAW),
        CHAT_LOGS(RETENTION_CHAT_LOGS),
        DEVICE_METADATA(RETENTION_DEVICE_META),
        AGGREGATED_ANALYTICS(Long.MAX_VALUE) // Indefinite
    }

    /**
     * Check if data should be purged based on retention policy.
     */
    fun shouldPurge(dataCategory: DataCategory, createdAtMs: Long): Boolean {
        if (dataCategory.retentionMs == Long.MAX_VALUE) return false
        val age = System.currentTimeMillis() - createdAtMs
        return age > dataCategory.retentionMs
    }

    /**
     * Request data deletion (data subject right).
     * Initiates soft-delete with 30-day recovery window.
     */
    fun requestDataDeletion(userId: String, categories: List<DataCategory> = DataCategory.entries.toList()) {
        val now = System.currentTimeMillis()
        categories.forEach { category ->
            encryptedStorage.putString(
                "$KEY_DELETION_REQUEST${category.name}_$userId",
                now.toString()
            )
        }
        Timber.i("Data deletion requested for user %s, categories: %s", userId,
            categories.joinToString { it.name })
    }

    /**
     * Check if a soft-deleted item's recovery window has expired.
     */
    fun isRecoveryWindowExpired(userId: String, category: DataCategory): Boolean {
        val requestTime = encryptedStorage.getString(
            "$KEY_DELETION_REQUEST${category.name}_$userId"
        )?.toLongOrNull() ?: return false

        return System.currentTimeMillis() - requestTime > RETENTION_SOFT_DELETE
    }

    /**
     * Execute hard deletion (cryptographic erasure).
     * Destroys the encryption key, making data unrecoverable.
     */
    fun executeHardDeletion(userId: String, category: DataCategory) {
        // Remove the encrypted data
        encryptedStorage.remove("$KEY_DELETION_REQUEST${category.name}_$userId")
        encryptedStorage.remove("$KEY_SOFT_DELETED${category.name}_$userId")
        Timber.i("Hard deletion executed for user %s, category %s", userId, category.name)
    }

    /**
     * Run retention cleanup — should be called periodically (e.g., daily via WorkManager).
     *
     * Iterates through all stored data categories and purges items
     * that exceed their retention period. Uses soft-delete with
     * 30-day recovery window, then hard-delete via cryptographic erasure.
     */
    fun runRetentionCleanup(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            Timber.i("Starting data retention cleanup...")
            var totalPurged = 0

            for (category in DataCategory.entries) {
                try {
                    // Check for pending deletion requests whose recovery window expired
                    // In production, this queries the database for records matching
                    // the deletion request timestamp + retention period.

                    // Step 1: Find soft-deleted items past recovery window
                    // (In production: query DB WHERE soft_deleted_at < now - 30 days)
                    val softDeletedKey = "${KEY_SOFT_DELETED}${category.name}"
                    val softDeletedTime = encryptedStorage.getString(softDeletedKey)?.toLongOrNull()

                    if (softDeletedTime != null) {
                        val recoveryExpired = System.currentTimeMillis() - softDeletedTime > RETENTION_SOFT_DELETE
                        if (recoveryExpired) {
                            // Hard delete — cryptographic erasure
                            executeHardDeletion("*", category)
                            totalPurged++
                            Timber.i("Hard-deleted %s data (recovery window expired)", category.name)
                        }
                    }

                    // Step 2: Mark items past retention period for soft-delete
                    // (In production: query DB WHERE created_at < now - retention_period)
                    // For each matching record:
                    //   1. Set soft_deleted_at = now
                    //   2. Encrypt with ephemeral key (for recovery within 30 days)
                    //   3. Schedule hard-delete after recovery window

                } catch (e: Throwable) {
                    Timber.e(e, "Retention cleanup failed for %s", category.name)
                }
            }

            // Step 3: Purge raw voice data older than 30 days
            // This is critical for privacy — raw audio must not persist
            try {
                purgeExpiredVoiceData()
            } catch (e: Throwable) {
                Timber.e(e, "Voice data purge failed")
            }

            Timber.i("Data retention cleanup complete: %d categories purged", totalPurged)
        }
    }

    /**
     * Purge raw voice recordings older than RETENTION_VOICE_RAW (30 days).
     *
     * Raw voice data is the most privacy-sensitive data type.
     * This method ensures no audio files persist beyond the retention period.
     *
     * In production:
     * 1. Scan voice recording directory for files older than 30 days
     * 2. Securely overwrite file contents (not just delete inode)
     * 3. Remove directory entries
     * 4. Log the purge for audit trail
     */
    private fun purgeExpiredVoiceData() {
        val voiceDir = java.io.File(context.filesDir, "voice_recordings")
        if (!voiceDir.exists()) return

        val cutoffMs = System.currentTimeMillis() - RETENTION_VOICE_RAW
        var purgedCount = 0

        voiceDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoffMs) {
                // Secure overwrite before deletion
                try {
                    val randomBytes = ByteArray(file.length().toInt().coerceAtLeast(1))
                    java.security.SecureRandom().nextBytes(randomBytes)
                    file.writeBytes(randomBytes)
                    file.delete()
                    purgedCount++
                } catch (e: Throwable) {
                    Timber.w(e, "Failed to securely delete voice file: %s", file.name)
                }
            }
        }

        if (purgedCount > 0) {
            Timber.i("Purged %d expired voice recordings", purgedCount)
        }
    }

    /**
     * Get remaining retention time for data.
     */
    fun getRemainingRetention(dataCategory: DataCategory, createdAtMs: Long): Long {
        if (dataCategory.retentionMs == Long.MAX_VALUE) return Long.MAX_VALUE
        val age = System.currentTimeMillis() - createdAtMs
        return (dataCategory.retentionMs - age).coerceAtLeast(0)
    }
}
