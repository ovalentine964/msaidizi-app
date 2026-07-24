package com.msaidizi.core.model.llm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JNI bridge to llama.cpp for on-device LLM inference.
 *
 * This is the single source of truth for all native llama.cpp calls.
 * The [LlmEngine] facade delegates all generation work here.
 *
 * ## Why llama.cpp over ONNX Runtime for LLMs
 * - 2-5x faster inference for autoregressive generation
 * - Memory-mapped file access for low RAM usage
 * - Direct GGUF model loading (no conversion needed)
 * - KV cache Q4_0 quantization (4x memory reduction, 2-3x speed boost)
 *
 * ## Native Library
 * JNI methods are implemented in `llama_jni.cpp` via Android NDK.
 * The native library "llama_jni" is built by CMakeLists.txt and
 * linked against the llama.cpp static library.
 *
 * ## KV Cache Q4_0 Optimization
 * Enables 4-bit quantization of the Key-Value cache, reducing KV cache memory
 * by ~4x (from FP16 to Q4_0). This yields a 2-3x inference speed boost on
 * memory-constrained devices by reducing memory bandwidth pressure during
 * the autoregressive decode loop. Enabled by default for devices with ≤3GB RAM.
 *
 * @see LlmEngine for the high-level generation API
 */
@Singleton
class LlamaCppBridge @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "LlamaCppBridge"

        // Context lengths by device tier
        private const val CTX_BASIC = 2048
        private const val CTX_STANDARD = 4096
        private const val CTX_ENHANCED = 4096

        private const val DEFAULT_MAX_TOKENS = 256
        private const val DEFAULT_TEMPERATURE = 0.3f

        /** Whether the native library loaded successfully */
        var isNativeAvailable = false
            private set

        private var kvCacheQ4Enabled: Boolean? = null

        init {
            try {
                System.loadLibrary("llama_jni")
                isNativeAvailable = true
                Timber.d(TAG, "llama_jni native library loaded")
            } catch (e: UnsatisfiedLinkError) {
                isNativeAvailable = false
                Timber.e(e, "llama_jni native library not found — LLM features disabled")
            }
        }

        /** Check if KV cache Q4_0 should be used (auto-enables on ≤3GB RAM) */
        fun isKvCacheQ4Enabled(): Boolean {
            kvCacheQ4Enabled?.let { return it }
            val maxMemoryMB = Runtime.getRuntime().maxMemory() / (1024 * 1024)
            return maxMemoryMB <= 3072
        }
    }

    // ── State ──
    private var modelHandle: Long = 0L
    private var isLoaded = false
    private val loadMutex = Mutex()

    // ── Native JNI Methods ──

    /** Load a GGUF model file. Returns model handle (>0 on success). */
    external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int, useKvCacheQ4: Boolean): Long

    /** Generate text from a prompt using the loaded model. */
    external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Float): String

    /** Free a loaded model and release all associated memory. */
    external fun nativeFreeModel(handle: Long)

    // ── Public API ──

    /**
     * Load a GGUF model from the given path.
     * Thread-safe, idempotent, with full file validation.
     *
     * @param path Absolute path to the .gguf model file
     * @param nCtx Maximum context window length
     * @param nThreads Number of CPU threads for inference
     * @return true if the model was loaded successfully
     */
    suspend fun loadModel(
        path: String,
        nCtx: Int = getMaxContextLength(),
        nThreads: Int = getInferenceThreads()
    ): Boolean = loadMutex.withLock {
        if (isLoaded && modelHandle != 0L) return@withLock true

        if (!isNativeAvailable) {
            Timber.w(TAG, "Cannot load: llama_jni not available")
            return@withLock false
        }

        // Validate model file
        val file = File(path)
        if (!file.exists() || !file.canRead()) {
            Timber.w(TAG, "Model file not found or not readable: %s", path)
            return@withLock false
        }

        if (file.length() < 1024 * 1024) {
            Timber.e(TAG, "Model file suspiciously small (%d bytes) — likely corrupt", file.length())
            return@withLock false
        }

        // Verify GGUF magic bytes
        try {
            val magic = ByteArray(4)
            file.inputStream().use { it.read(magic) }
            if (String(magic, Charsets.US_ASCII) != "GGUF") {
                Timber.e(TAG, "Invalid GGUF header")
                return@withLock false
            }
        } catch (_: Throwable) {}

        // Memory check
        val runtime = Runtime.getRuntime()
        val freeHeapMB = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / (1024 * 1024)
        val modelSizeMB = file.length() / (1024 * 1024)
        if (freeHeapMB < modelSizeMB / 2) {
            Timber.e(TAG, "Insufficient memory: %dMB free, model is %dMB", freeHeapMB, modelSizeMB)
            return@withLock false
        }

        val useKvCacheQ4 = isKvCacheQ4Enabled()

        try {
            val startTime = System.currentTimeMillis()
            modelHandle = withContext(Dispatchers.IO) {
                nativeLoadModel(path, nCtx, nThreads, useKvCacheQ4)
            }

            if (modelHandle == 0L) {
                Timber.e(TAG, "nativeLoadModel returned 0 — load failed")
                return@withLock false
            }

            isLoaded = true
            val elapsed = System.currentTimeMillis() - startTime
            Timber.i(TAG, "Model loaded in %dms (handle=%d, kv_cache=%s)",
                elapsed, modelHandle, if (useKvCacheQ4) "Q4_0" else "F16")
            true
        } catch (e: OutOfMemoryError) {
            Timber.e(TAG, "OOM loading model")
            unload()
            System.gc()
            false
        } catch (e: Throwable) {
            Timber.e(e, "Model load error")
            false
        }
    }

    /**
     * Load the default Msaidizi LLM model.
     * Prefers Gemma 4 E2B, falls back to Qwen 3.5 0.8B.
     */
    suspend fun loadDefaultModel(): Boolean {
        val maxMemoryMB = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        val modelsDir = File(context.filesDir, "models")
        val isLowTier = maxMemoryMB < 3072

        // Try Gemma 4 E2B first on MEDIUM/HIGH tier
        if (!isLowTier) {
            val gemmaPath = if (maxMemoryMB >= 4096) {
                File(modelsDir, "gemma-4-e2b-Q4_K_M.gguf")
            } else {
                File(modelsDir, "gemma-4-e2b-Q3_K_M.gguf")
            }
            if (gemmaPath.exists()) {
                try {
                    if (loadModel(gemmaPath.absolutePath)) return true
                } catch (_: Throwable) {}
            }
        }

        // Fallback: Qwen 3.5 0.8B
        val qwenPath = File(modelsDir, "Qwen3.5-0.8B-Q4_K_M.gguf")
        return if (qwenPath.exists()) {
            loadModel(qwenPath.absolutePath)
        } else {
            Timber.w(TAG, "No LLM model files found")
            false
        }
    }

    /**
     * Generate text from a prompt.
     *
     * @param prompt Full prompt text
     * @param maxTokens Maximum tokens to generate
     * @param temperature Sampling temperature
     * @return Generated text, or empty string on failure
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        temperature: Float = DEFAULT_TEMPERATURE
    ): String = withContext(Dispatchers.Default) {
        if (!isLoaded || modelHandle == 0L) {
            Timber.w(TAG, "Cannot generate: model not loaded")
            return@withContext ""
        }

        try {
            // Memory safety check
            val runtime = Runtime.getRuntime()
            val freeHeapMB = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / (1024 * 1024)
            if (freeHeapMB < 50) {
                Timber.w(TAG, "Skipping inference — only %dMB free", freeHeapMB)
                return@withContext ""
            }

            val startTime = System.currentTimeMillis()
            val result = nativeGenerate(modelHandle, prompt, maxTokens, temperature)
            val elapsed = System.currentTimeMillis() - startTime
            Timber.d(TAG, "Generated %d chars in %dms", result.length, elapsed)
            result.trim()
        } catch (e: OutOfMemoryError) {
            Timber.e(TAG, "OOM during generation — unloading")
            unload()
            System.gc()
            ""
        } catch (e: Throwable) {
            Timber.e(e, "Generation error")
            ""
        }
    }

    /** Unload the model and free all associated memory */
    fun unload() {
        if (modelHandle != 0L) {
            try {
                nativeFreeModel(modelHandle)
                Timber.d(TAG, "Model unloaded (freed handle=%d)", modelHandle)
            } catch (e: Throwable) {
                Timber.e(e, "Error freeing model")
            }
            modelHandle = 0L
        }
        isLoaded = false
    }

    fun isModelLoaded(): Boolean = isLoaded && modelHandle != 0L

    // ── Helpers ──

    private fun getMaxContextLength(): Int {
        val maxMemoryMB = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        return when {
            maxMemoryMB >= 4096 -> CTX_ENHANCED
            maxMemoryMB >= 3072 -> CTX_STANDARD
            else -> CTX_BASIC
        }
    }

    private fun getInferenceThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return (cores / 2).coerceIn(1, 4)
    }
}
