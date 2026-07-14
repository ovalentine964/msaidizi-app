package com.k2fsa.sherpa.onnx

/**
 * Text-to-Speech engine via sherpa-onnx.
 *
 * Supports VITS-based models including Piper, Kokoro, and MMS.
 * Generates audio samples directly from text input.
 *
 * This is the JNI bridge to sherpa-onnx's OfflineTts C++ class.
 */
class OfflineTts(config: TtsConfig) : AutoCloseable {
    private var ptr: Long

    init {
        SherpaOnnxLoader.checkLoaded()
        ptr = newNative(
            config.model.vits.model,
            config.model.vits.tokens,
            config.model.vits.dataDir,
            config.model.vits.dictDir,
            config.model.vits.lexicon,
            config.model.numThreads,
            if (config.model.debug) 1 else 0,
            config.model.provider,
            config.maxNumSentences,
        )
    }

    /**
     * Generate speech from text.
     *
     * @param text Text to synthesize
     * @param sid Speaker ID (0 for single-speaker models)
     * @param speed Speech rate (1.0 = normal, 0.8 = slower, 1.2 = faster)
     * @return AudioData containing samples and sample rate
     */
    fun generate(text: String, sid: Int = 0, speed: Float = 1.0f): AudioData {
        val samples = generate(ptr, text, sid, speed)
        val sampleRate = getSampleRate(ptr)
        return AudioData(samples = samples, sampleRate = sampleRate)
    }

    /**
     * Get the output sample rate of the model.
     */
    fun getSampleRate(): Int {
        return getSampleRate(ptr)
    }

    override fun close() {
        if (ptr != 0L) {
            free(ptr)
            ptr = 0L
        }
    }

    private external fun newNative(
        model: String,
        tokens: String,
        dataDir: String,
        dictDir: String,
        lexicon: String,
        numThreads: Int,
        debug: Int,
        provider: String,
        maxNumSentences: Int,
    ): Long

    private external fun generate(ptr: Long, text: String, sid: Int, speed: Float): FloatArray
    private external fun getSampleRate(ptr: Long): Int
    private external fun free(ptr: Long)
}

/**
 * Audio data returned by TTS generation.
 */
data class AudioData(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioData) return false
        return sampleRate == other.sampleRate && samples.contentEquals(other.samples)
    }
    override fun hashCode(): Int = sampleRate * 31 + samples.contentHashCode()
}
