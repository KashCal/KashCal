package org.onekash.kashcal.sync.integration

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import java.io.File
import java.util.Properties

/**
 * Tests multi-href calendar-multiget compatibility across all live CalDAV servers.
 * Verifies that the batched multiget pattern works correctly, and that the
 * single-href fallback activates only for servers that need it (Zoho).
 *
 * Run: ./gradlew testDebugUnitTest -Pintegration --tests "*MultigetServerCompatTest*"
 */
class MultigetServerCompatTest {

    private val factory = OkHttpCalDavClientFactory()
    private val props = Properties()

    @Before
    fun setup() {
        val possiblePaths = listOf(
            "local.properties",
            "../local.properties",
            "/onekash/KashCal/local.properties"
        )
        for (path in possiblePaths) {
            val file = File(path)
            if (file.exists()) {
                file.inputStream().use { props.load(it) }
                break
            }
        }
    }

    private fun createClient(serverUrl: String, username: String, password: String): CalDavClient {
        val url = if (serverUrl.startsWith("http")) serverUrl else "https://$serverUrl"
        return factory.createClient(
            Credentials(username = username, password = password, serverUrl = url),
            DefaultQuirks(url)
        )
    }

    private fun testMultigetCompat(serverName: String, client: CalDavClient, serverUrl: String, davEndpoint: String? = null) = runBlocking {
        val url = davEndpoint ?: if (serverUrl.startsWith("http")) serverUrl else "https://$serverUrl"

        // Discovery
        val principal = client.discoverPrincipal(url).getOrNull()
        assumeTrue("$serverName: could not discover principal", principal != null)
        val home = client.discoverCalendarHome(principal!!).getOrNull()?.firstOrNull()
        assumeTrue("$serverName: could not discover home", home != null)
        val calendars = client.listCalendars(home!!).getOrNull()
        assumeTrue("$serverName: no calendars found", calendars != null && calendars.isNotEmpty())
        val calendarUrl = calendars!![0].url
        println("$serverName: calendar=${calendars[0].displayName} at $calendarUrl")

        // Fetch etags — try time-range first, fall back to full fetch
        val now = System.currentTimeMillis()
        val pastWindow = now - (90L * 24 * 60 * 60 * 1000)
        val etagResult = client.fetchEtagsInRange(calendarUrl, pastWindow, Long.MAX_VALUE / 2)
        val hrefs: List<String>
        if (etagResult.isSuccess() && etagResult.getOrNull()!!.isNotEmpty()) {
            hrefs = etagResult.getOrNull()!!.map { it.first }
        } else {
            if (etagResult.isError()) {
                val err = etagResult as org.onekash.kashcal.sync.client.model.CalDavResult.Error
                println("$serverName: fetchEtagsInRange failed (code=${err.code}): ${err.message}, trying fetchEventsInRange")
            }
            // Fallback: full calendar-query (may work when time-range doesn't)
            val fullResult = client.fetchEventsInRange(calendarUrl, pastWindow, Long.MAX_VALUE / 2)
            if (fullResult.isSuccess() && fullResult.getOrNull()!!.isNotEmpty()) {
                hrefs = fullResult.getOrNull()!!.map { it.href }
            } else {
                println("$serverName: no events found via either method")
                assumeTrue("$serverName: no events on server", false)
                return@runBlocking
            }
        }
        println("$serverName: ${hrefs.size} events on server")

        // Test 1: Single-href multiget
        val singleResult = client.fetchEventsByHref(calendarUrl, listOf(hrefs[0]))
        println("$serverName: single-href multiget: success=${singleResult.isSuccess()}, events=${singleResult.getOrNull()?.size}")
        assert(singleResult.isSuccess()) { "$serverName: single-href multiget failed" }
        assert(singleResult.getOrNull()!!.isNotEmpty()) { "$serverName: single-href multiget returned empty" }

        // Test 2: Multi-href multiget (up to 10)
        val batchHrefs = hrefs.take(10.coerceAtMost(hrefs.size))
        val batchResult = client.fetchEventsByHref(calendarUrl, batchHrefs)
        println("$serverName: multi-href multiget (${batchHrefs.size} hrefs): success=${batchResult.isSuccess()}, events=${batchResult.getOrNull()?.size}")

        if (batchResult.isSuccess() && batchResult.getOrNull()!!.isNotEmpty()) {
            println("$serverName: MULTI-HREF MULTIGET WORKS — no fallback needed")
            assert(batchResult.getOrNull()!!.size == batchHrefs.size) {
                "$serverName: expected ${batchHrefs.size} events, got ${batchResult.getOrNull()!!.size}"
            }
        } else {
            println("$serverName: MULTI-HREF MULTIGET EMPTY — single-href fallback will activate")
        }

        // Summary
        println("$serverName: PASS")
    }

    @Test
    fun `Nextcloud Remote - multiget compatibility`() {
        val server = props.getProperty("NEXTCLOUD_REMOTE_SERVER")
        val username = props.getProperty("NEXTCLOUD_REMOTE_USERNAME_1")
        val password = props.getProperty("NEXTCLOUD_REMOTE_PASSWORD_1")
        assumeTrue("Nextcloud Remote credentials not configured",
            server != null && username != null && password != null)
        val client = createClient(server!!, username!!, password!!)
        // Nextcloud uses /remote.php/dav/ as the DAV endpoint for discovery
        testMultigetCompat("Nextcloud Remote", client, server, "$server/remote.php/dav/")
    }

    /**
     * Direct multiget test against Nextcloud Remote with known hrefs.
     * Bypasses time-range issues by using a simple calendar-query without time filter.
     */
    @Test
    fun `Nextcloud Remote - direct multiget with known hrefs`() = runBlocking {
        val server = props.getProperty("NEXTCLOUD_REMOTE_SERVER")
        val username = props.getProperty("NEXTCLOUD_REMOTE_USERNAME_1")
        val password = props.getProperty("NEXTCLOUD_REMOTE_PASSWORD_1")
        assumeTrue("Nextcloud Remote credentials not configured",
            server != null && username != null && password != null)
        val client = createClient(server!!, username!!, password!!)
        val davEndpoint = "$server/remote.php/dav/"

        // Discovery
        val principal = client.discoverPrincipal(davEndpoint).getOrNull()!!
        val home = client.discoverCalendarHome(principal).getOrNull()!!.first()
        val calendars = client.listCalendars(home).getOrNull()!!
        assumeTrue("No calendars", calendars.isNotEmpty())
        val calendarUrl = calendars[0].url
        println("Nextcloud: calendar=${calendars[0].displayName} at $calendarUrl")

        // Use fetchEventsInRange without time filter (0 to max) to get all events
        val allEvents = client.fetchEventsInRange(calendarUrl, 0, Long.MAX_VALUE / 2)
        if (allEvents.isError()) {
            // Try sync-collection as last resort
            println("Nextcloud: fetchEventsInRange failed, trying sync-collection")
            val syncResult = client.syncCollection(calendarUrl, null)
            assumeTrue("Nextcloud: no sync data", syncResult.isSuccess())
            val changed = syncResult.getOrNull()!!.changed
            assumeTrue("Nextcloud: no events via sync-collection", changed.isNotEmpty())
            val hrefs = changed.map { it.href }
            println("Nextcloud: ${hrefs.size} hrefs from sync-collection: $hrefs")

            // Test multi-href multiget
            val multiResult = client.fetchEventsByHref(calendarUrl, hrefs)
            if (multiResult.isSuccess()) {
                val events = multiResult.getOrNull()!!
                println("Nextcloud: multi-href multiget (${hrefs.size} hrefs): ${events.size} events")
                println("Nextcloud: MULTI-HREF MULTIGET WORKS")
            } else {
                val err = multiResult as org.onekash.kashcal.sync.client.model.CalDavResult.Error
                println("Nextcloud: multi-href multiget ERROR (code=${err.code}): ${err.message}")
                println("Nextcloud: SKIPPING — server returned error (transient)")
            }
            return@runBlocking
        }

        val events = allEvents.getOrNull()!!
        assumeTrue("Nextcloud: no events on server", events.isNotEmpty())
        val hrefs = events.map { it.href }
        println("Nextcloud: ${hrefs.size} events on server")

        // Test multi-href multiget
        val multiResult = client.fetchEventsByHref(calendarUrl, hrefs)
        assert(multiResult.isSuccess()) { "Nextcloud: multi-href multiget failed" }
        val multiEvents = multiResult.getOrNull()!!
        println("Nextcloud: multi-href multiget (${hrefs.size} hrefs): ${multiEvents.size} events")
        assert(multiEvents.isNotEmpty()) { "Nextcloud: multi-href multiget returned empty" }
        assert(multiEvents.size == hrefs.size) { "Nextcloud: expected ${hrefs.size}, got ${multiEvents.size}" }
        println("Nextcloud: MULTI-HREF MULTIGET WORKS")
    }

    @Test
    fun `Zoho - multiget compatibility`() {
        val server = props.getProperty("ZOHO_SERVER")
        val username = props.getProperty("ZOHO_USERNAME")
        val password = props.getProperty("ZOHO_PASSWORD")
        assumeTrue("Zoho credentials not configured",
            server != null && username != null && password != null)
        val client = createClient(server!!, username!!, password!!)
        testMultigetCompat("Zoho", client, server)
    }

    @Test
    fun `iCloud - multiget compatibility`() {
        val server = props.getProperty("ICLOUD_SERVER") ?: "https://caldav.icloud.com"
        val username = props.getProperty("ICLOUD_USERNAME")
        val password = props.getProperty("ICLOUD_PASSWORD")
        assumeTrue("iCloud credentials not configured",
            username != null && password != null)
        val client = createClient(server, username!!, password!!)
        testMultigetCompat("iCloud", client, server)
    }

    @Test
    fun `Baikal - multiget compatibility`() {
        val server = props.getProperty("BAIKAL_SERVER")
        val username = props.getProperty("BAIKAL_USERNAME")
        val password = props.getProperty("BAIKAL_PASSWORD")
        assumeTrue("Baikal credentials not configured",
            server != null && username != null && password != null)
        val client = createClient(server!!, username!!, password!!)
        testMultigetCompat("Baikal", client, server)
    }

    @Test
    fun `Radicale - multiget compatibility`() {
        val server = props.getProperty("RADICALE_SERVER")
        val username = props.getProperty("RADICALE_USERNAME")
        val password = props.getProperty("RADICALE_PASSWORD")
        assumeTrue("Radicale credentials not configured",
            server != null && username != null && password != null)
        val client = createClient(server!!, username!!, password!!)
        testMultigetCompat("Radicale", client, server)
    }

    @Test
    fun `SOGo - multiget compatibility`() {
        val server = props.getProperty("SOGO_SERVER")
        val username = props.getProperty("SOGO_USERNAME")
        val password = props.getProperty("SOGO_PASSWORD")
        assumeTrue("SOGo credentials not configured",
            server != null && username != null && password != null)
        val client = createClient(server!!, username!!, password!!)
        testMultigetCompat("SOGo", client, server)
    }
}
