package com.msaidizi.app.agent.harness

/**
 * Stub: Inference harness for managing model inference.
 */
class InferenceHarness(
    private val config: HarnessConfig = HarnessConfig()
) {
    suspend fun infer(prompt: String, model: String = ""): String = ""
    fun isAvailable(): Boolean = false
}

data class HarnessConfig(
    val maxRetries: Int = 3,
    val timeoutMs: Long = 30_000,
    val fallbackModels: List<String> = emptyList()
)

data class ProviderCandidate(
    val name: String,
    val priority: Int,
    val isAvailable: Boolean = true
)

class InferenceHarnessException(message: String) : Exception(message)

/**
 * Stub: Learning harness for training data collection.
 */
class LearningHarness {
    fun collectTrainingData(input: String, output: String) {}
    fun getTrainingData(): List<Pair<String, String>> = emptyList()
}
