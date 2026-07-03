package com.msaidizi.app.ui.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.core.database.GoalDao
import com.msaidizi.app.core.model.GoalRecord
import com.msaidizi.app.core.model.GoalMilestone
import com.msaidizi.app.finance.GoalPlanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for the Goal screen.
 * Manages goal creation, progress tracking, milestone celebrations,
 * and time-to-goal predictions.
 */
@HiltViewModel
class GoalViewModel @Inject constructor(
    private val goalPlanner: GoalPlanner,
    private val goalDao: GoalDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(GoalUiState())
    val uiState: StateFlow<GoalUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    /**
     * Load all goal data: active goals, completed goals, overall stats.
     */
    fun loadData() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)

                val activeGoals = goalDao.getActive()
                val completedGoals = goalDao.getCompleted()
                val totalSaved = goalDao.getTotalSaved() ?: 0.0
                val totalTarget = goalDao.getTotalTarget() ?: 0.0
                val activeCount = goalDao.getActiveCount()

                // Load milestones and progress for each active goal
                val goalDetails = activeGoals.map { goal ->
                    val milestones = goalDao.getMilestones(goal.id)
                    val progressEntries = goalDao.getProgressEntries(goal.id)
                    val domainGoal = goal.toDomainGoal()
                    val forecast = goalPlanner.getTimeToGoal(domainGoal)
                    val encouragement = goalPlanner.getEncouragement(domainGoal)

                    GoalDetail(
                        goal = goal,
                        milestones = milestones,
                        progressCount = progressEntries.size,
                        forecastMessage = forecast.message,
                        daysRemaining = forecast.daysRemaining,
                        dailyRateNeeded = forecast.dailyRateNeeded,
                        isOnTrack = forecast.isOnTrack,
                        encouragement = encouragement
                    )
                }

                val overallProgress = if (totalTarget > 0) totalSaved / totalTarget else 0.0

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    activeGoalDetails = goalDetails,
                    completedGoals = completedGoals,
                    totalSaved = totalSaved,
                    totalTarget = totalTarget,
                    activeCount = activeCount,
                    overallProgress = overallProgress,
                    error = null
                )
            } catch (e: Exception) {
                Timber.e(e, "Error loading goal data")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Imeshindwa kupakia malengo. Jaribu tena."
                )
            }
        }
    }

    /**
     * Create a new goal from voice input.
     *
     * @param description Goal description in Swahili (e.g., "Ninataka kununua friji")
     * @param targetAmount Target amount in KSh
     * @param deadline Target date as Unix timestamp in seconds
     * @param category Goal category name
     */
    fun createGoalFromVoice(
        description: String,
        targetAmount: Double,
        deadline: Long,
        category: String = ""
    ) {
        viewModelScope.launch {
            try {
                if (description.isBlank()) {
                    _uiState.value = _uiState.value.copy(
                        error = "Sema lengo lako — mfano: \"Ninataka kununua friji\""
                    )
                    return@launch
                }

                if (targetAmount <= 0) {
                    _uiState.value = _uiState.value.copy(
                        error = "Weka kiasi sahihi (zaidi ya 0)"
                    )
                    return@launch
                }

                _uiState.value = _uiState.value.copy(isProcessing = true, error = null)

                val goalCategory = if (category.isNotBlank()) {
                    try {
                        GoalPlanner.GoalCategory.valueOf(category)
                    } catch (_: Exception) {
                        GoalPlanner.GoalCategory.OTHER
                    }
                } else {
                    GoalPlanner.GoalCategory.OTHER
                }

                goalPlanner.createGoal(
                    description = description,
                    targetAmount = targetAmount,
                    deadline = deadline,
                    category = goalCategory
                )

                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    confirmationMessage = "Lengo limeundwa! $description — KSh ${formatAmount(targetAmount)} 🎯",
                    showCreateForm = false
                )

                loadData()
            } catch (e: Exception) {
                Timber.e(e, "Error creating goal from voice")
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "Kosa la kuunda lengo. Jaribu tena."
                )
            }
        }
    }

    /**
     * Create a new goal from manual form input.
     */
    fun createGoalManually(
        description: String,
        targetAmount: Double,
        category: String,
        deadlineDays: Int
    ) {
        val deadline = if (deadlineDays > 0) {
            System.currentTimeMillis() / 1000 + (deadlineDays * 86400L)
        } else {
            System.currentTimeMillis() / 1000 + (90 * 86400L) // Default: 3 months
        }

        createGoalFromVoice(description, targetAmount, deadline, category)
    }

    /**
     * Update progress on a goal.
     *
     * @param goalId Goal ID
     * @param amount Amount to add (positive) or set
     * @param note Optional note
     */
    fun updateProgress(goalId: Long, amount: Double, note: String = "") {
        viewModelScope.launch {
            try {
                if (amount <= 0) {
                    _uiState.value = _uiState.value.copy(
                        error = "Weka kiasi sahihi (zaidi ya 0)"
                    )
                    return@launch
                }

                _uiState.value = _uiState.value.copy(isProcessing = true)

                val goalRecord = goalDao.getById(goalId)
                if (goalRecord == null) {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        error = "Lengo halijapatikana"
                    )
                    return@launch
                }

                val domainGoal = goalRecord.toDomainGoal()
                val (updatedGoal, celebration) = goalPlanner.updateProgress(
                    domainGoal, amount, note
                )

                // Check if goal is now complete
                if (updatedGoal.progress >= 1.0) {
                    goalPlanner.completeGoal(updatedGoal)
                }

                val celebrationMsg = celebration?.message
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    confirmationMessage = celebrationMsg
                        ?: "Imerekodiwa! KSh ${formatAmount(amount)} imeongezwa 📈",
                    celebratingMilestone = celebration?.milestone
                )

                loadData()
            } catch (e: Exception) {
                Timber.e(e, "Error updating goal progress")
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "Kosa la kurekodi maendeleo. Jaribu tena."
                )
            }
        }
    }

    /**
     * Update progress from voice input.
     *
     * @param goalId Goal ID
     * @param voiceText Voice input (e.g., "Nimeweka 2000 leo")
     */
    fun updateProgressFromVoice(goalId: Long, voiceText: String) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isProcessing = true)

                // Extract amount from voice text
                val amount = extractAmountFromVoice(voiceText)
                if (amount == null || amount <= 0) {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        error = "Sikuelewi kiasi. Sema kama: \"Nimeweka 2000\""
                    )
                    return@launch
                }

                updateProgress(goalId, amount, voiceText)
            } catch (e: Exception) {
                Timber.e(e, "Error processing voice progress")
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "Kosa la kuchakata sauti. Jaribu tena."
                )
            }
        }
    }

    /**
     * Mark a goal as complete.
     */
    fun completeGoal(goalId: Long) {
        viewModelScope.launch {
            try {
                val goalRecord = goalDao.getById(goalId) ?: return@launch
                val domainGoal = goalRecord.toDomainGoal()
                val (_, celebration) = goalPlanner.completeGoal(domainGoal)

                _uiState.value = _uiState.value.copy(
                    confirmationMessage = celebration.message,
                    celebratingMilestone = 1.0
                )

                loadData()
            } catch (e: Exception) {
                Timber.e(e, "Error completing goal")
                _uiState.value = _uiState.value.copy(
                    error = "Kosa. Jaribu tena."
                )
            }
        }
    }

    /**
     * Abandon a goal.
     */
    fun abandonGoal(goalId: Long) {
        viewModelScope.launch {
            try {
                val goalRecord = goalDao.getById(goalId) ?: return@launch
                val domainGoal = goalRecord.toDomainGoal()
                goalPlanner.abandonGoal(domainGoal)

                _uiState.value = _uiState.value.copy(
                    confirmationMessage = "Lengo limeachwa. Unaweza kuunda jipya wakati wowote."
                )

                loadData()
            } catch (e: Exception) {
                Timber.e(e, "Error abandoning goal")
                _uiState.value = _uiState.value.copy(
                    error = "Kosa. Jaribu tena."
                )
            }
        }
    }

    /**
     * Toggle the create goal form.
     */
    fun toggleCreateForm() {
        _uiState.value = _uiState.value.copy(
            showCreateForm = !_uiState.value.showCreateForm,
            error = null
        )
    }

    /**
     * Toggle progress input for a specific goal.
     */
    fun toggleProgressInput(goalId: Long) {
        _uiState.value = _uiState.value.copy(
            progressInputGoalId = if (_uiState.value.progressInputGoalId == goalId) null else goalId
        )
    }

    /**
     * Clear confirmation message.
     */
    fun clearConfirmation() {
        _uiState.value = _uiState.value.copy(
            confirmationMessage = null,
            celebratingMilestone = null
        )
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Extract amount from Swahili voice text.
     * Handles: "Nimeweka 2000", "KSh 500", "elfu moja"
     */
    private fun extractAmountFromVoice(text: String): Double? {
        val lower = text.lowercase().trim()

        // Try KSh/SH prefix
        val kshPattern = Regex("""(?:ksh|sh|kes)\s*(\d+(?:\.\d+)?)""")
        kshPattern.find(lower)?.let {
            return it.groupValues[1].toDoubleOrNull()
        }

        // Try Swahili number words
        val numberWords = mapOf(
            "moja" to 1, "mbili" to 2, "tatu" to 3, "nne" to 4, "tano" to 5,
            "sita" to 6, "saba" to 7, "nane" to 8, "tisa" to 9, "kumi" to 10
        )

        val elfuPattern = Regex("""elfu\s+(moja|mbili|tatu|nne|tano|sita|saba|nane|tisa|kumi)""")
        elfuPattern.find(lower)?.let {
            val multiplier = numberWords[it.groupValues[1]] ?: return@let
            return (multiplier * 1000).toDouble()
        }

        val miaPattern = Regex("""mia\s+(moja|mbili|tatu|nne|tano|sita|saba|nane|tisa)""")
        miaPattern.find(lower)?.let {
            val multiplier = numberWords[it.groupValues[1]] ?: return@let
            return (multiplier * 100).toDouble()
        }

        // Standalone number
        val numberPattern = Regex("""(\d+(?:\.\d+)?)""")
        return numberPattern.findAll(lower).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun formatAmount(amount: Double): String {
        return if (amount >= 1_000_000) {
            String.format("%.1fM", amount / 1_000_000)
        } else if (amount >= 1_000) {
            String.format("%,d", amount.toLong())
        } else if (amount == amount.toLong().toDouble()) {
            amount.toLong().toString()
        } else {
            String.format("%.2f", amount)
        }
    }

    /**
     * Convert GoalRecord entity to GoalPlanner.Goal domain model.
     */
    private fun GoalRecord.toDomainGoal(): GoalPlanner.Goal {
        return GoalPlanner.Goal(
            id = id.toString(),
            description = name,
            targetAmount = targetAmount,
            currentAmount = currentAmount,
            deadline = deadline,
            category = try {
                GoalPlanner.GoalCategory.valueOf(category)
            } catch (_: Exception) {
                GoalPlanner.GoalCategory.OTHER
            },
            isActive = status == "ACTIVE"
        )
    }
}

/**
 * UI State for the Goal screen.
 */
data class GoalUiState(
    val isLoading: Boolean = false,
    val isProcessing: Boolean = false,
    val showCreateForm: Boolean = false,
    val activeGoalDetails: List<GoalDetail> = emptyList(),
    val completedGoals: List<GoalRecord> = emptyList(),
    val totalSaved: Double = 0.0,
    val totalTarget: Double = 0.0,
    val activeCount: Int = 0,
    val overallProgress: Double = 0.0,
    val progressInputGoalId: Long? = null,
    val confirmationMessage: String? = null,
    val celebratingMilestone: Double? = null,
    val error: String? = null
)

/**
 * Detailed goal info including forecast and milestones.
 */
data class GoalDetail(
    val goal: GoalRecord,
    val milestones: List<GoalMilestone> = emptyList(),
    val progressCount: Int = 0,
    val forecastMessage: String = "",
    val daysRemaining: Int = 0,
    val dailyRateNeeded: Double = 0.0,
    val isOnTrack: Boolean = false,
    val encouragement: String = ""
) {
    val progress: Double
        get() = if (goal.targetAmount > 0) {
            (goal.currentAmount / goal.targetAmount).coerceIn(0.0, 1.0)
        } else 0.0

    val progressPercent: Int
        get() = (progress * 100).toInt()

    val remainingAmount: Double
        get() = maxOf(0.0, goal.targetAmount - goal.currentAmount)
}
