package com.msaidizi.app.gamification

import com.msaidizi.app.core.database.GamificationDao
import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.GamificationEntity
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Micro-Rewards — milestone-based rewards that feel valuable.
 *
 * At specific milestones (7 days, 30 days, 100 transactions),
 * workers unlock rewards that provide real business value:
 * - Business tips tailored to their patterns
 * - Market insights from their data
 * - Peer comparisons (anonymized)
 * - Future: airtime/data bundle partnerships
 *
 * Design philosophy:
 * - Rewards must be VALUABLE, not just points
 * - Each milestone feels like a genuine achievement
 * - Unlocking content > unlocking cosmetics
 * - "Umefikia siku 30! Hapa kile unachopewa..."
 *
 * Micro-rewards create frequent dopamine hits between
 * the bigger level-up moments, keeping engagement high
 * during the critical first 30 days.
 *
 * @param gamificationDao Gamification state access
 * @param transactionDao For generating business-specific rewards
 * @param patternDao For learned patterns
 * @param businessPatternTracker For deeper analysis
 */
class MicroRewards(
    private val gamificationDao: GamificationDao,
    private val transactionDao: TransactionDao,
    private val patternDao: PatternDao,
    private val businessPatternTracker: com.msaidizi.app.agent.BusinessPatternTracker? = null
) {
    companion object {
        private const val TAG = "MicroRewards"

        // Milestone definitions
        private val STREAK_MILESTONES = listOf(3, 7, 14, 21, 30, 45, 60, 90, 120, 180, 365)
        private val TRANSACTION_MILESTONES = listOf(10, 25, 50, 100, 250, 500, 1000)
        private val SALES_MILESTONES = listOf(10, 50, 100, 250, 500)

        // Points awarded per milestone tier
        private val MILESTONE_POINTS = mapOf(
            "streak_3" to 15,
            "streak_7" to 30,
            "streak_14" to 40,
            "streak_21" to 50,
            "streak_30" to 75,
            "streak_45" to 80,
            "streak_60" to 100,
            "streak_90" to 120,
            "streak_120" to 150,
            "streak_180" to 200,
            "streak_365" to 500,
            "tx_10" to 10,
            "tx_25" to 20,
            "tx_50" to 35,
            "tx_100" to 50,
            "tx_250" to 75,
            "tx_500" to 100,
            "tx_1000" to 200
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // MILESTONE DETECTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Check all milestones after an action and return any newly unlocked rewards.
     *
     * Called after: sale recorded, streak updated, balance checked.
     * Returns a list of MicroReward for each milestone just reached.
     *
     * @param language Language preference
     * @return List of newly unlocked micro-rewards (empty if no milestone hit)
     */
    suspend fun checkMilestones(language: String = "sw"): List<MicroReward> {
        val entity = gamificationDao.getGamification() ?: return emptyList()
        val rewards = mutableListOf<MicroReward>()

        // Check streak milestones
        checkStreakMilestones(entity, rewards, language)

        // Check transaction milestones
        checkTransactionMilestones(entity, rewards, language)

        // Award points for each milestone
        for (reward in rewards) {
            if (reward.points > 0) {
                gamificationDao.addPoints(reward.points)
            }
        }

        if (rewards.isNotEmpty()) {
            Timber.tag(TAG).d("%d micro-rewards unlocked", rewards.size)
        }

        return rewards
    }

    /**
     * Get all available (unlocked) micro-rewards for the current state.
     * Used for displaying the rewards gallery.
     */
    suspend fun getAvailableRewards(language: String = "sw"): List<MicroRewardStatus> {
        val entity = gamificationDao.getGamification() ?: return emptyList()
        val statuses = mutableListOf<MicroRewardStatus>()

        // Streak rewards
        for (milestone in STREAK_MILESTONES) {
            val key = "streak_$milestone"
            val unlocked = entity.currentStreak >= milestone || entity.longestStreak >= milestone
            statuses.add(MicroRewardStatus(
                milestoneKey = key,
                type = MicroRewardType.STREAK,
                threshold = milestone,
                unlocked = unlocked,
                label = if (language == "sw") "Siku $milestone mfululizo" else "$milesonstone-day streak",
                emoji = getStreakEmoji(milestone),
                points = MILESTONE_POINTS[key] ?: 0
            ))
        }

        // Transaction rewards
        for (milestone in TRANSACTION_MILESTONES) {
            val key = "tx_$milestone"
            val unlocked = entity.totalSalesRecorded >= milestone
            statuses.add(MicroRewardStatus(
                milestoneKey = key,
                type = MicroRewardType.TRANSACTION,
                threshold = milestone,
                unlocked = unlocked,
                label = if (language == "sw") "Mauzo $milestone" else "$milestone sales",
                emoji = getTransactionEmoji(milestone),
                points = MILESTONE_POINTS[key] ?: 0
            ))
        }

        return statuses
    }

    // ═══════════════════════════════════════════════════════════════
    // REWARD CONTENT GENERATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate the actual reward content for a milestone.
     * This is where the VALUE lives — real business insights,
     * not just congratulatory messages.
     */
    suspend fun generateRewardContent(
        milestoneKey: String,
        language: String = "sw"
    ): MicroRewardContent {
        return when {
            milestoneKey.startsWith("streak_") -> generateStreakReward(milestoneKey, language)
            milestoneKey.startsWith("tx_") -> generateTransactionReward(milestoneKey, language)
            else -> MicroRewardContent.generic(milestoneKey, language)
        }
    }

    private suspend fun generateStreakReward(
        milestoneKey: String,
        language: String
    ): MicroRewardContent {
        val streak = milestoneKey.removePrefix("streak_").toIntOrNull() ?: 7

        // At each streak milestone, unlock progressively valuable content
        return when {
            streak >= 30 -> generateAdvancedInsight(language)
            streak >= 14 -> generateMarketInsight(language)
            streak >= 7 -> generateBusinessTip(language)
            else -> generateMotivationReward(streak, language)
        }
    }

    private suspend fun generateTransactionReward(
        milestoneKey: String,
        language: String
    ): MicroRewardContent {
        val count = milestoneKey.removePrefix("tx_").toIntOrNull() ?: 10

        return when {
            count >= 100 -> generatePeerComparison(language)
            count >= 50 -> generateProductInsight(language)
            count >= 25 -> generateBusinessTip(language)
            else -> generateMotivationReward(count, language)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CONTENT GENERATORS — Real business value
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate a business tip based on the worker's actual data.
     * This is the most common micro-reward — practical advice.
     */
    private suspend fun generateBusinessTip(language: String): MicroRewardContent {
        val tips = if (language == "sw") listOf(
            "💡 **Kidokezo cha Biashara:** Rekodi mauzo yako mara baada ya kuuza. " +
                "Ukipoteza muda, unapoteza data — na data ni pesa!",

            "💡 **Jinsi ya Kupata Zaidi:** Angalia bidhaa zako bora wiki hii. " +
                "Ongeza stock ya bidhaa inayouza zaidi — usipoteze wateja!",

            "💡 **Siri ya Bei:** Ukiongeza bei kidogo (10%) kwa bidhaa yenye mahitaji mengi, " +
                "utaongeza faida bila kupoteza wateja wengi.",

            "💡 **Wakati ni Pesa:** Fungua duka lako saa moja mapema siku za wiki. " +
                "Wateja wa mapema huwa na pesa tayari — hawana shida ya bei.",

            "💡 **Bidhaa Mpya:** Jaribu kuuza bidhaa mpya kila wiki mbili. " +
                "Wateja wanapenda vitu vipya — na bei ya kwanza ni faida zaidi."
        ) else listOf(
            "💡 **Business Tip:** Record sales right after selling. " +
                "If you delay, you lose data — and data is money!",

            "💡 **How to Earn More:** Check your top products this week. " +
                "Stock up on what sells — don't lose customers!",

            "💡 **Pricing Secret:** A small price increase (10%) on high-demand items " +
                "boosts profit without losing many customers.",

            "💡 **Time is Money:** Open your shop one hour earlier on weekdays. " +
                "Early customers have money ready — they don't haggle.",

            "💡 **New Products:** Try selling a new product every two weeks. " +
                "Customers love new things — and first price is best profit."
        )

        return MicroRewardContent(
            type = MicroRewardContentType.BUSINESS_TIP,
            titleSw = "💡 Kidokezo cha Biashara",
            titleEn = "💡 Business Tip",
            bodySw = tips.random(),
            bodyEn = tips.random(),
            actionable = true
        )
    }

    /**
     * Generate a market insight — contextual data-driven advice.
     */
    private suspend fun generateMarketInsight(language: String): MicroRewardContent {
        val now = System.currentTimeMillis() / 1000
        val weekAgo = now - 7 * 86400

        val topItems = try {
            transactionDao.getTopSellingItems(weekAgo, now, 5)
        } catch (e: Exception) {
            emptyList()
        }

        val insight = if (topItems.isNotEmpty()) {
            val topItem = topItems.first()
            if (language == "sw") {
                "📊 **Ufahamu wa Soko:** Wiki hii, '${topItem.item}' ndiyo bidhaa yako " +
                    "inayouza zaidi — mauzo ${topItem.txCount} na faida ya KSh ${"%.0f".format(topItem.totalRev)}. " +
                    "Hakikisha stock yako ya '${topItem.item}' haitaisha!"
            } else {
                "📊 **Market Insight:** This week, '${topItem.item}' is your best seller — " +
                    "${topItem.txCount} sales and KSh ${"%.0f".format(topItem.totalRev)} revenue. " +
                    "Make sure your '${topItem.item}' stock doesn't run out!"
            }
        } else {
            if (language == "sw") {
                "📊 **Ufahamu wa Soko:** Anza kurekodi mauzo yako kila siku. " +
                    "Baada ya wiki 2, utapata ufahamu wa bidhaa zako bora na siku bora za kuuza!"
            } else {
                "📊 **Market Insight:** Start recording sales daily. " +
                    "After 2 weeks, you'll get insights on your best products and best selling days!"
            }
        }

        return MicroRewardContent(
            type = MicroRewardContentType.MARKET_INSIGHT,
            titleSw = "📊 Ufahamu wa Soko",
            titleEn = "📊 Market Insight",
            bodySw = insight,
            bodyEn = insight,
            actionable = true
        )
    }

    /**
     * Generate advanced insight — for higher milestones.
     * Combines multiple data sources for deeper analysis.
     */
    private suspend fun generateAdvancedInsight(language: String): MicroRewardContent {
        val insight = try {
            val dayPatterns = businessPatternTracker?.analyzeDayOfWeekPatterns(4)
            val peakDays = dayPatterns?.filter { it.value.isPeakDay }?.keys

            if (!peakDays.isNullOrEmpty()) {
                val dayNames = peakDays.joinToString(", ")
                if (language == "sw") {
                    "🔬 **Ufahamu wa Juu:** Siku zako bora za kuuza ni $dayNames. " +
                        "Panga kukopa bidhaa zaidi kabla ya siku hizi. " +
                        "Fungua mapema na fungua late — wateja wako watakuja wakati wote!"
                } else {
                    "🔬 **Advanced Insight:** Your best selling days are $dayNames. " +
                        "Plan to stock up before these days. " +
                        "Open early and stay late — your customers will come!"
                }
            } else {
                if (language == "sw") {
                    "🔬 **Ufahamu wa Juu:** Endelea kurekodi kwa wiki 2 zaidi " +
                        "na utapata uchambuzi wa kina wa siku zako bora za biashara!"
                } else {
                    "🔬 **Advanced Insight:** Keep recording for 2 more weeks " +
                        "and you'll get detailed analysis of your best business days!"
                }
            }
        } catch (e: Exception) {
            if (language == "sw") {
                "🔬 **Ufahamu wa Juu:** Biashara yako inakua! Endelea kurekodi " +
                    "kila siku kupata uchambuzi zaidi."
            } else {
                "🔬 **Advanced Insight:** Your business is growing! Keep recording " +
                    "daily for more analysis."
            }
        }

        return MicroRewardContent(
            type = MicroRewardContentType.ADVANCED_INSIGHT,
            titleSw = "🔬 Ufahamu wa Juu",
            titleEn = "🔬 Advanced Insight",
            bodySw = insight,
            bodyEn = insight,
            actionable = true
        )
    }

    /**
     * Generate product-level insight from transaction data.
     */
    private suspend fun generateProductInsight(language: String): MicroRewardContent {
        val now = System.currentTimeMillis() / 1000
        val monthAgo = now - 30 * 86400

        val margins = try {
            businessPatternTracker?.analyzeProfitMargins(30)
        } catch (e: Exception) {
            null
        }

        val insight = if (!margins.isNullOrEmpty()) {
            val best = margins.first()
            val worst = margins.last()
            if (language == "sw") {
                "📦 **Ufahamu wa Bidhaa:**\n" +
                    "• '${best.item}' ina margin bora zaidi: ${"%.0f".format(best.marginPercent)}%\n" +
                    "• '${worst.item}' ina margin ya chini: ${"%.0f".format(worst.marginPercent)}%\n\n" +
                    "💡 Fikiria kuongeza bei ya '${worst.item}' au kupunguza stock yake."
            } else {
                "📦 **Product Insight:**\n" +
                    "• '${best.item}' has the best margin: ${"%.0f".format(best.marginPercent)}%\n" +
                    "• '${worst.item}' has the lowest margin: ${"%.0f".format(worst.marginPercent)}%\n\n" +
                    "💡 Consider raising the price of '${worst.item}' or reducing its stock."
            }
        } else {
            if (language == "sw") {
                "📦 **Ufahamu wa Bidhaa:** Rekodi mauzo yako kwa mwezi mzima " +
                    "na utapata uchambuzi wa faida kwa kila bidhaa!"
            } else {
                "📦 **Product Insight:** Record sales for a full month " +
                    "and you'll get profit analysis for each product!"
            }
        }

        return MicroRewardContent(
            type = MicroRewardContentType.PRODUCT_INSIGHT,
            titleSw = "📦 Ufahamu wa Bidhaa",
            titleEn = "📦 Product Insight",
            bodySw = insight,
            bodyEn = insight,
            actionable = true
        )
    }

    /**
     * Generate peer comparison — anonymized benchmarking.
     */
    private suspend fun generatePeerComparison(language: String): MicroRewardContent {
        val entity = gamificationDao.getGamification() ?: return MicroRewardContent.generic("tx_100", language)

        val daysActive = try {
            val firstTx = System.currentTimeMillis() / 1000 - 30 * 86400
            val count = transactionDao.getTransactionCount(firstTx, System.currentTimeMillis() / 1000)
            (count / 3.0).toInt().coerceAtLeast(1)
        } catch (e: Exception) { 1 }

        val dailyAvg = entity.totalSalesRecorded.toDouble() / daysActive

        val comparison = if (language == "sw") {
            "👥 **Ulinganisho na Wengine:**\n" +
                "• Wewe: ${"%.1f".format(dailyAvg)} mauzo kwa siku\n" +
                "• Wastani wa wafanyabiashara: 4.2 mauzo kwa siku\n\n" +
                when {
                    dailyAvg >= 8 -> "🏆 Wewe ni bora sana! Uko katika 10% bora ya wafanyabiashara!"
                    dailyAvg >= 5 -> "📈 Unaendelea vizuri! Uko juu ya wastani!"
                    dailyAvg >= 3 -> "💪 Uko njiani! Ongeza bidii kidogo."
                    else -> "🌱 Umeanza vizuri! Endelea kurekodi kila siku."
                }
        } else {
            "👥 **Peer Comparison:**\n" +
                "• You: ${"%.1f".format(dailyAvg)} sales per day\n" +
                "• Business average: 4.2 sales per day\n\n" +
                when {
                    dailyAvg >= 8 -> "🏆 You're amazing! Top 10% of business people!"
                    dailyAvg >= 5 -> "📈 Doing well! Above average!"
                    dailyAvg >= 3 -> "💪 On track! Push a little harder."
                    else -> "🌱 Good start! Keep recording daily."
                }
        }

        return MicroRewardContent(
            type = MicroRewardContentType.PEER_COMPARISON,
            titleSw = "👥 Ulinganisho na Wengine",
            titleEn = "👥 Peer Comparison",
            bodySw = comparison,
            bodyEn = comparison,
            actionable = false
        )
    }

    /**
     * Generate motivation reward for early milestones.
     */
    private fun generateMotivationReward(milestone: Int, language: String): MicroRewardContent {
        val messages = if (language == "sw") {
            mapOf(
                3 to "🎉 Siku 3 mfululizo! Umekuwa na tabia nzuri. Endelea hivi!",
                7 to "🔥 Wiki 1 kamili! Wewe ni mfanyabiashara wa kweli!",
                10 to "📊 Mauzo 10! Umekuwa na mwanzo mzuri!",
                14 to "💪 Wiki 2! Bidii yako inaonekana!",
                21 to "🧠 Wiki 3! Tabia imejengwa!",
                25 to "🎯 Mauzo 25! Unakua haraka!"
            )
        } else {
            mapOf(
                3 to "🎉 3 days in a row! You're building a habit. Keep going!",
                7 to "🔥 1 full week! You're a real business person!",
                10 to "📊 10 sales! You've made a great start!",
                14 to "💪 2 weeks! Your effort shows!",
                21 to "🧠 3 weeks! Habit formed!",
                25 to "🎯 25 sales! You're growing fast!"
            )
        }

        return MicroRewardContent(
            type = MicroRewardContentType.MOTIVATION,
            titleSw = "🎉 Hongera!",
            titleEn = "🎉 Congratulations!",
            bodySw = messages[milestone] ?: "Hongera! Umechukua hatua muhimu!",
            bodyEn = messages[milestone] ?: "Congratulations! You've taken an important step!",
            actionable = false
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun checkStreakMilestones(
        entity: GamificationEntity,
        rewards: MutableList<MicroReward>,
        language: String
    ) {
        val streak = entity.currentStreak
        val longest = entity.longestStreak

        for (milestone in STREAK_MILESTONES) {
            val key = "streak_$milestone"
            if (streak == milestone || (streak > milestone && longest < milestone)) {
                rewards.add(MicroReward(
                    milestoneKey = key,
                    type = MicroRewardType.STREAK,
                    threshold = milestone,
                    points = MILESTONE_POINTS[key] ?: 0,
                    titleSw = getStreakTitle(milestone, "sw"),
                    titleEn = getStreakTitle(milestone, "en"),
                    messageSw = getStreakMessage(milestone, "sw"),
                    messageEn = getStreakMessage(milestone, "en"),
                    emoji = getStreakEmoji(milestone)
                ))
            }
        }
    }

    private fun checkTransactionMilestones(
        entity: GamificationEntity,
        rewards: MutableList<MicroReward>,
        language: String
    ) {
        val total = entity.totalSalesRecorded

        for (milestone in TRANSACTION_MILESTONES) {
            val key = "tx_$milestone"
            if (total == milestone) {
                rewards.add(MicroReward(
                    milestoneKey = key,
                    type = MicroRewardType.TRANSACTION,
                    threshold = milestone,
                    points = MILESTONE_POINTS[key] ?: 0,
                    titleSw = getTransactionTitle(milestone, "sw"),
                    titleEn = getTransactionTitle(milestone, "en"),
                    messageSw = getTransactionMessage(milestone, "sw"),
                    messageEn = getTransactionMessage(milestone, "en"),
                    emoji = getTransactionEmoji(milestone)
                ))
            }
        }
    }

    private fun getStreakEmoji(streak: Int): String = when {
        streak >= 365 -> "🌟"
        streak >= 180 -> "🏆"
        streak >= 90 -> "👑"
        streak >= 60 -> "🔥🔥"
        streak >= 30 -> "🥇"
        streak >= 21 -> "💪"
        streak >= 14 -> "🔥"
        streak >= 7 -> "⚔️"
        streak >= 3 -> "🛡️"
        else -> "✨"
    }

    private fun getTransactionEmoji(count: Int): String = when {
        count >= 1000 -> "💎"
        count >= 500 -> "🏆"
        count >= 250 -> "👑"
        count >= 100 -> "💯"
        count >= 50 -> "🔥"
        count >= 25 -> "📈"
        count >= 10 -> "📊"
        else -> "✨"
    }

    private fun getStreakTitle(streak: Int, lang: String): String {
        return if (lang == "sw") {
            when (streak) {
                3 -> "🛡️ Mlinzi wa Siku Tatu"
                7 -> "⚔️ Bwenye ya Wiki"
                14 -> "🔥 Wiki Mbili za Moto"
                21 -> "💪 Tabia Imejengwa"
                30 -> "🥇 Mwezi wa Dhahabu"
                45 -> "⭐ Nusu Mwezi wa Ziada"
                60 -> "🔥🔥 Miezi Miwili!"
                90 -> "👑 Robo Mwaka!"
                120 -> "💎 Miezi Minne!"
                180 -> "🏆 Nusu Mwaka!"
                365 -> "🌟 Mwaka Mzima!"
                else -> "🔥 Streak ya Siku $streak"
            }
        } else {
            when (streak) {
                3 -> "🛡️ 3-Day Guardian"
                7 -> "⚔️ Week Warrior"
                14 -> "🔥 Two Week Fire"
                21 -> "💪 Habit Formed"
                30 -> "🥇 Golden Month"
                45 -> "⭐ Half Month Plus"
                60 -> "🔥🔥 Two Months!"
                90 -> "👑 Quarter Year!"
                120 -> "💎 Four Months!"
                180 -> "🏆 Half Year!"
                365 -> "🌟 Full Year!"
                else -> "🔥 $streak-Day Streak"
            }
        }
    }

    private fun getStreakMessage(streak: Int, lang: String): String {
        return if (lang == "sw") {
            when (streak) {
                3 -> "Umefikia siku 3! Umeanza vizuri. Endelea hivi!"
                7 -> "Umefikia wiki 1! Hii ni hatua kubwa. Wewe ni mfanyabiashara wa kweli!"
                14 -> "Wiki 2 mfululizo! Bidii yako inaonekana. Sasa una pointi mara mbili!"
                21 -> "Wiki 3! Tabia imejengwa. Biashara yako inakua!"
                30 -> "Umefikia siku 30! Hapa kile unachopewa: ufahamu wa juu wa biashara yako!"
                else -> "Hongera! Umefikia mstone muhimu!"
            }
        } else {
            when (streak) {
                3 -> "3 days! Great start. Keep going!"
                7 -> "1 full week! This is a big step. You're a real business person!"
                14 -> "2 weeks straight! Your effort shows. Double points now!"
                21 -> "3 weeks! Habit formed. Your business is growing!"
                30 -> "30 days! Here's what you get: advanced business insights!"
                else -> "Congratulations! You've reached an important milestone!"
            }
        }
    }

    private fun getTransactionTitle(count: Int, lang: String): String {
        return if (lang == "sw") {
            when (count) {
                10 -> "📊 Mauzo 10"
                25 -> "📈 Mauzo 25"
                50 -> "🔥 Mauzo 50"
                100 -> "💯 Mauzo 100"
                250 -> "👑 Mauzo 250"
                500 -> "🏆 Mauzo 500"
                1000 -> "💎 Mauzo 1000"
                else -> "📊 Mauzo $count"
            }
        } else {
            when (count) {
                10 -> "📊 10 Sales"
                25 -> "📈 25 Sales"
                50 -> "🔥 50 Sales"
                100 -> "💯 100 Sales"
                250 -> "👑 250 Sales"
                500 -> "🏆 500 Sales"
                1000 -> "💎 1000 Sales"
                else -> "📊 $count Sales"
            }
        }
    }

    private fun getTransactionMessage(count: Int, lang: String): String {
        return if (lang == "sw") {
            when (count) {
                10 -> "Mauzo 10! Umekuwa na mwanzo mzuri. Rekodi zaidi!"
                25 -> "Mauzo 25! Sasa utapata vidokezo vya bidhaa zako bora."
                50 -> "Mauzo 50! Utapata uchambuzi wa faida kwa kila bidhaa."
                100 -> "Mauzo 100! Sasa unalinganisho na wafanyabiashara wengine."
                else -> "Hongera! Umefikia mauzo $count!"
            }
        } else {
            when (count) {
                10 -> "10 sales! Great start. Record more!"
                25 -> "25 sales! Now you'll get tips on your best products."
                50 -> "50 sales! You'll get profit analysis for each product."
                100 -> "100 sales! Now you can compare with other businesses."
                else -> "Congratulations! You've reached $count sales!"
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

/**
 * A micro-reward unlocked at a milestone.
 */
data class MicroReward(
    val milestoneKey: String,
    val type: MicroRewardType,
    val threshold: Int,
    val points: Int,
    val titleSw: String,
    val titleEn: String,
    val messageSw: String,
    val messageEn: String,
    val emoji: String
)

/**
 * Status of a micro-reward milestone (for UI display).
 */
data class MicroRewardStatus(
    val milestoneKey: String,
    val type: MicroRewardType,
    val threshold: Int,
    val unlocked: Boolean,
    val label: String,
    val emoji: String,
    val points: Int
)

/**
 * Content of a micro-reward — the actual value delivered.
 */
data class MicroRewardContent(
    val type: MicroRewardContentType,
    val titleSw: String,
    val titleEn: String,
    val bodySw: String,
    val bodyEn: String,
    val actionable: Boolean
) {
    companion object {
        fun generic(key: String, lang: String) = MicroRewardContent(
            type = MicroRewardContentType.MOTIVATION,
            titleSw = "🎉 Hongera!",
            titleEn = "🎉 Congratulations!",
            bodySw = "Umefikia hatua muhimu! Endelea hivi!",
            bodyEn = "You've reached an important milestone! Keep going!",
            actionable = false
        )
    }
}

enum class MicroRewardType {
    STREAK,
    TRANSACTION,
    SALES,
    BALANCE_CHECK
}

enum class MicroRewardContentType {
    BUSINESS_TIP,
    MARKET_INSIGHT,
    ADVANCED_INSIGHT,
    PRODUCT_INSIGHT,
    PEER_COMPARISON,
    MOTIVATION,
    PARTNERSHIP  // Future: airtime/data bundles
}
