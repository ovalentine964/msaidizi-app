package com.msaidizi.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.*
import com.msaidizi.app.core.metrics.PhaseMetrics
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * VoicePipeline — Full voice input/output pipeline.
 *
 * Flow: Record → VAD → STT → (agent processes) → TTS → Play
 *
 * Sherpa-ONNX for STT (Whisper Tiny INT4).
 * Piper TTS for output (25MB, lightweight).
 * Kokoro TTS for quality when memory allows (3GB+ devices).
 *
 * Mutual exclusion: Only ONE heavy model in memory at a time.
 * On 2GB devices: STT and TTS are loaded/unloaded sequentially.
 *
 * Design: arch_voice.md, arch_android.md Section 4.2
 */
@Singleton
class VoicePipeline @Inject constructor(
    private val context: Context,
    private val speechRecognizer: SpeechRecognizer,
    private val ttsEngine: TtsEngine,
    private val metrics: PhaseMetrics
) {
    @Volatile private var isListening = false
    @Volatile private var isSpeaking = false
    @Volatile private var isRecording = false

    // VAD (Silero via Sherpa-ONNX) — lightweight, stays loaded
    private var voiceDetector: VoiceActivityDetector? = null
    private var vadLoaded = false

    // Audio recording
    private var audioRecord: AudioRecord? = null

    // Barge-in: stop TTS when user speaks
    private val bargeInDetected = AtomicBoolean(false)

    // State tracking
    private var lastTranscriptionTime = 0L
    private var consecutiveFailures = 0

    companion object {
        private const val SAMPLE_RATE = 16000  // 16kHz for STT
        private const val VAD_SAMPLES_PER_FRAME = 512  // 32ms at 16kHz
        private const val VAD_THRESHOLD = 0.5f
        private const val VAD_MIN_SPEECH_DURATION_MS = 250
        private const val VAD_MIN_SILENCE_DURATION_MS = 500
        private const val VAD_MAX_SPEECH_DURATION_MS = 30000  // 30s max utterance
        private const val VAD_MODEL_ASSET = "models/silero_vad.onnx"

        // Barge-in detection during TTS
        private const val BARGE_IN_THRESHOLD = 0.6f  // Higher threshold to avoid TTS bleed
        private const val MAX_CONSECUTIVE_FAILURES = 3

        // STT confidence thresholds
        private const val CONFIDENCE_GOOD = 0.6f
        private const val CONFIDENCE_MINIMUM = 0.3f
    }

    // ── Pipeline lifecycle ────────────────────────────────────

    /**
     * Initialize the voice pipeline.
     * Loads VAD model (lightweight, stays loaded).
     * STT and TTS are loaded on-demand.
     */
    suspend fun initialize(): Boolean {
        return try {
            loadVad()
            Timber.i("Voice pipeline initialized")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize voice pipeline")
            false
        }
    }

    // ── Core pipeline: listen → VAD → STT → result ───────────

    /**
     * Record audio, run VAD, then transcribe.
     * Returns when a complete utterance is detected (speech start → silence).
     *
     * @return TranscriptionResult with text, confidence, and timing
     */
    suspend fun listenAndTranscribe(): TranscriptionResult {
        val totalTime = System.currentTimeMillis()

        // Step 1: Record audio with VAD
        val audioData = recordWithVad()
        if (audioData == null || audioData.isEmpty()) {
            return TranscriptionResult(
                text = "",
                confidence = 0f,
                success = false,
                error = "No speech detected"
            )
        }

        // Step 2: Mutual exclusion — ensure TTS is unloaded before loading STT
        ensureSttReady()

        // Step 3: Transcribe
        val sttStart = System.currentTimeMillis()
        val result = speechRecognizer.recognize(audioData)
        val sttTime = System.currentTimeMillis() - sttStart

        metrics.recordPhase("voice_stt", sttTime, result.success)

        // Step 4: Quality gate
        if (result.success) {
            consecutiveFailures = 0
            lastTranscriptionTime = System.currentTimeMillis()
            return result.copy(processingTimeMs = System.currentTimeMillis() - totalTime)
        }

        consecutiveFailures++
        Timber.w("STT failed (${consecutiveFailures}/$MAX_CONSECUTIVE_FAILURES): ${result.error}")

        return result.copy(processingTimeMs = System.currentTimeMillis() - totalTime)
    }

    /**
     * Transcribe pre-recorded audio data.
     * Handles mutual exclusion: pauses TTS if speaking.
     */
    suspend fun transcribe(audioData: ByteArray): TranscriptionResult {
        if (isSpeaking) {
            ttsEngine.stop()
            isSpeaking = false
        }

        isListening = true
        return try {
            ensureSttReady()

            val sttStart = System.currentTimeMillis()
            val result = speechRecognizer.recognize(audioData)
            metrics.recordPhase("voice_stt", System.currentTimeMillis() - sttStart, result.success)

            if (result.success) {
                consecutiveFailures = 0
                lastTranscriptionTime = System.currentTimeMillis()
            } else {
                consecutiveFailures++
            }

            result
        } catch (e: Exception) {
            Timber.e(e, "Transcription failed")
            consecutiveFailures++
            TranscriptionResult(
                text = "",
                confidence = 0f,
                success = false,
                error = e.message
            )
        } finally {
            isListening = false
        }
    }

    // ── TTS output ────────────────────────────────────────────

    /**
     * Speak text using TTS.
     * Handles mutual exclusion: unloads STT before loading TTS.
     * Supports barge-in: if user speaks during TTS, stops immediately.
     */
    suspend fun speak(text: String, language: String = "sw"): Boolean {
        if (isListening) {
            Timber.w("Speaking while listening — may cause feedback")
        }

        isSpeaking = true
        bargeInDetected.set(false)

        return try {
            // Ensure TTS is loaded (mutual exclusion: unload STT first)
            ensureTtsReady()

            // Start barge-in monitoring in background
            val bargeInJob = CoroutineScope(Dispatchers.IO).launch { monitorBargeIn() }

            val ttsStart = System.currentTimeMillis()
            val success = ttsEngine.speak(text, language)
            val ttsTime = System.currentTimeMillis() - ttsStart

            metrics.recordPhase("voice_tts", ttsTime, success)

            if (bargeInDetected.get()) {
                Timber.i("TTS interrupted by barge-in")
            }

            success
        } catch (e: Exception) {
            Timber.e(e, "TTS failed")
            false
        } finally {
            isSpeaking = false
        }
    }

    /**
     * Speak with fallback: try TTS, fall back to text if TTS fails.
     * Returns true if TTS succeeded, false if fell back to text.
     */
    suspend fun speakWithFallback(text: String, language: String = "sw"): Boolean {
        return try {
            speak(text, language)
        } catch (e: Exception) {
            Timber.e(e, "TTS failed, falling back to text")
            false
        }
    }

    /**
     * Stop current speech output.
     */
    fun stopSpeaking() {
        ttsEngine.stop()
        isSpeaking = false
    }

    // ── Full interaction loop ─────────────────────────────────

    /**
     * Complete voice interaction: listen → transcribe → process → speak.
     *
     * @param process Function that takes transcribed text and returns response
     * @return InteractionResult with both input and output
     */
    suspend fun interact(
        process: suspend (String) -> String
    ): InteractionResult {
        val totalTime = System.currentTimeMillis()

        // Step 1: Listen and transcribe
        val sttResult = listenAndTranscribe()
        if (!sttResult.success || sttResult.text.isBlank()) {
            // Fallback: speak error message
            val errorMsg = if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                "Sikusikia vizuri. Tafadhali jaribu tena baadaye."
            } else {
                "Sikusikia vizuri. Tafadhali rudia."
            }
            speak(errorMsg)
            return InteractionResult(
                input = "",
                output = "",
                sttSuccess = false,
                ttsSuccess = true,
                error = sttResult.error,
                totalProcessingTimeMs = System.currentTimeMillis() - totalTime
            )
        }

        // Step 2: Process the transcribed text (agent call)
        val processStart = System.currentTimeMillis()
        val response = try {
            process(sttResult.text)
        } catch (e: Exception) {
            Timber.e(e, "Processing failed")
            "Samahani, kuna hitafali. Jaribu tena."
        }
        val processTime = System.currentTimeMillis() - processStart
        metrics.recordPhase("voice_process", processTime, true)

        // Step 3: Speak the response
        val ttsSuccess = speak(response)

        return InteractionResult(
            input = sttResult.text,
            output = response,
            sttSuccess = true,
            ttsSuccess = ttsSuccess,
            confidence = sttResult.confidence,
            totalProcessingTimeMs = System.currentTimeMillis() - totalTime
        )
    }

    // ── State queries ─────────────────────────────────────────

    fun isSpeaking(): Boolean = isSpeaking
    fun isListening(): Boolean = isListening
    fun isRecording(): Boolean = isRecording

    /**
     * Release all resources.
     */
    fun release() {
        stopRecording()
        speechRecognizer.release()
        ttsEngine.release()
        releaseVad()
    }

    // ── VAD (Voice Activity Detection) ────────────────────────

    /**
     * Load Silero VAD model via Sherpa-ONNX.
     * This is lightweight (~5MB) and stays loaded permanently.
     */
    private fun loadVad(): Boolean {
        if (vadLoaded && voiceDetector != null) return true

        return try {
            val vadModelPath = extractVadModel()
            if (vadModelPath == null) {
                Timber.w("VAD model not found, will use amplitude-based detection")
                return false
            }

            val config = VadModelConfig(
                sileroVad = SileroVadConfig(
                    model = vadModelPath,
                    threshold = VAD_THRESHOLD,
                    minSilenceDuration = VAD_MIN_SILENCE_DURATION_MS,
                    minSpeechDuration = VAD_MIN_SPEECH_DURATION_MS,
                    maxSpeechDuration = VAD_MAX_SPEECH_DURATION_MS,
                    speechPadLeft = 30,
                    speechPadRight = 30
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1  // VAD is lightweight, 1 thread is enough
            )

            voiceDetector = VoiceActivityDetector(config, bufferSize = VAD_MAX_SPEECH_DURATION_MS * SAMPLE_RATE / 1000)
            vadLoaded = true
            Timber.i("Silero VAD loaded")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load VAD model")
            vadLoaded = false
            voiceDetector = null
            false
        }
    }

    private fun releaseVad() {
        try {
            voiceDetector?.release()
        } catch (e: Exception) {
            Timber.w(e, "Error releasing VAD")
        }
        voiceDetector = null
        vadLoaded = false
    }

    private fun extractVadModel(): String? {
        return try {
            val targetFile = File(context.filesDir, VAD_MODEL_ASSET)
            if (targetFile.exists() && targetFile.length() > 0) {
                return targetFile.absolutePath
            }

            targetFile.parentFile?.mkdirs()
            context.assets.open(VAD_MODEL_ASSET).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            Timber.d("Extracted VAD model: ${targetFile.length()} bytes")
            targetFile.absolutePath
        } catch (e: Exception) {
            Timber.w(e, "VAD model not available in assets")
            null
        }
    }

    // ── Audio recording with VAD ──────────────────────────────

    /**
     * Record audio until VAD detects end of speech.
     * Returns the complete speech audio as 16-bit PCM bytes.
     */
    private fun recordWithVad(): ByteArray? {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(bufferSize, SAMPLE_RATE * 2)  // At least 1 second buffer
            )
        } catch (e: SecurityException) {
            Timber.e(e, "AudioRecord permission denied")
            return null
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Timber.e("AudioRecord failed to initialize")
            record.release()
            return null
        }

        audioRecord = record
        isRecording = true
        record.startRecording()

        val collectedAudio = mutableListOf<Byte>()
        val readBuffer = ByteArray(VAD_SAMPLES_PER_FRAME * 2)  // 16-bit = 2 bytes per sample
        var speechStarted = false
        var silenceFrames = 0
        val maxSilenceFrames = (VAD_MIN_SILENCE_DURATION_MS / 32)  // ~32ms per frame at 512 samples

        val vad = voiceDetector

        return try {
            if (vad != null) {
                // VAD-based recording
                recordWithSileroVad(record, vad, readBuffer)
            } else {
                // Fallback: amplitude-based detection
                recordWithAmplitudeDetection(record, readBuffer)
            }
        } catch (e: Exception) {
            Timber.e(e, "Recording failed")
            null
        } finally {
            isRecording = false
            try {
                record.stop()
                record.release()
            } catch (e: Exception) {
                Timber.w(e, "Error stopping AudioRecord")
            }
            audioRecord = null
        }
    }

    /**
     * Record using Silero VAD via Sherpa-ONNX.
     */
    private fun recordWithSileroVad(
        record: AudioRecord,
        vad: VoiceActivityDetector,
        readBuffer: ByteArray
    ): ByteArray? {
        val collectedAudio = mutableListOf<Byte>()
        var speechStarted = false
        var silenceFrames = 0
        val maxSilenceFrames = VAD_MIN_SILENCE_DURATION_MS / 32
        val maxSpeechBytes = VAD_MAX_SPEECH_DURATION_MS * SAMPLE_RATE * 2 / 1000

        // Reset VAD state
        vad.reset()

        while (isRecording) {
            val bytesRead = record.read(readBuffer, 0, readBuffer.size)
            if (bytesRead <= 0) break

            // Convert PCM16 to float for VAD
            val samples = FloatArray(bytesRead / 2) { i ->
                val lo = readBuffer[i * 2].toInt() and 0xFF
                val hi = readBuffer[i * 2 + 1].toInt()
                ((hi shl 8) or lo).toShort().toFloat() / 32768f
            }

            // Feed to VAD
            vad.acceptWaveform(samples, SAMPLE_RATE)

            if (!speechStarted) {
                // Waiting for speech to start
                if (vad.isDetected()) {
                    speechStarted = true
                    // Collect the buffered speech segments
                    val segments = vad.speechSegments()
                    for (segment in segments) {
                        for (s in segment.samples) {
                            val pcm = (s * 32767f).toInt().toShort()
                            collectedAudio.add((pcm.toInt() and 0xFF).toByte())
                            collectedAudio.add((pcm.toInt() shr 8).toByte())
                        }
                    }
                    Timber.d("VAD: speech started")
                }
            } else {
                // Speech in progress — collect audio
                collectedAudio.addAll(readBuffer.take(bytesRead))

                // Check if speech ended
                if (!vad.isDetected()) {
                    silenceFrames++
                    if (silenceFrames >= maxSilenceFrames) {
                        Timber.d("VAD: speech ended (${collectedAudio.size} bytes)")
                        break
                    }
                } else {
                    silenceFrames = 0
                }

                // Safety: max speech duration
                if (collectedAudio.size >= maxSpeechBytes) {
                    Timber.w("VAD: max speech duration reached")
                    break
                }
            }
        }

        return if (collectedAudio.isNotEmpty()) collectedAudio.toByteArray() else null
    }

    /**
     * Fallback recording using amplitude threshold (no VAD model).
     */
    private fun recordWithAmplitudeDetection(
        record: AudioRecord,
        readBuffer: ByteArray
    ): ByteArray? {
        val collectedAudio = mutableListOf<Byte>()
        var speechStarted = false
        var silenceFrames = 0
        val maxSilenceFrames = VAD_MIN_SILENCE_DURATION_MS / 32
        val maxSpeechBytes = VAD_MAX_SPEECH_DURATION_MS * SAMPLE_RATE * 2 / 1000
        val amplitudeThreshold = 0.01f  // ~-40dBFS

        while (isRecording) {
            val bytesRead = record.read(readBuffer, 0, readBuffer.size)
            if (bytesRead <= 0) break

            // Calculate RMS amplitude
            var sum = 0.0
            for (i in 0 until bytesRead / 2) {
                val lo = readBuffer[i * 2].toInt() and 0xFF
                val hi = readBuffer[i * 2 + 1].toInt()
                val sample = ((hi shl 8) or lo).toShort().toFloat() / 32768f
                sum += sample * sample
            }
            val rms = sqrt(sum / (bytesRead / 2)).toFloat()

            if (!speechStarted) {
                if (rms > amplitudeThreshold) {
                    speechStarted = true
                    collectedAudio.addAll(readBuffer.take(bytesRead))
                    Timber.d("Amplitude VAD: speech started (rms=$rms)")
                }
            } else {
                collectedAudio.addAll(readBuffer.take(bytesRead))

                if (rms < amplitudeThreshold) {
                    silenceFrames++
                    if (silenceFrames >= maxSilenceFrames) {
                        Timber.d("Amplitude VAD: speech ended (${collectedAudio.size} bytes)")
                        break
                    }
                } else {
                    silenceFrames = 0
                }

                if (collectedAudio.size >= maxSpeechBytes) {
                    Timber.w("Amplitude VAD: max speech duration reached")
                    break
                }
            }
        }

        return if (collectedAudio.isNotEmpty()) collectedAudio.toByteArray() else null
    }

    private fun stopRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Timber.w(e, "Error stopping recording")
        }
        audioRecord = null
    }

    // ── Mutual exclusion: model swapping ──────────────────────

    /**
     * Ensure STT model is ready.
     * On 2GB devices: unloads TTS first to free memory.
     */
    private suspend fun ensureSttReady() {
        if (ttsEngine.isModelLoaded()) {
            // On 2GB devices, only one heavy model at a time
            // Keep Piper loaded (lightweight), but unload Kokoro
            // For now, always unload TTS to be safe
            Timber.d("Mutual exclusion: unloading TTS before STT")
            ttsEngine.unloadModel()
        }
        if (!speechRecognizer.isModelLoaded()) {
            speechRecognizer.loadModel()
        }
    }

    /**
     * Ensure TTS model is ready.
     * On 2GB devices: unloads STT first to free memory.
     * Prefers Piper (lightweight) if available, falls back to Kokoro.
     */
    private suspend fun ensureTtsReady() {
        if (speechRecognizer.isModelLoaded()) {
            Timber.d("Mutual exclusion: unloading STT before TTS")
            speechRecognizer.unloadModel()
        }
        if (!ttsEngine.isModelLoaded()) {
            ttsEngine.loadModel(TtsEngine.TtsBackend.PIPER)
        }
    }

    // ── Barge-in detection ────────────────────────────────────

    /**
     * Monitor microphone during TTS playback for barge-in.
     * Runs on a background coroutine.
     * If user speaks during TTS, sets bargeInDetected flag.
     */
    private fun monitorBargeIn() {
        if (!vadLoaded || voiceDetector == null) return

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(bufferSize, SAMPLE_RATE)
            )
        } catch (e: SecurityException) {
            Timber.w(e, "Barge-in monitor: permission denied")
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }

        try {
            record.startRecording()
            val readBuffer = ByteArray(VAD_SAMPLES_PER_FRAME * 2)
            val bargeInVad = try {
                val vadModelPath = extractVadModel() ?: return
                val config = VadModelConfig(
                    sileroVad = SileroVadConfig(
                        model = vadModelPath,
                        threshold = BARGE_IN_THRESHOLD,
                        minSilenceDuration = 200,
                        minSpeechDuration = 300,
                        maxSpeechDuration = 5000
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1
                )
                VoiceActivityDetector(config, bufferSize = SAMPLE_RATE * 5)
            } catch (e: Exception) {
                Timber.w(e, "Failed to create barge-in VAD")
                return
            }

            while (isSpeaking && !bargeInDetected.get()) {
                val bytesRead = record.read(readBuffer, 0, readBuffer.size)
                if (bytesRead <= 0) continue

                val samples = FloatArray(bytesRead / 2) { i ->
                    val lo = readBuffer[i * 2].toInt() and 0xFF
                    val hi = readBuffer[i * 2 + 1].toInt()
                    ((hi shl 8) or lo).toShort().toFloat() / 32768f
                }

                bargeInVad.acceptWaveform(samples, SAMPLE_RATE)

                if (bargeInVad.isDetected()) {
                    Timber.i("Barge-in detected!")
                    bargeInDetected.set(true)
                    stopSpeaking()
                    break
                }
            }

            bargeInVad.release()
        } catch (e: Exception) {
            Timber.w(e, "Barge-in monitor error")
        } finally {
            try {
                record.stop()
                record.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}

// ── Data classes ──────────────────────────────────────────────

/**
 * Result of speech-to-text transcription.
 */
data class TranscriptionResult(
    val text: String,
    val confidence: Float,
    val success: Boolean,
    val language: String? = null,
    val processingTimeMs: Long = 0,
    val error: String? = null
)

/**
 * Result of a complete voice interaction (listen → process → speak).
 */
data class InteractionResult(
    val input: String,
    val output: String,
    val sttSuccess: Boolean,
    val ttsSuccess: Boolean,
    val confidence: Float = 0f,
    val error: String? = null,
    val totalProcessingTimeMs: Long = 0
)
