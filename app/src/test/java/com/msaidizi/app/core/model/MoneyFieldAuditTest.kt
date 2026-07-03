package com.msaidizi.app.core.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests verifying that all monetary fields use Double (not Float).
 *
 * Float has ~7 decimal digits of precision, which causes rounding errors
 * for financial calculations (e.g., 10000.15f → 10000.14990234375).
 * Double has ~15 decimal digits, sufficient for KSh amounts.
 *
 * This test suite serves as a regression guard: if anyone adds a Float
 * field for money, these tests will catch it.
 */
class MoneyFieldAuditTest {

    // ── Transaction ──────────────────────────────────────────────────

    @Test
    fun `Transaction totalAmount is Double`() {
        val field = Transaction::class.java.getDeclaredField("totalAmount")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    @Test
    fun `Transaction unitPrice is Double`() {
        val field = Transaction::class.java.getDeclaredField("unitPrice")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    @Test
    fun `Transaction costBasis is Double`() {
        val field = Transaction::class.java.getDeclaredField("costBasis")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    @Test
    fun `Transaction quantity is Double`() {
        val field = Transaction::class.java.getDeclaredField("quantity")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    // ── LoanRecord ───────────────────────────────────────────────────

    @Test
    fun `LoanRecord amount is Double`() {
        val field = LoanRecord::class.java.getDeclaredField("amount")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    @Test
    fun `LoanRecord interestRate is Double`() {
        val field = LoanRecord::class.java.getDeclaredField("interestRate")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    @Test
    fun `LoanRecord totalDue is Double`() {
        val field = LoanRecord::class.java.getDeclaredField("totalDue")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    @Test
    fun `LoanRecord totalRepaid is Double`() {
        val field = LoanRecord::class.java.getDeclaredField("totalRepaid")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    // ── LoanRepayment ────────────────────────────────────────────────

    @Test
    fun `LoanRepayment amount is Double`() {
        val field = LoanRepayment::class.java.getDeclaredField("amount")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    @Test
    fun `LoanRepayment paidAmount is nullable Double`() {
        val field = LoanRepayment::class.java.getDeclaredField("paidAmount")
        assertEquals(Double::class.java, field.type)
    }

    @Test
    fun `LoanRepayment penalty is Double`() {
        val field = LoanRepayment::class.java.getDeclaredField("penalty")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    // ── GoalRecord ───────────────────────────────────────────────────

    @Test
    fun `GoalRecord targetAmount is Double`() {
        val field = GoalRecord::class.java.getDeclaredField("targetAmount")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    @Test
    fun `GoalRecord currentAmount is Double`() {
        val field = GoalRecord::class.java.getDeclaredField("currentAmount")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    @Test
    fun `GoalRecord weeklyTarget is Double`() {
        val field = GoalRecord::class.java.getDeclaredField("weeklyTarget")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    @Test
    fun `GoalRecord dailyTarget is Double`() {
        val field = GoalRecord::class.java.getDeclaredField("dailyTarget")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    // ── GoalProgressEntry ────────────────────────────────────────────

    @Test
    fun `GoalProgressEntry amount is Double`() {
        val field = GoalProgressEntry::class.java.getDeclaredField("amount")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    // ── TitheRecord ──────────────────────────────────────────────────

    @Test
    fun `TitheRecord amount is Double`() {
        val field = TitheRecord::class.java.getDeclaredField("amount")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    @Test
    fun `TitheRecord incomeAtTime is Double`() {
        val field = TitheRecord::class.java.getDeclaredField("incomeAtTime")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    // ── InventoryItem ────────────────────────────────────────────────

    @Test
    fun `InventoryItem avgCost is Double`() {
        val field = InventoryItem::class.java.getDeclaredField("avgCost")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    @Test
    fun `InventoryItem currentStock is Double`() {
        val field = InventoryItem::class.java.getDeclaredField("currentStock")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    // ── BriefingDeliveryEntity ───────────────────────────────────────

    @Test
    fun `BriefingDeliveryEntity predictedSales is Double`() {
        val field = BriefingDeliveryEntity::class.java.getDeclaredField("predictedSales")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    @Test
    fun `BriefingDeliveryEntity predictedProfit is Double`() {
        val field = BriefingDeliveryEntity::class.java.getDeclaredField("predictedProfit")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    @Test
    fun `BriefingDeliveryEntity actualSales is Double`() {
        val field = BriefingDeliveryEntity::class.java.getDeclaredField("actualSales")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    @Test
    fun `BriefingDeliveryEntity actualProfit is Double`() {
        val field = BriefingDeliveryEntity::class.java.getDeclaredField("actualProfit")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    // ── DailySummary ─────────────────────────────────────────────────

    @Test
    fun `DailySummary totalSales is Double`() {
        val field = DailySummary::class.java.getDeclaredField("totalSales")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    @Test
    fun `DailySummary profit is Double`() {
        val field = DailySummary::class.java.getDeclaredField("profit")
        assertEquals(Double::class.javaPrimitiveType, field.type)
    }

    // ── Float precision regression test ──────────────────────────────

    @Test
    fun `Double does not lose precision for typical KSh amounts`() {
        // These are amounts that Float would mangle
        val amounts = listOf(
            10000.15,
            99999.99,
            123456.78,
            0.10,
            50000.00,
            150.50,
        )

        for (amount in amounts) {
            val asDouble = amount
            val asFloat = amount.toFloat()
            val roundTripped = asFloat.toDouble()

            // Double preserves exact value
            assertEquals("Double should preserve $amount", amount, asDouble, 0.0)

            // Float loses precision for amounts > ~10000
            if (amount > 10000) {
                assertNotEquals(
                    "Float loses precision for $amount: $amount != $roundTripped",
                    amount, roundTripped, 0.001
                )
            }
        }
    }

    @Test
    fun `Double handles cumulative addition without drift`() {
        // Simulate adding 1000 transactions of 150.50 KSh
        var total = 0.0
        repeat(1000) { total += 150.50 }
        assertEquals(150500.0, total, 0.01)

        // Float would drift
        var floatTotal = 0.0f
        repeat(1000) { floatTotal += 150.50f }
        val floatDrift = kotlin.math.abs(floatTotal.toDouble() - 150500.0)
        assertTrue(
            "Float drift should be > 0.01 for 1000 additions: drift=$floatDrift",
            floatDrift > 0.01
        )
    }
}
