package com.msaidizi.core.model.version

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages model versioning and update tracking.
 *
 * Tracks which version of each model is installed on the device. When a
 * newer version is available in the registry, the downloader can upgrade
 * the model (typically on WiFi, in the background).
 *
 * ## Version Storage
 * Versions are stored as plain text files in `context.filesDir/model_versions/`.
 * Each model has a file named `<model_id>.version` containing the version string.
 *
 * @see com.msaidizi.core.model.registry.ModelRegistry for model definitions
 */
@Singleton
class ModelVersionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val versionsDir = File(context.filesDir, "model_versions").apply { mkdirs() }

    /** Read the installed version of a model */
    fun readVersion(modelId: String): String? {
        val file = File(versionsDir, "$modelId.version")
        return if (file.exists()) file.readText().trim().takeIf { it.isNotBlank() } else null
    }

    /** Write the installed version of a model */
    fun writeVersion(modelId: String, version: String) {
        val file = File(versionsDir, "$modelId.version")
        file.writeText(version)
        Timber.d("ModelVersionManager: %s → v%s", modelId, version)
    }

    /** Check if an update is available for a model */
    fun isUpdateAvailable(modelId: String, latestVersion: String): Boolean {
        val current = readVersion(modelId) ?: return true
        return current != latestVersion
    }

    /** Clear all version records */
    fun clearAll() {
        versionsDir.listFiles()?.forEach { it.delete() }
    }
}
