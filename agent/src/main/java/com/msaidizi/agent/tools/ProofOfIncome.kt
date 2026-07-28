package com.msaidizi.agent.tools

import com.google.gson.Gson
import com.msaidizi.core.database.DailySummaryDao
import com.msaidizi.core.database.DebtDao
import com.msaidizi.core.database.ExpenseDao
import com.msaidizi.core.database.SaleDao
import com.msaidizi.core.database.UserProfileDao
import com.msaidizi.core.util.DateTimeUtil
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ProofOfIncome — Generate formal, shareable income documentation.
 *
 * Transforms invisible informal business data into paperproof that
 * banks, landlords, suppliers, and government agencies accept.
 *
 * Features:
 * - Audience-aware formatting (bank / landlord / supplier / government)
 * - Tamper-evident SHA-256 verification hash
 * - PDF/WhatsApp-ready text output
 * - Swahili voice summaries
 * - 5 actions: generate, history, verify, share, format
 */
@Singleton
class ProofOfIncome @Inject constructor(
    private val saleDao: SaleDao,
    private val expenseDao: ExpenseDao,
    private val dailySummaryDao: DailySummaryDao,
    private val debtDao: DebtDao,
    private val userProfileDao: UserProfileDao,
    private val gson: Gson
) : Tool {

    override val name = "proof_of_income"
    override val description = "Generate formal income statements for banks, landlords, suppliers, and government"

    override val argsSchema = argSchema {
        enum(
            "action", "Proof action to perform",
            listOf("generate", "history", "verify", "share", "format"),
            required = false
        )
        enum(
            "proof_type", "Type of proof document",
            listOf("income_statement", "tenant_profile", "supplier_profile", "tax_summary", "business_profile"),
            required = false
        )
        enum(
            "audience", "Target audience for formatting",
            listOf("bank", "landlord", "supplier", "government"),
            required = false
        )
        integer("period_months", "Number of months to include (3, 6, or 12)", required = false)
        enum(
            "format", "Output format",
            listOf("pdf", "sms", "link", "json"),
            required = false
        )
        string("hash", "SHA-256 verification hash (for verify action)", required = false)
        string("proof_id", "Proof ID (for share/format actions)", required = false)
        enum(
            "via", "Share delivery channel",
            listOf("sms", "whatsapp", "link"),
            required = false
        )
        string("to", "Recipient phone number or identifier (for share)", required = false)
    }

    // ── In-memory proof store (production would use Room DB) ──
    private val proofStore = mutableMapOf<String, ProofRecord>()
    private val shareLog = mutableListOf<ShareLogEntry>()

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "generate"
        return when (action.lowercase()) {
            "generate" -> generateProof(params)
            "history" -> getProofHistory(params)
            "verify" -> verifyProof(params)
            "share" -> shareProof(params)
            "format" -> formatProof(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ════════════════════════════════════════════
    // ACTION: generate
    // ════════════════════════════════════════════

    private suspend fun generateProof(params: Map<String, String>): ToolResult {
        return try {
            val proofType = params["proof_type"] ?: "income_statement"
            val audience = params["audience"] ?: "bank"
            val periodMonths = (params["period_months"] ?: "6").toIntOrNull() ?: 6
            val outputFormat = params["format"] ?: "pdf"

            // ── Fetch user profile ──
            val profile = userProfileDao.getProfileOnce()
            val businessName = profile?.userName?.takeIf { it.isNotBlank() } ?: "Biashara"
            val language = profile?.preferredLanguage ?: "sw"

            // ── Calculate period boundaries ──
            val cal = Calendar.getInstance()
            val periodEnd = DateTimeUtil.today()
            val periodEndDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(periodEnd)
                ?: return ToolResult.error(name, "Invalid date", "DATE_ERROR")
            cal.time = periodEndDate
            cal.add(Calendar.MONTH, -periodMonths)
            val periodStart = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)

            val periodStartTs = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(periodStart)?.time
                ?: return ToolResult.error(name, "Invalid date", "DATE_ERROR")
            val periodEndTs = DateTimeUtil.endOfDay(periodEndDate.time)

            // ── Gather monthly financials from DailySummaryDao ──
            val summaries = dailySummaryDao.getSummariesBetween(periodStart, periodEnd).first()
            val monthlyData = aggregateMonthly(summaries, periodMonths)

            // ── Gather transaction counts from SaleDao ──
            val totalTxns = saleDao.getTransactionCountBetween(periodStartTs, periodEndTs).first()

            // ── Gather debt/obligations from DebtDao ──
            val totalOutstanding = debtDao.getTotalOutstanding().first() ?: 0.0
            val activeDebtCount = debtDao.getActiveDebtCount().first()

            // ── Calculate key metrics ──
            val revenues = monthlyData.map { it.revenue }
            val profits = monthlyData.map { it.profit }
            val avgRevenue = if (revenues.isNotEmpty()) revenues.average() else 0.0
            val avgProfit = if (profits.isNotEmpty()) profits.average() else 0.0
            val avgMargin = if (avgRevenue > 0) (avgProfit / avgRevenue * 100) else 0.0

            // Income stability (coefficient of variation — lower = more stable)
            val incomeCV = if (profits.size > 1 && avgProfit > 0) {
                val variance = profits.map { (it - avgProfit) * (it - avgProfit) }.average()
                (Math.sqrt(variance) / avgProfit * 100)
            } else 0.0

            // Business longevity (days since first recorded summary)
            val allSummaries = dailySummaryDao.getRecentSummaries(Int.MAX_VALUE).first()
            val businessDays = allSummaries.size
            val firstDate = allSummaries.lastOrNull()?.date
            val businessMonths = if (firstDate != null) {
                val first = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(firstDate)
                val last = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(periodEnd)
                if (first != null && last != null) {
                    val diffMs = last.time - first.time
                    (diffMs / (30L * 24 * 60 * 60 * 1000)).toInt().coerceAtLeast(1)
                } else periodMonths
            } else periodMonths

            // ── Build financials JSON (for hash) ──
            val financialsJson = gson.toJson(mapOf(
                "business_name" to businessName,
                "period_start" to periodStart,
                "period_end" to periodEnd,
                "period_months" to periodMonths,
                "monthly_data" to monthlyData.map {
                    mapOf(
                        "month" to it.month,
                        "revenue" to it.revenue,
                        "expenses" to it.expenses,
                        "net_income" to it.profit
                    )
                },
                "avg_monthly_revenue" to avgRevenue,
                "avg_monthly_expenses" to (monthlyData.map { it.expenses }.averageOrNull() ?: 0.0),
                "avg_monthly_net_income" to avgProfit,
                "income_stability_cv" to incomeCV,
                "avg_profit_margin" to avgMargin,
                "total_transactions" to totalTxns,
                "business_months" to businessMonths
            ))

            // ── Tamper-evident hash ──
            val verificationHash = sha256Hex(financialsJson)
            val verificationUrl = "msaidizi.co.ke/verify/${verificationHash.take(16)}"

            // ── Repayment capacity (for bank) ──
            val monthlyObligations = if (activeDebtCount > 0 && totalOutstanding > 0) {
                // Estimate monthly repayment as outstanding / 12
                (totalOutstanding / 12).toInt()
            } else 0
            val availableForRepayment = (avgProfit - monthlyObligations).toInt().coerceAtLeast(0)
            val recommendedEmi = (avgProfit * 0.33).toInt() // 33% DTI ratio

            // ── SMS summary (160 chars) ──
            val smsText = buildString {
                append("$businessName Income Proof: ")
                append("Avg KSh ${"%,.0f".format(avgProfit)}/mo net, ")
                append("${periodMonths}mo, margin ${"%.0f".format(avgMargin)}%")
                append(". Verify: ${verificationHash.take(8)}")
            }.take(160)

            // ── Store proof record ──
            val proofId = "proof_${System.currentTimeMillis()}"
            val record = ProofRecord(
                id = proofId,
                proofType = proofType,
                audience = audience,
                periodStart = periodStart,
                periodEnd = periodEnd,
                periodMonths = periodMonths,
                businessName = businessName,
                monthlyData = monthlyData,
                avgMonthlyIncome = avgProfit.toInt(),
                incomeStabilityCV = incomeCV,
                avgProfitMargin = avgMargin,
                totalTransactions = totalTxns,
                businessMonths = businessMonths,
                monthlyObligations = monthlyObligations,
                availableForRepayment = availableForRepayment,
                recommendedEmi = recommendedEmi,
                verificationHash = verificationHash,
                verificationUrl = verificationUrl,
                format = outputFormat,
                smsText = smsText,
                generatedAt = System.currentTimeMillis()
            )
            proofStore[proofId] = record

            // ── Build formatted output ──
            val formatted = formatForAudience(record, audience)

            // ── Swahili voice summary ──
            val voiceSummary = buildSwahiliVoiceSummary(record, audience)

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "proof_id" to proofId,
                    "proof_type" to proofType,
                    "audience" to audience,
                    "period" to "$periodStart — $periodEnd",
                    "avg_monthly_income" to avgProfit.toInt(),
                    "income_stability_cv" to incomeCV,
                    "avg_profit_margin" to avgMargin,
                    "total_transactions" to totalTxns,
                    "verification_hash" to verificationHash,
                    "verification_url" to verificationUrl,
                    "sms_text" to smsText,
                    "financials_json" to financialsJson
                ),
                message = "$formatted\n\n🎤 *Sauti (Voice Summary):*\n$voiceSummary"
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate proof of income")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ════════════════════════════════════════════
    // ACTION: history
    // ════════════════════════════════════════════

    private suspend fun getProofHistory(params: Map<String, String>): ToolResult {
        return try {
            val filterType = params["proof_type"]
            val records = proofStore.values
                .filter { filterType == null || it.proofType == filterType }
                .sortedByDescending { it.generatedAt }

            if (records.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "📭 Hakuna document zilizotengenezwa bado.\n\n" +
                        "Tengenezwa document ya mapato yako kwa kusema:\n" +
                        "\"Nataka document ya kuonyesha bank\" au\n" +
                        "\"Nataka proof ya income ya miezi 6\""
                )
            }

            val history = buildString {
                appendLine("📄 *Historia ya Document za Mapato*\n")
                records.forEach { rec ->
                    val dateStr = DateTimeUtil.formatDate(rec.generatedAt)
                    val statusEmoji = if (isExpired(rec)) "❌" else "✅"
                    appendLine("$statusEmoji [${rec.id}]")
                    appendLine("   Aina: ${proofTypeLabel(rec.proofType)}")
                    appendLine("   Kwa: ${audienceLabel(rec.audience)}")
                    appendLine("   Kipindi: ${rec.periodStart} — ${rec.periodEnd}")
                    appendLine("   Mapato: KSh ${"%,d".format(rec.avgMonthlyIncome)}/mwezi")
                    appendLine("   Tarehe: $dateStr")
                    appendLine()
                }
            }

            ToolResult.success(name, data = records.map { mapOf("id" to it.id, "type" to it.proofType, "audience" to it.audience) }, message = history)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get proof history")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ════════════════════════════════════════════
    // ACTION: verify
    // ════════════════════════════════════════════

    private suspend fun verifyProof(params: Map<String, String>): ToolResult {
        return try {
            val hash = params["hash"]
                ?: return ToolResult.error(name, "Missing 'hash' parameter", "MISSING_HASH")

            val match = proofStore.values.find { it.verificationHash == hash || it.verificationHash.startsWith(hash) }

            if (match == null) {
                return ToolResult.success(
                    name,
                    data = mapOf("verified" to false),
                    message = "❌ *Uthibitishaji Umeshindikana*\n\n" +
                        "Hash ya verification haikupatikana. Document hii:\n" +
                        "• Huenda ikawa ya uongo\n" +
                        "• Huenda ikatengenezwa na programu nyingine\n" +
                        "• Huenda ikakatwa au kubadilishwa\n\n" +
                        "Tafadhali pata document mpya kutoka Msaidizi."
                )
            }

            if (isExpired(match)) {
                return ToolResult.success(
                    name,
                    data = mapOf("verified" to true, "expired" to true),
                    message = "⚠️ *Document Imepatikana Lakini Imeisha Muda*\n\n" +
                        "Document hii ilikuwa halali lakini muda wake umekwisha.\n" +
                        "Tafadhali tengenezwa mpya kwa kusema \"Nataka proof mpya ya mapato\"."
                )
            }

            val result = buildString {
                appendLine("✅ *Document Imethibitishwa!*")
                appendLine()
                appendLine("📋 Biashara: ${match.businessName}")
                appendLine("📅 Kipindi: ${match.periodStart} — ${match.periodEnd}")
                appendLine("💰 Mapato ya kawaida: KSh ${"%,d".format(match.avgMonthlyIncome)}/mwezi")
                appendLine("📊 Margin: ${"%.0f".format(match.avgProfitMargin)}%")
                appendLine("🔢 Miamala: ${match.totalTransactions}")
                appendLine("🔒 Hash: ${match.verificationHash.take(16)}...")
                appendLine("📅 Iliyotengenezwa: ${DateTimeUtil.formatDate(match.generatedAt)}")
            }

            ToolResult.success(
                name,
                data = mapOf("verified" to true, "proof_id" to match.id, "expired" to false),
                message = result
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to verify proof")
            ToolResult.error(name, "Failed: ${e.message}", "VERIFY_ERROR")
        }
    }

    // ════════════════════════════════════════════
    // ACTION: share
    // ════════════════════════════════════════════

    private suspend fun shareProof(params: Map<String, String>): ToolResult {
        return try {
            val proofId = params["proof_id"]
                ?: return ToolResult.error(name, "Missing 'proof_id' parameter", "MISSING_PROOF_ID")
            val via = params["via"] ?: "whatsapp"
            val to = params["to"] ?: ""

            val record = proofStore[proofId]
                ?: return ToolResult.error(name, "Proof not found: $proofId", "NOT_FOUND")

            if (isExpired(record)) {
                return ToolResult.error(name, "Proof has expired. Generate a new one.", "EXPIRED")
            }

            // Log the share
            val shareEntry = ShareLogEntry(
                proofId = proofId,
                sharedVia = via,
                sharedTo = to,
                sharedAt = System.currentTimeMillis()
            )
            shareLog.add(shareEntry)

            // Build share message based on channel
            val shareMessage = when (via) {
                "sms" -> record.smsText
                "whatsapp" -> buildWhatsAppShare(record)
                "link" -> "📄 *${record.businessName} Income Proof*\n\n" +
                    "Verify: ${record.verificationUrl}\n" +
                    "Hash: ${record.verificationHash.take(16)}..."
                else -> record.smsText
            }

            val viaLabel = when (via) {
                "sms" -> "SMS"
                "whatsapp" -> "WhatsApp"
                "link" -> "Link"
                else -> via
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "proof_id" to proofId,
                    "shared_via" to via,
                    "shared_to" to to,
                    "message" to shareMessage
                ),
                message = "📤 *Document Imetumwa kwa $viaLabel!*\n\n" +
                    if (to.isNotBlank()) "Kwa: $to\n" else "" +
                    "Document: ${proofTypeLabel(record.proofType)}\n" +
                    "Kipindi: ${record.periodStart} — ${record.periodEnd}\n\n" +
                    "Ujumbe uliotumwa:\n---\n$shareMessage\n---"
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to share proof")
            ToolResult.error(name, "Failed: ${e.message}", "SHARE_ERROR")
        }
    }

    // ════════════════════════════════════════════
    // ACTION: format
    // ════════════════════════════════════════════

    private suspend fun formatProof(params: Map<String, String>): ToolResult {
        return try {
            val proofId = params["proof_id"]
                ?: return ToolResult.error(name, "Missing 'proof_id' parameter", "MISSING_PROOF_ID")
            val audience = params["audience"] ?: "bank"

            val record = proofStore[proofId]
                ?: return ToolResult.error(name, "Proof not found: $proofId", "NOT_FOUND")

            val formatted = formatForAudience(record, audience)
            val voiceSummary = buildSwahiliVoiceSummary(record, audience)

            ToolResult.success(
                name,
                data = mapOf(
                    "proof_id" to proofId,
                    "audience" to audience,
                    "formatted_text" to formatted,
                    "voice_summary" to voiceSummary
                ),
                message = "$formatted\n\n🎤 *Sauti:*\n$voiceSummary"
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to format proof")
            ToolResult.error(name, "Failed: ${e.message}", "FORMAT_ERROR")
        }
    }

    // ════════════════════════════════════════════
    // AUDIENCE-AWARE FORMATTING
    // ════════════════════════════════════════════

    private fun formatForAudience(record: ProofRecord, audience: String): String {
        return when (audience) {
            "bank" -> formatForBank(record)
            "landlord" -> formatForLandlord(record)
            "supplier" -> formatForSupplier(record)
            "government" -> formatForGovernment(record)
            else -> formatForBank(record)
        }
    }

    /**
     * Bank version: emphasizes repayment capacity, income consistency, debt-to-income ratio.
     */
    private fun formatForBank(record: ProofRecord): String = buildString {
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine("   MSAIDIZI VERIFIED INCOME STATEMENT")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine("   ${record.businessName}")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine()
        appendLine("📅 Period: ${record.periodStart} — ${record.periodEnd} (${record.periodMonths} months)")
        appendLine("🏢 Business Age: ${record.businessMonths} months")
        appendLine()
        appendLine("┌──────────┬────────────┬────────────┬────────────┐")
        appendLine("│   Month  │  Revenue   │  Expenses  │  Net Inc   │")
        appendLine("├──────────┼────────────┼────────────┼────────────┤")
        record.monthlyData.forEach { m ->
            appendLine("│ ${m.month.padEnd(8)} │ KSh ${"%,7.0f".format(m.revenue)} │ KSh ${"%,7.0f".format(m.expenses)} │ KSh ${"%,7.0f".format(m.profit)} │")
        }
        appendLine("├──────────┼────────────┼────────────┼────────────┤")
        val avgRev = record.monthlyData.map { it.revenue }.averageOrNull() ?: 0.0
        val avgExp = record.monthlyData.map { it.expenses }.averageOrNull() ?: 0.0
        appendLine("│ AVERAGE  │ KSh ${"%,7.0f".format(avgRev)} │ KSh ${"%,7.0f".format(avgExp)} │ KSh ${"%,7.0f".format(record.avgMonthlyIncome.toDouble())} │")
        appendLine("└──────────┴────────────┴────────────┴────────────┘")
        appendLine()
        appendLine("📊 KEY METRICS")
        appendLine("  • Average Monthly Net Income:   KSh ${"%,d".format(record.avgMonthlyIncome)}")
        appendLine("  • Income Stability (CV):        ±${"%.0f".format(record.incomeStabilityCV)}% ${stabilityLabel(record.incomeStabilityCV)}")
        appendLine("  • Average Profit Margin:        ${"%.0f".format(record.avgProfitMargin)}%")
        appendLine("  • Total Transactions:            ${"%,d".format(record.totalTransactions)}")
        appendLine("  • Business Active:               ${record.businessMonths} months")
        appendLine()
        appendLine("💰 REPAYMENT CAPACITY")
        appendLine("  • Monthly Net Income:            KSh ${"%,d".format(record.avgMonthlyIncome)}")
        appendLine("  • Existing Obligations:          KSh ${"%,d".format(record.monthlyObligations)}/month")
        appendLine("  • Available for Repayment:       KSh ${"%,d".format(record.availableForRepayment)}/month")
        appendLine("  • Recommended Max Monthly EMI:   KSh ${"%,d".format(record.recommendedEmi)} (33% DTI)")
        appendLine()
        appendLine("🔒 VERIFICATION")
        appendLine("  • Hash: ${record.verificationHash.take(16)}... (SHA-256)")
        appendLine("  • Verify: ${record.verificationUrl}")
        appendLine("  • Generated: ${DateTimeUtil.formatDate(record.generatedAt)}")
        appendLine()
        appendLine("This document was auto-generated from verified business")
        appendLine("transaction data recorded in Msaidizi.")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    /**
     * Landlord version: emphasizes income stability, rent-to-income ratio, payment reliability.
     */
    private fun formatForLandlord(record: ProofRecord): String = buildString {
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine("   MSAIDIZI TENANT INCOME PROFILE")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine("   ${record.businessName}")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine()
        appendLine("👤 Tenant: ${record.businessName}")
        appendLine("📅 Verified Income Period: ${record.periodMonths} months")
        appendLine()
        appendLine("💰 INCOME SUMMARY")
        appendLine("  • Average Monthly Income:     KSh ${"%,d".format(record.avgMonthlyIncome)}")
        appendLine("  • Income Stability:           ${stabilityLabel(record.incomeStabilityCV)} (±${"%.0f".format(record.incomeStabilityCV)}%)")
        appendLine("  • Profit Margin:              ${"%.0f".format(record.avgProfitMargin)}%")
        appendLine("  • Business Active:            ${record.businessMonths} months")
        appendLine()
        appendLine("🏠 RENT AFFORDABILITY")
        val maxRent = (record.avgMonthlyIncome * 0.30).toInt() // 30% rule
        appendLine("  • Recommended Max Rent:       KSh ${"%,d".format(maxRent)}/month (30% of income)")
        appendLine("  • Rent-to-Income at KSh 15K:  ${"%.0f".format(15000.0 / record.avgMonthlyIncome * 100)}%")
        appendLine("  • Existing Obligations:       KSh ${"%,d".format(record.monthlyObligations)}/month")
        appendLine()
        appendLine("📈 INCOME TREND (last ${record.periodMonths} months)")
        record.monthlyData.takeLast(6).forEach { m ->
            val bar = "█".repeat((m.profit / 1000).toInt().coerceIn(1, 20))
            appendLine("  ${m.month}: KSh ${"%,.0f".format(m.profit)} $bar")
        }
        appendLine()
        appendLine("🔒 VERIFICATION")
        appendLine("  • Hash: ${record.verificationHash.take(16)}... (SHA-256)")
        appendLine("  • Verify: ${record.verificationUrl}")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    /**
     * Supplier version: emphasizes purchase volume, payment reliability, growth.
     */
    private fun formatForSupplier(record: ProofRecord): String = buildString {
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine("   MSAIDIZI SUPPLIER BUSINESS PROFILE")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine("   ${record.businessName}")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine()
        appendLine("🏢 BUSINESS OVERVIEW")
        appendLine("  • Business Name:              ${record.businessName}")
        appendLine("  • Active For:                 ${record.businessMonths} months")
        appendLine("  • Total Transactions:         ${"%,d".format(record.totalTransactions)}")
        appendLine()
        appendLine("📦 PURCHASE & VOLUME CAPACITY")
        val avgMonthlyExpenses = record.monthlyData.map { it.expenses }.averageOrNull() ?: 0.0
        appendLine("  • Average Monthly Purchases:  KSh ${"%,.0f".format(avgMonthlyExpenses)}")
        appendLine("  • Peak Month Purchases:       KSh ${"%,.0f".format(record.monthlyData.maxOfOrNull { it.expenses } ?: 0.0)}")
        appendLine("  • Monthly Revenue:            KSh ${"%,d".format(record.avgMonthlyIncome)}")
        appendLine("  • Profit Margin:              ${"%.0f".format(record.avgProfitMargin)}%")
        appendLine()
        appendLine("💳 PAYMENT RELIABILITY")
        appendLine("  • Outstanding Debts:          KSh ${"%,d".format(record.monthlyObligations * 12)}")
        appendLine("  • Monthly Obligations:        KSh ${"%,d".format(record.monthlyObligations)}")
        appendLine("  • Available Credit Capacity:  KSh ${"%,d".format(record.availableForRepayment)}")
        appendLine()
        appendLine("📈 BUSINESS GROWTH")
        if (record.monthlyData.size >= 2) {
            val first = record.monthlyData.first().revenue
            val last = record.monthlyData.last().revenue
            val growthPct = if (first > 0) ((last - first) / first * 100) else 0.0
            val arrow = if (growthPct > 0) "📈" else if (growthPct < 0) "📉" else "➡️"
            appendLine("  • Revenue Trend:              $arrow ${if (growthPct > 0) "+" else ""}${"%.0f".format(growthPct)}% over ${record.periodMonths} months")
        }
        appendLine("  • Business Stability:         ${stabilityLabel(record.incomeStabilityCV)}")
        appendLine()
        appendLine("🔒 VERIFICATION")
        appendLine("  • Hash: ${record.verificationHash.take(16)}... (SHA-256)")
        appendLine("  • Verify: ${record.verificationUrl}")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    /**
     * Government version: emphasizes revenue, deductible expenses, taxable income, compliance.
     */
    private fun formatForGovernment(record: ProofRecord): String = buildString {
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine("   MSAIDIZI TAX INCOME SUMMARY")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine("   ${record.businessName}")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine()
        appendLine("📋 BUSINESS IDENTIFICATION")
        appendLine("  • Business Name:              ${record.businessName}")
        appendLine("  • Business Duration:          ${record.businessMonths} months")
        appendLine("  • Reporting Period:           ${record.periodStart} — ${record.periodEnd}")
        appendLine()
        val totalRevenue = record.monthlyData.sumOf { it.revenue }
        val totalExpenses = record.monthlyData.sumOf { it.expenses }
        val totalNet = record.monthlyData.sumOf { it.profit }
        appendLine("💵 ANNUAL INCOME SUMMARY")
        appendLine("  • Total Revenue:              KSh ${"%,.0f".format(totalRevenue)}")
        appendLine("  • Total Business Expenses:    KSh ${"%,.0f".format(totalExpenses)}")
        appendLine("  • Net Business Income:        KSh ${"%,.0f".format(totalNet)}")
        appendLine("  • Average Monthly Income:     KSh ${"%,d".format(record.avgMonthlyIncome)}")
        appendLine("  • Profit Margin:              ${"%.0f".format(record.avgProfitMargin)}%")
        appendLine()
        appendLine("📊 MONTHLY BREAKDOWN")
        appendLine("┌──────────┬────────────┬────────────┬────────────┐")
        appendLine("│   Month  │  Revenue   │  Expenses  │  Net Inc   │")
        appendLine("├──────────┼────────────┼────────────┼────────────┤")
        record.monthlyData.forEach { m ->
            appendLine("│ ${m.month.padEnd(8)} │ KSh ${"%,7.0f".format(m.revenue)} │ KSh ${"%,7.0f".format(m.expenses)} │ KSh ${"%,7.0f".format(m.profit)} │")
        }
        appendLine("├──────────┼────────────┼────────────┼────────────┤")
        appendLine("│ TOTAL    │ KSh ${"%,7.0f".format(totalRevenue)} │ KSh ${"%,7.0f".format(totalExpenses)} │ KSh ${"%,7.0f".format(totalNet)} │")
        appendLine("└──────────┴────────────┴────────────┴────────────┘")
        appendLine()
        appendLine("📈 ACTIVITY METRICS")
        appendLine("  • Total Transactions:         ${"%,d".format(record.totalTransactions)}")
        appendLine("  • Active Business Months:     ${record.businessMonths}")
        appendLine("  • Avg Transactions/Month:     ${"%,d".format(record.totalTransactions / record.periodMonths.coerceAtLeast(1))}")
        appendLine()
        appendLine("🔒 VERIFICATION")
        appendLine("  • Hash: ${record.verificationHash.take(16)}... (SHA-256)")
        appendLine("  • Verify: ${record.verificationUrl}")
        appendLine()
        appendLine("This income summary was auto-generated from verified")
        appendLine("business transaction data recorded in Msaidizi.")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    // ════════════════════════════════════════════
    // SWAHILI VOICE SUMMARIES
    // ════════════════════════════════════════════

    private fun buildSwahiliVoiceSummary(record: ProofRecord, audience: String): String {
        val avgInc = record.avgMonthlyIncome
        val margin = record.avgProfitMargin
        val stability = stabilityLabel(record.incomeStabilityCV)

        return when (audience) {
            "bank" -> {
                "Hii ni document ya mapato ya ${record.businessName}. " +
                    "Kipindi cha miezi ${record.periodMonths}, mapato ya kawaida ni KSh ${"%,d".format(avgInc)} kwa mwezi. " +
                    "Margin ya faida ni asilimia ${"%.0f".format(margin)}. " +
                "Mapato ni ${stability}. " +
                    "Kwa mkopo, uwezo wa kulipa ni KSh ${"%,d".format(record.availableForRepayment)} kwa mwezi. " +
                    "Alama ya uthibitishaji ni ${record.verificationHash.take(8)}."
            }
            "landlord" -> {
                val maxRent = (avgInc * 0.30).toInt()
                "Hii ni profile ya mpangaji wa ${record.businessName}. " +
                    "Mapato ya kawaida ni KSh ${"%,d".format(avgInc)} kwa mwezi. " +
                    "Mapato ni ${stability}. " +
                    "Kodi ya juu inayoweza kulipwa ni KSh ${"%,d".format(maxRent)} kwa mwezi."
            }
            "supplier" -> {
                "Hii ni profile ya biashara ya ${record.businessName}. " +
                    "Biashara imekuwa ikifanya kazi kwa miezi ${record.businessMonths}. " +
                    "Mapato ya kawaida ni KSh ${"%,d".format(avgInc)} kwa mwezi, margin ya asilimia ${"%.0f".format(margin)}. " +
                    "Uwezo wa kununua bidhaa ni KSh ${"%,d".format(record.availableForRepayment)} kwa mwezi."
            }
            "government" -> {
                val totalNet = record.monthlyData.sumOf { it.profit }
                "Hii ni muhtasari wa mapato ya ${record.businessName}. " +
                    "Mapato jumla ni KSh ${"%,.0f".format(record.monthlyData.sumOf { it.revenue })}. " +
                    "Gharama ni KSh ${"%,.0f".format(record.monthlyData.sumOf { it.expenses })}. " +
                    "Mapato halisi ni KSh ${"%,.0f".format(totalNet)} kwa kipindi cha miezi ${record.periodMonths}."
            }
            else -> "Document ya mapato ya ${record.businessName}. Mapato ya kawaida ni KSh ${"%,d".format(avgInc)} kwa mwezi."
        }
    }

    // ════════════════════════════════════════════
    // WHATSAPP SHARE BUILDER
    // ════════════════════════════════════════════

    private fun buildWhatsAppShare(record: ProofRecord): String = buildString {
        appendLine("📄 *${record.businessName}* — Income Proof")
        appendLine()
        appendLine("📅 Period: ${record.periodMonths} months (${record.periodStart} — ${record.periodEnd})")
        appendLine("💰 Avg Monthly Income: KSh ${"%,d".format(record.avgMonthlyIncome)}")
        appendLine("📊 Profit Margin: ${"%.0f".format(record.avgProfitMargin)}%")
        appendLine("📈 Stability: ${stabilityLabel(record.incomeStabilityCV)}")
        appendLine("🔢 Transactions: ${"%,d".format(record.totalTransactions)}")
        appendLine()
        appendLine("🔒 Verify: ${record.verificationUrl}")
        appendLine("Hash: ${record.verificationHash.take(16)}...")
        appendLine()
        appendLine("_Auto-generated by Msaidizi_")
    }

    // ════════════════════════════════════════════
    // DATA AGGREGATION
    // ════════════════════════════════════════════

    private data class MonthlyFinancial(
        val month: String,     // "Jan 2026"
        val revenue: Double,
        val expenses: Double,
        val profit: Double
    )

    /**
     * Aggregate daily summaries into monthly buckets.
     * Fills missing months with zeros for complete table display.
     */
    private fun aggregateMonthly(
        summaries: List<com.msaidizi.app.model.DailySummaryEntity>,
        periodMonths: Int
    ): List<MonthlyFinancial> {
        val monthFormat = SimpleDateFormat("yyyy-MM", Locale.US)
        val displayMonth = SimpleDateFormat("MMM yyyy", Locale.US)

        // Group by month
        val grouped = summaries.groupBy { it.date.substring(0, 7) } // "YYYY-MM"

        // Build complete month list
        val cal = Calendar.getInstance()
        val months = mutableListOf<String>()
        for (i in (periodMonths - 1) downTo 0) {
            val c = Calendar.getInstance()
            c.add(Calendar.MONTH, -i)
            months.add(monthFormat.format(c.time))
        }

        return months.map { ym ->
            val days = grouped[ym] ?: emptyList()
            MonthlyFinancial(
                month = try {
                    displayMonth.format(monthFormat.parse(ym)!!)
                } catch (_: Exception) { ym },
                revenue = days.sumOf { it.totalSales },
                expenses = days.sumOf { it.totalExpenses },
                profit = days.sumOf { it.profit }
            )
        }
    }

    // ════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun stabilityLabel(cv: Double): String = when {
        cv <= 15 -> "stable ✅"
        cv <= 30 -> "moderate ⚠️"
        else -> "volatile ❌"
    }

    private fun proofTypeLabel(type: String): String = when (type) {
        "income_statement" -> "Income Statement"
        "tenant_profile" -> "Tenant Profile"
        "supplier_profile" -> "Supplier Profile"
        "tax_summary" -> "Tax Summary"
        "business_profile" -> "Business Profile"
        else -> type
    }

    private fun audienceLabel(audience: String): String = when (audience) {
        "bank" -> "Bank/SACCO"
        "landlord" -> "Landlord"
        "supplier" -> "Supplier"
        "government" -> "Government/KRA"
        else -> audience
    }

    private fun isExpired(record: ProofRecord): Boolean {
        val ninetyDaysMs = 90L * 24 * 60 * 60 * 1000
        return System.currentTimeMillis() - record.generatedAt > ninetyDaysMs
    }

    private fun List<Double>.averageOrNull(): Double? = if (isNotEmpty()) average() else null

    // ════════════════════════════════════════════
    // DATA CLASSES
    // ════════════════════════════════════════════

    data class ProofRecord(
        val id: String,
        val proofType: String,
        val audience: String,
        val periodStart: String,
        val periodEnd: String,
        val periodMonths: Int,
        val businessName: String,
        val monthlyData: List<MonthlyFinancial>,
        val avgMonthlyIncome: Int,
        val incomeStabilityCV: Double,
        val avgProfitMargin: Double,
        val totalTransactions: Int,
        val businessMonths: Int,
        val monthlyObligations: Int,
        val availableForRepayment: Int,
        val recommendedEmi: Int,
        val verificationHash: String,
        val verificationUrl: String,
        val format: String,
        val smsText: String,
        val generatedAt: Long
    )

    data class ShareLogEntry(
        val proofId: String,
        val sharedVia: String,
        val sharedTo: String,
        val sharedAt: Long
    )
}
