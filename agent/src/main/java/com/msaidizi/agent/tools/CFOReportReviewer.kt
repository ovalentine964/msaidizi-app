package com.msaidizi.agent.tools

import com.msaidizi.agent.guardrails.SensitiveActionGuard
import com.msaidizi.agent.guardrails.SensitiveActionType
import com.msaidizi.agent.guardrails.AuditTrailManager
import com.msaidizi.agent.guardrails.AuditEventType
import com.msaidizi.agent.guardrails.AuditSeverity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CFOReportReviewer — Human-in-the-Loop for CFO Report Delivery.
 *
 * Before any CFO report is delivered (via WhatsApp, SMS, or in-app),
 * it must pass through this reviewer:
 * 1. Generate report preview
 * 2. Show preview to user: "Here's your daily report. Send it via WhatsApp?"
 * 3. User can edit/correct before delivery
 * 4. Track corrections for harness improvement
 *
 * This prevents:
 * - Incorrect financial data being sent to stakeholders
 * - Math errors in reports reaching the user
 * - Outdated data being shared
 * - Reports going to wrong recipients
 */
@Singleton
class CFOReportReviewer @Inject constructor(
    private val sensitiveActionGuard: SensitiveActionGuard,
    private val auditTrailManager: AuditTrailManager
) {
    // ─── Pending Reports ───

    private val pendingReports = ConcurrentHashMap<String, PendingReport>()

    /** Correction history for harness improvement */
    private val correctionHistory = mutableListOf<ReportCorrection>()

    // ─── Core API ───

    /**
     * Submit a CFO report for review before delivery.
     * Returns a ReportReviewRequest that the user must approve.
     */
    fun submitForReview(
        reportContent: String,
        reportType: ReportType,
        deliveryChannel: DeliveryChannel,
        recipientInfo: String? = null,
        metadata: Map<String, String> = emptyMap()
    ): ReportReviewRequest {
        val reviewId = UUID.randomUUID().toString()

        val request = ReportReviewRequest(
            reviewId = reviewId,
            reportType = reportType,
            reportContent = reportContent,
            deliveryChannel = deliveryChannel,
            recipientInfo = recipientInfo,
            previewText = buildPreview(reportContent, reportType, deliveryChannel, recipientInfo),
            metadata = metadata
        )

        pendingReports[reviewId] = PendingReport(
            request = request,
            createdAt = System.currentTimeMillis()
        )

        auditTrailManager.log(
            eventType = AuditEventType.SYSTEM_EVENT,
            actor = "CFOReportReviewer",
            action = "report_submitted_for_review",
            resource = reportType.name,
            details = mapOf(
                "review_id" to reviewId,
                "channel" to deliveryChannel.name,
                "recipient" to (recipientInfo ?: "N/A")
            ),
            severity = AuditSeverity.MEDIUM
        )

        Timber.d("CFO report submitted for review: $reviewId (${reportType.name})")
        return request
    }

    /**
     * User approves the report for delivery.
     * Optionally with edits.
     */
    fun approve(
        reviewId: String,
        editedContent: String? = null,
        userComment: String? = null
    ): ReportReviewResult {
        val pending = pendingReports.remove(reviewId)
            ?: return ReportReviewResult(
                reviewId = reviewId,
                status = ReportReviewStatus.NOT_FOUND,
                message = "Ripoti haikupatikana."
            )

        val finalContent = editedContent ?: pending.request.reportContent
        val wasEdited = editedContent != null

        // Track correction if edited
        if (wasEdited) {
            synchronized(correctionHistory) {
                correctionHistory.add(
                    ReportCorrection(
                        reviewId = reviewId,
                        reportType = pending.request.reportType,
                        originalContent = pending.request.reportContent,
                        correctedContent = editedContent!!,
                        userComment = userComment,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }

        auditTrailManager.log(
            eventType = AuditEventType.FINANCIAL_TRANSACTION,
            actor = "CFOReportReviewer",
            action = if (wasEdited) "report_approved_with_edits" else "report_approved",
            resource = pending.request.reportType.name,
            details = mapOf(
                "review_id" to reviewId,
                "edited" to wasEdited.toString(),
                "channel" to pending.request.deliveryChannel.name,
                "user_comment" to (userComment ?: "")
            ),
            severity = AuditSeverity.INFO
        )

        return ReportReviewResult(
            reviewId = reviewId,
            status = ReportReviewStatus.APPROVED,
            finalContent = finalContent,
            message = if (wasEdited) "Ripoti imesahihishwa na kutumwa." else "Ripoti imetumwa.",
            deliveryChannel = pending.request.deliveryChannel,
            recipientInfo = pending.request.recipientInfo
        )
    }

    /**
     * User rejects the report — don't deliver.
     */
    fun reject(
        reviewId: String,
        reason: String? = null
    ): ReportReviewResult {
        val pending = pendingReports.remove(reviewId)
            ?: return ReportReviewResult(
                reviewId = reviewId,
                status = ReportReviewStatus.NOT_FOUND,
                message = "Ripoti haikupatikana."
            )

        auditTrailManager.log(
            eventType = AuditEventType.GUARDRAIL_BLOCK,
            actor = "CFOReportReviewer",
            action = "report_rejected",
            resource = pending.request.reportType.name,
            details = mapOf(
                "review_id" to reviewId,
                "reason" to (reason ?: "No reason given")
            ),
            severity = AuditSeverity.MEDIUM
        )

        return ReportReviewResult(
            reviewId = reviewId,
            status = ReportReviewStatus.REJECTED,
            message = "Ripoti imetupwa. Haitumwa."
        )
    }

    /**
     * Get pending reports awaiting review.
     */
    fun getPendingReports(): List<ReportReviewRequest> {
        return pendingReports.values.map { it.request }
    }

    /**
     * Get correction statistics for harness improvement.
     * Shows which report types get corrected most, common correction patterns.
     */
    fun getCorrectionStats(): ReportCorrectionStats {
        synchronized(correctionHistory) {
            if (correctionHistory.isEmpty()) {
                return ReportCorrectionStats()
            }

            val byType = correctionHistory.groupBy { it.reportType }

            return ReportCorrectionStats(
                totalCorrections = correctionHistory.size,
                byReportType = byType.mapValues { it.value.size },
                recentCorrections = correctionHistory.sortedByDescending { it.timestamp }.take(10)
            )
        }
    }

    // ─── Preview Building ───

    private fun buildPreview(
        content: String,
        reportType: ReportType,
        channel: DeliveryChannel,
        recipient: String?
    ): String {
        val channelName = when (channel) {
            DeliveryChannel.WHATSAPP -> "WhatsApp"
            DeliveryChannel.SMS -> "SMS"
            DeliveryChannel.IN_APP -> "ndani ya app"
            DeliveryChannel.EMAIL -> "barua pepe"
        }

        val recipientStr = if (recipient != null) " kwa $recipient" else ""

        val header = when (reportType) {
            ReportType.DAILY_BRIEFING -> "📊 Ripoti ya Leo"
            ReportType.WEEKLY_REPORT -> "📊 Ripoti ya Wiki"
            ReportType.CASHFLOW_FORECAST -> "📈 Utabiri wa Cash Flow"
            ReportType.SAVINGS_ADVICE -> "💰 Nasili ya Akiba"
            ReportType.PROOF_OF_INCOME -> "📄 Uthibitisho wa Mapato"
            ReportType.CREDIT_READINESS -> "📋 Ripoti ya Mkopo"
        }

        return buildString {
            appendLine("📋 **$header**")
            appendLine("Itatumwa kupitia $channelName$recipientStr")
            appendLine("━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine(content)
            appendLine()
            appendLine("━━━━━━━━━━━━━━━━━━━━")
            appendLine("Unakubali kutuma? (Ndio / Hapana / Sahihisha)")
        }
    }
}

// ─── Data Classes ───

enum class ReportType {
    DAILY_BRIEFING,
    WEEKLY_REPORT,
    CASHFLOW_FORECAST,
    SAVINGS_ADVICE,
    PROOF_OF_INCOME,
    CREDIT_READINESS
}

enum class DeliveryChannel {
    WHATSAPP,
    SMS,
    IN_APP,
    EMAIL
}

enum class ReportReviewStatus {
    APPROVED,
    REJECTED,
    NOT_FOUND
}

data class ReportReviewRequest(
    val reviewId: String,
    val reportType: ReportType,
    val reportContent: String,
    val deliveryChannel: DeliveryChannel,
    val recipientInfo: String?,
    val previewText: String,
    val metadata: Map<String, String> = emptyMap()
)

data class ReportReviewResult(
    val reviewId: String,
    val status: ReportReviewStatus,
    val finalContent: String? = null,
    val message: String,
    val deliveryChannel: DeliveryChannel? = null,
    val recipientInfo: String? = null
)

data class ReportCorrection(
    val reviewId: String,
    val reportType: ReportType,
    val originalContent: String,
    val correctedContent: String,
    val userComment: String?,
    val timestamp: Long
)

data class ReportCorrectionStats(
    val totalCorrections: Int = 0,
    val byReportType: Map<ReportType, Int> = emptyMap(),
    val recentCorrections: List<ReportCorrection> = emptyList()
)

internal data class PendingReport(
    val request: ReportReviewRequest,
    val createdAt: Long
)
