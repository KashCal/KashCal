package org.onekash.kashcal.sync.integration

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials as CalDavCredentials
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Integration test to verify WebDAV MOVE operation support on CalDAV servers.
 *
 * Tests whether iCloud and Nextcloud support the MOVE method for moving
 * calendar events between calendars within the same account.
 *
 * Run: ./gradlew testDebugUnitTest --tests "*MoveOperationServerTest*"
 */
class MoveOperationServerTest {

    private lateinit var client: OkHttpClient

    // iCloud credentials
    private var icloudUsername: String? = null
    private var icloudPassword: String? = null

    // Nextcloud credentials
    private var nextcloudServer: String? = null
    private var nextcloudUsername: String? = null
    private var nextcloudPassword: String? = null

    // Track created events for cleanup
    private val createdEventUrls = mutableListOf<Pair<String, String>>() // (url, auth)

    @Before
    fun setup() {
        loadCredentials()

        client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false) // Handle redirects manually for auth
            .build()
    }

    @After
    fun cleanup() {
        // Delete any events we created
        createdEventUrls.forEach { (url, auth) ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .delete()
                    .header("Authorization", auth)
                    .build()
                client.newCall(request).execute().close()
            } catch (e: Exception) {
                println("Cleanup failed for $url: ${e.message}")
            }
        }
    }

    private fun loadCredentials() {
        val possiblePaths = listOf(
            "local.properties",
            "../local.properties",
            "/onekash/KashCal/local.properties"
        )

        val props = java.util.Properties()
        for (path in possiblePaths) {
            val file = File(path)
            if (file.exists()) {
                file.inputStream().use { props.load(it) }
                break
            }
        }

        icloudUsername = props.getProperty("ICLOUD_USERNAME")
        icloudPassword = props.getProperty("ICLOUD_APP_PASSWORD")
        nextcloudServer = props.getProperty("NEXTCLOUD_SERVER")
        nextcloudUsername = props.getProperty("NEXTCLOUD_USERNAME")
        nextcloudPassword = props.getProperty("NEXTCLOUD_PASSWORD")
    }

    // ==================== iCloud MOVE Tests ====================

    @Test
    fun `iCloud - MOVE method returns expected response`() = runBlocking {
        assumeTrue("iCloud credentials not configured",
            icloudUsername != null && icloudPassword != null)

        val auth = Credentials.basic(icloudUsername!!, icloudPassword!!)

        // Step 1: Discover calendars
        println("=== iCloud MOVE Test ===")
        val calendars = discoverICloudCalendars(auth)
        assumeTrue("Need at least 2 calendars for MOVE test", calendars.size >= 2)

        val sourceCalendar = calendars[0]
        val targetCalendar = calendars[1]
        println("Source calendar: ${sourceCalendar.first}")
        println("Target calendar: ${targetCalendar.first}")

        // Step 2: Create test event in source calendar
        val uid = "move-test-${UUID.randomUUID()}@kashcal.test"
        val eventUrl = createTestEvent(sourceCalendar.second, uid, auth)
        assertNotNull("Failed to create test event", eventUrl)
        println("Created event: $eventUrl")

        // Step 3: Attempt MOVE to target calendar
        val targetUrl = "${targetCalendar.second}$uid.ics"
        println("Attempting MOVE to: $targetUrl")

        val moveRequest = Request.Builder()
            .url(eventUrl!!)
            .method("MOVE", null)
            .header("Authorization", auth)
            .header("Destination", targetUrl)
            .header("Overwrite", "F")
            .build()

        val moveResponse = client.newCall(moveRequest).execute()
        val moveStatus = moveResponse.code
        val moveBody = moveResponse.body?.string() ?: ""
        moveResponse.close()

        println("MOVE response: $moveStatus")
        println("MOVE body: ${moveBody.take(500)}")

        // Track for cleanup (might be at source or target depending on success)
        createdEventUrls.add(eventUrl to auth)
        createdEventUrls.add(targetUrl to auth)

        // Analyze result
        when (moveStatus) {
            201, 204 -> {
                println("✅ iCloud SUPPORTS MOVE (status $moveStatus)")
                // Verify event is at target
                val getTarget = Request.Builder()
                    .url(targetUrl)
                    .get()
                    .header("Authorization", auth)
                    .build()
                val targetResponse = client.newCall(getTarget).execute()
                assertEquals("Event should exist at target", 200, targetResponse.code)
                targetResponse.close()
            }
            412 -> {
                println("⚠️ iCloud returns 412 for MOVE (precondition failed)")
                println("This confirms EDGE_CASES.md: 'DELETE first, then PUT (not MOVE)'")
            }
            403 -> {
                println("⚠️ iCloud returns 403 for MOVE (forbidden)")
            }
            405 -> {
                println("❌ iCloud does NOT support MOVE (405 Method Not Allowed)")
            }
            else -> {
                println("⚠️ iCloud MOVE returned unexpected status: $moveStatus")
            }
        }

        // Record result for summary
        println("\n=== iCloud MOVE Result: HTTP $moveStatus ===")
    }

    // ==================== Nextcloud MOVE Tests ====================

    @Test
    fun `Nextcloud - MOVE method returns expected response`() = runBlocking {
        assumeTrue("Nextcloud credentials not configured",
            nextcloudServer != null && nextcloudUsername != null && nextcloudPassword != null)

        val auth = Credentials.basic(nextcloudUsername!!, nextcloudPassword!!)

        // Step 1: Discover calendars
        println("=== Nextcloud MOVE Test ===")
        var calendars = discoverNextcloudCalendars(auth)

        // Create test calendar if we don't have 2 writable ones
        if (calendars.size < 2) {
            println("Only ${calendars.size} calendars found, creating test calendar...")
            val testCalUrl = createTestCalendar(
                "$nextcloudServer/remote.php/dav/calendars/$nextcloudUsername/",
                "movetest-${System.currentTimeMillis()}",
                auth
            )
            if (testCalUrl != null) {
                println("Created test calendar: $testCalUrl")
                calendars = discoverNextcloudCalendars(auth)
            }
        }

        assumeTrue("Need at least 2 calendars for MOVE test", calendars.size >= 2)

        val sourceCalendar = calendars[0]
        val targetCalendar = calendars[1]
        println("Source calendar: ${sourceCalendar.first}")
        println("Target calendar: ${targetCalendar.first}")

        // Step 2: Create test event in source calendar
        val uid = "move-test-${UUID.randomUUID()}@kashcal.test"
        val eventUrl = createTestEvent(sourceCalendar.second, uid, auth)
        assertNotNull("Failed to create test event", eventUrl)
        println("Created event: $eventUrl")

        // Step 3: Attempt MOVE to target calendar
        val targetUrl = "${targetCalendar.second}$uid.ics"
        println("Attempting MOVE to: $targetUrl")

        val moveRequest = Request.Builder()
            .url(eventUrl!!)
            .method("MOVE", null)
            .header("Authorization", auth)
            .header("Destination", targetUrl)
            .header("Overwrite", "F")
            .build()

        val moveResponse = client.newCall(moveRequest).execute()
        val moveStatus = moveResponse.code
        val moveBody = moveResponse.body?.string() ?: ""
        moveResponse.close()

        println("MOVE response: $moveStatus")
        println("MOVE body: ${moveBody.take(500)}")

        // Track for cleanup
        createdEventUrls.add(eventUrl to auth)
        createdEventUrls.add(targetUrl to auth)

        // Analyze result
        when (moveStatus) {
            201, 204 -> {
                println("✅ Nextcloud SUPPORTS MOVE (status $moveStatus)")
                // Verify event is at target
                val getTarget = Request.Builder()
                    .url(targetUrl)
                    .get()
                    .header("Authorization", auth)
                    .build()
                val targetResponse = client.newCall(getTarget).execute()
                assertEquals("Event should exist at target", 200, targetResponse.code)
                targetResponse.close()

                // Verify event is gone from source
                val getSource = Request.Builder()
                    .url(eventUrl)
                    .get()
                    .header("Authorization", auth)
                    .build()
                val sourceResponse = client.newCall(getSource).execute()
                assertEquals("Event should be gone from source", 404, sourceResponse.code)
                sourceResponse.close()
                println("✅ Event successfully moved (verified at target, gone from source)")
            }
            412 -> {
                println("⚠️ Nextcloud returns 412 for MOVE (precondition failed)")
            }
            403 -> {
                println("⚠️ Nextcloud returns 403 for MOVE (forbidden)")
            }
            405 -> {
                println("❌ Nextcloud does NOT support MOVE (405 Method Not Allowed)")
            }
            else -> {
                println("⚠️ Nextcloud MOVE returned unexpected status: $moveStatus")
            }
        }

        println("\n=== Nextcloud MOVE Result: HTTP $moveStatus ===")
    }

    // ==================== CalDavClient.moveEvent() Tests ====================

    @Test
    fun `iCloud - CalDavClient moveEvent implementation works`() = runBlocking {
        assumeTrue("iCloud credentials not configured",
            icloudUsername != null && icloudPassword != null)

        val auth = Credentials.basic(icloudUsername!!, icloudPassword!!)

        println("=== iCloud CalDavClient.moveEvent() Test ===")
        val calendars = discoverICloudCalendars(auth)
        assumeTrue("Need at least 2 calendars", calendars.size >= 2)

        val sourceCalendar = calendars[0]
        val targetCalendar = calendars[1]
        println("Source: ${sourceCalendar.first} (${sourceCalendar.second})")
        println("Target: ${targetCalendar.first} (${targetCalendar.second})")

        // Create test event
        val uid = "caldavclient-move-${UUID.randomUUID()}@kashcal.test"
        val eventUrl = createTestEvent(sourceCalendar.second, uid, auth)
        assertNotNull("Failed to create test event", eventUrl)
        println("Created: $eventUrl")

        // Use CalDavClient.moveEvent()
        val clientFactory = OkHttpCalDavClientFactory()
        val caldavClient = clientFactory.createClient(
            CalDavCredentials(icloudUsername!!, icloudPassword!!, "https://caldav.icloud.com"),
            ICloudQuirks()
        )

        val result = caldavClient.moveEvent(eventUrl!!, targetCalendar.second, uid)

        // Track for cleanup
        createdEventUrls.add(eventUrl to auth)
        if (result.isSuccess()) {
            val (newUrl, _) = result.getOrNull()!!
            createdEventUrls.add(newUrl to auth)
        }

        println("moveEvent result: $result")

        assertTrue("moveEvent should succeed", result.isSuccess())
        val (newUrl, newEtag) = result.getOrNull()!!
        println("✅ moveEvent succeeded: newUrl=$newUrl, newEtag=$newEtag")

        // Verify event exists at target
        val verifyRequest = Request.Builder()
            .url(newUrl)
            .get()
            .header("Authorization", auth)
            .build()
        val verifyResponse = client.newCall(verifyRequest).execute()
        assertEquals("Event should exist at new location", 200, verifyResponse.code)
        verifyResponse.close()
        println("✅ Event verified at target location")
    }

    @Test
    fun `Nextcloud - CalDavClient moveEvent implementation works`() = runBlocking {
        assumeTrue("Nextcloud credentials not configured",
            nextcloudServer != null && nextcloudUsername != null && nextcloudPassword != null)

        val auth = Credentials.basic(nextcloudUsername!!, nextcloudPassword!!)

        println("=== Nextcloud CalDavClient.moveEvent() Test ===")
        val calendars = discoverNextcloudCalendars(auth)
        assumeTrue("Need at least 2 calendars", calendars.size >= 2)

        val sourceCalendar = calendars[0]
        val targetCalendar = calendars[1]
        println("Source: ${sourceCalendar.first} (${sourceCalendar.second})")
        println("Target: ${targetCalendar.first} (${targetCalendar.second})")

        // Create test event
        val uid = "caldavclient-move-${UUID.randomUUID()}@kashcal.test"
        val eventUrl = createTestEvent(sourceCalendar.second, uid, auth)
        assertNotNull("Failed to create test event", eventUrl)
        println("Created: $eventUrl")

        // Use CalDavClient.moveEvent()
        val clientFactory = OkHttpCalDavClientFactory()
        val caldavClient = clientFactory.createClient(
            CalDavCredentials(nextcloudUsername!!, nextcloudPassword!!, nextcloudServer!!),
            DefaultQuirks(nextcloudServer!!)
        )

        val result = caldavClient.moveEvent(eventUrl!!, targetCalendar.second, uid)

        // Track for cleanup
        createdEventUrls.add(eventUrl to auth)
        if (result.isSuccess()) {
            val (newUrl, _) = result.getOrNull()!!
            createdEventUrls.add(newUrl to auth)
        }

        println("moveEvent result: $result")

        assertTrue("moveEvent should succeed", result.isSuccess())
        val (newUrl, newEtag) = result.getOrNull()!!
        println("✅ moveEvent succeeded: newUrl=$newUrl, newEtag=$newEtag")

        // Verify event exists at target
        val verifyRequest = Request.Builder()
            .url(newUrl)
            .get()
            .header("Authorization", auth)
            .build()
        val verifyResponse = client.newCall(verifyRequest).execute()
        assertEquals("Event should exist at new location", 200, verifyResponse.code)
        verifyResponse.close()
        println("✅ Event verified at target location")
    }

    // ==================== Helper Methods ====================

    private fun discoverICloudCalendars(auth: String): List<Pair<String, String>> = runBlocking {
        // Use the project's CalDavClient for proper iCloud discovery
        val clientFactory = OkHttpCalDavClientFactory()
        val quirks = ICloudQuirks()
        val caldavClient = clientFactory.createClient(
            CalDavCredentials(icloudUsername!!, icloudPassword!!, "https://caldav.icloud.com"),
            quirks
        )

        // Discover principal
        val principalResult = caldavClient.discoverPrincipal("https://caldav.icloud.com/")
        if (!principalResult.isSuccess()) {
            println("Failed to discover principal: $principalResult")
            return@runBlocking emptyList()
        }
        val principalUrl = principalResult.getOrNull()!!
        println("Principal URL: $principalUrl")

        // Discover calendar home
        val homeResult = caldavClient.discoverCalendarHome(principalUrl)
        if (!homeResult.isSuccess()) {
            println("Failed to discover calendar home: $homeResult")
            return@runBlocking emptyList()
        }
        val calendarHome = homeResult.getOrNull()!!.first()
        println("Calendar home: $calendarHome")

        // List calendars
        val calendarsResult = caldavClient.listCalendars(calendarHome)
        if (!calendarsResult.isSuccess()) {
            println("Failed to list calendars: $calendarsResult")
            return@runBlocking emptyList()
        }

        val calendars = calendarsResult.getOrNull()!!
            .filter { !it.url.contains("inbox") && !it.url.contains("outbox") }
            .map { it.displayName to it.url }

        println("Found ${calendars.size} iCloud calendars:")
        calendars.forEach { println("  - ${it.first}: ${it.second}") }
        calendars
    }

    private fun discoverNextcloudCalendars(auth: String): List<Pair<String, String>> {
        val davUrl = "$nextcloudServer/remote.php/dav/calendars/$nextcloudUsername/"
        return listCalendars(davUrl, auth)
    }

    private fun listCalendars(calendarHomeUrl: String, auth: String): List<Pair<String, String>> {
        val listRequest = Request.Builder()
            .url(calendarHomeUrl)
            .method("PROPFIND", """
                <?xml version="1.0" encoding="UTF-8"?>
                <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/">
                    <d:prop>
                        <d:displayname/>
                        <d:resourcetype/>
                    </d:prop>
                </d:propfind>
            """.trimIndent().toRequestBody("application/xml".toMediaType()))
            .header("Authorization", auth)
            .header("Depth", "1")
            .build()

        val response = client.newCall(listRequest).execute()
        val body = response.body?.string() ?: ""
        response.close()

        // Parse calendar URLs (look for resources with <calendar/> in resourcetype)
        val calendars = mutableListOf<Pair<String, String>>()
        val responsePattern = Regex("""<d:response>(.*?)</d:response>""", RegexOption.DOT_MATCHES_ALL)
        val hrefPattern = Regex("""<d:href>([^<]+)</d:href>""")
        val namePattern = Regex("""<d:displayname>([^<]*)</d:displayname>""")
        val calendarPattern = Regex("""<(?:c:|cal:)?calendar""", RegexOption.IGNORE_CASE)

        responsePattern.findAll(body).forEach { match ->
            val responseXml = match.groupValues[1]
            if (calendarPattern.containsMatchIn(responseXml)) {
                val href = hrefPattern.find(responseXml)?.groupValues?.get(1) ?: return@forEach
                val name = namePattern.find(responseXml)?.groupValues?.get(1) ?: href

                // Skip inbox/outbox/contact_birthdays (read-only)
                if (href.contains("inbox") || href.contains("outbox") ||
                    href.contains("contact_birthdays")) return@forEach

                val fullUrl = when {
                    href.startsWith("http") -> href
                    calendarHomeUrl.contains("icloud.com") -> "https://caldav.icloud.com$href"
                    else -> "$nextcloudServer$href"
                }
                calendars.add(name to fullUrl)
            }
        }

        println("Found ${calendars.size} calendars:")
        calendars.forEach { println("  - ${it.first}: ${it.second}") }
        return calendars
    }

    private fun createTestEvent(calendarUrl: String, uid: String, auth: String): String? {
        val now = System.currentTimeMillis()
        val dtstart = formatICalDate(now + 86400000) // Tomorrow
        val dtend = formatICalDate(now + 86400000 + 3600000) // +1 hour
        val dtstamp = formatICalDate(now)

        val icalData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//KashCal//MOVE Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:$dtstamp
            DTSTART:$dtstart
            DTEND:$dtend
            SUMMARY:MOVE Test Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val eventUrl = "$calendarUrl$uid.ics"

        val request = Request.Builder()
            .url(eventUrl)
            .put(icalData.toRequestBody("text/calendar".toMediaType()))
            .header("Authorization", auth)
            .header("If-None-Match", "*")
            .build()

        val response = client.newCall(request).execute()
        val status = response.code
        response.close()

        return if (status in 200..299) eventUrl else null
    }

    private fun formatICalDate(millis: Long): String {
        val instant = java.time.Instant.ofEpochMilli(millis)
        val zdt = instant.atZone(java.time.ZoneOffset.UTC)
        return java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(zdt)
    }

    private fun createTestCalendar(calendarHomeUrl: String, name: String, auth: String): String? {
        val calendarUrl = "$calendarHomeUrl$name/"

        val mkcalendarBody = """
            <?xml version="1.0" encoding="UTF-8"?>
            <c:mkcalendar xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:set>
                    <d:prop>
                        <d:displayname>$name</d:displayname>
                        <c:supported-calendar-component-set>
                            <c:comp name="VEVENT"/>
                        </c:supported-calendar-component-set>
                    </d:prop>
                </d:set>
            </c:mkcalendar>
        """.trimIndent()

        val request = Request.Builder()
            .url(calendarUrl)
            .method("MKCALENDAR", mkcalendarBody.toRequestBody("application/xml".toMediaType()))
            .header("Authorization", auth)
            .build()

        val response = client.newCall(request).execute()
        val status = response.code
        response.close()

        println("MKCALENDAR response: $status")
        return if (status in 200..299) calendarUrl else null
    }
}
