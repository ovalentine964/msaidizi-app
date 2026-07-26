package com.msaidizi.app.voice

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VadEngine — Kotlin JNI wrapper for on-device Voice Activity Detection via sherpa-onnx.
 *
 * Uses Silero VAD for robust, on-device voice activity detection.
 * Replaces naive amplitude-based VAD with a learned model.
 *
 * Usage:
 * ```kotlin
 * val engine = VadEngine()
 * val handle = engine.createVad("/path/to/silero_vad.onnx")
 * val isSpeech = engine.processAudio(handle, audioSamples)
 * engine.destroyVad(handle)
 * ```
 */
@Singleton
class VadEngine @Inject constructor() {

    companion object {
        init {
            try {
                System.loadLibrary("vad_jni")
                Timber.i("vad_jni native library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Timber.e(e, "Failed to load vad_jni — VAD unavailable")
            }
        }
    }

    // ── Native methods (defined in vad_jni.cpp) ──────────────

    /**
     * Create a VAD detector with the given model.
     * @param modelPath Path to the Silero VAD ONNX model
     * @param threshold Speech detection threshold (0.0–1.0, default 0.5)
     * @param minSilenceDuration Minimum silence duration to mark end of speech (seconds)
     * @param minSpeechDuration Minimum speech duration to trigger detection (seconds)
     * @param maxSpeechDuration Maximum speech segment duration (seconds)
     * @return VAD handle (>0) on success, 0 on failure
     */
    internal external fun nativeCreateVad(
        modelPath: String,
        threshold: Float,
        minSilenceDuration: Float,
        minSpeechDuration: Float,
        maxSpeechDuration: Float
    ): Long

    /**
     * Feed audio samples and check for speech activity.
     * @param handle VAD handle from [nativeCreateVad]
     * @param audioData Float array of PCM samples (normalised to [-1, 1], 16kHz)
     * @return true if speech is detected in the provided audio
     */
    internal external fun nativeProcessAudio(handle: Long, audioData: FloatArray): Boolean

    /**
     * Check current VAD state without feeding new audio.
     * @param handle VAD handle
     * @return true if speech is currently detected
     */
    internal external fun nativeIsSpeech(handle: Long): Boolean

    /**
     * Reset VAD internal state.
     * @param handle VAD handle
     */
    internal external fun nativeReset(handle: Long)

    /**
     * Destroy a VAD detector and free memory.
     * @param handle VAD handle from [nativeCreateVad]
     */
    internal external fun nativeDestroyVad(handle: Long)

    // ── Public API ───────────────────────────────────────────

    private var vadHandle: Long = 0L
    private var isCreated = false

    /**
     * Create a VAD detector with default settings.
     * @param modelPath Path to the Silero VAD ONNX model
     * @return true if the VAD was created successfully
     */
    fun createVad(modelPath: String): Boolean {
        return createVad(
            modelPath = modelPath,
            threshold = 0.5f,
            minSilenceDuration = 0.5f,
            minSpeechDuration = 0.25f,
            maxSpeechDuration = 30.0f
        )
    }

    /**
     * Create a VAD detector with explicit parameters.
     * @param modelPath Path to the Silero VAD ONNX model
     * @param threshold Speech detection threshold (0.0–1.0)
     * @param minSilenceDuration Minimum silence to end speech (seconds)
     * @param minSpeechDuration Minimum speech to trigger (seconds)
     * @param maxSpeechDuration Maximum speech segment (seconds)
     * @return true if the VAD was created successfully
     */
    fun createVad(
        modelPath: String,
        threshold: Float = 0.5f,
        minSilenceDuration: Float = 0.5f,
        minSpeechDuration: Float = 0.25f,
        maxSpeechDuration: Float = 30.0f
    ): Boolean {
        if (isCreated) {
            Timber.w("VAD already created — destroy first")
            return true
        }

        return try {
            Timber.i("Creating VAD: model=%s threshold=%.2f", modelPath, threshold)
            vadHandle = nativeCreateVad(
                modelPath, threshold,
                minSilenceDuration, minSpeechDuration, maxSpeechDuration
            )
            isCreated = vadHandle != 0L

            if (isCreated) {
                Timber.i("VAD created — handle=%d", vadHandle)
            } else {
                Timber.e("VAD creation returned null handle")
            }
            isCreated
        } catch (e: Exception) {
            Timber.e(e, "createVad failed")
            false
        }
    }

    /**
     * Feed audio samples and check for speech activity.
     * @param audioData Audio samples as float array (normalised to [-1, 1], 16kHz mono)
     * @return true if speech is detected
     */
    fun processAudio(audioData: FloatArray): Boolean {
        if (!isCreated) {
            Timber.w("processAudio called but no VAD created")
            return false
        }

        return try {
            nativeProcessAudio(vadHandle, audioData)
        } catch (e: Exception) {
            Timber.e(e, "processAudio failed")
            false
        }
    }

    /**
     * Check current VAD state without feeding new audio.
     * @return true if speech is currently detected
     */
    fun isSpeech(): Boolean {
        if (!isCreated) return false

        return try {
            nativeIsSpeech(vadHandle)
        } catch (e: Exception) {
            Timber.e(e, "isSpeech failed")
            false
        }
    }

    /**
     * Reset VAD internal state (clears speech/silence counters).
     */
    fun reset() {
        if (!isCreated) return

        try {
            nativeReset(vadHandle)
            Timber.d("VAD reset")
        } catch (e: Exception) {
            Timber.e(e, "reset failed")
        }
    }

    /**
     * Destroy the VAD detector and free memory.
     */
    fun destroyVad() {
        if (!isCreated) return
        try {
            nativeDestroyVad(vadHandle)
            Timber.i("VAD destroyed — handle=%d", vadHandle)
        } catch (e: Exception) {
            Timber.e(e, "destroyVad failed")
        } finally {
            vadHandle = 0L
            isCreated = false
        }
    }

    /**
     * Check if the VAD is currently created and ready.
     */
    fun isReady(): Boolean = isCreated

    /**
     * Get engine status for diagnostics.
     */
    fun getStatus(): Map<String, Any> = mapOf(
        "created" to isCreated,
        "handle" to vadHandle
    )
}
