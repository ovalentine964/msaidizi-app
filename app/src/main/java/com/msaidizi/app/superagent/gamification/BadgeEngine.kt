package com.msaidizi.app.superagent.gamification

import timber.log.Timber

/**
 * Badge Engine — 15+ Swahili badges for business milestones.
 *
 * ## Badge Design Philosophy
 * Badges are named in Swahili to feel culturally relevant.
 * Each badge represents a real business achievement, not arbitrary gamification.
 *
 * ## Badge Categories
 * - **SALES** — Recording sales milestones
 * - **STREAK** — Consistency achievements
 * - **KNOWLEDGE** — Learning milestones
 * - **LEVEL** — Level-up achievements
 * - **SOCIAL** — Community engagement
 *
 * @param badgeProvider Provides access to badge storage
 */
class BadgeEngine(
    private val badgeProvider: BadgeProvider
) {
    companion object {
        private const val TAG = "BadgeEngine"
    }

    /** All available badges */
    val badges: List<BadgeDef> = listOf(
        // ═══ SALES BADGES ═══
        BadgeDef(
            id = "biashara_ndogo",
            nameSw = "Biashara Ndogo",
            nameEn = "Small Business",
            descriptionSw = "Rekodi mauzo yako ya kwanza",
            descriptionEn = "Record your first sale",
            emoji = "🏪",
            category = "SALES",
            requirement = BadgeRequirement.SALES_COUNT,
            threshold = 1
        ),
        BadgeDef(
            id = "mfanyabiashara_mkuu",
            nameSw = "Mfanyabiashara Mkuu",
            nameEn = "Top Seller",
            descriptionSw = "Rekodi mauzo 50",
            descriptionEn = "Record 50 sales",
            emoji = "💰",
            category = "SALES",
            requirement = BadgeRequirement.SALES_COUNT,
            threshold = 50
        ),
        BadgeDef(
            id = "mfanyabiashara_100",
            nameSw = "Mfanyabiashara 100",
            nameEn = "Century Seller",
            descriptionSw = "Rekodi mauzo 100",
            descriptionEn = "Record 100 sales",
            emoji = "💯",
            category = "SALES",
            requirement = BadgeRequirement.SALES_COUNT,
            threshold = 100
        ),
        BadgeDef(
            id = "malkia_wa_biashara",
            nameSw = "Malkia wa Biashara",
            nameEn = "Business Queen",
            descriptionSw = "Rekodi mauzo 250",
            descriptionEn = "Record 250 sales",
            emoji = "👸",
            category = "SALES",
            requirement = BadgeRequirement.SALES_COUNT,
            threshold = 250
        ),
        BadgeDef(
            id = "mfanyabiashara_wa_siku",
            nameSw = "Mfanyabiashara wa Siku",
            nameEn = "Daily Trader",
            descriptionSw = "Rekodi mauzo 5 kwa siku moja",
            descriptionEn = "Record 5 sales in one day",
            emoji = "🔥",
            category = "SALES",
            requirement = BadgeRequirement.DAILY_SALES,
            threshold = 5
        ),
        BadgeDef(
            id = "mfupi_wa_siku",
            nameSw = "Mara Mbili",
            nameEn = "Double Up",
            descriptionSw = "Rekodi mauzo 10 kwa siku moja",
            descriptionEn = "Record 10 sales in one day",
            emoji = "✌️",
            category = "SALES",
            requirement = BadgeRequirement.DAILY_SALES,
            threshold = 10
        ),

        // ═══ STREAK BADGES ═══
        BadgeDef(
            id = "mlinzi_wa_siku_tatu",
            nameSw = "Mlinzi wa Siku Tatu",
            nameEn = "3-Day Guardian",
            descriptionSw = "Shikilia mfululizo wa siku 3",
            descriptionEn = "Maintain a 3-day streak",
            emoji = "🛡️",
            category = "STREAK",
            requirement = BadgeRequirement.STREAK_DAYS,
            threshold = 3
        ),
        BadgeDef(
            id = "bwenye_hafta",
            nameSw = "Bwenye ya Wiki",
            nameEn = "Week Warrior",
            descriptionSw = "Shikilia mfululizo wa siku 7",
            descriptionEn = "Maintain a 7-day streak",
            emoji = "⚔️",
            category = "STREAK",
            requirement = BadgeRequirement.STREAK_DAYS,
            threshold = 7
        ),
        BadgeDef(
            id = "mwezi_wa_dhahabu",
            nameSw = "Mwezi wa Dhahabu",
            nameEn = "Golden Month",
            descriptionSw = "Shikilia mfululizo wa siku 30",
            descriptionEn = "Maintain a 30-day streak",
            emoji = "🥇",
            category = "STREAK",
            requirement = BadgeRequirement.STREAK_DAYS,
            threshold = 30
        ),
        BadgeDef(
            id = "streak_ya_mwezi_mbili",
            nameSw = "Streak ya Miezi Miwili",
            nameEn = "Two Month Streak",
            descriptionSw = "Shikilia mfululizo wa siku 60",
            descriptionEn = "Maintain a 60-day streak",
            emoji = "🔥🔥",
            category = "STREAK",
            requirement = BadgeRequirement.STREAK_DAYS,
            threshold = 60
        ),

        // ═══ KNOWLEDGE BADGES ═══
        BadgeDef(
            id = "mtaalamu_wa_bei",
            nameSw = "Mtaalamu wa Bei",
            nameEn = "Price Expert",
            descriptionSw = "Angalia salio mara 20",
            descriptionEn = "Check balance 20 times",
            emoji = "📊",
            category = "KNOWLEDGE",
            requirement = BadgeRequirement.BALANCE_CHECKS,
            threshold = 20
        ),
        BadgeDef(
            id = "mfuatiliaji_wa_siku",
            nameSw = "Mfuatiliaji wa Siku",
            nameEn = "Daily Tracker",
            descriptionSw = "Angalia salio mara 50",
            descriptionEn = "Check balance 50 times",
            emoji = "🔍",
            category = "KNOWLEDGE",
            requirement = BadgeRequirement.BALANCE_CHECKS,
            threshold = 50
        ),

        // ═══ LEVEL BADGES ═══
        BadgeDef(
            id = "mjasiriamali_chipukizi",
            nameSw = "Mjasiriamali Chipukizi",
            nameEn = "Rising Entrepreneur",
            descriptionSw = "Fikia Level 2 (Mjasiriamali)",
            descriptionEn = "Reach Level 2 (Entrepreneur)",
            emoji = "🌱",
            category = "LEVEL",
            requirement = BadgeRequirement.LEVEL_REACHED,
            threshold = 2
        ),
        BadgeDef(
            id = "bingwa_wa_biashara",
            nameSw = "Bingwa wa Biashara",
            nameEn = "Business Champion",
            descriptionSw = "Fikia Level 3 (Bingwa)",
            descriptionEn = "Reach Level 3 (Champion)",
            emoji = "🏆",
            category = "LEVEL",
            requirement = BadgeRequirement.LEVEL_REACHED,
            threshold = 3
        ),
        BadgeDef(
            id = "kiongozi_mkuu",
            nameSw = "Kiongozi Mkuu",
            nameEn = "Great Leader",
            descriptionSw = "Fikia Level 4 (Kiongozi)",
            descriptionEn = "Reach Level 4 (Leader)",
            emoji = "👑",
            category = "LEVEL",
            requirement = BadgeRequirement.LEVEL_REACHED,
            threshold = 4
        ),
        BadgeDef(
            id = "mfanyabiashara_bora",
            nameSw = "Mfanyabiashara Bora",
            nameEn = "Best Business Person",
            descriptionSw = "Fikia Level 5 (Legend)",
            descriptionEn = "Reach Level 5 (Legend)",
            emoji = "⭐",
            category = "LEVEL",
            requirement = BadgeRequirement.LEVEL_REACHED,
            threshold = 5
        ),

        // ═══ POINTS BADGES ═══
        BadgeDef(
            id = "mkusanyaji_pesa",
            nameSw = "Mkusanyaji Pesa",
            nameEn = "Money Collector",
            descriptionSw = "Pata pointi 100",
            descriptionEn = "Earn 100 points",
            emoji = "💵",
            category = "POINTS",
            requirement = BadgeRequirement.TOTAL_POINTS,
            threshold = 100
        ),
        BadgeDef(
            id = "tajiri_pointi",
            nameSw = "Tajiri wa Pointi",
            nameEn = "Points Tycoon",
            descriptionSw = "Pata pointi 1000",
            descriptionEn = "Earn 1000 points",
            emoji = "💎",
            category = "POINTS",
            requirement = BadgeRequirement.TOTAL_POINTS,
            threshold = 1000
        )
    )

    /**
     * Check for newly earned badges after an update.
     *
     * @param stats Current gamification stats
     * @param language "sw" or "en"
     * @return List of newly earned badges with celebration messages
     */
    suspend fun checkNewBadges(stats: BadgeCheckStats, language: String = "sw"): List<BadgeEarned> {
        val earnedIds = badgeProvider.getEarnedBadgeIds()
        val newBadges = mutableListOf<BadgeEarned>()

        for (badge in badges) {
            if (badge.id in earnedIds) continue

            val earned = when (badge.requirement) {
                BadgeRequirement.SALES_COUNT -> stats.totalSales >= badge.threshold
                BadgeRequirement.DAILY_SALES -> stats.todaySales >= badge.threshold
                BadgeRequirement.STREAK_DAYS -> stats.currentStreak >= badge.threshold
                BadgeRequirement.BALANCE_CHECKS -> stats.totalBalanceChecks >= badge.threshold
                BadgeRequirement.LEVEL_REACHED -> stats.currentLevel >= badge.threshold
                BadgeRequirement.TOTAL_POINTS -> stats.totalPoints >= badge.threshold
            }

            if (earned) {
                badgeProvider.markBadgeEarned(badge.id)
                val message = if (language == "sw") {
                    "${badge.emoji} Badge mpya: ${badge.nameSw}! ${badge.descriptionSw}"
                } else {
                    "${badge.emoji} New badge: ${badge.nameEn}! ${badge.descriptionEn}"
                }
                newBadges.add(BadgeEarned(badge, message))
                Timber.d(TAG, "Badge earned: %s", badge.id)
            }
        }

        return newBadges
    }

    /**
     * Get all badges with their earned status.
     */
    suspend fun getAllBadgeStatus(stats: BadgeCheckStats): List<BadgeStatus> {
        val earnedIds = badgeProvider.getEarnedBadgeIds()

        return badges.map { badge ->
            BadgeStatus(
                badge = badge,
                earned = badge.id in earnedIds,
                progress = calculateProgress(badge, stats)
            )
        }
    }

    /**
     * Get badges by category.
     */
    suspend fun getBadgesByCategory(category: String, stats: BadgeCheckStats): List<BadgeStatus> {
        return getAllBadgeStatus(stats).filter { it.badge.category == category }
    }

    /**
     * Get count of earned badges.
     */
    suspend fun getEarnedCount(): Int {
        return badgeProvider.getEarnedBadgeIds().size
    }

    // ═══════════════ PRIVATE HELPERS ═══════════════

    private fun calculateProgress(badge: BadgeDef, stats: BadgeCheckStats): Float {
        val current = when (badge.requirement) {
            BadgeRequirement.SALES_COUNT -> stats.totalSales
            BadgeRequirement.DAILY_SALES -> stats.todaySales
            BadgeRequirement.STREAK_DAYS -> stats.currentStreak
            BadgeRequirement.BALANCE_CHECKS -> stats.totalBalanceChecks
            BadgeRequirement.LEVEL_REACHED -> stats.currentLevel
            BadgeRequirement.TOTAL_POINTS -> stats.totalPoints
        }
        return (current.toFloat() / badge.threshold).coerceIn(0f, 1f)
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/**
 * Badge definition.
 */
data class BadgeDef(
    val id: String,
    val nameSw: String,
    val nameEn: String,
    val descriptionSw: String,
    val descriptionEn: String,
    val emoji: String,
    val category: String,
    val requirement: BadgeRequirement,
    val threshold: Int
)

/**
 * Badge requirement types.
 */
enum class BadgeRequirement {
    SALES_COUNT,
    DAILY_SALES,
    STREAK_DAYS,
    BALANCE_CHECKS,
    LEVEL_REACHED,
    TOTAL_POINTS
}

/**
 * Stats for badge checking.
 */
data class BadgeCheckStats(
    val totalSales: Int = 0,
    val todaySales: Int = 0,
    val currentStreak: Int = 0,
    val totalBalanceChecks: Int = 0,
    val currentLevel: Int = 0,
    val totalPoints: Int = 0
)

/**
 * A newly earned badge with celebration message.
 */
data class BadgeEarned(
    val badge: BadgeDef,
    val message: String
)

/**
 * Badge with earned status and progress.
 */
data class BadgeStatus(
    val badge: BadgeDef,
    val earned: Boolean,
    val progress: Float
)

/**
 * Interface for badge storage.
 */
interface BadgeProvider {
    suspend fun getEarnedBadgeIds(): Set<String>
    suspend fun markBadgeEarned(badgeId: String)
}
