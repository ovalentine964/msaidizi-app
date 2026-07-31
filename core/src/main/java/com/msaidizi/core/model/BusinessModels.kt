package com.msaidizi.core.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
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

// ══════════════════════════════════════════════════════════
// 12 Worker Archetypes — Primary classification system
// ══════════════════════════════════════════════════════════

@Serializable
enum class ArchetypeType(
    val displayName: String,
    val swahiliName: String,
    val icon: String
) {
    VENDOR("Vendor", "Mchina/Mchuuzi", "🏪"),
    FOOD_SERVICE("Food Service", "Mama Lishe", "🍳"),
    ARTISAN("Artisan/Maker", "Fundi/Mfua", "🔧"),
    SERVICE_PROVIDER("Service Provider", "Mfundi", "✂️"),
    TRANSPORT_OPERATOR("Transport Operator", "Dereva/Msafiri", "🏍️"),
    CROP_FARMER("Crop Farmer", "Mkulima", "🌾"),
    LIVESTOCK_KEEPER("Livestock Keeper", "Mfugaji", "🐄"),
    FISHER("Fisher", "Mvuvi", "🎣"),
    AGENT_BROKER("Agent/Broker", "Dalali/Agent", "🤝"),
    DIGITAL_WORKER("Digital Worker", "Mfanyi Mtandaoni", "💻"),
    CASUAL_LABORER("Casual Laborer", "Kibarua", "👷"),
    COMMUNITY_CARE_WORKER("Community/Care Worker", "Mhudumu", "🤲")
}

/**
 * Sub-type configuration within an archetype.
 * Workers are classified by archetype first, then refined by sub-type.
 */
@Serializable
data class SubTypeConfig(
    val workerTypeId: String,          // Maps to taxonomy ID (e.g., "T-001")
    val displayName: String,           // "Mama Mboga"
    val localName: String,             // "Mama Mboga"
    val inventoryType: String? = null, // "perishable_produce", "non_perishable", etc.
    val incomePattern: String = "daily", // daily | weekly | seasonal | project
    val perishableInventory: Boolean = false,
    val vehicleOwned: Boolean? = null, // null for non-transport
    val employees: Int = 0,
    val customFields: Map<String, String> = emptyMap()
)

@Serializable
data class WorkerArchetypeProfile(
    val primaryArchetype: ArchetypeType,
    val secondaryArchetypes: List<ArchetypeType> = emptyList(),
    val subTypes: Map<ArchetypeType, SubTypeConfig> = emptyMap(),
    val customTags: List<String> = emptyList()
)

// ══════════════════════════════════════════════════════════
// BusinessType enum — Sub-type registry within archetypes
// ══════════════════════════════════════════════════════════

@Serializable
enum class BusinessType(
    val displayName: String,
    val swahiliName: String,
    val category: String,
    val archetype: ArchetypeType
) {
    // ── Trade (Vendor archetype) ──
    MAMA_MBOGA("Vegetable vendor", "Mama Mboga", "Trade", ArchetypeType.VENDOR),
    DUKA("Shop owner", "Dukawallah", "Trade", ArchetypeType.VENDOR),
    MACHINGA("Hawker", "Machinga", "Trade", ArchetypeType.VENDOR),
    MITUMBA("Second-hand clothes seller", "Mitumba", "Trade", ArchetypeType.VENDOR),
    PHONE_ACCESSORIES("Phone accessories", "Vifaa vya simu", "Trade", ArchetypeType.VENDOR),
    COSMETICS("Cosmetics seller", "Muuza urembo", "Trade", ArchetypeType.VENDOR),
    HARDWARE_STORE("Hardware store", "Duka la vifaa", "Trade", ArchetypeType.VENDOR),
    FRUIT_VENDOR("Fruit vendor", "Muuza matunda", "Trade", ArchetypeType.VENDOR),
    CEREAL_SELLER("Cereal seller", "Mchina", "Trade", ArchetypeType.VENDOR),
    FISH_VENDOR("Fish vendor", "Muuza samaki", "Trade", ArchetypeType.VENDOR),
    MEAT_VENDOR("Meat vendor", "Mchinjaji", "Trade", ArchetypeType.VENDOR),
    EGG_VENDOR("Egg vendor", "Muuza mayai", "Trade", ArchetypeType.VENDOR),
    FABRIC_SELLER("Fabric seller", "Muuza vitambaa", "Trade", ArchetypeType.VENDOR),
    SHOE_SELLER("Shoe seller", "Muuza viatu", "Trade", ArchetypeType.VENDOR),
    STATIONERY_SELLER("Stationery seller", "Muuza vifaa vya ofisi", "Trade", ArchetypeType.VENDOR),
    MARKET_STALL("Market stall owner", "Mwenye kibanda", "Trade", ArchetypeType.VENDOR),
    WHOLESALE_TRADER("Wholesale trader", "Mchina jumla", "Trade", ArchetypeType.VENDOR),
    MOBILE_HAWKER("Mobile hawkler", "Machinga wa kutembea", "Trade", ArchetypeType.VENDOR),
    HERB_SELLER("Herb/spice seller", "Muuza viungo", "Trade", ArchetypeType.VENDOR),
    UTENSIL_SELLER("Utensil seller", "Muuza vyombo", "Trade", ArchetypeType.VENDOR),
    ELECTRONICS_VENDOR("Electronics vendor", "Muuza elektroniki", "Trade", ArchetypeType.VENDOR),

    // ── Transport (Transport Operator archetype) ──
    BODA_BODA("Motorcycle taxi", "Boda Boda", "Transport", ArchetypeType.TRANSPORT_OPERATOR),
    TUK_TUK("Tuk-tuk driver", "Dereva tuk-tuk", "Transport", ArchetypeType.TRANSPORT_OPERATOR),
    MATATU("Matatu driver/conductor", "Matatu", "Transport", ArchetypeType.TRANSPORT_OPERATOR),
    CART_PUSHER("Cart pusher", "Mkokoteni", "Transport", ArchetypeType.TRANSPORT_OPERATOR),
    TRUCK_DRIVER("Truck driver", "Dereva wa lori", "Transport", ArchetypeType.TRANSPORT_OPERATOR),
    DELIVERY_RIDER("Delivery rider", "Msafirishaji", "Transport", ArchetypeType.TRANSPORT_OPERATOR),
    AUTO_RICKSHAW("Auto-rickshaw driver", "Bajaj", "Transport", ArchetypeType.TRANSPORT_OPERATOR),
    MATATU_OWNER("Matatu owner", "Mwenye matatu", "Transport", ArchetypeType.TRANSPORT_OPERATOR),
    INFORMAL_TAXI("Informal taxi driver", "Dereva teksi", "Transport", ArchetypeType.TRANSPORT_OPERATOR),
    RIDE_HAIL_DRIVER("Ride-hail driver", "Dereva wa app", "Transport", ArchetypeType.TRANSPORT_OPERATOR),

    // ── Food (Food Service archetype) ──
    MAMA_LISHE("Food vendor", "Mama Lishe", "Food", ArchetypeType.FOOD_SERVICE),
    HOTELI("Restaurant", "Hoteli", "Food", ArchetypeType.FOOD_SERVICE),
    CHAPATI_SELLER("Chapati/bread seller", "Muuza chapati", "Food", ArchetypeType.FOOD_SERVICE),
    WATER_SELLER("Water seller", "Muuza maji", "Food", ArchetypeType.VENDOR),
    TRADITIONAL_BREWER("Traditional brewer", "Mlevi wa kienyeji", "Food", ArchetypeType.FOOD_SERVICE),
    ROASTED_MAIZE("Roasted maize seller", "Muuza mahindi", "Food", ArchetypeType.FOOD_SERVICE),
    MANDAZI_SELLER("Mandazi seller", "Muuza mandazi", "Food", ArchetypeType.FOOD_SERVICE),
    JUICE_SELLER("Juice seller", "Muuza juisi", "Food", ArchetypeType.FOOD_SERVICE),
    TEA_SELLER("Tea seller", "Muuza chai", "Food", ArchetypeType.FOOD_SERVICE),
    BAKER("Baker", "Mkate wa keki", "Food", ArchetypeType.FOOD_SERVICE),
    CAKE_DECORATOR("Cake decorator", "Mchapaji keki", "Food", ArchetypeType.FOOD_SERVICE),
    CONFECTIONER("Confectioner", "Muuza vitafunwa", "Food", ArchetypeType.FOOD_SERVICE),
    SNACK_MAKER("Snack maker", "Muuza vitafunwa", "Food", ArchetypeType.FOOD_SERVICE),
    FRUIT_SALAD("Fruit salad seller", "Muuza matunda", "Food", ArchetypeType.FOOD_SERVICE),
    NYAMA_CHOMA("Nyama choma vendor", "Muuza nyama choma", "Food", ArchetypeType.FOOD_SERVICE),
    PORRIDGE_SELLER("Porridge seller", "Muuza uji", "Food", ArchetypeType.FOOD_SERVICE),
    MUTURA_SELLER("Mutura seller", "Muuza mutura", "Food", ArchetypeType.FOOD_SERVICE),
    SUGARCANE_VENDOR("Sugarcane vendor", "Muuza miwa", "Food", ArchetypeType.FOOD_SERVICE),
    KIOSK_BAR("Kiosk bar owner", "Mwenye baa", "Food", ArchetypeType.FOOD_SERVICE),

    // ── Services (Service Provider archetype) ──
    FUNDI("Repair technician", "Fundi", "Services", ArchetypeType.SERVICE_PROVIDER),
    SALON("Salon owner", "Mwenye salon", "Services", ArchetypeType.SERVICE_PROVIDER),
    BARBER("Barber", "Kinyozi", "Services", ArchetypeType.SERVICE_PROVIDER),
    MAMA_FUO("Laundry", "Mama Fuo", "Services", ArchetypeType.SERVICE_PROVIDER),
    TAILOR("Tailor", "Fundi Nguo", "Services", ArchetypeType.ARTISAN),
    SHOE_SHINER("Shoe shiner", "Muuza kiatu", "Services", ArchetypeType.SERVICE_PROVIDER),
    CAR_WASH("Car wash", "Car wash", "Services", ArchetypeType.SERVICE_PROVIDER),
    PLUMBER_SVC("Plumber", "Plumber", "Services", ArchetypeType.SERVICE_PROVIDER),
    ELECTRICIAN_SVC("Electrician", "Mfundi umeme", "Services", ArchetypeType.SERVICE_PROVIDER),
    LOCKSMITH("Locksmith", "Fundi ufunguo", "Services", ArchetypeType.SERVICE_PROVIDER),
    CLEANER("Cleaner", "Msafishi", "Services", ArchetypeType.SERVICE_PROVIDER),
    GARDENER("Gardener", "Mtu wa bustani", "Services", ArchetypeType.SERVICE_PROVIDER),
    MECHANIC("Mechanic", "Mekaniki", "Services", ArchetypeType.SERVICE_PROVIDER),
    PANEL_BEATER("Panel beater", "Fundi karakana", "Services", ArchetypeType.SERVICE_PROVIDER),
    AUTO_ELECTRICIAN("Auto electrician", "Mfundi umeme wa gari", "Services", ArchetypeType.SERVICE_PROVIDER),
    VULCANIZER("Vulcanizer", "Vulcanizer", "Services", ArchetypeType.SERVICE_PROVIDER),
    COBBLER("Cobbler", "Fundi kiatu", "Services", ArchetypeType.SERVICE_PROVIDER),
    PHONE_REPAIR_SVC("Phone repair", "Fundi simu", "Services", ArchetypeType.SERVICE_PROVIDER),
    ELECTRONICS_REPAIR("Electronics repair", "Fundi elektroniki", "Services", ArchetypeType.SERVICE_PROVIDER),

    // ── Agriculture (Crop Farmer, Livestock Keeper, Fisher archetypes) ──
    MKULIMA("Farmer", "Mkulima", "Agriculture", ArchetypeType.CROP_FARMER),
    MVUVI("Fisherman", "Mvuvi", "Agriculture", ArchetypeType.FISHER),
    MFUGAJI("Livestock keeper", "Mfugaji", "Agriculture", ArchetypeType.LIVESTOCK_KEEPER),
    PRODUCE_BROKER("Produce broker", "Dalali", "Agriculture", ArchetypeType.AGENT_BROKER),
    CASH_CROP_FARMER("Cash crop farmer", "Mkulima wa mazao", "Agriculture", ArchetypeType.CROP_FARMER),
    HORTICULTURAL_FARMER("Horticultural farmer", "Mkulima wa mboga", "Agriculture", ArchetypeType.CROP_FARMER),
    POULTRY_FARMER("Poultry farmer", "Mfugaji kuku", "Agriculture", ArchetypeType.LIVESTOCK_KEEPER),
    DAIRY_FARMER("Dairy farmer", "Mfugaji ng'ombe", "Agriculture", ArchetypeType.LIVESTOCK_KEEPER),
    GOAT_KEEPER("Goat/sheep keeper", "Mfugaji mbuzi", "Agriculture", ArchetypeType.LIVESTOCK_KEEPER),
    PIG_FARMER("Pig farmer", "Mfugaji nguruwe", "Agriculture", ArchetypeType.LIVESTOCK_KEEPER),
    BEE_KEEPER("Bee keeper", "Mfugaji nyuki", "Agriculture", ArchetypeType.LIVESTOCK_KEEPER),
    FISH_FARMER("Fish farmer", "Mfugaji samaki", "Agriculture", ArchetypeType.FISHER),
    FISH_DRIER("Fish drier/smoker", "Mukausha samaki", "Agriculture", ArchetypeType.FISHER),
    FISHMONGER("Fishmonger", "Muuza samaki", "Agriculture", ArchetypeType.FISHER),

    // ── Construction (Casual Laborer archetype) ──
    MJENGO("Construction worker", "Mjengo", "Construction", ArchetypeType.CASUAL_LABORER),
    MASON("Mason", "Mjenzi", "Construction", ArchetypeType.ARTISAN),
    PLUMBER("Plumber", "Plumber", "Construction", ArchetypeType.SERVICE_PROVIDER),
    ELECTRICIAN("Electrician", "Mfundi umeme", "Construction", ArchetypeType.SERVICE_PROVIDER),
    ROOFER("Roofer", "Fundi paa", "Construction", ArchetypeType.ARTISAN),
    PLASTERER("Plasterer", "Fundi plaster", "Construction", ArchetypeType.ARTISAN),
    PAINTER("Painter", "Mchoraji", "Construction", ArchetypeType.ARTISAN),
    TILE_SETTER("Tile setter", "Fundi tiles", "Construction", ArchetypeType.ARTISAN),
    SCAFFOLDING_WORKER("Scaffolding worker", "Mtu wa scaffolding", "Construction", ArchetypeType.CASUAL_LABORER),

    // ── Digital (Digital Worker & Agent/Broker archetypes) ──
    M_PESA("M-Pesa agent", "M-Pesa", "Digital", ArchetypeType.AGENT_BROKER),
    CYBER_CAFE("Cyber cafe", "Cyber cafe", "Digital", ArchetypeType.DIGITAL_WORKER),
    PHONE_REPAIR("Phone repair technician", "Fundi simu", "Digital", ArchetypeType.SERVICE_PROVIDER),
    SOCIAL_MEDIA_RESELLER("Social media reseller", "Muuza mtandaoni", "Digital", ArchetypeType.DIGITAL_WORKER),
    OTHER_MOBILE_MONEY("Other mobile money agent", "Agent wa pesa", "Digital", ArchetypeType.AGENT_BROKER),
    FOREX_BUREAU("Forex bureau", "Forex", "Digital", ArchetypeType.AGENT_BROKER),
    MONEY_LENDER("Money lender", "Mkopesha pesa", "Digital", ArchetypeType.AGENT_BROKER),
    GRAPHIC_DESIGNER("Graphic designer", "Mbuni wa graphics", "Digital", ArchetypeType.DIGITAL_WORKER),
    SOCIAL_MEDIA_MANAGER("Social media manager", "Meneja wa mitandao", "Digital", ArchetypeType.DIGITAL_WORKER),
    CONTENT_CREATOR("Content creator", "Mtengenezaji maudhui", "Digital", ArchetypeType.DIGITAL_WORKER),
    DATA_ENTRY("Data entry clerk", "Mhariri data", "Digital", ArchetypeType.DIGITAL_WORKER),
    ONLINE_SELLER("Online seller", "Muuza mtandaoni", "Digital", ArchetypeType.DIGITAL_WORKER),
    ONLINE_TUTOR("Online tutor", "Mwalimu mtandaoni", "Digital", ArchetypeType.DIGITAL_WORKER),

    // ── Artisans (Artisan archetype) ──
    JUA_KALI("Jua kali artisan", "Jua Kali", "Artisans", ArchetypeType.ARTISAN),
    BASKET_WEAVER("Basket weaver", "Mfumaji kikapu", "Artisans", ArchetypeType.ARTISAN),
    POTTER("Potter", "Mfinyanzi", "Artisans", ArchetypeType.ARTISAN),
    WELDER("Welder", "Mfundi welder", "Artisans", ArchetypeType.ARTISAN),
    BLACKSMITH("Blacksmith", "Mfua chuma", "Artisans", ArchetypeType.ARTISAN),
    METAL_FABRICATOR("Metal fabricator", "Mfua vyuma", "Artisans", ArchetypeType.ARTISAN),
    CARPENTER("Carpenter", "Seremala", "Artisans", ArchetypeType.ARTISAN),
    FURNITURE_MAKER("Furniture maker", "Mtengenezaji samani", "Artisans", ArchetypeType.ARTISAN),
    CARVER("Carver", "Mchongaji", "Artisans", ArchetypeType.ARTISAN),
    DRESSMAKER("Dressmaker", "Mshonaji nguo", "Artisans", ArchetypeType.ARTISAN),
    EMBROIDERER("Embroiderer", "Mshoni mapambo", "Artisans", ArchetypeType.ARTISAN),
    LEATHER_WORKER("Leather worker", "Mfua ngozi", "Artisans", ArchetypeType.ARTISAN),
    SOAP_MAKER("Soap maker", "Mtengenezaji sabuni", "Artisans", ArchetypeType.ARTISAN),
    CANDLE_MAKER("Candle maker", "Mtengenezaji mishumaa", "Artisans", ArchetypeType.ARTISAN),
    BEAD_WORKER("Bead worker", "Mfashoni shanga", "Artisans", ArchetypeType.ARTISAN),
    TIRE_SANDAL_MAKER("Tire sandal maker", "Mshoni viatu", "Artisans", ArchetypeType.ARTISAN),

    // ── Community/Care Workers (Community/Care Worker archetype) ──
    DOMESTIC_WORKER("Domestic worker", "Mtumishi wa nyumbani", "Other", ArchetypeType.CASUAL_LABORER),
    NANNY("Nanny/caretaker", "Mdada", "Other", ArchetypeType.CASUAL_LABORER),
    NIGHT_GUARD("Night guard", "Mlinzi wa usiku", "Other", ArchetypeType.COMMUNITY_CARE_WORKER),
    MC_HOST("MC/Event host", "MC", "Other", ArchetypeType.COMMUNITY_CARE_WORKER),
    DJ("DJ", "DJ", "Other", ArchetypeType.COMMUNITY_CARE_WORKER),
    MUSICIAN("Musician", "Mwanamuziki", "Other", ArchetypeType.COMMUNITY_CARE_WORKER),
    PHOTOGRAPHER("Photographer", "Mpiga picha", "Other", ArchetypeType.COMMUNITY_CARE_WORKER),
    VIDEOGRAPHER("Videographer", "Mrekodi video", "Other", ArchetypeType.COMMUNITY_CARE_WORKER),
    WASTE_PICKER("Waste picker", "Mkutaji taka", "Other", ArchetypeType.COMMUNITY_CARE_WORKER),
    COMMUNITY_HEALTH_WORKER("Community health worker", "Mhudumu wa afya", "Other", ArchetypeType.COMMUNITY_CARE_WORKER),
    HERBALIST("Herbalist", "Mganga wa kienyeji", "Other", ArchetypeType.COMMUNITY_CARE_WORKER),
    PRIVATE_SECURITY("Private security guard", "Mlinzi binafsi", "Other", ArchetypeType.COMMUNITY_CARE_WORKER),
    BOUNCER("Bouncer", "Bouncer", "Other", ArchetypeType.COMMUNITY_CARE_WORKER),
    DRIVING_INSTRUCTOR("Driving instructor", "Mwalimu wa kuendesha", "Other", ArchetypeType.COMMUNITY_CARE_WORKER),
    VOCATIONAL_TRAINER("Vocational trainer", "Mwalimu wa ufundi", "Other", ArchetypeType.COMMUNITY_CARE_WORKER),
    QURAN_TEACHER("Quran teacher", "Mwalimu wa Quran", "Other", ArchetypeType.COMMUNITY_CARE_WORKER),

    // ── Fallback ──
    OTHER("Other", "Nyingine", "Other", ArchetypeType.VENDOR)
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

@Entity(
    tableName = "sales",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["productName"]),
        Index(value = ["paymentMethod"]),
        Index(value = ["customerId"]),
        Index(value = ["timestamp", "paymentMethod"])
    ]
)
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

@Entity(
    tableName = "products",
    indices = [
        Index(value = ["category"]),
        Index(value = ["isActive"])
    ]
)
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

@Entity(
    tableName = "expenses",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["category"]),
        Index(value = ["timestamp", "category"])
    ]
)
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

@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["timestamp"]),
        Index(value = ["role", "timestamp"])
    ]
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: String, // user, assistant, system
    val content: String,
    val intent: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "knowledge_entries",
    indices = [
        Index(value = ["category"]),
        Index(value = ["category", "key"]),
        Index(value = ["updatedAt"])
    ]
)
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
@Entity(
    tableName = "debts",
    indices = [
        Index(value = ["status"]),
        Index(value = ["customerName"]),
        Index(value = ["dueDate"]),
        Index(value = ["status", "outstandingBalance"])
    ]
)
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
// Market Pooling Models
// Mama mbogas pool wholesale market trips, split transport costs
// ──────────────────────────────────────────────

enum class MarketPoolStatus {
    OPEN,           // Accepting members and orders
    IN_PROGRESS,    // Trip underway
    COMPLETED,      // Trip done, accounts settled
    CANCELLED       // Trip cancelled
}

@Entity(tableName = "market_pools")
data class MarketPoolEntity(
    @PrimaryKey val poolId: String,
    val marketDestination: String,        // e.g. "Wakulima", "Gikomba"
    val tripDate: String,                 // YYYY-MM-DD
    val scheduleDays: String = "",        // e.g. "Mon,Wed,Fri" for recurring
    val transportCost: Double,            // total transport cost KSh
    val porterageCost: Double = 0.0,      // porter/loading cost KSh
    val creatorId: String,
    val creatorName: String,
    val status: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val needsSync: Boolean = true
)

@Entity(
    tableName = "market_pool_members",
    foreignKeys = [ForeignKey(
        entity = MarketPoolEntity::class,
        parentColumns = ["poolId"],
        childColumns = ["poolId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["poolId"])]
)
data class MarketPoolMemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val poolId: String,
    val memberId: String,
    val memberName: String,
    val phone: String = "",
    val role: String = "member",          // "admin" or "member"
    val joinedAt: Long = System.currentTimeMillis(),
    val needsSync: Boolean = true
)

@Entity(
    tableName = "market_pool_orders",
    foreignKeys = [ForeignKey(
        entity = MarketPoolEntity::class,
        parentColumns = ["poolId"],
        childColumns = ["poolId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["poolId"]), Index(value = ["memberId"])]
)
data class MarketPoolOrderEntity(
    @PrimaryKey(autoGenerate = true) val orderId: Long = 0,
    val poolId: String,
    val memberId: String,
    val tripDate: String,                 // YYYY-MM-DD
    val itemName: String,                 // e.g. "nyanya", "sukuma"
    val quantity: Double,
    val unit: String,                     // "kg", "bunch", "piece", "litre"
    val maxPricePerUnit: Double,          // ceiling price KSh
    val status: String = "pending",       // pending | bought | delivered | disputed
    val actualPricePerUnit: Double? = null,
    val actualQuantity: Double? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val needsSync: Boolean = true
)

@Entity(
    tableName = "market_pool_contributions",
    foreignKeys = [ForeignKey(
        entity = MarketPoolEntity::class,
        parentColumns = ["poolId"],
        childColumns = ["poolId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["poolId"]), Index(value = ["memberId"])]
)
data class MarketPoolContributionEntity(
    @PrimaryKey(autoGenerate = true) val contributionId: Long = 0,
    val poolId: String,
    val memberId: String,
    val tripDate: String,
    val amountExpected: Double,
    val amountPaid: Double = 0.0,
    val paymentMethod: String = "mpesa",  // "mpesa" or "cash"
    val paymentRef: String = "",
    val status: String = "pending",       // pending | partial | complete
    val paidAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val needsSync: Boolean = true
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
    ],
    indices = [
        Index(value = ["workerId"]),
        Index(value = ["customerKey"]),
        Index(value = ["visitDate"]),
        Index(value = ["workerId", "customerKey"]),
        Index(value = ["workerId", "visitDate"])
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
