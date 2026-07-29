package com.msaidizi.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes speech requests to the best available engine.
 *
 * Selection logic:
 * 1. If user explicitly selected an engine → use that
 * 2. If Riva is configured and reachable → prefer Riva (better quality)
 * 3. Otherwise → use sherpa-onnx (on-device, always available)
 *
 * Riva availability is cached and re-checked periodically to avoid
 * blocking every request with a network probe.
 */
@Singleton
class SpeechEngineRouter @Inject constructor(
    private val sherpaEngine: SherpaOnnxEngine,
    private val rivaEngine: RivaClientEngine,
    private val rivaConfig: RivaConfig
) {
    /** Engine selection mode. */
    enum class EngineMode {
        /** Automatically select best available engine. */
        AUTO,
        /** Force on-device sherpa-onnx (no network). */
        LOCAL_ONLY,
        /** Force Riva cloud/server (fail if unavailable). */
        CLOUD_ONLY
    }

    private var mode = EngineMode.AUTO
    private var rivaReachable = false
    private var lastRivaCheckMs = 0L

    companion object {
        /** How often to re-check Riva availability in AUTO mode. */
        private const val RIVA_CHECK_INTERVAL_MS = 30_000L

        /** Timeout for Riva availability check. */
        private const val RIVA_CHECK_TIMEOUT_MS = 3_000L
    }

    /**
     * Set the engine selection mode.
     */
    fun setMode(newMode: EngineMode) {
        mode = newMode
        Timber.i("Speech engine mode set to: %s", newMode)
    }

    /**
     * Get the current engine mode.
     */
    fun getMode(): EngineMode = mode

    /**
     * Get the active ASR engine based on current mode and availability.
     */
    suspend fun getAsrEngine(): SpeechEngine {
        return when (mode) {
            EngineMode.LOCAL_ONLY -> sherpaEngine
            EngineMode.CLOUD_ONLY -> {
                if (checkRivaAvailability()) rivaEngine
                else {
                    Timber.w("CLOUD_ONLY mode but Riva unavailable, falling back to local")
                    sherpaEngine
                }
            }
            EngineMode.AUTO -> {
                if (rivaConfig.isEnabled && checkRivaAvailability()) {
                    rivaEngine
                } else {
                    sherpaEngine
                }
            }
        }
    }

    /**
     * Get the active TTS engine based on current mode and availability.
     * Same routing logic as ASR.
     */
    suspend fun getTtsEngine(): SpeechEngine = getAsrEngine()

    /**
     * Check if Riva is currently reachable (cached).
     */
    private suspend fun checkRivaAvailability(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastRivaCheckMs < RIVA_CHECK_INTERVAL_MS) {
            return rivaReachable
        }

        rivaReachable = withContext(Dispatchers.IO) {
            try {
                rivaEngine.isAvailable()
            } catch (e: Exception) {
                Timber.w(e, "Riva availability check failed")
                false
            }
        }
        lastRivaCheckMs = now

        Timber.d("Riva availability: %b", rivaReachable)
        return rivaReachable
    }

    /**
     * Force a fresh Riva availability check (e.g., after config change).
     */
    suspend fun refreshRivaAvailability(): Boolean {
        lastRivaCheckMs = 0L
        return checkRivaAvailability()
    }

    /**
     * Get comprehensive status for diagnostics UI.
     */
    fun getStatus(): Map<String, Any> = mapOf(
        "mode" to mode.name,
        "riva_configured" to rivaConfig.isEnabled,
        "riva_reachable" to rivaReachable,
        "riva_is_cloud" to rivaConfig.isCloud,
        "riva_host" to rivaConfig.serverHost,
        "effective_asr_engine" to when {
            mode == EngineMode.LOCAL_ONLY -> "sherpa-onnx"
            mode == EngineMode.CLOUD_ONLY && rivaReachable -> "riva"
            mode == EngineMode.CLOUD_ONLY -> "sherpa-onnx (fallback)"
            rivaConfig.isEnabled && rivaReachable -> "riva"
            else -> "sherpa-onnx"
        }
    )

    /**
     * Release resources held by both engines.
     */
    fun release() {
        rivaEngine.release()
        sherpaEngine.release()
    }
}
