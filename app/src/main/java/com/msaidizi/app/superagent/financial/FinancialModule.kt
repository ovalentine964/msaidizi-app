package com.msaidizi.superagent.financial

import timber.log.Timber

/**
 * Financial Module — Entry point for the :superagent:financial capability.
 *
 * This is the unified financial reasoning module for the Msaidizi superagent.
 * It orchestrates all financial capabilities into a single cohesive module.
 *
 * ## Architecture
 *
 * The financial module replaces the scattered financial handlers from the
 * multi-agent architecture (TransactionHandler, QueryHandler, CFOEngine,
 * BudgetAnalyzerAgent, SavingsStrategistAgent, etc.) into one module.
 *
 * ## Capabilities
 *
 * | Engine               | Purpose                                    |
 * |----------------------|--------------------------------------------|
 * | [TransactionRecorder] | Voice-based transaction recording          |
 * | [PnLCalculator]      | Profit/loss tracking (daily/weekly/monthly) |
 * | [CashFlowPredictor]  | Predict shortages 7-30 days ahead          |
 * | [CFOEngine]          | Proactive financial management             |
 * | [InventoryTracker]   | Stock with perishability and spoilage      |
 * | [SupplierComparator] | Compare prices across suppliers            |
 * | [PricingAdvisor]     | Recommend pricing based on costs + market  |
 * | [BudgetCreator]      | Voice-based budget creation                |
 * | [FraudDetector]      | Duplicate and anomaly detection            |
 * | [TaxTracker]         | KRA compliance, deductible expenses        |
 *
 * ## Usage
 *
 * ```kotlin
 * val financialModule = FinancialModule()
 *
 * // Record a sale from voice
 * val result = financialModule.recordTransaction("Nimeuziwa mandazi kumi, mia mbili")
 *
 * // Get daily briefing
 * val briefing = financialModule.getDailyBriefing("Amina", "Msaidizi", today, yesterday, recent)
 *
 * // Check for anomalies
 * val fraud = financialModule.detectFraud(recentTransactions, historical)
 * ```
 *
 * @author Msaidizi Financial Team
 */
class FinancialModule(
    val transactionRecorder: TransactionRecorder = TransactionRecorder(),
    val pnfCalculator: PnLCalculator = PnLCalculator(),
    val cashFlowPredictor: CashFlowPredictor = CashFlowPredictor(),
    val cfoEngine: CFOEngine = CFOEngine(pnfCalculator, cashFlowPredictor),
    val inventoryTracker: InventoryTracker = InventoryTracker(),
    val supplierComparator: SupplierComparator = SupplierComparator(),
    val pricingAdvisor: PricingAdvisor = PricingAdvisor(),
    val budgetCreator: BudgetCreator = BudgetCreator(),
    val fraudDetector: FraudDetector = FraudDetector(),
    val taxTracker: TaxTracker = TaxTracker()
) {

    companion object {
        private const val TAG = "FinancialModule"
    }

    // ═══════════════════════════════════════════════════════════════
    // TRANSACTION RECORDING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a transaction from voice input.
     *
     * Parses natural language (Swahili/Sheng) into a structured transaction.
     *
     * @param voiceInput Raw text from speech-to-text
     * @param language Language code (default "sw")
     * @return [RecordedTransaction] with parsed data and confirmation
     */
    fun recordTransaction(voiceInput: String, language: String = "sw"): RecordedTransaction {
        Timber.tag(TAG).d("Recording transaction from voice: %s", voiceInput)
        return transactionRecorder.recordFromVoice(voiceInput, language)
    }

    // ═══════════════════════════════════════════════════════════════
    // FINANCIAL QUERIES
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get daily P&L statement.
     */
    fun getDailyPnL(transactions: List<Transaction>, workerName: String = ""): PnLStatement {
        return pnfCalculator.calculateDaily(transactions, workerName)
    }

    /**
     * Get weekly P&L statement.
     */
    fun getWeeklyPnL(transactions: List<Transaction>, workerName: String = ""): PnLStatement {
        return pnfCalculator.calculateWeekly(transactions, workerName)
    }

    /**
     * Get monthly P&L statement.
     */
    fun getMonthlyPnL(transactions: List<Transaction>, workerName: String = ""): PnLStatement {
        return pnfCalculator.calculateMonthly(transactions, workerName)
    }

    /**
     * Get P&L trend over multiple days.
     */
    fun getPnLTrend(dailyTransactions: Map<String, List<Transaction>>): List<PnLStatement> {
        return pnfCalculator.calculateTrend(dailyTransactions)
    }

    // ═══════════════════════════════════════════════════════════════
    // PROACTIVE ALERTS (CFO)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get morning briefing — delivered every morning without being asked.
     */
    fun getDailyBriefing(
        workerName: String,
        assistantName: String,
        todayTransactions: List<Transaction>,
        yesterdayTransactions: List<Transaction>,
        recentTransactions: List<Transaction>
    ): DailyBriefing {
        return cfoEngine.getDailyBriefing(
            workerName, assistantName,
            todayTransactions, yesterdayTransactions, recentTransactions
        )
    }

    /**
     * Get cash flow forecast — predicts when money runs out.
     */
    fun getCashFlowForecast(
        currentCash: Double,
        dailyRevenues: List<Double>,
        dailyExpenses: List<Double>
    ): CashFlowForecast {
        return cfoEngine.getCashFlowForecast(currentCash, dailyRevenues, dailyExpenses)
    }

    /**
     * Get restock recommendations — what to buy and when.
     */
    fun getRestockRecommendation(
        inventory: Map<String, InventoryItem>,
        recentSales: List<Transaction>
    ): RestockAdvice {
        return cfoEngine.getRestockRecommendation(inventory, recentSales)
    }

    /**
     * Get savings recommendation — how much to save today.
     */
    fun getSavingsRecommendation(todayProfit: Double, totalSaved: Double): SavingsAdvice {
        return cfoEngine.getSavingsRecommendation(todayProfit, totalSaved)
    }

    /**
     * Get risk alerts — identify threats to business.
     */
    fun getRiskAlerts(
        recentTransactions: List<Transaction>,
        olderTransactions: List<Transaction>
    ): List<RiskAlert> {
        return cfoEngine.getRiskAlerts(recentTransactions, olderTransactions)
    }

    /**
     * Get weekly report with comparison to previous week.
     */
    fun getWeeklyReport(
        workerName: String,
        assistantName: String,
        thisWeek: List<Transaction>,
        lastWeek: List<Transaction>
    ): PnLStatement {
        return cfoEngine.getWeeklyReport(workerName, assistantName, thisWeek, lastWeek)
    }

    // ═══════════════════════════════════════════════════════════════
    // INVENTORY
    // ═══════════════════════════════════════════════════════════════

    /**
     * Update inventory from a transaction.
     */
    fun updateInventory(
        currentInventory: Map<String, InventoryItem>,
        transaction: Transaction
    ): Map<String, InventoryItem> {
        return inventoryTracker.updateStock(currentInventory, transaction)
    }

    /**
     * Get inventory summary.
     */
    fun getInventorySummary(
        inventory: Map<String, InventoryItem>,
        salesHistory: List<Transaction>
    ): String {
        val velocity = inventoryTracker.calculateVelocity(salesHistory)
        return inventoryTracker.getInventorySummary(inventory, velocity)
    }

    /**
     * Check for spoilage risk.
     */
    fun checkSpoilageRisk(inventory: Map<String, InventoryItem>): List<SpoilageAlert> {
        return inventoryTracker.checkSpoilageRisk(inventory)
    }

    // ═══════════════════════════════════════════════════════════════
    // SUPPLIER COMPARISON
    // ═══════════════════════════════════════════════════════════════

    /**
     * Compare suppliers for a specific item.
     */
    fun compareSuppliers(item: String, suppliers: List<SupplierPrice>): SupplierComparison {
        return supplierComparator.compare(item, suppliers)
    }

    // ═══════════════════════════════════════════════════════════════
    // PRICING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get pricing recommendation for an item.
     */
    fun getPricingRecommendation(
        item: String,
        costBasis: Double,
        currentPrice: Double = 0.0,
        marketAverage: Double = 0.0,
        dailySalesVelocity: Double = 0.0,
        daysUntilExpiry: Int? = null
    ): PricingRecommendation {
        return pricingAdvisor.recommend(
            item, costBasis, currentPrice, marketAverage,
            dailySalesVelocity, daysUntilExpiry
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // BUDGETING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Create a budget from expected income.
     */
    fun createBudget(monthlyIncome: Double, period: String = "monthly"): BudgetPlan {
        return budgetCreator.createFromIncome(monthlyIncome, period = period)
    }

    /**
     * Create a budget from voice input.
     */
    fun createBudgetFromVoice(voiceInput: String, monthlyIncome: Double): BudgetPlan {
        return budgetCreator.createFromVoice(voiceInput, monthlyIncome)
    }

    /**
     * Track spending against budget.
     */
    fun trackBudgetSpending(
        budget: BudgetPlan,
        transactions: List<Transaction>
    ): BudgetPlan {
        return budgetCreator.trackSpending(budget, transactions)
    }

    // ═══════════════════════════════════════════════════════════════
    // FRAUD DETECTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Analyze transactions for fraud indicators.
     */
    fun detectFraud(
        recentTransactions: List<Transaction>,
        historicalTransactions: List<Transaction> = emptyList()
    ): FraudReport {
        return fraudDetector.analyze(recentTransactions, historicalTransactions)
    }

    // ═══════════════════════════════════════════════════════════════
    // TAX TRACKING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calculate tax liability for a period.
     */
    fun calculateTax(
        transactions: List<Transaction>,
        periodStart: Long,
        periodEnd: Long,
        obligation: TaxObligation = TaxObligation.TURNOVER_TAX
    ): TaxReport {
        return taxTracker.calculateTax(transactions, periodStart, periodEnd, obligation)
    }

    /**
     * Get tax optimization tips.
     */
    fun getTaxTips(transactions: List<Transaction>): List<String> {
        return taxTracker.getOptimizationTips(transactions)
    }
}
