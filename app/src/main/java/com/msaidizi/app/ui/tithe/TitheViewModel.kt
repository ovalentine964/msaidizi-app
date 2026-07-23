package com.msaidizi.app.ui.tithe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.data.dao.GivingDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class TitheViewModel @Inject constructor(
    private val givingDao: GivingDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(TitheUiState())
    val uiState: StateFlow<TitheUiState> = _uiState.asStateFlow()

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    init {
        loadGiving()
    }

    private fun loadGiving() {
        viewModelScope.launch {
            val workerId = "default"

            // Current month
            val cal = Calendar.getInstance()
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val monthStart = cal.timeInMillis
            val now = System.currentTimeMillis()

            val allGiving = givingDao.getByWorker(workerId)
            val monthlyTotal = givingDao.getTotalGiving(workerId, monthStart, now) ?: 0.0

            val rows = allGiving.map { giving ->
                GivingRow(
                    type = giving.type,
                    amount = giving.amount,
                    recipient = giving.recipient,
                    date = dateFormat.format(Date(giving.timestamp)),
                    notes = giving.notes
                )
            }

            _uiState.value = _uiState.value.copy(
                givingRecords = rows,
                monthlyTotal = monthlyTotal,
                totalCount = allGiving.size
            )
        }
    }
}

data class TitheUiState(
    val givingRecords: List<GivingRow> = emptyList(),
    val monthlyTotal: Double = 0.0,
    val totalCount: Int = 0
)

data class GivingRow(
    val type: String,
    val amount: Double,
    val recipient: String,
    val date: String,
    val notes: String
)
