package com.msaidizi.app.agent

import com.msaidizi.app.gamification.GamificationEngine
import com.msaidizi.app.core.model.IntentResult
import com.msaidizi.app.core.model.Trend
import com.msaidizi.app.mindset.RichHabitsScore
import timber.log.Timber

/**
 * Handles all query intents: balance, profit, stock, daily/weekly summaries.
 *
 * Extracted from Orchestrator for Single Responsibility.
 * Pure read operations — no side effects beyond gamification tracking.
 *
 * **ECO 201 §1.2:** Profit = Total Revenue - Total Cost.
 * **STA 244 §10.1:** Trend analysis using moving averages.
 */
class QueryHandler(
    private val businessAgent: BusinessAgent,
    private val analysisAgent: AnalysisAgent,
    private val advisorAgent: AdvisorAgent,
    private val gamificationEngine: GamificationEngine? = null,
    private val richHabitsScore: RichHabitsScore? = null
) {
    /** Handle profit query. ECO 201 §1.2: Profit = Revenue - Cost. */
    suspend fun handleProfitQuery(language: String): AgentResponse {
        val profit = businessAgent.getDailyProfit()
        val sales = businessAgent.getDailySales()
        val margin = if (sales > 0) (profit / sales * 100) else 0.0

        return AgentResponse(
            text = if (language == "sw") {
                "💰 Faida yako leo ni KSh ${"%.0f".format(profit)} (margin ${margin.toInt()}%)"
            } else {
                "💰 Your profit today is KSh ${"%.0f".format(profit)} (margin ${margin.toInt()}%)"
            },
            type = ResponseType.INFORMATION,
            data = mapOf(
                "profit" to profit.toString(),
                "sales" to sales.toString(),
                "margin" to margin.toString()
            )
        )
    }

    /** Handle balance query with gamification rewards. */
    suspend fun handleBalanceQuery(language: String): AgentResponse {
        val balance = businessAgent.getBalance()

        val gamificationMessages = mutableListOf<String>()
        gamificationEngine?.let { ge ->
            try { gamificationMessages.addAll(ge.onBalanceChecked(language).messages) } catch (_: Exception) {}
        }
        richHabitsScore?.let { rhs ->
            try { rhs.autoCompleteFromAction("balance_check", language).forEach { gamificationMessages.add(it.message) } } catch (_: Exception) {}
        }

        val baseText = if (language == "sw") "💰 Salio lako ni KSh ${"%.0f".format(balance)}"
        else "💰 Your balance is KSh ${"%.0f".format(balance)}"

        val fullText = if (gamificationMessages.isNotEmpty()) baseText + "\n" + gamificationMessages.joinToString("\n")
        else baseText

        return AgentResponse(text = fullText, type = ResponseType.INFORMATION, data = mapOf("balance" to balance.toString()))
    }

    /** Handle stock query. */
    suspend fun handleStockQuery(intentResult: IntentResult, language: String): AgentResponse {
        val text = advisorAgent.getStockInfo(intentResult.extractedData["item"], language)
        return AgentResponse(text = text, type = ResponseType.INFORMATION)
    }

    /** Handle daily summary. */
    suspend fun handleDailySummary(language: String): AgentResponse {
        val text = advisorAgent.getDailySummary(language)
        return AgentResponse(text = text, type = ResponseType.INFORMATION)
    }

    /**
     * Handle weekly summary with trend analysis.
     * **STA 244 §10.1:** Trend analysis using moving averages.
     */
    suspend fun handleWeeklySummary(language: String): AgentResponse {
        val cashFlow = businessAgent.getCashFlow(7)
        val topItems = analysisAgent.topItems(7, 3)
        val trend = analysisAgent.salesTrend()

        val trendText = when (trend) {
            Trend.RISING -> if (language == "sw") "📈 Inaongezeka" else "📈 Rising"
            Trend.FALLING -> if (language == "sw") "📉 Inapungua" else "📉 Falling"
            Trend.STABLE -> if (language == "sw") "➡️ Imara" else "➡️ Stable"
            Trend.INSUFFICIENT_DATA -> if (language == "sw") "📊 Data ndogo" else "📊 Limited data"
        }

        val topItemsText = topItems.joinToString("\n") { item ->
            "• ${item.item}: KSh ${"%.0f".format(item.totalRevenue)}"
        }

        return AgentResponse(
            text = if (language == "sw") {
                """
                |📊 Muhtasari wa wiki:
                |
                |💰 Mauzo: KSh ${"%.0f".format(cashFlow.inflow)}
                |🛒 Matumizi: KSh ${"%.0f".format(cashFlow.outflow)}
                |📈 Faida: KSh ${"%.0f".format(cashFlow.net)}
                |📊 Mwelekeo: $trendText
                |
                |⭐ Bidhaa bora:
                |$topItemsText
                """.trimMargin()
            } else {
                """
                |📊 Weekly Summary:
                |
                |💰 Sales: KSh ${"%.0f".format(cashFlow.inflow)}
                |🛒 Expenses: KSh ${"%.0f".format(cashFlow.outflow)}
                |📈 Profit: KSh ${"%.0f".format(cashFlow.net)}
                |📊 Trend: $trendText
                |
                |⭐ Top items:
                |$topItemsText
                """.trimMargin()
            },
            type = ResponseType.INFORMATION,
            data = mapOf(
                "sales" to cashFlow.inflow.toString(),
                "expenses" to cashFlow.outflow.toString(),
                "profit" to cashFlow.net.toString(),
                "trend" to trend.name
            )
        )
    }
}
