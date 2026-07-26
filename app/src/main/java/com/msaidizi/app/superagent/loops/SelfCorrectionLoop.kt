package com.msaidizi.app.superagent.loops

import com.msaidizi.app.superagent.tools.ToolRegistry
import com.msaidizi.app.superagent.tools.ToolResult
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SelfCorrectionLoop — Retry and recover from tool execution failures.
 *
 * Implements the Reflexion pattern: when a tool fails, analyze the failure,
 * adjust parameters, and retry. Falls back to alternative tools if all retries
 * exhaust.
 *
 * Failure recovery strategies:
 *   1. Retry with same parameters (transient failures)
 *   2. Retry with adjusted parameters (parameter errors)
 *   3. Fall back to alternative tool (tool unavailable)
 *   4. Return cached/degraded result (graceful degradation)
 *
 * All failures are logged for flywheel learning.
 *
 * Reference: loop_engineering_report.md §3.1 (Reflexion), §4.2 (Circuit Breaker)
 */
@Singleton
class SelfCorrectionLoop @Inject constructor() {

    companion object {
        /** Maximum retry attempts per tool call. */
        const val MAX_RETRIES = 3

        /** Base delay between retries (ms). Doubles each attempt. */
        const val BASE_RETRY_DELAY_MS = 500L

        /** Maximum delay between retries (ms). */
        const val MAX_RETRY_DELAY_MS = 4000L
    }

    /** Track failure history per tool for learning. */
    private val failureHistory = mutableMapOf<String, MutableList<ToolFailure>>()

    /**
     * Execute a tool with self-correction retry logic.
     *
     * If the tool fails, retries up to [MAX_RETRIES] times with exponential
     * backoff. On each retry, logs the failure for flywheel learning.
     *
     * @param toolName    Name of the tool to execute
     * @param params      Parameters to pass to the tool
     * @param toolRegistry Tool registry for execution
     * @return ToolResult from successful execution, or error result after all retries exhausted
     */
    suspend fun executeWithCorrection(
        toolName: String,
        params: Map<String, String>,
        toolRegistry: ToolRegistry
    ): ToolResult {
        var lastResult: ToolResult? = null
        var currentParams = params.toMap()
        var backoffMs = BASE_RETRY_DELAY_MS

        for (attempt in 1..MAX_RETRIES) {
            val result = toolRegistry.execute(toolName, currentParams)

            if (result == null) {
                lastResult = ToolResult.error(toolName, "Tool not found: $toolName", "TOOL_NOT_FOUND")
                recordFailure(toolName, attempt, "TOOL_NOT_FOUND", params)
                break // No point retrying if tool doesn't exist
            }

            if (result.success) {
                if (attempt > 1) {
                    Timber.i("SelfCorrection [%s]: Succeeded on attempt %d/%d",
                        toolName, attempt, MAX_RETRIES)
                }
                return result
            }

            // ── Failure analysis ─────────────────────────────────
            lastResult = result
            recordFailure(toolName, attempt, result.errorCode ?: "UNKNOWN", currentParams)

            Timber.w("SelfCorrection [%s]: Attempt %d/%d failed: %s (%s)",
                toolName, attempt, MAX_RETRIES, result.message, result.errorCode)

            // ── Adjust parameters for retry ──────────────────────
            val adjusted = adjustParamsForRetry(toolName, currentParams, result, attempt)
            if (adjusted != null) {
                currentParams = adjusted
                Timber.d("SelfCorrection [%s]: Adjusted params for retry", toolName)
            }

            // ── Wait before retry (skip on last attempt) ─────────
            if (attempt < MAX_RETRIES) {
                kotlinx.coroutines.delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            }
        }

        // All retries exhausted — try fallback
        val fallback = tryFallback(toolName, params, toolRegistry)
        if (fallback != null) {
            Timber.i("SelfCorrection [%s]: Using fallback result", toolName)
            return fallback
        }

        Timber.w("SelfCorrection [%s]: All %d attempts failed, returning last error",
            toolName, MAX_RETRIES)
        return lastResult ?: ToolResult.error(toolName, "All retries exhausted", "RETRIES_EXHAUSTED")
    }

    /**
     * Analyze failure and adjust parameters for retry.
     *
     * Returns adjusted params, or null to retry with same params.
     */
    private fun adjustParamsForRetry(
        toolName: String,
        params: Map<String, String>,
        failedResult: ToolResult,
        attempt: Int
    ): Map<String, String>? {
        val errorCode = failedResult.errorCode ?: return null

        return when (errorCode) {
            "VALIDATION_ERROR" -> {
                // Parameter validation failed — try fixing obvious issues
                fixValidationErrors(params, failedResult.message)
            }
            "MISSING_AMOUNT" -> {
                // Amount parsing failed — try different format
                params.toMutableMap().apply {
                    val amount = this["amount"]
                    if (amount != null) {
                        // Try removing commas, currency symbols
                        this["amount"] = amount
                            .replace(",", "")
                            .replace(Regex("[^\\d.]"), "")
                    }
                }
            }
            "DB_ERROR" -> {
                // Database error — retry with same params (transient)
                null
            }
            "NO_WIFI", "LOW_BATTERY" -> {
                // External condition — no point retrying immediately
                null
            }
            else -> null // Retry with same params
        }
    }

    /**
     * Attempt to fix validation errors in parameters.
     */
    private fun fixValidationErrors(
        params: Map<String, String>,
        errorMessage: String
    ): Map<String, String>? {
        val fixed = params.toMutableMap()
        var changed = false

        // Fix common validation issues
        if (errorMessage.contains("amount", ignoreCase = true)) {
            fixed["amount"]?.let { amount ->
                val cleaned = amount.replace(",", "").replace(Regex("[^\\d.]"), "")
                if (cleaned != amount) {
                    fixed["amount"] = cleaned
                    changed = true
                }
            }
        }

        if (errorMessage.contains("payment_method", ignoreCase = true)) {
            fixed["payment_method"]?.let { method ->
                val normalized = normalizePaymentMethod(method)
                if (normalized != method) {
                    fixed["payment_method"] = normalized
                    changed = true
                }
            }
        }

        return if (changed) fixed else null
    }

    /**
     * Normalize payment method strings to known values.
     */
    private fun normalizePaymentMethod(method: String): String {
        val lower = method.lowercase().trim()
        return when {
            lower.contains("mpesa") || lower.contains("m-pesa") || lower.contains("m pesa") -> "mpesa"
            lower.contains("cash") || lower.contains("pesa") -> "cash"
            lower.contains("credit") || lower.contains("deni") -> "credit"
            lower.contains("bank") -> "bank"
            lower.contains("card") -> "card"
            else -> method
        }
    }

    /**
     * Try to get a fallback result when all retries are exhausted.
     *
     * For some tools, we can return a degraded but useful result.
     */
    private suspend fun tryFallback(
        toolName: String,
        params: Map<String, String>,
        toolRegistry: ToolRegistry
    ): ToolResult? {
        // For stock checks, return a "check manually" message
        if (toolName == "check_stock" || toolName == "inventory_tracker") {
            return ToolResult.success(
                toolName,
                message = "I couldn't check your stock right now. Please check manually and record it."
            )
        }

        // For queries, return a "try again later" message
        if (toolName.startsWith("query_")) {
            return ToolResult.success(
                toolName,
                message = "I couldn't retrieve that data right now. Please try again in a moment."
            )
        }

        // For recording tools, don't return a success — the user needs to know it failed
        if (toolName.startsWith("record_")) {
            return null // Let the error propagate
        }

        return null
    }

    /**
     * Record a tool failure for flywheel learning.
     */
    private fun recordFailure(toolName: String, attempt: Int, errorCode: String, params: Map<String, String>) {
        val failures = failureHistory.getOrPut(toolName) { mutableListOf() }
        failures.add(ToolFailure(
            toolName = toolName,
            attempt = attempt,
            errorCode = errorCode,
            params = params,
            timestamp = System.currentTimeMillis()
        ))

        // Keep only last 50 failures per tool
        while (failures.size > 50) {
            failures.removeAt(0)
        }
    }

    /**
     * Get failure statistics for a tool. Used by FlywheelEngine for learning.
     */
    fun getFailureStats(toolName: String): FailureStats {
        val failures = failureHistory[toolName] ?: emptyList()
        val totalFailures = failures.size
        val errorCodes = failures.groupingBy { it.errorCode }.eachCount()
        val recentFailures = failures.filter {
            System.currentTimeMillis() - it.timestamp < 3600_000L // last hour
        }

        return FailureStats(
            toolName = toolName,
            totalFailures = totalFailures,
            recentFailures = recentFailures.size,
            topErrorCodes = errorCodes.entries.sortedByDescending { it.value }.take(3)
                .associate { it.key to it.value }
        )
    }

    /**
     * Get all tools with high failure rates. Used for circuit breaker decisions.
     */
    fun getHighFailureTools(): List<String> {
        return failureHistory.entries
            .filter { (_, failures) ->
                val recent = failures.filter {
                    System.currentTimeMillis() - it.timestamp < 3600_000L
                }
                recent.size >= 3 // 3+ failures in the last hour
            }
            .map { it.key }
    }

    /**
     * Clear failure history for a tool (e.g., after successful circuit breaker reset).
     */
    fun clearFailures(toolName: String) {
        failureHistory.remove(toolName)
    }
}

// ═══════════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════════

data class ToolFailure(
    val toolName: String,
    val attempt: Int,
    val errorCode: String,
    val params: Map<String, String>,
    val timestamp: Long
)

data class FailureStats(
    val toolName: String,
    val totalFailures: Int,
    val recentFailures: Int,
    val topErrorCodes: Map<String, Int>
)
