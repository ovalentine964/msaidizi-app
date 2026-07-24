package com.msaidizi.app.superagent.flow

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * BusinessFlowEngine — The Main Orchestrator
 * "Biashara yangu ikoje?"
 *
 * This is the entry point. It brings together all trackers and generates
 * the reports workers need to understand WHERE THEIR MONEY IS GOING.
 *
 * Usage:
 * ```kotlin
 * val engine = BusinessFlowEngine()
 *
 * // Record data
 * engine.recordSale(sale)
 * engine.recordExpense(expense)
 *
 * // Get the big picture
 * val report = engine.getFullReport(ReportPeriod.THIS_WEEK)
 * println(report.voiceSummary)
 * ```
 */
class BusinessFlowEngine {

    // ── Trackers ───────────────────────────────
    val revenueTracker = RevenueTracker()
    val costTracker = CostTracker()
    val inventoryTracker = InventoryTracker()
    val customerTracker = CustomerTracker()
    val supplierTracker = SupplierTracker()
    val profitCalculator = ProfitCalculator(revenueTracker, costTracker)
    val moneyFlowVisualizer = MoneyFlowVisualizer(
        revenueTracker, costTracker, profitCalculator,
        inventoryTracker, customerTracker, supplierTracker
    )

    // ── Quick Data Entry ───────────────────────

    /**
     * Record a sale. Updates revenue, inventory, and customer tracking.
     * "Mauzo yametokea."
     */
    fun recordSale(sale: Sale) {
        revenueTracker.recordSale(sale)
        inventoryTracker.recordSale(sale.productId, sale.quantity)

        if (sale.customerId != null) {
            customerTracker.recordVisit(sale.customerId, sale.amount, listOf(sale.productName))
        }
    }

    /**
     * Record an expense. Updates cost tracking.
     * "Gharama imetokea."
     */
    fun recordExpense(expense: Expense) {
        costTracker.recordExpense(expense)
    }

    /**
     * Record a stock purchase (COGS expense + inventory update).
     * "Nimenunua stock."
     */
    fun recordStockPurchase(
        productId: String,
        productName: String,
        quantity: Int,
        costPerUnit: Double,
        supplierId: String? = null
    ) {
        val totalCost = quantity * costPerUnit

        // Record as COGS expense
        costTracker.recordExpense(Expense(
            id = "STOCK_${System.currentTimeMillis()}",
            category = ExpenseCategory.COGS,
            amount = totalCost,
            description = "Stock purchase: $quantity x $productName",
            supplierId = supplierId
        ))

        // Update inventory
        inventoryTracker.addStock(productId, quantity, costPerUnit)

        // Record supplier order
        if (supplierId != null) {
            supplierTracker.recordOrder(SupplierOrder(
                id = "ORD_${System.currentTimeMillis()}",
                supplierId = supplierId,
                productId = productId,
                quantity = quantity,
                pricePerUnit = costPerUnit,
                totalCost = totalCost
            ))
        }
    }

    /**
     * Record credit given to a customer (dlana).
     * "Nimemkopesha mteja."
     */
    fun recordCredit(customerId: String, amount: Double, products: List<String> = emptyList()) {
        customerTracker.addCredit(customerId, amount)
        // Also record as a sale (revenue earned, but cash not yet received)
        customerTracker.recordVisit(customerId, amount, products)
    }

    /**
     * Record credit payment received.
     * "Mteja amelipa dlana."
     */
    fun recordCreditPayment(customerId: String, amount: Double) {
        customerTracker.settleCredit(customerId, amount)
    }

    /**
     * Register a new product.
     * "Bidhaa mpya."
     */
    fun registerProduct(product: Product) {
        inventoryTracker.registerProduct(product)
    }

    /**
     * Register a new supplier.
     * "Muuzaji mpya."
     */
    fun registerSupplier(supplier: Supplier) {
        supplierTracker.registerSupplier(supplier)
    }

    /**
     * Register a new customer.
     * "Mteja mpya."
     */
    fun registerCustomer(customer: Customer) {
        customerTracker.addCustomer(customer)
    }

    // ── Reports ────────────────────────────────

    /**
     * THE REPORT: "Where did my money go?"
     * This is what workers actually want to know.
     */
    fun getMoneyFlowReport(period: ReportPeriod, customRange: DateRange? = null): MoneyFlowReport {
        return moneyFlowVisualizer.generateMoneyFlowReport(period, customRange)
    }

    /**
     * Text-based visual flow diagram.
     * Great for display in chat or dashboard.
     */
    fun getFlowDiagram(period: ReportPeriod, customRange: DateRange? = null): String {
        return moneyFlowVisualizer.generateFlowDiagram(period, customRange)
    }

    /**
     * Voice-friendly flow summary in Swahili.
     * For TTS output — sounds natural when spoken.
     */
    fun getVoiceFlow(period: ReportPeriod, customRange: DateRange? = null): String {
        return moneyFlowVisualizer.generateVoiceFlow(period, customRange)
    }

    /**
     * Full comprehensive report with all trackers.
     */
    fun getFullReport(period: ReportPeriod, customRange: DateRange? = null): FullBusinessReport {
        val moneyFlow = moneyFlowVisualizer.generateMoneyFlowReport(period, customRange)
        val revenue = revenueTracker.getSummary(period, customRange)
        val costs = costTracker.getSummary(period, customRange)
        val profit = profitCalculator.getProfitSummary(period, customRange)
        val inventory = inventoryTracker.getSummary()
        val customers = customerTracker.getSummary(period, customRange)
        val creditReport = customerTracker.getCreditReport()
        val suppliers = supplierTracker.getSummary(period, customRange)
        val turnover = inventoryTracker.getTurnoverAnalysis()
        val profitabilityScore = profitCalculator.getProfitabilityScore(period, customRange)

        return FullBusinessReport(
            period = moneyFlow.period,
            moneyFlow = moneyFlow,
            revenue = revenue,
            costs = costs,
            profit = profit,
            inventory = inventory,
            customers = customers,
            creditReport = creditReport,
            suppliers = suppliers,
            turnover = turnover,
            profitabilityScore = profitabilityScore,
            // Voice summaries for each section
            revenueVoiceSummary = revenueTracker.getVoiceSummary(period, customRange),
            costVoiceSummary = costTracker.getVoiceSummary(period, customRange),
            profitVoiceSummary = profitCalculator.getVoiceSummary(period, customRange),
            inventoryVoiceSummary = inventoryTracker.getVoiceSummary(),
            customerVoiceSummary = customerTracker.getVoiceSummary(period, customRange),
            supplierVoiceSummary = supplierTracker.getVoiceSummary(period, customRange),
            // The full voice report (all sections combined)
            fullVoiceReport = buildFullVoiceReport(period, customRange)
        )
    }

    /**
     * Quick dashboard data for UI display.
     */
    fun getDashboardData(period: ReportPeriod, customRange: DateRange? = null): DashboardData {
        val revenue = revenueTracker.getSummary(period, customRange)
        val costs = costTracker.getSummary(period, customRange)
        val profit = profitCalculator.getProfitSummary(period, customRange)
        val inventory = inventoryTracker.getSummary()
        val customers = customerTracker.getSummary(period, customRange)
        val creditReport = customerTracker.getCreditReport()

        return DashboardData(
            period = revenue.period,
            // Key metrics
            totalRevenue = revenue.totalRevenue,
            totalCosts = costs.totalCosts,
            netProfit = profit.netProfit,
            netMargin = profit.netMargin,
            profitTrend = profit.trendDirection,
            // Quick stats
            totalSales = revenue.totalTransactions,
            averageDailyRevenue = revenue.dailyAverage,
            totalCustomers = customers.totalCustomers,
            newCustomers = customers.newCustomers,
            // Inventory
            stockValue = inventory.totalStockValue,
            lowStockCount = inventory.lowStockAlerts.size,
            deadStockCount = inventory.deadStock.size,
            // Credit
            outstandingCredit = creditReport.totalOutstanding,
            // Health
            profitabilityScore = profitCalculator.getProfitabilityScore(period, customRange).score
        )
    }

    // ── Batch Data Loading ─────────────────────

    fun loadSales(sales: List<Sale>) {
        sales.forEach { recordSale(it) }
    }

    fun loadExpenses(expenses: List<Expense>) {
        expenses.forEach { recordExpense(it) }
    }

    fun loadProducts(products: List<Product>) {
        products.forEach { registerProduct(it) }
    }

    fun loadSuppliers(suppliers: List<Supplier>) {
        suppliers.forEach { registerSupplier(it) }
    }

    fun loadCustomers(customers: List<Customer>) {
        customers.forEach { registerCustomer(it) }
    }

    // ── Reset ──────────────────────────────────

    fun clearAllData() {
        revenueTracker.clearData()
        costTracker.clearData()
        inventoryTracker.clearData()
        customerTracker.clearData()
        supplierTracker.clearData()
    }

    // ── Private Helpers ────────────────────────

    private fun buildFullVoiceReport(period: ReportPeriod, customRange: DateRange? = null): String {
        return buildString {
            // Money flow (the headline)
            appendLine(moneyFlowVisualizer.generateVoiceFlow(period, customRange))
            appendLine("")

            // Profitability score
            val score = profitCalculator.getProfitabilityScore(period, customRange)
            appendLine("Alama ya biashara yako: ${score.score} kati ya 100. ${score.messageSw}")
            appendLine("")

            // Top recommendation
            val report = moneyFlowVisualizer.generateMoneyFlowReport(period, customRange)
            report.recommendations.firstOrNull()?.let { rec ->
                appendLine("Ushauri muhimu: ${rec.detailSw}")
            }
        }
    }
}

// ── Report Data Classes ──────────────────────

data class FullBusinessReport(
    val period: DateRange,
    val moneyFlow: MoneyFlowReport,
    val revenue: RevenueSummary,
    val costs: CostSummary,
    val profit: ProfitSummary,
    val inventory: InventorySummary,
    val customers: CustomerSummary,
    val creditReport: CreditReport,
    val suppliers: SupplierSummary,
    val turnover: TurnoverAnalysis,
    val profitabilityScore: ProfitabilityScore,
    // Voice summaries
    val revenueVoiceSummary: String,
    val costVoiceSummary: String,
    val profitVoiceSummary: String,
    val inventoryVoiceSummary: String,
    val customerVoiceSummary: String,
    val supplierVoiceSummary: String,
    val fullVoiceReport: String
)

data class DashboardData(
    val period: DateRange,
    // Key metrics
    val totalRevenue: Double,
    val totalCosts: Double,
    val netProfit: Double,
    val netMargin: Double,
    val profitTrend: TrendDirection,
    // Quick stats
    val totalSales: Int,
    val averageDailyRevenue: Double,
    val totalCustomers: Int,
    val newCustomers: Int,
    // Inventory
    val stockValue: Double,
    val lowStockCount: Int,
    val deadStockCount: Int,
    // Credit
    val outstandingCredit: Double,
    // Health
    val profitabilityScore: Int
)
