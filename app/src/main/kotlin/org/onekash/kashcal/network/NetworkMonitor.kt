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
 * Singleton for monitoring network connectivity state.
 *
 * Part of the offline-first architecture - enables reactive UI updates
 * for network status and triggers sync when connectivity is restored.
 *
 * Features:
 * - Exposes StateFlow<Boolean> isOnline for reactive UI updates
 * - Exposes StateFlow<Boolean> isMetered for metered network handling
 * - Proper lifecycle management to prevent memory leaks
 * - Callback support for network restore events (e.g., trigger sync)
 *
 * Usage:
 * - Inject via Hilt (@Inject constructor)
 * - Call startMonitoring() in Application.onCreate()
 * - Call stopMonitoring() when app terminates (optional)
 * - Collect isOnline flow in ViewModels/Composables for UI updates
 *
 * Best Practices:
 * - NetworkCallback MUST be unregistered to prevent memory leaks
 * - Uses registerDefaultNetworkCallback() for reliable offline detection
 * - onLost callback means no default network available
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

    // Network callback - kept as instance variable for unregistration
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // StateFlows for reactive observation
    private val _isOnline = MutableStateFlow(safeCheckCurrentConnectivity())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isMetered = MutableStateFlow(safeCheckIfMetered())
    val isMetered: StateFlow<Boolean> = _isMetered.asStateFlow()

    // Callback for network restore events
    private var onNetworkRestored: (() -> Unit)? = null

    /**
     * Safe wrapper for connectivity check that doesn't throw.
     * Returns true (assume online) if check fails.
     */
    private fun safeCheckCurrentConnectivity(): Boolean {
        return try {
            checkCurrentConnectivity()
        } catch (e: Exception) {
            Log.e(TAG, "Failed initial connectivity check, assuming online", e)
            true // Assume online to allow sync attempts
        }
    }

    /**
     * Safe wrapper for metered check that doesn't throw.
     * Returns false (not metered) if check fails.
     */
    private fun safeCheckIfMetered(): Boolean {
        return try {
            checkIfMetered()
        } catch (e: Exception) {
            Log.e(TAG, "Failed initial metered check, assuming not metered", e)
            false
        }
    }

    /**
     * Check current connectivity synchronously.
     * Used for initial state and non-reactive checks.
     *
     * @return true if device has validated internet connectivity
     */
    fun checkCurrentConnectivity(): Boolean {
        val cm = connectivityManager ?: return true // Assume online if unavailable
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Check if current connection is metered (e.g., cellular).
     */
    private fun checkIfMetered(): Boolean {
        val cm = connectivityManager ?: return false // Assume not metered if unavailable
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Start monitoring network connectivity.
     *
     * Call this in Application.onCreate() or main Activity.onCreate().
     *
     * @param onNetworkRestored Optional callback when network becomes available.
     *                          Used to trigger sync when connectivity is restored.
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

                // Trigger callback if transitioning from offline to online
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
                val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val isNotMetered = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

                _isOnline.value = hasInternet && isValidated
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
     * Stop monitoring network connectivity.
     *
     * Call this to prevent memory leaks.
     * Safe to call even if startMonitoring() wasn't called.
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

    /**
     * Set callback for network restore events.
     *
     * @param callback Function to invoke when network is restored.
     *                 Typically used to trigger pending sync operations.
     */
    fun setOnNetworkRestored(callback: (() -> Unit)?) {
        onNetworkRestored = callback
    }

    /**
     * Check if monitoring is currently active.
     */
    fun isMonitoring(): Boolean = networkCallback != null
}
