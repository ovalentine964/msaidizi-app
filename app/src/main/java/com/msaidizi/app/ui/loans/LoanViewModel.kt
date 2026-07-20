package com.msaidizi.app.ui.loans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.core.database.LoanDao
import com.msaidizi.app.core.model.LoanRepayment
import com.msaidizi.app.finance.LoanManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Loan Management screen.
 *
 * Handles:
 * - Recording new loans via LoanManager
 * - Loading active/completed loan status from LoanDao
 * - Tracking repayments
 * - Calculating default risk per loan
 */
@HiltViewModel
class LoanViewModel @Inject constructor(
    private val loanManager: LoanManager,
    private val loanDao: LoanDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoanUiState())
    val uiState: StateFlow<LoanUiState> = _uiState.asStateFlow()

    init {
        loadLoans()
    }

    // ═══════════════════════════════════════════════════════════════
    // DATA LOADING
    // ═══════════════════════════════════════════════════════════════

    fun loadLoans() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)

                val activeLoans = loanManager.getActiveLoans()
                val allEntities = loanDao.getAll()
                val completedEntities = allEntities.filter { it.status == "COMPLETED" || it.status == "PAID" }
                val totalOutstanding = loanDao.getTotalOutstanding() ?: 0.0
                val activeCount = loanDao.getActiveCount()

                // Build loan card data for active loans
                val activeLoanCards = activeLoans.map { loan ->
                    val roomId = loan.id.toLongOrNull() ?: 0L
                    val repayments = if (roomId > 0) loanDao.getRepayments(roomId) else emptyList()
                    val now = System.currentTimeMillis() / 1000

                    val progress = if (loan.totalToRepay > 0) {
                        (loan.totalRepaid / loan.totalToRepay).coerceIn(0.0, 1.0)
                    } else 0.0

                    val nextPayment = loan.nextPayment
                    val daysUntilNext = nextPayment?.let {
                        ((it.dueDate - now) / 86400).toInt()
                    }

                    val defaultRisk = calculateDefaultRisk(loan, repayments, now)

                    LoanCardData(
                        id = loan.id,
                        amount = loan.amount,
                        purpose = loan.purpose,
                        lender = loan.lender,
                        totalDue = loan.totalToRepay,
                        totalRepaid = loan.totalRepaid,
                        balance = loan.balance,
                        progress = progress,
                        status = loan.status.name,
                        nextPaymentAmount = nextPayment?.amount,
                        nextPaymentDue = nextPayment?.dueDate,
                        daysUntilNextPayment = daysUntilNext,
                        paymentsCompleted = loan.paymentsCompleted,
                        totalPayments = loan.repaymentSchedule.size,
                        interestRate = loan.effectiveInterestRate,
                        defaultRisk = defaultRisk,
                        isBusinessLoan = loan.purpose.contains("Biashara", ignoreCase = true) ||
                                loan.purpose.contains("Business", ignoreCase = true) ||
                                loan.purpose.contains("Stock", ignoreCase = true) ||
                                loan.purpose.contains("Inventory", ignoreCase = true),
                        startDate = loan.startDate
                    )
                }

                // Build repayment schedule across all active loans
                val allRepayments = activeLoanCards.flatMap { card ->
                    val roomId = card.id.toLongOrNull() ?: return@flatMap emptyList<RepaymentItem>()
                    val reps = loanDao.getRepayments(roomId)
                    reps.map { r ->
                        RepaymentItem(
                            loanId = card.id,
                            lender = card.lender,
                            amount = r.amount,
                            dueDate = r.dueDate,
                            status = r.status,
                            paidDate = r.paidDate,
                            paidAmount = r.paidAmount
                        )
                    }
                }.sortedBy { it.dueDate }

                _uiState.value = LoanUiState(
                    isLoading = false,
                    activeLoans = activeLoanCards,
                    completedCount = completedEntities.size,
                    totalOutstanding = totalOutstanding,
                    activeLoanCount = activeCount,
                    repaymentSchedule = allRepayments,
                    error = null
                )
            } catch (e: Throwable) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Imeshindwa kupakia mikopo: ${e.message}"
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LOAN RECORDING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a new loan with the given parameters.
     *
     * @param amount Loan principal amount in KSh
     * @param purpose One of: "Biashara", "Binafsi", "Dharura", "Elimu"
     * @param lender Lender name (e.g., "M-Shwari", "KCB M-Pesa")
     * @param interestRate Annual interest rate as percentage (e.g., 15.0)
     * @param termMonths Repayment term in months
     */
    fun recordLoan(
        amount: Double,
        purpose: String,
        lender: String,
        interestRate: Double,
        termMonths: Int
    ) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isRecording = true, error = null)

                val now = System.currentTimeMillis() / 1000
                val schedule = loanManager.generateRepaymentSchedule(
                    principal = amount,
                    annualInterestRate = interestRate / 100.0,
                    termMonths = termMonths,
                    startDate = now
                )

                val endDate = schedule.lastOrNull()?.dueDate ?: (now + termMonths * 30L * 86400)

                val loan = LoanManager.Loan(
                    amount = amount,
                    purpose = purpose,
                    interestRate = interestRate / 100.0,
                    repaymentSchedule = schedule,
                    startDate = now,
                    endDate = endDate,
                    lender = lender,
                    status = LoanManager.LoanStatus.ACTIVE
                )

                loanManager.recordLoan(loan)

                _uiState.value = _uiState.value.copy(
                    isRecording = false,
                    showRecordForm = false,
                    successMessage = "Mkopo wa KSh ${formatAmount(amount.toInt())} umerekodhiwa!"
                )

                loadLoans()
            } catch (e: Throwable) {
                _uiState.value = _uiState.value.copy(
                    isRecording = false,
                    error = "Imeshindwa kurekodi mkopo: ${e.message}"
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // REPAYMENT RECORDING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a repayment for a specific loan.
     */
    fun recordRepayment(loanId: String, amount: Double) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isRecordingRepayment = true, error = null)

                val result = loanManager.recordRepayment(loanId, amount)
                if (result != null) {
                    _uiState.value = _uiState.value.copy(
                        isRecordingRepayment = false,
                        successMessage = "Malipo ya KSh ${formatAmount(amount.toInt())} yamerekodhiwa!"
                    )
                    loadLoans()
                } else {
                    _uiState.value = _uiState.value.copy(
                        isRecordingRepayment = false,
                        error = "Mkopo haujapatikana."
                    )
                }
            } catch (e: Throwable) {
                _uiState.value = _uiState.value.copy(
                    isRecordingRepayment = false,
                    error = "Imeshindwa kurekodi malipo: ${e.message}"
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // UI STATE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════

    fun toggleRecordForm() {
        _uiState.value = _uiState.value.copy(
            showRecordForm = !_uiState.value.showRecordForm
        )
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(
            successMessage = null,
            error = null
        )
    }

    fun refresh() {
        loadLoans()
    }

    // ═══════════════════════════════════════════════════════════════
    // DEFAULT RISK CALCULATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calculate default risk for a loan based on repayment behavior.
     * Returns a risk level: LOW, MEDIUM, HIGH, CRITICAL.
     *
     * Factors:
     * - Percentage of overdue payments
     * - Days until next payment
     * - Overall repayment progress vs timeline
     * - Payment consistency
     */
    private fun calculateDefaultRisk(
        loan: LoanManager.Loan,
        repayments: List<LoanRepayment>,
        now: Long
    ): DefaultRisk {
        if (loan.status == LoanManager.LoanStatus.PAID) return DefaultRisk.LOW
        if (loan.repaymentSchedule.isEmpty()) return DefaultRisk.LOW

        val totalPayments = loan.repaymentSchedule.size
        val overduePayments = loan.repaymentSchedule.count {
            it.status == LoanManager.RepaymentStatus.OVERDUE
        }
        val paidPayments = loan.paymentsCompleted

        // Factor 1: Overdue ratio
        val overdueRatio = if (totalPayments > 0) overduePayments.toDouble() / totalPayments else 0.0

        // Factor 2: Progress vs time elapsed
        val startDate = loan.startDate
        val endDate = loan.endDate
        val totalTime = (endDate - startDate).coerceAtLeast(1)
        val elapsedTime = (now - startDate).coerceIn(0, totalTime)
        val timeProgress = elapsedTime.toDouble() / totalTime
        val paymentProgress = if (totalPayments > 0) paidPayments.toDouble() / totalPayments else 0.0
        val progressGap = timeProgress - paymentProgress

        // Factor 3: Days until next payment (negative = overdue)
        val nextPayment = loan.nextPayment
        val daysUntilNext = nextPayment?.let { ((it.dueDate - now) / 86400).toInt() } ?: 0

        // Calculate risk score (0-100)
        var riskScore = 0

        // Overdue payments are the strongest signal
        riskScore += (overdueRatio * 50).toInt()

        // Progress behind schedule
        if (progressGap > 0.2) riskScore += 20
        else if (progressGap > 0.1) riskScore += 10

        // Next payment urgency
        when {
            daysUntilNext < 0 -> riskScore += 25  // Already overdue
            daysUntilNext <= 3 -> riskScore += 10  // Very soon
        }

        // High interest rate loans are riskier
        if (loan.interestRate > 0.20) riskScore += 5

        return when {
            riskScore >= 60 -> DefaultRisk.CRITICAL
            riskScore >= 40 -> DefaultRisk.HIGH
            riskScore >= 20 -> DefaultRisk.MEDIUM
            else -> DefaultRisk.LOW
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

// ═══════════════════════════════════════════════════════════════
// UI STATE & DATA CLASSES
// ═══════════════════════════════════════════════════════════════

data class LoanUiState(
    val isLoading: Boolean = false,
    val activeLoans: List<LoanCardData> = emptyList(),
    val completedCount: Int = 0,
    val totalOutstanding: Double = 0.0,
    val activeLoanCount: Int = 0,
    val repaymentSchedule: List<RepaymentItem> = emptyList(),
    val showRecordForm: Boolean = false,
    val isRecording: Boolean = false,
    val isRecordingRepayment: Boolean = false,
    val successMessage: String? = null,
    val error: String? = null
)

data class LoanCardData(
    val id: String,
    val amount: Double,
    val purpose: String,
    val lender: String,
    val totalDue: Double,
    val totalRepaid: Double,
    val balance: Double,
    val progress: Double,
    val status: String,
    val nextPaymentAmount: Double?,
    val nextPaymentDue: Long?,
    val daysUntilNextPayment: Int?,
    val paymentsCompleted: Int,
    val totalPayments: Int,
    val interestRate: Double,
    val defaultRisk: DefaultRisk,
    val isBusinessLoan: Boolean,
    val startDate: Long
)

data class RepaymentItem(
    val loanId: String,
    val lender: String,
    val amount: Double,
    val dueDate: Long,
    val status: String,
    val paidDate: Long?,
    val paidAmount: Double?
)

enum class DefaultRisk(val labelSw: String, val labelEn: String) {
    LOW("Chini", "Low"),
    MEDIUM("Wastani", "Medium"),
    HIGH("Juu", "High"),
    CRITICAL("Hatari", "Critical")
}
