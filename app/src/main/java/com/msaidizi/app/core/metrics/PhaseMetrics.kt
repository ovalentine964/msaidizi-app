package com.msaidizi.app.core.metrics

import com.msaidizi.app.data.dao.PhaseMetricsDao
import com.msaidizi.app.data.entity.PhaseMetricEntity
import com.msaidizi.app.data.entity.PhaseMetricAlertEntity
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PhaseMetrics — Per-phase metrics for the cognitive loop.
 * 
 * Each phase (perceive, remember, reason, reflect, act, learn) emits:
 * - Latency (ms) — histogram
 * - Success rate (%) — gauge
 * - Error count — counter
 * 
 * Stored locally in Room DB for offline analysis.
 * Exported in Prometheus format for sync.
 * 
 * Design: arch_android.md, impl_metrics improvement
 */
@Singleton
class PhaseMetrics @Inject constructor(
    private val metricsDao: PhaseMetricsDao
) {
    // In-memory counters for fast access
    private val latencyBuckets = mutableMapOf<String, MutableList<Long>>()
    private val successCounters = mutableMapOf<String, Long>()
    private val errorCounters = mutableMapOf<String, Long>()

    /**
     * Record a phase execution.
     * @param phase One of: perceive, remember, reason, reflect, act, learn, voice_stt, voice_tts
     * @param latencyMs Execution time in milliseconds
     * @param success Whether the phase completed without error
     */
    suspend fun recordPhase(phase: String, latencyMs: Long, success: Boolean) {
        // Update in-memory counters
        latencyBuckets.getOrPut(phase) { mutableListOf() }.add(latencyMs)
        if (success) {
            successCounters[phase] = (successCounters[phase] ?: 0L) + 1
        } else {
            errorCounters[phase] = (errorCounters[phase] ?: 0L) + 1
        }

        // Persist to Room DB
        try {
            metricsDao.insert(
                PhaseMetricEntity(
                    phase = phase,
                    latencyMs = latencyMs,
                    success = success,
                    timestamp = System.currentTimeMillis()
                )
            )

            // Check thresholds and alert if breached
            checkThresholds(phase, latencyMs)
        } catch (e: Exception) {
            Timber.w(e, "Failed to persist phase metric")
        }
    }

    /**
     * Record a successful overall interaction.
     */
    suspend fun recordSuccess(totalLatencyMs: Long) {
        recordPhase("total", totalLatencyMs, true)
    }

    /**
     * Record a failed overall interaction.
     */
    suspend fun recordError(totalLatencyMs: Long) {
        recordPhase("total", totalLatencyMs, false)
    }

    /**
     * Start timing a phase (convenience method).
     */
    fun startPhase(phase: String): Long {
        return System.currentTimeMillis()
    }

    /**
     * End timing a phase and record it.
     */
    suspend fun endPhase(phase: String, startTime: Long, success: Boolean = true) {
        val latencyMs = System.currentTimeMillis() - startTime
        recordPhase(phase, latencyMs, success)
    }

    /**
     * Get aggregated stats for a phase over a time window.
     */
    suspend fun getPhaseStats(phase: String, windowMs: Long = 24 * 60 * 60 * 1000): PhaseStats {
        val since = System.currentTimeMillis() - windowMs
        val entries = try {
            metricsDao.getEntriesSince(phase, since)
        } catch (e: Exception) {
            emptyList()
        }

        if (entries.isEmpty()) return PhaseStats.empty(phase)

        val latencies = entries.map { it.latencyMs }
        val successes = entries.count { it.success }
        val errors = entries.count { !it.success }

        return PhaseStats(
            phase = phase,
            count = entries.size,
            avgLatencyMs = latencies.average().toLong(),
            p50LatencyMs = percentile(latencies, 50),
            p95LatencyMs = percentile(latencies, 95),
            p99LatencyMs = percentile(latencies, 99),
            maxLatencyMs = latencies.maxOrNull() ?: 0,
            successRate = if (entries.isNotEmpty()) successes.toDouble() / entries.size * 100 else 0.0,
            errorCount = errors,
            windowStartMs = since,
            windowEndMs = System.currentTimeMillis()
        )
    }

    /**
     * Get all phase stats for dashboard/debug view.
     */
    suspend fun getAllPhaseStats(windowMs: Long = 24 * 60 * 60 * 1000): Map<String, PhaseStats> {
        val phases = listOf("perceive", "remember", "reason", "reflect", "act", "learn", "voice_stt", "voice_tts", "total")
        return phases.associateWith { getPhaseStats(it, windowMs) }
    }

    /**
     * Get summary stats from in-memory counters (fast, no DB query).
     */
    fun summary(): Map<String, PhaseSummary> {
        val phases = (latencyBuckets.keys + successCounters.keys + errorCounters.keys).toSet()
        return phases.associateWith { phase ->
            val latencies = latencyBuckets[phase] ?: emptyList()
            val successes = successCounters[phase] ?: 0L
            val errors = errorCounters[phase] ?: 0L
            PhaseSummary(
                avgLatencyMs = if (latencies.isNotEmpty()) latencies.average().toLong() else 0L,
                p95LatencyMs = latencies.sorted().let { it.getOrElse((it.size * 0.95).toInt()) { 0L } },
                successCount = successes,
                errorCount = errors,
                successRate = if (successes + errors > 0) successes.toDouble() / (successes + errors) else 1.0
            )
        }
    }

    /**
     * Export metrics in Prometheus text format.
     */
    fun exportPrometheus(): String = buildString {
        appendLine("# HELP msaidizi_phase_latency_ms Cognitive loop phase latency in milliseconds")
        appendLine("# TYPE msaidizi_phase_latency_ms histogram")
        latencyBuckets.forEach { (phase, latencies) ->
            val sorted = latencies.sorted()
            val p50 = sorted.getOrElse(sorted.size / 2) { 0L }
            val p95 = sorted.getOrElse((sorted.size * 0.95).toInt()) { 0L }
            val p99 = sorted.getOrElse((sorted.size * 0.99).toInt()) { 0L }
            appendLine("msaidizi_phase_latency_ms{phase=\"$phase\",quantile=\"0.5\"} $p50")
            appendLine("msaidizi_phase_latency_ms{phase=\"$phase\",quantile=\"0.95\"} $p95")
            appendLine("msaidizi_phase_latency_ms{phase=\"$phase\",quantile=\"0.99\"} $p99")
        }
        appendLine("# HELP msaidizi_phase_success_total Successful phase executions")
        appendLine("# TYPE msaidizi_phase_success_total counter")
        successCounters.forEach { (phase, count) ->
            appendLine("msaidizi_phase_success_total{phase=\"$phase\"} $count")
        }
        appendLine("# HELP msaidizi_phase_error_total Phase execution errors")
        appendLine("# TYPE msaidizi_phase_error_total counter")
        errorCounters.forEach { (phase, count) ->
            appendLine("msaidizi_phase_error_total{phase=\"$phase\"} $count")
        }
    }

    /**
     * Cleanup old metrics (retention: 7 days).
     */
    suspend fun cleanup() {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000
        try {
            metricsDao.deleteBefore(cutoff)
            metricsDao.deleteAlertsBefore(cutoff)
        } catch (e: Exception) {
            Timber.w(e, "Metrics cleanup failed")
        }
    }

    private suspend fun checkThresholds(phase: String, latencyMs: Long) {
        val threshold = THRESHOLDS[phase] ?: return

        when {
            latencyMs > threshold.criticalMs -> {
                Timber.e("CRITICAL: Phase $phase took ${latencyMs}ms (threshold: ${threshold.criticalMs}ms)")
                try {
                    metricsDao.insertAlert(
                        PhaseMetricAlertEntity(
                            phase = phase,
                            level = "critical",
                            message = "Latency ${latencyMs}ms exceeds critical threshold ${threshold.criticalMs}ms",
                            timestamp = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Failed to insert alert")
                }
            }
            latencyMs > threshold.warningMs -> {
                Timber.w("WARNING: Phase $phase took ${latencyMs}ms (threshold: ${threshold.warningMs}ms)")
            }
        }
    }

    private fun percentile(sorted: List<Long>, p: Int): Long {
        if (sorted.isEmpty()) return 0
        val index = (p / 100.0 * (sorted.size - 1)).toInt()
        return sorted.sorted()[index]
    }

    companion object {
        data class ThresholdConfig(
            val warningMs: Long,
            val criticalMs: Long,
            val successRateWarning: Double
        )

        val THRESHOLDS = mapOf(
            "perceive" to ThresholdConfig(warningMs = 200, criticalMs = 500, successRateWarning = 95.0),
            "remember" to ThresholdConfig(warningMs = 50, criticalMs = 200, successRateWarning = 90.0),
            "reason" to ThresholdConfig(warningMs = 500, criticalMs = 2000, successRateWarning = 85.0),
            "reflect" to ThresholdConfig(warningMs = 1000, criticalMs = 3000, successRateWarning = 80.0),
            "act" to ThresholdConfig(warningMs = 200, criticalMs = 1000, successRateWarning = 90.0),
            "learn" to ThresholdConfig(warningMs = 100, criticalMs = 500, successRateWarning = 90.0),
            "voice_stt" to ThresholdConfig(warningMs = 1000, criticalMs = 3000, successRateWarning = 85.0),
            "voice_tts" to ThresholdConfig(warningMs = 500, criticalMs = 2000, successRateWarning = 90.0)
        )
    }
}

data class PhaseStats(
    val phase: String,
    val count: Int,
    val avgLatencyMs: Long,
    val p50LatencyMs: Long,
    val p95LatencyMs: Long,
    val p99LatencyMs: Long,
    val maxLatencyMs: Long,
    val successRate: Double,
    val errorCount: Int,
    val windowStartMs: Long,
    val windowEndMs: Long
) {
    companion object {
        fun empty(phase: String) = PhaseStats(
            phase = phase, count = 0, avgLatencyMs = 0, p50LatencyMs = 0,
            p95LatencyMs = 0, p99LatencyMs = 0, maxLatencyMs = 0,
            successRate = 0.0, errorCount = 0, windowStartMs = 0, windowEndMs = 0
        )
    }
}

data class PhaseSummary(
    val avgLatencyMs: Long,
    val p95LatencyMs: Long,
    val successCount: Long,
    val errorCount: Long,
    val successRate: Double
)
