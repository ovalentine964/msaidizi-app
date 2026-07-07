package com.msaidizi.app.security.crypto.pqc

import timber.log.Timber
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hybrid key exchange combining classical (X25519/ECDHE) with post-quantum (ML-KEM).
 *
 * This is the recommended approach during the PQC transition period (2026–2030+):
 * - If ML-KEM is broken, classical ECDHE still protects the connection
 * - If classical ECDHE is broken by quantum computers, ML-KEM still protects it
 * - The combined shared secret is as strong as the stronger of the two
 *
 * Follows the approach used by Cloudflare, Google Chrome, and Meta:
 *   shared_secret = HKDF(ECDHE_secret || ML-KEM_secret)
 *
 * @see <a href="https://blog.cloudflare.com/post-quantum-for-all/">Cloudflare PQ TLS</a>
 */
class HybridKeyExchange(
    private val mlKemProvider: MlKemProvider = MlKemProvider(MlKemParameterSet.ML_KEM_768)
) {
    companion object {
        private const val HKDF_ALGORITHM = "HmacSHA256"
        private const val HYBRID_ALGORITHM_ID = "X25519+ML-KEM-768"
        private const val HYBRID_SHARED_SECRET_SIZE = 32
    }

    /**
     * Result of a hybrid key exchange initiation.
     */
    data class HybridInitResult(
        /** ECDHE public key to send to peer */
        val ecdhPublicKey: ByteArray,
        /** ML-KEM ciphertext to send to peer */
        val mlKemCiphertext: ByteArray,
        /** Combined shared secret for symmetric encryption */
        val sharedSecret: ByteArray,
        val algorithmId: String = HYBRID_ALGORITHM_ID
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HybridInitResult) return false
            return algorithmId == other.algorithmId &&
                    ecdhPublicKey.contentEquals(other.ecdhPublicKey) &&
                    mlKemCiphertext.contentEquals(other.mlKemCiphertext) &&
                    sharedSecret.contentEquals(other.sharedSecret)
        }

        override fun hashCode(): Int {
            var result = algorithmId.hashCode()
            result = 31 * result + ecdhPublicKey.contentHashCode()
            result = 31 * result + mlKemCiphertext.contentHashCode()
            result = 31 * result + sharedSecret.contentHashCode()
            return result
        }
    }

    /**
     * Initiate a hybrid key exchange (client side).
     *
     * Generates both ECDHE and ML-KEM key material, then combines them
     * into a single shared secret using HKDF.
     *
     * @param peerMlKemPublicKey The server's ML-KEM public key (from key registration)
     * @return HybridInitResult containing public material and combined shared secret
     */
    fun initiate(peerMlKemPublicKey: ByteArray): HybridInitResult {
        // Step 1: Generate ECDHE ephemeral key pair (X25519)
        val ecdhKeyPair = generateX25519KeyPair()

        // Step 2: ML-KEM encapsulation
        val mlKemResult = mlKemProvider.encapsulate(peerMlKemPublicKey)

        // Step 3: Combine using HKDF
        val combinedSecret = combineSecrets(
            ecdhSecret = deriveEcdhSecret(ecdhKeyPair.privateKey, peerMlKemPublicKey), // stub
            mlKemSecret = mlKemResult.sharedSecret
        )

        Timber.i("Hybrid key exchange initiated: %s", HYBRID_ALGORITHM_ID)

        return HybridInitResult(
            ecdhPublicKey = ecdhKeyPair.publicKey,
            mlKemCiphertext = mlKemResult.ciphertext,
            sharedSecret = combinedSecret
        )
    }

    /**
     * Complete a hybrid key exchange (server side).
     *
     * @param peerEcdhPublicKey Client's ECDHE public key
     * @param mlKemCiphertext Client's ML-KEM ciphertext
     * @param mlKemPrivateKey Server's ML-KEM private key
     * @return Combined shared secret
     */
    fun complete(
        peerEcdhPublicKey: ByteArray,
        mlKemCiphertext: ByteArray,
        mlKemPrivateKey: ByteArray
    ): ByteArray {
        // Step 1: ECDHE shared secret
        val ecdhSecret = deriveEcdhSecret(mlKemPrivateKey, peerEcdhPublicKey) // stub

        // Step 2: ML-KEM decapsulation
        val mlKemSecret = mlKemProvider.decapsulate(mlKemCiphertext, mlKemPrivateKey)

        // Step 3: Combine using HKDF
        val combinedSecret = combineSecrets(ecdhSecret, mlKemSecret)

        Timber.i("Hybrid key exchange completed: %s", HYBRID_ALGORITHM_ID)
        return combinedSecret
    }

    /**
     * Combine ECDHE and ML-KEM shared secrets using HKDF.
     *
     * The combination ensures that the final secret is secure even if
     * one of the two algorithms is broken.
     */
    private fun combineSecrets(ecdhSecret: ByteArray, mlKemSecret: ByteArray): ByteArray {
        // Concatenate as input key material
        val combinedInput = ByteArray(ecdhSecret.size + mlKemSecret.size)
        System.arraycopy(ecdhSecret, 0, combinedInput, 0, ecdhSecret.size)
        System.arraycopy(mlKemSecret, 0, combinedInput, ecdhSecret.size, mlKemSecret.size)

        // HKDF extract-and-expand
        // Salt: algorithm identifier (domain separation)
        val salt = HYBRID_ALGORITHM_ID.toByteArray(Charsets.UTF_8)

        // Extract: PRK = HMAC-Hash(salt, IKM)
        val mac = javax.crypto.Mac.getInstance(HKDF_ALGORITHM)
        mac.init(SecretKeySpec(salt, HKDF_ALGORITHM))
        val prk = mac.doFinal(combinedInput)

        // Expand: OKM = HMAC-Hash(PRK, info || 0x01)
        mac.init(SecretKeySpec(prk, HKDF_ALGORITHM))
        mac.update(HYBRID_ALGORITHM_ID.toByteArray(Charsets.UTF_8))
        mac.update(0x01.toByte())
        val okm = mac.doFinal()

        // Truncate to desired size
        return okm.copyOf(HYBRID_SHARED_SECRET_SIZE)
    }

    /**
     * Generate an X25519 ephemeral key pair.
     *
     * TODO: Replace with java.security.KeyPairGenerator when Android adds
     * X25519 support, or use Bouncy Castle's X25519 implementation.
     */
    private fun generateX25519KeyPair(): CryptoKeyPair {
        // STUB: Use EC key pair as placeholder for X25519
        val keyGen = java.security.KeyPairGenerator.getInstance("EC")
        keyGen.initialize(256)
        val kp = keyGen.generateKeyPair()

        return CryptoKeyPair(
            publicKey = kp.public.encoded,
            privateKey = kp.private.encoded,
            algorithmId = "X25519"
        )
    }

    /**
     * Derive ECDH shared secret.
     *
     * TODO: Replace with actual X25519 shared secret derivation.
     */
    private fun deriveEcdhSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        // STUB: Hash-based placeholder
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(privateKey)
        digest.update(peerPublicKey)
        return digest.digest()
    }

    /**
     * Encrypt data using the hybrid shared secret (AES-256-GCM).
     */
    fun encryptWithSharedSecret(plaintext: ByteArray, sharedSecret: ByteArray): ByteArray {
        val keySpec = SecretKeySpec(sharedSecret, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)

        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)

        val buffer = java.nio.ByteBuffer.allocate(iv.size + ciphertext.size)
        buffer.put(iv)
        buffer.put(ciphertext)
        return buffer.array()
    }

    /**
     * Decrypt data using the hybrid shared secret (AES-256-GCM).
     */
    fun decryptWithSharedSecret(encrypted: ByteArray, sharedSecret: ByteArray): ByteArray {
        val buffer = java.nio.ByteBuffer.wrap(encrypted)

        val iv = ByteArray(12)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        val keySpec = SecretKeySpec(sharedSecret, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
        return cipher.doFinal(ciphertext)
    }
}
