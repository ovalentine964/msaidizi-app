package com.msaidizi.core.voice.registry

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registry for voice-related model files (ASR, TTS, VAD).
 *
 * This is a lightweight registry specific to voice models. It resolves
 * model file paths on disk and provides availability checks. The actual
 * model downloading is handled by the `:core:model` module's
 * [com.msaidizi.core.model.downloader.ModelDownloader].
 *
 * ## Model Storage
 *
 * Models are stored in `context.filesDir/models/` as individual files:
 * - `whisper-encoder-int8.onnx` + `whisper-decoder-int8.onnx` + `whisper-tokens.json`
 * - `piper-swahili.onnx` + `tokens.txt` + `espeak-ng-data/`
 * - `silero_vad.onnx`
 *
 * ## Integration
 *
 * Voice engines (WhisperSttEngine, PiperTtsEngine, VoiceActivityDetector)
 * use this registry to locate their model files. The registry does NOT
 * download models — that's the ModelDownloader's job.
 *
 * @see com.msaidizi.core.model.downloader.ModelDownloader for model downloads
 */
@Singleton
class VoiceModelRegistry @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    // ── Voice model definitions ──

    /** Voice model IDs and their expected files */
    private val voiceModels = mapOf(
        "whisper-tiny-int4" to mapOf(
            "encoder" to "whisper-encoder-int8.onnx",
            "decoder" to "whisper-decoder-int8.onnx",
            "tokens" to "whisper-tokens.json"
        ),
        "moonshine-tiny" to mapOf(
            "encoder" to "moonshine-tiny-encoder.onnx",
            "decoder" to "moonshine-tiny-decoder.onnx"
        ),
        "piper-swahili" to mapOf(
            "model" to "piper-swahili.onnx"
        ),
        "silero-vad" to mapOf(
            "model" to "silero_vad.onnx"
        )
    )

    /**
     * Check if a voice model is ready (all files present on disk).
     *
     * @param modelId Model identifier (e.g. "whisper-tiny-int4", "piper-swahili")
     * @return true if all required files exist and are non-empty
     */
    fun isModelReady(modelId: String): Boolean {
        val files = voiceModels[modelId] ?: return false
        return files.values.all { filename ->
            val file = File(modelsDir, filename)
            file.exists() && file.length() > 0
        }
    }

    /**
     * Get the path to a single-file model.
     *
     * @param modelId Model identifier
     * @return File path, or null if not available
     */
    fun getModelPath(modelId: String): File? {
        val files = voiceModels[modelId] ?: return null
        // For single-file models, return the "model" entry
        val filename = files["model"] ?: files.values.firstOrNull() ?: return null
        val file = File(modelsDir, filename)
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * Get the path to a specific file within a multi-file model.
     *
     * @param modelId Model identifier (e.g. "whisper-tiny-int4")
     * @param fileKey File key (e.g. "encoder", "decoder", "tokens")
     * @return File path, or null if not available
     */
    fun getModelFilePath(modelId: String, fileKey: String): File? {
        val files = voiceModels[modelId] ?: return null
        val filename = files[fileKey] ?: return null
        val file = File(modelsDir, filename)
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * Get the models directory.
     */
    fun getModelsDir(): File = modelsDir

    /**
     * Get list of all available voice models.
     */
    fun getAvailableModels(): Set<String> {
        return voiceModels.keys.filter { isModelReady(it) }.toSet()
    }
}
