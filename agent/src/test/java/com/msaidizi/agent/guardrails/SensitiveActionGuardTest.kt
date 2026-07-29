package com.msaidizi.agent.guardrails

import com.msaidizi.agent.harness.AssembledContext
import com.msaidizi.agent.harness.IntentType
import com.msaidizi.agent.harness.UserIntent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SensitiveActionGuard].
 *
 * Tests all sensitive action categories:
 * 1. Large financial transactions (>10,000 KES)
 * 2. Critical financial transactions (>500,000 KES)
 * 3. External communications
 * 4. High-stakes financial decisions
 * 5. Bulk operations
 * 6. Normal operations (should pass through)
 */
class SensitiveActionGuardTest {

    private lateinit var guard: SensitiveActionGuard
    private lateinit var auditTrailManager: AuditTrailManager
    private lateinit var context: AssembledContext

    @Before
    fun setup() {
        auditTrailManager = AuditTrailManager()
        guard = SensitiveActionGuard(auditTrailManager)
        context = AssembledContext()
    }

    // ═══════════════════════════════════════════════════════════════
    //  LARGE FINANCIAL TRANSACTIONS
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `small sale amount - ALLOW`() {
        val intent = UserIntent(
            type = IntentType.RECORD_SALE,
            confidence = 0.9f,
            entities = mapOf("amount" to "5000"),
            rawText = "nimeuza kwa 5000"
        )
        val result = guard.check(intent, context)
        assertEquals(SensitiveDecision.ALLOW, result.decision)
    }

    @Test
    fun `sale amount at threshold - REQUIRE_CONFIRMATION`() {
        val intent = UserIntent(
            type = IntentType.RECORD_SALE,
            confidence = 0.9f,
            entities = mapOf("amount" to "10000"),
            rawText = "nimeuza kwa 10000"
        )
        val result = guard.check(intent, context)
        assertEquals(SensitiveDecision.REQUIRE_CONFIRMATION, result.decision)
        assertEquals(SensitiveCategory.LARGE_TRANSACTION, result.category)
        assertTrue(result.reason.contains("Unakubali"))
    }

    @Test
    fun `large expense - REQUIRE_CONFIRMATION`() {
        val intent = UserIntent(
            type = IntentType.RECORD_EXPENSE,
            confidence = 0.9f,
            entities = mapOf("amount" to "50000"),
            rawText = "nimetumia 50000"
        )
        val result = guard.check(intent, context)
        assertEquals(SensitiveDecision.REQUIRE_CONFIRMATION, result.decision)
        assertEquals(SensitiveCategory.LARGE_TRANSACTION, result.category)
    }

    @Test
    fun `critical purchase amount - BLOCK`() {
        val intent = UserIntent(
            type = IntentType.RECORD_PURCHASE,
            confidence = 0.9f,
            entities = mapOf("amount" to "600000"),
            rawText = "nimenunua kwa 600000"
        )
        val result = guard.check(intent, context)
        assertEquals(SensitiveDecision.BLOCK, result.decision)
        assertEquals(SensitiveCategory.LARGE_TRANSACTION, result.category)
        assertTrue(result.reason.contains("pesa nyingi"))
    }

    @Test
    fun `amount from tool params - detected correctly`() {
        val intent = UserIntent(
            type = IntentType.RECORD_SALE,
            confidence = 0.9f,
            toolParams = mapOf("record_sale" to mapOf("amount" to "25000")),
            rawText = "nimeuza bidhaa"
        )
        val result = guard.check(intent, context)
        assertEquals(SensitiveDecision.REQUIRE_CONFIRMATION, result.decision)
    }

    // ═══════════════════════════════════════════════════════════════
    //  EXTERNAL COMMUNICATIONS
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `whatsapp report - REQUIRE_CONFIRMATION`() {
        val intent = UserIntent(
            type = IntentType.WHATSAPP_REPORT,
            confidence = 0.9f,
            rawText = "tuma ripoti whatsapp"
        )
        val result = guard.check(intent, context)
        assertEquals(SensitiveDecision.REQUIRE_CONFIRMATION, result.decision)
        assertEquals(SensitiveCategory.EXTERNAL_COMMUNICATION, result.category)
        assertTrue(result.reason.contains("WhatsApp"))
    }

    // ═══════════════════════════════════════════════════════════════
    //  HIGH-STAKES DECISIONS
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `credit check - REQUIRE_CONFIRMATION`() {
        val intent = UserIntent(
            type = IntentType.CREDIT_CHECK,
            confidence = 0.85f,
            rawText = "angalia credit score"
        )
        val result = guard.check(intent, context)
        assertEquals(SensitiveDecision.REQUIRE_CONFIRMATION, result.decision)
        assertEquals(SensitiveCategory.HIGH_STAKES_DECISION, result.category)
    }

    @Test
    fun `loan compare - REQUIRE_CONFIRMATION`() {
        val intent = UserIntent(
            type = IntentType.LOAN_COMPARE,
            confidence = 0.85f,
            rawText = "linganisha mikopo"
        )
        val result = guard.check(intent, context)
        assertEquals(SensitiveDecision.REQUIRE_CONFIRMATION, result.decision)
        assertEquals(SensitiveCategory.HIGH_STAKES_DECISION, result.category)
    }

    @Test
    fun `insurance match - REQUIRE_CONFIRMATION`() {
        val intent = UserIntent(
            type = IntentType.INSURANCE_MATCH,
            confidence = 0.85f,
            rawText = "tafuta bima"
        )
        val result = guard.check(intent, context)
        assertEquals(SensitiveDecision.REQUIRE_CONFIRMATION, result.decision)
        assertEquals(SensitiveCategory.HIGH_STAKES_DECISION, result.category)
    }

    // ═══════════════════════════════════════════════════════════════
    //  BULK OPERATIONS
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `bulk operation with 3+ tools - REQUIRE_CONFIRMATION`() {
        val intent = UserIntent(
            type = IntentType.DAILY_REPORT,
            confidence = 0.8f,
            requiredTools = listOf("cfo_engine", "inventory_tracker", "customer_insights"),
            rawText = "nipe ripoti kamili"
        )
        val result = guard.check(intent, context)
        assertEquals(SensitiveDecision.REQUIRE_CONFIRMATION, result.decision)
        assertEquals(SensitiveCategory.BULK_OPERATION, result.category)
    }

    @Test
    fun `operation with 2 tools - ALLOW`() {
        val intent = UserIntent(
            type = IntentType.DAILY_REPORT,
            confidence = 0.8f,
            requiredTools = listOf("cfo_engine", "inventory_tracker"),
            rawText = "nipe ripoti"
        )
        val result = guard.check(intent, context)
        assertEquals(SensitiveDecision.ALLOW, result.decision)
    }

    // ═══════════════════════════════════════════════════════════════
    //  NORMAL OPERATIONS (should pass through)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `greeting - ALLOW`() {
        val intent = UserIntent(
            type = IntentType.GREETING,
            confidence = 0.95f,
            rawText = "habari"
        )
        val result = guard.check(intent, context)
        assertEquals(SensitiveDecision.ALLOW, result.decision)
        assertEquals(SensitiveCategory.NONE, result.category)
    }

    @Test
    fun `check stock - ALLOW`() {
        val intent = UserIntent(
            type = IntentType.ASK_STOCK,
            confidence = 0.85f,
            rawText = "stock ikoje"
        )
        val result = guard.check(intent, context)
        assertEquals(SensitiveDecision.ALLOW, result.decision)
    }

    @Test
    fun `ask sales today - ALLOW`() {
        val intent = UserIntent(
            type = IntentType.ASK_SALES_TODAY,
            confidence = 0.85f,
            rawText = "nimepata ngapi leo"
        )
        val result = guard.check(intent, context)
        assertEquals(SensitiveDecision.ALLOW, result.decision)
    }

    // ═══════════════════════════════════════════════════════════════
    //  EDGE CASES
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `no amount in financial intent - ALLOW`() {
        val intent = UserIntent(
            type = IntentType.RECORD_SALE,
            confidence = 0.9f,
            entities = emptyMap(),
            rawText = "nimeuza"
        )
        val result = guard.check(intent, context)
        assertEquals(SensitiveDecision.ALLOW, result.decision)
    }

    @Test
    fun `amount just below threshold - ALLOW`() {
        val intent = UserIntent(
            type = IntentType.RECORD_SALE,
            confidence = 0.9f,
            entities = mapOf("amount" to "9999"),
            rawText = "nimeuza kwa 9999"
        )
        val result = guard.check(intent, context)
        assertEquals(SensitiveDecision.ALLOW, result.decision)
    }

    @Test
    fun `amount just at threshold - REQUIRE_CONFIRMATION`() {
        val intent = UserIntent(
            type = IntentType.RECORD_SALE,
            confidence = 0.9f,
            entities = mapOf("amount" to "10000"),
            rawText = "nimeuza kwa 10000"
        )
        val result = guard.check(intent, context)
        assertEquals(SensitiveDecision.REQUIRE_CONFIRMATION, result.decision)
    }
}
