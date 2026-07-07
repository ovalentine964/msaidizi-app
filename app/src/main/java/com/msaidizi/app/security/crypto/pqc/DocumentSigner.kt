package com.msaidizi.app.security.crypto.pqc

import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Quantum-resistant document signing service.
 *
 * Uses ML-DSA (Dilithium) for document signatures, with classical ECDSA
 * as a fallback during the migration period.
 *
 * Signs:
 * - Transaction receipts and confirmations
 * - Identity verification documents
 * - Audit log integrity seals
 * - API request payloads (anti-tampering)
 * - Sync data integrity proofs
 *
 * Per NIST FIPS 204, ML-DSA signatures are deterministic (no nonce reuse risks)
 * and provide EUF-CMA security against quantum adversaries.
 */
@Singleton
class DocumentSigner @Inject constructor(
    private val algorithmRegistry: AlgorithmRegistry,
    private val auditLogger: CryptoAuditLogger
) {
    /**
     * Sign a document with the current preferred signature algorithm.
     *
     * @param document The document bytes to sign
     * @param privateKey The signing private key
     * @param algorithmId Optional algorithm override (null = use default)
     * @return SignatureResult containing the signature and metadata
     */
    fun sign(
        document: ByteArray,
        privateKey: ByteArray,
        algorithmId: String? = null
    ): SignatureResult {
        val provider = algorithmRegistry.getSignatureProvider(algorithmId)
        val dataHash = sha256Hex(document)

        return try {
            val signature = provider.sign(document, privateKey)

            val result = SignatureResult(
                signature = signature,
                algorithmId = provider.algorithmId,
                isPostQuantum = provider.isPostQuantum,
                dataHash = dataHash,
                timestamp = System.currentTimeMillis()
            )

            auditLogger.logSignature(
                algorithmId = provider.algorithmId,
                dataHash = dataHash,
                success = true
            )

            Timber.i("Document signed: algorithm=%s, pq=%b, hash=%s",
                provider.algorithmId, provider.isPostQuantum, dataHash)

            result
        } catch (e: Exception) {
            auditLogger.logSignature(
                algorithmId = provider.algorithmId,
                dataHash = dataHash,
                success = false,
                error = e.message
            )
            throw SecurityException("Document signing failed: ${e.message}", e)
        }
    }

    /**
     * Verify a document signature.
     *
     * @param document The original document bytes
     * @param signatureResult The signature to verify
     * @param publicKey The signer's public key
     * @return true if the signature is valid
     */
    fun verify(
        document: ByteArray,
        signatureResult: SignatureResult,
        publicKey: ByteArray
    ): Boolean {
        val provider = algorithmRegistry.getSignatureProvider(signatureResult.algorithmId)
        val dataHash = sha256Hex(document)

        return try {
            val valid = provider.verify(document, signatureResult.signature, publicKey)

            auditLogger.logVerification(
                algorithmId = signatureResult.algorithmId,
                dataHash = dataHash,
                valid = valid
            )

            Timber.i("Signature verification: algorithm=%s, valid=%b, hash=%s",
                signatureResult.algorithmId, valid, dataHash)

            valid
        } catch (e: Exception) {
            auditLogger.logVerification(
                algorithmId = signatureResult.algorithmId,
                dataHash = dataHash,
                valid = false,
                error = e.message
            )
            Timber.e(e, "Signature verification failed")
            false
        }
    }

    /**
     * Create a dual signature (classical + PQ) for maximum compatibility.
     *
     * During the hybrid migration phase, documents are signed with both
     * ECDSA (classical) and ML-DSA (post-quantum). Verifiers can check
     * either signature depending on their capabilities.
     *
     * @param document The document to sign
     * @param ecdsaKey Classical ECDSA private key
     * @param mlDsaKey ML-DSA private key
     * @return DualSignature containing both signatures
     */
    fun signDual(
        document: ByteArray,
        ecdsaKey: ByteArray,
        mlDsaKey: ByteArray
    ): DualSignature {
        val ecdsaSig = sign(document, ecdsaKey, "ECDSA-P256")
        val mlDsaSig = sign(document, mlDsaKey, PqcConfig.getRecommendedSignatureAlgorithm())

        Timber.i("Dual signature created: ECDSA-P256 + %s", mlDsaSig.algorithmId)

        return DualSignature(
            classicalSignature = ecdsaSig,
            pqSignature = mlDsaSig,
            dataHash = sha256Hex(document)
        )
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Result of a document signing operation.
 */
data class SignatureResult(
    val signature: ByteArray,
    val algorithmId: String,
    val isPostQuantum: Boolean,
    val dataHash: String,
    val timestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignatureResult) return false
        return algorithmId == other.algorithmId &&
                isPostQuantum == other.isPostQuantum &&
                dataHash == other.dataHash &&
                timestamp == other.timestamp &&
                signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = algorithmId.hashCode()
        result = 31 * result + isPostQuantum.hashCode()
        result = 31 * result + dataHash.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

/**
 * Dual signature for hybrid migration period.
 * Contains both a classical (ECDSA) and post-quantum (ML-DSA) signature.
 */
data class DualSignature(
    val classicalSignature: SignatureResult,
    val pqSignature: SignatureResult,
    val dataHash: String
)
