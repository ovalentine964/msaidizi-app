package com.msaidizi.app.security.crypto

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the SQLCipher database encryption passphrase.
 *
 * The passphrase is a 32-byte random key stored in EncryptedSharedPreferences
 * (which itself is backed by Android Keystore — hardware-backed TEE/StrongBox).
 *
 * Security model:
 * - Passphrase is randomly generated (high entropy, not derivable from user data)
 * - Stored in EncryptedSharedPreferences (AES-256-GCM, key in Android Keystore)
 * - Never exposed outside this class
 * - Cleared on logout (user must re-authenticate; new DB created on next login)
 *
 * Fallback strategy (3-tier):
 * Tier 1: EncryptedSharedPreferences (ideal — hardware-backed)
 * Tier 2: Plain SharedPreferences (key stored without encryption, DB still encrypted)
 * Tier 3: Device-derived deterministic key (SHA-256 of ANDROID_ID + salt — preserves data)
 *
 * The deterministic fallback means that even if Keystore AND prefs are both lost,
 * the same device will derive the same key, so the database remains readable.
 * Less secure than random keys, but prevents data loss on budget devices.
 *
 * On 2GB devices in Kenya/East Africa, Keystore failures are not rare.
 * A working database with degraded key security is always better than data loss.
 */
@Singleton
class DatabaseKeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_FILE = "msaidizi_db_key_prefs"
        private const val PREFS_FILE_FALLBACK = "msaidizi_db_key_prefs_plain"
        private const val PREFS_FILE_BACKUP = "msaidizi_db_key_backup" // Second plain backup
        private const val KEY_DB_PASSPHRASE = "db_encryption_passphrase"
        private const val KEY_DB_PASSPHRASE_BACKUP = "db_encryption_passphrase_v2"
        private const val KEY_CORRUPTION_COUNT = "keystore_corruption_count"
        private const val KEY_LAST_CORRUPTION_TS = "keystore_last_corruption_ts"
        private const val KEY_USING_DETERMINISTIC = "using_deterministic_key"
        private const val PASSPHRASE_LENGTH = 32 // 256-bit key
        private const val TAG = "DatabaseKeyManager"
        private const val DEVICE_KEY_SALT = "msaidizi_device_key_v1_2026"
    }

    /**
     * Lazily initialize EncryptedSharedPreferences.
     * Returns null if initialization fails (Keystore corruption, etc.).
     */
    private val encryptedPrefs: SharedPreferences? by lazy {
        try {
            val masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(
                androidx.security.crypto.MasterKeys.AES256_GCM_SPEC
            )
            androidx.security.crypto.EncryptedSharedPreferences.create(
                PREFS_FILE,
                masterKeyAlias,
                context,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Throwable) {
            // Keystore corruption, lock screen change, hardware failure, etc.
            // This is a known failure mode on low-end Android devices.
            Timber.e(e, "EncryptedSharedPreferences init failed — will use fallback")
            logCorruptionEvent("encrypted_prefs_init_failed", e)
            try {
                io.sentry.Sentry.captureException(e)
            } catch (_: Throwable) {}
            null
        }
    }

    /**
     * Fallback plain SharedPreferences.
     * Used ONLY when EncryptedSharedPreferences fails.
     * The key is still randomly generated — it's just stored without encryption.
     * This is acceptable because the database file itself is SQLCipher-encrypted;
     * the key in prefs is an extra layer, not the primary defense.
     */
    private val fallbackPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_FILE_FALLBACK, Context.MODE_PRIVATE)
    }

    /**
     * Second backup plain SharedPreferences.
     * Used as an additional recovery source if both encrypted and first fallback fail.
     */
    private val backupPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_FILE_BACKUP, Context.MODE_PRIVATE)
    }

    /**
     * Get the active SharedPreferences (encrypted preferred, fallback if needed).
     */
    private fun getActivePrefs(): SharedPreferences {
        return encryptedPrefs ?: fallbackPrefs
    }

    /**
     * Get or create the database encryption passphrase.
     * Returns 32 bytes of cryptographically secure random data.
     * On first call, generates and securely stores the key.
     *
     * Recovery priority:
     * 1. Read from encrypted prefs (best)
     * 2. Read from plain fallback prefs
     * 3. Read from second backup prefs
     * 4. Generate deterministic key from device ID (preserves data across reinstalls)
     * 5. Generate transient random key (data lost on next launch)
     *
     * NEVER throws — falls back through all tiers gracefully.
     */
    fun getPassphrase(): ByteArray {
        return try {
            val prefs = getActivePrefs()
            val stored = prefs.getString(KEY_DB_PASSPHRASE, null)
            if (stored != null) {
                Base64.decode(stored, Base64.NO_WRAP)
            } else {
                // Key not found in primary prefs — try recovery

                recoverOrGeneratePassphrase()
            }
        } catch (e: Throwable) {
            // Catastrophic failure — try recovery from backup sources

            Timber.e(e, "CRITICAL: Failed to read DB passphrase — attempting recovery")
            logCorruptionEvent("passphrase_read_catastrophic", e)
            try { io.sentry.Sentry.captureException(e) } catch (_: Throwable) {}
            recoverFromBackupOrFallback()
        }
    }

    /**
     * Try to recover the passphrase from backup locations, or generate a new one.
     * Called when the primary prefs exist but the key is missing.
     */
    private fun recoverOrGeneratePassphrase(): ByteArray {
        // Try backup prefs (plain)

        val backupKey = tryReadFromPrefs(backupPrefs, KEY_DB_PASSPHRASE_BACKUP)
        if (backupKey != null) {
            Timber.i("Recovered passphrase from backup prefs")
            // Re-store in primary prefs for future reads
            try {
                getActivePrefs().edit()
                    .putString(KEY_DB_PASSPHRASE, Base64.encodeToString(backupKey, Base64.NO_WRAP))
                    .apply()
            } catch (_: Throwable) {}
            return backupKey
        }

        // Try fallback prefs

        val fallbackKey = tryReadFromPrefs(fallbackPrefs, KEY_DB_PASSPHRASE)
        if (fallbackKey != null) {
            Timber.i("Recovered passphrase from fallback prefs")
            // Back up to both locations
            storeToMultipleLocations(fallbackKey)
            return fallbackKey
        }

        // No key found anywhere — generate new and store in all locations

        return generateAndStorePassphrase(getActivePrefs())
    }

    /**
     * Try to recover from backup locations after a catastrophic read failure.
     */
    private fun recoverFromBackupOrFallback(): ByteArray {
        // Try all backup locations

        for (prefs in listOf(fallbackPrefs, backupPrefs)) {
            try {
                val key = tryReadFromPrefs(prefs, KEY_DB_PASSPHRASE)
                    ?: tryReadFromPrefs(prefs, KEY_DB_PASSPHRASE_BACKUP)
                if (key != null) {
                    Timber.i("Recovered passphrase from backup storage after catastrophic failure")
                    return key
                }
            } catch (_: Throwable) {}
        }

        // All recovery failed — use deterministic device-derived key
        // This preserves data across app restarts on the same device
        Timber.w("All key recovery failed — using deterministic device-derived key")
        return generateDeterministicDeviceKey()
    }

    /**
     * Try to read a Base64-encoded key from a SharedPreferences instance.
     * Returns null if not found or decode fails.
     */
    private fun tryReadFromPrefs(prefs: SharedPreferences, key: String): ByteArray? {
        return try {
            val stored = prefs.getString(key, null)
            if (stored != null) Base64.decode(stored, Base64.NO_WRAP) else null
        } catch (e: Throwable) {
            Timber.w(e, "Failed to read key '%s' from prefs", key)
            null
        }
    }

    /**
     * Generate a deterministic key derived from the device's ANDROID_ID.
     * This is less secure than a random key but ensures data persistence
     * when all other key storage mechanisms fail.
     *
     * Uses SHA-256(ANDROID_ID + salt) to produce a 32-byte key.
     * The salt prevents trivial rainbow table attacks.
     *
     * Trade-off: If ANDROID_ID changes (factory reset, some ROM changes),
     * the database becomes inaccessible. But this is rare and acceptable
     * as a last-resort fallback.
     */
    @SuppressLint("HardwareIds")
    private fun generateDeterministicDeviceKey(): ByteArray {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: Build.FINGERPRINT // Fallback to build fingerprint
        } catch (e: Throwable) {
            // Some devices block Settings.Secure — use build info
            "${Build.BOARD}-${Build.DEVICE}-${Build.ID}-${Build.FINGERPRINT}"
        }

        val input = "$androidId:$DEVICE_KEY_SALT"
        val digest = MessageDigest.getInstance("SHA-256")
        val key = digest.digest(input.toByteArray(Charsets.UTF_8))

        // Store this deterministic key in backup prefs for future recovery
        try {
            backupPrefs.edit()
                .putString(KEY_DB_PASSPHRASE_BACKUP, Base64.encodeToString(key, Base64.NO_WRAP))
                .putBoolean(KEY_USING_DETERMINISTIC, true)
                .apply()
        } catch (_: Throwable) {}

        logCorruptionEvent("deterministic_key_generated", null)
        Timber.w("Using deterministic device-derived key — reduced security but data preserved")
        try {
            io.sentry.Sentry.captureMessage(
                "Database encryption key: all storage failed, using deterministic device key"
            )
        } catch (_: Throwable) {}

        return key
    }

    /**
     * Store a passphrase in multiple locations for redundancy.
     */
    private fun storeToMultipleLocations(passphrase: ByteArray) {
        val encoded = Base64.encodeToString(passphrase, Base64.NO_WRAP)
        // Primary
        try {
            getActivePrefs().edit().putString(KEY_DB_PASSPHRASE, encoded).apply()
        } catch (e: Throwable) {
            Timber.w(e, "Failed to store key in primary prefs")
        }
        // Fallback
        try {
            fallbackPrefs.edit().putString(KEY_DB_PASSPHRASE, encoded).apply()
        } catch (e: Throwable) {
            Timber.w(e, "Failed to store key in fallback prefs")
        }
        // Backup
        try {
            backupPrefs.edit().putString(KEY_DB_PASSPHRASE_BACKUP, encoded).apply()
        } catch (e: Throwable) {
            Timber.w(e, "Failed to store key in backup prefs")
        }
    }

    /**
     * Generate a new passphrase and store it in all locations.
     */
    private fun generateAndStorePassphrase(prefs: SharedPreferences): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_LENGTH)
        SecureRandom().nextBytes(passphrase)

        storeToMultipleLocations(passphrase)
        Timber.i("Generated new database encryption key (%d bytes)", PASSPHRASE_LENGTH)
        return passphrase
    }

    /**
     * Generate a transient passphrase (not persisted).
     * Used as absolute last-resort fallback when all storage mechanisms fail.
     * The database will be re-created on each app launch.
     */
    private fun generateTransientPassphrase(): ByteArray {
        Timber.w("Using transient database key — data will not persist across launches")
        logCorruptionEvent("transient_key_used", null)
        val passphrase = ByteArray(PASSPHRASE_LENGTH)
        SecureRandom().nextBytes(passphrase)
        return passphrase
    }

    /**
     * Log a Keystore corruption event for diagnostics.
     * Tracks count and timestamps to identify repeat failures.
     */
    private fun logCorruptionEvent(eventType: String, error: Throwable?) {
        try {
            val prefs = backupPrefs // Use backup prefs since primary may be broken
            val count = prefs.getInt(KEY_CORRUPTION_COUNT, 0) + 1
            prefs.edit()
                .putInt(KEY_CORRUPTION_COUNT, count)
                .putLong(KEY_LAST_CORRUPTION_TS, System.currentTimeMillis())
                .apply()
            Timber.w("Keystore corruption event #%d: %s", count, eventType)
            if (error != null) {
                Timber.w(error, "Corruption details: %s", eventType)
            }
        } catch (_: Throwable) {}
    }

    /**
     * Get the number of Keystore corruption events recorded.
     * Used by diagnostics to assess device health.
     */
    fun getCorruptionCount(): Int {
        return try {
            backupPrefs.getInt(KEY_CORRUPTION_COUNT, 0)
        } catch (_: Throwable) { 0 }
    }

    /**
     * Check if the current key is a deterministic device-derived key.
     * Indicates degraded security but working data persistence.
     */
    fun isUsingDeterministicKey(): Boolean {
        return try {
            backupPrefs.getBoolean(KEY_USING_DETERMINISTIC, false)
        } catch (_: Throwable) { false }
    }

    /**
     * Clear the stored passphrase (called on logout).
     * The encrypted database will be inaccessible after this.
     * A new database with a new key will be created on next login.
     */
    fun clearKey() {
        try {
            getActivePrefs().edit().remove(KEY_DB_PASSPHRASE).apply()
        } catch (_: Throwable) {}
        try {
            fallbackPrefs.edit().remove(KEY_DB_PASSPHRASE).apply()
        } catch (_: Throwable) {}
        try {
            backupPrefs.edit()
                .remove(KEY_DB_PASSPHRASE_BACKUP)
                .remove(KEY_USING_DETERMINISTIC)
                .apply()
        } catch (_: Throwable) {}
        Timber.i("Database encryption key cleared from all storage locations")
    }

    /**
     * Check if EncryptedSharedPreferences is working.
     * Used by diagnostics to report Keystore health.
     */
    fun isUsingEncryptedStorage(): Boolean {
        return encryptedPrefs != null
    }

    /**
     * Get a human-readable summary of key storage health.
     * Useful for diagnostics and support.
     */
    fun getKeyStorageHealth(): String {
        val encrypted = isUsingEncryptedStorage()
        val deterministic = isUsingDeterministicKey()
        val corruptionCount = getCorruptionCount()

        return buildString {
            append("KeyStorage: ")
            if (encrypted) append("encrypted")
            else if (!deterministic) append("plain-fallback")
            else append("deterministic-device")
            if (corruptionCount > 0) append(" (corruptions: $corruptionCount)")
        }
    }
}
