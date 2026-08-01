package com.msaidizi.voice

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DeviceCapabilityDetector — Detects device hardware capabilities for model selection.
 *
 * P1: Support multiple Whisper model sizes based on device capability.
 * - Budget phones (<3GB RAM, <4 cores): Whisper Tiny (39M params)
 * - Mid-range (3-4GB RAM, 4-6 cores): Whisper Small (244M params)
 * - Flagship (4GB+ RAM, 6+ cores): Whisper Large V3 Turbo (809M params)
 *
 * Also determines:
 * - Whether to enable NNAPI/GPU acceleration
 * - Thread count for inference
 * - Context window size for LLM
 */
@Singleton
class DeviceCapabilityDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    enum class DeviceTier {
        BUDGET,     // <3GB RAM, <4 cores — use smallest models
        MID_RANGE,  // 3-4GB RAM, 4-6 cores — balanced
        FLAGSHIP    // 4GB+ RAM, 6+ cores — use largest models
    }

    data class DeviceCapabilities(
        val tier: DeviceTier,
        val totalRamMb: Long,
        val availableRamMb: Long,
        val cpuCores: Int,
        val sdkVersion: Int,
        val hasNNAPI: Boolean,
        val recommendedWhisperModel: String,
        val recommendedLlmContextSize: Int,
        val recommendedThreadCount: Int,
        val enableStreamingStt: Boolean
    )

    private var cachedCapabilities: DeviceCapabilities? = null

    /**
     * Detect device capabilities (cached after first call).
     */
    fun detect(): DeviceCapabilities {
        cachedCapabilities?.let { return it }

        val totalRamMb = getTotalRamMb()
        val availableRamMb = getAvailableRamMb()
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val sdkVersion = Build.VERSION.SDK_INT
        val hasNNAPI = sdkVersion >= 27 // Android 8.1+ has NNAPI

        val tier = when {
            totalRamMb < 3000 || cpuCores < 4 -> DeviceTier.BUDGET
            totalRamMb < 4000 || cpuCores < 6 -> DeviceTier.MID_RANGE
            else -> DeviceTier.FLAGSHIP
        }

        val capabilities = DeviceCapabilities(
            tier = tier,
            totalRamMb = totalRamMb,
            availableRamMb = availableRamMb,
            cpuCores = cpuCores,
            sdkVersion = sdkVersion,
            hasNNAPI = hasNNAPI,
            recommendedWhisperModel = recommendedWhisperModel(tier),
            recommendedLlmContextSize = recommendedLlmContextSize(tier),
            recommendedThreadCount = recommendedThreadCount(cpuCores),
            enableStreamingStt = tier != DeviceTier.BUDGET // Budget phones use offline Whisper
        )

        Timber.i("Device detected: %s (RAM=%dMB, cores=%d, SDK=%d, NNAPI=%s)",
            tier, totalRamMb, cpuCores, sdkVersion, hasNNAPI)
        Timber.i("Recommended: whisper=%s, ctx=%d, threads=%d, streaming=%s",
            capabilities.recommendedWhisperModel,
            capabilities.recommendedLlmContextSize,
            capabilities.recommendedThreadCount,
            capabilities.enableStreamingStt)

        cachedCapabilities = capabilities
        return capabilities
    }

    /**
     * Get the recommended Whisper model variant for this device.
     */
    private fun recommendedWhisperModel(tier: DeviceTier): String {
        return when (tier) {
            DeviceTier.BUDGET -> "whisper-tiny"     // 39M params, fastest
            DeviceTier.MID_RANGE -> "whisper-small"  // 244M params, balanced
            DeviceTier.FLAGSHIP -> "whisper-large-v3-turbo" // 809M params, best quality
        }
    }

    /**
     * Get the recommended LLM context window size.
     */
    private fun recommendedLlmContextSize(tier: DeviceTier): Int {
        return when (tier) {
            DeviceTier.BUDGET -> 4096      // Minimum viable
            DeviceTier.MID_RANGE -> 8192   // Good for advice
            DeviceTier.FLAGSHIP -> 8192    // Max supported by Qwen3
        }
    }

    /**
     * Get the recommended thread count for inference.
     */
    private fun recommendedThreadCount(cpuCores: Int): Int {
        return (cpuCores / 2).coerceIn(2, 4)
    }

    private fun getTotalRamMb(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }

    private fun getAvailableRamMb(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.availMem / (1024 * 1024)
    }
}
