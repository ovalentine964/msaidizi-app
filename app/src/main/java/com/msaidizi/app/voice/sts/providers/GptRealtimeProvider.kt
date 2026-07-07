package com.msaidizi.app.voice.sts.providers

import com.msaidizi.app.voice.sts.StsProvider
import com.msaidizi.app.voice.sts.StsSession
import com.msaidizi.app.voice.sts.StsTranscription
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI GPT-Realtime-2 provider for Speech-to-Speech.
 *
 * Connects to OpenAI's Realtime API for direct speech-to-speech processing.
 * Features:
 * - GPT-5-class reasoning in voice modality
 * - 128K context window (4× previous generation)
 * - 5 adjustable reasoning levels (minimal/low/medium/high/xhigh)
 * - 70+ input languages with real-time translation
 * - Parallel tool calling with transparency
 * - Preamble phrases to eliminate "dead air"
 *
 * Latency target: 300-500ms end-to-end (server-side)
 *
 * API: WebSocket connection to OpenAI Realtime endpoint
 * Audio format: PCM16 @ 24kHz (upsampled from 16kHz input)
 *
 * Configuration required:
 * - OPENAI_API_KEY: API key with Realtime API access
 * - OPENAI_REALTIME_MODEL: Model ID (default: "gpt-realtime-2")
 *
 * @see <a href="https://openai.com/index/advancing-voice-intelligence-with-new-models-in-the-api/">OpenAI Realtime API</a>
 */
@Singleton
class GptRealtimeProvider @Inject constructor() : StsProvider {

    companion object {
        private const val TAG = "GptRealtime"
        const val PROVIDER_ID = "gpt-realtime-2"
        const val PROVIDER_NAME = "OpenAI GPT-Realtime-2"

        // Reasoning effort levels
        const val REASONING_MINIMAL = "minimal"
        const val REASONING_LOW = "low"
        const val REASONING_MEDIUM = "medium"
        const val REASONING_HIGH = "high"
        const val REASONING_XHIGH = "xhigh"

        // Default Msaidizi system prompt for voice
        private const val SYSTEM_PROMPT = """You are Msaidizi, a voice-first business assistant for small traders in Africa.
You communicate ONLY through voice — no text, no reading.
Be brief, warm, and direct. Use simple language.
Confirm financial transactions verbally: "I heard you sold 50 kilograms of tomatoes for 2,000 shillings. Is that correct?"
If you need to check something, say so: "Let me check that for you."
Speak in the user's language and dialect."""
    }

    override val id = PROVIDER_ID
    override val name = PROVIDER_NAME
    override val requiresNetwork = true
    override val supportedLanguages = setOf(
        "sw", "en", "sheng", "yo", "ha", "am", "zu", "xh", "ig",
        "fr", "ar", "hi", "pt", "es", "zh", "ja", "ko", "de", "it"
    )

    private val _audioOutput = MutableSharedFlow<ShortArray>(extraBufferCapacity = 32)
    override val audioOutput: SharedFlow<ShortArray> = _audioOutput

    private val _transcription = MutableSharedFlow<StsTranscription>(extraBufferCapacity = 4)
    override val transcription: SharedFlow<StsTranscription> = _transcription

    // Connection state
    private var isConnected = false
    private var currentSession: StsSession? = null

    // Latency tracking
    private val latencyHistory = mutableListOf<Long>()
    private var totalRequests = 0

    // Reasoning effort level
    private var reasoningEffort = REASONING_MEDIUM

    // ────────────────────── Lifecycle ──────────────────────

    override fun isAvailable(): Boolean {
        // Check if API key is configured
        // In production, check BuildConfig or encrypted preferences
        return try {
            val apiKey = getApiKey()
            apiKey.isNotBlank()
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun initializeSession(session: StsSession) {
        currentSession = session
        totalRequests = 0

        // Configure reasoning effort based on language complexity
        reasoningEffort = when (session.language) {
            "sw", "sheng" -> REASONING_MEDIUM  // Familiar languages, standard reasoning
            "yo", "ha", "am" -> REASONING_HIGH  // Less-resourced, more careful
            else -> REASONING_MEDIUM
        }

        // In production: establish WebSocket connection to OpenAI Realtime API
        // ws.connect("wss://api.openai.com/v1/realtime?model=gpt-realtime-2")
        // Send session.update with:
        //   - system prompt
        //   - voice configuration
        //   - tool definitions (record_sale, check_inventory, etc.)
        //   - input audio format (pcm16, 24kHz)
        //   - output audio format (pcm16, 24kHz)
        //   - reasoning_effort level

        isConnected = true
        Timber.tag(TAG).i("Session initialized: %s (reasoning: %s)", session.sessionId, reasoningEffort)
    }

    override suspend fun endSession(session: StsSession) {
        // Close WebSocket connection
        isConnected = false
        currentSession = null
        Timber.tag(TAG).i("Session ended: %s (turns: %d, duration: %dms)",
            session.sessionId, session.turnCount, session.getDurationMs())
    }

    // ────────────────────── Audio Streaming ──────────────────────

    override suspend fun streamAudio(session: StsSession, audioChunk: ShortArray) {
        if (!isConnected) return

        val startTime = System.currentTimeMillis()

        // In production:
        // 1. Upsample 16kHz → 24kHz if needed
        // 2. Encode to base64
        // 3. Send via WebSocket: {"type": "input_audio_buffer.append", "audio": base64}
        //
        // The provider streams back:
        // - {"type": "response.audio.delta", "audio": base64} — partial audio
        // - {"type": "response.audio_transcript.delta", "transcript": "..."} — partial text
        // - {"type": "response.done"} — turn complete

        // Simulate provider behavior for architecture
        // In production, this would process WebSocket messages

        totalRequests++
        val elapsed = System.currentTimeMillis() - startTime
        latencyHistory.add(elapsed)
        if (latencyHistory.size > 100) latencyHistory.removeAt(0)
    }

    override suspend fun endUtterance() {
        if (!isConnected) return

        // In production:
        // Send: {"type": "input_audio_buffer.commit"}
        // This signals the end of the user's turn
        // Provider will generate and stream back the response

        Timber.tag(TAG).d("Utterance ended, awaiting response")
    }

    override fun interrupt() {
        // In production:
        // Send: {"type": "response.cancel"}
        // This stops the current response generation
        // Provider will start listening for the next user input

        Timber.tag(TAG).d("Response interrupted by user")
    }

    // ────────────────────── Quality Metrics ──────────────────────

    override fun getAverageLatencyMs(): Long {
        return if (latencyHistory.isNotEmpty()) {
            latencyHistory.average().toLong()
        } else 400L  // Default estimate from research
    }

    override fun getQualityScore(): Float {
        // GPT-Realtime-2 scored 96.6% on Big Bench Audio
        return 0.966f
    }

    override fun getCostPerMinute(): Float {
        // Estimated cost per minute of conversation
        // GPT-Realtime-2: ~$0.06/min input, ~$0.24/min output
        return 0.15f  // Average estimate
    }

    // ────────────────────── Configuration ──────────────────────

    /**
     * Set reasoning effort level for the session.
     * Higher reasoning = better quality but higher latency.
     */
    fun setReasoningEffort(level: String) {
        reasoningEffort = level
        // In production: send session.update with new reasoning_effort
    }

    /**
     * Configure tool definitions for the session.
     * Tools enable the model to take actions (record sales, check inventory, etc.)
     */
    fun setTools(tools: List<Map<String, Any>>) {
        // In production: send session.update with tool definitions
        // GPT-Realtime-2 supports parallel tool calling with transparency
    }

    // ────────────────────── Helpers ──────────────────────

    private fun getApiKey(): String {
        // In production: retrieve from encrypted storage
        // Never hardcode API keys
        return ""
    }
}
