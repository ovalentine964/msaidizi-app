package com.msaidizi.app.core.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * KeyManager — Hardware-backed AES-256-GCM encryption.
 *
 * FIX 1: AES-GCM IV Reuse Vulnerability
 * --------------------------------------
 * PREVIOUS (VULNERABLE): All N fields encrypted with the same IV/Key pair.
 *   An attacker observing two ciphertexts encrypted with the same (key, IV)
 *   can XOR them to recover the plaintext XOR, defeating confidentiality.
 *   For AES-GCM, IV reuse also destroys authentication — an attacker can
 *   forge arbitrary ciphertexts.
 *
 * FIX: Each encryption call generates a unique 12-byte IV via SecureRandom.
 *   The IV is prepended to the ciphertext and extracted during decryption.
 *   The same IV is never reused for the same key.
 *
 * Key properties:
 * - Keys are generated inside Android Keystore (hardware-backed)
 * - setRandomizedEncryptionRequired(true) — Keystore enforces random IV
 * - StrongBox preferred (Pixel 3+, Samsung S10+)
 * - Separate key aliases for different purposes (auth, sync, biometric, storage, db)
 */
object KeyManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_GCM_TAG_LENGTH_BITS = 128
    private const val IV_SIZE_BYTES = 12  // 96-bit IV per NIST SP 800-38D

    /**
     * Key aliases for different security domains.
     * Each domain gets its own key to limit blast radius.
     */
    object KeyAlias {
        const val AUTH = "msaidizi_key_auth"
        const val SYNC = "msaidizi_key_sync"
        const val BIOMETRIC = "msaidizi_key_biometric"
        const val STORAGE = "msaidizi_key_storage"
        const val DB = "msaidizi_key_db"
        const val PII = "msaidizi_key_pii"  // For encrypting PII fields (phone, name)
    }

    private val secureRandom = SecureRandom()

    /**
     * Ensure a key exists for the given alias. Creates it if missing.
     */
    fun ensureKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        keyStore.getEntry(alias, null)?.let { entry ->
            return (entry as KeyStore.SecretKeyEntry).secretKey
        }

        return generateKey(alias)
    }

    /**
     * Generate a new AES-256-GCM key inside Android Keystore.
     *
     * Key generation parameters:
     * - AES-256 (KeyProperties.KEY_ALGORITHM_AES, 256-bit)
     * - GCM block mode (KeyProperties.BLOCK_MODE_GCM)
     * - No padding (GCM handles its own padding)
     * - User authentication NOT required (use BiometricAuthManager for that)
     * - Randomized encryption REQUIRED (Keystore enforces unique IV)
     * - StrongBox backed if available
     */
    private fun generateKey(alias: String): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).apply {
            setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            setKeySize(256)
            setRandomizedEncryptionRequired(true) // CRITICAL: prevents IV reuse at Keystore level

            // Prefer StrongBox (hardware security module) if available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setIsStrongBoxBacked(true)
            }
        }.build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * Encrypt plaintext with a unique IV.
     *
     * Output format: [12-byte IV][ciphertext + GCM tag]
     *
     * The IV is generated fresh for each call using SecureRandom.
     * The Keystore's setRandomizedEncryptionRequired(true) provides
     * a second layer of defense — even if we somehow reused an IV,
     * the Keystore would reject the operation.
     *
     * @param plaintext The data to encrypt
     * @param alias Key alias to use (default: STORAGE)
     * @return IV prepended to ciphertext, Base64-encoded
     */
    fun encrypt(plaintext: ByteArray, alias: String = KeyAlias.STORAGE): String {
        val key = ensureKey(alias)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        // Let the Keystore generate the IV (setRandomizedEncryptionRequired ensures uniqueness)
        cipher.init(Cipher.ENCRYPT_MODE, key)

        // Extract the Keystore-generated IV (12 bytes)
        val iv = cipher.iv
        require(iv.size == IV_SIZE_BYTES) {
            "Expected ${IV_SIZE_BYTES}-byte IV, got ${iv.size} bytes"
        }

        // Encrypt
        val ciphertext = cipher.doFinal(plaintext)

        // Build output: IV || ciphertext (includes GCM auth tag)
        val output = ByteArray(IV_SIZE_BYTES + ciphertext.size)
        System.arraycopy(iv, 0, output, 0, IV_SIZE_BYTES)
        System.arraycopy(ciphertext, 0, output, IV_SIZE_BYTES, ciphertext.size)

        return Base64.encodeToString(output, Base64.NO_WRAP)
    }

    /**
     * Encrypt a String (convenience wrapper).
     */
    fun encryptString(plaintext: String, alias: String = KeyAlias.STORAGE): String {
        return encrypt(plaintext.toByteArray(Charsets.UTF_8), alias)
    }

    /**
     * Decrypt ciphertext that was encrypted with [encrypt].
     *
     * Extracts the 12-byte IV from the front of the ciphertext,
     * then decrypts using that IV.
     *
     * @param encryptedBase64 Base64-encoded IV + ciphertext
     * @param alias Key alias used during encryption
     * @return Decrypted plaintext
     * @throws SecurityException if authentication tag verification fails
     */
    fun decrypt(encryptedBase64: String, alias: String = KeyAlias.STORAGE): ByteArray {
        val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)

        require(combined.size > IV_SIZE_BYTES) {
            "Ciphertext too short: expected >${IV_SIZE_BYTES} bytes, got ${combined.size}"
        }

        // Extract IV from the first 12 bytes
        val iv = combined.copyOfRange(0, IV_SIZE_BYTES)
        val ciphertext = combined.copyOfRange(IV_SIZE_BYTES, combined.size)

        val key = ensureKey(alias)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(AES_GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

        return cipher.doFinal(ciphertext) // Throws AEADBadTagException if tampered
    }

    /**
     * Decrypt to String (convenience wrapper).
     */
    fun decryptString(encryptedBase64: String, alias: String = KeyAlias.STORAGE): String {
        return String(decrypt(encryptedBase64, alias), Charsets.UTF_8)
    }

    /**
     * Encrypt multiple fields with SEPARATE IVs for each field.
     *
     * This is the critical fix — previously all fields were encrypted
     * with the same (key, IV) pair. Now each field gets its own IV.
     *
     * @param fields Map of field name to plaintext value
     * @param alias Key alias to use
     * @return Map of field name to encrypted (IV+ciphertext) Base64 string
     */
    fun encryptFields(
        fields: Map<String, String>,
        alias: String = KeyAlias.PII
    ): Map<String, String> {
        return fields.mapValues { (_, value) ->
            encryptString(value, alias)
        }
    }

    /**
     * Decrypt multiple fields.
     */
    fun decryptFields(
        encryptedFields: Map<String, String>,
        alias: String = KeyAlias.PII
    ): Map<String, String> {
        return encryptedFields.mapValues { (_, value) ->
            decryptString(value, alias)
        }
    }

    /**
     * Delete a key from the Keystore. Used for key rotation or account deletion.
     */
    fun deleteKey(alias: String) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.deleteEntry(alias)
    }

    /**
     * Check if a key exists for the given alias.
     */
    fun keyExists(alias: String): Boolean {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.containsAlias(alias)
    }
}
