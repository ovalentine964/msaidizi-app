package com.msaidizi.app.superagent.tools

import javax.inject.Inject
import javax.inject.Singleton

data class Goal(
    val id: String,
    val name: String,
    val targetAmount: Double,
    val currentAmount: Double,
    val deadline: Long
)

/**
 * GoalTracker — Track savings goals and business targets.
 */
@Singleton
class GoalTracker @Inject constructor() : Tool {

    override val name = "goal_tracker"
    override val description = "Track savings goals and business targets"

    private val goals = mutableListOf<Goal>()

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "list"
        return when (action.lowercase()) {
            "create" -> {
                val goalName = params["name"]
                    ?: return ToolResult.error(name, "Goal name required", "MISSING_NAME")
                val target = params["target"]?.toDoubleOrNull()
                    ?: return ToolResult.error(name, "Target amount required", "MISSING_TARGET")
                val goal = Goal(
                    id = java.util.UUID.randomUUID().toString(),
                    name = goalName,
                    targetAmount = target,
                    currentAmount = 0.0,
                    deadline = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000
                )
                goals.add(goal)
                ToolResult.success(name, mapOf("goal_id" to goal.id, "name" to goalName, "target" to target), "Goal created: $goalName — Target: Ksh ${"%,.0f".format(target)}")
            }
            "update" -> {
                val goalId = params["goal_id"]
                    ?: return ToolResult.error(name, "Goal ID required", "MISSING_ID")
                val amount = params["amount"]?.toDoubleOrNull()
                    ?: return ToolResult.error(name, "Amount required", "MISSING_AMOUNT")
                val goal = goals.find { it.id == goalId }
                    ?: return ToolResult.error(name, "Goal not found", "NOT_FOUND")
                val idx = goals.indexOf(goal)
                goals[idx] = goal.copy(currentAmount = goal.currentAmount + amount)
                val remaining = goal.targetAmount - goal.currentAmount - amount
                ToolResult.success(
                    name,
                    mapOf("goal" to goal.name, "added" to amount, "total" to (goal.currentAmount + amount), "remaining" to remaining.coerceAtLeast(0.0)),
                    "${goal.name}: Ksh ${"%,.0f".format(goal.currentAmount + amount)} / Ksh ${"%,.0f".format(goal.targetAmount)}" +
                            if (remaining <= 0) " 🎉 Goal reached!" else " (Ksh ${"%,.0f".format(remaining.coerceAtLeast(0.0))} to go)"
                )
            }
            "list" -> {
                if (goals.isEmpty()) {
                    ToolResult.success(name, message = "No goals set yet. Create one with 'create goal'.")
                } else {
                    val list = goals.joinToString("\n") { g ->
                        val pct = if (g.targetAmount > 0) (g.currentAmount / g.targetAmount * 100).toInt() else 0
                        "🎯 ${g.name}: Ksh ${"%,.0f".format(g.currentAmount)} / Ksh ${"%,.0f".format(g.targetAmount)} ($pct%)"
                    }
                    ToolResult.success(name, message = list)
                }
            }
            "delete" -> {
                val goalId = params["goal_id"]
                    ?: return ToolResult.error(name, "Goal ID required", "MISSING_ID")
                val removed = goals.removeAll { it.id == goalId }
                if (removed) ToolResult.success(name, message = "Goal deleted")
                else ToolResult.error(name, "Goal not found", "NOT_FOUND")
            }
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }
}
