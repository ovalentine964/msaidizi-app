package com.msaidizi.app.security.crypto

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted local storage for sensitive data.
 *
 * Per SECURITY_ARCHITECTURE.md Section 4.3:
 * - Cached transactions encrypted with per-user key
 * - Voice recordings encrypted with AES-256-GCM, auto-delete after processing
 * - Downloaded statements encrypted
 * - Data classified by tier (T1 Restricted, T2 Confidential)
 */
@Singleton
class EncryptedStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyManager: KeyManager
) {
    companion object {
        private const val ENCRYPTED_DIR = "encrypted_store"
    }

    private val encryptedDir: File by lazy {
        File(context.filesDir, ENCRYPTED_DIR).also { it.mkdirs() }
    }

    /**
     * Store encrypted data for a given key.
     * Data is encrypted with AES-256-GCM using Android Keystore.
     * Includes HMAC integrity verification on the stored file.
     */
    fun put(key: String, value: ByteArray, keyAlias: String = KeyManager.KEY_ALIAS_STORAGE) {
        try {
            val encrypted = keyManager.encrypt(value, keyAlias)
            val file = File(encryptedDir, sanitizeFilename(key))

            // Compute HMAC over the ciphertext for integrity verification
            val hmac = computeHmac(encrypted)
            val payload = hmac + encrypted // [32-byte HMAC][encrypted data]

            file.writeBytes(payload)
            Timber.d("Encrypted storage: wrote %d bytes for key '%s'", payload.size, key)
        } catch (e: Exception) {
            Timber.e(e, "Failed to write encrypted storage for key '%s'", key)
            throw SecurityException("Failed to store encrypted data", e)
        }
    }

    /**
     * Retrieve and decrypt data for a given key.
     * Verifies HMAC integrity before decryption.
     */
    fun get(key: String, keyAlias: String = KeyManager.KEY_ALIAS_STORAGE): ByteArray? {
        val file = File(encryptedDir, sanitizeFilename(key))
        if (!file.exists()) return null

        return try {
            val payload = file.readBytes()
            if (payload.size < 33) { // HMAC(32) + min ciphertext
                Timber.e("Encrypted storage: file too small for key '%s'", key)
                return null
            }

            val storedHmac = payload.copyOfRange(0, 32)
            val encrypted = payload.copyOfRange(32, payload.size)

            // Verify HMAC integrity (constant-time comparison)
            val computedHmac = computeHmac(encrypted)
            if (!constantTimeEquals(storedHmac, computedHmac)) {
                Timber.e("Encrypted storage: HMAC integrity check FAILED for key '%s' — possible tampering", key)
                return null
            }

            keyManager.decrypt(encrypted, keyAlias)
        } catch (e: Exception) {
            Timber.e(e, "Failed to read encrypted storage for key '%s'", key)
            null
        }
    }

    /**
     * Compute HMAC-SHA256 over data using a storage-specific key.
     * Uses a different key from encryption to prevent cross-protocol attacks.
     */
    private fun computeHmac(data: ByteArray): ByteArray {
        val hmacKey = keyManager.ensureKey("angavu_hmac_storage")
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(hmacKey)
        return mac.doFinal(data)
    }

    /**
     * Constant-time byte array comparison to prevent timing attacks.
     */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }

    /**
     * Store a string value (encrypted).
     */
    fun putString(key: String, value: String, keyAlias: String = KeyManager.KEY_ALIAS_STORAGE) {
        put(key, value.toByteArray(Charsets.UTF_8), keyAlias)
    }

    /**
     * Retrieve and decrypt a string value.
     */
    fun getString(key: String, keyAlias: String = KeyManager.KEY_ALIAS_STORAGE): String? {
        return get(key, keyAlias)?.let { String(it, Charsets.UTF_8) }
    }

    /**
     * Delete an encrypted entry.
     */
    fun remove(key: String) {
        val file = File(encryptedDir, sanitizeFilename(key))
        if (file.exists()) {
            // Secure delete: overwrite with random data before deleting
            val random = java.security.SecureRandom()
            val randomData = ByteArray(file.length().toInt().coerceAtLeast(1))
            random.nextBytes(randomData)
            file.writeBytes(randomData)
            file.delete()
            Timber.d("Encrypted storage: securely deleted key '%s'", key)
        }
    }

    /**
     * Check if a key exists.
     */
    fun contains(key: String): Boolean {
        return File(encryptedDir, sanitizeFilename(key)).exists()
    }

    /**
     * Clear all encrypted storage (e.g., on logout).
     */
    fun clearAll() {
        encryptedDir.listFiles()?.forEach { file ->
            // Secure delete each file
            val random = java.security.SecureRandom()
            val randomData = ByteArray(file.length().toInt().coerceAtLeast(1))
            random.nextBytes(randomData)
            file.writeBytes(randomData)
            file.delete()
        }
        Timber.i("Encrypted storage: all entries cleared")
    }

    /**
     * Sanitize key for use as filename (prevent path traversal).
     */
    private fun sanitizeFilename(key: String): String {
        return key.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }
}
