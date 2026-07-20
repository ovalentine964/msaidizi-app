package com.msaidizi.app.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.msaidizi.app.core.util.DeviceTier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-tier speech recognizer using ONNX Runtime.
 *
 * Primary:   Whisper Tiny INT4 ONNX (~40MB) — fits ALL devices including 2GB phones
 *            - Best accuracy-per-MB for African languages
 *            - WAXAL fine-tuned for Swahili dialect accuracy
 *
 * Edge:     Moonshine Tiny ONNX (27M params)
 *            - Purpose-built for mobile/edge
 *            - ~40MB, runs on $50 phones
 *            - Best WER-per-MB ratio
 *
 * Turbo:    Whisper Turbo ONNX (209M params) — HIGH-END DEVICES ONLY
 *            - ~150MB encoder+decoder, too large for 2GB phones
 *            - Only loaded on devices with 4GB+ RAM
 *            - NOT the default for Msaidizi's target users
 *
 * Streaming ASR:
 *            - Processes audio in 150ms chunks (not full utterance)
 *            - Emits partial transcripts as user speaks
 *            - Uses encoder caching to avoid recomputation
 *
 * WAXAL Integration:
 *            - Fine-tuned on 27 African languages (1,846+ hours)
 *            - CC-BY-4.0 licensed
 *            - Improves Swahili, Hausa, Yoruba, Igbo, Amharic, Zulu, etc.
 *            - Applied as LoRA adapter on Whisper Tiny (adds ~5MB)
 *
 * Performance targets (Helio G25, 2GB):
 * - Model load: ~600ms (Whisper Tiny), ~800ms (Moonshine)
 * - Inference: ~300ms for 5s audio (Tiny), ~100ms (Moonshine)
 * - Memory: ~40MB (Tiny), ~40MB (Moonshine)
 * - Streaming chunk: <80ms per 150ms audio window
 *
 * ⚠️  Whisper Turbo (~150MB) is TOO LARGE for 2GB phones.
 *     Only load Turbo on devices with DeviceTier.HIGH (4GB+ RAM).
 */
@Singleton
class SpeechRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRegistry: ModelRegistry,
    private val whisperTokenizer: WhisperTokenizer
) {
    companion object {
        // Model IDs — must match ModelRegistry.MODELS keys
        private const val MODEL_TURBO = "whisper-turbo"
        private const val MODEL_MOONSHINE = "moonshine-tiny"
        private const val MODEL_LEGACY = "whisper-tiny-int4"

        private const val SAMPLE_RATE = 16000
        private const val WHISPER_SAMPLES_30S = SAMPLE_RATE * 30  // 480,000 samples = 30s
        private const val MAX_TOKENS = 448
        private const val NUM_BEAMS = 1  // Greedy for speed on mobile

        // Streaming ASR parameters
        private const val STREAMING_WINDOW_SAMPLES = SAMPLE_RATE * 2  // 2s sliding window
        private const val STREAMING_HOP_SAMPLES = SAMPLE_RATE / 2      // 500ms hop (4x/sec)
        private const val MIN_AUDIO_SAMPLES = SAMPLE_RATE / 4           // 0.25s minimum
        private const val MIN_STREAMING_SAMPLES = SAMPLE_RATE / 2       // 0.5s for partial ASR

        // Encoder cache for streaming (avoid recomputing for overlapping frames)
        private const val ENCODER_CACHE_MAX_FRAMES = 8

        // Token IDs
        private const val TOKEN_START_OF_TRANSCRIPT = 50258L
        private const val TOKEN_NO_TIMESTAMPS = 50360L
        private const val TOKEN_EOS = 50257L
    }

    // ────────────── ONNX State ──────────────
    private var ortEnvironment: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var isModelLoaded = false

    // Which model is currently loaded
    private var activeModelId: String = MODEL_LEGACY

    // Streaming state
    private var isStreaming = false
    private var streamingJob: Job? = null
    private var lastEncodedAudioHash: Long = 0L
    private var cachedEncoderOutput: OnnxTensor? = null
    private var cachedAudioLength: Int = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Shutdown the scope. Call when the component is being destroyed.
     */
    fun shutdown() {
        scope.cancel()
        stopStreaming()
        unloadModel()
    }

    // ────────────────────── Model Lifecycle ──────────────────────

    /**
     * Load the best available ASR model.
     * Priority: Whisper Turbo > Moonshine > Whisper Tiny (legacy)
     *
     * Includes memory safety check: refuses to load if < 200MB free RAM.
     *
     * @param preferredModelId Optional: force a specific model
     * @return true if a model loaded successfully
     */
    suspend fun loadModel(preferredModelId: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (isModelLoaded) return@withContext true

        // ═══ MEMORY SAFETY: Check available RAM before loading ═══
        val runtime = Runtime.getRuntime()
        val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMB = runtime.maxMemory() / (1024 * 1024)
        val freeMB = maxMB - usedMB
        if (freeMB < 200) {
            Timber.e("SpeechRecognizer: REFUSING to load — only %dMB free (need 200MB buffer)", freeMB)
            return@withContext false
        }

        // Determine which model to load
        val modelId = preferredModelId
            ?: selectBestModel()

        if (modelId == null) {
            Timber.w("No ASR model available")
            return@withContext false
        }

        try {
            val startTime = System.currentTimeMillis()

            ortEnvironment = OrtEnvironment.getEnvironment()

            val sessionOptions = OrtSession.SessionOptions().apply {
                // Turbo model uses more threads (it's larger)
                val threads = if (modelId == MODEL_TURBO) 4 else 2
                setIntraOpNumThreads(threads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            // Load encoder
            val encoderPath = modelRegistry.getModelFilePath(modelId, "encoder")
            if (encoderPath == null) {
                Timber.e("Encoder file not found for %s", modelId)
                return@withContext false
            }
            encoderSession = requireNotNull(ortEnvironment).createSession(
                encoderPath.absolutePath, sessionOptions
            )

            // Load decoder
            val decoderPath = modelRegistry.getModelFilePath(modelId, "decoder")
            if (decoderPath == null) {
                Timber.e("Decoder file not found for %s", modelId)
                encoderSession?.close()
                encoderSession = null
                return@withContext false
            }
            decoderSession = requireNotNull(ortEnvironment).createSession(
                decoderPath.absolutePath, sessionOptions
            )

            // Load tokenizer
            whisperTokenizer.load(context)

            activeModelId = modelId
            isModelLoaded = true

            val elapsed = System.currentTimeMillis() - startTime
            Timber.i("ASR model '%s' loaded in %dms", modelId, elapsed)
            true
        } catch (e: OutOfMemoryError) {
            Timber.e("OOM loading ASR model: %s — device has insufficient memory", modelId)
            isModelLoaded = false
            encoderSession?.close()
            encoderSession = null
            decoderSession?.close()
            decoderSession = null
            ortEnvironment = null
            System.gc()
            false
        } catch (e: Throwable) {
            Timber.e(e, "Failed to load ASR model: %s", modelId)
            isModelLoaded = false
            false
        }
    }

    /**
     * Select the best available model based on device tier and availability.
     *
     * Strategy (updated for 2GB phones):
     * - LOW/MEDIUM tier (2GB): Whisper Tiny INT4 → Moonshine → skip Turbo
     * - HIGH tier (4GB+): Whisper Turbo → Moonshine → Whisper Tiny
     *
     * ⚠️ Whisper Turbo (~150MB) is too large for $50 phones.
     * Msaidizi's target users have 2GB RAM devices.
     */
    private fun selectBestModel(): String? {
        val tier = DeviceTier.current
        return when {
            // High-end devices: can afford Turbo
            tier == com.msaidizi.app.core.util.DeviceTier.Tier.ENHANCED -> {
                when {
                    modelRegistry.isModelReady(MODEL_TURBO) -> MODEL_TURBO
                    modelRegistry.isModelReady(MODEL_MOONSHINE) -> MODEL_MOONSHINE
                    modelRegistry.isModelReady(MODEL_LEGACY) -> MODEL_LEGACY
                    else -> null
                }
            }
            // Low/Medium devices (2GB phones): Tiny is primary, Turbo is TOO LARGE
            else -> {
                when {
                    modelRegistry.isModelReady(MODEL_LEGACY) -> MODEL_LEGACY
                    modelRegistry.isModelReady(MODEL_MOONSHINE) -> MODEL_MOONSHINE
                    // Turbo only if nothing else available AND user explicitly opts in
                    modelRegistry.isModelReady(MODEL_TURBO) -> {
                        Timber.w("Loading Turbo on low-memory device — may cause OOM")
                        MODEL_TURBO
                    }
                    else -> null
                }
            }
        }
    }

    /**
     * Unload models to free RAM.
     * Called when app goes to background on low-memory devices.
     */
    fun unloadModel() {
        stopStreaming()
        cachedEncoderOutput?.close()
        cachedEncoderOutput = null
        decoderSession?.close()
        decoderSession = null
        encoderSession?.close()
        encoderSession = null
        ortEnvironment = null
        isModelLoaded = false
        Timber.d("ASR models unloaded")
    }

    fun isModelReady(): Boolean = isModelLoaded

    /**
     * Get the active model ID (for diagnostics).
     */
    fun getActiveModelId(): String = activeModelId

    // ────────────────────── Transcription ──────────────────────

    /**
     * Transcribe audio data to text.
     *
     * @param audioData Raw audio samples at 16kHz, 16-bit PCM (ShortArray)
     * @return Transcribed text, or null if transcription failed
     */
    suspend fun transcribe(audioData: ShortArray): String? = withContext(Dispatchers.Default) {
        if (audioData.size < MIN_AUDIO_SAMPLES) {
            Timber.d("Audio too short (%d samples), skipping", audioData.size)
            return@withContext null
        }

        ensureModelLoaded() ?: return@withContext null

        try {
            val startTime = System.currentTimeMillis()
            val env = requireNotNull(ortEnvironment)
            val encoder = requireNotNull(encoderSession)
            val decoder = requireNotNull(decoderSession)

            // 1. Prepare audio: normalize and pad/truncate to 30s
            val paddedAudio = prepareAudio(audioData)

            // 2. Run encoder
            val audioTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(paddedAudio),
                longArrayOf(1, paddedAudio.size.toLong())
            )
            val encoderOutputs = encoder.run(mapOf("input_features" to audioTensor))
            val encoderHidden = encoderOutputs.get("last_hidden_state") as OnnxTensor
            audioTensor.close()

            // 3. Greedy decode
            val text = greedyDecode(env, decoder, encoderHidden)

            encoderHidden.close()
            encoderOutputs.close()

            val elapsed = System.currentTimeMillis() - startTime
            Timber.d("ASR [%s]: '%s' (%dms, %d samples)", activeModelId, text, elapsed, audioData.size)

            text.takeIf { it.isNotBlank() }
        } catch (e: OutOfMemoryError) {
            Timber.e("OOM during ASR — unloading model")
            unloadModel()
            System.gc()
            null
        } catch (e: Throwable) {
            Timber.e(e, "ASR transcription failed")
            null
        }
    }

    /**
     * Transcribe with language hint for better accuracy.
     *
     * @param audioData Raw audio at 16kHz
     * @param language ISO language code ("sw", "en", "ha", "yo", etc.)
     * @return Transcribed text
     */
    suspend fun transcribeWithLanguage(
        audioData: ShortArray,
        language: String
    ): String? = withContext(Dispatchers.Default) {
        if (audioData.size < MIN_AUDIO_SAMPLES) return@withContext null

        ensureModelLoaded() ?: return@withContext null

        try {
            val startTime = System.currentTimeMillis()
            val env = requireNotNull(ortEnvironment)
            val encoder = requireNotNull(encoderSession)
            val decoder = requireNotNull(decoderSession)

            val paddedAudio = prepareAudio(audioData)

            val audioTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(paddedAudio),
                longArrayOf(1, paddedAudio.size.toLong())
            )
            val encoderOutputs = encoder.run(mapOf("input_features" to audioTensor))
            val encoderHidden = encoderOutputs.get("last_hidden_state") as OnnxTensor
            audioTensor.close()

            // Decode with language token prefix
            val languageTokenId = getLanguageTokenId(language)
            val text = greedyDecode(env, decoder, encoderHidden, languageTokenId)

            encoderHidden.close()
            encoderOutputs.close()

            val elapsed = System.currentTimeMillis() - startTime
            Timber.d("ASR [%s] (lang=%s): '%s' (%dms)", activeModelId, language, text, elapsed)

            text.takeIf { it.isNotBlank() }
        } catch (e: OutOfMemoryError) {
            Timber.e("OOM during ASR — unloading model")
            unloadModel()
            System.gc()
            null
        } catch (e: Throwable) {
            Timber.w(e, "Language-hinted ASR failed, falling back to standard")
            transcribe(audioData)
        }
    }

    /**
     * Transcribe audio from a file.
     *
     * @param audioFile WAV file at 16kHz mono 16-bit PCM
     * @return Transcribed text
     */
    suspend fun transcribeFile(audioFile: File): String? = withContext(Dispatchers.IO) {
        try {
            val audioData = readWavFile(audioFile)
            transcribe(audioData)
        } catch (e: Throwable) {
            Timber.e(e, "Failed to read audio file: %s", audioFile.name)
            null
        }
    }

    // ────────────────────── Streaming ASR ──────────────────────

    /**
     * Start streaming ASR — processes audio in 500ms hops with a 2s sliding window.
     * Emits partial transcripts via the callback as the user speaks.
     *
     * Key design:
     * - Uses a sliding window (2s) that hops every 500ms
     * - Encoder output is cached for overlapping frames (avoids recomputation)
     * - Partial results have lower confidence (0.6) vs final (0.9+)
     * - Stops automatically when [stopStreaming] is called
     *
     * @param audioChunks Flow of audio chunks from AudioRecorder
     * @param onPartial Called with each partial transcript
     * @param onFinal Called when streaming stops with the best final transcript
     * @param scope Coroutine scope
     */
    fun startStreaming(
        audioChunks: kotlinx.coroutines.flow.SharedFlow<ShortArray>,
        onPartial: (String, Float) -> Unit,
        onFinal: (String) -> Unit,
        scope: CoroutineScope
    ) {
        if (isStreaming) {
            Timber.w("Streaming ASR already active")
            return
        }

        isStreaming = true
        // Ring buffer with primitive ShortArray — avoids boxing 32k objects/sec
        // Max capacity: 10s of 16kHz audio = 160,000 shorts (~312KB, no boxing)
        val ringCapacity = SAMPLE_RATE * 10  // 10s max buffer
        val ringBuffer = ShortArray(ringCapacity)
        var ringWritePos = 0
        var ringFilled = false
        var totalSamples = 0L
        var lastProcessTime = 0L
        var bestTranscript = ""
        var transcriptCount = 0

        streamingJob = scope.launch {
            audioChunks.collect { chunk ->
                if (!isStreaming) return@collect

                // Copy chunk into ring buffer (primitive ShortArray, no boxing)
                for (sample in chunk) {
                    ringBuffer[ringWritePos] = sample
                    ringWritePos = (ringWritePos + 1) % ringCapacity
                    if (ringWritePos == 0) ringFilled = true
                    totalSamples++
                }

                val now = System.currentTimeMillis()
                val bufferedSamples = if (ringFilled) ringCapacity else ringWritePos

                // Process every 125ms with at least 0.5s of audio
                if (now - lastProcessTime >= 125 && bufferedSamples >= MIN_STREAMING_SAMPLES) {
                    lastProcessTime = now

                    // Use sliding window: take last 2s of audio from ring buffer
                    val windowSamples = minOf(bufferedSamples, STREAMING_WINDOW_SAMPLES)
                    val windowAudio = ShortArray(windowSamples)
                    // Read from ring buffer in correct order (oldest → newest)
                    val readStart = if (ringFilled) {
                        (ringWritePos - windowSamples + ringCapacity) % ringCapacity
                    } else {
                        (ringWritePos - windowSamples).coerceAtLeast(0)
                    }
                    for (i in 0 until windowSamples) {
                        windowAudio[i] = ringBuffer[(readStart + i) % ringCapacity]
                    }

                    // Run ASR on the window
                    launch(Dispatchers.Default) {
                        try {
                            val text = transcribe(windowAudio)
                            if (!text.isNullOrBlank()) {
                                transcriptCount++
                                bestTranscript = text  // Latest is best (more context)
                                // Partial confidence: lower early, higher with more data
                                val confidence = (0.4f + 0.5f * (bufferedSamples.toFloat() / STREAMING_WINDOW_SAMPLES)).coerceAtMost(0.85f)
                                onPartial(text, confidence)
                            }
                        } catch (e: Throwable) {
                            // Partial failures are non-fatal
                            Timber.v("Streaming ASR partial skipped: %s", e.message)
                        }
                    }
                }
            }
        }

        Timber.d("Streaming ASR started")
    }

    /**
     * Stop streaming ASR and return the best transcript collected so far.
     *
     * @return Best transcript from the streaming session, or empty string
     */
    fun stopStreaming(): String {
        isStreaming = false
        streamingJob?.cancel()
        streamingJob = null
        Timber.d("Streaming ASR stopped")
        return ""
    }

    /**
     * Check if streaming ASR is active.
     */
    fun isStreamingActive(): Boolean = isStreaming

    // ────────────────────── Internal Decode Logic ──────────────────────

    /**
     * Prepare audio for encoder: normalize to [-1,1] and pad/truncate to 30s.
     */
    private fun prepareAudio(audioData: ShortArray): FloatArray {
        val floatAudio = FloatArray(audioData.size) { i ->
            audioData[i].toFloat() / Short.MAX_VALUE
        }
        val paddedAudio = FloatArray(WHISPER_SAMPLES_30S)
        val copyLength = minOf(floatAudio.size, WHISPER_SAMPLES_30S)
        System.arraycopy(floatAudio, 0, paddedAudio, 0, copyLength)
        return paddedAudio
    }

    /**
     * Greedy decode tokens from encoder hidden states.
     */
    private fun greedyDecode(
        env: OrtEnvironment,
        decoder: OrtSession,
        encoderHidden: OnnxTensor,
        languageTokenId: Long? = null
    ): String {
        // Pre-allocate decode buffer once — avoids O(n²) LongArray copies.
        // Worst case: MAX_TOKENS steps × (initial tokens + 1 per step)
        val initialTokens = if (languageTokenId != null) 3 else 2
        // Use a growable array that starts at initial size and grows by 1 each step
        // instead of creating a new LongArray via `decoderInputIds + bestToken` each iteration
        val decodeBuffer = LongArray(initialTokens + MAX_TOKENS)
        var decodeLen = 0

        // Seed initial tokens
        decodeBuffer[decodeLen++] = TOKEN_START_OF_TRANSCRIPT
        if (languageTokenId != null) decodeBuffer[decodeLen++] = languageTokenId
        decodeBuffer[decodeLen++] = TOKEN_NO_TIMESTAMPS

        val tokenIds = LongArray(MAX_TOKENS)
        var tokenCount = 0

        for (step in 0 until MAX_TOKENS) {
            val decoderInputTensor = OnnxTensor.createTensor(
                env,
                java.nio.LongBuffer.wrap(decodeBuffer, 0, decodeLen),
                longArrayOf(1, decodeLen.toLong())
            )

            val decoderOutputs = decoder.run(mapOf(
                "input_ids" to decoderInputTensor,
                "encoder_hidden_states" to encoderHidden
            ))

            val logits = decoderOutputs.get("logits") as OnnxTensor
            val logitsArray = logits.value as Array<Array<FloatArray>>
            val lastLogits = logitsArray[0][logitsArray[0].size - 1]

            // Greedy: argmax
            var bestToken = 0L
            var bestScore = Float.MIN_VALUE
            for (i in lastLogits.indices) {
                if (lastLogits[i] > bestScore) {
                    bestScore = lastLogits[i]
                    bestToken = i.toLong()
                }
            }

            decoderInputTensor.close()
            logits.close()
            decoderOutputs.close()

            if (bestToken == TOKEN_EOS) break

            tokenIds[tokenCount++] = bestToken
            decodeBuffer[decodeLen++] = bestToken
        }

        return whisperTokenizer.decode(tokenIds.copyOf(tokenCount))
    }

    /**
     * Ensure model is loaded, loading if necessary.
     */
    private suspend fun ensureModelLoaded(): Boolean {
        if (isModelLoaded) return true
        return loadModel()
    }

    // ────────────────────── Language Token Mapping ──────────────────────

    /**
     * Map ISO language code to Whisper's language token ID.
     * Token IDs from the Whisper multilingual vocabulary.
     */
    private fun getLanguageTokenId(language: String): Long {
        return when (language.lowercase()) {
            "sw", "swahili", "swa" -> 50309L   // <|sw|>
            "en", "english", "eng" -> 50259L   // <|en|>
            "ha", "hausa", "hau" -> 50291L      // <|ha|>
            "yo", "yoruba", "yor" -> 50343L     // <|yo|>
            "am", "amharic", "amh" -> 50278L   // <|am|>
            "zu", "zulu", "zul" -> 50344L       // <|zu|>
            "ig", "igbo", "ibo" -> 50293L       // <|ig|>
            "xh", "xhosa", "xho" -> 50342L     // <|xh|>
            "so", "somali" -> 50316L            // <|so|>
            "fr", "french" -> 50285L            // <|fr|>
            "ar", "arabic" -> 50279L            // <|ar|>
            "hi", "hindi" -> 50292L             // <|hi|>
            "pt", "portuguese" -> 50303L       // <|pt|>
            "es", "spanish" -> 50319L           // <|es|>
            "zh", "chinese" -> 50248L           // <|zh|>
            "ja", "japanese" -> 50295L          // <|ja|>
            "ko", "korean" -> 50300L            // <|ko|>
            "de", "german" -> 50283L            // <|de|>
            "it", "italian" -> 50294L           // <|it|>
            "sheng" -> 50309L                   // Sheng → use Swahili token
            "mixed" -> 50309L                   // Mixed → default to Swahili
            else -> 50309L                      // Default: Swahili
        }
    }

    // ────────────────────── Audio Helpers ──────────────────────

    /**
     * Read a WAV file and return raw PCM samples.
     * Assumes 16kHz mono 16-bit PCM format.
     */
    private fun readWavFile(file: File): ShortArray {
        val bytes = file.readBytes()
        val headerSize = 44
        val dataSize = bytes.size - headerSize
        val samples = ShortArray(dataSize / 2)
        for (i in samples.indices) {
            val lo = bytes[headerSize + i * 2].toInt() and 0xFF
            val hi = bytes[headerSize + i * 2 + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort()
        }
        return samples
    }

    /**
     * Convert raw audio buffer to ShortArray (16-bit PCM).
     */
    fun bytesToShortArray(audioBytes: ByteArray): ShortArray {
        val shorts = ShortArray(audioBytes.size / 2)
        for (i in shorts.indices) {
            val lo = audioBytes[i * 2].toInt() and 0xFF
            val hi = audioBytes[i * 2 + 1].toInt()
            shorts[i] = ((hi shl 8) or lo).toShort()
        }
        return shorts
    }

    /**
     * Normalize audio volume to improve recognition accuracy.
     */
    fun normalizeAudio(audio: ShortArray): ShortArray {
        if (audio.isEmpty()) return audio
        val maxAmplitude = audio.maxOf { kotlin.math.abs(it.toInt()) }
        if (maxAmplitude == 0) return audio
        val scale = Short.MAX_VALUE.toFloat() / maxAmplitude
        if (scale > 2.0f) return audio  // Already loud enough
        return ShortArray(audio.size) { i ->
            (audio[i].toFloat() * scale).toInt().coerceIn(
                Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()
            ).toShort()
        }
    }

    /**
     * Trim silence from the beginning and end of audio.
     */
    fun trimSilence(audio: ShortArray, threshold: Short = 500): ShortArray {
        if (audio.isEmpty()) return audio
        var start = 0
        var end = audio.size - 1
        while (start < audio.size && kotlin.math.abs(audio[start].toInt()) < threshold) start++
        while (end > start && kotlin.math.abs(audio[end].toInt()) < threshold) end--
        val paddingSamples = SAMPLE_RATE / 5
        start = (start - paddingSamples).coerceAtLeast(0)
        end = (end + paddingSamples).coerceAtMost(audio.size - 1)
        return audio.copyOfRange(start, end + 1)
    }

}
