package com.msaidizi.agent.guardrails

import com.msaidizi.agent.harness.AssembledContext
import com.msaidizi.agent.harness.IntentType
import com.msaidizi.agent.harness.UserIntent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [HumanApprovalInterceptor].
 *
 * Tests the "Unakubali?" approval workflow:
 * 1. Always-approve intent types (credit, loans, insurance)
 * 2. Approval request creation and resolution
 * 3. Expiry handling
 * 4. Statistics tracking
 * 5. SensitiveActionGuard integration
 */
class HumanApprovalInterceptorTest {

    private lateinit var interceptor: HumanApprovalInterceptor
    private lateinit var auditTrailManager: AuditTrailManager
    private lateinit var context: AssembledContext

    @Before
    fun setup() {
        auditTrailManager = AuditTrailManager()
        interceptor = HumanApprovalInterceptor(auditTrailManager)
        context = AssembledContext()
    }

    // ═══════════════════════════════════════════════════════════════
    //  ALWAYS-APPROVE INTENT TYPES
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `credit check requires approval`() = runTest {
        val intent = UserIntent(
            type = IntentType.CREDIT_CHECK,
            confidence = 0.85f,
            rawText = "angalia credit score"
        )
        val request = interceptor.check(intent, context)
        assertNotNull(request)
        assertEquals(ApprovalCategory.HIGH_STAKES_DECISION, request!!.category)
        assertEquals(RiskLevel.HIGH, request.riskLevel)
        assertTrue(request.promptMessage.contains("credit score"))
    }

    @Test
    fun `loan compare requires approval`() = runTest {
        val intent = UserIntent(
            type = IntentType.LOAN_COMPARE,
            confidence = 0.85f,
            rawText = "linganisha mikopo"
        )
        val request = interceptor.check(intent, context)
        assertNotNull(request)
        assertEquals(ApprovalCategory.HIGH_STAKES_DECISION, request!!.category)
    }

    @Test
    fun `insurance match requires approval`() = runTest {
        val intent = UserIntent(
            type = IntentType.INSURANCE_MATCH,
            confidence = 0.85f,
            rawText = "tafuta bima"
        )
        val request = interceptor.check(intent, context)
        assertNotNull(request)
        assertEquals(ApprovalCategory.HIGH_STAKES_DECISION, request!!.category)
    }

    // ═══════════════════════════════════════════════════════════════
    //  NORMAL OPERATIONS (no approval needed)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `normal sale does not require approval`() = runTest {
        val intent = UserIntent(
            type = IntentType.RECORD_SALE,
            confidence = 0.9f,
            entities = mapOf("amount" to "500"),
            rawText = "nimeuza kwa 500"
        )
        val request = interceptor.check(intent, context)
        assertNull(request)
    }

    @Test
    fun `greeting does not require approval`() = runTest {
        val intent = UserIntent(
            type = IntentType.GREETING,
            confidence = 0.95f,
            rawText = "habari"
        )
        val request = interceptor.check(intent, context)
        assertNull(request)
    }

    @Test
    fun `check stock does not require approval`() = runTest {
        val intent = UserIntent(
            type = IntentType.ASK_STOCK,
            confidence = 0.85f,
            rawText = "stock ikoje"
        )
        val request = interceptor.check(intent, context)
        assertNull(request)
    }

    // ═══════════════════════════════════════════════════════════════
    //  APPROVAL RESOLUTION
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `resolve approval - approved`() = runTest {
        val intent = UserIntent(
            type = IntentType.CREDIT_CHECK,
            confidence = 0.85f,
            rawText = "angalia credit score"
        )
        val request = interceptor.check(intent, context)!!
        assertTrue(interceptor.hasPendingApproval())

        val resolved = interceptor.resolve(request.requestId, approved = true)
        assertTrue(resolved)
        assertFalse(interceptor.hasPendingApproval())

        val stats = interceptor.getApprovalStats()
        assertEquals(1, stats.totalRequests)
        assertEquals(1, stats.approvedCount)
        assertEquals(0, stats.rejectedCount)
    }

    @Test
    fun `resolve approval - rejected`() = runTest {
        val intent = UserIntent(
            type = IntentType.LOAN_COMPARE,
            confidence = 0.85f,
            rawText = "linganisha mikopo"
        )
        val request = interceptor.check(intent, context)!!
        val resolved = interceptor.resolve(request.requestId, approved = false, userNote = "Sio sasa")
        assertTrue(resolved)

        val stats = interceptor.getApprovalStats()
        assertEquals(1, stats.totalRequests)
        assertEquals(0, stats.approvedCount)
        assertEquals(1, stats.rejectedCount)
    }

    @Test
    fun `resolve with wrong ID returns false`() = runTest {
        val intent = UserIntent(
            type = IntentType.CREDIT_CHECK,
            confidence = 0.85f,
            rawText = "angalia credit score"
        )
        interceptor.check(intent, context)!!

        val resolved = interceptor.resolve("wrong_id", approved = true)
        assertFalse(resolved)
        assertTrue(interceptor.hasPendingApproval())

        // Clean up
        interceptor.cancelPending()
    }

    // ═══════════════════════════════════════════════════════════════
    //  SENSITIVE ACTION GUARD INTEGRATION
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `createFromSensitiveResult - large transaction`() = runTest {
        val intent = UserIntent(
            type = IntentType.RECORD_SALE,
            confidence = 0.9f,
            entities = mapOf("amount" to "50000"),
            rawText = "nimeuza kwa 50000"
        )
        val sensitiveResult = SensitiveActionResult(
            decision = SensitiveDecision.REQUIRE_CONFIRMATION,
            reason = "Unakubali kurekodi muamala wa Ksh 50,000?",
            category = SensitiveCategory.LARGE_TRANSACTION,
            metadata = mapOf("amount" to "50000")
        )

        val request = interceptor.createFromSensitiveResult(intent, sensitiveResult)
        assertEquals(ApprovalCategory.LARGE_TRANSACTION, request.category)
        assertEquals(RiskLevel.MEDIUM, request.riskLevel)
        assertTrue(request.promptMessage.contains("50,000"))
    }

    @Test
    fun `createFromSensitiveResult - external communication`() = runTest {
        val intent = UserIntent(
            type = IntentType.WHATSAPP_REPORT,
            confidence = 0.9f,
            rawText = "tuma ripoti whatsapp"
        )
        val sensitiveResult = SensitiveActionResult(
            decision = SensitiveDecision.REQUIRE_CONFIRMATION,
            reason = "Unakubali kutuma ripoti kwa WhatsApp?",
            category = SensitiveCategory.EXTERNAL_COMMUNICATION
        )

        val request = interceptor.createFromSensitiveResult(intent, sensitiveResult)
        assertEquals(ApprovalCategory.EXTERNAL_COMMUNICATION, request.category)
    }

    // ═══════════════════════════════════════════════════════════════
    //  CANCEL AND STATISTICS
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `cancel pending request`() = runTest {
        val intent = UserIntent(
            type = IntentType.CREDIT_CHECK,
            confidence = 0.85f,
            rawText = "angalia credit score"
        )
        interceptor.check(intent, context)
        assertTrue(interceptor.hasPendingApproval())

        interceptor.cancelPending()
        assertFalse(interceptor.hasPendingApproval())
    }

    @Test
    fun `approval rate calculation`() = runTest {
        // Approve 2, reject 1
        val intent = UserIntent(
            type = IntentType.CREDIT_CHECK,
            confidence = 0.85f,
            rawText = "angalia credit score"
        )

        // First - approve
        var request = interceptor.check(intent, context)!!
        interceptor.resolve(request.requestId, approved = true)

        // Second - approve
        request = interceptor.check(intent, context)!!
        interceptor.resolve(request.requestId, approved = true)

        // Third - reject
        request = interceptor.check(intent, context)!!
        interceptor.resolve(request.requestId, approved = false)

        val stats = interceptor.getApprovalStats()
        assertEquals(3, stats.totalRequests)
        assertEquals(2, stats.approvedCount)
        assertEquals(1, stats.rejectedCount)
        assertEquals(2f / 3f, stats.approvalRate, 0.01f)
    }

    @Test
    fun `no pending approval initially`() {
        assertFalse(interceptor.hasPendingApproval())
        assertNull(interceptor.getPendingRequest())
    }

    @Test
    fun `request has unique ID`() = runTest {
        val intent = UserIntent(
            type = IntentType.CREDIT_CHECK,
            confidence = 0.85f,
            rawText = "angalia credit score"
        )
        val request1 = interceptor.check(intent, context)!!
        interceptor.resolve(request1.requestId, approved = true)

        val request2 = interceptor.check(intent, context)!!
        assertNotEquals(request1.requestId, request2.requestId)
        interceptor.cancelPending()
    }
}
