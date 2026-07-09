package com.msaidizi.app.security.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure token storage using EncryptedSharedPreferences backed by Android Keystore.
 *
 * Per SECURITY_ARCHITECTURE.md Section 4.3:
 * - Auth tokens stored in EncryptedSharedPreferences (AES-256-GCM backed by Android Keystore)
 * - Hardware-backed key storage
 * - No plaintext tokens on disk
 */
@Singleton
class SecureTokenStorage @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "angavu_secure_tokens"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_TOKEN_EXPIRY = "token_expiry"
        private const val KEY_REFRESH_EXPIRY = "refresh_expiry"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_DEVICE_ID = "device_id"
    }

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setRequestStrongBoxBacked(true) // Use StrongBox if available
            .build()
    }

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveTokens(accessToken: String, refreshToken: String, accessExpiryMs: Long, refreshExpiryMs: Long) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_TOKEN_EXPIRY, accessExpiryMs)
            .putLong(KEY_REFRESH_EXPIRY, refreshExpiryMs)
            .apply()
        Timber.d("Tokens saved to encrypted storage")
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    fun getAccessTokenExpiry(): Long = prefs.getLong(KEY_TOKEN_EXPIRY, 0)

    fun getRefreshTokenExpiry(): Long = prefs.getLong(KEY_REFRESH_EXPIRY, 0)

    fun isAccessTokenExpired(): Boolean {
        val expiry = getAccessTokenExpiry()
        return expiry == 0L || System.currentTimeMillis() >= expiry
    }

    fun isRefreshTokenExpired(): Boolean {
        val expiry = getRefreshTokenExpiry()
        return expiry == 0L || System.currentTimeMillis() >= expiry
    }

    fun saveUserId(userId: String) {
        prefs.edit().putString(KEY_USER_ID, userId).apply()
    }

    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)

    fun saveDeviceId(deviceId: String) {
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
    }

    fun getDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)

    /**
     * Clear all tokens — called on logout or security events.
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        Timber.i("All tokens cleared from encrypted storage")
    }

    fun hasValidSession(): Boolean {
        return getAccessToken() != null && !isRefreshTokenExpired()
    }

    /**
     * Validate that tokens belong to the current device.
     * Prevents token theft via device cloning or backup extraction.
     *
     * @param currentDeviceId The hashed device ID from DeviceBinder
     * @return true if the session is valid AND bound to this device
     */
    fun isSessionBoundToDevice(currentDeviceId: String): Boolean {
        if (!hasValidSession()) return false
        val storedDeviceId = getDeviceId() ?: return false
        // Use constant-time comparison to prevent timing attacks
        return storedDeviceId.length == currentDeviceId.length &&
            storedDeviceId.toByteArray().zip(currentDeviceId.toByteArray())
                .fold(0) { acc, (a, b) -> acc or (a.toInt() xor b.toInt()) } == 0
    }

    /**
     * Save a token binding nonce for CSRF protection.
     * The nonce is sent with each request and validated server-side.
     */
    fun saveCsrfNonce(nonce: String) {
        prefs.edit().putString("csrf_nonce", nonce).apply()
    }

    fun getCsrfNonce(): String? = prefs.getString("csrf_nonce", null)
}
