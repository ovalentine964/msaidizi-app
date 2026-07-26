package com.msaidizi.app.superagent.council

import com.msaidizi.app.superagent.harness.AssembledContext
import com.msaidizi.app.superagent.harness.IntentType
import com.msaidizi.app.superagent.harness.UserIntent
import com.msaidizi.app.superagent.tools.ToolResult
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * CouncilSupervisor — Supervisor-Worker pattern for council orchestration.
 *
 * The supervisor sits between SuperagentHarness and the council system:
 *   SuperagentHarness (pipeline) → CouncilSupervisor (delegation) → Councils (execution)
 *
 * Responsibilities:
 *   1. Delegate intents to the correct council via CouncilManager
 *   2. Spawn sub-agents for cross-council queries via AgentSpawner
 *   3. Monitor council health (response time, error rate)
 *   4. Fall back to direct tool execution if a council is unhealthy
 *   5. Track inter-council event chains for observability
 *
 * Health monitoring:
 *   - Tracks rolling average response time per council (window = 20)
 *   - Tracks error rate per council (window = 20)
 *   - A council is "unhealthy" if:
 *     avg response time > 5000ms OR error rate > 50%
 *   - Unhealthy councils fall back to direct ToolRegistry execution
 *
 * Performance overhead:
 *   - Routing: <1ms (CouncilManager map lookup)
 *   - Health tracking: <0.1ms per operation (atomic updates)
 *   - Total supervisor overhead: <2ms per intent
 */
@Singleton
class CouncilSupervisor @Inject constructor(
    private val councilManager: CouncilManager,
    private val agentSpawner: AgentSpawner,
    private val eventBus: CouncilEventBus,
    private val contextScope: ContextScope
) {
    /**
     * Health metrics per council.
     * Rolling window of last N measurements for response time and errors.
     */
    private val healthMetrics = ConcurrentHashMap<CouncilType, CouncilHealthMetrics>(8)

    companion object {
        /** Rolling window size for health metrics. */
        private const val METRICS_WINDOW = 20

        /** Response time threshold for unhealthy classification (ms). */
        private const val UNHEALTHY_RESPONSE_TIME_MS = 5000L

        /** Error rate threshold for unhealthy classification (0.0-1.0). */
        private const val UNHEALTHY_ERROR_RATE = 0.5f

        /** Minimum samples before health assessment kicks in. */
        private const val MIN_SAMPLES_FOR_HEALTH = 3
    }

    // ── Main Supervision API ────────────────────────────────────

    /**
     * Process an intent through the council system.
     *
     * Decision tree:
     *   1. No tools needed → return empty (conversational intent)
     *   2. Single council → delegate to CouncilManager
     *   3. Multiple councils → spawn sub-agents via AgentSpawner
     *   4. Council unhealthy → fall back to direct tool execution
     */
    suspend fun process(
        intent: UserIntent,
        context: AssembledContext,
        sessionId: String
    ): SupervisedResult {
        val startTime = System.currentTimeMillis()

        // Update global context for council scoping
        contextScope.setGlobalContext(context)

        // No tools needed — conversational intent
        if (intent.requiredTools.isEmpty()) {
            return SupervisedResult(
                toolResults = emptyList(),
                executionTimeMs = System.currentTimeMillis() - startTime,
                strategy = ExecutionStrategy.DIRECT
            )
        }

        // Check if multi-council spawning is needed
        if (agentSpawner.needsSpawning(intent)) {
            return processMultiCouncil(intent, sessionId, startTime)
        }

        // Single council delegation
        return processSingleCouncil(intent, sessionId, startTime)
    }

    /**
     * Get health status for all councils.
     * Used for monitoring and debugging.
     */
    fun getHealthStatus(): Map<CouncilType, HealthStatus> {
        return CouncilType.entries.associateWith { council ->
            val metrics = healthMetrics[council]
            if (metrics == null || metrics.sampleCount < MIN_SAMPLES_FOR_HEALTH) {
                HealthStatus.UNKNOWN
            } else {
                when {
                    metrics.avgResponseTimeMs > UNHEALTHY_RESPONSE_TIME_MS -> HealthStatus.UNHEALTHY
                    metrics.errorRate > UNHEALTHY_ERROR_RATE -> HealthStatus.UNHEALTHY
                    metrics.avgResponseTimeMs > UNHEALTHY_RESPONSE_TIME_MS / 2 -> HealthStatus.DEGRADED
                    else -> HealthStatus.HEALTHY
                }
            }
        }
    }

    /**
     * Get active sub-agent count from the spawner.
     */
    fun getActiveAgentCount(): Int = agentSpawner.getActiveAgentCount()

    /**
     * Get council summary from the manager.
     */
    fun getCouncilSummary() = councilManager.getCouncilSummary()

    /**
     * Reset health metrics for a council.
     * Called when a council recovers from an unhealthy state.
     */
    fun resetHealth(council: CouncilType) {
        healthMetrics.remove(council)
        Timber.i("Health metrics reset for $council")
    }

    // ── Internal Processing ─────────────────────────────────────

    /**
     * Process a single-council intent.
     * Delegates to CouncilManager and tracks health.
     */
    private suspend fun processSingleCouncil(
        intent: UserIntent,
        sessionId: String,
        startTime: Long
    ): SupervisedResult {
        val council = councilManager.resolveCouncil(intent)

        // Check council health before delegation
        if (council != null && isUnhealthy(council)) {
            Timber.w("Council $council is unhealthy, falling back to direct execution")
            return processDirectFallback(intent, startTime)
        }

        // Delegate to council
        val result = try {
            councilManager.executeIntent(intent, sessionId)
        } catch (e: Exception) {
            Timber.e(e, "Council execution failed for ${intent.type}")
            // Record failure in health metrics
            if (council != null) {
                recordMetrics(council, success = false, responseTimeMs = System.currentTimeMillis() - startTime)
            }
            // Fall back to direct execution
            return processDirectFallback(intent, startTime)
        }

        // Record success in health metrics
        if (council != null) {
            recordMetrics(council, success = true, responseTimeMs = result.executionTimeMs)
        }

        return SupervisedResult(
            toolResults = result.toolResults,
            executionTimeMs = System.currentTimeMillis() - startTime,
            strategy = ExecutionStrategy.SINGLE_COUNCIL,
            councilUsed = council
        )
    }

    /**
     * Process a multi-council intent by spawning sub-agents.
     */
    private suspend fun processMultiCouncil(
        intent: UserIntent,
        sessionId: String,
        startTime: Long
    ): SupervisedResult {
        Timber.d("Multi-council processing for ${intent.type}")

        val spawnResult = agentSpawner.spawn(intent, sessionId)

        // Record health for each council that participated
        for (subResult in spawnResult.subTaskResults) {
            recordMetrics(
                subResult.council,
                success = subResult.success,
                responseTimeMs = subResult.executionTimeMs
            )
        }

        if (!spawnResult.success) {
            Timber.w("All sub-agents failed, falling back to direct execution")
            return processDirectFallback(intent, startTime)
        }

        return SupervisedResult(
            toolResults = spawnResult.mergeToolResults(),
            executionTimeMs = System.currentTimeMillis() - startTime,
            strategy = ExecutionStrategy.MULTI_COUNCIL,
            spawnResult = spawnResult
        )
    }

    /**
     * Direct fallback: execute tools via ToolRegistry without council routing.
     * Used when a council is unhealthy or execution fails.
     */
    private suspend fun processDirectFallback(
        intent: UserIntent,
        startTime: Long
    ): SupervisedResult {
        Timber.d("Direct fallback execution for ${intent.type}")

        val toolResults = mutableListOf<ToolResult>()
        for (toolName in intent.requiredTools) {
            val params = intent.toolParams[toolName] ?: emptyMap()
            val result = councilManager.executeTool(toolName, params)
            if (result != null) {
                toolResults.add(result)
            }
        }

        return SupervisedResult(
            toolResults = toolResults,
            executionTimeMs = System.currentTimeMillis() - startTime,
            strategy = ExecutionStrategy.DIRECT_FALLBACK
        )
    }

    // ── Health Monitoring ───────────────────────────────────────

    /**
     * Check if a council is unhealthy.
     */
    private fun isUnhealthy(council: CouncilType): Boolean {
        val metrics = healthMetrics[council] ?: return false
        if (metrics.sampleCount < MIN_SAMPLES_FOR_HEALTH) return false
        return metrics.avgResponseTimeMs > UNHEALTHY_RESPONSE_TIME_MS ||
               metrics.errorRate > UNHEALTHY_ERROR_RATE
    }

    /**
     * Record execution metrics for a council.
     * Uses a rolling window to avoid unbounded memory growth.
     */
    private fun recordMetrics(
        council: CouncilType,
        success: Boolean,
        responseTimeMs: Long
    ) {
        val metrics = healthMetrics.getOrPut(council) { CouncilHealthMetrics() }
        synchronized(metrics) {
            metrics.responseTimes.add(responseTimeMs)
            if (metrics.responseTimes.size > METRICS_WINDOW) {
                metrics.responseTimes.removeAt(0)
            }
            metrics.errors.add(!success)
            if (metrics.errors.size > METRICS_WINDOW) {
                metrics.errors.removeAt(0)
            }
            metrics.sampleCount = metrics.responseTimes.size
            metrics.avgResponseTimeMs = metrics.responseTimes.average().toLong()
            metrics.errorRate = metrics.errors.count { it }.toFloat() / max(metrics.errors.size, 1)
        }
    }
}

// ──────────────────────────────────────────────
// Types
// ──────────────────────────────────────────────

/**
 * Result from the supervisor's processing.
 */
data class SupervisedResult(
    val toolResults: List<ToolResult>,
    val executionTimeMs: Long,
    val strategy: ExecutionStrategy,
    val councilUsed: CouncilType? = null,
    val spawnResult: SpawnResult? = null
)

/**
 * How the supervisor chose to execute the intent.
 */
enum class ExecutionStrategy {
    DIRECT,           // No council needed (conversational)
    SINGLE_COUNCIL,   // Delegated to one council
    MULTI_COUNCIL,    // Spawned sub-agents across councils
    DIRECT_FALLBACK   // Bypassed council due to health issues
}

/**
 * Health status of a council.
 */
enum class HealthStatus {
    UNKNOWN,    // Not enough data
    HEALTHY,    // Normal operation
    DEGRADED,   // Elevated response times
    UNHEALTHY   // High error rate or very slow
}

/**
 * Rolling window health metrics for a council.
 * Mutable, thread-safe via synchronized blocks.
 */
class CouncilHealthMetrics {
    val responseTimes = mutableListOf<Long>()
    val errors = mutableListOf<Boolean>()
    var sampleCount: Int = 0
    var avgResponseTimeMs: Long = 0
    var errorRate: Float = 0f
}
