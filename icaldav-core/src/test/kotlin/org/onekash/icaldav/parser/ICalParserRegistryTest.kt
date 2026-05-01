package org.onekash.icaldav.parser

import net.fortuna.ical4j.model.TimeZone
import net.fortuna.ical4j.model.TimeZoneRegistry
import net.fortuna.ical4j.model.TimeZoneRegistryImpl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.onekash.icaldav.model.ParseResult
import java.time.ZoneId
import java.time.zone.ZoneRules
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for configurable TimeZoneRegistry in ICalParser.
 *
 * Verifies that:
 * - Default constructor uses SimpleTimeZoneRegistry (Android-safe)
 * - Custom registry can be injected (JVM server use case)
 * - Parsing works correctly with both registry types
 */
@DisplayName("ICalParser Registry Configuration")
class ICalParserRegistryTest {

    // Sample iCalendar with embedded VTIMEZONE
    private val icsWithVTimezone = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VTIMEZONE
        TZID:America/New_York
        BEGIN:STANDARD
        DTSTART:19701101T020000
        RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU
        TZOFFSETFROM:-0400
        TZOFFSETTO:-0500
        TZNAME:EST
        END:STANDARD
        BEGIN:DAYLIGHT
        DTSTART:19700308T020000
        RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU
        TZOFFSETFROM:-0500
        TZOFFSETTO:-0400
        TZNAME:EDT
        END:DAYLIGHT
        END:VTIMEZONE
        BEGIN:VEVENT
        UID:test-vtimezone@example.com
        DTSTAMP:20260122T100000Z
        DTSTART;TZID=America/New_York:20260115T140000
        DTEND;TZID=America/New_York:20260115T150000
        SUMMARY:Meeting with embedded VTIMEZONE
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    // Sample iCalendar with TZID but no embedded VTIMEZONE
    private val icsWithTzidOnly = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:test-tzid-only@example.com
        DTSTAMP:20260122T100000Z
        DTSTART;TZID=America/Los_Angeles:20260120T090000
        DTEND;TZID=America/Los_Angeles:20260120T100000
        SUMMARY:Meeting with TZID only
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    // Sample all-day event
    private val icsAllDay = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:test-allday@example.com
        DTSTAMP:20260122T100000Z
        DTSTART;VALUE=DATE:20260125
        DTEND;VALUE=DATE:20260126
        SUMMARY:All Day Event
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    // Sample UTC event
    private val icsUtc = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:test-utc@example.com
        DTSTAMP:20260122T100000Z
        DTSTART:20260122T140000Z
        DTEND:20260122T150000Z
        SUMMARY:UTC Meeting
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    @Nested
    @DisplayName("Default Constructor")
    inner class DefaultConstructorTests {

        @Test
        @DisplayName("parses events with embedded VTIMEZONE")
        fun `default parser parses events with embedded VTIMEZONE`() {
            val parser = ICalParser()
            val result = parser.parseAllEvents(icsWithVTimezone)

            assertTrue(result is ParseResult.Success)
            val events = (result as ParseResult.Success).value
            assertEquals(1, events.size)
            assertEquals("test-vtimezone@example.com", events[0].uid)
            assertEquals("America/New_York", events[0].dtStart.timezone?.id)
        }

        @Test
        @DisplayName("parses events with TZID only (no embedded VTIMEZONE)")
        fun `default parser parses events with TZID only`() {
            val parser = ICalParser()
            val result = parser.parseAllEvents(icsWithTzidOnly)

            assertTrue(result is ParseResult.Success)
            val events = (result as ParseResult.Success).value
            assertEquals(1, events.size)
            assertEquals("test-tzid-only@example.com", events[0].uid)
            assertEquals("America/Los_Angeles", events[0].dtStart.timezone?.id)
        }

        @Test
        @DisplayName("parses all-day events correctly")
        fun `default parser parses all-day events`() {
            val parser = ICalParser()
            val result = parser.parseAllEvents(icsAllDay)

            assertTrue(result is ParseResult.Success)
            val events = (result as ParseResult.Success).value
            assertEquals(1, events.size)
            assertTrue(events[0].isAllDay)
            assertEquals("20260125", events[0].dtStart.toDayCode())
        }

        @Test
        @DisplayName("parses UTC events correctly")
        fun `default parser parses UTC events`() {
            val parser = ICalParser()
            val result = parser.parseAllEvents(icsUtc)

            assertTrue(result is ParseResult.Success)
            val events = (result as ParseResult.Success).value
            assertEquals(1, events.size)
            assertEquals("test-utc@example.com", events[0].uid)
        }
    }

    @Nested
    @DisplayName("Custom Registry")
    inner class CustomRegistryTests {

        @Test
        @DisplayName("createWithFullRegistry provides TimeZoneRegistryImpl for JVM servers")
        fun `createWithFullRegistry works`() {
            // This test verifies JVM server use case
            // On Android, createWithFullRegistry() would fail at runtime
            val parser = ICalParser.createWithFullRegistry()

            val result = parser.parseAllEvents(icsWithVTimezone)
            assertTrue(result is ParseResult.Success)
            val events = (result as ParseResult.Success).value
            assertEquals(1, events.size)
            assertEquals("America/New_York", events[0].dtStart.timezone?.id)
        }

        @Test
        @DisplayName("accepts TimeZoneRegistryImpl via constructor for JVM servers")
        fun `parser accepts TimeZoneRegistryImpl directly`() {
            // Direct constructor usage (requires ical4j dependency in caller)
            val registry = TimeZoneRegistryImpl()
            val parser = ICalParser(registry)

            val result = parser.parseAllEvents(icsWithVTimezone)
            assertTrue(result is ParseResult.Success)
            val events = (result as ParseResult.Success).value
            assertEquals(1, events.size)
        }

        @Test
        @DisplayName("TimeZoneRegistryImpl provides richer timezone handling")
        fun `TimeZoneRegistryImpl provides timezone objects`() {
            val registry = TimeZoneRegistryImpl()

            // TimeZoneRegistryImpl returns actual TimeZone objects
            val tz = registry.getTimeZone("America/New_York")
            assertNotNull(tz, "TimeZoneRegistryImpl should return TimeZone objects")
            assertEquals("America/New_York", tz.id)
        }

        @Test
        @DisplayName("SimpleTimeZoneRegistry returns null for getTimeZone")
        fun `SimpleTimeZoneRegistry returns null for getTimeZone`() {
            val registry = SimpleTimeZoneRegistry()

            // SimpleTimeZoneRegistry returns null (uses embedded VTIMEZONE)
            val tz = registry.getTimeZone("America/New_York")
            assertEquals(null, tz, "SimpleTimeZoneRegistry should return null")
        }

        @Test
        @DisplayName("custom mock registry can be used for testing")
        fun `parser accepts mock registry for testing`() {
            // Create a mock registry for testing
            val mockRegistry = object : TimeZoneRegistry {
                override fun getTimeZone(id: String?): TimeZone? = null
                override fun register(timezone: TimeZone?) {}
                override fun register(timezone: TimeZone?, update: Boolean) {}
                override fun clear() {}
                override fun getZoneRules(): Map<String, ZoneRules> = emptyMap()
                override fun getZoneId(tzId: String?): ZoneId? =
                    if (tzId != null) ZoneId.of(tzId) else null
                override fun getTzId(zoneId: String?): String? = zoneId
            }

            // Verify parser can be created with custom registry and parsing works
            val parser = ICalParser(mockRegistry)
            val result = parser.parseAllEvents(icsWithTzidOnly)

            // The parser should successfully parse the data
            assertTrue(result is ParseResult.Success, "Parsing should succeed with custom registry")
            val events = (result as ParseResult.Success).value
            assertEquals(1, events.size)
        }
    }

    @Nested
    @DisplayName("Multiple Parser Instances")
    inner class MultipleInstanceTests {

        @Test
        @DisplayName("multiple parsers with different registries work independently")
        fun `multiple parsers work independently`() {
            val simpleParser = ICalParser(SimpleTimeZoneRegistry())
            val fullParser = ICalParser(TimeZoneRegistryImpl())

            // Both should parse the same data correctly
            val simpleResult = simpleParser.parseAllEvents(icsWithVTimezone)
            val fullResult = fullParser.parseAllEvents(icsWithVTimezone)

            assertTrue(simpleResult is ParseResult.Success)
            assertTrue(fullResult is ParseResult.Success)

            val simpleEvents = (simpleResult as ParseResult.Success).value
            val fullEvents = (fullResult as ParseResult.Success).value

            assertEquals(simpleEvents[0].uid, fullEvents[0].uid)
            assertEquals(simpleEvents[0].summary, fullEvents[0].summary)
            assertEquals(simpleEvents[0].dtStart.timezone?.id, fullEvents[0].dtStart.timezone?.id)
        }

        @Test
        @DisplayName("thread safety with multiple parsers")
        fun `multiple parsers are thread safe`() {
            val parsers = (1..10).map { ICalParser() }
            val results = mutableListOf<ParseResult<*>>()

            // Parse concurrently
            val threads = parsers.map { parser ->
                Thread {
                    synchronized(results) {
                        results.add(parser.parseAllEvents(icsWithVTimezone))
                    }
                }
            }

            threads.forEach { it.start() }
            threads.forEach { it.join() }

            // All should succeed
            assertEquals(10, results.size)
            results.forEach { result ->
                assertTrue(result is ParseResult.Success, "All parsing should succeed")
            }
        }
    }

    @Nested
    @DisplayName("Registry Comparison")
    inner class RegistryComparisonTests {

        @Test
        @DisplayName("both registries parse embedded VTIMEZONE identically")
        fun `both registries parse embedded VTIMEZONE identically`() {
            val simpleParser = ICalParser(SimpleTimeZoneRegistry())
            val fullParser = ICalParser(TimeZoneRegistryImpl())

            val simpleResult = simpleParser.parseAllEvents(icsWithVTimezone)
            val fullResult = fullParser.parseAllEvents(icsWithVTimezone)

            assertTrue(simpleResult is ParseResult.Success)
            assertTrue(fullResult is ParseResult.Success)

            val simpleEvent = (simpleResult as ParseResult.Success).value[0]
            val fullEvent = (fullResult as ParseResult.Success).value[0]

            // Core properties should match
            assertEquals(simpleEvent.uid, fullEvent.uid)
            assertEquals(simpleEvent.summary, fullEvent.summary)
            assertEquals(simpleEvent.dtStart.timezone?.id, fullEvent.dtStart.timezone?.id)
            assertEquals(simpleEvent.dtEnd?.timezone?.id, fullEvent.dtEnd?.timezone?.id)
        }

        @Test
        @DisplayName("both registries parse all-day events identically")
        fun `both registries parse all-day events identically`() {
            val simpleParser = ICalParser(SimpleTimeZoneRegistry())
            val fullParser = ICalParser(TimeZoneRegistryImpl())

            val simpleResult = simpleParser.parseAllEvents(icsAllDay)
            val fullResult = fullParser.parseAllEvents(icsAllDay)

            val simpleEvent = (simpleResult as ParseResult.Success).value[0]
            val fullEvent = (fullResult as ParseResult.Success).value[0]

            assertEquals(simpleEvent.isAllDay, fullEvent.isAllDay)
            assertEquals(simpleEvent.dtStart.toDayCode(), fullEvent.dtStart.toDayCode())
        }

        @Test
        @DisplayName("both registries parse UTC events identically")
        fun `both registries parse UTC events identically`() {
            val simpleParser = ICalParser(SimpleTimeZoneRegistry())
            val fullParser = ICalParser(TimeZoneRegistryImpl())

            val simpleResult = simpleParser.parseAllEvents(icsUtc)
            val fullResult = fullParser.parseAllEvents(icsUtc)

            val simpleEvent = (simpleResult as ParseResult.Success).value[0]
            val fullEvent = (fullResult as ParseResult.Success).value[0]

            assertEquals(simpleEvent.dtStart.timestamp, fullEvent.dtStart.timestamp)
        }
    }
}
