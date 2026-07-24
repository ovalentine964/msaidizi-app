package com.msaidizi.app.agent

import android.content.Context
import com.msaidizi.app.agent.harness.InferenceHarness
import com.msaidizi.app.data.api.MsaidiziApi
import com.msaidizi.app.voice.LlmEngine

/**
 * Stub: Model router for selecting between local and cloud models.
 */
class ModelRouter(
    private val context: Context,
    private val llmEngine: LlmEngine,
    private val apiClient: MsaidiziApi,
    private val inferenceHarness: InferenceHarness
) {
    suspend fun route(prompt: String, language: String = "sw"): String = ""
    fun isLocalModelAvailable(): Boolean = false
}
