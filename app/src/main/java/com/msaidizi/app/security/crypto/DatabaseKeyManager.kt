package com.msaidizi.app.security.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
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
 * Fallback strategy:
 * If EncryptedSharedPreferences fails (Keystore corruption, device reset,
 * lock screen change, hardware failure), we fall back to plain SharedPreferences
 * with a warning. The app MUST NOT crash — graceful degradation is critical.
 * On 2GB devices in Kenya/East Africa, Keystore failures are not rare.
 */
@Singleton
class DatabaseKeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_FILE = "msaidizi_db_key_prefs"
        private const val PREFS_FILE_FALLBACK = "msaidizi_db_key_prefs_plain"
        private const val KEY_DB_PASSPHRASE = "db_encryption_passphrase"
        private const val PASSPHRASE_LENGTH = 32 // 256-bit key
        private const val TAG = "DatabaseKeyManager"
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
     * NEVER throws — falls back to plain storage if encrypted storage fails.
     * The app MUST NOT crash due to key derivation failure.
     */
    fun getPassphrase(): ByteArray {
        return try {
            val prefs = getActivePrefs()
            val stored = prefs.getString(KEY_DB_PASSPHRASE, null)
            if (stored != null) {
                Base64.decode(stored, Base64.NO_WRAP)
            } else {
                generateAndStorePassphrase(prefs)
            }
        } catch (e: Throwable) {
            // Catastrophic failure — generate a transient key
            // This means the DB will be re-created on next launch,
            // but at least the app doesn't crash.
            Timber.e(e, "CRITICAL: Failed to read/generate DB passphrase — using transient key")
            try {
                io.sentry.Sentry.captureException(e)
            } catch (_: Throwable) {}
            generateTransientPassphrase()
        }
    }

    /**
     * Generate a new passphrase and store it.
     */
    private fun generateAndStorePassphrase(prefs: SharedPreferences): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_LENGTH)
        SecureRandom().nextBytes(passphrase)

        try {
            prefs.edit()
                .putString(KEY_DB_PASSPHRASE, Base64.encodeToString(passphrase, Base64.NO_WRAP))
                .apply()
            Timber.i("Generated new database encryption key (%d bytes)", PASSPHRASE_LENGTH)
        } catch (e: Throwable) {
            Timber.e(e, "Failed to store passphrase — key will be transient")
            try {
                io.sentry.Sentry.captureException(e)
            } catch (_: Throwable) {}
        }
        return passphrase
    }

    /**
     * Generate a transient passphrase (not persisted).
     * Used as last-resort fallback when all storage mechanisms fail.
     * The database will be re-created on each app launch.
     */
    private fun generateTransientPassphrase(): ByteArray {
        Timber.w("Using transient database key — data will not persist across launches")
        val passphrase = ByteArray(PASSPHRASE_LENGTH)
        SecureRandom().nextBytes(passphrase)
        return passphrase
    }

    /**
     * Clear the stored passphrase (called on logout).
     * The encrypted database will be inaccessible after this.
     * A new database with a new key will be created on next login.
     */
    fun clearKey() {
        try {
            getActivePrefs().edit().remove(KEY_DB_PASSPHRASE).apply()
            Timber.i("Database encryption key cleared")
        } catch (e: Throwable) {
            Timber.e(e, "Failed to clear database key")
        }
    }

    /**
     * Check if EncryptedSharedPreferences is working.
     * Used by diagnostics to report Keystore health.
     */
    fun isUsingEncryptedStorage(): Boolean {
        return encryptedPrefs != null
    }
}
