package com.k2fsa.sherpa.onnx

/**
 * Voice Activity Detector (VAD) via sherpa-onnx.
 *
 * Uses Silero VAD to detect speech vs. silence in real-time audio streams.
 * Much more efficient than the raw ONNX Runtime approach — sherpa-onnx
 * handles all the RNN state management internally.
 *
 * This is the JNI bridge to sherpa-onnx's VoiceActivityDetector C++ class.
 */
class VoiceActivityDetector(
    config: VadModelConfig,
    bufferSizeInSeconds: Float = 30.0f
) : AutoCloseable {
    private var ptr: Long

    init {
        SherpaOnnxLoader.ensureAvailable()
        try {
            ptr = newNative(
                config.sileroVad.model,
                config.sileroVad.threshold,
                config.sileroVad.minSilenceDuration,
                config.sileroVad.minSpeechDuration,
                config.sileroVad.maxSpeechDuration,
                config.sileroVad.speechPadLeft,
                config.sileroVad.speechPadRight,
                config.numThreads,
                if (config.debug) 1 else 0,
                config.provider,
                bufferSizeInSeconds,
            )
        } catch (e: Throwable) {
            ptr = 0L
            throw UnsatisfiedLinkError(
                "Failed to initialize VoiceActivityDetector (Silero VAD). " +
                "Model files may be missing or corrupt. Error: ${e.message}"
            )
        }
    }

    /**
     * Accept audio samples for processing.
     * Samples should be FloatArray normalized to [-1, 1] at 16kHz.
     */
    fun acceptWaveform(samples: FloatArray) {
        acceptWaveform(ptr, samples)
    }

    /**
     * Check if speech is currently detected.
     */
    fun isSpeechDetected(): Boolean {
        return isSpeechDetected(ptr) != 0
    }

    /**
     * Pop the earliest detected speech segment.
     * Call this in a loop while [isNotEmpty] returns true.
     */
    fun pop(): SpeechSegment {
        val start = getSegmentStart(ptr)
        val samples = getSegmentSamples(ptr)
        popFront(ptr)
        return SpeechSegment(start = start, samples = samples)
    }

    /**
     * Check if there are detected speech segments waiting.
     */
    fun isNotEmpty(): Boolean {
        return isEmpty(ptr) == 0
    }

    /**
     * Clear all detected segments and reset state.
     */
    fun clear() {
        clear(ptr)
    }

    /**
     * Reset the VAD state for a new session.
     */
    fun reset() {
        reset(ptr)
    }

    /**
     * Flush remaining audio (call when input ends).
     */
    fun flush() {
        flush(ptr)
    }

    override fun close() {
        if (ptr != 0L) {
            free(ptr)
            ptr = 0L
        }
    }

    private external fun newNative(
        model: String,
        threshold: Float,
        minSilenceDuration: Float,
        minSpeechDuration: Float,
        maxSpeechDuration: Float,
        speechPadLeft: Float,
        speechPadRight: Float,
        numThreads: Int,
        debug: Int,
        provider: String,
        bufferSizeInSeconds: Float,
    ): Long

    private external fun acceptWaveform(ptr: Long, samples: FloatArray)
    private external fun isSpeechDetected(ptr: Long): Int
    private external fun getSegmentStart(ptr: Long): Int
    private external fun getSegmentSamples(ptr: Long): FloatArray
    private external fun popFront(ptr: Long)
    private external fun isEmpty(ptr: Long): Int
    private external fun clear(ptr: Long)
    private external fun reset(ptr: Long)
    private external fun flush(ptr: Long)
    private external fun free(ptr: Long)
}
