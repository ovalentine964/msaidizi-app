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
