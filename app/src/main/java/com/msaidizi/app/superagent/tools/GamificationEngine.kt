package com.msaidizi.app.superagent.tools

import com.msaidizi.app.core.database.KnowledgeDao
import com.msaidizi.app.core.database.UserProfileDao
import com.msaidizi.app.model.KnowledgeEntity
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GamificationEngine — Points, levels, streaks, badges.
 *
 * Motivates consistent business tracking through gamification:
 * - Points for recording transactions, following advice
 * - Levels based on cumulative points
 * - Daily streak tracking
 * - Achievement badges
 */
@Singleton
class GamificationEngine @Inject constructor(
    private val knowledgeDao: KnowledgeDao,
    private val gson: Gson
) : Tool {

    override val name = "gamification"
    override val description = "Points, levels, streaks, and badges for business tracking"

    // Points configuration
    private val pointsTable = mapOf(
        "record_sale" to 10,
        "record_expense" to 5,
        "record_purchase" to 8,
        "check_stock" to 3,
        "follow_advice" to 15,
        "daily_report" to 10,
        "voice_input" to 5,
        "goal_update" to 10,
        "first_sale_of_day" to 20
    )

    // Level thresholds
    private val levelThresholds = listOf(
        0 to "Mtu Mzima",         // Beginner
        100 to "Mjasiriamali",     // Entrepreneur
        300 to "Biashara Ndogo",   // Small Business
        600 to "Biashara Kuu",     // Big Business
        1000 to "Mfanyabiashara",  // Business Pro
        2000 to "Mwenyekiti",      // Chairman
        5000 to "Mfalme wa Biashara" // Business King
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "status"
        return when (action.lowercase()) {
            "add" -> addPoints(params)
            "status" -> getStatus()
            "streak" -> updateStreak()
            "badge" -> checkBadges()
            "leaderboard" -> getLeaderboard()
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    /**
     * Add points for an action.
     */
    suspend fun addPoints(params: Map<String, String>): ToolResult {
        return try {
            val actionType = params["action_type"]
                ?: return ToolResult.error(name, "Action type required", "MISSING_ACTION_TYPE")

            val basePoints = pointsTable[actionType] ?: 5
            val multiplier = getStreakMultiplier()
            val earnedPoints = (basePoints * multiplier).toInt()

            // Load current gamification state
            val state = loadGamificationState()
            val newTotalPoints = state.totalPoints + earnedPoints
            val newLevel = calculateLevel(newTotalPoints)
            val leveledUp = newLevel != state.level

            // Update state
            val updatedState = state.copy(
                totalPoints = newTotalPoints,
                level = newLevel,
                lastActionAt = System.currentTimeMillis(),
                actionCounts = state.actionCounts.toMutableMap().apply {
                    put(actionType, (get(actionType) ?: 0) + 1)
                }
            )
            saveGamificationState(updatedState)

            // Check for new badges
            val newBadges = checkForNewBadges(updatedState, state.badges)

            // Build response
            val message = buildString {
                appendLine("+$earnedPoints points! (${basePoints} × ${"%.1f".format(multiplier)} streak bonus)")
                appendLine("Total: $newTotalPoints points")
                if (leveledUp) {
                    appendLine("🎉 LEVEL UP! You are now: $newLevel")
                }
                if (newBadges.isNotEmpty()) {
                    newBadges.forEach { appendLine("🏆 Badge unlocked: $it") }
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "points_earned" to earnedPoints,
                    "total_points" to newTotalPoints,
                    "level" to newLevel,
                    "leveled_up" to leveledUp,
                    "new_badges" to newBadges
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to add points")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * Get current gamification status.
     */
    suspend fun getStatus(): ToolResult {
        return try {
            val state = loadGamificationState()
            val nextLevel = levelThresholds.firstOrNull { it.first > state.totalPoints }
            val pointsToNext = nextLevel?.let { it.first - state.totalPoints } ?: 0

            val status = buildString {
                appendLine("🎮 *Your Progress*")
                appendLine()
                appendLine("⭐ Points: ${state.totalPoints}")
                appendLine("📊 Level: ${state.level}")
                appendLine("🔥 Streak: ${state.streakDays} days")
                if (pointsToNext > 0) {
                    appendLine("🎯 Next level: ${pointsToNext} points to go")
                }
                if (state.badges.isNotEmpty()) {
                    appendLine()
                    appendLine("🏆 Badges: ${state.badges.joinToString(", ")}")
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "points" to state.totalPoints,
                    "level" to state.level,
                    "streak" to state.streakDays,
                    "badges" to state.badges
                ),
                message = status
            )
        } catch (e: Exception) {
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * Update daily streak.
     */
    suspend fun updateStreak(): ToolResult {
        return try {
            val state = loadGamificationState()
            val today = getDayOfYear()
            val lastDay = state.lastActiveDay

            val newStreak = when {
                lastDay == today -> state.streakDays // Same day, no change
                lastDay == today - 1 -> state.streakDays + 1 // Consecutive day
                lastDay == 0 -> 1 // First ever
                else -> 1 // Streak broken
            }

            val updatedState = state.copy(
                streakDays = newStreak,
                lastActiveDay = today,
                longestStreak = maxOf(state.longestStreak, newStreak)
            )
            saveGamificationState(updatedState)

            val message = when {
                newStreak > state.streakDays && newStreak > 1 -> "🔥 ${newStreak}-day streak! Keep it up!"
                newStreak == 1 && state.streakDays > 1 -> "Streak reset. Start a new one today! 💪"
                else -> "Day $newStreak of tracking. 🔥"
            }

            ToolResult.success(
                toolName = name,
                data = mapOf("streak" to newStreak, "longest" to updatedState.longestStreak),
                message = message
            )
        } catch (e: Exception) {
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * Check and award badges.
     */
    suspend fun checkBadges(): ToolResult {
        return try {
            val state = loadGamificationState()
            val newBadges = checkForNewBadges(state, state.badges)

            if (newBadges.isNotEmpty()) {
                val updatedState = state.copy(badges = state.badges + newBadges)
                saveGamificationState(updatedState)
                ToolResult.success(
                    toolName = name,
                    data = mapOf("new_badges" to newBadges, "all_badges" to updatedState.badges),
                    message = newBadges.joinToString("\n") { "🏆 New badge: $it" }
                )
            } else {
                ToolResult.success(
                    name,
                    data = mapOf("badges" to state.badges),
                    message = if (state.badges.isEmpty()) "No badges yet. Keep tracking!" else "Badges: ${state.badges.joinToString(", ")}"
                )
            }
        } catch (e: Exception) {
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * Get a motivational leaderboard (single-user comparison to goals).
     */
    suspend fun getLeaderboard(): ToolResult {
        val state = loadGamificationState()
        val message = buildString {
            appendLine("📊 *Weekly Progress*")
            appendLine("Total transactions: ${state.actionCounts.values.sum()}")
            state.actionCounts.forEach { (action, count) ->
                appendLine("  • $action: $count times")
            }
        }
        return ToolResult.success(name, message = message)
    }

    // ── Private helpers ──────────────────────

    private fun getStreakMultiplier(): Double {
        // Streak multiplier: 1.0 base, +0.1 per streak day, max 2.0
        return try {
            val state = loadGamificationStateSync()
            (1.0 + state.streakDays * 0.1).coerceAtMost(2.0)
        } catch (e: Exception) {
            1.0
        }
    }

    private fun getDayOfYear(): Int {
        return Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    }

    private fun calculateLevel(points: Int): String {
        return levelThresholds.lastOrNull { points >= it.first }?.second ?: "Mtu Mzima"
    }

    private suspend fun checkForNewBadges(state: GamificationState, currentBadges: List<String>): List<String> {
        val newBadges = mutableListOf<String>()

        if (state.totalPoints >= 100 && "Rookie" !in currentBadges) newBadges.add("Rookie")
        if (state.totalPoints >= 500 && "Veteran" !in currentBadges) newBadges.add("Veteran")
        if (state.totalPoints >= 1000 && "Expert" !in currentBadges) newBadges.add("Expert")
        if (state.streakDays >= 7 && "Week Warrior" !in currentBadges) newBadges.add("Week Warrior")
        if (state.streakDays >= 30 && "Monthly Master" !in currentBadges) newBadges.add("Monthly Master")
        if ((state.actionCounts["record_sale"] ?: 0) >= 100 && "Sales Star" !in currentBadges) newBadges.add("Sales Star")
        if ((state.actionCounts["record_expense"] ?: 0) >= 50 && "Expense Tracker" !in currentBadges) newBadges.add("Expense Tracker")

        return newBadges
    }

    private suspend fun loadGamificationState(): GamificationState {
        val entry = knowledgeDao.getEntry("gamification", "state")
        return if (entry != null) {
            try {
                gson.fromJson(entry.value, GamificationState::class.java)
            } catch (e: Exception) {
                GamificationState()
            }
        } else {
            GamificationState()
        }
    }

    private fun loadGamificationStateSync(): GamificationState {
        // Synchronous version for multiplier calculation
        return GamificationState() // Fallback — in practice, cache this
    }

    private suspend fun saveGamificationState(state: GamificationState) {
        knowledgeDao.insert(
            KnowledgeEntity(
                category = "gamification",
                key = "state",
                value = gson.toJson(state),
                confidence = 1.0f
            )
        )
    }
}

data class GamificationState(
    val totalPoints: Int = 0,
    val level: String = "Mtu Mzima",
    val streakDays: Int = 0,
    val longestStreak: Int = 0,
    val lastActiveDay: Int = 0,
    val lastActionAt: Long = 0,
    val badges: List<String> = emptyList(),
    val actionCounts: Map<String, Int> = emptyMap()
)
