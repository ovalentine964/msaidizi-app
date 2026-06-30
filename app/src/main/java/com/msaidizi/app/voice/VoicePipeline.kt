package com.msaidizi.app.voice

import android.content.Context
import com.msaidizi.app.core.util.DeviceTier
import com.msaidizi.app.core.util.SwahiliParser
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
    private val ttsEngine: TextToSpeech
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

        // Initialize TTS (Android TTS fallback is always available)
        ttsEngine.initialize()

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
                    else -> { /* continue */ }
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
     * Runs ASR on the captured audio.
     */
    private suspend fun processEndOfSpeech() {
        val speechAudio = vad.getSpeechAudio()
        if (speechAudio == null || speechAudio.isEmpty()) {
            _pipelineState.value = PipelineState.IDLE
            return
        }

        _pipelineState.value = PipelineState.PROCESSING

        try {
            // Step 1: ASR — convert audio to text
            val rawText = speechRecognizer.transcribe(speechAudio)

            if (rawText.isNullOrBlank()) {
                _transcription.emit(TranscriptionResult(
                    text = "",
                    confidence = 0f,
                    success = false,
                    error = "Could not understand. Please try again."
                ))
                _pipelineState.value = PipelineState.IDLE
                return
            }

            // Step 2: Post-process ASR output
            val correctedText = SwahiliParser.correctASROutput(rawText)

            Timber.d("Transcription: '%s' → '%s'", rawText, correctedText)

            _transcription.emit(TranscriptionResult(
                text = correctedText,
                confidence = 0.85f,  // TODO: Get actual confidence from Whisper
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
     */
    suspend fun speak(text: String, language: String = "sw") {
        _pipelineState.value = PipelineState.SPEAKING
        ttsEngine.speak(text, language)
        _response.emit(text)
    }

    /**
     * Speak and wait for completion.
     */
    suspend fun speakAndWait(text: String, language: String = "sw") {
        speak(text, language)
        // Wait for TTS to finish
        while (ttsEngine.isSpeaking()) {
            delay(100)
        }
        _pipelineState.value = PipelineState.IDLE
    }

    /**
     * Stop speaking.
     */
    fun stopSpeaking() {
        ttsEngine.stop()
        _pipelineState.value = PipelineState.IDLE
    }

    /**
     * Release models when app goes to background.
     * Critical for 2GB devices — frees ~65MB.
     */
    fun onBackground() {
        Timber.d("VoicePipeline: Releasing models for background")
        speechRecognizer.unloadModel()
        // Keep TTS for notifications
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
        ttsEngine.release()
        audioCollectionJob?.cancel()
        _pipelineState.value = PipelineState.IDLE
    }

    /**
     * Get pipeline status.
     */
    fun getStatus(): Map<String, Any> = mapOf(
        "state" to _pipelineState.value.name,
        "asrLoaded" to speechRecognizer.isModelReady(),
        "ttsReady" to (ttsEngine.isSpeaking()),
        "deviceTier" to DeviceTier.current.name
    )
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
