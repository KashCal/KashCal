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
 * Live regression across the CalDAV fleet for the two scheduling-discovery
 * probes the app performs at sync time (RFC 6638):
 *   - `discoverScheduleOutboxUrl` (§2.1.1) — PROPFIND on the principal.
 *   - `supportsAutoSchedule` (§2) — OPTIONS on the calendar collection,
 *     reading the `calendar-auto-schedule` DAV-header token.
 *
 * This pins the per-server disposition observed live so a regression is caught
 * in either direction (a server we expect to advertise an outbox/capability
 * silently stopping, or a non-advertising server starting). It is DISCOVERY
 * ONLY — it never POSTs to the outbox or sends an invite.
 *
 * The OPTIONS capability MUST be probed on the calendar COLLECTION, not the
 * service root: at least one server advertises the token only on the
 * collection. This test asserts the in-app `supportsAutoSchedule` reproduces
 * the capability matrix the audit captured by hand.
 *
 * Skips (never fails) on unreachable / no-credential / discovery-failure
 * servers so it is safe in CI without the local fleet.
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
         * Whether each server advertises a schedule-outbox-URL on its principal
         * (verified live 2026-06-09 against the in-app discovery path):
         * Sabre-family + Stalwart + Zoho + Mailbox + SOGo advertise one; bare
         * Radicale and the per-user-partitioned iCloud principal do not.
         *
         * SOGo: advertises a populated schedule-outbox-URL whose href points at
         * the calendar collection itself (e.g. `/SOGo/dav/<user>/Calendar/
         * personal/`) rather than a dedicated `/outbox/`. The raw PROPFIND body
         * carries the href INSIDE the `<schedule-outbox-URL>` element (the
         * response self-href is the principal), so the parser correctly reads
         * the property value. This corrects the earlier hand-captured note that
         * SOGo returned the property empty — that was a different container
         * state; this fleet advertises it.
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
         * Whether the calendar collection advertises calendar-auto-schedule via
         * OPTIONS (verified live 2026-06-09 against the in-app discovery path):
         * Baikal-family + Nextcloud + Stalwart + SOGo advertise it; bare
         * Radicale does not. iCloud and Zoho are intentionally absent — their
         * disposition is driven by the runtime read-back, not the OPTIONS flag,
         * so we don't pin a capability baseline for them.
         *
         * SOGo's authenticated OPTIONS DAV header includes calendar-auto-schedule
         * (alongside calendar-schedule); the flag is advisory only and does not
         * by itself mean SOGo delivers on a plain PUT (see
         * ServerSideSchedulingProbeTest, which classifies SOGo's actual delivery
         * separately off the runtime signal).
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
