package com.msaidizi.app.voice.briefing

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.msaidizi.app.cfo.BriefingDelivery
import com.msaidizi.app.cfo.BriefingResult
import com.msaidizi.app.cfo.BriefingType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Delivers audio briefings through WhatsApp and other channels.
 *
 * This is the final mile — the "audio file → worker's ears" step.
 * Follows the Blog-to-Podcast pattern's delivery model:
 *   Generated audio → Distribution channel → Listener
 *
 * Delivery priority:
 * 1. WhatsApp voice note (primary — most natural for illiterate users)
 * 2. Android notification with audio playback action
 * 3. In-app audio player (when app is open)
 * 4. File sharing intent (fallback — user picks channel)
 *
 * WhatsApp integration approach:
 * - Uses Android's share intent with WhatsApp MIME type
 * - Audio file shared as voice note (.wav/.ogg)
 * - Worker receives it like any voice message from a friend
 * - No WhatsApp Business API needed for v1 (local share)
 *
 * Why WhatsApp is primary:
 * - 95%+ smartphone penetration in Kenya
 * - Workers already check WhatsApp daily
 * - Voice notes are culturally natural (mamas send them constantly)
 * - No extra app installation needed
 * - Works offline (queued until connection)
 *
 * @see AudioBriefingGenerator for audio generation
 * @see BriefingDelivery for text briefing generation
 */
@Singleton
class AudioBriefingDelivery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioGenerator: AudioBriefingGenerator
) {
    companion object {
        private const val TAG = "AudioBriefingDelivery"

        /** WhatsApp package name */
        private const val WHATSAPP_PACKAGE = "com.whatsapp"

        /** WhatsApp Business package name */
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
    }

    // ═══════════════════════════════════════════════════════════════
    // MAIN DELIVERY: Generate + Deliver in one call
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate and deliver an audio briefing.
     *
     * Complete pipeline:
     *   BriefingResult → AudioBriefingGenerator → AudioBriefing → WhatsApp/Notification
     *
     * @param briefing Text briefing from BriefingDelivery
     * @param workerName Worker's name
     * @param language Language code
     * @param deliveryChannel Where to send the audio
     * @return DeliveryResult with status and metadata
     */
    suspend fun deliver(
        briefing: BriefingResult,
        workerName: String = "Mfanyabiashara",
        language: String = "sw",
        deliveryChannel: DeliveryChannel = DeliveryChannel.AUTO
    ): DeliveryResult = withContext(Dispatchers.Default) {
        Timber.tag(TAG).d("Delivering audio briefing: type=%s channel=%s",
            briefing.type, deliveryChannel)

        // Generate audio
        val audioBriefing = audioGenerator.generate(briefing, workerName, language)

        if (!audioBriefing.success || !audioBriefing.isReadyForDelivery) {
            Timber.tag(TAG).w("Audio generation failed: %s", audioBriefing.errorMessage)
            return@withContext DeliveryResult(
                success = false,
                channel = deliveryChannel,
                audioBriefing = audioBriefing,
                errorMessage = audioBriefing.errorMessage ?: "Audio generation failed"
            )
        }

        // Select delivery channel
        val resolvedChannel = resolveChannel(deliveryChannel)

        // Deliver based on channel
        val delivered = when (resolvedChannel) {
            DeliveryChannel.WHATSAPP -> deliverViaWhatsApp(audioBriefing, workerName)
            DeliveryChannel.NOTIFICATION -> deliverViaNotification(audioBriefing, briefing)
            DeliveryChannel.IN_APP -> deliverInApp(audioBriefing)
            DeliveryChannel.SHARE -> deliverViaShareIntent(audioBriefing)
            DeliveryChannel.AUTO -> {
                // Try WhatsApp first, fall back to notification
                if (isWhatsAppInstalled()) {
                    deliverViaWhatsApp(audioBriefing, workerName)
                } else {
                    deliverViaNotification(audioBriefing, briefing)
                }
            }
        }

        // Cleanup old audio files
        audioGenerator.cleanupOldBriefings()

        DeliveryResult(
            success = delivered,
            channel = resolvedChannel,
            audioBriefing = audioBriefing,
            errorMessage = if (!delivered) "Delivery failed" else null
        )
    }

    /**
     * Generate audio and return it for manual delivery.
     * Used when the caller wants to handle delivery themselves
     * (e.g., attaching to a WhatsApp message via Business API).
     *
     * @param briefing Text briefing
     * @param workerName Worker's name
     * @param language Language code
     * @return AudioBriefing with file path
     */
    suspend fun generateForDelivery(
        briefing: BriefingResult,
        workerName: String = "Mfanyabiashara",
        language: String = "sw"
    ): AudioBriefing {
        return audioGenerator.generate(briefing, workerName, language)
    }

    // ═══════════════════════════════════════════════════════════════
    // DELIVERY CHANNELS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Deliver audio briefing as WhatsApp voice note.
     *
     * Uses Android share intent to send the WAV file through WhatsApp.
     * The file appears as a voice message in the chat.
     *
     * For WhatsApp Business API integration (future):
     * - Upload audio file to media endpoint
     * - Send audio message to worker's phone number
     * - Track delivery status via webhooks
     */
    private fun deliverViaWhatsApp(audioBriefing: AudioBriefing, workerName: String): Boolean {
        val audioFile = audioBriefing.audioFile ?: return false

        return try {
            val uri = getFileUri(audioFile)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "Sikiliza taarifa yako ya biashara 🎙️")
                setPackage(WHATSAPP_PACKAGE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // Try WhatsApp first, then WhatsApp Business
            if (isPackageInstalled(WHATSAPP_PACKAGE)) {
                intent.setPackage(WHATSAPP_PACKAGE)
            } else if (isPackageInstalled(WHATSAPP_BUSINESS_PACKAGE)) {
                intent.setPackage(WHATSAPP_BUSINESS_PACKAGE)
            } else {
                Timber.tag(TAG).w("WhatsApp not installed")
                return false
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            Timber.tag(TAG).d("Audio briefing shared via WhatsApp: %s", audioFile.name)
            true
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "Failed to deliver via WhatsApp")
            false
        }
    }

    /**
     * Deliver audio briefing as Android notification with playback action.
     *
     * The notification includes:
     * - Title: Briefing type (e.g., "Habari za Asubuhi! ☀️")
     * - Body: First line of briefing text
     * - Action: "Sikiliza" (Listen) button that plays the audio
     * - BigText: Full briefing text for literate users
     *
     * Uses the existing notification channel from BriefingNotificationWorker.
     */
    private fun deliverViaNotification(audioBriefing: AudioBriefing, briefing: BriefingResult): Boolean {
        // The notification is handled by BriefingNotificationWorker.
        // Here we just flag that audio is available.
        // The worker will check for audio file when the notification is tapped.

        Timber.tag(TAG).d("Audio briefing flagged for notification delivery")

        // Store the audio path in shared preferences for the notification worker
        val prefs = context.getSharedPreferences("audio_briefings", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("latest_audio_path", audioBriefing.filePath)
            .putString("latest_audio_type", audioBriefing.briefingType.name)
            .putLong("latest_audio_duration", audioBriefing.durationSeconds.toLong())
            .putLong("latest_audio_timestamp", System.currentTimeMillis())
            .apply()

        return true
    }

    /**
     * Deliver audio for in-app playback.
     * Returns the file path for the UI to play.
     */
    private fun deliverInApp(audioBriefing: AudioBriefing): Boolean {
        // Store path for in-app player to pick up
        val prefs = context.getSharedPreferences("audio_briefings", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("playback_audio_path", audioBriefing.filePath)
            .putLong("playback_timestamp", System.currentTimeMillis())
            .apply()

        Timber.tag(TAG).d("Audio briefing ready for in-app playback: %s", audioBriefing.filePath)
        return true
    }

    /**
     * Deliver via generic share intent — user picks the app.
     * Fallback when WhatsApp is not available.
     */
    private fun deliverViaShareIntent(audioBriefing: AudioBriefing): Boolean {
        val audioFile = audioBriefing.audioFile ?: return false

        return try {
            val uri = getFileUri(audioFile)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "Sikiliza taarifa yako ya biashara 🎙️")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "Tuma audio briefing").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)

            Timber.tag(TAG).d("Audio briefing shared via chooser: %s", audioFile.name)
            true
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "Failed to share audio briefing")
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Resolve AUTO channel to a concrete delivery method.
     */
    private fun resolveChannel(channel: DeliveryChannel): DeliveryChannel {
        if (channel != DeliveryChannel.AUTO) return channel

        return when {
            isWhatsAppInstalled() -> DeliveryChannel.WHATSAPP
            else -> DeliveryChannel.NOTIFICATION
        }
    }

    /**
     * Check if WhatsApp (regular or Business) is installed.
     */
    private fun isWhatsAppInstalled(): Boolean {
        return isPackageInstalled(WHATSAPP_PACKAGE) || isPackageInstalled(WHATSAPP_BUSINESS_PACKAGE)
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Get a content URI for a file (required for sharing on Android 7+).
     */
    private fun getFileUri(file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }
}

/**
 * Delivery channel options for audio briefings.
 */
enum class DeliveryChannel {
    /** Auto-select best available channel */
    AUTO,

    /** WhatsApp voice note (preferred) */
    WHATSAPP,

    /** Android notification with audio action */
    NOTIFICATION,

    /** In-app audio player */
    IN_APP,

    /** Generic share intent (user picks app) */
    SHARE
}

/**
 * Result of audio briefing delivery.
 */
data class DeliveryResult(
    val success: Boolean,
    val channel: DeliveryChannel,
    val audioBriefing: AudioBriefing,
    val errorMessage: String? = null
)
