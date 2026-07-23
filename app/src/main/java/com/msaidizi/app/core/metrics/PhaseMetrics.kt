package com.msaidizi.app.core.metrics

import javax.inject.Inject
import javax.inject.Singleton

/**
 * PhaseMetrics — Per-phase metrics for the cognitive loop.
 * 
 * Each phase (perceive, remember, reason, reflect, act, learn) emits:
 * - Latency (ms)
 * - Success rate (%)
 * - Error count
 * 
 * Stored locally in Room DB for offline analysis.
 * Synced to backend Prometheus when connected.
 * 
 * Design: arch_android.md, impl_metrics improvement
 */
@Singleton
class PhaseMetrics @Inject constructor() {
    private val metrics = mutableMapOf<String, PhaseMetricData>()
    
    fun startPhase(phase: String) {
        metrics[phase] = PhaseMetricData(
            startTime = System.currentTimeMillis(),
            phase = phase
        )
    }
    
    fun endPhase(phase: String, latencyMs: Long) {
        val data = metrics[phase] ?: return
        metrics[phase] = data.copy(
            endTime = System.currentTimeMillis(),
            latencyMs = latencyMs
        )
    }
    
    fun recordSuccess(totalLatencyMs: Long) {
        // TODO: Store in Room DB
        // TODO: Sync to backend Prometheus when connected
    }
    
    fun recordError(totalLatencyMs: Long) {
        // TODO: Store in Room DB
        // TODO: Sync to backend Prometheus when connected
    }
    
    fun getPhaseMetrics(phase: String): PhaseMetricData? = metrics[phase]
    
    fun getAllMetrics(): Map<String, PhaseMetricData> = metrics.toMap()
}

data class PhaseMetricData(
    val phase: String,
    val startTime: Long = 0,
    val endTime: Long = 0,
    val latencyMs: Long = 0,
    val successCount: Int = 0,
    val errorCount: Int = 0
)
