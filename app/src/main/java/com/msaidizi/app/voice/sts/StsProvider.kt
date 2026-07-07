package com.msaidizi.app.voice.sts

import kotlinx.coroutines.flow.SharedFlow

/**
 * Interface for Speech-to-Speech providers.
 *
 * Implementations wrap specific STS backends:
 * - [GptRealtimeProvider] — OpenAI GPT-Realtime-2 API
 * - [ElevenLabsProvider] — ElevenLabs Eleven v3 Conversational
 * - [LocalStsProvider] — On-device optimized pipeline
 *
 * Each provider manages its own network connections, audio encoding,
 * and session lifecycle. The [SpeechToSpeechEngine] orchestrates
 * provider selection and audio routing.
 */
interface StsProvider {

    /** Unique provider identifier */
    val id: String

    /** Human-readable provider name */
    val name: String

    /** Whether this provider requires network connectivity */
    val requiresNetwork: Boolean

    /** Set of supported ISO language codes */
    val supportedLanguages: Set<String>

    /** Streaming audio output from the provider */
    val audioOutput: SharedFlow<ShortArray>

    /** Transcription output (for logging/accessibility) */
    val transcription: SharedFlow<StsTranscription>

    // ────────────────────── Lifecycle ──────────────────────

    /**
     * Check if the provider is currently available.
     * Returns false if model not loaded, API key missing, or network required but offline.
     */
    fun isAvailable(): Boolean

    /**
     * Initialize a conversation session.
     * Sets up model state, system prompts, tool definitions, etc.
     */
    suspend fun initializeSession(session: StsSession)

    /**
     * End a conversation session and release resources.
     */
    suspend fun endSession(session: StsSession)

    // ────────────────────── Audio Streaming ──────────────────────

    /**
     * Stream an audio chunk to the provider for real-time processing.
     * Called repeatedly as the user speaks. The provider may emit
     * partial results via [audioOutput] and [transcription].
     *
     * @param session Active session
     * @param audioChunk Raw audio at 16kHz, 16-bit PCM
     */
    suspend fun streamAudio(session: StsSession, audioChunk: ShortArray)

    /**
     * Signal end of the user's utterance.
     * Provider should finalize any pending processing and emit
     * the complete response.
     */
    suspend fun endUtterance()

    /**
     * Interrupt the current response generation.
     * Used for natural turn-taking (user starts speaking while
     * the system is still responding).
     */
    fun interrupt()

    // ────────────────────── Quality Metrics ──────────────────────

    /**
     * Get average latency in milliseconds for this provider.
     */
    fun getAverageLatencyMs(): Long

    /**
     * Get quality score [0.0, 1.0] based on user feedback and benchmarks.
     */
    fun getQualityScore(): Float

    /**
     * Get estimated cost per minute of conversation.
     * Returns 0.0 for free/on-device providers.
     */
    fun getCostPerMinute(): Float
}

/**
 * Session state for an STS conversation.
 */
data class StsSession(
    val sessionId: String,
    val language: String,
    val dialect: String?,
    val provider: StsProvider,
    var turnCount: Int = 0,
    val startTime: Long = System.currentTimeMillis(),
    val metadata: MutableMap<String, Any> = mutableMapOf()
) {
    /** Get session duration in milliseconds */
    fun getDurationMs(): Long = System.currentTimeMillis() - startTime

    /** Check if session is still within turn limits */
    fun canContinue(): Boolean = turnCount < 50
}
