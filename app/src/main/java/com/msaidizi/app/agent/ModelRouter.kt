package com.msaidizi.app.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.LruCache
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ModelRouter — OmniRoute-inspired on-device inference router for Msaidizi.
 *
 * Routes inference requests between:
 * - On-device models (llama.cpp NDK) — offline, free, low-latency
 * - Cloud API (Groq, DeepSeek) — higher quality, needs connectivity
 * - Backend proxy (Biashara Intelligence) — full agent capabilities
 *
 * Features:
 * - Automatic offline/online detection and routing
 * - Result caching with LRU eviction
 * - Usage and cost tracking
 * - Compression of long prompts
 * - Graceful degradation when offline
 */
class ModelRouter(
    private val context: Context,
    private val config: RouterConfig = RouterConfig()
) {

    data class RouterConfig(
        val cacheSize: Int = 100,
        val maxPromptTokens: Int = 2048,
        val compressionThreshold: Int = 1500,
        val cloudTimeoutMs: Long = 15_000,
        val onDeviceTimeoutMs: Long = 10_000,
        val enableCache: Boolean = true,
        val preferOnDevice: Boolean = true
    )

    data class InferenceRequest(
        val requestId: String,
        val messages: List<Map<String, String>>,
        val model: String? = null,
        val maxTokens: Int = 512,
        val temperature: Float = 0.7f,
        val taskComplexity: TaskComplexity = TaskComplexity.MEDIUM,
        val userId: String? = null
    )

    data class InferenceResponse(
        val requestId: String,
        val providerId: String,
        val modelUsed: String,
        val content: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val latencyMs: Long,
        val fromCache: Boolean = false,
        val compressionInfo: Map<String, Any> = emptyMap()
    )

    enum class TaskComplexity { LOW, MEDIUM, HIGH }

    enum class ProviderType { ON_DEVICE, CLOUD_API, BACKEND }

    data class Provider(
        val id: String,
        val type: ProviderType,
        val displayName: String,
        val models: List<String>,
        val costPer1kInput: Double = 0.0,
        val costPer1kOutput: Double = 0.0,
        val priority: Int = 100,
        var isAvailable: Boolean = true,
        var consecutiveFailures: Int = 0,
        val avgLatencyMs: Long = 0,
        val totalRequests: AtomicLong = AtomicLong(0),
        val totalFailures: AtomicLong = AtomicLong(0)
    )

    // Provider registry
    private val providers = ConcurrentHashMap<String, Provider>().apply {
        put("on-device", Provider(
            id = "on-device",
            type = ProviderType.ON_DEVICE,
            displayName = "On-Device (llama.cpp)",
            models = listOf("qwen-0.5b-fl-sw", "phi-2", "tinyllama"),
            costPer1kInput = 0.0,
            costPer1kOutput = 0.0,
            priority = 10
        ))
        put("groq", Provider(
            id = "groq",
            type = ProviderType.CLOUD_API,
            displayName = "Groq (LPU)",
            models = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant"),
            costPer1kInput = 0.00005,
            costPer1kOutput = 0.0001,
            priority = 20
        ))
        put("deepseek", Provider(
            id = "deepseek",
            type = ProviderType.CLOUD_API,
            displayName = "DeepSeek",
            models = listOf("deepseek-chat", "deepseek-coder"),
            costPer1kInput = 0.00014,
            costPer1kOutput = 0.00028,
            priority = 30
        ))
        put("backend", Provider(
            id = "backend",
            type = ProviderType.BACKEND,
            displayName = "Biashara Backend",
            models = listOf("biashara-agent"),
            costPer1kInput = 0.0,
            costPer1kOutput = 0.0,
            priority = 25
        ))
    }

    // Result cache
    private val resultCache = LruCache<String, InferenceResponse>(config.cacheSize)

    // Usage tracking
    private val totalRequests = AtomicLong(0)
    private val totalTokensIn = AtomicLong(0)
    private val totalTokensOut = AtomicLong(0)
    private val totalCostMicros = AtomicLong(0) // cost in micro-dollars
    private val requestLog = mutableListOf<RequestLogEntry>()

    data class RequestLogEntry(
        val requestId: String,
        val providerId: String,
        val model: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val latencyMs: Long,
        val fromCache: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Route an inference request to the optimal provider.
     */
    suspend fun infer(request: InferenceRequest): InferenceResponse = withContext(Dispatchers.IO) {
        totalRequests.incrementAndGet()

        // Check cache first
        if (config.enableCache) {
            val cacheKey = computeCacheKey(request)
            resultCache.get(cacheKey)?.let { cached ->
                return@withContext cached.copy(fromCache = true)
            }
        }

        // Compress if needed
        val messages = if (estimateTokens(request.messages) > config.compressionThreshold) {
            compressMessages(request.messages)
        } else {
            request.messages
        }

        // Build fallback chain
        val chain = buildFallbackChain(request.taskComplexity)

        // Try each provider
        var lastException: Exception? = null
        for (provider in chain) {
            try {
                val startTime = System.currentTimeMillis()
                val response = callProvider(provider, request.copy(messages = messages))
                val latencyMs = System.currentTimeMillis() - startTime

                provider.totalRequests.incrementAndGet()
                provider.consecutiveFailures = 0

                val result = response.copy(latencyMs = latencyMs)

                // Track usage
                totalTokensIn.addAndGet(result.inputTokens.toLong())
                totalTokensOut.addAndGet(result.outputTokens.toLong())
                val costMicros = ((result.inputTokens * provider.costPer1kInput / 1000.0) +
                        (result.outputTokens * provider.costPer1kOutput / 1000.0) * 1_000_000).toLong()
                totalCostMicros.addAndGet(costMicros)

                // Log
                synchronized(requestLog) {
                    requestLog.add(RequestLogEntry(
                        requestId = result.requestId,
                        providerId = result.providerId,
                        model = result.modelUsed,
                        inputTokens = result.inputTokens,
                        outputTokens = result.outputTokens,
                        latencyMs = result.latencyMs,
                        fromCache = false
                    ))
                    if (requestLog.size > 200) {
                        requestLog.removeAt(0)
                    }
                }

                // Cache result
                if (config.enableCache && result.content.isNotEmpty()) {
                    val cacheKey = computeCacheKey(request)
                    resultCache.put(cacheKey, result)
                }

                return@withContext result

            } catch (e: Exception) {
                lastException = e
                provider.totalFailures.incrementAndGet()
                provider.consecutiveFailures++
                if (provider.consecutiveFailures >= 3) {
                    provider.isAvailable = false
                }
            }
        }

        throw lastException ?: IllegalStateException("No providers available")
    }

    /**
     * Build an ordered fallback chain based on task complexity and connectivity.
     */
    private fun buildFallbackChain(complexity: TaskComplexity): List<Provider> {
        val isOnline = isNetworkAvailable()
        val available = providers.values.filter { it.isAvailable }

        return when {
            // Offline: only on-device
            !isOnline -> available.filter { it.type == ProviderType.ON_DEVICE }

            // Low complexity: prefer on-device if configured
            complexity == TaskComplexity.LOW && config.preferOnDevice -> {
                val onDevice = available.filter { it.type == ProviderType.ON_DEVICE }
                val cloud = available.filter { it.type != ProviderType.ON_DEVICE }
                    .sortedBy { it.priority }
                onDevice + cloud
            }

            // High complexity: prefer cloud
            complexity == TaskComplexity.HIGH -> {
                val cloud = available.filter {
                    it.type == ProviderType.CLOUD_API || it.type == ProviderType.BACKEND
                }.sortedBy { it.priority }
                val onDevice = available.filter { it.type == ProviderType.ON_DEVICE }
                cloud + onDevice
            }

            // Medium: balanced — on-device first, then cloud
            else -> available.sortedBy { it.priority }
        }
    }

    /**
     * Call a specific provider. Override for actual integration.
     */
    private suspend fun callProvider(
        provider: Provider,
        request: InferenceRequest
    ): InferenceResponse {
        // This is where actual provider integration goes:
        // - On-device: JNI call to llama.cpp
        // - Cloud: HTTP call to Groq/DeepSeek API
        // - Backend: HTTP call to Biashara Intelligence API

        val model = request.model ?: provider.models.firstOrNull() ?: "default"

        return when (provider.type) {
            ProviderType.ON_DEVICE -> {
                // TODO: JNI call to llama.cpp NDK
                // LlamaCppBridge.infer(model, request.messages, request.maxTokens, request.temperature)
                InferenceResponse(
                    requestId = request.requestId,
                    providerId = provider.id,
                    modelUsed = model,
                    content = "", // Will be filled by actual implementation
                    inputTokens = estimateTokens(request.messages),
                    outputTokens = 0,
                    latencyMs = 0
                )
            }
            ProviderType.CLOUD_API -> {
                // TODO: HTTP call to cloud API
                // val apiClient = ApiClient(provider.baseUrl)
                // apiClient.chatCompletion(model, request.messages, request.maxTokens, request.temperature)
                InferenceResponse(
                    requestId = request.requestId,
                    providerId = provider.id,
                    modelUsed = model,
                    content = "",
                    inputTokens = estimateTokens(request.messages),
                    outputTokens = 0,
                    latencyMs = 0
                )
            }
            ProviderType.BACKEND -> {
                // TODO: HTTP call to Biashara backend
                // val backend = BackendClient()
                // backend.infer(request)
                InferenceResponse(
                    requestId = request.requestId,
                    providerId = provider.id,
                    modelUsed = model,
                    content = "",
                    inputTokens = estimateTokens(request.messages),
                    outputTokens = 0,
                    latencyMs = 0
                )
            }
        }
    }

    /**
     * Check if network is available.
     */
    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Estimate token count from messages.
     */
    private fun estimateTokens(messages: List<Map<String, String>>): Int {
        var total = 0
        for (msg in messages) {
            total += 4 // overhead
            total += (msg["role"]?.length ?: 0) / 4
            total += (msg["content"]?.length ?: 0) / 4
        }
        return maxOf(1, total)
    }

    /**
     * Compress long message histories.
     */
    private fun compressMessages(messages: List<Map<String, String>>): List<Map<String, String>> {
        if (messages.size <= 4) return messages

        val system = messages.filter { it["role"] == "system" }
        val nonSystem = messages.filter { it["role"] != "system" }
        val recent = nonSystem.takeLast(4)
        val older = nonSystem.dropLast(4)

        // Summarize older messages
        val summary = older.joinToString("\n") { msg ->
            val content = msg["content"]?.take(100) ?: ""
            "[${msg["role"]}]: $content"
        }

        val summaryMsg = mapOf("role" to "system", "content" to "Previous context:\n$summary")
        return system + listOf(summaryMsg) + recent
    }

    /**
     * Compute cache key for a request.
     */
    private fun computeCacheKey(request: InferenceRequest): String {
        val content = request.messages.joinToString("|") { "${it["role"]}:${it["content"]}" }
        val hash = MessageDigest.getInstance("MD5")
            .digest("$content:${request.model}:${request.maxTokens}:${request.temperature}".toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Get usage statistics.
     */
    fun getStats(): Map<String, Any> {
        val providerStats = providers.mapValues { (_, p) ->
            mapOf(
                "totalRequests" to p.totalRequests.get(),
                "totalFailures" to p.totalFailures.get(),
                "isAvailable" to p.isAvailable,
                "consecutiveFailures" to p.consecutiveFailures
            )
        }
        return mapOf(
            "totalRequests" to totalRequests.get(),
            "totalTokensInput" to totalTokensIn.get(),
            "totalTokensOutput" to totalTokensOut.get(),
            "totalCostMicros" to totalCostMicros.get(),
            "cacheSize" to resultCache.size(),
            "providers" to providerStats,
            "isOnline" to isNetworkAvailable()
        )
    }

    /**
     * Get provider health status.
     */
    fun getProviderHealth(): List<Map<String, Any>> {
        return providers.values.map { p ->
            mapOf(
                "id" to p.id,
                "type" to p.type.name,
                "displayName" to p.displayName,
                "isAvailable" to p.isAvailable,
                "totalRequests" to p.totalRequests.get(),
                "totalFailures" to p.totalFailures.get(),
                "consecutiveFailures" to p.consecutiveFailures,
                "models" to p.models
            )
        }
    }

    /**
     * Clear the result cache.
     */
    fun clearCache() {
        resultCache.evictAll()
    }

    /**
     * Reset a provider's availability (e.g., after reconnection).
     */
    fun resetProvider(providerId: String) {
        providers[providerId]?.let {
            it.isAvailable = true
            it.consecutiveFailures = 0
        }
    }
}
