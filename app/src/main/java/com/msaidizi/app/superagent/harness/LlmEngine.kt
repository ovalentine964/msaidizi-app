package com.msaidizi.app.superagent.harness

import android.content.Context
import com.msaidizi.app.superagent.tools.ToolResult
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM Engine — Interface to the on-device language model (Qwen 0.8B via llama.cpp).
 *
 * This is the reasoning core of the superagent. Runs entirely on-device.
 * Uses Hermes-style function calling for tool use.
 */
@Singleton
class LlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) {
    private var isInitialized = false
    private var modelPath: String? = null

    // JNI bridge to llama.cpp
    private external fun nativeLoadModel(path: String, contextSize: Int, threads: Int): Long
    private external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        stopSequences: String // JSON array
    ): String
    private external fun nativeFreeModel(handle: Long)

    private var modelHandle: Long = 0L

    /**
     * Initialize the LLM engine with the model file.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext true

        try {
            // Find model file in app's files directory
            val modelDir = File(context.filesDir, "models")
            val modelFile = modelDir.listFiles()?.firstOrNull { it.name.endsWith(".gguf") }

            if (modelFile == null) {
                Timber.w("No GGUF model found in ${modelDir.absolutePath}")
                // Model will be downloaded on first use or provided via assets
                return@withContext false
            }

            modelPath = modelFile.absolutePath

            // Detect available cores (use 2-4 for low-end devices)
            val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val contextSize = 2048 // Small context for 2GB RAM devices

            modelHandle = nativeLoadModel(modelFile.absolutePath, contextSize, cores)
            isInitialized = modelHandle != 0L

            if (isInitialized) {
                Timber.i("LLM initialized: ${modelFile.name} (${cores} threads, ctx=$contextSize)")
            } else {
                Timber.e("Failed to initialize LLM")
            }

            isInitialized
        } catch (e: Exception) {
            Timber.e(e, "LLM initialization error")
            false
        }
    }

    /**
     * Generate a response from the LLM.
     */
    suspend fun generate(
        systemPrompt: String,
        userMessage: String,
        context: AssembledContext,
        toolResults: List<ToolResult>,
        intent: UserIntent
    ): String = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            return@withContext generateFallbackResponse(intent)
        }

        try {
            val prompt = buildPrompt(systemPrompt, userMessage, context, toolResults, intent)

            val response = nativeGenerate(
                handle = modelHandle,
                prompt = prompt,
                maxTokens = 256, // Keep responses short for mobile
                temperature = 0.7f,
                topP = 0.9f,
                stopSequences = gson.toJson(listOf("Human:", "User:", "\n\n"))
            )

            response.trim()
        } catch (e: Exception) {
            Timber.e(e, "LLM generation failed")
            generateFallbackResponse(intent)
        }
    }

    /**
     * Build the full prompt for the LLM.
     */
    private fun buildPrompt(
        systemPrompt: String,
        userMessage: String,
        context: AssembledContext,
        toolResults: List<ToolResult>,
        intent: UserIntent
    ): String {
        return buildString {
            // System prompt
            appendLine("<|im_start|>system")
            appendLine(systemPrompt)
            appendLine("