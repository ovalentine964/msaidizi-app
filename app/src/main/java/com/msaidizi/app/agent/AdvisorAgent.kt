package com.msaidizi.app.agent

import com.msaidizi.app.core.model.CashFlow
import com.msaidizi.app.core.model.IntentResult
import com.msaidizi.app.core.model.IntentType
import com.msaidizi.app.core.model.Trend
import timber.log.Timber


/**
 * Advisor Agent — generates actionable business recommendations grounded
 * in economic and financial theory.
 * Code decides WHAT to say, LLM decides HOW to say it (when available).
 * Without LLM, uses template-based responses in Swahili.
 *
 * ## Economic & Statistical Foundations
 *
 * ### ECO 206 — Economics of Microfinance
 * - **Financial Inclusion Theory:** Credit access is a capability enabler (Sen, 1999).
 *   We identify when a user's business patterns suggest creditworthiness.
 * - **Adverse Selection (Stiglitz-Weiss):** Lenders can't distinguish good from bad
 *   borrowers. We use transaction data to signal creditworthiness.
 * - **Progressive Lending:** Start small, increase as repayment history builds.
 *   We recommend conservative borrowing levels based on observed cash flow.
 * - **Savings Mobilization:** Consistent savings is the foundation of financial health.
 *   We track savings patterns and encourage saving behavior.
 *
 * ### ECO 209 — Money and Banking
 * - **Money Supply & Velocity:** Track how fast money circulates in the user's business.
 * - **Interest Rate Theory (Fisher Equation):** r = i - π. We explain real vs. nominal
 *   returns to help traders understand inflation's impact on their earnings.
 * - **Financial Innovation:** Mobile money (M-Pesa) has transformed financial access.
 *   We recommend digital payment adoption based on transaction patterns.
 *
 * ### ECO 210 — Quantitative Methods
 * - **Break-Even Analysis:** Solving for the quantity where revenue = costs
 * - **Optimization:** Constrained maximization of profit given capital limits
 * - **Present Value:** Evaluating investment decisions using discounted cash flow
 *
 * ### ECO 322 — Advanced Macroeconomics
 * - **Inflation Context:** CPI tracking to contextualize real vs. nominal earnings
 * - **Exchange Rate Impact:** For cross-border traders, KES movements matter
 * - **Business Cycles:** Adjusting advice based on expansion/contraction signals
 * - **Fiscal Policy:** Tax implications for growing informal businesses
 *
 * @see BusinessAgent for transaction data
 * @see AnalysisAgent for statistical analysis
 */
class AdvisorAgent(
    private val businessAgent: BusinessAgent,
    private val analysisAgent: AnalysisAgent
) {

    // ═══════════════════════════════════════════════════════════════
    // ECO 206 — MICROFINANCE: Financial inclusion advice
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate savings advice based on observed cash flow patterns.
     *
     * **ECO 206 §6.4:** Savings mobilization is the foundation of financial
     * inclusion. The "dual-self model" (Thaler & Shefrin) explains why
     * people struggle to save — the "planner" wants to save, but the "doer"
     * wants to spend. Commitment devices help.
     *
     * **ECO 209 §7.1:** The Fisher equation links nominal and real returns:
     *   Real return = Nominal return - Inflation
     * A trader earning 10% nominal but facing 7% inflation only earns 3% real.
     *
     * We recommend saving a percentage of daily profit, calibrated to the
     * user's observed income stability.
     */
    suspend fun getSavingsAdvice(language: String = "sw"): String {
        val cashFlow = businessAgent.getCashFlow(30)
        val dailyProfit = businessAgent.getDailyProfit()

        if (cashFlow.net <= 0) {
            return if (language == "sw") {
                "💡 Mapato yako ni hasara wiki hii. Anza na akiba ndogo — hata KSh 50 kwa siku. " +
                "Akiba ndogo inakua kuwa kubwa kwa muda."
            } else {
                "💡 Your net income is negative this week. Start with small savings — even KSh 50/day. " +
                "Small savings grow over time."
            }
        }

        val avgDailyProfit = cashFlow.net / 30.0
        val recommendedSavings = (avgDailyProfit * 0.10).coerceAtLeast(50.0) // 10% of profit, min KSh 50

        return if (language == "sw") {
            "💰 Faida yako ya wastani ni KSh ${"%.0f".format(avgDailyProfit)}/siku. " +
            "Ninapendekeza uweke KSh ${"%.0f".format(recommendedSavings)}/siku (10% ya faida). " +
            "Baada ya miezi 3, utakuwa na KSh ${"%.0f".format(recommendedSavings * 90)} — " +
            "ya kutosha kununua bidhaa zaidi au kukabiliana na dharura."
        } else {
            "💰 Your average daily profit is KSh ${"%.0f".format(avgDailyProfit)}. " +
            "I recommend saving KSh ${"%.0f".format(recommendedSavings)}/day (10% of profit). " +
            "After 3 months, you'll have KSh ${"%.0f".format(recommendedSavings * 90)} — " +
            "enough to buy more stock or handle emergencies."
        }
    }

    /**
     * Generate credit readiness assessment.
     *
     * **ECO 206 §6.2:** Credit rationing occurs when lenders can't distinguish
     * good from bad borrowers (Stiglitz-Weiss). We use observable business
     * metrics as signals of creditworthiness:
     *
     * 1. Transaction regularity (consistent daily recording)
     * 2. Profit margin stability (low variance = reliable)
     * 3. Business age (longer track record = more trustworthy)
     * 4. Savings behavior (demonstrates financial discipline)
     *
     * These align with "signaling theory" (Spence) — costly actions that
     * reveal private information about quality.
     */
    suspend fun getCreditReadinessAssessment(language: String = "sw"): String {
        val cashFlow = businessAgent.getCashFlow(30)
        val margin = analysisAgent.getProfitMargin(30)
        val transactionCount = businessAgent.getDailyTransactionCount()

        // Scoring factors (0-100)
        val cashFlowScore = when {
            cashFlow.net > 0 && cashFlow.net / 30 > 500 -> 30.0
            cashFlow.net > 0 -> 20.0
            cashFlow.net > -1000 -> 10.0
            else -> 0.0
        }

        val marginScore = when {
            margin > 30 -> 30.0
            margin > 15 -> 20.0
            margin > 5 -> 10.0
            else -> 0.0
        }

        val regularityScore = when {
            transactionCount >= 5 -> 25.0
            transactionCount >= 2 -> 15.0
            else -> 5.0
        }

        val savingsScore = if (cashFlow.net > 0) 15.0 else 0.0

        val totalScore = cashFlowScore + marginScore + regularityScore + savingsScore

        return when {
            totalScore >= 70 -> if (language == "sw") {
                "✅ Biashara yako iko tayari kwa mkopo! Alama: ${totalScore.toInt()}/100. " +
                "Mapato yako ni thabiti na una historia nzuri ya biashara."
            } else {
                "✅ Your business is ready for credit! Score: ${totalScore.toInt()}/100. " +
                "Your income is stable and you have a good business track record."
            }

            totalScore >= 40 -> if (language == "sw") {
                "⏳ Biashara yako inakaribia kukua kwa mkopo. Alama: ${totalScore.toInt()}/100. " +
                "Ongeza mauzo ya kila siku na weka akiba zaidi ili kuboresha alama yako."
            } else {
                "⏳ Your business is getting closer to credit readiness. Score: ${totalScore.toInt()}/100. " +
                "Increase daily sales and save more to improve your score."
            }

            else -> if (language == "sw") {
                "📝 Anza kurekodi mauzo yako kila siku. Alama: ${totalScore.toInt()}/100. " +
                "Baada ya wiki 4 za kurekodi kila siku, nitakupa mkopo mdogo wa kujaribu."
            } else {
                "📝 Start recording your sales every day. Score: ${totalScore.toInt()}/100. " +
                "After 4 weeks of daily recording, I'll recommend a small trial loan."
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ECO 209 — MONEY & BANKING: Investment and savings advice
    // ═══════════════════════════════════════════════════════════════

    /**
     * Evaluate whether reinvesting in inventory is better than saving.
     *
     * **ECO 210 §8.1 (Mathematical Economics):** We compare the marginal
     * return on investment in inventory vs. the return on savings.
     *
     * **ECO 201 §1.3 (Consumer Theory):** The optimal allocation equalizes
     * the marginal utility per shilling across all uses:
     *   MU_inventory / P_inventory = MU_savings / P_savings
     *
     * For a trader, "MU of inventory" = expected additional profit from
     * one more unit of stock, and "MU of savings" = interest earned.
     *
     * @return Recommendation on whether to reinvest or save
     */
    suspend fun getReinvestmentAdvice(language: String = "sw"): String {
        val margin = analysisAgent.getProfitMargin(14)
        val salesTrend = analysisAgent.salesTrend()
        val restockAlerts = businessAgent.getRestockAlerts()

        // If margins are high and trend is rising, reinvest
        // If margins are low or trend is falling, save
        val shouldReinvest = margin > 15 && salesTrend != Trend.FALLING
        val hasRestockNeeds = restockAlerts.isNotEmpty()

        return when {
            hasRestockNeeds && shouldReinvest -> if (language == "sw") {
                "📦 Nunua bidhaa zaidi! Margin yako ni ${margin.toInt()}% na mauzo yanaongezeka. " +
                "Kununua zaidi sasa kutakuletea faida zaidi."
            } else {
                "📦 Buy more stock! Your margin is ${margin.toInt()}% and sales are rising. " +
                "Buying more now will bring more profit."
            }

            shouldReinvest -> if (language == "sw") {
                "📈 Mauzo yako ni mazuri (margin ${margin.toInt()}%). " +
                "Fikiria kuongeza aina mpya za bidhaa au kuuza kwenye soko jipya."
            } else {
                "📈 Your sales are good (margin ${margin.toInt()}%). " +
                "Consider adding new product types or selling in a new market."
            }

            else -> if (language == "sw") {
                "💰 Weka pesa kwanza. Margin yako ni ndogo (${margin.toInt()}%). " +
                "Badilisha bei au punguza gharama kabla ya kununua zaidi."
            } else {
                "💰 Save first. Your margin is low (${margin.toInt()}%). " +
                "Adjust prices or reduce costs before buying more."
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ECO 322 — ADVANCED MACROECONOMICS: Context-aware advice
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate inflation-aware profit analysis.
     *
     * **ECO 322 §5.5:** Inflation erodes purchasing power. The real profit is:
     *   Real Profit = Nominal Profit - (Inflation Rate × Capital)
     *
     * **ECO 209 §7.3 (Fisher Equation):** r = i - π
     * A trader who earns 20% nominal profit but faces 10% inflation
     * only earns 10% real profit.
     *
     * We don't have real-time CPI data, but we can flag when the user's
     * costs are rising faster than their revenue — a sign of cost-push
     * inflation at the micro level.
     */
    suspend fun getInflationAwareAnalysis(language: String = "sw"): String {
        val recentMargin = analysisAgent.getProfitMargin(7)
        val olderMargin = analysisAgent.getProfitMargin(30)

        if (recentMargin == 0.0 || olderMargin == 0.0) {
            return if (language == "sw") {
                "📊 Data ya kutosha kuchambua mwelekeo wa bei."
            } else {
                "📊 Not enough data to analyze price trends."
            }
        }

        val marginChange = recentMargin - olderMargin

        return when {
            marginChange < -5 -> if (language == "sw") {
                "⚠️ Margin yako imepungua kutoka ${olderMargin.toInt()}% hadi ${recentMargin.toInt()}%. " +
                "Hii inaweza kuwa bei za bidhaa zinazopanda (mfumuko wa bei). " +
                "Jaribu kupunguza gharama au kuongeza bei za mauzo."
            } else {
                "⚠️ Your margin dropped from ${olderMargin.toInt()}% to ${recentMargin.toInt()}%. " +
                "This could be input price inflation. Try reducing costs or increasing selling prices."
            }

            marginChange > 5 -> if (language == "sw") {
                "📈 Margin yako imeongezeka! Bei za bidhaa zako zinafaa — " +
                "fikiria kuweka akiba zaidi wakati huu mzuri."
            } else {
                "📈 Your margin increased! Your pricing is working well — " +
                "consider saving more during this good period."
            }

            else -> if (language == "sw") {
                "➡️ Margin yako iko thabiti (${recentMargin.toInt()}%). Biashara iko sawa."
            } else {
                "➡️ Your margin is stable (${recentMargin.toInt()}%). Business is steady."
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ECO 210 — QUANTITATIVE METHODS: Data-driven recommendations
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate comprehensive advice based on current business state.
     *
     * **ECO 210 §8.1:** Data-driven recommendations using quantitative analysis.
     * Each insight is grounded in economic theory and supported by data.
     *
     * **BCB 108 §1.2:** Communication follows the inverted pyramid —
     * most critical information first, supporting details after.
     */
    suspend fun getAdvice(language: String = "sw"): String {
        val insights = mutableListOf<String>()

        // ECO 322: Check cash flow (macroeconomic stability at micro level)
        val cashFlow = businessAgent.getCashFlow(7)
        if (cashFlow.net < 0) {
            insights.add(if (language == "sw") {
                "⚠️ Umepoteza KSh ${"%.0f".format(-cashFlow.net)} wiki hii. Angalia matumizi yako."
            } else {
                "⚠️ You lost KSh ${"%.0f".format(-cashFlow.net)} this week. Check your expenses."
            })
        }

        // STA 244: Check sales trend
        val trend = analysisAgent.salesTrend()
        if (trend == Trend.FALLING) {
            insights.add(if (language == "sw") {
                "📉 Mauzo yako yamepungua wiki hii. Jaribu kupunguza bei au kuongeza bidhaa mpya."
            } else {
                "📉 Your sales have declined this week. Try reducing prices or adding new products."
            })
        }

        // ECO 201 §1.4: Check restock alerts (inventory management)
        val restockAlerts = businessAgent.getRestockAlerts()
        for (alert in restockAlerts.take(3)) {
            insights.add(if (language == "sw") {
                "📦 ${alert.item} inakaribia kuisha (${alert.currentStock} imebaki). Nunua zaidi."
            } else {
                "📦 ${alert.item} is running low (${alert.currentStock} left). Restock soon."
            })
        }

        // ECO 201 §1.4: Check profit margin (cost analysis)
        val margin = analysisAgent.getProfitMargin(7)
        if (margin in 0.0..10.0) {
            insights.add(if (language == "sw") {
                "💰 Margin yako ni ndogo (${margin.toInt()}%). Fikiria kupunguza gharama au kuongeza bei."
            } else {
                "💰 Your margin is low (${margin.toInt()}%). Consider reducing costs or increasing prices."
            })
        }

        // ECO 206: Check savings behavior
        if (cashFlow.net > 0) {
            val savingsRecommendation = cashFlow.net * 0.10
            insights.add(if (language == "sw") {
                "🏦 Weka akiba ya KSh ${"%.0f".format(savingsRecommendation)} wiki hii (10% ya faida)."
            } else {
                "🏦 Save KSh ${"%.0f".format(savingsRecommendation)} this week (10% of profit)."
            })
        }

        // ECO 202: Check top items (product mix optimization)
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

    // ═══════════════════════════════════════════════════════════════
    // STANDARD RESPONSES (BCB 108 — Business Communication)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get daily summary response.
     *
     * **BCB 108 §1.2:** Structured report with inverted pyramid —
     * profit first, then sales, then expenses, then transactions.
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
     *
     * **BCB 108 §1.4:** Cross-cultural communication — time-appropriate greeting
     * combined with immediate business intelligence (inverted pyramid).
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
     *
     * **BCB 108 §1.1:** Clear, concise, action-oriented help text.
     * Uses concrete examples ("Nimeuza mandazi kumi kwa 500") rather
     * than abstract descriptions.
     */
    fun getDomainAdvice(intentResult: IntentResult, language: String = "sw"): String {
        val item = intentResult.extractedData["item"] ?: ""
        val amount = intentResult.extractedData["amount"]
        val queryType = intentResult.extractedData["queryType"]

        return when (intentResult.intent) {
            IntentType.TRANSPORT_TRIP -> if (language == "sw") {
                "🚗 Taarifa za safari: ${item.ifBlank { "safari" }}" +
                if (amount != null) " — KSh $amount" else ""
            } else {
                "🚗 Trip info: ${item.ifBlank { "trip" }}" +
                if (amount != null) " — KSh $amount" else ""
            }
            IntentType.FARMING_ACTIVITY -> if (language == "sw") {
                "🌱 Shughuli ya kilimo: ${item.ifBlank { "mazao" }}"
            } else {
                "🌱 Farming activity: ${item.ifBlank { "crop" }}"
            }
            IntentType.DIGITAL_TRANSACTION,
            IntentType.DIGITAL_COMMISSION -> if (language == "sw") {
                "📱 Shughuli ya kidijitali: ${item.ifBlank { "transaction" }}" +
                if (amount != null) " — KSh $amount" else ""
            } else {
                "📱 Digital transaction: ${item.ifBlank { "transaction" }}" +
                if (amount != null) " — KSh $amount" else ""
            }
            IntentType.SERVICE_CLIENT,
            IntentType.SERVICE_JOB -> if (language == "sw") {
                "💇 Huduma: ${item.ifBlank { "mteja" }}" +
                if (amount != null) " — KSh $amount" else ""
            } else {
                "💇 Service: ${item.ifBlank { "client" }}" +
                if (amount != null) " — KSh $amount" else ""
            }
            else -> if (language == "sw") "Taarifa" else "Information"
        }
    }

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
