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
 * Kokoro TTS engine — primary text-to-speech for Msaidizi.
 *
 * Kokoro: 82M params, Apache 2.0 license, runs real-time on CPU.
 * Significantly better quality than Piper while still fitting on $50 phones.
 *
 * Architecture: StyleTTS 2 based — generates speech from phoneme IDs
 * with a style vector that controls voice personality.
 *
 * Voice personalities (configurable):
 * - EMPATHETIC: Warm, slow, comforting (for frustrated/anxious users)
 * - EXCITED: Fast, high energy, upbeat (for happy users, good news)
 * - PROFESSIONAL: Clear, measured, authoritative (for business confirmations)
 * - DEFAULT: Balanced, natural conversational tone
 *
 * Model inputs (ONNX):
 * - "phoneme_ids": int64 [1, N] — phoneme token IDs
 * - "style_vector": float32 [1, 256] — voice personality embedding
 * - "speed": float32 [1] — speech rate multiplier (1.0 = normal)
 *
 * Model output:
 * - "audio": float32 [1, samples] — 24kHz audio waveform
 *
 * Performance on Helio G25 (2GB):
 * - Model load: ~500ms
 * - Synthesis: ~300ms for 5 words, ~600ms for 15 words
 * - Memory: ~90MB when loaded
 * - CPU: ~15% on 2 cores
 *
 * Kokoro supports multiple voices via style vectors.
 * Msaidizi ships 3 voice styles pre-computed in kokoro-voices.bin:
 * 1. Default (balanced, warm)
 * 2. Empathetic (slower, lower pitch, comforting)
 * 3. Excited (faster, higher pitch, energetic)
 *
 * @see <a href="https://github.com/hexgrad/kokoro">Kokoro TTS</a>
 * @see <a href="https://huggingface.co/hexgrad/Kokoro-82M">HuggingFace</a>
 */
@Singleton
class KokoroTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRegistry: ModelRegistry
) {
    companion object {
        private const val MODEL_ID = "kokoro-swahili"
        private const val OUTPUT_SAMPLE_RATE = 24000  // Kokoro outputs at 24kHz
        private const val DEFAULT_SPEED = 1.0f
        private const val AUDIO_BUFFER_MULTIPLIER = 4

        // Phoneme special tokens
        private const val PAD_PHONEME_ID = 0
        private const val BOS_PHONEME_ID = 1
        private const val EOS_PHONEME_ID = 2

        // Style vector dimension
        private const val STYLE_DIM = 256

        // Voice personality indices in kokoro-voices.bin
        const val VOICE_DEFAULT = 0
        const val VOICE_EMPATHETIC = 1
        const val VOICE_EXCITED = 2
        const val VOICE_PROFESSIONAL = 3

        /**
         * Map emotion to voice personality.
         * Used by VoicePipeline to auto-select tone based on detected emotion.
         */
        fun emotionToVoice(emotion: com.msaidizi.app.voice.emotion.VoiceEmotion): Int {
            return when (emotion) {
                com.msaidizi.app.voice.emotion.VoiceEmotion.NEUTRAL -> VOICE_DEFAULT
                com.msaidizi.app.voice.emotion.VoiceEmotion.HAPPY -> VOICE_EXCITED
                com.msaidizi.app.voice.emotion.VoiceEmotion.FRUSTRATED -> VOICE_EMPATHETIC
                com.msaidizi.app.voice.emotion.VoiceEmotion.CONFUSED -> VOICE_EMPATHETIC
                com.msaidizi.app.voice.emotion.VoiceEmotion.ANXIOUS -> VOICE_EMPATHETIC
                com.msaidizi.app.voice.emotion.VoiceEmotion.URGENT -> VOICE_PROFESSIONAL
            }
        }
    }

    // ────────────── ONNX State ──────────────
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isModelLoaded = false

    // ────────────── Voice Style Vectors ──────────────
    private var voiceStyles: Array<FloatArray> = emptyArray()
    private var isVoiceStylesLoaded = false

    // ────────────── Phoneme Map ──────────────
    private var phonemeMap: Map<String, Int> = emptyMap()
    private var isPhonemeMapLoaded = false

    // ────────────── Playback State ──────────────
    private var isCurrentlySpeaking = false
    private var currentAudioTrack: AudioTrack? = null
    private var speed: Float = DEFAULT_SPEED
    private var currentVoiceId: Int = VOICE_DEFAULT

    // ────────────────────── Model Lifecycle ──────────────────────

    /**
     * Load the Kokoro TTS ONNX model, voice styles, and phoneme map.
     *
     * Includes memory safety check: refuses to load if < 200MB free RAM.
     * On 2GB (BASIC) devices, Kokoro (~90MB) is only loaded when Whisper is NOT loaded.
     */
    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        if (isModelLoaded) return@withContext true

        // ═══ MEMORY SAFETY: Check available RAM before loading ═══
        val runtime = Runtime.getRuntime()
        val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMB = runtime.maxMemory() / (1024 * 1024)
        val freeMB = maxMB - usedMB
        if (freeMB < 200) {
            Timber.e("Kokoro TTS: REFUSING to load — only %dMB free (need 200MB buffer)", freeMB)
            return@withContext false
        }

        val modelDir = modelRegistry.getModelPath(MODEL_ID)
        if (modelDir == null) {
            Timber.w("Kokoro TTS model not found")
            return@withContext false
        }

        try {
            val startTime = System.currentTimeMillis()

            ortEnvironment = OrtEnvironment.getEnvironment()

            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setOptimizedModelFilePath(
                    File(context.cacheDir, "kokoro_optimized.onnx").absolutePath
                )
            }

            // Load model (multi-file model: directory contains model + voices + config)
            val modelFile = if (modelDir.isDirectory) {
                File(modelDir, "kokoro-swahili.onnx")
            } else {
                modelDir
            }

            ortSession = requireNotNull(ortEnvironment).createSession(
                modelFile.absolutePath, sessionOptions
            )

            // Load voice style vectors
            loadVoiceStyles(modelDir)

            // Load phoneme map from config
            loadPhonemeMap(modelDir)

            isModelLoaded = true
            val elapsed = System.currentTimeMillis() - startTime
            Timber.i("Kokoro TTS model loaded in %dms", elapsed)
            true
        } catch (e: OutOfMemoryError) {
            Timber.e("OOM loading Kokoro TTS model — device has insufficient memory")
            isModelLoaded = false
            ortSession?.close()
            ortSession = null
            ortEnvironment = null
            System.gc()
            false
        } catch (e: Exception) {
            Timber.e(e, "Failed to load Kokoro TTS model")
            isModelLoaded = false
            false
        }
    }

    /**
     * Unload model to free ~90MB RAM.
     */
    fun unloadModel() {
        stop()
        ortSession?.close()
        ortSession = null
        ortEnvironment = null
        isModelLoaded = false
        voiceStyles = emptyArray()
        isVoiceStylesLoaded = false
        Timber.d("Kokoro TTS model unloaded")
    }

    fun isModelReady(): Boolean = isModelLoaded

    // ────────────────────── Voice Personality ──────────────────────

    /**
     * Set the voice personality.
     * @param voiceId VOICE_DEFAULT, VOICE_EMPATHETIC, VOICE_EXCITED, or VOICE_PROFESSIONAL
     */
    fun setVoice(voiceId: Int) {
        currentVoiceId = voiceId.coerceIn(0, maxOf(0, voiceStyles.size - 1))
    }

    /**
     * Set voice based on detected emotion.
     * Convenience method for emotion-aware TTS.
     */
    fun setVoiceForEmotion(emotion: com.msaidizi.app.voice.emotion.VoiceEmotion) {
        setVoice(emotionToVoice(emotion))
    }

    /**
     * Set speech speed (0.5 = half speed, 2.0 = double speed).
     */
    fun setSpeed(newSpeed: Float) {
        speed = newSpeed.coerceIn(0.5f, 2.0f)
    }

    // ────────────────────── Speech Synthesis ──────────────────────

    /**
     * Speak text with the current voice personality.
     *
     * @param text Text to speak
     * @param language Language code ("sw", "en", "sheng")
     */
    suspend fun speak(text: String, language: String = "sw") = withContext(Dispatchers.Default) {
        if (!isModelLoaded) {
            val loaded = loadModel()
            if (!loaded) {
                Timber.w("Cannot speak — Kokoro model not loaded")
                return@withContext
            }
        }

        if (text.isBlank()) return@withContext

        isCurrentlySpeaking = true
        try {
            // 1. Text → Phoneme IDs
            val phonemeIds = textToPhonemes(text, language)
            if (phonemeIds.isEmpty()) {
                Timber.w("Kokoro: No phonemes generated for: %s", text)
                return@withContext
            }

            // 2. Get voice style vector
            val styleVector = if (isVoiceStylesLoaded && currentVoiceId < voiceStyles.size) {
                voiceStyles[currentVoiceId]
            } else {
                FloatArray(STYLE_DIM)  // Zero vector = default
            }

            // 3. Create input tensors
            val env = requireNotNull(ortEnvironment)
            val session = requireNotNull(ortSession)

            val phonemeIdArray = phonemeIds.map { it.toLong() }.toLongArray()
            val phonemeTensor = OnnxTensor.createTensor(
                env, LongBuffer.wrap(phonemeIdArray),
                longArrayOf(1, phonemeIdArray.size.toLong())
            )

            val styleTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(styleVector),
                longArrayOf(1, STYLE_DIM.toLong())
            )

            val speedTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(floatArrayOf(speed)),
                longArrayOf(1)
            )

            // 4. Run ONNX inference
            val inputs = mapOf(
                "phoneme_ids" to phonemeTensor,
                "style_vector" to styleTensor,
                "speed" to speedTensor
            )

            val startTime = System.currentTimeMillis()
            val results = session.run(inputs)
            val inferenceTime = System.currentTimeMillis() - startTime

            // 5. Extract audio samples (float32 [-1, 1])
            val audioOutput = results.get("audio")
            val samples = ((audioOutput as OnnxTensor).value as Array<FloatArray>)[0]

            Timber.d(
                "Kokoro: Synthesized %d samples (%.1fs) in %dms [voice=%d]",
                samples.size, samples.size.toFloat() / OUTPUT_SAMPLE_RATE,
                inferenceTime, currentVoiceId
            )

            // 6. Convert float32 [-1,1] → int16 PCM
            val pcmData = ShortArray(samples.size) { i ->
                (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }

            // 7. Play audio
            playPcmAudio(pcmData, OUTPUT_SAMPLE_RATE)

            // 8. Cleanup
            phonemeTensor.close()
            styleTensor.close()
            speedTensor.close()
            results.close()

        } catch (e: OutOfMemoryError) {
            Timber.e("OOM during Kokoro TTS synthesis")
            unloadModel()
            System.gc()
        } catch (e: Exception) {
            Timber.e(e, "Kokoro TTS synthesis error")
        } finally {
            isCurrentlySpeaking = false
        }
    }

    /**
     * Synthesize text to raw PCM samples without playing.
     * Used by STS pipeline and StreamingVoicePipeline.
     *
     * @param text Text to synthesize
     * @param language Language code
     * @return Raw PCM samples at 24kHz, empty array on failure
     */
    suspend fun synthesizeToPcm(text: String, language: String = "sw"): ShortArray = withContext(Dispatchers.Default) {
        if (!isModelLoaded) {
            val loaded = loadModel()
            if (!loaded) return@withContext ShortArray(0)
        }
        if (text.isBlank()) return@withContext ShortArray(0)

        try {
            val phonemeIds = textToPhonemes(text, language)
            if (phonemeIds.isEmpty()) return@withContext ShortArray(0)

            val styleVector = if (isVoiceStylesLoaded && currentVoiceId < voiceStyles.size) {
                voiceStyles[currentVoiceId]
            } else {
                FloatArray(STYLE_DIM)
            }

            val env = requireNotNull(ortEnvironment)
            val session = requireNotNull(ortSession)

            val phonemeIdArray = phonemeIds.map { it.toLong() }.toLongArray()
            val phonemeTensor = OnnxTensor.createTensor(
                env, LongBuffer.wrap(phonemeIdArray),
                longArrayOf(1, phonemeIdArray.size.toLong())
            )
            val styleTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(styleVector),
                longArrayOf(1, STYLE_DIM.toLong())
            )
            val speedTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(floatArrayOf(speed)),
                longArrayOf(1)
            )

            val results = session.run(mapOf(
                "phoneme_ids" to phonemeTensor,
                "style_vector" to styleTensor,
                "speed" to speedTensor
            ))
            val audioOutput = results.get("audio")
            val samples = ((audioOutput as OnnxTensor).value as Array<FloatArray>)[0]

            val pcmData = ShortArray(samples.size) { i ->
                (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }

            phonemeTensor.close()
            styleTensor.close()
            speedTensor.close()
            results.close()

            pcmData
        } catch (e: Exception) {
            Timber.e(e, "Kokoro synthesizeToPcm error")
            ShortArray(0)
        }
    }

    /**
     * Speak with streaming — splits text into sentences and synthesizes each.
     */
    suspend fun speakStreaming(text: String, language: String = "sw") {
        val sentences = splitIntoSentences(text)
        for (sentence in sentences) {
            if (sentence.isNotBlank() && isCurrentlySpeaking) {
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

    fun isSpeaking(): Boolean = isCurrentlySpeaking

    // ────────────────────── Phonemizer ──────────────────────

    private fun textToPhonemes(text: String, language: String): List<Int> {
        val phonemes = mutableListOf<Int>()
        phonemes.add(BOS_PHONEME_ID)

        // Try espeak-ng for IPA phonemization
        val ipaPhonemes = tryEspeakPhonemize(text, language)
        if (ipaPhonemes != null) {
            for (phoneme in ipaPhonemes) {
                val id = phonemeMap[phoneme]
                if (id != null) {
                    phonemes.add(id)
                } else {
                    for (c in phoneme) {
                        val charId = phonemeMap[c.toString()]
                        if (charId != null) phonemes.add(charId)
                    }
                }
            }
        } else {
            // Fallback: character-level
            for (c in text.lowercase()) {
                val id = phonemeMap[c.toString()]
                if (id != null) phonemes.add(id)
            }
        }

        phonemes.add(EOS_PHONEME_ID)
        return phonemes
    }

    private fun tryEspeakPhonemize(text: String, language: String): List<String>? {
        return try {
            val langCode = when (language) {
                "sw" -> "sw"
                "en" -> "en"
                "sheng" -> "sw"
                else -> "sw"
            }
            val process = Runtime.getRuntime().exec(
                arrayOf("espeak-ng", "-v", langCode, "-q", "--ipa", text)
            )
            val output = process.inputStream.bufferedReader().readText().trim()
            val completed = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) {
                Timber.w("espeak-ng process timed out after 5s, destroying")
                process.destroyForcibly()
                return null
            }
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                Timber.w("espeak-ng exited with code %d", exitCode)
                return null
            }
            if (output.isNotEmpty()) {
                output.split(Regex("\\s+")).filter { it.isNotEmpty() }
            } else null
        } catch (e: Exception) {
            Timber.w(e, "espeak-ng phonemization failed")
            null
        }
    }

    private fun loadPhonemeMap(modelDir: File) {
        try {
            val configFile = if (modelDir.isDirectory) {
                File(modelDir, "kokoro-config.json")
            } else {
                File(modelDir.parent, "${modelDir.nameWithoutExtension}-config.json")
            }

            if (!configFile.exists()) {
                Timber.w("Kokoro config not found, using default phoneme map")
                loadDefaultPhonemeMap()
                return
            }

            val json = configFile.readText()
            val mapStart = json.indexOf("\"phoneme_id_map\"")
            if (mapStart == -1) {
                loadDefaultPhonemeMap()
                return
            }

            val map = mutableMapOf<String, Int>()
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
                Timber.d("Kokoro: Loaded %d phoneme mappings", map.size)
            } else {
                loadDefaultPhonemeMap()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load Kokoro phoneme map")
            loadDefaultPhonemeMap()
        }
    }

    private fun loadDefaultPhonemeMap() {
        val map = mutableMapOf<String, Int>()
        var id = 3
        for (c in 'a'..'z') map[c.toString()] = id++
        map["ng'"] = id++
        map["ny"] = id++
        map["sh"] = id++
        map["dh"] = id++
        map["th"] = id++
        map["kh"] = id++
        for (c in '0'..'9') map[c.toString()] = id++
        map[" "] = id++
        map["."] = id++
        map[","] = id++
        map["?"] = id++
        map["!"] = id++
        phonemeMap = map
        isPhonemeMapLoaded = true
        Timber.d("Kokoro: Loaded default phoneme map with %d entries", map.size)
    }

    /**
     * Load pre-computed voice style vectors from kokoro-voices.bin.
     * Format: N voices × STYLE_DIM floats (little-endian float32).
     */
    private fun loadVoiceStyles(modelDir: File) {
        try {
            val voicesFile = if (modelDir.isDirectory) {
                File(modelDir, "kokoro-voices.bin")
            } else {
                File(modelDir.parent, "kokoro-voices.bin")
            }

            if (!voicesFile.exists()) {
                Timber.w("Kokoro voices file not found, using default style")
                isVoiceStylesLoaded = false
                return
            }

            val bytes = voicesFile.readBytes()
            val floatsPerVoice = STYLE_DIM
            val numVoices = bytes.size / (floatsPerVoice * 4)  // 4 bytes per float32

            if (numVoices < 1) {
                Timber.w("Kokoro voices file too small")
                isVoiceStylesLoaded = false
                return
            }

            val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            voiceStyles = Array(numVoices) { voiceIdx ->
                FloatArray(floatsPerVoice) { buffer.float }
            }

            isVoiceStylesLoaded = true
            Timber.d("Kokoro: Loaded %d voice styles", numVoices)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load Kokoro voice styles")
            isVoiceStylesLoaded = false
        }
    }

    // ────────────────────── Audio Playback ──────────────────────

    private suspend fun playPcmAudio(pcm: ShortArray, sampleRate: Int) {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
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
            bufferSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
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
            // Suspend delay instead of Thread.sleep — non-blocking
            while (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING
                && audioTrack.playbackHeadPosition < pcm.size
                && isCurrentlySpeaking
            ) {
                kotlinx.coroutines.delay(20)
            }
        } catch (e: Exception) {
            Timber.e(e, "AudioTrack playback error")
        } finally {
            try {
                audioTrack.stop()
                audioTrack.release()
            } catch (_: Exception) {}
            if (currentAudioTrack == audioTrack) currentAudioTrack = null
        }
    }

    private fun splitIntoSentences(text: String): List<String> {
        return text.split(Regex("(?<=[.!?])\\s+|(?<=\\n)"))
            .filter { it.isNotBlank() }
    }
}
