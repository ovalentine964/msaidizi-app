package com.msaidizi.app.voice.transfer

import android.content.Context
import android.os.Environment
import com.msaidizi.app.BuildConfig
import com.msaidizi.app.core.model.ModelTier
import com.msaidizi.app.core.model.ModelVersionTracker
import com.msaidizi.app.voice.ModelDef
import com.msaidizi.app.voice.ModelRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects and loads models from SD card.
 *
 * SD card distribution is a key delivery channel for Kenya's informal economy.
 * Pre-loaded SD cards can be distributed at M-Pesa shops, market stalls, etc.
 *
 * Expected SD card structure:
 * /sdcard/Msaidizi/models/whisper-tiny-int4.onnx
 * /sdcard/Msaidizi/models/piper-swahili.onnx
 * /sdcard/Msaidizi/models/qwen3.5-0.8b-q4_k_m.gguf
 */
@Singleton
class SdCardModelLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRegistry: ModelRegistry,
    private val versionTracker: ModelVersionTracker
) {
    companion object {
        /** Standard SD card model directory */
        private const val SD_CARD_MODEL_DIR = "Msaidizi/models"

        /** Alternative paths to check */
        private val SD_CARD_PATHS = listOf(
            "/storage/emulated/0/$SD_CARD_MODEL_DIR",
            "/storage/sdcard1/$SD_CARD_MODEL_DIR",
            "/storage/external_SD/$SD_CARD_MODEL_DIR",
            "/mnt/extSdCard/$SD_CARD_MODEL_DIR"
        )

        private const val BUFFER_SIZE = 8192
    }

    sealed class SdCardState {
        object Idle : SdCardState()
        data class ModelsFound(val models: List<SdCardModel>) : SdCardState()
        data class Copying(val modelId: String, val percent: Int) : SdCardState()
        data class Complete(val copiedCount: Int) : SdCardState()
        data class Error(val message: String) : SdCardState()
    }

    data class SdCardModel(
        val modelId: String,
        val file: File,
        val sizeBytes: Long,
        val filename: String
    )

    private val _state = MutableStateFlow<SdCardState>(SdCardState.Idle)
    val state: StateFlow<SdCardState> = _state

    /**
     * Scan SD card paths for available models.
     * Returns list of models found that aren't already in internal storage.
     */
    suspend fun scanForModels(): List<SdCardModel> = withContext(Dispatchers.IO) {
        val found = mutableListOf<SdCardModel>()

        for (sdPath in SD_CARD_PATHS) {
            val dir = File(sdPath)
            if (!dir.exists() || !dir.isDirectory) continue

            Timber.d("Scanning SD card path: %s", sdPath)

            for (def in ModelRegistry.MODELS.values) {
                val file = File(dir, def.filename)
                if (file.exists() && file.length() > 0) {
                    // Check if already downloaded in internal storage
                    if (!modelRegistry.isModelReady(def.id)) {
                        found.add(
                            SdCardModel(
                                modelId = def.id,
                                file = file,
                                sizeBytes = file.length(),
                                filename = def.filename
                            )
                        )
                        Timber.i("Found model on SD card: %s (%d bytes)", def.id, file.length())
                    }
                }
            }
        }

        if (found.isNotEmpty()) {
            _state.value = SdCardState.ModelsFound(found)
        }

        found
    }

    /**
     * Copy all found SD card models to internal storage with verification.
     *
     * @param models Models to copy (from scanForModels)
     * @param onProgress Callback: (modelId, progress 0.0-1.0)
     * @return Number of models successfully copied
     */
    suspend fun copyModelsFromSdCard(
        models: List<SdCardModel>,
        onProgress: (String, Float) -> Unit = { _, _ -> }
    ): Int = withContext(Dispatchers.IO) {
        var copiedCount = 0

        for (sdModel in models) {
            val def = ModelRegistry.MODELS[sdModel.modelId] ?: continue

            _state.value = SdCardState.Copying(sdModel.modelId, 0)

            try {
                // Size sanity check — reject files that are suspiciously different in size
                val sizeDiff = kotlin.math.abs(sdModel.sizeBytes - def.sizeBytes)
                if (sizeDiff > def.sizeBytes * 0.5) {
                    Timber.e(
                        "SD card model %s size mismatch: expected ~%d, got %d",
                        sdModel.modelId, def.sizeBytes, sdModel.sizeBytes
                    )
                    continue
                }

                // Verify SHA-256 — mandatory in release, optional in debug if hash is empty
                if (def.sha256.isNotEmpty()) {
                    val hash = sha256File(sdModel.file)
                    if (hash != def.sha256) {
                        Timber.e("SD card model %s SHA-256 mismatch", sdModel.modelId)
                        continue
                    }
                } else if (!BuildConfig.DEBUG) {
                    Timber.e(
                        "SD card model %s: SHA-256 hash missing — blocking in production",
                        sdModel.modelId
                    )
                    continue
                } else {
                    Timber.w(
                        "SD card model %s: SHA-256 hash missing — skipping verification in debug",
                        sdModel.modelId
                    )
                }

                // Copy with progress tracking
                val destDir = File(context.filesDir, "models").apply { mkdirs() }
                val destFile = File(destDir, def.filename)
                val tempFile = File(destDir, "${def.filename}.sd_copy")

                val totalBytes = sdModel.file.length()
                var copiedBytes = 0L

                sdModel.file.inputStream().use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            copiedBytes += bytesRead
                            val progress = (copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                            onProgress(sdModel.modelId, progress)
                            _state.value = SdCardState.Copying(
                                sdModel.modelId,
                                (progress * 100).toInt()
                            )
                        }
                    }
                }

                // Atomic rename
                val renamed = tempFile.renameTo(destFile)
                if (!renamed) {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }

                // Write version file
                versionTracker.writeVersion(sdModel.modelId, def.version)

                copiedCount++
                Timber.i("Model %s copied from SD card successfully", sdModel.modelId)

            } catch (e: Exception) {
                Timber.e(e, "Failed to copy model %s from SD card", sdModel.modelId)
            }
        }

        _state.value = SdCardState.Complete(copiedCount)
        copiedCount
    }

    /**
     * Check if SD card is mounted and accessible.
     */
    fun isSdCardAvailable(): Boolean {
        val state = Environment.getExternalStorageState()
        return state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY
    }

    /**
     * Get the primary SD card model directory path.
     */
    fun getSdCardModelDir(): File? {
        for (path in SD_CARD_PATHS) {
            val dir = File(path)
            if (dir.exists()) return dir
        }
        return null
    }

    fun resetState() {
        _state.value = SdCardState.Idle
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
