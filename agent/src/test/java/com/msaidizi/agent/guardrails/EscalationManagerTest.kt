package com.msaidizi.agent.guardrails

import com.msaidizi.agent.harness.AssembledContext
import com.msaidizi.agent.harness.IntentType
import com.msaidizi.agent.harness.UserIntent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [EscalationManager].
 *
 * Tests all escalation triggers:
 * 1. Confidence collapse
 * 2. Guardrail anomalies
 * 3. Repeated failures
 * 4. User complaints
 * 5. Approval rejections
 * 6. High failure density
 * 7. Success recording (resets counters)
 */
class EscalationManagerTest {

    private lateinit var escalationManager: EscalationManager
    private lateinit var auditTrailManager: AuditTrailManager
    private lateinit var context: AssembledContext

    @Before
    fun setup() {
        auditTrailManager = AuditTrailManager()
        escalationManager = EscalationManager(auditTrailManager)
        context = AssembledContext()
    }

    private fun createIntent(type: IntentType): UserIntent {
        return UserIntent(
            type = type,
            confidence = 0.8f,
            rawText = "test input"
        )
    }

    // ═══════════════════════════════════════════════════════════════
    //  CONFIDENCE COLLAPSE
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `confidence below threshold triggers escalation`() = runTest {
        val intent = createIntent(IntentType.ASK_ADVICE)
        val decision = escalationManager.checkForEscalation(
            intent = intent,
            context = context,
            oodaConfidence = 0.2f
        )
        assertTrue(decision.shouldEscalate)
        assertEquals(EscalationLevel.MODERATE, decision.escalationLevel)
        assertEquals(FailureType.CONFIDENCE_COLLAPSE, decision.failureType)
    }

    @Test
    fun `confidence above threshold does not trigger escalation`() = runTest {
        val intent = createIntent(IntentType.ASK_ADVICE)
        val decision = escalationManager.checkForEscalation(
            intent = intent,
            context = context,
            oodaConfidence = 0.8f
        )
        assertFalse(decision.shouldEscalate)
    }

    @Test
    fun `confidence at threshold does not trigger escalation`() = runTest {
        val intent = createIntent(IntentType.ASK_ADVICE)
        val decision = escalationManager.checkForEscalation(
            intent = intent,
            context = context,
            oodaConfidence = 0.3f
        )
        // 0.3 is NOT < 0.3, so no escalation
        assertFalse(decision.shouldEscalate)
    }

    @Test
    fun `confidence just below threshold triggers escalation`() = runTest {
        val intent = createIntent(IntentType.ASK_ADVICE)
        val decision = escalationManager.checkForEscalation(
            intent = intent,
            context = context,
            oodaConfidence = 0.29f
        )
        assertTrue(decision.shouldEscalate)
    }

    // ═══════════════════════════════════════════════════════════════
    //  GUARDRAIL ANOMALIES
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `high severity guardrail issue triggers escalation after threshold`() = runTest {
        val intent = createIntent(IntentType.ASK_ADVICE)
        // Record enough guardrail failures to hit threshold
        repeat(EscalationManager.CONSECUTIVE_FAILURE_THRESHOLD) {
            escalationManager.checkForEscalation(
                intent = intent,
                context = context,
                guardrailIssues = listOf("[HIGH] Financial claim lacks provenance")
            )
        }
        // The next check should escalate
        val decision = escalationManager.checkForEscalation(
            intent = intent,
            context = context,
            guardrailIssues = listOf("[CRITICAL] Hallucination detected")
        )
        assertTrue(decision.shouldEscalate)
        assertEquals(EscalationLevel.HIGH, decision.escalationLevel)
    }

    @Test
    fun `low severity guardrail issues do not trigger escalation`() = runTest {
        val intent = createIntent(IntentType.ASK_ADVICE)
        val decision = escalationManager.checkForEscalation(
            intent = intent,
            context = context,
            guardrailIssues = listOf("[LOW] Minor plausibility warning")
        )
        assertFalse(decision.shouldEscalate)
    }

    @Test
    fun `no guardrail issues does not trigger escalation`() = runTest {
        val intent = createIntent(IntentType.ASK_ADVICE)
        val decision = escalationManager.checkForEscalation(
            intent = intent,
            context = context,
            guardrailIssues = emptyList()
        )
        assertFalse(decision.shouldEscalate)
    }

    // ═══════════════════════════════════════════════════════════════
    //  REPEATED FAILURES
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `consecutive tool failures trigger escalation`() = runTest {
        val intent = createIntent(IntentType.RECORD_SALE)
        repeat(EscalationManager.CONSECUTIVE_FAILURE_THRESHOLD) {
            escalationManager.recordToolFailure("record_sale", IntentType.RECORD_SALE, "DB error")
        }

        val decision = escalationManager.checkForEscalation(intent, context)
        assertTrue(decision.shouldEscalate)
        assertEquals(EscalationLevel.MODERATE, decision.escalationLevel)
        assertEquals(FailureType.REPEATED_FAILURE, decision.failureType)
    }

    @Test
    fun `tool failures below threshold do not trigger escalation`() = runTest {
        val intent = createIntent(IntentType.RECORD_SALE)
        repeat(EscalationManager.CONSECUTIVE_FAILURE_THRESHOLD - 1) {
            escalationManager.recordToolFailure("record_sale", IntentType.RECORD_SALE, "DB error")
        }

        val decision = escalationManager.checkForEscalation(intent, context)
        assertFalse(decision.shouldEscalate)
    }

    @Test
    fun `success resets consecutive failure count`() = runTest {
        val intent = createIntent(IntentType.RECORD_SALE)

        // Record 2 failures
        repeat(2) {
            escalationManager.recordToolFailure("record_sale", IntentType.RECORD_SALE, "DB error")
        }

        // Record success
        escalationManager.recordSuccess(IntentType.RECORD_SALE)

        // Now 1 more failure shouldn't trigger (count reset to 0, then 1)
        escalationManager.recordToolFailure("record_sale", IntentType.RECORD_SALE, "DB error")

        val decision = escalationManager.checkForEscalation(intent, context)
        assertFalse(decision.shouldEscalate)
    }

    // ═══════════════════════════════════════════════════════════════
    //  USER COMPLAINTS
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `user complaints trigger escalation after threshold`() = runTest {
        val intent = createIntent(IntentType.ASK_ADVICE)

        repeat(EscalationManager.COMPLAINT_THRESHOLD) {
            escalationManager.recordUserComplaint("si sahihi, hii ni mbaya")
        }

        val decision = escalationManager.checkForEscalation(intent, context)
        assertTrue(decision.shouldEscalate)
        assertEquals(EscalationLevel.HIGH, decision.escalationLevel)
        assertEquals(FailureType.USER_COMPLAINT, decision.failureType)
    }

    @Test
    fun `complaint keywords detected correctly`() {
        assertTrue(escalationManager.detectComplaint("si sahihi"))
        assertTrue(escalationManager.detectComplaint("hii ni mbaya sana"))
        assertTrue(escalationManager.detectComplaint("haifanyi kazi"))
        assertTrue(escalationManager.detectComplaint("nimechoka na hii"))
        assertTrue(escalationManager.detectComplaint("there is a problem"))
        assertFalse(escalationManager.detectComplaint("asante sana"))
        assertFalse(escalationManager.detectComplaint("habari yako"))
    }

    @Test
    fun `single complaint does not trigger escalation`() = runTest {
        val intent = createIntent(IntentType.ASK_ADVICE)
        escalationManager.recordUserComplaint("si sahihi")

        val decision = escalationManager.checkForEscalation(intent, context)
        assertFalse(decision.shouldEscalate)
    }

    // ═══════════════════════════════════════════════════════════════
    //  APPROVAL REJECTIONS
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `approval rejections trigger escalation after threshold`() = runTest {
        val intent = createIntent(IntentType.CREDIT_CHECK)

        repeat(EscalationManager.APPROVAL_REJECTION_THRESHOLD) {
            escalationManager.recordApprovalRejection()
        }

        val decision = escalationManager.checkForEscalation(intent, context)
        assertTrue(decision.shouldEscalate)
        assertEquals(EscalationLevel.LOW, decision.escalationLevel)
        assertEquals(FailureType.APPROVAL_REJECTION, decision.failureType)
    }

    @Test
    fun `approval rejections below threshold do not trigger`() = runTest {
        val intent = createIntent(IntentType.CREDIT_CHECK)

        repeat(EscalationManager.APPROVAL_REJECTION_THRESHOLD - 1) {
            escalationManager.recordApprovalRejection()
        }

        val decision = escalationManager.checkForEscalation(intent, context)
        assertFalse(decision.shouldEscalate)
    }

    // ═══════════════════════════════════════════════════════════════
    //  STATUS AND RESET
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `status reflects current state`() = runTest {
        escalationManager.recordToolFailure("record_sale", IntentType.RECORD_SALE, "error")
        escalationManager.recordUserComplaint("mbaya")
        escalationManager.recordApprovalRejection()

        val status = escalationManager.getStatus()
        assertEquals(1, status.consecutiveFailuresByOp[IntentType.RECORD_SALE.name])
        assertEquals(1, status.sessionComplaintCount)
        assertEquals(1, status.approvalRejectionCount)
    }

    @Test
    fun `reset clears all counters`() = runTest {
        escalationManager.recordToolFailure("record_sale", IntentType.RECORD_SALE, "error")
        escalationManager.recordUserComplaint("mbaya")
        escalationManager.recordApprovalRejection()

        escalationManager.resetSession()

        val status = escalationManager.getStatus()
        assertEquals(0, status.consecutiveFailuresByOp.size)
        assertEquals(0, status.sessionComplaintCount)
        assertEquals(0, status.approvalRejectionCount)
        assertEquals(EscalationState.NONE, status.currentState)
    }

    @Test
    fun `no escalation when everything is fine`() = runTest {
        val intent = createIntent(IntentType.RECORD_SALE)
        val decision = escalationManager.checkForEscalation(intent, context)
        assertFalse(decision.shouldEscalate)
        assertEquals("", decision.reason)
    }

    // ═══════════════════════════════════════════════════════════════
    //  ESCALATION LEVELS
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `confidence collapse is moderate escalation`() = runTest {
        val intent = createIntent(IntentType.ASK_ADVICE)
        val decision = escalationManager.checkForEscalation(
            intent = intent,
            context = context,
            oodaConfidence = 0.1f
        )
        assertEquals(EscalationLevel.MODERATE, decision.escalationLevel)
    }

    @Test
    fun `user complaint is high escalation`() = runTest {
        val intent = createIntent(IntentType.ASK_ADVICE)
        repeat(EscalationManager.COMPLAINT_THRESHOLD) {
            escalationManager.recordUserComplaint("shida kubwa")
        }
        val decision = escalationManager.checkForEscalation(intent, context)
        assertEquals(EscalationLevel.HIGH, decision.escalationLevel)
    }

    @Test
    fun `approval rejection is low escalation`() = runTest {
        val intent = createIntent(IntentType.CREDIT_CHECK)
        repeat(EscalationManager.APPROVAL_REJECTION_THRESHOLD) {
            escalationManager.recordApprovalRejection()
        }
        val decision = escalationManager.checkForEscalation(intent, context)
        assertEquals(EscalationLevel.LOW, decision.escalationLevel)
    }
}
