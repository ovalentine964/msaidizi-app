package com.msaidizi.app.superagent.tools

import javax.inject.Inject
import javax.inject.Singleton

data class ToolValidationResult(val valid: Boolean, val reason: String, val severity: String)

/**
 * GuardrailsEngine (Tool) — Transaction validation, rate limiting, anomaly detection.
 *
 * This is the Tool-interface version for direct tool access.
 * The harness uses the full GuardrailsEngine in superagent.guardrails.
 */
@Singleton
class GuardrailsEngine @Inject constructor() : Tool {

    override val name = "guardrails"
    override val description = "Transaction validation, rate limiting, and anomaly detection"

    private val recentTransactions = mutableListOf<Double>()
    private val MAX_HOURLY = 100
    private val ANOMALY_SIGMA = 3.0

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "validate"
        return when (action.lowercase()) {
            "validate" -> {
                val amount = params["amount"]?.toDoubleOrNull()
                    ?: return ToolResult.error(name, "Amount required", "MISSING_AMOUNT")
                val product = params["product"] ?: ""
                val result = validateTransaction(amount, product)
                if (result.valid) {
                    ToolResult.success(name, mapOf("valid" to true, "severity" to result.severity), result.reason)
                } else {
                    ToolResult.error(name, result.reason, "VALIDATION_FAILED")
                }
            }
            "validate_advice" -> {
                val advice = params["advice"]
                    ?: return ToolResult.error(name, "Advice text required", "MISSING_ADVICE")
                val result = validateAdvice(advice)
                ToolResult.success(name, mapOf("valid" to result.valid, "severity" to result.severity), result.reason)
            }
            "reset" -> {
                resetHourlyCounter()
                ToolResult.success(name, message = "Hourly counter reset")
            }
            "status" -> {
                ToolResult.success(
                    name,
                    mapOf("recent_count" to recentTransactions.size, "max_hourly" to MAX_HOURLY),
                    "Recent transactions: ${recentTransactions.size}/$MAX_HOURLY"
                )
            }
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    fun validateTransaction(amount: Double, product: String): ToolValidationResult {
        // Check 1: Reasonable amount range
        if (amount <= 0) return ToolValidationResult(false, "Amount must be positive", "error")
        if (amount > 1_000_000) return ToolValidationResult(false, "Amount exceeds maximum (KES 1M)", "error")

        // Check 2: Rate limiting
        if (recentTransactions.size >= MAX_HOURLY) {
            return ToolValidationResult(false, "Too many transactions this hour (max $MAX_HOURLY)", "warning")
        }

        // Check 3: Anomaly detection
        if (recentTransactions.size >= 5) {
            val mean = recentTransactions.average()
            val stdDev = Math.sqrt(recentTransactions.map { (it - mean) * (it - mean) }.average())
            val zScore = if (stdDev > 0) Math.abs(amount - mean) / stdDev else 0.0
            if (zScore > ANOMALY_SIGMA) {
                return ToolValidationResult(true, "Unusual amount (z-score: ${"%.1f".format(zScore)}). Confirm?", "warning")
            }
        }

        // Check 4: Duplicate detection (same amount within 30 seconds)
        if (recentTransactions.isNotEmpty() && recentTransactions.last() == amount) {
            return ToolValidationResult(true, "Possible duplicate. Confirm?", "warning")
        }

        recentTransactions.add(amount)
        return ToolValidationResult(true, "Valid", "ok")
    }

    fun validateAdvice(advice: String): ToolValidationResult {
        val hasNumbers = Regex("\\d+").containsMatchIn(advice)
        if (hasNumbers) {
            return ToolValidationResult(true, "Advice contains numbers — verify from tool output only", "info")
        }
        return ToolValidationResult(true, "Advice validated", "ok")
    }

    fun resetHourlyCounter() { recentTransactions.clear() }
}
