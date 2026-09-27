package org.onekash.kashcal.sync.parser.migration

import org.junit.Assert
import org.onekash.icaldav.model.ICalEvent
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper

/**
 * Asserts that an icaldav [ICalEvent] and a Room [Event] agree, for the parser migration tests
 * that check [ICalEventMapper]'s conversion.
 */
object ParsedEventComparator {

    /**
     * Asserts that [event], mapped by [ICalEventMapper], carries [icalEvent]'s fields.
     *
     * @param message prefix for assertion failure messages
     */
    fun assertMappingEquivalent(icalEvent: ICalEvent, event: Event, message: String = "") {
        val prefix = if (message.isNotEmpty()) "$message: " else ""

        Assert.assertEquals("${prefix}UID mismatch", icalEvent.uid, event.uid)

        Assert.assertEquals("${prefix}importId mismatch", icalEvent.importId, event.importId)

        // SUMMARY → title, "Untitled" when absent.
        Assert.assertEquals("${prefix}title mismatch", icalEvent.summary ?: "Untitled", event.title)

        Assert.assertEquals("${prefix}description mismatch", icalEvent.description, event.description)

        Assert.assertEquals("${prefix}location mismatch", icalEvent.location, event.location)

        Assert.assertEquals("${prefix}isAllDay mismatch", icalEvent.isAllDay, event.isAllDay)

        Assert.assertEquals("${prefix}startTs mismatch", icalEvent.dtStart.timestamp, event.startTs)

        // All-day DTEND is exclusive; the entity stores the end 1 ms earlier.
        val expectedEnd = icalEvent.effectiveEnd()
        val expectedEndTs = if (icalEvent.isAllDay && expectedEnd.timestamp > icalEvent.dtStart.timestamp) {
            expectedEnd.timestamp - 1
        } else {
            expectedEnd.timestamp
        }
        Assert.assertEquals("${prefix}endTs mismatch", expectedEndTs, event.endTs)

        Assert.assertEquals(
            "${prefix}timezone mismatch",
            icalEvent.dtStart.timezone?.id,
            event.timezone
        )

        Assert.assertEquals(
            "${prefix}status mismatch",
            icalEvent.status.toICalString(),
            event.status
        )

        Assert.assertEquals(
            "${prefix}transp mismatch",
            icalEvent.transparency.toICalString(),
            event.transp
        )

        // CLASS defaults to PUBLIC.
        Assert.assertEquals(
            "${prefix}classification mismatch",
            icalEvent.classification?.toICalString() ?: "PUBLIC",
            event.classification
        )

        Assert.assertEquals(
            "${prefix}rrule mismatch",
            icalEvent.rrule?.toICalString(),
            event.rrule
        )

        Assert.assertEquals("${prefix}sequence mismatch", icalEvent.sequence, event.sequence)

        // RECURRENCE-ID → originalInstanceTime
        Assert.assertEquals(
            "${prefix}originalInstanceTime mismatch",
            icalEvent.recurrenceId?.timestamp,
            event.originalInstanceTime
        )
    }

    /**
     * Asserts that [event2], the round-tripped copy, matches [event1]: identity, text, times
     * within [assertTimestampsEquivalent]'s tolerance, status, RRULE, RECURRENCE-ID, and the
     * counts of EXDATEs, alarms and raw properties.
     *
     * @param message prefix for assertion failure messages
     */
    fun assertRoundTripEquivalent(event1: ICalEvent, event2: ICalEvent, message: String = "") {
        val prefix = if (message.isNotEmpty()) "$message: " else ""

        Assert.assertEquals("${prefix}UID mismatch", event1.uid, event2.uid)

        Assert.assertEquals("${prefix}summary mismatch", event1.summary, event2.summary)
        Assert.assertEquals("${prefix}description mismatch", event1.description, event2.description)
        Assert.assertEquals("${prefix}location mismatch", event1.location, event2.location)

        Assert.assertEquals("${prefix}isAllDay mismatch", event1.isAllDay, event2.isAllDay)
        assertTimestampsEquivalent(
            event1.dtStart.timestamp,
            event2.dtStart.timestamp,
            "${prefix}dtStart"
        )
        assertTimestampsEquivalent(
            event1.effectiveEnd().timestamp,
            event2.effectiveEnd().timestamp,
            "${prefix}dtEnd"
        )

        Assert.assertEquals("${prefix}status mismatch", event1.status, event2.status)
        Assert.assertEquals("${prefix}transparency mismatch", event1.transparency, event2.transparency)

        // Compared as strings.
        Assert.assertEquals(
            "${prefix}rrule mismatch",
            event1.rrule?.toICalString(),
            event2.rrule?.toICalString()
        )

        Assert.assertEquals(
            "${prefix}exdates count mismatch",
            event1.exdates.size,
            event2.exdates.size
        )

        Assert.assertEquals(
            "${prefix}alarms count mismatch",
            event1.alarms.size,
            event2.alarms.size
        )

        Assert.assertEquals(
            "${prefix}recurrenceId mismatch",
            event1.recurrenceId?.timestamp,
            event2.recurrenceId?.timestamp
        )

        // Unknown properties, X-properties included.
        Assert.assertEquals(
            "${prefix}rawProperties count mismatch",
            event1.rawProperties.size,
            event2.rawProperties.size
        )
    }

    /**
     * Asserts that two epoch-millisecond timestamps differ by at most one second, since an
     * iCalendar DATE-TIME drops sub-second precision on a round trip.
     *
     * @param field field name for the failure message
     */
    fun assertTimestampsEquivalent(expected: Long, actual: Long, field: String) {
        val tolerance = 1000L
        Assert.assertTrue(
            "$field: expected $expected but was $actual (diff=${actual - expected}ms)",
            kotlin.math.abs(expected - actual) <= tolerance
        )
    }

    /**
     * Asserts that both alarm lists have the same size and, index by index, the same action
     * and trigger in minutes.
     *
     * @param message prefix for assertion failure messages
     */
    fun assertAlarmsEquivalent(
        expected: List<org.onekash.icaldav.model.ICalAlarm>,
        actual: List<org.onekash.icaldav.model.ICalAlarm>,
        message: String = ""
    ) {
        val prefix = if (message.isNotEmpty()) "$message: " else ""
        Assert.assertEquals("${prefix}alarm count mismatch", expected.size, actual.size)

        expected.forEachIndexed { idx, expectedAlarm ->
            val actualAlarm = actual[idx]
            Assert.assertEquals(
                "${prefix}alarm[$idx] action mismatch",
                expectedAlarm.action,
                actualAlarm.action
            )
            Assert.assertEquals(
                "${prefix}alarm[$idx] trigger mismatch",
                expectedAlarm.trigger?.toMinutes(),
                actualAlarm.trigger?.toMinutes()
            )
        }
    }

    /**
     * Asserts that [exception] has [master]'s UID and a RECURRENCE-ID, which is what tells them
     * apart (RFC 5545).
     */
    fun assertExceptionSharesUid(master: ICalEvent, exception: ICalEvent) {
        Assert.assertEquals(
            "Exception UID must match master UID",
            master.uid,
            exception.uid
        )
        Assert.assertNotNull(
            "Exception must have RECURRENCE-ID",
            exception.recurrenceId
        )
    }

    /** Maps [icalEvent] with [ICalEventMapper.toEntity] and returns only the event. */
    fun toEntity(
        icalEvent: ICalEvent,
        rawIcal: String? = null,
        calendarId: Long = 1L,
        caldavUrl: String? = null,
        etag: String? = null
    ): Event {
        return ICalEventMapper.toEntity(icalEvent, rawIcal, calendarId, caldavUrl, etag).event
    }
}
