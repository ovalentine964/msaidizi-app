package com.msaidizi.core.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.msaidizi.core.voice.registry.VoiceModelRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Piper TTS engine for Swahili text-to-speech using Sherpa-ONNX.
 *
 * Piper is a fast, lightweight TTS system optimized for on-device use.
 * The Swahili model (~25MB) produces intelligible speech with low latency,
 * suitable for Msaidizi's voice feedback loop.
 *
 * ## Why Piper for Msaidizi
 * - Small model size (~25MB) — fits on 2GB phones alongside Whisper
 * - Fast synthesis — <500ms for typical response sentences
 * - Sherpa-ONNX JNI handles phonemization and vocoding internally
 * - Quality is "acceptable" — not human-like, but clear enough for business data
 *
 * ## Memory Footprint
 * - Model: ~25MB RAM when loaded
 * - Audio buffer: ~1MB for 10s of speech
 * - Total: ~26MB — well within BASIC tier budget
 *
 * @see KokoroTtsEngine for higher-quality TTS on STANDARD+ devices
 */
@Singleton
class PiperTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRegistry: VoiceModelRegistry
) : TextToSpeech {

    override val name = "Piper"

    companion object {
        private const val SAMPLE_RATE = 22050  // Piper default output sample rate
        private const val DEFAULT_SPEED = 1.0f
    }

    // ── Sherpa-ONNX TTS engine (JNI) ──
    private var ttsEngine: com.k2fsa.sherpa.onnx.OfflineTts? = null
    private var isLoaded = false
    private var isCurrentlySpeaking = false
    private var currentAudioTrack: AudioTrack? = null

    override fun isModelReady(): Boolean = isLoaded
    override fun isSpeaking(): Boolean = isCurrentlySpeaking

    /**
     * Load the Piper Swahili TTS model via Sherpa-ONNX.
     *
     * Sherpa-ONNX handles phonemization and vocoding internally —
     * no need for manual phoneme mapping or espeak-ng integration.
     *
     * @return true if model loaded successfully
     */
    override suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext true

        val modelFile = modelRegistry.getModelPath("piper-swahili")
        if (modelFile == null) {
            Timber.w("PiperTtsEngine: Piper TTS model not found on disk")
            return@withContext false
        }

        try {
            val startTime = System.currentTimeMillis()

            val vitsConfig = com.k2fsa.sherpa.onnx.VitsModelConfig(
                model = modelFile.absolutePath,
                tokens = findTokensFile(modelFile) ?: "",
                dataDir = findEspeakDataDir() ?: ""
            )
            val modelConfig = com.k2fsa.sherpa.onnx.TtsModelConfig(
                vits = vitsConfig,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
            val config = com.k2fsa.sherpa.onnx.TtsConfig(
                model = modelConfig,
                maxNumSentences = 1
            )

            ttsEngine = com.k2fsa.sherpa.onnx.OfflineTts(config)
            isLoaded = true

            val elapsed = System.currentTimeMillis() - startTime
            Timber.i("PiperTtsEngine: Loaded in %dms (sampleRate=%d)", elapsed, ttsEngine?.getSampleRate() ?: 0)
            true
        } catch (e: OutOfMemoryError) {
            Timber.e("PiperTtsEngine: OOM loading TTS")
            unloadModel()
            System.gc()
            false
        } catch (e: Throwable) {
            Timber.e(e, "PiperTtsEngine: Failed to load TTS")
            unloadModel()
            false
        }
    }

    override fun unloadModel() {
        stop()
        ttsEngine?.close()
        ttsEngine = null
        isLoaded = false
        Timber.d("PiperTtsEngine: Unloaded")
    }

    /**
     * Speak text using Piper TTS.
     *
     * Pipeline: text → sherpa-onnx phonemizer → VITS vocoder → PCM → AudioTrack
     *
     * @param text Text to speak
     * @param language Language code (unused — Piper Swahili is single-language)
     */
    override suspend fun speak(text: String, language: String) = withContext(Dispatchers.Default) {
        if (!isLoaded) {
            val loaded = loadModel()
            if (!loaded) {
                Timber.w("PiperTtsEngine: Cannot speak — model not loaded")
                return@withContext
            }
        }

        if (text.isBlank()) return@withContext

        isCurrentlySpeaking = true
        try {
            val engine = ttsEngine ?: return@withContext
            val startTime = System.currentTimeMillis()

            // Generate audio — sherpa-onnx handles phonemization + vocoding
            val audioData = engine.generate(text, sid = 0, speed = DEFAULT_SPEED)

            val inferenceTime = System.currentTimeMillis() - startTime
            Timber.d("PiperTtsEngine: Generated %d samples (%.1fs) in %dms",
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
            Timber.e("PiperTtsEngine: OOM during synthesis")
            unloadModel()
            System.gc()
        } catch (e: Throwable) {
            Timber.e(e, "PiperTtsEngine: Synthesis error")
        } finally {
            isCurrentlySpeaking = false
        }
    }

    override fun stop() {
        currentAudioTrack?.let { track ->
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                }
                track.release()
            } catch (_: Throwable) {}
        }
        currentAudioTrack = null
        isCurrentlySpeaking = false
    }

    // ── Audio playback ──

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

            // Wait for playback to finish
            while (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING
                && audioTrack.playbackHeadPosition < pcm.size
                && isCurrentlySpeaking
            ) {
                delay(20)
            }
        } catch (e: Throwable) {
            Timber.e(e, "PiperTtsEngine: AudioTrack error")
        } finally {
            try { audioTrack.stop(); audioTrack.release() } catch (_: Throwable) {}
            if (currentAudioTrack == audioTrack) currentAudioTrack = null
        }
    }

    // ── File resolution helpers ──

    private fun findTokensFile(modelFile: File): String? {
        val candidates = listOf(
            File(modelFile.parent, "tokens.txt"),
            File(modelFile.parent, "piper-tokens.txt"),
            File(modelFile.parent, "${modelFile.nameWithoutExtension}-tokens.txt")
        )
        return candidates.firstOrNull { it.exists() }?.absolutePath
    }

    private fun findEspeakDataDir(): String? {
        val candidates = listOf(
            File(context.filesDir, "models/espeak-ng-data"),
            File(context.filesDir, "espeak-ng-data")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }?.absolutePath
    }
}
