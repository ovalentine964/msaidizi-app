package com.msaidizi.app.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Meta MMS (Massively Multilingual Speech) TTS engine.
 *
 * Supports 1,100+ languages including African languages:
 * - Swahili (swa)     - Yoruba (yor)      - Hausa (hau)
 * - Amharic (amh)     - Zulu (zul)        - Igbo (ibo)
 * - Xhosa (xho)       - Shona (sna)       - Northern Sotho (nso)
 *
 * Architecture: VITS (Variational Inference with adversarial learning
 * for end-to-end Text-to-Speech). Each language has a separate model.
 *
 * MMS uses character-level tokenization (vocab.txt) rather than
 * phoneme-based tokenization like Piper. Text is uppercased before
 * tokenization, and each character maps to an integer ID.
 *
 * Model inputs (ONNX):
 * - "x": int64 [1, N] — character token IDs
 * - "x_lengths": int64 [1] — token count
 * - "noise_scale": float32 [1] — variation (0.667 default)
 * - "length_scale": float32 [1] — speed control (1.0 = normal)
 * - "noise_scale_w": float32 [1] — duration noise (0.8 default)
 *
 * Model output:
 * - "audio": float32 [1, samples] — 16kHz audio waveform
 *
 * Performance estimate (Helio G25):
 * - Model load: ~400ms
 * - Synthesis: ~600ms for 5 words, ~1.2s for 15 words
 * - Memory: ~35MB when loaded
 *
 * Reference: Pratap et al. (2023) "Scaling Speech Technology to 1,000+ Languages"
 * License: CC-BY-NC-4.0
 *
 * @see <a href="https://arxiv.org/abs/2305.13516">MMS Paper</a>
 * @see <a href="https://huggingface.co/facebook/mms-tts">HuggingFace Models</a>
 */
@Singleton
class MMSTextToSpeech @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRegistry: ModelRegistry
) {
    companion object {
        /** MMS output sample rate (all MMS models output at 16kHz) */
        private const val OUTPUT_SAMPLE_RATE = 16000

        private const val DEFAULT_SPEED = 1.0f
        private const val DEFAULT_NOISE_SCALE = 0.667f
        private const val DEFAULT_NOISE_W = 0.8f
        private const val AUDIO_BUFFER_MULTIPLIER = 4

        // Special tokens in MMS vocab
        private const val PAD_TOKEN_ID = 0
        private const val UNK_TOKEN_ID = 0

        /**
         * MMS language codes (ISO 639-3) mapped to model IDs in ModelRegistry.
         * Each language needs its own ONNX model + vocab.txt file.
         */
        val LANGUAGE_MODEL_MAP = mapOf(
            "sw" to "mms-tts-swa",     // Swahili
            "swa" to "mms-tts-swa",
            "en" to "mms-tts-eng",     // English
            "eng" to "mms-tts-eng",
            "yo" to "mms-tts-yor",     // Yoruba
            "yor" to "mms-tts-yor",
            "ha" to "mms-tts-hau",     // Hausa
            "hau" to "mms-tts-hau",
            "am" to "mms-tts-amh",     // Amharic
            "amh" to "mms-tts-amh",
            "zu" to "mms-tts-zul",     // Zulu
            "zul" to "mms-tts-zul",
            "ig" to "mms-tts-ibo",     // Igbo
            "ibo" to "mms-tts-ibo",
            "xh" to "mms-tts-xho",     // Xhosa
            "xho" to "mms-tts-xho",
            "sn" to "mms-tts-sna",     // Shona
            "sna" to "mms-tts-sna",
            "st" to "mms-tts-nso",     // Northern Sotho
            "nso" to "mms-tts-nso"
        )

        /**
         * Map app-level language codes to ISO 639-3 for model lookup.
         */
        private fun normalizeLanguageCode(language: String): String {
            return when (language.lowercase()) {
                "sw", "swahili", "swa" -> "swa"
                "en", "english", "eng" -> "eng"
                "yo", "yoruba", "yor" -> "yor"
                "ha", "hausa", "hau" -> "hau"
                "am", "amharic", "amh" -> "amh"
                "zu", "zulu", "zul" -> "zul"
                "ig", "igbo", "ibo" -> "ibo"
                "xh", "xhosa", "xho" -> "xho"
                "sn", "shona", "sna" -> "sna"
                "st", "sotho", "nso" -> "nso"
                "sheng" -> "swa"  // Sheng → use Swahili voice
                "mixed" -> "swa"  // Code-mixed → default to Swahili
                else -> language.lowercase().take(3)
            }
        }
    }

    // ────────────── ONNX State ──────────────
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var currentModelId: String? = null
    private var isModelLoaded = false

    // ────────────── Tokenizer State ──────────────
    private var vocab: Map<String, Int> = emptyMap()  // char → token ID
    private var isVocabLoaded = false

    // ────────────── Playback State ──────────────
    private var isCurrentlySpeaking = false
    private var currentAudioTrack: AudioTrack? = null
    private var speed: Float = DEFAULT_SPEED

    // ────────────────────── Model Lifecycle ──────────────────────

    /**
     * Load MMS TTS ONNX model and vocabulary for a specific language.
     *
     * @param language Language code (ISO 639-1 or 639-3)
     * @return true if model loaded successfully
     */
    suspend fun loadModel(language: String = "swa"): Boolean = withContext(Dispatchers.IO) {
        val langCode = normalizeLanguageCode(language)
        val modelId = LANGUAGE_MODEL_MAP[langCode]

        if (modelId == null) {
            Timber.w("MMS TTS: No model mapping for language '%s'", language)
            return@withContext false
        }

        // Skip reload if same model already loaded
        if (isModelLoaded && currentModelId == modelId) {
            return@withContext true
        }

        // Unload previous model if switching languages
        if (isModelLoaded && currentModelId != modelId) {
            unloadModel()
        }

        val modelFile = modelRegistry.getModelPath(modelId)
        if (modelFile == null) {
            Timber.w("MMS TTS: Model '%s' not downloaded for language '%s'", modelId, language)
            return@withContext false
        }

        try {
            val startTime = System.currentTimeMillis()

            ortEnvironment = OrtEnvironment.getEnvironment()

            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setOptimizedModelFilePath(
                    File(context.cacheDir, "mms_${langCode}_optimized.onnx").absolutePath
                )
            }

            ortSession = ortEnvironment!!.createSession(
                modelFile.absolutePath,
                sessionOptions
            )

            // Load vocabulary (vocab.txt in same directory as model)
            loadVocabulary(modelFile)

            currentModelId = modelId
            isModelLoaded = true

            val elapsed = System.currentTimeMillis() - startTime
            Timber.i("MMS TTS model '%s' loaded in %dms", modelId, elapsed)
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load MMS TTS model '%s'", modelId)
            isModelLoaded = false
            false
        }
    }

    /**
     * Unload model to free ~35MB RAM.
     */
    fun unloadModel() {
        stop()
        ortSession?.close()
        ortSession = null
        ortEnvironment = null
        currentModelId = null
        isModelLoaded = false
        vocab = emptyMap()
        isVocabLoaded = false
        Timber.d("MMS TTS model unloaded")
    }

    fun isModelReady(): Boolean = isModelLoaded

    /**
     * Get the currently loaded language model ID, or null if none loaded.
     */
    fun getLoadedModelId(): String? = currentModelId

    /**
     * Check if a specific language is supported by MMS.
     */
    fun isLanguageSupported(language: String): Boolean {
        val langCode = normalizeLanguageCode(language)
        return LANGUAGE_MODEL_MAP.containsKey(langCode)
    }

    /**
     * Get list of supported language codes.
     */
    fun getSupportedLanguages(): List<String> {
        return listOf("sw", "en", "yo", "ha", "am", "zu", "ig", "xh", "sn", "st")
    }

    // ────────────────────── Speech Synthesis ──────────────────────

    /**
     * Synthesize speech from text using MMS TTS.
     *
     * @param text Text to synthesize
     * @param language Language code (default: Swahili)
     */
    suspend fun speak(text: String, language: String = "sw") = withContext(Dispatchers.Default) {
        val langCode = normalizeLanguageCode(language)

        // Load model for requested language if not already loaded
        if (!isModelLoaded || currentModelId != LANGUAGE_MODEL_MAP[langCode]) {
            val loaded = loadModel(langCode)
            if (!loaded) {
                Timber.w("MMS TTS: Cannot speak — model not loaded for '%s'", language)
                return@withContext
            }
        }

        if (text.isBlank()) return@withContext

        isCurrentlySpeaking = true
        try {
            // 1. Text → Token IDs (character-level, uppercased)
            val tokenIds = tokenize(text)
            if (tokenIds.isEmpty()) {
                Timber.w("MMS TTS: No tokens generated for: %s", text)
                return@withContext
            }

            // 2. Create input tensors
            val tokenIdArray = tokenIds.map { it.toLong() }.toLongArray()
            val tokenCount = tokenIdArray.size.toLong()

            val xTensor = OnnxTensor.createTensor(
                ortEnvironment!!,
                LongBuffer.wrap(tokenIdArray),
                longArrayOf(1, tokenCount)
            )

            val xLengthsTensor = OnnxTensor.createTensor(
                ortEnvironment!!,
                LongBuffer.wrap(longArrayOf(tokenCount)),
                longArrayOf(1)
            )

            val noiseScaleTensor = OnnxTensor.createTensor(
                ortEnvironment!!,
                FloatBuffer.wrap(floatArrayOf(DEFAULT_NOISE_SCALE)),
                longArrayOf(1)
            )

            val lengthScaleTensor = OnnxTensor.createTensor(
                ortEnvironment!!,
                FloatBuffer.wrap(floatArrayOf(1.0f / speed)),
                longArrayOf(1)
            )

            val noiseWTensor = OnnxTensor.createTensor(
                ortEnvironment!!,
                FloatBuffer.wrap(floatArrayOf(DEFAULT_NOISE_W)),
                longArrayOf(1)
            )

            // 3. Run ONNX inference
            val inputs = mapOf(
                "x" to xTensor,
                "x_lengths" to xLengthsTensor,
                "noise_scale" to noiseScaleTensor,
                "length_scale" to lengthScaleTensor,
                "noise_scale_w" to noiseWTensor
            )

            val startTime = System.currentTimeMillis()
            val results = ortSession!!.run(inputs)
            val inferenceTime = System.currentTimeMillis() - startTime

            // 4. Extract audio samples (float32 [-1, 1])
            val audioOutput = results.get("audio")
            val samples = (audioOutput.value as Array<FloatArray>)[0]

            Timber.d(
                "MMS TTS: Synthesized %d samples (%.1fs) in %dms [lang=%s]",
                samples.size,
                samples.size.toFloat() / OUTPUT_SAMPLE_RATE,
                inferenceTime,
                langCode
            )

            // 5. Convert float32 [-1,1] → int16 PCM
            val pcmData = ShortArray(samples.size) { i ->
                (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }

            // 6. Play audio via AudioTrack (streaming)
            playPcmAudio(pcmData, OUTPUT_SAMPLE_RATE)

            // 7. Cleanup tensors
            xTensor.close()
            xLengthsTensor.close()
            noiseScaleTensor.close()
            lengthScaleTensor.close()
            noiseWTensor.close()
            results.close()

        } catch (e: OutOfMemoryError) {
            Timber.e("OOM during MMS TTS synthesis")
            unloadModel()
            System.gc()
        } catch (e: Exception) {
            Timber.e(e, "MMS TTS synthesis error")
        } finally {
            isCurrentlySpeaking = false
        }
    }

    /**
     * Speak with streaming playback — splits text into sentences.
     */
    suspend fun speakStreaming(text: String, language: String = "sw") {
        val sentences = splitIntoSentences(text)
        for (sentence in sentences) {
            if (sentence.isNotBlank()) {
                speak(sentence.trim(), language)
            }
        }
    }

    /**
     * Stop current speech playback immediately.
     */
    fun stop() {
        currentAudioTrack?.let { track ->
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                }
                track.release()
            } catch (e: Exception) {
                Timber.w(e, "Error stopping AudioTrack")
            }
        }
        currentAudioTrack = null
        isCurrentlySpeaking = false
    }

    /**
     * Check if currently speaking.
     */
    fun isSpeaking(): Boolean = isCurrentlySpeaking

    /**
     * Set speech speed (0.5 = half speed, 2.0 = double speed).
     */
    fun setSpeed(newSpeed: Float) {
        speed = newSpeed.coerceIn(0.5f, 2.0f)
    }

    // ────────────────────── Tokenizer ──────────────────────

    /**
     * Convert text to token IDs for MMS model.
     *
     * MMS uses character-level tokenization:
     * 1. Convert text to uppercase (MMS models trained on uppercase)
     * 2. Map each character to its vocab ID
     * 3. Unknown characters get UNK_TOKEN_ID
     *
     * @param text Input text
     * @return List of token IDs
     */
    private fun tokenize(text: String): List<Int> {
        if (!isVocabLoaded) {
            Timber.w("MMS TTS: Vocabulary not loaded, using fallback")
            return fallbackTokenize(text)
        }

        val tokens = mutableListOf<Int>()
        val upperText = text.uppercase()

        for (char in upperText) {
            val charStr = char.toString()
            val id = vocab[charStr]
            if (id != null) {
                tokens.add(id)
            } else {
                // Try space for unknown characters
                val spaceId = vocab[" "]
                if (spaceId != null) {
                    tokens.add(spaceId)
                }
            }
        }

        return tokens
    }

    /**
     * Fallback tokenizer when vocab.txt is not available.
     * Maps ASCII characters to simple integer IDs.
     */
    private fun fallbackTokenize(text: String): List<Int> {
        return text.uppercase().map { char ->
            when {
                char in 'A'..'Z' -> char - 'A' + 3  // IDs 3-28
                char == ' ' -> 29
                char == '.' -> 30
                char == ',' -> 31
                char == '?' -> 32
                char == '!' -> 33
                char in '0'..'9' -> char - '0' + 34
                else -> 0  // PAD/UNK
            }
        }
    }

    /**
     * Load vocabulary from vocab.txt file adjacent to ONNX model.
     *
     * Format: one token per line, line number = token ID.
     * Example:
     *   <pad>     → ID 0
     *   <unk>     → ID 1
     *   A         → ID 2
     *   B         → ID 3
     *   ...
     */
    private fun loadVocabulary(modelFile: File) {
        try {
            // vocab.txt should be in same directory as the ONNX model
            val vocabFile = File(modelFile.parentFile, "vocab.txt")

            if (!vocabFile.exists()) {
                Timber.w("MMS TTS: vocab.txt not found at %s, using fallback", vocabFile.absolutePath)
                isVocabLoaded = false
                return
            }

            val map = mutableMapOf<String, Int>()
            vocabFile.readLines().forEachIndexed { index, line ->
                val token = line.trim()
                if (token.isNotEmpty()) {
                    map[token] = index
                }
            }

            if (map.isNotEmpty()) {
                vocab = map
                isVocabLoaded = true
                Timber.d("MMS TTS: Loaded vocabulary with %d tokens", map.size)
            } else {
                Timber.w("MMS TTS: vocab.txt is empty")
                isVocabLoaded = false
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load MMS vocabulary")
            isVocabLoaded = false
        }
    }

    // ────────────────────── Audio Playback ──────────────────────

    /**
     * Play PCM audio through Android AudioTrack.
     * MMS outputs at 16kHz (vs Piper's 22050Hz).
     *
     * @param pcm 16-bit PCM samples
     * @param sampleRate Sample rate in Hz (16000 for MMS)
     */
    private fun playPcmAudio(pcm: ShortArray, sampleRate: Int) {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val bufferSize = maxOf(minBufferSize, pcm.size * 2 * AUDIO_BUFFER_MULTIPLIER)

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
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        currentAudioTrack = audioTrack

        try {
            audioTrack.play()

            // Stream audio in chunks for lower latency
            val chunkSize = minBufferSize.coerceAtMost(8192)
            var offset = 0
            while (offset < pcm.size && isCurrentlySpeaking) {
                val remaining = pcm.size - offset
                val writeSize = minOf(chunkSize / 2, remaining)
                audioTrack.write(pcm, offset, writeSize)
                offset += writeSize
            }

            // Wait for playback to complete
            while (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING
                && audioTrack.playbackHeadPosition < pcm.size
                && isCurrentlySpeaking
            ) {
                delay(20)
            }
        } catch (e: Exception) {
            Timber.e(e, "AudioTrack playback error")
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

    // ────────────────────── Text Helpers ──────────────────────

    /**
     * Split text into sentences for streaming synthesis.
     */
    private fun splitIntoSentences(text: String): List<String> {
        return text.split(Regex("(?<=[.!?])\\s+|(?<=\\n)"))
            .filter { it.isNotBlank() }
    }

    private suspend fun delay(ms: Long) {
        kotlinx.coroutines.delay(ms)
    }
}
