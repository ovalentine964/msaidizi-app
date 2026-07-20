package com.msaidizi.app.core.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks model versions on disk.
 * Each model has a companion `.version` file containing its version string.
 * Used to detect when updates are available and to ensure model compatibility.
 */
@Singleton
class ModelVersionTracker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    /**
     * Write the version file for a model.
     * Creates a file like `whisper-tiny-int4.onnx.version` containing the version string.
     */
    fun writeVersion(modelId: String, version: String) {
        try {
            val versionFile = getVersionFile(modelId)
            versionFile.writeText(version)
            Timber.d("Version file written for %s: %s", modelId, version)
        } catch (e: Throwable) {
            Timber.e(e, "Failed to write version for %s", modelId)
        }
    }

    /**
     * Read the stored version for a model, or null if no version file exists.
     */
    fun readVersion(modelId: String): String? {
        return try {
            val versionFile = getVersionFile(modelId)
            if (versionFile.exists()) {
                versionFile.readText().trim().ifEmpty { null }
            } else {
                null
            }
        } catch (e: Throwable) {
            Timber.e(e, "Failed to read version for %s", modelId)
            null
        }
    }

    /**
     * Check if the stored version matches the expected version.
     * Returns true if versions match or if no stored version exists (first install).
     */
    fun isVersionCurrent(modelId: String, expectedVersion: String): Boolean {
        val stored = readVersion(modelId) ?: return true // No version file = fresh install
        return stored == expectedVersion
    }

    /**
     * Remove the version file for a model (called when deleting).
     */
    fun removeVersion(modelId: String) {
        try {
            getVersionFile(modelId).delete()
        } catch (e: Throwable) {
            Timber.w(e, "Failed to remove version file for %s", modelId)
        }
    }

    /**
     * Get all model IDs that have version files on disk.
     */
    fun getModelsWithVersions(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        modelsDir.listFiles()?.filter { it.extension == "version" }?.forEach { file ->
            val modelId = file.nameWithoutExtension
            val version = file.readText().trim()
            if (version.isNotEmpty()) {
                result[modelId] = version
            }
        }
        return result
    }

    private fun getVersionFile(modelId: String): File {
        return File(modelsDir, "$modelId.version")
    }
}
