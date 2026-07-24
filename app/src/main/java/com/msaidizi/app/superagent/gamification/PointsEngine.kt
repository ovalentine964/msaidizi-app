package com.msaidizi.app.superagent.gamification

import timber.log.Timber

/**
 * Points Engine — manages point accumulation and variable rewards.
 *
 * ## Points System
 * - Record sale = 10 pts
 * - Check balance = 5 pts
 * - Daily streak = 20 pts (per day maintained)
 * - Complete daily habits = 15 pts
 * - Listen to mindset lesson = 8 pts
 * - Give tithe/charity = 12 pts
 *
 * ## Variable Rewards (Hook Model)
 * Points are multiplied based on streak length:
 * - Streak 0-6 days: ×1
 * - Streak 7-13 days: ×2
 * - Streak 14-29 days: ×3
 * - Streak 30+ days: ×5
 *
 * Random bonus chances:
 * - 2% chance: JACKPOT (5× bonus)
 * - 4% chance: Big bonus (3× bonus)
 * - 9% chance: Small bonus (1× bonus)
 * - 85% chance: No bonus
 *
 * This unpredictability keeps workers engaged (slot machine effect).
 *
 * @param pointsProvider Provides access to points storage
 */
class PointsEngine(
    private val pointsProvider: PointsProvider
) {
    companion object {
        private const val TAG = "PointsEngine"

        // Base points per action
        const val POINTS_SALE = 10
        const val POINTS_BALANCE_CHECK = 5
        const val POINTS_DAILY_STREAK = 20
        const val POINTS_HABITS_BONUS = 15
        const val POINTS_MINDSET_LESSON = 8
        const val POINTS_GIVING = 12
        const val POINTS_STOCK_CHECK = 3
        const val POINTS_GOAL_SET = 10
    }

    /**
     * Award points for an action with variable reward calculation.
     *
     * @param action The action that earned points
     * @param currentStreak The worker's current streak (for multiplier)
     * @param language "sw" or "en"
     * @return PointsResult with total points and any bonus messages
     */
    suspend fun awardPoints(
        action: String,
        currentStreak: Int,
        language: String = "sw"
    ): PointsResult {
        val basePoints = getBasePoints(action)
        val multiplier = getStreakMultiplier(currentStreak)
        val bonus = calculateBonus(basePoints, language)

        val totalPoints = (basePoints * multiplier) + bonus.bonusPoints

        pointsProvider.addPoints(totalPoints)

        Timber.d(TAG, "Points awarded: %s → %d (base=%d × %d + bonus=%d)",
            action, totalPoints, basePoints, multiplier, bonus.bonusPoints)

        return PointsResult(
            basePoints = basePoints,
            multiplier = multiplier,
            bonusPoints = bonus.bonusPoints,
            totalPoints = totalPoints,
            bonusMessage = bonus.message,
            streakMessage = getStreakMultiplierMessage(multiplier, language)
        )
    }

    /**
     * Get current total points.
     */
    suspend fun getTotalPoints(): Int {
        return pointsProvider.getTotalPoints()
    }

    /**
     * Get points earned today.
     */
    suspend fun getTodayPoints(): Int {
        return pointsProvider.getTodayPoints()
    }

    /**
     * Get points history for the last N days.
     */
    suspend fun getPointsHistory(days: Int): List<DailyPoints> {
        return pointsProvider.getPointsHistory(days)
    }

    // ═══════════════ PRIVATE HELPERS ═══════════════

    private fun getBasePoints(action: String): Int {
        return when (action) {
            "sale" -> POINTS_SALE
            "balance_check" -> POINTS_BALANCE_CHECK
            "daily_streak" -> POINTS_DAILY_STREAK
            "habits_bonus" -> POINTS_HABITS_BONUS
            "mindset_lesson" -> POINTS_MINDSET_LESSON
            "giving" -> POINTS_GIVING
            "stock_check" -> POINTS_STOCK_CHECK
            "goal_set" -> POINTS_GOAL_SET
            else -> 0
        }
    }

    private fun getStreakMultiplier(streak: Int): Int {
        return when {
            streak >= 30 -> 5
            streak >= 14 -> 3
            streak >= 7 -> 2
            else -> 1
        }
    }

    private fun getStreakMultiplierMessage(multiplier: Int, language: String): String? {
        if (multiplier <= 1) return null

        return if (language == "sw") {
            when (multiplier) {
                2 -> "🔥 Streak ×2! Pointi zimeongezeka maradufu!"
                3 -> "🔥 Streak ×3! Pointi tatu za ziada!"
                5 -> "🔥🔥 Streak ×5! Pointi tano za ziada! Wewe ni kiongozi!"
                else -> "🔥 Streak ×$multiplier!"
            }
        } else {
            when (multiplier) {
                2 -> "🔥 Streak ×2! Points doubled!"
                3 -> "🔥 Streak ×3! Triple points!"
                5 -> "🔥🔥 Streak ×5! Quintuple points! You're a leader!"
                else -> "🔥 Streak ×$multiplier!"
            }
        }
    }

    private fun calculateBonus(basePoints: Int, language: String): BonusResult {
        val random = Math.random()

        return when {
            random < 0.02 -> {
                val bonus = basePoints * 5
                BonusResult(bonus, if (language == "sw") "🎰 JACKPOT! +$bonus pointi!" else "🎰 JACKPOT! +$bonus points!")
            }
            random < 0.06 -> {
                val bonus = basePoints * 3
                BonusResult(bonus, if (language == "sw") "🎁 Bonus kubwa! +$bonus pointi!" else "🎁 Big bonus! +$bonus points!")
            }
            random < 0.15 -> {
                val bonus = basePoints
                BonusResult(bonus, if (language == "sw") "✨ Bonus! +$bonus pointi!" else "✨ Bonus! +$bonus points!")
            }
            else -> BonusResult(0, null)
        }
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/**
 * Result of a points award.
 */
data class PointsResult(
    val basePoints: Int,
    val multiplier: Int,
    val bonusPoints: Int,
    val totalPoints: Int,
    val bonusMessage: String?,
    val streakMessage: String?
)

/**
 * Internal bonus calculation result.
 */
internal data class BonusResult(
    val bonusPoints: Int,
    val message: String?
)

/**
 * Points earned on a specific day.
 */
data class DailyPoints(
    val date: String,
    val points: Int
)

/**
 * Interface for points storage.
 */
interface PointsProvider {
    suspend fun addPoints(points: Int)
    suspend fun getTotalPoints(): Int
    suspend fun getTodayPoints(): Int
    suspend fun getPointsHistory(days: Int): List<DailyPoints>
}
