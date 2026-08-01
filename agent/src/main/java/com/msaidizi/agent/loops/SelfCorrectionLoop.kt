package com.msaidizi.agent.loops

import com.msaidizi.core.database.KnowledgeDao
import com.msaidizi.agent.tools.core.ToolRegistry
import com.msaidizi.agent.tools.core.ToolResult
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
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
class SelfCorrectionLoop @Inject constructor(
    private val knowledgeDao: KnowledgeDao,
    private val gson: Gson
) {

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
     * P1: Memory-augmented Reflexion — when a failure pattern matches a stored
     * reflection, inject that reflection into the retry context.
     * "Last time this tool failed with VALIDATION_ERROR, the fix was to
     *  normalize the amount format."
     *
     * Returns adjusted params, or null to retry with same params.
     */
    @Suppress("RedundantSuspendModifier")
    private suspend fun adjustParamsForRetry(
        toolName: String,
        params: Map<String, String>,
        failedResult: ToolResult,
        attempt: Int
    ): Map<String, String>? {
        val errorCode = failedResult.errorCode ?: return null

        // Check for stored reflections that match this failure pattern
        val storedReflection = findStoredReflection(toolName, errorCode)
        if (storedReflection != null) {
            Timber.d("SelfCorrection [%s]: Found stored reflection for %s: %s",
                toolName, errorCode, storedReflection.suggestion)
            // Apply the stored fix suggestion
            val reflected = applyReflection(params, storedReflection)
            if (reflected != null) return reflected
        }

        return when (errorCode) {
            "VALIDATION_ERROR" -> {
                fixValidationErrors(params, failedResult.message)
            }
            "MISSING_AMOUNT" -> {
                params.toMutableMap().apply {
                    val amount = this["amount"]
                    if (amount != null) {
                        this["amount"] = amount
                            .replace(",", "")
                            .replace(Regex("[^\\d.]"), "")
                    }
                }
            }
            "DB_ERROR" -> null
            "NO_WIFI", "LOW_BATTERY" -> null
            else -> null
        }
    }

    /**
     * P1: Find a stored reflection matching this tool + error code pattern.
     * Reflections are learned from past failures and stored in the knowledge base.
     */
    private suspend fun findStoredReflection(
        toolName: String,
        errorCode: String
    ): StoredReflection? {
        return try {
            val key = "reflection_${toolName}_${errorCode.lowercase()}"
            val entry = knowledgeDao.getEntry("reflections", key) ?: return null
            val data = gson.fromJson(entry.value, Map::class.java) as? Map<*, *> ?: return null
            StoredReflection(
                toolName = toolName,
                errorCode = errorCode,
                suggestion = data["suggestion"]?.toString() ?: return null,
                paramAdjustments = (data["paramAdjustments"] as? Map<*, *>)?.mapKeys { it.key.toString() }
                    ?.mapValues { it.value.toString() } ?: emptyMap(),
                successCount = (data["successCount"] as? Number)?.toInt() ?: 0,
                confidence = entry.confidence
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Apply a stored reflection's parameter adjustments.
     */
    private fun applyReflection(
        params: Map<String, String>,
        reflection: StoredReflection
    ): Map<String, String>? {
        if (reflection.paramAdjustments.isEmpty()) return null
        if (reflection.confidence < 0.5f) return null // Don't apply low-confidence reflections

        val adjusted = params.toMutableMap()
        for ((key, value) in reflection.paramAdjustments) {
            adjusted[key] = value
        }
        return adjusted
    }

    /**
     * P1: Store a reflection after a successful retry.
     * This enables the Reflexion pattern — learning from past failures.
     */
    suspend fun storeReflection(
        toolName: String,
        errorCode: String,
        suggestion: String,
        paramAdjustments: Map<String, String>
    ) {
        try {
            val key = "reflection_${toolName}_${errorCode.lowercase()}"
            val existing = knowledgeDao.getEntry("reflections", key)

            if (existing != null) {
                // Update existing reflection with higher confidence
                val data = gson.fromJson(existing.value, Map::class.java) as? Map<*, *> ?: emptyMap<Any, Any>()
                val currentSuccessCount = (data["successCount"] as? Number)?.toInt() ?: 0
                knowledgeDao.update(existing.copy(
                    value = gson.toJson(mapOf(
                        "suggestion" to suggestion,
                        "paramAdjustments" to paramAdjustments,
                        "successCount" to currentSuccessCount + 1,
                        "lastUsed" to System.currentTimeMillis()
                    )),
                    confidence = (existing.confidence + 0.1f).coerceAtMost(1.0f),
                    usageCount = existing.usageCount + 1,
                    updatedAt = System.currentTimeMillis()
                ))
            } else {
                knowledgeDao.insert(
                    com.msaidizi.core.model.KnowledgeEntity(
                        category = "reflections",
                        key = key,
                        value = gson.toJson(mapOf(
                            "suggestion" to suggestion,
                            "paramAdjustments" to paramAdjustments,
                            "successCount" to 1,
                            "lastUsed" to System.currentTimeMillis()
                        )),
                        confidence = 0.6f,
                        usageCount = 1
                    )
                )
            }
            Timber.d("SelfCorrection: Stored reflection for %s/%s", toolName, errorCode)
        } catch (e: Exception) {
            Timber.w(e, "Failed to store reflection")
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

/**
 * P1: Stored reflection from past failures (Reflexion pattern).
 * When a tool fails, we check if we've seen this exact failure before
 * and apply the learned fix.
 */
data class StoredReflection(
    val toolName: String,
    val errorCode: String,
    val suggestion: String,
    val paramAdjustments: Map<String, String>,
    val successCount: Int,
    val confidence: Float
)
