package com.k2fsa.sherpa.onnx

/**
 * Online (streaming) speech recognizer.
 *
 * Used for real-time speech recognition as the user speaks.
 * Supports streaming transducer models (Zipformer, Conformer, etc.)
 * via sherpa-onnx.
 *
 * This is the JNI bridge to sherpa-onnx's OnlineRecognizer C++ class.
 */
class OnlineRecognizer(config: OnlineRecognizerConfig) : AutoCloseable {
    private var ptr: Long

    init {
        SherpaOnnxLoader.ensureAvailable()
        try {
            ptr = newNative(
                config.featConfig.sampleRate,
                config.featConfig.featureDim,
                config.modelConfig.transducer.encoder,
                config.modelConfig.transducer.decoder,
                config.modelConfig.transducer.joiner,
                config.modelConfig.tokens,
                config.modelConfig.numThreads,
                if (config.modelConfig.debug) 1 else 0,
                config.modelConfig.provider,
                config.modelConfig.modelType,
                config.modelConfig.modelingUnit,
                config.modelConfig.bpeVocab,
                if (config.enableEndpoint) 1 else 0,
                config.rule1MinTrailingSilence,
                config.rule2MinTrailingSilence,
                config.rule3MinUtteranceLength,
                config.hotwordsFile,
                config.hotwordsScore,
                config.blankPenalty,
            )
        } catch (e: Throwable) {
            ptr = 0L
            throw UnsatisfiedLinkError(
                "Failed to initialize OnlineRecognizer (streaming ASR). " +
                "Model files may be missing or corrupt. Error: ${e.message}"
            )
        }
    }

    /**
     * Create a new streaming recognition session.
     */
    fun createStream(): OnlineStream {
        return OnlineStream(createStream(ptr))
    }

    /**
     * Decode features from the stream.
     */
    fun decode(stream: OnlineStream) {
        decode(ptr, stream.ptr)
    }

    /**
     * Get the current partial/final result.
     */
    fun getResult(stream: OnlineStream): OnlineRecognitionResult {
        val text = getResult(ptr, stream.ptr)
        val isEndpoint = isEndpoint(ptr, stream.ptr) != 0
        return OnlineRecognitionResult(text = text, isEndpoint = isEndpoint)
    }

    /**
     * Check if an endpoint (utterance boundary) has been detected.
     */
    fun isEndpoint(stream: OnlineStream): Boolean {
        return isEndpoint(ptr, stream.ptr) != 0
    }

    /**
     * Reset the recognizer state for a new utterance.
     */
    fun reset(stream: OnlineStream) {
        reset(ptr, stream.ptr)
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
        encoder: String,
        decoder: String,
        joiner: String,
        tokens: String,
        numThreads: Int,
        debug: Int,
        provider: String,
        modelType: String,
        modelingUnit: String,
        bpeVocab: String,
        enableEndpoint: Int,
        rule1MinTrailingSilence: Float,
        rule2MinTrailingSilence: Float,
        rule3MinUtteranceLength: Float,
        hotwordsFile: String,
        hotwordsScore: Float,
        blankPenalty: Float,
    ): Long

    private external fun createStream(ptr: Long): Long
    private external fun decode(ptr: Long, streamPtr: Long)
    private external fun getResult(ptr: Long, streamPtr: Long): String
    private external fun isEndpoint(ptr: Long, streamPtr: Long): Int
    private external fun reset(ptr: Long, streamPtr: Long)
    private external fun free(ptr: Long)
}

/**
 * Online stream — holds audio data and state for streaming recognition.
 */
class OnlineStream(internal val ptr: Long) : AutoCloseable {
    init {
        SherpaOnnxLoader.ensureAvailable()
    }

    /**
     * Accept audio samples as FloatArray (normalized to [-1, 1]).
     */
    fun acceptWaveform(samples: FloatArray, sampleRate: Int = 16000) {
        acceptWaveform(ptr, samples, sampleRate)
    }

    /**
     * Signal that no more audio will be added.
     */
    fun inputFinished() {
        inputFinished(ptr)
    }

    override fun close() {
        if (ptr != 0L) {
            freeStream(ptr)
        }
    }

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun inputFinished(ptr: Long)
    private external fun freeStream(ptr: Long)
}
