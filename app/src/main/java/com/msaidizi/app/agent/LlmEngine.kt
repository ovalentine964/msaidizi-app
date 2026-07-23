package com.msaidizi.app.agent

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LlmEngine — On-device LLM inference via llama.cpp.
 * 
 * Qwen 0.8B running on $50 phones. Offline. Free.
 * Only used for 10% of requests that IntentRouter can't handle.
 * 
 * Cloud escalation: DeepSeek V4 Flash ($0.14/MTok) for complex queries
 * when device is online and on-device model is insufficient.
 * 
 * Design: arch_android.md Section 1.4, synthesize_all.md IP7
 */
@Singleton
class LlmEngine @Inject constructor() {
    private var isLoaded = false
    private var nativeHandle: Long = 0

    /**
     * Load the model into memory. Called on first use or after OOM recovery.
     * Model: Qwen3.5-0.8B-Q4_K_M.gguf (~500MB)
     * Memory budget: only load after Whisper/Kokoro unloaded (mutual exclusion).
     */
    suspend fun loadModel(): Boolean {
        if (isLoaded) return true

        return try {
            // TODO: Implement llama.cpp JNI loading
            // nativeHandle = LlamaCpp.loadModel(
            //     modelPath = "models/qwen3.5-0.8b-q4_k_m.gguf",
            //     contextSize = 2048,
            //     threads = 2,
            //     gpuLayers = 0  // No GPU on $50 phones
            // )
            isLoaded = true
            Timber.i("LLM model loaded (Qwen 0.8B)")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load LLM model")
            false
        }
    }

    /**
     * Generate a response from the on-device LLM.
     * @param prompt The full prompt including context
     * @param maxTokens Maximum tokens to generate
     * @return Generated text, or empty string on failure
     */
    suspend fun generate(prompt: String, maxTokens: Int = 256): String {
        if (!isLoaded) {
            val loaded = loadModel()
            if (!loaded) return ""
        }

        return try {
            // TODO: Implement llama.cpp inference
            // val result = LlamaCpp.generate(
            //     handle = nativeHandle,
            //     prompt = prompt,
            //     maxTokens = maxTokens,
            //     temperature = 0.7f,
            //     topP = 0.9f,
            //     repeatPenalty = 1.1f
            // )
            // result.text

            // Placeholder until JNI is integrated
            ""
        } catch (e: Exception) {
            Timber.e(e, "LLM generation failed")
            ""
        }
    }

    /**
     * Generate with system prompt and user input.
     */
    suspend fun generateResponse(
        userInput: String,
        context: String = "",
        language: String = "sw",
        maxTokens: Int = 256
    ): String {
        val prompt = buildString {
            append("You are Msaidizi, a helpful business assistant for informal workers in Kenya. ")
            append("Respond in ${if (language == "sw") "Swahili" else "English"}. ")
            append("Be brief, practical, and encouraging. ")
            if (context.isNotEmpty()) {
                append("Context: $context\n")
            }
            append("User: $userInput\nAssistant:")
        }

        return generate(prompt, maxTokens)
    }

    /**
     * Check if model is loaded.
     */
    fun isModelLoaded(): Boolean = isLoaded

    /**
     * Unload model to free memory (for 2GB devices).
     * Critical: must be called before loading STT/TTS models.
     */
    fun unload() {
        if (isLoaded) {
            // TODO: LlamaCpp.destroy(nativeHandle)
            nativeHandle = 0
            isLoaded = false
            Timber.i("LLM model unloaded")
        }
    }
}
