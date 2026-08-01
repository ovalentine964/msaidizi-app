package com.msaidizi.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * StreamingSttEngine — Real-time streaming speech-to-text via sherpa-onnx.
 *
 * Unlike the offline [SherpaOnnxEngine] which requires the full audio buffer
 * before recognition, this engine processes audio incrementally, providing
 * **partial transcription results as the user speaks** — eliminating the
 * "wait for silence" UX bottleneck.
 *
 * Uses sherpa-onnx's online recognizer API with:
 * - Streaming transducer models (Zipformer, Emformer, Conformer)
 * - Silero VAD-based endpoint detection for automatic utterance segmentation
 * - Partial results with incremental decoding
 *
 * Model layout (expected under models/sherpa-onnx/streaming/):
 * ```
 * streaming/
 *   encoder.onnx          — streaming encoder
 *   decoder.onnx          — streaming decoder (prediction network)
 *   joiner.onnx           — transducer joiner (optional for CTC models)
 *   tokens.txt            — token vocabulary
 * ```
 *
 * Supported model families:
 * - Zipformer transducer (recommended: best accuracy/speed tradeoff)
 * - Emformer transducer (lowest latency)
 * - Paraformer streaming
 * - Zipformer2 CTC streaming
 * - NeMo CTC streaming
 *
 * For 2GB RAM devices: use the "tiny" or "small" variant of any model.
 * Recommended: sherpa-onnx-streaming-zipformer-small-bilingual-sw-en (≈45MB)
 */
@Singleton
class StreamingSttEngine @Inject constructor() {

    companion object {
        init {
            try {
                System.loadLibrary("sherpa_jni")
                Timber.i("sherpa_jni native library loaded (streaming STT)")
            } catch (e: UnsatisfiedLinkError) {
                Timber.e(e, "Failed to load sherpa_jni — streaming STT unavailable")
            }
        }

        /** Default sample rate for streaming recognition */
        const val DEFAULT_SAMPLE_RATE = 16000

        /** Chunk size in samples (320 samples = 20ms at 16kHz) for low-latency streaming */
        const val CHUNK_SIZE_SAMPLES = 320

        /** Chunk size in bytes (PCM16) */
        const val CHUNK_SIZE_BYTES = CHUNK_SIZE_SAMPLES * 2
    }

    /** Current state of the streaming recognizer */
    data class StreamingState(
        val isActive: Boolean = false,
        val partialText: String = "",
        val finalText: String = "",
        val isEndpoint: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(StreamingState())
    val state: StateFlow<StreamingState> = _state.asStateFlow()

    private var recognizerHandle: Long = 0L
    private var isCreated = false

    // Accumulated final segments (from endpoint detections)
    private val finalizedSegments = mutableListOf<String>()

    // ── Native methods ───────────────────────────────────────

    internal external fun nativeCreateStreamingRecognizer(configJson: String): Long
    internal external fun nativeAcceptWaveform(handle: Long, audioData: FloatArray, sampleRate: Int)
    internal external fun nativeAcceptWaveformPcm16(handle: Long, pcmData: ByteArray, sampleRate: Int)
    internal external fun nativeIsReady(handle: Long): Boolean
    internal external fun nativeDecode(handle: Long)
    internal external fun nativeGetResult(handle: Long): String
    internal external fun nativeIsEndpoint(handle: Long): Boolean
    internal external fun nativeReset(handle: Long)
    internal external fun nativeDestroy(handle: Long)

    // ── Public API ───────────────────────────────────────────

    /**
     * Create a streaming recognizer with the given model paths.
     *
     * @param encoderPath Path to the streaming encoder ONNX model
     * @param decoderPath Path to the streaming decoder ONNX model
     * @param joinerPath Path to the joiner ONNX model (empty for CTC models)
     * @param tokensPath Path to the tokens.txt file
     * @param language Target language code ("sw", "en", or "auto")
     * @param numThreads Number of CPU threads (2 recommended for budget phones)
     * @return true if the recognizer was created successfully
     */
    fun createRecognizer(
        encoderPath: String,
        decoderPath: String,
        joinerPath: String = "",
        tokensPath: String,
        language: String = "sw",
        numThreads: Int = 2
    ): Boolean {
        if (isCreated) {
            Timber.w("Streaming recognizer already created — destroy first")
            return true
        }

        return try {
            val configJson = buildString {
                append("{")
                append("\"encoder\":\"${encoderPath.escapeJson()}\",")
                append("\"decoder\":\"${decoderPath.escapeJson()}\",")
                if (joinerPath.isNotEmpty()) {
                    append("\"joiner\":\"${joinerPath.escapeJson()}\",")
                }
                append("\"tokens\":\"${tokensPath.escapeJson()}\",")
                append("\"language\":\"${language.escapeJson()}\",")
                append("\"num_threads\":$numThreads,")
                append("\"debug\":0,")
                append("\"decoding_method\":\"greedy_search\",")
                // Endpoint detection thresholds (tuned for conversational speech)
                append("\"rule1_min_trailing_silence\":2.4,")
                append("\"rule2_min_trailing_silence\":1.2,")
                append("\"rule3_min_utterance_length\":20.0")
                append("}")
            }

            Timber.i("Creating streaming recognizer: lang=%s, threads=%d", language, numThreads)
            recognizerHandle = nativeCreateStreamingRecognizer(configJson)
            isCreated = recognizerHandle != 0L

            if (isCreated) {
                Timber.i("Streaming recognizer created — handle=%d", recognizerHandle)
                _state.value = StreamingState(isActive = true)
            } else {
                Timber.e("Streaming recognizer creation returned null handle")
                _state.value = StreamingState(error = "Failed to create streaming recognizer")
            }
            isCreated
        } catch (e: Exception) {
            Timber.e(e, "createRecognizer failed")
            _state.value = StreamingState(error = e.message)
            false
        }
    }

    /**
     * Feed audio data (float samples) for streaming recognition.
     * Call this as audio chunks arrive from the microphone.
     *
     * @param audioData Float array of PCM samples (normalised to [-1, 1], 16kHz mono)
     * @param sampleRate Sample rate (default 16000)
     */
    fun acceptWaveform(audioData: FloatArray, sampleRate: Int = DEFAULT_SAMPLE_RATE) {
        if (!isCreated) return
        nativeAcceptWaveform(recognizerHandle, audioData, sampleRate)
    }

    /**
     * Feed PCM 16-bit audio data for streaming recognition.
     * Convenience method that converts PCM16 bytes internally.
     *
     * @param pcmData Raw PCM 16-bit LE bytes (16kHz mono)
     * @param sampleRate Sample rate (default 16000)
     */
    fun acceptPcm16(pcmData: ByteArray, sampleRate: Int = DEFAULT_SAMPLE_RATE) {
        if (!isCreated) return
        nativeAcceptWaveformPcm16(recognizerHandle, pcmData, sampleRate)
    }

    /**
     * Process available audio and get the current partial/final result.
     *
     * Call this after [acceptWaveform] or [acceptPcm16]. If an endpoint
     * is detected, the result is finalized and the stream is reset for
     * the next utterance.
     *
     * @return Current transcription text (partial or final)
     */
    fun decodeAndGetResult(): String {
        if (!isCreated) return ""

        // Decode all available audio frames
        while (nativeIsReady(recognizerHandle)) {
            nativeDecode(recognizerHandle)
        }

        // Get partial result
        val partialResult = nativeGetResult(recognizerHandle)

        // Check for endpoint (utterance boundary)
        val isEndpoint = nativeIsEndpoint(recognizerHandle)
        if (isEndpoint && partialResult.isNotBlank()) {
            // Endpoint detected — finalize this segment
            finalizedSegments.add(partialResult)
            Timber.i("Streaming STT endpoint: '%s'", partialResult)
            nativeReset(recognizerHandle)

            val fullText = finalizedSegments.joinToString(" ")
            _state.value = _state.value.copy(
                partialText = "",
                finalText = fullText,
                isEndpoint = true
            )
            return fullText
        }

        // Update partial text
        _state.value = _state.value.copy(
            partialText = partialResult,
            isEndpoint = false
        )
        return partialResult
    }

    /**
     * Feed audio and immediately decode for the lowest latency path.
     * Combines [acceptPcm16] + [decodeAndGetResult] in one call.
     *
     * @param pcmData PCM16 bytes (16kHz mono)
     * @return Current transcription text
     */
    fun feedAndDecode(pcmData: ByteArray): String {
        acceptPcm16(pcmData)
        return decodeAndGetResult()
    }

    /**
     * Get all finalized text segments concatenated.
     */
    fun getFinalizedText(): String {
        return finalizedSegments.joinToString(" ")
    }

    /**
     * Reset the streaming recognizer for a new conversation turn.
     * Clears all finalized segments and resets internal state.
     */
    fun reset() {
        if (!isCreated) return
        nativeReset(recognizerHandle)
        finalizedSegments.clear()
        _state.value = StreamingState(isActive = true)
        Timber.d("Streaming recognizer reset")
    }

    /**
     * Check if the streaming recognizer is created and ready.
     */
    fun isReady(): Boolean = isCreated

    /**
     * Destroy the streaming recognizer and free memory.
     */
    fun destroy() {
        if (!isCreated) return
        try {
            nativeDestroy(recognizerHandle)
            Timber.i("Streaming recognizer destroyed — handle=%d", recognizerHandle)
        } catch (e: Exception) {
            Timber.e(e, "destroy failed")
        } finally {
            recognizerHandle = 0L
            isCreated = false
            finalizedSegments.clear()
            _state.value = StreamingState()
        }
    }

    /**
     * Get engine status for diagnostics.
     */
    fun getStatus(): Map<String, Any> = mapOf(
        "created" to isCreated,
        "handle" to recognizerHandle,
        "finalizedSegments" to finalizedSegments.size,
        "isActive" to _state.value.isActive
    )

    // ── Utilities ────────────────────────────────────────────

    private fun String.escapeJson(): String =
        replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
