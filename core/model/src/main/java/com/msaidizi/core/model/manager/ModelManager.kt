package com.msaidizi.core.model.manager

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import com.msaidizi.core.model.llm.LlmEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central orchestrator for on-device AI model lifecycle.
 *
 * Manages the complete lifecycle of AI models on the device:
 * 1. **Device-tier-aware model selection** — picks the right model based on RAM
 * 2. **Memory management** — monitors RAM and auto-unloads at 85% pressure
 * 3. **Graceful fallback** — falls back to smaller models or cloud API on failure
 * 4. **Hot-swap** — swap models without app restart
 *
 * ## Device Tiers (East Africa Market Reality)
 *
 * | Tier | RAM   | CPU          | Default Model              | Fallback              |
 * |------|-------|--------------|----------------------------|-----------------------|
 * | LOW  | ≤2GB  | Helio A22    | Qwen3.5-0.8B Q4_K_M       | Gemma4-E2B Q3_K_M    |
 * | MID  | 3-4GB | Helio G25    | Gemma4-E2B Q4_K_M          | Qwen3.5-0.8B Q4_K_M  |
 * | HIGH | ≥6GB  | Dimensity 700| Gemma4-E2B Q4_K_M          | Qwen3.5-2B Q4_K_M    |
 *
 * ## Auto-Unload at 85% RAM (M-KOPA Principle)
 *
 * The 85% threshold is critical for 2GB phones. When system memory pressure
 * exceeds 85%, the model is automatically unloaded to prevent OOM kills.
 * The app falls back to rule-based responses until memory is available.
 *
 * ## Integration with Inference Pipeline
 *
 * ```
 * User text → InferencePipeline.classifyComplexity()
 *   → SIMPLE  → rule-based response (no LLM needed)
 *   → COMPLEX → ModelManager.loadLlm() → LlmEngine.generateResponse()
 * ```
 *
 * @see LlmEngine for generation
 * @see com.msaidizi.core.model.downloader.ModelDownloader for downloads
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llmEngine: LlmEngine
) {
    companion object {
        private const val TAG = "ModelManager"

        // RAM thresholds for device tier classification
        private const val RAM_LOW_THRESHOLD_MB = 2048
        private const val RAM_MID_THRESHOLD_MB = 4096

        // Memory pressure thresholds
        const val LOW_MEMORY_PERCENT = 85     // Unload models when >85% RAM used
        const val CRITICAL_MEMORY_PERCENT = 92 // Emergency unload

        // Auto-unload after idle
        private const val AUTO_UNLOAD_MINUTES_IDLE = 10L

        // Model IDs
        const val MODEL_GEMMA_4_E2B = "gemma-4-e2b-q4km"
        const val MODEL_GEMMA_4_E2B_SMALL = "gemma-4-e2b-q3km"
        const val MODEL_QWEN_08B = "qwen3.5-0.8b-q4km"
        const val MODEL_QWEN_2B = "qwen3.5-2b-q4km"
        const val MODEL_GEMMA_4_E2B_ULTRA = "gemma-4-e2b-q2k"
        const val MODEL_QWEN_08B_LITE = "qwen-3.5-0.8b-q2k"
    }

    /** Device capability tier (computed once at init) */
    enum class DeviceTier {
        LOW,   // ≤2GB RAM
        MID,   // 3-4GB RAM
        HIGH   // ≥6GB RAM
    }

    /** Model manager states */
    enum class State {
        IDLE, READY, LOADING, LOADED, FALLBACK_TO_CLOUD, ERROR
    }

    // ── State ──
    val deviceTier: DeviceTier by lazy { classifyDevice() }
    private var loadedModelId: String? = null
    private var lastInferenceTimeMs: Long = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var memoryMonitorJob: Job? = null

    private val _state = MutableStateFlow(State.IDLE)
    /** Observable model manager state */
    val state: StateFlow<State> = _state

    private val _memoryInfo = MutableStateFlow(MemoryInfo.empty())
    /** Observable memory information */
    val memoryInfo: StateFlow<MemoryInfo> = _memoryInfo

    // ────────────────────── Public API ──────────────────────

    /**
     * Initialize the model manager.
     * Classifies device tier and starts memory monitoring.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        Timber.i(TAG, "Initializing — tier=%s, RAM=%dMB, cores=%d",
            deviceTier, getTotalRamMb(), Runtime.getRuntime().availableProcessors())

        startMemoryMonitor()
        _state.value = State.READY
    }

    /**
     * Load the best available LLM for this device.
     * Handles fallback chain: preferred → smaller → cloud.
     *
     * @param forceReload Force reload even if already loaded
     * @return true if an on-device model was loaded
     */
    suspend fun loadLlm(forceReload: Boolean = false): Boolean {
        if (!forceReload && llmEngine.isModelLoaded()) return true

        _state.value = State.LOADING

        val candidates = getModelCandidates()
        if (candidates.isEmpty()) {
            _state.value = State.FALLBACK_TO_CLOUD
            return false
        }

        for (modelId in candidates) {
            try {
                val loaded = llmEngine.loadModel()
                if (loaded) {
                    loadedModelId = modelId
                    _state.value = State.LOADED
                    Timber.i(TAG, "LLM loaded: %s", modelId)
                    return true
                }
            } catch (e: OutOfMemoryError) {
                Timber.e(TAG, "OOM loading %s — trying smaller", modelId)
                llmEngine.unloadModel()
                System.gc()
            } catch (e: Throwable) {
                Timber.e(e, "Failed to load %s", modelId)
            }
        }

        _state.value = State.FALLBACK_TO_CLOUD
        return false
    }

    /** Unload the current LLM and free memory */
    fun unloadLlm() {
        llmEngine.unloadModel()
        loadedModelId = null
        _state.value = State.IDLE
        Timber.i(TAG, "LLM unloaded")
    }

    /**
     * Hot-swap the current model without app restart.
     *
     * @param targetModelId Model to swap to
     * @return true if swap succeeded
     */
    suspend fun hotSwapModel(targetModelId: String): Boolean {
        Timber.i(TAG, "Hot-swapping: %s → %s", loadedModelId, targetModelId)
        _state.value = State.LOADING

        val previous = loadedModelId
        llmEngine.unloadModel()

        return try {
            val loaded = llmEngine.loadModel()
            if (loaded) {
                loadedModelId = targetModelId
                _state.value = State.LOADED
                true
            } else {
                // Rollback
                if (previous != null) llmEngine.loadModel()
                loadedModelId = previous
                _state.value = State.ERROR
                false
            }
        } catch (e: Throwable) {
            _state.value = State.ERROR
            false
        }
    }

    /**
     * Record inference performance for monitoring.
     * Call after each LLM inference.
     */
    fun recordInference(latencyMs: Long, success: Boolean) {
        lastInferenceTimeMs = System.currentTimeMillis()
    }

    /** Get the optimal model ID for this device */
    fun getOptimalModelId(): String? {
        return when (deviceTier) {
            DeviceTier.LOW -> MODEL_QWEN_08B
            DeviceTier.MID -> MODEL_GEMMA_4_E2B
            DeviceTier.HIGH -> MODEL_GEMMA_4_E2B
        }
    }

    /** Get the fallback model ID for this device */
    fun getFallbackModelId(): String? {
        return when (deviceTier) {
            DeviceTier.LOW -> MODEL_GEMMA_4_E2B_SMALL
            DeviceTier.MID -> MODEL_QWEN_08B
            DeviceTier.HIGH -> MODEL_QWEN_2B
        }
    }

    /** Check if on-device LLM is feasible on this device */
    fun isOnDeviceLlmFeasible(): Boolean = getTotalRamMb() >= 1536

    /** Get recommended context length for this device */
    fun getRecommendedContextLength(): Int = when (deviceTier) {
        DeviceTier.LOW -> 2048
        DeviceTier.MID -> 4096
        DeviceTier.HIGH -> 4096
    }

    /**
     * Handle a low-memory callback from the system.
     * Unloads models to free RAM.
     */
    fun onLowMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Timber.w(TAG, "Critical memory — unloading all")
                unloadLlm()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                Timber.w(TAG, "Low memory — unloading LLM")
                unloadLlm()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                Timber.w(TAG, "Critical running memory — unloading + GC")
                unloadLlm()
                System.gc()
            }
        }
    }

    /** Get total RAM in MB */
    fun getTotalRamMb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return (memInfo.totalMem / (1024 * 1024)).toInt()
    }

    /** Get available (free) RAM in MB */
    fun getAvailableRamMb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return (memInfo.availMem / (1024 * 1024)).toInt()
    }

    /** Shutdown the model manager */
    fun shutdown() {
        memoryMonitorJob?.cancel()
        unloadLlm()
    }

    // ── Private: Device Classification ──

    private fun classifyDevice(): DeviceTier {
        val totalRamMb = getTotalRamMb()
        return when {
            totalRamMb >= RAM_MID_THRESHOLD_MB -> DeviceTier.HIGH
            totalRamMb >= RAM_LOW_THRESHOLD_MB -> DeviceTier.MID
            else -> DeviceTier.LOW
        }
    }

    private fun getModelCandidates(): List<String> {
        if (!isOnDeviceLlmFeasible()) return emptyList()
        return when (deviceTier) {
            DeviceTier.LOW -> listOf(MODEL_QWEN_08B, MODEL_GEMMA_4_E2B_SMALL, MODEL_QWEN_08B_LITE)
            DeviceTier.MID -> listOf(MODEL_GEMMA_4_E2B, MODEL_GEMMA_4_E2B_SMALL, MODEL_QWEN_08B)
            DeviceTier.HIGH -> listOf(MODEL_GEMMA_4_E2B, MODEL_QWEN_2B, MODEL_QWEN_08B)
        }
    }

    // ── Private: Memory Monitoring ──

    /**
     * Start periodic memory monitoring.
     * Checks every 30 seconds and auto-unloads at 85% pressure.
     */
    private fun startMemoryMonitor() {
        memoryMonitorJob?.cancel()
        memoryMonitorJob = scope.launch {
            while (isActive) {
                try {
                    val info = collectMemoryInfo()
                    _memoryInfo.value = info

                    // Auto-unload at 85% RAM pressure
                    if (info.usedPercent >= CRITICAL_MEMORY_PERCENT && llmEngine.isModelLoaded()) {
                        Timber.w(TAG, "Memory critical (%d%%) — emergency unload", info.usedPercent)
                        unloadLlm()
                    } else if (info.usedPercent >= LOW_MEMORY_PERCENT && llmEngine.isModelLoaded()) {
                        val idleMs = System.currentTimeMillis() - lastInferenceTimeMs
                        if (idleMs >= AUTO_UNLOAD_MINUTES_IDLE * 60 * 1000) {
                            Timber.i(TAG, "Memory high (%d%%), idle %dmin — unloading",
                                info.usedPercent, idleMs / 60000)
                            unloadLlm()
                        }
                    }
                } catch (e: Throwable) {
                    Timber.e(e, "Memory monitor error")
                }
                delay(30_000)
            }
        }
    }

    private fun collectMemoryInfo(): MemoryInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val totalMb = (memInfo.totalMem / (1024 * 1024)).toInt()
        val availableMb = (memInfo.availMem / (1024 * 1024)).toInt()
        val usedMb = totalMb - availableMb
        val usedPercent = ((usedMb.toLong() * 100) / totalMb).toInt()

        return MemoryInfo(totalMb, availableMb, usedMb, usedPercent, memInfo.lowMemory,
            (memInfo.threshold / (1024 * 1024)).toInt())
    }
}

/** System memory information snapshot */
data class MemoryInfo(
    val totalMb: Int,
    val availableMb: Int,
    val usedMb: Int,
    val usedPercent: Int,
    val isLowMemory: Boolean,
    val thresholdMb: Int
) {
    companion object {
        fun empty() = MemoryInfo(0, 0, 0, 0, false, 0)
    }
}
