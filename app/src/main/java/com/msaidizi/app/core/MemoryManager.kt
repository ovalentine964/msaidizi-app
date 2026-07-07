package com.msaidizi.app.core

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Debug
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
class MemoryManager(private val context: Context) {

    // ═══ Memory Thresholds (MB) ═══
    // Tuned for 2GB devices: aggressive cleanup before Android's LMK kicks in
    companion object {
        private const val LOW_MEMORY_THRESHOLD_MB = 150L      // ~150MB free = danger zone
        private const val CRITICAL_MEMORY_THRESHOLD_MB = 80L  // ~80MB free = emergency
        private const val MODEL_RELEASE_THRESHOLD_MB = 200L   // Release models below this

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

    // ═══ State ═══
    private val isMonitoring = AtomicBoolean(false)
    private val lastTrimTime = AtomicLong(0)
    private val trimCount = AtomicLong(0)

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
     */
    private fun notifyListeners(level: MemoryLevel) {
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
