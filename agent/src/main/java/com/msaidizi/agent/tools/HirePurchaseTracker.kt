package com.msaidizi.agent.tools

import com.msaidizi.core.util.DateTimeUtil
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

// ══════════════════════════════════════════════
// FIX 5: HIRE-PURCHASE TRACKER — P1
// ══════════════════════════════════════════════
// Many boda boda riders rent motorcycles (KES 300-500/day).
// This tool tracks daily hire fees vs actual earnings
// and shows when buying your own bike pays off.
//
// "At this rate, buying your own bike pays off in 8 months"
// "You paid KES 120,000 in hire fees this year — that's
//  enough to buy your own motorcycle!"
// ══════════════════════════════════════════════

// ──────────────────────────────────────────────
// Hire-Purchase Agreement Entity
// ──────────────────────────────────────────────

@androidx.room.Entity(tableName = "hire_purchase_agreements")
data class HirePurchaseAgreementEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerName: String,                 // who owns the motorcycle
    val ownerPhone: String = "",
    val motorcycleDescription: String = "", // make/model/color
    val dailyFee: Double,                  // KES per day
    val depositPaid: Double = 0.0,         // initial deposit if any
    val startDate: String = "",            // YYYY-MM-DD
    val endDate: String? = null,           // if fixed term
    val totalPurchasePrice: Double? = null, // agreed buyout price
    val isActive: Boolean = true,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val needsSync: Boolean = true
)

// ──────────────────────────────────────────────
// Hire Payment Entity
// ──────────────────────────────────────────────

@androidx.room.Entity(
    tableName = "hire_payments",
    indices = [androidx.room.Index(value = ["agreementId", "date"])]
)
data class HirePaymentEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val agreementId: Long,
    val amount: Double,                    // amount paid
    val paymentType: String = "daily_fee", // daily_fee | deposit | buyout | other
    val date: String = "",                 // YYYY-MM-DD
    val timestamp: Long = System.currentTimeMillis(),
    val needsSync: Boolean = true
)

// ──────────────────────────────────────────────
// DAOs
// ──────────────────────────────────────────────

@androidx.room.Dao
interface HirePurchaseAgreementDao {
    @androidx.room.Insert
    suspend fun insert(agreement: HirePurchaseAgreementEntity): Long

    @androidx.room.Update
    suspend fun update(agreement: HirePurchaseAgreementEntity)

    @androidx.room.Query("SELECT * FROM hire_purchase_agreements WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveAgreement(): HirePurchaseAgreementEntity?

    @androidx.room.Query("SELECT * FROM hire_purchase_agreements WHERE id = :id")
    suspend fun getById(id: Long): HirePurchaseAgreementEntity?

    @androidx.room.Query("SELECT * FROM hire_purchase_agreements ORDER BY createdAt DESC")
    fun getAll(): kotlinx.coroutines.flow.Flow<List<HirePurchaseAgreementEntity>>

    @androidx.room.Query("UPDATE hire_purchase_agreements SET isActive = 0 WHERE id = :id")
    suspend fun deactivate(id: Long)
}

@androidx.room.Dao
interface HirePaymentDao {
    @androidx.room.Insert
    suspend fun insert(payment: HirePaymentEntity): Long

    @androidx.room.Query("SELECT * FROM hire_payments WHERE agreementId = :agreementId ORDER BY date DESC")
    fun getByAgreement(agreementId: Long): kotlinx.coroutines.flow.Flow<List<HirePaymentEntity>>

    @androidx.room.Query("SELECT COALESCE(SUM(amount), 0) FROM hire_payments WHERE agreementId = :agreementId")
    suspend fun getTotalPaid(agreementId: Long): Double

    @androidx.room.Query("SELECT COALESCE(SUM(amount), 0) FROM hire_payments WHERE agreementId = :agreementId AND paymentType = :type")
    suspend fun getTotalPaidByType(agreementId: Long, type: String): Double

    @androidx.room.Query("SELECT COALESCE(SUM(amount), 0) FROM hire_payments WHERE agreementId = :agreementId AND date BETWEEN :startDate AND :endDate")
    suspend fun getTotalPaidBetween(agreementId: Long, startDate: String, endDate: String): Double

    @androidx.room.Query("SELECT COUNT(DISTINCT date) FROM hire_payments WHERE agreementId = :agreementId AND paymentType = 'daily_fee'")
    suspend fun getDaysPaid(agreementId: Long): Int

    @androidx.room.Query("SELECT * FROM hire_payments WHERE agreementId = :agreementId ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(agreementId: Long, limit: Int = 30): kotlinx.coroutines.flow.Flow<List<HirePaymentEntity>>

    @androidx.room.Delete
    suspend fun delete(payment: HirePaymentEntity)
}

// ──────────────────────────────────────────────
// HIRE-PURCHASE TRACKER TOOL
// ──────────────────────────────────────────────

/**
 * Hire-Purchase Tracker for boda boda riders.
 *
 * Tracks daily hire fees vs actual earnings.
 * Shows total paid, remaining buyout, and when
 * buying your own motorcycle pays off.
 *
 * Actions:
 *  - setup:      Set up hire-purchase agreement
 *  - pay:        Record daily hire payment
 *  - status:     Show current hire-purchase status
 *  - comparison: Compare hire vs buy economics
 *  - buyout:     Calculate buyout timeline
 *  - history:    View payment history
 *  - end:        End agreement (bought own bike or returned)
 *
 * Voice (Swahili):
 *  - "Nimelipa hira ya leo" → pay
 *  - "Nimelipa mia tatu hira" → pay 300
 *  - "Nimelipa ngapi jumla?" → status
 *  - "Ninunue lini pikipiki?" → buyout
 */
@Singleton
class HirePurchaseTracker @Inject constructor(
    private val agreementDao: HirePurchaseAgreementDao,
    private val paymentDao: HirePaymentDao,
    private val bodaIncomeDao: BodaIncomeDao,
    private val bodaExpenseDao: BodaExpenseDao
) : Tool {

    override val name = "hire_purchase"
    override val description = "Hire-purchase tracker for boda boda riders renting motorcycles. " +
            "Track daily hire fees (KES 300-500/day) vs actual earnings. " +
            "Shows total paid and when buying your own bike pays off. " +
            "'Umefanya KES 120,000 kwa mwaka — ungeweza kununua pikipiki yako!'"

    override val argsSchema = argSchema {
        enum("action", "Action to perform",
            listOf("setup", "pay", "status", "comparison", "buyout", "history", "end"))

        // ── setup ──
        string("owner_name", "Motorcycle owner's name", required = false)
        string("owner_phone", "Owner's phone number", required = false)
        string("motorcycle", "Motorcycle description (e.g. 'Boxer black')", required = false)
        number("daily_fee", "Daily hire fee in KES", required = false)
        number("deposit", "Deposit paid in KES", required = false)
        number("purchase_price", "Agreed purchase/buyout price in KES", required = false)
        string("start_date", "Agreement start date (YYYY-MM-DD)", required = false)

        // ── pay ──
        number("amount", "Payment amount in KES", required = false)
        enum("payment_type", "Payment type", listOf("daily_fee", "deposit", "buyout", "other"), required = false)

        // ── buyout ──
        number("own_bike_cost", "Cost of buying own motorcycle in KES", required = false)
        integer("target_months", "Target months to buy", required = false)

        // ── history ──
        integer("limit", "Number of records to return", required = false)

        // ── Voice input ──
        string("voice_text", "Raw Swahili voice input", required = false)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val effectiveParams = if (params.containsKey("voice_text") && !params.containsKey("action")) {
            parseVoiceInput(params["voice_text"]!!).toMutableMap().apply { putAll(params) }
        } else {
            params
        }

        val action = effectiveParams["action"] ?: "status"
        return when (action.lowercase()) {
            "setup" -> setupAgreement(effectiveParams)
            "pay" -> recordPayment(effectiveParams)
            "status" -> showStatus(effectiveParams)
            "comparison" -> showComparison(effectiveParams)
            "buyout" -> showBuyoutPlan(effectiveParams)
            "history" -> viewHistory(effectiveParams)
            "end" -> endAgreement(effectiveParams)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // SETUP AGREEMENT
    // ──────────────────────────────────────────────

    private suspend fun setupAgreement(params: Map<String, String>): ToolResult {
        return try {
            val dailyFee = params["daily_fee"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "daily_fee required. Sema: hira ya siku ni ngapi?", "MISSING_DAILY_FEE")
            val ownerName = params["owner_name"] ?: "Mwenyeji"

            // Deactivate any existing agreement
            val existing = agreementDao.getActiveAgreement()
            if (existing != null) {
                agreementDao.deactivate(existing.id)
            }

            val agreement = HirePurchaseAgreementEntity(
                ownerName = ownerName,
                ownerPhone = params["owner_phone"] ?: "",
                motorcycleDescription = params["motorcycle"] ?: "",
                dailyFee = dailyFee,
                depositPaid = params["deposit"]?.toDoubleOrNull() ?: 0.0,
                startDate = params["start_date"] ?: DateTimeUtil.today(),
                totalPurchasePrice = params["purchase_price"]?.toDoubleOrNull()
            )
            val id = agreementDao.insert(agreement)

            // Record deposit if provided
            val deposit = params["deposit"]?.toDoubleOrNull()
            if (deposit != null && deposit > 0) {
                paymentDao.insert(
                    HirePaymentEntity(
                        agreementId = id,
                        amount = deposit,
                        paymentType = "deposit",
                        date = agreement.startDate
                    )
                )
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "agreement_id" to id,
                    "owner" to ownerName,
                    "daily_fee" to dailyFee,
                    "deposit" to deposit,
                    "purchase_price" to agreement.totalPurchasePrice
                ),
                message = "✅ Mkataba wa hira umewekwa!\n" +
                        "━━━━━━━━━━━━━━━━━━━━\n" +
                        "👤 Mwenyeji: $ownerName\n" +
                        "🛵 ${params["motorcycle"] ?: "Pikipiki"}\n" +
                        "💰 Hira ya siku: KES ${"%,.0f".format(dailyFee)}\n" +
                        (deposit?.let { "💵 Deposit: KES ${"%,.0f".format(it)}\n" } ?: "") +
                        (agreement.totalPurchasePrice?.let { "🏷️ Bei ya kununua: KES ${"%,.0f".format(it)}\n" } ?: "") +
                        "📅 Tarehe: ${agreement.startDate}\n\n" +
                        "Rekodi malipo kila siku: 'Nimelipa hira ya leo'"
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to setup agreement")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // RECORD PAYMENT
    // ──────────────────────────────────────────────

    private suspend fun recordPayment(params: Map<String, String>): ToolResult {
        return try {
            val agreement = agreementDao.getActiveAgreement()
                ?: return ToolResult.error(
                    name,
                    "Hakuna mkataba wa hira. Weka kwanza: 'Nataka kuweka hira yangu'",
                    "NO_AGREEMENT"
                )

            val amount = params["amount"]?.toDoubleOrNull() ?: agreement.dailyFee
            val paymentType = params["payment_type"] ?: "daily_fee"
            val date = params["date"] ?: DateTimeUtil.today()

            val payment = HirePaymentEntity(
                agreementId = agreement.id,
                amount = amount,
                paymentType = paymentType,
                date = date
            )
            paymentDao.insert(payment)

            // Get totals
            val totalPaid = paymentDao.getTotalPaid(agreement.id)
            val daysPaid = paymentDao.getDaysPaid(agreement.id)
            val monthStart = monthStartDate()
            val monthPaid = paymentDao.getTotalPaidBetween(agreement.id, monthStart, date)

            // Get income data for comparison
            val todayIncome = bodaIncomeDao.getTotalForDate(date)
            val todayExpenses = bodaExpenseDao.getTotalForDate(date)
            val todayHireCost = amount
            val todayNetAfterHire = todayIncome - todayExpenses - todayHireCost

            val report = buildString {
                appendLine("✅ Malipo ya hira yamerekodwa!")
                appendLine("━━━━━━━━━━━━━━━━━━━━")
                appendLine("💰 KES ${"%,.0f".format(amount)} ($paymentType)")
                appendLine("📅 $date")
                appendLine()
                appendLine("📊 Jumla:")
                appendLine("   💸 Umefanya: KES ${"%,.0f".format(totalPaid)}")
                appendLine("   📅 Siku: $daysPaid")
                if (agreement.totalPurchasePrice != null) {
                    val remaining = agreement.totalPurchasePrice - totalPaid
                    if (remaining > 0) {
                        appendLine("   🏷️ Imebaki: KES ${"%,.0f".format(remaining)} ya kununua")
                    } else {
                        appendLine("   ✅ Umelipa zaidi ya bei ya pikipiki!")
                    }
                }
                appendLine()
                appendLine("── Leo ──")
                appendLine("   📥 Mapato: KES ${"%,.0f".format(todayIncome)}")
                appendLine("   📤 Matumizi: KES ${"%,.0f".format(todayExpenses)}")
                appendLine("   🛵 Hira: KES ${"%,.0f".format(todayHireCost)}")
                if (todayNetAfterHire >= 0) {
                    appendLine("   ✅ Faida baada ya hira: KES ${"%,.0f".format(todayNetAfterHire)}")
                } else {
                    appendLine("   🔴 Hasara baada ya hira: KES ${"%,.0f".format(Math.abs(todayNetAfterHire))}")
                }
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "amount" to amount,
                    "total_paid" to totalPaid,
                    "days_paid" to daysPaid,
                    "today_income" to todayIncome,
                    "today_net_after_hire" to todayNetAfterHire
                ),
                message = report
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to record payment")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // SHOW STATUS
    // ──────────────────────────────────────────────

    private suspend fun showStatus(params: Map<String, String>): ToolResult {
        return try {
            val agreement = agreementDao.getActiveAgreement()
                ?: return ToolResult.success(
                    name,
                    message = "🛵 Hakuna mkataba wa hira bado.\n\n" +
                            "Weka mkataba: 'Nataka kuweka hira yangu'\n" +
                            "Au kama umenunua pikipiki yako, hii haitakuhusu! 🎉"
                )

            val totalPaid = paymentDao.getTotalPaid(agreement.id)
            val depositPaid = paymentDao.getTotalPaidByType(agreement.id, "deposit")
            val dailyFeesPaid = paymentDao.getTotalPaidByType(agreement.id, "daily_fee")
            val daysPaid = paymentDao.getDaysPaid(agreement.id)
            val today = DateTimeUtil.today()
            val monthStart = monthStartDate()
            val monthPaid = paymentDao.getTotalPaidBetween(agreement.id, monthStart, today)

            // Calculate days since start
            val daysSinceStart = daysBetween(agreement.startDate, today).coerceAtLeast(1)
            val expectedDays = daysSinceStart // assume daily payment
            val paymentRate = if (expectedDays > 0) (daysPaid.toDouble() / expectedDays * 100) else 0.0

            // Average daily net income (from boda income/expense if available)
            val weekStart = weekStartDate()
            val weekIncome = bodaIncomeDao.getTotalBetween(weekStart, today)
            val weekExpenses = bodaExpenseDao.getTotalBetween(weekStart, today)
            val daysThisWeek = daysBetween(weekStart, today).coerceAtLeast(1)
            val avgDailyNet = (weekIncome - weekExpenses) / daysThisWeek
            val avgDailyNetAfterHire = avgDailyNet - agreement.dailyFee

            val report = buildString {
                appendLine("🛵 *Hali ya Hira — Pikipiki*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("👤 Mwenyeji: ${agreement.ownerName}")
                if (agreement.motorcycleDescription.isNotEmpty()) {
                    appendLine("🛵 ${agreement.motorcycleDescription}")
                }
                appendLine("💰 Hira ya siku: KES ${"%,.0f".format(agreement.dailyFee)}")
                appendLine("📅 Tangu: ${agreement.startDate} ($daysSinceStart siku)")
                appendLine()
                appendLine("── Malipo ──")
                appendLine("   💸 Jumla: KES ${"%,.0f".format(totalPaid)}")
                if (depositPaid > 0) appendLine("   💵 Deposit: KES ${"%,.0f".format(depositPaid)}")
                appendLine("   📅 Malipo ya siku: KES ${"%,.0f".format(dailyFeesPaid)} ($daysPaid siku)")
                appendLine("   📅 Mwezi huu: KES ${"%,.0f".format(monthPaid)}")
                appendLine("   📊 Kiwango cha malipo: ${"%.0f".format(paymentRate)}%")

                if (agreement.totalPurchasePrice != null) {
                    val remaining = agreement.totalPurchasePrice - totalPaid
                    appendLine()
                    appendLine("── Kununua ──")
                    appendLine("   🏷️ Bei: KES ${"%,.0f".format(agreement.totalPurchasePrice)}")
                    if (remaining > 0) {
                        appendLine("   📉 Imebaki: KES ${"%,.0f".format(remaining)}")
                        val daysToPayoff = if (agreement.dailyFee > 0) (remaining / agreement.dailyFee).toInt() else 0
                        appendLine("   📅 Siku za kulipa: ~$daysToPayoff (kwa hira ya kila siku)")
                    } else {
                        appendLine("   ✅ Umelipa kiasi cha kutosha! Sema na mwenyeji wako.")
                    }
                }

                // Net income after hire
                appendLine()
                appendLine("── Faida Baada ya Hira ──")
                appendLine("   📥 Mapato ya wastani: KES ${"%,.0f".format(avgDailyNet + agreement.dailyFee)}/siku")
                appendLine("   🛵 Hira: KES ${"%,.0f".format(agreement.dailyFee)}/siku")
                if (avgDailyNetAfterHire >= 0) {
                    appendLine("   ✅ Faida: KES ${"%,.0f".format(avgDailyNetAfterHire)}/siku")
                } else {
                    appendLine("   🔴 Hasara: KES ${"%,.0f".format(Math.abs(avgDailyNetAfterHire))}/siku")
                    appendLine("   ⚠️ Hira ni kubwa kuliko faida yako! Fikiria kubadilisha.")
                }

                // Hire as % of income
                val totalDailyIncome = avgDailyNet + agreement.dailyFee
                if (totalDailyIncome > 0) {
                    val hirePercent = (agreement.dailyFee / totalDailyIncome * 100)
                    appendLine("   📊 Hira ni ${"%.0f".format(hirePercent)}% ya mapato yako")
                    if (hirePercent > 30) {
                        appendLine("   ⚠️ Hira ni kubwa sana (>30%). Riders bora wanatumia <25%.")
                    }
                }
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "agreement" to agreement,
                    "total_paid" to totalPaid,
                    "days_paid" to daysPaid,
                    "avg_daily_net" to avgDailyNet,
                    "avg_daily_net_after_hire" to avgDailyNetAfterHire,
                    "hire_percent" to if (totalDailyIncome > 0) (agreement.dailyFee / totalDailyIncome * 100) else 0.0
                ),
                message = report
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to show status")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // COMPARISON: HIRE vs BUY
    // ──────────────────────────────────────────────

    private suspend fun showComparison(params: Map<String, String>): ToolResult {
        return try {
            val agreement = agreementDao.getActiveAgreement()

            val ownBikeCost = params["own_bike_cost"]?.toDoubleOrNull()
                ?: agreement?.totalPurchasePrice
                ?: DEFAULT_MOTORCYCLE_COST

            val dailyFee = agreement?.dailyFee ?: 500.0
            val today = DateTimeUtil.today()

            // Get actual earnings data
            val monthStart = monthStartDate()
            val weekStart = weekStartDate()
            val weekIncome = bodaIncomeDao.getTotalBetween(weekStart, today)
            val weekExpenses = bodaExpenseDao.getTotalBetween(weekStart, today)
            val daysThisWeek = daysBetween(weekStart, today).coerceAtLeast(1)
            val avgDailyGross = weekIncome / daysThisWeek
            val avgDailyExpenses = weekExpenses / daysThisWeek

            // Scenario calculations
            val dailyNetHire = avgDailyGross - avgDailyExpenses - dailyFee
            val dailyNetOwn = avgDailyGross - avgDailyExpenses // no hire fee

            // Monthly savings if own
            val monthlySavingsFromOwning = dailyFee * 30

            // How long to save for own bike
            val monthsToSave = if (dailyNetHire > 0) {
                (ownBikeCost / (dailyNetHire * 30)).toInt()
            } else {
                -1 // can't save
            }

            // Break-even: when total hire paid = bike cost
            val daysToBreakEven = if (dailyFee > 0) (ownBikeCost / dailyFee).toInt() else 0
            val monthsToBreakEven = daysToBreakEven / 30

            // Total hire cost over 1 year
            val yearlyHireCost = dailyFee * 365

            val report = buildString {
                appendLine("📊 *Linganisha: Hira vs Nunua*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("🛵 Bei ya pikipiki: KES ${"%,.0f".format(ownBikeCost)}")
                appendLine("💰 Hira ya siku: KES ${"%,.0f".format(dailyFee)}")
                appendLine()
                appendLine("── Chaguo 1: Endelea na Hira ──")
                appendLine("   💸 Gharama ya mwaka: KES ${"%,.0f".format(yearlyHireCost)}")
                appendLine("   📅 Faida/siku: KES ${"%,.0f".format(dailyNetHire)}")
                appendLine("   📅 Faida/mwezi: KES ${"%,.0f".format(dailyNetHire * 30)}")
                appendLine()
                appendLine("── Chaguo 2: Nunua Pikipiki Yako ──")
                appendLine("   💸 Gharama ya mwaka: KES 0 (hakuna hira)")
                appendLine("   📅 Faida/siku: KES ${"%,.0f".format(dailyNetOwn)}")
                appendLine("   📅 Faida/mwezi: KES ${"%,.0f".format(dailyNetOwn * 30)}")
                appendLine("   💚 Okoa/mwezi: KES ${"%,.0f".format(monthlySavingsFromOwning)}")
                appendLine("   💚 Okoa/mwaka: KES ${"%,.0f".format(yearlyHireCost)}")
                appendLine()

                if (monthsToSave > 0) {
                    appendLine("── Mpango wa Kununua ──")
                    appendLine("   💰 Bei: KES ${"%,.0f".format(ownBikeCost)}")
                    appendLine("   📅 Ukifanya kazi kila siku na kuweka faida:")
                    if (dailyNetHire > 0) {
                        val dailySavings = dailyNetHire * 0.3 // save 30% of net
                        val daysNeeded = (ownBikeCost / dailySavings).toInt()
                        val monthsNeeded = daysNeeded / 30
                        appendLine("   💡 Ukiweka 30% ya faida: KES ${"%.0f".format(dailySavings)}/siku")
                        appendLine("   📅 Utanunua baada ya ~$monthsNeeded miezi ($daysNeeded siku)")
                    }
                    appendLine()
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                    if (monthsToBreakEven <= 12) {
                        appendLine("✅ NUNUA! Baada ya miezi $monthsToBreakEven, " +
                                "utakuwa umelipa hira ya kutosha kununua pikipiki.")
                        appendLine("   Kila siku baada ya hapo ni HASARA.")
                    } else {
                        appendLine("💡 Fikiria kununua. Hira ya mwaka ni KES ${"%,.0f".format(yearlyHireCost)}.")
                    }
                } else {
                    appendLine("⚠️ Faida yako ni ndogo sana — punguza matumizi kwanza.")
                    appendLine("   Au tafuta pikipiki ya bei ndogo.")
                }
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "own_bike_cost" to ownBikeCost,
                    "daily_fee" to dailyFee,
                    "daily_net_hire" to dailyNetHire,
                    "daily_net_own" to dailyNetOwn,
                    "monthly_savings" to monthlySavingsFromOwning,
                    "yearly_hire_cost" to yearlyHireCost,
                    "months_to_break_even" to monthsToBreakEven,
                    "months_to_save" to monthsToSave
                ),
                message = report
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to show comparison")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // BUYOUT PLAN
    // ──────────────────────────────────────────────

    private suspend fun showBuyoutPlan(params: Map<String, String>): ToolResult {
        return try {
            val agreement = agreementDao.getActiveAgreement()
            val ownBikeCost = params["own_bike_cost"]?.toDoubleOrNull()
                ?: agreement?.totalPurchasePrice
                ?: DEFAULT_MOTORCYCLE_COST

            val targetMonths = params["target_months"]?.toIntOrNull() ?: 6

            val today = DateTimeUtil.today()
            val weekStart = weekStartDate()
            val weekIncome = bodaIncomeDao.getTotalBetween(weekStart, today)
            val weekExpenses = bodaExpenseDao.getTotalBetween(weekStart, today)
            val daysThisWeek = daysBetween(weekStart, today).coerceAtLeast(1)
            val avgDailyNet = (weekIncome - weekExpenses) / daysThisWeek

            val dailyFee = agreement?.dailyFee ?: 500.0
            val dailyNetAfterHire = avgDailyNet - dailyFee

            // How much to save daily to buy in target months
            val dailySavingsNeeded = ownBikeCost / (targetMonths * 30)

            // Can the rider afford it?
            val canAfford = dailyNetAfterHire >= dailySavingsNeeded

            // Progressive savings plan
            val savingsPlan = (1..targetMonths).map { month ->
                val saved = dailySavingsNeeded * 30 * month
                val remaining = ownBikeCost - saved
                mapOf(
                    "month" to month,
                    "total_saved" to saved,
                    "remaining" to remaining.coerceAtLeast(0.0)
                )
            }

            val report = buildString {
                appendLine("🎯 *Mpango wa Kununua Pikipiki*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("🏷️ Bei: KES ${"%,.0f".format(ownBikeCost)}")
                appendLine("📅 Lengo: miezi $targetMonths")
                appendLine("💰 Unahitaji kuweka: KES ${"%,.0f".format(dailySavingsNeeded)}/siku")
                appendLine()
                appendLine("── Hali Yako ──")
                appendLine("   📥 Mapato ya wastani: KES ${"%,.0f".format(avgDailyNet + dailyFee)}/siku")
                appendLine("   🛵 Hira: KES ${"%,.0f".format(dailyFee)}/siku")
                appendLine("   ✅ Faida: KES ${"%,.0f".format(dailyNetAfterHire)}/siku")

                if (canAfford) {
                    appendLine()
                    appendLine("✅ UNAWEZA! Ukiweka KES ${"%,.0f".format(dailySavingsNeeded)}/siku:")
                    savingsPlan.forEach { m ->
                        appendLine("   Mwezi ${m["month"]}: KES ${"%,.0f".format(m["total_saved"])} imewekwa (imebaki KES ${"%,.0f".format(m["remaining"])})")
                    }
                    appendLine()
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine("🎉 Baada ya miezi $targetMonths, pikipiki ni YAKO!")
                    appendLine("   Hira ya KES ${"%,.0f".format(dailyFee)}/siku itaisha!")
                } else {
                    val deficit = dailySavingsNeeded - dailyNetAfterHire
                    appendLine()
                    appendLine("⚠️ Hauwezi kufikia lengo la miezi $targetMonths.")
                    appendLine("   Unahitaji KES ${"%,.0f".format(dailySavingsNeeded)}/siku lakini faida ni KES ${"%,.0f".format(dailyNetAfterHire)}/siku")
                    appendLine("   Pengo: KES ${"%,.0f".format(deficit)}/siku")
                    appendLine()
                    appendLine("💡 Mapendekezo:")
                    appendLine("   • Ongeza safari (fanya kazi masaa zaidi)")
                    appendLine("   • Punguza matumizi mengine")
                    appendLine("   • Fikiria miezi zaidi (target ya miezi ${targetMonths + 3})")
                    val adjustedMonths = if (dailyNetAfterHire > 0) (ownBikeCost / (dailyNetAfterHire * 0.3) / 30).toInt() else 99
                    appendLine("   • Au weka 30% ya faida: miezi ~$adjustedMonths")
                }

                // Motivation
                val totalHireInTargetMonths = dailyFee * targetMonths * 30
                appendLine()
                appendLine("💡 Ukikaa na hira kwa miezi $targetMonths utalipa:")
                appendLine("   KES ${"%,.0f".format(totalHireInTargetMonths)} — pesa ambayo ingekuwa yako!")
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "bike_cost" to ownBikeCost,
                    "target_months" to targetMonths,
                    "daily_savings_needed" to dailySavingsNeeded,
                    "can_afford" to canAfford,
                    "savings_plan" to savingsPlan
                ),
                message = report
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to show buyout plan")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // VIEW HISTORY
    // ──────────────────────────────────────────────

    private suspend fun viewHistory(params: Map<String, String>): ToolResult {
        return try {
            val agreement = agreementDao.getActiveAgreement()
                ?: return ToolResult.success(name, message = "📜 Hakuna mkataba wa hira bado.")

            val limit = params["limit"]?.toIntOrNull() ?: 30
            val payments = paymentDao.getRecent(agreement.id, limit).first()

            if (payments.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "📜 Hakuna malipo ya hira bado.\n" +
                            "Sema: 'Nimelipa hira ya leo' kuanza."
                )
            }

            val dateFormat = SimpleDateFormat("dd MMM", Locale.getDefault())
            val totalPaid = paymentDao.getTotalPaid(agreement.id)

            val report = buildString {
                appendLine("📜 *Historia ya Malipo ya Hira*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("💰 Jumla: KES ${"%,.0f".format(totalPaid)}")
                appendLine()
                payments.forEach { p ->
                    val emoji = when (p.paymentType) {
                        "daily_fee" -> "📅"
                        "deposit" -> "💵"
                        "buyout" -> "🏷️"
                        else -> "💰"
                    }
                    appendLine("  $emoji ${dateFormat.format(Date(p.timestamp))} — KES ${"%,.0f".format(p.amount)} (${p.paymentType})")
                }
            }

            ToolResult.success(name, data = mapOf("payments" to payments, "total_paid" to totalPaid), message = report)
        } catch (e: Exception) {
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // END AGREEMENT
    // ──────────────────────────────────────────────

    private suspend fun endAgreement(params: Map<String, String>): ToolResult {
        return try {
            val agreement = agreementDao.getActiveAgreement()
                ?: return ToolResult.success(name, message = "🛵 Hakuna mkataba wa hira unaofanya kazi.")

            val totalPaid = paymentDao.getTotalPaid(agreement.id)
            val daysPaid = paymentDao.getDaysPaid(agreement.id)

            agreementDao.deactivate(agreement.id)

            val report = buildString {
                appendLine("🏁 *Mkataba wa Hira Umekamilika!*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("👤 Mwenyeji: ${agreement.ownerName}")
                appendLine("💰 Jumla uliyolipa: KES ${"%,.0f".format(totalPaid)}")
                appendLine("📅 Siku: $daysPaid")
                appendLine("💰 Hira ya siku: KES ${"%,.0f".format(agreement.dailyFee)}")
                appendLine()
                if (totalPaid >= (agreement.totalPurchasePrice ?: Double.MAX_VALUE)) {
                    appendLine("🎉 Umelipa kiasi cha kutosha kununua pikipiki!")
                    appendLine("   Pikipiki ni YAKO sasa!")
                } else {
                    appendLine("💡 Umelipa KES ${"%,.0f".format(totalPaid)} kwa hira.")
                    appendLine("   Hiyo ingekuwa na Kununua pikipiki ya bei ndogo!")
                }
                appendLine()
                appendLine("Hongera kwa kumaliza mkataba! 🙏")
            }

            ToolResult.success(
                name,
                data = mapOf("total_paid" to totalPaid, "days_paid" to daysPaid),
                message = report
            )
        } catch (e: Exception) {
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // VOICE INPUT PARSER
    // ──────────────────────────────────────────────

    private fun parseVoiceInput(text: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val lower = text.lowercase()

        when {
            // Pay patterns: "nimelipa hira", "hira ya leo"
            lower.contains(Regex("nimelipa.*hira|hira.*leo|malipo|lipa.*hira|daily.*fee")) -> {
                params["action"] = "pay"
                extractAmount(lower)?.let { params["amount"] = it }
            }
            // Status patterns: "hira yangu", "nimelipa ngapi"
            lower.contains(Regex("hira.*yangu|nimelipa.*ngapi|jumla.*hira|status|state|hali")) -> {
                params["action"] = "status"
            }
            // Comparison: "linganisha", "nunue vs hira"
            lower.contains(Regex("linganisha|compare|hira.*nunua|nunua.*hira|buy.*rent")) -> {
                params["action"] = "comparison"
            }
            // Buyout: "nunue lini", "mpango wa kununua"
            lower.contains(Regex("nunue.*lini|mpango.*kununua|buyout|buy.*plan|pikipiki.*yangu")) -> {
                params["action"] = "buyout"
            }
            // Setup: "weka hira", "anza hira"
            lower.contains(Regex("weka.*hira|anza.*hira|setup.*hire|new.*agreement")) -> {
                params["action"] = "setup"
                extractAmount(lower)?.let { params["daily_fee"] = it }
            }
            // History
            lower.contains(Regex("historia|history|malipo.*yangu|records")) -> {
                params["action"] = "history"
            }
            // End
            lower.contains(Regex("maliza|end|stop|close|nimemaliza")) -> {
                params["action"] = "end"
            }
        }

        return params
    }

    private fun extractAmount(text: String): String? {
        val swahiliOnes = mapOf(
            "moja" to 1, "mbili" to 2, "tatu" to 3, "nne" to 4, "tano" to 5,
            "sita" to 6, "saba" to 7, "nane" to 8, "tisa" to 9
        )

        var total = 0.0
        for ((word, value) in swahiliOnes) {
            if (text.contains("mia $word")) total += value * 100
            if (text.contains("elfu $word")) total += value * 1000
        }
        Regex("mia\\s*(\\d+)").find(text)?.let { total += it.groupValues[1].toDouble() * 100 }
        Regex("elfu\\s*(\\d+)").find(text)?.let { total += it.groupValues[1].toDouble() * 1000 }

        if (total > 0) return total.toInt().toString()

        Regex("""(\d+\.?\d*)""").find(text)?.let { return it.groupValues[1] }
        return null
    }

    // ──────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────

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

    companion object {
        const val DEFAULT_MOTORCYCLE_COST = 120_000.0 // KES, typical used motorcycle
    }
}
