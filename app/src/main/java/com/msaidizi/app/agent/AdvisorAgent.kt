package com.msaidizi.app.agent

import com.msaidizi.app.core.model.CashFlow
import com.msaidizi.app.core.model.Trend
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Advisor Agent — generates actionable business recommendations.
 * Code decides WHAT to say, LLM decides HOW to say it (when available).
 * Without LLM, uses template-based responses in Swahili.
 */
@Singleton
class AdvisorAgent @Inject constructor(
    private val businessAgent: BusinessAgent,
    private val analysisAgent: AnalysisAgent
) {
    /**
     * Generate advice based on current business state.
     * Returns advice in Swahili (or English based on preference).
     */
    suspend fun getAdvice(language: String = "sw"): String {
        val insights = mutableListOf<String>()

        // Check cash flow
        val cashFlow = businessAgent.getCashFlow(7)
        if (cashFlow.net < 0) {
            insights.add(if (language == "sw") {
                "⚠️ Umepoteza KSh ${"%.0f".format(-cashFlow.net)} wiki hii. Angalia matumizi yako."
            } else {
                "⚠️ You lost KSh ${"%.0f".format(-cashFlow.net)} this week. Check your expenses."
            })
        }

        // Check sales trend
        val trend = analysisAgent.salesTrend()
        if (trend == Trend.FALLING) {
            insights.add(if (language == "sw") {
                "📉 Mauzo yako yamepungua wiki hii. Jaribu kupunguza bei au kuongeza bidhaa mpya."
            } else {
                "📉 Your sales have declined this week. Try reducing prices or adding new products."
            })
        }

        // Check restock alerts
        val restockAlerts = businessAgent.getRestockAlerts()
        for (alert in restockAlerts.take(3)) {
            insights.add(if (language == "sw") {
                "📦 ${alert.item} inakaribia kuisha (${alert.currentStock} imebaki). Nunua zaidi."
            } else {
                "📦 ${alert.item} is running low (${alert.currentStock} left). Restock soon."
            })
        }

        // Check profit margin
        val margin = analysisAgent.getProfitMargin(7)
        if (margin in 0.0..10.0) {
            insights.add(if (language == "sw") {
                "💰 Margin yako ni ndogo (${margin.toInt()}%). Fikiria kupunguza gharama au kuongeza bei."
            } else {
                "💰 Your margin is low (${margin.toInt()}%). Consider reducing costs or increasing prices."
            })
        }

        // Check top items
        val topItems = analysisAgent.topItems(3)
        if (topItems.isNotEmpty()) {
            val topItem = topItems.first()
            insights.add(if (language == "sw") {
                "⭐ Bidhaa bora wiki hii: ${topItem.item} (KSh ${"%.0f".format(topItem.totalRevenue)})"
            } else {
                "⭐ Best selling item this week: ${topItem.item} (KSh ${"%.0f".format(topItem.totalRevenue)})"
            })
        }

        // If no insights, give encouragement
        if (insights.isEmpty()) {
            val profit = businessAgent.getDailyProfit()
            insights.add(if (language == "sw") {
                if (profit > 0) {
                    "✅ Biashara yako iko vizuri leo! Umefaidi KSh ${"%.0f".format(profit)}."
                } else {
                    "📝 Anza kurekodi mauzo yako leo. Sema 'Nimeuza' kuanza."
                }
            } else {
                if (profit > 0) {
                    "✅ Your business is doing well today! Profit: KSh ${"%.0f".format(profit)}."
                } else {
                    "📝 Start recording your sales today. Say 'I sold' to begin."
                }
            })
        }

        return insights.joinToString("\n\n")
    }

    /**
     * Get daily summary response.
     */
    suspend fun getDailySummary(language: String = "sw"): String {
        val sales = businessAgent.getDailySales()
        val purchases = businessAgent.getDailyPurchases()
        val profit = businessAgent.getDailyProfit()
        val count = businessAgent.getDailyTransactionCount()
        val margin = if (sales > 0) (profit / sales * 100) else 0.0

        return if (language == "sw") {
            buildString {
                appendLine("📊 Muhtasari wa leo:")
                appendLine("")
                appendLine("💰 Mauzo: KSh ${"%.0f".format(sales)}")
                appendLine("🛒 Manunuzi: KSh ${"%.0f".format(purchases)}")
                appendLine("📈 Faida: KSh ${"%.0f".format(profit)}")
                appendLine("📋 Shughuli: $count")
                if (sales > 0) {
                    appendLine("📊 Margin: ${margin.toInt()}%")
                }
            }
        } else {
            buildString {
                appendLine("📊 Today's Summary:")
                appendLine("")
                appendLine("💰 Sales: KSh ${"%.0f".format(sales)}")
                appendLine("🛒 Purchases: KSh ${"%.0f".format(purchases)}")
                appendLine("📈 Profit: KSh ${"%.0f".format(profit)}")
                appendLine("📋 Transactions: $count")
                if (sales > 0) {
                    appendLine("📊 Margin: ${margin.toInt()}%")
                }
            }
        }
    }

    /**
     * Get stock query response.
     */
    suspend fun getStockInfo(item: String? = null, language: String = "sw"): String {
        val alerts = businessAgent.getRestockAlerts()

        if (alerts.isEmpty()) {
            return if (language == "sw") {
                "✅ Stock yako iko vizuri. Hakuna bidhaa inayokaribia kuisha."
            } else {
                "✅ Your stock is fine. No items are running low."
            }
        }

        val items = alerts.joinToString("\n") { alert ->
            if (language == "sw") {
                "• ${alert.item}: ${alert.currentStock} imebaki (chini ya ${alert.threshold})"
            } else {
                "• ${alert.item}: ${alert.currentStock} left (below ${alert.threshold})"
            }
        }

        return if (language == "sw") {
            "📦 Bidhaa zinazohitaji kununuliwa:\n$items"
        } else {
            "📦 Items that need restocking:\n$items"
        }
    }

    /**
     * Get greeting response.
     */
    suspend fun getGreeting(language: String = "sw"): String {
        val hour = java.time.LocalTime.now().hour
        val profit = businessAgent.getDailyProfit()

        return if (language == "sw") {
            when {
                hour < 12 -> "🌅 Habari za asubuhi! Faida yako leo ni KSh ${"%.0f".format(profit)}."
                hour < 17 -> "☀️ Habari za mchana! Faida yako leo ni KSh ${"%.0f".format(profit)}."
                else -> "🌙 Habari za jioni! Faida yako leo ni KSh ${"%.0f".format(profit)}."
            }
        } else {
            when {
                hour < 12 -> "🌅 Good morning! Your profit today is KSh ${"%.0f".format(profit)}."
                hour < 17 -> "☀️ Good afternoon! Your profit today is KSh ${"%.0f".format(profit)}."
                else -> "🌙 Good evening! Your profit today is KSh ${"%.0f".format(profit)}."
            }
        }
    }

    /**
     * Get help response.
     */
    fun getHelp(language: String = "sw"): String {
        return if (language == "sw") {
            """
            |🎤 Msaidizi — CFO wako wa biashara!
            |
            |Sema vitu hivi:
            |• "Nimeuza mandazi kumi kwa 500" — Rekodi mauzo
            |• "Nimenunua unga kwa 200" — Rekodi manunuzi
            |• "Faida yangu" — Angalia faida
            |• "Report ya leo" — Muhtasari wa leo
            |• "Stock" — Angalia bidhaa
            |• "Nisaidie" — Pata ushauri
            """.trimMargin()
        } else {
            """
            |🎤 Msaidizi — Your business CFO!
            |
            |Say things like:
            |• "I sold 10 mandazi for 500" — Record a sale
            |• "I bought flour for 200" — Record a purchase
            |• "My profit" — Check profit
            |• "Daily report" — Today's summary
            |• "Stock" — Check inventory
            |• "Help me" — Get advice
            """.trimMargin()
        }
    }
}
