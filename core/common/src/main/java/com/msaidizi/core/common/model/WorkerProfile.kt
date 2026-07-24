package com.msaidizi.core.common.model

/**
 * Worker profile — the identity of a Msaidizi user.
 *
 * Captured during onboarding (voice-first, transaction-first) and
 * enriched over time through daily interactions. This profile drives
 * personalization, dialect adaptation, and business intelligence.
 *
 * ## M-KOPA Parallel
 * Like M-KOPA's customer profile that builds through payment history,
 * Msaidizi's WorkerProfile builds through transaction history. The
 * profile is a living document that becomes richer with every interaction.
 */
data class WorkerProfile(
    val id: Long = 0,

    // ═══ IDENTITY ═══
    /** Worker's name — "Amina", "Juma" */
    val name: String = "",
    /** Phone number (for M-Pesa integration, verification) */
    val phoneNumber: String = "",
    /** Profile image URL */
    val profileImageUrl: String = "",

    // ═══ BUSINESS ═══
    /** Business type — "mboga", "duka", "fundi", "mama fua" */
    val businessType: String = "",
    /** Business name (if any) — "Amina's Vegetables" */
    val businessName: String = "",
    /** Business category — "food_vendor", "retail", "services", "artisan" */
    val businessCategory: String = "",
    /** Primary products/services (comma-separated) — "nyanya,mboga,matunda" */
    val primaryProducts: String = "",
    /** Average daily revenue (KSh, calculated from transactions) */
    val avgDailyRevenue: Double = 0.0,
    /** Average daily profit (KSh, calculated from transactions) */
    val avgDailyProfit: Double = 0.0,
    /** Typical operating hours — "morning", "all_day", "evening" */
    val operatingHours: String = "all_day",

    // ═══ LOCATION ═══
    /** Primary business location — "Gikomba", "Kenyatta Market" */
    val locationName: String = "",
    /** GPS latitude */
    val locationLat: Double? = null,
    /** GPS longitude */
    val locationLng: Double? = null,
    /** Market/area ID (for Soko Pulse) */
    val marketId: String = "",
    /** City/region — "Nairobi", "Mombasa" */
    val region: String = "",

    // ═══ LANGUAGE & DIALECT ═══
    /** Primary language code — "sw" (Swahili), "en" (English), etc. */
    val language: String = "sw",
    /** Detected dialect — "sheng-influenced", "coastal", "standard" */
    val dialect: String = "",
    /** Whether worker code-switches between languages */
    val codeSwitches: Boolean = false,
    /** Preferred response style — "formal", "casual", "sheng" */
    val responseStyle: String = "casual",

    // ═══ WORK PATTERNS ═══
    /**
     * Work pattern classification based on transaction history.
     * - "daily_consistent": Tracks every day, regular hours
     * - "weekly_peak": Heavy on market days (Mon/Thu/Sat)
     * - "seasonal": Revenue varies by season
     * - "irregular": No clear pattern yet
     * - "new": Too few data points to classify
     */
    val workPattern: String = "new",
    /** Days per week typically working (0-7) */
    val typicalWorkingDays: Int = 5,
    /** Busiest day of week (1=Monday, 7=Sunday) */
    val peakDay: Int = 0,
    /** Busiest hour of day (0-23) */
    val peakHour: Int = 0,

    // ═══ FINANCIAL PATTERNS ═══
    /** Typical profit margin (0.0-1.0) */
    val typicalMargin: Double = 0.0,
    /** Most common payment method */
    val primaryPaymentMethod: String = "cash",
    /** M-Pesa connected */
    val mpesaConnected: Boolean = false,
    /** Has formal bank account */
    val hasBankAccount: Boolean = false,

    // ═══ ENGAGEMENT ═══
    /** Total days active since onboarding */
    val daysActive: Int = 0,
    /** Current consecutive days streak */
    val currentStreak: Int = 0,
    /** Longest streak ever */
    val longestStreak: Int = 0,
    /** Total transactions recorded */
    val totalTransactions: Int = 0,
    /** Preferred interaction time — "morning", "evening", "any" */
    val preferredInteractionTime: String = "any",

    // ═══ PROOF (M-KOPA MODEL) ═══
    /** Current Alama Score */
    val alamaScore: Double = 0.0,
    /** Current Alama Tier */
    val alamaTier: AlamaTier = AlamaTier.MTOTO,
    /** Total proof points accumulated */
    val totalProofPoints: Int = 0,

    // ═══ TIMESTAMPS ═══
    /** When the worker first used Msaidizi */
    val createdAt: Long = System.currentTimeMillis(),
    /** Last interaction timestamp */
    val lastInteractionAt: Long = System.currentTimeMillis(),
    /** Last profile update */
    val updatedAt: Long = System.currentTimeMillis(),
    /** When synced to backend */
    val syncedAt: Long? = null
) {
    /**
     * Whether the worker has enough history for meaningful analysis.
     * Requires at least 7 days of activity.
     */
    val hasEnoughHistory: Boolean
        get() = daysActive >= 7 && totalTransactions >= 14

    /**
     * Whether the worker is eligible for credit readiness assessment.
     * Requires 90+ days and MKUU tier.
     */
    val isCreditEligible: Boolean
        get() = daysActive >= 90 && alamaTier >= AlamaTier.MKUU

    /**
     * Streak status for gamification.
     */
    val streakStatus: StreakStatus
        get() = when {
            currentStreak == 0 -> StreakStatus.BROKEN
            currentStreak < 7 -> StreakStatus.BUILDING
            currentStreak < 30 -> StreakStatus.STRONG
            currentStreak < 90 -> StreakStatus.ON_FIRE
            else -> StreakStatus.LEGENDARY
        }
}

/**
 * Alama Score tiers — progressive service unlocking.
 * Mirrors M-KOPA's credit tier model.
 */
enum class AlamaTier(
    val displayName: String,
    val swahiliName: String,
    val minProofPoints: Int,
    val unlockMessage: String
) {
    MTOTO("Newborn", "Mtoto", 0,
        "Karibu! Sasa tunaanza safari yako ya biashara."),
    MBEGU("Seed", "Mbegu", 3,
        "Hongera! Umefanya mwanzo mzuri. Sasa nitakuletea muhtasari wa kila siku."),
    MZAZI("Parent Tree", "Mzazi", 15,
        "Biashara yako inakua! Sasa unaonyesha Alama Score yako."),
    MKUU("Leader", "Mkuu", 51,
        "Historia yako ni thabiti! Sasa una uwezo wa kukopwa."),
    JIJI("City", "Jiji", 151,
        "Biashara yako imethibitishwa! Sasa unaweza kupata mkopo wa biashara."),
    DUNIA("World", "Dunia", 501,
        "Wewe ni mfanyabiashara wa kuaminika! Sasa una utambulisho wa kifedha kamili.")
}

/**
 * Streak status for gamification.
 */
enum class StreakStatus {
    BROKEN,     // 0 days
    BUILDING,   // 1-6 days
    STRONG,     // 7-29 days
    ON_FIRE,    // 30-89 days
    LEGENDARY   // 90+ days
}

/**
 * Worker business categories.
 */
object BusinessCategories {
    const val FOOD_VENDOR = "food_vendor"        // Mama mboga, food stalls
    const val RETAIL = "retail"                   // Duka, shop
    const val SERVICES = "services"               // Mama fua, fundi
    const val ARTISAN = "artisan"                 // Craftspeople
    const val TRANSPORT = "transport"             // Boda boda, matatu
    const val AGRICULTURE = "agriculture"         // Farmers
    const val TECH = "tech"                       // Phone repair, cyber cafe

    /** Map of category → typical products/services */
    val CATEGORY_PRODUCTS = mapOf(
        FOOD_VENDOR to listOf("mboga", "matunda", "nyama", "samaki", "maziwa", "mayai"),
        RETAIL to listOf("sabuni", "mafuta", "vifaa", "nguo", "simu"),
        SERVICES to listOf("kufua", "kupika", "kupiga picha", "kutengeneza"),
        ARTISAN to listOf("sufuria", "nguo", "viti", "meza"),
        TRANSPORT to listOf("usafiri", "delivery", "kubeba"),
        AGRICULTURE to listOf("mahindi", "maharage", "ngano", "viazi")
    )
}
