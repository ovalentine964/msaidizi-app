package com.msaidizi.app.voice

import android.content.Context
import com.msaidizi.app.core.util.DeviceTier
import com.msaidizi.app.core.language.AdaptiveAsrEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Complete voice pipeline orchestrator (v2 — upgraded).
 *
 * Flow: AudioRecord → VAD → Whisper Turbo → IntentRouter → Agent → Kokoro → AudioTrack
 *
 * Upgrades from v1:
 * - ASR: Whisper Turbo (primary) → Moonshine (edge) → Whisper Tiny (legacy)
 * - TTS: Kokoro (primary, 82M) → Piper (fallback, 26MB)
 * - Streaming: Real streaming ASR with 500ms hops
 * - Emotion: Auto-selects voice personality based on detected emotion
 * - MsingiAI: Integrates Sauti models for Swahili dialect enhancement
 *
 * Memory management:
 * - Models lazy-loaded, released on background
 * - Kokoro: ~90MB, Piper: ~25MB (kept as fallback)
 * - ASR: ~150MB (Turbo) or ~40MB (Moonshine/legacy)
 */
@Singleton
class VoicePipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioRecorder: AudioRecorder,
    private val vad: VoiceActivityDetector,
    private val speechRecognizer: SpeechRecognizer,
    private val kokoroTts: KokoroTtsEngine,
    private val piperTts: TextToSpeech,
    private val mmsTtsEngine: MMSTextToSpeech,
    private val adaptiveAsrEngine: AdaptiveAsrEngine
) {
    // Pipeline state
    private val _pipelineState = MutableStateFlow(PipelineState.IDLE)
    val pipelineState: StateFlow<PipelineState> = _pipelineState

    // Last transcription result
    private val _transcription = MutableSharedFlow<TranscriptionResult>(extraBufferCapacity = 4)
    val transcription: SharedFlow<TranscriptionResult> = _transcription

    // Last spoken response
    private val _response = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val response: SharedFlow<String> = _response

    // Collect audio chunks from recorder
    private var audioCollectionJob: Job? = null

    // ────────────────────── Initialization ──────────────────────

    /**
     * Initialize the voice pipeline.
     * Loads Kokoro TTS (primary) and VAD. ASR lazy-loads on first use.
     */
    suspend fun initialize() {
        Timber.d("VoicePipeline: Initializing...")
        _pipelineState.value = PipelineState.INITIALIZING

        // Load primary TTS: Kokoro (better quality, 82MB)
        val kokoroLoaded = kokoroTts.loadModel()
        if (!kokoroLoaded) {
            // Fallback to Piper if Kokoro not available
            Timber.w("Kokoro TTS not available, falling back to Piper")
            piperTts.loadModel()
        }

        // ASR is lazy-loaded on first use (saves 150MB on 2GB devices)
        if (DeviceTier.preloadASR()) {
            speechRecognizer.loadModel()
        }

        _pipelineState.value = PipelineState.IDLE
        Timber.i("VoicePipeline: Ready (TTS: %s, ASR: %s)",
            if (kokoroLoaded) "Kokoro" else "Piper",
            if (speechRecognizer.isModelReady()) speechRecognizer.getActiveModelId() else "lazy")
    }

    // ────────────────────── Voice Input ──────────────────────

    /**
     * Start listening for voice input.
     * Begins recording and processes audio through the pipeline.
     */
    suspend fun startListening(scope: CoroutineScope) {
        if (_pipelineState.value == PipelineState.LISTENING) {
            Timber.w("Already listening")
            return
        }

        _pipelineState.value = PipelineState.LISTENING
        vad.reset()

        audioRecorder.startRecording(scope)

        audioCollectionJob = scope.launch {
            audioRecorder.audioChunks.collect { chunk ->
                val hasSpeech = vad.processChunk(chunk) > 0.5f

                if (hasSpeech && _pipelineState.value != PipelineState.PROCESSING) {
                    // Speech detected, waiting for end of speech
                }
            }
        }

        scope.launch {
            audioRecorder.recordingState.collect { state ->
                when (state) {
                    RecordingState.STOPPED, RecordingState.MAX_DURATION -> {
                        processEndOfSpeech()
                    }
                    RecordingState.ERROR_NO_PERMISSION,
                    RecordingState.ERROR_INIT,
                    RecordingState.ERROR_READ -> {
                        _pipelineState.value = PipelineState.ERROR
                    }
                    else -> { }
                }
            }
        }
    }

    /**
     * Stop listening and process the captured audio.
     */
    suspend fun stopListening() {
        audioCollectionJob?.cancel()
        audioRecorder.stopRecording()
        processEndOfSpeech()
    }

    /**
     * Process the end of speech event.
     * Uses AdaptiveAsrEngine for dialect normalization and confidence calibration.
     */
    private suspend fun processEndOfSpeech() {
        val speechAudio = vad.getAccumulatedAudio()
        if (speechAudio == null || speechAudio.isEmpty()) {
            _pipelineState.value = PipelineState.IDLE
            return
        }

        _pipelineState.value = PipelineState.PROCESSING

        try {
            // Use AdaptiveAsrEngine for full adaptive pipeline
            val result = adaptiveAsrEngine.transcribe(speechAudio)

            if (result.transcript.isBlank()) {
                _transcription.emit(TranscriptionResult(
                    text = "",
                    confidence = 0f,
                    success = false,
                    error = "Could not understand. Please try again."
                ))
                _pipelineState.value = PipelineState.IDLE
                return
            }

            Timber.d(
                "Transcription: '%s' → '%s' (conf: %.3f, dialect: %s, drift: %s)",
                result.rawTranscript, result.transcript,
                result.calibratedConfidence.calibratedConfidence,
                result.dialectRegion, result.driftDetected
            )

            _transcription.emit(TranscriptionResult(
                text = result.transcript,
                confidence = result.calibratedConfidence.calibratedConfidence,
                success = true
            ))

            _pipelineState.value = PipelineState.IDLE
        } catch (e: Exception) {
            Timber.e(e, "Error processing speech")
            _transcription.emit(TranscriptionResult(
                text = "",
                confidence = 0f,
                success = false,
                error = "Error processing speech: ${e.message}"
            ))
            _pipelineState.value = PipelineState.ERROR
        }
    }

    // ────────────────────── Voice Output ──────────────────────

    /**
     * Speak a response to the user.
     *
     * TTS engine priority:
     * 1. Kokoro (primary) — best quality, emotion-aware voice personality
     * 2. Piper (fallback) — smaller, works on all devices
     * 3. MMS (other African languages) — broader language support
     */
    suspend fun speak(text: String, language: String = "sw") {
        _pipelineState.value = PipelineState.SPEAKING

        val engine = selectTtsEngine(language)
        Timber.d("TTS: Using %s engine for language '%s'", engine.name, language)

        when (engine) {
            TtsEngineType.KOKORO -> kokoroTts.speak(text, language)
            TtsEngineType.PIPER -> piperTts.speak(text, language)
            TtsEngineType.MMS -> mmsTtsEngine.speak(text, language)
        }

        _response.emit(text)
    }

    /**
     * Speak with emotion-aware voice personality.
     * Auto-selects Kokoro voice style based on detected emotion.
     *
     * @param text Text to speak
     * @param language Language code
     * @param emotion Detected user emotion (selects voice personality)
     */
    suspend fun speakWithEmotion(
        text: String,
        language: String = "sw",
        emotion: com.msaidizi.app.voice.emotion.VoiceEmotion = com.msaidizi.app.voice.emotion.VoiceEmotion.NEUTRAL
    ) {
        _pipelineState.value = PipelineState.SPEAKING

        // Set Kokoro voice personality based on emotion
        if (kokoroTts.isModelReady()) {
            kokoroTts.setVoiceForEmotion(emotion)
        }

        speak(text, language)
    }

    /**
     * Speak and wait for completion.
     */
    suspend fun speakAndWait(text: String, language: String = "sw") {
        speak(text, language)
        while (isAnyTtsSpeaking()) {
            delay(100)
        }
        _pipelineState.value = PipelineState.IDLE
    }

    /**
     * Stop speaking (stops all engines).
     */
    fun stopSpeaking() {
        kokoroTts.stop()
        piperTts.stop()
        mmsTtsEngine.stop()
        _pipelineState.value = PipelineState.IDLE
    }

    /**
     * Select TTS engine based on language and availability.
     *
     * Strategy:
     * - Swahili/Sheng/English → Kokoro (best quality) → Piper (fallback)
     * - Other African languages → MMS (broader language support)
     * - Unknown → Kokoro with Swahili fallback
     */
    private fun selectTtsEngine(language: String): TtsEngineType {
        return when (language.lowercase()) {
            "sw", "swahili", "swa", "sheng", "mixed" -> {
                if (kokoroTts.isModelReady()) TtsEngineType.KOKORO
                else if (piperTts.isModelReady()) TtsEngineType.PIPER
                else TtsEngineType.KOKORO  // Will trigger lazy load
            }
            "en", "english", "eng" -> {
                if (kokoroTts.isModelReady()) TtsEngineType.KOKORO
                else if (piperTts.isModelReady()) TtsEngineType.PIPER
                else TtsEngineType.KOKORO
            }
            else -> {
                if (mmsTtsEngine.isLanguageSupported(language) && mmsTtsEngine.isModelReady()) {
                    TtsEngineType.MMS
                } else if (kokoroTts.isModelReady()) {
                    TtsEngineType.KOKORO
                } else {
                    Timber.w("Language '%s' not supported by any TTS, falling back to Kokoro/Swahili", language)
                    TtsEngineType.KOKORO
                }
            }
        }
    }

    /**
     * Check if any TTS engine is currently speaking.
     */
    private fun isAnyTtsSpeaking(): Boolean {
        return kokoroTts.isSpeaking() || piperTts.isSpeaking() || mmsTtsEngine.isSpeaking()
    }

    // ────────────────────── Lifecycle ──────────────────────

    /**
     * Release models when app goes to background.
     * Critical for 2GB devices — frees ~240MB.
     */
    fun onBackground() {
        Timber.d("VoicePipeline: Releasing models for background")
        speechRecognizer.unloadModel()
        mmsTtsEngine.unloadModel()
        // Keep Kokoro/Piper TTS for notifications
    }

    /**
     * Reload models when app returns to foreground.
     */
    suspend fun onForeground() {
        Timber.d("VoicePipeline: Reloading models")
        if (DeviceTier.preloadASR()) {
            speechRecognizer.loadModel()
        }
    }

    /**
     * Release all resources.
     */
    fun release() {
        audioRecorder.release()
        speechRecognizer.unloadModel()
        kokoroTts.unloadModel()
        piperTts.unloadModel()
        mmsTtsEngine.unloadModel()
        audioCollectionJob?.cancel()
        _pipelineState.value = PipelineState.IDLE
    }

    /**
     * Get pipeline status.
     */
    fun getStatus(): Map<String, Any> = mapOf(
        "state" to _pipelineState.value.name,
        "asrModel" to speechRecognizer.getActiveModelId(),
        "asrLoaded" to speechRecognizer.isModelReady(),
        "kokoroReady" to kokoroTts.isModelReady(),
        "piperReady" to piperTts.isModelReady(),
        "mmsReady" to mmsTtsEngine.isModelReady(),
        "ttsSpeaking" to isAnyTtsSpeaking(),
        "deviceTier" to DeviceTier.current.name
    )
}

/**
 * TTS engine selection (updated with Kokoro).
 */
enum class TtsEngineType {
    /** Kokoro — best quality, 82MB, emotion-aware voice personalities */
    KOKORO,
    /** Piper — fast, optimized for Swahili, ~25MB (fallback) */
    PIPER,
    /** Meta MMS — 1,100+ languages, ~35MB per language */
    MMS
}

/**
 * Pipeline states.
 */
enum class PipelineState {
    IDLE,
    INITIALIZING,
    LISTENING,
    PROCESSING,
    SPEAKING,
    ERROR
}

/**
 * Transcription result from ASR.
 */
data class TranscriptionResult(
    val text: String,
    val confidence: Float,
    val success: Boolean,
    val error: String? = null
)
