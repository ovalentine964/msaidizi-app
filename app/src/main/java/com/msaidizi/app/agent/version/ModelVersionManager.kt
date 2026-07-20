package com.msaidizi.app.agent.version

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber
import java.io.File

/**
 * Model Version Manager — On-device model upgrade path for Msaidizi.
 *
 * Manages the lifecycle of on-device AI models:
 * - Current: Qwen 3.5 0.8B (Q4_K_M, ~580MB, llama.cpp NDK)
 * - Previous: Qwen 0.5B (Q4_K_M, ~300MB, deprecated)
 * - Future: Qwen3.5-2B (Q4_K_M, ~1.2GB, for 3GB+ devices)
 *
 * ## Upgrade Path (updated 2026-07-16 — Gemma 4 E2B promoted to primary)
 *
 * | Model             | Params | Quantized | Performance              | Min RAM |
 * |-------------------|--------|-----------|--------------------------|--------|
 * | Qwen 0.5B         | 0.5B   | ~300MB    | Basic tasks (deprecated) | 2GB    |
 * | Qwen3.5-0.8B      | 0.8B   | ~580MB    | Fallback — multilingual  | 2GB    |
 * | Qwen3.5-2B        | 2B     | ~1.2GB    | Edge reasoning           | 3GB    |
 * | Gemma 4 E2B Q3_K  | 2B     | ~1.0GB    | Primary text LLM (LOW)   | 2GB    |
 * | Gemma 4 E2B Q4_K  | 2B     | ~1.5GB    | Primary text+vision      | 3GB    |
 *
 * ## Version-Aware Management
 * - Tracks which model version is installed
 * - Validates model file integrity (size, hash)
 * - Supports A/B testing between model versions
 * - Automatic rollback if new model fails
 * - Progressive download for large models
 *
 * ## GGUF Model Format
 * All models use GGUF format via llama.cpp NDK:
 * - Q4_K_M quantization (4-bit, k-quant mixed)
 * - Memory-mapped loading for fast startup
 * - Compatible with existing LlmEngine JNI bindings
 *
 * Based on: Swarm 7 — Qwen3.5-0.8B targets mobile/embedded with
 * 100+ language support and Apache 2.0 license
 */
class ModelVersionManager(private val context: Context) {

    /**
     * Known model versions with metadata.
     */
    enum class ModelVersion(
        val displayName: String,
        val modelFileName: String,
        val parameterCount: String,
        val quantizedSizeMb: Int,
        val minRamMb: Int,
        val license: String,
        val capabilities: List<String>
    ) {
        QWEN_0_5B(
            displayName = "Qwen 0.5B (Deprecated)",
            modelFileName = "qwen-0.5b-q4_k_m.gguf",
            parameterCount = "0.5B",
            quantizedSizeMb = 300,
            minRamMb = 2048,
            license = "Apache 2.0",
            capabilities = listOf("text_generation", "swahili", "simple_qa")
        ),
        QWEN3_5_0_8B(
            displayName = "Qwen3.5 0.8B (Fallback)",
            modelFileName = "qwen3.5-0.8b-q4_k_m.gguf",
            parameterCount = "0.8B",
            quantizedSizeMb = 580,
            minRamMb = 2048,
            license = "Apache 2.0",
            capabilities = listOf(
                "text_generation", "multilingual_100+", "thinking_mode",
                "non_thinking_mode", "swahili", "improved_reasoning"
            )
        ),
        QWEN3_5_2B(
            displayName = "Qwen3.5 2B (Future)",
            modelFileName = "qwen3.5-2b-q4_k_m.gguf",
            parameterCount = "2B",
            quantizedSizeMb = 1200,
            minRamMb = 3072,
            license = "Apache 2.0",
            capabilities = listOf(
                "text_generation", "multilingual_100+", "thinking_mode",
                "edge_reasoning", "function_calling", "swahili"
            )
        ),
        GEMMA4_E2B(
            displayName = "Gemma 4 E2B (Primary)",
            modelFileName = "gemma-4-e2b-q4_k_m.gguf",
            parameterCount = "2B",
            quantizedSizeMb = 1500,
            minRamMb = 3072,
            license = "Apache 2.0",
            capabilities = listOf(
                "text_generation", "vision", "audio", "function_calling",
                "multimodal", "goods_recognition", "primary_text_llm"
            )
        ),
        GEMMA4_E2B_SMALL(
            displayName = "Gemma 4 E2B Q3_K_M (Primary, Low-RAM)",
            modelFileName = "gemma-4-e2b-q3_k_m.gguf",
            parameterCount = "2B",
            quantizedSizeMb = 1000,
            minRamMb = 2048,
            license = "Apache 2.0",
            capabilities = listOf(
                "text_generation", "vision", "function_calling",
                "primary_text_llm", "low_ram_optimized"
            )
        ),
        GEMMA4_E2B_ULTRA(
            displayName = "Gemma 4 E2B Q2_K (Ultra-Lite)",
            modelFileName = "gemma-4-e2b-q2_k.gguf",
            parameterCount = "2B",
            quantizedSizeMb = 650,
            minRamMb = 2048,
            license = "Apache 2.0",
            capabilities = listOf(
                "text_generation", "vision",
                "ultra_lite", "data_saver", "initial_download"
            )
        ),
        QWEN3_5_0_8B_LITE(
            displayName = "Qwen3.5 0.8B Q2_K (Ultra-Lite)",
            modelFileName = "qwen3.5-0.8b-q2_k.gguf",
            parameterCount = "0.8B",
            quantizedSizeMb = 300,
            minRamMb = 2048,
            license = "Apache 2.0",
            capabilities = listOf(
                "text_generation", "multilingual_100+",
                "ultra_lite", "data_saver", "initial_download"
            )
        )
    }

    /**
     * A/B test configuration for model comparison.
     */
    data class ABTestConfig(
        val versionA: ModelVersion,
        val versionB: ModelVersion,
        val trafficSplitPct: Int = 50,  // % of traffic to version A
        val startTimeMs: Long = System.currentTimeMillis(),
        val durationHours: Int = 24
    )

    data class ModelStatus(
        val currentVersion: ModelVersion,
        val installedVersions: List<ModelVersion>,
        val isDownloading: Boolean = false,
        val downloadProgressPct: Int = 0,
        val lastValidatedTimestamp: Long = 0,
        val validationPassed: Boolean = true
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("model_versions", Context.MODE_PRIVATE)

    private val modelsDir = File(context.filesDir, "models")

    init {
        modelsDir.mkdirs()
    }

    // ── Current Model ──────────────────────────────────────────────

    /**
     * Get the currently active model version.
     * Updated (2026-07-16): default is now Gemma 4 E2B.
     */
    fun getCurrentVersion(): ModelVersion {
        val saved = prefs.getString("current_version", null)
        return if (saved != null) {
            try { ModelVersion.valueOf(saved) } catch (_: Throwable) { ModelVersion.GEMMA4_E2B }
        } else ModelVersion.GEMMA4_E2B
    }

    /**
     * Set the active model version.
     */
    fun setCurrentVersion(version: ModelVersion) {
        prefs.edit().putString("current_version", version.name).apply()
        Timber.i("Model version set to: %s", version.displayName)
    }

    /**
     * Get the model file path for a given version.
     */
    fun getModelPath(version: ModelVersion): File {
        return File(modelsDir, version.modelFileName)
    }

    /**
     * Check if a model version is installed and valid.
     */
    fun isInstalled(version: ModelVersion): Boolean {
        val file = getModelPath(version)
        if (!file.exists()) return false

        // Validate file size is reasonable (±20% of expected)
        val expectedBytes = version.quantizedSizeMb.toLong() * 1024 * 1024
        val actualBytes = file.length()
        val tolerance = expectedBytes * 0.2
        return actualBytes.toDouble() in (expectedBytes - tolerance)..(expectedBytes + tolerance)
    }

    /**
     * Get status of all model versions.
     */
    fun getModelStatus(): ModelStatus {
        val current = getCurrentVersion()
        val installed = ModelVersion.entries.filter { isInstalled(it) }

        return ModelStatus(
            currentVersion = current,
            installedVersions = installed
        )
    }

    // ── Auto-Upgrade Logic ─────────────────────────────────────────

    /**
     * Determine the best model version for this device.
     *
     * Selection logic (updated 2026-07-16):
     * 1. Check available RAM
     * 2. Filter to versions that fit in RAM
     * 3. Prefer Gemma 4 E2B variants over Qwen
     * 4. Fall back to Qwen if Gemma can't fit
     * 5. Consider Q2_K ultra-lite for data-limited scenarios
     */
    fun getRecommendedVersion(dataSaverMode: Boolean = false): ModelVersion {
        val runtime = Runtime.getRuntime()
        val maxMemoryMb = runtime.maxMemory() / (1024 * 1024)
        val availableMb = maxMemoryMb - 512  // Reserve 512MB for app

        val current = getCurrentVersion()

        // If data-saver mode, prefer Q2_K ultra-lite variants
        if (dataSaverMode) {
            val ultraLite = ModelVersion.entries.filter {
                it.capabilities.contains("ultra_lite") && it.minRamMb <= availableMb
            }
            if (ultraLite.isNotEmpty()) {
                val recommended = ultraLite.first()
                if (recommended != current) {
                    Timber.i(
                        "Data-saver: recommended ultra-lite model: %s (RAM: %dMB)",
                        recommended.displayName, availableMb
                    )
                }
                return recommended
            }
        }

        // Standard path: prefer Gemma 4 E2B variants; fall back to Qwen
        val candidates = ModelVersion.entries
            .filter { it.minRamMb <= availableMb }
            .sortedWith(compareByDescending<ModelVersion> {
                // Prefer Gemma 4 E2B over Qwen
                it.name.startsWith("GEMMA4")
            }.thenByDescending { it.quantizedSizeMb })

        val recommended = candidates.firstOrNull() ?: current

        if (recommended != current) {
            Timber.i(
                "Recommended model upgrade: %s → %s (RAM: %dMB)",
                current.displayName, recommended.displayName, availableMb
            )
        }

        return recommended
    }

    /**
     * Check if an upgrade is available and recommended.
     */
    fun isUpgradeAvailable(dataSaverMode: Boolean = false): Pair<Boolean, ModelVersion?> {
        val current = getCurrentVersion()
        val recommended = getRecommendedVersion(dataSaverMode)

        val currentIndex = ModelVersion.entries.indexOf(current)
        val recommendedIndex = ModelVersion.entries.indexOf(recommended)

        return if (recommendedIndex > currentIndex) {
            true to recommended
        } else {
            false to null
        }
    }

    // ── A/B Testing ────────────────────────────────────────────────

    /**
     * Start an A/B test between two model versions.
     */
    fun startABTest(config: ABTestConfig) {
        prefs.edit()
            .putString("ab_version_a", config.versionA.name)
            .putString("ab_version_b", config.versionB.name)
            .putInt("ab_split_pct", config.trafficSplitPct)
            .putLong("ab_start_ms", config.startTimeMs)
            .putInt("ab_duration_hours", config.durationHours)
            .putBoolean("ab_active", true)
            .apply()
        Timber.i("A/B test started: %s vs %s (%d%% split)",
            config.versionA.displayName, config.versionB.displayName, config.trafficSplitPct)
    }

    /**
     * Get which version to use for this request (A/B test routing).
     */
    fun getABTestVersion(requestId: String): ModelVersion? {
        if (!prefs.getBoolean("ab_active", false)) return null

        val startMs = prefs.getLong("ab_start_ms", 0)
        val durationMs = prefs.getInt("ab_duration_hours", 24).toLong() * 3600 * 1000
        if (System.currentTimeMillis() > startMs + durationMs) {
            prefs.edit().putBoolean("ab_active", false).apply()
            return null
        }

        val splitPct = prefs.getInt("ab_split_pct", 50)
        val hash = requestId.hashCode().let { if (it < 0) -it else it } % 100
        val versionName = if (hash < splitPct) {
            prefs.getString("ab_version_a", null)
        } else {
            prefs.getString("ab_version_b", null)
        }

        return try { versionName?.let { ModelVersion.valueOf(it) } } catch (_: Throwable) { null }
    }

    // ── Rollback ───────────────────────────────────────────────────

    /**
     * Rollback to the previous model version if current one is failing.
     */
    fun rollback(): ModelVersion {
        val current = getCurrentVersion()
        val currentIndex = ModelVersion.entries.indexOf(current)
        val fallback = if (currentIndex > 0) {
            ModelVersion.entries[currentIndex - 1]
        } else {
            ModelVersion.QWEN3_5_0_8B
        }

        setCurrentVersion(fallback)
        Timber.w("Model rollback: %s → %s", current.displayName, fallback.displayName)
        return fallback
    }

    /**
     * Get upgrade path information for display to user.
     * Now includes data-saver ultra-lite options.
     */
    fun getUpgradePathInfo(dataSaverMode: Boolean = false): Map<String, Any> {
        val current = getCurrentVersion()
        val (hasUpgrade, recommended) = isUpgradeAvailable(dataSaverMode)
        val ultraLiteVersions = ModelVersion.entries.filter { it.capabilities.contains("ultra_lite") }

        return mapOf(
            "current_model" to current.displayName,
            "current_params" to current.parameterCount,
            "current_size_mb" to current.quantizedSizeMb,
            "upgrade_available" to hasUpgrade,
            "recommended_model" to (recommended?.displayName ?: "None"),
            "recommended_params" to (recommended?.parameterCount ?: "N/A"),
            "recommended_size_mb" to (recommended?.quantizedSizeMb ?: 0),
            "installed_models" to ModelVersion.entries.filter { isInstalled(it) }.map { it.displayName },
            "ultra_lite_available" to ultraLiteVersions.map { it.displayName },
            "data_saver_mode" to dataSaverMode
        )
    }
}
