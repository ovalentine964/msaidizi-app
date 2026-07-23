package com.msaidizi.app.ui.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.data.dao.GoalDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class GoalsViewModel @Inject constructor(
    private val goalDao: GoalDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(GoalsUiState())
    val uiState: StateFlow<GoalsUiState> = _uiState.asStateFlow()

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    init {
        loadGoals()
    }

    private fun loadGoals() {
        viewModelScope.launch {
            val workerId = "default"
            val goals = goalDao.getByWorker(workerId)
            val activeGoals = goals.filter { it.status == "active" }
            val completedGoals = goals.filter { it.status == "completed" }

            val rows = goals.map { goal ->
                GoalRow(
                    id = goal.id,
                    name = goal.name,
                    targetAmount = goal.targetAmount,
                    currentAmount = goal.currentAmount,
                    progress = if (goal.targetAmount > 0) (goal.currentAmount / goal.targetAmount * 100).toInt() else 0,
                    deadline = goal.deadline?.let { dateFormat.format(Date(it)) },
                    category = goal.category,
                    status = goal.status
                )
            }

            _uiState.value = _uiState.value.copy(
                goals = rows,
                activeCount = activeGoals.size,
                completedCount = completedGoals.size,
                totalSaved = activeGoals.sumOf { it.currentAmount }
            )
        }
    }
}

data class GoalsUiState(
    val goals: List<GoalRow> = emptyList(),
    val activeCount: Int = 0,
    val completedCount: Int = 0,
    val totalSaved: Double = 0.0
)

data class GoalRow(
    val id: Long,
    val name: String,
    val targetAmount: Double,
    val currentAmount: Double,
    val progress: Int,
    val deadline: String?,
    val category: String,
    val status: String
)
