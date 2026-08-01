package com.msaidizi.agent.tools.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.msaidizi.voice.SherpaOnnxEngine
import com.msaidizi.voice.StreamingSttEngine
import com.msaidizi.voice.VadEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import com.msaidizi.agent.tools.core.*

/**
 * VoicePipeline — Full on-device Speech-to-Text and Text-to-Speech.
 *
 * **v2 — Streaming STT**: Uses [StreamingSttEngine] for real-time partial
 * transcription. Audio is fed incrementally as it arrives from the microphone,
 * and partial results are surfaced to the UI via [VoicePipelineState.partialText].
 * This eliminates the "wait for silence" UX bottleneck — users see words
 * appear as they speak.
 *
 * Falls back to offline [SherpaOnnxEngine] if streaming models are not available.
 *
 * Integrates with [SherpaOnnxEngine] for offline STT and Piper ONNX TTS.
 * All processing is fully offline — no network calls.
 *
 * Supported languages:
 * - Swahili (sw) — primary
 * - English (en) — secondary
 * - Auto-detect — uses [LanguageDetector] for post-STT language identification
 *
 * Model layout (expected under app's filesDir or assets):
 *   models/
 *     sherpa-onnx/
 *       whisper/                       ← offline fallback
 *         encoder.onnx, decoder.onnx, tokens.txt
 *       streaming/                     ← streaming STT (preferred)
 *         encoder.onnx, decoder.onnx, joiner.onnx, tokens.txt
 *       silero_vad/                    ← VAD model
 *         silero_vad.onnx
 *       piper-sw/
 *         model.onnx, tokens.txt, espeak-ng-data/
 *       piper-en/
 *         model.onnx, tokens.txt, espeak-ng-data/
 */
@Singleton
class VoicePipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sherpaEngine: SherpaOnnxEngine,
    private val streamingSttEngine: StreamingSttEngine,
    private val languageDetector: LanguageDetector,
    private val codeSwitchHandler: CodeSwitchHandler,
    private val vadEngine: VadEngine
) : Tool {

    override val name = "voice_pipeline"
    override val description = "Voice input/output: speech-to-text (streaming) and text-to-speech (fully on-device via sherpa-onnx)"

    override val argsSchema = argSchema {
        enum("action", "Voice pipeline action",
            listOf("listen", "speak", "status"), required = false)
        string("text", "Text to speak (for TTS)", required = false)
        string("language", "Language code (sw or en)", required = false)
    }

    private val _voiceState = MutableStateFlow(VoicePipelineState())
    val voiceState: StateFlow<VoicePipelineState> = _voiceState.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private val audioBuffer = ByteArrayOutputStream()

    // Engine initialization state
    private var sttInitialized = false
    private var streamingSttInitialized = false
    private var ttsInitialized = false
    private var activeTtsLanguage: String? = null
    private var vadInitialized = false

    /** Whether we're using streaming STT (true) or offline fallback (false) */
    private var useStreamingStt = false

    // ── Model path resolution ────────────────────────────────

    private val modelsDir: File
        get() = File(context.filesDir, "models/sherpa-onnx")

    private fun whisperDir(): File = File(modelsDir, "whisper")
    private fun streamingDir(): File = File(modelsDir, "streaming")
    private fun piperDir(lang: String): File = File(modelsDir, "piper-$lang")

    /**
     * Resolve model file paths for the streaming STT model.
     * Supports transducer (encoder+decoder+joiner) and CTC (single model) layouts.
     */
    private fun resolveStreamingModelPaths(language: String): StreamingModelPaths? {
        val dir = streamingDir()
        if (!dir.exists()) {
            Timber.d("Streaming model directory not found: %s", dir.absolutePath)
            return null
        }

        val encoder = File(dir, "encoder.onnx")
        val decoder = File(dir, "decoder.onnx")
        val joiner = File(dir, "joiner.onnx")
        val tokens = File(dir, "tokens.txt")

        if (encoder.exists() && decoder.exists() && tokens.exists()) {
            return StreamingModelPaths(
                encoderPath = encoder.absolutePath,
                decoderPath = decoder.absolutePath,
                joinerPath = if (joiner.exists()) joiner.absolutePath else "",
                tokensPath = tokens.absolutePath,
                language = language
            )
        }

        // Check for single-model streaming (Zipformer2 CTC, NeMo CTC)
        val model = File(dir, "model.onnx")
        if (model.exists() && tokens.exists()) {
            return StreamingModelPaths(
                encoderPath = model.absolutePath,
                decoderPath = "",
                joinerPath = "",
                tokensPath = tokens.absolutePath,
                language = language
            )
        }

        Timber.w("No valid streaming STT model found in %s", dir.absolutePath)
        return null
    }

    /**
     * Resolve model file paths for the Whisper offline STT model.
     */
    private fun resolveSttModelPaths(language: String): SttModelPaths? {
        val dir = whisperDir()
        if (!dir.exists()) {
            Timber.w("Whisper model directory not found: %s", dir.absolutePath)
            return null
        }

        val encoder = File(dir, "encoder.onnx")
        val decoder = File(dir, "decoder.onnx")
        val tokens = File(dir, "tokens.txt")

        if (encoder.exists() && decoder.exists() && tokens.exists()) {
            return SttModelPaths(
                encoderPath = encoder.absolutePath,
                decoderPath = decoder.absolutePath,
                tokensPath = tokens.absolutePath,
                language = language
            )
        }

        val model = File(dir, "model.onnx")
        if (model.exists() && tokens.exists()) {
            return SttModelPaths(
                encoderPath = model.absolutePath,
                decoderPath = "",
                tokensPath = tokens.absolutePath,
                language = language
            )
        }

        Timber.w("No valid STT model found in %s", dir.absolutePath)
        return null
    }

    /**
     * Resolve model file paths for the Piper TTS model.
     */
    private fun resolveTtsModelPaths(language: String): TtsModelPaths? {
        val dir = piperDir(language)
        if (!dir.exists()) {
            Timber.w("Piper TTS directory not found for lang=%s: %s", language, dir.absolutePath)
            if (language != "sw") {
                val fallback = piperDir("sw")
                if (fallback.exists()) return resolveTtsModelPaths("sw")
            }
            return null
        }

        val model = File(dir, "model.onnx")
        val tokens = File(dir, "tokens.txt")
        val dataDir = File(dir, "espeak-ng-data")

        if (!model.exists()) {
            Timber.w("TTS model file not found: %s", model.absolutePath)
            return null
        }

        return TtsModelPaths(
            modelPath = model.absolutePath,
            tokensPath = if (tokens.exists()) tokens.absolutePath else "",
            dataDir = if (dataDir.exists()) dataDir.absolutePath else ""
        )
    }

    // ── Engine lifecycle ─────────────────────────────────────

    /**
     * Initialize the STT engine. Prefers streaming; falls back to offline.
     * Safe to call multiple times — no-ops if already initialized.
     */
    fun initializeStt(language: String = "sw"): Boolean {
        if (streamingSttInitialized || sttInitialized) return true

        // Try streaming first
        val streamingPaths = resolveStreamingModelPaths(language)
        if (streamingPaths != null) {
            streamingSttInitialized = streamingSttEngine.createRecognizer(
                encoderPath = streamingPaths.encoderPath,
                decoderPath = streamingPaths.decoderPath,
                joinerPath = streamingPaths.joinerPath,
                tokensPath = streamingPaths.tokensPath,
                language = streamingPaths.language,
                numThreads = 2
            )
            if (streamingSttInitialized) {
                useStreamingStt = true
                Timber.i("Streaming STT engine initialized — lang=%s", language)
                return true
            }
        }

        // Fallback to offline Whisper
        val offlinePaths = resolveSttModelPaths(language)
        if (offlinePaths != null) {
            sttInitialized = sherpaEngine.createRecognizer(
                encoderPath = offlinePaths.encoderPath,
                decoderPath = offlinePaths.decoderPath,
                tokensPath = offlinePaths.tokensPath,
                language = offlinePaths.language,
                numThreads = 2
            )
            if (sttInitialized) {
                useStreamingStt = false
                Timber.i("Offline STT engine initialized (fallback) — lang=%s", language)
                return true
            }
        }

        Timber.e("Cannot initialize STT — no models found")
        _voiceState.value = _voiceState.value.copy(error = "STT models not found. Please download them first.")
        return false
    }

    /**
     * Initialize the TTS engine for a given language.
     * Destroys previous TTS instance if language changed.
     */
    fun initializeTts(language: String = "sw"): Boolean {
        if (ttsInitialized && activeTtsLanguage == language) return true

        if (ttsInitialized) {
            sherpaEngine.destroySynthesizer()
            ttsInitialized = false
            activeTtsLanguage = null
        }

        val paths = resolveTtsModelPaths(language) ?: run {
            Timber.e("Cannot initialize TTS — model files not found for lang=%s", language)
            _voiceState.value = _voiceState.value.copy(error = "TTS models not found for $language")
            return false
        }

        ttsInitialized = sherpaEngine.createSynthesizer(
            modelPath = paths.modelPath,
            tokensPath = paths.tokensPath,
            dataDir = paths.dataDir,
            numThreads = 2
        )

        if (ttsInitialized) {
            activeTtsLanguage = language
            Timber.i("TTS engine initialized — lang=%s", language)
        } else {
            Timber.e("TTS engine initialization failed for lang=%s", language)
            _voiceState.value = _voiceState.value.copy(error = "Failed to initialize TTS for $language")
        }
        return ttsInitialized
    }

    /**
     * Release all native resources. Call when pipeline is no longer needed.
     */
    fun release() {
        stopRecording()
        streamingSttEngine.destroy()
        sherpaEngine.release()
        streamingSttInitialized = false
        sttInitialized = false
        ttsInitialized = false
        activeTtsLanguage = null
        useStreamingStt = false
        Timber.i("VoicePipeline released")
    }

    // ── Tool interface ───────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "listen"
        return when (action.lowercase()) {
            "listen" -> startListening(params["language"] ?: "auto")
            "stop" -> stopListening()
            "speak" -> speak(
                text = params["text"] ?: return ToolResult.error(name, "Text required", "MISSING_TEXT"),
                language = params["language"] ?: "sw"
            )
            "transcribe" -> {
                val audioPath = params["audio_path"]
                    ?: return ToolResult.error(name, "audio_path required", "MISSING_PATH")
                transcribeFile(audioPath, params["language"] ?: "auto")
            }
            "detect_language" -> {
                val text = params["text"]
                    ?: return ToolResult.error(name, "Text required", "MISSING_TEXT")
                val detection = languageDetector.detectLanguage(text)
                ToolResult.success(name, data = detection.toMap(), message = "Detected: ${detection.primary}")
            }
            "handle_codeswitch" -> {
                val text = params["text"]
                    ?: return ToolResult.error(name, "Text required", "MISSING_TEXT")
                val segments = codeSwitchHandler.segment(text)
                val data = segments.map { mapOf("text" to it.text, "language" to it.language, "confidence" to it.confidence) }
                ToolResult.success(name, data = data, message = "Found ${segments.size} language segment(s)")
            }
            "init_stt" -> {
                val lang = params["language"] ?: "sw"
                val ok = initializeStt(lang)
                ToolResult.success(name, data = mapOf("initialized" to ok, "language" to lang, "streaming" to useStreamingStt),
                    message = if (ok) "STT ready ($lang, streaming=$useStreamingStt)" else "STT init failed")
            }
            "init_tts" -> {
                val lang = params["language"] ?: "sw"
                val ok = initializeTts(lang)
                ToolResult.success(name, data = mapOf("initialized" to ok, "language" to lang),
                    message = if (ok) "TTS ready ($lang)" else "TTS init failed")
            }
            "status" -> {
                val sherpaStatus = sherpaEngine.getStatus()
                val streamingStatus = streamingSttEngine.getStatus()
                ToolResult.success(name, data = mapOf(
                    "stt_initialized" to (streamingSttInitialized || sttInitialized),
                    "streaming_stt" to useStreamingStt,
                    "tts_initialized" to ttsInitialized,
                    "active_tts_language" to (activeTtsLanguage ?: "none"),
                    "models_dir" to modelsDir.absolutePath,
                    "sherpa" to sherpaStatus,
                    "streaming" to streamingStatus
                ), message = "STT: ${if (streamingSttInitialized || sttInitialized) "ready (streaming=$useStreamingStt)" else "not initialized"}, TTS: ${if (ttsInitialized) "ready ($activeTtsLanguage)" else "not initialized"}")
            }
            else -> ToolResult.error(name, "Unknown action: $action. Valid: listen, stop, speak, transcribe, detect_language, handle_codeswitch, init_stt, init_tts, status", "INVALID_ACTION")
        }
    }

    // ── Recording ────────────────────────────────────────────

    /**
     * Start listening for voice input with streaming STT.
     * Shows partial transcription as the user speaks — no "wait for silence".
     *
     * When an endpoint is detected (silence after speech), the final text is returned.
     *
     * @param language Target language ("sw", "en", or "auto" for detection)
     */
    suspend fun startListening(language: String = "auto"): ToolResult = withContext(Dispatchers.IO) {
        try {
            if (isRecording) {
                return@withContext ToolResult.error(name, "Already listening", "ALREADY_LISTENING")
            }

            // Ensure STT is initialized
            val sttLang = if (language == "auto") "sw" else language
            if (!initializeStt(sttLang)) {
                return@withContext ToolResult.error(name, "STT engine not initialized. Download models first.", "STT_NOT_READY")
            }

            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                return@withContext ToolResult.error(name, "Failed to initialize audio recorder", "INIT_ERROR")
            }

            audioBuffer.reset()

            // Reset streaming recognizer for new utterance
            if (useStreamingStt) {
                streamingSttEngine.reset()
            }

            audioRecord?.startRecording()
            isRecording = true
            _voiceState.value = VoicePipelineState(isListening = true, isStreaming = useStreamingStt)

            Timber.d("Voice recording started (lang=%s, streaming=%b)", language, useStreamingStt)

            if (useStreamingStt) {
                // ── STREAMING PATH ──────────────────────────
                // Feed audio chunks to streaming recognizer and surface partial results
                listenWithStreaming(sampleRate, bufferSize)
            } else {
                // ── OFFLINE FALLBACK PATH ───────────────────
                // Original behavior: record until silence, then transcribe whole buffer
                listenWithOffline(sampleRate, bufferSize)
            }

        } catch (e: Exception) {
            Timber.e(e, "Voice recording/STT failed")
            stopRecording()
            _voiceState.value = VoicePipelineState(error = e.message)
            ToolResult.error(name, "Recording failed: ${e.message}", "RECORD_ERROR")
        }
    }

    /**
     * Streaming STT path: feed audio incrementally, show partial results.
     * Returns when an endpoint is detected (natural pause in speech).
     */
    private suspend fun listenWithStreaming(sampleRate: Int, bufferSize: Int): ToolResult {
        val buffer = ByteArray(StreamingSttEngine.CHUNK_SIZE_BYTES)
        var totalAudioBytes = 0

        while (isRecording) {
            val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
            if (bytesRead > 0) {
                audioBuffer.write(buffer, 0, bytesRead)
                totalAudioBytes += bytesRead

                // Feed audio chunk to streaming recognizer
                val chunk = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
                streamingSttEngine.acceptPcm16(chunk, sampleRate)

                // Decode and get partial result
                val partialResult = streamingSttEngine.decodeAndGetResult()
                val currentState = streamingSttEngine.state.value

                // Update UI with partial transcription
                if (currentState.partialText.isNotBlank()) {
                    _voiceState.value = _voiceState.value.copy(
                        partialText = currentState.partialText
                    )
                }

                // Check if endpoint detected (natural pause → utterance complete)
                if (currentState.isEndpoint) {
                    Timber.d("Streaming STT endpoint detected")
                    break
                }
            }
        }

        stopRecording()

        // Get final accumulated text
        val finalText = streamingSttEngine.getFinalizedText()
        _voiceState.value = _voiceState.value.copy(
            isListening = false,
            isProcessing = false,
            partialText = ""
        )

        if (finalText.isBlank()) {
            return ToolResult.success(name, message = "No speech recognized")
        }

        // Detect language if auto
        val detectedLanguage = languageDetector.detectLanguage(finalText).primary

        // Handle code-switching if detected
        val processedText = if (detectedLanguage == "mixed") {
            val segments = codeSwitchHandler.segment(finalText)
            codeSwitchHandler.normalize(segments)
        } else {
            finalText
        }

        Timber.i("Streaming STT result: '%s' (lang=%s)", processedText, detectedLanguage)

        return ToolResult.success(
            toolName = name,
            data = mapOf(
                "text" to processedText,
                "raw_text" to finalText,
                "language" to detectedLanguage,
                "duration_ms" to (totalAudioBytes / (sampleRate * 2) * 1000),
                "audio_bytes" to totalAudioBytes,
                "streaming" to true
            ),
            message = processedText
        )
    }

    /**
     * Offline fallback path: record until silence, then transcribe.
     * Used when streaming models are not available.
     */
    private suspend fun listenWithOffline(sampleRate: Int, bufferSize: Int): ToolResult {
        val buffer = ByteArray(bufferSize)
        var silenceCounter = 0
        val maxSilence = 50 // ~1 second of silence
        var speechDetected = false

        // Initialize Silero VAD if model is available
        val vadModelPath = File(modelsDir, "silero_vad/silero_vad.onnx")
        if (!vadInitialized && vadModelPath.exists()) {
            vadInitialized = vadEngine.createVad(vadModelPath.absolutePath)
            if (vadInitialized) Timber.i("Silero VAD initialized for voice pipeline")
        }

        while (isRecording) {
            val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
            if (bytesRead > 0) {
                audioBuffer.write(buffer, 0, bytesRead)

                if (vadInitialized) {
                    val floatSamples = pcm16ToFloat(buffer, bytesRead)
                    val isSpeech = vadEngine.processAudio(floatSamples)
                    if (isSpeech) {
                        speechDetected = true
                        silenceCounter = 0
                    } else if (speechDetected) {
                        silenceCounter++
                        if (silenceCounter > maxSilence) {
                            Timber.d("Voice activity ended (Silero VAD silence)")
                            break
                        }
                    }
                } else {
                    val amplitude = calculateRMSAmplitude(buffer, bytesRead)
                    if (amplitude > 500) {
                        speechDetected = true
                        silenceCounter = 0
                    } else if (speechDetected) {
                        silenceCounter++
                        if (silenceCounter > maxSilence) {
                            Timber.d("Voice activity ended (RMS silence)")
                            break
                        }
                    }
                }
            }
        }

        if (vadInitialized) vadEngine.reset()

        val audioData = audioBuffer.toByteArray()
        stopRecording()

        if (!speechDetected || audioData.isEmpty()) {
            return ToolResult.success(name, message = "No speech detected")
        }

        _voiceState.value = VoicePipelineState(isListening = false, isProcessing = true)

        val transcription = withContext(Dispatchers.Default) {
            sherpaEngine.recognizeFromPcm16(audioData, sampleRate)
        }

        _voiceState.value = VoicePipelineState(isListening = false, isProcessing = false)

        if (transcription.isBlank()) {
            return ToolResult.success(name, message = "No speech recognized")
        }

        val detectedLanguage = languageDetector.detectLanguage(transcription).primary
        val processedText = if (detectedLanguage == "mixed") {
            val segments = codeSwitchHandler.segment(transcription)
            codeSwitchHandler.normalize(segments)
        } else {
            transcription
        }

        Timber.i("Offline STT result: '%s' (lang=%s)", processedText, detectedLanguage)

        return ToolResult.success(
            toolName = name,
            data = mapOf(
                "text" to processedText,
                "raw_text" to transcription,
                "language" to detectedLanguage,
                "duration_ms" to (audioData.size / (sampleRate * 2) * 1000),
                "audio_bytes" to audioData.size,
                "streaming" to false
            ),
            message = processedText
        )
    }

    /**
     * Stop listening.
     */
    suspend fun stopListening(): ToolResult {
        stopRecording()
        _voiceState.value = VoicePipelineState(isListening = false)
        return ToolResult.success(name, message = "Stopped listening")
    }

    /**
     * Transcribe an audio file on disk.
     */
    suspend fun transcribeFile(filePath: String, language: String = "auto"): ToolResult = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                return@withContext ToolResult.error(name, "Audio file not found: $filePath", "FILE_NOT_FOUND")
            }

            val sttLang = if (language == "auto") "sw" else language
            if (!initializeStt(sttLang)) {
                return@withContext ToolResult.error(name, "STT engine not ready", "STT_NOT_READY")
            }

            val audioData = file.readBytes()
            if (audioData.isEmpty()) {
                return@withContext ToolResult.error(name, "Audio file is empty", "EMPTY_FILE")
            }

            _voiceState.value = VoicePipelineState(isProcessing = true)

            val transcription = if (useStreamingStt) {
                // Feed file audio through streaming recognizer
                withContext(Dispatchers.Default) {
                    streamingSttEngine.reset()
                    val chunkSize = StreamingSttEngine.CHUNK_SIZE_BYTES
                    var offset = 0
                    while (offset < audioData.size) {
                        val end = minOf(offset + chunkSize, audioData.size)
                        val chunk = audioData.copyOfRange(offset, end)
                        streamingSttEngine.acceptPcm16(chunk, 16000)
                        streamingSttEngine.decodeAndGetResult()
                        offset = end
                    }
                    streamingSttEngine.getFinalizedText()
                }
            } else {
                withContext(Dispatchers.Default) {
                    sherpaEngine.recognizeFromPcm16(audioData, 16000)
                }
            }

            _voiceState.value = VoicePipelineState(isProcessing = false)

            val detectedLanguage = if (language == "auto") {
                languageDetector.detectLanguage(transcription).primary
            } else {
                language
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "text" to transcription,
                    "language" to detectedLanguage,
                    "source" to filePath,
                    "audio_bytes" to audioData.size,
                    "streaming" to useStreamingStt
                ),
                message = transcription
            )
        } catch (e: Exception) {
            Timber.e(e, "File transcription failed")
            _voiceState.value = VoicePipelineState(error = e.message)
            ToolResult.error(name, "Transcription failed: ${e.message}", "TRANSCRIBE_ERROR")
        }
    }

    // ── TTS ──────────────────────────────────────────────────

    /**
     * Speak text using on-device Piper TTS.
     * Falls back to Android built-in TTS if Piper models are not available.
     */
    suspend fun speak(
        text: String,
        language: String = "sw",
        speed: Float = 1.0f
    ): ToolResult = withContext(Dispatchers.IO) {
        try {
            if (text.isBlank()) {
                return@withContext ToolResult.error(name, "No text to speak", "EMPTY_TEXT")
            }

            _voiceState.value = VoicePipelineState(isSpeaking = true)

            val detection = languageDetector.detectLanguage(text)
            val effectiveLang = if (detection.isCodeMixed) detection.primary else language

            val piperSuccess = speakWithPiper(text, effectiveLang, speed)

            if (!piperSuccess) {
                Timber.i("Piper TTS not available, falling back to Android TTS")
                speakWithAndroidTts(text, effectiveLang)
            }

            _voiceState.value = VoicePipelineState(isSpeaking = false)
            ToolResult.success(name, message = "Spoke: ${text.take(50)}...")
        } catch (e: Exception) {
            Timber.e(e, "TTS failed")
            _voiceState.value = VoicePipelineState(isSpeaking = false, error = e.message)
            ToolResult.error(name, "TTS failed: ${e.message}", "TTS_ERROR")
        }
    }

    private suspend fun speakWithPiper(text: String, language: String, speed: Float): Boolean {
        if (!initializeTts(language)) return false

        return try {
            val pcmData = withContext(Dispatchers.Default) {
                sherpaEngine.synthesizeToPcm16Bytes(text, sid = 0, speed = speed)
            }

            if (pcmData.isEmpty()) {
                Timber.w("Piper TTS returned empty audio")
                return false
            }

            playPcmAudio(pcmData, sampleRate = 22050)
            true
        } catch (e: Exception) {
            Timber.e(e, "Piper TTS playback failed")
            false
        }
    }

    private fun playPcmAudio(pcmData: ByteArray, sampleRate: Int) {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(bufferSize, pcmData.size))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(pcmData, 0, pcmData.size)
        track.play()

        val durationMs = (pcmData.size.toLong() / (sampleRate * 2)) * 1000
        Thread.sleep(durationMs + 100)

        track.stop()
        track.release()
    }

    private suspend fun speakWithAndroidTts(text: String, language: String) = withContext(Dispatchers.Main) {
        val tts = android.speech.tts.TextToSpeech(context) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                Timber.d("Android TTS initialized")
            }
        }

        val locale = when (language) {
            "sw" -> java.util.Locale("sw", "KE")
            "en" -> java.util.Locale("en", "KE")
            else -> java.util.Locale("sw", "KE")
        }
        tts.language = locale
        tts.setSpeechRate(0.9f)

        tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "msaidizi_tts")

        kotlinx.coroutines.delay(text.length * 80L)
        tts.shutdown()
    }

    // ── Audio utilities ──────────────────────────────────────

    private fun pcm16ToFloat(buffer: ByteArray, length: Int): FloatArray {
        val sampleCount = length / 2
        val floatArray = FloatArray(sampleCount)
        val shortBuffer = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            floatArray[i] = shortBuffer.getShort().toFloat() / 32768.0f
        }
        return floatArray
    }

    private fun calculateRMSAmplitude(buffer: ByteArray, length: Int): Double {
        var sum = 0.0
        val shortBuffer = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val sampleCount = length / 2
        for (i in 0 until sampleCount) {
            val sample = shortBuffer.getShort().toDouble()
            sum += sample * sample
        }
        return if (sampleCount > 0) kotlin.math.sqrt(sum / sampleCount) else 0.0
    }

    private fun stopRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Timber.w(e, "Error stopping recording")
        }
        audioRecord = null
    }
}

// ── Data classes ─────────────────────────────────────────────

data class VoicePipelineState(
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val isProcessing: Boolean = false,
    val isStreaming: Boolean = false,
    val partialText: String = "",
    val error: String? = null
)

private data class StreamingModelPaths(
    val encoderPath: String,
    val decoderPath: String,
    val joinerPath: String,
    val tokensPath: String,
    val language: String
)

private data class SttModelPaths(
    val encoderPath: String,
    val decoderPath: String,
    val tokensPath: String,
    val language: String
)

private data class TtsModelPaths(
    val modelPath: String,
    val tokensPath: String,
    val dataDir: String
)
