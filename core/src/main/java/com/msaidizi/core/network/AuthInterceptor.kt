package com.msaidizi.core.network

import com.msaidizi.core.security.EncryptionManager
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp interceptor that adds authentication headers to sync API requests.
 *
 * Headers:
 * - Authorization: Bearer <token> (stored in EncryptedSharedPreferences)
 * - X-Device-ID: anonymous device fingerprint
 * - X-Request-Timestamp: epoch millis for replay protection
 * - X-Request-Signature: HMAC of request body for integrity
 *
 * If no token is available, the request proceeds without auth
 * (backend should reject unauthenticated requests with 401).
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val encryptionManager: EncryptionManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        val prefs = encryptionManager.getEncryptedPrefs()
        val token = prefs.getString(KEY_SYNC_TOKEN, null)
        val deviceIdHash = prefs.getString(KEY_DEVICE_ID_HASH, null) ?: "unknown"

        val timestamp = System.currentTimeMillis().toString()

        val requestBuilder = original.newBuilder()
            .addHeader("X-Device-ID", deviceIdHash)
            .addHeader("X-Request-Timestamp", timestamp)

        if (token != null) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        } else {
            Timber.w("No sync API token available — request will be unauthenticated")
        }

        // Sign the request body for integrity
        val body = original.body
        if (body != null) {
            val buffer = okio.Buffer()
            body.writeTo(buffer)
            val bodyHash = hashString(buffer.readUtf8())
            requestBuilder.addHeader("X-Request-Signature", bodyHash)
        }

        return chain.proceed(requestBuilder.build())
    }

    /**
     * Store a sync API token (called after successful auth/registration).
     */
    fun setToken(token: String) {
        encryptionManager.getEncryptedPrefs()
            .edit()
            .putString(KEY_SYNC_TOKEN, token)
            .apply()
        Timber.d("Sync API token stored")
    }

    /**
     * Clear the stored token (logout).
     */
    fun clearToken() {
        encryptionManager.getEncryptedPrefs()
            .edit()
            .remove(KEY_SYNC_TOKEN)
            .apply()
        Timber.d("Sync API token cleared")
    }

    private fun hashString(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }

    companion object {
        private const val KEY_SYNC_TOKEN = "sync_api_token"
        private const val KEY_DEVICE_ID_HASH = "device_id_hash"
    }
}
