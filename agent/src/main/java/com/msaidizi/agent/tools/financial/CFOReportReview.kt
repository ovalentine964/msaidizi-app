package com.msaidizi.agent.tools.financial

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
 * CFOReportReview — Human Review Before Report Delivery (Fix 4).
 *
 * Before delivering CFO reports (daily briefing, weekly report, etc.),
 * shows a preview to the user for review and correction.
 *
 * Flow:
 * 1. CFOEngine generates report
 * 2. CFOReportReview intercepts and shows preview
 * 3. User can approve, edit, or reject
 * 4. Only approved reports are delivered (via WhatsApp, etc.)
 * 5. Corrections are tracked for harness improvement
 *
 * Voice: "Here's your daily report. Send it via WhatsApp?"
 */
@Singleton
class CFOReportReview @Inject constructor(
    private val cfoEngine: CFOEngine,
    private val sensitiveActionGuard: SensitiveActionGuard,
    private val auditTrailManager: AuditTrailManager
) {
    // ─── Pending Reports ───

    private val pendingReports = ConcurrentHashMap<String, ReviewPendingReport>()

    /** Track corrections for harness improvement */
    private val correctionHistory = mutableListOf<ReviewCorrection>()

    // ─── Core API ───

    /**
     * Generate a report and queue it for user review before delivery.
     *
     * @param reportType Type of report (briefing, cashflow, weekly, savings)
     * @param deliveryChannel Where the report will be sent (whatsapp, sms, etc.)
     * @return ReportReviewRequest with preview and confirmation ID
     */
    suspend fun generateForReview(
        reportType: ReportType,
        deliveryChannel: String = "whatsapp"
    ): ReviewRequest {
        // Generate the report via CFOEngine
        val reportResult = when (reportType) {
            ReportType.DAILY_BRIEFING -> cfoEngine.generateDailyBriefing()
            ReportType.CASHFLOW_FORECAST -> cfoEngine.predictCashFlow()
            ReportType.WEEKLY_REPORT -> cfoEngine.generateWeeklyReport()
            ReportType.SAVINGS_ADVICE -> cfoEngine.getSavingsAdvice()
            ReportType.PROOF_OF_INCOME, ReportType.CREDIT_READINESS -> {
                com.msaidizi.agent.tools.core.ToolResult.error(
                    "cfo_report_review", "Report type $reportType not yet supported", "UNSUPPORTED"
                )
            }
        }

        if (!reportResult.success) {
            return ReviewRequest(
                confirmationId = null,
                reportType = reportType,
                preview = null,
                message = "Samahani, ripoti haikupatikana: ${reportResult.message}",
                deliveryChannel = deliveryChannel
            )
        }

        val reportContent = reportResult.message

        // Request confirmation for delivery via SensitiveActionGuard
        val confirmationRequest = sensitiveActionGuard.requiresConfirmation(
            actionType = SensitiveActionType.REPORT_DELIVERY,
            description = "Tuma ripoti ya ${reportType.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }} kupitia $deliveryChannel",
            metadata = mapOf(
                "report_type" to reportType.name,
                "delivery_channel" to deliveryChannel,
                "report_length" to reportContent.length.toString()
            )
        )

        val confirmationId = confirmationRequest?.confirmationId
            ?: java.util.UUID.randomUUID().toString()

        // Store pending report
        pendingReports[confirmationId] = ReviewPendingReport(
            confirmationId = confirmationId,
            reportType = reportType,
            content = reportContent,
            deliveryChannel = deliveryChannel,
            createdAt = System.currentTimeMillis()
        )

        auditTrailManager.log(
            eventType = AuditEventType.SYSTEM_EVENT,
            actor = "CFOReportReview",
            action = "report_queued_for_review",
            resource = reportType.name,
            details = mapOf(
                "confirmation_id" to confirmationId,
                "delivery_channel" to deliveryChannel,
                "report_length" to reportContent.length.toString()
            ),
            severity = AuditSeverity.MEDIUM
        )

        val prompt = confirmationRequest?.prompt
            ?: "Hii ni ripoti yako ya ${reportType.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }}. Tuma kupitia $deliveryChannel?"

        return ReviewRequest(
            confirmationId = confirmationId,
            reportType = reportType,
            preview = reportContent,
            message = prompt,
            deliveryChannel = deliveryChannel
        )
    }

    /**
     * Process user's response to report review.
     *
     * @param confirmationId The report's confirmation ID
     * @param approved Whether user approves the report
     * @param editedContent User's edited version (if they corrected it)
     * @param userComment Optional user comment
     */
    suspend fun processReview(
        confirmationId: String,
        approved: Boolean,
        editedContent: String? = null,
        userComment: String? = null
    ): ReviewDeliveryResult {
        val pending = pendingReports[confirmationId]
            ?: return ReviewDeliveryResult(
                delivered = false,
                message = "Ripoti haikupatikana au tayari imeshughulikiwa."
            )

        pendingReports.remove(confirmationId)

        // Track correction if user edited
        if (editedContent != null && editedContent != pending.content) {
            val correction = ReviewCorrection(
                reportType = pending.reportType,
                originalContent = pending.content,
                correctedContent = editedContent,
                correctionTimestamp = System.currentTimeMillis()
            )
            synchronized(correctionHistory) {
                correctionHistory.add(correction)
            }

            auditTrailManager.log(
                eventType = AuditEventType.SYSTEM_EVENT,
                actor = "CFOReportReview",
                action = "report_corrected",
                resource = pending.reportType.name,
                details = mapOf(
                    "confirmation_id" to confirmationId,
                    "original_length" to pending.content.length.toString(),
                    "corrected_length" to editedContent.length.toString()
                ),
                severity = AuditSeverity.MEDIUM
            )
        }

        // Confirm via SensitiveActionGuard
        sensitiveActionGuard.confirm(confirmationId, approved, userComment)

        val finalContent = editedContent ?: pending.content

        auditTrailManager.log(
            eventType = if (approved) AuditEventType.SYSTEM_EVENT else AuditEventType.GUARDRAIL_BLOCK,
            actor = "CFOReportReview",
            action = if (approved) "report_approved_for_delivery" else "report_rejected",
            resource = pending.reportType.name,
            details = mapOf(
                "confirmation_id" to confirmationId,
                "approved" to approved.toString(),
                "delivery_channel" to pending.deliveryChannel,
                "was_edited" to (editedContent != null).toString()
            ),
            severity = AuditSeverity.INFO
        )

        return if (approved) {
            ReviewDeliveryResult(
                delivered = true,
                message = "Ripoti imetumwa kupitia ${pending.deliveryChannel}.",
                finalContent = finalContent,
                wasEdited = editedContent != null
            )
        } else {
            ReviewDeliveryResult(
                delivered = false,
                message = "Ripoti haijatuma."
            )
        }
    }

    /**
     * Get correction statistics for harness improvement.
     * Shows which report types get corrected most → need better generation.
     */
    fun getCorrectionStats(): ReviewCorrectionStats {
        synchronized(correctionHistory) {
            if (correctionHistory.isEmpty()) {
                return ReviewCorrectionStats()
            }

            val byType = correctionHistory.groupBy { it.reportType }
            return ReviewCorrectionStats(
                totalCorrections = correctionHistory.size,
                correctionsByType = byType.mapValues { it.value.size },
                mostCorrectedType = byType.entries.maxByOrNull { it.value.size }?.key
            )
        }
    }

    /**
     * Get pending reports awaiting review.
     */
    fun getPendingReports(): List<ReviewRequest> {
        return pendingReports.values.map { pending ->
            ReviewRequest(
                confirmationId = pending.confirmationId,
                reportType = pending.reportType,
                preview = pending.content,
                message = "Ripoti inasubiri ukaguzi.",
                deliveryChannel = pending.deliveryChannel
            )
        }
    }
}

// ─── Data Classes ───

data class ReviewRequest(
    val confirmationId: String?,
    val reportType: ReportType,
    val preview: String?,
    val message: String,
    val deliveryChannel: String = "whatsapp"
)

data class ReviewDeliveryResult(
    val delivered: Boolean,
    val message: String,
    val finalContent: String? = null,
    val wasEdited: Boolean = false
)

data class ReviewCorrection(
    val reportType: ReportType,
    val originalContent: String,
    val correctedContent: String,
    val correctionTimestamp: Long
)

data class ReviewCorrectionStats(
    val totalCorrections: Int = 0,
    val correctionsByType: Map<ReportType, Int> = emptyMap(),
    val mostCorrectedType: ReportType? = null
)

internal data class ReviewPendingReport(
    val confirmationId: String,
    val reportType: ReportType,
    val content: String,
    val deliveryChannel: String,
    val createdAt: Long
)
