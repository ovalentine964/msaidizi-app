package com.msaidizi.app.superagent.flow

/**
 * MoneyFlowVisualizer — "Pesa yangu imeenda wapi?"
 * The centerpiece: a clear breakdown showing workers WHERE THEIR MONEY WENT.
 *
 * Input: KES 10,000 revenue
 * Output:
 *   Stock:     KES 6,000 (60%)
 *   Transport: KES 1,500 (15%)
 *   Rent:      KES 1,000 (10%)
 *   Waste:     KES   500 (5%)
 *   Utilities: KES   200 (2%)
 *   PROFIT:    KES   800 (8%)
 *
 * Visual text-based flow for voice and display.
 */
class MoneyFlowVisualizer(
    private val revenueTracker: RevenueTracker,
    private val costTracker: CostTracker,
    private val profitCalculator: ProfitCalculator,
    private val inventoryTracker: InventoryTracker,
    private val customerTracker: CustomerTracker,
    private val supplierTracker: SupplierTracker
) {

    /**
     * Generate the full "Where did my money go?" report.
     * This is the report workers will hear and see.
     */
    fun generateMoneyFlowReport(period: ReportPeriod, customRange: DateRange? = null): MoneyFlowReport {
        val revenue = revenueTracker.getSummary(period, customRange)
        val costs = costTracker.getSummary(period, customRange)
        val profit = profitCalculator.getProfitSummary(period, customRange)
        val inventory = inventoryTracker.getSummary()
        val customerSummary = customerTracker.getSummary(period, customRange)

        // Money IN
        val moneyIn = MoneyFlowCategory(
            label = "Total Revenue",
            labelSw = "Pesa Iliyoingia",
            amount = revenue.totalRevenue,
            percentOfTotal = 100.0
        )

        // Money OUT — break it down
        val moneyOutCategories = mutableListOf<MoneyFlowCategory>()
        val totalOut = costs.totalCosts

        if (costs.cogs > 0) {
            moneyOutCategories.add(MoneyFlowCategory(
                label = "Stock Purchases",
                labelSw = "Manunuo ya Stock",
                amount = costs.cogs,
                percentOfTotal = safePercent(costs.cogs, revenue.totalRevenue)
            ))
        }
        if (costs.transport > 0) {
            moneyOutCategories.add(MoneyFlowCategory(
                label = "Transport",
                labelSw = "Usafiri",
                amount = costs.transport,
                percentOfTotal = safePercent(costs.transport, revenue.totalRevenue)
            ))
        }
        if (costs.rent > 0) {
            moneyOutCategories.add(MoneyFlowCategory(
                label = "Rent & Market Fees",
                labelSw = "Kodi ya Duka/Soko",
                amount = costs.rent,
                percentOfTotal = safePercent(costs.rent, revenue.totalRevenue)
            ))
        }
        if (costs.waste > 0) {
            moneyOutCategories.add(MoneyFlowCategory(
                label = "Waste & Spoilage",
                labelSw = "Potea na Kuharibika",
                amount = costs.waste,
                percentOfTotal = safePercent(costs.waste, revenue.totalRevenue)
            ))
        }
        if (costs.utilities > 0) {
            moneyOutCategories.add(MoneyFlowCategory(
                label = "Utilities (Airtime, Bundles)",
                labelSw = "Muda wa Simu na Internet",
                amount = costs.utilities,
                percentOfTotal = safePercent(costs.utilities, revenue.totalRevenue)
            ))
        }
        if (costs.labor > 0) {
            moneyOutCategories.add(MoneyFlowCategory(
                label = "Labor",
                labelSw = "Mshahara wa Wafanyakazi",
                amount = costs.labor,
                percentOfTotal = safePercent(costs.labor, revenue.totalRevenue)
            ))
        }
        if (costs.otherCosts > 0) {
            moneyOutCategories.add(MoneyFlowCategory(
                label = "Other Costs",
                labelSw = "Gharama Nyingine",
                amount = costs.otherCosts,
                percentOfTotal = safePercent(costs.otherCosts, revenue.totalRevenue)
            ))
        }

        val moneyOut = MoneyFlowBreakdown(
            total = totalOut,
            categories = moneyOutCategories
        )

        // Money TIED UP (locked in stock or owed by customers)
        val moneyTied = MoneyTiedUp(
            inStock = inventory.totalStockValue,
            inCredit = customerSummary.totalOutstandingCredit,
            total = inventory.totalStockValue + customerSummary.totalOutstandingCredit
        )

        // Generate recommendations
        val recommendations = generateRecommendations(revenue, costs, profit, inventory, customerSummary)

        // Build summaries
        val summarySw = buildSwahiliSummary(moneyIn, moneyOut, profit, moneyTied, costs)
        val summaryEn = buildEnglishSummary(moneyIn, moneyOut, profit, moneyTied, costs)
        val voice = buildVoiceSummary(moneyIn, moneyOut, profit, moneyTied, costs)

        return MoneyFlowReport(
            period = revenue.period,
            moneyIn = moneyIn,
            moneyOut = moneyOut,
            moneyLeft = profit.netProfit,
            moneyTied = moneyTied,
            summaryEnglish = summaryEn,
            summarySwahili = summarySw,
            voiceSummary = voice,
            recommendations = recommendations
        )
    }

    // ── Text Flow Visualization ────────────────

    /**
     * Generate a text-based visual flow showing money in → money out → profit.
     * Works for both display and voice (TTS).
     *
     * Example output:
     * ```
     * 💰 PESA ILIYOINGIA: KES 10,000
     * │
     * ├── 📦 Stock: KES 6,000 (60%)
     * ├── 🚗 Usafiri: KES 1,500 (15%)
     * ├── 🏠 Kodi: KES 1,000 (10%)
     * ├── 🗑️ Potea: KES 500 (5%)
     * ├── 📱 Simu: KES 200 (2%)
     * │
     * └── 💵 FAIDA: KES 800 (8%)
     * ```
     */
    fun generateFlowDiagram(period: ReportPeriod, customRange: DateRange? = null): String {
        val report = generateMoneyFlowReport(period, customRange)

        return buildString {
            appendLine("💰 PESA ILIYOINGIA: ${formatKes(report.moneyIn.amount)}")
            appendLine("│")

            report.moneyOut.categories.forEachIndexed { index, category ->
                val isLast = index == report.moneyOut.categories.size - 1
                val prefix = if (isLast) "└──" else "├──"
                val icon = getCategoryIcon(category.labelSw)
                appendLine("$prefix $icon ${category.labelSw}: ${formatKes(category.amount)} (${formatPercent(category.percentOfTotal)})")
            }

            appendLine("│")
            val profitIcon = if (report.moneyLeft >= 0) "💵" else "🔴"
            val profitLabel = if (report.moneyLeft >= 0) "FAIDA" else "HASARA"
            appendLine("└── $profitIcon $profitLabel: ${formatKes(kotlin.math.abs(report.moneyLeft))} (${formatPercent(safePercent(report.moneyLeft, report.moneyIn.amount))})")

            if (report.moneyTied.total > 0) {
                appendLine("")
                appendLine("🔒 PESA IMESHIKWA:")
                if (report.moneyTied.inStock > 0) {
                    appendLine("   📦 Stock: ${formatKes(report.moneyTied.inStock)}")
                }
                if (report.moneyTied.inCredit > 0) {
                    appendLine("   📋 Dlana: ${formatKes(report.moneyTied.inCredit)}")
                }
            }

            if (report.recommendations.isNotEmpty()) {
                appendLine("")
                appendLine("💡 MAPENDEKEZO:")
                report.recommendations.take(3).forEach { rec ->
                    appendLine("   • ${rec.titleSw}")
                }
            }
        }
    }

    /**
     * Simplified flow for voice/TTS.
     * Natural Swahili speech, not robotic.
     */
    fun generateVoiceFlow(period: ReportPeriod, customRange: DateRange? = null): String {
        val report = generateMoneyFlowReport(period, customRange)

        return buildString {
            // Opening
            append("Wacha nionyeshe pesa yako ilikwendaje. ")

            // Money in
            append("Ulipata ${formatKesVoice(report.moneyIn.amount)}. ")

            // Money out breakdown
            append("Pesa ilikwendaje: ")
            report.moneyOut.categories.forEach { category ->
                append("${category.labelSw} ${formatKesVoice(category.amount)}, ")
            }

            // Profit
            if (report.moneyLeft >= 0) {
                append("Faida yako ni ${formatKesVoice(report.moneyLeft)}. ")
            } else {
                append("Ulipoteza ${formatKesVoice(kotlin.math.abs(report.moneyLeft))}. ")
            }

            // Money tied up
            if (report.moneyTied.total > 0) {
                append("Pesa imeshikwa: stock ${formatKesVoice(report.moneyTied.inStock)}")
                if (report.moneyTied.inCredit > 0) {
                    append(", dlana ${formatKesVoice(report.moneyTied.inCredit)}")
                }
                append(". ")
            }

            // Top recommendation
            report.recommendations.firstOrNull()?.let { rec ->
                append("Ushauri: ${rec.detailSw}")
            }
        }
    }

    // ── Summary Builders ───────────────────────

    private fun buildSwahiliSummary(
        moneyIn: MoneyFlowCategory,
        moneyOut: MoneyFlowBreakdown,
        profit: ProfitSummary,
        moneyTied: MoneyTiedUp,
        costs: CostSummary
    ): String {
        return buildString {
            // The money speech
            append("Ulipata ${formatKes(moneyIn.amount)} ")
            append("Wiki hii/Mwezi huu. ")

            // Where it went (top 3)
            val topCosts = moneyOut.categories.sortedByDescending { it.amount }.take(3)
            append("Pesa ilikwendaje: ")
            topCosts.forEach { cat ->
                append("${cat.labelSw} ${formatKes(cat.amount)} (asilimia ${"%.0f".format(cat.percentOfTotal)}), ")
            }

            // Profit
            if (profit.netProfit > 0) {
                append("Faida yako ni ${formatKes(profit.netProfit)}. ")
                append("Hiyo ni asilimia ${"%.0f".format(profit.netMargin)} ya mauzo. ")
            } else {
                append("Hukupata faida — ulipoteza ${formatKes(kotlin.math.abs(profit.netProfit))}. ")
            }

            // Trend
            when (profit.trendDirection) {
                TrendDirection.UP -> append("Faida imeongezeka — vizuri sana! ")
                TrendDirection.DOWN -> append("Faida imepungua — angalia gharama. ")
                TrendDirection.FLAT -> append("Faida imebaki sawa. ")
            }

            // Tied up money
            if (moneyTied.total > 0) {
                append("Kumbuka: ${formatKes(moneyTied.total)} imeshikwa ")
                if (moneyTied.inStock > 0) append("stock ${formatKes(moneyTied.inStock)} ")
                if (moneyTied.inCredit > 0) append("na dlana ${formatKes(moneyTied.inCredit)}.")
            }
        }
    }

    private fun buildEnglishSummary(
        moneyIn: MoneyFlowCategory,
        moneyOut: MoneyFlowBreakdown,
        profit: ProfitSummary,
        moneyTied: MoneyTiedUp,
        costs: CostSummary
    ): String {
        return buildString {
            append("Money In: KES ${"%,.0f".format(moneyIn.amount)}. ")
            append("Money Out: KES ${"%,.0f".format(moneyOut.total)} ")
            append("(Stock: KES ${"%,.0f".format(costs.cogs)}, ")
            append("Transport: KES ${"%,.0f".format(costs.transport)}, ")
            append("Rent: KES ${"%,.0f".format(costs.rent)}, ")
            append("Waste: KES ${"%,.0f".format(costs.waste)}). ")
            append("Profit: KES ${"%,.0f".format(profit.netProfit)} (${profit.netMargin}% margin). ")
            append("Tied up: KES ${"%,.0f".format(moneyTied.total)} ")
            append("(stock KES ${"%,.0f".format(moneyTied.inStock)}, credit KES ${"%,.0f".format(moneyTied.inCredit)}).")
        }
    }

    private fun buildVoiceSummary(
        moneyIn: MoneyFlowCategory,
        moneyOut: MoneyFlowBreakdown,
        profit: ProfitSummary,
        moneyTied: MoneyTiedUp,
        costs: CostSummary
    ): String {
        return buildString {
            append("Ukipata ${formatKesVoice(moneyIn.amount)}, ")
            append("${formatKesVoice(moneyOut.total)} zimeenda kwenye gharama. ")
            append("Stock ni ${formatKesVoice(costs.cogs)}, ")
            append("usafiri ${formatKesVoice(costs.transport)}, ")
            append("kodi ${formatKesVoice(costs.rent)}. ")
            if (costs.waste > 0) {
                append("Umepoteza ${formatKesVoice(costs.waste)} kupotea. ")
            }
            if (profit.netProfit > 0) {
                append("Faida yako: ${formatKesVoice(profit.netProfit)}. ")
            } else {
                append("Ulipoteza ${formatKesVoice(kotlin.math.abs(profit.netProfit))}. ")
            }
            if (profit.trendDirection == TrendDirection.UP) {
                append("Faida inaongezeka — endelea hivyo!")
            } else if (profit.trendDirection == TrendDirection.DOWN) {
                append("Faida inapungua — angalia gharama zako.")
            }
        }
    }

    // ── Recommendations Engine ─────────────────

    private fun generateRecommendations(
        revenue: RevenueSummary,
        costs: CostSummary,
        profit: ProfitSummary,
        inventory: InventorySummary,
        customerSummary: CustomerSummary
    ): List<Recommendation> {
        val recs = mutableListOf<Recommendation>()

        // 1. High waste?
        if (costs.waste > 0 && revenue.totalRevenue > 0) {
            val wastePercent = (costs.waste / revenue.totalRevenue) * 100
            if (wastePercent > 5) {
                recs.add(Recommendation(
                    type = RecommendationType.COST_REDUCTION,
                    priority = if (wastePercent > 10) RecommendationPriority.CRITICAL else RecommendationPriority.HIGH,
                    title = "Reduce waste — losing ${"%.0f".format(wastePercent)}% to spoilage",
                    titleSw = "Punguza potea — unapoteza asilimia ${"%.0f".format(wastePercent)}",
                    detail = "You're losing KES ${"%,.0f".format(costs.waste)} to waste. Consider buying less perishable stock or selling near-expiry items at a discount.",
                    detailSw = "Unapoteza KES ${"%,.0f".format(costs.waste)} kupotea. Nunua kidogo au uza vitu vya karibu kuharibika kwa bei ndogo.",
                    potentialSavings = costs.waste * 0.5  // save half of waste
                ))
            }
        }

        // 2. High transport costs?
        if (costs.transport > 0 && revenue.totalRevenue > 0) {
            val transportPercent = (costs.transport / revenue.totalRevenue) * 100
            if (transportPercent > 15) {
                recs.add(Recommendation(
                    type = RecommendationType.COST_REDUCTION,
                    priority = RecommendationPriority.MEDIUM,
                    title = "Transport costs are high (${transportPercent.toInt()}% of revenue)",
                    titleSw = "Gharama za usafiri ni kubwa (asilimia ${transportPercent.toInt()})",
                    detail = "KES ${"%,.0f".format(costs.transport)} on transport. Consider bulk buying to reduce trips, or finding a closer supplier.",
                    detailSw = "KES ${"%,.0f".format(costs.transport)} kwa usafiri. Nunua wingi kupunguza safari, au tafuta muuzaji wa karibu.",
                    potentialSavings = costs.transport * 0.3
                ))
            }
        }

        // 3. Dead stock?
        if (inventory.deadStockValue > 0) {
            recs.add(Recommendation(
                type = RecommendationType.INVENTORY,
                priority = RecommendationPriority.HIGH,
                title = "Dead stock: KES ${"%,.0f".format(inventory.deadStockValue)} tied up in unsold items",
                titleSw = "Stock imekufa: KES ${"%,.0f".format(inventory.deadStockValue)} imeshikwa",
                detail = "These items haven't sold in 14+ days: ${inventory.deadStock.joinToString(", ") { it.productName }}. Consider discounting or returning.",
                detailSw = "Hivi havijauzwa siku 14+: ${inventory.deadStock.joinToString(", ") { it.productName }}. Uza kwa bei ndogo au rudisha.",
                potentialSavings = inventory.deadStockValue * 0.5
            ))
        }

        // 4. Low stock alerts?
        if (inventory.lowStockAlerts.isNotEmpty()) {
            recs.add(Recommendation(
                type = RecommendationType.INVENTORY,
                priority = RecommendationPriority.CRITICAL,
                title = "Low stock alert: ${inventory.lowStockAlerts.joinToString(", ") { it.productName }}",
                titleSw = "Stock inakaribia kuisha: ${inventory.lowStockAlerts.joinToString(", ") { it.productName }}",
                detail = "These products will run out soon. Reorder now to avoid lost sales.",
                detailSw = "Hivi vitaisha hivi karibuni. Nunua sasa usipoteze mauzo."
            ))
        }

        // 5. Outstanding credit?
        if (customerSummary.totalOutstandingCredit > 1000) {
            recs.add(Recommendation(
                type = RecommendationType.CUSTOMER,
                priority = if (customerSummary.totalOutstandingCredit > 10000) RecommendationPriority.HIGH else RecommendationPriority.MEDIUM,
                title = "KES ${"%,.0f".format(customerSummary.totalOutstandingCredit)} owed by customers",
                titleSw = "Wateja wanakudai KES ${"%,.0f".format(customerSummary.totalOutstandingCredit)}",
                detail = "Collect outstanding credit to improve cash flow.",
                detailSw = "Dai dlana yako ili pesa zirudi kwako."
            ))
        }

        // 6. Low margin?
        if (profit.netMargin < 10 && profit.netMargin > 0) {
            recs.add(Recommendation(
                type = RecommendationType.REVENUE_BOOST,
                priority = RecommendationPriority.MEDIUM,
                title = "Low profit margin: only ${"%.0f".format(profit.netMargin)}%",
                titleSw = "Faida ni ndogo: asilimia ${"%.0f".format(profit.netMargin)} tu",
                detail = "Your margin is thin. Consider raising prices or reducing costs.",
                detailSw = "Faida ni ndogo. Onza bei au punguza gharama.",
                potentialSavings = revenue.totalRevenue * 0.05  // 5% improvement
            ))
        }

        // 7. Price comparison from suppliers
        val priceAlerts = supplierTracker.generatePriceAlerts()
        if (priceAlerts.isNotEmpty()) {
            val best = priceAlerts.first()
            recs.add(Recommendation(
                type = RecommendationType.SUPPLIER,
                priority = RecommendationPriority.MEDIUM,
                title = "Save KES ${"%.0f".format(best.savings)}/unit on ${best.productName}",
                titleSw = "Okoa KES ${"%.0f".format(best.savings)} kwa kila ${best.productName}",
                detail = best.message,
                detailSw = best.messageSw,
                potentialSavings = best.savings * 30  // assume 30 units/month
            ))
        }

        return recs.sortedBy { priorityOrder(it.priority) }
    }

    // ── Helpers ────────────────────────────────

    private fun safePercent(part: Double, total: Double): Double {
        return if (total > 0) (kotlin.math.abs(part) / total) * 100 else 0.0
    }

    private fun formatKes(amount: Double): String {
        return when {
            amount >= 1_000_000 -> "KES ${"%.1f".format(amount / 1_000_000)} milioni"
            amount >= 1_000 -> "KES ${"%,.0f".format(amount)}"
            else -> "KES ${"%.0f".format(amount)}"
        }
    }

    private fun formatKesVoice(amount: Double): String {
        val abs = kotlin.math.abs(amount)
        return when {
            abs >= 1_000_000 -> "${"%.1f".format(abs / 1_000_000)} milioni"
            abs >= 1_000 -> "${"%,.0f".format(abs)}"
            else -> "${"%.0f".format(abs)}"
        }
    }

    private fun formatPercent(value: Double): String {
        return "${"%.0f".format(value)}%"
    }

    private fun getCategoryIcon(labelSw: String): String {
        return when {
            labelSw.contains("Stock", ignoreCase = true) -> "📦"
            labelSw.contains("Usafiri", ignoreCase = true) -> "🚗"
            labelSw.contains("Kodi", ignoreCase = true) -> "🏠"
            labelSw.contains("Potea", ignoreCase = true) -> "🗑️"
            labelSw.contains("Simu", ignoreCase = true) || labelSw.contains("Muda", ignoreCase = true) -> "📱"
            labelSw.contains("Mshahara", ignoreCase = true) -> "👷"
            labelSw.contains("Nyingine", ignoreCase = true) -> "📋"
            else -> "💸"
        }
    }

    private fun priorityOrder(priority: RecommendationPriority): Int = when (priority) {
        RecommendationPriority.CRITICAL -> 0
        RecommendationPriority.HIGH -> 1
        RecommendationPriority.MEDIUM -> 2
        RecommendationPriority.LOW -> 3
    }
}
