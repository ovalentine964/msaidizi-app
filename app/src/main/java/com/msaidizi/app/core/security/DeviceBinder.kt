package com.msaidizi.app.core.security

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import java.security.MessageDigest
import java.util.UUID

/**
 * DeviceBinder — Stable, privacy-preserving device identifier.
 *
 * FIX 2: DeviceId Null Handling
 * -----------------------------
 * PREVIOUS (VULNERABLE): If READ_PHONE_STATE permission is denied,
 * DeviceId returns null. A null UID defeats device binding — any
 * device could claim to be any user. This is a critical auth bypass.
 *
 * FIX: Multi-fallback strategy with ANDROID_ID as primary fallback:
 *   1. IMEI (if permission granted) — most stable, per-SIM-slot
 *   2. ANDROID_ID (Settings.Secure) — per-device, persists across installs
 *   3. Instance ID (UUID) — per-install, least stable but always available
 *
 * The identifier is SHA-256 hashed with a per-device salt before use,
 * so the raw device ID is never stored or transmitted.
 *
 * Privacy considerations:
 * - ANDROID_ID is 64-bit random number, resets on factory reset
 * - We hash it to prevent correlation across services
 * - Per-install salt prevents rainbow table attacks
 * - The hash is one-way — server cannot reverse to device serial
 */
object DeviceBinder {

    private const val TAG = "DeviceBinder"
    private const val PREFS_NAME = "msaidizi_device"
    private const val KEY_DEVICE_HASH = "device_id_hash"
    private const val KEY_INSTALL_SALT = "install_salt"
    private const val KEY_ID_SOURCE = "id_source"

    /**
     * Source of the device identifier (for diagnostics, never logged with raw ID).
     */
    enum class IdSource {
        IMEI,           // TelephonyManager.getImei() — requires READ_PHONE_STATE
        ANDROID_ID,     // Settings.Secure.ANDROID_ID — no permission needed
        INSTANCE_UUID   // Random UUID per install — always available
    }

    /**
     * Get a stable, hashed device identifier.
     *
     * This method NEVER returns null. It implements a fallback chain:
     *   IMEI → ANDROID_ID → Instance UUID
     *
     * The returned value is a SHA-256 hash of the raw ID combined with
     * a per-install salt, encoded as a hex string.
     *
     * @param context Android context
     * @return 64-char hex string (SHA-256 hash), never null
     */
    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Return cached hash if available
        prefs.getString(KEY_DEVICE_HASH, null)?.let { cached ->
            if (cached.isNotBlank()) return cached
        }

        // Ensure we have a per-install salt
        val salt = getOrCreateSalt(prefs)

        // Try each source in priority order
        val rawId: String
        val source: IdSource

        // Attempt 1: IMEI (most stable, requires permission)
        val imeiId = tryGetImei(context)
        if (imeiId != null) {
            rawId = imeiId
            source = IdSource.IMEI
            Log.d(TAG, "DeviceId source: IMEI")
        } else {
            // Attempt 2: ANDROID_ID (no permission needed, per-device)
            val androidId = tryGetAndroidId(context)
            if (androidId != null && androidId != "9774d56d682e549c") {
                // Note: "9774d56d682e549c" is a known bug value on some Android 2.2 devices
                // that returns the same ID for all devices. We reject it.
                rawId = androidId
                source = IdSource.ANDROID_ID
                Log.d(TAG, "DeviceId source: ANDROID_ID (IMEI unavailable)")
            } else {
                // Attempt 3: Instance UUID (always works, per-install)
                val instanceId = getOrCreateInstanceId(prefs)
                rawId = instanceId
                source = IdSource.INSTANCE_UUID
                Log.d(TAG, "DeviceId source: Instance UUID (IMEI and ANDROID_ID unavailable)")
            }
        }

        // Hash: SHA-256(rawId + ":" + salt)
        val hashed = hashWithSalt(rawId, salt)

        // Cache the result
        prefs.edit()
            .putString(KEY_DEVICE_HASH, hashed)
            .putString(KEY_ID_SOURCE, source.name)
            .apply()

        return hashed
    }

    /**
     * Get the source of the current device identifier.
     * Useful for diagnostics and adaptive security policies.
     */
    fun getIdSource(context: Context): IdSource {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sourceName = prefs.getString(KEY_ID_SOURCE, null)
        return when (sourceName) {
            IdSource.IMEI.name -> IdSource.IMEI
            IdSource.ANDROID_ID.name -> IdSource.ANDROID_ID
            else -> IdSource.INSTANCE_UUID
        }
    }

    /**
     * Check if the device identifier is from a high-confidence source.
     * IMEI and ANDROID_ID are stable; Instance UUID is install-bound.
     */
    fun isHighConfidenceId(context: Context): Boolean {
        return getIdSource(context) in listOf(IdSource.IMEI, IdSource.ANDROID_ID)
    }

    /**
     * Force regeneration of the device ID.
     * Called when SIM swap is detected or security event occurs.
     */
    fun regenerateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_DEVICE_HASH)
            .remove(KEY_ID_SOURCE)
            .apply()
        return getDeviceId(context)
    }

    /**
     * Invalidate cached device ID (e.g., after detecting tampering).
     * Next call to getDeviceId will regenerate.
     */
    fun invalidateCache(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_DEVICE_HASH).apply()
    }

    // ── Internal helpers ──────────────────────────────────────

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun tryGetImei(context: Context): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: IMEI access requires READ_PRIVILEGED_PHONE_STATE
                // (system apps only). Third-party apps cannot get IMEI.
                Log.d(TAG, "Android 10+: IMEI not available to third-party apps")
                null
            } else {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                @Suppress("DEPRECATION")
                val imei = tm?.deviceId
                if (imei.isNullOrBlank()) null else imei
            }
        } catch (e: SecurityException) {
            Log.d(TAG, "IMEI permission denied: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Error getting IMEI: ${e.message}")
            null
        }
    }

    @SuppressLint("HardwareIds")
    private fun tryGetAndroidId(context: Context): String? {
        return try {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )
            if (androidId.isNullOrBlank()) null else androidId
        } catch (e: Exception) {
            Log.w(TAG, "Error getting ANDROID_ID: ${e.message}")
            null
        }
    }

    private fun getOrCreateInstanceId(prefs: android.content.SharedPreferences): String {
        val key = "instance_uuid"
        prefs.getString(key, null)?.let { return it }

        val uuid = UUID.randomUUID().toString()
        prefs.edit().putString(key, uuid).apply()
        return uuid
    }

    private fun getOrCreateSalt(prefs: android.content.SharedPreferences): String {
        prefs.getString(KEY_INSTALL_SALT, null)?.let { return it }

        val salt = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_SALT, salt).apply()
        return salt
    }

    private fun hashWithSalt(rawId: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$rawId:$salt".toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
