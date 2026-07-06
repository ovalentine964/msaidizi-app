package com.msaidizi.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber


/**
 * Network connectivity monitor.
 * Detects WiFi, cellular, and connectivity changes.
 * Used by SyncManager to determine when to sync.
 */
class NetworkMonitor(
    private val context: Context
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
        as ConnectivityManager

    private val _networkState = MutableStateFlow(NetworkState.DISCONNECTED)
    val networkState: StateFlow<NetworkState> = _networkState

    private val _connectionType = MutableStateFlow(ConnectionType.NONE)
    val connectionType: StateFlow<ConnectionType> = _connectionType

    private var isRegistered = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Start monitoring network connectivity.
     */
    fun startMonitoring() {
        if (isRegistered) return

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _networkState.value = NetworkState.CONNECTED
                _connectionType.value = getConnectionType(network)
                Timber.d("Network available: ${_connectionType.value}")
            }

            override fun onLost(network: Network) {
                _networkState.value = NetworkState.DISCONNECTED
                _connectionType.value = ConnectionType.NONE
                Timber.d("Network lost")
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                _connectionType.value = getConnectionTypeFromCapabilities(capabilities)
                Timber.d("Network capabilities changed: ${_connectionType.value}")
            }
        }

        connectivityManager.registerNetworkCallback(networkRequest, callback)
        networkCallback = callback

        // Check initial state
        val activeNetwork = connectivityManager.activeNetwork
        if (activeNetwork != null) {
            _networkState.value = NetworkState.CONNECTED
            _connectionType.value = getConnectionType(activeNetwork)
        }

        isRegistered = true
        Timber.d("Network monitoring started")
    }

    /**
     * Stop monitoring network connectivity.
     * Unregisters the SAME callback instance that was registered.
     */
    fun stopMonitoring() {
        if (!isRegistered) return
        try {
            networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            Timber.w(e, "Error unregistering network callback")
        }
        networkCallback = null
        isRegistered = false
    }

    /**
     * Check if currently connected to the internet.
     */
    fun isConnected(): Boolean {
        return _networkState.value == NetworkState.CONNECTED
    }

    /**
     * Check if connected via WiFi.
     */
    fun isWifi(): Boolean {
        return _connectionType.value == ConnectionType.WIFI
    }

    /**
     * Check if connected via cellular.
     */
    fun isCellular(): Boolean {
        return _connectionType.value == ConnectionType.CELLULAR
    }

    /**
     * Check if sync should happen based on current connectivity.
     * Prefers WiFi, falls back to cellular for critical data.
     */
    fun shouldSync(wifiOnly: Boolean = true): Boolean {
        if (!isConnected()) return false
        if (wifiOnly && !isWifi()) return false
        return true
    }

    /**
     * Get connection type for a network.
     */
    private fun getConnectionType(network: Network): ConnectionType {
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return ConnectionType.NONE
        return getConnectionTypeFromCapabilities(capabilities)
    }

    /**
     * Get connection type from capabilities.
     */
    private fun getConnectionTypeFromCapabilities(capabilities: NetworkCapabilities): ConnectionType {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.ETHERNET
            else -> ConnectionType.OTHER
        }
    }
}

/**
 * Network connectivity states.
 */
enum class NetworkState {
    CONNECTED,
    DISCONNECTED
}

/**
 * Connection types.
 */
enum class ConnectionType {
    NONE,
    WIFI,
    CELLULAR,
    ETHERNET,
    OTHER
}
