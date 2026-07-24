package com.msaidizi.core.common.util

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Device tier detection for performance optimization.
 *
 * Msaidizi targets a wide range of Android devices, from high-end
 * Samsung/Pixel to budget Tecno/Infinix/Itel phones common in Africa.
 * Device tier determines which features are enabled.
 *
 * ## Tier Strategy
 * - HIGH: Full LLM, streaming TTS, all features
 * - MEDIUM: Small LLM, batched TTS, most features
 * - LOW: Cloud-only LLM, basic TTS, core features only
 * - MINIMAL: Cloud-only everything, text-only fallback
 */
object DeviceTierDetector {

    enum class DeviceTier {
        HIGH,      // 8GB+ RAM, flagship SoC
        MEDIUM,    // 4-8GB RAM, mid-range SoC
        LOW,       // 2-4GB RAM, budget SoC
        MINIMAL    // <2GB RAM, very old/budget
    }

    /**
     * Detect device tier based on available RAM and CPU cores.
     */
    fun detect(context: Context): DeviceTier {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        val cpuCores = Runtime.getRuntime().availableProcessors()

        return when {
            totalRamMb >= 6_000 && cpuCores >= 6 -> DeviceTier.HIGH
            totalRamMb >= 3_500 && cpuCores >= 4 -> DeviceTier.MEDIUM
            totalRamMb >= 1_500 && cpuCores >= 2 -> DeviceTier.LOW
            else -> DeviceTier.MINIMAL
        }
    }

    /**
     * Whether the device is 32-bit (armeabi-v7a).
     * 32-bit devices have severe memory constraints — native LLM
     * models are too large. These run in cloud-only mode.
     */
    fun is32BitDevice(): Boolean {
        return Build.SUPPORTED_ABIS.firstOrNull() == "armeabi-v7a"
    }

    /**
     * Whether the device is in battery saver mode.
     * When true, reduce background work and disable proactive features.
     */
    fun isBatterySaverMode(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isPowerSaveMode
    }

    /**
     * Available RAM in MB.
     */
    fun getAvailableRamMb(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.availMem / (1024 * 1024)
    }

    /**
     * Whether the device can run on-device LLM.
     * Requires: 64-bit, 4GB+ RAM, not in battery saver.
     */
    fun canRunOnDeviceLlm(context: Context): Boolean {
        if (is32BitDevice()) return false
        if (isBatterySaverMode(context)) return false
        val tier = detect(context)
        return tier == DeviceTier.HIGH || tier == DeviceTier.MEDIUM
    }

    /**
     * Whether the device can run on-device TTS.
     * Requires: 64-bit, 2GB+ RAM.
     */
    fun canRunOnDeviceTts(context: Context): Boolean {
        if (is32BitDevice()) return false
        val tier = detect(context)
        return tier != DeviceTier.MINIMAL
    }
}
