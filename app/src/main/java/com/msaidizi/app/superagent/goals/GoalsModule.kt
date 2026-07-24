package com.msaidizi.superagent.goals

import timber.log.Timber

/**
 * Goals Module — Entry point for the :superagent:goals capability.
 *
 * This module handles savings goals, loan tracking, and tithe/giving
 * management for informal workers.
 *
 * ## Architecture
 *
 * The goals module replaces the scattered goal-related code from the
 * multi-agent architecture (GoalPlanner, GoalEngine, TitheTracker,
 * LoanManager) into one cohesive module.
 *
 * ## Capabilities
 *
 * | Engine           | Purpose                                        |
 * |------------------|------------------------------------------------|
 * | [GoalTracker]    | Savings goals, milestones, streaks             |
 * | [LoanTracker]    | Loan recording, repayment tracking, alerts     |
 * | [TitheTracker]   | Charitable giving tracking                     |
 *
 * ## Usage
 *
 * ```kotlin
 * val goalsModule = GoalsModule()
 *
 * // Create a savings goal
 * val goal = goalsModule.createGoal("Frijiji mpya", 50_000.0, GoalCategory.ASSET)
 *
 * // Track progress
 * val (updated, milestones) = goalsModule.recordGoalProgress(goal, 5_000.0)
 *
 * // Record a loan
 * val loan = goalsModule.recordLoan(10_000.0, "Stock", "M-Shwari", 0.10)
 *
 * // Record tithe
 * val tithe = goalsModule.recordGiving(GivingType.TITHE, 500.0, "Kanisa")
 * ```
 *
 * @author Msaidizi Financial Team
 */
class GoalsModule(
    val goalTracker: GoalTracker = GoalTracker(),
    val loanTracker: LoanTracker = LoanTracker(),
    val titheTracker: TitheTracker = TitheTracker()
) {

    companion object {
        private const val TAG = "GoalsModule"
    }

    // ═══════════════════════════════════════════════════════════════
    // GOAL OPERATIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Create a new savings goal.
     *
     * @param name Goal name/description
     * @param targetAmount Target amount in KSh
     * @param category Goal category
     * @param deadline Target date (Unix timestamp, 0 = no deadline)
     * @param deeperPurpose Why this goal matters
     * @return New [Goal]
     */
    fun createGoal(
        name: String,
        targetAmount: Double,
        category: GoalCategory = GoalCategory.SAVINGS,
        deadline: Long = 0,
        deeperPurpose: String = ""
    ): Goal {
        Timber.tag(TAG).d("Creating goal: %s, target=KSh %.0f", name, targetAmount)
        return goalTracker.createGoal(name, targetAmount, category, deadline, deeperPurpose)
    }

    /**
     * Create a goal from voice input.
     *
     * @param voiceInput Voice text describing the goal
     * @return Parsed [Goal] or null
     */
    fun createGoalFromVoice(voiceInput: String): Goal? {
        Timber.tag(TAG).d("Creating goal from voice: %s", voiceInput)
        return goalTracker.createFromVoice(voiceInput)
    }

    /**
     * Record progress toward a goal.
     *
     * @param goal Current goal state
     * @param amount Amount contributed
     * @param note Optional note
     * @return Pair of updated goal and new milestones
     */
    fun recordGoalProgress(
        goal: Goal,
        amount: Double,
        note: String = ""
    ): Pair<Goal, List<GoalMilestone>> {
        Timber.tag(TAG).d("Recording progress: KSh %.0f toward '%s'", amount, goal.name)
        val (updated, milestones) = goalTracker.recordProgress(goal, amount, note)

        // Generate encouragement
        if (milestones.isNotEmpty()) {
            val encouragement = goalTracker.encourage(updated, milestones)
            Timber.tag(TAG).d("Milestone reached: %s", encouragement)
        }

        return Pair(updated, milestones)
    }

    /**
     * Forecast when a goal will be reached.
     *
     * @param goal The goal
     * @param progressHistory Progress entries
     * @return [GoalForecast]
     */
    fun forecastGoal(goal: Goal, progressHistory: List<GoalProgress>): GoalForecast {
        return goalTracker.forecast(goal, progressHistory)
    }

    /**
     * Adjust goal targets based on income patterns.
     *
     * @param goal Current goal
     * @param averageDailyIncome Average daily income
     * @return Updated goal
     */
    fun adjustGoal(goal: Goal, averageDailyIncome: Double): Goal {
        return goalTracker.adjustTargets(goal, averageDailyIncome)
    }

    /**
     * Generate encouragement for a goal.
     *
     * @param goal Current goal
     * @param milestones New milestones (if any)
     * @return Encouragement message in Swahili
     */
    fun encourageGoal(goal: Goal, milestones: List<GoalMilestone> = emptyList()): String {
        return goalTracker.encourage(goal, milestones)
    }

    // ═══════════════════════════════════════════════════════════════
    // LOAN OPERATIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a new loan.
     *
     * @param amount Principal amount
     * @param purpose What the loan is for
     * @param lender Who lent the money
     * @param interestRate Monthly interest rate
     * @param durationMonths Duration in months
     * @return New [Loan]
     */
    fun recordLoan(
        amount: Double,
        purpose: String,
        lender: String = "",
        interestRate: Double = 0.0,
        durationMonths: Int = 1
    ): Loan {
        Timber.tag(TAG).d("Recording loan: KSh %.0f from %s", amount, lender)
        return loanTracker.recordLoan(amount, purpose, lender, interestRate, durationMonths)
    }

    /**
     * Record a loan from voice input.
     *
     * @param voiceInput Voice text describing the loan
     * @return Parsed [Loan] or null
     */
    fun recordLoanFromVoice(voiceInput: String): Loan? {
        Timber.tag(TAG).d("Recording loan from voice: %s", voiceInput)
        return loanTracker.recordFromVoice(voiceInput)
    }

    /**
     * Generate repayment schedule for a loan.
     *
     * @param loan The loan
     * @return List of [LoanRepayment] entries
     */
    fun getRepaymentSchedule(loan: Loan): List<LoanRepayment> {
        return loanTracker.generateRepaymentSchedule(loan)
    }

    /**
     * Record a loan repayment.
     *
     * @param loan The loan being repaid
     * @param amount Amount being repaid
     * @param repayments Current repayment schedule
     * @return Pair of updated loan and updated repayments
     */
    fun recordRepayment(
        loan: Loan,
        amount: Double,
        repayments: List<LoanRepayment>
    ): Pair<Loan, List<LoanRepayment>> {
        Timber.tag(TAG).d("Recording repayment: KSh %.0f for loan %d", amount, loan.id)
        return loanTracker.recordRepayment(loan, amount, repayments)
    }

    /**
     * Get loan alerts (upcoming and overdue payments).
     *
     * @param loans All active loans
     * @param repayments All repayment schedules
     * @param monthlyIncome Monthly income for debt-to-income ratio
     * @return [LoanTrackerResult] with alerts
     */
    fun getLoanAlerts(
        loans: List<Loan>,
        repayments: List<LoanRepayment>,
        monthlyIncome: Double = 0.0
    ): LoanTrackerResult {
        return loanTracker.getAlerts(loans, repayments, monthlyIncome)
    }

    // ═══════════════════════════════════════════════════════════════
    // GIVING/TITHE OPERATIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a giving entry.
     *
     * @param type Type of giving
     * @param amount Amount given
     * @param recipient Who received it
     * @param incomeAtTime Income at time of giving
     * @return New [GivingRecord]
     */
    fun recordGiving(
        type: GivingType,
        amount: Double,
        recipient: String = "",
        incomeAtTime: Double = 0.0
    ): GivingRecord {
        Timber.tag(TAG).d("Recording giving: %s KSh %.0f", type.name, amount)
        return titheTracker.recordGiving(type, amount, recipient, incomeAtTime)
    }

    /**
     * Record giving from voice input.
     *
     * @param voiceInput Voice text describing the giving
     * @return Parsed [GivingRecord] or null
     */
    fun recordGivingFromVoice(voiceInput: String): GivingRecord? {
        Timber.tag(TAG).d("Recording giving from voice: %s", voiceInput)
        return titheTracker.recordFromVoice(voiceInput)
    }

    /**
     * Analyze giving patterns.
     *
     * @param records All giving records
     * @param totalIncome Total income for the period
     * @return [GivingTrackerResult] with analysis
     */
    fun analyzeGiving(
        records: List<GivingRecord>,
        totalIncome: Double = 0.0
    ): GivingTrackerResult {
        return titheTracker.analyze(records, totalIncome)
    }

    /**
     * Calculate recommended tithe amount.
     *
     * @param income Income amount
     * @param givingType Type of giving
     * @return Recommended amount
     */
    fun recommendGiving(income: Double, givingType: GivingType = GivingType.TITHE): Double {
        return titheTracker.recommendTithe(income, givingType)
    }
}
