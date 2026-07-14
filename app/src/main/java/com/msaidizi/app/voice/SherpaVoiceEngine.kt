package com.msaidizi.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.*
import com.msaidizi.app.core.util.DeviceTier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified voice engine powered by Sherpa-ONNX.
 *
 * Replaces the raw ONNX Runtime approach with sherpa-onnx's optimized
 * JNI bindings for all voice processing:
 *
 * - **ASR**: Whisper via sherpa-onnx (offline, multilingual)
 * - **TTS**: Piper via sherpa-onnx (offline, Swahili + English)
 * - **VAD**: Silero VAD via sherpa-onnx (voice activity detection)
 *
 * Why sherpa-onnx over raw ONNX Runtime:
 * 1. Optimized ARM NEON inference paths (2-3x faster)
 * 2. Built-in audio preprocessing (no manual mel-spectrogram code)
 * 3. Streaming ASR with proper endpoint detection
 * 4. VAD with internal RNN state management (no manual state juggling)
 * 5. TTS with built-in phonemizer support
 * 6. Active maintenance by k2-fsa team (next-gen Kaldi)
 *
 * Model memory footprint (approximate):
 * ┌──────────────────────────┬───────────┐
 * │ Component                │ RAM (MB)  │
 * ├──────────────────────────┼───────────┤
 * │ Whisper Tiny (ASR)       │ ~40       │
 * │ Piper Swahili (TTS)      │ ~25       │
 * │ Silero VAD               │ ~5        │
 * │ sherpa-onnx JNI overhead │ ~10       │
 * ├──────────────────────────┼───────────┤
 * │ Total (all loaded)       │ ~80       │
 * └──────────────────────────┴───────────┘
 *
 * On 2GB (BASIC) devices, models are loaded/unloaded with mutual exclusion
 * following the same strategy as the existing pipeline.
 */
@Singleton
class SherpaVoiceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRegistry: ModelRegistry
) {
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val TTS_DEFAULT_SPEED = 1.0f
    }

    // ────────────── Sherpa-ONNX Components ──────────────
    private var offlineRecognizer: OfflineRecognizer? = null
    private var onlineRecognizer: OnlineRecognizer? = null
    private var ttsEngine: OfflineTts? = null
    private var vadDetector: com.k2fsa.sherpa.onnx.VoiceActivityDetector? = null

    // ────────────── State ──────────────
    private var isAsrLoaded = false
    private var isTtsLoaded = false
    private var isVadLoaded = false
    private var isCurrentlySpeaking = false
    private var currentAudioTrack: AudioTrack? = null

    // Track which ASR model is active
    private var activeAsrModel: String = "none"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ════════════════════════════════════════════════════════════════════
    // ASR — Automatic Speech Recognition (Whisper via sherpa-onnx)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Load the ASR model (Whisper via sherpa-onnx).
     *
     * Sherpa-onnx handles all the complexity: mel-spectrogram extraction,
     * encoder/decoder orchestration, token decoding, and language tokens.
     *
     * @param language Language code for Whisper ("sw", "en", etc.)
     * @return true if model loaded successfully
     */
    suspend fun loadAsr(language: String = "sw"): Boolean = withContext(Dispatchers.IO) {
        if (isAsrLoaded) return@withContext true

        // Locate model files via ModelRegistry
        val encoderPath = modelRegistry.getModelFilePath("whisper-tiny-int4", "encoder")
        val decoderPath = modelRegistry.getModelFilePath("whisper-tiny-int4", "decoder")
        val tokensPath = modelRegistry.getModelFilePath("whisper-tiny-int4", "tokens")

        if (encoderPath == null || decoderPath == null || tokensPath == null) {
            Timber.w("SherpaVoiceEngine: Whisper model files not found — ASR unavailable")
            return@withContext false
        }

        try {
            val startTime = System.currentTimeMillis()

            val whisperConfig = WhisperModelConfig(
                encoder = encoderPath.absolutePath,
                decoder = decoderPath.absolutePath,
                language = language,
                task = "transcribe",
            )

            val modelConfig = OfflineModelConfig(
                whisper = whisperConfig,
                tokens = tokensPath.absolutePath,
                numThreads = if (DeviceTier.current >= DeviceTier.Tier.ENHANCED) 4 else 2,
                debug = false,
                provider = "cpu",
            )

            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = modelConfig,
            )

            offlineRecognizer = OfflineRecognizer(config)
            isAsrLoaded = true
            activeAsrModel = "whisper-tiny-int4"

            val elapsed = System.currentTimeMillis() - startTime
            Timber.i("SherpaVoiceEngine: ASR loaded in %dms (model=%s, lang=%s)",
                elapsed, activeAsrModel, language)
            true
        } catch (e: OutOfMemoryError) {
            Timber.e("SherpaVoiceEngine: OOM loading ASR")
            unloadAsr()
            System.gc()
            false
        } catch (e: Exception) {
            Timber.e(e, "SherpaVoiceEngine: Failed to load ASR")
            unloadAsr()
            false
        }
    }

    /**
     * Load streaming ASR model (for real-time transcription).
     * Uses sherpa-onnx's OnlineRecognizer with a streaming transducer model.
     *
     * Falls back to offline (simulated streaming) if no streaming model available.
     */
    suspend fun loadStreamingAsr(): Boolean = withContext(Dispatchers.IO) {
        if (onlineRecognizer != null) return@withContext true

        // Try to find streaming model files
        // For now, we use offline recognizer in simulated-streaming mode
        // (sherpa-onnx supports this via chunked processing)
        Timber.d("SherpaVoiceEngine: Streaming ASR uses offline recognizer in simulated mode")
        loadAsr()
    }

    /**
     * Transcribe audio data to text using sherpa-onnx.
     *
     * Sherpa-onnx handles ALL preprocessing internally:
     * - Audio normalization
     * - Mel-spectrogram extraction
     * - Encoder inference
     * - Greedy/beam search decoding
     * - Token-to-text conversion
     *
     * @param audioData Raw audio samples at 16kHz, 16-bit PCM (ShortArray)
     * @return Transcribed text, or null if transcription failed
     */
    suspend fun transcribe(audioData: ShortArray): String? = withContext(Dispatchers.Default) {
        if (!isAsrLoaded) {
            val loaded = loadAsr()
            if (!loaded) return@withContext null
        }

        if (audioData.size < SAMPLE_RATE / 4) {  // < 0.25s
            Timber.d("SherpaVoiceEngine: Audio too short (%d samples), skipping", audioData.size)
            return@withContext null
        }

        try {
            val startTime = System.currentTimeMillis()
            val recognizer = offlineRecognizer ?: return@withContext null

            // Convert ShortArray → FloatArray normalized to [-1, 1]
            val floatAudio = FloatArray(audioData.size) { i ->
                audioData[i].toFloat() / Short.MAX_VALUE
            }

            // Create stream, feed audio, decode, get result
            recognizer.createStream().use { stream ->
                stream.acceptWaveform(floatAudio, SAMPLE_RATE)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)

                val elapsed = System.currentTimeMillis() - startTime
                Timber.d("SherpaVoiceEngine ASR: '%s' (%dms, %d samples)",
                    result.text, elapsed, audioData.size)

                result.text.takeIf { it.isNotBlank() }
            }
        } catch (e: OutOfMemoryError) {
            Timber.e("SherpaVoiceEngine: OOM during ASR — unloading")
            unloadAsr()
            System.gc()
            null
        } catch (e: Exception) {
            Timber.e(e, "SherpaVoiceEngine: ASR transcription failed")
            null
        }
    }

    /**
     * Transcribe with language hint for better accuracy.
     */
    suspend fun transcribeWithLanguage(
        audioData: ShortArray,
        language: String
    ): String? {
        // sherpa-onnx Whisper handles language via config
        // For now, delegate to standard transcribe
        return transcribe(audioData)
    }

    /**
     * Unload ASR model to free memory.
     */
    fun unloadAsr() {
        offlineRecognizer?.close()
        offlineRecognizer = null
        onlineRecognizer?.close()
        onlineRecognizer = null
        isAsrLoaded = false
        activeAsrModel = "none"
        Timber.d("SherpaVoiceEngine: ASR unloaded")
    }

    fun isAsrReady(): Boolean = isAsrLoaded
    fun getActiveAsrModel(): String = activeAsrModel

    // ════════════════════════════════════════════════════════════════════
    // TTS — Text-to-Speech (Piper via sherpa-onnx)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Load the TTS model (Piper Swahili via sherpa-onnx).
     *
     * Sherpa-onnx handles phonemization and vocoding internally.
     * No need for manual phoneme mapping or espeak-ng integration.
     *
     * @return true if model loaded successfully
     */
    suspend fun loadTts(): Boolean = withContext(Dispatchers.IO) {
        if (isTtsLoaded) return@withContext true

        val modelFile = modelRegistry.getModelPath("piper-swahili")
        if (modelFile == null) {
            Timber.w("SherpaVoiceEngine: Piper TTS model not found")
            return@withContext false
        }

        try {
            val startTime = System.currentTimeMillis()

            // sherpa-onnx expects the model directory structure:
            // model.onnx, tokens.txt, etc.
            // For Piper, the model is a single .onnx file
            val vitsConfig = VitsModelConfig(
                model = modelFile.absolutePath,
                tokens = findTokensFile(modelFile) ?: "",
                dataDir = findEspeakDataDir() ?: "",
            )

            val modelConfig = TtsModelConfig(
                vits = vitsConfig,
                numThreads = if (DeviceTier.current >= DeviceTier.Tier.ENHANCED) 4 else 2,
                debug = false,
                provider = "cpu",
            )

            val config = TtsConfig(
                model = modelConfig,
                maxNumSentences = 1,
            )

            ttsEngine = OfflineTts(config)
            isTtsLoaded = true

            val elapsed = System.currentTimeMillis() - startTime
            Timber.i("SherpaVoiceEngine: TTS loaded in %dms (sampleRate=%d)",
                elapsed, ttsEngine?.getSampleRate() ?: 0)
            true
        } catch (e: OutOfMemoryError) {
            Timber.e("SherpaVoiceEngine: OOM loading TTS")
            unloadTts()
            System.gc()
            false
        } catch (e: Exception) {
            Timber.e(e, "SherpaVoiceEngine: Failed to load TTS")
            unloadTts()
            false
        }
    }

    /**
     * Speak text using sherpa-onnx TTS.
     *
     * @param text Text to speak
     * @param language Language code ("sw", "en")
     */
    suspend fun speak(text: String, language: String = "sw") = withContext(Dispatchers.Default) {
        if (!isTtsLoaded) {
            val loaded = loadTts()
            if (!loaded) {
                Timber.w("SherpaVoiceEngine: Cannot speak — TTS not loaded")
                return@withContext
            }
        }

        if (text.isBlank()) return@withContext

        isCurrentlySpeaking = true
        try {
            val engine = ttsEngine ?: return@withContext
            val startTime = System.currentTimeMillis()

            // Generate audio — sherpa-onnx handles phonemization + vocoding
            val audioData = engine.generate(text, sid = 0, speed = TTS_DEFAULT_SPEED)

            val inferenceTime = System.currentTimeMillis() - startTime
            Timber.d("SherpaVoiceEngine TTS: Generated %d samples (%.1fs) in %dms",
                audioData.samples.size,
                audioData.samples.size.toFloat() / audioData.sampleRate,
                inferenceTime)

            // Convert float32 [-1,1] → int16 PCM
            val pcmData = ShortArray(audioData.samples.size) { i ->
                (audioData.samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }

            // Play audio via AudioTrack
            playPcmAudio(pcmData, audioData.sampleRate)

        } catch (e: OutOfMemoryError) {
            Timber.e("SherpaVoiceEngine: OOM during TTS synthesis")
            unloadTts()
            System.gc()
        } catch (e: Exception) {
            Timber.e(e, "SherpaVoiceEngine: TTS synthesis error")
        } finally {
            isCurrentlySpeaking = false
        }
    }

    /**
     * Synthesize text to raw PCM samples without playing.
     * Used by STS pipeline and streaming TTS.
     */
    suspend fun synthesizeToPcm(text: String, language: String = "sw"): ShortArray =
        withContext(Dispatchers.Default) {
            if (!isTtsLoaded) {
                val loaded = loadTts()
                if (!loaded) return@withContext ShortArray(0)
            }
            if (text.isBlank()) return@withContext ShortArray(0)

            try {
                val engine = ttsEngine ?: return@withContext ShortArray(0)
                val audioData = engine.generate(text, sid = 0, speed = TTS_DEFAULT_SPEED)

                ShortArray(audioData.samples.size) { i ->
                    (audioData.samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                }
            } catch (e: Exception) {
                Timber.e(e, "SherpaVoiceEngine: TTS synthesizeToPcm error")
                ShortArray(0)
            }
        }

    /**
     * Stop current speech playback.
     */
    fun stopSpeaking() {
        currentAudioTrack?.let { track ->
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                }
                track.release()
            } catch (e: Exception) {
                Timber.w(e, "SherpaVoiceEngine: Error stopping AudioTrack")
            }
        }
        currentAudioTrack = null
        isCurrentlySpeaking = false
    }

    /**
     * Unload TTS model to free memory.
     */
    fun unloadTts() {
        stopSpeaking()
        ttsEngine?.close()
        ttsEngine = null
        isTtsLoaded = false
        Timber.d("SherpaVoiceEngine: TTS unloaded")
    }

    fun isTtsReady(): Boolean = isTtsLoaded
    fun isSpeaking(): Boolean = isCurrentlySpeaking

    // ════════════════════════════════════════════════════════════════════
    // VAD — Voice Activity Detection (Silero via sherpa-onnx)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Load Silero VAD model via sherpa-onnx.
     *
     * sherpa-onnx's VAD implementation handles RNN hidden state internally,
     * eliminating the manual state management required with raw ONNX Runtime.
     */
    suspend fun loadVad(): Boolean = withContext(Dispatchers.IO) {
        if (isVadLoaded) return@withContext true

        val modelFile = modelRegistry.getModelPath("silero-vad")
        if (modelFile == null) {
            Timber.w("SherpaVoiceEngine: Silero VAD model not found")
            return@withContext false
        }

        try {
            val startTime = System.currentTimeMillis()

            val sileroConfig = SileroVadModelConfig(
                model = modelFile.absolutePath,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                maxSpeechDuration = 30.0f,
            )

            val config = VadModelConfig(
                sileroVad = sileroConfig,
                numThreads = 1,
                debug = false,
            )

            vadDetector = com.k2fsa.sherpa.onnx.VoiceActivityDetector(config)
            isVadLoaded = true

            val elapsed = System.currentTimeMillis() - startTime
            Timber.i("SherpaVoiceEngine: VAD loaded in %dms", elapsed)
            true
        } catch (e: Exception) {
            Timber.e(e, "SherpaVoiceEngine: Failed to load VAD")
            isVadLoaded = false
            false
        }
    }

    /**
     * Process audio chunk through VAD.
     *
     * @param audioData Audio samples at 16kHz mono 16-bit PCM
     * @return true if speech is detected
     */
    fun processVadChunk(audioData: ShortArray): Boolean {
        val detector = vadDetector ?: return false

        // Convert ShortArray → FloatArray
        val floatAudio = FloatArray(audioData.size) { i ->
            audioData[i].toFloat() / Short.MAX_VALUE
        }

        detector.acceptWaveform(floatAudio)
        return detector.isSpeechDetected()
    }

    /**
     * Get accumulated speech segments from VAD.
     */
    fun getVadSegments(): List<SpeechSegment> {
        val detector = vadDetector ?: return emptyList()
        val segments = mutableListOf<SpeechSegment>()
        while (detector.isNotEmpty()) {
            segments.add(detector.pop())
        }
        return segments
    }

    /**
     * Reset VAD state.
     */
    fun resetVad() {
        vadDetector?.reset()
    }

    /**
     * Unload VAD model.
     */
    fun unloadVad() {
        vadDetector?.close()
        vadDetector = null
        isVadLoaded = false
        Timber.d("SherpaVoiceEngine: VAD unloaded")
    }

    fun isVadReady(): Boolean = isVadLoaded

    // ════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ════════════════════════════════════════════════════════════════════

    /**
     * Release all resources.
     */
    fun release() {
        unloadAsr()
        unloadTts()
        unloadVad()
        scope.cancel()
        Timber.d("SherpaVoiceEngine: All resources released")
    }

    /**
     * Get engine status for diagnostics.
     */
    fun getStatus(): Map<String, Any> = mapOf(
        "asrLoaded" to isAsrLoaded,
        "asrModel" to activeAsrModel,
        "ttsLoaded" to isTtsLoaded,
        "vadLoaded" to isVadLoaded,
        "speaking" to isCurrentlySpeaking,
        "deviceTier" to DeviceTier.current.name,
    )

    // ════════════════════════════════════════════════════════════════════
    // Audio Playback
    // ════════════════════════════════════════════════════════════════════

    /**
     * Play PCM audio through Android AudioTrack.
     */
    private suspend fun playPcmAudio(pcm: ShortArray, sampleRate: Int) {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val bufferSize = maxOf(minBufferSize, pcm.size * 2 * 4)

        val audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            bufferSize,
            AudioTrack.MODE_STREAM,
            android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        currentAudioTrack = audioTrack

        try {
            audioTrack.play()

            val chunkSize = minBufferSize.coerceAtMost(8192)
            var offset = 0
            while (offset < pcm.size && isCurrentlySpeaking) {
                val remaining = pcm.size - offset
                val writeSize = minOf(chunkSize / 2, remaining)
                audioTrack.write(pcm, offset, writeSize)
                offset += writeSize
            }

            while (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING
                && audioTrack.playbackHeadPosition < pcm.size
                && isCurrentlySpeaking
            ) {
                delay(20)
            }
        } catch (e: Exception) {
            Timber.e(e, "SherpaVoiceEngine: AudioTrack playback error")
        } finally {
            try {
                audioTrack.stop()
                audioTrack.release()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
            if (currentAudioTrack == audioTrack) {
                currentAudioTrack = null
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════

    /**
     * Find the tokens.txt file adjacent to the model.
     */
    private fun findTokensFile(modelFile: File): String? {
        val candidates = listOf(
            File(modelFile.parent, "tokens.txt"),
            File(modelFile.parent, "${modelFile.nameWithoutExtension}-tokens.txt"),
            File(modelFile.parent, "piper-tokens.txt"),
        )
        return candidates.firstOrNull { it.exists() }?.absolutePath
    }

    /**
     * Find espeak-ng-data directory for phonemization.
     */
    private fun findEspeakDataDir(): String? {
        val candidates = listOf(
            File(context.filesDir, "models/espeak-ng-data"),
            File(context.filesDir, "espeak-ng-data"),
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }?.absolutePath
    }
}
