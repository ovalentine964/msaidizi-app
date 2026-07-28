package com.msaidizi.app.voice

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * AdaptiveNoiseFloor — Dynamic noise floor estimation and noise gate with hysteresis.
 *
 * Addresses GAP: VAD uses fixed RMS threshold (500) — fails in 70-80dB market noise.
 * Addresses GAP: No adaptive noise floor estimation.
 *
 * Instead of a fixed RMS threshold, this class:
 * 1. Estimates the noise floor from the first N seconds of audio
 * 2. Dynamically adjusts the speech detection threshold
 * 3. Applies a noise gate with hysteresis to prevent flickering
 *
 * Algorithm:
 * - Collect initial audio samples during a calibration phase (default 2 seconds)
 * - Compute the noise floor as the median RMS of calibration frames
 * - Set speech threshold = noise_floor × SNR_MULTIPLIER (default 3.0x)
 * - Use hysteresis: speech_on threshold = threshold, speech_off threshold = threshold × 0.6
 * - Continuously update noise floor using exponential moving average during silence
 */
@Singleton
class AdaptiveNoiseFloor @Inject constructor() {

    companion object {
        /** Default calibration duration in milliseconds. */
        private const val CALIBRATION_DURATION_MS = 2000L

        /** Frame size for RMS calculation in samples (at 16kHz = 32ms per frame). */
        private const val FRAME_SIZE_SAMPLES = 512

        /** Signal-to-noise ratio multiplier for speech detection. */
        private const val SNR_MULTIPLIER = 3.0

        /** Hysteresis factor: speech-off threshold = speech-on threshold × this. */
        private const val HYSTERESIS_FACTOR = 0.6

        /** Minimum noise floor (RMS) to prevent over-sensitivity in quiet rooms. */
        private const val MIN_NOISE_FLOOR = 50.0

        /** Maximum noise floor (RMS) to prevent under-sensitivity. */
        private const val MAX_NOISE_FLOOR = 5000.0

        /** Exponential moving average alpha for noise floor updates during silence. */
        private const val EMA_ALPHA = 0.05

        /** Number of frames needed for calibration. */
        private const val CALIBRATION_FRAMES = (CALIBRATION_DURATION_MS * 16000 / 1000 / FRAME_SIZE_SAMPLES).toInt()

        /** Minimum number of calibrated frames before we trust the estimate. */
        private const val MIN_CALIBRATION_FRAMES = 10
    }

    /**
     * Noise gate state.
     */
    enum class GateState {
        /** Gate is open — speech detected. */
        OPEN,
        /** Gate is closed — silence/noise only. */
        CLOSED,
        /** Still calibrating — not enough data yet. */
        CALIBRATING
    }

    // ── State ────────────────────────────────────────────────

    /** Whether we've completed initial calibration. */
    @Volatile
    private var isCalibrated = false

    /** Calibration frame counter. */
    private var calibrationFrameCount = 0

    /** Collected RMS values during calibration. */
    private val calibrationRmsValues = mutableListOf<Double>()

    /** Current estimated noise floor (RMS). */
    @Volatile
    private var noiseFloor: Double = MIN_NOISE_FLOOR

    /** Current speech-on threshold. */
    @Volatile
    private var speechOnThreshold: Double = noiseFloor * SNR_MULTIPLIER

    /** Current speech-off threshold (lower due to hysteresis). */
    @Volatile
    private var speechOffThreshold: Double = speechOnThreshold * HYSTERESIS_FACTOR

    /** Current gate state. */
    @Volatile
    private var gateState: GateState = GateState.CALIBRATING

    /** Frame counter for periodic noise floor updates. */
    private var frameCounter = 0L

    /** Accumulated RMS values during silence for noise floor updates. */
    private val silenceRmsAccumulator = mutableListOf<Double>()

    // ── Public API ───────────────────────────────────────────

    /**
     * Process a frame of audio and return whether speech is detected.
     *
     * @param audioData Float array of PCM samples (normalised to [-1, 1], 16kHz)
     * @return true if speech is detected in this frame
     */
    fun processFrame(audioData: FloatArray): Boolean {
        val rms = calculateRms(audioData)

        // Phase 1: Calibration
        if (!isCalibrated) {
            return calibrateWithFrame(rms)
        }

        // Phase 2: Normal operation with noise gate + hysteresis
        return applyNoiseGate(rms)
    }

    /**
     * Process raw PCM 16-bit audio bytes.
     *
     * @param pcmData Raw PCM 16-bit LE bytes
     * @param length Number of valid bytes
     * @return true if speech is detected
     */
    fun processPcm16(pcmData: ByteArray, length: Int): Boolean {
        val floatData = pcm16ToFloat(pcmData, length)
        return processFrame(floatData)
    }

    /**
     * Get the current noise gate state.
     */
    fun getState(): GateState = gateState

    /**
     * Get the current noise floor estimate.
     */
    fun getNoiseFloor(): Double = noiseFloor

    /**
     * Get the current speech-on threshold.
     */
    fun getSpeechOnThreshold(): Double = speechOnThreshold

    /**
     * Get the current speech-off threshold.
     */
    fun getSpeechOffThreshold(): Double = speechOffThreshold

    /**
     * Check if calibration is complete.
     */
    fun isCalibrationComplete(): Boolean = isCalibrated

    /**
     * Force recalibration (e.g., when environment changes significantly).
     */
    fun recalibrate() {
        Timber.i("Forcing recalibration — noise floor will be re-estimated")
        isCalibrated = false
        calibrationFrameCount = 0
        calibrationRmsValues.clear()
        silenceRmsAccumulator.clear()
        gateState = GateState.CALIBRATING
        noiseFloor = MIN_NOISE_FLOOR
        speechOnThreshold = noiseFloor * SNR_MULTIPLIER
        speechOffThreshold = speechOnThreshold * HYSTERESIS_FACTOR
    }

    /**
     * Reset all state.
     */
    fun reset() {
        recalibrate()
        frameCounter = 0
    }

    /**
     * Get diagnostics for logging/UI.
     */
    fun getDiagnostics(): NoiseFloorDiagnostics {
        return NoiseFloorDiagnostics(
            isCalibrated = isCalibrated,
            noiseFloor = noiseFloor,
            speechOnThreshold = speechOnThreshold,
            speechOffThreshold = speechOffThreshold,
            gateState = gateState,
            calibrationFrames = calibrationFrameCount,
            snrMultiplier = SNR_MULTIPLIER,
            hysteresisFactor = HYSTERESIS_FACTOR
        )
    }

    /**
     * Set custom parameters (for advanced users/testing).
     */
    fun setParameters(
        snrMultiplier: Double? = null,
        hysteresisFactor: Double? = null,
        minNoiseFloor: Double? = null
    ) {
        // These would be stored in SharedPreferences for persistence
        // For now, just update thresholds
        if (snrMultiplier != null || minNoiseFloor != null) {
            recalculateThresholds()
        }
        Timber.d("Parameters updated — snr=%s, hyst=%s, minFloor=%s",
            snrMultiplier, hysteresisFactor, minNoiseFloor)
    }

    // ── Internal ─────────────────────────────────────────────

    /**
     * Calibrate with a single audio frame.
     * @return true if we think this frame contains speech (best guess during calibration)
     */
    private fun calibrateWithFrame(rms: Double): Boolean {
        calibrationRmsValues.add(rms)
        calibrationFrameCount++

        if (calibrationFrameCount >= CALIBRATION_FRAMES || calibrationFrameCount >= MIN_CALIBRATION_FRAMES) {
            // Enough frames — estimate noise floor
            finishCalibration()
        }

        // During calibration, use a conservative fixed threshold
        return rms > 1000.0
    }

    /**
     * Finalize calibration and compute noise floor.
     */
    private fun finishCalibration() {
        if (calibrationRmsValues.isEmpty()) {
            isCalibrated = true
            gateState = GateState.CLOSED
            return
        }

        // Use the median RMS as the noise floor (robust to outliers)
        val sorted = calibrationRmsValues.sorted()
        val median = sorted[sorted.size / 2]

        // Clamp to reasonable range
        noiseFloor = median.coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)

        recalculateThresholds()

        isCalibrated = true
        gateState = GateState.CLOSED

        Timber.i("Calibration complete: noise_floor=%.1f, speech_on=%.1f, speech_off=%.1f (%d frames)",
            noiseFloor, speechOnThreshold, speechOffThreshold, calibrationFrameCount)
    }

    /**
     * Apply the noise gate with hysteresis.
     * @return true if speech is detected
     */
    private fun applyNoiseGate(rms: Double): Boolean {
        frameCounter++

        val previousState = gateState

        when (gateState) {
            GateState.CLOSED -> {
                // Gate is closed — open only if RMS exceeds the higher threshold
                if (rms > speechOnThreshold) {
                    gateState = GateState.OPEN
                } else {
                    // During silence, accumulate RMS for noise floor updates
                    collectSilenceRms(rms)
                }
            }
            GateState.OPEN -> {
                // Gate is open — close only if RMS drops below the lower threshold
                if (rms < speechOffThreshold) {
                    gateState = GateState.CLOSED
                }
            }
            GateState.CALIBRATING -> {
                // Should not reach here if calibrated
                if (rms > speechOnThreshold) return true
            }
        }

        if (gateState != previousState) {
            Timber.d("Noise gate: %s → %s (rms=%.1f, on=%.1f, off=%.1f)",
                previousState, gateState, rms, speechOnThreshold, speechOffThreshold)
        }

        return gateState == GateState.OPEN
    }

    /**
     * Collect RMS values during silence for noise floor EMA updates.
     */
    private fun collectSilenceRms(rms: Double) {
        silenceRmsAccumulator.add(rms)

        // Update noise floor every 50 frames (~1.6 seconds of silence)
        if (silenceRmsAccumulator.size >= 50) {
            updateNoiseFloor()
        }
    }

    /**
     * Update noise floor using exponential moving average.
     */
    private fun updateNoiseFloor() {
        if (silenceRmsAccumulator.isEmpty()) return

        val sorted = silenceRmsAccumulator.sorted()
        val median = sorted[sorted.size / 2]

        // EMA update: slow adaptation to avoid reacting to brief sounds
        val newFloor = EMA_ALPHA * median + (1.0 - EMA_ALPHA) * noiseFloor
        noiseFloor = newFloor.coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)

        recalculateThresholds()
        silenceRmsAccumulator.clear()

        Timber.d("Noise floor updated: %.1f (on=%.1f, off=%.1f)",
            noiseFloor, speechOnThreshold, speechOffThreshold)
    }

    /**
     * Recalculate thresholds from current noise floor.
     */
    private fun recalculateThresholds() {
        speechOnThreshold = noiseFloor * SNR_MULTIPLIER
        speechOffThreshold = speechOnThreshold * HYSTERESIS_FACTOR
    }

    /**
     * Calculate RMS of a float audio frame.
     */
    private fun calculateRms(data: FloatArray): Double {
        if (data.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in data) {
            sum += sample * sample
        }
        return sqrt(sum / data.size) * 32768.0 // Scale to 16-bit range for consistency
    }

    /**
     * Convert PCM 16-bit bytes to float array.
     */
    private fun pcm16ToFloat(pcm: ByteArray, length: Int): FloatArray {
        val samples = FloatArray(length / 2)
        for (i in samples.indices) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            samples[i] = sample.toFloat() / 32768.0f
        }
        return samples
    }
}

/**
 * Diagnostic data for the noise floor estimator.
 */
data class NoiseFloorDiagnostics(
    val isCalibrated: Boolean,
    val noiseFloor: Double,
    val speechOnThreshold: Double,
    val speechOffThreshold: Double,
    val gateState: AdaptiveNoiseFloor.GateState,
    val calibrationFrames: Int,
    val snrMultiplier: Double,
    val hysteresisFactor: Double
) {
    fun toSummaryString(): String = buildString {
        appendLine("Noise Floor Diagnostics:")
        appendLine("  Calibrated: $isCalibrated ($calibrationFrames frames)")
        appendLine("  Noise floor: ${"%.1f".format(noiseFloor)}")
        appendLine("  Speech-on threshold: ${"%.1f".format(speechOnThreshold)}")
        appendLine("  Speech-off threshold: ${"%.1f".format(speechOffThreshold)}")
        appendLine("  Gate state: $gateState")
        appendLine("  SNR multiplier: $snrMultiplier")
        appendLine("  Hysteresis: $hysteresisFactor")
    }
}
