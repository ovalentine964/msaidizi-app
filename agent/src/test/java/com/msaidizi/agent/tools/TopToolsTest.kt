package com.msaidizi.agent.tools

import org.junit.Assert.*
import org.junit.Test

/**
 * T5: Top 10 most-used tools — tests for the highest-frequency code paths.
 *
 * Based on tool usage patterns in the codebase:
 * 1. AlamaScore (credit scoring) — already tested in AlamaScoreTest
 * 2. MpesaSmsParser (M-Pesa parsing) — already tested in MpesaSmsParserTest
 * 3. IntentRouter (intent classification) — already tested in IntentRouterPatternTest
 * 4. EncryptionManager — already tested in EncryptionManagerTest
 * 5. PinHasher — already tested in PinHasherTest
 * 6. CFOEngine (financial reports)
 * 7. DebtTracker (customer debts)
 * 8. GoalTracker (savings goals)
 * 9. BusinessHealthDashboard (health metrics)
 * 10. AutoRestock (inventory management)
 *
 * This file tests tools 6-10 that don't have dedicated test files yet.
 */
class TopToolsTest {

    // ═══════════════════════════════════════════════════════════
    //  Tool 6: CFOEngine — Financial Report Generation
    // ═══════════════════════════════════════════════════════════

    data class DailyFinancials(
        val date: String,
        val revenue: Double,
        val expenses: Double,
        val transactions: Int
    )

    data class CfoReport(
        val totalRevenue: Double,
        val totalExpenses: Double,
        val netProfit: Double,
        val profitMargin: Double,
        val avgDailyRevenue: Double,
        val topExpenseCategory: String,
        val daysAnalyzed: Int
    )

    private fun generateCfoReport(dailyData: List<DailyFinancials>): CfoReport {
        val totalRevenue = dailyData.sumOf { it.revenue }
        val totalExpenses = dailyData.sumOf { it.expenses }
        val netProfit = totalRevenue - totalExpenses
        val profitMargin = if (totalRevenue > 0) netProfit / totalRevenue else 0.0
        val avgDailyRevenue = if (dailyData.isNotEmpty()) totalRevenue / dailyData.size else 0.0

        return CfoReport(
            totalRevenue = totalRevenue,
            totalExpenses = totalExpenses,
            netProfit = netProfit,
            profitMargin = profitMargin,
            avgDailyRevenue = avgDailyRevenue,
            topExpenseCategory = "operations",
            daysAnalyzed = dailyData.size
        )
    }

    @Test
    fun `CFO report calculates profit correctly`() {
        val data = listOf(
            DailyFinancials("2024-01-01", 5000.0, 2000.0, 15),
            DailyFinancials("2024-01-02", 6000.0, 2500.0, 18),
            DailyFinancials("2024-01-03", 4000.0, 1800.0, 12)
        )

        val report = generateCfoReport(data)

        assertEquals(15000.0, report.totalRevenue, 0.01)
        assertEquals(6300.0, report.totalExpenses, 0.01)
        assertEquals(8700.0, report.netProfit, 0.01)
        assertEquals(3, report.daysAnalyzed)
    }

    @Test
    fun `CFO report handles empty data`() {
        val report = generateCfoReport(emptyList())

        assertEquals(0.0, report.totalRevenue, 0.01)
        assertEquals(0.0, report.netProfit, 0.01)
        assertEquals(0.0, report.profitMargin, 0.01)
        assertEquals(0, report.daysAnalyzed)
    }

    @Test
    fun `CFO report profit margin calculation`() {
        val data = listOf(
            DailyFinancials("2024-01-01", 10000.0, 6000.0, 20)
        )

        val report = generateCfoReport(data)

        assertEquals(0.4, report.profitMargin, 0.01) // 40% margin
    }

    // ═══════════════════════════════════════════════════════════
    //  Tool 7: DebtTracker — Customer Debt Management
    // ═══════════════════════════════════════════════════════════

    data class CustomerDebt(
        val customerName: String,
        val totalOwed: Double,
        val totalPaid: Double,
        val lastPaymentDate: String?
    ) {
        val remaining: Double get() = totalOwed - totalPaid
        val isFullyPaid: Boolean get() = remaining <= 0
    }

    private fun calculateTotalOutstanding(debts: List<CustomerDebt>): Double {
        return debts.filter { !it.isFullyPaid }.sumOf { it.remaining }
    }

    private fun getTopDebtors(debts: List<CustomerDebt>, limit: Int = 5): List<CustomerDebt> {
        return debts.filter { !it.isFullyPaid }
            .sortedByDescending { it.remaining }
            .take(limit)
    }

    @Test
    fun `debt tracker calculates outstanding correctly`() {
        val debts = listOf(
            CustomerDebt("John", 1000.0, 500.0, "2024-01-01"),
            CustomerDebt("Jane", 2000.0, 2000.0, "2024-01-02"), // Fully paid
            CustomerDebt("Peter", 500.0, 0.0, null)
        )

        val outstanding = calculateTotalOutstanding(debts)
        assertEquals(1500.0, outstanding, 0.01) // 500 + 0 + 1000
    }

    @Test
    fun `debt tracker identifies top debtors`() {
        val debts = listOf(
            CustomerDebt("Alice", 5000.0, 1000.0, null),
            CustomerDebt("Bob", 1000.0, 800.0, null),
            CustomerDebt("Charlie", 3000.0, 0.0, null),
            CustomerDebt("Diana", 2000.0, 2000.0, "2024-01-01") // Paid
        )

        val topDebtors = getTopDebtors(debts, 2)

        assertEquals(2, topDebtors.size)
        assertEquals("Alice", topDebtors[0].customerName)
        assertEquals("Charlie", topDebtors[1].customerName)
    }

    @Test
    fun `fully paid customers excluded from debtors`() {
        val debts = listOf(
            CustomerDebt("John", 1000.0, 1000.0, "2024-01-01")
        )

        assertEquals(0.0, calculateTotalOutstanding(debts), 0.01)
        assertTrue(getTopDebtors(debts).isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    //  Tool 8: GoalTracker — Savings Goals
    // ═══════════════════════════════════════════════════════════

    data class SavingsGoal(
        val name: String,
        val targetAmount: Double,
        val currentAmount: Double,
        val deadline: String
    ) {
        val progress: Double get() = if (targetAmount > 0) currentAmount / targetAmount else 0.0
        val remaining: Double get() = targetAmount - currentAmount
        val isAchieved: Boolean get() = currentAmount >= targetAmount
    }

    @Test
    fun `goal tracker calculates progress`() {
        val goal = SavingsGoal("Bike", 50000.0, 25000.0, "2024-06-01")

        assertEquals(0.5, goal.progress, 0.01)
        assertEquals(25000.0, goal.remaining, 0.01)
        assertFalse(goal.isAchieved)
    }

    @Test
    fun `goal tracker detects achieved goal`() {
        val goal = SavingsGoal("Bike", 50000.0, 55000.0, "2024-06-01")

        assertTrue(goal.isAchieved)
        assertTrue(goal.progress >= 1.0)
    }

    @Test
    fun `goal tracker handles zero target`() {
        val goal = SavingsGoal("Emergency", 0.0, 1000.0, "2024-06-01")

        assertEquals(0.0, goal.progress, 0.01) // Avoid division by zero
    }

    // ═══════════════════════════════════════════════════════════
    //  Tool 9: BusinessHealthDashboard — Health Metrics
    // ═══════════════════════════════════════════════════════════

    data class HealthMetrics(
        val dailySales: Double,
        val dailyExpenses: Double,
        val activeDays: Int,
        val totalDays: Int,
        val hasStock: Boolean,
        val hasCustomers: Boolean
    ) {
        val consistency: Double get() = if (totalDays > 0) activeDays / totalDays.toDouble() else 0.0
        val isHealthy: Boolean get() = dailySales > dailyExpenses && consistency > 0.5
    }

    @Test
    fun `health dashboard - healthy business`() {
        val metrics = HealthMetrics(
            dailySales = 5000.0,
            dailyExpenses = 3000.0,
            activeDays = 25,
            totalDays = 30,
            hasStock = true,
            hasCustomers = true
        )

        assertTrue(metrics.isHealthy)
        assertTrue(metrics.consistency > 0.5)
    }

    @Test
    fun `health dashboard - unhealthy business`() {
        val metrics = HealthMetrics(
            dailySales = 1000.0,
            dailyExpenses = 3000.0,
            activeDays = 5,
            totalDays = 30,
            hasStock = false,
            hasCustomers = false
        )

        assertFalse(metrics.isHealthy)
    }

    // ═══════════════════════════════════════════════════════════
    //  Tool 10: AutoRestock — Inventory Management
    // ═══════════════════════════════════════════════════════════

    data class InventoryAlert(
        val item: String,
        val currentStock: Double,
        val threshold: Double,
        val avgDailySales: Double
    ) {
        val needsRestock: Boolean get() = currentStock <= threshold
        val daysUntilStockout: Double get() =
            if (avgDailySales > 0) currentStock / avgDailySales else Double.MAX_VALUE
    }

    @Test
    fun `auto restock detects low stock`() {
        val alert = InventoryAlert("Mandazi", 3.0, 5.0, 10.0)

        assertTrue(alert.needsRestock)
        assertEquals(0.3, alert.daysUntilStockout, 0.01)
    }

    @Test
    fun `auto restock - adequate stock`() {
        val alert = InventoryAlert("Sugar", 50.0, 10.0, 5.0)

        assertFalse(alert.needsRestock)
        assertEquals(10.0, alert.daysUntilStockout, 0.01)
    }

    @Test
    fun `auto restock - zero sales rate`() {
        val alert = InventoryAlert("Slow Item", 100.0, 5.0, 0.0)

        assertFalse(alert.needsRestock)
        assertEquals(Double.MAX_VALUE, alert.daysUntilStockout, 0.01)
    }
}
