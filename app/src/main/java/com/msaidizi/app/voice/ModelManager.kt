package com.msaidizi.app.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ModelManager — Manages on-device AI model lifecycle.
 *
 * Responsibilities:
 * - Check if models exist in app internal storage ([filesDir]/models/)
 * - Copy bundled models from assets/ on first launch (one-time)
 * - Report model status (present / missing / corrupted)
 * - Provide model paths to engine classes
 *
 * Model layout in assets/:
 * ```
 * assets/models/
 *   gguf/
 *     Qwen3.5-0.8B-Q4_K_M.gguf        ← LLM
 *   onnx-whisper/
 *     encoder.onnx, decoder.onnx, tokens.txt  ← STT
 *   onnx-piper/
 *     piper-sw/
 *       model.onnx, tokens.txt, espeak-ng-data/  ← TTS (Swahili)
 *     piper-en/
 *       model.onnx, tokens.txt, espeak-ng-data/  ← TTS (English)
 * ```
 *
 * After first extraction, models live at:
 * ```
 * filesDir/models/
 *   Qwen3.5-0.8B-Q4_K_M.gguf
 *   sherpa-onnx/
 *     whisper/
 *       encoder.onnx, decoder.onnx, tokens.txt
 *     piper-sw/
 *       model.onnx, tokens.txt, espeak-ng-data/
 *     piper-en/
 *       model.onnx, tokens.txt, espeak-ng-data/
 * ```
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelTier: ModelTier,
    private val whisperConfig: WhisperModelConfig,
    private val deltaUpdater: DeltaModelUpdater,
    private val batteryOptimizer: BatteryOptimizer
) {
    companion object {
        private const val MODELS_DIR = "models"
        private const val ASSETS_MODELS_DIR = "models"
        private const val PREFS_NAME = "model_manager"
        private const val KEY_ASSETS_EXTRACTED = "assets_extracted_v1"

        /** Minimum plausible GGUF size (1 MB). */
        private const val MIN_GGUF_SIZE = 1L * 1024 * 1024

        /** Minimum plausible ONNX size (10 KB). */
        private const val MIN_ONNX_SIZE = 10L * 1024
    }

    /** Observable current tier for UI updates. */
    private val _currentTier = MutableStateFlow(modelTier.getActiveTier())
    val currentTier: StateFlow<ModelTier.Tier> = _currentTier.asStateFlow()

    /** Model directories in assets/. */
    private data class AssetMapping(
        val assetPath: String,      // path under assets/models/
        val storagePath: String,    // path under filesDir/models/
        val requiredFiles: List<String>
    )

    private val assetMappings = listOf(
        // LLM — GGUF
        AssetMapping(
            assetPath = "gguf",
            storagePath = ".",  // filesDir/models/Qwen3.5-0.8B-Q4_K_M.gguf
            requiredFiles = listOf("Qwen3.5-0.8B-Q4_K_M.gguf")
        ),
        // STT — Whisper
        AssetMapping(
            assetPath = "onnx-whisper",
            storagePath = "sherpa-onnx/whisper",
            requiredFiles = listOf("tokens.txt")
        ),
        // TTS — Piper Swahili
        AssetMapping(
            assetPath = "onnx-piper/piper-sw",
            storagePath = "sherpa-onnx/piper-sw",
            requiredFiles = listOf("model.onnx")
        ),
        // TTS — Piper English (optional)
        AssetMapping(
            assetPath = "onnx-piper/piper-en",
            storagePath = "sherpa-onnx/piper-en",
            requiredFiles = listOf("model.onnx")
        )
    )

    private val modelsDir: File
        get() = File(context.filesDir, MODELS_DIR)

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── Public API ───────────────────────────────────────────

    /**
     * Ensure all models are available in internal storage.
     *
     * On first launch, copies bundled models from assets/ to filesDir/.
     * Subsequent calls are no-ops (fast path: checks SharedPreferences flag).
     *
     * @return true if all required models are present after this call.
     */
    suspend fun ensureModelsAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (prefs.getBoolean(KEY_ASSETS_EXTRACTED, false)) {
            // Already extracted — quick validation
            return@withContext validateRequiredModels()
        }

        Timber.i("First launch — extracting bundled models from assets/")
        val success = extractAllAssets()

        if (success) {
            prefs.edit().putBoolean(KEY_ASSETS_EXTRACTED, true).apply()
            Timber.i("All models extracted successfully")
        } else {
            Timber.w("Some models failed to extract")
        }

        success
    }

    /**
     * Get the path to the LLM model file.
     * Uses the active tier to determine which model to load.
     * @return Absolute path, or null if not available.
     */
    fun getLlmModelPath(): String? {
        val tier = modelTier.getActiveTier()
        // Try tier-specific model first
        val tierFile = File(modelsDir, tier.llmModelFilename)
        if (tierFile.exists() && tierFile.length() >= MIN_GGUF_SIZE) {
            return tierFile.absolutePath
        }
        // Try the old default model
        val defaultFile = File(modelsDir, "Qwen3.5-0.8B-Q4_K_M.gguf")
        if (defaultFile.exists() && defaultFile.length() >= MIN_GGUF_SIZE) {
            return defaultFile.absolutePath
        }
        // Try any .gguf file as fallback
        return modelsDir.listFiles()?.firstOrNull {
            it.extension.equals("gguf", ignoreCase = true) && it.length() >= MIN_GGUF_SIZE
        }?.absolutePath
    }

    /**
     * Switch to a different model tier without app restart.
     * @param newTier The tier to switch to
     * @return true if the switch was successful
     */
    suspend fun switchTier(newTier: ModelTier.Tier): Boolean {
        val switched = modelTier.switchTier(newTier)
        if (switched) {
            _currentTier.value = newTier
            Timber.i("ModelManager: switched to tier %s", newTier.displayName)
        }
        return switched
    }

    /**
     * Get the active model tier.
     */
    fun getActiveTier(): ModelTier.Tier = modelTier.getActiveTier()

    /**
     * Get the recommended tier for this device.
     */
    fun getRecommendedTier(): ModelTier.Tier = modelTier.detectRecommendedTier()

    /**
     * Get tier comparison info for settings UI.
     */
    fun getTierComparison(): List<TierInfo> = modelTier.getTierComparison()

    /**
     * Get device info for diagnostics.
     */
    fun getDeviceInfo(): DeviceInfo = modelTier.getDeviceInfo()

    /**
     * Get the context size for the current tier and battery state.
     */
    fun getEffectiveContextSize(): Int {
        val tier = modelTier.getActiveTier()
        val batteryProfile = batteryOptimizer.getPerformanceProfile()
        return minOf(tier.nCtx, batteryProfile.contextSize)
    }

    /**
     * Get the max tokens for the current tier and battery state.
     */
    fun getEffectiveMaxTokens(): Int {
        val batteryProfile = batteryOptimizer.getPerformanceProfile()
        return batteryProfile.maxTokens
    }

    /**
     * Get the Whisper model configuration.
     */
    fun getWhisperConfig(): WhisperModelConfig = whisperConfig

    /**
     * Get the delta updater.
     */
    fun getDeltaUpdater(): DeltaModelUpdater = deltaUpdater

    /**
     * Get the battery optimizer.
     */
    fun getBatteryOptimizer(): BatteryOptimizer = batteryOptimizer

    /**
     * Get the directory for Whisper STT models.
     * @return Directory path, or null if not available.
     */
    fun getWhisperModelDir(): String? {
        val dir = File(modelsDir, "sherpa-onnx/whisper")
        return if (dir.exists() && hasRequiredOnnxFiles(dir)) {
            dir.absolutePath
        } else {
            null
        }
    }

    /**
     * Get the directory for Piper TTS models for a given language.
     * @param language "sw" or "en"
     * @return Directory path, or null if not available.
     */
    fun getPiperModelDir(language: String = "sw"): String? {
        val dir = File(modelsDir, "sherpa-onnx/piper-$language")
        return if (dir.exists() && File(dir, "model.onnx").exists()) {
            dir.absolutePath
        } else {
            null
        }
    }

    /**
     * Get comprehensive model status report.
     */
    fun getModelStatus(): ModelStatus {
        val llmFile = File(modelsDir, "Qwen3.5-0.8B-Q4_K_M.gguf")
        val whisperDir = File(modelsDir, "sherpa-onnx/whisper")
        val piperSwDir = File(modelsDir, "sherpa-onnx/piper-sw")
        val piperEnDir = File(modelsDir, "sherpa-onnx/piper-en")

        return ModelStatus(
            llm = checkModelFile(llmFile, MIN_GGUF_SIZE),
            sttWhisper = checkModelDir(whisperDir, listOf("tokens.txt")),
            ttsPiperSw = checkModelDir(piperSwDir, listOf("model.onnx")),
            ttsPiperEn = checkModelDir(piperEnDir, listOf("model.onnx")),
            assetsExtracted = prefs.getBoolean(KEY_ASSETS_EXTRACTED, false),
            modelsBaseDir = modelsDir.absolutePath
        )
    }

    /**
     * Force re-extraction of models from assets (e.g., after an update).
     */
    suspend fun forceReExtract(): Boolean = withContext(Dispatchers.IO) {
        Timber.i("Force re-extracting models from assets/")
        prefs.edit().putBoolean(KEY_ASSETS_EXTRACTED, false).apply()
        extractAllAssets()
    }

    /**
     * Delete all models from internal storage to free space.
     */
    fun deleteAllModels() {
        Timber.w("Deleting all models from internal storage")
        modelsDir.deleteRecursively()
        modelsDir.mkdirs()
        prefs.edit().putBoolean(KEY_ASSETS_EXTRACTED, false).apply()
    }

    // ── Asset extraction ─────────────────────────────────────

    private fun extractAllAssets(): Boolean {
        modelsDir.mkdirs()
        var allSuccess = true

        for (mapping in assetMappings) {
            try {
                extractAssetGroup(mapping)
            } catch (e: Exception) {
                Timber.e(e, "Failed to extract asset group: ${mapping.assetPath}")
                // Don't fail for optional models (e.g., piper-en)
                if (mapping.assetPath != "onnx-piper/piper-en") {
                    allSuccess = false
                }
            }
        }

        return allSuccess
    }

    private fun extractAssetGroup(mapping: AssetMapping) {
        val destDir = File(modelsDir, mapping.storagePath)
        destDir.mkdirs()

        val assetDir = "$ASSETS_MODELS_DIR/${mapping.assetPath}"

        // List files in the asset directory
        val files = try {
            context.assets.list(assetDir) ?: emptyArray()
        } catch (e: Exception) {
            Timber.w("Asset directory not found: $assetDir")
            emptyArray()
        }

        if (files.isEmpty()) {
            Timber.d("No files in assets/$assetDir (may be placeholder)")
            return
        }

        // Copy each file
        for (fileName in files) {
            if (fileName == ".gitkeep") continue

            val assetPath = "$assetDir/$fileName"
            val destFile = File(destDir, fileName)

            // Check if it's a subdirectory
            val subFiles = try {
                context.assets.list(assetPath)
            } catch (e: Exception) {
                null
            }

            if (subFiles != null && subFiles.isNotEmpty()) {
                // It's a directory — recurse
                destFile.mkdirs()
                for (subFile in subFiles) {
                    if (subFile == ".gitkeep") continue
                    extractFile("$assetPath/$subFile", File(destFile, subFile))
                }
            } else {
                // It's a file — copy
                extractFile(assetPath, destFile)
            }
        }

        Timber.i("Extracted assets/$assetDir → ${destDir.absolutePath}")
    }

    private fun extractFile(assetPath: String, destFile: File) {
        if (destFile.exists() && destFile.length() > 0) {
            Timber.d("Already exists: ${destFile.name}")
            return
        }

        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            Timber.d("Extracted: ${destFile.name} (${destFile.length()} bytes)")
        } catch (e: Exception) {
            Timber.w(e, "Failed to extract: $assetPath")
        }
    }

    // ── Validation ───────────────────────────────────────────

    private fun validateRequiredModels(): Boolean {
        // Check LLM
        val llmPath = getLlmModelPath()
        if (llmPath == null) {
            Timber.w("LLM model not found")
            return false
        }

        // Check STT
        val whisperPath = getWhisperModelDir()
        if (whisperPath == null) {
            Timber.w("Whisper STT model not found")
            return false
        }

        // TTS is optional (can fall back to Android TTS)
        return true
    }

    private fun hasRequiredOnnxFiles(dir: File): Boolean {
        // Accept either encoder+decoder or single model
        val hasEncoder = File(dir, "encoder.onnx").exists()
        val hasModel = File(dir, "model.onnx").exists()
        val hasTokens = File(dir, "tokens.txt").exists()
        return (hasEncoder || hasModel) && hasTokens
    }

    private fun checkModelFile(file: File, minSize: Long): ModelFileInfo {
        return when {
            !file.exists() -> ModelFileInfo(
                name = file.name,
                path = file.absolutePath,
                status = ModelFileStatus.MISSING,
                sizeBytes = 0
            )
            file.length() < minSize -> ModelFileInfo(
                name = file.name,
                path = file.absolutePath,
                status = ModelFileStatus.CORRUPTED,
                sizeBytes = file.length(),
                error = "File too small (${file.length()} bytes, expected ≥$minSize)"
            )
            else -> ModelFileInfo(
                name = file.name,
                path = file.absolutePath,
                status = ModelFileStatus.PRESENT,
                sizeBytes = file.length()
            )
        }
    }

    private fun checkModelDir(dir: File, requiredFiles: List<String>): ModelFileInfo {
        if (!dir.exists()) {
            return ModelFileInfo(
                name = dir.name,
                path = dir.absolutePath,
                status = ModelFileStatus.MISSING,
                sizeBytes = 0
            )
        }

        val missingFiles = requiredFiles.filter { !File(dir, it).exists() }
        if (missingFiles.isNotEmpty()) {
            return ModelFileInfo(
                name = dir.name,
                path = dir.absolutePath,
                status = ModelFileStatus.CORRUPTED,
                sizeBytes = dirSize(dir),
                error = "Missing files: ${missingFiles.joinToString(", ")}"
            )
        }

        return ModelFileInfo(
            name = dir.name,
            path = dir.absolutePath,
            status = ModelFileStatus.PRESENT,
            sizeBytes = dirSize(dir)
        )
    }

    private fun dirSize(dir: File): Long {
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}

// ── Status data classes ──────────────────────────────────────

enum class ModelFileStatus {
    PRESENT,    // File exists and passes size check
    MISSING,    // File does not exist
    CORRUPTED   // File exists but fails validation
}

data class ModelFileInfo(
    val name: String,
    val path: String,
    val status: ModelFileStatus,
    val sizeBytes: Long,
    val error: String? = null
) {
    val sizeMb: Double get() = sizeBytes / (1024.0 * 1024.0)
    val isReady: Boolean get() = status == ModelFileStatus.PRESENT
}

data class ModelStatus(
    val llm: ModelFileInfo,
    val sttWhisper: ModelFileInfo,
    val ttsPiperSw: ModelFileInfo,
    val ttsPiperEn: ModelFileInfo,
    val assetsExtracted: Boolean,
    val modelsBaseDir: String
) {
    val allRequiredPresent: Boolean
        get() = llm.isReady && sttWhisper.isReady

    val allPresent: Boolean
        get() = llm.isReady && sttWhisper.isReady && ttsPiperSw.isReady

    val totalSizeMb: Double
        get() = (llm.sizeBytes + sttWhisper.sizeBytes + ttsPiperSw.sizeBytes + ttsPiperEn.sizeBytes) / (1024.0 * 1024.0)

    fun toSummaryString(): String = buildString {
        appendLine("Model Status:")
        appendLine("  LLM (Qwen3.5 0.8B): ${llm.status} (${String.format("%.1f", llm.sizeMb)} MB)")
        appendLine("  STT (Whisper):     ${sttWhisper.status} (${String.format("%.1f", sttWhisper.sizeMb)} MB)")
        appendLine("  TTS (Piper SW):    ${ttsPiperSw.status} (${String.format("%.1f", ttsPiperSw.sizeMb)} MB)")
        appendLine("  TTS (Piper EN):    ${ttsPiperEn.status} (${String.format("%.1f", ttsPiperEn.sizeMb)} MB)")
        appendLine("  Total: ${String.format("%.1f", totalSizeMb)} MB")
        appendLine("  Assets extracted: $assetsExtracted")
    }
}
