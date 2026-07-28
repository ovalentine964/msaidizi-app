package com.msaidizi.agent.guardrails

import com.msaidizi.agent.guardrails.AuditTrailManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SensitiveActionGuard — Human-in-the-Loop for High-Risk Financial Actions.
 *
 * Intercepts sensitive actions and requires explicit user confirmation before execution.
 * Actions requiring confirmation:
 * - Large transactions (> KES 5,000)
 * - Tax filings
 * - Loan applications
 * - Group (chama) contributions and withdrawals
 * - Credit decisions
 *
 * Flow:
 * 1. Action is proposed by the agent
 * 2. SensitiveActionGuard intercepts and generates a confirmation prompt
 * 3. User confirms or rejects via voice/text
 * 4. Action proceeds only on explicit confirmation
 * 5. Timeout (30s default) → action is cancelled
 *
 * All confirmations are logged to the audit trail for accountability.
 */
@Singleton
class SensitiveActionGuard @Inject constructor(
    private val auditTrailManager: AuditTrailManager
) {
    // ─── Configuration ───

    /** Threshold above which transactions require confirmation (KES) */
    val LARGE_TRANSACTION_THRESHOLD = 5_000.0

    /** Default confirmation timeout in milliseconds */
    val DEFAULT_TIMEOUT_MS = 30_000L

    /** Actions that always require confirmation regardless of amount */
    val ALWAYS_SENSITIVE = setOf(
        SensitiveActionType.LOAN_APPLICATION,
        SensitiveActionType.TAX_FILING,
        SensitiveActionType.CHAMA_WITHDRAWAL,
        SensitiveActionType.GROUP_CONTRIBUTION,
        SensitiveActionType.CREDIT_DECISION
    )

    // ─── Pending Confirmations ───

    private val pendingConfirmations = ConcurrentHashMap<String, PendingConfirmation>()

    private val _confirmationEvents = MutableStateFlow<ConfirmationEvent?>(null)
    val confirmationEvents: StateFlow<ConfirmationEvent?> = _confirmationEvents.asStateFlow()

    // ─── Core API ───

    /**
     * Check if an action requires human confirmation.
     * Returns a ConfirmationRequest if confirmation is needed, null if action can proceed.
     */
    fun requiresConfirmation(
        actionType: SensitiveActionType,
        amount: Double? = null,
        description: String,
        metadata: Map<String, String> = emptyMap()
    ): ConfirmationRequest? {
        val needsConfirmation = when {
            // Always-sensitive actions
            actionType in ALWAYS_SENSITIVE -> true
            // Large transaction threshold
            actionType == SensitiveActionType.TRANSACTION && amount != null ->
                amount > LARGE_TRANSACTION_THRESHOLD
            // Any action with amount over threshold
            amount != null && amount > LARGE_TRANSACTION_THRESHOLD -> true
            else -> false
        }

        if (!needsConfirmation) return null

        val confirmationId = UUID.randomUUID().toString()
        val timeoutMs = DEFAULT_TIMEOUT_MS

        val prompt = buildConfirmationPrompt(actionType, amount, description, metadata)

        val request = ConfirmationRequest(
            confirmationId = confirmationId,
            actionType = actionType,
            amount = amount,
            description = description,
            prompt = prompt,
            timeoutMs = timeoutMs,
            metadata = metadata
        )

        // Register pending confirmation
        val pending = PendingConfirmation(
            request = request,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + timeoutMs
        )
        pendingConfirmations[confirmationId] = pending

        // Audit log
        auditTrailManager.log(
            eventType = AuditEventType.GUARDRAIL_BLOCK,
            actor = "SensitiveActionGuard",
            action = "confirmation_requested",
            resource = actionType.name,
            details = mapOf(
                "confirmation_id" to confirmationId,
                "amount" to (amount?.toString() ?: "N/A"),
                "description" to description,
                "timeout_ms" to timeoutMs.toString()
            ),
            severity = AuditSeverity.HIGH
        )

        Timber.d("Confirmation required: $actionType (id=$confirmationId, amount=$amount)")
        _confirmationEvents.value = ConfirmationEvent.Requested(request)

        return request
    }

    /**
     * Process user's confirmation response.
     * Returns true if confirmed, false if rejected or expired.
     */
    suspend fun confirm(
        confirmationId: String,
        approved: Boolean,
        userComment: String? = null
    ): ConfirmationResult {
        val pending = pendingConfirmations[confirmationId]
            ?: return ConfirmationResult(
                confirmationId = confirmationId,
                status = ConfirmationStatus.NOT_FOUND,
                message = "Confirmation request not found or already processed."
            )

        // Check expiry
        if (System.currentTimeMillis() > pending.expiresAt) {
            pendingConfirmations.remove(confirmationId)
            auditTrailManager.log(
                eventType = AuditEventType.GUARDRAIL_BLOCK,
                actor = "SensitiveActionGuard",
                action = "confirmation_expired",
                resource = pending.request.actionType.name,
                details = mapOf("confirmation_id" to confirmationId),
                severity = AuditSeverity.HIGH
            )
            return ConfirmationResult(
                confirmationId = confirmationId,
                status = ConfirmationStatus.EXPIRED,
                message = "Muda umekwisha. Kitendo hiki kimefutwa kwa usalama wako."
            )
        }

        // Process response
        pendingConfirmations.remove(confirmationId)

        val status = if (approved) ConfirmationStatus.APPROVED else ConfirmationStatus.REJECTED
        val message = if (approved) {
            "Imeidhinishwa. Inaendelea..."
        } else {
            "Imekataliwa. Kitendo hakijafanyika."
        }

        // Audit log
        auditTrailManager.log(
            eventType = if (approved) AuditEventType.FINANCIAL_TRANSACTION else AuditEventType.GUARDRAIL_BLOCK,
            actor = "SensitiveActionGuard",
            action = if (approved) "confirmation_approved" else "confirmation_rejected",
            resource = pending.request.actionType.name,
            details = mapOf(
                "confirmation_id" to confirmationId,
                "approved" to approved.toString(),
                "user_comment" to (userComment ?: ""),
                "amount" to (pending.request.amount?.toString() ?: "N/A"),
                "description" to pending.request.description
            ),
            severity = if (approved) AuditSeverity.HIGH else AuditSeverity.INFO
        )

        _confirmationEvents.value = ConfirmationEvent.Resolved(confirmationId, status)

        return ConfirmationResult(
            confirmationId = confirmationId,
            status = status,
            message = message
        )
    }

    /**
     * Cancel a pending confirmation (e.g., user cancelled the action entirely).
     */
    fun cancel(confirmationId: String): Boolean {
        val removed = pendingConfirmations.remove(confirmationId) != null
        if (removed) {
            _confirmationEvents.value = ConfirmationEvent.Cancelled(confirmationId)
        }
        return removed
    }

    /**
     * Get all currently pending confirmations.
     */
    fun getPendingConfirmations(): List<ConfirmationRequest> {
        cleanupExpired()
        return pendingConfirmations.values.map { it.request }
    }

    /**
     * Check if a specific confirmation is still pending.
     */
    fun isPending(confirmationId: String): Boolean {
        val pending = pendingConfirmations[confirmationId] ?: return false
        return System.currentTimeMillis() <= pending.expiresAt
    }

    // ─── Prompt Building ───

    /**
     * Build a user-friendly confirmation prompt in Swahili/English.
     */
    private fun buildConfirmationPrompt(
        actionType: SensitiveActionType,
        amount: Double?,
        description: String,
        metadata: Map<String, String>
    ): String {
        val amountStr = if (amount != null) "KES ${"%,.0f".format(amount)}" else ""

        return when (actionType) {
            SensitiveActionType.TRANSACTION -> {
                if (amount != null && amount > LARGE_TRANSACTION_THRESHOLD) {
                    "Hii ni muamamala mkubwa wa $amountStr. $description. Unakubali?"
                } else {
                    "Unakubali $description?"
                }
            }
            SensitiveActionType.LOAN_APPLICATION -> {
                "Unataka kuomba mkopo wa $amountStr. $description. Unakubali kuomba?"
            }
            SensitiveActionType.CREDIT_DECISION -> {
                "Kulingana na data ya biashara yako, unaweza kupata mkopo wa $amountStr. $description. Unataka kuendelea?"
            }
            SensitiveActionType.TAX_FILING -> {
                "Unataka kuwasilisha ripoti ya kodi. $description. Unakubali?"
            }
            SensitiveActionType.CHAMA_WITHDRAWAL -> {
                "Unataka kutoa $amountStr kutoka chama. $description. Unakubali?"
            }
            SensitiveActionType.GROUP_CONTRIBUTION -> {
                "Unataka kuchangia $amountStr kwenye chama. $description. Unakubali?"
            }
            SensitiveActionType.LARGE_EXPENSE -> {
                "Hii ni gharama kubwa ya $amountStr. $description. Unakubali?"
            }
            SensitiveActionType.REPORT_DELIVERY -> {
                "Unataka kutuma ripoti. $description. Unakubali kutuma?"
            }
        }
    }

    // ─── Cleanup ───

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val expired = pendingConfirmations.entries.filter { it.value.expiresAt < now }
        for (entry in expired) {
            pendingConfirmations.remove(entry.key)
            auditTrailManager.log(
                eventType = AuditEventType.GUARDRAIL_BLOCK,
                actor = "SensitiveActionGuard",
                action = "confirmation_auto_expired",
                resource = entry.value.request.actionType.name,
                details = mapOf("confirmation_id" to entry.key),
                severity = AuditSeverity.MEDIUM
            )
        }
    }
}

// ─── Data Classes ───

enum class SensitiveActionType {
    TRANSACTION,          // General financial transaction
    LOAN_APPLICATION,     // Applying for a loan
    CREDIT_DECISION,      // Credit/loan eligibility decision
    TAX_FILING,           // Tax report submission
    CHAMA_WITHDRAWAL,     // Withdrawing from chama
    GROUP_CONTRIBUTION,   // Contributing to chama/group
    LARGE_EXPENSE,        // Large expense recording
    REPORT_DELIVERY       // Sending reports (WhatsApp, etc.)
}

data class ConfirmationRequest(
    val confirmationId: String,
    val actionType: SensitiveActionType,
    val amount: Double?,
    val description: String,
    val prompt: String,
    val timeoutMs: Long,
    val metadata: Map<String, String> = emptyMap()
)

data class ConfirmationResult(
    val confirmationId: String,
    val status: ConfirmationStatus,
    val message: String
)

enum class ConfirmationStatus {
    APPROVED,
    REJECTED,
    EXPIRED,
    NOT_FOUND
}

sealed class ConfirmationEvent {
    data class Requested(val request: ConfirmationRequest) : ConfirmationEvent()
    data class Resolved(val confirmationId: String, val status: ConfirmationStatus) : ConfirmationEvent()
    data class Cancelled(val confirmationId: String) : ConfirmationEvent()
}

internal data class PendingConfirmation(
    val request: ConfirmationRequest,
    val createdAt: Long,
    val expiresAt: Long
)
