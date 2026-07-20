package com.msaidizi.app.core.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Device capability detection for adaptive behavior on low-resource devices.
 *
 * Africa's device landscape is dominated by budget phones:
 * - 2GB RAM devices (Tecno Pop 7, Infinix Smart 7, Nokia C21)
 * - Android Go editions
 * - Limited storage (16GB)
 *
 * This utility detects device capabilities and adjusts behavior:
 * - Memory limits → reduce concurrent tasks, use smaller caches
 * - Android Go → use lite models, disable animations
 * - Low storage → aggressive cache cleanup
 *
 * Based on the QA plan's Device Compatibility matrix (§5).
 */
object DeviceCapability {

    enum class PerformanceTier {
        LOW,     // ≤2GB RAM or Android Go
        MEDIUM,  // 3-4GB RAM
        HIGH     // 5GB+ RAM
    }

    /**
     * Detect device performance tier based on available RAM and Android Go status.
     *
     * @param context Application context
     * @return Performance tier for adaptive behavior
     */
    fun getTier(context: Context): PerformanceTier {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalRamMb = memInfo.totalMem / (1024 * 1024)

        return when {
            totalRamMb <= 2048 || isAndroidGo(context) -> PerformanceTier.LOW
            totalRamMb <= 4096 -> PerformanceTier.MEDIUM
            else -> PerformanceTier.HIGH
        }
    }

    /**
     * Check if the device is running Android Go edition.
     * Android Go has reduced RAM and storage, uses lite apps.
     */
    fun isAndroidGo(context: Context): Boolean {
        return try {
            context.packageManager.hasSystemFeature("android.software.go")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Get total device RAM in MB.
     */
    fun getTotalRamMb(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }

    /**
     * Get available RAM in MB.
     */
    fun getAvailableRamMb(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.availMem / (1024 * 1024)
    }

    /**
     * Check if device is in low memory state.
     */
    fun isLowMemory(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.lowMemory
    }

    /**
     * Max concurrent AI tasks based on device tier.
     * LOW: 1 task (sequential), MEDIUM: 2, HIGH: 4.
     */
    fun getMaxConcurrentAiTasks(tier: PerformanceTier): Int = when (tier) {
        PerformanceTier.LOW -> 1
        PerformanceTier.MEDIUM -> 2
        PerformanceTier.HIGH -> 4
    }

    /**
     * Whether to use lite/smaller AI models.
     * Android Go and 2GB devices should use quantized models.
     */
    fun shouldUseLiteModel(tier: PerformanceTier): Boolean = tier == PerformanceTier.LOW

    /**
     * Image cache size in MB based on device tier.
     */
    fun getImageCacheSizeMb(tier: PerformanceTier): Int = when (tier) {
        PerformanceTier.LOW -> 16
        PerformanceTier.MEDIUM -> 32
        PerformanceTier.HIGH -> 64
    }

    /**
     * Max Room database cache size based on device tier.
     */
    fun getDbCacheSizeMb(tier: PerformanceTier): Int = when (tier) {
        PerformanceTier.LOW -> 4
        PerformanceTier.MEDIUM -> 8
        PerformanceTier.HIGH -> 16
    }

    /**
     * Whether to enable animations on this device.
     * Disable on low-tier to save battery and CPU.
     */
    fun shouldEnableAnimations(tier: PerformanceTier): Boolean = tier != PerformanceTier.LOW

    /**
     * Get API level of the device.
     */
    fun getApiLevel(): Int = Build.VERSION.SDK_INT

    /**
     * Check if device meets minimum API requirement (26 / Android 8.0).
     */
    fun meetsMinimumApi(): Boolean = Build.VERSION.SDK_INT >= 26

    /**
     * Check if the device is running a 32-bit (armeabi-v7a) process.
     *
     * 32-bit Android devices are common in Africa's budget phone market:
     * - Tecno Pop series, Infinix Smart series, Itel A series
     * - Often have ≤2GB RAM with 32-bit-only ARMv7 CPUs
     * - Cannot run arm64-v8a native libraries
     *
     * On 32-bit devices, on-device LLM inference is not feasible because:
     * - llama.cpp models (580MB+) exceed available memory
     * - The process address space is limited to ~2GB (32-bit pointer width)
     * - mmap for large GGUF files is unreliable on 32-bit
     *
     * These devices should use cloud-only mode via the ModelRouter fallback chain.
     */
    fun is32BitDevice(): Boolean {
        // Build.SUPPORTED_ABIS lists ABIs the device can run, ordered by preference.
        // If the first ABI is armeabi-v7a (not arm64-v8a), the device is 32-bit.
        return Build.SUPPORTED_ABIS.firstOrNull() == "armeabi-v7a"
    }

    /**
     * Get the primary ABI for this device (e.g., "arm64-v8a" or "armeabi-v7a").
     */
    fun getPrimaryAbi(): String {
        return Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    }

    /**
     * Check if the device is running in a 32-bit process on a 64-bit capable device.
     * Some devices have 64-bit hardware but run 32-bit Android (common with Android Go).
     */
    fun is32BitProcessOn64BitHardware(): Boolean {
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: return false
        return primaryAbi == "armeabi-v7a" && Build.SUPPORTED_ABIS.contains("arm64-v8a")
    }

    /**
     * Whether on-device LLM inference is feasible on this device.
     * Considers both architecture (32-bit → no) and memory (too low → no).
     */
    fun isOnDeviceLlmSupported(context: Context): Boolean {
        if (is32BitDevice()) return false
        return getTotalRamMb(context) >= 1536
    }

    /**
     * Get a user-facing message explaining why on-device AI is unavailable.
     * Returns null if on-device AI is supported.
     */
    fun getUnsupportedReason(context: Context): String? {
        if (is32BitDevice()) {
            return "Kifaa chako kinatumia mfumo wa 32-bit. " +
                "Msaidizi anatumia mtandao (cloud) kutoa huduma. " +
                "Hakuna model ya ndani inayoweza kutumika.\n\n" +
                "Your device uses a 32-bit system. " +
                "Msaidizi uses cloud services to provide AI features. " +
                "No on-device model can run on this device."
        }
        if (getTotalRamMb(context) < 1536) {
            return "Kifaa chako kina RAM ndogo sana (${getTotalRamMb(context)}MB). " +
                "Msaidizi anatumia mtandao (cloud) kutoa huduma.\n\n" +
                "Your device has insufficient RAM (${getTotalRamMb(context)}MB). " +
                "Msaidizi uses cloud services for AI features."
        }
        return null
    }

    /**
     * Get a human-readable device summary for logging.
     * Enhanced to include architecture information.
     */
    fun getDeviceSummary(context: Context): String {
        val tier = getTier(context)
        val ramMb = getTotalRamMb(context)
        val isGo = isAndroidGo(context)
        val abi = getPrimaryAbi()
        val is32bit = is32BitDevice()
        return "Device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
            "API ${Build.VERSION.SDK_INT}, RAM ${ramMb}MB, " +
            "Tier=$tier, AndroidGo=$isGo, ABI=$abi, 32-bit=$is32bit"
    }
}
