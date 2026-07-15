package com.msaidizi.app.core

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Debug
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Memory Manager for Msaidizi — critical for 2GB RAM devices.
 *
 * Responsibilities:
 * 1. Monitor memory usage (heap, native, GPU)
 * 2. Trim caches when memory pressure detected
 * 3. Release model resources (ASR, LLM, TTS) when backgrounded
 * 4. Prevent OOM on low-memory devices
 * 5. Coordinate memory-sensitive components via callbacks
 *
 * Design principles:
 * - Zero allocation in hot paths (monitoring loop)
 * - Weak references for registered components (no leaks)
 * - Graduated response: trim → release → emergency
 * - Thread-safe for concurrent access from multiple agents
 *
 * Usage:
 *   val memManager = MemoryManager(context)
 *   memManager.registerCache("intent_patterns", intentCache)
 *   memManager.registerModelReleaser("whisper", whisperReleaser)
 *   // In Application.onTrimMemory():
 *   memManager.onTrimMemory(level)
 */
@Singleton
class MemoryManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    // ═══ Memory Thresholds (MB) ═══
    // Tuned for 2GB devices: aggressive cleanup before Android's LMK kicks in
    companion object {
        private const val LOW_MEMORY_THRESHOLD_MB = 150L      // ~150MB free = danger zone
        private const val CRITICAL_MEMORY_THRESHOLD_MB = 80L  // ~80MB free = emergency
        private const val MODEL_RELEASE_THRESHOLD_MB = 200L   // Release models below this

        // Minimum free memory required to load a model (MB)
        private const val MIN_FREE_TO_LOAD_MODEL_MB = 200L

        // Estimated model memory footprints (MB)
        const val WHISPER_MEMORY_MB = 40L
        const val KOKORO_MEMORY_MB = 90L
        const val PIPER_MEMORY_MB = 25L
        const val LLM_MEMORY_MB = 600L  // Qwen 3.5 0.8B Q4_K_M loaded in RAM

        // Cache size limits for 2GB devices
        private const val MAX_CACHE_ENTRIES = 500
        private const val CACHE_TRIM_PERCENTAGE = 0.3  // Remove 30% on trim

        // Monitoring interval
        private const val MONITOR_INTERVAL_MS = 30_000L  // 30 seconds
    }

    // ═══ Registered Components ═══
    private val caches = ConcurrentHashMap<String, WeakReference<MutableCollection<*>>>()
    private val modelReleasers = ConcurrentHashMap<String, () -> Unit>()
    private val memoryListeners = ConcurrentHashMap<String, (MemoryLevel) -> Unit>()

    // ═══ Sequential Model Coordination ═══
    // Callback to coordinate with SequentialModelLoader on LOW-tier devices
    private var sequentialCoordinationCallback: ((MemoryLevel) -> Unit)? = null

    // ═══ State ═══
    private val isMonitoring = AtomicBoolean(false)
    private val lastTrimTime = AtomicLong(0)
    private val trimCount = AtomicLong(0)

    // ═══ Mutual Exclusion ═══
    // Tracks which heavy model is currently loaded to enforce mutual exclusion on 2GB devices.
    // Only ONE of {WHISPER, KOKORO, LLM} may be loaded at a time on BASIC tier.
    enum class LoadedHeavyModel { NONE, WHISPER, KOKORO, LLM }
    @Volatile private var loadedHeavyModel: LoadedHeavyModel = LoadedHeavyModel.NONE

    // ═══ Memory Levels ═══
    enum class MemoryLevel {
        NORMAL,     // Plenty of memory
        MODERATE,   // Getting tight, trim caches
        LOW,        // Release non-essential resources
        CRITICAL,   // Emergency: release everything possible
        COMPLETE    // App may be killed imminently
    }

    // ═══ Public API ═══

    /**
     * Register a cache collection for automatic trimming.
     * Uses WeakReference to avoid preventing GC.
     *
     * @param name Unique name for logging
     * @param cache The mutable collection to trim
     */
    fun registerCache(name: String, cache: MutableCollection<*>) {
        caches[name] = WeakReference(cache)
        Timber.d("MemoryManager: Registered cache '$name'")
    }

    /**
     * Register a model resource releaser.
     * Called when memory is low to release heavy ML models.
     *
     * @param name Model name (e.g., "whisper", "llm", "tts")
     * @param releaser Lambda that releases the model
     */
    fun registerModelReleaser(name: String, releaser: () -> Unit) {
        modelReleasers[name] = releaser
        Timber.d("MemoryManager: Registered model releaser '$name'")
    }

    /**
     * Register a memory level change listener.
     * Components can react to memory pressure (e.g., reduce batch sizes).
     *
     * @param name Component name
     * @param listener Called with new MemoryLevel
     */
    fun registerListener(name: String, listener: (MemoryLevel) -> Unit) {
        memoryListeners[name] = listener
    }

    /**
     * Register a sequential model coordination callback.
     * Called when memory pressure changes on LOW-tier devices,
     * allowing SequentialModelLoader to react (e.g., abort in-flight operations).
     *
     * @param callback Called with the new MemoryLevel
     */
    fun registerSequentialCoordinator(callback: (MemoryLevel) -> Unit) {
        sequentialCoordinationCallback = callback
        Timber.d("MemoryManager: Registered sequential model coordinator")
    }

    /**
     * Unregister a component (call in onDestroy).
     */
    fun unregister(name: String) {
        caches.remove(name)
        modelReleasers.remove(name)
        memoryListeners.remove(name)
    }

    /**
     * Handle Android's onTrimMemory callback.
     * This is the PRIMARY entry point — called by the system.
     */
    fun onTrimMemory(level: Int) {
        val memoryLevel = mapAndroidLevel(level)
        Timber.w("MemoryManager: onTrimMemory level=$level → $memoryLevel")

        when (memoryLevel) {
            MemoryLevel.NORMAL -> { /* No action needed */ }
            MemoryLevel.MODERATE -> trimCaches()
            MemoryLevel.LOW -> {
                trimCaches()
                releaseNonEssentialModels()
            }
            MemoryLevel.CRITICAL -> {
                trimCaches()
                releaseAllModels()
            }
            MemoryLevel.COMPLETE -> {
                trimCaches()
                releaseAllModels()
                notifyListeners(memoryLevel)
            }
        }

        notifyListeners(memoryLevel)
        trimCount.incrementAndGet()
        lastTrimTime.set(System.currentTimeMillis())
    }

    // ═══ Model Loading Guard ═══

    /**
     * Check if there is enough free memory to load a model of the given size.
     * Returns true if safe to load, false otherwise.
     *
     * @param modelMemoryMB Estimated memory footprint of the model in MB
     */
    fun canLoadModel(modelMemoryMB: Long): Boolean {
        val status = getMemoryStatus()
        val safe = status.deviceFreeMB >= modelMemoryMB + MIN_FREE_TO_LOAD_MODEL_MB
        if (!safe) {
            Timber.w(
                "MemoryManager: REFUSING to load model (%dMB) — only %dMB free (need %dMB buffer)",
                modelMemoryMB, status.deviceFreeMB, MIN_FREE_TO_LOAD_MODEL_MB
            )
        }
        return safe
    }

    /**
     * Enforce mutual exclusion for heavy models on 2GB (BASIC tier) devices.
     * Before loading [requested], unloads the currently loaded heavy model if different.
     *
     * @param requested The model about to be loaded
     * @return true if the requested model may proceed to load, false if blocked by memory
     */
    fun acquireHeavyModelSlot(requested: LoadedHeavyModel): Boolean {
        if (requested == LoadedHeavyModel.NONE) return true

        val current = loadedHeavyModel
        if (current == requested) return true  // Already loaded

        // Must evict the current model first
        if (current != LoadedHeavyModel.NONE) {
            Timber.i("MemoryManager: Mutual exclusion — evicting %s to load %s", current, requested)
            val releaser = modelReleasers[current.name.lowercase()]
            releaser?.invoke()
            loadedHeavyModel = LoadedHeavyModel.NONE
        }

        // Check memory before proceeding
        val modelSize = when (requested) {
            LoadedHeavyModel.WHISPER -> WHISPER_MEMORY_MB
            LoadedHeavyModel.KOKORO -> KOKORO_MEMORY_MB
            LoadedHeavyModel.LLM -> LLM_MEMORY_MB
            else -> 0L
        }
        if (!canLoadModel(modelSize)) {
            Timber.e("MemoryManager: Not enough memory for %s (%dMB)", requested, modelSize)
            return false
        }

        loadedHeavyModel = requested
        return true
    }

    /**
     * Release a heavy model slot (call after unloading a model).
     */
    fun releaseHeavyModelSlot(model: LoadedHeavyModel) {
        if (loadedHeavyModel == model) {
            loadedHeavyModel = LoadedHeavyModel.NONE
            Timber.d("MemoryManager: Released heavy model slot: %s", model)
        }
    }

    /**
     * Get which heavy model is currently loaded.
     */
    fun getLoadedHeavyModel(): LoadedHeavyModel = loadedHeavyModel

    /**
     * Get current memory status for diagnostics.
     */
    fun getMemoryStatus(): MemoryStatus {
        val runtime = Runtime.getRuntime()
        val usedHeap = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxHeap = runtime.maxMemory() / (1024 * 1024)
        val freeHeap = maxHeap - usedHeap

        val nativeHeap = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        val nativeFree = Debug.getNativeHeapFreeSize() / (1024 * 1024)

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val deviceFree = memInfo.availMem / (1024 * 1024)
        val deviceTotal = memInfo.totalMem / (1024 * 1024)
        val isLowMemory = memInfo.lowMemory

        val level = when {
            deviceFree < CRITICAL_MEMORY_THRESHOLD_MB -> MemoryLevel.CRITICAL
            deviceFree < LOW_MEMORY_THRESHOLD_MB -> MemoryLevel.LOW
            deviceFree < MODEL_RELEASE_THRESHOLD_MB -> MemoryLevel.MODERATE
            else -> MemoryLevel.NORMAL
        }

        return MemoryStatus(
            usedHeapMB = usedHeap,
            maxHeapMB = maxHeap,
            freeHeapMB = freeHeap,
            nativeHeapMB = nativeHeap,
            nativeFreeMB = nativeFree,
            deviceFreeMB = deviceFree,
            deviceTotalMB = deviceTotal,
            isLowMemory = isLowMemory,
            currentLevel = level,
            trimCount = trimCount.get(),
            lastTrimTimeMs = lastTrimTime.get()
        )
    }

    /**
     * Proactive memory check — call from heartbeat or periodic task.
     * Returns true if memory is healthy, false if cleanup was needed.
     */
    fun checkAndCleanup(): Boolean {
        val status = getMemoryStatus()
        return when (status.currentLevel) {
            MemoryLevel.NORMAL -> true
            MemoryLevel.MODERATE -> {
                Timber.w("MemoryManager: Proactive trim — ${status.deviceFreeMB}MB free")
                trimCaches()
                false
            }
            MemoryLevel.LOW -> {
                Timber.w("MemoryManager: Proactive model release — ${status.deviceFreeMB}MB free")
                trimCaches()
                releaseNonEssentialModels()
                false
            }
            MemoryLevel.CRITICAL, MemoryLevel.COMPLETE -> {
                Timber.e("MemoryManager: CRITICAL — ${status.deviceFreeMB}MB free, emergency cleanup")
                trimCaches()
                releaseAllModels()
                false
            }
        }
    }

    /**
     * Force release all registered models.
     * Call when app is backgrounded or memory is critical.
     */
    fun releaseAllModels() {
        Timber.w("MemoryManager: Releasing ALL ${modelReleasers.size} models")
        modelReleasers.forEach { (name, releaser) ->
            try {
                releaser()
                Timber.d("MemoryManager: Released model '$name'")
            } catch (e: Exception) {
                Timber.e(e, "MemoryManager: Failed to release model '$name'")
            }
        }
    }

    /**
     * Release only non-essential models (keep ASR for voice input).
     */
    fun releaseNonEssentialModels() {
        // Keep whisper (ASR) active for voice input
        val nonEssential = modelReleasers.filterKeys { it != "whisper" }
        Timber.w("MemoryManager: Releasing ${nonEssential.size} non-essential models")
        nonEssential.forEach { (name, releaser) ->
            try {
                releaser()
                Timber.d("MemoryManager: Released non-essential model '$name'")
            } catch (e: Exception) {
                Timber.e(e, "MemoryManager: Failed to release model '$name'")
            }
        }
    }

    // ═══ Private ═══

    /**
     * Trim all registered caches by removing oldest/least-used entries.
     */
    private fun trimCaches() {
        var totalTrimmed = 0
        caches.forEach { (name, weakRef) ->
            val cache = weakRef.get()
            if (cache == null) {
                caches.remove(name)
                return@forEach
            }

            val sizeBefore = cache.size
            if (sizeBefore > MAX_CACHE_ENTRIES) {
                val toRemove = (sizeBefore * CACHE_TRIM_PERCENTAGE).toInt()
                when (cache) {
                    is MutableList<*> -> {
                        // Remove from beginning (oldest)
                        repeat(toRemove) {
                            if (cache.isNotEmpty()) {
                                (cache as MutableList<Any>).removeAt(0)
                                totalTrimmed++
                            }
                        }
                    }
                    is MutableMap<*, *> -> {
                        // Remove random entries (ConcurrentHashMap)
                        val keys = cache.keys.toList().take(toRemove)
                        keys.forEach { (cache as MutableMap<Any, Any>).remove(it); totalTrimmed++ }
                    }
                    is MutableSet<*> -> {
                        val toDrop = cache.take(toRemove)
                        toDrop.forEach { (cache as MutableSet<Any>).remove(it); totalTrimmed++ }
                    }
                }
                Timber.d("MemoryManager: Trimmed cache '$name': $sizeBefore → ${cache.size}")
            }
        }
        if (totalTrimmed > 0) {
            Timber.i("MemoryManager: Total cache entries trimmed: $totalTrimmed")
        }
    }

    /**
     * Notify all registered listeners of memory level change.
     * Also notifies the sequential model coordinator if registered.
     */
    private fun notifyListeners(level: MemoryLevel) {
        // Notify sequential coordinator first (higher priority — may abort operations)
        try {
            sequentialCoordinationCallback?.invoke(level)
        } catch (e: Exception) {
            Timber.e(e, "MemoryManager: Sequential coordinator threw exception")
        }

        memoryListeners.forEach { (name, listener) ->
            try {
                listener(level)
            } catch (e: Exception) {
                Timber.e(e, "MemoryManager: Listener '$name' threw exception")
            }
        }
    }

    /**
     * Map Android's ComponentCallbacks2 levels to our MemoryLevel.
     */
    private fun mapAndroidLevel(level: Int): MemoryLevel = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> MemoryLevel.MODERATE
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> MemoryLevel.MODERATE
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> MemoryLevel.LOW
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> MemoryLevel.CRITICAL
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
        ComponentCallbacks2.TRIM_MEMORY_MODERATE,
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> MemoryLevel.COMPLETE
        else -> MemoryLevel.NORMAL
    }

    // ═══ Data Class ═══

    data class MemoryStatus(
        val usedHeapMB: Long,
        val maxHeapMB: Long,
        val freeHeapMB: Long,
        val nativeHeapMB: Long,
        val nativeFreeMB: Long,
        val deviceFreeMB: Long,
        val deviceTotalMB: Long,
        val isLowMemory: Boolean,
        val currentLevel: MemoryLevel,
        val trimCount: Long,
        val lastTrimTimeMs: Long
    ) {
        val heapUsagePercent: Int get() = if (maxHeapMB > 0) ((usedHeapMB * 100) / maxHeapMB).toInt() else 0
        val deviceUsagePercent: Int get() = if (deviceTotalMB > 0) (((deviceTotalMB - deviceFreeMB) * 100) / deviceTotalMB).toInt() else 0

        override fun toString(): String = buildString {
            append("MemoryStatus(")
            append("heap=${usedHeapMB}/${maxHeapMB}MB (${heapUsagePercent}%), ")
            append("native=${nativeHeapMB}MB, ")
            append("device=${deviceFreeMB}/${deviceTotalMB}MB (${deviceUsagePercent}%), ")
            append("low=$isLowMemory, level=$currentLevel, trims=$trimCount")
            append(")")
        }
    }
}
