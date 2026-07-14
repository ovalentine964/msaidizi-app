package com.msaidizi.app.gamification

import com.msaidizi.app.core.database.GamificationDao
import com.msaidizi.app.core.model.GamificationEntity
import timber.log.Timber

/**
 * Level Progression Rewards — feature unlocks at each level.
 *
 * At each level (Mwanafunzi → Mfanyabiashara → Mkuu), workers
 * unlock new features that provide increasing business value.
 *
 * Design philosophy:
 * - Each level feels like a genuine upgrade
 * - Features are progressively more valuable
 * - Unlocking features > unlocking cosmetics
 * - Workers should WANT to reach the next level
 *
 * Level progression:
 *   Level 0: Mwanafunzi (Student) — Learn basics
 *   Level 1: Mfanyabiashara (Business Person) — Weekly reports
 *   Level 2: Mjasiriamali (Entrepreneur) — Peer comparison
 *   Level 3: Bingwa (Champion) — Advanced insights
 *   Level 4: Kiongozi (Leader) — Mentorship mode
 *   Level 5: Legend — All features + exclusive access
 *
 * Each unlock triggers a celebration message and shows
 * exactly what the worker can now do.
 *
 * @param gamificationDao Gamification state access
 */
class LevelProgressionRewards(
    private val gamificationDao: GamificationDao
) {
    companion object {
        private const val TAG = "LevelProgression"

        // Level thresholds (matching GamificationEngine)
        private val LEVEL_THRESHOLDS = intArrayOf(0, 100, 300, 600, 1000, 2000)

        // Level names
        private val LEVEL_NAMES_SW = arrayOf(
            "Mwanafunzi", "Mfanyabiashara", "Mjasiriamali",
            "Bingwa", "Kiongozi", "Legend"
        )
        private val LEVEL_NAMES_EN = arrayOf(
            "Student", "Business Person", "Entrepreneur",
            "Champion", "Leader", "Legend"
        )

        // Level emojis
        private val LEVEL_EMOJIS = arrayOf("📚", "🏪", "🚀", "🏆", "👑", "⭐")
    }

    // ═══════════════════════════════════════════════════════════════
    // FEATURE UNLOCK DEFINITIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get all features unlocked at a specific level.
     * Each feature has a name, description, and Swahili/English labels.
     */
    fun getFeaturesForLevel(level: Int): List<UnlockedFeature> {
        return when (level) {
            0 -> listOf(
                UnlockedFeature(
                    id = "basic_recording",
                    nameSw = "Kurekodi Mauzo",
                    nameEn = "Record Sales",
                    descriptionSw = "Jifunze jinsi ya kurekodi mauzo yako ya kwanza. Kila mauzo yanakupa pointi!",
                    descriptionEn = "Learn how to record your first sales. Every sale gives you points!",
                    emoji = "📝",
                    category = FeatureCategory.CORE
                ),
                UnlockedFeature(
                    id = "balance_check",
                    nameSw = "Angalia Salio",
                    nameEn = "Check Balance",
                    descriptionSw = "Angalia salio lako la biashara. Pata pointi kwa kila ukaguzi!",
                    descriptionEn = "Check your business balance. Earn points for each check!",
                    emoji = "💰",
                    category = FeatureCategory.CORE
                ),
                UnlockedFeature(
                    id = "basic_streak",
                    nameSw = "Anza Streak",
                    nameEn = "Start Streak",
                    descriptionSw = "Rekodi mauzo kila siku kujenga mfululizo wako!",
                    descriptionEn = "Record sales daily to build your streak!",
                    emoji = "🔥",
                    category = FeatureCategory.CORE
                )
            )

            1 -> listOf(
                UnlockedFeature(
                    id = "weekly_reports",
                    nameSw = "Ripoti za Wiki",
                    nameEn = "Weekly Reports",
                    descriptionSw = "Pata ripoti ya mauzo yako kila wiki — jinsi gani ulivyoendelea!",
                    descriptionEn = "Get a sales report every week — see how you've progressed!",
                    emoji = "📊",
                    category = FeatureCategory.ANALYTICS
                ),
                UnlockedFeature(
                    id = "business_tips",
                    nameSw = "Vidokezo vya Biashara",
                    nameEn = "Business Tips",
                    descriptionSw = "Pata vidokezo vya biashara vinavyolingana na biashara yako!",
                    descriptionEn = "Get business tips tailored to your business!",
                    emoji = "💡",
                    category = FeatureCategory.INTELLIGENCE
                ),
                UnlockedFeature(
                    id = "streak_2x",
                    nameSw = "Streak ×2 Pointi",
                    nameEn = "Streak ×2 Points",
                    descriptionSw = "Pointi zako za streak sasa ni mara mbili!",
                    descriptionEn = "Your streak points are now doubled!",
                    emoji = "⚡",
                    category = FeatureCategory.GAMIFICATION
                )
            )

            2 -> listOf(
                UnlockedFeature(
                    id = "peer_comparison",
                    nameSw = "Ulinganisho na Wengine",
                    nameEn = "Peer Comparison",
                    descriptionSw = "Linganisha biashara yako na wafanyabiashara wengine (kwa faragha)!",
                    descriptionEn = "Compare your business with other businesses (privately)!",
                    emoji = "👥",
                    category = FeatureCategory.SOCIAL
                ),
                UnlockedFeature(
                    id = "profit_analysis",
                    nameSw = "Uchambuzi wa Faida",
                    nameEn = "Profit Analysis",
                    descriptionSw = "Angalia faida yako kwa kila bidhaa — jua ni ipi inayofaa zaidi!",
                    descriptionEn = "See your profit for each product — know which is most profitable!",
                    emoji = "💰",
                    category = FeatureCategory.ANALYTICS
                ),
                UnlockedFeature(
                    id = "price_suggestions",
                    nameSw = "Mapendekezo ya Bei",
                    nameEn = "Price Suggestions",
                    descriptionSw = "Pata mapendekezo ya bei kutokana na data yako!",
                    descriptionEn = "Get price suggestions based on your data!",
                    emoji = "🏷️",
                    category = FeatureCategory.INTELLIGENCE
                ),
                UnlockedFeature(
                    id = "streak_3x",
                    nameSw = "Streak ×3 Pointi",
                    nameEn = "Streak ×3 Points",
                    descriptionSw = "Pointi zako za streak sasa ni mara tatu!",
                    descriptionEn = "Your streak points are now tripled!",
                    emoji = "⚡⚡",
                    category = FeatureCategory.GAMIFICATION
                )
            )

            3 -> listOf(
                UnlockedFeature(
                    id = "sales_predictions",
                    nameSw = "Utabiri wa Mauzo",
                    nameEn = "Sales Predictions",
                    descriptionSw = "Pata utabiri wa mauzo yako ya wiki ijayo — jipange!",
                    descriptionEn = "Get predictions for next week's sales — plan ahead!",
                    emoji = "🔮",
                    category = FeatureCategory.INTELLIGENCE
                ),
                UnlockedFeature(
                    id = "advanced_insights",
                    nameSw = "Insights za Juu",
                    nameEn = "Advanced Insights",
                    descriptionSw = "Fungua insights za kina kuhusu biashara yako — masaa, siku, bidhaa!",
                    descriptionEn = "Unlock deep insights about your business — hours, days, products!",
                    emoji = "🔬",
                    category = FeatureCategory.INTELLIGENCE
                ),
                UnlockedFeature(
                    id = "loan_assistance",
                    nameSw = "Msaada wa Mikopo",
                    nameEn = "Loan Assistance",
                    descriptionSw = "Pata msaada wa kupanga na kufuatilia mikopo yako!",
                    descriptionEn = "Get help planning and tracking your loans!",
                    emoji = "🏦",
                    category = FeatureCategory.FINANCIAL
                ),
                UnlockedFeature(
                    id = "streak_5x",
                    nameSw = "Streak ×5 Pointi",
                    nameEn = "Streak ×5 Points",
                    descriptionSw = "Pointi zako za streak sasa ni mara tano! Hatua kubwa!",
                    descriptionEn = "Your streak points are now 5x! Big step!",
                    emoji = "⚡⚡⚡",
                    category = FeatureCategory.GAMIFICATION
                )
            )

            4 -> listOf(
                UnlockedFeature(
                    id = "mentorship_mode",
                    nameSw = "Mentor wa Biashara",
                    nameEn = "Business Mentor",
                    descriptionSw = "Msaidizi sasa ni mentor wako — anaongea na wewe kama mwalimu!",
                    descriptionEn = "Msaidizi is now your mentor — talks to you like a teacher!",
                    emoji = "🎓",
                    category = FeatureCategory.EXCLUSIVE
                ),
                UnlockedFeature(
                    id = "growth_strategy",
                    nameSw = "Mkakati wa Ukuaji",
                    nameEn = "Growth Strategy",
                    descriptionSw = "Pata mkakati wa kukuza biashara yako — hatua kwa hatua!",
                    descriptionEn = "Get a growth strategy for your business — step by step!",
                    emoji = "📈",
                    category = FeatureCategory.INTELLIGENCE
                ),
                UnlockedFeature(
                    id = "business_health",
                    nameSw = "Afya ya Biashara",
                    nameEn = "Business Health",
                    descriptionSw = "Angalia afya ya biashara yako kwa alama 0-100!",
                    descriptionEn = "See your business health score 0-100!",
                    emoji = "❤️",
                    category = FeatureCategory.ANALYTICS
                )
            )

            5 -> listOf(
                UnlockedFeature(
                    id = "legend_status",
                    nameSw = "⭐ Legend Status",
                    nameEn = "⭐ Legend Status",
                    descriptionSw = "Wewe ni Legend! Vipengele vyote vimefunguliwa!",
                    descriptionEn = "You're a Legend! All features unlocked!",
                    emoji = "⭐",
                    category = FeatureCategory.EXCLUSIVE
                ),
                UnlockedFeature(
                    id = "exclusive_assistant",
                    nameSw = "Msaidizi wa Kipekee",
                    nameEn = "Exclusive Assistant",
                    descriptionSw = "Msaidizi wako anajifunza kutoka kwako — anaelewa biashara yako vizuri zaidi!",
                    descriptionEn = "Your assistant learns from you — understands your business best!",
                    emoji = "🤖",
                    category = FeatureCategory.EXCLUSIVE
                ),
                UnlockedFeature(
                    id = "priority_insights",
                    nameSw = "Kipaumbele cha Insights",
                    nameEn = "Priority Insights",
                    descriptionSw = "Pata insights mpya kabla ya wengine — kipaumbele!",
                    descriptionEn = "Get new insights before others — priority access!",
                    emoji = "🚀",
                    category = FeatureCategory.EXCLUSIVE
                )
            )

            else -> emptyList()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LEVEL UNLOCK CELEBRATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate a celebration message when a new level is reached.
     * Shows what was just unlocked and what's coming next.
     *
     * @param newLevel The level that was just reached
     * @param language Language preference
     * @return LevelUnlockCelebration with messages and feature list
     */
    suspend fun celebrateLevelUnlock(
        newLevel: Int,
        language: String = "sw"
    ): LevelUnlockCelebration {
        val entity = gamificationDao.getGamification()
            ?: return LevelUnlockCelebration.error(language)

        val features = getFeaturesForLevel(newLevel)
        val levelName = if (language == "sw") LEVEL_NAMES_SW[newLevel] else LEVEL_NAMES_EN[newLevel]
        val emoji = LEVEL_EMOJIS[newLevel]

        val celebration = if (language == "sw") {
            when (newLevel) {
                1 -> "🎉 Hongera! Umefika Level $levelName $emoji!\n\n" +
                    "Sasa wewe ni Mfanyabiashara! Haya ndiyo mapya:"
                2 -> "🚀 Umefika Level $levelName $emoji!\n\n" +
                    "Wewe ni Mjasiriamali sasa! Haya ndiyo mapya:"
                3 -> "🏆 LEVEL BINGWA! $emoji\n\n" +
                    "Umefika Level 3! Haya ndiyo mapya ya nguvu:"
                4 -> "👑 KIONGOZI! $emoji\n\n" +
                    "Wewe ni kiongozi sasa! Haya ndiyo mapya:"
                5 -> "⭐⭐⭐ LEGEND! ⭐⭐⭐\n\n" +
                    "Umefika kilele! Vipengele vyote vimefunguliwa!"
                else -> "🎉 Hongera! Umefika Level $levelName $emoji!"
            }
        } else {
            when (newLevel) {
                1 -> "🎉 Congratulations! You reached Level $levelName $emoji!\n\n" +
                    "You're now a Business Person! Here's what's new:"
                2 -> "🚀 You reached Level $levelName $emoji!\n\n" +
                    "You're an Entrepreneur now! Here's what's new:"
                3 -> "🏆 CHAMPION LEVEL! $emoji\n\n" +
                    "You reached Level 3! Here are powerful new features:"
                4 -> "👑 LEADER! $emoji\n\n" +
                    "You're a leader now! Here's what's new:"
                5 -> "⭐⭐⭐ LEGEND! ⭐⭐⭐\n\n" +
                    "You've reached the top! All features unlocked!"
                else -> "🎉 Congratulations! You reached Level $levelName $emoji!"
            }
        }

        // Build feature list
        val featureList = features.joinToString("\n") { feature ->
            val name = if (language == "sw") feature.nameSw else feature.nameEn
            val desc = if (language == "sw") feature.descriptionSw else feature.descriptionEn
            "${feature.emoji} **$name**\n   $desc"
        }

        // Next level preview
        val nextLevelPreview = if (newLevel < 5) {
            val nextFeatures = getFeaturesForLevel(newLevel + 1).take(2)
            val nextName = if (language == "sw") LEVEL_NAMES_SW[newLevel + 1] else LEVEL_NAMES_EN[newLevel + 1]
            val nextEmoji = LEVEL_EMOJIS[newLevel + 1]
            val nextThreshold = LEVEL_THRESHOLDS[newLevel + 1]

            if (language == "sw") {
                "\n\n🔮 **Level ijayo: $nextName $nextEmoji** ($nextThreshold pointi)\n" +
                    nextFeatures.joinToString("\n") { "   ${it.emoji} ${it.nameSw}" }
            } else {
                "\n\n🔮 **Next level: $nextName $nextEmoji** ($nextThreshold points)\n" +
                    nextFeatures.joinToString("\n") { "   ${it.emoji} ${it.nameEn}" }
            }
        } else ""

        return LevelUnlockCelebration(
            level = newLevel,
            levelName = levelName,
            emoji = emoji,
            celebrationMessage = celebration,
            featureList = featureList,
            nextLevelPreview = nextLevelPreview,
            features = features,
            points = entity.totalPoints
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // FEATURE ACCESS CHECK
    // ═══════════════════════════════════════════════════════════════

    /**
     * Check if a specific feature is unlocked for the current level.
     *
     * @param featureId The feature ID to check
     * @return true if the feature is unlocked
     */
    suspend fun isFeatureUnlocked(featureId: String): Boolean {
        val entity = gamificationDao.getGamification() ?: return false
        val currentLevel = entity.level

        // Check all levels up to current
        for (level in 0..currentLevel) {
            val features = getFeaturesForLevel(level)
            if (features.any { it.id == featureId }) return true
        }
        return false
    }

    /**
     * Get the level at which a feature is unlocked.
     *
     * @param featureId The feature ID
     * @return Level index, or -1 if not found
     */
    fun getFeatureLevel(featureId: String): Int {
        for (level in 0..5) {
            val features = getFeaturesForLevel(level)
            if (features.any { it.id == featureId }) return level
        }
        return -1
    }

    /**
     * Get all features with their unlock status.
     * Used for the features gallery in the gamification screen.
     */
    suspend fun getAllFeaturesWithStatus(language: String = "sw"): List<FeatureStatus> {
        val entity = gamificationDao.getGamification() ?: return emptyList()
        val currentLevel = entity.level

        val allFeatures = mutableListOf<FeatureStatus>()
        for (level in 0..5) {
            val features = getFeaturesForLevel(level)
            val levelName = if (language == "sw") LEVEL_NAMES_SW[level] else LEVEL_NAMES_EN[level]
            val emoji = LEVEL_EMOJIS[level]

            for (feature in features) {
                allFeatures.add(FeatureStatus(
                    feature = feature,
                    level = level,
                    levelName = levelName,
                    levelEmoji = emoji,
                    unlocked = level <= currentLevel,
                    isNewlyUnlocked = level == currentLevel
                ))
            }
        }

        return allFeatures
    }

    /**
     * Get summary of what's unlocked vs locked.
     */
    suspend fun getUnlockSummary(language: String = "sw"): UnlockSummary {
        val entity = gamificationDao.getGamification() ?: return UnlockSummary.empty()
        val currentLevel = entity.level

        var unlocked = 0
        var locked = 0

        for (level in 0..5) {
            val features = getFeaturesForLevel(level)
            for (feature in features) {
                if (level <= currentLevel) unlocked++ else locked++
            }
        }

        return UnlockSummary(
            currentLevel = currentLevel,
            levelName = if (language == "sw") LEVEL_NAMES_SW[currentLevel] else LEVEL_NAMES_EN[currentLevel],
            levelEmoji = LEVEL_EMOJIS[currentLevel],
            unlockedFeatures = unlocked,
            lockedFeatures = locked,
            totalFeatures = unlocked + locked,
            progress = if (currentLevel >= 5) 1.0f else {
                val current = entity.totalPoints - LEVEL_THRESHOLDS[currentLevel]
                val needed = LEVEL_THRESHOLDS[currentLevel + 1] - LEVEL_THRESHOLDS[currentLevel]
                (current.toFloat() / needed).coerceIn(0f, 1f)
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

/**
 * A feature that can be unlocked at a certain level.
 */
data class UnlockedFeature(
    val id: String,
    val nameSw: String,
    val nameEn: String,
    val descriptionSw: String,
    val descriptionEn: String,
    val emoji: String,
    val category: FeatureCategory
)

/**
 * Feature categories for grouping in UI.
 */
enum class FeatureCategory(val nameSw: String, val nameEn: String, val emoji: String) {
    CORE("Msingi", "Core", "📝"),
    ANALYTICS("Uchambuzi", "Analytics", "📊"),
    INTELLIGENCE("Busara", "Intelligence", "💡"),
    SOCIAL("Jamii", "Social", "👥"),
    FINANCIAL("Fedha", "Financial", "💰"),
    GAMIFICATION("Mchezo", "Gamification", "🎮"),
    EXCLUSIVE("Kipekee", "Exclusive", "⭐")
}

/**
 * Celebration when a level is unlocked.
 */
data class LevelUnlockCelebration(
    val level: Int,
    val levelName: String,
    val emoji: String,
    val celebrationMessage: String,
    val featureList: String,
    val nextLevelPreview: String,
    val features: List<UnlockedFeature>,
    val points: Int
) {
    companion object {
        fun error(language: String) = LevelUnlockCelebration(
            level = 0,
            levelName = "",
            emoji = "",
            celebrationMessage = if (language == "sw") "⚠️ Kuna tatizo." else "⚠️ Something went wrong.",
            featureList = "",
            nextLevelPreview = "",
            features = emptyList(),
            points = 0
        )
    }
}

/**
 * Feature with unlock status.
 */
data class FeatureStatus(
    val feature: UnlockedFeature,
    val level: Int,
    val levelName: String,
    val levelEmoji: String,
    val unlocked: Boolean,
    val isNewlyUnlocked: Boolean
)

/**
 * Summary of feature unlocks.
 */
data class UnlockSummary(
    val currentLevel: Int,
    val levelName: String,
    val levelEmoji: String,
    val unlockedFeatures: Int,
    val lockedFeatures: Int,
    val totalFeatures: Int,
    val progress: Float
) {
    companion object {
        fun empty() = UnlockSummary(0, "", "", 0, 0, 0, 0f)
    }
}
