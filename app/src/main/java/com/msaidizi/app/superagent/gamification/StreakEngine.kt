package com.msaidizi.app.superagent.gamification

import timber.log.Timber
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Streak Engine — daily streaks with 1 free miss per week.
 *
 * ## Streak Rules
 * - Worker must perform at least 1 tracked action per day
 * - Streak increments by 1 for each consecutive day
 * - 1 free miss per week (streak protection)
 * - Protection resets every Monday
 * - Missing 2+ days resets streak to 1
 *
 * ## Streak Protection
 * If a worker misses 1 day but has protection available:
 * - Streak continues (doesn't break)
 * - Protection is consumed for that week
 * - Worker is notified: "Your streak was saved!"
 *
 * ## Streak Recovery
 * If a worker loses a streak of 7+ days:
 * - Offer recovery: "Your X-day streak broke! Want to recover it?"
 * - Recovery costs: complete 3 habits in one day OR record 5 sales
 * - Only offered once per lost streak
 *
 * ## Motivational Messages
 * - Streak risk alerts (after 6 PM if no activity)
 * - Streak milestone celebrations (7, 14, 30, 60, 90 days)
 * - Pattern insights ("You're most consistent on weekdays!")
 *
 * @param streakProvider Provides access to streak storage
 */
class StreakEngine(
    private val streakProvider: StreakProvider
) {
    companion object {
        private const val TAG = "StreakEngine"

        /** Maximum streak protections per week */
        const val MAX_PROTECTIONS_PER_WEEK = 1

        /** Minimum streak for recovery offer */
        const val MIN_STREAK_FOR_RECOVERY = 7

        /** Milestone streaks for celebrations */
        val STREAK_MILESTONES = listOf(3, 7, 14, 21, 30, 60, 90, 180, 365)
    }

    /**
     * Record daily activity and update streak.
     *
     * @param language "sw" or "en"
     * @return StreakUpdate with new streak status and any messages
     */
    suspend fun recordActivity(language: String = "sw"): StreakUpdate {
        val today = LocalDate.now().toEpochDay()
        val state = streakProvider.getStreakState()

        if (state.lastActiveDay == today) {
            return StreakUpdate(
                previousStreak = state.currentStreak,
                newStreak = state.currentStreak,
                streakMaintained = true,
                protectionUsed = false,
                message = null,
                milestone = null
            )
        }

        val daysSinceLastActive = (today - state.lastActiveDay).toInt()
        val newStreak: Int
        var protectionUsed = false
        var message: String? = null
        var recoveryOffered = false

        when {
            // First ever activity
            state.lastActiveDay == 0L -> {
                newStreak = 1
            }
            // Consecutive day
            daysSinceLastActive == 1 -> {
                newStreak = state.currentStreak + 1
            }
            // Missed 1 day — check streak protection
            daysSinceLastActive == 2 -> {
                val currentWeek = getCurrentWeek()
                val protectionsUsed = if (state.protectionWeek == currentWeek) {
                    state.protectionsUsed
                } else {
                    0 // New week, reset
                }

                if (protectionsUsed < MAX_PROTECTIONS_PER_WEEK) {
                    // Use streak protection
                    newStreak = state.currentStreak + 1
                    protectionUsed = true
                    message = getProtectionMessage(state.currentStreak + 1, language)
                    Timber.d(TAG, "Streak protection used")
                } else {
                    // No protection available, streak breaks
                    val lostStreak = state.currentStreak
                    newStreak = 1
                    if (lostStreak >= MIN_STREAK_FOR_RECOVERY) {
                        recoveryOffered = true
                        streakProvider.setRecoveryOffer(lostStreak)
                    }
                }
            }
            // Missed 2+ days, streak resets
            daysSinceLastActive > 2 -> {
                val lostStreak = state.currentStreak
                newStreak = 1
                if (lostStreak >= MIN_STREAK_FOR_RECOVERY) {
                    recoveryOffered = true
                    streakProvider.setRecoveryOffer(lostStreak)
                }
            }
            else -> {
                newStreak = state.currentStreak
            }
        }

        // Update streak state
        val currentWeek = getCurrentWeek()
        val protectionsUsed = if (daysSinceLastActive == 2 && protectionUsed) {
            (if (state.protectionWeek == currentWeek) state.protectionsUsed else 0) + 1
        } else if (state.protectionWeek != currentWeek) {
            0 // New week
        } else {
            state.protectionsUsed
        }

        streakProvider.updateStreak(
            streak = newStreak,
            lastActiveDay = today,
            protectionsUsed = protectionsUsed,
            protectionWeek = currentWeek
        )

        // Check for milestones
        val milestone = checkMilestone(state.currentStreak, newStreak, language)

        // Build message
        if (message == null && milestone != null) {
            message = milestone
        }

        if (message == null && newStreak > state.currentStreak) {
            message = getStreakIncrementMessage(newStreak, language)
        }

        Timber.d(TAG, "Streak updated: %d → %d (days since: %d)",
            state.currentStreak, newStreak, daysSinceLastActive)

        return StreakUpdate(
            previousStreak = state.currentStreak,
            newStreak = newStreak,
            streakMaintained = newStreak > state.currentStreak || daysSinceLastActive == 1,
            protectionUsed = protectionUsed,
            message = message,
            milestone = milestone
        )
    }

    /**
     * Get current streak info.
     */
    suspend fun getStreakInfo(language: String = "sw"): StreakInfo {
        val state = streakProvider.getStreakState()
        val currentWeek = getCurrentWeek()
        val protectionsAvailable = if (state.protectionWeek == currentWeek) {
            MAX_PROTECTIONS_PER_WEEK - state.protectionsUsed
        } else {
            MAX_PROTECTIONS_PER_WEEK
        }

        return StreakInfo(
            currentStreak = state.currentStreak,
            longestStreak = state.longestStreak,
            protectionsAvailable = protectionsAvailable,
            lastActiveDay = state.lastActiveDay
        )
    }

    /**
     * Check if streak is at risk (no activity today, past 6 PM).
     *
     * @return Risk reminder message, or null if not at risk
     */
    suspend fun getStreakRiskReminder(language: String = "sw"): String? {
        val state = streakProvider.getStreakState()
        if (state.currentStreak == 0) return null

        val today = LocalDate.now().toEpochDay()
        if (state.lastActiveDay == today) return null

        val hour = java.time.LocalTime.now().hour
        if (hour < 18) return null

        val streak = state.currentStreak
        val info = getStreakInfo(language)

        return if (language == "sw") {
            when {
                streak >= 30 -> "⚠️ Mama! Streak yako ya siku $streak inaweza kupotea! Rekodi mauzo yako sasa!"
                streak >= 7 -> "⚠️ Streak yako ya wiki ${streak / 7} iko hatarini! Sema mauzo yako leo!"
                info.protectionsAvailable > 0 -> "💡 Streak yako ya siku $streak iko hatarini, lakini una kinga moja ya bure wiki hii."
                else -> "🔥 Streak yako ya siku $streak itapotea usiku huu! Rekodi mauzo yako sasa!"
            }
        } else {
            when {
                streak >= 30 -> "⚠️ Your $streak-day streak is at risk! Record your sales now!"
                streak >= 7 -> "⚠️ Your week ${streak / 7} streak is at risk!"
                info.protectionsAvailable > 0 -> "💡 Your $streak-day streak is at risk, but you have a free protection this week."
                else -> "🔥 Your $streak-day streak will break tonight! Record your sales now!"
            }
        }
    }

    /**
     * Get streak recovery offer if available.
     */
    suspend fun getRecoveryOffer(language: String = "sw"): StreakRecoveryOffer? {
        val recovery = streakProvider.getRecoveryOffer() ?: return null

        return StreakRecoveryOffer(
            lostStreak = recovery.lostStreak,
            message = if (language == "sw") {
                "😢 Streak yako ya siku ${recovery.lostStreak} imevunjika! " +
                "Unataka kuirekebisha? Rekodi mauzo 5 leo na nitakurejeshea!"
            } else {
                "😢 Your ${recovery.lostStreak}-day streak broke! " +
                "Want to recover? Record 5 sales today and I'll restore it!"
            },
            requirement = "Record 5 sales today"
        )
    }

    /**
     * Attempt streak recovery.
     *
     * @param todaySales Number of sales recorded today
     * @return true if recovery succeeded
     */
    suspend fun attemptRecovery(todaySales: Int): Boolean {
        val recovery = streakProvider.getRecoveryOffer() ?: return false
        if (todaySales < 5) return false

        // Restore streak
        streakProvider.updateStreak(
            streak = recovery.lostStreak,
            lastActiveDay = LocalDate.now().toEpochDay(),
            protectionsUsed = 0,
            protectionWeek = getCurrentWeek()
        )
        streakProvider.clearRecoveryOffer()

        Timber.d(TAG, "Streak recovered: %d days", recovery.lostStreak)
        return true
    }

    /**
     * Get streak freeze status.
     */
    suspend fun getStreakFreezeStatus(): Triple<Boolean, Int, Int> {
        val info = getStreakInfo()
        val used = MAX_PROTECTIONS_PER_WEEK - info.protectionsAvailable
        return Triple(info.protectionsAvailable > 0, used, MAX_PROTECTIONS_PER_WEEK)
    }

    // ═══════════════ PRIVATE HELPERS ═══════════════

    private fun getCurrentWeek(): Int {
        return LocalDate.now().get(WeekFields.of(Locale.getDefault()).weekOfWeekBasedYear())
    }

    private fun checkMilestone(oldStreak: Int, newStreak: Int, language: String): String? {
        val milestone = STREAK_MILESTONES.firstOrNull { it in (oldStreak + 1)..newStreak }
            ?: return null

        return if (language == "sw") {
            when (milestone) {
                3 -> "🔥 Siku 3 mfululizo! Umefanya vizuri! Endelea!"
                7 -> "⚔️ Wiki moja imekamilika! Siku 7 mfululizo! Hongera!"
                14 -> "💪 Wiki mbili! Streak yako inakua!"
                21 -> "🌟 Siku 21! Tabia imara imeundwa!"
                30 -> "🥇 Mwezi mzima! Siku 30 mfululizo! Wewe ni mfanyabiashara bora!"
                60 -> "🔥🔥 Miezi miwili! Siku 60 mfululizo! Ajabu!"
                90 -> "👑 Robo mwaka! Siku 90 mfululizo! Umebisha!"
                180 -> "⭐ Nusu mwaka! Siku 180! Wewe ni Legend!"
                365 -> "🏆 MWAKA MZIMA! Siku 365 mfululizo! Wewe ni shujaa!"
                else -> "🎉 Streak mpya: siku $milestone mfululizo!"
            }
        } else {
            when (milestone) {
                3 -> "🔥 3 days in a row! Well done! Keep going!"
                7 -> "⚔️ One week complete! 7 days straight! Congratulations!"
                14 -> "💪 Two weeks! Your streak is growing!"
                21 -> "🌟 21 days! A strong habit has formed!"
                30 -> "🥇 Full month! 30 days straight! You're amazing!"
                60 -> "🔥🔥 Two months! 60 days straight! Incredible!"
                90 -> "👑 Quarter year! 90 days! You've made it!"
                180 -> "⭐ Half year! 180 days! You're a legend!"
                365 -> "🏆 FULL YEAR! 365 days straight! You're a hero!"
                else -> "🎉 New streak: $milestone days in a row!"
            }
        }
    }

    private fun getStreakIncrementMessage(streak: Int, language: String): String? {
        if (streak < 3) return null

        return if (language == "sw") {
            "🔥 Streak ya siku $streak! Endelea hivi!"
        } else {
            "🔥 $streak-day streak! Keep it up!"
        }
    }

    private fun getProtectionMessage(streak: Int, language: String): String {
        return if (language == "sw") {
            "🛡️ Streak yako imeokolewa! Siku $streak mfululizo! " +
            "Kinga yako ya bure wiki hii imetumika."
        } else {
            "🛡️ Streak saved! $streak days in a row! " +
            "Your free weekly protection was used."
        }
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/**
 * Result of a streak update.
 */
data class StreakUpdate(
    val previousStreak: Int,
    val newStreak: Int,
    val streakMaintained: Boolean,
    val protectionUsed: Boolean,
    val message: String?,
    val milestone: String?
)

/**
 * Streak information.
 */
data class StreakInfo(
    val currentStreak: Int,
    val longestStreak: Int,
    val protectionsAvailable: Int,
    val lastActiveDay: Long
)

/**
 * Streak recovery offer.
 */
data class StreakRecoveryOffer(
    val lostStreak: Int,
    val message: String,
    val requirement: String
)

/**
 * Internal streak state.
 */
data class StreakState(
    val currentStreak: Int,
    val longestStreak: Int,
    val lastActiveDay: Long,
    val protectionsUsed: Int,
    val protectionWeek: Int
)

/**
 * Internal recovery state.
 */
data class RecoveryState(
    val lostStreak: Int
)

/**
 * Interface for streak storage.
 */
interface StreakProvider {
    suspend fun getStreakState(): StreakState
    suspend fun updateStreak(streak: Int, lastActiveDay: Long, protectionsUsed: Int, protectionWeek: Int)
    suspend fun setRecoveryOffer(lostStreak: Int)
    suspend fun getRecoveryOffer(): RecoveryState?
    suspend fun clearRecoveryOffer()
}
