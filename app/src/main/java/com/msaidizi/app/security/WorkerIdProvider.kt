package com.msaidizi.app.security

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkerIdProvider — Single source of truth for the worker identity.
 *
 * On first install, generates a UUID and persists it.
 * All subsequent reads return the same ID.
 *
 * This replaces hardcoded "default" worker IDs that caused
 * memory/transaction leakage between different workers.
 */
@Singleton
class WorkerIdProvider @Inject constructor(
    private val prefs: SharedPreferences
) {
    @Volatile
    private var cachedId: String? = null

    /**
     * Returns the stable worker ID for this installation.
     * Generates and persists one if none exists yet.
     */
    fun getWorkerId(): String {
        cachedId?.let { return it }

        val existing = prefs.getString(PREF_KEY, null)
        if (existing != null) {
            cachedId = existing
            return existing
        }

        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(PREF_KEY, newId).apply()
        cachedId = newId
        Timber.i("Generated new worker ID: $newId")
        return newId
    }

    companion object {
        private const val PREF_KEY = "worker_id"
    }
}
