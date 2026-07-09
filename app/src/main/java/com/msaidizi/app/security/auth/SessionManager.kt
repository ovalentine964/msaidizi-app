package com.msaidizi.app.security.auth

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session manager — handles session lifecycle, timeout, and device binding.
 *
 * Per SECURITY_ARCHITECTURE.md:
 * - Session timeout: 5 minutes of inactivity requires re-auth
 * - Device binding: stolen device flagged, sessions revoked on report
 * - Trusted devices: up to 3 trusted devices per user
 * - Session tied to device_id and IP subnet
 */
@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenStorage: SecureTokenStorage,
    private val jwtTokenManager: JwtTokenManager
) {
    companion object {
        private const val SESSION_TIMEOUT_MS = 5 * 60 * 1000L  // 5 minutes inactivity
        private const val MAX_TRUSTED_DEVICES = 3
        private const val DEVICE_ID_PREF = "angavu_device_binding"
        private const val KEY_BOUND_DEVICES = "bound_devices"
        private const val KEY_IP_SUBNET = "session_ip_subnet"
        private const val KEY_SESSION_TOKEN = "session_token"
        private const val MAX_CONCURRENT_SESSIONS = 3
    }

    sealed class SessionState {
        object Inactive : SessionState()
        object Active : SessionState()
        object Expired : SessionState()
        object RequiresReauth : SessionState()
        object SuspiciousActivity : SessionState()
    }

    private val _state = MutableStateFlow<SessionState>(SessionState.Inactive)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var lastActivityTime: Long = 0
    private var sessionCheckThread: Thread? = null
    private var boundIpSubnet: String? = null

    /**
     * Get or create a stable device ID for binding.
     * Uses ANDROID_ID (survives app reinstalls, changes on factory reset).
     */
    fun getDeviceId(): String {
        val storedId = tokenStorage.getDeviceId()
        if (storedId != null) return storedId

        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString()

        // Hash the ANDROID_ID for privacy (don't store raw device identifier)
        val hashedId = com.msaidizi.app.core.util.CryptoUtils.sha256("angavu:$androidId")
        tokenStorage.saveDeviceId(hashedId)
        return hashedId
    }

    /**
     * Start a new session after successful authentication.
     * Binds session to current IP subnet for hijack detection.
     */
    fun startSession(userId: String, ipSubnet: String? = null) {
        lastActivityTime = System.currentTimeMillis()
        boundIpSubnet = ipSubnet

        // Generate a cryptographically random session token
        val sessionToken = java.security.SecureRandom().let { rng ->
            val bytes = ByteArray(32); rng.nextBytes(bytes)
            bytes.joinToString("") { "%02x".format(it) }
        }

        // Store session binding
        val prefs = context.getSharedPreferences(DEVICE_ID_PREF, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_IP_SUBNET, ipSubnet ?: "")
            .putString(KEY_SESSION_TOKEN, sessionToken)
            .apply()

        _state.value = SessionState.Active
        Timber.i("Session started for user %s on device %s (IP subnet bound: %b)",
            userId, getDeviceId(), ipSubnet != null)
        startSessionMonitor()
    }

    /**
     * Record user activity to keep session alive.
     * Validates IP subnet hasn't changed (session hijack detection).
     */
    fun recordActivity(currentIpSubnet: String? = null) {
        // Session hijack detection: verify IP subnet hasn't changed
        if (boundIpSubnet != null && currentIpSubnet != null && boundIpSubnet != currentIpSubnet) {
            Timber.e("Session hijack detected: IP subnet changed from %s to %s",
                boundIpSubnet, currentIpSubnet)
            _state.value = SessionState.SuspiciousActivity
            return
        }

        lastActivityTime = System.currentTimeMillis()
        if (_state.value is SessionState.Expired || _state.value is SessionState.RequiresReauth) {
            _state.value = SessionState.RequiresReauth
        }
    }

    /**
     * Check if the current session is still valid.
     */
    fun isSessionValid(): Boolean {
        if (_state.value !is SessionState.Active) return false
        val elapsed = System.currentTimeMillis() - lastActivityTime
        return elapsed < SESSION_TIMEOUT_MS
    }

    /**
     * End the current session (logout).
     * Clears all session state including IP binding and session token.
     */
    fun endSession() {
        sessionCheckThread?.interrupt()
        sessionCheckThread = null
        lastActivityTime = 0
        boundIpSubnet = null

        val prefs = context.getSharedPreferences(DEVICE_ID_PREF, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_IP_SUBNET)
            .remove(KEY_SESSION_TOKEN)
            .apply()

        _state.value = SessionState.Inactive
        Timber.i("Session ended — all session state cleared")
    }

    /**
     * Check if this device is bound/trusted for the user.
     */
    fun isDeviceTrusted(userId: String): Boolean {
        val prefs = context.getSharedPreferences(DEVICE_ID_PREF, Context.MODE_PRIVATE)
        val boundDevices = prefs.getStringSet(KEY_BOUND_DEVICES, emptySet()) ?: emptySet()
        val currentDevice = getDeviceId()
        return boundDevices.contains("$userId:$currentDevice")
    }

    /**
     * Bind current device as trusted for the user.
     * Limits to MAX_TRUSTED_DEVICES per user.
     */
    fun bindDevice(userId: String): Boolean {
        val prefs = context.getSharedPreferences(DEVICE_ID_PREF, Context.MODE_PRIVATE)
        val allBound = prefs.getStringSet(KEY_BOUND_DEVICES, mutableSetOf())?.toMutableSet()
            ?: mutableSetOf()

        // Count devices for this user
        val userDevices = allBound.filter { it.startsWith("$userId:") }
        val currentDevice = "$userId:${getDeviceId()}"

        if (userDevices.size >= MAX_TRUSTED_DEVICES && !userDevices.contains(currentDevice)) {
            Timber.w("Max trusted devices (%d) reached for user %s", MAX_TRUSTED_DEVICES, userId)
            return false
        }

        allBound.add(currentDevice)
        prefs.edit().putStringSet(KEY_BOUND_DEVICES, allBound).apply()
        Timber.i("Device bound for user %s", userId)
        return true
    }

    /**
     * Unbind a specific device.
     */
    fun unbindDevice(userId: String, deviceId: String) {
        val prefs = context.getSharedPreferences(DEVICE_ID_PREF, Context.MODE_PRIVATE)
        val allBound = prefs.getStringSet(KEY_BOUND_DEVICES, mutableSetOf())?.toMutableSet()
            ?: mutableSetOf()
        allBound.remove("$userId:$deviceId")
        prefs.edit().putStringSet(KEY_BOUND_DEVICES, allBound).apply()
        Timber.i("Device unbound for user %s", userId)
    }

    /**
     * Revoke all devices for a user (e.g., after account compromise).
     */
    fun revokeAllDevices(userId: String) {
        val prefs = context.getSharedPreferences(DEVICE_ID_PREF, Context.MODE_PRIVATE)
        val allBound = prefs.getStringSet(KEY_BOUND_DEVICES, mutableSetOf())?.toMutableSet()
            ?: mutableSetOf()
        allBound.removeAll { it.startsWith("$userId:") }
        prefs.edit().putStringSet(KEY_BOUND_DEVICES, allBound).apply()
        Timber.w("All devices revoked for user %s", userId)
    }

    private fun startSessionMonitor() {
        sessionCheckThread?.interrupt()
        sessionCheckThread = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(30_000) // Check every 30 seconds
                    if (_state.value is SessionState.Active) {
                        val elapsed = System.currentTimeMillis() - lastActivityTime
                        if (elapsed >= SESSION_TIMEOUT_MS) {
                            _state.value = SessionState.Expired
                            Timber.w("Session expired due to %dms inactivity", elapsed)
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // Normal shutdown
            }
        }.apply {
            name = "session-monitor"
            isDaemon = true
            start()
        }
    }
}
