package com.msaidizi.app.core.ai
import android.content.ComponentCallbacks2

import android.app.ActivityManager
import android.content.Context
import com.msaidizi.app.core.util.DeviceCapability
import com.msaidizi.app.voice.LlmEngine
import com.msaidizi.app.voice.ModelRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.inject.Provider

/**
 * Central orchestrator for on-device AI model lifecycle.
 *
 * Responsibilities:
 * 1. **Device-aware model selection** — picks the right model variant based on
 *    available RAM, CPU cores, and storage.
 * 2. **Multi-backend support** — abstracts llama.cpp (GGUF), ONNX Runtime,
 *    and TFLite behind a unified inference interface.
 * 3. **Memory management** — monitors system memory and automatically unloads
 *    models when the device is under pressure.
 * 4. **Graceful fallback** — if the preferred model can't load (OOM, missing
 *    file), falls back to smaller variants or cloud API.
 * 5. **Model registry bridge** — coordinates with [ModelRegistry] for
 *    download/verification and [ModelDownloader] for background fetching.
 *
 * ## Device Tiers (Kenya/East Africa market reality)
 *
 * | Tier | RAM   | CPU          | Default Model              | Alt Model           |
 * |------|-------|--------------|----------------------------|---------------------|
 * | LOW  | ≤2GB  | Helio A22    | Qwen3.5-0.8B Q4_K_M       | — (Gemma too large) |
 * | MID  | 3-4GB | Helio G25    | Qwen3.5-0.8B Q4_K_M       | Gemma4-E2B Q4_K_M  |
 * | HIGH | ≥6GB  | Dimensity 700| Qwen3.5-2B Q4_K_M         | Gemma4-E2B Q4_K_M  |
 *
 * ## Decision Council Model Selection (updated 2026-07-16)
 *
 * - **Primary:** Gemma 4 E2B — promoted to primary text LLM (was vision-only)
 * - **Fallback:** Qwen 3.5 0.8B — lightweight fallback for memory-constrained devices
 * - **Future:** Tiny Aya Earth — placeholder for when Cohere releases it
 *
 * ## Gemma 4 E2B Promotion (2026-07-16)
 * Gemma 4 E2B promoted from multimodal-only to primary text LLM.
 * - MID/HIGH: Gemma 4 E2B is default for text + vision
 * - LOW: Gemma 4 E2B Q3_K_M as primary, Qwen fallback under memory pressure
 * - Memory pressure: auto-fallback to Qwen when available RAM < 500MB
 *
 * ## Integration Points
 *
 * - [LlmEngine] — primary inference engine (llama.cpp JNI)
 * - [LlamaCppEngine] — alternative engine with simpler API
 * - [ModelRegistry] — model download, verification, storage
 * - [ModelDownloader] — background download orchestration
 * - [com.msaidizi.app.agent.ModelRouter] — routes requests between on-device and cloud
 *
 * @see ModelDownloader for download orchestration
 * @see ModelRegistry for model file management
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRegistry: ModelRegistry,
    private val modelDownloader: ModelDownloader,
    private val llmEngine: LlmEngine,
    private val sequentialModelLoader: Provider<SequentialModelLoader>  // Provider to break circular dependency
) {
    companion object {
        private const val TAG = "ModelManager"

        // RAM thresholds (MB) for device tier classification
        private const val RAM_LOW_THRESHOLD_MB = 2048
        private const val RAM_MID_THRESHOLD_MB = 4096

        // Memory pressure thresholds
        private const val LOW_MEMORY_PERCENT = 85  // Unload models when >85% RAM used
        private const val CRITICAL_MEMORY_PERCENT = 92 // Emergency unload

        // ────────────────────── Model IDs ──────────────────────
        // Decision Council updated (2026-07-16):
        //   Primary:  Gemma 4 E2B — promoted to primary text LLM for all tiers
        //   Fallback: Qwen 3.5 0.8B — lightweight fallback for memory pressure
        //   Future:   Tiny Aya Earth — placeholder for Cohere release

        /** Gemma 4 E2B Q4_K_M — primary text+vision model for MID/HIGH tiers (~1.5GB) */
        private const val MODEL_GEMMA_4_E2B = "gemma-4-e2b-q4km"

        /** Gemma 4 E2B Q3_K_M — primary model for LOW-tier 2GB devices (~1.0GB) */
        private const val MODEL_GEMMA_4_E2B_SMALL = "gemma-4-e2b-q3km"

        /** Qwen 3.5 0.8B Q4_K_M — fallback model for memory-constrained scenarios (~580MB) */
        private const val MODEL_QWEN_08B = "qwen3.5-0.8b-q4km"

        /** Qwen 3.5 2B Q4_K_M — higher-quality fallback for HIGH-tier devices (~1.2GB) */
        private const val MODEL_QWEN_2B = "qwen3.5-2b-q4km"

        /** Gemma 4 E2B Q2_K — ultra-lite model for data-limited users (~650MB) */
        private const val MODEL_GEMMA_4_E2B_ULTRA = "gemma-4-e2b-q2k"

        /** Qwen 3.5 0.8B Q2_K — smallest viable LLM for initial download (~300MB) */
        private const val MODEL_QWEN_08B_LITE = "qwen-3.5-0.8b-q2k"

        /** Tiny Aya Earth — future placeholder (not yet available) */
        private const val MODEL_TINY_AYA_EARTH = "tiny-aya-earth"

        // Auto-unload timeout: unload model after this many minutes of inactivity
        private const val AUTO_UNLOAD_MINUTES_IDLE = 10L
    }

    // ────────────────────── Device Tier ──────────────────────

    /**
     * Classified device capability tier.
     * Used for automatic model selection and feature gating.
     */
    enum class DeviceTier {
        /** ≤2GB RAM — minimal models, aggressive memory management */
        LOW,
        /** 3-4GB RAM — standard models, normal memory management */
        MID,
        /** ≥6GB RAM — full models, relaxed memory management */
        HIGH
    }

    // ────────────────────── Model Backend Types ──────────────────────

    /**
     * Supported inference backends.
     * Each backend handles different model formats with different tradeoffs.
     */
    enum class ModelBackend {
        /** llama.cpp via JNI — GGUF format, best for LLMs */
        LLAMA_CPP,
        /** ONNX Runtime — ONNX format, used for Whisper, Piper, Silero */
        ONNX,
        /** TensorFlow Lite — TFLite format, fallback for older devices */
        TFLITE
    }

    // ────────────────────── State ──────────────────────

    /** Current device tier (computed once at init) */
    val deviceTier: DeviceTier by lazy { classifyDevice() }

    /** Currently loaded LLM model ID, null if no model loaded */
    private var loadedModelId: String? = null

    /** Backend used for the currently loaded model */
    private var activeBackend: ModelBackend? = null

    /** Timestamp of last inference (for auto-unload) */
    private var lastInferenceTimeMs: Long = 0L

    /** Coroutine scope for background tasks */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Whether memory monitoring is active */
    private var memoryMonitorJob: Job? = null

    private val _modelState = MutableStateFlow(ModelManagerState.IDLE)
    /** Observable state of the model manager */
    val modelState: StateFlow<ModelManagerState> = _modelState

    private val _memoryInfo = MutableStateFlow(MemoryInfo.empty())
    /** Observable memory information */
    val memoryInfo: StateFlow<MemoryInfo> = _memoryInfo

    // Track loaded models for multi-model scenarios
    private val loadedModels = ConcurrentHashMap<String, LoadedModelInfo>()

    // Performance monitoring — tracks latency and accuracy per model
    private val modelPerformance = ConcurrentHashMap<String, ModelPerformanceMetrics>()

    /**
     * Performance metrics for a loaded model.
     * Used for A/B testing and automatic model selection.
     */
    data class ModelPerformanceMetrics(
        val modelId: String,
        var totalInferences: Long = 0,
        var totalLatencyMs: Long = 0,
        var errorCount: Long = 0,
        var lastInferenceMs: Long = 0,
        var avgLatencyMs: Double = 0.0,
        var p95LatencyMs: Long = 0
    ) {
        fun recordInference(latencyMs: Long, success: Boolean) {
            totalInferences++
            if (success) {
                totalLatencyMs += latencyMs
                avgLatencyMs = totalLatencyMs.toDouble() / totalInferences
                // Simple P95 approximation: track max recent latency
                if (latencyMs > p95LatencyMs) p95LatencyMs = latencyMs
            } else {
                errorCount++
            }
            lastInferenceMs = System.currentTimeMillis()
        }

        fun errorRate(): Double = if (totalInferences > 0) errorCount.toDouble() / totalInferences else 0.0
    }

    // ────────────────────── Public API ──────────────────────

    /**
     * Initialize the model manager.
     * Call once at app startup (e.g., from Application.onCreate or a Hilt initializer).
     *
     * - Classifies device tier
     * - Starts memory monitoring
     * - Pre-warms model availability check
     * - Triggers download of essential models if missing
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        Timber.i(
            TAG, "Initializing — device tier=%s, RAM=%dMB, cores=%d",
            deviceTier, getTotalRamMb(), Runtime.getRuntime().availableProcessors()
        )

        // Check which models are available
        val available = modelRegistry.getAvailableModels()
        val essential = getEssentialModels()
        val missing = essential.filter { it !in available }

        if (missing.isNotEmpty()) {
            Timber.i(TAG, "Missing essential models: %s — triggering download", missing)
            modelDownloader.ensureEssentialModels()
        }

        // Start memory monitoring
        startMemoryMonitor()

        // Initialize sequential model loader for LOW-tier devices
        sequentialModelLoader.get().initialize(deviceTier)

        _modelState.value = ModelManagerState.READY
        Timber.i(TAG, "ModelManager initialized (tier=%s, sequential=%s)", deviceTier, deviceTier == DeviceTier.LOW)
    }

    /**
     * Hot-swap the current LLM model without app restart.
     * Unloads current model, loads new one, updates state atomically.
     *
     * @param targetModelId The model to swap to (e.g., MODEL_QWEN_08B or MODEL_GEMMA_4_E2B)
     * @return true if swap succeeded
     */
    suspend fun hotSwapModel(targetModelId: String): Boolean {
        Timber.i(TAG, "Hot-swapping model: %s → %s", loadedModelId, targetModelId)
        _modelState.value = ModelManagerState.LOADING

        // Unload current model
        val previousModel = loadedModelId
        llmEngine.unloadModel()
        loadedModels.remove(previousModel)

        // Load new model
        return try {
            val loaded = loadSpecificModel(targetModelId, ModelBackend.LLAMA_CPP)
            if (loaded) {
                loadedModelId = targetModelId
                activeBackend = ModelBackend.LLAMA_CPP
                _modelState.value = ModelManagerState.LOADED
                Timber.i(TAG, "Hot-swap complete: %s → %s", previousModel, targetModelId)
                true
            } else {
                // Rollback to previous model
                Timber.w(TAG, "Hot-swap failed, rolling back to %s", previousModel)
                if (previousModel != null) {
                    loadSpecificModel(previousModel, ModelBackend.LLAMA_CPP)
                    loadedModelId = previousModel
                }
                _modelState.value = ModelManagerState.ERROR
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Hot-swap failed")
            _modelState.value = ModelManagerState.ERROR
            false
        }
    }

    /**
     * Record inference performance for model monitoring.
     * Call after each LLM inference.
     */
    fun recordInferencePerformance(latencyMs: Long, success: Boolean) {
        val modelId = loadedModelId ?: return
        val metrics = modelPerformance.getOrPut(modelId) { ModelPerformanceMetrics(modelId) }
        metrics.recordInference(latencyMs, success)
        lastInferenceTimeMs = System.currentTimeMillis()
    }

    /**
     * Get performance metrics for all loaded models.
     */
    fun getModelPerformanceMetrics(): Map<String, ModelPerformanceMetrics> {
        return modelPerformance.toMap()
    }

    /**
     * Get the best model for this device based on performance history.
     * Falls back to device tier if no performance data available.
     */
    fun getBestModelForDevice(): String? {
        // Check if we have performance data
        val bestByPerformance = modelPerformance.values
            .filter { it.totalInferences >= 10 && it.errorRate() < 0.1 }
            .minByOrNull { it.avgLatencyMs }

        if (bestByPerformance != null) {
            return bestByPerformance.modelId
        }

        // Fallback to device tier
        return getOptimalModelId()
    }

    /**
     * Check if a specific model variant can fit in current memory.
     * Returns false if model would cause OOM.
     */
    fun canLoadModel(modelId: String): Boolean {
        val modelSizeMb = getModelSizeMb(modelId)
        val availableMb = getAvailableRamMb()
        // Reserve 300MB for system + app heap
        return availableMb - 300 >= modelSizeMb
    }

    /**
     * Get estimated model size in MB.
     * Now includes Q2_K ultra-lite variants for data-limited users.
     */
    private fun getModelSizeMb(modelId: String): Int {
        return when (modelId) {
            MODEL_GEMMA_4_E2B -> 1500       // ~1.5GB Q4_K_M (Gemma 4 E2B)
            MODEL_GEMMA_4_E2B_SMALL -> 1000 // ~1.0GB Q3_K_M (Gemma 4 E2B, LOW tier)
            MODEL_GEMMA_4_E2B_ULTRA -> 650  // ~650MB Q2_K (Gemma 4 E2B, data-saver)
            MODEL_QWEN_08B -> 580           // ~580MB Q4_K_M (Qwen3.5-0.8B)
            MODEL_QWEN_08B_LITE -> 300      // ~300MB Q2_K (Qwen3.5-0.8B, data-saver)
            MODEL_QWEN_2B -> 1200           // ~1.2GB Q4_K_M (Qwen3.5-2B)
            MODEL_TINY_AYA_EARTH -> 400     // ~400MB estimated (Tiny Aya Earth)
            else -> 500
        }
    }

    /**
     * Load the best available LLM for this device.
     * Handles fallback chain: preferred → smaller → cloud.
     * Now with graceful degradation for oversized models.
     *
     * On 32-bit devices, this immediately falls back to cloud — no model candidates.
     *
     * @param forceReload Force reload even if a model is already loaded
     * @return true if an on-device model was loaded successfully
     */
    suspend fun loadLlm(forceReload: Boolean = false): Boolean {
        if (!forceReload && llmEngine.isModelLoaded()) {
            Timber.d(TAG, "LLM already loaded")
            return true
        }

        _modelState.value = ModelManagerState.LOADING

        // Determine the best model for this device
        val candidateModels = getModelCandidates()

        // No candidates (32-bit device or insufficient RAM) — go straight to cloud
        if (candidateModels.isEmpty()) {
            val reason = if (DeviceCapability.is32BitDevice()) "32-bit device" else "insufficient RAM"
            Timber.i(TAG, "No on-device LLM candidates (%s) — using cloud API", reason)
            _modelState.value = ModelManagerState.FALLBACK_TO_CLOUD
            return false
        }

        for (modelId in candidateModels) {
            try {
                val loaded = loadSpecificModel(modelId, ModelBackend.LLAMA_CPP)
                if (loaded) {
                    loadedModelId = modelId
                    activeBackend = ModelBackend.LLAMA_CPP
                    _modelState.value = ModelManagerState.LOADED
                    Timber.i(TAG, "LLM loaded: %s (backend=%s)", modelId, activeBackend)
                    return true
                }
            } catch (e: OutOfMemoryError) {
                Timber.e(TAG, "OOM loading model %s — trying smaller variant", modelId)
                llmEngine.unloadModel()
                System.gc()
                continue
            } catch (e: Exception) {
                Timber.e(e, "Failed to load model %s", modelId)
                continue
            }
        }

        // All on-device models failed
        _modelState.value = ModelManagerState.FALLBACK_TO_CLOUD
        Timber.w(TAG, "No on-device LLM could be loaded — falling back to cloud")
        return false
    }

    /**
     * Unload the current LLM and free memory.
     * Safe to call even if no model is loaded.
     */
    fun unloadLlm() {
        llmEngine.unloadModel()
        loadedModelId = null
        activeBackend = null
        loadedModels.remove(MODEL_QWEN_08B)
        _modelState.value = ModelManagerState.IDLE
        Timber.i(TAG, "LLM unloaded")
    }

    /**
     * Get the optimal model ID for this device.
     * Returns the model that balances quality and memory constraints.
     *
     * Decision Council (2026-07-16): Gemma 4 E2B promoted to primary.
     * - LOW:  Gemma 4 E2B Q3_K_M primary (~1GB), Qwen 3.5 0.8B fallback
     * - MID:  Gemma 4 E2B Q4_K_M primary (~1.5GB), Qwen 3.5 0.8B fallback
     * - HIGH: Gemma 4 E2B Q4_K_M primary, Qwen 3.5 2B fallback
     *
     * Returns null on 32-bit devices (no on-device LLM available).
     */
    fun getOptimalModelId(): String? {
        if (DeviceCapability.is32BitDevice()) return null

        return when (deviceTier) {
            DeviceTier.LOW -> MODEL_GEMMA_4_E2B_SMALL  // Gemma 4 E2B Q3_K_M (~1GB) for 2GB devices
            DeviceTier.MID -> MODEL_GEMMA_4_E2B        // Gemma 4 E2B Q4_K_M (~1.5GB) primary
            DeviceTier.HIGH -> MODEL_GEMMA_4_E2B       // Gemma 4 E2B Q4_K_M primary
        }
    }

    /**
     * Get the alternative/fallback model ID for this device.
     *
     * Decision Council (2026-07-16): Qwen is now the fallback.
     * - LOW:  Qwen 3.5 0.8B (memory pressure fallback)
     * - MID:  Qwen 3.5 0.8B (memory pressure fallback)
     * - HIGH: Qwen 3.5 2B (higher quality fallback)
     *
     * Returns null on 32-bit devices.
     */
    fun getAlternativeModelId(): String? {
        if (DeviceCapability.is32BitDevice()) return null

        return when (deviceTier) {
            DeviceTier.LOW -> MODEL_QWEN_08B      // Qwen fallback for 2GB devices
            DeviceTier.MID -> MODEL_QWEN_08B       // Qwen fallback for MID tier
            DeviceTier.HIGH -> MODEL_QWEN_2B       // Qwen 2B fallback for HIGH tier
        }
    }

    /**
     * Get the recommended context length for this device.
     * Updated (2026-07-16): expanded for Gemma 4 E2B promotion.
     * - LOW:  2048 tokens (expanded from 1024)
     * - MID:  4096 tokens (expanded from 2048)
     * - HIGH: 4096 tokens
     */
    fun getRecommendedContextLength(): Int {
        return when (deviceTier) {
            DeviceTier.LOW -> 2048
            DeviceTier.MID -> 4096
            DeviceTier.HIGH -> 4096
        }
    }

    /**
     * Check if on-device LLM inference is feasible on this device.
     * Returns false for 32-bit devices (address space too small) and very low RAM devices.
     *
     * Updated: 32-bit (armeabi-v7a) devices are explicitly blocked.
     * Even though the native code compiles for armeabi-v7a, the process address
     * space (~1.5GB usable) cannot hold llama.cpp models (580MB+).
     */
    fun isOnDeviceLlmFeasible(): Boolean {
        // 32-bit devices cannot run on-device LLM
        if (DeviceCapability.is32BitDevice()) return false
        return getTotalRamMb() >= 1536  // Gemma 4 E2B Q3_K_M needs ~1.5GB total
    }

    /**
     * Get comprehensive model status for debugging/UI.
     */
    fun getModelStatus(): ModelStatus {
        val available = modelRegistry.getAvailableModels()
        val essential = getEssentialModels()
        val storageUsed = modelRegistry.getStorageUsedBytes()

        return ModelStatus(
            deviceTier = deviceTier,
            totalRamMb = getTotalRamMb(),
            availableRamMb = getAvailableRamMb(),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            loadedModelId = loadedModelId,
            activeBackend = activeBackend,
            isLlmLoaded = llmEngine.isModelLoaded(),
            modelState = _modelState.value,
            availableModels = available,
            essentialModels = essential.toSet(),
            missingEssential = essential.filter { it !in available }.toSet(),
            storageUsedBytes = storageUsed,
            storageUsedFormatted = modelRegistry.getStorageUsedFormatted(),
            onDeviceFeasible = isOnDeviceLlmFeasible(),
            lastInferenceTimeMs = lastInferenceTimeMs,
            loadedModelCount = loadedModels.size
        )
    }

    /**
     * Handle a low-memory callback from the system.
     * Unloads models to free RAM.
     *
     * @param level The memory pressure level (from ComponentCallbacks2)
     */
    fun onLowMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Timber.w(TAG, "Critical memory pressure — unloading all models")
                unloadAllModels()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                Timber.w(TAG, "Low memory — unloading LLM")
                unloadLlm()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                Timber.w(TAG, "Critical running memory — unloading LLM and clearing caches")
                unloadLlm()
                System.gc()
            }
        }
    }

    /**
     * Trigger a background check for model updates.
     * Downloads newer versions if available.
     */
    suspend fun checkForUpdates() = withContext(Dispatchers.IO) {
        val essential = getEssentialModels()
        for (modelId in essential) {
            if (modelRegistry.isUpdateAvailable(modelId)) {
                Timber.i(TAG, "Update available for %s — queuing download", modelId)
                modelDownloader.downloadModel(modelId)
            }
        }
    }

    /**
     * Delete all downloaded models and free storage.
     */
    fun deleteAllModels() {
        unloadAllModels()
        modelRegistry.deleteAllModels()
        Timber.i(TAG, "All models deleted")
    }

    /**
     * Get the total RAM on this device in MB.
     */
    fun getTotalRamMb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return (memInfo.totalMem / (1024 * 1024)).toInt()
    }

    /**
     * Get currently available (free) RAM in MB.
     */
    fun getAvailableRamMb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return (memInfo.availMem / (1024 * 1024)).toInt()
    }

    // ────────────────────── Private: Device Classification ──────────────────────

    /**
     * Classify the device into a capability tier.
     * Uses total RAM as the primary signal.
     */
    private fun classifyDevice(): DeviceTier {
        val totalRamMb = getTotalRamMb()
        return when {
            totalRamMb >= RAM_MID_THRESHOLD_MB -> DeviceTier.HIGH
            totalRamMb >= RAM_LOW_THRESHOLD_MB -> DeviceTier.MID
            else -> DeviceTier.LOW
        }
    }

    /**
     * Get the list of LLM model candidates for this device, ordered by preference.
     * First element is the preferred model; later elements are fallbacks.
     * Includes graceful degradation: tries larger models first on capable devices.
     *
     * 32-bit devices return an empty list — on-device LLM is not available.
     * Voice models (Whisper, VAD, TTS) still work on 32-bit.
     *
     * Decision Council (2026-07-15):
     * - LOW:  Qwen 3.5 0.8B ONLY (Gemma 4 E2B ~600MB too large for ≤2GB devices)
     * - MID:  Qwen 3.5 0.8B default → Gemma 4 E2B fallback
     * - HIGH: Qwen 3.5 2B default → Gemma 4 E2B fallback → Qwen 3.5 0.8B last resort
     *
     * Now includes Q2_K ultra-lite variants for data-limited scenarios.
     * Data-saver path: Q2_K → Q3_K_M → Q4_K_M (progressive quality upgrade)
     */
    private fun getModelCandidates(): List<String> {
        if (!isOnDeviceLlmFeasible()) return emptyList()

        return when (deviceTier) {
            DeviceTier.LOW -> listOf(
                MODEL_GEMMA_4_E2B_SMALL,     // Gemma Q3 primary (~1GB)
                MODEL_GEMMA_4_E2B_ULTRA,     // Gemma Q2_K fallback (~650MB)
                MODEL_QWEN_08B,              // Qwen Q4 fallback (~580MB)
                MODEL_QWEN_08B_LITE          // Qwen Q2_K last resort (~300MB)
            )
            DeviceTier.MID -> listOf(
                MODEL_GEMMA_4_E2B,           // Gemma Q4 primary (~1.5GB)
                MODEL_GEMMA_4_E2B_SMALL,     // Gemma Q3 fallback (~1GB)
                MODEL_QWEN_08B               // Qwen Q4 fallback (~580MB)
            )
            DeviceTier.HIGH -> listOf(
                MODEL_GEMMA_4_E2B,           // Gemma Q4 primary (~1.5GB)
                MODEL_QWEN_2B,               // Qwen 2B fallback (~1.2GB)
                MODEL_QWEN_08B               // Qwen 0.8B last resort (~580MB)
            )
        }
    }

    /**
     * Get essential models that should always be available.
     * On 32-bit devices, only voice models are essential (LLM is cloud-only).
     * Now includes Q2_K ultra-lite models for LOW-tier devices.
     */
    private fun getEssentialModels(): List<String> {
        val voiceModels = listOf(
            "silero-vad",
            "whisper-tiny-int4",
            "piper-swahili"
        )

        return if (isOnDeviceLlmFeasible()) {
            voiceModels + when (deviceTier) {
                DeviceTier.LOW -> listOf(
                    MODEL_GEMMA_4_E2B_SMALL,     // Primary Q3_K_M (~1GB)
                    MODEL_GEMMA_4_E2B_ULTRA,     // Fallback Q2_K (~650MB)
                    MODEL_QWEN_08B,              // Qwen fallback (~580MB)
                    MODEL_QWEN_08B_LITE          // Qwen Q2_K last resort (~300MB)
                )
                DeviceTier.MID -> listOf(
                    MODEL_GEMMA_4_E2B,           // Primary Q4_K_M (~1.5GB)
                    MODEL_GEMMA_4_E2B_SMALL,     // Fallback Q3_K_M (~1GB)
                    MODEL_QWEN_08B               // Qwen fallback (~580MB)
                )
                else -> listOf(
                    MODEL_GEMMA_4_E2B,           // Primary Q4_K_M (~1.5GB)
                    MODEL_QWEN_08B               // Qwen fallback (~580MB)
                )
            }
        } else {
            voiceModels
        }
    }

    // ────────────────────── Private: Model Loading ──────────────────────

    /**
     * Load a specific model with the given backend.
     *
     * @return true if the model loaded successfully
     */
    private suspend fun loadSpecificModel(modelId: String, backend: ModelBackend): Boolean {
        val modelPath = modelRegistry.getModelPath(modelId)
        if (modelPath == null) {
            Timber.w(TAG, "Model %s not found on disk — needs download", modelId)
            // Try to download it
            val downloaded = modelDownloader.downloadModel(modelId)
            if (!downloaded) return false
            return loadSpecificModel(modelId, backend) // Retry after download
        }

        return when (backend) {
            ModelBackend.LLAMA_CPP -> loadLlamaCppModel(modelId, modelPath)
            ModelBackend.ONNX -> {
                // ONNX models (Whisper, Piper, Silero) are loaded by their own engines
                // via ModelRegistry. We just verify they exist.
                modelRegistry.isModelReady(modelId)
            }
            ModelBackend.TFLITE -> {
                // TFLite fallback — not currently implemented
                Timber.w(TAG, "TFLite backend not implemented")
                false
            }
        }
    }

    /**
     * Load a GGUF model via LlmEngine (llama.cpp JNI).
     */
    private suspend fun loadLlamaCppModel(modelId: String, modelPath: File): Boolean {
        val nCtx = getRecommendedContextLength()
        val nThreads = getOptimalThreads()

        Timber.d(
            TAG, "Loading GGUF model: %s (size=%dMB, ctx=%d, threads=%d)",
            modelId, modelPath.length() / (1024 * 1024), nCtx, nThreads
        )

        val loaded = llmEngine.loadModel()
        if (loaded) {
            loadedModels[modelId] = LoadedModelInfo(
                modelId = modelId,
                backend = ModelBackend.LLAMA_CPP,
                loadedAtMs = System.currentTimeMillis(),
                contextLength = nCtx,
                modelPath = modelPath.absolutePath
            )
        }
        return loaded
    }

    /**
     * Unload all loaded models and free memory.
     */
    private fun unloadAllModels() {
        llmEngine.unloadModel()
        loadedModels.clear()
        loadedModelId = null
        activeBackend = null
        _modelState.value = ModelManagerState.IDLE
        Timber.i(TAG, "All models unloaded")
    }

    /**
     * Get the sequential model loader instance.
     * Used by VoicePipeline and other components for LOW-tier device operation.
     *
     * @return The SequentialModelLoader, or null if not in sequential mode
     */
    fun getSequentialLoader(): SequentialModelLoader? {
        return if (deviceTier == DeviceTier.LOW) sequentialModelLoader.get() else null
    }

    /**
     * Check if sequential model loading is active (LOW-tier devices).
     * When true, callers MUST use [SequentialModelLoader.withModel] instead of
     * loading models directly.
     */
    fun isSequentialMode(): Boolean {
        return deviceTier == DeviceTier.LOW
    }

    /**
     * Get optimal CPU thread count for inference.
     * Conservative: uses half of available cores, capped at 4.
     */
    private fun getOptimalThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return (cores / 2).coerceIn(1, 4)
    }

    // ────────────────────── Private: Memory Monitoring ──────────────────────

    /**
     * Start periodic memory monitoring.
     * Checks memory every 30 seconds and unloads models if pressure is high.
     */
    private fun startMemoryMonitor() {
        memoryMonitorJob?.cancel()
        memoryMonitorJob = scope.launch {
            while (isActive) {
                try {
                    val info = collectMemoryInfo()
                    _memoryInfo.value = info

                    // Auto-unload on memory pressure
                    if (info.usedPercent >= CRITICAL_MEMORY_PERCENT && llmEngine.isModelLoaded()) {
                        Timber.w(
                            TAG, "Memory critical (%d%%) — emergency unload",
                            info.usedPercent
                        )
                        unloadLlm()
                    } else if (info.usedPercent >= LOW_MEMORY_PERCENT && llmEngine.isModelLoaded()) {
                        // Only unload if model has been idle
                        val idleMs = System.currentTimeMillis() - lastInferenceTimeMs
                        val idleMinutes = idleMs / (60 * 1000)
                        if (idleMinutes >= AUTO_UNLOAD_MINUTES_IDLE) {
                            Timber.i(
                                TAG, "Memory high (%d%%) and model idle %dmin — unloading",
                                info.usedPercent, idleMinutes
                            )
                            unloadLlm()
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Memory monitor error")
                }
                delay(30_000) // Check every 30 seconds
            }
        }
    }

    /**
     * Collect current memory information.
     */
    private fun collectMemoryInfo(): MemoryInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val totalMb = (memInfo.totalMem / (1024 * 1024)).toInt()
        val availableMb = (memInfo.availMem / (1024 * 1024)).toInt()
        val usedMb = totalMb - availableMb
        val usedPercent = ((usedMb.toLong() * 100) / totalMb).toInt()

        return MemoryInfo(
            totalMb = totalMb,
            availableMb = availableMb,
            usedMb = usedMb,
            usedPercent = usedPercent,
            isLowMemory = memInfo.lowMemory,
            thresholdMb = (memInfo.threshold / (1024 * 1024)).toInt()
        )
    }

    /**
     * Stop memory monitoring.
     * Call on app shutdown or when models are no longer needed.
     */
    fun shutdown() {
        memoryMonitorJob?.cancel()
        memoryMonitorJob = null
        unloadAllModels()
        Timber.i(TAG, "ModelManager shut down")
    }
}

// ────────────────────── Data Classes ──────────────────────

/**
 * Model manager states.
 */
enum class ModelManagerState {
    /** Not initialized */
    IDLE,
    /** Ready to load models */
    READY,
    /** Currently loading a model */
    LOADING,
    /** Model loaded and ready for inference */
    LOADED,
    /** On-device model failed, using cloud fallback */
    FALLBACK_TO_CLOUD,
    /** Error state */
    ERROR
}

/**
 * System memory information snapshot.
 */
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

/**
 * Information about a currently loaded model.
 */
data class LoadedModelInfo(
    val modelId: String,
    val backend: ModelManager.ModelBackend,
    val loadedAtMs: Long,
    val contextLength: Int,
    val modelPath: String
)

/**
 * Comprehensive model status for debugging and UI display.
 */
data class ModelStatus(
    val deviceTier: ModelManager.DeviceTier,
    val totalRamMb: Int,
    val availableRamMb: Int,
    val cpuCores: Int,
    val loadedModelId: String?,
    val activeBackend: ModelManager.ModelBackend?,
    val isLlmLoaded: Boolean,
    val modelState: ModelManagerState,
    val availableModels: Set<String>,
    val essentialModels: Set<String>,
    val missingEssential: Set<String>,
    val storageUsedBytes: Long,
    val storageUsedFormatted: String,
    val onDeviceFeasible: Boolean,
    val lastInferenceTimeMs: Long,
    val loadedModelCount: Int,
    val modelPerformance: Map<String, ModelManager.ModelPerformanceMetrics> = emptyMap()
)
