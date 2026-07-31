package com.msaidizi.agent.tools.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.msaidizi.voice.SherpaOnnxEngine
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

/**
 * VoicePipeline — Full on-device Speech-to-Text and Text-to-Speech.
 *
 * Integrates with [SherpaOnnxEngine] for on-device STT (Whisper ONNX)
 * and Piper ONNX TTS. All processing is fully offline — no network calls.
 *
 * Supported languages:
 * - Swahili (sw) — primary
 * - English (en) — secondary
 * - Auto-detect — uses [LanguageDetector] for post-STT language identification
 *
 * Model layout (expected under app's filesDir or assets):
 *   models/
 *     sherpa-onnx/
 *       whisper/
 *         encoder.onnx
 *         decoder.onnx
 *         tokens.txt
 *       piper-sw/
 *         model.onnx
 *         tokens.txt
 *         espeak-ng-data/
 *       piper-en/
 *         model.onnx
 *         tokens.txt
 *         espeak-ng-data/
 */
@Singleton
class VoicePipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sherpaEngine: SherpaOnnxEngine,
    private val languageDetector: LanguageDetector,
    private val codeSwitchHandler: CodeSwitchHandler,
    private val vadEngine: VadEngine
) : Tool {

    override val name = "voice_pipeline"
    override val description = "Voice input/output: speech-to-text and text-to-speech (fully on-device via sherpa-onnx)"

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
    private var ttsInitialized = false
    private var activeTtsLanguage: String? = null
    private var vadInitialized = false

    // ── Model path resolution ────────────────────────────────

    private val modelsDir: File
        get() = File(context.filesDir, "models/sherpa-onnx")

    private fun whisperDir(): File = File(modelsDir, "whisper")
    private fun piperDir(lang: String): File = File(modelsDir, "piper-$lang")

    /**
     * Resolve model file paths for the Whisper STT model.
     * Supports both Whisper (encoder+decoder) and streaming (single model) layouts.
     */
    private fun resolveSttModelPaths(language: String): SttModelPaths? {
        val dir = whisperDir()
        if (!dir.exists()) {
            Timber.w("Whisper model directory not found: %s", dir.absolutePath)
            return null
        }

        // Check for encoder/decoder layout (Whisper)
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

        // Check for single-model layout (Paraformer, SenseVoice, etc.)
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
            // Fallback to Swahili if requested language not available
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
     * Initialize the STT engine. Call once before first recognition.
     * Safe to call multiple times — no-ops if already initialized.
     */
    fun initializeStt(language: String = "sw"): Boolean {
        if (sttInitialized) return true

        val paths = resolveSttModelPaths(language) ?: run {
            Timber.e("Cannot initialize STT — model files not found")
            _voiceState.value = _voiceState.value.copy(error = "STT models not found. Please download them first.")
            return false
        }

        sttInitialized = sherpaEngine.createRecognizer(
            encoderPath = paths.encoderPath,
            decoderPath = paths.decoderPath,
            tokensPath = paths.tokensPath,
            language = paths.language,
            numThreads = 2
        )

        if (sttInitialized) {
            Timber.i("STT engine initialized — lang=%s", language)
        } else {
            Timber.e("STT engine initialization failed")
            _voiceState.value = _voiceState.value.copy(error = "Failed to initialize speech recognition")
        }
        return sttInitialized
    }

    /**
     * Initialize the TTS engine for a given language.
     * Destroys previous TTS instance if language changed.
     */
    fun initializeTts(language: String = "sw"): Boolean {
        if (ttsInitialized && activeTtsLanguage == language) return true

        // Destroy existing if language changed
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
        sherpaEngine.release()
        sttInitialized = false
        ttsInitialized = false
        activeTtsLanguage = null
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
                ToolResult.success(name, data = mapOf("initialized" to ok, "language" to lang),
                    message = if (ok) "STT ready ($lang)" else "STT init failed")
            }
            "init_tts" -> {
                val lang = params["language"] ?: "sw"
                val ok = initializeTts(lang)
                ToolResult.success(name, data = mapOf("initialized" to ok, "language" to lang),
                    message = if (ok) "TTS ready ($lang)" else "TTS init failed")
            }
            "status" -> {
                val sherpaStatus = sherpaEngine.getStatus()
                ToolResult.success(name, data = mapOf(
                    "stt_initialized" to sttInitialized,
                    "tts_initialized" to ttsInitialized,
                    "active_tts_language" to (activeTtsLanguage ?: "none"),
                    "models_dir" to modelsDir.absolutePath,
                    "sherpa" to sherpaStatus
                ), message = "STT: ${if (sttInitialized) "ready" else "not initialized"}, TTS: ${if (ttsInitialized) "ready ($activeTtsLanguage)" else "not initialized"}")
            }
            else -> ToolResult.error(name, "Unknown action: $action. Valid: listen, stop, speak, transcribe, detect_language, handle_codeswitch, init_stt, init_tts, status", "INVALID_ACTION")
        }
    }

    // ── Recording ────────────────────────────────────────────

    /**
     * Start listening for voice input, perform STT when speech ends (VAD).
     * Returns the transcribed text.
     *
     * @param language Target language ("sw", "en", or "auto" for detection)
     */
    suspend fun startListening(language: String = "auto"): ToolResult = withContext(Dispatchers.IO) {
        try {
            if (isRecording) {
                return@withContext ToolResult.error(name, "Already listening", "ALREADY_LISTENING")
            }

            // Ensure STT is initialized
            val sttLang = if (language == "auto") "sw" else language // Whisper multilingual handles auto
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
            audioRecord?.startRecording()
            isRecording = true
            _voiceState.value = VoicePipelineState(isListening = true)

            Timber.d("Voice recording started (lang=%s)", language)

            // Read audio data with Silero VAD (or fallback to RMS-based)
            val buffer = ByteArray(bufferSize)
            var silenceCounter = 0
            val maxSilence = 50 // ~1 second of silence (at 20ms per read)
            var speechDetected = false

            // Initialize Silero VAD if model is available
            val vadModelPath = File(modelsDir, "silero_vad/silero_vad.onnx")
            if (!vadInitialized && vadModelPath.exists()) {
                vadInitialized = vadEngine.createVad(vadModelPath.absolutePath)
                if (vadInitialized) {
                    Timber.i("Silero VAD initialized for voice pipeline")
                }
            }

            while (isRecording) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (bytesRead > 0) {
                    audioBuffer.write(buffer, 0, bytesRead)

                    if (vadInitialized) {
                        // Use Silero VAD: convert PCM16 bytes to float samples
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
                        // Fallback: naive RMS-based VAD
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

            // Reset VAD state for next recording
            if (vadInitialized) {
                vadEngine.reset()
            }

            val audioData = audioBuffer.toByteArray()
            stopRecording()

            if (!speechDetected || audioData.isEmpty()) {
                return@withContext ToolResult.success(name, message = "No speech detected")
            }

            _voiceState.value = VoicePipelineState(isListening = false, isProcessing = true)

            // Perform STT via sherpa-onnx
            val transcription = withContext(Dispatchers.Default) {
                sherpaEngine.recognizeFromPcm16(audioData, sampleRate)
            }

            _voiceState.value = VoicePipelineState(isListening = false, isProcessing = false)

            if (transcription.isBlank()) {
                return@withContext ToolResult.success(name, message = "No speech recognized")
            }

            // Detect language if auto
            val detectedLanguage = if (language == "auto") {
                languageDetector.detectLanguage(transcription).primary
            } else {
                language
            }

            // Handle code-switching if detected
            val processedText = if (detectedLanguage == "mixed") {
                val segments = codeSwitchHandler.segment(transcription)
                codeSwitchHandler.normalize(segments)
            } else {
                transcription
            }

            Timber.i("STT result: '%s' (lang=%s)", processedText, detectedLanguage)

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "text" to processedText,
                    "raw_text" to transcription,
                    "language" to detectedLanguage,
                    "duration_ms" to (audioData.size / (sampleRate * 2) * 1000),
                    "audio_bytes" to audioData.size
                ),
                message = processedText
            )
        } catch (e: Exception) {
            Timber.e(e, "Voice recording/STT failed")
            stopRecording()
            _voiceState.value = VoicePipelineState(error = e.message)
            ToolResult.error(name, "Recording failed: ${e.message}", "RECORD_ERROR")
        }
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

            // Read raw PCM file (assumed 16-bit LE, 16kHz mono)
            val audioData = file.readBytes()
            if (audioData.isEmpty()) {
                return@withContext ToolResult.error(name, "Audio file is empty", "EMPTY_FILE")
            }

            _voiceState.value = VoicePipelineState(isProcessing = true)

            val transcription = withContext(Dispatchers.Default) {
                sherpaEngine.recognizeFromPcm16(audioData, 16000)
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
                    "audio_bytes" to audioData.size
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
     *
     * @param text Text to speak
     * @param language Language code ("sw", "en")
     * @param speed Speech rate multiplier (0.5–2.0, default 1.0)
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

            // Handle code-switched text: may need to speak segments in different languages
            val detection = languageDetector.detectLanguage(text)
            val effectiveLang = if (detection.isCodeMixed) {
                // For code-mixed text, use primary language
                detection.primary
            } else {
                language
            }

            // Try Piper TTS first
            val piperSuccess = speakWithPiper(text, effectiveLang, speed)

            if (!piperSuccess) {
                // Fallback to Android built-in TTS
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

    /**
     * Speak using on-device Piper TTS via sherpa-onnx.
     * @return true if successful, false if models not available
     */
    private suspend fun speakWithPiper(text: String, language: String, speed: Float): Boolean {
        if (!initializeTts(language)) return false

        return try {
            // Generate audio
            val pcmData = withContext(Dispatchers.Default) {
                sherpaEngine.synthesizeToPcm16(text, sid = 0, speed = speed)
            }

            if (pcmData.isEmpty()) {
                Timber.w("Piper TTS returned empty audio")
                return false
            }

            // Play via AudioTrack
            playPcmAudio(pcmData, sampleRate = 22050)
            true
        } catch (e: Exception) {
            Timber.e(e, "Piper TTS playback failed")
            false
        }
    }

    /**
     * Play PCM 16-bit audio through AudioTrack.
     */
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

        // Wait for playback to complete
        val durationMs = (pcmData.size.toLong() / (sampleRate * 2)) * 1000
        Thread.sleep(durationMs + 100) // Small buffer

        track.stop()
        track.release()
    }

    /**
     * Fallback: speak using Android's built-in TTS engine.
     */
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

        // Wait for completion estimate
        kotlinx.coroutines.delay(text.length * 80L)
        tts.shutdown()
    }

    // ── Audio utilities ──────────────────────────────────────

    /**
     * Convert PCM 16-bit LE bytes to float array normalized to [-1, 1].
     * Used by Silero VAD which expects float input.
     */
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
    val partialText: String = "",
    val error: String? = null
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
