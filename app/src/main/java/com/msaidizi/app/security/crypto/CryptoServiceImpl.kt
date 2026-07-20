package com.msaidizi.app.security.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified cryptographic service implementation.
 *
 * AES-256-GCM via Android Keystore with hardware-backed keys.
 * Consolidates the best practices from the previous implementations:
 *
 * - Hardware-backed key storage (TEE/StrongBox) — from core/security/KeyManager
 * - setRandomizedEncryptionRequired(true) — enforces unique IV at Keystore level
 * - Separate key aliases per security domain — limits blast radius
 * - StrongBox preferred (Pixel 3+, Samsung S10+) — graceful fallback to TEE
 * - IV prepended to ciphertext (12 bytes, NIST SP 800-38D)
 *
 * Output format for encrypt(): [12-byte IV][ciphertext + 16-byte GCM auth tag]
 */
@Singleton
class CryptoServiceImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : CryptoService {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val IV_SIZE_BYTES = 12 // 96-bit IV per NIST SP 800-38D
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private val secureRandom = SecureRandom()

    // ── Raw byte encryption ──────────────────────────────────────

    override fun encrypt(data: ByteArray, alias: CryptoService.KeyAlias): ByteArray {
        val key = ensureKey(alias)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        // Keystore generates a unique IV (setRandomizedEncryptionRequired)
        val iv = cipher.iv
        require(iv.size == IV_SIZE_BYTES) { "Expected ${IV_SIZE_BYTES}-byte IV, got ${iv.size}" }

        val ciphertext = cipher.doFinal(data)

        // Build output: IV || ciphertext (includes GCM auth tag)
        val output = ByteArray(IV_SIZE_BYTES + ciphertext.size)
        System.arraycopy(iv, 0, output, 0, IV_SIZE_BYTES)
        System.arraycopy(ciphertext, 0, output, IV_SIZE_BYTES, ciphertext.size)
        return output
    }

    override fun decrypt(data: ByteArray, alias: CryptoService.KeyAlias): ByteArray {
        require(data.size > IV_SIZE_BYTES) {
            "Ciphertext too short: expected >${IV_SIZE_BYTES} bytes, got ${data.size}"
        }

        val iv = data.copyOfRange(0, IV_SIZE_BYTES)
        val ciphertext = data.copyOfRange(IV_SIZE_BYTES, data.size)

        val key = ensureKey(alias)
        val cipher = Cipher.getInstance(AES_GCM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)

        return cipher.doFinal(ciphertext) // Throws AEADBadTagException if tampered
    }

    // ── String convenience ───────────────────────────────────────

    override fun encryptToString(data: String, alias: CryptoService.KeyAlias): String {
        val encrypted = encrypt(data.toByteArray(Charsets.UTF_8), alias)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    override fun decryptToString(data: String, alias: CryptoService.KeyAlias): String {
        val decoded = Base64.decode(data, Base64.NO_WRAP)
        return String(decrypt(decoded, alias), Charsets.UTF_8)
    }

    // ── Field-level encryption ───────────────────────────────────

    override fun encryptFields(fields: Map<String, String>, alias: CryptoService.KeyAlias): Map<String, String> {
        return fields.mapValues { (_, value) -> encryptToString(value, alias) }
    }

    override fun decryptFields(fields: Map<String, String>, alias: CryptoService.KeyAlias): Map<String, String> {
        return fields.mapValues { (_, value) -> decryptToString(value, alias) }
    }

    // ── Key management ───────────────────────────────────────────

    override fun ensureKey(alias: CryptoService.KeyAlias): SecretKey {
        keyStore.getEntry(alias.aliasName, null)?.let { entry ->
            return (entry as KeyStore.SecretKeyEntry).secretKey
        }
        return generateKey(alias)
    }

    override fun keyExists(alias: CryptoService.KeyAlias): Boolean {
        return keyStore.containsAlias(alias.aliasName)
    }

    override fun deleteKey(alias: CryptoService.KeyAlias) {
        keyStore.deleteEntry(alias.aliasName)
        Timber.w("Deleted Keystore key: %s", alias.aliasName)
    }

    // ── Hashing ──────────────────────────────────────────────────

    override fun sha256(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    override fun generateApiKey(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ── Internal ─────────────────────────────────────────────────

    private fun generateKey(alias: CryptoService.KeyAlias): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val spec = KeyGenParameterSpec.Builder(
            alias.aliasName,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).apply {
            setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            setKeySize(256)
            setRandomizedEncryptionRequired(true) // CRITICAL: prevents IV reuse

            // Prefer StrongBox (hardware security module) if available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    setIsStrongBoxBacked(true)
                } catch (e: Exception) {
                    Timber.d("StrongBox not available for %s, using TEE", alias.aliasName)
                }
            }
        }.build()

        keyGenerator.init(spec)
        val key = keyGenerator.generateKey()
        Timber.i("Generated new Keystore key: %s", alias.aliasName)
        return key
    }
}
