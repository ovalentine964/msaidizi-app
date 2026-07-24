package com.msaidizi.core.security.pqc

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.MLKEMKeyPairGenerator
import org.bouncycastle.crypto.params.MLKEMKeyGenerationParameters
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyPairGenerator
import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPublicKeyParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSASigner
import java.security.SecureRandom

/**
 * Post-Quantum Cryptography provider using Bouncy Castle.
 *
 * Implements:
 * - ML-KEM (FIPS 203): Key Encapsulation Mechanism for secure key exchange
 * - ML-DSA (FIPS 204): Digital Signature Algorithm for document signing
 *
 * These are NIST-standardized post-quantum algorithms, resistant to
 * attacks from quantum computers. Critical for long-term financial
 * data protection.
 *
 * ## Why PQC Matters for Msaidizi
 * Financial data stored today could be decrypted by quantum computers
 * in 10-15 years ("harvest now, decrypt later" attack). PQC ensures
 * worker financial data remains secure even against future threats.
 */
class PostQuantumCrypto {

    /**
     * Generate an ML-KEM key pair for key encapsulation.
     * Used for secure key exchange between app and backend.
     *
     * @param parameterSet ML-KEM parameter set (512, 768, 1024)
     * @return KeyPair containing public and private keys
     */
    fun generateKemKeyPair(parameterSet: Int = 768): KemKeyPair {
        val params = when (parameterSet) {
            512 -> MLKEMParameters.ml_kem_512
            768 -> MLKEMParameters.ml_kem_768
            1024 -> MLKEMParameters.ml_kem_1024
            else -> MLKEMParameters.ml_kem_768
        }

        val generator = MLKEMKeyPairGenerator()
        generator.init(MLKEMKeyGenerationParameters(params, SecureRandom()))
        val keyPair = generator.generateKeyPair()

        return KemKeyPair(
            publicKey = keyPair.public.encoded,
            privateKey = keyPair.private.encoded
        )
    }

    /**
     * Generate an ML-DSA key pair for digital signatures.
     * Used for signing transactions and documents.
     *
     * @param parameterSet ML-DSA parameter set (44, 65, 87)
     * @return SignatureKeyPair containing public and private keys
     */
    fun generateSignatureKeyPair(parameterSet: Int = 65): SignatureKeyPair {
        val params = when (parameterSet) {
            44 -> MLDSAParameters.ml_dsa_44
            65 -> MLDSAParameters.ml_dsa_65
            87 -> MLDSAParameters.ml_dsa_87
            else -> MLDSAParameters.ml_dsa_65
        }

        val generator = MLDSAKeyPairGenerator()
        generator.init(org.bouncycastle.crypto.params.AsymmetricKeyParameterGeneratorParameters(SecureRandom()))
        val keyPair = generator.generateKeyPair()

        return SignatureKeyPair(
            publicKey = keyPair.public.encoded,
            privateKey = keyPair.private.encoded
        )
    }

    /**
     * Sign a message using ML-DSA.
     *
     * @param message Message to sign
     * @param privateKey Private key bytes
     * @return Signature bytes
     */
    fun sign(message: ByteArray, privateKey: ByteArray): ByteArray {
        val privateKeyParams = MLDSAPrivateKeyParameters(
            MLDSAParameters.ml_dsa_65,
            privateKey
        )

        val signer = MLDSASigner()
        signer.init(true, privateKeyParams)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /**
     * Verify a signature using ML-DSA.
     *
     * @param message Original message
     * @param signature Signature bytes
     * @param publicKey Public key bytes
     * @return true if signature is valid
     */
    fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        try {
            val publicKeyParams = MLDSAPublicKeyParameters(
                MLDSAParameters.ml_dsa_65,
                publicKey
            )

            val signer = MLDSASigner()
            signer.init(false, publicKeyParams)
            signer.update(message, 0, message.size)
            return signer.verifySignature(signature)
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Compute SHA-256 hash of data.
     * Used as a building block for various crypto operations.
     */
    fun sha256(data: ByteArray): ByteArray {
        val digest = SHA256Digest()
        val output = ByteArray(digest.digestSize)
        digest.update(data, 0, data.size)
        digest.doFinal(output, 0)
        return output
    }
}

/**
 * ML-KEM key pair.
 */
data class KemKeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KemKeyPair) return false
        return publicKey.contentEquals(other.publicKey) && privateKey.contentEquals(other.privateKey)
    }

    override fun hashCode(): Int {
        var result = publicKey.contentHashCode()
        result = 31 * result + privateKey.contentHashCode()
        return result
    }
}

/**
 * ML-DSA signature key pair.
 */
data class SignatureKeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignatureKeyPair) return false
        return publicKey.contentEquals(other.publicKey) && privateKey.contentEquals(other.privateKey)
    }

    override fun hashCode(): Int {
        var result = publicKey.contentHashCode()
        result = 31 * result + privateKey.contentHashCode()
        return result
    }
}
