package com.msaidizi.agent.guardrails

import com.msaidizi.agent.guardrails.AuditTrailManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EscalationManager — Low-Confidence Escalation to Human.
 *
 * When the agent's confidence in its output is low (< 60%), instead of
 * delivering potentially incorrect output, the agent escalates to the user:
 * "I'm not sure about this. Can you double-check?"
 *
 * This prevents:
 * - Wrong financial advice being delivered as fact
 * - Incorrect transaction amounts being recorded
 * - Bad credit recommendations
 * - Hallucinated data reaching the user
 *
 * Escalation flow:
 * 1. Agent produces output with confidence score
 * 2. If confidence < threshold → escalate
 * 3. User reviews, corrects, or approves
 * 4. Corrections are logged for harness improvement
 *
 * All escalations are tracked for model/harness improvement.
 */
@Singleton
class EscalationManager @Inject constructor(
    private val auditTrailManager: AuditTrailManager
) {
    // ─── Configuration ───

    /** Confidence threshold below which we escalate (0.0 - 1.0) */
    val ESCALATION_THRESHOLD = 0.6f

    /** Threshold for critical financial actions — higher bar */
    val FINANCIAL_ESCALATION_THRESHOLD = 0.7f

    /** Categories of output that always get escalated when uncertain */
    val CRITICAL_CATEGORIES = setOf(
        EscalationCategory.FINANCIAL_ADVICE,
        EscalationCategory.CREDIT_RECOMMENDATION,
        EscalationCategory.TAX_CALCULATION,
        EscalationCategory.TRANSACTION_AMOUNT
    )

    // ─── State ───

    private val pendingEscalations = ConcurrentHashMap<String, PendingEscalation>()

    /** History of all escalations for harness improvement */
    private val escalationHistory = mutableListOf<EscalationRecord>()

    private val _escalationEvents = MutableStateFlow<EscalationEvent?>(null)
    val escalationEvents: StateFlow<EscalationEvent?> = _escalationEvents.asStateFlow()

    // ─── Core API ───

    /**
     * Evaluate whether an output should be escalated based on confidence.
     *
     * @param confidence Agent's confidence in the output (0.0 - 1.0)
     * @param category What kind of output this is
     * @param output The actual output text
     * @param context Additional context about the decision
     * @return EscalationRequest if escalation is needed, null if output can be delivered
     */
    fun evaluate(
        confidence: Float,
        category: EscalationCategory,
        output: String,
        context: Map<String, String> = emptyMap()
    ): EscalationRequest? {
        val threshold = if (category in CRITICAL_CATEGORIES) {
            FINANCIAL_ESCALATION_THRESHOLD
        } else {
            ESCALATION_THRESHOLD
        }

        if (confidence >= threshold) return null

        val escalationId = UUID.randomUUID().toString()
        val severity = when {
            confidence < 0.3f -> EscalationSeverity.HIGH
            confidence < 0.5f -> EscalationSeverity.MEDIUM
            else -> EscalationSeverity.LOW
        }

        val prompt = buildEscalationPrompt(category, confidence, output, severity)

        val request = EscalationRequest(
            escalationId = escalationId,
            category = category,
            confidence = confidence,
            threshold = threshold,
            output = output,
            prompt = prompt,
            severity = severity,
            context = context
        )

        // Register pending escalation
        pendingEscalations[escalationId] = PendingEscalation(
            request = request,
            createdAt = System.currentTimeMillis()
        )

        // Audit log
        auditTrailManager.log(
            eventType = AuditEventType.GUARDRAIL_BLOCK,
            actor = "EscalationManager",
            action = "escalation_triggered",
            resource = category.name,
            details = mapOf(
                "escalation_id" to escalationId,
                "confidence" to confidence.toString(),
                "threshold" to threshold.toString(),
                "severity" to severity.name,
                "output_preview" to output.take(100)
            ),
            severity = when (severity) {
                EscalationSeverity.HIGH -> AuditSeverity.CRITICAL
                EscalationSeverity.MEDIUM -> AuditSeverity.HIGH
                EscalationSeverity.LOW -> AuditSeverity.MEDIUM
            }
        )

        Timber.w("Escalation triggered: $category (confidence=$confidence, threshold=$threshold, severity=$severity)")
        _escalationEvents.value = EscalationEvent.Triggered(request)

        return request
    }

    /**
     * Process user's response to an escalation.
     * User can: approve as-is, correct the output, or reject entirely.
     */
    suspend fun resolve(
        escalationId: String,
        resolution: EscalationResolution,
        correctedOutput: String? = null,
        userComment: String? = null
    ): EscalationResult {
        val pending = pendingEscalations[escalationId]
            ?: return EscalationResult(
                escalationId = escalationId,
                status = EscalationStatus.NOT_FOUND,
                message = "Escalation not found."
            )

        pendingEscalations.remove(escalationId)

        val finalOutput = when (resolution) {
            EscalationResolution.APPROVED -> pending.request.output
            EscalationResolution.CORRECTED -> correctedOutput ?: pending.request.output
            EscalationResolution.REJECTED -> null
        }

        val status = when (resolution) {
            EscalationResolution.APPROVED -> EscalationStatus.APPROVED
            EscalationResolution.CORRECTED -> EscalationStatus.CORRECTED
            EscalationResolution.REJECTED -> EscalationStatus.REJECTED
        }

        // Record for harness improvement
        val record = EscalationRecord(
            escalationId = escalationId,
            category = pending.request.category,
            originalConfidence = pending.request.confidence,
            originalOutput = pending.request.output,
            resolution = resolution,
            correctedOutput = correctedOutput,
            userComment = userComment,
            resolvedAt = System.currentTimeMillis(),
            responseTimeMs = System.currentTimeMillis() - pending.createdAt
        )
        synchronized(escalationHistory) {
            escalationHistory.add(record)
        }

        // Audit log
        auditTrailManager.log(
            eventType = AuditEventType.SYSTEM_EVENT,
            actor = "EscalationManager",
            action = "escalation_resolved",
            resource = pending.request.category.name,
            details = mapOf(
                "escalation_id" to escalationId,
                "resolution" to resolution.name,
                "corrected" to (correctedOutput != null).toString(),
                "user_comment" to (userComment ?: ""),
                "response_time_ms" to record.responseTimeMs.toString()
            ),
            severity = AuditSeverity.INFO
        )

        val message = when (resolution) {
            EscalationResolution.APPROVED -> "Imekaguliwa na kukubaliwa."
            EscalationResolution.CORRECTED -> "Imesahihishwa. Asante!"
            EscalationResolution.REJECTED -> "Imekataliwa."
        }

        _escalationEvents.value = EscalationEvent.Resolved(escalationId, status)

        return EscalationResult(
            escalationId = escalationId,
            status = status,
            finalOutput = finalOutput,
            message = message
        )
    }

    // ─── Analytics for Harness Improvement ───

    /**
     * Get escalation statistics for harness improvement.
     * Tracks:
     * - Which categories escalate most → need better training
     * - Correction patterns → common mistakes to fix
     * - Response times → UX optimization
     * - Approval vs correction vs rejection rates
     */
    fun getEscalationStats(): EscalationStats {
        synchronized(escalationHistory) {
            if (escalationHistory.isEmpty()) {
                return EscalationStats()
            }

            val totalEscalations = escalationHistory.size
            val byCategory = escalationHistory.groupBy { it.category }
            val byResolution = escalationHistory.groupBy { it.resolution }

            val avgResponseTime = escalationHistory.map { it.responseTimeMs }.average()
            val avgConfidence = escalationHistory.map { it.originalConfidence.toDouble() }.average()

            val correctionRate = escalationHistory.count {
                it.resolution == EscalationResolution.CORRECTED
            }.toDouble() / totalEscalations

            val approvalRate = escalationHistory.count {
                it.resolution == EscalationResolution.APPROVED
            }.toDouble() / totalEscalations

            // Most common correction patterns
            val corrections = escalationHistory
                .filter { it.resolution == EscalationResolution.CORRECTED && it.correctedOutput != null }
                .groupBy { it.category }

            return EscalationStats(
                totalEscalations = totalEscalations,
                byCategory = byCategory.mapValues { it.value.size },
                byResolution = byResolution.mapValues { it.value.size },
                avgResponseTimeMs = avgResponseTime.toLong(),
                avgConfidenceAtEscalation = avgConfidence.toFloat(),
                correctionRate = correctionRate,
                approvalRate = approvalRate,
                categoriesNeedingImprovement = byCategory.entries
                    .sortedByDescending { it.value.size }
                    .take(3)
                    .map { it.key }
            )
        }
    }

    /**
     * Get recent escalation records for review.
     */
    fun getRecentEscalations(limit: Int = 20): List<EscalationRecord> {
        synchronized(escalationHistory) {
            return escalationHistory.sortedByDescending { it.resolvedAt }.take(limit)
        }
    }

    /**
     * Get pending escalations.
     */
    fun getPendingEscalations(): List<EscalationRequest> {
        return pendingEscalations.values.map { it.request }
    }

    // ─── Prompt Building ───

    private fun buildEscalationPrompt(
        category: EscalationCategory,
        confidence: Float,
        output: String,
        severity: EscalationSeverity
    ): String {
        val confidencePercent = (confidence * 100).toInt()

        return when (severity) {
            EscalationSeverity.HIGH -> {
                when (category) {
                    EscalationCategory.FINANCIAL_ADVICE ->
                        "⚠️ Siko na uhakika ($confidencePercent%) kuhusu hii nasili ya kifedha. Tafadhali kagua:\n\n$output\n\nJe, ni sahihi?"
                    EscalationCategory.CREDIT_RECOMMENDATION ->
                        "⚠️ Siko na uhakika ($confidencePercent%) kuhusu hii pendekezo la mkopo. Tafadhali hakiki:\n\n$output\n\nJe, unakubali?"
                    EscalationCategory.TRANSACTION_AMOUNT ->
                        "⚠️ Siko na uhakika ($confidencePercent%) kuhusu kiasi. Tafadhali thibitisha:\n\n$output\n\nJe, ni sahihi?"
                    EscalationCategory.TAX_CALCULATION ->
                        "⚠️ Siko na uhakika ($confidencePercent%) kuhusu hesabu ya kodi. Tafadhali kagua:\n\n$output\n\nJe, ni sahihi?"
                    else ->
                        "⚠️ Siko na uhakika ($confidencePercent%) kuhusu hii. Tafadhali kagua:\n\n$output\n\nJe, ni sahihi?"
                }
            }
            EscalationSeverity.MEDIUM -> {
                "🤔 Siwezi kuhakikisha hii (uhakika: $confidencePercent%). Tafadhali kagua:\n\n$output\n\nJe, unakubali au unataka kusahihisha?"
            }
            EscalationSeverity.LOW -> {
                "📋 Tafadhali kagua hii (uhakika: $confidencePercent%):\n\n$output\n\nJe, ni sahihi?"
            }
        }
    }

    // ─── Cleanup ───

    /**
     * Clean up expired escalations (older than 5 minutes).
     */
    fun cleanupExpired() {
        val cutoff = System.currentTimeMillis() - 5 * 60 * 1000
        val expired = pendingEscalations.entries.filter { it.value.createdAt < cutoff }
        for (entry in expired) {
            pendingEscalations.remove(entry.key)
            // Record as unresolved
            synchronized(escalationHistory) {
                escalationHistory.add(
                    EscalationRecord(
                        escalationId = entry.key,
                        category = entry.value.request.category,
                        originalConfidence = entry.value.request.confidence,
                        originalOutput = entry.value.request.output,
                        resolution = EscalationResolution.REJECTED,
                        correctedOutput = null,
                        userComment = "Auto-expired",
                        resolvedAt = System.currentTimeMillis(),
                        responseTimeMs = System.currentTimeMillis() - entry.value.createdAt
                    )
                )
            }
        }
    }
}

// ─── Data Classes ───

enum class EscalationCategory {
    FINANCIAL_ADVICE,        // General financial advice
    CREDIT_RECOMMENDATION,   // Loan/credit product recommendations
    TRANSACTION_AMOUNT,      // Transaction amount verification
    TAX_CALCULATION,         // Tax computation
    REPORT_CONTENT,          // Report content
    CHAMA_DECISION,          // Group financial decisions
    GENERAL                  // Other
}

enum class EscalationSeverity {
    LOW, MEDIUM, HIGH
}

enum class EscalationResolution {
    APPROVED,    // User approves the output as-is
    CORRECTED,   // User provides correction
    REJECTED     // User rejects entirely
}

enum class EscalationStatus {
    APPROVED,
    CORRECTED,
    REJECTED,
    NOT_FOUND
}

data class EscalationRequest(
    val escalationId: String,
    val category: EscalationCategory,
    val confidence: Float,
    val threshold: Float,
    val output: String,
    val prompt: String,
    val severity: EscalationSeverity,
    val context: Map<String, String> = emptyMap()
)

data class EscalationResult(
    val escalationId: String,
    val status: EscalationStatus,
    val finalOutput: String? = null,
    val message: String
)

data class EscalationRecord(
    val escalationId: String,
    val category: EscalationCategory,
    val originalConfidence: Float,
    val originalOutput: String,
    val resolution: EscalationResolution,
    val correctedOutput: String?,
    val userComment: String?,
    val resolvedAt: Long,
    val responseTimeMs: Long
)

data class EscalationStats(
    val totalEscalations: Int = 0,
    val byCategory: Map<EscalationCategory, Int> = emptyMap(),
    val byResolution: Map<EscalationResolution, Int> = emptyMap(),
    val avgResponseTimeMs: Long = 0,
    val avgConfidenceAtEscalation: Float = 0f,
    val correctionRate: Double = 0.0,
    val approvalRate: Double = 0.0,
    val categoriesNeedingImprovement: List<EscalationCategory> = emptyList()
)

sealed class EscalationEvent {
    data class Triggered(val request: EscalationRequest) : EscalationEvent()
    data class Resolved(val escalationId: String, val status: EscalationStatus) : EscalationEvent()
}

internal data class PendingEscalation(
    val request: EscalationRequest,
    val createdAt: Long
)
