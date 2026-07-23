package com.msaidizi.app.ui.loans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.data.dao.LoanDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class LoansViewModel @Inject constructor(
    private val loanDao: LoanDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoansUiState())
    val uiState: StateFlow<LoansUiState> = _uiState.asStateFlow()

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    init {
        loadLoans()
    }

    private fun loadLoans() {
        viewModelScope.launch {
            val workerId = "default"
            val loans = loanDao.getByWorker(workerId)
            val activeLoans = loans.filter { it.status == "active" }
            val totalOutstanding = loanDao.getTotalOutstanding(workerId) ?: 0.0

            val rows = loans.map { loan ->
                LoanRow(
                    id = loan.id,
                    lender = loan.lender,
                    amount = loan.amount,
                    remainingAmount = loan.remainingAmount,
                    interestRate = loan.interestRate,
                    dueDate = loan.dueDate?.let { dateFormat.format(Date(it)) },
                    status = loan.status,
                    notes = loan.notes
                )
            }

            _uiState.value = _uiState.value.copy(
                loans = rows,
                activeCount = activeLoans.size,
                totalOutstanding = totalOutstanding
            )
        }
    }
}

data class LoansUiState(
    val loans: List<LoanRow> = emptyList(),
    val activeCount: Int = 0,
    val totalOutstanding: Double = 0.0
)

data class LoanRow(
    val id: Long,
    val lender: String,
    val amount: Double,
    val remainingAmount: Double,
    val interestRate: Double,
    val dueDate: String?,
    val status: String,
    val notes: String
)
