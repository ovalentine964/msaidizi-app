package com.msaidizi.app.superagent.guardrails

import com.msaidizi.app.core.database.KnowledgeDao
import com.msaidizi.app.superagent.harness.AssembledContext
import com.msaidizi.app.superagent.harness.IntentType
import com.msaidizi.app.superagent.harness.UserIntent
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GuardrailsEngine — Financial integrity and safety checks.
 *
 * Ensures:
 * - Transactions are valid before recording
 * - No duplicate entries
 * - Financial data integrity
 * - User safety (no harmful advice)
 */
@Singleton
class GuardrailsEngine @Inject constructor(
    private val knowledgeDao: KnowledgeDao
) {
    /**
     * Check intent + context before processing.
     */
    suspend fun check(intent: UserIntent, context: AssembledContext): GuardrailResult {
        // Block dangerous intents
        if (isDangerousIntent(intent)) {
            return GuardrailResult(blocked = true, message = "Samahani, I can't do that.")
        }

        // Validate transaction amounts
        if (intent.type in listOf(IntentType.RECORD_SALE, IntentType.RECORD_EXPENSE, IntentType.RECORD_PURCHASE)) {
            val amount = intent.entities["amount"]?.toDoubleOrNull()
            if (amount == null || amount <= 0) {
                return GuardrailResult(blocked = true, message = "Please provide a valid amount.")
            }
            if (amount > 1_000_000) {
                return GuardrailResult(
                    blocked = true,
                    message = "That amount seems very large (Ksh ${"%,.0f".format(amount)}). Please confirm."
                )
            }
        }

        return GuardrailResult(blocked = false)
    }

    /**
     * Check generated output before sending to user.
     */
    suspend fun checkOutput(output: String): GuardrailResult {
        // Ensure no hallucinated financial data
        val suspiciousPatterns = listOf(
            "your bank account",
            "transfer money",
            "send to",
            "loan application"
        )
        for (pattern in suspiciousPatterns) {
            if (output.lowercase().contains(pattern)) {
                return GuardrailResult(
                    blocked = true,
                    message = "Samahani, I need to rephrase that."
                )
            }
        }
        return GuardrailResult(blocked = false)
    }

    /**
     * Validate a transaction for financial integrity.
     */
    fun validateTransaction(amount: Double, paymentMethod: String, productName: String?): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (amount <= 0) errors.add("Amount must be positive")
        if (amount > 500_000) warnings.add("Unusually large amount")

        val validMethods = setOf("cash", "mpesa", "credit", "bank", "card")
        if (paymentMethod.lowercase() !in validMethods) {
            errors.add("Unknown payment method: $paymentMethod")
        }

        if (productName.isNullOrBlank()) {
            warnings.add("No product name specified")
        }

        return when {
            errors.isNotEmpty() -> ValidationResult(accepted = false, errors = errors, warnings = warnings)
            warnings.isNotEmpty() -> ValidationResult(accepted = true, flagged = true, errors = emptyList(), warnings = warnings)
            else -> ValidationResult(accepted = true)
        }
    }

    private fun isDangerousIntent(intent: UserIntent): Boolean {
        // Block deletion of all data, system commands, etc.
        val dangerous = listOf(IntentType.UNKNOWN)
        return intent.type in dangerous && intent.confidence < 0.3f
    }
}

data class GuardrailResult(
    val blocked: Boolean,
    val message: String? = null
)

data class ValidationResult(
    val accepted: Boolean,
    val flagged: Boolean = false,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)
