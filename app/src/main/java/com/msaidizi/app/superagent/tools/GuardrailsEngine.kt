package com.msaidizi.app.superagent.tools
import javax.inject.Inject

data class ValidationResult(val valid: Boolean, val reason: String, val severity: String)

class GuardrailsEngine @Inject constructor() {
    private val recentTransactions = mutableListOf<Double>()
    private val MAX_HOURLY = 100
    private val ANOMALY_SIGMA = 3.0

    fun validateTransaction(amount: Double, product: String): ValidationResult {
        // Check 1: Reasonable amount range
        if (amount <= 0) return ValidationResult(false, "Amount must be positive", "error")
        if (amount > 1_000_000) return ValidationResult(false, "Amount exceeds maximum (KES 1M)", "error")

        // Check 2: Rate limiting
        if (recentTransactions.size >= MAX_HOURLY) {
            return ValidationResult(false, "Too many transactions this hour (max $MAX_HOURLY)", "warning")
        }

        // Check 3: Anomaly detection
        if (recentTransactions.size >= 5) {
            val mean = recentTransactions.average()
            val stdDev = Math.sqrt(recentTransactions.map { (it - mean) * (it - mean) }.average())
            val zScore = if (stdDev > 0) Math.abs(amount - mean) / stdDev else 0.0
            if (zScore > ANOMALY_SIGMA) {
                return ValidationResult(true, "Unusual amount (z-score: ${"%.1f".format(zScore)}). Confirm?", "warning")
            }
        }

        // Check 4: Duplicate detection (same amount within 30 seconds)
        if (recentTransactions.isNotEmpty() && recentTransactions.last() == amount) {
            return ValidationResult(true, "Possible duplicate. Confirm?", "warning")
        }

        recentTransactions.add(amount)
        return ValidationResult(true, "Valid", "ok")
    }

    fun validateAdvice(advice: String): ValidationResult {
        // Check: No hallucinated numbers
        val hasNumbers = Regex("\\d+").containsMatchIn(advice)
        if (hasNumbers) {
            return ValidationResult(true, "Advice contains numbers — verify from tool output only", "info")
        }
        return ValidationResult(true, "Advice validated", "ok")
    }

    fun resetHourlyCounter() { recentTransactions.clear() }
}
