package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves live, across every configured server, that the CalDAV collection name filter is
 * redundant with the resourcetype and component gates.
 *
 * The generic and iCloud quirks drop scheduling and notification collections (inbox, outbox,
 * notification), and the generic quirks a final `/tasks/` segment, with a reserved-word filter
 * matched on whole path segments
 * ([org.onekash.kashcal.sync.quirks.DefaultQuirks.shouldSkipCalendar],
 * [org.onekash.kashcal.sync.provider.icloud.ICloudQuirks.shouldSkipCalendar]). A substring match
 * silently hides a real collection whose path merely contains the letters ("my-inbox-friends", or
 * any account whose username contains "inbox"). This test pins the invariant that makes the strict
 * filter safe: every collection the production name filter skips is one the app wouldn't surface
 * anyway, because it lacks the `<calendar>` resourcetype or fails the VEVENT component gate. So the
 * name filter can only add false drops, never prevent a real one.
 *
 * The proof reads the raw home-set PROPFIND (via [CollectionResourceTypeProof]), not the
 * parser's filtered output, so the inbox and outbox the parser drops are visible and their
 * resourcetype can be inspected. Each server's redacted raw XML is written as a fixture for the
 * offline `CollectionResourceTypeProofFixtureTest`.
 *
 * Skips (never fails) servers without credentials, unreachable ones, and ones without CalDAV.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerCalendarResourceTypeProofTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerCalendarResourceTypeProofTest(
    private val config: CalDavServerConfig,
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        /** The production `listCalendars` PROPFIND body (`OkHttpCalDavClient`). */
        private val LIST_CALENDARS_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"
                        xmlns:cs="http://calendarserver.org/ns/" xmlns:ic="http://apple.com/ns/ical/">
                <d:prop>
                    <d:displayname/>
                    <d:resourcetype/>
                    <ic:calendar-color/>
                    <cs:getctag/>
                    <d:current-user-privilege-set/>
                    <c:supported-calendar-component-set/>
                </d:prop>
            </d:propfind>
        """.trimIndent()
    }

    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null

    @Before
    fun setup() {
        CalDavTestServerLoader.createClient(config)?.let {
            client = it.first
            creds = it.second
        }
    }

    private fun assumeReady() {
        assumeTrue("${config.name}: no credentials in local.properties", client != null)
        assumeTrue(
            "${config.name}: server unreachable at ${creds!!.davEndpoint}",
            CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint),
        )
    }

    @Test
    fun `scheduling collections never carry the calendar resourcetype`() = runBlocking {
        assumeReady()
        val c = client!!
        val cr = creds!!

        val root = if (config.usesWellKnownDiscovery) {
            c.discoverWellKnown(cr.serverUrl).getOrNull() ?: cr.serverUrl
        } else {
            cr.davEndpoint
        }
        val principal = c.discoverPrincipal(root).getOrNull()
        assumeTrue("${config.name}: no principal (CalDAV likely unsupported)", principal != null)

        val home = (c.discoverCalendarHome(principal!!) as? CalDavResult.Success)?.data?.firstOrNull()
        assumeTrue("${config.name}: no calendar-home-set", home != null)

        val raw = CollectionResourceTypeProof.fetchRawPropfind(
            CollectionResourceTypeProof.rawClient(cr.username, cr.password),
            home!!,
            LIST_CALENDARS_BODY,
        )
        assumeTrue("${config.name}: raw home-set PROPFIND failed", raw != null)

        CollectionResourceTypeProof.writeFixture("caldav", config.name, raw!!)
        val rows = CollectionResourceTypeProof.parseCollections(raw)
        assumeTrue("${config.name}: no collections parsed from home-set", rows.isNotEmpty())

        println("\n=== CalDAV resourcetype proof: ${config.name} (${rows.size} collections) ===")
        rows.forEach { println("  " + CollectionResourceTypeProof.matrixRow(config.name, it, "caldav")) }

        // The invariant, through the production predicate: every collection the name filter
        // skips is one the app wouldn't surface anyway (lacks <calendar>, or fails the VEVENT
        // gate).
        val isICloud = config.name.equals("icloud", ignoreCase = true)
        val disagreements = rows.filter {
            CollectionResourceTypeProof.calDavNameFilterSkips(it, isICloud) && it.appSurfacesAsCalendar
        }
        assertTrue(
            "${config.name}: the production name filter skips a collection the app WOULD " +
                "surface (resourcetype + VEVENT gate) — the filter is not safe redundancy: " +
                disagreements.map { CollectionResourceTypeProof.redactPii(it.href) },
            disagreements.isEmpty(),
        )

        // At least one app-visible calendar, so the walk reached live data and not an empty or
        // misdiscovered home.
        assumeTrue(
            "${config.name}: no app-visible calendar surfaced (discovery reached wrong home?)",
            rows.any { it.appSurfacesAsCalendar },
        )
    }
}
