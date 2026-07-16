package org.onekash.kashcal.sync.util

import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.NetworkType

/**
 * Shared WorkManager network constraint for sync/refresh work.
 *
 * Requires the INTERNET capability but NOT VALIDATED. Plain
 * [NetworkType.CONNECTED] maps (via JobScheduler's JobInfo) to a network
 * requirement that hard-codes NET_CAPABILITY_VALIDATED — "the OS confirmed a
 * route to the public internet". A self-hosted CalDAV/ICS server on a LAN or
 * VPN is fully reachable but has no public-internet route, so its network
 * reports INTERNET without VALIDATED and a VALIDATED-gated job never runs,
 * leaving sync stuck forever (#296).
 *
 * Supplying a custom [NetworkRequest] via
 * [Constraints.Builder.setRequiredNetworkRequest] is the platform-sanctioned
 * way to require connectivity without validation. This mirrors
 * Thunderbird-for-Android, which gates on NET_CAPABILITY_INTERNET alone.
 *
 * Single source of truth so every sync/refresh worker stays consistent.
 */
object SyncNetworkConstraints {

    /**
     * A [NetworkRequest] that requires a connected, internet-capable network
     * without requiring public-internet validation.
     *
     * [NetworkRequest.Builder] seeds NET_CAPABILITY_NOT_VPN and
     * NET_CAPABILITY_NOT_RESTRICTED by default (from
     * NetworkCapabilities.DEFAULT_CAPABILITIES), which would exclude
     * VPN/restricted networks. A server reachable only through a VPN must still
     * sync (#296), so both are removed — this matches what JobScheduler's own
     * NetworkType.CONNECTED path does before requiring VALIDATED.
     */
    fun internetNetworkRequest(): NetworkRequest =
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            // Deliberately NOT adding NET_CAPABILITY_VALIDATED — see class KDoc.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()

    /**
     * A [Constraints.Builder] pre-seeded with [internetNetworkRequest]. Callers
     * may chain further constraints (e.g. `setRequiresBatteryNotLow(true)`)
     * before building.
     *
     * The [NetworkType.CONNECTED] argument is a mandatory fallback used only on
     * API < 28; on this app's supported SDKs (minSdk 31) the [NetworkRequest]
     * is authoritative and the fallback type is inert.
     */
    fun builder(): Constraints.Builder =
        Constraints.Builder()
            .setRequiredNetworkRequest(internetNetworkRequest(), NetworkType.CONNECTED)
}
