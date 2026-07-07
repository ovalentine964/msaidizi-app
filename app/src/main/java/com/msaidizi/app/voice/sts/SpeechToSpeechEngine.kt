package com.msaidizi.app.voice.sts

import android.content.Context
import com.msaidizi.app.core.util.DeviceTier
import com.msaidizi.app.voice.AudioRecorder
import com.msaidizi.app.voice.VoiceActivityDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speech-to-Speech (STS) engine — direct audio-to-audio processing.
 *
 * Bypasses the traditional ASR → LLM → TTS pipeline for lower latency.
 * Architecture inspired by GPT-Realtime-2 and Moshi (Kyutai).
 *
 * Two modes of operation:
 * 1. **Native STS**: Direct speech-to-speech via cloud API (GPT-Realtime-2, ElevenLabs)
 *    - Sub-500ms latency
 *    - Requires network connectivity
 *    - Best quality for supported languages
 *
 * 2. **Optimized Pipeline**: Local ASR → LLM → TTS with streaming optimizations
 *    - <1000ms latency on-device
 *    - Works offline
 *    - Streaming TTS starts before LLM finishes
 *
 * The engine automatically selects the best mode based on:
 * - Network availability
 * - Device capabilities
 * - Language/dialect requirements
 * - User preference
 *
 * Integration points for future model providers:
 * - [StsProvider] interface for pluggable backends
 * - Audio streaming via [AudioStream] for real-time processing
 * - [StsSession] manages conversation state and context
 *
 * @see StsProvider for backend integration
 * @see StsSession for conversation management
 */
@Singleton
class SpeechToSpeechEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioRecorder: AudioRecorder,
    private val vad: VoiceActivityDetector
) {
    companion object {
        private const val TAG = "STSEngine"
        private const val STREAMING_CHUNK_MS = 100L  // Process audio every 100ms
        private const val MAX_CONVERSATION_TURNS = 50
    }

    /** Registered STS providers (cloud APIs, local models, etc.) */
    private val providers = mutableMapOf<String, StsProvider>()

    /** Active STS session */
    private var activeSession: StsSession? = null

    /** Current engine state */
    private val _engineState = MutableStateFlow(StsEngineState.IDLE)
    val engineState: StateFlow<StsEngineState> = _engineState

    /** Streaming audio output from STS provider */
    private val _audioOutput = MutableSharedFlow<ShortArray>(extraBufferCapacity = 32)
    val audioOutput: SharedFlow<ShortArray> = _audioOutput

    /** Transcription for logging/accessibility (always generated even in STS mode) */
    private val _transcription = MutableSharedFlow<StsTranscription>(extraBufferCapacity = 4)
    val transcription: SharedFlow<StsTranscription> = _transcription

    /** Provider selection strategy */
    private var selectionStrategy = StsSelectionStrategy.LOWEST_LATENCY

    // ────────────────────── Provider Management ──────────────────────

    /**
     * Register an STS provider (e.g., GPT-Realtime-2, ElevenLabs, local model).
     *
     * Providers are selected based on [StsSelectionStrategy]:
     * - LOWEST_LATENCY: Pick provider with lowest measured latency
     * - OFFLINE_FIRST: Prefer local providers, fallback to cloud
     * - HIGHEST_QUALITY: Pick provider with best quality scores
     * - COST_OPTIMIZED: Prefer free/cheap providers
     */
    fun registerProvider(provider: StsProvider) {
        providers[provider.id] = provider
        Timber.tag(TAG).i("Registered STS provider: %s (%s)", provider.id, provider.name)
    }

    fun unregisterProvider(providerId: String) {
        providers.remove(providerId)
    }

    fun getRegisteredProviders(): List<StsProviderInfo> {
        return providers.values.map { provider ->
            StsProviderInfo(
                id = provider.id,
                name = provider.name,
                isAvailable = provider.isAvailable(),
                supportedLanguages = provider.supportedLanguages,
                averageLatencyMs = provider.getAverageLatencyMs(),
                isOnline = provider.requiresNetwork
            )
        }
    }

    // ────────────────────── Session Management ──────────────────────

    /**
     * Start a new STS conversation session.
     *
     * @param language Primary language for the conversation
     * @param dialect Specific dialect (e.g., "migori", "sheng")
     * @param preferredProviderId Optional provider preference
     * @return Session ID, or null if session could not be created
     */
    suspend fun startSession(
        language: String = "sw",
        dialect: String? = null,
        preferredProviderId: String? = null
    ): String? {
        if (_engineState.value != StsEngineState.IDLE) {
            Timber.tag(TAG).w("Cannot start session: engine is %s", _engineState.value)
            return null
        }

        // Select provider
        val provider = selectProvider(language, preferredProviderId)
        if (provider == null) {
            Timber.tag(TAG).e("No suitable STS provider for language: %s", language)
            return null
        }

        val session = StsSession(
            sessionId = generateSessionId(),
            language = language,
            dialect = dialect,
            provider = provider
        )

        try {
            provider.initializeSession(session)
            activeSession = session
            _engineState.value = StsEngineState.LISTENING
            Timber.tag(TAG).i("STS session started: %s (provider: %s)", session.sessionId, provider.id)
            return session.sessionId
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to initialize STS session")
            return null
        }
    }

    /**
     * End the current STS session.
     */
    suspend fun endSession() {
        activeSession?.let { session ->
            try {
                session.provider.endSession(session)
                Timber.tag(TAG).i("STS session ended: %s (turns: %d)", session.sessionId, session.turnCount)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error ending STS session")
            }
        }
        activeSession = null
        _engineState.value = StsEngineState.IDLE
    }

    // ────────────────────── Voice Processing ──────────────────────

    /**
     * Start listening for voice input in the current STS session.
     * Streams audio to the provider in real-time for processing.
     */
    suspend fun startListening(scope: CoroutineScope) {
        val session = activeSession
        if (session == null) {
            Timber.tag(TAG).w("No active session")
            return
        }

        _engineState.value = StsEngineState.LISTENING
        vad.reset()
        audioRecorder.startRecording(scope)

        // Stream audio chunks to provider
        scope.launch {
            audioRecorder.audioChunks.collect { chunk ->
                if (_engineState.value == StsEngineState.LISTENING) {
                    // Send audio to provider for real-time processing
                    session.provider.streamAudio(session, chunk)

                    // Also run VAD locally for end-of-speech detection
                    val hasSpeech = vad.processChunk(chunk) > 0.5f
                    if (!hasSpeech && vad.getState() == VoiceActivityDetector.VadState.SPEECH_END) {
                        // Speech ended — provider should have partial results
                        _engineState.value = StsEngineState.PROCESSING
                    }
                }
            }
        }

        // Collect provider's audio output
        scope.launch {
            session.provider.audioOutput.collect { audioChunk ->
                _audioOutput.emit(audioChunk)
            }
        }

        // Collect transcription (for logging/accessibility)
        scope.launch {
            session.provider.transcription.collect { text ->
                _transcription.emit(text)
                session.turnCount++
            }
        }
    }

    /**
     * Stop listening and trigger final processing.
     */
    suspend fun stopListening() {
        audioRecorder.stopRecording()
        activeSession?.provider?.endUtterance()
        _engineState.value = StsEngineState.PROCESSING
    }

    /**
     * Interrupt the current response (for natural turn-taking).
     * User starts speaking while the system is still responding.
     */
    fun interrupt() {
        activeSession?.provider?.interrupt()
        _engineState.value = StsEngineState.LISTENING
    }

    // ────────────────────── Provider Selection ──────────────────────

    /**
     * Select the best provider based on strategy and requirements.
     */
    private fun selectProvider(language: String, preferredId: String? = null): StsProvider? {
        // If user has a preference, try it first
        if (preferredId != null) {
            providers[preferredId]?.let { provider ->
                if (provider.isAvailable() && language in provider.supportedLanguages) {
                    return provider
                }
            }
        }

        val candidates = providers.values.filter { provider ->
            provider.isAvailable() && language in provider.supportedLanguages
        }

        if (candidates.isEmpty()) return null

        return when (selectionStrategy) {
            StsSelectionStrategy.LOWEST_LATENCY -> {
                candidates.minByOrNull { it.getAverageLatencyMs() }
            }
            StsSelectionStrategy.OFFLINE_FIRST -> {
                candidates.firstOrNull { !it.requiresNetwork }
                    ?: candidates.first()
            }
            StsSelectionStrategy.HIGHEST_QUALITY -> {
                candidates.maxByOrNull { it.getQualityScore() }
            }
            StsSelectionStrategy.COST_OPTIMIZED -> {
                candidates.minByOrNull { it.getCostPerMinute() }
            }
        }
    }

    // ────────────────────── Helpers ──────────────────────

    private fun generateSessionId(): String {
        return "sts_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }

    fun getStatus(): Map<String, Any> = mapOf(
        "state" to _engineState.value.name,
        "activeSession" to (activeSession?.sessionId ?: "none"),
        "registeredProviders" to providers.size,
        "selectionStrategy" to selectionStrategy.name,
        "deviceTier" to DeviceTier.current.name
    )
}

// ════════════════════════════════════════════════════════════════════
// DATA CLASSES & ENUMS
// ════════════════════════════════════════════════════════════════════

enum class StsEngineState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING,
    ERROR
}

enum class StsSelectionStrategy {
    LOWEST_LATENCY,
    OFFLINE_FIRST,
    HIGHEST_QUALITY,
    COST_OPTIMIZED
}

data class StsProviderInfo(
    val id: String,
    val name: String,
    val isAvailable: Boolean,
    val supportedLanguages: Set<String>,
    val averageLatencyMs: Long,
    val isOnline: Boolean
)

/**
 * STS transcription — always generated for logging even in direct STS mode.
 */
data class StsTranscription(
    val userText: String,
    val responseText: String,
    val language: String,
    val isPartial: Boolean,
    val latencyMs: Long
)
