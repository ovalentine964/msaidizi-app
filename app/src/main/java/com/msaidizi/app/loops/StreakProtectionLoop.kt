package com.msaidizi.app.loops

import com.msaidizi.app.core.database.GamificationDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.GamificationEntity
import com.msaidizi.app.gamification.GamificationEngine
import timber.log.Timber
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Streak Protection Loop — preserves the worker's daily streak.
 *
 * Loop flow:
 *   6PM check → Worker hasn't recorded today → Voice reminder in Swahili →
 *   Worker records → Streak preserved → Encouragement → Continue tomorrow
 *
 * This loop leverages loss aversion: people feel the pain of losing
 * something (their streak) more than the pleasure of gaining something.
 * By reminding workers before their streak breaks, we create urgency
 * that drives daily engagement.
 *
 * Features:
 * - Evening reminder at 6 PM if no activity today
 * - Streak freeze: 1 free miss per week (already in GamificationEngine)
 * - Celebration messages at streak milestones (3, 7, 14, 30, 60 days)
 * - Encouragement after streak breaks to restart
 *
 * Behavioral design:
 * - Loss aversion: "Your 7-day streak is at risk!"
 * - Variable messaging: Different reminders each time
 * - Celebration: Positive reinforcement at milestones
 * - Recovery: Gentle encouragement when streak breaks
 *
 * @param gamificationEngine Core gamification state
 * @param gamificationDao Direct access for streak queries
 * @param transactionDao To check if worker recorded today
 */
class StreakProtectionLoop(
    private val gamificationEngine: GamificationEngine,
    private val gamificationDao: GamificationDao,
    private val transactionDao: TransactionDao
) {
    companion object {
        private const val TAG = "StreakProtectionLoop"

        /** Streak milestones that trigger celebrations */
        private val STREAK_MILESTONES = setOf(3, 7, 14, 21, 30, 45, 60, 90, 120, 180, 365)

        /** Multiplier bonuses at streak milestones */
        private val STREAK_MULTIPLIERS = mapOf(
            5 to 2,   // 5-day streak: 2x points
            10 to 3,  // 10-day streak: 3x points
            30 to 5   // 30-day streak: 5x points
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // EVENING REMINDER — 6 PM check
    // ═══════════════════════════════════════════════════════════════

    /**
     * Execute the evening streak protection check.
     *
     * Called by WorkManager at 6 PM daily. If the worker hasn't
     * recorded any transactions today and has an active streak,
     * sends a voice reminder in Swahili.
     *
     * @param language Language preference
     * @return StreakProtectionResult with reminder message (null if no reminder needed)
     */
    suspend fun executeEveningCheck(language: String = "sw"): StreakProtectionResult {
        Timber.tag(TAG).d("Executing evening streak protection check")

        val entity = gamificationDao.getGamification() ?: return StreakProtectionResult.noAction()

        // No streak to protect
        if (entity.currentStreak == 0) {
            Timber.tag(TAG).d("No active streak, no reminder needed")
            return StreakProtectionResult.noAction()
        }

        // Check if worker has recorded today
        val hasRecordedToday = checkHasRecordedToday()
        if (hasRecordedToday) {
            Timber.tag(TAG).d("Worker has recorded today, streak is safe")
            return StreakProtectionResult.noAction()
        }

        // Worker hasn't recorded — streak is at risk!
        val reminder = generateReminder(entity, language)
        val streakFreezeAvailable = checkStreakFreezeAvailable(entity)

        Timber.tag(TAG).d(
            "Streak at risk! Current: %d days. Freeze available: %b",
            entity.currentStreak, streakFreezeAvailable
        )

        return StreakProtectionResult(
            shouldRemind = true,
            reminderMessage = reminder,
            currentStreak = entity.currentStreak,
            streakFreezeAvailable = streakFreezeAvailable,
            streakFreezeMessage = if (streakFreezeAvailable) {
                generateStreakFreezeOffer(language)
            } else null
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // STREAK CELEBRATION — after recording
    // ═══════════════════════════════════════════════════════════════

    /**
     * Check if a streak milestone was just reached and generate celebration.
     *
     * Called after the worker records a transaction and the streak updates.
     *
     * @param language Language preference
     * @return Celebration message if milestone reached, null otherwise
     */
    suspend fun checkStreakMilestone(language: String = "sw"): String? {
        val entity = gamificationDao.getGamification() ?: return null
        val streak = entity.currentStreak

        if (streak !in STREAK_MILESTONES) return null

        return generateMilestoneCelebration(streak, language)
    }

    /**
     * Get the current streak multiplier for point bonuses.
     *
     * Higher streaks earn more points per action, creating
     * an investment effect that makes workers value their streak.
     *
     * @return Multiplier (1x, 2x, 3x, or 5x)
     */
    suspend fun getStreakMultiplier(): Int {
        val entity = gamificationDao.getGamification() ?: return 1
        val streak = entity.currentStreak

        return when {
            streak >= 30 -> 5
            streak >= 10 -> 3
            streak >= 5 -> 2
            else -> 1
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // STREAK BROKEN — encouragement to restart
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate encouragement message when a streak is broken.
     *
     * Called when the worker's streak resets to 0 after missing
     * more than 1 day (and no freeze available).
     *
     * @param lostStreak The streak that was lost
     * @param language Language preference
     * @return Encouragement message
     */
    fun generateStreakBrokenMessage(lostStreak: Int, language: String = "sw"): String {
        return if (language == "sw") {
            when {
                lostStreak >= 30 -> "😢 Streak yako ya siku $lostStreak imepotea. " +
                    "Lakini usikate tamaa! Kila siku ni nafasi mpya. Anza tena leo! 💪"
                lostStreak >= 7 -> "😔 Wiki $lostStreak za bidii zimepotea. " +
                    "Lakini wewe ni mfanyabiashara — simama tena! Anza mfululizo mpya leo."
                else -> "💪 Streak yako imepotea, lakini sio mwisho! " +
                    "Rekodi mauzo leo na uanze mfululizo mpya."
            }
        } else {
            when {
                lostStreak >= 30 -> "😢 Your $lostStreak-day streak is gone. " +
                    "But don't give up! Every day is a new chance. Start again today! 💪"
                lostStreak >= 7 -> "😔 $lostStreak weeks of effort lost. " +
                    "But you're a business person — get back up! Start a new streak today."
                else -> "💪 Your streak is gone, but it's not the end! " +
                    "Record a sale today and start a new streak."
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // STREAK STATUS — for UI display
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get full streak status for display.
     */
    suspend fun getStreakStatus(language: String = "sw"): StreakStatus {
        val entity = gamificationDao.getGamification() ?: return StreakStatus.empty()
        val hasRecordedToday = checkHasRecordedToday()
        val multiplier = getStreakMultiplier()
        val nextMilestone = STREAK_MILESTONES.filter { it > entity.currentStreak }.minOrNull()

        return StreakStatus(
            currentStreak = entity.currentStreak,
            longestStreak = entity.longestStreak,
            hasRecordedToday = hasRecordedToday,
            multiplier = multiplier,
            nextMilestone = nextMilestone,
            daysToMilestone = nextMilestone?.minus(entity.currentStreak),
            streakFreezeAvailable = checkStreakFreezeAvailable(entity),
            statusMessage = generateStatusMessage(entity, hasRecordedToday, language)
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Check if the worker has recorded any transaction today.
     */
    private suspend fun checkHasRecordedToday(): Boolean {
        val todayStart = LocalDate.now().atStartOfDay()
            .toEpochSecond(java.time.ZoneOffset.UTC)
        val now = System.currentTimeMillis() / 1000
        val count = transactionDao.getTransactionCount(todayStart, now)
        return count > 0
    }

    /**
     * Check if streak freeze is available this week.
     * Already handled by GamificationEngine, but we check here for UI.
     */
    private fun checkStreakFreezeAvailable(entity: GamificationEntity): Boolean {
        val currentWeek = LocalDate.now().get(WeekFields.of(Locale.getDefault()).weekOfWeekBasedYear())
        return if (entity.protectionWeek == currentWeek) {
            entity.streakProtectionsUsed < GamificationEngine.MAX_STREAK_PROTECTIONS_PER_WEEK
        } else {
            true // New week, protection reset
        }
    }

    /**
     * Generate a reminder message based on streak length.
     * Uses variable messaging to avoid habituation.
     */
    private fun generateReminder(entity: GamificationEntity, language: String): String {
        val streak = entity.currentStreak

        val reminders = if (language == "sw") {
            listOf(
                "⚠️ Hujaerekodi leo! Streak yako ya $streak siku inaweza kupotea. Sema mauzo yako sasa!",
                "🔥 Streak yako ya $streak siku iko hatarini! Rekodi mauzo leo ili usiipoteze.",
                "⏰ Mama! Hujaerekodi leo. Streak yako ya $streak siku — usiiruhusu ipotee!",
                "💪 Umekuwa na mfululizo wa $streak siku! Rekodi leo ili kuendelea.",
                "🚨 Tahadhari! Streak yako ya $streak siku itapotea usiporekodi leo."
            )
        } else {
            listOf(
                "⚠️ You haven't recorded today! Your $streak-day streak is at risk. Record your sales now!",
                "🔥 Your $streak-day streak is in danger! Record today to keep it going.",
                "⏰ Hey! You haven't recorded today. Your $streak-day streak — don't let it break!",
                "💪 You've been on a $streak-day streak! Record today to continue.",
                "🚨 Warning! Your $streak-day streak will be lost if you don't record today."
            )
        }

        // Add streak freeze offer if available
        val freezeNote = if (checkStreakFreezeAvailable(entity)) {
            if (language == "sw") "\n\n🛡️ Pia, una kinga ya mfululizo 1 wiki hii — unaweza kutumia ikiwa hujaweza kurekodi."
            else "\n\n🛡️ Also, you have 1 streak freeze this week — you can use it if you can't record."
        } else ""

        return reminders.random() + freezeNote
    }

    /**
     * Generate streak freeze offer message.
     */
    private fun generateStreakFreezeOffer(language: String): String {
        return if (language == "sw") {
            "🛡️ Unaweza kutumia kinga ya mfululizo. Streak yako itaendelea hata kama hujarekodi leo. " +
            "Sema 'Tumia kinga' kutumia."
        } else {
            "🛡️ You can use a streak freeze. Your streak will continue even if you don't record today. " +
            "Say 'Use freeze' to activate."
        }
    }

    /**
     * Generate celebration for streak milestones.
     */
    private fun generateMilestoneCelebration(streak: Int, language: String): String {
        val (emoji, message) = when (streak) {
            3 -> "🛡️" to if (language == "sw") "Mfululizo wa siku 3! Umeanza vizuri!" else "3-day streak! Great start!"
            7 -> "⚔️" to if (language == "sw") "Wiki 1 kamili! Wewe ni mfanyabiashara wa kweli!" else "1 full week! You're a real business person!"
            14 -> "🔥" to if (language == "sw") "Wiki 2 mfululizo! Bidii yako inaonekana!" else "2 weeks straight! Your effort shows!"
            21 -> "💪" to if (language == "sw") "Wiki 3! Tabia imejengwa!" else "3 weeks! Habit formed!"
            30 -> "🥇" to if (language == "sw") "MWEZI KAMILI! Wewe ni Legend! 🔥🔥🔥" else "FULL MONTH! You're a Legend! 🔥🔥🔥"
            45 -> "⭐" to if (language == "sw") "Siku 45! Wewe ni mfano wa wengine!" else "45 days! You're an example to others!"
            60 -> "🔥🔥" to if (language == "sw") "MIEZI 2! Streak ya kihistoria!" else "2 MONTHS! Historic streak!"
            90 -> "👑" to if (language == "sw") "ROBO MWAKA! Wewe ni Kiongozi!" else "QUARTER YEAR! You're a Leader!"
            120 -> "💎" to if (language == "sw") "MIEZI 4! Streak ya kimataifa!" else "4 MONTHS! World-class streak!"
            180 -> "🏆" to if (language == "sw") "NUSU MWAKA! Bingwa wa biashara!" else "HALF YEAR! Business champion!"
            365 -> "🌟" to if (language == "sw") "MWAKA KAMILI! Wewe ni LEGEND ya kweli!" else "FULL YEAR! You're a TRUE LEGEND!"
            else -> "🔥" to if (language == "sw") "Streak ya $streak siku!" else "$streak-day streak!"
        }

        val multiplier = STREAK_MULTIPLIERS.entries
            .filter { streak >= it.key }
            .maxByOrNull { it.key }
            ?.value

        val multiplierText = if (multiplier != null && multiplier > 1) {
            if (language == "sw") "\n\n🎯 Pointi zako sasa ni ×$multiplier!"
            else "\n\n🎯 Your points are now ×$multiplier!"
        } else ""

        return "$emoji $message$multiplierText"
    }

    /**
     * Generate status message for streak display.
     */
    private fun generateStatusMessage(
        entity: GamificationEntity,
        hasRecordedToday: Boolean,
        language: String
    ): String {
        val streak = entity.currentStreak

        return if (language == "sw") {
            when {
                streak == 0 -> "Anza mfululizo wako leo! Rekodi mauzo."
                hasRecordedToday -> "🔥 Streak yako: $streak siku! Umerekodi leo. Nzuri!"
                streak >= 30 -> "🔥 Streak yako: $streak siku! Wewe ni Legend!"
                streak >= 7 -> "🔥 Streak yako: $streak siku! Endelea hivi!"
                else -> "🔥 Streak yako: $streak siku. Rekodi leo ili kuendelea!"
            }
        } else {
            when {
                streak == 0 -> "Start your streak today! Record a sale."
                hasRecordedToday -> "🔥 Your streak: $streak days! You recorded today. Nice!"
                streak >= 30 -> "🔥 Your streak: $streak days! You're a Legend!"
                streak >= 7 -> "🔥 Your streak: $streak days! Keep it up!"
                else -> "🔥 Your streak: $streak days. Record today to continue!"
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

/**
 * Result of the evening streak protection check.
 */
data class StreakProtectionResult(
    val shouldRemind: Boolean,
    val reminderMessage: String? = null,
    val currentStreak: Int = 0,
    val streakFreezeAvailable: Boolean = false,
    val streakFreezeMessage: String? = null
) {
    companion object {
        fun noAction() = StreakProtectionResult(shouldRemind = false)
    }
}

/**
 * Full streak status for UI display.
 */
data class StreakStatus(
    val currentStreak: Int,
    val longestStreak: Int,
    val hasRecordedToday: Boolean,
    val multiplier: Int,
    val nextMilestone: Int?,
    val daysToMilestone: Int?,
    val streakFreezeAvailable: Boolean,
    val statusMessage: String
) {
    companion object {
        fun empty() = StreakStatus(0, 0, false, 1, null, null, false, "")
    }
}
