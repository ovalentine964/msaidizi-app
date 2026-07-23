package com.msaidizi.app.agent.tools

import com.msaidizi.app.data.dao.TransactionDao
import com.msaidizi.app.data.dao.GoalDao
import com.msaidizi.app.data.dao.LoanDao
import java.util.Calendar

/**
 * BriefingTool — Generate daily business briefings (morning/evening).
 */
class BriefingTool(
    private val transactionDao: TransactionDao,
    private val goalDao: GoalDao,
    private val loanDao: LoanDao
) : Tool {
    override val name = "briefing"
    override val description = "Muhtasari — Daily business briefing"
    override val supportedIntents = listOf("daily_briefing", "morning_report", "evening_summary")
    override val memoryRequiredMB = 15

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val workerId = args["workerId"]?.toString()
            ?: throw IllegalStateException("workerId must be provided by SuperAgent")
        val type = args["type"]?.toString() ?: "morning"

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis
        val now = System.currentTimeMillis()

        val todaySales = transactionDao.getTotalSales(workerId, todayStart, now) ?: 0.0
        val todayExpenses = (transactionDao.getTotalPurchases(workerId, todayStart, now) ?: 0.0) +
                           (transactionDao.getTotalExpenses(workerId, todayStart, now) ?: 0.0)
        val todayProfit = todaySales - todayExpenses
        val activeGoals = goalDao.getActiveGoals(workerId)
        val activeLoans = loanDao.getActiveLoans(workerId)
        val totalDebt = loanDao.getTotalOutstanding(workerId) ?: 0.0

        return if (type == "morning") {
            ToolResult(
                text = if (language == "sw") {
                    buildString {
                        append("🌅 Habari za asubuhi! Muhtasari wa leo:\n\n")
                        append("📊 Jana: Faida KSh ${"%,.0f".format(todayProfit)}\n")
                        if (activeGoals.isNotEmpty()) {
                            append("🎯 Malengo: ${activeGoals.size} yanasubiri\n")
                        }
                        if (activeLoans.isNotEmpty()) {
                            append("💳 Mikopo: KSh ${"%,.0f".format(totalDebt)} baki\n")
                        }
                        append("\n💡 Kumbuka: Rekodi kila mauzo leo!")
                    }
                } else {
                    buildString {
                        append("🌅 Good morning! Today's briefing:\n\n")
                        append("📊 Yesterday: Profit KSh ${"%,.0f".format(todayProfit)}\n")
                        if (activeGoals.isNotEmpty()) {
                            append("🎯 Goals: ${activeGoals.size} active\n")
                        }
                        if (activeLoans.isNotEmpty()) {
                            append("💳 Loans: KSh ${"%,.0f".format(totalDebt)} outstanding\n")
                        }
                        append("\n💡 Remember: Record every sale today!")
                    }
                },
                data = mapOf("type" to "morning"),
                success = true
            )
        } else {
            ToolResult(
                text = if (language == "sw") {
                    buildString {
                        append("🌙 Muhtasari wa jioni:\n\n")
                        append("💰 Mauzo: KSh ${"%,.0f".format(todaySales)}\n")
                        append("📉 Matumizi: KSh ${"%,.0f".format(todayExpenses)}\n")
                        append("📊 Faida: KSh ${"%,.0f".format(todayProfit)}\n")
                        if (todayProfit > 0) {
                            append("\n🎉 Hongera! Leo umefanikiwa!")
                        } else if (todaySales > 0) {
                            append("\n💪 Jaribu kupunguza gharama kesho.")
                        } else {
                            append("\n📝 Kesho anza mapema. Kila siku ni fursa mpya!")
                        }
                    }
                } else {
                    buildString {
                        append("🌙 Evening summary:\n\n")
                        append("💰 Sales: KSh ${"%,.0f".format(todaySales)}\n")
                        append("📉 Expenses: KSh ${"%,.0f".format(todayExpenses)}\n")
                        append("📊 Profit: KSh ${"%,.0f".format(todayProfit)}\n")
                        if (todayProfit > 0) {
                            append("\n🎉 Congratulations! Profitable day!")
                        } else if (todaySales > 0) {
                            append("\n💪 Try to reduce costs tomorrow.")
                        } else {
                            append("\n📝 Tomorrow is a new opportunity!")
                        }
                    }
                },
                data = mapOf("type" to "evening", "profit" to todayProfit.toString()),
                success = true
            )
        }
    }

    override fun onLowMemory() {}
}
