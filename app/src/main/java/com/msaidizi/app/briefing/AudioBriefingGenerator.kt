package com.msaidizi.app.voice.briefing

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.msaidizi.app.cfo.BriefingResult
import com.msaidizi.app.cfo.BriefingType
import com.msaidizi.app.voice.KokoroTtsEngine
import com.msaidizi.app.voice.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audio Briefing Generator — wires Msaidizi's financial briefings to audio output.
 *
 * Implements the Blog-to-Podcast pattern:
 *   Financial Data → Briefing Text → Natural Swahili Script → TTS → Audio File
 *
 * Pipeline (mirrors blog_to_podcast_agent.py):
 *   1. CFOEngine generates BriefingResult (text)
 *   2. AudioBriefingTextTransformer converts to spoken Swahili
 *   3. KokoroTtsEngine synthesizes to PCM audio
 *   4. PCM encoded to WAV/OGG for delivery
 *   5. AudioBriefingDelivery sends via WhatsApp / notification
 *
 * Audio delivery targets:
 * - WhatsApp voice note (primary — most natural for illiterate users)
 * - Android notification with inline playback
 * - In-app audio player
 * - SMS with audio link (fallback)
 *
 * Voice personality: warm, encouraging "big sister" voice.
 * Uses Kokoro VOICE_EXCITED for good news, VOICE_EMPATHETIC for warnings.
 *
 * Performance targets (Helio G25, 2GB RAM):
 * - Daily briefing audio: <3 seconds to generate
 * - Audio file size: <200KB for 60-second briefing
 * - Memory: reuses existing TTS engine (no extra model load)
 *
 * @see AudioBriefingTextTransformer for text transformation
 * @see AudioBriefingDelivery for delivery channels
 */
@Singleton
class AudioBriefingGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val textTransformer: AudioBriefingTextTransformer,
    private val kokoroTts: KokoroTtsEngine,
    private val piperTts: TextToSpeech
) {
    companion object {
        private const val TAG = "AudioBriefingGenerator"
        private const val AUDIO_DIR = "audio_briefings"
        private const val WAV_SAMPLE_RATE = 24000  // Kokoro output rate
        private const val WAV_CHANNELS = 1         // Mono
        private const val WAV_BITS_PER_SAMPLE = 16 // 16-bit PCM

        /** Maximum audio duration before truncation (seconds) */
        private const val MAX_DURATION_SECONDS = 120

        /** Speed for briefing speech (slightly slower than conversation for clarity) */
        private const val BRIEFING_SPEED = 0.9f
    }

    // Track the current playback for cancellation
    private var currentJob: Job? = null

    // ═══════════════════════════════════════════════════════════════
    // CORE: Generate Audio from BriefingResult
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate an audio briefing from a BriefingResult.
     *
     * This is the main entry point — the "Blog → Podcast" equivalent.
     * Takes the text briefing from CFOEngine and produces a playable audio file.
     *
     * @param briefing BriefingResult from BriefingDelivery
     * @param workerName Worker's name for personalization
     * @param language Language code ("sw", "en")
     * @return AudioBriefing with file path, duration, and metadata
     */
    suspend fun generate(
        briefing: BriefingResult,
        workerName: String = "Mfanyabiashara",
        language: String = "sw"
    ): AudioBriefing = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        Timber.tag(TAG).d("Generating audio briefing: type=%s, textLen=%d",
            briefing.type, briefing.message.length)

        // Step 1: Transform text for natural speech
        val audioType = when (briefing.type) {
            BriefingType.MORNING -> BriefingAudioType.DAILY
            BriefingType.EVENING -> BriefingAudioType.EVENING
            BriefingType.WEEKLY -> BriefingAudioType.WEEKLY
            BriefingType.ALERT -> BriefingAudioType.ALERT
        }

        val spokenText = textTransformer.transform(
            text = briefing.message,
            briefingType = audioType,
            workerName = workerName
        )

        Timber.tag(TAG).d("Transformed text: %d chars → %d chars", briefing.message.length, spokenText.length)

        // Step 2: Select voice personality based on briefing content
        val voiceId = selectVoice(briefing, audioType)

        // Step 3: Synthesize to PCM audio
        val pcmData = synthesizeSpeech(spokenText, language, voiceId)

        if (pcmData.isEmpty()) {
            Timber.tag(TAG).w("TTS synthesis returned empty audio")
            return@withContext AudioBriefing(
                filePath = null,
                durationSeconds = 0.0,
                briefingType = audioType,
                text = spokenText,
                success = false,
                errorMessage = "TTS synthesis failed"
            )
        }

        // Step 4: Save as WAV file
        val audioFile = saveAsWav(pcmData, briefing.type.name.lowercase())

        val elapsed = System.currentTimeMillis() - startTime
        val durationSeconds = pcmData.size.toDouble() / WAV_SAMPLE_RATE

        Timber.tag(TAG).i(
            "Audio briefing generated: %.1fs audio in %dms (%s)",
            durationSeconds, elapsed, audioFile?.name
        )

        AudioBriefing(
            filePath = audioFile?.absolutePath,
            durationSeconds = durationSeconds,
            briefingType = audioType,
            text = spokenText,
            success = audioFile != null,
            generationTimeMs = elapsed
        )
    }

    /**
     * Generate audio briefing directly from raw data (no BriefingResult needed).
     * Useful for custom briefings or when CFOEngine isn't available.
     *
     * @param text Raw text to convert to audio
     * @param type Briefing type for voice selection
     * @param workerName Worker's name
     * @param language Language code
     * @return AudioBriefing
     */
    suspend fun generateFromText(
        text: String,
        type: BriefingAudioType = BriefingAudioType.DAILY,
        workerName: String = "Mfanyabiashara",
        language: String = "sw"
    ): AudioBriefing = withContext(Dispatchers.Default) {
        val spokenText = textTransformer.transform(text, type, workerName)
        val voiceId = selectVoiceForType(type)
        val pcmData = synthesizeSpeech(spokenText, language, voiceId)

        if (pcmData.isEmpty()) {
            return@withContext AudioBriefing(
                filePath = null,
                durationSeconds = 0.0,
                briefingType = type,
                text = spokenText,
                success = false,
                errorMessage = "TTS synthesis failed"
            )
        }

        val audioFile = saveAsWav(pcmData, type.name.lowercase())
        val durationSeconds = pcmData.size.toDouble() / WAV_SAMPLE_RATE

        AudioBriefing(
            filePath = audioFile?.absolutePath,
            durationSeconds = durationSeconds,
            briefingType = type,
            text = spokenText,
            success = audioFile != null
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // PLAYBACK: Play audio briefing directly
    // ═══════════════════════════════════════════════════════════════

    /**
     * Play a briefing's audio directly through the device speaker.
     * Uses the existing TTS engine for streaming playback.
     *
     * @param briefing BriefingResult to speak
     * @param workerName Worker's name
     * @param language Language code
     */
    suspend fun speakBriefing(
        briefing: BriefingResult,
        workerName: String = "Mfanyabiashara",
        language: String = "sw"
    ) = withContext(Dispatchers.Default) {
        val audioType = when (briefing.type) {
            BriefingType.MORNING -> BriefingAudioType.DAILY
            BriefingType.EVENING -> BriefingAudioType.EVENING
            BriefingType.WEEKLY -> BriefingAudioType.WEEKLY
            BriefingType.ALERT -> BriefingAudioType.ALERT
        }

        val spokenText = textTransformer.transform(briefing.message, audioType, workerName)
        val voiceId = selectVoice(briefing, audioType)

        // Set voice personality
        kokoroTts.setVoice(voiceId)
        kokoroTts.setSpeed(BRIEFING_SPEED)

        // Speak with streaming (sentence by sentence for lower latency)
        currentJob = coroutineContext[Job]
        kokoroTts.speakStreaming(spokenText, language)
    }

    /**
     * Stop current audio playback.
     */
    fun stopPlayback() {
        currentJob?.cancel()
        kokoroTts.stop()
        piperTts.stop()
    }

    // ═══════════════════════════════════════════════════════════════
    // VOICE PERSONALITY: Select the right voice for the briefing
    // ═══════════════════════════════════════════════════════════════

    /**
     * Select voice personality based on briefing content and type.
     *
     * Good news (profit up, milestones) → VOICE_EXCITED (energetic, celebratory)
     * Warnings (low stock, cash flow) → VOICE_PROFESSIONAL (clear, authoritative)
     * Neutral/analytical → VOICE_DEFAULT (warm, balanced)
     * Bad news (losses, decline) → VOICE_EMPATHETIC (slow, comforting)
     */
    private fun selectVoice(briefing: BriefingResult, type: BriefingAudioType): Int {
        // Alerts get professional voice for clarity
        if (type == BriefingAudioType.ALERT) {
            return KokoroTtsEngine.VOICE_PROFESSIONAL
        }

        // Check content for sentiment signals
        val message = briefing.message.lowercase()

        // Positive signals → excited/celebratory
        val positiveWords = listOf("hongera", "nzuri", "imepanda", "faida", "ongezeko",
            "alama", "milestone", "streak", "level")
        if (positiveWords.any { message.contains(it) }) {
            return KokoroTtsEngine.VOICE_EXCITED
        }

        // Negative signals → empathetic/comforting
        val negativeWords = listOf("hasara", "imeshuka", "tahadhari", "hatari",
            "ushauri", "angalia", "kwa pole")
        if (negativeWords.any { message.contains(it) }) {
            return KokoroTtsEngine.VOICE_EMPATHETIC
        }

        // Weekly/monthly reports → professional (analytical)
        if (type == BriefingAudioType.WEEKLY || type == BriefingAudioType.MONTHLY) {
            return KokoroTtsEngine.VOICE_PROFESSIONAL
        }

        return KokoroTtsEngine.VOICE_DEFAULT
    }

    private fun selectVoiceForType(type: BriefingAudioType): Int {
        return when (type) {
            BriefingAudioType.DAILY -> KokoroTtsEngine.VOICE_DEFAULT
            BriefingAudioType.EVENING -> KokoroTtsEngine.VOICE_EMPATHETIC
            BriefingAudioType.WEEKLY -> KokoroTtsEngine.VOICE_PROFESSIONAL
            BriefingAudioType.ALERT -> KokoroTtsEngine.VOICE_PROFESSIONAL
            BriefingAudioType.MONTHLY -> KokoroTtsEngine.VOICE_PROFESSIONAL
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TTS SYNTHESIS: Text → PCM audio
    // ═══════════════════════════════════════════════════════════════

    /**
     * Synthesize text to PCM audio using Kokoro (preferred) or Piper (fallback).
     *
     * @param text Text to synthesize
     * @param language Language code
     * @param voiceId Kokoro voice personality ID
     * @return Raw PCM samples at WAV_SAMPLE_RATE, empty on failure
     */
    private suspend fun synthesizeSpeech(
        text: String,
        language: String,
        voiceId: Int
    ): ShortArray {
        // Try Kokoro first (better quality)
        if (kokoroTts.isModelReady()) {
            kokoroTts.setVoice(voiceId)
            kokoroTts.setSpeed(BRIEFING_SPEED)
            val pcm = kokoroTts.synthesizeToPcm(text, language)
            if (pcm.isNotEmpty()) {
                Timber.tag(TAG).d("Kokoro synthesized %d samples (%.1fs)",
                    pcm.size, pcm.size.toDouble() / WAV_SAMPLE_RATE)
                return pcm
            }
        }

        // Fallback to Piper
        Timber.tag(TAG).d("Falling back to Piper TTS")
        if (piperTts.isModelReady()) {
            piperTts.setSpeed(BRIEFING_SPEED)
            val pcm = piperTts.synthesizeToPcm(text, language)
            if (pcm.isNotEmpty()) {
                Timber.tag(TAG).d("Piper synthesized %d samples (%.1fs)",
                    pcm.size, pcm.size.toDouble() / 22050)  // Piper is 22050Hz
                return pcm
            }
        }

        Timber.tag(TAG).w("No TTS engine available for synthesis")
        return ShortArray(0)
    }

    // ═══════════════════════════════════════════════════════════════
    // FILE OUTPUT: PCM → WAV file
    // ═══════════════════════════════════════════════════════════════

    /**
     * Save PCM audio data as a WAV file for delivery.
     *
     * WAV format is chosen because:
     * - Simple to generate (no complex codec)
     - Compatible with WhatsApp voice notes (auto-converted)
     * - Works with Android notification sound
     * - No licensing issues (vs MP3)
     *
     * @param pcmData Raw 16-bit PCM samples
     * @param prefix Filename prefix (e.g., "morning", "weekly")
     * @return File reference, or null on failure
     */
    private fun saveAsWav(pcmData: ShortArray, prefix: String): File? {
        return try {
            val audioDir = File(context.filesDir, AUDIO_DIR)
            if (!audioDir.exists()) audioDir.mkdirs()

            val timestamp = System.currentTimeMillis()
            val file = File(audioDir, "briefing_${prefix}_$timestamp.wav")

            // Limit duration to MAX_DURATION_SECONDS
            val maxSamples = WAV_SAMPLE_RATE * MAX_DURATION_SECONDS
            val samplesToWrite = pcmData.size.coerceAtMost(maxSamples)

            FileOutputStream(file).use { fos ->
                // WAV header
                val dataSize = samplesToWrite * 2  // 16-bit = 2 bytes per sample
                val fileSize = 36 + dataSize

                fos.write("RIFF".toByteArray())
                fos.write(intToLittleEndian(fileSize))
                fos.write("WAVE".toByteArray())

                // Format chunk
                fos.write("fmt ".toByteArray())
                fos.write(intToLittleEndian(16))                // Chunk size
                fos.write(shortToLittleEndian(1))               // PCM format
                fos.write(shortToLittleEndian(WAV_CHANNELS))
                fos.write(intToLittleEndian(WAV_SAMPLE_RATE))
                fos.write(intToLittleEndian(WAV_SAMPLE_RATE * WAV_CHANNELS * WAV_BITS_PER_SAMPLE / 8))
                fos.write(shortToLittleEndian(WAV_CHANNELS * WAV_BITS_PER_SAMPLE / 8))
                fos.write(shortToLittleEndian(WAV_BITS_PER_SAMPLE))

                // Data chunk
                fos.write("data".toByteArray())
                fos.write(intToLittleEndian(dataSize))

                // PCM data (little-endian 16-bit)
                val buffer = ByteBuffer.allocate(samplesToWrite * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until samplesToWrite) {
                    buffer.putShort(pcmData[i])
                }
                fos.write(buffer.array())
            }

            Timber.tag(TAG).d("Saved audio briefing: %s (%.1fKB)",
                file.name, file.length() / 1024.0)
            file
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to save WAV file")
            null
        }
    }

    /**
     * Delete old audio briefing files to save storage.
     * Keeps the most recent [keepCount] files.
     */
    fun cleanupOldBriefings(keepCount: Int = 10) {
        try {
            val audioDir = File(context.filesDir, AUDIO_DIR)
            if (!audioDir.exists()) return

            val files = audioDir.listFiles()
                ?.filter { it.name.startsWith("briefing_") && it.name.endsWith(".wav") }
                ?.sortedByDescending { it.lastModified() }

            if (files != null && files.size > keepCount) {
                files.drop(keepCount).forEach { file ->
                    if (file.delete()) {
                        Timber.tag(TAG).d("Cleaned up old briefing: %s", file.name)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to clean up old briefings")
        }
    }

    /**
     * Get the directory where audio briefings are stored.
     */
    fun getAudioBriefingDir(): File {
        return File(context.filesDir, AUDIO_DIR).also {
            if (!it.exists()) it.mkdirs()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // BYTE HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun intToLittleEndian(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun shortToLittleEndian(value: Short): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
    }

    private fun shortToLittleEndian(value: Int): ByteArray {
        return shortToLittleEndian(value.toShort())
    }
}

/**
 * Result of audio briefing generation.
 */
data class AudioBriefing(
    /** Path to the generated WAV file, null if generation failed */
    val filePath: String?,

    /** Duration of the audio in seconds */
    val durationSeconds: Double,

    /** Type of briefing for voice personality */
    val briefingType: BriefingAudioType,

    /** The transformed text that was synthesized */
    val text: String,

    /** Whether generation succeeded */
    val success: Boolean,

    /** Error message if generation failed */
    val errorMessage: String? = null,

    /** Time taken to generate in milliseconds */
    val generationTimeMs: Long = 0
) {
    /** Get the audio file for delivery */
    val audioFile: File?
        get() = filePath?.let { File(it) }

    /** Check if audio file exists and is valid */
    val isReadyForDelivery: Boolean
        get() = success && audioFile?.exists() == true && (audioFile?.length() ?: 0) > 44
}
