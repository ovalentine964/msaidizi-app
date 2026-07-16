package com.k2fsa.sherpa.onnx

/**
 * Offline (non-streaming) speech recognizer.
 *
 * Used for batch transcription of complete audio utterances.
 * Supports Whisper, Moonshine, SenseVoice, Paraformer, and other
 * non-streaming ASR models via sherpa-onnx.
 *
 * This is the JNI bridge to sherpa-onnx's OfflineRecognizer C++ class.
 */
class OfflineRecognizer(config: OfflineRecognizerConfig) : AutoCloseable {
    private var ptr: Long

    init {
        if (!SherpaOnnxLoader.checkLoaded()) {
            throw UnsatisfiedLinkError("sherpa-onnx JNI not available")
        }
        ptr = newNative(
            config.featConfig.sampleRate,
            config.featConfig.featureDim,
            config.modelConfig.whisper?.encoder ?: "",
            config.modelConfig.whisper?.decoder ?: "",
            config.modelConfig.whisper?.language ?: "sw",
            config.modelConfig.whisper?.task ?: "transcribe",
            config.modelConfig.whisper?.tailPaddings ?: -1,
            config.modelConfig.tokens,
            config.modelConfig.numThreads,
            if (config.modelConfig.debug) 1 else 0,
            config.modelConfig.provider,
            config.modelConfig.modelType,
            config.modelConfig.modelingUnit,
            config.modelConfig.bpeVocab,
            config.hotwordsFile,
            config.hotwordsScore,
            config.blankPenalty,
        )
    }

    /**
     * Create a new stream for recognition.
     */
    fun createStream(): OfflineStream {
        return OfflineStream(createStream(ptr))
    }

    /**
     * Decode audio samples (FloatArray, normalized to [-1, 1]).
     * Returns the recognition result.
     */
    fun decode(stream: OfflineStream) {
        decode(ptr, stream.ptr)
    }

    /**
     * Get the result after decoding.
     */
    fun getResult(stream: OfflineStream): OfflineRecognitionResult {
        val text = getResult(ptr, stream.ptr)
        return OfflineRecognitionResult(text = text)
    }

    override fun close() {
        if (ptr != 0L) {
            free(ptr)
            ptr = 0L
        }
    }

    private external fun newNative(
        sampleRate: Int,
        featureDim: Int,
        whisperEncoder: String,
        whisperDecoder: String,
        whisperLanguage: String,
        whisperTask: String,
        whisperTailPaddings: Int,
        tokens: String,
        numThreads: Int,
        debug: Int,
        provider: String,
        modelType: String,
        modelingUnit: String,
        bpeVocab: String,
        hotwordsFile: String,
        hotwordsScore: Float,
        blankPenalty: Float,
    ): Long

    private external fun createStream(ptr: Long): Long
    private external fun decode(ptr: Long, streamPtr: Long)
    private external fun getResult(ptr: Long, streamPtr: Long): String
    private external fun free(ptr: Long)
}

/**
 * Offline stream — holds audio data for non-streaming recognition.
 */
class OfflineStream(internal val ptr: Long) : AutoCloseable {
    init {
        if (!SherpaOnnxLoader.checkLoaded()) {
            throw UnsatisfiedLinkError("sherpa-onnx JNI not available")
        }
    }

    /**
     * Accept audio samples as FloatArray (normalized to [-1, 1]).
     */
    fun acceptWaveform(samples: FloatArray, sampleRate: Int = 16000) {
        acceptWaveform(ptr, samples, sampleRate)
    }

    override fun close() {
        if (ptr != 0L) {
            freeStream(ptr)
        }
    }

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun freeStream(ptr: Long)
}
