package com.msaidizi.app.agent.coach

import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.TransactionType
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Savings Strategist Agent — Second stage of the Financial Coach pipeline.
 *
 * Analyzes income and expense patterns to generate personalized savings
 * strategies for informal workers with irregular income.
 *
 * ## Pipeline Position
 *   User Input → IntentRouter → BudgetAnalyzer → [SavingsStrategist] → DebtAdvisor → Response
 *
 * ## Key Challenge
 * Traditional savings advice assumes stable monthly income.
 * Mama mbogas earn KSh 500 one day and KSh 3,000 the next.
 * Our strategies adapt to this reality.
 *
 * ## Academic Foundations
 *
 * ### ECO 206 §6.4 — Savings Mobilization
 * - **Thaler's Mental Accounting:** People treat money differently based on
 *   its source. We use "save before you spend" framing.
 * - **Commitment Devices:** Savings jars, M-Pesa lock accounts, chamas.
 *   We recommend specific mechanisms available in Kenya.
 * - **Progressive Saving:** Start with 5% of daily profit, increase to 20%
 *   over 3 months. Behavioral economics shows small wins build habits.
 *
 * ### ECO 209 §7.1 — Fisher Equation
 * - Real savings = Nominal savings - Inflation
 * - We factor in Kenya's ~7% inflation to show true purchasing power.
 *
 * ### STA 244 §10.3 — Forecasting
 * - Exponential smoothing for income forecasting
 * - We predict "safe to save" amounts based on income patterns
 *
 * @param transactionDao Room DAO for transaction data access
 * @param budgetAnalyzer Budget Analyzer Agent (pipeline stage 1)
 */
class SavingsStrategistAgent(
    private val transactionDao: TransactionDao,
    private val budgetAnalyzer: BudgetAnalyzerAgent
) {
    companion object {
        private const val TAG = "SavingsStrategist"

        /** Kenya inflation rate (approximate, for real return calculation) */
        private const val INFLATION_RATE = 0.07

        /** M-Pesa savings account interest rate */
        private const val MPESA_INTEREST_RATE = 0.05

        /** Minimum savings recommendation as % of daily profit */
        private const val MIN_SAVINGS_PERCENT = 0.05  // 5%

        /** Target savings recommendation as % of daily profit */
        private const val TARGET_SAVINGS_PERCENT = 0.20  // 20%

        /** Emergency fund target (3 months of average expenses) */
        private const val EMERGENCY_FUND_MONTHS = 3

        /** Maximum savings recommendation (don't starve the business) */
        private const val MAX_SAVINGS_PERCENT = 0.30  // 30%
    }

    // ═══════════════════════════════════════════════════════════════
    // ECO 206 §6.4 — PROGRESSIVE SAVINGS STRATEGY
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate a personalized savings strategy.
     *
     * Adapts to the worker's income stability:
     * - Stable income → fixed daily savings amount
     * - Variable income → percentage-based savings
     * - Volatile income → "save on good days" strategy
     *
     * @param language Output language
     * @return Savings strategy recommendation
     */
    suspend fun generateSavingsStrategy(language: String = "sw"): SavingsStrategy {
        val incomePattern = budgetAnalyzer.detectIncomePattern(30)
        val spendingBreakdown = budgetAnalyzer.categorizeSpending(30)

        // Calculate monthly profit
        val monthlyIncome = incomePattern.averageDaily * 30
        val monthlyExpenses = spendingBreakdown.totalSpending
        val monthlyProfit = monthlyIncome - monthlyExpenses

        // Determine savings capacity
        val savingsCapacity = when {
            monthlyProfit <= 0 -> SavingsCapacity.NEGATIVE_CASH_FLOW
            monthlyProfit < monthlyIncome * 0.1 -> SavingsCapacity.MINIMAL
            monthlyProfit < monthlyIncome * 0.3 -> SavingsCapacity.MODERATE
            else -> SavingsCapacity.HEALTHY
        }

        // Calculate recommended savings
        val recommendedDaily = when (savingsCapacity) {
            SavingsCapacity.NEGATIVE_CASH_FLOW -> 0.0
            SavingsCapacity.MINIMAL -> incomePattern.averageDaily * MIN_SAVINGS_PERCENT
            SavingsCapacity.MODERATE -> incomePattern.averageDaily * TARGET_SAVINGS_PERCENT
            SavingsCapacity.HEALTHY -> incomePattern.averageDaily * min(TARGET_SAVINGS_PERCENT * 1.5, MAX_SAVINGS_PERCENT)
        }

        // Strategy based on income stability
        val strategyType = when (incomePattern.incomeStability) {
            IncomeStability.STABLE, IncomeStability.MODERATE -> StrategyType.FIXED_DAILY
            IncomeStability.VOLATILE -> StrategyType.PERCENTAGE_BASED
            IncomeStability.HIGHLY_VOLATILE -> StrategyType.SAVE_ON_GOOD_DAYS
            IncomeStability.NO_DATA -> StrategyType.START_SMALL
        }

        // Emergency fund target
        val emergencyFundTarget = monthlyExpenses * EMERGENCY_FUND_MONTHS

        val message = buildMessage(
            language = language,
            strategyType = strategyType,
            savingsCapacity = savingsCapacity,
            recommendedDaily = recommendedDaily,
            incomePattern = incomePattern,
            monthlyProfit = monthlyProfit,
            emergencyFundTarget = emergencyFundTarget
        )

        Timber.d(TAG, "Savings strategy: type=%s, capacity=%s, daily=KSh %.0f",
            strategyType, savingsCapacity, recommendedDaily)

        return SavingsStrategy(
            strategyType = strategyType,
            savingsCapacity = savingsCapacity,
            recommendedDailyAmount = recommendedDaily,
            recommendedPercent = when (strategyType) {
                StrategyType.PERCENTAGE_BASED -> TARGET_SAVINGS_PERCENT
                StrategyType.SAVE_ON_GOOD_DAYS -> TARGET_SAVINGS_PERCENT
                else -> if (incomePattern.averageDaily > 0) recommendedDaily / incomePattern.averageDaily else 0.0
            },
            emergencyFundTarget = emergencyFundTarget,
            message = message,
            monthlyProfit = monthlyProfit
        )
    }

    /**
     * Build the savings strategy message.
     */
    private fun buildMessage(
        language: String,
        strategyType: StrategyType,
        savingsCapacity: SavingsCapacity,
        recommendedDaily: Double,
        incomePattern: IncomePattern,
        monthlyProfit: Double,
        emergencyFundTarget: Double
    ): String {
        if (savingsCapacity == SavingsCapacity.NEGATIVE_CASH_FLOW) {
            return if (language == "sw") {
                "💡 Mapato yako ni chini ya matumizi. Hatua za kwanza:\n" +
                "1. Angalia matumizi yako — kuna nini unaweza kupunguza?\n" +
                "2. Ongeza bei za mauzo kidogo\n" +
                "3. Fikiria bidhaa mpya za kuuza\n\n" +
                "Usijali — hata KSh 50 kwa siku inasaidia baada ya muda."
            } else {
                "💡 Your income is below expenses. First steps:\n" +
                "1. Review your expenses — what can you reduce?\n" +
                "2. Increase selling prices slightly\n" +
                "3. Consider new products to sell\n\n" +
                "Don't worry — even KSh 50/day helps over time."
            }
        }

        return buildString {
            if (language == "sw") {
                appendLine("💰 Mkakati Wako wa Akiba:")
                appendLine()

                when (strategyType) {
                    StrategyType.FIXED_DAILY -> {
                        appendLine("📋 Mkakati: Weka kiasi kile kile kila siku")
                        appendLine("💰 Kila siku: KSh ${recommendedDaily.roundToInt()}")
                        appendLine("📅 Mwezi: ~KSh ${(recommendedDaily * 30).roundToInt()}")
                        appendLine()
                        appendLine("💡 Weka hii kwenye M-Pesa Savings au kasha la akiba.")
                    }
                    StrategyType.PERCENTAGE_BASED -> {
                        appendLine("📋 Mkakati: Weka ${"%.0f".format(TARGET_SAVINGS_PERCENT * 100)}% ya faida kila siku")
                        appendLine("💰 Kila siku: ~KSh ${recommendedDaily.roundToInt()} (wastani)")
                        appendLine()
                        appendLine("💡 Siku nzuri — weka zaidi. Siku mbaya — weka kidogo. Muhimu: weka kila siku.")
                    }
                    StrategyType.SAVE_ON_GOOD_DAYS -> {
                        appendLine("📋 Mkakati: Weka siku za faida kubwa")
                        appendLine("💰 Faida ya wastani: KSh ${incomePattern.averageDaily.roundToInt()}/siku")
                        appendLine("🎯 Lengo: weka 20% siku faida > KSh ${(incomePattern.averageDaily * 1.2).roundToInt()}")
                        appendLine()
                        appendLine("💡 Mapato yako ni mabaya. Weka akiba siku za mwanzo wa mwezi — ndio faida zaidi.")
                    }
                    StrategyType.START_SMALL -> {
                        appendLine("📋 Mkakati: Anza ndogo, kua pole pole")
                        appendLine("💰 Wiki 1-4: KSh 50/siku")
                        appendLine("💰 Wiki 5-8: KSh 100/siku")
                        appendLine("💰 Wiki 9+: 10% ya faida")
                        appendLine()
                        appendLine("💡 Anza na KSh 50 tu. Baada ya mwezi, utazoea.")
                    }
                }

                appendLine()
                appendLine("🏦 Lengo la dharura: KSh ${formatAmount(emergencyFundTarget)}")
                appendLine("   (Matumizi ya miezi 3 — kwa dharura kama ugonjwa au msimu mbaya)")
            } else {
                appendLine("💰 Your Savings Strategy:")
                appendLine()

                when (strategyType) {
                    StrategyType.FIXED_DAILY -> {
                        appendLine("📋 Strategy: Save the same amount every day")
                        appendLine("💰 Daily: KSh ${recommendedDaily.roundToInt()}")
                        appendLine("📅 Monthly: ~KSh ${(recommendedDaily * 30).roundToInt()}")
                    }
                    StrategyType.PERCENTAGE_BASED -> {
                        appendLine("📋 Strategy: Save ${"%.0f".format(TARGET_SAVINGS_PERCENT * 100)}% of daily profit")
                        appendLine("💰 Daily: ~KSh ${recommendedDaily.roundToInt()} (average)")
                    }
                    StrategyType.SAVE_ON_GOOD_DAYS -> {
                        appendLine("📋 Strategy: Save on high-profit days")
                        appendLine("💰 Average profit: KSh ${incomePattern.averageDaily.roundToInt()}/day")
                        appendLine("🎯 Target: save 20% when profit > KSh ${(incomePattern.averageDaily * 1.2).roundToInt()}")
                    }
                    StrategyType.START_SMALL -> {
                        appendLine("📋 Strategy: Start small, grow over time")
                        appendLine("💰 Weeks 1-4: KSh 50/day")
                        appendLine("💰 Weeks 5-8: KSh 100/day")
                        appendLine("💰 Weeks 9+: 10% of profit")
                    }
                }

                appendLine()
                appendLine("🏦 Emergency fund target: KSh ${formatAmount(emergencyFundTarget)}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ECO 209 §7.1 — SAVINGS GROWTH PROJECTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Project savings growth over time.
     *
     * Shows the worker what their savings will look like in 3, 6, 12 months.
     * Uses compound interest with M-Pesa's ~5% annual rate.
     *
     * ECO 209 §7.1: Future Value = PV × (1 + r)^n
     *
     * @param dailyAmount Amount saved per day
     * @param language Output language
     * @return Growth projection message
     */
    fun projectSavingsGrowth(dailyAmount: Double, language: String = "sw"): String {
        val monthlyAmount = dailyAmount * 30
        val monthlyRate = MPESA_INTEREST_RATE / 12

        val projections = listOf(3, 6, 12).map { months ->
            // Future value of annuity: FV = PMT × ((1+r)^n - 1) / r
            val fv = if (monthlyRate > 0) {
                monthlyAmount * (Math.pow(1 + monthlyRate, months.toDouble()) - 1) / monthlyRate
            } else {
                monthlyAmount * months
            }
            months to fv
        }

        return buildString {
            if (language == "sw") {
                appendLine("📈 Ukuaji wa Akiba (ukiweka KSh ${dailyAmount.roundToInt()}/siku):")
            } else {
                appendLine("📈 Savings Growth (saving KSh ${dailyAmount.roundToInt()}/day):")
            }

            for ((months, amount) in projections) {
                val label = if (language == "sw") "miezi" else "months"
                appendLine("  ${months} $label: KSh ${formatAmount(amount)}")
            }

            // Real return calculation
            val realReturn = MPESA_INTEREST_RATE - INFLATION_RATE
            if (realReturn < 0) {
                if (language == "sw") {
                    appendLine("\n💡 Kumbuka: M-Pesa hutoa riba ${"%.0f".format(MPESA_INTEREST_RATE * 100)}%, " +
                        "lakini mfumuko wa bei ni ${"%.0f".format(INFLATION_RATE * 100)}%. " +
                        "Akiba bora ni kuuza zaidi — si tu kuhifadhi.")
                } else {
                    appendLine("\n💡 Note: M-Pesa gives ${"%.0f".format(MPESA_INTEREST_RATE * 100)}% interest, " +
                        "but inflation is ${"%.0f".format(INFLATION_RATE * 100)}%. " +
                        "The best savings is investing in your business.")
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ECO 206 §6.2 — SAVINGS MECHANISM RECOMMENDATIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Recommend specific savings mechanisms available in Kenya.
     *
     * Each mechanism has different liquidity, interest, and commitment
     * characteristics. We match to the worker's needs.
     *
     * @param emergencyFundTarget Target emergency fund amount
     * @param currentSavings Current savings amount
     * @param language Output language
     * @return List of recommended mechanisms
     */
    fun recommendSavingsMechanisms(
        emergencyFundTarget: Double,
        currentSavings: Double,
        language: String = "sw"
    ): List<SavingsMechanism> {
        val mechanisms = mutableListOf<SavingsMechanism>()

        // M-Pesa Savings (KCB M-Pesa or M-Shwari)
        mechanisms.add(
            SavingsMechanism(
                name = if (language == "sw") "M-Pesa Savings (M-Shwari)" else "M-Pesa Savings (M-Shwari)",
                description = if (language == "sw") {
                    "Weka pesa kwenye M-Shwari. Inalipwa riba ya 5% kwa mwaka. Unaweza kukopa pia."
                } else {
                    "Save in M-Shwari. Earns 5% annual interest. You can also borrow against it."
                },
                interestRate = 0.05,
                liquidity = LiquidityLevel.HIGH,
                commitmentLevel = CommitmentLevel.LOW,
                bestFor = if (language == "sw") "Akiba ya dharura" else "Emergency savings",
                minimumAmount = 1.0
            )
        )

        // Chama (savings group)
        mechanisms.add(
            SavingsMechanism(
                name = if (language == "sw") "Chama (Kikundi cha Akiba)" else "Chama (Savings Group)",
                description = if (language == "sw") {
                    "Jiunge na chama. Mnaweka pesa kila wiki au mwezi. Mkopo wa haraka ukishindwa."
                } else {
                    "Join a savings group. Save weekly or monthly. Quick loans when in need."
                },
                interestRate = 0.0,  // Varies
                liquidity = LiquidityLevel.MEDIUM,
                commitmentLevel = CommitmentLevel.HIGH,
                bestFor = if (language == "sw") "Akiba ya muda mrefu" else "Long-term savings",
                minimumAmount = 500.0
            )
        )

        // Sacco
        if (currentSavings > 5000 || emergencyFundTarget > 20000) {
            mechanisms.add(
                SavingsMechanism(
                    name = if (language == "sw") "SACCO" else "SACCO",
                    description = if (language == "sw") {
                        "Jiunge na SACCO. Riba nzuri (8-12%), mkopo wa bei rahisi. Lakini pesa imefungwa."
                    } else {
                        "Join a SACCO. Good interest (8-12%), cheap loans. But money is locked."
                    },
                    interestRate = 0.10,
                    liquidity = LiquidityLevel.LOW,
                    commitmentLevel = CommitmentLevel.HIGH,
                    bestFor = if (language == "sw") "Uwekezaji wa muda mrefu" else "Long-term investment",
                    minimumAmount = 1000.0
                )
            )
        }

        // Kibubu (physical savings box)
        mechanisms.add(
            SavingsMechanism(
                name = if (language == "sw") "Kibubu (Kasha la Akiba)" else "Kibubu (Savings Box)",
                description = if (language == "sw") {
                    "Weka pesa kwenye kasha. Rahisi kuanza, hakuna riba, lakini inasaidia kujenga tabia."
                } else {
                    "Save in a box. Easy to start, no interest, but builds the habit."
                },
                interestRate = 0.0,
                liquidity = LiquidityLevel.HIGH,
                commitmentLevel = CommitmentLevel.LOW,
                bestFor = if (language == "sw") "Kuanza tabya ya kuhifadhi" else "Building the saving habit",
                minimumAmount = 10.0
            )
        )

        return mechanisms
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
// DATA CLASSES — Savings Strategist outputs
// ═══════════════════════════════════════════════════════════════

data class SavingsStrategy(
    val strategyType: StrategyType,
    val savingsCapacity: SavingsCapacity,
    val recommendedDailyAmount: Double,
    val recommendedPercent: Double,
    val emergencyFundTarget: Double,
    val message: String,
    val monthlyProfit: Double
)

enum class StrategyType {
    /** Save the same amount every day — for stable income */
    FIXED_DAILY,

    /** Save a percentage of daily profit — for variable income */
    PERCENTAGE_BASED,

    /** Save more on good days, less on bad — for volatile income */
    SAVE_ON_GOOD_DAYS,

    /** Start small, increase over time — for new users */
    START_SMALL
}

enum class SavingsCapacity {
    NEGATIVE_CASH_FLOW,
    MINIMAL,
    MODERATE,
    HEALTHY
}

data class SavingsMechanism(
    val name: String,
    val description: String,
    val interestRate: Double,
    val liquidity: LiquidityLevel,
    val commitmentLevel: CommitmentLevel,
    val bestFor: String,
    val minimumAmount: Double
)

enum class LiquidityLevel {
    HIGH,    // Can withdraw anytime
    MEDIUM,  // Some restrictions
    LOW      // Money is locked
}

enum class CommitmentLevel {
    LOW,     // No obligation
    MEDIUM,  // Regular but flexible
    HIGH     // Must contribute regularly
}
