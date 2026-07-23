package com.msaidizi.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.data.dao.TransactionDao
import com.msaidizi.app.security.WorkerIdProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val transactionDao: TransactionDao,
    private val workerIdProvider: WorkerIdProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    init {
        loadTransactions()
    }

    fun setFilter(filter: String) {
        _uiState.value = _uiState.value.copy(filter = filter)
        loadTransactions()
    }

    private fun loadTransactions() {
        viewModelScope.launch {
            val workerId = workerIdProvider.getWorkerId()
            val allTransactions = transactionDao.getByWorker(workerId)
            val filter = _uiState.value.filter

            val filtered = if (filter == "all") allTransactions
                          else allTransactions.filter { it.type == filter }

            val rows = filtered.map {
                TransactionHistoryRow(
                    item = it.item,
                    type = it.type,
                    amount = it.amount,
                    date = dateFormat.format(Date(it.timestamp))
                )
            }

            _uiState.value = _uiState.value.copy(transactions = rows)
        }
    }
}

data class HistoryUiState(
    val filter: String = "all",
    val transactions: List<TransactionHistoryRow> = emptyList()
)

data class TransactionHistoryRow(
    val item: String,
    val type: String,
    val amount: Double,
    val date: String
)
