package org.onekash.kashcal.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Credentials as OkHttpCredentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.OkHttpCalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Test to validate the incremental sync bug AND verify the fix works.
 *
 * This test:
 * 1. Shows current broken behavior (extractICalData returns empty)
 * 2. Shows what the FIX would return (extractChangedItems returns hrefs/etags)
 *
 * Run with: ./gradlew testDebugUnitTest --tests "*ValidateSyncBugTest*"
 */
class ValidateSyncBugTest {

    private lateinit var client: OkHttpCalDavClient
    private lateinit var quirks: ICloudQuirks
    private lateinit var rawHttpClient: OkHttpClient

    private var username: String? = null
    private var password: String? = null
    private val serverUrl = "https://caldav.icloud.com"

    @Before
    fun setup() {
        loadCredentials()
        quirks = ICloudQuirks()

        if (username != null && password != null) {
            val credentials = Credentials(
                username = username!!,
                password = password!!,
                serverUrl = serverUrl
            )
            val factory = OkHttpCalDavClientFactory()
            client = factory.createClient(credentials, quirks) as OkHttpCalDavClient
        } else {
            // Create a client with dummy credentials for tests that will be skipped
            val dummyCredentials = Credentials(
                username = "test@example.com",
                password = "test-password",
                serverUrl = serverUrl
            )
            val factory = OkHttpCalDavClientFactory()
            client = factory.createClient(dummyCredentials, quirks) as OkHttpCalDavClient
        }

        // Raw HTTP client for direct XML inspection
        rawHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()
                if (username != null && password != null) {
                    requestBuilder.header("Authorization", OkHttpCredentials.basic(username!!, password!!))
                }
                requestBuilder.header("User-Agent", "KashCal/2.0 (Android)")
                chain.proceed(requestBuilder.build())
            }
            .build()
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
                        when {
                            parts[0].contains("username", ignoreCase = true) -> username = parts[1]
                            parts[0].contains("password", ignoreCase = true) &&
                                !parts[0].contains("keystore", ignoreCase = true) -> password = parts[1]
                        }
                    }
                }
                if (username != null && password != null) break
            }
        }
    }

    /**
     * PROPOSED FIX: Extract hrefs and etags without requiring calendar-data
     */
    private fun extractChangedItems(responseBody: String): List<Pair<String, String?>> {
        val items = mutableListOf<Pair<String, String?>>()

        // Match response elements
        val responseRegex = Regex(
            """<(?:d:)?response[^>]*>(.*?)</(?:d:)?response>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        for (match in responseRegex.findAll(responseBody)) {
            val responseXml = match.groupValues[1]

            // Check if this is a 404 (deleted item) - skip those
            if (responseXml.contains("HTTP/1.1 404", ignoreCase = true)) {
                continue
            }

            // Extract href
            val hrefRegex = Regex("""<(?:d:)?href[^>]*>([^<]+)</(?:d:)?href>""", RegexOption.IGNORE_CASE)
            val href = hrefRegex.find(responseXml)?.groupValues?.get(1) ?: continue

            // Skip non-.ics files (like the calendar itself)
            if (!href.endsWith(".ics")) continue

            // Extract etag
            val etagRegex = Regex("""<(?:d:)?getetag[^>]*>"?([^"<]+)"?</""", RegexOption.IGNORE_CASE)
            val etag = etagRegex.find(responseXml)?.groupValues?.get(1)?.trim('"')

            items.add(Pair(href, etag))
        }

        return items
    }

    @Test
    fun `VALIDATE BUG AND FIX - incremental sync after creating event`() = runBlocking {
        assumeTrue("iCloud credentials not available", username != null && password != null)

        println("=== VALIDATING INCREMENTAL SYNC BUG ===\n")

        // Step 1: Discover calendar
        println("Step 1: Discovering calendar...")
        val principalResult = client.discoverPrincipal(serverUrl)
        assumeTrue("Should discover principal", principalResult.isSuccess())
        val principal = principalResult.getOrNull()!!

        val homeResult = client.discoverCalendarHome(principal)
        assumeTrue("Should discover home", homeResult.isSuccess())
        val home = homeResult.getOrNull()!!.first()

        val calendarsResult = client.listCalendars(home)
        assumeTrue("Should list calendars", calendarsResult.isSuccess())
        val calendars = calendarsResult.getOrNull()!!
        val calendar = calendars.firstOrNull { !it.url.contains("inbox") && !it.url.contains("outbox") }
        assumeTrue("Should have a calendar", calendar != null)
        println("  Calendar: ${calendar!!.displayName}")
        println("  URL: ${calendar.url}")

        // Step 2: Get current sync token
        println("\nStep 2: Getting current sync token...")
        val tokenResponse = callSyncCollectionRaw(calendar.url, null)
        val currentToken = extractSyncToken(tokenResponse ?: "")
        assumeTrue("Should get sync token", currentToken != null)
        println("  Sync Token: ${currentToken!!.take(50)}...")

        // Step 3: Create a test event
        val testUid = "kashcal-test-${System.currentTimeMillis()}"
        println("\nStep 3: Creating test event (UID: $testUid)...")
        val createResult = createTestEvent(calendar.url, testUid)
        if (!createResult) {
            println("  Failed to create event, skipping test")
            return@runBlocking
        }
        println("  Event created successfully!")

        // Step 4: Call sync-collection with OLD token
        println("\nStep 4: Calling sync-collection with OLD token to detect the new event...")
        val rawResponse = callSyncCollectionRaw(calendar.url, currentToken)

        if (rawResponse == null) {
            println("  Failed to get sync response")
            deleteTestEvent(calendar.url, testUid)
            return@runBlocking
        }

        println("\n=== COMPARING PARSING METHODS ===")

        // Current broken method
        val iCalData = quirks.extractICalData(rawResponse)
        println("\nCURRENT (broken) - extractICalData():")
        println("  Items found: ${iCalData.size}")

        // Proposed fix
        val changedItems = extractChangedItems(rawResponse)
        println("\nFIXED - extractChangedItems():")
        println("  Items found: ${changedItems.size}")

        // Check if our test event is in the list
        val foundTestEvent = changedItems.any { it.first.contains(testUid) }
        println("\n  Test event ($testUid) found: $foundTestEvent")

        if (changedItems.isNotEmpty()) {
            println("\n  Changed items:")
            changedItems.forEach { (href, etag) ->
                val marker = if (href.contains(testUid)) " ← OUR TEST EVENT" else ""
                println("    - ${href.substringAfterLast("/")} (etag: $etag)$marker")
            }
        }

        // Show the difference
        println("\n=== RESULT ===")
        if (iCalData.isEmpty() && changedItems.isNotEmpty()) {
            println("*** BUG CONFIRMED ***")
            println("  extractICalData() found 0 changes")
            println("  extractChangedItems() found ${changedItems.size} change(s)")
            println("  Test event detected: $foundTestEvent")
        } else if (changedItems.isEmpty()) {
            println("No changes detected - sync token might be stale")
        }

        // Step 5: Clean up - delete test event
        println("\nStep 5: Cleaning up - deleting test event...")
        deleteTestEvent(calendar.url, testUid)
        println("  Done!")

        println("\n=== VALIDATION COMPLETE ===")
    }

    private fun extractSyncToken(response: String): String? {
        val patterns = listOf(
            """<(?:d:)?sync-token[^>]*>([^<]+)</(?:d:)?sync-token>""",
            """sync-token[^>]*>([^<]+)</"""
        )
        for (pattern in patterns) {
            val regex = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val match = regex.find(response)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        return null
    }

    private suspend fun createTestEvent(calendarUrl: String, uid: String): Boolean =
        withContext(Dispatchers.IO) {
            val eventUrl = "${calendarUrl.trimEnd('/')}/$uid.ics"
            val now = System.currentTimeMillis()
            val dtstamp = formatUtcDate(now)
            val dtstart = formatUtcDate(now + 3600000) // 1 hour from now
            val dtend = formatUtcDate(now + 7200000)   // 2 hours from now

            val icalData = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//KashCal//Test//EN
                BEGIN:VEVENT
                UID:$uid
                DTSTAMP:$dtstamp
                DTSTART:$dtstart
                DTEND:$dtend
                SUMMARY:KashCal Incremental Sync Test
                DESCRIPTION:This event tests incremental sync. It will be deleted shortly.
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val request = Request.Builder()
                .url(eventUrl)
                .put(icalData.toRequestBody("text/calendar; charset=utf-8".toMediaType()))
                .header("If-None-Match", "*")
                .build()

            try {
                val response = rawHttpClient.newCall(request).execute()
                println("  PUT response: ${response.code}")
                response.code == 201 || response.code == 204
            } catch (e: Exception) {
                println("  Error creating event: ${e.message}")
                false
            }
        }

    private suspend fun deleteTestEvent(calendarUrl: String, uid: String): Boolean =
        withContext(Dispatchers.IO) {
            val eventUrl = "${calendarUrl.trimEnd('/')}/$uid.ics"

            val request = Request.Builder()
                .url(eventUrl)
                .delete()
                .build()

            try {
                val response = rawHttpClient.newCall(request).execute()
                response.code == 200 || response.code == 204 || response.code == 404
            } catch (e: Exception) {
                false
            }
        }

    private fun formatUtcDate(millis: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = millis
        return String.format(
            "%04d%02d%02dT%02d%02d%02dZ",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND)
        )
    }

    /**
     * Make raw sync-collection request to get the XML response
     */
    private suspend fun callSyncCollectionRaw(calendarUrl: String, syncToken: String?): String? =
        withContext(Dispatchers.IO) {
            val tokenElement = if (syncToken != null) {
                "<d:sync-token>$syncToken</d:sync-token>"
            } else {
                "<d:sync-token/>"
            }

            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <d:sync-collection xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                    $tokenElement
                    <d:sync-level>1</d:sync-level>
                    <d:prop>
                        <d:getetag/>
                    </d:prop>
                </d:sync-collection>
            """.trimIndent()

            val request = Request.Builder()
                .url(calendarUrl)
                .method("REPORT", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                .header("Depth", "1")
                .build()

            try {
                val response = rawHttpClient.newCall(request).execute()
                if (response.code == 207) {
                    response.body?.string()
                } else {
                    println("  HTTP ${response.code}")
                    null
                }
            } catch (e: Exception) {
                println("  Error: ${e.message}")
                null
            }
        }
}
