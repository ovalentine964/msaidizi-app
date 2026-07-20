package com.msaidizi.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.agent.AnalysisAgent
import com.msaidizi.app.agent.BusinessAgent
import com.msaidizi.app.core.model.CashFlow
import com.msaidizi.app.core.model.ItemRanking
import com.msaidizi.app.core.model.Trend
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Dashboard screen.
 * Shows business analytics, trends, and charts.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val businessAgent: BusinessAgent,
    private val analysisAgent: AnalysisAgent
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadDashboardData()
    }

    fun loadDashboardData() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)

                // Load data in parallel
                val cashFlow = businessAgent.getCashFlow(7)
                val salesTrend = analysisAgent.salesTrend()
                val topItems = analysisAgent.topItems(7, 5)
                val dailySales = analysisAgent.getDailySalesData(7)
                val profitMargin = analysisAgent.getProfitMargin(7)
                val salesVelocity = analysisAgent.getSalesVelocity(7)
                val dayOfWeekPattern = analysisAgent.getDayOfWeekPattern(28)
                val abcAnalysis = analysisAgent.abcAnalysis(30)

                _uiState.value = DashboardUiState(
                    isLoading = false,
                    weeklyCashFlow = cashFlow,
                    salesTrend = salesTrend,
                    topItems = topItems,
                    dailySalesData = dailySales,
                    profitMargin = profitMargin,
                    salesVelocity = salesVelocity,
                    dayOfWeekPattern = dayOfWeekPattern,
                    abcAnalysis = abcAnalysis,
                    error = null
                )
            } catch (e: Throwable) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load dashboard: ${e.message}"
                )
            }
        }
    }

    fun refresh() {
        loadDashboardData()
    }
}

/**
 * UI State for Dashboard.
 */
data class DashboardUiState(
    val isLoading: Boolean = false,
    val weeklyCashFlow: CashFlow = CashFlow(0.0, 0.0, 0.0, ""),
    val salesTrend: Trend = Trend.INSUFFICIENT_DATA,
    val topItems: List<ItemRanking> = emptyList(),
    val dailySalesData: List<Pair<String, Double>> = emptyList(),
    val profitMargin: Double = 0.0,
    val salesVelocity: Double = 0.0,
    val dayOfWeekPattern: Map<String, Double> = emptyMap(),
    val abcAnalysis: Map<String, Char> = emptyMap(),
    val error: String? = null
)
