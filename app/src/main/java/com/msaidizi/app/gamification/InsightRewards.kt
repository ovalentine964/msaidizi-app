package com.msaidizi.app.gamification

import com.msaidizi.app.agent.BusinessPatternTracker
import com.msaidizi.app.core.database.GamificationDao
import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.PatternType
import timber.log.Timber
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Insight Rewards — business intelligence as gamification rewards.
 *
 * After completing streaks and reaching milestones, workers unlock
 * contextual business insights. These are VALUABLE — they help
 * the worker make better business decisions.
 *
 * Philosophy: The best reward is knowledge that makes you money.
 *
 * Insight tiers:
 * - Bronze (7-day streak): Basic tips — best day to sell
 * - Silver (14-day streak): Product analysis — what sells most
 * - Gold (30-day streak): Advanced patterns — peak hours, pricing
 * - Platinum (60-day streak): Predictive — what to stock next week
 * - Diamond (90-day streak): Strategic — growth opportunities
 *
 * Each insight is generated from the worker's OWN data,
 * making it immediately actionable.
 *
 * "Siku yako bora ni Jumatano — fungua mapema!"
 * "Wateja wengi wanapenda nyanya — ongeza stock!"
 *
 * @param gamificationDao Gamification state
 * @param transactionDao Transaction data for analysis
 * @param patternDao Learned business patterns
 * @param businessPatternTracker Deep analysis engine
 */
class InsightRewards(
    private val gamificationDao: GamificationDao,
    private val transactionDao: TransactionDao,
    private val patternDao: PatternDao,
    private val businessPatternTracker: BusinessPatternTracker? = null
) {
    companion object {
        private const val TAG = "InsightRewards"

        // Insight unlock thresholds
        private const val BRONZE_STREAK = 7
        private const val SILVER_STREAK = 14
        private const val GOLD_STREAK = 30
        private const val PLATINUM_STREAK = 60
        private const val DIAMOND_STREAK = 90

        // Minimum transactions for data-driven insights
        private const val MIN_TRANSACTIONS_FOR_INSIGHT = 10
    }

    // ═══════════════════════════════════════════════════════════════
    // INSIGHT UNLOCK CHECK
    // ═══════════════════════════════════════════════════════════════

    /**
     * Check what insights are unlocked based on current streak.
     *
     * @return InsightTier with unlocked insights and next unlock target
     */
    suspend fun getUnlockedInsights(language: String = "sw"): InsightTier {
        val entity = gamificationDao.getGamification()
            ?: return InsightTier.empty()

        val streak = maxOf(entity.currentStreak, entity.longestStreak)
        val totalSales = entity.totalSalesRecorded

        val tier = when {
            streak >= DIAMOND_STREAK -> InsightLevel.DIAMOND
            streak >= PLATINUM_STREAK -> InsightLevel.PLATINUM
            streak >= GOLD_STREAK -> InsightLevel.GOLD
            streak >= SILVER_STREAK -> InsightLevel.SILVER
            streak >= BRONZE_STREAK -> InsightLevel.BRONZE
            else -> InsightLevel.LOCKED
        }

        val nextTier = when (tier) {
            InsightLevel.DIAMOND -> null
            InsightLevel.PLATINUM -> TierProgress(DIAMOND_STREAK, DIAMOND_STREAK - streak, "💎")
            InsightLevel.GOLD -> TierProgress(PLATINUM_STREAK, PLATINUM_STREAK - streak, "🏆")
            InsightLevel.SILVER -> TierProgress(GOLD_STREAK, GOLD_STREAK - streak, "🥇")
            InsightLevel.BRONZE -> TierProgress(SILVER_STREAK, SILVER_STREAK - streak, "🔥")
            InsightLevel.LOCKED -> TierProgress(BRONZE_STREAK, BRONZE_STREAK - streak, "🛡️")
        }

        return InsightTier(
            currentTier = tier,
            streakDays = streak,
            totalSales = totalSales,
            nextTier = nextTier,
            unlockedInsightCount = tier.ordinal,
            totalInsightCount = InsightLevel.entries.size - 1, // Exclude LOCKED
            message = generateTierMessage(tier, language)
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // INSIGHT GENERATION — The actual valuable content
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate a contextual business insight after a streak milestone.
     * This is called when the worker completes a streak milestone.
     *
     * @param streakMilestone The streak value that was just reached
     * @param language Language preference
     * @return InsightReward with actionable business intelligence
     */
    suspend fun generateInsight(
        streakMilestone: Int,
        language: String = "sw"
    ): InsightReward {
        val entity = gamificationDao.getGamification()
            ?: return InsightReward.error(language)

        // Determine insight tier based on streak
        val tier = when {
            streakMilestone >= DIAMOND_STREAK -> InsightLevel.DIAMOND
            streakMilestone >= PLATINUM_STREAK -> InsightLevel.PLATINUM
            streakMilestone >= GOLD_STREAK -> InsightLevel.GOLD
            streakMilestone >= SILVER_STREAK -> InsightLevel.SILVER
            streakMilestone >= BRONZE_STREAK -> InsightLevel.BRONZE
            else -> InsightLevel.LOCKED
        }

        if (tier == InsightLevel.LOCKED) {
            return InsightReward(
                tier = tier,
                title = if (language == "sw") "🔒 Funga Insights" else "🔒 Locked Insights",
                message = if (language == "sw") {
                    "Fikia siku $BRONZE_STREAK mfululizo kufungua insights za biashara yako!"
                } else {
                    "Reach a $BRONZE_STREAK-day streak to unlock business insights!"
                },
                actionable = false,
                insightType = InsightType.GENERAL
            )
        }

        // Generate tier-appropriate insight
        return when (tier) {
            InsightLevel.BRONZE -> generateBronzeInsight(language)
            InsightLevel.SILVER -> generateSilverInsight(language)
            InsightLevel.GOLD -> generateGoldInsight(language)
            InsightLevel.PLATINUM -> generatePlatinumInsight(language)
            InsightLevel.DIAMOND -> generateDiamondInsight(language)
            else -> InsightReward.error(language)
        }
    }

    /**
     * Generate the "best day" insight — Bronze tier.
     * "Siku yako bora ni Jumatano — fungua mapema!"
     */
    private suspend fun generateBronzeInsight(language: String): InsightReward {
        val now = System.currentTimeMillis() / 1000
        val monthAgo = now - 30 * 86400

        return try {
            val dailyTotals = transactionDao.getDailySalesTotals(monthAgo)
            if (dailyTotals.size < 7) {
                return InsightReward(
                    tier = InsightLevel.BRONZE,
                    title = if (language == "sw") "💡 Kidokezo" else "💡 Tip",
                    message = if (language == "sw") {
                        "Rekodi mauzo yako kwa wiki 2 zaidi na utapata kujua siku yako bora ya kuuza!"
                    } else {
                        "Record sales for 2 more weeks to discover your best selling day!"
                    },
                    actionable = true,
                    insightType = InsightType.BEST_DAY
                )
            }

            // Group by day of week
            val dayTotals = Array(7) { mutableListOf<Double>() }
            for (total in dailyTotals) {
                val date = java.time.Instant.ofEpochSecond(total.day)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                val dayOfWeek = date.dayOfWeek.value - 1
                dayTotals[dayOfWeek].add(total.total)
            }

            val dayAverages = dayTotals.mapIndexed { index, sales ->
                index to if (sales.isNotEmpty()) sales.average() else 0.0
            }

            val bestDay = dayAverages.maxByOrNull { it.second }
            val worstDay = dayAverages.filter { it.second > 0 }.minByOrNull { it.second }

            if (bestDay == null || bestDay.second <= 0) {
                return InsightReward.error(language)
            }

            val bestDayName = getDayName(bestDay.first, language)
            val bestDayAvg = bestDay.second

            val message = if (language == "sw") {
                "💡 Siku yako bora ni $bestDayName — wastani wa mauzo: KSh ${"%.0f".format(bestDayAvg)}!\n\n" +
                    "📋 **Jinsi ya kutumia hii:**\n" +
                    "• Fungua duka mapema $bestDayName\n" +
                    "• Hakikisha stock yako iko juu\n" +
                    "• Panga mikutano na wateja wakuu siku hiyo"
            } else {
                "💡 Your best day is $bestDayName — average sales: KSh ${"%.0f".format(bestDayAvg)}!\n\n" +
                    "📋 **How to use this:**\n" +
                    "• Open your shop early on $bestDayName\n" +
                    "• Make sure your stock is high\n" +
                    "• Schedule meetings with key customers that day"
            }

            InsightReward(
                tier = InsightLevel.BRONZE,
                title = if (language == "sw") "💡 Siku Yako Bora" else "💡 Your Best Day",
                message = message,
                actionable = true,
                insightType = InsightType.BEST_DAY,
                data = mapOf(
                    "bestDay" to bestDayName,
                    "averageSales" to bestDayAvg.toString()
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate bronze insight")
            InsightReward.error(language)
        }
    }

    /**
     * Generate "top product" insight — Silver tier.
     * "Wateja wengi wanapenda nyanya — ongeza stock!"
     */
    private suspend fun generateSilverInsight(language: String): InsightReward {
        val now = System.currentTimeMillis() / 1000
        val twoWeeksAgo = now - 14 * 86400

        return try {
            val topItems = transactionDao.getTopSellingItems(twoWeeksAgo, now, 5)
            if (topItems.isEmpty()) {
                return InsightReward(
                    tier = InsightLevel.SILVER,
                    title = if (language == "sw") "📦 Ufahamu wa Bidhaa" else "📦 Product Insight",
                    message = if (language == "sw") {
                        "Rekodi mauzo yako na utapata kujua bidhaa yako inayouza zaidi!"
                    } else {
                        "Record sales to discover your best-selling product!"
                    },
                    actionable = true,
                    insightType = InsightType.TOP_PRODUCT
                )
            }

            val top = topItems.first()
            val second = topItems.getOrNull(1)

            val message = if (language == "sw") {
                "📦 Bidhaa yako #1 ni '${top.item}' — mauzo ${top.txCount} wiki hii!\n" +
                    "💰 Faida: KSh ${"%.0f".format(top.totalRev)}\n\n" +
                    if (second != null) {
                        "📊 #2 ni '${second.item}' — mauzo ${second.txCount}\n\n"
                    } else { "\n" } +
                    "📋 **Jinsi ya kutumia hii:**\n" +
                    "• Ongeza stock ya '${top.item}' — wateja wanataka zaidi!\n" +
                    "• Weka '${top.item}' mahali pa wateja kuona kwanza\n" +
                    "• Fikiria kupunguza bei ya bidhaa zisizouza"
            } else {
                "📦 Your #1 product is '${top.item}' — ${top.txCount} sales this week!\n" +
                    "💰 Revenue: KSh ${"%.0f".format(top.totalRev)}\n\n" +
                    if (second != null) {
                        "📊 #2 is '${second.item}' — ${second.txCount} sales\n\n"
                    } else { "\n" } +
                    "📋 **How to use this:**\n" +
                    "• Stock more '${top.item}' — customers want more!\n" +
                    "• Place '${top.item}' where customers see it first\n" +
                    "• Consider reducing prices on slow-moving items"
            }

            InsightReward(
                tier = InsightLevel.SILVER,
                title = if (language == "sw") "📦 Bidhaa Yako Bora" else "📦 Your Top Product",
                message = message,
                actionable = true,
                insightType = InsightType.TOP_PRODUCT,
                data = mapOf(
                    "topProduct" to top.item,
                    "topSales" to top.txCount.toString(),
                    "topRevenue" to top.totalRev.toString()
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate silver insight")
            InsightReward.error(language)
        }
    }

    /**
     * Generate "peak hours" insight — Gold tier.
     * "Wateja wengi wanakuja saa 2-4 za afternoon!"
     */
    private suspend fun generateGoldInsight(language: String): InsightReward {
        return try {
            val peakHours = businessPatternTracker?.analyzePeakHours(14)
            if (peakHours.isNullOrEmpty() || peakHours.size < 3) {
                return InsightReward(
                    tier = InsightLevel.GOLD,
                    title = if (language == "sw") "⏰ Masaa ya Kazi" else "⏰ Business Hours",
                    message = if (language == "sw") {
                        "Rekodi mauzo yako kwa wiki 2 zaidi na utapata kujua masaa yako bora!"
                    } else {
                        "Record sales for 2 more weeks to discover your peak hours!"
                    },
                    actionable = true,
                    insightType = InsightType.PEAK_HOURS
                )
            }

            val peaks = peakHours.filter { it.isPeakHour }.take(3)
            val peakLabels = peaks.joinToString(", ") { "${it.hour}:00" }

            val message = if (language == "sw") {
                "⏰ Masaa yako bora ya kuuza: $peakLabels\n\n" +
                    "📋 **Jinsi ya kutumia hii:**\n" +
                    "• Fungua duka kabla ya saa ${peaks.first().hour}:00\n" +
                    "• Hakikisha stock iko juu wakati huu\n" +
                    "• Usifunge duka wakati wa kilele\n" +
                    "• Panga mapumziko yako nje ya masaa haya"
            } else {
                "⏰ Your peak selling hours: $peakLabels\n\n" +
                    "📋 **How to use this:**\n" +
                    "• Open your shop before ${peaks.first().hour}:00\n" +
                    "• Make sure stock is high during these hours\n" +
                    "• Don't close during peak times\n" +
                    "• Schedule breaks outside these hours"
            }

            InsightReward(
                tier = InsightLevel.GOLD,
                title = if (language == "sw") "⏰ Masaa Yako Bora" else "⏰ Your Peak Hours",
                message = message,
                actionable = true,
                insightType = InsightType.PEAK_HOURS,
                data = mapOf(
                    "peakHours" to peakLabels,
                    "topHour" to "${peaks.first().hour}:00"
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate gold insight")
            InsightReward.error(language)
        }
    }

    /**
     * Generate "trend prediction" insight — Platinum tier.
     * "Mauzo yako yanaongezeka — ongeza stock wiki ijayo!"
     */
    private suspend fun generatePlatinumInsight(language: String): InsightReward {
        return try {
            val trend = businessPatternTracker?.detectWeeklyTrend()
            if (trend == null || trend.direction == com.msaidizi.app.agent.Trend.INSUFFICIENT_DATA) {
                return InsightReward(
                    tier = InsightLevel.PLATINUM,
                    title = if (language == "sw") "📈 Mwelekeo wa Mauzo" else "📈 Sales Trend",
                    message = if (language == "sw") {
                        "Rekodi mauzo kwa wiki 3 zaidi na utapata utabiri wa mauzo!"
                    } else {
                        "Record sales for 3 more weeks to get sales predictions!"
                    },
                    actionable = true,
                    insightType = InsightType.TREND
                )
            }

            val direction = when (trend.direction) {
                com.msaidizi.app.agent.Trend.RISING -> if (language == "sw") "inaongezeka" else "rising"
                com.msaidizi.app.agent.Trend.FALLING -> if (language == "sw") "inapungua" else "falling"
                else -> if (language == "sw") "imara" else "stable"
            }

            val changePercent = kotlin.math.abs(trend.changePercent).toInt()

            val message = if (language == "sw") {
                "📈 Mauzo yako $direction ($changePercent% wiki hii)\n\n" +
                    when (trend.direction) {
                        com.msaidizi.app.agent.Trend.RISING ->
                            "📋 **Jinsi ya kutumia hii:**\n" +
                            "• Ongeza stock — mauzo yanaongezeka!\n" +
                            "• Fungua masaa zaidi wiki ijayo\n" +
                            "• Fikiria kupanua bidhaa zako"
                        com.msaidizi.app.agent.Trend.FALLING ->
                            "📋 **Jinsi ya kutumia hii:**\n" +
                            "• Angalia bei zako — zinaweza kuwa juu\n" +
                            "• Jaribu promotion ndogo\n" +
                            "• Ongeza bidhaa mpya kuvutia wateja"
                        else ->
                            "📋 **Jinsi ya kutumia hii:**\n" +
                            "• Biashara yako ni imara — nzuri!\n" +
                            "• Jaribu kitu kipya kukuza mauzo\n" +
                            "• Angalia bidhaa zisizouza"
                    }
            } else {
                "📈 Your sales are $direction ($changePercent% this week)\n\n" +
                    when (trend.direction) {
                        com.msaidizi.app.agent.Trend.RISING ->
                            "📋 **How to use this:**\n" +
                            "• Stock up — sales are increasing!\n" +
                            "• Open more hours next week\n" +
                            "• Consider expanding your products"
                        com.msaidizi.app.agent.Trend.FALLING ->
                            "📋 **How to use this:**\n" +
                            "• Check your prices — they might be too high\n" +
                            "• Try a small promotion\n" +
                            "• Add new products to attract customers"
                        else ->
                            "📋 **How to use this:**\n" +
                            "• Your business is stable — good!\n" +
                            "• Try something new to grow sales\n" +
                            "• Look at slow-moving products"
                    }
            }

            InsightReward(
                tier = InsightLevel.PLATINUM,
                title = if (language == "sw") "📈 Mwelekeo wa Mauzo" else "📈 Sales Trend",
                message = message,
                actionable = true,
                insightType = InsightType.TREND,
                data = mapOf(
                    "direction" to trend.direction.name,
                    "changePercent" to trend.changePercent.toString()
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate platinum insight")
            InsightReward.error(language)
        }
    }

    /**
     * Generate "growth strategy" insight — Diamond tier.
     * Comprehensive business analysis with strategic recommendations.
     */
    private suspend fun generateDiamondInsight(language: String): InsightReward {
        return try {
            val healthScore = businessPatternTracker?.calculateBusinessHealthScore()
            val margins = businessPatternTracker?.analyzeProfitMargins(30)

            if (healthScore == null) {
                return InsightReward(
                    tier = InsightLevel.DIAMOND,
                    title = if (language == "sw") "🔬 Mkakati wa Ukuaji" else "🔬 Growth Strategy",
                    message = if (language == "sw") {
                        "Endelea kurekodi na utapata mkakati wa kukuza biashara yako!"
                    } else {
                        "Keep recording to get a growth strategy for your business!"
                    },
                    actionable = true,
                    insightType = InsightType.GROWTH_STRATEGY
                )
            }

            val score = healthScore.totalScore.toInt()
            val topMargin = margins?.firstOrNull()

            val message = if (language == "sw") {
                "🔬 **Afya ya Biashara Yako: $score/100**\n\n" +
                    "📊 Scores:\n" +
                    "• Faida: ${healthScore.marginScore.toInt()}/30\n" +
                    "• Mwelekeo: ${healthScore.trendScore.toInt()}/20\n" +
                    "• Mzunguko wa pesa: ${healthScore.cashFlowScore.toInt()}/20\n" +
                    "• Bidhaa tofauti: ${healthScore.diversityScore.toInt()}/15\n" +
                    "• Usimamizi wa stock: ${healthScore.inventoryScore.toInt()}/15\n\n" +
                    if (topMargin != null) {
                        "💡 **Bidhaa yenye faida zaidi:** '${topMargin.item}' (${topMargin.marginPercent.toInt()}% margin)\n\n"
                    } else { "" } +
                    "📋 **Mkakati wako:**\n" +
                    when {
                        score >= 80 -> "• Biashara yako ni bora! Fikiria kupanua.\n• Ongeza bidhaa mpya.\n• Fungua duka la pili."
                        score >= 60 -> "• Biashara yako ni nzuri. Angalia faida.\n• Punguza gharama za bidhaa zenye margin ndogo.\n• Ongeza mauzo ya bidhaa zenye faida kubwa."
                        score >= 40 -> "• Biashara yako inahitaji maboresho.\n• Angalia bei zako — zinaweza kuwa chini sana.\n• Punguza bidhaa zisizouza."
                        else -> "• Anza na msingi: rekodi kila siku.\n• Angalia bidhaa gani inauza zaidi.\n• Weka bei sahihi."
                    }
            } else {
                "🔬 **Business Health: $score/100**\n\n" +
                    "📊 Scores:\n" +
                    "• Profit: ${healthScore.marginScore.toInt()}/30\n" +
                    "• Trend: ${healthScore.trendScore.toInt()}/20\n" +
                    "• Cash flow: ${healthScore.cashFlowScore.toInt()}/20\n" +
                    "• Product diversity: ${healthScore.diversityScore.toInt()}/15\n" +
                    "• Inventory: ${healthScore.inventoryScore.toInt()}/15\n\n" +
                    if (topMargin != null) {
                        "💡 **Most profitable product:** '${topMargin.item}' (${topMargin.marginPercent.toInt()}% margin)\n\n"
                    } else { "" } +
                    "📋 **Your strategy:**\n" +
                    when {
                        score >= 80 -> "• Your business is excellent! Consider expanding.\n• Add new products.\n• Open a second location."
                        score >= 60 -> "• Good business. Focus on profit.\n• Reduce costs on low-margin items.\n• Increase sales of high-profit products."
                        score >= 40 -> "• Needs improvement. Check your prices.\n• Reduce slow-moving stock.\n• Focus on what sells."
                        else -> "• Start with basics: record daily.\n• Find your best-selling product.\n• Set the right price."
                    }
            }

            InsightReward(
                tier = InsightLevel.DIAMOND,
                title = if (language == "sw") "🔬 Mkakati wa Ukuaji" else "🔬 Growth Strategy",
                message = message,
                actionable = true,
                insightType = InsightType.GROWTH_STRATEGY,
                data = mapOf(
                    "healthScore" to score.toString(),
                    "marginPercent" to healthScore.marginPercent.toString(),
                    "trend" to healthScore.trend.name
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate diamond insight")
            InsightReward.error(language)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun getDayName(dayIndex: Int, language: String): String {
        return if (language == "sw") {
            when (dayIndex) {
                0 -> "Jumatatu"
                1 -> "Jumanne"
                2 -> "Jumatano"
                3 -> "Alhamisi"
                4 -> "Ijumaa"
                5 -> "Jumamosi"
                6 -> "Jumapili"
                else -> "Siku"
            }
        } else {
            when (dayIndex) {
                0 -> "Monday"
                1 -> "Tuesday"
                2 -> "Wednesday"
                3 -> "Thursday"
                4 -> "Friday"
                5 -> "Saturday"
                6 -> "Sunday"
                else -> "Day"
            }
        }
    }

    private fun generateTierMessage(tier: InsightLevel, language: String): String {
        return if (language == "sw") {
            when (tier) {
                InsightLevel.DIAMOND -> "💎💎💎 Diamond! Una insights za juu zaidi za biashara!"
                InsightLevel.PLATINUM -> "🏆🏆 Platinum! Una utabiri wa mauzo!"
                InsightLevel.GOLD -> "🥇 Gold! Una ufahamu wa masaa ya kazi!"
                InsightLevel.SILVER -> "🔥 Silver! Una ufahamu wa bidhaa!"
                InsightLevel.BRONZE -> "🛡️ Bronze! Una kujua siku yako bora!"
                InsightLevel.LOCKED -> "🔒 Fikia siku 7 mfululizo kufungua insights!"
            }
        } else {
            when (tier) {
                InsightLevel.DIAMOND -> "💎💎💎 Diamond! You have the highest business insights!"
                InsightLevel.PLATINUM -> "🏆🏆 Platinum! You have sales predictions!"
                InsightLevel.GOLD -> "🥇 Gold! You understand your peak hours!"
                InsightLevel.SILVER -> "🔥 Silver! You know your top products!"
                InsightLevel.BRONZE -> "🛡️ Bronze! You know your best day!"
                InsightLevel.LOCKED -> "🔒 Reach a 7-day streak to unlock insights!"
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

/**
 * Insight levels — progressive unlock based on streak.
 */
enum class InsightLevel {
    LOCKED,
    BRONZE,      // 7-day streak: best day insight
    SILVER,      // 14-day streak: top product insight
    GOLD,        // 30-day streak: peak hours insight
    PLATINUM,    // 60-day streak: trend prediction
    DIAMOND      // 90-day streak: growth strategy
}

/**
 * Types of business insights.
 */
enum class InsightType {
    BEST_DAY,
    TOP_PRODUCT,
    PEAK_HOURS,
    TREND,
    GROWTH_STRATEGY,
    GENERAL
}

/**
 * A business insight reward.
 */
data class InsightReward(
    val tier: InsightLevel,
    val title: String,
    val message: String,
    val actionable: Boolean,
    val insightType: InsightType,
    val data: Map<String, String> = emptyMap()
) {
    companion object {
        fun error(language: String) = InsightReward(
            tier = InsightLevel.LOCKED,
            title = if (language == "sw") "💡 Ufahamu" else "💡 Insight",
            message = if (language == "sw") {
                "Endelea kurekodi mauzo yako kupata ufahamu wa biashara!"
            } else {
                "Keep recording sales to get business insights!"
            },
            actionable = false,
            insightType = InsightType.GENERAL
        )
    }
}

/**
 * Current insight tier status.
 */
data class InsightTier(
    val currentTier: InsightLevel,
    val streakDays: Int,
    val totalSales: Int,
    val nextTier: TierProgress?,
    val unlockedInsightCount: Int,
    val totalInsightCount: Int,
    val message: String
) {
    companion object {
        fun empty() = InsightTier(
            currentTier = InsightLevel.LOCKED,
            streakDays = 0,
            totalSales = 0,
            nextTier = TierProgress(BRONZE_STREAK, BRONZE_STREAK, "🛡️"),
            unlockedInsightCount = 0,
            totalInsightCount = 5,
            message = ""
        )

        private const val BRONZE_STREAK = 7
    }
}

/**
 * Progress toward the next insight tier.
 */
data class TierProgress(
    val targetStreak: Int,
    val daysRemaining: Int,
    val emoji: String
)
