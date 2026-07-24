package com.msaidizi.core.security.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for sensitive data.
 * Uses Android's EncryptedSharedPreferences backed by Android Keystore.
 *
 * Stores:
 * - JWT tokens (access + refresh)
 * - API keys
 * - M-Pesa credentials
 * - Database encryption key
 * - Biometric-bound secrets
 */
class EncryptedStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // ═══ TOKEN STORAGE ═══

    fun saveAccessToken(token: String) {
        prefs.edit().putString(KEY_ACCESS_TOKEN, token).apply()
    }

    fun getAccessToken(): String? {
        return prefs.getString(KEY_ACCESS_TOKEN, null)
    }

    fun saveRefreshToken(token: String) {
        prefs.edit().putString(KEY_REFRESH_TOKEN, token).apply()
    }

    fun getRefreshToken(): String? {
        return prefs.getString(KEY_REFRESH_TOKEN, null)
    }

    fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .apply()
    }

    // ═══ DATABASE KEY ═══

    fun saveDatabaseKey(key: ByteArray) {
        prefs.edit().putString(KEY_DATABASE_KEY, android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)).apply()
    }

    fun getDatabaseKey(): ByteArray? {
        val encoded = prefs.getString(KEY_DATABASE_KEY, null) ?: return null
        return android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
    }

    // ═══ API KEYS ═══

    fun saveApiKey(name: String, key: String) {
        prefs.edit().putString("api_key_$name", key).apply()
    }

    fun getApiKey(name: String): String? {
        return prefs.getString("api_key_$name", null)
    }

    // ═══ MPESA ═══

    fun saveMpesaCredentials(consumerKey: String, consumerSecret: String) {
        prefs.edit()
            .putString(KEY_MPESA_CONSUMER_KEY, consumerKey)
            .putString(KEY_MPESA_CONSUMER_SECRET, consumerSecret)
            .apply()
    }

    fun getMpesaConsumerKey(): String? {
        return prefs.getString(KEY_MPESA_CONSUMER_KEY, null)
    }

    fun getMpesaConsumerSecret(): String? {
        return prefs.getString(KEY_MPESA_CONSUMER_SECRET, null)
    }

    // ═══ GENERIC ═══

    fun saveString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getString(key: String): String? {
        return prefs.getString(key, null)
    }

    fun saveLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    fun getLong(key: String, defaultValue: Long = 0): Long {
        return prefs.getLong(key, defaultValue)
    }

    fun saveBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_FILE_NAME = "msaidizi_encrypted_prefs"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_DATABASE_KEY = "database_key"
        private const val KEY_MPESA_CONSUMER_KEY = "mpesa_consumer_key"
        private const val KEY_MPESA_CONSUMER_SECRET = "mpesa_consumer_secret"
    }
}
