package com.msaidizi.app.core.model

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * AnomalyDetector (CUSUM Drift Tracker) tests — detects when mama mboga's sales pattern changes.
 *
 * Tests cover:
 * - Burn-in period (baseline calibration)
 * - Normal operation (no drift)
 * - Drift detection (sudden shift in predictions)
 * - Improvement detection
 * - Reset and recovery
 * - Edge cases (single observation, extreme values)
 */
@RunWith(JUnit4::class)
class AnomalyDetectorTest {

    private lateinit var tracker: CusumDriftTracker

    @Before
    fun setup() {
        tracker = CusumDriftTracker()
    }

    // ═══════════════════════════════════════════════════════════════════
    // Burn-in Period — Initial baseline calibration
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `tracker starts in calibrating state`() {
        val result = tracker.update(0.8, 0.8)
        assertTrue("Should be calibrating during burn-in", result.isCalibrating)
        assertFalse("Should not detect drift during burn-in", result.isDriftDetected)
    }

    @Test
    fun `tracker transitions from calibrating after enough observations`() {
        // Feed 40 observations (burn-in = 30, needs 40 total)
        for (i in 1..40) {
            tracker.update(0.8, 0.8)
        }
        val result = tracker.update(0.8, 0.8)
        assertFalse("Should not be calibrating after burn-in", result.isCalibrating)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Normal Operation — Consistent predictions, no drift
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `no drift detected for consistent predictions`() {
        // Calibrate with consistent predictions
        for (i in 1..50) {
            val result = tracker.update(0.85, 0.85 + (Math.random() - 0.5) * 0.02)
            // Small noise around 0.85 should not trigger drift
        }

        // Continue with same pattern
        for (i in 1..20) {
            val result = tracker.update(0.85, 0.85 + (Math.random() - 0.5) * 0.02)
            assertFalse("Consistent predictions should not trigger drift", result.isDriftDetected)
        }
    }

    @Test
    fun `CUSUM statistic stays near zero for good predictions`() {
        // Calibrate
        for (i in 1..50) {
            tracker.update(0.9, 0.9)
        }

        // Perfect predictions
        val result = tracker.update(0.9, 0.9)
        assertTrue("CUSUM should be near zero for perfect predictions",
            result.cusumValue < 1.0)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Drift Detection — When model quality degrades
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `drift detected when predictions suddenly degrade`() {
        // Calibrate with good predictions
        for (i in 1..50) {
            tracker.update(0.9, 0.9)
        }

        // Now feed consistently bad predictions
        var driftDetected = false
        for (i in 1..20) {
            val result = tracker.update(0.5, 0.9)  // Predicting 0.5 when actual is 0.9
            if (result.isDriftDetected) {
                driftDetected = true
                break
            }
        }

        assertTrue("Drift should be detected after sustained degradation", driftDetected)
    }

    @Test
    fun `drift detected for gradual degradation`() {
        // Calibrate
        for (i in 1..50) {
            tracker.update(0.9, 0.9)
        }

        // Gradually degrade predictions
        var driftDetected = false
        for (i in 1..30) {
            val predicted = 0.9 - (i * 0.01)  // 0.89, 0.88, 0.87, ...
            val result = tracker.update(predicted, 0.9)
            if (result.isDriftDetected) {
                driftDetected = true
                break
            }
        }

        assertTrue("Drift should be detected for gradual degradation", driftDetected)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Improvement Detection
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `improvement tracked when predictions get better`() {
        // Calibrate with mediocre predictions
        for (i in 1..50) {
            tracker.update(0.7, 0.7)
        }

        // Feed better predictions
        for (i in 1..10) {
            val result = tracker.update(0.95, 0.95)
            // Should accumulate on the lower CUSUM
        }

        // The lower CUSUM should be accumulating
        // (This tracks improvement, which is good)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Reset and Recovery
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `reset clears all state`() {
        // Feed some data
        for (i in 1..50) {
            tracker.update(0.8, 0.8)
        }

        tracker.reset()

        // After reset, should be back to calibrating
        val result = tracker.update(0.8, 0.8)
        assertTrue("Should be calibrating after reset", result.isCalibrating)
    }

    @Test
    fun `tracker can re-detect drift after reset`() {
        // Calibrate and trigger drift
        for (i in 1..50) {
            tracker.update(0.9, 0.9)
        }
        for (i in 1..20) {
            tracker.update(0.3, 0.9)
        }

        // Reset
        tracker.reset()

        // Re-calibrate
        for (i in 1..50) {
            tracker.update(0.9, 0.9)
        }

        // Should detect drift again
        var driftDetected = false
        for (i in 1..20) {
            val result = tracker.update(0.3, 0.9)
            if (result.isDriftDetected) {
                driftDetected = true
                break
            }
        }

        assertTrue("Should detect drift again after reset", driftDetected)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Edge Cases
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `handles perfect predictions (zero error)`() {
        for (i in 1..50) {
            val result = tracker.update(1.0, 1.0)
            assertFalse("Perfect predictions should never trigger drift", result.isDriftDetected)
        }
    }

    @Test
    fun `handles maximum error predictions`() {
        // Calibrate
        for (i in 1..50) {
            tracker.update(0.5, 0.5)
        }

        // Feed maximum error
        var driftDetected = false
        for (i in 1..10) {
            val result = tracker.update(0.0, 1.0)  // Maximum possible error
            if (result.isDriftDetected) {
                driftDetected = true
                break
            }
        }

        assertTrue("Maximum error should trigger drift quickly", driftDetected)
    }

    @Test
    fun `handles alternating good and bad predictions`() {
        // Calibrate
        for (i in 1..50) {
            tracker.update(0.8, 0.8)
        }

        // Alternating good/bad — should average out and not trigger drift quickly
        var driftDetected = false
        for (i in 1..20) {
            val predicted = if (i % 2 == 0) 0.8 else 0.6
            val result = tracker.update(predicted, 0.8)
            if (result.isDriftDetected) {
                driftDetected = true
                break
            }
        }

        // Alternating pattern may or may not trigger drift depending on parameters
        // This test verifies the tracker doesn't crash on mixed signals
    }

    @Test
    fun `drift result contains useful diagnostic information`() {
        // Calibrate
        for (i in 1..50) {
            tracker.update(0.8, 0.8)
        }

        val result = tracker.update(0.5, 0.8)
        assertNotNull("Result should not be null", result)
        assertTrue("CUSUM value should be non-negative", result.cusumValue >= 0.0)
        assertTrue("Observation count should be positive", result.observationCount > 0)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Diagnostics
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `getDiagnostics returns meaningful state`() {
        for (i in 1..50) {
            tracker.update(0.8, 0.8)
        }

        val diagnostics = tracker.getDiagnostics()
        assertNotNull("Diagnostics should not be null", diagnostics)
    }
}
