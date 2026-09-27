package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.client.CalDavClient

/**
 * Guards issue #281 live: Xandikos advertises the RFC 3744 <all> aggregate privilege instead of
 * the leaf <write>/<write-content>, so a privilege parser that recognizes only the leaves shows
 * its calendars as read-only.
 *
 * Drives the production discovery chain (principal -> calendar-home -> listCalendars) against a
 * real Xandikos server and asserts the discovered calendars are writable. The fixture-based
 * unit test in CalDavXmlParserTest pins the captured wire bytes; this test pins the live
 * server's behavior, so a Xandikos release that changes its privilege reporting fails here
 * instead of silently.
 *
 * Auto-skips when Xandikos is unreachable (it is a local dev server, absent on CI). Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*XandikosReadOnlyDiscoveryTest*'
 */
class XandikosReadOnlyDiscoveryTest {

    private val config = CalDavServerConfig.XANDIKOS
    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null

    @Before
    fun setup() {
        val pair = CalDavTestServerLoader.createClient(config)
        if (pair != null) {
            client = pair.first
            creds = pair.second
        }
    }

    private fun assumeReady() {
        assumeTrue(
            "${config.name} credentials not available",
            client != null && creds != null
        )
        assumeTrue(
            "${config.name} server not reachable",
            CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint)
        )
    }

    @Test
    fun `discovered Xandikos calendar is writable not read-only`() = runBlocking {
        assumeReady()
        val c = client!!
        val endpoint = creds!!.davEndpoint

        val principal = c.discoverPrincipal(endpoint).getOrNull()
        assertNotNull("Should discover principal", principal)
        val home = c.discoverCalendarHome(principal!!).getOrNull()?.firstOrNull()
        assertNotNull("Should discover calendar home", home)
        val calendars = c.listCalendars(home!!).getOrNull()
        assertNotNull("Should list calendars", calendars)

        val calendarColls = calendars!!.filter {
            !it.url.contains("inbox") && !it.url.contains("outbox")
        }
        assertTrue("Xandikos should expose at least one calendar", calendarColls.isNotEmpty())
        assertTrue(
            "Xandikos calendars must be discovered as writable (issue #281); " +
                "found read-only: ${calendarColls.filter { it.isReadOnly }.map { it.url }}",
            calendarColls.none { it.isReadOnly }
        )
    }

    @Test
    fun `metadata refresh path also reports Xandikos calendar writable`() = runBlocking {
        // The pull metadata-refresh path (getCtag -> extractCalendarMetadata) parses the
        // same privilege-set independently of listCalendars, so both parser paths are
        // covered against the live server. isReadOnly=false means the privilege-set was read
        // as writable (the <all> mapping); null means no privilege-set was present, which
        // Xandikos doesn't do. The assertion fails only on true, so a null also passes.
        assumeReady()
        val c = client!!
        val endpoint = creds!!.davEndpoint

        val principal = c.discoverPrincipal(endpoint).getOrNull()!!
        val home = c.discoverCalendarHome(principal).getOrNull()!!.first()
        val calendar = c.listCalendars(home).getOrNull()!!
            .first { !it.url.contains("inbox") && !it.url.contains("outbox") }

        val probe = c.getCtag(calendar.url).getOrNull()
        assertNotNull("getCtag probe should succeed", probe)
        assertFalse(
            "Metadata refresh must read the <all> privilege as writable (issue #281)",
            probe!!.isReadOnly == true
        )
    }
}
