package org.onekash.kashcal.data.ics

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Comprehensive comparison test between IcsParserService (current) and ICalParser (icaldav library).
 *
 * Downloads real holiday calendars from Thunderbird and compares parsing results.
 * This validates the migration approach before replacing the custom parser.
 *
 * Test categories:
 * 1. Pre-test: Verify IcsParserService handles all calendars
 * 2. Post-test: Verify ICalParser handles all calendars
 * 3. Comparison: Ensure both parsers produce equivalent results
 * 4. Edge cases: Test calendars with special features (Unicode, RRULE, etc.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsParserComparisonTest {

    companion object {
        private const val CALENDAR_ID = 1L
        private const val SUBSCRIPTION_ID = 1L
        private const val BASE_URL = "https://www.thunderbird.net/media/caldata/autogen/"

        /**
         * Comprehensive list of holiday calendars from Thunderbird.
         * Selected to cover:
         * - Different regions (Americas, Europe, Asia, Africa, Oceania)
         * - Different scripts (Latin, Cyrillic, Arabic, CJK, Hebrew)
         * - Different calendar features (all-day, multi-day, descriptions)
         *
         * Note: Some calendars may be empty or 404 depending on Thunderbird's current data.
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
         * Calendars known to be empty (valid ICS but no events).
         * These are Thunderbird data issues, not parser issues.
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

    // ========== Pre-Tests: IcsParserService (Current Implementation) ==========

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

        // All calendars should parse successfully (excluding known empty ones)
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

            // Holiday calendars should have mostly all-day events
            val allDayEvents = events.filter { it.isAllDay }
            assertTrue(
                "$name: Holiday calendar should have all-day events (found ${events.size} events)",
                allDayEvents.isNotEmpty()
            )

            // Verify all-day events have valid timestamps
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

    // ========== Post-Tests: ICalParser (icaldav Library) ==========

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

        // All calendars should parse successfully (excluding known empty ones)
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
                    )
                }

                assertTrue("$name: Should have events after mapping", events.isNotEmpty())

                // Verify mapped events have all required fields
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

        // Allow some mismatches due to CANCELLED event handling differences
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

            // UIDs should be identical (or nearly so)
            val missingInIcal = rfcUids - icalUids
            val missingInRfc = icalUids - rfcUids

            // IcsParserService skips events without SUMMARY, so some UIDs may be missing
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
                    )
                }
                val icalTitles = icalEvents.map { it.uid to it.title }.toMap()

                // Compare titles for matching UIDs
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
                    )
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

                    // For all-day events, startTs should match (both use UTC midnight)
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
        // Test calendars with non-Latin scripts (use available ones)
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

                // Titles should not be empty or garbled
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

            // Check for any events with descriptions (likely to have escaped chars)
            val eventsWithDesc = rfcEvents.filter { !it.description.isNullOrBlank() }

            eventsWithDesc.forEach { event ->
                // Should not contain raw escape sequences
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

                    // Compare description lengths
                    val rfcDescLen = rfcEvent.description?.length ?: 0
                    val icalDescLen = icalEvent.description?.length ?: 0

                    // Lengths should be similar (within 10 chars for whitespace differences)
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

                // Check for CATEGORIES (IcsParserService doesn't parse this)
                if (events.any { it.categories.isNotEmpty() }) {
                    calendarsWithCategories++
                }

                // Check for CLASS (IcsParserService parses this)
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
                    // importId should be generated
                    assertNotNull(
                        "$name: ${event.summary} - importId should be generated",
                        event.importId
                    )

                    // For regular events, importId should equal UID
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

        // ICalParser may be slower due to ical4j overhead, but should be reasonable
        // Allow up to 5x slower (still acceptable for background sync)
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

            // Check Japanese characters are preserved
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
