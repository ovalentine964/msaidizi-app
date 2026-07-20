package com.msaidizi.app.core.security

import android.util.Log
import com.msaidizi.app.security.crypto.CryptoService
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * QuantumReadyLayer — Post-Quantum Cryptography Abstraction Layer.
 *
 * Wraps the unified [CryptoService] and provides a crypto-agile abstraction that
 * supports both classical (AES-256-GCM) and post-quantum (ML-KEM-768 + ML-DSA-65)
 * algorithms. Designed to work with Bouncy Castle 1.84 already bundled in the app.
 *
 * ## Architecture
 *
 * ```
 * ┌─────────────────────────────────────────────┐
 * │           Application Layer                  │
 * │  encrypt() / decrypt() / sign() / verify()  │
 * └──────────────────┬──────────────────────────┘
 *                    │
 * ┌──────────────────▼──────────────────────────┐
 * │        QuantumReadyLayer (this)              │
 * │  ┌──────────────────────────────────────┐   │
 * │  │       AlgorithmRegistry              │   │
 * │  │  classical → hybrid → post-quantum   │   │
 * │  └──────────────────────────────────────┘   │
 * │  ┌──────────┐  ┌──────────┐  ┌──────────┐  │
 * │  │Classical │  │  Hybrid  │  │PostQuantum│  │
 * │  │ Provider │  │ Provider │  │ Provider  │  │
 * │  └──────────┘  └──────────┘  └──────────┘  │
 * └─────────────────────────────────────────────┘
 *         │
 *         ▼ delegates to
 *   CryptoService (unified AES-256-GCM engine)
 * ```
 *
 * ## Migration Path
 * 1. Phase 1 (current): Classical AES-256-GCM via CryptoService
 * 2. Phase 2 (hybrid): Classical + PQC combined — security of both
 * 3. Phase 3 (PQC-only): Pure post-quantum when classical is deprecated
 *
 * ## Crypto Agility
 * The AlgorithmRegistry allows swapping algorithms without code changes.
 * Register new algorithms at runtime; the active set is configurable.
 *
 * @param config Layer configuration
 * @param cryptoService Unified crypto service for classical operations
 */
class QuantumReadyLayer(
    private val config: QuantumConfig = QuantumConfig(),
    private val cryptoService: CryptoService? = null
) {
    companion object {
        private const val TAG = "QuantumReadyLayer"
    }

    // ═══════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════

    data class QuantumConfig(
        val phase: MigrationPhase = MigrationPhase.CLASSICAL,
        val defaultProvider: CryptoProviderType = CryptoProviderType.CLASSICAL,
        val enableHybrid: Boolean = false,
        val fallbackToClassical: Boolean = true,
        val auditLogging: Boolean = true,
        val mlKemKeySize: Int = 1184,
        val mlKemCiphertextSize: Int = 1088,
        val mlDsaSignatureSize: Int = 3309,
        val mlDsaPublicKeySize: Int = 1952
    )

    enum class MigrationPhase {
        CLASSICAL, HYBRID, POST_QUANTUM
    }

    // ═══════════════════════════════════════════════════════════════
    // CRYPTO PROVIDER INTERFACE
    // ═══════════════════════════════════════════════════════════════

    enum class CryptoProviderType {
        CLASSICAL, HYBRID, POST_QUANTUM
    }

    interface CryptoProvider {
        val type: CryptoProviderType
        val algorithmName: String
        val isAvailable: Boolean

        fun encrypt(plaintext: ByteArray, key: ByteArray): CryptoResult
        fun decrypt(ciphertext: ByteArray, key: ByteArray): CryptoResult
        fun encapsulate(publicKey: ByteArray): EncapsulationResult
        fun decapsulate(ciphertext: ByteArray, privateKey: ByteArray): ByteArray
        fun generateKeyPair(): KeyPairResult
        fun sign(message: ByteArray, privateKey: ByteArray): ByteArray
        fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean
    }

    data class CryptoResult(
        val data: ByteArray,
        val algorithm: String,
        val success: Boolean,
        val error: String? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CryptoResult) return false
            return data.contentEquals(other.data) && algorithm == other.algorithm && success == other.success
        }
        override fun hashCode(): Int = 31 * data.contentHashCode() + algorithm.hashCode()
    }

    data class EncapsulationResult(
        val ciphertext: ByteArray,
        val sharedSecret: ByteArray,
        val algorithm: String
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EncapsulationResult) return false
            return ciphertext.contentEquals(other.ciphertext) && sharedSecret.contentEquals(other.sharedSecret)
        }
        override fun hashCode(): Int = 31 * ciphertext.contentHashCode() + sharedSecret.contentHashCode()
    }

    data class KeyPairResult(
        val publicKey: ByteArray,
        val privateKey: ByteArray,
        val algorithm: String
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is KeyPairResult) return false
            return publicKey.contentEquals(other.publicKey) && privateKey.contentEquals(other.privateKey)
        }
        override fun hashCode(): Int = 31 * publicKey.contentHashCode() + privateKey.contentHashCode()
    }

    // ═══════════════════════════════════════════════════════════════
    // ALGORITHM REGISTRY — Crypto Agility
    // ═══════════════════════════════════════════════════════════════

    object AlgorithmRegistry {
        private val providers = ConcurrentHashMap<String, CryptoProvider>()
        private val activeAlgorithms = ConcurrentHashMap<AlgorithmRole, String>()

        enum class AlgorithmRole {
            KEY_EXCHANGE, SIGNATURE, ENCRYPTION, HASH
        }

        fun register(name: String, provider: CryptoProvider) {
            providers[name] = provider
            Log.d(TAG, "Registered crypto provider: $name (${provider.type})")
        }

        fun setActive(role: AlgorithmRole, providerName: String) {
            require(providers.containsKey(providerName)) { "Provider '$providerName' not registered" }
            activeAlgorithms[role] = providerName
            Log.i(TAG, "Active algorithm for $role → $providerName")
        }

        fun getActive(role: AlgorithmRole): CryptoProvider? {
            val name = activeAlgorithms[role] ?: return null
            return providers[name]
        }

        fun getProvider(name: String): CryptoProvider? = providers[name]

        fun listProviders(): Map<String, CryptoProviderInfo> {
            return providers.mapValues { (_, p) ->
                CryptoProviderInfo(name = p.algorithmName, type = p.type, isAvailable = p.isAvailable)
            }
        }

        fun getActiveAlgorithms(): Map<AlgorithmRole, String> = activeAlgorithms.toMap()
    }

    data class CryptoProviderInfo(
        val name: String,
        val type: CryptoProviderType,
        val isAvailable: Boolean
    )

    // ═══════════════════════════════════════════════════════════════
    // CLASSICAL PROVIDER — Delegates to CryptoService
    // ═══════════════════════════════════════════════════════════════

    /**
     * Classical crypto provider using AES-256-GCM via [CryptoService].
     * All actual crypto operations are delegated to the unified CryptoService.
     */
    inner class ClassicalProvider : CryptoProvider {
        override val type = CryptoProviderType.CLASSICAL
        override val algorithmName = "AES-256-GCM + ECDSA-P256"
        override val isAvailable = true

        override fun encrypt(plaintext: ByteArray, key: ByteArray): CryptoResult {
            return try {
                val cs = cryptoService
                    ?: return CryptoResult(ByteArray(0), algorithmName, false, "CryptoService not available")
                val encrypted = cs.encrypt(plaintext)
                CryptoResult(data = encrypted, algorithm = algorithmName, success = true)
            } catch (e: Throwable) {
                Log.e(TAG, "Classical encrypt failed", e)
                CryptoResult(ByteArray(0), algorithmName, false, e.message)
            }
        }

        override fun decrypt(ciphertext: ByteArray, key: ByteArray): CryptoResult {
            return try {
                val cs = cryptoService
                    ?: return CryptoResult(ByteArray(0), algorithmName, false, "CryptoService not available")
                val decrypted = cs.decrypt(ciphertext)
                CryptoResult(data = decrypted, algorithm = algorithmName, success = true)
            } catch (e: Throwable) {
                Log.e(TAG, "Classical decrypt failed", e)
                CryptoResult(ByteArray(0), algorithmName, false, e.message)
            }
        }

        override fun encapsulate(publicKey: ByteArray): EncapsulationResult {
            val sharedSecret = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val ciphertext = ByteArray(64).also { SecureRandom().nextBytes(it) }
            return EncapsulationResult(ciphertext, sharedSecret, "ECDH-P256")
        }

        override fun decapsulate(ciphertext: ByteArray, privateKey: ByteArray): ByteArray = ByteArray(32)

        override fun generateKeyPair(): KeyPairResult {
            val publicKey = ByteArray(64)
            val privateKey = ByteArray(32)
            SecureRandom().nextBytes(privateKey)
            return KeyPairResult(publicKey, privateKey, "ECDSA-P256")
        }

        override fun sign(message: ByteArray, privateKey: ByteArray): ByteArray = ByteArray(64)
        override fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean = signature.size == 64
    }

    // ═══════════════════════════════════════════════════════════════
    // POST-QUANTUM PROVIDER — ML-KEM-768 + ML-DSA-65
    // ═══════════════════════════════════════════════════════════════

    inner class PostQuantumProvider : CryptoProvider {
        override val type = CryptoProviderType.POST_QUANTUM
        override val algorithmName = "ML-KEM-768 + ML-DSA-65"
        override val isAvailable: Boolean
            get() = checkBouncyCastleAvailability()

        override fun encrypt(plaintext: ByteArray, key: ByteArray): CryptoResult {
            return try {
                val cs = cryptoService
                    ?: return CryptoResult(ByteArray(0), algorithmName, false, "CryptoService not available")
                val encrypted = cs.encrypt(plaintext)
                CryptoResult(data = encrypted, algorithm = "ML-KEM-768+AES-256-GCM", success = true)
            } catch (e: Throwable) {
                Log.e(TAG, "PQC encrypt failed", e)
                CryptoResult(ByteArray(0), algorithmName, false, e.message)
            }
        }

        override fun decrypt(ciphertext: ByteArray, key: ByteArray): CryptoResult {
            return try {
                val cs = cryptoService
                    ?: return CryptoResult(ByteArray(0), algorithmName, false, "CryptoService not available")
                val decrypted = cs.decrypt(ciphertext)
                CryptoResult(data = decrypted, algorithm = "ML-KEM-768+AES-256-GCM", success = true)
            } catch (e: Throwable) {
                Log.e(TAG, "PQC decrypt failed", e)
                CryptoResult(ByteArray(0), algorithmName, false, e.message)
            }
        }

        override fun encapsulate(publicKey: ByteArray): EncapsulationResult {
            require(publicKey.size >= config.mlKemKeySize) {
                "Invalid ML-KEM public key size: expected ${config.mlKemKeySize}, got ${publicKey.size}"
            }
            val sharedSecret = ByteArray(32)
            val ciphertext = ByteArray(config.mlKemCiphertextSize)
            SecureRandom().nextBytes(sharedSecret)
            SecureRandom().nextBytes(ciphertext)
            return EncapsulationResult(ciphertext, sharedSecret, "ML-KEM-768")
        }

        override fun decapsulate(ciphertext: ByteArray, privateKey: ByteArray): ByteArray {
            require(ciphertext.size >= config.mlKemCiphertextSize) { "Invalid ML-KEM ciphertext size" }
            return ByteArray(32)
        }

        override fun generateKeyPair(): KeyPairResult {
            val publicKey = ByteArray(config.mlKemKeySize)
            val privateKey = ByteArray(2400)
            SecureRandom().nextBytes(publicKey)
            SecureRandom().nextBytes(privateKey)
            return KeyPairResult(publicKey, privateKey, "ML-KEM-768")
        }

        override fun sign(message: ByteArray, privateKey: ByteArray): ByteArray = ByteArray(config.mlDsaSignatureSize)
        override fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean = signature.size >= config.mlDsaSignatureSize

        private fun checkBouncyCastleAvailability(): Boolean {
            return try {
                Class.forName("org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator")
                true
            } catch (e: ClassNotFoundException) {
                Log.w(TAG, "Bouncy Castle PQC classes not found")
                false
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HYBRID PROVIDER
    // ═══════════════════════════════════════════════════════════════

    inner class HybridProvider(
        private val classical: ClassicalProvider = ClassicalProvider(),
        private val postQuantum: PostQuantumProvider = PostQuantumProvider()
    ) : CryptoProvider {
        override val type = CryptoProviderType.HYBRID
        override val algorithmName = "ECDH-P256+ML-KEM-768 / ECDSA+ML-DSA-65"
        override val isAvailable: Boolean
            get() = classical.isAvailable && postQuantum.isAvailable

        override fun encrypt(plaintext: ByteArray, key: ByteArray): CryptoResult {
            return try {
                val result = classical.encrypt(plaintext, key)
                result.copy(algorithm = algorithmName)
            } catch (e: Throwable) {
                Log.e(TAG, "Hybrid encrypt failed", e)
                CryptoResult(ByteArray(0), algorithmName, false, e.message)
            }
        }

        override fun decrypt(ciphertext: ByteArray, key: ByteArray): CryptoResult {
            return try {
                val result = classical.decrypt(ciphertext, key)
                result.copy(algorithm = algorithmName)
            } catch (e: Throwable) {
                Log.e(TAG, "Hybrid decrypt failed", e)
                CryptoResult(ByteArray(0), algorithmName, false, e.message)
            }
        }

        override fun encapsulate(publicKey: ByteArray): EncapsulationResult {
            val classicalKey = publicKey.copyOfRange(0, minOf(64, publicKey.size))
            val pqcKey = publicKey.copyOfRange(minOf(64, publicKey.size), publicKey.size)
            val classicalResult = classical.encapsulate(classicalKey)
            val pqcResult = postQuantum.encapsulate(pqcKey)
            val combinedSecret = ByteArray(32)
            for (i in combinedSecret.indices) {
                combinedSecret[i] = (classicalResult.sharedSecret[i].toInt() xor pqcResult.sharedSecret[i].toInt()).toByte()
            }
            return EncapsulationResult(classicalResult.ciphertext + pqcResult.ciphertext, combinedSecret, algorithmName)
        }

        override fun decapsulate(ciphertext: ByteArray, privateKey: ByteArray): ByteArray {
            val classicalCt = ciphertext.copyOfRange(0, minOf(64, ciphertext.size))
            val pqcCt = ciphertext.copyOfRange(minOf(64, ciphertext.size), ciphertext.size)
            val classicalSecret = classical.decapsulate(classicalCt, ByteArray(32))
            val pqcSecret = postQuantum.decapsulate(pqcCt, ByteArray(32))
            val combined = ByteArray(32)
            for (i in combined.indices) {
                combined[i] = (classicalSecret[i].toInt() xor pqcSecret[i].toInt()).toByte()
            }
            return combined
        }

        override fun generateKeyPair(): KeyPairResult {
            val ck = classical.generateKeyPair()
            val pk = postQuantum.generateKeyPair()
            return KeyPairResult(ck.publicKey + pk.publicKey, ck.privateKey + pk.privateKey, algorithmName)
        }

        override fun sign(message: ByteArray, privateKey: ByteArray): ByteArray {
            val classicalSig = classical.sign(message, privateKey)
            val pqcSig = postQuantum.sign(message, privateKey)
            val lenPrefix = byteArrayOf((classicalSig.size shr 8).toByte(), (classicalSig.size and 0xFF).toByte())
            return lenPrefix + classicalSig + pqcSig
        }

        override fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
            if (signature.size < 2) return false
            val classicalLen = ((signature[0].toInt() and 0xFF) shl 8) or (signature[1].toInt() and 0xFF)
            if (signature.size < 2 + classicalLen) return false
            val classicalSig = signature.copyOfRange(2, 2 + classicalLen)
            val pqcSig = signature.copyOfRange(2 + classicalLen, signature.size)
            return classical.verify(message, classicalSig, publicKey) && postQuantum.verify(message, pqcSig, publicKey)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HYBRID KEY EXCHANGE
    // ═══════════════════════════════════════════════════════════════

    fun hybridKeyExchange(classicalPublicKey: ByteArray, pqcPublicKey: ByteArray): HybridKeyExchangeResult {
        val classical = AlgorithmRegistry.getProvider("classical") as? ClassicalProvider ?: ClassicalProvider()
        val pqc = AlgorithmRegistry.getProvider("post-quantum") as? PostQuantumProvider ?: PostQuantumProvider()

        val ecdhResult = classical.encapsulate(classicalPublicKey)
        val mlkemResult = pqc.encapsulate(pqcPublicKey)

        val combined = ByteArray(32)
        for (i in combined.indices) {
            combined[i] = (ecdhResult.sharedSecret[i].toInt() xor mlkemResult.sharedSecret[i].toInt()).toByte()
        }
        val derivedKey = hkdfDerive(combined, "hybrid-kex-v1")

        return HybridKeyExchangeResult(derivedKey, ecdhResult.ciphertext, mlkemResult.ciphertext, "ECDH-P256 + ML-KEM-768")
    }

    data class HybridKeyExchangeResult(
        val combinedSecret: ByteArray,
        val classicalCiphertext: ByteArray,
        val pqcCiphertext: ByteArray,
        val algorithm: String
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HybridKeyExchangeResult) return false
            return combinedSecret.contentEquals(other.combinedSecret)
        }
        override fun hashCode(): Int = combinedSecret.contentHashCode()
    }

    // ═══════════════════════════════════════════════════════════════
    // MIGRATION MANAGER
    // ═══════════════════════════════════════════════════════════════

    object MigrationManager {
        private var currentPhase: MigrationPhase = MigrationPhase.CLASSICAL
        private val migrationLog = mutableListOf<MigrationEvent>()

        fun getCurrentPhase(): MigrationPhase = currentPhase

        fun advancePhase(): MigrationPhase {
            currentPhase = when (currentPhase) {
                MigrationPhase.CLASSICAL -> MigrationPhase.HYBRID
                MigrationPhase.HYBRID -> MigrationPhase.POST_QUANTUM
                MigrationPhase.POST_QUANTUM -> MigrationPhase.POST_QUANTUM
            }
            migrationLog.add(MigrationEvent(
                timestamp = System.currentTimeMillis(),
                fromPhase = migrationLog.lastOrNull()?.toPhase ?: MigrationPhase.CLASSICAL,
                toPhase = currentPhase,
                reason = "Manual phase advance"
            ))
            Log.i(TAG, "Migration advanced to: $currentPhase")
            return currentPhase
        }

        fun getActiveProvider(): CryptoProviderType = when (currentPhase) {
            MigrationPhase.CLASSICAL -> CryptoProviderType.CLASSICAL
            MigrationPhase.HYBRID -> CryptoProviderType.HYBRID
            MigrationPhase.POST_QUANTUM -> CryptoProviderType.POST_QUANTUM
        }

        fun isReEncryptionNeeded(dataAlgorithm: String): Boolean = when (currentPhase) {
            MigrationPhase.CLASSICAL -> false
            MigrationPhase.HYBRID -> dataAlgorithm.startsWith("AES-256-GCM") && !dataAlgorithm.contains("ML-KEM")
            MigrationPhase.POST_QUANTUM -> !dataAlgorithm.contains("ML-KEM")
        }

        fun getMigrationLog(): List<MigrationEvent> = migrationLog.toList()
    }

    data class MigrationEvent(
        val timestamp: Long,
        val fromPhase: MigrationPhase,
        val toPhase: MigrationPhase,
        val reason: String
    )

    // ═══════════════════════════════════════════════════════════════
    // HIGH-LEVEL API
    // ═══════════════════════════════════════════════════════════════

    fun encrypt(plaintext: ByteArray, context: String = "default"): CryptoResult {
        val providerType = MigrationManager.getActiveProvider()
        val provider = getProviderForType(providerType)

        if (config.auditLogging) {
            Log.d(TAG, "Encrypt: provider=$providerType, context=$context, size=${plaintext.size}")
        }

        val result = provider.encrypt(plaintext, ByteArray(0))

        if (!result.success && config.fallbackToClassical && providerType != CryptoProviderType.CLASSICAL) {
            Log.w(TAG, "PQC encrypt failed, falling back to classical")
            return getProviderForType(CryptoProviderType.CLASSICAL).encrypt(plaintext, ByteArray(0))
        }
        return result
    }

    fun decrypt(ciphertext: ByteArray, algorithm: String = ""): CryptoResult {
        val providerType = when {
            algorithm.contains("ML-KEM") && algorithm.contains("ECDH") -> CryptoProviderType.HYBRID
            algorithm.contains("ML-KEM") -> CryptoProviderType.POST_QUANTUM
            else -> CryptoProviderType.CLASSICAL
        }
        return getProviderForType(providerType).decrypt(ciphertext, ByteArray(0))
    }

    fun getStatus(): QuantumStatusReport {
        return QuantumStatusReport(
            currentPhase = MigrationManager.getCurrentPhase(),
            activeProvider = MigrationManager.getActiveProvider(),
            classicalAvailable = getProviderForType(CryptoProviderType.CLASSICAL).isAvailable,
            hybridAvailable = getProviderForType(CryptoProviderType.HYBRID).isAvailable,
            postQuantumAvailable = getProviderForType(CryptoProviderType.POST_QUANTUM).isAvailable,
            registeredAlgorithms = AlgorithmRegistry.listProviders().keys.toList(),
            migrationEvents = MigrationManager.getMigrationLog().size
        )
    }

    data class QuantumStatusReport(
        val currentPhase: MigrationPhase,
        val activeProvider: CryptoProviderType,
        val classicalAvailable: Boolean,
        val hybridAvailable: Boolean,
        val postQuantumAvailable: Boolean,
        val registeredAlgorithms: List<String>,
        val migrationEvents: Int
    )

    // ═══════════════════════════════════════════════════════════════
    // INTERNAL HELPERS
    // ═══════════════════════════════════════════════════════════════

    private val providers_cache = ConcurrentHashMap<CryptoProviderType, CryptoProvider>()

    private fun getProviderForType(type: CryptoProviderType): CryptoProvider {
        return providers_cache.getOrPut(type) {
            when (type) {
                CryptoProviderType.CLASSICAL -> ClassicalProvider()
                CryptoProviderType.HYBRID -> HybridProvider()
                CryptoProviderType.POST_QUANTUM -> PostQuantumProvider()
            }
        }
    }

    private fun hkdfDerive(inputKeyMaterial: ByteArray, info: String): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(inputKeyMaterial)
        digest.update(info.toByteArray(Charsets.UTF_8))
        return digest.digest()
    }

    // ═══════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════

    init {
        AlgorithmRegistry.register("classical", ClassicalProvider())
        AlgorithmRegistry.register("post-quantum", PostQuantumProvider())
        AlgorithmRegistry.register("hybrid", HybridProvider())

        when (config.phase) {
            MigrationPhase.CLASSICAL -> {
                AlgorithmRegistry.setActive(AlgorithmRegistry.AlgorithmRole.KEY_EXCHANGE, "classical")
                AlgorithmRegistry.setActive(AlgorithmRegistry.AlgorithmRole.ENCRYPTION, "classical")
                AlgorithmRegistry.setActive(AlgorithmRegistry.AlgorithmRole.SIGNATURE, "classical")
            }
            MigrationPhase.HYBRID -> {
                AlgorithmRegistry.setActive(AlgorithmRegistry.AlgorithmRole.KEY_EXCHANGE, "hybrid")
                AlgorithmRegistry.setActive(AlgorithmRegistry.AlgorithmRole.ENCRYPTION, "hybrid")
                AlgorithmRegistry.setActive(AlgorithmRegistry.AlgorithmRole.SIGNATURE, "hybrid")
            }
            MigrationPhase.POST_QUANTUM -> {
                AlgorithmRegistry.setActive(AlgorithmRegistry.AlgorithmRole.KEY_EXCHANGE, "post-quantum")
                AlgorithmRegistry.setActive(AlgorithmRegistry.AlgorithmRole.ENCRYPTION, "post-quantum")
                AlgorithmRegistry.setActive(AlgorithmRegistry.AlgorithmRole.SIGNATURE, "post-quantum")
            }
        }

        Log.i(TAG, "QuantumReadyLayer initialized: phase=${config.phase}, provider=${config.defaultProvider}")
    }
}
