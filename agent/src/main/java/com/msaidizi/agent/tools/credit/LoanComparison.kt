import com.msaidizi.agent.tools.financial.CFOEngine
package com.msaidizi.agent.tools.credit

import com.msaidizi.core.database.DailySummaryDao
import com.msaidizi.core.database.DebtDao
import com.msaidizi.core.database.ExpenseDao
import com.msaidizi.core.database.SaleDao
import com.msaidizi.agent.memory.MemoryManager
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import com.msaidizi.agent.tools.core.*

/**
 * LoanComparison — Side-by-side true cost comparison of loan products.
 *
 * Workers compare loans by asking friends or taking the first offer they see
 * on M-Pesa. This tool creates a structured comparison showing true cost
 * (including hidden fees), effective APR, repayment-to-income ratios,
 * and flags predatory loans.
 *
 * Features:
 *  1. compare      — Side-by-side comparison of 2-5 loan products
 *  2. true_cost    — Full cost breakdown including all hidden fees
 *  3. best_option  — Ranked recommendation based on worker's situation
 *  4. warning      — Flag predatory terms, excessive rates, dangerous traps
 *  5. history      — Past comparisons and what the worker chose
 *
 * Integrates with CreditReadiness (eligible products), AlamaScore (score),
 * DebtTracker (existing debt), CFOEngine (income pattern).
 * Voice-first, Swahili-native, actionable in 30 seconds.
 */
@Singleton
class LoanComparison @Inject constructor(
    private val alamaScore: AlamaScore,
    private val cfoEngine: CFOEngine,
    private val saleDao: SaleDao,
    private val expenseDao: ExpenseDao,
    private val dailySummaryDao: DailySummaryDao,
    private val debtDao: DebtDao,
    private val memoryManager: MemoryManager
) : Tool {

    override val name = "loan_comparison"
    override val description = "Compare loans side-by-side: true cost, hidden fees, effective APR, repayment-to-income ratios. Flags predatory loans. Voice: 'Nilinganishe mikopo' or 'M-Shwari au KCB — ipi ni bora?'"

    override val argsSchema = argSchema {
        enum(
            "action",
            "Loan comparison action to perform",
            listOf("compare", "true_cost", "best_option", "warning", "history"),
            required = false
        )
        number("amount", "Loan amount in KES to compare", required = false)
        enum(
            "purpose",
            "Loan purpose",
            listOf("stock", "emergency", "school_fees", "equipment", "bike_repair", "other"),
            required = false
        )
        enum(
            "urgency",
            "How soon the money is needed",
            listOf("now", "this_week", "this_month"),
            required = false
        )
        string(
            "products",
            "Comma-separated product IDs to compare (e.g. 'mshwari,kcb_mpesa,sacco_standard'). If empty, compares all eligible.",
            required = false
        )
        integer("term_days", "Preferred repayment term in days (for true_cost)", required = false)
        string("voice_input", "Raw Swahili voice text to parse", required = false)
    }

    // ──────────────────────────────────────────────
    // LENDER PRODUCT CATALOG (shared with CreditReadiness)
    // ──────────────────────────────────────────────

    private data class LenderProduct(
        val id: String,
        val lender: String,
        val product: String,
        val type: LenderType,
        val minAmount: Double,
        val maxAmount: Double,
        val monthlyRate: Double,
        val processingFeePct: Double = 0.0,
        val processingFeeFixed: Double = 0.0,
        val insuranceFeePct: Double = 0.0,
        val penaltyFeePct: Double = 0.0,
        val effectiveApr: Double,
        val maxTermDays: Int,
        val minAlamaScore: Int,
        val minTimeInBusinessMonths: Int = 0,
        val minMonthlyRevenue: Double = 0.0,
        val disbursement: String,
        val repayment: String,
        val requiresGuarantor: Boolean = false,
        val requiresCollateral: Boolean = false,
        val swahiliName: String,
        val description: String,
        val descriptionSw: String
    )

    private enum class LenderType { MOBILE_LOAN, SACCO, BANK_SME, MFI, HIRE_PURCHASE, OVERDRAFT }

    /**
     * Build the lender product catalog.
     * Rates and fees reflect real Kenyan market as of 2026.
     * True APR calculated including all hidden fees.
     */
    private fun buildLenderCatalog(): List<LenderProduct> = listOf(
        // ── Digital/Mobile Lenders ──
        LenderProduct(
            id = "mshwari", lender = "M-Shwari (Safaricom/NCBA)", product = "M-Shwari Loan",
            type = LenderType.MOBILE_LOAN,
            minAmount = 100.0, maxAmount = 50_000.0,
            monthlyRate = 0.075,
            effectiveApr = 0.90,
            maxTermDays = 30, minAlamaScore = 300,
            disbursement = "instant", repayment = "monthly",
            swahiliName = "M-Shwari",
            description = "Instant mobile loan via M-Pesa. No paperwork.",
            descriptionSw = "Mkopo wa simu kupitia M-Pesa. Hakuna karatasi."
        ),
        LenderProduct(
            id = "kcb_mpesa", lender = "KCB M-Pesa", product = "KCB M-Pesa Loan",
            type = LenderType.MOBILE_LOAN,
            minAmount = 100.0, maxAmount = 100_000.0,
            monthlyRate = 0.06,
            processingFeeFixed = 500.0,
            effectiveApr = 1.32,
            maxTermDays = 30, minAlamaScore = 350,
            disbursement = "instant", repayment = "monthly",
            swahiliName = "KCB M-Pesa",
            description = "KCB loan via M-Pesa. Shows 6%/month but KES 500 fee makes it expensive.",
            descriptionSw = "Mkopo wa KCB kupitia M-Pesa. Inaonyesha 6%/mwezi lakini ada ya KES 500 inafanya iwe ghali."
        ),
        LenderProduct(
            id = "fuliza", lender = "Safaricom/Fuliza", product = "Fuliza M-Pesa",
            type = LenderType.OVERDRAFT,
            minAmount = 1.0, maxAmount = 70_000.0,
            monthlyRate = 0.0,
            effectiveApr = 3.65,
            maxTermDays = 30, minAlamaScore = 300,
            disbursement = "instant", repayment = "auto_deduct",
            swahiliName = "Fuliza",
            description = "Overdraft on M-Pesa. Extremely expensive — ~1% daily.",
            descriptionSw = "Overdraft ya M-Pesa. Ghali sana — ~1% kwa siku."
        ),
        LenderProduct(
            id = "tala", lender = "Tala", product = "Tala Loan",
            type = LenderType.MOBILE_LOAN,
            minAmount = 500.0, maxAmount = 50_000.0,
            monthlyRate = 0.15,
            effectiveApr = 1.80,
            maxTermDays = 60, minAlamaScore = 350,
            disbursement = "instant", repayment = "monthly",
            swahiliName = "Tala",
            description = "App-based mobile loan. Quick but expensive.",
            descriptionSw = "Mkopo wa app. Haraka lakini ghali."
        ),
        LenderProduct(
            id = "branch", lender = "Branch", product = "Branch Loan",
            type = LenderType.MOBILE_LOAN,
            minAmount = 250.0, maxAmount = 100_000.0,
            monthlyRate = 0.14,
            effectiveApr = 1.68,
            maxTermDays = 60, minAlamaScore = 350,
            disbursement = "instant", repayment = "monthly",
            swahiliName = "Branch",
            description = "App-based loan. Competitive with Tala.",
            descriptionSw = "Mkopo wa app. Karibu na Tala."
        ),

        // ── SACCOs ──
        LenderProduct(
            id = "sacco_standard", lender = "SACCO (Typical)", product = "SACCO Development Loan",
            type = LenderType.SACCO,
            minAmount = 5_000.0, maxAmount = 500_000.0,
            monthlyRate = 0.0125,
            processingFeePct = 0.01,
            effectiveApr = 0.15,
            maxTermDays = 365, minAlamaScore = 550,
            minTimeInBusinessMonths = 6,
            disbursement = "1 week", repayment = "monthly",
            requiresGuarantor = true,
            swahiliName = "SACCO",
            description = "Best rates for workers. Requires 6-month membership and guarantor.",
            descriptionSw = "Riba nzuri kwa wafanyakazi. Inahitaji uanachama wa miezi 6 na mdhamini."
        ),
        LenderProduct(
            id = "sacco_emergency", lender = "SACCO (Typical)", product = "SACCO Emergency Loan",
            type = LenderType.SACCO,
            minAmount = 2_000.0, maxAmount = 50_000.0,
            monthlyRate = 0.0167,
            processingFeePct = 0.01,
            effectiveApr = 0.20,
            maxTermDays = 180, minAlamaScore = 500,
            minTimeInBusinessMonths = 3,
            disbursement = "3 days", repayment = "monthly",
            swahiliName = "SACCO - Dharura",
            description = "Emergency SACCO loan. Faster but slightly higher rate.",
            descriptionSw = "Mkopo wa dharura wa SACCO. Haraka zaidi lakini riba kidogo juu."
        ),

        // ── Banks ──
        LenderProduct(
            id = "bank_sme", lender = "Commercial Bank (Typical)", product = "SME Loan",
            type = LenderType.BANK_SME,
            minAmount = 50_000.0, maxAmount = 5_000_000.0,
            monthlyRate = 0.015,
            processingFeePct = 0.02, insuranceFeePct = 0.01,
            effectiveApr = 0.22,
            maxTermDays = 730, minAlamaScore = 700,
            minTimeInBusinessMonths = 12, minMonthlyRevenue = 100_000.0,
            disbursement = "2 weeks", repayment = "monthly",
            requiresCollateral = true,
            swahiliName = "Benki - Mkopo wa Biashara",
            description = "Bank SME loan. Best rates but high requirements.",
            descriptionSw = "Mkopo wa benki wa biashara. Riba nzuri lakini vigezo vingi."
        ),
        LenderProduct(
            id = "bank_personal", lender = "Commercial Bank (Typical)", product = "Personal Loan",
            type = LenderType.BANK_SME,
            minAmount = 10_000.0, maxAmount = 1_000_000.0,
            monthlyRate = 0.02,
            processingFeePct = 0.03, insuranceFeePct = 0.015,
            effectiveApr = 0.30,
            maxTermDays = 365, minAlamaScore = 650,
            minTimeInBusinessMonths = 6, minMonthlyRevenue = 30_000.0,
            disbursement = "1 week", repayment = "monthly",
            swahiliName = "Benki - Mkopo wa Kibinafsi",
            description = "Bank personal loan. Easier than SME loan.",
            descriptionSw = "Mkopo wa kibinafsi wa benki. Rahisi zaidi ya mkopo wa biashara."
        ),

        // ── MFIs ──
        LenderProduct(
            id = "mfi_juhudi", lender = "MFI (Typical)", product = "Biashara Loan",
            type = LenderType.MFI,
            minAmount = 5_000.0, maxAmount = 200_000.0,
            monthlyRate = 0.025,
            processingFeePct = 0.03,
            effectiveApr = 0.36,
            maxTermDays = 365, minAlamaScore = 450,
            minTimeInBusinessMonths = 3,
            disbursement = "3 days", repayment = "weekly",
            swahiliName = "MFI - Mkopo wa Biashara",
            description = "Microfinance loan. Good for growing businesses.",
            descriptionSw = "Mkopo wa taasisi ndogo ya fedha. Nzuri kwa biashara zinazokua."
        ),
        LenderProduct(
            id = "mfi_group", lender = "MFI (Typical)", product = "Group Loan",
            type = LenderType.MFI,
            minAmount = 3_000.0, maxAmount = 100_000.0,
            monthlyRate = 0.02,
            processingFeePct = 0.02,
            effectiveApr = 0.28,
            maxTermDays = 270, minAlamaScore = 400,
            disbursement = "1 week", repayment = "weekly",
            requiresGuarantor = true,
            swahiliName = "MFI - Mkopo wa Kikundi",
            description = "Group lending. Lower rates through peer guarantee.",
            descriptionSw = "Mkopo wa kikundi. Riba nafuu kupitia mdhamini wa kikundi."
        ),

        // ── Chama ──
        LenderProduct(
            id = "chama_loan", lender = "Chama", product = "Chama Loan",
            type = LenderType.SACCO,
            minAmount = 2_000.0, maxAmount = 50_000.0,
            monthlyRate = 0.05,
            effectiveApr = 0.60,
            maxTermDays = 90, minAlamaScore = 400,
            disbursement = "next meeting", repayment = "monthly",
            swahiliName = "Chama",
            description = "Loan from your chama (savings group). Flexible terms.",
            descriptionSw = "Mkopo kutoka chama yako. Masharti yenye kubadilika."
        ),

        // ── Hire Purchase ──
        LenderProduct(
            id = "watu_credit", lender = "Watu Credit", product = "Asset Finance",
            type = LenderType.HIRE_PURCHASE,
            minAmount = 20_000.0, maxAmount = 500_000.0,
            monthlyRate = 0.05,
            effectiveApr = 0.80,
            maxTermDays = 365, minAlamaScore = 450,
            disbursement = "24h", repayment = "daily",
            swahiliName = "Watu Credit",
            description = "Hire purchase for bikes, equipment. Daily payments sound small but cost is high.",
            descriptionSw = "Kununua kwa awamu kwa pikipiki, vifaa. Malipo ya siku yanaonekana madogo lakini gharama ni kubwa."
        ),
        LenderProduct(
            id = "mogo", lender = "Mogo", product = "Boda Boda Finance",
            type = LenderType.HIRE_PURCHASE,
            minAmount = 30_000.0, maxAmount = 300_000.0,
            monthlyRate = 0.04,
            effectiveApr = 0.65,
            maxTermDays = 540, minAlamaScore = 400,
            disbursement = "24h", repayment = "daily",
            swahiliName = "Mogo",
            description = "Motorcycle/boda boda financing. GPS tracker installed.",
            descriptionSw = "Ufadhili wa pikipiki/boda boda. GPS tracker imewekwa."
        )
    )

    // ──────────────────────────────────────────────
    // PEER OUTCOME DATA (anonymized, aggregated)
    // ──────────────────────────────────────────────

    /**
     * Simulated peer outcome data for Kenyan loan products.
     * In production this would come from the loan_peer_outcomes table.
     */
    private data class PeerOutcome(
        val productId: String,
        val totalBorrowers: Int,
        val onTimePct: Double,
        val earlyPct: Double,
        val latePct: Double,
        val defaultPct: Double,
        val refinancedPct: Double,
        val avgDaysToRepay: Int,
        val wouldBorrowAgainPct: Double,
        val avgRating: Double
    )

    private fun buildPeerOutcomes(): List<PeerOutcome> = listOf(
        PeerOutcome("mshwari", 12450, 0.71, 0.12, 0.10, 0.04, 0.03, 23, 0.65, 3.2),
        PeerOutcome("kcb_mpesa", 8320, 0.68, 0.10, 0.13, 0.05, 0.04, 25, 0.58, 2.9),
        PeerOutcome("fuliza", 31200, 0.52, 0.08, 0.22, 0.09, 0.09, 18, 0.35, 2.1),
        PeerOutcome("tala", 5670, 0.63, 0.09, 0.16, 0.07, 0.05, 28, 0.52, 2.8),
        PeerOutcome("branch", 4890, 0.65, 0.11, 0.14, 0.06, 0.04, 26, 0.55, 3.0),
        PeerOutcome("sacco_standard", 2340, 0.88, 0.22, 0.06, 0.02, 0.02, 180, 0.92, 4.5),
        PeerOutcome("sacco_emergency", 1890, 0.82, 0.15, 0.08, 0.03, 0.02, 90, 0.88, 4.2),
        PeerOutcome("bank_sme", 890, 0.91, 0.18, 0.05, 0.02, 0.02, 360, 0.90, 4.3),
        PeerOutcome("bank_personal", 1560, 0.85, 0.14, 0.07, 0.03, 0.03, 240, 0.82, 3.9),
        PeerOutcome("mfi_juhudi", 3210, 0.79, 0.12, 0.09, 0.04, 0.03, 150, 0.80, 3.8),
        PeerOutcome("mfi_group", 2780, 0.84, 0.16, 0.06, 0.02, 0.02, 120, 0.86, 4.1),
        PeerOutcome("chama_loan", 6540, 0.76, 0.10, 0.10, 0.04, 0.03, 45, 0.72, 3.5),
        PeerOutcome("watu_credit", 4120, 0.70, 0.08, 0.14, 0.06, 0.04, 200, 0.60, 3.0),
        PeerOutcome("mogo", 3890, 0.73, 0.10, 0.12, 0.05, 0.03, 280, 0.65, 3.2)
    )

    // ──────────────────────────────────────────────
    // EXECUTION DISPATCH
    // ──────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        // Voice input parsing
        if (!params["voice_input"].isNullOrBlank()) {
            return parseVoiceInput(params["voice_input"]!!)
        }

        val action = params["action"] ?: "compare"
        return when (action.lowercase()) {
            "compare" -> compareOffers(params)
            "true_cost" -> calculateTrueCost(params)
            "best_option" -> bestOption(params)
            "warning" -> checkWarnings(params)
            "history" -> showHistory(params)
            else -> ToolResult.error(
                name,
                "Unknown action: $action. Use: compare, true_cost, best_option, warning, history",
                "INVALID_ACTION"
            )
        }
    }

    // ──────────────────────────────────────────────
    // 1. COMPARE — Side-by-side comparison
    // ──────────────────────────────────────────────

    /**
     * Side-by-side comparison of 2-5 loan products for a given amount.
     * Shows true cost, effective APR, repayment schedule, and income impact.
     */
    private suspend fun compareOffers(params: Map<String, String>): ToolResult {
        return try {
            val amount = params["amount"]?.toDoubleOrNull() ?: run {
                // Try to get from recent context
                val contextAmount = memoryManager.retrieve("loan_comparison_amount")
                contextAmount.toDoubleOrNull() ?: 10_000.0
            }
            val purpose = params["purpose"] ?: "other"
            val urgency = params["urgency"] ?: "now"
            val requestedProductIds = params["products"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }

            // Get worker financial context
            val financials = getWorkerFinancials()
            val score = financials["alama_score"] as Int
            val avgDailyIncome = financials["avg_daily_income"] as Double
            val totalDebt = financials["total_debt"] as Double
            val debtToIncome = financials["debt_to_income_pct"] as Double

            // Filter products
            val catalog = buildLenderCatalog()
            val eligible = if (requestedProductIds != null && requestedProductIds.isNotEmpty()) {
                catalog.filter { it.id in requestedProductIds }
            } else {
                catalog.filter { product ->
                    score >= product.minAlamaScore &&
                            amount >= product.minAmount &&
                            amount <= product.maxAmount &&
                            (financials["avg_monthly_revenue"] as Double) >= product.minMonthlyRevenue
                }
            }

            if (eligible.isEmpty()) {
                return ToolResult.success(
                    toolName = name,
                    message = buildString {
                        appendLine("⚠️ Hakuna mkopo unaolingana na KES ${formatKes(amount)} kwa sasa.")
                        appendLine()
                        appendLine("Alama Score yako: $score")
                        appendLine("Jaribu:")
                        appendLine("• Omba kiasi kidogo")
                        appendLine("• Boresha Alama Score (tumia 'credit_readiness' → 'improve')")
                    }
                )
            }

            // Calculate true costs for all eligible products
            val comparisons = eligible.map { product ->
                val trueCost = calculateProductTrueCost(amount, product, financials)
                val incomeImpact = if (avgDailyIncome > 0) {
                    (trueCost["daily_burden"] as Double) / avgDailyIncome * 100
                } else 0.0
                val peerOutcome = buildPeerOutcomes().find { it.productId == product.id }

                Triple(product, trueCost, peerOutcome) to incomeImpact
            }.sortedBy { (it.first.second["effective_apr"] as Double) }

            // Save comparison to memory
            memoryManager.storeMemory(
                "last_loan_comparison",
                "amount=$amount,purpose=$purpose,products=${comparisons.size},date=${System.currentTimeMillis()}",
                "loan_comparison"
            )
            memoryManager.storeMemory("loan_comparison_amount", amount.toString(), "loan_comparison")

            // Build response
            val message = buildString {
                appendLine("📊 LINGANISHO LA MIKOPO — KES ${formatKes(amount)}")
                appendLine("   Kusudi: ${purposeLabel(purpose)} | Haraka: ${urgencyLabel(urgency)}")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                // Table header
                appendLine("┌─────────────────┬──────────┬──────────┬──────────┬──────────┐")
                appendLine("│                  │ ${padRight("M-Shwari", 8)} │ ${padRight("KCB", 8)} │ ${padRight("SACCO", 8)} │ ${padRight("Fuliza", 8)} │")
                appendLine("├─────────────────┼──────────┼──────────┼──────────┼──────────┤")

                // Show top 4 for table, rest below
                val topComparisons = comparisons.take(4)

                // Amount row
                appendLine("│ Kiasi           │ ${padRight(formatKesShort(amount), 8)} │ ${padRight(formatKesShort(amount), 8)} │ ${padRight(formatKesShort(amount), 8)} │ ${padRight(formatKesShort(amount), 8)} │")

                // Monthly rate row
                appendLine("│ Riba/mwezi      │ ${padRight(formatRate(topComparisons.getOrNull(0)?.first?.first?.monthlyRate), 8)} │ ${padRight(formatRate(topComparisons.getOrNull(1)?.first?.first?.monthlyRate), 8)} │ ${padRight(formatRate(topComparisons.getOrNull(2)?.first?.first?.monthlyRate), 8)} │ ${padRight(formatRate(topComparisons.getOrNull(3)?.first?.first?.monthlyRate), 8)} │")

                // Processing fee row
                appendLine("│ Ada             │ ${padRight(formatFee(topComparisons.getOrNull(0)?.first?.first), 8)} │ ${padRight(formatFee(topComparisons.getOrNull(1)?.first?.first), 8)} │ ${padRight(formatFee(topComparisons.getOrNull(2)?.first?.first), 8)} │ ${padRight(formatFee(topComparisons.getOrNull(3)?.first?.first), 8)} │")

                // Total repayment row
                appendLine("│ Jumla ya kulipa │ ${padRight(formatKesShort(topComparisons.getOrNull(0)?.first?.second?.get("total_repayment") as? Double), 8)} │ ${padRight(formatKesShort(topComparisons.getOrNull(1)?.first?.second?.get("total_repayment") as? Double), 8)} │ ${padRight(formatKesShort(topComparisons.getOrNull(2)?.first?.second?.get("total_repayment") as? Double), 8)} │ ${padRight(formatKesShort(topComparisons.getOrNull(3)?.first?.second?.get("total_repayment") as? Double), 8)} │")

                // Effective APR row
                appendLine("│ APR halisi      │ ${padRight(formatAprShort(topComparisons.getOrNull(0)?.first?.second?.get("effective_apr") as? Double), 8)} │ ${padRight(formatAprShort(topComparisons.getOrNull(1)?.first?.second?.get("effective_apr") as? Double), 8)} │ ${padRight(formatAprShort(topComparisons.getOrNull(2)?.first?.second?.get("effective_apr") as? Double), 8)} │ ${padRight(formatAprShort(topComparisons.getOrNull(3)?.first?.second?.get("effective_apr") as? Double), 8)} │")

                appendLine("└─────────────────┴──────────┴──────────┴──────────┴──────────┘")
                appendLine()

                // Detailed breakdown for each product
                for ((idx, comp) in comparisons.withIndex()) {
                    val product = comp.first.first
                    val tc = comp.first.second
                    val peer = comp.first.third
                    val incomeImpactPct = comp.second
                    val apr = (tc["effective_apr"] as Double) * 100
                    val totalRepay = tc["total_repayment"] as Double
                    val dailyBurden = tc["daily_burden"] as Double

                    appendLine("${idx + 1}. ${product.swahiliName}")
                    appendLine("   Kiasi: KES ${formatKes(amount)} | Riba: ${"%.1f".format(product.monthlyRate * 100)}%/mwezi")
                    val processingFee = tc["processing_fee"] as Double
                    if (processingFee > 0) {
                        appendLine("   Ada ya usindikaji: KES ${formatKes(processingFee)}")
                    }
                    appendLine("   Gharama halisi (APR): ${formatApr(apr)}")
                    appendLine("   Jumla ya kulipa: KES ${formatKes(totalRepay)}")
                    appendLine("   Mzigo wa siku: KES ${formatKes(dailyBurden)} (${formatPct(incomeImpactPct)} ya mapato)")
                    appendLine("   ⏱️ Kupokea: ${product.disbursement} | 📅 Kulipa: ${product.repayment}")

                    // Peer data
                    if (peer != null) {
                        appendLine("   📱 Data ya wateja: ${peer.totalBorrowers} walikopa. ${formatPct(peer.onTimePct * 100)} walilipa kwa wakati.")
                    }

                    // Warnings
                    if (apr > 100) {
                        appendLine("   🔴 ONYO: Riba ni ya juu sana!")
                    } else if (apr > 50) {
                        appendLine("   ⚠️ Riba ya juu — tumia kwa dharura tu.")
                    }
                    if (product.requiresGuarantor) appendLine("   ⚠️ Inahitaji mdhamini")
                    if (product.requiresCollateral) appendLine("   ⚠️ Inahitaji dhamana")
                    appendLine()
                }

                // Recommendation
                val best = comparisons.first()
                val bestProduct = best.first.first
                appendLine("🏆 MAPENDEKEZO:")
                appendLine("   Bora zaidi: ${bestProduct.swahiliName}")
                if (urgency == "now" && bestProduct.disbursement != "instant") {
                    val instantOption = comparisons.find { it.first.first.disbursement == "instant" }
                    if (instantOption != null) {
                        appendLine("   Ukikopa sasa hivi: ${instantOption.first.first.swahiliName} (haraka zaidi)")
                    }
                }

                // Debt warning
                if (debtToIncome > 20) {
                    appendLine()
                    appendLine("⚠️ ONYO: Deni lako ni ${formatPct(debtToIncome)} ya mapato.")
                    appendLine("   Ongeza deni kwa busara. Kiwango kinachopendekezwa: chini ya 20%.")
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "amount" to amount,
                    "purpose" to purpose,
                    "urgency" to urgency,
                    "comparisons" to comparisons.map { comp ->
                        val product = comp.first.first
                        val tc = comp.first.second
                        mapOf(
                            "product_id" to product.id,
                            "lender" to product.lender,
                            "product" to product.product,
                            "swahili_name" to product.swahiliName,
                            "amount" to amount,
                            "monthly_rate" to product.monthlyRate,
                            "processing_fee" to (tc["processing_fee"] as Double),
                            "total_repayment" to (tc["total_repayment"] as Double),
                            "effective_apr" to (tc["effective_apr"] as Double),
                            "daily_burden" to (tc["daily_burden"] as Double),
                            "income_impact_pct" to comp.second,
                            "disbursement" to product.disbursement,
                            "repayment" to product.repayment
                        )
                    },
                    "recommended" to best.first.first.swahiliName,
                    "worker_score" to score,
                    "debt_to_income_pct" to debtToIncome
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to compare loans")
            ToolResult.error(name, "Failed to compare: ${e.message}", "CALCULATION_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 2. TRUE COST — Full cost breakdown
    // ──────────────────────────────────────────────

    /**
     * Show total repayment including all fees, insurance, penalties.
     * Reveals the hidden cost that lenders don't show upfront.
     */
    private suspend fun calculateTrueCost(params: Map<String, String>): ToolResult {
        return try {
            val amount = params["amount"]?.toDoubleOrNull() ?: 10_000.0
            val productIds = params["products"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }

            val financials = getWorkerFinancials()
            val avgDailyIncome = financials["avg_daily_income"] as Double

            val catalog = buildLenderCatalog()
            val products = if (productIds != null && productIds.isNotEmpty()) {
                catalog.filter { it.id in productIds }
            } else {
                // Default: compare mobile loans which have the most hidden fees
                catalog.filter { it.type == LenderType.MOBILE_LOAN || it.type == LenderType.OVERDRAFT }
            }

            if (products.isEmpty()) {
                return ToolResult.error(name, "Hakuna bidhaa zilizopatikana. Taja product IDs sahihi.", "NO_PRODUCTS")
            }

            val costBreakdowns = products.map { product ->
                val tc = calculateProductTrueCost(amount, product, financials)
                product to tc
            }.sortedBy { it.second["effective_apr"] as Double }

            val message = buildString {
                appendLine("💰 GHARAMA HALISI YA MIKOPO — KES ${formatKes(amount)}")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("Wakopeshaji wanaonyesha riba ndogo lakini kuna ada za siri.")
                appendLine("Hii ndiyo gharama HALISI utakayolipa:")
                appendLine()

                for ((product, tc) in costBreakdowns) {
                    val apr = (tc["effective_apr"] as Double) * 100
                    val totalInterest = tc["interest_charged"] as Double
                    val processingFee = tc["processing_fee"] as Double
                    val insuranceFee = tc["insurance_fee"] as Double
                    val totalFees = tc["total_fees"] as Double
                    val totalRepay = tc["total_repayment"] as Double
                    val dailyBurden = tc["daily_burden"] as Double
                    val incomeImpact = if (avgDailyIncome > 0) dailyBurden / avgDailyIncome * 100 else 0.0

                    appendLine("┌─ ${product.swahiliName} ──────────────────────────")
                    appendLine("│ Kiasi:              KES ${formatKes(amount)}")
                    appendLine("│ Riba ya mwezi:      ${"%.1f".format(product.monthlyRate * 100)}%")
                    appendLine("│ Riba halisi:        KES ${formatKes(totalInterest)}")
                    if (processingFee > 0) {
                        appendLine("│ Ada ya usindikaji:  KES ${formatKes(processingFee)} ← ADA YA SIRI!")
                    }
                    if (insuranceFee > 0) {
                        appendLine("│ Bima:               KES ${formatKes(insuranceFee)}")
                    }
                    if (totalFees > 0) {
                        appendLine("│ Jumla ada:          KES ${formatKes(totalFees)}")
                    }
                    appendLine("│ ─────────────────────────────────────")
                    appendLine("│ UTALIPA JUMLA:      KES ${formatKes(totalRepay)}")
                    appendLine("│ Gharama ya ziada:   KES ${formatKes(totalRepay - amount)}")
                    appendLine("│ APR HALISI:         ${formatApr(apr)}")
                    appendLine("│ Mzigo wa siku:      KES ${formatKes(dailyBurden)}")
                    appendLine("│ % ya mapato ya siku: ${formatPct(incomeImpact)}")
                    appendLine("└──────────────────────────────────────")
                    appendLine()

                    // Highlight hidden fees
                    if (processingFee > 0 || insuranceFee > 0) {
                        appendLine("   ⚠️ ADA ZA SIRI: KES ${formatKes(totalFees)} — hizi hazionyeshwi wazi!")
                        if (product.processingFeeFixed > 0) {
                            val feeAsPct = (product.processingFeeFixed / amount) * 100
                            appendLine("   Ada ya KES ${formatKes(product.processingFeeFixed)} ni ${formatPct(feeAsPct)} ya mkopo wako!")
                        }
                        appendLine()
                    }
                }

                // Compare the hidden cost difference
                if (costBreakdowns.size >= 2) {
                    val cheapest = costBreakdowns.first()
                    val mostExpensive = costBreakdowns.last()
                    val savings = mostExpensive.second["total_repayment"] as Double - cheapest.second["total_repayment"] as Double
                    if (savings > 0) {
                        appendLine("💡 UKICHAGUA ${cheapest.first.swahiliName} badala ya ${mostExpensive.first.swahiliName}:")
                        appendLine("   UTAOKOA KES ${formatKes(savings)}!")
                        appendLine("   Hiyo ni bei ya ${estimateEquivalent(savings)}")
                    }
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "amount" to amount,
                    "breakdowns" to costBreakdowns.map { (product, tc) ->
                        mapOf(
                            "product_id" to product.id,
                            "swahili_name" to product.swahiliName,
                            "principal" to amount,
                            "interest_charged" to (tc["interest_charged"] as Double),
                            "processing_fee" to (tc["processing_fee"] as Double),
                            "insurance_fee" to (tc["insurance_fee"] as Double),
                            "total_fees" to (tc["total_fees"] as Double),
                            "total_repayment" to (tc["total_repayment"] as Double),
                            "effective_apr" to (tc["effective_apr"] as Double),
                            "daily_burden" to (tc["daily_burden"] as Double),
                            "hidden_fees_total" to (tc["total_fees"] as Double)
                        )
                    }
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to calculate true cost")
            ToolResult.error(name, "Failed: ${e.message}", "CALCULATION_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 3. BEST OPTION — Ranked recommendation
    // ──────────────────────────────────────────────

    /**
     * Recommend the best loan option based on the worker's specific situation.
     * Considers urgency, income pattern, existing debt, and risk tolerance.
     */
    private suspend fun bestOption(params: Map<String, String>): ToolResult {
        return try {
            val amount = params["amount"]?.toDoubleOrNull() ?: 10_000.0
            val purpose = params["purpose"] ?: "other"
            val urgency = params["urgency"] ?: "now"

            val financials = getWorkerFinancials()
            val score = financials["alama_score"] as Int
            val avgDailyIncome = financials["avg_daily_income"] as Double
            val totalDebt = financials["total_debt"] as Double
            val debtToIncome = financials["debt_to_income_pct"] as Double

            val catalog = buildLenderCatalog()
            val peerOutcomes = buildPeerOutcomes()

            // Filter eligible products
            val eligible = catalog.filter { product ->
                score >= product.minAlamaScore &&
                        amount >= product.minAmount &&
                        amount <= product.maxAmount &&
                        (financials["avg_monthly_revenue"] as Double) >= product.minMonthlyRevenue
            }

            if (eligible.isEmpty()) {
                return ToolResult.success(
                    toolName = name,
                    message = buildString {
                        appendLine("⚠️ Hakuna mkopo unaopatikana kwa KES ${formatKes(amount)} kwa sasa.")
                        appendLine("Alama Score: $score — boresha alama yako kwanza.")
                    }
                )
            }

            // Score each product based on worker's situation
            data class ScoredProduct(
                val product: LenderProduct,
                val trueCost: Map<String, Any>,
                val peer: PeerOutcome?,
                val costScore: Double,
                val speedScore: Double,
                val safetyScore: Double,
                val incomeFitScore: Double,
                val totalScore: Double,
                val reason: String
            )

            val scored = eligible.map { product ->
                val tc = calculateProductTrueCost(amount, product, financials)
                val peer = peerOutcomes.find { it.productId == product.id }
                val apr = tc["effective_apr"] as Double
                val dailyBurden = tc["daily_burden"] as Double
                val incomeImpact = if (avgDailyIncome > 0) dailyBurden / avgDailyIncome * 100 else 100.0

                // Cost score (lower APR = higher score, max 30 points)
                val costScore = when {
                    apr <= 0.15 -> 30.0
                    apr <= 0.30 -> 25.0
                    apr <= 0.50 -> 20.0
                    apr <= 0.80 -> 15.0
                    apr <= 1.00 -> 10.0
                    apr <= 2.00 -> 5.0
                    else -> 0.0
                }

                // Speed score (max 20 points based on urgency)
                val speedMultiplier = when (urgency) {
                    "now" -> 1.0
                    "this_week" -> 0.7
                    "this_month" -> 0.4
                    else -> 0.5
                }
                val baseSpeed = when (product.disbursement) {
                    "instant" -> 20.0
                    "24h" -> 16.0
                    "3 days" -> 12.0
                    "1 week" -> 8.0
                    else -> 4.0
                }
                val speedScore = baseSpeed * speedMultiplier

                // Safety score (max 25 points — based on peer outcomes and type)
                val safetyBase = when (product.type) {
                    LenderType.SACCO -> 25.0
                    LenderType.BANK_SME -> 25.0
                    LenderType.MFI -> 20.0
                    LenderType.MOBILE_LOAN -> 12.0
                    LenderType.HIRE_PURCHASE -> 8.0
                    LenderType.OVERDRAFT -> 3.0
                }
                val peerBonus = if (peer != null && peer.onTimePct > 0.8) 5.0 else 0.0
                val safetyScore = safetyBase + peerBonus

                // Income fit score (max 25 points — how well repayment fits income)
                val incomeFitScore = when {
                    incomeImpact < 5 -> 25.0
                    incomeImpact < 10 -> 20.0
                    incomeImpact < 15 -> 15.0
                    incomeImpact < 20 -> 10.0
                    incomeImpact < 30 -> 5.0
                    else -> 0.0
                }

                // Penalty for high debt-to-income
                val dtiPenalty = if (debtToIncome > 30 && apr > 0.5) -10.0 else 0.0

                val totalScore = costScore + speedScore + safetyScore + incomeFitScore + dtiPenalty

                // Build reason
                val reason = buildString {
                    when {
                        apr <= 0.20 -> append("Riba nafuu (${formatApr(apr * 100)})")
                        apr <= 0.50 -> append("Riba wastani (${formatApr(apr * 100)})")
                        else -> append("Riba ya juu (${formatApr(apr * 100)})")
                    }
                    if (product.disbursement == "instant") append(", haraka sana")
                    if (peer != null && peer.onTimePct > 0.8) append(", wateja wengi walilipa kwa wakati")
                    if (incomeImpact > 20) append(", ⚠️ mzigo mkubwa kwa mapato yako")
                }

                ScoredProduct(product, tc, peer, costScore, speedScore, safetyScore, incomeFitScore, totalScore, reason)
            }.sortedByDescending { it.totalScore }

            val best = scored.first()
            val bestApr = (best.trueCost["effective_apr"] as Double) * 100

            val message = buildString {
                appendLine("🏆 CHAGUA BORA — KES ${formatKes(amount)} kwa ${purposeLabel(purpose)}")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("Kulingana na hali yako (Alama Score: $score, mapato: KES ${formatKes(avgDailyIncome)}/siku):")
                appendLine()

                for ((idx, sp) in scored.withIndex()) {
                    val medal = when (idx) {
                        0 -> "🥇"
                        1 -> "🥈"
                        2 -> "🥉"
                        else -> "  "
                    }
                    val totalRepay = sp.trueCost["total_repayment"] as Double
                    val aprVal = (sp.trueCost["effective_apr"] as Double) * 100

                    appendLine("$medal ${idx + 1}. ${sp.product.swahiliName} — Alama: ${"%.0f".format(sp.totalScore)}/100")
                    appendLine("   Jumla: KES ${formatKes(totalRepay)} | APR: ${formatApr(aprVal)}")
                    appendLine("   ${sp.reason}")
                    appendLine()
                }

                // Detailed recommendation for the best
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("✅ MAPENDEKEZO: ${best.product.swahiliName}")
                appendLine()
                appendLine("Kwa nini:")
                appendLine("• Gharama halisi: ${formatApr(bestApr)} APR")
                appendLine("• Jumla utakayolipa: KES ${formatKes(best.trueCost["total_repayment"] as Double)}")
                val dailyBurden = best.trueCost["daily_burden"] as Double
                val incomeImpact = if (avgDailyIncome > 0) dailyBurden / avgDailyIncome * 100 else 0.0
                appendLine("• Mzigo wa siku: KES ${formatKes(dailyBurden)} (${formatPct(incomeImpact)} ya mapato)")
                appendLine("• Kupokea: ${best.product.disbursement}")

                if (best.peer != null) {
                    appendLine("• ${formatPct(best.peer.onTimePct * 100)} ya wateja walilipa kwa wakati")
                }

                // Savings vs worst option
                if (scored.size >= 2) {
                    val worst = scored.last()
                    val savings = (worst.trueCost["total_repayment"] as Double) - (best.trueCost["total_repayment"] as Double)
                    if (savings > 0) {
                        appendLine()
                        appendLine("💰 Ukichagua hii badala ya ${worst.product.swahiliName}:")
                        appendLine("   Utaokoa KES ${formatKes(savings)}")
                    }
                }

                // Debt warning
                if (debtToIncome > 20) {
                    appendLine()
                    appendLine("⚠️ Tahadhari: Deni lako ni ${formatPct(debtToIncome)} ya mapato.")
                    appendLine("   Fikiria kwanza kabla ya kuongeza deni jipya.")
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "amount" to amount,
                    "purpose" to purpose,
                    "urgency" to urgency,
                    "recommended" to mapOf(
                        "product_id" to best.product.id,
                        "swahili_name" to best.product.swahiliName,
                        "total_score" to best.totalScore,
                        "effective_apr" to (best.trueCost["effective_apr"] as Double),
                        "total_repayment" to (best.trueCost["total_repayment"] as Double),
                        "daily_burden" to (best.trueCost["daily_burden"] as Double),
                        "reason" to best.reason
                    ),
                    "rankings" to scored.map { sp ->
                        mapOf(
                            "product_id" to sp.product.id,
                            "swahili_name" to sp.product.swahiliName,
                            "total_score" to sp.totalScore,
                            "cost_score" to sp.costScore,
                            "speed_score" to sp.speedScore,
                            "safety_score" to sp.safetyScore,
                            "income_fit_score" to sp.incomeFitScore
                        )
                    }
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to find best option")
            ToolResult.error(name, "Failed: ${e.message}", "CALCULATION_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 4. WARNING — Flag predatory loans
    // ──────────────────────────────────────────────

    /**
     * Red-flag loans with predatory terms: effective APR > 50%, hidden fees,
     * dangerous repayment structures, or terms that exploit information asymmetry.
     */
    private suspend fun checkWarnings(params: Map<String, String>): ToolResult {
        return try {
            val amount = params["amount"]?.toDoubleOrNull() ?: 10_000.0
            val productIds = params["products"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }

            val financials = getWorkerFinancials()
            val score = financials["alama_score"] as Int
            val avgDailyIncome = financials["avg_daily_income"] as Double
            val debtToIncome = financials["debt_to_income_pct"] as Double

            val catalog = buildLenderCatalog()
            val products = if (productIds != null && productIds.isNotEmpty()) {
                catalog.filter { it.id in productIds }
            } else {
                // Check all products the worker might consider
                catalog.filter { score >= it.minAlamaScore && amount >= it.minAmount && amount <= it.maxAmount }
            }

            val warnings = mutableListOf<Map<String, Any>>()

            for (product in products) {
                val tc = calculateProductTrueCost(amount, product, financials)
                val apr = tc["effective_apr"] as Double
                val processingFee = tc["processing_fee"] as Double
                val dailyBurden = tc["daily_burden"] as Double
                val incomeImpact = if (avgDailyIncome > 0) dailyBurden / avgDailyIncome * 100 else 100.0
                val totalRepay = tc["total_repayment"] as Double
                val extraCost = totalRepay - amount

                val flags = mutableListOf<String>()
                var severity = "safe" // safe, caution, danger, predatory

                // Check APR
                when {
                    apr > 2.0 -> {
                        flags.add("Riba ya ajabu: ${formatApr(apr * 100)} APR — ni zaidi ya mara 2 ya mkopo!")
                        severity = "predatory"
                    }
                    apr > 1.0 -> {
                        flags.add("Riba ya juu sana: ${formatApr(apr * 100)} APR — utalipa zaidi ya mara 2!")
                        severity = "danger"
                    }
                    apr > 0.50 -> {
                        flags.add("Riba ya juu: ${formatApr(apr * 100)} APR")
                        if (severity == "safe") severity = "caution"
                    }
                }

                // Check hidden fees
                if (processingFee > 0) {
                    val feePct = (processingFee / amount) * 100
                    if (feePct > 5) {
                        flags.add("Ada ya siri: KES ${formatKes(processingFee)} (${formatPct(feePct)} ya mkopo wako!)")
                        if (severity == "safe" || severity == "caution") severity = "danger"
                    } else if (feePct > 2) {
                        flags.add("Ada ya usindikaji: KES ${formatKes(processingFee)} (${formatPct(feePct)})")
                        if (severity == "safe") severity = "caution"
                    }
                }

                // Check income impact
                when {
                    incomeImpact > 50 -> {
                        flags.add("Mzigo mkubwa: ${formatPct(incomeImpact)} ya mapato yako ya siku — huwezi kulipa!")
                        severity = "predatory"
                    }
                    incomeImpact > 30 -> {
                        flags.add("Mzigo mkubwa: ${formatPct(incomeImpact)} ya mapato yako ya siku")
                        if (severity != "predatory") severity = "danger"
                    }
                    incomeImpact > 20 -> {
                        flags.add("Mzigo: ${formatPct(incomeImpact)} ya mapato — juu kidogo")
                        if (severity == "safe") severity = "caution"
                    }
                }

                // Check debt-to-income
                if (debtToIncome > 30) {
                    val newDti = debtToIncome + incomeImpact
                    flags.add("Deni lako tayari ni ${formatPct(debtToIncome)} — mkopo huu utafanya ${formatPct(newDti)}")
                    if (severity == "safe" || severity == "caution") severity = "danger"
                }

                // Check repayment structure dangers
                if (product.repayment == "daily" && incomeImpact > 15) {
                    flags.add("Malipo ya kila siku — ukikosa siku moja, adhabu ni kubwa")
                    if (severity == "safe") severity = "caution"
                }

                // Check for Fuliza trap
                if (product.id == "fuliza") {
                    flags.add("Fuliza ni mtego — 1% kwa siku = 365% APR! Tumia kwa dharura TU.")
                    severity = "predatory"
                }

                // Check if extra cost exceeds principal
                if (extraCost > amount) {
                    flags.add("Utalipa KES ${formatKes(extraCost)} ZAIDI ya mkopo wako — ni mara ${"%.1f".format(extraCost / amount + 1)} ya kiasi!")
                    if (severity != "predatory") severity = "danger"
                }

                if (flags.isNotEmpty()) {
                    warnings.add(mapOf(
                        "product_id" to product.id,
                        "swahili_name" to product.swahiliName,
                        "severity" to severity,
                        "flags" to flags,
                        "effective_apr" to apr,
                        "total_repayment" to totalRepay,
                        "income_impact_pct" to incomeImpact
                    ))
                }
            }

            // Sort by severity
            val severityOrder = mapOf("predatory" to 0, "danger" to 1, "caution" to 2, "safe" to 3)
            warnings.sortBy { severityOrder[it["severity"]] ?: 3 }

            val message = buildString {
                if (warnings.isEmpty()) {
                    appendLine("✅ HAKUNA ONYO — Mikopo unayochunguza ni salama.")
                    appendLine("Endelea kulinganisha na kuchagua bora zaidi.")
                } else {
                    appendLine("🚨 ONYO ZA MIKOPO — KES ${formatKes(amount)}")
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine()

                    for (warning in warnings) {
                        val severity = warning["severity"] as String
                        val severityEmoji = when (severity) {
                            "predatory" -> "🔴 MTEGO"
                            "danger" -> "🟠 HATARI"
                            "caution" -> "🟡 TAHADHARI"
                            else -> "🟢 SALAMA"
                        }

                        appendLine("$severityEmoji — ${warning["swahili_name"]}")
                        appendLine("   APR: ${formatApr((warning["effective_apr"] as Double) * 100)}")
                        appendLine("   Jumla ya kulipa: KES ${formatKes(warning["total_repayment"] as Double)}")
                        for (flag in warning["flags"] as List<String>) {
                            appendLine("   ⚠️ $flag")
                        }
                        appendLine()
                    }

                    // Predatory loan advice
                    val predatory = warnings.filter { it["severity"] == "predatory" }
                    if (predatory.isNotEmpty()) {
                        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        appendLine("🚨 MIKOPO HII NI MTEGO!")
                        appendLine("   Usikope kutoka kwa wakopeshaji hawa ikiwa una njia nyingine.")
                        appendLine("   Badala yake:")
                        appendLine("   • Omba mkopo wa SACCO (riba 15% APR)")
                        appendLine("   • Omba mkopo wa chama yako")
                        appendLine("   • Zungumza na mdhibiti wako wa biashara")
                        appendLine("   • Tafuta msaada wa kifedha (Financial Literacy)")
                    }
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "amount" to amount,
                    "warnings" to warnings,
                    "predatory_count" to warnings.count { it["severity"] == "predatory" },
                    "danger_count" to warnings.count { it["severity"] == "danger" },
                    "caution_count" to warnings.count { it["severity"] == "caution" }
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to check warnings")
            ToolResult.error(name, "Failed: ${e.message}", "CALCULATION_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 5. HISTORY — Past comparisons
    // ──────────────────────────────────────────────

    /**
     * Show history of loan comparisons, what was recommended, and what the worker chose.
     * Helps workers learn from past decisions.
     */
    private suspend fun showHistory(params: Map<String, String>): ToolResult {
        return try {
            val financials = getWorkerFinancials()
            val score = financials["alama_score"] as Int
            val totalDebt = financials["total_debt"] as Double
            val debtToIncome = financials["debt_to_income_pct"] as Double
            val avgDailyIncome = financials["avg_daily_income"] as Double

            // Retrieve stored comparison history
            val lastComparison = memoryManager.retrieve("last_loan_comparison")
            val comparisonCount = memoryManager.retrieve("loan_comparison_count")

            // Get active debts
            val activeDebts = debtDao.getActiveDebtCount().first()

            // Score milestones
            val catalog = buildLenderCatalog()
            val eligibleCount = catalog.count { score >= it.minAlamaScore }

            val message = buildString {
                appendLine("📜 HISTORIA YA LINGANISHO LA MIKOPO")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("📊 HALI YAKO YA SASA:")
                appendLine("   Alama Score: $score")
                appendLine("   Deni la sasa: KES ${formatKes(totalDebt)} (${formatPct(debtToIncome)} ya mapato)")
                appendLine("   Mapato ya siku: KES ${formatKes(avgDailyIncome)}")
                appendLine("   Mikopo inayopatikana: $eligibleCount bidhaa")
                appendLine("   Deni hai: $activeDebts")
                appendLine()

                // Last comparison
                if (lastComparison.isNotBlank()) {
                    appendLine("📋 LINGANISHO LA MWISHO:")
                    appendLine("   $lastComparison")
                    appendLine()
                }

                // Comparison count
                if (comparisonCount.isNotBlank()) {
                    appendLine("📊 Jumla ya linganisho: $comparisonCount")
                    appendLine()
                }

                // Tips for comparing
                appendLine("💡 VIDOLE VYA KULINGANISHA:")
                appendLine("   1. Angalia APR halisi — siyo riba ya mwezi tu")
                appendLine("   2. Jumlisha ADA ZOTE — usindikaji, bima, adhabu")
                appendLine("   3. Linganisha na mapato yako — mzigo wa siku < 20%")
                appendLine("   4. Angalia data ya wateja — wangapi walilipa kwa wakati?")
                appendLine("   5. Fikiria haraka vs. gharama — je, unaweza subiri?")

                // Available actions
                appendLine()
                appendLine("📱 UNAWEZA:")
                appendLine("   • 'Nilinganishe mikopo' — linganisha bidhaa")
                appendLine("   • 'Gharama halisi ya M-Shwari' — angalia ada za siri")
                appendLine("   • 'Nipi ni bora?' — pendekezo la haraka")
                appendLine("   • 'Nionyeshe onyo' — angalia mikopo hatari")
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "current_score" to score,
                    "total_debt" to totalDebt,
                    "debt_to_income_pct" to debtToIncome,
                    "avg_daily_income" to avgDailyIncome,
                    "eligible_products" to eligibleCount,
                    "active_debts" to activeDebts,
                    "last_comparison" to lastComparison
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to show history")
            ToolResult.error(name, "Failed: ${e.message}", "CALCULATION_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // VOICE INPUT PARSING (Swahili)
    // ──────────────────────────────────────────────

    /**
     * Parse Swahili voice input and route to appropriate action.
     *
     * Trigger phrases:
     * - "Nilinganishe mikopo" → compare
     * - "M-Shwari au KCB — ipi ni bora?" → compare
     * - "Riba ya mkopo ni ngapi?" → true_cost
     * - "Ninahitaji KES 5,000 — ni wapi nipate?" → best_option
     * - "Gharama halisi" → true_cost
     * - "Nionyeshe onyo" → warning
     * - "Mikopo hatari" → warning
     * - "Historia yangu" → history
     */
    private suspend fun parseVoiceInput(voiceInput: String): ToolResult {
        val input = voiceInput.lowercase().trim()
        Timber.d("Parsing loan comparison voice input: '$input'")

        val amount = extractAmount(input)
        val purpose = extractPurpose(input)
        val urgency = extractUrgency(input)

        return when {
            // True cost intents
            input.contains("gharama halisi") || input.contains("true cost") ||
            input.contains("ada za siri") || input.contains("hidden fee") ||
            input.contains("riba ya") || input.contains("interest") -> {
                val params = mutableMapOf<String, String>("action" to "true_cost")
                if (amount != null) params["amount"] = amount.toString()
                // Extract product if mentioned
                val product = extractProductId(input)
                if (product != null) params["products"] = product
                calculateTrueCost(params)
            }

            // Warning intents
            input.contains("onyo") || input.contains("warning") ||
            input.contains("hatari") || input.contains("danger") ||
            input.contains("mtego") || input.contains("predatory") ||
            input.contains("siri") || input.contains("hidden") -> {
                val params = mutableMapOf<String, String>("action" to "warning")
                if (amount != null) params["amount"] = amount.toString()
                checkWarnings(params)
            }

            // Best option intents
            input.contains("bora") || input.contains("best") ||
            input.contains("nipate wapi") || input.contains("nichukue") ||
            input.contains("nini ni bora") || input.contains("ipi ni bora") ||
            input.contains("which is better") || input.contains("recommend") -> {
                val params = mutableMapOf<String, String>("action" to "best_option")
                if (amount != null) params["amount"] = amount.toString()
                if (purpose != null) params["purpose"] = purpose
                if (urgency != null) params["urgency"] = urgency
                bestOption(params)
            }

            // History intents
            input.contains("historia") || input.contains("history") ||
            input.contains("record") || input.contains("nilichukua") ||
            input.contains("nilienda") -> {
                showHistory(mutableMapOf("action" to "history"))
            }

            // Compare intents (default for loan-related queries)
            input.contains("lingan") || input.contains("compare") ||
            input.contains("au") || input.contains("or") ||
            input.contains("mkopo") || input.contains("loan") ||
            input.contains("kopesha") || input.contains("borrow") -> {
                val params = mutableMapOf<String, String>("action" to "compare")
                if (amount != null) params["amount"] = amount.toString()
                if (purpose != null) params["purpose"] = purpose
                if (urgency != null) params["urgency"] = urgency
                val product = extractProductId(input)
                if (product != null) params["products"] = product
                compareOffers(params)
            }

            // Default: if amount mentioned, show best option; otherwise show compare
            else -> {
                val params = mutableMapOf<String, String>(
                    "action" to if (amount != null) "best_option" else "compare"
                )
                if (amount != null) params["amount"] = amount.toString()
                if (purpose != null) params["purpose"] = purpose
                if (urgency != null) params["urgency"] = urgency
                if (amount != null) bestOption(params) else compareOffers(params)
            }
        }
    }

    /**
     * Extract KES amount from Swahili voice input.
     */
    private fun extractAmount(input: String): Double? {
        val kesPattern = Regex("""(?:kes|ksh|k)\s*(\d[\d,]*(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        kesPattern.find(input)?.let {
            return it.groupValues[1].replace(",", "").toDoubleOrNull()
        }
        val numberPattern = Regex("""(\d[\d,]*(?:\.\d+)?)""")
        numberPattern.find(input)?.let {
            return it.groupValues[1].replace(",", "").toDoubleOrNull()
        }
        val swahiliNumbers = mapOf(
            "elfu moja" to 1000.0, "elfu mbili" to 2000.0, "elfu tatu" to 3000.0,
            "elfu nne" to 4000.0, "elfu tano" to 5000.0, "elfu kumi" to 10000.0,
            "elfu ishirini" to 20000.0, "elfu hamsini" to 50000.0,
            "laki moja" to 100000.0, "laki mbili" to 200000.0,
            "milioni" to 1000000.0
        )
        for ((word, value) in swahiliNumbers) {
            if (input.contains(word)) return value
        }
        return null
    }

    /**
     * Extract loan purpose from Swahili voice input.
     */
    private fun extractPurpose(input: String): String? {
        return when {
            input.contains("stock") || input.contains("bidhaa") || input.contains("mboga") -> "stock"
            input.contains("dharura") || input.contains("emergency") || input.contains("haraka") -> "emergency"
            input.contains("shule") || input.contains("school") || input.contains("fees") -> "school_fees"
            input.contains("vifaa") || input.contains("equipment") || input.contains("machine") -> "equipment"
            input.contains("boda") || input.contains("pikipiki") || input.contains("bike") -> "bike_repair"
            else -> null
        }
    }

    /**
     * Extract urgency from Swahili voice input.
     */
    private fun extractUrgency(input: String): String? {
        return when {
            input.contains("sasa") || input.contains("leo") || input.contains("now") ||
            input.contains("sasa hivi") || input.contains("immediately") -> "now"
            input.contains("wiki hii") || input.contains("this week") ||
            input.contains("kabla ya") -> "this_week"
            input.contains("mwezi huu") || input.contains("this month") -> "this_month"
            else -> null
        }
    }

    /**
     * Extract product ID from voice input if specific lender mentioned.
     */
    private fun extractProductId(input: String): String? {
        return when {
            input.contains("m-shwari") || input.contains("mshwari") -> "mshwari"
            input.contains("kcb") -> "kcb_mpesa"
            input.contains("fuliza") -> "fuliza"
            input.contains("tala") -> "tala"
            input.contains("branch") -> "branch"
            input.contains("sacco") -> "sacco_standard"
            input.contains("chama") -> "chama_loan"
            input.contains("watu") -> "watu_credit"
            input.contains("mogo") -> "mogo"
            input.contains("mfi") || input.contains("juhudi") -> "mfi_juhudi"
            else -> null
        }
    }

    // ──────────────────────────────────────────────
    // CALCULATION HELPERS
    // ──────────────────────────────────────────────

    /**
     * Get worker financial context from multiple sources.
     */
    private suspend fun getWorkerFinancials(): Map<String, Any> {
        val scoreResult = alamaScore.calculateScore()
        val score = scoreResult.score

        val summaries = dailySummaryDao.getRecentSummaries(30).first()
        val avgDailySales = if (summaries.isNotEmpty()) {
            summaries.map { it.totalSales }.average()
        } else 0.0
        val avgMonthlyRevenue = avgDailySales * 30

        val totalDebt = debtDao.getTotalOutstanding().first() ?: 0.0
        val debtToIncome = if (avgMonthlyRevenue > 0) (totalDebt / avgMonthlyRevenue * 100) else 0.0

        val businessType = memoryManager.retrieve("business_type").ifBlank { "informal_trader" }
        val location = memoryManager.retrieve("location").ifBlank { "Kenya" }

        return mapOf(
            "alama_score" to score,
            "avg_daily_income" to avgDailySales,
            "avg_monthly_revenue" to avgMonthlyRevenue,
            "total_debt" to totalDebt,
            "debt_to_income_pct" to debtToIncome,
            "business_type" to businessType,
            "location" to location
        )
    }

    /**
     * Calculate true cost of a loan including all hidden fees.
     * This is the core calculation that reveals what lenders don't show.
     */
    private fun calculateProductTrueCost(
        principal: Double,
        product: LenderProduct,
        financials: Map<String, Any>
    ): Map<String, Any> {
        val termDays = product.maxTermDays
        val termMonths = (termDays / 30.0).coerceAtLeast(1.0)

        // Calculate fees
        val processingFee = (principal * product.processingFeePct) + product.processingFeeFixed
        val insuranceFee = principal * product.insuranceFeePct
        val totalFees = processingFee + insuranceFee

        // Calculate interest based on product type
        val totalInterest = when (product.type) {
            LenderType.OVERDRAFT -> {
                // Daily-charged products (Fuliza): ~1% per day
                principal * 0.01 * termDays
            }
            else -> {
                // Monthly rate products
                principal * product.monthlyRate * termMonths
            }
        }

        // Total repayment
        val totalRepayment = principal + totalInterest + totalFees

        // Monthly payment
        val monthlyRepayment = totalRepayment / termMonths

        // Daily burden
        val dailyBurden = totalRepayment / termDays

        // Effective APR calculation
        val effectiveApr = calculateEffectiveApr(principal, totalRepayment, termDays)

        // Penalty estimate (if they pay late)
        val penaltyEstimate = if (product.penaltyFeePct > 0) {
            principal * product.penaltyFeePct * (termMonths * 0.5) // Assume 50% chance of late payment
        } else 0.0

        // Worst-case total (with penalties)
        val worstCaseTotal = totalRepayment + penaltyEstimate

        return mapOf(
            "principal" to principal,
            "interest_charged" to totalInterest,
            "processing_fee" to processingFee,
            "insurance_fee" to insuranceFee,
            "total_fees" to totalFees,
            "total_repayment" to totalRepayment,
            "worst_case_total" to worstCaseTotal,
            "effective_apr" to effectiveApr,
            "monthly_repayment" to monthlyRepayment,
            "daily_burden" to dailyBurden,
            "term_days" to termDays,
            "term_months" to termMonths,
            "penalty_estimate" to penaltyEstimate
        )
    }

    /**
     * Calculate effective APR including all fees.
     * For short-term loans, annualize the total cost percentage.
     * For long-term loans, use standard APR calculation.
     */
    private fun calculateEffectiveApr(principal: Double, totalRepayment: Double, termDays: Int): Double {
        val totalCostPct = (totalRepayment - principal) / principal
        return if (termDays <= 30) {
            // Short-term: annualize
            totalCostPct * (365.0 / termDays)
        } else if (termDays <= 365) {
            // Medium-term: annualize proportionally
            totalCostPct * (365.0 / termDays)
        } else {
            // Long-term: direct annual rate
            totalCostPct * (365.0 / termDays)
        }
    }

    // ──────────────────────────────────────────────
    // FORMATTING HELPERS
    // ──────────────────────────────────────────────

    private fun formatKes(amount: Double): String {
        return if (amount >= 1000) {
            "%,.0f".format(amount)
        } else {
            "%.0f".format(amount)
        }
    }

    private fun formatKesShort(amount: Double?): String {
        if (amount == null) return "—"
        return when {
            amount >= 1_000_000 -> "${"%.1f".format(amount / 1_000_000)}M"
            amount >= 1_000 -> "${"%.0f".format(amount / 1_000)}K"
            else -> "%.0f".format(amount)
        }
    }

    private fun formatRate(rate: Double?): String {
        if (rate == null) return "—"
        return "${"%.1f".format(rate * 100)}%"
    }

    private fun formatFee(product: LenderProduct?): String {
        if (product == null) return "—"
        return when {
            product.processingFeeFixed > 0 -> "KES ${"%.0f".format(product.processingFeeFixed)}"
            product.processingFeePct > 0 -> "${"%.1f".format(product.processingFeePct * 100)}%"
            else -> "Hakuna"
        }
    }

    private fun formatApr(aprPct: Double): String {
        return when {
            aprPct < 1.0 -> "${"%.1f".format(aprPct * 100)}%"
            aprPct < 100 -> "${"%.0f".format(aprPct)}%"
            else -> "${"%.0f".format(aprPct)}%"
        }
    }

    private fun formatAprShort(apr: Double?): String {
        if (apr == null) return "—"
        val aprPct = apr * 100
        return when {
            aprPct < 100 -> "${"%.0f".format(aprPct)}%"
            else -> "${"%.0f".format(aprPct)}%"
        }
    }

    private fun formatPct(pct: Double): String {
        return "${"%.1f".format(pct)}%"
    }

    private fun padRight(text: String?, width: Int): String {
        val t = text ?: "—"
        return if (t.length >= width) t.substring(0, width) else t.padEnd(width)
    }

    private fun purposeLabel(purpose: String): String {
        return when (purpose) {
            "stock" -> "Kununua bidhaa"
            "emergency" -> "Dharura"
            "school_fees" -> "Ada ya shule"
            "equipment" -> "Vifaa"
            "bike_repair" -> "Kukarabati pikipiki"
            else -> "Nyingine"
        }
    }

    private fun urgencyLabel(urgency: String): String {
        return when (urgency) {
            "now" -> "Sasa hivi"
            "this_week" -> "Wiki hii"
            "this_month" -> "Mwezi huu"
            else -> urgency
        }
    }

    /**
     * Estimate what the savings could buy (for relatable context).
     */
    private fun estimateEquivalent(amount: Double): String {
        return when {
            amount >= 10_000 -> "wiki 2-3 za mboga"
            amount >= 5_000 -> "wiki 1-2 za mboga"
            amount >= 2_000 -> "siku 3-5 za mboga"
            amount >= 1_000 -> "siku 2 za mboga"
            amount >= 500 -> "chakula cha mchana"
            else -> "sukuma wiki 2"
        }
    }

    /**
     * Error function approximation for normal distribution CDF.
     */
    private fun erf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * abs(x))
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x)
        return if (x >= 0) y else -y
    }
}
