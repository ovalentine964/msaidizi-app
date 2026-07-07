package com.msaidizi.app.security.crypto.pqc

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Algorithm registry for crypto-agility.
 *
 * Allows runtime selection and swapping of cryptographic algorithms without
 * changing application code. This is critical for PQC migration:
 * - Register both classical and PQC providers
 * - Switch algorithms via configuration (remote config, feature flags)
 * - A/B test new algorithms before full rollout
 * - Emergency algorithm swap if a vulnerability is discovered
 *
 * Usage:
 *   val provider = algorithmRegistry.getEncryptProvider("AES-256-GCM")
 *   val provider = algorithmRegistry.getEncryptProvider("ML-KEM-768")
 */
@Singleton
class AlgorithmRegistry @Inject constructor() {

    private val encryptProviders = mutableMapOf<String, CryptoProvider>()
    private val signatureProviders = mutableMapOf<String, CryptoProvider>()
    private val kemProviders = mutableMapOf<String, KeyEncapsulationProvider>()

    /** Default algorithms — can be overridden via configuration */
    var defaultEncryptAlgorithm: String = "AES-256-GCM"
        private set
    var defaultSignatureAlgorithm: String = "ML-DSA-65"
        private set
    var defaultKemAlgorithm: String = "ML-KEM-768"
        private set

    init {
        // Register classical providers
        registerClassicalProviders()

        // Register post-quantum providers
        registerPqcProviders()

        Timber.i("AlgorithmRegistry initialized: %d encrypt, %d sign, %d KEM providers",
            encryptProviders.size, signatureProviders.size, kemProviders.size)
    }

    /**
     * Register a symmetric encryption provider.
     */
    fun registerEncryptProvider(provider: CryptoProvider) {
        encryptProviders[provider.algorithmId] = provider
        Timber.d("Registered encrypt provider: %s (PQ=%b)", provider.algorithmId, provider.isPostQuantum)
    }

    /**
     * Register a signature provider.
     */
    fun registerSignatureProvider(provider: CryptoProvider) {
        signatureProviders[provider.algorithmId] = provider
        Timber.d("Registered signature provider: %s (PQ=%b)", provider.algorithmId, provider.isPostQuantum)
    }

    /**
     * Register a KEM provider.
     */
    fun registerKemProvider(provider: KeyEncapsulationProvider) {
        kemProviders[provider.algorithmId] = provider
        Timber.d("Registered KEM provider: %s (PQ=%b)", provider.algorithmId, provider.isPostQuantum)
    }

    /**
     * Get an encryption provider by algorithm ID.
     * Falls back to default if not found.
     */
    fun getEncryptProvider(algorithmId: String? = null): CryptoProvider {
        val id = algorithmId ?: defaultEncryptAlgorithm
        return encryptProviders[id]
            ?: throw IllegalArgumentException("Unknown encrypt algorithm: $id. Available: ${encryptProviders.keys}")
    }

    /**
     * Get a signature provider by algorithm ID.
     */
    fun getSignatureProvider(algorithmId: String? = null): CryptoProvider {
        val id = algorithmId ?: defaultSignatureAlgorithm
        return signatureProviders[id]
            ?: throw IllegalArgumentException("Unknown signature algorithm: $id. Available: ${signatureProviders.keys}")
    }

    /**
     * Get a KEM provider by algorithm ID.
     */
    fun getKemProvider(algorithmId: String? = null): KeyEncapsulationProvider {
        val id = algorithmId ?: defaultKemAlgorithm
        return kemProviders[id]
            ?: throw IllegalArgumentException("Unknown KEM algorithm: $id. Available: ${kemProviders.keys}")
    }

    /**
     * Set the default encryption algorithm (e.g., via remote config).
     */
    fun setDefaultEncryptAlgorithm(algorithmId: String) {
        require(encryptProviders.containsKey(algorithmId)) {
            "Cannot set default to unregistered algorithm: $algorithmId"
        }
        defaultEncryptAlgorithm = algorithmId
        Timber.i("Default encrypt algorithm changed to: %s", algorithmId)
    }

    /**
     * Set the default signature algorithm.
     */
    fun setDefaultSignatureAlgorithm(algorithmId: String) {
        require(signatureProviders.containsKey(algorithmId)) {
            "Cannot set default to unregistered algorithm: $algorithmId"
        }
        defaultSignatureAlgorithm = algorithmId
        Timber.i("Default signature algorithm changed to: %s", algorithmId)
    }

    /**
     * Set the default KEM algorithm.
     */
    fun setDefaultKemAlgorithm(algorithmId: String) {
        require(kemProviders.containsKey(algorithmId)) {
            "Cannot set default to unregistered algorithm: $algorithmId"
        }
        defaultKemAlgorithm = algorithmId
        Timber.i("Default KEM algorithm changed to: %s", algorithmId)
    }

    /**
     * Get all registered algorithm IDs, grouped by type.
     */
    fun listAlgorithms(): Map<String, List<String>> {
        return mapOf(
            "encrypt" to encryptProviders.keys.toList(),
            "signature" to signatureProviders.keys.toList(),
            "kem" to kemProviders.keys.toList()
        )
    }

    /**
     * Get all post-quantum algorithm IDs.
     */
    fun listPqAlgorithms(): Map<String, List<String>> {
        return mapOf(
            "encrypt" to encryptProviders.filter { it.value.isPostQuantum }.keys.toList(),
            "signature" to signatureProviders.filter { it.value.isPostQuantum }.keys.toList(),
            "kem" to kemProviders.filter { it.value.isPostQuantum }.keys.toList()
        )
    }

    // === Internal registration ===

    private fun registerClassicalProviders() {
        // AES-256-GCM — current primary encryption
        registerEncryptProvider(AesGcmProvider())

        // ECDSA P-256 — current signature scheme (to be replaced by ML-DSA)
        registerSignatureProvider(EcdsaProvider())
    }

    private fun registerPqcProviders() {
        // ML-KEM (Kyber) variants
        registerKemProvider(MlKemProvider(MlKemParameterSet.ML_KEM_512))
        registerKemProvider(MlKemProvider(MlKemParameterSet.ML_KEM_768))
        registerKemProvider(MlKemProvider(MlKemParameterSet.ML_KEM_1024))

        // ML-DSA (Dilithium) variants
        registerSignatureProvider(MlDsaProvider(MlDsaParameterSet.ML_DSA_44))
        registerSignatureProvider(MlDsaProvider(MlDsaParameterSet.ML_DSA_65))
        registerSignatureProvider(MlDsaProvider(MlDsaParameterSet.ML_DSA_87))
    }
}

/**
 * AES-256-GCM provider (classical, quantum-safe for symmetric encryption).
 * AES-256 provides 128-bit post-quantum security (Grover's algorithm halves effective key length).
 */
private class AesGcmProvider : CryptoProvider {
    override val algorithmId = "AES-256-GCM"
    override val isPostQuantum = false // Symmetric, but quantum-safe with 256-bit key
    override val securityLevel = 1

    override fun generateKeyPair(): CryptoKeyPair {
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return CryptoKeyPair(key.encoded, key.encoded, algorithmId)
    }

    override fun encrypt(plaintext: ByteArray, key: ByteArray): ByteArray {
        val keySpec = javax.crypto.spec.SecretKeySpec(key, "AES")
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec)
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext)
        val buf = java.nio.ByteBuffer.allocate(iv.size + ct.size)
        buf.put(iv); buf.put(ct)
        return buf.array()
    }

    override fun decrypt(ciphertext: ByteArray, key: ByteArray): ByteArray {
        val buf = java.nio.ByteBuffer.wrap(ciphertext)
        val iv = ByteArray(12); buf.get(iv)
        val ct = ByteArray(buf.remaining()); buf.get(ct)
        val keySpec = javax.crypto.spec.SecretKeySpec(key, "AES")
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, javax.crypto.spec.GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    override fun sign(data: ByteArray, privateKey: ByteArray): ByteArray =
        throw UnsupportedOperationException("AES-GCM doesn't support signing")

    override fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean =
        throw UnsupportedOperationException("AES-GCM doesn't support signature verification")
}

/**
 * ECDSA P-256 provider (classical, NOT quantum-resistant).
 * Retained for backward compatibility during PQC migration.
 */
private class EcdsaProvider : CryptoProvider {
    override val algorithmId = "ECDSA-P256"
    override val isPostQuantum = false
    override val securityLevel = 1

    override fun generateKeyPair(): CryptoKeyPair {
        val kp = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        return CryptoKeyPair(kp.public.encoded, kp.private.encoded, algorithmId)
    }

    override fun encrypt(plaintext: ByteArray, key: ByteArray): ByteArray =
        throw UnsupportedOperationException("ECDSA is a signature algorithm")

    override fun decrypt(ciphertext: ByteArray, key: ByteArray): ByteArray =
        throw UnsupportedOperationException("ECDSA is a signature algorithm")

    override fun sign(data: ByteArray, privateKey: ByteArray): ByteArray {
        val keyFactory = java.security.KeyFactory.getInstance("EC")
        val privKey = keyFactory.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privateKey))
        val sig = java.security.Signature.getInstance("SHA256withECDSA")
        sig.initSign(privKey)
        sig.update(data)
        return sig.sign()
    }

    override fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        val keyFactory = java.security.KeyFactory.getInstance("EC")
        val pubKey = keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(publicKey))
        val sig = java.security.Signature.getInstance("SHA256withECDSA")
        sig.initVerify(pubKey)
        sig.update(data)
        return sig.verify(signature)
    }
}
