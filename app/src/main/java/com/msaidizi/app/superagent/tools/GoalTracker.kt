package com.msaidizi.app.superagent.tools
import javax.inject.Inject
data class Goal(val id: String, val name: String, val targetAmount: Double, val currentAmount: Double, val deadline: Long)
data class GoalInput(val action: String, val goalId: String? = null, val amount: Double? = null)
class GoalTracker @Inject constructor() {
    private val goals = mutableListOf<Goal>()
    fun execute(input: GoalInput): ToolResult = when(input.action) {
        "create" -> { goals.add(Goal(java.util.UUID.randomUUID().toString(), input.goalId ?: "", input.amount ?: 0.0, 0.0, System.currentTimeMillis() + 30L*24*60*60*1000)); ToolResult.Success("Goal created") }
        "update" -> { goals.find { it.id == input.goalId }?.let { goals[goals.indexOf(it)] = it.copy(currentAmount = it.currentAmount + (input.amount ?: 0.0)) }; ToolResult.Success("Goal updated") }
        "list" -> ToolResult.Success(goals.joinToString("\n") { "${it.name}: ${it.currentAmount}/${it.targetAmount}" })
        else -> ToolResult.Error("Unknown action")
    }
}
