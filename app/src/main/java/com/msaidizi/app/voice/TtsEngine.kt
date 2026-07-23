package com.msaidizi.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * TtsEngine — Text-to-speech using Piper TTS via Sherpa-ONNX.
 *
 * Primary: Piper TTS (25MB model, works on 2GB devices)
 * Quality: Kokoro TTS (better quality, 3GB+ devices only)
 *
 * Mutual exclusion with SpeechRecognizer: when STT is active,
 * TTS model is unloaded to stay within 2GB memory budget.
 *
 * Design: arch_voice.md, arch_android.md Section 4.2
 */
@Singleton
class TtsEngine @Inject constructor(
    private val context: Context
) {
    private var isLoaded = false
    @Volatile private var isSpeaking = false
    private var currentEngine: TtsBackend = TtsBackend.PIPER

    // Sherpa-ONNX TTS instance
    private var offlineTts: OfflineTts? = null

    // Audio playback
    private var audioTrack: AudioTrack? = null
    @Volatile private var stopRequested = false

    enum class TtsBackend {
        PIPER,   // Lightweight, 25MB, works on 2GB devices
        KOKORO   // Higher quality, needs 3GB+ devices
    }

    companion object {
        // Piper model paths
        private const val PIPER_ASSET_DIR = "models/piper"
        private const val PIPER_MODEL = "piper-swahili.onnx"
        private const val PIPER_TOKENS = "tokens.txt"
        private const val PIPER_DATA_DIR = "espeak-ng-data"

        // Kokoro model paths
        private const val KOKORO_ASSET_DIR = "models/kokoro"
        private const val KOKORO_MODEL = "kokoro-swahili.onnx"
        private const val KOKORO_VOICES = "voices.bin"

        // Audio output config
        private const val SAMPLE_RATE = 22050  // Piper default output rate
        private const val KOKORO_SAMPLE_RATE = 24000
        private const val NUM_THREADS = 2
        private const val KOKORO_NUM_THREADS = 4
        private const val SPEAKER_ID = 0
        private const val SPEED = 1.0f
    }

    /**
     * Load the TTS model.
     * Extracts model files from assets and initializes Sherpa-ONNX OfflineTts.
     */
    suspend fun loadModel(backend: TtsBackend = TtsBackend.PIPER): Boolean {
        if (isLoaded && currentEngine == backend && offlineTts != null) return true

        // Unload previous model if switching backends
        if (isLoaded) unloadModel()

        return try {
            currentEngine = backend
            val tts = when (backend) {
                TtsBackend.PIPER -> createPiperTts()
                TtsBackend.KOKORO -> createKokoroTts()
            }

            if (tts == null) {
                Timber.e("Failed to create TTS instance for $backend")
                return false
            }

            offlineTts = tts
            isLoaded = true
            Timber.i("TTS model loaded: $backend")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load TTS model: $backend")
            isLoaded = false
            offlineTts = null
            false
        }
    }

    /**
     * Speak the given text using Sherpa-ONNX TTS.
     * Synthesizes audio and plays it through AudioTrack.
     *
     * @return true if speech completed successfully
     */
    suspend fun speak(text: String, language: String = "sw"): Boolean {
        if (!isLoaded || offlineTts == null) {
            val loaded = loadModel()
            if (!loaded) return false
        }

        isSpeaking = true
        stopRequested = false
        return try {
            val processedText = preprocessForTts(text, language)
            if (processedText.isBlank()) {
                Timber.w("TTS: nothing to speak after preprocessing")
                return true
            }

            val tts = offlineTts!!
            val sampleRate = if (currentEngine == TtsBackend.KOKORO) KOKORO_SAMPLE_RATE else SAMPLE_RATE

            // Generate audio using Sherpa-ONNX
            val audio = tts.generate(
                text = processedText,
                sid = SPEAKER_ID,
                speed = SPEED
            )

            if (stopRequested) {
                Timber.d("TTS: stop requested during synthesis")
                return true
            }

            // audio.samples is FloatArray in range [-1, 1]
            val samples = audio.samples
            if (samples.isEmpty()) {
                Timber.w("TTS: synthesized empty audio")
                return false
            }

            Timber.d("TTS synthesized ${samples.size} samples (${samples.size.toFloat() / sampleRate}s)")

            // Play audio
            playAudioFloat(samples, sampleRate)

            !stopRequested
        } catch (e: Exception) {
            Timber.e(e, "TTS synthesis/playback failed")
            false
        } finally {
            isSpeaking = false
            releaseAudioTrack()
        }
    }

    /**
     * Stop current speech output immediately.
     */
    fun stop() {
        stopRequested = true
        if (isSpeaking) {
            try {
                audioTrack?.pause()
                audioTrack?.flush()
            } catch (e: Exception) {
                Timber.w(e, "Error stopping audio track")
            }
            isSpeaking = false
        }
    }

    /**
     * Check if currently speaking.
     */
    fun isSpeaking(): Boolean = isSpeaking

    /**
     * Check if model is loaded.
     */
    fun isModelLoaded(): Boolean = isLoaded

    /**
     * Unload model to free memory.
     * Releases Sherpa-ONNX OfflineTts instance.
     */
    fun unloadModel() {
        stop()
        if (isLoaded) {
            try {
                offlineTts?.release()
            } catch (e: Exception) {
                Timber.w(e, "Error releasing TTS")
            }
            offlineTts = null
            isLoaded = false
            Timber.i("TTS model unloaded")
        }
    }

    /**
     * Release all resources.
     */
    fun release() {
        stop()
        unloadModel()
        releaseAudioTrack()
    }

    // ── Model initialization ──────────────────────────────────

    /**
     * Create Piper TTS instance from assets.
     * Piper uses VITS architecture with espeak-ng phonemizer.
     */
    private fun createPiperTts(): OfflineTts? {
        val dir = ensureModelFiles(PIPER_ASSET_DIR, listOf(PIPER_MODEL, PIPER_TOKENS)) ?: return null

        val modelPath = File(dir, PIPER_MODEL).absolutePath
        val tokensPath = File(dir, PIPER_TOKENS).absolutePath

        // Ensure espeak-ng data directory exists
        val espeakDir = File(dir, PIPER_DATA_DIR)
        if (!espeakDir.exists()) {
            ensureModelFiles("$PIPER_ASSET_DIR/$PIPER_DATA_DIR", emptyList(), createOnly = true)
        }
        val espeakDataDir = espeakDir.absolutePath

        val modelConfig = OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(
                model = modelPath,
                tokens = tokensPath,
                dataDir = espeakDataDir,
                dictDir = "",
                lexicon = ""
            ),
            numThreads = NUM_THREADS,
            debug = false,
            provider = "cpu"
        )

        val config = OfflineTtsConfig(
            model = modelConfig,
            maxNumSen = 1,  // Process one sentence at a time for lower latency
            ruleFsts = ""
        )

        return OfflineTts(config)
    }

    /**
     * Create Kokoro TTS instance from assets.
     * Kokoro uses StyleTTS 2 architecture for higher quality.
     */
    private fun createKokoroTts(): OfflineTts? {
        val dir = ensureModelFiles(KOKORO_ASSET_DIR, listOf(KOKORO_MODEL, KOKORO_VOICES)) ?: return null

        val modelPath = File(dir, KOKORO_MODEL).absolutePath
        val voicesPath = File(dir, KOKORO_VOICES).absolutePath

        val modelConfig = OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(
                model = modelPath,
                tokens = "",
                dataDir = "",
                dictDir = "",
                lexicon = "",
                voicesBin = voicesPath
            ),
            numThreads = KOKORO_NUM_THREADS,
            debug = false,
            provider = "cpu"
        )

        val config = OfflineTtsConfig(
            model = modelConfig,
            maxNumSen = 1,
            ruleFsts = ""
        )

        return OfflineTts(config)
    }

    // ── Audio playback ────────────────────────────────────────

    /**
     * Play synthesized float audio through Android AudioTrack.
     * Converts float [-1,1] → 16-bit PCM and streams.
     */
    private fun playAudioFloat(samples: FloatArray, sampleRate: Int) {
        val pcmBuffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val clamped = s.coerceIn(-1f, 1f)
            pcmBuffer.putShort((clamped * 32767f).toInt().toShort())
        }
        val pcmBytes = pcmBuffer.array()

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
            .setBufferSizeInBytes(maxOf(bufferSize, pcmBytes.size))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        track.play()

        // Stream PCM data in chunks
        val chunkSize = 4096
        var offset = 0
        while (offset < pcmBytes.size && !stopRequested) {
            val end = min(offset + chunkSize, pcmBytes.size)
            val written = track.write(pcmBytes, offset, end - offset)
            if (written < 0) {
                Timber.e("AudioTrack write error: $written")
                break
            }
            offset += written
        }

        // Wait for playback to finish
        if (!stopRequested) {
            while (track.playbackHeadPosition < samples.size / 2 &&
                   track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                   !stopRequested
            ) {
                Thread.sleep(10)
            }
        }

        track.stop()
    }

    private fun releaseAudioTrack() {
        try {
            audioTrack?.release()
        } catch (e: Exception) {
            Timber.w(e, "Error releasing audio track")
        }
        audioTrack = null
    }

    // ── Text preprocessing ────────────────────────────────────

    /**
     * Pre-process text for TTS:
     * - Remove markdown/emoji
     * - Expand abbreviations
     * - Convert numbers to words (Swahili)
     */
    private fun preprocessForTts(text: String, language: String): String {
        var processed = text

        // Remove emoji (TTS can't speak them)
        processed = processed.replace(Regex("[\\p{So}\\p{Cn}]"), "")

        // Remove markdown artifacts
        processed = processed.replace(Regex("\\*\\*"), "")  // bold
        processed = processed.replace(Regex("\\*"), "")     // italic
        processed = processed.replace(Regex("`"), "")       // code

        // Expand common abbreviations
        processed = processed.replace("KSh", "Kenya Shillings")
        processed = processed.replace("M-Pesa", "Em-Pesa")

        // Convert simple numbers to spoken form
        processed = processed.replace(Regex("(\\d+)")) { match ->
            val num = match.value.toIntOrNull()
            if (num != null && num in 0..100) {
                numberToSwahili(num)
            } else {
                match.value
            }
        }

        // Clean up extra whitespace
        processed = processed.replace(Regex("\\s+"), " ").trim()

        return processed
    }

    /**
     * Convert number to Swahili word.
     */
    private fun numberToSwahili(n: Int): String = when (n) {
        0 -> "sifuri"
        1 -> "moja"; 2 -> "mbili"; 3 -> "tatu"; 4 -> "nne"; 5 -> "tano"
        6 -> "sita"; 7 -> "saba"; 8 -> "nane"; 9 -> "tisa"; 10 -> "kumi"
        11 -> "kumi na moja"; 12 -> "kumi na mbili"; 13 -> "kumi na tatu"; 14 -> "kumi na nne"; 15 -> "kumi na tano"
        16 -> "kumi na sita"; 17 -> "kumi na saba"; 18 -> "kumi na nane"; 19 -> "kumi na tisa"; 20 -> "ishirini"
        30 -> "thelathini"; 40 -> "arobaini"; 50 -> "hamsini"; 60 -> "sitini"; 70 -> "sabini"
        80 -> "themanini"; 90 -> "tisini"; 100 -> "mia moja"
        else -> n.toString()
    }

    // ── Asset extraction ──────────────────────────────────────

    /**
     * Extract model files from APK assets to internal storage.
     * Sherpa-ONNX needs filesystem paths, not asset streams.
     */
    private fun ensureModelFiles(
        assetDir: String,
        requiredFiles: List<String>,
        createOnly: Boolean = false
    ): File? {
        return try {
            val targetDir = File(context.filesDir, assetDir)

            if (!createOnly) {
                val allPresent = requiredFiles.all {
                    File(targetDir, it).exists() && File(targetDir, it).length() > 0
                }
                if (allPresent) {
                    return targetDir
                }
            }

            targetDir.mkdirs()

            if (createOnly) return targetDir

            val assetManager = context.assets
            for (fileName in requiredFiles) {
                val assetPath = "$assetDir/$fileName"
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
