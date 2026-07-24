package com.msaidizi.app.superagent.goals

import timber.log.Timber
import kotlin.math.*

/**
 * Loan Tracker — loan recording, repayment tracking, and deadline alerts.
 *
 * Most informal workers don't default because they can't pay. They default
 * because they lose track of payments, spend loan money on personal items,
 * or don't understand the true cost of borrowing. Msaidizi is the loan
 * management partner that prevents all three.
 *
 * ## Features
 * - **Loan recording:** Amount, purpose, lender, interest rate
 * - **Repayment tracking:** Schedule, payments, remaining balance
 * - **Deadline alerts:** Upcoming and overdue payments
 * - **Purpose compliance:** Ensures loan goes to stated purpose
 * - **Debt-to-income monitoring:** Prevents over-indebtedness
 *
 * ## Academic Foundations
 * - **ECO 206 (Microfinance):** Loan repayment behavior, group lending theory
 * - **STA 341 (Estimation):** Default probability estimation
 * - **FIN 201 (Corporate Finance):** Debt service coverage ratio
 *
 * @author Msaidizi Financial Team
 */
class LoanTracker {

    companion object {
        private const val TAG = "LoanTracker"

        /** Warning threshold — days before payment due date */
        private const val REMINDER_DAYS_BEFORE = 3

        /** Critical threshold — days after which loan is overdue */
        private const val OVERDUE_THRESHOLD_DAYS = 1

        /** Maximum recommended debt-to-income ratio */
        private const val MAX_DEBT_TO_INCOME = 0.40

        /** Seconds in a day */
        private const val SECONDS_PER_DAY = 86_400L
    }

    // ═══════════════════════════════════════════════════════════════
    // LOAN RECORDING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a new loan.
     *
     * @param amount Principal loan amount
     * @param purpose What the loan is for
     * @param lender Who lent the money
     * @param interestRate Monthly interest rate (0.0-1.0)
     * @param durationMonths Loan duration in months
     * @param repaymentFrequency How often payments are due
     * @return New [Loan] with calculated total due
     */
    fun recordLoan(
        amount: Double,
        purpose: String,
        lender: String = "",
        interestRate: Double = 0.0,
        durationMonths: Int = 1,
        repaymentFrequency: RepaymentFrequency = RepaymentFrequency.MONTHLY
    ): Loan {
        val now = System.currentTimeMillis() / 1000
        val endDate = now + (durationMonths * 30 * SECONDS_PER_DAY)

        // Calculate total due (simple interest)
        val totalInterest = amount * interestRate * durationMonths
        val totalDue = amount + totalInterest

        return Loan(
            amount = amount,
            purpose = purpose,
            lender = lender,
            interestRate = interestRate,
            totalDue = totalDue,
            startDate = now,
            endDate = endDate,
            repaymentFrequency = repaymentFrequency
        )
    }

    /**
     * Parse a loan from voice input.
     *
     * Examples:
     * - "Nimekopa elfu kumi kutoka kwa M-Shwari kwa stock"
     * - "Mkopo wa elfu hamsini, riba 10%"
     *
     * @param voiceInput Voice text describing the loan
     * @return Parsed [Loan] or null if parsing failed
     */
    fun recordFromVoice(voiceInput: String): Loan? {
        val normalized = voiceInput.lowercase().trim()

        // Extract amount
        val amount = extractAmount(normalized)
        if (amount <= 0) return null

        // Extract lender
        val lender = extractLender(normalized)

        // Extract purpose
        val purpose = extractPurpose(normalized)

        // Extract interest rate
        val interestRate = extractInterestRate(normalized)

        return recordLoan(
            amount = amount,
            purpose = purpose,
            lender = lender,
            interestRate = interestRate
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // REPAYMENT TRACKING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate a repayment schedule for a loan.
     *
     * @param loan The loan to generate schedule for
     * @return List of [LoanRepayment] entries
     */
    fun generateRepaymentSchedule(loan: Loan): List<LoanRepayment> {
        val payments = mutableListOf<LoanRepayment>()
        val paymentAmount = when (loan.repaymentFrequency) {
            RepaymentFrequency.DAILY -> loan.totalDue / max(1, ((loan.endDate - loan.startDate) / SECONDS_PER_DAY).toInt())
            RepaymentFrequency.WEEKLY -> loan.totalDue / max(1, ((loan.endDate - loan.startDate) / (7 * SECONDS_PER_DAY)).toInt())
            RepaymentFrequency.BIWEEKLY -> loan.totalDue / max(1, ((loan.endDate - loan.startDate) / (14 * SECONDS_PER_DAY)).toInt())
            RepaymentFrequency.MONTHLY -> loan.totalDue / max(1, ((loan.endDate - loan.startDate) / (30 * SECONDS_PER_DAY)).toInt())
        }

        val intervalDays = when (loan.repaymentFrequency) {
            RepaymentFrequency.DAILY -> 1L
            RepaymentFrequency.WEEKLY -> 7L
            RepaymentFrequency.BIWEEKLY -> 14L
            RepaymentFrequency.MONTHLY -> 30L
        }

        var dueDate = loan.startDate + (intervalDays * SECONDS_PER_DAY)
        var paymentNumber = 1

        while (dueDate <= loan.endDate) {
            payments.add(
                LoanRepayment(
                    loanId = loan.id,
                    amount = paymentAmount,
                    dueDate = dueDate
                )
            )
            dueDate += intervalDays * SECONDS_PER_DAY
            paymentNumber++
        }

        return payments
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
        val now = System.currentTimeMillis() / 1000
        val newTotalRepaid = loan.totalRepaid + amount

        // Find the earliest pending repayment
        val updatedRepayments = repayments.toMutableList()
        val pendingIndex = updatedRepayments.indexOfFirst { it.status == RepaymentStatus.PENDING }

        if (pendingIndex >= 0) {
            val pending = updatedRepayments[pendingIndex]
            if (amount >= pending.amount) {
                // Full payment
                updatedRepayments[pendingIndex] = pending.copy(
                    paidDate = now,
                    paidAmount = amount,
                    status = RepaymentStatus.PAID
                )
            } else {
                // Partial payment
                updatedRepayments[pendingIndex] = pending.copy(
                    paidDate = now,
                    paidAmount = amount,
                    status = RepaymentStatus.PARTIAL
                )
            }
        }

        // Update loan status
        val newStatus = when {
            newTotalRepaid >= loan.totalDue -> LoanStatus.COMPLETED
            loan.endDate > 0 && now > loan.endDate -> LoanStatus.OVERDUE
            else -> loan.status
        }

        val updatedLoan = loan.copy(
            totalRepaid = newTotalRepaid,
            status = newStatus,
            updatedAt = now
        )

        return Pair(updatedLoan, updatedRepayments)
    }

    // ═══════════════════════════════════════════════════════════════
    // DEADLINE ALERTS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get upcoming and overdue payments.
     *
     * @param loans All active loans
     * @param repayments All repayment schedules
     * @return [LoanTrackerResult] with alerts and summary
     */
    fun getAlerts(
        loans: List<Loan>,
        repayments: List<LoanRepayment>,
        monthlyIncome: Double = 0.0
    ): LoanTrackerResult {
        val now = System.currentTimeMillis() / 1000
        val activeLoans = loans.filter { it.status == LoanStatus.ACTIVE || it.status == LoanStatus.OVERDUE }

        // Find upcoming payments (due within REMINDER_DAYS_BEFORE days)
        val upcomingPayments = repayments.filter { repayment ->
            repayment.status == RepaymentStatus.PENDING &&
                repayment.dueDate > now &&
                (repayment.dueDate - now) <= REMINDER_DAYS_BEFORE * SECONDS_PER_DAY
        }

        // Find overdue payments
        val overduePayments = repayments.filter { repayment ->
            repayment.status == RepaymentStatus.PENDING &&
                repayment.dueDate < now
        }.map { repayment ->
            val daysOverdue = ((now - repayment.dueDate) / SECONDS_PER_DAY).toInt()
            val penalty = repayment.amount * 0.01 * daysOverdue // 1% per day penalty
            repayment.copy(
                status = RepaymentStatus.OVERDUE,
                penalty = penalty
            )
        }

        val totalDebt = activeLoans.sumOf { it.balance }
        val debtToIncome = if (monthlyIncome > 0) totalDebt / monthlyIncome else 0.0

        val message = buildAlertMessage(
            activeLoans, upcomingPayments, overduePayments,
            totalDebt, debtToIncome
        )

        return LoanTrackerResult(
            loans = activeLoans,
            upcomingPayments = upcomingPayments,
            overduePayments = overduePayments,
            totalDebt = totalDebt,
            debtToIncomeRatio = debtToIncome,
            message = message
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // VOICE PARSING HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun extractAmount(text: String): Double {
        val swahiliNumbers = mapOf(
            "moja" to 1, "mbili" to 2, "tatu" to 3, "nne" to 4, "tano" to 5,
            "sita" to 6, "saba" to 7, "nane" to 8, "tisa" to 9, "kumi" to 10,
            "ishirini" to 20, "thelathini" to 30, "arobaini" to 40,
            "hamsini" to 50, "sitini" to 60, "sabini" to 70,
            "themanini" to 80, "tisini" to 90
        )
        val magnitudes = mapOf("mia" to 100, "elfu" to 1000, "laki" to 100_000)
        val words = text.lowercase().split("\\s+".toRegex())
        var total = 0.0
        var current = 0.0
        var i = 0
        while (i < words.size) {
            val word = words[i]
            val mag = magnitudes[word]
            if (mag != null) {
                if (i + 1 < words.size) {
                    val next = swahiliNumbers[words[i + 1]]
                    if (next != null) { total += mag * next; i += 2; continue }
                }
                if (current > 0) { total += current * mag; current = 0.0 } else { total += mag }
                i++; continue
            }
            val num = swahiliNumbers[word] ?: word.toDoubleOrNull()
            if (num != null) current = num.toDouble()
            i++
        }
        return total + current
    }

    private fun extractLender(text: String): String {
        val lenders = listOf(
            "m-shwari" to "M-Shwari", "kcb" to "KCB", "mpesa" to "M-Pesa",
            "fuliza" to "Fuliza", "tala" to "Tala", "branch" to "Branch",
            "okash" to "OKash", "haraka" to "Haraka"
        )
        for ((key, name) in lenders) {
            if (text.contains(key)) return name
        }
        return ""
    }

    private fun extractPurpose(text: String): String {
        val purposes = mapOf(
            "stock" to "Stock", "bidhaa" to "Stock", "inventory" to "Stock",
            "biashara" to "Business", "business" to "Business",
            "shule" to "School fees", "school" to "School fees",
            "kodi" to "Rent", "rent" to "Rent",
            "dharura" to "Emergency", "emergency" to "Emergency"
        )
        for ((key, purpose) in purposes) {
            if (text.contains(key)) return purpose
        }
        return "General"
    }

    private fun extractInterestRate(text: String): Double {
        val regex = Regex("(\\d+)%")
        val match = regex.find(text)
        return (match?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0) / 100.0
    }

    // ═══════════════════════════════════════════════════════════════
    // MESSAGE GENERATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build alert message in Swahili.
     */
    private fun buildAlertMessage(
        loans: List<Loan>,
        upcoming: List<LoanRepayment>,
        overdue: List<LoanRepayment>,
        totalDebt: Double,
        debtToIncome: Double
    ): String {
        return buildString {
            if (loans.isEmpty()) {
                append("Huna mikopo inayoendelea. Nzuri! ✅")
                return@buildString
            }

            append("📋 Muhtasari wa Mikopo:\n\n")

            loans.forEach { loan ->
                val emoji = when (loan.status) {
                    LoanStatus.OVERDUE -> "🔴"
                    LoanStatus.ACTIVE -> "🟡"
                    else -> "🟢"
                }
                append("$emoji ${loan.purpose}: ")
                append("KSh ${formatAmount(loan.balance)} imebaki ")
                append("(${loan.repaymentPercent}% imelipwa)\n")
            }

            if (overdue.isNotEmpty()) {
                append("\n🚨 Malipo yaliyopita muda:\n")
                overdue.forEach { payment ->
                    append("• KSh ${formatAmount(payment.amount)} — ilipaswa kulipwa\n")
                }
            }

            if (upcoming.isNotEmpty()) {
                append("\n⏰ Malipo yanayokuja:\n")
                upcoming.forEach { payment ->
                    val daysUntil = ((payment.dueDate - System.currentTimeMillis() / 1000) / SECONDS_PER_DAY).toInt()
                    append("• KSh ${formatAmount(payment.amount)} — siku $daysUntil\n")
                }
            }

            append("\nJumla ya deni: KSh ${formatAmount(totalDebt)}")
            if (debtToIncome > 0) {
                append("\nUwiano wa deni/mapato: ${(debtToIncome * 100).toInt()}%")
                if (debtToIncome > MAX_DEBT_TO_INCOME) {
                    append(" ⚠️ (Juu sana! Lengo ni chini ya 40%)")
                }
            }
        }
    }

    private fun formatAmount(amount: Double): String {
        return when {
            amount >= 1_000_000 -> String.format("%.1fM", amount / 1_000_000.0)
            amount >= 1_000 -> String.format("%,.0f", amount)
            else -> String.format("%.0f", amount)
        }
    }
}
