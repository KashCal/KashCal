package org.onekash.kashcal.sync.integration

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.UUID

/**
 * Integration test for Baikal CalDAV server configured with HTTP Digest authentication.
 *
 * Proves that KashCal's DigestAuthenticator handles the full challenge-response flow
 * against a real SabreDAV Digest auth implementation, not just MockWebServer.
 *
 * Run: ./gradlew testDebugUnitTest --tests "*BaikalDigestAuthIntegrationTest*"
 *
 * Prerequisites:
 * - Baikal server configured for Digest auth running at localhost:8083
 * - Credentials in local.properties:
 *   BAIKAL_DIGEST_SERVER=http://localhost:8083
 *   BAIKAL_DIGEST_USERNAME=testuser1
 *   BAIKAL_DIGEST_PASSWORD=testpass1
 *
 * Start Digest-configured Baikal:
 *   docker run -d --name baikal-digest-test -p 8083:80 ckulka/baikal:nginx
 *   Then configure Digest auth in SabreDAV config and create test user.
 */
class BaikalDigestAuthIntegrationTest {

    private lateinit var client: CalDavClient
    private lateinit var clientFactory: OkHttpCalDavClientFactory
    private var baseServerUrl: String = "http://localhost:8083"
    private var davEndpoint: String = "http://localhost:8083/dav.php/"
    private var username: String? = null
    private var password: String? = null

    // Test state
    private var calendarUrl: String? = null
    private var testEventUrl: String? = null
    private var testEventEtag: String? = null
    private val testUid = "test-digest-${UUID.randomUUID()}@kashcal.test"

    private val icsDateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'").apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Before
    fun setup() {
        loadCredentials()

        val quirks = DefaultQuirks(baseServerUrl)
        clientFactory = OkHttpCalDavClientFactory()

        val creds = if (username != null && password != null) {
            Credentials(username = username!!, password = password!!, serverUrl = davEndpoint)
        } else {
            Credentials(username = "dummy", password = "dummy", serverUrl = davEndpoint)
        }
        client = clientFactory.createClient(creds, quirks)
    }

    @After
    fun cleanup() = runBlocking {
        if (testEventUrl != null) {
            client.deleteEvent(testEventUrl!!, testEventEtag ?: "")
        }
    }

    private fun loadCredentials() {
        val possiblePaths = listOf(
            "local.properties",
            "../local.properties",
            "/onekash/KashCal/local.properties"
        )

        for (path in possiblePaths) {
            val propsFile = File(path)
            if (propsFile.exists()) {
                propsFile.readLines().forEach { line ->
                    val parts = line.split("=").map { it.trim() }
                    if (parts.size == 2) {
                        when (parts[0]) {
                            "BAIKAL_DIGEST_SERVER" -> {
                                baseServerUrl = parts[1]
                                davEndpoint = parts[1] + "/dav.php/"
                            }
                            "BAIKAL_DIGEST_USERNAME" -> username = parts[1]
                            "BAIKAL_DIGEST_PASSWORD" -> password = parts[1]
                        }
                    }
                }
                if (username != null && password != null) break
            }
        }
    }

    private fun assumeDigestServerAvailable() {
        assumeTrue(
            "Baikal Digest credentials not available in local.properties",
            username != null && password != null
        )

        try {
            val url = URL(davEndpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "OPTIONS"
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val code = conn.responseCode

            assumeTrue(
                "Digest Baikal not available (code: $code)",
                code == 401 || code in 200..299
            )

            // Verify this instance actually uses Digest auth (not Basic)
            if (code == 401) {
                val wwwAuth = conn.getHeaderField("WWW-Authenticate") ?: ""
                assumeTrue(
                    "Server doesn't use Digest auth: $wwwAuth",
                    wwwAuth.contains("Digest", ignoreCase = true)
                )
            }
        } catch (e: Exception) {
            assumeTrue("Digest Baikal not reachable: ${e.message}", false)
        }
    }

    private suspend fun discoverCalendar(): String? {
        val principal = client.discoverPrincipal(davEndpoint).getOrNull() ?: return null
        val home = client.discoverCalendarHome(principal).getOrNull() ?: return null
        val calendars = client.listCalendars(home).getOrNull() ?: return null

        return calendars.firstOrNull { cal ->
            !cal.url.contains("inbox") && !cal.url.contains("outbox")
        }?.url
    }

    // ========== DISCOVERY TESTS ==========

    @Test
    fun `discover principal URL on Digest-auth Baikal`() = runBlocking {
        assumeDigestServerAvailable()

        val result = client.discoverPrincipal(davEndpoint)
        assert(result.isSuccess()) {
            "Failed to discover principal through Digest auth: ${(result as? CalDavResult.Error)?.message}"
        }

        val principal = result.getOrNull()!!
        println("Digest Baikal principal URL: $principal")
        assert(principal.contains("principals")) { "Principal should contain 'principals'" }
    }

    @Test
    fun `discover calendar home on Digest-auth Baikal`() = runBlocking {
        assumeDigestServerAvailable()

        val principal = client.discoverPrincipal(davEndpoint).getOrNull()
        assumeTrue("Could not discover principal", principal != null)

        val result = client.discoverCalendarHome(principal!!)
        assert(result.isSuccess()) {
            "Failed to discover calendar home through Digest auth: ${(result as? CalDavResult.Error)?.message}"
        }

        val home = result.getOrNull()!!
        println("Digest Baikal calendar home: $home")
        assert(home.isNotEmpty()) { "Calendar home should not be empty" }
    }

    @Test
    fun `list calendars on Digest-auth Baikal`() = runBlocking {
        assumeDigestServerAvailable()

        val principal = client.discoverPrincipal(davEndpoint).getOrNull()
        assumeTrue("Could not discover principal", principal != null)

        val home = client.discoverCalendarHome(principal!!).getOrNull()
        assumeTrue("Could not discover calendar home", home != null)

        val result = client.listCalendars(home!!)
        assert(result.isSuccess()) {
            "Failed to list calendars through Digest auth: ${(result as? CalDavResult.Error)?.message}"
        }

        val calendars = result.getOrNull()!!
        println("Found ${calendars.size} calendar(s) on Digest Baikal:")
        calendars.forEach { cal ->
            println("  - ${cal.displayName}: ${cal.url}")
        }
        assert(calendars.isNotEmpty()) { "Should have at least one calendar" }
    }

    // ========== CRUD TEST ==========

    @Test
    fun `create and fetch event on Digest-auth Baikal`() = runBlocking {
        assumeDigestServerAvailable()

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on Digest Baikal", calendarUrl != null)

        val icsContent = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Baikal Digest Test//EN
BEGIN:VEVENT
UID:$testUid
DTSTAMP:${icsDateFormat.format(java.util.Date())}
DTSTART:20260201T100000Z
DTEND:20260201T110000Z
SUMMARY:Digest Auth Test Event
DESCRIPTION:Created by Digest auth integration test
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val result = client.createEvent(calendarUrl!!, testUid, icsContent)
        assert(result.isSuccess()) {
            "Failed to create event through Digest auth: ${(result as? CalDavResult.Error)?.message}"
        }

        val (url, etag) = result.getOrNull()!!
        testEventUrl = url
        testEventEtag = etag

        println("Created event on Digest Baikal: $url (etag: $etag)")

        // Verify by fetching
        val fetchResult = client.fetchEvent(url)
        assert(fetchResult.isSuccess()) { "Failed to fetch event through Digest auth" }

        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        assert(fetchedIcs.contains("SUMMARY:Digest Auth Test Event"))
    }

    // ========== SYNC TOKEN TEST ==========

    @Test
    fun `get sync token from Digest-auth Baikal`() = runBlocking {
        assumeDigestServerAvailable()

        calendarUrl = discoverCalendar()
        assumeTrue("No calendar found on Digest Baikal", calendarUrl != null)

        val result = client.getSyncToken(calendarUrl!!)
        assert(result.isSuccess()) {
            "Failed to get sync token through Digest auth: ${(result as? CalDavResult.Error)?.message}"
        }

        val token = result.getOrNull()!!
        println("Digest Baikal sync token: $token")
        assert(token.isNotEmpty()) { "Sync token should not be empty" }
    }
}
