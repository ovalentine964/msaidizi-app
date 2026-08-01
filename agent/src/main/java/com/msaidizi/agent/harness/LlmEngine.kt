package com.msaidizi.agent.harness

import android.content.Context
import com.msaidizi.agent.tools.core.ToolResult
import com.msaidizi.voice.LlamaCppEngine
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
 * LLM Engine — on-device language model (Qwen3.5 0.8B via llama.cpp).
 * Runs entirely on-device, no network required.
 */
@Singleton
class LlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson,
    private val llamaCppEngine: LlamaCppEngine
) {
    companion object {
        const val EXPECTED_MODEL_NAME = "Qwen3.5-0.8B-Q4_K_M.gguf"
        private const val MIN_GGUF_SIZE_BYTES = 1L * 1024 * 1024
        private val GGUF_MAGIC = byteArrayOf(
            'G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()
        )

        /** Default context window — 4096 tokens minimum */
        const val DEFAULT_CONTEXT_SIZE = 4096

        /** Extended context for advice/report intents — 8192 tokens */
        const val ADVICE_CONTEXT_SIZE = 8192

        /** Maximum context to prevent OOM on 2GB devices */
        const val MAX_CONTEXT_SIZE = 8192
    }

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

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext true
        state = State.Loading
        try {
            val modelDir = File(context.filesDir, "models")
            if (!modelDir.exists()) modelDir.mkdirs()

            val modelFile = findModelFile(modelDir)
            if (modelFile == null) {
                state = State.MissingModel("Model not found: $EXPECTED_MODEL_NAME")
                return@withContext false
            }

            val validationError = validateGgufFile(modelFile)
            if (validationError != null) {
                state = State.Error("Model invalid: $validationError")
                return@withContext false
            }

            val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val loaded = llamaCppEngine.loadModel(modelFile.absolutePath, DEFAULT_CONTEXT_SIZE, cores)
            isInitialized = loaded
            state = if (loaded) State.Ready else State.Error("loadModel returned false")
            isInitialized
        } catch (e: Exception) {
            Timber.e(e, "LLM initialization error")
            state = State.Error(e.message ?: "Unknown error")
            false
        }
    }

    private fun findModelFile(modelDir: File): File? {
        val files = modelDir.listFiles() ?: return null
        return files.firstOrNull { it.name == EXPECTED_MODEL_NAME }
            ?: files.firstOrNull { it.name.endsWith(".gguf", ignoreCase = true) && it.name.contains("Qwen3", ignoreCase = true) }
            ?: files.firstOrNull { it.name.endsWith(".gguf", ignoreCase = true) }
    }

    private fun validateGgufFile(file: File): String? {
        if (!file.exists() || !file.canRead()) return "File not readable"
        if (file.length() < MIN_GGUF_SIZE_BYTES) return "File too small"
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                if (!magic.contentEquals(GGUF_MAGIC)) "Invalid GGUF magic" else null
            }
        } catch (e: Exception) { "Read error: ${e.message}" }
    }

    /**
     * Determine the appropriate context size for the given intent.
     * Advice and report intents need more context for richer analysis.
     */
    private fun contextSizeForIntent(intent: UserIntent): Int {
        return when (intent.type) {
            IntentType.ASK_ADVICE,
            IntentType.DAILY_REPORT,
            IntentType.WEEKLY_REPORT,
            IntentType.MONTHLY_REPORT,
            IntentType.ASK_PROFIT -> ADVICE_CONTEXT_SIZE
            else -> DEFAULT_CONTEXT_SIZE
        }
    }

    /**
     * Generate a response from the LLM.
     *
     * P1 improvements:
     * - Thinking mode enabled for complex reasoning intents (advice, reports, analysis)
     * - Dynamic max tokens based on intent complexity
     * - Intent-specific temperature tuning
     */
    suspend fun generate(
        systemPrompt: String,
        userMessage: String,
        context: AssembledContext,
        toolResults: List<ToolResult>,
        intent: UserIntent
    ): String = withContext(Dispatchers.Default) {
        if (!isInitialized) return@withContext generateFallbackResponse(intent)
        try {
            val ctxSize = contextSizeForIntent(intent)
            val enableThinking = shouldEnableThinking(intent)
            val prompt = buildPrompt(systemPrompt, userMessage, context, toolResults, intent, ctxSize, enableThinking)
            val maxTokens = dynamicMaxTokens(intent)
            val temperature = dynamicTemperature(intent)
            val result = llamaCppEngine.generate(
                prompt,
                maxTokens = maxTokens,
                temperature = temperature,
                topP = 0.9f
            ).trim()

            // Strip thinking tags from output if thinking mode was enabled
            if (enableThinking) {
                stripThinkingTags(result)
            } else {
                result
            }
        } catch (e: Exception) {
            Timber.e(e, "LLM generation failed")
            generateFallbackResponse(intent)
        }
    }

    /**
     * Determine if thinking mode should be enabled for this intent.
     * Qwen3 supports toggleable "thinking mode" for step-by-step reasoning.
     * Enable for complex reasoning tasks; disable for simple queries.
     */
    private fun shouldEnableThinking(intent: UserIntent): Boolean {
        return when (intent.type) {
            IntentType.ASK_ADVICE,
            IntentType.LOAN_COMPARE,
            IntentType.INSURANCE_MATCH,
            IntentType.DAILY_REPORT,
            IntentType.WEEKLY_REPORT,
            IntentType.MONTHLY_REPORT,
            IntentType.ASK_PROFIT,
            IntentType.CREDIT_CHECK -> true
            else -> false
        }
    }

    /**
     * Dynamic max tokens based on intent complexity.
     * Advice and report intents need more tokens for detailed analysis.
     */
    private fun dynamicMaxTokens(intent: UserIntent): Int {
        return when (intent.type) {
            IntentType.ASK_ADVICE -> 768
            IntentType.LOAN_COMPARE,
            IntentType.INSURANCE_MATCH -> 640
            IntentType.DAILY_REPORT,
            IntentType.WEEKLY_REPORT,
            IntentType.MONTHLY_REPORT -> 768
            IntentType.ASK_PROFIT -> 512
            IntentType.CREDIT_CHECK -> 512
            else -> 256
        }
    }

    /**
     * Dynamic temperature based on intent type.
     * Lower temperature for factual/financial tasks, higher for creative advice.
     */
    private fun dynamicTemperature(intent: UserIntent): Float {
        return when (intent.type) {
            IntentType.ASK_ADVICE,
            IntentType.INSURANCE_MATCH -> 0.7f   // Creative advice
            IntentType.LOAN_COMPARE,
            IntentType.CREDIT_CHECK,
            IntentType.ASK_PROFIT -> 0.4f          // Factual analysis
            IntentType.DAILY_REPORT,
            IntentType.WEEKLY_REPORT,
            IntentType.MONTHLY_REPORT -> 0.5f      // Balanced
            else -> 0.7f
        }
    }

    /**
     * Strip Qwen3 thinking tags from output.
     * Thinking mode outputs <think>...</think> before the actual response.
     */
    private fun stripThinkingTags(text: String): String {
        val thinkPattern = Regex("<think>[\s\S]*?</think>", RegexOption.DOT_MATCHES_ALL)
        return thinkPattern.replace(text, "").trim()
    }

    private fun buildPrompt(
        systemPrompt: String,
        userMessage: String,
        context: AssembledContext,
        toolResults: List<ToolResult>,
        intent: UserIntent,
        contextSize: Int = DEFAULT_CONTEXT_SIZE,
        enableThinking: Boolean = false
    ): String = buildString {
        // Calculate budget for context sections based on available context size
        val contextBudget = ((contextSize - 512) * 0.6).toInt().coerceAtLeast(500)

        appendLine("<|im_start|>system")
        appendLine(systemPrompt)
        appendLine("You are Msaidizi, an AI business assistant for Kenyan MSMEs.")
        appendLine("Respond in the user's language (Swahili or English).")
        if (enableThinking) {
            appendLine("Think step-by-step before answering. For financial advice, analyze the data carefully.")
        } else {
            appendLine("Be concise, actionable, and friendly.")
        }
        appendLine("</s>")
        appendLine("<|im_start|>user")
        if (!context.recentFinancialSummary.isNullOrEmpty()) {
            appendLine("Business context:")
            appendLine(context.recentFinancialSummary.take((contextBudget * 0.4).toInt()))
        }
        if (toolResults.isNotEmpty()) {
            appendLine("Tool results:")
            toolResults.forEach { result ->
                appendLine("- ${result.toolName}: ${result.message.take((contextBudget * 0.3).toInt())}")
            }
        }
        // Include knowledge context for advice intents
        if (intent.type == IntentType.ASK_ADVICE && context.knowledgeContext.isNotEmpty()) {
            appendLine("Relevant knowledge:")
            context.knowledgeContext.take(3).forEach { knowledge ->
                appendLine("- ${knowledge.take(200)}")
            }
        }
        // Include flywheel patterns for personalized advice
        if (context.relevantPatterns.isNotEmpty() &&
            (intent.type == IntentType.ASK_ADVICE || intent.type == IntentType.DAILY_REPORT)) {
            appendLine("Learned patterns:")
            context.relevantPatterns.take(2).forEach { pattern ->
                appendLine("- ${pattern.take(150)}")
            }
        }
        appendLine(userMessage)
        appendLine("</s>")
        appendLine("<|im_start|>assistant")
    }

    private fun generateFallbackResponse(intent: UserIntent): String {
        return when (intent.type) {
            IntentType.RECORD_SALE -> "Nimesikia! Tafadhali sema tena ili nirekodi mauzo yako."
            IntentType.RECORD_EXPENSE -> "Sawa! Tafadhali sema gharama yako tena."
            IntentType.ASK_PROFIT -> "Inapakia ripoti ya faida yako..."
            IntentType.GREETING -> "Habari! Mimi ni Msaidizi, msaidizi wako wa biashara. Nisaidie nini leo?"
            else -> "Pole sana, sijaelewa. Tafadhali jaribu tena."
        }
    }

    fun release() {
        isInitialized = false
        llamaCppEngine.release()
        Timber.i("LLM engine released")
    }
}
