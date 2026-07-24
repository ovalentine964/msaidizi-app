package com.msaidizi.app.superagent.flow

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// ──────────────────────────────────────────────
// Core money flow models
// ──────────────────────────────────────────────

/** Represents money coming IN (revenue) */
data class Sale(
    val id: String,
    val productId: String,
    val productName: String,
    val amount: Double,        // KES
    val costOfGoods: Double,   // what the worker paid for the item
    val quantity: Int,
    val customerId: String? = null,
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val paymentMethod: PaymentMethod = PaymentMethod.CASH
)

enum class PaymentMethod {
    CASH, M_PESA, BANK, CREDIT, OTHER
}

/** Represents money going OUT (expenses) */
data class Expense(
    val id: String,
    val category: ExpenseCategory,
    val amount: Double,        // KES
    val description: String,
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val isRecurring: Boolean = false,
    val supplierId: String? = null
)

enum class ExpenseCategory {
    COGS,           // Cost of Goods Sold — stock purchases
    TRANSPORT,      // Pikipiki, matatu, delivery
    RENT,           // Stall rent, market fees
    WASTE,          // Spoiled goods, expired stock
    UTILITIES,      // Airtime, bundles, charging
    LABOR,          // Paying helpers
    PACKAGING,      // Bags, containers, wrapping
    TAX_FEES,       // Government fees, licenses
    OTHER
}

/** A product the worker sells */
data class Product(
    val id: String,
    val name: String,
    val nameSw: String,        // Swahili name
    val costPrice: Double,     // what worker pays per unit
    val sellingPrice: Double,  // what customer pays
    val unit: String,          // "piece", "kg", "litre", "packet"
    val category: String,
    val isPerishable: Boolean = false,
    val shelfLifeDays: Int? = null
)

/** Inventory snapshot */
data class InventoryItem(
    val productId: String,
    val productName: String,
    val currentStock: Int,
    val costPerUnit: Double,
    val sellingPricePerUnit: Double,
    val lastRestocked: LocalDate,
    val daysSinceRestock: Int,
    val averageDailySales: Double,
    val isDeadStock: Boolean = false,  // no sales in 14+ days
    val daysOfStockLeft: Double = if (averageDailySales > 0) currentStock / averageDailySales else 999.0
)

/** Customer record */
data class Customer(
    val id: String,
    val name: String,
    val phone: String? = null,
    val firstVisit: LocalDate,
    val lastVisit: LocalDate,
    val totalSpent: Double,
    val visitCount: Int,
    val isReturning: Boolean = visitCount > 1,
    val averageSpend: Double = if (visitCount > 0) totalSpent / visitCount else 0.0,
    val outstandingCredit: Double = 0.0  // dlana — money they owe
)

/** Supplier record */
data class Supplier(
    val id: String,
    val name: String,
    val nameSw: String,
    val phone: String? = null,
    val products: List<String>,           // product IDs they supply
    val averagePrice: Double,             // average price across products
    val marketAveragePrice: Double,       // what others charge
    val deliveryReliability: Double,      // 0.0-1.0 (percentage on-time)
    val qualityRating: Double,            // 0.0-1.0
    val lastOrderDate: LocalDate? = null,
    val alternativeSuppliers: List<String> = emptyList()  // names
)

// ──────────────────────────────────────────────
// Period & reporting models
// ──────────────────────────────────────────────

enum class ReportPeriod {
    TODAY, YESTERDAY, THIS_WEEK, LAST_WEEK,
    THIS_MONTH, LAST_MONTH, CUSTOM
}

data class DateRange(
    val start: LocalDate,
    val end: LocalDate
) {
    val days: Long = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1

    companion object {
        fun today(): DateRange {
            val now = LocalDate.now()
            return DateRange(now, now)
        }

        fun thisWeek(): DateRange {
            val now = LocalDate.now()
            val start = now.minusDays(now.dayOfWeek.value.toLong() - 1)
            return DateRange(start, now)
        }

        fun thisMonth(): DateRange {
            val now = LocalDate.now()
            return DateRange(now.withDayOfMonth(1), now)
        }
    }
}

// ──────────────────────────────────────────────
// Revenue models
// ──────────────────────────────────────────────

data class RevenueSummary(
    val period: DateRange,
    val totalRevenue: Double,
    val totalTransactions: Int,
    val revenueByProduct: Map<String, Double>,        // productName → KES
    val revenueByPaymentMethod: Map<PaymentMethod, Double>,
    val revenueByHour: Map<Int, Double>,               // hour(0-23) → KES
    val peakHour: Int,
    val peakDayRevenue: Double,
    val dailyAverage: Double,
    val trendPercent: Double,                          // vs previous period
    val trendDirection: TrendDirection
)

data class RevenueByProduct(
    val productId: String,
    val productName: String,
    val totalRevenue: Double,
    val totalQuantity: Int,
    val totalTransactions: Int,
    val percentOfTotal: Double
)

// ──────────────────────────────────────────────
// Cost models
// ──────────────────────────────────────────────

data class CostSummary(
    val period: DateRange,
    val totalCosts: Double,
    val costsByCategory: Map<ExpenseCategory, Double>,
    val cogs: Double,
    val transport: Double,
    val rent: Double,
    val waste: Double,
    val utilities: Double,
    val labor: Double,
    val otherCosts: Double,
    val dailyAverage: Double,
    val trendPercent: Double,
    val topExpense: ExpenseCategory,
    val costBreakdownPercent: Map<ExpenseCategory, Double>  // category → % of total
)

// ──────────────────────────────────────────────
// Profit models
// ──────────────────────────────────────────────

data class ProfitSummary(
    val period: DateRange,
    val totalRevenue: Double,
    val totalCosts: Double,
    val grossProfit: Double,           // revenue - COGS
    val netProfit: Double,             // revenue - ALL costs
    val grossMargin: Double,           // grossProfit / revenue * 100
    val netMargin: Double,             // netProfit / revenue * 100
    val dailyAverageProfit: Double,
    val trendPercent: Double,          // vs previous period
    val trendDirection: TrendDirection,
    val comparisonNote: String         // human-readable comparison
)

enum class TrendDirection {
    UP, DOWN, FLAT
}

// ──────────────────────────────────────────────
// Inventory models
// ──────────────────────────────────────────────

data class InventorySummary(
    val totalStockValue: Double,       // cost value of all stock
    val totalItems: Int,
    val products: List<InventoryItem>,
    val deadStock: List<InventoryItem>,          // no sales in 14+ days
    val deadStockValue: Double,                  // KES tied up in dead stock
    val lowStockAlerts: List<InventoryItem>,     // < 3 days of stock left
    val averageTurnoverDays: Double,             // how fast stock sells
    val reorderSuggestions: List<ReorderSuggestion>
)

data class ReorderSuggestion(
    val productId: String,
    val productName: String,
    val currentStock: Int,
    val suggestedOrderQuantity: Int,
    val estimatedCost: Double,
    val urgency: ReorderUrgency,
    val reason: String,
    val reasonSw: String  // Swahili
)

enum class ReorderUrgency {
    CRITICAL,   // stock out risk in 1-2 days
    HIGH,       // stock out risk in 3-5 days
    MEDIUM,     // stock out risk in 6-10 days
    LOW         // comfortable for now
}

// ──────────────────────────────────────────────
// Customer models
// ──────────────────────────────────────────────

data class CustomerSummary(
    val period: DateRange,
    val totalCustomers: Int,
    val newCustomers: Int,
    val returningCustomers: Int,
    val averageSpendPerCustomer: Double,
    val averageSpendPerVisit: Double,
    val topCustomers: List<Customer>,
    val customersWithCredit: List<Customer>,
    val totalOutstandingCredit: Double,
    val retentionRate: Double  // % of customers who came back
)

// ──────────────────────────────────────────────
// Supplier models
// ──────────────────────────────────────────────

data class SupplierSummary(
    val suppliers: List<Supplier>,
    val totalSpendBySupplier: Map<String, Double>,
    val priceComparisonAlerts: List<PriceAlert>,
    val reliabilityAlerts: List<ReliabilityAlert>
)

data class PriceAlert(
    val productName: String,
    val currentSupplier: String,
    val currentPrice: Double,
    val cheaperSupplier: String,
    val cheaperPrice: Double,
    val savings: Double,              // KES per unit
    val savingsPercent: Double,
    val message: String,
    val messageSw: String
)

data class ReliabilityAlert(
    val supplierName: String,
    val reliabilityScore: Double,
    val message: String,
    val messageSw: String
)

// ──────────────────────────────────────────────
// Money Flow (the big picture)
// ──────────────────────────────────────────────

data class MoneyFlowReport(
    val period: DateRange,
    val moneyIn: MoneyFlowCategory,         // total revenue
    val moneyOut: MoneyFlowBreakdown,        // where it went
    val moneyLeft: Double,                   // profit (what worker keeps)
    val moneyTied: MoneyTiedUp,             // money locked in stock/credit
    val summaryEnglish: String,
    val summarySwahili: String,
    val voiceSummary: String,               // optimized for TTS
    val recommendations: List<Recommendation>
)

data class MoneyFlowCategory(
    val label: String,
    val labelSw: String,
    val amount: Double,
    val percentOfTotal: Double
)

data class MoneyFlowBreakdown(
    val total: Double,
    val categories: List<MoneyFlowCategory>
)

data class MoneyTiedUp(
    val inStock: Double,
    val inCredit: Double,           // money customers owe
    val total: Double
)

data class Recommendation(
    val type: RecommendationType,
    val priority: RecommendationPriority,
    val title: String,
    val titleSw: String,
    val detail: String,
    val detailSw: String,
    val potentialSavings: Double? = null  // KES if applicable
)

enum class RecommendationType {
    COST_REDUCTION,     // lower expenses
    REVENUE_BOOST,      // increase sales
    INVENTORY,          // stock management
    CUSTOMER,           // customer retention
    SUPPLIER            // better deals
}

enum class RecommendationPriority {
    CRITICAL, HIGH, MEDIUM, LOW
}
