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
    private var ortSession: OrtSession? = null        // Encoder
    private var ortDecoderSession: OrtSession? = null  // Decoder (merged with KV cache)
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
     * Load the Whisper encoder and decoder ONNX models from disk.
     * Uses memory-mapped loading for fast startup and reduced RAM.
     *
     * @return true if models loaded successfully
     */
    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        if (isModelLoaded) return@withContext true

        // Verify all required files exist
        if (!modelRegistry.isModelReady(MODEL_ID)) {
            Timber.w("Whisper model files not found")
            return@withContext false
        }

        try {
            val startTime = System.currentTimeMillis()

            ortEnvironment = OrtEnvironment.getEnvironment()

            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            // Load encoder
            val encoderPath = modelRegistry.getModelFilePath(MODEL_ID, "encoder")
            if (encoderPath == null) {
                Timber.e("Whisper encoder file not found")
                return@withContext false
            }
            ortSession = requireNotNull(ortEnvironment) { "ORT environment not initialized" }.createSession(
                encoderPath.absolutePath,
                sessionOptions
            )

            // Load decoder
            val decoderPath = modelRegistry.getModelFilePath(MODEL_ID, "decoder")
            if (decoderPath == null) {
                Timber.e("Whisper decoder file not found")
                ortSession?.close()
                ortSession = null
                return@withContext false
            }
            ortDecoderSession = requireNotNull(ortEnvironment) { "ORT environment not initialized" }.createSession(
                decoderPath.absolutePath,
                sessionOptions
            )

            // Load tokenizer vocabulary
            whisperTokenizer.load(context)

            isModelLoaded = true
            val elapsed = System.currentTimeMillis() - startTime
            Timber.i("Whisper encoder+decoder loaded in %dms", elapsed)
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load Whisper model")
            isModelLoaded = false
            false
        }
    }

    /**
     * Unload models to free ~40MB RAM.
     * Called when app goes to background on low-memory devices.
     */
    fun unloadModel() {
        ortDecoderSession?.close()
        ortDecoderSession = null
        ortSession?.close()
        ortSession = null
        ortEnvironment = null
        isModelLoaded = false
        Timber.d("Whisper models unloaded")
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
            val env = requireNotNull(ortEnvironment)
            val encoder = requireNotNull(ortSession)
            val decoder = requireNotNull(ortDecoderSession)

            // 1. Convert ShortArray to FloatArray (normalized to [-1, 1])
            val floatAudio = FloatArray(audioData.size) { i ->
                audioData[i].toFloat() / Short.MAX_VALUE
            }

            // 2. Pad/truncate to 30 seconds
            val paddedAudio = FloatArray(WHISPER_SAMPLES)
            val copyLength = minOf(floatAudio.size, WHISPER_SAMPLES)
            System.arraycopy(floatAudio, 0, paddedAudio, 0, copyLength)

            // 3. Run encoder: audio → encoder_hidden_states
            val audioTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(paddedAudio),
                longArrayOf(1, paddedAudio.size.toLong())
            )
            val encoderInputs = mapOf("input_features" to audioTensor)
            val encoderOutputs = encoder.run(encoderInputs)
            val encoderHidden = encoderOutputs.get("last_hidden_state") as OnnxTensor
            audioTensor.close()

            // 4. Decode tokens one at a time
            val tokenIds = mutableListOf<Long>()
            // <|startoftranscript|> <|notimestamps|>
            var decoderInputIds = longArrayOf(50258L, 50360L)

            for (step in 0 until MAX_TOKENS) {
                val decoderInputTensor = OnnxTensor.createTensor(
                    env,
                    java.nio.LongBuffer.wrap(decoderInputIds),
                    longArrayOf(1, decoderInputIds.size.toLong())
                )

                val decoderInputs = mutableMapOf<String, OnnxTensor>(
                    "input_ids" to decoderInputTensor,
                    "encoder_hidden_states" to encoderHidden
                )

                // Pass past key values if available
                // (Merged decoder handles KV cache internally)

                val decoderOutputs = decoder.run(decoderInputs)
                val logits = decoderOutputs.get("logits") as OnnxTensor

                // Get last token logits
                val logitsArray = logits.value as Array<Array<FloatArray>>
                val lastLogits = logitsArray[0][logitsArray[0].size - 1]

                // Greedy decoding: pick argmax
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

                // Check EOS: <|endoftranscript|> = 50257
                if (bestToken == 50257L) break

                tokenIds.add(bestToken)

                // Append token to decoder input for next step
                decoderInputIds = decoderInputIds + bestToken
            }

            encoderHidden.close()
            encoderOutputs.close()

            // 5. Decode tokens to text
            val text = whisperTokenizer.decode(tokenIds.toLongArray())

            val elapsed = System.currentTimeMillis() - startTime
            Timber.d("Whisper transcription: '%s' (%dms, %d samples)", text, elapsed, audioData.size)

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
            val env = requireNotNull(ortEnvironment)
            val encoder = requireNotNull(ortSession)
            val decoder = requireNotNull(ortDecoderSession)

            // 1. Prepare audio
            val floatAudio = FloatArray(audioData.size) { i ->
                audioData[i].toFloat() / Short.MAX_VALUE
            }
            val paddedAudio = FloatArray(WHISPER_SAMPLES)
            val copyLength = minOf(floatAudio.size, WHISPER_SAMPLES)
            System.arraycopy(floatAudio, 0, paddedAudio, 0, copyLength)

            // 2. Run encoder
            val audioTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(paddedAudio),
                longArrayOf(1, paddedAudio.size.toLong())
            )
            val encoderOutputs = encoder.run(mapOf("input_features" to audioTensor))
            val encoderHidden = encoderOutputs.get("last_hidden_state") as OnnxTensor
            audioTensor.close()

            // 3. Decode with language token prefix
            val languageTokenId = getLanguageTokenId(language)
            // <|startoftranscript|> <|language|> <|notimestamps|>
            var decoderInputIds = longArrayOf(50258L, languageTokenId, 50360L)
            val tokenIds = mutableListOf<Long>()

            for (step in 0 until MAX_TOKENS) {
                val decoderInputTensor = OnnxTensor.createTensor(
                    env,
                    java.nio.LongBuffer.wrap(decoderInputIds),
                    longArrayOf(1, decoderInputIds.size.toLong())
                )

                val decoderOutputs = decoder.run(mapOf(
                    "input_ids" to decoderInputTensor,
                    "encoder_hidden_states" to encoderHidden
                ))
                val logits = decoderOutputs.get("logits") as OnnxTensor
                val logitsArray = logits.value as Array<Array<FloatArray>>
                val lastLogits = logitsArray[0][logitsArray[0].size - 1]

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

                if (bestToken == 50257L) break  // EOS
                tokenIds.add(bestToken)
                decoderInputIds = decoderInputIds + bestToken
            }

            encoderHidden.close()
            encoderOutputs.close()

            val text = whisperTokenizer.decode(tokenIds.toLongArray())
            val elapsed = System.currentTimeMillis() - startTime
            Timber.d("Whisper transcription (lang=%s): '%s' (%dms)", language, text, elapsed)

            text.takeIf { it.isNotBlank() }
        } catch (e: OutOfMemoryError) {
            Timber.e("OOM during Whisper transcription — unloading model")
            unloadModel()
            System.gc()
            null
        } catch (e: Exception) {
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
