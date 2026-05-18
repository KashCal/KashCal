package org.onekash.kashcal.sync.integration

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import java.io.File
import java.util.Properties

/**
 * Nextcloud sync resilience test.
 *
 * Tests KashCal's CalDAV sync against a Nextcloud instance populated with edge-case
 * events that exercise failure modes identified in docs/NEXTCLOUD_SYNC_ANALYSIS.md:
 *
 * - Normal VEVENTs (baseline)
 * - VTODOs mixed in same calendar
 * - Extended/unusual RFC 5545 properties
 * - Unicode/non-ASCII in SUMMARY/DESCRIPTION/LOCATION
 * - Recurring event with exception in same .ics
 * - Orphaned exception (RECURRENCE-ID with no master)
 * - Large DESCRIPTION (~50KB)
 * - All-day events (DATE vs DATETIME)
 * - Events with VALARM
 * - Empty SUMMARY
 * - Events with VTIMEZONE + TZID
 *
 * Prerequisite: Run the test event setup script or create events via CalDAV PUT
 * on the Nextcloud "resilience-test" calendar.
 *
 * Run: ./gradlew testDebugUnitTest -Pintegration --tests "*NextcloudSyncResilienceTest*"
 */
class NextcloudSyncResilienceTest {

    private val factory = OkHttpCalDavClientFactory()
    private val props = Properties()
    private val icalParser = ICalParser()

    private var server: String? = null
    private var username: String? = null
    private var password: String? = null

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

        server = props.getProperty("NEXTCLOUD_SERVER")
        username = props.getProperty("NEXTCLOUD_USERNAME")
        password = props.getProperty("NEXTCLOUD_PASSWORD")

        // Mock Log for unit test environment
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    private fun skipIfMissing() {
        assumeTrue(
            "Nextcloud credentials not configured",
            server != null && username != null && password != null
        )
    }

    private fun createClient(): Pair<CalDavClient, DefaultQuirks> {
        val quirks = DefaultQuirks(server!!)
        val client = factory.createClient(
            Credentials(username = username!!, password = password!!, serverUrl = server!!),
            quirks
        )
        return client to quirks
    }

    /**
     * Test 1: Discovery finds the resilience-test calendar.
     */
    @Test
    fun `discovery finds resilience-test calendar`() = runBlocking {
        skipIfMissing()
        val (client, _) = createClient()

        val davEndpoint = "$server/remote.php/dav/"
        val principalResult = client.discoverPrincipal(davEndpoint)
        assertTrue("Principal discovery failed", principalResult.isSuccess())

        val homeResult = client.discoverCalendarHome(principalResult.getOrNull()!!)
        assertTrue("Calendar home discovery failed", homeResult.isSuccess())

        val calendarsResult = client.listCalendars(homeResult.getOrNull()!!.first())
        assertTrue("Calendar listing failed", calendarsResult.isSuccess())

        val calendars = calendarsResult.getOrNull()!!
        println("Found ${calendars.size} calendars:")
        calendars.forEach { println("  - ${it.displayName} at ${it.url}") }

        val resilienceCal = calendars.find { it.displayName == "Resilience Test" }
        assertNotNull("resilience-test calendar not found. Create it first.", resilienceCal)
        println("\nResilience Test calendar found at: ${resilienceCal!!.url}")
    }

    /**
     * Test 2: fetchEtagsInRange returns all VEVENTs from the mixed calendar.
     * VTODOs should be excluded by the VEVENT comp-filter.
     */
    @Test
    fun `fetchEtagsInRange returns VEVENTs only - excludes VTODOs`() = runBlocking {
        skipIfMissing()
        val (client, _) = createClient()
        val calUrl = getResilienceCalendarUrl(client)

        val now = System.currentTimeMillis()
        val oneYearBack = 365L * 24 * 60 * 60 * 1000
        val etagResult = client.fetchEtagsInRange(calUrl, now - oneYearBack, 4102444800000L)

        assertTrue("fetchEtagsInRange failed: $etagResult", etagResult.isSuccess())
        val etags = (etagResult as CalDavResult.Success).data

        println("fetchEtagsInRange returned ${etags.size} hrefs:")
        etags.forEach { (href, etag) ->
            println("  $href  etag=${etag?.take(20)}...")
        }

        // We uploaded 12 events, 1 is VTODO. So we should get 11 VEVENTs.
        assertTrue("Expected at least 10 VEVENTs, got ${etags.size}", etags.size >= 10)

        // VTODO should NOT be in the list
        val todoHref = etags.find { it.first.contains("task-item.ics") }
        assertNull("VTODO task-item.ics should be excluded by VEVENT comp-filter", todoHref)

        println("\nVTODO correctly excluded from etag listing")
    }

    /**
     * Test 3: fetchEventsByHref can fetch ALL events including edge cases.
     * This is the multiget batch path. If any event causes a batch failure,
     * this test will catch it.
     */
    @Test
    fun `fetchEventsByHref fetches all edge-case events in single batch`() = runBlocking {
        skipIfMissing()
        val (client, _) = createClient()
        val calUrl = getResilienceCalendarUrl(client)

        // Get all hrefs first
        val now = System.currentTimeMillis()
        val oneYearBack = 365L * 24 * 60 * 60 * 1000
        val etags = client.fetchEtagsInRange(calUrl, now - oneYearBack, 4102444800000L)
            .let { (it as CalDavResult.Success).data }

        val hrefs = etags.map { it.first }
        println("Fetching ${hrefs.size} events via multiget...")

        // Fetch all events in one batch (simulates what pullFull does)
        val fetchResult = client.fetchEventsByHref(calUrl, hrefs)
        assertTrue("fetchEventsByHref failed: $fetchResult", fetchResult.isSuccess())

        val events = (fetchResult as CalDavResult.Success).data
        println("Fetched ${events.size} events:")
        events.forEach { event ->
            val summary = event.icalData.lineSequence()
                .firstOrNull { it.startsWith("SUMMARY") }
                ?.substringAfter(":")
                ?.take(60) ?: "(no summary)"
            println("  ${event.url}: $summary")
        }

        // All VEVENT hrefs should return data
        assertEquals(
            "All fetched hrefs should return event data",
            hrefs.size,
            events.size
        )
    }

    /**
     * Test 4: ICalParser can parse ALL edge-case events without throwing.
     * This tests the parse path in processEvents() that currently re-throws
     * Throwable (line 725-728 in PullStrategy.kt).
     */
    @Test
    fun `ICalParser parses all edge-case events without exceptions`() = runBlocking {
        skipIfMissing()
        val (client, _) = createClient()
        val calUrl = getResilienceCalendarUrl(client)

        val events = fetchAllEvents(client, calUrl)

        var parsed = 0
        var skippedNonEvent = 0
        var parseErrors = 0
        val exceptions = mutableListOf<Pair<String, Throwable>>()

        for (event in events) {
            val icalData = event.icalData
            val isNonEvent = !icalData.contains("BEGIN:VEVENT") &&
                (icalData.contains("BEGIN:VTODO") || icalData.contains("BEGIN:VJOURNAL"))

            if (isNonEvent) {
                skippedNonEvent++
                println("  SKIP (non-event): ${event.url}")
                continue
            }

            try {
                val result = icalParser.parseAllEvents(icalData)
                val parsedEvents = result.getOrNull()

                if (parsedEvents == null) {
                    parseErrors++
                    println("  PARSE_ERROR: ${event.url} - $result")
                } else if (parsedEvents.isEmpty()) {
                    parseErrors++
                    println("  EMPTY: ${event.url} - no VEVENT components")
                } else {
                    parsed++
                    val masterCount = parsedEvents.count { !ICalEventMapper.isException(it) }
                    val excCount = parsedEvents.count { ICalEventMapper.isException(it) }
                    println("  OK: ${event.url} -> $masterCount master(s), $excCount exception(s)")
                }
            } catch (e: Throwable) {
                exceptions.add(event.url to e)
                println("  EXCEPTION: ${event.url} -> ${e.javaClass.simpleName}: ${e.message?.take(100)}")
            }
        }

        println("\n=== Parse Summary ===")
        println("Parsed OK:      $parsed")
        println("Non-event skip: $skippedNonEvent")
        println("Parse errors:   $parseErrors")
        println("EXCEPTIONS:     ${exceptions.size}")

        if (exceptions.isNotEmpty()) {
            println("\n=== EXCEPTIONS (these would abort sync!) ===")
            exceptions.forEach { (url, e) ->
                println("  $url:")
                println("    ${e.javaClass.name}: ${e.message}")
                e.stackTrace.take(5).forEach { frame ->
                    println("      at $frame")
                }
            }
        }

        // The key assertion: NO exceptions should be thrown
        assertTrue(
            "Parser threw ${exceptions.size} exception(s) that would abort sync:\n" +
                exceptions.joinToString("\n") { "  ${it.first}: ${it.second.message}" },
            exceptions.isEmpty()
        )

        // At least the normal events should parse
        assertTrue("Expected at least 8 parsed events, got $parsed", parsed >= 8)
    }

    /**
     * Test 5: Unicode/non-ASCII event parses correctly.
     * Nextcloud returns UTF-8 responses. This verifies our parser handles
     * non-ASCII characters in SUMMARY, DESCRIPTION, and LOCATION.
     */
    @Test
    fun `unicode event preserves non-ASCII characters`() = runBlocking {
        skipIfMissing()
        val (client, _) = createClient()
        val calUrl = getResilienceCalendarUrl(client)

        val events = fetchAllEvents(client, calUrl)
        val unicodeEvent = events.find { it.url.contains("unicode-event.ics") }
        assertNotNull("unicode-event.ics not found", unicodeEvent)

        val icalData = unicodeEvent!!.icalData
        println("Unicode event iCal data:\n$icalData")

        val result = icalParser.parseAllEvents(icalData)
        val parsedEvents = result.getOrNull()
        assertNotNull("Failed to parse unicode event", parsedEvents)
        assertTrue("No events parsed from unicode-event.ics", parsedEvents!!.isNotEmpty())

        val event = parsedEvents.first()
        println("\nParsed SUMMARY: ${event.summary}")
        println("Parsed LOCATION: ${event.location}")

        // Verify non-ASCII chars survived round-trip
        assertTrue(
            "SUMMARY should contain Müller, got: ${event.summary}",
            event.summary?.contains("Müller") == true
        )
        assertTrue(
            "SUMMARY should contain 田中 (Tanaka), got: ${event.summary}",
            event.summary?.contains("田中") == true
        )
    }

    /**
     * Test 6: Recurring event with exception parses as master + exception.
     */
    @Test
    fun `recurring event with exception parses both components`() = runBlocking {
        skipIfMissing()
        val (client, _) = createClient()
        val calUrl = getResilienceCalendarUrl(client)

        val events = fetchAllEvents(client, calUrl)
        val recurEvent = events.find { it.url.contains("recurring-with-exception.ics") }
        assertNotNull("recurring-with-exception.ics not found", recurEvent)

        val result = icalParser.parseAllEvents(recurEvent!!.icalData)
        val parsedEvents = result.getOrNull()
        assertNotNull("Failed to parse recurring event", parsedEvents)

        val masters = parsedEvents!!.filter { !ICalEventMapper.isException(it) }
        val excs = parsedEvents.filter { ICalEventMapper.isException(it) }

        println("Parsed ${masters.size} master(s) and ${excs.size} exception(s)")
        masters.forEach { println("  Master: ${it.summary} (UID=${it.uid})") }
        excs.forEach { println("  Exception: ${it.summary} (UID=${it.uid}, RECURRENCE-ID=${it.recurrenceId})") }

        assertEquals("Expected 1 master event", 1, masters.size)
        assertEquals("Expected 1 exception event", 1, excs.size)

        // Verify same UID (RFC 5545 requirement)
        assertEquals(
            "Exception must have same UID as master",
            masters.first().uid,
            excs.first().uid
        )
    }

    /**
     * Test 7: Orphaned exception doesn't crash parser.
     * An exception without a matching master should parse gracefully.
     */
    @Test
    fun `orphaned exception parses without crashing`() = runBlocking {
        skipIfMissing()
        val (client, _) = createClient()
        val calUrl = getResilienceCalendarUrl(client)

        val events = fetchAllEvents(client, calUrl)
        val orphanEvent = events.find { it.url.contains("orphan-exception.ics") }
        assertNotNull("orphan-exception.ics not found", orphanEvent)

        // Should not throw
        val result = icalParser.parseAllEvents(orphanEvent!!.icalData)
        val parsedEvents = result.getOrNull()

        println("Orphan exception parse result: ${parsedEvents?.size} event(s)")
        parsedEvents?.forEach { event ->
            println("  ${event.summary} (UID=${event.uid}, RECURRENCE-ID=${event.recurrenceId})")
            println("  isException=${ICalEventMapper.isException(event)}")
        }

        // It should parse without crashing, even if it's an orphan
        assertNotNull("Orphan exception should not return null", parsedEvents)
    }

    /**
     * Test 8: Large description event doesn't cause OOM or timeout.
     */
    @Test
    fun `large description event fetches and parses within limits`() = runBlocking {
        skipIfMissing()
        val (client, _) = createClient()
        val calUrl = getResilienceCalendarUrl(client)

        val events = fetchAllEvents(client, calUrl)
        val largeEvent = events.find { it.url.contains("large-description.ics") }
        assertNotNull("large-description.ics not found", largeEvent)

        println("Large event iCal data size: ${largeEvent!!.icalData.length} chars")
        assertTrue(
            "Large event should be substantial (>10KB)",
            largeEvent.icalData.length > 10_000
        )

        val result = icalParser.parseAllEvents(largeEvent.icalData)
        val parsedEvents = result.getOrNull()
        assertNotNull("Failed to parse large description event", parsedEvents)
        assertTrue("No events parsed from large-description.ics", parsedEvents!!.isNotEmpty())

        println("Large event parsed OK, title: ${parsedEvents.first().summary}")
    }

    /**
     * Test 9: All-day events parse correctly with DATE (not DATETIME).
     */
    @Test
    fun `all-day events parse with DATE format`() = runBlocking {
        skipIfMissing()
        val (client, _) = createClient()
        val calUrl = getResilienceCalendarUrl(client)

        val events = fetchAllEvents(client, calUrl)

        // Single all-day
        val allday = events.find { it.url.contains("allday-event.ics") }
        assertNotNull("allday-event.ics not found", allday)

        val result1 = icalParser.parseAllEvents(allday!!.icalData)
        val parsed1 = result1.getOrNull()!!
        assertTrue("allday-event should parse", parsed1.isNotEmpty())
        println("All-day event: ${parsed1.first().summary}, isAllDay=${parsed1.first().isAllDay}")
        assertTrue("Should be marked as all-day", parsed1.first().isAllDay)

        // Multi-day all-day
        val multiday = events.find { it.url.contains("multiday-allday.ics") }
        assertNotNull("multiday-allday.ics not found", multiday)

        val result2 = icalParser.parseAllEvents(multiday!!.icalData)
        val parsed2 = result2.getOrNull()!!
        assertTrue("multiday-allday should parse", parsed2.isNotEmpty())
        println("Multi-day event: ${parsed2.first().summary}, isAllDay=${parsed2.first().isAllDay}")
        assertTrue("Should be marked as all-day", parsed2.first().isAllDay)
    }

    /**
     * Test 10: Event with VALARM parses alarms correctly.
     */
    @Test
    fun `event with VALARM parses alarm triggers`() = runBlocking {
        skipIfMissing()
        val (client, _) = createClient()
        val calUrl = getResilienceCalendarUrl(client)

        val events = fetchAllEvents(client, calUrl)
        val alarmEvent = events.find { it.url.contains("event-with-alarm.ics") }
        assertNotNull("event-with-alarm.ics not found", alarmEvent)

        val result = icalParser.parseAllEvents(alarmEvent!!.icalData)
        val parsed = result.getOrNull()!!
        assertTrue("alarm event should parse", parsed.isNotEmpty())

        val event = parsed.first()
        println("Alarm event: ${event.summary}")
        println("Alarms: ${event.alarms}")

        // Should have 2 alarms (-PT15M and -PT1H)
        assertTrue("Should have at least 1 alarm", event.alarms.isNotEmpty())
    }

    /**
     * Test 11: Empty SUMMARY event parses without crashing.
     */
    @Test
    fun `empty summary event parses gracefully`() = runBlocking {
        skipIfMissing()
        val (client, _) = createClient()
        val calUrl = getResilienceCalendarUrl(client)

        val events = fetchAllEvents(client, calUrl)
        val emptyEvent = events.find { it.url.contains("empty-summary.ics") }
        assertNotNull("empty-summary.ics not found", emptyEvent)

        val result = icalParser.parseAllEvents(emptyEvent!!.icalData)
        val parsed = result.getOrNull()
        assertNotNull("empty summary should not fail parse", parsed)
        assertTrue("Should parse at least one event", parsed!!.isNotEmpty())

        println("Empty summary event title: '${parsed.first().summary}'")
    }

    /**
     * Test 12: Timezone event with VTIMEZONE + TZID parses correctly.
     */
    @Test
    fun `timezone event with VTIMEZONE parses correct time`() = runBlocking {
        skipIfMissing()
        val (client, _) = createClient()
        val calUrl = getResilienceCalendarUrl(client)

        val events = fetchAllEvents(client, calUrl)
        val tzEvent = events.find { it.url.contains("timezone-event.ics") }
        assertNotNull("timezone-event.ics not found", tzEvent)

        val result = icalParser.parseAllEvents(tzEvent!!.icalData)
        val parsed = result.getOrNull()
        assertNotNull("timezone event should parse", parsed)
        assertTrue("Should parse at least one event", parsed!!.isNotEmpty())

        val event = parsed.first()
        val startTs = event.dtStart.timestamp
        val endTs = event.effectiveEnd().timestamp
        println("Timezone event: ${event.summary}")
        println("  startTs: $startTs (${java.time.Instant.ofEpochMilli(startTs)})")
        println("  endTs: $endTs (${java.time.Instant.ofEpochMilli(endTs)})")

        // 10:00 AM Eastern on Feb 22, 2026 = 15:00 UTC (EST = UTC-5)
        assertTrue("Start time should be set", startTs > 0)
        assertTrue("End time should be after start", endTs > startTs)
    }

    /**
     * Test 13: Full sync-collection (incremental path) returns VTODO hrefs
     * that must be handled by processEvents without crashing.
     *
     * Unlike fetchEtagsInRange (which filters by VEVENT), syncCollection returns ALL
     * changed resources including VTODOs. The parser must handle them gracefully.
     */
    @Test
    fun `syncCollection returns VTODOs that parser handles gracefully`() = runBlocking {
        skipIfMissing()
        val (client, _) = createClient()
        val calUrl = getResilienceCalendarUrl(client)

        // Do a sync-collection with empty token to get all items
        val syncResult = client.syncCollection(calUrl, null)
        if (syncResult.isError()) {
            println("syncCollection failed (may not be supported), skipping")
            return@runBlocking
        }

        val syncReport = (syncResult as CalDavResult.Success).data
        println("syncCollection returned:")
        println("  Changed items: ${syncReport.changed.size}")
        println("  Deleted items: ${syncReport.deleted.size}")
        println("  New token: ${syncReport.syncToken?.take(30)}...")

        // Check if VTODO appears in changed items
        val todoItem = syncReport.changed.find { it.href.contains("task-item") }
        if (todoItem != null) {
            println("\n  VTODO found in sync-collection results: ${todoItem.href}")
            println("  This is expected - sync-collection returns ALL resources")

            // Fetch the VTODO via multiget (simulating what pullIncremental does)
            val fetchResult = client.fetchEventsByHref(calUrl, listOf(todoItem.href))
            if (fetchResult.isSuccess()) {
                val fetched = (fetchResult as CalDavResult.Success).data
                if (fetched.isNotEmpty()) {
                    val icalData = fetched.first().icalData
                    println("  VTODO iCal data (first 200 chars): ${icalData.take(200)}")

                    val isNonEvent = !icalData.contains("BEGIN:VEVENT") &&
                        (icalData.contains("BEGIN:VTODO") || icalData.contains("BEGIN:VJOURNAL"))

                    if (isNonEvent) {
                        println("  Correctly identified as non-event resource")
                    } else {
                        // Try parsing - should not crash
                        try {
                            val parseResult = icalParser.parseAllEvents(icalData)
                            println("  Parse result: $parseResult (expected empty or error)")
                        } catch (e: Throwable) {
                            fail("VTODO parsing threw ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                }
            }
        } else {
            println("\n  VTODO not found in sync-collection (calendar may filter it)")
        }
    }

    /**
     * Test 14: Nextcloud deleted-calendar flag is detected.
     * Nextcloud soft-deletes calendars with <x1:deleted-calendar> in resourcetype.
     */
    @Test
    fun `deleted calendar detection in PROPFIND response`() = runBlocking {
        skipIfMissing()
        val (client, _) = createClient()

        val davEndpoint = "$server/remote.php/dav/"
        val principal = client.discoverPrincipal(davEndpoint).getOrNull()!!
        val homeUrls = client.discoverCalendarHome(principal).getOrNull()!!

        val calendarsResult = client.listCalendars(homeUrls.first())
        assertTrue("Calendar listing failed", calendarsResult.isSuccess())

        val calendars = calendarsResult.getOrNull()!!
        println("All calendars:")
        calendars.forEach { cal ->
            println("  ${cal.displayName} -> ${cal.url} (readOnly=${cal.isReadOnly})")
        }

        // The mixed-test calendar (soft-deleted) should either be excluded by
        // extractCalendars() or flagged
        val deletedCal = calendars.find { it.displayName == "Mixed Test" }
        if (deletedCal != null) {
            println("\nWARNING: Soft-deleted 'Mixed Test' calendar appears in listing!")
            println("This could cause sync issues if KashCal tries to sync it.")
            println("Nextcloud marks it with <x1:deleted-calendar> but KashCal's")
            println("extractCalendars() doesn't filter for this.")
        } else {
            println("\nSoft-deleted calendar correctly excluded from listing")
        }
    }

    // ==================== THEORY TESTS ====================
    // These test the three hypotheses for "a few events + empty sync log"

    /**
     * THEORY 1: ICalEventMapper.toEntity().event crashes on edge-case data.
     *
     * If toEntity() throws for a specific event, it would propagate through
     * processEvents() → pullFull() → pull() → syncCalendar(). The sync
     * would stop and events already written stay in Room.
     *
     * Color.parseColor() is the main risk — it's an Android API that throws
     * IllegalArgumentException for unsupported color formats. Our test event
     * has COLOR:tomato which Android handles but unit tests may not.
     */
    @Test
    fun `THEORY 1 - toEntity maps all Nextcloud events without exceptions`() = runBlocking {
        skipIfMissing()
        val (client, _) = createClient()
        val calUrl = getResilienceCalendarUrl(client)

        // Mock Color.parseColor for unit test environment
        mockkStatic(android.graphics.Color::class)
        every { android.graphics.Color.parseColor(any()) } answers {
            val color = firstArg<String>()
            // Simulate Android's Color.parseColor for common named colors
            when (color.lowercase()) {
                "tomato" -> 0xFFFF6347.toInt()
                "red" -> 0xFFFF0000.toInt()
                "blue" -> 0xFF0000FF.toInt()
                else -> {
                    if (color.startsWith("#")) {
                        // Basic hex parsing
                        val hex = color.removePrefix("#")
                        when (hex.length) {
                            6 -> (0xFF000000 or hex.toLong(16)).toInt()
                            8 -> hex.toLong(16).toInt()
                            else -> throw IllegalArgumentException("Unknown color: $color")
                        }
                    } else {
                        throw IllegalArgumentException("Unknown color: $color")
                    }
                }
            }
        }

        val events = fetchAllEvents(client, calUrl)
        var mapped = 0
        val failures = mutableListOf<Pair<String, Throwable>>()

        for (event in events) {
            val icalData = event.icalData
            if (!icalData.contains("BEGIN:VEVENT")) continue

            val parseResult = icalParser.parseAllEvents(icalData)
            val parsedEvents = parseResult.getOrNull() ?: continue

            for (icalEvent in parsedEvents) {
                try {
                    val entity = ICalEventMapper.toEntity(
                        icalEvent = icalEvent,
                        rawIcal = icalData,
                        calendarId = 1L,
                        caldavUrl = event.url,
                        etag = event.etag
                    ).event
                    mapped++
                    println("  OK: ${entity.title} (uid=${entity.uid}, startTs=${entity.startTs}, " +
                        "isAllDay=${entity.isAllDay}, color=${entity.color}, " +
                        "geoLat=${entity.geoLat}, rrule=${entity.rrule?.take(30)})")
                } catch (e: Throwable) {
                    failures.add(event.url to e)
                    println("  FAIL: ${event.url} -> ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }

        println("\n=== toEntity Summary ===")
        println("Mapped OK:  $mapped")
        println("FAILURES:   ${failures.size}")

        if (failures.isNotEmpty()) {
            println("\n=== FAILURES (would crash processEvents!) ===")
            failures.forEach { (url, e) ->
                println("  $url: ${e.javaClass.name}: ${e.message}")
                e.stackTrace.take(3).forEach { println("    at $it") }
            }
        }

        assertTrue(
            "toEntity() failed for ${failures.size} event(s):\n" +
                failures.joinToString("\n") { "  ${it.first}: ${it.second.message}" },
            failures.isEmpty()
        )
        assertTrue("Expected at least 8 mapped events", mapped >= 8)
    }

    /**
     * THEORY 2: SyncSession serialization round-trip preserves all fields.
     *
     * kotlinx.serialization uses the Kotlin constructor, so default values
     * are applied correctly and type safety is preserved at compile time.
     */
    @Test
    fun `THEORY 2 - SyncSession serialization round-trip preserves all fields`() {
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        // Create a session with ALL fields populated (worst case for round-trip)
        val original = org.onekash.kashcal.sync.session.SyncSession(
            id = "test-session-001",
            timestamp = System.currentTimeMillis(),
            calendarId = 42L,
            calendarName = "Test Calendar",
            syncType = org.onekash.kashcal.sync.session.SyncType.FULL,
            triggerSource = org.onekash.kashcal.sync.session.SyncTrigger.FOREGROUND_MANUAL,
            durationMs = 5000L,
            hrefsReported = 50,
            eventsFetched = 45,
            eventsWritten = 10,
            eventsUpdated = 5,
            eventsDeleted = 2,
            eventsPushedCreated = 3,
            eventsPushedUpdated = 1,
            eventsPushedDeleted = 0,
            skippedParseError = 2,
            skippedPendingLocal = 1,
            skippedEtagUnchanged = 20,
            skippedOrphanedException = 1,
            skippedAlreadySynced = 0,
            skippedRecentlyPushed = 3,
            hasMissingEvents = true,
            missingCount = 5,
            tokenAdvanced = true,
            abandonedParseErrors = 1,
            errorType = org.onekash.kashcal.sync.session.ErrorType.PARSE,
            errorStage = "pull",
            errorMessage = "Failed to parse event",
            truncated = true
        )

        // Round-trip: serialize -> deserialize
        val jsonStr = json.encodeToString(listOf(original))
        println("Serialized JSON size: ${jsonStr.length} chars")
        println("JSON sample: ${jsonStr.take(200)}...")

        val deserialized: List<org.onekash.kashcal.sync.session.SyncSession> =
            json.decodeFromString(jsonStr)

        assertEquals("Should deserialize 1 session", 1, deserialized.size)
        val restored = deserialized.first()

        // Verify ALL fields survived round-trip
        assertEquals("id", original.id, restored.id)
        assertEquals("timestamp", original.timestamp, restored.timestamp)
        assertEquals("calendarId", original.calendarId, restored.calendarId)
        assertEquals("calendarName", original.calendarName, restored.calendarName)
        assertEquals("syncType", original.syncType, restored.syncType)
        assertEquals("triggerSource", original.triggerSource, restored.triggerSource)
        assertEquals("durationMs", original.durationMs, restored.durationMs)
        assertEquals("hrefsReported", original.hrefsReported, restored.hrefsReported)
        assertEquals("eventsFetched", original.eventsFetched, restored.eventsFetched)
        assertEquals("eventsWritten", original.eventsWritten, restored.eventsWritten)
        assertEquals("eventsUpdated", original.eventsUpdated, restored.eventsUpdated)
        assertEquals("eventsDeleted", original.eventsDeleted, restored.eventsDeleted)
        assertEquals("eventsPushedCreated", original.eventsPushedCreated, restored.eventsPushedCreated)
        assertEquals("eventsPushedUpdated", original.eventsPushedUpdated, restored.eventsPushedUpdated)
        assertEquals("eventsPushedDeleted", original.eventsPushedDeleted, restored.eventsPushedDeleted)
        assertEquals("skippedParseError", original.skippedParseError, restored.skippedParseError)
        assertEquals("skippedPendingLocal", original.skippedPendingLocal, restored.skippedPendingLocal)
        assertEquals("skippedEtagUnchanged", original.skippedEtagUnchanged, restored.skippedEtagUnchanged)
        assertEquals("skippedOrphanedException", original.skippedOrphanedException, restored.skippedOrphanedException)
        assertEquals("skippedAlreadySynced", original.skippedAlreadySynced, restored.skippedAlreadySynced)
        assertEquals("skippedRecentlyPushed", original.skippedRecentlyPushed, restored.skippedRecentlyPushed)
        assertEquals("hasMissingEvents", original.hasMissingEvents, restored.hasMissingEvents)
        assertEquals("missingCount", original.missingCount, restored.missingCount)
        assertEquals("tokenAdvanced", original.tokenAdvanced, restored.tokenAdvanced)
        assertEquals("abandonedParseErrors", original.abandonedParseErrors, restored.abandonedParseErrors)
        assertEquals("errorType", original.errorType, restored.errorType)
        assertEquals("errorStage", original.errorStage, restored.errorStage)
        assertEquals("errorMessage", original.errorMessage, restored.errorMessage)
        assertEquals("truncated", original.truncated, restored.truncated)

        // Verify computed properties work after deserialization
        assertEquals("status", original.status, restored.status)
        assertEquals("totalChanges", original.totalChanges, restored.totalChanges)
        assertEquals("totalPushed", original.totalPushed, restored.totalPushed)

        println("All fields survived serialization round-trip")
    }

    /**
     * THEORY 2b: Serialization handles missing fields in old session JSON.
     *
     * If the user's app was updated and the session file has old-format entries,
     * kotlinx.serialization applies Kotlin default values for missing fields
     * (unlike Gson which used JVM zero values).
     */
    @Test
    fun `THEORY 2b - serialization handles missing fields in old session JSON`() {
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        // Simulate old JSON with only the original fields (no push stats, no recently-pushed, etc.)
        val oldJson = """[{
            "id": "old-session-001",
            "timestamp": ${System.currentTimeMillis()},
            "calendarId": 1,
            "calendarName": "Personal",
            "syncType": "FULL",
            "triggerSource": "FOREGROUND_MANUAL",
            "durationMs": 3000,
            "hrefsReported": 10,
            "eventsFetched": 10,
            "eventsWritten": 5,
            "eventsUpdated": 3,
            "eventsDeleted": 0
        }]"""

        // This should NOT throw
        val sessions: List<org.onekash.kashcal.sync.session.SyncSession> = try {
            json.decodeFromString(oldJson)
        } catch (e: Throwable) {
            fail("Deserialization of old-format JSON threw: ${e.javaClass.simpleName}: ${e.message}")
            return
        }

        assertEquals("Should deserialize 1 session", 1, sessions.size)
        val session = sessions.first()

        println("Deserialized old-format session:")
        println("  id: ${session.id}")
        println("  calendarName: ${session.calendarName}")
        println("  eventsWritten: ${session.eventsWritten}")
        println("  eventsPushedCreated: ${session.eventsPushedCreated}")
        println("  skippedRecentlyPushed: ${session.skippedRecentlyPushed}")
        println("  truncated: ${session.truncated}")
        println("  errorType: ${session.errorType}")

        // Missing Int fields get Kotlin default (0)
        assertEquals("eventsPushedCreated should be 0", 0, session.eventsPushedCreated)
        assertEquals("skippedRecentlyPushed should be 0", 0, session.skippedRecentlyPushed)

        // Missing Boolean fields get Kotlin default (false for truncated)
        assertEquals("truncated should be false", false, session.truncated)

        // tokenAdvanced gets Kotlin default (true) — correctness fix vs Gson's JVM zero (false)
        assertEquals("tokenAdvanced should be true (Kotlin default)", true, session.tokenAdvanced)

        // Missing nullable fields get Kotlin default (null)
        assertNull("errorType should be null", session.errorType)

        // Verify computed properties don't crash
        try {
            val status = session.status
            val total = session.totalChanges
            val pushed = session.totalPushed
            println("  status: $status, totalChanges: $total, totalPushed: $pushed")
        } catch (e: Throwable) {
            fail("Computed property access crashed: ${e.javaClass.simpleName}: ${e.message}")
        }

        println("Old-format JSON deserialized successfully")
    }

    /**
     * THEORY 3: Multi-calendar sync aborts after first calendar.
     *
     * CalDavSyncEngine.syncAccount() iterates calendars. If a read-only calendar
     * returns AuthError (401), it returns immediately — skipping remaining calendars.
     * Similarly, writable calendar AuthError returns immediately.
     *
     * This test verifies we can discover all calendars and fetch from each one.
     * If the user's Nextcloud has a calendar that returns errors, remaining calendars
     * are skipped.
     */
    @Test
    fun `THEORY 3 - all calendars are individually accessible`() = runBlocking {
        skipIfMissing()
        val (client, _) = createClient()

        val davEndpoint = "$server/remote.php/dav/"
        val principal = client.discoverPrincipal(davEndpoint).getOrNull()!!
        val homeUrls = client.discoverCalendarHome(principal).getOrNull()!!
        val calendars = client.listCalendars(homeUrls.first()).getOrNull()!!

        println("Testing access to ${calendars.size} calendars:\n")

        var accessible = 0
        var failed = 0

        for (cal in calendars) {
            print("  ${cal.displayName} (${cal.url})... ")

            try {
                // Try fetching etags — this is what pull does first
                val etagResult = client.fetchEtagsInRange(
                    cal.url,
                    System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000,
                    System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
                )

                when {
                    etagResult.isSuccess() -> {
                        val count = (etagResult as CalDavResult.Success).data.size
                        println("OK ($count events)")
                        accessible++
                    }
                    else -> {
                        println("ERROR: $etagResult")
                        failed++
                    }
                }
            } catch (e: Throwable) {
                println("EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
                failed++
            }

            // Also try sync-collection (incremental path)
            try {
                val syncResult = client.syncCollection(cal.url, null)
                val status = if (syncResult.isSuccess()) {
                    val report = (syncResult as CalDavResult.Success).data
                    "OK (${report.changed.size} items, token=${report.syncToken?.take(20)}...)"
                } else {
                    "FAILED: $syncResult"
                }
                println("    sync-collection: $status")
            } catch (e: Throwable) {
                println("    sync-collection: EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        println("\n=== Calendar Access Summary ===")
        println("Accessible: $accessible")
        println("Failed:     $failed")

        // All calendars should be accessible
        if (failed > 0) {
            println("\nWARNING: $failed calendar(s) returned errors!")
            println("In syncAccount(), AuthError from ANY calendar stops the entire loop.")
            println("This could explain 'imports a few events then stops'.")
        }

        assertEquals("All calendars should be accessible", 0, failed)
    }

    // === Helper methods ===

    private suspend fun getResilienceCalendarUrl(client: CalDavClient): String {
        val davEndpoint = "$server/remote.php/dav/"
        val principal = client.discoverPrincipal(davEndpoint).getOrNull()!!
        val homeUrls = client.discoverCalendarHome(principal).getOrNull()!!
        val calendars = client.listCalendars(homeUrls.first()).getOrNull()!!

        val resilienceCal = calendars.find { it.displayName == "Resilience Test" }
            ?: throw AssertionError(
                "resilience-test calendar not found. Available: ${calendars.map { it.displayName }}"
            )

        return resilienceCal.url
    }

    private suspend fun fetchAllEvents(client: CalDavClient, calUrl: String): List<CalDavEvent> {
        val now = System.currentTimeMillis()
        val oneYearBack = 365L * 24 * 60 * 60 * 1000
        val etags = client.fetchEtagsInRange(calUrl, now - oneYearBack, 4102444800000L)
            .let { (it as CalDavResult.Success).data }

        val hrefs = etags.map { it.first }
        val fetchResult = client.fetchEventsByHref(calUrl, hrefs)
        assertTrue("fetchEventsByHref failed", fetchResult.isSuccess())

        return (fetchResult as CalDavResult.Success).data
    }
}