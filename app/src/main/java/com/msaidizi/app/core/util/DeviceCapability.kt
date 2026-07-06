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
     * Get a human-readable device summary for logging.
     */
    fun getDeviceSummary(context: Context): String {
        val tier = getTier(context)
        val ramMb = getTotalRamMb(context)
        val isGo = isAndroidGo(context)
        return "Device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
            "API ${Build.VERSION.SDK_INT}, RAM ${ramMb}MB, " +
            "Tier=$tier, AndroidGo=$isGo"
    }
}
