package com.msaidizi.app.superagent.tools

import com.msaidizi.core.database.DailySummaryDao
import com.msaidizi.core.database.ExpenseDao
import com.msaidizi.core.database.SaleDao
import com.msaidizi.core.model.DailySummaryEntity
import com.msaidizi.core.util.DateTimeUtil
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

// ══════════════════════════════════════════════
// FIX 2: TRUE NET INCOME CALCULATOR — P0
// ══════════════════════════════════════════════
// Boda boda riders think they earn KES 1,500/day
// but after fuel (40-60%), hire fees, bribes, maintenance
// they actually keep KES 200. This tool makes the
// TRUE profit visible. The single most valuable insight.
// ══════════════════════════════════════════════

// ──────────────────────────────────────────────
// Boda Boda Income Entity
// ──────────────────────────────────────────────

/**
 * Tracks a boda boda rider's daily income entries.
 * Each fare/payment the rider receives during the day.
 */
@androidx.room.Entity(
    tableName = "boda_income",
    indices = [androidx.room.Index(value = ["date"])]
)
data class BodaIncomeEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,                    // fare amount in KES
    val route: String = "",                // "Town → Stage ya Mawe"
    val tripType: String = "fare",         // fare | delivery | charter | other
    val paymentMethod: String = "cash",    // cash | mpesa
    val passengerCount: Int = 1,
    val date: String = "",                 // YYYY-MM-DD
    val timestamp: Long = System.currentTimeMillis(),
    val needsSync: Boolean = true
)

// ──────────────────────────────────────────────
// Boda Boda Expense Entity
// ──────────────────────────────────────────────

/**
 * Tracks ALL boda boda expenses including hidden costs.
 * Categories: fuel, hire_fee, police_bribe, maintenance,
 * sacco, airtime, food, other
 */
@androidx.room.Entity(
    tableName = "boda_expenses",
    indices = [androidx.room.Index(value = ["date"])]
)
data class BodaExpenseEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val category: String,                  // fuel | hire_fee | police_bribe | maintenance | sacco | airtime | food | other
    val description: String = "",
    val date: String = "",                 // YYYY-MM-DD
    val timestamp: Long = System.currentTimeMillis(),
    val needsSync: Boolean = true
)

// ──────────────────────────────────────────────
// DAOs
// ──────────────────────────────────────────────

@androidx.room.Dao
interface BodaIncomeDao {
    @androidx.room.Insert
    suspend fun insert(income: BodaIncomeEntity): Long

    @androidx.room.Query("SELECT * FROM boda_income WHERE date = :date ORDER BY timestamp DESC")
    fun getByDate(date: String): kotlinx.coroutines.flow.Flow<List<BodaIncomeEntity>>

    @androidx.room.Query("SELECT COALESCE(SUM(amount), 0) FROM boda_income WHERE date = :date")
    suspend fun getTotalForDate(date: String): Double

    @androidx.room.Query("SELECT COALESCE(SUM(amount), 0) FROM boda_income WHERE date BETWEEN :startDate AND :endDate")
    suspend fun getTotalBetween(startDate: String, endDate: String): Double

    @androidx.room.Query("SELECT COUNT(*) FROM boda_income WHERE date = :date")
    suspend fun getTripCountForDate(date: String): Int

    @androidx.room.Query("SELECT route, COUNT(*) as count, AVG(amount) as avgFare FROM boda_income WHERE date BETWEEN :startDate AND :endDate GROUP BY route ORDER BY count DESC LIMIT :limit")
    suspend fun getTopRoutes(startDate: String, endDate: String, limit: Int = 10): List<RouteSummary>

    @androidx.room.Query("SELECT * FROM boda_income ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 50): kotlinx.coroutines.flow.Flow<List<BodaIncomeEntity>>

    @androidx.room.Delete
    suspend fun delete(income: BodaIncomeEntity)
}

data class RouteSummary(
    val route: String,
    val count: Int,
    val avgFare: Double
)

@androidx.room.Dao
interface BodaExpenseDao {
    @androidx.room.Insert
    suspend fun insert(expense: BodaExpenseEntity): Long

    @androidx.room.Query("SELECT * FROM boda_expenses WHERE date = :date ORDER BY timestamp DESC")
    fun getByDate(date: String): kotlinx.coroutines.flow.Flow<List<BodaExpenseEntity>>

    @androidx.room.Query("SELECT COALESCE(SUM(amount), 0) FROM boda_expenses WHERE date = :date")
    suspend fun getTotalForDate(date: String): Double

    @androidx.room.Query("SELECT COALESCE(SUM(amount), 0) FROM boda_expenses WHERE date = :date AND category = :category")
    suspend fun getTotalForDateByCategory(date: String, category: String): Double

    @androidx.room.Query("SELECT COALESCE(SUM(amount), 0) FROM boda_expenses WHERE date BETWEEN :startDate AND :endDate")
    suspend fun getTotalBetween(startDate: String, endDate: String): Double

    @androidx.room.Query("SELECT category, COALESCE(SUM(amount), 0) as total FROM boda_expenses WHERE date = :date GROUP BY category ORDER BY total DESC")
    suspend fun getByCategoryForDate(date: String): List<CategoryTotal>

    @androidx.room.Query("SELECT category, COALESCE(SUM(amount), 0) as total FROM boda_expenses WHERE date BETWEEN :startDate AND :endDate GROUP BY category ORDER BY total DESC")
    suspend fun getByCategoryBetween(startDate: String, endDate: String): List<CategoryTotal>

    @androidx.room.Query("SELECT COALESCE(SUM(amount), 0) FROM boda_expenses WHERE date BETWEEN :startDate AND :endDate AND category = 'police_bribe'")
    suspend fun getBribesBetween(startDate: String, endDate: String): Double

    @androidx.room.Query("SELECT * FROM boda_expenses ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 50): kotlinx.coroutines.flow.Flow<List<BodaExpenseEntity>>

    @androidx.room.Delete
    suspend fun delete(expense: BodaExpenseEntity)
}

data class CategoryTotal(
    val category: String,
    val total: Double
)

// ──────────────────────────────────────────────
// TRUE NET INCOME CALCULATOR TOOL
// ──────────────────────────────────────────────

/**
 * True Net Income Calculator for boda boda riders.
 *
 * Shows riders their ACTUAL profit after ALL expenses:
 * fare income - fuel - hire fee - police bribes - maintenance - sacco - other
 *
 * Actions:
 *  - today:     Today's true net income breakdown
 *  - week:      This week's summary
 *  - month:     This month's summary
 *  - add_income: Record a fare/payment received
 *  - add_expense: Record an expense (fuel, bribe, maintenance, etc.)
 *  - insights:  Money-saving insights based on patterns
 *
 * Voice (Swahili):
 *  - "Nimepata mia tano" → add_income 500
 *  - "Niliweka petroli mia mbili" → add_expense fuel 200
 *  - "Polisi amenilazimisha mia" → add_expense police_bribe 100
 *  - "Leo nimepata ngapi?" → today
 */
@Singleton
class NetIncomeCalculator @Inject constructor(
    private val bodaIncomeDao: BodaIncomeDao,
    private val bodaExpenseDao: BodaExpenseDao
) : Tool {

    override val name = "net_income"
    override val description = "TRUE net income calculator for boda boda riders. " +
            "Tracks ALL income (fares) and ALL expenses (fuel, hire fee, police bribes, maintenance, sacco). " +
            "Shows your REAL profit, not just gross income. " +
            "'Leo umefanya KES 1,500 lakini umetumia KES 1,300. Faida yako: KES 200'"

    override val argsSchema = argSchema {
        enum("action", "Action to perform",
            listOf("today", "week", "month", "add_income", "add_expense", "insights", "delete"))

        // ── add_income ──
        number("amount", "Amount in KES", required = false)
        string("route", "Route taken (e.g. 'Town → Stage')", required = false)
        enum("trip_type", "Type of trip", listOf("fare", "delivery", "charter", "other"), required = false)
        enum("payment_method", "How paid", listOf("cash", "mpesa"), required = false)
        integer("passenger_count", "Number of passengers", required = false)

        // ── add_expense ──
        enum("category", "Expense category",
            listOf("fuel", "hire_fee", "police_bribe", "maintenance", "sacco", "airtime", "food", "other"),
            required = false)
        string("description", "Expense description", required = false)

        // ── delete ──
        string("entry_id", "ID of entry to delete", required = false)
        enum("entry_type", "Type of entry to delete", listOf("income", "expense"), required = false)

        // ── period ──
        string("date", "Specific date (YYYY-MM-DD)", required = false)

        // ── Voice input ──
        string("voice_text", "Raw Swahili voice input to parse", required = false)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        // Parse voice input if provided
        val effectiveParams = if (params.containsKey("voice_text") && !params.containsKey("action")) {
            parseVoiceInput(params["voice_text"]!!).toMutableMap().apply { putAll(params) }
        } else {
            params
        }

        val action = effectiveParams["action"] ?: "today"
        return when (action.lowercase()) {
            "today" -> showToday(effectiveParams)
            "week" -> showWeek()
            "month" -> showMonth()
            "add_income" -> addIncome(effectiveParams)
            "add_expense" -> addExpense(effectiveParams)
            "insights" -> showInsights()
            "delete" -> deleteEntry(effectiveParams)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // TODAY'S TRUE NET INCOME
    // ──────────────────────────────────────────────

    private suspend fun showToday(params: Map<String, String>): ToolResult {
        return try {
            val date = params["date"] ?: DateTimeUtil.today()
            val totalIncome = bodaIncomeDao.getTotalForDate(date)
            val totalExpenses = bodaExpenseDao.getTotalForDate(date)
            val netProfit = totalIncome - totalExpenses
            val tripCount = bodaIncomeDao.getTripCountForDate(date)
            val expensesByCategory = bodaExpenseDao.getByCategoryForDate(date)

            // Yesterday for comparison
            val yesterdayDate = yesterday(date)
            val yesterdayIncome = bodaIncomeDao.getTotalForDate(yesterdayDate)
            val yesterdayExpenses = bodaExpenseDao.getTotalForDate(yesterdayDate)
            val yesterdayNet = yesterdayIncome - yesterdayExpenses

            val profitChange = if (yesterdayNet != 0.0) {
                ((netProfit - yesterdayNet) / Math.abs(yesterdayNet) * 100)
            } else 0.0

            val report = buildString {
                appendLine("💰 *Ripoti ya Leo — $date*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("📥 MAPATO (Income):")
                appendLine("   Fare jumla: KES ${"%,.0f".format(totalIncome)}")
                appendLine("   Safari: $tripCount")
                appendLine()
                appendLine("📤 MATUMIZI (Expenses):")
                if (expensesByCategory.isEmpty()) {
                    appendLine("   (Hakuna matumizi yaliyorekodiwa)")
                } else {
                    expensesByCategory.forEach { cat ->
                        val emoji = categoryEmoji(cat.category)
                        val label = categoryLabel(cat.category)
                        appendLine("   $emoji $label: KES ${"%,.0f".format(cat.total)}")
                    }
                }
                appendLine("   ─────────────────")
                appendLine("   Jumla: KES ${"%,.0f".format(totalExpenses)}")
                appendLine()
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")

                if (netProfit >= 0) {
                    appendLine("✅ FAIDA YAKO YA KWELI: KES ${"%,.0f".format(netProfit)}")
                } else {
                    appendLine("🔴 HASARA YAKO: KES ${"%,.0f".format(Math.abs(netProfit))}")
                }

                if (totalIncome > 0) {
                    val profitPercent = (netProfit / totalIncome * 100)
                    appendLine("📊 ${"%.0f".format(profitPercent)}% ya mapato yako ni faida")
                }

                if (profitChange != 0.0) {
                    val arrow = if (profitChange > 0) "📈" else "📉"
                    appendLine("$arrow ${if (profitChange > 0) "+" else ""}${"%.0f".format(profitChange)}% kuliko jana (KES ${"%,.0f".format(yesterdayNet)})")
                }

                // Key insight
                if (totalIncome > 0 && expensesByCategory.isNotEmpty()) {
                    val biggestExpense = expensesByCategory.first()
                    val percent = (biggestExpense.total / totalIncome * 100)
                    appendLine()
                    appendLine("💡 Kikubwa: ${categoryLabel(biggestExpense.category)} ni ${"%.0f".format(percent)}% ya mapato yako")
                }
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "date" to date,
                    "income" to totalIncome,
                    "expenses" to totalExpenses,
                    "net_profit" to netProfit,
                    "trip_count" to tripCount,
                    "expenses_by_category" to expensesByCategory,
                    "yesterday_net" to yesterdayNet
                ),
                message = report
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to show today's income")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // WEEKLY SUMMARY
    // ──────────────────────────────────────────────

    private suspend fun showWeek(): ToolResult {
        return try {
            val today = DateTimeUtil.today()
            val weekStart = weekStartDate()

            val totalIncome = bodaIncomeDao.getTotalBetween(weekStart, today)
            val totalExpenses = bodaExpenseDao.getTotalBetween(weekStart, today)
            val netProfit = totalIncome - totalExpenses
            val expensesByCategory = bodaExpenseDao.getByCategoryBetween(weekStart, today)
            val topRoutes = bodaIncomeDao.getTopRoutes(weekStart, today, 5)
            val bribes = bodaExpenseDao.getBribesBetween(weekStart, today)

            // Days worked
            val daysWorked = daysBetween(weekStart, today) + 1
            val avgDailyNet = if (daysWorked > 0) netProfit / daysWorked else 0.0

            val report = buildString {
                appendLine("📊 *Ripoti ya Wiki — ${weekStart} hadi $today*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("📥 Mapato: KES ${"%,.0f".format(totalIncome)}")
                appendLine("📤 Matumizi: KES ${"%,.0f".format(totalExpenses)}")
                if (netProfit >= 0) {
                    appendLine("✅ Faida: KES ${"%,.0f".format(netProfit)}")
                } else {
                    appendLine("🔴 Hasara: KES ${"%,.0f".format(Math.abs(netProfit))}")
                }
                appendLine("📅 Siku: $daysWorked | Avg: KES ${"%,.0f".format(avgDailyNet)}/siku")
                appendLine()
                appendLine("── Matumizi kwa Kategori ──")
                expensesByCategory.forEach { cat ->
                    val emoji = categoryEmoji(cat.category)
                    val label = categoryLabel(cat.category)
                    val percent = if (totalExpenses > 0) (cat.total / totalExpenses * 100) else 0.0
                    appendLine("  $emoji $label: KES ${"%,.0f".format(cat.total)} (${"%.0f".format(percent)}%)")
                }

                if (bribes > 0) {
                    appendLine()
                    appendLine("🚨 Polisi wamekula: KES ${"%,.0f".format(bribes)} wiki hii")
                    appendLine("   Hiyo ni KES ${"%,.0f".format(bribes / daysWorked)}/siku")
                }

                if (topRoutes.isNotEmpty()) {
                    appendLine()
                    appendLine("── Routes Bora ──")
                    topRoutes.forEachIndexed { i, r ->
                        appendLine("  ${i + 1}. ${r.route}: KES ${"%,.0f".format(r.avgFare)} avg (${r.count}x)")
                    }
                }

                if (totalIncome > 0) {
                    appendLine()
                    val fuelPercent = expensesByCategory.find { it.category == "fuel" }?.let {
                        (it.total / totalIncome * 100)
                    } ?: 0.0
                    if (fuelPercent > 50) {
                        appendLine("⚠️ Fuel ni ${"%.0f".format(fuelPercent)}% ya mapato yako!")
                        appendLine("   Jaribu kupunguza: panda pikipiki polepole, jaza mafuta asubuhi.")
                    }
                }
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "week_start" to weekStart,
                    "week_end" to today,
                    "income" to totalIncome,
                    "expenses" to totalExpenses,
                    "net_profit" to netProfit,
                    "avg_daily_net" to avgDailyNet,
                    "days_worked" to daysWorked,
                    "bribes_total" to bribes
                ),
                message = report
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to show week summary")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // MONTHLY SUMMARY
    // ──────────────────────────────────────────────

    private suspend fun showMonth(): ToolResult {
        return try {
            val today = DateTimeUtil.today()
            val monthStart = monthStartDate()

            val totalIncome = bodaIncomeDao.getTotalBetween(monthStart, today)
            val totalExpenses = bodaExpenseDao.getTotalBetween(monthStart, today)
            val netProfit = totalIncome - totalExpenses
            val expensesByCategory = bodaExpenseDao.getByCategoryBetween(monthStart, today)
            val bribes = bodaExpenseDao.getBribesBetween(monthStart, today)
            val daysWorked = daysBetween(monthStart, today) + 1
            val avgDailyNet = if (daysWorked > 0) netProfit / daysWorked else 0.0

            // Projections
            val daysInMonth = 30
            val projectedMonthly = avgDailyNet * daysInMonth

            val report = buildString {
                appendLine("📅 *Ripoti ya Mwezi — ${monthStart} hadi $today*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("📥 Mapato: KES ${"%,.0f".format(totalIncome)}")
                appendLine("📤 Matumizi: KES ${"%,.0f".format(totalExpenses)}")
                if (netProfit >= 0) {
                    appendLine("✅ Faida: KES ${"%,.0f".format(netProfit)}")
                } else {
                    appendLine("🔴 Hasara: KES ${"%,.0f".format(Math.abs(netProfit))}")
                }
                appendLine("📅 Siku: $daysWorked | Avg: KES ${"%,.0f".format(avgDailyNet)}/siku")
                appendLine("📈 Makadirio ya mwezi: KES ${"%,.0f".format(projectedMonthly)}")
                appendLine()
                appendLine("── Matumizi kwa Kategori ──")
                expensesByCategory.forEach { cat ->
                    val emoji = categoryEmoji(cat.category)
                    val label = categoryLabel(cat.category)
                    val percent = if (totalExpenses > 0) (cat.total / totalExpenses * 100) else 0.0
                    appendLine("  $emoji $label: KES ${"%,.0f".format(cat.total)} (${"%.0f".format(percent)}%)")
                }

                if (bribes > 0) {
                    appendLine()
                    appendLine("🚨 Polisi wiki hii: KES ${"%,.0f".format(bribes)}")
                    appendLine("   Makadirio ya mwezi: KES ${"%,.0f".format(bribes * 4)}")
                }

                // Savings potential
                if (avgDailyNet > 0) {
                    val savingsTarget = avgDailyNet * 0.2
                    appendLine()
                    appendLine("💡 Ukiweka 20% ya faida:")
                    appendLine("   KES ${"%,.0f".format(savingsTarget)}/siku → KES ${"%,.0f".format(savingsTarget * 30)}/mwezi")
                }
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "month_start" to monthStart,
                    "income" to totalIncome,
                    "expenses" to totalExpenses,
                    "net_profit" to netProfit,
                    "avg_daily_net" to avgDailyNet,
                    "projected_monthly" to projectedMonthly,
                    "bribes_total" to bribes
                ),
                message = report
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to show month summary")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // ADD INCOME
    // ──────────────────────────────────────────────

    private suspend fun addIncome(params: Map<String, String>): ToolResult {
        return try {
            val amount = params["amount"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "amount required. Sema: umepata pesa ngapi?", "MISSING_AMOUNT")

            if (amount <= 0) {
                return ToolResult.error(name, "Amount lazima iwe zaidi ya 0", "INVALID_AMOUNT")
            }

            val date = params["date"] ?: DateTimeUtil.today()
            val income = BodaIncomeEntity(
                amount = amount,
                route = params["route"] ?: "",
                tripType = params["trip_type"] ?: "fare",
                paymentMethod = params["payment_method"] ?: "cash",
                passengerCount = params["passenger_count"]?.toIntOrNull() ?: 1,
                date = date
            )
            val id = bodaIncomeDao.insert(income)

            // Get updated totals
            val totalIncome = bodaIncomeDao.getTotalForDate(date)
            val totalExpenses = bodaExpenseDao.getTotalForDate(date)
            val netProfit = totalIncome - totalExpenses

            ToolResult.success(
                name,
                data = mapOf(
                    "entry_id" to id,
                    "amount" to amount,
                    "date" to date,
                    "total_income_today" to totalIncome,
                    "total_expenses_today" to totalExpenses,
                    "net_profit_today" to netProfit
                ),
                message = "✅ Fare imeongezwa: KES ${"%,.0f".format(amount)}" +
                        (params["route"]?.let { " ($it)" } ?: "") +
                        "\n📊 Leo: Mapato KES ${"%,.0f".format(totalIncome)} | Faida KES ${"%,.0f".format(netProfit)}"
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to add income")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // ADD EXPENSE
    // ──────────────────────────────────────────────

    private suspend fun addExpense(params: Map<String, String>): ToolResult {
        return try {
            val amount = params["amount"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "amount required. Sema: umetumia pesa ngapi?", "MISSING_AMOUNT")
            val category = params["category"]
                ?: return ToolResult.error(name, "category required. Sema: ni matumizi gani? (fuel, hire_fee, police_bribe, maintenance, etc.)", "MISSING_CATEGORY")

            if (amount <= 0) {
                return ToolResult.error(name, "Amount lazima iwe zaidi ya 0", "INVALID_AMOUNT")
            }

            val date = params["date"] ?: DateTimeUtil.today()
            val expense = BodaExpenseEntity(
                amount = amount,
                category = category,
                description = params["description"] ?: "",
                date = date
            )
            val id = bodaExpenseDao.insert(expense)

            // Get updated totals
            val totalIncome = bodaIncomeDao.getTotalForDate(date)
            val totalExpenses = bodaExpenseDao.getTotalForDate(date)
            val netProfit = totalIncome - totalExpenses

            val emoji = categoryEmoji(category)
            val label = categoryLabel(category)

            val message = buildString {
                appendLine("✅ $emoji $label imeongezwa: KES ${"%,.0f".format(amount)}")
                appendLine("📊 Leo: Mapato KES ${"%,.0f".format(totalIncome)} | Matumizi KES ${"%,.0f".format(totalExpenses)}")

                if (category == "police_bribe") {
                    appendLine()
                    appendLine("🚨 Polisi wamekula KES ${"%,.0f".format(amount)} leo.")
                    val totalBribesToday = bodaExpenseDao.getTotalForDateByCategory(date, "police_bribe")
                    appendLine("   Jumla ya leo: KES ${"%,.0f".format(totalBribesToday)}")
                    appendLine("   Hii ni pesa inayoenda bure. Rekodi kila mara.")
                }

                if (netProfit >= 0) {
                    appendLine("✅ Faida bado: KES ${"%,.0f".format(netProfit)}")
                } else {
                    appendLine("🔴 Uko kwenye hasara: KES ${"%,.0f".format(Math.abs(netProfit))}")
                }
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "entry_id" to id,
                    "amount" to amount,
                    "category" to category,
                    "date" to date,
                    "total_income_today" to totalIncome,
                    "total_expenses_today" to totalExpenses,
                    "net_profit_today" to netProfit
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to add expense")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // INSIGHTS
    // ──────────────────────────────────────────────

    private suspend fun showInsights(): ToolResult {
        return try {
            val today = DateTimeUtil.today()
            val weekStart = weekStartDate()
            val monthStart = monthStartDate()

            val weekIncome = bodaIncomeDao.getTotalBetween(weekStart, today)
            val weekExpenses = bodaExpenseDao.getTotalBetween(weekStart, today)
            val weekExpensesByCategory = bodaExpenseDao.getByCategoryBetween(weekStart, today)
            val monthIncome = bodaIncomeDao.getTotalBetween(monthStart, today)
            val monthExpenses = bodaExpenseDao.getTotalBetween(monthStart, today)
            val topRoutes = bodaIncomeDao.getTopRoutes(monthStart, today, 5)
            val bribes = bodaExpenseDao.getBribesBetween(monthStart, today)
            val daysWorked = daysBetween(weekStart, today) + 1

            val insights = mutableListOf<String>()

            // Fuel efficiency insight
            val fuelExpense = weekExpensesByCategory.find { it.category == "fuel" }
            if (fuelExpense != null && weekIncome > 0) {
                val fuelPercent = (fuelExpense.total / weekIncome * 100)
                if (fuelPercent > 55) {
                    insights.add("⚠️ Fuel inakula ${"%.0f".format(fuelPercent)}% ya mapato yako! " +
                            "Wakenya wengi wanatumia 40-50%. " +
                            "Jaza mafuta asubuhi (ni cheaper), panda polepole, epuka kusimama sana.")
                } else if (fuelPercent < 40) {
                    insights.add("✅ Fuel ni ${"%.0f".format(fuelPercent)}% tu — vizuri sana! " +
                            "Wewe ni rider wa kuokoa mafuta.")
                }
            }

            // Bribe insight
            if (bribes > 0) {
                val dailyBribe = bribes / daysBetween(monthStart, today).coerceAtLeast(1)
                insights.add("🚨 Polisi wamekula KES ${"%,.0f".format(bribes)} mwezi huu " +
                        "(KES ${"%.0f".format(dailyBribe)}/siku). " +
                        "Hii ni hasara. Soma haki zako — si kila kitu ni rushwa.")
            }

            // Route insights
            if (topRoutes.isNotEmpty()) {
                val bestRoute = topRoutes.maxByOrNull { it.avgFare }
                if (bestRoute != null) {
                    insights.add("🛵 Route bora: '${bestRoute.route}' — " +
                            "average KES ${"%,.0f".format(bestRoute.avgFare)} (${bestRoute.count} safari)")
                }
            }

            // Savings insight
            val weekNet = weekIncome - weekExpenses
            if (weekNet > 0) {
                val dailyNet = weekNet / daysWorked
                val savingsTarget = dailyNet * 0.2
                insights.add("💡 Ukiweka 20% ya faida: KES ${"%.0f".format(savingsTarget)}/siku. " +
                        "Baada ya miezi 3 utakuwa na KES ${"%,.0f".format(savingsTarget * 90)}.")
            }

            // Net income warning
            val monthNet = monthIncome - monthExpenses
            val monthDays = daysBetween(monthStart, today).coerceAtLeast(1)
            val avgDaily = monthNet / monthDays
            if (avgDaily < 200 && monthIncome > 0) {
                insights.add("🔴 Faida yako ya kila siku ni KES ${"%.0f".format(avgDaily)} tu! " +
                        "Mapato ni KES ${"%,.0f".format(monthIncome / monthDays)} lakini matumizi ni KES ${"%,.0f".format(monthExpenses / monthDays)}. " +
                        "Fikiria: route bora, wakati bora, au punguza matumizi.")
            }

            if (insights.isEmpty()) {
                insights.add("📊 Rekodi safari na matumizi zako kwa wiki 2+ ili nikupate ushauri bora.")
            }

            val report = buildString {
                appendLine("💡 *Ushauri wa Pesa — Msaidizi*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                insights.forEachIndexed { i, insight ->
                    appendLine("${i + 1}. $insight")
                    appendLine()
                }
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "insights" to insights,
                    "week_net" to weekNet,
                    "month_net" to monthNet,
                    "avg_daily_net" to avgDaily
                ),
                message = report
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate insights")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // DELETE ENTRY
    // ──────────────────────────────────────────────

    private suspend fun deleteEntry(params: Map<String, String>): ToolResult {
        return try {
            val entryId = params["entry_id"]?.toLongOrNull()
                ?: return ToolResult.error(name, "entry_id required", "MISSING_ID")
            val entryType = params["entry_type"]
                ?: return ToolResult.error(name, "entry_type required (income or expense)", "MISSING_TYPE")

            when (entryType) {
                "income" -> {
                    val entries = bodaIncomeDao.getRecent(100).first()
                    val entry = entries.find { it.id == entryId }
                    if (entry != null) {
                        bodaIncomeDao.delete(entry)
                        ToolResult.success(name, message = "✅ Fare ya KES ${"%,.0f".format(entry.amount)} imefutwa.")
                    } else {
                        ToolResult.error(name, "Entry haipatikani", "NOT_FOUND")
                    }
                }
                "expense" -> {
                    val entries = bodaExpenseDao.getRecent(100).first()
                    val entry = entries.find { it.id == entryId }
                    if (entry != null) {
                        bodaExpenseDao.delete(entry)
                        ToolResult.success(name, message = "✅ ${categoryLabel(entry.category)} ya KES ${"%,.0f".format(entry.amount)} imefutwa.")
                    } else {
                        ToolResult.error(name, "Entry haipatikani", "NOT_FOUND")
                    }
                }
                else -> ToolResult.error(name, "entry_type lazima iwe 'income' au 'expense'", "INVALID_TYPE")
            }
        } catch (e: Exception) {
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // VOICE INPUT PARSER (Swahili)
    // ──────────────────────────────────────────────

    private fun parseVoiceInput(text: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val lower = text.lowercase()

        when {
            // Income patterns: "nikaongeza mia", "nimepata 500", "fare ni 300"
            lower.contains(Regex("nikaongeza|nimepata|nimelipwa|fare|mapato|nimepata|nimechukua|nimetengeneza")) -> {
                params["action"] = "add_income"
                extractAmount(lower)?.let { params["amount"] = it }
            }
            // Fuel expense patterns: "niliweka petroli", "nimejaza mafuta"
            lower.contains(Regex("petroli|mafuta|fuel|niliweka.*petroli|nimejaza")) -> {
                params["action"] = "add_expense"
                params["category"] = "fuel"
                extractAmount(lower)?.let { params["amount"] = it }
            }
            // Police bribe patterns: "polisi", "rushwa", "amenilazimisha"
            lower.contains(Regex("polisi|rushwa|amenilazimisha|checkpoint|kizuizi|chai")) -> {
                params["action"] = "add_expense"
                params["category"] = "police_bribe"
                extractAmount(lower)?.let { params["amount"] = it }
            }
            // Hire fee patterns: "hira", "daily fee", "kodi ya pikipiki"
            lower.contains(Regex("hira|hire|daily.?fee|kodi.*pikipiki|kodi.*boda")) -> {
                params["action"] = "add_expense"
                params["category"] = "hire_fee"
                extractAmount(lower)?.let { params["amount"] = it }
            }
            // Maintenance patterns: "repair", "service", "matengenezo"
            lower.contains(Regex("repair|service|matengenezo|mechanic|makanika|spare|tairi|betri")) -> {
                params["action"] = "add_expense"
                params["category"] = "maintenance"
                extractAmount(lower)?.let { params["amount"] = it }
            }
            // Sacco patterns: "sacco", "mchango"
            lower.contains(Regex("sacco|mchango|contribution")) -> {
                params["action"] = "add_expense"
                params["category"] = "sacco"
                extractAmount(lower)?.let { params["amount"] = it }
            }
            // Food patterns: "chakula", "food", "meal"
            lower.contains(Regex("chakula|food|meal|lunch|breakfast|supper")) -> {
                params["action"] = "add_expense"
                params["category"] = "food"
                extractAmount(lower)?.let { params["amount"] = it }
            }
            // View patterns: "leo", "nimepata ngapi", "ripoti"
            lower.contains(Regex("leo|nimepata ngapi|ripoti|saa ngapi|mapato.*leo|faida.*leo")) -> {
                params["action"] = "today"
            }
            // Week patterns
            lower.contains(Regex("wiki|week|hii wiki")) -> {
                params["action"] = "week"
            }
            // Month patterns
            lower.contains(Regex("mwezi|month|hii mwezi")) -> {
                params["action"] = "month"
            }
            // Insights
            lower.contains(Regex("ushauri|advice|insight|nifanye nini|tips|okoa")) -> {
                params["action"] = "insights"
            }
        }

        // Extract route if present
        val routePattern = Regex("""(\w+)\s*(?:→|->|hadi|kwenda|mpaka)\s*(\w+)""")
        routePattern.find(text)?.let {
            params["route"] = "${it.groupValues[1]} → ${it.groupValues[2]}"
        }

        return params
    }

    private fun extractAmount(text: String): String? {
        // Try Swahili amounts first: "mia tano" = 500, "elfu mbili" = 2000
        val swahiliOnes = mapOf(
            "moja" to 1, "mbili" to 2, "tatu" to 3, "nne" to 4, "tano" to 5,
            "sita" to 6, "saba" to 7, "nane" to 8, "tisa" to 9
        )

        var total = 0.0

        // "elfu X"
        for ((word, value) in swahiliOnes) {
            if (text.contains("elfu $word")) total += value * 1000
        }
        Regex("elfu\\s*(\\d+)").find(text)?.let {
            total += it.groupValues[1].toDouble() * 1000
        }

        // "mia X"
        for ((word, value) in swahiliOnes) {
            if (text.contains("mia $word")) total += value * 100
        }
        Regex("mia\\s*(\\d+)").find(text)?.let {
            total += it.groupValues[1].toDouble() * 100
        }

        if (total > 0) return total.toInt().toString()

        // Plain number
        val numberMatch = Regex("""(\d+\.?\d*)""").find(text)
        return numberMatch?.groupValues?.get(1)
    }

    // ──────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────

    private fun categoryEmoji(category: String): String = when (category) {
        "fuel" -> "⛽"
        "hire_fee" -> "🛵"
        "police_bribe" -> "🚨"
        "maintenance" -> "🔧"
        "sacco" -> "🏦"
        "airtime" -> "📱"
        "food" -> "🍚"
        else -> "📋"
    }

    private fun categoryLabel(category: String): String = when (category) {
        "fuel" -> "Petroli/Mafuta"
        "hire_fee" -> "Kodi ya Pikipiki"
        "police_bribe" -> "Polisi (Rushwa)"
        "maintenance" -> "Matengenezo"
        "sacco" -> "Sacco"
        "airtime" -> "Airtime/Data"
        "food" -> "Chakula"
        else -> category.replace("_", " ").replaceFirstChar { it.uppercase() }
    }

    private fun yesterday(date: String): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val d = sdf.parse(date) ?: return date
        val cal = Calendar.getInstance()
        cal.time = d
        cal.add(Calendar.DAY_OF_MONTH, -1)
        return sdf.format(cal.time)
    }

    private fun weekStartDate(): String {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
    }

    private fun monthStartDate(): String {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
    }

    private fun daysBetween(start: String, end: String): Int {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val startDate = sdf.parse(start)
            val endDate = sdf.parse(end)
            if (startDate != null && endDate != null) {
                ((endDate.time - startDate.time) / (24 * 60 * 60 * 1000)).toInt()
            } else 1
        } catch (e: Exception) { 1 }
    }
}
