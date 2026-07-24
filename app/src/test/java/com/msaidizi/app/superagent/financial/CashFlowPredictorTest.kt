package com.msaidizi.app.superagent.financial

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [CashFlowPredictor] — Holt's double exponential smoothing
 * cash flow forecasting.
 */
class CashFlowPredictorTest {

    private lateinit var predictor: CashFlowPredictor

    @BeforeEach
    fun setup() {
        predictor = CashFlowPredictor()
    }

    // ═══════════════════════════════════════════════════════════════
    // INSUFFICIENT DATA
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class InsufficientData {

        @Test
        fun `returns error message when less than 5 days of data`() {
            val result = predictor.predict(
                currentCash = 5000.0,
                dailyRevenues = listOf(1000.0, 1200.0),
                dailyExpenses = listOf(500.0, 600.0)
            )

            assertTrue(result.message.contains("Hakuna data"))
            assertEquals(-1, result.daysRemaining)
            assertTrue(result.isHealthy) // defaults to healthy when no data
        }

        @Test
        fun `empty revenue list returns insufficient data`() {
            val result = predictor.predict(
                currentCash = 5000.0,
                dailyRevenues = emptyList(),
                dailyExpenses = List(10) { 500.0 }
            )

            assertTrue(result.message.contains("Hakuna data"))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HEALTHY CASH FLOW
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class HealthyCashFlow {

        @Test
        fun `positive cash flow is healthy`() {
            val revenues = List(28) { 3000.0 }
            val expenses = List(28) { 1500.0 }

            val result = predictor.predict(
                currentCash = 20000.0,
                dailyRevenues = revenues,
                dailyExpenses = expenses
            )

            assertTrue(result.isHealthy, "Positive cash flow should be healthy")
            assertTrue(result.daysRemaining > 30 || result.daysRemaining == Int.MAX_VALUE)
            assertTrue(result.message.contains("salama") || result.message.contains("✅"))
        }

        @Test
        fun `daily burn rate is negative when revenue exceeds expenses`() {
            val revenues = List(28) { 5000.0 }
            val expenses = List(28) { 2000.0 }

            val result = predictor.predict(
                currentCash = 10000.0,
                dailyRevenues = revenues,
                dailyExpenses = expenses
            )

            assertTrue(result.dailyBurnRate < 0, "Burn rate should be negative (positive cash flow)")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CASH SHORTAGE
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class CashShortage {

        @Test
        fun `negative cash flow triggers warning`() {
            val revenues = List(28) { 1000.0 }
            val expenses = List(28) { 3000.0 }

            val result = predictor.predict(
                currentCash = 5000.0,
                dailyRevenues = revenues,
                dailyExpenses = expenses,
                forecastDays = 14
            )

            assertFalse(result.isHealthy)
            assertTrue(result.daysRemaining in 1..14,
                "Should predict shortage within forecast window, got ${result.daysRemaining}")
            assertTrue(result.message.contains("Tahadhari") || result.message.contains("⚠️") ||
                result.message.contains("🚨"))
        }

        @Test
        fun `zero cash with expenses is immediate shortage`() {
            val revenues = List(28) { 1000.0 }
            val expenses = List(28) { 2000.0 }

            val result = predictor.predict(
                currentCash = 0.0,
                dailyRevenues = revenues,
                dailyExpenses = expenses
            )

            assertFalse(result.isHealthy)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // KNOWN EXPENSES
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class KnownExpenses {

        @Test
        fun `known upcoming expenses reduce projected cash`() {
            val revenues = List(28) { 3000.0 }
            val expenses = List(28) { 2000.0 }

            val withoutKnown = predictor.predict(
                currentCash = 10000.0,
                dailyRevenues = revenues,
                dailyExpenses = expenses
            )

            val withKnown = predictor.predict(
                currentCash = 10000.0,
                dailyRevenues = revenues,
                dailyExpenses = expenses,
                knownUpcomingExpenses = mapOf(7 to 5000.0)
            )

            // With known expenses, days remaining should be less or equal
            assertTrue(withKnown.daysRemaining <= withoutKnown.daysRemaining)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // FORECAST DAYS
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class ForecastDays {

        @Test
        fun `forecast days clamped to max 30`() {
            val revenues = List(28) { 3000.0 }
            val expenses = List(28) { 2000.0 }

            val result = predictor.predict(
                currentCash = 50000.0,
                dailyRevenues = revenues,
                dailyExpenses = expenses,
                forecastDays = 100 // should be clamped to 30
            )

            assertTrue(result.message.contains("siku 30"))
        }

        @Test
        fun `forecast days clamped to min 1`() {
            val revenues = List(28) { 3000.0 }
            val expenses = List(28) { 2000.0 }

            val result = predictor.predict(
                currentCash = 50000.0,
                dailyRevenues = revenues,
                dailyExpenses = expenses,
                forecastDays = -5 // should be clamped to 1
            )

            assertNotNull(result)
        }
    }
}
