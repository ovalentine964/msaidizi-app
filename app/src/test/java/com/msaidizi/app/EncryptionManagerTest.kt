package com.msaidizi.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Security-critical tests for EncryptionManager.
 *
 * Tests the core cryptographic primitives that EncryptionManager relies on:
 * AES-256-GCM encrypt/decrypt roundtrip, output uniqueness, and key randomness.
 *
 * Note: Full EncryptionManager tests require Android instrumentation (Keystore).
 * These unit tests validate the underlying crypto logic on JVM.
 */
class EncryptionManagerTest {

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH = 128
    }

    private fun generateAesKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        return keyGen.generateKey()
    }

    private fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    private fun decrypt(key: SecretKey, payload: ByteArray): ByteArray {
        val iv = payload.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = payload.copyOfRange(GCM_IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(ciphertext)
    }

    @Test
    fun encryptDecrypt_roundtrip() {
        val key = generateAesKey()
        val original = "Msaidizi secure data 🔐".toByteArray(Charsets.UTF_8)

        val encrypted = encrypt(key, original)
        val decrypted = decrypt(key, encrypted)

        assertArrayEquals(
            "Decrypted output must match original plaintext",
            original,
            decrypted
        )
    }

    @Test
    fun encrypt_differentInputs() {
        val key = generateAesKey()
        val input1 = "Transaction: KSh 500 from John".toByteArray(Charsets.UTF_8)
        val input2 = "Transaction: KSh 1200 from Jane".toByteArray(Charsets.UTF_8)

        val encrypted1 = encrypt(key, input1)
        val encrypted2 = encrypt(key, input2)

        assertNotEquals(
            "Different plaintexts must produce different ciphertexts",
            encrypted1.toList(),
            encrypted2.toList()
        )
    }

    @Test
    fun keyGeneration_isRandom() {
        val key1 = generateAesKey()
        val key2 = generateAesKey()

        assertNotEquals(
            "Two independently generated keys must not be equal",
            key1.encoded.toList(),
            key2.encoded.toList()
        )

        assertNotNull("Key bytes must not be null", key1.encoded)
        assertNotNull("Key bytes must not be null", key2.encoded)
        assertNotEquals("Key material must differ", key1.encoded, key2.encoded)
    }
}
