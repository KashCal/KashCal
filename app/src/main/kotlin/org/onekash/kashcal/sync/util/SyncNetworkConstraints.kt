package org.onekash.kashcal.sync.util

import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.NetworkType

/**
 * Builds the WorkManager network constraint every sync and refresh worker uses, so they stay
 * consistent (single source of truth).
 *
 * It requires the INTERNET capability but not VALIDATED. Plain [NetworkType.CONNECTED] maps,
 * through JobScheduler's JobInfo, to a requirement that hard-codes NET_CAPABILITY_VALIDATED (the
 * OS confirmed a route to the public internet). A self-hosted CalDAV or ICS server on a LAN or
 * VPN is reachable but has no public route, so its network reports INTERNET without VALIDATED
 * and a VALIDATED-gated job never runs: sync is stuck forever (#296).
 *
 * A custom [NetworkRequest] through [Constraints.Builder.setRequiredNetworkRequest] is the
 * platform's way to require connectivity without validation.
 */
object SyncNetworkConstraints {

    /**
     * Returns a [NetworkRequest] for a connected, internet-capable network, without
     * public-internet validation.
     *
     * [NetworkRequest.Builder] seeds NET_CAPABILITY_NOT_VPN and NET_CAPABILITY_NOT_RESTRICTED by
     * default (from NetworkCapabilities.DEFAULT_CAPABILITIES), which would exclude VPN and
     * restricted networks. A server reachable only through a VPN must still sync (#296), so both
     * are removed, as JobScheduler's own NetworkType.CONNECTED path does before requiring
     * VALIDATED.
     */
    fun internetNetworkRequest(): NetworkRequest =
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            // Never add NET_CAPABILITY_VALIDATED; the class doc says why.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()

    /**
     * Returns a [Constraints.Builder] seeded with [internetNetworkRequest]; callers may chain
     * more constraints (for example `setRequiresBatteryNotLow(true)`) before building.
     *
     * The [NetworkType.CONNECTED] argument is a required fallback used only below API 28; at
     * minSdk 31 the [NetworkRequest] decides and the fallback has no effect.
     */
    fun builder(): Constraints.Builder =
        Constraints.Builder()
            .setRequiredNetworkRequest(internetNetworkRequest(), NetworkType.CONNECTED)
}
