package com.msaidizi.app.finance

import com.msaidizi.app.core.database.LoanDao
import com.msaidizi.app.core.model.LoanRecord
import com.msaidizi.app.core.model.LoanRepayment
import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import timber.log.Timber
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Loan management and repayment tracking for informal workers.
 *
 * When a worker takes a loan:
 * 1. Record the loan (amount, purpose, interest rate, repayment schedule)
 * 2. Track how the money is spent (ensure it goes to business)
 * 3. Set up repayment reminders
 * 4. Monitor repayment progress
 * 5. Warn if loan purpose is being violated
 *
 * The key insight: most informal workers don't default because they can't pay.
 * They default because they lose track of payments, spend loan money on
 * personal items, or don't understand the true cost of borrowing.
 * Msaidizi is the loan management partner that prevents all three.
 *
 * Academic foundations:
 * - ECO 206 (Microfinance): Loan repayment behavior, group lending theory,
 *   Stiglitz-Weiss adverse selection, moral hazard in micro-lending
 * - ECO 424 (Advanced Econometrics): Heckman correction for selection bias
 *   in loan uptake, endogeneity of loan amount and business outcomes
 * - STA 341 (Estimation): Default probability estimation via logistic
 *   regression, Bayesian updating of repayment likelihood
 * - ECO 210 (Quantitative Methods): Cash flow optimization for repayment
 *   scheduling, linear programming for debt prioritization
 * - FIN 201 (Corporate Finance): Debt service coverage ratio, amortization
 */
class LoanManager(
    private val loanDao: LoanDao
) {

    companion object {
        private const val TAG = "LoanManager"

        /** Warning threshold — days before payment due date */
        private const val REMINDER_DAYS_BEFORE = 3

        /** Critical threshold — days after which loan is overdue */
        private const val OVERDUE_THRESHOLD_DAYS = 1

        /** Minimum percentage of loan that must go to stated purpose */
        private const val PURPOSE_COMPLIANCE_THRESHOLD = 0.70 // 70%

        /** Maximum recommended debt-to-income ratio */
        private const val MAX_DEBT_TO_INCOME = 0.40 // 40%
    }

    // ═══════════════════════════════════════════════════════════════
    // DATA MODELS
    // ═══════════════════════════════════════════════════════════════

    data class Loan(
        val id: String = UUID.randomUUID().toString(),
        val amount: Double,
        val purpose: String, // "Buy inventory", "Expand shop", "Buy equipment", etc.
        val interestRate: Double, // Annual rate as decimal (e.g., 0.15 for 15%)
        val repaymentSchedule: List<Repayment>,
        val startDate: Long, // Unix timestamp in seconds
        val endDate: Long,
        val lender: String,
        val status: LoanStatus = LoanStatus.ACTIVE,
        val totalRepaid: Double = 0.0,
        val penaltyAmount: Double = 0.0
    ) {
        /** Total amount to repay (principal + interest) */
        val totalToRepay: Double
            get() = repaymentSchedule.sumOf { it.amount }

        /** Remaining balance */
        val balance: Double
            get() = totalToRepay - totalRepaid + penaltyAmount

        /** Number of payments completed */
        val paymentsCompleted: Int
            get() = repaymentSchedule.count { it.status == RepaymentStatus.PAID }

        /** Number of payments remaining */
        val paymentsRemaining: Int
            get() = repaymentSchedule.count {
                it.status == RepaymentStatus.PENDING || it.status == RepaymentStatus.OVERDUE
            }

        /** Next unpaid payment */
        val nextPayment: Repayment?
            get() = repaymentSchedule
                .filter { it.status == RepaymentStatus.PENDING || it.status == RepaymentStatus.OVERDUE }
                .minByOrNull { it.dueDate }

        /** True cost of the loan (total interest paid) */
        val totalInterest: Double
            get() = totalToRepay - amount

        /** Interest as percentage of principal */
        val effectiveInterestRate: Double
            get() = if (amount > 0) (totalInterest / amount) * 100 else 0.0
    }

    data class Repayment(
        val id: String = UUID.randomUUID().toString(),
        val amount: Double,
        val dueDate: Long, // Unix timestamp in seconds
        val paidDate: Long? = null,
        val paidAmount: Double? = null,
        val status: RepaymentStatus = RepaymentStatus.PENDING,
        val penalty: Double = 0.0
    )

    enum class LoanStatus {
        ACTIVE,     // Loan is being repaid
        PAID,       // Fully repaid
        DEFAULTED,  // Missed too many payments
        OVERDUE     // Has overdue payments but not yet defaulted
    }

    enum class RepaymentStatus {
        PENDING,    // Not yet due
        PAID,       // Paid on time
        OVERDUE,    // Past due date, not paid
        PARTIAL     // Partially paid
    }

    /**
     * Result of loan purpose compliance check.
     */
    data class PurposeCompliance(
        val isCompliant: Boolean,
        val businessSpentPercent: Double,
        val personalSpentPercent: Double,
        val businessTransactions: List<Transaction>,
        val personalTransactions: List<Transaction>,
        val message: String
    )

    /**
     * Loan progress summary.
     */
    data class LoanProgress(
        val loanId: String,
        val paymentsCompleted: Int,
        val totalPayments: Int,
        val amountPaid: Double,
        val amountRemaining: Double,
        val nextPaymentAmount: Double?,
        val nextPaymentDue: Long?,
        val daysUntilNextPayment: Int?,
        val isOnTrack: Boolean,
        val message: String
    )

    // ═══════════════════════════════════════════════════════════════
    // STATE — In-memory loan store (would be Room DB in production)
    // ═══════════════════════════════════════════════════════════════

    private val loans = mutableMapOf<String, Loan>()

    // ═══════════════════════════════════════════════════════════════
    // LOAN RECORDING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a new loan with its repayment schedule.
     *
     * This is called when a worker says:
     * "Nimechukua mkopo wa KSh 10,000 kutoka M-Shwari"
     *
     * @param loan The loan to record
     * @return The recorded loan with generated ID
     */
    suspend fun recordLoan(loan: Loan): Loan {
        Timber.tag(TAG).d("Recording loan: KSh %.0f from %s for '%s'",
            loan.amount, loan.lender, loan.purpose)

        // Persist to Room
        val entity = LoanRecord(
            amount = loan.amount,
            purpose = loan.purpose,
            lender = loan.lender,
            interestRate = loan.interestRate,
            totalDue = loan.totalToRepay,
            startDate = loan.startDate,
            endDate = loan.endDate,
            repaymentFrequency = "MONTHLY",
            totalRepaid = loan.totalRepaid,
            status = loan.status.name,
            createdAt = System.currentTimeMillis() / 1000,
            updatedAt = System.currentTimeMillis() / 1000
        )
        val roomId = loanDao.insertLoan(entity)

        // Persist repayment schedule
        for (repayment in loan.repaymentSchedule) {
            loanDao.insertRepayment(
                LoanRepayment(
                    loanId = roomId,
                    amount = repayment.amount,
                    dueDate = repayment.dueDate,
                    paidDate = repayment.paidDate,
                    paidAmount = repayment.paidAmount,
                    status = repayment.status.name,
                    penalty = repayment.penalty
                )
            )
        }

        val persistedLoan = loan.copy(id = roomId.toString())
        loans[roomId.toString()] = persistedLoan

        Timber.tag(TAG).d("Loan recorded. %d payments scheduled, total repayment: KSh %.0f",
            loan.repaymentSchedule.size, loan.totalToRepay)

        return persistedLoan
    }

    /**
     * Generate a standard repayment schedule for a loan.
     *
     * Creates equal monthly payments with interest.
     *
     * ECO 210 (Quantitative Methods): Amortization formula
     * PMT = P * [r(1+r)^n] / [(1+r)^n - 1]
     *
     * @param principal Loan amount
     * @param annualInterestRate Annual interest rate (e.g., 0.15 for 15%)
     * @param termMonths Number of monthly payments
     * @param startDate Loan start date (Unix timestamp)
     * @return List of repayment installments
     */
    fun generateRepaymentSchedule(
        principal: Double,
        annualInterestRate: Double,
        termMonths: Int,
        startDate: Long
    ): List<Repayment> {
        val monthlyRate = annualInterestRate / 12.0
        val monthlyPayment = if (monthlyRate > 0) {
            principal * (monthlyRate * Math.pow(1 + monthlyRate, termMonths.toDouble())) /
                (Math.pow(1 + monthlyRate, termMonths.toDouble()) - 1)
        } else {
            principal / termMonths
        }

        val schedule = mutableListOf<Repayment>()
        var remainingBalance = principal
        val monthInSeconds = 30L * 24 * 60 * 60 // ~30 days

        for (i in 1..termMonths) {
            val interestPortion = remainingBalance * monthlyRate
            val principalPortion = monthlyPayment - interestPortion
            val paymentAmount = if (i == termMonths) {
                // Last payment — adjust for rounding
                remainingBalance + interestPortion
            } else {
                monthlyPayment
            }

            schedule.add(
                Repayment(
                    amount = paymentAmount,
                    dueDate = startDate + (i * monthInSeconds),
                    status = RepaymentStatus.PENDING
                )
            )

            remainingBalance -= principalPortion
        }

        Timber.tag(TAG).d("Generated %d payments of KSh %.0f each", termMonths, monthlyPayment)
        return schedule
    }

    // ═══════════════════════════════════════════════════════════════
    // REPAYMENT RECORDING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a loan repayment.
     *
     * Called when worker says: "Nimelipa KSh 5,000 ya mkopo"
     * or when M-Pesa auto-detects a loan payment.
     *
     * @param loanId The loan to apply payment to
     * @param amount Amount paid in KSh
     * @return Updated loan, or null if loan not found
     */
    suspend fun recordRepayment(loanId: String, amount: Double): Loan? {
        val loan = loans[loanId] ?: run {
            Timber.tag(TAG).w("Loan not found: %s", loanId)
            return null
        }

        val now = System.currentTimeMillis() / 1000
        var remainingPayment = amount
        val updatedSchedule = loan.repaymentSchedule.toMutableList()

        // Apply payment to oldest unpaid installment first (FIFO)
        for (i in updatedSchedule.indices) {
            val repayment = updatedSchedule[i]
            if (repayment.status == RepaymentStatus.PAID) continue
            if (remainingPayment <= 0) break

            val dueAmount = repayment.amount + repayment.penalty
            val appliedAmount = minOf(remainingPayment, dueAmount)
            val isFullyPaid = appliedAmount >= dueAmount

            updatedSchedule[i] = repayment.copy(
                paidDate = now,
                paidAmount = (repayment.paidAmount ?: 0.0) + appliedAmount,
                status = if (isFullyPaid) RepaymentStatus.PAID else RepaymentStatus.PARTIAL
            )

            remainingPayment -= appliedAmount
        }

        val newTotalRepaid = loan.totalRepaid + (amount - remainingPayment)
        val allPaid = updatedSchedule.all { it.status == RepaymentStatus.PAID }
        val hasOverdue = updatedSchedule.any { it.status == RepaymentStatus.OVERDUE }

        val newStatus = when {
            allPaid -> LoanStatus.PAID
            hasOverdue -> LoanStatus.OVERDUE
            else -> loan.status
        }

        val updatedLoan = loan.copy(
            repaymentSchedule = updatedSchedule,
            totalRepaid = newTotalRepaid,
            status = newStatus
        )

        // Persist repayment to Room
        val roomId = loanId.toLongOrNull()
        if (roomId != null) {
            loanDao.addRepayment(roomId, amount - remainingPayment)
            if (allPaid) {
                loanDao.updateStatus(roomId, "PAID")
            } else if (hasOverdue) {
                loanDao.updateStatus(roomId, "OVERDUE")
            }
        }

        loans[loanId] = updatedLoan

        Timber.tag(TAG).d("Repayment recorded: KSh %.0f. Balance: KSh %.0f",
            amount, updatedLoan.balance)

        return updatedLoan
    }

    // ═══════════════════════════════════════════════════════════════
    // QUERY METHODS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get all upcoming payments across all active loans.
     *
     * @return List of upcoming repayments sorted by due date
     */
    suspend fun getUpcomingPayments(): List<Repayment> {
        val now = System.currentTimeMillis() / 1000
        return loans.values
            .filter { it.status == LoanStatus.ACTIVE || it.status == LoanStatus.OVERDUE }
            .flatMap { it.repaymentSchedule }
            .filter {
                it.status == RepaymentStatus.PENDING || it.status == RepaymentStatus.OVERDUE
            }
            .sortedBy { it.dueDate }
    }

    /**
     * Get loan progress as a human-readable string.
     *
     * "3 of 6 payments made. Next: KSh 5,000 due in 5 days"
     *
     * @param loanId The loan to check
     * @return Progress string in Swahili
     */
    suspend fun getLoanProgress(loanId: String): String {
        val loan = loans[loanId] ?: return "Mkopo haujapatikana."

        val progress = getLoanProgressDetail(loanId)
        return progress?.message ?: "Mkopo haujapatikana."
    }

    /**
     * Get detailed loan progress.
     */
    suspend fun getLoanProgressDetail(loanId: String): LoanProgress? {
        val loan = loans[loanId] ?: return null

        val now = System.currentTimeMillis() / 1000
        val nextPayment = loan.nextPayment
        val daysUntil = nextPayment?.let {
            ((it.dueDate - now) / 86400).toInt()
        }

        val message = buildString {
            append("Mkopo wa KSh ${formatAmount(loan.amount.toInt())} kutoka ${loan.lender}\n")
            append("Malipo: ${loan.paymentsCompleted} ya ${loan.repaymentSchedule.size} yamelipwa\n")
            append("Salio: KSh ${formatAmount(loan.balance.toInt())}\n")

            if (nextPayment != null) {
                append("Linalofuata: KSh ${formatAmount(nextPayment.amount.toInt())}")
                when {
                    daysUntil == null -> {}
                    daysUntil < 0 -> append(" (IMEPITWA siku ${abs(daysUntil)}!)")
                    daysUntil == 0 -> append(" (LEO!)")
                    daysUntil == 1 -> append(" (KESHO)")
                    daysUntil <= 7 -> append(" (siku $daysUntil)")
                    else -> append(" (baada ya siku $daysUntil)")
                }
            } else if (loan.status == LoanStatus.PAID) {
                append("✅ Mkopo umelipwa kikamilifu!")
            }
        }

        return LoanProgress(
            loanId = loanId,
            paymentsCompleted = loan.paymentsCompleted,
            totalPayments = loan.repaymentSchedule.size,
            amountPaid = loan.totalRepaid,
            amountRemaining = loan.balance,
            nextPaymentAmount = nextPayment?.amount,
            nextPaymentDue = nextPayment?.dueDate,
            daysUntilNextPayment = daysUntil,
            isOnTrack = loan.repaymentSchedule.none { it.status == RepaymentStatus.OVERDUE },
            message = message
        )
    }

    /**
     * Get all active loans.
     */
    suspend fun getActiveLoans(): List<Loan> {
        // Load from Room first
        val entities = loanDao.getActive()
        return entities.mapNotNull { entity ->
            val cached = loans[entity.id.toString()]
            if (cached != null) return@mapNotNull cached

            val repayments = loanDao.getRepayments(entity.id).map { r ->
                Repayment(
                    id = r.id.toString(),
                    amount = r.amount,
                    dueDate = r.dueDate,
                    paidDate = r.paidDate,
                    paidAmount = r.paidAmount,
                    status = try { RepaymentStatus.valueOf(r.status) } catch (_: Exception) { RepaymentStatus.PENDING },
                    penalty = r.penalty
                )
            }
            Loan(
                id = entity.id.toString(),
                amount = entity.amount,
                purpose = entity.purpose,
                interestRate = entity.interestRate,
                repaymentSchedule = repayments,
                startDate = entity.startDate,
                endDate = entity.endDate,
                lender = entity.lender,
                status = try { LoanStatus.valueOf(entity.status) } catch (_: Exception) { LoanStatus.ACTIVE },
                totalRepaid = entity.totalRepaid
            ).also { loans[it.id] = it }
        }
    }

    /**
     * Get a specific loan.
     */
    suspend fun getLoan(loanId: String): Loan? {
        loans[loanId]?.let { return it }
        val roomId = loanId.toLongOrNull() ?: return null
        val entity = loanDao.getById(roomId) ?: return null
        val repayments = loanDao.getRepayments(entity.id).map { r ->
            Repayment(
                id = r.id.toString(),
                amount = r.amount,
                dueDate = r.dueDate,
                paidDate = r.paidDate,
                paidAmount = r.paidAmount,
                status = try { RepaymentStatus.valueOf(r.status) } catch (_: Exception) { RepaymentStatus.PENDING },
                penalty = r.penalty
            )
        }
        return Loan(
            id = entity.id.toString(),
            amount = entity.amount,
            purpose = entity.purpose,
            interestRate = entity.interestRate,
            repaymentSchedule = repayments,
            startDate = entity.startDate,
            endDate = entity.endDate,
            lender = entity.lender,
            status = try { LoanStatus.valueOf(entity.status) } catch (_: Exception) { LoanStatus.ACTIVE },
            totalRepaid = entity.totalRepaid
        ).also { loans[it.id] = it }
    }

    // ═══════════════════════════════════════════════════════════════
    // LOAN PURPOSE VERIFICATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Check if loan money is being used for its stated business purpose.
     *
     * This is the key differentiator — most loan apps give money and
     * forget about it. Msaidizi ensures the loan actually helps the business.
     *
     * How it works:
     * 1. Look at transactions after loan was disbursed
     * 2. Classify each as business or personal spending
     * 3. Calculate compliance percentage
     * 4. Warn if too much went to personal use
     *
     * ECO 206 (Microfinance): Loan diversion is the #1 cause of default
     * in micro-lending. Monitoring purpose compliance reduces default rates.
     *
     * @param loanId The loan to check
     * @param transactions Transactions since loan was disbursed
     * @return PurposeCompliance with detailed breakdown
     */
    fun checkLoanPurpose(loanId: String, transactions: List<Transaction>): PurposeCompliance {
        val loan = loans[loanId] ?: return PurposeCompliance(
            isCompliant = false,
            businessSpentPercent = 0.0,
            personalSpentPercent = 0.0,
            businessTransactions = emptyList(),
            personalTransactions = emptyList(),
            message = "Mkopo haujapatikana."
        )

        // Filter transactions after loan start date
        val postLoanTransactions = transactions.filter {
            it.createdAt >= loan.startDate &&
            (it.type == TransactionType.PURCHASE || it.type == TransactionType.EXPENSE)
        }

        if (postLoanTransactions.isEmpty()) {
            return PurposeCompliance(
                isCompliant = true,
                businessSpentPercent = 0.0,
                personalSpentPercent = 0.0,
                businessTransactions = emptyList(),
                personalTransactions = emptyList(),
                message = "Bado hakuna matumizi yaliyorekodiwa tangu mkopo ulipotolewa."
            )
        }

        // Classify transactions as business or personal
        val businessCategories = setOf(
            "food", "agriculture", "inventory", "stock", "supplies",
            "equipment", "wholesale", "raw materials"
        )
        val personalCategories = setOf(
            "entertainment", "personal", "clothing", "electronics",
            "airtime", "data", "restaurant", "leisure"
        )

        val businessTxns = mutableListOf<Transaction>()
        val personalTxns = mutableListOf<Transaction>()

        for (txn in postLoanTransactions) {
            val category = txn.category.lowercase()
            val item = txn.item.lowercase()

            val isBusiness = when {
                // Category-based classification
                businessCategories.any { category.contains(it) } -> true
                personalCategories.any { category.contains(it) } -> false
                // Item-based heuristics
                item.contains("stock") || item.contains("bidhaa") ||
                    item.contains("vifaa") || item.contains("mbegu") ||
                    item.contains("mbolea") || item.contains("supplier") -> true
                item.contains("chakula") || item.contains("nyumba") ||
                    item.contains("sherehe") || item.contains("nguo") -> false
                // Default: purchases are likely business, expenses are mixed
                txn.type == TransactionType.PURCHASE -> true
                else -> true // Optimistic default
            }

            if (isBusiness) businessTxns.add(txn) else personalTxns.add(txn)
        }

        val totalSpent = postLoanTransactions.sumOf { it.totalAmount }
        val businessSpent = businessTxns.sumOf { it.totalAmount }
        val personalSpent = personalTxns.sumOf { it.totalAmount }

        val businessPercent = if (totalSpent > 0) businessSpent / totalSpent else 1.0
        val personalPercent = if (totalSpent > 0) personalSpent / totalSpent else 0.0

        val isCompliant = businessPercent >= PURPOSE_COMPLIANCE_THRESHOLD

        val message = when {
            postLoanTransactions.isEmpty() ->
                "Bado hakuna matumizi yaliyorekodiwa."

            isCompliant && personalPercent < 0.1 ->
                "✅ Umefanya vizuri! ${(businessPercent * 100).roundToInt()}% ya mkopo " +
                "imetumika kwenye biashara kama ulivyopanga."

            isCompliant ->
                "✅ Sawa! ${(businessPercent * 100).roundToInt()}% ya mkopo imetumika kwenye biashara. " +
                "Lakini ${(personalPercent * 100).roundToInt()}% imeenda kwa matumizi binafsi. " +
                "Jaribu kupunguza matumizi binafsi."

            else ->
                "⚠️ Onyo! ${(personalPercent * 100).roundToInt()}% ya mkopo wako " +
                "imetumika kwa matumizi binafsi, si biashara! " +
                "Mkopo ulikusaidia kununua ${loan.purpose}. " +
                "Tumia pesa iliyobaki kwenye biashara yako."
        }

        Timber.tag(TAG).d("Purpose check: %.0f%% business, %.0f%% personal, compliant=%s",
            businessPercent * 100, personalPercent * 100, isCompliant)

        return PurposeCompliance(
            isCompliant = isCompliant,
            businessSpentPercent = businessPercent,
            personalSpentPercent = personalPercent,
            businessTransactions = businessTxns,
            personalTransactions = personalTxns,
            message = message
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // REPAYMENT REMINDERS — Voice-ready
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate a voice-ready repayment reminder.
     *
     * Designed to be spoken by TTS in Swahili.
     * "Leo ni siku ya kulipa mkopo. KSh 5,000 inapaswa kulipwa kwa M-Shwari."
     *
     * @return Swahili reminder message
     */
    suspend fun getRepaymentReminder(): String {
        val now = System.currentTimeMillis() / 1000
        val upcomingPayments = getUpcomingPayments()

        if (upcomingPayments.isEmpty()) {
            return "Hakuna malipo ya mkopo yanayokuja. Umefanya vizuri!"
        }

        // Find the most urgent payment
        val urgentPayment = upcomingPayments.first()
        val daysUntil = ((urgentPayment.dueDate - now) / 86400).toInt()

        // Find which loan this belongs to
        val associatedLoan = loans.values.find { loan ->
            loan.repaymentSchedule.any { it.id == urgentPayment.id }
        }

        return when {
            daysUntil < 0 ->
                "⚠️ Malipo ya mkopo yamepita siku ${abs(daysUntil)}! " +
                "Lipa KSh ${formatAmount(urgentPayment.amount.toInt())} haraka ili kuepuka adhabu."

            daysUntil == 0 ->
                "🔔 Leo ni siku ya kulipa mkopo! " +
                "Lipa KSh ${formatAmount(urgentPayment.amount.toInt())} kwa ${associatedLoan?.lender ?: "mkopaji wako"}."

            daysUntil == 1 ->
                "🔔 Kesho ni siku ya kulipa mkopo. " +
                "Jiandae kulipa KSh ${formatAmount(urgentPayment.amount.toInt())}."

            daysUntil <= REMINDER_DAYS_BEFORE ->
                "📋 Malipo ya mkopo yatakuja baada ya siku $daysUntil. " +
                "Lipa KSh ${formatAmount(urgentPayment.amount.toInt())} kwa ${associatedLoan?.lender ?: "mkopaji wako"}."

            else ->
                "Malipo yajayo ya mkopo ni baada ya siku $daysUntil. " +
                "Kiasi: KSh ${formatAmount(urgentPayment.amount.toInt())}."
        }
    }

    /**
     * Generate loan status summary for daily briefing.
     *
     * Includes loan reminders in the morning CFO briefing.
     *
     * @return Swahili loan status for briefing, or null if no active loans
     */
    suspend fun getBriefingLoanStatus(): String? {
        val activeLoans = getActiveLoans()
        if (activeLoans.isEmpty()) return null

        val now = System.currentTimeMillis() / 1000

        return buildString {
            for (loan in activeLoans) {
                val nextPayment = loan.nextPayment ?: continue
                val daysUntil = ((nextPayment.dueDate - now) / 86400).toInt()

                when {
                    daysUntil < 0 ->
                        append("⚠️ Mkopo wa ${loan.lender}: malipo yamepita! Lipa KSh ${formatAmount(nextPayment.amount.toInt())}. ")
                    daysUntil == 0 ->
                        append("🔔 Leo lipa KSh ${formatAmount(nextPayment.amount.toInt())} ya mkopo wa ${loan.lender}. ")
                    daysUntil <= 3 ->
                        append("📋 ${loan.lender}: lipa KSh ${formatAmount(nextPayment.amount.toInt())} baada ya siku $daysUntil. ")
                    else -> {} // Don't clutter briefing with distant payments
                }
            }

            // Add purpose compliance summary
            val totalBalance = activeLoans.sumOf { it.balance }
            if (totalBalance > 0) {
                append("\nMikopo yote: salio KSh ${formatAmount(totalBalance.toInt())}.")
            }
        }.trim().ifEmpty { null }
    }

    /**
     * Generate a full loan report.
     *
     * "Ripoti ya mkopo" voice command response.
     *
     * @return Comprehensive Swahili loan report
     */
    suspend fun getLoanReport(): String {
        val allLoans = loadAllLoans()
        if (allLoans.isEmpty()) {
            return "Huna mkopo wowote uliorekodiwa. Hii ni habari nzuri!"
        }

        val activeLoans = allLoans.filter {
            it.status == LoanStatus.ACTIVE || it.status == LoanStatus.OVERDUE
        }
        val paidLoans = allLoans.filter { it.status == LoanStatus.PAID }

        return buildString {
            append("📋 Ripoti ya Mikopo\n\n")

            if (activeLoans.isNotEmpty()) {
                append("Mikopo hai (${activeLoans.size}):\n")
                for (loan in activeLoans) {
                    val progress = loan.paymentsCompleted
                    val total = loan.repaymentSchedule.size
                    val paidPercent = if (total > 0) (progress * 100 / total) else 0

                    append("• ${loan.lender}: KSh ${formatAmount(loan.amount.toInt())}\n")
                    append("  Kusudi: ${loan.purpose}\n")
                    append("  Malipo: $progress/$total ($paidPercent%)\n")
                    append("  Salio: KSh ${formatAmount(loan.balance.toInt())}\n")

                    val next = loan.nextPayment
                    if (next != null) {
                        val days = ((next.dueDate - System.currentTimeMillis() / 1000) / 86400).toInt()
                        append("  Linalofuata: KSh ${formatAmount(next.amount.toInt())}")
                        when {
                            days < 0 -> append(" (IMEPITWA!)")
                            days == 0 -> append(" (LEO)")
                            days <= 7 -> append(" (siku $days)")
                            else -> append("")
                        }
                        append("\n")
                    }
                }
            }

            if (paidLoans.isNotEmpty()) {
                append("\nMikopo iliyolipwa (${paidLoans.size}):\n")
                for (loan in paidLoans) {
                    append("• ${loan.lender}: KSh ${formatAmount(loan.amount.toInt())} — IMELIPWA ✅\n")
                }
            }

            // Total debt summary
            val totalDebt = activeLoans.sumOf { it.balance }
            val totalMonthlyPayments = activeLoans.sumOf { loan ->
                loan.repaymentSchedule
                    .filter { it.status == RepaymentStatus.PENDING || it.status == RepaymentStatus.OVERDUE }
                    .sumOf { it.amount }
            }

            if (activeLoans.isNotEmpty()) {
                append("\nJumla ya deni: KSh ${formatAmount(totalDebt.toInt())}\n")
                append("Malipo ya mwezi: ~KSh ${formatAmount(totalMonthlyPayments.toInt())}")
            }
        }
    }

    /**
     * Check debt-to-income ratio.
     *
     * FIN 201: Debt service coverage ratio.
     * Warns if loan repayments exceed healthy percentage of income.
     *
     * @param monthlyIncome Average monthly income in KSh
     * @return Warning message if ratio is too high, null if healthy
     */
    suspend fun checkDebtToIncome(monthlyIncome: Double): String? {
        if (monthlyIncome <= 0) return null

        val activeLoans = getActiveLoans()
        if (activeLoans.isEmpty()) return null

        val now = System.currentTimeMillis() / 1000
        val monthInSeconds = 30L * 24 * 60 * 60

        val monthlyPayments = activeLoans.sumOf { loan ->
            loan.repaymentSchedule
                .filter {
                    (it.status == RepaymentStatus.PENDING || it.status == RepaymentStatus.OVERDUE) &&
                    it.dueDate <= now + monthInSeconds
                }
                .sumOf { it.amount }
        }

        val ratio = monthlyPayments / monthlyIncome

        return when {
            ratio > MAX_DEBT_TO_INCOME ->
                "⚠️ Tahadhari: Malipo ya mkopo ni ${(ratio * 100).roundToInt()}% ya mapato yako. " +
                "Inashauriwa isizidi ${(MAX_DEBT_TO_INCOME * 100).roundToInt()}%. " +
                "Fikiria kuongeza mauzo au kupunguza matumizi."
            ratio > 0.25 ->
                "Malipo ya mkopo ni ${(ratio * 100).roundToInt()}% ya mapato yako. Iko salama, lakini angalia."
            else -> null // Healthy ratio, no warning needed
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // OVERDUE DETECTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Check for overdue payments and update statuses.
     *
     * Should be called daily (e.g., in morning briefing).
     *
     * @return List of overdue payment messages
     */
    suspend fun checkOverduePayments(): List<String> {
        val now = System.currentTimeMillis() / 1000
        val overdueMessages = mutableListOf<String>()

        for ((loanId, loan) in loans) {
            if (loan.status != LoanStatus.ACTIVE && loan.status != LoanStatus.OVERDUE) continue

            var hasOverdue = false
            val updatedSchedule = loan.repaymentSchedule.toMutableList()

            for (i in updatedSchedule.indices) {
                val repayment = updatedSchedule[i]
                if (repayment.status == RepaymentStatus.PENDING && repayment.dueDate < now) {
                    updatedSchedule[i] = repayment.copy(status = RepaymentStatus.OVERDUE)
                    hasOverdue = true

                    val daysOverdue = ((now - repayment.dueDate) / 86400).toInt()
                    overdueMessages.add(
                        "⚠️ Mkopo wa ${loan.lender}: malipo ya KSh ${formatAmount(repayment.amount.toInt())} " +
                        "yamepita siku $daysOverdue. Lipa haraka!"
                    )
                }
            }

            if (hasOverdue) {
                loans[loanId] = loan.copy(
                    repaymentSchedule = updatedSchedule,
                    status = LoanStatus.OVERDUE
                )
            }
        }

        return overdueMessages
    }

    // ═══════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════

    private suspend fun loadAllLoans(): List<Loan> {
        val entities = loanDao.getAll()
        return entities.mapNotNull { entity ->
            val cached = loans[entity.id.toString()]
            if (cached != null) return@mapNotNull cached

            val repayments = loanDao.getRepayments(entity.id).map { r ->
                Repayment(
                    id = r.id.toString(),
                    amount = r.amount,
                    dueDate = r.dueDate,
                    paidDate = r.paidDate,
                    paidAmount = r.paidAmount,
                    status = try { RepaymentStatus.valueOf(r.status) } catch (_: Exception) { RepaymentStatus.PENDING },
                    penalty = r.penalty
                )
            }
            Loan(
                id = entity.id.toString(),
                amount = entity.amount,
                purpose = entity.purpose,
                interestRate = entity.interestRate,
                repaymentSchedule = repayments,
                startDate = entity.startDate,
                endDate = entity.endDate,
                lender = entity.lender,
                status = try { LoanStatus.valueOf(entity.status) } catch (_: Exception) { LoanStatus.ACTIVE },
                totalRepaid = entity.totalRepaid
            ).also { loans[it.id] = it }
        }
    }

    private fun formatAmount(amount: Int): String {
        return if (amount >= 1_000_000) {
            String.format("%.1fM", amount / 1_000_000.0)
        } else if (amount >= 1_000) {
            String.format("%,d", amount)
        } else {
            amount.toString()
        }
    }
}
