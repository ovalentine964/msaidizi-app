package com.msaidizi.app.superagent.gamification

import timber.log.Timber

/**
 * Gamification Module — unified entry point for points, badges, levels,
 * streaks, and social features.
 *
 * ## Architecture
 * This module is a capability of the superagent — called directly by the
 * ReasoningEngine, not via event bus. It's mostly passive (triggered by
 * other modules' actions) but also handles direct queries.
 *
 * ## Sub-modules
 * - [PointsEngine] — point accumulation and variable rewards
 * - [BadgeEngine] — 15+ Swahili badges for milestones
 * - [LevelEngine] — 6 levels from Mwanafunzi to Legend
 * - [StreakEngine] — daily streaks with protection
 * - [SocialEngine] — peer comparison and leaderboard
 *
 * ## Passive Events
 * Most gamification is triggered by other modules:
 * - Sale recorded → PointsEngine + StreakEngine + BadgeEngine
 * - Balance checked → PointsEngine
 * - Lesson completed → PointsEngine
 * - Goal set → PointsEngine
 * - Tithe recorded → PointsEngine
 *
 * @param pointsEngine Points accumulation engine
 * @param badgeEngine Badge achievement engine
 * @param levelEngine Level progression engine
 * @param streakEngine Streak tracking engine
 * @param socialEngine Social comparison engine
 */
class GamificationModule(
    private val pointsEngine: PointsEngine,
    private val badgeEngine: BadgeEngine,
    private val levelEngine: LevelEngine,
    private val streakEngine: StreakEngine,
    private val socialEngine: SocialEngine
) {
    companion object {
        private const val TAG = "GamificationModule"
    }

    // ═══════════════ PASSIVE EVENTS ═══════════════
    // These are called by other modules after successful actions.

    /**
     * Handle a sale being recorded — awards points, updates streak, checks badges.
     *
     * @param language "sw" or "en"
     * @return GamificationEvent with all updates and messages
     */
    suspend fun onSaleRecorded(language: String = "sw"): GamificationEvent {
        val oldPoints = pointsEngine.getTotalPoints()

        // Update streak
        val streakUpdate = streakEngine.recordActivity(language)

        // Award points
        val pointsResult = pointsEngine.awardPoints("sale", streakUpdate.newStreak, language)

        // Check level up
        val newPoints = oldPoints + pointsResult.totalPoints
        val levelUp = levelEngine.checkLevelUp(oldPoints, newPoints, language)

        // Check badges
        val stats = buildBadgeCheckStats(newPoints, streakUpdate.newStreak)
        val newBadges = badgeEngine.checkNewBadges(stats, language)

        // Build messages
        val messages = mutableListOf<String>()
        pointsResult.streakMessage?.let { messages.add(it) }
        pointsResult.bonusMessage?.let { messages.add(it) }
        streakUpdate.message?.let { messages.add(it) }
        levelUp?.let { messages.add(it.celebration) }
        newBadges.forEach { messages.add(it.message) }

        // Surprise praise
        getSurprisePraise(language)?.let { messages.add(it) }

        return GamificationEvent(
            pointsEarned = pointsResult.totalPoints,
            totalPoints = newPoints,
            currentLevel = levelUp?.newLevel ?: levelEngine.calculateLevel(newPoints),
            levelUp = levelUp != null,
            newBadges = newBadges.map { it.badge },
            messages = messages,
            streakInfo = streakEngine.getStreakInfo(language)
        )
    }

    /**
     * Handle a balance check — awards points.
     */
    suspend fun onBalanceChecked(language: String = "sw"): GamificationEvent {
        val oldPoints = pointsEngine.getTotalPoints()
        val streakUpdate = streakEngine.recordActivity(language)
        val pointsResult = pointsEngine.awardPoints("balance_check", streakUpdate.newStreak, language)
        val newPoints = oldPoints + pointsResult.totalPoints

        return GamificationEvent(
            pointsEarned = pointsResult.totalPoints,
            totalPoints = newPoints,
            currentLevel = levelEngine.calculateLevel(newPoints),
            levelUp = false,
            newBadges = emptyList(),
            messages = listOfNotNull(pointsResult.bonusMessage),
            streakInfo = streakEngine.getStreakInfo(language)
        )
    }

    /**
     * Handle a lesson being completed — awards points.
     */
    suspend fun onLessonCompleted(language: String = "sw"): GamificationEvent {
        val oldPoints = pointsEngine.getTotalPoints()
        val streakUpdate = streakEngine.recordActivity(language)
        val pointsResult = pointsEngine.awardPoints("mindset_lesson", streakUpdate.newStreak, language)
        val newPoints = oldPoints + pointsResult.totalPoints
        val levelUp = levelEngine.checkLevelUp(oldPoints, newPoints, language)

        val messages = mutableListOf<String>()
        pointsResult.bonusMessage?.let { messages.add(it) }
        levelUp?.let { messages.add(it.celebration) }

        return GamificationEvent(
            pointsEarned = pointsResult.totalPoints,
            totalPoints = newPoints,
            currentLevel = levelUp?.newLevel ?: levelEngine.calculateLevel(newPoints),
            levelUp = levelUp != null,
            newBadges = emptyList(),
            messages = messages,
            streakInfo = streakEngine.getStreakInfo(language)
        )
    }

    /**
     * Handle tithe/giving being recorded — awards points.
     */
    suspend fun onGivingRecorded(language: String = "sw"): GamificationEvent {
        val oldPoints = pointsEngine.getTotalPoints()
        val streakUpdate = streakEngine.recordActivity(language)
        val pointsResult = pointsEngine.awardPoints("giving", streakUpdate.newStreak, language)
        val newPoints = oldPoints + pointsResult.totalPoints

        return GamificationEvent(
            pointsEarned = pointsResult.totalPoints,
            totalPoints = newPoints,
            currentLevel = levelEngine.calculateLevel(newPoints),
            levelUp = false,
            newBadges = emptyList(),
            messages = listOfNotNull(pointsResult.bonusMessage),
            streakInfo = streakEngine.getStreakInfo(language)
        )
    }

    /**
     * Handle all daily habits being completed — awards bonus points.
     */
    suspend fun onAllHabitsCompleted(language: String = "sw"): GamificationEvent {
        val oldPoints = pointsEngine.getTotalPoints()
        val pointsResult = pointsEngine.awardPoints("habits_bonus", 0, language)
        val newPoints = oldPoints + pointsResult.totalPoints
        val levelUp = levelEngine.checkLevelUp(oldPoints, newPoints, language)

        val messages = mutableListOf<String>()
        if (language == "sw") {
            messages.add("🎉 Umekamilisha tabia zote 10 leo! +${pointsResult.totalPoints} pointi!")
        } else {
            messages.add("🎉 All 10 habits completed today! +${pointsResult.totalPoints} points!")
        }
        levelUp?.let { messages.add(it.celebration) }

        return GamificationEvent(
            pointsEarned = pointsResult.totalPoints,
            totalPoints = newPoints,
            currentLevel = levelUp?.newLevel ?: levelEngine.calculateLevel(newPoints),
            levelUp = levelUp != null,
            newBadges = emptyList(),
            messages = messages,
            streakInfo = streakEngine.getStreakInfo(language)
        )
    }

    // ═══════════════ DIRECT QUERIES ═══════════════

    /**
     * Handle a gamification query from the worker.
     *
     * @param type The type of query
     * @param language "sw" or "en"
     * @return Query response with voice-ready content
     */
    suspend fun handleQuery(type: GamificationQueryType, language: String = "sw"): GamificationQueryResponse {
        return when (type) {
            GamificationQueryType.BADGE_QUERY -> handleBadgeQuery(language)
            GamificationQueryType.LEVEL_QUERY -> handleLevelQuery(language)
            GamificationQueryType.STREAK_QUERY -> handleStreakQuery(language)
            GamificationQueryType.POINTS_QUERY -> handlePointsQuery(language)
            GamificationQueryType.LEADERBOARD -> handleLeaderboardQuery(language)
            GamificationQueryType.OVERVIEW -> handleOverviewQuery(language)
        }
    }

    /**
     * Get streak risk reminder if applicable.
     */
    suspend fun getStreakRiskReminder(language: String = "sw"): String? {
        return streakEngine.getStreakRiskReminder(language)
    }

    /**
     * Get social proof message if applicable.
     */
    suspend fun getSocialProof(workerStats: WorkerStats, language: String = "sw"): String? {
        return socialEngine.getSocialProof(workerStats, language)
    }

    // ═══════════════ PRIVATE QUERY HANDLERS ═══════════════

    private suspend fun handleBadgeQuery(language: String): GamificationQueryResponse {
        val stats = buildBadgeCheckStats(pointsEngine.getTotalPoints(), streakEngine.getStreakInfo().currentStreak)
        val badges = badgeEngine.getAllBadgeStatus(stats)
        val earned = badges.count { it.earned }

        val text = if (language == "sw") {
            "🏅 Umepata badge $earned kati ya ${badges.size}. " +
            badges.filter { it.earned }.joinToString(", ") { "${it.badge.emoji} ${it.badge.nameSw}" }
        } else {
            "🏅 You've earned $earned of ${badges.size} badges. " +
            badges.filter { it.earned }.joinToString(", ") { "${it.badge.emoji} ${it.badge.nameEn}" }
        }

        return GamificationQueryResponse(text = text, shouldSpeak = true)
    }

    private suspend fun handleLevelQuery(language: String): GamificationQueryResponse {
        val text = levelEngine.getProgressVoice(pointsEngine.getTotalPoints(), language)
        return GamificationQueryResponse(text = text, shouldSpeak = true)
    }

    private suspend fun handleStreakQuery(language: String): GamificationQueryResponse {
        val info = streakEngine.getStreakInfo(language)
        val text = if (language == "sw") {
            "🔥 Streak yako ya siku ${info.currentStreak}. Rekodi yako bora: ${info.longestStreak}. " +
            "Kinga ya bure wiki hii: ${info.protectionsAvailable}."
        } else {
            "🔥 Your streak: ${info.currentStreak} days. Best: ${info.longestStreak}. " +
            "Free protection this week: ${info.protectionsAvailable}."
        }
        return GamificationQueryResponse(text = text, shouldSpeak = true)
    }

    private suspend fun handlePointsQuery(language: String): GamificationQueryResponse {
        val total = pointsEngine.getTotalPoints()
        val today = pointsEngine.getTodayPoints()
        val text = if (language == "sw") {
            "💎 Pointi zako: $total jumla. Leo: $today. Level: ${levelEngine.getLevelInfo(total, language).nameSw}."
        } else {
            "💎 Your points: $total total. Today: $today. Level: ${levelEngine.getLevelInfo(total, language).nameEn}."
        }
        return GamificationQueryResponse(text = text, shouldSpeak = true)
    }

    private suspend fun handleLeaderboardQuery(language: String): GamificationQueryResponse {
        val leaderboard = socialEngine.getLeaderboard("current_worker", "general", language)
        return GamificationQueryResponse(text = leaderboard.message, shouldSpeak = true)
    }

    private suspend fun handleOverviewQuery(language: String): GamificationQueryResponse {
        val points = pointsEngine.getTotalPoints()
        val level = levelEngine.getLevelInfo(points, language)
        val streak = streakEngine.getStreakInfo(language)
        val stats = buildBadgeCheckStats(points, streak.currentStreak)
        val earnedBadges = badgeEngine.getEarnedCount()

        val text = if (language == "sw") {
            "📊 Muhtasari wako: " +
            "Level ${level.nameSw} ${level.emoji}. " +
            "Pointi $points. " +
            "Streak siku ${streak.currentStreak}. " +
            "Badge $earnedBadges."
        } else {
            "📊 Your overview: " +
            "Level ${level.nameEn} ${level.emoji}. " +
            "Points: $points. " +
            "Streak: ${streak.currentStreak} days. " +
            "Badges: $earnedBadges."
        }

        return GamificationQueryResponse(text = text, shouldSpeak = true)
    }

    // ═══════════════ HELPERS ═══════════════

    private suspend fun buildBadgeCheckStats(totalPoints: Int, currentStreak: Int): BadgeCheckStats {
        return BadgeCheckStats(
            totalSales = 0 // Wired via onSaleRecorded events from FinancialModule
            todaySales = 0,
            currentStreak = currentStreak,
            totalBalanceChecks = 0,
            currentLevel = levelEngine.calculateLevel(totalPoints),
            totalPoints = totalPoints
        )
    }

    private fun getSurprisePraise(language: String): String? {
        if (Math.random() > 0.15) return null

        val praises = if (language == "sw") listOf(
            "⭐ Wewe ni mfanyabiashara bora! Endelea hivi!",
            "🔥 Biashara yako inakua! Nakutakia kila la heri!",
            "💪 Umefanya kazi nzuri leo! Najivunia wewe!",
            "🌟 Ujasiri wako wa biashara ni wa ajabu!",
            "👏 Hongera! Umefanya vizuri sana!"
        ) else listOf(
            "⭐ You're an amazing business person!",
            "🔥 Your business is growing!",
            "💪 Great work today!",
            "🌟 Your business spirit is incredible!",
            "👏 Congratulations!"
        )
        return praises.random()
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/**
 * Types of gamification queries.
 */
enum class GamificationQueryType {
    BADGE_QUERY,
    LEVEL_QUERY,
    STREAK_QUERY,
    POINTS_QUERY,
    LEADERBOARD,
    OVERVIEW
}

/**
 * Response to a gamification query.
 */
data class GamificationQueryResponse(
    val text: String,
    val shouldSpeak: Boolean = true
)

/**
 * Unified gamification event — returned after any action that affects gamification.
 */
data class GamificationEvent(
    val pointsEarned: Int,
    val totalPoints: Int,
    val currentLevel: Int,
    val levelUp: Boolean,
    val newBadges: List<BadgeDef>,
    val messages: List<String>,
    val streakInfo: StreakInfo
) {
    companion object {
        fun empty() = GamificationEvent(
            pointsEarned = 0,
            totalPoints = 0,
            currentLevel = 0,
            levelUp = false,
            newBadges = emptyList(),
            messages = emptyList(),
            streakInfo = StreakInfo(0, 0, 1, 0)
        )
    }
}
