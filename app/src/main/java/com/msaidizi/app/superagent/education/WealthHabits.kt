package com.msaidizi.app.superagent.education

import timber.log.Timber
import java.time.LocalDate

/**
 * Wealth Habits — tracks 10 daily habits for building wealth mindset.
 *
 * Based on principles from:
 * - "Rich Habits" by Tom Corley (5-year study of wealthy vs poor habits)
 * - "Atomic Habits" by James Clear (habit formation science)
 * - Financial literacy fundamentals for East African informal workers
 *
 * ## The 10 Habits
 * 1. **Record all sales** (Rekodi mauzo yote) — 📝
 * 2. **Check balance** (Angalia salio) — 💰
 * 3. **Save money** (Hifadhi pesa) — 🏦
 * 4. **Give tithe** (Toa zaka) — 🙏
 * 5. **Set goals** (Weka malengo) — 🎯
 * 6. **Review progress** (Angalia maendeleo) — 📊
 * 7. **Learn something** (Jifunze kitu) — 📖
 * 8. **Help someone** (Msaidie mtu) — 🤝
 * 9. **Stay positive** (Kuwa na moyo mzuri) — 😊
 * 10. **Be consistent** (Kuwa na uthabiti) — 🔥
 *
 * ## Scoring
 * Each habit = 10 points. Max daily score = 100.
 * Weekly average displayed for motivation.
 * Consecutive days of 100% = streak bonus.
 *
 * ## Voice Celebration
 * When the worker completes all 10 habits, a voice celebration plays:
 * "Hongera! Umekamilisha tabia zote 10 leo! Wewe ni mfanyabiashara bora!"
 *
 * @param habitsProvider Provides access to habits storage
 */
class WealthHabits(
    private val habitsProvider: HabitsProvider
) {
    companion object {
        private const val TAG = "WealthHabits"

        /** The 10 wealth habits */
        val HABITS = listOf(
            HabitDef("record_sales", "Rekodi mauzo yote", "Record all sales", "📝"),
            HabitDef("check_balance", "Angalia salio", "Check balance", "💰"),
            HabitDef("save_money", "Hifadhi pesa", "Save money", "🏦"),
            HabitDef("give_tithe", "Toa zaka", "Give tithe", "🙏"),
            HabitDef("set_goals", "Weka malengo", "Set goals", "🎯"),
            HabitDef("review_progress", "Angalia maendeleo", "Review progress", "📊"),
            HabitDef("learn_something", "Jifunze kitu", "Learn something", "📖"),
            HabitDef("help_someone", "Msaidie mtu", "Help someone", "🤝"),
            HabitDef("stay_positive", "Kuwa na moyo mzuri", "Stay positive", "😊"),
            HabitDef("be_consistent", "Kuwa na uthabiti", "Be consistent", "🔥")
        )
    }

    /**
     * Get daily habits summary for voice delivery.
     *
     * @param language "sw" or "en"
     * @return HabitsSummary with completion status and voice text
     */
    suspend fun getDailySummary(language: String = "sw"): HabitsSummary {
        val today = LocalDate.now().toString()
        val completedIds = habitsProvider.getCompletedHabitsForDate(today)
        val completedCount = completedIds.size
        val totalCount = HABITS.size
        val score = completedCount * 10

        val voiceSummary = buildVoiceSummary(completedCount, totalCount, score, language)

        return HabitsSummary(
            date = today,
            completedCount = completedCount,
            totalCount = totalCount,
            score = score,
            completedHabitIds = completedIds,
            voiceSummary = voiceSummary
        )
    }

    /**
     * Mark a habit as complete for today.
     *
     * @param habitId The habit to complete
     * @param language "sw" or "en"
     * @return HabitCompletionResult with celebration message
     */
    suspend fun completeHabit(habitId: String, language: String = "sw"): HabitCompletionResult {
        val habit = HABITS.find { it.id == habitId }
            ?: return HabitCompletionResult(
                habitId = habitId,
                message = if (language == "sw") "Tabia haijapatikana" else "Habit not found",
                allCompleted = false
            )

        val today = LocalDate.now().toString()
        val alreadyCompleted = habitsProvider.isHabitCompletedToday(habitId, today)

        if (alreadyCompleted) {
            return HabitCompletionResult(
                habitId = habitId,
                message = if (language == "sw") {
                    "${habit.emoji} ${habit.nameSw} — tayari imekamilika leo!"
                } else {
                    "${habit.emoji} ${habit.nameEn} — already completed today!"
                },
                allCompleted = false
            )
        }

        habitsProvider.markHabitCompleted(habitId, today)

        // Check if all habits are now complete
        val completedIds = habitsProvider.getCompletedHabitsForDate(today)
        val allCompleted = completedIds.size >= HABITS.size

        val message = if (allCompleted) {
            if (language == "sw") {
                "🎉 ${habit.emoji} ${habit.nameSw} imekamilika! " +
                "Hongera! Umekamilisha tabia zote ${HABITS.size} leo! " +
                "Wewe ni mfanyabiashara bora! +15 pointi!"
            } else {
                "🎉 ${habit.emoji} ${habit.nameEn} done! " +
                "Congratulations! You completed all ${HABITS.size} habits today! " +
                "You're an amazing business person! +15 points!"
            }
        } else {
            val remaining = HABITS.size - completedIds.size
            if (language == "sw") {
                "${habit.emoji} ${habit.nameSw} imekamilika! Bado tabia $remaining. Endelea!"
            } else {
                "${habit.emoji} ${habit.nameEn} done! $remaining habits remaining. Keep going!"
            }
        }

        Timber.d(TAG, "Habit completed: %s (all=%b)", habitId, allCompleted)

        return HabitCompletionResult(
            habitId = habitId,
            message = message,
            allCompleted = allCompleted
        )
    }

    /**
     * Get weekly habits trend for progress tracking.
     */
    suspend fun getWeeklyTrend(language: String = "sw"): WeeklyHabitsTrend {
        val today = LocalDate.now()
        val dailyScores = (0L until 7L).map { daysAgo ->
            val date = today.minusDays(daysAgo)
            val completed = habitsProvider.getCompletedHabitsForDate(date.toString())
            DailyHabitScore(date.toString(), completed.size * 10)
        }.reversed()

        val avgScore = if (dailyScores.isNotEmpty()) {
            dailyScores.map { it.score }.average().toInt()
        } else {
            0
        }

        val perfectDays = dailyScores.count { it.score >= 100 }

        return WeeklyHabitsTrend(
            dailyScores = dailyScores,
            averageScore = avgScore,
            perfectDays = perfectDays,
            summary = buildWeeklySummary(avgScore, perfectDays, language)
        )
    }

    /**
     * Auto-complete habits based on worker's activity.
     * Called by the superagent when it detects relevant actions.
     *
     * @param action The action detected (e.g., "sale_recorded", "balance_checked")
     */
    suspend fun autoCompleteFromAction(action: String) {
        val today = LocalDate.now().toString()

        val habitId = when (action) {
            "sale_recorded" -> "record_sales"
            "balance_checked" -> "check_balance"
            "money_saved" -> "save_money"
            "tithe_recorded" -> "give_tithe"
            "goal_set" -> "set_goals"
            "lesson_completed" -> "learn_something"
            else -> return
        }

        if (!habitsProvider.isHabitCompletedToday(habitId, today)) {
            habitsProvider.markHabitCompleted(habitId, today)
            Timber.d(TAG, "Auto-completed habit: %s", habitId)
        }
    }

    // ═══════════════ VOICE BUILDERS ═══════════════

    private fun buildVoiceSummary(
        completedCount: Int,
        totalCount: Int,
        score: Int,
        language: String
    ): String {
        return if (language == "sw") {
            when {
                completedCount == 0 -> "Bado hujakamilisha tabia yoyote leo. Anza sasa! 🌟"
                completedCount == totalCount -> "🎉 Umekamilisha tabia zote $totalCount leo! Hongera! Alama: $score/100"
                completedCount >= totalCount / 2 -> "Vizuri! Umekamilisha tabia $completedCount kati ya $totalCount. Alama: $score/100. Endelea!"
                else -> "Umekamilisha tabia $completedCount kati ya $totalCount. Alama: $score/100. Jaribu zaidi!"
            }
        } else {
            when {
                completedCount == 0 -> "You haven't completed any habits today. Start now! 🌟"
                completedCount == totalCount -> "🎉 All $totalCount habits completed! Score: $score/100"
                completedCount >= totalCount / 2 -> "Great! $completedCount of $totalCount habits done. Score: $score/100. Keep going!"
                else -> "$completedCount of $totalCount habits done. Score: $score/100. Try harder!"
            }
        }
    }

    private fun buildWeeklySummary(avgScore: Int, perfectDays: Int, language: String): String {
        return if (language == "sw") {
            buildString {
                append("Wiki hii: wastani wa alama $avgScore/100")
                if (perfectDays > 0) append(". Siku $perfectDays za ukamilifu! 🌟")
                when {
                    avgScore >= 80 -> append(" Nzuri sana! Endelea hivi!")
                    avgScore >= 50 -> append(" Vizuri. Ongeza juhudi!")
                    else -> append(" Anza na tabia moja kwa siku. Hatua kwa hatua!")
                }
            }
        } else {
            buildString {
                append("This week: average score $avgScore/100")
                if (perfectDays > 0) append(". $perfectDays perfect days! 🌟")
                when {
                    avgScore >= 80 -> append(" Excellent! Keep it up!")
                    avgScore >= 50 -> append(" Good. Push harder!")
                    else -> append(" Start with one habit per day. Step by step!")
                }
            }
        }
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/**
 * Definition of a wealth habit.
 */
data class HabitDef(
    val id: String,
    val nameSw: String,
    val nameEn: String,
    val emoji: String
)

/**
 * Daily habits summary.
 */
data class HabitsSummary(
    val date: String,
    val completedCount: Int,
    val totalCount: Int,
    val score: Int,
    val completedHabitIds: Set<String>,
    val voiceSummary: String
)

/**
 * Result of completing a habit.
 */
data class HabitCompletionResult(
    val habitId: String,
    val message: String,
    val allCompleted: Boolean
)

/**
 * Daily habit score for trend tracking.
 */
data class DailyHabitScore(
    val date: String,
    val score: Int
)

/**
 * Weekly habits trend.
 */
data class WeeklyHabitsTrend(
    val dailyScores: List<DailyHabitScore>,
    val averageScore: Int,
    val perfectDays: Int,
    val summary: String
)

/**
 * Interface for habits storage — implemented by the database layer.
 */
interface HabitsProvider {
    suspend fun getCompletedHabitsForDate(date: String): Set<String>
    suspend fun isHabitCompletedToday(habitId: String, date: String): Boolean
    suspend fun markHabitCompleted(habitId: String, date: String)
}
