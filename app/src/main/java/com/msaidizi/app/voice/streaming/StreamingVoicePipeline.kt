package com.msaidizi.app.voice.streaming

import android.content.Context
import com.msaidizi.app.core.util.DeviceTier
import com.msaidizi.app.voice.*
import com.msaidizi.app.voice.emotion.AudioFeatureExtractor
import com.msaidizi.app.voice.emotion.VoiceEmotion
import com.msaidizi.app.voice.emotion.VoiceEmotionDetector
import com.msaidizi.app.voice.dialect.DialectDetectionEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Low-latency streaming voice pipeline (v2 — real streaming ASR).
 *
 * Key upgrades from v1:
 * 1. **Real Streaming ASR**: Uses SpeechRecognizer.startStreaming() for
 *    actual partial transcription in 500ms hops (not just full-utterance re-runs)
 * 2. **Kokoro TTS**: Primary TTS with emotion-aware voice personality
 * 3. **Streaming TTS**: Start TTS synthesis on first sentence of LLM output
 * 4. **Preamble Phrases**: Eliminate dead air with filler phrases
 * 5. **Emotion → Voice**: Auto-selects Kokoro voice style based on emotion
 *
 * Latency breakdown (target):
 * ┌──────────────────────────────┬───────────────┐
 * │ Component                    │ Target (ms)   │
 * ├──────────────────────────────┼───────────────┤
 * │ Streaming ASR (partial)      │ <200          │
 * │ LLM (first token)           │ <150          │
 * │ Kokoro TTS (first chunk)    │ <100          │
 * │ Audio playback latency       │ <20           │
 * ├──────────────────────────────┼───────────────┤
 * │ Total (first response byte)  │ <350          │
 * └──────────────────────────────┴───────────────┘
 *
 * @see VoicePipeline for the standard (non-streaming) pipeline
 */
@Singleton
class StreamingVoicePipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioRecorder: AudioRecorder,
    private val vad: VoiceActivityDetector,
    private val speechRecognizer: SpeechRecognizer,
    private val kokoroTts: KokoroTtsEngine,
    private val piperTts: TextToSpeech,
    private val mmsTtsEngine: MMSTextToSpeech,
    private val llmEngine: LlmEngine,
    private val featureExtractor: AudioFeatureExtractor,
    private val emotionDetector: VoiceEmotionDetector,
    private val dialectEngine: DialectDetectionEngine
) {
    companion object {
        private const val TAG = "StreamingPipeline"
        private const val PREAMBLE_DELAY_MS = 50L
    }

    // ────────────── Pipeline State ──────────────

    private val _pipelineState = MutableStateFlow(StreamingPipelineState.IDLE)
    val pipelineState: StateFlow<StreamingPipelineState> = _pipelineState

    /** Partial transcripts as user speaks */
    private val _partialTranscript = MutableSharedFlow<StreamingTranscript>(extraBufferCapacity = 8)
    val partialTranscript: SharedFlow<StreamingTranscript> = _partialTranscript

    /** Final transcription result */
    private val _finalTranscript = MutableSharedFlow<StreamingTranscript>(extraBufferCapacity = 4)
    val finalTranscript: SharedFlow<StreamingTranscript> = _finalTranscript

    /** Audio response chunks */
    private val _audioResponse = MutableSharedFlow<ShortArray>(extraBufferCapacity = 32)
    val audioResponse: SharedFlow<ShortArray> = _audioResponse

    /** Detected emotion */
    private val _emotion = MutableSharedFlow<VoiceEmotion>(extraBufferCapacity = 4)
    val emotion: SharedFlow<VoiceEmotion> = _emotion

    /** Detected dialect */
    private val _dialect = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val dialect: SharedFlow<String> = _dialect

    // ────────────── Preamble Phrases ──────────────

    private val preamblePhrases = mapOf(
        "sw" to listOf("Sawa...", "Hebu nione...", "Sikiliza..."),
        "en" to listOf("Sure...", "Let me check...", "One moment..."),
        "sheng" to listOf("Sawa boss...", "Hebu nione...", "Poa...")
    )

    private var processingScope: CoroutineScope? = null

    // ────────────────────── Initialization ──────────────────────

    /**
     * Initialize the streaming pipeline.
     */
    suspend fun initialize() {
        Timber.tag(TAG).d("Initializing streaming pipeline...")
        _pipelineState.value = StreamingPipelineState.INITIALIZING

        // Load primary TTS: Kokoro
        val kokoroLoaded = kokoroTts.loadModel()
        if (!kokoroLoaded) {
            Timber.tag(TAG).w("Kokoro not available, falling back to Piper")
            piperTts.loadModel()
        }

        // ASR lazy-loads on first use
        if (DeviceTier.preloadASR()) {
            speechRecognizer.loadModel()
        }

        _pipelineState.value = StreamingPipelineState.IDLE
        Timber.tag(TAG).i("Streaming pipeline ready (ASR: %s, TTS: %s)",
            if (speechRecognizer.isModelReady()) speechRecognizer.getActiveModelId() else "lazy",
            if (kokoroLoaded) "Kokoro" else "Piper")
    }

    // ────────────────────── Voice Processing ──────────────────────

    /**
     * Start listening with REAL streaming ASR.
     * Uses SpeechRecognizer.startStreaming() for partial transcripts in 500ms hops.
     */
    suspend fun startListening(scope: CoroutineScope) {
        if (_pipelineState.value == StreamingPipelineState.LISTENING) {
            Timber.tag(TAG).w("Already listening")
            return
        }

        _pipelineState.value = StreamingPipelineState.LISTENING
        processingScope = scope
        vad.reset()
        emotionDetector.reset()

        audioRecorder.startRecording(scope)

        // Start real streaming ASR
        speechRecognizer.startStreaming(
            audioChunks = audioRecorder.audioChunks,
            onPartial = { text, confidence ->
                scope.launch {
                    _partialTranscript.emit(StreamingTranscript(
                        text = text,
                        rawText = text,
                        confidence = confidence,
                        dialect = "",
                        emotion = VoiceEmotion.NEUTRAL,
                        latencyMs = 0,
                        isPartial = true
                    ))
                }
            },
            onFinal = { text ->
                // Final result handled in processEndOfSpeech
            },
            scope = scope
        )

        // Monitor VAD for end-of-speech
        scope.launch {
            audioRecorder.audioChunks.collect { chunk ->
                val speechProb = vad.processChunk(chunk)
                if (speechProb > 0.5f) {
                    // Also extract emotion features (async, non-blocking)
                    processingScope?.launch(Dispatchers.Default) {
                        val features = featureExtractor.extractEmotionFeatures(chunk)
                        val emotionResult = emotionDetector.detect(features)
                        _emotion.emit(emotionResult.primaryEmotion)
                    }
                }
            }
        }

        // Monitor for end of speech
        scope.launch {
            audioRecorder.recordingState.collect { state ->
                when (state) {
                    RecordingState.STOPPED, RecordingState.MAX_DURATION -> {
                        processEndOfSpeech()
                    }
                    RecordingState.ERROR_NO_PERMISSION,
                    RecordingState.ERROR_INIT,
                    RecordingState.ERROR_READ -> {
                        _pipelineState.value = StreamingPipelineState.ERROR
                    }
                    else -> { }
                }
            }
        }
    }

    /**
     * Stop listening and process final audio.
     */
    suspend fun stopListening() {
        speechRecognizer.stopStreaming()
        audioRecorder.stopRecording()
        processEndOfSpeech()
    }

    // ────────────────────── Internal Processing ──────────────────────

    /**
     * Process end of speech — full pipeline execution.
     */
    private suspend fun processEndOfSpeech() {
        val speechAudio = vad.getAccumulatedAudio()
        if (speechAudio == null || speechAudio.isEmpty()) {
            _pipelineState.value = StreamingPipelineState.IDLE
            return
        }

        _pipelineState.value = StreamingPipelineState.PROCESSING
        val startTime = System.currentTimeMillis()

        try {
            // Step 1: Extract features for emotion + dialect detection (parallel)
            val featuresJob = processingScope?.async(Dispatchers.Default) {
                val emotionFeatures = featureExtractor.extractEmotionFeatures(speechAudio)
                val dialectFeatures = featureExtractor.extractDialectFeatures(speechAudio)
                Pair(emotionFeatures, dialectFeatures)
            }

            // Step 2: Final ASR transcription (more accurate than streaming partials)
            val transcript = speechRecognizer.transcribe(speechAudio)
            if (transcript.isNullOrBlank()) {
                _pipelineState.value = StreamingPipelineState.IDLE
                return
            }

            // Step 3: Get features
            val (emotionFeatures, dialectFeatures) = featuresJob?.await()
                ?: Pair(null, null)

            // Step 4: Emotion analysis
            val detectedEmotion = if (emotionFeatures != null) {
                emotionDetector.detect(emotionFeatures).primaryEmotion
            } else VoiceEmotion.NEUTRAL
            _emotion.emit(detectedEmotion)

            // Step 5: Dialect detection
            val dialectResult = dialectEngine.detect(transcript, dialectFeatures)
            _dialect.emit(dialectResult.dialectId)

            // Step 6: Normalize dialect
            val normalizedText = dialectEngine.normalize(transcript, dialectResult.dialectId)

            // Step 7: Emit final transcript
            val elapsed = System.currentTimeMillis() - startTime
            _finalTranscript.emit(StreamingTranscript(
                text = normalizedText,
                rawText = transcript,
                confidence = dialectResult.confidence,
                dialect = dialectResult.dialectId,
                emotion = detectedEmotion,
                latencyMs = elapsed,
                isPartial = false
            ))

            _pipelineState.value = StreamingPipelineState.IDLE

            Timber.tag(TAG).d(
                "Processed in %dms: '%s' (dialect: %s, emotion: %s, ASR: %s)",
                elapsed, normalizedText, dialectResult.dialectId,
                detectedEmotion, speechRecognizer.getActiveModelId()
            )

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error in streaming pipeline")
            _pipelineState.value = StreamingPipelineState.ERROR
        }
    }

    // ────────────────────── TTS with Emotion ──────────────────────

    /**
     * Speak a response with emotion-aware voice personality.
     * Uses Kokoro's emotion-to-voice mapping for empathetic responses.
     */
    suspend fun speakWithEmotion(
        text: String,
        language: String = "sw",
        emotion: VoiceEmotion = VoiceEmotion.NEUTRAL
    ) {
        _pipelineState.value = StreamingPipelineState.SPEAKING

        if (kokoroTts.isModelReady()) {
            kokoroTts.setVoiceForEmotion(emotion)
            kokoroTts.speak(text, language)
        } else if (piperTts.isModelReady()) {
            piperTts.speak(text, language)
        }

        _pipelineState.value = StreamingPipelineState.IDLE
    }

    // ────────────────────── Preamble Phrases ──────────────────────

    /**
     * Play a preamble phrase to eliminate dead air while processing.
     */
    suspend fun playPreamble(language: String = "sw") {
        val phrases = preamblePhrases[language] ?: preamblePhrases["sw"]!!
        val phrase = phrases.random()

        processingScope?.launch {
            if (kokoroTts.isModelReady()) {
                kokoroTts.speak(phrase, language)
            } else {
                piperTts.speak(phrase, language)
            }
        }
    }

    // ────────────────────── Resource Management ──────────────────────

    fun onBackground() {
        speechRecognizer.unloadModel()
        mmsTtsEngine.unloadModel()
    }

    suspend fun onForeground() {
        if (DeviceTier.preloadASR()) {
            speechRecognizer.loadModel()
        }
    }

    fun release() {
        speechRecognizer.stopStreaming()
        audioRecorder.release()
        speechRecognizer.unloadModel()
        kokoroTts.unloadModel()
        piperTts.unloadModel()
        mmsTtsEngine.unloadModel()
        _pipelineState.value = StreamingPipelineState.IDLE
    }

    fun getStatus(): Map<String, Any> = mapOf(
        "state" to _pipelineState.value.name,
        "asrModel" to speechRecognizer.getActiveModelId(),
        "asrLoaded" to speechRecognizer.isModelReady(),
        "asrStreaming" to speechRecognizer.isStreamingActive(),
        "kokoroReady" to kokoroTts.isModelReady(),
        "piperReady" to piperTts.isModelReady(),
        "deviceTier" to DeviceTier.current.name
    )
}

// ════════════════════════════════════════════════════════════════════
// DATA CLASSES
// ════════════════════════════════════════════════════════════════════

enum class StreamingPipelineState {
    IDLE,
    INITIALIZING,
    LISTENING,
    PROCESSING,
    SPEAKING,
    ERROR
}

/**
 * Streaming transcript with metadata.
 */
data class StreamingTranscript(
    val text: String,
    val rawText: String,
    val confidence: Float,
    val dialect: String,
    val emotion: VoiceEmotion,
    val latencyMs: Long,
    val isPartial: Boolean
)
