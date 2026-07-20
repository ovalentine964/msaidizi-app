package com.msaidizi.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.msaidizi.app.MainActivity
import com.msaidizi.app.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Background worker that periodically checks for app updates.
 *
 * Runs every 24 hours via WorkManager. When an update is found,
 * shows a notification that opens the app's update dialog.
 *
 * This replaces the need for users to manually check for updates
 * or re-download from the website.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val autoUpdater: AutoUpdater
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val WORK_NAME = "msaidizi_update_check"
        private const val CHANNEL_ID = "app_updates"
        private const val NOTIFICATION_ID = 2001

        /**
         * Schedules periodic update checks.
         * Call this from MsaidiziApp.onCreate().
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                24, TimeUnit.HOURS
            )
                .setInitialDelay(6, TimeUnit.HOURS) // Don't check immediately on install
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Timber.d("Update check worker scheduled")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            when (val result = autoUpdater.checkForUpdate()) {
                is AutoUpdater.UpdateResult.Available -> {
                    showUpdateNotification(result.info)
                    Timber.i("Update available: v${result.info.versionName}")
                    Result.success()
                }
                is AutoUpdater.UpdateResult.UpToDate -> {
                    Timber.d("App is up to date")
                    Result.success()
                }
                is AutoUpdater.UpdateResult.Skipped -> {
                    Timber.d("Update was skipped by user")
                    Result.success()
                }
                is AutoUpdater.UpdateResult.Error -> {
                    Timber.w("Update check failed: ${result.message}")
                    Result.retry()
                }
            }
        } catch (e: Throwable) {
            Timber.e(e, "Update check worker failed")
            Result.retry()
        }
    }

    private fun showUpdateNotification(info: AutoUpdater.UpdateInfo) {
        val notificationManager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create channel (Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for app updates"
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Intent to open the app (which will show update dialog)
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("show_update", true)
            putExtra("update_version", info.versionName)
            putExtra("update_url", info.downloadUrl)
            putExtra("update_notes", info.releaseNotes)
        }

        val pendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification) // Use existing notification icon
            .setContentTitle("Msaidizi Update Available")
            .setContentText("Version ${info.versionName} is ready to install")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Version ${info.versionName} is ready to install.\n\n${info.releaseNotes.take(200)}")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
