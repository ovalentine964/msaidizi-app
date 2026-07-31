package com.msaidizi.agent.guardrails

import com.msaidizi.agent.tools.core.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HumanApprovalInterceptor — Unified Human-in-the-Loop Integration Layer.
 *
 * Sits between tool execution and response delivery in the SuperagentHarness.
 * Intercepts outputs that require human approval before delivery.
 *
 * Integrates:
 * - SensitiveActionGuard: For financial action confirmation (Fix 2)
 * - EscalationManager: For low-confidence outputs (Fix 3)
 * - CreditDecisionApproval: For loan eligibility (Fix 1)
 * - CFOReportReview: For report delivery (Fix 4)
 * - ChamaApprovalWorkflow: For group decisions (Fix 5)
 *
 * Called by SuperagentHarness.processInput() after OODA loop produces output.
 */
@Singleton
class HumanApprovalInterceptor @Inject constructor(
    private val sensitiveActionGuard: SensitiveActionGuard,
    private val escalationManager: EscalationManager,
    private val auditTrailManager: AuditTrailManager
) {
    /**
     * Intercept a harness response and determine if human approval is needed.
     *
     * @param response The generated response from the OODA loop
     * @param intent The user's intent
     * @param toolResults Results from tool execution
     * @param confidence The OODA loop's confidence in the output
     * @return InterceptionResult indicating whether to deliver, block, or request approval
     */
    suspend fun intercept(
        response: String,
        intent: UserIntent,
        toolResults: List<ToolResult>,
        confidence: Float
    ): InterceptionResult {
        // ── Check 1: Escalation for low confidence ──
        val escalationCategory = mapIntentToEscalationCategory(intent.type)
        val escalationRequest = escalationManager.evaluate(
            confidence = confidence,
            category = escalationCategory,
            output = response,
            context = mapOf(
                "intent" to intent.type.name,
                "raw_input" to intent.rawText
            )
        )

        if (escalationRequest != null) {
            Timber.w("Low confidence (${(confidence * 100).toInt()}%) — escalating to user")
            return InterceptionResult(
                action = InterceptionAction.ESCALATE,
                escalationRequest = escalationRequest,
                message = escalationRequest.prompt,
                originalResponse = response
            )
        }

        // ── Check 2: Sensitive action confirmation ──
        val sensitiveAction = detectSensitiveAction(intent, toolResults)
        if (sensitiveAction != null) {
            val confirmationRequest = sensitiveActionGuard.requiresConfirmation(
                actionType = sensitiveAction.first,
                amount = sensitiveAction.second,
                description = sensitiveAction.third,
                metadata = mapOf("intent" to intent.type.name)
            )

            if (confirmationRequest != null) {
                Timber.d("Sensitive action detected — requesting confirmation")
                return InterceptionResult(
                    action = InterceptionAction.REQUEST_CONFIRMATION,
                    confirmationRequest = confirmationRequest,
                    message = confirmationRequest.prompt,
                    originalResponse = response
                )
            }
        }

        // ── Check 3: Financial output verification ──
        if (containsFinancialData(response) && confidence < 0.8f) {
            val recheck = escalationManager.evaluate(
                confidence = confidence,
                category = EscalationCategory.FINANCIAL_ADVICE,
                output = response
            )
            if (recheck != null) {
                return InterceptionResult(
                    action = InterceptionAction.ESCALATE,
                    escalationRequest = recheck,
                    message = recheck.prompt,
                    originalResponse = response
                )
            }
        }

        // ── No interception needed — deliver directly ──
        return InterceptionResult(
            action = InterceptionAction.DELIVER,
            originalResponse = response
        )
    }

    /**
     * Process user's response to an escalation or confirmation.
     */
    suspend fun processUserResponse(
        interceptionResult: InterceptionResult,
        approved: Boolean,
        correctedOutput: String? = null,
        userComment: String? = null
    ): InterceptionResolution {
        return when (interceptionResult.action) {
            InterceptionAction.ESCALATE -> {
                val escalationId = interceptionResult.escalationRequest?.escalationId
                    ?: return InterceptionResolution(deliver = false, message = "Escalation not found.")

                val resolution = if (approved) {
                    if (correctedOutput != null) EscalationResolution.CORRECTED
                    else EscalationResolution.APPROVED
                } else {
                    EscalationResolution.REJECTED
                }

                val result = escalationManager.resolve(escalationId, resolution, correctedOutput, userComment)

                InterceptionResolution(
                    deliver = result.status != EscalationStatus.REJECTED,
                    finalOutput = result.finalOutput ?: interceptionResult.originalResponse,
                    message = result.message
                )
            }

            InterceptionAction.REQUEST_CONFIRMATION -> {
                val confirmationId = interceptionResult.confirmationRequest?.confirmationId
                    ?: return InterceptionResolution(deliver = false, message = "Confirmation not found.")

                val result = sensitiveActionGuard.confirm(confirmationId, approved, userComment)

                InterceptionResolution(
                    deliver = result.status == ConfirmationStatus.APPROVED,
                    message = result.message
                )
            }

            InterceptionAction.DELIVER -> {
                InterceptionResolution(deliver = true, message = "Already delivered.")
            }
        }
    }

    /**
     * Detect if an intent triggers a sensitive action.
     * Returns (actionType, amount, description) or null.
     */
    private fun detectSensitiveAction(
        intent: UserIntent,
        toolResults: List<ToolResult>
    ): Triple<SensitiveActionType, Double?, String>? {
        return when (intent.type) {
            IntentType.RECORD_SALE -> {
                val amount = intent.entities["amount"]?.toDoubleOrNull()
                if (amount != null && amount > sensitiveActionGuard.LARGE_TRANSACTION_THRESHOLD) {
                    Triple(SensitiveActionType.TRANSACTION, amount, "Rekodi mauzo ya KES ${"%,.0f".format(amount)}")
                } else null
            }

            IntentType.RECORD_EXPENSE -> {
                val amount = intent.entities["amount"]?.toDoubleOrNull()
                if (amount != null && amount > sensitiveActionGuard.LARGE_TRANSACTION_THRESHOLD) {
                    Triple(SensitiveActionType.LARGE_EXPENSE, amount, "Rekodi gharama ya KES ${"%,.0f".format(amount)}")
                } else null
            }

            IntentType.CREDIT_CHECK -> {
                Triple(SensitiveActionType.CREDIT_DECISION, null, "Angalia uwezo wa mkopo")
            }

            IntentType.LOAN_COMPARE -> {
                Triple(SensitiveActionType.LOAN_APPLICATION, null, "Linganisha mikopo")
            }

            IntentType.CHAMA_MANAGE -> {
                // Chama actions go through ChamaApprovalWorkflow separately
                null
            }

            IntentType.DAILY_REPORT,
            IntentType.WEEKLY_REPORT,
            IntentType.MONTHLY_REPORT -> {
                Triple(SensitiveActionType.REPORT_DELIVERY, null, "Tuma ripoti")
            }

            IntentType.WHATSAPP_REPORT -> {
                Triple(SensitiveActionType.REPORT_DELIVERY, null, "Tuma ripoti kupitia WhatsApp")
            }

            else -> null
        }
    }

    /**
     * Map intent type to escalation category.
     */
    private fun mapIntentToEscalationCategory(intentType: IntentType): EscalationCategory {
        return when (intentType) {
            IntentType.ASK_ADVICE -> EscalationCategory.FINANCIAL_ADVICE
            IntentType.CREDIT_CHECK,
            IntentType.LOAN_COMPARE -> EscalationCategory.CREDIT_RECOMMENDATION
            IntentType.RECORD_SALE,
            IntentType.RECORD_EXPENSE,
            IntentType.RECORD_PURCHASE -> EscalationCategory.TRANSACTION_AMOUNT
            IntentType.CHAMA_MANAGE -> EscalationCategory.CHAMA_DECISION
            IntentType.DAILY_REPORT,
            IntentType.WEEKLY_REPORT,
            IntentType.MONTHLY_REPORT -> EscalationCategory.REPORT_CONTENT
            else -> EscalationCategory.GENERAL
        }
    }

    /**
     * Check if response contains financial data that needs verification.
     */
    private fun containsFinancialData(response: String): Boolean {
        val financialPatterns = listOf(
            Regex("""KES\s*[\d,]+"""),
            Regex("""Ksh\s*[\d,]+"""),
            Regex("""\d+\s*(?:shillings?|/=)"""),
            Regex("""profit|loss|revenue|sales|expenses""", RegexOption.IGNORE_CASE)
        )
        return financialPatterns.any { it.containsMatchIn(response) }
    }
}

// ─── Data Classes ───

enum class InterceptionAction {
    DELIVER,                // No approval needed, deliver directly
    ESCALATE,               // Low confidence — ask user to verify
    REQUEST_CONFIRMATION    // Sensitive action — ask user to confirm
}

data class InterceptionResult(
    val action: InterceptionAction,
    val originalResponse: String,
    val escalationRequest: EscalationRequest? = null,
    val confirmationRequest: ConfirmationRequest? = null,
    val message: String? = null
)

data class InterceptionResolution(
    val deliver: Boolean,
    val finalOutput: String? = null,
    val message: String
)
