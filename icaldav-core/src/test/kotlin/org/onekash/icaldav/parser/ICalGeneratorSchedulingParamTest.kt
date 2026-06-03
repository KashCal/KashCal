package org.onekash.icaldav.parser

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.onekash.icaldav.model.Attendee
import org.onekash.icaldav.model.AttendeeRole
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ITipMethod
import org.onekash.icaldav.model.Organizer
import org.onekash.icaldav.model.PartStat
import org.onekash.icaldav.model.ScheduleAgent
import org.onekash.icaldav.model.ScheduleForceSend
import org.onekash.icaldav.model.Transparency
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * RFC 6638 §7.1/§7.2: a client MUST NOT include SCHEDULE-AGENT or
 * SCHEDULE-FORCE-SEND in scheduling messages it sends (any METHOD-bearing
 * iTIP message). On plain resource-storage PUTs (no METHOD) the parameters
 * are legitimate server routing hints and must be preserved.
 *
 * Covers all four sites: ORGANIZER and ATTENDEE × SCHEDULE-AGENT and
 * SCHEDULE-FORCE-SEND.
 */
class ICalGeneratorSchedulingParamTest {

    private val generator = ICalGenerator()

    private fun eventWithScheduleParams(): ICalEvent {
        val zone = ZoneId.of("America/New_York")
        val start = ZonedDateTime.of(2026, 1, 15, 14, 0, 0, 0, zone)
        val end = ZonedDateTime.of(2026, 1, 15, 15, 0, 0, 0, zone)
        return ICalEvent(
            uid = "sched-param@example.test",
            importId = "sched-param@example.test",
            summary = "Sync",
            description = null,
            location = null,
            dtStart = ICalDateTime.fromZonedDateTime(start, false),
            dtEnd = ICalDateTime.fromZonedDateTime(end, false),
            duration = null,
            isAllDay = false,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = null,
            exdates = emptyList(),
            rdates = emptyList(),
            recurrenceId = null,
            alarms = emptyList(),
            categories = emptyList(),
            organizer = Organizer(
                email = "boss@example.test",
                name = "Boss",
                sentBy = null,
                scheduleAgent = ScheduleAgent.SERVER,
                scheduleForceSend = ScheduleForceSend.REQUEST
            ),
            attendees = listOf(
                Attendee(
                    email = "alice@example.test",
                    name = "Alice",
                    partStat = PartStat.NEEDS_ACTION,
                    role = AttendeeRole.REQ_PARTICIPANT,
                    rsvp = true,
                    scheduleAgent = ScheduleAgent.CLIENT,
                    scheduleForceSend = ScheduleForceSend.REQUEST
                )
            ),
            color = null,
            dtstamp = null,
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            rawProperties = emptyMap()
        )
    }

    @Test
    fun `METHOD-bearing message strips SCHEDULE-AGENT and FORCE-SEND from both lines`() {
        val ics = generator.generate(eventWithScheduleParams(), method = ITipMethod.REQUEST)

        assertTrue(ics.contains("METHOD:REQUEST"), "test fixture sanity: METHOD present")
        assertFalse(
            ics.contains("SCHEDULE-AGENT"),
            "iTIP message must not echo SCHEDULE-AGENT (ORGANIZER or ATTENDEE)"
        )
        assertFalse(
            ics.contains("SCHEDULE-FORCE-SEND"),
            "iTIP message must not echo SCHEDULE-FORCE-SEND (ORGANIZER or ATTENDEE)"
        )
        // The properties themselves still go out — only the params are stripped.
        assertTrue(ics.contains("ORGANIZER"), "ORGANIZER still emitted")
        assertTrue(ics.contains("ATTENDEE"), "ATTENDEE still emitted")
    }

    @Test
    fun `resource-storage PUT preserves SCHEDULE-AGENT and FORCE-SEND on both lines`() {
        val ics = generator.generate(eventWithScheduleParams(), method = null)

        assertFalse(ics.contains("METHOD:"), "storage PUT has no METHOD")
        // Two SCHEDULE-AGENT occurrences: ORGANIZER (SERVER) + ATTENDEE (CLIENT).
        assertTrue(ics.contains("SCHEDULE-AGENT=SERVER"), "ORGANIZER SCHEDULE-AGENT preserved")
        assertTrue(ics.contains("SCHEDULE-AGENT=CLIENT"), "ATTENDEE SCHEDULE-AGENT preserved")
        assertTrue(ics.contains("SCHEDULE-FORCE-SEND=REQUEST"), "SCHEDULE-FORCE-SEND preserved")
    }
}
