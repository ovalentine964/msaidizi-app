package com.msaidizi.agent.tools.financial

import com.msaidizi.core.database.GoalContributionDao
import com.msaidizi.core.database.GoalDao
import com.msaidizi.core.model.GoalContributionEntity
import com.msaidizi.core.model.GoalEntity
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ════════════════════════════════════════════════════════════
// GOAL TRACKER — Room DB persisted savings goals
// ════════════════════════════════════════════════════════════
// Fixed: Goals now persist across app restarts via Room DB.
// Previously used in-memory mutableListOf<Goal>() which lost
// all goals on app restart.
//
// Features:
// - Create, update, list, delete goals
// - Track contributions over time
// - Progress percentage and daily savings needed
// - Overdue goal detection
// - Total saved across all goals
// ════════════════════════════════════════════════════════════

/**
 * GoalTracker — Track savings goals and business targets.
 *
 * Persists goals to Room DB so they survive app restarts.
 * Each goal tracks target amount, current progress, deadline,
 * and contribution history.
 */
@Singleton
class GoalTracker @Inject constructor(
    private val goalDao: GoalDao,
    private val goalContributionDao: GoalContributionDao
) : Tool {

    override val name = "goal_tracker"
    override val description = "Track savings goals and business targets. " +
            "Goals persist across app restarts. Track progress, get daily savings targets, " +
            "see contribution history. 'Weka akiba mia mbili' → add KES 200 toward goal."

    override val argsSchema = argSchema {
        enum("action", "Action to perform",
            listOf("create", "update", "list", "delete", "status", "progress", "history"), required = false)
        string("name", "Goal name (for create)", required = false)
        string("goal_id", "Goal ID (for update/delete/history)", required = false)
        number("target", "Target amount in KES (for create)", required = false)
        number("amount", "Amount to add toward goal (for update)", required = false)
        string("deadline", "Deadline in days from now (for create)", required = false)
        enum("goal_type", "Type of goal",
            listOf("savings", "business_target", "equipment"), required = false)
        string("notes", "Notes about the goal", required = false)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "list"
        return when (action.lowercase()) {
            "create" -> createGoal(params)
            "update" -> updateGoal(params)
            "list" -> listGoals()
            "delete" -> deleteGoal(params)
            "status" -> goalStatus(params)
            "progress" -> overallProgress()
            "history" -> contributionHistory(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ════════════════════════════════════════════════════════════
    //  CREATE GOAL
    // ════════════════════════════════════════════════════════════

    private suspend fun createGoal(params: Map<String, String>): ToolResult {
        return try {
            val goalName = params["name"]
                ?: return ToolResult.error(name, "Jina la lengo linahitajika", "MISSING_NAME")
            val target = params["target"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "Kiasi cha lengo linahitajika", "MISSING_TARGET")

            if (target <= 0) {
                return ToolResult.error(name, "Kiasi lazima kiwe zaidi ya 0", "INVALID_AMOUNT")
            }

            val deadlineDays = params["deadline"]?.toIntOrNull() ?: 30
            val goalType = params["goal_type"] ?: "savings"
            val notes = params["notes"]

            val goalId = UUID.randomUUID().toString()
            val goal = GoalEntity(
                id = goalId,
                name = goalName,
                targetAmount = target,
                currentAmount = 0.0,
                deadline = System.currentTimeMillis() + deadlineDays.toLong() * 24 * 60 * 60 * 1000,
                status = "active",
                goalType = goalType,
                notes = notes
            )

            goalDao.insert(goal)

            // Calculate daily savings needed
            val dailyNeeded = target / deadlineDays

            val report = buildString {
                appendLine("🎯 Lengo Jipya: $goalName")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("📊 Lengo: KES ${"%,.0f".format(target)}")
                appendLine("📅 Muda: siku $deadlineDays")
                appendLine("💰 Unahitaji: KES ${"%,.0f".format(dailyNeeded)}/siku")
                appendLine()
                appendLine("Anza leo — hata KES 20 inasaidia! 💪")
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "goal_id" to goalId,
                    "name" to goalName,
                    "target" to target,
                    "deadline_days" to deadlineDays,
                    "daily_needed" to dailyNeeded
                ),
                message = report
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to create goal")
            ToolResult.error(name, "Imeshindwa: ${e.message}", "DB_ERROR")
        }
    }

    // ════════════════════════════════════════════════════════════
    //  UPDATE GOAL (add contribution)
    // ════════════════════════════════════════════════════════════

    private suspend fun updateGoal(params: Map<String, String>): ToolResult {
        return try {
            val goalId = params["goal_id"]
                ?: return ToolResult.error(name, "ID ya lengo linahitajika", "MISSING_ID")
            val amount = params["amount"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "Kiasi kinahitajika", "MISSING_AMOUNT")

            if (amount <= 0) {
                return ToolResult.error(name, "Kiasi lazima kiwe zaidi ya 0", "INVALID_AMOUNT")
            }

            val goal = goalDao.getById(goalId)
                ?: return ToolResult.error(name, "Lengo halipatikani", "NOT_FOUND")

            // Add contribution
            goalDao.addContribution(goalId, amount)
            goalContributionDao.insert(
                GoalContributionEntity(
                    goalId = goalId,
                    amount = amount,
                    source = params["source"] ?: "manual",
                    notes = params["notes"]
                )
            )

            // Check if goal completed
            val newAmount = goal.currentAmount + amount
            if (newAmount >= goal.targetAmount) {
                goalDao.updateStatus(goalId, "completed")
            }

            val remaining = (goal.targetAmount - newAmount).coerceAtLeast(0.0)
            val pct = (newAmount / goal.targetAmount * 100).coerceAtMost(100.0)
            val daysLeft = ((goal.deadline - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).coerceAtLeast(1)
            val dailyNeeded = remaining / daysLeft

            val report = buildString {
                appendLine("✅ ${goal.name}: KES ${"%,.0f".format(amount)} imeongezwa!")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("📊 Ilivyokuwa: KES ${"%,.0f".format(goal.currentAmount)} / KES ${"%,.0f".format(goal.targetAmount)}")
                appendLine("📊 Sasa: KES ${"%,.0f".format(newAmount)} / KES ${"%,.0f".format(goal.targetAmount)} (${"%.0f".format(pct)}%)")

                // Progress bar
                val barLength = 20
                val filled = (pct / 100 * barLength).toInt().coerceIn(0, barLength)
                val bar = "█".repeat(filled) + "░".repeat(barLength - filled)
                appendLine("   [$bar] ${"%.0f".format(pct)}%")
                appendLine()

                if (newAmount >= goal.targetAmount) {
                    appendLine("🎉🎉🎉 LENGO LIMEFIKIWA! 🎉🎉🎉")
                    appendLine("Umekuwa na akiba ya KES ${"%,.0f".format(goal.targetAmount)}!")
                    appendLine("Hongera! Uwezo wako wa kuweka akiba ni wa ajabu! 💪")
                } else {
                    appendLine("💰 Imebaki: KES ${"%,.0f".format(remaining)}")
                    appendLine("📅 Siku ${daysLeft} zimebaki")
                    appendLine("💡 Unahitaji: KES ${"%,.0f".format(dailyNeeded)}/siku")
                }
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "goal" to goal.name,
                    "added" to amount,
                    "total" to newAmount,
                    "remaining" to remaining,
                    "percent" to pct,
                    "completed" to (newAmount >= goal.targetAmount)
                ),
                message = report
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to update goal")
            ToolResult.error(name, "Imeshindwa: ${e.message}", "DB_ERROR")
        }
    }

    // ════════════════════════════════════════════════════════════
    //  LIST GOALS
    // ════════════════════════════════════════════════════════════

    private suspend fun listGoals(): ToolResult {
        return try {
            val goals = goalDao.getActiveGoalsOnce()

            if (goals.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "Hakuna malengo bado. Anza na 'create goal' — hata KES 500 ni mwanzo mzuri! 🎯"
                )
            }

            val totalSaved = goals.sumOf { it.currentAmount }
            val totalTarget = goals.sumOf { it.targetAmount }
            val overallPct = if (totalTarget > 0) (totalSaved / totalTarget * 100) else 0.0

            val report = buildString {
                appendLine("🎯 MALENGO YAKO")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                goals.forEach { goal ->
                    val pct = if (goal.targetAmount > 0) (goal.currentAmount / goal.targetAmount * 100) else 0.0
                    val emoji = when {
                        pct >= 100 -> "✅"
                        pct >= 50 -> "🟢"
                        pct >= 25 -> "🟡"
                        else -> "🔴"
                    }
                    val daysLeft = ((goal.deadline - System.currentTimeMillis()) / (24 * 60 * 60 * 1000))

                    appendLine("$emoji ${goal.name}")
                    appendLine("   KES ${"%,.0f".format(goal.currentAmount)} / KES ${"%,.0f".format(goal.targetAmount)} (${"%.0f".format(pct)}%)")

                    if (daysLeft > 0) {
                        val remaining = goal.targetAmount - goal.currentAmount
                        val dailyNeeded = remaining / daysLeft
                        appendLine("   Siku $daysLeft zimebaki — KES ${"%,.0f".format(dailyNeeded)}/siku")
                    } else if (goal.currentAmount < goal.targetAmount) {
                        appendLine("   ⏰ Muda umepita!")
                    }
                    appendLine()
                }

                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("📊 JUMLA: KES ${"%,.0f".format(totalSaved)} / KES ${"%,.0f".format(totalTarget)} (${"%.0f".format(overallPct)}%)")
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "goals" to goals.map { mapOf("id" to it.id, "name" to it.name, "target" to it.targetAmount, "current" to it.currentAmount) },
                    "totalSaved" to totalSaved,
                    "totalTarget" to totalTarget
                ),
                message = report
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to list goals")
            ToolResult.error(name, "Imeshindwa: ${e.message}", "DB_ERROR")
        }
    }

    // ════════════════════════════════════════════════════════════
    //  DELETE GOAL
    // ════════════════════════════════════════════════════════════

    private suspend fun deleteGoal(params: Map<String, String>): ToolResult {
        return try {
            val goalId = params["goal_id"]
                ?: return ToolResult.error(name, "ID ya lengo linahitajika", "MISSING_ID")
            val goal = goalDao.getById(goalId)
                ?: return ToolResult.error(name, "Lengo halipatikani", "NOT_FOUND")

            goalDao.delete(goal)
            ToolResult.success(name, message = "✅ Lengo '${goal.name}' limefutwa.")
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete goal")
            ToolResult.error(name, "Imeshindwa: ${e.message}", "DB_ERROR")
        }
    }

    // ════════════════════════════════════════════════════════════
    //  GOAL STATUS (single goal detail)
    // ════════════════════════════════════════════════════════════

    private suspend fun goalStatus(params: Map<String, String>): ToolResult {
        return try {
            val goalId = params["goal_id"]
                ?: return ToolResult.error(name, "ID ya lengo linahitajika", "MISSING_ID")
            val goal = goalDao.getById(goalId)
                ?: return ToolResult.error(name, "Lengo halipatikani", "NOT_FOUND")

            val pct = if (goal.targetAmount > 0) (goal.currentAmount / goal.targetAmount * 100) else 0.0
            val remaining = (goal.targetAmount - goal.currentAmount).coerceAtLeast(0.0)
            val daysLeft = ((goal.deadline - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).coerceAtLeast(1)
            val dailyNeeded = remaining / daysLeft
            val contributions = goalContributionDao.getByGoalOnce(goalId)
            val totalContributed = contributions.sumOf { it.amount }

            val report = buildString {
                appendLine("🎯 ${goal.name}")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("📊 Lengo: KES ${"%,.0f".format(goal.targetAmount)}")
                appendLine("💰 Imewekwa: KES ${"%,.0f".format(goal.currentAmount)} (${"%.0f".format(pct)}%)")
                appendLine("📅 Imebaki: siku $daysLeft")
                appendLine("💡 Unahitaji: KES ${"%,.0f".format(dailyNeeded)}/siku")
                appendLine("📝 Michango: ${contributions.size}")
                if (goal.notes != null) appendLine("📋 Notes: ${goal.notes}")

                if (pct >= 100) {
                    appendLine()
                    appendLine("🎉 LENGO LIMEFIKIWA!")
                }
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "name" to goal.name, "target" to goal.targetAmount,
                    "current" to goal.currentAmount, "percent" to pct,
                    "daysLeft" to daysLeft, "dailyNeeded" to dailyNeeded,
                    "contributionCount" to contributions.size
                ),
                message = report
            )
        } catch (e: Exception) {
            ToolResult.error(name, "Imeshindwa: ${e.message}", "DB_ERROR")
        }
    }

    // ════════════════════════════════════════════════════════════
    //  OVERALL PROGRESS
    // ════════════════════════════════════════════════════════════

    private suspend fun overallProgress(): ToolResult {
        return try {
            val goals = goalDao.getActiveGoalsOnce()
            val totalSaved = goals.sumOf { it.currentAmount }
            val totalTarget = goals.sumOf { it.targetAmount }
            val completedCount = goals.count { it.currentAmount >= it.targetAmount }

            val report = buildString {
                appendLine("📊 MAENDELEO YA AKIBA")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("🎯 Malengo: ${goals.size} (yaliyokamilika: $completedCount)")
                appendLine("💰 Imewekwa: KES ${"%,.0f".format(totalSaved)}")
                appendLine("📊 Lengo: KES ${"%,.0f".format(totalTarget)}")
                if (totalTarget > 0) {
                    val pct = totalSaved / totalTarget * 100
                    appendLine("📈 Maendeleo: ${"%.0f".format(pct)}%")
                }
                appendLine()
                appendLine("💡 Akiba ni kama mboga — hata kidogo kwa siku inakua! 🌱")
            }

            ToolResult.success(
                name,
                data = mapOf("totalSaved" to totalSaved, "totalTarget" to totalTarget, "goalCount" to goals.size),
                message = report
            )
        } catch (e: Exception) {
            ToolResult.error(name, "Imeshindwa: ${e.message}", "DB_ERROR")
        }
    }

    // ════════════════════════════════════════════════════════════
    //  CONTRIBUTION HISTORY
    // ════════════════════════════════════════════════════════════

    private suspend fun contributionHistory(params: Map<String, String>): ToolResult {
        return try {
            val goalId = params["goal_id"]
                ?: return ToolResult.error(name, "ID ya lengo linahitajika", "MISSING_ID")
            val goal = goalDao.getById(goalId)
                ?: return ToolResult.error(name, "Lengo halipatikani", "NOT_FOUND")

            val contributions = goalContributionDao.getByGoalOnce(goalId)

            val report = buildString {
                appendLine("📝 HISTORIA YA MICHANGO — ${goal.name}")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                if (contributions.isEmpty()) {
                    appendLine("Hakuna michango bado.")
                } else {
                    contributions.take(10).forEach { c ->
                        val date = java.text.SimpleDateFormat("dd MMM", java.util.Locale.US)
                            .format(java.util.Date(c.timestamp))
                        appendLine("  $date: KES ${"%,.0f".format(c.amount)} (${c.source})")
                    }
                    if (contributions.size > 10) {
                        appendLine("  ... na ${contributions.size - 10} zaidi")
                    }
                    appendLine()
                    appendLine("Jumla: KES ${"%,.0f".format(contributions.sumOf { it.amount })} kwa michango ${contributions.size}")
                }
            }

            ToolResult.success(name, message = report)
        } catch (e: Exception) {
            ToolResult.error(name, "Imeshindwa: ${e.message}", "DB_ERROR")
        }
    }
}
