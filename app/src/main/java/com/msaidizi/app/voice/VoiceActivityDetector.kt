package com.msaidizi.app.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Voice Activity Detection using Silero VAD.
 * Detects speech segments in audio streams.
 *
 * Silero VAD model: ~2.5MB ONNX, <1ms per frame.
 * This is a CODE-BASED fallback when ONNX model is not available.
 * The code-based approach uses energy-based detection.
 */
@Singleton
class VoiceActivityDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // Energy thresholds for speech detection
        private const val SPEECH_THRESHOLD = 0.015f  // RMS energy threshold
        private const val SILENCE_THRESHOLD = 0.008f
        private const val MIN_SPEECH_DURATION_MS = 300  // Minimum speech duration
        private const val MAX_SILENCE_DURATION_MS = 1500  // Max silence before end of speech
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE_MS = 30  // 30ms frames
        private const val FRAME_SIZE_SAMPLES = SAMPLE_RATE * FRAME_SIZE_MS / 1000  // 480 samples
    }

    // VAD state
    private var isSpeaking = false
    private var speechStartTime = 0L
    private var lastSpeechTime = 0L
    private var silenceDuration = 0L

    // Audio buffer for collecting speech segments
    private val speechBuffer = mutableListOf<ShortArray>()
    private var totalSpeechSamples = 0

    /**
     * Process an audio chunk and detect speech.
     * Returns true if speech is detected in this chunk.
     */
    fun processChunk(audioData: ShortArray): Boolean {
        val rms = calculateRMS(audioData)
        val now = System.currentTimeMillis()

        return when {
            rms > SPEECH_THRESHOLD -> {
                if (!isSpeaking) {
                    isSpeaking = true
                    speechStartTime = now
                    Timber.d("VAD: Speech started (RMS=%.4f)", rms)
                }
                lastSpeechTime = now
                silenceDuration = 0
                speechBuffer.add(audioData)
                totalSpeechSamples += audioData.size
                true
            }
            rms < SILENCE_THRESHOLD && isSpeaking -> {
                silenceDuration += (audioData.size * 1000L / SAMPLE_RATE)
                if (silenceDuration > MAX_SILENCE_DURATION_MS) {
                    // End of speech
                    isSpeaking = false
                    val speechDuration = now - speechStartTime
                    Timber.d("VAD: Speech ended (duration=%dms, samples=%d)", speechDuration, totalSpeechSamples)
                    false
                } else {
                    // Still in speech, just silent part
                    speechBuffer.add(audioData)
                    totalSpeechSamples += audioData.size
                    true
                }
            }
            else -> {
                // Below speech threshold but above silence
                if (isSpeaking) {
                    speechBuffer.add(audioData)
                    totalSpeechSamples += audioData.size
                    silenceDuration += (audioData.size * 1000L / SAMPLE_RATE)
                }
                isSpeaking
            }
        }
    }

    /**
     * Get the collected speech audio.
     * Returns null if no speech was detected.
     */
    fun getSpeechAudio(): ShortArray? {
        if (speechBuffer.isEmpty() || totalSpeechSamples == 0) return null

        // Check minimum speech duration
        val durationMs = totalSpeechSamples * 1000L / SAMPLE_RATE
        if (durationMs < MIN_SPEECH_DURATION_MS) {
            Timber.d("VAD: Speech too short (%dms < %dms)", durationMs, MIN_SPEECH_DURATION_MS)
            reset()
            return null
        }

        // Concatenate all speech chunks
        val result = ShortArray(totalSpeechSamples)
        var offset = 0
        for (chunk in speechBuffer) {
            chunk.copyInto(result, offset)
            offset += chunk.size
        }

        Timber.d("VAD: Collected %d samples (%.1fs)", totalSpeechSamples, durationMs / 1000.0)
        reset()
        return result
    }

    /**
     * Reset VAD state.
     */
    fun reset() {
        isSpeaking = false
        speechStartTime = 0
        lastSpeechTime = 0
        silenceDuration = 0
        speechBuffer.clear()
        totalSpeechSamples = 0
    }

    /**
     * Check if currently detecting speech.
     */
    fun isDetectingSpeech(): Boolean = isSpeaking

    /**
     * Calculate RMS (Root Mean Square) energy of audio signal.
     * This is the core metric for energy-based VAD.
     */
    private fun calculateRMS(audioData: ShortArray): Float {
        if (audioData.isEmpty()) return 0f

        var sumSquares = 0.0
        for (sample in audioData) {
            val normalized = sample.toFloat() / Short.MAX_VALUE
            sumSquares += normalized * normalized
        }
        return Math.sqrt(sumSquares / audioData.size).toFloat()
    }

    /**
     * Calculate zero-crossing rate (useful for distinguishing speech from noise).
     */
    private fun calculateZeroCrossingRate(audioData: ShortArray): Float {
        if (audioData.size < 2) return 0f

        var crossings = 0
        for (i in 1 until audioData.size) {
            if ((audioData[i] >= 0) != (audioData[i - 1] >= 0)) {
                crossings++
            }
        }
        return crossings.toFloat() / (audioData.size - 1)
    }
}
