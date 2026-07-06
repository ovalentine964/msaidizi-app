package com.msaidizi.app.security.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure key management using Android Keystore.
 *
 * Per SECURITY_ARCHITECTURE.md Section 4.5:
 * - Hardware-backed key storage (TEE/StrongBox)
 * - Keys non-exportable
 * - Separate keys for different purposes (auth, sync, DB, biometric)
 * - Key rotation support
 */
@Singleton
class KeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val IV_LENGTH = 12

        // Key aliases for different purposes
        const val KEY_ALIAS_AUTH = "angavu_auth_key"
        const val KEY_ALIAS_SYNC = "angavu_sync_key"
        const val KEY_ALIAS_BIOMETRIC = "angavu_biometric_key"
        const val KEY_ALIAS_STORAGE = "angavu_storage_key"
        const val KEY_ALIAS_DB = "angavu_db_key"
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    /**
     * Get or create a key for a specific purpose.
     * Keys are hardware-backed and non-exportable.
     */
    fun getOrCreateKey(alias: String, requireBiometric: Boolean = false): SecretKey {
        // Return existing key
        keyStore.getEntry(alias, null)?.let { entry ->
            return (entry as KeyStore.SecretKeyEntry).secretKey
        }

        // Generate new key
        val keyGen = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val specBuilder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true) // Unique IV per encryption

        if (requireBiometric) {
            specBuilder
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationValidityDurationSeconds(-1) // Require auth for every use
        }

        // Prefer StrongBox if available (hardware security module)
        try {
            specBuilder.setIsStrongBoxBacked(true)
        } catch (e: Exception) {
            Timber.d("StrongBox not available, using TEE")
        }

        keyGen.init(specBuilder.build())
        val key = keyGen.generateKey()
        Timber.i("Generated new key: %s (biometric=%b)", alias, requireBiometric)
        return key
    }

    /**
     * Check if a key exists in the Keystore.
     */
    fun keyExists(alias: String): Boolean {
        return keyStore.containsAlias(alias)
    }

    /**
     * Delete a key from the Keystore.
     * WARNING: This is irreversible — data encrypted with this key will be lost.
     */
    fun deleteKey(alias: String) {
        keyStore.deleteEntry(alias)
        Timber.w("Deleted key: %s", alias)
    }

    /**
     * Encrypt data with a specific key.
     * Returns: IV (12 bytes) || ciphertext || GCM tag
     */
    fun encrypt(data: ByteArray, keyAlias: String): ByteArray {
        val key = getOrCreateKey(keyAlias)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv
        val ciphertext = cipher.doFinal(data)

        // Prepend IV to ciphertext
        val buffer = java.nio.ByteBuffer.allocate(iv.size + ciphertext.size)
        buffer.put(iv)
        buffer.put(ciphertext)
        return buffer.array()
    }

    /**
     * Decrypt data encrypted with [encrypt].
     * Expects: IV (12 bytes) || ciphertext || GCM tag
     */
    fun decrypt(encrypted: ByteArray, keyAlias: String): ByteArray {
        val buffer = java.nio.ByteBuffer.wrap(encrypted)

        val iv = ByteArray(IV_LENGTH)
        buffer.get(iv)

        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        val key = getOrCreateKey(keyAlias)
        val cipher = Cipher.getInstance(AES_GCM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)

        return cipher.doFinal(ciphertext)
    }

    /**
     * Encrypt a string (convenience method).
     */
    fun encryptString(plaintext: String, keyAlias: String): ByteArray {
        return encrypt(plaintext.toByteArray(Charsets.UTF_8), keyAlias)
    }

    /**
     * Decrypt to string (convenience method).
     */
    fun decryptToString(encrypted: ByteArray, keyAlias: String): String {
        return String(decrypt(encrypted, keyAlias), Charsets.UTF_8)
    }

    /**
     * Generate a cryptographically secure random byte array.
     */
    fun generateSecureRandom(length: Int): ByteArray {
        val random = SecureRandom()
        val bytes = ByteArray(length)
        random.nextBytes(bytes)
        return bytes
    }

    /**
     * Get a Cipher instance initialized for decryption with the biometric-bound key.
     * Used with BiometricPrompt.CryptoObject for biometric-bound crypto operations.
     */
    fun getCipherForBiometricDecrypt(encrypted: ByteArray, keyAlias: String): Cipher {
        val buffer = java.nio.ByteBuffer.wrap(encrypted)
        val iv = ByteArray(IV_LENGTH)
        buffer.get(iv)

        val key = getOrCreateKey(keyAlias, requireBiometric = true)
        val cipher = Cipher.getInstance(AES_GCM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher
    }
}
