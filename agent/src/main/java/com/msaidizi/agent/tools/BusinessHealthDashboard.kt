package com.msaidizi.agent.tools

import com.msaidizi.core.database.CustomerDao
import com.msaidizi.core.database.DailySummaryDao
import com.msaidizi.core.database.DebtDao
import com.msaidizi.core.database.ExpenseDao
import com.msaidizi.core.database.ProductDao
import com.msaidizi.core.database.SaleDao
import com.msaidizi.core.database.StockMovementDao
import com.msaidizi.core.database.UserProfileDao
import com.msaidizi.core.util.DateTimeUtil
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BusinessHealthDashboard — One-screen business health cockpit.
 *
 * A single-screen, glanceable view of business health for informal workers.
 * Answers: "Biashara yangu iko aje?" (How is my business doing RIGHT NOW?)
 *
 * Features:
 *  - Traffic-light system: 🟢 (good), 🟡 (watch), 🔴 (danger)
 *  - Trend arrows: ↑ up, → stable, ↓ down for every metric
 *  - Plain language, KSh amounts, no accounting jargon
 *  - 6 actions: dashboard, summary, trends, alerts, compare, forecast
 *  - Swahili-first output for voice delivery
 */
@Singleton
class BusinessHealthDashboard @Inject constructor(
    private val saleDao: SaleDao,
    private val expenseDao: ExpenseDao,
    private val debtDao: DebtDao,
    private val productDao: ProductDao,
    private val dailySummaryDao: DailySummaryDao,
    private val stockMovementDao: StockMovementDao,
    private val customerDao: CustomerDao,
    private val userProfileDao: UserProfileDao
) : Tool {

    override val name = "business_health_dashboard"
    override val description =
        "One-screen business health: revenue, profit, growth, top products, cash flow, alerts"

    override val argsSchema = argSchema {
        enum(
            "action",
            "Dashboard action to perform",
            listOf("dashboard", "summary", "trends", "alerts", "compare", "forecast"),
            required = false
        )
        string("date", "Date for dashboard (YYYY-MM-DD or 'today')", required = false, default = "today")
        enum(
            "severity",
            "Alert severity filter",
            listOf("all", "warning", "critical"),
            required = false,
            default = "all"
        )
        string("period_a", "First period for comparison (e.g. '2026-06' or 'last_month')", required = false)
        string("period_b", "Second period for comparison (e.g. '2026-07' or 'this_month')", required = false)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "dashboard"
        return when (action.lowercase()) {
            "dashboard" -> buildDashboard(params)
            "summary" -> generateDailyBriefing()
            "trends" -> buildTrends()
            "alerts" -> buildAlerts(params)
            "compare" -> buildComparison(params)
            "forecast" -> buildForecast()
            else -> ToolResult.error(name, "Hii hatua haipo: $action", "INVALID_ACTION")
        }
    }

    // ════════════════════════════════════════════════════════════
    //  DATA CLASSES — internal snapshot models
    // ════════════════════════════════════════════════════════════

    private data class PeriodMetrics(
        val revenue: Double,
        val costs: Double,
        val profit: Double,
        val margin: Double,
        val txnCount: Int
    )

    private data class CashPosition(
        val cashOnHand: Double,
        val runwayDays: Int,
        val outstandingReceivables: Double
    )

    private data class ProductSnapshot(
        val name: String,
        val dailyProfit: Double,
        val margin: Double,
        val status: String // "green", "yellow", "red"
    )

    private data class AlertItem(
        val severity: String,   // "warning", "critical"
        val metric: String,
        val message: String,
        val messageSw: String   // Swahili
    )

    // ════════════════════════════════════════════════════════════
    //  ACTION 1: dashboard.get — Full one-screen dashboard
    // ════════════════════════════════════════════════════════════

    private suspend fun buildDashboard(params: Map<String, String>): ToolResult {
        return try {
            val now = System.currentTimeMillis()
            val todayStart = DateTimeUtil.startOfDay(now)
            val todayEnd = DateTimeUtil.endOfDay(now)
            val weekStart = todayStart - 6 * 86_400_000L
            val monthStart = DateTimeUtil.startOfMonth()

            // ── Period metrics ──
            val today = fetchPeriodMetrics(todayStart, todayEnd)
            val week = fetchPeriodMetrics(weekStart, todayEnd)
            val month = fetchPeriodMetrics(monthStart, todayEnd)

            // ── Previous period metrics (for trend arrows) ──
            val prevWeekStart = weekStart - 7 * 86_400_000L
            val prevWeekEnd = weekStart - 1
            val prevWeek = fetchPeriodMetrics(prevWeekStart, prevWeekEnd)

            val prevMonthStart = getPreviousMonthStart()
            val prevMonthEnd = monthStart - 1
            val prevMonth = fetchPeriodMetrics(prevMonthStart, prevMonthEnd)

            // ── Trends ──
            val weekRevenueTrend = calcTrend(week.revenue, prevWeek.revenue)
            val weekProfitTrend = calcTrend(week.profit, prevWeek.profit)
            val monthRevenueTrend = calcTrend(month.revenue, prevMonth.revenue)
            val monthProfitTrend = calcTrend(month.profit, prevMonth.profit)

            // ── Cash position ──
            val cashPosition = fetchCashPosition()

            // ── Top products ──
            val topProducts = fetchTopProducts(weekStart, todayEnd, 3)

            // ── Watch list (low margin / spoilage) ──
            val watchList = fetchWatchList(weekStart, todayEnd)

            // ── Alerts ──
            val alerts = gatherAlerts(today, week, cashPosition)

            // ── Alama score (approximate from recent data) ──
            val alamaScore = estimateAlamaScore()

            // ── Build dashboard text ──
            val businessName = userProfileDao.getProfileOnce()?.userName?.ifBlank { "Biashara yako" } ?: "Biashara yako"
            val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date())

            val dashboard = buildString {
                appendLine("📊 BIASHARA — $businessName        📅 $dateStr")
                appendLine("─".repeat(50))
                appendLine()

                // Revenue / Profit rows with traffic lights
                appendLine("💰 MAPATO & FAIDA")
                appendLine(
                    "  Leo:    ${fmt(today.revenue)} sales, " +
                        "${fmt(today.profit)} faida ${trafficLight(today.margin, 20.0, 10.0)} " +
                        "margin ${pct(today.margin)}"
                )
                appendLine(
                    "  Wiki:   ${fmt(week.revenue)} sales, " +
                        "${fmt(week.profit)} faida ${trafficLight(week.margin, 20.0, 10.0)} " +
                        "margin ${pct(week.margin)}  ${trendArrow(weekProfitTrend)}"
                )
                appendLine(
                    "  Mwezi:  ${fmt(month.revenue)} sales, " +
                        "${fmt(month.profit)} faida ${trafficLight(month.margin, 20.0, 10.0)} " +
                        "margin ${pct(month.margin)}  ${trendArrow(monthProfitTrend)}"
                )
                appendLine()

                // Cash position
                appendLine("💵 HALI YA PESA")
                appendLine(
                    "  Pesa mkononi: ${fmt(cashPosition.cashOnHand)}   " +
                        "Runway: siku ${cashPosition.runwayDays}   " +
                        "Deni: ${fmt(cashPosition.outstandingReceivables)}"
                )
                if (cashPosition.outstandingReceivables > 0) {
                    appendLine("  ${trafficLight(cashPosition.outstandingReceivables, week.revenue * 0.1, week.revenue * 0.25)} " +
                        "Deni ni ${pct(cashPosition.outstandingReceivables / week.revenue.coerceAtLeast(1.0) * 100)} ya wiki")
                }
                appendLine()

                // Top products
                if (topProducts.isNotEmpty()) {
                    appendLine("🏆 BIDHAA BORA")
                    topProducts.forEachIndexed { i, p ->
                        appendLine("  ${i + 1}. ${p.name} — ${fmt(p.dailyProfit)}/siku  ${trafficLight(p.margin, 25.0, 15.0)}")
                    }
                    appendLine()
                }

                // Watch list
                if (watchList.isNotEmpty()) {
                    appendLine("⚠️ ANGALIA")
                    watchList.forEach { p ->
                        appendLine("  ${trafficLight(p.margin, 15.0, 8.0)} ${p.name} — margin ${pct(p.margin)}")
                    }
                    appendLine()
                }

                // Growth headline
                val overallTrend = monthProfitTrend
                val trendWord = when {
                    overallTrend > 5 -> "inakua ✅"
                    overallTrend < -5 -> "inadhoofika ❌"
                    else -> "imara ➡️"
                }
                appendLine("📈 UKUAJI: Mapato ${trendArrow(monthRevenueTrend)} ${pct(kotlin.math.abs(monthRevenueTrend))} " +
                    "| Faida ${trendArrow(monthProfitTrend)} ${pct(kotlin.math.abs(monthProfitTrend))} " +
                    "| Alama: $alamaScore")
                appendLine("  Biashara $trendWord")
                appendLine()

                // Alerts
                if (alerts.isNotEmpty()) {
                    appendLine("🔔 TAHAHADHARI")
                    alerts.take(3).forEach { a ->
                        appendLine("  ${if (a.severity == "critical") "🔴" else "🟡"} ${a.messageSw}")
                    }
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "today" to mapOf("revenue" to today.revenue, "costs" to today.costs, "profit" to today.profit, "margin" to today.margin),
                    "week" to mapOf("revenue" to week.revenue, "costs" to week.costs, "profit" to week.profit, "margin" to week.margin),
                    "month" to mapOf("revenue" to month.revenue, "costs" to month.costs, "profit" to month.profit, "margin" to month.margin),
                    "cash" to cashPosition,
                    "topProducts" to topProducts,
                    "alerts" to alerts,
                    "alamaScore" to alamaScore,
                    "trends" to mapOf("weekRevenue" to weekRevenueTrend, "weekProfit" to weekProfitTrend, "monthRevenue" to monthRevenueTrend, "monthProfit" to monthProfitTrend)
                ),
                message = dashboard
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to build dashboard")
            ToolResult.error(name, "Imeshindwa: ${e.message}", "DB_ERROR")
        }
    }

    // ════════════════════════════════════════════════════════════
    //  ACTION 2: summary — Daily auto-briefing (Swahili voice)
    // ════════════════════════════════════════════════════════════

    private suspend fun generateDailyBriefing(): ToolResult {
        return try {
            val now = System.currentTimeMillis()
            val todayStart = DateTimeUtil.startOfDay(now)
            val todayEnd = DateTimeUtil.endOfDay(now)
            val yesterdayStart = todayStart - 86_400_000L
            val yesterdayEnd = todayStart - 1

            val today = fetchPeriodMetrics(todayStart, todayEnd)
            val yesterday = fetchPeriodMetrics(yesterdayStart, yesterdayEnd)
            val salesChange = calcTrend(today.revenue, yesterday.revenue)
            val profitChange = calcTrend(today.profit, yesterday.profit)

            val topProducts = saleDao.getTopProducts(todayStart, todayEnd, 3).first()
            val lowStock = productDao.getLowStock().first()

            val businessName = userProfileDao.getProfileOnce()?.userName?.ifBlank { "Mwenye biashara" } ?: "Mwenye biashara"
            val greeting = DateTimeUtil.getGreeting("sw")

            val briefing = buildString {
                appendLine("$greeting, $businessName!")
                appendLine()
                appendLine("Leo umefanya sales za ${fmt(today.revenue)}.")
                appendLine("Umepata faida ya ${fmt(today.profit)} — margin ni ${pct(today.margin)}.")

                if (salesChange != 0.0) {
                    val comparison = if (salesChange > 0) "zimepanda" else "zimeshuka"
                    appendLine("Sales $comparison ${pct(kotlin.math.abs(salesChange))} ikilinganishwa na jana.")
                }

                if (today.txnCount > 0) {
                    appendLine("Umefanya miamala ${today.txnCount} leo.")
                }

                if (topProducts.isNotEmpty()) {
                    appendLine()
                    appendLine("Bidhaa bora leo:")
                    topProducts.forEachIndexed { i, p ->
                        appendLine("  ${i + 1}. ${p.productName} — ${fmt(p.totalRevenue)}")
                    }
                }

                if (lowStock.isNotEmpty()) {
                    appendLine()
                    appendLine("⚠️ Tahadhari: stock ya chini — ${lowStock.joinToString(", ") { it.name }}")
                }

                // Week context
                val weekStart = todayStart - 6 * 86_400_000L
                val week = fetchPeriodMetrics(weekStart, todayEnd)
                val prevWeekStart = weekStart - 7 * 86_400_000L
                val prevWeekEnd = weekStart - 1
                val prevWeek = fetchPeriodMetrics(prevWeekStart, prevWeekEnd)
                val weekProfitTrend = calcTrend(week.profit, prevWeek.profit)

                if (weekProfitTrend > 5) {
                    appendLine()
                    appendLine("📈 Wiki hii ni vizuri — faida imepanda ${pct(weekProfitTrend)}!")
                } else if (weekProfitTrend < -5) {
                    appendLine()
                    appendLine("📉 Wiki hii faida imeshuka ${pct(kotlin.math.abs(weekProfitTrend))}. Fikiria kupunguza gharama.")
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "today" to mapOf("revenue" to today.revenue, "profit" to today.profit, "margin" to today.margin),
                    "salesChange" to salesChange,
                    "profitChange" to profitChange
                ),
                message = briefing
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate daily briefing")
            ToolResult.error(name, "Imeshindwa: ${e.message}", "DB_ERROR")
        }
    }

    // ════════════════════════════════════════════════════════════
    //  ACTION 3: trends — Multi-period trend view
    // ════════════════════════════════════════════════════════════

    private suspend fun buildTrends(): ToolResult {
        return try {
            val summaries = dailySummaryDao.getRecentSummaries(30).first()

            if (summaries.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "Hakuna data ya kutosha. Anza kurekodi mauzo yako kila siku! 📝"
                )
            }

            // Group by week (last 4 weeks)
            val calendar = Calendar.getInstance()
            val weeklyData = summaries.groupBy { summary ->
                calendar.timeInMillis = summary.createdAt
                calendar.get(Calendar.WEEK_OF_YEAR)
            }.entries.sortedBy { it.key }.takeLast(4)

            // Calculate week-over-week trends
            val weeklyTrends = weeklyData.map { (weekNum, days) ->
                val revenue = days.sumOf { it.totalSales }
                val costs = days.sumOf { it.totalExpenses }
                val profit = days.sumOf { it.profit }
                val margin = if (revenue > 0) profit / revenue * 100 else 0.0
                val txCount = days.sumOf { it.transactionCount }
                mapOf(
                    "week" to weekNum,
                    "revenue" to revenue,
                    "costs" to costs,
                    "profit" to profit,
                    "margin" to margin,
                    "transactions" to txCount,
                    "days" to days.size
                )
            }

            // Calculate changes between consecutive weeks
            val trendChanges = weeklyTrends.zipWithNext().map { (prev, curr) ->
                val revenueChange = calcTrend(curr["revenue"] as Double, prev["revenue"] as Double)
                val profitChange = calcTrend(curr["profit"] as Double, prev["profit"] as Double)
                mapOf(
                    "revenueChange" to revenueChange,
                    "profitChange" to profitChange,
                    "trend" to when {
                        profitChange > 5 -> "up"
                        profitChange < -5 -> "down"
                        else -> "stable"
                    }
                )
            }

            // Build trend summary
            val trendText = buildString {
                appendLine("📊 MIENENDEKO YA BIASHARA (Wiki 4)")
                appendLine("─".repeat(40))
                appendLine()

                weeklyTrends.forEachIndexed { i, week ->
                    val arrow = if (i < trendChanges.size) {
                        when (trendChanges[i]["trend"]) {
                            "up" -> "⬆️"
                            "down" -> "⬇️"
                            else -> "➡️"
                        }
                    } else ""

                    appendLine("Wiki ${week["week"]}:")
                    appendLine("  Sales: ${fmt(week["revenue"] as Double)} | " +
                        "Faida: ${fmt(week["profit"] as Double)} | " +
                        "Margin: ${pct(week["margin"] as Double)} $arrow")
                    appendLine("  Miamala: ${week["transactions"]} | Siku: ${week["days"]}")

                    if (i < trendChanges.size) {
                        val change = trendChanges[i]
                        val revChange = change["revenueChange"] as Double
                        val profChange = change["profitChange"] as Double
                        appendLine("  Mapato: ${trendArrow(revChange)} ${pct(kotlin.math.abs(revChange))} | " +
                            "Faida: ${trendArrow(profChange)} ${pct(kotlin.math.abs(profChange))}")
                    }
                    appendLine()
                }

                // Overall direction
                if (weeklyTrends.size >= 2) {
                    val firstWeekProfit = weeklyTrends.first()["profit"] as Double
                    val lastWeekProfit = weeklyTrends.last()["profit"] as Double
                    val overallChange = calcTrend(lastWeekProfit, firstWeekProfit)

                    appendLine("─".repeat(40))
                    when {
                        overallChange > 10 -> appendLine("✅ Biashara yako INAKUA! Faida imepanda ${pct(overallChange)} kwa wiki 4.")
                        overallChange > 0 -> appendLine("📈 Biashara inakua polepole — faida imepanda ${pct(overallChange)}.")
                        overallChange > -10 -> appendLine("➡️ Biashara imara — mabadiliko madogo tu (${pct(overallChange)}).")
                        else -> appendLine("⚠️ Biashara inadhoofika — faida imeshuka ${pct(kotlin.math.abs(overallChange))}. Angalia gharama.")
                    }
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "weeklyTrends" to weeklyTrends,
                    "trendChanges" to trendChanges
                ),
                message = trendText
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to build trends")
            ToolResult.error(name, "Imeshindwa: ${e.message}", "DB_ERROR")
        }
    }

    // ════════════════════════════════════════════════════════════
    //  ACTION 4: alerts — Active business alerts
    // ════════════════════════════════════════════════════════════

    private suspend fun buildAlerts(params: Map<String, String>): ToolResult {
        return try {
            val severity = params["severity"] ?: "all"
            val now = System.currentTimeMillis()
            val todayStart = DateTimeUtil.startOfDay(now)
            val todayEnd = DateTimeUtil.endOfDay(now)
            val weekStart = todayStart - 6 * 86_400_000L

            val today = fetchPeriodMetrics(todayStart, todayEnd)
            val week = fetchPeriodMetrics(weekStart, todayEnd)
            val cashPosition = fetchCashPosition()

            val allAlerts = gatherAlerts(today, week, cashPosition)

            // Filter by severity
            val filtered = when (severity) {
                "critical" -> allAlerts.filter { it.severity == "critical" }
                "warning" -> allAlerts.filter { it.severity == "warning" }
                else -> allAlerts
            }

            val alertText = buildString {
                if (filtered.isEmpty()) {
                    appendLine("✅ Hakuna tahadhari! Biashara yako iko sawa.")
                    return@buildString
                }

                appendLine("🔔 TAHAHADHARI ZA BIASHARA")
                appendLine("─".repeat(40))
                appendLine()

                val critical = filtered.filter { it.severity == "critical" }
                val warnings = filtered.filter { it.severity == "warning" }

                if (critical.isNotEmpty()) {
                    appendLine("🔴 HATARI:")
                    critical.forEach { a ->
                        appendLine("  • ${a.messageSw}")
                    }
                    appendLine()
                }

                if (warnings.isNotEmpty()) {
                    appendLine("🟡 TAHADHARI:")
                    warnings.forEach { a ->
                        appendLine("  • ${a.messageSw}")
                    }
                }

                appendLine()
                appendLine("Jumla ya tahadhari: ${filtered.size}")
            }

            ToolResult.success(
                toolName = name,
                data = mapOf("alerts" to filtered, "count" to filtered.size),
                message = alertText
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to build alerts")
            ToolResult.error(name, "Imeshindwa: ${e.message}", "DB_ERROR")
        }
    }

    // ════════════════════════════════════════════════════════════
    //  ACTION 5: compare — Period vs period comparison
    // ════════════════════════════════════════════════════════════

    private suspend fun buildComparison(params: Map<String, String>): ToolResult {
        return try {
            val now = System.currentTimeMillis()
            val calendar = Calendar.getInstance()

            // Parse period_a and period_b
            val periodA = params["period_a"] ?: "last_month"
            val periodB = params["period_b"] ?: "this_month"

            val (aStart, aEnd) = resolvePeriod(periodA, calendar, now)
            val (bStart, bEnd) = resolvePeriod(periodB, calendar, now)

            val metricsA = fetchPeriodMetrics(aStart, aEnd)
            val metricsB = fetchPeriodMetrics(bStart, bEnd)

            val revenueChange = calcTrend(metricsB.revenue, metricsA.revenue)
            val profitChange = calcTrend(metricsB.profit, metricsA.profit)
            val marginChange = metricsB.margin - metricsA.margin

            val comparisonText = buildString {
                appendLine("📊 LINGANISHO LA VIPINDUO")
                appendLine("─".repeat(45))
                appendLine()
                appendLine("${formatPeriodLabel(periodA)} vs ${formatPeriodLabel(periodB)}")
                appendLine()
                appendLine("                    ${formatPeriodLabel(periodA).take(12).padEnd(14)} ${formatPeriodLabel(periodB).take(12).padEnd(14)} Mabadiliko")
                appendLine("─".repeat(45))
                appendLine("Mapato:          ${fmt(metricsA.revenue).padEnd(14)} ${fmt(metricsB.revenue).padEnd(14)} ${trendArrow(revenueChange)} ${pct(kotlin.math.abs(revenueChange))}")
                appendLine("Gharama:         ${fmt(metricsA.costs).padEnd(14)} ${fmt(metricsB.costs).padEnd(14)}")
                appendLine("Faida:           ${fmt(metricsA.profit).padEnd(14)} ${fmt(metricsB.profit).padEnd(14)} ${trendArrow(profitChange)} ${pct(kotlin.math.abs(profitChange))}")
                appendLine("Margin:          ${pct(metricsA.margin).padEnd(14)} ${pct(metricsB.margin).padEnd(14)} ${if (marginChange > 0) "+" else ""}${"%.1f".format(marginChange)}pp")
                appendLine("Miamala:         ${metricsA.txnCount.toString().padEnd(14)} ${metricsB.txnCount.toString().padEnd(14)}")
                appendLine()

                // Interpretation
                when {
                    profitChange > 10 -> appendLine("✅ Faida imepanda sana! Biashara inakua.")
                    profitChange > 0 -> appendLine("📈 Faida imepanda — endelea hivi!")
                    profitChange > -10 -> appendLine("➡️ Faida imara — mabadiliko madogo.")
                    else -> appendLine("⚠️ Faida imeshuka — angalia gharama na mauzo.")
                }

                if (marginChange > 2) {
                    appendLine("✅ Margin imeboresha — unapata zaidi kwa kila sale.")
                } else if (marginChange < -2) {
                    appendLine("📉 Margin imeshuka — gharama zinazidi mauzo.")
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "periodA" to mapOf("label" to periodA, "metrics" to metricsA),
                    "periodB" to mapOf("label" to periodB, "metrics" to metricsB),
                    "changes" to mapOf("revenue" to revenueChange, "profit" to profitChange, "margin" to marginChange)
                ),
                message = comparisonText
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to build comparison")
            ToolResult.error(name, "Imeshindwa: ${e.message}", "DB_ERROR")
        }
    }

    // ════════════════════════════════════════════════════════════
    //  ACTION 6: forecast — Cash flow prediction
    // ════════════════════════════════════════════════════════════

    private suspend fun buildForecast(): ToolResult {
        return try {
            val summaries = dailySummaryDao.getRecentSummaries(30).first()

            if (summaries.size < 7) {
                return ToolResult.success(
                    name,
                    message = "Hakuna data ya kutosha kutabiri. Rekodi mauzo kwa angalau wiki 1! 📝"
                )
            }

            val avgDailySales = summaries.map { it.totalSales }.average()
            val avgDailyExpenses = summaries.map { it.totalExpenses }.average()
            val avgDailyProfit = avgDailySales - avgDailyExpenses

            // Day-of-week patterns
            val calendar = Calendar.getInstance()
            val dayOfWeekSales = summaries.groupBy {
                calendar.timeInMillis = it.createdAt
                calendar.get(Calendar.DAY_OF_WEEK)
            }.mapValues { (_, days) -> days.map { it.totalSales }.average() }

            // 7-day forecast
            val predictions = (1..7).map { dayOffset ->
                calendar.timeInMillis = System.currentTimeMillis()
                calendar.add(Calendar.DAY_OF_MONTH, dayOffset)
                val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
                val predictedSales = dayOfWeekSales[dayOfWeek] ?: avgDailySales
                val predictedExpenses = avgDailyExpenses
                val predictedProfit = predictedSales - predictedExpenses
                mapOf(
                    "date" to DateTimeUtil.formatDate(calendar.timeInMillis),
                    "predicted_sales" to predictedSales,
                    "predicted_expenses" to predictedExpenses,
                    "predicted_profit" to predictedProfit
                )
            }

            val weeklySalesTotal = predictions.sumOf { it["predicted_sales"] as Double }

            val forecast = buildString {
                appendLine("📈 TABIRI — Wiki Ijayo")
                appendLine("─".repeat(40))
                appendLine()
                appendLine("Sales ya kila siku (wastani): ${fmt(avgDailySales)}")
                appendLine("Faida ya kila siku (wastani): ${fmt(avgDailyProfit)}")
                appendLine()
                appendLine("TABIRI YA KILA SIKU:")
                predictions.forEach { p ->
                    appendLine("  ${p["date"]}: Sales ${fmt(p["predicted_sales"] as Double)} | " +
                        "Faida ${fmt(p["predicted_profit"] as Double)}")
                }
                appendLine()
                appendLine("─".repeat(40))
                appendLine("Jumla wiki ijayo:")
                appendLine("  Sales: ${fmt(predictions.sumOf { it["predicted_sales"] as Double })}")
                appendLine("  Faida: ${fmt(predictions.sumOf { it["predicted_profit"] as Double })}")
                appendLine()
                appendLine("⚠️ Hii ni tabiri — inategemea mwenendo wa siku 30 zilizopita.")
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "avgDailySales" to avgDailySales,
                    "avgDailyExpenses" to avgDailyExpenses,
                    "avgDailyProfit" to avgDailyProfit,
                    "predictions" to predictions,
                    "weeklyTotal" to mapOf(
                        "sales" to predictions.sumOf { it["predicted_sales"] as Double },
                        "profit" to predictions.sumOf { it["predicted_profit"] as Double }
                    )
                ),
                message = forecast
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to build forecast")
            ToolResult.error(name, "Imeshindwa: ${e.message}", "DB_ERROR")
        }
    }

    // ════════════════════════════════════════════════════════════
    //  HELPER METHODS
    // ════════════════════════════════════════════════════════════

    /**
     * Fetch aggregated metrics for a time period from DAOs.
     */
    private suspend fun fetchPeriodMetrics(start: Long, end: Long): PeriodMetrics {
        val revenue = saleDao.getTotalSalesBetween(start, end).first() ?: 0.0
        val costs = expenseDao.getTotalExpensesBetween(start, end).first() ?: 0.0
        val profit = revenue - costs
        val margin = if (revenue > 0) profit / revenue * 100 else 0.0
        val txnCount = saleDao.getTransactionCountBetween(start, end).first()
        return PeriodMetrics(revenue, costs, profit, margin, txnCount)
    }

    /**
     * Calculate cash position: estimated cash on hand, runway, outstanding receivables.
     */
    private suspend fun fetchCashPosition(): CashPosition {
        // Recent profit as proxy for cash on hand (last 3 days of profit)
        val now = System.currentTimeMillis()
        val threeDaysAgo = now - 3 * 86_400_000L
        val recentRevenue = saleDao.getTotalSalesBetween(threeDaysAgo, now).first() ?: 0.0
        val recentExpenses = expenseDao.getTotalExpensesBetween(threeDaysAgo, now).first() ?: 0.0
        val cashOnHand = (recentRevenue - recentExpenses).coerceAtLeast(0.0)

        // Runway: days of expenses cash can cover
        val dailyExpenses = if (recentExpenses > 0) recentExpenses / 3.0 else 1.0
        val runwayDays = if (dailyExpenses > 0) (cashOnHand / dailyExpenses).toInt() else 99

        // Outstanding receivables from debts
        val outstanding = debtDao.getTotalOutstanding().first() ?: 0.0

        return CashPosition(cashOnHand, runwayDays, outstanding)
    }

    /**
     * Fetch top products for a period.
     */
    private suspend fun fetchTopProducts(start: Long, end: Long, limit: Int): List<ProductSnapshot> {
        val topProducts = saleDao.getTopProducts(start, end, limit).first()
        return topProducts.map { p ->
            // Estimate margin from sell price vs buy price if available
            val products = productDao.getAllActive().first()
            val product = products.find { it.name.equals(p.productName, ignoreCase = true) }
            val margin = if (product != null && product.sellPrice > 0) {
                ((product.sellPrice - product.buyPrice) / product.sellPrice * 100)
            } else 25.0 // default estimate

            ProductSnapshot(
                name = p.productName,
                dailyProfit = p.totalRevenue * (margin / 100.0),
                margin = margin,
                status = trafficLightStatus(margin, 25.0, 15.0)
            )
        }
    }

    /**
     * Fetch watch list: products with low margins or high spoilage.
     */
    private suspend fun fetchWatchList(start: Long, end: Long): List<ProductSnapshot> {
        val products = productDao.getAllActive().first()
        val topProducts = saleDao.getTopProducts(start, end, 20).first()

        return topProducts.mapNotNull { sale ->
            val product = products.find { it.name.equals(sale.productName, ignoreCase = true) }
            val margin = if (product != null && product.sellPrice > 0) {
                ((product.sellPrice - product.buyPrice) / product.sellPrice * 100)
            } else 25.0

            if (margin < 15.0) {
                ProductSnapshot(
                    name = sale.productName,
                    dailyProfit = sale.totalRevenue * (margin / 100.0),
                    margin = margin,
                    status = trafficLightStatus(margin, 15.0, 8.0)
                )
            } else null
        }
    }

    /**
     * Gather all business alerts.
     */
    private suspend fun gatherAlerts(
        today: PeriodMetrics,
        week: PeriodMetrics,
        cash: CashPosition
    ): List<AlertItem> {
        val alerts = mutableListOf<AlertItem>()

        // Cash runway alerts
        if (cash.runwayDays <= 3) {
            alerts.add(AlertItem(
                "critical", "cash_runway",
                "Cash runway only ${cash.runwayDays} days",
                "🔴 Pesa zinakaribia kuisha — runway ni siku ${cash.runwayDays} tu!"
            ))
        } else if (cash.runwayDays <= 7) {
            alerts.add(AlertItem(
                "warning", "cash_runway",
                "Cash runway ${cash.runwayDays} days",
                "🟡 Pesa zitakwisha kwa siku ${cash.runwayDays}. Ongeza mauzo au punguza gharama."
            ))
        }

        // Outstanding debt alerts
        if (cash.outstandingReceivables > week.revenue * 0.25) {
            alerts.add(AlertItem(
                "critical", "receivables",
                "Outstanding debt is ${pct(cash.outstandingReceivables / week.revenue.coerceAtLeast(1.0) * 100)} of weekly revenue",
                "🔴 Deni ni kubwa — ${fmt(cash.outstandingReceivables)} bado hajalipwa. Fuatilia wadaiwa!"
            ))
        } else if (cash.outstandingReceivables > week.revenue * 0.1) {
            alerts.add(AlertItem(
                "warning", "receivables",
                "Outstanding debt increasing",
                "🟡 Deni linazidi — ${fmt(cash.outstandingReceivables)} bado hajalipwa."
            ))
        }

        // Margin alerts
        if (today.margin < 10 && today.revenue > 0) {
            alerts.add(AlertItem(
                "critical", "margin",
                "Today's margin critically low: ${pct(today.margin)}",
                "🔴 Margin ya leo ni ndogo sana — ${pct(today.margin)}. Unapoteza pesa!"
            ))
        } else if (today.margin < 20 && today.revenue > 0) {
            alerts.add(AlertItem(
                "warning", "margin",
                "Today's margin low: ${pct(today.margin)}",
                "🟡 Margin ya leo ni ${pct(today.margin)}. Jaribu kupunguza gharama au ongeza bei."
            ))
        }

        // Weekly profit decline
        if (week.margin < 15 && week.revenue > 0) {
            alerts.add(AlertItem(
                "warning", "weekly_margin",
                "Weekly margin declining: ${pct(week.margin)}",
                "🟡 Margin ya wiki ni ${pct(week.margin)} — angalia bidhaa zinazopoteza pesa."
            ))
        }

        // Low stock alerts
        val lowStock = productDao.getLowStock().first()
        if (lowStock.isNotEmpty()) {
            alerts.add(AlertItem(
                "warning", "low_stock",
                "Low stock: ${lowStock.joinToString(", ") { it.name }}",
                "🟡 Stock ya chini: ${lowStock.joinToString(", ") { it.name }}. Nunua mapema!"
            ))
        }

        // No sales today (if past noon)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (today.revenue == 0.0 && hour >= 14) {
            alerts.add(AlertItem(
                "warning", "no_sales",
                "No sales recorded today",
                "🟡 Haujarekodi mauzo leo bado. Uhakikishe umerekodi?"
            ))
        }

        return alerts
    }

    /**
     * Estimate Alama score from recent data (simplified version).
     */
    private suspend fun estimateAlamaScore(): Int {
        val summaries = dailySummaryDao.getRecentSummaries(30).first()
        if (summaries.isEmpty()) return 300

        var score = 300
        val activeDays = summaries.count { it.totalSales > 0 }
        score += (activeDays / 30.0 * 150).toInt().coerceAtMost(150)

        val totalTxns = summaries.sumOf { it.transactionCount }
        score += (totalTxns / 5).coerceAtMost(100)

        val recentProfit = summaries.take(15).sumOf { it.profit }
        val olderProfit = summaries.drop(15).sumOf { it.profit }
        if (olderProfit > 0 && recentProfit > olderProfit) {
            score += 50
        }

        return score.coerceIn(300, 850)
    }

    /**
     * Resolve a period string to start/end timestamps.
     */
    private fun resolvePeriod(
        period: String,
        calendar: Calendar,
        now: Long
    ): Pair<Long, Long> {
        val todayStart = DateTimeUtil.startOfDay(now)

        return when (period.lowercase()) {
            "today" -> todayStart to DateTimeUtil.endOfDay(now)
            "yesterday" -> (todayStart - 86_400_000L) to (todayStart - 1)
            "this_week" -> {
                calendar.timeInMillis = now
                calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                calendar.timeInMillis to DateTimeUtil.endOfDay(now)
            }
            "last_week" -> {
                calendar.timeInMillis = now
                calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val thisWeekStart = calendar.timeInMillis
                (thisWeekStart - 7 * 86_400_000L) to (thisWeekStart - 1)
            }
            "this_month" -> DateTimeUtil.startOfMonth() to DateTimeUtil.endOfDay(now)
            "last_month" -> getPreviousMonthStart() to (DateTimeUtil.startOfMonth() - 1)
            else -> {
                // Handle YYYY-MM format
                if (period.matches(Regex("\\d{4}-\\d{2}"))) {
                    val parts = period.split("-")
                    calendar.set(parts[0].toInt(), parts[1].toInt() - 1, 1, 0, 0, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    val start = calendar.timeInMillis
                    calendar.add(Calendar.MONTH, 1)
                    val end = calendar.timeInMillis - 1
                    start to end
                } else {
                    // Default to this month
                    DateTimeUtil.startOfMonth() to DateTimeUtil.endOfDay(now)
                }
            }
        }
    }

    /**
     * Get start of previous month.
     */
    private fun getPreviousMonthStart(): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -1)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Format period label for display.
     */
    private fun formatPeriodLabel(period: String): String {
        return when (period.lowercase()) {
            "today" -> "Leo"
            "yesterday" -> "Jana"
            "this_week" -> "Wiki hii"
            "last_week" -> "Wiki iliyopita"
            "this_month" -> "Mwezi huu"
            "last_month" -> "Mwezi uliopita"
            else -> period
        }
    }

    /**
     * Calculate percentage change between two values.
     */
    private fun calcTrend(current: Double, previous: Double): Double {
        if (previous == 0.0) return if (current > 0) 100.0 else 0.0
        return ((current - previous) / previous) * 100
    }

    /**
     * Trend arrow symbol based on change percentage.
     * ↑ up (>2%), → stable (-2% to 2%), ↓ down (<-2%)
     */
    private fun trendArrow(changePct: Double): String {
        return when {
            changePct > 2 -> "↑"
            changePct < -2 -> "↓"
            else -> "→"
        }
    }

    /**
     * Traffic light emoji based on value vs thresholds.
     * 🟢 green (good), 🟡 yellow (watch), 🔴 red (danger)
     *
     * @param value The metric value
     * @param goodThreshold Above this = green
     * @param warnThreshold Above this = yellow, below = red
     */
    private fun trafficLight(value: Double, goodThreshold: Double, warnThreshold: Double): String {
        return when {
            value >= goodThreshold -> "🟢"
            value >= warnThreshold -> "🟡"
            else -> "🔴"
        }
    }

    /**
     * Return status string for traffic light.
     */
    private fun trafficLightStatus(value: Double, goodThreshold: Double, warnThreshold: Double): String {
        return when {
            value >= goodThreshold -> "green"
            value >= warnThreshold -> "yellow"
            else -> "red"
        }
    }

    /**
     * Format currency in KSh.
     */
    private fun fmt(amount: Double): String {
        return when {
            amount >= 1_000_000 -> "KSh ${"%.1f".format(amount / 1_000_000)}M"
            amount >= 1_000 -> "KSh ${"%,.0f".format(amount)}"
            else -> "KSh ${"%.0f".format(amount)}"
        }
    }

    /**
     * Format percentage.
     */
    private fun pct(value: Double): String {
        return "${"%.0f".format(value)}%"
    }
}
