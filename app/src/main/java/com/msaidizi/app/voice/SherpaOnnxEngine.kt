package com.msaidizi.app.voice

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SherpaOnnxEngine — Kotlin JNI wrapper for on-device ASR and TTS via sherpa-onnx.
 *
 * Provides:
 * - **Speech-to-Text** (offline): Whisper ONNX model for Kiswahili & English
 * - **Text-to-Speech** (offline): Piper ONNX model for voice output
 *
 * Usage:
 * ```kotlin
 * val engine = SherpaOnnxEngine()
 * // ASR
 * val recogHandle = engine.createRecognizer(config)
 * val text = engine.recognize(recogHandle, audioSamples, 16000)
 * engine.destroyRecognizer(recogHandle)
 * // TTS
 * val ttsHandle = engine.createSynthesizer(config)
 * val audio = engine.synthesize(ttsHandle, "Habari yako?", 0, 1.0f)
 * engine.destroySynthesizer(ttsHandle)
 * ```
 */
@Singleton
class SherpaOnnxEngine @Inject constructor() {

    companion object {
        init {
            try {
                System.loadLibrary("sherpa_jni")
                Timber.i("sherpa_jni native library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Timber.e(e, "Failed to load sherpa_jni — ASR/TTS unavailable")
            }
        }
    }

    // ── Native methods (defined in sherpa_jni.cpp) ───────────

    // --- ASR (Recognizer) ---

    /**
     * Create an offline recogniser from a JSON config.
     * @param configJson JSON with encoder, decoder, tokens paths
     * @return Recogniser handle (>0) on success, 0 on failure
     */
    internal external fun nativeCreateRecognizer(configJson: String): Long

    /**
     * Recognise speech from audio samples.
     * @param handle Recogniser handle
     * @param audioData Float array of PCM samples (normalised to [-1, 1])
     * @param sampleRate Audio sample rate (e.g., 16000)
     * @return Recognised text
     */
    internal external fun nativeRecognize(handle: Long, audioData: FloatArray, sampleRate: Int): String

    /**
     * Destroy a recogniser and free memory.
     */
    internal external fun nativeDestroyRecognizer(handle: Long)

    // --- TTS (Synthesizer) ---

    /**
     * Create a TTS synthesiser from a JSON config.
     * @param configJson JSON with model, tokens, data-dir paths
     * @return Synthesiser handle (>0) on success, 0 on failure
     */
    internal external fun nativeCreateSynthesizer(configJson: String): Long

    /**
     * Synthesise speech from text.
     * @param handle Synthesiser handle
     * @param text Text to speak
     * @param sid Speaker ID (0 for single-speaker models)
     * @param speed Speech rate multiplier (1.0 = normal)
     * @return Float array of PCM audio samples (22050 Hz, mono, float32)
     */
    internal external fun nativeSynthesize(handle: Long, text: String, sid: Int, speed: Float): FloatArray

    /**
     * Destroy a synthesiser and free memory.
     */
    internal external fun nativeDestroySynthesizer(handle: Long)

    // ── Public API — ASR ─────────────────────────────────────

    private var recognizerHandle: Long = 0L
    private var synthesizerHandle: Long = 0L
    private var recognizerCreated = false
    private var synthesizerCreated = false

    /**
     * Create an ASR recogniser with the given model paths.
     * @param encoderPath Path to the encoder ONNX model
     * @param decoderPath Path to the decoder ONNX model (or empty for streaming)
     * @param tokensPath Path to the tokens.txt file
     * @param language Target language code ("sw", "en")
     * @param numThreads Number of CPU threads
     * @return true if the recogniser was created successfully
     */
    fun createRecognizer(
        encoderPath: String,
        decoderPath: String = "",
        tokensPath: String,
        language: String = "sw",
        numThreads: Int = 2
    ): Boolean {
        if (recognizerCreated) {
            Timber.w("Recognizer already created — destroy first")
            return true
        }

        return try {
            val configJson = buildString {
                append("{")
                append("\"encoder\":\"${encoderPath.escapeJson()}\",")
                append("\"decoder\":\"${decoderPath.escapeJson()}\",")
                append("\"tokens\":\"${tokensPath.escapeJson()}\",")
                append("\"language\":\"${language.escapeJson()}\",")
                append("\"num_threads\":$numThreads,")
                append("\"use_itn\":false,")
                append("\"decoding_method\":\"greedy_search\"")
                append("}")
            }

            Timber.i("Creating recognizer: lang=%s, threads=%d", language, numThreads)
            recognizerHandle = nativeCreateRecognizer(configJson)
            recognizerCreated = recognizerHandle != 0L

            if (recognizerCreated) {
                Timber.i("Recognizer created — handle=%d", recognizerHandle)
            } else {
                Timber.e("Recognizer creation returned null handle")
            }
            recognizerCreated
        } catch (e: Exception) {
            Timber.e(e, "createRecognizer failed")
            false
        }
    }

    /**
     * Recognise speech from PCM audio data.
     * @param audioData Audio samples as float array (normalised to [-1, 1])
     * @param sampleRate Sample rate of the audio (default 16000)
     * @return Recognised text, or empty string on failure
     */
    fun recognize(audioData: FloatArray, sampleRate: Int = 16000): String {
        if (!recognizerCreated) {
            Timber.w("recognize called but no recognizer created")
            return ""
        }

        return try {
            nativeRecognize(recognizerHandle, audioData, sampleRate)
        } catch (e: Exception) {
            Timber.e(e, "recognize failed")
            ""
        }
    }

    /**
     * Recognise speech from raw PCM bytes (16-bit LE).
     * Convenience method that converts to float array first.
     * @param pcmData Raw PCM 16-bit LE bytes
     * @param sampleRate Sample rate (default 16000)
     * @return Recognised text
     */
    fun recognizeFromPcm16(pcmData: ByteArray, sampleRate: Int = 16000): String {
        val floatData = pcm16ToFloat(pcmData)
        return recognize(floatData, sampleRate)
    }

    /**
     * Destroy the current recogniser and free memory.
     */
    fun destroyRecognizer() {
        if (!recognizerCreated) return
        try {
            nativeDestroyRecognizer(recognizerHandle)
            Timber.i("Recognizer destroyed — handle=%d", recognizerHandle)
        } catch (e: Exception) {
            Timber.e(e, "destroyRecognizer failed")
        } finally {
            recognizerHandle = 0L
            recognizerCreated = false
        }
    }

    // ── Public API — TTS ─────────────────────────────────────

    /**
     * Create a TTS synthesiser with the given model paths.
     * @param modelPath Path to the Piper ONNX model
     * @param tokensPath Path to the tokens file
     * @param dataDir Path to the data directory (espeak-ng data)
     * @param numThreads Number of CPU threads
     * @return true if the synthesiser was created successfully
     */
    fun createSynthesizer(
        modelPath: String,
        tokensPath: String = "",
        dataDir: String = "",
        numThreads: Int = 2
    ): Boolean {
        if (synthesizerCreated) {
            Timber.w("Synthesizer already created — destroy first")
            return true
        }

        return try {
            val configJson = buildString {
                append("{")
                append("\"model\":\"${modelPath.escapeJson()}\",")
                if (tokensPath.isNotEmpty()) {
                    append("\"tokens\":\"${tokensPath.escapeJson()}\",")
                }
                if (dataDir.isNotEmpty()) {
                    append("\"data_dir\":\"${dataDir.escapeJson()}\",")
                }
                append("\"num_threads\":$numThreads,")
                append("\"debug\":false")
                append("}")
            }

            Timber.i("Creating synthesizer: model=%s", modelPath)
            synthesizerHandle = nativeCreateSynthesizer(configJson)
            synthesizerCreated = synthesizerHandle != 0L

            if (synthesizerCreated) {
                Timber.i("Synthesizer created — handle=%d", synthesizerHandle)
            } else {
                Timber.e("Synthesizer creation returned null handle")
            }
            synthesizerCreated
        } catch (e: Exception) {
            Timber.e(e, "createSynthesizer failed")
            false
        }
    }

    /**
     * Synthesise speech from text.
     * @param text Text to speak
     * @param sid Speaker ID (0 for single-speaker)
     * @param speed Speech rate (1.0 = normal, 0.5–2.0 range)
     * @return PCM audio samples (22050 Hz, mono, float32), or empty array on failure
     */
    fun synthesize(text: String, sid: Int = 0, speed: Float = 1.0f): FloatArray {
        if (!synthesizerCreated) {
            Timber.w("synthesize called but no synthesizer created")
            return floatArrayOf()
        }

        return try {
            nativeSynthesize(synthesizerHandle, text, sid, speed)
        } catch (e: Exception) {
            Timber.e(e, "synthesize failed")
            floatArrayOf()
        }
    }

    /**
     * Synthesise and return as PCM 16-bit bytes (for AudioTrack playback).
     * @param text Text to speak
     * @param sid Speaker ID
     * @param speed Speech rate
     * @return PCM 16-bit LE byte array at 22050 Hz
     */
    fun synthesizeToPcm16(text: String, sid: Int = 0, speed: Float = 1.0f): ByteArray {
        val floatSamples = synthesize(text, sid, speed)
        return floatToPcm16(floatSamples)
    }

    /**
     * Destroy the current synthesiser and free memory.
     */
    fun destroySynthesizer() {
        if (!synthesizerCreated) return
        try {
            nativeDestroySynthesizer(synthesizerHandle)
            Timber.i("Synthesizer destroyed — handle=%d", synthesizerHandle)
        } catch (e: Exception) {
            Timber.e(e, "destroySynthesizer failed")
        } finally {
            synthesizerHandle = 0L
            synthesizerCreated = false
        }
    }

    // ── Lifecycle ────────────────────────────────────────────

    /**
     * Release all native resources.
     */
    fun release() {
        destroyRecognizer()
        destroySynthesizer()
    }

    /**
     * Get engine status for diagnostics.
     */
    fun getStatus(): Map<String, Any> = mapOf(
        "recognizerCreated" to recognizerCreated,
        "synthesizerCreated" to synthesizerCreated,
        "recognizerHandle" to recognizerHandle,
        "synthesizerHandle" to synthesizerHandle
    )

    // ── Audio conversion utilities ───────────────────────────

    companion object AudioUtils {
        /**
         * Convert PCM 16-bit LE bytes to float array normalised to [-1, 1].
         */
        fun pcm16ToFloat(pcm: ByteArray): FloatArray {
            val samples = FloatArray(pcm.size / 2)
            for (i in samples.indices) {
                val lo = pcm[i * 2].toInt() and 0xFF
                val hi = pcm[i * 2 + 1].toInt()
                val sample = (hi shl 8) or lo
                samples[i] = sample.toFloat() / 32768.0f
            }
            return samples
        }

        /**
         * Convert float array ([-1, 1]) to PCM 16-bit LE bytes.
         */
        fun floatToPcm16(samples: FloatArray): ByteArray {
            val pcm = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val clamped = samples[i].coerceIn(-1.0f, 1.0f)
                val intSample = (clamped * 32767.0f).toInt()
                pcm[i * 2] = (intSample and 0xFF).toByte()
                pcm[i * 2 + 1] = (intSample shr 8).toByte()
            }
            return pcm
        }

        /**
         * Simple JSON string escape.
         */
        internal fun String.escapeJson(): String =
            replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
    }
}
