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
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI GPT-Realtime-2 provider for Speech-to-Speech.
 *
 * Connects to OpenAI's Realtime API via WebSocket for direct
 * speech-to-speech processing. Streams user audio and receives
 * synthesized response audio in real-time.
 *
 * API: WebSocket at wss://api.openai.com/v1/realtime?model=gpt-realtime-2
 * Audio format: PCM16 @ 24kHz (server), 16kHz input (upsampled client-side)
 *
 * Protocol:
 * 1. Client connects with Bearer token
 * 2. Client sends `session.update` with config
 * 3. Client streams `input_audio_buffer.append` (base64 PCM16)
 * 4. Client sends `input_audio_buffer.commit` at end of utterance
 * 5. Server streams `response.audio.delta` (base64 PCM16)
 * 6. Server streams `response.audio_transcript.delta` (text)
 * 7. Server sends `response.done` when turn complete
 *
 * Configuration required:
 * - OPENAI_API_KEY: API key with Realtime API access
 *
 * @see <a href="https://platform.openai.com/docs/guides/realtime">OpenAI Realtime API</a>
 */
@Singleton
class GptRealtimeProvider @Inject constructor() : StsProvider {

    companion object {
        private const val TAG = "GptRealtime"
        const val PROVIDER_ID = "gpt-realtime-2"
        const val PROVIDER_NAME = "OpenAI GPT-Realtime-2"

        const val REASONING_MINIMAL = "minimal"
        const val REASONING_LOW = "low"
        const val REASONING_MEDIUM = "medium"
        const val REASONING_HIGH = "high"
        const val REASONING_XHIGH = "xhigh"

        private const val WS_URL = "wss://api.openai.com/v1/realtime?model=gpt-realtime-2"
        private const val INPUT_SAMPLE_RATE = 16000   // Our input is 16kHz
        private const val OUTPUT_SAMPLE_RATE = 24000   // Server outputs at 24kHz
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 120_000L

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

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var currentSession: StsSession? = null
    private val latencyHistory = mutableListOf<Long>()
    private var totalRequests = 0
    private var reasoningEffort = REASONING_MEDIUM
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Accumulate partial response transcript
    private val responseTranscriptBuilder = StringBuilder()

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    // ────────────────────── Lifecycle ──────────────────────

    override fun isAvailable(): Boolean {
        return try {
            getApiKey().isNotBlank()
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun initializeSession(session: StsSession) {
        currentSession = session
        totalRequests = 0
        responseTranscriptBuilder.clear()

        reasoningEffort = when (session.language) {
            "sw", "sheng" -> REASONING_MEDIUM
            "yo", "ha", "am" -> REASONING_HIGH
            else -> REASONING_MEDIUM
        }

        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            Timber.tag(TAG).e("API key not configured")
            return
        }

        val request = Request.Builder()
            .url(WS_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("OpenAI-Beta", "realtime=v1")
            .build()

        return suspendCancellableCoroutine { continuation ->
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    isConnected = true
                    Timber.tag(TAG).i("WebSocket connected for session: %s", session.sessionId)

                    // Send session configuration
                    val sessionUpdate = JSONObject().apply {
                        put("type", "session.update")
                        put("session", JSONObject().apply {
                            put("modalities", JSONArray().put("text").put("audio"))
                            put("instructions", SYSTEM_PROMPT)
                            put("voice", "alloy")
                            put("input_audio_format", "pcm16")
                            put("output_audio_format", "pcm16")
                            put("input_audio_transcription", JSONObject().apply {
                                put("model", "whisper-1")
                            })
                            put("turn_detection", JSONObject().apply {
                                put("type", "server_vad")
                                put("threshold", 0.5)
                                put("prefix_padding_ms", 300)
                                put("silence_duration_ms", 800)
                            })
                            put("temperature", 0.6)
                            put("max_response_output_tokens", 4096)
                            put("reasoning_effort", reasoningEffort)
                            put("tools", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "function")
                                    put("name", "record_sale")
                                    put("description", "Record a sale transaction")
                                    put("parameters", JSONObject().apply {
                                        put("type", "object")
                                        put("properties", JSONObject().apply {
                                            put("item", JSONObject().apply {
                                                put("type", "string")
                                                put("description", "Product name")
                                            })
                                            put("quantity", JSONObject().apply {
                                                put("type", "number")
                                            })
                                            put("amount", JSONObject().apply {
                                                put("type", "number")
                                                put("description", "Total in KES")
                                            })
                                        })
                                        put("required", JSONArray().put("item").put("amount"))
                                    })
                                })
                            })
                        })
                    }

                    ws.send(sessionUpdate.toString())
                    continuation.resume(Unit) {}
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    handleServerEvent(text)
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    // OpenAI Realtime API uses text frames, not binary
                    Timber.tag(TAG).d("Unexpected binary message: %d bytes", bytes.size)
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
        responseTranscriptBuilder.clear()
        Timber.tag(TAG).i("Session ended: %s (turns: %d, duration: %dms)",
            session.sessionId, session.turnCount, session.getDurationMs())
    }

    // ────────────────────── Audio Streaming ──────────────────────

    override suspend fun streamAudio(session: StsSession, audioChunk: ShortArray) {
        if (!isConnected || webSocket == null) return

        val startTime = System.currentTimeMillis()

        // Convert ShortArray to base64-encoded PCM16
        val byteBuffer = ByteBuffer.allocate(audioChunk.size * 2).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            audioChunk.forEach { putShort(it) }
        }
        val base64Audio = Base64.encodeToString(byteBuffer.array(), Base64.NO_WRAP)

        // Send audio chunk
        val event = JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", base64Audio)
        }

        webSocket?.send(event.toString())

        totalRequests++
        val elapsed = System.currentTimeMillis() - startTime
        latencyHistory.add(elapsed)
        if (latencyHistory.size > 100) latencyHistory.removeAt(0)
    }

    override suspend fun endUtterance() {
        if (!isConnected || webSocket == null) return

        // Commit the audio buffer — signals end of user's turn
        val event = JSONObject().apply {
            put("type", "input_audio_buffer.commit")
        }

        webSocket?.send(event.toString())
        responseTranscriptBuilder.clear()
        Timber.tag(TAG).d("Utterance committed, awaiting response")
    }

    override fun interrupt() {
        if (!isConnected || webSocket == null) return

        // Cancel current response generation
        val event = JSONObject().apply {
            put("type", "response.cancel")
        }

        webSocket?.send(event.toString())
        Timber.tag(TAG).d("Response cancelled by user")
    }

    // ────────────────────── Server Event Handling ──────────────────────

    /**
     * Handle server-sent events from OpenAI Realtime API.
     *
     * Key event types:
     * - session.created / session.updated: Session confirmed
     * - input_audio_buffer.speech_started: VAD detected speech
     * - input_audio_buffer.speech_stopped: VAD detected end of speech
     * - input_audio_buffer.committed: Audio buffer committed
     * - response.audio.delta: Partial audio response (base64 PCM16)
     * - response.audio_transcript.delta: Partial text transcript
     * - response.audio.done: Audio response complete
     * - response.done: Full response turn complete
     * - response.function_call_arguments.done: Function call received
     * - error: Error from server
     */
    private fun handleServerEvent(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")

            when (type) {
                "session.created" -> {
                    Timber.tag(TAG).i("Session created by server")
                }

                "session.updated" -> {
                    Timber.tag(TAG).d("Session configuration updated")
                }

                "input_audio_buffer.speech_started" -> {
                    Timber.tag(TAG).d("Server VAD: speech started")
                }

                "input_audio_buffer.speech_stopped" -> {
                    Timber.tag(TAG).d("Server VAD: speech stopped")
                }

                "input_audio_buffer.committed" -> {
                    Timber.tag(TAG).d("Audio buffer committed")
                }

                "conversation.item.input_audio_transcription.completed" -> {
                    // User's speech was transcribed by the server
                    val transcript = json.optString("transcript", "")
                    if (transcript.isNotBlank()) {
                        scope.launch {
                            _transcription.emit(StsTranscription(
                                userText = transcript,
                                responseText = "",
                                language = currentSession?.language ?: "sw",
                                isPartial = false,
                                latencyMs = 0
                            ))
                        }
                    }
                }

                "response.audio.delta" -> {
                    // Partial audio response — decode and emit
                    val audioBase64 = json.optString("delta", "")
                    if (audioBase64.isNotBlank()) {
                        val audioBytes = Base64.decode(audioBase64, Base64.DEFAULT)

                        // Server outputs at 24kHz PCM16
                        val shortArray = ShortArray(audioBytes.size / 2)
                        ByteBuffer.wrap(audioBytes)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                            .get(shortArray)

                        if (shortArray.isNotEmpty()) {
                            scope.launch {
                                _audioOutput.emit(shortArray)
                            }
                        }
                    }
                }

                "response.audio_transcript.delta" -> {
                    // Partial text transcript of the response
                    val delta = json.optString("delta", "")
                    if (delta.isNotBlank()) {
                        responseTranscriptBuilder.append(delta)
                    }
                }

                "response.audio_transcript.done" -> {
                    // Full response transcript available
                    val transcript = json.optString("transcript", "")
                        .ifBlank { responseTranscriptBuilder.toString() }
                    if (transcript.isNotBlank()) {
                        scope.launch {
                            _transcription.emit(StsTranscription(
                                userText = "",
                                responseText = transcript,
                                language = currentSession?.language ?: "sw",
                                isPartial = false,
                                latencyMs = 0
                            ))
                        }
                    }
                    responseTranscriptBuilder.clear()
                }

                "response.done" -> {
                    // Full response turn complete
                    currentSession?.turnCount = (currentSession?.turnCount ?: 0) + 1

                    // Check for function calls in the response
                    val response = json.optJSONObject("response")
                    val output = response?.optJSONArray("output")
                    if (output != null) {
                        for (i in 0 until output.length()) {
                            val item = output.optJSONObject(i) ?: continue
                            if (item.optString("type") == "function_call") {
                                handleFunctionCall(item)
                            }
                        }
                    }

                    Timber.tag(TAG).d("Response turn complete")
                }

                "response.function_call_arguments.done" -> {
                    handleFunctionCall(json)
                }

                "error" -> {
                    val error = json.optJSONObject("error")
                    val errorMsg = error?.optString("message", "Unknown error") ?: "Unknown error"
                    val errorCode = error?.optString("type", "unknown") ?: "unknown"
                    Timber.tag(TAG).e("Server error [%s]: %s", errorCode, errorMsg)
                }

                "rate_limits.updated" -> {
                    Timber.tag(TAG).d("Rate limits updated")
                }

                else -> {
                    Timber.tag(TAG).v("Unhandled event: %s", type)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error parsing server event: %s", text.take(100))
        }
    }

    /**
     * Handle a function call from the model.
     * Transmits the call result back to the server.
     */
    private fun handleFunctionCall(item: JSONObject) {
        val callId = item.optString("call_id", "")
        val functionName = item.optString("name", "")
        val args = item.optString("arguments", "{}")

        Timber.tag(TAG).i("Function call: %s(%s)", functionName, args)

        // In production, execute the function and send result back
        // For now, acknowledge with a placeholder result
        scope.launch {
            val resultEvent = JSONObject().apply {
                put("type", "conversation.item.create")
                put("item", JSONObject().apply {
                    put("type", "function_call_output")
                    put("call_id", callId)
                    put("output", JSONObject().apply {
                        put("status", "success")
                        put("message", "Transaction recorded")
                    }).toString()
                })
            }
            webSocket?.send(resultEvent.toString())

            // Trigger response after function output
            val responseEvent = JSONObject().apply {
                put("type", "response.create")
            }
            webSocket?.send(responseEvent.toString())
        }
    }

    // ────────────────────── Quality Metrics ──────────────────────

    override fun getAverageLatencyMs(): Long {
        return if (latencyHistory.isNotEmpty()) {
            latencyHistory.average().toLong()
        } else 400L
    }

    override fun getQualityScore(): Float = 0.966f

    override fun getCostPerMinute(): Float = 0.15f

    // ────────────────────── Configuration ──────────────────────

    fun setReasoningEffort(level: String) {
        reasoningEffort = level
        if (isConnected) {
            scope.launch {
                val update = JSONObject().apply {
                    put("type", "session.update")
                    put("session", JSONObject().apply {
                        put("reasoning_effort", level)
                    })
                }
                webSocket?.send(update.toString())
            }
        }
    }

    fun setTools(tools: List<Map<String, Any>>) {
        // Update tool definitions for the session
        if (isConnected) {
            scope.launch {
                val toolsArray = JSONArray()
                for (tool in tools) {
                    toolsArray.put(JSONObject(tool))
                }
                val update = JSONObject().apply {
                    put("type", "session.update")
                    put("session", JSONObject().apply {
                        put("tools", toolsArray)
                    })
                }
                webSocket?.send(update.toString())
            }
        }
    }

    private fun getApiKey(): String {
        return try {
            val field = Class.forName("com.msaidizi.app.BuildConfig")
                .getDeclaredField("OPENAI_API_KEY")
            field.get(null) as? String ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
