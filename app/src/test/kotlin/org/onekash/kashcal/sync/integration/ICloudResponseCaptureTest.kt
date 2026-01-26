package org.onekash.kashcal.sync.integration

import android.util.Log
import io.mockk.*
import kotlinx.coroutines.test.runTest
import okhttp3.Credentials as OkHttpCredentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Integration test to capture actual iCloud CalDAV responses.
 *
 * Purpose: Capture raw XML to understand what's iCloud-specific vs standard CalDAV.
 *
 * Run with: ./gradlew test --tests "*ICloudResponseCaptureTest*" --info
 *
 * Outputs captured responses to: app/src/test/resources/caldav/icloud/
 */
class ICloudResponseCaptureTest {

    private lateinit var httpClient: OkHttpClient
    private var username: String? = null
    private var password: String? = null
    private val outputDir = File("/onekash/KashCal/app/src/test/resources/caldav/icloud")

    companion object {
        private const val ICLOUD_SERVER = "https://caldav.icloud.com"
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
    }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        loadCredentials()
        outputDir.mkdirs()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun loadCredentials() {
        val file = File("/onekash/KashCal/local.properties")
        if (!file.exists()) {
            println("SKIP: No local.properties found")
            return
        }

        file.readLines().forEach { line ->
            when {
                line.startsWith("ICLOUD_USERNAME=") -> username = line.substringAfter("=").trim()
                line.startsWith("ICLOUD_APP_PASSWORD=") -> password = line.substringAfter("=").trim()
            }
        }

        if (username != null && password != null) {
            println("Credentials loaded: ${username?.take(3)}***")
        } else {
            println("Credentials not found. Keys: ICLOUD_USERNAME, ICLOUD_APP_PASSWORD")
        }
    }

    private fun propfindRequest(url: String, body: String, depth: String = "0"): String? {
        if (username == null || password == null) return null

        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
            .header("Authorization", OkHttpCredentials.basic(username!!, password!!))
            .header("Depth", depth)
            .header("Content-Type", "application/xml; charset=utf-8")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                println("\n=== PROPFIND $url (${response.code}) ===")
                responseBody
            }
        } catch (e: Exception) {
            println("Error: ${e.message}")
            null
        }
    }

    private fun reportRequest(url: String, body: String): String? {
        if (username == null || password == null) return null

        val request = Request.Builder()
            .url(url)
            .method("REPORT", body.toRequestBody(XML_MEDIA_TYPE))
            .header("Authorization", OkHttpCredentials.basic(username!!, password!!))
            .header("Depth", "1")
            .header("Content-Type", "application/xml; charset=utf-8")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                println("\n=== REPORT $url (${response.code}) ===")
                responseBody
            }
        } catch (e: Exception) {
            println("Error: ${e.message}")
            null
        }
    }

    private fun saveResponse(filename: String, content: String?) {
        if (content == null) return
        val file = File(outputDir, filename)
        file.writeText(content)
        println("Saved: ${file.absolutePath}")
    }

    // ==================== Response Capture Tests ====================

    @Test
    fun `capture 1 - current user principal`() = runTest {
        if (username == null) {
            println("SKIPPED: No credentials")
            return@runTest
        }

        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
                <d:prop>
                    <d:current-user-principal/>
                </d:prop>
            </d:propfind>
        """.trimIndent()

        val response = propfindRequest(ICLOUD_SERVER, body)
        saveResponse("01_current_user_principal.xml", response)

        response?.let {
            println("\n--- Analysis ---")
            println("Uses xmlns prefix: ${it.contains("<d:") || it.contains("<D:")}")
            println("Uses non-prefixed: ${it.contains("<href>") && !it.contains("<d:href>")}")
            println("Contains principal URL: ${it.contains("/principal/")}")
        }
    }

    @Test
    fun `capture 2 - calendar home set`() = runTest {
        if (username == null) {
            println("SKIPPED: No credentials")
            return@runTest
        }

        // First get principal URL
        val principalBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
                <d:prop>
                    <d:current-user-principal/>
                </d:prop>
            </d:propfind>
        """.trimIndent()

        val principalResponse = propfindRequest(ICLOUD_SERVER, principalBody)
        val principalUrl = extractHref(principalResponse, "current-user-principal")

        if (principalUrl == null) {
            println("Could not extract principal URL")
            return@runTest
        }

        val fullPrincipalUrl = if (principalUrl.startsWith("http")) principalUrl
                               else "$ICLOUD_SERVER$principalUrl"
        println("Principal URL: $fullPrincipalUrl")

        // Now get calendar home
        val calHomeBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:prop>
                    <c:calendar-home-set/>
                </d:prop>
            </d:propfind>
        """.trimIndent()

        val response = propfindRequest(fullPrincipalUrl, calHomeBody)
        saveResponse("02_calendar_home_set.xml", response)

        response?.let {
            println("\n--- Analysis ---")
            println("Contains calendar-home-set: ${it.contains("calendar-home-set")}")
            println("Uses CDATA: ${it.contains("CDATA")}")
            val homeUrl = extractHref(it, "calendar-home-set")
            println("Calendar home URL: $homeUrl")
        }
    }

    @Test
    fun `capture 3 - calendar list`() = runTest {
        if (username == null) {
            println("SKIPPED: No credentials")
            return@runTest
        }

        // Get calendar home URL first
        val calendarHomeUrl = discoverCalendarHome()
        if (calendarHomeUrl == null) {
            println("Could not discover calendar home")
            return@runTest
        }

        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:ic="http://apple.com/ns/ical/">
                <d:prop>
                    <d:displayname/>
                    <d:resourcetype/>
                    <c:supported-calendar-component-set/>
                    <cs:getctag/>
                    <ic:calendar-color/>
                    <d:current-user-privilege-set/>
                </d:prop>
            </d:propfind>
        """.trimIndent()

        val response = propfindRequest(calendarHomeUrl, body, depth = "1")
        saveResponse("03_calendar_list.xml", response)

        response?.let {
            println("\n--- Analysis ---")
            println("Response length: ${it.length} chars")
            println("Calendar count: ${Regex("<response[^>]*>").findAll(it).count()}")
            println("Uses CDATA: ${it.contains("CDATA")}")
            println("Contains calendar-color: ${it.contains("calendar-color")}")
            println("Contains getctag: ${it.contains("getctag")}")
        }
    }

    @Test
    fun `capture 4 - sync collection (incremental sync)`() = runTest {
        if (username == null) {
            println("SKIPPED: No credentials")
            return@runTest
        }

        val calendarUrl = discoverFirstCalendar()
        if (calendarUrl == null) {
            println("Could not discover calendar")
            return@runTest
        }

        // Request sync-collection with empty token (initial sync)
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:sync-collection xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:sync-token/>
                <d:sync-level>1</d:sync-level>
                <d:prop>
                    <d:getetag/>
                </d:prop>
            </d:sync-collection>
        """.trimIndent()

        val response = reportRequest(calendarUrl, body)
        saveResponse("04_sync_collection.xml", response)

        response?.let {
            println("\n--- Analysis ---")
            println("Response length: ${it.length} chars")
            println("Contains sync-token: ${it.contains("sync-token")}")
            println("Event hrefs: ${Regex("\\.ics</").findAll(it).count()}")
            println("Contains 404 status: ${it.contains("404")}")
        }
    }

    @Test
    fun `capture 5 - calendar multiget (event fetch)`() = runTest {
        if (username == null) {
            println("SKIPPED: No credentials")
            return@runTest
        }

        val calendarUrl = discoverFirstCalendar()
        if (calendarUrl == null) {
            println("Could not discover calendar")
            return@runTest
        }

        // First get some event hrefs via sync-collection
        val syncBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:sync-collection xmlns:d="DAV:">
                <d:sync-token/>
                <d:sync-level>1</d:sync-level>
                <d:prop><d:getetag/></d:prop>
            </d:sync-collection>
        """.trimIndent()

        val syncResponse = reportRequest(calendarUrl, syncBody)
        val hrefs = extractEventHrefs(syncResponse)

        if (hrefs.isEmpty()) {
            println("No events found in calendar")
            return@runTest
        }

        // Fetch first 3 events via multiget
        val hrefsToFetch = hrefs.take(3)
        val hrefXml = hrefsToFetch.joinToString("\n") { "    <d:href>$it</d:href>" }

        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <c:calendar-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:prop>
                    <d:getetag/>
                    <c:calendar-data/>
                </d:prop>
            $hrefXml
            </c:calendar-multiget>
        """.trimIndent()

        val response = reportRequest(calendarUrl, body)
        saveResponse("05_calendar_multiget.xml", response)

        response?.let {
            println("\n--- Analysis ---")
            println("Response length: ${it.length} chars")
            println("Uses CDATA for calendar-data: ${it.contains("<![CDATA[")}")
            println("Uses plain calendar-data: ${it.contains("<calendar-data>BEGIN:")}")
            println("Events fetched: ${Regex("BEGIN:VCALENDAR").findAll(it).count()}")
            println("Contains VALARM: ${it.contains("VALARM")}")
        }
    }

    @Test
    fun `capture 6 - ctag check`() = runTest {
        if (username == null) {
            println("SKIPPED: No credentials")
            return@runTest
        }

        val calendarUrl = discoverFirstCalendar()
        if (calendarUrl == null) {
            println("Could not discover calendar")
            return@runTest
        }

        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
                <d:prop>
                    <cs:getctag/>
                </d:prop>
            </d:propfind>
        """.trimIndent()

        val response = propfindRequest(calendarUrl, body)
        saveResponse("06_ctag_check.xml", response)

        response?.let {
            println("\n--- Analysis ---")
            println("Response length: ${it.length} chars")
            println("Contains getctag: ${it.contains("getctag")}")
            val ctag = Regex("""<(?:cs:)?getctag>([^<]+)</""").find(it)?.groupValues?.get(1)
            println("Ctag value: $ctag")
        }
    }

    @Test
    fun `capture 7 - etag only listing`() = runTest {
        if (username == null) {
            println("SKIPPED: No credentials")
            return@runTest
        }

        val calendarUrl = discoverFirstCalendar()
        if (calendarUrl == null) {
            println("Could not discover calendar")
            return@runTest
        }

        // Calendar-query to get etags only (for etag fallback sync)
        val now = System.currentTimeMillis()
        val oneYearAgo = now - (365L * 24 * 60 * 60 * 1000)
        val startDate = formatDateForCalDAV(oneYearAgo)
        val endDate = formatDateForCalDAV(now + (365L * 24 * 60 * 60 * 1000))

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

        val response = reportRequest(calendarUrl, body)
        saveResponse("07_etag_listing.xml", response)

        response?.let {
            println("\n--- Analysis ---")
            println("Response length: ${it.length} chars")
            println("Event count: ${Regex("\\.ics</").findAll(it).count()}")
            println("Contains getetag: ${it.contains("getetag")}")
        }
    }

    // ==================== Summary Test ====================

    @Test
    fun `capture all and summarize iCloud quirks`() = runTest {
        if (username == null) {
            println("SKIPPED: No credentials")
            return@runTest
        }

        println("\n" + "=".repeat(60))
        println("iCloud CalDAV Response Analysis")
        println("=".repeat(60))

        // Run all captures and collect analysis
        val analysis = mutableMapOf<String, Any>()

        // Capture principal
        val principalBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/></d:prop></d:propfind>
        """.trimIndent()
        val principalResp = propfindRequest(ICLOUD_SERVER, principalBody)
        principalResp?.let {
            analysis["principal_uses_prefix"] = it.contains("<d:") || it.contains("<D:")
            analysis["principal_no_prefix"] = it.contains("<href>") && !it.contains("<d:href")
        }

        // Capture multiget for CDATA check
        val calendarUrl = discoverFirstCalendar()
        if (calendarUrl != null) {
            val syncBody = """
                <?xml version="1.0" encoding="utf-8"?>
                <d:sync-collection xmlns:d="DAV:">
                    <d:sync-token/><d:sync-level>1</d:sync-level>
                    <d:prop><d:getetag/></d:prop>
                </d:sync-collection>
            """.trimIndent()
            val syncResp = reportRequest(calendarUrl, syncBody)
            val hrefs = extractEventHrefs(syncResp)

            if (hrefs.isNotEmpty()) {
                val hrefXml = hrefs.take(1).joinToString("\n") { "    <d:href>$it</d:href>" }
                val multigetBody = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <c:calendar-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                        <d:prop><d:getetag/><c:calendar-data/></d:prop>
                    $hrefXml
                    </c:calendar-multiget>
                """.trimIndent()
                val multigetResp = reportRequest(calendarUrl, multigetBody)
                multigetResp?.let {
                    analysis["uses_cdata"] = it.contains("<![CDATA[")
                    analysis["plain_calendar_data"] = it.contains("<calendar-data>BEGIN:")
                }
            }
        }

        // Print summary
        println("\n" + "-".repeat(60))
        println("SUMMARY: What's iCloud-specific vs Standard CalDAV")
        println("-".repeat(60))

        println("\n1. XML Namespace Prefixes:")
        println("   iCloud uses: ${if (analysis["principal_no_prefix"] == true) "NON-PREFIXED (no d:)" else "PREFIXED (d:)"}")
        println("   Standard CalDAV uses: PREFIXED (d:)")

        println("\n2. Calendar-data CDATA wrapping:")
        println("   iCloud uses: ${if (analysis["uses_cdata"] == true) "CDATA wrapping" else "Plain XML"}")
        println("   Standard CalDAV uses: Plain XML (no CDATA)")

        println("\n3. Sync token invalid response:")
        println("   iCloud uses: 403 Forbidden")
        println("   Standard CalDAV uses: 410 Gone (RFC 6578)")

        println("\n4. Calendar types to skip:")
        println("   iCloud: inbox, outbox, tasks, REMINDERS")
        println("   Standard CalDAV: inbox, outbox only")

        println("\n5. App-specific password:")
        println("   iCloud: REQUIRED")
        println("   Standard CalDAV: Not required (normal password)")

        println("\n" + "=".repeat(60))
        println("Files saved to: ${outputDir.absolutePath}")
        println("=".repeat(60))
    }

    // ==================== Helper Methods ====================

    private fun extractHref(xml: String?, elementName: String): String? {
        if (xml == null) return null
        // Try both prefixed and non-prefixed patterns
        val patterns = listOf(
            Regex("""<(?:d:|D:)?$elementName[^>]*>.*?<(?:d:|D:)?href[^>]*>([^<]+)</""", RegexOption.DOT_MATCHES_ALL),
            Regex("""<$elementName[^>]*>.*?<href[^>]*>([^<]+)</""", RegexOption.DOT_MATCHES_ALL)
        )
        for (pattern in patterns) {
            pattern.find(xml)?.let { return it.groupValues[1].trim() }
        }
        return null
    }

    private fun extractEventHrefs(xml: String?): List<String> {
        if (xml == null) return emptyList()
        return Regex("""<(?:d:|D:)?href>([^<]+\.ics)</""")
            .findAll(xml)
            .map { it.groupValues[1] }
            .toList()
    }

    private fun discoverCalendarHome(): String? {
        // Get principal
        val principalBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/></d:prop></d:propfind>
        """.trimIndent()
        val principalResp = propfindRequest(ICLOUD_SERVER, principalBody)
        val principalUrl = extractHref(principalResp, "current-user-principal") ?: return null

        val fullPrincipalUrl = if (principalUrl.startsWith("http")) principalUrl
                               else "$ICLOUD_SERVER$principalUrl"

        // Get calendar home
        val calHomeBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:prop><c:calendar-home-set/></d:prop>
            </d:propfind>
        """.trimIndent()
        val calHomeResp = propfindRequest(fullPrincipalUrl, calHomeBody)
        return extractHref(calHomeResp, "calendar-home-set")
    }

    private fun discoverFirstCalendar(): String? {
        val calendarHome = discoverCalendarHome() ?: return null

        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:prop><d:resourcetype/><d:displayname/></d:prop>
            </d:propfind>
        """.trimIndent()

        val response = propfindRequest(calendarHome, body, depth = "1")
        if (response == null) return null

        // Find first actual calendar (has <calendar/> in resourcetype)
        val calendarPattern = Regex(
            """<(?:d:|D:)?response>.*?<(?:d:|D:)?href>([^<]+)</.*?<(?:c:|cal:)?calendar""",
            RegexOption.DOT_MATCHES_ALL
        )

        calendarPattern.findAll(response).forEach { match ->
            val href = match.groupValues[1]
            if (!href.contains("inbox") && !href.contains("outbox") &&
                !href.contains("notification") && href != calendarHome) {
                return if (href.startsWith("http")) href else "$ICLOUD_SERVER$href"
            }
        }
        return null
    }

    private fun formatDateForCalDAV(millis: Long): String {
        val instant = java.time.Instant.ofEpochMilli(millis)
        val formatter = java.time.format.DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(java.time.ZoneOffset.UTC)
        return formatter.format(instant)
    }
}
