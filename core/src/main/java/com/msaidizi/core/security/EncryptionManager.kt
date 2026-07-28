package com.msaidizi.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Handles encryption for the Msaidizi app.
 * Uses Android Keystore for key management and SQLCipher for database encryption.
 *
 * Security fixes:
 * - SecureRandom replaces kotlin.random for all cryptographic operations
 * - Proper AES-256-GCM encryption/decryption via Android Keystore
 * - Authenticated encryption with associated data (AEAD)
 */
@Singleton
class EncryptionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyAlias = "msaidizi_master_key"
    private val secureRandom = SecureRandom()

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
     * Generate a cryptographically secure passphrase using SecureRandom.
     * Replaced kotlin.random with java.security.SecureRandom.
     */
    private fun generatePassphrase(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*"
        val bytes = ByteArray(64)
        secureRandom.nextBytes(bytes)
        return bytes.map { byte -> chars[Math.abs(byte.toInt() % chars.length)] }.joinToString("")
    }

    /**
     * Encrypt arbitrary data using Android Keystore AES-256-GCM.
     * Returns IV (12 bytes) prepended to ciphertext.
     */
    fun encryptData(plaintext: ByteArray): ByteArray {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        // Prepend IV to ciphertext
        return iv + ciphertext
    }

    /**
     * Decrypt data that was encrypted with encryptData().
     * Expects IV (12 bytes) prepended to ciphertext.
     */
    fun decryptData(encryptedPayload: ByteArray): ByteArray {
        val secretKey = getOrCreateSecretKey()
        val iv = encryptedPayload.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = encryptedPayload.copyOfRange(GCM_IV_LENGTH, encryptedPayload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(ciphertext)
    }

    /**
     * Encrypt a string and return Base64-encoded result.
     */
    fun encryptString(plaintext: String): String {
        val encrypted = encryptData(plaintext.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
    }

    /**
     * Decrypt a Base64-encoded encrypted string.
     */
    fun decryptString(encryptedBase64: String): String {
        val encrypted = android.util.Base64.decode(encryptedBase64, android.util.Base64.NO_WRAP)
        val decrypted = decryptData(encrypted)
        return String(decrypted, Charsets.UTF_8)
    }

    /**
     * Generate cryptographically secure random bytes.
     */
    fun generateSecureBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        // Return existing key if present
        keyStore.getEntry(keyAlias, null)?.let { entry ->
            return (entry as KeyStore.SecretKeyEntry).secretKey
        }

        // Generate new key
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false) // Set true if biometric required for each use
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val KEY_DB_PASSPHRASE = "db_passphrase"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }
}
