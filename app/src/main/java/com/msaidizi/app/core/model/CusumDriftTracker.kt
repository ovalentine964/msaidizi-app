package com.msaidizi.app.core.model

import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lightweight CUSUM (Cumulative Sum) drift tracker for on-device model monitoring.
 *
 * Detects when a model's prediction quality degrades over time, signaling
 * that the model may need updating. Designed for resource-constrained Android
 * devices — uses O(1) memory per update (sliding window for baseline only).
 *
 * Usage:
 * ```
 * val tracker = CusumDriftTracker()
 * // After each prediction evaluation:
 * val result = tracker.update(predicted = 0.8, actual = 1.0)
 * if (result.isDriftDetected) {
 *     // Signal backend that model may need retraining
 *     reportDrift(result)
 * }
 * ```
 *
 * The CUSUM statistic accumulates standardized deviations from the expected
 * baseline performance. When it exceeds the decision threshold, drift is flagged.
 *
 * Reference: Page, E.S. (1954). Continuous inspection schemes. Biometrika, 41(1/2).
 */
@Singleton
class CusumDriftTracker @Inject constructor() {

    companion object {
        // Default minimum shift to detect (in standard deviation units)
        private const val DEFAULT_DELTA = 1.0

        // Default decision threshold (in standard deviation units)
        private const val DEFAULT_H = 4.0

        // Number of observations to calibrate baseline
        private const val BURN_IN_SIZE = 30

        // Sliding window size for adaptive baseline
        private const val WINDOW_SIZE = 200

        // Minimum observations before drift detection activates
        private const val MIN_OBSERVATIONS = BURN_IN_SIZE + 10
    }

    // CUSUM parameters
    private val delta: Double = DEFAULT_DELTA
    private val h: Double = DEFAULT_H
    private val k: Double = delta / 2.0  // Allowance (slack parameter)

    // State
    private var sUpper: Double = 0.0  // CUSUM for degradation detection
    private var sLower: Double = 0.0  // CUSUM for improvement detection
    private var nObservations: Int = 0
    private var nAlerts: Int = 0
    private var lastAlertIndex: Int = -1

    // Baseline calibration
    private var baselineMean: Double = 0.0
    private var baselineStd: Double = 1.0
    private val burnInValues = mutableListOf<Double>()

    // Sliding window for recent values
    private val recentValues = ConcurrentLinkedDeque<Double>()

    /**
     * Update the tracker with a new prediction-actual pair.
     *
     * Computes an accuracy-like metric (1 - |predicted - actual|) and
     * feeds it into the CUSUM chart.
     *
     * @param predicted The model's predicted value (0.0 to 1.0)
     * @param actual The actual observed value (0.0 to 1.0)
     * @return CusumResult with drift status and current state
     */
    fun update(predicted: Double, actual: Double): CusumResult {
        // Compute accuracy-like metric: 1 - absolute error
        val metric = max(0.0, 1.0 - abs(predicted - actual))
        return updateMetric(metric)
    }

    /**
     * Update the tracker with a pre-computed metric value.
     *
     * Use this when you already have an accuracy/AUC/precision value
     * rather than raw predictions.
     *
     * @param metricValue The observed performance metric (higher = better)
     * @return CusumResult with drift status and current state
     */
    fun updateMetric(metricValue: Double): CusumResult {
        nObservations++

        // Sliding window
        recentValues.addLast(metricValue)
        if (recentValues.size > WINDOW_SIZE) {
            recentValues.removeFirst()
        }

        // Burn-in phase: collect samples to calibrate baseline
        if (nObservations <= BURN_IN_SIZE) {
            burnInValues.add(metricValue)
            if (nObservations == BURN_IN_SIZE) {
                calibrateBaseline()
            }
            return CusumResult(
                isDriftDetected = false,
                isCalibrating = true,
                metricValue = metricValue,
                cusumUpper = 0.0,
                cusumLower = 0.0,
                baselineMean = baselineMean,
                baselineStd = baselineStd,
                observations = nObservations
            )
        }

        // Standardize the observation
        val z = if (baselineStd > 1e-10) {
            (metricValue - baselineMean) / baselineStd
        } else {
            0.0
        }

        // Update CUSUM statistics
        // Upper CUSUM: detects degradation (metric dropping below baseline)
        sUpper = max(0.0, sUpper + (-z) - k)
        // Lower CUSUM: detects improvement (metric rising above baseline)
        sLower = min(0.0, sLower + (-z) + k)

        // Check for drift
        var driftDetected = false
        var driftDirection = DriftDirection.NONE

        if (sUpper > h) {
            driftDetected = true
            driftDirection = DriftDirection.DEGRADATION
            nAlerts++
            lastAlertIndex = nObservations
            Timber.w(
                "CUSUM drift detected: degradation | metric=%.4f | baseline=%.4f | cusum=%.4f | threshold=%.4f",
                metricValue, baselineMean, sUpper, h
            )
            // Reset CUSUM after alert
            sUpper = 0.0
            sLower = 0.0
        } else if (abs(sLower) > h) {
            driftDetected = true
            driftDirection = DriftDirection.IMPROVEMENT
            Timber.i(
                "CUSUM improvement detected: metric=%.4f | baseline=%.4f | cusum=%.4f",
                metricValue, baselineMean, sLower
            )
            // Reset after improvement alert
            sUpper = 0.0
            sLower = 0.0
        }

        return CusumResult(
            isDriftDetected = driftDetected,
            isCalibrating = false,
            metricValue = metricValue,
            cusumUpper = sUpper,
            cusumLower = sLower,
            baselineMean = baselineMean,
            baselineStd = baselineStd,
            observations = nObservations,
            driftDirection = driftDirection,
            alertsTotal = nAlerts
        )
    }

    /**
     * Get the current monitoring status without adding a new observation.
     */
    fun getStatus(): CusumStatus {
        val recentList = recentValues.toList()
        val recentMean = if (recentList.isNotEmpty()) recentList.average() else baselineMean
        val recentStd = if (recentList.size > 1) {
            val mean = recentList.average()
            sqrt(recentList.map { (it - mean) * (it - mean) }.average())
        } else {
            baselineStd
        }

        return CusumStatus(
            observations = nObservations,
            alertsTotal = nAlerts,
            cusumUpper = sUpper,
            cusumLower = sLower,
            baselineMean = baselineMean,
            baselineStd = baselineStd,
            recentMean = recentMean,
            recentStd = recentStd,
            isCalibrated = nObservations >= BURN_IN_SIZE,
            isDriftImminent = sUpper > h * 0.5
        )
    }

    /**
     * Reset the tracker to initial state.
     */
    fun reset() {
        sUpper = 0.0
        sLower = 0.0
        nObservations = 0
        nAlerts = 0
        lastAlertIndex = -1
        baselineMean = 0.0
        baselineStd = 1.0
        burnInValues.clear()
        recentValues.clear()
        Timber.d("CUSUM tracker reset")
    }

    private fun calibrateBaseline() {
        if (burnInValues.isEmpty()) return
        baselineMean = burnInValues.average()
        val variance = burnInValues.map { (it - baselineMean) * (it - baselineMean) }.average()
        baselineStd = max(sqrt(variance), 1e-6)
        Timber.i(
            "CUSUM calibrated: mean=%.4f, std=%.4f, n=%d",
            baselineMean, baselineStd, burnInValues.size
        )
    }
}

/**
 * Result of a single CUSUM update.
 */
data class CusumResult(
    // Whether drift was detected in this observation
    val isDriftDetected: Boolean,
    // Whether the tracker is still in burn-in/calibration phase
    val isCalibrating: Boolean,
    // The metric value that was processed
    val metricValue: Double,
    // Current upper CUSUM value (degradation)
    val cusumUpper: Double,
    // Current lower CUSUM value (improvement)
    val cusumLower: Double,
    // Current baseline mean
    val baselineMean: Double,
    // Current baseline standard deviation
    val baselineStd: Double,
    // Total observations processed
    val observations: Int,
    // Direction of detected drift (if any)
    val driftDirection: DriftDirection = DriftDirection.NONE,
    // Total alerts generated
    val alertsTotal: Int = 0
)

/**
 * Current CUSUM monitoring status.
 */
data class CusumStatus(
    val observations: Int,
    val alertsTotal: Int,
    val cusumUpper: Double,
    val cusumLower: Double,
    val baselineMean: Double,
    val baselineStd: Double,
    val recentMean: Double,
    val recentStd: Double,
    val isCalibrated: Boolean,
    val isDriftImminent: Boolean
)

/**
 * Direction of detected drift.
 */
enum class DriftDirection {
    NONE,
    DEGRADATION,
    IMPROVEMENT
}
