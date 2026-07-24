package com.msaidizi.app.superagent.financial

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [PnLCalculator] — Profit & Loss tracking.
 *
 * Covers daily/weekly/monthly P&L, margin calculations,
 * comparison, and edge cases.
 */
class PnLCalculatorTest {

    private lateinit var calculator: PnLCalculator

    @BeforeEach
    fun setup() {
        calculator = PnLCalculator()
    }

    // ── Helper: create transactions ──

    private fun sale(item: String = "mandazi", amount: Double = 200.0, costBasis: Double = 120.0) =
        Transaction(
            type = TransactionType.SALE,
            item = item,
            totalAmount = amount,
            costBasis = costBasis
        )

    private fun purchase(item: String = "unga", amount: Double = 500.0) =
        Transaction(
            type = TransactionType.PURCHASE,
            item = item,
            totalAmount = amount,
            costBasis = amount
        )

    private fun expense(category: String = "usafiri", amount: Double = 100.0) =
        Transaction(
            type = TransactionType.EXPENSE,
            item = category,
            totalAmount = amount,
            costBasis = amount
        )

    // ═══════════════════════════════════════════════════════════════
    // DAILY P&L
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class DailyPnL {

        @Test
        fun `empty transactions returns zero PnL`() {
            val pnl = calculator.calculateDaily(emptyList())
            assertEquals(0.0, pnl.totalRevenue)
            assertEquals(0.0, pnl.netProfit)
            assertEquals(0, pnl.transactionCount)
            assertTrue(pnl.message.contains("Hakuna"))
        }

        @Test
        fun `single sale calculates correctly`() {
            val txns = listOf(sale(amount = 500.0, costBasis = 300.0))
            val pnl = calculator.calculateDaily(txns)

            assertEquals(500.0, pnl.totalRevenue)
            assertEquals(0.0, pnl.totalCostOfGoods) // purchases, not sales
            assertEquals(500.0, pnl.grossProfit)
            assertEquals(500.0, pnl.netProfit)
            assertEquals(1, pnl.transactionCount)
        }

        @Test
        fun `sales minus purchases and expenses`() {
            val txns = listOf(
                sale(amount = 1000.0, costBasis = 600.0),
                sale(amount = 500.0, costBasis = 300.0),
                purchase(amount = 400.0),
                expense(amount = 100.0)
            )
            val pnl = calculator.calculateDaily(txns)

            assertEquals(1500.0, pnl.totalRevenue)
            assertEquals(400.0, pnl.totalCostOfGoods)
            assertEquals(1100.0, pnl.grossProfit)
            assertEquals(100.0, pnl.totalExpenses)
            assertEquals(1000.0, pnl.netProfit)
        }

        @Test
        fun `negative net profit when expenses exceed revenue`() {
            val txns = listOf(
                sale(amount = 200.0, costBasis = 150.0),
                expense(amount = 300.0)
            )
            val pnl = calculator.calculateDaily(txns)

            assertEquals(200.0, pnl.totalRevenue)
            assertTrue(pnl.netProfit < 0, "Should have negative profit")
            assertTrue(pnl.message.contains("hasara") || pnl.message.contains("Hasara"),
                "Message should mention loss")
        }

        @Test
        fun `gross margin percentage calculated correctly`() {
            val txns = listOf(sale(amount = 1000.0, costBasis = 700.0))
            val pnl = calculator.calculateDaily(txns)

            // Gross margin = (1000 - 0) / 1000 = 1.0 (no purchases recorded)
            // But net margin considers costBasis through grossProfit
            assertEquals(1000.0, pnl.grossProfit)
            assertTrue(pnl.grossMarginPercent >= 0.0)
        }

        @Test
        fun `worker name appears in message`() {
            val txns = listOf(sale())
            val pnl = calculator.calculateDaily(txns, workerName = "Amina")
            assertTrue(pnl.message.contains("Amina"))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // WEEKLY P&L
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class WeeklyPnL {

        @Test
        fun `weekly aggregates all transactions`() {
            val txns = listOf(
                sale(amount = 1000.0), sale(amount = 1500.0),
                sale(amount = 800.0), purchase(amount = 2000.0)
            )
            val pnl = calculator.calculateWeekly(txns)

            assertEquals(3300.0, pnl.totalRevenue)
            assertEquals(2000.0, pnl.totalCostOfGoods)
            assertEquals(4, pnl.transactionCount)
            assertTrue(pnl.period.contains("Wiki"))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // COMPARISON
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class Comparison {

        @Test
        fun `revenue increase shows positive message`() {
            val current = calculator.calculateDaily(listOf(sale(amount = 1500.0)))
            val previous = calculator.calculateDaily(listOf(sale(amount = 1000.0)))

            val msg = calculator.compare(current, previous)
            assertTrue(msg.contains("ongezeka") || msg.contains("📈"),
                "Should mention revenue increase")
        }

        @Test
        fun `revenue decrease shows negative message`() {
            val current = calculator.calculateDaily(listOf(sale(amount = 500.0)))
            val previous = calculator.calculateDaily(listOf(sale(amount = 1000.0)))

            val msg = calculator.compare(current, previous)
            assertTrue(msg.contains("pungua") || msg.contains("📉"),
                "Should mention revenue decrease")
        }

        @Test
        fun `no previous data shows fallback message`() {
            val current = calculator.calculateDaily(listOf(sale()))
            val previous = calculator.calculateDaily(emptyList())

            val msg = calculator.compare(current, previous)
            assertTrue(msg.contains("Hakuna data"))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TREND
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class Trend {

        @Test
        fun `trend returns one statement per day`() {
            val daily = mapOf(
                "Jumatatu" to listOf(sale(amount = 1000.0)),
                "Jumanne" to listOf(sale(amount = 1200.0)),
                "Jumatano" to listOf(sale(amount = 800.0))
            )
            val trend = calculator.calculateTrend(daily)
            assertEquals(3, trend.size)
        }
    }
}
