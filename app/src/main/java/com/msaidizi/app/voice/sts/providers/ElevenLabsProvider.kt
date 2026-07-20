package com.msaidizi.app.voice.sts.providers

import android.util.Base64
import com.msaidizi.app.voice.sts.StsProvider
import com.msaidizi.app.voice.sts.StsSession
import com.msaidizi.app.voice.sts.StsTranscription
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ElevenLabs Eleven v3 Conversational provider for Speech-to-Speech.
 *
 * Connects to ElevenLabs Conversational AI via WebSocket for real-time
 * speech-to-speech processing. Sends user audio chunks and receives
 * synthesized response audio in real-time.
 *
 * API: WebSocket at wss://api.elevenlabs.io/v1/convai/conversation
 * Audio format: PCM16 @ 16kHz (input), PCM16 @ 16kHz (output)
 *
 * Protocol:
 * 1. Client sends `conversation_initiation_client_data` with config
 * 2. Server responds with `conversation_initiation_metadata`
 * 3. Client streams `user_audio_chunk` (base64 PCM16)
 * 4. Server streams `audio` (base64 PCM16) + `agent_response` (text)
 * 5. Client sends `user_activity` to signal end of speech or interrupt
 *
 * Configuration required:
 * - ELEVENLABS_API_KEY: API key (from BuildConfig or encrypted prefs)
 * - ELEVENLABS_VOICE_ID: Voice ID for Msaidizi
 *
 * @see <a href="https://elevenlabs.io/docs/conversational-ai/overview">ElevenLabs Conversational AI</a>
 */
@Singleton
class ElevenLabsProvider @Inject constructor() : StsProvider {

    companion object {
        private const val TAG = "ElevenLabs"
        const val PROVIDER_ID = "elevenlabs-v3"
        const val PROVIDER_NAME = "ElevenLabs Eleven v3"

        private const val WS_URL = "wss://api.elevenlabs.io/v1/convai/conversation"
        private const val DEFAULT_VOICE_ID = "msaidizi-default-voice"

        // ElevenLabs expects audio as base64-encoded PCM16 at 16kHz
        private const val SAMPLE_RATE = 16000
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 60_000L
        private const val PING_INTERVAL_MS = 15_000L
    }

    override val id = PROVIDER_ID
    override val name = PROVIDER_NAME
    override val requiresNetwork = true
    override val supportedLanguages = setOf(
        "sw", "en", "sheng", "yo", "ha", "am", "zu", "xh", "ig",
        "fr", "ar", "hi", "pt", "es", "zh", "ja", "ko", "de", "it",
        "rw", "lg", "ak", "sn", "st", "tn", "ts"
    )

    private val _audioOutput = MutableSharedFlow<ShortArray>(extraBufferCapacity = 32)
    override val audioOutput: SharedFlow<ShortArray> = _audioOutput

    private val _transcription = MutableSharedFlow<StsTranscription>(extraBufferCapacity = 4)
    override val transcription: SharedFlow<StsTranscription> = _transcription

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var currentSession: StsSession? = null
    private val latencyHistory = mutableListOf<Long>()
    private var totalRequests = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .build()

    // ────────────────────── Lifecycle ──────────────────────

    override fun isAvailable(): Boolean {
        return try {
            getApiKey().isNotBlank()
        } catch (e: Throwable) {
            false
        }
    }

    override suspend fun initializeSession(session: StsSession) {
        currentSession = session
        totalRequests = 0

        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            Timber.tag(TAG).e("API key not configured")
            return
        }

        val url = "$WS_URL?xi-api-key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .build()

        return suspendCancellableCoroutine { continuation ->
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    isConnected = true
                    Timber.tag(TAG).i("WebSocket connected for session: %s", session.sessionId)

                    // Send initialization configuration
                    val initConfig = JSONObject().apply {
                        put("type", "conversation_initiation_client_data")
                        put("conversation_config", JSONObject().apply {
                            put("agent", JSONObject().apply {
                                put("language", session.language)
                                put("prompt", JSONObject().apply {
                                    put("prompt", "You are Msaidizi, a voice-first business assistant for small traders in Africa. Be brief, warm, and direct.")
                                })
                                put("first_message", "Habari! Msaidizi hapa. Nikisaidiaje leo?")
                                put("voice_id", DEFAULT_VOICE_ID)
                            })
                            put("tts", JSONObject().apply {
                                put("voice_id", DEFAULT_VOICE_ID)
                            })
                            put("turn_detection", JSONObject().apply {
                                put("type", "server_vad")
                                put("silence_duration_ms", 800)
                            })
                        })
                        put("custom_llm_extra_body", JSONObject().apply {
                            put("language", session.language)
                        })
                    }

                    ws.send(initConfig.toString())
                    continuation.resume(Unit) {}
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    handleTextMessage(text)
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    handleBinaryMessage(bytes)
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    Timber.tag(TAG).i("WebSocket closing: %d %s", code, reason)
                    ws.close(1000, null)
                    isConnected = false
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Timber.tag(TAG).e(t, "WebSocket failure")
                    isConnected = false
                    if (continuation.isActive) {
                        continuation.resume(Unit) {}
                    }
                }
            })
        }
    }

    override suspend fun endSession(session: StsSession) {
        webSocket?.close(1000, "Session ended")
        webSocket = null
        isConnected = false
        currentSession = null
        Timber.tag(TAG).i("Session ended: %s", session.sessionId)
    }

    // ────────────────────── Audio Streaming ──────────────────────

    override suspend fun streamAudio(session: StsSession, audioChunk: ShortArray) {
        if (!isConnected || webSocket == null) return

        val startTime = System.currentTimeMillis()

        // Convert ShortArray to ByteArray (little-endian PCM16)
        val byteBuffer = ByteBuffer.allocate(audioChunk.size * 2).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            audioChunk.forEach { putShort(it) }
        }

        // Encode to base64
        val base64Audio = Base64.encodeToString(byteBuffer.array(), Base64.NO_WRAP)

        // Send as JSON message
        val message = JSONObject().apply {
            put("type", "user_audio_chunk")
            put("audio", base64Audio)
        }

        webSocket?.send(message.toString())

        totalRequests++
        val elapsed = System.currentTimeMillis() - startTime
        latencyHistory.add(elapsed)
        if (latencyHistory.size > 100) latencyHistory.removeAt(0)
    }

    override suspend fun endUtterance() {
        if (!isConnected || webSocket == null) return

        val message = JSONObject().apply {
            put("type", "user_activity")
            put("activity", "end_of_speech")
        }

        webSocket?.send(message.toString())
        Timber.tag(TAG).d("Utterance ended, awaiting response")
    }

    override fun interrupt() {
        if (!isConnected || webSocket == null) return

        val message = JSONObject().apply {
            put("type", "user_activity")
            put("activity", "interrupt")
        }

        webSocket?.send(message.toString())
        Timber.tag(TAG).d("Response interrupted by user")
    }

    // ────────────────────── Message Handling ──────────────────────

    /**
     * Handle text WebSocket messages from ElevenLabs.
     * Message types:
     * - `conversation_initiation_metadata`: Session config confirmed
     * - `agent_response`: Agent text response
     * - `agent_response_correction`: Corrected agent response
     * - `user_transcript`: User speech transcription
     * - `interruption`: User interrupted the agent
     * - `ping`: Server health check
     */
    private fun handleTextMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")

            when (type) {
                "conversation_initiation_metadata" -> {
                    val metadata = json.optJSONObject("conversation_initiation_metadata")
                    Timber.tag(TAG).i(
                        "Session metadata: conversation_id=%s",
                        metadata?.optString("conversation_id", "unknown")
                    )
                }

                "agent_response" -> {
                    val agentText = json.optString("agent_response", "")
                    if (agentText.isNotBlank()) {
                        scope.launch {
                            _transcription.emit(StsTranscription(
                                userText = "",
                                responseText = agentText,
                                language = currentSession?.language ?: "sw",
                                isPartial = false,
                                latencyMs = 0
                            ))
                        }
                    }
                }

                "user_transcript" -> {
                    val userText = json.optString("user_transcript", "")
                    val isFinal = json.optBoolean("is_final", false)
                    if (userText.isNotBlank()) {
                        scope.launch {
                            _transcription.emit(StsTranscription(
                                userText = userText,
                                responseText = "",
                                language = currentSession?.language ?: "sw",
                                isPartial = !isFinal,
                                latencyMs = 0
                            ))
                        }
                    }
                }

                "interruption" -> {
                    Timber.tag(TAG).d("Agent interrupted by user")
                }

                "ping" -> {
                    // Respond to keep-alive
                    val pong = JSONObject().apply {
                        put("type", "pong")
                        put("event_id", json.optLong("event_id", 0))
                    }
                    webSocket?.send(pong.toString())
                }

                "error" -> {
                    val errorMsg = json.optString("message", "Unknown error")
                    Timber.tag(TAG).e("ElevenLabs error: %s", errorMsg)
                }

                else -> {
                    Timber.tag(TAG).d("Unhandled message type: %s", type)
                }
            }
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "Error parsing message: %s", text.take(100))
        }
    }

    /**
     * Handle binary WebSocket messages (audio data from ElevenLabs).
     * ElevenLabs sends audio as raw PCM16 bytes.
     */
    private fun handleBinaryMessage(bytes: ByteString) {
        try {
            val byteArray = bytes.toByteArray()

            // Convert byte array to ShortArray (PCM16 little-endian)
            val shortArray = ShortArray(byteArray.size / 2)
            ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortArray)

            if (shortArray.isNotEmpty()) {
                scope.launch {
                    _audioOutput.emit(shortArray)
                }
            }
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "Error processing binary audio message")
        }
    }

    // ────────────────────── Quality Metrics ──────────────────────

    override fun getAverageLatencyMs(): Long {
        return if (latencyHistory.isNotEmpty()) {
            latencyHistory.average().toLong()
        } else 300L
    }

    override fun getQualityScore(): Float = 0.95f

    override fun getCostPerMinute(): Float = 0.08f

    // ────────────────────── Voice Configuration ──────────────────────

    fun setVoice(voiceId: String) {
        // Update voice for next session
        Timber.tag(TAG).d("Voice set to: %s", voiceId)
    }

    fun setVoiceParameters(
        stability: Float = 0.5f,
        similarityBoost: Float = 0.75f,
        style: Float = 0.5f,
        useSpeakerBoost: Boolean = true
    ) {
        Timber.tag(TAG).d("Voice params: stability=%.2f, simBoost=%.2f", stability, similarityBoost)
    }

    private fun getApiKey(): String {
        // In production: retrieve from BuildConfig or encrypted preferences
        // API keys must never be hardcoded
        return try {
            val field = Class.forName("com.msaidizi.app.BuildConfig")
                .getDeclaredField("ELEVENLABS_API_KEY")
            field.get(null) as? String ?: ""
        } catch (e: Throwable) {
            ""
        }
    }
}
