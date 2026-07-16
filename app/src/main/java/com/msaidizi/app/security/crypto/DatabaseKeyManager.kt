package com.msaidizi.app.security.crypto

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
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
 */
@Singleton
class DatabaseKeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_FILE = "msaidizi_db_key_prefs"
        private const val KEY_DB_PASSPHRASE = "db_encryption_passphrase"
        private const val PASSPHRASE_LENGTH = 32 // 256-bit key
    }

    private val prefs by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            PREFS_FILE,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Get or create the database encryption passphrase.
     * Returns 32 bytes of cryptographically secure random data.
     * On first call, generates and securely stores the key.
     */
    fun getPassphrase(): ByteArray {
        val stored = prefs.getString(KEY_DB_PASSPHRASE, null)
        if (stored != null) {
            return Base64.decode(stored, Base64.NO_WRAP)
        }

        // Generate new 256-bit passphrase
        val passphrase = ByteArray(PASSPHRASE_LENGTH)
        SecureRandom().nextBytes(passphrase)

        // Store as Base64 in EncryptedSharedPreferences
        prefs.edit()
            .putString(KEY_DB_PASSPHRASE, Base64.encodeToString(passphrase, Base64.NO_WRAP))
            .apply()

        Timber.i("Generated new database encryption key (%d bytes)", PASSPHRASE_LENGTH)
        return passphrase
    }

    /**
     * Clear the stored passphrase (called on logout).
     * The encrypted database will be inaccessible after this.
     * A new database with a new key will be created on next login.
     */
    fun clearKey() {
        prefs.edit().remove(KEY_DB_PASSPHRASE).apply()
        Timber.i("Database encryption key cleared")
    }
}
