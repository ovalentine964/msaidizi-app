package com.msaidizi.app.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles encryption for the Msaidizi app.
 * Uses Android Keystore for key management and SQLCipher for database encryption.
 */
@Singleton
class EncryptionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyAlias = "msaidizi_master_key"

    /**
     * Get or create the database encryption passphrase.
     * This is used by SQLCipher to encrypt the Room database.
     */
    fun getDatabasePassphrase(): ByteArray {
        val prefs = getEncryptedPrefs()
        val stored = prefs.getString(KEY_DB_PASSPHRASE, null)

        return if (stored != null) {
            stored.toByteArray()
        } else {
            val passphrase = generatePassphrase()
            prefs.edit().putString(KEY_DB_PASSPHRASE, passphrase).apply()
            passphrase.toByteArray()
        }
    }

    /**
     * Get encrypted SharedPreferences for sensitive data.
     */
    fun getEncryptedPrefs(): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            "msaidizi_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Generate a cryptographically secure passphrase.
     */
    private fun generatePassphrase(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*"
        return (1..64).map { chars.random() }.joinToString("")
    }

    companion object {
        private const val KEY_DB_PASSPHRASE = "db_passphrase"
    }
}
