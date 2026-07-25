package com.msaidizi.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// ──────────────────────────────────────────────
// Domain Models
// ──────────────────────────────────────────────

@Serializable
data class BusinessProfile(
    val businessType: BusinessType = BusinessType.OTHER,
    val location: String = "",
    val products: List<String> = emptyList(),
    val operatingHours: OperatingHours = OperatingHours(),
    val language: Language = Language.KISWAHILI,
    val currency: String = "KES"
)

@Serializable
enum class BusinessType(
    val displayName: String,
    val swahiliName: String,
    val category: String
) {
    // ── Trade (7) ──
    MAMA_MBOGA("Vegetable vendor", "Mama Mboga", "Trade"),
    DUKA("Shop owner", "Dukawallah", "Trade"),
    MACHINGA("Hawker", "Machinga", "Trade"),
    MITUMBA("Second-hand clothes seller", "Mitumba", "Trade"),
    PHONE_ACCESSORIES("Phone accessories", "Vifaa vya simu", "Trade"),
    COSMETICS("Cosmetics seller", "Muuza urembo", "Trade"),
    HARDWARE_STORE("Hardware store", "Duka la vifaa", "Trade"),

    // ── Transport (5) ──
    BODA_BODA("Motorcycle taxi", "Boda Boda", "Transport"),
    TUK_TUK("Tuk-tuk driver", "Dereva tuk-tuk", "Transport"),
    MATATU("Matatu driver/conductor", "Matatu", "Transport"),
    CART_PUSHER("Cart pusher", "Mkokoteni", "Transport"),
    TRUCK_DRIVER("Truck driver", "Dereva wa lori", "Transport"),

    // ── Food (5) ──
    MAMA_LISHE("Food vendor", "Mama Lishe", "Food"),
    HOTELI("Restaurant", "Hoteli", "Food"),
    CHAPATI_SELLER("Chapati/bread seller", "Muuza chapati", "Food"),
    WATER_SELLER("Water seller", "Muuza maji", "Food"),
    TRADITIONAL_BREWER("Traditional brewer", "Mlevi wa kienyeji", "Food"),

    // ── Services (7) ──
    FUNDI("Repair technician", "Fundi", "Services"),
    SALON("Salon owner", "Mwenye salon", "Services"),
    BARBER("Barber", "Kinyozi", "Services"),
    MAMA_FUO("Laundry", "Mama Fuo", "Services"),
    TAILOR("Tailor", "Fundi Nguo", "Services"),
    SHOE_SHINER("Shoe shiner", "Muuza kiatu", "Services"),
    CAR_WASH("Car wash", "Car wash", "Services"),

    // ── Agriculture (4) ──
    MKULIMA("Farmer", "Mkulima", "Agriculture"),
    MVUVI("Fisherman", "Mvuvi", "Agriculture"),
    MFUGAJI("Livestock keeper", "Mfugaji", "Agriculture"),
    PRODUCE_BROKER("Produce broker", "Dalali", "Agriculture"),

    // ── Construction (4) ──
    MJENGO("Construction worker", "Mjengo", "Construction"),
    MASON("Mason", "Mjenzi", "Construction"),
    PLUMBER("Plumber", "Plumber", "Construction"),
    ELECTRICIAN("Electrician", "Mfundi umeme", "Construction"),

    // ── Digital (4) ──
    M_PESA("M-Pesa agent", "M-Pesa", "Digital"),
    CYBER_CAFE("Cyber cafe", "Cyber cafe", "Digital"),
    PHONE_REPAIR("Phone repair technician", "Fundi simu", "Digital"),
    SOCIAL_MEDIA_RESELLER("Social media reseller", "Muuza mtandaoni", "Digital"),

    // ── Artisans (4) ──
    JUA_KALI("Jua kali artisan", "Jua Kali", "Artisans"),
    BASKET_WEAVER("Basket weaver", "Mfumaji kikapu", "Artisans"),
    POTTER("Potter", "Mfinyanzi", "Artisans"),
    WELDER("Welder", "Mfundi welder", "Artisans"),

    // ── Fallback ──
    OTHER("Other", "Nyingine", "Other")
}

@Serializable
enum class Language(val code: String, val displayName: String) {
    KISWAHILI("sw", "Kiswahili"),
    ENGLISH("en", "English"),
    SHENG("sheng", "Sheng"),
    KIKUYU("ki", "Kikuyu"),
    LUO("luo", "Dholuo"),
    KALENJIN("kln", "Kalenjin")
}

@Serializable
data class OperatingHours(
    val openHour: Int = 6,
    val closeHour: Int = 20,
    val openDays: List<Int> = listOf(1, 2, 3, 4, 5, 6) // Mon-Sat
)

// ──────────────────────────────────────────────
// Room Entities
// ──────────────────────────────────────────────

@Entity(tableName = "sales")
data class SaleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val productName: String,
    val quantity: Double,
    val unitPrice: Double,
    val totalPrice: Double,
    val paymentMethod: String = "cash", // cash, mpesa, credit
    val customerId: Long? = null,
    val customerName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val notes: String? = null
)

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String,
    val unit: String, // kg, piece, bunch, litre
    val buyPrice: Double,
    val sellPrice: Double,
    val currentStock: Double = 0.0,
    val minStock: Double = 0.0,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String, // transport, rent, stock, utilities, misc
    val description: String,
    val amount: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val isRecurring: Boolean = false
)

@Entity(tableName = "customers")
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String? = null,
    val creditBalance: Double = 0.0,
    val totalPurchases: Double = 0.0,
    val lastPurchaseAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "daily_summaries")
data class DailySummaryEntity(
    @PrimaryKey val date: String, // YYYY-MM-DD
    val totalSales: Double,
    val totalExpenses: Double,
    val profit: Double,
    val transactionCount: Int,
    val topProduct: String? = null,
    val cashSales: Double = 0.0,
    val mpesaSales: Double = 0.0,
    val creditSales: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "stock_movements")
data class StockMovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val type: String, // purchase, sale, adjustment, spoilage
    val quantity: Double,
    val previousStock: Double,
    val newStock: Double,
    val notes: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

// ──────────────────────────────────────────────
// Service Business Models
// For service workers: fundi, salon, barber, tailor, car wash, etc.
// ──────────────────────────────────────────────

/**
 * A service offered by a service worker.
 * Unlike products, services have a base price and category but no stock.
 */
@Serializable
data class ServiceItem(
    val name: String,           // "Phone screen repair", "Hair braiding"
    val basePrice: Double,      // Standard price
    val category: String        // "repair", "beauty", "cleaning", "construction", "tailoring"
)

/**
 * A completed service transaction.
 * Tracks labour vs materials separately for profit analysis.
 */
@Serializable
data class ServiceTransaction(
    val serviceName: String,      // What service was performed
    val labourCost: Double,       // Worker's time/skill
    val materialsCost: Double,    // Parts/products used
    val totalCharged: Double,     // What customer paid
    val customerName: String?,
    val timestamp: Long
)

/**
 * Room entity for persisting service transactions.
 */
@Entity(tableName = "service_transactions")
data class ServiceTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serviceName: String,
    val serviceCategory: String,    // repair, beauty, cleaning, construction, tailoring
    val labourCost: Double,
    val materialsCost: Double,
    val totalCharged: Double,
    val customerName: String? = null,
    val paymentMethod: String = "cash", // cash, mpesa, credit
    val timestamp: Long = System.currentTimeMillis(),
    val notes: String? = null
)

/**
 * Room entity for the service menu (services offered by the business).
 */
@Entity(tableName = "service_menu")
data class ServiceMenuEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String,           // repair, beauty, cleaning, construction, tailoring
    val basePrice: Double,
    val isActive: Boolean = true,
    val usageCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

// ──────────────────────────────────────────────
// Conversation & Memory Models
// ──────────────────────────────────────────────

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: String, // user, assistant, system
    val content: String,
    val intent: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "knowledge_entries")
data class KnowledgeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String, // vocab, dialect, pattern, advice
    val key: String,
    val value: String,
    val confidence: Float = 1.0f,
    val usageCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

// ──────────────────────────────────────────────
// User Profile
// ──────────────────────────────────────────────

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Int = 1, // singleton
    val msaidiziName: String = "Msaidizi",
    val userName: String = "",
    val businessProfile: String = "", // JSON serialized BusinessProfile
    val isOnboarded: Boolean = false,
    val voiceEnabled: Boolean = true,
    val preferredLanguage: String = "sw",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

// ──────────────────────────────────────────────
// Debt Tracking Models
// ──────────────────────────────────────────────

/**
 * A debt record: someone owes the user money.
 * Tracks the original amount, outstanding balance, product/service sold,
 * and due date for aging analysis.
 */
@Entity(tableName = "debts")
data class DebtEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerName: String,
    val customerPhone: String? = null,
    val amount: Double,              // Original debt amount
    val outstandingBalance: Double,   // Remaining balance
    val product: String,             // What was sold (product or service name)
    val notes: String? = null,
    val status: String = "active",   // active, settled, written_off
    val dueDate: Long? = null,       // When payment was expected
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * A repayment against a debt.
 * Supports partial payments — multiple repayments per debt.
 */
@Entity(
    tableName = "debt_repayments",
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = DebtEntity::class,
            parentColumns = ["id"],
            childColumns = ["debtId"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ]
)
data class DebtRepaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val debtId: Long,
    val amount: Double,
    val paymentMethod: String = "cash", // cash, mpesa, other
    val notes: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Aging bucket summary for debt analysis.
 */
data class AgingBucket(
    val bucket: String,      // "current", "30_days", "60_days", "90_plus"
    val debtCount: Int,
    val totalAmount: Double
)

/**
 * Customer debt summary for credit decisions.
 */
data class CustomerDebtSummary(
    val customerName: String,
    val totalDebts: Int,
    val totalAmount: Double,
    val totalOutstanding: Double,
    val totalRepaid: Double,
    val onTimePayments: Int,
    val latePayments: Int,
    val averageRepaymentDays: Double?
)

// ──────────────────────────────────────────────
// Customer Insights Models
// ──────────────────────────────────────────────

@Entity(tableName = "customer_profiles")
data class CustomerProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workerId: String,
    val customerKey: String,            // phone number or normalized name
    val customerName: String? = null,   // display name

    // Visit metrics
    val totalVisits: Int = 0,
    val visitsThisMonth: Int = 0,
    val avgVisitsPerMonth: Double = 0.0,

    // Spend metrics
    val totalSpend: Double = 0.0,
    val spendThisMonth: Double = 0.0,
    val avgSpendPerVisit: Double = 0.0,

    // Recency
    val firstVisit: String? = null,     // YYYY-MM-DD
    val lastVisit: String? = null,      // YYYY-MM-DD
    val daysSinceLastVisit: Int = 0,

    // Segmentation
    val segment: String = "new",        // vip, regular, occasional, lapsed, new
    val segmentSince: String? = null,

    // Credit
    val creditOutstanding: Double = 0.0,
    val creditLimit: Double = 0.0,
    val creditReliability: Double = 1.0, // 0.0 to 1.0

    // Preferences (top 3 products by frequency, JSON array)
    val topProductsJson: String = "[]",

    // Revenue contribution
    val revenuePct: Double = 0.0,
    val revenueRank: Int = 0,

    val updatedAt: Long = System.currentTimeMillis()
)

// ──────────────────────────────────────────────
// Restock Threshold Models
// ──────────────────────────────────────────────

/**
 * Custom restock threshold per product.
 * Used by AutoRestock to trigger alerts at a user-defined stock level
 * rather than relying solely on the product's minStock.
 */
@Entity(
    tableName = "restock_thresholds",
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ],
    indices = [androidx.room.Index(value = ["productId"], unique = true)]
)
data class RestockThresholdEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val threshold: Double,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "customer_visits",
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = CustomerProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ]
)
data class CustomerVisitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workerId: String,
    val customerKey: String,
    val profileId: Long = 0,
    val visitDate: String,              // YYYY-MM-DD
    val txnId: Long? = null,            // link to SaleEntity
    val amount: Double = 0.0,
    val productsJson: String = "[]",    // [{name, qty, price}]
    val paymentMethod: String? = null,  // cash, mpesa, credit
    val createdAt: Long = System.currentTimeMillis()
)

data class CustomerSegmentSummary(
    val segment: String,
    val count: Int,
    val totalSpend: Double,
    val avgSpend: Double
)

data class CustomerChurnRisk(
    val customerKey: String,
    val customerName: String?,
    val segment: String,
    val daysSinceLastVisit: Int,
    val avgVisitsPerMonth: Double,
    val totalSpend: Double
)

// ──────────────────────────────────────────────
// Bulk Order Models
// For coordinating group buying among workers
// ──────────────────────────────────────────────

enum class BulkOrderStatus {
    OPEN,           // Accepting commitments
    MINIMUM_MET,    // Enough quantity to negotiate
    NEGOTIATING,    // Talking to supplier
    CONFIRMED,      // Price agreed, ready to pay
    DISTRIBUTING,   // Bulk delivery being split
    COMPLETED,      // All delivered and paid
    CANCELLED,      // Cancelled by creator
    EXPIRED         // Deadline passed without minimum
}

enum class PaymentStatus {
    PENDING,        // Not yet paid
    IN_ESCROW,      // Paid into M-Pesa escrow
    RELEASED,       // Released to supplier after delivery
    REFUNDED        // Refunded (order cancelled)
}

enum class EscrowType {
    DEPOSIT,        // Worker pays in
    RELEASE,        // Paid to supplier
    REFUND          // Returned to worker
}

@Entity(tableName = "bulk_orders")
data class BulkOrderEntity(
    @PrimaryKey val orderId: String,
    val product: String,
    val unit: String,
    val targetPricePerUnit: Double,
    val totalQuantityNeeded: Double,
    val totalQuantityCommitted: Double = 0.0,
    val minimumQuantity: Double,
    val area: String,
    val creatorWorkerId: String,
    val creatorName: String,
    val creatorPhone: String,
    val status: String,
    val deadline: Long,
    val supplierName: String?,
    val agreedPricePerUnit: Double?,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val needsSync: Boolean = true
)

@Entity(tableName = "bulk_commitments",
    foreignKeys = [ForeignKey(
        entity = BulkOrderEntity::class,
        parentColumns = ["orderId"],
        childColumns = ["orderId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["orderId"]), Index(value = ["workerId"])]
)
data class BulkCommitmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: String,
    val workerId: String,
    val workerName: String,
    val phone: String,
    val quantity: Double,
    val amountPaid: Double = 0.0,
    val paymentStatus: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val needsSync: Boolean = true
)

@Entity(tableName = "bulk_escrow",
    foreignKeys = [ForeignKey(
        entity = BulkOrderEntity::class,
        parentColumns = ["orderId"],
        childColumns = ["orderId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["orderId"])]
)
data class BulkEscrowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: String,
    val workerId: String,
    val amount: Double,
    val type: String,           // deposit, release, refund
    val mpesaReference: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val needsSync: Boolean = true
)

// ──────────────────────────────────────────────
// Chama (Savings Group) Models
// ──────────────────────────────────────────────

/**
 * A chama — informal savings group, the #1 financial vehicle for
 * informal workers in Kenya (KES 300B+ annually).
 * Members contribute a fixed amount on a weekly or monthly basis
 * and take turns receiving the pooled pot.
 */
@Entity(tableName = "chamas")
data class ChamaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val contributionAmount: Double,       // KES per cycle
    val frequency: String = "monthly",     // weekly or monthly
    val savingsTarget: Double = 0.0,       // optional group goal
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * A member of a chama.
 * [rotationOrder] determines when they receive the pot (1-based).
 */
@Entity(
    tableName = "chama_members",
    foreignKeys = [ForeignKey(
        entity = ChamaEntity::class,
        parentColumns = ["id"],
        childColumns = ["chamaId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["chamaId"])]
)
data class ChamaMemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chamaId: Long,
    val name: String,
    val phone: String,
    val rotationOrder: Int,          // 1-based position in payout rotation
    val isActive: Boolean = true,
    val joinedAt: Long = System.currentTimeMillis()
)

/**
 * A single contribution made by a chama member.
 * Tracks M-Pesa reference and any penalty for late payment.
 */
@Entity(
    tableName = "chama_contributions",
    foreignKeys = [ForeignKey(
        entity = ChamaEntity::class,
        parentColumns = ["id"],
        childColumns = ["chamaId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["chamaId"])]
)
data class ChamaContributionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chamaId: Long,
    val memberId: Long,
    val memberName: String,
    val amount: Double,
    val mpesaRef: String? = null,     // M-Pesa transaction reference
    val penalty: Double = 0.0,       // late payment penalty
    val date: String,                // YYYY-MM-DD
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * A payout from the chama pot to a member.
 * Represents one cycle of the rotation.
 */
@Entity(
    tableName = "chama_payouts",
    foreignKeys = [ForeignKey(
        entity = ChamaEntity::class,
        parentColumns = ["id"],
        childColumns = ["chamaId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["chamaId"])]
)
data class ChamaPayoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chamaId: Long,
    val recipientId: Long,
    val recipientName: String,
    val amount: Double,
    val cycleNumber: Int,
    val timestamp: Long = System.currentTimeMillis()
)

// ──────────────────────────────────────────────
// UI State Models
// ──────────────────────────────────────────────

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isVoice: Boolean = false,
    val isProcessing: Boolean = false
)

enum class MessageRole { USER, ASSISTANT, SYSTEM }

data class DashboardState(
    val todaySales: Double = 0.0,
    val todayExpenses: Double = 0.0,
    val todayProfit: Double = 0.0,
    val transactionCount: Int = 0,
    val lowStockProducts: List<ProductEntity> = emptyList(),
    val recentSales: List<SaleEntity> = emptyList(),
    val greeting: String = ""
)

data class VoiceState(
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val isProcessing: Boolean = false,
    val partialText: String = "",
    val error: String? = null
)
