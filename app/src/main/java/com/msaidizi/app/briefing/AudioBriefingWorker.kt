package com.msaidizi.app.voice.briefing

import android.content.Context
import androidx.work.*
import com.msaidizi.app.MsaidiziApp
import com.msaidizi.app.agent.WorkerType
import com.msaidizi.app.cfo.BriefingDelivery
import com.msaidizi.app.cfo.BriefingType
import com.msaidizi.app.loops.BriefingNotificationWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager worker that generates and delivers AUDIO briefings.
 *
 * This extends the existing BriefingNotificationWorker pattern:
 *   BriefingNotificationWorker → text notification
 *   AudioBriefingWorker → audio notification + WhatsApp voice note
 *
 * Scheduling:
 * - Morning audio briefing: 7:05 AM EAT (5 min after text, to avoid overlap)
 * - Evening audio summary: 7:05 PM EAT
 * - Weekly audio report: Monday 8:05 AM EAT
 *
 * The 5-minute delay after text briefings ensures:
 * 1. Text briefing is delivered first (fast, always works)
 * 2. Audio briefing follows (may take a few seconds to generate)
 * 3. If audio fails, worker still has the text briefing
 *
 * Flow:
 *   7:00 AM → Text briefing notification (existing)
 *   7:05 AM → Audio briefing generated → WhatsApp voice note + notification
 *
 * This is the Blog-to-Podcast wiring:
 *   CFOEngine (blog) → BriefingDelivery (text) → AudioBriefingGenerator (podcast)
 *
 * @see BriefingNotificationWorker for the text-based equivalent
 * @see AudioBriefingGenerator for the audio generation pipeline
 * @see AudioBriefingDelivery for the delivery channels
 */
class AudioBriefingWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "AudioBriefingWorker"
        private const val KEY_BRIEFING_TYPE = "audio_briefing_type"

        /**
         * Schedule all audio briefing workers.
         * Call after onboarding or from Application.onCreate().
         */
        fun scheduleAllAudioBriefings(context: Context) {
            scheduleMorningAudio(context)
            scheduleEveningAudio(context)
            scheduleWeeklyAudio(context)
            Timber.i(TAG, "All audio briefings scheduled")
        }

        /**
         * Cancel all audio briefing workers.
         */
        fun cancelAllAudioBriefings(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("morning_audio_briefing")
            WorkManager.getInstance(context).cancelUniqueWork("evening_audio_briefing")
            WorkManager.getInstance(context).cancelUniqueWork("weekly_audio_briefing")
            Timber.i(TAG, "All audio briefings cancelled")
        }

        /**
         * Schedule morning audio briefing at 7:05 AM EAT.
         */
        private fun scheduleMorningAudio(context: Context) {
            scheduleDaily(context, "morning_audio_briefing", 7, 5, "MORNING")
        }

        /**
         * Schedule evening audio summary at 7:05 PM EAT.
         */
        private fun scheduleEveningAudio(context: Context) {
            scheduleDaily(context, "evening_audio_briefing", 19, 5, "EVENING")
        }

        /**
         * Schedule weekly audio report on Monday at 8:05 AM EAT.
         */
        private fun scheduleWeeklyAudio(context: Context) {
            val now = ZonedDateTime.now(ZoneId.of("Africa/Nairobi"))
            val nextMonday = now.toLocalDate()
                .with(java.time.DayOfWeek.MONDAY)
                .atTime(8, 5)
                .atZone(ZoneId.of("Africa/Nairobi"))
            val delay = Duration.between(now, nextMonday).toMillis().coerceAtLeast(0)

            val inputData = workDataOf(KEY_BRIEFING_TYPE to "WEEKLY")

            val request = PeriodicWorkRequestBuilder<AudioBriefingWorker>(
                7, TimeUnit.DAYS
            )
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(inputData)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .addTag("weekly_audio_briefing")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "weekly_audio_briefing",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        private fun scheduleDaily(
            context: Context,
            workName: String,
            hour: Int,
            minute: Int,
            briefingType: String
        ) {
            val now = ZonedDateTime.now(ZoneId.of("Africa/Nairobi"))
            var target = now.toLocalDate().atTime(hour, minute).atZone(ZoneId.of("Africa/Nairobi"))
            if (now.isAfter(target)) {
                target = target.plusDays(1)
            }
            val delay = Duration.between(now, target).toMillis()

            val inputData = workDataOf(KEY_BRIEFING_TYPE to briefingType)

            val request = PeriodicWorkRequestBuilder<AudioBriefingWorker>(
                1, TimeUnit.DAYS
            )
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(inputData)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .addTag(workName)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                workName,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        val briefingType = inputData.getString(KEY_BRIEFING_TYPE) ?: "MORNING"
        Timber.tag(TAG).d("AudioBriefingWorker: Starting %s audio briefing", briefingType)

        val app = applicationContext as? MsaidiziApp
            ?: return Result.failure(workDataOf("error" to "Not MsaidiziApp"))

        val prefs = applicationContext.getSharedPreferences("worker_profile", Context.MODE_PRIVATE)
        val workerName = prefs.getString("worker_name", "Mfanyabiashara") ?: "Mfanyabiashara"
        val agentName = prefs.getString("msaidizi_name", "Msaidizi") ?: "Msaidizi"
        val workerTypeName = prefs.getString("business_type", "UNKNOWN") ?: "UNKNOWN"
        val language = prefs.getString("language", "sw") ?: "sw"
        val workerType = try {
            WorkerType.valueOf(workerTypeName)
        } catch (_: Exception) {
            WorkerType.UNKNOWN
        }

        return try {
            val briefingDelivery = app.briefingDelivery
                ?: return Result.failure(workDataOf("error" to "BriefingDelivery not available"))

            val audioDelivery = app.audioBriefingDelivery
                ?: return Result.failure(workDataOf("error" to "AudioBriefingDelivery not available"))

            // Generate the text briefing first (reuses existing infrastructure)
            val textBriefing = when (briefingType) {
                "MORNING" -> briefingDelivery.deliverMorningBriefing(
                    workerName = workerName,
                    assistantName = agentName,
                    workerType = workerType,
                    todayTransactions = emptyList(),
                    yesterdayTransactions = emptyList(),
                    recentTransactions = emptyList()
                )
                "EVENING" -> briefingDelivery.deliverEveningSummary(
                    workerName = workerName,
                    todayTransactions = emptyList()
                )
                else -> return Result.failure(workDataOf("error" to "Unsupported type: $briefingType"))
            }

            // Generate and deliver audio
            val result = audioDelivery.deliver(
                briefing = textBriefing,
                workerName = workerName,
                language = language,
                deliveryChannel = DeliveryChannel.AUTO
            )

            if (result.success) {
                Timber.tag(TAG).i(
                    "%s audio briefing delivered via %s (%.1fs audio)",
                    briefingType, result.channel, result.audioBriefing.durationSeconds
                )
                Result.success()
            } else {
                Timber.tag(TAG).w("Audio briefing delivery failed: %s", result.errorMessage)
                // Don't retry — text briefing was already delivered as fallback
                Result.success()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "AudioBriefingWorker failed")
            // Don't retry — text briefing is the reliable fallback
            Result.success()
        }
    }
}
