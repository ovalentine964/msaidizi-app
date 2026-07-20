package com.msaidizi.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.agent.BusinessAgent
import com.msaidizi.app.core.model.DailySummary
import com.msaidizi.app.core.model.RestockAlert
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Home screen.
 * Shows daily summary, quick stats, and recent activity.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val businessAgent: BusinessAgent
) : ViewModel() {

    // UI State
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadDashboardData()
    }

    /**
     * Load all dashboard data.
     */
    fun loadDashboardData() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)

                val sales = businessAgent.getDailySales()
                val purchases = businessAgent.getDailyPurchases()
                val profit = businessAgent.getDailyProfit()
                val transactionCount = businessAgent.getDailyTransactionCount()
                val restockAlerts = businessAgent.getRestockAlerts()
                val topItems = businessAgent.getTopSellingItems(7, 3)

                _uiState.value = HomeUiState(
                    isLoading = false,
                    dailySales = sales,
                    dailyPurchases = purchases,
                    dailyProfit = profit,
                    transactionCount = transactionCount,
                    restockAlerts = restockAlerts,
                    topItems = topItems,
                    error = null
                )
            } catch (e: Throwable) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load data: ${e.message}"
                )
            }
        }
    }

    /**
     * Refresh data (pull-to-refresh).
     */
    fun refresh() {
        loadDashboardData()
    }
}

/**
 * UI State for the Home screen.
 */
data class HomeUiState(
    val isLoading: Boolean = false,
    val dailySales: Double = 0.0,
    val dailyPurchases: Double = 0.0,
    val dailyProfit: Double = 0.0,
    val transactionCount: Int = 0,
    val restockAlerts: List<RestockAlert> = emptyList(),
    val topItems: List<com.msaidizi.app.core.model.ItemRanking> = emptyList(),
    val error: String? = null
)
