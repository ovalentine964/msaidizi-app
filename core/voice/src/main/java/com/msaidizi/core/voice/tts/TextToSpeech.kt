package com.msaidizi.core.voice.tts

/**
 * Text-to-Speech interface for the voice pipeline.
 *
 * Abstracts the underlying TTS engine (Piper, Kokoro, MMS) behind a
 * unified API. Implementations handle model lifecycle, phonemization,
 * vocoding, and audio playback.
 *
 * ## TTS Strategy
 * - **BASIC tier (2GB)**: Piper (25MB) — small, fast, acceptable quality
 * - **STANDARD+ (3GB+)**: Kokoro (82MB) — best quality, emotion-aware
 * - **Other languages**: MMS TTS (65MB per language) — on-demand
 *
 * @see PiperTtsEngine for the primary Swahili TTS implementation
 */
interface TextToSpeech {

    /** Engine display name (e.g. "Piper", "Kokoro", "MMS") */
    val name: String

    /** Whether the TTS model is currently loaded */
    fun isModelReady(): Boolean

    /** Whether audio is currently playing */
    fun isSpeaking(): Boolean

    /** Load the TTS model. Returns true on success. */
    suspend fun loadModel(): Boolean

    /** Unload model to free memory */
    fun unloadModel()

    /**
     * Speak text aloud.
     * Blocks until audio playback completes.
     *
     * @param text Text to speak
     * @param language ISO language code ("sw", "en")
     */
    suspend fun speak(text: String, language: String = "sw")

    /** Stop current playback */
    fun stop()
}

/**
 * TTS engine types available in Msaidizi.
 */
enum class TtsEngineType {
    /** Kokoro — best quality, 82MB, emotion-aware voice personalities */
    KOKORO,
    /** Piper — fast, optimized for Swahili, ~25MB (fallback) */
    PIPER,
    /** Meta MMS — 1,100+ languages, ~65MB per language */
    MMS
}
