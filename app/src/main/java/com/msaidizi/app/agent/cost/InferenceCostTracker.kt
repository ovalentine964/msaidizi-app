package com.msaidizi.app.agent.cost

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Inference Cost Tracker — Per-call cost attribution for Msaidizi.
 *
 * Tracks every model call with cost attribution:
 * - On-device calls: $0.00 (free, local inference)
 * - Cloud calls: per-token pricing (input + output)
 * - Backend calls: $0.00 (included in infrastructure)
 *
 * ## Cost Attribution Model
 *
 * Each inference call produces a CostRecord with:
 * - Provider used (on-device, cloud, backend)
 * - Model name
 * - Input/output token counts
 * - Calculated cost in micro-dollars
 * - Task type for per-task cost analysis
 * - User ID for per-user budgeting
 *
 * ## Per-User Monthly Budget
 * Target: $0.013/user/month (80% on-device, 15% cloud reasoning, 5% premium)
 *
 * ## Cost Optimization Insights
 * The tracker provides analytics to identify:
 * - Which task types are most expensive
 * - Which users are over budget
 * - Whether on-device routing is hitting the 80% target
 * - Cost trends over time
 *
 * Based on Swarm 7 research: inference costs collapsed to $0.0001-0.001/1K tokens
 */
class InferenceCostTracker {

    // ── Global Counters ────────────────────────────────────────────

    private val totalCalls = AtomicLong(0)
    private val totalOnDeviceCalls = AtomicLong(0)
    private val totalCloudCalls = AtomicLong(0)
    private val totalBackendCalls = AtomicLong(0)
    private val totalCostMicros = AtomicLong(0)
    private val totalInputTokens = AtomicLong(0)
    private val totalOutputTokens = AtomicLong(0)

    // ── Per-User Tracking ──────────────────────────────────────────

    private val userCosts = ConcurrentHashMap<String, UserCostState>()

    // ── Per-Task Tracking ──────────────────────────────────────────

    private val taskCosts = ConcurrentHashMap<String, TaskCostState>()

    // ── Cost Records (bounded ring buffer) ─────────────────────────

    private val recentRecords = ArrayDeque<CostRecord>(500)
    private val maxRecords = 500

    /**
     * Record an inference call with full cost attribution.
     *
     * @param providerId The provider used (on-device, deepseek-flash, etc.)
     * @param modelId The specific model used
     * @param inputTokens Number of input tokens
     * @param outputTokens Number of output tokens
     * @param costMicros Calculated cost in micro-dollars ($0.000001)
     * @param taskType The task type that triggered this call
     * @param userId The user who triggered this call
     * @param latencyMs How long the call took
     * @param fromCache Whether the result was cached
     */
    fun record(
        providerId: String,
        modelId: String,
        inputTokens: Int,
        outputTokens: Int,
        costMicros: Long,
        taskType: String,
        userId: String = "anonymous",
        latencyMs: Long = 0,
        fromCache: Boolean = false
    ) {
        val record = CostRecord(
            providerId = providerId,
            modelId = modelId,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            costMicros = costMicros,
            taskType = taskType,
            userId = userId,
            latencyMs = latencyMs,
            fromCache = fromCache,
            timestamp = System.currentTimeMillis()
        )

        // Update global counters
        totalCalls.incrementAndGet()
        totalCostMicros.addAndGet(costMicros)
        totalInputTokens.addAndGet(inputTokens.toLong())
        totalOutputTokens.addAndGet(outputTokens.toLong())

        when {
            providerId == "on-device" || providerId == "on-device-vision" ->
                totalOnDeviceCalls.incrementAndGet()
            providerId == "backend" ->
                totalBackendCalls.incrementAndGet()
            else ->
                totalCloudCalls.incrementAndGet()
        }

        // Per-user tracking
        userCosts.getOrPut(userId) { UserCostState(userId) }.record(costMicros)

        // Per-task tracking
        taskCosts.getOrPut(taskType) { TaskCostState(taskType) }.record(costMicros)

        // Ring buffer of recent records
        synchronized(recentRecords) {
            if (recentRecords.size >= maxRecords) recentRecords.removeFirst()
            recentRecords.addLast(record)
        }
    }

    // ── Per-User Analytics ─────────────────────────────────────────

    fun getUserCost(userId: String): UserCostState? = userCosts[userId]

    fun getUserMonthlyCostMicros(userId: String): Long =
        userCosts[userId]?.monthlyCostMicros?.get() ?: 0L

    fun isUserOverBudget(userId: String, monthlyBudgetMicros: Long = 13_000L): Boolean =
        getUserMonthlyCostMicros(userId) >= monthlyBudgetMicros

    fun isUserNearBudget(userId: String, monthlyBudgetMicros: Long = 13_000L, thresholdPct: Float = 0.8f): Boolean =
        getUserMonthlyCostMicros(userId) >= (monthlyBudgetMicros * thresholdPct).toLong()

    // ── Per-Task Analytics ─────────────────────────────────────────

    fun getTaskCost(taskType: String): TaskCostState? = taskCosts[taskType]

    fun getTaskCostBreakdown(): Map<String, Map<String, Any>> {
        return taskCosts.mapValues { (_, state) ->
            mapOf(
                "total_calls" to state.totalCalls.get(),
                "total_cost_micros" to state.totalCostMicros.get(),
                "avg_cost_micros" to if (state.totalCalls.get() > 0)
                    state.totalCostMicros.get() / state.totalCalls.get() else 0
            )
        }
    }

    // ── Global Analytics ───────────────────────────────────────────

    fun getStats(): Map<String, Any> {
        val onDevicePct = if (totalCalls.get() > 0)
            totalOnDeviceCalls.get() * 100.0 / totalCalls.get() else 0.0
        val cloudPct = if (totalCalls.get() > 0)
            totalCloudCalls.get() * 100.0 / totalCalls.get() else 0.0

        return mapOf(
            "total_calls" to totalCalls.get(),
            "on_device_calls" to totalOnDeviceCalls.get(),
            "cloud_calls" to totalCloudCalls.get(),
            "backend_calls" to totalBackendCalls.get(),
            "on_device_pct" to onDevicePct,
            "cloud_pct" to cloudPct,
            "total_cost_micros" to totalCostMicros.get(),
            "total_cost_dollars" to totalCostMicros.get() / 1_000_000.0,
            "total_input_tokens" to totalInputTokens.get(),
            "total_output_tokens" to totalOutputTokens.get(),
            "tracked_users" to userCosts.size,
            "tracked_task_types" to taskCosts.size,
            "recent_records" to recentRecords.size
        )
    }

    fun getRecentRecords(n: Int = 20): List<CostRecord> {
        synchronized(recentRecords) {
            return recentRecords.takeLast(n).toList()
        }
    }

    // ── Reset (for testing) ────────────────────────────────────────

    fun reset() {
        totalCalls.set(0)
        totalOnDeviceCalls.set(0)
        totalCloudCalls.set(0)
        totalBackendCalls.set(0)
        totalCostMicros.set(0)
        totalInputTokens.set(0)
        totalOutputTokens.set(0)
        userCosts.clear()
        taskCosts.clear()
        synchronized(recentRecords) { recentRecords.clear() }
    }
}

// ── Data Classes ────────────────────────────────────────────────

/**
 * Immutable record of a single inference call.
 */
data class CostRecord(
    val providerId: String,
    val modelId: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val costMicros: Long,
    val taskType: String,
    val userId: String,
    val latencyMs: Long,
    val fromCache: Boolean,
    val timestamp: Long
)

/**
 * Per-user cost state — tracks monthly and daily spending.
 */
class UserCostState(val userId: String) {
    val monthlyCostMicros = AtomicLong(0)
    val dailyCostMicros = AtomicLong(0)
    val totalCalls = AtomicLong(0)
    var lastCallTimestamp: Long = 0

    fun record(costMicros: Long) {
        monthlyCostMicros.addAndGet(costMicros)
        dailyCostMicros.addAndGet(costMicros)
        totalCalls.incrementAndGet()
        lastCallTimestamp = System.currentTimeMillis()
    }

    fun resetMonthly() { monthlyCostMicros.set(0) }
    fun resetDaily() { dailyCostMicros.set(0) }
}

/**
 * Per-task cost state — tracks aggregate costs by task type.
 */
class TaskCostState(val taskType: String) {
    val totalCalls = AtomicLong(0)
    val totalCostMicros = AtomicLong(0)

    fun record(costMicros: Long) {
        totalCalls.incrementAndGet()
        totalCostMicros.addAndGet(costMicros)
    }
}
