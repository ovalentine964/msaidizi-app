package com.msaidizi.app.loops

import com.msaidizi.app.core.database.GamificationDao
import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.GamificationEntity
import com.msaidizi.app.core.model.TransactionType
import com.msaidizi.app.gamification.GamificationEngine
import timber.log.Timber
import java.time.LocalDate
import kotlin.random.Random

/**
 * Variable Rewards Loop — drives engagement through unpredictable rewards.
 *
 * Loop flow:
 *   Worker records sale → GamificationEngine determines reward →
 *   Variable reward (random badge, surprise praise, insight, social proof) →
 *   Worker experiences surprise → Engagement increases → Records more
 *
 * Based on the Hook Model (Nir Eyal):
 *   Trigger → Action → Variable Reward → Investment
 *
 * The key insight: fixed rewards (10 pts per sale) become invisible
 * over time. Variable rewards (sometimes 10, sometimes 50, sometimes
 * a surprise insight) keep the brain engaged because it can't predict
 * what's coming.
 *
 * Variable reward types:
 * 1. **Random bonus points** — 10% chance of 2-5x bonus points
 * 2. **Surprise voice praise** — Unexpected Swahili encouragement
 * 3. **Hidden insight** — "Did you know? Tuesdays are your best day!"
 * 4. **Social proof** — "You're in the top 20% of record-keepers!"
 * 5. **Mystery badge** — Unpredictable badge unlocks
 * 6. **Streak surprise** — Random streak bonuses at non-milestone days
 *
 * Mathematical foundations:
 * - STA 443 (Probability): Variable rewards follow a known distribution
 *   P(bonus) = 0.10, E[bonus] = 30 pts
 * - PSY 301 (Behavioral): Variable ratio reinforcement schedules
 *   produce the highest response rates (Skinner, 1957)
 *
 * @param gamificationEngine Core gamification for point/badge operations
 * @param gamificationDao Direct access to gamification state
 * @param transactionDao For generating contextual insights
 * @param patternDao For learned patterns (peak hours, top items)
 */
class VariableRewardsLoop(
    private val gamificationEngine: GamificationEngine,
    private val gamificationDao: GamificationDao,
    private val transactionDao: TransactionDao,
    private val patternDao: PatternDao
) {
    companion object {
        private const val TAG = "VariableRewardsLoop"

        // Probability thresholds for each reward type (must sum ≤ 1.0)
        private const val PROB_BONUS_POINTS = 0.10      // 10% chance
        private const val PROB_SURPRISE_PRAISE = 0.08    // 8% chance
        private const val PROB_HIDDEN_INSIGHT = 0.07     // 7% chance
        private const val PROB_SOCIAL_PROOF = 0.05       // 5% chance
        private const val PROB_MYSTERY_BADGE = 0.03      // 3% chance
        private const val PROB_STREAK_SURPRISE = 0.05    // 5% chance

        // Bonus point ranges
        private const val BONUS_POINTS_MIN = 10
        private const val BONUS_POINTS_MAX = 50

        // Streak surprise multiplier
        private const val STREAK_SURPRISE_MULTIPLIER = 2

        /** Track which rewards have been seen recently to avoid repetition */
        private const val REWARD_COOLDOWN_HOURS = 2
    }

    /** Last reward timestamps to prevent spam */
    private val lastRewardTimestamps = mutableMapOf<RewardType, Long>()

    // ═══════════════════════════════════════════════════════════════
    // MAIN REWARD EVALUATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Evaluate and potentially award a variable reward.
     *
     * Called after any rewarding action (sale, balance check, goal completion).
     * Returns a VariableReward if the random check passes, null otherwise.
     *
     * The key: most of the time (60%+), no special reward fires.
     * This unpredictability is what makes variable rewards powerful.
     *
     * @param action The action that triggered this check
     * @param language Language preference
     * @return VariableReward if awarded, null if no special reward this time
     */
    suspend fun evaluateReward(action: RewardAction, language: String = "sw"): VariableReward? {
        Timber.tag(TAG).d("Evaluating variable reward for action: %s", action.name)

        val entity = gamificationDao.getGamification() ?: return null

        // Try each reward type in priority order
        val reward = tryMysteryBadge(entity, language)
            ?: tryBonusPoints(entity, language)
            ?: tryStreakSurprise(entity, language)
            ?: trySurprisePraise(action, language)
            ?: tryHiddenInsight(entity, language)
            ?: trySocialProof(entity, language)

        if (reward != null) {
            Timber.tag(TAG).d("Variable reward awarded: %s — %s", reward.type.name, reward.title)

            // Apply the reward (points, badges, etc.)
            applyReward(reward, entity)

            // Track cooldown
            lastRewardTimestamps[reward.type] = System.currentTimeMillis()
        }

        return reward
    }

    /**
     * Get a variable reward message for gamification events.
     * This is the lighter version that just returns a message string,
     * used by the Orchestrator to append to responses.
     *
     * @param pointsEarned Points just earned
     * @param language Language preference
     * @return Optional reward message to append
     */
    suspend fun getVariableRewardMessage(
        pointsEarned: Int,
        language: String = "sw"
    ): String? {
        val entity = gamificationDao.getGamification() ?: return null
        val reward = evaluateReward(RewardAction.SALE, language) ?: return null
        return reward.message
    }

    // ═══════════════════════════════════════════════════════════════
    // REWARD TYPE IMPLEMENTATIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Random bonus points — the classic variable ratio reward.
     *
     * 10% chance of 10-50 bonus points.
     * Expected value: 3 bonus points per action (0.10 × 30 avg).
     */
    private suspend fun tryBonusPoints(entity: GamificationEntity, language: String): VariableReward? {
        if (Random.nextDouble() >= PROB_BONUS_POINTS) return null
        if (isOnCooldown(RewardType.BONUS_POINTS)) return null

        val bonusPoints = Random.nextInt(BONUS_POINTS_MIN, BONUS_POINTS_MAX + 1)

        val message = if (language == "sw") {
            "🎁 Bonus! +$bonusPoints pointi za ziada!"
        } else {
            "🎁 Bonus! +$bonusPoints extra points!"
        }

        return VariableReward(
            type = RewardType.BONUS_POINTS,
            title = if (language == "sw") "Bonus!" else "Bonus!",
            message = message,
            points = bonusPoints,
            emoji = "🎁"
        )
    }

    /**
     * Surprise voice praise — unexpected encouragement.
     *
     * Uses variable Swahili phrases to keep it fresh.
     * 8% chance after any action.
     */
    private fun trySurprisePraise(action: RewardAction, language: String): VariableReward? {
        if (Random.nextDouble() >= PROB_SURPRISE_PRAISE) return null
        if (isOnCooldown(RewardType.SURPRISE_PRAISE)) return null

        val praises = if (language == "sw") {
            listOf(
                "🌟 Wewe ni mfanyabiashara bora! Endelea hivi!",
                "💪 Umefanya vizuri sana leo! Msaidizi anaona bidii yako!",
                "⭐ Hongera! Biashara yako inakua kila siku!",
                "🎉 Umejitahidi! Faida yako inaongezeka!",
                "👏 Nzuri sana! Wewe ni mfano wa wafanyabiashara wengine!",
                "🔥 Moto! Biashara yako inaendelea vizuri!",
                "✨ Msaidizi anafuraha kuona maendeleo yako!",
                "🏆 Umeonyesha uongozi mzuri wa biashara leo!"
            )
        } else {
            listOf(
                "🌟 You're an excellent business person! Keep it up!",
                "💪 Great job today! Msaidizi sees your effort!",
                "⭐ Congratulations! Your business grows every day!",
                "🎉 Well done! Your profit is increasing!",
                "👏 Very good! You're an example to other business people!",
                "🔥 Fire! Your business is doing well!",
                "✨ Msaidizi is happy to see your progress!",
                "🏆 You showed great business leadership today!"
            )
        }

        return VariableReward(
            type = RewardType.SURPRISE_PRAISE,
            title = if (language == "sw") "Sifa!" else "Praise!",
            message = praises.random(),
            points = 0,
            emoji = "🌟"
        )
    }

    /**
     * Hidden insight — surprise business intelligence.
     *
     * Reveals a pattern the worker didn't know about.
     * 7% chance, but only when we have enough data.
     */
    private suspend fun tryHiddenInsight(entity: GamificationEntity, language: String): VariableReward? {
        if (Random.nextDouble() >= PROB_HIDDEN_INSIGHT) return null
        if (isOnCooldown(RewardType.HIDDEN_INSIGHT)) return null
        if (entity.totalSalesRecorded < 10) return null // Need data for insights

        val insight = generateInsight(language) ?: return null

        return VariableReward(
            type = RewardType.HIDDEN_INSIGHT,
            title = if (language == "sw") "Ufahamu!" else "Insight!",
            message = insight,
            points = 5, // Small points for the "aha" moment
            emoji = "💡"
        )
    }

    /**
     * Social proof — anonymous peer comparison.
     *
     * Shows the worker how they compare to others.
     * 5% chance.
     */
    private suspend fun trySocialProof(entity: GamificationEntity, language: String): VariableReward? {
        if (Random.nextDouble() >= PROB_SOCIAL_PROOF) return null
        if (isOnCooldown(RewardType.SOCIAL_PROOF)) return null
        if (entity.totalSalesRecorded < 5) return null

        val percentile = estimatePercentile(entity)
        val message = formatSocialProof(percentile, language)

        return VariableReward(
            type = RewardType.SOCIAL_PROOF,
            title = if (language == "sw") "Ulinganisho" else "Comparison",
            message = message,
            points = 0,
            emoji = "📊"
        )
    }

    /**
     * Mystery badge — unpredictable badge unlock.
     *
     * Unlocks a badge that isn't in the normal progression.
     * 3% chance, creates "what was that?" curiosity.
     */
    private suspend fun tryMysteryBadge(entity: GamificationEntity, language: String): VariableReward? {
        if (Random.nextDouble() >= PROB_MYSTERY_BADGE) return null
        if (isOnCooldown(RewardType.MYSTERY_BADGE)) return null
        if (entity.totalSalesRecorded < 5) return null

        val earnedIds = entity.earnedBadges.split(",").filter { it.isNotBlank() }.toSet()

        // Mystery badges are special variants
        val mysteryBadges = listOf(
            MysteryBadge("mystery_lucky", "🍀", "Bahati", "Lucky", "Umepata bahati ya siku!"),
            MysteryBadge("mystery_speed", "⚡", "Kasi", "Speed", "Uerekodi kwa kasi!"),
            MysteryBadge("mystery_consistent", "🎯", "Makini", "Consistent", "Uko makini na biashara!"),
            MysteryBadge("mystery_early", "🌅", "Mapema", "Early Bird", "Umeamka mapema kwa biashara!"),
            MysteryBadge("mystery_smart", "🧠", "Busara", "Smart", "Umetumia busara ya biashara!")
        )

        // Pick one the worker hasn't earned yet
        val available = mysteryBadges.filter { it.id !in earnedIds }
        if (available.isEmpty()) return null

        val chosen = available.random()
        val name = if (language == "sw") chosen.nameSw else chosen.nameEn
        val desc = if (language == "sw") chosen.descSw else chosen.descEn

        return VariableReward(
            type = RewardType.MYSTERY_BADGE,
            title = if (language == "sw") "Badge ya Siri!" else "Mystery Badge!",
            message = "${chosen.emoji} $name — $desc",
            points = 15,
            emoji = chosen.emoji,
            badgeId = chosen.id
        )
    }

    /**
     * Streak surprise — random bonus on non-milestone streak days.
     *
     * Makes every streak day feel potentially special, not just
     * the milestone days (3, 7, 30, etc.).
     * 5% chance, but only on non-milestone days.
     */
    private suspend fun tryStreakSurprise(entity: GamificationEntity, language: String): VariableReward? {
        if (Random.nextDouble() >= PROB_STREAK_SURPRISE) return null
        if (isOnCooldown(RewardType.STREAK_SURPRISE)) return null

        val streak = entity.currentStreak
        if (streak < 2) return null // Need at least 2 days
        // Skip milestone days (they have their own celebrations)
        if (streak in setOf(3, 7, 14, 21, 30, 45, 60, 90, 120, 180, 365)) return null

        val bonusPoints = streak * STREAK_SURPRISE_MULTIPLIER

        val message = if (language == "sw") {
            "🔥 Streak Surprise! Siku $streak inaleta pointi $bonusPoints za ziada!"
        } else {
            "🔥 Streak Surprise! Day $streak brings $bonusPoints bonus points!"
        }

        return VariableReward(
            type = RewardType.STREAK_SURPRISE,
            title = if (language == "sw") "Streak Surprise!" else "Streak Surprise!",
            message = message,
            points = bonusPoints,
            emoji = "🔥"
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPER: APPLY REWARD
    // ═══════════════════════════════════════════════════════════════

    /**
     * Apply the reward — add points, badges, etc.
     */
    private suspend fun applyReward(reward: VariableReward, entity: GamificationEntity) {
        // Add bonus points
        if (reward.points > 0) {
            gamificationDao.addPoints(reward.points)
            Timber.tag(TAG).d("Added %d bonus points", reward.points)
        }

        // Add mystery badge
        if (reward.badgeId != null) {
            val earnedIds = entity.earnedBadges.split(",").filter { it.isNotBlank() }.toMutableSet()
            if (reward.badgeId !in earnedIds) {
                earnedIds.add(reward.badgeId)
                gamificationDao.updateLevelAndBadges(
                    level = entity.level,
                    badges = earnedIds.joinToString(",")
                )
                Timber.tag(TAG).d("Mystery badge earned: %s", reward.badgeId)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPER: INSIGHT GENERATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate a contextual insight from transaction data.
     */
    private suspend fun generateInsight(language: String): String? {
        val now = System.currentTimeMillis() / 1000
        val weekAgo = now - 7 * 86400

        // Get daily sales totals for the past week
        val dailyTotals = transactionDao.getDailySalesTotals(weekAgo)
        if (dailyTotals.size < 3) return null

        // Find best day
        val bestDay = dailyTotals.maxByOrNull { it.total }
        val worstDay = dailyTotals.minByOrNull { it.total }

        if (bestDay == null || worstDay == null || bestDay == worstDay) return null

        val bestDayName = getDayName(bestDay.day)
        val worstDayName = getDayName(worstDay.day)

        val insights = if (language == "sw") {
            listOf(
                "💡 Ujumbe: Siku yako bora zaidi ni $bestDayName! Panga bidii zaidi siku hiyo.",
                "💡 Umefanya mauzo mazuri zaidi $bestDayName kuliko $worstDayName. Fikiria kufungua duka mapema $bestDayName!",
                "💡 Data yako inaonyesha $bestDayName ni siku yako ya faida zaidi. Jipange!"
            )
        } else {
            listOf(
                "💡 Insight: Your best day is $bestDayName! Plan more effort that day.",
                "💡 You sold more on $bestDayName than $worstDayName. Consider opening early on $bestDayName!",
                "💡 Your data shows $bestDayName is your most profitable day. Prepare!"
            )
        }

        return insights.random()
    }

    /**
     * Get day name from epoch timestamp.
     */
    private fun getDayName(epochDay: Long): String {
        val date = LocalDate.ofEpochDay(epochDay / 86400)
        return when (date.dayOfWeek.value) {
            1 -> "Jumatatu" // Monday
            2 -> "Jumanne"
            3 -> "Jumatano"
            4 -> "Alhamisi"
            6 -> "Jumamosi"
            7 -> "Jumapili"
            else -> "Ijumaa" // Friday
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPER: SOCIAL PROOF ESTIMATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Estimate the worker's percentile among peers.
     * Uses local heuristics (no server data needed).
     */
    private fun estimatePercentile(entity: GamificationEntity): Int {
        // Simple heuristic based on sales count and streak
        val salesScore = when {
            entity.totalSalesRecorded >= 100 -> 40
            entity.totalSalesRecorded >= 50 -> 30
            entity.totalSalesRecorded >= 20 -> 20
            entity.totalSalesRecorded >= 10 -> 15
            else -> 5
        }

        val streakScore = when {
            entity.currentStreak >= 30 -> 40
            entity.currentStreak >= 14 -> 30
            entity.currentStreak >= 7 -> 20
            entity.currentStreak >= 3 -> 10
            else -> 0
        }

        val levelScore = entity.level * 5

        return (salesScore + streakScore + levelScore).coerceIn(5, 95)
    }

    /**
     * Format social proof message.
     */
    private fun formatSocialProof(percentile: Int, language: String): String {
        return if (language == "sw") {
            when {
                percentile >= 90 -> "⭐ Wewe ni bora sana! Uko top 10% ya wafanyabiashara!"
                percentile >= 70 -> "👍 Unaendelea vizuri! Uko juu ya wastani wa wafanyabiashara."
                percentile >= 50 -> "➡️ Uko katikati. Ongeza bidii kidogo!"
                else -> "💪 Una uwezo wa kufanya vizuri zaidi! Jaribu kuongeza mauzo."
            }
        } else {
            when {
                percentile >= 90 -> "⭐ You're amazing! You're in the top 10% of business people!"
                percentile >= 70 -> "👍 You're doing well! Above the average business person."
                percentile >= 50 -> "➡️ You're in the middle. Push a little harder!"
                else -> "💪 You can do better! Try increasing your sales."
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPER: COOLDOWN CHECK
    // ═══════════════════════════════════════════════════════════════

    /**
     * Check if a reward type is on cooldown to prevent spam.
     */
    private fun isOnCooldown(type: RewardType): Boolean {
        val lastTime = lastRewardTimestamps[type] ?: return false
        val hoursSince = (System.currentTimeMillis() - lastTime) / (1000 * 60 * 60)
        return hoursSince < REWARD_COOLDOWN_HOURS
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

/**
 * A variable reward awarded to the worker.
 */
data class VariableReward(
    val type: RewardType,
    val title: String,
    val message: String,
    val points: Int = 0,
    val emoji: String,
    val badgeId: String? = null
)

/**
 * Types of variable rewards.
 */
enum class RewardType {
    BONUS_POINTS,
    SURPRISE_PRAISE,
    HIDDEN_INSIGHT,
    SOCIAL_PROOF,
    MYSTERY_BADGE,
    STREAK_SURPRISE
}

/**
 * Actions that can trigger variable rewards.
 */
enum class RewardAction {
    SALE,
    BALANCE_CHECK,
    GOAL_COMPLETED,
    LESSON_COMPLETED,
    GIVING_RECORDED,
    STREAK_MILESTONE
}

/**
 * Mystery badge definition.
 */
private data class MysteryBadge(
    val id: String,
    val emoji: String,
    val nameSw: String,
    val nameEn: String,
    val descSw: String,
    val descEn: String = descSw
)
