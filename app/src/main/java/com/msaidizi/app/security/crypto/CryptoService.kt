package com.msaidizi.app.security.crypto

/**
 * Unified cryptographic service interface.
 *
 * Consolidates all encryption/decryption operations into a single entry point.
 * Replaces the previous duplicated implementations in:
 * - core/util/CryptoUtils (object)
 * - core/security/KeyManager (object)
 * - security/crypto/KeyManager (@Singleton class)
 *
 * Implementation uses AES-256-GCM via Android Keystore with hardware-backed keys.
 * Each key alias gets its own Keystore key to limit blast radius.
 *
 * For post-quantum capabilities, see [QuantumReadyLayer] which wraps this service.
 */
interface CryptoService {

    // ── Raw byte encryption ──────────────────────────────────────

    /** Encrypt raw bytes. Output: IV (12 bytes) || ciphertext || GCM tag. */
    fun encrypt(data: ByteArray, alias: KeyAlias = KeyAlias.STORAGE): ByteArray

    /** Decrypt bytes produced by [encrypt]. */
    fun decrypt(data: ByteArray, alias: KeyAlias = KeyAlias.STORAGE): ByteArray

    // ── String convenience ───────────────────────────────────────

    /** Encrypt a UTF-8 string, return Base64-encoded ciphertext. */
    fun encryptToString(data: String, alias: KeyAlias = KeyAlias.STORAGE): String

    /** Decrypt a Base64-encoded ciphertext back to the original string. */
    fun decryptToString(data: String, alias: KeyAlias = KeyAlias.STORAGE): String

    // ── Field-level encryption ───────────────────────────────────

    /** Encrypt multiple fields, each with its own IV. */
    fun encryptFields(fields: Map<String, String>, alias: KeyAlias = KeyAlias.PII): Map<String, String>

    /** Decrypt multiple fields. */
    fun decryptFields(fields: Map<String, String>, alias: KeyAlias = KeyAlias.PII): Map<String, String>

    // ── Key management ───────────────────────────────────────────

    /** Ensure a key exists for the given alias (creates if missing). Returns the SecretKey. */
    fun ensureKey(alias: KeyAlias): javax.crypto.SecretKey

    /** Check if a key exists. */
    fun keyExists(alias: KeyAlias): Boolean

    /** Delete a key. WARNING: data encrypted with this key will be lost. */
    fun deleteKey(alias: KeyAlias)

    // ── Hashing (utility, not encryption) ────────────────────────

    /** SHA-256 hash of a string (hex-encoded). For non-sensitive data like device IDs. */
    fun sha256(input: String): String

    /** Generate a cryptographically secure random API key (hex-encoded). */
    fun generateApiKey(): String

    /**
     * Key aliases for different security domains.
     * Each domain gets its own Keystore key to limit blast radius.
     */
    enum class KeyAlias(val aliasName: String) {
        AUTH("msaidizi_key_auth"),
        SYNC("msaidizi_key_sync"),
        BIOMETRIC("msaidizi_key_biometric"),
        STORAGE("msaidizi_key_storage"),
        DB("msaidizi_key_db"),
        PII("msaidizi_key_pii"),
        HMAC("msaidizi_key_hmac")
    }
}
