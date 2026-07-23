package com.msaidizi.app.agent.tools

/**
 * GoalTool — Set and track financial/business goals.
 * Combines RecordGoalTool and CheckGoalTool.
 */
class GoalTool(
    private val goalDao: com.msaidizi.app.data.dao.GoalDao
) : Tool {
    override val name = "goal"
    override val description = "Malengo — Set and track goals"
    override val supportedIntents = listOf("goal_set", "goal_check", "goal_progress", "goal_report")
    override val memoryRequiredMB = 5

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val action = args["action"]?.toString()
            ?: if (args.containsKey("amount") || args.containsKey("target")) "set" else "check"

        return when (action) {
            "set" -> setGoal(args, language)
            "check", "progress" -> checkGoals(args, language)
            else -> checkGoals(args, language)
        }
    }

    private suspend fun setGoal(args: Map<String, Any>, language: String): ToolResult {
        val amount = (args["amount"] as? Number)?.toDouble()
            ?: args["target"]?.toString()?.toDoubleOrNull()
            ?: args["amount"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult(
                text = if (language == "sw") "Lengo lako ni pesa ngapi? Sema: Lengo la akiba 5000"
                else "What's your goal amount? Say: Savings goal 5000",
                data = emptyMap(), success = false, errorCode = "MISSING_AMOUNT"
            )

        val name = args["name"]?.toString()
            ?: args["item"]?.toString()
            ?: if (language == "sw") "Lengo la akiba" else "Savings goal"

        val goal = com.msaidizi.app.data.entity.GoalEntity(
            name = name,
            targetAmount = amount,
            category = args["category"]?.toString() ?: "savings",
            workerId = args["workerId"]?.toString() ?: throw IllegalStateException("workerId must be provided by SuperAgent")
        )
        val id = goalDao.insert(goal)

        return ToolResult(
            text = if (language == "sw") {
                "🎯 Lengo limekuwa: $name — KSh ${"%,.0f".format(amount)}. Nitakukumbusha kuhusu maendeleo!"
            } else {
                "🎯 Goal set: $name — KSh ${"%,.0f".format(amount)}. I'll track your progress!"
            },
            data = mapOf("goalId" to id.toString(), "name" to name, "target" to amount.toString()),
            success = true
        )
    }

    private suspend fun checkGoals(args: Map<String, Any>, language: String): ToolResult {
        val workerId = args["workerId"]?.toString() ?: throw IllegalStateException("workerId must be provided by SuperAgent")
        val activeGoals = goalDao.getActiveGoals(workerId)

        if (activeGoals.isEmpty()) {
            return ToolResult(
                text = if (language == "sw") {
                    "🎯 Bado hujaweka malengo. Sema: Lengo la akiba [kiasi]"
                } else {
                    "🎯 No goals set yet. Say: Savings goal [amount]"
                },
                data = emptyMap(), success = true
            )
        }

        val goalsText = activeGoals.joinToString("\n") { goal ->
            val progress = if (goal.targetAmount > 0) {
                ((goal.currentAmount / goal.targetAmount) * 100).toInt()
            } else 0
            val bar = progressBar(progress)
            if (language == "sw") {
                "🎯 ${goal.name}: $bar $progress% (KSh ${"%,.0f".format(goal.currentAmount)}/${"%,.0f".format(goal.targetAmount)})"
            } else {
                "🎯 ${goal.name}: $bar $progress% (KSh ${"%,.0f".format(goal.currentAmount)}/${"%,.0f".format(goal.targetAmount)})"
            }
        }

        return ToolResult(
            text = goalsText,
            data = mapOf("goalCount" to activeGoals.size.toString()),
            success = true
        )
    }

    private fun progressBar(percent: Int): String {
        val filled = (percent / 10).coerceIn(0, 10)
        return "█".repeat(filled) + "░".repeat(10 - filled)
    }

    override fun onLowMemory() {}
}
