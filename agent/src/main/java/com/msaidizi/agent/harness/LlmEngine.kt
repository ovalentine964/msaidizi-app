package com.msaidizi.agent.harness

import android.content.Context
import com.msaidizi.agent.tools.ToolResult
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
            val loaded = llamaCppEngine.loadModel(modelFile.absolutePath, 2048, cores)
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

    suspend fun generate(
        systemPrompt: String,
        userMessage: String,
        context: AssembledContext,
        toolResults: List<ToolResult>,
        intent: UserIntent
    ): String = withContext(Dispatchers.Default) {
        if (!isInitialized) return@withContext generateFallbackResponse(intent)
        try {
            val prompt = buildPrompt(systemPrompt, userMessage, context, toolResults, intent)
            llamaCppEngine.generate(prompt, maxTokens = 256, temperature = 0.7f, topP = 0.9f).trim()
        } catch (e: Exception) {
            Timber.e(e, "LLM generation failed")
            generateFallbackResponse(intent)
        }
    }

    private fun buildPrompt(
        systemPrompt: String,
        userMessage: String,
        context: AssembledContext,
        toolResults: List<ToolResult>,
        intent: UserIntent
    ): String = buildString {
        appendLine("<|im_start|>system")
        appendLine(systemPrompt)
        appendLine("You are Msaidizi, an AI business assistant for Kenyan MSMEs.")
        appendLine("Respond in the user's language (Swahili or English).")
        appendLine("Be concise, actionable, and friendly.")
        appendLine("</s>")
        appendLine("<|im_start|>user")
        if (!context.recentFinancialSummary.isNullOrEmpty()) {
            appendLine("Business context:")
            appendLine(context.recentFinancialSummary.take(500))
        }
        if (toolResults.isNotEmpty()) {
            appendLine("Tool results:")
            toolResults.forEach { result ->
                appendLine("- ${result.toolName}: ${result.message.take(200)}")
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
