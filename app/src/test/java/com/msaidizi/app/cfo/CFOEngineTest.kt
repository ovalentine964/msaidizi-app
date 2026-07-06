package com.msaidizi.app.cfo

import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Unit tests for CFOEngine — the proactive financial management engine.
 * Tests daily briefings, cash flow forecasts, restock recommendations,
 * savings advice, credit readiness, weekly reports, and risk alerts.
 */
@DisplayName("CFOEngine")
class CFOEngineTest {

    private lateinit var engine: CFOEngine

    @BeforeEach
    fun setUp() {
        engine = CFOEngine()
    }

    // Helper to create test transactions
    private fun sale(
        item: String = "mandazi",
        amount: Double = 500.0,
        quantity: Double = 10.0,
        createdAt: Long = System.currentTimeMillis() / 1000
    ) = Transaction(
        type = TransactionType.SALE,
        item = item,
        quantity = quantity,
        unitPrice = amount / quantity,
        totalAmount = amount,
        createdAt = createdAt
    )

    private fun expense(
        category: String = "transport",
        amount: Double = 100.0,
        createdAt: Long = System.currentTimeMillis() / 1000
    ) = Transaction(
        type = TransactionType.EXPENSE,
        item = category,
        totalAmount = amount,
        createdAt = createdAt
    )

    private fun purchase(
        item: String = "unga",
        amount: Double = 200.0,
        createdAt: Long = System.currentTimeMillis() / 1000
    ) = Transaction(
        type = TransactionType.PURCHASE,
        item = item,
        totalAmount = amount,
        createdAt = createdAt
    )

    // ── Daily Briefing ──────────────────────────────────────────

    @Nested
    @DisplayName("Daily Briefing")
    inner class DailyBriefingTests {

        @Test
        fun `generates briefing with today's sales`() {
            val today = listOf(sale(amount = 3200.0))
            val yesterday = listOf(sale(amount = 2800.0))
            val recent = today + yesterday

            val briefing = engine.getDailyBriefing(
                workerName = "Mama Njeri",
                assistantName = "Msaidizi",
                todayTransactions = today,
                yesterdayTransactions = yesterday,
                recentTransactions = recent
            )

            assertTrue(briefing.message.contains("Mama Njeri"), "Should mention worker name")
            assertTrue(briefing.todaySales == 3200.0, "Sales should be 3200")
            assertTrue(briefing.todayProfit > 0, "Profit should be positive")
        }

        @Test
        fun `handles empty today transactions`() {
            val briefing = engine.getDailyBriefing(
                workerName = "Juma",
                assistantName = "Msaidizi",
                todayTransactions = emptyList(),
                yesterdayTransactions = listOf(sale(amount = 2000.0)),
                recentTransactions = listOf(sale(amount = 2000.0))
            )

            assertTrue(briefing.message.contains("Leo bado"), "Should say no sales yet")
            assertEquals(0.0, briefing.todaySales)
        }

        @Test
        fun `calculates profit correctly with expenses`() {
            val today = listOf(
                sale(amount = 3000.0),
                expense(amount = 500.0)
            )

            val briefing = engine.getDailyBriefing(
                workerName = "Test",
                assistantName = "Msaidizi",
                todayTransactions = today,
                yesterdayTransactions = emptyList(),
                recentTransactions = today
            )

            assertEquals(3000.0, briefing.todaySales)
            assertEquals(500.0, briefing.todayExpenses)
            assertEquals(2500.0, briefing.todayProfit)
        }

        @Test
        fun `includes savings recommendation when profitable`() {
            val today = listOf(sale(amount = 5000.0))

            val briefing = engine.getDailyBriefing(
                workerName = "Test",
                assistantName = "Msaidizi",
                todayTransactions = today,
                yesterdayTransactions = emptyList(),
                recentTransactions = today
            )

            assertTrue(briefing.savingsRecommendation > 0, "Should recommend savings")
        }

        @Test
        fun `identifies top selling item`() {
            val today = listOf(
                sale(item = "mandazi", amount = 1000.0),
                sale(item = "nyanya", amount = 2000.0),
                sale(item = "mandazi", amount = 500.0)
            )

            val briefing = engine.getDailyBriefing(
                workerName = "Test",
                assistantName = "Msaidizi",
                todayTransactions = today,
                yesterdayTransactions = emptyList(),
                recentTransactions = today
            )

            // mandazi = 1500, nyanya = 2000 → nyanya is top
            assertEquals("nyanya", briefing.topSellingItem)
        }
    }

    // ── Cash Flow Forecast ──────────────────────────────────────

    @Nested
    @DisplayName("Cash Flow Forecast")
    inner class CashFlowForecastTests {

        @Test
        fun `predicts days remaining when burning cash`() {
            val forecast = engine.getCashFlowForecast(
                currentCash = 10000.0,
                dailyExpenses = 1500.0,
                dailyRevenue = 1000.0
            )

            // Net burn = 500/day, 10000/500 = 20 days
            assertEquals(20, forecast.daysRemaining)
            assertTrue(forecast.isHealthy)
        }

        @Test
        fun `reports healthy when revenue exceeds expenses`() {
            val forecast = engine.getCashFlowForecast(
                currentCash = 5000.0,
                dailyExpenses = 800.0,
                dailyRevenue = 1200.0
            )

            assertEquals(Int.MAX_VALUE, forecast.daysRemaining)
            assertTrue(forecast.isHealthy)
        }

        @Test
        fun `warns when less than 7 days remaining`() {
            val forecast = engine.getCashFlowForecast(
                currentCash = 3000.0,
                dailyExpenses = 1000.0,
                dailyRevenue = 600.0
            )

            // Net burn = 400/day, 3000/400 = 7 days
            assertFalse(forecast.isHealthy, "Should not be healthy")
            assertTrue(forecast.message.contains("Tahadhari") || forecast.message.contains("siku"))
        }
    }

    // ── Restock Recommendation ──────────────────────────────────

    @Nested
    @DisplayName("Restock Recommendation")
    inner class RestockTests {

        @Test
        fun `recommends restocking items with low stock`() {
            val recentSales = (1..14).map { day ->
                sale(item = "mandazi", amount = 500.0, quantity = 10.0,
                    createdAt = (System.currentTimeMillis() / 1000) - (day * 86400))
            }
            val currentStock = mapOf("mandazi" to 5.0) // Only 0.5 days of stock

            val advice = engine.getRestockRecommendation(recentSales, currentStock)

            assertTrue(advice.items.isNotEmpty(), "Should have restock items")
            assertEquals("mandazi", advice.items.first().item)
        }

        @Test
        fun `reports stock is fine when adequate`() {
            val recentSales = listOf(sale(item = "mandazi", amount = 100.0, quantity = 1.0))
            val currentStock = mapOf("mandazi" to 100.0) // Plenty of stock

            val advice = engine.getRestockRecommendation(recentSales, currentStock)

            assertTrue(advice.items.isEmpty(), "Should not recommend restocking")
        }
    }

    // ── Savings Recommendation ──────────────────────────────────

    @Nested
    @DisplayName("Savings Recommendation")
    inner class SavingsTests {

        @Test
        fun `recommends 20 percent of profit for savings`() {
            val advice = engine.getSavingsRecommendation(
                todayProfit = 1000.0,
                totalSaved = 0.0
            )

            assertEquals(200.0, advice.recommendedAmount, 0.01)
        }

        @Test
        fun `reports zero savings when no profit`() {
            val advice = engine.getSavingsRecommendation(
                todayProfit = 0.0,
                totalSaved = 0.0
            )

            assertEquals(0.0, advice.recommendedAmount)
        }

        @Test
        fun `celebrates when emergency fund target reached`() {
            val advice = engine.getSavingsRecommendation(
                todayProfit = 500.0,
                totalSaved = 10000.0 // At target
            )

            assertEquals(100, advice.progressPercent)
            assertTrue(advice.message.contains("Hongera") || advice.message.contains("🎉"))
        }
    }

    // ── Credit Readiness ────────────────────────────────────────

    @Nested
    @DisplayName("Credit Readiness")
    inner class CreditReadinessTests {

        @Test
        fun `scores high for consistent profitable business`() {
            // 30 days of transactions, high margin, good savings
            val transactions = (1..30).flatMap { day ->
                listOf(
                    sale(amount = 2000.0, createdAt = day * 86400L),
                    sale(amount = 1500.0, createdAt = day * 86400L),
                    expense(amount = 500.0, createdAt = day * 86400L)
                )
            }

            val readiness = engine.getCreditReadiness(transactions, totalSaved = 8000.0)

            assertTrue(readiness.score >= 60, "Score ${readiness.score} should be >= 60")
            assertTrue(readiness.isReady, "Should be credit ready")
        }

        @Test
        fun `scores low for inconsistent business`() {
            val transactions = listOf(
                sale(amount = 100.0, createdAt = 86400)
            )

            val readiness = engine.getCreditReadiness(transactions, totalSaved = 0.0)

            assertFalse(readiness.isReady, "Should not be credit ready")
        }
    }

    // ── Weekly Report ───────────────────────────────────────────

    @Nested
    @DisplayName("Weekly Report")
    inner class WeeklyReportTests {

        @Test
        fun `generates weekly report with correct totals`() {
            val thisWeek = (1..7).map { day ->
                sale(amount = 1000.0, createdAt = day * 86400L)
            }
            val lastWeek = (1..7).map { day ->
                sale(amount = 800.0, createdAt = (day - 7) * 86400L)
            }

            val report = engine.getWeeklyReport(
                workerName = "Juma",
                assistantName = "Msaidizi",
                thisWeek = thisWeek,
                lastWeek = lastWeek
            )

            assertEquals(7000.0, report.totalSales)
            assertTrue(report.salesGrowthPercent > 0, "Should show growth")
            assertTrue(report.message.contains("Juma"))
        }
    }

    // ── Risk Alerts ─────────────────────────────────────────────

    @Nested
    @DisplayName("Risk Alerts")
    inner class RiskAlertTests {

        @Test
        fun `detects revenue decline`() {
            val recent = listOf(sale(amount = 5000.0)) // Low recent
            val older = (1..14).map { sale(amount = 2000.0, createdAt = it * 86400L) } // High older

            val alerts = engine.getRiskAlerts(recent, older)

            assertTrue(alerts.any { it.type == RiskType.REVENUE_DECLINE },
                "Should detect revenue decline")
        }

        @Test
        fun `reports safe when no issues`() {
            val transactions = (1..14).map { day ->
                sale(amount = 2000.0, createdAt = day * 86400L)
            }

            val alerts = engine.getRiskAlerts(transactions, transactions)

            assertTrue(alerts.any { it.type == RiskType.NONE },
                "Should report no risks")
        }

        @Test
        fun `detects concentration risk when one item dominates`() {
            val recent = (1..14).map { day ->
                sale(item = "mandazi", amount = 5000.0, createdAt = day * 86400L)
            } + sale(item = "nyanya", amount = 100.0)

            val alerts = engine.getRiskAlerts(recent, recent)

            assertTrue(alerts.any { it.type == RiskType.CONCENTRATION_RISK },
                "Should detect concentration risk")
        }
    }
}
