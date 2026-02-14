package org.onekash.kashcal.sync.client.integration

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Integration test comparing sync approaches:
 * 1. Current: sync-collection + multiget
 * 2. Alternative: calendar-query (etags only) + multiget (sabre.io recommendation)
 *
 * Run with: ./gradlew testDebugUnitTest --tests "*EtagBasedSyncTest*"
 *
 * Requires: local.properties with:
 *   caldav.username=your_apple_id@icloud.com
 *   caldav.app_password=xxxx-xxxx-xxxx-xxxx
 */
class EtagBasedSyncTest {

    private lateinit var client: CalDavClient
    private lateinit var rawHttpClient: OkHttpClient
    private var username: String? = null
    private var password: String? = null
    private var serverUrl: String = "https://caldav.icloud.com"

    companion object {
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
    }

    @Before
    fun setup() {
        val quirks = ICloudQuirks()
        loadCredentials()

        rawHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val newRequest = original.newBuilder()
                    .header("Authorization", okhttp3.Credentials.basic(username ?: "", password ?: ""))
                    .build()
                chain.proceed(newRequest)
            }
            .build()

        if (username != null && password != null) {
            val credentials = Credentials(
                username = username!!,
                password = password!!,
                serverUrl = serverUrl
            )
            val factory = OkHttpCalDavClientFactory()
            client = factory.createClient(credentials, quirks)
        } else {
            // Create a client with dummy credentials for tests that will be skipped
            val dummyCredentials = Credentials(
                username = "test@example.com",
                password = "test-password",
                serverUrl = serverUrl
            )
            val factory = OkHttpCalDavClientFactory()
            client = factory.createClient(dummyCredentials, quirks)
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
                println("Found credentials file at: ${propsFile.absolutePath}")
                propsFile.readLines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#") || !trimmed.contains("=")) return@forEach
                    val key = trimmed.substringBefore("=").trim()
                    val value = trimmed.substringAfter("=").trim()
                    when (key) {
                        "ICLOUD_USERNAME" -> username = value
                        "ICLOUD_APP_PASSWORD" -> password = value
                        "caldav.username" -> if (username == null) username = value
                        "caldav.app_password" -> if (password == null) password = value
                        "caldav.server" -> serverUrl = value
                    }
                }
                if (username != null && password != null) break
            }
        }

        println("Credentials loaded: username=${username?.take(5)}***, server=$serverUrl")
    }

    private fun assumeCredentialsAvailable() {
        assumeTrue(
            "iCloud credentials not available. Add caldav.username and caldav.app_password to local.properties.",
            username != null && password != null
        )
    }

    // ========== Test: Calendar Query with ETags Only ==========

    @Test
    fun `calendar-query fetching etags only - bandwidth comparison`() = runBlocking {
        assumeCredentialsAvailable()

        val calendarUrl = getFirstCalendarUrl() ?: return@runBlocking
        println("\n=== Testing calendar-query (etags only) ===")
        println("Calendar URL: $calendarUrl")

        val now = System.currentTimeMillis()
        val startMs = now - (90L * 24 * 60 * 60 * 1000)  // 90 days back
        val endMs = now + (365L * 24 * 60 * 60 * 1000)   // 1 year forward

        // Method 1: Full calendar-query (current approach for full sync)
        println("\n--- Method 1: calendar-query with FULL iCal data ---")
        val fullStartTime = System.currentTimeMillis()
        val fullResult = client.fetchEventsInRange(calendarUrl, startMs, endMs)
        val fullDuration = System.currentTimeMillis() - fullStartTime

        assert(fullResult.isSuccess()) { "Full calendar-query failed: ${(fullResult as? CalDavResult.Error)?.message}" }
        val fullEvents = fullResult.getOrNull()!!
        val fullDataSize = fullEvents.sumOf { it.icalData.length }

        println("  Events fetched: ${fullEvents.size}")
        println("  Total iCal data size: ${fullDataSize / 1024} KB")
        println("  Duration: ${fullDuration}ms")

        // Method 2: ETags-only calendar-query (sabre.io recommendation)
        println("\n--- Method 2: calendar-query with ETags ONLY ---")
        val etagStartTime = System.currentTimeMillis()
        val etagResult = fetchEtagsOnly(calendarUrl, startMs, endMs)
        val etagDuration = System.currentTimeMillis() - etagStartTime

        println("  Events found: ${etagResult.size}")
        println("  Duration: ${etagDuration}ms")
        println("  Response size: ~${etagResult.size * 150} bytes (estimated)")

        // Compare
        println("\n=== Comparison ===")
        println("Full query:  ${fullEvents.size} events, ${fullDataSize / 1024} KB, ${fullDuration}ms")
        println("ETags only:  ${etagResult.size} events, ~${etagResult.size * 150 / 1024} KB, ${etagDuration}ms")
        println("Bandwidth saved: ~${(fullDataSize - etagResult.size * 150) / 1024} KB (${100 - (etagResult.size * 150 * 100 / fullDataSize)}%)")

        // Verify we got the same hrefs
        val fullHrefs = fullEvents.map { it.href }.toSet()
        val etagHrefs = etagResult.map { it.first }.toSet()
        println("\nHref match: ${fullHrefs == etagHrefs}")

        if (fullHrefs != etagHrefs) {
            println("Missing in etag query: ${fullHrefs - etagHrefs}")
            println("Extra in etag query: ${etagHrefs - fullHrefs}")
        }
    }

    @Test
    fun `simulate etag-based incremental sync`() = runBlocking {
        assumeCredentialsAvailable()

        val calendarUrl = getFirstCalendarUrl() ?: return@runBlocking
        println("\n=== Simulating ETags-Based Incremental Sync ===")
        println("Calendar URL: $calendarUrl")

        val now = System.currentTimeMillis()
        val startMs = now - (90L * 24 * 60 * 60 * 1000)
        val endMs = now + (365L * 24 * 60 * 60 * 1000)

        // Step 1: Fetch all etags (simulating local cache)
        println("\n--- Step 1: Initial sync - fetch all etags ---")
        val initialEtags = fetchEtagsOnly(calendarUrl, startMs, endMs)
        println("  Cached ${initialEtags.size} event etags")

        // Build local cache simulation
        val localCache = initialEtags.associate { (href, etag) -> href to etag }.toMutableMap()

        // Step 2: Simulate time passing and re-fetch etags
        println("\n--- Step 2: Check for changes (etags only) ---")
        val checkStartTime = System.currentTimeMillis()
        val currentEtags = fetchEtagsOnly(calendarUrl, startMs, endMs)
        val checkDuration = System.currentTimeMillis() - checkStartTime
        println("  Fetched ${currentEtags.size} etags in ${checkDuration}ms")

        // Step 3: Compare etags to find changes
        println("\n--- Step 3: Local comparison ---")
        val currentEtagMap = currentEtags.associate { (href, etag) -> href to etag }

        val newEvents = mutableListOf<String>()
        val changedEvents = mutableListOf<String>()
        val deletedEvents = mutableListOf<String>()

        // Find new and changed
        for ((href, etag) in currentEtagMap) {
            when {
                href !in localCache -> newEvents.add(href)
                localCache[href] != etag -> changedEvents.add(href)
            }
        }

        // Find deleted
        for (href in localCache.keys) {
            if (href !in currentEtagMap) {
                deletedEvents.add(href)
            }
        }

        println("  New events: ${newEvents.size}")
        println("  Changed events: ${changedEvents.size}")
        println("  Deleted events: ${deletedEvents.size}")

        // Step 4: Fetch only changed/new events via multiget
        val toFetch = newEvents + changedEvents
        if (toFetch.isNotEmpty()) {
            println("\n--- Step 4: Fetch changed events via multiget ---")
            val multigetStartTime = System.currentTimeMillis()
            val fetchedEvents = client.fetchEventsByHref(calendarUrl, toFetch)
            val multigetDuration = System.currentTimeMillis() - multigetStartTime

            if (fetchedEvents.isSuccess()) {
                val events = fetchedEvents.getOrNull()!!
                println("  Fetched ${events.size} events in ${multigetDuration}ms")
                println("  Data size: ${events.sumOf { it.icalData.length } / 1024} KB")
            } else {
                println("  Multiget failed: ${(fetchedEvents as CalDavResult.Error).message}")
            }
        } else {
            println("\n--- Step 4: No changes to fetch ---")
        }

        println("\n=== Summary ===")
        println("This approach required ${if (toFetch.isEmpty()) "1" else "2"} HTTP requests")
        println("vs sync-collection which requires 2 requests (sync-collection + multiget)")
    }

    @Test
    fun `compare sync-collection vs etag-based approaches`() = runBlocking {
        assumeCredentialsAvailable()

        val calendarUrl = getFirstCalendarUrl() ?: return@runBlocking
        println("\n=== Comparing Sync Approaches ===")

        val now = System.currentTimeMillis()
        val startMs = now - (90L * 24 * 60 * 60 * 1000)
        val endMs = now + (365L * 24 * 60 * 60 * 1000)

        // Approach 1: sync-collection (current KashCal approach)
        println("\n--- Approach 1: sync-collection ---")
        val syncTokenResult = client.getSyncToken(calendarUrl)
        if (syncTokenResult.isSuccess() && syncTokenResult.getOrNull() != null) {
            val syncToken = syncTokenResult.getOrNull()!!
            println("  Got sync token: ${syncToken.take(30)}...")

            val syncStartTime = System.currentTimeMillis()
            val syncResult = client.syncCollection(calendarUrl, syncToken)
            val syncDuration = System.currentTimeMillis() - syncStartTime

            if (syncResult.isSuccess()) {
                val report = syncResult.getOrNull()!!
                println("  Changed: ${report.changed.size}, Deleted: ${report.deleted.size}")
                println("  Duration: ${syncDuration}ms")
            } else {
                println("  Sync-collection returned error (token may be expired)")
            }
        } else {
            println("  Sync token not available")
        }

        // Approach 2: ETags-based (sabre.io recommendation)
        println("\n--- Approach 2: calendar-query (etags) ---")
        val etagStartTime = System.currentTimeMillis()
        val etags = fetchEtagsOnly(calendarUrl, startMs, endMs)
        val etagDuration = System.currentTimeMillis() - etagStartTime
        println("  Fetched ${etags.size} etags in ${etagDuration}ms")

        println("\n=== Trade-offs ===")
        println("""
            |
            | Method              | Pros                          | Cons
            | ------------------- | ----------------------------- | ----
            | sync-collection     | Only returns deltas           | Requires token state
            |                     | Smallest payload for few      | Token can expire (410)
            |                     | changes                       | Need fallback logic
            | ------------------- | ----------------------------- | ----
            | calendar-query      | No state to manage            | Returns ALL etags
            | (etags)             | Simpler implementation        | More bandwidth for
            |                     | No token expiration           | large calendars
            |
        """.trimMargin())
    }

    // ========== Helper: Fetch ETags Only ==========

    /**
     * Fetch only etags (not iCal data) using calendar-query.
     * This is the sabre.io recommended approach for change detection.
     */
    private suspend fun fetchEtagsOnly(
        calendarUrl: String,
        startMs: Long,
        endMs: Long
    ): List<Pair<String, String>> {
        val quirks = ICloudQuirks()
        val startDate = quirks.formatDateForQuery(startMs)
        val endDate = quirks.formatDateForQuery(endMs)

        // Note: NO <c:calendar-data/> - only requesting getetag
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:prop>
                    <d:getetag/>
                </d:prop>
                <c:filter>
                    <c:comp-filter name="VCALENDAR">
                        <c:comp-filter name="VEVENT">
                            <c:time-range start="$startDate" end="$endDate"/>
                        </c:comp-filter>
                    </c:comp-filter>
                </c:filter>
            </c:calendar-query>
        """.trimIndent()

        val request = Request.Builder()
            .url(calendarUrl)
            .method("REPORT", body.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "1")
            .build()

        val response = rawHttpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            println("ETags-only query failed: ${response.code}")
            return emptyList()
        }

        // Parse response to extract href + etag pairs
        return parseEtagResponse(responseBody)
    }

    /**
     * Parse WebDAV multistatus response for href + etag pairs.
     */
    private fun parseEtagResponse(xml: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()

        // Simple regex parsing (production would use XML parser)
        val responsePattern = Regex(
            """<(?:d:|D:)?response[^>]*>(.*?)</(?:d:|D:)?response>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val hrefPattern = Regex(
            """<(?:d:|D:)?href[^>]*>([^<]+)</(?:d:|D:)?href>""",
            RegexOption.IGNORE_CASE
        )
        val etagPattern = Regex(
            """<(?:d:|D:)?getetag[^>]*>"?([^"<]+)"?</(?:d:|D:)?getetag>""",
            RegexOption.IGNORE_CASE
        )

        responsePattern.findAll(xml).forEach { match ->
            val responseXml = match.groupValues[1]
            val href = hrefPattern.find(responseXml)?.groupValues?.get(1)
            val etag = etagPattern.find(responseXml)?.groupValues?.get(1)?.trim('"')

            if (href != null && etag != null && href.endsWith(".ics")) {
                results.add(href to etag)
            }
        }

        return results
    }

    // ========== Helper: Get Calendar URL ==========

    private suspend fun getFirstCalendarUrl(): String? {
        val principal = client.discoverPrincipal(serverUrl).getOrNull()
        if (principal == null) {
            println("Failed to discover principal")
            return null
        }

        val home = client.discoverCalendarHome(principal).getOrNull()?.firstOrNull()
        if (home == null) {
            println("Failed to discover calendar home")
            return null
        }

        val calendars = client.listCalendars(home).getOrNull()
        val calendar = calendars?.firstOrNull { cal ->
            !cal.url.contains("inbox") && !cal.url.contains("outbox")
        }

        if (calendar == null) {
            println("No suitable calendar found")
            return null
        }

        return calendar.url
    }
}
