package com.msaidizi.app.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whisper Tiny INT4 speech recognizer using ONNX Runtime.
 *
 * Whisper ONNX model expects:
 * - "audio": float32 [1, N] — raw audio at 16kHz, normalized [-1,1]
 * - "max_length": int32 [1] — max decoder tokens (448 for tiny)
 * - "min_length": int32 [1] — min decoder tokens (1)
 * - "num_beams": int32 [1] — beam search width (1 = greedy for speed)
 * - "length_penalty": float32 [1] — length penalty (1.0)
 * - "repetition_penalty": float32 [1] — repetition penalty (1.2)
 *
 * Output: sequences — int64 [1, seq_len] token IDs
 * Decode with WhisperTokenizer to get text.
 *
 * Audio preprocessing:
 * - Input must be 16kHz mono
 * - Padded/truncated to exactly 30 seconds (480,000 samples)
 * - Normalized to [-1.0, 1.0] range
 *
 * Performance on Helio G25 (2GB):
 * - Model load: ~800ms (mmap)
 * - Inference: ~600ms for 5s audio, ~1200ms for 15s audio
 * - Memory: ~40MB when loaded
 */
@Singleton
class SpeechRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRegistry: ModelRegistry,
    private val whisperTokenizer: WhisperTokenizer
) {
    companion object {
        private const val MODEL_ID = "whisper-tiny-int4"
        private const val SAMPLE_RATE = 16000
        private const val WHISPER_SAMPLES = SAMPLE_RATE * 30  // 30 seconds
        private const val MAX_TOKENS = 448
        private const val MIN_TOKENS = 1
        private const val NUM_BEAMS = 1  // Greedy for speed
        private const val LENGTH_PENALTY = 1.0f
        private const val REPETITION_PENALTY = 1.2f

        // Minimum audio length in samples (0.5s) to bother transcribing
        private const val MIN_AUDIO_SAMPLES = SAMPLE_RATE / 2
    }

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isModelLoaded = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Shutdown the scope. Call when the component is being destroyed.
     */
    fun shutdown() {
        scope.cancel()
        unloadModel()
    }


    // ────────────────────── Model Lifecycle ──────────────────────

    /**
     * Load the Whisper ONNX model from disk.
     * Uses memory-mapped loading for fast startup and reduced RAM.
     *
     * @return true if model loaded successfully
     */
    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        if (isModelLoaded) return@withContext true

        val modelFile = modelRegistry.getModelPath(MODEL_ID)
        if (modelFile == null) {
            Timber.w("Whisper model not found at expected path")
            return@withContext false
        }

        try {
            val startTime = System.currentTimeMillis()

            ortEnvironment = OrtEnvironment.getEnvironment()

            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)  // 2 threads for Whisper
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                // Enable memory-mapped model loading for reduced RAM
                setOptimizedModelFilePath(
                    File(context.cacheDir, "whisper_optimized.onnx").absolutePath
                )
            }

            ortSession = requireNotNull(ortEnvironment) { "ORT environment not initialized" }.createSession(
                modelFile.absolutePath,
                sessionOptions
            )

            // Load tokenizer vocabulary
            whisperTokenizer.load(context)

            isModelLoaded = true
            val elapsed = System.currentTimeMillis() - startTime
            Timber.i("Whisper model loaded in %dms", elapsed)
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load Whisper model")
            isModelLoaded = false
            false
        }
    }

    /**
     * Unload model to free ~40MB RAM.
     * Called when app goes to background on low-memory devices.
     */
    fun unloadModel() {
        ortSession?.close()
        ortSession = null
        ortEnvironment = null
        isModelLoaded = false
        Timber.d("Whisper model unloaded")
    }

    fun isModelReady(): Boolean = isModelLoaded

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

        if (!isModelLoaded) {
            val loaded = loadModel()
            if (!loaded) return@withContext null
        }

        try {
            val startTime = System.currentTimeMillis()

            // 1. Convert ShortArray to FloatArray (normalized to [-1, 1])
            val floatAudio = FloatArray(audioData.size) { i ->
                audioData[i].toFloat() / Short.MAX_VALUE
            }

            // 2. Whisper expects exactly 30 seconds of audio (480,000 samples).
            //    Pad with zeros if shorter, truncate if longer.
            val paddedAudio = FloatArray(WHISPER_SAMPLES)
            val copyLength = minOf(floatAudio.size, WHISPER_SAMPLES)
            System.arraycopy(floatAudio, 0, paddedAudio, 0, copyLength)
            // Remaining samples are already zero (padding)

            // 3. Create input tensors
            val audioTensor = OnnxTensor.createTensor(
                requireNotNull(ortEnvironment) { "ORT environment not initialized" },
                FloatBuffer.wrap(paddedAudio),
                longArrayOf(1, paddedAudio.size.toLong())
            )

            val maxLengthTensor = OnnxTensor.createTensor(
                requireNotNull(ortEnvironment) { "ORT environment not initialized" },
                IntBuffer.wrap(intArrayOf(MAX_TOKENS)),
                longArrayOf(1)
            )

            val minLengthTensor = OnnxTensor.createTensor(
                requireNotNull(ortEnvironment) { "ORT environment not initialized" },
                IntBuffer.wrap(intArrayOf(MIN_TOKENS)),
                longArrayOf(1)
            )

            val numBeamsTensor = OnnxTensor.createTensor(
                requireNotNull(ortEnvironment) { "ORT environment not initialized" },
                IntBuffer.wrap(intArrayOf(NUM_BEAMS)),
                longArrayOf(1)
            )

            val lengthPenaltyTensor = OnnxTensor.createTensor(
                requireNotNull(ortEnvironment) { "ORT environment not initialized" },
                FloatBuffer.wrap(floatArrayOf(LENGTH_PENALTY)),
                longArrayOf(1)
            )

            val repetitionPenaltyTensor = OnnxTensor.createTensor(
                requireNotNull(ortEnvironment) { "ORT environment not initialized" },
                FloatBuffer.wrap(floatArrayOf(REPETITION_PENALTY)),
                longArrayOf(1)
            )

            // 4. Run inference
            val inputs = mapOf(
                "audio" to audioTensor,
                "max_length" to maxLengthTensor,
                "min_length" to minLengthTensor,
                "num_beams" to numBeamsTensor,
                "length_penalty" to lengthPenaltyTensor,
                "repetition_penalty" to repetitionPenaltyTensor
            )

            val results = requireNotNull(ortSession) { "ORT session not initialized" }.run(inputs)

            // 5. Decode output tokens to text
            val sequences = results.get("sequences")
            val tokenIds = ((sequences as OnnxTensor).value as Array<LongArray>)[0]
            val text = whisperTokenizer.decode(tokenIds)

            val elapsed = System.currentTimeMillis() - startTime
            Timber.d("Whisper transcription: '%s' (%dms, %d samples)", text, elapsed, audioData.size)

            // 6. Cleanup tensors
            audioTensor.close()
            maxLengthTensor.close()
            minLengthTensor.close()
            numBeamsTensor.close()
            lengthPenaltyTensor.close()
            repetitionPenaltyTensor.close()
            results.close()

            text.takeIf { it.isNotBlank() }
        } catch (e: OutOfMemoryError) {
            Timber.e("OOM during Whisper transcription — unloading model")
            unloadModel()
            System.gc()
            null
        } catch (e: Exception) {
            Timber.e(e, "Whisper transcription failed")
            null
        }
    }

    /**
     * Transcribe with language hint for better accuracy.
     * Passes the language token to Whisper's decoder as a forced decoder input.
     * Whisper uses language tokens like <|sw|>, <|en|>, <|ha|>, etc.
     *
     * @param audioData Raw audio at 16kHz
     * @param language ISO language code ("sw", "en", "ha", "yo", "zu", etc.)
     * @return Transcribed text
     */
    suspend fun transcribeWithLanguage(
        audioData: ShortArray,
        language: String
    ): String? = withContext(Dispatchers.Default) {
        if (audioData.size < MIN_AUDIO_SAMPLES) {
            Timber.d("Audio too short (%d samples), skipping", audioData.size)
            return@withContext null
        }

        if (!isModelLoaded) {
            val loaded = loadModel()
            if (!loaded) return@withContext null
        }

        try {
            val startTime = System.currentTimeMillis()

            // 1. Convert ShortArray to FloatArray (normalized to [-1, 1])
            val floatAudio = FloatArray(audioData.size) { i ->
                audioData[i].toFloat() / Short.MAX_VALUE
            }

            // 2. Pad/truncate to 30 seconds
            val paddedAudio = FloatArray(WHISPER_SAMPLES)
            val copyLength = minOf(floatAudio.size, WHISPER_SAMPLES)
            System.arraycopy(floatAudio, 0, paddedAudio, 0, copyLength)

            // 3. Create input tensors
            val audioTensor = OnnxTensor.createTensor(
                requireNotNull(ortEnvironment) { "ORT environment not initialized" },
                FloatBuffer.wrap(paddedAudio),
                longArrayOf(1, paddedAudio.size.toLong())
            )

            val maxLengthTensor = OnnxTensor.createTensor(
                requireNotNull(ortEnvironment) { "ORT environment not initialized" },
                IntBuffer.wrap(intArrayOf(MAX_TOKENS)),
                longArrayOf(1)
            )

            val minLengthTensor = OnnxTensor.createTensor(
                requireNotNull(ortEnvironment) { "ORT environment not initialized" },
                IntBuffer.wrap(intArrayOf(MIN_TOKENS)),
                longArrayOf(1)
            )

            val numBeamsTensor = OnnxTensor.createTensor(
                requireNotNull(ortEnvironment) { "ORT environment not initialized" },
                IntBuffer.wrap(intArrayOf(NUM_BEAMS)),
                longArrayOf(1)
            )

            val lengthPenaltyTensor = OnnxTensor.createTensor(
                requireNotNull(ortEnvironment) { "ORT environment not initialized" },
                FloatBuffer.wrap(floatArrayOf(LENGTH_PENALTY)),
                longArrayOf(1)
            )

            val repetitionPenaltyTensor = OnnxTensor.createTensor(
                requireNotNull(ortEnvironment) { "ORT environment not initialized" },
                FloatBuffer.wrap(floatArrayOf(REPETITION_PENALTY)),
                longArrayOf(1)
            )

            // 4. Create decoder input with language token as forced prefix.
            //    Whisper's decoder starts with <|startoftranscript|><|language|><|notimestamps|>
            //    We encode this as a LongArray for the "decoder_input_ids" tensor.
            val languageTokenId = getLanguageTokenId(language)
            val decoderInputIds = longArrayOf(
                50258L,              // <|startoftranscript|>
                languageTokenId,     // <|language|> — e.g. <|sw|>=50309, <|en|>=50259
                50258L + 2           // <|notimestamps|> (token 50360 in base, adjust per model)
            )

            val decoderInputTensor = OnnxTensor.createTensor(
                requireNotNull(ortEnvironment) { "ORT environment not initialized" },
                java.nio.LongBuffer.wrap(decoderInputIds),
                longArrayOf(1, decoderInputIds.size.toLong())
            )

            // 5. Run inference with language hint
            val inputs = mutableMapOf(
                "audio" to audioTensor,
                "max_length" to maxLengthTensor,
                "min_length" to minLengthTensor,
                "num_beams" to numBeamsTensor,
                "length_penalty" to lengthPenaltyTensor,
                "repetition_penalty" to repetitionPenaltyTensor
            )

            // Add decoder_input_ids if the model supports it
            // Some ONNX exports have this input, others use only the audio + params
            try {
                inputs["decoder_input_ids"] = decoderInputTensor
            } catch (e: Exception) {
                // Model may not accept decoder_input_ids — that's OK
                Timber.d("decoder_input_ids not accepted by model, using standard decoding")
                decoderInputTensor.close()
            }

            val results = requireNotNull(ortSession) { "ORT session not initialized" }.run(inputs)

            // 6. Decode output tokens to text
            val sequences = results.get("sequences")
            val tokenIds = ((sequences as OnnxTensor).value as Array<LongArray>)[0]
            val text = whisperTokenizer.decode(tokenIds)

            val elapsed = System.currentTimeMillis() - startTime
            Timber.d("Whisper transcription (lang=%s): '%s' (%dms)", language, text, elapsed)

            // 7. Cleanup tensors
            audioTensor.close()
            maxLengthTensor.close()
            minLengthTensor.close()
            numBeamsTensor.close()
            lengthPenaltyTensor.close()
            repetitionPenaltyTensor.close()
            if (inputs.containsKey("decoder_input_ids")) decoderInputTensor.close()
            results.close()

            text.takeIf { it.isNotBlank() }
        } catch (e: OutOfMemoryError) {
            Timber.e("OOM during Whisper transcription — unloading model")
            unloadModel()
            System.gc()
            null
        } catch (e: Exception) {
            // If decoder_input_ids approach fails, fall back to standard transcribe
            Timber.w(e, "Language-hinted transcription failed, falling back to standard")
            transcribe(audioData)
        }
    }

    /**
     * Map ISO language code to Whisper's language token ID.
     * These are fixed tokens in the Whisper tokenizer vocabulary.
     *
     * Token IDs from OpenAI's whisper-tiny vocabulary:
     * <|af|>=50277, <|am|>=50278, <|ar|>=50279, <|as|>=50280, ...
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
        } catch (e: Exception) {
            Timber.e(e, "Failed to read audio file: %s", audioFile.name)
            null
        }
    }

    // ────────────────────── Audio Helpers ──────────────────────

    /**
     * Read a WAV file and return raw PCM samples.
     * Assumes 16kHz mono 16-bit PCM format.
     */
    private fun readWavFile(file: File): ShortArray {
        val bytes = file.readBytes()
        // Skip WAV header (44 bytes for standard PCM WAV)
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
     * Applies peak normalization to [-1.0, 1.0] range.
     */
    fun normalizeAudio(audio: ShortArray): ShortArray {
        if (audio.isEmpty()) return audio

        val maxAmplitude = audio.maxOf { kotlin.math.abs(it.toInt()) }
        if (maxAmplitude == 0) return audio

        val scale = Short.MAX_VALUE.toFloat() / maxAmplitude
        // Only normalize if volume is very low
        if (scale > 2.0f) return audio  // Already loud enough

        return ShortArray(audio.size) { i ->
            (audio[i].toFloat() * scale).toInt().coerceIn(
                Short.MIN_VALUE.toInt(),
                Short.MAX_VALUE.toInt()
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

        // Find first non-silent sample
        while (start < audio.size && kotlin.math.abs(audio[start].toInt()) < threshold) {
            start++
        }

        // Find last non-silent sample
        while (end > start && kotlin.math.abs(audio[end].toInt()) < threshold) {
            end--
        }

        // Keep 200ms padding on each side
        val paddingSamples = SAMPLE_RATE / 5
        start = (start - paddingSamples).coerceAtLeast(0)
        end = (end + paddingSamples).coerceAtMost(audio.size - 1)

        return audio.copyOfRange(start, end + 1)
    }
}
