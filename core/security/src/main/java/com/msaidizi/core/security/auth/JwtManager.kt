package com.msaidizi.core.security.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.UUID

/**
 * JWT token manager using RS256 (RSA + SHA-256).
 *
 * Handles token generation, validation, and refresh for Msaidizi
 * backend authentication. Uses Android Keystore for key storage.
 *
 * ## Token Lifecycle
 * - Access token: 15 minutes (short-lived, stored in memory)
 * - Refresh token: 30 days (long-lived, stored in EncryptedSharedPreferences)
 * - Token rotation on each refresh
 */
class JwtManager {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "msaidizi_jwt_key"
        private const val TOKEN_EXPIRY_MS = 15 * 60 * 1000L // 15 minutes
        private const val REFRESH_TOKEN_EXPIRY_MS = 30 * 24 * 60 * 60 * 1000L // 30 days
    }

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    /**
     * Generate or retrieve the RSA key pair from Android Keystore.
     */
    fun getOrCreateKeyPair(): KeyPair {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA,
                KEYSTORE_PROVIDER
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setKeySize(2048)
                .setUserAuthenticationRequired(false) // Auth handled at app level
                .build()
            keyPairGenerator.initialize(spec)
            keyPairGenerator.generateKeyPair()
        }

        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
        val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey
        return KeyPair(publicKey, privateKey)
    }

    /**
     * Generate a JWT access token.
     *
     * @param workerId Worker's unique identifier
     * @param deviceId Device identifier
     * @return Signed JWT string
     */
    fun generateAccessToken(workerId: String, deviceId: String): String {
        val keyPair = getOrCreateKeyPair()
        val now = System.currentTimeMillis()

        val header = """{"alg":"RS256","typ":"JWT"}"""
        val payload = buildString {
            append("{")
            append("\"sub\":\"$workerId\",")
            append("\"dev\":\"$deviceId\",")
            append("\"iat\":${now / 1000},")
            append("\"exp\":${(now + TOKEN_EXPIRY_MS) / 1000},")
            append("\"jti\":\"${UUID.randomUUID()}\"")
            append("}")
        }

        val headerB64 = base64UrlEncode(header.toByteArray())
        val payloadB64 = base64UrlEncode(payload.toByteArray())
        val signingInput = "$headerB64.$payloadB64"

        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(keyPair.private)
            update(signingInput.toByteArray())
        }.sign()

        val signatureB64 = base64UrlEncode(signature)
        return "$signingInput.$signatureB64"
    }

    /**
     * Generate a refresh token (opaque string).
     */
    fun generateRefreshToken(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return base64UrlEncode(bytes)
    }

    /**
     * Validate a JWT token.
     *
     * @param token JWT string
     * @return TokenClaims if valid, null if invalid/expired
     */
    fun validateToken(token: String): TokenClaims? {
        try {
            val parts = token.split(".")
            if (parts.size != 3) return null

            val (headerB64, payloadB64, signatureB64) = parts
            val signingInput = "$headerB64.$payloadB64"

            // Verify signature
            val keyPair = getOrCreateKeyPair()
            val signatureBytes = base64UrlDecode(signatureB64)
            val isValid = Signature.getInstance("SHA256withRSA").apply {
                initVerify(keyPair.public)
                update(signingInput.toByteArray())
            }.verify(signatureBytes)

            if (!isValid) return null

            // Parse payload
            val payloadJson = String(base64UrlDecode(payloadB64))
            val exp = Regex("\"exp\":(\\d+)").find(payloadJson)?.groupValues?.get(1)?.toLong() ?: return null
            val sub = Regex("\"sub\":\"([^\"]+)\"").find(payloadJson)?.groupValues?.get(1) ?: return null
            val dev = Regex("\"dev\":\"([^\"]+)\"").find(payloadJson)?.groupValues?.get(1) ?: ""
            val jti = Regex("\"jti\":\"([^\"]+)\"").find(payloadJson)?.groupValues?.get(1) ?: ""

            // Check expiry
            if (System.currentTimeMillis() / 1000 > exp) return null

            return TokenClaims(
                subject = sub,
                deviceId = dev,
                expiresAt = exp * 1000,
                tokenId = jti
            )
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Check if a token is expired.
     */
    fun isTokenExpired(token: String): Boolean {
        val claims = validateToken(token) ?: return true
        return System.currentTimeMillis() >= claims.expiresAt
    }

    private fun base64UrlEncode(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun base64UrlDecode(data: String): ByteArray {
        return Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}

/**
 * Decoded JWT token claims.
 */
data class TokenClaims(
    val subject: String,      // Worker ID
    val deviceId: String,     // Device identifier
    val expiresAt: Long,      // Expiration timestamp (ms)
    val tokenId: String       // Unique token ID
)
