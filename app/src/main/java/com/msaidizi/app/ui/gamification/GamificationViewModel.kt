package com.msaidizi.app.ui.gamification

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.gamification.BadgeStatus
import com.msaidizi.app.gamification.GamificationEngine
import com.msaidizi.app.gamification.InsightReward
import com.msaidizi.app.gamification.InsightTier
import com.msaidizi.app.gamification.LevelInfo
import com.msaidizi.app.gamification.LevelUnlockCelebration
import com.msaidizi.app.gamification.MicroReward
import com.msaidizi.app.gamification.MicroRewardStatus
import com.msaidizi.app.gamification.RecoveryStatus
import com.msaidizi.app.gamification.StreakInfo
import com.msaidizi.app.gamification.UnlockSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for the Gamification screen.
 *
 * Loads badge gallery, level progress, streak status, variable rewards,
 * and social proof data from [GamificationEngine].
 *
 * Anti-shame design: no public leaderboards, badges can never be lost.
 */
@HiltViewModel
class GamificationViewModel @Inject constructor(
    private val gamificationEngine: GamificationEngine
) : ViewModel() {

    companion object {
        private const val TAG = "GamificationVM"
    }

    // ── Level & XP ────────────────────────────────────────────────

    private val _levelInfo = MutableLiveData<LevelInfo>()
    val levelInfo: LiveData<LevelInfo> = _levelInfo

    private val _totalPoints = MutableLiveData<Int>()
    val totalPoints: LiveData<Int> = _totalPoints

    // ── Badges ────────────────────────────────────────────────────

    private val _badgeGroups = MutableLiveData<List<BadgeCategoryGroup>>()
    val badgeGroups: LiveData<List<BadgeCategoryGroup>> = _badgeGroups

    private val _earnedCount = MutableLiveData(0)
    val earnedCount: LiveData<Int> = _earnedCount

    private val _totalCount = MutableLiveData(0)
    val totalCount: LiveData<Int> = _totalCount

    // ── Streak ────────────────────────────────────────────────────

    private val _streakInfo = MutableLiveData<StreakInfo>()
    val streakInfo: LiveData<StreakInfo> = _streakInfo

    // ── Social Proof ──────────────────────────────────────────────

    private val _socialProof = MutableLiveData<SocialProofData>()
    val socialProof: LiveData<SocialProofData> = _socialProof

    // ── Streak Recovery ───────────────────────────────────────────

    private val _recoveryStatus = MutableLiveData<RecoveryStatus>()
    val recoveryStatus: LiveData<RecoveryStatus> = _recoveryStatus

    private val _recoveryOffer = MutableLiveData<com.msaidizi.app.gamification.StreakRecoveryOffer?>()
    val recoveryOffer: LiveData<com.msaidizi.app.gamification.StreakRecoveryOffer?> = _recoveryOffer

    // ── Micro-Rewards ─────────────────────────────────────────────

    private val _microRewards = MutableLiveData<List<MicroRewardStatus>>()
    val microRewards: LiveData<List<MicroRewardStatus>> = _microRewards

    private val _newMicroReward = MutableLiveData<MicroReward?>()
    val newMicroReward: LiveData<MicroReward?> = _newMicroReward

    // ── Insight Rewards ───────────────────────────────────────────

    private val _insightTier = MutableLiveData<InsightTier>()
    val insightTier: LiveData<InsightTier> = _insightTier

    private val _latestInsight = MutableLiveData<InsightReward?>()
    val latestInsight: LiveData<InsightReward?> = _latestInsight

    // ── Level Progression ─────────────────────────────────────────

    private val _unlockSummary = MutableLiveData<UnlockSummary>()
    val unlockSummary: LiveData<UnlockSummary> = _unlockSummary

    private val _levelCelebration = MutableLiveData<LevelUnlockCelebration?>()
    val levelCelebration: LiveData<LevelUnlockCelebration?> = _levelCelebration

    // ── Variable Reward Popup ─────────────────────────────────────

    private val _variableReward = MutableLiveData<VariableRewardPopup?>()
    val variableReward: LiveData<VariableRewardPopup?> = _variableReward

    // ── Loading state ─────────────────────────────────────────────

    private val _isLoading = MutableLiveData(true)
    val isLoading: LiveData<Boolean> = _isLoading

    // ═══════════════════════════════════════════════════════════════
    // DATA LOADING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Load all gamification data. Call from Fragment's onViewCreated.
     */
    fun loadGamificationData() {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                // Load level info
                val level = gamificationEngine.getCurrentLevel("sw")
                _levelInfo.value = level
                _totalPoints.value = level.totalPoints

                // Load badges grouped by category
                val badgeStatuses = gamificationEngine.getBadgeStatus()
                val grouped = groupBadgesByCategory(badgeStatuses)
                _badgeGroups.value = grouped

                val earned = badgeStatuses.count { it.earned }
                _earnedCount.value = earned
                _totalCount.value = badgeStatuses.size

                // Load streak info
                _streakInfo.value = gamificationEngine.getStreakInfo()

                // Load social proof
                loadSocialProof()

                // Load streak recovery status
                _recoveryStatus.value = gamificationEngine.streakRecovery.getRecoveryStatus("sw")

                // Load micro-rewards
                _microRewards.value = gamificationEngine.microRewards?.getAvailableRewards("sw") ?: emptyList()

                // Load insight tier
                _insightTier.value = gamificationEngine.insightRewards?.getUnlockedInsights("sw")

                // Load level unlock summary
                _unlockSummary.value = gamificationEngine.levelProgression.getUnlockSummary("sw")

                // Maybe trigger a variable reward surprise (10% chance on screen open)
                maybeShowVariableReward()

                _isLoading.value = false
                Timber.d(TAG, "Gamification data loaded: %d/%d badges, level %d", earned, badgeStatuses.size, level.levelIndex)
            } catch (e: Throwable) {
                Timber.e(e, "Failed to load gamification data")
                _isLoading.value = false
            }
        }
    }

    /**
     * Refresh data (e.g., after pull-to-refresh).
     */
    fun refresh() {
        loadGamificationData()
    }

    /**
     * Dismiss the variable reward popup.
     */
    fun dismissVariableReward() {
        _variableReward.value = null
    }

    // ═══════════════════════════════════════════════════════════════
    // BADGE GROUPING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Group badges into categories with Swahili labels.
     * Categories: Onboarding, Consistency, Growth, Intelligence, Financial, Social, Loyalty
     */
    private fun groupBadgesByCategory(statuses: List<BadgeStatus>): List<BadgeCategoryGroup> {
        val categoryMap = mutableMapOf<BadgeCategory, MutableList<BadgeStatus>>()

        for (status in statuses) {
            val category = resolveCategory(status.badge.id)
            categoryMap.getOrPut(category) { mutableListOf() }.add(status)
        }

        return BadgeCategory.entries.mapNotNull { category ->
            val items = categoryMap[category] ?: return@mapNotNull null
            BadgeCategoryGroup(
                category = category,
                nameSw = category.nameSw,
                earnedCount = items.count { it.earned },
                totalCount = items.size,
                badges = items
            )
        }
    }

    /**
     * Map badge IDs to their display category.
     */
    private fun resolveCategory(badgeId: String): BadgeCategory = when (badgeId) {
        "biashara_ndogo" -> BadgeCategory.ONBOARDING
        "mlinzi_wa_siku_tatu", "bwenye_hafta", "mwezi_wa_dhahabu", "streak_ya_mwezi_mbili" -> BadgeCategory.CONSISTENCY
        "mjasiriamali_chipukizi", "bingwa_wa_biashara", "kiongozi_mkuu", "mfanyabiashara_bora" -> BadgeCategory.GROWTH
        "mtaalamu_wa_bei", "mfuatiliaji_wa_siku" -> BadgeCategory.INTELLIGENCE
        "mfanyabiashara_mkuu", "mkusanyaji_pesa", "tajiri_pointi", "mfanyabiashara_100", "malkia_wa_biashara" -> BadgeCategory.FINANCIAL
        "mfanyabiashara_wa_siku", "mfanyabiashara_mara_mbili" -> BadgeCategory.SOCIAL
        else -> BadgeCategory.LOYALTY
    }

    // ═══════════════════════════════════════════════════════════════
    // SOCIAL PROOF
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate anonymized social proof data.
     * Anti-shame: no names, no rankings, just encouraging comparisons.
     */
    private suspend fun loadSocialProof() {
        try {
            val state = gamificationEngine.getState()
            val streak = state.currentStreak

            // Generate simulated anonymized peer stats
            // In production, this would come from a backend endpoint
            val peerAvgSales = 4.2
            val peerAvgStreak = 5.8
            val userSales = state.totalSalesRecorded
            val daysActive = if (state.lastActiveDay > 0) {
                (java.time.LocalDate.now().toEpochDay() - state.lastActiveDay).toInt().coerceAtLeast(1)
            } else 1

            val dailyAvg = userSales.toDouble() / daysActive

            val percentile = when {
                dailyAvg >= peerAvgSales * 2 -> 95
                dailyAvg >= peerAvgSales * 1.5 -> 85
                dailyAvg >= peerAvgSales -> 70
                dailyAvg >= peerAvgSales * 0.7 -> 50
                else -> 30
            }

            _socialProof.value = SocialProofData(
                percentile = percentile,
                peerAvgSales = peerAvgSales,
                userDailyAvg = dailyAvg,
                peerAvgStreak = peerAvgStreak,
                userStreak = streak,
                messageSw = buildSocialProofMessage(percentile, dailyAvg, peerAvgSales, streak),
                messageEn = buildSocialProofMessageEn(percentile, dailyAvg, peerAvgSales, streak)
            )
        } catch (e: Throwable) {
            Timber.e(e, "Failed to load social proof")
        }
    }

    private fun buildSocialProofMessage(
        percentile: Int,
        userAvg: Double,
        peerAvg: Double,
        streak: Int
    ): String = when {
        percentile >= 90 -> "🏆 Wewe ni miongoni mwa wafanyabiashara 10% bora! Wastani wako wa mauzo ni ${"%.1f".format(userAvg)}/siku."
        percentile >= 70 -> "📈 Unafanya vizuri kuliko wastani! Wastani wa wafanyabiashara ni ${"%.1f".format(peerAvg)}/siku."
        streak >= 7 -> "🔥 Mfululizo wako wa siku $streak ni wa ajabu! Wafanyabiashara wenye mfululizo mrefu wanapata faida zaidi."
        else -> "💪 Endelea kurekodi mauzo kila siku! Wafanyabiashara wanaofanya hivi wanakua haraka."
    }

    private fun buildSocialProofMessageEn(
        percentile: Int,
        userAvg: Double,
        peerAvg: Double,
        streak: Int
    ): String = when {
        percentile >= 90 -> "🏆 You're in the top 10%! Your average is ${"%.1f".format(userAvg)} sales/day."
        percentile >= 70 -> "📈 You're doing better than average! Peer average is ${"%.1f".format(peerAvg)} sales/day."
        streak >= 7 -> "🔥 Your $streak-day streak is amazing! Streakers earn more."
        else -> "💪 Keep recording daily! Consistent earners grow faster."
    }

    // ═══════════════════════════════════════════════════════════════
    // VARIABLE REWARDS (Surprise Element)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Maybe show a variable reward popup (10% chance).
     * Uses the Hook Model: Variable Reward element.
     */
    private suspend fun maybeShowVariableReward() {
        if (Math.random() > 0.10) return

        val reward = gamificationEngine.getVariableReward("daily_streak", "sw")
        if (reward.bonusPoints > 0) {
            _variableReward.postValue(
                VariableRewardPopup(
                    titleSw = "🎁 Zawadi ya Surprise!",
                    titleEn = "🎁 Surprise Reward!",
                    messageSw = reward.bonusMessage ?: "Umepata +${reward.bonusPoints} pointi za ziada!",
                    messageEn = "You got +${reward.bonusPoints} bonus points!",
                    bonusPoints = reward.bonusPoints,
                    isJackpot = reward.bonusPoints >= reward.basePoints * 3
                )
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LEVEL PERKS
    // ═══════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════
    // STREAK RECOVERY
    // ═══════════════════════════════════════════════════════════════

    /**
     * Offer streak recovery after a streak break.
     */
    suspend fun offerStreakRecovery(lostStreak: Int) {
        val offer = gamificationEngine.streakRecovery.checkAndOfferRecovery(lostStreak, "sw")
        _recoveryOffer.postValue(offer)
    }

    /**
     * Execute streak recovery.
     */
    suspend fun executeRecovery(lostStreak: Int): Boolean {
        val result = gamificationEngine.streakRecovery.executeRecovery(lostStreak, "sw")
        if (result.success) {
            // Refresh all data
            loadGamificationData()
        }
        return result.success
    }

    /**
     * Dismiss recovery offer.
     */
    fun dismissRecoveryOffer() {
        _recoveryOffer.value = null
    }

    // ═══════════════════════════════════════════════════════════════
    // MICRO-REWARDS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Check for new micro-rewards after an action.
     */
    suspend fun checkMicroRewards() {
        val rewards = gamificationEngine.microRewards?.checkMilestones("sw") ?: emptyList()
        if (rewards.isNotEmpty()) {
            _newMicroReward.postValue(rewards.first())
            // Refresh available rewards list
            _microRewards.postValue(gamificationEngine.microRewards?.getAvailableRewards("sw") ?: emptyList())
        }
    }

    /**
     * Dismiss micro-reward popup.
     */
    fun dismissMicroReward() {
        _newMicroReward.value = null
    }

    // ═══════════════════════════════════════════════════════════════
    // INSIGHT REWARDS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate an insight reward for a streak milestone.
     */
    suspend fun generateInsightReward(streakMilestone: Int) {
        val insight = gamificationEngine.insightRewards?.generateInsight(streakMilestone, "sw")
        _latestInsight.postValue(insight)
        // Refresh tier
        _insightTier.postValue(gamificationEngine.insightRewards?.getUnlockedInsights("sw"))
    }

    /**
     * Dismiss insight popup.
     */
    fun dismissInsight() {
        _latestInsight.value = null
    }

    // ═══════════════════════════════════════════════════════════════
    // LEVEL PROGRESSION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Celebrate a level unlock.
     */
    suspend fun celebrateLevelUnlock(newLevel: Int) {
        val celebration = gamificationEngine.levelProgression.celebrateLevelUnlock(newLevel, "sw")
        _levelCelebration.postValue(celebration)
        // Refresh unlock summary
        _unlockSummary.postValue(gamificationEngine.levelProgression.getUnlockSummary("sw"))
    }

    /**
     * Dismiss level celebration.
     */
    fun dismissLevelCelebration() {
        _levelCelebration.value = null
    }

    // ═══════════════════════════════════════════════════════════════
    // LEVEL PERKS (existing)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get perks for a given level index. Swahili descriptions.
     */
    fun getLevelPerks(levelIndex: Int): List<String> = when (levelIndex) {
        0 -> listOf(
            "Jifunze jinsi ya kutumia Msaidizi",
            "Rekodi mauzo yako ya kwanza",
            "Angalia salio lako"
        )
        1 -> listOf(
            "Ripoti za kila wiki",
            "Vidokezo vya biashara",
            "Streak ×2 pointi"
        )
        2 -> listOf(
            "Uchambuzi wa faida",
            "Mapendekezo ya bei",
            "Streak ×3 pointi"
        )
        3 -> listOf(
            "Utabiri wa mauzo",
            "Msaada wa mikopo",
            "Streak ×5 pointi"
        )
        4 -> listOf(
            "Mentor wa biashara",
            "Ufikiaji wa mikopo",
            "Streak ×5 pointi + bonus"
        )
        5 -> listOf(
            "⭐ Legend Status",
            "Vipengele vyote vimefunguliwa",
            "Msaidizi wa kipekee"
        )
        else -> emptyList()
    }

    /**
     * Get perks for a given level index. English descriptions.
     */
    fun getLevelPerksEn(levelIndex: Int): List<String> = when (levelIndex) {
        0 -> listOf(
            "Learn how to use Msaidizi",
            "Record your first sale",
            "Check your balance"
        )
        1 -> listOf(
            "Weekly reports",
            "Business tips",
            "Streak ×2 points"
        )
        2 -> listOf(
            "Profit analysis",
            "Price suggestions",
            "Streak ×3 points"
        )
        3 -> listOf(
            "Sales predictions",
            "Loan assistance",
            "Streak ×5 points"
        )
        4 -> listOf(
            "Business mentor",
            "Loan access",
            "Streak ×5 points + bonus"
        )
        5 -> listOf(
            "⭐ Legend Status",
            "All features unlocked",
            "Exclusive assistant"
        )
        else -> emptyList()
    }
}

// ═══════════════════════════════════════════════════════════════
// UI DATA CLASSES
// ═══════════════════════════════════════════════════════════════

/**
 * Badge category enum with Swahili names and display colors.
 */
enum class BadgeCategory(
    val nameSw: String,
    val nameEn: String,
    val emoji: String,
    val colorRes: String  // Color resource name for XML lookup
) {
    ONBOARDING("Kuanza", "Onboarding", "🌱", "badge_onboarding"),
    CONSISTENCY("Uthabiti", "Consistency", "🔥", "badge_consistency"),
    GROWTH("Ukuaji", "Growth", "🌱", "badge_growth"),
    INTELLIGENCE("Busara", "Intelligence", "🧠", "badge_intelligence"),
    FINANCIAL("Fedha", "Financial", "💰", "badge_financial"),
    SOCIAL("Jamii", "Social", "👥", "badge_social"),
    LOYALTY("Uaminifu", "Loyalty", "❤️", "badge_loyalty")
}

/**
 * Grouped badges for a single category.
 */
data class BadgeCategoryGroup(
    val category: BadgeCategory,
    val nameSw: String,
    val earnedCount: Int,
    val totalCount: Int,
    val badges: List<BadgeStatus>
)

/**
 * Social proof data — anonymized peer comparison.
 */
data class SocialProofData(
    val percentile: Int,
    val peerAvgSales: Double,
    val userDailyAvg: Double,
    val peerAvgStreak: Double,
    val userStreak: Int,
    val messageSw: String,
    val messageEn: String
)

/**
 * Variable reward popup data.
 */
data class VariableRewardPopup(
    val titleSw: String,
    val titleEn: String,
    val messageSw: String,
    val messageEn: String,
    val bonusPoints: Int,
    val isJackpot: Boolean
)
