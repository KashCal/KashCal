package org.onekash.kashcal.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks whether the default network is online and metered, and runs a callback when it comes
 * back after being offline.
 *
 * [KashCalApplication][org.onekash.kashcal.KashCalApplication] calls [startMonitoring] in
 * `onCreate`, with a callback that requests a sync, and [stopMonitoring] in `onTerminate`. The
 * network callback must be unregistered or it leaks. It uses `registerDefaultNetworkCallback`,
 * so `onLost` means no default network is left.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "NetworkMonitor"
    }

    private val connectivityManager: ConnectivityManager? =
        try {
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get ConnectivityManager", e)
            null
        }

    // Kept for unregistration; non-null while monitoring.
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val _isOnline = MutableStateFlow(safeCheckCurrentConnectivity())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isMetered = MutableStateFlow(safeCheckIfMetered())
    val isMetered: StateFlow<Boolean> = _isMetered.asStateFlow()

    private var onNetworkRestored: (() -> Unit)? = null

    /** Returns [checkCurrentConnectivity], or true if it throws (assume online, so sync runs). */
    private fun safeCheckCurrentConnectivity(): Boolean {
        return try {
            checkCurrentConnectivity()
        } catch (e: Exception) {
            Log.e(TAG, "Failed initial connectivity check, assuming online", e)
            true
        }
    }

    /** Returns [checkIfMetered], or false (not metered) if it throws. */
    private fun safeCheckIfMetered(): Boolean {
        return try {
            checkIfMetered()
        } catch (e: Exception) {
            Log.e(TAG, "Failed initial metered check, assuming not metered", e)
            false
        }
    }

    /**
     * Returns true if the active network has the INTERNET capability, or if there is no
     * ConnectivityManager. Synchronous; seeds [isOnline] and the callback's offline state.
     *
     * VALIDATED is not required: it means the OS's public-internet probe succeeded, and a
     * network that reaches a self-hosted CalDAV or ICS server on a LAN or VPN may have no
     * public route. Requiring it would treat that network as offline and leave sync stuck
     * forever (#296).
     */
    fun checkCurrentConnectivity(): Boolean {
        val cm = connectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Returns true if the active network lacks NOT_METERED (for example cellular). */
    private fun checkIfMetered(): Boolean {
        val cm = connectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Registers the default-network callback that keeps [isOnline] and [isMetered] current. A
     * second call while monitoring is ignored.
     *
     * @param onNetworkRestored runs when a network becomes available after the monitor saw
     *   none.
     */
    fun startMonitoring(onNetworkRestored: (() -> Unit)? = null) {
        if (networkCallback != null) {
            Log.w(TAG, "NetworkMonitor already started, ignoring duplicate call")
            return
        }

        this.onNetworkRestored = onNetworkRestored

        val callback = object : ConnectivityManager.NetworkCallback() {
            private var wasOffline = !checkCurrentConnectivity()

            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available")
                val wasOfflineBefore = wasOffline
                _isOnline.value = true
                wasOffline = false

                if (wasOfflineBefore) {
                    Log.d(TAG, "Network restored, triggering callback")
                    this@NetworkMonitor.onNetworkRestored?.invoke()
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
                _isOnline.value = false
                wasOffline = true
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isNotMetered = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

                // Requires only INTERNET, as [checkCurrentConnectivity] does. The two must
                // match or the flag flickers back to offline when capabilities update on an
                // unvalidated network.
                _isOnline.value = hasInternet
                _isMetered.value = !isNotMetered

                Log.d(TAG, "Capabilities changed: online=${_isOnline.value}, metered=${_isMetered.value}")
            }

            override fun onUnavailable() {
                Log.d(TAG, "Network unavailable")
                _isOnline.value = false
                wasOffline = true
            }
        }

        val cm = connectivityManager
        if (cm == null) {
            Log.w(TAG, "ConnectivityManager unavailable, cannot start monitoring")
            return
        }

        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
            Log.d(TAG, "NetworkMonitor started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    /**
     * Unregisters the network callback and drops the restore callback, so neither leaks. Safe
     * to call without [startMonitoring].
     */
    fun stopMonitoring() {
        val cm = connectivityManager
        networkCallback?.let { callback ->
            try {
                cm?.unregisterNetworkCallback(callback)
                Log.d(TAG, "NetworkMonitor stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister network callback", e)
            }
        }
        networkCallback = null
        onNetworkRestored = null
    }

    /** Replaces the callback [startMonitoring] runs when the network is restored. */
    fun setOnNetworkRestored(callback: (() -> Unit)?) {
        onNetworkRestored = callback
    }

    /** Returns true while the network callback is registered. */
    fun isMonitoring(): Boolean = networkCallback != null
}
