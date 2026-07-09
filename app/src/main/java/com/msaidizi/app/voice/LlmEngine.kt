package com.msaidizi.app.voice

import android.content.Context
import com.msaidizi.app.core.language.LanguageLearningPipeline
import com.msaidizi.app.core.language.LanguageModelRegistry
import com.msaidizi.app.core.language.AdaptiveAsrEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level LLM engine facade for on-device inference.
 *
 * Delegates all native JNI work to [LlamaCppEngine] (the single source of
 * truth for llama.cpp bindings). This class adds business-logic concerns:
 * - System prompt templates per language
 * - Function calling schema parsing
 * - Adaptive/learned context enrichment
 * - Correction parsing
 * - Token counting and prompt truncation
 *
 * Previously this class contained duplicate JNI stubs that shadowed
 * [LlamaCppEngine]. Those stubs have been removed; all native calls
 * now route through the single [LlamaCppEngine] instance.
 *
 * Model: Qwen3.5-0.8B Q4_K_M (~450MB GGUF)
 *
 * Performance on Helio G25 (2GB):
 * - Load time: ~3s with mmap
 * - Inference: ~8 tokens/sec (prompt), ~5 tokens/sec (generation)
 * - Context: 1024 tokens (BASIC tier) to 4096 (ENHANCED)
 * - RAM: ~350MB when loaded (released on background)
 */
@Singleton
class LlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llamaCppEngine: LlamaCppEngine,
    private val languageModelRegistry: LanguageModelRegistry? = null,
    private val adaptiveAsrEngine: AdaptiveAsrEngine? = null
) {
    companion object {
        // Default context lengths by device tier
        private const val CTX_BASIC = 1024
        private const val CTX_STANDARD = 2048
        private const val CTX_ENHANCED = 4096

        // Default generation parameters
        private const val DEFAULT_MAX_TOKENS = 256
        private const val DEFAULT_TEMPERATURE = 0.3f
        private const val DEFAULT_TOP_P = 0.9f
        private const val DEFAULT_MAX_TOKENS_RESPONSE = 128

        // Whether the underlying native library is available
        val isNativeAvailable: Boolean get() = LlamaCppEngine.isNativeAvailable

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

        // Function calling schema for business operations
        val FUNCTION_SCHEMAS = listOf(
            mapOf(
                "name" to "record_sale",
                "description" to "Record a sale transaction",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "item" to mapOf("type" to "string", "description" to "Product name"),
                        "quantity" to mapOf("type" to "number", "description" to "Quantity sold"),
                        "amount" to mapOf("type" to "number", "description" to "Total amount in KES")
                    ),
                    "required" to listOf("item", "amount")
                )
            ),
            mapOf(
                "name" to "record_expense",
                "description" to "Record a business expense",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "item" to mapOf("type" to "string", "description" to "What was bought"),
                        "amount" to mapOf("type" to "number", "description" to "Amount in KES")
                    ),
                    "required" to listOf("item", "amount")
                )
            ),
            mapOf(
                "name" to "check_inventory",
                "description" to "Check current inventory levels",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "item" to mapOf("type" to "string", "description" to "Product to check (empty for all)")
                    )
                )
            ),
            mapOf(
                "name" to "get_business_summary",
                "description" to "Get business performance summary",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "period" to mapOf(
                            "type" to "string",
                            "enum" to listOf("today", "week", "month"),
                            "description" to "Time period"
                        )
                    )
                )
            )
        )
    }

    private var isGenerating = false

    // ────────────────────── Model Lifecycle ──────────────────────

    /**
     * Load the LLM model with memory-mapped file access.
     * Delegates to [LlamaCppEngine.loadModel].
     *
     * @return true if model loaded successfully
     */
    suspend fun loadModel(): Boolean {
        if (llamaCppEngine.isModelLoaded()) return true

        if (!LlamaCppEngine.isNativeAvailable) {
            Timber.w("Cannot load LLM: llama_jni native library is not available")
            return false
        }

        val modelPath = File(context.filesDir, "models/qwen-0.5b-q4_k_m.gguf")
        if (!modelPath.exists()) {
            Timber.w("LLM model not found: %s", modelPath.absolutePath)
            return false
        }

        return llamaCppEngine.loadModel(modelPath.absolutePath)
    }

    /**
     * Unload model to free ~350MB RAM.
     * Called when app goes to background on 2GB devices.
     */
    fun unloadModel() {
        llamaCppEngine.unload()
        Timber.d("LLM model unloaded (via LlmEngine facade)")
    }

    fun isModelLoaded(): Boolean = llamaCppEngine.isModelLoaded()
    fun isCurrentlyGenerating(): Boolean = isGenerating

    // ────────────────────── Generation ──────────────────────

    /**
     * Generate a response from the LLM.
     *
     * @param prompt The full prompt (system + user)
     * @param maxTokens Maximum tokens to generate (default 256 for speed)
     * @param temperature Sampling temperature (0.1 for factual, 0.7 for creative)
     * @param onToken Callback for streaming token output (unused — llama.cpp returns full text)
     * @return Complete generated text
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        temperature: Float = DEFAULT_TEMPERATURE,
        onToken: ((String) -> Unit)? = null
    ): String {
        if (!llamaCppEngine.isModelLoaded()) {
            val loaded = loadModel()
            if (!loaded) return ""
        }

        if (isGenerating) {
            Timber.w("LLM already generating, skipping request")
            return ""
        }

        isGenerating = true
        try {
            val startTime = System.currentTimeMillis()

            val maxCtx = getMaxContextLength()
            val actualPrompt = truncatePrompt(prompt, maxCtx - maxTokens)

            val result = llamaCppEngine.generate(
                actualPrompt,
                maxTokens = maxTokens,
                temperature = temperature
            )

            // If streaming callback requested, invoke with full result
            if (onToken != null && result.isNotBlank()) {
                onToken(result)
            }

            val elapsed = System.currentTimeMillis() - startTime
            Timber.d(
                "LLM generated %d chars in %dms (%.1f tok/s)",
                result.length, elapsed,
                if (elapsed > 0) result.length * 4.0 / elapsed * 1000 else 0.0
            )
            return result
        } catch (e: Exception) {
            Timber.e(e, "LLM generation error")
            return ""
        } finally {
            isGenerating = false
        }
    }

    /**
     * Generate with Msaidizi system prompt.
     * Wraps user input in the appropriate prompt template for the language.
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
        val systemPrompt = SYSTEM_PROMPTS[language] ?: requireNotNull(SYSTEM_PROMPTS["sw"]) { "Missing fallback Swahili system prompt" }

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

        return generate(
            fullPrompt,
            maxTokens = DEFAULT_MAX_TOKENS_RESPONSE,
            temperature = DEFAULT_TEMPERATURE
        )
    }

    /**
     * Generate response with learned context from the language learning pipeline.
     */
    suspend fun generateWithLearnedContext(
        userInput: String,
        businessContext: String = "",
        language: String = "sw",
        learnedContext: String? = null
    ): String {
        val systemPrompt = SYSTEM_PROMPTS[language] ?: requireNotNull(SYSTEM_PROMPTS["sw"]) { "Missing fallback Swahili system prompt" }

        val enrichedContext = buildLearnedContext(businessContext, language, learnedContext)

        val fullPrompt = buildString {
            append(systemPrompt)
            append("\n\n")

            if (enrichedContext.isNotBlank()) {
                append("Maelezo ya ziada (learned context):\n")
                append(enrichedContext)
                append("\n\n")
            }

            append("Mteja: ")
            append(userInput)
            append("\nMsaidizi:")
        }

        return generate(
            fullPrompt,
            maxTokens = DEFAULT_MAX_TOKENS_RESPONSE,
            temperature = DEFAULT_TEMPERATURE
        )
    }

    /**
     * Build learned context string from the language learning pipeline.
     */
    private suspend fun buildLearnedContext(
        businessContext: String,
        language: String,
        prebuiltContext: String?
    ): String {
        if (!prebuiltContext.isNullOrBlank()) return prebuiltContext

        val parts = mutableListOf<String>()

        val langDef = languageModelRegistry?.getLanguageDef(language)
        if (langDef != null) {
            parts.add("Lugha: ${langDef.nativeName}")
        }

        val stats = try {
            adaptiveAsrEngine?.getCorrectionStats()
        } catch (e: Exception) { null }

        if (stats != null && stats.totalCorrections > 0) {
            parts.add("Ukuzi wa msamiati: maneno ${stats.totalCorrections}")
        }

        if (businessContext.isNotBlank()) {
            parts.add(businessContext)
        }

        return parts.joinToString("\n")
    }

    /**
     * Generate with function calling support.
     */
    suspend fun generateWithFunctions(
        userInput: String,
        context: String = "",
        language: String = "sw"
    ): FunctionCallResult {
        val functionsJson = FUNCTION_SCHEMAS.joinToString("\n") { schema ->
            "- ${schema["name"]}: ${schema["description"]}"
        }

        val prompt = buildString {
            append("You are Msaidizi, a business assistant. You can call functions or reply directly.\n\n")
            append("Available functions:\n")
            append(functionsJson)
            append("\n\n")
            if (context.isNotBlank()) {
                append("Business context:\n$context\n\n")
            }
            append("User: $userInput\n\n")
            append("If a function call is appropriate, respond with JSON: {\"function\": \"name\", \"args\": {...}}\n")
            append("Otherwise, reply in plain text.\n")
            append("Response:")
        }

        val output = generate(prompt, maxTokens = 128, temperature = 0.1f)

        return try {
            // Try to parse as function call using regex
            if (output.isNotBlank() && output.trimStart().startsWith("{")) {
                FunctionCallResult(
                    isFunctionCall = true,
                    functionCallJson = output.trim(),
                    textResponse = null
                )
            } else {
                FunctionCallResult(
                    isFunctionCall = false,
                    functionCallJson = null,
                    textResponse = output
                )
            }
        } catch (e: Exception) {
            FunctionCallResult(
                isFunctionCall = false,
                functionCallJson = null,
                textResponse = output
            )
        }
    }

    /**
     * Generate a response with personalized context from AdaptiveLearningEngine.
     */
    suspend fun generateWithAdaptiveContext(
        userInput: String,
        personalizedContext: String = "",
        businessContext: String = "",
        language: String = "sw"
    ): String {
        val systemPrompt = SYSTEM_PROMPTS[language] ?: requireNotNull(SYSTEM_PROMPTS["sw"]) { "Missing fallback Swahili system prompt" }

        val fullPrompt = buildString {
            append(systemPrompt)
            append("\n\n")

            if (personalizedContext.isNotBlank()) {
                append("Maelezo ya mteja (kutokana na kujifunza kwa mteja):\n")
                append(personalizedContext)
                append("\n\n")
            }

            if (businessContext.isNotBlank()) {
                append("Maelezo ya biashara:\n")
                append(businessContext)
                append("\n\n")
            }

            append("Mteja: ")
            append(userInput)
            append("\nMsaidizi:")
        }

        return generate(
            fullPrompt,
            maxTokens = DEFAULT_MAX_TOKENS_RESPONSE,
            temperature = DEFAULT_TEMPERATURE
        )
    }

    /**
     * Generate advice with full adaptive context.
     */
    suspend fun generateAdviceWithContext(
        userInput: String,
        personalizedContext: String = "",
        recentTransactions: String = "",
        inventoryStatus: String = "",
        language: String = "sw"
    ): String {
        val businessContext = buildString {
            if (recentTransactions.isNotBlank()) {
                append("Shughuli za hivi karibuni: ")
                append(recentTransactions)
                append(". ")
            }
            if (inventoryStatus.isNotBlank()) {
                append("Hali ya stock: ")
                append(inventoryStatus)
                append(". ")
            }
        }

        return generateWithAdaptiveContext(
            userInput = userInput,
            personalizedContext = personalizedContext,
            businessContext = businessContext,
            language = language
        )
    }

    /**
     * Parse a correction from user input.
     */
    suspend fun parseCorrection(userInput: String, context: String = ""): Correction? {
        val prompt = """Parse the user's correction. Extract what they said was wrong and what is correct.

Examples:
"I meant 500 not 300" → {"correct": 500, "wrong": 300}
"No, the item is sugar not flour" → {"correct_item": "sugar", "wrong_item": "flour"}
"I sold 10 not 5" → {"correct_quantity": 10, "wrong_quantity": 5}

User correction: $userInput
JSON:"""

        val output = generate(prompt, maxTokens = 64, temperature = 0.1f)

        return try {
            val correctAmount = Regex(""""correct":\s*(\d+\.?\d*)""").find(output)
                ?.groupValues?.get(1)?.toDoubleOrNull()
            val wrongAmount = Regex(""""wrong":\s*(\d+\.?\d*)""").find(output)
                ?.groupValues?.get(1)?.toDoubleOrNull()
            val correctItem = Regex("\"correct_item\":\\s*\"([^\"]+)\"").find(output)
                ?.groupValues?.get(1)
            val wrongItem = Regex("\"wrong_item\":\\s*\"([^\"]+)\"").find(output)
                ?.groupValues?.get(1)

            if (correctAmount != null || correctItem != null) {
                Correction(
                    correctedAmount = correctAmount,
                    originalAmount = wrongAmount,
                    correctedItem = correctItem,
                    originalItem = wrongItem
                )
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // ────────────────────── Helpers ──────────────────────

    /**
     * Truncate prompt from the beginning, keeping the most recent context.
     * Approximate: 1 token ≈ 4 chars for English, 3 chars for Swahili.
     */
    private fun truncatePrompt(prompt: String, maxTokens: Int): String {
        val maxChars = maxTokens * 3  // Conservative estimate
        return if (prompt.length > maxChars) {
            "..." + prompt.takeLast(maxChars)
        } else prompt
    }

    /**
     * Get maximum context length based on device tier.
     */
    private fun getMaxContextLength(): Int {
        val maxMemoryMB = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        return when {
            maxMemoryMB >= 4096 -> CTX_ENHANCED   // 4GB+ device
            maxMemoryMB >= 3072 -> CTX_STANDARD   // 3GB device
            else -> CTX_BASIC                      // 2GB device
        }
    }
} // LlmEngine

// ────────────────────── Data Classes ──────────────────────

/**
 * Result from function-calling generation.
 */
data class FunctionCallResult(
    val isFunctionCall: Boolean,
    val functionCallJson: String?,
    val textResponse: String?
)

/**
 * Parsed correction from user input.
 */
data class Correction(
    val correctedAmount: Double? = null,
    val originalAmount: Double? = null,
    val correctedItem: String? = null,
    val originalItem: String? = null,
    val correctedQuantity: Double? = null,
    val originalQuantity: Double? = null
)
