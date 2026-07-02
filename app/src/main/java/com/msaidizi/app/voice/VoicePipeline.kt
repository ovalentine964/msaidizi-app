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
 * Complete voice pipeline orchestrator.
 * Flow: AudioRecord → VAD → Whisper → IntentRouter → Agent → Piper → AudioTrack
 *
 * Manages the lifecycle of voice models and coordinates the voice interaction.
 * Memory-mapped models, lazy loading, release on background.
 */
@Singleton
class VoicePipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioRecorder: AudioRecorder,
    private val vad: VoiceActivityDetector,
    private val speechRecognizer: SpeechRecognizer,
    private val ttsEngine: TextToSpeech,
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

    /**
     * Initialize the voice pipeline.
     * Loads VAD (always loaded), defers ASR and TTS for lazy loading.
     */
    suspend fun initialize() {
        Timber.d("VoicePipeline: Initializing...")
        _pipelineState.value = PipelineState.INITIALIZING

        // VAD is lightweight (~3MB), always load
        // (VoiceActivityDetector uses code-based detection, no model needed)

        // Initialize Piper TTS for Swahili (fast, good quality)
        ttsEngine.loadModel()

        // MMS TTS is lazy-loaded on demand for non-Swahili languages
        // (saves ~35MB RAM until actually needed)

        // ASR is lazy-loaded on first use (saves 40MB on 2GB devices)
        if (DeviceTier.preloadASR()) {
            speechRecognizer.loadModel()
        }

        _pipelineState.value = PipelineState.IDLE
        Timber.i("VoicePipeline: Ready")
    }

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

        // Start recording
        audioRecorder.startRecording(scope)

        // Collect audio chunks and process through VAD
        audioCollectionJob = scope.launch {
            audioRecorder.audioChunks.collect { chunk ->
                val hasSpeech = vad.processChunk(chunk)

                if (hasSpeech && _pipelineState.value != PipelineState.PROCESSING) {
                    // Speech detected, wait for end of speech
                }
            }
        }

        // Monitor for end of speech
        scope.launch {
            audioRecorder.recordingState.collect { state ->
                when (state) {
                    RecordingState.STOPPED,
                    RecordingState.MAX_DURATION -> {
                        processEndOfSpeech()
                    }
                    RecordingState.ERROR_NO_PERMISSION,
                    RecordingState.ERROR_INIT,
                    RecordingState.ERROR_READ -> {
                        _pipelineState.value = PipelineState.ERROR
                    }
                    else -> { } // continue
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
     * Uses AdaptiveAsrEngine for dialect normalization, vocabulary correction,
     * phoneme-aware post-processing, and Bayesian confidence calibration.
     */
    private suspend fun processEndOfSpeech() {
        val speechAudio = vad.getSpeechAudio()
        if (speechAudio == null || speechAudio.isEmpty()) {
            _pipelineState.value = PipelineState.IDLE
            return
        }

        _pipelineState.value = PipelineState.PROCESSING

        try {
            // Use AdaptiveAsrEngine for full adaptive pipeline
            // (dialect normalization, vocabulary correction, phoneme mapping, confidence calibration)
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

    /**
     * Speak a response to the user.
     *
     * Routes to the appropriate TTS engine:
     * - Swahili/Sheng → Piper (fast, optimized for Swahili)
     * - Other African languages → MMS (broader language support)
     * - Unsupported language → Piper with Swahili fallback
     */
    suspend fun speak(text: String, language: String = "sw") {
        _pipelineState.value = PipelineState.SPEAKING

        val engine = selectTtsEngine(language)
        Timber.d("TTS: Using %s engine for language '%s'", engine.name, language)

        when (engine) {
            TtsEngineType.MMS -> mmsTtsEngine.speak(text, language)
            TtsEngineType.PIPER -> ttsEngine.speak(text, language)
        }

        _response.emit(text)
    }

    /**
     * Speak and wait for completion.
     */
    suspend fun speakAndWait(text: String, language: String = "sw") {
        speak(text, language)
        // Wait for appropriate TTS engine to finish
        while (isAnyTtsSpeaking()) {
            delay(100)
        }
        _pipelineState.value = PipelineState.IDLE
    }

    /**
     * Stop speaking (stops both engines).
     */
    fun stopSpeaking() {
        ttsEngine.stop()
        mmsTtsEngine.stop()
        _pipelineState.value = PipelineState.IDLE
    }

    /**
     * Select TTS engine based on language.
     *
     * Strategy:
     * - Swahili/Sheng/English → Piper (fast, low memory, good quality)
     * - Other supported African languages → MMS (supports 1,100+ languages)
     * - Unknown language → Piper with Swahili fallback
     */
    private fun selectTtsEngine(language: String): TtsEngineType {
        return when (language.lowercase()) {
            "sw", "swahili", "swa", "sheng", "mixed" -> TtsEngineType.PIPER
            "en", "english", "eng" -> TtsEngineType.PIPER
            else -> {
                // Check if MMS supports this language
                if (mmsTtsEngine.isLanguageSupported(language)) {
                    TtsEngineType.MMS
                } else {
                    Timber.w("Language '%s' not supported by any TTS, falling back to Piper/Swahili", language)
                    TtsEngineType.PIPER
                }
            }
        }
    }

    /**
     * Check if any TTS engine is currently speaking.
     */
    private fun isAnyTtsSpeaking(): Boolean {
        return ttsEngine.isSpeaking() || mmsTtsEngine.isSpeaking()
    }

    /**
     * Release models when app goes to background.
     * Critical for 2GB devices — frees ~100MB.
     */
    fun onBackground() {
        Timber.d("VoicePipeline: Releasing models for background")
        speechRecognizer.unloadModel()
        // Unload MMS to free ~35MB (heavier than Piper)
        mmsTtsEngine.unloadModel()
        // Keep Piper TTS for Swahili notifications
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
        ttsEngine.unloadModel()
        mmsTtsEngine.unloadModel()
        audioCollectionJob?.cancel()
        _pipelineState.value = PipelineState.IDLE
    }

    /**
     * Get pipeline status.
     */
    fun getStatus(): Map<String, Any> = mapOf(
        "state" to _pipelineState.value.name,
        "asrLoaded" to speechRecognizer.isModelReady(),
        "piperReady" to ttsEngine.isModelReady(),
        "mmsReady" to mmsTtsEngine.isModelReady(),
        "ttsSpeaking" to isAnyTtsSpeaking(),
        "deviceTier" to DeviceTier.current.name
    )
}

/**
 * TTS engine selection.
 */
enum class TtsEngineType {
    /** Piper — fast, optimized for Swahili, ~25MB */
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
