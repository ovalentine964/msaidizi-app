package com.msaidizi.app.security.auth

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import timber.log.Timber
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * JWT Token Manager — handles access + refresh token lifecycle.
 *
 * Per SECURITY_ARCHITECTURE.md Section 3.4:
 * - Access Token: RS256, 15-min expiry, claims: sub, phone_hash, roles, tier, device_id, iat, exp, jti
 * - Refresh Token: RS256, 7-day expiry, server-side hash stored, rotation on each use
 * - Family detection: revoked refresh token reuse → revoke all tokens in family
 * - Device binding: tied to device_id and IP subnet
 *
 * Client-side implementation: handles token parsing, validation, refresh logic.
 * Actual signing/verification is server-side (RS256 asymmetric).
 */
@Singleton
class JwtTokenManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenStorage: SecureTokenStorage
) {
    companion object {
        private const val ACCESS_TOKEN_DURATION_MS = 15 * 60 * 1000L   // 15 minutes
        private const val REFRESH_TOKEN_DURATION_MS = 7 * 24 * 60 * 60 * 1000L  // 7 days
        private const val TOKEN_REFRESH_BUFFER_MS = 60 * 1000L  // Refresh 1 min before expiry
    }

    sealed class TokenState {
        object Unauthenticated : TokenState()
        object Refreshing : TokenState()
        data class Authenticated(val userId: String, val roles: List<String>) : TokenState()
        data class Expired(val reason: String) : TokenState()
        data class Error(val message: String) : TokenState()
    }

    private val _state = MutableStateFlow<TokenState>(TokenState.Unauthenticated)
    val state: StateFlow<TokenState> = _state.asStateFlow()

    /**
     * Store tokens received from authentication flow.
     */
    fun storeTokens(
        accessToken: String,
        refreshToken: String,
        userId: String,
        deviceId: String
    ) {
        val accessExpiry = parseTokenExpiry(accessToken)
            ?: (System.currentTimeMillis() + ACCESS_TOKEN_DURATION_MS)
        val refreshExpiry = parseTokenExpiry(refreshToken)
            ?: (System.currentTimeMillis() + REFRESH_TOKEN_DURATION_MS)

        tokenStorage.saveTokens(accessToken, refreshToken, accessExpiry, refreshExpiry)
        tokenStorage.saveUserId(userId)
        tokenStorage.saveDeviceId(deviceId)

        val claims = parseTokenClaims(accessToken)
        val roles = claims?.optJSONArray("roles")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()

        _state.value = TokenState.Authenticated(userId, roles)
        Timber.i("Tokens stored for user %s with roles %s", userId, roles)
    }

    /**
     * Get a valid access token, refreshing if needed.
     * Returns null if re-authentication is required.
     */
    suspend fun getValidAccessToken(): String? {
        val accessToken = tokenStorage.getAccessToken() ?: return null

        // Check if access token is still valid (with buffer)
        if (!tokenStorage.isAccessTokenExpired()) {
            val expiry = tokenStorage.getAccessTokenExpiry()
            if (System.currentTimeMillis() < expiry - TOKEN_REFRESH_BUFFER_MS) {
                return accessToken
            }
        }

        // Access token expired — try refresh
        return refreshToken()
    }

    /**
     * Refresh the access token using the refresh token.
     * Implements token rotation: each refresh issues a new refresh token.
     */
    private suspend fun refreshToken(): String? {
        val refreshToken = tokenStorage.getRefreshToken()

        if (refreshToken == null || tokenStorage.isRefreshTokenExpired()) {
            Timber.w("Refresh token expired or missing — re-auth required")
            _state.value = TokenState.Expired("Session expired. Please log in again.")
            tokenStorage.clearAll()
            return null
        }

        _state.value = TokenState.Refreshing

        return try {
            // In production, this would call the API to refresh tokens.
            // The server would:
            // 1. Validate the refresh token hash
            // 2. Check token family for replay attacks
            // 3. Issue new access + refresh tokens (rotation)
            // 4. Invalidate the old refresh token
            //
            // For now, we simulate the refresh call.
            Timber.i("Refreshing tokens...")

            // TODO: Replace with actual API call
            // val response = api.refreshToken(RefreshTokenRequest(refreshToken))
            // if (response.isSuccessful) { ... }

            null // Placeholder — actual API integration needed
        } catch (e: Exception) {
            Timber.e(e, "Token refresh failed")
            _state.value = TokenState.Error("Connection error. Please try again.")
            null
        }
    }

    /**
     * Revoke all tokens — called on logout or security events.
     */
    fun revokeAllTokens() {
        tokenStorage.clearAll()
        _state.value = TokenState.Unauthenticated
        Timber.i("All tokens revoked")
    }

    /**
     * Parse expiry time from JWT token payload.
     */
    private fun parseTokenExpiry(token: String): Long? {
        return try {
            val claims = parseTokenClaims(token)
            claims?.optLong("exp")?.let { it * 1000 } // Convert seconds to ms
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse JWT claims without verification (client-side).
     * Server handles signature verification.
     */
    private fun parseTokenClaims(token: String): JSONObject? {
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return null
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE))
            JSONObject(payload)
        } catch (e: Exception) {
            Timber.w("Failed to parse JWT claims: %s", e.message)
            null
        }
    }

    /**
     * Generate a unique JTI (JWT ID) for token tracking.
     */
    fun generateJti(): String = UUID.randomUUID().toString()
}
