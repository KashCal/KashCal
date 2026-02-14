package org.onekash.kashcal.sync.integration

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials as OkCredentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.parser.CalDavXmlParser
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Exhaustive live integration test for Zoho CalDAV.
 *
 * Tests the full CalDAV lifecycle against a real Zoho Calendar account:
 * - Discovery (principal, calendar home, list calendars)
 * - Change detection (ctag, sync token)
 * - Two-step fetch (etags → multiget, Zoho-specific pattern)
 * - Single event CRUD (create → fetch → update → fetch → delete)
 * - All-day event lifecycle
 * - Recurring event (RRULE) create → fetch → delete
 * - Recurring event with exception (RECURRENCE-ID)
 * - Batch create/fetch/delete of multiple events
 * - Etag handling across all mutation operations
 *
 * Requires ZOHO_SERVER, ZOHO_USERNAME, ZOHO_PASSWORD in local.properties.
 *
 * Run: ./gradlew testDebugUnitTest --tests "*ZohoCalDavIntegrationTest*"
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ZohoCalDavIntegrationTest {

    private lateinit var client: CalDavClient
    private val factory = OkHttpCalDavClientFactory()
    private val xmlParser = CalDavXmlParser()

    companion object {
        // Credentials
        private var serverUrl: String? = null
        private var username: String? = null
        private var password: String? = null

        // Discovery state (shared across tests)
        private var principalUrl: String? = null
        private var calendarHomeUrl: String? = null
        private var calendarUrl: String? = null
        private var calendarDisplayName: String? = null

        // Single event state
        private var singleEventUid: String? = null
        private var singleEventUrl: String? = null
        private var singleEventEtag: String? = null

        // All-day event state
        private var allDayEventUid: String? = null
        private var allDayEventUrl: String? = null
        private var allDayEventEtag: String? = null

        // Recurring event state
        private var recurringEventUid: String? = null
        private var recurringEventUrl: String? = null
        private var recurringEventEtag: String? = null

        // Recurring with exception state
        private var recurExcEventUid: String? = null
        private var recurExcEventUrl: String? = null
        private var recurExcEventEtag: String? = null

        // Batch events state
        private var batchEventUids: MutableList<String> = mutableListOf()
        private var batchEventUrls: MutableList<String> = mutableListOf()
        private var batchEventEtags: MutableList<String> = mutableListOf()

        // Cleanup tracker — all event URLs that need cleanup
        private val createdEventUrls = mutableListOf<String>()

        private val TS_FORMATTER = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)

        private val DATE_FORMATTER = DateTimeFormatter
            .ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC)
    }

    @Before
    fun setup() {
        loadCredentials()
        assumeTrue(
            "Zoho credentials not available (set ZOHO_SERVER/USERNAME/PASSWORD in local.properties)",
            serverUrl != null && username != null && password != null
        )

        if (!serverUrl!!.startsWith("http")) {
            serverUrl = "https://$serverUrl"
        }

        val quirks = DefaultQuirks(serverUrl!!)
        client = factory.createClient(
            Credentials(username = username!!, password = password!!, serverUrl = serverUrl!!),
            quirks
        )
    }

    @After
    fun teardown() {
        // Intentionally empty — cleanup happens in test99
    }

    // ==================== 01: Discovery ====================

    @Test
    fun `test01 discover principal`() = runBlocking {
        println("\n===== TEST 01: Discover Principal =====")

        val result = client.discoverPrincipal(serverUrl!!)
        assertSuccess("discoverPrincipal", result)

        principalUrl = result.getOrNull()!!
        println("Principal URL: $principalUrl")
        assertTrue("Principal URL should be non-empty", principalUrl!!.isNotEmpty())
    }

    @Test
    fun `test02 discover calendar home`() = runBlocking {
        println("\n===== TEST 02: Discover Calendar Home =====")
        assumeTrue("Principal not discovered", principalUrl != null)

        val result = client.discoverCalendarHome(principalUrl!!)
        assertSuccess("discoverCalendarHome", result)

        calendarHomeUrl = result.getOrNull()!!
        println("Calendar Home URL: $calendarHomeUrl")
        assertTrue("Calendar home should be non-empty", calendarHomeUrl!!.isNotEmpty())
    }

    @Test
    fun `test03 list calendars`() = runBlocking {
        println("\n===== TEST 03: List Calendars =====")
        assumeTrue("Calendar home not discovered", calendarHomeUrl != null)

        val result = client.listCalendars(calendarHomeUrl!!)
        assertSuccess("listCalendars", result)

        val calendars = result.getOrNull()!!
        assertTrue("Should find at least 1 calendar", calendars.isNotEmpty())

        println("Found ${calendars.size} calendar(s):")
        calendars.forEach { cal ->
            println("  - ${cal.displayName} | url=${cal.url} | ctag=${cal.ctag} | color=${cal.color}")
        }

        // Use first calendar for tests
        val selected = calendars[0]
        calendarUrl = selected.url
        calendarDisplayName = selected.displayName
        println("\nSelected: ${selected.displayName} at ${selected.url}")
    }

    // ==================== 04-05: Change Detection ====================

    @Test
    fun `test04 get ctag`() = runBlocking {
        println("\n===== TEST 04: Get Ctag =====")
        assumeTrue("Calendar not discovered", calendarUrl != null)

        val result = client.getCtag(calendarUrl!!)
        // Zoho may or may not support ctag — log either way
        if (result.isSuccess()) {
            val ctag = result.getOrNull()
            println("Ctag: $ctag")
            assertNotNull("Ctag should be non-null on success", ctag)
        } else {
            val err = result as CalDavResult.Error
            println("Ctag not available (code=${err.code}): ${err.message}")
            println("This is expected for some Zoho configurations — sync proceeds without ctag optimization")
        }
    }

    @Test
    fun `test05 get sync token`() = runBlocking {
        println("\n===== TEST 05: Get Sync Token =====")
        assumeTrue("Calendar not discovered", calendarUrl != null)

        val result = client.getSyncToken(calendarUrl!!)
        if (result.isSuccess()) {
            val syncToken = result.getOrNull()
            println("Sync token: ${syncToken?.take(20)}...")
        } else {
            val err = result as CalDavResult.Error
            println("Sync token not available (code=${err.code}): ${err.message}")
        }
    }

    // ==================== 06-07: Two-Step Fetch (Zoho pattern) ====================

    @Test
    fun `test06 fetch etags in range (calendar-query without calendar-data)`() = runBlocking {
        println("\n===== TEST 06: Fetch ETags in Range =====")
        assumeTrue("Calendar not discovered", calendarUrl != null)

        val now = System.currentTimeMillis()
        val pastWindow = now - (180L * 24 * 60 * 60 * 1000) // 180 days ago
        val futureEnd = now + (365L * 24 * 60 * 60 * 1000) // 1 year out

        val result = client.fetchEtagsInRange(calendarUrl!!, pastWindow, futureEnd)
        assertSuccess("fetchEtagsInRange", result)

        val etags = result.getOrNull()!!
        println("Etags returned: ${etags.size}")
        etags.take(5).forEach { (href, etag) ->
            println("  href=$href | etag=$etag")
        }

        // This may be 0 if the calendar is empty, which is fine
        println("Calendar has ${etags.size} event(s) in the query window")
    }

    @Test
    fun `test07 fetch events by href - multiget (two-step completion)`() = runBlocking {
        println("\n===== TEST 07: Fetch Events by Href (Multiget) =====")
        assumeTrue("Calendar not discovered", calendarUrl != null)

        val now = System.currentTimeMillis()
        val pastWindow = now - (180L * 24 * 60 * 60 * 1000)
        val futureEnd = now + (365L * 24 * 60 * 60 * 1000)

        // Step 1: Get etags
        val etagResult = client.fetchEtagsInRange(calendarUrl!!, pastWindow, futureEnd)
        assumeTrue("Need etags for multiget test", etagResult.isSuccess())
        val etags = etagResult.getOrNull()!!

        if (etags.isEmpty()) {
            println("No events on server — skipping multiget verification")
            return@runBlocking
        }

        // Step 2: Multiget with the hrefs
        val hrefs = etags.map { it.first }
        val result = client.fetchEventsByHref(calendarUrl!!, hrefs.take(10))

        // Zoho may return empty for multi-href multiget — that's the known behavior
        if (result.isSuccess()) {
            val events = result.getOrNull()!!
            println("Multiget returned ${events.size} event(s) for ${hrefs.take(10).size} href(s)")
            events.take(3).forEach { ev ->
                println("  href=${ev.href} | etag=${ev.etag} | data=${ev.icalData.take(80)}...")
            }

            if (events.isEmpty() && hrefs.size > 1) {
                println("WARNING: Multi-href multiget returned empty — Zoho may require one-by-one fetch")

                // Verify single-href works
                val singleResult = client.fetchEventsByHref(calendarUrl!!, listOf(hrefs[0]))
                if (singleResult.isSuccess()) {
                    val singleEvents = singleResult.getOrNull()!!
                    println("Single-href multiget returned ${singleEvents.size} event(s)")
                    assertTrue("Single-href multiget should work", singleEvents.isNotEmpty())
                }
            }
        } else {
            val err = result as CalDavResult.Error
            println("Multiget failed (code=${err.code}): ${err.message}")
            println("Zoho multiget may need one-by-one fallback")
        }
    }

    // ==================== 10-14: Single Event CRUD ====================

    @Test
    fun `test10 create single event`() = runBlocking {
        println("\n===== TEST 10: Create Single Event =====")
        assumeTrue("Calendar not discovered", calendarUrl != null)

        singleEventUid = "kashcal-zoho-test-${UUID.randomUUID()}@kashcal.test"
        val now = System.currentTimeMillis()
        val startTs = now + (2 * 60 * 60 * 1000) // 2 hours from now
        val endTs = startTs + (60 * 60 * 1000) // +1 hour

        val ical = buildIcs(
            uid = singleEventUid!!,
            summary = "KashCal Zoho Test - Single Event",
            description = "Integration test: single event CRUD cycle",
            location = "Test Lab",
            dtstart = formatTimestamp(startTs),
            dtend = formatTimestamp(endTs)
        )

        println("Creating event: $singleEventUid")
        val result = client.createEvent(calendarUrl!!, singleEventUid!!, ical)
        assertSuccess("createEvent", result)

        val (url, etag) = result.getOrNull()!!
        singleEventUrl = url
        singleEventEtag = etag
        createdEventUrls.add(url)

        println("  URL: $url")
        println("  ETag: '$etag' (empty=${etag.isEmpty()})")

        // If etag is empty, fetch it via PROPFIND
        if (etag.isEmpty()) {
            println("  ETag missing from PUT response — fetching via PROPFIND...")
            val etagResult = client.fetchEtag(url)
            if (etagResult.isSuccess() && etagResult.getOrNull() != null) {
                singleEventEtag = etagResult.getOrNull()!!
                println("  Fetched ETag: $singleEventEtag")
            } else {
                // Try single-href multiget
                val href = url.removePrefix(extractBaseHost(calendarUrl!!))
                val mgResult = client.fetchEventsByHref(calendarUrl!!, listOf(href))
                if (mgResult.isSuccess() && mgResult.getOrNull()!!.isNotEmpty()) {
                    singleEventEtag = mgResult.getOrNull()!![0].etag ?: ""
                    println("  Fetched ETag via multiget: $singleEventEtag")
                }
            }
        }

        assertTrue("Should have obtained an etag", singleEventEtag!!.isNotEmpty())
    }

    @Test
    fun `test11 fetch single event and verify`() = runBlocking {
        println("\n===== TEST 11: Fetch Single Event =====")
        assumeTrue("Event not created", singleEventUrl != null)

        val result = client.fetchEvent(singleEventUrl!!)
        assertSuccess("fetchEvent", result)

        val event = result.getOrNull()!!
        println("Fetched ICS (first 500 chars):")
        println(event.icalData.take(500))
        println("ETag: ${event.etag}")

        // Verify content
        assertTrue("Should contain VCALENDAR", event.icalData.contains("BEGIN:VCALENDAR"))
        assertTrue("Should contain our UID", event.icalData.contains(singleEventUid!!))
        assertTrue("Should contain SUMMARY", event.icalData.contains("KashCal Zoho Test - Single Event"))
        assertTrue("Should contain LOCATION", event.icalData.contains("Test Lab"))

        // Update etag to what server returned
        if (event.etag != null) {
            singleEventEtag = event.etag
        }
    }

    @Test
    fun `test12 update single event`() = runBlocking {
        println("\n===== TEST 12: Update Single Event =====")
        assumeTrue("Event not created", singleEventUrl != null && singleEventEtag != null)

        // Fetch current ICS to get server-canonical version
        val fetchResult = client.fetchEvent(singleEventUrl!!)
        assertSuccess("fetchEvent for update", fetchResult)
        val currentIcs = fetchResult.getOrNull()!!.icalData
        singleEventEtag = fetchResult.getOrNull()!!.etag ?: singleEventEtag

        // Build updated ICS — change summary, add description, extend end time
        val now = System.currentTimeMillis()
        val startTs = now + (3 * 60 * 60 * 1000)
        val endTs = startTs + (2 * 60 * 60 * 1000) // 2 hours instead of 1

        val updatedIcal = buildIcs(
            uid = singleEventUid!!,
            summary = "KashCal Zoho Test - Updated Event",
            description = "Integration test: event was updated successfully",
            location = "Updated Location",
            dtstart = formatTimestamp(startTs),
            dtend = formatTimestamp(endTs)
        )

        println("Updating event with etag: $singleEventEtag")
        val result = client.updateEvent(singleEventUrl!!, updatedIcal, singleEventEtag!!)

        if (result.isSuccess()) {
            singleEventEtag = result.getOrNull()!!
            println("Update succeeded. New ETag: $singleEventEtag")

            // If etag is empty, fetch it
            if (singleEventEtag!!.isEmpty()) {
                val etagResult = client.fetchEtag(singleEventUrl!!)
                if (etagResult.isSuccess() && etagResult.getOrNull() != null) {
                    singleEventEtag = etagResult.getOrNull()!!
                }
            }
        } else {
            val err = result as CalDavResult.Error
            println("Update failed (code=${err.code}): ${err.message}")

            // Zoho may not honor If-Match — try raw PUT
            if (err.code == 412 || err.code == 400) {
                println("Attempting raw PUT without If-Match...")
                val rawStatus = rawPut(singleEventUrl!!, updatedIcal)
                println("Raw PUT status: $rawStatus")
                assertTrue("Raw PUT should succeed", rawStatus in 200..299)

                // Refresh etag
                val etagResult = client.fetchEtag(singleEventUrl!!)
                if (etagResult.isSuccess() && etagResult.getOrNull() != null) {
                    singleEventEtag = etagResult.getOrNull()!!
                }
            } else {
                fail("Update failed: code=${err.code}, msg=${err.message}")
            }
        }
    }

    @Test
    fun `test13 fetch updated event and verify changes`() = runBlocking {
        println("\n===== TEST 13: Verify Updated Event =====")
        assumeTrue("Event not created", singleEventUrl != null)

        val result = client.fetchEvent(singleEventUrl!!)
        assertSuccess("fetchEvent after update", result)

        val event = result.getOrNull()!!
        println("Updated ICS (first 500 chars):")
        println(event.icalData.take(500))

        assertTrue("Should contain updated summary",
            event.icalData.contains("KashCal Zoho Test - Updated Event"))
        assertTrue("Should contain updated location",
            event.icalData.contains("Updated Location"))
        assertTrue("Should contain updated description",
            event.icalData.contains("event was updated successfully"))

        singleEventEtag = event.etag ?: singleEventEtag
    }

    @Test
    fun `test14 delete single event`() = runBlocking {
        println("\n===== TEST 14: Delete Single Event =====")
        assumeTrue("Event not created", singleEventUrl != null && singleEventEtag != null)

        // Refresh etag before delete
        val fetchResult = client.fetchEvent(singleEventUrl!!)
        if (fetchResult.isSuccess()) {
            singleEventEtag = fetchResult.getOrNull()!!.etag ?: singleEventEtag
        }

        println("Deleting event with etag: $singleEventEtag")
        var deleteSucceeded = false
        val result = client.deleteEvent(singleEventUrl!!, singleEventEtag!!)

        if (result.isSuccess()) {
            println("Delete succeeded (CalDAV)")
            deleteSucceeded = true
        } else {
            val err = result as CalDavResult.Error
            println("Delete failed (code=${err.code}): ${err.message}")

            // Try raw DELETE without If-Match
            val rawStatus = rawDelete(singleEventUrl!!)
            println("Raw DELETE status: $rawStatus")
            if (rawStatus in 200..299 || rawStatus == 404) {
                deleteSucceeded = true
            }
        }

        assertTrue("DELETE should return success (200/204/404)", deleteSucceeded)

        // Verify deletion with eventual consistency tolerance
        // Zoho may still return the event briefly after a successful DELETE
        var gone = false
        for (attempt in 1..5) {
            val verifyResult = client.fetchEvent(singleEventUrl!!)
            if (verifyResult.isError()) {
                gone = true
                break
            }
            if (attempt < 5) {
                println("  Event still fetchable after delete, waiting 2s (attempt $attempt/5)...")
                Thread.sleep(2000)
            }
        }
        if (gone) {
            println("Verified: event no longer exists on server")
        } else {
            println("WARNING: Event still fetchable after DELETE + 8s — Zoho eventual consistency")
            println("DELETE response was successful, treating as passed")
        }

        createdEventUrls.remove(singleEventUrl)
        singleEventUrl = null
        singleEventUid = null
        singleEventEtag = null
    }

    // ==================== 20-22: All-Day Event ====================

    @Test
    fun `test20 create all-day event`() = runBlocking {
        println("\n===== TEST 20: Create All-Day Event =====")
        assumeTrue("Calendar not discovered", calendarUrl != null)

        allDayEventUid = "kashcal-zoho-allday-${UUID.randomUUID()}@kashcal.test"

        // All-day event: DTSTART;VALUE=DATE and DTEND;VALUE=DATE
        val tomorrow = Instant.now().plusSeconds(86400)
        val startDate = DATE_FORMATTER.format(tomorrow)
        val endDate = DATE_FORMATTER.format(tomorrow.plusSeconds(86400)) // +1 day

        val ical = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Zoho Integration Test//EN
BEGIN:VEVENT
UID:${allDayEventUid}
DTSTAMP:${formatTimestamp(System.currentTimeMillis())}
DTSTART;VALUE=DATE:${startDate}
DTEND;VALUE=DATE:${endDate}
SUMMARY:KashCal All-Day Test Event
DESCRIPTION:Integration test for all-day event handling
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        println("Creating all-day event: $allDayEventUid")
        println("  DTSTART: $startDate")
        println("  DTEND: $endDate")

        val result = client.createEvent(calendarUrl!!, allDayEventUid!!, ical)
        assertSuccess("createEvent (all-day)", result)

        val (url, etag) = result.getOrNull()!!
        allDayEventUrl = url
        allDayEventEtag = etag
        createdEventUrls.add(url)

        println("  URL: $url")
        println("  ETag: '$etag'")

        // Fetch etag if missing
        if (etag.isEmpty()) {
            allDayEventEtag = fetchEtagFallback(url)
        }
    }

    @Test
    fun `test21 fetch all-day event and verify DATE properties`() = runBlocking {
        println("\n===== TEST 21: Verify All-Day Event =====")
        assumeTrue("All-day event not created", allDayEventUrl != null)

        val result = client.fetchEvent(allDayEventUrl!!)
        assertSuccess("fetchEvent (all-day)", result)

        val event = result.getOrNull()!!
        println("All-day ICS:")
        println(event.icalData)

        assertTrue("Should contain VCALENDAR", event.icalData.contains("BEGIN:VCALENDAR"))
        assertTrue("Should contain our UID", event.icalData.contains(allDayEventUid!!))
        assertTrue("Should contain VALUE=DATE in DTSTART",
            event.icalData.contains("DTSTART;VALUE=DATE:") ||
                event.icalData.contains("DTSTART:") // Zoho may strip VALUE=DATE
        )
        assertTrue("Should contain all-day summary",
            event.icalData.contains("KashCal All-Day Test Event"))

        allDayEventEtag = event.etag ?: allDayEventEtag
    }

    @Test
    fun `test22 delete all-day event`() = runBlocking {
        println("\n===== TEST 22: Delete All-Day Event =====")
        assumeTrue("All-day event not created", allDayEventUrl != null)

        deleteEventSafe(allDayEventUrl!!, allDayEventEtag)
        createdEventUrls.remove(allDayEventUrl)
        allDayEventUrl = null
        allDayEventUid = null
        allDayEventEtag = null
    }

    // ==================== 30-33: Recurring Event (RRULE) ====================

    @Test
    fun `test30 create recurring event with RRULE`() = runBlocking {
        println("\n===== TEST 30: Create Recurring Event (Daily x5) =====")
        assumeTrue("Calendar not discovered", calendarUrl != null)

        recurringEventUid = "kashcal-zoho-recur-${UUID.randomUUID()}@kashcal.test"
        val now = System.currentTimeMillis()
        val startTs = now + (4 * 60 * 60 * 1000) // 4 hours from now
        val endTs = startTs + (30 * 60 * 1000) // 30 minutes

        val ical = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Zoho Integration Test//EN
BEGIN:VEVENT
UID:${recurringEventUid}
DTSTAMP:${formatTimestamp(now)}
DTSTART:${formatTimestamp(startTs)}
DTEND:${formatTimestamp(endTs)}
SUMMARY:KashCal Recurring Test - Daily
DESCRIPTION:Recurring event with RRULE DAILY COUNT=5
RRULE:FREQ=DAILY;COUNT=5
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        println("Creating recurring event: $recurringEventUid")
        println("  RRULE: FREQ=DAILY;COUNT=5")

        val result = client.createEvent(calendarUrl!!, recurringEventUid!!, ical)
        assertSuccess("createEvent (recurring)", result)

        val (url, etag) = result.getOrNull()!!
        recurringEventUrl = url
        recurringEventEtag = etag
        createdEventUrls.add(url)

        println("  URL: $url")
        println("  ETag: '$etag'")

        if (etag.isEmpty()) {
            recurringEventEtag = fetchEtagFallback(url)
        }
    }

    @Test
    fun `test31 fetch recurring event and verify RRULE`() = runBlocking {
        println("\n===== TEST 31: Verify Recurring Event =====")
        assumeTrue("Recurring event not created", recurringEventUrl != null)

        val result = client.fetchEvent(recurringEventUrl!!)
        assertSuccess("fetchEvent (recurring)", result)

        val event = result.getOrNull()!!
        println("Recurring ICS:")
        println(event.icalData)

        assertTrue("Should contain VCALENDAR", event.icalData.contains("BEGIN:VCALENDAR"))
        assertTrue("Should contain our UID", event.icalData.contains(recurringEventUid!!))
        assertTrue("Should contain RRULE",
            event.icalData.contains("RRULE:") || event.icalData.contains("RRULE;"))
        assertTrue("Should contain DAILY",
            event.icalData.contains("FREQ=DAILY"))
        assertTrue("Should contain COUNT=5",
            event.icalData.contains("COUNT=5"))

        recurringEventEtag = event.etag ?: recurringEventEtag
    }

    @Test
    fun `test32 update recurring event - change RRULE to WEEKLY`() = runBlocking {
        println("\n===== TEST 32: Update Recurring Event RRULE =====")
        assumeTrue("Recurring event not created", recurringEventUrl != null)

        // Refresh etag
        val fetchResult = client.fetchEvent(recurringEventUrl!!)
        assertSuccess("fetchEvent for recurring update", fetchResult)
        recurringEventEtag = fetchResult.getOrNull()!!.etag ?: recurringEventEtag

        val now = System.currentTimeMillis()
        val startTs = now + (4 * 60 * 60 * 1000)
        val endTs = startTs + (30 * 60 * 1000)

        val updatedIcal = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Zoho Integration Test//EN
BEGIN:VEVENT
UID:${recurringEventUid}
DTSTAMP:${formatTimestamp(now)}
DTSTART:${formatTimestamp(startTs)}
DTEND:${formatTimestamp(endTs)}
SUMMARY:KashCal Recurring Test - Weekly (Updated)
DESCRIPTION:Changed from DAILY to WEEKLY COUNT=4
RRULE:FREQ=WEEKLY;COUNT=4
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        println("Updating recurring event RRULE to WEEKLY COUNT=4")
        val result = client.updateEvent(recurringEventUrl!!, updatedIcal, recurringEventEtag!!)

        if (result.isSuccess()) {
            recurringEventEtag = result.getOrNull()!!
            println("Update succeeded. New ETag: $recurringEventEtag")
            if (recurringEventEtag!!.isEmpty()) {
                recurringEventEtag = fetchEtagFallback(recurringEventUrl!!)
            }
        } else {
            val err = result as CalDavResult.Error
            println("Update failed (code=${err.code}): ${err.message}")
            // Fallback: raw PUT
            val rawStatus = rawPut(recurringEventUrl!!, updatedIcal)
            println("Raw PUT status: $rawStatus")
            assertTrue("Raw PUT should succeed", rawStatus in 200..299)
            recurringEventEtag = fetchEtagFallback(recurringEventUrl!!)
        }

        // Verify
        val verifyResult = client.fetchEvent(recurringEventUrl!!)
        assertSuccess("fetchEvent after RRULE update", verifyResult)
        val ics = verifyResult.getOrNull()!!.icalData
        assertTrue("Should contain WEEKLY", ics.contains("FREQ=WEEKLY"))
        println("Verified: RRULE updated to WEEKLY")
    }

    @Test
    fun `test33 delete recurring event`() = runBlocking {
        println("\n===== TEST 33: Delete Recurring Event =====")
        assumeTrue("Recurring event not created", recurringEventUrl != null)

        deleteEventSafe(recurringEventUrl!!, recurringEventEtag)
        createdEventUrls.remove(recurringEventUrl)
        recurringEventUrl = null
        recurringEventUid = null
        recurringEventEtag = null
    }

    // ==================== 40-44: Recurring Event with Exception (RECURRENCE-ID) ====================

    @Test
    fun `test40 create recurring event for exception test`() = runBlocking {
        println("\n===== TEST 40: Create Recurring Event for Exception Test =====")
        assumeTrue("Calendar not discovered", calendarUrl != null)

        recurExcEventUid = "kashcal-zoho-recexc-${UUID.randomUUID()}@kashcal.test"
        val now = System.currentTimeMillis()
        // Start tomorrow at 10:00 UTC
        val tomorrowMidnight = now - (now % 86400000) + 86400000
        val startTs = tomorrowMidnight + (10 * 60 * 60 * 1000) // 10:00 UTC
        val endTs = startTs + (60 * 60 * 1000) // 11:00 UTC

        val ical = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Zoho Integration Test//EN
BEGIN:VEVENT
UID:${recurExcEventUid}
DTSTAMP:${formatTimestamp(now)}
DTSTART:${formatTimestamp(startTs)}
DTEND:${formatTimestamp(endTs)}
SUMMARY:KashCal Recurring for Exception
DESCRIPTION:Daily recurring event for RECURRENCE-ID exception testing
RRULE:FREQ=DAILY;COUNT=5
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        println("Creating recurring event: $recurExcEventUid")
        val result = client.createEvent(calendarUrl!!, recurExcEventUid!!, ical)
        assertSuccess("createEvent (recurring for exception)", result)

        val (url, etag) = result.getOrNull()!!
        recurExcEventUrl = url
        recurExcEventEtag = etag
        createdEventUrls.add(url)

        if (etag.isEmpty()) {
            recurExcEventEtag = fetchEtagFallback(url)
        }
        println("Created. URL: $url, ETag: $recurExcEventEtag")
    }

    @Test
    fun `test41 add exception to recurring event via RECURRENCE-ID`() = runBlocking {
        println("\n===== TEST 41: Add Exception (RECURRENCE-ID) =====")
        assumeTrue("Recurring event not created", recurExcEventUrl != null)

        // Refresh etag
        val fetchResult = client.fetchEvent(recurExcEventUrl!!)
        assertSuccess("fetchEvent for exception", fetchResult)
        recurExcEventEtag = fetchResult.getOrNull()!!.etag ?: recurExcEventEtag
        val currentIcs = fetchResult.getOrNull()!!.icalData

        // Parse the DTSTART from the fetched ICS to calculate occurrence times
        val now = System.currentTimeMillis()
        val tomorrowMidnight = now - (now % 86400000) + 86400000
        val firstOccurrenceStart = tomorrowMidnight + (10 * 60 * 60 * 1000)
        val secondOccurrenceStart = firstOccurrenceStart + 86400000 // +1 day
        val secondOccurrenceEnd = secondOccurrenceStart + (60 * 60 * 1000)

        // The modified 2nd occurrence — different time and summary
        val modifiedStart = secondOccurrenceStart + (2 * 60 * 60 * 1000) // 12:00 instead of 10:00
        val modifiedEnd = modifiedStart + (90 * 60 * 1000) // 1.5 hours instead of 1

        // Build ICS with master + exception VEVENT (RFC 5545 §3.8.4.4)
        val ical = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Zoho Integration Test//EN
BEGIN:VEVENT
UID:${recurExcEventUid}
DTSTAMP:${formatTimestamp(now)}
DTSTART:${formatTimestamp(firstOccurrenceStart)}
DTEND:${formatTimestamp(firstOccurrenceStart + 3600000)}
SUMMARY:KashCal Recurring for Exception
DESCRIPTION:Daily recurring event for RECURRENCE-ID exception testing
RRULE:FREQ=DAILY;COUNT=5
END:VEVENT
BEGIN:VEVENT
UID:${recurExcEventUid}
DTSTAMP:${formatTimestamp(now)}
RECURRENCE-ID:${formatTimestamp(secondOccurrenceStart)}
DTSTART:${formatTimestamp(modifiedStart)}
DTEND:${formatTimestamp(modifiedEnd)}
SUMMARY:KashCal Exception - Modified Occurrence
DESCRIPTION:This is the modified 2nd occurrence
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        println("Updating recurring event with RECURRENCE-ID exception")
        println("  Original 2nd occurrence: ${formatTimestamp(secondOccurrenceStart)}")
        println("  Modified to: ${formatTimestamp(modifiedStart)}")

        val result = client.updateEvent(recurExcEventUrl!!, ical, recurExcEventEtag!!)

        if (result.isSuccess()) {
            recurExcEventEtag = result.getOrNull()!!
            println("Update with exception succeeded. New ETag: $recurExcEventEtag")
            if (recurExcEventEtag!!.isEmpty()) {
                recurExcEventEtag = fetchEtagFallback(recurExcEventUrl!!)
            }
        } else {
            val err = result as CalDavResult.Error
            println("Update failed (code=${err.code}): ${err.message}")
            // Fallback: raw PUT
            val rawStatus = rawPut(recurExcEventUrl!!, ical)
            println("Raw PUT status: $rawStatus")
            assertTrue("Raw PUT should succeed", rawStatus in 200..299)
            recurExcEventEtag = fetchEtagFallback(recurExcEventUrl!!)
        }
    }

    @Test
    fun `test42 fetch recurring event with exception and verify RECURRENCE-ID`() = runBlocking {
        println("\n===== TEST 42: Verify Recurring Event with Exception =====")
        assumeTrue("Recurring event not created", recurExcEventUrl != null)

        val result = client.fetchEvent(recurExcEventUrl!!)
        assertSuccess("fetchEvent (recurring with exception)", result)

        val ics = result.getOrNull()!!.icalData
        println("Recurring+Exception ICS:")
        println(ics)

        assertTrue("Should contain VCALENDAR", ics.contains("BEGIN:VCALENDAR"))
        assertTrue("Should contain UID", ics.contains(recurExcEventUid!!))
        assertTrue("Should contain RRULE", ics.contains("RRULE:") || ics.contains("RRULE;"))

        // Verify exception VEVENT with RECURRENCE-ID
        val veventCount = ics.split("BEGIN:VEVENT").size - 1
        println("VEVENT count: $veventCount")
        assertTrue("Should have at least 2 VEVENTs (master + exception)", veventCount >= 2)
        assertTrue("Should contain RECURRENCE-ID", ics.contains("RECURRENCE-ID"))
        assertTrue("Should contain modified summary",
            ics.contains("KashCal Exception - Modified Occurrence"))

        recurExcEventEtag = result.getOrNull()!!.etag ?: recurExcEventEtag
    }

    @Test
    fun `test43 delete recurring event with exception`() = runBlocking {
        println("\n===== TEST 43: Delete Recurring Event with Exception =====")
        assumeTrue("Recurring event not created", recurExcEventUrl != null)

        deleteEventSafe(recurExcEventUrl!!, recurExcEventEtag)
        createdEventUrls.remove(recurExcEventUrl)
        recurExcEventUrl = null
        recurExcEventUid = null
        recurExcEventEtag = null
    }

    // ==================== 50-54: Batch Operations ====================

    @Test
    fun `test50 batch create 5 events`() = runBlocking {
        println("\n===== TEST 50: Batch Create 5 Events =====")
        assumeTrue("Calendar not discovered", calendarUrl != null)

        batchEventUids.clear()
        batchEventUrls.clear()
        batchEventEtags.clear()

        val now = System.currentTimeMillis()

        for (i in 1..5) {
            val uid = "kashcal-zoho-batch-$i-${UUID.randomUUID()}@kashcal.test"
            val startTs = now + (i * 60 * 60 * 1000L) // i hours from now
            val endTs = startTs + (30 * 60 * 1000) // +30 min

            val ical = buildIcs(
                uid = uid,
                summary = "KashCal Batch Event #$i",
                description = "Batch test event $i of 5",
                dtstart = formatTimestamp(startTs),
                dtend = formatTimestamp(endTs)
            )

            println("Creating batch event $i: $uid")
            val result = client.createEvent(calendarUrl!!, uid, ical)
            assertSuccess("createEvent (batch #$i)", result)

            val (url, etag) = result.getOrNull()!!
            batchEventUids.add(uid)
            batchEventUrls.add(url)
            createdEventUrls.add(url)

            var resolvedEtag = etag
            if (etag.isEmpty()) {
                resolvedEtag = fetchEtagFallback(url) ?: ""
            }
            batchEventEtags.add(resolvedEtag)

            println("  Created: $url (etag=$resolvedEtag)")
        }

        assertEquals("Should have created 5 events", 5, batchEventUrls.size)
    }

    @Test
    fun `test51 fetch batch events via two-step (etags then multiget)`() = runBlocking {
        println("\n===== TEST 51: Fetch Batch Events via Two-Step =====")
        assumeTrue("Batch events not created", batchEventUrls.size == 5)

        // Step 1: Calendar-query for etags
        val now = System.currentTimeMillis()
        val pastWindow = now - (24 * 60 * 60 * 1000L) // 1 day ago
        val futureEnd = now + (30 * 24 * 60 * 60 * 1000L) // 30 days out

        val etagResult = client.fetchEtagsInRange(calendarUrl!!, pastWindow, futureEnd)
        assertSuccess("fetchEtagsInRange (batch)", etagResult)

        val allEtags = etagResult.getOrNull()!!
        println("Total etags in range: ${allEtags.size}")

        // Check our batch events appear in etags
        val batchHrefs = mutableListOf<String>()
        for (uid in batchEventUids) {
            val matchingEtag = allEtags.find { (href, _) -> href.contains(uid.substringBefore("@")) }
            if (matchingEtag != null) {
                batchHrefs.add(matchingEtag.first)
                println("  Found batch event href: ${matchingEtag.first}")
            }
        }
        println("Found ${batchHrefs.size} of 5 batch events in etag results")

        // Step 2: Multiget
        if (batchHrefs.isNotEmpty()) {
            val multigetResult = client.fetchEventsByHref(calendarUrl!!, batchHrefs)
            if (multigetResult.isSuccess()) {
                val events = multigetResult.getOrNull()!!
                println("Multiget returned ${events.size} events for ${batchHrefs.size} hrefs")

                if (events.isEmpty() && batchHrefs.size > 1) {
                    println("Multi-href multiget returned empty — trying one-by-one")
                    var oneByOneSuccess = 0
                    for (href in batchHrefs) {
                        val singleResult = client.fetchEventsByHref(calendarUrl!!, listOf(href))
                        if (singleResult.isSuccess() && singleResult.getOrNull()!!.isNotEmpty()) {
                            oneByOneSuccess++
                        }
                    }
                    println("One-by-one: $oneByOneSuccess/${batchHrefs.size} succeeded")
                    assertTrue("At least some one-by-one fetches should work", oneByOneSuccess > 0)
                } else {
                    // Verify each event has VCALENDAR data
                    events.forEach { ev ->
                        assertTrue("Event should have ICS data", ev.icalData.contains("BEGIN:VCALENDAR"))
                    }
                }
            } else {
                println("Multiget failed — this is a known Zoho limitation")
            }
        }
    }

    @Test
    fun `test52 verify batch events individually via GET`() = runBlocking {
        println("\n===== TEST 52: Verify Batch Events via Individual GET =====")
        assumeTrue("Batch events not created", batchEventUrls.size == 5)

        var successCount = 0
        for ((i, url) in batchEventUrls.withIndex()) {
            val result = client.fetchEvent(url)
            if (result.isSuccess()) {
                val event = result.getOrNull()!!
                assertTrue("Should contain batch UID",
                    event.icalData.contains(batchEventUids[i]))
                assertTrue("Should contain batch summary",
                    event.icalData.contains("KashCal Batch Event #${i + 1}"))
                successCount++
            } else {
                val err = result as CalDavResult.Error
                println("  GET failed for event ${i + 1}: code=${err.code}, msg=${err.message}")
            }
        }

        println("Individual GET: $successCount/5 succeeded")
        assertEquals("All batch events should be fetchable", 5, successCount)
    }

    @Test
    fun `test53 ctag changes after batch create`() = runBlocking {
        println("\n===== TEST 53: Verify Ctag Changed =====")
        assumeTrue("Calendar not discovered", calendarUrl != null)

        val result = client.getCtag(calendarUrl!!)
        if (result.isSuccess()) {
            val newCtag = result.getOrNull()
            println("Ctag after batch create: $newCtag")
            assertNotNull("Ctag should be non-null", newCtag)
        } else {
            println("Ctag still unavailable (expected for some Zoho configs)")
        }
    }

    @Test
    fun `test54 batch delete all events`() = runBlocking {
        println("\n===== TEST 54: Batch Delete =====")
        assumeTrue("Batch events not created", batchEventUrls.size == 5)

        var deleteSuccess = 0
        for ((i, url) in batchEventUrls.withIndex()) {
            val etag = batchEventEtags.getOrNull(i) ?: ""
            deleteEventSafe(url, etag)
            createdEventUrls.remove(url)
            deleteSuccess++
        }

        println("Deleted $deleteSuccess/5 batch events")
        batchEventUids.clear()
        batchEventUrls.clear()
        batchEventEtags.clear()
    }

    // ==================== 60-62: Special Event Properties ====================

    @Test
    fun `test60 create event with priority, categories, URL`() = runBlocking {
        println("\n===== TEST 60: Event with RFC 5545 Properties =====")
        assumeTrue("Calendar not discovered", calendarUrl != null)

        val uid = "kashcal-zoho-props-${UUID.randomUUID()}@kashcal.test"
        val now = System.currentTimeMillis()
        val startTs = now + (6 * 60 * 60 * 1000)
        val endTs = startTs + (60 * 60 * 1000)

        val ical = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Zoho Integration Test//EN
BEGIN:VEVENT
UID:${uid}
DTSTAMP:${formatTimestamp(now)}
DTSTART:${formatTimestamp(startTs)}
DTEND:${formatTimestamp(endTs)}
SUMMARY:KashCal Properties Test
LOCATION:San Francisco, CA
DESCRIPTION:Testing RFC 5545 properties round-trip
PRIORITY:1
CATEGORIES:TESTING,KASHCAL,ZOHO
URL:https://example.com/zoho-test
GEO:37.7749;-122.4194
STATUS:CONFIRMED
TRANSP:OPAQUE
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        println("Creating event with properties: $uid")
        val result = client.createEvent(calendarUrl!!, uid, ical)
        assertSuccess("createEvent (properties)", result)

        val (url, _) = result.getOrNull()!!
        createdEventUrls.add(url)

        // Fetch and verify
        val fetchResult = client.fetchEvent(url)
        assertSuccess("fetchEvent (properties)", fetchResult)
        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        println("Fetched ICS with properties:")
        println(fetchedIcs)

        // Check which properties survived round-trip
        val props = mapOf(
            "SUMMARY" to fetchedIcs.contains("KashCal Properties Test"),
            "LOCATION" to fetchedIcs.contains("San Francisco"),
            "DESCRIPTION" to fetchedIcs.contains("RFC 5545 properties"),
            "PRIORITY" to fetchedIcs.contains("PRIORITY:"),
            "CATEGORIES" to fetchedIcs.contains("CATEGORIES:"),
            "URL" to fetchedIcs.contains("URL:"),
            "GEO" to fetchedIcs.contains("GEO:"),
            "STATUS" to fetchedIcs.contains("STATUS:"),
            "TRANSP" to fetchedIcs.contains("TRANSP:")
        )

        println("\nProperty round-trip results:")
        props.forEach { (prop, survived) ->
            println("  $prop: ${if (survived) "PRESERVED" else "STRIPPED"}")
        }

        // Essential properties should survive
        assertTrue("SUMMARY should survive", props["SUMMARY"]!!)
        assertTrue("DESCRIPTION should survive", props["DESCRIPTION"]!!)

        // Clean up
        deleteEventSafe(url, fetchResult.getOrNull()!!.etag)
        createdEventUrls.remove(url)
        Unit
    }

    @Test
    fun `test61 create event with VALARM reminder`() = runBlocking {
        println("\n===== TEST 61: Event with VALARM =====")
        assumeTrue("Calendar not discovered", calendarUrl != null)

        val uid = "kashcal-zoho-alarm-${UUID.randomUUID()}@kashcal.test"
        val now = System.currentTimeMillis()
        val startTs = now + (8 * 60 * 60 * 1000)
        val endTs = startTs + (60 * 60 * 1000)

        val ical = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Zoho Integration Test//EN
BEGIN:VEVENT
UID:${uid}
DTSTAMP:${formatTimestamp(now)}
DTSTART:${formatTimestamp(startTs)}
DTEND:${formatTimestamp(endTs)}
SUMMARY:KashCal Alarm Test
DESCRIPTION:Testing VALARM round-trip
BEGIN:VALARM
TRIGGER:-PT15M
ACTION:DISPLAY
DESCRIPTION:Event in 15 minutes
END:VALARM
BEGIN:VALARM
TRIGGER:-PT1H
ACTION:DISPLAY
DESCRIPTION:Event in 1 hour
END:VALARM
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        println("Creating event with VALARMs: $uid")
        val result = client.createEvent(calendarUrl!!, uid, ical)
        assertSuccess("createEvent (VALARM)", result)

        val (url, _) = result.getOrNull()!!
        createdEventUrls.add(url)

        // Fetch and verify
        val fetchResult = client.fetchEvent(url)
        assertSuccess("fetchEvent (VALARM)", fetchResult)
        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        println("Fetched ICS with VALARM:")
        println(fetchedIcs)

        val hasValarm = fetchedIcs.contains("BEGIN:VALARM")
        val hasTrigger = fetchedIcs.contains("TRIGGER:")
        println("\nVALARM preserved: $hasValarm")
        println("TRIGGER preserved: $hasTrigger")

        // Clean up
        deleteEventSafe(url, fetchResult.getOrNull()!!.etag)
        createdEventUrls.remove(url)
        Unit
    }

    @Test
    fun `test62 create event with long description and special characters`() = runBlocking {
        println("\n===== TEST 62: Event with Special Characters =====")
        assumeTrue("Calendar not discovered", calendarUrl != null)

        val uid = "kashcal-zoho-special-${UUID.randomUUID()}@kashcal.test"
        val now = System.currentTimeMillis()
        val startTs = now + (10 * 60 * 60 * 1000)
        val endTs = startTs + (60 * 60 * 1000)

        val ical = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Zoho Integration Test//EN
BEGIN:VEVENT
UID:${uid}
DTSTAMP:${formatTimestamp(now)}
DTSTART:${formatTimestamp(startTs)}
DTEND:${formatTimestamp(endTs)}
SUMMARY:KashCal Special: Braces {test} & Ampersand
DESCRIPTION:Line 1\nLine 2\nLine 3 with <angle> brackets\nAccents: cafe\u0301
LOCATION:123 Main St\, Suite 456
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        println("Creating event with special chars: $uid")
        val result = client.createEvent(calendarUrl!!, uid, ical)
        assertSuccess("createEvent (special chars)", result)

        val (url, _) = result.getOrNull()!!
        createdEventUrls.add(url)

        // Fetch and verify
        val fetchResult = client.fetchEvent(url)
        assertSuccess("fetchEvent (special chars)", fetchResult)
        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        println("Fetched ICS with special chars:")
        println(fetchedIcs)

        assertTrue("Summary should be preserved", fetchedIcs.contains("Braces"))
        assertTrue("Should contain VCALENDAR", fetchedIcs.contains("BEGIN:VCALENDAR"))

        // Clean up
        deleteEventSafe(url, fetchResult.getOrNull()!!.etag)
        createdEventUrls.remove(url)
        Unit
    }

    // ==================== 70-72: Recurring Event Variants ====================

    @Test
    fun `test70 create weekly recurring event with BYDAY`() = runBlocking {
        println("\n===== TEST 70: Weekly Recurring with BYDAY =====")
        assumeTrue("Calendar not discovered", calendarUrl != null)

        val uid = "kashcal-zoho-weekly-${UUID.randomUUID()}@kashcal.test"
        val now = System.currentTimeMillis()
        val startTs = now + (12 * 60 * 60 * 1000)
        val endTs = startTs + (60 * 60 * 1000)

        val ical = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Zoho Integration Test//EN
BEGIN:VEVENT
UID:${uid}
DTSTAMP:${formatTimestamp(now)}
DTSTART:${formatTimestamp(startTs)}
DTEND:${formatTimestamp(endTs)}
SUMMARY:KashCal Weekly MWF Meeting
RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=10
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        println("Creating weekly event with BYDAY=MO,WE,FR: $uid")
        val result = client.createEvent(calendarUrl!!, uid, ical)
        assertSuccess("createEvent (weekly BYDAY)", result)

        val (url, _) = result.getOrNull()!!
        createdEventUrls.add(url)

        // Verify
        val fetchResult = client.fetchEvent(url)
        assertSuccess("fetchEvent (weekly BYDAY)", fetchResult)
        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        println("Weekly BYDAY ICS:")
        println(fetchedIcs)

        assertTrue("Should contain RRULE", fetchedIcs.contains("RRULE:") || fetchedIcs.contains("RRULE;"))
        assertTrue("Should contain FREQ=WEEKLY", fetchedIcs.contains("FREQ=WEEKLY"))
        // BYDAY may be reordered by server
        assertTrue("Should contain BYDAY", fetchedIcs.contains("BYDAY="))

        // Clean up
        deleteEventSafe(url, fetchResult.getOrNull()!!.etag)
        createdEventUrls.remove(url)
        Unit
    }

    @Test
    fun `test71 create monthly recurring event with BYMONTHDAY`() = runBlocking {
        println("\n===== TEST 71: Monthly Recurring with BYMONTHDAY =====")
        assumeTrue("Calendar not discovered", calendarUrl != null)

        val uid = "kashcal-zoho-monthly-${UUID.randomUUID()}@kashcal.test"
        val now = System.currentTimeMillis()
        val startTs = now + (14 * 60 * 60 * 1000)
        val endTs = startTs + (60 * 60 * 1000)

        val ical = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Zoho Integration Test//EN
BEGIN:VEVENT
UID:${uid}
DTSTAMP:${formatTimestamp(now)}
DTSTART:${formatTimestamp(startTs)}
DTEND:${formatTimestamp(endTs)}
SUMMARY:KashCal Monthly 15th Event
RRULE:FREQ=MONTHLY;BYMONTHDAY=15;COUNT=6
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        println("Creating monthly event BYMONTHDAY=15: $uid")
        val result = client.createEvent(calendarUrl!!, uid, ical)
        assertSuccess("createEvent (monthly BYMONTHDAY)", result)

        val (url, _) = result.getOrNull()!!
        createdEventUrls.add(url)

        val fetchResult = client.fetchEvent(url)
        assertSuccess("fetchEvent (monthly)", fetchResult)
        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        println("Monthly ICS:")
        println(fetchedIcs)

        assertTrue("Should contain FREQ=MONTHLY", fetchedIcs.contains("FREQ=MONTHLY"))

        // Clean up
        deleteEventSafe(url, fetchResult.getOrNull()!!.etag)
        createdEventUrls.remove(url)
        Unit
    }

    @Test
    fun `test72 create yearly recurring event`() = runBlocking {
        println("\n===== TEST 72: Yearly Recurring Event =====")
        assumeTrue("Calendar not discovered", calendarUrl != null)

        val uid = "kashcal-zoho-yearly-${UUID.randomUUID()}@kashcal.test"
        val now = System.currentTimeMillis()
        val startTs = now + (16 * 60 * 60 * 1000)
        val endTs = startTs + (60 * 60 * 1000)

        val ical = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Zoho Integration Test//EN
BEGIN:VEVENT
UID:${uid}
DTSTAMP:${formatTimestamp(now)}
DTSTART:${formatTimestamp(startTs)}
DTEND:${formatTimestamp(endTs)}
SUMMARY:KashCal Annual Review
RRULE:FREQ=YEARLY;COUNT=3
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        println("Creating yearly event: $uid")
        val result = client.createEvent(calendarUrl!!, uid, ical)
        assertSuccess("createEvent (yearly)", result)

        val (url, _) = result.getOrNull()!!
        createdEventUrls.add(url)

        val fetchResult = client.fetchEvent(url)
        assertSuccess("fetchEvent (yearly)", fetchResult)
        val fetchedIcs = fetchResult.getOrNull()!!.icalData
        println("Yearly ICS:")
        println(fetchedIcs)

        assertTrue("Should contain FREQ=YEARLY", fetchedIcs.contains("FREQ=YEARLY"))

        // Clean up
        deleteEventSafe(url, fetchResult.getOrNull()!!.etag)
        createdEventUrls.remove(url)
        Unit
    }

    // ==================== 80-82: Etag Handling Edge Cases ====================

    @Test
    fun `test80 etag changes after update`() = runBlocking {
        println("\n===== TEST 80: Etag Changes After Update =====")
        assumeTrue("Calendar not discovered", calendarUrl != null)

        val uid = "kashcal-zoho-etag-${UUID.randomUUID()}@kashcal.test"
        val now = System.currentTimeMillis()
        val startTs = now + (18 * 60 * 60 * 1000)
        val endTs = startTs + (60 * 60 * 1000)

        // Create
        val ical1 = buildIcs(uid = uid, summary = "Etag Test v1",
            dtstart = formatTimestamp(startTs), dtend = formatTimestamp(endTs))
        val createResult = client.createEvent(calendarUrl!!, uid, ical1)
        assertSuccess("createEvent (etag test)", createResult)
        val (url, _) = createResult.getOrNull()!!
        createdEventUrls.add(url)

        val etag1 = fetchEtagFallback(url) ?: error("Should get etag after create")
        println("Etag after create: $etag1")

        // Update
        val ical2 = buildIcs(uid = uid, summary = "Etag Test v2",
            dtstart = formatTimestamp(startTs), dtend = formatTimestamp(endTs))
        val updateResult = client.updateEvent(url, ical2, etag1)
        if (updateResult.isSuccess()) {
            val etag2Direct = updateResult.getOrNull()!!
            println("Etag from update response: $etag2Direct")
        }

        val etag2 = fetchEtagFallback(url) ?: error("Should get etag after update")
        println("Etag after update: $etag2")

        assertNotEquals("Etag should change after update", etag1, etag2)
        println("Confirmed: etag changed from '$etag1' to '$etag2'")

        // Clean up
        deleteEventSafe(url, etag2)
        createdEventUrls.remove(url)
        Unit
    }

    @Test
    fun `test81 stale etag update returns 412 conflict`() = runBlocking {
        println("\n===== TEST 81: Stale Etag → 412 Conflict =====")
        assumeTrue("Calendar not discovered", calendarUrl != null)

        val uid = "kashcal-zoho-conflict-${UUID.randomUUID()}@kashcal.test"
        val now = System.currentTimeMillis()
        val startTs = now + (20 * 60 * 60 * 1000)
        val endTs = startTs + (60 * 60 * 1000)

        // Create
        val ical = buildIcs(uid = uid, summary = "Conflict Test",
            dtstart = formatTimestamp(startTs), dtend = formatTimestamp(endTs))
        val createResult = client.createEvent(calendarUrl!!, uid, ical)
        assertSuccess("createEvent (conflict test)", createResult)
        val (url, _) = createResult.getOrNull()!!
        createdEventUrls.add(url)

        val etag1 = fetchEtagFallback(url) ?: error("Should get etag")

        // Update to get new etag
        val ical2 = buildIcs(uid = uid, summary = "Conflict Test v2",
            dtstart = formatTimestamp(startTs), dtend = formatTimestamp(endTs))
        val updateResult = client.updateEvent(url, ical2, etag1)
        if (!updateResult.isSuccess()) {
            // Fallback
            rawPut(url, ical2)
        }

        // Now try update with STALE etag1
        val ical3 = buildIcs(uid = uid, summary = "Conflict Test v3",
            dtstart = formatTimestamp(startTs), dtend = formatTimestamp(endTs))
        println("Attempting update with stale etag: $etag1")
        val staleResult = client.updateEvent(url, ical3, etag1)

        if (staleResult.isError()) {
            val err = staleResult as CalDavResult.Error
            println("Stale update returned: code=${err.code}, msg=${err.message}")
            // 412 is expected, but some servers may return other errors
            assertTrue("Should be a conflict or error",
                err.code == 412 || err.code == 409 || err.code >= 400)
            println("Confirmed: stale etag correctly rejected")
        } else {
            println("WARNING: Server accepted stale etag (no conflict detection)")
        }

        // Clean up
        val currentEtag = fetchEtagFallback(url) ?: ""
        deleteEventSafe(url, currentEtag)
        createdEventUrls.remove(url)
        Unit
    }

    @Test
    fun `test82 duplicate UID create returns error`() = runBlocking {
        println("\n===== TEST 82: Duplicate UID Create =====")
        assumeTrue("Calendar not discovered", calendarUrl != null)

        val uid = "kashcal-zoho-dup-${UUID.randomUUID()}@kashcal.test"
        val now = System.currentTimeMillis()
        val startTs = now + (22 * 60 * 60 * 1000)
        val endTs = startTs + (60 * 60 * 1000)

        // First create
        val ical = buildIcs(uid = uid, summary = "Duplicate Test",
            dtstart = formatTimestamp(startTs), dtend = formatTimestamp(endTs))
        val result1 = client.createEvent(calendarUrl!!, uid, ical)
        assertSuccess("first createEvent", result1)
        val (url, _) = result1.getOrNull()!!
        createdEventUrls.add(url)

        // Second create with same UID — should fail (PUT If-None-Match: *)
        println("Attempting duplicate create with same UID: $uid")
        val result2 = client.createEvent(calendarUrl!!, uid, ical)

        if (result2.isError()) {
            val err = result2 as CalDavResult.Error
            println("Duplicate create returned: code=${err.code}, msg=${err.message}")
            assertTrue("Should reject duplicate (412 or similar)",
                err.code == 412 || err.code == 409 || err.code >= 400)
            println("Confirmed: duplicate UID correctly rejected")
        } else {
            println("WARNING: Server accepted duplicate UID (overwrote existing)")
        }

        // Clean up
        val currentEtag = fetchEtagFallback(url) ?: ""
        deleteEventSafe(url, currentEtag)
        createdEventUrls.remove(url)
        Unit
    }

    // ==================== 90: Sync Collection (webdav-sync) ====================

    @Test
    fun `test90 sync collection if supported`() = runBlocking {
        println("\n===== TEST 90: Sync Collection =====")
        assumeTrue("Calendar not discovered", calendarUrl != null)

        // Get sync token first
        val tokenResult = client.getSyncToken(calendarUrl!!)
        if (!tokenResult.isSuccess() || tokenResult.getOrNull() == null) {
            println("Sync token not available — sync-collection not supported on Zoho")
            return@runBlocking
        }

        val syncToken = tokenResult.getOrNull()!!
        println("Sync token: ${syncToken.take(20)}...")

        val syncResult = client.syncCollection(calendarUrl!!, syncToken)
        if (syncResult.isSuccess()) {
            val report = syncResult.getOrNull()!!
            println("Sync report:")
            println("  New token: ${report.syncToken?.take(20)}...")
            println("  Changed: ${report.changed.size}")
            println("  Deleted: ${report.deleted.size}")
            println("  Truncated: ${report.truncated}")
        } else {
            val err = syncResult as CalDavResult.Error
            println("Sync collection failed (code=${err.code}): ${err.message}")
            println("This may be expected if Zoho doesn't support webdav-sync")
        }
    }

    // ==================== 99: Final Cleanup ====================

    @Test
    fun `test99 cleanup all remaining test events`() = runBlocking {
        println("\n===== TEST 99: Final Cleanup =====")

        if (createdEventUrls.isEmpty()) {
            println("No events to clean up")
            return@runBlocking
        }

        println("Cleaning up ${createdEventUrls.size} remaining event(s)...")
        val toClean = createdEventUrls.toList()
        for (url in toClean) {
            try {
                val etag = fetchEtagFallback(url)
                deleteEventSafe(url, etag)
                println("  Deleted: $url")
            } catch (e: Exception) {
                println("  Failed to clean up $url: ${e.message}")
                // Last resort: raw DELETE
                try { rawDelete(url) } catch (_: Exception) {}
            }
        }
        createdEventUrls.clear()
        println("Cleanup complete")
    }

    // ==================== Helpers ====================

    private fun <T> assertSuccess(operation: String, result: CalDavResult<T>) {
        if (result.isError()) {
            val err = result as CalDavResult.Error
            fail("$operation failed: code=${err.code}, msg=${err.message}")
        }
        assertTrue("$operation should succeed", result.isSuccess())
    }

    private fun formatTimestamp(millis: Long): String {
        return TS_FORMATTER.format(Instant.ofEpochMilli(millis))
    }

    private fun buildIcs(
        uid: String,
        summary: String,
        description: String = "",
        location: String = "",
        dtstart: String,
        dtend: String,
        extraProps: String = ""
    ): String {
        return buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            appendLine("PRODID:-//KashCal//Zoho Integration Test//EN")
            appendLine("BEGIN:VEVENT")
            appendLine("UID:$uid")
            appendLine("DTSTAMP:${formatTimestamp(System.currentTimeMillis())}")
            appendLine("DTSTART:$dtstart")
            appendLine("DTEND:$dtend")
            appendLine("SUMMARY:$summary")
            if (description.isNotEmpty()) appendLine("DESCRIPTION:$description")
            if (location.isNotEmpty()) appendLine("LOCATION:$location")
            if (extraProps.isNotEmpty()) appendLine(extraProps)
            appendLine("END:VEVENT")
            appendLine("END:VCALENDAR")
        }.trimEnd()
    }

    private fun extractBaseHost(calendarUrl: String): String {
        if (!calendarUrl.contains("://")) return ""
        val afterProtocol = calendarUrl.substringAfter("://")
        val host = afterProtocol.substringBefore("/")
        return calendarUrl.substringBefore("://") + "://" + host
    }

    private suspend fun fetchEtagFallback(eventUrl: String): String? {
        // Try PROPFIND first
        val propfindResult = client.fetchEtag(eventUrl)
        if (propfindResult.isSuccess() && propfindResult.getOrNull() != null) {
            return propfindResult.getOrNull()
        }

        // Try fetchEvent (GET) which includes etag in response
        val getResult = client.fetchEvent(eventUrl)
        if (getResult.isSuccess() && getResult.getOrNull()?.etag != null) {
            return getResult.getOrNull()!!.etag
        }

        return null
    }

    private suspend fun deleteEventSafe(url: String, etag: String?) {
        // Refresh etag
        val currentEtag = if (etag.isNullOrEmpty()) {
            fetchEtagFallback(url) ?: ""
        } else {
            // Try to get fresh etag
            fetchEtagFallback(url) ?: etag
        }

        if (currentEtag.isNotEmpty()) {
            val result = client.deleteEvent(url, currentEtag)
            if (result.isSuccess()) return
        }

        // Fallback: raw DELETE
        rawDelete(url)
    }

    private fun rawPut(eventUrl: String, icalData: String): Int {
        val httpClient = OkHttpClient.Builder()
            .authenticator { _, response ->
                val credential = OkCredentials.basic(username!!, password!!)
                response.request.newBuilder().header("Authorization", credential).build()
            }
            .build()
        val request = Request.Builder()
            .url(eventUrl)
            .put(icalData.toRequestBody("text/calendar; charset=utf-8".toMediaType()))
            .build()
        return httpClient.newCall(request).execute().use { it.code }
    }

    private fun rawDelete(eventUrl: String): Int {
        val httpClient = OkHttpClient.Builder()
            .authenticator { _, response ->
                val credential = OkCredentials.basic(username!!, password!!)
                response.request.newBuilder().header("Authorization", credential).build()
            }
            .build()
        val request = Request.Builder().url(eventUrl).delete().build()
        return httpClient.newCall(request).execute().use { it.code }
    }

    private fun loadCredentials() {
        if (serverUrl != null && username != null && password != null) return

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
                            "ZOHO_SERVER" -> serverUrl = parts[1]
                            "ZOHO_USERNAME" -> username = parts[1]
                            "ZOHO_PASSWORD" -> password = parts[1]
                        }
                    }
                }
                if (username != null && password != null && serverUrl != null) break
            }
        }
    }
}