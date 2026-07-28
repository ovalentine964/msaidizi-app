package com.msaidizi.app.voice

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * ModelTier — Automatic device capability detection and model tier selection.
 *
 * Addresses GAP: 555MB models on sub-$50 Android phones with 1-2GB RAM.
 *
 * Tiers:
 * - **Lite**:     Q4_0 quantized Qwen 0.5B (~300MB) — for devices with ≤2GB RAM
 * - **Standard**: Q4_K_M Qwen 0.8B (~500MB) — for devices with 3-4GB RAM
 * - **Pro**:      Q5_K_M Qwen 0.8B (~600MB) — for devices with ≥5GB RAM
 *
 * The tier can be auto-detected from device RAM or manually overridden by the user.
 */
@Singleton
class ModelTier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "model_tier"
        private const val KEY_MANUAL_TIER = "manual_tier"
        private const val KEY_LAST_DETECTED = "last_detected_tier"
    }

    /**
     * Model tier definitions with their associated model files and parameters.
     */
    enum class Tier(
        val displayName: String,
        val description: String,
        val llmModelFilename: String,
        val llmModelSizeMb: Int,
        val quantization: String,
        val baseModel: String,
        val nCtx: Int,
        val nThreads: Int,
        val whisperModel: WhisperSize,
        val minRamMb: Int
    ) {
        LITE(
            displayName = "Lite",
            description = "Optimized for low-end devices (1-2GB RAM). Smaller model, faster inference.",
            llmModelFilename = "Qwen3.5-0.5B-Q4_0.gguf",
            llmModelSizeMb = 300,
            quantization = "Q4_0",
            baseModel = "Qwen3.5-0.5B",
            nCtx = 1024,
            nThreads = 2,
            whisperModel = WhisperSize.TINY,
            minRamMb = 0
        ),
        STANDARD(
            displayName = "Standard",
            description = "Balanced performance for mid-range devices (3-4GB RAM).",
            llmModelFilename = "Qwen3.5-0.8B-Q4_K_M.gguf",
            llmModelSizeMb = 500,
            quantization = "Q4_K_M",
            baseModel = "Qwen3.5-0.8B",
            nCtx = 2048,
            nThreads = 3,
            whisperModel = WhisperSize.TINY,
            minRamMb = 2048
        ),
        PRO(
            displayName = "Pro",
            description = "Maximum quality for high-end devices (5GB+ RAM). Best accuracy.",
            llmModelFilename = "Qwen3.5-0.8B-Q5_K_M.gguf",
            llmModelSizeMb = 600,
            quantization = "Q5_K_M",
            baseModel = "Qwen3.5-0.8B",
            nCtx = 4096,
            nThreads = 4,
            whisperModel = WhisperSize.SMALL,
            minRamMb = 4096
        );

        /**
         * Get the download URL for this tier's LLM model.
         */
        fun getLlmDownloadUrl(): String {
            return when (this) {
                LITE -> "https://huggingface.co/unsloth/Qwen3.5-0.5B-GGUF/resolve/main/Qwen3.5-0.5B-Q4_0.gguf"
                STANDARD -> "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf"
                PRO -> "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q5_K_M.gguf"
            }
        }
    }

    /**
     * Whisper model size options for different tiers.
     */
    enum class WhisperSize(
        val displayName: String,
        val modelFilename: String,
        val sizeMb: Int,
        val downloadUrl: String,
        val werSwahili: String  // estimated WER for Swahili
    ) {
        TINY(
            displayName = "Whisper Tiny",
            modelFilename = "sherpa-onnx-whisper-tiny",
            sizeMb = 40,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2",
            werSwahili = "15-25%"
        ),
        BASE(
            displayName = "Whisper Base",
            modelFilename = "sherpa-onnx-whisper-base",
            sizeMb = 80,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2",
            werSwahili = "12-18%"
        ),
        SMALL(
            displayName = "Whisper Small",
            modelFilename = "sherpa-onnx-whisper-small",
            sizeMb = 140,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2",
            werSwahili = "8-14%"
        );
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Get the total device RAM in MB.
     */
    fun getDeviceRamMb(): Long {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            (memInfo.totalMem / (1024.0 * 1024.0)).roundToLong()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get device RAM")
            // Fallback: try reading /proc/meminfo
            try {
                val reader = java.io.BufferedReader(java.io.FileReader("/proc/meminfo"))
                val line = reader.readLine() // "MemTotal:   XXXXX kB"
                reader.close()
                val kb = line.replace(Regex("[^0-9]"), "").toLong()
                kb / 1024
            } catch (e2: Exception) {
                Timber.e(e2, "Fallback RAM detection failed")
                1024 // Assume 1GB as safe default
            }
        }
    }

    /**
     * Get available device RAM in MB (total - used).
     */
    fun getAvailableRamMb(): Long {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            (memInfo.availMem / (1024.0 * 1024.0)).roundToLong()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get available RAM")
            getDeviceRamMb() / 2 // Assume half is available
        }
    }

    /**
     * Auto-detect the recommended tier based on device RAM.
     */
    fun detectRecommendedTier(): Tier {
        val ramMb = getDeviceRamMb()
        val tier = when {
            ramMb <= 2048 -> Tier.LITE
            ramMb <= 4096 -> Tier.STANDARD
            else -> Tier.PRO
        }
        Timber.i("Device RAM: %dMB → recommended tier: %s", ramMb, tier.displayName)
        prefs.edit().putString(KEY_LAST_DETECTED, tier.name).apply()
        return tier
    }

    /**
     * Get the active tier (manual override or auto-detected).
     */
    fun getActiveTier(): Tier {
        val manualTier = prefs.getString(KEY_MANUAL_TIER, null)
        if (manualTier != null) {
            return try {
                Tier.valueOf(manualTier)
            } catch (e: IllegalArgumentException) {
                detectRecommendedTier()
            }
        }
        return detectRecommendedTier()
    }

    /**
     * Set a manual tier override.
     */
    fun setManualTier(tier: Tier?) {
        if (tier == null) {
            prefs.edit().remove(KEY_MANUAL_TIER).apply()
            Timber.i("Manual tier cleared — using auto-detection")
        } else {
            prefs.edit().putString(KEY_MANUAL_TIER, tier.name).apply()
            Timber.i("Manual tier set: %s", tier.displayName)
        }
    }

    /**
     * Get the last auto-detected tier (before any override).
     */
    fun getLastDetectedTier(): Tier? {
        val name = prefs.getString(KEY_LAST_DETECTED, null)
        return try {
            name?.let { Tier.valueOf(it) }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Check if the current device can run a given tier.
     */
    fun canRunTier(tier: Tier): Boolean {
        val ramMb = getDeviceRamMb()
        // Need at least tier's min RAM + 512MB headroom for OS
        return ramMb >= (tier.minRamMb + 512)
    }

    /**
     * Get tier comparison info for the settings UI.
     */
    fun getTierComparison(): List<TierInfo> {
        val ramMb = getDeviceRamMb()
        val active = getActiveTier()
        return Tier.entries.map { tier ->
            TierInfo(
                tier = tier,
                isRecommended = tier == detectRecommendedTier(),
                isActive = tier == active,
                canRun = canRunTier(tier),
                totalModelSizeMb = tier.llmModelSizeMb + tier.whisperModel.sizeMb
            )
        }
    }

    /**
     * Switch to a new tier. Returns true if the switch is feasible.
     */
    suspend fun switchTier(newTier: Tier): Boolean {
        if (!canRunTier(newTier)) {
            Timber.w("Cannot run tier %s on this device (%dMB RAM)", newTier.displayName, getDeviceRamMb())
            return false
        }

        val oldTier = getActiveTier()
        if (oldTier == newTier) {
            Timber.d("Already on tier %s", newTier.displayName)
            return true
        }

        setManualTier(newTier)
        Timber.i("Switched tier: %s → %s", oldTier.displayName, newTier.displayName)
        return true
    }

    /**
     * Get device info for diagnostics.
     */
    fun getDeviceInfo(): DeviceInfo {
        val ramMb = getDeviceRamMb()
        val availMb = getAvailableRamMb()
        val active = getActiveTier()
        val recommended = detectRecommendedTier()

        return DeviceInfo(
            totalRamMb = ramMb,
            availableRamMb = availMb,
            activeTier = active,
            recommendedTier = recommended,
            isManualOverride = prefs.getString(KEY_MANUAL_TIER, null) != null,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = Build.VERSION.SDK_INT
        )
    }
}

/**
 * Information about a model tier for the settings UI.
 */
data class TierInfo(
    val tier: ModelTier.Tier,
    val isRecommended: Boolean,
    val isActive: Boolean,
    val canRun: Boolean,
    val totalModelSizeMb: Int
)

/**
 * Device information for diagnostics.
 */
data class DeviceInfo(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val activeTier: ModelTier.Tier,
    val recommendedTier: ModelTier.Tier,
    val isManualOverride: Boolean,
    val deviceModel: String,
    val androidVersion: Int
) {
    fun toSummaryString(): String = buildString {
        appendLine("Device: $deviceModel (Android $androidVersion)")
        appendLine("RAM: ${totalRamMb}MB total, ${availableRamMb}MB available")
        appendLine("Active tier: ${activeTier.displayName} ${if (isManualOverride) "(manual)" else "(auto)"}")
        appendLine("Recommended: ${recommendedTier.displayName}")
        appendLine("LLM: ${activeTier.llmModelFilename} (${activeTier.llmModelSizeMb}MB)")
        appendLine("Whisper: ${activeTier.whisperModel.displayName} (${activeTier.whisperModel.sizeMb}MB)")
        appendLine("Context: ${activeTier.nCtx}, Threads: ${activeTier.nThreads}")
    }
}
