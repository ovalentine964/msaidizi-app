package com.msaidizi.app.superagent.tools

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VoicePipeline — Speech-to-Text and Text-to-Speech for voice interaction.
 *
 * Handles:
 * - Audio recording from microphone
 * - Language detection (Kiswahili vs English)
 * - STT via on-device Whisper or cloud API
 * - TTS via Android TTS engine or ElevenLabs
 * - Voice Activity Detection (VAD)
 */
@Singleton
class VoicePipeline @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "voice_pipeline"
    override val description = "Voice input/output: speech-to-text and text-to-speech"

    private val _voiceState = MutableStateFlow(VoicePipelineState())
    val voiceState: StateFlow<VoicePipelineState> = _voiceState.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private val audioBuffer = ByteArrayOutputStream()

    // Language detection patterns
    private val swahiliPatterns = listOf(
        "habari", "sawa", "asante", "karibu", "naomba", "ninaomba",
        "nataka", "nitakaa", "naomba", "tafadhali", "pole", "shukrani",
        "nimeuza", "nimenunua", "nimetumia", "nimepata", "bidhaa", "faida",
        "deni", "mteja", "pesa", "stock", "gharama"
    )
    private val englishPatterns = listOf(
        "how much", "what is", "can you", "please", "thank you",
        "sold", "bought", "spent", "profit", "expense", "revenue",
        "customer", "product", "stock", "inventory", "report"
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "listen"
        return when (action.lowercase()) {
            "listen" -> startListening()
            "stop" -> stopListening()
            "speak" -> speak(params["text"] ?: "", params["language"] ?: "sw")
            "detect_language" -> {
                val text = params["text"] ?: return ToolResult.error(name, "Text required", "MISSING_TEXT")
                val lang = detectLanguage(text)
                ToolResult.success(name, data = mapOf("language" to lang), message = "Detected: $lang")
            }
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    /**
     * Start listening for voice input.
     * Returns audio buffer when speech ends (VAD).
     */
    suspend fun startListening(): ToolResult = withContext(Dispatchers.IO) {
        try {
            if (isRecording) {
                return@withContext ToolResult.error(name, "Already listening", "ALREADY_LISTENING")
            }

            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                return@withContext ToolResult.error(name, "Failed to initialize audio recorder", "INIT_ERROR")
            }

            audioBuffer.reset()
            audioRecord?.startRecording()
            isRecording = true
            _voiceState.value = VoicePipelineState(isListening = true)

            Timber.d("Voice recording started")

            // Read audio data
            val buffer = ByteArray(bufferSize)
            var silenceCounter = 0
            val maxSilence = 50 // ~1 second of silence (at 20ms per read)

            while (isRecording) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (bytesRead > 0) {
                    audioBuffer.write(buffer, 0, bytesRead)

                    // Simple VAD: detect silence
                    val amplitude = calculateRMSAmplitude(buffer, bytesRead)
                    if (amplitude < 500) { // Silence threshold
                        silenceCounter++
                        if (silenceCounter > maxSilence) {
                            Timber.d("Voice activity ended (silence detected)")
                            break
                        }
                    } else {
                        silenceCounter = 0
                    }
                }
            }

            val audioData = audioBuffer.toByteArray()
            stopRecording()

            _voiceState.value = VoicePipelineState(isListening = false, isProcessing = true)

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "audio_size" to audioData.size,
                    "sample_rate" to sampleRate,
                    "duration_ms" to (audioData.size / (sampleRate * 2) * 1000)
                ),
                message = "Captured ${audioData.size / (sampleRate * 2)}s of audio"
            )
        } catch (e: Exception) {
            Timber.e(e, "Voice recording failed")
            stopRecording()
            ToolResult.error(name, "Recording failed: ${e.message}", "RECORD_ERROR")
        }
    }

    /**
     * Stop listening.
     */
    suspend fun stopListening(): ToolResult {
        stopRecording()
        _voiceState.value = VoicePipelineState(isListening = false)
        return ToolResult.success(name, message = "Stopped listening")
    }

    /**
     * Speak text using TTS.
     */
    suspend fun speak(text: String, language: String = "sw"): ToolResult = withContext(Dispatchers.IO) {
        try {
            if (text.isBlank()) {
                return@withContext ToolResult.error(name, "No text to speak", "EMPTY_TEXT")
            }

            _voiceState.value = VoicePipelineState(isSpeaking = true)

            // Use Android's built-in TTS
            val tts = android.speech.tts.TextToSpeech(context) { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    Timber.d("TTS initialized")
                }
            }

            // Set language
            val locale = when (language) {
                "sw" -> java.util.Locale("sw", "KE")
                "en" -> java.util.Locale("en", "KE")
                else -> java.util.Locale("sw", "KE")
            }
            tts.language = locale
            tts.setSpeechRate(0.9f) // Slightly slower for clarity

            // Speak
            tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "msaidizi_tts")

            // Wait for completion (simplified — in production use OnUtteranceCompletedListener)
            kotlinx.coroutines.delay(text.length * 80L) // Rough estimate

            tts.shutdown()
            _voiceState.value = VoicePipelineState(isSpeaking = false)

            ToolResult.success(name, message = "Spoke: ${text.take(50)}...")
        } catch (e: Exception) {
            Timber.e(e, "TTS failed")
            _voiceState.value = VoicePipelineState(isSpeaking = false)
            ToolResult.error(name, "TTS failed: ${e.message}", "TTS_ERROR")
        }
    }

    /**
     * Detect language of input text.
     * Returns "sw" (Kiswahili), "en" (English), or "sheng" (Sheng).
     */
    fun detectLanguage(text: String): String {
        val lower = text.lowercase()
        val words = lower.split(Regex("\\s+"))

        val swahiliScore = words.count { it in swahiliPatterns } + swahiliPatterns.count { lower.contains(it) }
        val englishScore = words.count { it in englishPatterns } + englishPatterns.count { lower.contains(it) }

        // Check for Sheng (mix of Swahili and English slang)
        val shengPatterns = listOf("sasa", "niaje", "mambo", "vipi", "poa", "sijui", "ata", "juu")
        val shengScore = words.count { it in shengPatterns }

        return when {
            shengScore > 1 -> "sheng"
            swahiliScore > englishScore -> "sw"
            englishScore > swahiliScore -> "en"
            else -> "sw" // Default to Swahili
        }
    }

    /**
     * Process audio buffer through STT.
     * This is a placeholder — in production, use Whisper.cpp or cloud STT.
     */
    suspend fun processSTT(audioData: ByteArray, language: String = "sw"): ToolResult {
        // In production: send to on-device Whisper model or cloud API
        // For now, return a placeholder
        return ToolResult.success(
            toolName = name,
            data = mapOf("audio_size" to audioData.size, "language" to language),
            message = "STT processing (${audioData.size} bytes, lang=$language)"
        )
    }

    private fun calculateRMSAmplitude(buffer: ByteArray, length: Int): Double {
        var sum = 0.0
        val shortBuffer = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val sampleCount = length / 2
        for (i in 0 until sampleCount) {
            val sample = shortBuffer.getShort().toDouble()
            sum += sample * sample
        }
        return kotlin.math.sqrt(sum / sampleCount)
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
}

data class VoicePipelineState(
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val isProcessing: Boolean = false,
    val partialText: String = "",
    val error: String? = null
)
