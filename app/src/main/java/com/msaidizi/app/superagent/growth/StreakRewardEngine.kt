package com.msaidizi.app.superagent.growth

import timber.log.Timber

/**
 * Streak Reward Engine — reward consistent usage.
 *
 * Workers earn rewards for using Msaidizi daily.
 * Streaks encourage habit formation and retention.
 */
class StreakRewardEngine {
    companion object {
        private const val TAG = "StreakRewardEngine"

        // Streak milestones and rewards
        private val STREAK_REWARDS = mapOf(
            3 to StreakReward(3, 10, "3-day streak! Earned KES 10 airtime", "Streak ya siku 3! Umepata KES 10 ya hewa"),
            7 to StreakReward(7, 50, "7-day streak! Earned KES 50 airtime", "Streak ya siku 7! Umepata KES 50 ya hewa"),
            14 to StreakReward(14, 100, "14-day streak! Earned KES 100 airtime", "Streak ya siku 14! Umepata KES 100 ya hewa"),
            30 to StreakReward(30, 300, "30-day streak! Earned KES 300 airtime", "Streak ya siku 30! Umepata KES 300 ya hewa"),
            60 to StreakReward(60, 500, "60-day streak! Earned KES 500 airtime", "Streak ya siku 60! Umepata KES 500 ya hewa"),
            90 to StreakReward(90, 1000, "90-day streak! Earned KES 1,000 airtime", "Streak ya siku 90! Umepata KES 1,000 ya hewa")
        )
    }

    /**
     * Check if a streak milestone was reached and return reward.
     */
    fun checkStreakReward(currentStreak: Int, language: String = "sw"): StreakReward? {
        val reward = STREAK_REWARDS[currentStreak]
        if (reward != null) {
            Timber.d("$TAG: Streak milestone reached: $currentStreak days")
        }
        return reward
    }

    /**
     * Get streak info message.
     */
    fun getStreakInfo(currentStreak: Int, language: String = "sw"): String {
        val nextMilestone = STREAK_REWARDS.keys.filter { it > currentStreak }.minOrNull()
        return if (language == "sw") {
            if (nextMilestone != null) {
                "Streak yako ni siku $currentStreak. Siku ${nextMilestone - currentStreak} zaidi hadi tuzo ijayo!"
            } else {
                "Streak yako ni siku $currentStreak! Umeongea sana!"
            }
        } else {
            if (nextMilestone != null) {
                "Your streak is $currentStreak days. ${nextMilestone - currentStreak} more days to next reward!"
            } else {
                "Your streak is $currentStreak days! Amazing!"
            }
        }
    }
}

data class StreakReward(
    val days: Int,
    val rewardKES: Int,
    val message: String,
    val messageSw: String
)
