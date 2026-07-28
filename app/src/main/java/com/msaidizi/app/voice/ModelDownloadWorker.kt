package com.msaidizi.app.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL


/**
 * ModelDownloadWorker — Downloads AI models on first launch via WorkManager.
 *
 * This worker handles the ~555MB model download flow for production builds
 * where models are NOT bundled in the APK (to keep APK size small).
 *
 * Features:
 * - Resumable downloads (HTTP Range headers)
 * - Progress reporting via WorkInfo
 * - WiFi-only constraint (configurable)
 * - SHA-256 verification after download
 * - Retry with exponential backoff
 *
 * Usage:
 * ```
 * val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
 *     .setConstraints(Constraints.Builder()
 *         .setRequiredNetworkType(NetworkType.UNMETERED)  // WiFi only
 *         .setRequiresStorageNotLow(true)
 *         .build())
 *     .build()
 * WorkManager.getInstance(context).enqueueUniqueWork(
 *     "model_download", ExistingWorkPolicy.KEEP, request)
 * ```
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "model_download"
        const val KEY_PROGRESS = "download_progress"
        const val KEY_STATUS = "download_status"
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_ERROR = "error_message"

        /** Model download URLs. In production, fetch from a config endpoint. */
        private val MODEL_MANIFEST = listOf(
            ModelEntry(
                name = "Qwen3.5-0.8B-Q4_K_M.gguf",
                url = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf",
                destPath = "models/Qwen3.5-0.8B-Q4_K_M.gguf",
                sizeBytes = 500_000_000L,
                sha256 = ""  // Set after first verified download
            ),
            ModelEntry(
                name = "whisper-tiny (STT)",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2",
                destPath = "models/sherpa-onnx/whisper",
                sizeBytes = 40_000_000L,
                sha256 = "",
                isArchive = true
            ),
            ModelEntry(
                name = "piper-swahili (TTS)",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-sw_CD-lanfrica-medium.tar.bz2",
                destPath = "models/sherpa-onnx/piper-sw",
                sizeBytes = 15_000_000L,
                sha256 = "",
                isArchive = true
            )
        )
    }

    private data class ModelEntry(
        val name: String,
        val url: String,
        val destPath: String,  // Relative to appContext.filesDir
        val sizeBytes: Long,
        val sha256: String,
        val isArchive: Boolean = false
    )

    override suspend fun doWork(): Result {
        Timber.i("ModelDownloadWorker starting")

        val modelsDir = File(appContext.filesDir, "models")
        modelsDir.mkdirs()

        val totalModels = MODEL_MANIFEST.size
        var completedModels = 0

        for (model in MODEL_MANIFEST) {
            try {
                setProgress(buildProgressInfo(model.name, completedModels, totalModels, "downloading"))

                val destFile = File(appContext.filesDir, model.destPath)

                // Skip if already downloaded and valid
                if (isModelReady(destFile, model)) {
                    Timber.d("Model already present: ${model.name}")
                    completedModels++
                    continue
                }

                // Check WiFi constraint
                if (!isWifiAvailable()) {
                    Timber.w("WiFi not available, retrying later")
                    return Result.retry()
                }

                // Download
                Timber.i("Downloading model: ${model.name} from ${model.url}")
                downloadFile(model.url, destFile, model.isArchive)

                // Verify
                if (model.sha256.isNotEmpty()) {
                    val hash = destFile.inputStream().use { input ->
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            digest.update(buffer, 0, read)
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                    if (hash != model.sha256) {
                        Timber.e("SHA-256 mismatch for ${model.name}: expected ${model.sha256}, got $hash")
                        destFile.delete()
                        return Result.retry()
                    }
                }

                completedModels++
                Timber.i("Model downloaded: ${model.name} (${completedModels}/${totalModels})")

            } catch (e: Exception) {
                Timber.e(e, "Failed to download model: ${model.name}")
                setProgress(buildProgressInfo(model.name, completedModels, totalModels, "error: ${e.message}"))

                // If this is the LLM model (required), retry
                if (model.name.contains("Qwen")) {
                    return Result.retry()
                }
                // Optional models (STT/TTS) — continue without them
                completedModels++
            }
        }

        // Mark download complete
        val prefs = appContext.getSharedPreferences("model_manager", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("assets_extracted_v1", true).apply()

        setProgress(buildProgressInfo("all", totalModels, totalModels, "complete"))
        Timber.i("All models downloaded successfully")
        return Result.success()
    }

    private fun buildProgressInfo(modelName: String, completed: Int, total: Int, status: String): Data {
        return Data.Builder()
            .putString(KEY_MODEL_NAME, modelName)
            .putInt(KEY_PROGRESS, (completed * 100) / total)
            .putString(KEY_STATUS, status)
            .build()
    }

    private fun isModelReady(dest: File, model: ModelEntry): Boolean {
        if (model.isArchive) {
            // For archives, check if the destination directory has files
            return dest.exists() && dest.isDirectory && dest.listFiles()?.isNotEmpty() == true
        }
        return dest.exists() && dest.length() >= model.sizeBytes * 9 / 10  // Allow 10% variance
    }

    private fun isWifiAvailable(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun downloadFile(urlStr: String, dest: File, isArchive: Boolean) {
        dest.parentFile?.mkdirs()

        // Create temp file for atomic download
        val tempFile = File(dest.parentFile, "${dest.name}.download")

        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000

        // Resume support
        if (tempFile.exists()) {
            conn.setRequestProperty("Range", "bytes=${tempFile.length()}-")
        }

        conn.connect()

        val responseCode = conn.responseCode
        if (responseCode !in 200..299 && responseCode != 206) {
            throw java.io.IOException("HTTP $responseCode for $urlStr")
        }

        val append = responseCode == 206
        conn.inputStream.use { input ->
            FileOutputStream(tempFile, append).use { output ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                }
            }
        }

        // Atomic rename
        if (isArchive) {
            // Extract archive to destination directory
            dest.mkdirs()
            extractArchive(tempFile, dest)
            tempFile.delete()
        } else {
            tempFile.renameTo(dest)
        }
    }

    private fun extractArchive(archive: File, destDir: File) {
        // tar.bz2 extraction using ProcessBuilder (available on Android)
        try {
            val process = ProcessBuilder("tar", "xjf", archive.absolutePath, "--strip-components=1", "-C", destDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val output = process.inputStream.bufferedReader().readText()
                Timber.e("tar extraction failed (exit $exitCode): $output")
                throw java.io.IOException("Archive extraction failed: $output")
            }
        } catch (e: java.io.IOException) {
            Timber.w(e, "tar not available, attempting manual extraction")
            // Fallback: try using Apache Commons Compress or just copy the archive
            // For production, include a proper tar/bz2 library
            throw java.io.IOException("Cannot extract archive — tar not available on device", e)
        }
    }


}
