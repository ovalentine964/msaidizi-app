package com.msaidizi.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

/**
 * ViewModel for History screen.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val transactionDao: TransactionDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private var currentDate = LocalDate.now()

    init {
        loadTransactions()
    }

    fun loadTransactions() {
        viewModelScope.launch {
            val startOfDay = currentDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            val endOfDay = currentDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

            transactionDao.getTransactionsForDate(startOfDay, endOfDay).collect { transactions ->
                _uiState.value = HistoryUiState(
                    date = currentDate.toString(),
                    transactions = transactions,
                    totalSales = transactions.filter { it.type.name == "SALE" }.sumOf { it.totalAmount },
                    totalPurchases = transactions.filter { it.type.name == "PURCHASE" }.sumOf { it.totalAmount }
                )
            }
        }
    }

    fun previousDay() {
        currentDate = currentDate.minusDays(1)
        loadTransactions()
    }

    fun nextDay() {
        currentDate = currentDate.plusDays(1)
        loadTransactions()
    }

    fun goToToday() {
        currentDate = LocalDate.now()
        loadTransactions()
    }
}

data class HistoryUiState(
    val date: String = "",
    val transactions: List<Transaction> = emptyList(),
    val totalSales: Double = 0.0,
    val totalPurchases: Double = 0.0
)
