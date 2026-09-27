package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins, per server, the two RFC 6638 scheduling-discovery probes that account setup and calendar
 * refresh run (`persistSchedulingDiscovery`):
 *  - `discoverScheduleOutboxUrl` (§2.1.1): PROPFIND on the principal.
 *  - `supportsAutoSchedule` (§2): OPTIONS on the calendar collection, reading the
 *    `calendar-auto-schedule` DAV-header token.
 *
 * A change fails in either direction: a server that advertised stops, or one that didn't starts.
 * Discovery only: it never POSTs to the outbox or sends an invite.
 *
 * The OPTIONS capability must be probed on the calendar collection, not the service root: at
 * least one server advertises the token only on the collection.
 *
 * Skips, never fails, on a server with no credentials, no baseline, no reachable endpoint, or a
 * failed discovery step, so it is safe in CI without the local servers.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerSchedulingDiscoveryTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerSchedulingDiscoveryTest(
    private val config: CalDavServerConfig
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        /**
         * Whether each server advertises a schedule-outbox-URL on its principal, verified live
         * 2026-06-09 through the app's discovery: the Sabre-based servers, Stalwart, Zoho, Mailbox,
         * SOGo and Fastmail do; bare Radicale doesn't. The per-user-partitioned iCloud principal
         * doesn't either, but iCloud has no entry and skips.
         *
         * SOGo's schedule-outbox-URL points at the calendar collection itself (e.g.
         * `/SOGo/dav/<user>/Calendar/personal/`), not a dedicated `/outbox/`. The href sits inside
         * the `<schedule-outbox-URL>` element (the response's own href is the principal), so the
         * parser reads the property value. A SOGo container in a different state has returned the
         * property empty.
         */
        private val OUTBOX_ADVERTISED: Map<String, Boolean> = mapOf(
            "Stalwart" to true,
            "Baikal" to true,
            "BaikalDigest" to true,
            "Nextcloud" to true,
            "Zoho" to true,
            "Mailbox" to true,
            "SOGo" to true,
            "Fastmail" to true,
            "Radicale" to false,
        )

        /**
         * Whether the calendar collection advertises calendar-auto-schedule via OPTIONS, verified
         * live 2026-06-09 through the app's discovery: Baikal, BaikalDigest, Nextcloud, Stalwart,
         * SOGo and Fastmail do; bare Radicale doesn't. iCloud and Zoho are left out on purpose:
         * their delivery is classified from the read-back, not this flag. Servers without an
         * entry skip.
         *
         * SOGo's authenticated OPTIONS DAV header includes calendar-auto-schedule (with
         * calendar-schedule), but that alone doesn't mean SOGo delivers on a plain PUT;
         * `ServerSideSchedulingProbeTest` classifies its delivery from the read-back.
         */
        private val CAPABILITY_ADVERTISED: Map<String, Boolean> = mapOf(
            "Stalwart" to true,
            "Baikal" to true,
            "BaikalDigest" to true,
            "Nextcloud" to true,
            "SOGo" to true,
            "Fastmail" to true,
            "Radicale" to false,
        )
    }

    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null

    @Before
    fun setup() {
        CalDavTestServerLoader.createClient(config)?.let {
            client = it.first; creds = it.second
        }
    }

    private fun assumeReady() {
        assumeTrue("${config.name} credentials not available", client != null && creds != null)
        assumeTrue(
            "${config.name} server not reachable",
            CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint)
        )
    }

    private suspend fun resolveCaldavRoot(): String {
        val c = client!!
        val endpoint = creds!!.davEndpoint
        return if (config.usesWellKnownDiscovery) {
            c.discoverWellKnown(endpoint).getOrNull() ?: endpoint
        } else endpoint
    }

    private suspend fun discoverCalendar(principal: String): String? {
        val home = client!!.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        return client!!.listCalendars(home).getOrNull()
            ?.firstOrNull { !it.url.contains("inbox") && !it.url.contains("outbox") }?.url
    }

    @Test
    fun `schedule-outbox-URL discovery matches the recorded baseline`() = runBlocking {
        assumeReady()
        val expected = OUTBOX_ADVERTISED[config.name]
        assumeTrue("No outbox baseline recorded for ${config.name}", expected != null)

        val c = client!!
        val principal = c.discoverPrincipal(resolveCaldavRoot()).getOrNull()
        assumeTrue("${config.name}: principal discovery failed", principal != null)

        val result = c.discoverScheduleOutboxUrl(principal!!)
        assumeTrue(
            "${config.name}: outbox PROPFIND failed: ${(result as? CalDavResult.Error)?.message}",
            result.isSuccess()
        )
        val outbox = (result as CalDavResult.Success).data
        println("=== OUTBOX DISCOVERY: ${config.name} -> ${outbox ?: "(none)"} ===")

        assertEquals(
            "${config.name} schedule-outbox-URL advertisement changed from recorded baseline",
            expected, outbox != null
        )
    }

    @Test
    fun `auto-schedule capability on the collection matches the recorded baseline`() = runBlocking {
        assumeReady()
        val expected = CAPABILITY_ADVERTISED[config.name]
        assumeTrue("No capability baseline recorded for ${config.name}", expected != null)

        val c = client!!
        val principal = c.discoverPrincipal(resolveCaldavRoot()).getOrNull()
        assumeTrue("${config.name}: principal discovery failed", principal != null)
        val calendarUrl = discoverCalendar(principal!!)
        assumeTrue("${config.name}: no calendar found", calendarUrl != null)

        val result = c.supportsAutoSchedule(calendarUrl!!)
        assumeTrue(
            "${config.name}: OPTIONS failed: ${(result as? CalDavResult.Error)?.message}",
            result.isSuccess()
        )
        val supported = (result as CalDavResult.Success).data
        println("=== AUTO-SCHEDULE CAPABILITY: ${config.name} ($calendarUrl) -> $supported ===")

        assertEquals(
            "${config.name} calendar-auto-schedule advertisement changed from recorded baseline",
            expected, supported
        )
    }
}
