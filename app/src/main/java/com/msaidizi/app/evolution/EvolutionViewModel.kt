package com.msaidizi.app.evolution

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Self-Evolution Dashboard.
 *
 * Shows workers how Msaidizi is evolving based on their feedback:
 * - "Your suggestion: Show market prices every morning → Coming next week!"
 * - "Workers like you requested: Credit readiness score → Now available!"
 * - "Total feedback submitted: 1,247 from 890 workers"
 *
 * This creates a sense of ownership — workers see their feedback
 * shaping the app they use every day. Research shows that responsive
 * feedback loops increase retention by 40%.
 *
 * ECO 206 (Microfinance): Trust is built through demonstrated responsiveness.
 * When workers see their suggestions become features, they engage more deeply.
 */
@HiltViewModel
class EvolutionViewModel @Inject constructor(
    private val feedbackCollector: FeedbackCollector,
    private val featureTracker: FeatureRequestTracker,
    private val sessionManager: com.msaidizi.app.security.auth.SessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(EvolutionUiState())
    val uiState: StateFlow<EvolutionUiState> = _uiState.asStateFlow()

    init {
        loadEvolutionData()
    }

    /**
     * Load all evolution data for the dashboard.
     */
    fun loadEvolutionData() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)

                // Load in parallel
                val topRequests = featureTracker.getTopRequests(10)
                val shippedFeatures = featureTracker.getShippedFeatures()
                val feedbackStats = feedbackCollector.getFeedbackStats()
                val requestStats = featureTracker.getRequestStats()

                _uiState.value = EvolutionUiState(
                    isLoading = false,
                    topRequests = topRequests,
                    shippedFeatures = shippedFeatures,
                    feedbackStats = feedbackStats,
                    totalFeatureClusters = requestStats["totalFeatureClusters"] as? Int ?: 0,
                    totalRequests = requestStats["totalRequests"] as? Int ?: 0,
                    error = null
                )
            } catch (e: Throwable) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load evolution data: ${e.message}"
                )
            }
        }
    }

    /**
     * Submit new feedback from the worker.
     */
    fun submitFeedback(text: String, category: String? = null) {
        viewModelScope.launch {
            try {
                feedbackCollector.collectFromText(
                    workerId = getCurrentWorkerId(),
                    text = text,
                    category = category
                )
                // Track as feature request if applicable
                val feedback = FeedbackCollector.Feedback(
                    workerId = getCurrentWorkerId(),
                    type = FeedbackCollector.FeedbackType.FEATURE_REQUEST,
                    text = text,
                    language = "sw",
                    timestamp = System.currentTimeMillis(),
                    category = category
                )
                featureTracker.trackRequest(feedback)

                // Reload dashboard
                loadEvolutionData()
            } catch (e: Throwable) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to submit feedback: ${e.message}"
                )
            }
        }
    }

    /**
     * Get the worker's own recent feedback.
     */
    fun getMyFeedback(): LiveData<List<FeedbackCollector.Feedback>> {
        val result = MutableLiveData<List<FeedbackCollector.Feedback>>()
        viewModelScope.launch {
            val all = feedbackCollector.getRecentFeedback(50)
            result.postValue(all.filter { it.workerId == getCurrentWorkerId() })
        }
        return result
    }

    /**
     * Sync feedback to backend.
     */
    fun syncFeedback() {
        viewModelScope.launch {
            try {
                val result = feedbackCollector.syncToBackend()
                if (result.isSuccess) {
                    _uiState.value = _uiState.value.copy(
                        lastSyncResult = "Synced ${result.synced} feedback items"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        lastSyncResult = "Sync failed: ${result.error}"
                    )
                }
            } catch (e: Throwable) {
                _uiState.value = _uiState.value.copy(
                    lastSyncResult = "Sync error: ${e.message}"
                )
            }
        }
    }

    fun refresh() {
        loadEvolutionData()
    }

    /**
     * Get current worker ID from the authenticated session.
     * Falls back to device-bound ID when no active session exists.
     */
    private fun getCurrentWorkerId(): String {
        val deviceId = sessionManager.getDeviceId()
        return if (sessionManager.isSessionValid()) {
            // Session is active — use the device ID as the worker identifier
            // (sessions are bound to device + user via SessionManager.startSession)
            "worker_${deviceId.take(16)}"
        } else {
            // No active session — use device ID for anonymous/local tracking
            "worker_${deviceId.take(16)}"
        }
    }
}

/**
 * UI State for the Evolution Dashboard.
 */
data class EvolutionUiState(
    val isLoading: Boolean = false,
    val topRequests: List<FeatureRequestTracker.FeatureRequest> = emptyList(),
    val shippedFeatures: List<FeatureRequestTracker.FeatureRequest> = emptyList(),
    val feedbackStats: FeedbackCollector.FeedbackStats? = null,
    val totalFeatureClusters: Int = 0,
    val totalRequests: Int = 0,
    val lastSyncResult: String? = null,
    val error: String? = null
) {
    /**
     * Human-readable status for a feature request.
     * Maps status to emoji + text for WhatsApp-friendly display.
     */
    fun getStatusEmoji(status: FeatureRequestTracker.RequestStatus): String {
        return when (status) {
            FeatureRequestTracker.RequestStatus.NEW -> "🆕"
            FeatureRequestTracker.RequestStatus.ANALYZING -> "🔍"
            FeatureRequestTracker.RequestStatus.PLANNED -> "📋"
            FeatureRequestTracker.RequestStatus.IN_PROGRESS -> "🔨"
            FeatureRequestTracker.RequestStatus.SHIPPED -> "✅"
        }
    }

    /**
     * Get a motivational message based on evolution progress.
     * Displayed at the top of the dashboard.
     */
    fun getMotivationalMessage(): String {
        return when {
            shippedFeatures.isNotEmpty() ->
                "🎉 Msaidizi is growing! ${shippedFeatures.size} features shipped based on worker feedback."
            topRequests.isNotEmpty() ->
                "💡 ${totalRequests} suggestions from workers are being analyzed. Your voice matters!"
            else ->
                "🗣️ Tell Msaidizi what you need — shape the app you use every day!"
        }
    }
}
