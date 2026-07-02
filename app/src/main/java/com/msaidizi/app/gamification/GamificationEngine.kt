package com.msaidizi.app.gamification

import com.msaidizi.app.core.database.GamificationDao
import com.msaidizi.app.core.model.Badge
import com.msaidizi.app.core.model.GamificationEntity
import timber.log.Timber
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Gamification Engine — drives retention through Swahili badges, levels,
 * streaks, and points for the Msaidizi app.
 *
 * Designed for informal workers in Kenya: all names in Swahili,
 * milestones tied to real business actions (recording sales, checking
 * balance, maintaining streaks).
 *
 * Points system:
 * - Record sale = 10 pts
 * - Check balance = 5 pts
 * - Daily streak = 20 pts (per day maintained)
 * - Complete daily habits bonus = 15 pts
 * - Listen to mindset lesson = 8 pts
 * - Give tithe/charity = 12 pts
 *
 * 6 Levels:
 * 0. Mwanafunzi (Student) — 0-99 pts
 * 1. Mfanyabiashara (Business Person) — 100-299 pts
 * 2. Mjasiriamali (Entrepreneur) — 300-599 pts
 * 3. Bingwa (Champion) — 600-999 pts
 * 4. Kiongozi (Leader) — 1000-1999 pts
 * 5. Legend — 2000+ pts
 *
 * Streak protection: 1 free miss per week (resets every Monday).
 *
 * @param gamificationDao Room DAO for persistence
 */
class GamificationEngine(
    private val gamificationDao: GamificationDao
) {
    companion object {
        private const val TAG = "GamificationEngine"

        // Points per action
        const val POINTS_SALE = 10
        const val POINTS_BALANCE_CHECK = 5
        const val POINTS_DAILY_STREAK = 20
        const val POINTS_HABITS_BONUS = 15
        const val POINTS_MINDSET_LESSON = 8
        const val POINTS_GIVING = 12

        // Level thresholds
        private val LEVEL_THRESHOLDS = intArrayOf(0, 100, 300, 600, 1000, 2000)

        // Level names
        private val LEVEL_NAMES_SW = arrayOf(
            "Mwanafunzi", "Mfanyabiashara", "Mjasiriamali",
            "Bingwa", "Kiongozi", "Legend"
        )
        private val LEVEL_NAMES_EN = arrayOf(
            "Student", "Business Person", "Entrepreneur",
            "Champion", "Leader", "Legend"
        )

        // Level emojis
        private val LEVEL_EMOJIS = arrayOf("📚", "🏪", "🚀", "🏆", "👑", "⭐")

        // Streak protections per week
        const val MAX_STREAK_PROTECTIONS_PER_WEEK = 1
    }

    // ═══════════════════════════════════════════════════════════════
    // 15+ SWAHILI BADGES
    // ═══════════════════════════════════════════════════════════════

    val badges: List<Badge> = listOf(
        Badge(
            id = "biashara_ndogo",
            nameSw = "Biashara Ndogo",
            nameEn = "Small Business",
            descriptionSw = "Rekodi mauzo yako ya kwanza",
            descriptionEn = "Record your first sale",
            emoji = "🏪",
            requirement = { e, _, _ -> e.totalSalesRecorded >= 1 }
        ),
        Badge(
            id = "mfanyabiashara_mkuu",
            nameSw = "Mfanyabiashara Mkuu",
            nameEn = "Top Seller",
            descriptionSw = "Rekodi mauzo 50",
            descriptionEn = "Record 50 sales",
            emoji = "💰",
            requirement = { e, _, _ -> e.totalSalesRecorded >= 50 }
        ),
        Badge(
            id = "mtaalamu_wa_bei",
            nameSw = "Mtaalamu wa Bei",
            nameEn = "Price Expert",
            descriptionSw = "Angalia salio mara 20",
            descriptionEn = "Check balance 20 times",
            emoji = "📊",
            requirement = { e, _, _ -> e.totalBalanceChecks >= 20 }
        ),
        Badge(
            id = "mfanyabiashara_wa_siku",
            nameSw = "Mfanyabiashara wa Siku",
            nameEn = "Daily Trader",
            descriptionSw = "Rekodi mauzo 5 kwa siku moja",
            descriptionEn = "Record 5 sales in one day",
            emoji = "🔥",
            requirement = { _, todaySales, _ -> todaySales >= 5 }
        ),
        Badge(
            id = "mlinzi_wa_siku_tatu",
            nameSw = "Mlinzi wa Siku Tatu",
            nameEn = "3-Day Guardian",
            descriptionSw = "Shikilia mfululizo wa siku 3",
            descriptionEn = "Maintain a 3-day streak",
            emoji = "🛡️",
            requirement = { e, _, _ -> e.currentStreak >= 3 }
        ),
        Badge(
            id = "bwenye_hafta",
            nameSw = "Bwenye ya Wiki",
            nameEn = "Week Warrior",
            descriptionSw = "Shikilia mfululizo wa siku 7",
            descriptionEn = "Maintain a 7-day streak",
            emoji = "⚔️",
            requirement = { e, _, _ -> e.currentStreak >= 7 }
        ),
        Badge(
            id = "mwezi_wa_dhahabu",
            nameSw = "Mwezi wa Dhahabu",
            nameEn = "Golden Month",
            descriptionSw = "Shikilia mfululizo wa siku 30",
            descriptionEn = "Maintain a 30-day streak",
            emoji = "🥇",
            requirement = { e, _, _ -> e.currentStreak >= 30 }
        ),
        Badge(
            id = "mjasiriamali_chipukizi",
            nameSw = "Mjasiriamali Chipukizi",
            nameEn = "Rising Entrepreneur",
            descriptionSw = "Fikia Level 2 (Mjasiriamali)",
            descriptionEn = "Reach Level 2 (Entrepreneur)",
            emoji = "🌱",
            requirement = { e, _, _ -> e.level >= 2 }
        ),
        Badge(
            id = "bingwa_wa_biashara",
            nameSw = "Bingwa wa Biashara",
            nameEn = "Business Champion",
            descriptionSw = "Fikia Level 3 (Bingwa)",
            descriptionEn = "Reach Level 3 (Champion)",
            emoji = "🏆",
            requirement = { e, _, _ -> e.level >= 3 }
        ),
        Badge(
            id = "kiongozi_mkuu",
            nameSw = "Kiongozi Mkuu",
            nameEn = "Great Leader",
            descriptionSw = "Fikia Level 4 (Kiongozi)",
            descriptionEn = "Reach Level 4 (Leader)",
            emoji = "👑",
            requirement = { e, _, _ -> e.level >= 4 }
        ),
        Badge(
            id = "mkusanyaji_pesa",
            nameSw = "Mkusanyaji Pesa",
            nameEn = "Money Collector",
            descriptionSw = "Pata pointi 100",
            descriptionEn = "Earn 100 points",
            emoji = "💵",
            requirement = { e, _, _ -> e.totalPoints >= 100 }
        ),
        Badge(
            id = "tajiri_pointi",
            nameSw = "Tajiri wa Pointi",
            nameEn = "Points Tycoon",
            descriptionSw = "Pata pointi 1000",
            descriptionEn = "Earn 1000 points",
            emoji = "💎",
            requirement = { e, _, _ -> e.totalPoints >= 1000 }
        ),
        Badge(
            id = "mfanyabiashara_mara_mbili",
            nameSw = "Mara Mbili",
            nameEn = "Double Up",
            descriptionSw = "Rekodi mauzo 10 kwa siku moja",
            descriptionEn = "Record 10 sales in one day",
            emoji = "✌️",
            requirement = { _, todaySales, _ -> todaySales >= 10 }
        ),
        Badge(
            id = "mfupi_wa_siku",
            nameSw = "Mfuatiliaji wa Siku",
            nameEn = "Daily Tracker",
            descriptionSw = "Angalia salio mara 50",
            descriptionEn = "Check balance 50 times",
            emoji = "🔍",
            requirement = { e, _, _ -> e.totalBalanceChecks >= 50 }
        ),
        Badge(
            id = "mfanyabiashara_100",
            nameSw = "Mfanyabiashara 100",
            nameEn = "Century Seller",
            descriptionSw = "Rekodi mauzo 100",
            descriptionEn = "Record 100 sales",
            emoji = "💯",
            requirement = { e, _, _ -> e.totalSalesRecorded >= 100 }
        ),
        Badge(
            id = "streak_ya_mwezi_mbili",
            nameSw = "Streak ya Miezi Miwili",
            nameEn = "Two Month Streak",
            descriptionSw = "Shikilia mfululizo wa siku 60",
            descriptionEn = "Maintain a 60-day streak",
            emoji = "🔥🔥",
            requirement = { e, _, _ -> e.currentStreak >= 60 }
        ),
        Badge(
            id = "malkia_wa_biashara",
            nameSw = "Malkia wa Biashara",
            nameEn = "Business Queen",
            descriptionSw = "Rekodi mauzo 250",
            descriptionEn = "Record 250 sales",
            emoji = "👸",
            requirement = { e, _, _ -> e.totalSalesRecorded >= 250 }
        ),
        Badge(
            id = "mfanyabiashara_bora",
            nameSw = "Mfanyabiashara Bora",
            nameEn = "Best Business Person",
            descriptionSw = "Fikia Level 5 (Legend)",
            descriptionEn = "Reach Level 5 (Legend)",
            emoji = "⭐",
            requirement = { e, _, _ -> e.level >= 5 }
        )
    )

    // ═══════════════════════════════════════════════════════════════
    // CORE OPERATIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Initialize gamification state if not exists.
     */
    suspend fun initialize() {
        if (gamificationDao.getGamification() == null) {
            gamificationDao.upsert(GamificationEntity())
            Timber.d(TAG, "Gamification initialized")
        }
    }

    /**
     * Record a sale event — awards points and updates counts.
     * @return GamificationEvent with points earned, level-up info, and new badges
     */
    suspend fun onSaleRecorded(language: String = "sw"): GamificationEvent {
        val now = System.currentTimeMillis() / 1000
        gamificationDao.addPoints(POINTS_SALE, now)
        gamificationDao.incrementSalesCount(now)

        // Update streak
        updateDailyStreak()

        // Check level & badges
        val entity = gamificationDao.getGamification() ?: return GamificationEvent.empty()
        return evaluateChanges(entity, language)
    }

    /**
     * Balance check event — awards points.
     */
    suspend fun onBalanceChecked(language: String = "sw"): GamificationEvent {
        val now = System.currentTimeMillis() / 1000
        gamificationDao.addPoints(POINTS_BALANCE_CHECK, now)
        gamificationDao.incrementBalanceChecks(now)

        val entity = gamificationDao.getGamification() ?: return GamificationEvent.empty()
        return evaluateChanges(entity, language)
    }

    /**
     * Daily streak maintenance — awards streak points.
     */
    suspend fun onDailyActivity(language: String = "sw"): GamificationEvent {
        updateDailyStreak()
        val entity = gamificationDao.getGamification() ?: return GamificationEvent.empty()
        return evaluateChanges(entity, language)
    }

    /**
     * Habits bonus awarded when all 10 habits completed.
     */
    suspend fun onAllHabitsCompleted(language: String = "sw"): GamificationEvent {
        val now = System.currentTimeMillis() / 1000
        gamificationDao.addPoints(POINTS_HABITS_BONUS, now)
        val entity = gamificationDao.getGamification() ?: return GamificationEvent.empty()
        return evaluateChanges(entity, language)
    }

    /**
     * Mindset lesson completion — awards points.
     */
    suspend fun onLessonCompleted(language: String = "sw"): GamificationEvent {
        val now = System.currentTimeMillis() / 1000
        gamificationDao.addPoints(POINTS_MINDSET_LESSON, now)
        val entity = gamificationDao.getGamification() ?: return GamificationEvent.empty()
        return evaluateChanges(entity, language)
    }

    /**
     * Giving (tithe/charity) event — awards points.
     */
    suspend fun onGivingRecorded(language: String = "sw"): GamificationEvent {
        val now = System.currentTimeMillis() / 1000
        gamificationDao.addPoints(POINTS_GIVING, now)
        val entity = gamificationDao.getGamification() ?: return GamificationEvent.empty()
        return evaluateChanges(entity, language)
    }

    /**
     * Get current gamification state.
     */
    suspend fun getState(): GamificationEntity {
        return gamificationDao.getGamification() ?: run {
            initialize()
            gamificationDao.getGamification()!!
        }
    }

    /**
     * Get current level info.
     */
    suspend fun getCurrentLevel(language: String = "sw"): LevelInfo {
        val entity = getState()
        return LevelInfo(
            levelIndex = entity.level,
            nameSw = LEVEL_NAMES_SW[entity.level],
            nameEn = LEVEL_NAMES_EN[entity.level],
            emoji = LEVEL_EMOJIS[entity.level],
            totalPoints = entity.totalPoints,
            nextLevelPoints = if (entity.level < 5) LEVEL_THRESHOLDS[entity.level + 1] else -1,
            progress = calculateLevelProgress(entity)
        )
    }

    /**
     * Get all badges with earned status.
     */
    suspend fun getBadgeStatus(todaySalesCount: Int = 0, todayBalanceChecks: Int = 0): List<BadgeStatus> {
        val entity = getState()
        val earnedIds = entity.earnedBadges.split(",").filter { it.isNotBlank() }.toSet()

        return badges.map { badge ->
            BadgeStatus(
                badge = badge,
                earned = badge.id in earnedIds || badge.requirement(entity, todaySalesCount, todayBalanceChecks),
                justEarned = badge.id !in earnedIds && badge.requirement(entity, todaySalesCount, todayBalanceChecks)
            )
        }
    }

    /**
     * Get streak info with protection status.
     */
    suspend fun getStreakInfo(): StreakInfo {
        val entity = getState()
        val currentWeek = LocalDate.now().get(WeekFields.of(Locale.getDefault()).weekOfWeekBasedYear())
        val protectionsAvailable = if (entity.protectionWeek == currentWeek) {
            MAX_STREAK_PROTECTIONS_PER_WEEK - entity.streakProtectionsUsed
        } else {
            MAX_STREAK_PROTECTIONS_PER_WEEK
        }

        return StreakInfo(
            currentStreak = entity.currentStreak,
            longestStreak = entity.longestStreak,
            protectionsAvailable = protectionsAvailable,
            lastActiveDay = entity.lastActiveDay
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Update daily streak. Called whenever user performs any tracked action.
     * Uses the streak protection system: 1 free miss per week.
     */
    private suspend fun updateDailyStreak() {
        val entity = gamificationDao.getGamification() ?: return
        val today = LocalDate.now().toEpochDay()
        val lastActive = entity.lastActiveDay

        if (lastActive == today) return // Already counted today

        val daysSinceLastActive = (today - lastActive).toInt()

        val newStreak = when {
            // First ever activity
            lastActive == 0L -> 1
            // Consecutive day
            daysSinceLastActive == 1 -> entity.currentStreak + 1
            // Missed 1 day — check streak protection
            daysSinceLastActive == 2 -> {
                val currentWeek = LocalDate.now().get(WeekFields.of(Locale.getDefault()).weekOfWeekBasedYear())
                val protectionsUsed = if (entity.protectionWeek == currentWeek) {
                    entity.streakProtectionsUsed
                } else {
                    0 // New week, reset
                }

                if (protectionsUsed < MAX_STREAK_PROTECTIONS_PER_WEEK) {
                    // Use streak protection
                    Timber.d(TAG, "Streak protection used (week %d)", currentWeek)
                    entity.currentStreak + 1 // Streak continues
                } else {
                    // No protection available, streak breaks
                    1
                }
            }
            // Missed 2+ days, streak resets
            daysSinceLastActive > 2 -> 1
            else -> entity.currentStreak
        }

        val currentWeek = LocalDate.now().get(WeekFields.of(Locale.getDefault()).weekOfWeekBasedYear())
        val protectionsUsed = if (daysSinceLastActive == 2 &&
            entity.protectionWeek == currentWeek &&
            entity.streakProtectionsUsed < MAX_STREAK_PROTECTIONS_PER_WEEK
        ) {
            entity.streakProtectionsUsed + 1
        } else if (entity.protectionWeek != currentWeek) {
            0 // New week
        } else {
            entity.streakProtectionsUsed
        }

        gamificationDao.updateStreak(
            streak = newStreak,
            day = today,
            protections = protectionsUsed,
            week = currentWeek
        )

        // Award streak points if streak was maintained
        if (newStreak > entity.currentStreak || daysSinceLastActive == 1) {
            gamificationDao.addPoints(POINTS_DAILY_STREAK)
        }

        Timber.d(TAG, "Streak updated: %d → %d (days since: %d)", entity.currentStreak, newStreak, daysSinceLastActive)
    }

    /**
     * Evaluate level changes and new badges after an action.
     */
    private suspend fun evaluateChanges(entity: GamificationEntity, language: String): GamificationEvent {
        val oldLevel = entity.level
        val newLevel = calculateLevel(entity.totalPoints)
        val levelUp = newLevel > oldLevel

        // Check for new badges
        val earnedIds = entity.earnedBadges.split(",").filter { it.isNotBlank() }.toMutableSet()
        val newBadges = mutableListOf<Badge>()

        for (badge in badges) {
            if (badge.id !in earnedIds && badge.requirement(entity, 0, 0)) {
                newBadges.add(badge)
                earnedIds.add(badge.id)
            }
        }

        // Persist level & badges if changed
        if (levelUp || newBadges.isNotEmpty()) {
            gamificationDao.updateLevelAndBadges(
                level = newLevel,
                badges = earnedIds.joinToString(",")
            )
        }

        // Build celebration messages
        val messages = mutableListOf<String>()
        if (levelUp) {
            messages.add(if (language == "sw") {
                "🎉 Umefika Level ${LEVEL_NAMES_SW[newLevel]} ${LEVEL_EMOJIS[newLevel]}!"
            } else {
                "🎉 You reached Level ${LEVEL_NAMES_EN[newLevel]} ${LEVEL_EMOJIS[newLevel]}!"
            })
        }

        for (badge in newBadges) {
            messages.add(if (language == "sw") {
                "${badge.emoji} Badge mpya: ${badge.nameSw}!"
            } else {
                "${badge.emoji} New badge: ${badge.nameEn}!"
            })
        }

        return GamificationEvent(
            pointsEarned = 0, // Caller should set this
            totalPoints = entity.totalPoints,
            currentLevel = newLevel,
            levelUp = levelUp,
            newBadges = newBadges,
            messages = messages,
            streakInfo = getStreakInfo()
        )
    }

    private fun calculateLevel(points: Int): Int {
        for (i in LEVEL_THRESHOLDS.indices.reversed()) {
            if (points >= LEVEL_THRESHOLDS[i]) return i
        }
        return 0
    }

    private fun calculateLevelProgress(entity: GamificationEntity): Float {
        if (entity.level >= 5) return 1.0f
        val current = entity.totalPoints - LEVEL_THRESHOLDS[entity.level]
        val needed = LEVEL_THRESHOLDS[entity.level + 1] - LEVEL_THRESHOLDS[entity.level]
        return (current.toFloat() / needed).coerceIn(0f, 1f)
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

data class GamificationEvent(
    val pointsEarned: Int,
    val totalPoints: Int,
    val currentLevel: Int,
    val levelUp: Boolean,
    val newBadges: List<Badge>,
    val messages: List<String>,
    val streakInfo: StreakInfo
) {
    companion object {
        fun empty() = GamificationEvent(0, 0, 0, false, emptyList(), emptyList(), StreakInfo(0, 0, 1, 0))
    }
}

data class LevelInfo(
    val levelIndex: Int,
    val nameSw: String,
    val nameEn: String,
    val emoji: String,
    val totalPoints: Int,
    val nextLevelPoints: Int,
    val progress: Float
)

data class BadgeStatus(
    val badge: Badge,
    val earned: Boolean,
    val justEarned: Boolean
)

data class StreakInfo(
    val currentStreak: Int,
    val longestStreak: Int,
    val protectionsAvailable: Int,
    val lastActiveDay: Long
)
