package org.onekash.kashcal.domain.scheduling

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event

/**
 * Tests [shouldNotifyAttendees], the predicate behind the inline "Save & notify" banner. It must
 * take the scheduling-significance decision from [SequenceBumper] (the source of truth) so the
 * banner matches the wire behavior, and add only the new-event case (never notifies), the
 * attendee-count gate, the attendee-set change and the removal case.
 */
class EditNotifyPredicateTest {

    private fun event(
        startTs: Long = 1_700_000_000_000L,
        endTs: Long = 1_700_003_600_000L,
        title: String = "Standup",
        location: String? = null,
        description: String? = null,
        rrule: String? = null,
        status: String = "CONFIRMED",
    ) = Event(
        uid = "uid-1",
        calendarId = 1L,
        title = title,
        startTs = startTs,
        endTs = endTs,
        location = location,
        description = description,
        rrule = rrule,
        isAllDay = false,
        status = status,
        transp = "OPAQUE",
        classification = "PUBLIC",
        dtstamp = 1_700_000_000_000L,
    )

    @Test
    fun `create (no old event) never notifies`() {
        assertFalse(shouldNotifyAttendees(old = null, new = event(), attendeeCount = 3))
    }

    @Test
    fun `zero attendees never notifies even on a timing change`() {
        val old = event()
        val new = event(startTs = old.startTs + 3_600_000L, endTs = old.endTs + 3_600_000L)
        assertFalse(shouldNotifyAttendees(old = old, new = new, attendeeCount = 0))
    }

    @Test
    fun `timing change with attendees notifies`() {
        val old = event()
        val new = event(startTs = old.startTs + 3_600_000L, endTs = old.endTs + 3_600_000L)
        assertTrue(shouldNotifyAttendees(old = old, new = new, attendeeCount = 2))
    }

    @Test
    fun `cosmetic-only change with attendees does not notify`() {
        val old = event(description = "Agenda A")
        val new = event(description = "Agenda B")
        assertFalse(shouldNotifyAttendees(old = old, new = new, attendeeCount = 2))
    }

    @Test
    fun `title change with attendees notifies`() {
        val old = event(title = "Standup")
        val new = event(title = "Daily Standup")
        assertTrue(shouldNotifyAttendees(old = old, new = new, attendeeCount = 2))
    }

    @Test
    fun `location change with attendees notifies`() {
        val old = event(location = "Room A")
        val new = event(location = "Room B")
        assertTrue(shouldNotifyAttendees(old = old, new = new, attendeeCount = 2))
    }

    @Test
    fun `recurrence change with attendees notifies`() {
        val old = event(rrule = "FREQ=WEEKLY")
        val new = event(rrule = "FREQ=DAILY")
        assertTrue(shouldNotifyAttendees(old = old, new = new, attendeeCount = 1))
    }

    @Test
    fun `cancellation with attendees notifies`() {
        val old = event(status = "CONFIRMED")
        val new = event(status = "CANCELLED")
        assertTrue(shouldNotifyAttendees(old = old, new = new, attendeeCount = 1))
    }

    @Test
    fun `adding an attendee notifies even with no scheduling change`() {
        // Adding a guest sends them a REQUEST (RFC 5546 §3.2.2.2 update), so the banner must
        // show, but it isn't a SequenceBumper change (ATTENDEE isn't in the §2.1.4 bump set),
        // so shouldBump stays false.
        val old = event()
        val new = old.copy() // identical scheduling fields
        assertFalse(SequenceBumper.shouldBump(old, new))
        assertTrue(
            shouldNotifyAttendees(old, new, attendeeCount = 2, attendeeSetChanged = true),
        )
    }

    @Test
    fun `an add edited back down to empty does not notify`() {
        // An add-only delta with no one left to invite: nothing to notify.
        val old = event()
        assertFalse(
            shouldNotifyAttendees(old, old.copy(), attendeeCount = 0, attendeeSetChanged = true),
        )
    }

    @Test
    fun `removing the last guest notifies even with zero attendees left`() {
        // The dropped guest still gets a CANCEL, so the banner must show though the resulting
        // set is empty.
        val old = event()
        assertTrue(
            shouldNotifyAttendees(old, old.copy(), attendeeCount = 0, attendeeRemoved = true),
        )
    }

    @Test
    fun `removing one of several guests notifies`() {
        val old = event()
        assertTrue(
            shouldNotifyAttendees(old, old.copy(), attendeeCount = 2, attendeeRemoved = true),
        )
    }

    @Test
    fun `a cosmetic edit to a zero-attendee event with no removal does not notify`() {
        // The empty-set gate is lifted only for a removal, never for a plain cosmetic edit on
        // a zero-attendee event.
        val old = event(description = "Agenda A")
        val new = event(description = "Agenda B")
        assertFalse(
            shouldNotifyAttendees(old, new, attendeeCount = 0, attendeeRemoved = false),
        )
    }

    @Test
    fun `attendee delta on create still does not notify`() {
        assertFalse(
            shouldNotifyAttendees(old = null, new = event(), attendeeCount = 3, attendeeSetChanged = true),
        )
    }

    @Test
    fun `cosmetic change with no attendee delta does not notify`() {
        val old = event(description = "Agenda A")
        val new = event(description = "Agenda B")
        assertFalse(
            shouldNotifyAttendees(old, new, attendeeCount = 2, attendeeSetChanged = false),
        )
    }

    @Test
    fun `delegates to SequenceBumper - no independent field list`() {
        // With attendees, the predicate must equal SequenceBumper.shouldBump for any old/new
        // pair; checked on a timing change and a cosmetic one.
        val old = event()
        val timing = event(startTs = old.startTs + 1000L, endTs = old.endTs + 1000L)
        assertTrue(
            shouldNotifyAttendees(old, timing, attendeeCount = 1) ==
                SequenceBumper.shouldBump(old, timing),
        )
        val cosmetic = event(description = "Added an agenda")
        assertTrue(
            shouldNotifyAttendees(old, cosmetic, attendeeCount = 1) ==
                SequenceBumper.shouldBump(old, cosmetic),
        )
    }
}
