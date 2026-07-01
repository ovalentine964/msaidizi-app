package com.msaidizi.app.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Silero VAD (Voice Activity Detection) using ONNX Runtime.
 *
 * Detects speech vs. silence in real-time audio streams.
 * Uses the Silero VAD model which is based on a small neural network
 * with RNN hidden state that maintains context across chunks.
 *
 * Model: silero_vad.onnx (~2.5MB)
 * Input chunk size: 512 samples = 32ms at 16kHz
 * Output: speech probability [0.0, 1.0]
 *
 * Features:
 * - ONNX inference with RNN hidden state management
 * - Speech start/end event detection
 * - Configurable silence timeout for end-of-speech
 * - Fallback to energy-based detection if model unavailable
 * - Low CPU usage (1 inference thread)
 *
 * Performance:
 * - Inference: <5ms per 512-sample chunk
 * - Memory: ~5MB when loaded
 * - CPU: <1% on Helio G25
 */
@Singleton
class VoiceActivityDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRegistry: ModelRegistry
) {
    companion object {
        private const val MODEL_ID = "silero-vad"
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SIZE_SAMPLES = 512  // 32ms at 16kHz
        private const val SPEECH_THRESHOLD = 0.5f
        private const val ENERGY_THRESHOLD = 300.0  // RMS threshold for energy fallback
        private const val MAX_SILENCE_DURATION_MS = 1500L  // 1.5s silence → end of speech
        private const val MIN_SPEECH_DURATION_MS = 250L  // Ignore very short bursts
        private const val HIDDEN_STATE_SIZE = 2 * 1 * 64  // [2, 1, 64] for Silero VAD v4
    }

    // ────────────── ONNX State ──────────────
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isSileroLoaded = false

    // RNN hidden state (must persist across chunks for temporal context)
    private var hState = FloatArray(HIDDEN_STATE_SIZE)
    private var cState = FloatArray(HIDDEN_STATE_SIZE)

    // ────────────── VAD State Machine ──────────────
    enum class VadState {
        SILENCE,     // No speech detected
        SPEECH_START,// Speech just began
        SPEAKING,    // Actively speaking
        SPEECH_END   // Speech just ended (silence timeout)
    }

    private var currentState = VadState.SILENCE
    private var isSpeaking = false
    private var speechStartTime = 0L
    private var lastSpeechTime = 0L
    private var silenceDuration = 0L
    private var totalSpeechSamples = 0L

    /** Accumulated speech audio buffer */
    private val speechBuffer = mutableListOf<ShortArray>()

    /** Callback for speech events */
    var onSpeechStart: (() -> Unit)? = null
    var onSpeechEnd: ((ShortArray) -> Unit)? = null


    // ────────────────────── Model Lifecycle ──────────────────────

    /**
     * Load Silero VAD ONNX model.
     * Falls back to energy-based detection if model not available.
     */
    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        if (isSileroLoaded) return@withContext true

        val modelFile = modelRegistry.getModelPath(MODEL_ID)
        if (modelFile == null) {
            Timber.w("Silero VAD model not found, will use energy-based fallback")
            return@withContext false
        }

        try {
            val startTime = System.currentTimeMillis()

            ortEnvironment = OrtEnvironment.getEnvironment()

            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)  // VAD needs only 1 thread
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            ortSession = ortEnvironment!!.createSession(
                modelFile.absolutePath,
                sessionOptions
            )

            isSileroLoaded = true
            val elapsed = System.currentTimeMillis() - startTime
            Timber.i("Silero VAD model loaded in %dms", elapsed)
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load Silero VAD model")
            isSileroLoaded = false
            false
        }
    }

    /**
     * Unload model to free memory.
     */
    fun unloadModel() {
        ortSession?.close()
        ortSession = null
        ortEnvironment = null
        isSileroLoaded = false
        resetState()
        Timber.d("Silero VAD model unloaded")
    }

    fun isModelReady(): Boolean = isSileroLoaded

    // ────────────────────── Public API ──────────────────────

    /**
     * Process an audio chunk and return speech probability.
     *
     * @param audioData Audio samples at 16kHz mono 16-bit PCM
     * @return Speech probability [0.0, 1.0]
     */
    fun processChunk(audioData: ShortArray): Float {
        val speechProb = if (isSileroLoaded) {
            processSileroChunk(audioData)
        } else {
            processEnergyChunk(audioData)
        }

        val isSpeech = speechProb > SPEECH_THRESHOLD
        val now = System.currentTimeMillis()

        when {
            isSpeech && !isSpeaking -> {
                // Speech started
                isSpeaking = true
                speechStartTime = now
                lastSpeechTime = now
                silenceDuration = 0
                currentState = VadState.SPEECH_START
                speechBuffer.clear()
                speechBuffer.add(audioData)
                totalSpeechSamples = audioData.size.toLong()
                onSpeechStart?.invoke()
                Timber.d("VAD: Speech started (prob=%.2f)", speechProb)
            }

            isSpeech && isSpeaking -> {
                // Continue speaking
                lastSpeechTime = now
                silenceDuration = 0
                currentState = VadState.SPEAKING
                speechBuffer.add(audioData)
                totalSpeechSamples += audioData.size
            }

            !isSpeech && isSpeaking -> {
                // Possible silence during speech
                silenceDuration += (audioData.size * 1000L / SAMPLE_RATE)

                if (silenceDuration >= MAX_SILENCE_DURATION_MS) {
                    // Speech ended — check minimum duration
                    val speechDurationMs = now - speechStartTime
                    if (speechDurationMs >= MIN_SPEECH_DURATION_MS) {
                        currentState = VadState.SPEECH_END
                        isSpeaking = false
                        val completeAudio = getAccumulatedAudio()
                        resetSileroState()
                        onSpeechEnd?.invoke(completeAudio)
                        Timber.d(
                            "VAD: Speech ended (duration=%dms, samples=%d)",
                            speechDurationMs, totalSpeechSamples
                        )
                    } else {
                        // Too short — discard as noise
                        isSpeaking = false
                        currentState = VadState.SILENCE
                        resetSileroState()
                        speechBuffer.clear()
                        Timber.d("VAD: Short noise discarded (%dms)", speechDurationMs)
                    }
                } else {
                    // Still in speech, add trailing audio
                    speechBuffer.add(audioData)
                    totalSpeechSamples += audioData.size
                }
            }

            else -> {
                // Pure silence
                currentState = VadState.SILENCE
            }
        }

        return speechProb
    }

    /**
     * Process audio chunk and return whether speech is detected.
     */
    fun isSpeechDetected(audioData: ShortArray): Boolean {
        return processChunk(audioData) > SPEECH_THRESHOLD
    }

    /**
     * Get the accumulated speech audio since last speech start.
     */
    fun getAccumulatedAudio(): ShortArray {
        val totalSamples = speechBuffer.sumOf { it.size }
        val result = ShortArray(totalSamples)
        var offset = 0
        for (chunk in speechBuffer) {
            System.arraycopy(chunk, 0, result, offset, chunk.size)
            offset += chunk.size
        }
        return result
    }

    /**
     * Reset all VAD state (call at start of new interaction).
     */
    fun reset() {
        isSpeaking = false
        speechStartTime = 0
        lastSpeechTime = 0
        silenceDuration = 0
        totalSpeechSamples = 0
        currentState = VadState.SILENCE
        speechBuffer.clear()
        resetSileroState()
    }

    /**
     * Get current VAD state.
     */
    fun getState(): VadState = currentState

    /**
     * Check if currently in speech.
     */
    fun isCurrentlySpeaking(): Boolean = isSpeaking

    /**
     * Get speech duration in milliseconds (0 if not speaking).
     */
    fun getSpeechDurationMs(): Long {
        return if (isSpeaking) {
            System.currentTimeMillis() - speechStartTime
        } else 0L
    }

    // ────────────────────── Silero ONNX Inference ──────────────────────

    /**
     * Process audio chunk with Silero VAD ONNX model.
     *
     * Silero expects exactly 512 samples per chunk (32ms at 16kHz).
     * For larger chunks, processes in 512-sample windows and returns max probability.
     *
     * @param audioData Audio samples at 16kHz
     * @return Maximum speech probability across all windows [0.0, 1.0]
     */
    private fun processSileroChunk(audioData: ShortArray): Float {
        var maxProb = 0f

        // Process in 512-sample windows
        for (offset in 0 until audioData.size step WINDOW_SIZE_SAMPLES) {
            val end = minOf(offset + WINDOW_SIZE_SAMPLES, audioData.size)
            val window = audioData.copyOfRange(offset, end)

            // Pad to 512 if needed (last window may be shorter)
            val padded = if (window.size < WINDOW_SIZE_SAMPLES) {
                ShortArray(WINDOW_SIZE_SAMPLES).also { window.copyInto(it) }
            } else window

            val prob = runSileroInference(padded)
            maxProb = maxOf(maxProb, prob)
        }

        return maxProb
    }

    /**
     * Run a single Silero VAD inference on a 512-sample window.
     *
     * Model inputs:
     * - "input": float32 [1, 512] — audio samples normalized to [-1, 1]
     * - "sr": int64 [1] — sample rate (16000)
     * - "h": float32 [2, 1, 64] — RNN hidden state (h)
     * - "c": float32 [2, 1, 64] — RNN cell state (c)
     *
     * Model outputs:
     * - "output": float32 [1, 1] — speech probability
     * - "hn": float32 [2, 1, 64] — updated h state
     * - "cn": float32 [2, 1, 64] — updated c state
     */
    private fun runSileroInference(audioWindow: ShortArray): Float {
        return try {
            // Convert to float normalized [-1, 1]
            val floatAudio = FloatArray(WINDOW_SIZE_SAMPLES) { i ->
                audioWindow[i].toFloat() / Short.MAX_VALUE
            }

            // Create input tensors
            val inputTensor = OnnxTensor.createTensor(
                ortEnvironment!!,
                FloatBuffer.wrap(floatAudio),
                longArrayOf(1, WINDOW_SIZE_SAMPLES.toLong())
            )

            val srTensor = OnnxTensor.createTensor(
                ortEnvironment!!,
                LongBuffer.wrap(longArrayOf(SAMPLE_RATE.toLong())),
                longArrayOf(1)
            )

            val hTensor = OnnxTensor.createTensor(
                ortEnvironment!!,
                FloatBuffer.wrap(hState),
                longArrayOf(2, 1, 64)
            )

            val cTensor = OnnxTensor.createTensor(
                ortEnvironment!!,
                FloatBuffer.wrap(cState),
                longArrayOf(2, 1, 64)
            )

            val inputs = mapOf(
                "input" to inputTensor,
                "sr" to srTensor,
                "h" to hTensor,
                "c" to cTensor
            )

            // Run inference
            val results = ortSession!!.run(inputs)

            // Extract speech probability
            val output = results.get("output")
            val prob = (output.value as Array<FloatArray>)[0][0]

            // Update RNN hidden state for next chunk
            val hn = results.get("hn")
            val cn = results.get("cn")
            hState = flattenNestedArray(hn.value)
            cState = flattenNestedArray(cn.value)

            // Cleanup tensors
            inputTensor.close()
            srTensor.close()
            hTensor.close()
            cTensor.close()
            results.close()

            prob
        } catch (e: Exception) {
            Timber.w(e, "Silero inference error, falling back to energy")
            processEnergyChunk(audioWindow)
        }
    }

    /**
     * Flatten nested array from ONNX output to FloatArray.
     * Handles [2][1][64] → FloatArray(128)
     */
    @Suppress("UNCHECKED_CAST")
    private fun flattenNestedArray(value: Any?): FloatArray {
        return when (value) {
            is Array<*> -> {
                val result = mutableListOf<Float>()
                fun flatten(arr: Any?) {
                    when (arr) {
                        is Array<*> -> arr.forEach { flatten(it) }
                        is Float -> result.add(arr)
                        is Double -> result.add(arr.toFloat())
                        is Number -> result.add(arr.toFloat())
                    }
                }
                flatten(value)
                result.toFloatArray()
            }
            else -> FloatArray(HIDDEN_STATE_SIZE)
        }
    }

    // ────────────────────── Energy-Based Fallback ──────────────────────

    /**
     * Energy-based voice activity detection (fallback).
     * Uses RMS (Root Mean Square) of audio amplitude.
     *
     * @param audioData Audio samples
     * @return Speech probability estimate [0.0, 1.0]
     */
    private fun processEnergyChunk(audioData: ShortArray): Float {
        val rms = calculateRMS(audioData)
        // Map RMS to probability: 0 at ENERGY_THRESHOLD, 1.0 at 3x threshold
        return ((rms - ENERGY_THRESHOLD) / (ENERGY_THRESHOLD * 2))
            .coerceIn(0.0, 1.0)
            .toFloat()
    }

    /**
     * Calculate Root Mean Square of audio samples.
     */
    private fun calculateRMS(audioData: ShortArray): Double {
        if (audioData.isEmpty()) return 0.0
        var sumSquares = 0.0
        for (sample in audioData) {
            sumSquares += sample.toDouble() * sample.toDouble()
        }
        return kotlin.math.sqrt(sumSquares / audioData.size)
    }

    // ────────────────────── State Management ──────────────────────

    /**
     * Reset Silero RNN hidden state.
     * Must be called at the start of each new utterance.
     */
    private fun resetSileroState() {
        hState = FloatArray(HIDDEN_STATE_SIZE)
        cState = FloatArray(HIDDEN_STATE_SIZE)
    }

    private fun resetState() {
        isSpeaking = false
        speechStartTime = 0
        lastSpeechTime = 0
        silenceDuration = 0
        totalSpeechSamples = 0
        currentState = VadState.SILENCE
        speechBuffer.clear()
        resetSileroState()
    }
}
