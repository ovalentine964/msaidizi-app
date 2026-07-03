package com.msaidizi.app.ui.tithe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.core.database.TitheDao
import com.msaidizi.app.core.model.TitheRecord
import com.msaidizi.app.finance.TitheTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * ViewModel for the Tithe screen.
 * Manages tithe recording, history, abundance pattern, and consistency tracking.
 */
@HiltViewModel
class TitheViewModel @Inject constructor(
    private val titheTracker: TitheTracker,
    private val titheDao: TitheDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(TitheUiState())
    val uiState: StateFlow<TitheUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    /**
     * Load all tithe data: history, summary, streak.
     */
    fun loadData() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)

                // Load tithe history
                val records = titheDao.getAll()

                // Load giving summary for this month
                val summary = titheTracker.getGivingSummary("month")

                // Load abundance pattern
                val abundancePattern = titheTracker.getAbundancePattern()

                // Calculate monthly totals for the last 6 months
                val monthlyTotals = calculateMonthlyTotals()

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    titheRecords = records,
                    totalGiven = summary.totalGiven,
                    consistencyScore = summary.consistencyScore,
                    streakDays = summary.streakDays,
                    givingFrequency = summary.givingFrequency,
                    abundancePattern = summary.abundancePattern,
                    abundanceHistory = abundancePattern,
                    monthlyTotals = monthlyTotals,
                    topRecipient = summary.topRecipient,
                    error = null
                )
            } catch (e: Exception) {
                Timber.e(e, "Error loading tithe data")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Imeshindwa kupakia data. Jaribu tena."
                )
            }
        }
    }

    /**
     * Record a tithe/giving via voice input.
     * Parses Swahili voice command and records the giving.
     *
     * @param voiceText Transcribed voice input (e.g., "Nilitoa sadaka KSh 200")
     */
    fun recordGivingFromVoice(voiceText: String) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isRecording = false, isProcessing = true)

                val record = titheTracker.parseGivingCommand(voiceText)
                if (record != null) {
                    titheTracker.recordGiving(record)
                    val confirmation = titheTracker.generateGivingConfirmation(record)
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        confirmationMessage = confirmation
                    )
                    // Reload data
                    loadData()
                } else {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        error = "Sikuelewi. Sema kama: \"Nilitoa sadaka KSh 200\""
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Error recording giving from voice")
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "Kosa la kurekodi. Jaribu tena."
                )
            }
        }
    }

    /**
     * Record a tithe/giving via manual form input.
     *
     * @param amount Amount in KSh
     * @param type Giving type (TITHE, OFFERING, ZAKAT, etc.)
     * @param recipient Recipient name
     * @param notes Optional notes
     */
    fun recordGivingManually(
        amount: Double,
        type: String,
        recipient: String,
        notes: String = ""
    ) {
        viewModelScope.launch {
            try {
                if (amount <= 0) {
                    _uiState.value = _uiState.value.copy(
                        error = "Weka kiasi sahihi (zaidi ya 0)"
                    )
                    return@launch
                }

                _uiState.value = _uiState.value.copy(isProcessing = true)

                val givingType = try {
                    TitheTracker.GivingType.valueOf(type)
                } catch (_: Exception) {
                    TitheTracker.GivingType.OFFERING
                }

                val record = TitheTracker.GivingRecord(
                    amount = amount,
                    type = givingType,
                    recipient = recipient,
                    date = System.currentTimeMillis(),
                    notes = notes
                )

                titheTracker.recordGiving(record)
                val confirmation = titheTracker.generateGivingConfirmation(record)

                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    confirmationMessage = confirmation,
                    showForm = false
                )

                // Reload data
                loadData()
            } catch (e: Exception) {
                Timber.e(e, "Error recording giving manually")
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "Kosa la kurekodi. Jaribu tena."
                )
            }
        }
    }

    /**
     * Start voice recording.
     */
    fun startRecording() {
        _uiState.value = _uiState.value.copy(isRecording = true, error = null)
    }

    /**
     * Stop voice recording.
     */
    fun stopRecording() {
        _uiState.value = _uiState.value.copy(isRecording = false)
    }

    /**
     * Toggle the manual input form visibility.
     */
    fun toggleForm() {
        _uiState.value = _uiState.value.copy(
            showForm = !_uiState.value.showForm,
            error = null
        )
    }

    /**
     * Clear confirmation message after it's been shown.
     */
    fun clearConfirmation() {
        _uiState.value = _uiState.value.copy(confirmationMessage = null)
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Get giving reminder based on last giving date.
     */
    fun getGivingReminder(): String {
        val records = _uiState.value.titheRecords
        if (records.isEmpty()) return "Anza kutoa leo — hata KSh 50 inatosha! 🙏"

        val lastGiving = records.firstOrNull()?.date ?: return ""
        val daysSince = TimeUnit.MILLISECONDS.toDays(
            System.currentTimeMillis() - lastGiving
        ).toInt()

        return titheTracker.generateGivingReminder(lastGiving, daysSince)
    }

    /**
     * Calculate monthly totals for the last 6 months.
     */
    private suspend fun calculateMonthlyTotals(): List<Pair<String, Double>> {
        val result = mutableListOf<Pair<String, Double>>()
        val calendar = Calendar.getInstance()

        for (i in 5 downTo 0) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.MONTH, -i)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val monthStart = cal.timeInMillis

            cal.add(Calendar.MONTH, 1)
            val monthEnd = cal.timeInMillis

            val total = titheDao.getMonthlyTotal(monthStart, monthEnd) ?: 0.0

            // Format month name
            val monthCal = Calendar.getInstance()
            monthCal.timeInMillis = monthStart
            val monthName = when (monthCal.get(Calendar.MONTH)) {
                Calendar.JANUARY -> "Jan"
                Calendar.FEBRUARY -> "Feb"
                Calendar.MARCH -> "Mar"
                Calendar.APRIL -> "Apr"
                Calendar.MAY -> "Mei"
                Calendar.JUNE -> "Jun"
                Calendar.JULY -> "Jul"
                Calendar.AUGUST -> "Ago"
                Calendar.SEPTEMBER -> "Sep"
                Calendar.OCTOBER -> "Okt"
                Calendar.NOVEMBER -> "Nov"
                Calendar.DECEMBER -> "Des"
                else -> ""
            }

            result.add(Pair(monthName, total))
        }

        return result
    }
}

/**
 * UI State for the Tithe screen.
 */
data class TitheUiState(
    val isLoading: Boolean = false,
    val isRecording: Boolean = false,
    val isProcessing: Boolean = false,
    val showForm: Boolean = false,
    val titheRecords: List<TitheRecord> = emptyList(),
    val totalGiven: Double = 0.0,
    val consistencyScore: Int = 0,
    val streakDays: Int = 0,
    val givingFrequency: String = "",
    val abundancePattern: Double = 0.0,
    val abundanceHistory: Map<String, Double> = emptyMap(),
    val monthlyTotals: List<Pair<String, Double>> = emptyList(),
    val topRecipient: String = "",
    val confirmationMessage: String? = null,
    val error: String? = null
)
