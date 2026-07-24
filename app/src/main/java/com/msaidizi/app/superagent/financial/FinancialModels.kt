package com.msaidizi.superagent.financial

// ═══════════════════════════════════════════════════════════════
// FINANCIAL MODULE — Data Models
// ═══════════════════════════════════════════════════════════════
// All data classes and enums used across the financial module.
// ═══════════════════════════════════════════════════════════════

/**
 * Transaction types supported by the financial module.
 */
enum class TransactionType {
    SALE,
    PURCHASE,
    EXPENSE,
    WITHDRAWAL,
    DEPOSIT,
    FEE,
    REFUND,
    OTHER
}

/**
 * Payment methods supported.
 */
enum class PaymentMethod {
    CASH,
    MPESA,
    BANK_TRANSFER,
    CREDIT,
    OTHER
}

/**
 * Urgency levels for restock recommendations.
 */
enum class RestockUrgency {
    /** Stock out today or tomorrow */
    CRITICAL,
    /** Stock out within 2 days */
    HIGH,
    /** Stock out within 3 days */
    MEDIUM,
    /** More than 3 days of stock remaining */
    LOW
}

/**
 * Risk severity levels.
 */
enum class RiskSeverity {
    HIGH,
    MEDIUM,
    LOW,
    NONE
}

/**
 * Types of financial risk detected.
 */
enum class RiskType {
    REVENUE_DECLINE,
    MARGIN_COMPRESSION,
    CONCENTRATION_RISK,
    IRREGULAR_ACTIVITY,
    DUPLICATE_TRANSACTION,
    ANOMALOUS_AMOUNT,
    NONE
}

/**
 * KRA tax obligation types for Kenyan informal workers.
 */
enum class TaxObligation {
    /** Monthly Residential Rental Income Tax */
    RENTAL_INCOME,
    /** Turnover Tax (1% for businesses under KSh 25M) */
    TURNOVER_TAX,
    /** Withholding Tax */
    WITHHOLDING_TAX,
    /** Value Added Tax (if registered) */
    VAT,
    /** PAYE for employees */
    PAYE,
    /** Digital Service Tax */
    DIGITAL_SERVICE_TAX
}

/**
 * Perishability classification for inventory items.
 */
enum class Perishability {
    /** Lasts 1-2 days (e.g., fresh milk, cooked food) */
    HIGHLY_PERISHABLE,
    /** Lasts 3-7 days (e.g., fresh vegetables, bread) */
    PERISHABLE,
    /** Lasts 1-4 weeks (e.g., fruits, some dairy) */
    SEMI_PERISHABLE,
    /** Lasts months+ (e.g., grains, canned goods) */
    NON_PERISHABLE
}

// ═══════════════════════════════════════════════════════════════
// TRANSACTION MODELS
// ═══════════════════════════════════════════════════════════════

/**
 * A financial transaction recorded by voice or manual input.
 *
 * @property id Unique identifier (0 for new records)
 * @property type Transaction type (SALE, PURCHASE, EXPENSE, etc.)
 * @property item Item name as spoken by user
 * @property category Auto-classified category (e.g., "food", "transport")
 * @property quantity Quantity sold/purchased
 * @property unit Unit of measure ("pieces", "kg", "liters")
 * @property unitPrice Price per unit in KSh
 * @property totalAmount Total amount in KSh
 * @property costBasis Cost for profit calculation (for sales)
 * @property paymentMethod How the transaction was paid
 * @property supplier Supplier name (for purchases)
 * @property customer Customer name (if known)
 * @property locationLat GPS latitude
 * @property locationLng GPS longitude
 * @property locationName Human-readable location
 * @property notes Additional notes
 * @property confidence ASR confidence score (0.0-1.0)
 * @property language Language used for recording
 * @property createdAt Unix timestamp in seconds
 * @property syncedAt Unix timestamp when synced, null if not synced
 */
data class Transaction(
    val id: Long = 0,
    val type: TransactionType,
    val item: String,
    val category: String = "",
    val quantity: Double = 1.0,
    val unit: String = "pieces",
    val unitPrice: Double = 0.0,
    val totalAmount: Double,
    val costBasis: Double = 0.0,
    val paymentMethod: String = "cash",
    val supplier: String = "",
    val customer: String = "",
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val locationName: String = "",
    val notes: String = "",
    val confidence: Float = 1.0f,
    val language: String = "sw",
    val createdAt: Long = System.currentTimeMillis() / 1000,
    val syncedAt: Long? = null
) {
    /** Calculate margin for this transaction */
    val margin: Double
        get() = totalAmount - costBasis

    /** Calculate margin percentage */
    val marginPercent: Double
        get() = if (totalAmount > 0) margin / totalAmount else 0.0

    /** Whether this is an income transaction */
    val isIncome: Boolean
        get() = type == TransactionType.SALE || type == TransactionType.DEPOSIT

    /** Whether this is an expense transaction */
    val isExpense: Boolean
        get() = type == TransactionType.PURCHASE || type == TransactionType.EXPENSE ||
                type == TransactionType.FEE || type == TransactionType.WITHDRAWAL
}

/**
 * Inventory item with perishability tracking.
 *
 * @property itemName Item name (lowercase, normalized)
 * @property category Product category
 * @property currentStock Current quantity in stock
 * @property unit Unit of measure
 * @property unitCost Cost per unit
 * @property sellingPrice Selling price per unit
 * @property perishability How perishable this item is
 * @property shelfLifeDays Days before spoilage (0 if non-perishable)
 * @property lastRestockDate When last restocked (Unix timestamp)
 * @property expiryDate When current stock expires (Unix timestamp, null if unknown)
 * @property supplier Preferred supplier name
 * @property minimumStock Minimum stock level before alert
 */
data class InventoryItem(
    val itemName: String,
    val category: String = "",
    val currentStock: Double,
    val unit: String = "pieces",
    val unitCost: Double = 0.0,
    val sellingPrice: Double = 0.0,
    val perishability: Perishability = Perishability.NON_PERISHABLE,
    val shelfLifeDays: Int = 0,
    val lastRestockDate: Long = 0,
    val expiryDate: Long? = null,
    val supplier: String = "",
    val minimumStock: Double = 0.0
)

/**
 * Supplier pricing record for comparison.
 *
 * @property supplierName Supplier identifier
 * @property item Item name
 * @property price Price per unit in KSh
 * @property unit Unit of measure
 * @property minimumOrder Minimum order quantity
 * @property deliveryDays Delivery lead time in days
 * @property reliabilityScore Reliability rating (0.0-1.0)
 * @property lastUpdated When this price was last verified
 */
data class SupplierPrice(
    val supplierName: String,
    val item: String,
    val price: Double,
    val unit: String = "pieces",
    val minimumOrder: Double = 1.0,
    val deliveryDays: Int = 1,
    val reliabilityScore: Double = 0.8,
    val lastUpdated: Long = System.currentTimeMillis() / 1000
)

/**
 * Budget category allocation.
 *
 * @property category Budget category name
 * @property allocatedAmount Amount allocated in KSh
 * @property spentAmount Amount spent so far
 * @property period Budget period ("daily", "weekly", "monthly")
 */
data class BudgetCategory(
    val category: String,
    val allocatedAmount: Double,
    val spentAmount: Double = 0.0,
    val period: String = "monthly"
) {
    val remaining: Double get() = allocatedAmount - spentAmount
    val utilizationPercent: Double
        get() = if (allocatedAmount > 0) (spentAmount / allocatedAmount * 100) else 0.0
    val isOverBudget: Boolean get() = spentAmount > allocatedAmount
}

// ═══════════════════════════════════════════════════════════════
// RESULT MODELS
// ═══════════════════════════════════════════════════════════════

/**
 * Daily briefing output from CFOEngine.
 *
 * @property message Swahili voice message for the worker
 * @property todaySales Total sales today in KSh
 * @property todayExpenses Total expenses today in KSh
 * @property todayProfit Net profit today in KSh
 * @property yesterdaySales Yesterday's total sales
 * @property yesterdayProfit Yesterday's net profit
 * @property salesTrendPercent Sales change vs yesterday (%)
 * @property topSellingItem Best-selling item today
 * @property savingsRecommendation Recommended savings amount
 */
data class DailyBriefing(
    val message: String,
    val todaySales: Double,
    val todayExpenses: Double,
    val todayProfit: Double,
    val yesterdaySales: Double,
    val yesterdayProfit: Double,
    val salesTrendPercent: Int,
    val topSellingItem: String?,
    val savingsRecommendation: Double
)

/**
 * Cash flow forecast output.
 *
 * @property message Swahili voice message
 * @property currentCash Current cash on hand
 * @property dailyBurnRate Net daily cash burn (negative = positive cash flow)
 * @property daysRemaining Days until cash runs out
 * @property isHealthy Whether cash position is healthy
 * @property predictedIncome Expected income next 7 days
 * @property predictedExpenses Expected expenses next 7 days
 */
data class CashFlowForecast(
    val message: String,
    val currentCash: Double,
    val dailyBurnRate: Double,
    val daysRemaining: Int,
    val isHealthy: Boolean,
    val predictedIncome: Double = 0.0,
    val predictedExpenses: Double = 0.0
)

/**
 * Restock recommendation output.
 *
 * @property message Swahili voice message
 * @property items Items that need restocking
 */
data class RestockAdvice(
    val message: String,
    val items: List<RestockItem>
)

/**
 * Single restock item recommendation.
 *
 * @property item Item name
 * @property currentStock Current stock level
 * @property dailyVelocity Average daily sales
 * @property daysOfStockRemaining Days until stockout
 * @property suggestedQuantity How many to order
 * @property estimatedCost Estimated restock cost
 * @property urgency How urgent the restock is
 */
data class RestockItem(
    val item: String,
    val currentStock: Double,
    val dailyVelocity: Double,
    val daysOfStockRemaining: Double,
    val suggestedQuantity: Int,
    val estimatedCost: Double,
    val urgency: RestockUrgency
)

/**
 * Savings advice output.
 *
 * @property message Swahili voice message
 * @property recommendedAmount Recommended savings amount today
 * @property totalSaved Total saved so far
 * @property targetAmount Savings target
 * @property progressPercent Progress toward target (0-100)
 */
data class SavingsAdvice(
    val message: String,
    val recommendedAmount: Double,
    val totalSaved: Double,
    val targetAmount: Double,
    val progressPercent: Int
)

/**
 * Risk alert output.
 *
 * @property type Type of risk detected
 * @property severity How severe the risk is
 * @property message Swahili voice message
 * @property recommendation What to do about it
 */
data class RiskAlert(
    val type: RiskType,
    val severity: RiskSeverity,
    val message: String,
    val recommendation: String
)

/**
 * Profit & Loss statement.
 *
 * @property period Description of the period
 * @property totalRevenue Total sales revenue
 * @property totalCostOfGoods Cost of goods sold
 * @property grossProfit Revenue minus COGS
 * @property totalExpenses Operating expenses
 * @property netProfit Gross profit minus expenses
 * @property grossMarginPercent Gross margin as percentage
 * @property netMarginPercent Net margin as percentage
 * @property transactionCount Number of transactions in period
 * @property message Swahili voice summary
 */
data class PnLStatement(
    val period: String,
    val totalRevenue: Double,
    val totalCostOfGoods: Double,
    val grossProfit: Double,
    val totalExpenses: Double,
    val netProfit: Double,
    val grossMarginPercent: Double,
    val netMarginPercent: Double,
    val transactionCount: Int,
    val message: String
)

/**
 * Supplier comparison result.
 *
 * @property item Item being compared
 * @property suppliers Ranked list of suppliers (best first)
 * @property message Swahili voice recommendation
 */
data class SupplierComparison(
    val item: String,
    val suppliers: List<SupplierRanking>,
    val message: String
)

/**
 * Single supplier ranking in a comparison.
 *
 * @property supplierName Supplier name
 * @property price Price per unit
 * @property deliveryDays Lead time
 * @property reliabilityScore Reliability (0.0-1.0)
 * @property overallScore Composite score (higher is better)
 * @property savingsVsAverage Savings compared to average price
 */
data class SupplierRanking(
    val supplierName: String,
    val price: Double,
    val deliveryDays: Int,
    val reliabilityScore: Double,
    val overallScore: Double,
    val savingsVsAverage: Double
)

/**
 * Pricing recommendation.
 *
 * @property item Item name
 * @property currentPrice Current selling price
 * @property recommendedPrice Recommended price
 * @property costBasis Cost per unit
 * @property expectedMargin Expected margin at recommended price
 * @property marketAverage Average market price
 * @property message Swahili voice recommendation
 */
data class PricingRecommendation(
    val item: String,
    val currentPrice: Double,
    val recommendedPrice: Double,
    val costBasis: Double,
    val expectedMargin: Double,
    val marketAverage: Double,
    val message: String
)

/**
 * Budget plan output.
 *
 * @property categories Budget category allocations
 * @property totalIncome Expected total income
 * @property totalAllocated Total allocated across categories
 * @property surplus Shortfall or surplus
 * @property message Swahili voice summary
 */
data class BudgetPlan(
    val categories: List<BudgetCategory>,
    val totalIncome: Double,
    val totalAllocated: Double,
    val surplus: Double,
    val message: String
)

/**
 * Fraud/anomaly detection result.
 *
 * @property anomalies List of detected anomalies
 * @property duplicateSuspects Suspected duplicate transactions
 * @property message Swahili voice warning (if any)
 */
data class FraudReport(
    val anomalies: List<AnomalyDetection>,
    val duplicateSuspects: List<DuplicateSuspect>,
    val message: String
)

/**
 * Single anomaly detection.
 *
 * @property transaction The anomalous transaction
 * @property reason Why it's flagged
 * @property zScore Statistical Z-score (how far from normal)
 * @property severity How severe the anomaly is
 */
data class AnomalyDetection(
    val transaction: Transaction,
    val reason: String,
    val zScore: Double,
    val severity: RiskSeverity
)

/**
 * Suspected duplicate transaction.
 *
 * @property transaction1 First transaction
 * @property transaction2 Second transaction
 * @property similarityScore How similar they are (0.0-1.0)
 * @property reason Why they're suspected duplicates
 */
data class DuplicateSuspect(
    val transaction1: Transaction,
    val transaction2: Transaction,
    val similarityScore: Double,
    val reason: String
)

/**
 * Tax tracking result for KRA compliance.
 *
 * @property period Tax period description
 * @property totalRevenue Total revenue for the period
 * @property totalExpenses Total expenses for the period
 * @property estimatedTax Estimated tax liability
 * @property deductibleExpenses List of deductible expense categories
 * @property obligation The applicable tax obligation
 * @property dueDate When tax is due (Unix timestamp)
 * @property message Swahili voice summary
 */
data class TaxReport(
    val period: String,
    val totalRevenue: Double,
    val totalExpenses: Double,
    val estimatedTax: Double,
    val deductibleExpenses: Map<String, Double>,
    val obligation: TaxObligation,
    val dueDate: Long,
    val message: String
)

/**
 * Spoilage alert for perishable inventory.
 *
 * @property item Inventory item at risk
 * @property quantityAtRisk Quantity that may spoil
 * @property estimatedLoss Estimated financial loss
 * @property hoursUntilExpiry Hours until expiry
 * @property recommendation What to do (discount, donate, etc.)
 */
data class SpoilageAlert(
    val item: InventoryItem,
    val quantityAtRisk: Double,
    val estimatedLoss: Double,
    val hoursUntilExpiry: Double,
    val recommendation: String
)

/**
 * Recorded transaction result after voice input processing.
 *
 * @property transaction The parsed and recorded transaction
 * @property confirmationMessage Swahili voice confirmation
 * @property dataCompleteness How complete the extracted data is (0.0-1.0)
 * @property followUpQuestions Any follow-up questions needed
 */
data class RecordedTransaction(
    val transaction: Transaction,
    val confirmationMessage: String,
    val dataCompleteness: Float,
    val followUpQuestions: List<String>
)
