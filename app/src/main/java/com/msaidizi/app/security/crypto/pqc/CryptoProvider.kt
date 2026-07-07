package com.msaidizi.app.security.crypto.pqc

/**
 * Algorithm-agnostic cryptography provider interface.
 *
 * This is the core of crypto-agility: all cryptographic operations go through
 * this interface, allowing algorithm swaps without changing calling code.
 *
 * Designed for post-quantum migration — implementations can switch between
 * classical (AES-256-GCM, RSA, ECDSA) and post-quantum (ML-KEM, ML-DSA)
 * algorithms transparently.
 *
 * Per NIST FIPS 203 (ML-KEM) and FIPS 204 (ML-DSA), and White House
 * Executive Order 14412 (June 2026) mandating PQC migration by 2030.
 */
interface CryptoProvider {

    /** Unique identifier for this provider (e.g., "AES-256-GCM", "ML-KEM-768") */
    val algorithmId: String

    /** Whether this algorithm is post-quantum resistant */
    val isPostQuantum: Boolean

    /** Security level (1-5, matching NIST levels) */
    val securityLevel: Int

    /**
     * Encrypt data.
     * @param plaintext Data to encrypt
     * @param key Encryption key (algorithm-specific format)
     * @return Ciphertext with any algorithm-specific metadata (IV, nonce, tag)
     */
    fun encrypt(plaintext: ByteArray, key: ByteArray): ByteArray

    /**
     * Decrypt data.
     * @param ciphertext Data to decrypt (includes algorithm metadata)
     * @param key Decryption key
     * @return Original plaintext
     */
    fun decrypt(ciphertext: ByteArray, key: ByteArray): ByteArray

    /**
     * Generate a key pair (for asymmetric algorithms) or key (for symmetric).
     * @return KeyPair containing public and private keys, or symmetric key in both fields
     */
    fun generateKeyPair(): CryptoKeyPair

    /**
     * Sign data (asymmetric algorithms only).
     * @param data Data to sign
     * @param privateKey Signing key
     * @return Signature bytes
     * @throws UnsupportedOperationException if algorithm doesn't support signing
     */
    fun sign(data: ByteArray, privateKey: ByteArray): ByteArray

    /**
     * Verify a signature.
     * @param data Original data
     * @param signature Signature to verify
     * @param publicKey Verification key
     * @return true if signature is valid
     */
    fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean
}

/**
 * A cryptographic key pair.
 * For symmetric algorithms, both fields contain the same key.
 */
data class CryptoKeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
    val algorithmId: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CryptoKeyPair) return false
        return algorithmId == other.algorithmId &&
                publicKey.contentEquals(other.publicKey) &&
                privateKey.contentEquals(other.privateKey)
    }

    override fun hashCode(): Int {
        var result = algorithmId.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + privateKey.contentHashCode()
        return result
    }
}

/**
 * Result of a key encapsulation mechanism (KEM) operation.
 * Used by ML-KEM (Kyber) and hybrid key exchange.
 */
data class EncapsulatedKey(
    val ciphertext: ByteArray,  // Send to peer
    val sharedSecret: ByteArray, // Derived shared secret
    val algorithmId: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncapsulatedKey) return false
        return algorithmId == other.algorithmId &&
                ciphertext.contentEquals(other.ciphertext) &&
                sharedSecret.contentEquals(other.sharedSecret)
    }

    override fun hashCode(): Int {
        var result = algorithmId.hashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + sharedSecret.contentHashCode()
        return result
    }
}
