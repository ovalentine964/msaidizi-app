package com.msaidizi.core.voice.stt

import timber.log.Timber

/**
 * High-level speech recognition interface.
 *
 * Abstracts the underlying ASR engine (Sherpa-ONNX Whisper, Moonshine, etc.)
 * behind a unified API. The [VoicePipeline] uses this interface without
 * knowing which engine is active.
 *
 * Implementations must handle:
 * - Model lifecycle (load/unload)
 * - Audio preprocessing (normalization, padding)
 * - Language-aware transcription (Swahili, English, Sheng)
 * - OOM safety (auto-unload on memory pressure)
 */
interface SpeechRecognizer {

    /** Whether the ASR model is currently loaded and ready */
    fun isModelReady(): Boolean

    /** Identifier of the currently loaded model (e.g. "whisper-tiny-int4") */
    fun getActiveModelId(): String

    /** Load the best available ASR model. Returns true on success. */
    suspend fun loadModel(preferredModelId: String? = null): Boolean

    /** Unload model to free memory */
    fun unloadModel()

    /**
     * Transcribe raw audio samples to text.
     *
     * @param audioData 16kHz mono 16-bit PCM audio samples
     * @return Transcribed text, or null if transcription failed
     */
    suspend fun transcribe(audioData: ShortArray): String?

    /**
     * Transcribe with a language hint for better accuracy.
     *
     * @param audioData 16kHz mono 16-bit PCM audio samples
     * @param language ISO language code ("sw", "en", "sheng", etc.)
     * @return Transcribed text
     */
    suspend fun transcribeWithLanguage(audioData: ShortArray, language: String): String?
}

/**
 * Result of a transcription attempt.
 *
 * @property text The transcribed text
 * @property confidence Recognition confidence (0.0–1.0)
 * @property language Detected or expected language code
 * @property dialectRegion Detected dialect region (e.g. "coastal", "inland")
 * @property success Whether transcription produced usable text
 * @property error Error message if transcription failed
 */
data class TranscriptionResult(
    val text: String,
    val confidence: Float,
    val success: Boolean,
    val language: String? = null,
    val dialectRegion: String? = null,
    val error: String? = null
)
