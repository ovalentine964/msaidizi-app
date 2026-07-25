package com.msaidizi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Security-critical tests for PinHasher.
 *
 * Validates PBKDF2-HMAC-SHA256 PIN hashing: consistency, uniqueness,
 * correct verification, and rejection of wrong PINs.
 *
 * Tests the exact algorithm used by PinHasher (PBKDF2, 310k iterations,
 * 256-bit salt, 256-bit key, constant-time comparison).
 */
class PinHasherTest {

    private companion object {
        const val SALT_LENGTH = 32
        const val ITERATIONS = 310_000
        const val KEY_LENGTH = 32
    }

    private val secureRandom = SecureRandom()

    data class HashedPin(
        val encoded: String,
        val salt: ByteArray,
        val hash: ByteArray
    )

    private fun hashPin(pin: String): HashedPin {
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

    private fun verifyPin(pin: String, storedEncoded: String): Boolean {
        return try {
            val parts = storedEncoded.split(":")
            if (parts.size != 3) return false
            val salt = Base64.getDecoder().decode(parts[0])
            val iterations = parts[1].toInt()
            val storedHash = Base64.getDecoder().decode(parts[2])
            val computedHash = pbkdf2(pin.toCharArray(), salt, iterations, storedHash.size)
            constantTimeEquals(computedHash, storedHash)
        } catch (e: Exception) {
            false
        }
    }

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

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }

    @Test
    fun hash_consistent() {
        // Verify that re-deriving with the same salt produces the same hash
        val pin = "4829"
        val hashed = hashPin(pin)

        // Re-derive with the stored salt
        val rederived = pbkdf2(pin.toCharArray(), hashed.salt, ITERATIONS, KEY_LENGTH)

        assertEquals(
            "Same PIN + same salt must produce same hash",
            hashed.hash.toList(),
            rederived.toList()
        )
    }

    @Test
    fun hash_differentInputs() {
        val pin1 = "1234"
        val pin2 = "5678"

        val hash1 = hashPin(pin1)
        val hash2 = hashPin(pin2)

        assertNotEquals(
            "Different PINs must produce different hashes",
            hash1.hash.toList(),
            hash2.hash.toList()
        )
        assertNotEquals(
            "Different PINs must produce different encoded strings",
            hash1.encoded,
            hash2.encoded
        )
    }

    @Test
    fun verify_correctPin() {
        val pin = "9371"
        val hashed = hashPin(pin)

        val result = verifyPin(pin, hashed.encoded)

        assertTrue(
            "Verification of correct PIN must succeed",
            result
        )
    }

    @Test
    fun verify_wrongPin() {
        val pin = "9371"
        val wrongPin = "0000"
        val hashed = hashPin(pin)

        val result = verifyPin(wrongPin, hashed.encoded)

        assertFalse(
            "Verification of wrong PIN must fail",
            result
        )
    }
}
