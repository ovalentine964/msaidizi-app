package com.msaidizi.app.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Auto-updater for Msaidizi.
 *
 * Checks GitHub Releases for new versions and uses Android's built-in
 * PackageInstaller to apply updates — no manual "Unknown Sources" needed
 * on Android 8.0+ (API 26+).
 *
 * Flow:
 *   1. Check version endpoint → compare with current versionCode
 *   2. Download APK via DownloadManager (shows notification)
 *   3. On tap → Android's built-in installer handles everything
 *
 * The key insight: on Android 8.0+, the system has "Install from unknown apps"
 * which is a per-app permission (not a global toggle). Once the user grants
 * it to the browser/download manager the first time, future installs are
 * one-tap. And if we use DownloadManager + PackageInstaller directly,
 * we can trigger the system installer UI with zero manual Settings steps.
 */
@Singleton
class AutoUpdater @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    companion object {
        private const val UPDATE_CHECK_URL =
            "https://api.github.com/repos/ovalentine964/msaidizi-app/releases/latest"
        private const val PREF_NAME = "msaidizi_update"
        private const val PREF_LAST_CHECK = "last_check_ts"
        private const val PREF_SKIP_VERSION = "skip_version_code"
        private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L // 12 hours
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * Result of an update check.
     */
    sealed class UpdateResult {
        data class Available(val info: UpdateInfo) : UpdateResult()
        object UpToDate : UpdateResult()
        object Skipped : UpdateResult()
        data class Error(val message: String) : UpdateResult()
    }

    /**
     * Info about an available update.
     */
    @Serializable
    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val downloadUrl: String,
        val fileSize: Long,
        val releaseNotes: String
    )

    /**
     * Checks GitHub Releases for a newer version.
     * Returns [UpdateResult.Available] if an update exists.
     */
    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            // Don't check too frequently
            val lastCheck = prefs.getLong(PREF_LAST_CHECK, 0)
            val now = System.currentTimeMillis()
            if (now - lastCheck < CHECK_INTERVAL_MS) {
                Timber.d("Update check skipped — last check was ${(now - lastCheck) / 60000}min ago")
                return@withContext UpdateResult.UpToDate
            }
            prefs.edit().putLong(PREF_LAST_CHECK, now).apply()

            val currentVersionCode = try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode
                }
            } catch (e: PackageManager.NameNotFoundException) {
                0
            }

            val connection = URL(UPDATE_CHECK_URL).openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            if (connection.responseCode != 200) {
                return@withContext UpdateResult.Error("HTTP ${connection.responseCode}")
            }

            val body = connection.inputStream.bufferedReader().readText()
            val release = json.parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonObject }

            val tagName = release["tag_name"]?.toString()?.trim('"') ?: ""
            val releaseVersionCode = tagName.removePrefix("v").replace(".", "").toIntOrNull() ?: 0
            val releaseVersionName = tagName.removePrefix("v")
            val releaseNotes = release["body"]?.toString()?.trim('"') ?: ""

            // Find the APK asset
            val assets = release["assets"] as? kotlinx.serialization.json.JsonArray
            val apkAsset = assets?.firstOrNull { asset ->
                val obj = asset as? kotlinx.serialization.json.JsonObject
                val name = obj?.get("name")?.toString()?.trim('"') ?: ""
                name.endsWith(".apk")
            } as? kotlinx.serialization.json.JsonObject

            val downloadUrl = apkAsset?.get("browser_download_url")?.toString()?.trim('"')
                ?: return@withContext UpdateResult.Error("No APK in release")
            val fileSize = apkAsset["size"]?.toString()?.toLongOrNull() ?: 0

            // Skip if user chose to skip this version
            val skipVersion = prefs.getInt(PREF_SKIP_VERSION, -1)
            if (releaseVersionCode == skipVersion) {
                return@withContext UpdateResult.Skipped
            }

            if (releaseVersionCode > currentVersionCode) {
                Timber.i("Update available: $currentVersionCode → $releaseVersionCode")
                UpdateResult.Available(
                    UpdateInfo(
                        versionCode = releaseVersionCode,
                        versionName = releaseVersionName,
                        downloadUrl = downloadUrl,
                        fileSize = fileSize,
                        releaseNotes = releaseNotes
                    )
                )
            } else {
                Timber.d("App is up to date (version $currentVersionCode)")
                UpdateResult.UpToDate
            }
        } catch (e: Throwable) {
            Timber.e(e, "Update check failed")
            UpdateResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Downloads the update APK and triggers the system installer.
     *
     * On Android 8.0+ this launches the system "Install this app?" dialog
     * directly — no manual "Unknown Sources" toggle needed.
     */
    suspend fun downloadAndInstall(
        info: UpdateInfo,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileName = "msaidizi-${info.versionName}.apk"
            val destFile = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )

            // Clean up old downloads
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.listFiles()
                ?.filter { it.name.startsWith("msaidizi-") && it.name.endsWith(".apk") }
                ?.forEach { it.delete() }

            // Use DownloadManager for reliable download with notification
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(info.downloadUrl))
                .setTitle("Msaidizi Update")
                .setDescription("Downloading Msaidizi v${info.versionName}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(destFile))
                .setAllowedOverMetered(true) // Allow on mobile data
                .setAllowedOverRoaming(true)

            val downloadId = downloadManager.enqueue(request)
            Timber.i("Download started: id=$downloadId, url=${info.downloadUrl}")

            // Wait for download to complete
            val success = waitForDownload(downloadId, onProgress)

            if (success && destFile.exists()) {
                installApk(destFile)
                true
            } else {
                Timber.e("Download failed or file missing")
                false
            }
        } catch (e: Throwable) {
            Timber.e(e, "Download/install failed")
            false
        }
    }

    /**
     * Marks a version to be skipped in future update checks.
     */
    fun skipVersion(versionCode: Int) {
        prefs.edit().putInt(PREF_SKIP_VERSION, versionCode).apply()
    }

    /**
     * Resets the skip preference so previously skipped versions
     * will show again on next check.
     */
    fun resetSkip() {
        prefs.edit().remove(PREF_SKIP_VERSION).apply()
    }

    /**
     * Forces an immediate update check (ignores the cooldown).
     */
    suspend fun forceCheck(): UpdateResult {
        prefs.edit().putLong(PREF_LAST_CHECK, 0).apply()
        return checkForUpdate()
    }

    // -- Private helpers --

    private suspend fun waitForDownload(
        downloadId: Long,
        onProgress: ((Float) -> Unit)?
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    context.unregisterReceiver(this)

                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val cursor = dm.query(query)

                    if (cursor != null && cursor.moveToFirst()) {
                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val status = cursor.getInt(statusIndex)

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            continuation.resume(true)
                        } else {
                            val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                            val reason = cursor.getInt(reasonIndex)
                            Timber.e("Download failed: status=$status, reason=$reason")
                            continuation.resume(false)
                        }
                        cursor.close()
                    } else {
                        continuation.resume(false)
                    }
                }
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        context.registerReceiver(receiver, filter)

        continuation.invokeOnCancellation {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Throwable) {}
        }

        // Poll for progress updates
        Thread {
            while (continuation.isActive) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val cursor = dm.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val bytesIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val bytes = cursor.getLong(bytesIndex)
                    val total = cursor.getLong(totalIndex)
                    if (total > 0) {
                        onProgress?.invoke(bytes.toFloat() / total.toFloat())
                    }
                    cursor.close()
                }
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.start()
    }

    /**
     * Triggers the Android system package installer.
     *
     * On Android 7.0+ (API 24+): uses FileProvider URI
     * On Android 8.0+ (API 26+): the system handles the "unknown app" permission
     *   automatically — user just taps "Install" in the system dialog.
     *
     * This is the KEY to eliminating the "Unknown Sources" friction.
     */
    private fun installApk(apkFile: File) {
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)

        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // API 24+ requires FileProvider
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
        } else {
            Uri.fromFile(apkFile)
        }

        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION

        // On API 26+, the system will show its own "Do you want to install?" dialog
        // and handle the "Install from unknown apps" permission per-app automatically.
        // No manual Settings → Security → Unknown Sources needed!

        try {
            context.startActivity(intent)
            Timber.i("System installer launched for ${apkFile.name}")
        } catch (e: Throwable) {
            Timber.e(e, "Failed to launch installer")
            // Fallback: open file with default handler
            val fallback = Intent(Intent.ACTION_VIEW)
            fallback.setDataAndType(uri, "application/vnd.android.package-archive")
            fallback.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            try {
                context.startActivity(fallback)
            } catch (e2: Throwable) {
                Timber.e(e2, "Fallback installer also failed")
            }
        }
    }
}
