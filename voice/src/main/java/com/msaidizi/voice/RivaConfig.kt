package com.msaidizi.voice

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Configuration for Riva NIM server connection.
 *
 * Stores server endpoint, API key, and model preferences securely using
 * EncryptedSharedPreferences. Supports both cloud-hosted (build.nvidia.com)
 * and self-hosted Riva NIM deployments.
 *
 * Cloud config example:
 *   host = "grpc.nvcf.nvidia.com", port = 443, useSsl = true
 *
 * Self-hosted config example:
 *   host = "192.168.1.100", port = 50051, useSsl = false
 */
@Singleton
class RivaConfig @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "riva_config"
        private const val DEFAULT_GRPC_PORT = 50051
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences by lazy {
        try {
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to create encrypted prefs, falling back to regular prefs")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /** Riva server hostname (e.g., "grpc.nvcf.nvidia.com" or "192.168.1.100"). */
    val serverHost: String
        get() = prefs.getString("server_host", "") ?: ""

    /** Riva server gRPC port (default: 50051). */
    val serverPort: Int
        get() = prefs.getInt("server_port", DEFAULT_GRPC_PORT)

    /** NVIDIA API key for cloud authentication. */
    val apiKey: String
        get() = prefs.getString("api_key", "") ?: ""

    /** NVCF function ID for cloud ASR model (rotates per release). */
    val asrFunctionId: String
        get() = prefs.getString("asr_function_id", "") ?: ""

    /** NVCF function ID for cloud TTS model (rotates per release). */
    val ttsFunctionId: String
        get() = prefs.getString("tts_function_id", "") ?: ""

    /** Whether to use TLS (required for cloud, optional for self-hosted). */
    val useSsl: Boolean
        get() = prefs.getBoolean("use_ssl", false)

    /** ASR language code (e.g., "sw", "en", "sw-KE"). */
    val asrLanguageCode: String
        get() = prefs.getString("asr_language", "sw") ?: "sw"

    /** TTS voice name (discover via --list-voices on running NIM). */
    val ttsVoiceName: String
        get() = prefs.getString("tts_voice", "") ?: ""

    /** Whether Riva integration is enabled. */
    val isEnabled: Boolean
        get() = prefs.getBoolean("enabled", false) && serverHost.isNotEmpty()

    /** Whether this is a cloud (build.nvidia.com) deployment. */
    val isCloud: Boolean
        get() = serverHost.contains("nvcf.nvidia.com") ||
                serverHost.contains("build.nvidia.com")

    /**
     * Update configuration.
     */
    fun update(
        host: String = serverHost,
        port: Int = serverPort,
        apiKey: String = this.apiKey,
        asrFunctionId: String = this.asrFunctionId,
        ttsFunctionId: String = this.ttsFunctionId,
        useSsl: Boolean = this.useSsl,
        asrLanguage: String = asrLanguageCode,
        ttsVoice: String = ttsVoiceName,
        enabled: Boolean = isEnabled
    ) {
        prefs.edit().apply {
            putString("server_host", host)
            putInt("server_port", port)
            putString("api_key", apiKey)
            putString("asr_function_id", asrFunctionId)
            putString("tts_function_id", ttsFunctionId)
            putBoolean("use_ssl", useSsl)
            putString("asr_language", asrLanguage)
            putString("tts_voice", ttsVoice)
            putBoolean("enabled", enabled)
            apply()
        }
        Timber.i("Riva config updated: host=%s:%d, ssl=%b, enabled=%b",
            host, port, useSsl, enabled)
    }

    /**
     * Clear all configuration and disable Riva.
     */
    fun clear() {
        prefs.edit().clear().apply()
        Timber.i("Riva config cleared")
    }

    /**
     * Get configuration summary for diagnostics (no secrets).
     */
    fun toSummaryMap(): Map<String, Any> = mapOf(
        "enabled" to isEnabled,
        "host" to serverHost,
        "port" to serverPort,
        "use_ssl" to useSsl,
        "is_cloud" to isCloud,
        "has_api_key" to apiKey.isNotEmpty(),
        "asr_language" to asrLanguageCode,
        "tts_voice" to ttsVoiceName,
        "has_asr_function_id" to asrFunctionId.isNotEmpty(),
        "has_tts_function_id" to ttsFunctionId.isNotEmpty()
    )
}
