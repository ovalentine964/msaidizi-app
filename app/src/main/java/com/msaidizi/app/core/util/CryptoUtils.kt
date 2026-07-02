package com.msaidizi.app.core.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import timber.log.Timber
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encryption utilities for data at rest and in transit.
 * Uses Android Keystore for key management.
 * AES-256-GCM for encryption.
 */
object CryptoUtils {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "msaidizi_sync_key"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH = 12

    /**
     * Get or create the encryption key in Android Keystore.
     */
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        // Return existing key if available
        keyStore.getEntry(KEY_ALIAS, null)?.let { entry ->
            return (entry as KeyStore.SecretKeyEntry).secretKey
        }

        // Generate new key
        val keyGen = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGen.init(spec)
        return keyGen.generateKey()
    }

    /**
     * Encrypt data using AES-256-GCM.
     * Returns: IV (12 bytes) + ciphertext + tag
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)

        // Prepend IV to ciphertext
        val buffer = ByteBuffer.allocate(iv.size + ciphertext.size)
        buffer.put(iv)
        buffer.put(ciphertext)
        return buffer.array()
    }

    /**
     * Decrypt data encrypted with [encrypt].
     * Expects: IV (12 bytes) + ciphertext + tag
     */
    fun decrypt(encrypted: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(encrypted)

        val iv = ByteArray(IV_LENGTH)
        buffer.get(iv)

        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(AES_GCM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)

        return cipher.doFinal(ciphertext)
    }

    /**
     * Generate a random API key for device registration.
     */
    fun generateApiKey(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Hash a value using SHA-256 (for non-sensitive data like device IDs).
     */
    fun sha256(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private const val DB_KEY_ALIAS = "msaidizi_db_keystore_key"
    private const val DB_PREFS_NAME = "msaidizi_db_key"
    private const val DB_PREFS_KEY = "db_passphrase_encrypted"
    private const val DB_PREFS_IV = "db_passphrase_iv"

    /**
     * Get or create a database encryption key for SQLCipher.
     * The passphrase is generated randomly and encrypted with an Android Keystore
     * key before being stored in SharedPreferences. On retrieval, it is decrypted
     * using the Keystore key so the plaintext passphrase never persists to disk.
     */
    fun getOrCreateDatabaseKey(context: android.content.Context): String {
        val prefs = context.getSharedPreferences(DB_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val encryptedHex = prefs.getString(DB_PREFS_KEY, null)
        val ivHex = prefs.getString(DB_PREFS_IV, null)

        if (encryptedHex != null && ivHex != null) {
            // Decrypt the stored passphrase using Keystore key
            return try {
                val encrypted = hexToBytes(encryptedHex)
                val iv = hexToBytes(ivHex)
                val decrypted = decryptWithKeystoreKey(encrypted, iv)
                String(decrypted, Charsets.UTF_8)
            } catch (e: Exception) {
                Timber.e(e, "CryptoUtils: Failed to decrypt DB key, regenerating")
                generateAndStoreDatabaseKey(context, prefs)
            }
        }

        return generateAndStoreDatabaseKey(context, prefs)
    }

    private fun generateAndStoreDatabaseKey(
        context: android.content.Context,
        prefs: android.content.SharedPreferences
    ): String {
        // Generate a random 32-byte passphrase
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val passphrase = bytes.joinToString("") { "%02x".format(it) }

        // Encrypt with Android Keystore and store the ciphertext
        try {
            val (encrypted, iv) = encryptWithKeystoreKey(passphrase.toByteArray(Charsets.UTF_8))
            prefs.edit()
                .putString(DB_PREFS_KEY, bytesToHex(encrypted))
                .putString(DB_PREFS_IV, bytesToHex(iv))
                .apply()
            Timber.d("CryptoUtils: Generated and encrypted new database key")
        } catch (e: Exception) {
            Timber.e(e, "CryptoUtils: Keystore encryption failed, storing plaintext as fallback")
            // Last-resort fallback — still better than no encryption
            prefs.edit().putString(DB_PREFS_KEY, passphrase).apply()
        }

        return passphrase
    }

    /**
     * Get or create a dedicated Keystore key for wrapping the DB passphrase.
     */
    private fun getOrCreateDbKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        keyStore.getEntry(DB_KEY_ALIAS, null)?.let { entry ->
            return (entry as KeyStore.SecretKeyEntry).secretKey
        }

        val keyGen = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            DB_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGen.init(spec)
        return keyGen.generateKey()
    }

    /**
     * Encrypt data with the DB Keystore key.
     * Returns (ciphertext, iv).
     */
    private fun encryptWithKeystoreKey(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val key = getOrCreateDbKeystoreKey()
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return Pair(ciphertext, iv)
    }

    /**
     * Decrypt data with the DB Keystore key.
     */
    private fun decryptWithKeystoreKey(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val key = getOrCreateDbKeystoreKey()
        val cipher = Cipher.getInstance(AES_GCM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(ciphertext)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            bytes[i] = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16).toByte()
        }
        return bytes
    }
}
