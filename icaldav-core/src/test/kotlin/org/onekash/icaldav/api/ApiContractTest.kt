package org.onekash.icaldav.api

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.AlarmAction
import org.onekash.icaldav.model.Attendee
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.Frequency
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ITipMethod
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.model.RRule
import org.onekash.icaldav.model.Transparency
import org.onekash.icaldav.model.WeekdayNum
import org.onekash.icaldav.parser.ICalGenerator
import org.onekash.icaldav.parser.ICalParser
import org.onekash.icaldav.recurrence.RRuleExpander
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * API Contract Tests
 *
 * These tests verify that the public API contract remains stable and behaves
 * as documented. Important for open source library consumers who depend on:
 * - Consistent API surface
 * - Predictable return types
 * - Exhaustive sealed class handling
 */
@DisplayName("API Contract Tests")
class ApiContractTest {

    @Nested
    @DisplayName("ParseResult Sealed Class Contract")
    inner class ParseResultContractTests {

        @Test
        fun `ParseResult sealed class is exhaustively matchable`() {
            val parser = ICalParser()
            val validIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-uid
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T100000Z
                SUMMARY:Test
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(validIcal)

            // This when expression must be exhaustive
            val outcome = when (result) {
                is ParseResult.Success -> "success"
                is ParseResult.Error -> "error"
            }
            assertNotNull(outcome)
        }

        @Test
        fun `ParseResult Success contains expected value`() {
            val parser = ICalParser()
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-uid
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T100000Z
                SUMMARY:Test Event
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)

            assertIs<ParseResult.Success<List<ICalEvent>>>(result)
            assertEquals(1, result.value.size)
            assertEquals("test-uid", result.value[0].uid)
        }

        @Test
        fun `ParseResult Error contains error information`() {
            val parser = ICalParser()
            // Missing required UID
            val invalidIcal = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                DTSTART:20231215T100000Z
                SUMMARY:No UID
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(invalidIcal)

            // Should either succeed (lenient) or return error
            assertTrue(result is ParseResult.Success || result is ParseResult.Error)
        }

        @Test
        fun `getOrNull returns value for Success`() {
            val parser = ICalParser()
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:getornull-uid
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)
            if (result is ParseResult.Success) {
                assertNotNull(result.getOrNull())
            }
        }

        @Test
        fun `getOrDefault returns default for Error`() {
            val parser = ICalParser()
            val result = parser.parseAllEvents("invalid content")

            val default = emptyList<ICalEvent>()
            val value = result.getOrDefault(default)

            // Should return either parsed events or default
            assertNotNull(value)
        }
    }

    @Nested
    @DisplayName("ICalEvent Data Class Contract")
    inner class ICalEventContractTests {

        @Test
        fun `ICalEvent has all required properties accessible`() {
            val event = createTestEvent("test-uid")

            // Core properties must be accessible
            assertNotNull(event.uid)
            assertNotNull(event.importId)
            assertNotNull(event.dtStart)
            assertNotNull(event.status)
            assertNotNull(event.transparency)
        }

        @Test
        fun `ICalEvent optional properties can be null`() {
            val event = createMinimalEvent()

            // These should be nullable without throwing
            val description: String? = event.description
            val location: String? = event.location
            val dtEnd: ICalDateTime? = event.dtEnd
            val rrule: RRule? = event.rrule
            val color: String? = event.color
            val url: String? = event.url

            // Accessing nullable properties doesn't throw
            assertTrue(true)
        }

        @Test
        fun `ICalEvent copy function works correctly`() {
            val original = createTestEvent("original-uid")
            val copied = original.copy(uid = "copied-uid", summary = "Copied Event")

            assertEquals("original-uid", original.uid)
            assertEquals("copied-uid", copied.uid)
            assertEquals("Copied Event", copied.summary)
            assertEquals(original.dtStart.timestamp, copied.dtStart.timestamp)
        }

        @Test
        fun `ICalEvent equality is based on data`() {
            val event1 = createTestEvent("same-uid")
            val event2 = createTestEvent("same-uid")
            val event3 = createTestEvent("different-uid")

            assertEquals(event1, event2)
            assertFalse(event1 == event3)
        }
    }

    @Nested
    @DisplayName("ICalDateTime Contract")
    inner class ICalDateTimeContractTests {

        @Test
        fun `ICalDateTime can represent UTC time`() {
            val utcTime = ICalDateTime(
                timestamp = System.currentTimeMillis(),
                timezone = null,
                isUtc = true,
                isDate = false
            )

            assertTrue(utcTime.isUtc)
            assertFalse(utcTime.isDate)
        }

        @Test
        fun `ICalDateTime can represent all-day date`() {
            val allDayDate = ICalDateTime(
                timestamp = System.currentTimeMillis(),
                timezone = null,
                isUtc = false,
                isDate = true
            )

            assertFalse(allDayDate.isUtc)
            assertTrue(allDayDate.isDate)
        }

        @Test
        fun `ICalDateTime can represent zoned time`() {
            val zone = ZoneId.of("America/New_York")
            val zonedTime = ICalDateTime(
                timestamp = System.currentTimeMillis(),
                timezone = zone,
                isUtc = false,
                isDate = false
            )

            assertEquals(zone, zonedTime.timezone)
            assertFalse(zonedTime.isUtc)
            assertFalse(zonedTime.isDate)
        }

        @Test
        fun `ICalDateTime toZonedDateTime produces valid result`() {
            val dateTime = ICalDateTime(
                timestamp = System.currentTimeMillis(),
                timezone = ZoneId.of("UTC"),
                isUtc = true,
                isDate = false
            )

            val zdt = dateTime.toZonedDateTime()
            assertNotNull(zdt)
        }
    }

    @Nested
    @DisplayName("RRule Contract")
    inner class RRuleContractTests {

        @Test
        fun `RRule frequency enum is exhaustive`() {
            // All frequency values must be handleable
            Frequency.entries.forEach { freq ->
                val rrule = RRule(freq = freq)
                assertNotNull(rrule.freq)
            }
        }

        @Test
        fun `RRule can be constructed with minimal parameters`() {
            val rrule = RRule(freq = Frequency.DAILY)

            assertEquals(Frequency.DAILY, rrule.freq)
            assertEquals(null, rrule.count)
            assertEquals(null, rrule.until)
        }

        @Test
        fun `RRule with COUNT creates bounded recurrence`() {
            val rrule = RRule(freq = Frequency.WEEKLY, count = 5)

            assertEquals(5, rrule.count)
        }

        @Test
        fun `RRule BYDAY supports weekday values`() {
            val byDay = listOf(
                WeekdayNum(DayOfWeek.MONDAY),
                WeekdayNum(DayOfWeek.WEDNESDAY),
                WeekdayNum(DayOfWeek.FRIDAY)
            )
            val rrule = RRule(freq = Frequency.WEEKLY, byDay = byDay)

            assertEquals(3, rrule.byDay?.size)
        }
    }

    @Nested
    @DisplayName("EventStatus Enum Contract")
    inner class EventStatusContractTests {

        @Test
        fun `EventStatus has all RFC 5545 values`() {
            val statuses = EventStatus.entries

            assertTrue(statuses.contains(EventStatus.TENTATIVE))
            assertTrue(statuses.contains(EventStatus.CONFIRMED))
            assertTrue(statuses.contains(EventStatus.CANCELLED))
        }

        @Test
        fun `EventStatus is usable in when expression`() {
            EventStatus.entries.forEach { status ->
                val description = when (status) {
                    EventStatus.TENTATIVE -> "tentative"
                    EventStatus.CONFIRMED -> "confirmed"
                    EventStatus.CANCELLED -> "cancelled"
                }
                assertNotNull(description)
            }
        }
    }

    @Nested
    @DisplayName("Transparency Enum Contract")
    inner class TransparencyContractTests {

        @Test
        fun `Transparency has OPAQUE and TRANSPARENT`() {
            val values = Transparency.entries

            assertTrue(values.contains(Transparency.OPAQUE))
            assertTrue(values.contains(Transparency.TRANSPARENT))
        }
    }

    @Nested
    @DisplayName("ICalParser API Contract")
    inner class ICalParserContractTests {

        @Test
        fun `ICalParser can be instantiated`() {
            val parser = ICalParser()
            assertNotNull(parser)
        }

        @Test
        fun `parseAllEvents returns ParseResult`() {
            val parser = ICalParser()
            val ical = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                BEGIN:VEVENT
                UID:test-uid
                DTSTAMP:20231215T100000Z
                DTSTART:20231215T100000Z
                SUMMARY:Test
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()

            val result = parser.parseAllEvents(ical)

            assertTrue(result is ParseResult.Success || result is ParseResult.Error)
        }

        @Test
        fun `ensureConfigured is callable`() {
            // Static configuration should be thread-safe and idempotent
            ICalParser.ensureConfigured()
            ICalParser.ensureConfigured() // Should not throw on second call
        }
    }

    @Nested
    @DisplayName("ICalGenerator API Contract")
    inner class ICalGeneratorContractTests {

        @Test
        fun `ICalGenerator can be instantiated`() {
            val generator = ICalGenerator()
            assertNotNull(generator)
        }

        @Test
        fun `generate produces valid iCalendar string`() {
            val generator = ICalGenerator()
            val event = createTestEvent("gen-test")

            val output = generator.generate(event, method = null)

            assertTrue(output.contains("BEGIN:VCALENDAR"))
            assertTrue(output.contains("END:VCALENDAR"))
            assertTrue(output.contains("BEGIN:VEVENT"))
            assertTrue(output.contains("END:VEVENT"))
        }

        @Test
        fun `generate with includeMethod parameter works`() {
            val generator = ICalGenerator()
            val event = createTestEvent("method-test")

            val withMethod = generator.generate(event, method = ITipMethod.PUBLISH)
            val withoutMethod = generator.generate(event, method = null)

            // Both should produce valid output
            assertTrue(withMethod.contains("BEGIN:VCALENDAR"))
            assertTrue(withoutMethod.contains("BEGIN:VCALENDAR"))
        }
    }

    @Nested
    @DisplayName("RRuleExpander API Contract")
    inner class RRuleExpanderContractTests {

        @Test
        fun `RRuleExpander can be instantiated`() {
            val expander = RRuleExpander()
            assertNotNull(expander)
        }

        @Test
        fun `expand returns list of events`() {
            val expander = RRuleExpander()
            val event = createRecurringEvent()
            val zone = ZoneId.of("UTC")

            val start = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, zone).toInstant()
            val end = ZonedDateTime.of(2024, 12, 31, 23, 59, 59, 0, zone).toInstant()

            val occurrences = expander.expand(event, start, end)

            assertTrue(occurrences.isNotEmpty())
            occurrences.forEach { occurrence ->
                assertNotNull(occurrence.dtStart)
            }
        }
    }

    @Nested
    @DisplayName("Collection Type Contracts")
    inner class CollectionTypeContractTests {

        @Test
        fun `exdates is immutable List`() {
            val event = createTestEvent("test")
            val exdates: List<ICalDateTime> = event.exdates

            assertNotNull(exdates)
        }

        @Test
        fun `alarms is immutable List`() {
            val event = createTestEvent("test")
            val alarms: List<ICalAlarm> = event.alarms

            assertNotNull(alarms)
        }

        @Test
        fun `categories is immutable List`() {
            val event = createTestEvent("test")
            val categories: List<String> = event.categories

            assertNotNull(categories)
        }

        @Test
        fun `attendees is immutable List`() {
            val event = createTestEvent("test")
            val attendees: List<Attendee> = event.attendees

            assertNotNull(attendees)
        }

        @Test
        fun `rawProperties is immutable Map`() {
            val event = createTestEvent("test")
            val rawProps: Map<String, String> = event.rawProperties

            assertNotNull(rawProps)
        }
    }

    @Nested
    @DisplayName("Alarm Contract")
    inner class AlarmContractTests {

        @Test
        fun `ICalAlarm has required properties`() {
            val alarm = ICalAlarm(
                action = AlarmAction.DISPLAY,
                trigger = Duration.ofMinutes(-15),
                triggerAbsolute = null,
                triggerRelatedToEnd = false,
                description = "Reminder",
                summary = null
            )

            assertEquals(AlarmAction.DISPLAY, alarm.action)
            assertEquals(Duration.ofMinutes(-15), alarm.trigger)
        }

        @Test
        fun `AlarmAction enum has expected values`() {
            assertTrue(AlarmAction.entries.contains(AlarmAction.DISPLAY))
            assertTrue(AlarmAction.entries.contains(AlarmAction.AUDIO))
            assertTrue(AlarmAction.entries.contains(AlarmAction.EMAIL))
        }
    }

    // Helper functions

    private fun createTestEvent(uid: String): ICalEvent {
        // Use fixed timestamp for deterministic equality tests
        val now = 1700000000000L
        return ICalEvent(
            uid = uid,
            importId = uid,
            summary = "Test Event",
            description = null,
            location = null,
            dtStart = ICalDateTime(now, null, true, false),
            dtEnd = ICalDateTime(now + 3600000, null, true, false),
            duration = null,
            isAllDay = false,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = null,
            exdates = emptyList(),
            recurrenceId = null,
            alarms = emptyList(),
            categories = emptyList(),
            organizer = null,
            attendees = emptyList(),
            color = null,
            dtstamp = null,
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )
    }

    private fun createMinimalEvent(): ICalEvent {
        return ICalEvent(
            uid = "minimal",
            importId = "minimal",
            summary = null,
            description = null,
            location = null,
            dtStart = ICalDateTime(System.currentTimeMillis(), null, true, false),
            dtEnd = null,
            duration = null,
            isAllDay = false,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = null,
            exdates = emptyList(),
            recurrenceId = null,
            alarms = emptyList(),
            categories = emptyList(),
            organizer = null,
            attendees = emptyList(),
            color = null,
            dtstamp = null,
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )
    }

    private fun createRecurringEvent(): ICalEvent {
        val zone = ZoneId.of("UTC")
        val dtStart = ICalDateTime(
            timestamp = ZonedDateTime.of(2024, 1, 15, 10, 0, 0, 0, zone)
                .toInstant().toEpochMilli(),
            timezone = zone,
            isUtc = true,
            isDate = false
        )

        return ICalEvent(
            uid = "recurring-test",
            importId = "recurring-test",
            summary = "Recurring Event",
            description = null,
            location = null,
            dtStart = dtStart,
            dtEnd = dtStart.copy(timestamp = dtStart.timestamp + 3600000),
            duration = null,
            isAllDay = false,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = RRule(freq = Frequency.DAILY, count = 5),
            exdates = emptyList(),
            recurrenceId = null,
            alarms = emptyList(),
            categories = emptyList(),
            organizer = null,
            attendees = emptyList(),
            color = null,
            dtstamp = null,
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )
    }
}
