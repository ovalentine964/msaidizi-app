package com.msaidizi.app.voice.integration

import com.msaidizi.app.core.util.DeviceTier
import com.msaidizi.app.voice.sts.StsProvider
import com.msaidizi.app.voice.sts.StsProviderInfo
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registry for voice model providers — manages model selection and lifecycle.
 *
 * Supports multiple voice model types:
 * 1. **On-device ASR**: Whisper Tiny INT4, Whisper Small, etc.
 * 2. **On-device TTS**: Piper, Meta MMS, VITS
 * 3. **On-device LLM**: Qwen 0.5B, Qwen 2.5 0.5B, etc.
 * 4. **Cloud STS**: GPT-Realtime-2, ElevenLabs v3
 * 5. **Cloud ASR/TTS**: Deepgram, Google, Azure
 *
 * Model selection strategy:
 * - **Offline-first**: Prefer on-device models, fallback to cloud
 * - **Quality-first**: Use best available model regardless of location
 * - **Cost-optimized**: Minimize API costs
 * - **Latency-optimized**: Minimize response time
 *
 * @see ModelProvider for the provider interface
 * @see VoiceModelType for supported model types
 */
@Singleton
class VoiceModelRegistry @Inject constructor() {

    companion object {
        private const val TAG = "VoiceModelRegistry"
    }

    /** Registered model providers by type */
    private val providers = mutableMapOf<VoiceModelType, MutableList<ModelProvider>>()

    /** Active provider selections */
    private val activeProviders = mutableMapOf<VoiceModelType, ModelProvider>()

    /** Selection strategy */
    private var strategy = ModelSelectionStrategy.OFFLINE_FIRST

    /** STS provider registry (delegates to STS engine) */
    private val stsProviders = mutableMapOf<String, StsProvider>()

    // ────────────────────── Registration ──────────────────────

    /**
     * Register a model provider.
     */
    fun registerProvider(type: VoiceModelType, provider: ModelProvider) {
        providers.getOrPut(type) { mutableListOf() }.add(provider)
        Timber.tag(TAG).d("Registered %s provider: %s (%s)", type, provider.id, provider.name)
    }

    /**
     * Register an STS provider.
     */
    fun registerStsProvider(provider: StsProvider) {
        stsProviders[provider.id] = provider
        Timber.tag(TAG).d("Registered STS provider: %s", provider.id)
    }

    // ────────────────────── Provider Selection ──────────────────────

    /**
     * Get the best provider for a given model type.
     *
     * @param type Model type needed
     * @param language Target language
     * @param preferOnline Whether to prefer cloud providers
     * @return Best available provider, or null
     */
    fun getBestProvider(
        type: VoiceModelType,
        language: String = "sw",
        preferOnline: Boolean = false
    ): ModelProvider? {
        val candidates = providers[type]?.filter { provider ->
            provider.isAvailable() && language in provider.supportedLanguages
        } ?: return null

        if (candidates.isEmpty()) return null

        return when (strategy) {
            ModelSelectionStrategy.OFFLINE_FIRST -> {
                candidates.firstOrNull { !it.requiresNetwork }
                    ?: candidates.first()
            }
            ModelSelectionStrategy.QUALITY_FIRST -> {
                candidates.maxByOrNull { it.qualityScore }
            }
            ModelSelectionStrategy.COST_OPTIMIZED -> {
                candidates.minByOrNull { it.costPerMinute }
            }
            ModelSelectionStrategy.LATENCY_OPTIMIZED -> {
                candidates.minByOrNull { it.averageLatencyMs }
            }
        }
    }

    /**
     * Get the best STS provider for speech-to-speech.
     */
    fun getBestStsProvider(
        language: String = "sw",
        preferredId: String? = null
    ): StsProvider? {
        if (preferredId != null) {
            stsProviders[preferredId]?.let { provider ->
                if (provider.isAvailable() && language in provider.supportedLanguages) {
                    return provider
                }
            }
        }

        return stsProviders.values.filter { provider ->
            provider.isAvailable() && language in provider.supportedLanguages
        }.minByOrNull { it.getAverageLatencyMs() }
    }

    /**
     * Get all available providers for a model type.
     */
    fun getAvailableProviders(type: VoiceModelType, language: String = "sw"): List<ModelProviderInfo> {
        return providers[type]
            ?.filter { it.isAvailable() && language in it.supportedLanguages }
            ?.map { provider ->
                ModelProviderInfo(
                    id = provider.id,
                    name = provider.name,
                    type = type,
                    isAvailable = true,
                    requiresNetwork = provider.requiresNetwork,
                    supportedLanguages = provider.supportedLanguages,
                    qualityScore = provider.qualityScore,
                    averageLatencyMs = provider.averageLatencyMs,
                    costPerMinute = provider.costPerMinute
                )
            } ?: emptyList()
    }

    /**
     * Get all available STS providers.
     */
    fun getAvailableStsProviders(): List<StsProviderInfo> {
        return stsProviders.values.map { provider ->
            StsProviderInfo(
                id = provider.id,
                name = provider.name,
                isAvailable = provider.isAvailable(),
                supportedLanguages = provider.supportedLanguages,
                averageLatencyMs = provider.getAverageLatencyMs(),
                isOnline = provider.requiresNetwork
            )
        }
    }

    // ────────────────────── Configuration ──────────────────────

    /**
     * Set the model selection strategy.
     */
    fun setStrategy(newStrategy: ModelSelectionStrategy) {
        strategy = newStrategy
        Timber.tag(TAG).i("Model selection strategy: %s", newStrategy)
    }

    /**
     * Force a specific provider for a model type.
     */
    fun setActiveProvider(type: VoiceModelType, provider: ModelProvider) {
        activeProviders[type] = provider
        Timber.tag(TAG).i("Active %s provider: %s", type, provider.id)
    }

    /**
     * Get the current strategy.
     */
    fun getStrategy(): ModelSelectionStrategy = strategy

    /**
     * Get registry status.
     */
    fun getStatus(): Map<String, Any> = mapOf(
        "strategy" to strategy.name,
        "registeredTypes" to providers.keys.map { it.name },
        "stsProviders" to stsProviders.size,
        "activeProviders" to activeProviders.mapKeys { it.key.name }.mapValues { it.value.id }
    )
}

// ════════════════════════════════════════════════════════════════════
// INTERFACES & DATA CLASSES
// ════════════════════════════════════════════════════════════════════

/**
 * Supported voice model types.
 */
enum class VoiceModelType {
    ASR,        // Speech-to-Text
    TTS,        // Text-to-Speech
    LLM,        // Language Model
    STS,        // Speech-to-Speech (combined)
    EMOTION,    // Emotion detection
    DIALECT     // Dialect detection
}

/**
 * Model selection strategy.
 */
enum class ModelSelectionStrategy {
    OFFLINE_FIRST,      // Prefer on-device, fallback to cloud
    QUALITY_FIRST,      // Best quality regardless of location
    COST_OPTIMIZED,     // Minimize API costs
    LATENCY_OPTIMIZED   // Minimize response time
}

/**
 * Interface for a model provider.
 */
interface ModelProvider {
    val id: String
    val name: String
    val type: VoiceModelType
    val requiresNetwork: Boolean
    val supportedLanguages: Set<String>
    val qualityScore: Float        // 0.0-1.0
    val averageLatencyMs: Long
    val costPerMinute: Float       // 0.0 for free/on-device

    fun isAvailable(): Boolean
}

/**
 * Information about a model provider.
 */
data class ModelProviderInfo(
    val id: String,
    val name: String,
    val type: VoiceModelType,
    val isAvailable: Boolean,
    val requiresNetwork: Boolean,
    val supportedLanguages: Set<String>,
    val qualityScore: Float,
    val averageLatencyMs: Long,
    val costPerMinute: Float
)

/**
 * Built-in model provider implementations.
 */
/**
 * Whisper Tiny INT4 — PRIMARY ASR for Msaidizi.
 *
 * ~40MB, fits on 2GB phones. Best accuracy-per-MB for African languages.
 * WAXAL fine-tuned for Swahili dialect accuracy.
 *
 * ⚠️ Do NOT default to Turbo (~150MB) — Msaidizi's users have $50 phones.
 */
class WhisperTinyAsrProvider : ModelProvider {
    override val id = "whisper-tiny-int4"
    override val name = "Whisper Tiny INT4 (primary)"
    override val type = VoiceModelType.ASR
    override val requiresNetwork = false
    override val supportedLanguages = setOf("sw", "en", "sheng", "yo", "ha", "am", "zu", "xh", "ig", "so")
    override val qualityScore = 0.85f  // Boosted by WAXAL fine-tuning
    override val averageLatencyMs = 300L
    override val costPerMinute = 0f
    override fun isAvailable() = true
}

class MoonshineAsrProvider : ModelProvider {
    override val id = "moonshine-tiny"
    override val name = "Moonshine Tiny (mobile/edge)"
    override val type = VoiceModelType.ASR
    override val requiresNetwork = false
    override val supportedLanguages = setOf("sw", "en", "sheng", "yo", "ha", "am", "zu", "xh", "ig")
    override val qualityScore = 0.80f
    override val averageLatencyMs = 100L
    override val costPerMinute = 0f
    override fun isAvailable() = true
}

/**
 * Whisper Turbo — HIGH-END DEVICES ONLY (4GB+ RAM).
 *
 * ~150MB. Near large-v3 accuracy. Too large for $50 phones.
 * Only used when DeviceTier is HIGH.
 */
class WhisperTurboAsrProvider : ModelProvider {
    override val id = "whisper-turbo"
    override val name = "Whisper Turbo (high-end only)"
    override val type = VoiceModelType.ASR
    override val requiresNetwork = false
    override val supportedLanguages = setOf("sw", "en", "sheng", "yo", "ha", "am", "zu", "xh", "ig", "so")
    override val qualityScore = 0.92f
    override val averageLatencyMs = 200L
    override val costPerMinute = 0f
    override fun isAvailable() = com.msaidizi.app.core.util.DeviceTier.current == com.msaidizi.app.core.util.DeviceTier.HIGH
}

class KokoroTtsProvider : ModelProvider {
    override val id = "kokoro-swahili"
    override val name = "Kokoro TTS (82M, Apache 2.0)"
    override val type = VoiceModelType.TTS
    override val requiresNetwork = false
    override val supportedLanguages = setOf("sw", "en", "sheng")
    override val qualityScore = 0.90f
    override val averageLatencyMs = 300L
    override val costPerMinute = 0f
    override fun isAvailable() = true
}

class PiperTtsProvider : ModelProvider {
    override val id = "piper-swahili"
    override val name = "Piper TTS (fallback)"
    override val type = VoiceModelType.TTS
    override val requiresNetwork = false
    override val supportedLanguages = setOf("sw", "en", "sheng")
    override val qualityScore = 0.75f
    override val averageLatencyMs = 400L
    override val costPerMinute = 0f
    override fun isAvailable() = true
}

class MmsTtsProvider : ModelProvider {
    override val id = "meta-mms"
    override val name = "Meta MMS TTS"
    override val type = VoiceModelType.TTS
    override val requiresNetwork = false
    override val supportedLanguages = setOf("sw", "yo", "ha", "am", "zu", "xh", "ig")
    override val qualityScore = 0.70f
    override val averageLatencyMs = 600L
    override val costPerMinute = 0f
    override fun isAvailable() = true
}

class QwenLlmProvider : ModelProvider {
    override val id = "qwen-3.5-0.8b-q4km"
    override val name = "Qwen 3.5 0.8B Q4 (llama.cpp)"
    override val type = VoiceModelType.LLM
    override val requiresNetwork = false
    override val supportedLanguages = setOf("sw", "en", "sheng")
    override val qualityScore = 0.70f
    override val averageLatencyMs = 400L
    override val costPerMinute = 0f
    override fun isAvailable() = true
}
