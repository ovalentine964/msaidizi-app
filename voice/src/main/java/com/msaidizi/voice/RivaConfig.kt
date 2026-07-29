package com.msaidizi.voice

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Configuration for Riva NIM server connection.
 *
 * Stores server endpoint, API key, and model preferences.
 * Uses standard SharedPreferences (EncryptedSharedPreferences dependency
 * not included in voice module to keep it lightweight for offline builds).
 *
 * NOTE: Riva is disabled by default — the app uses on-device SherpaOnnx.
 * This config is only used when cloud Riva is explicitly enabled.
 */
@Singleton
class RivaConfig @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "riva_config"
        private const val DEFAULT_GRPC_PORT = 50051
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

    /** Whether to use TLS (required for cloud, optional for self-hosted). */
    val useSsl: Boolean
        get() = prefs.getBoolean("use_ssl", false)

    /** ASR language code (e.g., "sw", "en", "sw-KE"). */
    val asrLanguageCode: String
        get() = prefs.getString("asr_language", "sw") ?: "sw"

    /** TTS voice name. */
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
        useSsl: Boolean = this.useSsl,
        asrLanguage: String = asrLanguageCode,
        ttsVoice: String = ttsVoiceName,
        enabled: Boolean = isEnabled
    ) {
        prefs.edit().apply {
            putString("server_host", host)
            putInt("server_port", port)
            putString("api_key", apiKey)
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
        "tts_voice" to ttsVoiceName
    )
}
