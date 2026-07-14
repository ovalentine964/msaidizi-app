package com.msaidizi.app.voice

import android.content.Context
import com.msaidizi.app.core.MemoryManager
import com.msaidizi.app.core.util.DeviceTier
import com.msaidizi.app.core.language.AdaptiveAsrEngine
import com.msaidizi.app.core.language.ConversationLearningPipeline
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Complete voice pipeline orchestrator (v3 — memory-safe for 2GB devices).
 *
 * Flow: AudioRecord → VAD → Whisper Tiny → IntentRouter → Agent → Kokoro/Piper → AudioTrack
 *
 * ═══ MUTUAL EXCLUSION (critical for 2GB phones) ═══
 * On BASIC tier (2GB RAM), Whisper (~40MB) and Kokoro (~90MB) are NEVER loaded
 * at the same time. Total would be ~130MB + OS overhead → OOM crash.
 *
 * During STT (voice input):
 *   1. Unload Kokoro TTS if loaded → frees ~90MB
 *   2. Load Whisper for transcription → uses ~40MB
 *   3. After STT completes, unload Whisper → frees ~40MB
 *   4. Piper TTS (25MB) remains available as TTS fallback throughout
 *
 * During TTS (voice output):
 *   1. STT result is already captured; Whisper is unloaded
 *   2. On BASIC tier: prefer Piper (25MB). Load Kokoro only if memory allows.
 *   3. On STANDARD+: use Kokoro for best quality
 *
 * ASR Strategy (2GB phones first):
 * - Primary: Whisper Tiny INT4 (~40MB) — fits all devices
 * - Edge: Moonshine Tiny (~40MB) — alternative for mobile
 * - Turbo: ~150MB — HIGH-END ONLY (4GB+ RAM)
 * - WAXAL adapter: +5MB LoRA fine-tuned for African languages
 *
 * TTS Strategy:
 * - BASIC tier (2GB): Piper (25MB) default, Kokoro on-demand with memory check
 * - STANDARD+: Kokoro (90MB) primary, Piper (25MB) fallback
 * - MMS: Other African languages (on-demand)
 *
 * Graceful degradation:
 * - If any model fails to load, falls back to text input
 * - voiceInputAvailable = false signals UI to show text input
 * - OOM during inference triggers model unload + System.gc()
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
    private val adaptiveAsrEngine: AdaptiveAsrEngine,
    private val memoryManager: MemoryManager,
    private val conversationLearningPipeline: ConversationLearningPipeline,
    private val harness: VoicePipelineHarness,
    private val sherpaVoiceEngine: SherpaVoiceEngine
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

    // Processing feedback from harness (Swahili messages during processing)
    val processingFeedback: SharedFlow<String> = harness.processingFeedback

    // Collect audio chunks from recorder
    private var audioCollectionJob: Job? = null

    // Graceful degradation: set to true if a model fails to load.
    // When true, the UI should offer text input as fallback.
    private val _voiceInputAvailable = MutableStateFlow(true)
    val voiceInputAvailable: StateFlow<Boolean> = _voiceInputAvailable

    // Whether Kokoro was loaded successfully during initialization
    private var kokoroLoadAttempted = false
    private var kokoroLoadSucceeded = false

    // ── Sherpa-ONNX integration flag ──
    // When true, uses SherpaVoiceEngine for ASR/TTS/VAD instead of raw ONNX Runtime.
    // Set to true when sherpa-onnx JNI libs and models are available.
    // Falls back to legacy pipeline if sherpa-onnx initialization fails.
    private var useSherpaOnnx = false

    // ────────────────────── Initialization ──────────────────────

    /**
     * Initialize the voice pipeline.
     * Strategy depends on device tier:
     * - BASIC (2GB): Load Piper TTS only (~25MB). ASR and Kokoro lazy-loaded with mutual exclusion.
     * - STANDARD+: Load Kokoro TTS (~90MB). ASR lazy-loaded on first use.
     *
     * On 2GB devices, Kokoro and Whisper are NEVER loaded simultaneously.
     * During STT: Whisper is loaded, Kokoro is unloaded (use Piper for TTS).
     * During TTS with Kokoro: Whisper is already unloaded (STT result is available).
     */
    suspend fun initialize() {
        Timber.d("VoicePipeline: Initializing (tier=%s)...", DeviceTier.current)
        _pipelineState.value = PipelineState.INITIALIZING

        // Wire conversation learning pipeline to ASR engine
        adaptiveAsrEngine.conversationLearningPipeline = conversationLearningPipeline
        Timber.d("VoicePipeline: Conversation learning pipeline wired to ASR engine")

        // ── Try Sherpa-ONNX first (preferred) ──
        useSherpaOnnx = tryInitSherpaOnnx()
        if (useSherpaOnnx) {
            Timber.i("VoicePipeline: Using Sherpa-ONNX voice engine")
            _pipelineState.value = PipelineState.IDLE
            return
        }

        // ── Fallback: Legacy ONNX Runtime pipeline ──
        Timber.i("VoicePipeline: Sherpa-ONNX unavailable, using legacy ONNX Runtime")
        initLegacyPipeline()
    }

    /**
     * Try to initialize sherpa-onnx voice engine.
     * Returns true if successful, false to fall back to legacy.
     */
    private suspend fun tryInitSherpaOnnx(): Boolean {
        return try {
            // Load VAD (lightweight, always useful)
            val vadLoaded = sherpaVoiceEngine.loadVad()
            if (vadLoaded) {
                Timber.d("VoicePipeline: Sherpa VAD loaded")
            }

            // Load ASR (Whisper via sherpa-onnx)
            val asrLoaded = sherpaVoiceEngine.loadAsr("sw")
            if (!asrLoaded) {
                Timber.w("VoicePipeline: Sherpa ASR failed to load")
            }

            // Load TTS (Piper via sherpa-onnx)
            val ttsLoaded = sherpaVoiceEngine.loadTts()
            if (!ttsLoaded) {
                Timber.w("VoicePipeline: Sherpa TTS failed to load")
            }

            val anyLoaded = vadLoaded || asrLoaded || ttsLoaded
            if (!anyLoaded) {
                Timber.w("VoicePipeline: No sherpa-onnx components loaded")
            }
            anyLoaded
        } catch (e: Exception) {
            Timber.w(e, "VoicePipeline: Sherpa-ONNX init failed")
            false
        }
    }

    /**
     * Initialize the legacy ONNX Runtime pipeline (original behavior).
     */
    private suspend fun initLegacyPipeline() {

        if (isBasicTier) {
            // ═══ 2GB DEVICE: Conservative initialization ═══
            // Only load Piper TTS (25MB) — leave headroom for the OS and app.
            // Kokoro (90MB) will be loaded on-demand with memory checks.
            // ASR (40MB) will be lazy-loaded on first voice input.
            val piperLoaded = safeLoadModel("piper", MemoryManager.PIPER_MEMORY_MB) {
                piperTts.loadModel()
            }
            if (!piperLoaded) {
                Timber.e("VoicePipeline: Piper TTS failed — voice output unavailable")
            }
            Timber.i("VoicePipeline: BASIC tier — Piper TTS only, ASR lazy, Kokoro on-demand")
        } else {
            // ═══ 3GB+ DEVICE: Standard initialization ═══
            // Load Kokoro TTS (better quality) with fallback to Piper.
            kokoroLoadAttempted = true
            val kokoroLoaded = safeLoadModel("kokoro", MemoryManager.KOKORO_MEMORY_MB) {
                kokoroTts.loadModel()
            }
            if (kokoroLoaded) {
                kokoroLoadSucceeded = true
                memoryManager.acquireHeavyModelSlot(MemoryManager.LoadedHeavyModel.KOKORO)
            } else {
                Timber.w("Kokoro TTS not available, falling back to Piper")
                safeLoadModel("piper", MemoryManager.PIPER_MEMORY_MB) {
                    piperTts.loadModel()
                }
            }

            // Pre-load ASR on higher tiers
            if (DeviceTier.preloadASR()) {
                safeLoadModel("whisper", MemoryManager.WHISPER_MEMORY_MB) {
                    speechRecognizer.loadModel()
                }
            }
        }

        _pipelineState.value = PipelineState.IDLE
        Timber.i("VoicePipeline: Ready (TTS: %s, ASR: %s, tier: %s)",
            getActiveTtsName(),
            if (speechRecognizer.isModelReady()) speechRecognizer.getActiveModelId() else "lazy",
            DeviceTier.current.name
        )
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
     *
     * MUTUAL EXCLUSION: On 2GB devices, unloads Kokoro TTS before loading Whisper.
     * Piper TTS (25MB) remains available as fallback for voice output.
     */
    private suspend fun processEndOfSpeech() {
        val speechAudio = vad.getAccumulatedAudio()
        if (speechAudio == null || speechAudio.isEmpty()) {
            _pipelineState.value = PipelineState.IDLE
            return
        }

        _pipelineState.value = PipelineState.PROCESSING

        try {
            // ═══ MUTUAL EXCLUSION: Free memory for Whisper ═══
            val isBasicTier = DeviceTier.current == DeviceTier.Tier.BASIC
            if (isBasicTier && kokoroTts.isModelReady()) {
                Timber.d("VoicePipeline: Unloading Kokoro TTS to make room for Whisper STT")
                kokoroTts.unloadModel()
                memoryManager.releaseHeavyModelSlot(MemoryManager.LoadedHeavyModel.KOKORO)
            }

            // Acquire the Whisper slot (may unload Kokoro on STANDARD tier too if memory tight)
            if (!memoryManager.acquireHeavyModelSlot(MemoryManager.LoadedHeavyModel.WHISPER)) {
                Timber.e("VoicePipeline: Cannot load ASR — insufficient memory")
                _transcription.emit(TranscriptionResult(
                    text = "",
                    confidence = 0f,
                    success = false,
                    error = "Memory too low for voice recognition. Please type instead."
                ))
                _voiceInputAvailable.value = false
                _pipelineState.value = PipelineState.IDLE
                return
            }

            // Ensure ASR model is loaded (lazy-load if needed)
            if (!speechRecognizer.isModelReady()) {
                val loaded = speechRecognizer.loadModel()
                if (!loaded) {
                    Timber.e("VoicePipeline: ASR model failed to load")
                    _transcription.emit(TranscriptionResult(
                        text = "",
                        confidence = 0f,
                        success = false,
                        error = "Voice recognition unavailable. Please type your message."
                    ))
                    _voiceInputAvailable.value = false
                    memoryManager.releaseHeavyModelSlot(MemoryManager.LoadedHeavyModel.WHISPER)
                    _pipelineState.value = PipelineState.IDLE
                    return
                }
            }

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

            // Feed word-level confidence to conversation learning pipeline
            // This captures unknown words and builds personalized vocabulary
            try {
                conversationLearningPipeline.processTranscription(
                    rawTranscript = result.rawTranscript,
                    correctedTranscript = result.transcript,
                    wordConfidences = result.wordConfidences,
                    language = result.language,
                    dialectRegion = result.dialectRegion,
                    isConfirmed = false  // Not yet confirmed at this point
                )
            } catch (e: Exception) {
                Timber.w(e, "VoicePipeline: Failed to feed learning pipeline")
            }

            // ═══ MUTUAL EXCLUSION: Unload Whisper after STT completes ═══
            // STT result is already captured; no need to keep Whisper in memory.
            // Kokoro/Piper will be loaded on-demand for TTS.
            speechRecognizer.unloadModel()
            memoryManager.releaseHeavyModelSlot(MemoryManager.LoadedHeavyModel.WHISPER)
            Timber.d("VoicePipeline: Unloaded Whisper after STT — memory freed for TTS")

            _pipelineState.value = PipelineState.IDLE
        } catch (e: OutOfMemoryError) {
            Timber.e("OOM during speech processing — falling back to text input")
            speechRecognizer.unloadModel()
            kokoroTts.unloadModel()
            memoryManager.releaseHeavyModelSlot(MemoryManager.LoadedHeavyModel.WHISPER)
            memoryManager.releaseHeavyModelSlot(MemoryManager.LoadedHeavyModel.KOKORO)
            System.gc()
            _transcription.emit(TranscriptionResult(
                text = "",
                confidence = 0f,
                success = false,
                error = "Out of memory. Please type your message."
            ))
            _voiceInputAvailable.value = false
            _pipelineState.value = PipelineState.ERROR
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

    // ────────────────────── Quality-Gated Pipeline Execution ──────────────────────

    /**
     * Execute the full voice pipeline with quality gates via [VoicePipelineHarness].
     *
     * This is the preferred entry point for voice interactions. It wraps the
     * raw pipeline methods (STT → LLM → TTS) with:
     * - STT confidence threshold (< 0.6 → ask to repeat)
     * - LLM response validation + safety check + thinking mode activation
     * - TTS naturalness scoring + voice selection
     * - Fallback: voice → text if any stage fails
     * - Processing feedback: "Sawa, nimesikia..." during gaps
     *
     * @param audioData Raw audio bytes from the microphone
         * @param language Language code (default "sw")
     * @param llmCall Function to call the LLM with transcribed text
     * @return Pipeline result with quality scores and degradation info
     */
    suspend fun executeWithQualityGates(
        audioData: ByteArray,
        language: String = "sw",
        llmCall: suspend (String) -> String
    ): VoicePipelineHarness.VoicePipelineResult {
        return harness.executePipeline(
            audioData = audioData,
            language = language,
            sttCall = {
                // Use the pipeline's STT path
                val result = processEndOfSpeechForResult()
                result ?: TranscriptionResult(
                    text = "",
                    confidence = 0f,
                    success = false,
                    error = "STT processing failed"
                )
            },
            llmCall = llmCall,
            ttsCall = { text -> speak(text, language) },
            feedbackCall = { message -> _response.emit(message) }
        )
    }

    /**
     * Get harness status for monitoring/debugging.
     */
    fun getHarnessStatus(): String = harness.getStatusSummary()

    /**
     * Get harness metrics for analytics.
     */
    fun getHarnessMetrics() = harness.getAggregatePipelineStats()

    /**
     * Internal STT processing that returns a result instead of emitting to flow.
     * Used by the harness for quality-gated execution.
     */
    private suspend fun processEndOfSpeechForResult(): TranscriptionResult? {
        val speechAudio = vad.getAccumulatedAudio() ?: return null
        if (speechAudio.isEmpty()) return null

        try {
            // Ensure ASR model is loaded
            if (!speechRecognizer.isModelReady()) {
                val loaded = speechRecognizer.loadModel()
                if (!loaded) {
                    return TranscriptionResult(
                        text = "",
                        confidence = 0f,
                        success = false,
                        error = "ASR model failed to load"
                    )
                }
            }

            val result = adaptiveAsrEngine.transcribe(speechAudio)
            return TranscriptionResult(
                text = result.transcript,
                confidence = result.calibratedConfidence.calibratedConfidence,
                success = result.transcript.isNotBlank(),
                error = if (result.transcript.isBlank()) "Empty transcription" else null
            )
        } catch (e: Exception) {
            Timber.e(e, "VoicePipeline: STT error in harness mode")
            return TranscriptionResult(
                text = "",
                confidence = 0f,
                success = false,
                error = e.message
            )
        }
    }

    // ────────────────────── Voice Output ──────────────────────

    /**
     * Speak a response to the user.
     * Waits for audio playback to complete before returning to IDLE.
     *
     * TTS engine priority:
     * - BASIC (2GB): Piper (25MB) first — Kokoro only if memory allows
     * - STANDARD+: Kokoro (90MB) → Piper (25MB) → MMS
     *
     * MUTUAL EXCLUSION: Unloads Whisper before loading Kokoro on 2GB devices.
     */
    suspend fun speak(text: String, language: String = "sw") {
        _pipelineState.value = PipelineState.SPEAKING

        try {
            val engine = selectTtsEngine(language)
            Timber.d("TTS: Using %s engine for language '%s'", engine.name, language)

            when (engine) {
                TtsEngineType.KOKORO -> {
                    // ═══ MUTUAL EXCLUSION: Ensure Whisper is unloaded before Kokoro ═══
                    val isBasicTier = DeviceTier.current == DeviceTier.Tier.BASIC
                    if (isBasicTier) {
                        // On 2GB devices: unload Whisper if still loaded, acquire Kokoro slot
                        if (speechRecognizer.isModelReady()) {
                            Timber.d("VoicePipeline: Unloading Whisper for Kokoro TTS")
                            speechRecognizer.unloadModel()
                            memoryManager.releaseHeavyModelSlot(MemoryManager.LoadedHeavyModel.WHISPER)
                        }
                        if (!memoryManager.acquireHeavyModelSlot(MemoryManager.LoadedHeavyModel.KOKORO)) {
                            Timber.w("VoicePipeline: Cannot load Kokoro — falling back to Piper")
                            piperTts.speak(text, language)
                            return
                        }
                    }

                    // Load Kokoro if not ready (lazy-load with memory guard)
                    if (!kokoroTts.isModelReady()) {
                        if (!safeLoadModel("kokoro", MemoryManager.KOKORO_MEMORY_MB) {
                                kokoroTts.loadModel()
                            }) {
                            Timber.w("VoicePipeline: Kokoro load failed — falling back to Piper")
                            piperTts.speak(text, language)
                            return
                        }
                    }
                    kokoroTts.speak(text, language)
                }
                TtsEngineType.PIPER -> piperTts.speak(text, language)
                TtsEngineType.MMS -> mmsTtsEngine.speak(text, language)
            }
        } catch (e: OutOfMemoryError) {
            Timber.e("OOM during TTS — degrading to text-only")
            kokoroTts.unloadModel()
            memoryManager.releaseHeavyModelSlot(MemoryManager.LoadedHeavyModel.KOKORO)
            System.gc()
            // Try Piper as last resort
            try {
                piperTts.speak(text, language)
            } catch (_: Exception) {
                Timber.e("VoicePipeline: Even Piper TTS failed — text-only mode")
            }
        } catch (e: Exception) {
            Timber.e(e, "TTS error")
        } finally {
            // Wait for audio playback to complete before transitioning to IDLE.
            while (isAnyTtsSpeaking()) {
                kotlinx.coroutines.delay(50)
            }
            _response.emit(text)
            _pipelineState.value = PipelineState.IDLE
        }
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
     * speak() already waits for audio completion, so this just delegates.
     */
    suspend fun speakAndWait(text: String, language: String = "sw") {
        speak(text, language)
        // speak() already transitions to IDLE after playback completes
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
     * Select TTS engine based on language, device tier, and availability.
     *
     * Strategy:
     * - BASIC (2GB): Piper first for all languages. Kokoro only if loaded and memory OK.
     * - STANDARD+: Kokoro (best quality) → Piper (fallback) → MMS
     */
    private fun selectTtsEngine(language: String): TtsEngineType {
        val isBasicTier = DeviceTier.current == DeviceTier.Tier.BASIC

        return when (language.lowercase()) {
            "sw", "swahili", "swa", "sheng", "mixed" -> {
                if (isBasicTier) {
                    // 2GB device: Piper first, Kokoro only if loaded and memory OK
                    if (piperTts.isModelReady()) TtsEngineType.PIPER
                    else if (kokoroTts.isModelReady()) TtsEngineType.KOKORO
                    else TtsEngineType.PIPER  // Will trigger lazy load
                } else {
                    if (kokoroTts.isModelReady()) TtsEngineType.KOKORO
                    else if (piperTts.isModelReady()) TtsEngineType.PIPER
                    else TtsEngineType.KOKORO  // Will trigger lazy load
                }
            }
            "en", "english", "eng" -> {
                if (isBasicTier) {
                    if (piperTts.isModelReady()) TtsEngineType.PIPER
                    else if (kokoroTts.isModelReady()) TtsEngineType.KOKORO
                    else TtsEngineType.PIPER
                } else {
                    if (kokoroTts.isModelReady()) TtsEngineType.KOKORO
                    else if (piperTts.isModelReady()) TtsEngineType.PIPER
                    else TtsEngineType.KOKORO
                }
            }
            else -> {
                if (mmsTtsEngine.isLanguageSupported(language) && mmsTtsEngine.isModelReady()) {
                    TtsEngineType.MMS
                } else if (!isBasicTier && kokoroTts.isModelReady()) {
                    TtsEngineType.KOKORO
                } else if (piperTts.isModelReady()) {
                    TtsEngineType.PIPER
                } else {
                    Timber.w("Language '%s' not supported by any TTS, falling back to Piper", language)
                    TtsEngineType.PIPER
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
     * On 2GB (BASIC) devices: release ALL models — critical for survival.
     * On 3GB+: release ASR and MMS, keep Kokoro/Piper for notifications.
     */
    fun onBackground() {
        val isBasicTier = DeviceTier.current == DeviceTier.Tier.BASIC
        Timber.d("VoicePipeline: Releasing models for background (tier=%s)", DeviceTier.current)

        speechRecognizer.unloadModel()
        mmsTtsEngine.unloadModel()
        memoryManager.releaseHeavyModelSlot(MemoryManager.LoadedHeavyModel.WHISPER)

        if (isBasicTier) {
            // 2GB devices: release EVERYTHING
            kokoroTts.unloadModel()
            memoryManager.releaseHeavyModelSlot(MemoryManager.LoadedHeavyModel.KOKORO)
            Timber.i("VoicePipeline: BASIC tier — released all models for background")
        }
    }

    /**
     * Reload models when app returns to foreground.
     * On 2GB devices, only reload Piper TTS (lightweight).
     * ASR and Kokoro will lazy-load on demand.
     */
    suspend fun onForeground() {
        val isBasicTier = DeviceTier.current == DeviceTier.Tier.BASIC
        Timber.d("VoicePipeline: Reloading models (tier=%s)", DeviceTier.current)

        if (isBasicTier) {
            // 2GB: Only reload Piper (25MB) — leave headroom
            if (!piperTts.isModelReady()) {
                safeLoadModel("piper", MemoryManager.PIPER_MEMORY_MB) {
                    piperTts.loadModel()
                }
            }
        } else {
            // 3GB+: Reload Kokoro and ASR if tier warrants it
            if (!kokoroTts.isModelReady()) {
                safeLoadModel("kokoro", MemoryManager.KOKORO_MEMORY_MB) {
                    kokoroTts.loadModel()
                }
            }
            if (DeviceTier.preloadASR() && !speechRecognizer.isModelReady()) {
                safeLoadModel("whisper", MemoryManager.WHISPER_MEMORY_MB) {
                    speechRecognizer.loadModel()
                }
            }
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
        memoryManager.releaseHeavyModelSlot(MemoryManager.LoadedHeavyModel.WHISPER)
        memoryManager.releaseHeavyModelSlot(MemoryManager.LoadedHeavyModel.KOKORO)
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
        "deviceTier" to DeviceTier.current.name,
        "voiceInputAvailable" to _voiceInputAvailable.value,
        "heavyModel" to memoryManager.getLoadedHeavyModel().name,
        "deviceFreeMB" to memoryManager.getMemoryStatus().deviceFreeMB
    )

    // ────────────────────── Helpers ──────────────────────

    /**
     * Safely load a model with memory pre-check.
     * Returns false and logs if there isn't enough memory.
     */
    private suspend fun safeLoadModel(
        name: String,
        estimatedMB: Long,
        loader: suspend () -> Boolean
    ): Boolean {
        if (!memoryManager.canLoadModel(estimatedMB)) {
            Timber.w("VoicePipeline: Skipping %s load — insufficient memory (%dMB needed)", name, estimatedMB)
            return false
        }
        return try {
            loader()
        } catch (e: OutOfMemoryError) {
            Timber.e("OOM loading %s model", name)
            System.gc()
            false
        } catch (e: Exception) {
            Timber.e(e, "Failed to load %s model", name)
            false
        }
    }

    private fun getActiveTtsName(): String = when {
        kokoroTts.isModelReady() -> "Kokoro"
        piperTts.isModelReady() -> "Piper"
        else -> "none"
    }
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
 * Includes detected language for multi-language support.
 */
data class TranscriptionResult(
    val text: String,
    val confidence: Float,
    val success: Boolean,
    val language: String? = null,
    val error: String? = null
)
