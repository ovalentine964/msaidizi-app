package com.msaidizi.app.core.security

import android.content.SharedPreferences
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PIN hashing using PBKDF2-HMAC-SHA256 with per-user random salt.
 *
 * Stored format: Base64(salt:iterations:hash)
 * - salt: 256-bit random (32 bytes)
 * - iterations: 310,000 (OWASP 2023 recommendation for PBKDF2-HMAC-SHA256)
 * - hash: 256-bit derived key (32 bytes)
 *
 * This replaces the previous stub that accepted any 4+ digit PIN.
 */
@Singleton
class PinHasher @Inject constructor() {

    private val secureRandom = SecureRandom()

    /**
     * Hash a PIN with a fresh random salt.
     * Returns an encoded string suitable for storage.
     */
    fun hashPin(pin: String): HashedPin {
        val salt = ByteArray(SALT_LENGTH)
        secureRandom.nextBytes(salt)

        val hash = pbkdf2(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH)

        val encoded = buildString {
            append(Base64.getEncoder().encodeToString(salt))
            append(":")
            append(ITERATIONS)
            append(":")
            append(Base64.getEncoder().encodeToString(hash))
        }

        return HashedPin(encoded = encoded, salt = salt, hash = hash)
    }

    /**
     * Verify a PIN against a stored hash.
     * Uses constant-time comparison to prevent timing attacks.
     */
    fun verifyPin(pin: String, storedEncoded: String): Boolean {
        return try {
            val parts = storedEncoded.split(":")
            if (parts.size != 3) return false

            val salt = Base64.getDecoder().decode(parts[0])
            val iterations = parts[1].toInt()
            val storedHash = Base64.getDecoder().decode(parts[2])

            val computedHash = pbkdf2(pin.toCharArray(), salt, iterations, storedHash.size)

            // Constant-time comparison
            constantTimeEquals(computedHash, storedHash)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Derive key from PIN using PBKDF2-HMAC-SHA256.
     */
    private fun pbkdf2(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        keyLength: Int
    ): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, keyLength * 8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return key
    }

    /**
     * Constant-time byte array comparison to prevent timing side-channel attacks.
     */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }

    data class HashedPin(
        val encoded: String,
        val salt: ByteArray,
        val hash: ByteArray
    )

    companion object {
        private const val SALT_LENGTH = 32      // 256 bits
        private const val ITERATIONS = 310_000  // OWASP 2023 recommendation
        private const val KEY_LENGTH = 32       // 256 bits
    }
}
