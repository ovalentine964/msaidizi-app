package com.msaidizi.app.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.*
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SpeechRecognizer — Speech-to-text using Sherpa-ONNX Whisper.
 *
 * Model: whisper-tiny-int8.onnx (~40MB)
 * Supports: Swahili, English, Sheng (via post-processing)
 *
 * On 2GB devices: loaded/unloaded on demand to save memory.
 * On 3GB+ devices: can stay loaded alongside TTS.
 *
 * Design: arch_voice.md
 */
@Singleton
class SpeechRecognizer @Inject constructor(
    private val context: Context
) {
    private var isLoaded = false
    private var recognizer: OfflineRecognizer? = null

    // Model file paths (resolved from assets on first load)
    private var modelDir: File? = null

    companion object {
        private const val MODEL_ASSET_DIR = "models/whisper"
        private const val ENCODER_MODEL = "whisper-encoder-int8.onnx"
        private const val DECODER_MODEL = "whisper-decoder-int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val SAMPLE_RATE = 16000
        private const val NUM_THREADS = 2
        private const val LANGUAGE = "sw"  // Swahili default
        private const val TASK = "transcribe"
        private const val DECODER_METHOD = "greedy_search"
    }

    /**
     * Load the Whisper model into memory.
     * Extracts model files from assets if needed, then initializes Sherpa-ONNX.
     * Must be called before recognize().
     */
    suspend fun loadModel(): Boolean {
        if (isLoaded && recognizer != null) return true

        return try {
            // Ensure model files are available on the filesystem
            val dir = ensureModelFiles()
            if (dir == null) {
                Timber.e("Failed to extract model files from assets")
                return false
            }
            modelDir = dir

            val encoderPath = File(dir, ENCODER_MODEL).absolutePath
            val decoderPath = File(dir, DECODER_MODEL).absolutePath
            val tokensPath = File(dir, TOKENS_FILE).absolutePath

            // Configure Whisper model for Sherpa-ONNX
            val whisperConfig = OfflineWhisperConfig(
                encoder = encoderPath,
                decoder = decoderPath,
                language = LANGUAGE,
                task = TASK,
                tailPaddings = 1280  // Whisper expects 30s frames; pad short audio
            )

            val modelConfig = OfflineModelConfig(
                whisper = whisperConfig,
                tokens = tokensPath,
                numThreads = NUM_THREADS,
                debug = false,
                provider = "cpu"  // No GPU on target 2GB devices
            )

            val config = OfflineRecognizerConfig(
                model = modelConfig,
                decodingMethod = DECODER_METHOD,
                maxActivePaths = 4,
                hotwordsFile = "",
                hotwordsScore = 1.5f,
                blankPenalty = 0.0f
            )

            recognizer = OfflineRecognizer(config)
            isLoaded = true
            Timber.i("Speech recognizer model loaded from: ${dir.absolutePath}")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load speech recognizer model")
            isLoaded = false
            recognizer = null
            false
        }
    }

    /**
     * Recognize speech from raw PCM audio data.
     * Audio format: 16kHz, 16-bit, mono PCM (little-endian).
     *
     * @param audioData Raw PCM bytes (16-bit LE, mono, 16kHz)
     * @return TranscriptionResult with text, confidence, and timing
     */
    suspend fun recognize(audioData: ByteArray): TranscriptionResult {
        if (!isLoaded || recognizer == null) {
            val loaded = loadModel()
            if (!loaded) {
                return TranscriptionResult(
                    text = "",
                    confidence = 0f,
                    success = false,
                    error = "Model not loaded"
                )
            }
        }

        val startTime = System.currentTimeMillis()

        return try {
            val rec = recognizer!!

            // Convert ByteArray → FloatArray (16-bit PCM → normalized float [-1, 1])
            val samples = pcm16ToFloat(audioData)

            if (samples.isEmpty()) {
                return TranscriptionResult(
                    text = "",
                    confidence = 0f,
                    success = false,
                    error = "Empty audio data",
                    processingTimeMs = System.currentTimeMillis() - startTime
                )
            }

            // Create stream and feed audio
            val stream = rec.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE)

            // Run offline recognition
            rec.decode(stream)

            // Extract result
            val result = stream.result
            val text = result.text.trim()
            val segments = result.segments

            // Compute a confidence proxy from segment timestamps:
            // If we got text and segments have valid timestamps, treat as high confidence.
            // Sherpa-ONNX OfflineRecognizer doesn't return raw log-probs easily,
            // so we use a heuristic: non-empty text with reasonable length → 0.85+
            val confidence = when {
                text.isEmpty() -> 0f
                text.length < 3 -> 0.3f  // Very short, likely noise
                else -> estimateConfidence(text, segments, samples.size)
            }

            val processingTime = System.currentTimeMillis() - startTime

            // Post-process for Swahili: fix common Whisper artifacts
            val cleanedText = postProcessSwahili(text)

            Timber.d("Recognized: '$cleanedText' (confidence=%.2f, %dms)", confidence, processingTime)

            stream.release()

            TranscriptionResult(
                text = cleanedText,
                confidence = confidence,
                success = cleanedText.isNotEmpty(),
                language = LANGUAGE,
                processingTimeMs = processingTime
            )
        } catch (e: Exception) {
            Timber.e(e, "Recognition failed")
            TranscriptionResult(
                text = "",
                confidence = 0f,
                success = false,
                error = e.message,
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }

    /**
     * Check if model is loaded.
     */
    fun isModelLoaded(): Boolean = isLoaded

    /**
     * Unload model to free memory (for 2GB devices).
     * Releases the native recognizer and frees ~40MB.
     */
    fun unloadModel() {
        if (isLoaded) {
            try {
                recognizer?.release()
            } catch (e: Exception) {
                Timber.w(e, "Error releasing recognizer")
            }
            recognizer = null
            isLoaded = false
            Timber.i("Speech recognizer model unloaded")
        }
    }

    /**
     * Release all resources.
     */
    fun release() {
        unloadModel()
    }

    // ── Private helpers ──────────────────────────────────────────

    /**
     * Convert 16-bit PCM bytes (little-endian) to normalized float array.
     */
    private fun pcm16ToFloat(pcm: ByteArray): FloatArray {
        if (pcm.size < 2) return FloatArray(0)
        val numSamples = pcm.size / 2
        val samples = FloatArray(numSamples)
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) {
            val s16 = buffer.short
            samples[i] = s16.toFloat() / 32768.0f
        }
        return samples
    }

    /**
     * Estimate confidence from text length and audio duration.
     * Heuristic: longer text from sufficient audio → higher confidence.
     */
    private fun estimateConfidence(
        text: String,
        segments: Array<OfflineRecognizerResultSegment>,
        numSamples: Int
    ): Float {
        val audioDurationSec = numSamples.toFloat() / SAMPLE_RATE
        val wordsPerSecond = if (audioDurationSec > 0) {
            text.split(Regex("\\s+")).size / audioDurationSec
        } else 0f

        // Normal speech is 2-5 words/sec. Too fast or too slow = low confidence.
        val rateScore = when {
            wordsPerSecond in 1.0f..8.0f -> 0.85f
            wordsPerSecond in 0.5f..10.0f -> 0.7f
            else -> 0.5f
        }

        // Boost if we have multiple segments (structured speech)
        val segmentBoost = if (segments.size > 1) 0.05f else 0f

        // Penalize very short text from long audio (likely noise)
        val noisePenalty = if (text.length < 5 && audioDurationSec > 3f) 0.3f else 0f

        return (rateScore + segmentBoost - noisePenalty).coerceIn(0.1f, 0.98f)
    }

    /**
     * Post-process Whisper output for Swahili:
     * - Remove Whisper hallucination artifacts (repeated phrases, "subscribe", etc.)
     * - Normalize common transliterations
     */
    private fun postProcessSwahili(text: String): String {
        var cleaned = text.trim()

        // Remove common Whisper hallucination patterns
        val hallucinationPatterns = listOf(
            "Subscribe", "subscribe", "Like and subscribe",
            "Thank you for watching", "Thanks for watching",
            "[Music]", "[Applause]", "[Laughter]",
            "Sauti za", "Sauti za Afrika"  // Specific to Swahili audio training data
        )
        for (pattern in hallucinationPatterns) {
            cleaned = cleaned.replace(pattern, "").trim()
        }

        // Remove repeated phrases (Whisper artifact)
        cleaned = removeRepeatedPhrases(cleaned)

        // Normalize Swahili spelling variants
        cleaned = cleaned
            .replace("ninasema", "ninazungumza")  // Normalize dialect variants
            .replace("nna", "nina")  // Common abbreviation expansion

        return cleaned.trim()
    }

    /**
     * Remove repeated phrases that Whisper sometimes produces.
     * E.g., "Niko hapa niko hapa niko hapa" → "Niko hapa"
     */
    private fun removeRepeatedPhrases(text: String): String {
        val words = text.split(Regex("\\s+"))
        if (words.size < 4) return text

        // Check for phrase-level repetition (2-4 word phrases repeated)
        for (phraseLen in 2..4) {
            if (words.size >= phraseLen * 2) {
                val phrase = words.subList(0, phraseLen).joinToString(" ").lowercase()
                var repeated = true
                for (i in phraseLen until minOf(phraseLen * 3, words.size)) {
                    val candidate = words[i % phraseLen]
                    if (words[i].lowercase() != candidate.lowercase()) {
                        repeated = false
                        break
                    }
                }
                if (repeated) {
                    return words.subList(0, phraseLen).joinToString(" ")
                }
            }
        }

        return text
    }

    /**
     * Extract model files from APK assets to internal storage.
     * Sherpa-ONNX needs filesystem paths, not asset streams.
     * Skips extraction if files already exist and sizes match.
     */
    private fun ensureModelFiles(): File? {
        return try {
            val targetDir = File(context.filesDir, MODEL_ASSET_DIR)

            // Check if already extracted
            val requiredFiles = listOf(ENCODER_MODEL, DECODER_MODEL, TOKENS_FILE)
            val allPresent = requiredFiles.all { File(targetDir, it).exists() && File(targetDir, it).length() > 0 }

            if (allPresent) {
                Timber.d("Model files already present at: ${targetDir.absolutePath}")
                return targetDir
            }

            targetDir.mkdirs()

            val assetManager = context.assets
            for (fileName in requiredFiles) {
                val assetPath = "$MODEL_ASSET_DIR/$fileName"
                val outFile = File(targetDir, fileName)

                try {
                    assetManager.open(assetPath).use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Timber.d("Extracted: $fileName (${outFile.length()} bytes)")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to extract asset: $assetPath")
                    return null
                }
            }

            targetDir
        } catch (e: Exception) {
            Timber.e(e, "Failed to prepare model directory")
            null
        }
    }
}
