package com.msaidizi.app.voice.waxal

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.msaidizi.app.voice.ModelRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WAXAL LoRA adapter integration for on-device ASR fine-tuning.
 *
 * WAXAL (Google, 2024): 27 African languages, 1,846+ hours ASR, CC-BY-4.0.
 * This class loads a LoRA adapter (~5MB) that improves Whisper Tiny's
 * accuracy on Swahili and other African languages WITHOUT increasing
 * the base model size.
 *
 * Architecture:
 * - Base: Whisper Tiny INT4 (~40MB)
 * - Adapter: WAXAL Swahili LoRA (~5MB)
 * - Total: ~45MB — still fits on 2GB phones
 *
 * The adapter modifies attention layers (q_proj, v_proj, k_proj, out_proj)
 * with low-rank updates (rank=16) learned from WAXAL training data.
 *
 * Usage in SpeechRecognizer:
 * ```kotlin
 * // Load base model + adapter
 * speechRecognizer.loadModel("whisper-tiny-int4")
 * waxalAdapter.loadAdapter("sw")
 *
 * // Transcribe with adapter-enhanced accuracy
 * val result = speechRecognizer.transcribe(audioData)
 * ```
 *
 * Supported languages (from WAXAL):
 * - sw: Swahili (120+ hours)
 * - ha: Hausa (80+ hours)
 * - yo: Yoruba (60+ hours)
 * - ig: Igbo (50+ hours)
 * - am: Amharic (70+ hours)
 * - zu: Zulu (40+ hours)
 * - xh: Xhosa (35+ hours)
 * - so: Somali (45+ hours)
 */
@Singleton
class WaxalAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRegistry: ModelRegistry
) {
    companion object {
        private const val MODEL_ID_PREFIX = "waxal"
        private const val TAG = "WaxalAdapter"

        /** Languages with WAXAL adapters */
        val SUPPORTED_LANGUAGES = setOf(
            "sw", "ha", "yo", "ig", "am", "zu", "xh", "so"
        )
    }

    // ────────────── State ──────────────
    private var ortEnvironment: OrtEnvironment? = null
    private var adapterSession: OrtSession? = null
    private var activeLanguage: String? = null
    private var isAdapterLoaded = false

    // LoRA weight matrices (loaded from adapter ONNX)
    private var loraWeights: Map<String, FloatArray> = emptyMap()

    // ────────────── Adapter Lifecycle ──────────────

    /**
     * Load the WAXAL LoRA adapter for a specific language.
     *
     * @param language ISO language code (e.g., "sw", "ha", "yo")
     * @return true if adapter loaded successfully
     */
    suspend fun loadAdapter(language: String): Boolean = withContext(Dispatchers.IO) {
        if (isAdapterLoaded && activeLanguage == language) {
            return@withContext true
        }

        val modelId = "$MODEL_ID_PREFIX-${language}-adapter"
        val modelFile = modelRegistry.getModelPath(modelId)

        if (modelFile == null) {
            Timber.tag(TAG).w("WAXAL adapter not found for language: %s", language)
            return@withContext false
        }

        try {
            val startTime = System.currentTimeMillis()

            // Unload previous adapter if different language
            if (isAdapterLoaded && activeLanguage != language) {
                unloadAdapter()
            }

            ortEnvironment = OrtEnvironment.getEnvironment()

            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)  // Adapter is small, 1 thread is enough
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            // Load adapter weights
            if (modelFile.isDirectory) {
                // Multi-file adapter
                val adapterOnnx = File(modelFile, "adapter.onnx")
                if (adapterOnnx.exists()) {
                    adapterSession = ortEnvironment!!.createSession(
                        adapterOnnx.absolutePath, sessionOptions
                    )
                }
            } else {
                // Single-file adapter
                adapterSession = ortEnvironment!!.createSession(
                    modelFile.absolutePath, sessionOptions
                )
            }

            activeLanguage = language
            isAdapterLoaded = true

            val elapsed = System.currentTimeMillis() - startTime
            Timber.tag(TAG).i("WAXAL adapter loaded for '%s' in %dms", language, elapsed)
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to load WAXAL adapter for: %s", language)
            isAdapterLoaded = false
            false
        }
    }

    /**
     * Unload the adapter to free memory (~5MB).
     */
    fun unloadAdapter() {
        adapterSession?.close()
        adapterSession = null
        ortEnvironment = null
        loraWeights = emptyMap()
        isAdapterLoaded = false
        activeLanguage = null
        Timber.tag(TAG).d("WAXAL adapter unloaded")
    }

    fun isAdapterReady(): Boolean = isAdapterLoaded

    fun getActiveLanguage(): String? = activeLanguage

    /**
     * Check if a language has a WAXAL adapter available.
     */
    fun hasAdapter(language: String): Boolean {
        val modelId = "$MODEL_ID_PREFIX-${language}-adapter"
        return modelRegistry.isModelReady(modelId)
    }

    /**
     * Get the model ID for a language's WAXAL adapter.
     */
    fun getAdapterModelId(language: String): String {
        return "$MODEL_ID_PREFIX-${language}-adapter"
    }

    /**
     * Apply the LoRA adapter to encoder hidden states.
     *
     * This modifies the encoder output by adding the low-rank update:
     *   h' = h + α · A · B · h
     *
     * where A and B are the LoRA weight matrices, and α is the scaling factor.
     *
     * @param encoderHidden Encoder output tensor [1, seq_len, hidden_dim]
     * @return Modified encoder output with LoRA adaptation applied
     */
    fun applyAdapter(encoderHidden: OnnxTensor): OnnxTensor? {
        if (!isAdapterLoaded || adapterSession == null) {
            return null
        }

        return try {
            val env = ortEnvironment!!
            val session = adapterSession!!

            val results = session.run(mapOf("encoder_hidden_states" to encoderHidden))
            val adapted = results.get("adapted_hidden_states") as OnnxTensor

            adapted
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to apply WAXAL adapter")
            null
        }
    }

    /**
     * Get adapter status for diagnostics.
     */
    fun getStatus(): Map<String, Any> = mapOf(
        "loaded" to isAdapterLoaded,
        "language" to (activeLanguage ?: "none"),
        "supportedLanguages" to SUPPORTED_LANGUAGES,
        "availableAdapters" to SUPPORTED_LANGUAGES.filter { hasAdapter(it) }
    )
}
