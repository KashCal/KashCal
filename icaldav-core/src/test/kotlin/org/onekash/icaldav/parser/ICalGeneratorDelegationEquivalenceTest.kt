package org.onekash.icaldav.parser

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.AlarmAction
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalAlarm
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ICalJournal
import org.onekash.icaldav.model.ICalTodo
import org.onekash.icaldav.model.ITipMethod
import org.onekash.icaldav.model.JournalStatus
import org.onekash.icaldav.model.TodoStatus
import org.onekash.icaldav.model.Transparency
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals

/**
 * Golden-snapshot tests locking the wire format of the three single-component
 * `ICalGenerator.generate(event|todo|journal, ...)` entry points.
 *
 * Purpose: these methods are being collapsed into delegators that route through
 * `generate(calendar: ICalCalendar, ...)`. The goldens are captured from the
 * PRE-refactor implementation with deterministic fixtures (pinned DTSTAMP,
 * `preserveDtstamp = true`, fixed ZoneId). After refactor, output must match
 * byte-for-byte.
 *
 * VTIMEZONE bodies are excluded from the golden because they're generated from
 * ZoneRules at runtime and covered exhaustively by VTimezoneGeneratorTest.
 * The snapshot captures only the VCALENDAR envelope + component body + the
 * VTIMEZONE block structure, via `normalize()` which strips the VTIMEZONE
 * inner content while preserving BEGIN/TZID/END markers.
 */
@DisplayName("ICalGenerator single-component delegation equivalence")
class ICalGeneratorDelegationEquivalenceTest {

    private val generator = ICalGenerator(
        prodId = "-//DelegationTest//EN",
        includeAppleExtensions = true
    )

    // ========== Fixtures ==========

    private fun nyEvent(
        uid: String = "evt-1",
        alarms: List<ICalAlarm> = emptyList(),
    ): ICalEvent {
        val zone = ZoneId.of("America/New_York")
        return ICalEvent(
            uid = uid,
            importId = uid,
            summary = "Event Summary",
            description = "Event Description",
            location = "Event Location",
            dtStart = ICalDateTime.fromZonedDateTime(ZonedDateTime.of(2024, 3, 6, 12, 0, 0, 0, zone)),
            dtEnd = ICalDateTime.fromZonedDateTime(ZonedDateTime.of(2024, 3, 6, 13, 0, 0, 0, zone)),
            duration = null,
            isAllDay = false,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = null,
            exdates = emptyList(),
            recurrenceId = null,
            alarms = alarms,
            categories = emptyList(),
            organizer = null,
            attendees = emptyList(),
            color = null,
            dtstamp = ICalDateTime.parse("20240101T000000Z"),
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )
    }

    private fun tokyoTodo(): ICalTodo {
        val zone = ZoneId.of("Asia/Tokyo")
        return ICalTodo(
            uid = "td-1",
            summary = "Todo Summary",
            status = TodoStatus.NEEDS_ACTION,
            dtstamp = ICalDateTime.parse("20240101T000000Z"),
            dtStart = ICalDateTime.fromZonedDateTime(ZonedDateTime.of(2024, 3, 6, 9, 0, 0, 0, zone)),
            due = ICalDateTime.fromZonedDateTime(ZonedDateTime.of(2024, 3, 6, 18, 0, 0, 0, zone))
        )
    }

    private fun londonJournal(): ICalJournal {
        val zone = ZoneId.of("Europe/London")
        return ICalJournal(
            uid = "jr-1",
            summary = "Journal Summary",
            status = JournalStatus.FINAL,
            dtstamp = ICalDateTime.parse("20240101T000000Z"),
            dtStart = ICalDateTime.fromZonedDateTime(ZonedDateTime.of(2024, 3, 6, 10, 0, 0, 0, zone))
        )
    }

    private fun displayAlarm(): ICalAlarm = ICalAlarm(
        action = AlarmAction.DISPLAY,
        trigger = Duration.ofMinutes(-15),
        triggerAbsolute = null,
        description = "Reminder",
        summary = null,
        uid = "ALARM-FIXED-UID-1"
    )

    /**
     * Strip VTIMEZONE inner content so snapshots aren't sensitive to JDK/OS
     * timezone database revisions. Keeps BEGIN:VTIMEZONE / TZID / END:VTIMEZONE
     * markers so placement and count are still verified.
     */
    private fun normalize(ics: String): String {
        val result = StringBuilder()
        var inVtimezone = false
        for (line in ics.lineSequence()) {
            when {
                line == "BEGIN:VTIMEZONE" -> {
                    inVtimezone = true
                    result.appendLine(line)
                }
                line == "END:VTIMEZONE" -> {
                    inVtimezone = false
                    result.appendLine(line)
                }
                inVtimezone && line.startsWith("TZID:") -> result.appendLine(line)
                inVtimezone -> Unit // skip DST transition details
                else -> result.appendLine(line)
            }
        }
        // Trim trailing blank lines so goldens don't need to track exact EOL count.
        return result.toString().trimEnd('\n', '\r') + "\n"
    }

    // ========== Golden snapshots (captured from pre-refactor implementation) ==========

    @Test
    fun `generate(event) with method null and non-UTC TZID matches golden`() {
        val actual = normalize(
            generator.generate(nyEvent(), method = null, preserveDtstamp = true, includeVTimezone = true)
        )
        val expected = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//DelegationTest//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:America/New_York
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:evt-1
            DTSTAMP:20240101T000000Z
            DTSTART;TZID=America/New_York:20240306T120000
            DTEND;TZID=America/New_York:20240306T130000
            SUMMARY:Event Summary
            DESCRIPTION:Event Description
            LOCATION:Event Location
            STATUS:CONFIRMED
            SEQUENCE:0
            END:VEVENT
            END:VCALENDAR

        """.trimIndent()
        assertEquals(expected, actual)
    }

    @Test
    fun `generate(event) with method REQUEST emits METHOD line`() {
        val actual = normalize(
            generator.generate(nyEvent(uid = "evt-req"), method = ITipMethod.REQUEST, preserveDtstamp = true, includeVTimezone = true)
        )
        val expected = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//DelegationTest//EN
            CALSCALE:GREGORIAN
            METHOD:REQUEST
            BEGIN:VTIMEZONE
            TZID:America/New_York
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:evt-req
            DTSTAMP:20240101T000000Z
            DTSTART;TZID=America/New_York:20240306T120000
            DTEND;TZID=America/New_York:20240306T130000
            SUMMARY:Event Summary
            DESCRIPTION:Event Description
            LOCATION:Event Location
            STATUS:CONFIRMED
            SEQUENCE:0
            END:VEVENT
            END:VCALENDAR

        """.trimIndent()
        assertEquals(expected, actual)
    }

    @Test
    fun `generate(event) with Apple VALARM extensions flows through`() {
        val actual = normalize(
            generator.generate(
                nyEvent(uid = "evt-alarm", alarms = listOf(displayAlarm())),
                method = null,
                preserveDtstamp = true,
                includeVTimezone = true
            )
        )
        val expected = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//DelegationTest//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:America/New_York
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:evt-alarm
            DTSTAMP:20240101T000000Z
            DTSTART;TZID=America/New_York:20240306T120000
            DTEND;TZID=America/New_York:20240306T130000
            SUMMARY:Event Summary
            DESCRIPTION:Event Description
            LOCATION:Event Location
            STATUS:CONFIRMED
            SEQUENCE:0
            BEGIN:VALARM
            UID:ALARM-FIXED-UID-1
            X-WR-ALARMUID:ALARM-FIXED-UID-1
            X-APPLE-DEFAULT-ALARM:FALSE
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR

        """.trimIndent()
        assertEquals(expected, actual)
    }

    @Test
    fun `generate(todo) with non-UTC TZID matches golden`() {
        val actual = normalize(
            generator.generate(tokyoTodo(), method = null, preserveDtstamp = true, includeVTimezone = true)
        )
        val expected = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//DelegationTest//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Tokyo
            END:VTIMEZONE
            BEGIN:VTODO
            UID:td-1
            DTSTAMP:20240101T000000Z
            DTSTART;TZID=Asia/Tokyo:20240306T090000
            DUE;TZID=Asia/Tokyo:20240306T180000
            SUMMARY:Todo Summary
            STATUS:NEEDS-ACTION
            SEQUENCE:0
            END:VTODO
            END:VCALENDAR

        """.trimIndent()
        assertEquals(expected, actual)
    }

    @Test
    fun `generate(journal) with non-UTC TZID matches golden`() {
        val actual = normalize(
            generator.generate(londonJournal(), method = null, preserveDtstamp = true, includeVTimezone = true)
        )
        val expected = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//DelegationTest//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Europe/London
            END:VTIMEZONE
            BEGIN:VJOURNAL
            UID:jr-1
            DTSTAMP:20240101T000000Z
            DTSTART;TZID=Europe/London:20240306T100000
            SUMMARY:Journal Summary
            STATUS:FINAL
            SEQUENCE:0
            END:VJOURNAL
            END:VCALENDAR

        """.trimIndent()
        assertEquals(expected, actual)
    }

    @Test
    fun `generate(event) with includeVTimezone false omits VTIMEZONE block`() {
        val actual = normalize(
            generator.generate(nyEvent(uid = "evt-no-tz"), method = null, preserveDtstamp = true, includeVTimezone = false)
        )
        val expected = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//DelegationTest//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:evt-no-tz
            DTSTAMP:20240101T000000Z
            DTSTART;TZID=America/New_York:20240306T120000
            DTEND;TZID=America/New_York:20240306T130000
            SUMMARY:Event Summary
            DESCRIPTION:Event Description
            LOCATION:Event Location
            STATUS:CONFIRMED
            SEQUENCE:0
            END:VEVENT
            END:VCALENDAR

        """.trimIndent()
        assertEquals(expected, actual)
    }
}
