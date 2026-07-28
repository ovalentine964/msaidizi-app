package com.msaidizi.agent.tools

import com.msaidizi.core.database.ProductDao
import com.msaidizi.core.database.SaleDao
import com.msaidizi.core.database.StockMovementDao
import com.msaidizi.core.util.DateTimeUtil
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * ProfitByProduct — Tracks profitability per product.
 *
 * Answers: "Nini inaniletea faida zaidi?" (What brings me the most profit?)
 *
 * Features:
 * - Product-level P&L (revenue - COGS - spoilage = net profit)
 * - ABC classification (A = top 20% profit, B = middle, C = bottom)
 * - Actionable recommendations in Swahili (increase, decrease, drop)
 * - Spoilage integration via StockMovementDao (type = "spoilage")
 * - Period-over-period comparison (week vs week, month vs month)
 *
 * Actions: analyze, ranking, recommendations, history, compare, margins
 */
@Singleton
class ProfitByProduct @Inject constructor(
    private val saleDao: SaleDao,
    private val productDao: ProductDao,
    private val stockMovementDao: StockMovementDao
) : Tool {

    override val name = "profit_by_product"
    override val description = "Tracks profit per product with ABC classification and recommendations"

    override val argsSchema = argSchema {
        enum(
            "action", "Action to perform",
            listOf("analyze", "ranking", "recommendations", "history", "compare", "margins"),
            required = false
        )
        string("product", "Product name (for history/margins actions)", required = false)
        string("period", "Time period: daily, weekly, monthly", required = false)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "analyze"
        return when (action.lowercase()) {
            "analyze" -> analyzeProfit(params)
            "ranking" -> getRanking(params)
            "recommendations" -> getRecommendations(params)
            "history" -> getProductHistory(params)
            "compare" -> comparePeriods(params)
            "margins" -> getMargins(params)
            else -> ToolResult.error(name, "Action sio sahihi: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // ACTION 1: ANALYZE — Full product P&L breakdown
    // ──────────────────────────────────────────────

    /**
     * Analyze profit by product for the selected period.
     * Returns revenue, COGS, gross profit, spoilage loss, net profit, margin %.
     */
    suspend fun analyzeProfit(params: Map<String, String>): ToolResult {
        return try {
            val period = params["period"] ?: "weekly"
            val (start, end) = resolvePeriod(period)

            val topProducts = saleDao.getTopProducts(start, end, 20).first()
            if (topProducts.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "Hakuna mauzo ya kurekodiwa kwa kipindi hichi. Anza kuuza na nitafuatilia faida! 📊"
                )
            }

            val products = productDao.getAllActive().first()
            val productMap = products.associateBy { it.name.lowercase() }

            val analyses = topProducts.map { salesSummary ->
                val product = productMap[salesSummary.productName.lowercase()]
                val buyPrice = product?.buyPrice ?: 0.0
                val cogs = salesSummary.totalQty * buyPrice
                val grossProfit = salesSummary.totalRevenue - cogs
                val spoilageLoss = getSpoilageLoss(salesSummary.productName, start, end)
                val netProfit = grossProfit - spoilageLoss
                val margin = if (salesSummary.totalRevenue > 0) {
                    (netProfit / salesSummary.totalRevenue * 100)
                } else 0.0

                ProductProfitAnalysis(
                    productName = salesSummary.productName,
                    revenue = salesSummary.totalRevenue,
                    unitsSold = salesSummary.totalQty,
                    avgSellPrice = if (salesSummary.totalQty > 0) {
                        salesSummary.totalRevenue / salesSummary.totalQty
                    } else 0.0,
                    cogs = cogs,
                    avgBuyPrice = buyPrice,
                    grossProfit = grossProfit,
                    spoilageLoss = spoilageLoss,
                    netProfit = netProfit,
                    marginPct = margin
                )
            }.sortedByDescending { it.netProfit }

            // Assign ABC classification
            val classified = assignAbcClassification(analyses)

            // Build output
            val periodLabel = formatPeriodLabel(period)
            val totalRevenue = classified.sumOf { it.revenue }
            val totalCogs = classified.sumOf { it.cogs }
            val totalGross = classified.sumOf { it.grossProfit }
            val totalSpoilage = classified.sumOf { it.spoilageLoss }
            val totalNet = classified.sumOf { it.netProfit }
            val totalMargin = if (totalRevenue > 0) totalNet / totalRevenue * 100 else 0.0

            val output = buildString {
                appendLine("📊 FAIDA KWA BIDHAA — $periodLabel")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                classified.forEach { p ->
                    val abcEmoji = when (p.abcClass) {
                        "A" -> "🅰️"
                        "B" -> "🅱️"
                        else -> "🅲️"
                    }
                    val profitArrow = if (p.netProfit > 0) "✅" else "❌"
                    appendLine(
                        "$abcEmoji ${p.productName.padEnd(16)} | " +
                            "Mauzo: KSh ${"%,.0f".format(p.revenue).padStart(8)} | " +
                            "Gharama: KSh ${"%,.0f".format(p.cogs).padStart(8)} | " +
                            "Faida: KSh ${"%,.0f".format(p.netProfit).padStart(8)} | " +
                            "Margin: ${"%.0f".format(p.marginPct)}% $profitArrow"
                    )
                    if (p.spoilageLoss > 0) {
                        appendLine(
                            "   ${" ".repeat(16)} | ⚠️  Hasara ya kuoza: KSh ${"%,.0f".format(p.spoilageLoss)}"
                        )
                    }
                }

                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine(
                    "JUMLA          | " +
                        "Mauzo: KSh ${"%,.0f".format(totalRevenue).padStart(8)} | " +
                        "Gharama: KSh ${"%,.0f".format(totalCogs).padStart(8)} | " +
                        "Faida: KSh ${"%,.0f".format(totalNet).padStart(8)} | " +
                        "Margin: ${"%.0f".format(totalMargin)}%"
                )
                if (totalSpoilage > 0) {
                    appendLine("⚠️  Jumla hasara ya kuoza: KSh ${"%,.0f".format(totalSpoilage)}")
                }

                appendLine()
                appendLine("📊 Uchambuzi wa ABC:")
                appendLine("   A-Bidhaa (${classified.count { it.abcClass == "A" }}): ${classified.filter { it.abcClass == "A" }.joinToString(", ") { it.productName }}")
                appendLine("   B-Bidhaa (${classified.count { it.abcClass == "B" }}): ${classified.filter { it.abcClass == "B" }.joinToString(", ") { it.productName }}")
                appendLine("   C-Bidhaa (${classified.count { it.abcClass == "C" }}): ${classified.filter { it.abcClass == "C" }.joinToString(", ") { it.productName }}")
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "products" to classified,
                    "totals" to mapOf(
                        "revenue" to totalRevenue,
                        "cogs" to totalCogs,
                        "gross_profit" to totalGross,
                        "spoilage_loss" to totalSpoilage,
                        "net_profit" to totalNet,
                        "margin_pct" to totalMargin
                    ),
                    "period" to period,
                    "period_label" to periodLabel
                ),
                message = output
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to analyze profit by product")
            ToolResult.error(name, "Imeshindikana: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // ACTION 2: RANKING — ABC-ranked product list
    // ──────────────────────────────────────────────

    /**
     * Get ABC-ranked product list with profit contribution.
     */
    suspend fun getRanking(params: Map<String, String>): ToolResult {
        return try {
            val period = params["period"] ?: "monthly"
            val (start, end) = resolvePeriod(period)

            val topProducts = saleDao.getTopProducts(start, end, 20).first()
            if (topProducts.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "Hakuna mauzo ya kurekodiwa. Anza kuuza! 📦"
                )
            }

            val products = productDao.getAllActive().first()
            val productMap = products.associateBy { it.name.lowercase() }

            val analyses = topProducts.map { salesSummary ->
                val product = productMap[salesSummary.productName.lowercase()]
                val buyPrice = product?.buyPrice ?: 0.0
                val cogs = salesSummary.totalQty * buyPrice
                val grossProfit = salesSummary.totalRevenue - cogs
                val spoilageLoss = getSpoilageLoss(salesSummary.productName, start, end)
                val netProfit = grossProfit - spoilageLoss
                val margin = if (salesSummary.totalRevenue > 0) {
                    (netProfit / salesSummary.totalRevenue * 100)
                } else 0.0

                ProductProfitAnalysis(
                    productName = salesSummary.productName,
                    revenue = salesSummary.totalRevenue,
                    unitsSold = salesSummary.totalQty,
                    avgSellPrice = if (salesSummary.totalQty > 0) {
                        salesSummary.totalRevenue / salesSummary.totalQty
                    } else 0.0,
                    cogs = cogs,
                    avgBuyPrice = buyPrice,
                    grossProfit = grossProfit,
                    spoilageLoss = spoilageLoss,
                    netProfit = netProfit,
                    marginPct = margin
                )
            }.sortedByDescending { it.netProfit }

            val classified = assignAbcClassification(analyses)
            val totalProfit = classified.sumOf { it.netProfit }.coerceAtLeast(1.0)

            val periodLabel = formatPeriodLabel(period)

            val output = buildString {
                appendLine("🏆 Orodha ya Bidhaa — $periodLabel")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                // A items
                val aItems = classified.filter { it.abcClass == "A" }
                if (aItems.isNotEmpty()) {
                    appendLine()
                    appendLine("🅰️  A-BIDHAA (Faida kubwa — zinaletea pesa nyingi):")
                    aItems.forEachIndexed { i, p ->
                        val profitPct = (p.netProfit / totalProfit * 100)
                        appendLine("   ${i + 1}. ${p.productName} — Faida: KSh ${"%,.0f".format(p.netProfit)} (${ "%.0f".format(profitPct)}% ya faida yote, margin ${ "%.0f".format(p.marginPct)}%)")
                    }
                }

                // B items
                val bItems = classified.filter { it.abcClass == "B" }
                if (bItems.isNotEmpty()) {
                    appendLine()
                    appendLine("🅱️  B-BIDHAA (Faida ya wastani):")
                    bItems.forEachIndexed { i, p ->
                        appendLine("   ${aItems.size + i + 1}. ${p.productName} — Faida: KSh ${"%,.0f".format(p.netProfit)} (margin ${ "%.0f".format(p.marginPct)}%)")
                    }
                }

                // C items
                val cItems = classified.filter { it.abcClass == "C" }
                if (cItems.isNotEmpty()) {
                    appendLine()
                    appendLine("🅲️  C-BIDHAA (Faida ndogo — angalia kama zinastahili):")
                    cItems.forEachIndexed { i, p ->
                        val spoilNote = if (p.spoilageLoss > 0) " ⚠️ Kuoza: KSh ${"%,.0f".format(p.spoilageLoss)}" else ""
                        appendLine("   ${aItems.size + bItems.size + i + 1}. ${p.productName} — Faida: KSh ${"%,.0f".format(p.netProfit)} (margin ${ "%.0f".format(p.marginPct)}%)$spoilNote")
                    }
                }

                appendLine()
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                val aProfitPct = if (totalProfit > 0) aItems.sumOf { it.netProfit } / totalProfit * 100 else 0.0
                val aSkuPct = if (classified.isNotEmpty()) aItems.size.toDouble() / classified.size * 100 else 0.0
                appendLine("📊 A-Bidhaa: ${aItems.size} bidhaa (${ "%.0f".format(aSkuPct)}% ya SKU) → ${ "%.0f".format(aProfitPct)}% ya faida")
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "ranking" to classified.mapIndexed { i, p ->
                        mapOf(
                            "rank" to (i + 1),
                            "product" to p.productName,
                            "net_profit" to p.netProfit,
                            "margin_pct" to p.marginPct,
                            "abc_class" to p.abcClass
                        )
                    },
                    "a_items" to classified.filter { it.abcClass == "A" }.map { it.productName },
                    "b_items" to classified.filter { it.abcClass == "B" }.map { it.productName },
                    "c_items" to classified.filter { it.abcClass == "C" }.map { it.productName }
                ),
                message = output
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get ranking")
            ToolResult.error(name, "Imeshindikana: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // ACTION 3: RECOMMENDATIONS — Auto recommendations
    // ──────────────────────────────────────────────

    /**
     * Get stocking recommendations based on profit data.
     * Produces Swahili recommendations: increase, decrease, drop, maintain.
     */
    suspend fun getRecommendations(params: Map<String, String>): ToolResult {
        return try {
            val period = "weekly"
            val (start, end) = resolvePeriod(period)
            val (prevStart, prevEnd) = resolvePreviousPeriod(period)

            val topProducts = saleDao.getTopProducts(start, end, 20).first()
            if (topProducts.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "Hakuna data ya kutosha kutoa mapendekezo. Rekodi mauzo kwanza! 💡"
                )
            }

            val products = productDao.getAllActive().first()
            val productMap = products.associateBy { it.name.lowercase() }

            val analyses = topProducts.map { salesSummary ->
                val product = productMap[salesSummary.productName.lowercase()]
                val buyPrice = product?.buyPrice ?: 0.0
                val cogs = salesSummary.totalQty * buyPrice
                val grossProfit = salesSummary.totalRevenue - cogs
                val spoilageLoss = getSpoilageLoss(salesSummary.productName, start, end)
                val netProfit = grossProfit - spoilageLoss
                val margin = if (salesSummary.totalRevenue > 0) {
                    (netProfit / salesSummary.totalRevenue * 100)
                } else 0.0

                // Get previous period data for trend
                val prevTop = saleDao.getTopProducts(prevStart, prevEnd, 20).first()
                val prevSales = prevTop.find { it.productName.equals(salesSummary.productName, ignoreCase = true) }
                val prevRevenue = prevSales?.totalRevenue ?: 0.0
                val revenueChange = if (prevRevenue > 0) {
                    (salesSummary.totalRevenue - prevRevenue) / prevRevenue * 100
                } else 0.0

                ProductProfitAnalysis(
                    productName = salesSummary.productName,
                    revenue = salesSummary.totalRevenue,
                    unitsSold = salesSummary.totalQty,
                    avgSellPrice = if (salesSummary.totalQty > 0) {
                        salesSummary.totalRevenue / salesSummary.totalQty
                    } else 0.0,
                    cogs = cogs,
                    avgBuyPrice = buyPrice,
                    grossProfit = grossProfit,
                    spoilageLoss = spoilageLoss,
                    netProfit = netProfit,
                    marginPct = margin,
                    revenueChangePct = revenueChange
                )
            }.sortedByDescending { it.netProfit }

            val classified = assignAbcClassification(analyses)

            // Generate recommendations
            val recommendations = mutableListOf<ProductRecommendation>()

            classified.forEach { product ->
                val rec = when {
                    // High margin, growing → increase
                    product.abcClass == "A" && product.marginPct >= 25 && product.revenueChangePct > 0 -> {
                        ProductRecommendation(
                            productName = product.productName,
                            action = "increase",
                            reason = "Margin ni ${ "%.0f".format(product.marginPct)}% na mauzo yameongezeka ${ "%.0f".format(product.revenueChangePct)}%",
                            swahiliMessage = "⬆️ Ongeza ${product.productName} — faida ni kubwa (margin ${ "%.0f".format(product.marginPct)}%) na mauzo yanaongezeka",
                            impactEstimate = (product.netProfit * 0.3).toInt(),
                            confidence = 0.85
                        )
                    }
                    // High margin, stable → maintain/increase
                    product.abcClass == "A" && product.marginPct >= 20 -> {
                        ProductRecommendation(
                            productName = product.productName,
                            action = "increase",
                            reason = "A-bidhaa yenye margin ${ "%.0f".format(product.marginPct)}%",
                            swahiliMessage = "⬆️ Ongeza ${product.productName} — ni A-bidhaa, inaleta faida kubwa",
                            impactEstimate = (product.netProfit * 0.2).toInt(),
                            confidence = 0.75
                        )
                    }
                    // Low margin, high spoilage → decrease
                    product.spoilageLoss > product.netProfit * 0.3 && product.marginPct < 15 -> {
                        ProductRecommendation(
                            productName = product.productName,
                            action = "decrease",
                            reason = "Hasara ya kuoza ni KSh ${"%,.0f".format(product.spoilageLoss)}, margin ni ${ "%.0f".format(product.marginPct)}%",
                            swahiliMessage = "⬇️ Punguza ${product.productName} — ina kuoza kwingi (KSh ${"%,.0f".format(product.spoilageLoss)}) na margin ni ndogo",
                            impactEstimate = (product.spoilageLoss * 0.5).toInt(),
                            confidence = 0.80
                        )
                    }
                    // Very low margin, negative or near-zero profit → drop
                    product.marginPct < 5 && product.netProfit <= 50 -> {
                        ProductRecommendation(
                            productName = product.productName,
                            action = "drop",
                            reason = "Margin ni ${ "%.0f".format(product.marginPct)}% pekee, faida ni KSh ${"%,.0f".format(product.netProfit)}",
                            swahiliMessage = "❌ Acha ${product.productName} — faida ni ndogo sana (${ "%.0f".format(product.marginPct)}%) — haifai shelf space",
                            impactEstimate = 0,
                            confidence = 0.70
                        )
                    }
                    // C-class with high spoilage → decrease
                    product.abcClass == "C" && product.spoilageLoss > 0 -> {
                        ProductRecommendation(
                            productName = product.productName,
                            action = "decrease",
                            reason = "C-bidhaa yenye hasara ya kuoza KSh ${"%,.0f".format(product.spoilageLoss)}",
                            swahiliMessage = "⬇️ Punguza ${product.productName} — ni C-bidhaa na ina kuoza. Fikiria kupunguza order",
                            impactEstimate = (product.spoilageLoss * 0.4).toInt(),
                            confidence = 0.65
                        )
                    }
                    // Medium margin, declining → watch
                    product.revenueChangePct < -10 -> {
                        ProductRecommendation(
                            productName = product.productName,
                            action = "watch",
                            reason = "Mauzo yameshuka ${ "%.0f".format(abs(product.revenueChangePct))}%",
                            swahiliMessage = "⚠️ Angalia ${product.productName} — mauzo yameshuka ${ "%.0f".format(abs(product.revenueChangePct))}%",
                            impactEstimate = 0,
                            confidence = 0.60
                        )
                    }
                    // Default → maintain
                    else -> {
                        ProductRecommendation(
                            productName = product.productName,
                            action = "maintain",
                            reason = "Inafanya vizuri — margin ${ "%.0f".format(product.marginPct)}%",
                            swahiliMessage = "✅ Endelea na ${product.productName} — inafanya vizuri",
                            impactEstimate = 0,
                            confidence = 0.50
                        )
                    }
                }
                recommendations.add(rec)
            }

            // Sort: increase first, then decrease, then others
            val sortOrder = mapOf("increase" to 0, "decrease" to 1, "drop" to 2, "watch" to 3, "maintain" to 4)
            val sorted = recommendations.sortedBy { sortOrder[it.action] ?: 5 }

            val output = buildString {
                appendLine("💡 MAPENDEKEZO YA BIDHAA — Wiki hii")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                val increases = sorted.filter { it.action == "increase" }
                val decreases = sorted.filter { it.action == "decrease" }
                val drops = sorted.filter { it.action == "drop" }
                val watches = sorted.filter { it.action == "watch" }
                val maintains = sorted.filter { it.action == "maintain" }

                if (increases.isNotEmpty()) {
                    appendLine("📈 ONGEZA:")
                    increases.forEach { appendLine("   ${it.swahiliMessage}") }
                    appendLine()
                }

                if (decreases.isNotEmpty()) {
                    appendLine("📉 PUNGUZA:")
                    decreases.forEach { appendLine("   ${it.swahiliMessage}") }
                    appendLine()
                }

                if (drops.isNotEmpty()) {
                    appendLine("🚫 ACHA:")
                    drops.forEach { appendLine("   ${it.swahiliMessage}") }
                    appendLine()
                }

                if (watches.isNotEmpty()) {
                    appendLine("👁️ ANGLIA:")
                    watches.forEach { appendLine("   ${it.swahiliMessage}") }
                    appendLine()
                }

                if (maintains.isNotEmpty()) {
                    appendLine("✅ ENDELEA:")
                    maintains.forEach { appendLine("   ${it.swahiliMessage}") }
                    appendLine()
                }

                // Impact summary
                val totalIncreaseImpact = increases.sumOf { it.impactEstimate }
                val totalDecreaseImpact = decreases.sumOf { it.impactEstimate }
                val totalImpact = totalIncreaseImpact + totalDecreaseImpact

                if (totalImpact > 0) {
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine("💰 Ukipata mapendekezo haya, faida yako inaweza kuongezeka ~KSh ${"%,.0f".format(totalImpact.toDouble())} kwa mwezi")
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "recommendations" to sorted.map { rec ->
                        mapOf(
                            "product" to rec.productName,
                            "action" to rec.action,
                            "reason" to rec.reason,
                            "impact_estimate" to rec.impactEstimate,
                            "confidence" to rec.confidence
                        )
                    },
                    "total_impact_estimate" to (sorted.sumOf { it.impactEstimate })
                ),
                message = output
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get recommendations")
            ToolResult.error(name, "Imeshindikana: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // ACTION 4: HISTORY — Product profit trend over time
    // ──────────────────────────────────────────────

    /**
     * Get profit history for a specific product over multiple periods.
     */
    suspend fun getProductHistory(params: Map<String, String>): ToolResult {
        return try {
            val productName = params["product"]
                ?: return ToolResult.error(name, "Jina la bidhaa linahitajika", "MISSING_PRODUCT")
            val period = params["period"] ?: "weekly"

            val weeksToShow = 4
            val historyData = mutableListOf<PeriodProfitSnapshot>()

            for (i in weeksToShow - 1 downTo 0) {
                val (start, end) = resolvePeriodOffset(period, -i)
                val topProducts = saleDao.getTopProducts(start, end, 50).first()
                val sales = topProducts.find { it.productName.equals(productName, ignoreCase = true) }

                if (sales != null) {
                    val products = productDao.getAllActive().first()
                    val product = products.find { it.name.equals(productName, ignoreCase = true) }
                    val buyPrice = product?.buyPrice ?: 0.0
                    val cogs = sales.totalQty * buyPrice
                    val spoilageLoss = getSpoilageLoss(productName, start, end)
                    val netProfit = sales.totalRevenue - cogs - spoilageLoss
                    val margin = if (sales.totalRevenue > 0) {
                        netProfit / sales.totalRevenue * 100
                    } else 0.0

                    historyData.add(
                        PeriodProfitSnapshot(
                            periodStart = start,
                            periodEnd = end,
                            revenue = sales.totalRevenue,
                            cogs = cogs,
                            spoilageLoss = spoilageLoss,
                            netProfit = netProfit,
                            marginPct = margin,
                            unitsSold = sales.totalQty
                        )
                    )
                } else {
                    historyData.add(
                        PeriodProfitSnapshot(
                            periodStart = start,
                            periodEnd = end,
                            revenue = 0.0,
                            cogs = 0.0,
                            spoilageLoss = 0.0,
                            netProfit = 0.0,
                            marginPct = 0.0,
                            unitsSold = 0.0
                        )
                    )
                }
            }

            // Check if product exists in data
            if (historyData.all { it.revenue == 0.0 }) {
                return ToolResult.success(
                    name,
                    message = "Sikupata mauzo ya $productName kwa kipindi hicho. Hakikisha jina ni sahihi."
                )
            }

            val periodLabel = if (period == "weekly") "wiki" else "mwezi"
            val output = buildString {
                appendLine("📈 Historia ya Faida — $productName")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                historyData.forEachIndexed { i, snapshot ->
                    val weekLabel = when {
                        i == historyData.size - 1 -> "Wiki hii"
                        i == historyData.size - 2 -> "Wiki iliyopita"
                        else -> "${historyData.size - i} wiki zilizopita"
                    }
                    val arrow = if (i > 0) {
                        val prev = historyData[i - 1]
                        when {
                            snapshot.netProfit > prev.netProfit -> " ⬆️"
                            snapshot.netProfit < prev.netProfit -> " ⬇️"
                            else -> " ➡️"
                        }
                    } else ""

                    appendLine("  $weekLabel: Mauzo KSh ${"%,.0f".format(snapshot.revenue)} | Faida KSh ${"%,.0f".format(snapshot.netProfit)} | Margin ${ "%.0f".format(snapshot.marginPct)}%$arrow")
                    if (snapshot.spoilageLoss > 0) {
                        appendLine("   ⚠️  Kuoza: KSh ${"%,.0f".format(snapshot.spoilageLoss)}")
                    }
                }

                // Trend summary
                val firstWithData = historyData.firstOrNull { it.revenue > 0 }
                val latest = historyData.last()
                if (firstWithData != null && latest.revenue > 0 && firstWithData != latest) {
                    val profitChange = latest.netProfit - firstWithData.netProfit
                    val trend = if (profitChange > 0) "inaongezeka ✅" else if (profitChange < 0) "inashuka ⚠️" else "imara ➡️"
                    appendLine()
                    appendLine("📊 Mkazo: Faida ya $productName $trend")
                    appendLine("   Mabadiliko: KSh ${"%,.0f".format(profitChange)} kwa wiki $weeksToShow")
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "product" to productName,
                    "history" to historyData.map {
                        mapOf(
                            "revenue" to it.revenue,
                            "net_profit" to it.netProfit,
                            "margin_pct" to it.marginPct,
                            "spoilage_loss" to it.spoilageLoss
                        )
                    }
                ),
                message = output
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get product history")
            ToolResult.error(name, "Imeshindikana: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // ACTION 5: COMPARE — Period-over-period comparison
    // ──────────────────────────────────────────────

    /**
     * Compare current period profit vs. previous period.
     */
    suspend fun comparePeriods(params: Map<String, String>): ToolResult {
        return try {
            val period = params["period"] ?: "weekly"
            val (currentStart, currentEnd) = resolvePeriod(period)
            val (prevStart, prevEnd) = resolvePreviousPeriod(period)

            val currentProducts = saleDao.getTopProducts(currentStart, currentEnd, 20).first()
            val prevProducts = saleDao.getTopProducts(prevStart, prevEnd, 20).first()

            if (currentProducts.isEmpty() && prevProducts.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "Hakuna data ya kulinganisha. Rekodi mauzo kwanza! 📊"
                )
            }

            val products = productDao.getAllActive().first()
            val productMap = products.associateBy { it.name.lowercase() }

            // Build comparison for all products that appeared in either period
            val allProductNames = (currentProducts.map { it.productName } + prevProducts.map { it.productName })
                .distinct()

            val comparisons = allProductNames.map { name ->
                val current = currentProducts.find { it.productName.equals(name, ignoreCase = true) }
                val prev = prevProducts.find { it.productName.equals(name, ignoreCase = true) }

                val product = productMap[name.lowercase()]
                val buyPrice = product?.buyPrice ?: 0.0

                val currentRevenue = current?.totalRevenue ?: 0.0
                val currentCogs = (current?.totalQty ?: 0.0) * buyPrice
                val currentSpoilage = getSpoilageLoss(name, currentStart, currentEnd)
                val currentNetProfit = currentRevenue - currentCogs - currentSpoilage

                val prevRevenue = prev?.totalRevenue ?: 0.0
                val prevCogs = (prev?.totalQty ?: 0.0) * buyPrice
                val prevSpoilage = getSpoilageLoss(name, prevStart, prevEnd)
                val prevNetProfit = prevRevenue - prevCogs - prevSpoilage

                val revenueChange = if (prevRevenue > 0) (currentRevenue - prevRevenue) / prevRevenue * 100 else 0.0
                val profitChange = if (prevNetProfit > 0) (currentNetProfit - prevNetProfit) / prevNetProfit * 100 else 0.0

                ProductComparison(
                    productName = name,
                    currentRevenue = currentRevenue,
                    currentNetProfit = currentNetProfit,
                    prevRevenue = prevRevenue,
                    prevNetProfit = prevNetProfit,
                    revenueChangePct = revenueChange,
                    profitChangePct = profitChange
                )
            }.sortedByDescending { it.currentNetProfit }

            val periodLabel = formatPeriodLabel(period)

            val output = buildString {
                appendLine("🔄 LINGANISHO — $periodLabel")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                comparisons.forEach { comp ->
                    val revArrow = if (comp.revenueChangePct > 0) "📈" else if (comp.revenueChangePct < 0) "📉" else "➡️"
                    val profArrow = if (comp.profitChangePct > 0) "📈" else if (comp.profitChangePct < 0) "📉" else "➡️"

                    appendLine("${comp.productName}:")
                    appendLine("   Mauzo: KSh ${"%,.0f".format(comp.currentRevenue)} ${if (comp.revenueChangePct > 0) "+" else ""}${ "%.0f".format(comp.revenueChangePct)}% $revArrow")
                    appendLine("   Faida: KSh ${"%,.0f".format(comp.currentNetProfit)} ${if (comp.profitChangePct > 0) "+" else ""}${ "%.0f".format(comp.profitChangePct)}% $profArrow")
                    appendLine()
                }

                // Overall summary
                val totalCurrentProfit = comparisons.sumOf { it.currentNetProfit }
                val totalPrevProfit = comparisons.sumOf { it.prevNetProfit }
                val overallChange = if (totalPrevProfit > 0) {
                    (totalCurrentProfit - totalPrevProfit) / totalPrevProfit * 100
                } else 0.0

                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                val overallArrow = if (overallChange > 0) "📈" else if (overallChange < 0) "📉" else "➡️"
                appendLine("JUMLA: Faida KSh ${"%,.0f".format(totalCurrentProfit)} (${if (overallChange > 0) "+" else ""}${ "%.0f".format(overallChange)}%) $overallArrow")

                // Swahili summary
                appendLine()
                when {
                    overallChange > 10 -> appendLine("✅ Vizuri sana! Biashara yako ya bidhaa inakua.")
                    overallChange > 0 -> appendLine("✅ Nzuri. Biashara yako inapanda kidogo.")
                    overallChange > -10 -> appendLine("➡️ Imara. Hakuna mabadiliko makubwa.")
                    else -> appendLine("⚠️ Tahadhari! Faida imeshuka — angalia bidhaa zisizofaa.")
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "comparisons" to comparisons,
                    "overall_change_pct" to overallChange,
                    "total_current_profit" to comparisons.sumOf { it.currentNetProfit },
                    "total_prev_profit" to comparisons.sumOf { it.prevNetProfit }
                ),
                message = output
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to compare periods")
            ToolResult.error(name, "Imeshindikana: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // ACTION 6: MARGINS — Detailed margin analysis
    // ──────────────────────────────────────────────

    /**
     * Get detailed margin analysis for all products or a specific product.
     */
    suspend fun getMargins(params: Map<String, String>): ToolResult {
        return try {
            val productName = params["product"]
            val period = params["period"] ?: "weekly"
            val (start, end) = resolvePeriod(period)

            val topProducts = saleDao.getTopProducts(start, end, 20).first()
            if (topProducts.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "Hakuna mauzo ya kurekodiwa. Anza kuuza! 📊"
                )
            }

            val products = productDao.getAllActive().first()
            val productMap = products.associateBy { it.name.lowercase() }

            // Filter to specific product if provided
            val filteredProducts = if (productName != null) {
                topProducts.filter { it.productName.equals(productName, ignoreCase = true) }
            } else {
                topProducts
            }

            if (filteredProducts.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "Sikupata mauzo ya ${productName ?: "bidhaa yoyote"}. Hakikisha jina ni sahihi."
                )
            }

            val marginAnalyses = filteredProducts.map { salesSummary ->
                val product = productMap[salesSummary.productName.lowercase()]
                val buyPrice = product?.buyPrice ?: 0.0
                val sellPrice = if (salesSummary.totalQty > 0) {
                    salesSummary.totalRevenue / salesSummary.totalQty
                } else {
                    product?.sellPrice ?: 0.0
                }
                val cogs = salesSummary.totalQty * buyPrice
                val grossProfit = salesSummary.totalRevenue - cogs
                val spoilageLoss = getSpoilageLoss(salesSummary.productName, start, end)
                val netProfit = grossProfit - spoilageLoss
                val grossMargin = if (salesSummary.totalRevenue > 0) {
                    grossProfit / salesSummary.totalRevenue * 100
                } else 0.0
                val netMargin = if (salesSummary.totalRevenue > 0) {
                    netProfit / salesSummary.totalRevenue * 100
                } else 0.0
                val markupPct = if (buyPrice > 0) {
                    (sellPrice - buyPrice) / buyPrice * 100
                } else 0.0

                MarginAnalysis(
                    productName = salesSummary.productName,
                    avgSellPrice = sellPrice,
                    avgBuyPrice = buyPrice,
                    markupPct = markupPct,
                    grossMarginPct = grossMargin,
                    netMarginPct = netMargin,
                    spoilageImpactPct = if (salesSummary.totalRevenue > 0) {
                        spoilageLoss / salesSummary.totalRevenue * 100
                    } else 0.0,
                    revenue = salesSummary.totalRevenue,
                    netProfit = netProfit,
                    spoilageLoss = spoilageLoss
                )
            }.sortedByDescending { it.netMarginPct }

            val periodLabel = formatPeriodLabel(period)

            val output = buildString {
                appendLine("💰 Uchambuzi wa Margin — $periodLabel")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                marginAnalyses.forEach { m ->
                    val marginStatus = when {
                        m.netMarginPct >= 30 -> "🟢 Nzuri sana"
                        m.netMarginPct >= 20 -> "🟡 Nzuri"
                        m.netMarginPct >= 10 -> "🟠 Wastani"
                        else -> "🔴 Chini"
                    }

                    appendLine("${m.productName} — $marginStatus")
                    appendLine("   Bei ya kununua: KSh ${"%,.0f".format(m.avgBuyPrice)}")
                    appendLine("   Bei ya kuuza:   KSh ${"%,.0f".format(m.avgSellPrice)}")
                    appendLine("   Markup:         ${ "%.0f".format(m.markupPct)}%")
                    appendLine("   Margin ya brutto: ${ "%.0f".format(m.grossMarginPct)}%")
                    appendLine("   Margin ya neti:   ${ "%.0f".format(m.netMarginPct)}%")
                    if (m.spoilageImpactPct > 0) {
                        appendLine("   ⚠️  Athari ya kuoza: -${ "%.1f".format(m.spoilageImpactPct)}% ya mauzo")
                    }
                    appendLine()
                }

                // Advice based on margins
                val lowMarginProducts = marginAnalyses.filter { it.netMarginPct < 10 }
                val highMarginProducts = marginAnalyses.filter { it.netMarginPct >= 25 }

                if (lowMarginProducts.isNotEmpty() || highMarginProducts.isNotEmpty()) {
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine("💡 Ushauri:")
                    if (highMarginProducts.isNotEmpty()) {
                        appendLine("   ✅ Zingatia: ${highMarginProducts.joinToString(", ") { it.productName }} — margin kubwa!")
                    }
                    if (lowMarginProducts.isNotEmpty()) {
                        appendLine("   ⚠️ Angalia: ${lowMarginProducts.joinToString(", ") { it.productName }} — margin ndogo. Fikiria kupunguza bei ya kununua au kuongeza bei ya kuuza")
                    }
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "margins" to marginAnalyses.map {
                        mapOf(
                            "product" to it.productName,
                            "avg_buy_price" to it.avgBuyPrice,
                            "avg_sell_price" to it.avgSellPrice,
                            "markup_pct" to it.markupPct,
                            "gross_margin_pct" to it.grossMarginPct,
                            "net_margin_pct" to it.netMarginPct,
                            "spoilage_impact_pct" to it.spoilageImpactPct
                        )
                    }
                ),
                message = output
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get margins")
            ToolResult.error(name, "Imeshindikana: ${e.message}", "DB_ERROR")
        }
    }

    // ── Academic Formula: Simple LP for Product Allocation (OR) ─────────────

    /**
     * Solve a simple linear program for optimal product mix.
     * Maximizes total profit subject to a budget constraint:
     *   max  Σ (profitPerUnit_i × quantity_i)
     *   s.t. Σ (costPerUnit_i × quantity_i) ≤ budget
     *        quantity_i ≥ 0
     *
     * Uses a greedy algorithm (highest profit-to-cost ratio first)
     * which is optimal for single-constraint LP (continuous relaxation).
     *
     * @param products List of Triple(productName, profitPerUnit, costPerUnit)
     * @param constraints Map with keys: "budget" (Double), optionally "maxPerProduct" (Int)
     * @return Map of productName → optimalQuantity to purchase
     */
    fun calculateOptimalMix(
        products: List<Triple<String, Double, Double>>,
        constraints: Map<String, Any>
    ): Map<String, Double> {
        val budget = (constraints["budget"] as? Double)
            ?: (constraints["budget"] as? Number)?.toDouble()
            ?: return emptyMap()
        val maxPerProduct = (constraints["maxPerProduct"] as? Int)
            ?: (constraints["maxPerProduct"] as? Number)?.toInt()
            ?: Int.MAX_VALUE

        if (products.isEmpty() || budget <= 0) return emptyMap()

        // Filter to products with positive profit and cost, then sort by profit/cost ratio descending
        val ranked = products
            .filter { it.second > 0 && it.third > 0 }
            .sortedByDescending { it.second / it.third }

        var remainingBudget = budget
        val allocation = mutableMapOf<String, Double>()

        for ((name, profitPerUnit, costPerUnit) in ranked) {
            if (remainingBudget <= 0) break
            // Max units from budget
            val maxFromBudget = remainingBudget / costPerUnit
            // Apply per-product cap (continuous relaxation)
            val quantity = minOf(maxFromBudget, maxPerProduct.toDouble())
            if (quantity > 0) {
                allocation[name] = quantity
                remainingBudget -= quantity * costPerUnit
            }
        }

        return allocation
    }

    // ──────────────────────────────────────────────
    // ABC CLASSIFICATION
    // ──────────────────────────────────────────────

    /**
     * Assign ABC classification based on cumulative profit contribution.
     * A = top 20% of products generating ~80% of profit
     * B = next 30% of products
     * C = bottom 50% of products (consider reducing or dropping)
     */
    private fun assignAbcClassification(
        products: List<ProductProfitAnalysis>
    ): List<ProductProfitAnalysis> {
        if (products.isEmpty()) return emptyList()

        val totalProfit = products.sumOf { it.netProfit }.coerceAtLeast(1.0)
        val sorted = products.sortedByDescending { it.netProfit }

        var cumulativeProfit = 0.0
        return sorted.map { product ->
            cumulativeProfit += product.netProfit
            val cumulativePct = cumulativeProfit / totalProfit * 100

            val abcClass = when {
                cumulativePct <= 80 -> "A"
                cumulativePct <= 95 -> "B"
                else -> "C"
            }

            product.copy(abcClass = abcClass)
        }
    }

    // ──────────────────────────────────────────────
    // SPOILAGE INTEGRATION
    // ──────────────────────────────────────────────

    /**
     * Get spoilage loss for a product within a time range.
     * Queries StockMovementDao for movements with type = "spoilage".
     * Multiplies spoiled quantity by buy price to get loss value.
     */
    private suspend fun getSpoilageLoss(productName: String, start: Long, end: Long): Double {
        return try {
            val products = productDao.getAllActive().first()
            val product = products.find { it.name.equals(productName, ignoreCase = true) }
                ?: return 0.0

            val movements = stockMovementDao.getMovementsBetween(start, end).first()
            val spoilageMovements = movements.filter {
                it.productId == product.id && it.type.equals("spoilage", ignoreCase = true)
            }

            val totalSpoiledQty = spoilageMovements.sumOf { abs(it.quantity) }
            totalSpoiledQty * product.buyPrice
        } catch (e: Exception) {
            Timber.w(e, "Failed to get spoilage loss for $productName")
            0.0
        }
    }

    // ──────────────────────────────────────────────
    // PERIOD HELPERS
    // ──────────────────────────────────────────────

    /**
     * Resolve period string to (startTimestamp, endTimestamp).
     */
    private fun resolvePeriod(period: String): Pair<Long, Long> {
        return when (period.lowercase()) {
            "daily" -> {
                val start = DateTimeUtil.startOfDay()
                val end = DateTimeUtil.endOfDay()
                Pair(start, end)
            }
            "weekly" -> {
                val start = DateTimeUtil.startOfWeek()
                val end = System.currentTimeMillis()
                Pair(start, end)
            }
            "monthly" -> {
                val cal = java.util.Calendar.getInstance()
                cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                val end = System.currentTimeMillis()
                Pair(start, end)
            }
            else -> {
                val start = DateTimeUtil.startOfWeek()
                val end = System.currentTimeMillis()
                Pair(start, end)
            }
        }
    }

    /**
     * Resolve previous period for comparison.
     */
    private fun resolvePreviousPeriod(period: String): Pair<Long, Long> {
        return when (period.lowercase()) {
            "daily" -> {
                val start = DateTimeUtil.startOfDay() - 24 * 60 * 60 * 1000L
                val end = DateTimeUtil.startOfDay() - 1
                Pair(start, end)
            }
            "weekly" -> {
                val thisWeekStart = DateTimeUtil.startOfWeek()
                val start = thisWeekStart - 7 * 24 * 60 * 60 * 1000L
                val end = thisWeekStart - 1
                Pair(start, end)
            }
            "monthly" -> {
                val cal = java.util.Calendar.getInstance()
                cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                val thisMonthStart = cal.timeInMillis
                cal.add(java.util.Calendar.MONTH, -1)
                val start = cal.timeInMillis
                val end = thisMonthStart - 1
                Pair(start, end)
            }
            else -> {
                val thisWeekStart = DateTimeUtil.startOfWeek()
                val start = thisWeekStart - 7 * 24 * 60 * 60 * 1000L
                val end = thisWeekStart - 1
                Pair(start, end)
            }
        }
    }

    /**
     * Resolve period with offset (for history).
     * offset = 0 is current period, -1 is previous, etc.
     */
    private fun resolvePeriodOffset(period: String, offset: Int): Pair<Long, Long> {
        val (currentStart, _) = resolvePeriod(period)
        val periodMs = when (period.lowercase()) {
            "daily" -> 24 * 60 * 60 * 1000L
            "weekly" -> 7 * 24 * 60 * 60 * 1000L
            "monthly" -> 30 * 24 * 60 * 60 * 1000L
            else -> 7 * 24 * 60 * 60 * 1000L
        }
        val start = currentStart + (offset * periodMs)
        val end = start + periodMs - 1
        return Pair(start, end)
    }

    private fun formatPeriodLabel(period: String): String {
        return when (period.lowercase()) {
            "daily" -> "Leo (${DateTimeUtil.today()})"
            "weekly" -> "Wiki hii"
            "monthly" -> "Mwezi huu"
            else -> "Kipindi hichi"
        }
    }

    // ──────────────────────────────────────────────
    // DATA CLASSES
    // ──────────────────────────────────────────────

    data class ProductProfitAnalysis(
        val productName: String,
        val revenue: Double,
        val unitsSold: Double,
        val avgSellPrice: Double,
        val cogs: Double,
        val avgBuyPrice: Double,
        val grossProfit: Double,
        val spoilageLoss: Double,
        val netProfit: Double,
        val marginPct: Double,
        val abcClass: String = "C",
        val revenueChangePct: Double = 0.0
    )

    data class ProductRecommendation(
        val productName: String,
        val action: String, // increase, decrease, drop, maintain, watch
        val reason: String,
        val swahiliMessage: String,
        val impactEstimate: Int,
        val confidence: Double
    )

    data class PeriodProfitSnapshot(
        val periodStart: Long,
        val periodEnd: Long,
        val revenue: Double,
        val cogs: Double,
        val spoilageLoss: Double,
        val netProfit: Double,
        val marginPct: Double,
        val unitsSold: Double
    )

    data class ProductComparison(
        val productName: String,
        val currentRevenue: Double,
        val currentNetProfit: Double,
        val prevRevenue: Double,
        val prevNetProfit: Double,
        val revenueChangePct: Double,
        val profitChangePct: Double
    )

    data class MarginAnalysis(
        val productName: String,
        val avgSellPrice: Double,
        val avgBuyPrice: Double,
        val markupPct: Double,
        val grossMarginPct: Double,
        val netMarginPct: Double,
        val spoilageImpactPct: Double,
        val revenue: Double,
        val netProfit: Double,
        val spoilageLoss: Double
    )
}
