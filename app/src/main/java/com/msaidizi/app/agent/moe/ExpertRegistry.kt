package com.msaidizi.app.agent.moe

/**
 * Expert Registry — Dynamic registration of model experts for MoE routing.
 *
 * Allows runtime registration of new expert models (e.g., when upgrading
 * from Qwen 3.5 0.8B to Qwen3.5-2B, or adding a new vision model).
 *
 * The registry also tracks expert health (failure rates, latency)
 * for intelligent routing decisions.
 */
class ExpertRegistry {

    data class ExpertHealth(
        var totalRequests: Long = 0,
        var totalFailures: Long = 0,
        var avgLatencyMs: Long = 0,
        var lastUsedTimestamp: Long = 0,
        var consecutiveFailures: Int = 0
    ) {
        val successRate: Double
            get() = if (totalRequests == 0L) 1.0
                    else 1.0 - (totalFailures.toDouble() / totalRequests)

        val isHealthy: Boolean
            get() = consecutiveFailures < 3 && successRate > 0.5
    }

    private val health = mutableMapOf<MoERouter.ExpertType, ExpertHealth>()

    fun recordSuccess(type: MoERouter.ExpertType, latencyMs: Long) {
        val h = health.getOrPut(type) { ExpertHealth() }
        h.totalRequests++
        h.consecutiveFailures = 0
        h.avgLatencyMs = (h.avgLatencyMs + latencyMs) / 2
        h.lastUsedTimestamp = System.currentTimeMillis()
    }

    fun recordFailure(type: MoERouter.ExpertType) {
        val h = health.getOrPut(type) { ExpertHealth() }
        h.totalRequests++
        h.totalFailures++
        h.consecutiveFailures++
        h.lastUsedTimestamp = System.currentTimeMillis()
    }

    fun getHealth(type: MoERouter.ExpertType): ExpertHealth =
        health.getOrPut(type) { ExpertHealth() }

    fun isHealthy(type: MoERouter.ExpertType): Boolean =
        getHealth(type).isHealthy

    fun getAllHealth(): Map<MoERouter.ExpertType, ExpertHealth> = health.toMap()
}
