package com.msaidizi.app.superagent.gamification

import timber.log.Timber

/**
 * Level Engine — 6 progression levels from Mwanafunzi to Legend.
 *
 * ## Level Progression
 * 0. **Mwanafunzi** (Student) — 0-99 pts — 📚
 * 1. **Mfanyabiashara** (Business Person) — 100-299 pts — 🏪
 * 2. **Mjasiriamali** (Entrepreneur) — 300-599 pts — 🚀
 * 3. **Bingwa** (Champion) — 600-999 pts — 🏆
 * 4. **Kiongozi** (Leader) — 1000-1999 pts — 👑
 * 5. **Legend** — 2000+ pts — ⭐
 *
 * ## Design Philosophy
 * Levels are named in Swahili to feel culturally relevant.
 * Each level represents a real business maturity stage:
 * - Mwanafunzi: Just learning to track business
 * - Mfanyabiashara: Regular trader, knows basics
 * - Mjasiriamali: Entrepreneur thinking about growth
 * - Bingwa: Champion with proven track record
 * - Kiongozi: Leader who inspires others
 * - Legend: Master of their craft
 *
 * ## Level-Up Celebrations
 * Each level-up triggers a voice celebration with the worker's name.
 * The celebration includes the new level name and what it means.
 */
class LevelEngine {

    companion object {
        private const val TAG = "LevelEngine"

        /** Points required for each level */
        val LEVEL_THRESHOLDS = intArrayOf(0, 100, 300, 600, 1000, 2000)

        /** Level names in Swahili */
        val LEVEL_NAMES_SW = arrayOf(
            "Mwanafunzi", "Mfanyabiashara", "Mjasiriamali",
            "Bingwa", "Kiongozi", "Legend"
        )

        /** Level names in English */
        val LEVEL_NAMES_EN = arrayOf(
            "Student", "Business Person", "Entrepreneur",
            "Champion", "Leader", "Legend"
        )

        /** Level emojis */
        val LEVEL_EMOJIS = arrayOf("📚", "🏪", "🚀", "🏆", "👑", "⭐")

        /** Level descriptions in Swahili */
        val LEVEL_DESCRIPTIONS_SW = arrayOf(
            "Unajifunza kufuatilia biashara yako",
            "Unafanya biashara kila siku",
            "Unafikiri kuhusu kukua",
            "Una rekodi nzuri ya biashara",
            "Unaoongoza wengine",
            "Umebisha — wewe ni mfano!"
        )

        /** Level descriptions in English */
        val LEVEL_DESCRIPTIONS_EN = arrayOf(
            "Learning to track your business",
            "Trading every day",
            "Thinking about growth",
            "Proven business track record",
            "Leading others",
            "You've made it — you're an example!"
        )
    }

    /**
     * Calculate level from total points.
     *
     * @param totalPoints The worker's total points
     * @return Level index (0-5)
     */
    fun calculateLevel(totalPoints: Int): Int {
        for (i in LEVEL_THRESHOLDS.indices.reversed()) {
            if (totalPoints >= LEVEL_THRESHOLDS[i]) return i
        }
        return 0
    }

    /**
     * Get level info for the worker.
     *
     * @param totalPoints The worker's total points
     * @param language "sw" or "en"
     * @return LevelInfo with all level details
     */
    fun getLevelInfo(totalPoints: Int, language: String = "sw"): LevelInfo {
        val levelIndex = calculateLevel(totalPoints)
        val nextLevelPoints = if (levelIndex < 5) LEVEL_THRESHOLDS[levelIndex + 1] else -1
        val progress = calculateProgress(levelIndex, totalPoints)

        return LevelInfo(
            levelIndex = levelIndex,
            nameSw = LEVEL_NAMES_SW[levelIndex],
            nameEn = LEVEL_NAMES_EN[levelIndex],
            emoji = LEVEL_EMOJIS[levelIndex],
            description = if (language == "sw") LEVEL_DESCRIPTIONS_SW[levelIndex] else LEVEL_DESCRIPTIONS_EN[levelIndex],
            totalPoints = totalPoints,
            nextLevelPoints = nextLevelPoints,
            progress = progress
        )
    }

    /**
     * Check if a level-up occurred and return celebration message.
     *
     * @param oldPoints Points before the action
     * @param newPoints Points after the action
     * @param language "sw" or "en"
     * @return LevelUpResult with celebration message, or null if no level-up
     */
    fun checkLevelUp(oldPoints: Int, newPoints: Int, language: String = "sw"): LevelUpResult? {
        val oldLevel = calculateLevel(oldPoints)
        val newLevel = calculateLevel(newPoints)

        if (newLevel <= oldLevel) return null

        val levelInfo = getLevelInfo(newPoints, language)
        val celebration = buildCelebration(levelInfo, language)

        Timber.d(TAG, "Level up: %d → %d (%s)", oldLevel, newLevel, levelInfo.nameSw)

        return LevelUpResult(
            oldLevel = oldLevel,
            newLevel = newLevel,
            levelInfo = levelInfo,
            celebration = celebration
        )
    }

    /**
     * Get progress bar text for voice output.
     */
    fun getProgressVoice(totalPoints: Int, language: String = "sw"): String {
        val levelInfo = getLevelInfo(totalPoints, language)

        if (levelInfo.levelIndex >= 5) {
            return if (language == "sw") {
                "⭐ Umebisha Level Legend! Hongera!"
            } else {
                "⭐ You've reached Legend level! Congratulations!"
            }
        }

        val pointsNeeded = levelInfo.nextLevelPoints - totalPoints
        val percent = (levelInfo.progress * 100).toInt()

        return if (language == "sw") {
            "Level ${levelInfo.nameSw} ${levelInfo.emoji}. " +
            "Umefikia $percent%. Unahitaji pointi $pointsNeeded kufika " +
            "Level ${LEVEL_NAMES_SW[levelInfo.levelIndex + 1]}."
        } else {
            "Level ${levelInfo.nameEn} ${levelInfo.emoji}. " +
            "You're at $percent%. You need $points points to reach " +
            "Level ${LEVEL_NAMES_EN[levelInfo.levelIndex + 1]}."
        }
    }

    // ═══════════════ PRIVATE HELPERS ═══════════════

    private fun calculateProgress(levelIndex: Int, totalPoints: Int): Float {
        if (levelIndex >= 5) return 1.0f
        val current = totalPoints - LEVEL_THRESHOLDS[levelIndex]
        val needed = LEVEL_THRESHOLDS[levelIndex + 1] - LEVEL_THRESHOLDS[levelIndex]
        return (current.toFloat() / needed).coerceIn(0f, 1f)
    }

    private fun buildCelebration(levelInfo: LevelInfo, language: String): String {
        return if (language == "sw") {
            when (levelInfo.levelIndex) {
                1 -> "🎉 Hongera! Umefika Level Mfanyabiashara 🏪! Sasa wewe ni mfanyabiashara wa kweli!"
                2 -> "🚀 Umefika Level Mjasiriamali! Biashara yako inakua! Endelea hivi!"
                3 -> "🏆 Bingwa! Umefika Level 3! Wewe ni mfano wa biashara nzuri!"
                4 -> "👑 Kiongozi! Umefika Level 4! Unaongoza wengine kwa mfano wako!"
                5 -> "⭐ LEGEND! Umefika level ya juu! Wewe ni mfanyabiashara bora zaidi!"
                else -> "🎉 Umepanda level! Endelea hivi!"
            }
        } else {
            when (levelInfo.levelIndex) {
                1 -> "🎉 Congratulations! You reached Business Person level 🏪! You're a real trader now!"
                2 -> "🚀 Entrepreneur level! Your business is growing! Keep going!"
                3 -> "🏆 Champion! Level 3! You're a model of good business!"
                4 -> "👑 Leader! Level 4! You lead by example!"
                5 -> "⭐ LEGEND! You've reached the top! You're the best business person!"
                else -> "🎉 Level up! Keep going!"
            }
        }
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/**
 * Level information.
 */
data class LevelInfo(
    val levelIndex: Int,
    val nameSw: String,
    val nameEn: String,
    val emoji: String,
    val description: String,
    val totalPoints: Int,
    val nextLevelPoints: Int,
    val progress: Float
)

/**
 * Level-up result with celebration.
 */
data class LevelUpResult(
    val oldLevel: Int,
    val newLevel: Int,
    val levelInfo: LevelInfo,
    val celebration: String
)
