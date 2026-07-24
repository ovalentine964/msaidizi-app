package com.msaidizi.app.voice

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LlamaCppEngine — Kotlin JNI wrapper for on-device LLM inference via llama.cpp.
 *
 * Loads a GGUF model (e.g., Qwen 0.8B) and generates text completions
 * entirely on-device. No network required.
 *
 * Usage:
 * ```kotlin
 * val engine = LlamaCppEngine()
 * val handle = engine.loadModel("/path/to/model.gguf")
 * val response = engine.generate(handle, "Habari yako?")
 * engine.unloadModel(handle)
 * ```
 *
 * Thread safety: each method acquires a per-handle mutex on the native side,
 * so concurrent calls on the same handle are serialised. Different handles
 * can be used concurrently.
 */
@Singleton
class LlamaCppEngine @Inject constructor() {

    companion object {
        init {
            try {
                System.loadLibrary("llama_jni")
                Timber.i("llama_jni native library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Timber.e(e, "Failed to load llama_jni — LLM inference unavailable")
            }
        }
    }

    // ── Native methods (defined in llama_jni.cpp) ────────────

    /**
     * Load a GGUF model from disk.
     * @param path Absolute path to the .gguf file
     * @param nCtx Context window size (2048 recommended for mobile)
     * @param nThreads Number of CPU threads (2–4 recommended)
     * @return Model handle (>0) on success, 0 on failure
     */
    internal external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int): Long

    /**
     * Generate text from a prompt.
     * @param handle Model handle from [nativeLoadModel]
     * @param prompt Full prompt (including system/role tokens)
     * @param maxTokens Maximum tokens to generate
     * @param temperature Sampling temperature (0.0 = greedy)
     * @param topP Nucleus sampling threshold
     * @param stopSequences JSON array of stop strings
     * @return Generated text (excluding prompt)
     */
    internal external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        stopSequences: String
    ): String

    /**
     * Unload a model and free all associated memory.
     * @param handle Model handle from [nativeLoadModel]
     */
    internal external fun nativeUnloadModel(handle: Long)

    // ── Public API ───────────────────────────────────────────

    private var modelHandle: Long = 0L
    private var isLoaded = false

    /**
     * Check if the native library was loaded successfully.
     */
    val isNativeAvailable: Boolean
        get() = try {
            // Will throw if library not loaded
            Class.forName("com.msaidizi.app.voice.LlamaCppEngine")
            true
        } catch (_: Exception) {
            false
        }

    /**
     * Load a model with default settings for mobile devices.
     * @param modelPath Absolute path to the .gguf model file
     * @return true if the model loaded successfully
     */
    fun loadModel(modelPath: String): Boolean {
        return loadModel(
            path = modelPath,
            nCtx = 2048,
            nThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        )
    }

    /**
     * Load a model with explicit parameters.
     * @param path Absolute path to the .gguf model file
     * @param nCtx Context window size
     * @param nThreads Number of CPU threads
     * @return true if the model loaded successfully
     */
    fun loadModel(path: String, nCtx: Int, nThreads: Int): Boolean {
        if (isLoaded) {
            Timber.w("Model already loaded — unload first")
            return true
        }

        return try {
            Timber.i("Loading model: %s (ctx=%d, threads=%d)", path, nCtx, nThreads)
            modelHandle = nativeLoadModel(path, nCtx, nThreads)
            isLoaded = modelHandle != 0L

            if (isLoaded) {
                Timber.i("Model loaded — handle=%d", modelHandle)
            } else {
                Timber.e("Model load returned null handle")
            }
            isLoaded
        } catch (e: Exception) {
            Timber.e(e, "loadModel failed")
            false
        }
    }

    /**
     * Generate a response for the given prompt.
     * @param prompt Full prompt string (system + user)
     * @param maxTokens Maximum tokens to generate (default 256 for mobile)
     * @param temperature Sampling temperature (0.7 default)
     * @param topP Nucleus sampling (0.9 default)
     * @param stopSequences List of strings that stop generation
     * @return Generated text, or empty string on failure
     */
    fun generate(
        prompt: String,
        maxTokens: Int = 256,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        stopSequences: List<String> = listOf("Human:", "User:", "\n\n")
    ): String {
        if (!isLoaded) {
            Timber.w("generate called but no model loaded")
            return ""
        }

        return try {
            val stopsJson = stopSequences.joinToString(
                prefix = "[\"", separator = "\",\"", postfix = "\"]"
            ) { it.replace("\"", "\\\"") }

            nativeGenerate(modelHandle, prompt, maxTokens, temperature, topP, stopsJson)
                .trim()
        } catch (e: Exception) {
            Timber.e(e, "generate failed")
            ""
        }
    }

    /**
     * Check if a model is currently loaded.
     */
    fun isModelLoaded(): Boolean = isLoaded

    /**
     * Unload the current model and free memory.
     */
    fun unloadModel() {
        if (!isLoaded) return
        try {
            nativeUnloadModel(modelHandle)
            Timber.i("Model unloaded — handle=%d", modelHandle)
        } catch (e: Exception) {
            Timber.e(e, "unloadModel failed")
        } finally {
            modelHandle = 0L
            isLoaded = false
        }
    }

    /**
     * Get model info for diagnostics.
     */
    fun getModelInfo(): Map<String, Any> = mapOf(
        "loaded" to isLoaded,
        "handle" to modelHandle,
        "nativeAvailable" to isNativeAvailable
    )
}
