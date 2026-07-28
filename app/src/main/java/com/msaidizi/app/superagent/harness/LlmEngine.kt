package com.msaidizi.app.superagent.harness

import android.content.Context
import com.msaidizi.app.superagent.tools.ToolResult
import com.msaidizi.app.voice.LlamaCppEngine
import com.msaidizi.app.voice.BatteryOptimizer
import com.msaidizi.app.voice.ModelTier
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM Engine — Interface to the on-device language model (Qwen3.5 0.8B via llama.cpp).
 *
 * This is the reasoning core of the superagent. Runs entirely on-device.
 * Uses Hermes-style function calling for tool use.
 *
 * Model must be loaded at app startup via [initialize]. If the model file is
 * missing or invalid, the engine enters a degraded mode where [generate]
 * returns fallback responses and emits a download prompt via [state].
 *
 * JNI bridge is owned by [LlamaCppEngine] — this class delegates native calls
 * to avoid duplicate JNI method declarations.
 */
@Singleton
class LlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson,
    private val llamaCppEngine: LlamaCppEngine,
    private val batteryOptimizer: BatteryOptimizer,
    private val modelTier: ModelTier
) {
    companion object {
        /** Expected model filename (Q4_K_M quantised Qwen3.5 0.8B). */
        const val EXPECTED_MODEL_NAME = "Qwen3.5-0.8B-Q4_K_M.gguf"

        /** Minimum plausible GGUF file size (1 MB). */
        private const val MIN_GGUF_SIZE_BYTES = 1L * 1024 * 1024

        /** GGUF magic bytes: "GGUF" in ASCII. */
        private val GGUF_MAGIC = byteArrayOf(
            'G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()
        )
    }

    /** Observable engine state for the UI layer. */
    sealed class State {
        object NotInitialized : State()
        object Loading : State()
        object Ready : State()
        data class MissingModel(val reason: String) : State()
        data class Error(val message: String) : State()
    }

    @Volatile
    var state: State = State.NotInitialized
        private set

    private var isInitialized = false
    private var modelPath: String? = null

    // JNI bridge delegated to LlamaCppEngine (single JNI owner)

    /**
     * Initialize the LLM engine with the model file.
     *
     * Must be called once at app startup (e.g. from Application.onCreate or a
     * Hilt-provided Initializer). Safe to call multiple times -- subsequent
     * calls are no-ops if the model is already loaded.
     *
     * @return true if the model is loaded and ready for inference.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext true

        state = State.Loading

        try {
            // 1. Locate model directory
            val modelDir = File(context.filesDir, "models")
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }

            // 2. Find the expected model file
            val modelFile = findModelFile(modelDir)

            if (modelFile == null) {
                val msg = "Model not found. Please download $EXPECTED_MODEL_NAME " +
                    "to ${modelDir.absolutePath}"
                Timber.w(msg)
                state = State.MissingModel(msg)
                return@withContext false
            }

            // 3. Validate the GGUF file
            val validationError = validateGgufFile(modelFile)
            if (validationError != null) {
                val msg = "Model file invalid: $validationError -- ${modelFile.absolutePath}"
                Timber.e(msg)
                state = State.Error(msg)
                return@withContext false
            }

            modelPath = modelFile.absolutePath

            // 4. Load via LlamaCppEngine (delegates to llama.cpp JNI)
            // Use tier-specific settings + battery-aware context sizing
            val tier = modelTier.getActiveTier()
            val batteryProfile = batteryOptimizer.getPerformanceProfile()
            val cores = tier.nThreads.coerceAtMost(Runtime.getRuntime().availableProcessors())
            val contextSize = minOf(tier.nCtx, batteryProfile.contextSize)

            val loaded = llamaCppEngine.loadModel(modelFile.absolutePath, contextSize, cores)
            isInitialized = loaded

            if (isInitialized) {
                Timber.i("LLM initialized: %s (%d threads, ctx=%d)", modelFile.name, cores, contextSize)
                state = State.Ready
            } else {
                val msg = "loadModel returned false for ${modelFile.name}"
                Timber.e(msg)
                state = State.Error(msg)
            }

            isInitialized
        } catch (e: Exception) {
            Timber.e(e, "LLM initialization error")
            state = State.Error(e.message ?: "Unknown error")
            false
        }
    }

    /**
     * Locate the model file in [modelDir].
     *
     * Priority:
     * 1. Exact match for [EXPECTED_MODEL_NAME]
     * 2. Any .gguf file whose name contains "Qwen3" (case-insensitive)
     * 3. Any .gguf file as a last resort
     */
    private fun findModelFile(modelDir: File): File? {
        val files = modelDir.listFiles() ?: return null

        files.firstOrNull { it.name == EXPECTED_MODEL_NAME }?.let { return it }

        files.firstOrNull {
            it.name.endsWith(".gguf", ignoreCase = true) &&
                it.name.contains("Qwen3", ignoreCase = true)
        }?.let { return it }

        return files.firstOrNull { it.name.endsWith(".gguf", ignoreCase = true) }
    }

    /**
     * Validate that [file] is a plausible GGUF model.
     *
     * Checks: exists, readable, minimum size, correct magic bytes.
     * @return null if valid, or a human-readable error string.
     */
    private fun validateGgufFile(file: File): String? {
        if (!file.exists() || !file.canRead()) {
            return "File does not exist or is not readable"
        }

        if (file.length() < MIN_GGUF_SIZE_BYTES) {
            return "File too small (${file.length()} bytes) -- likely corrupt or truncated"
        }

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                if (!magic.contentEquals(GGUF_MAGIC)) {
                    return "Invalid GGUF magic bytes (expected 'GGUF', got '${String(magic)}')"
                }
            }
            null
        } catch (e: Exception) {
            "Failed to read file header: ${e.message}"
        }
    }

    /**
     * Generate a response from the LLM.
     *
     * If the model is not loaded, returns a graceful fallback -- never crashes.
     */
    suspend fun generate(
        systemPrompt: String,
        userMessage: String,
        context: AssembledContext,
        toolResults: List<ToolResult>,
        intent: UserIntent
    ): String = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            Timber.w("generate called but model not initialized (state=%s)", state)
            return@withContext generateFallbackResponse(intent)
        }

        try {
            val prompt = buildPrompt(systemPrompt, userMessage, context, toolResults, intent)

            // Record battery drain for this inference
            batteryOptimizer.startInferenceSession()

            val response = llamaCppEngine.generate(
                prompt = prompt,
                maxTokens = batteryOptimizer.getPerformanceProfile().maxTokens,
                temperature = batteryOptimizer.getPerformanceProfile().temperature,
                topP = 0.9f,
                stopSequences = listOf("Human:", "User:", "\n\n")
            )

            batteryOptimizer.endInferenceSession()

            response.trim()
        } catch (e: Exception) {
            Timber.e(e, "LLM generation failed")
            generateFallbackResponse(intent)
        }
    }

    /**
     * Build the full prompt in ChatML format compatible with Qwen3.
     */
    private fun buildPrompt(
        systemPrompt: String,
        userMessage: String,
        context: AssembledContext,
        toolResults: List<ToolResult>,
        intent: UserIntent
    ): String {
        return buildString {
            appendLine("<|im_start|>system")
            appendLine(systemPrompt)
            appendLine("You are Msaidizi, an AI business assistant for Kenyan MSMEs.")
            appendLine("Respond in the user's language (Swahili or English).")
            appendLine("Be concise, actionable, and friendly.")
            appendLine("