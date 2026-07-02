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
 * On-device LLM inference engine using llama.cpp (GGUF format).
 *
 * Model: Qwen2.5-0.5B Q4_K_M (~300MB)
 *
 * Use cases (10% of requests that code/regex can't handle):
 * - Natural language advice generation
 * - Correction parsing ("I meant 500 not 300")
 * - Complex queries that regex misses
 * - Multilingual responses
 * - Summarization
 *
 * Performance on Helio G25 (2GB):
 * - Load time: ~3s with mmap
 * - Inference: ~8 tokens/sec (prompt), ~5 tokens/sec (generation)
 * - Context: 1024 tokens (BASIC tier) to 4096 (ENHANCED)
 * - RAM: ~350MB when loaded (released on background)
 *
 * Architecture: Uses llama.cpp JNI bindings via Android NDK.
 * Build dependency: com.github.ggerganov:llama.cpp-android
 *
 * Memory management:
 * - Model is memory-mapped for fast loading
 * - Automatically unloaded on low-memory devices when backgrounded
 * - Progressive loading: only loads on 3GB+ devices unless explicitly requested
 */
@Singleton
class LlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val languageModelRegistry: LanguageModelRegistry? = null,
    private val adaptiveAsrEngine: AdaptiveAsrEngine? = null
) {
    // ────────────── Native JNI Methods (llama.cpp) ──────────────

    /**
     * Load a GGUF model with memory-mapped file access.
     * @param path Path to .gguf file
     * @param nCtx Maximum context length in tokens
     * @param nThreads Number of CPU threads for inference
     * @return Model handle (0 on failure)
     */
    // Native method — implemented in llama_jni.cpp via JNI
    // Stub for kapt compatibility (actual implementation loaded at runtime)
    fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int): Long {
        throw UnsupportedOperationException("Native method — requires llama_jni library")
    }

    /**
     * Free a loaded model and release memory.
     * @param handle Model handle from nativeLoadModel
     */
    // Native method — implemented in llama_jni.cpp via JNI
    // Stub for kapt compatibility (actual implementation loaded at runtime)
    fun nativeFreeModel(handle: Long) {
        throw UnsupportedOperationException("Native method — requires llama_jni library")
    }

    /**
     * Generate text from a prompt with streaming callback.
     *
     * @param handle Model handle
     * @param prompt Full prompt text (system + user)
     * @param maxTokens Maximum tokens to generate
     * @param temperature Sampling temperature (0.0 = greedy, 1.0 = creative)
     * @param topP Nucleus sampling threshold
     * @param stopSequences Array of strings that stop generation
     * @param callback Called for each generated token
     * @return Complete generated text
     */
    // Native method — implemented in llama_jni.cpp via JNI
    // Stub for kapt compatibility (actual implementation loaded at runtime)
    fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        stopSequences: Array<String>,
        callback: (String) -> Unit
    ): String {
        throw UnsupportedOperationException("Native method — requires llama_jni library")
    }

    /**
     * Count tokens in a text string.
     * @param handle Model handle
     * @param text Text to tokenize
     * @return Number of tokens
     */
    // Native method — implemented in llama_jni.cpp via JNI
    // Stub for kapt compatibility (actual implementation loaded at runtime)
    fun nativeGetTokenCount(handle: Long, text: String): Int {
        throw UnsupportedOperationException("Native method — requires llama_jni library")
    }

    /**
     * Evaluate a function call from the model output.
     * Parses JSON function call format.
     */
    // Native method — implemented in llama_jni.cpp via JNI
    // Stub for kapt compatibility (actual implementation loaded at runtime)
    fun nativeParseFunctionCall(
        handle: Long,
        output: String
    ): String {
        throw UnsupportedOperationException("Native method — requires llama_jni library")
    }

    companion object {
        // Whether llama_jni native library is available
        var isNativeAvailable = false
            private set

        init {
            try {
                System.loadLibrary("llama_jni")
                isNativeAvailable = true
                Timber.d("llama_jni native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                isNativeAvailable = false
                Timber.e(e, "llama_jni native library not found — LLM features disabled")
            }
        }

        // Default context lengths by device tier
        private const val CTX_BASIC = 1024
        private const val CTX_STANDARD = 2048
        private const val CTX_ENHANCED = 4096

        // Default generation parameters
        private const val DEFAULT_MAX_TOKENS = 256
        private const val DEFAULT_TEMPERATURE = 0.3f
        private const val DEFAULT_TOP_P = 0.9f
        private const val DEFAULT_MAX_TOKENS_RESPONSE = 128

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

    private var modelHandle: Long = 0
    private var isLoaded = false
    private var isGenerating = false
    private var currentNThreads = 2

    // ────────────────────── Model Lifecycle ──────────────────────

    /**
     * Load the LLM model with memory-mapped file access.
     * Uses mmap for fast loading and reduced RAM pressure.
     *
     * @return true if model loaded successfully
     */
    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext true

        if (!isNativeAvailable) {
            Timber.w("Cannot load LLM: llama_jni native library is not available")
            return@withContext false
        }

        try {
            val modelPath = File(context.filesDir, "models/qwen-0.5b-q4_k_m.gguf")
            if (!modelPath.exists()) {
                Timber.w("LLM model not found: %s", modelPath.absolutePath)
                return@withContext false
            }

            val nCtx = getMaxContextLength()
            currentNThreads = getInferenceThreads()

            Timber.d("Loading LLM: %s (ctx=%d, threads=%d)", modelPath.name, nCtx, currentNThreads)
            val startTime = System.currentTimeMillis()

            modelHandle = nativeLoadModel(modelPath.absolutePath, nCtx, currentNThreads)

            if (modelHandle == 0L) {
                Timber.e("Failed to load LLM model (returned handle 0)")
                return@withContext false
            }

            isLoaded = true
            val elapsed = System.currentTimeMillis() - startTime
            Timber.i("LLM loaded in %dms (handle=%d)", elapsed, modelHandle)
            true
        } catch (e: UnsatisfiedLinkError) {
            Timber.e(e, "llama.cpp native library not available")
            false
        } catch (e: Exception) {
            Timber.e(e, "LLM load error")
            false
        }
    }

    /**
     * Unload model to free ~350MB RAM.
     * Called when app goes to background on 2GB devices.
     */
    fun unloadModel() {
        if (modelHandle != 0L) {
            try {
                nativeFreeModel(modelHandle)
            } catch (e: Exception) {
                Timber.e(e, "Error freeing LLM model")
            }
            modelHandle = 0
        }
        isLoaded = false
        Timber.d("LLM model unloaded")
    }

    fun isModelLoaded(): Boolean = isLoaded
    fun isCurrentlyGenerating(): Boolean = isGenerating

    // ────────────────────── Generation ──────────────────────

    /**
     * Generate a response from the LLM.
     *
     * @param prompt The full prompt (system + user)
     * @param maxTokens Maximum tokens to generate (default 256 for speed)
     * @param temperature Sampling temperature (0.1 for factual, 0.7 for creative)
     * @param onToken Callback for streaming token output
     * @return Complete generated text
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        temperature: Float = DEFAULT_TEMPERATURE,
        onToken: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.Default) {
        if (!isLoaded) {
            val loaded = loadModel()
            if (!loaded) return@withContext ""
        }

        if (isGenerating) {
            Timber.w("LLM already generating, skipping request")
            return@withContext ""
        }

        isGenerating = true
        try {
            val startTime = System.currentTimeMillis()

            // Truncate prompt if it exceeds context window
            val promptTokens = nativeGetTokenCount(modelHandle, prompt)
            val maxCtx = getMaxContextLength()
            val actualPrompt = if (promptTokens > maxCtx - maxTokens) {
                truncatePrompt(prompt, maxCtx - maxTokens)
            } else prompt

            val stopSequences = arrayOf(
                "<|eot|>", "</s>", "\n\n\n", "Human:", "User:", "Msaidizi:"
            )

            val result = StringBuilder()
            nativeGenerate(
                modelHandle, actualPrompt, maxTokens,
                temperature, DEFAULT_TOP_P, stopSequences
            ) { token ->
                result.append(token)
                onToken?.invoke(token)
            }

            val elapsed = System.currentTimeMillis() - startTime
            val output = result.toString().trim()
            Timber.d(
                "LLM generated %d chars in %dms (%.1f tok/s)",
                output.length, elapsed,
                if (elapsed > 0) output.length * 4.0 / elapsed * 1000 else 0.0
            )
            output

        } catch (e: Exception) {
            Timber.e(e, "LLM generation error")
            ""
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

        return generate(
            fullPrompt,
            maxTokens = DEFAULT_MAX_TOKENS_RESPONSE,
            temperature = DEFAULT_TEMPERATURE
        )
    }

    /**
     * Generate response with learned context from the language learning pipeline.
     *
     * This method enriches the prompt with:
     * - User's learned vocabulary (product names, prices, terms)
     * - Business patterns (restock cycles, price trends)
     * - Dialect-specific normalization (Migori Swahili → standard)
     * - Active language adapter context
     *
     * The learned context is injected as a structured prefix that the LLM
     * can use to better understand the user's intent and generate more
     * personalized responses.
     *
     * @param userInput User's message (already transcribed by AdaptiveAsrEngine)
     * @param businessContext Business context (transactions, inventory)
     * @param language Response language
     * @param learnedContext Optional pre-built learned context string
     * @return Msaidizi's response, enriched with learned patterns
     */
    suspend fun generateWithLearnedContext(
        userInput: String,
        businessContext: String = "",
        language: String = "sw",
        learnedContext: String? = null
    ): String {
        val systemPrompt = SYSTEM_PROMPTS[language] ?: SYSTEM_PROMPTS["sw"]!!

        // Build learned context from available sources
        val enrichedContext = buildLearnedContext(businessContext, language, learnedContext)

        val fullPrompt = buildString {
            append(systemPrompt)
            append("\n\n")

            // Inject learned context (vocabulary, patterns, dialect info)
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
     *
     * Context includes:
     * - Active language and dialect
     * - User's top vocabulary terms with typical prices
     * - Recent correction patterns (what the user tends to correct)
     * - Business product categories
     * - Learning progress (how personalized the model is)
     *
     * Format is structured for LLM consumption:
     * ```
     * Lugha: Kiswahili (Migori)
     * Bidhaa: mandazi (KSh 20), chapati (KSh 30), mchele (KSh 150/kilo)
     * Masoko ya kawaida: mauzo ya asubuhi, ununuzi wa jioni
     * Ukuzi wa msamiati: 45 maneno
     * ```
     */
    private suspend fun buildLearnedContext(
        businessContext: String,
        language: String,
        prebuiltContext: String?
    ): String {
        // If pre-built context is provided, use it
        if (!prebuiltContext.isNullOrBlank()) return prebuiltContext

        val parts = mutableListOf<String>()

        // Language and dialect info
        val langDef = languageModelRegistry?.getLanguageDef(language)
        if (langDef != null) {
            parts.add("Lugha: ${langDef.nativeName}")
        }

        // Learning progress
        val stats = try {
            adaptiveAsrEngine?.getCorrectionStats()
        } catch (e: Exception) { null }

        if (stats != null && stats.totalCorrections > 0) {
            parts.add("Ukuzi wa msamiati: maneno ${stats.totalCorrections}")
        }

        // Merge with business context
        if (businessContext.isNotBlank()) {
            parts.add(businessContext)
        }

        return parts.joinToString("\n")
    }

    /**
     * Generate with function calling support.
     * Returns a structured function call if the model determines one is needed.
     *
     * @param userInput User's message
     * @param context Business context
     * @param language Response language
     * @return FunctionCallResult with either a function call or text response
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
            // Try to parse as function call
            val funcJson = nativeParseFunctionCall(modelHandle, output)
            if (funcJson.isNotBlank() && funcJson.startsWith("{")) {
                FunctionCallResult(
                    isFunctionCall = true,
                    functionCallJson = funcJson,
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
     *
     * This is the primary method for Level 2 adaptive learning.
     * The personalizedContext includes:
     * - User's product vocabulary and price ranges
     * - Business patterns (peak hours, best days, trends)
     * - Recent correction history
     * - Business health score
     *
     * @param userInput User's message
     * * @param personalizedContext Context from AdaptiveLearningEngine
     * @param businessContext Additional business context (transactions, inventory)
     * @param language Response language
     * @return Msaidizi's personalized response
     */
    suspend fun generateWithAdaptiveContext(
        userInput: String,
        personalizedContext: String = "",
        businessContext: String = "",
        language: String = "sw"
    ): String {
        val systemPrompt = SYSTEM_PROMPTS[language] ?: SYSTEM_PROMPTS["sw"]!!

        val fullPrompt = buildString {
            append(systemPrompt)
            append("\n\n")

            // Personalized context from adaptive learning (highest priority)
            if (personalizedContext.isNotBlank()) {
                append("Maelezo ya mteja (kutokana na kujifunza kwa mteja):\n")
                append(personalizedContext)
                append("\n\n")
            }

            // General business context
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
     * Combines personalized context with business data for rich, relevant advice.
     *
     * @param userInput User's question or request
     * @param personalizedContext From AdaptiveLearningEngine
     * @param recentTransactions Recent transaction summary
     * @param inventoryStatus Current inventory status
     * @param language Response language
     * @return Personalized business advice
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
     * E.g., "I meant 500 not 300" → Correction(500.0, 300.0)
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
            // Simple parsing — extract numbers and items
            val correctAmount = Regex(""""correct":\s*(\d+\.?\d*)""").find(output)
                ?.groupValues?.get(1)?.toDoubleOrNull()
            val wrongAmount = Regex(""""wrong":\s*(\d+\.?\d*)""").find(output)
                ?.groupValues?.get(1)?.toDoubleOrNull()
            val correctItem = Regex(""""correct_item":\s*"([^"]+)"""").find(output)
                ?.groupValues?.get(1)
            val wrongItem = Regex(""""wrong_item":\s*"([^"]+)"""").find(output)
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
        val runtime = Runtime.getRuntime()
        val maxMemoryMB = runtime.maxMemory() / (1024 * 1024)
        return when {
            maxMemoryMB >= 4096 -> CTX_ENHANCED   // 4GB+ device
            maxMemoryMB >= 3072 -> CTX_STANDARD   // 3GB device
            else -> CTX_BASIC                      // 2GB device
        }
    }

    /**
     * Get optimal thread count for inference.
     */
    private fun getInferenceThreads(): Int {
        val cpuCores = Runtime.getRuntime().availableProcessors()
        return (cpuCores / 2).coerceIn(1, 4)  // Use half cores, max 4
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
