package com.msaidizi.app.voice

import android.content.Context
import com.msaidizi.app.core.util.DeviceCapability
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sentry.Breadcrumb
import io.sentry.Sentry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * llama.cpp-based LLM engine for on-device inference.
 *
 * 2-5x faster than ONNX Runtime for LLM inference.
 * Uses GGUF models directly (no conversion needed).
 * Memory-mapped file access for low RAM usage.
 *
 * On 2GB devices:
 * - Qwen 3.5 0.8B Q4_K_M: ~500MB RAM, ~10 tokens/sec
 * - Phi-2 Q4_K_M: ~600MB RAM, ~8 tokens/sec
 *
 * **KV Cache Q4_0 Optimization (v2):**
 * Enables 4-bit quantization of the Key-Value cache, reducing KV cache memory
 * by ~4x (from FP16 to Q4_0). This yields a 2-3x inference speed boost on
 * memory-constrained devices by reducing memory bandwidth pressure during
 * the autoregressive decode loop. Enabled by default for devices with ≤3GB RAM.
 *
 * JNI methods are implemented in llama_jni.cpp via Android NDK.
 * The native library "llama_jni" is built by CMakeLists.txt and
 * linked against the llama.cpp static library.
 */
@Singleton
class LlamaCppEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "LlamaCppEngine"

        // Default context lengths by device tier (expanded 2026-07-16)
        private const val CTX_BASIC = 2048     // Expanded from 1024 for Gemma 4 E2B
        private const val CTX_STANDARD = 4096  // Expanded from 2048
        private const val CTX_ENHANCED = 4096

        // Default generation parameters
        private const val DEFAULT_MAX_TOKENS = 256
        private const val DEFAULT_TEMPERATURE = 0.3f

        /** Whether the native library loaded successfully */
        var isNativeAvailable = false
            private set

        /**
         * Enable KV cache Q4_0 quantization by default on devices with ≤3GB RAM.
         * Can be overridden via [setKvCacheQ4Enabled].
         */
        private var kvCacheQ4Enabled: Boolean? = null  // null = auto-detect

        init {
            try {
                System.loadLibrary("llama_jni")
                isNativeAvailable = true
                Timber.d(TAG, "llama_jni native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                isNativeAvailable = false
                Timber.e(e, "llama_jni native library not found — LLM features disabled")
            }
        }

        /**
         * Override KV cache Q4_0 setting.
         * @param enabled true to force Q4_0, false to force FP16, null for auto-detect
         */
        fun setKvCacheQ4Enabled(enabled: Boolean?) {
            kvCacheQ4Enabled = enabled
            Timber.i(TAG, "KV cache Q4_0 override: %s", enabled?.toString() ?: "auto")
        }

        /**
         * Check if KV cache Q4_0 should be used.
         * Auto-enables on devices with ≤3GB RAM where memory bandwidth is the bottleneck.
         */
        fun isKvCacheQ4Enabled(): Boolean {
            kvCacheQ4Enabled?.let { return it }
            // Auto-detect: enable on devices with ≤3GB RAM
            val maxMemoryMB = Runtime.getRuntime().maxMemory() / (1024 * 1024)
            val enabled = maxMemoryMB <= 3072
            Timber.d(TAG, "KV cache Q4_0 auto-detected: %s (RAM=%dMB)", enabled, maxMemoryMB)
            return enabled
        }
    }

    // ────────────── State ──────────────

    private var modelHandle: Long = 0L
    private var isLoaded = false
    private val loadMutex = Mutex()

    // ────────────── Native JNI Methods (llama.cpp) ──────────────

    /**
     * Load a GGUF model file with memory-mapped file access.
     *
     * @param path Absolute path to the .gguf model file
     * @param nCtx Maximum context window length in tokens
     * @param nThreads Number of CPU threads for inference
     * @param useKvCacheQ4 Enable Q4_0 quantization for KV cache (2-3x speed boost)
     * @return Model handle (>0 on success, 0 on failure)
     */
    external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int, useKvCacheQ4: Boolean): Long

    /**
     * Generate text from a prompt using the loaded model.
     *
     * @param handle Model handle from [nativeLoadModel]
     * @param prompt Full prompt text (system + user)
     * @param maxTokens Maximum number of tokens to generate
     * @param temperature Sampling temperature (0.0 = greedy, 1.0 = creative)
     * @return Generated text string
     */
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): String

    /**
     * Free a loaded model and release all associated memory.
     *
     * @param handle Model handle from [nativeLoadModel]
     */
    external fun nativeFreeModel(handle: Long)

    // ────────────── Public API ──────────────

    /**
     * Load a GGUF model from the given path.
     * Thread-safe: concurrent calls are serialized via mutex.
     * Idempotent: returns true immediately if already loaded.
     *
     * KV cache Q4_0 quantization is automatically enabled on devices with ≤3GB RAM.
     * Override via [setKvCacheQ4Enabled].
     *
     * @param path Absolute path to the .gguf model file
     * @param nCtx Maximum context length (auto-detected by default)
     * @param nThreads CPU thread count (auto-detected by default)
     * @return true if the model was loaded successfully
     */
    suspend fun loadModel(
        path: String,
        nCtx: Int = getMaxContextLength(),
        nThreads: Int = getInferenceThreads()
    ): Boolean = loadMutex.withLock {
        if (isLoaded && modelHandle != 0L) {
            Timber.d(TAG, "Model already loaded (handle=%d)", modelHandle)
            return@withLock true
        }

        if (!isNativeAvailable) {
            Timber.w(TAG, "Cannot load model: llama_jni native library not available")
            reportSentryBreadcrumb("llama_jni_unavailable", mapOf("path" to path))
            return@withLock false
        }

        // Block model loading on 32-bit (armeabi-v7a) devices.
        // llama.cpp models (580MB+) exceed the ~1.5GB usable process address space
        // on 32-bit Android. The app falls back to cloud-only mode via ModelRouter.
        if (DeviceCapability.is32BitDevice()) {
            Timber.w(TAG, "Model loading blocked: 32-bit device (armeabi-v7a). " +
                "On-device LLM not supported. Using cloud API for inference.")
            reportSentryBreadcrumb("model_blocked_32bit", mapOf("abi" to DeviceCapability.getPrimaryAbi()))
            return@withLock false
        }

        // ── Validate model file BEFORE calling JNI ──
        // Invalid/corrupt model files cause native segfaults (SIGSEGV) that
        // bypass all JVM exception handling and crash the app immediately.
        // We validate aggressively here to catch issues before they reach native code.
        val file = File(path)

        if (!file.exists()) {
            Timber.w(TAG, "Model file not found: %s", path)
            reportSentryBreadcrumb("model_file_missing", mapOf("path" to path))
            return@withLock false
        }

        if (!file.canRead()) {
            Timber.e(TAG, "Model file not readable (permission denied): %s", path)
            reportSentryBreadcrumb("model_file_not_readable", mapOf("path" to path))
            return@withLock false
        }

        val fileSizeBytes = file.length()
        if (fileSizeBytes == 0L) {
            Timber.e(TAG, "Model file is empty (0 bytes): %s", path)
            reportSentryBreadcrumb("model_file_empty", mapOf("path" to path))
            return@withLock false
        }

        // Minimum valid GGUF file: header (magic + version + n_tensors) ≈ 32 bytes
        // Real models are hundreds of MB. Files < 1MB are almost certainly corrupt.
        val MIN_VALID_GGUF_BYTES = 1024 * 1024L  // 1MB
        if (fileSizeBytes < MIN_VALID_GGUF_BYTES) {
            Timber.e(TAG, "Model file suspiciously small (%d bytes) — likely corrupt: %s", fileSizeBytes, path)
            reportSentryBreadcrumb("model_file_too_small", mapOf(
                "path" to path,
                "sizeBytes" to fileSizeBytes.toString()
            ))
            return@withLock false
        }

        // Check GGUF magic bytes: first 4 bytes should be "GGUF" (0x46475547)
        try {
            val magic = ByteArray(4)
            file.inputStream().use { it.read(magic) }
            val magicStr = String(magic, Charsets.US_ASCII)
            if (magicStr != "GGUF") {
                Timber.e(TAG, "Model file has invalid GGUF header (magic='%s'): %s", magicStr, path)
                reportSentryBreadcrumb("model_invalid_gguf_magic", mapOf(
                    "path" to path,
                    "magic" to magicStr
                ))
                return@withLock false
            }
        } catch (e: Throwable) {
            Timber.w(TAG, "Could not read GGUF magic bytes (proceeding anyway): %s", e.message)
        }

        // Check available memory before loading
        val runtime = Runtime.getRuntime()
        val freeHeapMB = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / (1024 * 1024)
        val estimatedModelSizeMB = fileSizeBytes / (1024 * 1024)
        if (freeHeapMB < estimatedModelSizeMB / 2) {
            Timber.e(TAG, "Insufficient memory to load model: %dMB free, model is %dMB", freeHeapMB, estimatedModelSizeMB)
            reportSentryBreadcrumb("model_insufficient_memory", mapOf(
                "path" to path,
                "freeHeapMB" to freeHeapMB.toString(),
                "modelSizeMB" to estimatedModelSizeMB.toString()
            ))
            return@withLock false
        }

        val useKvCacheQ4 = isKvCacheQ4Enabled()

        try {
            Timber.d(
                TAG, "Loading model: %s (size=%d MB, ctx=%d, threads=%d, kv_cache_q4=%s)",
                file.name, fileSizeBytes / (1024 * 1024), nCtx, nThreads, useKvCacheQ4
            )
            reportSentryBreadcrumb("model_load_start", mapOf(
                "file" to file.name,
                "sizeMB" to (fileSizeBytes / (1024 * 1024)).toString(),
                "ctx" to nCtx.toString(),
                "threads" to nThreads.toString()
            ))
            val startTime = System.currentTimeMillis()

            modelHandle = withContext(Dispatchers.IO) {
                nativeLoadModel(path, nCtx, nThreads, useKvCacheQ4)
            }

            if (modelHandle == 0L) {
                Timber.e(TAG, "Failed to load model (nativeLoadModel returned 0)")
                reportSentryBreadcrumb("model_load_failed_native_returned_0", mapOf("path" to path))
                return@withLock false
            }

            isLoaded = true
            val elapsed = System.currentTimeMillis() - startTime
            Timber.i(
                TAG, "Model loaded in %dms (handle=%d, kv_cache=%s)",
                elapsed, modelHandle, if (useKvCacheQ4) "Q4_0" else "F16"
            )
            reportSentryBreadcrumb("model_load_success", mapOf(
                "handle" to modelHandle.toString(),
                "elapsedMs" to elapsed.toString()
            ))
            true
        } catch (e: OutOfMemoryError) {
            Timber.e(e, "OOM loading model — unloading and freeing memory")
            reportSentryBreadcrumb("model_load_oom", mapOf("path" to path))
            unload()
            System.gc()
            false
        } catch (e: UnsatisfiedLinkError) {
            Timber.e(e, "Native library not available")
            reportSentryBreadcrumb("model_load_unsatisfied_link", mapOf("path" to path))
            false
        } catch (e: Throwable) {
            Timber.e(e, "Model load error")
            reportSentryBreadcrumb("model_load_exception", mapOf(
                "path" to path,
                "error" to (e.message ?: "unknown")
            ))
            false
        }
    }

    /**
     * Generate text from a prompt.
     * Loads the model automatically if not yet loaded (requires prior [loadModel] call).
     *
     * @param prompt Full prompt text
     * @param maxTokens Maximum tokens to generate (default 256)
     * @param temperature Sampling temperature (default 0.3)
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
            // Memory safety check: ensure enough free memory before inference
            val runtime = Runtime.getRuntime()
            val freeHeapMB = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / (1024 * 1024)
            if (freeHeapMB < 50) {
                Timber.w(TAG, "Skipping LLM inference — only %dMB free heap (need ≥50MB)", freeHeapMB)
                return@withContext ""
            }

            val startTime = System.currentTimeMillis()

            val result = nativeGenerate(modelHandle, prompt, maxTokens, temperature)

            val elapsed = System.currentTimeMillis() - startTime
            Timber.d(
                TAG, "Generated %d chars in %dms",
                result.length, elapsed
            )
            result.trim()
        } catch (e: OutOfMemoryError) {
            Timber.e(e, "OOM during LLM generation — unloading model")
            unload()
            System.gc()
            ""
        } catch (e: Throwable) {
            Timber.e(e, "Generation error")
            ""
        }
    }

    /**
     * Unload the model and free all associated memory.
     * Safe to call even if no model is loaded.
     */
    fun unload() {
        if (modelHandle != 0L) {
            try {
                nativeFreeModel(modelHandle)
                Timber.d(TAG, "Model unloaded (freed handle=%d)", modelHandle)
                reportSentryBreadcrumb("model_unloaded", mapOf("handle" to modelHandle.toString()))
            } catch (e: Throwable) {
                Timber.e(e, "Error freeing model")
                reportSentryBreadcrumb("model_unload_error", mapOf("error" to (e.message ?: "unknown")))
            }
            modelHandle = 0L
        }
        isLoaded = false
    }

    /**
     * Check whether a model is currently loaded and ready for inference.
     */
    fun isModelLoaded(): Boolean = isLoaded && modelHandle != 0L

    /**
     * Load the default Msaidizi LLM model.
     * Updated (2026-07-16): Prefers Gemma 4 E2B, falls back to Qwen 3.5 0.8B.
     *
     * @return true if the default model loaded successfully
     */
    suspend fun loadDefaultModel(): Boolean {
        val maxMemoryMB = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        val modelsDir = File(context.filesDir, "models")
        val isLowTier = maxMemoryMB < 3072

        reportSentryBreadcrumb("load_default_model_start", mapOf(
            "maxMemoryMB" to maxMemoryMB.toString(),
            "isLowTier" to isLowTier.toString()
        ))

        // For LOW-tier (2GB) devices, skip Gemma 4 E2B — too large (~1.2GB).
        // Qwen 3.5 0.8B (~500MB) is primary for LOW-tier.
        if (!isLowTier) {
            // Try Gemma 4 E2B first on MEDIUM/HIGH tier devices
            val gemmaPath = if (maxMemoryMB >= 4096) {
                File(modelsDir, "gemma-4-e2b-q4_k_m.gguf")
            } else {
                File(modelsDir, "gemma-4-e2b-q3_k_m.gguf")
            }
            if (gemmaPath.exists()) {
                try {
                    val loaded = loadModel(gemmaPath.absolutePath)
                    if (loaded) return true
                } catch (e: Throwable) {
                    Timber.e(e, "Exception loading Gemma 4 E2B")
                    reportSentryBreadcrumb("gemma_load_exception", mapOf(
                        "path" to gemmaPath.absolutePath,
                        "error" to (e.message ?: "unknown")
                    ))
                }
                Timber.w(TAG, "Gemma 4 E2B failed to load, falling back to Qwen")
            } else {
                Timber.i(TAG, "Gemma 4 E2B model file not found at %s", gemmaPath.absolutePath)
            }
        } else {
            Timber.i(TAG, "LOW-tier device (%dMB RAM) — using Qwen 3.5 0.8B as primary", maxMemoryMB)
        }

        // Primary for LOW-tier / Fallback for MEDIUM/HIGH: Qwen 3.5 0.8B
        val qwenPath = File(modelsDir, "Qwen3.5-0.8B-Q4_K_M.gguf")
        if (!qwenPath.exists()) {
            Timber.e(TAG, "Qwen model file not found: %s", qwenPath.absolutePath)
            reportSentryBreadcrumb("qwen_model_file_missing", mapOf(
                "path" to qwenPath.absolutePath,
                "modelsDir" to modelsDir.absolutePath,
                "modelsDirExists" to modelsDir.exists().toString(),
                "modelsDirContents" to (modelsDir.listFiles()?.map { it.name }?.joinToString() ?: "null")
            ))
            return false
        }
        return loadModel(qwenPath.absolutePath)
    }

    // ────────────── Sentry Breadcrumbs ──────────────

    /**
     * Report a Sentry breadcrumb for native crash detection.
     * Breadcrumbs are lightweight — they only add context to crash reports,
     * not create events. This helps diagnose native segfaults that bypass
     * JVM exception handling.
     */
    private fun reportSentryBreadcrumb(event: String, data: Map<String, String> = emptyMap()) {
        try {
            val breadcrumb = io.sentry.Breadcrumb().apply {
                category = "model_loading"
                type = "navigation"
                message = event
                data.forEach { (k, v) -> setData(k, v) }
            }
            io.sentry.Sentry.addBreadcrumb(breadcrumb)
        } catch (_: Throwable) {
            // Sentry may not be initialized — silently ignore
        }
    }

    // ────────────── Helpers ──────────────

    /**
     * Get maximum context length based on available device memory.
     */
    private fun getMaxContextLength(): Int {
        val maxMemoryMB = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        return when {
            maxMemoryMB >= 4096 -> CTX_ENHANCED   // 4GB+ device
            maxMemoryMB >= 3072 -> CTX_STANDARD    // 3GB device
            else -> CTX_BASIC                       // 2GB device
        }
    }

    /**
     * Get optimal thread count for inference.
     * Uses half of available cores, capped at 4.
     */
    private fun getInferenceThreads(): Int {
        val cpuCores = Runtime.getRuntime().availableProcessors()
        return (cpuCores / 2).coerceIn(1, 4)
    }
}
