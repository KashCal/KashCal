package org.onekash.kashcal.sync.parser.migration

import org.junit.Assert
import org.onekash.icaldav.model.ICalEvent
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper

/**
 * Utility for comparing ICalEvent (icaldav) with Event (KashCal entity).
 *
 * This is used in migration tests to verify that ICalEventMapper correctly
 * converts icaldav model to KashCal's database entity.
 */
object ParsedEventComparator {

    /**
     * Assert that an ICalEvent maps correctly to a KashCal Event entity.
     *
     * @param icalEvent The icaldav parsed event
     * @param event The KashCal entity (from ICalEventMapper)
     * @param message Context message for assertion failures
     */
    fun assertMappingEquivalent(icalEvent: ICalEvent, event: Event, message: String = "") {
        val prefix = if (message.isNotEmpty()) "$message: " else ""

        // UID
        Assert.assertEquals("${prefix}UID mismatch", icalEvent.uid, event.uid)

        // ImportId
        Assert.assertEquals("${prefix}importId mismatch", icalEvent.importId, event.importId)

        // Summary → title
        Assert.assertEquals("${prefix}title mismatch", icalEvent.summary ?: "Untitled", event.title)

        // Description
        Assert.assertEquals("${prefix}description mismatch", icalEvent.description, event.description)

        // Location
        Assert.assertEquals("${prefix}location mismatch", icalEvent.location, event.location)

        // isAllDay
        Assert.assertEquals("${prefix}isAllDay mismatch", icalEvent.isAllDay, event.isAllDay)

        // Start timestamp
        Assert.assertEquals("${prefix}startTs mismatch", icalEvent.dtStart.timestamp, event.startTs)

        // End timestamp (with all-day adjustment)
        val expectedEnd = icalEvent.effectiveEnd()
        val expectedEndTs = if (icalEvent.isAllDay && expectedEnd.timestamp > icalEvent.dtStart.timestamp) {
            expectedEnd.timestamp - 1
        } else {
            expectedEnd.timestamp
        }
        Assert.assertEquals("${prefix}endTs mismatch", expectedEndTs, event.endTs)

        // Timezone
        Assert.assertEquals(
            "${prefix}timezone mismatch",
            icalEvent.dtStart.timezone?.id,
            event.timezone
        )

        // Status
        Assert.assertEquals(
            "${prefix}status mismatch",
            icalEvent.status.toICalString(),
            event.status
        )

        // Transparency
        Assert.assertEquals(
            "${prefix}transp mismatch",
            icalEvent.transparency.toICalString(),
            event.transp
        )

        // Classification
        Assert.assertEquals(
            "${prefix}classification mismatch",
            icalEvent.classification?.toICalString() ?: "PUBLIC",
            event.classification
        )

        // RRULE
        Assert.assertEquals(
            "${prefix}rrule mismatch",
            icalEvent.rrule?.toICalString(),
            event.rrule
        )

        // Sequence
        Assert.assertEquals("${prefix}sequence mismatch", icalEvent.sequence, event.sequence)

        // RECURRENCE-ID → originalInstanceTime
        Assert.assertEquals(
            "${prefix}originalInstanceTime mismatch",
            icalEvent.recurrenceId?.timestamp,
            event.originalInstanceTime
        )
    }

    /**
     * Assert that two ICalEvents are equivalent (for round-trip testing).
     *
     * @param event1 First event
     * @param event2 Second event (after round-trip)
     * @param message Context message for assertion failures
     */
    fun assertRoundTripEquivalent(event1: ICalEvent, event2: ICalEvent, message: String = "") {
        val prefix = if (message.isNotEmpty()) "$message: " else ""

        // Core identity
        Assert.assertEquals("${prefix}UID mismatch", event1.uid, event2.uid)

        // Content
        Assert.assertEquals("${prefix}summary mismatch", event1.summary, event2.summary)
        Assert.assertEquals("${prefix}description mismatch", event1.description, event2.description)
        Assert.assertEquals("${prefix}location mismatch", event1.location, event2.location)

        // Time
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

        // Status
        Assert.assertEquals("${prefix}status mismatch", event1.status, event2.status)
        Assert.assertEquals("${prefix}transparency mismatch", event1.transparency, event2.transparency)

        // RRULE (compare string representation for equivalence)
        Assert.assertEquals(
            "${prefix}rrule mismatch",
            event1.rrule?.toICalString(),
            event2.rrule?.toICalString()
        )

        // EXDATE count
        Assert.assertEquals(
            "${prefix}exdates count mismatch",
            event1.exdates.size,
            event2.exdates.size
        )

        // Alarms count
        Assert.assertEquals(
            "${prefix}alarms count mismatch",
            event1.alarms.size,
            event2.alarms.size
        )

        // RECURRENCE-ID
        Assert.assertEquals(
            "${prefix}recurrenceId mismatch",
            event1.recurrenceId?.timestamp,
            event2.recurrenceId?.timestamp
        )

        // rawProperties count (X-properties preserved)
        Assert.assertEquals(
            "${prefix}rawProperties count mismatch",
            event1.rawProperties.size,
            event2.rawProperties.size
        )
    }

    /**
     * Assert timestamps are equivalent within tolerance.
     *
     * iCalendar DATE-TIME can lose sub-second precision on round-trip.
     * Allow 1-second tolerance.
     *
     * @param expected Expected timestamp in milliseconds
     * @param actual Actual timestamp in milliseconds
     * @param field Field name for error message
     */
    fun assertTimestampsEquivalent(expected: Long, actual: Long, field: String) {
        val tolerance = 1000L // 1 second
        Assert.assertTrue(
            "$field: expected $expected but was $actual (diff=${actual - expected}ms)",
            kotlin.math.abs(expected - actual) <= tolerance
        )
    }

    /**
     * Assert alarm lists are equivalent.
     *
     * @param expected Expected alarms
     * @param actual Actual alarms
     * @param message Context message
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
     * Assert that an exception event shares the master's UID.
     *
     * RFC 5545 requirement: Exception events have same UID, distinguished by RECURRENCE-ID.
     *
     * @param master Master recurring event
     * @param exception Exception event
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

    /**
     * Convert an ICalEvent to a KashCal Event entity using ICalEventMapper.
     *
     * Convenience method for tests.
     */
    fun toEntity(
        icalEvent: ICalEvent,
        rawIcal: String? = null,
        calendarId: Long = 1L,
        caldavUrl: String? = null,
        etag: String? = null
    ): Event {
        return ICalEventMapper.toEntity(icalEvent, rawIcal, calendarId, caldavUrl, etag)
    }
}
