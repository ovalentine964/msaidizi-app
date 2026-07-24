package com.msaidizi.core.model.inference

import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages per-user inference cost budget.
 *
 * Prevents runaway costs by tracking daily LLM inference count and
 * blocking further inference when the budget is exhausted.
 *
 * ## Budget Model
 * - **On-device inference**: Unlimited (no cost, just battery/CPU)
 * - **Cloud API fallback**: Limited to N calls per day per user
 *
 * ## Why This Matters
 * Msaidizi targets users who can't afford expensive data plans. If the app
 * falls back to cloud API (e.g., during model download), unlimited calls
 * would drain the user's airtime. The budget ensures costs stay predictable.
 *
 * @see InferencePipeline for the routing logic
 */
@Singleton
class CostBudgetManager @Inject constructor() {

    companion object {
        /** Maximum cloud API calls per day */
        private const val DAILY_BUDGET = 100

        /** Cost per cloud API call in hypothetical units */
        private const val COST_PER_CALL = 1
    }

    // ── Counters ──
    private val dailyCalls = AtomicInteger(0)
    private val totalCalls = AtomicInteger(0)
    private val dailyResetTime = AtomicLong(0)

    /**
     * Check if the user can afford another inference.
     *
     * @return true if budget allows another call
     */
    fun canAffordInference(): Boolean {
        resetIfNeeded()
        return dailyCalls.get() < DAILY_BUDGET
    }

    /**
     * Record that an inference was made.
     * Call this after each successful LLM inference.
     */
    fun recordInference() {
        resetIfNeeded()
        dailyCalls.incrementAndGet()
        totalCalls.incrementAndGet()
    }

    /** Get remaining daily budget */
    fun getRemainingBudget(): Int {
        resetIfNeeded()
        return (DAILY_BUDGET - dailyCalls.get()).coerceAtLeast(0)
    }

    /** Get total calls made today */
    fun getDailyCalls(): Int {
        resetIfNeeded()
        return dailyCalls.get()
    }

    /** Get total calls ever made */
    fun getTotalCalls(): Int = totalCalls.get()

    /** Get budget status for UI display */
    fun getBudgetStatus(): BudgetStatus {
        resetIfNeeded()
        return BudgetStatus(
            dailyUsed = dailyCalls.get(),
            dailyLimit = DAILY_BUDGET,
            remaining = getRemainingBudget(),
            isExhausted = !canAffordInference()
        )
    }

    /** Reset the daily counter at midnight */
    private fun resetIfNeeded() {
        val now = System.currentTimeMillis()
        val todayStart = now - (now % (24 * 60 * 60 * 1000))

        if (dailyResetTime.get() < todayStart) {
            dailyCalls.set(0)
            dailyResetTime.set(todayStart)
            Timber.d("CostBudgetManager: Daily budget reset")
        }
    }
}

/** Budget status for UI display */
data class BudgetStatus(
    val dailyUsed: Int,
    val dailyLimit: Int,
    val remaining: Int,
    val isExhausted: Boolean
)
