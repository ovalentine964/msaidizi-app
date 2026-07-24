package com.msaidizi.app.superagent.goals

// ═══════════════════════════════════════════════════════════════
// GOALS MODULE — Data Models
// ═══════════════════════════════════════════════════════════════
// All data classes and enums for goals, loans, and tithe tracking.
// ═══════════════════════════════════════════════════════════════

/**
 * Goal status.
 */
enum class GoalStatus {
    ACTIVE,
    COMPLETED,
    ABANDONED,
    PAUSED
}

/**
 * Goal category types.
 */
enum class GoalCategory {
    EQUIPMENT,
    INVENTORY,
    SAVINGS,
    DEBT,
    BUSINESS,
    EDUCATION,
    EMERGENCY,
    ASSET,
    OTHER
}

/**
 * Loan status.
 */
enum class LoanStatus {
    ACTIVE,
    COMPLETED,
    DEFAULTED,
    OVERDUE
}

/**
 * Loan repayment frequency.
 */
enum class RepaymentFrequency {
    DAILY,
    WEEKLY,
    BIWEEKLY,
    MONTHLY
}

/**
 * Repayment status.
 */
enum class RepaymentStatus {
    PENDING,
    PAID,
    OVERDUE,
    PARTIAL
}

/**
 * Giving type for tithe tracking.
 */
enum class GivingType {
    TITHE,
    OFFERING,
    ZAKAT,
    SADAQAH,
    CHARITY,
    OTHER
}

// ═══════════════════════════════════════════════════════════════
// GOAL MODELS
// ═══════════════════════════════════════════════════════════════

/**
 * A savings or achievement goal.
 *
 * @property id Unique identifier
 * @property name Goal name/description
 * @property targetAmount Target amount in KSh
 * @property currentAmount Amount saved/achieved so far
 * @property category Goal category
 * @property deadline Target completion date (Unix timestamp, 0 = no deadline)
 * @property status Current status
 * @property weeklyTarget Weekly contribution target
 * @property dailyTarget Daily contribution target
 * @property streak Current consecutive contribution days
 * @property bestStreak Best ever streak
 * @property deeperPurpose Why this goal matters (for motivation)
 * @property createdAt When the goal was created
 * @property updatedAt Last update timestamp
 */
data class Goal(
    val id: Long = 0,
    val name: String,
    val targetAmount: Double,
    val currentAmount: Double = 0.0,
    val category: GoalCategory = GoalCategory.SAVINGS,
    val deadline: Long = 0,
    val status: GoalStatus = GoalStatus.ACTIVE,
    val weeklyTarget: Double = 0.0,
    val dailyTarget: Double = 0.0,
    val streak: Int = 0,
    val bestStreak: Int = 0,
    val deeperPurpose: String = "",
    val createdAt: Long = System.currentTimeMillis() / 1000,
    val updatedAt: Long = System.currentTimeMillis() / 1000
) {
    /** Progress as a fraction (0.0 - 1.0) */
    val progress: Double
        get() = if (targetAmount > 0) (currentAmount / targetAmount).coerceIn(0.0, 1.0) else 0.0

    /** Progress as percentage (0-100) */
    val progressPercent: Int
        get() = (progress * 100).toInt().coerceIn(0, 100)

    /** Remaining amount to reach goal */
    val remaining: Double
        get() = maxOf(0.0, targetAmount - currentAmount)

    /** Whether the goal has been reached */
    val isCompleted: Boolean
        get() = currentAmount >= targetAmount

    /** Days until deadline (negative if past) */
    val daysUntilDeadline: Int
        get() {
            if (deadline <= 0) return Int.MAX_VALUE
            return ((deadline - System.currentTimeMillis() / 1000) / 86_400).toInt()
        }
}

/**
 * A progress entry for a goal.
 *
 * @property id Unique identifier
 * @property goalId Associated goal ID
 * @property amount Amount contributed
 * @property note Optional note
 * @property timestamp When the contribution was made
 */
data class GoalProgress(
    val id: Long = 0,
    val goalId: Long,
    val amount: Double,
    val note: String = "",
    val timestamp: Long = System.currentTimeMillis() / 1000
)

/**
 * A milestone reached for a goal.
 *
 * @property id Unique identifier
 * @property goalId Associated goal ID
 * @property percentage Milestone percentage (0.25, 0.50, 0.75, 1.00)
 * @property reachedAt When the milestone was reached
 */
data class GoalMilestone(
    val id: Long = 0,
    val goalId: Long,
    val percentage: Double,
    val reachedAt: Long = System.currentTimeMillis() / 1000
)

/**
 * Goal progress forecast.
 *
 * @property goal The goal being forecast
 * @property projectedCompletionDate Estimated completion date
 * @property daysToCompletion Estimated days to complete
 * @property dailyRequired Daily amount needed to meet deadline
 * @property weeklyRequired Weekly amount needed to meet deadline
 * @property isOnTrack Whether current pace meets deadline
 * @property confidenceLevel Forecast confidence (0.0-1.0)
 * @property message Human-readable forecast in Swahili
 */
data class GoalForecast(
    val goal: Goal,
    val projectedCompletionDate: Long,
    val daysToCompletion: Int,
    val dailyRequired: Double,
    val weeklyRequired: Double,
    val isOnTrack: Boolean,
    val confidenceLevel: Double,
    val message: String
)

// ═══════════════════════════════════════════════════════════════
// LOAN MODELS
// ═══════════════════════════════════════════════════════════════

/**
 * A loan record.
 *
 * @property id Unique identifier
 * @property amount Principal loan amount in KSh
 * @property purpose What the loan is for
 * @property lender Who lent the money
 * @property interestRate Monthly interest rate (0.0-1.0)
 * @property totalDue Total amount due (principal + interest)
 * @property startDate Loan start date (Unix timestamp)
 * @property endDate Loan end date (Unix timestamp)
 * @property repaymentFrequency How often payments are due
 * @property totalRepaid Amount repaid so far
 * @property status Current status
 * @property createdAt When the loan was recorded
 * @property updatedAt Last update timestamp
 */
data class Loan(
    val id: Long = 0,
    val amount: Double,
    val purpose: String,
    val lender: String = "",
    val interestRate: Double = 0.0,
    val totalDue: Double = 0.0,
    val startDate: Long,
    val endDate: Long = 0,
    val repaymentFrequency: RepaymentFrequency = RepaymentFrequency.MONTHLY,
    val totalRepaid: Double = 0.0,
    val status: LoanStatus = LoanStatus.ACTIVE,
    val createdAt: Long = System.currentTimeMillis() / 1000,
    val updatedAt: Long = System.currentTimeMillis() / 1000
) {
    /** Remaining balance */
    val balance: Double
        get() = maxOf(0.0, totalDue - totalRepaid)

    /** Repayment progress (0.0 - 1.0) */
    val repaymentProgress: Double
        get() = if (totalDue > 0) (totalRepaid / totalDue).coerceIn(0.0, 1.0) else 0.0

    /** Repayment progress as percentage */
    val repaymentPercent: Int
        get() = (repaymentProgress * 100).toInt().coerceIn(0, 100)

    /** Days until loan is due (negative if overdue) */
    val daysUntilDue: Int
        get() {
            if (endDate <= 0) return Int.MAX_VALUE
            return ((endDate - System.currentTimeMillis() / 1000) / 86_400).toInt()
        }

    /** Whether the loan is overdue */
    val isOverdue: Boolean
        get() = status == LoanStatus.OVERDUE ||
            (endDate > 0 && daysUntilDue < 0 && status == LoanStatus.ACTIVE)

    /** Debt-to-income calculation helper */
    val monthlyPayment: Double
        get() {
            if (endDate <= 0 || startDate >= endDate) return balance
            val months = ((endDate - startDate) / (30.0 * 86_400)).coerceAtLeast(1.0)
            return totalDue / months
        }
}

/**
 * A single loan repayment.
 *
 * @property id Unique identifier
 * @property loanId Associated loan ID
 * @property amount Expected payment amount
 * @property dueDate When payment is due (Unix timestamp)
 * @property paidDate When payment was actually made (null if not paid)
 * @property paidAmount Actual amount paid (null if not paid)
 * @property status Payment status
 * @property penalty Late payment penalty
 */
data class LoanRepayment(
    val id: Long = 0,
    val loanId: Long,
    val amount: Double,
    val dueDate: Long,
    val paidDate: Long? = null,
    val paidAmount: Double? = null,
    val status: RepaymentStatus = RepaymentStatus.PENDING,
    val penalty: Double = 0.0
)

/**
 * Loan tracking result with alerts.
 *
 * @property loans All active loans
 * @property upcomingPayments Payments due soon
 * @property overduePayments Overdue payments
 * @property totalDebt Total outstanding debt
 * @property debtToIncomeRatio Debt as fraction of income
 * @property message Human-readable summary in Swahili
 */
data class LoanTrackerResult(
    val loans: List<Loan>,
    val upcomingPayments: List<LoanRepayment>,
    val overduePayments: List<LoanRepayment>,
    val totalDebt: Double,
    val debtToIncomeRatio: Double,
    val message: String
)

// ═══════════════════════════════════════════════════════════════
// TITHE MODELS
// ═══════════════════════════════════════════════════════════════

/**
 * A giving record (tithe, offering, charity, etc.).
 *
 * @property id Unique identifier
 * @property type Type of giving
 * @property amount Amount given in KSh
 * @property recipient Who received the giving
 * @property date When the giving was made (Unix timestamp, milliseconds)
 * @property category Category (e.g., "Sunday service", "Ramadan")
 * @property notes Optional notes
 * @property incomeAtTime Income at the time of giving (for calculating %)
 * @property inputMethod How the giving was recorded
 * @property createdAt When the record was created
 */
data class GivingRecord(
    val id: Long = 0,
    val type: GivingType,
    val amount: Double,
    val recipient: String = "",
    val date: Long = System.currentTimeMillis(),
    val category: String = "",
    val notes: String = "",
    val incomeAtTime: Double = 0.0,
    val inputMethod: String = "VOICE",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Tithe/giving tracking result.
 *
 * @property totalGiving Total amount given
 * @property givingByType Breakdown by giving type
 * @property monthlyAverage Average monthly giving
 * @property givingAsIncomePercent Giving as percentage of income
 * @property consistencyScore How consistent giving is (0-100)
 * @property streak Current consecutive giving periods
 * @property message Human-readable summary in Swahili
 */
data class GivingTrackerResult(
    val totalGiving: Double,
    val givingByType: Map<GivingType, Double>,
    val monthlyAverage: Double,
    val givingAsIncomePercent: Double,
    val consistencyScore: Double,
    val streak: Int,
    val message: String
)
