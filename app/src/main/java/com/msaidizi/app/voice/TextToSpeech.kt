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
 * Piper TTS (Text-to-Speech) engine using ONNX Runtime.
 *
 * Piper uses a single ONNX model that takes phoneme IDs and outputs
 * audio samples directly. The model includes the vocoder (no separate
 * vocoder step needed).
 *
 * Pipeline: Text → Phonemizer → Phoneme IDs → ONNX Inference → AudioTrack
 *
 * Model inputs:
 * - "phoneme_ids": int64 [1, N] — phoneme token IDs
 * - "speaker_id": int64 [1] — speaker ID (0 for single-speaker models)
 * - "length_scale": float32 [1] — speed control (1.0 = normal, 0.8 = faster)
 * - "noise_scale": float32 [1] — variation (0.667 default)
 * - "noise_w": float32 [1] — phoneme duration noise (0.8 default)
 *
 * Model outputs:
 * - "audio": float32 [1, samples] — 22050Hz audio waveform
 *
 * Performance on Helio G25:
 * - Model load: ~300ms
 * - Synthesis: ~400ms for 5 words, ~800ms for 15 words
 * - Memory: ~25MB when loaded
 *
 * Swahili phoneme mapping:
 * Piper ships with a phoneme map file (piper-swahili.onnx.json) that
 * maps graphemes/phonemes to integer IDs used by the model.
 */
@Singleton
class TextToSpeech @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRegistry: ModelRegistry
) {
    companion object {
        private const val MODEL_ID = "piper-swahili"
        private const val OUTPUT_SAMPLE_RATE = 22050  // Piper outputs at 22050Hz
        private const val DEFAULT_SPEED = 1.0f
        private const val DEFAULT_NOISE_SCALE = 0.667f
        private const val DEFAULT_NOISE_W = 0.8f
        private const val DEFAULT_SPEAKER_ID = 0L
        private const val AUDIO_BUFFER_MULTIPLIER = 4  // Buffer size = pcm.size * this

        /** Phoneme ID for padding */
        private const val PAD_PHONEME_ID = 0
        /** Phoneme ID for beginning of utterance */
        private const val BOS_PHONEME_ID = 1
        /** Phoneme ID for end of utterance */
        private const val EOS_PHONEME_ID = 2
    }

    // ────────────── ONNX State ──────────────
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isModelLoaded = false

    // ────────────── Playback State ──────────────
    private var isCurrentlySpeaking = false
    private var currentAudioTrack: AudioTrack? = null
    private var speed: Float = DEFAULT_SPEED

    /** Phoneme map: grapheme/phoneme string → integer ID */
    private var phonemeMap: Map<String, Int> = emptyMap()
    private var isPhonemeMapLoaded = false


    // ────────────────────── Model Lifecycle ──────────────────────

    /**
     * Load the Piper TTS ONNX model and phoneme map.
     */
    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        if (isModelLoaded) return@withContext true

        val modelFile = modelRegistry.getModelPath(MODEL_ID)
        if (modelFile == null) {
            Timber.w("Piper TTS model not found")
            return@withContext false
        }

        try {
            val startTime = System.currentTimeMillis()

            ortEnvironment = OrtEnvironment.getEnvironment()

            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setOptimizedModelFilePath(
                    File(context.cacheDir, "piper_optimized.onnx").absolutePath
                )
            }

            ortSession = ortEnvironment!!.createSession(
                modelFile.absolutePath,
                sessionOptions
            )

            // Load phoneme map from model config JSON
            loadPhonemeMap(modelFile)

            isModelLoaded = true
            val elapsed = System.currentTimeMillis() - startTime
            Timber.i("Piper TTS model loaded in %dms", elapsed)
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load Piper TTS model")
            isModelLoaded = false
            false
        }
    }

    /**
     * Unload model to free ~25MB RAM.
     */
    fun unloadModel() {
        stop()
        ortSession?.close()
        ortSession = null
        ortEnvironment = null
        isModelLoaded = false
        Timber.d("Piper TTS model unloaded")
    }

    fun isModelReady(): Boolean = isModelLoaded

    // ────────────────────── Speech Synthesis ──────────────────────

    /**
     * Speak text with automatic language detection.
     *
     * @param text Text to speak
     * @param language Language code ("sw", "en", "sheng")
     */
    suspend fun speak(text: String, language: String = "sw") = withContext(Dispatchers.Default) {
        if (!isModelLoaded) {
            val loaded = loadModel()
            if (!loaded) {
                Timber.w("Cannot speak — model not loaded")
                return@withContext
            }
        }

        if (text.isBlank()) return@withContext

        isCurrentlySpeaking = true
        try {
            // 1. Text → Phoneme IDs
            val phonemeIds = textToPhonemes(text, language)
            if (phonemeIds.isEmpty()) {
                Timber.w("Piper: No phonemes generated for: %s", text)
                return@withContext
            }

            // 2. Create input tensors
            val phonemeIdArray = phonemeIds.map { it.toLong() }.toLongArray()

            val phonemeTensor = OnnxTensor.createTensor(
                ortEnvironment!!,
                LongBuffer.wrap(phonemeIdArray),
                longArrayOf(1, phonemeIdArray.size.toLong())
            )

            val speakerTensor = OnnxTensor.createTensor(
                ortEnvironment!!,
                LongBuffer.wrap(longArrayOf(DEFAULT_SPEAKER_ID)),
                longArrayOf(1)
            )

            // Speed control: length_scale = 1.0/speed (higher speed = shorter duration)
            val lengthScaleTensor = OnnxTensor.createTensor(
                ortEnvironment!!,
                FloatBuffer.wrap(floatArrayOf(1.0f / speed)),
                longArrayOf(1)
            )

            val noiseScaleTensor = OnnxTensor.createTensor(
                ortEnvironment!!,
                FloatBuffer.wrap(floatArrayOf(DEFAULT_NOISE_SCALE)),
                longArrayOf(1)
            )

            val noiseWTensor = OnnxTensor.createTensor(
                ortEnvironment!!,
                FloatBuffer.wrap(floatArrayOf(DEFAULT_NOISE_W)),
                longArrayOf(1)
            )

            // 3. Run ONNX inference
            val inputs = mapOf(
                "phoneme_ids" to phonemeTensor,
                "speaker_id" to speakerTensor,
                "length_scale" to lengthScaleTensor,
                "noise_scale" to noiseScaleTensor,
                "noise_w" to noiseWTensor
            )

            val startTime = System.currentTimeMillis()
            val results = ortSession!!.run(inputs)
            val inferenceTime = System.currentTimeMillis() - startTime

            // 4. Extract audio samples (float32 [-1, 1])
            val audioOutput = results.get("audio")
            val samples = (audioOutput.value as Array<FloatArray>)[0]

            Timber.d(
                "Piper: Synthesized %d samples (%.1fs) in %dms",
                samples.size, samples.size.toFloat() / OUTPUT_SAMPLE_RATE, inferenceTime
            )

            // 5. Convert float32 [-1,1] → int16 PCM
            val pcmData = ShortArray(samples.size) { i ->
                (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }

            // 6. Play audio via AudioTrack (streaming)
            playPcmAudio(pcmData, OUTPUT_SAMPLE_RATE)

            // 7. Cleanup tensors
            phonemeTensor.close()
            speakerTensor.close()
            lengthScaleTensor.close()
            noiseScaleTensor.close()
            noiseWTensor.close()
            results.close()

        } catch (e: OutOfMemoryError) {
            Timber.e("OOM during TTS synthesis")
            unloadModel()
            System.gc()
        } catch (e: Exception) {
            Timber.e(e, "Piper TTS synthesis error")
        } finally {
            isCurrentlySpeaking = false
        }
    }

    /**
     * Speak with streaming playback — starts playing before full synthesis.
     * Splits text into sentences and synthesizes each one.
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

    // ────────────────────── Phonemizer ──────────────────────

    /**
     * Convert text to phoneme IDs using the model's phoneme map.
     *
     * Pipeline:
     * 1. Try espeak-ng for IPA phonemization (if available)
     * 2. Fall back to grapheme-to-phoneme mapping
     * 3. Wrap with BOS/EOS tokens
     *
     * @param text Input text
     * @param language Language code for phonemizer
     * @return List of phoneme IDs
     */
    private fun textToPhonemes(text: String, language: String): List<Int> {
        val phonemes = mutableListOf<Int>()

        // Add BOS token
        phonemes.add(BOS_PHONEME_ID)

        // Try espeak-ng first (more accurate)
        val ipaPhonemes = tryEspeakPhonemize(text, language)
        if (ipaPhonemes != null) {
            // Map IPA phonemes to model IDs
            for (phoneme in ipaPhonemes) {
                val id = phonemeMap[phoneme]
                if (id != null) {
                    phonemes.add(id)
                } else {
                    // Try character-level fallback for unknown phonemes
                    for (c in phoneme) {
                        val charId = phonemeMap[c.toString()]
                        if (charId != null) {
                            phonemes.add(charId)
                        }
                    }
                }
            }
        } else {
            // Fallback: direct character-to-phoneme mapping
            for (c in text.lowercase()) {
                val id = phonemeMap[c.toString()]
                if (id != null) {
                    phonemes.add(id)
                }
            }
        }

        // Add EOS token
        phonemes.add(EOS_PHONEME_ID)

        return phonemes
    }

    /**
     * Try to phonemize text using espeak-ng.
     * Returns list of IPA phoneme strings, or null if espeak-ng unavailable.
     */
    private fun tryEspeakPhonemize(text: String, language: String): List<String>? {
        return try {
            val langCode = when (language) {
                "sw" -> "sw"    // Swahili
                "en" -> "en"    // English
                "sheng" -> "sw" // Sheng → use Swahili phonemizer
                else -> "sw"
            }

            val process = Runtime.getRuntime().exec(
                arrayOf("espeak-ng", "-v", langCode, "-q", "--ipa", text)
            )
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()

            if (output.isNotEmpty()) {
                // Split IPA output into individual phonemes
                output.split(Regex("\\s+")).filter { it.isNotEmpty() }
            } else null
        } catch (e: Exception) {
            // espeak-ng not available on this device
            null
        }
    }

    /**
     * Load phoneme map from model config file.
     * File: piper-swahili.onnx.json (adjacent to model file)
     */
    private fun loadPhonemeMap(modelFile: File) {
        try {
            val configFile = File(modelFile.parent, "${modelFile.nameWithoutExtension}.json")
            if (!configFile.exists()) {
                // Try alternative naming
                val altConfig = File(modelFile.parent, "piper-swahili.json")
                if (altConfig.exists()) {
                    parsePhonemeConfig(altConfig)
                } else {
                    Timber.w("Phoneme config not found, using default mapping")
                    loadDefaultPhonemeMap()
                }
                return
            }
            parsePhonemeConfig(configFile)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load phoneme map")
            loadDefaultPhonemeMap()
        }
    }

    /**
     * Parse Piper config JSON to extract phoneme map.
     * Format: {"phoneme_id_map": {"a": 3, "b": 4, ...}}
     */
    private fun parsePhonemeConfig(configFile: File) {
        try {
            val json = configFile.readText()
            // Simple JSON parsing for phoneme_id_map
            val mapStart = json.indexOf("\"phoneme_id_map\"")
            if (mapStart == -1) {
                loadDefaultPhonemeMap()
                return
            }

            val map = mutableMapOf<String, Int>()
            // Extract key-value pairs: "char": id
            val pattern = Regex(""""([^"]+)":\s*(\d+)""")
            val section = json.substring(mapStart)
            for (match in pattern.findAll(section)) {
                val char = match.groupValues[1]
                val id = match.groupValues[2].toIntOrNull() ?: continue
                map[char] = id
            }

            if (map.isNotEmpty()) {
                phonemeMap = map
                isPhonemeMapLoaded = true
                Timber.d("Loaded %d phoneme mappings", map.size)
            } else {
                loadDefaultPhonemeMap()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse phoneme config")
            loadDefaultPhonemeMap()
        }
    }

    /**
     * Default phoneme map for Swahili (fallback).
     * Maps basic Latin characters + common Swahili digraphs.
     */
    private fun loadDefaultPhonemeMap() {
        val map = mutableMapOf<String, Int>()

        // Basic Latin letters (IDs start after special tokens)
        var id = 3
        for (c in 'a'..'z') {
            map[c.toString()] = id++
        }

        // Common Swahili digraphs/special phonemes
        map["ng'"] = id++
        map["ny"] = id++
        map["sh"] = id++
        map["dh"] = id++
        map["th"] = id++
        map["kh"] = id++

        // Digits
        for (c in '0'..'9') {
            map[c.toString()] = id++
        }

        // Space and punctuation
        map[" "] = id++
        map["."] = id++
        map[","] = id++
        map["?"] = id++
        map["!"] = id++

        phonemeMap = map
        isPhonemeMapLoaded = true
        Timber.d("Loaded default phoneme map with %d entries", map.size)
    }

    // ────────────────────── Audio Playback ──────────────────────

    /**
     * Play PCM audio through Android AudioTrack.
     *
     * @param pcm 16-bit PCM samples
     * @param sampleRate Sample rate in Hz
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
                val writeSize = minOf(chunkSize / 2, remaining)  // /2 because ShortArray
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
