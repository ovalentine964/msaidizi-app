package com.msaidizi.app.mindset

import com.msaidizi.app.core.database.RichHabitsDao
import com.msaidizi.app.core.model.RichHabitEntry
import timber.log.Timber
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Rich Habits Score — tracks 10 daily habits for building wealth mindset.
 *
 * Based on principles from:
 * - "Rich Habits" by Tom Corley (5-year study of wealthy vs poor habits)
 * - "Atomic Habits" by James Clear (habit formation science)
 * - Financial literacy fundamentals for East African informal workers
 *
 * 10 Habits tracked daily:
 * 1. Record all sales (Rekodi mauzo yote)
 * 2. Check balance (Angalia salio)
 * 3. Save money (Hifadhi pesa)
 * 4. Give tithe (Toa zaka)
 * 5. Set goals (Weka malengo)
 * 6. Review progress (Angalia maendeleo)
 * 7. Learn something (Jifunze kitu)
 * 8. Help someone (Msaidie mtu)
 * 9. Stay positive (Kuwa na moyo mzuri)
 * 10. Be consistent (Kuwa na uthabiti)
 *
 * Score: 0-100 daily (each habit = 10 points).
 * Peer comparison: anonymized average.
 * Voice celebration on improvements.
 *
 * @param habitsDao Room DAO for habit persistence
 */
class RichHabitsScore(
    private val habitsDao: RichHabitsDao
) {
    companion object {
        private const val TAG = "RichHabitsScore"
        private val DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE

        /** The 10 rich habits */
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

    // ═══════════════════════════════════════════════════════════════
    // CORE OPERATIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Mark a habit as completed for today.
     *
     * @param habitId The habit identifier
     * @param language Preferred language
     * @return HabitCompletion with celebration message if applicable
     */
    suspend fun completeHabit(habitId: String, language: String = "sw"): HabitCompletion {
        val today = LocalDate.now().format(DATE_FMT)
        val existing = habitsDao.getEntry(today, habitId)

        if (existing?.completed == true) {
            return HabitCompletion(
                habitId = habitId,
                alreadyCompleted = true,
                message = if (language == "sw") "Tayari umekamilisha!" else "Already done!",
                dailyScore = getTodayScore(),
                allCompleted = false
            )
        }

        habitsDao.upsert(
            RichHabitEntry(
                habitId = habitId,
                date = today,
                completed = true,
                completedAt = System.currentTimeMillis() / 1000
            )
        )

        val completedCount = habitsDao.getCompletedCountForDate(today)
        val allCompleted = completedCount >= HABITS.size
        val dailyScore = completedCount * 10

        val habitName = HABITS.find { it.id == habitId }
        val message = buildString {
            if (language == "sw") {
                append("${habitName?.emojiSw ?: "✅"} ${habitName?.nameSw ?: habitId} — imekamilisha!")
                if (allCompleted) {
                    append("\n\n🎉 Hongera! Umekamilisha tabia zote 10 leo! Score: $dailyScore/100")
                } else if (completedCount == 5) {
                    append("\n\n💪 Nusu imekamilisha! Endelea hivi!")
                }
            } else {
                append("${habitName?.emojiSw ?: "✅"} ${habitName?.nameEn ?: habitId} — done!")
                if (allCompleted) {
                    append("\n\n🎉 Congratulations! All 10 habits completed today! Score: $dailyScore/100")
                } else if (completedCount == 5) {
                    append("\n\n💪 Halfway there! Keep going!")
                }
            }
        }

        return HabitCompletion(
            habitId = habitId,
            alreadyCompleted = false,
            message = message,
            dailyScore = dailyScore,
            allCompleted = allCompleted
        )
    }

    /**
     * Get today's score (0-100).
     */
    suspend fun getTodayScore(): Int {
        val today = LocalDate.now().format(DATE_FMT)
        return habitsDao.getCompletedCountForDate(today) * 10
    }

    /**
     * Get today's habit status — which are completed and which aren't.
     */
    suspend fun getTodayHabits(): List<HabitStatus> {
        val today = LocalDate.now().format(DATE_FMT)
        val entries = habitsDao.getEntriesForDate(today)
        val completedIds = entries.filter { it.completed }.map { it.habitId }.toSet()

        return HABITS.map { habit ->
            HabitStatus(
                habit = habit,
                completed = habit.id in completedIds
            )
        }
    }

    /**
     * Get the weekly average score.
     */
    suspend fun getWeeklyAverage(): Double {
        val sinceDate = LocalDate.now().minusDays(7).format(DATE_FMT)
        val avg = habitsDao.getAverageCompletedSince(sinceDate)
        return (avg ?: 0.0) * 10 // Convert from count to 0-100 score
    }

    /**
     * Get comparison with anonymized peer average.
     * Returns a message comparing user's score to the peer average.
     */
    suspend fun getPeerComparison(language: String = "sw"): String {
        val myScore = getTodayScore()
        // Simulated peer average (in production, this comes from server)
        val peerAverage = 45

        return if (language == "sw") {
            when {
                myScore > peerAverage + 20 -> "🌟 Wewe ni bora kuliko wengi! Score yako: $myScore, wastani wa wengine: $peerAverage"
                myScore > peerAverage -> "👍 Wewe ni juu ya wastani! Score: $myScore vs $peerAverage"
                myScore == peerAverage -> "➡️ Uko sawa na wastani. Score: $myScore"
                myScore > 0 -> "💪 Endelea! Score yako: $myScore, wastani: $peerAverage"
                else -> "📝 Anza leo! Rekodi mauzo yako ya kwanza."
            }
        } else {
            when {
                myScore > peerAverage + 20 -> "🌟 You're above average! Score: $myScore, peer avg: $peerAverage"
                myScore > peerAverage -> "👍 Above average! Score: $myScore vs $peerAverage"
                myScore == peerAverage -> "➡️ On par with average. Score: $myScore"
                myScore > 0 -> "💪 Keep going! Score: $myScore, avg: $peerAverage"
                else -> "📝 Start today! Record your first sale."
            }
        }
    }

    /**
     * Get celebration message for score improvements.
     * Compares today with yesterday.
     */
    suspend fun getImprovementCelebration(language: String = "sw"): String? {
        val today = LocalDate.now().format(DATE_FMT)
        val yesterday = LocalDate.now().minusDays(1).format(DATE_FMT)

        val todayScore = habitsDao.getCompletedCountForDate(today) * 10
        val yesterdayScore = habitsDao.getCompletedCountForDate(yesterday) * 10

        if (todayScore <= yesterdayScore || yesterdayScore == 0) return null

        val improvement = todayScore - yesterdayScore

        return if (language == "sw") {
            "🎉 Umepanda kwa pointi $improvement! Jana: $yesterdayScore, Leo: $todayScore. Nzuri sana!"
        } else {
            "🎉 Up $improvement points! Yesterday: $yesterdayScore, Today: $todayScore. Great job!"
        }
    }

    /**
     * Get detailed score breakdown for a specific date.
     */
    suspend fun getScoreBreakdown(date: LocalDate = LocalDate.now()): ScoreBreakdown {
        val dateStr = date.format(DATE_FMT)
        val entries = habitsDao.getEntriesForDate(dateStr)
        val completedMap = entries.filter { it.completed }.associateBy { it.habitId }

        val habitResults = HABITS.map { habit ->
            val entry = completedMap[habit.id]
            HabitResult(
                habit = habit,
                completed = entry != null,
                completedAt = entry?.completedAt ?: 0
            )
        }

        val completedCount = habitResults.count { it.completed }

        return ScoreBreakdown(
            date = dateStr,
            score = completedCount * 10,
            maxScore = 100,
            habits = habitResults,
            completedCount = completedCount,
            totalHabits = HABITS.size
        )
    }

    /**
     * Auto-complete habits based on user actions.
     * Called by the Orchestrator when user performs relevant actions.
     *
     * @param action The action performed: "sale", "balance_check", "lesson", "giving"
     * @return List of newly completed habits
     */
    suspend fun autoCompleteFromAction(action: String, language: String = "sw"): List<HabitCompletion> {
        val results = mutableListOf<HabitCompletion>()

        when (action) {
            "sale" -> {
                results.add(completeHabit("record_sales", language))
                results.add(completeHabit("be_consistent", language))
            }
            "balance_check" -> {
                results.add(completeHabit("check_balance", language))
                results.add(completeHabit("review_progress", language))
            }
            "lesson" -> {
                results.add(completeHabit("learn_something", language))
            }
            "giving" -> {
                results.add(completeHabit("give_tithe", language))
            }
            "goal_set" -> {
                results.add(completeHabit("set_goals", language))
            }
            "save" -> {
                results.add(completeHabit("save_money", language))
            }
        }

        return results.filter { !it.alreadyCompleted }
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

data class HabitDef(
    val id: String,
    val nameSw: String,
    val nameEn: String,
    val emojiSw: String
)

data class HabitStatus(
    val habit: HabitDef,
    val completed: Boolean
)

data class HabitCompletion(
    val habitId: String,
    val alreadyCompleted: Boolean,
    val message: String,
    val dailyScore: Int,
    val allCompleted: Boolean
)

data class ScoreBreakdown(
    val date: String,
    val score: Int,
    val maxScore: Int,
    val habits: List<HabitResult>,
    val completedCount: Int,
    val totalHabits: Int
)

data class HabitResult(
    val habit: HabitDef,
    val completed: Boolean,
    val completedAt: Long
)
