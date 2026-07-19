package com.msaidizi.app.agent.coach

import com.msaidizi.app.core.database.LoanDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.Loan
import com.msaidizi.app.core.model.TransactionType
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Debt Advisor Agent — Third stage of the Financial Coach pipeline.
 *
 * Tracks loans, analyzes debt burden, suggests repayment strategies,
 * and advises on when borrowing is safe vs dangerous.
 *
 * ## Pipeline Position
 *   User Input → IntentRouter → BudgetAnalyzer → SavingsStrategist → [DebtAdvisor] → Response
 *
 * ## The Debt Trap Problem
 * Kenya's informal workers face predatory lending:
 * - Fuliza: 1% daily fee (365% APR!)
 * - M-Shwari loans: 7.5% per month
 * - SHG/Chama loans: 5-10% per month
 * - Informal moneylenders: 20-50% per month
 *
 * Msaidizi doesn't moralize — it shows the math and lets workers decide.
 *
 * ## Academic Foundations
 *
 * ### ECO 206 §6.3 — Debt Management
 * - **Debt-to-Income Ratio:** Total debt payments / income. Should be < 40%.
 * - **Debt Snowball vs Avalanche:** Pay smallest first (motivation) vs highest
 *   interest first (math). We recommend avalanche but show both.
 * - **Progressive Lending:** Start small, build track record.
 *
 * ### ECO 210 §3 — Break-Even Analysis
 * - When does borrowing make sense? When ROI > interest rate.
 * - A mama mboga borrowing at 10%/month to buy stock that yields 30%/month is rational.
 *
 * ### FIN 201 §4 — Time Value of Money
 * - Present Value of loan payments to compare loan offers
 * - Effective Annual Rate (EAR) to compare different loan terms
 *
 * @param loanDao Room DAO for loan data access
 * @param transactionDao Room DAO for transaction data access
 * @param budgetAnalyzer Budget Analyzer Agent (for income/expense context)
 */
class DebtAdvisorAgent(
    private val loanDao: LoanDao,
    private val transactionDao: TransactionDao,
    private val budgetAnalyzer: BudgetAnalyzerAgent
) {
    companion object {
        private const val TAG = "DebtAdvisor"

        /** Healthy debt-to-income ratio */
        private const val HEALTHY_DTI = 0.30  // 30%

        /** Warning debt-to-income ratio */
        private const val WARNING_DTI = 0.50  // 50%

        /** Critical debt-to-income ratio */
        private const val CRITICAL_DTI = 0.70  // 70%

        /** Known loan types and their typical rates */
        val LOAN_TYPES = mapOf(
            "fuliza" to LoanInfo("Fuliza", 0.01, "daily"),       // 1% daily = ~365% APR
            "mshwari" to LoanInfo("M-Shwari", 0.075, "monthly"),  // 7.5% monthly
            "kcb" to LoanInfo("KCB M-Pesa", 0.075, "monthly"),
            "tala" to LoanInfo("Tala", 0.15, "monthly"),          // ~15% monthly
            "branch" to LoanInfo("Branch", 0.15, "monthly"),
            "chama" to LoanInfo("Chama", 0.05, "monthly"),        // 5% typical
            "mali" to LoanInfo("Mali", 0.10, "monthly"),
            "haraka" to LoanInfo("Haraka", 0.20, "monthly"),      // Informal
            "shylock" to LoanInfo("Shylock", 0.30, "monthly"),    // Predatory
            "mali-mali" to LoanInfo("Mali Mali", 0.20, "monthly")
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // ECO 206 §6.3 — DEBT STATUS & ANALYSIS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate a comprehensive debt status report.
     *
     * Shows all active loans, total debt burden, and debt-to-income ratio.
     *
     * @param language Output language
     * @return Debt status report
     */
    suspend fun getDebtStatus(language: String = "sw"): DebtStatus {
        val activeLoans = loanDao.getActiveLoans()
        val totalDebt = activeLoans.sumOf { it.remainingAmount }
        val monthlyPayments = activeLoans.sumOf { it.monthlyPayment }

        // Get income context
        val incomePattern = budgetAnalyzer.detectIncomePattern(30)
        val monthlyIncome = incomePattern.averageDaily * 30

        val dti = if (monthlyIncome > 0) monthlyPayments / monthlyIncome else 0.0
        val healthLevel = when {
            dti <= HEALTHY_DTI -> DebtHealth.HEALTHY
            dti <= WARNING_DTI -> DebtHealth.WARNING
            dti <= CRITICAL_DTI -> DebtHealth.DANGER
            else -> DebtHealth.CRITICAL
        }

        val message = buildString {
            if (language == "sw") {
                appendLine("🏦 Hali ya Mikopo Yako:")
                appendLine()

                if (activeLoans.isEmpty()) {
                    appendLine("✅ Huna mikopo! Nzuri sana.")
                    return@buildString
                }

                appendLine("📋 Mikopo ${activeLoans.size}:")
                for (loan in activeLoans) {
                    val interestInfo = LOAN_TYPES[loan.lender?.lowercase()]
                    appendLine("  • ${loan.lender ?: "Mkopo"}: KSh ${formatAmount(loan.remainingAmount)}")
                    if (interestInfo != null) {
                        appendLine("    Riba: ${"%.1f".format(interestInfo.rate * 100)}%/${interestInfo.period}")
                    }
                }

                appendLine()
                appendLine("💰 Jumla ya deni: KSh ${formatAmount(totalDebt)}")
                appendLine("📅 Malipo ya mwezi: KSh ${formatAmount(monthlyPayments)}")
                appendLine("📊 Uwiano wa deni/mapato: ${"%.0f".format(dti * 100)}%")

                when (healthLevel) {
                    DebtHealth.HEALTHY -> appendLine("✅ Salama! Deni lako ni chini ya 30% ya mapato.")
                    DebtHealth.WARNING -> appendLine("⚠️ Tahadhari! Deni lako ni 30-50% ya mapato.")
                    DebtHealth.DANGER -> appendLine("🚨 Hatari! Deni lako ni 50-70% ya mapato.")
                    DebtHealth.CRITICAL -> appendLine("🔴 DHARURA! Deni lako ni zaidi ya 70% ya mapato!")
                }
            } else {
                appendLine("🏦 Your Loan Status:")
                appendLine()

                if (activeLoans.isEmpty()) {
                    appendLine("✅ No active loans! Great position.")
                    return@buildString
                }

                appendLine("📋 ${activeLoans.size} active loans:")
                for (loan in activeLoans) {
                    appendLine("  • ${loan.lender ?: "Loan"}: KSh ${formatAmount(loan.remainingAmount)}")
                }

                appendLine()
                appendLine("💰 Total debt: KSh ${formatAmount(totalDebt)}")
                appendLine("📅 Monthly payments: KSh ${formatAmount(monthlyPayments)}")
                appendLine("📊 Debt-to-income ratio: ${"%.0f".format(dti * 100)}%")
            }
        }

        return DebtStatus(
            activeLoans = activeLoans,
            totalDebt = totalDebt,
            monthlyPayments = monthlyPayments,
            debtToIncomeRatio = dti,
            healthLevel = healthLevel,
            message = message
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // ECO 210 §3 — REPAYMENT STRATEGY
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate a debt repayment strategy.
     *
     * Uses the "avalanche" method (highest interest first) as the primary
     * recommendation, with "snowball" (smallest first) as an alternative.
     *
     * FIN 201 §4: The mathematically optimal strategy is to pay the
     * highest-interest debt first. But behavioral economics shows
     * that small wins (snowball) can be more motivating.
     *
     * @param language Output language
     * @return Repayment strategy
     */
    suspend fun getRepaymentStrategy(language: String = "sw"): RepaymentStrategy {
        val activeLoans = loanDao.getActiveLoans()
        if (activeLoans.isEmpty()) {
            return RepaymentStrategy(
                strategyType = RepaymentStrategyType.NONE,
                message = if (language == "sw") "✅ Huna mikopo! Hakuna mkakati wa malipo unaohitajika."
                else "✅ No loans! No repayment strategy needed.",
                totalMonthlyPayment = 0.0,
                estimatedPayoffMonths = 0
            )
        }

        val incomePattern = budgetAnalyzer.detectIncomePattern(30)
        val monthlyIncome = incomePattern.averageDaily * 30
        val availableForDebt = monthlyIncome * 0.30 // Max 30% of income for debt

        // Sort by interest rate (avalanche method)
        val sortedByRate = activeLoans.sortedByDescending { loan ->
            LOAN_TYPES[loan.lender?.lowercase()]?.rate ?: 0.10
        }

        // Calculate total payoff time
        var totalPayoffMonths = 0
        val allocations = mutableListOf<DebtAllocation>()

        var remainingBudget = availableForDebt
        for (loan in sortedByRate) {
            val rate = LOAN_TYPES[loan.lender?.lowercase()]?.rate ?: 0.10
            val payment = min(loan.monthlyPayment, remainingBudget)
            val monthsToPayoff = if (payment > 0 && loan.remainingAmount > 0) {
                // Simple calculation: remaining / monthly payment
                (loan.remainingAmount / payment).toInt().coerceAtLeast(1)
            } else 0

            allocations.add(
                DebtAllocation(
                    loan = loan,
                    recommendedPayment = payment,
                    interestRate = rate,
                    monthsToPayoff = monthsToPayoff,
                    priority = allocations.size + 1
                )
            )

            totalPayoffMonths = max(totalPayoffMonths, monthsToPayoff)
            remainingBudget = max(0.0, remainingBudget - payment)
        }

        val message = buildString {
            if (language == "sw") {
                appendLine("📋 Mkakati wa Kulipa Deni:")
                appendLine()
                appendLine("💰 Unaweza kutumia KSh ${formatAmount(availableForDebt)}/mwezi kulipa deni.")
                appendLine("📅 Muda wa kulipia: miezi ~$totalPayoffMonths")
                appendLine()
                appendLine("🎯 Lipa hivi (riba ya juu kwanza):")

                for (alloc in allocations) {
                    appendLine("  ${alloc.priority}. ${alloc.loan.lender ?: "Mkopo"}: ")
                    appendLine("     KSh ${formatAmount(alloc.recommendedPayment)}/mwezi")
                    appendLine("     (Riba: ${"%.1f".format(alloc.interestRate * 100)}%, ")
                    appendLine("     Muda: miezi ~${alloc.monthsToPayoff})")
                }

                appendLine()
                appendLine("💡 Sababu: Mkopo wa riba ya juu unaongeza deni haraka. Lipa kwanza!")
            } else {
                appendLine("📋 Debt Repayment Strategy:")
                appendLine()
                appendLine("💰 You can allocate KSh ${formatAmount(availableForDebt)}/month for debt.")
                appendLine("📅 Estimated payoff: ~$totalPayoffMonths months")
                appendLine()
                appendLine("🎯 Pay in this order (highest interest first):")

                for (alloc in allocations) {
                    appendLine("  ${alloc.priority}. ${alloc.loan.lender ?: "Loan"}: ")
                    appendLine("     KSh ${formatAmount(alloc.recommendedPayment)}/month")
                    appendLine("     (Interest: ${"%.1f".format(alloc.interestRate * 100)}%, ")
                    appendLine("     Time: ~${alloc.monthsToPayoff} months)")
                }
            }
        }

        return RepaymentStrategy(
            strategyType = RepaymentStrategyType.AVALANCHE,
            message = message,
            totalMonthlyPayment = availableForDebt,
            estimatedPayoffMonths = totalPayoffMonths,
            allocations = allocations
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // ECO 206 §6.2 — BORROWING ADVISORY
    // ═══════════════════════════════════════════════════════════════

    /**
     * Advise whether to borrow, and from where.
     *
     * ECO 210 §3: Borrowing is rational when ROI > interest rate.
     * A mama mboga who can turn KSh 10,000 into KSh 13,000 in a month
     * should borrow at 10%/month — the 30% return beats the 10% cost.
     *
     * But if ROI < interest rate, borrowing destroys value.
     *
     * @param amount Desired loan amount
     * @param purpose Purpose of the loan
     * @param language Output language
     * @return Borrowing advice
     */
    suspend fun getBorrowAdvice(
        amount: Double,
        purpose: String,
        language: String = "sw"
    ): BorrowAdvice {
        val debtStatus = getDebtStatus(language)
        val incomePattern = budgetAnalyzer.detectIncomePattern(30)
        val monthlyIncome = incomePattern.averageDaily * 30

        // Can they afford more debt?
        val currentDTI = debtStatus.debtToIncomeRatio
        val canAffordMore = currentDTI < HEALTHY_DTI

        // Calculate what they can safely borrow
        val maxSafeMonthlyPayment = monthlyIncome * HEALTHY_DTI - debtStatus.monthlyPayments
        val maxSafeLoan = maxSafeMonthlyPayment * 6 // Assume 6-month term

        val message = buildString {
            if (language == "sw") {
                if (!canAffordMore) {
                    appendLine("🚨 USIKOPE! Deni lako ni tayari ${"%.0f".format(currentDTI * 100)}% ya mapato.")
                    appendLine()
                    appendLine("Lipa deni uliyonayo kwanza. Ongeza mauzo au punguza matumizi.")
                    appendLine()
                    appendLine("Ikiwa ni dharura ya kweli, jaribu:")
                    appendLine("  • Kuomba msaada familia/marafiki")
                    appendLine("  • Kuuza kitu usichohitaji")
                    appendLine("  • Kuomba mkodogo wa chama")
                } else {
                    appendLine("✅ Unaweza kukopa hadi KSh ${formatAmount(maxSafeLoan)} salama.")
                    appendLine()
                    appendLine("📋 Chaguo bora za mkopo:")

                    // Recommend cheapest options
                    val sortedOptions = LOAN_TYPES.entries.sortedBy { it.value.rate }
                    for ((key, info) in sortedOptions.take(3)) {
                        val monthlyInterest = amount * info.rate
                        val totalRepayment = amount + monthlyInterest * 3 // 3-month term
                        appendLine("  • ${info.name}: ${"%.1f".format(info.rate * 100)}%/${info.period}")
                        appendLine("    Mkopo: KSh ${formatAmount(amount)} → Lipa KSh ${formatAmount(totalRepayment)} (miezi 3)")
                    }

                    appendLine()
                    appendLine("⚠️ EPUKA:")
                    appendLine("  • Fuliza — riba ya 1% kwa siku ni hatari!")
                    appendLine("  • Mashostaki — riba ya 20-50% kwa mwezi")
                }
            } else {
                if (!canAffordMore) {
                    appendLine("🚨 DON'T BORROW! Your debt is already ${"%.0f".format(currentDTI * 100)}% of income.")
                    appendLine()
                    appendLine("Pay existing debt first. Increase sales or reduce expenses.")
                } else {
                    appendLine("✅ You can safely borrow up to KSh ${formatAmount(maxSafeLoan)}.")
                    appendLine()
                    appendLine("📋 Best loan options:")
                    val sortedOptions = LOAN_TYPES.entries.sortedBy { it.value.rate }
                    for ((_, info) in sortedOptions.take(3)) {
                        appendLine("  • ${info.name}: ${"%.1f".format(info.rate * 100)}%/${info.period}")
                    }
                }
            }
        }

        return BorrowAdvice(
            canBorrow = canAffordMore,
            maxSafeAmount = maxSafeLoan,
            currentDTI = currentDTI,
            message = message,
            recommendedLenders = LOAN_TYPES.entries
                .sortedBy { it.value.rate }
                .take(3)
                .map { it.value.name }
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // FIN 201 §4 — LOAN COMPARISON
    // ═══════════════════════════════════════════════════════════════

    /**
     * Compare two loan offers using Effective Annual Rate (EAR).
     *
     * FIN 201 §4: Different loan terms can't be compared by nominal
     * rates alone. EAR standardizes the comparison:
     *   EAR = (1 + r/n)^n - 1
     *
     * @param offer1 First loan offer
     * @param offer2 Second loan offer
     * @return Comparison result
     */
    fun compareLoanOffers(
        offer1: LoanOffer,
        offer2: LoanOffer
    ): LoanComparison {
        val ear1 = calculateEAR(offer1.rate, offer1.compoundingPeriods)
        val ear2 = calculateEAR(offer2.rate, offer2.compoundingPeriods)

        val totalCost1 = offer1.amount * ear1 * (offer1.termMonths / 12.0)
        val totalCost2 = offer2.amount * ear2 * (offer2.termMonths / 12.0)

        val cheaper = if (totalCost1 < totalCost2) offer1 else offer2
        val savings = Math.abs(totalCost1 - totalCost2)

        return LoanComparison(
            offer1 = offer1,
            offer2 = offer2,
            ear1 = ear1,
            ear2 = ear2,
            totalCost1 = totalCost1,
            totalCost2 = totalCost2,
            cheaperOffer = cheaper,
            savingsAmount = savings
        )
    }

    /**
     * Calculate Effective Annual Rate.
     * EAR = (1 + r/n)^n - 1
     */
    private fun calculateEAR(nominalRate: Double, periodsPerYear: Int): Double {
        return Math.pow(1 + nominalRate / periodsPerYear, periodsPerYear.toDouble()) - 1
    }

    // ═══════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════

    private fun formatAmount(amount: Double): String {
        return if (amount >= 1_000_000) {
            String.format("%.1fM", amount / 1_000_000.0)
        } else if (amount >= 1_000) {
            String.format("%,.0f", amount)
        } else {
            String.format("%.0f", amount)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES — Debt Advisor outputs
// ═══════════════════════════════════════════════════════════════

data class DebtStatus(
    val activeLoans: List<Loan>,
    val totalDebt: Double,
    val monthlyPayments: Double,
    val debtToIncomeRatio: Double,
    val healthLevel: DebtHealth,
    val message: String
)

enum class DebtHealth {
    HEALTHY,   // DTI < 30%
    WARNING,   // DTI 30-50%
    DANGER,    // DTI 50-70%
    CRITICAL   // DTI > 70%
}

data class RepaymentStrategy(
    val strategyType: RepaymentStrategyType,
    val message: String,
    val totalMonthlyPayment: Double,
    val estimatedPayoffMonths: Int,
    val allocations: List<DebtAllocation> = emptyList()
)

enum class RepaymentStrategyType {
    NONE,
    AVALANCHE,  // Highest interest first (math optimal)
    SNOWBALL    // Smallest balance first (motivational)
}

data class DebtAllocation(
    val loan: Loan,
    val recommendedPayment: Double,
    val interestRate: Double,
    val monthsToPayoff: Int,
    val priority: Int
)

data class BorrowAdvice(
    val canBorrow: Boolean,
    val maxSafeAmount: Double,
    val currentDTI: Double,
    val message: String,
    val recommendedLenders: List<String>
)

data class LoanOffer(
    val lender: String,
    val amount: Double,
    val rate: Double,          // Nominal rate per period
    val compoundingPeriods: Int, // Compounding frequency per year
    val termMonths: Int
)

data class LoanComparison(
    val offer1: LoanOffer,
    val offer2: LoanOffer,
    val ear1: Double,
    val ear2: Double,
    val totalCost1: Double,
    val totalCost2: Double,
    val cheaperOffer: LoanOffer,
    val savingsAmount: Double
)

data class LoanInfo(
    val name: String,
    val rate: Double,
    val period: String  // "daily" or "monthly"
)
