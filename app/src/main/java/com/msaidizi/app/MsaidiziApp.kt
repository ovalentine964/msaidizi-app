package com.msaidizi.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import com.msaidizi.agent.memory.MemoryManager
import com.msaidizi.agent.flywheel.FlywheelEngine
import com.msaidizi.voice.ModelDownloadManager
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class MsaidiziApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var modelDownloadManager: ModelDownloadManager
    @Inject lateinit var memoryManager: MemoryManager
    @Inject lateinit var flywheelEngine: FlywheelEngine

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            // T3: Disable Crashlytics in debug builds for cleaner dev experience
            Firebase.crashlytics.setCrashlyticsCollectionEnabled(false)
        } else {
            // T3: Enable Crashlytics in release builds
            Firebase.crashlytics.setCrashlyticsCollectionEnabled(true)
        }

        createNotificationChannels()
        scheduleModelDownload()

        // Run memory consolidation pipeline on startup (L1→L2→L3→L4)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                memoryManager.runConsolidationPipeline()
                // Run confidence decay to prevent inflation
                flywheelEngine.decayConfidence()
                // Close learning → knowledge graph feedback loop
                flywheelEngine.feedbackToKnowledgeGraph()
                Timber.i("Startup consolidation, confidence decay, and KG feedback complete")
            } catch (e: Exception) {
                Timber.e(e, "Startup consolidation failed")
            }
        }

        // Schedule proactive agent runs (daily CFO, weekly summary, monthly review)
        com.msaidizi.agent.proactive.ScheduledAgentWorker.scheduleAll(this)
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
