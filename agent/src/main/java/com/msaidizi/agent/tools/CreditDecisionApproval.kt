package com.msaidizi.agent.tools

import com.msaidizi.agent.guardrails.SensitiveActionGuard
import com.msaidizi.agent.guardrails.SensitiveActionType
import com.msaidizi.agent.guardrails.AuditTrailManager
import com.msaidizi.agent.guardrails.AuditEventType
import com.msaidizi.agent.guardrails.AuditSeverity
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CreditDecisionApproval — Human-in-the-Loop for Credit Decisions (Fix 1).
 *
 * Wraps AlamaScore to require explicit user confirmation before any
 * credit application is submitted.
 *
 * Flow:
 * 1. AlamaScore computes credit score
 * 2. If creditReady → present loan eligibility to user
 * 3. User must explicitly approve before application proceeds
 * 4. No automatic credit applications without human consent
 *
 * Voice prompt: "Based on your business data, you may qualify for KES 10,000
 * loan. Want to proceed?"
 */
@Singleton
class CreditDecisionApproval @Inject constructor(
    private val alamaScore: AlamaScore,
    private val sensitiveActionGuard: SensitiveActionGuard,
    private val auditTrailManager: AuditTrailManager
) {
    // ─── Loan Eligibility Calculation ───

    /**
     * Calculate loan eligibility based on Alama Score.
     * Returns eligibility info without submitting any application.
     */
    suspend fun checkLoanEligibility(): CreditEligibilityResult {
        val scoreResult = alamaScore.calculateScore()

        if (!scoreResult.creditReady) {
            return CreditEligibilityResult(
                eligible = false,
                score = scoreResult.score,
                level = scoreResult.level,
                factors = scoreResult.factors,
                maxLoanAmount = 0.0,
                message = "Alama Score yako ni ${scoreResult.score} (${scoreResult.level}). " +
                    "Inahitaji angalau 500 ili kuomba mkopo. " +
                    "Endelea kurekodi mauzo yako ili kuboresha score."
            )
        }

        // Calculate max loan amount based on score
        val maxLoan = calculateMaxLoan(scoreResult.score)

        // Determine loan products available
        val products = getAvailableProducts(scoreResult.score, maxLoan)

        return CreditEligibilityResult(
            eligible = true,
            score = scoreResult.score,
            level = scoreResult.level,
            factors = scoreResult.factors,
            maxLoanAmount = maxLoan,
            products = products,
            message = buildEligibilityMessage(scoreResult, maxLoan, products)
        )
    }

    /**
     * Present loan eligibility and request human approval.
     * Returns a confirmation request that must be resolved before proceeding.
     */
    suspend fun requestLoanApproval(
        requestedAmount: Double,
        productId: String? = null
    ): LoanApprovalRequest {
        val eligibility = checkLoanEligibility()

        if (!eligibility.eligible) {
            return LoanApprovalRequest(
                confirmationId = null,
                eligible = false,
                message = eligibility.message
            )
        }

        if (requestedAmount > eligibility.maxLoanAmount) {
            return LoanApprovalRequest(
                confirmationId = null,
                eligible = false,
                message = "Kiasi cha KES ${"%,.0f".format(requestedAmount)} kinauzidi kiwango " +
                    "chako cha mkopo: KES ${"%,.0f".format(eligibility.maxLoanAmount)}. " +
                    "Tafadhaliomba kiasi kidogo."
            )
        }

        // Request confirmation via SensitiveActionGuard
        val confirmationRequest = sensitiveActionGuard.requiresConfirmation(
            actionType = SensitiveActionType.CREDIT_DECISION,
            amount = requestedAmount,
            description = "Omba mkopo wa KES ${"%,.0f".format(requestedAmount)} " +
                "(Alama Score: ${eligibility.score})",
            metadata = mapOf(
                "alama_score" to eligibility.score.toString(),
                "max_loan" to eligibility.maxLoanAmount.toString(),
                "product_id" to (productId ?: "default")
            )
        )

        val prompt = confirmationRequest?.prompt
            ?: "Kulingana na data ya biashara yako, unaweza kupata mkopo wa " +
                "KES ${"%,.0f".format(requestedAmount)}. Unataka kuendelea?"

        auditTrailManager.log(
            eventType = AuditEventType.FINANCIAL_TRANSACTION,
            actor = "CreditDecisionApproval",
            action = "loan_approval_requested",
            resource = "credit",
            details = mapOf(
                "requested_amount" to requestedAmount.toString(),
                "max_eligible" to eligibility.maxLoanAmount.toString(),
                "alama_score" to eligibility.score.toString(),
                "confirmation_id" to (confirmationRequest?.confirmationId ?: "auto_rejected")
            ),
            severity = AuditSeverity.HIGH
        )

        return LoanApprovalRequest(
            confirmationId = confirmationRequest?.confirmationId,
            eligible = true,
            message = prompt,
            eligibility = eligibility
        )
    }

    /**
     * Process user's response to loan approval request.
     */
    suspend fun processLoanApproval(
        confirmationId: String,
        approved: Boolean,
        userComment: String? = null
    ): LoanApprovalResult {
        val confirmationResult = sensitiveActionGuard.confirm(confirmationId, approved, userComment)

        auditTrailManager.log(
            eventType = if (approved) AuditEventType.FINANCIAL_TRANSACTION else AuditEventType.GUARDRAIL_BLOCK,
            actor = "CreditDecisionApproval",
            action = if (approved) "loan_approved_by_user" else "loan_rejected_by_user",
            resource = "credit",
            details = mapOf(
                "confirmation_id" to confirmationId,
                "approved" to approved.toString(),
                "user_comment" to (userComment ?: "")
            ),
            severity = AuditSeverity.HIGH
        )

        return LoanApprovalResult(
            approved = approved,
            message = confirmationResult.message,
            confirmationId = confirmationId
        )
    }

    // ─── Helpers ───

    private fun calculateMaxLoan(score: Int): Double {
        // Conservative loan sizing based on score
        return when {
            score >= 750 -> 50_000.0  // Excellent
            score >= 650 -> 25_000.0  // Good
            score >= 550 -> 15_000.0  // Building
            score >= 500 -> 10_000.0  // Minimum viable
            else -> 0.0
        }
    }

    private fun getAvailableProducts(score: Int, maxLoan: Double): List<LoanProduct> {
        val products = mutableListOf<LoanProduct>()
        if (score >= 500) {
            products.add(LoanProduct("biashara_plus", "Biashara Plus", maxLoan, 30, 0.15))
        }
        if (score >= 650) {
            products.add(LoanProduct("growth_loan", "Growth Loan", maxLoan * 2, 90, 0.12))
        }
        if (score >= 750) {
            products.add(LoanProduct("premium_line", "Premium Credit Line", maxLoan * 3, 180, 0.10))
        }
        return products
    }

    private fun buildEligibilityMessage(
        scoreResult: AlamaScoreResult,
        maxLoan: Double,
        products: List<LoanProduct>
    ): String {
        return buildString {
            appendLine("📊 *Alama Score: ${scoreResult.score} (${scoreResult.level})*")
            appendLine()
            appendLine("Kulingana na data ya biashara yako, unaweza kupata mkopo:")
            appendLine("💰 Kiasi cha juu: KES ${"%,.0f".format(maxLoan)}")
            appendLine()
            if (products.isNotEmpty()) {
                appendLine("📦 Bidhaa zinazopatikana:")
                products.forEach { p ->
                    appendLine("  • ${p.name}: KES ${"%,.0f".format(p.maxAmount)} (${p.termDays} siku, ${"%.0f".format(p.interestRate * 100)}%)")
                }
            }
            appendLine()
            appendLine("Tafadhali kagua na kubali kabla ya kuendelea.")
        }
    }
}

// ─── Data Classes ───

data class CreditEligibilityResult(
    val eligible: Boolean,
    val score: Int,
    val level: String,
    val factors: List<String>,
    val maxLoanAmount: Double,
    val products: List<LoanProduct> = emptyList(),
    val message: String
)

data class LoanApprovalRequest(
    val confirmationId: String?,
    val eligible: Boolean,
    val message: String,
    val eligibility: CreditEligibilityResult? = null
)

data class LoanApprovalResult(
    val approved: Boolean,
    val message: String,
    val confirmationId: String
)

data class LoanProduct(
    val id: String,
    val name: String,
    val maxAmount: Double,
    val termDays: Int,
    val interestRate: Double
)
