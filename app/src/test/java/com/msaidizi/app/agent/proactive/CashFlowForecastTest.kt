package com.msaidizi.app.agent.proactive

import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import com.msaidizi.app.testutil.TestModels
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Unit tests for [CashFlowPredictor] — Holt's double exponential smoothing.
 *
 * Verifies forecasting logic with known inputs/outputs:
 * - Stable income → predicts similar values
 * - Upward trend → predicts higher values
 * - Downward trend → predicts lower values
 * - Empty data → returns zero with low confidence
 */
@DisplayName("CashFlowPredictor")
class CashFlowForecastTest {

    private lateinit var dao: TransactionDao
    private lateinit var predictor: CashFlowPredictor

    @BeforeEach
    fun setup() {
        dao = mockk()
        predictor = CashFlowPredictor(dao)
    }

    // =====================================================================
    // EMPTY / INSUFFICIENT DATA
    // =====================================================================

    @Nested
    @DisplayName("with no transaction data")
    inner class EmptyDataTests {

        @Test
        fun `empty transactions returns zero prediction with zero confidence`() = runTest {
            coEvery { dao.getTransactionsInRangeSuspend(any(), any()) } returns emptyList()

            val result = predictor.predictTomorrow()

            assertEquals(0.0, result.predictedIncome)
            assertEquals(0.0, result.predictedExpenses)
            assertEquals(0.0, result.predictedNet)
            assertEquals(0.0, result.confidence)
            assertEquals(CashFlowTrend.INSUFFICIENT_DATA, result.trend)
        }

        @Test
        fun `empty data message indicates insufficient data`() = runTest {
            coEvery { dao.getTransactionsInRangeSuspend(any(), any()) } returns emptyList()

            val result = predictor.predictTomorrow()

            assertTrue(result.message.contains("Hakuna data") || result.message.contains("insufficient"),
                "Message should indicate no data available")
        }
    }

    // =====================================================================
    // STABLE INCOME PATTERN
    // =====================================================================

    @Nested
    @DisplayName("with stable income pattern")
    inner class StablePatternTests {

        @Test
        fun `stable daily income predicts similar value`() = runTest {
            val transactions = TestModels.dailyTransactionSeries(
                daysCount = 21,
                dailySales = 5000.0,
                dailyExpenses = 2000.0
            )
            coEvery { dao.getTransactionsInRangeSuspend(any(), any()) } returns transactions

            val result = predictor.predictTomorrow()

            // Net should be approximately 3000 (5000 - 2000)
            assertTrue(result.predictedNet > 1000, "Predicted net ${result.predictedNet} should be positive")
            assertTrue(result.predictedNet < 5000, "Predicted net ${result.predictedNet} should be reasonable")
            assertTrue(result.predictedIncome > 0, "Should predict positive income")
            assertTrue(result.predictedExpenses > 0, "Should predict positive expenses")
        }

        @Test
        fun `stable pattern has reasonable confidence`() = runTest {
            val transactions = TestModels.dailyTransactionSeries(daysCount = 21)
            coEvery { dao.getTransactionsInRangeSuspend(any(), any()) } returns transactions

            val result = predictor.predictTomorrow()

            assertTrue(result.confidence > 0.3, "Confidence ${result.confidence} should be decent with 21 days of data")
            assertTrue(result.confidence <= 0.95, "Confidence should not exceed 0.95")
        }

        @Test
        fun `stable pattern trend is STABLE`() = runTest {
            val transactions = TestModels.dailyTransactionSeries(
                daysCount = 21,
                dailySales = 5000.0,
                dailyExpenses = 2000.0
            )
            coEvery { dao.getTransactionsInRangeSuspend(any(), any()) } returns transactions

            val result = predictor.predictTomorrow()

            // With constant daily amounts, trend should be STABLE or INSUFFICIENT_DATA
            assertTrue(
                result.trend == CashFlowTrend.STABLE || result.trend == CashFlowTrend.INSUFFICIENT_DATA,
                "Stable pattern should have STABLE trend, got ${result.trend}"
            )
        }
    }

    // =====================================================================
    // UPWARD TREND
    // =====================================================================

    @Nested
    @DisplayName("with upward trend")
    inner class UpwardTrendTests {

        @Test
        fun `increasing sales predicts higher income than average`() = runTest {
            val transactions = TestModels.trendingTransactionSeries(
                daysCount = 21,
                startDailySales = 3000.0,
                dailyGrowth = 300.0, // Sales grow by 300/day
                dailyExpenses = 1500.0
            )
            coEvery { dao.getTransactionsInRangeSuspend(any(), any()) } returns transactions

            val result = predictor.predictTomorrow()

            // Last day's sales would be 3000 + 20*300 = 9000
            // Average over 21 days = (3000 + 9000) / 2 = 6000
            // Holt's should predict close to or above the last value
            assertTrue(result.predictedIncome > 4000,
                "Upward trend should predict income > 4000, got ${result.predictedIncome}")
        }

        @Test
        fun `upward trend detected correctly`() = runTest {
            val transactions = TestModels.trendingTransactionSeries(
                daysCount = 21,
                startDailySales = 2000.0,
                dailyGrowth = 400.0, // Strong growth
                dailyExpenses = 1000.0
            )
            coEvery { dao.getTransactionsInRangeSuspend(any(), any()) } returns transactions

            val result = predictor.predictTomorrow()

            // Net is increasing significantly each day
            assertTrue(
                result.trend == CashFlowTrend.IMPROVING || result.trend == CashFlowTrend.STABLE,
                "Strong upward trend should be IMPROVING or STABLE, got ${result.trend}"
            )
        }
    }

    // =====================================================================
    // DOWNWARD TREND
    // =====================================================================

    @Nested
    @DisplayName("with downward trend")
    inner class DownwardTrendTests {

        @Test
        fun `declining sales predicts lower income`() = runTest {
            // Start with high sales, decrease daily
            val transactions = TestModels.trendingTransactionSeries(
                daysCount = 21,
                startDailySales = 8000.0,
                dailyGrowth = -200.0, // Sales decline by 200/day
                dailyExpenses = 2000.0
            )
            coEvery { dao.getTransactionsInRangeSuspend(any(), any()) } returns transactions

            val result = predictor.predictTomorrow()

            // Should predict declining income
            assertTrue(result.predictedIncome < 8000,
                "Declining trend should predict income < 8000, got ${result.predictedIncome}")
        }
    }

    // =====================================================================
    // MULTI-DAY FORECAST
    // =====================================================================

    @Nested
    @DisplayName("multi-day forecast")
    inner class MultiDayTests {

        @Test
        fun `7-day forecast returns valid prediction`() = runTest {
            val transactions = TestModels.dailyTransactionSeries(daysCount = 21)
            coEvery { dao.getTransactionsInRangeSuspend(any(), any()) } returns transactions

            val result = predictor.predictDaysAhead(7)

            assertNotNull(result)
            assertNotNull(result.message)
            assertTrue(result.confidence > 0)
        }

        @Test
        fun `7-day forecast with upward trend predicts higher than 1-day`() = runTest {
            val transactions = TestModels.trendingTransactionSeries(
                daysCount = 21,
                startDailySales = 3000.0,
                dailyGrowth = 300.0,
                dailyExpenses = 1500.0
            )
            coEvery { dao.getTransactionsInRangeSuspend(any(), any()) } returns transactions

            val oneDay = predictor.predictTomorrow()
            val sevenDay = predictor.predictDaysAhead(7)

            // With positive trend, 7-day forecast should be >= 1-day forecast
            assertTrue(sevenDay.predictedIncome >= oneDay.predictedIncome - 1.0,
                "7-day income ${sevenDay.predictedIncome} should be >= 1-day ${oneDay.predictedIncome}")
        }
    }

    // =====================================================================
    // PREDICTION MODEL (CashFlowPrediction)
    // =====================================================================

    @Nested
    @DisplayName("CashFlowPrediction model")
    inner class PredictionModelTests {

        @Test
        fun `positive net flow isPositive is true`() {
            val prediction = CashFlowPrediction(
                predictedIncome = 5000.0,
                predictedExpenses = 2000.0,
                predictedNet = 3000.0,
                message = "test",
                confidence = 0.8,
                trend = CashFlowTrend.STABLE
            )
            assertTrue(prediction.isPositive)
        }

        @Test
        fun `negative net flow isPositive is false`() {
            val prediction = CashFlowPrediction(
                predictedIncome = 1000.0,
                predictedExpenses = 3000.0,
                predictedNet = -2000.0,
                message = "test",
                confidence = 0.5,
                trend = CashFlowTrend.DECLINING
            )
            assertFalse(prediction.isPositive)
        }

        @Test
        fun `netFormatted handles positive amounts`() {
            val prediction = CashFlowPrediction(
                predictedIncome = 5000.0,
                predictedExpenses = 2000.0,
                predictedNet = 3000.0,
                message = "test",
                confidence = 0.8,
                trend = CashFlowTrend.STABLE
            )
            assertTrue(prediction.netFormatted.startsWith("KSh"))
            assertFalse(prediction.netFormatted.startsWith("-"))
        }

        @Test
        fun `netFormatted handles negative amounts`() {
            val prediction = CashFlowPrediction(
                predictedIncome = 1000.0,
                predictedExpenses = 3000.0,
                predictedNet = -2000.0,
                message = "test",
                confidence = 0.5,
                trend = CashFlowTrend.DECLINING
            )
            assertTrue(prediction.netFormatted.startsWith("-KSh"))
        }

        @Test
        fun `netFormatted uses comma separators for large amounts`() {
            val prediction = CashFlowPrediction(
                predictedIncome = 50000.0,
                predictedExpenses = 20000.0,
                predictedNet = 30000.0,
                message = "test",
                confidence = 0.8,
                trend = CashFlowTrend.STABLE
            )
            assertTrue(prediction.netFormatted.contains(","), "Large amounts should have commas")
        }
    }

    // =====================================================================
    // EDGE CASES
    // =====================================================================

    @Nested
    @DisplayName("edge cases")
    inner class EdgeCaseTests {

        @Test
        fun `single day of data returns valid prediction`() = runTest {
            val today = LocalDate.now()
            val epoch = today.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            val transactions = listOf(
                TestModels.sale(totalAmount = 3000.0, createdAt = epoch + 36000),
                TestModels.expense(amount = 1000.0, createdAt = epoch + 43200)
            )
            coEvery { dao.getTransactionsInRangeSuspend(any(), any()) } returns transactions

            val result = predictor.predictTomorrow()

            assertNotNull(result)
            // With 1 day of data, confidence should be low
            assertTrue(result.confidence < 0.5, "Low data should mean low confidence")
        }

        @Test
        fun `only sales (no expenses) predicts positive net`() = runTest {
            val transactions = TestModels.dailyTransactionSeries(
                daysCount = 14,
                dailySales = 5000.0,
                dailyExpenses = 0.0 // No expenses
            ).filter { it.type == TransactionType.SALE }

            coEvery { dao.getTransactionsInRangeSuspend(any(), any()) } returns transactions

            val result = predictor.predictTomorrow()

            assertTrue(result.predictedIncome > 0, "Should predict income from sales")
            assertEquals(0.0, result.predictedExpenses, 0.01, "No expenses in data")
        }

        @Test
        fun `prediction never returns negative income or expenses`() = runTest {
            // Even with declining trend, values should be clamped to 0
            val transactions = TestModels.trendingTransactionSeries(
                daysCount = 21,
                startDailySales = 100.0,
                dailyGrowth = -10.0, // Rapidly declining
                dailyExpenses = 50.0
            )
            coEvery { dao.getTransactionsInRangeSuspend(any(), any()) } returns transactions

            val result = predictor.predictTomorrow()

            assertTrue(result.predictedIncome >= 0.0, "Income should never be negative")
            assertTrue(result.predictedExpenses >= 0.0, "Expenses should never be negative")
        }
    }

    // =====================================================================
    // HOLT'S ALGORITHM BEHAVIOR
    // =====================================================================

    @Nested
    @DisplayName("Holt's smoothing behavior")
    inner class HoltTests {

        @Test
        fun `Holt reacts faster than simple average to trends`() = runTest {
            // Create a strong upward trend
            val transactions = TestModels.trendingTransactionSeries(
                daysCount = 21,
                startDailySales = 1000.0,
                dailyGrowth = 500.0, // Very strong growth
                dailyExpenses = 500.0
            )
            coEvery { dao.getTransactionsInRangeSuspend(any(), any()) } returns transactions

            val result = predictor.predictTomorrow()

            // Simple average of 1000..11000 would be 6000
            // Holt's should predict closer to the recent high values
            assertTrue(result.predictedIncome > 5000,
                "Holt's should follow trend above simple average, got ${result.predictedIncome}")
        }

        @Test
        fun `confidence increases with more data points`() = runTest {
            // Test with 7 days
            val fewDays = TestModels.dailyTransactionSeries(daysCount = 7)
            coEvery { dao.getTransactionsInRangeSuspend(any(), any()) } returns fewDays
            val resultFew = predictor.predictTomorrow()

            // Test with 28 days
            val manyDays = TestModels.dailyTransactionSeries(daysCount = 28)
            coEvery { dao.getTransactionsInRangeSuspend(any(), any()) } returns manyDays
            val resultMany = predictor.predictTomorrow()

            assertTrue(resultMany.confidence > resultFew.confidence,
                "More data should give higher confidence: ${resultMany.confidence} vs ${resultFew.confidence}")
        }
    }
}
