package com.msaidizi.app.security.crypto.pqc

/**
 * Post-Quantum Cryptography configuration.
 *
 * Controls the PQC migration state and algorithm selection.
 * Can be driven by remote config (Firebase Remote Config, feature flags)
 * for gradual rollout without app updates.
 *
 * PQC Migration Phases:
 * Phase 0 (Current): Classical-only — AES-256-GCM, ECDSA, ECDHE
 * Phase 1 (2026 Q3-Q4): Hybrid mode — classical + PQC running in parallel
 * Phase 2 (2027): PQC-preferred — PQC algorithms preferred, classical fallback
 * Phase 3 (2028+): PQC-only — classical algorithms deprecated
 *
 * White House EO 14412 mandates federal PQC migration by Dec 31, 2030.
 */
object PqcConfig {

    /**
     * Migration phases.
     */
    enum class MigrationPhase {
        /** Classical-only: no PQC algorithms active */
        PHASE_0_CLASSICAL,

        /** Hybrid: both classical and PQC algorithms running */
        PHASE_1_HYBRID,

        /** PQC-preferred: PQC algorithms preferred, classical fallback available */
        PHASE_2_PQC_PREFERRED,

        /** PQC-only: classical algorithms deprecated */
        PHASE_3_PQC_ONLY
    }

    /** Current migration phase — set via remote config or build config
     *  Default: PHASE_0_CLASSICAL (real threats first, quantum later)
     *  Enable hybrid via remote config when PQC TLS is supported on Android */
    var migrationPhase: MigrationPhase = MigrationPhase.PHASE_0_CLASSICAL

    /** Enable/disable hybrid key exchange for TLS connections */
    var enableHybridKeyExchange: Boolean = false

    /** Enable/disable PQC document signing (ML-DSA) */
    var enablePqcSigning: Boolean = false

    /** Enable/disable crypto audit logging */
    var enableAuditLogging: Boolean = true

    /** Enable/disable algorithm-change audit alerts */
    var enableAlgorithmChangeAlerts: Boolean = true

    /**
     * Get the recommended key exchange algorithm based on current phase.
     */
    fun getRecommendedKeyExchangeAlgorithm(): String {
        return when (migrationPhase) {
            MigrationPhase.PHASE_0_CLASSICAL -> "ECDHE"
            MigrationPhase.PHASE_1_HYBRID -> "X25519+ML-KEM-768"
            MigrationPhase.PHASE_2_PQC_PREFERRED -> "ML-KEM-768"
            MigrationPhase.PHASE_3_PQC_ONLY -> "ML-KEM-768"
        }
    }

    /**
     * Get the recommended signature algorithm based on current phase.
     */
    fun getRecommendedSignatureAlgorithm(): String {
        return when (migrationPhase) {
            MigrationPhase.PHASE_0_CLASSICAL -> "ECDSA-P256"
            MigrationPhase.PHASE_1_HYBRID -> "ML-DSA-65"  // Use PQC for new signatures
            MigrationPhase.PHASE_2_PQC_PREFERRED -> "ML-DSA-65"
            MigrationPhase.PHASE_3_PQC_ONLY -> "ML-DSA-65"
        }
    }

    /**
     * Get the recommended encryption algorithm based on current phase.
     * Note: AES-256-GCM is quantum-safe (256-bit key → 128-bit post-quantum security).
     */
    fun getRecommendedEncryptionAlgorithm(): String {
        // AES-256 is already quantum-safe — Grover's algorithm only halves effective key length
        return "AES-256-GCM"
    }

    /**
     * Whether to use hybrid (classical + PQ) key exchange.
     */
    fun shouldUseHybridKeyExchange(): Boolean {
        return migrationPhase >= MigrationPhase.PHASE_1_HYBRID && enableHybridKeyExchange
    }

    /**
     * Whether to require PQ signatures for new documents.
     */
    fun shouldRequirePqSignature(): Boolean {
        return migrationPhase >= MigrationPhase.PHASE_1_HYBRID && enablePqcSigning
    }

    /**
     * Whether classical fallback is allowed.
     */
    fun allowClassicalFallback(): Boolean {
        return migrationPhase <= MigrationPhase.PHASE_2_PQC_PREFERRED
    }

    /**
     * Get TLS cipher suites to offer, ordered by preference.
     * Includes both classical and PQC-hybrid suites.
     */
    fun getPreferredCipherSuites(): List<String> {
        return when (migrationPhase) {
            MigrationPhase.PHASE_0_CLASSICAL -> listOf(
                "TLS_AES_256_GCM_SHA384",
                "TLS_CHACHA20_POLY1305_SHA256",
                "TLS_AES_128_GCM_SHA256"
            )
            MigrationPhase.PHASE_1_HYBRID -> listOf(
                "TLS_AES_256_GCM_SHA384",         // Classical (still quantum-safe for symmetric)
                "TLS_CHACHA20_POLY1305_SHA256",     // Classical
                "TLS_AES_128_GCM_SHA256"            // Classical
                // Note: PQC-hybrid cipher suites for TLS are not yet standardized.
                // When IANA assigns code points for ML-KEM hybrid groups,
                // they will be added here. The key exchange happens at a layer
                // above cipher suite selection in TLS 1.3.
            )
            MigrationPhase.PHASE_2_PQC_PREFERRED -> listOf(
                "TLS_AES_256_GCM_SHA384",
                "TLS_CHACHA20_POLY1305_SHA256",
                "TLS_AES_128_GCM_SHA256"
            )
            MigrationPhase.PHASE_3_PQC_ONLY -> listOf(
                "TLS_AES_256_GCM_SHA384"   // Only AES-256 in PQC-only mode
            )
        }
    }

    /**
     * Get a status report of the current PQC configuration.
     */
    fun getStatusReport(): Map<String, Any> {
        return mapOf(
            "migrationPhase" to migrationPhase.name,
            "hybridKeyExchange" to enableHybridKeyExchange,
            "pqcSigning" to enablePqcSigning,
            "auditLogging" to enableAuditLogging,
            "recommendedKeyExchange" to getRecommendedKeyExchangeAlgorithm(),
            "recommendedSignature" to getRecommendedSignatureAlgorithm(),
            "recommendedEncryption" to getRecommendedEncryptionAlgorithm(),
            "cipherSuites" to getPreferredCipherSuites(),
            "classicalFallbackAllowed" to allowClassicalFallback(),
            "whitHouseEoDeadline" to "2030-12-31"
        )
    }
}
