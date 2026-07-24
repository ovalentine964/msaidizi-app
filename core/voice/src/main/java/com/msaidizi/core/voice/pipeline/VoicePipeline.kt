package com.msaidizi.core.voice.pipeline

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.msaidizi.core.voice.stt.AdaptiveAsrEngine
import com.msaidizi.core.voice.stt.SpeechRecognizer
import com.msaidizi.core.voice.stt.TranscriptionResult
import com.msaidizi.core.voice.tts.PiperTtsEngine
import com.msaidizi.core.voice.tts.TextToSpeech
import com.msaidizi.core.voice.tts.TtsEngineType
import com.msaidizi.core.voice.vad.VoiceActivityDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Complete voice pipeline orchestrator for Msaidizi.
 *
 * Coordinates the full voice interaction flow:
 *
 * ```
 * AudioRecord → VAD (Silero) → STT (Whisper) → Dialect Detection → text
 *                                                            ↓
 *                                     (text goes to ReasoningEngine)
 *                                                            ↓
 * text → TTS (Piper/Kokoro) → AudioTrack
 * ```
 *
 * ## Memory Safety (Critical for 2GB Phones)
 *
 * On BASIC tier (2GB RAM), Whisper (~40MB) and Kokoro (~90MB) are NEVER
 * loaded simultaneously. The pipeline uses mutual exclusion:
 *
 * - **During STT**: Unload Kokoro → Load Whisper → Transcribe → Unload Whisper
 * - **During TTS**: Use Piper (25MB) as default; Kokoro only on STANDARD+
 *
 * This ensures the app never exceeds the memory budget on low-end devices.
 *
 * ## Graceful Degradation
 *
 * If any model fails to load or runs OOM:
 * 1. The pipeline catches the error
 * 2. Sets [voiceInputAvailable] to false
 * 3. The UI falls back to text input
 *
 * The user never sees a crash — they just get text input instead of voice.
 *
 * ## Integration with Superagent Architecture
 *
 * The VoicePipeline is a **core module** — it has no knowledge of intents,
 * business logic, or the reasoning engine. It simply:
 * 1. Listens for audio → produces text (STT path)
 * 2. Takes text → produces audio (TTS path)
 *
 * The [com.msaidizi.superagent.engine.ReasoningEngine] consumes the text
 * output and produces text responses that the pipeline speaks.
 *
 * @see AdaptiveAsrEngine for dialect-aware transcription
 * @see VoiceActivityDetector for speech boundary detection
 * @see PiperTtsEngine for the primary TTS engine
 */
@Singleton
class VoicePipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vad: VoiceActivityDetector,
    private val asrEngine: AdaptiveAsrEngine,
    private val speechRecognizer: SpeechRecognizer,
    private val piperTts: PiperTtsEngine
) {
    // ── Pipeline state ──
    private val _pipelineState = MutableStateFlow(PipelineState.IDLE)
    /** Observable pipeline state */
    val pipelineState: StateFlow<PipelineState> = _pipelineState

    // ── Transcription results ──
    private val _transcription = MutableSharedFlow<TranscriptionResult>(extraBufferCapacity = 4)
    /** Emitted when STT produces a transcription */
    val transcription: SharedFlow<TranscriptionResult> = _transcription

    // ── Spoken responses ──
    private val _response = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Emitted when TTS finishes speaking a response */
    val response: SharedFlow<String> = _response

    // ── Voice availability ──
    private val _voiceInputAvailable = MutableStateFlow(true)
    /** Whether voice input is available (false = fallback to text) */
    val voiceInputAvailable: StateFlow<Boolean> = _voiceInputAvailable

    // ── Audio focus ──
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hadAudioFocus = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                stopSpeaking()
                hadAudioFocus = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                stopSpeaking()
                hadAudioFocus = false
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hadAudioFocus = true
            }
        }
    }

    // ────────────────────── Initialization ──────────────────────

    /**
     * Initialize the voice pipeline.
     *
     * Loads ASR and TTS models appropriate for the device tier.
     * On 2GB devices, only Piper TTS is loaded initially — ASR is
     * lazy-loaded on first voice input to conserve memory.
     */
    suspend fun initialize() {
        Timber.d("VoicePipeline: Initializing...")
        _pipelineState.value = PipelineState.INITIALIZING

        try {
            // Initialize VAD (lightweight, always loaded)
            vad.initialize()

            // Load TTS (Piper, ~25MB — fits all devices)
            val ttsLoaded = piperTts.loadModel()
            if (!ttsLoaded) {
                Timber.w("VoicePipeline: Piper TTS failed — voice output unavailable")
            }

            // Lazy-load ASR on first use (conserves memory on 2GB devices)
            // Higher-tier devices can preload here if desired

            _pipelineState.value = PipelineState.IDLE
            Timber.i("VoicePipeline: Ready (TTS: %s, ASR: lazy)",
                if (piperTts.isModelReady()) "Piper" else "none")
        } catch (e: Throwable) {
            Timber.e(e, "VoicePipeline: Initialization failed — voice disabled")
            _voiceInputAvailable.value = false
            _pipelineState.value = PipelineState.IDLE
        }
    }

    // ────────────────────── Voice Input (STT Path) ──────────────────────

    /**
     * Start listening for voice input.
     * Begins recording and processes audio through VAD.
     *
     * Flow: AudioRecorder → VAD → speech detection → accumulate → STT
     */
    suspend fun startListening(scope: CoroutineScope) {
        if (_pipelineState.value == PipelineState.LISTENING) {
            Timber.w("VoicePipeline: Already listening")
            return
        }

        _pipelineState.value = PipelineState.LISTENING
        vad.reset()

        // Wire VAD speech-end callback to trigger STT
        vad.onSpeechEnd = { audioData ->
            scope.launch {
                processEndOfSpeech(audioData)
            }
        }
    }

    /**
     * Feed audio chunks from the recorder into the VAD.
     * Call this from the audio recording loop.
     *
     * @param chunk Audio samples at 16kHz mono 16-bit PCM
     */
    fun feedAudioChunk(chunk: ShortArray) {
        if (_pipelineState.value != PipelineState.LISTENING) return
        vad.processChunk(chunk)
    }

    /**
     * Stop listening and process any remaining audio.
     */
    suspend fun stopListening() {
        val remaining = vad.getAccumulatedAudio()
        if (remaining.isNotEmpty()) {
            processEndOfSpeech(remaining)
        }
        _pipelineState.value = PipelineState.IDLE
    }

    /**
     * Process end-of-speech: run ASR on accumulated audio.
     *
     * This is where the STT magic happens. The pipeline:
     * 1. Loads the ASR model if not already loaded (lazy on 2GB devices)
     * 2. Runs adaptive transcription (dialect normalization, confidence calibration)
     * 3. Emits the transcription result
     */
    private suspend fun processEndOfSpeech(audioData: ShortArray) {
        if (audioData.isEmpty()) {
            _pipelineState.value = PipelineState.IDLE
            return
        }

        _pipelineState.value = PipelineState.PROCESSING

        try {
            // Ensure ASR model is loaded (lazy-load)
            if (!speechRecognizer.isModelReady()) {
                val loaded = speechRecognizer.loadModel()
                if (!loaded) {
                    _transcription.emit(TranscriptionResult(
                        text = "",
                        confidence = 0f,
                        success = false,
                        error = "Voice recognition unavailable. Please type your message."
                    ))
                    _voiceInputAvailable.value = false
                    _pipelineState.value = PipelineState.IDLE
                    return
                }
            }

            // Run adaptive ASR (handles dialect normalization + confidence calibration)
            val result = asrEngine.transcribe(audioData)

            if (result.transcript.isBlank()) {
                _transcription.emit(TranscriptionResult(
                    text = "",
                    confidence = 0f,
                    success = false,
                    error = "Could not understand. Please try again."
                ))
            } else {
                Timber.d("VoicePipeline: '%s' → '%s' (conf=%.3f, dialect=%s)",
                    result.rawTranscript, result.transcript,
                    result.calibratedConfidence, result.dialectRegion)

                _transcription.emit(TranscriptionResult(
                    text = result.transcript,
                    confidence = result.calibratedConfidence,
                    success = true,
                    language = result.language,
                    dialectRegion = result.dialectRegion
                ))
            }

            // Unload ASR model to free memory (critical on 2GB devices)
            speechRecognizer.unloadModel()

            _pipelineState.value = PipelineState.IDLE
        } catch (e: OutOfMemoryError) {
            Timber.e("VoicePipeline: OOM during STT — falling back to text")
            speechRecognizer.unloadModel()
            System.gc()
            _transcription.emit(TranscriptionResult(
                text = "", confidence = 0f, success = false,
                error = "Out of memory. Please type your message."
            ))
            _voiceInputAvailable.value = false
            _pipelineState.value = PipelineState.IDLE
        } catch (e: Throwable) {
            Timber.e(e, "VoicePipeline: STT error")
            _transcription.emit(TranscriptionResult(
                text = "", confidence = 0f, success = false,
                error = "Error: ${e.message}"
            ))
            _pipelineState.value = PipelineState.IDLE
        }
    }

    // ────────────────────── Voice Output (TTS Path) ──────────────────────

    /**
     * Speak a response to the user.
     * Waits for audio playback to complete.
     *
     * @param text Text to speak
     * @param language Language code (default "sw")
     */
    suspend fun speak(text: String, language: String = "sw") {
        if (!requestAudioFocus()) {
            Timber.w("VoicePipeline: Audio focus denied — cannot speak")
            return
        }

        _pipelineState.value = PipelineState.SPEAKING

        try {
            // Use Piper TTS (always available, ~25MB)
            // Kokoro can be selected on higher-tier devices via selectTtsEngine()
            piperTts.speak(text, language)
        } catch (e: Throwable) {
            Timber.e(e, "VoicePipeline: TTS error")
        } finally {
            // Wait for playback to complete
            while (piperTts.isSpeaking()) {
                delay(50)
            }
            _response.emit(text)
            _pipelineState.value = PipelineState.IDLE
            abandonAudioFocus()
        }
    }

    /** Stop speaking immediately */
    fun stopSpeaking() {
        piperTts.stop()
        _pipelineState.value = PipelineState.IDLE
    }

    // ────────────────────── Lifecycle ──────────────────────

    /** Release models when app goes to background */
    fun onBackground() {
        speechRecognizer.unloadModel()
        Timber.d("VoicePipeline: Released ASR for background")
    }

    /** Reload models when app returns to foreground */
    suspend fun onForeground() {
        if (!piperTts.isModelReady()) {
            piperTts.loadModel()
        }
    }

    /** Release all resources */
    fun release() {
        abandonAudioFocus()
        speechRecognizer.unloadModel()
        piperTts.unloadModel()
        vad.reset()
        _pipelineState.value = PipelineState.IDLE
    }

    /** Get pipeline status for diagnostics */
    fun getStatus(): Map<String, Any> = mapOf(
        "state" to _pipelineState.value.name,
        "asrLoaded" to speechRecognizer.isModelReady(),
        "asrModel" to speechRecognizer.getActiveModelId(),
        "ttsReady" to piperTts.isModelReady(),
        "ttsSpeaking" to piperTts.isSpeaking(),
        "voiceInputAvailable" to _voiceInputAvailable.value
    )

    // ────────────────────── Audio Focus ──────────────────────

    private fun requestAudioFocus(): Boolean {
        if (hadAudioFocus) return true

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .setAcceptsDelayedFocusGain(true)
            .build()

        audioFocusRequest = focusRequest
        val result = audioManager.requestAudioFocus(focusRequest)
        hadAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hadAudioFocus
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
        hadAudioFocus = false
    }
}

/** Pipeline states */
enum class PipelineState {
    IDLE, INITIALIZING, LISTENING, PROCESSING, SPEAKING, ERROR
}
