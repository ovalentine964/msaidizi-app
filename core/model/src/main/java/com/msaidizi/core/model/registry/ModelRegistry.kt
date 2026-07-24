package com.msaidizi.core.model.registry

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central registry for all ML model definitions and file management.
 *
 * ## Model Inventory
 *
 * | Model                        | Size      | Purpose                  |
 * |------------------------------|-----------|--------------------------|
 * | silero-vad                   | ~2.5MB    | Voice activity detection |
 * | whisper-tiny-int4            | ~40MB     | Speech recognition (ASR) |
 * | piper-swahili                | ~25MB     | Text-to-speech (TTS)     |
 * | gemma-4-e2b-q4km (Primary)  | ~1500MB   | Primary text + vision    |
 * | gemma-4-e2b-q3km             | ~1000MB   | LOW-tier primary          |
 * | qwen-3.5-0.8b-q4km (Fallback)| ~580MB   | Fallback for low memory  |
 * | qwen-3.5-0.8b-q2k            | ~300MB    | Data-saver lite variant  |
 * | gemma-4-e2b-q2k              | ~650MB    | Data-saver lite variant  |
 *
 * ## Storage
 * Models are stored in `context.filesDir/models/`.
 * NOT bundled in the APK (too large). Downloaded on first launch.
 *
 * @see com.msaidizi.core.model.downloader.ModelDownloader for downloads
 */
@Singleton
class ModelRegistry @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    /**
     * Model definitions with metadata.
     */
    data class ModelDef(
        val id: String,
        val filename: String,
        val sizeBytes: Long,
        val priority: Priority,
        val tier: Tier,
        val version: String = "1.0.0"
    )

    enum class Priority { CRITICAL, HIGH, LOW }
    enum class Tier { BUNDLED, FIRST_LAUNCH, ON_DEMAND }

    /** All registered models */
    val models: Map<String, ModelDef> = mapOf(
        "silero-vad" to ModelDef("silero-vad", "silero_vad.onnx", 643_854, Priority.CRITICAL, Tier.BUNDLED, "5.1.2"),
        "whisper-tiny-int4" to ModelDef("whisper-tiny-int4", "whisper-encoder-int8.onnx", 39_000_000, Priority.HIGH, Tier.FIRST_LAUNCH, "1.0.0"),
        "piper-swahili" to ModelDef("piper-swahili", "piper-swahili.onnx", 63_149_224, Priority.HIGH, Tier.FIRST_LAUNCH, "1.0.0"),
        "gemma-4-e2b-q4km" to ModelDef("gemma-4-e2b-q4km", "gemma-4-e2b-Q4_K_M.gguf", 1_500_000_000, Priority.LOW, Tier.ON_DEMAND, "1.0.0"),
        "gemma-4-e2b-q3km" to ModelDef("gemma-4-e2b-q3km", "gemma-4-e2b-Q3_K_M.gguf", 1_000_000_000, Priority.LOW, Tier.ON_DEMAND, "1.0.0"),
        "gemma-4-e2b-q2k" to ModelDef("gemma-4-e2b-q2k", "gemma-4-e2b-Q2_K.gguf", 650_000_000, Priority.LOW, Tier.ON_DEMAND, "1.0.0"),
        "qwen-3.5-0.8b-q4km" to ModelDef("qwen-3.5-0.8b-q4km", "Qwen3.5-0.8B-Q4_K_M.gguf", 579_615_840, Priority.LOW, Tier.ON_DEMAND, "2.0.0"),
        "qwen-3.5-0.8b-q2k" to ModelDef("qwen-3.5-0.8b-q2k", "Qwen3.5-0.8B-Q2_K.gguf", 300_000_000, Priority.LOW, Tier.ON_DEMAND, "1.0.0"),
        "qwen-3.5-2b-q4km" to ModelDef("qwen-3.5-2b-q4km", "Qwen3.5-2B-Q4_K_M.gguf", 1_200_000_000, Priority.LOW, Tier.ON_DEMAND, "1.0.0")
    )

    /** Check if a model is ready on disk */
    fun isModelReady(modelId: String): Boolean {
        val def = models[modelId] ?: return false
        val file = File(modelsDir, def.filename)
        return file.exists() && file.length() > 0
    }

    /** Get the file path for a model */
    fun getModelPath(modelId: String): File? {
        val def = models[modelId] ?: return null
        val file = File(modelsDir, def.filename)
        return if (file.exists() && file.length() > 0) file else null
    }

    /** Get models by tier */
    fun getModelsByTier(tier: Tier): List<ModelDef> {
        return models.values.filter { it.tier == tier }
    }

    /** Get all available model IDs */
    fun getAvailableModels(): Set<String> {
        return models.keys.filter { isModelReady(it) }.toSet()
    }

    /** Get storage used by models in bytes */
    fun getStorageUsedBytes(): Long {
        return modelsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /** Get formatted storage usage */
    fun getStorageUsedFormatted(): String {
        val bytes = getStorageUsedBytes()
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }

    /** Delete all models */
    fun deleteAllModels() {
        modelsDir.listFiles()?.forEach { it.delete() }
    }

    /** Get the models directory */
    fun getModelsDir(): File = modelsDir
}
