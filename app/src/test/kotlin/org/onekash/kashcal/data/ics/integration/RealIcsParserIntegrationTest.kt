package org.onekash.kashcal.data.ics.integration

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.ics.IcsParserService
import java.util.concurrent.TimeUnit

/**
 * Runs [IcsParserService] against 25 public holiday ICS feeds fetched live (list in
 * [THUNDERBIRD_CALENDARS], index at https://www.thunderbird.net/en-US/calendar/holidays/).
 *
 * The per-country tests assert each feed fetches, is valid ICS and yields at least one event.
 * The batch test needs at least 80% of feeds to parse and every parsed feed to yield an event.
 * The USA feed is checked for required fields, all-day events and unique UIDs, and three feeds
 * for a calendar name.
 *
 * Needs network access. The per-country and batch tests skip when the host HEAD probe fails;
 * the others pass without asserting when their fetch fails.
 *
 * Lives under `integration/`, so it runs only with `-Pintegration`:
 * `./gradlew :app:testDebugUnitTest -Pintegration --tests "*RealIcsParserIntegrationTest*"`
 */
class RealIcsParserIntegrationTest {

    private var networkAvailable = false

    companion object {
        private const val CALENDAR_ID = 1L
        private const val SUBSCRIPTION_ID = 1L

        // Holiday feed URLs (2025 URL format), picked for geographic spread.
        private val THUNDERBIRD_CALENDARS = listOf(
            // Americas
            "USA" to "https://www.thunderbird.net/media/caldata/autogen/USHolidays.ics",
            "Canada" to "https://www.thunderbird.net/media/caldata/autogen/CanadaHolidays.ics",
            "Brazil" to "https://www.thunderbird.net/media/caldata/autogen/BrazilHolidays.ics",
            "Mexico" to "https://www.thunderbird.net/media/caldata/autogen/MexicoHolidays.ics",
            "Argentina" to "https://www.thunderbird.net/media/caldata/autogen/ArgentinaHolidays.ics",

            // Europe
            "UK" to "https://www.thunderbird.net/media/caldata/autogen/UKHolidays.ics",
            "Germany" to "https://www.thunderbird.net/media/caldata/autogen/GermanHolidays.ics",
            "France" to "https://www.thunderbird.net/media/caldata/autogen/FrenchHolidays.ics",
            "Italy" to "https://www.thunderbird.net/media/caldata/autogen/ItalyHolidays.ics",
            "Spain" to "https://www.thunderbird.net/media/caldata/autogen/SpainHolidays.ics",
            "Netherlands" to "https://www.thunderbird.net/media/caldata/autogen/DutchHolidays.ics",
            "Poland" to "https://www.thunderbird.net/media/caldata/autogen/PolishHolidays.ics",
            "Sweden" to "https://www.thunderbird.net/media/caldata/autogen/SwedishHolidays.ics",

            // Asia Pacific
            "Japan" to "https://www.thunderbird.net/media/caldata/autogen/JapanHolidays.ics",
            "China" to "https://www.thunderbird.net/media/caldata/autogen/ChinaHolidays.ics",
            "India" to "https://www.thunderbird.net/media/caldata/autogen/IndiaHolidays.ics",
            "Australia" to "https://www.thunderbird.net/media/caldata/autogen/AustraliaHolidays.ics",
            "NewZealand" to "https://www.thunderbird.net/media/caldata/autogen/NewZealandHolidays.ics",
            "SouthKorea" to "https://www.thunderbird.net/media/caldata/autogen/SouthKoreaHolidays.ics",

            // Middle East & Africa
            "Israel" to "https://www.thunderbird.net/media/caldata/autogen/IsraelHolidays.ics",
            "SouthAfrica" to "https://www.thunderbird.net/media/caldata/autogen/SouthAfricaHolidays.ics",
            "Turkey" to "https://www.thunderbird.net/media/caldata/autogen/TurkeyHolidays.ics",

            // Other
            "Russia" to "https://www.thunderbird.net/media/caldata/autogen/RussianHolidays.ics",
            "Ukraine" to "https://www.thunderbird.net/media/caldata/autogen/UkraineHolidays.ics",
            "Switzerland" to "https://www.thunderbird.net/media/caldata/autogen/SwissHolidays.ics"
        )
    }

    private lateinit var httpClient: OkHttpClient

    @Before
    fun setup() {
        httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        // A failed HEAD to the feed host makes the tests calling assumeNetworkAvailable skip.
        networkAvailable = try {
            val request = Request.Builder()
                .url("https://www.thunderbird.net")
                .head()
                .build()
            httpClient.newCall(request).execute().isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    private fun assumeNetworkAvailable() {
        assumeTrue("Network not available - skipping test", networkAvailable)
    }

    // ========== Individual Country Tests ==========

    @Test
    fun `parse USA holidays`() {
        testCalendar("USA")
    }

    @Test
    fun `parse UK holidays`() {
        testCalendar("UK")
    }

    @Test
    fun `parse Germany holidays`() {
        testCalendar("Germany")
    }

    @Test
    fun `parse Japan holidays`() {
        testCalendar("Japan")
    }

    @Test
    fun `parse Australia holidays`() {
        testCalendar("Australia")
    }

    @Test
    fun `parse India holidays`() {
        testCalendar("India")
    }

    @Test
    fun `parse Brazil holidays`() {
        testCalendar("Brazil")
    }

    @Test
    fun `parse France holidays`() {
        testCalendar("France")
    }

    @Test
    fun `parse China holidays`() {
        testCalendar("China")
    }

    @Test
    fun `parse Israel holidays`() {
        testCalendar("Israel")
    }

    // ========== Batch Tests ==========

    @Test
    fun `parse all Thunderbird calendars successfully`() = runBlocking {
        assumeNetworkAvailable()

        val results = mutableMapOf<String, ParseResult>()

        for ((country, url) in THUNDERBIRD_CALENDARS) {
            results[country] = try {
                val content = fetchIcsContent(url)
                if (content == null) {
                    ParseResult.FetchFailed
                } else if (!IcsParserService.isValidIcs(content)) {
                    ParseResult.InvalidFormat
                } else {
                    val events = IcsParserService.parseIcsContent(content, CALENDAR_ID, SUBSCRIPTION_ID)
                    ParseResult.Success(events.size)
                }
            } catch (e: Exception) {
                ParseResult.ParseError(e.message ?: "Unknown error")
            }
        }

        // Printed per feed for inspection.
        println("\n===== Thunderbird Calendar Parse Results =====")
        results.forEach { (country, result) ->
            when (result) {
                is ParseResult.Success -> println("✓ $country: ${result.eventCount} events")
                is ParseResult.FetchFailed -> println("✗ $country: Fetch failed")
                is ParseResult.InvalidFormat -> println("✗ $country: Invalid ICS format")
                is ParseResult.ParseError -> println("✗ $country: ${result.message}")
            }
        }
        println("==============================================\n")

        val successCount = results.values.count { it is ParseResult.Success }
        val totalCount = results.size
        val successRate = successCount.toDouble() / totalCount

        println("Success rate: $successCount/$totalCount (${(successRate * 100).toInt()}%)")

        assertTrue(
            "Expected at least 80% success rate, got ${(successRate * 100).toInt()}%",
            successRate >= 0.8
        )

        // Every feed that parsed must yield at least one event.
        results.values.filterIsInstance<ParseResult.Success>().forEach { result ->
            assertTrue("Calendar should have at least 1 event", result.eventCount > 0)
        }
    }

    @Test
    fun `all parsed events have required fields`() = runBlocking {
        // The USA feed is the reference; a failed fetch passes without asserting.
        val content = fetchIcsContent(THUNDERBIRD_CALENDARS.first { it.first == "USA" }.second)
            ?: return@runBlocking

        val events = IcsParserService.parseIcsContent(content, CALENDAR_ID, SUBSCRIPTION_ID)

        assertTrue("Should have events", events.isNotEmpty())

        events.forEach { event ->
            assertNotNull("Event should have UID", event.uid)
            assertTrue("UID should not be blank", event.uid.isNotBlank())
            assertNotNull("Event should have title", event.title)
            assertTrue("Title should not be blank", event.title.isNotBlank())
            assertTrue("Start timestamp should be positive", event.startTs > 0)
            assertTrue("End timestamp should be >= start", event.endTs >= event.startTs)
            assertNotNull("Event should have calendar ID", event.calendarId)
            assertEquals("Calendar ID should match", CALENDAR_ID, event.calendarId)

            // Every event in a holiday feed is expected to be all-day.
            assertTrue("Holiday events should be all-day: ${event.title}", event.isAllDay)
        }
    }

    @Test
    fun `calendar names extracted correctly`() = runBlocking {
        val calendarsWithNames = listOf(
            "USA" to "https://www.thunderbird.net/media/caldata/autogen/UnitedStatesHolidays.ics",
            "Germany" to "https://www.thunderbird.net/media/caldata/autogen/GermanHolidays.ics",
            "Japan" to "https://www.thunderbird.net/media/caldata/autogen/JapaneseHolidays.ics"
        )

        for ((country, url) in calendarsWithNames) {
            val content = fetchIcsContent(url) ?: continue
            val calendarName = IcsParserService.getCalendarName(content)

            println("$country calendar name: $calendarName")

            // A feed that fails to fetch is skipped. getCalendarName takes NAME or X-WR-CALNAME,
            // else a non-blank PRODID.
            assertNotNull("$country should have calendar name", calendarName)
        }
    }

    @Test
    fun `events have unique UIDs within calendar`() = runBlocking {
        val content = fetchIcsContent(THUNDERBIRD_CALENDARS.first { it.first == "USA" }.second)
            ?: return@runBlocking

        val events = IcsParserService.parseIcsContent(content, CALENDAR_ID, SUBSCRIPTION_ID)
        val uids = events.map { it.uid }
        val uniqueUids = uids.toSet()

        assertEquals(
            "All UIDs should be unique",
            events.size,
            uniqueUids.size
        )
    }

    @Test
    fun `recurring events have RRULE`() = runBlocking {
        // Print-only: logs any RRULE events in the first 10 feeds and asserts nothing.
        var foundRecurring = false

        for ((country, url) in THUNDERBIRD_CALENDARS.take(10)) {
            val content = fetchIcsContent(url) ?: continue
            val events = IcsParserService.parseIcsContent(content, CALENDAR_ID, SUBSCRIPTION_ID)

            val recurringEvents = events.filter { it.rrule != null }
            if (recurringEvents.isNotEmpty()) {
                foundRecurring = true
                println("$country has ${recurringEvents.size} recurring events")
                recurringEvents.take(3).forEach { event ->
                    println("  - ${event.title}: ${event.rrule}")
                }
            }
        }

        println("Found recurring events: $foundRecurring")
    }

    // ========== Helper Methods ==========

    private fun testCalendar(country: String) = runBlocking {
        assumeNetworkAvailable()

        val url = THUNDERBIRD_CALENDARS.first { it.first == country }.second
        val content = fetchIcsContent(url)

        assertNotNull("Failed to fetch $country calendar", content)
        assertTrue("$country calendar should be valid ICS", IcsParserService.isValidIcs(content!!))

        val events = IcsParserService.parseIcsContent(content, CALENDAR_ID, SUBSCRIPTION_ID)

        assertTrue("$country should have at least 1 event", events.isNotEmpty())
        println("$country: Parsed ${events.size} events")

        events.take(5).forEach { event ->
            println("  - ${event.title} (${if (event.isAllDay) "all-day" else "timed"})")
        }
    }

    private fun fetchIcsContent(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "text/calendar, */*")
                .header("User-Agent", "KashCal-Test/1.0")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()
            } else {
                println("HTTP ${response.code} for $url")
                null
            }
        } catch (e: Exception) {
            println("Error fetching $url: ${e.message}")
            null
        }
    }

    private sealed class ParseResult {
        data class Success(val eventCount: Int) : ParseResult()
        data object FetchFailed : ParseResult()
        data object InvalidFormat : ParseResult()
        data class ParseError(val message: String) : ParseResult()
    }
}