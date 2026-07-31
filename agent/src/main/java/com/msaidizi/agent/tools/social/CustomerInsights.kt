package com.msaidizi.agent.tools.social

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.msaidizi.core.database.CustomerDao
import com.msaidizi.core.database.CustomerProfileDao
import com.msaidizi.core.database.CustomerVisitDao
import com.msaidizi.core.database.DebtDao
import com.msaidizi.core.database.SaleDao
import com.msaidizi.core.util.DateTimeUtil
import com.msaidizi.core.model.CustomerProfileEntity
import com.msaidizi.core.model.CustomerVisitEntity
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import com.msaidizi.agent.tools.core.*

/**
 * CustomerInsights — Auto-build customer profiles from transactions.
 *
 * Makes the customer base visible: who buys, who owes, who's loyal,
 * who's drifting away. Answers: "Wateja wangu wakoje?"
 *
 * Features:
 *  1. Auto-build profiles from SaleDao transactions
 *  2. Segment customers: VIP, Regular, Occasional, Lapsed, New
 *  3. Churn alerts for customers who haven't visited recently
 *  4. Per-customer credit tracking integrated with DebtTracker
 *  5. Top customers by revenue contribution
 *  6. Visit history and trends
 *
 * 6 Actions: profile, segment, churn_alert, top_customers, history, loyalty
 * Voice-first, bilingual (Kiswahili + English).
 */
@Singleton
class CustomerInsights @Inject constructor(
    private val profileDao: CustomerProfileDao,
    private val visitDao: CustomerVisitDao,
    private val customerDao: CustomerDao,
    private val saleDao: SaleDao,
    private val debtDao: DebtDao,
    private val gson: Gson
) : Tool {

    override val name = "customer_insights"
    override val description = "Auto-build customer profiles, segment VIP/Regular/Occasional/Lapsed/New, churn alerts, credit tracking, top customers. Voice: 'Wateja wangu wakoje?'"

    override val argsSchema = argSchema {
        enum(
            "action", "Customer insight action to perform",
            listOf(
                "profile",       // Detailed profile for one customer
                "segment",       // Loyalty segment breakdown (VIP/Regular/Occasional/Lapsed/New)
                "churn_alert",   // Customers at risk of churning
                "top_customers", // Top N customers by revenue
                "history",       // Customer list with visit/spend history
                "loyalty"        // Loyalty overview with all segments
            ),
            required = false
        )
        string("worker_id", "Worker/business ID", required = true)
        string("customer_key", "Customer phone number or name (for profile action)", required = false)
        integer("days_threshold", "Days since last visit to flag as churn risk (default: 14)", required = false)
        integer("limit", "Max results to return (default: 20)", required = false)
        string("sort_by", "Sort customers by: total_spend, visits_this_month, days_since_last_visit, credit_outstanding", required = false)
        string("voice_input", "Raw Swahili/English voice text to parse", required = false)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "loyalty"
        val workerId = params["worker_id"]

        if (workerId.isNullOrBlank()) {
            return ToolResult.error(name, "Worker ID is required. Example: worker_id='njeri_001'", "MISSING_WORKER_ID")
        }

        // Voice input parsing
        if (!params["voice_input"].isNullOrBlank()) {
            return parseVoiceInput(params["voice_input"]!!, workerId)
        }

        // Auto-sync profiles from transactions before any read action
        syncProfilesFromTransactions(workerId)

        return when (action.lowercase()) {
            "profile" -> getCustomerProfile(workerId, params)
            "segment" -> getSegmentBreakdown(workerId)
            "churn_alert" -> getChurnAlerts(workerId, params)
            "top_customers" -> getTopCustomers(workerId, params)
            "history" -> getCustomerHistory(workerId, params)
            "loyalty" -> getLoyaltyOverview(workerId)
            else -> ToolResult.error(
                name,
                "Unknown action: $action. Use: profile, segment, churn_alert, top_customers, history, loyalty",
                "INVALID_ACTION"
            )
        }
    }

    // ──────────────────────────────────────────────
    // PROFILE — Detailed view of one customer
    // ──────────────────────────────────────────────

    private suspend fun getCustomerProfile(workerId: String, params: Map<String, String>): ToolResult {
        return try {
            val customerKey = params["customer_key"]?.trim()
            if (customerKey.isNullOrBlank()) {
                return ToolResult.error(name, "Customer key (phone or name) is required for profile. Example: customer_key='John'", "MISSING_CUSTOMER_KEY")
            }

            val profile = profileDao.getByKey(workerId, customerKey)
            if (profile == null) {
                return ToolResult.error(name, "Customer '$customerKey' not found. Check the name or phone number.", "CUSTOMER_NOT_FOUND")
            }

            // Get recent visits
            val recentVisits = visitDao.getRecentByCustomer(workerId, customerKey, 5)

            // Get credit info from debt tracker
            val creditDebts = debtDao.getActiveByCustomer(profile.customerName ?: customerKey).first()
            val creditOutstanding = creditDebts.sumOf { it.outstandingBalance }

            val segmentEmoji = segmentToEmoji(profile.segment)
            val segmentLabel = segmentToSwahili(profile.segment)

            val message = buildString {
                appendLine("$segmentEmoji *Wasifu wa Mteja: ${profile.customerName ?: customerKey}*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("📊 *Segmenti:* $segmentLabel")
                appendLine("📅 *Ziara jumla:* ${profile.totalVisits}")
                appendLine("📅 *Ziara mwezi huu:* ${profile.visitsThisMonth}")
                appendLine("📈 *Wastani wa ziara/mwezi:* ${"%.1f".format(profile.avgVisitsPerMonth)}")
                appendLine()
                appendLine("💰 *Matumizi jumla:* KES ${"%,.0f".format(profile.totalSpend)}")
                appendLine("💰 *Matumizi mwezi huu:* KES ${"%,.0f".format(profile.spendThisMonth)}")
                appendLine("💰 *Wastani kwa ziara:* KES ${"%,.0f".format(profile.avgSpendPerVisit)}")
                appendLine()
                appendLine("📆 *Ziara ya kwanza:* ${profile.firstVisit ?: 'N/A'}")
                appendLine("📆 *Ziara ya mwisho:* ${profile.lastVisit ?: 'N/A'}")
                appendLine("⏰ *Siku tangu ziara ya mwisho:* ${profile.daysSinceLastVisit}")
                appendLine()

                // Credit section
                if (creditOutstanding > 0) {
                    appendLine("💳 *Deni:* KES ${"%,.0f".format(creditOutstanding)}")
                    creditDebts.forEach { debt ->
                        val overdue = debt.dueDate != null && debt.dueDate < System.currentTimeMillis()
                        val statusEmoji = if (overdue) "🔴" else "🟡"
                        appendLine("   $statusEmoji ${debt.product}: KES ${"%,.0f".format(debt.outstandingBalance)}")
                    }
                    appendLine()
                }

                // Top products
                val topProducts: List<Map<String, Any>> = try {
                    val type = object : TypeToken<List<Map<String, Any>>>() {}.type
                    gson.fromJson(profile.topProductsJson, type) ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
                if (topProducts.isNotEmpty()) {
                    appendLine("🛒 *Bidhaa anazopenda:*")
                    topProducts.forEachIndexed { i, prod ->
                        val name = prod["name"] ?: prod["product"] ?: "?"
                        appendLine("   ${i + 1}. $name")
                    }
                    appendLine()
                }

                // Recent visits
                if (recentVisits.isNotEmpty()) {
                    appendLine("📋 *Ziara za hivi karibuni:*")
                    recentVisits.forEach { visit ->
                        appendLine("   • ${visit.visitDate} — KES ${"%,.0f".format(visit.amount)} (${visit.paymentMethod ?: "cash"})")
                    }
                }

                appendLine()
                appendLine("📊 *Mchango wa mapato:* ${"%.1f".format(profile.revenuePct)}%")
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "customer_key" to customerKey,
                    "name" to profile.customerName,
                    "segment" to profile.segment,
                    "total_visits" to profile.totalVisits,
                    "total_spend" to profile.totalSpend,
                    "days_since_last_visit" to profile.daysSinceLastVisit,
                    "credit_outstanding" to creditOutstanding
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get customer profile")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // SEGMENT — Breakdown by loyalty segment
    // ──────────────────────────────────────────────

    private suspend fun getSegmentBreakdown(workerId: String): ToolResult {
        return try {
            val summaries = profileDao.getSegmentSummary(workerId)
            val totalCustomers = profileDao.getCustomerCount(workerId)

            if (totalCustomers == 0) {
                return ToolResult.success(
                    toolName = name,
                    message = "📋 *Hakuna wateja bado.*\n\nAnza kuuza — wateja watajitengeneza automatically kutoka kwa mauzo yako!"
                )
            }

            val totalRevenue = profileDao.getTotalRevenue(workerId) ?: 0.0
            val summaryMap = summaries.associateBy { it.segment }

            val message = buildString {
                appendLine("📊 *Wateja kwa Segmenti*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("👥 Jumla ya wateja: $totalCustomers")
                appendLine("💰 Jumla ya mapato: KES ${"%,.0f".format(totalRevenue)}")
                appendLine()

                val segments = listOf("vip", "regular", "occasional", "lapsed", "new")
                segments.forEach { seg ->
                    val summary = summaryMap[seg]
                    val count = summary?.count ?: 0
                    val spend = summary?.totalSpend ?: 0.0
                    val emoji = segmentToEmoji(seg)
                    val label = segmentToSwahili(seg)
                    val pct = if (totalCustomers > 0) (count.toDouble() / totalCustomers * 100) else 0.0

                    appendLine("$emoji *$label:* $count wateja (${"%.0f".format(pct)}%) — KES ${"%,.0f".format(spend)}")
                }

                // Concentration insight
                val top10 = profileDao.getTopCustomersOnce(workerId, 10)
                val top10Spend = top10.sumOf { it.totalSpend }
                if (totalRevenue > 0) {
                    val concentration = top10Spend / totalRevenue * 100
                    appendLine()
                    appendLine("📈 Wateja 10 bora wanaleta ${"%.0f".format(concentration)}% ya mapato yako yote.")
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "total_customers" to totalCustomers,
                    "segments" to summaries.associate { it.segment to mapOf("count" to it.count, "total_spend" to it.totalSpend) }
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get segment breakdown")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // CHURN ALERT — Customers at risk of leaving
    // ──────────────────────────────────────────────

    private suspend fun getChurnAlerts(workerId: String, params: Map<String, String>): ToolResult {
        return try {
            val threshold = params["days_threshold"]?.toIntOrNull() ?: 14
            val atRisk = profileDao.getChurnRiskOnce(workerId, threshold)

            if (atRisk.isEmpty()) {
                return ToolResult.success(
                    toolName = name,
                    message = "🎉 *Hakuna wateja walio hatarini!*\n\nWateja wako wote wamekuja hivi karibuni. Vizuri sana! 💪"
                )
            }

            val message = buildString {
                appendLine("⚠️ *Wateja Walio Hatarini*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("Wateja hawa hawajaja kwa siku $threshold+:")
                appendLine()

                atRisk.forEach { customer ->
                    val segEmoji = segmentToEmoji(customer.segment)
                    val segLabel = segmentToSwahili(customer.segment)
                    val name = customer.customerName ?: customer.customerKey

                    appendLine("$segEmoji *$name*")
                    appendLine("   📅 Siku ${customer.daysSinceLastVisit} tangu ziara ya mwisho")
                    appendLine("   📊 Segmenti ya zamani: $segLabel")
                    appendLine("   💰 Jumla ya matumizi: KES ${"%,.0f".format(customer.totalSpend)}")
                    appendLine("   📈 Wastani wa ziara: ${"%.1f".format(customer.avgVisitsPerMonth)}/mwezi")
                    if (customer.creditOutstanding > 0) {
                        appendLine("   💳 Deni: KES ${"%,.0f".format(customer.creditOutstanding)}")
                    }
                    appendLine()
                }

                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("💡 *Mapendekezo:*")
                appendLine("• Tuma ujumbe wa kumkaribisha tena")
                appendLine("• Punguza bei kwa muda mfupi")
                appendLine("• Uliza kama kuna tatizo lolote")

                // Specific advice for high-value churners
                val vipChurners = atRisk.filter { it.segment == "vip" || it.segment == "regular" }
                if (vipChurners.isNotEmpty()) {
                    appendLine()
                    appendLine("🚨 *Umuhimu:* Wateja ${vipChurners.size} ni VIP/Regular — wape kipaumbele!")
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "at_risk_count" to atRisk.size,
                    "threshold_days" to threshold,
                    "customers" to atRisk.map {
                        mapOf(
                            "key" to it.customerKey,
                            "name" to it.customerName,
                            "segment" to it.segment,
                            "days_since_visit" to it.daysSinceLastVisit,
                            "total_spend" to it.totalSpend
                        )
                    }
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get churn alerts")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // TOP CUSTOMERS — Revenue leaders
    // ──────────────────────────────────────────────

    private suspend fun getTopCustomers(workerId: String, params: Map<String, String>): ToolResult {
        return try {
            val limit = params["limit"]?.toIntOrNull() ?: 10
            val sortBy = params["sort_by"] ?: "total_spend"

            val customers = when (sortBy) {
                "visits_this_month" -> profileDao.getByWorker(workerId).first()
                    .sortedByDescending { it.visitsThisMonth }
                    .take(limit)
                "days_since_last_visit" -> profileDao.getByRecency(workerId, limit).first()
                "credit_outstanding" -> profileDao.getWithCredit(workerId).first().take(limit)
                else -> profileDao.getTopCustomers(workerId, limit).first()
            }

            val totalRevenue = profileDao.getTotalRevenue(workerId) ?: 0.0

            if (customers.isEmpty()) {
                return ToolResult.success(
                    toolName = name,
                    message = "📋 *Hakuna wateja bado.*\n\nAnza kuuza — wateja watajitokeza!"
                )
            }

            val message = buildString {
                appendLine("🏆 *Wateja Bora — Top $limit*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                customers.forEachIndexed { i, customer ->
                    val name = customer.customerName ?: customer.customerKey
                    val emoji = segmentToEmoji(customer.segment)
                    val runningPct = if (totalRevenue > 0) (customer.totalSpend / totalRevenue * 100) else 0.0

                    appendLine("${i + 1}. $emoji *$name*")
                    appendLine("   💰 KES ${"%,.0f".format(customer.totalSpend)} (${"%.1f".format(runningPct)}%)")
                    appendLine("   📅 Ziara: ${customer.totalVisits} jumla, ${customer.visitsThisMonth} mwezi huu")
                    appendLine("   📊 Segmenti: ${segmentToSwahili(customer.segment)}")
                    if (customer.creditOutstanding > 0) {
                        appendLine("   💳 Deni: KES ${"%,.0f".format(customer.creditOutstanding)}")
                    }
                    appendLine()
                }

                // Concentration analysis
                val topSpend = customers.sumOf { it.totalSpend }
                if (totalRevenue > 0) {
                    val pct = topSpend / totalRevenue * 100
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine("📊 Wateja hawa ${customers.size} wanaleta ${"%.0f".format(pct)}% ya mapato yako yote.")
                    if (pct > 50) {
                        appendLine("⚠️ Mtindo wa hatari: tegemeo kwa wateja wachache. Ongeza wateja wapya!")
                    }
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "count" to customers.size,
                    "sort_by" to sortBy,
                    "customers" to customers.map {
                        mapOf(
                            "key" to it.customerKey,
                            "name" to it.customerName,
                            "segment" to it.segment,
                            "total_spend" to it.totalSpend,
                            "total_visits" to it.totalVisits
                        )
                    }
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get top customers")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // HISTORY — Full customer list with history
    // ──────────────────────────────────────────────

    private suspend fun getCustomerHistory(workerId: String, params: Map<String, String>): ToolResult {
        return try {
            val sortBy = params["sort_by"] ?: "total_spend"
            val limit = params["limit"]?.toIntOrNull() ?: 20

            val allCustomers = profileDao.getByWorker(workerId).first()
            val sorted = when (sortBy) {
                "visits_this_month" -> allCustomers.sortedByDescending { it.visitsThisMonth }
                "days_since_last_visit" -> allCustomers.sortedByDescending { it.daysSinceLastVisit }
                "credit_outstanding" -> allCustomers.sortedByDescending { it.creditOutstanding }
                else -> allCustomers.sortedByDescending { it.totalSpend }
            }.take(limit)

            val totalCount = profileDao.getCustomerCount(workerId)
            val repeatCount = profileDao.getRepeatCustomerCount(workerId)
            val totalRevenue = profileDao.getTotalRevenue(workerId) ?: 0.0

            if (sorted.isEmpty()) {
                return ToolResult.success(
                    toolName = name,
                    message = "📋 *Hakuna wateja bado.*\n\nWateja watajitengeneza automatically unapofanya mauzo."
                )
            }

            val message = buildString {
                appendLine("📋 *Orodha ya Wateja*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("👥 Jumla: $totalCount | 🔁 Warudi: $repeatCount (${"%.0f".format(if (totalCount > 0) repeatCount.toDouble() / totalCount * 100 else 0.0)}%)")
                appendLine("💰 Mapato jumla: KES ${"%,.0f".format(totalRevenue)}")
                appendLine()

                sorted.forEach { customer ->
                    val name = customer.customerName ?: customer.customerKey
                    val emoji = segmentToEmoji(customer.segment)

                    appendLine("$emoji *$name*")
                    appendLine("   💰 KES ${"%,.0f".format(customer.totalSpend)} | 📅 ${customer.totalVisits} ziara | ⏰ Siku ${customer.daysSinceLastVisit}")
                    if (customer.creditOutstanding > 0) {
                        appendLine("   💳 Deni: KES ${"%,.0f".format(customer.creditOutstanding)}")
                    }
                }

                if (totalCount > limit) {
                    appendLine()
                    appendLine("... na wateja ${totalCount - limit} wengine. Ongeza limit kuona zaidi.")
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "total_customers" to totalCount,
                    "repeat_customers" to repeatCount,
                    "showing" to sorted.size,
                    "sort_by" to sortBy
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get customer history")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // LOYALTY — Full loyalty overview
    // ──────────────────────────────────────────────

    private suspend fun getLoyaltyOverview(workerId: String): ToolResult {
        return try {
            val totalCustomers = profileDao.getCustomerCount(workerId)
            val repeatCount = profileDao.getRepeatCustomerCount(workerId)
            val totalRevenue = profileDao.getTotalRevenue(workerId) ?: 0.0
            val segmentSummary = profileDao.getSegmentSummary(workerId)
            val summaryMap = segmentSummary.associateBy { it.segment }

            val top5 = profileDao.getTopCustomersOnce(workerId, 5)
            val churnRisk = profileDao.getChurnRiskOnce(workerId, 14)
            val withCredit = profileDao.getWithCreditOnce(workerId)
            val totalCredit = profileDao.getTotalCreditOutstanding(workerId) ?: 0.0

            if (totalCustomers == 0) {
                return ToolResult.success(
                    toolName = name,
                    message = "📋 *Hakuna wateja bado.*\n\nAnza kuuza — Msaidizi ataunda wasifu wa wateja wako automatically! 🛒"
                )
            }

            val repeatPct = if (totalCustomers > 0) repeatCount.toDouble() / totalCustomers * 100 else 0.0

            val message = buildString {
                appendLine("👥 *Muhtasari wa Wateja — ${DateTimeUtil.today()}*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("📊 *Wateja:* $totalCustomers jumla")
                appendLine("🔁 *Warudi:* $repeatCount (${"%.0f".format(repeatPct)}%)")
                appendLine("💰 *Mapato jumla:* KES ${"%,.0f".format(totalRevenue)}")

                // Segment breakdown
                appendLine()
                appendLine("📊 *Segmenti:*")
                val segments = listOf("vip", "regular", "occasional", "lapsed", "new")
                segments.forEach { seg ->
                    val summary = summaryMap[seg]
                    val count = summary?.count ?: 0
                    val emoji = segmentToEmoji(seg)
                    val label = segmentToSwahili(seg)
                    appendLine("   $emoji $label: $count")
                }

                // Top customers
                if (top5.isNotEmpty()) {
                    appendLine()
                    appendLine("🏆 *Wateja 5 Bora:*")
                    top5.forEachIndexed { i, customer ->
                        val name = customer.customerName ?: customer.customerKey
                        appendLine("   ${i + 1}. $name — KES ${"%,.0f".format(customer.totalSpend)} (${customer.totalVisits} ziara)")
                    }
                }

                // Churn risk summary
                if (churnRisk.isNotEmpty()) {
                    appendLine()
                    appendLine("⚠️ *Wateja ${churnRisk.size} wako hatarini* (hawajaja siku 14+)")
                    val vipRisk = churnRisk.filter { it.segment == "vip" || it.segment == "regular" }
                    if (vipRisk.isNotEmpty()) {
                        appendLine("   🚨 ${vipRisk.size} ni VIP/Regular — wape kipaumbele!")
                    }
                }

                // Credit summary
                if (withCredit.isNotEmpty()) {
                    appendLine()
                    appendLine("💳 *Deni:*")
                    appendLine("   Jumla: KES ${"%,.0f".format(totalCredit)}")
                    appendLine("   Wateja ${withCredit.size} wana deni")
                }

                // Revenue concentration
                if (top5.isNotEmpty() && totalRevenue > 0) {
                    val top5Spend = top5.sumOf { it.totalSpend }
                    val concentration = top5Spend / totalRevenue * 100
                    appendLine()
                    appendLine("📈 *Umakini:* Wateja 5 bora wanaleta ${"%.0f".format(concentration)}% ya mapato.")
                    when {
                        concentration > 60 -> appendLine("   ⚠️ Hatari: tegemeo kwa wateja wachache. Ongeza wateja wapya!")
                        concentration > 40 -> appendLine("   🟡 Wastani. Jaribu kuongeza wateja wapya.")
                        else -> appendLine("   ✅ Nzuri! Mapato yako yameenea vizuri.")
                    }
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "total_customers" to totalCustomers,
                    "repeat_customers" to repeatCount,
                    "total_revenue" to totalRevenue,
                    "total_credit_outstanding" to totalCredit,
                    "churn_risk_count" to churnRisk.size,
                    "segments" to summaryMap.mapValues { mapOf("count" to it.value.count, "spend" to it.value.totalSpend) }
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get loyalty overview")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // AUTO-SYNC — Build profiles from transactions
    // ──────────────────────────────────────────────

    /**
     * Sync customer profiles from SaleDao transaction data.
     * Runs before every read action to keep profiles fresh.
     * Customers are identified by phone number or name from sales records.
     */
    private suspend fun syncProfilesFromTransactions(workerId: String) {
        try {
            val now = System.currentTimeMillis()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

            // Get all sales for the last 90 days (rolling window)
            val ninetyDaysAgo = now - TimeUnit.DAYS.toMillis(90)
            val sales = saleDao.getSalesBetween(ninetyDaysAgo, now).first()

            if (sales.isEmpty()) return

            // Group sales by customer (use customerName or "anonymous_<date>" fallback)
            val salesByCustomer = sales.groupBy { sale ->
                // Use customerName if available, otherwise create anonymous key
                sale.customerName?.trim()?.takeIf { it.isNotBlank() }
                    ?: "walk_in_${dateFormat.format(sale.timestamp)}"
            }

            // This month boundaries
            val cal = Calendar.getInstance()
            val thisMonthStart = dateFormat.format(cal.apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }.timeInMillis)

            for ((customerKey, customerSales) in salesByCustomer) {
                val isAnonymous = customerKey.startsWith("walk_in_")
                if (isAnonymous) continue // Skip anonymous walk-ins for profile building

                val existing = profileDao.getByKey(workerId, customerKey)

                val totalVisits = customerSales.size
                val totalSpend = customerSales.sumOf { it.totalPrice }
                val visitDates = customerSales.map { dateFormat.format(it.timestamp) }.distinct().sorted()
                val firstVisit = visitDates.firstOrNull()
                val lastVisit = visitDates.lastOrNull()

                // Calculate days since last visit
                val lastVisitDate = lastVisit?.let {
                    dateFormat.parse(it)?.time
                } ?: now
                val daysSince = TimeUnit.MILLISECONDS.toDays(now - lastVisitDate).toInt()

                // This month visits
                val thisMonthVisits = customerSales.count { sale ->
                    dateFormat.format(sale.timestamp) >= thisMonthStart
                }
                val thisMonthSpend = customerSales.filter { sale ->
                    dateFormat.format(sale.timestamp) >= thisMonthStart
                }.sumOf { it.totalPrice }

                // Average visits per month (over last 3 months)
                val threeMonthsAgo = now - TimeUnit.DAYS.toMillis(90)
                val monthsActive = 3.0
                val avgVisitsPerMonth = totalVisits / monthsActive

                // Average spend per visit
                val avgSpendPerVisit = if (totalVisits > 0) totalSpend / totalVisits else 0.0

                // Top products
                val productFrequency = customerSales
                    .groupBy { it.productName }
                    .mapValues { it.value.size }
                    .entries
                    .sortedByDescending { it.value }
                    .take(3)
                    .map { mapOf("name" to it.key, "count" to it.value) }
                val topProductsJson = gson.toJson(productFrequency)

                // Get credit info
                val debts = debtDao.getActiveByCustomer(customerKey).first()
                val creditOutstanding = debts.sumOf { it.outstandingBalance }

                // Determine segment
                val segment = determineSegment(
                    avgVisitsPerMonth = avgVisitsPerMonth,
                    daysSinceLastVisit = daysSince,
                    totalVisits = totalVisits,
                    firstVisit = firstVisit
                )

                // Calculate revenue rank and percentage
                val totalRevenue = profileDao.getTotalRevenue(workerId) ?: 0.0
                val revenuePct = if (totalRevenue > 0) (totalSpend / totalRevenue * 100) else 0.0

                val profile = CustomerProfileEntity(
                    id = existing?.id ?: 0,
                    workerId = workerId,
                    customerKey = customerKey,
                    customerName = customerKey, // Use key as display name
                    totalVisits = totalVisits,
                    visitsThisMonth = thisMonthVisits,
                    avgVisitsPerMonth = avgVisitsPerMonth,
                    totalSpend = totalSpend,
                    spendThisMonth = thisMonthSpend,
                    avgSpendPerVisit = avgSpendPerVisit,
                    firstVisit = firstVisit,
                    lastVisit = lastVisit,
                    daysSinceLastVisit = daysSince,
                    segment = segment,
                    segmentSince = existing?.segmentSince ?: dateFormat.format(now),
                    creditOutstanding = creditOutstanding,
                    creditLimit = existing?.creditLimit ?: 0.0,
                    creditReliability = existing?.creditReliability ?: 1.0,
                    topProductsJson = topProductsJson,
                    revenuePct = revenuePct,
                    revenueRank = 0,
                    updatedAt = now
                )

                profileDao.insertOrUpdate(profile)

                // Insert visit records for new transactions
                val existingVisitDates = visitDao.getByCustomerOnce(workerId, customerKey)
                    .map { it.visitDate to it.txnId }
                    .toSet()

                for (sale in customerSales) {
                    val visitDate = dateFormat.format(sale.timestamp)
                    val visitKey = visitDate to sale.id
                    if (visitKey !in existingVisitDates) {
                        visitDao.insert(
                            CustomerVisitEntity(
                                workerId = workerId,
                                customerKey = customerKey,
                                profileId = profile.id,
                                visitDate = visitDate,
                                txnId = sale.id,
                                amount = sale.totalPrice,
                                productsJson = gson.toJson(listOf(mapOf("name" to sale.productName, "qty" to sale.quantity, "price" to sale.totalPrice))),
                                paymentMethod = sale.paymentMethod,
                                createdAt = sale.timestamp
                            )
                        )
                    }
                }
            }

            // Update revenue ranks
            val allProfiles = profileDao.getTopCustomersOnce(workerId, Int.MAX_VALUE)
            val totalRev = allProfiles.sumOf { it.totalSpend }
            allProfiles.forEachIndexed { index, profile ->
                val pct = if (totalRev > 0) (profile.totalSpend / totalRev * 100) else 0.0
                profileDao.update(profile.copy(
                    revenueRank = index + 1,
                    revenuePct = pct
                ))
            }

            Timber.d("CustomerInsights: Synced ${salesByCustomer.size} customer profiles for worker $workerId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync customer profiles from transactions")
        }
    }

    /**
     * Determine customer segment based on visit patterns.
     *
     * VIP: top 10% by visits, 15+ visits/month, KSh 500+ avg spend
     * Regular: 8-14 visits/month
     * Occasional: 3-7 visits/month
     * Lapsed: was regular/occasional, 14+ days since last visit
     * New: first visit within 30 days
     */
    private fun determineSegment(
        avgVisitsPerMonth: Double,
        daysSinceLastVisit: Int,
        totalVisits: Int,
        firstVisit: String?
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = System.currentTimeMillis()

        // Check if new (first visit within 30 days)
        val firstVisitDate = firstVisit?.let { dateFormat.parse(it)?.time }
        if (firstVisitDate != null) {
            val daysSinceFirst = TimeUnit.MILLISECONDS.toDays(now - firstVisitDate).toInt()
            if (daysSinceFirst <= 30 && totalVisits <= 5) {
                return "new"
            }
        }

        // Check if lapsed (was active, now 14+ days since last visit)
        if (daysSinceLastVisit >= 14 && avgVisitsPerMonth >= 3) {
            return "lapsed"
        }

        // Segment by visit frequency
        return when {
            avgVisitsPerMonth >= 15 -> "vip"
            avgVisitsPerMonth >= 8 -> "regular"
            avgVisitsPerMonth >= 3 -> "occasional"
            else -> "new"
        }
    }

    // ──────────────────────────────────────────────
    // VOICE INPUT PARSING
    // ──────────────────────────────────────────────

    /**
     * Parse Swahili/English voice input into an action.
     *
     * Patterns:
     *  - "Wateja wangu wakoje?" → loyalty
     *  - "Nani ananipenda?" → loyalty
     *  - "Wateja gani wamenitoroka?" → churn_alert
     *  - "Mteja John amekuja lini?" → profile (customer_key=John)
     *  - "Wateja bora" → top_customers
     *  - "VIP wangu" → segment
     */
    private suspend fun parseVoiceInput(voiceInput: String, workerId: String): ToolResult {
        val input = voiceInput.trim().lowercase()
        Timber.d("Parsing voice customer input: '$input'")

        // Churn patterns
        if (input.contains("hatarini") || input.contains("churn") || input.contains("wanitoroka") ||
            input.contains("wamepotea") || input.contains("hawajaja") || input.contains("at_risk")) {
            return getChurnAlerts(workerId, mapOf("days_threshold" to "14"))
        }

        // Segment patterns
        if (input.contains("segmenti") || input.contains("vip") || input.contains("regular") ||
            input.contains("loyalty") || input.contains("wanaonipenda")) {
            return getSegmentBreakdown(workerId)
        }

        // Top customers patterns
        if (input.contains("bora") || input.contains("top") || input.contains("wanaoniletea pesa")) {
            return getTopCustomers(workerId, mapOf("limit" to "10"))
        }

        // Profile patterns — look for customer name
        val profilePatterns = listOf(
            Regex("""(?:mteja|customer|wasifu\s+wa)\s+([A-Za-zÀ-ÿ\s]+)""", RegexOption.IGNORE_CASE),
            Regex("""([A-Za-zÀ-ÿ]+)\s+(?:amekuja|anunua|ananipenda)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in profilePatterns) {
            pattern.find(voiceInput)?.let { match ->
                val customerName = match.groupValues[1].trim()
                if (customerName.isNotBlank() && customerName.length > 1) {
                    return getCustomerProfile(workerId, mapOf("customer_key" to customerName))
                }
            }
        }

        // Default: loyalty overview
        return getLoyaltyOverview(workerId)
    }

    // ──────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────

    private fun segmentToEmoji(segment: String): String = when (segment) {
        "vip" -> "🌟"
        "regular" -> "🟢"
        "occasional" -> "🟡"
        "lapsed" -> "🔴"
        "new" -> "🆕"
        else -> "⚪"
    }

    private fun segmentToSwahili(segment: String): String = when (segment) {
        "vip" -> "VIP (Mteja wa Juu)"
        "regular" -> "Mteja wa Kawaida"
        "occasional" -> "Mteja wa Mara kwa Mara"
        "lapsed" -> "Mteja Aliyepotea"
        "new" -> "Mteja Mpya"
        else -> segment
    }
}
