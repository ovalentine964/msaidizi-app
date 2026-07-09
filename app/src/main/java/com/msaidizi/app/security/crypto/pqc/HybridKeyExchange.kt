package com.msaidizi.app.security.crypto.pqc

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import timber.log.Timber
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hybrid key exchange combining classical X25519 with post-quantum ML-KEM-768.
 *
 * This is the recommended approach during the PQC transition period (2026–2030+):
 * - If ML-KEM is broken, classical X25519 still protects the connection
 * - If classical X25519 is broken by quantum computers, ML-KEM still protects it
 * - The combined shared secret is as strong as the stronger of the two
 *
 * Follows the approach used by Cloudflare, Google Chrome, and Meta:
 *   shared_secret = HKDF(X25519_secret || ML-KEM_secret)
 *
 * This is a REAL implementation using:
 * - Bouncy Castle X25519 for the classical component
 * - Bouncy Castle ML-KEM-768 for the post-quantum component
 * - HKDF-SHA256 for secure combination (RFC 5869)
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
        /** X25519 public key to send to peer */
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
     * Generates both X25519 and ML-KEM key material, then combines them
     * into a single shared secret using HKDF.
     *
     * @param peerMlKemPublicKey The server's ML-KEM public key (from key registration)
     * @return HybridInitResult containing public material and combined shared secret
     */
    fun initiate(
        peerX25519PublicKey: ByteArray,
        peerMlKemPublicKey: ByteArray
    ): HybridInitResult {
        // Step 1: Generate X25519 ephemeral key pair (real, via Bouncy Castle)
        val x25519KeyPair = generateX25519KeyPair()

        // Step 2: Compute X25519 shared secret (real DH)
        val x25519Secret = deriveX25519Secret(
            x25519KeyPair.privateKey,
            peerX25519PublicKey
        )

        // Step 3: ML-KEM encapsulation (real, via Bouncy Castle)
        val mlKemResult = mlKemProvider.encapsulate(peerMlKemPublicKey)

        // Step 4: Combine using HKDF
        val combinedSecret = combineSecrets(
            ecdhSecret = x25519Secret,
            mlKemSecret = mlKemResult.sharedSecret
        )

        Timber.i("Hybrid key exchange initiated: %s", HYBRID_ALGORITHM_ID)

        return HybridInitResult(
            ecdhPublicKey = x25519KeyPair.publicKey,
            mlKemCiphertext = mlKemResult.ciphertext,
            sharedSecret = combinedSecret
        )
    }

    /**
     * Complete a hybrid key exchange (server side).
     *
     * Recovers the same combined shared secret as the initiator,
     * using the server's ML-KEM private key and the client's X25519 public key.
     *
     * @param peerX25519PublicKey Client's X25519 public key
     * @param mlKemCiphertext Client's ML-KEM ciphertext
     * @param mlKemPrivateKey Server's ML-KEM private key
     * @return Combined shared secret
     */
    fun complete(
        peerX25519PublicKey: ByteArray,
        mlKemCiphertext: ByteArray,
        mlKemPrivateKey: ByteArray,
        x25519PrivateKey: ByteArray
    ): ByteArray {
        // Step 1: X25519 shared secret (real DH with our X25519 private key)
        val x25519Secret = deriveX25519Secret(x25519PrivateKey, peerX25519PublicKey)

        // Step 2: ML-KEM decapsulation (real, via Bouncy Castle)
        val mlKemSecret = mlKemProvider.decapsulate(mlKemCiphertext, mlKemPrivateKey)

        // Step 3: Combine using HKDF
        val combinedSecret = combineSecrets(x25519Secret, mlKemSecret)

        Timber.i("Hybrid key exchange completed: %s", HYBRID_ALGORITHM_ID)
        return combinedSecret
    }

    /**
     * Complete a hybrid key exchange with a real X25519 secret.
     *
     * This variant accepts a pre-computed X25519 shared secret for use
     * when the caller has already performed the X25519 agreement.
     *
     * @param x25519SharedSecret Pre-computed X25519 shared secret (32 bytes)
     * @param mlKemCiphertext Client's ML-KEM ciphertext
     * @param mlKemPrivateKey Server's ML-KEM private key
     * @return Combined shared secret
     */
    fun completeWithX25519Secret(
        x25519SharedSecret: ByteArray,
        mlKemCiphertext: ByteArray,
        mlKemPrivateKey: ByteArray
    ): ByteArray {
        val mlKemSecret = mlKemProvider.decapsulate(mlKemCiphertext, mlKemPrivateKey)
        return combineSecrets(x25519SharedSecret, mlKemSecret)
    }

    /**
     * Combine X25519 and ML-KEM shared secrets using HKDF (RFC 5869).
     *
     * The combination ensures that the final secret is secure even if
     * one of the two algorithms is broken.
     *
     * HKDF-Extract: PRK = HMAC-Hash(salt, IKM)
     * HKDF-Expand:  OKM = HMAC-Hash(PRK, info || 0x01)
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
        val mac = Mac.getInstance(HKDF_ALGORITHM)
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
     * Generate a real X25519 ephemeral key pair using Bouncy Castle.
     */
    private fun generateX25519KeyPair(): CryptoKeyPair {
        val keyPairGenerator = X25519KeyPairGenerator()
        keyPairGenerator.init(X25519KeyGenerationParameters(SecureRandom()))
        val keyPair = keyPairGenerator.generateKeyPair()

        val privateKey = keyPair.private as X25519PrivateKeyParameters
        val publicKey = keyPair.public as X25519PublicKeyParameters

        return CryptoKeyPair(
            publicKey = publicKey.encoded,
            privateKey = privateKey.encoded,
            algorithmId = "X25519"
        )
    }

    /**
     * Derive X25519 shared secret using Bouncy Castle.
     *
     * @param privateKeyBytes Our X25519 private key (32 bytes)
     * @param peerPublicKeyBytes Peer's X25519 public key (32 bytes)
     * @return 32-byte shared secret
     */
    private fun deriveX25519Secret(privateKeyBytes: ByteArray, peerPublicKeyBytes: ByteArray): ByteArray {
        val privateKey = X25519PrivateKeyParameters(privateKeyBytes, 0)
        val peerPublicKey = X25519PublicKeyParameters(peerPublicKeyBytes, 0)

        val agreement = X25519Agreement()
        agreement.init(privateKey)

        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(peerPublicKey, sharedSecret, 0)

        return sharedSecret
    }

    /**
     * Generate a real X25519 ephemeral key pair using Bouncy Castle.
     * Returns both public and private key bytes for the caller to use.
     */
    fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val kp = generateX25519KeyPair()
        return Pair(kp.publicKey, kp.privateKey)
    }

    /**
     * Encrypt data using the hybrid shared secret (AES-256-GCM).
     */
    fun encryptWithSharedSecret(plaintext: ByteArray, sharedSecret: ByteArray): ByteArray {
        val keySpec = SecretKeySpec(sharedSecret, "AES")
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec)

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
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, spec)
        return cipher.doFinal(ciphertext)
    }
}
