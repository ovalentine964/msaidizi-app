package com.msaidizi.app.core.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import timber.log.Timber
import java.io.File

/**
 * Device tier detection for progressive enhancement.
 *
 * Determines what features and model sizes the device can handle.
 * Critical for the 2GB target — ensures we don't overload the device.
 *
 * Tiers:
 * - BASIC: 2GB RAM, 4 cores (Samsung Galaxy A03, Tecno Spark Go)
 * - STANDARD: 3GB RAM, 6 cores (Tecno Spark 10)
 * - ENHANCED: 4GB RAM (Samsung Galaxy A14)
 * - PREMIUM: 6GB+ RAM (Samsung Galaxy A25)
 */
object DeviceTier {

    enum class Tier {
        BASIC,      // 2GB RAM — our primary target
        STANDARD,   // 3GB RAM
        ENHANCED,   // 4GB RAM
        PREMIUM     // 6GB+ RAM
    }

    var current: Tier = Tier.BASIC  // Safe default for budget devices; updated by initialize()
        private set

    var totalRamMB: Long = 0
        private set

    var availableCores: Int = 0
        private set

    var hasNPU: Boolean = false
        private set

    var socName: String = ""
        private set

    fun initialize(context: Context) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        totalRamMB = memInfo.totalMem / (1024 * 1024)
        availableCores = Runtime.getRuntime().availableProcessors()
        socName = Build.HARDWARE ?: Build.BOARD ?: "unknown"
        hasNPU = detectNPU()

        current = when {
            totalRamMB <= 2048 && availableCores <= 4 -> Tier.BASIC
            totalRamMB <= 3072 && availableCores <= 6 -> Tier.STANDARD
            totalRamMB <= 4096 -> Tier.ENHANCED
            else -> Tier.PREMIUM
        }

        Timber.i("DeviceTier: $current (RAM=${totalRamMB}MB, cores=$availableCores, SoC=$socName)")
    }

    /**
     * Get max context length for LLM based on device tier.
     */
    fun getMaxContextLength(): Int = when (current) {
        Tier.BASIC -> 1024
        Tier.STANDARD -> 2048
        Tier.ENHANCED -> 4096
        Tier.PREMIUM -> 8192
    }

    /**
     * Get max audio recording duration in seconds.
     */
    fun getMaxRecordingDurationSec(): Int = when (current) {
        Tier.BASIC -> 30
        Tier.STANDARD -> 60
        Tier.ENHANCED -> 120
        Tier.PREMIUM -> 300
    }

    /**
     * Should we use XML Views (true) or Compose (false)?
     * 2GB devices should use XML for lower memory overhead.
     */
    fun useXmlViews(): Boolean = current == Tier.BASIC || current == Tier.STANDARD

    /**
     * Should we pre-load ASR model?
     * On 2GB devices, lazy-load to save memory.
     */
    fun preloadASR(): Boolean = current >= Tier.STANDARD

    /**
     * Should we pre-load LLM?
     * Only on 4GB+ devices.
     */
    fun preloadLLM(): Boolean = current >= Tier.ENHANCED

    /**
     * Should we enable background learning?
     * Only on 3GB+ devices.
     */
    fun enableBackgroundLearning(): Boolean = current >= Tier.STANDARD

    /**
     * Get model quantization level.
     */
    fun getQuantization(): String = when (current) {
        Tier.BASIC -> "INT4"
        Tier.STANDARD -> "INT4"
        Tier.ENHANCED -> "INT8"
        Tier.PREMIUM -> "INT8"
    }

    /**
     * Get number of inference threads.
     */
    fun getInferenceThreads(): Int = when (current) {
        Tier.BASIC -> 2
        Tier.STANDARD -> 2
        Tier.ENHANCED -> 4
        Tier.PREMIUM -> 4
    }

    /**
     * Check if device has enough free storage for models.
     * Returns available MB.
     */
    fun getAvailableStorageMB(): Long {
        val dataDir = File("/data")
        return dataDir.freeSpace / (1024 * 1024)
    }

    private fun detectNPU(): Boolean {
        // Check for common NPU indicators
        val hardware = Build.HARDWARE.lowercase()
        return hardware.contains("mt676") || // MediaTek Helio G series
               hardware.contains("sm6") ||    // Snapdragon 6 series
               hardware.contains("sm4")       // Snapdragon 4 series
    }
}
