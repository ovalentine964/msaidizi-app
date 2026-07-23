package com.msaidizi.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.agent.AgentInput
import com.msaidizi.app.agent.AgentOutput
import com.msaidizi.app.agent.SuperAgent
import com.msaidizi.app.data.dao.TransactionDao
import com.msaidizi.app.security.WorkerIdProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val superAgent: SuperAgent,
    private val transactionDao: TransactionDao,
    private val workerIdProvider: WorkerIdProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val sessionId = UUID.randomUUID().toString()

    init {
        loadTodayData()
    }

    fun toggleVoice() {
        // Voice toggle will be handled by VoicePipeline integration
        _uiState.value = _uiState.value.copy(isListening = !_uiState.value.isListening)
    }

    fun quickAction(action: String) {
        viewModelScope.launch {
            val input = AgentInput(
                text = when (action) {
                    "sale" -> "Nimeuza"
                    "expense" -> "Nimetumia"
                    "balance" -> "Salio"
                    "goals" -> "Angalia malengo"
                    else -> action
                },
                language = "sw",
                sessionId = sessionId,
                workerId = workerIdProvider.getWorkerId()
            )
            val output = superAgent.processInput(input)
            _uiState.value = _uiState.value.copy(lastResponse = output)
        }
    }

    fun sendText(text: String) {
        viewModelScope.launch {
            val input = AgentInput(
                text = text,
                language = "sw",
                sessionId = sessionId,
                workerId = workerIdProvider.getWorkerId()
            )
            val output = superAgent.processInput(input)
            _uiState.value = _uiState.value.copy(lastResponse = output)
            loadTodayData()
        }
    }

    private fun loadTodayData() {
        viewModelScope.launch {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val todayStart = cal.timeInMillis
            val now = System.currentTimeMillis()

            val workerId = workerIdProvider.getWorkerId()
            val sales = transactionDao.getTotalSales(workerId, todayStart, now) ?: 0.0
            val purchases = transactionDao.getTotalPurchases(workerId, todayStart, now) ?: 0.0
            val expenses = transactionDao.getTotalExpenses(workerId, todayStart, now) ?: 0.0
            val count = transactionDao.getTransactionCount(workerId, todayStart, now)
            val recent = transactionDao.getByWorker(workerId).take(5).map {
                TransactionRowData(
                    item = it.item,
                    type = it.type,
                    amount = it.amount,
                    timestamp = it.timestamp
                )
            }

            _uiState.value = _uiState.value.copy(
                todaySales = sales,
                todayExpenses = purchases + expenses,
                todayProfit = sales - purchases - expenses,
                todayTransactions = count,
                recentTransactions = recent
            )
        }
    }
}

data class DashboardUiState(
    val isListening: Boolean = false,
    val todaySales: Double = 0.0,
    val todayExpenses: Double = 0.0,
    val todayProfit: Double = 0.0,
    val todayTransactions: Int = 0,
    val recentTransactions: List<TransactionRowData> = emptyList(),
    val lastResponse: AgentOutput? = null
)
