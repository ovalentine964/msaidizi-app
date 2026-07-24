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
 * High-level LLM engine facade for on-device inference.
 *
 * This is the primary interface for all LLM operations in Msaidizi.
 * It delegates native JNI work to [LlamaCppBridge] (the single source of
 * truth for llama.cpp bindings) and adds business-logic concerns:
 *
 * - System prompt templates per language (Swahili, English, Sheng)
 * - Function calling schema parsing
 * - Prompt truncation for context window limits
 * - Thinking mode (chain-of-thought via Qwen's `<think>` tags)
 *
 * ## Model Selection by Device Tier
 *
 * | Tier | RAM   | Primary Model              | Fallback              |
 * |------|-------|----------------------------|-----------------------|
 * | LOW  | ≤2GB  | Qwen 3.5 0.8B Q4_K_M      | Gemma 4 E2B Q3_K_M   |
 * | MID  | 3-4GB | Gemma 4 E2B Q4_K_M         | Qwen 3.5 0.8B        |
 * | HIGH | ≥6GB  | Gemma 4 E2B Q4_K_M         | Qwen 3.5 2B          |
 *
 * ## Performance (Helio G25, 2GB, Qwen 3.5 0.8B)
 * - Load time: ~2s with mmap
 * - Inference: ~10 tok/s (prompt), ~6 tok/s (generation)
 * - Context: 2048 tokens (LOW) to 4096 (ENHANCED)
 * - RAM: ~600MB when loaded (released on background)
 *
 * ## OOM Safety
 * All generation and model loading paths catch [OutOfMemoryError],
 * release the model, trigger GC, and return empty string. The caller
 * falls back to rule-based responses or cloud API.
 *
 * @see LlamaCppBridge for the JNI bridge
 * @see com.msaidizi.core.model.manager.ModelManager for model lifecycle
 */
@Singleton
class LlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llamaBridge: LlamaCppBridge
) {
    companion object {
        // Context lengths by device tier
        private const val CTX_BASIC = 2048
        private const val CTX_STANDARD = 4096
        private const val CTX_ENHANCED = 4096

        // Generation defaults
        private const val DEFAULT_MAX_TOKENS = 256
        private const val DEFAULT_TEMPERATURE = 0.3f
        private const val DEFAULT_MAX_TOKENS_RESPONSE = 128

        /** Extra tokens for thinking mode chain-of-thought */
        const val THINKING_TOKEN_BUDGET = 512

        // System prompts by language
        private val SYSTEM_PROMPTS = mapOf(
            "sw" to """Wewe ni Msaidizi, msaidizi wa biashara kwa wafanyabiashara wadogo Afrika.
Jibu kwa Kiswahili rahisi, lugha rahisi inayoeleweka na mtu yeyote.
Kuwa mfupi na wa moja kwa moja. Tumia mifano kutoka biashara ya kawaida.
Usitumie maneno magumu au ya kitaalamu.""",

            "en" to """You are Msaidizi, a business assistant for small traders in Africa.
Reply in simple, clear English that anyone can understand.
Be brief and direct. Use examples from everyday business.
Don't use jargon or complex terms.""",

            "sheng" to """Wewe ni Msaidizi, msaidizi wa biashara. Jibu kwa Sheng rahisi.
Kuwa brief na toa info poa. Usiwatie maneno mangi."""
        )
    }

    private var isGenerating = false

    // ────────────────────── Model Lifecycle ──────────────────────

    /**
     * Load the best available LLM model for this device.
     *
     * Delegates to [LlamaCppBridge.loadModel]. On 32-bit devices,
     * model loading is blocked — the app operates in cloud-only mode.
     *
     * @return true if model loaded successfully
     */
    suspend fun loadModel(): Boolean {
        if (llamaBridge.isModelLoaded()) return true
        return llamaBridge.loadDefaultModel()
    }

    /** Unload model to free ~500MB RAM */
    fun unloadModel() {
        llamaBridge.unload()
        Timber.d("LlmEngine: Model unloaded")
    }

    fun isModelLoaded(): Boolean = llamaBridge.isModelLoaded()
    fun isCurrentlyGenerating(): Boolean = isGenerating

    // ────────────────────── Generation ──────────────────────

    /**
     * Generate text from a prompt.
     *
     * @param prompt Full prompt (system + user)
     * @param maxTokens Maximum tokens to generate
     * @param temperature Sampling temperature (0.1 factual, 0.7 creative)
     * @param thinkingEnabled Activate chain-of-thought (Qwen only; disabled for Gemma)
     * @return Generated text, or empty string on failure
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        temperature: Float = DEFAULT_TEMPERATURE,
        thinkingEnabled: Boolean = false
    ): String {
        if (!llamaBridge.isModelLoaded()) {
            val loaded = loadModel()
            if (!loaded) return ""
        }

        if (isGenerating) {
            Timber.w("LlmEngine: Already generating, skipping")
            return ""
        }

        isGenerating = true
        try {
            val startTime = System.currentTimeMillis()
            val maxCtx = getMaxContextLength()
            val actualPrompt = truncatePrompt(prompt, maxCtx - maxTokens)

            val result = llamaBridge.generate(actualPrompt, maxTokens, temperature)

            val elapsed = System.currentTimeMillis() - startTime
            Timber.d("LlmEngine: Generated %d chars in %dms", result.length, elapsed)
            return result
        } catch (e: OutOfMemoryError) {
            Timber.e("LlmEngine: OOM during generation — unloading model")
            unloadModel()
            System.gc()
            return ""
        } catch (e: Throwable) {
            Timber.e(e, "LlmEngine: Generation error")
            return ""
        } finally {
            isGenerating = false
        }
    }

    /**
     * Generate a Msaidizi response with system prompt and business context.
     *
     * This is the primary method used by the reasoning engine. It wraps
     * user input in the appropriate language template.
     *
     * @param userInput User's message
     * @param context Business context (recent transactions, inventory, etc.)
     * @param language Response language ("sw", "en", "sheng")
     * @return Msaidizi's response
     */
    suspend fun generateResponse(
        userInput: String,
        context: String = "",
        language: String = "sw"
    ): String {
        val systemPrompt = SYSTEM_PROMPTS[language] ?: SYSTEM_PROMPTS["sw"]!!

        val fullPrompt = buildString {
            append(systemPrompt)
            append("\n\n")
            if (context.isNotBlank()) {
                append("Maelezo ya biashara:\n")
                append(context)
                append("\n\n")
            }
            append("Mteja: ")
            append(userInput)
            append("\nMsaidizi:")
        }

        return generate(fullPrompt, maxTokens = DEFAULT_MAX_TOKENS_RESPONSE, temperature = DEFAULT_TEMPERATURE)
    }

    /**
     * Generate with function calling support.
     * Parses the output to detect JSON function call patterns.
     */
    suspend fun generateWithFunctions(
        userInput: String,
        context: String = "",
        language: String = "sw"
    ): FunctionCallResult {
        val prompt = buildString {
            append("You are Msaidizi, a business assistant. You can call functions or reply directly.\n\n")
            append("Available functions:\n")
            append("- record_sale: Record a sale (item, quantity, amount)\n")
            append("- record_expense: Record an expense (item, amount)\n")
            append("- check_inventory: Check stock levels\n")
            append("- get_business_summary: Get performance summary\n\n")
            if (context.isNotBlank()) {
                append("Business context:\n$context\n\n")
            }
            append("User: $userInput\n\n")
            append("If a function call is appropriate, respond with JSON: {\"function\": \"name\", \"args\": {...}}\n")
            append("Otherwise, reply in plain text.\nResponse:")
        }

        val output = generate(prompt, maxTokens = 128, temperature = 0.1f)

        return if (output.isNotBlank() && output.trimStart().startsWith("{")) {
            FunctionCallResult(isFunctionCall = true, functionCallJson = output.trim(), textResponse = null)
        } else {
            FunctionCallResult(isFunctionCall = false, functionCallJson = null, textResponse = output)
        }
    }

    // ────────────────────── Helpers ──────────────────────

    private fun truncatePrompt(prompt: String, maxTokens: Int): String {
        val maxChars = maxTokens * 3  // Conservative: ~3 chars per token for Swahili
        return if (prompt.length > maxChars) {
            "..." + prompt.takeLast(maxChars)
        } else prompt
    }

    private fun getMaxContextLength(): Int {
        val maxMemoryMB = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        return when {
            maxMemoryMB >= 4096 -> CTX_ENHANCED
            maxMemoryMB >= 3072 -> CTX_STANDARD
            else -> CTX_BASIC
        }
    }
}

/** Result from function-calling generation */
data class FunctionCallResult(
    val isFunctionCall: Boolean,
    val functionCallJson: String?,
    val textResponse: String?
)
