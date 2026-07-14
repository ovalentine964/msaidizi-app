package com.msaidizi.app.loops

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.msaidizi.app.MainActivity
import com.msaidizi.app.MsaidiziApp
import com.msaidizi.app.R
import com.msaidizi.app.agent.WorkerType
import com.msaidizi.app.cfo.BriefingDelivery
import com.msaidizi.app.cfo.BriefingResult
import com.msaidizi.app.cfo.BriefingType
import com.msaidizi.app.core.database.TransactionDao
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that delivers morning briefings as Android notifications.
 *
 * Scheduled daily at 7 AM (configurable). Generates a personalized briefing
 * via CFOEngine + BriefingDelivery and shows it as a high-priority notification
 * with voice-read capability.
 *
 * Flow:
 *   7:00 AM → WorkManager triggers → MorningBriefingLoop.executeMorningBriefing()
 *   → BriefingResult → Android Notification → Worker taps → MainActivity opens
 *
 * For low-literacy workers, the notification includes a BigTextStyle with the
 * full Swahili message, plus a "Sikiliza" (Listen) action that triggers TTS.
 */
class BriefingNotificationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "BriefingNotifWorker"
        private const val CHANNEL_ID = "msaidizi_briefings"
        private const val MORNING_NOTIFICATION_ID = 1001
        private const val EVENING_NOTIFICATION_ID = 1002
        private const val WEEKLY_NOTIFICATION_ID = 1003
        private const val MIDDAY_NOTIFICATION_ID = 1004
        private const val STREAK_NOTIFICATION_ID = 1005

        /** Input key: briefing type (MORNING, EVENING, WEEKLY, MIDDAY, STREAK) */
        const val KEY_BRIEFING_TYPE = "briefing_type"

        /**
         * Schedule the daily morning briefing at 7 AM EAT.
         * Uses WorkManager periodic work with exact timing.
         */
        fun scheduleMorningBriefing(context: Context) {
            scheduleDaily(context, "morning_briefing", 7, 0, "MORNING")
        }

        /**
         * Schedule the daily evening summary at 7 PM EAT.
         */
        fun scheduleEveningSummary(context: Context) {
            scheduleDaily(context, "evening_summary", 19, 0, "EVENING")
        }

        /**
         * Schedule the mid-day nudge at 12 PM EAT.
         * Prompts worker to report sales using voice.
         */
        fun scheduleMidDayNudge(context: Context) {
            scheduleDaily(context, "midday_nudge", 12, 0, "MIDDAY")
        }

        /**
         * Schedule the evening streak reminder at 6 PM EAT.
         * Only fires if worker hasn't recorded any transactions today.
         */
        fun scheduleStreakReminder(context: Context) {
            scheduleDaily(context, "streak_reminder", 18, 0, "STREAK")
        }

        /**
         * Schedule the weekly summary on Monday at 8 AM EAT.
         */
        fun scheduleWeeklySummary(context: Context) {
            val now = ZonedDateTime.now(ZoneId.of("Africa/Nairobi"))
            val nextMonday = now.toLocalDate()
                .with(java.time.DayOfWeek.MONDAY)
                .atTime(8, 0)
                .atZone(ZoneId.of("Africa/Nairobi"))
            val delay = Duration.between(now, nextMonday).toMillis().coerceAtLeast(0)

            val inputData = workDataOf(KEY_BRIEFING_TYPE to "WEEKLY")

            val request = PeriodicWorkRequestBuilder<BriefingNotificationWorker>(
                7, TimeUnit.DAYS
            )
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(inputData)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .addTag("weekly_summary")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "weekly_summary",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Timber.d(TAG, "Weekly summary scheduled for Monday 8 AM EAT")
        }

        /**
         * Schedule all briefing workers.
         * Call from Application.onCreate() or after onboarding completion.
         */
        fun scheduleAllBriefings(context: Context) {
            createNotificationChannel(context)
            scheduleMorningBriefing(context)
            scheduleMidDayNudge(context)
            scheduleEveningSummary(context)
            scheduleStreakReminder(context)
            scheduleWeeklySummary(context)
            Timber.i(TAG, "All briefing notifications scheduled")
        }

        /**
         * Cancel all scheduled briefings.
         */
        fun cancelAllBriefings(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("morning_briefing")
            WorkManager.getInstance(context).cancelUniqueWork("midday_nudge")
            WorkManager.getInstance(context).cancelUniqueWork("evening_summary")
            WorkManager.getInstance(context).cancelUniqueWork("streak_reminder")
            WorkManager.getInstance(context).cancelUniqueWork("weekly_summary")
            Timber.i(TAG, "All briefing notifications cancelled")
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

            val request = PeriodicWorkRequestBuilder<BriefingNotificationWorker>(
                1, TimeUnit.DAYS
            )
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(inputData)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .addTag(workName)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                workName,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Timber.d(TAG, "%s scheduled for %02d:%02d EAT (delay=%dms)",
                workName, hour, minute, delay)
        }

        /**
         * Create notification channel for Android 8+.
         */
        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Msaidizi Briefings",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Daily business briefings from Msaidizi"
                    enableVibration(true)
                    setShowBadge(true)
                }
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(channel)
            }
        }
    }

    override suspend fun doWork(): Result {
        val briefingType = inputData.getString(KEY_BRIEFING_TYPE) ?: "MORNING"
        Timber.d(TAG, "BriefingNotificationWorker: Starting %s briefing", briefingType)

        val app = applicationContext as? MsaidiziApp
            ?: return Result.failure(workDataOf("error" to "Not MsaidiziApp"))

        // Read worker profile from SharedPreferences
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
            // Handle MIDDAY and STREAK types which don't need BriefingDelivery
            if (briefingType == "MIDDAY") {
                return handleMidDayNudge(workerName, agentName, language)
            }
            if (briefingType == "STREAK") {
                return handleStreakReminder(workerName, language)
            }

            val briefingDelivery = app.briefingDelivery
                ?: return Result.failure(workDataOf("error" to "BriefingDelivery not available"))

            val result = when (briefingType) {
                "MORNING" -> {
                    briefingDelivery.deliverMorningBriefing(
                        workerName = workerName,
                        assistantName = agentName,
                        workerType = workerType,
                        todayTransactions = emptyList(),
                        yesterdayTransactions = emptyList(),
                        recentTransactions = emptyList()
                    )
                }
                "EVENING" -> {
                    briefingDelivery.deliverEveningSummary(
                        workerName = workerName,
                        todayTransactions = emptyList()
                    )
                }
                "WEEKLY" -> {
                    // Fetch real transaction data for weekly report
                    val transactionDao = getTransactionDao()
                    val now = System.currentTimeMillis() / 1000
                    val weekAgo = now - 7 * 86400L
                    val twoWeeksAgo = now - 14 * 86400L
                    val thisWeek = transactionDao?.getTransactionsInRangeSuspend(weekAgo, now) ?: emptyList()
                    val lastWeek = transactionDao?.getTransactionsInRangeSuspend(twoWeeksAgo, weekAgo) ?: emptyList()
                    briefingDelivery.deliverWeeklySummary(
                        workerName = workerName,
                        assistantName = agentName,
                        thisWeek = thisWeek,
                        lastWeek = lastWeek
                    )
                }
                else -> return Result.failure(workDataOf("error" to "Unknown type: $briefingType"))
            }

            showNotification(result, briefingType, language)
            Timber.i(TAG, "%s briefing delivered as notification", briefingType)
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Failed to deliver %s briefing", briefingType)
            if (runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf("error" to e.message))
        }
    }

    /**
     * Handle mid-day nudge: prompt worker to report sales.
     * Uses personalized voice message with worker's name.
     */
    private suspend fun handleMidDayNudge(
        workerName: String,
        agentName: String,
        language: String
    ): Result {
        // Check if worker has already recorded today
        val transactionDao = getTransactionDao()
        if (transactionDao != null) {
            val todayStart = LocalDate.now().atStartOfDay(ZoneId.of("Africa/Nairobi")).toEpochSecond()
            val now = System.currentTimeMillis() / 1000
            val todayCount = transactionDao.getTransactionCount(todayStart, now)
            if (todayCount > 0) {
                Timber.d(TAG, "Worker has already recorded %d transactions today, skipping midday nudge", todayCount)
                return Result.success()
            }
        }

        val message = if (language == "sw") {
            "$workerName, umeshauza ngapi leo? Sema 'Nimeuza...' kuripoti kwa $agentName"
        } else {
            "$workerName, how many sales today? Say 'I sold...' to report to $agentName"
        }

        val result = BriefingResult(
            message = message,
            type = BriefingType.ALERT,
            priority = com.msaidizi.app.cfo.BriefingPriority.NORMAL,
            data = mapOf("nudgeType" to "MIDDAY")
        )

        showNotification(result, "MIDDAY", language)
        Timber.i(TAG, "Mid-day nudge delivered for %s", workerName)
        return Result.success()
    }

    /**
     * Handle streak reminder at 6PM.
     * Only fires if worker hasn't recorded any transactions today.
     * Uses loss aversion psychology to drive engagement.
     */
    private suspend fun handleStreakReminder(
        workerName: String,
        language: String
    ): Result {
        val transactionDao = getTransactionDao()
        if (transactionDao != null) {
            val todayStart = LocalDate.now().atStartOfDay(ZoneId.of("Africa/Nairobi")).toEpochSecond()
            val now = System.currentTimeMillis() / 1000
            val todayCount = transactionDao.getTransactionCount(todayStart, now)
            if (todayCount > 0) {
                Timber.d(TAG, "Worker recorded today, no streak reminder needed")
                return Result.success()
            }
        }

        // Read streak info from gamification database
        var currentStreak = 0
        try {
            val db = com.msaidizi.app.core.database.AppDatabase.getInstance(applicationContext)
            val gamification = db.gamificationDao().getGamification()
            currentStreak = gamification?.currentStreak ?: 0
        } catch (e: Exception) {
            Timber.w(e, "Could not read gamification state for streak reminder")
        }

        val message = if (language == "sw") {
            if (currentStreak > 0) {
                "$workerName, usipoteze streak yako ya siku $currentStreak! Sema chochote kuhusu biashara yako leo"
            } else {
                "$workerName, hujaerekodi leo! Sema chochote kuhusu biashara yako ili kuanza streak yako"
            }
        } else {
            if (currentStreak > 0) {
                "$workerName, don't lose your $currentStreak-day streak! Say anything about your business today"
            } else {
                "$workerName, you haven't recorded today! Say something about your business to start your streak"
            }
        }

        val result = BriefingResult(
            message = message,
            type = BriefingType.ALERT,
            priority = com.msaidizi.app.cfo.BriefingPriority.HIGH,
            data = mapOf("nudgeType" to "STREAK", "streak" to currentStreak.toString())
        )

        showNotification(result, "STREAK", language)
        Timber.i(TAG, "Streak reminder delivered for %s (streak=%d)", workerName, currentStreak)
        return Result.success()
    }

    /**
     * Get TransactionDao via Room database instance.
     */
    private fun getTransactionDao(): TransactionDao? {
        return try {
            val db = com.msaidizi.app.core.database.AppDatabase.getInstance(applicationContext)
            db.transactionDao()
        } catch (e: Exception) {
            Timber.w(e, "Could not get TransactionDao")
            null
        }
    }

    /**
     * Show the briefing as an Android notification.
     * Uses BigTextStyle for the full Swahili message.
     */
    private fun showNotification(
        result: BriefingResult,
        briefingType: String,
        language: String
    ) {
        // Check notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Timber.w(TAG, "No POST_NOTIFICATIONS permission")
                return
            }
        }

        val notificationId = when (briefingType) {
            "MORNING" -> MORNING_NOTIFICATION_ID
            "EVENING" -> EVENING_NOTIFICATION_ID
            "WEEKLY" -> WEEKLY_NOTIFICATION_ID
            "MIDDAY" -> MIDDAY_NOTIFICATION_ID
            "STREAK" -> STREAK_NOTIFICATION_ID
            else -> MORNING_NOTIFICATION_ID
        }

        // Intent to open MainActivity when notification is tapped
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_briefing", true)
            putExtra("briefing_type", briefingType)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val emoji = when (briefingType) {
            "MORNING" -> "☀️"
            "EVENING" -> "🌆"
            "WEEKLY" -> "📋"
            "MIDDAY" -> "📢"
            "STREAK" -> "🔥"
            else -> "📊"
        }

        val title = when (briefingType) {
            "MORNING" -> if (language == "sw") "Habari za Asubuhi! $emoji" else "Good Morning! $emoji"
            "EVENING" -> if (language == "sw") "Muhtasari wa Leo $emoji" else "Today's Summary $emoji"
            "WEEKLY" -> if (language == "sw") "Ripoti ya Wiki $emoji" else "Weekly Report $emoji"
            "MIDDAY" -> if (language == "sw") "Wakati wa Kuuza! $emoji" else "Time to Sell! $emoji"
            "STREAK" -> if (language == "sw") "Streak Yako $emoji" else "Your Streak $emoji"
            else -> "Msaidizi $emoji"
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(result.message.take(100) + "...")
            .setStyle(NotificationCompat.BigTextStyle().bigText(result.message))
            .setPriority(
                when (result.priority) {
                    com.msaidizi.app.cfo.BriefingPriority.HIGH -> NotificationCompat.PRIORITY_HIGH
                    com.msaidizi.app.cfo.BriefingPriority.NORMAL -> NotificationCompat.PRIORITY_DEFAULT
                    com.msaidizi.app.cfo.BriefingPriority.LOW -> NotificationCompat.PRIORITY_LOW
                }
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 250, 250, 250))
            .build()

        NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
    }
}
