package com.msaidizi.agent.tools.financial

import com.msaidizi.core.database.*
import com.msaidizi.core.model.ArchetypeType
import com.msaidizi.core.util.DateTimeUtil
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import com.msaidizi.agent.tools.core.*

// ════════════════════════════════════════════════════════════
// PER-ARCHETYPE FINANCIAL MODEL ENGINE
// ════════════════════════════════════════════════════════════
// Each of the 12 archetypes has a distinct:
//  - Revenue model (daily, project-based, seasonal, etc.)
//  - Cost structure (stock-heavy, fuel-heavy, rent-heavy, etc.)
//  - Profit formula (margin-based, per-job, per-trip, etc.)
//  - Cash flow pattern (daily positive, lumpy, seasonal)
//
// This engine computes archetype-aware financial metrics
// instead of applying a generic "small business" template.
// ════════════════════════════════════════════════════════════

/**
 * Financial snapshot for a specific archetype.
 */
data class ArchetypeFinancialSnapshot(
    val archetype: ArchetypeType,
    val period: String,               // "today", "week", "month"
    val revenue: Double,
    val costs: Double,
    val grossProfit: Double,
    val operatingProfit: Double,
    val netProfit: Double,
    val margin: Double,               // as percentage
    val revenueBreakdown: Map<String, Double>,
    val costBreakdown: Map<String, Double>,
    val cashFlowPattern: String,      // "daily_positive", "lumpy", "seasonal", "monthly"
    val healthIndicators: Map<String, HealthIndicator>,
    val swahiliSummary: String
)

data class HealthIndicator(
    val name: String,
    val nameSwahili: String,
    val value: Double,
    val unit: String,
    val status: String,               // "green", "yellow", "red"
    val message: String,
    val messageSwahili: String
)

/**
 * ArchetypeFinancialModel — Per-archetype revenue, cost, profit, cash flow.
 *
 * Computes financial metrics tailored to each worker archetype.
 * A Mama Mboga's model tracks spoilage; a boda boda rider's tracks fuel;
 * a farmer's tracks seasonal harvest cycles.
 */
@Singleton
class ArchetypeFinancialModel @Inject constructor(
    private val saleDao: SaleDao,
    private val expenseDao: ExpenseDao,
    private val productDao: ProductDao,
    private val dailySummaryDao: DailySummaryDao,
    private val debtDao: DebtDao,
    private val userProfileDao: UserProfileDao,
    private val serviceTransactionDao: ServiceTransactionDao,
    private val bodaIncomeDao: BodaIncomeDao,
    private val bodaExpenseDao: BodaExpenseDao,
    private val stockMovementDao: StockMovementDao
) : Tool {

    override val name = "archetype_financial_model"
    override val description = "Per-archetype financial calculations: revenue, cost, profit, cash flow. " +
            "Tailored to vendor, artisan, service, transport, farmer, livestock, fisher, agent, digital, casual worker."

    override val argsSchema = argSchema {
        enum("action", "Financial model action",
            listOf("snapshot", "compare", "cost_breakdown", "revenue_model", "cash_flow"), required = false)
        enum("archetype", "Worker archetype",
            listOf("VENDOR", "ARTISAN", "SERVICE_PROVIDER", "TRANSPORT_OPERATOR",
                "CROP_FARMER", "LIVESTOCK_KEEPER", "FISHER", "AGENT_BROKER",
                "DIGITAL_WORKER", "CASUAL_LABORER", "FOOD_SERVICE", "COMMUNITY_CARE_WORKER"),
            required = false)
        string("period", "Time period: today, week, month", required = false, default = "week")
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "snapshot"
        return when (action.lowercase()) {
            "snapshot" -> buildSnapshot(params)
            "compare" -> compareArchetypes(params)
            "cost_breakdown" -> buildCostBreakdown(params)
            "revenue_model" -> describeRevenueModel(params)
            "cash_flow" -> describeCashFlow(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ════════════════════════════════════════════════════════════
    //  SNAPSHOT — Full financial picture for an archetype
    // ════════════════════════════════════════════════════════════

    private suspend fun buildSnapshot(params: Map<String, String>): ToolResult {
        return try {
            val archetypeStr = params["archetype"]
            val archetype = archetypeStr?.let { runCatching { ArchetypeType.valueOf(it) }.getOrNull() }
                ?: detectArchetype()

            val period = params["period"] ?: "week"
            val (start, end) = resolvePeriod(period)

            // Fetch raw data
            val revenue = saleDao.getTotalSalesBetween(start, end).first() ?: 0.0
            val expenses = expenseDao.getTotalExpensesBetween(start, end).first() ?: 0.0
            val serviceRevenue = serviceTransactionDao.getTotalRevenueBetween(start, end).first() ?: 0.0
            val totalRevenue = revenue + serviceRevenue

            // Archetype-specific cost and revenue breakdowns
            val revenueBreakdown = computeRevenueBreakdown(archetype, start, end)
            val costBreakdown = computeCostBreakdown(archetype, start, end)

            // Archetype-specific profit calculations
            val grossProfit = totalRevenue - (costBreakdown["stock"] ?: 0.0) - (costBreakdown["materials"] ?: 0.0)
            val operatingExpenses = costBreakdown.filterKeys { it != "stock" && it != "materials" }.values.sum()
            val operatingProfit = grossProfit - operatingExpenses
            val netProfit = totalRevenue - expenses - (costBreakdown["stock"] ?: 0.0)
            val margin = if (totalRevenue > 0) (netProfit / totalRevenue * 100) else 0.0

            // Archetype-specific health indicators
            val healthIndicators = computeHealthIndicators(archetype, start, end, totalRevenue, expenses)

            // Cash flow pattern
            val cashFlowPattern = getCashFlowPattern(archetype)

            // Swahili summary
            val summary = buildSwahiliSummary(archetype, totalRevenue, netProfit, margin)

            val snapshot = ArchetypeFinancialSnapshot(
                archetype = archetype,
                period = period,
                revenue = totalRevenue,
                costs = expenses,
                grossProfit = grossProfit,
                operatingProfit = operatingProfit,
                netProfit = netProfit,
                margin = margin,
                revenueBreakdown = revenueBreakdown,
                costBreakdown = costBreakdown,
                cashFlowPattern = cashFlowPattern,
                healthIndicators = healthIndicators,
                swahiliSummary = summary
            )

            val report = buildString {
                appendLine("📊 MFUMO WA FEDHA — ${archetype.swahiliName}")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("📅 Period: ${period.uppercase()}")
                appendLine()

                appendLine("💰 MAPATO: KES ${"%,.0f".format(totalRevenue)}")
                revenueBreakdown.forEach { (source, amount) ->
                    appendLine("   • $source: KES ${"%,.0f".format(amount)}")
                }
                appendLine()

                appendLine("📤 GHARAMA:")
                costBreakdown.forEach { (category, amount) ->
                    val pct = if (totalRevenue > 0) (amount / totalRevenue * 100) else 0.0
                    appendLine("   • $category: KES ${"%,.0f".format(amount)} (${"%.0f".format(pct)}%)")
                }
                appendLine()

                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("📈 FAIDA GROSS: KES ${"%,.0f".format(grossProfit)}")
                appendLine("📈 FAIDA HALISI: KES ${"%,.0f".format(netProfit)}")
                appendLine("📊 MARGIN: ${"%.0f".format(margin)}%")
                appendLine("💵 CASH FLOW: $cashFlowPattern")
                appendLine()

                // Health indicators
                appendLine("🔔 AFYA YA BIASHARA:")
                healthIndicators.forEach { (_, ind) ->
                    val emoji = when (ind.status) { "green" -> "🟢"; "yellow" -> "🟡"; else -> "🔴" }
                    appendLine("   $emoji ${ind.nameSwahili}: ${"%.1f".format(ind.value)}${ind.unit} — ${ind.messageSwahili}")
                }
                appendLine()
                appendLine(summary)
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "archetype" to archetype.name,
                    "revenue" to totalRevenue,
                    "costs" to expenses,
                    "grossProfit" to grossProfit,
                    "netProfit" to netProfit,
                    "margin" to margin,
                    "revenueBreakdown" to revenueBreakdown,
                    "costBreakdown" to costBreakdown,
                    "cashFlowPattern" to cashFlowPattern,
                    "healthIndicators" to healthIndicators.mapValues { it.value.status }
                ),
                message = report
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to build archetype financial snapshot")
            ToolResult.error(name, "Imeshindwa: ${e.message}", "DB_ERROR")
        }
    }

    // ════════════════════════════════════════════════════════════
    //  REVENUE BREAKDOWN — Archetype-specific revenue sources
    // ════════════════════════════════════════════════════════════

    private suspend fun computeRevenueBreakdown(
        archetype: ArchetypeType,
        start: Long,
        end: Long
    ): Map<String, Double> {
        val breakdown = mutableMapOf<String, Double>()

        when (archetype) {
            ArchetypeType.VENDOR, ArchetypeType.FOOD_SERVICE -> {
                val cashSales = saleDao.getTotalSalesBetween(start, end).first() ?: 0.0
                val mpesaSales = saleDao.getMpesaSalesBetween(start, end).first() ?: 0.0
                val creditSales = saleDao.getCreditSalesBetween(start, end).first() ?: 0.0
                breakdown["Mauzo ya Cash"] = cashSales - mpesaSales
                breakdown["Mauzo ya M-Pesa"] = mpesaSales
                breakdown["Mauzo ya Mkopo"] = creditSales
            }
            ArchetypeType.SERVICE_PROVIDER -> {
                val serviceRev = serviceTransactionDao.getTotalRevenueBetween(start, end).first() ?: 0.0
                val productRev = saleDao.getTotalSalesBetween(start, end).first() ?: 0.0
                breakdown["Ada za Huduma"] = serviceRev
                breakdown["Mauzo ya Bidhaa"] = productRev
            }
            ArchetypeType.TRANSPORT_OPERATOR -> {
                val income = bodaIncomeDao.getTotalBetween(
                    formatDateForBoda(start), formatDateForBoda(end)
                )
                breakdown["Nauli/Fare"] = income
            }
            ArchetypeType.CROP_FARMER -> {
                val harvestSales = saleDao.getTotalSalesBetween(start, end).first() ?: 0.0
                breakdown["Mauzo ya Mazao"] = harvestSales
            }
            ArchetypeType.LIVESTOCK_KEEPER -> {
                val sales = saleDao.getTotalSalesBetween(start, end).first() ?: 0.0
                breakdown["Maziwa/Nyama/Mayai"] = sales
            }
            ArchetypeType.AGENT_BROKER -> {
                val sales = saleDao.getTotalSalesBetween(start, end).first() ?: 0.0
                breakdown["Kamisheni"] = sales
            }
            else -> {
                val sales = saleDao.getTotalSalesBetween(start, end).first() ?: 0.0
                breakdown["Mapato ya Jumla"] = sales
            }
        }

        return breakdown.filter { it.value > 0 }
    }

    // ════════════════════════════════════════════════════════════
    //  COST BREAKDOWN — Archetype-specific cost categories
    // ════════════════════════════════════════════════════════════

    private suspend fun computeCostBreakdown(
        archetype: ArchetypeType,
        start: Long,
        end: Long
    ): Map<String, Double> {
        val expenses = expenseDao.getExpensesBetween(start, end).first()
        val byCategory = expenses.groupBy { it.category }.mapValues { it.value.sumOf { e -> e.amount } }.toMutableMap()

        // Add archetype-specific implicit costs
        when (archetype) {
            ArchetypeType.VENDOR, ArchetypeType.FOOD_SERVICE -> {
                // Track spoilage from stock movements
                val movements = stockMovementDao.getMovementsBetween(start, end).first()
                val spoilage = movements.filter { it.type == "spoilage" }.sumOf {
                    it.quantity * (it.previousStock - it.newStock).coerceAtLeast(0.0)
                }
                if (spoilage > 0) byCategory["Kupotea (Spoilage)"] = spoilage
            }
            ArchetypeType.TRANSPORT_OPERATOR -> {
                // Fuel is primary cost — from boda expense tracker
                val bodaExpenses = bodaExpenseDao.getByCategoryBetween(
                    formatDateForBoda(start), formatDateForBoda(end)
                )
                bodaExpenses.forEach { catExp ->
                    val label = when (catExp.category) {
                        "fuel" -> "Mafuta/Petroli"
                        "hire_fee" -> "Kodi ya Pikipiki"
                        "police_bribe" -> "Polisi (Rushwa)"
                        "maintenance" -> "Matengenezo"
                        else -> catExp.category
                    }
                    byCategory[label] = (byCategory[label] ?: 0.0) + catExp.total
                }
            }
            ArchetypeType.CROP_FARMER -> {
                // Add post-harvest loss estimate
                val harvestMovements = stockMovementDao.getMovementsBetween(start, end).first()
                val harvestLoss = harvestMovements.filter { it.type == "spoilage" }.sumOf { it.quantity }
                if (harvestLoss > 0) byCategory["Hasara ya Baada ya Mavuno"] = harvestLoss
            }
            else -> { /* Use standard expense categories */ }
        }

        return byCategory.toSortedMap(compareByDescending { byCategory[it] ?: 0.0 })
    }

    // ════════════════════════════════════════════════════════════
    //  HEALTH INDICATORS — Archetype-specific traffic lights
    // ════════════════════════════════════════════════════════════

    private suspend fun computeHealthIndicators(
        archetype: ArchetypeType,
        start: Long,
        end: Long,
        revenue: Double,
        expenses: Double
    ): Map<String, HealthIndicator> {
        val indicators = mutableMapOf<String, HealthIndicator>()

        // ── Universal indicators ──

        // 1. Daily Profit Margin
        val margin = if (revenue > 0) ((revenue - expenses) / revenue * 100) else 0.0
        indicators["profit_margin"] = HealthIndicator(
            "Daily Profit Margin", "Faida ya Kila Siku",
            margin, "%",
            trafficLight(margin, 30.0, 15.0),
            marginMessage(margin), marginMessageSw(margin)
        )

        // 2. Cash Buffer Days
        val dailyExpenses = if (expenses > 0) expenses / 7.0 else 1.0
        val recentProfit = (saleDao.getTotalSalesBetween(
            System.currentTimeMillis() - 3 * 86_400_000L, System.currentTimeMillis()
        ).first() ?: 0.0) - (expenseDao.getTotalExpensesBetween(
            System.currentTimeMillis() - 3 * 86_400_000L, System.currentTimeMillis()
        ).first() ?: 0.0)
        val cashBuffer = if (dailyExpenses > 0) (recentProfit.coerceAtLeast(0.0) / dailyExpenses) else 99.0
        indicators["cash_buffer"] = HealthIndicator(
            "Cash Buffer Days", "Siku za Akiba",
            cashBuffer, " siku",
            trafficLight(cashBuffer, 14.0, 7.0),
            "Cash covers ${cashBuffer.toInt()} days",
            if (cashBuffer >= 14) "Pesa zinatosheka siku ${cashBuffer.toInt()}"
            else if (cashBuffer >= 7) "Pesa zinatosheka siku ${cashBuffer.toInt()} — ongeza akiba"
            else "🔴 Pesa zinakaribia kuisha — siku ${cashBuffer.toInt()} tu!"
        )

        // 3. Debt-to-Income Ratio
        val totalDebt = debtDao.getTotalOutstanding().first() ?: 0.0
        val weeklyIncome = revenue
        val debtRatio = if (weeklyIncome > 0) (totalDebt / weeklyIncome * 100) else 0.0
        indicators["debt_ratio"] = HealthIndicator(
            "Debt-to-Income Ratio", "Uwiano wa Deni",
            debtRatio, "%",
            trafficLight(100 - debtRatio, 80.0, 60.0), // inverted: lower is better
            "Debt is ${"%.0f".format(debtRatio)}% of income",
            if (debtRatio < 20) "Deni ni ndogo — ${"%.0f".format(debtRatio)}% ya mapato"
            else if (debtRatio < 40) "Deni ni kiasi — ${"%.0f".format(debtRatio)}% ya mapato"
            else "🔴 Deni ni kubwa — ${"%.0f".format(debtRatio)}% ya mapato!"
        )

        // 4. Savings Rate (from GoalTracker)
        // Use a simplified estimate based on profit
        val savingsRate = if (revenue > 0) ((revenue - expenses) / revenue * 20).coerceIn(0.0, 100.0) else 0.0
        indicators["savings_rate"] = HealthIndicator(
            "Savings Rate", "Kiwango cha Akiba",
            savingsRate, "%",
            trafficLight(savingsRate, 10.0, 5.0),
            "Estimated savings ${"%.0f".format(savingsRate)}%",
            if (savingsRate >= 10) "Akiba ni nzuri — ${"%.0f".format(savingsRate)}%"
            else if (savingsRate >= 5) "Akiba ni kidogo — jaribu kuongeza"
            else "🔴 Akiba ni ndogo sana — anza hata KES 20 kwa siku"
        )

        // ── Archetype-specific indicators ──
        when (archetype) {
            ArchetypeType.VENDOR, ArchetypeType.FOOD_SERVICE -> {
                // Spoilage rate
                val movements = stockMovementDao.getMovementsBetween(start, end).first()
                val totalStock = movements.filter { it.type == "purchase" }.sumOf { it.quantity }
                val spoiled = movements.filter { it.type == "spoilage" }.sumOf { it.quantity }
                val spoilageRate = if (totalStock > 0) (spoiled / totalStock * 100) else 0.0
                indicators["spoilage_rate"] = HealthIndicator(
                    "Spoilage Rate", "Kiwango cha Kupotea",
                    spoilageRate, "%",
                    trafficLight(100 - spoilageRate, 95.0, 90.0),
                    "Spoilage: ${"%.1f".format(spoilageRate)}%",
                    if (spoilageRate < 5) "Kupotea ni kidogo — vizuri!"
                    else if (spoilageRate < 10) "Kupotea ni kiasi — angalia stock"
                    else "🔴 Kupotea ni kubwa — ${"%.0f".format(spoilageRate)}%! Nunua kidogo au uze haraka"
                )
            }
            ArchetypeType.TRANSPORT_OPERATOR -> {
                // Fuel cost as % of revenue
                val bodaExpenses = bodaExpenseDao.getByCategoryBetween(
                    formatDateForBoda(start), formatDateForBoda(end)
                )
                val fuelCost = bodaExpenses.find { it.category == "fuel" }?.total ?: 0.0
                val fuelPct = if (revenue > 0) (fuelCost / revenue * 100) else 0.0
                indicators["fuel_ratio"] = HealthIndicator(
                    "Fuel Cost Ratio", "Gharama ya Mafuta",
                    fuelPct, "%",
                    trafficLight(100 - fuelPct, 65.0, 55.0),
                    "Fuel: ${"%.0f".format(fuelPct)}% of revenue",
                    if (fuelPct < 35) "Mafuta ni ${"%.0f".format(fuelPct)}% — vizuri!"
                    else if (fuelPct < 45) "Mafuta ni ${"%.0f".format(fuelPct)}% — kiasi"
                    else "🔴 Mafuta ni ${"%.0f".format(fuelPct)}% — gharama kubwa! Panda polepole."
                )
            }
            ArchetypeType.SERVICE_PROVIDER -> {
                // Revenue per client-hour (approximate)
                val txnCount = serviceTransactionDao.getTransactionCountBetween(start, end).first()
                val avgRevenuePerTxn = if (txnCount > 0) revenue / txnCount else 0.0
                indicators["revenue_per_client"] = HealthIndicator(
                    "Revenue per Client", "Mapato kwa Mteja",
                    avgRevenuePerTxn, " KES",
                    trafficLight(avgRevenuePerTxn, 500.0, 200.0),
                    "KES ${"%.0f".format(avgRevenuePerTxn)} per client",
                    if (avgRevenuePerTxn >= 500) "Mapato ni mazuri — KES ${"%.0f".format(avgRevenuePerTxn)}/mteja"
                    else if (avgRevenuePerTxn >= 200) "Mapato ni kiasi — KES ${"%.0f".format(avgRevenuePerTxn)}/mteja"
                    else "🔴 Mapato ni madogo — KES ${"%.0f".format(avgRevenuePerTxn)}/mteja"
                )
            }
            ArchetypeType.CROP_FARMER -> {
                // Post-harvest loss rate
                val movements = stockMovementDao.getMovementsBetween(start, end).first()
                val harvested = movements.filter { it.type == "purchase" }.sumOf { it.quantity }
                val lost = movements.filter { it.type == "spoilage" }.sumOf { it.quantity }
                val lossRate = if (harvested > 0) (lost / harvested * 100) else 0.0
                indicators["post_harvest_loss"] = HealthIndicator(
                    "Post-Harvest Loss", "Hasara ya Baada ya Mavuno",
                    lossRate, "%",
                    trafficLight(100 - lossRate, 90.0, 75.0),
                    "Loss: ${"%.1f".format(lossRate)}%",
                    if (lossRate < 10) "Hasara ni ndogo — ${"%.0f".format(lossRate)}%"
                    else if (lossRate < 25) "Hasara ni kiasi — ${"%.0f".format(lossRate)}%. Hifadhi vizuri."
                    else "🔴 Hasara ni kubwa — ${"%.0f".format(lossRate)}%! Hifadhi au uza haraka."
                )
            }
            ArchetypeType.ARTISAN -> {
                // Quote-to-job conversion (estimate from service txns)
                val txnCount = serviceTransactionDao.getTransactionCountBetween(start, end).first()
                indicators["job_volume"] = HealthIndicator(
                    "Jobs Completed", "Kazi Zilizokamilika",
                    txnCount.toDouble(), " kazi",
                    trafficLight(txnCount.toDouble(), 10.0, 5.0),
                    "$txnCount jobs this period",
                    if (txnCount >= 10) "Kazi nzuri — $txnCount kazi!"
                    else if (txnCount >= 5) "Kazi ni kiasi — $txnCount"
                    else "Kazi ni chache — $txnCount. Tafuta wateja zaidi."
                )
            }
            else -> { /* No archetype-specific indicator */ }
        }

        return indicators
    }

    // ════════════════════════════════════════════════════════════
    //  CASH FLOW PATTERN
    // ════════════════════════════════════════════════════════════

    private fun getCashFlowPattern(archetype: ArchetypeType): String {
        return when (archetype) {
            ArchetypeType.VENDOR, ArchetypeType.FOOD_SERVICE -> "daily_positive"
            ArchetypeType.SERVICE_PROVIDER -> "daily_positive"
            ArchetypeType.TRANSPORT_OPERATOR -> "daily_positive"
            ArchetypeType.ARTISAN -> "lumpy"
            ArchetypeType.CROP_FARMER -> "seasonal"
            ArchetypeType.LIVESTOCK_KEEPER -> "daily_positive"  // dairy
            ArchetypeType.FISHER -> "lumpy"
            ArchetypeType.AGENT_BROKER -> "daily_positive"
            ArchetypeType.DIGITAL_WORKER -> "lumpy"
            ArchetypeType.CASUAL_LABORER -> "daily_positive"
            ArchetypeType.COMMUNITY_CARE_WORKER -> "lumpy"
            else -> "daily_positive"
        }
    }

    // ════════════════════════════════════════════════════════════
    //  COMPARE — Side-by-side archetype comparison
    // ════════════════════════════════════════════════════════════

    private suspend fun compareArchetypes(params: Map<String, String>): ToolResult {
        // Compare user's metrics against archetype benchmarks
        val archetype = detectArchetype()
        val benchmarks = getArchetypeBenchmarks(archetype)

        val report = buildString {
            appendLine("📊 LINGANISHO — ${archetype.swahiliName}")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            benchmarks.forEach { (metric, benchmark) ->
                appendLine("• $metric")
                appendLine("  wastani wa soko: ${benchmark.first}")
                appendLine("  bora: ${benchmark.second}")
            }
        }

        return ToolResult.success(name, message = report)
    }

    private fun getArchetypeBenchmarks(archetype: ArchetypeType): Map<String, Pair<String, String>> {
        return when (archetype) {
            ArchetypeType.VENDOR -> mapOf(
                "Margin ya faida" to ("15-25%" to ">30%"),
                "Kupotea kwa stock" to ("5-10%" to "<5%"),
                "Mauzo ya siku" to ("KES 2,000-5,000" to ">KES 8,000")
            )
            ArchetypeType.TRANSPORT_OPERATOR -> mapOf(
                "Mafuta %" to ("35-45%" to "<35%"),
                "Safari kwa siku" to ("8-15" to ">20"),
                "Faida ya siku" to ("KES 300-600" to ">KES 800")
            )
            else -> mapOf(
                "Margin" to ("15-25%" to ">30%"),
                "Ukuaji" to ("0-10%" to ">15%")
            )
        }
    }

    // ════════════════════════════════════════════════════════════
    //  COST BREAKDOWN ACTION
    // ════════════════════════════════════════════════════════════

    private suspend fun buildCostBreakdown(params: Map<String, String>): ToolResult {
        val archetype = params["archetype"]?.let { runCatching { ArchetypeType.valueOf(it) }.getOrNull() }
            ?: detectArchetype()
        val period = params["period"] ?: "week"
        val (start, end) = resolvePeriod(period)
        val breakdown = computeCostBreakdown(archetype, start, end)
        val revenue = saleDao.getTotalSalesBetween(start, end).first() ?: 0.0

        val report = buildString {
            appendLine("📤 GHARAMA — ${archetype.swahiliName}")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            breakdown.forEach { (cat, amount) ->
                val pct = if (revenue > 0) (amount / revenue * 100) else 0.0
                appendLine("• $cat: KES ${"%,.0f".format(amount)} (${"%.0f".format(pct)}%)")
            }
        }

        return ToolResult.success(name, data = breakdown, message = report)
    }

    // ════════════════════════════════════════════════════════════
    //  REVENUE MODEL DESCRIPTION
    // ════════════════════════════════════════════════════════════

    private fun describeRevenueModel(params: Map<String, String>): ToolResult {
        val archetype = params["archetype"]?.let { runCatching { ArchetypeType.valueOf(it) }.getOrNull() }
            ?: ArchetypeType.VENDOR

        val model = when (archetype) {
            ArchetypeType.VENDOR -> "Mapato ya kila siku — mauzo ya bidhaa kwa bei ya juu ya gharama. " +
                    "Mapato hutegemea trafiki ya wateja, siku ya wiki, na msimu."
            ArchetypeType.ARTISAN -> "Mapato ya mradi — kazi husika. Unapata pesa unapokamilisha kazi. " +
                    "Wiki moja inaweza kuwa KES 15,000, nyingine KES 2,000."
            ArchetypeType.SERVICE_PROVIDER -> "Mapato ya kila siku — ada za huduma kutoka kwa wateja. " +
                    "Wateja wa kawaida wanatoa mapato ya uhakika."
            ArchetypeType.TRANSPORT_OPERATOR -> "Mapato ya kila siku — nauli kutoka kwa abiria. " +
                    "Mvua inapunguza mapato; Ijumaa na Jumamosi ni siku bora."
            ArchetypeType.CROP_FARMER -> "Mapato ya msimu — mauzo ya mazao mara 1-3 kwa mwaka. " +
                    "Mwezi mwingine una KES 0. Huu ndio msingi wa bajeti."
            ArchetypeType.LIVESTOCK_KEEPER -> "Mapato ya kila siku (maziwa) au ya mara kwa mara (nyama). " +
                    "Dairy ni ya kuhakika zaidi."
            ArchetypeType.FISHER -> "Mapato ya kila siku — inategemea uvuvi. Siku nzuri = KES 3,000. Siku mbaya = KES 0."
            ArchetypeType.AGENT_BROKER -> "Mapato ya kila siku — kamisheni kwa kila muamala. " +
                    "Ya kuhakika zaidi — watu wanahitaji pesa kila siku."
            ArchetypeType.DIGITAL_WORKER -> "Mapato ya mradi — kazi za mtandaoni. " +
                    "Inaweza kuwa KES 50,000 mwezi mmoja, KES 2,000 unaofuata."
            ArchetypeType.CASUAL_LABORER -> "Mapato ya kila siku — lakini hakuna uhakika wa kazi kesho. " +
                    "Siku 15-20 kwa mwezi tu."
            else -> "Mapato mbalimbali kulingana na aina ya biashara."
        }

        return ToolResult.success(name, message = "💰 MFUMO WA MAPATO — ${archetype.swahiliName}\n\n$model")
    }

    // ════════════════════════════════════════════════════════════
    //  CASH FLOW DESCRIPTION
    // ════════════════════════════════════════════════════════════

    private fun describeCashFlow(params: Map<String, String>): ToolResult {
        val archetype = params["archetype"]?.let { runCatching { ArchetypeType.valueOf(it) }.getOrNull() }
            ?: ArchetypeType.VENDOR

        val pattern = when (archetype) {
            ArchetypeType.VENDOR -> "💵 MTIRIRIKO WA PESA — Vendor\n\n" +
                    "Siku: Mapato ya kila siku (positive)\n" +
                    "Wiki: Nunua stock Jumatatu/Jumamosi, uza kila siku\n" +
                    "Mwezi: Kodi ya kibanda (outflow kubwa)\n\n" +
                    "⚠️ Hatari: Januari — mauzo yanashuka lakini kodi na deni ni kama kawaida."
            ArchetypeType.CROP_FARMER -> "💵 MTIRIRIKO WA PESA — Mkulima\n\n" +
                    "Jan-Machi: 🔴 HAPANA pesa — gharama za kupanda\n" +
                    "Apr-Jul: 🟡 — Mazao yanakua, gharama ndogo\n" +
                    "Aug-Okt: 🟢 — MAUNO! Pesa inakuja\n" +
                    "Nov-Dec: 🟡 — Tumia pesa ya mauzo\n\n" +
                    "⚠️ Huu ndio msingi wa bajeti ya msimu."
            ArchetypeType.TRANSPORT_OPERATOR -> "💵 MTIRIRIKO WA PESA — Dereva\n\n" +
                    "Siku: Nauli ya kila siku (positive) minus mafuta\n" +
                    "Wiki: Kikosi cha pikipiki (outflow kubwa)\n" +
                    "Mvua: 🔴 Mapato yanashuka 30-50%\n\n" +
                    "⚠️ Hatari: Hire-purchase — ukikosa wiki 2, pikipisi inachukuliwa."
            else -> "💵 Mtiririko wa pesa unategemea aina ya biashara."
        }

        return ToolResult.success(name, message = pattern)
    }

    // ════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════

    private suspend fun detectArchetype(): ArchetypeType {
        val profile = userProfileDao.getProfileOnce()
        return try {
            val bp = profile?.businessProfile
            if (bp != null && bp.isNotBlank()) {
                // Parse from JSON — simplified
                ArchetypeType.VENDOR // default fallback
            } else ArchetypeType.VENDOR
        } catch (e: Exception) {
            ArchetypeType.VENDOR
        }
    }

    private fun resolvePeriod(period: String): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val todayStart = DateTimeUtil.startOfDay(now)
        return when (period.lowercase()) {
            "today" -> todayStart to DateTimeUtil.endOfDay(now)
            "week" -> (todayStart - 6 * 86_400_000L) to now
            "month" -> DateTimeUtil.startOfMonth() to now
            else -> (todayStart - 6 * 86_400_000L) to now
        }
    }

    /**
     * Format Long timestamp to YYYY-MM-DD string for Boda DAOs.
     */
    private fun formatDateForBoda(timestamp: Long): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(timestamp))
    }

    private fun trafficLight(value: Double, goodThreshold: Double, warnThreshold: Double): String {
        return when {
            value >= goodThreshold -> "green"
            value >= warnThreshold -> "yellow"
            else -> "red"
        }
    }

    private fun marginMessage(margin: Double): String = when {
        margin >= 30 -> "Excellent profit margin"
        margin >= 15 -> "Good profit margin"
        margin >= 0 -> "Low profit margin — review costs"
        else -> "Losing money — immediate action needed"
    }

    private fun marginMessageSw(margin: Double): String = when {
        margin >= 30 -> "Faida ni nzuri sana!"
        margin >= 15 -> "Faida ni nzuri"
        margin >= 0 -> "Faida ni ndogo — angalia gharama"
        else -> "🔴 Unapoteza pesa — fanya haraka!"
    }

    private fun buildSwahiliSummary(
        archetype: ArchetypeType,
        revenue: Double,
        profit: Double,
        margin: Double
    ): String {
        val emoji = when {
            margin >= 25 -> "✅"
            margin >= 10 -> "📈"
            else -> "⚠️"
        }
        return "$emoji ${archetype.swahiliName}: Mapato KES ${"%,.0f".format(revenue)}, " +
                "Faida KES ${"%,.0f".format(profit)} (${"%.0f".format(margin)}%)"
    }
}
