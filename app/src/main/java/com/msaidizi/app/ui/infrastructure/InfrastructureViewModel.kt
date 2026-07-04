package com.msaidizi.app.ui.infrastructure

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.data.api.MsaidiziApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Shows workers how their usage drives the data center.
 *
 * "Your transactions this month: KSh 45,000"
 * "Infrastructure contribution: KSh 675 (1.5%)"
 * "Progress to next phase: 67%"
 * "Your benefit: 2x faster responses coming soon"
 *
 * Workers see their impact → feel ownership → stay engaged
 */
@HiltViewModel
class InfrastructureViewModel @Inject constructor(
    private val api: MsaidiziApi
) : ViewModel() {

    private val _roadmap = MutableLiveData<DataCenterRoadmap>()
    val roadmap: LiveData<DataCenterRoadmap> = _roadmap

    private val _workerContribution = MutableLiveData<WorkerContribution>()
    val workerContribution: LiveData<WorkerContribution> = _workerContribution

    private val _phaseBenefits = MutableLiveData<List<PhaseBenefit>>()
    val phaseBenefits: LiveData<List<PhaseBenefit>> = _phaseBenefits

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    init {
        loadRoadmap()
    }

    fun loadRoadmap() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null

                // Load roadmap data from backend
                val roadmapResponse = api.getInfrastructureRoadmap()
                if (roadmapResponse.isSuccessful) {
                    roadmapResponse.body()?.let { data ->
                        _roadmap.value = data
                        _phaseBenefits.value = data.allPhases.map { phase ->
                            PhaseBenefit(
                                phaseId = phase.phaseId,
                                phaseName = phase.phaseName,
                                status = phase.status,
                                description = phase.description,
                                workerBenefits = phase.workerBenefits,
                                latencyTargetMs = phase.latencyTargetMs,
                                isCurrentPhase = phase.phaseId == data.currentPhase.phaseId,
                            )
                        }
                    }
                }

                // Load worker contribution
                val contributionResponse = api.getWorkerContribution()
                if (contributionResponse.isSuccessful) {
                    contributionResponse.body()?.let { data ->
                        _workerContribution.value = data
                    }
                }
            } catch (e: Exception) {
                _error.value = "Failed to load infrastructure data: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refresh() {
        loadRoadmap()
    }
}

// ---------------------------------------------------------------------------
// Data models matching backend API responses
// ---------------------------------------------------------------------------

data class DataCenterRoadmap(
    val currentPhase: PhaseInfo,
    val progressToNext: PhaseProgress,
    val allPhases: List<PhaseInfo>,
    val timeline: PhaseTimeline,
    val message: String,
)

data class PhaseInfo(
    val phaseId: Int,
    val phaseName: String,
    val status: String,  // "current", "future", "completed"
    val description: String,
    val capacity: String,
    val latencyTargetMs: Int,
    val costMonthlyUsd: Int,
    val workerBenefits: List<String>,
    val minWorkersRequired: Int,
    val minMonthlyRevenueUsd: Long,
    val infraBudgetUsd: Long,
)

data class PhaseProgress(
    val currentPhase: Int,
    val currentPhaseName: String,
    val nextPhase: Int?,
    val nextPhaseName: String?,
    val progressPct: Double,
    val breakdown: ProgressBreakdown,
    val barriers: List<Barrier>,
    val message: String,
)

data class ProgressBreakdown(
    val workers: Double,
    val revenue: Double,
    val fund: Double,
)

data class Barrier(
    val type: String,  // "workers", "monthly_revenue", "infra_fund"
    val current: Long?,
    val target: Long?,
    val gap: Long?,
    val progressPct: Double,
)

data class PhaseTimeline(
    val phase1Start: String,
    val phase2Target: String,
    val phase3Target: String,
    val phase4Target: String,
    val phase5Target: String,
)

data class WorkerContribution(
    val workerId: String,
    val estimatedMonthlyDataValueUsd: Double,
    val infraAllocationPct: Double,
    val estimatedMonthlyContributionUsd: Double,
    val estimatedMonthlyContributionKes: Int,
    val message: String,
)

data class PhaseBenefit(
    val phaseId: Int,
    val phaseName: String,
    val status: String,
    val description: String,
    val workerBenefits: List<String>,
    val latencyTargetMs: Int,
    val isCurrentPhase: Boolean,
)
