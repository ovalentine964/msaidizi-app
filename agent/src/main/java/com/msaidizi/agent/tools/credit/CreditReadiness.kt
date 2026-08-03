package com.msaidizi.agent.tools.credit
import com.msaidizi.agent.tools.financial.CFOEngine

import com.msaidizi.core.database.DailySummaryDao
import com.msaidizi.core.database.DebtDao
import com.msaidizi.core.database.ExpenseDao
import com.msaidizi.core.database.SaleDao
import com.msaidizi.agent.memory.MemoryManager
import com.msaidizi.agent.guardrails.SensitiveActionGuard
import com.msaidizi.agent.guardrails.SensitiveActionType
import com.msaidizi.agent.guardrails.ConfirmationResult
import com.msaidizi.agent.guardrails.ConfirmationStatus
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln
import com.msaidizi.agent.tools.core.*

/**
 * CreditReadiness — Eliminates information asymmetry for informal workers.
 *
 * Workers don't know if they qualify for credit, how much, or at what rate.
 * This tool tells them upfront: "You qualify for KES X at Y% APR" — before
 * they waste time applying and getting rejected (which lowers their score).
 *
 * Features:
 *  1. check  — Full credit readiness assessment against all lender products
 *  2. simulate — "If you save KES X for Y weeks → score hits Z → unlocks loan"
 *  3. compare — Side-by-side comparison of all eligible lenders with true APR
 *  4. improve — Personalized actions to raise Alama Score and unlock better products
 *  5. history — Past assessments, goals, and eligibility change timeline
 *
 * Integrates with AlamaScore + CFOEngine for data-driven, personalized output.
 * Voice-first, Swahili-native, actionable in 30 seconds.
 */
@Singleton
class CreditReadiness @Inject constructor(
    private val alamaScore: AlamaScore,
    private val cfoEngine: CFOEngine,
    private val saleDao: SaleDao,
    private val expenseDao: ExpenseDao,
    private val dailySummaryDao: DailySummaryDao,
    private val debtDao: DebtDao,
    private val memoryManager: MemoryManager,
    private val sensitiveActionGuard: SensitiveActionGuard
) : Tool {

    override val name = "credit_readiness"
    override val description = "Check loan eligibility, simulate improvements, compare lenders (banks, SACCOs, MFIs, digital). Voice: 'Naweza pata mkopo?' or 'Nikopeshe pesa ngapi?'"

    override val argsSchema = argSchema {
        enum(
            "action",
            "Credit readiness action to perform",
            listOf("check", "simulate", "compare", "improve", "history", "debt_trap_check"),
            required = false
        )
        number("target_amount", "Target loan amount in KES (for simulate/compare)", required = false)
        integer("save_weekly", "Weekly savings amount in KES (for simulate)", required = false)
        integer("save_weeks", "Number of weeks to save (for simulate)", required = false)
        integer("reduce_fuliza_weekly", "Target max Fuliza uses per week (for simulate)", required = false)
        string("voice_input", "Raw Swahili voice text to parse (e.g. 'Naweza pata mkopo?')", required = false)
    }

    // ── Academic Formula Methods (STA 341, ECO 414) ────────────────────────

    /**
     * Bayesian updating with a Beta-Binomial model.
     * Given a Beta(α, β) prior and observed (successes, failures),
     * returns the posterior parameters Beta(α+s, β+f).
     *
     * The posterior mean (expected repayment probability) is:
     *   E[θ] = (α + s) / (α + β + s + f)
     *
     * @param priorAlpha α parameter of the Beta prior (prior "successes")
     * @param priorBeta β parameter of the Beta prior (prior "failures")
     * @param successes Observed number of successful repayments
     * @param failures Observed number of defaults/ failures
     * @return Pair of (posteriorAlpha, posteriorBeta)
     */
    fun bayesianUpdate(
        priorAlpha: Double,
        priorBeta: Double,
        successes: Int,
        failures: Int
    ): Pair<Double, Double> {
        require(priorAlpha > 0 && priorBeta > 0) { "Prior parameters must be positive" }
        require(successes >= 0 && failures >= 0) { "Observations must be non-negative" }
        val posteriorAlpha = priorAlpha + successes
        val posteriorBeta = priorBeta + failures
        return Pair(posteriorAlpha, posteriorBeta)
    }

    /**
     * Logistic regression default probability.
     * P(default) = 1 / (1 + e^(-Xβ))
     * where Xβ = intercept + Σ(feature_i × coefficient_i)
     *
     * @param features Vector of feature values (e.g. debt-to-income, months in business)
     * @param coefficients Corresponding coefficient vector (same length as features)
     * @param intercept Logistic regression intercept (β₀), defaults to 0.0
     * @return Probability of default ∈ (0, 1)
     */
    fun defaultProbability(
        features: List<Double>,
        coefficients: List<Double>,
        intercept: Double = 0.0
    ): Double {
        require(features.size == coefficients.size) { "Features and coefficients must have same length" }
        val linearCombination = intercept +
            features.zip(coefficients).sumOf { (x, beta) -> x * beta }
        return 1.0 / (1.0 + exp(-linearCombination))
    }

    // ──────────────────────────────────────────────
    // LENDER PRODUCT CATALOG
    // ──────────────────────────────────────────────

    /**
     * Comprehensive catalog of Kenyan lender products for informal workers.
     * Includes true APR calculations with all hidden fees.
     */
    private data class LenderProduct(
        val id: String,
        val lender: String,
        val product: String,
        val type: LenderType,
        val minAmount: Double,
        val maxAmount: Double,
        val monthlyRate: Double,           // Displayed monthly rate
        val processingFeePct: Double = 0.0, // % of principal
        val processingFeeFixed: Double = 0.0, // Fixed KES amount
        val insuranceFeePct: Double = 0.0,
        val penaltyFeePct: Double = 0.0,   // Late payment penalty % per month
        val effectiveApr: Double,          // TRUE annual rate including all fees
        val maxTermDays: Int,
        val minAlamaScore: Int,
        val minTimeInBusinessMonths: Int = 0,
        val minMonthlyRevenue: Double = 0.0,
        val disbursement: String,          // "instant", "24h", "1 week"
        val repayment: String,             // "monthly", "weekly", "daily", "lump_sum"
        val requiresGuarantor: Boolean = false,
        val requiresCollateral: Boolean = false,
        val swahiliName: String,
        val description: String
    )

    private enum class LenderType { MOBILE_LOAN, SACCO, BANK_SME, MFI, HIRE_PURCHASE, OVERDRAFT }

    /**
     * Build the lender product catalog.
     * Rates and fees reflect real Kenyan market as of 2026.
     * True APR is calculated including all hidden fees.
     */
    private fun buildLenderCatalog(): List<LenderProduct> = listOf(
        // ── Digital/Mobile Lenders ──
        LenderProduct(
            id = "mshwari", lender = "M-Shwari (Safaricom/NCBA)", product = "M-Shwari Loan",
            type = LenderType.MOBILE_LOAN,
            minAmount = 100.0, maxAmount = 50_000.0,
            monthlyRate = 0.075, // 7.5%/month
            effectiveApr = 0.90, // 90% APR
            maxTermDays = 30, minAlamaScore = 300,
            disbursement = "instant", repayment = "monthly",
            swahiliName = "M-Shwari",
            description = "Instant mobile loan via M-Pesa. No paperwork."
        ),
        LenderProduct(
            id = "kcb_mpesa", lender = "KCB M-Pesa", product = "KCB M-Pesa Loan",
            type = LenderType.MOBILE_LOAN,
            minAmount = 100.0, maxAmount = 100_000.0,
            monthlyRate = 0.06, // 6%/month displayed
            processingFeePct = 0.0, processingFeeFixed = 500.0, // KES 500 flat fee
            effectiveApr = 1.32, // 132% effective APR due to fee on short-term
            maxTermDays = 30, minAlamaScore = 350,
            disbursement = "instant", repayment = "monthly",
            swahiliName = "KCB M-Pesa",
            description = "KCB loan via M-Pesa. Shows 6%/month but KES 500 fee makes it expensive."
        ),
        LenderProduct(
            id = "fuliza", lender = "Safaricom/Fuliza", product = "Fuliza M-Pesa",
            type = LenderType.OVERDRAFT,
            minAmount = 1.0, maxAmount = 70_000.0,
            monthlyRate = 0.0, // Charged daily
            processingFeePct = 0.0, processingFeeFixed = 0.0,
            effectiveApr = 1.095, // 1% daily = ~365% APR (but capped)
            maxTermDays = 30, minAlamaScore = 300,
            disbursement = "instant", repayment = "auto_deduct",
            swahiliName = "Fuliza",
            description = "Overdraft on M-Pesa. Very expensive — 1% daily. Use only for emergencies."
        ),
        LenderProduct(
            id = "tala", lender = "Tala", product = "Tala Loan",
            type = LenderType.MOBILE_LOAN,
            minAmount = 500.0, maxAmount = 50_000.0,
            monthlyRate = 0.15, // 15%/month
            effectiveApr = 1.80, // 180% APR
            maxTermDays = 60, minAlamaScore = 350,
            disbursement = "instant", repayment = "monthly",
            swahiliName = "Tala",
            description = "App-based mobile loan. Quick but expensive."
        ),
        LenderProduct(
            id = "branch", lender = "Branch", product = "Branch Loan",
            type = LenderType.MOBILE_LOAN,
            minAmount = 250.0, maxAmount = 100_000.0,
            monthlyRate = 0.14, // 14%/month
            effectiveApr = 1.68, // 168% APR
            maxTermDays = 60, minAlamaScore = 350,
            disbursement = "instant", repayment = "monthly",
            swahiliName = "Branch",
            description = "App-based loan. Competitive with Tala."
        ),

        // ── SACCOs ──
        LenderProduct(
            id = "sacco_standard", lender = "SACCO (Typical)", product = "SACCO Development Loan",
            type = LenderType.SACCO,
            minAmount = 5_000.0, maxAmount = 500_000.0,
            monthlyRate = 0.0125, // 1.25%/month = 15% APR
            processingFeePct = 0.01, // 1% processing
            effectiveApr = 0.15, // 15% APR
            maxTermDays = 365, minAlamaScore = 550,
            minTimeInBusinessMonths = 6,
            disbursement = "1 week", repayment = "monthly",
            requiresGuarantor = true,
            swahiliName = "SACCO",
            description = "Best rates for workers. Requires 6-month membership and guarantor."
        ),
        LenderProduct(
            id = "sacco_emergency", lender = "SACCO (Typical)", product = "SACCO Emergency Loan",
            type = LenderType.SACCO,
            minAmount = 2_000.0, maxAmount = 50_000.0,
            monthlyRate = 0.0167, // 1.67%/month = 20% APR
            processingFeePct = 0.01, effectiveApr = 0.20,
            maxTermDays = 180, minAlamaScore = 500,
            minTimeInBusinessMonths = 3,
            disbursement = "3 days", repayment = "monthly",
            swahiliName = "SACCO - Dharura",
            description = "Emergency SACCO loan. Faster but slightly higher rate."
        ),

        // ── Banks ──
        LenderProduct(
            id = "bank_sme", lender = "Commercial Bank (Typical)", product = "SME Loan",
            type = LenderType.BANK_SME,
            minAmount = 50_000.0, maxAmount = 5_000_000.0,
            monthlyRate = 0.015, // 1.5%/month = 18% APR
            processingFeePct = 0.02, insuranceFeePct = 0.01,
            effectiveApr = 0.22, // ~22% with fees
            maxTermDays = 730, minAlamaScore = 700,
            minTimeInBusinessMonths = 12, minMonthlyRevenue = 100_000.0,
            disbursement = "2 weeks", repayment = "monthly",
            requiresCollateral = true,
            swahiliName = "Benki - Mkopo wa Biashara",
            description = "Bank SME loan. Best rates but high requirements."
        ),
        LenderProduct(
            id = "bank_personal", lender = "Commercial Bank (Typical)", product = "Personal Loan",
            type = LenderType.BANK_SME,
            minAmount = 10_000.0, maxAmount = 1_000_000.0,
            monthlyRate = 0.02, // 2%/month = 24% APR
            processingFeePct = 0.03, insuranceFeePct = 0.015,
            effectiveApr = 0.30, // ~30% with fees
            maxTermDays = 365, minAlamaScore = 650,
            minTimeInBusinessMonths = 6, minMonthlyRevenue = 30_000.0,
            disbursement = "1 week", repayment = "monthly",
            swahiliName = "Benki - Mkopo wa Kibinafsi",
            description = "Bank personal loan. Easier than SME loan."
        ),

        // ── MFIs ──
        LenderProduct(
            id = "mfi_juhudi", lender = "MFI (Typical)", product = "Biashara Loan",
            type = LenderType.MFI,
            minAmount = 5_000.0, maxAmount = 200_000.0,
            monthlyRate = 0.025, // 2.5%/month = 30% APR
            processingFeePct = 0.03, effectiveApr = 0.36,
            maxTermDays = 365, minAlamaScore = 450,
            minTimeInBusinessMonths = 3,
            disbursement = "3 days", repayment = "weekly",
            swahiliName = "MFI - Mkopo wa Biashara",
            description = "Microfinance loan. Good for growing businesses."
        ),
        LenderProduct(
            id = "mfi_group", lender = "MFI (Typical)", product = "Group Loan",
            type = LenderType.MFI,
            minAmount = 3_000.0, maxAmount = 100_000.0,
            monthlyRate = 0.02, // 2%/month = 24% APR
            processingFeePct = 0.02, effectiveApr = 0.28,
            maxTermDays = 270, minAlamaScore = 400,
            disbursement = "1 week", repayment = "weekly",
            requiresGuarantor = true,
            swahiliName = "MFI - Mkopo wa Kikundi",
            description = "Group lending. Lower rates through peer guarantee."
        ),

        // ── Chama ──
        LenderProduct(
            id = "chama_loan", lender = "Chama", product = "Chama Loan",
            type = LenderType.SACCO,
            minAmount = 2_000.0, maxAmount = 50_000.0,
            monthlyRate = 0.05, // 5%/month
            effectiveApr = 0.60, // 60% APR
            maxTermDays = 90, minAlamaScore = 400,
            disbursement = "next meeting", repayment = "monthly",
            swahiliName = "Chama",
            description = "Loan from your chama (savings group). Flexible terms."
        ),

        // ── Hire Purchase ──
        LenderProduct(
            id = "watu_credit", lender = "Watu Credit", product = "Asset Finance",
            type = LenderType.HIRE_PURCHASE,
            minAmount = 20_000.0, maxAmount = 500_000.0,
            monthlyRate = 0.05, // 5%/month = 60% APR but presented as daily
            effectiveApr = 0.80, // ~80% effective
            maxTermDays = 365, minAlamaScore = 450,
            disbursement = "24h", repayment = "daily",
            swahiliName = "Watu Credit",
            description = "Hire purchase for bikes, equipment. Daily payments sound small but cost is high."
        ),
        LenderProduct(
            id = "mogo", lender = "Mogo", product = "Boda Boda Finance",
            type = LenderType.HIRE_PURCHASE,
            minAmount = 30_000.0, maxAmount = 300_000.0,
            monthlyRate = 0.04, effectiveApr = 0.65,
            maxTermDays = 540, minAlamaScore = 400,
            disbursement = "24h", repayment = "daily",
            swahiliName = "Mogo",
            description = "Motorcycle/boda boda financing. GPS tracker installed."
        )
    )

    // ──────────────────────────────────────────────
    // EXECUTION DISPATCH
    // ──────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        // Voice input parsing
        if (!params["voice_input"].isNullOrBlank()) {
            return parseVoiceInput(params["voice_input"]!!)
        }

        val action = params["action"] ?: "check"
        return when (action.lowercase()) {
            "check" -> checkReadiness(params)
            "simulate" -> simulateImprovement(params)
            "compare" -> compareOffers(params)
            "improve" -> showImprovements(params)
            "history" -> showHistory(params)
            "debt_trap_check" -> detectDebtTrap(params)
            else -> ToolResult.error(
                name,
                "Unknown action: $action. Use: check, simulate, compare, improve, history, debt_trap_check",
                "INVALID_ACTION"
            )
        }
    }

    // ──────────────────────────────────────────────
    // 1. CHECK READINESS — Full credit assessment
    // ──────────────────────────────────────────────

    /**
     * Run full credit readiness assessment.
     * Pulls Alama Score, CFOEngine data, debt load, and matches against all lender products.
     */
    private suspend fun checkReadiness(params: Map<String, String>): ToolResult {
        return try {
            val scoreResult = alamaScore.calculateScore()
            val score = scoreResult.score
            val level = scoreResult.level

            // Pull business financial data from CFOEngine
            val summaries = dailySummaryDao.getRecentSummaries(30).first()
            val avgMonthlyRevenue = if (summaries.isNotEmpty()) {
                summaries.map { it.totalSales }.average() * 30
            } else 0.0
            val avgMonthlyExpenses = if (summaries.isNotEmpty()) {
                summaries.map { it.totalExpenses }.average() * 30
            } else 0.0
            val avgDailyRevenue = if (summaries.isNotEmpty()) {
                summaries.map { it.totalSales }.average()
            } else 0.0

            // Pull debt data
            val totalDebt = debtDao.getTotalOutstanding().first() ?: 0.0
            val debtToIncome = if (avgMonthlyRevenue > 0) (totalDebt / avgMonthlyRevenue * 100) else 0.0

            // Estimate time in business from data
            val allSummaries = dailySummaryDao.getRecentSummaries(365).first()
            val timeInBusinessMonths = if (allSummaries.isNotEmpty()) {
                val daysOfData = allSummaries.size
                (daysOfData / 30.0).toInt().coerceAtLeast(1)
            } else 0

            // Determine business type from memory or defaults
            val businessType = memoryManager.retrieve("business_type").ifBlank { "informal_trader" }
            val location = memoryManager.retrieve("location").ifBlank { "Kenya" }

            // Match against lender catalog
            val catalog = buildLenderCatalog()
            val eligible = mutableListOf<Map<String, Any>>()
            val ineligible = mutableListOf<Map<String, Any>>()

            for (product in catalog) {
                val canQualify = score >= product.minAlamaScore &&
                        timeInBusinessMonths >= product.minTimeInBusinessMonths &&
                        avgMonthlyRevenue >= product.minMonthlyRevenue

                if (canQualify) {
                    val maxForWorker = calculateMaxLoan(product, avgMonthlyRevenue, totalDebt)
                    eligible.add(
                        mapOf(
                            "id" to product.id,
                            "lender" to product.lender,
                            "product" to product.product,
                            "type" to product.type.name,
                            "max_amount" to maxForWorker,
                            "monthly_rate" to product.monthlyRate,
                            "effective_apr" to product.effectiveApr,
                            "disbursement" to product.disbursement,
                            "repayment" to product.repayment,
                            "requires_guarantor" to product.requiresGuarantor,
                            "requires_collateral" to product.requiresCollateral,
                            "swahili_name" to product.swahiliName
                        )
                    )
                } else {
                    val reasons = mutableListOf<String>()
                    if (score < product.minAlamaScore) reasons.add("Alama Score inatosha: unahitaji ${product.minAlamaScore}, una $score")
                    if (timeInBusinessMonths < product.minTimeInBusinessMonths) reasons.add("Muda wa biashara: unahitaji miezi ${product.minTimeInBusinessMonths}, una miezi $timeInBusinessMonths")
                    if (avgMonthlyRevenue < product.minMonthlyRevenue) reasons.add("Mapato ya mwezi: unahitaji KES ${"%,.0f".format(product.minMonthlyRevenue)}, una KES ${"%,.0f".format(avgMonthlyRevenue)}")

                    ineligible.add(
                        mapOf(
                            "id" to product.id,
                            "lender" to product.lender,
                            "product" to product.product,
                            "reason" to reasons.joinToString("; "),
                            "swahili_name" to product.swahiliName
                        )
                    )
                }
            }

            // Calculate percentile rank
            val percentile = calculatePercentile(score, businessType)

            // Risk flags
            val riskFlags = mutableListOf<String>()
            if (debtToIncome > 20) riskFlags.add("high_debt_ratio")
            if (debtToIncome > 30) riskFlags.add("critical_debt_ratio")

            // Build improvement path
            val improvementPath = buildImprovementPath(score, totalDebt, avgMonthlyRevenue, timeInBusinessMonths)

            // Build response
            val message = buildString {
                appendLine("📋 CREDIT READINESS REPORT")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("🎯 Alama Score: $score/850 ($level)")
                appendLine("📊 Wewe uko katika ${percentileLabel(percentile)} ya $businessType")
                appendLine()

                // Eligible products
                appendLine("💰 UNAWEZA PATA LEO:")
                appendLine()
                if (eligible.isEmpty()) {
                    appendLine("   ⚠️ Hakuna mkopo unaoweza pata sasa hivi.")
                    appendLine("   Ongeza Alama Score yako kwa hatua chini.")
                } else {
                    // Group by type for readability
                    val grouped = eligible.groupBy { it["type"] }
                    for ((type, products) in grouped) {
                        appendLine("   ${typeEmoji(type)} ${typeLabel(type)}:")
                        for (p in products) {
                            val maxAmt = p["max_amount"] as Double
                            val apr = (p["effective_apr"] as Double * 100)
                            val disb = p["disbursement"] as String
                            val rate = (p["monthly_rate"] as Double * 100)
                            appendLine("   ✅ ${p["swahili_name"]}: mpaka KES ${"%,.0f".format(maxAmt)} @ ${"%.1f".format(rate)}%/mwezi (${formatApr(apr)} APR) — $disb")
                        }
                        appendLine()
                    }
                }

                // Ineligible products (brief)
                if (ineligible.isNotEmpty()) {
                    appendLine("❌ HAUJATIMIZA VIGEZO:")
                    for (p in ineligible) {
                        appendLine("   ❌ ${p["swahili_name"]}: ${p["reason"]}")
                    }
                    appendLine()
                }

                // Risk warnings
                if (riskFlags.contains("critical_debt_ratio")) {
                    appendLine("🚨 ONYO: Deni lako ni ${"%.0f".format(debtToIncome)}% ya mapato yako!")
                    appendLine("   Kiwango kinachopendekezwa: chini ya 20%. Lipa deni KES ${"%,.0f".format(totalDebt - avgMonthlyRevenue * 0.2)} kufungua mikopo bora.")
                    appendLine()
                } else if (riskFlags.contains("high_debt_ratio")) {
                    appendLine("⚠️ Deni lako ni ${"%.0f".format(debtToIncome)}% ya mapato yako.")
                    appendLine("   Pendekezo: chini ya 20%. Lipa deni kidogo kufungua mikopo bora.")
                    appendLine()
                }

                // Top improvement action
                if (improvementPath.isNotEmpty()) {
                    val top = improvementPath.first()
                    appendLine("📈 HATUA YA KWANZA YA KUBORESHA:")
                    appendLine("   ${top["action"]}")
                    appendLine("   → Alama Score itaongezeka +${top["score_gain"]} → Fungua: ${top["unlocks"]}")
                }
            }

            // Save to memory for history tracking
            memoryManager.storeMemory(
                "last_credit_assessment",
                "score=$score,eligible=${eligible.size},ineligible=${ineligible.size},dti=${"%.1f".format(debtToIncome)}",
                "credit"
            )

            // ── HUMAN-IN-THE-LOOP: Credit Decision Approval ──
            // When Alama Score suggests loan eligibility, require human confirmation
            // before any credit application can proceed.
            val topEligible = eligible.maxByOrNull { it["max_amount"] as Double }
            if (topEligible != null) {
                val maxAmount = topEligible["max_amount"] as Double
                val lenderName = topEligible["swahili_name"] as String
                val confirmation = sensitiveActionGuard.requiresConfirmation(
                    actionType = SensitiveActionType.CREDIT_DECISION,
                    amount = maxAmount,
                    description = "Unaweza kupata mkopo wa KES ${"%,.0f".format(maxAmount)} kutoka $lenderName. Alama Score: $score ($level)",
                    metadata = mapOf(
                        "alama_score" to score.toString(),
                        "lender" to lenderName,
                        "max_amount" to maxAmount.toString(),
                        "eligible_count" to eligible.size.toString()
                    )
                )
                if (confirmation != null) {
                    // Return the credit assessment WITH the confirmation prompt
                    // The harness will present the confirmation to the user
                    return ToolResult.success(
                        toolName = name,
                        data = mapOf(
                            "score" to score,
                            "level" to level,
                            "percentile" to percentile,
                            "eligible_products" to eligible,
                            "ineligible_products" to ineligible,
                            "debt_to_income_pct" to debtToIncome,
                            "risk_flags" to riskFlags,
                            "improvement_path" to improvementPath,
                            "avg_monthly_revenue" to avgMonthlyRevenue,
                            "avg_monthly_expenses" to avgMonthlyExpenses,
                            "confirmation_required" to true,
                            "confirmation_id" to confirmation.confirmationId,
                            "confirmation_prompt" to confirmation.prompt
                        ),
                        message = message + "\n\n" + confirmation.prompt
                    )
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "score" to score,
                    "level" to level,
                    "percentile" to percentile,
                    "eligible_products" to eligible,
                    "ineligible_products" to ineligible,
                    "debt_to_income_pct" to debtToIncome,
                    "risk_flags" to riskFlags,
                    "improvement_path" to improvementPath,
                    "avg_monthly_revenue" to avgMonthlyRevenue,
                    "avg_monthly_expenses" to avgMonthlyExpenses
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to check credit readiness")
            ToolResult.error(name, "Failed to check readiness: ${e.message}", "CALCULATION_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 2. SIMULATE IMPROVEMENT — What-if scenarios
    // ──────────────────────────────────────────────

    /**
     * "If you save KES X/week for Y weeks → score hits Z → unlocks SACCO loan at 15% APR"
     * Shows the concrete path from current state to better credit.
     */
    private suspend fun simulateImprovement(params: Map<String, String>): ToolResult {
        return try {
            val targetAmount = params["target_amount"]?.toDoubleOrNull()
            val saveWeekly = params["save_weekly"]?.toIntOrNull() ?: 200
            val saveWeeks = params["save_weeks"]?.toIntOrNull() ?: 12
            val reduceFuliza = params["reduce_fuliza_weekly"]?.toIntOrNull()

            val scoreResult = alamaScore.calculateScore()
            val currentScore = scoreResult.score

            val summaries = dailySummaryDao.getRecentSummaries(30).first()
            val avgMonthlyRevenue = if (summaries.isNotEmpty()) {
                summaries.map { it.totalSales }.average() * 30
            } else 0.0
            val totalDebt = debtDao.getTotalOutstanding().first() ?: 0.0

            // Calculate score improvement from savings
            val savingsScoreGain = calculateSavingsScoreGain(saveWeekly, saveWeeks)

            // Calculate score improvement from reducing Fuliza
            val fulizaScoreGain = if (reduceFuliza != null) calculateFulizaScoreGain(reduceFuliza) else 0

            // Simulated future score
            val simulatedScore = (currentScore + savingsScoreGain + fulizaScoreGain).coerceIn(300, 850)

            // What products unlock at the new score?
            val catalog = buildLenderCatalog()
            val currentlyEligible = catalog.filter { currentScore >= it.minAlamaScore }
            val willBeEligible = catalog.filter { simulatedScore >= it.minAlamaScore }
            val newlyUnlocked = willBeEligible.filter { new ->
                currentlyEligible.none { it.id == new.id }
            }

            // Calculate savings accumulated
            val totalSaved = saveWeekly.toLong() * saveWeeks

            // What can they get with the new score?
            val bestNewOffers = newlyUnlocked.map { product ->
                val maxLoan = calculateMaxLoan(product, avgMonthlyRevenue, totalDebt)
                mapOf(
                    "lender" to product.swahiliName,
                    "product" to product.product,
                    "max_amount" to maxLoan,
                    "apr" to product.effectiveApr,
                    "disbursement" to product.disbursement
                )
            }

            // If target amount specified, show gap analysis
            val targetGap = if (targetAmount != null) {
                val canGetNow = currentlyEligible.maxOfOrNull {
                    calculateMaxLoan(it, avgMonthlyRevenue, totalDebt)
                } ?: 0.0
                val canGetFuture = willBeEligible.maxOfOrNull {
                    calculateMaxLoan(it, avgMonthlyRevenue, totalDebt)
                } ?: 0.0
                mapOf(
                    "target" to targetAmount,
                    "can_get_now" to canGetNow,
                    "can_get_future" to canGetFuture,
                    "gap_now" to (targetAmount - canGetNow).coerceAtLeast(0.0),
                    "gap_future" to (targetAmount - canGetFuture).coerceAtLeast(0.0),
                    "achievable" to (canGetFuture >= targetAmount)
                )
            } else null

            val message = buildString {
                appendLine("📈 SIMULATION YA KUBORESHA ALAMA SCORE")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("📊 Sasa hivi: Alama Score ni $currentScore")
                appendLine()

                // Show what they're doing
                appendLine("🎯 UKIFANYA HAYA:")
                if (saveWeekly > 0 && saveWeeks > 0) {
                    appendLine("   💰 Weka akiba KES ${"%,d".format(saveWeekly)}/wiki kwa wiki $saveWeeks")
                    appendLine("      → Akiba: KES ${"%,d".format(totalSaved)}")
                    appendLine("      → Score: +$savingsScoreGain points")
                }
                if (reduceFuliza != null) {
                    appendLine("   📱 Punguza Fuliza: chini ya $reduceFuliza× kwa wiki")
                    appendLine("      → Score: +$fulizaScoreGain points")
                }
                appendLine()
                appendLine("📊 Alama Score mpya: $simulatedScore (kutoka $currentScore)")
                appendLine()

                // Newly unlocked products
                if (newlyUnlocked.isNotEmpty()) {
                    appendLine("🔓 MIKOPO MIPYA ITAKAYOFUNGUKA:")
                    for (offer in bestNewOffers) {
                        val apr = ((offer["apr"] as Double) * 100)
                        appendLine("   ✅ ${offer["lender"]}: mpaka KES ${"%,.0f".format(offer["max_amount"] as Double)} @ ${formatApr(apr)} APR")
                    }
                    appendLine()

                    // Calculate savings vs current options
                    val currentBest = currentlyEligible.filter { it.type == LenderType.MOBILE_LOAN }
                        .minByOrNull { it.effectiveApr }
                    val futureBest = newlyUnlocked.minByOrNull { it.effectiveApr }
                    if (currentBest != null && futureBest != null) {
                        val loanAmount = 10_000.0 // Example: KES 10,000 loan
                        val currentCost = loanAmount * currentBest.effectiveApr
                        val futureCost = loanAmount * futureBest.effectiveApr
                        val savings = currentCost - futureCost
                        if (savings > 0) {
                            appendLine("💡 UKICHUKUA MKOPO WA KES 10,000:")
                            appendLine("   Sasa: ${currentBest.swahiliName} — gharama: KES ${"%,.0f".format(currentCost)}/mwaka")
                            appendLine("   Baada: ${futureBest.swahiliName} — gharama: KES ${"%,.0f".format(futureCost)}/mwaka")
                            appendLine("   💰 UTAOKOA: KES ${"%,.0f".format(savings)}/mwaka!")
                            appendLine()
                        }
                    }
                } else {
                    appendLine("   Hakuna mikopo mipya itakayofunguka na hatua hizi.")
                    appendLine("   Jaribu kuongeza akiba zaidi au kuboresha biashara yako.")
                    appendLine()
                }

                // Target gap analysis
                if (targetGap != null) {
                    val target = targetGap["target"] as Double
                    val achievable = targetGap["achievable"] as Boolean
                    appendLine("🎯 lengo lako: KES ${"%,.0f".format(target)}")
                    if (achievable) {
                        appendLine("   ✅ UTAFIKA! Baada ya wiki $saveWeeks, utaweza pata KES ${"%,.0f".format(targetGap["can_get_future"] as Double)}")
                    } else {
                        val gap = targetGap["gap_future"] as Double
                        appendLine("   ⚠️ Bado kuna pengo la KES ${"%,.0f".format(gap)}. Ongeza wiki za akiba au pata mkopo wa pamoja.")
                    }
                    appendLine()
                }

                // Timeline
                appendLine("📅 RATIBA:")
                appendLine("   Wiki 1-${saveWeeks}: Weka akiba KES ${"%,d".format(saveWeekly)}/wiki")
                if (reduceFuliza != null) {
                    appendLine("   Wiki 1-${saveWeeks}: Punguza Fuliza chini ya $reduceFuliza×/wiki")
                }
                appendLine("   Wiki $saveWeeks: Alama Score itafika $simulatedScore")
                appendLine("   Wiki ${saveWeeks + 1}: Omba mkopo mpya!")
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "current_score" to currentScore,
                    "simulated_score" to simulatedScore,
                    "savings_score_gain" to savingsScoreGain,
                    "fuliza_score_gain" to fulizaScoreGain,
                    "total_saved" to totalSaved,
                    "newly_unlocked" to bestNewOffers,
                    "target_gap" to targetGap
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to simulate improvement")
            ToolResult.error(name, "Failed to simulate: ${e.message}", "CALCULATION_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 3. COMPARE OFFERS — Side-by-side with true APR
    // ──────────────────────────────────────────────

    /**
     * Compare all eligible lenders side-by-side, showing true APR including hidden fees.
     * Matches repayment schedule to worker's income pattern.
     */
    private suspend fun compareOffers(params: Map<String, String>): ToolResult {
        return try {
            val targetAmount = params["target_amount"]?.toDoubleOrNull() ?: 10_000.0

            val scoreResult = alamaScore.calculateScore()
            val score = scoreResult.score

            val summaries = dailySummaryDao.getRecentSummaries(30).first()
            val avgMonthlyRevenue = if (summaries.isNotEmpty()) {
                summaries.map { it.totalSales }.average() * 30
            } else 0.0
            val avgDailyRevenue = if (summaries.isNotEmpty()) {
                summaries.map { it.totalSales }.average()
            } else 0.0
            val totalDebt = debtDao.getTotalOutstanding().first() ?: 0.0

            val catalog = buildLenderCatalog()

            // Find products that can offer at least the target amount
            val eligible = catalog.filter { product ->
                val maxLoan = calculateMaxLoan(product, avgMonthlyRevenue, totalDebt)
                score >= product.minAlamaScore &&
                        maxLoan >= targetAmount &&
                        avgMonthlyRevenue >= product.minMonthlyRevenue
            }

            if (eligible.isEmpty()) {
                return ToolResult.success(
                    toolName = name,
                    message = buildString {
                        appendLine("⚠️ Hakuna mkopo unaoweza kukopesha KES ${"%,.0f".format(targetAmount)} sasa hivi.")
                        appendLine()
                        appendLine("Alama Score yako: $score")
                        appendLine("Jaribu:")
                        appendLine("• Omba kiasi kidogo (angalia 'check' kuona unachoweza pata)")
                        appendLine("• Boresha Alama Score (angalia 'simulate')")
                    }
                )
            }

            // Calculate true cost for each product
            val comparisons = eligible.map { product ->
                val trueCost = calculateTrueCost(targetAmount, product)
                val monthlyRepayment = trueCost["monthly_repayment"] as Double
                val dailyBurden = trueCost["daily_burden"] as Double
                val incomeImpactPct = if (avgDailyRevenue > 0) (dailyBurden / avgDailyRevenue * 100) else 0.0

                mapOf(
                    "product" to product,
                    "true_cost" to trueCost,
                    "income_impact_pct" to incomeImpactPct
                )
            }.sortedBy { (it["true_cost"] as Map<*, *>)["effective_apr"] as Double }

            // Find best overall, cheapest, fastest
            val cheapest = comparisons.minByOrNull {
                (it["true_cost"] as Map<*, *>)["total_repayment"] as Double
            }
            val fastest = comparisons.minByOrNull {
                (it["product"] as LenderProduct).disbursement.let { d ->
                    when (d) {
                        "instant" -> 0
                        "24h" -> 1
                        "3 days" -> 3
                        "1 week" -> 7
                        else -> 14
                    }
                }
            }

            // Score each product
            data class ProductScore(
                val comparison: Map<String, Any>,
                val speedScore: Int,
                val costScore: Int,
                val safetyScore: Int,
                val totalScore: Int
            )

            val scored = comparisons.map { comp ->
                val product = comp["product"] as LenderProduct
                val trueCost = comp["true_cost"] as Map<String, Any>
                val apr = trueCost["effective_apr"] as Double

                val speedScore = when (product.disbursement) {
                    "instant" -> 5
                    "24h" -> 4
                    "3 days" -> 3
                    "1 week" -> 2
                    else -> 1
                }
                val costScore = when {
                    apr <= 0.20 -> 5
                    apr <= 0.40 -> 4
                    apr <= 0.70 -> 3
                    apr <= 1.00 -> 2
                    else -> 1
                }
                val safetyScore = when (product.type) {
                    LenderType.SACCO -> 5
                    LenderType.BANK_SME -> 5
                    LenderType.MFI -> 4
                    LenderType.MOBILE_LOAN -> 3
                    LenderType.HIRE_PURCHASE -> 2
                    LenderType.OVERDRAFT -> 1
                }
                val totalScore = speedScore + costScore + safetyScore

                ProductScore(comp, speedScore, costScore, safetyScore, totalScore)
            }.sortedByDescending { it.totalScore }

            val best = scored.first()

            val message = buildString {
                appendLine("📊 LINGANISHO LA MIKOPO — KES ${"%,.0f".format(targetAmount)}")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                for (ps in scored) {
                    val product = ps.comparison["product"] as LenderProduct
                    val tc = ps.comparison["true_cost"] as Map<String, Any>
                    val apr = (tc["effective_apr"] as Double * 100)
                    val monthlyRate = (product.monthlyRate * 100)
                    val totalRepay = tc["total_repayment"] as Double
                    val processingFee = tc["processing_fee"] as Double
                    val dailyBurden = tc["daily_burden"] as Double
                    val incomeImpact = ps.comparison["income_impact_pct"] as Double
                    val isBest = ps == best

                    if (isBest) appendLine("🏆 ${product.swahiliName} — CHAGUA BORA!")
                    else appendLine("   ${product.swahiliName}")

                    appendLine("   Kiasi: KES ${"%,.0f".format(targetAmount)}")
                    appendLine("   Riba: ${"%.1f".format(monthlyRate)}%/mwezi")
                    if (processingFee > 0) {
                        appendLine("   Ada: KES ${"%.0f".format(processingFee)}")
                    }
                    appendLine("   ⚡ Gharama halisi (APR): ${formatApr(apr)}")
                    appendLine("   💵 Utalipa jumla: KES ${"%,.0f".format(totalRepay)}")
                    appendLine("   ⏱️ Kupokea: ${product.disbursement}")
                    appendLine("   📅 Kulipa: ${product.repayment}")
                    appendLine("   📊 Mzigo wa siku: KES ${"%.0f".format(dailyBurden)} (${formatPct(incomeImpact)} ya mapato)")

                    // Stars
                    appendLine("   ⚡ SPEED: ${"⭐".repeat(ps.speedScore)}${"☆".repeat(5 - ps.speedScore)}")
                    appendLine("   💰 GHARAMA: ${"⭐".repeat(ps.costScore)}${"☆".repeat(5 - ps.costScore)}")
                    appendLine("   🛡️ USAALAMA: ${"⭐".repeat(ps.safetyScore)}${"☆".repeat(5 - ps.safetyScore)}")

                    if (product.requiresGuarantor) appendLine("   ⚠️ Inahitaji mdhamini")
                    if (product.requiresCollateral) appendLine("   ⚠️ Inahitaji dhamana")
                    appendLine()
                }

                // Recommendations
                appendLine("💡 MAPENDEKEZO:")
                if (scored.size > 1) {
                    val cheapestByApr = scored.minByOrNull {
                        ((it.comparison["true_cost"] as Map<*, *>)["effective_apr"]) as Double
                    }
                    if (cheapestByApr != null && cheapestByApr != best) {
                        val cheapProduct = cheapestByApr.comparison["product"] as LenderProduct
                        appendLine("   💰 Nafuu zaidi: ${cheapProduct.swahiliName}")
                    }
                }
                appendLine("   🏆 Bora jumla: ${best.comparison.let { (it["product"] as LenderProduct).swahiliName }}")

                // Warning for predatory rates
                val predatory = scored.filter {
                    ((it.comparison["true_cost"] as Map<*, *>)["effective_apr"] as Double) > 0.50
                }
                if (predatory.isNotEmpty()) {
                    appendLine()
                    appendLine("⚠️ ONYO: Mikopo hii ina riba ya juu sana (>50% APR):")
                    for (p in predatory) {
                        val prod = p.comparison["product"] as LenderProduct
                        val aprVal = ((p.comparison["true_cost"] as Map<*, *>)["effective_apr"] as Double * 100)
                        appendLine("   🔴 ${prod.swahiliName}: ${formatApr(aprVal)} APR")
                    }
                    appendLine("   Tumia kwa dharura tu!")
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "target_amount" to targetAmount,
                    "comparisons" to scored.map { ps ->
                        val product = ps.comparison["product"] as LenderProduct
                        val tc = ps.comparison["true_cost"] as Map<String, Any>
                        mapOf(
                            "lender" to product.lender,
                            "product" to product.product,
                            "swahili_name" to product.swahiliName,
                            "amount" to targetAmount,
                            "monthly_rate" to product.monthlyRate,
                            "effective_apr" to (tc["effective_apr"] as Double),
                            "total_repayment" to (tc["total_repayment"] as Double),
                            "processing_fee" to (tc["processing_fee"] as Double),
                            "daily_burden" to (tc["daily_burden"] as Double),
                            "disbursement" to product.disbursement,
                            "speed_score" to ps.speedScore,
                            "cost_score" to ps.costScore,
                            "safety_score" to ps.safetyScore,
                            "is_best" to (ps == best)
                        )
                    },
                    "recommended" to best.comparison.let { (it["product"] as LenderProduct).swahiliName }
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to compare offers")
            ToolResult.error(name, "Failed to compare: ${e.message}", "CALCULATION_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 4. IMPROVE — Personalized actions to raise score
    // ──────────────────────────────────────────────

    /**
     * Show personalized, prioritized actions to improve Alama Score
     * and unlock better loan products. Each action is specific and measurable.
     */
    private suspend fun showImprovements(params: Map<String, String>): ToolResult {
        return try {
            val scoreResult = alamaScore.calculateScore()
            val score = scoreResult.score
            val factors = scoreResult.factors

            val summaries = dailySummaryDao.getRecentSummaries(30).first()
            val avgDailySales = if (summaries.isNotEmpty()) summaries.map { it.totalSales }.average() else 0.0
            val avgMonthlyRevenue = avgDailySales * 30
            val totalDebt = debtDao.getTotalOutstanding().first() ?: 0.0
            val debtToIncome = if (avgMonthlyRevenue > 0) (totalDebt / avgMonthlyRevenue * 100) else 0.0

            val activeDays = summaries.count { it.totalSales > 0 }
            val consistency = if (summaries.isNotEmpty()) activeDays.toDouble() / summaries.size else 0.0

            val allSummaries = dailySummaryDao.getRecentSummaries(365).first()
            val timeInBusinessMonths = if (allSummaries.isNotEmpty()) (allSummaries.size / 30.0).toInt().coerceAtLeast(1) else 0

            val catalog = buildLenderCatalog()

            // Build prioritized improvement actions
            data class ImprovementAction(
                val priority: Int,
                val action: String,
                val actionSwahili: String,
                val scoreGain: Int,
                val unlocks: String,
                val difficulty: String,
                val timeframe: String
            )

            val actions = mutableListOf<ImprovementAction>()

            // 1. Consistency improvement
            if (consistency < 0.8) {
                val targetDays = 26 // ~6 days/week
                val gain = ((targetDays / 30.0 - consistency) * 150).toInt().coerceIn(5, 30)
                val newScore = (score + gain).coerceIn(300, 850)
                val newProducts = catalog.filter { score < it.minAlamaScore && newScore >= it.minAlamaScore }
                actions.add(
                    ImprovementAction(
                        priority = 1,
                        action = "Record sales every day, even if KES 0",
                        actionSwahili = "Rekodi mauzo kila siku, hata kama ni KES 0. Sasa hivi una siku ${activeDays}/30. Lengo: siku 26/30.",
                        scoreGain = gain,
                        unlocks = if (newProducts.isNotEmpty()) newProducts.joinToString(", ") { it.swahiliName } else "Alama Score bora",
                        difficulty = "rahisi",
                        timeframe = "wiki 4"
                    )
                )
            }

            // 2. Transaction volume
            val totalTx = summaries.sumOf { it.transactionCount }
            if (totalTx < 200) {
                val gain = 15
                val newScore = (score + gain).coerceIn(300, 850)
                val newProducts = catalog.filter { score < it.minAlamaScore && newScore >= it.minAlamaScore }
                actions.add(
                    ImprovementAction(
                        priority = 2,
                        action = "Increase daily transactions by accepting M-Pesa for small sales",
                        actionSwahili = "Ongeza mauzo ya kila siku kwa kukubali M-Pesa hata kwa mauzo madogo. Lengo: mauzo 10+/siku.",
                        scoreGain = gain,
                        unlocks = if (newProducts.isNotEmpty()) newProducts.joinToString(", ") { it.swahiliName } else "Alama Score bora",
                        difficulty = "rahisi",
                        timeframe = "wiki 2-4"
                    )
                )
            }

            // 3. Savings behavior
            val hasSavings = memoryManager.retrieve("savings_goals").isNotBlank()
            if (!hasSavings) {
                val gain = 50
                val newScore = (score + gain).coerceIn(300, 850)
                val newProducts = catalog.filter { score < it.minAlamaScore && newScore >= it.minAlamaScore }
                actions.add(
                    ImprovementAction(
                        priority = 3,
                        action = "Start saving — even KES 50/week",
                        actionSwahili = "Anza kuweka akiba — hata KES 50 kwa wiki. Akiba inaboresha Alama Score yako kwa +50 points.",
                        scoreGain = gain,
                        unlocks = if (newProducts.isNotEmpty()) newProducts.joinToString(", ") { it.swahiliName } else "Alama Score bora",
                        difficulty = "rahisi",
                        timeframe = "wiki 1"
                    )
                )
            }

            // 4. M-Pesa usage
            val mpesaSales = summaries.sumOf { it.mpesaSales }
            if (mpesaSales < avgDailySales * summaries.size * 0.3) {
                val gain = 50
                val newScore = (score + gain).coerceIn(300, 850)
                val newProducts = catalog.filter { score < it.minAlamaScore && newScore >= it.minAlamaScore }
                actions.add(
                    ImprovementAction(
                        priority = 4,
                        action = "Accept M-Pesa for more transactions",
                        actionSwahili = "Kubali M-Pesa kwa mauzo zaidi. Sasa hivi M-Pesa ni ${"%.0f".format(mpesaSales / (avgDailySales * summaries.size.coerceAtLeast(1)) * 100)}% ya mauzo. Lengo: 50%+.",
                        scoreGain = gain,
                        unlocks = if (newProducts.isNotEmpty()) newProducts.joinToString(", ") { it.swahiliName } else "Alama Score bora",
                        difficulty = "rahisi",
                        timeframe = "wiki 2"
                    )
                )
            }

            // 5. Debt reduction
            if (debtToIncome > 20) {
                val targetDebt = avgMonthlyRevenue * 0.2
                val reduction = totalDebt - targetDebt
                val gain = when {
                    debtToIncome > 30 -> 25
                    debtToIncome > 20 -> 15
                    else -> 5
                }
                actions.add(
                    ImprovementAction(
                        priority = if (debtToIncome > 30) 0 else 5,
                        action = "Pay down debt to below 20% of monthly income",
                        actionSwahili = "Lipa deni. Sasa hivi deni ni ${"%.0f".format(debtToIncome)}% ya mapato. Lipa KES ${"%,.0f".format(reduction)} kufikia 20%.",
                        scoreGain = gain,
                        unlocks = "Nafasi ya mkopo mwingi, riba nafuu",
                        difficulty = if (debtToIncome > 30) "ngumu" else "wastani",
                        timeframe = "wiki 4-12"
                    )
                )
            }

            // 6. Business growth
            if (allSummaries.size >= 60) {
                val firstMonth = allSummaries.drop(allSummaries.size - 30).sumOf { it.totalSales }
                val lastMonth = allSummaries.take(30).sumOf { it.totalSales }
                if (firstMonth > 0) {
                    val growth = (lastMonth - firstMonth) / firstMonth
                    if (growth < 0.1) {
                        actions.add(
                            ImprovementAction(
                                priority = 6,
                                action = "Grow your business — even 10% growth helps your score",
                                actionSwahili = "Biashara yako haikui. Jaribu kuongeza mauzo kwa 10% — nunua bidhaa mpya, ongeza wateja, au fungua mapema zaidi.",
                                scoreGain = 20,
                                unlocks = "Alama Score bora + nafasi ya mikopo mikubwa",
                                difficulty = "wastani",
                                timeframe = "wiki 8"
                            )
                        )
                    }
                }
            }

            // 7. Time in business (passive — they can't speed this up, but worth mentioning)
            if (timeInBusinessMonths < 6) {
                actions.add(
                    ImprovementAction(
                        priority = 7,
                        action = "Keep your business running consistently",
                        actionSwahili = "Endelea na biashara yako kila siku. Baada ya miezi 6, utafungua mikopo ya SACCO.",
                        scoreGain = 10,
                        unlocks = "SACCO loans",
                        difficulty = "rahisi",
                        timeframe = "miezi ${6 - timeInBusinessMonths}"
                    )
                )
            }

            // Sort by priority
            actions.sortBy { it.priority }

            val message = buildString {
                appendLine("📈 HATUA ZA KUBORESHA ALAMA SCORE YAKO")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("📊 Alama Score sasa hivi: $score/850 (${scoreResult.level})")
                appendLine()

                if (actions.isEmpty()) {
                    appendLine("🎉 Hongera! Alama Score yako iko vizuri!")
                    appendLine("Endelea hivi na utafungua mikopo bora zaidi.")
                } else {
                    appendLine("Fanya hizi kwa mpango huu (kuanzia muhimu zaidi):")
                    appendLine()

                    for ((index, action) in actions.withIndex()) {
                        val difficultyEmoji = when (action.difficulty) {
                            "rahisi" -> "🟢"
                            "wastani" -> "🟡"
                            else -> "🔴"
                        }
                        appendLine("${index + 1}. $difficultyEmoji ${action.actionSwahili}")
                        appendLine("   → Alama Score: +${action.scoreGain}")
                        appendLine("   → Fungua: ${action.unlocks}")
                        appendLine("   → Muda: ${action.timeframe}")
                        appendLine()
                    }

                    // Total potential improvement
                    val totalGain = actions.sumOf { it.scoreGain }
                    val potentialScore = (score + totalGain).coerceIn(300, 850)
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine("🎯 Ukifanya ZOTE: Alama Score $score → $potentialScore")
                    appendLine()

                    // What that unlocks
                    val newlyUnlocked = catalog.filter { score < it.minAlamaScore && potentialScore >= it.minAlamaScore }
                    if (newlyUnlocked.isNotEmpty()) {
                        appendLine("🔓 Itafungua:")
                        for (p in newlyUnlocked.distinctBy { it.swahiliName }) {
                            appendLine("   ✅ ${p.swahiliName}: mpaka KES ${"%,.0f".format(p.maxAmount)} @ ${formatApr(p.effectiveApr * 100)} APR")
                        }
                    }
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "current_score" to score,
                    "actions" to actions.map { mapOf(
                        "action" to it.actionSwahili,
                        "score_gain" to it.scoreGain,
                        "unlocks" to it.unlocks,
                        "difficulty" to it.difficulty,
                        "timeframe" to it.timeframe
                    )},
                    "total_potential_gain" to actions.sumOf { it.scoreGain }
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to show improvements")
            ToolResult.error(name, "Failed: ${e.message}", "CALCULATION_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 5. HISTORY — Past assessments and goals
    // ──────────────────────────────────────────────

    /**
     * Show history of credit readiness assessments, goals set, and eligibility changes.
     */
    private suspend fun showHistory(params: Map<String, String>): ToolResult {
        return try {
            val scoreResult = alamaScore.calculateScore()
            val currentScore = scoreResult.score

            // Retrieve stored assessment history from memory
            val lastAssessment = memoryManager.retrieve("last_credit_assessment")
            val creditGoals = memoryManager.retrieve("credit_goals")

            // Get score trend from daily summaries
            val summaries = dailySummaryDao.getRecentSummaries(90).first()
            val recentScore = currentScore

            // Calculate score trend (approximate from business data trends)
            val firstHalf = summaries.drop(summaries.size / 2)
            val secondHalf = summaries.take(summaries.size / 2)
            val firstAvg = if (firstHalf.isNotEmpty()) firstHalf.map { it.totalSales }.average() else 0.0
            val secondAvg = if (secondHalf.isNotEmpty()) secondHalf.map { it.totalSales }.average() else 0.0
            val trend = if (firstAvg > 0) ((secondAvg - firstAvg) / firstAvg * 100) else 0.0

            // Current eligible products count
            val catalog = buildLenderCatalog()
            val eligibleCount = catalog.count { currentScore >= it.minAlamaScore }

            // Debt history
            val totalDebt = debtDao.getTotalOutstanding().first() ?: 0.0
            val activeDebts = debtDao.getActiveDebtCount().first()

            val message = buildString {
                appendLine("📜 HISTORIA YA CREDIT READINESS")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("📊 Alama Score ya Sasa: $currentScore/850")
                appendLine("📈 Mwelekeo: ${if (trend > 0) "↑" else if (trend < 0) "↓" else "→"} ${"%.1f".format(kotlin.math.abs(trend))}% mauzo ya wiki")
                appendLine("🔓 Mikopo inayopatikana: $eligibleCount bidhaa")
                appendLine()

                // Current debt status
                appendLine("💰 HALI YA DENI:")
                appendLine("   Deni la sasa: KES ${"%,.0f".format(totalDebt)}")
                appendLine("   Deni hai: $activeDebts")
                appendLine()

                // Last assessment
                if (lastAssessment.isNotBlank()) {
                    appendLine("📋 TATHMINI YA MWISHO:")
                    appendLine("   $lastAssessment")
                    appendLine()
                }

                // Goals
                if (creditGoals.isNotBlank()) {
                    appendLine("🎯 MALENGO:")
                    appendLine("   $creditGoals")
                    appendLine()
                }

                // Score milestones
                appendLine("🏁 MILELE ZA ALAMA SCORE:")
                val milestones = listOf(
                    400 to "Building — Anza kujenga historia",
                    500 to "Good — Fungua mikopo ya M-Shwari/KCB",
                    550 to "Good+ — Fungua SACCO emergency loan",
                    650 to "Strong — Fungua mikopo ya benki",
                    700 to "Strong+ — Fungua mikopo ya SME",
                    750 to "Excellent — Mikopo yote inapatikana"
                )
                for ((threshold, label) in milestones) {
                    val status = if (currentScore >= threshold) "✅" else "⬜"
                    appendLine("   $status $threshold: $label")
                }

                // What's next
                val nextMilestone = milestones.firstOrNull { currentScore < it.first }
                if (nextMilestone != null) {
                    val gap = nextMilestone.first - currentScore
                    appendLine()
                    appendLine("👉 Lengo linalofuata: Alama Score ${nextMilestone.first}")
                    appendLine("   Inabidi uongeze +$gap points")
                    appendLine("   → ${nextMilestone.second}")
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "current_score" to currentScore,
                    "trend_pct" to trend,
                    "eligible_products" to eligibleCount,
                    "total_debt" to totalDebt,
                    "active_debts" to activeDebts,
                    "last_assessment" to lastAssessment,
                    "next_milestone" to (milestones.firstOrNull { currentScore < it.first }?.first)
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
     * - "Naweza pata mkopo?" → check
     * - "Alama yangu inatosha?" → check
     * - "Nikopeshe pesa ngapi?" → check
     * - "Ninahitaji KES 20,000 — nitapata wapi?" → compare with target_amount
     * - "Nilinganishe mikopo" → compare
     * - "Niboreshaje alama?" → improve
     * - "Historia yangu" → history
     * - "Nisaidie kuboresha" → improve
     */
    private suspend fun parseVoiceInput(voiceInput: String): ToolResult {
        val input = voiceInput.lowercase().trim()
        Timber.d("Parsing credit readiness voice input: '$input'")

        // Extract target amount if mentioned
        val targetAmount = extractAmount(input)

        // Route based on intent
        return when {
            // Compare intents
            input.contains("lingan") || input.contains("compare") ||
            input.contains("ipi ni bora") || input.contains("which is better") ||
            input.contains("nipate wapi") || input.contains("nilinganishe") -> {
                val params = mutableMapOf<String, String>("action" to "compare")
                if (targetAmount != null) params["target_amount"] = targetAmount.toString()
                compareOffers(params)
            }

            // Simulate intents
            input.contains("simul") || input.contains("nikiweka") ||
            input.contains("what if") || input.contains("niki") -> {
                val params = mutableMapOf<String, String>("action" to "simulate")
                if (targetAmount != null) params["target_amount"] = targetAmount.toString()
                // Parse savings amount if mentioned
                val weeklySave = extractWeeklySave(input)
                if (weeklySave != null) params["save_weekly"] = weeklySave.toString()
                simulateImprovement(params)
            }

            // Improve intents
            input.contains("boresha") || input.contains("improve") ||
            input.contains("ongeza") || input.contains("nifanye nini") ||
            input.contains("hatua") || input.contains("how") -> {
                showImprovements(mutableMapOf("action" to "improve"))
            }

            // History intents
            input.contains("historia") || input.contains("history") ||
            input.contains("record") || input.contains("milestone") -> {
                showHistory(mutableMapOf("action" to "history"))
            }

            // Default: check readiness
            else -> {
                checkReadParams(targetAmount)
            }
        }
    }

    /**
     * Extract KES amount from Swahili voice input.
     * Handles: "KES 20,000", "elfu ishirini", "20000", "5,000"
     */
    private fun extractAmount(input: String): Double? {
        // Pattern: KES followed by number
        val kesPattern = Regex("""(?:kes|ksh|k)\s*(\d[\d,]*(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        kesPattern.find(input)?.let {
            return it.groupValues[1].replace(",", "").toDoubleOrNull()
        }

        // Pattern: standalone number with context
        val numberPattern = Regex("""(\d[\d,]*(?:\.\d+)?)""")
        numberPattern.find(input)?.let {
            return it.groupValues[1].replace(",", "").toDoubleOrNull()
        }

        // Pattern: Swahili number words
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
     * Extract weekly savings amount from Swahili voice input.
     * Handles: "nikiweka 200 kwa wiki", "save 500 per week"
     */
    private fun extractWeeklySave(input: String): Int? {
        val pattern = Regex("""(?:weka|save|akiba)\s*(\d[\d,]*)\s*(?:kwa|per|kila)\s*wiki""", RegexOption.IGNORE_CASE)
        pattern.find(input)?.let {
            return it.groupValues[1].replace(",", "").toIntOrNull()
        }
        return null
    }

    /**
     * Check readiness with optional target amount from voice.
     */
    private suspend fun checkReadParams(targetAmount: Double?): ToolResult {
        val params = mutableMapOf<String, String>("action" to "check")
        if (targetAmount != null) params["target_amount"] = targetAmount.toString()
        return checkReadiness(params)
    }

    // ──────────────────────────────────────────────
    // CALCULATION HELPERS
    // ──────────────────────────────────────────────

    /**
     * Calculate the maximum loan amount a worker can qualify for from a given product.
     * Based on income, existing debt, and product limits.
     * Rule: total debt service should not exceed 30% of monthly income.
     */
    private fun calculateMaxLoan(
        product: LenderProduct,
        avgMonthlyRevenue: Double,
        totalExistingDebt: Double
    ): Double {
        if (avgMonthlyRevenue <= 0) return product.minAmount

        // Max debt service = 30% of monthly revenue
        val maxDebtService = avgMonthlyRevenue * 0.30

        // Existing debt service (estimate monthly repayment at 10% of outstanding)
        val existingDebtService = totalExistingDebt * 0.10

        // Available for new debt service
        val availableService = (maxDebtService - existingDebtService).coerceAtLeast(0.0)

        // Convert available service to loan amount based on product's rate
        val monthlyRate = product.monthlyRate.coerceAtLeast(0.01)
        val termMonths = (product.maxTermDays / 30.0).coerceAtLeast(1.0)

        // Simple approximation: max loan where monthly payment ≤ available service
        // Monthly payment ≈ principal * (monthlyRate * (1+monthlyRate)^term) / ((1+monthlyRate)^term - 1)
        val factor = if (monthlyRate > 0) {
            val compoundFactor = Math.pow(1 + monthlyRate, termMonths)
            monthlyRate * compoundFactor / (compoundFactor - 1)
        } else {
            1.0 / termMonths
        }

        val maxFromIncome = availableService / factor

        // Cap at product limits
        return maxFromIncome.coerceIn(product.minAmount, product.maxAmount)
    }

    /**
     * Calculate true cost of a loan including all hidden fees.
     * Returns total repayment, effective APR, monthly payment, daily burden.
     */
    private fun calculateTrueCost(principal: Double, product: LenderProduct): Map<String, Any> {
        val termMonths = (product.maxTermDays / 30.0).coerceAtLeast(1.0)

        // Calculate fees
        val processingFee = (principal * product.processingFeePct) + product.processingFeeFixed
        val insuranceFee = principal * product.insuranceFeePct
        val totalFees = processingFee + insuranceFee

        // Calculate interest
        val monthlyRate = product.monthlyRate
        val totalInterest = if (monthlyRate > 0) {
            principal * monthlyRate * termMonths
        } else {
            // For daily-charged products (like Fuliza), use effective APR
            principal * product.effectiveApr * (termMonths / 12.0)
        }

        // Total repayment
        val totalRepayment = principal + totalInterest + totalFees

        // Monthly payment
        val monthlyRepayment = totalRepayment / termMonths

        // Daily burden
        val dailyBurden = totalRepayment / product.maxTermDays

        // Effective APR (true annual cost)
        val effectiveApr = if (termMonths <= 1) {
            // Short-term loans: annualize the total cost
            val totalCostPct = (totalRepayment - principal) / principal
            totalCostPct * (365.0 / product.maxTermDays)
        } else {
            // Long-term loans: standard APR calculation
            val annualCostPct = ((totalRepayment - principal) / principal) * (12.0 / termMonths)
            annualCostPct
        }

        return mapOf(
            "principal" to principal,
            "interest_charged" to totalInterest,
            "processing_fee" to processingFee,
            "insurance_fee" to insuranceFee,
            "total_fees" to totalFees,
            "total_repayment" to totalRepayment,
            "effective_apr" to effectiveApr,
            "monthly_repayment" to monthlyRepayment,
            "daily_burden" to dailyBurden,
            "term_months" to termMonths
        )
    }

    /**
     * Calculate score gain from consistent savings behavior.
     * Savings behavior is worth up to 50 points in AlamaScore.
     */
    private fun calculateSavingsScoreGain(weeklyAmount: Int, weeks: Int): Int {
        if (weeklyAmount <= 0 || weeks <= 0) return 0
        // Base gain for having savings goals: +50
        // Additional gain for consistency over time
        val baseGain = 50
        val consistencyBonus = if (weeks >= 12) 15 else if (weeks >= 8) 10 else 5
        return baseGain + consistencyBonus
    }

    /**
     * Calculate score gain from reducing Fuliza usage.
     * Fuliza usage indicates cash flow stress, which lowers score.
     */
    private fun calculateFulizaScoreGain(targetWeeklyUses: Int): Int {
        // Reducing Fuliza usage improves transaction consistency and financial health
        return when {
            targetWeeklyUses <= 1 -> 20
            targetWeeklyUses <= 3 -> 15
            targetWeeklyUses <= 5 -> 10
            else -> 5
        }
    }

    /**
     * Calculate peer percentile ranking based on score and business type.
     * Uses approximate distribution for Kenyan informal workers.
     */
    private fun calculatePercentile(score: Int, businessType: String): Double {
        // Approximate percentile based on score distribution
        // Mean score ~500, SD ~100 for informal workers
        val zScore = (score - 500.0) / 100.0
        // Approximate CDF of normal distribution
        val percentile = (1.0 + erf(zScore / Math.sqrt(2.0))) / 2.0 * 100.0
        return percentile.coerceIn(0.0, 100.0)
    }

    /**
     * Error function approximation for normal distribution CDF.
     */
    private fun erf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * kotlin.math.abs(x))
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x)
        return if (x >= 0) y else -y
    }

    /**
     * Build personalized improvement path based on current score components.
     */
    private fun buildImprovementPath(
        score: Int,
        totalDebt: Double,
        avgMonthlyRevenue: Double,
        timeInBusinessMonths: Int
    ): List<Map<String, Any>> {
        val path = mutableListOf<Map<String, Any>>()
        val catalog = buildLenderCatalog()

        // Savings is the easiest win
        if (score < 600) {
            val newScore = (score + 50).coerceIn(300, 850)
            val newProducts = catalog.filter { score < it.minAlamaScore && newScore >= it.minAlamaScore }
            path.add(mapOf(
                "action" to "Anza kuweka akiba — hata KES 50/wiki",
                "target_value" to 50.0,
                "score_gain" to 50,
                "unlocks" to if (newProducts.isNotEmpty()) newProducts.joinToString(", ") { it.swahiliName } else "Alama Score bora"
            ))
        }

        // Consistency
        if (score < 650) {
            path.add(mapOf(
                "action" to "Rekodi mauzo KILA siku — hata KES 0",
                "target_value" to 26.0,
                "score_gain" to 20,
                "unlocks" to "Historia thabiti ya biashara"
            ))
        }

        // Debt reduction
        val dti = if (avgMonthlyRevenue > 0) (totalDebt / avgMonthlyRevenue * 100) else 0.0
        if (dti > 20) {
            val targetDebt = avgMonthlyRevenue * 0.2
            path.add(mapOf(
                "action" to "Lipa deni kufikia chini ya 20% ya mapato",
                "target_value" to targetDebt,
                "score_gain" to 15,
                "unlocks" to "Nafasi ya mikopo zaidi"
            ))
        }

        return path
    }

    // ──────────────────────────────────────────────
    // P1: DEBT TRAP DETECTION
    // ──────────────────────────────────────────────

    /**
     * P1: Detect debt trap patterns before they become critical.
     *
     * Alerts when:
     * - Fuliza usage > 3x/week (dependency pattern)
     * - Debt-to-income ratio > 40% (over-indebtedness)
     * - Multiple concurrent loans (debt stacking)
     * - Declining Alama Score trend (financial stress)
     *
     * Voice: "Je, niko kwenye debt trap?" or "Am I in a debt trap?"
     */
    private suspend fun detectDebtTrap(params: Map<String, String>): ToolResult {
        return try {
            val scoreResult = alamaScore.calculateScore()
            val score = scoreResult.score

            // Pull recent financial data
            val summaries = dailySummaryDao.getRecentSummaries(30).first()
            val avgMonthlyRevenue = if (summaries.isNotEmpty()) {
                summaries.map { it.totalSales }.average() * 30
            } else 0.0

            // Check Fuliza usage pattern
            val recentDebts = debtDao.getAll().first()
            val activeDebtTotal = recentDebts.filter { it.status == "active" }.sumOf { it.amount }
            val debtToIncomeRatio = if (avgMonthlyRevenue > 0) activeDebtTotal / avgMonthlyRevenue else 0.0

            // Count Fuliza-like transactions (repeated small loans)
            val fulizaPattern = detectFulizaPattern(summaries)

            // Assess risk level
            val riskFactors = mutableListOf<String>()
            var riskScore = 0

            if (debtToIncomeRatio > 0.40) {
                riskFactors.add("Deni ni ${"%.0f".format(debtToIncomeRatio * 100)}% ya mapato yako (juu ya 40%)")
                riskScore += 3
            } else if (debtToIncomeRatio > 0.25) {
                riskFactors.add("Deni ni ${"%.0f".format(debtToIncomeRatio * 100)}% ya mapato yako (kukaribia kikomo)")
                riskScore += 1
            }

            if (fulizaPattern.weeklyUses > 3) {
                riskFactors.add("Unatumia Fuliza mara ${fulizaPattern.weeklyUses} kwa wiki — kuna hatari ya kuzoea")
                riskScore += 2
            }

            if (recentDebts.filter { it.status == "active" }.size > 2) {
                riskFactors.add("Una mikopo ${recentDebts.filter { it.status == "active" }.size} inayoendesha — stacking")
                riskScore += 2
            }

            if (score < 400) {
                riskFactors.add("Alama yako ni $score — chini ya 400")
                riskScore += 2
            }

            val riskLevel = when {
                riskScore >= 5 -> "JUU" // HIGH
                riskScore >= 3 -> "WASTANI" // MEDIUM
                else -> "CHINI" // LOW
            }

            val message = buildString {
                appendLine("🔍 Uchunguzi wa Deni (Debt Trap Check)")
                appendLine()
                appendLine("Alama ya Alama: $score")
                appendLine("Deni la sasa: KES ${"%,.0f".format(activeDebtTotal)}")
                appendLine("Mapato ya mwezi: KES ${"%,.0f".format(avgMonthlyRevenue)}")
                appendLine("Ratio ya deni/mapato: ${"%.0f".format(debtToIncomeRatio * 100)}%")
                appendLine()

                if (riskFactors.isEmpty()) {
                    appendLine("✅ Huna dalili za debt trap. Endelea vizuri!")
                    appendLine()
                    appendLine("Vidokezo:")
                    appendLine("- Weka akiba ya dharura (wiki 2-4 za gharama)")
                    appendLine("- Epuka Fuliza — tumia kama njia ya mwisho")
                } else {
                    appendLine("⚠️ Hatari: $riskLevel")
                    appendLine()
                    appendLine("Dalili:")
                    riskFactors.forEach { appendLine("  ❌ $it") }
                    appendLine()
                    appendLine("\U0001f4a1 Mapendekezo:")
                    if (debtToIncomeRatio > 0.40) {
                        appendLine("  1. Simama na mikopo mipya — lipa deni la sasa kwanza")
                    }
                    if (fulizaPattern.weeklyUses > 3) {
                        appendLine("  2. Punguza Fuliza — weka akiba ya KES 500-1000 kwa wiki")
                    }
                    if (recentDebts.filter { it.status == "active" }.size > 2) {
                        appendLine("  3. Lipa deni dogo kwanza (snowball method)")
                    }
                    appendLine("  4. Ongeza mapato — angalia fursa mpya za biashara")
                    appendLine("  5. Zungumza na Msaidizi kwa ushauri wa kibinafsi")
                }
            }

            ToolResult.success(name, message, mapOf(
                "risk_level" to riskLevel,
                "risk_score" to riskScore,
                "debt_to_income" to debtToIncomeRatio,
                "active_debt" to activeDebtTotal,
                "monthly_revenue" to avgMonthlyRevenue,
                "risk_factors" to riskFactors
            ))
        } catch (e: Exception) {
            Timber.e(e, "Debt trap detection failed")
            ToolResult.error(name, "Failed to analyze debt pattern: ${e.message}", "DEBT_TRAP_ERROR")
        }
    }

    private data class FulizaPattern(
        val weeklyUses: Int,
        val avgAmount: Double,
        val isRecurring: Boolean
    )

    /**
     * Detect Fuliza-like usage patterns from transaction history.
     * Fuliza shows as negative M-Pesa balance / overdraft transactions.
     */
    private fun detectFulizaPattern(summaries: List<com.msaidizi.core.model.DailySummaryEntity>): FulizaPattern {
        // Heuristic: count days with expenses > 120% of sales (overdraft indicator)
        val overSpendDays = summaries.count { day ->
            day.totalExpenses > day.totalSales * 1.2 && day.totalSales > 0
        }
        val weeklyUses = (overSpendDays * 7.0 / summaries.size.coerceAtLeast(1)).toInt()
        val avgAmount = if (summaries.isNotEmpty()) {
            summaries.filter { it.totalExpenses > it.totalSales * 1.2 }
                .map { it.totalExpenses - it.totalSales }
                .average()
        } else 0.0

        return FulizaPattern(
            weeklyUses = weeklyUses,
            avgAmount = avgAmount.coerceAtLeast(0.0),
            isRecurring = weeklyUses > 2
        )
    }

    // ──────────────────────────────────────────────
    // FORMATTING HELPERS
    // ──────────────────────────────────────────────

    private fun formatApr(aprPct: Double): String {
        return when {
            aprPct < 1.0 -> "${"%.1f".format(aprPct * 100)}%"
            else -> "${"%.0f".format(aprPct)}%"
        }
    }

    private fun formatPct(pct: Double): String {
        return "${"%.1f".format(pct)}%"
    }

    private fun percentileLabel(percentile: Double): String {
        return when {
            percentile >= 90 -> "MAZIWA 10% YA JUU"
            percentile >= 75 -> "MAZIWA 25% YA JUU"
            percentile >= 50 -> "MAZIWA 50% YA JUU"
            percentile >= 25 -> "MAZIWA 25% YA CHINI"
            else -> "MAZIWA 10% YA CHINI"
        }
    }

    private fun typeEmoji(type: String): String {
        return when (type) {
            "MOBILE_LOAN" -> "📱"
            "SACCO" -> "🏦"
            "BANK_SME" -> "🏛️"
            "MFI" -> "🏪"
            "HIRE_PURCHASE" -> "🏍️"
            "OVERDRAFT" -> "⚡"
            else -> "💰"
        }
    }

    private fun typeLabel(type: String): String {
        return when (type) {
            "MOBILE_LOAN" -> "Mikopo ya Simu"
            "SACCO" -> "SACCO / Chama"
            "BANK_SME" -> "Benki"
            "MFI" -> "MFI (Taasisi ya Mikopo)"
            "HIRE_PURCHASE" -> "Kununua Kwa Awamu"
            "OVERDRAFT" -> "Overdraft"
            else -> type
        }
    }
}
