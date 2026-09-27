package org.onekash.kashcal.data.ics

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Compares [IcsParserService] with a direct [ICalParser] parse on real holiday feeds downloaded
 * from [BASE_URL] in [setup].
 *
 * [IcsParserService] wraps the same [ICalParser], drops CANCELLED events and maps through
 * [ICalEventMapper], so the comparison checks what that wrapping changes. Test groups:
 * - PRE: [IcsParserService] parses every feed with the required fields.
 * - POST: [ICalParser] parses every feed and its events map to
 *   [org.onekash.kashcal.data.db.entity.Event].
 * - COMPARE: event counts, UIDs, titles and all-day starts agree.
 * - EDGE: non-Latin titles, escaped characters, description lengths.
 * - FEATURE, PERF and FIXTURE: CATEGORIES and importId, parse time, bundled fixtures.
 *
 * Fewer than 20 downloaded feeds fails the two "parses all downloaded calendars" tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsParserComparisonTest {

    companion object {
        private const val CALENDAR_ID = 1L
        private const val SUBSCRIPTION_ID = 1L
        private const val BASE_URL = "https://www.thunderbird.net/media/caldata/autogen/"

        /**
         * Lists the holiday feeds to download, chosen to span regions (Americas, Europe, Asia,
         * Africa, Oceania), scripts (Latin, Cyrillic, CJK and others) and features (all-day,
         * multi-day, descriptions).
         *
         * Some feeds may be empty or 404, depending on the host's current data.
         */
        val HOLIDAY_CALENDARS = listOf(
            // Americas
            "ArgentinaHolidays.ics",
            "BrazilHolidays.ics",
            "CanadaHolidays.ics",
            "ChileHolidays.ics",
            "MexicoHolidays.ics",

            // Europe - Latin script
            "AustrianHolidays.ics",
            "BelgianHolidays.ics",
            "CzechHolidays.ics",
            "DutchHolidays.ics",
            "FrenchHolidays.ics",
            "GermanHolidays.ics",
            "ItalianHolidays.ics",
            "NorwegianHolidays.ics",
            "PolishHolidays.ics",
            "SwedishHolidays.ics",
            "SwissHolidays.ics",
            "UKHolidays.ics",

            // Europe - Cyrillic script
            "BulgarianHolidays.ics",

            // Middle East / North Africa
            "AlgeriaHolidays.ics",

            // Asia
            "ChinaHolidays.ics",
            "HongKongHolidays.ics",
            "IndiaHolidays.ics",
            "JapanHolidays.ics",
            "MalaysiaHolidays.ics",
            "SingaporeHolidays.ics",
            "SouthKoreaHolidays.ics",
            "TaiwanHolidays.ics",
            "ThailandHolidays.ics",
            "VietnamHolidays.ics",

            // Oceania
            "AustraliaHolidays.ics",
            "NewZealandHolidays.ics",

            // Africa
            "SouthAfricaHolidays.ics",
            "KenyaHolidays.ics"
        )

        /**
         * Lists feeds known to be valid ICS with no events, a data issue at the host and not a
         * parser issue. IndonesiaHolidays.ics isn't in [HOLIDAY_CALENDARS], so it isn't downloaded.
         */
        val KNOWN_EMPTY_CALENDARS = setOf(
            "IndonesiaHolidays.ics"  // Empty as of 2025-01
        )
    }

    private lateinit var httpClient: OkHttpClient
    private lateinit var icalParser: ICalParser
    private val downloadedCalendars = mutableMapOf<String, String>()
    private val downloadErrors = mutableMapOf<String, String>()

    @Before
    fun setup() {
        httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        icalParser = ICalParser()

        // Download all calendars once
        downloadAllCalendars()
    }

    private fun downloadAllCalendars() {
        for (calendar in HOLIDAY_CALENDARS) {
            try {
                val url = "$BASE_URL$calendar"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    val content = response.body?.string()
                    if (content != null && content.contains("BEGIN:VCALENDAR")) {
                        downloadedCalendars[calendar] = content
                    } else {
                        downloadErrors[calendar] = "Invalid ICS content"
                    }
                } else {
                    downloadErrors[calendar] = "HTTP ${response.code}"
                }
            } catch (e: Exception) {
                downloadErrors[calendar] = e.message ?: "Unknown error"
            }
        }

        println("Downloaded ${downloadedCalendars.size} calendars, ${downloadErrors.size} errors")
        if (downloadErrors.isNotEmpty()) {
            println("Download errors: $downloadErrors")
        }
    }

    // ========== Pre-Tests: IcsParserService ==========

    @Test
    fun `PRE - IcsParserService parses all downloaded calendars`() {
        assertTrue("Should have downloaded at least 20 calendars", downloadedCalendars.size >= 20)

        val results = mutableMapOf<String, ParseResult>()

        for ((name, content) in downloadedCalendars) {
            val events = IcsParserService.parseIcsContent(content, CALENDAR_ID, SUBSCRIPTION_ID)
            results[name] = ParseResult(
                eventCount = events.size,
                success = events.isNotEmpty() || name in KNOWN_EMPTY_CALENDARS,
                error = if (events.isEmpty() && name !in KNOWN_EMPTY_CALENDARS) "No events parsed" else null
            )
        }

        // Report results
        val successful = results.filter { it.value.success }
        val failed = results.filter { !it.value.success }

        println("\n=== IcsParserService Results ===")
        println("Successful: ${successful.size}/${results.size}")
        println("Total events parsed: ${results.values.sumOf { it.eventCount }}")

        if (failed.isNotEmpty()) {
            println("Failed calendars: ${failed.keys}")
        }

        // Every calendar except the known empty ones must parse
        assertTrue(
            "All calendars should parse with IcsParserService. Failed: ${failed.keys}",
            failed.isEmpty()
        )
    }

    @Test
    fun `PRE - IcsParserService handles all-day events correctly`() {
        for ((name, content) in downloadedCalendars) {
            if (name in KNOWN_EMPTY_CALENDARS) continue

            val events = IcsParserService.parseIcsContent(content, CALENDAR_ID, SUBSCRIPTION_ID)

            // Each holiday calendar must have at least one all-day event
            val allDayEvents = events.filter { it.isAllDay }
            assertTrue(
                "$name: Holiday calendar should have all-day events (found ${events.size} events)",
                allDayEvents.isNotEmpty()
            )

            // All-day events end at or after their start
            for (event in allDayEvents) {
                assertTrue(
                    "$name: ${event.title} - endTs should be >= startTs",
                    event.endTs >= event.startTs
                )
            }
        }
    }

    @Test
    fun `PRE - IcsParserService extracts all properties`() {
        for ((name, content) in downloadedCalendars) {
            val events = IcsParserService.parseIcsContent(content, CALENDAR_ID, SUBSCRIPTION_ID)

            for (event in events) {
                // Required properties
                assertNotNull("$name: ${event.title} - UID required", event.uid)
                assertTrue("$name: ${event.title} - title required", event.title.isNotBlank())
                assertTrue("$name: ${event.title} - startTs required", event.startTs > 0)
                assertTrue("$name: ${event.title} - endTs required", event.endTs > 0)

                // TRANSP should be set (holiday events are typically TRANSPARENT)
                assertNotNull("$name: ${event.title} - transp should be set", event.transp)
            }
        }
    }

    // ========== Post-Tests: ICalParser (icaldav) ==========

    @Test
    fun `POST - ICalParser parses all downloaded calendars`() {
        assertTrue("Should have downloaded at least 20 calendars", downloadedCalendars.size >= 20)

        val results = mutableMapOf<String, ParseResult>()

        for ((name, content) in downloadedCalendars) {
            val parseResult = icalParser.parseAllEvents(content)

            when (parseResult) {
                is org.onekash.icaldav.model.ParseResult.Success -> {
                    results[name] = ParseResult(
                        eventCount = parseResult.value.size,
                        success = parseResult.value.isNotEmpty() || name in KNOWN_EMPTY_CALENDARS,
                        error = if (parseResult.value.isEmpty() && name !in KNOWN_EMPTY_CALENDARS) "No events parsed" else null
                    )
                }
                is org.onekash.icaldav.model.ParseResult.Error -> {
                    results[name] = ParseResult(
                        eventCount = 0,
                        success = false,
                        error = parseResult.error.message
                    )
                }
            }
        }

        // Report results
        val successful = results.filter { it.value.success }
        val failed = results.filter { !it.value.success }

        println("\n=== ICalParser Results ===")
        println("Successful: ${successful.size}/${results.size}")
        println("Total events parsed: ${results.values.sumOf { it.eventCount }}")

        if (failed.isNotEmpty()) {
            println("Failed calendars:")
            failed.forEach { (name, result) ->
                println("  $name: ${result.error}")
            }
        }

        // Every calendar except the known empty ones must parse
        assertTrue(
            "All calendars should parse with ICalParser. Failed: ${failed.keys}",
            failed.isEmpty()
        )
    }

    @Test
    fun `POST - ICalParser to Event mapping works for all calendars`() {
        for ((name, content) in downloadedCalendars) {
            if (name in KNOWN_EMPTY_CALENDARS) continue

            val parseResult = icalParser.parseAllEvents(content)

            if (parseResult is org.onekash.icaldav.model.ParseResult.Success) {
                val events = parseResult.value.map { icalEvent ->
                    ICalEventMapper.toEntity(
                        icalEvent = icalEvent,
                        rawIcal = null,
                        calendarId = CALENDAR_ID,
                        caldavUrl = "${IcsSubscription.SOURCE_PREFIX}:${SUBSCRIPTION_ID}:${icalEvent.uid}",
                        etag = null
                    ).event
                }

                assertTrue("$name: Should have events after mapping", events.isNotEmpty())

                // Mapped events carry the required fields
                for (event in events) {
                    assertNotNull("$name: ${event.title} - UID required", event.uid)
                    assertTrue("$name: ${event.title} - title required", event.title.isNotBlank())
                    assertTrue("$name: ${event.title} - startTs > 0", event.startTs > 0)
                    assertTrue("$name: ${event.title} - endTs >= startTs", event.endTs >= event.startTs)
                }
            }
        }
    }

    // ========== Comparison Tests ==========

    @Test
    fun `COMPARE - Both parsers produce same event count`() {
        val comparison = mutableMapOf<String, Pair<Int, Int>>()
        val mismatches = mutableListOf<String>()

        for ((name, content) in downloadedCalendars) {
            // Parse with IcsParserService
            val rfcEvents = IcsParserService.parseIcsContent(content, CALENDAR_ID, SUBSCRIPTION_ID)

            // Parse with ICalParser
            val icalResult = icalParser.parseAllEvents(content)
            val icalCount = when (icalResult) {
                is org.onekash.icaldav.model.ParseResult.Success -> icalResult.value.size
                else -> 0
            }

            comparison[name] = Pair(rfcEvents.size, icalCount)

            if (rfcEvents.size != icalCount) {
                mismatches.add("$name: IcsParserService=${rfcEvents.size}, ICalParser=$icalCount")
            }
        }

        println("\n=== Event Count Comparison ===")
        println("Calendars with same count: ${comparison.size - mismatches.size}/${comparison.size}")

        if (mismatches.isNotEmpty()) {
            println("Mismatches (may be due to CANCELLED events):")
            mismatches.forEach { println("  $it") }
        }

        // Counts differ where a feed has CANCELLED events, which IcsParserService drops; under
        // 20% of feeds may differ
        val mismatchRate = mismatches.size.toFloat() / comparison.size
        assertTrue(
            "Mismatch rate should be < 20% (actual: ${(mismatchRate * 100).toInt()}%)",
            mismatchRate < 0.20
        )
    }

    @Test
    fun `COMPARE - Both parsers extract same UIDs`() {
        for ((name, content) in downloadedCalendars) {
            val rfcEvents = IcsParserService.parseIcsContent(content, CALENDAR_ID, SUBSCRIPTION_ID)
            val rfcUids = rfcEvents.map { it.uid }.toSet()

            val icalResult = icalParser.parseAllEvents(content)
            val icalUids = when (icalResult) {
                is org.onekash.icaldav.model.ParseResult.Success ->
                    icalResult.value.map { it.uid }.toSet()
                else -> emptySet()
            }

            // At most 10% of IcsParserService's UIDs may be missing from ICalParser's
            val missingInIcal = rfcUids - icalUids
            val missingInRfc = icalUids - rfcUids

            assertTrue(
                "$name: Too many UIDs missing in ICalParser: $missingInIcal",
                missingInIcal.size <= rfcUids.size * 0.1 // Allow 10% difference
            )
        }
    }

    @Test
    fun `COMPARE - Both parsers extract same titles`() {
        for ((name, content) in downloadedCalendars) {
            val rfcEvents = IcsParserService.parseIcsContent(content, CALENDAR_ID, SUBSCRIPTION_ID)
            val rfcTitles = rfcEvents.map { it.uid to it.title }.toMap()

            val icalResult = icalParser.parseAllEvents(content)
            if (icalResult is org.onekash.icaldav.model.ParseResult.Success) {
                val icalEvents = icalResult.value.map { icalEvent ->
                    ICalEventMapper.toEntity(
                        icalEvent = icalEvent,
                        rawIcal = null,
                        calendarId = CALENDAR_ID,
                        caldavUrl = null,
                        etag = null
                    ).event
                }
                val icalTitles = icalEvents.map { it.uid to it.title }.toMap()

                // Titles must match for every UID both return
                for ((uid, rfcTitle) in rfcTitles) {
                    val icalTitle = icalTitles[uid]
                    if (icalTitle != null) {
                        assertEquals(
                            "$name: Title mismatch for UID $uid",
                            rfcTitle,
                            icalTitle
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `COMPARE - Both parsers handle all-day dates identically`() {
        for ((name, content) in downloadedCalendars) {
            val rfcEvents = IcsParserService.parseIcsContent(content, CALENDAR_ID, SUBSCRIPTION_ID)
            val rfcByUid = rfcEvents.associateBy { it.uid }

            val icalResult = icalParser.parseAllEvents(content)
            if (icalResult is org.onekash.icaldav.model.ParseResult.Success) {
                val icalEvents = icalResult.value.map { icalEvent ->
                    ICalEventMapper.toEntity(
                        icalEvent = icalEvent,
                        rawIcal = null,
                        calendarId = CALENDAR_ID,
                        caldavUrl = null,
                        etag = null
                    ).event
                }
                val icalByUid = icalEvents.associateBy { it.uid }

                for ((uid, rfcEvent) in rfcByUid) {
                    val icalEvent = icalByUid[uid] ?: continue

                    // isAllDay flag should match
                    assertEquals(
                        "$name ($uid): isAllDay mismatch",
                        rfcEvent.isAllDay,
                        icalEvent.isAllDay
                    )

                    // All-day events must have the same startTs
                    if (rfcEvent.isAllDay) {
                        assertEquals(
                            "$name ($uid): startTs mismatch for all-day event",
                            rfcEvent.startTs,
                            icalEvent.startTs
                        )
                    }
                }
            }
        }
    }

    // ========== Edge Case Tests ==========

    @Test
    fun `EDGE - Unicode titles parsed correctly by both parsers`() {
        // Non-Latin feeds; those that didn't download are skipped, but at least 2 must run
        val unicodeCalendars = listOf(
            "JapanHolidays.ics",     // Japanese
            "ChinaHolidays.ics",     // Chinese
            "BulgarianHolidays.ics", // Cyrillic
            "SouthKoreaHolidays.ics" // Korean
        )

        var testedCount = 0
        for (name in unicodeCalendars) {
            val content = downloadedCalendars[name] ?: continue

            val rfcEvents = IcsParserService.parseIcsContent(content, CALENDAR_ID, SUBSCRIPTION_ID)
            val icalResult = icalParser.parseAllEvents(content)

            if (icalResult is org.onekash.icaldav.model.ParseResult.Success) {
                val icalEvents = icalResult.value

                // Both should have events
                assertTrue("$name: IcsParserService should have events", rfcEvents.isNotEmpty())
                assertTrue("$name: ICalParser should have events", icalEvents.isNotEmpty())

                // Titles must not be blank
                rfcEvents.forEach { event ->
                    assertTrue(
                        "$name: RFC title should not be empty",
                        event.title.isNotBlank()
                    )
                }

                icalEvents.forEach { event ->
                    assertTrue(
                        "$name: iCal title should not be empty",
                        event.summary?.isNotBlank() == true
                    )
                }
                testedCount++
            }
        }

        assertTrue("Should test at least 2 Unicode calendars", testedCount >= 2)
    }

    @Test
    fun `EDGE - Escaped characters handled by both parsers`() {
        for ((name, content) in downloadedCalendars) {
            val rfcEvents = IcsParserService.parseIcsContent(content, CALENDAR_ID, SUBSCRIPTION_ID)

            // Descriptions are where escaped characters are likely
            val eventsWithDesc = rfcEvents.filter { !it.description.isNullOrBlank() }

            eventsWithDesc.forEach { event ->
                // No raw \n (without a real newline) or \, may remain
                assertFalse(
                    "$name: Description should not contain raw \\n",
                    event.description?.contains("\\n") == true && !event.description!!.contains("\n")
                )
                assertFalse(
                    "$name: Description should not contain raw \\,",
                    event.description?.contains("\\,") == true
                )
            }
        }
    }

    @Test
    fun `EDGE - Long descriptions handled without truncation`() {
        for ((name, content) in downloadedCalendars) {
            val rfcEvents = IcsParserService.parseIcsContent(content, CALENDAR_ID, SUBSCRIPTION_ID)
            val icalResult = icalParser.parseAllEvents(content)

            if (icalResult is org.onekash.icaldav.model.ParseResult.Success) {
                val rfcByUid = rfcEvents.associateBy { it.uid }
                val icalByUid = icalResult.value.associateBy { it.uid }

                for ((uid, rfcEvent) in rfcByUid) {
                    val icalEvent = icalByUid[uid] ?: continue

                    val rfcDescLen = rfcEvent.description?.length ?: 0
                    val icalDescLen = icalEvent.description?.length ?: 0

                    // Non-empty description lengths must be within 10 characters, allowing for
                    // whitespace differences
                    if (rfcDescLen > 0 && icalDescLen > 0) {
                        assertTrue(
                            "$name ($uid): Description length mismatch (RFC=$rfcDescLen, iCal=$icalDescLen)",
                            kotlin.math.abs(rfcDescLen - icalDescLen) <= 10
                        )
                    }
                }
            }
        }
    }

    // ========== Feature Parity Tests ==========

    @Test
    fun `FEATURE - ICalParser provides additional properties not in IcsParserService`() {
        var calendarsWithCategories = 0
        var calendarsWithClass = 0

        for ((name, content) in downloadedCalendars) {
            val icalResult = icalParser.parseAllEvents(content)

            if (icalResult is org.onekash.icaldav.model.ParseResult.Success) {
                val events = icalResult.value

                // Counts the calendars with CATEGORIES; at least one must have them
                if (events.any { it.categories.isNotEmpty() }) {
                    calendarsWithCategories++
                }

                // Counts the calendars with CLASS (printed, not asserted)
                if (events.any { it.classification != null }) {
                    calendarsWithClass++
                }
            }
        }

        println("\n=== Additional Properties Available ===")
        println("Calendars with CATEGORIES: $calendarsWithCategories")
        println("Calendars with CLASS: $calendarsWithClass")

        // Holiday calendars typically have CATEGORIES:Holidays
        assertTrue(
            "At least some calendars should have CATEGORIES",
            calendarsWithCategories > 0
        )
    }

    @Test
    fun `FEATURE - ICalParser provides importId for database deduplication`() {
        for ((name, content) in downloadedCalendars) {
            val icalResult = icalParser.parseAllEvents(content)

            if (icalResult is org.onekash.icaldav.model.ParseResult.Success) {
                val events = icalResult.value

                events.forEach { event ->
                    // Every event has an importId
                    assertNotNull(
                        "$name: ${event.summary} - importId should be generated",
                        event.importId
                    )

                    // Without a RECURRENCE-ID, importId equals the UID
                    if (event.recurrenceId == null) {
                        assertEquals(
                            "$name: importId should equal UID for non-exception events",
                            event.uid,
                            event.importId
                        )
                    }
                }
            }
        }
    }

    // ========== Performance Test ==========

    @Test
    fun `PERF - Compare parsing speed`() {
        val iterations = 3
        var rfcTotalMs = 0L
        var icalTotalMs = 0L

        repeat(iterations) {
            for ((_, content) in downloadedCalendars) {
                // Time IcsParserService
                val rfcStart = System.currentTimeMillis()
                IcsParserService.parseIcsContent(content, CALENDAR_ID, SUBSCRIPTION_ID)
                rfcTotalMs += System.currentTimeMillis() - rfcStart

                // Time ICalParser
                val icalStart = System.currentTimeMillis()
                icalParser.parseAllEvents(content)
                icalTotalMs += System.currentTimeMillis() - icalStart
            }
        }

        val totalParses = downloadedCalendars.size * iterations
        println("\n=== Performance Comparison ===")
        println("Total parses: $totalParses")
        println("IcsParserService: ${rfcTotalMs}ms total, ${rfcTotalMs / totalParses}ms avg")
        println("ICalParser: ${icalTotalMs}ms total, ${icalTotalMs / totalParses}ms avg")

        // IcsParserService runs the same parse plus mapping, so this 5x bound only catches a
        // direct parse that is far slower than the wrapped one
        assertTrue(
            "ICalParser should not be more than 5x slower",
            icalTotalMs < rfcTotalMs * 5
        )
    }

    // ========== Test with Existing Fixtures ==========

    @Test
    fun `FIXTURE - Parse existing Brazil holidays fixture with both parsers`() {
        val content = javaClass.classLoader?.getResourceAsStream("ics/BrazilHolidays.ics")
            ?.bufferedReader()?.readText() ?: return

        val rfcEvents = IcsParserService.parseIcsContent(content, CALENDAR_ID, SUBSCRIPTION_ID)
        val icalResult = icalParser.parseAllEvents(content)

        assertTrue("IcsParserService should parse fixture", rfcEvents.isNotEmpty())
        assertTrue("ICalParser should parse fixture", icalResult is org.onekash.icaldav.model.ParseResult.Success)

        if (icalResult is org.onekash.icaldav.model.ParseResult.Success) {
            println("Brazil holidays: RFC=${rfcEvents.size}, iCal=${icalResult.value.size}")
        }
    }

    @Test
    fun `FIXTURE - Parse existing German holidays fixture with both parsers`() {
        val content = javaClass.classLoader?.getResourceAsStream("ics/GermanHolidays.ics")
            ?.bufferedReader()?.readText() ?: return

        val rfcEvents = IcsParserService.parseIcsContent(content, CALENDAR_ID, SUBSCRIPTION_ID)
        val icalResult = icalParser.parseAllEvents(content)

        assertTrue("IcsParserService should parse fixture", rfcEvents.isNotEmpty())
        assertTrue("ICalParser should parse fixture", icalResult is org.onekash.icaldav.model.ParseResult.Success)

        if (icalResult is org.onekash.icaldav.model.ParseResult.Success) {
            println("German holidays: RFC=${rfcEvents.size}, iCal=${icalResult.value.size}")
        }
    }

    @Test
    fun `FIXTURE - Parse existing Japan holidays fixture with both parsers`() {
        val content = javaClass.classLoader?.getResourceAsStream("ics/JapanHolidays.ics")
            ?.bufferedReader()?.readText() ?: return

        val rfcEvents = IcsParserService.parseIcsContent(content, CALENDAR_ID, SUBSCRIPTION_ID)
        val icalResult = icalParser.parseAllEvents(content)

        assertTrue("IcsParserService should parse fixture", rfcEvents.isNotEmpty())
        assertTrue("ICalParser should parse fixture", icalResult is org.onekash.icaldav.model.ParseResult.Success)

        if (icalResult is org.onekash.icaldav.model.ParseResult.Success) {
            println("Japan holidays: RFC=${rfcEvents.size}, iCal=${icalResult.value.size}")

            // Japanese characters survive the parse (a SUMMARY char above U+3000)
            val hasJapanese = icalResult.value.any { event ->
                event.summary?.any { it.code > 0x3000 } == true
            }
            assertTrue("Japanese characters should be preserved", hasJapanese)
        }
    }

    // ========== Helper Classes ==========

    data class ParseResult(
        val eventCount: Int,
        val success: Boolean,
        val error: String?
    )
}
