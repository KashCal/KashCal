package org.onekash.kashcal.sync.integration

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import java.io.File
import java.util.UUID

/**
 * Round-trips RFC 5545 and RFC 7986 fields through real iCloud. Tests run in name order and
 * share state through the companion:
 * 1. Discover a calendar.
 * 2. Create an event with PRIORITY, GEO, URL, CATEGORIES and COLOR.
 * 3. Fetch it back and check the parsed and mapped fields.
 * 4. Change PRIORITY, GEO and CATEGORIES, patch the ICS with [IcsPatcher] and push it.
 * 5. Fetch it and check the changes, and that URL and title were kept.
 * 6. Delete the event.
 *
 * Run: ./gradlew :app:testDebugUnitTest -Pintegration --tests "*RealICloudRfc5545*"
 *
 * Requires: local.properties with iCloud credentials
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RealICloudRfc5545RoundTripTest {

    private lateinit var client: CalDavClient
    private lateinit var parser: ICalParser
    private var username: String? = null
    private var password: String? = null
    private val serverUrl = "https://caldav.icloud.com"
    private val factory = OkHttpCalDavClientFactory()

    // State shared across the ordered tests.
    companion object {
        private var testCalendarUrl: String? = null
        private var testEventUrl: String? = null
        private var testEventUid: String? = null
        private var testEventEtag: String? = null
        private var originalIcs: String? = null

        private const val TEST_TITLE = "RFC 5545/7986 Test Event"
        private const val TEST_LOCATION = "Apple Park, Cupertino"
        private const val TEST_PRIORITY = 1  // High priority
        private const val TEST_GEO_LAT = 37.334722
        private const val TEST_GEO_LON = -122.008889
        private const val TEST_COLOR = "#FF5733"  // Orange-red
        private const val TEST_URL = "https://example.com/test-event"
        private val TEST_CATEGORIES = listOf("TESTING", "KASHCAL", "RFC5545")

        // Values test04 patches in.
        private const val UPDATED_PRIORITY = 5  // Medium priority
        private const val UPDATED_GEO_LAT = 40.7128
        private const val UPDATED_GEO_LON = -74.0060
        private val UPDATED_CATEGORIES = listOf("UPDATED", "PATCHED")
    }

    @Before
    fun setup() {
        val quirks = ICloudQuirks()
        parser = ICalParser()
        loadCredentials()

        if (username != null && password != null) {
            val credentials = Credentials(
                username = username!!,
                password = password!!,
                serverUrl = serverUrl
            )
            client = factory.createClient(credentials, quirks)
        } else {
            // Blank credentials; every test skips without real ones.
            val credentials = Credentials(
                username = "",
                password = "",
                serverUrl = serverUrl
            )
            client = factory.createClient(credentials, quirks)
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
                    if (line.startsWith("#")) return@forEach
                    val parts = line.split("=", limit = 2).map { it.trim() }
                    if (parts.size == 2) {
                        when (parts[0]) {
                            "caldav.username" -> username = parts[1]
                            "caldav.app_password" -> password = parts[1]
                        }
                    }
                }
                if (username != null && password != null) break
            }
        }
    }

    private fun assumeCredentialsAvailable() {
        assumeTrue(
            "iCloud credentials not available",
            username != null && password != null
        )
    }

    // ========== Test 1: Discover Calendars ==========

    @Test
    fun `test01 discover iCloud calendars`() = runBlocking {
        assumeCredentialsAvailable()

        println("\n========== TEST 1: Discover iCloud Calendars ==========")

        println("Credentials loaded: username=${username?.take(3)}***, password=${if (password != null) "set" else "null"}")
        val principalResult = client.discoverPrincipal(serverUrl)
        if (!principalResult.isSuccess()) {
            val error = principalResult as? org.onekash.kashcal.sync.client.model.CalDavResult.Error
            println("Discovery failed: code=${error?.code}, message=${error?.message}")
        }
        assert(principalResult.isSuccess()) { "Failed to discover principal: $principalResult" }
        val principal = principalResult.getOrNull()!!
        println("Principal: $principal")

        val homeResult = client.discoverCalendarHome(principal)
        assert(homeResult.isSuccess()) { "Failed to discover calendar home" }
        val home = homeResult.getOrNull()!!.first()
        println("Calendar Home: $home")

        val calendarsResult = client.listCalendars(home)
        assert(calendarsResult.isSuccess()) { "Failed to list calendars" }
        val calendars = calendarsResult.getOrNull()!!

        println("\nFound ${calendars.size} calendars:")
        calendars.forEach { cal ->
            println("  - ${cal.displayName} (${cal.url})")
        }

        // The first calendar that isn't a webcal subscription; isReadOnly isn't checked.
        val testCalendar = calendars.firstOrNull { !it.url.contains("webcal") }
        assert(testCalendar != null) { "No writable calendar found" }

        testCalendarUrl = testCalendar!!.url
        println("\nSelected test calendar: ${testCalendar.displayName}")
        println("URL: $testCalendarUrl")
    }

    // ========== Test 2: Create Event with RFC 5545/7986 Fields ==========

    @Test
    fun `test02 create event with RFC 5545 fields and push to iCloud`() = runBlocking {
        assumeCredentialsAvailable()
        assumeTrue("Calendar not discovered", testCalendarUrl != null)

        println("\n========== TEST 2: Create Event with RFC 5545/7986 Fields ==========")

        testEventUid = "kashcal-rfc5545-test-${UUID.randomUUID()}@kashcal.test"

        // Tomorrow, 14:00 to 15:00 UTC.
        val now = System.currentTimeMillis()
        val tomorrow = now + 24 * 60 * 60 * 1000
        val startTs = tomorrow - (tomorrow % (24 * 60 * 60 * 1000)) + 14 * 60 * 60 * 1000
        val endTs = startTs + 60 * 60 * 1000  // +1 hour

        val ics = buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            appendLine("PRODID:-//KashCal//RFC5545 Test//EN")
            appendLine("BEGIN:VEVENT")
            appendLine("UID:$testEventUid")
            appendLine("DTSTAMP:${formatICalTimestamp(System.currentTimeMillis())}")
            appendLine("DTSTART:${formatICalTimestamp(startTs)}")
            appendLine("DTEND:${formatICalTimestamp(endTs)}")
            appendLine("SUMMARY:$TEST_TITLE")
            appendLine("LOCATION:$TEST_LOCATION")
            appendLine("DESCRIPTION:Testing RFC 5545/7986 field round-trip with KashCal")
            // RFC 5545 properties
            appendLine("PRIORITY:$TEST_PRIORITY")
            appendLine("GEO:$TEST_GEO_LAT;$TEST_GEO_LON")
            appendLine("URL:$TEST_URL")
            appendLine("CATEGORIES:${TEST_CATEGORIES.joinToString(",")}")
            // RFC 7986 property
            appendLine("COLOR:$TEST_COLOR")
            appendLine("END:VEVENT")
            appendLine("END:VCALENDAR")
        }

        println("Generated ICS:")
        println(ics)

        println("\nCreating event with UID: $testEventUid")

        val result = client.createEvent(testCalendarUrl!!, testEventUid!!, ics)
        println("CREATE result: $result")

        assert(result.isSuccess()) { "Failed to create event: ${result}" }

        val (eventUrl, etag) = result.getOrNull()!!
        testEventUrl = eventUrl
        testEventEtag = etag
        originalIcs = ics

        println("Event created successfully!")
        println("Etag: $testEventEtag")
    }

    // ========== Test 3: Fetch Event and Verify Parsing ==========

    @Test
    fun `test03 fetch event and verify RFC 5545 parsing`() = runBlocking {
        assumeCredentialsAvailable()
        assumeTrue("Event not created", testEventUrl != null)

        println("\n========== TEST 3: Fetch Event and Verify Parsing ==========")

        val result = client.fetchEvent(testEventUrl!!)
        assert(result.isSuccess()) { "Failed to fetch event" }

        val calDavEvent = result.getOrNull()!!
        val fetchedIcs = calDavEvent.icalData
        val fetchedEtag = calDavEvent.etag

        println("Fetched ICS from iCloud:")
        println(fetchedIcs)
        println("\nEtag: $fetchedEtag")

        val icalEvents = parser.parseAllEvents(fetchedIcs).getOrNull()
        assert(icalEvents != null) { "Failed to parse ICS" }
        assert(icalEvents!!.isNotEmpty()) { "No events parsed" }

        val icalEvent = icalEvents.first()
        println("\nParsed ICalEvent:")
        println("  UID: ${icalEvent.uid}")
        println("  Summary: ${icalEvent.summary}")
        println("  Priority: ${icalEvent.priority}")
        println("  GEO: ${icalEvent.geo}")
        println("  URL: ${icalEvent.url}")
        println("  Color: ${icalEvent.color}")
        println("  Categories: ${icalEvent.categories}")

        val event = ICalEventMapper.toEntity(
            icalEvent = icalEvent,
            rawIcal = fetchedIcs,
            calendarId = 1L,
            caldavUrl = testEventUrl,
            etag = fetchedEtag
        ).event

        println("\nMapped Event entity:")
        println("  title: ${event.title}")
        println("  priority: ${event.priority}")
        println("  geoLat: ${event.geoLat}")
        println("  geoLon: ${event.geoLon}")
        println("  color: ${event.color}")
        println("  url: ${event.url}")
        println("  categories: ${event.categories}")

        assert(event.title == TEST_TITLE) { "Title mismatch" }
        assert(event.priority == TEST_PRIORITY) { "Priority mismatch: expected $TEST_PRIORITY, got ${event.priority}" }
        assert(event.url == TEST_URL) { "URL mismatch: expected $TEST_URL, got ${event.url}" }

        // GEO within a floating-point tolerance.
        assert(event.geoLat != null) { "geoLat should not be null" }
        assert(event.geoLon != null) { "geoLon should not be null" }
        assert(kotlin.math.abs(event.geoLat!! - TEST_GEO_LAT) < 0.0001) {
            "geoLat mismatch: expected $TEST_GEO_LAT, got ${event.geoLat}"
        }
        assert(kotlin.math.abs(event.geoLon!! - TEST_GEO_LON) < 0.0001) {
            "geoLon mismatch: expected $TEST_GEO_LON, got ${event.geoLon}"
        }

        assert(event.categories != null) { "categories should not be null" }
        TEST_CATEGORIES.forEach { cat ->
            assert(event.categories!!.contains(cat)) { "Missing category: $cat" }
        }

        // iCloud may drop COLOR. event.color goes through Android's Color.parseColor, which
        // returns 0 in JVM tests, so the raw icalEvent.color is checked instead.
        if (icalEvent.color != null) {
            println("\niCloud PRESERVED COLOR property: ${icalEvent.color}")

            val expectedArgb = parseColorPureKotlin(TEST_COLOR)
            val actualArgb = parseColorPureKotlin(icalEvent.color)
            println("Expected ARGB: ${expectedArgb?.toString(16)} from $TEST_COLOR")
            println("Actual ARGB: ${actualArgb?.toString(16)} from ${icalEvent.color}")

            assert(actualArgb != null) { "Failed to parse color: ${icalEvent.color}" }
            assert(actualArgb == expectedArgb) {
                "Color mismatch: expected ${expectedArgb?.toString(16)}, got ${actualArgb?.toString(16)}"
            }
            println("✅ COLOR parsing verified!")
        } else {
            println("\niCloud STRIPPED COLOR property (some servers don't support RFC 7986)")
        }

        // test04 patches the server's copy.
        originalIcs = fetchedIcs
        testEventEtag = fetchedEtag

        println("\n✅ All RFC 5545 fields parsed correctly!")
    }

    // ========== Test 4: Update Event with Changed Fields ==========

    @Test
    fun `test04 update event with changed RFC fields`() = runBlocking {
        assumeCredentialsAvailable()
        assumeTrue("Event not created", testEventUrl != null)
        assumeTrue("Original ICS not available", originalIcs != null)

        println("\n========== TEST 4: Update Event with Changed RFC Fields ==========")

        val parseResult = parser.parseAllEvents(originalIcs!!)
        val icalEvent = parseResult.getOrNull()!!.first()

        val event = ICalEventMapper.toEntity(
            icalEvent = icalEvent,
            rawIcal = originalIcs!!,
            calendarId = 1L,
            caldavUrl = testEventUrl,
            etag = testEventEtag
        ).event.copy(
            priority = UPDATED_PRIORITY,
            geoLat = UPDATED_GEO_LAT,
            geoLon = UPDATED_GEO_LON,
            categories = UPDATED_CATEGORIES
        )

        println("Modified Event:")
        println("  priority: ${event.priority} (was $TEST_PRIORITY)")
        println("  geoLat: ${event.geoLat} (was $TEST_GEO_LAT)")
        println("  geoLon: ${event.geoLon} (was $TEST_GEO_LON)")
        println("  categories: ${event.categories} (was $TEST_CATEGORIES)")

        val patchedIcs = IcsPatcher.patch(originalIcs, event)

        println("\nPatched ICS:")
        println(patchedIcs)

        assert(patchedIcs.contains("PRIORITY:$UPDATED_PRIORITY")) {
            "Patched ICS should contain updated priority"
        }
        assert(patchedIcs.contains("$UPDATED_GEO_LAT")) {
            "Patched ICS should contain updated latitude"
        }
        assert(patchedIcs.contains("UPDATED")) {
            "Patched ICS should contain updated categories"
        }

        println("\nPushing update to: $testEventUrl")
        println("If-Match: $testEventEtag")

        val result = client.updateEvent(testEventUrl!!, patchedIcs, testEventEtag!!)
        println("UPDATE result: $result")

        assert(result.isSuccess()) { "Failed to update event: $result" }

        testEventEtag = result.getOrNull()!!
        originalIcs = patchedIcs

        println("\nEvent updated successfully!")
        println("New Etag: $testEventEtag")
    }

    // ========== Test 5: Fetch Updated Event and Verify ==========

    @Test
    fun `test05 fetch updated event and verify patching worked`() = runBlocking {
        assumeCredentialsAvailable()
        assumeTrue("Event not created", testEventUrl != null)

        println("\n========== TEST 5: Fetch Updated Event and Verify Patching ==========")

        val result = client.fetchEvent(testEventUrl!!)
        assert(result.isSuccess()) { "Failed to fetch updated event" }

        val calDavEvent = result.getOrNull()!!
        val fetchedIcs = calDavEvent.icalData

        println("Fetched updated ICS:")
        println(fetchedIcs)

        val icalEvent = parser.parseAllEvents(fetchedIcs).getOrNull()!!.first()
        val event = ICalEventMapper.toEntity(
            icalEvent = icalEvent,
            rawIcal = fetchedIcs,
            calendarId = 1L,
            caldavUrl = testEventUrl,
            etag = calDavEvent.etag
        ).event

        println("\nVerifying updated fields:")
        println("  priority: ${event.priority} (expected $UPDATED_PRIORITY)")
        println("  geoLat: ${event.geoLat} (expected $UPDATED_GEO_LAT)")
        println("  geoLon: ${event.geoLon} (expected $UPDATED_GEO_LON)")
        println("  categories: ${event.categories} (expected $UPDATED_CATEGORIES)")

        assert(event.priority == UPDATED_PRIORITY) {
            "Priority not updated: expected $UPDATED_PRIORITY, got ${event.priority}"
        }
        assert(event.geoLat != null && kotlin.math.abs(event.geoLat!! - UPDATED_GEO_LAT) < 0.0001) {
            "geoLat not updated: expected $UPDATED_GEO_LAT, got ${event.geoLat}"
        }
        assert(event.geoLon != null && kotlin.math.abs(event.geoLon!! - UPDATED_GEO_LON) < 0.0001) {
            "geoLon not updated: expected $UPDATED_GEO_LON, got ${event.geoLon}"
        }

        assert(event.categories != null) { "categories should not be null" }
        UPDATED_CATEGORIES.forEach { cat ->
            assert(event.categories!!.contains(cat)) {
                "Missing updated category: $cat. Got: ${event.categories}"
            }
        }

        // Fields the patch didn't change are kept.
        assert(event.url == TEST_URL) { "URL should be preserved: expected $TEST_URL, got ${event.url}" }
        assert(event.title == TEST_TITLE) { "Title should be preserved" }

        println("\n✅ All updated fields verified! Patching works correctly!")
    }

    // ========== Test 6: Delete Test Event ==========

    @Test
    fun `test06 delete test event from iCloud`() = runBlocking {
        assumeCredentialsAvailable()
        assumeTrue("Event not created", testEventUrl != null)

        println("\n========== TEST 6: Delete Test Event ==========")

        println("Deleting: $testEventUrl")

        val result = client.deleteEvent(testEventUrl!!, testEventEtag!!)
        println("DELETE result: $result")

        assert(result.isSuccess()) { "Failed to delete event: $result" }

        val fetchResult = client.fetchEvent(testEventUrl!!)
        assert(!fetchResult.isSuccess()) { "Event should be deleted but still exists" }

        println("\n✅ Event deleted successfully!")

        testEventUrl = null
        testEventUid = null
        testEventEtag = null
        originalIcs = null
    }

    // ========== Cleanup ==========

    @After
    fun cleanup() {
        // test06 deletes the event. If an earlier test fails, the event stays on iCloud and
        // needs a manual delete.
    }

    // ========== Helpers ==========

    private fun formatICalTimestamp(millis: Long): String {
        val instant = java.time.Instant.ofEpochMilli(millis)
        val formatter = java.time.format.DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(java.time.ZoneOffset.UTC)
        return formatter.format(instant)
    }

    /**
     * Parses `RRGGBB` or `AARRGGBB` hex, with or without a leading `#`, to ARGB, or null for
     * anything else. Android's Color.parseColor returns 0 in JVM tests.
     */
    private fun parseColorPureKotlin(color: String?): Int? {
        if (color.isNullOrBlank()) return null
        return try {
            val hex = color.removePrefix("#")
            when (hex.length) {
                6 -> (0xFF shl 24) or hex.toLong(16).toInt()  // RGB -> ARGB with full alpha
                8 -> hex.toLong(16).toInt()  // Already ARGB
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
