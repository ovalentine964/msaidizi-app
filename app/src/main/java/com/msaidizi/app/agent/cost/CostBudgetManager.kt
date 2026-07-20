package com.msaidizi.app.agent.cost

import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages per-user and global cost budgets for inference requests.
 * Extracted from ModelRouter to separate cost/budget concerns from routing logic.
 *
 * Cost model: $0.013/user/month target.
 */
class CostBudgetManager(
    private val monthlyBudgetMicros: Long = 13_000L,   // $0.013 in micro-dollars
    private val dailyBudgetMicros: Long = 433L,         // $0.013/30 per day
    private val alertThresholdPct: Float = 0.8f          // Alert at 80% budget
) {
    private val userMonthlyCost = ConcurrentHashMap<String, AtomicLong>()
    private val userDailyCost = ConcurrentHashMap<String, AtomicLong>()
    private var currentMonth: Int = Calendar.getInstance().get(Calendar.MONTH)
    private var currentDay: Int = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

    data class CostBudgetStatus(
        val userId: String,
        val monthlyUsedMicros: Long,
        val monthlyBudgetMicros: Long,
        val dailyUsedMicros: Long,
        val dailyBudgetMicros: Long,
        val monthlyPctUsed: Float,
        val isOverBudget: Boolean,
        val isNearBudget: Boolean
    )

    data class RequestLogEntry(
        val requestId: String,
        val providerId: String,
        val model: String,
        val taskType: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val latencyMs: Long,
        val fromCache: Boolean,
        val costMicros: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val requestLog = mutableListOf<RequestLogEntry>()
    private val totalTokensIn = AtomicLong(0)
    private val totalTokensOut = AtomicLong(0)
    private val totalCostMicros = AtomicLong(0)

    /**
     * Record usage for a completed inference request.
     */
    fun trackUsage(
        userId: String,
        inputTokens: Int,
        outputTokens: Int,
        costMicros: Long,
        taskType: String
    ) {
        totalTokensIn.addAndGet(inputTokens.toLong())
        totalTokensOut.addAndGet(outputTokens.toLong())
        totalCostMicros.addAndGet(costMicros)

        userMonthlyCost.getOrPut(userId) { AtomicLong(0) }.addAndGet(costMicros)
        userDailyCost.getOrPut(userId) { AtomicLong(0) }.addAndGet(costMicros)

        synchronized(requestLog) {
            requestLog.add(RequestLogEntry(
                requestId = java.util.UUID.randomUUID().toString().take(8),
                providerId = "",
                model = "",
                taskType = taskType,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                latencyMs = 0,
                fromCache = false,
                costMicros = costMicros
            ))
            if (requestLog.size > 500) requestLog.removeAt(0)
        }

        resetCountersIfNeeded()
    }

    /**
     * Check the budget status for a given user.
     */
    fun checkBudget(userId: String): CostBudgetStatus {
        resetCountersIfNeeded()

        val monthlyUsed = userMonthlyCost[userId]?.get() ?: 0L
        val dailyUsed = userDailyCost[userId]?.get() ?: 0L

        return CostBudgetStatus(
            userId = userId,
            monthlyUsedMicros = monthlyUsed,
            monthlyBudgetMicros = monthlyBudgetMicros,
            dailyUsedMicros = dailyUsed,
            dailyBudgetMicros = dailyBudgetMicros,
            monthlyPctUsed = monthlyUsed.toFloat() / monthlyBudgetMicros,
            isOverBudget = monthlyUsed >= monthlyBudgetMicros,
            isNearBudget = monthlyUsed >= (monthlyBudgetMicros * alertThresholdPct).toLong()
        )
    }

    /**
     * Calculate the cost of an inference call in micro-dollars.
     */
    fun calculateCost(costPer1kInput: Double, costPer1kOutput: Double, inputTokens: Int, outputTokens: Int): Long {
        return ((inputTokens * costPer1kInput / 1000.0) +
                (outputTokens * costPer1kOutput / 1000.0) * 1_000_000).toLong()
    }

    /**
     * Get global usage statistics.
     */
    fun getGlobalStats(): Map<String, Any> = mapOf(
        "totalTokensInput" to totalTokensIn.get(),
        "totalTokensOutput" to totalTokensOut.get(),
        "totalCostMicros" to totalCostMicros.get(),
        "totalCostDollars" to totalCostMicros.get() / 1_000_000.0,
        "logSize" to synchronized(requestLog) { requestLog.size }
    )

    private fun resetCountersIfNeeded() {
        val cal = Calendar.getInstance()
        val nowMonth = cal.get(Calendar.MONTH)
        val nowDay = cal.get(Calendar.DAY_OF_YEAR)

        if (nowMonth != currentMonth) {
            currentMonth = nowMonth
            userMonthlyCost.values.forEach { it.set(0) }
        }
        if (nowDay != currentDay) {
            currentDay = nowDay
            userDailyCost.values.forEach { it.set(0) }
        }
    }
}
