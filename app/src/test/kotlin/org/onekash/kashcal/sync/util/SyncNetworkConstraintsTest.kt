package org.onekash.kashcal.sync.util

import android.net.NetworkCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the shared sync network constraint requires connectivity WITHOUT
 * demanding a validated public-internet route, so background sync/refresh runs
 * on self-hosted CalDAV/ICS servers reachable only over a LAN or VPN (#296).
 *
 * Robolectric is required: the app sets testOptions isReturnDefaultValues=true,
 * so a plain-JVM test against the android.jar stubs would get
 * NetworkRequest.hasCapability()==false regardless of what was added.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SyncNetworkConstraintsTest {

    @Test
    fun `network request requires INTERNET`() {
        val request = SyncNetworkConstraints.internetNetworkRequest()
        assertTrue(
            "Sync must require the INTERNET capability",
            request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        )
    }

    @Test
    fun `network request does NOT require VALIDATED`() {
        val request = SyncNetworkConstraints.internetNetworkRequest()
        assertFalse(
            "Sync must NOT require VALIDATED — that would block LAN/VPN servers (#296)",
            request.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
    }

    @Test
    fun `network request does NOT require NOT_VPN`() {
        // NetworkRequest.Builder() seeds NET_CAPABILITY_NOT_VPN by default, so an
        // unmodified request only matches non-VPN networks. A server reachable
        // only through a VPN must still sync, so NOT_VPN must be removed — this
        // mirrors what JobScheduler's own NetworkType.CONNECTED path does (#296).
        val request = SyncNetworkConstraints.internetNetworkRequest()
        assertFalse(
            "Sync must NOT require NOT_VPN — that would block VPN-only servers (#296)",
            request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
        )
    }

    @Test
    fun `network request does NOT require NOT_RESTRICTED`() {
        // Mirrors JobScheduler's NetworkType.CONNECTED path, which removes
        // NOT_RESTRICTED so restricted networks can satisfy the job too.
        val request = SyncNetworkConstraints.internetNetworkRequest()
        assertFalse(
            "Sync must NOT require NOT_RESTRICTED (parity with NetworkType.CONNECTED)",
            request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED),
        )
    }

    @Test
    fun `constraints expose the internet-only network request`() {
        // Assert on the request as it survives Constraints.Builder.build()
        // (which runs maybeMarkCapabilitiesRestricted), i.e. what the worker
        // actually gets — not just the raw internetNetworkRequest().
        val constraints = SyncNetworkConstraints.builder().build()
        val request = constraints.requiredNetworkRequest
        assertNotNull("Constraints should carry a required NetworkRequest", request)
        assertTrue(request!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        assertFalse(
            "Built constraints must not require NOT_VPN (VPN-only servers, #296)",
            request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
        )
    }

    @Test
    fun `builder allows composing additional constraints`() {
        // The builder returns a Constraints.Builder, so a caller can chain its own
        // requirements on top of the shared network request. No scheduler in the app
        // currently requires battery-not-low; this only asserts the seam composes.
        val constraints = SyncNetworkConstraints.builder()
            .setRequiresBatteryNotLow(true)
            .build()
        assertTrue(constraints.requiresBatteryNotLow())
        assertNotNull(constraints.requiredNetworkRequest)
        assertTrue(
            constraints.requiredNetworkRequest!!
                .hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        )
    }
}
