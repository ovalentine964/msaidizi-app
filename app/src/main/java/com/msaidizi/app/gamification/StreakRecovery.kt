package com.msaidizi.app.gamification

import com.msaidizi.app.core.database.GamificationDao
import com.msaidizi.app.core.model.GamificationEntity
import timber.log.Timber
import java.time.LocalDate
import java.time.YearMonth

/**
 * Streak Recovery — "Fresh Start" mechanic for broken streaks.
 *
 * When a worker loses their streak, instead of pure punishment,
 * we offer a recovery path that preserves dignity and motivation.
 *
 * Behavioral design:
 * - Loss aversion: Show exactly what they lost ("Ulipoteza siku 15!")
 * - Fresh Start effect: Research shows people are more motivated
 *   to start fresh at temporal landmarks (new week, new month)
 * - Preserve progress: Points and levels NEVER reset — only streak
 * - 1 free recovery per month: Scarcity makes it valuable
 * - "Fresh Start" framing: Not "you failed" but "new beginning"
 *
 * Flow:
 *   Streak breaks → Loss message shown → Fresh Start offered →
 *   Worker accepts → Streak restored to previous value →
 *   Monthly recovery used → Encouragement message
 *
 * @param gamificationDao Room DAO for persistence
 */
class StreakRecovery(
    private val gamificationDao: GamificationDao
) {
    companion object {
        private const val TAG = "StreakRecovery"

        /** Free recoveries allowed per calendar month */
        const val MAX_RECOVERIES_PER_MONTH = 1

        /** Minimum streak worth recovering (avoid recovering 1-2 day streaks) */
        const val MIN_STREAK_FOR_RECOVERY = 3

        /** Bonus points awarded for using recovery (consolation) */
        const val RECOVERY_BONUS_POINTS = 10
    }

    // ═══════════════════════════════════════════════════════════════
    // LOSS DETECTION & RECOVERY OFFER
    // ═══════════════════════════════════════════════════════════════

    /**
     * Check if the worker just lost their streak and generate a
     * recovery offer with loss aversion messaging.
     *
     * Called when the streak detection in GamificationEngine
     * determines the streak has broken (missed 2+ days with no
     * protection available).
     *
     * @param lostStreak The streak value that was just lost
     * @param language Language preference
     * @return StreakRecoveryOffer with loss message and recovery option
     */
    suspend fun checkAndOfferRecovery(
        lostStreak: Int,
        language: String = "sw"
    ): StreakRecoveryOffer {
        val entity = gamificationDao.getGamification() ?: return StreakRecoveryOffer.unavailable()

        // Don't offer recovery for tiny streaks
        if (lostStreak < MIN_STREAK_FOR_RECOVERY) {
            return StreakRecoveryOffer.unavailable()
        }

        val recoveryAvailable = isRecoveryAvailable(entity)
        val lossMessage = generateLossMessage(lostStreak, language)

        return if (recoveryAvailable) {
            StreakRecoveryOffer(
                streakLost = lostStreak,
                recoveryAvailable = true,
                lossMessage = lossMessage,
                recoveryOfferMessage = generateRecoveryOfferMessage(lostStreak, language),
                freshStartMessage = null,
                bonusPoints = RECOVERY_BONUS_POINTS
            )
        } else {
            StreakRecoveryOffer(
                streakLost = lostStreak,
                recoveryAvailable = false,
                lossMessage = lossMessage,
                recoveryOfferMessage = null,
                freshStartMessage = generateFreshStartMessage(lostStreak, language),
                bonusPoints = 0
            )
        }
    }

    /**
     * Execute the streak recovery — restore the worker's streak.
     *
     * @param lostStreak The streak value to restore
     * @param language Language preference
     * @return StreakRecoveryResult with confirmation and celebration
     */
    suspend fun executeRecovery(
        lostStreak: Int,
        language: String = "sw"
    ): StreakRecoveryResult {
        val entity = gamificationDao.getGamification() ?: return StreakRecoveryResult.error()

        if (!isRecoveryAvailable(entity)) {
            return StreakRecoveryResult(
                success = false,
                message = if (language == "sw") {
                    "⚠️ Umeshatumia recovery ya mwezi huu. Jaribu mwezi ujao!"
                } else {
                    "⚠️ You've used this month's recovery. Try next month!"
                },
                restoredStreak = 0,
                bonusPoints = 0
            )
        }

        // Restore the streak
        val today = java.time.LocalDate.now().toEpochDay()
        gamificationDao.restoreStreak(
            streak = lostStreak,
            day = today
        )

        // Track recovery usage
        val currentMonth = YearMonth.now()
        gamificationDao.updateRecoveryUsage(
            recoveriesUsed = 1,
            month = currentMonth.year * 100 + currentMonth.monthValue
        )

        // Award bonus points for the recovery action
        gamificationDao.addPoints(RECOVERY_BONUS_POINTS)

        Timber.tag(TAG).d("Streak recovered: %d days restored, %d bonus points", lostStreak, RECOVERY_BONUS_POINTS)

        return StreakRecoveryResult(
            success = true,
            message = generateRecoverySuccessMessage(lostStreak, language),
            restoredStreak = lostStreak,
            bonusPoints = RECOVERY_BONUS_POINTS
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // LOSS AVERSION MESSAGES
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate the loss aversion message — make the worker FEEL the loss.
     * This isn't cruelty; it's motivation. Showing what was lost
     * makes the recovery offer more compelling.
     */
    private fun generateLossMessage(lostStreak: Int, language: String): String {
        return if (language == "sw") {
            when {
                lostStreak >= 60 -> "😢 Ulipoteza siku $lostStreak mfululizo! " +
                    "Miezi 2 ya bidii — imepotea. Lakini usikate tamaa..."
                lostStreak >= 30 -> "😔 Ulipoteza siku $lostStreak! " +
                    "Mwezi mzima wa mfululizo — umepotea. " +
                    "Lakini wewe ni mfanyabiashara, na mfanyabiashara haachi..."
                lostStreak >= 14 -> "😰 Ulipoteza siku $lostStreak mfululizo! " +
                    "Wiki 2 za bidii zimepotea. " +
                    "Unajua jinsi gani ilikuwa ngumu kufika huko..."
                lostStreak >= 7 -> "😟 Ulipoteza siku $lostStreak! " +
                    "Wiki nzima ya mfululizo — imepotea. " +
                    "Lakini una nafasi ya kurejea..."
                else -> "😔 Ulipoteza mfululizo wako wa siku $lostStreak. " +
                    "Sio nzuri, lakini una nafasi mpya."
            }
        } else {
            when {
                lostStreak >= 60 -> "😢 You lost a $lostStreak-day streak! " +
                    "2 months of effort — gone. But don't lose hope..."
                lostStreak >= 30 -> "😔 You lost $lostStreak days! " +
                    "A full month of consistency — gone. " +
                    "But you're a business person, and business people don't quit..."
                lostStreak >= 14 -> "😰 You lost a $lostStreak-day streak! " +
                    "2 weeks of effort — gone. " +
                    "You know how hard it was to get there..."
                lostStreak >= 7 -> "😟 You lost $lostStreak days! " +
                    "A whole week of consistency — gone. " +
                    "But you have a chance to come back..."
                else -> "😔 You lost your $lostStreak-day streak. " +
                    "Not great, but you have a fresh chance."
            }
        }
    }

    /**
     * Generate the recovery offer message — the lifeline.
     */
    private fun generateRecoveryOfferMessage(lostStreak: Int, language: String): String {
        return if (language == "sw") {
            "🛡️ Lakini! Una recovery 1 ya bure mwezi huu. " +
                "Unaweza kurejesha streak yako ya siku $lostStreak sasa hivi! " +
                "Sema 'Anza upya' au 'Fresh Start' kurejesha.\n\n" +
                "📊 Pointi na level yako hazitabadilika — " +
                "unachopoteza ni streak tu, na tunaweza kuirejesha!"
        } else {
            "🛡️ But! You have 1 free recovery this month. " +
                "You can restore your $lostStreak-day streak right now! " +
                "Say 'Fresh Start' or 'Anza upya' to restore.\n\n" +
                "📊 Your points and level won't change — " +
                "only the streak is affected, and we can restore it!"
        }
    }

    /**
     * Generate Fresh Start message when no recovery is available.
     * Still encouraging, not punishing.
     */
    private fun generateFreshStartMessage(lostStreak: Int, language: String): String {
        return if (language == "sw") {
            "🌱 Fresh Start! Umeshatumia recovery yako ya mwezi huu, " +
                "lakini sio mwisho. Anza mfululizo mpya leo!\n\n" +
                "💪 Kumbuka: Pointi zako ($lostStreak siku za bidii) " +
                "bado ziko salama. Level yako bado iko. " +
                "Wewe ni mfanyabiashara — simama tena!"
        } else {
            "🌱 Fresh Start! You've used this month's recovery, " +
                "but it's not the end. Start a new streak today!\n\n" +
                "💪 Remember: Your points ($lostStreak days of effort) " +
                "are still safe. Your level is intact. " +
                "You're a business person — get back up!"
        }
    }

    /**
     * Generate success message after recovery is executed.
     */
    private fun generateRecoverySuccessMessage(restoredStreak: Int, language: String): String {
        return if (language == "sw") {
            when {
                restoredStreak >= 30 -> "🎉 STREAK YAKO IMEREJEAA! Siku $restoredStreak mfululizo! " +
                    "Wewe ni kiongozi — haachi kamwe! +${RECOVERY_BONUS_POINTS} pointi za bonus! 💪"
                restoredStreak >= 7 -> "✅ Streak yako ya siku $restoredStreak imerejeshwa! " +
                    "Umerudi kwenye mchezo! +${RECOVERY_BONUS_POINTS} pointi za bonus! 🔥"
                else -> "✅ Streak yako imerejeshwa! Siku $restoredStreak mfululizo! " +
                    "Endelea hivi! +${RECOVERY_BONUS_POINTS} pointi za bonus!"
            }
        } else {
            when {
                restoredStreak >= 30 -> "🎉 YOUR STREAK IS BACK! $restoredStreak days in a row! " +
                    "You're a leader — you never give up! +${RECOVERY_BONUS_POINTS} bonus points! 💪"
                restoredStreak >= 7 -> "✅ Your $restoredStreak-day streak is restored! " +
                    "You're back in the game! +${RECOVERY_BONUS_POINTS} bonus points! 🔥"
                else -> "✅ Streak restored! $restoredStreak days in a row! " +
                    "Keep going! +${RECOVERY_BONUS_POINTS} bonus points!"
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Check if the worker has a recovery available this month.
     */
    private fun isRecoveryAvailable(entity: GamificationEntity): Boolean {
        val currentMonth = YearMonth.now()
        val currentMonthKey = currentMonth.year * 100 + currentMonth.monthValue

        // If recovery tracking is from a different month, reset
        return if (entity.streakRecoveryMonth != currentMonthKey) {
            true // New month, recovery available
        } else {
            entity.streakRecoveriesUsedThisMonth < MAX_RECOVERIES_PER_MONTH
        }
    }

    /**
     * Get recovery status for UI display.
     */
    suspend fun getRecoveryStatus(language: String = "sw"): RecoveryStatus {
        val entity = gamificationDao.getGamification() ?: return RecoveryStatus.empty()
        val currentMonth = YearMonth.now()
        val currentMonthKey = currentMonth.year * 100 + currentMonth.monthValue

        val recoveriesUsed = if (entity.streakRecoveryMonth != currentMonthKey) {
            0
        } else {
            entity.streakRecoveriesUsedThisMonth
        }

        val available = recoveriesUsed < MAX_RECOVERIES_PER_MONTH

        return RecoveryStatus(
            available = available,
            usedThisMonth = recoveriesUsed,
            maxPerMonth = MAX_RECOVERIES_PER_MONTH,
            monthLabel = currentMonth.month.getDisplayName(
                java.time.format.TextStyle.SHORT,
                java.util.Locale.getDefault()
            ),
            statusMessage = if (available) {
                if (language == "sw") "🛡️ Recovery 1 ya bure inapatikana mwezi huu"
                else "🛡️ 1 free recovery available this month"
            } else {
                if (language == "sw") "🔄 Recovery ya mwezi huu imeshatumika"
                else "🔄 This month's recovery already used"
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

/**
 * Recovery offer presented to the worker after streak breaks.
 */
data class StreakRecoveryOffer(
    val streakLost: Int,
    val recoveryAvailable: Boolean,
    val lossMessage: String,
    val recoveryOfferMessage: String?,
    val freshStartMessage: String?,
    val bonusPoints: Int
) {
    companion object {
        fun unavailable() = StreakRecoveryOffer(
            streakLost = 0,
            recoveryAvailable = false,
            lossMessage = "",
            recoveryOfferMessage = null,
            freshStartMessage = null,
            bonusPoints = 0
        )
    }
}

/**
 * Result of executing a streak recovery.
 */
data class StreakRecoveryResult(
    val success: Boolean,
    val message: String,
    val restoredStreak: Int,
    val bonusPoints: Int
) {
    companion object {
        fun error() = StreakRecoveryResult(false, "⚠️ Kuna tatizo.", 0, 0)
    }
}

/**
 * Recovery status for UI display.
 */
data class RecoveryStatus(
    val available: Boolean,
    val usedThisMonth: Int,
    val maxPerMonth: Int,
    val monthLabel: String,
    val statusMessage: String
) {
    companion object {
        fun empty() = RecoveryStatus(false, 0, 1, "", "")
    }
}
