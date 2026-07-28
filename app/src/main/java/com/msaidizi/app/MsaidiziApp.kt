package com.msaidizi.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.msaidizi.voice.ModelDownloadManager
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class MsaidiziApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var modelDownloadManager: ModelDownloadManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        createNotificationChannels()
        scheduleModelDownload()
    }

    /**
     * Schedule model download on first launch.
     * If models are bundled in APK, they'll be extracted from assets.
     * If not bundled (production), they'll be downloaded via WorkManager.
     */
    private fun scheduleModelDownload() {
        // Run in background — don't block app startup
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                modelDownloadManager.ensureModelsReady()
            } catch (e: Exception) {
                Timber.e(e, "Failed to schedule model download")
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val voiceChannel = NotificationChannel(
                CHANNEL_VOICE,
                "Voice Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Msaidizi voice processing"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(voiceChannel)
        }
    }

    companion object {
        const val CHANNEL_VOICE = "msaidizi_voice"
    }
}
