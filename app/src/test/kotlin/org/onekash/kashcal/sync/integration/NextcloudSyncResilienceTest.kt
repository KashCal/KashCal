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
 * Runs the CalDAV client, parser and mapper against a Nextcloud calendar seeded with edge-case
 * resources that exercise known Nextcloud sync failure modes:
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
 * Prerequisite: run the test event setup script or create the events via CalDAV PUT on the
 * Nextcloud calendar named "Resilience Test".
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

    /** Checks that discovery lists the "Resilience Test" calendar. */
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
     * Checks that fetchEtagsInRange lists the calendar's VEVENTs and its VEVENT comp-filter
     * leaves out the VTODO.
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

        // 12 resources were uploaded, one a VTODO, so 11 VEVENTs are expected.
        assertTrue("Expected at least 10 VEVENTs, got ${etags.size}", etags.size >= 10)

        val todoHref = etags.find { it.first.contains("task-item.ics") }
        assertNull("VTODO task-item.ics should be excluded by VEVENT comp-filter", todoHref)

        println("\nVTODO correctly excluded from etag listing")
    }

    /**
     * Checks that one calendar-multiget returns data for every listed href, edge cases
     * included, so no resource fails the batch.
     */
    @Test
    fun `fetchEventsByHref fetches all edge-case events in single batch`() = runBlocking {
        skipIfMissing()
        val (client, _) = createClient()
        val calUrl = getResilienceCalendarUrl(client)

        val now = System.currentTimeMillis()
        val oneYearBack = 365L * 24 * 60 * 60 * 1000
        val etags = client.fetchEtagsInRange(calUrl, now - oneYearBack, 4102444800000L)
            .let { (it as CalDavResult.Success).data }

        val hrefs = etags.map { it.first }
        println("Fetching ${hrefs.size} events via multiget...")

        // One multiget for every href; the pull sends batches of up to 20.
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

        assertEquals(
            "All fetched hrefs should return event data",
            hrefs.size,
            events.size
        )
    }

    /**
     * Checks that ICalParser parses every edge-case resource without throwing. The pull's
     * processEvents catches an Exception from the parser and skips that resource; any other
     * Throwable would abort the sync.
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

        assertTrue(
            "Parser threw ${exceptions.size} exception(s) that would abort sync:\n" +
                exceptions.joinToString("\n") { "  ${it.first}: ${it.second.message}" },
            exceptions.isEmpty()
        )

        // At least the ordinary events parse.
        assertTrue("Expected at least 8 parsed events, got $parsed", parsed >= 8)
    }

    /**
     * Checks that non-ASCII text in SUMMARY survives Nextcloud's UTF-8 response and the parser.
     * LOCATION is printed, not asserted.
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

        assertTrue(
            "SUMMARY should contain Müller, got: ${event.summary}",
            event.summary?.contains("Müller") == true
        )
        assertTrue(
            "SUMMARY should contain 田中 (Tanaka), got: ${event.summary}",
            event.summary?.contains("田中") == true
        )
    }

    /** Checks that a resource holding a master and an exception parses as both. */
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

        // RFC 5545: an exception shares its master's UID.
        assertEquals(
            "Exception must have same UID as master",
            masters.first().uid,
            excs.first().uid
        )
    }

    /** Checks that an exception with no master in its resource parses to a non-null result. */
    @Test
    fun `orphaned exception parses without crashing`() = runBlocking {
        skipIfMissing()
        val (client, _) = createClient()
        val calUrl = getResilienceCalendarUrl(client)

        val events = fetchAllEvents(client, calUrl)
        val orphanEvent = events.find { it.url.contains("orphan-exception.ics") }
        assertNotNull("orphan-exception.ics not found", orphanEvent)

        val result = icalParser.parseAllEvents(orphanEvent!!.icalData)
        val parsedEvents = result.getOrNull()

        println("Orphan exception parse result: ${parsedEvents?.size} event(s)")
        parsedEvents?.forEach { event ->
            println("  ${event.summary} (UID=${event.uid}, RECURRENCE-ID=${event.recurrenceId})")
            println("  isException=${ICalEventMapper.isException(event)}")
        }

        assertNotNull("Orphan exception should not return null", parsedEvents)
    }

    /** Checks that an event with a large DESCRIPTION (over 10 KB) fetches and parses. */
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

    /** Checks that single-day and multi-day DATE events parse as all-day. */
    @Test
    fun `all-day events parse with DATE format`() = runBlocking {
        skipIfMissing()
        val (client, _) = createClient()
        val calUrl = getResilienceCalendarUrl(client)

        val events = fetchAllEvents(client, calUrl)

        val allday = events.find { it.url.contains("allday-event.ics") }
        assertNotNull("allday-event.ics not found", allday)

        val result1 = icalParser.parseAllEvents(allday!!.icalData)
        val parsed1 = result1.getOrNull()!!
        assertTrue("allday-event should parse", parsed1.isNotEmpty())
        println("All-day event: ${parsed1.first().summary}, isAllDay=${parsed1.first().isAllDay}")
        assertTrue("Should be marked as all-day", parsed1.first().isAllDay)

        val multiday = events.find { it.url.contains("multiday-allday.ics") }
        assertNotNull("multiday-allday.ics not found", multiday)

        val result2 = icalParser.parseAllEvents(multiday!!.icalData)
        val parsed2 = result2.getOrNull()!!
        assertTrue("multiday-allday should parse", parsed2.isNotEmpty())
        println("Multi-day event: ${parsed2.first().summary}, isAllDay=${parsed2.first().isAllDay}")
        assertTrue("Should be marked as all-day", parsed2.first().isAllDay)
    }

    /** Checks that an event's VALARMs parse. */
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

        // The fixture has 2 alarms (-PT15M and -PT1H); only one is required.
        assertTrue("Should have at least 1 alarm", event.alarms.isNotEmpty())
    }

    /** Checks that an event with an empty SUMMARY parses. */
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

    /** Checks that an event with a VTIMEZONE and TZID parses to a start before its end. */
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

        // The fixture is 10:00 Eastern on Feb 22, 2026, 15:00 UTC (EST = UTC-5); only the
        // order is asserted.
        assertTrue("Start time should be set", startTs > 0)
        assertTrue("End time should be after start", endTs > startTs)
    }

    /**
     * Checks that a VTODO returned by sync-collection, the delta path, doesn't make the parser
     * throw. Unlike fetchEtagsInRange, which filters by VEVENT, sync-collection returns every
     * changed resource. Returns without asserting when sync-collection fails, doesn't list the
     * VTODO, or the fetch of it fails or comes back empty.
     */
    @Test
    fun `syncCollection returns VTODOs that parser handles gracefully`() = runBlocking {
        skipIfMissing()
        val (client, _) = createClient()
        val calUrl = getResilienceCalendarUrl(client)

        // A null token returns every resource.
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

        val todoItem = syncReport.changed.find { it.href.contains("task-item") }
        if (todoItem != null) {
            println("\n  VTODO found in sync-collection results: ${todoItem.href}")
            println("  This is expected - sync-collection returns ALL resources")

            // Fetch the VTODO by multiget, as the delta pull does.
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
     * Reports whether a soft-deleted calendar appears in the listing; asserts only that the
     * listing succeeds. Nextcloud soft-deletes a calendar by adding `<x1:deleted-calendar>` to
     * its resourcetype, and the client's calendar parser doesn't filter on it.
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

        // "Mixed Test" is the soft-deleted calendar.
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
    // Hypotheses for the report "a few events + empty sync log".

    /**
     * Checks that ICalEventMapper.toEntity maps every edge-case event without throwing. The
     * pull catches a mapping exception per event and skips it, so a throw here is an event
     * that never reaches Room.
     *
     * The fixture has COLOR:tomato, which the mapper resolves through EventColorPalette.
     * Color.parseColor, which returns 0 in JVM unit tests, is mocked below for other values;
     * like Android's, it throws IllegalArgumentException for a format it can't parse.
     */
    @Test
    fun `THEORY 1 - toEntity maps all Nextcloud events without exceptions`() = runBlocking {
        skipIfMissing()
        val (client, _) = createClient()
        val calUrl = getResilienceCalendarUrl(client)

        mockkStatic(android.graphics.Color::class)
        every { android.graphics.Color.parseColor(any()) } answers {
            val color = firstArg<String>()
            // A few named colors and hex, like Android's Color.parseColor.
            when (color.lowercase()) {
                "tomato" -> 0xFFFF6347.toInt()
                "red" -> 0xFFFF0000.toInt()
                "blue" -> 0xFF0000FF.toInt()
                else -> {
                    if (color.startsWith("#")) {
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
     * Checks that a SyncSession with every field set survives a JSON round trip with the
     * store's Json settings, computed properties included.
     */
    @Test
    fun `THEORY 2 - SyncSession serialization round-trip preserves all fields`() {
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

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

        val jsonStr = json.encodeToString(listOf(original))
        println("Serialized JSON size: ${jsonStr.length} chars")
        println("JSON sample: ${jsonStr.take(200)}...")

        val deserialized: List<org.onekash.kashcal.sync.session.SyncSession> =
            json.decodeFromString(jsonStr)

        assertEquals("Should deserialize 1 session", 1, deserialized.size)
        val restored = deserialized.first()

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

        assertEquals("status", original.status, restored.status)
        assertEquals("totalChanges", original.totalChanges, restored.totalChanges)
        assertEquals("totalPushed", original.totalPushed, restored.totalPushed)

        println("All fields survived serialization round-trip")
    }

    /**
     * Checks that session JSON written by an older app version, missing newer fields,
     * deserializes with each missing field at its Kotlin default.
     */
    @Test
    fun `THEORY 2b - serialization handles missing fields in old session JSON`() {
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        // Old-format JSON without the push stats, skippedRecentlyPushed or later fields.
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

        assertEquals("eventsPushedCreated should be 0", 0, session.eventsPushedCreated)
        assertEquals("skippedRecentlyPushed should be 0", 0, session.skippedRecentlyPushed)

        assertEquals("truncated should be false", false, session.truncated)

        // tokenAdvanced defaults to true, not a JVM zero of false.
        assertEquals("tokenAdvanced should be true (Kotlin default)", true, session.tokenAdvanced)

        assertNull("errorType should be null", session.errorType)

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
     * Checks that every listed calendar answers an etag listing, and prints its sync-collection
     * result.
     *
     * CalDavSyncEngine.syncAccount stops at the first auth failure: a 401 from a read-only
     * calendar's pull, or an AuthError from a writable calendar's sync, skips the remaining
     * calendars.
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
                // The full pull lists etags before fetching.
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

            // sync-collection, the delta path.
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