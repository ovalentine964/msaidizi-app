package com.msaidizi.voice

/**
 * Common interface for speech engines (on-device or cloud).
 *
 * Abstracts over sherpa-onnx (on-device) and Riva NIM (cloud) engines,
 * allowing [SpeechEngineRouter] to transparently route between them.
 */
interface SpeechEngine {

    /** Human-readable engine name for diagnostics. */
    val engineName: String

    /** Whether this engine is currently available and ready. */
    suspend fun isAvailable(): Boolean

    /**
     * Transcribe audio to text.
     *
     * @param audioData Audio samples as float array (normalised to [-1, 1])
     * @param sampleRate Sample rate of the audio (default 16000)
     * @return Recognised text, or empty string on failure
     */
    suspend fun recognize(audioData: FloatArray, sampleRate: Int = 16000): String

    /**
     * Transcribe raw PCM 16-bit audio to text.
     *
     * @param pcmData Raw PCM 16-bit LE bytes
     * @param sampleRate Sample rate (default 16000)
     * @return Recognised text
     */
    suspend fun recognizeFromPcm16(pcmData: ByteArray, sampleRate: Int = 16000): String {
        val floatData = SherpaOnnxEngine.AudioUtils.pcm16ToFloat(pcmData)
        return recognize(floatData, sampleRate)
    }

    /**
     * Synthesize text to audio.
     *
     * @param text Text to speak
     * @param language Language code ("sw", "en")
     * @param speed Speech rate multiplier (0.5–2.0, default 1.0)
     * @return PCM 16-bit LE byte array, or empty array on failure
     */
    suspend fun synthesize(text: String, language: String = "sw", speed: Float = 1.0f): ByteArray

    /** Release resources. */
    fun release()
}
