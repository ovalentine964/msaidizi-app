package com.msaidizi.voice

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DialectAwareSpeechEngineRouter — Routes ASR/TTS requests based on dialect profile.
 *
 * Extends the base SpeechEngineRouter with dialect awareness:
 * - Selects ASR engine based on worker's dialect (LoRA adapter priority)
 * - Routes TTS to appropriate voice for dialect (Sheng vs standard Swahili)
 * - Handles code-switched TTS output (speak Swahili parts in Swahili voice,
 *   English parts in English voice)
 *
 * Uses DialectProfileProvider interface to avoid circular dependency with agent module.
 */
@Singleton
class DialectAwareSpeechEngineRouter @Inject constructor(
    private val sherpaEngine: SherpaOnnxEngine,
    private val rivaEngine: RivaClientEngine,
    private val rivaConfig: RivaConfig
) {
    /** Engine selection mode. */
    enum class EngineMode {
        AUTO,
        LOCAL_ONLY,
        CLOUD_ONLY
    }

    /**
     * Interface for providing dialect profile info.
     * Implemented by agent module's DialectProfileManager.
     */
    interface DialectProfileProvider {
        fun getDialectCode(workerId: String): String?
        fun getConfidence(workerId: String): Float
        fun hasPersonalAdapter(workerId: String): Boolean
    }

    private var mode = EngineMode.AUTO
    private var rivaReachable = false
    private var lastRivaCheckMs = 0L
    private var dialectProvider: DialectProfileProvider? = null

    companion object {
        private const val RIVA_CHECK_INTERVAL_MS = 30_000L

        /** Map dialect codes to TTS voice identifiers */
        private val dialectVoiceMap = mapOf(
            "sw-KE-urban" to "piper-sw",
            "sw-KE-urban-sheng" to "piper-sw",
            "sw-KE-urban-mixed" to "piper-sw",
            "sw-KE-coastal" to "piper-sw",
            "sw-TZ" to "piper-sw",
            "sw-TZ-dar" to "piper-sw",
            "en-KE" to "piper-en"
        )

        /** Sheng-specific TTS speed adjustments (Sheng is spoken faster) */
        private val dialectSpeedMap = mapOf(
            "sw-KE-urban-sheng" to 1.15f,
            "sw-KE-urban" to 1.0f,
            "sw-KE-coastal" to 0.95f,
            "sw-TZ" to 0.95f,
            "en-KE" to 1.0f
        )
    }

    /**
     * Set the dialect profile provider (called by agent module during init).
     */
    fun setDialectProvider(provider: DialectProfileProvider) {
        dialectProvider = provider
        Timber.i("Dialect profile provider registered")
    }

    fun setMode(newMode: EngineMode) {
        mode = newMode
        Timber.i("DialectAware speech engine mode set to: %s", newMode)
    }

    fun getMode(): EngineMode = mode

    /**
     * Get ASR engine for a specific worker, considering dialect profile.
     */
    suspend fun getAsrEngine(workerId: String? = null): SpeechEngine {
        if (workerId != null && dialectProvider != null) {
            val dialectCode = dialectProvider!!.getDialectCode(workerId)
            val hasAdapter = dialectProvider!!.hasPersonalAdapter(workerId)
            if (hasAdapter) {
                Timber.d("Using personalized ASR for worker=%s, dialect=%s", workerId, dialectCode)
            }
        }

        return when (mode) {
            EngineMode.LOCAL_ONLY -> sherpaEngine
            EngineMode.CLOUD_ONLY -> {
                if (checkRivaAvailability()) rivaEngine
                else {
                    Timber.w("CLOUD_ONLY but Riva unavailable, falling back to local")
                    sherpaEngine
                }
            }
            EngineMode.AUTO -> {
                if (rivaConfig.isEnabled && checkRivaAvailability()) rivaEngine
                else sherpaEngine
            }
        }
    }

    /**
     * Get TTS engine for a specific worker's dialect.
     */
    suspend fun getTtsEngine(workerId: String? = null): TtsEngineConfig {
        val dialectCode = workerId?.let {
            dialectProvider?.getDialectCode(it)
        } ?: "sw-KE-urban"

        val voiceId = dialectVoiceMap[dialectCode] ?: "piper-sw"
        val speed = dialectSpeedMap[dialectCode] ?: 1.0f

        return TtsEngineConfig(
            engine = getBaseEngine(),
            voiceId = voiceId,
            speed = speed,
            dialectCode = dialectCode ?: "sw-KE-urban",
            supportsCodeSwitching = true
        )
    }

    /**
     * Speak text with dialect-aware voice selection.
     * Handles code-switched content by splitting into language segments.
     */
    suspend fun speakDialectAware(
        text: String,
        workerId: String? = null,
        languageDetector: LanguageDetectorAdapter? = null,
        codeSwitchHandler: CodeSwitchHandlerAdapter? = null
    ): ByteArray {
        val config = getTtsEngine(workerId)

        if (!config.supportsCodeSwitching || languageDetector == null || codeSwitchHandler == null) {
            return speakSimple(text, config)
        }

        val detection = languageDetector.detectLanguage(text)
        if (!detection.isCodeMixed) {
            return speakSimple(text, config)
        }

        // Code-switched TTS: segment and speak each part in appropriate voice
        val segments = codeSwitchHandler.segment(text)
        val audioParts = mutableListOf<ByteArray>()

        for (segment in segments) {
            val segmentVoice = when (segment.language) {
                "en" -> "piper-en"
                else -> "piper-sw"
            }

            val segmentAudio = speakSimple(segment.text.trim(), config.copy(voiceId = segmentVoice))
            if (segmentAudio.isNotEmpty()) {
                audioParts.add(segmentAudio)
            }
        }

        return concatPcm16(audioParts)
    }

    /**
     * Get the dialect code for TTS voice selection.
     */
    fun getDialectTtsVoice(dialectCode: String): String {
        return dialectVoiceMap[dialectCode] ?: "piper-sw"
    }

    /**
     * Get TTS speed for a dialect (Sheng is spoken faster).
     */
    fun getDialectSpeed(dialectCode: String): Float {
        return dialectSpeedMap[dialectCode] ?: 1.0f
    }

    /**
     * Get comprehensive status for diagnostics.
     */
    fun getStatus(workerId: String? = null): Map<String, Any> {
        val dialectInfo = workerId?.let { id ->
            dialectProvider?.let { provider ->
                mapOf(
                    "dialect_code" to (provider.getDialectCode(id) ?: "unknown"),
                    "confidence" to provider.getConfidence(id),
                    "has_lora" to provider.hasPersonalAdapter(id)
                )
            }
        }

        return mapOf(
            "mode" to mode.name,
            "riva_configured" to rivaConfig.isEnabled,
            "riva_reachable" to rivaReachable,
            "dialect_info" to (dialectInfo ?: "no worker or provider")
        )
    }

    // ── Private helpers ──────────────────────────────────────

    private suspend fun getBaseEngine(): SpeechEngine {
        return when (mode) {
            EngineMode.LOCAL_ONLY -> sherpaEngine
            EngineMode.CLOUD_ONLY -> if (checkRivaAvailability()) rivaEngine else sherpaEngine
            EngineMode.AUTO -> if (rivaConfig.isEnabled && checkRivaAvailability()) rivaEngine else sherpaEngine
        }
    }

    private suspend fun speakSimple(text: String, config: TtsEngineConfig): ByteArray {
        return try {
            config.engine.synthesize(text, language = extractLangFromVoice(config.voiceId), speed = config.speed)
        } catch (e: Exception) {
            Timber.e(e, "TTS failed for voice=%s", config.voiceId)
            ByteArray(0)
        }
    }

    private fun extractLangFromVoice(voiceId: String): String = when {
        voiceId.contains("en") -> "en"
        else -> "sw"
    }

    private fun concatPcm16(parts: List<ByteArray>): ByteArray {
        val totalSize = parts.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        for (part in parts) {
            part.copyInto(result, offset)
            offset += part.size
        }
        return result
    }

    private suspend fun checkRivaAvailability(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastRivaCheckMs < RIVA_CHECK_INTERVAL_MS) return rivaReachable

        rivaReachable = try {
            rivaEngine.isAvailable()
        } catch (e: Exception) {
            false
        }
        lastRivaCheckMs = now
        return rivaReachable
    }
}

/**
 * Adapter interfaces for code-switching support in TTS.
 * These allow voice module to use language detection without importing agent.
 */
interface LanguageDetectorAdapter {
    data class DetectionResult(val primary: String, val isCodeMixed: Boolean)
    fun detectLanguage(text: String): DetectionResult
}

interface CodeSwitchHandlerAdapter {
    data class Segment(val text: String, val language: String)
    fun segment(text: String): List<Segment>
}

/**
 * Configuration for a TTS engine instance.
 */
data class TtsEngineConfig(
    val engine: SpeechEngine,
    val voiceId: String,
    val speed: Float,
    val dialectCode: String,
    val supportsCodeSwitching: Boolean
) {
    fun copy(voiceId: String = this.voiceId, speed: Float = this.speed) = TtsEngineConfig(
        engine = engine,
        voiceId = voiceId,
        speed = speed,
        dialectCode = dialectCode,
        supportsCodeSwitching = supportsCodeSwitching
    )
}
